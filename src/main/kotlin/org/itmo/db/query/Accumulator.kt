package org.itmo.db.query

import org.itmo.db.core.DataType

/**
 * Сворачивает значения одного агрегата. NULL пропускаются; как в SQL, SUM/AVG/MIN/MAX
 * от пустого набора — null, а COUNT — 0. SUM целых — точный Long, переполнение приводит к ошибке;
 * AVG считается в double, поэтому на больших числах (например, 64-битных id) не падает.
 */
internal class Accumulator(
    private val function: AggregateFunction,
    private val type: DataType
) {
    private var count = 0L
    private var longSum = 0L
    private var doubleSum = 0.0
    private var min: Comparable<*>? = null
    private var max: Comparable<*>? = null

    fun add(value: Any?) {
        if (value == null) return
        count++
        when (function) {
            AggregateFunction.COUNT -> Unit
            AggregateFunction.SUM -> when (value) {
                is Int -> longSum = Math.addExact(longSum, value.toLong())
                is Long -> longSum = Math.addExact(longSum, value)
                is Double -> doubleSum += value
            }
            AggregateFunction.AVG -> doubleSum += (value as Number).toDouble()
            AggregateFunction.MIN -> if (min == null || compareValues(value as Comparable<*>, min) < 0) min = value as Comparable<*>
            AggregateFunction.MAX -> if (max == null || compareValues(value as Comparable<*>, max) > 0) max = value as Comparable<*>
        }
    }

    fun result(): Any? = when (function) {
        AggregateFunction.COUNT -> count
        AggregateFunction.SUM -> if (count == 0L) null else sum()
        AggregateFunction.AVG -> if (count == 0L) null else doubleSum / count
        AggregateFunction.MIN -> min
        AggregateFunction.MAX -> max
    }

    private fun sum(): Any = if (type == DataType.DOUBLE) doubleSum else longSum
}
