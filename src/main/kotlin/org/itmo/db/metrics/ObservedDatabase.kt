package org.itmo.db.metrics

import org.itmo.db.core.Database
import org.itmo.db.core.Row
import org.itmo.db.core.Schema
import org.itmo.db.core.Table
import org.itmo.db.query.AggregateFunction
import org.itmo.db.query.AggregateResult
import org.itmo.db.query.AggregateSpec
import org.itmo.db.query.Predicate
import org.itmo.db.query.SortSpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Обёртка над [Database], которая записывает в [Metrics] каждую операцию. Поведение базы не меняется. */
class ObservedDatabase(private val delegate: Database, private val metrics: Metrics) : Database {
    private val tables = ConcurrentHashMap<String, ObservedTable>()

    override fun createTable(name: String, schema: Schema): ObservedTable {
        val table = delegate.createTable(name, schema)
        return ObservedTable(table, metrics, initialRows = 0).also { tables[table.name] = it }
    }

    override fun getTable(name: String): ObservedTable? {
        val table = delegate.getTable(name) ?: return null
        return tables.compute(table.name) { _, known ->
            if (known != null && known.isWrapperOf(table)) known else ObservedTable(table, metrics)
        }
    }

    override fun dropTable(name: String): Boolean {
        val dropped = delegate.dropTable(name)
        tables.keys.removeIf { it.equals(name, ignoreCase = true) }
        return dropped
    }

    override fun listTables(): List<String> = delegate.listTables()

    override fun flush() {
        val start = System.nanoTime()
        try {
            delegate.flush()
        } finally {
            metrics.record(Operation.FLUSH, System.nanoTime() - start)
        }
    }

    override fun close() = delegate.close()

    /** Таблицы, с которыми уже работали через эту обёртку, с актуальным числом строк. */
    fun observedTables(): List<ObservedTable> = tables.values.sortedBy { it.name }
}

/**
 * Обёртка над [Table]. Для ленивых последовательностей (scan, filter, sort) задержка — время от вызова
 * до конца обхода; если обход не доведён до конца, операция не записывается.
 */
class ObservedTable(
    private val delegate: Table,
    private val metrics: Metrics,
    initialRows: Long? = null
) : Table {
    private val rowCount = AtomicLong(initialRows ?: countRows(delegate))

    /** Число строк в таблице: известно без обхода таблицы. */
    val rows: Long get() = rowCount.get()

    override val name: String get() = delegate.name
    override val schema: Schema get() = delegate.schema

    internal fun isWrapperOf(table: Table) = delegate === table

    override fun insert(row: Row): Long {
        val start = System.nanoTime()
        val rowId = delegate.insert(row)
        rowCount.incrementAndGet()
        metrics.record(Operation.INSERT, System.nanoTime() - start)
        return rowId
    }

    override fun get(rowId: Long): Row? {
        val start = System.nanoTime()
        try {
            return delegate.get(rowId)
        } finally {
            metrics.record(Operation.GET, System.nanoTime() - start)
        }
    }

    override fun scan(): Sequence<Row> = observe(Operation.SCAN) { delegate.scan() }

    override fun filter(predicate: Predicate): Sequence<Row> = observe(Operation.FILTER) { delegate.filter(predicate) }

    override fun sort(specs: List<SortSpec>): Sequence<Row> = observe(Operation.SORT) { delegate.sort(specs) }

    override fun aggregate(spec: AggregateSpec): AggregateResult {
        val start = System.nanoTime()
        try {
            return delegate.aggregate(spec)
        } finally {
            metrics.record(Operation.AGGREGATE, System.nanoTime() - start, rows)
        }
    }

    private fun observe(operation: Operation, create: () -> Sequence<Row>): Sequence<Row> {
        val start = System.nanoTime()
        val source = create()
        return sequence {
            var count = 0L
            for (row in source) {
                count++
                yield(row)
            }
            metrics.record(operation, System.nanoTime() - start, count)
        }
    }

    private companion object {
        fun countRows(table: Table): Long =
            table.aggregate(AggregateSpec(AggregateSpec.ALL, AggregateFunction.COUNT)).value as Long
    }
}
