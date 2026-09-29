package org.itmo.db.core

interface Column<T> {
    val name: String
    val type: DataType
    val size: Long

    fun get(rowIndex: Long): T?
    fun append(value: T?)
}
