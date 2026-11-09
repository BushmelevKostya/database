package org.itmo

import org.itmo.db.core.ColumnDefinition
import org.itmo.db.core.DataType
import org.itmo.db.core.Row
import org.itmo.db.core.Table
import org.itmo.db.metrics.ObservedTable
import org.itmo.db.query.AggregateFunction
import org.itmo.db.query.AggregateSpec
import org.itmo.db.query.SortSpec
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

private const val POOL_SIZE = 20_000
private val TABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,58}")

/** Откуда берутся строки для вставки и какие запросы гонять; [description] — что именно будет выполняться. */
internal class Workload(val description: String, val nextRow: (Random) -> Row, val queries: List<() -> Unit>)

/** Синтетические данные: тот же набор колонок и запросы, что в бенчмарке. */
internal fun syntheticWorkload(table: ObservedTable, nextId: AtomicLong) = Workload(
    description = "синтетические данные; запросы: filter age > 50, sort по city и age, avg(age) по city, count(*), scan",
    nextRow = { random -> randomRow(random, nextId.getAndIncrement()) },
    queries = listOf(
        { table.filter { (it["age"] as Int?)?.let { age -> age > 50 } == true }.count() },
        { table.sort(listOf(SortSpec("city"), SortSpec("age", ascending = false))).count() },
        { table.aggregate(AggregateSpec("age", AggregateFunction.AVG, groupBy = "city")) },
        { table.aggregate(AggregateSpec(AggregateSpec.ALL, AggregateFunction.COUNT)) },
        { table.scan().count() }
    )
)

/**
 * Нагрузка на настоящую таблицу. Писатели вставляют копии случайных существующих строк (так сохраняется
 * реальное распределение значений), а запросы подбираются по схеме: число для filter и avg,
 * колонка с небольшим числом значений для sort и group by. [source] — чтение мимо метрик, чтобы выборка
 * строк не попала на график.
 */
internal fun workloadFromTable(source: Table, table: ObservedTable): Workload {
    val rows = table.rows
    require(rows > 0) { "Таблица ${table.name} пуста" }
    val random = Random(7)
    // Номера строк идут по возрастанию: каждый блок таблицы поднимается один раз, даже если кэш мал.
    val ids = List(minOf(POOL_SIZE.toLong(), rows).toInt()) { random.nextLong(rows) }.sorted()
    val pool = ids.map { checkNotNull(source.get(it)) }

    val columns = table.schema.columns
    fun distinct(column: ColumnDefinition) = pool.mapNotNull { it[column.name] }.toSet().size
    // Для filter и avg нужна колонка с разными значениями: у флага 0/1 фильтр по медиане ничего бы не нашёл.
    val numeric = columns.filter { it.type == DataType.INT || it.type == DataType.DOUBLE }.maxByOrNull(::distinct)?.takeIf { distinct(it) >= 3 }
        ?: columns.filter { it.type == DataType.LONG }.maxByOrNull(::distinct)
    val grouping = (columns.filter { it.type == DataType.STRING } + columns.filter { it.type == DataType.INT || it.type == DataType.BOOLEAN })
        .firstOrNull { it != numeric && distinct(it) in 2..1000 }

    val queries = mutableListOf<() -> Unit>()
    val described = mutableListOf<String>()
    if (numeric != null) {
        val values = pool.mapNotNull { (it[numeric.name] as Number?)?.toDouble() }.sorted()
        if (values.isNotEmpty()) {
            val median = values[values.size / 2]
            queries += { table.filter { (it[numeric.name] as Number?)?.toDouble()?.let { v -> v > median } == true }.count() }
            described += "filter ${numeric.name} > $median"
        }
    }
    val sortSpecs = listOfNotNull(grouping?.let { SortSpec(it.name) }, numeric?.let { SortSpec(it.name, ascending = false) })
    if (sortSpecs.isNotEmpty()) {
        queries += { table.sort(sortSpecs).count() }
        described += "sort по " + sortSpecs.joinToString { it.columnName + if (it.ascending) "" else " desc" }
    }
    if (numeric != null) {
        queries += { table.aggregate(AggregateSpec(numeric.name, AggregateFunction.AVG, groupBy = grouping?.name)) }
        described += "avg(${numeric.name})" + (grouping?.let { " по ${it.name}" } ?: "")
    }
    queries += { table.aggregate(AggregateSpec(AggregateSpec.ALL, AggregateFunction.COUNT)) }
    queries += { table.scan().count() }
    described += listOf("count(*)", "scan")

    return Workload(
        description = "копии настоящих строк (выборка ${pool.size}); запросы: ${described.joinToString(", ")}",
        nextRow = { rnd -> pool[rnd.nextInt(pool.size)] },
        queries = queries
    )
}

/** Имя рабочей копии таблицы [from]: исходная таблица остаётся нетронутой. */
internal fun cloneName(from: String): String {
    require(TABLE_NAME.matches(from)) { "from: имя таблицы '$from' не подходит (до 59 символов: буквы, цифры, _)" }
    return "load_$from"
}

/** Копирует файлы таблицы [from] в [target]; схема копируется последней, чтобы копия не появилась недокопированной. */
internal fun cloneTable(dir: Path, from: String, target: String) {
    val source = dir.resolve(from)
    require(Files.exists(source.resolve("schema.bin"))) { "В $dir нет таблицы $from. Сначала загрузите данные: importcsv" }
    val destination = dir.resolve(target)
    if (Files.exists(destination)) check(destination.toFile().deleteRecursively()) { "Не удалось удалить прошлую копию $destination" }
    Files.createDirectories(destination)
    val files = Files.list(source).use { list ->
        list.filter { Files.isRegularFile(it) && !it.fileName.toString().endsWith(".tmp") }.toList()
    }
    for (file in files.sortedBy { it.fileName.toString() == "schema.bin" }) {
        Files.copy(file, destination.resolve(file.fileName))
    }
}
