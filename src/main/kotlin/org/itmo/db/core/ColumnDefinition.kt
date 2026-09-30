package org.itmo.db.core

data class ColumnDefinition(
    val name: String,
    val type: DataType,
    val nullable: Boolean = true
)
