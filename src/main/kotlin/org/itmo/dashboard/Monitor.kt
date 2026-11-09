package org.itmo.dashboard

import org.itmo.ColumnUsage
import org.itmo.columnUsages
import org.itmo.db.cache.LruCacheManager
import org.itmo.db.metrics.Histogram
import org.itmo.db.metrics.Metrics
import org.itmo.db.metrics.ObservedDatabase
import org.itmo.db.metrics.Operation
import org.itmo.db.metrics.OperationSnapshot
import org.itmo.db.storage.StorageEngine
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Раз в [intervalMillis] снимает метрики, считает скорости и задержки за прошедший интервал и хранит
 * историю для дашборда. Всё, что нужно странице, лежит в одном неизменяемом [State], который
 * публикуется целиком, поэтому HTTP-потокам не нужны блокировки.
 */
class Monitor(
    private val db: ObservedDatabase,
    private val metrics: Metrics,
    private val dir: Path,
    private val storage: StorageEngine,
    private val cache: LruCacheManager,
    private val cacheBytes: Long,
    private val intervalMillis: Long = 1000
) : AutoCloseable {
    /** Подпись текущей стадии теста, показывается в шапке страницы. */
    @Volatile
    var phase: String = "Запуск"

    val startedAt: Long = System.currentTimeMillis()

    private class OpSample(val rate: Double, val rowsRate: Double, val p50: Long, val p99: Long, val last: Long)

    private class Sample(
        val time: Long,
        val ops: Map<Operation, OpSample>,
        val cacheHits: Double,
        val cacheMisses: Double,
        val heap: Long,
        val cpu: Double,
        val rows: Long,
        val diskBytes: Long
    )

    private class TableUsage(val name: String, val rows: Long, val columns: List<ColumnUsage>)

    private class State(
        val samples: List<Sample>,
        val tables: List<TableUsage>,
        val cache: LruCacheManager.Stats,
        val totals: Map<Operation, OperationSnapshot>
    )

    @Volatile
    private var state = State(emptyList(), emptyList(), cache.stats(), metrics.snapshot())

    private var previousOps = metrics.snapshot()
    private var previousCache = cache.stats()
    private var previousNanos = System.nanoTime()
    private val os = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean

    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "dashboard-monitor").apply { isDaemon = true }
    }

    init {
        scheduler.scheduleAtFixedRate({ runCatching(::tick) }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        scheduler.shutdownNow()
    }

    private fun tick() {
        val now = System.nanoTime()
        val seconds = (now - previousNanos) / 1e9
        previousNanos = now

        val totals = metrics.snapshot()
        val ops = Operation.entries.associateWith { operation ->
            val current = totals.getValue(operation)
            val before = previousOps.getValue(operation)
            val delta = LongArray(Histogram.BUCKETS) { current.buckets[it] - before.buckets[it] }
            OpSample(
                rate = (current.calls - before.calls) / seconds,
                rowsRate = (current.rows - before.rows) / seconds,
                p50 = Histogram.percentile(delta, 0.5),
                p99 = Histogram.percentile(delta, 0.99),
                last = current.lastNanos
            )
        }
        previousOps = totals

        val cacheStats = cache.stats()
        val hits = (cacheStats.hits - previousCache.hits) / seconds
        val misses = (cacheStats.misses - previousCache.misses) / seconds
        previousCache = cacheStats

        val observed = db.observedTables().map { table ->
            val usages = runCatching { columnUsages(dir.resolve(table.name), table.schema) }.getOrDefault(emptyList())
            TableUsage(table.name, table.rows, usages)
        }
        // Таблицы, которые лежат на диске, но в этой сессии ещё не открывались: читаем только размеры файлов
        // (без блокировки базы, поэтому замеры идут и во время flush); число строк неизвестно.
        val known = observed.map { it.name }.toSet()
        val unopened = runCatching { storage.listTables() }.getOrDefault(emptyList()).filter { it !in known }.mapNotNull { name ->
            runCatching { storage.load(name)?.let { TableUsage(name, UNKNOWN_ROWS, columnUsages(dir.resolve(name), it)) } }.getOrNull()
        }
        val tables = (observed + unopened).sortedBy { it.name }
        val runtime = Runtime.getRuntime()
        val sample = Sample(
            time = System.currentTimeMillis(),
            ops = ops,
            cacheHits = hits,
            cacheMisses = misses,
            heap = runtime.totalMemory() - runtime.freeMemory(),
            cpu = os?.processCpuLoad ?: -1.0,
            rows = tables.sumOf { maxOf(it.rows, 0L) },
            diskBytes = tables.sumOf { table -> table.columns.sumOf { it.diskBytes } }
        )
        state = State((state.samples + sample).takeLast(HISTORY_LIMIT), tables, cacheStats, totals)
    }

    /** Состояние для страницы; в истории только замеры новее [since] (метка времени в миллисекундах). */
    fun stateJson(since: Long): String {
        val current = state
        val runtime = Runtime.getRuntime()
        return toJson(
            mapOf(
                "serverTime" to System.currentTimeMillis(),
                "startedAt" to startedAt,
                "phase" to phase,
                "dir" to dir.toString(),
                "jvm" to mapOf("heapMax" to runtime.maxMemory(), "threads" to Thread.activeCount()),
                "cache" to mapOf(
                    "capacity" to cacheBytes,
                    "hits" to current.cache.hits,
                    "misses" to current.cache.misses,
                    "evictions" to current.cache.evictions,
                    "usedBytes" to current.cache.usedBytes,
                    "blocks" to current.cache.blocks
                ),
                "totals" to current.totals.mapKeys { it.key.name }.mapValues { (_, snapshot) ->
                    mapOf("calls" to snapshot.calls, "rows" to snapshot.rows, "totalNanos" to snapshot.totalNanos)
                },
                "tables" to current.tables.map { table ->
                    mapOf(
                        "name" to table.name,
                        "rows" to table.rows,
                        "columns" to table.columns.map {
                            mapOf("name" to it.name, "type" to it.type.name.lowercase(), "diskBytes" to it.diskBytes, "rawBytes" to it.rawBytes)
                        }
                    )
                },
                "history" to current.samples.filter { it.time > since }.map { sample ->
                    mapOf(
                        "t" to sample.time,
                        "ops" to sample.ops.mapKeys { it.key.name }.mapValues { (_, op) ->
                            mapOf("rate" to op.rate, "rowsRate" to op.rowsRate, "p50" to op.p50, "p99" to op.p99, "last" to op.last)
                        },
                        "cacheHits" to sample.cacheHits,
                        "cacheMisses" to sample.cacheMisses,
                        "heap" to sample.heap,
                        "cpu" to sample.cpu,
                        "rows" to sample.rows,
                        "diskBytes" to sample.diskBytes
                    )
                }
            )
        )
    }

    private companion object {
        const val HISTORY_LIMIT = 600

        /** Число строк таблицы, которую в этой сессии не открывали: без чтения колонок его не узнать. */
        const val UNKNOWN_ROWS = -1L
    }
}
