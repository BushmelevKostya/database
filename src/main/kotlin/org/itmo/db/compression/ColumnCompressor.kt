package org.itmo.db.compression

import org.itmo.db.core.Column

interface ColumnCompressor {
    fun compress(column: Column<*>): CompressedColumn
    fun decompress(compressed: CompressedColumn): Column<*>
}
