package org.itmo.dashboard

import java.util.Locale

/** Минимальная запись JSON без библиотек: Map, Iterable, строки, числа, Boolean и null. */
internal fun toJson(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

private fun append(out: StringBuilder, value: Any?) {
    when (value) {
        null -> out.append("null")
        is String -> quote(out, value)
        is Boolean -> out.append(value)
        is Double -> out.append(if (value.isFinite()) String.format(Locale.ROOT, "%.4f", value) else "0")
        is Number -> out.append(value)
        is Map<*, *> -> {
            out.append('{')
            var first = true
            for ((key, item) in value) {
                if (!first) out.append(',')
                first = false
                quote(out, key.toString())
                out.append(':')
                append(out, item)
            }
            out.append('}')
        }
        is Iterable<*> -> {
            out.append('[')
            var first = true
            for (item in value) {
                if (!first) out.append(',')
                first = false
                append(out, item)
            }
            out.append(']')
        }
        else -> quote(out, value.toString())
    }
}

private fun quote(out: StringBuilder, text: String) {
    out.append('"')
    for (ch in text) {
        when {
            ch == '"' -> out.append("\\\"")
            ch == '\\' -> out.append("\\\\")
            ch < ' ' -> out.append(String.format("\\u%04x", ch.code))
            else -> out.append(ch)
        }
    }
    out.append('"')
}
