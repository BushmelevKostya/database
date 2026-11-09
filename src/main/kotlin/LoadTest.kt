package org.itmo

import org.itmo.dashboard.DashboardServer
import org.itmo.dashboard.Monitor
import org.itmo.db.cache.LruCacheManager
import org.itmo.db.compression.DeflateColumnCompressor
import org.itmo.db.core.ColumnarDatabase
import org.itmo.db.metrics.Metrics
import org.itmo.db.metrics.ObservedDatabase
import org.itmo.db.storage.FileStorageEngine
import java.nio.file.Path
import kotlin.system.exitProcess

private const val HELP = """Параметры (name=value, все необязательные):
$LOAD_PARAMS_HELP
  cache=256        бюджет кэша блоков в МБ: в памяти остаются только недавно читавшиеся блоки
  port=8765        порт дашборда на localhost
  dir=<путь>       каталог данных (по умолчанию ./data, общий с демо и консолью); таблица
                   load_events (или load_<таблица> с from=) пересоздаётся при старте, остальные
                   таблицы не трогаются
  linger=30        сколько секунд держать дашборд открытым после конца нагрузки

Нагрузку можно запустить и из консоли командой load: тогда дашборд поднимает сама консоль."""

/**
 * Долгий нагрузочный тест с живым дашбордом: параллельно работают писатели, читатели, запросы и
 * периодический flush, а страница http://localhost:порт показывает, что происходит в базе.
 * Данные пишутся в каталог данных (по умолчанию `./data`) и остаются после теста: таблицу `load_events`
 * можно посмотреть через inspect. При старте она пересоздаётся, остальные таблицы не затрагиваются.
 * С параметром `from` нагрузка идёт на копию настоящей таблицы (например, загруженной через importcsv).
 */
fun main(args: Array<String>) {
    try {
        runLoadTest(args)
    } catch (e: IllegalArgumentException) {
        System.err.println("Ошибка: ${e.message}")
        exitProcess(2)
    }
}

private fun runLoadTest(args: Array<String>) {
    if (args.any { it == "help" || it == "--help" }) return println(HELP)
    val options = Options(args)
    val settings = LoadSettings(options)
    val cacheBytes = options.int("cache", 256).toLong() shl 20
    val port = options.int("port", DEFAULT_DASHBOARD_PORT)
    val linger = options.int("linger", 30)
    val dir = options.string("dir")?.let(Path::of) ?: Path.of("data")
    options.requireNoUnknown()

    val storage = FileStorageEngine(dir, DeflateColumnCompressor())
    val cache = LruCacheManager(storage, cacheBytes)
    val metrics = Metrics()
    val engine = ColumnarDatabase(storage, cache)
    val db = ObservedDatabase(engine, metrics)
    val monitor = Monitor(db, metrics, dir, storage, cache, cacheBytes)
    val load = LoadRun(db, engine, metrics, monitor, dir, settings, idlePhase = "Готово")
    var server: DashboardServer? = null

    Runtime.getRuntime().addShutdownHook(Thread {
        load.stop()
        load.await(10_000)
    })

    try {
        server = DashboardServer(monitor, port)
        println("Дашборд: http://localhost:${server.port}")
        println("Нагрузка: ${if (settings.seconds == 0) "до Ctrl-C" else "${settings.seconds} с"}, писателей ${settings.writers}, читателей ${settings.readers}, запросов ${settings.queriers}. help — список параметров.")
        load.start()
        load.await()
        if (load.failure == null && linger > 0) {
            println("Дашборд остаётся открытым ещё $linger с (Ctrl-C — выйти раньше).")
            Thread.sleep(linger * 1000L)
        }
    } catch (_: InterruptedException) {
        // Ctrl-C во время ожидания: просто переходим к остановке.
    } finally {
        load.stop()
        server?.close()
        monitor.close()
        db.close()
    }
    if (load.failure != null) exitProcess(2)
    println("Данные остались в $dir. Посмотреть: ./gradlew inspect --args=\"$dir\"")
}
