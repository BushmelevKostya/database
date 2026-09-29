package org.itmo.db.replication

import org.itmo.db.core.Row

interface ReplicationManager {
    val role: NodeRole

    fun registerReplica(node: Node)
    fun propagate(tableName: String, row: Row)
    fun promoteToMaster()
}
