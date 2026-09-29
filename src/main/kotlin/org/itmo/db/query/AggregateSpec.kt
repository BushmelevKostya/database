package org.itmo.db.query

data class AggregateSpec(
    val columnName: String,
    val function: AggregateFunction,
    val groupBy: String? = null
)
