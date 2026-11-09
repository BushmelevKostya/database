package org.itmo.db.compression

import org.itmo.db.core.DataType

/** Сжатый блок колонки; [originalSize] — размер данных до сжатия, по нему проверяют целостность. */
data class CompressedBlock(
    val type: DataType,
    val rows: Int,
    val data: ByteArray,
    val originalSize: Long
)
