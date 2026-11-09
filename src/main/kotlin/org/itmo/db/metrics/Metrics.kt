package org.itmo.db.metrics

import java.util.concurrent.atomic.LongAdder

/** Операции, которые считаются в метриках. */
enum class Operation { INSERT, GET, SCAN, FILTER, SORT, AGGREGATE, FLUSH }

/** Накопленные значения одной операции на момент снимка. [buckets] — гистограмма задержек, см. [Histogram]. */
class OperationSnapshot(
    val calls: Long,
    val rows: Long,
    val totalNanos: Long,
    val lastNanos: Long,
    val buckets: LongArray
)

/**
 * Счётчики операций и гистограммы задержек. Потокобезопасны и дёшевы: на операцию приходится
 * несколько неблокирующих сложений, поэтому метрики можно оставлять включёнными под нагрузкой.
 */
class Metrics {
    private class Stat {
        val calls = LongAdder()
        val rows = LongAdder()
        val nanos = LongAdder()

        @Volatile
        var last = 0L
        val buckets = Array(Histogram.BUCKETS) { LongAdder() }
    }

    private val stats = Array(Operation.entries.size) { Stat() }

    /** Учитывает одну операцию: [rows] — сколько строк она затронула, [nanos] — сколько длилась. */
    fun record(operation: Operation, nanos: Long, rows: Long = 1) {
        val stat = stats[operation.ordinal]
        stat.calls.increment()
        stat.rows.add(rows)
        stat.nanos.add(nanos)
        stat.last = nanos
        stat.buckets[Histogram.index(nanos)].increment()
    }

    fun snapshot(): Map<Operation, OperationSnapshot> = Operation.entries.associateWith { operation ->
        val stat = stats[operation.ordinal]
        OperationSnapshot(
            stat.calls.sum(),
            stat.rows.sum(),
            stat.nanos.sum(),
            stat.last,
            LongArray(Histogram.BUCKETS) { stat.buckets[it].sum() }
        )
    }
}

/**
 * Гистограмма задержек в наносекундах: каждая степень двойки делится на 4 части,
 * поэтому погрешность процентиля не больше ~12%.
 */
object Histogram {
    const val BUCKETS = 160

    fun index(nanos: Long): Int {
        if (nanos < 4) return maxOf(nanos, 0).toInt()
        val exponent = 63 - java.lang.Long.numberOfLeadingZeros(nanos)
        val sub = ((nanos shr (exponent - 2)) and 3).toInt()
        return minOf((exponent - 1) * 4 + sub, BUCKETS - 1)
    }

    /** Середина диапазона, который покрывает корзина. */
    fun midpoint(index: Int): Long {
        if (index < 4) return index.toLong()
        val exponent = index / 4 + 1
        val width = 1L shl (exponent - 2)
        val lower = (4L + index % 4) shl (exponent - 2)
        return lower + width / 2
    }

    /** Процентиль [quantile] (0..1) по корзинам или 0, если значений нет. */
    fun percentile(buckets: LongArray, quantile: Double): Long {
        val total = buckets.sum()
        if (total == 0L) return 0
        val target = maxOf(1L, Math.ceil(total * quantile).toLong())
        var seen = 0L
        for (i in buckets.indices) {
            seen += buckets[i]
            if (seen >= target) return midpoint(i)
        }
        return midpoint(buckets.size - 1)
    }
}
