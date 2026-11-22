package org.example.defectmap

import org.example.defectmap.platform.UiFonts
import java.io.File
import java.io.StringReader
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** Найденная схема: содержимое SVG и откуда она взялась (для логов и диагностики). */
data class SchemaSource(
    val content: String,
    val source: String,
    val file: File?,
) {
    val sizeKb: Long get() = content.toByteArray(Charsets.UTF_8).size / 1024L
}

/**
 * Поиск SVG-схем подстанций и их подготовка к показу в WebView.
 *
 * Было: чтение файлов `readText()` без указания кодировки (на Linux с LANG=C
 * ломало кириллицу в схемах) и поиск строго в `schemas/` рядом с JAR.
 *
 * Стало: порядок поиска учитывает и пользовательский каталог данных, и поставку:
 *   1. `<dataDir>/schemas/schema_<key>.svg`  — то, что положил пользователь
 *   2. `<appDir>/schemas/schema_<key>.svg`   — то, что вложено в поставку
 *   3. ресурсы JAR `/org/example/defectmap/schema_<key>.svg`
 *   4. `src/main/resources/...` (запуск из IDE)
 *   5. то же самое для `schema.svg` (общий fallback)
 */
object Schemas {

    private const val RESOURCE_DIR = "/org/example/defectmap/"

    /** SVG Visio ссылается на Arial / Times New Roman — дописываем свободные аналоги. */
    private val FONT_FAMILY_REGEX =
        Regex("""font-family\s*:\s*([^;"'}]+)""", setOf(RegexOption.IGNORE_CASE))

    fun find(substationKey: String): SchemaSource? {
        val fileName = "schema_$substationKey.svg"
        findByName(fileName)?.let { return it }
        return findByName("schema.svg")
    }

    private fun findByName(fileName: String): SchemaSource? {
        // 1. Пользовательский каталог (dataDir)
        val userFile = File(AppPaths.schemasDir, fileName)
        readFile(userFile, "schemas (пользовательский каталог)")?.let { return it }

        // 2. Каталог поставки
        val bundledFile = File(AppPaths.bundledSchemasDir, fileName)
        if (bundledFile.path != userFile.path) {
            readFile(bundledFile, "schemas (поставка)")?.let { return it }
        }

        // 3. Ресурсы JAR
        readResource(fileName)?.let { return it }

        // 4. Исходники при запуске из IDE
        val devFile = File("src/main/resources/org/example/defectmap/$fileName")
        readFile(devFile, "src/main/resources (IDE)")?.let { return it }

        return null
    }

    private fun readFile(file: File, label: String): SchemaSource? {
        if (!file.isFile) return null
        return try {
            // ВАЖНО: явный UTF-8 — иначе на Linux с LANG=C кириллица в схеме станет мусором
            val content = file.readText(Charsets.UTF_8)
            SchemaSource(normalizeFonts(content), "$label: ${file.absolutePath}", file)
        } catch (t: Throwable) {
            System.err.println("⚠️ Не удалось прочитать схему ${file.absolutePath}: ${t.message}")
            null
        }
    }

    private fun readResource(fileName: String): SchemaSource? {
        return try {
            val stream = Schemas::class.java.getResourceAsStream("$RESOURCE_DIR$fileName")
                ?: return null
            stream.use { input ->
                val content = input.bufferedReader(Charsets.UTF_8).readText()
                SchemaSource(normalizeFonts(content), "ресурсы JAR: $fileName", null)
            }
        } catch (t: Throwable) {
            System.err.println("⚠️ Не удалось прочитать ресурс $fileName: ${t.message}")
            null
        }
    }

    /** Список доступных схем во всех каталогах (для диагностики). */
    fun listAvailable(): List<String> {
        val names = mutableSetOf<String>()
        listOf(AppPaths.schemasDir, AppPaths.bundledSchemasDir).forEach { dir ->
            dir.listFiles { f -> f.isFile && f.name.endsWith(".svg", ignoreCase = true) }
                ?.forEach { names.add(it.name) }
        }
        return names.sorted()
    }

    /**
     * Дописывает к `font-family: Arial` свободные аналоги.
     * Специфичность CSS не меняется, внешний вид в Windows — тоже:
     * первым в списке остаётся оригинальный шрифт.
     */
    fun normalizeFonts(svg: String): String {
        if (!svg.contains("font-family", ignoreCase = true)) return svg
        return FONT_FAMILY_REGEX.replace(svg) { match ->
            val raw = match.groupValues[1]
            val primary = raw.split(',').firstOrNull()?.trim()?.trim('"', '\'')
            if (primary.isNullOrBlank()) return@replace match.value

            val replacement = when (primary.lowercase(Locale.ROOT)) {
                "arial", "helvetica", "calibri", "segoe ui", "tahoma", "verdana" -> UiFonts.cssSans
                "times new roman", "times", "georgia" -> UiFonts.cssSerif
                "courier new", "consolas", "monospace" -> UiFonts.cssMono
                else -> return@replace match.value
            }
            "font-family: $replacement"
        }
    }

    /**
     * Проверка, что SVG — валидный XML.
     * Внешний DTD (w3.org) НЕ загружается: на изолированных машинах сети нет.
     */
    fun isWellFormed(xml: String): Boolean {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            factory.isValidating = false
            setFeatureQuietly(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false)
            setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false)
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun setFeatureQuietly(factory: DocumentBuilderFactory, name: String, value: Boolean) {
        try {
            factory.setFeature(name, value)
        } catch (_: Throwable) {
            // Парсер может не знать feature — это нормально
        }
    }
}
