package org.itmo.db.compression

import org.itmo.db.core.ColumnBlock
import org.itmo.db.core.DataType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeflateColumnCompressorTest {
    private val compressor = DeflateColumnCompressor()

    @Test
    fun `every type round-trips with nulls`() {
        val samples = mapOf(
            DataType.INT to listOf(1, null, Int.MIN_VALUE, Int.MAX_VALUE),
            DataType.LONG to listOf(null, Long.MIN_VALUE, 0L),
            DataType.DOUBLE to listOf(1.5, null, Double.MIN_VALUE, -0.0),
            DataType.BOOLEAN to listOf(true, false, null),
            DataType.STRING to listOf("", null, "привет", "line\nbreak")
        )
        for ((type, values) in samples) {
            val restored = compressor.decompress(compressor.compress(ColumnBlock.of(type, values)))

            assertEquals(type, restored.type)
            assertEquals(values, (0 until restored.rows).map { restored.get(it) }, "type $type")
        }
    }

    @Test
    fun `a repetitive block shrinks`() {
        val compressed = compressor.compress(ColumnBlock.of(DataType.STRING, List(1000) { "Moscow" }))

        assertTrue(compressed.data.size * 10 < compressed.originalSize, "${compressed.data.size} of ${compressed.originalSize}")
    }
}
