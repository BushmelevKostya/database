package org.itmo.db.core

interface Database : AutoCloseable {
    fun createTable(name: String, schema: Schema): Table
    fun getTable(name: String): Table?
    fun dropTable(name: String): Boolean
    fun listTables(): List<String>

    /** Делает все выполненные к этому моменту вставки устойчивыми на диске. */
    fun flush()
}
