package org.example.defectmap.platform

import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Подбор шрифтов с кросс-платформенным fallback.
 *
 * Проблема: Arial / Times New Roman / Calibri есть только в Windows и в составе MS Office.
 * На ALT Linux этих шрифтов по умолчанию нет, поэтому JavaFX молча подставляет
 * системный шрифт с другими метриками (текст "разъезжается"), а SVG-схемы Visio,
 * которые ссылаются на Arial/Times New Roman, рисуются чужим шрифтом.
 *
 * Решение: метрически совместимые свободные аналоги
 * (Liberation Sans/Serif, Carlito, DejaVu) + возможность подложить TTF в ресурсы
 * `org/example/defectmap/fonts/` — они загрузятся автоматически.
 */
object UiFonts {

    /** Sans-шрифты: Arial → метрически совместимый Liberation Sans → DejaVu Sans. */
    val SANS_CANDIDATES: List<String> = listOf(
        "Arial",
        "Liberation Sans",
        "Arimo",
        "Helvetica",
        "DejaVu Sans",
        "Noto Sans",
        "FreeSans",
        "Segoe UI",
        "Tahoma",
        "Verdana",
    )

    /** Serif: Times New Roman → Liberation Serif → Tinos → DejaVu Serif. */
    val SERIF_CANDIDATES: List<String> = listOf(
        "Times New Roman",
        "Liberation Serif",
        "Tinos",
        "Times",
        "DejaVu Serif",
        "Noto Serif",
        "FreeSerif",
        "Georgia",
    )

    /** Моно: Consolas → Liberation Mono → DejaVu Sans Mono. */
    val MONO_CANDIDATES: List<String> = listOf(
        "Consolas",
        "Cascadia Mono",
        "Liberation Mono",
        "DejaVu Sans Mono",
        "Noto Sans Mono",
        "Courier New",
        "FreeMono",
    )

    /** Шрифты, которые приложение пытается подгрузить из своих ресурсов. */
    private val BUNDLED_FONT_FILES = listOf(
        // Arial-совместимый (OFL): положите сюда LiberationSans-Regular.ttf и т.п.
        "LiberationSans-Regular.ttf",
        "LiberationSans-Bold.ttf",
        "LiberationSerif-Regular.ttf",
        "Carlito-Regular.ttf",
        "DejaVuSans.ttf",
    )

    private const val BUNDLED_FONT_DIR = "/org/example/defectmap/fonts/"

    /** Семейства, реально доступные JavaFX (пусто, если toolkit ещё не поднят). */
    private val fxFamilies: Set<String> by lazy {
        runCatching { Font.getFamilies().toSet() }.getOrDefault(emptySet())
    }

    /** Семейства, доступные AWT (используется для картинки-заглушки). */
    private val awtFamilies: Set<String> by lazy {
        runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .availableFontFamilyNames
                .toSet()
        }.getOrDefault(emptySet())
    }

    private val bundledFamilies = ConcurrentHashMap<String, Boolean>()

    /** Загружает TTF/OTF из ресурсов приложения (если они там есть). */
    fun installBundledFonts(): List<String> {
        val loaded = mutableListOf<String>()
        for (fileName in BUNDLED_FONT_FILES) {
            try {
                val stream = UiFonts::class.java.getResourceAsStream(BUNDLED_FONT_DIR + fileName)
                    ?: continue
                stream.use {
                    val family = Font.loadFont(it, 12.0)?.family
                    if (family != null) {
                        bundledFamilies[family] = true
                        loaded.add("$fileName → $family")
                    }
                }
            } catch (t: Throwable) {
                println("⚠️ Не удалось загрузить встроенный шрифт $fileName: ${t.message}")
            }
        }
        if (loaded.isNotEmpty()) {
            println("✅ Встроенные шрифты: ${loaded.joinToString()}")
        }
        return loaded
    }

    /** Есть ли каталог ресурсов со шрифтами (для диагностики). */
    fun hasBundledFonts(): Boolean =
        BUNDLED_FONT_FILES.any { UiFonts::class.java.getResource("$BUNDLED_FONT_DIR$it") != null }

    private fun pick(candidates: List<String>, available: Set<String>): String? =
        candidates.firstOrNull { candidate ->
            available.any { it.equals(candidate, ignoreCase = true) }
                || bundledFamilies.keys.any { it.equals(candidate, ignoreCase = true) }
        }

    /** Первое доступное sans-семейство или null (тогда используется системное). */
    val sansFamily: String?
        get() = pick(SANS_CANDIDATES, fxFamilies)

    val serifFamily: String?
        get() = pick(SERIF_CANDIDATES, fxFamilies)

    val monoFamily: String?
        get() = pick(MONO_CANDIDATES, fxFamilies)

    /** Семейство для глобального `-fx-font-family` (JavaFX принимает одно имя). */
    val sansFamilyOrDefault: String
        get() = sansFamily ?: "System"

    /**
     * Шрифт JavaFX с подстановкой: `Font.font("Arial", ...)` на Linux может вернуть
     * шрифт по умолчанию — здесь мы заранее выбираем реально установленный.
     */
    fun fxFont(
        size: Double,
        weight: FontWeight = FontWeight.NORMAL,
        candidates: List<String> = SANS_CANDIDATES,
    ): Font {
        val family = pick(candidates, fxFamilies)
        return runCatching { Font.font(family, weight, size) }
            .getOrElse { Font.font(null as String?, weight, size) }
    }

    /** Шрифт AWT с подстановкой (для BufferedImage-заглушки). */
    fun awtFont(
        size: Int,
        style: Int = java.awt.Font.PLAIN,
        candidates: List<String> = SANS_CANDIDATES,
    ): java.awt.Font {
        val family = pick(candidates, awtFamilies)
        return if (family != null) {
            java.awt.Font(family, style, size)
        } else {
            java.awt.Font(java.awt.Font.SANS_SERIF, style, size)
        }
    }

    /** CSS-стек с кавычками и generic-семейством: пригоден и для WebView, и для SVG. */
    fun cssStack(candidates: List<String>, generic: String): String =
        candidates.joinToString(", ") { "\"$it\"" } + ", " + generic

    val cssSans: String get() = cssStack(SANS_CANDIDATES, "sans-serif")
    val cssSerif: String get() = cssStack(SERIF_CANDIDATES, "serif")
    val cssMono: String get() = cssStack(MONO_CANDIDATES, "monospace")

    /** Проверка, что выбранный шрифт умеет кириллицу (важно для минимальных сборок ALT). */
    fun cyrillicCoverage(
        candidates: List<String> = SANS_CANDIDATES,
        probeJavaFx: Boolean = false,
    ): Pair<Boolean, String> {
        // В headless-режиме (--selftest) JavaFX-тулкит поднимать нельзя:
        // это тянет нативные библиотеки и требует дисплея.
        val family = pick(candidates, awtFamilies)
            ?: (if (probeJavaFx) pick(candidates, fxFamilies) else null)
            ?: java.awt.Font.SANS_SERIF
        val font = runCatching { java.awt.Font(family, java.awt.Font.PLAIN, 12) }
            .getOrDefault(java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, 12))
        val probe = "АБВГДЕЁЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯ№—«»"
        val missing = probe.filterNot { font.canDisplay(it) }
        return (missing.isEmpty() to font.family)
    }

    /**
     * Диагностическая строка: какие шрифты есть и что подставилось.
     *
     * [probeJavaFx] = false не трогает JavaFX (безопасно для `--selftest` без дисплея).
     */
    fun describe(probeJavaFx: Boolean = false): String {
        val sans = pick(SANS_CANDIDATES, awtFamilies) ?: "системный (Arial не найден)"
        val serif = pick(SERIF_CANDIDATES, awtFamilies) ?: "системный (Times New Roman не найден)"
        val mono = pick(MONO_CANDIDATES, awtFamilies) ?: "системный (Consolas не найден)"
        val fxInfo = if (probeJavaFx) {
            "fx=${fxFamilies.size}"
        } else {
            "fx=не пробывались (см. --uicheck)"
        }
        return "sans=$sans; serif=$serif; mono=$mono; " +
            "families: awt=${awtFamilies.size}, $fxInfo, bundled=${bundledFamilies.size}"
    }

    /** Ищет TTF в произвольной папке (полезно для диагностики установленных шрифтов). */
    fun findFontFiles(dirs: List<File>): List<File> =
        dirs.filter { it.isDirectory }
            .flatMap { dir ->
                dir.listFiles { f ->
                    f.isFile && f.name.lowercase(Locale.ROOT).let { it.endsWith(".ttf") || it.endsWith(".otf") }
                }?.toList().orEmpty()
            }
}
