package org.itmo.db.core

data class Row(val values: Map<String, Any?>) {
    operator fun get(columnName: String): Any? = values[columnName]
}
