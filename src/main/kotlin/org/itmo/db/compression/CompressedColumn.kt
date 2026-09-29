package org.itmo.db.compression

import org.itmo.db.core.DataType

data class CompressedColumn(
    val name: String,
    val type: DataType,
    val data: ByteArray,
    val originalSize: Long
)
