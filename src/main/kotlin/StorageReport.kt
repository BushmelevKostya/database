package org.itmo

import org.itmo.db.core.DataType
import org.itmo.db.core.Schema
import org.itmo.db.storage.ColumnFileFormat
import java.nio.file.Files
import java.nio.file.Path

/** Сколько места занимает колонка: файл на диске и её блоки до сжатия; [blocks] — сколько блоков записано. */
internal data class ColumnUsage(val name: String, val type: DataType, val diskBytes: Long, val rawBytes: Long, val blocks: Int)

/** Размеры файлов колонок таблицы; размер до сжатия читается из заголовков блоков. Колонки без файла пропускаются. */
internal fun columnUsages(tableDir: Path, schema: Schema): List<ColumnUsage> = schema.columns.mapNotNull { definition ->
    val file = tableDir.resolve(definition.name + ".col")
    if (!Files.exists(file)) return@mapNotNull null
    val scan = runCatching { ColumnFileFormat.scan(file) }.getOrNull() ?: return@mapNotNull null
    ColumnUsage(definition.name, definition.type, Files.size(file), scan.blocks.sumOf { it.originalSize }, scan.blocks.size)
}

/** Размер журнала таблицы: в нём лежат строки, которые ещё не набрали целый блок. */
internal fun logBytes(tableDir: Path): Long = tableDir.resolve("wal.log").let { if (Files.exists(it)) Files.size(it) else 0L }

/** Печатает по колонкам: размер на диске, размер до сжатия, степень сжатия и байт на строку. */
internal fun printUsage(rows: Long, usages: List<ColumnUsage>) {
    fun ratio(raw: Long, disk: Long) = if (disk == 0L) "-" else "x%.2f".format(raw.toDouble() / disk)
    fun perRow(disk: Long) = if (rows == 0L) "-" else "%.1f".format(disk.toDouble() / rows)
    val lines = usages.map { listOf(it.name, it.type.name.lowercase(), humanBytes(it.diskBytes), humanBytes(it.rawBytes), ratio(it.rawBytes, it.diskBytes), perRow(it.diskBytes)) }
    val disk = usages.sumOf { it.diskBytes }
    val raw = usages.sumOf { it.rawBytes }
    val total = listOf("итого", "", humanBytes(disk), humanBytes(raw), ratio(raw, disk), perRow(disk))
    printTable(listOf("колонка", "тип", "на диске", "без сжатия", "сжатие", "байт/строку"), lines + listOf(total))
}

internal fun humanBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "%.1f КБ".format(bytes / 1024.0)
    else -> "%.1f МБ".format(bytes / (1024.0 * 1024.0))
}
