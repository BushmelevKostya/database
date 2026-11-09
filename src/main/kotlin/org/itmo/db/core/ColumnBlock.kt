package org.itmo.db.core

import java.io.DataOutputStream
import java.util.BitSet

/**
 * Неизменяемый кусок колонки: подряд идущие значения одного типа. Числа лежат в массивах примитивов, а строки —
 * одним массивом байт UTF-8 со смещениями (объект `String` создаётся при чтении значения). Поэтому блок занимает
 * в памяти примерно столько же, сколько в несжатом виде на диске, а не в несколько раз больше, как список объектов.
 * Блок — единица записи в файл колонки и единица кэша.
 */
class ColumnBlock private constructor(
    val type: DataType,
    val rows: Int,
    /** Какие значения не NULL; `null`, если непустые все. */
    private val present: BitSet?,
    private val data: Any
) {
    /** Оценка размера в памяти в байтах: по ней кэш решает, что вытеснять. */
    val estimatedBytes: Long = BLOCK_OVERHEAD + (present?.let { (rows / 8 + 16).toLong() } ?: 0L) + when (type) {
        DataType.INT -> 4L * rows
        DataType.LONG, DataType.DOUBLE -> 8L * rows
        DataType.BOOLEAN -> rows.toLong()
        DataType.STRING -> (data as StringData).let { it.bytes.size + 4L * it.offsets.size }
    }

    fun isPresent(index: Int): Boolean = present == null || present[index]

    fun get(index: Int): Any? {
        if (index !in 0 until rows) throw IndexOutOfBoundsException("Row $index of a block with $rows rows")
        if (!isPresent(index)) return null
        return when (type) {
            DataType.INT -> (data as IntArray)[index]
            DataType.LONG -> (data as LongArray)[index]
            DataType.DOUBLE -> (data as DoubleArray)[index]
            DataType.BOOLEAN -> (data as BooleanArray)[index]
            DataType.STRING -> (data as StringData).get(index)
        }
    }

    /** Записывает непустое значение строки [index] в формате [ValueCodec]; строка копируется как байты, без превращения в `String`. */
    internal fun writeValue(out: DataOutputStream, index: Int) {
        if (type == DataType.STRING) {
            val strings = data as StringData
            val length = strings.offsets[index + 1] - strings.offsets[index]
            out.writeInt(length)
            out.write(strings.bytes, strings.offsets[index], length)
        } else {
            ValueCodec.write(out, type, checkNotNull(get(index)))
        }
    }

    /** Строки подряд: значение строки `i` — байты `bytes[offsets[i] until offsets[i + 1]]`. */
    private class StringData(val bytes: ByteArray, val offsets: IntArray) {
        fun get(index: Int): String = String(bytes, offsets[index], offsets[index + 1] - offsets[index], Charsets.UTF_8)
    }

    /**
     * Собирает блок по значению за раз; значение, которое не задали, остаётся NULL. Значения строкового
     * блока нужно задавать в порядке возрастания индекса.
     */
    class Builder(private val type: DataType, private val rows: Int) {
        private val present = BitSet(rows)
        private val data: Any? = when (type) {
            DataType.INT -> IntArray(rows)
            DataType.LONG -> LongArray(rows)
            DataType.DOUBLE -> DoubleArray(rows)
            DataType.BOOLEAN -> BooleanArray(rows)
            DataType.STRING -> null
        }
        private val offsets = if (type == DataType.STRING) IntArray(rows + 1) else IntArray(0)
        private var bytes = ByteArray(if (type == DataType.STRING) rows * 8 else 0)
        private var length = 0
        private var next = 0

        fun set(index: Int, value: Any?) {
            if (value == null) return
            when (type) {
                DataType.INT -> (data as IntArray)[index] = value as Int
                DataType.LONG -> (data as LongArray)[index] = value as Long
                DataType.DOUBLE -> (data as DoubleArray)[index] = value as Double
                DataType.BOOLEAN -> (data as BooleanArray)[index] = value as Boolean
                DataType.STRING -> (value as String).toByteArray(Charsets.UTF_8).let { setUtf8(index, it, 0, it.size) }
            }
            present.set(index)
        }

        /** Задаёт строку, уже закодированную в UTF-8, без лишнего превращения в `String`. */
        fun setUtf8(index: Int, source: ByteArray, from: Int, count: Int) {
            check(type == DataType.STRING) { "Not a string block" }
            require(index >= next) { "String values must be set in increasing order" }
            // Пропущенные до этой строки значения — NULL: у них пустой диапазон байт.
            while (next < index) offsets[++next] = length
            if (length + count > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, length + count))
            System.arraycopy(source, from, bytes, length, count)
            length += count
            offsets[++next] = length
            present.set(index)
        }

        fun build(): ColumnBlock {
            val all = if (present.cardinality() == rows) null else present
            if (type != DataType.STRING) return ColumnBlock(type, rows, all, checkNotNull(data))
            while (next < rows) offsets[++next] = length
            return ColumnBlock(type, rows, all, StringData(bytes.copyOf(length), offsets))
        }
    }

    companion object {
        private const val BLOCK_OVERHEAD = 64L

        fun of(type: DataType, values: List<Any?>): ColumnBlock {
            val builder = Builder(type, values.size)
            values.forEachIndexed(builder::set)
            return builder.build()
        }
    }
}
