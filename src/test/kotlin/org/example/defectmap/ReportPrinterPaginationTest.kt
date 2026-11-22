package org.example.defectmap

import org.example.defectmap.report.ReportItem
import org.example.defectmap.report.ReportPrinter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Разбиение отчёта на страницы печати — чистая логика, проверяется без принтера. */
class ReportPrinterPaginationTest {

    private fun item(index: Int) = ReportItem(
        equipmentName = "Оборудование $index",
        equipmentType = "В-220",
        equipmentCell = "1",
        defectName = "Дефект $index",
        defectDescription = "",
        defectStatus = "Обнаружен",
        markerLeft = null,
        markerTop = null,
        detectionDate = null,
    )

    @Test
    fun `делит на страницы по rowsPerPage`() {
        val pages = ReportPrinter.paginate(List(45) { item(it) }, 20)
        assertEquals(3, pages.size)
        assertEquals(listOf(20, 20, 5), pages.map { it.size })
        assertEquals("Оборудование 44", pages.last().last().equipmentName)
    }

    @Test
    fun `пустой список не даёт страниц`() {
        assertTrue(ReportPrinter.paginate(emptyList(), 20).isEmpty())
    }

    @Test
    fun `некорректный rowsPerPage приводится к 1`() {
        val pages = ReportPrinter.paginate(List(3) { item(it) }, 0)
        assertEquals(3, pages.size)
        assertTrue(pages.all { it.size == 1 })
    }
}
