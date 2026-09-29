package org.itmo.db.replication

import org.itmo.db.core.Database

interface Node {
    val id: String
    val role: NodeRole
    val database: Database
}
