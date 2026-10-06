package org.example.defectmap

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File

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
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file: File get() = File(AppPaths.appDir, "substations.json")

    /** Дефолтные ПС — используются только при первом запуске (миграция в JSON). */
    private val DEFAULTS = listOf(
        Substation("kustovaya", "Кустовая"),
        Substation("mirnaya", "Мирная"),
        Substation("orbita", "Орбита"),
        Substation("kometa", "Комета"),
        Substation("kvarts", "Кварц"),
        Substation("topaz", "Топаз"),
        Substation("nadezhda", "Надежда")
    )

    private var cached: List<Substation>? = null

    val ALL: List<Substation>
        get() {
            if (cached == null) cached = loadAll()
            return cached!!
        }

    val DEFAULT: Substation
        get() = ALL.firstOrNull() ?: DEFAULTS.first()

    fun byKey(key: String): Substation? = ALL.find { it.key == key }

    private fun loadAll(): List<Substation> {
        return try {
            if (!file.exists()) {
                saveAll(DEFAULTS)
                println("💾 Создан substations.json из ${DEFAULTS.size} ПС")
                DEFAULTS
            } else {
                val json = file.readText(Charsets.UTF_8)
                val type = object : TypeToken<List<Substation>>() {}.type
                val list: List<Substation> = gson.fromJson(json, type) ?: emptyList()
                if (list.isEmpty()) DEFAULTS else list
            }
        } catch (e: Exception) {
            println("⚠️ Не удалось прочитать substations.json: ${e.message}")
            DEFAULTS
        }
    }

    private fun saveAll(list: List<Substation>) {
        try {
            file.writeText(gson.toJson(list), Charsets.UTF_8)
            println("💾 substations.json сохранён: ${file.absolutePath}")
        } catch (e: Exception) {
            println("⚠️ Не удалось сохранить substations.json: ${e.message}")
        }
    }

    /** Добавляет ПС и сохраняет в файл. Возвращает false, если ключ занят. */
    fun add(substation: Substation): Boolean {
        val current = ALL.toMutableList()
        if (current.any { it.key == substation.key }) return false
        current.add(substation)
        saveAll(current)
        cached = current
        return true
    }

    /** Удалить ПС по ключу. Возвращает false, если ПС нет. */
    fun remove(key: String): Boolean {
        val current = ALL.toMutableList()
        val removed = current.removeAll { it.key == key }
        if (!removed) return false
        saveAll(current)
        cached = current
        return true
    }

    fun reload() {
        cached = null
    }

    /**
     * Генерирует ключ из отображаемого имени через простой транслит.
     * "Новая ПС" → "novaya_ps", "ОРУ-500" → "oru_500".
     */
    fun generateKey(displayName: String): String {
        val translit = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
            'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i",
            'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
            'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
            'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "ch",
            'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "",
            'э' to "e", 'ю' to "yu", 'я' to "ya"
        )
        val sb = StringBuilder()
        for (ch in displayName.lowercase()) {
            when {
                ch in 'a'..'z' || ch in '0'..'9' -> sb.append(ch)
                ch in translit -> sb.append(translit[ch])
                ch == ' ' || ch == '-' || ch == '_' -> sb.append('_')
            }
        }
        val cleaned = sb.toString()
            .replace(Regex("_+"), "_")
            .trim('_')
        return cleaned.ifEmpty { "ps_${System.currentTimeMillis()}" }
    }
}