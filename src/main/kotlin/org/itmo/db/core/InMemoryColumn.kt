package org.itmo.db.core

class InMemoryColumn<T : Any>(
    override val name: String,
    override val type: DataType
) : Column<T> {
    private val values = ArrayList<T?>()

    override val size: Long get() = values.size.toLong()

    override fun get(rowIndex: Long): T? {
        if (rowIndex !in 0 until size) throw IndexOutOfBoundsException("Row $rowIndex of column '$name', size $size")
        return values[rowIndex.toInt()]
    }

    override fun append(value: T?) {
        values.add(value)
    }

    /** Значения строк `from until to` как есть, без копирования. */
    fun slice(from: Int, to: Int): List<T?> = values.subList(from, to)
}
