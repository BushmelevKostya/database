package org.itmo.db.cache

import org.itmo.db.core.ColumnBlock

/**
 * Кэш хранит только неизменяемые блоки колонок, уже записанные в хранилище, поэтому записывать из него
 * нечего: вытеснить блок можно в любой момент, не теряя данных.
 */
interface CacheManager {
    /** Возвращает блок колонки из кэша, а при промахе читает его из хранилища. */
    fun loadBlock(tableName: String, columnName: String, blockIndex: Int): ColumnBlock

    /** Убирает из кэша все блоки таблицы (например, когда таблицу удалили). */
    fun deleteTable(tableName: String)

    fun isCached(tableName: String, columnName: String, blockIndex: Int): Boolean
}
