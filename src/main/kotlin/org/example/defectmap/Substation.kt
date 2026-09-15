package org.example.defectmap

/**
 * Справочник подстанций.
 * key — латиницей, используется в именах файлов (БД, SVG).
 * displayName — как показывать пользователю.
 */
data class Substation(
    val key: String,
    val displayName: String
)

object Substations {
    val ALL = listOf(
        Substation("kustovaya", "Кустовая"),
        Substation("mirnaya", "Мирная"),
        Substation("orbita", "Орбита"),
        Substation("kometa", "Комета"),
        Substation("kvarts", "Кварц"),
        Substation("topaz", "Топаз"),
        Substation("nadezhda", "Надежда")
    )

    fun byKey(key: String): Substation? = ALL.find { it.key == key }

    val DEFAULT: Substation = ALL.first()  // Кустовая
}