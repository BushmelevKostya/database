package org.itmo.db.query

data class AggregateResult(
    val value: Any?,
    val groups: Map<Any?, Any?> = emptyMap()
)
