package org.itmo.db.compression

import org.itmo.db.core.ColumnBlock
import org.itmo.db.core.DataType
import org.itmo.db.core.ValueCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.BitSet
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * Блок раскладывается как `число строк | bitmap наличия | непустые значения`, затем сжимается Deflate.
 * Значения одного типа, лежащие подряд, и делают блок хорошо сжимаемым.
 */
class DeflateColumnCompressor(private val level: Int = Deflater.DEFAULT_COMPRESSION) : ColumnCompressor {

    override fun compress(block: ColumnBlock): CompressedBlock {
        val raw = encode(block)
        return CompressedBlock(block.type, block.rows, deflate(raw), raw.size.toLong())
    }

    override fun decompress(compressed: CompressedBlock): ColumnBlock =
        decode(compressed.type, compressed.rows, inflate(compressed.data, compressed.originalSize))

    private fun encode(block: ColumnBlock): ByteArray {
        val rows = block.rows
        val present = BitSet(rows)
        for (i in 0 until rows) {
            if (block.isPresent(i)) present.set(i)
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(rows)
            out.write(present.toByteArray().copyOf(bitmapSize(rows)))
            for (i in 0 until rows) {
                if (block.isPresent(i)) block.writeValue(out, i)
            }
        }
        return bytes.toByteArray()
    }

    private fun decode(type: DataType, expectedRows: Int, raw: ByteArray): ColumnBlock {
        val input = DataInputStream(ByteArrayInputStream(raw))
        val rows = input.readInt()
        check(rows == expectedRows) { "Corrupted block: header says $expectedRows rows, data has $rows" }
        val present = BitSet.valueOf(ByteArray(bitmapSize(rows)).also(input::readFully))
        val builder = ColumnBlock.Builder(type, rows)
        for (i in 0 until rows) {
            if (!present[i]) continue
            if (type == DataType.STRING) {
                val utf8 = ByteArray(input.readInt()).also(input::readFully)
                builder.setUtf8(i, utf8, 0, utf8.size)
            } else {
                builder.set(i, ValueCodec.read(input, type))
            }
        }
        return builder.build()
    }

    private fun bitmapSize(rows: Int) = (rows + 7) / 8

    private fun deflate(raw: ByteArray): ByteArray {
        val deflater = Deflater(level)
        try {
            val out = ByteArrayOutputStream()
            DeflaterOutputStream(out, deflater).use { it.write(raw) }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(data: ByteArray, originalSize: Long): ByteArray {
        val inflater = Inflater()
        try {
            val raw = InflaterInputStream(ByteArrayInputStream(data), inflater).use { it.readAllBytes() }
            check(raw.size.toLong() == originalSize) { "Corrupted column: expected $originalSize bytes, got ${raw.size}" }
            return raw
        } finally {
            inflater.end()
        }
    }
}
