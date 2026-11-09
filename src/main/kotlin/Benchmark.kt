package org.itmo

import org.itmo.db.cache.LruCacheManager
import org.itmo.db.compression.DeflateColumnCompressor
import org.itmo.db.core.ColumnarDatabase
import org.itmo.db.core.Row
import org.itmo.db.query.AggregateFunction
import org.itmo.db.query.AggregateSpec
import org.itmo.db.query.SortSpec
import org.itmo.db.storage.FileStorageEngine
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.system.exitProcess

private const val TABLE = "bench_events"
private const val POINT_GETS_WITH_CACHE_MISSES = 30
private const val INSERTS_WITH_FLUSH = 20

/** Строки для вставки и размер тех же данных в виде CSV (для сравнения плотности хранения). */
private class Dataset(val rows: List<Row>, val csvBytes: Long)

/**
 * Нагрузочный тест: скорость записи и чтения, задержки, запросы, влияние кэша, эффективность хранения.
 * Первый аргумент — число строк (по умолчанию 100000), второй — каталог данных (по умолчанию `./data`).
 * Данные остаются после теста: таблицу `bench_events` можно посмотреть через inspect. При старте она
 * пересоздаётся, остальные таблицы не затрагиваются.
 * Данные случайные, но с фиксированным зерном: город и имя берутся из коротких списков (хорошо сжимаются),
 * score — случайное число (почти не сжимается), поэтому степень сжатия зависит от данных.
 */
fun main(args: Array<String>) {
    try {
        runBenchmark(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("Ошибка: ${e.message}")
        exitProcess(2)
    }
}

private fun runBenchmark(args: Array<String>) {
    val rowCount = args.firstOrNull()?.toIntOrNull() ?: 100_000
    require(rowCount >= 1000) { "Нужно не меньше 1000 строк, получено $rowCount" }

    val dir = args.getOrNull(1)?.let(Path::of) ?: Path.of("data")
    run(dir, rowCount)
    println("\nДанные остались в ${dir.toAbsolutePath()} (таблица $TABLE). Посмотреть: ./gradlew inspect --args=\"$dir\"")
}

private fun run(dir: Path, rowCount: Int) {
    println("Нагрузочный тест: $rowCount строк, колонки: ${SAMPLE_SCHEMA.columns.joinToString { "${it.name} ${it.type.name.lowercase()}" }}")
    val runtime = Runtime.getRuntime()
    println("JVM ${Runtime.version()}, CPU: ${runtime.availableProcessors()}, куча до ${runtime.maxMemory() shr 20} МБ")
    print("Прогрев JIT... ")
    warmUp()
    println("готово")
    ColumnarDatabase.open(dir).use { db ->
        if (db.dropTable(TABLE)) println("Таблица $TABLE из прошлого запуска удалена, остальные таблицы в $dir не тронуты.")
    }
    val data = generate(rowCount)

    // Запись: вставка в память (блоки по мере заполнения уходят на диск), затем flush остатка.
    val insertSamples = LongArray(rowCount)
    var insertNanos = 0L
    var flushNanos = 0L
    ColumnarDatabase.open(dir).use { db ->
        val table = db.createTable(TABLE, SAMPLE_SCHEMA)
        insertNanos = time {
            data.rows.forEachIndexed { i, row ->
                val start = System.nanoTime()
                table.insert(row)
                insertSamples[i] = System.nanoTime() - start
            }
        }
        flushNanos = time { db.flush() }
    }
    val usages = columnUsages(dir.resolve(TABLE), SAMPLE_SCHEMA)
    val diskBytes = usages.sumOf { it.diskBytes }

    val write = mutableListOf(
        listOf("insert (в память; при заполнении блоков пишутся на диск)", "$rowCount", duration(insertNanos), perSecond(rowCount, insertNanos), percentiles(insertSamples)),
        listOf("flush остатка (журнал + дозапись блоков + fsync)", "-", duration(flushNanos), "-", "-")
    )

    // Чтение и запросы на тёплом кэше; первое открытие базы считаем отдельно.
    val read = mutableListOf<List<String>>()
    val queries = mutableListOf<List<String>>()
    ColumnarDatabase.open(dir).use { db ->
        var scanned = 0
        val coldNanos = time { scanned = checkNotNull(db.getTable(TABLE)).scan().count() }
        read += listOf("открыть БД + scan (чтение файлов, распаковка)", "$scanned", duration(coldNanos), perSecond(scanned, coldNanos), "-")

        val table = checkNotNull(db.getTable(TABLE))
        val warmNanos = time { scanned = table.scan().count() }
        read += listOf("scan (кэш тёплый)", "$scanned", duration(warmNanos), perSecond(scanned, warmNanos), "-")

        val random = Random(1)
        val getSamples = LongArray(rowCount)
        val getNanos = time {
            for (i in getSamples.indices) {
                val start = System.nanoTime()
                table.get(random.nextLong(rowCount.toLong()))
                getSamples[i] = System.nanoTime() - start
            }
        }
        read += listOf("get(id), случайный", "$rowCount", duration(getNanos), perSecond(rowCount, getNanos), percentiles(getSamples))

        var found = 0
        var nanos = time { found = table.filter { (it["age"] as Int?)?.let { age -> age > 50 } == true }.count() }
        queries += listOf("filter age > 50", "$found строк", duration(nanos))
        nanos = time { found = table.sort(listOf(SortSpec("city"), SortSpec("age", ascending = false))).count() }
        queries += listOf("sort по city, age desc", "$found строк", duration(nanos))
        nanos = time { found = table.aggregate(AggregateSpec("age", AggregateFunction.AVG, groupBy = "city")).groups.size }
        queries += listOf("avg(age) group by city", "$found групп", duration(nanos))
        var count: Any? = null
        nanos = time { count = table.aggregate(AggregateSpec(AggregateSpec.ALL, AggregateFunction.COUNT)).value }
        queries += listOf("count(*)", "$count", duration(nanos))

        // Запись, как в консоли: после каждой вставки сброс на диск. Дописывается только журнал, а не вся колонка.
        val flushedSamples = LongArray(INSERTS_WITH_FLUSH)
        val flushedNanos = time {
            for (i in flushedSamples.indices) {
                val start = System.nanoTime()
                table.insert(data.rows[i])
                db.flush()
                flushedSamples[i] = System.nanoTime() - start
            }
        }
        write += listOf("insert + flush каждой строки (таблица уже из $rowCount; пишется только журнал)", "$INSERTS_WITH_FLUSH", duration(flushedNanos), perSecond(INSERTS_WITH_FLUSH, flushedNanos), percentiles(flushedSamples))
    }

    // Кэш: та же выборка get(id) при кэше на всю таблицу и при кэше в один блок (почти каждый get идёт на диск).
    val cache = mutableListOf<List<String>>()
    for (capacity in listOf(256L shl 20, 1L shl 20)) {
        val storage = FileStorageEngine(dir, DeflateColumnCompressor())
        val lru = LruCacheManager(storage, capacity)
        ColumnarDatabase(storage, lru).use { db ->
            val table = checkNotNull(db.getTable(TABLE))
            val before = lru.stats()
            val random = Random(2)
            val nanos = time { repeat(POINT_GETS_WITH_CACHE_MISSES) { table.get(random.nextLong(rowCount.toLong())) } }
            val after = lru.stats()
            cache += listOf(
                humanBytes(capacity), "$POINT_GETS_WITH_CACHE_MISSES", duration(nanos / POINT_GETS_WITH_CACHE_MISSES),
                "${after.hits - before.hits}", "${after.misses - before.misses}", "${after.evictions - before.evictions}"
            )
        }
    }

    println("\n== запись")
    printTable(listOf("операция", "строк", "время", "скорость", "задержка p50 / p99 / max"), write)
    println("\n== чтение (файлы лежат в page cache ОС, поэтому это не холодное чтение с диска)")
    printTable(listOf("операция", "строк", "время", "скорость", "задержка p50 / p99 / max"), read)
    println("\n== запросы (кэш тёплый)")
    printTable(listOf("запрос", "результат", "время"), queries)
    println("\n== кэш блоков: get(id) в таблице из ${SAMPLE_SCHEMA.columns.size} колонок (блок — ${FileStorageEngine.DEFAULT_BLOCK_ROWS} строк одной колонки)")
    printTable(listOf("бюджет кэша", "вызовов get", "среднее на get", "попаданий", "промахов", "вытеснений"), cache)
    println("\n== хранение: $rowCount строк")
    printUsage(rowCount.toLong(), usages)
    println("  Те же данные в CSV: ${humanBytes(data.csvBytes)}; БД (только колонки) занимает ${"%.0f".format(100.0 * diskBytes / data.csvBytes)}% от CSV.")
}

private fun generate(count: Int): Dataset {
    val random = Random(42)
    var csvBytes = 0L
    val rows = List(count) { i ->
        val id = i + 1L
        val row = randomRow(random, id)
        csvBytes += "$id,${row["name"]},${row["age"] ?: ""},${row["city"]},${row["score"]},${row["active"]}\n".toByteArray().size
        row
    }
    return Dataset(rows, csvBytes)
}

/** Небольшой проход тех же операций, чтобы JIT успел скомпилировать горячий код до замеров. */
private fun warmUp() {
    val dir = Files.createTempDirectory("columnar-warmup")
    try {
        val data = generate(20_000)
        ColumnarDatabase.open(dir).use { db ->
            val table = db.createTable(TABLE, SAMPLE_SCHEMA)
            data.rows.forEach(table::insert)
            db.flush()
        }
        ColumnarDatabase.open(dir).use { db ->
            val table = checkNotNull(db.getTable(TABLE))
            table.scan().count()
            repeat(5000) { table.get(it.toLong()) }
            table.filter { (it["age"] as Int?) == 30 }.count()
            table.sort(listOf(SortSpec("city"))).count()
            table.aggregate(AggregateSpec("age", AggregateFunction.AVG, groupBy = "city"))
        }
    } finally {
        dir.toFile().deleteRecursively()
    }
}

private inline fun time(block: () -> Unit): Long {
    val start = System.nanoTime()
    block()
    return System.nanoTime() - start
}

private fun perSecond(count: Int, nanos: Long): String = "%,.0f/с".format(count * 1e9 / nanos)

private fun percentiles(samples: LongArray): String {
    val sorted = samples.sortedArray()
    fun at(quantile: Double) = sorted[((sorted.size - 1) * quantile).toInt()]
    return "${duration(at(0.5))} / ${duration(at(0.99))} / ${duration(sorted.last())}"
}

private fun duration(nanos: Long): String = when {
    nanos < 1_000 -> "$nanos нс"
    nanos < 1_000_000 -> "%.1f мкс".format(nanos / 1e3)
    nanos < 1_000_000_000 -> "%.1f мс".format(nanos / 1e6)
    else -> "%.2f с".format(nanos / 1e9)
}
