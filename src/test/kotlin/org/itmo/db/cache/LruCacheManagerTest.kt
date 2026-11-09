package org.itmo.db.cache

import org.itmo.db.compression.DeflateColumnCompressor
import org.itmo.db.core.ColumnBlock
import org.itmo.db.core.ColumnDefinition
import org.itmo.db.core.ColumnarDatabase
import org.itmo.db.core.DataType
import org.itmo.db.core.Row
import org.itmo.db.core.Schema
import org.itmo.db.storage.FileStorageEngine
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LruCacheManagerTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the least recently used block is evicted to stay within the budget`() {
        val storage = FileStorageEngine(dir, DeflateColumnCompressor(), blockRows = 2)
        storage.writeSchema("t", Schema(listOf(ColumnDefinition("a", DataType.INT))))
        for (i in 0 until 3) storage.appendBlock("t", "a", ColumnBlock.of(DataType.INT, listOf(i * 10, i * 10 + 1)))
        val blockBytes = storage.readBlock("t", "a", 0).estimatedBytes
        val cache = LruCacheManager(storage, capacityBytes = blockBytes * 2 + 1)

        cache.loadBlock("t", "a", 0)
        cache.loadBlock("t", "a", 1)
        cache.loadBlock("t", "a", 0) // блок 1 теперь самый давний
        cache.loadBlock("t", "a", 2)

        assertTrue(cache.isCached("t", "a", 0))
        assertFalse(cache.isCached("t", "a", 1))
        assertTrue(cache.isCached("t", "a", 2))
        assertEquals(1, cache.stats().evictions)
        assertEquals(1, cache.stats().hits)
        assertEquals(20, cache.loadBlock("t", "a", 2).get(0))
        assertEquals(11, cache.loadBlock("t", "a", 1).get(1))
    }

    @Test
    fun `a table far larger than the cache is read correctly and the cache stays within its budget`() {
        val budget = 4096L
        val storage = FileStorageEngine(dir, DeflateColumnCompressor(), blockRows = 16)
        val cache = LruCacheManager(storage, budget)
        ColumnarDatabase(storage, cache).use { db ->
            val schema = Schema(listOf(ColumnDefinition("id", DataType.LONG), ColumnDefinition("name", DataType.STRING)))
            val table = db.createTable("t", schema)
            repeat(1000) { table.insert(Row(mapOf("id" to it.toLong(), "name" to "row-$it"))) }
            db.flush()

            assertEquals(1000, table.scan().count())
            assertEquals(listOf(0L, 500L, 999L), listOf(0L, 500L, 999L).map { table.get(it)?.get("id") })
            assertEquals("row-777", table.get(777)?.get("name"))
            assertTrue(cache.stats().evictions > 0)
            assertTrue(cache.stats().usedBytes <= budget, "${cache.stats().usedBytes} > $budget")
        }
    }
}
