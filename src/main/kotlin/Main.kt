package org.itmo

import org.itmo.db.core.ColumnDefinition
import org.itmo.db.core.ColumnarDatabase
import org.itmo.db.core.DataType
import org.itmo.db.core.Database
import org.itmo.db.core.Row
import org.itmo.db.core.Schema
import org.itmo.db.core.Table
import org.itmo.db.query.AggregateFunction
import org.itmo.db.query.AggregateSpec
import org.itmo.db.query.SortSpec
import org.itmo.db.storage.FileStorageEngine
import java.nio.file.Files
import java.nio.file.Path

/**
 * Демо базы данных: запросы, файлы на диске, перезапуск.
 * Необязательный аргумент — каталог данных; по умолчанию `./data`. Данные сохраняются между запусками.
 */
fun main(args: Array<String>) {
    val dir = args.firstOrNull()?.let(Path::of) ?: Path.of("data")
    println("Data directory: ${dir.toAbsolutePath()}\n")

    ColumnarDatabase.open(dir).use { db -> queries(db) }

    println("\n== files on disk (schema, log of rows not yet in blocks, compressed column blocks of ${FileStorageEngine.DEFAULT_BLOCK_ROWS} rows)")
    Files.walk(dir).use { files ->
        files.filter { Files.isRegularFile(it) }.sorted().forEach {
            println("  ${dir.relativize(it)}: ${Files.size(it)} bytes")
        }
    }

    println("\n== after restart")
    ColumnarDatabase.open(dir).use { db ->
        val users = checkNotNull(db.getTable("users"))
        println("  rows: ${users.scan().count()}, row 2: ${users.get(2)}")
    }
}

private fun queries(db: Database) {
    val users = db.getTable("users")?.also {
        println("Table 'users' found: ${it.scan().count()} rows saved by a previous run\n")
    } ?: createUsers(db)

    println("== get(1)\n  ${users.get(1)}")
    println("== filter age > 28")
    users.filter { (it["age"] as Int?)?.let { age -> age > 28 } == true }.forEach { println("  $it") }
    println("== sort by city, age desc")
    users.sort(listOf(SortSpec("city"), SortSpec("age", ascending = false))).forEach { println("  $it") }
    println("== avg(age) group by city")
    println("  ${users.aggregate(AggregateSpec("age", AggregateFunction.AVG, groupBy = "city"))}")
    println("== count(*)")
    println("  ${users.aggregate(AggregateSpec(AggregateSpec.ALL, AggregateFunction.COUNT)).value}")
}

private fun createUsers(db: Database): Table {
    val users = db.createTable(
        "users",
        Schema(
            listOf(
                ColumnDefinition("id", DataType.LONG, nullable = false),
                ColumnDefinition("name", DataType.STRING, nullable = false),
                ColumnDefinition("age", DataType.INT),
                ColumnDefinition("city", DataType.STRING)
            )
        )
    )
    listOf(
        Triple("Anna", 31, "Moscow"),
        Triple("Boris", 25, "Kazan"),
        Triple("Vera", null, "Moscow"),
        Triple("Gleb", 42, "Kazan"),
        Triple("Dina", 29, "Moscow")
    ).forEachIndexed { i, (name, age, city) ->
        users.insert(Row(mapOf("id" to i + 1L, "name" to name, "age" to age, "city" to city)))
    }
    return users
}
