package org.itmo.db.cache

import org.itmo.db.core.ColumnBlock
import org.itmo.db.storage.StorageEngine

/**
 * Держит в памяти блоки колонок, пока их суммарный размер укладывается в [capacityBytes]; когда место
 * кончается, выбрасывается блок, к которому дольше всего не обращались. Так в памяти остаются
 * только «горячие» блоки, а таблица может быть сколько угодно больше кэша.
 * Блок читается из хранилища вне блокировки кэша, чтобы медленное чтение с диска не останавливало
 * остальных читателей.
 */
class LruCacheManager(
    private val storage: StorageEngine,
    private val capacityBytes: Long
) : CacheManager {
    private data class Key(val table: String, val column: String, val block: Int)

    /** Счётчики для наблюдаемости: попадания, промахи (чтения из хранилища), вытеснения и занятое место. */
    data class Stats(val hits: Long, val misses: Long, val evictions: Long, val usedBytes: Long, val blocks: Int)

    private val entries = LinkedHashMap<Key, ColumnBlock>(16, 0.75f, true)
    private var usedBytes = 0L
    private var hits = 0L
    private var misses = 0L
    private var evictions = 0L

    init {
        require(capacityBytes > 0) { "Cache capacity must be positive, got $capacityBytes" }
    }

    override fun loadBlock(tableName: String, columnName: String, blockIndex: Int): ColumnBlock {
        val key = Key(tableName, columnName, blockIndex)
        synchronized(this) {
            entries[key]?.let {
                hits++
                return it
            }
            misses++
        }
        val block = storage.readBlock(tableName, columnName, blockIndex)
        synchronized(this) {
            // Пока читали, тот же блок мог положить другой поток: берём его, чтобы не считать место дважды.
            entries[key]?.let { return it }
            entries[key] = block
            usedBytes += block.estimatedBytes
            // Единственный блок остаётся, даже если он больше всего кэша: иначе его нечем было бы вернуть.
            while (usedBytes > capacityBytes && entries.size > 1) evictOldest()
        }
        return block
    }

    @Synchronized
    override fun deleteTable(tableName: String) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.table == tableName) {
                usedBytes -= entry.value.estimatedBytes
                iterator.remove()
            }
        }
    }

    @Synchronized
    override fun isCached(tableName: String, columnName: String, blockIndex: Int): Boolean =
        entries.containsKey(Key(tableName, columnName, blockIndex))

    @Synchronized
    fun stats(): Stats = Stats(hits, misses, evictions, usedBytes, entries.size)

    private fun evictOldest() {
        val iterator = entries.entries.iterator()
        val oldest = iterator.next()
        usedBytes -= oldest.value.estimatedBytes
        iterator.remove()
        evictions++
    }
}
