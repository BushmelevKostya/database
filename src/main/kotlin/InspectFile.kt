package org.itmo

import org.itmo.db.compression.DeflateColumnCompressor
import org.itmo.db.core.DataType
import org.itmo.db.storage.FileStorageEngine
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.InflaterInputStream

private const val COLUMN_MAGIC = 0x434F4C32
private const val SCHEMA_MAGIC = 0x53434831
private const val LOG_MAGIC = 0x57414C31
private const val BLOCK_HEADER_BYTES = 16
private const val LOG_RECORD_HEADER_BYTES = 8
private const val BLOCKS_SHOWN = 3
private const val LOG_RECORDS_SHOWN = 5
private const val HEX_DUMP_LIMIT = 256
private const val VALUES_LIMIT = 20
private const val FIELD_HEX_BYTES = 8

/**
 * Печатает содержимое файла как обычный текст: по одной записи в строке, без служебных данных.
 * Файл колонки читается так же, как его читает сама база (с распаковкой).
 */
internal fun printFileAsText(file: Path) {
    val path = file.toAbsolutePath()
    val tableDir = checkNotNull(path.parent) { "Нет каталога таблицы у $path" }
    val storage = FileStorageEngine(checkNotNull(tableDir.parent) { "Нет каталога данных у $path" }, DeflateColumnCompressor())
    val table = tableDir.fileName.toString()
    val fileName = path.fileName.toString()
    when {
        fileName == "schema.bin" -> checkNotNull(storage.load(table)) { "Нет схемы в $path" }.columns.forEach {
            println("${it.name} ${it.type}${if (it.nullable) "" else " NOT NULL"}")
        }
        fileName.endsWith(".col") -> {
            val column = fileName.removeSuffix(".col")
            val count = storage.blockCount(table, column)
            check(count > 0) { "В $path нет ни одного целого блока" }
            for (index in 0 until count) {
                val block = storage.readBlock(table, column, index)
                for (row in 0 until block.rows) println(block.get(row) ?: "NULL")
            }
        }
        fileName == "wal.log" -> {
            val log = checkNotNull(storage.readLog(table)) { "Нет журнала в $path" }
            log.rows.forEach { row -> println(row.joinToString(" | ") { it?.toString() ?: "NULL" }) }
        }
        else -> println("Неизвестный файл: ожидается schema.bin, wal.log или <колонка>.col")
    }
}

/** Показывает, что физически лежит в одном файле: сырые байты и их разбор по полям. */
internal fun dumpFileBytes(file: Path) {
    val bytes = Files.readAllBytes(file)
    println("Файл: ${file.toAbsolutePath()} (${bytes.size} байт)")
    println("\n-- сырые байты")
    hexDump(bytes)
    if (bytes.size < 4) return println("\nФайл слишком короткий")

    when (ByteBuffer.wrap(bytes).int) {
        COLUMN_MAGIC -> dumpColumn(bytes)
        SCHEMA_MAGIC -> dumpSchema(bytes)
        LOG_MAGIC -> dumpLog(file, bytes)
        else -> println("\nНеизвестный формат: файл не начинается с магического числа COL2, WAL1 или SCH1")
    }
}

private fun dumpColumn(bytes: ByteArray) {
    println("\n-- разбор файла колонки (смещения в байтах, числа big-endian): заголовок и блоки, которые только дописываются")
    val layout = Layout(bytes)
    layout.field<Int>("magic", { "0x%08X (\"COL2\")".format(it) }) { int }
    val name = layout.field<String>("имя колонки", { "\"$it\"" }) { utf() }
    val type = DataType.valueOf(layout.field<String>("тип") { utf() })
    layout.field<Int>("строк в каждом блоке") { int }

    var index = 0
    var first: Pair<ByteArray, Int>? = null
    while (layout.remaining >= BLOCK_HEADER_BYTES) {
        val shown = index < BLOCKS_SHOWN
        layout.field<Int>("блок $index: строк", print = shown) { int }
        val originalSize = layout.field<Int>("блок $index: размер до сжатия", print = shown) { int }
        val length = layout.field<Int>("блок $index: длина сжатых данных", print = shown) { int }
        layout.field<Int>("блок $index: crc32 сжатых данных", { "0x%08X".format(it) }, shown) { int }
        if (layout.remaining < length) {
            println("  !! блок $index оборван сбоем (нужно $length байт, осталось ${layout.remaining}); база отбросит его при записи следующего")
            return
        }
        val data = layout.field<ByteArray>("блок $index: сжатые данные (Deflate)", { "${it.size} байт" }, shown) {
            ByteArray(length).also { get(it) }
        }
        if (index == 0) first = data to originalSize
        index++
    }
    if (index > BLOCKS_SHOWN) println("  ... и ещё ${index - BLOCKS_SHOWN} блоков")
    layout.checkEnd()

    first?.let { (data, originalSize) -> dumpPayload(name, type, data, originalSize) }
}

/** Распаковывает первый блок и показывает его раскладку: число строк, bitmap наличия, значения подряд. */
private fun dumpPayload(name: String, type: DataType, data: ByteArray, originalSize: Int) {
    val raw = InflaterInputStream(data.inputStream()).use { it.readAllBytes() }
    println("\n-- блок 0 после распаковки: ${raw.size} байт (заявлено в заголовке: $originalSize)")
    println("   раскладка: число строк | bitmap наличия | непустые значения подряд")
    hexDump(raw)

    println("\n-- разбор распакованного блока 0 колонки '$name' ($type)")
    val payload = Layout(raw)
    val rows = payload.field<Int>("число строк") { int }
    val bitmap = payload.field<ByteArray>("bitmap наличия", { presence(it, rows) }) {
        ByteArray((rows + 7) / 8).also { get(it) }
    }
    var printed = 0
    var total = 0
    val nulls = mutableListOf<Int>()
    for (row in 0 until rows) {
        if ((bitmap[row / 8].toInt() shr (row % 8)) and 1 == 0) {
            nulls += row
            continue
        }
        total++
        payload.field<Any>("значение строки $row", { if (it is String) "\"$it\"" else it.toString() }, printed++ < VALUES_LIMIT) {
            value(type)
        }
    }
    if (total > VALUES_LIMIT) println("  ... и ещё ${total - VALUES_LIMIT} значений")
    if (nulls.isNotEmpty()) println("  NULL в строках ${nulls.take(VALUES_LIMIT)}: места в файле не занимают, их отмечает только bitmap")
    payload.checkEnd()
}

/** Журнал таблицы: заголовок и записи `длина | crc32 | значения строки`; для разбора значений нужна схема из каталога таблицы. */
private fun dumpLog(file: Path, bytes: ByteArray) {
    println("\n-- разбор журнала (смещения в байтах, числа big-endian): строки, которые ещё не вошли в блоки колонок")
    val path = file.toAbsolutePath()
    val tableDir = checkNotNull(path.parent)
    val schema = checkNotNull(FileStorageEngine(checkNotNull(tableDir.parent), DeflateColumnCompressor()).load(tableDir.fileName.toString())) {
        "Нет схемы таблицы рядом с $path"
    }
    val layout = Layout(bytes)
    layout.field<Int>("magic", { "0x%08X (\"WAL1\")".format(it) }) { int }
    layout.field<Long>("номер первой строки журнала") { long }
    var index = 0
    while (layout.remaining >= LOG_RECORD_HEADER_BYTES) {
        val shown = index < LOG_RECORDS_SHOWN
        val length = layout.field<Int>("запись $index: длина", print = shown) { int }
        layout.field<Int>("запись $index: crc32", { "0x%08X".format(it) }, shown) { int }
        if (length < 0 || layout.remaining < length) {
            println("  !! запись $index оборвана сбоем; при открытии она и всё после неё отбрасываются")
            return
        }
        val record = layout.field<ByteArray>("запись $index: значения", { "${it.size} байт" }, false) { ByteArray(length).also { get(it) } }
        if (shown) {
            val values = Layout(record)
            for (column in schema.columns) {
                val present = values.field<Boolean>("  ${column.name}: есть значение") { get() != 0.toByte() }
                if (present) values.field<Any>("  ${column.name}: значение", { if (it is String) "\"$it\"" else it.toString() }) { value(column.type) }
            }
        }
        index++
    }
    if (index > LOG_RECORDS_SHOWN) println("  ... и ещё ${index - LOG_RECORDS_SHOWN} записей")
    println("  записей в журнале: $index")
    layout.checkEnd()
}

private fun dumpSchema(bytes: ByteArray) {
    println("\n-- разбор схемы (смещения в байтах, числа big-endian)")
    val layout = Layout(bytes)
    layout.field<Int>("magic", { "0x%08X (\"SCH1\")".format(it) }) { int }
    val count = layout.field<Int>("число колонок") { int }
    repeat(count) { i ->
        layout.field<String>("колонка $i: имя", { "\"$it\"" }) { utf() }
        layout.field<String>("колонка $i: тип") { utf() }
        layout.field<Boolean>("колонка $i: nullable") { get() != 0.toByte() }
    }
    layout.checkEnd()
}

/** Бит i равен 1, если в строке i есть значение; строка 0 — младший бит первого байта. */
private fun presence(bitmap: ByteArray, rows: Int): String {
    val shown = minOf(rows, 64)
    val bits = (0 until shown).joinToString("") { if ((bitmap[it / 8].toInt() shr (it % 8)) and 1 == 1) "1" else "0" }
    return bits + (if (rows > shown) "…" else "") + "  (строка 0 слева; 1 — значение есть, 0 — NULL)"
}

/** Читает файл подряд и печатает для каждого поля диапазон байт, их hex и расшифровку. */
private class Layout(bytes: ByteArray) {
    private val buf = ByteBuffer.wrap(bytes)

    fun <T> field(label: String, show: (T) -> String = { it.toString() }, print: Boolean = true, read: ByteBuffer.() -> T): T {
        val from = buf.position()
        val value = buf.read()
        if (print) {
            val raw = ByteArray(buf.position() - from).also { buf.get(from, it) }
            val hex = raw.take(FIELD_HEX_BYTES).joinToString(" ") { "%02x".format(it) } + if (raw.size > FIELD_HEX_BYTES) " …" else ""
            println("  %04x-%04x  %-26s %s = %s".format(from, buf.position() - 1, hex, label, show(value)))
        }
        return value
    }

    val remaining: Int get() = buf.remaining()

    fun checkEnd() {
        if (buf.hasRemaining()) println("  !! после разбора осталось ${buf.remaining()} непрочитанных байт")
    }
}

private fun ByteBuffer.utf(): String {
    val length = short.toInt() and 0xFFFF
    return String(ByteArray(length).also { get(it) }, Charsets.UTF_8)
}

private fun ByteBuffer.value(type: DataType): Any = when (type) {
    DataType.INT -> int
    DataType.LONG -> long
    DataType.DOUBLE -> double
    DataType.BOOLEAN -> get() != 0.toByte()
    DataType.STRING -> String(ByteArray(int).also { get(it) }, Charsets.UTF_8)
}

private fun hexDump(bytes: ByteArray) {
    val shown = minOf(bytes.size, HEX_DUMP_LIMIT)
    for (from in 0 until shown step 16) {
        val chunk = bytes.copyOfRange(from, minOf(from + 16, shown))
        val hex = chunk.joinToString(" ") { "%02x".format(it) }.padEnd(16 * 3 - 1)
        val text = chunk.joinToString("") { if (it.toInt() in 32..126) it.toInt().toChar().toString() else "." }
        println("  %04x  %s  %s".format(from, hex, text))
    }
    if (bytes.size > shown) println("  ... ещё ${bytes.size - shown} байт")
}
