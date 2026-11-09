package org.itmo.db.storage

import org.itmo.db.compression.ColumnCompressor
import org.itmo.db.compression.CompressedBlock
import org.itmo.db.core.ColumnBlock
import org.itmo.db.core.ColumnDefinition
import org.itmo.db.core.DataType
import org.itmo.db.core.Identifiers
import org.itmo.db.core.Schema
import org.itmo.db.core.ValueCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.CRC32

/**
 * Раскладка: `<root>/<table>/schema.bin`, по файлу `<column>.col` на колонку (блоки только дописываются
 * в конец, см. [ColumnFileFormat]) и журнал `wal.log` со строками, которые ещё не набрали целый блок.
 * Схема и журнал заменяются целиком через временный файл и переименование, поэтому читатель не увидит
 * недописанный файл. Запись сразу уходит в ОС; [flush] делает долговечными файлы, изменённые с прошлого flush.
 * Читать блоки можно из нескольких потоков: под блокировкой берётся только положение блока в файле,
 * чтение и распаковка идут вне её.
 */
class FileStorageEngine(
    private val root: Path,
    private val compressor: ColumnCompressor,
    override val blockRows: Int = DEFAULT_BLOCK_ROWS
) : StorageEngine {
    /** Файл колонки в памяти: где лежит каждый блок и докуда файл целый. */
    private class ColumnFile(val path: Path, val type: DataType, val blocks: ArrayList<BlockRef>, var end: Long)

    private val unsynced = LinkedHashSet<Path>()
    private val columns = HashMap<String, ColumnFile>()
    private val schemas = HashMap<String, Schema>()
    private val logEnds = HashMap<String, Long>()

    init {
        require(blockRows > 0) { "Block size must be positive, got $blockRows" }
        Files.createDirectories(root)
    }

    @Synchronized
    override fun blockCount(tableName: String, columnName: String): Int = columnFile(tableName, columnName)?.blocks?.size ?: 0

    override fun appendBlock(tableName: String, columnName: String, block: ColumnBlock) {
        require(block.rows == blockRows) { "A block must have $blockRows rows, got ${block.rows}" }
        val compressed = compressor.compress(block)
        val crc = CRC32().apply { update(compressed.data) }.value.toInt()
        val header = ColumnFileFormat.blockHeader(compressed.rows, compressed.originalSize, compressed.data.size, crc)
        val bytes = ByteBuffer.allocate(header.size + compressed.data.size).put(header).put(compressed.data).flip()
        synchronized(this) {
            val file = columnFile(tableName, columnName) ?: createColumnFile(tableName, columnName, block.type)
            check(file.type == block.type) { "Column '$columnName' of '$tableName' is ${file.type}, got a block of ${block.type}" }
            val length = bytes.remaining()
            FileChannel.open(file.path, StandardOpenOption.WRITE).use { channel ->
                // Обрывок записи после сбоя лежит за концом последнего целого блока: затираем его.
                if (channel.size() > file.end) channel.truncate(file.end)
                ColumnFileFormat.writeFully(channel, bytes, file.end)
            }
            file.blocks += BlockRef(file.end, compressed.rows, compressed.originalSize, compressed.data.size, crc)
            file.end += length
            unsynced.add(file.path)
        }
    }

    override fun readBlock(tableName: String, columnName: String, index: Int): ColumnBlock {
        val (file, ref) = synchronized(this) {
            val file = checkNotNull(columnFile(tableName, columnName)) { "Column '$columnName' of '$tableName' has no data file" }
            require(index in file.blocks.indices) { "Block $index of '$columnName' does not exist, there are ${file.blocks.size}" }
            file to file.blocks[index]
        }
        val bytes = ByteBuffer.allocate(Math.toIntExact(ref.size))
        FileChannel.open(file.path, StandardOpenOption.READ).use { ColumnFileFormat.readFully(it, bytes, ref.offset) }
        check(!bytes.hasRemaining()) { "${file.path} is truncated: block $index is incomplete" }
        val data = bytes.array().copyOfRange(ColumnFileFormat.BLOCK_HEADER_BYTES, bytes.capacity())
        check(CRC32().apply { update(data) }.value.toInt() == ref.crc) { "${file.path} is corrupted: block $index fails its checksum" }
        return compressor.decompress(CompressedBlock(file.type, ref.rows, data, ref.originalSize))
    }

    @Synchronized
    override fun truncateBlocks(tableName: String, columnName: String, count: Int) {
        val file = columnFile(tableName, columnName) ?: return
        if (count >= file.blocks.size) return
        val end = file.blocks[count].offset
        FileChannel.open(file.path, StandardOpenOption.WRITE).use { it.truncate(end) }
        file.blocks.subList(count, file.blocks.size).clear()
        file.end = end
        unsynced.add(file.path)
    }

    @Synchronized
    override fun readLog(tableName: String): TableLog? {
        val path = logFile(tableName)
        if (!Files.exists(path)) return null
        return parseLog(schemaOf(tableName), Files.readAllBytes(path)).log
    }

    @Synchronized
    override fun appendLog(tableName: String, rows: List<List<Any?>>) {
        if (rows.isEmpty()) return
        val path = logFile(tableName)
        check(Files.exists(path)) { "Table '$tableName' has no log" }
        val schema = schemaOf(tableName)
        val end = logEnds.getOrPut(tableName) { parseLog(schema, Files.readAllBytes(path)).validLength.toLong() }
        val records = ByteBuffer.wrap(encodeRecords(schema, rows))
        val length = records.remaining()
        FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
            if (channel.size() > end) channel.truncate(end)
            ColumnFileFormat.writeFully(channel, records, end)
        }
        logEnds[tableName] = end + length
        unsynced.add(path)
    }

    @Synchronized
    override fun resetLog(tableName: String, firstRow: Long, rows: List<List<Any?>>) {
        val schema = schemaOf(tableName)
        val path = logFile(tableName)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(LOG_MAGIC)
            out.writeLong(firstRow)
            out.write(encodeRecords(schema, rows))
        }
        replace(path) { it.write(bytes.toByteArray()) }
        logEnds[tableName] = bytes.size().toLong()
    }

    @Synchronized
    override fun writeSchema(tableName: String, schema: Schema) {
        val tableDir = tableDir(tableName)
        if (Files.exists(tableDir) && !Files.exists(tableDir.resolve(SCHEMA_FILE))) {
            // Остаток от drop, прерванного на полпути: его колонки не должны стать нашими.
            deleteRecursively(tableDir)
        }
        forget(tableName)
        Files.createDirectories(tableDir)
        replace(tableDir.resolve(SCHEMA_FILE)) { out ->
            out.writeInt(SCHEMA_MAGIC)
            out.writeInt(schema.columns.size)
            schema.columns.forEach {
                out.writeUTF(it.name)
                out.writeUTF(it.type.name)
                out.writeBoolean(it.nullable)
            }
        }
    }

    override fun load(tableName: String): Schema? {
        val file = tableDir(tableName).resolve(SCHEMA_FILE)
        if (!Files.exists(file)) return null
        DataInputStream(Files.newInputStream(file).buffered()).use { input ->
            check(input.readInt() == SCHEMA_MAGIC) { "$file is not a schema file" }
            val columns = List(input.readInt()) {
                ColumnDefinition(input.readUTF(), DataType.valueOf(input.readUTF()), input.readBoolean())
            }
            return Schema(columns)
        }
    }

    override fun listTables(): List<String> = Files.list(root).use { entries ->
        entries
            .filter { Files.exists(it.resolve(SCHEMA_FILE)) }
            .map { it.fileName.toString() }
            .sorted()
            .toList()
    }

    @Synchronized
    override fun deleteTable(tableName: String) {
        val tableDir = tableDir(tableName)
        // Без схемы таблицы больше нет, даже если удалить остальное не удалось.
        Files.deleteIfExists(tableDir.resolve(SCHEMA_FILE))
        forget(tableName)
        deleteRecursively(tableDir)
        unsynced.removeIf { it.startsWith(tableDir) }
    }

    @Synchronized
    override fun flush() {
        unsynced.forEach { force(it, StandardOpenOption.WRITE) }
        // Переименования и удаления хранятся в каталоге; не на каждой платформе каталог можно открыть.
        (unsynced.map { it.parent } + root).distinct().forEach {
            try {
                force(it, StandardOpenOption.READ)
            } catch (_: IOException) {
            }
        }
        unsynced.clear()
    }

    /** Положение блоков файла колонки; читается один раз, дальше поддерживается при записи. */
    private fun columnFile(tableName: String, columnName: String): ColumnFile? {
        val path = columnPath(tableName, columnName)
        columns[path.toString()]?.let { return it }
        if (!Files.exists(path)) return null
        val scan = ColumnFileFormat.scan(path) ?: return null
        check(scan.name == columnName) { "$path holds column '${scan.name}', expected '$columnName'" }
        check(scan.blockRows == blockRows) {
            "$path записан с блоком в ${scan.blockRows} строк, а база открыта с блоком в $blockRows строк"
        }
        return ColumnFile(path, scan.type, ArrayList(scan.blocks), scan.end).also { columns[path.toString()] = it }
    }

    private fun createColumnFile(tableName: String, columnName: String, type: DataType): ColumnFile {
        check(Files.exists(tableDir(tableName).resolve(SCHEMA_FILE))) { "Unknown table '$tableName'" }
        val path = columnPath(tableName, columnName)
        val header = ColumnFileFormat.header(columnName, type, blockRows)
        Files.write(path, header)
        unsynced.add(path)
        return ColumnFile(path, type, ArrayList(), header.size.toLong()).also { columns[path.toString()] = it }
    }

    private fun schemaOf(tableName: String): Schema =
        schemas[tableName] ?: checkNotNull(load(tableName)) { "Unknown table '$tableName'" }.also { schemas[tableName] = it }

    /** Забывает всё, что помнили о файлах таблицы: они удалены или будут созданы заново. */
    private fun forget(tableName: String) {
        val prefix = tableDir(tableName).toString() + java.io.File.separator
        columns.keys.removeIf { it.startsWith(prefix) }
        schemas.remove(tableName)
        logEnds.remove(tableName)
    }

    private class ParsedLog(val log: TableLog, val validLength: Int)

    /** Разбирает журнал; запись, оборванная сбоем (не хватает байт или не сходится crc), и всё после неё отбрасывается. */
    private fun parseLog(schema: Schema, bytes: ByteArray): ParsedLog {
        check(bytes.size >= LOG_HEADER_BYTES && ByteBuffer.wrap(bytes).int == LOG_MAGIC) { "wal.log is not a table log" }
        val buffer = ByteBuffer.wrap(bytes)
        val firstRow = buffer.getLong(4)
        val rows = ArrayList<List<Any?>>()
        var position = LOG_HEADER_BYTES
        while (position + RECORD_HEADER_BYTES <= bytes.size) {
            val length = buffer.getInt(position)
            val crc = buffer.getInt(position + 4)
            if (length < 0 || position + RECORD_HEADER_BYTES + length > bytes.size) break
            val payload = bytes.copyOfRange(position + RECORD_HEADER_BYTES, position + RECORD_HEADER_BYTES + length)
            if (CRC32().apply { update(payload) }.value.toInt() != crc) break
            rows += decodeRow(schema, payload)
            position += RECORD_HEADER_BYTES + length
        }
        return ParsedLog(TableLog(firstRow, rows), position)
    }

    /** Буфер, из которого можно взять внутренний массив без копирования: запись журнала собирается без лишних выделений памяти. */
    private class RecordBuffer : ByteArrayOutputStream() {
        fun crc(): Int = CRC32().apply { update(buf, 0, count) }.value.toInt()
        fun copyTo(out: DataOutputStream) = out.write(buf, 0, count)
    }

    private fun encodeRecords(schema: Schema, rows: List<List<Any?>>): ByteArray {
        val out = ByteArrayOutputStream(rows.size * 64)
        val result = DataOutputStream(out)
        val payload = RecordBuffer()
        val values = DataOutputStream(payload)
        for (row in rows) {
            payload.reset()
            schema.columns.forEachIndexed { i, column ->
                val value = row[i]
                values.writeBoolean(value != null)
                if (value != null) ValueCodec.write(values, column.type, value)
            }
            result.writeInt(payload.size())
            result.writeInt(payload.crc())
            payload.copyTo(result)
        }
        return out.toByteArray()
    }

    private fun decodeRow(schema: Schema, payload: ByteArray): List<Any?> {
        val input = DataInputStream(ByteArrayInputStream(payload))
        return schema.columns.map { column -> if (input.readBoolean()) ValueCodec.read(input, column.type) else null }
    }

    private fun replace(target: Path, write: (DataOutputStream) -> Unit) {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(write)
        val temp = target.resolveSibling(target.fileName.toString() + TEMP_SUFFIX)
        Files.write(temp, bytes.toByteArray())
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
        unsynced.add(target)
    }

    private fun force(path: Path, option: StandardOpenOption) {
        FileChannel.open(path, option).use { it.force(true) }
    }

    private fun deleteRecursively(dir: Path) {
        if (Files.exists(dir)) {
            check(dir.toFile().deleteRecursively()) { "Cannot delete $dir" }
        }
    }

    private fun tableDir(tableName: String): Path {
        Identifiers.validate("table", tableName)
        return root.resolve(tableName)
    }

    private fun logFile(tableName: String): Path = tableDir(tableName).resolve(LOG_FILE)

    private fun columnPath(tableName: String, columnName: String): Path {
        Identifiers.validate("column", columnName)
        return tableDir(tableName).resolve(columnName + COLUMN_SUFFIX)
    }

    companion object {
        const val DEFAULT_BLOCK_ROWS = 8192

        private const val SCHEMA_FILE = "schema.bin"
        private const val LOG_FILE = "wal.log"
        private const val COLUMN_SUFFIX = ".col"
        private const val TEMP_SUFFIX = ".tmp"
        private const val SCHEMA_MAGIC = 0x53434831
        private const val LOG_MAGIC = 0x57414C31
        private const val LOG_HEADER_BYTES = 12
        private const val RECORD_HEADER_BYTES = 8
    }
}
