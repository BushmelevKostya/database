package org.itmo.db.core

import org.itmo.db.cache.CacheManager
import org.itmo.db.query.Accumulator
import org.itmo.db.query.AggregateFunction
import org.itmo.db.query.AggregateResult
import org.itmo.db.query.AggregateSpec
import org.itmo.db.query.Predicate
import org.itmo.db.query.SortSpec
import org.itmo.db.storage.StorageEngine

/**
 * Строки только добавляются, id строки — её позиция, поэтому строка — это один и тот же индекс во всех колонках.
 *
 * Данные таблицы лежат в трёх местах. Первые [sealedRows] строк записаны в файлы колонок целыми блоками и читаются
 * через кэш блоков. Остальные строки (не больше [MEMTABLE_LIMIT_BLOCKS] блоков) лежат в памяти, в `memtable`, а
 * при [flushLocked] попадают в журнал; когда их набирается на целый блок, блок дописывается в файлы колонок, а
 * журнал сокращается до остатка. Поэтому flush стоит столько, сколько новых данных, а не сколько всего в таблице.
 *
 * [pending] — строки, найденные в журнале при открытии: они остались после остановки, не дойдя до блоков.
 * Все методы, меняющие состояние, вызываются под записывающей блокировкой [lock].
 */
internal class ColumnarTable(
    override val name: String,
    override val schema: Schema,
    private val storage: StorageEngine,
    private val cache: CacheManager,
    private val lock: DatabaseLock,
    private var sealedRows: Long,
    pending: List<List<Any?>>
) : Table {
    private val blockRows = storage.blockRows
    private var memtable: List<InMemoryColumn<Any>> = newMemtable(pending)

    /** Сколько строк буфера уже записано в журнал. */
    private var logged = pending.size

    private val memRows: Int get() = memtable[0].size.toInt()
    private val rowCount: Long get() = sealedRows + memRows

    override fun insert(row: Row): Long = lock.write {
        val values = toColumnValues(row)
        // Сначала освобождаем место: если запись на диск не удалась, строка ещё не вставлена и состояние чистое.
        if (memRows >= MEMTABLE_LIMIT_BLOCKS * blockRows) {
            flushLocked()
            storage.flush()
        }
        memtable.forEachIndexed { i, column -> column.append(values[i]) }
        rowCount - 1
    }

    override fun get(rowId: Long): Row? = lock.read {
        if (rowId in 0 until rowCount) readRow(rowId, readers()) else null
    }

    override fun scan(): Sequence<Row> {
        val rows = lock.read { rowCount }
        return sequence {
            val readers = readers()
            for (rowId in 0 until rows) yield(lock.read { readRow(rowId, readers) })
        }
    }

    override fun filter(predicate: Predicate): Sequence<Row> = scan().filter(predicate::test)

    /** NULL — наименьшее значение, поэтому при сортировке по возрастанию он идёт первым. Равные строки сохраняют порядок вставки. */
    override fun sort(specs: List<SortSpec>): Sequence<Row> {
        specs.forEach { definition(it.columnName) }
        if (specs.isEmpty()) return scan()
        val (order, readers) = lock.read {
            val rows = rowCount
            // Значения ключей читаем один раз подряд: сортировка потом сравнивает их из массивов, а не поднимает блоки из кэша.
            val keys = specs.map { spec ->
                val reader = reader(spec.columnName)
                Array(Math.toIntExact(rows)) { reader.get(it.toLong()) as Comparable<*>? }
            }
            val comparator = specs
                .mapIndexed { i, spec ->
                    val byValue = Comparator<Int> { a, b -> compareValues(keys[i][a], keys[i][b]) }
                    if (spec.ascending) byValue else byValue.reversed()
                }
                .reduce { first, next -> first.thenComparing(next) }
            (0 until Math.toIntExact(rows)).sortedWith(comparator) to readers()
        }
        return order.asSequence().map { rowId -> lock.read { readRow(rowId.toLong(), readers) } }
    }

    override fun aggregate(spec: AggregateSpec): AggregateResult {
        val countsRows = spec.columnName == AggregateSpec.ALL
        val type = if (countsRows) DataType.LONG else definition(spec.columnName).type
        if (countsRows) {
            require(spec.function == AggregateFunction.COUNT) { "'${AggregateSpec.ALL}' is only valid for COUNT" }
        }
        if (spec.function == AggregateFunction.SUM || spec.function == AggregateFunction.AVG) {
            require(type in NUMERIC) { "${spec.function} needs a numeric column, '${spec.columnName}' is $type" }
        }
        val groupBy = spec.groupBy?.also { definition(it) }

        return lock.read {
            if (countsRows && groupBy == null) return@read AggregateResult(rowCount, emptyMap())
            val values = if (countsRows) null else reader(spec.columnName)
            val keys = groupBy?.let { reader(it) }
            val total = Accumulator(spec.function, type)
            val groups = LinkedHashMap<Any?, Accumulator>()
            for (rowId in 0 until rowCount) {
                val value = if (values == null) true else values.get(rowId)
                total.add(value)
                if (keys != null) groups.getOrPut(keys.get(rowId)) { Accumulator(spec.function, type) }.add(value)
            }
            AggregateResult(total.result(), groups.mapValues { it.value.result() })
        }
    }

    /**
     * Записывает на диск всё, что накопилось в памяти: новые строки уходят в журнал, а если набрался целый блок,
     * блоки дописываются в файлы колонок. Вызывается под записывающей блокировкой; долговечность дают flush
     * хранилища: перед сокращением журнала он вызывается здесь, остальное закрепляет вызывающий.
     */
    fun flushLocked() {
        if (logged < memRows) {
            storage.appendLog(name, memtable.rowsFrom(logged))
            logged = memRows
        }
        if (memRows >= blockRows) seal()
    }

    /**
     * Переносит целые блоки из памяти в файлы колонок. Журнал сокращается только после того, как блоки
     * записаны на диск: при сбое строки остаются либо в журнале, либо в блоках, либо там и там (дубли отсекает
     * [ColumnarDatabase] при открытии).
     */
    private fun seal() {
        val blocks = memRows / blockRows
        val firstBlock = Math.toIntExact(sealedRows / blockRows)
        for ((i, definition) in schema.columns.withIndex()) {
            // Прошлая попытка могла записать блоки только в часть колонок и оборваться: откатываем её следы.
            storage.truncateBlocks(name, definition.name, firstBlock)
            for (block in 0 until blocks) {
                val from = block * blockRows
                storage.appendBlock(name, definition.name, ColumnBlock.of(definition.type, memtable[i].slice(from, from + blockRows)))
            }
        }
        storage.flush()
        val sealedNow = blocks * blockRows
        val leftover = memtable.rowsFrom(sealedNow)
        storage.resetLog(name, sealedRows + sealedNow, leftover)
        sealedRows += sealedNow
        memtable = newMemtable(leftover)
        logged = leftover.size
    }

    private fun newMemtable(rows: List<List<Any?>>): List<InMemoryColumn<Any>> {
        val columns = schema.columns.map { InMemoryColumn<Any>(it.name, it.type) }
        for (row in rows) columns.forEachIndexed { i, column -> column.append(row[i]) }
        return columns
    }

    /** Строки буфера начиная с [from]: каждая строка — значения по порядку колонок. */
    private fun List<InMemoryColumn<Any>>.rowsFrom(from: Int): List<List<Any?>> =
        (from until memRows).map { row -> map { it.get(row.toLong()) } }

    private fun toColumnValues(row: Row): List<Any?> {
        val unknown = row.values.keys.firstOrNull { schema.column(it) == null }
        require(unknown == null) { "Table '$name' has no column '$unknown'" }
        return schema.columns.map { definition ->
            val value = row[definition.name]
            when {
                value != null -> requireNotNull(definition.type.coerce(value)) {
                    "Column '${definition.name}' is ${definition.type}, got ${value::class.simpleName}"
                }
                else -> {
                    require(definition.nullable) { "Column '${definition.name}' is not nullable" }
                    null
                }
            }
        }
    }

    private fun readRow(rowId: Long, readers: List<ColumnReader>): Row =
        Row(schema.columns.indices.associate { schema.columns[it].name to readers[it].get(rowId) })

    private fun readers(): List<ColumnReader> = schema.columns.indices.map { ColumnReader(it) }

    private fun reader(columnName: String): ColumnReader = ColumnReader(schema.columns.indexOfFirst { it.name == columnName })

    private fun definition(columnName: String): ColumnDefinition =
        requireNotNull(schema.column(columnName)) { "Table '$name' has no column '$columnName'" }

    /**
     * Читает значения одной колонки по номеру строки и помнит последний блок: при чтении подряд к кэшу
     * обращаются один раз на блок, а не на каждое значение. Блок неизменяем, так что запомненный
     * остаётся верным, даже если кэш его вытеснит.
     */
    private inner class ColumnReader(private val index: Int) {
        private val columnName = schema.columns[index].name
        private var block: ColumnBlock? = null
        private var blockIndex = -1

        fun get(rowId: Long): Any? {
            if (rowId >= sealedRows) return memtable[index].get(rowId - sealedRows)
            val wanted = (rowId / blockRows).toInt()
            if (wanted != blockIndex) {
                block = cache.loadBlock(name, columnName, wanted)
                blockIndex = wanted
            }
            return checkNotNull(block).get((rowId % blockRows).toInt())
        }
    }

    companion object {
        /** Сколько блоков строк может копиться в памяти до принудительной записи на диск. */
        const val MEMTABLE_LIMIT_BLOCKS = 4

        private val NUMERIC = setOf(DataType.INT, DataType.LONG, DataType.DOUBLE)
    }
}
