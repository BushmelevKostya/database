package org.itmo.db.query

data class AggregateSpec(
    val columnName: String,
    val function: AggregateFunction,
    val groupBy: String? = null
) {
    companion object {
        /** `columnName` для COUNT по строкам, а не по непустым значениям колонки. */
        const val ALL = "*"
    }
}
