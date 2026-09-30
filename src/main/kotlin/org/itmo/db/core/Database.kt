package org.itmo.db.core

interface Database {
    fun createTable(name: String, schema: Schema): Table
    fun getTable(name: String): Table?
    fun dropTable(name: String): Boolean
    fun listTables(): List<String>
}
