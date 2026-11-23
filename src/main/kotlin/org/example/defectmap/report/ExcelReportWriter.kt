package org.example.defectmap.report

import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Экспорт отчёта в Excel.
 *
 * Вынесен из контроллера: не зависит от JavaFX и FileChooser, поэтому
 * может быть вызван из `--selftest` и юнит-тестов для проверки на Linux.
 */
object ExcelReportWriter {

    const val SHEET_NAME = "Дефекты"

    val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    private val HEADERS = arrayOf(
        "№", "ДН", "Оборудование", "Тип", "В составе", "Ячейка",
        "Вид дефекта", "Описание", "Статус", "Дата создания", "X%", "Y%",
    )

    /** Пишет отчёт в [target]. Запись атомарная (через временный файл рядом). */
    fun write(items: List<ReportItem>, target: File): File {
        target.parentFile?.mkdirs()

        val sorted = items.sortedByDescending { it.defectStatus == "Обнаружен" }
        val workbook = XSSFWorkbook()
        try {
            val sheet = workbook.createSheet(SHEET_NAME)
            fillSheet(workbook, sheet, sorted)
            setupPrint(workbook, sheet, sorted.size)

            // Пишем во временный файл и переименовываем: на Linux/NFS так надёжнее
            val tmp = File(target.parentFile ?: File("."), target.name + ".tmp")
            tmp.outputStream().use { out -> workbook.write(out) }
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (t: Throwable) {
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
            return target
        } finally {
            runCatching { workbook.close() }
        }
    }

    private fun fillSheet(workbook: Workbook, sheet: org.apache.poi.ss.usermodel.Sheet, items: List<ReportItem>) {
        val headerStyle = workbook.createCellStyle().apply {
            val font = workbook.createFont()
            font.setBold(true)
            font.fontHeightInPoints = 12
            font.color = IndexedColors.WHITE.index
            setFont(font)

            fillForegroundColor = IndexedColors.DARK_BLUE.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
            alignment = HorizontalAlignment.CENTER
            verticalAlignment = VerticalAlignment.CENTER
            borderBottom = BorderStyle.THIN
            borderTop = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
        }

        val dataStyle = workbook.createCellStyle().apply {
            borderBottom = BorderStyle.THIN
            borderTop = BorderStyle.THIN
            borderLeft = BorderStyle.THIN
            borderRight = BorderStyle.THIN
            wrapText = true
            alignment = HorizontalAlignment.LEFT
            verticalAlignment = VerticalAlignment.CENTER
        }

        val headerRow = sheet.createRow(0)
        HEADERS.forEachIndexed { i, title ->
            headerRow.createCell(i).apply {
                setCellValue(title)
                cellStyle = headerStyle
            }
        }

        val italicStyle by lazy {
            workbook.createCellStyle().apply {
                cloneStyleFrom(dataStyle)
                val font = workbook.createFont()
                font.italic = true
                font.color = IndexedColors.GREY_50_PERCENT.index
                setFont(font)
            }
        }
        val greenStyle by lazy {
            workbook.createCellStyle().apply {
                cloneStyleFrom(dataStyle)
                val font = workbook.createFont()
                font.setBold(true)
                font.color = IndexedColors.GREEN.index
                setFont(font)
            }
        }
        val redStyle by lazy {
            workbook.createCellStyle().apply {
                cloneStyleFrom(dataStyle)
                val font = workbook.createFont()
                font.setBold(true)
                font.color = IndexedColors.RED.index
                setFont(font)
            }
        }


        items.forEachIndexed { index, item ->
            val row = sheet.createRow(index + 1)

            row.createCell(0).apply {
                setCellValue((index + 1).toDouble())
                cellStyle = dataStyle
            }
            row.createCell(1).apply {
                setCellValue(item.equipmentName)
                cellStyle = dataStyle
            }
            row.createCell(2).apply {
                setCellValue(item.equipmentType)
                cellStyle = dataStyle
            }
            row.createCell(3).apply {
                setCellValue(item.equipmentModel.ifBlank { "-" })
                cellStyle = dataStyle
            }
            row.createCell(4).apply {
                setCellValue(item.parentEquipmentName ?: "—")
                cellStyle = if (item.parentEquipmentName != null) italicStyle else dataStyle
            }
            row.createCell(5).apply {
                setCellValue(item.equipmentCell)
                cellStyle = dataStyle
            }
            row.createCell(6).apply {
                setCellValue(item.defectName)
                cellStyle = dataStyle
            }
            row.createCell(7).apply {
                setCellValue(item.defectDescription)
                cellStyle = dataStyle
            }
            row.createCell(8).apply {
                setCellValue(item.defectStatus)
                cellStyle = if (item.defectStatus == "Устранён") greenStyle else redStyle
            }
            row.createCell(9).apply {
                setCellValue(formatDate(item.detectionDate))
                cellStyle = dataStyle
            }
            row.createCell(10).apply {
                setCellValue(item.markerLeft?.let { formatPercent(it) } ?: "-")
                cellStyle = dataStyle
            }
            row.createCell(11).apply {
                setCellValue(item.markerTop?.let { formatPercent(it) } ?: "-")
                cellStyle = dataStyle
            }
        }

        val total = items.size
        val fixed = items.count { it.defectStatus == "Устранён" }
        val open = total - fixed

        val statsStyle = workbook.createCellStyle().apply {
            cloneStyleFrom(dataStyle)
            val font = workbook.createFont()
            font.setBold(true)
            font.fontHeightInPoints = 12
            setFont(font)
        }

        val statsRow = sheet.createRow(total + 2)
        statsRow.createCell(0).apply {
            setCellValue("ИТОГО:")
            cellStyle = statsStyle
        }
        statsRow.createCell(1).apply {
            setCellValue("Всего дефектов: $total")
            cellStyle = statsStyle
        }
        statsRow.createCell(2).apply {
            setCellValue("Обнаружено: $open")
            cellStyle = redStyle
        }
        statsRow.createCell(3).apply {
            setCellValue("Устранено: $fixed")
            cellStyle = greenStyle
        }

        // autoSizeColumn требует java.awt и шрифты: в headless-окружении может упасть.
        // Ширина не критична, поэтому любые сбои здесь не должны ломать экспорт.
        for (i in HEADERS.indices) {
            try {
                sheet.autoSizeColumn(i)
                val width = sheet.getColumnWidth(i)
                if (width > 8000) sheet.setColumnWidth(i, 8000)
                if (width < 3000) sheet.setColumnWidth(i, 3000)
            } catch (t: Throwable) {
                sheet.setColumnWidth(i, 4000)
            }
        }

        // Шапка не уезжает при прокрутке и печати
        try {
            sheet.createFreezePane(0, 1)
        } catch (_: Throwable) {
            // необязательно
        }
    }

    /** Настройки печати: альбомная ориентация, вписывание по ширине, шапка на каждой странице. */
    private fun setupPrint(workbook: Workbook, sheet: org.apache.poi.ss.usermodel.Sheet, rows: Int) {
        try {
            val setup = sheet.printSetup
            // В POI 5.2.5 у PrintSetup нет setOrientation — ориентация задаётся флагом
            setup.setLandscape(true)
            setup.setPaperSize(org.apache.poi.ss.usermodel.PrintSetup.A4_PAPERSIZE)
            setup.setFitWidth(1)
            setup.setFitHeight(0)
            sheet.setFitToPage(true)
            sheet.isPrintGridlines = true
            workbook.setPrintArea(
                workbook.getSheetIndex(sheet),
                0,
                HEADERS.size - 1,
                0,
                rows + 2,
            )
            sheet.repeatingRows = org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 0)
        } catch (t: Throwable) {
            // настройки печати не критичны для экспорта
        }
    }

    /** Дата в локальной зоне, независимо от локали ОС. */
    fun formatDate(epochMillis: Long?): String =
        epochMillis?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().format(DATE_FORMAT)
        } ?: "—"

    /** Проценты всегда с точкой — иначе на ru_RU получится "12,5" в разных ОС. */
    private fun formatPercent(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
}
