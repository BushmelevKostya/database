package org.itmo.db

import org.itmo.db.core.ColumnDefinition
import org.itmo.db.core.DataType
import org.itmo.db.core.Row
import org.itmo.db.core.Schema

val USERS = Schema(
    listOf(
        ColumnDefinition("id", DataType.LONG, nullable = false),
        ColumnDefinition("name", DataType.STRING, nullable = false),
        ColumnDefinition("age", DataType.INT),
        ColumnDefinition("city", DataType.STRING)
    )
)

fun user(id: Long, name: String, age: Int?, city: String?) =
    Row(mapOf("id" to id, "name" to name, "age" to age, "city" to city))

val SAMPLE_USERS = listOf(
    user(1, "Anna", 31, "Moscow"),
    user(2, "Boris", 25, "Kazan"),
    user(3, "Vera", null, "Moscow"),
    user(4, "Gleb", 42, "Kazan"),
    user(5, "Dina", 29, "Moscow")
)
