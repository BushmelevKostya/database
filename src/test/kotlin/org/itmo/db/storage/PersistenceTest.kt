package org.itmo.db.storage

import org.itmo.db.SAMPLE_USERS
import org.itmo.db.USERS
import org.itmo.db.compression.DeflateColumnCompressor
import org.itmo.db.core.ColumnarDatabase
import org.itmo.db.core.ColumnBlock
import org.itmo.db.core.DataType
import org.itmo.db.user
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersistenceTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `tables and rows survive a restart`() {
        ColumnarDatabase.open(dir).use { db ->
            val users = db.createTable("users", USERS)
            SAMPLE_USERS.forEach(users::insert)
        }

        ColumnarDatabase.open(dir).use { db ->
            assertEquals(listOf("users"), db.listTables())
            val users = checkNotNull(db.getTable("users"))
            assertEquals(USERS, users.schema)
            assertEquals(SAMPLE_USERS, users.scan().toList())
            assertEquals(5L, users.insert(user(6, "Egor", 50, null)))
        }

        ColumnarDatabase.open(dir).use { db ->
            assertEquals(6, checkNotNull(db.getTable("users")).scan().count())
        }
    }

    @Test
    fun `rows spread over blocks and the log survive a restart`() {
        ColumnarDatabase.open(dir, blockRows = 4).use { db ->
            val users = db.createTable("users", USERS)
            repeat(10) { users.insert(user(it + 1L, "user$it", it, null)) }
        }

        // 10 строк = 2 целых блока по 4 строки + 2 строки в журнале.
        val storage = FileStorageEngine(dir, DeflateColumnCompressor(), blockRows = 4)
        assertEquals(2, storage.blockCount("users", "id"))
        assertEquals(2, storage.readLog("users")?.rows?.size)

        ColumnarDatabase.open(dir, blockRows = 4).use { db ->
            val users = checkNotNull(db.getTable("users"))
            assertEquals((1L..10L).toList(), users.scan().map { it["id"] }.toList())
            assertEquals("user7", users.get(7)?.get("name"))
            assertEquals(10L, users.insert(user(11, "user10", 10, null)))
        }
    }

    @Test
    fun `flush appends to the column file and leaves the bytes already written untouched`() {
        ColumnarDatabase.open(dir, blockRows = 4).use { db ->
            val users = db.createTable("users", USERS)
            repeat(4) { users.insert(user(it + 1L, "u", 1, null)) }
            db.flush()
            val file = dir.resolve("users/id.col")
            val before = Files.readAllBytes(file)

            repeat(4) { users.insert(user(it + 5L, "u", 1, null)) }
            db.flush()

            val after = Files.readAllBytes(file)
            assertTrue(after.size > before.size)
            assertContentEquals(before, after.copyOf(before.size))
        }
    }

    @Test
    fun `memory holds a bounded number of rows even without flush`() {
        ColumnarDatabase.open(dir, blockRows = 4).use { db ->
            val users = db.createTable("users", USERS)
            repeat(40) { users.insert(user(it + 1L, "u", 1, null)) }

            assertTrue(FileStorageEngine(dir, DeflateColumnCompressor(), blockRows = 4).blockCount("users", "id") >= 5)
            assertEquals(40, users.scan().count())
        }
    }

    @Test
    fun `blocks that reached only some columns before a crash are dropped on open`() {
        ColumnarDatabase.open(dir, blockRows = 4).use { db ->
            SAMPLE_USERS.take(4).forEach(db.createTable("users", USERS)::insert)
        }
        // Имитируем сбой посреди записи блоков: в одной колонке уже есть второй блок, в остальных нет.
        val storage = FileStorageEngine(dir, DeflateColumnCompressor(), blockRows = 4)
        storage.appendBlock("users", "name", ColumnBlock.of(DataType.STRING, listOf("a", "b", "c", "d")))

        ColumnarDatabase.open(dir, blockRows = 4).use { db ->
            val users = checkNotNull(db.getTable("users"))
            assertEquals(SAMPLE_USERS.take(4), users.scan().toList())
            assertEquals(4L, users.insert(SAMPLE_USERS[4]))
            db.flush()
            assertEquals(SAMPLE_USERS, users.scan().toList())
        }
    }

    @Test
    fun `a log record torn by a crash is dropped on open`() {
        ColumnarDatabase.open(dir).use { db ->
            SAMPLE_USERS.take(3).forEach(db.createTable("users", USERS)::insert)
        }
        // Запись длиной 50 байт, из которой дописаны только два байта.
        Files.write(dir.resolve("users/wal.log"), byteArrayOf(0, 0, 0, 50, 1, 2), StandardOpenOption.APPEND)

        ColumnarDatabase.open(dir).use { db ->
            val users = checkNotNull(db.getTable("users"))
            assertEquals(SAMPLE_USERS.take(3), users.scan().toList())
            assertEquals(3L, users.insert(SAMPLE_USERS[3]))
        }
        ColumnarDatabase.open(dir).use { db ->
            assertEquals(SAMPLE_USERS.take(4), checkNotNull(db.getTable("users")).scan().toList())
        }
    }

    @Test
    fun `a log that was not shortened after its rows reached the blocks does not duplicate them`() {
        val storage = FileStorageEngine(dir, DeflateColumnCompressor(), blockRows = 4)
        storage.writeSchema("users", USERS)
        val rows = SAMPLE_USERS.map { row -> USERS.columns.map { row[it.name] } }
        storage.resetLog("users", 0, rows)
        // Сбой после записи блока, но до сокращения журнала: первые 4 строки есть и в блоке, и в журнале.
        USERS.columns.forEachIndexed { i, column ->
            storage.appendBlock("users", column.name, ColumnBlock.of(column.type, rows.take(4).map { it[i] }))
        }
        storage.flush()

        ColumnarDatabase.open(dir, blockRows = 4).use { db ->
            assertEquals(SAMPLE_USERS, checkNotNull(db.getTable("users")).scan().toList())
        }
    }

    @Test
    fun `table copied into the data directory becomes visible after refresh`() {
        ColumnarDatabase.open(dir).use { db ->
            SAMPLE_USERS.forEach(db.createTable("users", USERS)::insert)
            db.flush()
            val copy = Files.createDirectories(dir.resolve("users_copy"))
            Files.list(dir.resolve("users")).use { files -> files.forEach { Files.copy(it, copy.resolve(it.fileName)) } }
            assertEquals(null, db.getTable("users_copy"))

            db.refreshTables()

            assertEquals(SAMPLE_USERS, checkNotNull(db.getTable("users_copy")).scan().toList())
        }
    }
}
