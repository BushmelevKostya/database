package org.itmo.db.core

import org.itmo.db.cache.CacheManager
import org.itmo.db.cache.LruCacheManager
import org.itmo.db.compression.ColumnCompressor
import org.itmo.db.compression.DeflateColumnCompressor
import org.itmo.db.storage.FileStorageEngine
import org.itmo.db.storage.StorageEngine
import java.nio.file.Path

/**
 * Вставки лежат в памяти таблицы, пока их не запишут [flush] / [close] или пока их не наберётся на несколько блоков.
 * Таблица открывается при первом обращении: читаются только положения блоков и журнал, сами блоки
 * подгружает кэш по мере надобности. Заодно чинится сбой посреди записи (см. [openTable]).
 */
class ColumnarDatabase(
    private val storage: StorageEngine,
    private val cache: CacheManager
) : Database {
    private val lock = DatabaseLock()
    private val names = sortedSetOf<String>().apply { addAll(storage.listTables()) }
    private val opened = HashMap<String, ColumnarTable>()

    override fun createTable(name: String, schema: Schema): Table = lock.write {
        Identifiers.validate("table", name)
        require(schema.columns.isNotEmpty()) { "Table '$name' needs at least one column" }
        schema.columns.forEach { Identifiers.validate("column", it.name) }
        // Имена становятся именами файлов, а файловая система может не различать регистр.
        require(names.none { it.equals(name, ignoreCase = true) }) { "Table '$name' already exists" }
        require(schema.columns.distinctBy { it.name.lowercase() }.size == schema.columns.size) {
            "Column names of table '$name' must be unique ignoring case"
        }
        storage.writeSchema(name, schema)
        storage.resetLog(name, 0, emptyList())
        storage.flush()
        names.add(name)
        ColumnarTable(name, schema, storage, cache, lock, 0, emptyList()).also { opened[name] = it }
    }

    override fun getTable(name: String): Table? = lock.write {
        if (name in names) opened.getOrPut(name) { openTable(name) } else null
    }

    override fun dropTable(name: String): Boolean = lock.write {
        if (!names.remove(name)) return@write false
        opened.remove(name)
        cache.deleteTable(name)
        storage.deleteTable(name)
        true
    }

    override fun listTables(): List<String> = lock.read { names.toList() }

    /** Подхватывает таблицы, появившиеся в каталоге данных мимо этой базы (например, скопированные файлами). */
    fun refreshTables() = lock.write { names.addAll(storage.listTables()) }

    override fun flush() = lock.write { persist() }

    override fun close() = lock.close { persist() }

    private fun persist() {
        opened.values.forEach { it.flushLocked() }
        storage.flush()
    }

    /**
     * Строки таблицы — это целые блоки в файлах колонок плюс журнал. Колонки пишутся по очереди, поэтому после
     * сбоя в одной из них может оказаться на блок больше: лишние блоки отбрасываем, их строки всё ещё лежат
     * в журнале. Журнал, в свою очередь, мог не успеть сократиться после записи блоков: его первые строки уже
     * есть в блоках, их пропускаем. Дописанный не до конца хвост журнала отсекает само хранилище.
     */
    private fun openTable(name: String): ColumnarTable {
        val schema = checkNotNull(storage.load(name)) { "Table '$name' has no schema" }
        val counts = schema.columns.map { storage.blockCount(name, it.name) }
        val sealedBlocks = counts.min()
        schema.columns.forEachIndexed { i, definition ->
            if (counts[i] > sealedBlocks) storage.truncateBlocks(name, definition.name, sealedBlocks)
        }
        val sealedRows = sealedBlocks.toLong() * storage.blockRows

        val log = storage.readLog(name)
        if (log == null) {
            storage.resetLog(name, sealedRows, emptyList())
            return ColumnarTable(name, schema, storage, cache, lock, sealedRows, emptyList())
        }
        check(log.firstRow <= sealedRows) {
            "Table '$name' is damaged: its log starts at row ${log.firstRow}, but only $sealedRows rows are in blocks"
        }
        val pending = log.rows.drop(Math.toIntExact(minOf(sealedRows - log.firstRow, log.rows.size.toLong())))
        return ColumnarTable(name, schema, storage, cache, lock, sealedRows, pending)
    }

    companion object {
        /** Бюджет кэша блоков по умолчанию, в байтах. */
        const val DEFAULT_CACHE_BYTES = 256L shl 20

        fun open(
            directory: Path,
            cacheBytes: Long = DEFAULT_CACHE_BYTES,
            compressor: ColumnCompressor = DeflateColumnCompressor(),
            blockRows: Int = FileStorageEngine.DEFAULT_BLOCK_ROWS
        ): ColumnarDatabase {
            val storage = FileStorageEngine(directory, compressor, blockRows)
            return ColumnarDatabase(storage, LruCacheManager(storage, cacheBytes))
        }
    }
}
