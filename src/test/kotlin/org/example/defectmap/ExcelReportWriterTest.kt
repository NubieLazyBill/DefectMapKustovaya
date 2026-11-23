package org.example.defectmap

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.example.defectmap.report.ExcelReportWriter
import org.example.defectmap.report.ReportItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Экспорт в Excel не должен зависеть от ОС и от наличия дисплея:
 * именно этот путь проверяется на ALT в `--selftest`.
 */
class ExcelReportWriterTest {

    private fun sampleItems(): List<ReportItem> = listOf(
        ReportItem(
            equipmentName = "АТГ-1",
            equipmentType = "АТГ",
            equipmentCell = "1",
            defectName = "Течь масла",
            defectDescription = "Капельная течь по фланцу",
            defectStatus = "Обнаружен",
            markerLeft = 12.5,
            markerTop = 34.2,
            detectionDate = System.currentTimeMillis(),
            parentEquipmentName = "ОРУ-500",
        ),
        ReportItem(
            equipmentName = "В-220 №3",
            equipmentType = "В-220",
            equipmentCell = "7",
            defectName = "Износ контактов",
            defectDescription = "",
            defectStatus = "Устранён",
            markerLeft = null,
            markerTop = null,
            detectionDate = System.currentTimeMillis(),
        ),
    )

    @Test
    fun `пишет xlsx который читается обратно`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "defectmap-test-${System.nanoTime()}")
        dir.mkdirs()
        val target = File(dir, "report.xlsx")
        try {
            ExcelReportWriter.write(sampleItems(), target)
            assertTrue(target.isFile && target.length() > 0, "файл должен быть создан")

            XSSFWorkbook(target.inputStream()).use { wb ->
                val sheet = wb.getSheet(ExcelReportWriter.SHEET_NAME)
                assertNotNull(sheet, "лист ${ExcelReportWriter.SHEET_NAME}")
                sheet!!

                assertEquals("№", sheet.getRow(0).getCell(0).stringCellValue)
                assertEquals("Оборудование", sheet.getRow(0).getCell(1).stringCellValue)
                assertEquals("АТГ-1", sheet.getRow(1).getCell(1).stringCellValue)
                assertEquals("ОРУ-500", sheet.getRow(1).getCell(2).stringCellValue)
                // Проценты форматируются с точкой в любой локали
                assertEquals("12.5", sheet.getRow(1).getCell(10).stringCellValue)
                assertEquals("34.2", sheet.getRow(1).getCell(10).stringCellValue)

                // "Устранён" добавляет зелёный стиль, "Обнаружен" — красный
                assertEquals("Обнаружен", sheet.getRow(1).getCell(7).stringCellValue)
                assertEquals("Устранён", sheet.getRow(2).getCell(7).stringCellValue)

                val statsRow = sheet.getRow(sampleItems().size + 2)
                assertEquals("ИТОГО:", statsRow.getCell(0).stringCellValue)
                assertEquals("Всего дефектов: 2", statsRow.getCell(1).stringCellValue)
                assertEquals("Обнаружено: 1", statsRow.getCell(2).stringCellValue)
                assertEquals("Устранено: 1", statsRow.getCell(3).stringCellValue)

                // Настройки печати для Excel (альбомная, по ширине страницы)
                assertTrue(sheet.printSetup.landscape, "альбомная ориентация")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `пустой отчёт не падает`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "defectmap-test-${System.nanoTime()}")
        dir.mkdirs()
        val target = File(dir, "empty.xlsx")
        try {
            ExcelReportWriter.write(emptyList(), target)
            assertTrue(target.isFile && target.length() > 0)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `дата форматируется по локальной зоне`() {
        val millis = LocalDate.of(2025, 3, 7)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        assertEquals("07.03.2025", ExcelReportWriter.formatDate(millis))
        assertEquals("—", ExcelReportWriter.formatDate(null))
    }
}
