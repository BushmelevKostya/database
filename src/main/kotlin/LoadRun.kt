package org.itmo

import org.itmo.dashboard.Monitor
import org.itmo.db.core.ColumnarDatabase
import org.itmo.db.metrics.Metrics
import org.itmo.db.metrics.ObservedDatabase
import org.itmo.db.metrics.ObservedTable
import org.itmo.db.metrics.Operation
import org.itmo.db.metrics.OperationSnapshot
import org.itmo.db.query.AggregateFunction
import org.itmo.db.query.AggregateSpec
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.random.Random

internal const val DEFAULT_DASHBOARD_PORT = 8765
private const val SYNTHETIC_TABLE = "load_events"

internal const val LOAD_PARAMS_HELP = """  seconds=60       сколько длится нагрузка; 0 — пока не остановят (Ctrl-C или load stop)
  preload=50000    строк в таблице до начала нагрузки (с from= не используется)
  from=<таблица>   нагрузка на настоящие данные: таблица копируется в load_<таблица> (исходная не
                   меняется), строки для вставки берутся из неё, запросы подбираются по схеме.
                   Нужно место на диске под копию; загрузить таблицу можно через importcsv
  writers=1        потоков, которые вставляют строки
  writeRate=5000   вставок в секунду на один поток записи; 0 — без ограничения
  readers=4        потоков, которые читают строку по id
  readRate=20000   чтений в секунду на один поток чтения; 0 — без ограничения
  queriers=1       потоков, которые гоняют filter, sort и aggregate
  queryPause=500   пауза между запросами в мс
  flushEvery=1000  как часто сбрасывать данные на диск, мс; 0 — не сбрасывать
                   (с from= по умолчанию 10000: сброс большой таблицы занимает секунды)"""

/** Параметры нагрузки, общие для отдельного запуска `load` и команды `load` в консоли. */
internal class LoadSettings(options: Options) {
    val seconds = options.int("seconds", 60)
    val preload = options.int("preload", 50_000)
    val writers = options.int("writers", 1)
    val writeRate = options.int("writeRate", 5000)
    val readers = options.int("readers", 4)
    val readRate = options.int("readRate", 20_000)
    val queriers = options.int("queriers", 1)
    val queryPause = options.int("queryPause", 500)
    val from = options.string("from")
    val flushEvery = options.int("flushEvery", if (from != null) 10_000 else 1000)
}

/**
 * Один прогон нагрузки на уже открытой базе: готовит таблицу, запускает писателей, читателей, запросы и
 * периодический flush, по окончании печатает итог. Работает в своём потоке, поэтому консоль остаётся свободной.
 * Ход прогона виден на дашборде через общие [metrics] и [monitor].
 */
internal class LoadRun(
    private val db: ObservedDatabase,
    private val engine: ColumnarDatabase,
    private val metrics: Metrics,
    private val monitor: Monitor,
    private val dir: Path,
    private val settings: LoadSettings,
    private val idlePhase: String
) {
    private val running = AtomicBoolean(true)
    private val controller = Thread(::execute, "load-run").apply { isDaemon = true }

    /** Причина аварийной остановки; null, если прогон идёт или закончился штатно. */
    @Volatile
    var failure: String? = null
        private set

    val isActive: Boolean get() = controller.isAlive

    fun start() = controller.start()

    fun stop() = running.set(false)

    fun await(millis: Long = 0) = controller.join(millis)

    private fun execute() {
        try {
            run()
        } catch (e: RuntimeException) {
            fail(e.message ?: e.javaClass.simpleName)
        } finally {
            running.set(false)
            monitor.phase = idlePhase
        }
    }

    private fun run() {
        val before = metrics.snapshot()
        val from = settings.from
        val tableName = if (from == null) SYNTHETIC_TABLE else cloneName(from)
        val table: ObservedTable
        val workload: Workload
        if (from == null) {
            monitor.phase = "Предзагрузка: ${settings.preload} строк"
            println("Данные: ${dir.toAbsolutePath()}, таблица $tableName" + if (db.dropTable(tableName)) " (пересоздана, прошлые данные удалены)" else "")
            table = db.createTable(tableName, SAMPLE_SCHEMA)
            val random = Random(1)
            repeat(settings.preload) { table.insert(randomRow(random, it + 1L)) }
            db.flush()
            workload = syntheticWorkload(table, AtomicLong(settings.preload + 1L))
        } else {
            monitor.phase = "Подготовка: копирую таблицу $from"
            println("Копирую таблицу $from в $tableName (исходная не меняется)...")
            db.flush()
            db.dropTable(tableName)
            cloneTable(dir, from, tableName)
            engine.refreshTables()
            monitor.phase = "Подготовка: читаю копию $tableName"
            table = checkNotNull(db.getTable(tableName)) { "Не удалось открыть $tableName" }
            workload = workloadFromTable(checkNotNull(engine.getTable(tableName)), table)
        }
        println("Строк в таблице $tableName: ${table.rows}; ${workload.description}")

        val workers = mutableListOf<Thread>()
        repeat(settings.writers) { n ->
            workers += worker("writer-$n") {
                val rnd = Random(100 + n)
                val pacer = Pacer(settings.writeRate)
                while (running.get()) {
                    pacer.await()
                    table.insert(workload.nextRow(rnd))
                }
            }
        }
        repeat(settings.readers) { n ->
            workers += worker("reader-$n") {
                val rnd = Random(200 + n)
                val pacer = Pacer(settings.readRate)
                while (running.get()) {
                    pacer.await()
                    table.get(rnd.nextLong(maxOf(1L, table.rows)))
                }
            }
        }
        repeat(settings.queriers) { n ->
            workers += worker("querier-$n") {
                var next = 0
                while (running.get()) {
                    workload.queries[next++ % workload.queries.size]()
                    sleep(settings.queryPause)
                }
            }
        }
        if (settings.flushEvery > 0) {
            workers += worker("flusher") {
                while (running.get()) {
                    sleep(settings.flushEvery)
                    if (running.get()) db.flush()
                }
            }
        }

        val seconds = settings.seconds
        val started = System.nanoTime()
        while (running.get() && (seconds == 0 || (System.nanoTime() - started) / 1_000_000_000L < seconds)) {
            val elapsed = (System.nanoTime() - started) / 1_000_000_000L
            monitor.phase = if (seconds == 0) "Нагрузка идёт: $elapsed с (остановка: load stop)" else "Нагрузка идёт: $elapsed из $seconds с"
            Thread.sleep(500)
        }
        running.set(false)
        workers.forEach { it.join() }
        monitor.phase = "Завершение: запись на диск"
        db.flush()
        printSummary(before, metrics.snapshot(), table)
    }

    private fun worker(name: String, body: () -> Unit): Thread = thread(name = name, isDaemon = true) {
        try {
            body()
        } catch (e: RuntimeException) {
            fail("$name: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Останавливает прогон и запоминает первую причину; остальные рабочие потоки сами выйдут из циклов. */
    private fun fail(message: String) {
        if (failure == null) {
            failure = message
            println("Нагрузка остановлена из-за ошибки: $message")
        }
        running.set(false)
    }

    /** Итог только за время нагрузки: вызовы из консоли до и во время прогона в таблицу не попадают. */
    private fun printSummary(before: Map<Operation, OperationSnapshot>, after: Map<Operation, OperationSnapshot>, table: ObservedTable) {
        println("\nИтог нагрузки: в таблице ${table.name} ${table.rows} строк")
        val rows = after.mapNotNull { (operation, now) ->
            val start = before.getValue(operation)
            val calls = now.calls - start.calls
            if (calls <= 0) return@mapNotNull null
            val nanos = now.totalNanos - start.totalNanos
            listOf(operation.name.lowercase(), "%,d".format(calls), "%,d".format(now.rows - start.rows), "%.1f мкс".format(nanos / calls / 1e3))
        }
        printTable(listOf("операция", "вызовов", "строк", "средняя задержка"), rows)
    }
}

/**
 * Команда `load` консоли: запускает [LoadRun] в фоне на той же базе и том же дашборде, поэтому можно
 * вводить команды и параллельно смотреть на страницу. Одновременно идёт не больше одного прогона.
 */
internal class LoadHost(
    private val db: ObservedDatabase,
    private val engine: ColumnarDatabase,
    private val metrics: Metrics,
    private val monitor: Monitor,
    private val dir: Path,
    private val dashboardUrl: String?,
    private val idlePhase: String
) {
    private var current: LoadRun? = null

    fun command(args: List<String>) {
        when (args.firstOrNull()?.lowercase()) {
            "help" -> println(
                "Параметры load (name=value, все необязательные):\n$LOAD_PARAMS_HELP\n\n" +
                    "Размер кэша блоков здесь не задаётся: он общий на сессию консоли, ./shell.sh cache=<МБ>.\nПример: load from=hits seconds=60"
            )
            "stop" -> stop()
            else -> start(args)
        }
    }

    /** Останавливает прогон, если он идёт, и ждёт, пока данные будут записаны на диск. */
    fun shutdown() {
        current?.takeIf { it.isActive }?.let {
            it.stop()
            it.await(120_000)
        }
    }

    private fun start(args: List<String>) {
        check(current?.isActive != true) { "Нагрузка уже идёт. load stop — остановить" }
        val options = Options(args.toTypedArray())
        val settings = LoadSettings(options)
        options.requireNoUnknown()
        current = LoadRun(db, engine, metrics, monitor, dir, settings, idlePhase).also { it.start() }
        println("Нагрузка запущена в фоне. " + (dashboardUrl?.let { "Смотреть: $it. " } ?: "Дашборд выключен. ") + "load stop — остановить.")
    }

    private fun stop() {
        val run = current?.takeIf { it.isActive }
        if (run == null) return println("Нагрузка не запущена.")
        println("Останавливаю...")
        run.stop()
        run.await(120_000)
    }
}

private fun sleep(millis: Int) {
    try {
        Thread.sleep(millis.toLong())
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}

/** Ограничивает частоту операций: считает дедлайны, а не паузы, поэтому средняя скорость держится точно. */
private class Pacer(ratePerSecond: Int) {
    private val interval = if (ratePerSecond > 0) 1_000_000_000L / ratePerSecond else 0L
    private var next = System.nanoTime()

    fun await() {
        if (interval == 0L) return
        next += interval
        val wait = next - System.nanoTime()
        if (wait > 1_000_000L) LockSupport.parkNanos(wait) else if (wait < -1_000_000_000L) next = System.nanoTime()
    }
}
