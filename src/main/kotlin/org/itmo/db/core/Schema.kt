package org.itmo.db.core

data class Schema(val columns: List<ColumnDefinition>) {
    fun column(name: String): ColumnDefinition? = columns.firstOrNull { it.name == name }
}
