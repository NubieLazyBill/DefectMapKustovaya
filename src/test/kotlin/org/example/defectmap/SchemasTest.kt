package org.example.defectmap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Схемы подстанций: поиск, кодировка, подстановка шрифтов.
 *
 * Свободные аналоги Arial/Times критичны для ALT: без них Visio-SVG
 * рисуется другим шрифтом и подписи «уезжают».
 */
class SchemasTest {

    @Test
    fun `Arial заменяется на метрически совместимый стек`() {
        val svg = """<style>.st1 {font-family:Arial;font-size:12px}</style>"""
        val result = Schemas.normalizeFonts(svg)
        assertTrue(result.contains("Liberation Sans"), result)
        assertTrue(result.contains("sans-serif"), result)
        assertTrue(result.contains("font-size:12px"), "остальная часть правила не должна ломаться: $result")
    }

    @Test
    fun `Times New Roman заменяется на serif-аналог`() {
        val result = Schemas.normalizeFonts("""<text style="font-family:Times New Roman;font-size:9px"/>""")
        assertTrue(result.contains("Liberation Serif"), result)
        assertTrue(result.contains("serif"), result)
    }

    @Test
    fun `список шрифтов с fallback не ломается повторно`() {
        val svg = """<style>.a{font-family:Arial, sans-serif}</style>"""
        val once = Schemas.normalizeFonts(svg)
        val twice = Schemas.normalizeFonts(once)
        assertEquals(once, twice, "повторная обработка не должна менять результат")
        assertTrue(once.contains("Liberation Sans"))
    }

    @Test
    fun `неизвестный шрифт остаётся как есть`() {
        val svg = """<style>.a{font-family:"PT Sans"}</style>"""
        assertEquals(svg, Schemas.normalizeFonts(svg))
    }

    @Test
    fun `валидный SVG распознаётся как XML`() {
        assertTrue(
            Schemas.isWellFormed(
                """<?xml version="1.0" encoding="UTF-8"?><svg xmlns="http://www.w3.org/2000/svg"><text>Привет</text></svg>"""
            )
        )
    }

    @Test
    fun `битый SVG не считается валидным`() {
        assertFalse(Schemas.isWellFormed("<svg><text>без закрытия</svg>"))
    }

    @Test
    fun `встроенная схема находится на любой ОС`() {
        val schema = Schemas.find("kustovaya")
        assertNotNull(schema, "схема kustovaya должна быть в ресурсах JAR")
        schema!!
        assertTrue(schema.content.contains("<svg"), "ожидался SVG, источник: ${schema.source}")
        assertTrue(schema.content.contains("Liberation Sans"), "шрифты должны быть нормализованы")
        assertTrue(Schemas.isWellFormed(schema.content), "схема должна быть корректным XML")
    }

    @Test
    fun `неизвестная подстанция получает fallback schema`() {
        val schema = Schemas.find("no-such-substation-key")
        assertNotNull(schema, "должен сработать fallback на schema.svg")
        assertTrue(schema!!.content.contains("<svg"))
    }
}
