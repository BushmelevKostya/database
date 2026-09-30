package org.itmo.db.query

import org.itmo.db.core.Row

fun interface Predicate {
    fun test(row: Row): Boolean
}
