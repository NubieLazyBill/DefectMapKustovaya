package org.example.defectmap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Ключи подстанций попадают в имена файлов (БД, SVG) — транслит должен быть предсказуемым. */
class SubstationsTest {

    @Test
    fun `транслит кириллицы в ключ файла`() {
        assertEquals("kustovaya", Substations.generateKey("Кустовая"))
        assertEquals("novaya_ps", Substations.generateKey("Новая ПС"))
        assertEquals("oru_500", Substations.generateKey("ОРУ-500"))
        assertEquals("ps_1", Substations.generateKey("ПС 1"))
    }

    @Test
    fun `пустое имя получает запасной ключ`() {
        assertTrue(Substations.generateKey("").startsWith("ps_"))
        assertTrue(Substations.generateKey("!!!").startsWith("ps_"))
    }

    @Test
    fun `список подстанций не пуст`() {
        assertTrue(Substations.ALL.isNotEmpty())
        assertTrue(Substations.ALL.any { it.key == "kustovaya" })
    }
}
