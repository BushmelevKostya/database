package org.itmo.db.core

import java.io.DataInputStream
import java.io.DataOutputStream

/** Единая запись одного значения в байты: так значения лежат и в блоках колонок, и в журнале таблицы. */
internal object ValueCodec {
    fun write(out: DataOutputStream, type: DataType, value: Any) {
        when (type) {
            DataType.INT -> out.writeInt(value as Int)
            DataType.LONG -> out.writeLong(value as Long)
            DataType.DOUBLE -> out.writeDouble(value as Double)
            DataType.BOOLEAN -> out.writeBoolean(value as Boolean)
            DataType.STRING -> {
                val utf8 = (value as String).toByteArray(Charsets.UTF_8)
                out.writeInt(utf8.size)
                out.write(utf8)
            }
        }
    }

    fun read(input: DataInputStream, type: DataType): Any = when (type) {
        DataType.INT -> input.readInt()
        DataType.LONG -> input.readLong()
        DataType.DOUBLE -> input.readDouble()
        DataType.BOOLEAN -> input.readBoolean()
        DataType.STRING -> {
            val length = input.readInt()
            require(length in 0..MAX_STRING_BYTES) { "Corrupted string length $length" }
            String(ByteArray(length).also(input::readFully), Charsets.UTF_8)
        }
    }

    private const val MAX_STRING_BYTES = 1 shl 28
}
