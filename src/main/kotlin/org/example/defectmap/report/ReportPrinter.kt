package org.example.defectmap.report

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.print.PageLayout
import javafx.print.PageOrientation
import javafx.print.Paper
import javafx.print.Printer
import javafx.print.PrinterJob
import javafx.scene.Node
import javafx.scene.control.ChoiceDialog
import javafx.scene.control.Label
import javafx.scene.control.OverrunStyle
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.text.FontWeight
import javafx.stage.Window
import org.example.defectmap.platform.PlatformInfo
import org.example.defectmap.platform.UiFonts
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Печать отчёта по дефектам средствами JavaFX PrinterJob.
 *
 * Почему так, а не `WebView`+HTML: печать HTML в JavaFX WebView на Linux
 * требует GTK-принт-бэкенда и даёт непредсказуемую разбивку страниц.
 * Здесь таблица верстается обычными JavaFX-узлами и печатается постранично —
 * одинаково на Windows, ALT и macOS, работает с CUPS через стандартный API.
 *
 * Требования:
 *  - вызывать только с FX-потока;
 *  - в системе должен быть хотя бы один принтер (CUPS на Linux);
 *  - без принтеров возвращается [PrintOutcome.NoPrinters] — UI показывает сообщение.
 */
object ReportPrinter {

    /** Строк таблицы на странице по умолчанию (если не удалось посчитать по высоте). */
    const val DEFAULT_ROWS_PER_PAGE = 28

    private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    sealed interface PrintOutcome {
        /** В системе нет ни одного принтера (частая ситуация на серверах без CUPS). */
        data object NoPrinters : PrintOutcome

        /** Пользователь отказался печатать. */
        data object Cancelled : PrintOutcome

        /** Напечатано [pages] страниц на принтере [printer]. */
        data class Printed(val pages: Int, val printer: String) : PrintOutcome

        data class Failed(val message: String) : PrintOutcome
    }

    /** Доступные принтеры (пустой список — печать невозможна). */
    fun availablePrinters(): List<Printer> =
        runCatching { Printer.getAllPrinters().toList() }.getOrDefault(emptyList())

    /** Разбиение на страницы — выделено отдельно, чтобы покрыть тестом. */
    fun paginate(items: List<ReportItem>, rowsPerPage: Int): List<List<ReportItem>> {
        if (items.isEmpty()) return emptyList()
        return items.chunked(rowsPerPage.coerceAtLeast(1))
    }

    /** Сколько строк влезает на страницу с учётом заданной области печати. */
    fun rowsFor(layout: PageLayout, rowHeight: Double = 16.0): Int {
        val usable = layout.printableHeight - HEADER_HEIGHT - FOOTER_HEIGHT
        return (usable / rowHeight).toInt().coerceIn(5, 200)
    }

    /**
     * Печатает [items]. При нескольких принтерах показывает диалог выбора.
     * Должен вызываться с FX-потока.
     */
    fun print(items: List<ReportItem>, owner: Window?, title: String = "Отчёт по дефектам"): PrintOutcome {
        if (items.isEmpty()) return PrintOutcome.Failed("Нет данных для печати")

        val printers = availablePrinters()
        if (printers.isEmpty()) return PrintOutcome.NoPrinters

        val printer = if (printers.size == 1) {
            printers.first()
        } else {
            val names = printers.map { it.name }
            val dialog = ChoiceDialog(names.first(), names)
            dialog.title = "Печать"
            dialog.headerText = "Выберите принтер"
            dialog.contentText = "Принтер:"
            dialog.initOwner(owner)
            val selected = dialog.showAndWait().orElse(null) ?: return PrintOutcome.Cancelled
            printers.firstOrNull { it.name == selected } ?: printers.first()
        }

        val job = PrinterJob.createPrinterJob(printer)
            ?: return PrintOutcome.Failed("Не удалось создать задание печати для «${printer.name}»")

        return try {
            val layout = printer.createPageLayout(
                Paper.A4,
                PageOrientation.PORTRAIT,
                Printer.MarginType.DEFAULT,
            )

            val rowsPerPage = rowsFor(layout)
            val pages = paginate(items, rowsPerPage)

            pages.forEachIndexed { index, pageItems ->
                val node = buildPage(
                    layout = layout,
                    items = pageItems,
                    startIndex = index * rowsPerPage,
                    total = items.size,
                    pageNumber = index + 1,
                    pageCount = pages.size,
                    title = title,
                )
                if (!job.printPage(layout, node)) {
                    job.cancelJob()
                    return PrintOutcome.Failed(
                        "Принтер «${printer.name}» отклонил страницу ${index + 1}"
                    )
                }
            }

            if (job.endJob()) {
                PrintOutcome.Printed(pages.size, printer.name)
            } else {
                PrintOutcome.Failed("Задание печати завершилось с ошибкой")
            }
        } catch (t: Throwable) {
            runCatching { job.cancelJob() }
            PrintOutcome.Failed(t.message ?: t::class.java.simpleName)
        }
    }

    // ======================== ВЁРСТКА СТРАНИЦЫ ========================

    private const val HEADER_HEIGHT = 64.0
    private const val FOOTER_HEIGHT = 22.0
    private const val ROW_HEIGHT = 16.0

    /** Доли ширины колонок (в сумме 1.0). */
    private val COLUMNS = listOf(
        "№" to 0.04,
        "ДН" to 0.16,
        "Оборудование" to 0.14,
        "Тип" to 0.11,
        "В составе" to 0.12,
        "Ячейка" to 0.06,
        "Вид дефекта" to 0.13,
        "Описание" to 0.15,
        "Статус" to 0.09,
        "Дата" to 0.09,
    )

    private fun buildPage(
        layout: PageLayout,
        items: List<ReportItem>,
        startIndex: Int,
        total: Int,
        pageNumber: Int,
        pageCount: Int,
        title: String,
    ): Node {
        val width = layout.printableWidth
        val root = VBox(0.0).apply {
            prefWidth = width
            prefHeight = layout.printableHeight
            style = "-fx-background-color: white;"
        }

        val header = VBox(2.0).apply {
            prefHeight = HEADER_HEIGHT
            prefWidth = width
            alignment = Pos.CENTER_LEFT
        }
        header.children.addAll(
            Label(title).apply {
                font = UiFonts.fxFont(14.0, FontWeight.BOLD)
                prefWidth = width
            },
            Label("Печать: ${LocalDate.now().format(DATE_FORMAT)}   •   записей: $total   •   страница $pageNumber из $pageCount").apply {
                font = UiFonts.fxFont(8.0)
                prefWidth = width
            },
        )
        root.children.add(header)

        // Шапка таблицы
        root.children.add(
            HBox(0.0).apply {
                prefWidth = width
                prefHeight = ROW_HEIGHT
                children.addAll(COLUMNS.map { (name, fraction) ->
                    cell(name, width * fraction, ROW_HEIGHT, bold = true, fill = "#d9e2ec")
                })
            }
        )

        // Строки
        items.forEachIndexed { localIndex, item ->
            val fill = if (localIndex % 2 == 1) "#f5f7fa" else "#ffffff"
            val row = HBox(0.0).apply {
                prefWidth = width
                prefHeight = ROW_HEIGHT
                style = "-fx-background-color: $fill;"
            }
            val values = listOf(
                (startIndex + localIndex + 1).toString(),
                item.equipmentName,
                item.equipmentType,
                item.equipmentModel.ifBlank { "—" },
                item.parentEquipmentName ?: "—",
                item.equipmentCell,
                item.defectName,
                item.defectDescription,
                item.defectStatus,
                ExcelReportWriter.formatDate(item.detectionDate),
            )
            COLUMNS.forEachIndexed { index, (_, fraction) ->
                row.children.add(
                    cell(
                        values[index],
                        width * fraction,
                        ROW_HEIGHT,
                        bold = index == 6,
                        fill = fill,
                    )
                )
            }
            root.children.add(row)
        }

        // Прижимаем подпись к низу страницы
        val spacer = Region()
        VBox.setVgrow(spacer, Priority.ALWAYS)
        root.children.add(spacer)

        root.children.add(
            HBox(0.0).apply {
                prefWidth = width
                prefHeight = FOOTER_HEIGHT
                alignment = Pos.CENTER_RIGHT
                children.add(
                    Label("DefectMap • ${PlatformInfo.name}").apply {
                        font = UiFonts.fxFont(7.0)
                        padding = Insets(4.0, 2.0, 0.0, 2.0)
                    }
                )
            }
        )

        // Узел печатается вне scene graph — выставляем размеры и раскладываем вручную.
        root.resize(width, layout.printableHeight)
        runCatching { root.applyCss() }
        runCatching { root.layout() }
        return root
    }

    private fun cell(
        text: String,
        width: Double,
        height: Double,
        bold: Boolean = false,
        fill: String = "#ffffff",
    ): Label = Label(text).apply {
        prefWidth = width
        prefHeight = height
        maxWidth = width
        minWidth = width
        setPrefSize(width, height)
        font = UiFonts.fxFont(7.5, if (bold) FontWeight.BOLD else FontWeight.NORMAL)
        alignment = Pos.CENTER_LEFT
        textOverrun = OverrunStyle.ELLIPSIS
        isWrapText = false
        padding = Insets(1.0, 2.0, 1.0, 2.0)
        style = "-fx-background-color: $fill; -fx-border-color: #b0b8c4; -fx-border-width: 0 1 1 0;"
    }
}
