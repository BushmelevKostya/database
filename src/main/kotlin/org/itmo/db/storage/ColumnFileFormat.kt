package org.itmo.db.storage

import org.itmo.db.core.DataType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Где в файле колонки лежит блок: заголовок из 16 байт `строк | размер до сжатия | длина | crc32`, затем данные. */
internal class BlockRef(val offset: Long, val rows: Int, val originalSize: Long, val dataLength: Int, val crc: Int) {
    val size: Long get() = ColumnFileFormat.BLOCK_HEADER_BYTES + dataLength.toLong()
}

internal class ColumnFileScan(
    val name: String,
    val type: DataType,
    val blockRows: Int,
    val headerLength: Int,
    val blocks: List<BlockRef>,
    /** Конец последнего целого блока: всё, что дальше, — оборванная сбоем запись. */
    val end: Long
)

/**
 * Формат файла колонки: заголовок `COL2 | имя | тип | строк в блоке`, затем блоки подряд. Блок дописывается
 * одним вызовом записи, поэтому сбой оставляет в конце файла только обрывок, который при чтении отбрасывается.
 */
internal object ColumnFileFormat {
    const val MAGIC = 0x434F4C32
    const val LEGACY_MAGIC = 0x434F4C31
    const val BLOCK_HEADER_BYTES = 16
    private const val HEADER_READ_LIMIT = 4096

    fun header(name: String, type: DataType, blockRows: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeUTF(name)
            out.writeUTF(type.name)
            out.writeInt(blockRows)
        }
        return bytes.toByteArray()
    }

    fun blockHeader(rows: Int, originalSize: Long, dataLength: Int, crc: Int): ByteArray =
        ByteBuffer.allocate(BLOCK_HEADER_BYTES).putInt(rows).putInt(Math.toIntExact(originalSize)).putInt(dataLength).putInt(crc).array()

    /** Читает заголовок файла и заголовки блоков, сами данные блоков не читает. `null` — заголовок не дописан. */
    fun scan(path: Path): ColumnFileScan? = FileChannel.open(path, StandardOpenOption.READ).use { channel ->
        val size = channel.size()
        val head = ByteBuffer.allocate(minOf(size, HEADER_READ_LIMIT.toLong()).toInt())
        readFully(channel, head, 0)
        val input = DataInputStream(ByteArrayInputStream(head.array()))
        val magic = if (head.capacity() >= 4) input.readInt() else return null
        check(magic != LEGACY_MAGIC) {
            "$path записан в старом формате COL1 (колонка целиком). Удалите таблицу и загрузите данные заново"
        }
        check(magic == MAGIC) { "$path is not a column file" }
        val name: String
        val type: DataType
        val blockRows: Int
        try {
            name = input.readUTF()
            type = DataType.valueOf(input.readUTF())
            blockRows = input.readInt()
        } catch (_: java.io.EOFException) {
            return null
        }
        val headerLength = head.capacity() - input.available()

        val blocks = ArrayList<BlockRef>()
        var position = headerLength.toLong()
        val blockHeader = ByteBuffer.allocate(BLOCK_HEADER_BYTES)
        while (position + BLOCK_HEADER_BYTES <= size) {
            blockHeader.clear()
            readFully(channel, blockHeader, position)
            val rows = blockHeader.getInt(0)
            val originalSize = blockHeader.getInt(4).toLong()
            val dataLength = blockHeader.getInt(8)
            val crc = blockHeader.getInt(12)
            val torn = rows != blockRows || dataLength < 0 || originalSize < 0 ||
                position + BLOCK_HEADER_BYTES + dataLength > size
            if (torn) break
            blocks += BlockRef(position, rows, originalSize, dataLength, crc)
            position += BLOCK_HEADER_BYTES + dataLength
        }
        ColumnFileScan(name, type, blockRows, headerLength, blocks, position)
    }

    fun readFully(channel: FileChannel, buffer: ByteBuffer, position: Long) {
        var at = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, at)
            if (read < 0) break
            at += read
        }
    }

    fun writeFully(channel: FileChannel, buffer: ByteBuffer, position: Long) {
        var at = position
        while (buffer.hasRemaining()) at += channel.write(buffer, at)
    }
}
