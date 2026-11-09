package org.itmo.db.core

/** Имена таблиц и колонок становятся именами файлов, поэтому разрешён только безопасный набор символов. */
internal object Identifiers {
    private val pattern = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")

    fun validate(kind: String, name: String) {
        require(pattern.matches(name)) { "Invalid $kind name '$name': expected ${pattern.pattern}" }
    }
}
