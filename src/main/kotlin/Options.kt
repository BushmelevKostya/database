package org.itmo

/** Параметры `имя=значение`; незнакомое имя — ошибка, чтобы опечатка не прошла молча. */
internal class Options(args: Array<String>) {
    private val values = args.associate { arg ->
        require('=' in arg) { "Ожидалось имя=значение, получено '$arg'. help — список параметров." }
        arg.substringBefore('=') to arg.substringAfter('=')
    }
    private val used = mutableSetOf<String>()

    fun int(name: String, default: Int): Int {
        used += name
        val raw = values[name] ?: return default
        return requireNotNull(raw.toIntOrNull()?.takeIf { it >= 0 }) { "Параметр $name должен быть неотрицательным числом, получено '$raw'" }
    }

    fun string(name: String): String? {
        used += name
        return values[name]
    }

    fun requireNoUnknown() {
        val unknown = values.keys - used
        require(unknown.isEmpty()) { "Неизвестные параметры: ${unknown.joinToString()}. help — список параметров." }
    }
}
