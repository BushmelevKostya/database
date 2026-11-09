package org.itmo

import org.itmo.db.core.ColumnDefinition
import org.itmo.db.core.ColumnarDatabase
import org.itmo.db.core.DataType
import org.itmo.db.core.Row
import org.itmo.db.core.Schema
import org.itmo.db.core.Table
import org.itmo.db.query.AggregateFunction
import org.itmo.db.query.AggregateSpec
import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import kotlin.random.Random
import kotlin.system.exitProcess

private const val HELP = """Загрузка CSV/TSV в таблицу (типы колонок определяются по первым строкам).
Использование: ./gradlew importcsv --args="<файл> [параметры]"   (.gz читается без распаковки)

  table=<имя>       имя таблицы (по умолчанию из имени файла)
  dir=<путь>        каталог данных (по умолчанию ./data)
  delimiter=<x>     comma, semicolon, tab или один символ (по умолчанию по расширению и первой строке)
  quote=<x>         символ кавычек или none (по умолчанию " для csv; для tab кавычек нет)
  header=true       в первой строке имена колонок; header=false — заголовка нет, колонки c1, c2, ...
  columns=1,3,5-8   брать только эти колонки (номера с 1), по умолчанию все
  names=a,b,c       имена выбранных колонок по порядку (вместо c<номер> или заголовка)
  null=NA|-         ещё значения, которые считать NULL (пустая строка и \N всегда NULL)
  escapes=true      раскрывать \t \n \\ в значениях (формат TSV из ClickHouse)
  limit=100000      загрузить не больше стольких строк (0 — все). Память не ограничивает объём: блоки уходят на диск
  sample=10000      по скольким первым строкам определять типы
  replace=true      пересоздать таблицу, если она уже есть

Пример (датасет hits из ClickHouse, 133 колонки без заголовка):
  ./gradlew importcsv --args="/path/hits_v1.tsv header=false escapes=true limit=500000 columns=1,2,5-7,9,10,24,25"
"""

fun main(args: Array<String>) {
    try {
        runImport(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("Ошибка: ${e.message}")
        exitProcess(2)
    } catch (_: OutOfMemoryError) {
        System.err.println("Ошибка: не хватило памяти. Увеличьте кучу JVM или выберите меньше колонок (columns=).")
        exitProcess(3)
    }
}

private fun runImport(args: Array<String>) {
    if (args.isEmpty() || args.any { it == "help" || it == "--help" }) return print(HELP)
    val (positional, named) = args.partition { '=' !in it }
    require(positional.size == 1) { "Нужен один путь к файлу, получено ${positional.size}. help — описание." }
    val file = Path.of(positional[0])
    require(Files.isRegularFile(file)) { "Нет файла $file" }

    val options = Options(named.toTypedArray())
    val dir = options.string("dir")?.let(Path::of) ?: Path.of("data")
    val tableName = options.string("table")?.also { requireValidName(it) } ?: safeName(fileBaseName(file), "data")
    val delimiter = delimiterOf(options.string("delimiter"), file)
    val quote = quoteOf(options.string("quote"), delimiter)
    val hasHeader = options.string("header")?.let { requireNotNull(it.toBooleanStrictOrNull()) { "header: true или false" } } ?: true
    val wanted = options.string("columns")?.let(::parseColumnList)
    val givenNames = options.string("names")?.split(",")
    val nullTokens = (options.string("null")?.split("|") ?: emptyList()).toSet() + setOf("", "\\N")
    val escapes = options.string("escapes")?.let { requireNotNull(it.toBooleanStrictOrNull()) { "escapes: true или false" } } ?: false
    val limit = options.int("limit", 0)
    val sampleSize = options.int("sample", 10_000)
    val replace = options.string("replace")?.let { requireNotNull(it.toBooleanStrictOrNull()) { "replace: true или false" } } ?: false
    options.requireNoUnknown()

    val stream = CountingInputStream(Files.newInputStream(file).let { if (file.fileName.toString().endsWith(".gz")) GZIPInputStream(it, 1 shl 16) else it })
    BufferedReader(InputStreamReader(stream, Charsets.UTF_8), 1 shl 16).use { reader ->
        val records = RecordReader(reader, delimiter, quote)
        val first = requireNotNull(records.next()) { "Файл пуст" }
        val width = first.size
        val picked = (wanted ?: (1..width).toList()).also { list ->
            require(list.all { it in 1..width }) { "В файле $width колонок, а в columns есть номер вне 1..$width" }
        }
        require(givenNames == null || givenNames.size == picked.size) { "names: ${givenNames?.size} имён для ${picked.size} выбранных колонок" }
        val rawNames = givenNames ?: picked.map { if (hasHeader) first[it - 1] else "c$it" }
        val names = uniqueNames(rawNames)

        // Типы определяем по первым строкам; они же сразу идут в таблицу, файл читается один раз.
        val pending = ArrayList<List<String>>()
        if (!hasHeader) pending += first
        while (pending.size < sampleSize) pending += (records.next() ?: break).takeUnless(::isBlank) ?: continue
        val guesses = names.map { TypeGuess() }
        for (record in pending) {
            if (record.size != width) continue
            picked.forEachIndexed { i, column -> record[column - 1].takeUnless { it in nullTokens }?.let { guesses[i].add(unescapeIf(escapes, it)) } }
        }
        val schema = Schema(names.mapIndexed { i, name -> ColumnDefinition(name, guesses[i].type(), nullable = true) })

        ColumnarDatabase.open(dir).use { db ->
            val existing = db.listTables().firstOrNull { it.equals(tableName, ignoreCase = true) }
            if (existing != null) {
                require(replace) { "Таблица $existing уже есть в $dir: укажите table=<другое имя> или replace=true" }
                db.dropTable(existing)
            }
            val table = db.createTable(tableName, schema)
            println("Файл: $file (${humanBytes(Files.size(file))}), разделитель ${describe(delimiter)}, колонок в файле $width, берём ${names.size}")
            println("Таблица $tableName в ${dir.toAbsolutePath()}: " + schema.columns.joinToString { "${it.name} ${it.type.name.lowercase()}" })

            val stats = Stats()
            val started = System.nanoTime()
            fun load(record: List<String>) {
                if (record.size != width) {
                    stats.badWidth++
                    return
                }
                val values = LinkedHashMap<String, Any?>(names.size * 2)
                for (i in picked.indices) {
                    val text = record[picked[i] - 1]
                    if (text in nullTokens) {
                        values[names[i]] = null
                        continue
                    }
                    val value = unescapeIf(escapes, text)
                    stats.sourceBytes += value.utf8Size() + 1
                    val converted = convert(schema.columns[i].type, value)
                    if (converted == null) stats.coerced++
                    values[names[i]] = converted
                }
                table.insert(Row(values))
                stats.loaded++
                if (stats.loaded % 250_000L == 0L) println("  ... ${stats.loaded} строк, ${"%,.0f".format(stats.loaded * 1e9 / (System.nanoTime() - started))} строк/с")
            }

            for (record in pending) {
                if (limit > 0 && stats.loaded >= limit) break
                load(record)
            }
            while (limit == 0 || stats.loaded < limit) load((records.next() ?: break).takeUnless(::isBlank) ?: continue)
            val readNanos = System.nanoTime() - started
            val flushNanos = time { db.flush() }

            report(table, schema, dir.resolve(tableName), stats, stream.count, readNanos, flushNanos)
        }
    }
}

private class Stats {
    var loaded = 0L
    var badWidth = 0L
    var coerced = 0L
    var sourceBytes = 0L
}

private fun report(table: Table, schema: Schema, tableDir: Path, stats: Stats, bytesRead: Long, loadNanos: Long, flushNanos: Long) {
    println("\n== загружено ${stats.loaded} строк")
    if (stats.badWidth > 0) println("  пропущено строк с другим числом колонок: ${stats.badWidth}")
    if (stats.coerced > 0) println("  значений, не подошедших под тип колонки и записанных как NULL: ${stats.coerced} (если много — увеличьте sample=)")
    println("  чтение файла + вставка: ${seconds(loadNanos)} (${"%,.0f".format(stats.loaded * 1e9 / loadNanos)} строк/с, ${humanBytes((bytesRead * 1e9 / loadNanos).toLong())}/с исходного файла)")
    println("  flush (сжатие + запись): ${seconds(flushNanos)}")
    val runtime = Runtime.getRuntime()
    println("  куча JVM занята: ${humanBytes(runtime.totalMemory() - runtime.freeMemory())} из ${humanBytes(runtime.maxMemory())} (в памяти только буфер свежих строк и кэш блоков)")

    val usages = columnUsages(tableDir, schema)
    println("\n== хранение")
    printUsage(stats.loaded, usages)
    val disk = usages.sumOf { it.diskBytes }
    println("  Тот же текст этих колонок в исходном файле: ${humanBytes(stats.sourceBytes)}; БД занимает ${"%.0f".format(100.0 * disk / maxOf(1, stats.sourceBytes))}% от него.")

    println("\n== чтение обратно")
    val rows = table.aggregate(AggregateSpec(AggregateSpec.ALL, AggregateFunction.COUNT)).value
    val scan = time { table.scan().count() }
    println("  count(*) = $rows; scan всех строк: ${seconds(scan)} (${"%,.0f".format(stats.loaded * 1e9 / scan)} строк/с)")
    for (column in schema.columns.filter { it.type in setOf(DataType.INT, DataType.LONG, DataType.DOUBLE) }.take(3)) {
        var avg: Any? = null
        val nanos = time { avg = table.aggregate(AggregateSpec(column.name, AggregateFunction.AVG)).value }
        println("  avg(${column.name}) = $avg за ${seconds(nanos)}")
    }
    if (stats.loaded > 0) {
        val random = Random(1)
        val gets = 100_000
        val nanos = time { repeat(gets) { table.get(random.nextLong(stats.loaded)) } }
        println("  get(id) случайный, $gets раз: ${seconds(nanos)} (${"%,.0f".format(gets * 1e9 / nanos)}/с)")
    }
}

private inline fun time(block: () -> Unit): Long {
    val start = System.nanoTime()
    block()
    return System.nanoTime() - start
}

private fun seconds(nanos: Long) = "%.2f с".format(nanos / 1e9)

// ---------- определение типов ----------

/** Подбирает самый узкий тип, в который помещаются все увиденные значения колонки. */
private class TypeGuess {
    private var seen = false
    private var bool = true
    private var int = true
    private var long = true
    private var double = true
    private var integral = true

    fun add(value: String) {
        seen = true
        if (bool && value.lowercase().toBooleanStrictOrNull() == null) bool = false
        // «007» и «0123» — коды, а не числа: ведущие нули при превращении в число пропали бы.
        val leadingZero = value.length > 1 && value[0] == '0' && value[1].isDigit()
        if (leadingZero) {
            int = false; long = false; double = false; integral = false
            return
        }
        if (integral && !isIntegral(value)) integral = false
        if (int && value.toIntOrNull() == null) int = false
        if (long && value.toLongOrNull() == null) long = false
        if (double) {
            val parsed = value.toDoubleOrNull()
            if (parsed == null || !parsed.isFinite()) double = false
        }
    }

    fun type(): DataType = when {
        !seen -> DataType.STRING
        bool -> DataType.BOOLEAN
        int -> DataType.INT
        long -> DataType.LONG
        // Целое, не влезшее в Long (например, беззнаковый 64-битный id), в double потеряло бы цифры.
        integral -> DataType.STRING
        double -> DataType.DOUBLE
        else -> DataType.STRING
    }

    private fun isIntegral(value: String): Boolean {
        val digits = if (value.startsWith("-") || value.startsWith("+")) value.drop(1) else value
        return digits.isNotEmpty() && digits.all { it in '0'..'9' }
    }
}

private fun convert(type: DataType, text: String): Any? = when (type) {
    DataType.INT -> text.toIntOrNull()
    DataType.LONG -> text.toLongOrNull()
    DataType.DOUBLE -> text.toDoubleOrNull()?.takeIf { it.isFinite() }
    DataType.BOOLEAN -> text.lowercase().toBooleanStrictOrNull()
    DataType.STRING -> text
}

// ---------- разбор файла ----------

/** Читает записи CSV/TSV по одной; кавычки (если включены) могут содержать разделитель и перевод строки. */
private class RecordReader(private val reader: BufferedReader, private val delimiter: Char, private val quote: Char?) {
    fun next(): List<String>? {
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var started = false
        while (true) {
            val code = reader.read()
            if (code == -1) {
                if (!started) return null
                fields += field.toString()
                return fields
            }
            val ch = code.toChar()
            started = true
            when {
                inQuotes -> if (ch == quote) {
                    reader.mark(1)
                    val following = reader.read()
                    if (following == code) field.append(ch) else {
                        inQuotes = false
                        if (following != -1) reader.reset()
                    }
                } else field.append(ch)
                quote != null && ch == quote && field.isEmpty() -> inQuotes = true
                ch == delimiter -> {
                    fields += field.toString()
                    field.setLength(0)
                }
                ch == '\n' -> {
                    fields += field.toString()
                    return fields
                }
                ch == '\r' -> Unit
                else -> field.append(ch)
            }
        }
    }
}

private fun isBlank(record: List<String>) = record.size == 1 && record[0].isEmpty()

private fun unescapeIf(enabled: Boolean, text: String): String {
    if (!enabled || '\\' !in text) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (ch == '\\' && i + 1 < text.length) {
            when (val next = text[i + 1]) {
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                '0' -> out.append('\u0000')
                else -> out.append(next)
            }
            i += 2
        } else {
            out.append(ch)
            i++
        }
    }
    return out.toString()
}

/** Считает байты, прошедшие через поток, чтобы показать скорость чтения исходного файла. */
private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
}

private fun String.utf8Size(): Int = encodeToByteArray().size

// ---------- параметры и имена ----------

private fun delimiterOf(option: String?, file: Path): Char = when {
    option == null -> if (file.fileName.toString().removeSuffix(".gz").endsWith(".tsv")) '\t' else detectDelimiter(file)
    option.equals("comma", ignoreCase = true) -> ','
    option.equals("semicolon", ignoreCase = true) -> ';'
    option.equals("tab", ignoreCase = true) -> '\t'
    option.length == 1 -> option[0]
    else -> throw IllegalArgumentException("delimiter: comma, semicolon, tab или один символ, получено '$option'")
}

/** По первой строке выбирает самый частый из `,` `;` табуляции. */
private fun detectDelimiter(file: Path): Char {
    val stream = Files.newInputStream(file).let { if (file.fileName.toString().endsWith(".gz")) GZIPInputStream(it) else it }
    val line = stream.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readLine() ?: "" }
    return listOf(',', ';', '\t').maxByOrNull { sep -> line.count { it == sep } } ?: ','
}

private fun quoteOf(option: String?, delimiter: Char): Char? = when {
    option == null -> if (delimiter == '\t') null else '"'
    option.equals("none", ignoreCase = true) -> null
    option.length == 1 -> option[0]
    else -> throw IllegalArgumentException("quote: один символ или none, получено '$option'")
}

private fun describe(delimiter: Char) = if (delimiter == '\t') "табуляция" else "'$delimiter'"

private fun parseColumnList(text: String): List<Int> = text.split(",").flatMap { part ->
    val range = part.trim().split("-")
    val from = requireNotNull(range[0].toIntOrNull()) { "columns: ожидались номера и диапазоны (1,3,5-8), получено '$part'" }
    val to = if (range.size == 2) requireNotNull(range[1].toIntOrNull()) { "columns: неверный диапазон '$part'" } else from
    require(range.size <= 2 && from >= 1 && to >= from) { "columns: неверный диапазон '$part'" }
    (from..to).toList()
}.distinct()

private fun fileBaseName(file: Path): String = file.fileName.toString().removeSuffix(".gz").substringBeforeLast('.')

private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")

private fun requireValidName(name: String) = require(NAME.matches(name)) { "Имя '$name' не подходит: нужно ${NAME.pattern}" }

/** Приводит произвольный текст к допустимому имени таблицы или колонки (оно станет именем файла). */
private fun safeName(raw: String, fallback: String): String {
    var name = raw.trim().replace(Regex("[^A-Za-z0-9_]"), "_").ifEmpty { fallback }
    if (name[0] !in 'A'..'Z' && name[0] !in 'a'..'z' && name[0] != '_') name = "_$name"
    return name.take(64)
}

/** Имена колонок должны быть уникальны без учёта регистра. */
private fun uniqueNames(raw: List<String>): List<String> {
    val taken = HashSet<String>()
    return raw.mapIndexed { i, text ->
        val base = safeName(text, "c${i + 1}")
        var candidate = base
        var n = 2
        while (!taken.add(candidate.lowercase())) candidate = base.take(60) + "_" + n++
        candidate
    }
}
