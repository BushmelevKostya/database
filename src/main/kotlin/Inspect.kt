package org.itmo

import org.itmo.db.compression.DeflateColumnCompressor
import org.itmo.db.core.ColumnBlock
import org.itmo.db.core.DataType
import org.itmo.db.core.Schema
import org.itmo.db.storage.FileStorageEngine
import java.nio.file.Files
import java.nio.file.Path

/** Сколько значений колонки и строк таблицы печатать: в большой таблице весь вывод никто не прочтёт. */
private const val SHOWN_VALUES = 10L
private const val SHOWN_ROWS = 20L

/**
 * Показывает, что лежит в каталоге данных: схему, блоки колонок, журнал, значения, строки и размеры файлов.
 * Если аргумент — путь к файлу, печатает его записи как обычный текст, а с флагом `--bytes` —
 * ещё и байты файла с разбором формата.
 * Читает файлы напрямую и ничего не меняет. По умолчанию смотрит каталог `./data`.
 */
fun main(args: Array<String>) {
    val showBytes = "--bytes" in args
    val dir = args.firstOrNull { it != "--bytes" }?.let(Path::of) ?: Path.of("data")
    if (Files.isRegularFile(dir)) return if (showBytes) dumpFileBytes(dir) else printFileAsText(dir)

    println("Data directory: ${dir.toAbsolutePath()}")
    if (!Files.isDirectory(dir)) {
        println("  (каталога нет — база ещё не создавалась)")
        return
    }

    val storage = FileStorageEngine(dir, DeflateColumnCompressor())
    val tables = storage.listTables()
    if (tables.isEmpty()) println("  (таблиц нет)")
    tables.forEach { printTable(dir, storage, it) }

    tables.firstOrNull()?.let { table ->
        val column = checkNotNull(storage.load(table)).columns.first().name
        // Файл колонки появляется, когда набрался первый целый блок; до тех пор строки лежат только в журнале.
        val file = dir.resolve(table).resolve("$column.col").takeIf { Files.exists(it) } ?: dir.resolve(table).resolve("wal.log")
        println("\nЗаписи одного файла: ./gradlew inspect --args=\"$file\"")
        println("С байтами файла:     ./gradlew inspect --args=\"$file --bytes\"")
    }
}

/**
 * Содержимое таблицы так, как его соберёт база при открытии: целые блоки колонок плюс строки журнала.
 * Файлы только читаются: сбойный хвост, который база отбросит, здесь просто не учитывается.
 */
private class TableSnapshot(private val storage: FileStorageEngine, private val table: String, val schema: Schema) {
    val blockRows = storage.blockRows
    val blocks = schema.columns.map { storage.blockCount(table, it.name) }
    private val sealedRows = blocks.min().toLong() * blockRows
    private val log = storage.readLog(table)
    val pending: List<List<Any?>> = log?.rows?.drop(minOf(sealedRows - log.firstRow, log.rows.size.toLong()).toInt().coerceAtLeast(0)) ?: emptyList()
    val rows = sealedRows + pending.size
    private val lastBlocks = arrayOfNulls<Pair<Int, ColumnBlock>>(schema.columns.size)

    fun value(column: Int, row: Long): Any? {
        if (row >= sealedRows) return pending[(row - sealedRows).toInt()][column]
        val index = (row / blockRows).toInt()
        val block = lastBlocks[column]?.takeIf { it.first == index }?.second
            ?: storage.readBlock(table, schema.columns[column].name, index).also { lastBlocks[column] = index to it }
        return block.get((row % blockRows).toInt())
    }
}

private fun printTable(dir: Path, storage: FileStorageEngine, table: String) {
    val schema = checkNotNull(storage.load(table))
    val tableDir = dir.resolve(table)
    val snapshot = TableSnapshot(storage, table, schema)
    val rows = snapshot.rows
    val usages = columnUsages(tableDir, schema).associateBy { it.name }

    println("\n== таблица '$table': $rows строк, ${schema.columns.size} колонок")
    println("   schema.bin: ${Files.size(tableDir.resolve("schema.bin"))} байт")
    val sealed = snapshot.blocks.min()
    println("   в блоках колонок: ${sealed.toLong() * snapshot.blockRows} строк ($sealed × ${snapshot.blockRows}); " +
        "в журнале wal.log: ${snapshot.pending.size} строк (${logBytes(tableDir)} байт)")

    println("\n-- колонки: как хранятся (каждая колонка — отдельный файл из блоков, блоки только дописываются)")
    val header = listOf("колонка", "тип", "null?", "файл", "блоков", "на диске", "без сжатия", "значения (по порядку)")
    val lines = schema.columns.mapIndexed { i, definition ->
        val usage = usages[definition.name]
        listOf(
            definition.name,
            definition.type.name,
            if (definition.nullable) "да" else "нет",
            definition.name + ".col",
            "${snapshot.blocks[i]}",
            usage?.let { "${it.diskBytes} Б" } ?: "-",
            usage?.let { "${it.rawBytes} Б" } ?: "-",
            values(snapshot, i)
        )
    }
    printTable(header, lines)

    println("\n-- строки: как видит пользователь (строка = один индекс во всех колонках)")
    val rowLines = (0 until minOf(rows, SHOWN_ROWS)).map { row ->
        listOf(row.toString()) + schema.columns.mapIndexed { i, definition -> format(definition.type, snapshot.value(i, row)) }
    }
    printTable(listOf("#") + schema.columns.map { it.name }, rowLines)
    if (rows > SHOWN_ROWS) println("  ... ещё ${rows - SHOWN_ROWS} строк (показаны первые $SHOWN_ROWS)")

    if (snapshot.blocks.distinct().size > 1) {
        println("\n!! в колонках разное число блоков (${schema.columns.indices.joinToString { "${schema.columns[it].name}=${snapshot.blocks[it]}" }}): " +
            "запись блоков оборвалась сбоем, при открытии база отбросит лишние блоки, их строки есть в журнале")
    }
}

private fun values(snapshot: TableSnapshot, column: Int): String {
    val type = snapshot.schema.columns[column].type
    val shown = (0 until minOf(snapshot.rows, SHOWN_VALUES)).joinToString(", ") { format(type, snapshot.value(column, it)) }
    return if (snapshot.rows > SHOWN_VALUES) "[$shown, ... ещё ${snapshot.rows - SHOWN_VALUES}]" else "[$shown]"
}

private fun format(type: DataType, value: Any?): String = when {
    value == null -> "NULL"
    type == DataType.STRING -> "\"$value\""
    else -> value.toString()
}

internal fun printTable(header: List<String>, rows: List<List<String>>) {
    val widths = header.indices.map { i -> (rows.map { it[i].length } + header[i].length).max() }
    fun line(cells: List<String>) = println("  " + cells.indices.joinToString(" | ") { cells[it].padEnd(widths[it]) }.trimEnd())
    line(header)
    println("  " + widths.joinToString("-+-") { "-".repeat(it) })
    rows.forEach(::line)
}
