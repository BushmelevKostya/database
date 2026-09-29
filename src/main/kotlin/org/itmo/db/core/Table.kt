package org.itmo.db.core

import org.itmo.db.query.AggregateResult
import org.itmo.db.query.AggregateSpec
import org.itmo.db.query.Predicate
import org.itmo.db.query.SortSpec

interface Table {
    val name: String
    val schema: Schema

    fun insert(row: Row): Long
    fun get(rowId: Long): Row?
    fun scan(): Sequence<Row>
    fun filter(predicate: Predicate): Sequence<Row>
    fun sort(specs: List<SortSpec>): Sequence<Row>
    fun aggregate(spec: AggregateSpec): AggregateResult
}
