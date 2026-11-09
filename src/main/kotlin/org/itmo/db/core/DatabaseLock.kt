package org.itmo.db.core

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Одна блокировка на базу: писатели эксклюзивны, поэтому в колонку не дописывают,
 * пока другой поток её читает или кэш её вытесняет.
 */
internal class DatabaseLock {
    private val lock = ReentrantReadWriteLock()
    private var closed = false

    fun <T> read(block: () -> T): T = lock.read {
        ensureOpen()
        block()
    }

    fun <T> write(block: () -> T): T = lock.write {
        ensureOpen()
        block()
    }

    fun close(beforeClose: () -> Unit) = lock.write {
        if (!closed) {
            try {
                beforeClose()
            } finally {
                closed = true
            }
        }
    }

    private fun ensureOpen() = check(!closed) { "Database is closed" }
}
