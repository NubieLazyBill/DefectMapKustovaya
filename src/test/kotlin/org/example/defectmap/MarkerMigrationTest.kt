package org.example.defectmap

import org.example.defectmap.diagnostics.MarkerMigration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Миграция координат маркеров: старая версия сохраняла `top` в процентах от окна,
 * из-за чего значения доходили до 190%. Проверяем пересчёт и разбор JSON.
 */
class MarkerMigrationTest {

    @Test
    fun `пересчёт верхней координаты`() {
        assertEquals(50.0, MarkerMigration.rescaleTop(97.0, 1.94), 0.05)
        assertEquals(56.9, MarkerMigration.rescaleTop(110.3, 1.94), 0.05)
        assertEquals(98.1, MarkerMigration.rescaleTop(190.4, 1.94), 0.05)
        // уже нормальные значения почти не меняются
        assertEquals(12.5, MarkerMigration.rescaleTop(12.5, 1.0), 0.05)
    }

    @Test
    fun `результат всегда в диапазоне 0-100`() {
        assertEquals(100.0, MarkerMigration.rescaleTop(500.0, 1.94), 0.001)
        assertEquals(0.0, MarkerMigration.rescaleTop(-10.0, 1.94), 0.001)
        assertEquals(100.0, MarkerMigration.rescaleTop(120.0, 0.5), 0.001)
    }

    @Test
    fun `некорректный коэффициент не портит данные`() {
        assertEquals(42.0, MarkerMigration.rescaleTop(42.0, 0.0), 0.001)
        assertEquals(42.0, MarkerMigration.rescaleTop(42.0, -1.0), 0.001)
    }

    @Test
    fun `разбор JSON маркеров`() {
        val parsed = MarkerMigration.parse("""[{"left":10.5,"top":190.4,"isMain":true}]""")
        assertEquals(1, parsed.size)
        assertEquals(10.5, parsed[0].left, 0.001)
        assertEquals(190.4, parsed[0].top, 0.001)
        assertTrue(parsed[0].isMain)

        assertTrue(MarkerMigration.parse("[]").isEmpty())
        assertTrue(MarkerMigration.parse(null).isEmpty())
        assertTrue(MarkerMigration.parse("не json").isEmpty())
    }
}
