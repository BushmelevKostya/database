package org.itmo

import org.itmo.dashboard.DashboardServer
import org.itmo.dashboard.Monitor
import org.itmo.db.cache.LruCacheManager
import org.itmo.db.compression.DeflateColumnCompressor
import org.itmo.db.core.ColumnDefinition
import org.itmo.db.core.ColumnarDatabase
import org.itmo.db.core.DataType
import org.itmo.db.core.Database
import org.itmo.db.core.Row
import org.itmo.db.core.Schema
import org.itmo.db.core.Table
import org.itmo.db.metrics.Metrics
import org.itmo.db.metrics.ObservedDatabase
import org.itmo.db.query.AggregateFunction
import org.itmo.db.query.AggregateSpec
import org.itmo.db.query.Predicate
import org.itmo.db.query.SortSpec
import org.itmo.db.storage.FileStorageEngine
import java.io.IOException
import java.nio.file.Path
import kotlin.system.exitProcess

/** Сколько строк печатает select: таблица может быть на миллионы строк, а терминал столько не прочтёт. */
private const val SELECT_LIMIT = 50
private const val IDLE_PHASE = "Консоль: ждёт команд"

private const val HELP = """Команды (регистр не важен; значения с пробелами — в двойных кавычках):
  tables                                    список таблиц
  create table <имя> (<колонка> <тип> [not null], ...)
                                            типы: int, long, double, string, boolean
  describe <таблица>                        схема таблицы
  drop table <имя>                          удалить таблицу
  insert <таблица> <колонка>=<значение> ... пропущенная колонка = null
  get <таблица> <id>                        строка по номеру (его печатает insert)
  select <таблица> [where <колонка> <оп> <значение>] [order by <колонка> [desc], ...]
                                            оп: =  !=  <  <=  >  >=
  count|sum|avg|min|max <таблица> [<колонка>] [by <колонка>]
  stats [<таблица>]                         размер файлов, сжатие, байт на строку
  flush                                     записать данные на диск
  load [параметры]                          нагрузка в фоне, ход виден на дашборде (load help — параметры)
  load stop                                 остановить нагрузку
  dashboard                                 адрес дашборда
  exit                                      выход

Пример:
  create table users (id long not null, name string not null, age int)
  insert users id=1 name="Anna Ivanova" age=31
  select users where age > 30 order by name desc
  avg users age

Нагрузка на настоящие данные (таблица должна быть загружена через importcsv):
  load from=hits seconds=60"""

/**
 * Интерактивная консоль: команды вводятся в реальном времени, после каждого изменения данные
 * сразу попадают на диск. Стрелки вверх/вниз листают историю команд (см. [LineInput]).
 * Вместе с консолью поднимается дашборд http://localhost:8765: он показывает таблицы каталога данных,
 * размеры файлов и каждую выполненную команду; команда `load` запускает нагрузку в фоне на той же базе.
 * Аргументы: каталог данных (по умолчанию `./data`), `port=<число>`, `cache=<МБ>` (бюджет кэша блоков
 * на всю сессию, по умолчанию 256), `--no-dashboard`.
 */
fun main(args: Array<String>) {
    val options = try {
        Options(args.filter { '=' in it }.toTypedArray())
    } catch (e: IllegalArgumentException) {
        System.err.println("Ошибка: ${e.message}")
        exitProcess(2)
    }
    val port = options.int("port", DEFAULT_DASHBOARD_PORT)
    val cacheBytes = options.int("cache", (ColumnarDatabase.DEFAULT_CACHE_BYTES shr 20).toInt()).toLong() shl 20
    options.requireNoUnknown()
    val dir = args.firstOrNull { '=' !in it && !it.startsWith("--") }?.let(Path::of) ?: Path.of("data")

    val storage = FileStorageEngine(dir, DeflateColumnCompressor())
    val cache = LruCacheManager(storage, cacheBytes)
    val metrics = Metrics()
    val engine = ColumnarDatabase(storage, cache)
    val db = ObservedDatabase(engine, metrics)
    val monitor = Monitor(db, metrics, dir, storage, cache, cacheBytes)
    monitor.phase = IDLE_PHASE

    var server: DashboardServer? = null
    if ("--no-dashboard" !in args) {
        try {
            server = DashboardServer(monitor, port)
        } catch (e: IllegalArgumentException) {
            println("Дашборд не запущен: ${e.message}")
        } catch (e: IOException) {
            println("Дашборд не запущен: ${e.message}")
        }
    }
    val url = server?.let { "http://localhost:${it.port}" }
    val load = LoadHost(db, engine, metrics, monitor, dir, url, IDLE_PHASE)

    println("Data directory: ${dir.toAbsolutePath()}")
    if (url != null) println("Дашборд: $url (открыть в браузере и смотреть, что происходит в базе)")
    println("Кэш блоков: ${cacheBytes shr 20} МБ. help — список команд, exit — выход")
    try {
        LineInput.open().use { input ->
            val shell = Shell(db, dir, load, url)
            while (true) {
                val line = input.read("db> ")?.trim()?.removeSuffix(";")?.trim() ?: break
                if (line.isEmpty()) continue
                if (line.lowercase() in setOf("exit", "quit")) break
                try {
                    shell.execute(line)
                } catch (e: RuntimeException) {
                    println("Ошибка: ${e.message}")
                }
            }
        }
    } finally {
        load.shutdown()
        server?.close()
        monitor.close()
        db.close()
    }
    println("Данные сохранены.")
}

/** Разбирает строку команды и выполняет её через публичные интерфейсы [Database] и [Table]. */
private class Shell(
    private val db: Database,
    private val dir: Path,
    private val load: LoadHost,
    private val dashboardUrl: String?
) {

    fun execute(line: String) {
        val words = tokenize(line)
        val args = words.drop(1)
        when (val command = words.first().lowercase()) {
            "help" -> println(HELP)
            "tables" -> tables()
            "create" -> createTable(line)
            "describe" -> describe(args)
            "drop" -> dropTable(args)
            "insert" -> insert(args)
            "get" -> get(args)
            "select" -> select(args)
            "count", "sum", "avg", "min", "max" -> aggregate(AggregateFunction.valueOf(command.uppercase()), args)
            "stats" -> stats(args)
            "load" -> load.command(args)
            "dashboard" -> println(dashboardUrl ?: "Дашборд выключен (порт занят или запуск с --no-dashboard).")
            "flush" -> {
                db.flush()
                println("Записано на диск.")
            }
            else -> println("Неизвестная команда '$command'. help — список команд.")
        }
    }

    private fun tables() {
        val names = db.listTables()
        if (names.isEmpty()) println("(таблиц нет)") else names.forEach(::println)
    }

    private fun createTable(line: String) {
        val match = requireNotNull(CREATE.matchEntire(line)) { "Формат: create table <имя> (<колонка> <тип> [not null], ...)" }
        val columns = match.groupValues[2].split(",").map { definition ->
            val words = definition.trim().split(Regex("\\s+"))
            val notNull = words.drop(2).map { it.lowercase() } == listOf("not", "null")
            require(words.size == 2 || (words.size == 4 && notNull)) { "Колонка задаётся как '<имя> <тип> [not null]', получено '${definition.trim()}'" }
            ColumnDefinition(words[0], parseType(words[1]), nullable = !notNull)
        }
        db.createTable(match.groupValues[1], Schema(columns))
        println("Таблица создана.")
    }

    private fun describe(args: List<String>) {
        val table = table(args.firstOrNull())
        table.schema.columns.forEach { println("${it.name} ${it.type}${if (it.nullable) "" else " NOT NULL"}") }
        println("строк: ${table.scan().count()}")
    }

    private fun dropTable(args: List<String>) {
        require(args.size == 2 && args[0].equals("table", ignoreCase = true)) { "Формат: drop table <имя>" }
        val name = db.listTables().firstOrNull { it.equals(args[1], ignoreCase = true) }
        println(if (name != null && db.dropTable(name)) "Таблица удалена." else "Нет таблицы '${args[1]}'.")
    }

    private fun insert(args: List<String>) {
        val table = table(args.firstOrNull())
        val values = args.drop(1).associate { pair ->
            val separator = pair.indexOf('=')
            require(separator > 0) { "Ожидалось <колонка>=<значение>, получено '$pair'" }
            val column = column(table, pair.substring(0, separator))
            column.name to parseValue(column.type, pair.substring(separator + 1))
        }
        val rowId = table.insert(Row(values))
        db.flush()
        println("Вставлено, id строки = $rowId.")
    }

    private fun stats(args: List<String>) {
        db.flush()
        val names = if (args.isEmpty()) db.listTables() else listOf(table(args[0]).name)
        if (names.isEmpty()) println("(таблиц нет)")
        for (name in names) {
            val table = table(name)
            val rows = table.aggregate(AggregateSpec(AggregateSpec.ALL, AggregateFunction.COUNT)).value as Long
            println("== $name: строк $rows")
            printUsage(rows, columnUsages(dir.resolve(name), table.schema))
            println("  журнал wal.log: ${humanBytes(logBytes(dir.resolve(name)))}; в нём строки, которые ещё не набрали целый блок из ${FileStorageEngine.DEFAULT_BLOCK_ROWS}")
        }
    }

    private fun get(args: List<String>) {
        require(args.size == 2) { "Формат: get <таблица> <id>" }
        val table = table(args[0])
        val rowId = requireNotNull(args[1].toLongOrNull()) { "id должен быть числом, получено '${args[1]}'" }
        val row = table.get(rowId)
        if (row == null) println("Нет строки с id $rowId.") else table.schema.columns.forEach { println("${it.name} = ${show(row[it.name])}") }
    }

    private fun select(args: List<String>) {
        val table = table(args.firstOrNull())
        val rest = args.drop(1)
        val orderAt = rest.indexOfFirst { it.equals("order", ignoreCase = true) }
        val whereWords = (if (orderAt >= 0) rest.take(orderAt) else rest)
        val orderWords = if (orderAt >= 0) rest.drop(orderAt) else emptyList()

        val predicate = if (whereWords.isEmpty()) null else parseWhere(table, whereWords)
        val specs = if (orderWords.isEmpty()) emptyList() else parseOrder(table, orderWords)

        val rows = when {
            specs.isEmpty() -> if (predicate == null) table.scan() else table.filter(predicate)
            else -> table.sort(specs).let { sorted -> if (predicate == null) sorted else sorted.filter(predicate::test) }
        }
        val shown = ArrayList<Row>()
        var total = 0L
        for (row in rows) {
            if (shown.size < SELECT_LIMIT) shown += row
            total++
        }

        val names = table.schema.columns.map { it.name }
        printTable(names, shown.map { row -> names.map { show(row[it]) } })
        println(if (total > shown.size) "строк: $total (показаны первые ${shown.size}; сузьте выборку через where)" else "строк: $total")
    }

    private fun aggregate(function: AggregateFunction, args: List<String>) {
        val usage = "Формат: ${function.name.lowercase()} <таблица> [<колонка>] [by <колонка>]"
        require(args.isNotEmpty()) { usage }
        val table = table(args[0])
        val rest = args.drop(1)
        val byAt = rest.indexOfFirst { it.equals("by", ignoreCase = true) }
        val columnWords = if (byAt >= 0) rest.take(byAt) else rest
        require(columnWords.size <= 1 && (byAt < 0 || byAt == rest.size - 2)) { usage }

        val columnName = when {
            columnWords.isEmpty() -> {
                require(function == AggregateFunction.COUNT) { "Для ${function.name.lowercase()} нужна колонка. $usage" }
                AggregateSpec.ALL
            }
            columnWords[0] == AggregateSpec.ALL -> AggregateSpec.ALL
            else -> column(table, columnWords[0]).name
        }
        val groupBy = if (byAt >= 0) column(table, rest[byAt + 1]).name else null

        val result = table.aggregate(AggregateSpec(columnName, function, groupBy))
        if (groupBy != null) {
            printTable(listOf(groupBy, "${function.name.lowercase()}($columnName)"), result.groups.map { listOf(show(it.key), show(it.value)) })
            println("всего: ${show(result.value)}")
        } else {
            println(show(result.value))
        }
    }

    private fun parseWhere(table: Table, words: List<String>): Predicate {
        require(words.size == 4 && words[0].equals("where", ignoreCase = true)) { "Формат: where <колонка> <оп> <значение>" }
        val column = column(table, words[1])
        val operator = words[2]
        require(operator in OPERATORS) { "Неизвестный оператор '$operator', доступны: ${OPERATORS.joinToString(" ")}" }
        val literal = parseValue(column.type, words[3])
        return Predicate { row -> matches(row[column.name], operator, literal) }
    }

    private fun parseOrder(table: Table, words: List<String>): List<SortSpec> {
        require(words.size > 2 && words[1].equals("by", ignoreCase = true)) { "Формат: order by <колонка> [desc], ..." }
        return words.drop(2).joinToString(" ").split(",").map { item ->
            val parts = item.trim().split(Regex("\\s+"))
            val direction = parts.getOrNull(1)?.lowercase()
            require(parts.size in 1..2 && direction in setOf(null, "asc", "desc")) { "Формат сортировки: <колонка> [asc|desc], получено '${item.trim()}'" }
            SortSpec(column(table, parts[0]).name, ascending = direction != "desc")
        }
    }

    private fun matches(value: Any?, operator: String, literal: Any?): Boolean = when (operator) {
        "=" -> value == literal
        "!=" -> value != literal
        else -> value != null && literal != null && compareValues(value as Comparable<*>, literal as Comparable<*>).let {
            when (operator) {
                "<" -> it < 0
                "<=" -> it <= 0
                ">" -> it > 0
                else -> it >= 0
            }
        }
    }

    /** Имена таблиц и колонок уникальны без учёта регистра, поэтому их можно вводить в любом регистре. */
    private fun table(name: String?): Table {
        require(name != null) { "Не указана таблица" }
        val actual = db.listTables().firstOrNull { it.equals(name, ignoreCase = true) }
        return requireNotNull(actual?.let(db::getTable)) { "Нет таблицы '$name'. tables — список таблиц" }
    }

    private fun column(table: Table, name: String): ColumnDefinition =
        requireNotNull(table.schema.columns.firstOrNull { it.name.equals(name, ignoreCase = true) }) {
            "В таблице '${table.name}' нет колонки '$name'"
        }

    private fun parseType(name: String): DataType =
        requireNotNull(DataType.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }) {
            "Неизвестный тип '$name', доступны: ${DataType.entries.joinToString { it.name.lowercase() }}"
        }

    private fun parseValue(type: DataType, token: String): Any? {
        if (token.equals("null", ignoreCase = true)) return null
        val text = if (token.length >= 2 && token.startsWith('"') && token.endsWith('"')) token.substring(1, token.length - 1) else token
        return when (type) {
            DataType.INT -> requireNotNull(text.toIntOrNull()) { "'$text' не подходит для INT" }
            DataType.LONG -> requireNotNull(text.toLongOrNull()) { "'$text' не подходит для LONG" }
            DataType.DOUBLE -> requireNotNull(text.toDoubleOrNull()) { "'$text' не подходит для DOUBLE" }
            DataType.BOOLEAN -> requireNotNull(text.lowercase().toBooleanStrictOrNull()) { "'$text' не подходит для BOOLEAN (true/false)" }
            DataType.STRING -> text
        }
    }

    private fun show(value: Any?): String = value?.toString() ?: "NULL"

    /** Делит строку на слова по пробелам; текст в двойных кавычках остаётся одним словом (с кавычками). */
    private fun tokenize(line: String): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        for (ch in line) {
            when {
                ch == '"' -> {
                    quoted = !quoted
                    current.append(ch)
                }
                ch.isWhitespace() && !quoted -> if (current.isNotEmpty()) {
                    words += current.toString()
                    current.clear()
                }
                else -> current.append(ch)
            }
        }
        require(!quoted) { "Не закрыта кавычка" }
        if (current.isNotEmpty()) words += current.toString()
        return words
    }

    private companion object {
        val CREATE = Regex("""create\s+table\s+(\w+)\s*\((.*)\)""", RegexOption.IGNORE_CASE)
        val OPERATORS = listOf("=", "!=", "<", "<=", ">", ">=")
    }
}
