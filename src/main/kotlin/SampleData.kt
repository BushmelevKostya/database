package org.itmo

import org.itmo.db.core.ColumnDefinition
import org.itmo.db.core.DataType
import org.itmo.db.core.Row
import org.itmo.db.core.Schema
import kotlin.random.Random

private val FIRST_NAMES = listOf("Anna", "Boris", "Vera", "Gleb", "Dina", "Egor", "Zoya", "Igor", "Kira", "Lev", "Mila", "Nikita", "Olga", "Petr", "Rita", "Sergey", "Tanya", "Ulyana", "Fedor", "Yana")
private val LAST_NAMES = listOf("Ivanov", "Petrov", "Sidorov", "Smirnov", "Kuznetsov", "Popov", "Sokolov", "Lebedev", "Kozlov", "Novikov", "Morozov", "Volkov", "Solovyov", "Vasiliev", "Zaytsev", "Pavlov", "Semenov", "Golubev", "Vinogradov", "Bogdanov")
private val CITIES = listOf("Moscow", "Kazan", "Samara", "Omsk", "Tula", "Perm", "Ufa", "Tomsk", "Sochi", "Orel", "Kursk", "Penza", "Ryazan", "Tver", "Chita", "Irkutsk", "Saratov", "Voronezh", "Yaroslavl", "Vladimir")

/** Схема таблицы для нагрузочных тестов: числа, строки с малым и большим разнообразием, null, boolean. */
internal val SAMPLE_SCHEMA = Schema(
    listOf(
        ColumnDefinition("id", DataType.LONG, nullable = false),
        ColumnDefinition("name", DataType.STRING, nullable = false),
        ColumnDefinition("age", DataType.INT),
        ColumnDefinition("city", DataType.STRING),
        ColumnDefinition("score", DataType.DOUBLE),
        ColumnDefinition("active", DataType.BOOLEAN)
    )
)

/**
 * Случайная строка по [SAMPLE_SCHEMA]. Город и имя берутся из коротких списков (хорошо сжимаются),
 * score — случайное число (почти не сжимается), возраст в 5% строк пуст.
 */
internal fun randomRow(random: Random, id: Long): Row {
    val name = FIRST_NAMES.random(random) + " " + LAST_NAMES.random(random)
    val age = if (random.nextInt(20) == 0) null else 18 + random.nextInt(63)
    val city = CITIES.random(random)
    val score = random.nextDouble() * 100
    val active = random.nextBoolean()
    return Row(mapOf("id" to id, "name" to name, "age" to age, "city" to city, "score" to score, "active" to active))
}
