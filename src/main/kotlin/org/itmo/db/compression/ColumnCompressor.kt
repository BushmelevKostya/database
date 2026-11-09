package org.itmo.db.compression

import org.itmo.db.core.ColumnBlock

interface ColumnCompressor {
    fun compress(block: ColumnBlock): CompressedBlock
    fun decompress(compressed: CompressedBlock): ColumnBlock
}
