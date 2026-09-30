package org.itmo.db.storage

import org.itmo.db.core.Column
import org.itmo.db.core.Schema

interface StorageEngine {
    fun writeColumn(tableName: String, column: Column<*>)
    fun readColumn(tableName: String, columnName: String): Column<*>?
    fun load(tableName: String): Schema?
    fun flush()
}
