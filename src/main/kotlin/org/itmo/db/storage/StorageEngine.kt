package org.itmo.db.storage

import org.itmo.db.core.ColumnBlock
import org.itmo.db.core.Schema

/**
 * Хранилище устроено как журнал с дозаписью. Колонка лежит в файле как последовательность одинаковых по
 * числу строк блоков, которые только дописываются в конец; строки, ещё не набравшие целый блок,
 * лежат в журнале таблицы. Уже записанные байты не переписываются, поэтому цена записи зависит от
 * объёма новых данных, а не от размера таблицы.
 */
interface StorageEngine {
    /** Сколько строк в одном блоке колонки. */
    val blockRows: Int

    fun writeSchema(tableName: String, schema: Schema)
    fun load(tableName: String): Schema?
    fun listTables(): List<String>
    fun deleteTable(tableName: String)

    /** Сколько целых блоков лежит в файле колонки (0, если файла ещё нет). */
    fun blockCount(tableName: String, columnName: String): Int

    /** Дописывает блок в конец файла колонки; блок должен содержать ровно [blockRows] строк. */
    fun appendBlock(tableName: String, columnName: String, block: ColumnBlock)

    fun readBlock(tableName: String, columnName: String, index: Int): ColumnBlock

    /** Оставляет в файле колонки первые [count] блоков: так откатывается запись блоков, прерванная сбоем. */
    fun truncateBlocks(tableName: String, columnName: String, count: Int)

    /** Журнал таблицы или `null`, если его ещё нет. Читает файл и ничего не меняет. */
    fun readLog(tableName: String): TableLog?

    /** Дописывает строки в конец журнала; каждая строка — значения по порядку колонок схемы. */
    fun appendLog(tableName: String, rows: List<List<Any?>>)

    /** Атомарно заменяет журнал новым: он начинается со строки [firstRow] и содержит [rows]. */
    fun resetLog(tableName: String, firstRow: Long, rows: List<List<Any?>>)

    /** Барьер долговечности: всё, что записано до вызова, переживёт сбой. */
    fun flush()
}

/** Строки таблицы, ещё не вошедшие в блоки: первая из них имеет номер [firstRow]. */
class TableLog(val firstRow: Long, val rows: List<List<Any?>>)
