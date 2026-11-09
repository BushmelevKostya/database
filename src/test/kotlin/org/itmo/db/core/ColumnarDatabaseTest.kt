package org.itmo.db.core

import org.itmo.db.SAMPLE_USERS
import org.itmo.db.USERS
import org.itmo.db.query.AggregateFunction
import org.itmo.db.query.AggregateSpec
import org.itmo.db.query.SortSpec
import org.itmo.db.user
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ColumnarDatabaseTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `filter, sort and aggregate handle nulls, ties and groups`() = ColumnarDatabase.open(dir).use { db ->
        val users = db.createTable("users", USERS)
        SAMPLE_USERS.forEach(users::insert)
        assertQueries(users)
    }

    @Test
    fun `queries give the same answers when rows are spread over blocks and memory`() = ColumnarDatabase.open(dir, blockRows = 2).use { db ->
        val users = db.createTable("users", USERS)
        SAMPLE_USERS.forEach(users::insert)
        db.flush() // 4 строки уходят в два блока, пятая остаётся в памяти
        assertQueries(users)
    }

    private fun assertQueries(users: Table) {
        assertEquals(
            listOf("Anna", "Gleb", "Dina"),
            users.filter { ((it["age"] as Int?) ?: 0) > 28 }.map { it["name"] }.toList()
        )
        assertEquals(
            listOf("Gleb", "Boris", "Anna", "Dina", "Vera"),
            users.sort(listOf(SortSpec("city"), SortSpec("age", ascending = false))).map { it["name"] }.toList()
        )
        assertEquals(
            listOf("Vera", "Boris", "Dina", "Anna", "Gleb"),
            users.sort(listOf(SortSpec("age"))).map { it["name"] }.toList()
        )

        val avg = users.aggregate(AggregateSpec("age", AggregateFunction.AVG, groupBy = "city"))
        assertEquals(31.75, avg.value)
        assertEquals<Map<Any?, Any?>>(mapOf("Moscow" to 30.0, "Kazan" to 33.5), avg.groups)
        assertEquals(127L, users.aggregate(AggregateSpec("age", AggregateFunction.SUM)).value)
        assertEquals(4L, users.aggregate(AggregateSpec("age", AggregateFunction.COUNT)).value)
        assertEquals(5L, users.aggregate(AggregateSpec(AggregateSpec.ALL, AggregateFunction.COUNT)).value)
        assertEquals("Anna", users.aggregate(AggregateSpec("name", AggregateFunction.MIN)).value)
    }

    @Test
    fun `avg of huge longs does not overflow but sum does`() {
        ColumnarDatabase.open(dir).use { db ->
            val ids = db.createTable("ids", Schema(listOf(ColumnDefinition("id", DataType.LONG))))
            ids.insert(Row(mapOf("id" to Long.MAX_VALUE)))
            ids.insert(Row(mapOf("id" to Long.MAX_VALUE)))

            assertEquals(Long.MAX_VALUE.toDouble(), ids.aggregate(AggregateSpec("id", AggregateFunction.AVG)).value)
            assertFailsWith<ArithmeticException> { ids.aggregate(AggregateSpec("id", AggregateFunction.SUM)) }
        }
    }

    @Test
    fun `rows that do not fit the schema are rejected and leave no trace`() = ColumnarDatabase.open(dir).use { db ->
        val users = db.createTable("users", USERS)

        assertFailsWith<IllegalArgumentException> { users.insert(Row(mapOf("id" to 1L, "name" to null))) }
        assertFailsWith<IllegalArgumentException> { users.insert(Row(mapOf("id" to 1L, "name" to "A", "age" to "old"))) }
        assertFailsWith<IllegalArgumentException> { users.insert(Row(mapOf("id" to 1L, "name" to "A", "email" to "x"))) }
        assertEquals(0, users.scan().count())

        // Int подходит для колонки LONG и хранится как Long; id первой строки по-прежнему 0.
        assertEquals(0L, users.insert(Row(mapOf("id" to 7, "name" to "A"))))
        assertEquals(7L, users.get(0)?.get("id"))
        assertNull(users.get(1))
    }

    @Test
    fun `a dropped table does not leak into a new table with the same name`() {
        ColumnarDatabase.open(dir).use { db ->
            val old = db.createTable("t", USERS)
            SAMPLE_USERS.forEach(old::insert)
            db.flush()
            assertEquals(true, db.dropTable("t"))
            assertEquals(false, db.dropTable("t"))

            val fresh = db.createTable("t", Schema(listOf(ColumnDefinition("id", DataType.LONG))))
            assertEquals(0, fresh.scan().count())
        }
        ColumnarDatabase.open(dir).use { db ->
            assertEquals(listOf("t"), db.listTables())
            assertEquals(0, checkNotNull(db.getTable("t")).scan().count())
        }
    }

    @Test
    fun `table names must be safe file names and unique ignoring case`() = ColumnarDatabase.open(dir).use { db ->
        db.createTable("users", USERS)

        assertFailsWith<IllegalArgumentException> { db.createTable("Users", USERS) }
        assertFailsWith<IllegalArgumentException> { db.createTable("../escape", USERS) }
        assertFailsWith<IllegalArgumentException> {
            db.createTable("t", Schema(listOf(ColumnDefinition("a", DataType.INT), ColumnDefinition("A", DataType.INT))))
        }
        assertEquals(listOf("users"), db.listTables())
    }
}
