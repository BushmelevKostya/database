package org.itmo.db.cache

import org.itmo.db.core.Column

interface CacheManager {
    fun loadColumn(tableName: String, columnName: String): Column<*>
    fun deleteColumn(tableName: String, columnName: String)
    fun isCached(tableName: String, columnName: String): Boolean
}
