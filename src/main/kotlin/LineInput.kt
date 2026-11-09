package org.itmo

import java.io.IOException
import java.io.InputStreamReader

/**
 * Построчный ввод для консоли. В настоящем терминале работают стрелки (вверх/вниз — история команд,
 * влево/вправо — курсор), Home/End, Backspace/Delete, Ctrl-A/E/U/K, Ctrl-C сбрасывает строку,
 * Ctrl-D на пустой строке завершает ввод. Если ввод не терминал (pipe, файл, Windows без stty),
 * строки читаются как есть.
 */
internal sealed interface LineInput : AutoCloseable {
    /** Печатает [prompt] и возвращает введённую строку или null, когда ввод закончился. */
    fun read(prompt: String): String?

    override fun close() {}

    companion object {
        fun open(): LineInput = TerminalInput.openOrNull() ?: PlainInput
    }
}

private object PlainInput : LineInput {
    override fun read(prompt: String): String? {
        print(prompt)
        System.out.flush()
        return readlnOrNull()
    }
}

/** Терминал переводится в «сырой» режим командой `stty`; при выходе прежний режим возвращается. */
private class TerminalInput(private val savedMode: String) : LineInput {
    private val input = InputStreamReader(System.`in`, Charsets.UTF_8)
    private val history = mutableListOf<String>()
    private val restoreOnExit = Thread { stty(savedMode) }

    init {
        Runtime.getRuntime().addShutdownHook(restoreOnExit)
    }

    override fun close() {
        stty(savedMode)
        try {
            Runtime.getRuntime().removeShutdownHook(restoreOnExit)
        } catch (_: IllegalStateException) {
            // JVM уже завершается, хук отработает сам.
        }
    }

    override fun read(prompt: String): String? {
        val line = LineEditor(prompt, terminalWidth())
        var historyPosition = history.size
        var draft = ""
        line.redraw()
        while (true) {
            when (val ch = input.read()) {
                -1 -> return finishInput()
                4 -> if (line.isEmpty()) return finishInput() // Ctrl-D
                3 -> { // Ctrl-C
                    println("^C")
                    return ""
                }
                '\r'.code, '\n'.code -> {
                    println()
                    val text = line.text
                    if (text.isNotBlank() && history.lastOrNull() != text) history += text
                    return text
                }
                127, 8 -> line.deleteBefore()
                1 -> line.moveTo(0)
                5 -> line.moveToEnd()
                11 -> line.deleteToEnd()
                21 -> line.deleteToStart()
                27 -> when (readEscape()) {
                    Key.UP -> if (historyPosition > 0) {
                        if (historyPosition == history.size) draft = line.text
                        line.replace(history[--historyPosition])
                    }
                    Key.DOWN -> if (historyPosition < history.size) {
                        historyPosition++
                        line.replace(if (historyPosition == history.size) draft else history[historyPosition])
                    }
                    Key.LEFT -> line.moveBy(-1)
                    Key.RIGHT -> line.moveBy(1)
                    Key.HOME -> line.moveTo(0)
                    Key.END -> line.moveToEnd()
                    Key.DELETE -> line.deleteAt()
                    Key.OTHER -> Unit
                }
                else -> if (ch >= 32) line.insert(ch.toChar())
            }
        }
    }

    private fun finishInput(): String? {
        println()
        return null
    }

    /** Читает остаток escape-последовательности, например `[A` для стрелки вверх, `[3~` для Delete. */
    private fun readEscape(): Key {
        val introducer = input.read()
        if (introducer != '['.code && introducer != 'O'.code) return Key.OTHER
        val params = StringBuilder()
        while (true) {
            val ch = input.read()
            when {
                ch == -1 -> return Key.OTHER
                ch in 0x30..0x3F -> params.append(ch.toChar())
                else -> return when (ch.toChar()) {
                    'A' -> Key.UP
                    'B' -> Key.DOWN
                    'C' -> Key.RIGHT
                    'D' -> Key.LEFT
                    'H' -> Key.HOME
                    'F' -> Key.END
                    '~' -> when (params.toString().substringBefore(';')) {
                        "1", "7" -> Key.HOME
                        "3" -> Key.DELETE
                        "4", "8" -> Key.END
                        else -> Key.OTHER
                    }
                    else -> Key.OTHER
                }
            }
        }
    }

    private fun terminalWidth(): Int =
        stty("size")?.trim()?.split(" ")?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: 80

    private enum class Key { UP, DOWN, LEFT, RIGHT, HOME, END, DELETE, OTHER }

    companion object {
        /** Возвращает null, если ввод не терминал или `stty` недоступен. */
        fun openOrNull(): TerminalInput? {
            val saved = stty("-g")?.trim() ?: return null
            stty("-icanon", "-echo", "-isig", "min", "1") ?: return null
            return TerminalInput(saved)
        }
    }
}

/**
 * Редактируемая строка, которая сама перерисовывает себя на экране.
 * Перерисовка рассчитана на строки длиннее ширины терминала.
 */
private class LineEditor(private val prompt: String, private val width: Int) {
    private val buffer = StringBuilder()
    private var cursor = 0
    private var cursorRow = 0

    val text: String get() = buffer.toString()

    fun isEmpty() = buffer.isEmpty()

    fun insert(ch: Char) {
        buffer.insert(cursor++, ch)
        redraw()
    }

    fun deleteBefore() {
        if (cursor == 0) return
        buffer.deleteCharAt(--cursor)
        redraw()
    }

    fun deleteAt() {
        if (cursor == buffer.length) return
        buffer.deleteCharAt(cursor)
        redraw()
    }

    fun deleteToEnd() {
        buffer.setLength(cursor)
        redraw()
    }

    fun deleteToStart() {
        buffer.delete(0, cursor)
        cursor = 0
        redraw()
    }

    fun replace(value: String) {
        buffer.setLength(0)
        buffer.append(value)
        cursor = buffer.length
        redraw()
    }

    fun moveBy(delta: Int) = moveTo(cursor + delta)

    fun moveToEnd() = moveTo(buffer.length)

    fun moveTo(position: Int) {
        cursor = position.coerceIn(0, buffer.length)
        redraw()
    }

    fun redraw() {
        val out = StringBuilder()
        if (cursorRow > 0) out.append("\u001b[${cursorRow}A")
        out.append("\r\u001b[J").append(prompt).append(buffer)

        val total = prompt.length + buffer.length
        // Строка ровно во всю ширину: курсор ещё не перешёл на новую строку, переводим его сами.
        if (total > 0 && total % width == 0) out.append(" \r")
        val endRow = total / width
        val row = (prompt.length + cursor) / width
        val column = (prompt.length + cursor) % width
        if (endRow > row) out.append("\u001b[${endRow - row}A")
        out.append('\r')
        if (column > 0) out.append("\u001b[${column}C")

        cursorRow = row
        print(out)
        System.out.flush()
    }
}

/** Выполняет `stty` над нашим терминалом (stdin наследуется). Возвращает вывод или null при ошибке. */
private fun stty(vararg args: String): String? = try {
    val process = ProcessBuilder(listOf("stty") + args)
        .redirectInput(ProcessBuilder.Redirect.INHERIT)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
    if (process.waitFor() == 0) output else null
} catch (_: IOException) {
    null
}
