package org.itmo.db.core

/** Приводит [value] к каноническому JVM-типу этого типа колонки или возвращает null, если значение не подходит. */
internal fun DataType.coerce(value: Any): Any? = when (this) {
    DataType.INT -> value as? Int
    DataType.LONG -> when (value) {
        is Long -> value
        is Int -> value.toLong()
        else -> null
    }
    DataType.DOUBLE -> when (value) {
        is Double -> value
        is Int -> value.toDouble()
        else -> null
    }
    DataType.STRING -> value as? String
    DataType.BOOLEAN -> value as? Boolean
}
