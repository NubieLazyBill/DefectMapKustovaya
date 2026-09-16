package org.example.defectmap

import javafx.fxml.FXML
import javafx.scene.web.WebView
import javafx.scene.input.ScrollEvent
import javafx.scene.input.MouseEvent
import javafx.concurrent.Worker
import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.Alert.AlertType
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.scene.control.Button
import javafx.geometry.Pos
import javafx.scene.input.KeyCode
import javafx.scene.control.TextInputDialog
import javafx.scene.control.ChoiceDialog
import javafx.scene.layout.HBox
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.ContextMenu
import java.io.File
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.scene.control.ButtonType
import javafx.scene.layout.StackPane
import javafx.geometry.Insets
import javafx.animation.PauseTransition
import javafx.scene.control.MenuButton
import javafx.scene.control.TableView
import javafx.scene.control.TableColumn
import javafx.scene.control.TableCell
import javafx.scene.control.cell.PropertyValueFactory
import javafx.collections.FXCollections
import javafx.scene.control.TableRow
import javafx.scene.control.ButtonBar
import javafx.animation.FadeTransition
import javafx.scene.control.DatePicker
import javafx.util.Duration
import javafx.scene.control.Dialog
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.time.Instant

class DefectMapController {
    @FXML
    private lateinit var webView: WebView

    @FXML
    private lateinit var viewEquipmentBtn: Button

    @FXML
    private lateinit var devMenuBtn: MenuButton

    @FXML
    private lateinit var editModeMenuItem: MenuItem

    @FXML
    private lateinit var toggleMarkersMenuItem: MenuItem

    @FXML
    private lateinit var forceImportMenuItem: MenuItem

    @FXML
    private lateinit var defectsBtn: Button

    @FXML
    private lateinit var manageTypesMenuItem: MenuItem


    private var markersVisible = false
    private var isDraggingMarker = false
    private var currentEditingEquipmentId: String? = null
    private var zoomLevel = 1.0
    private val MIN_ZOOM = 0.5
    private val MAX_ZOOM = 5.0
    private val ZOOM_STEP = 0.1

    private var isDragging = false
    private var lastMouseX = 0.0
    private var lastMouseY = 0.0
    private var currentTranslateX = 0.0
    private var currentTranslateY = 0.0

    private var isEditMode = false
    private var equipmentCounter = 0

    private var equipmentListStage: Stage? = null
    private var defectsListStage: Stage? = null

    private var currentSubstation: Substation = Substations.DEFAULT
    private var database: Database = Database(currentSubstation.key)

    private val gson: Gson by lazy {
        GsonBuilder().setPrettyPrinting().create()
    }

    private var isInitialized = false
    private var dragStartX = 0.0
    private var dragStartY = 0.0
    private val DRAG_THRESHOLD = 5.0

    // ======================== ОТЧЁТЫ ========================

    @FXML
    private fun onCreateReport() {
        println("📊 СОЗДАНИЕ ОТЧЁТА")

        try {
            val allEquipment = loadEquipment()
            if (allEquipment.isEmpty()) {
                showInfo("📋 Нет данных для отчёта")
                return
            }

            val equipmentById = allEquipment.associateBy { it.id }

            val reportData = mutableListOf<ReportItem>()
            allEquipment.forEach { eq ->
                // ===== Прямой родитель (для отображения "В составе") =====
                val parent = eq.parentId?.let { pid -> equipmentById[pid] }
                val parentName = parent?.name

                // ===== Корневой предок (для фильтра по типу) =====
                val root = findRootEquipment(eq, equipmentById)
                val rootTypeName = EquipmentTypes.getTypeName(root.type)

                // ===== Ячейку берём у корня (там, где "сидит" вся сборка) =====
                val effectiveCell = root.cell.ifEmpty { eq.cell }

                val defects = database.getDefectsByEquipment(eq.id)
                defects.forEach { defect ->
                    reportData.add(
                        ReportItem(
                            equipmentName = eq.name,
                            equipmentType = EquipmentTypes.getTypeName(eq.type),
                            equipmentCell = effectiveCell,
                            defectName = defect.name,
                            defectDescription = defect.description,
                            defectStatus = if (defect.status == "fixed") "Устранён" else "Обнаружен",
                            markerLeft = defect.markerLeft,
                            markerTop = defect.markerTop,
                            detectionDate = defect.detectionDate,
                            parentEquipmentName = parentName,
                            rootEquipmentType = rootTypeName
                        )
                    )
                }
            }

            if (reportData.isEmpty()) {
                showInfo("📋 Нет зарегистрированных дефектов для отчёта")
                return
            }

            showReportDialog(reportData)

        } catch (e: Exception) {
            showError("Ошибка создания отчёта: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun showReportDialog(reportData: List<ReportItem>) {
        val reportStage = Stage()
        reportStage.title = "📊 Создание отчёта"
        reportStage.isResizable = true
        reportStage.minWidth = 750.0
        reportStage.minHeight = 620.0

        val ownerStage = webView.scene.window as Stage
        reportStage.initOwner(ownerStage)
        reportStage.initModality(javafx.stage.Modality.NONE)

        val root = VBox(15.0)
        root.style = "-fx-background-color: white; -fx-padding: 20px;"

        val headerLabel = Label("📊 Настройка отчёта")
        headerLabel.style = "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #333;"

        val filtersBox = VBox(10.0)
        filtersBox.style = "-fx-padding: 10px 0;"

        val statusLabel = Label("Статус дефектов:")
        statusLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val statusCombo = ComboBox<String>()
        statusCombo.items.addAll("Все", "Обнаружен", "Устранён")
        statusCombo.value = "Все"
        statusCombo.style = "-fx-pref-width: 150px;"

        val typeLabel = Label("Тип оборудования:")
        typeLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val typeCombo = ComboBox<String>()
        val allTypes = listOf("Все") + EquipmentTypes.ALL_TYPES.map { it.second }
        typeCombo.items.addAll(allTypes)
        typeCombo.value = "Все"
        typeCombo.style = "-fx-pref-width: 180px;"

        val cellLabel = Label("Ячейка:")
        cellLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val cellCombo = ComboBox<String>()

// Загружаем уникальные ячейки из БД
        val allCells = loadEquipment()
            .mapNotNull { it.cell.takeIf { cell -> cell.isNotEmpty() } }
            .distinct()
            .sorted()

        cellCombo.items.add("Все ячейки")
        cellCombo.items.addAll(allCells)
        cellCombo.value = "Все ячейки"
        cellCombo.style = "-fx-pref-width: 150px;"

// ===== ДОБАВИТЬ ЭТОТ БЛОК =====
        val defectTypeLabel = Label("Вид дефекта:")
        defectTypeLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val defectTypeCombo = ComboBox<String>()
        val defectTypesList = listOf("Все") + DefectTypes.ALL_TYPES
        defectTypeCombo.items.addAll(defectTypesList)
        defectTypeCombo.value = "Все"
        defectTypeCombo.style = "-fx-pref-width: 200px;"
// ===== КОНЕЦ НОВОГО БЛОКА =====

        val dateLabel = Label("Период создания:")
        dateLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val dateFrom = DatePicker()
        dateFrom.promptText = "с"
        dateFrom.style = "-fx-pref-width: 130px;"
        val dateTo = DatePicker()
        dateTo.promptText = "по"
        dateTo.style = "-fx-pref-width: 130px;"
        val dateBox = HBox(10.0, dateFrom, dateTo)
        dateBox.alignment = Pos.CENTER_LEFT

        val countLabel = Label("Найдено: ${reportData.size} дефектов")
        countLabel.style = "-fx-font-size: 14px; -fx-text-fill: #28a745; -fx-font-weight: bold;"

        val previewLabel = Label("Первые 5 записей:")
        previewLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val previewText = TextArea()
        previewText.isEditable = false
        previewText.prefHeight = 100.0
        previewText.style = "-fx-font-size: 12px; -fx-font-family: monospace;"

        fun updatePreview() {
            val filtered = filterReportData(
                reportData,
                statusCombo.value,
                typeCombo.value,
                cellCombo.value,
                defectTypeCombo.value,
                dateFrom.value,
                dateTo.value
            )
            countLabel.text = "Найдено: ${filtered.size} дефектов"

            if (filtered.isNotEmpty()) {
                val preview = filtered.take(5).joinToString("\n") { item ->
                    val dateStr = item.detectionDate?.let {
                        Instant.ofEpochMilli(it)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                    } ?: "—"
                    "${item.equipmentName} | ${item.defectName} | ${item.defectStatus} | $dateStr"
                }
                previewText.text = preview + if (filtered.size > 5) "\n... и ещё ${filtered.size - 5}" else ""
            } else {
                previewText.text = "Нет дефектов по выбранным фильтрам"
            }
        }

        statusCombo.valueProperty().addListener { _, _, _ -> updatePreview() }
        typeCombo.valueProperty().addListener { _, _, _ -> updatePreview() }
        cellCombo.valueProperty().addListener { _, _, _ -> updatePreview() }
        dateFrom.valueProperty().addListener { _, _, _ -> updatePreview() }
        dateTo.valueProperty().addListener { _, _, _ -> updatePreview() }

        val buttonBox = HBox(10.0)
        buttonBox.alignment = Pos.CENTER_RIGHT
        buttonBox.style = "-fx-padding: 15px 0 0 0;"

        val createBtn = Button("📊 Создать отчёт")
        createBtn.style = "-fx-background-color: #28a745; -fx-text-fill: white; -fx-padding: 8px 20px; -fx-background-radius: 4px; -fx-font-weight: bold; -fx-font-size: 13px;"
        createBtn.setOnAction {
            val status = statusCombo.value
            val typeName = typeCombo.value
            val cell = cellCombo.value ?: "Все ячейки"
            val defectType = defectTypeCombo.value
            val from = dateFrom.value
            val to = dateTo.value

            val filtered = filterReportData(reportData, status, typeName, cell, defectType,from, to)
            if (filtered.isEmpty()) {
                showInfo("⚠️ Нет дефектов по выбранным фильтрам")
                return@setOnAction
            }
            reportStage.close()
            saveReportToExcel(filtered)
        }

        val cancelBtn = Button("✕ Закрыть")
        cancelBtn.style = "-fx-background-color: #6c757d; -fx-text-fill: white; -fx-padding: 8px 20px; -fx-background-radius: 4px; -fx-font-size: 13px;"
        cancelBtn.setOnAction { reportStage.close() }

        buttonBox.children.addAll(createBtn, cancelBtn)

        filtersBox.children.addAll(
            statusLabel, statusCombo,
            typeLabel, typeCombo,
            cellLabel, cellCombo,
            defectTypeLabel, defectTypeCombo,
            dateLabel, dateBox
        )

        root.children.addAll(
            headerLabel,
            filtersBox,
            countLabel,
            previewLabel, previewText,
            buttonBox
        )

        val scene = Scene(root, 780.0, 650.0)
        reportStage.scene = scene

        reportStage.setOnShown {
            Platform.runLater {
                reportStage.requestFocus()
                reportStage.toFront()
                updatePreview()
            }
        }

        reportStage.show()
        reportStage.setOnHidden {
            println("📊 Окно отчёта закрыто")
        }

        defectTypeCombo.valueProperty().addListener { _, _, _ -> updatePreview() }  // ← ДОБАВИТЬ
    }

    private fun filterReportData(
        data: List<ReportItem>,
        status: String?,
        typeName: String?,
        cell: String?,
        defectType: String?,
        dateFrom: LocalDate?,
        dateTo: LocalDate?
    ): List<ReportItem> {
        return data.filter { item ->
            val statusMatch = status == null || status == "Все" ||
                    (status == "Обнаружен" && item.defectStatus == "Обнаружен") ||
                    (status == "Устранён" && item.defectStatus == "Устранён")

            val typeMatch = typeName == null || typeName == "Все" ||
                item.equipmentType == typeName ||
                item.rootEquipmentType == typeName

            // ===== ЯЧЕЙКА: если "Все ячейки" или пусто — не фильтруем =====
            val cellMatch = cell == null || cell == "Все ячейки" || cell.isEmpty() ||
                    item.equipmentCell.contains(cell, ignoreCase = true)

            val defectTypeMatch = defectType == null || defectType == "Все" ||
                    item.defectName.equals(defectType, ignoreCase = true)

            val dateMatch = if (dateFrom != null || dateTo != null) {
                val detectionDate = item.detectionDate?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
                }
                val fromMatch = dateFrom == null || (detectionDate != null && !detectionDate.isBefore(dateFrom))
                val toMatch = dateTo == null || (detectionDate != null && !detectionDate.isAfter(dateTo))
                fromMatch && toMatch
            } else true

            statusMatch && typeMatch && cellMatch && defectTypeMatch && dateMatch
        }
    }

    private fun saveReportToExcel(data: List<ReportItem>) {
        try {
            val sortedData = data.sortedByDescending { it.defectStatus == "Обнаружен" }

            val fileChooser = javafx.stage.FileChooser()
            fileChooser.title = "Сохранить отчёт"
            fileChooser.initialFileName = "report_defects_${LocalDate.now()}.xlsx"
            fileChooser.extensionFilters.add(
                javafx.stage.FileChooser.ExtensionFilter("Excel files (*.xlsx)", "*.xlsx")
            )

            val file = fileChooser.showSaveDialog(webView.scene.window)
            if (file == null) {
                println("❌ Сохранение отменено")
                return
            }

            val workbook = org.apache.poi.xssf.usermodel.XSSFWorkbook()
            val sheet = workbook.createSheet("Дефекты")

            val headerStyle = workbook.createCellStyle().apply {
                val font = workbook.createFont()
                font.setBold(true)
                font.fontHeightInPoints = 12
                font.color = org.apache.poi.ss.usermodel.IndexedColors.WHITE.index
                setFont(font)

                fillForegroundColor = org.apache.poi.ss.usermodel.IndexedColors.DARK_BLUE.index
                fillPattern = org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND
                alignment = org.apache.poi.ss.usermodel.HorizontalAlignment.CENTER
                borderBottom = org.apache.poi.ss.usermodel.BorderStyle.THIN
                borderTop = org.apache.poi.ss.usermodel.BorderStyle.THIN
                borderLeft = org.apache.poi.ss.usermodel.BorderStyle.THIN
                borderRight = org.apache.poi.ss.usermodel.BorderStyle.THIN
            }

            val dataStyle = workbook.createCellStyle().apply {
                borderBottom = org.apache.poi.ss.usermodel.BorderStyle.THIN
                borderTop = org.apache.poi.ss.usermodel.BorderStyle.THIN
                borderLeft = org.apache.poi.ss.usermodel.BorderStyle.THIN
                borderRight = org.apache.poi.ss.usermodel.BorderStyle.THIN
                wrapText = true
                alignment = org.apache.poi.ss.usermodel.HorizontalAlignment.LEFT
                verticalAlignment = org.apache.poi.ss.usermodel.VerticalAlignment.CENTER
            }

            val headers = arrayOf(
                "№", "Оборудование", "В составе", "Тип оборудования", "Ячейка",
                "Вид дефекта", "Описание", "Статус", "Дата создания", "X%", "Y%"
            )

            val headerRow = sheet.createRow(0)
            headers.forEachIndexed { i, title ->
                val cell = headerRow.createCell(i)
                cell.setCellValue(title)
                cell.cellStyle = headerStyle
            }

            sortedData.forEachIndexed { index, item ->
                val row = sheet.createRow(index + 1)

                row.createCell(0).apply {
                    setCellValue((index + 1).toDouble())
                    cellStyle = dataStyle
                }
                row.createCell(1).apply {
                    setCellValue(item.equipmentName)
                    cellStyle = dataStyle
                }
                // ===== НОВАЯ КОЛОНКА: "В составе" =====
                row.createCell(2).apply {
                    setCellValue(item.parentEquipmentName ?: "—")
                    val parentStyle = if (item.parentEquipmentName != null) {
                        workbook.createCellStyle().apply {
                            cloneStyleFrom(dataStyle)
                            val font = workbook.createFont()
                            font.italic = true
                            font.color = org.apache.poi.ss.usermodel.IndexedColors.GREY_50_PERCENT.index
                            setFont(font)
                        }
                    } else {
                        dataStyle
                    }
                    cellStyle = parentStyle
                }
                row.createCell(3).apply {
                    setCellValue(item.equipmentType)
                    cellStyle = dataStyle
                }
                row.createCell(4).apply {
                    setCellValue(item.equipmentCell)
                    cellStyle = dataStyle
                }
                row.createCell(5).apply {
                    setCellValue(item.defectName)
                    cellStyle = dataStyle
                }
                row.createCell(6).apply {
                    setCellValue(item.defectDescription)
                    cellStyle = dataStyle
                }
                row.createCell(7).apply {
                    setCellValue(item.defectStatus)
                    val statusStyle = if (item.defectStatus == "Устранён") {
                        workbook.createCellStyle().apply {
                            cloneStyleFrom(dataStyle)
                            val font = workbook.createFont()
                            font.setBold(true)
                            font.color = org.apache.poi.ss.usermodel.IndexedColors.GREEN.index
                            setFont(font)
                        }
                    } else {
                        workbook.createCellStyle().apply {
                            cloneStyleFrom(dataStyle)
                            val font = workbook.createFont()
                            font.setBold(true)
                            font.color = org.apache.poi.ss.usermodel.IndexedColors.RED.index
                            setFont(font)
                        }
                    }
                    cellStyle = statusStyle
                }
                row.createCell(8).apply {
                    val dateStr = item.detectionDate?.let {
                        Instant.ofEpochMilli(it)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                    } ?: "—"
                    setCellValue(dateStr)
                    cellStyle = dataStyle
                }
                row.createCell(9).apply {
                    setCellValue(item.markerLeft?.let { String.format("%.1f", it) } ?: "-")
                    cellStyle = dataStyle
                }
                row.createCell(10).apply {
                    setCellValue(item.markerTop?.let { String.format("%.1f", it) } ?: "-")
                    cellStyle = dataStyle
                }
            }

            val totalDefects = sortedData.size
            val fixedDefects = sortedData.count { it.defectStatus == "Устранён" }
            val openDefects = totalDefects - fixedDefects

            val statsRow = sheet.createRow(sortedData.size + 2)
            val statsStyle = workbook.createCellStyle().apply {
                cloneStyleFrom(dataStyle)
                val font = workbook.createFont()
                font.setBold(true)
                font.fontHeightInPoints = 12
                setFont(font)
            }

            statsRow.createCell(0).apply {
                setCellValue("ИТОГО:")
                cellStyle = statsStyle
            }
            statsRow.createCell(1).apply {
                setCellValue("Всего дефектов: $totalDefects")
                cellStyle = statsStyle
            }
            statsRow.createCell(2).apply {
                setCellValue("Обнаружено: $openDefects")
                val style = workbook.createCellStyle().apply {
                    cloneStyleFrom(statsStyle)
                    val font = workbook.createFont()
                    font.setBold(true)
                    font.color = org.apache.poi.ss.usermodel.IndexedColors.RED.index
                    setFont(font)
                }
                cellStyle = style
            }
            statsRow.createCell(3).apply {
                setCellValue("Устранено: $fixedDefects")
                val style = workbook.createCellStyle().apply {
                    cloneStyleFrom(statsStyle)
                    val font = workbook.createFont()
                    font.setBold(true)
                    font.color = org.apache.poi.ss.usermodel.IndexedColors.GREEN.index
                    setFont(font)
                }
                cellStyle = style
            }

            for (i in 0..10) {
                sheet.autoSizeColumn(i)
                val width = sheet.getColumnWidth(i)
                if (width > 8000) sheet.setColumnWidth(i, 8000)
                if (width < 3000) sheet.setColumnWidth(i, 3000)
            }

            workbook.write(file.outputStream())
            workbook.close()

            showInfo("✅ Отчёт сохранён:\n${file.absolutePath}\n\nВсего дефектов: $totalDefects\nОбнаружено: $openDefects\nУстранено: $fixedDefects")
            println("✅ Отчёт сохранён: ${file.absolutePath}")

        } catch (e: Exception) {
            showError("Ошибка сохранения отчёта: ${e.message}")
            e.printStackTrace()
        }
    }

    data class ReportItem(
        val equipmentName: String,
        val equipmentType: String,
        val equipmentCell: String,
        val defectName: String,
        val defectDescription: String,
        val defectStatus: String,
        val markerLeft: Double?,
        val markerTop: Double?,
        val detectionDate: Long?,
        val parentEquipmentName: String? = null,
        val rootEquipmentType: String? = null,
    )

    // ======================== ИНИЦИАЛИЗАЦИЯ ========================

    @FXML
    private fun initialize() {
        database.autoBackup()

        // ===== ЗАГРУЖАЕМ ТИПЫ ИЗ БД =====
        EquipmentTypes.loadFromDatabase(database)
        DefectTypes.loadFromDatabase(database)

        loadSvgIntoWebViewForSubstation(currentSubstation.key)
        updateWindowTitle()

        toggleMarkersMenuItem.text = if (markersVisible) "👁️ Скрыть маркеры" else "👁️ Показать маркеры"

        // ===== ОБРАБОТЧИК ДЛЯ УПРАВЛЕНИЯ ТИПАМИ =====
        manageTypesMenuItem.setOnAction { showManageTypesDialog() }


        webView.engine.getLoadWorker().stateProperty().addListener { _, _, newState ->
            if (newState == Worker.State.SUCCEEDED) {
                Platform.runLater {
                    setupZoom()
                    setupClickHandler()
                    setupButtons()
                    initEquipment()
                    isInitialized = true
                    loadAndRefresh()
                }
            }
        }

        webView.setOnKeyPressed { event ->
            if (event.isControlDown && event.code == KeyCode.S) {
                saveEquipment()
                showToast("✅ Данные сохранены")
                event.consume()
            }
        }

        Platform.runLater {
            val stage = webView.scene?.window as? Stage
            stage?.setOnCloseRequest {
                println("🔄 Приложение закрывается...")
                if (isInitialized) {
                    saveEquipment()
                }
                database.close()
                println("✅ Завершено")
            }
        }

        // Пункт меню "Сменить подстанцию"
        val switchSubstationItem = MenuItem("🔌 Сменить подстанцию")
        switchSubstationItem.setOnAction { showSwitchSubstationDialog() }
        devMenuBtn.items.add(switchSubstationItem)
    }

    // ======================== РЕЖИМ РЕДАКТИРОВАНИЯ ========================

    @FXML
    private fun toggleEditModeAction() {
        toggleEditMode(!isEditMode)
    }

    // ======================== ПЕРЕКЛЮЧАТЕЛЬ ВИДИМОСТИ МАРКЕРОВ ========================

    @FXML
    private fun toggleMarkersVisibility() {
        markersVisible = !markersVisible

        webView.engine.executeScript("""
        (function() {
            var markers = document.querySelectorAll('.equipment-marker');
            markers.forEach(function(marker) {
                if (${markersVisible}) {
                    marker.classList.remove('hidden');
                } else {
                    marker.classList.add('hidden');
                }
            });
        })();
    """.trimIndent())

        toggleMarkersMenuItem.text = if (markersVisible) "👁️ Скрыть маркеры" else "👁️ Показать маркеры"
    }

    // ======================== ЗАГРУЗКА SVG ========================

    private fun loadSvgIntoWebViewForSubstation(substationKey: String) {
        try {
            val fileName = "schema_$substationKey.svg"

            // 1. Ресурсы JAR
            val svgResource = javaClass.getResource("/org/example/defectmap/$fileName")
            if (svgResource != null) {
                val svgContent = svgResource.readText()
                webView.engine.loadContent(buildSvgHtml(svgContent))
                println("✅ SVG загружен из ресурсов: $fileName")
                return
            }

            // 2. Файловая система
            val svgFile = File("src/main/resources/org/example/defectmap/$fileName")
            if (svgFile.exists()) {
                val svgContent = svgFile.readText()
                webView.engine.loadContent(buildSvgHtml(svgContent))
                println("✅ SVG загружен из ФС: ${svgFile.absolutePath}")
                return
            }

            // 3. Рядом с JAR
            val jarSvgFile = File(fileName)
            if (jarSvgFile.exists()) {
                val svgContent = jarSvgFile.readText()
                webView.engine.loadContent(buildSvgHtml(svgContent))
                println("✅ SVG загружен рядом с JAR: ${jarSvgFile.absolutePath}")
                return
            }

            // 4. Fallback
            println("⚠️ Не найдена схема '$fileName', пробую fallback schema.svg")
            val fallback = javaClass.getResource("/org/example/defectmap/schema.svg")
            if (fallback != null) {
                val svgContent = fallback.readText()
                webView.engine.loadContent(buildSvgHtml(svgContent))
                println("✅ SVG загружен из fallback: schema.svg")
                return
            }

            println("❌ Не найдена ни одна схема для ПС '$substationKey'")
        } catch (e: Exception) {
            println("❌ Ошибка загрузки SVG: ${e.message}")
            e.printStackTrace()
        }

        // 5. Совсем ничего не нашли — грузим пустую HTML, чтобы WebView перезагрузился
        println("❌ Не найдена ни одна схема для ПС '$substationKey' — гружу пустую схему")
        val emptyHtml = """
    <!DOCTYPE html>
    <html>
      <head><style>body { font-family: sans-serif; padding: 40px; color: #999; }</style></head>
      <body>
        <h2>⚠️ Схема для ПС '$substationKey' не найдена</h2>
        <p>Положите файл <code>schema_${substationKey}.svg</code> в resources.</p>
      </body>
    </html>
""".trimIndent()
        webView.engine.loadContent(emptyHtml)
    }

    private fun showSwitchSubstationDialog() {
        val choices = Substations.ALL.map { it.displayName }
        val currentName = currentSubstation.displayName

        val dialog = ChoiceDialog(currentName, choices)
        dialog.title = "Смена подстанции"
        dialog.headerText = "Выберите подстанцию"
        dialog.contentText = "Подстанция:"

        val result = dialog.showAndWait()
        if (result.isEmpty) return

        val selectedName = result.get()
        val newSub = Substations.ALL.find { it.displayName == selectedName } ?: return

        if (newSub.key == currentSubstation.key) {
            showToast("ℹ️ Уже открыта ПС ${newSub.displayName}")
            return
        }

        switchSubstation(newSub)
    }

    private fun switchSubstation(newSub: Substation) {
        println("🔌 Смена подстанции: ${currentSubstation.displayName} → ${newSub.displayName}")

        // 1. Сохранить текущее состояние в СТАРУЮ БД (пока она открыта)
        if (isInitialized) {
            try { saveEquipment() } catch (e: Exception) {
                println("⚠️ Не удалось сохранить перед сменой ПС: ${e.message}")
            }
        }

        // 2. Очистить состояние в WebView ДО смены БД
        //    Иначе при закрытии приложения старые данные сохранятся в новую БД
        clearWebViewEquipment()

        // 3. Закрыть старую БД
        try { database.close() } catch (e: Exception) {
            println("⚠️ Ошибка закрытия БД: ${e.message}")
        }

        // 4. Сменить ПС и открыть новую БД
        currentSubstation = newSub
        database = Database(newSub.key)
        database.autoBackup()

        // 5. Перезагрузить типы из новой БД
        EquipmentTypes.loadFromDatabase(database)
        DefectTypes.loadFromDatabase(database)

        // 6. Сбросить хэш и состояние
        lastSavedHash = 0
        isEditMode = false
        currentEditingEquipmentId = null

        // 7. Загрузить SVG. Загрузка асинхронная.
        //    Подписываемся на SUCCEEDED ОДИН РАЗ для этой конкретной загрузки
        val listener = object : javafx.beans.value.ChangeListener<Worker.State> {
            override fun changed(
                observable: javafx.beans.value.ObservableValue<out Worker.State>,
                oldValue: Worker.State,
                newValue: Worker.State
            ) {
                if (newValue == Worker.State.SUCCEEDED) {
                    webView.engine.loadWorker.stateProperty().removeListener(this)
                    Platform.runLater {
                        setupZoom()
                        setupClickHandler()
                        initEquipment()
                        loadAndRefresh()
                        isInitialized = true
                        updateWindowTitle()
                        showToast("✅ Открыта ПС: ${newSub.displayName}")
                    }
                }
            }
        }
        webView.engine.loadWorker.stateProperty().addListener(listener)

        loadSvgIntoWebViewForSubstation(newSub.key)
    }

    private fun clearWebViewEquipment() {
        webView.engine.executeScript("""
        (function() {
            var container = document.getElementById('equipment-container');
            if (container) container.innerHTML = '';
            window.equipment = [];
            console.log('🧹 Маркеры и window.equipment очищены');
        })();
    """.trimIndent())
    }

    private fun updateWindowTitle() {
        Platform.runLater {
            val stage = webView.scene?.window as? Stage
            stage?.title = "DefectMap — ${currentSubstation.displayName}"
        }
    }

    private fun buildSvgHtml(svgContent: String): String {
        return """
    <!DOCTYPE html>
    <html>
      <head>
        <style>
          * { 
              margin: 0; 
              padding: 0; 
              user-select: none;
              -webkit-user-select: none;
              -moz-user-select: none;
              -ms-user-select: none;
          }
          html, body { 
              width: 100%; 
              height: 100%; 
              overflow: hidden;
              background: white;
          }
          #container {
            width: 100%;
            height: 100%;
            display: flex;
            justify-content: center;
            align-items: center;
            overflow: hidden;
            cursor: grab;
            position: relative;
          }
          #container.dragging { cursor: grabbing; }
          #image-wrapper {
            position: relative;
            display: inline-block;
            max-width: 100%;
            max-height: 100%;
            transform-origin: center center;
            width: 100%;
            height: 100%;
          }
          #image svg {
            display: block;
            width: 100%;
            height: 100%;
            object-fit: contain;
            transform-origin: center center;
            will-change: transform;
          }
          /* Превью оборудования при наведении */
          .marker-preview {
              position: fixed;
              z-index: 1000;
              pointer-events: none;
              background: white;
              border: 2px solid #333;
              border-radius: 8px;
              box-shadow: 0 4px 20px rgba(0,0,0,0.3);
              padding: 5px;
              display: none;
              max-width: 200px;
              max-height: 200px;
          }
          .marker-preview img {
              display: block;
              max-width: 190px;
              max-height: 190px;
              border-radius: 4px;
          }
          .marker-preview .preview-label {
              text-align: center;
              font-size: 11px;
              font-family: Arial, sans-serif;
              color: #333;
              margin-top: 4px;
              font-weight: bold;
          }
          .equipment-marker {
            position: absolute;
            cursor: grab;
            z-index: 10;
            pointer-events: auto;
            transform: translate(-50%, -50%);
            width: 28px;
            height: 28px;
            transition: all 0.2s ease;
          }
          .equipment-marker:active { cursor: grabbing; }
          .equipment-marker.hidden .dot {
              opacity: 0 !important;
              pointer-events: none;
          }
          .equipment-marker.hidden .tooltip-text {
              opacity: 0 !important;
              visibility: hidden !important;
          }
          .equipment-marker.hidden:hover .tooltip-text {
              opacity: 1 !important;
              visibility: visible !important;
          }
          .equipment-marker .dot {
            width: 24px;
            height: 24px;
            border-radius: 50%;
            border: 2px solid rgba(255, 255, 255, 0.8);
            box-shadow: 0 2px 8px rgba(0,0,0,0.15);
            display: flex;
            justify-content: center;
            align-items: center;
            color: white;
            font-weight: bold;
            font-size: 11px;
            font-family: Arial, sans-serif;
            background: rgba(0, 0, 0, 0.5);
            backdrop-filter: blur(2px);
            transition: all 0.2s ease;
          }
          .equipment-marker:hover .dot {
            background: rgba(0, 0, 0, 0.8);
            border-color: white;
          }
          .equipment-marker.breaker .dot { background: #ff4444; }
          .equipment-marker.disconnector .dot { background: #ff8800; }
          .equipment-marker.transformer .dot { background: #44bb44; }
          .equipment-marker.lightning .dot { background: #ffcc00; color: #333; }
          .equipment-marker.other .dot { background: #8888ff; }
          .equipment-marker .tooltip-text {
            visibility: hidden;
            opacity: 0;
            position: absolute;
            bottom: calc(100% + 10px);
            left: 50%;
            transform: translateX(-50%);
            background: rgba(0, 0, 0, 0.85);
            color: white;
            padding: 4px 12px;
            border-radius: 4px;
            font-size: 11px;
            font-family: Arial, sans-serif;
            white-space: nowrap;
            pointer-events: none;
            transition: all 0.25s ease;
            box-shadow: 0 4px 15px rgba(0,0,0,0.3);
            border: 1px solid rgba(255,255,255,0.1);
          }
          .equipment-marker .tooltip-text::after {
            content: '';
            position: absolute;
            top: 100%;
            left: 50%;
            transform: translateX(-50%);
            border: 5px solid transparent;
            border-top-color: rgba(0, 0, 0, 0.85);
          }
          .equipment-marker:hover .tooltip-text {
            visibility: visible;
            opacity: 1;
          }
          .equipment-marker.small { width: 20px; height: 20px; }
          .equipment-marker.small .dot { width: 16px; height: 16px; font-size: 8px; }
          .equipment-marker.normal { width: 28px; height: 28px; }
          .equipment-marker.normal .dot { width: 24px; height: 24px; font-size: 11px; }
          .equipment-marker.large { width: 36px; height: 36px; }
          .equipment-marker.large .dot { width: 32px; height: 32px; font-size: 14px; }
          .equipment-marker.marker-extra {
              border: 2px dashed rgba(255, 255, 255, 0.5);
              opacity: 0.85;
          }
          .equipment-marker.marker-extra .dot {
              border: 2px dashed rgba(255, 255, 255, 0.8);
          }
          .equipment-marker:hover {
              transform: translate(-50%, -50%) scale(1.2);
          }
          .edit-mode #container { cursor: crosshair; }
        </style>
      </head>
      <body>
        <div id="container">
          <div id="image-wrapper">
            <div id="image">$svgContent</div>
            <div id="equipment-container"></div>
          </div>
        </div>
      </body>
    </html>
""".trimIndent()
    }

    private fun loadAndRefresh() {
        if (webView.engine.getLoadWorker().state != Worker.State.SUCCEEDED) {
            println("⚠️ WebView ещё не загружен, откладываем обновление")
            javafx.animation.PauseTransition(javafx.util.Duration.millis(300.0)).apply {
                setOnFinished { loadAndRefresh() }
                play()
            }
            return
        }

        val imageMap = buildImageMap()
        val imageMapJson = gson.toJson(imageMap)

        webView.engine.executeScript("""
    window.imageMap = $imageMapJson;
""".trimIndent())

        val savedEquipment = database.loadAllEquipment()
        println("📂 Перезагружено из БД: ${savedEquipment.size} шт.")

        lastSavedHash = savedEquipment.hashCode()

        if (savedEquipment.isNotEmpty()) {
            val equipmentJson = gson.toJson(savedEquipment)

            webView.engine.executeScript("""
            (function() {
                var oldContainer = document.getElementById('equipment-container');
                if (oldContainer) {
                    oldContainer.remove();
                }
                
                var wrapper = document.getElementById('image-wrapper');
                if (!wrapper) {
                    console.error('❌ image-wrapper не найден');
                    return;
                }
                
                var container = document.createElement('div');
                container.id = 'equipment-container';
                wrapper.appendChild(container);
                
                var savedData = $equipmentJson;
                window.equipment = savedData;
                
                console.log('🔄 Пересоздаём маркеры для ' + savedData.length + ' записей');
                
                savedData.forEach(function(item) {
                    var markers = item.markers || [{left: item.left, top: item.top, isMain: true}];
                    
                    markers.forEach(function(markerPos, index) {
                        var marker = document.createElement('div');
                        var sizeClass = item.size || 'normal';
                        marker.className = 'equipment-marker ' + item.type + ' ' + sizeClass;
                        if (index > 0) marker.className += ' marker-extra';
                        if (!${markersVisible}) {
                            marker.className += ' hidden';
                        }
                        marker.id = item.id + '-marker-' + index + '-' + Date.now();
                        marker.style.left = markerPos.left + '%';
                        marker.style.top = markerPos.top + '%';
                        marker.dataset.equipmentId = item.id;
                        marker.dataset.markerIndex = index;
                        
                        if (index > 0) {
                            marker.style.border = '2px dashed rgba(255,255,255,0.5)';
                        }
                        
                        marker.innerHTML = '<div class="dot">' + item.letter + '</div><span class="tooltip-text">' + item.name + '</span>';
                        container.appendChild(marker);
                    });
                });
                
                console.log('✅ Пересоздано маркеров: ' + container.querySelectorAll('.equipment-marker').length);
            })();
        """.trimIndent())

            equipmentCounter = savedEquipment.size
        } else {
            webView.engine.executeScript("""
            (function() {
                var container = document.getElementById('equipment-container');
                if (container) container.remove();
                window.equipment = [];
            })();
        """.trimIndent())
        }
    }

    // ======================== ИНИЦИАЛИЗАЦИЯ ОБОРУДОВАНИЯ ========================

    private fun initEquipment() {
        val savedEquipment = database.loadAllEquipment()
        println("📂 Загружено из БД: ${savedEquipment.size} шт.")

        val imageMap = buildImageMap()
        val imageMapJson = gson.toJson(imageMap)

        webView.engine.executeScript("""
    window.imageMap = $imageMapJson;
""".trimIndent())

        lastSavedHash = savedEquipment.hashCode()

        if (savedEquipment.isNotEmpty()) {
            val equipmentJson = gson.toJson(savedEquipment)

            webView.engine.executeScript("""
            (function() {
                var oldContainer = document.getElementById('equipment-container');
                if (oldContainer) {
                    oldContainer.remove();
                }
                
                var wrapper = document.getElementById('image-wrapper');
                if (!wrapper) {
                    console.error('❌ image-wrapper не найден');
                    return;
                }
                
                var container = document.createElement('div');
                container.id = 'equipment-container';
                wrapper.appendChild(container);
                
                var savedData = $equipmentJson;
                window.equipment = savedData;
                
                savedData.forEach(function(item) {
                    var markers = item.markers;
                    if (!markers || markers.length === 0) {
                        markers = [{left: item.left, top: item.top, isMain: true}];
                    }
                    
                    markers.forEach(function(markerPos, index) {
                        var marker = document.createElement('div');
                        var sizeClass = item.size || 'normal';
                        marker.className = 'equipment-marker ' + item.type + ' ' + sizeClass;
                        if (index > 0) marker.className += ' marker-extra';
                        if (!${markersVisible}) {
                            marker.className += ' hidden';
                        }
                        marker.id = item.id + '-marker-' + index;
                        marker.style.left = markerPos.left + '%';
                        marker.style.top = markerPos.top + '%';
                        marker.dataset.equipmentId = item.id;
                        marker.dataset.markerIndex = index;
                        
                        if (index > 0) {
                            marker.style.border = '2px dashed rgba(255,255,255,0.5)';
                        }
                        
                        marker.innerHTML = '<div class="dot">' + item.letter + '</div><span class="tooltip-text">' + item.name + '</span>';
                        container.appendChild(marker);
                    });
                });
                
                console.log('✅ Инициализировано: ' + savedData.length + ' единиц оборудования');
            })();
        """.trimIndent())

            equipmentCounter = savedEquipment.size
        } else {
            webView.engine.executeScript("""
            (function() {
                var container = document.getElementById('equipment-container');
                if (container) container.remove();
                window.equipment = [];
            })();
        """.trimIndent())
        }
    }

    // ======================== КНОПКИ ========================

    private fun setupButtons() {
        viewEquipmentBtn.setOnAction { viewEquipmentList() }
        defectsBtn.setOnAction { showDefectsList() }
    }

    // ======================== ПЕРЕКЛЮЧЕНИЕ РЕЖИМА РЕДАКТИРОВАНИЯ (ИСПРАВЛЕНО) ========================

    private fun toggleEditMode(enable: Boolean) {
        // ===== УБРАНО АВТОСОХРАНЕНИЕ ПРИ ВЫХОДЕ =====
        // if (!enable && isEditMode) {
        //     println("💾 Сохраняем изменения при выходе из режима редактирования")
        //     saveEquipment()
        //     showToast("✅ Изменения сохранены")
        // }

        isEditMode = enable

        if (!enable) {
            currentEditingEquipmentId = null
            editModeMenuItem.text = "✏️ Режим редактирования"
        } else {
            editModeMenuItem.text = "🔒 Выйти из редактирования"
        }

        webView.engine.executeScript("""
        var container = document.getElementById('container');
        if (${enable}) {
            container.classList.add('edit-mode');
            window.editMode = true;
            document.body.style.cursor = 'crosshair';
        } else {
            container.classList.remove('edit-mode');
            window.editMode = false;
            document.body.style.cursor = 'default';
        }
    """.trimIndent())
    }

    // ======================== ЗУМ И ПАН ========================

    private fun setupZoom() {
        webView.setOnScroll { event: ScrollEvent ->
            val delta = if (event.deltaY > 0) ZOOM_STEP else -ZOOM_STEP
            val newZoom = (zoomLevel + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
            if (newZoom != zoomLevel) {
                zoomLevel = newZoom
                webView.engine.executeScript("""
                    var wrapper = document.getElementById('image-wrapper');
                    wrapper.style.transform = 'translate(${currentTranslateX}px, ${currentTranslateY}px) scale($zoomLevel)';
                    wrapper.style.transformOrigin = 'center center';
                """.trimIndent())
            }
            event.consume()
        }
    }

    // ======================== СПИСОК ДЕФЕКТОВ ========================

    private fun showDefectsList() {
        defectsListStage?.close()

        val allDefects = mutableListOf<DefectViewItem>()
        val allEquipment = loadEquipment()

        allEquipment.forEach { eq ->
            val defects = database.getDefectsByEquipment(eq.id)
            defects.forEach { defect ->
                allDefects.add(
                    DefectViewItem(
                        equipmentName = eq.name,
                        cell = eq.cell,
                        defectName = defect.name,
                        description = defect.description,
                        status = defect.status,
                        equipmentId = eq.id,
                        defectId = defect.id
                    )
                )
            }
        }

        if (allDefects.isEmpty()) {
            showInfo("📋 Нет зарегистрированных дефектов")
            return
        }

        val mainLayout = VBox(15.0)
        mainLayout.style = "-fx-background-color: white; -fx-padding: 20px;"
        mainLayout.prefWidth = 900.0
        mainLayout.prefHeight = 650.0

        val headerLabel = Label("📊 ВСЕ ДЕФЕКТЫ (${allDefects.size})")
        headerLabel.style = "-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #333;"

        val filterPanel = HBox(10.0)
        filterPanel.alignment = Pos.CENTER_LEFT
        filterPanel.style = "-fx-padding: 10px 0; -fx-background-color: #f8f9fa; -fx-border-color: #e9ecef; -fx-border-width: 0 0 1px 0;"

        val statusFilter = ComboBox<String>()
        statusFilter.promptText = "Все статусы"
        statusFilter.items.addAll("Все статусы", "🟡 Обнаружен", "✅ Устранён")
        statusFilter.selectionModel.selectFirst()
        statusFilter.style = "-fx-pref-width: 150px; -fx-padding: 4px; -fx-font-size: 13px;"

        val searchField = TextField()
        searchField.promptText = "🔍 Поиск по оборудованию..."
        searchField.style = "-fx-pref-width: 250px; -fx-padding: 6px 10px; -fx-border-color: #ced4da; -fx-border-radius: 4px;"

        val tableView = TableView<DefectViewItem>()
        tableView.style = "-fx-font-size: 13px; -fx-border-color: #dee2e6;"
        tableView.prefHeight = 600.0  // ← ВЫСОТА ТАБЛИЦЫ

        val colEquipment = TableColumn<DefectViewItem, String>("Оборудование")
        colEquipment.cellValueFactory = PropertyValueFactory("equipmentName")
        colEquipment.prefWidth = 250.0

        val colCell = TableColumn<DefectViewItem, String>("Ячейка")
        colCell.cellValueFactory = PropertyValueFactory("cell")
        colCell.prefWidth = 100.0
        colCell.style = "-fx-alignment: CENTER;"

        val colDefect = TableColumn<DefectViewItem, String>("Вид дефекта")
        colDefect.cellValueFactory = PropertyValueFactory("defectName")
        colDefect.prefWidth = 220.0

        val colDescription = TableColumn<DefectViewItem, String>("Описание")
        colDescription.cellValueFactory = PropertyValueFactory("description")
        colDescription.prefWidth = 350.0  // ← ШИРЕ

        val colStatus = TableColumn<DefectViewItem, String>("Статус")
        colStatus.cellValueFactory = PropertyValueFactory("status")
        colStatus.prefWidth = 100.0
        colStatus.style = "-fx-alignment: CENTER;"
        colStatus.setCellFactory {
            object : TableCell<DefectViewItem, String>() {
                override fun updateItem(item: String?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty || item == null) {
                        text = null
                    } else {
                        text = if (item == "fixed") "✅ Устранён" else "🟡 Обнаружен"
                    }
                }
            }
        }

        tableView.columns.addAll(colEquipment, colCell, colDefect, colDescription, colStatus)

        val observableData = FXCollections.observableArrayList(allDefects)
        tableView.items = observableData

        fun applyFilter() {
            val status = statusFilter.value
            val search = searchField.text.lowercase()

            val filtered = allDefects.filter { item ->
                val statusMatch = status == "Все статусы" ||
                        (status == "🟡 Обнаружен" && item.status != "fixed") ||
                        (status == "✅ Устранён" && item.status == "fixed")
                val searchMatch = search.isEmpty() || item.equipmentName.lowercase().contains(search)
                statusMatch && searchMatch
            }
            tableView.items = FXCollections.observableArrayList(filtered)
        }

        searchField.textProperty().addListener { _, _, _ -> applyFilter() }
        statusFilter.valueProperty().addListener { _, _, _ -> applyFilter() }

        val resetBtn = Button("Сбросить")
        resetBtn.style = "-fx-background-color: #6c757d; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 4px 16px; -fx-background-radius: 4px;"
        resetBtn.setOnAction {
            statusFilter.selectionModel.selectFirst()
            searchField.clear()
            applyFilter()
        }

        filterPanel.children.addAll(
            Label("Статус:"), statusFilter,
            searchField,
            resetBtn
        )

        tableView.setOnMouseClicked { event ->
            if (event.clickCount == 2) {
                val selected = tableView.selectionModel.selectedItem
                if (selected != null) {
                    val equipment = loadEquipment().find { it.id == selected.equipmentId }
                    if (equipment != null) {
                        val cardController = EquipmentCardController(
                            equipment = equipment,
                            database = database,
                            onDefectChanged = { }
                        )
                        cardController.show()
                        (tableView.scene.window as Stage).close()
                    }
                }
            }
        }

        val closeBtn = Button("✕ Закрыть")
        closeBtn.style = "-fx-background-color: #dc3545; -fx-text-fill: white; -fx-padding: 8px 20px; -fx-background-radius: 6px;"
        closeBtn.setOnAction { (closeBtn.scene.window as Stage).close() }

        val reportBtn = Button("📊 Создать отчёт")
        reportBtn.style = "-fx-background-color: #ffc107; -fx-text-fill: #333; -fx-padding: 8px 20px; -fx-background-radius: 6px; -fx-font-weight: bold;"
        reportBtn.setOnAction {
            val stage = reportBtn.scene.window as Stage
            stage.close()
            onCreateReport()
        }

        // ===== НОВАЯ КНОПКА "УПРАВЛЕНИЕ ВИДАМИ ДЕФЕКТОВ" =====
        val manageTypesBtn = Button("🔧 Виды дефектов")
        manageTypesBtn.style = "-fx-background-color: #6f42c1; -fx-text-fill: white; -fx-padding: 8px 20px; -fx-background-radius: 6px; -fx-font-weight: bold;"
        manageTypesBtn.setOnAction {
            showManageDefectTypesDialog()
        }

        val bottomPanel = HBox(20.0, manageTypesBtn, reportBtn, closeBtn)
        bottomPanel.alignment = Pos.CENTER_RIGHT

        mainLayout.children.addAll(headerLabel, filterPanel, tableView, bottomPanel)

        val popupStage = Stage()
        popupStage.title = "📊 Список дефектов"
        popupStage.scene = Scene(mainLayout, 1100.0, 800.0)  // ← ШИРЕ И ВЫШЕ
        popupStage.isResizable = true
        popupStage.minWidth = 900.0
        popupStage.minHeight = 600.0

        defectsListStage = popupStage
        popupStage.setOnHidden {
            defectsListStage = null
        }

        popupStage.showAndWait()
    }

    // ======================== КЛИКИ ========================

    // ======================== КЛИКИ (ПОЛНОСТЬЮ ПЕРЕПИСАНЫ) ========================

    private fun setupClickHandler() {
        // ============================================================
        //  ОБРАБОТЧИК НАЖАТИЯ МЫШИ
        // ============================================================
        webView.setOnMousePressed { event: MouseEvent ->
            if (!isEditMode) {
                if (event.isPrimaryButtonDown) {
                    isDragging = true
                    lastMouseX = event.x
                    lastMouseY = event.y
                    dragStartX = event.x
                    dragStartY = event.y
                    webView.engine.executeScript("""
                    document.getElementById('container').classList.add('dragging');
                """.trimIndent())
                }
            } else {
                if (event.isPrimaryButtonDown) {
                    val markerId = findClosestMarker(event.x, event.y)
                    if (markerId != null) {
                        println("🖱️ НАЖАТИЕ НА МАРКЕР: $markerId")
                        isDraggingMarker = true
                        webView.engine.executeScript("""
                        window.draggingMarkerId = '$markerId';
                        window.dragStartX = ${event.x};
                        window.dragStartY = ${event.y};
                        var marker = document.getElementById('$markerId');
                        if (marker) {
                            var leftStr = marker.style.left;
                            var topStr = marker.style.top;
                            window.dragOrigLeftPercent = parseFloat(leftStr);
                            window.dragOrigTopPercent = parseFloat(topStr);
                            
                            if (isNaN(window.dragOrigLeftPercent) || isNaN(window.dragOrigTopPercent)) {
                                var computed = window.getComputedStyle(marker);
                                window.dragOrigLeftPercent = parseFloat(computed.left);
                                window.dragOrigTopPercent = parseFloat(computed.top);
                            }
                            
                            if (isNaN(window.dragOrigLeftPercent) || isNaN(window.dragOrigTopPercent)) {
                                var wrapper = document.getElementById('image-wrapper');
                                var wrapperRect = wrapper.getBoundingClientRect();
                                var markerRect = marker.getBoundingClientRect();
                                var leftPx = markerRect.left - wrapperRect.left + markerRect.width / 2;
                                var topPx = markerRect.top - wrapperRect.top + markerRect.height / 2;
                                window.dragOrigLeftPercent = (leftPx / wrapperRect.width) * 100;
                                window.dragOrigTopPercent = (topPx / wrapperRect.height) * 100;
                            }
                            
                            marker.style.cursor = 'grabbing';
                            console.log('✅ Маркер захвачен: ' + marker.id);
                            console.log('✅ orig left: ' + window.dragOrigLeftPercent + '%, top: ' + window.dragOrigTopPercent + '%');
                        }
                    """.trimIndent())
                        event.consume()
                    }
                }
            }
        }

        // ============================================================
        //  ОБРАБОТЧИК ДВИЖЕНИЯ МЫШИ
        // ============================================================
        webView.setOnMouseDragged { event: MouseEvent ->
            if (!isEditMode) {
                if (isDragging) {
                    val deltaX = event.x - lastMouseX
                    val deltaY = event.y - lastMouseY
                    currentTranslateX += deltaX
                    currentTranslateY += deltaY
                    lastMouseX = event.x
                    lastMouseY = event.y
                    webView.engine.executeScript("""
                    var wrapper = document.getElementById('image-wrapper');
                    wrapper.style.transform = 'translate(${currentTranslateX}px, ${currentTranslateY}px) scale($zoomLevel)';
                    wrapper.style.transformOrigin = 'center center';
                """.trimIndent())
                    event.consume()
                }
            } else {
                if (isDraggingMarker) {
                    webView.engine.executeScript("""
                    (function() {
                        var marker = document.getElementById(window.draggingMarkerId);
                        if (!marker) return;
                        
                        var wrapper = document.getElementById('image-wrapper');
                        var wrapperRect = wrapper.getBoundingClientRect();
                        
                        var deltaX = ${event.x} - window.dragStartX;
                        var deltaY = ${event.y} - window.dragStartY;
                        
                        var deltaPercentX = (deltaX / wrapperRect.width) * 100;
                        var deltaPercentY = (deltaY / wrapperRect.height) * 100;
                        
                        var newLeftPercent = window.dragOrigLeftPercent + deltaPercentX;
                        var newTopPercent = window.dragOrigTopPercent + deltaPercentY;
                        
                        marker.style.left = newLeftPercent + '%';
                        marker.style.top = newTopPercent + '%';
                        
                        window.dragOrigLeftPercent = newLeftPercent;
                        window.dragOrigTopPercent = newTopPercent;
                        window.dragStartX = ${event.x};
                        window.dragStartY = ${event.y};
                    })();
                """.trimIndent())
                    event.consume()
                }
            }
        }

        // ============================================================
        //  ОБРАБОТЧИК ОТПУСКАНИЯ МЫШИ (ОПТИМИЗИРОВАН)
        // ============================================================
        webView.setOnMouseReleased { event: MouseEvent ->
            if (!isEditMode) {
                if (isDragging) {
                    isDragging = false
                    webView.engine.executeScript("""
                    document.getElementById('container').classList.remove('dragging');
                """.trimIndent())
                }
            } else {
                if (isDraggingMarker) {
                    val markerId = webView.engine.executeScript("""
                    (function() {
                        return window.draggingMarkerId || null;
                    })();
                """.trimIndent()) as? String

                    if (markerId != null) {
                        val jsonResult = webView.engine.executeScript("""
                        (function() {
                            var marker = document.getElementById('$markerId');
                            if (!marker) {
                                return JSON.stringify({ error: 'marker_not_found' });
                            }
                            
                            var equipmentId = marker.dataset.equipmentId;
                            if (!equipmentId) {
                                var parts = '$markerId'.split('-marker-');
                                if (parts.length > 0) {
                                    equipmentId = parts[0];
                                }
                            }
                            
                            var rect = marker.getBoundingClientRect();
                            var wrapper = document.getElementById('image-wrapper');
                            var wrapperRect = wrapper.getBoundingClientRect();
                            
                            var leftPx = rect.left - wrapperRect.left + rect.width / 2;
                            var topPx = rect.top - wrapperRect.top + rect.height / 2;
                            
                            var leftPercent = (leftPx / wrapperRect.width) * 100;
                            var topPercent = (topPx / wrapperRect.height) * 100;
                            
                            var data = {
                                equipmentId: equipmentId,
                                leftPercent: leftPercent,
                                topPercent: topPercent
                            };
                            
                            return JSON.stringify(data);
                        })();
                    """.trimIndent()) as? String

                        if (jsonResult != null && jsonResult != "null" && !jsonResult.contains("error")) {
                            try {
                                val type = object : TypeToken<Map<String, Any>>() {}.type
                                val data: Map<String, Any> = gson.fromJson(jsonResult, type)

                                val equipmentId = data["equipmentId"] as? String ?: ""
                                val leftPercent = (data["leftPercent"] as? Double) ?: 0.0
                                val topPercent = (data["topPercent"] as? Double) ?: 0.0

                                if (equipmentId.isNotEmpty()) {
                                    saveMarkerPositionOptimized(equipmentId, markerId, leftPercent, topPercent)
                                    showToast("✅ Маркер перемещён")
                                } else {
                                    showToast("⚠️ Ошибка при перетаскивании маркера")
                                }
                            } catch (e: Exception) {
                                println("❌ Ошибка парсинга JSON: ${e.message}")
                                showToast("⚠️ Ошибка при перетаскивании маркера")
                            }
                        } else {
                            showToast("⚠️ Ошибка при перетаскивании маркера")
                        }

                        webView.engine.executeScript("""
                        window.draggingMarkerId = null;
                        window.dragStartX = null;
                        window.dragStartY = null;
                        window.dragOrigLeftPercent = null;
                        window.dragOrigTopPercent = null;
                        var marker = document.getElementById('$markerId');
                        if (marker) marker.style.cursor = 'grab';
                    """.trimIndent())
                        isDraggingMarker = false
                        event.consume()
                    }
                }
            }
        }

        // ============================================================
        //  КЛИК
        // ============================================================
        webView.setOnMouseClicked { event: MouseEvent ->
            if (!isEditMode) {
                val dx = event.x - dragStartX
                val dy = event.y - dragStartY
                val distance = Math.sqrt(dx * dx + dy * dy)
                val wasDrag = distance > DRAG_THRESHOLD

                if (event.clickCount == 1 && event.button == javafx.scene.input.MouseButton.PRIMARY && !wasDrag) {
                    handleEquipmentClick(event.x, event.y)
                }
                if (event.clickCount == 2) {
                    zoomLevel = 1.0
                    currentTranslateX = 0.0
                    currentTranslateY = 0.0
                    webView.engine.executeScript("""
                    document.getElementById('image-wrapper').style.transform = 'translate(0px, 0px) scale(1)';
                    document.getElementById('image-wrapper').style.transformOrigin = 'center center';
                """.trimIndent())
                }
            } else {
                if (event.clickCount == 1 && event.button == javafx.scene.input.MouseButton.PRIMARY) {
                    val isMarker = isClickOnMarker(event.x, event.y)
                    if (!isMarker) {
                        println("🖱️ Клик в режиме редактирования!")
                        addEquipmentAtPosition(event.x, event.y)
                    }
                }
            }
        }

        // ============================================================
        //  КОНТЕКСТНОЕ МЕНЮ
        // ============================================================
        webView.setOnContextMenuRequested { event ->
            if (isEditMode) {
                val markerId = findClosestMarker(event.x, event.y)
                if (markerId != null) {
                    showContextMenu(event.x, event.y, markerId)
                }
            }
        }

        webView.setOnMouseExited {
            if (!isEditMode && isDragging) {
                isDragging = false
                webView.engine.executeScript("""
                document.getElementById('container').classList.remove('dragging');
            """.trimIndent())
            }
        }

        // ===== ПОДСВЕТКА УПРАВЛЯЕТСЯ ЧЕРЕЗ МЕНЮ =====
// setupMarkerHighlight() - убрано, управляется через toggleHighlightMode()
    }

// ======================== ПОИСК БЛИЖАЙШЕГО МАРКЕРА ========================

    private fun findClosestMarker(x: Double, y: Double): String? {
        return webView.engine.executeScript("""
        (function() {
            var container = document.getElementById('container');
            var rect = container.getBoundingClientRect();
            var markers = document.querySelectorAll('.equipment-marker');
            var clickX = $x;
            var clickY = $y;
            
            var closestMarker = null;
            var closestDistance = Infinity;
            var HIT_RADIUS = 30; // Радиус поиска в пикселях
            
            for (var i = 0; i < markers.length; i++) {
                var marker = markers[i];
                var markerRect = marker.getBoundingClientRect();
                
                // Центр маркера относительно container
                var centerX = markerRect.left + markerRect.width / 2 - rect.left;
                var centerY = markerRect.top + markerRect.height / 2 - rect.top;
                
                // Расстояние от клика до центра маркера
                var dx = clickX - centerX;
                var dy = clickY - centerY;
                var distance = Math.sqrt(dx * dx + dy * dy);
                
                // Размер маркера + запас
                var halfSize = Math.max(markerRect.width, markerRect.height) / 2 + 10;
                var maxDistance = Math.max(halfSize, HIT_RADIUS);
                
                if (distance < maxDistance && distance < closestDistance) {
                    closestMarker = marker;
                    closestDistance = distance;
                }
            }
            
            return closestMarker ? closestMarker.id : null;
        })();
    """.trimIndent()) as? String
    }

// ======================== ПРОВЕРКА КЛИКА ПО МАРКЕРУ ========================

    private fun isClickOnMarker(x: Double, y: Double): Boolean {
        return webView.engine.executeScript("""
        (function() {
            var container = document.getElementById('container');
            var rect = container.getBoundingClientRect();
            var markers = document.querySelectorAll('.equipment-marker');
            var clickX = $x;
            var clickY = $y;
            var HIT_RADIUS = 30;
            
            for (var i = 0; i < markers.length; i++) {
                var marker = markers[i];
                var markerRect = marker.getBoundingClientRect();
                
                var centerX = markerRect.left + markerRect.width / 2 - rect.left;
                var centerY = markerRect.top + markerRect.height / 2 - rect.top;
                
                var dx = clickX - centerX;
                var dy = clickY - centerY;
                var distance = Math.sqrt(dx * dx + dy * dy);
                var halfSize = Math.max(markerRect.width, markerRect.height) / 2 + 10;
                var maxDistance = Math.max(halfSize, HIT_RADIUS);
                
                if (distance < maxDistance) {
                    return true;
                }
            }
            return false;
        })();
    """.trimIndent()) as? Boolean ?: false
    }

// ======================== ПОДСВЕТКА МАРКЕРОВ ПРИ НАВЕДЕНИИ (ИСПРАВЛЕНА) ========================

    private fun setupMarkerHighlight() {
        webView.engine.executeScript("""
        (function() {
            // Добавляем CSS для подсветки
            var style = document.createElement('style');
            style.textContent = `
                .equipment-marker {
                    transition: all 0.2s ease;
                    z-index: 10;
                }
                /* Маркеры скрыты, но при наведении появляются */
                .equipment-marker.hidden {
                    opacity: 0.3;
                    pointer-events: auto !important;
                    transition: all 0.2s ease;
                }
                .equipment-marker.hidden .dot {
                    opacity: 0.3 !important;
                    pointer-events: auto !important;
                }
                .equipment-marker.hidden .tooltip-text {
                    opacity: 0 !important;
                    visibility: hidden !important;
                    transition: all 0.2s ease;
                }
                /* При наведении на скрытый маркер — он появляется */
                .equipment-marker.hidden:hover {
                    opacity: 1 !important;
                    transform: translate(-50%, -50%) scale(1.15) !important;
                    z-index: 15 !important;
                }
                .equipment-marker.hidden:hover .dot {
                    opacity: 1 !important;
                }
                .equipment-marker.hidden:hover .tooltip-text {
                    opacity: 1 !important;
                    visibility: visible !important;
                }
                /* Подсветка при наведении (для всех маркеров) */
                .equipment-marker.highlighted {
                    transform: translate(-50%, -50%) scale(1.3) !important;
                    z-index: 20 !important;
                    filter: brightness(1.3);
                }
                .equipment-marker.highlighted .dot {
                    box-shadow: 0 0 20px rgba(255, 255, 0, 0.6) !important;
                    border-color: #ffeb3b !important;
                }
                .equipment-marker .dot {
                    transition: all 0.2s ease;
                }
                /* Подсветка для скрытых маркеров */
                .equipment-marker.hidden.highlighted {
                    opacity: 1 !important;
                    transform: translate(-50%, -50%) scale(1.3) !important;
                    z-index: 25 !important;
                }
                .equipment-marker.hidden.highlighted .dot {
                    opacity: 1 !important;
                    box-shadow: 0 0 20px rgba(255, 255, 0, 0.6) !important;
                    border-color: #ffeb3b !important;
                }
                .equipment-marker.hidden.highlighted .tooltip-text {
                    opacity: 1 !important;
                    visibility: visible !important;
                }
            `;
            document.head.appendChild(style);
            
            // Функция для добавления обработчиков на маркер
            function addHighlightListeners(marker) {
                if (marker.classList.contains('has-listener')) return;
                marker.classList.add('has-listener');
                
                marker.addEventListener('mouseenter', function() {
                    // Убираем подсветку со всех маркеров
                    document.querySelectorAll('.equipment-marker').forEach(function(m) {
                        m.classList.remove('highlighted');
                    });
                    this.classList.add('highlighted');
                });
                
                marker.addEventListener('mouseleave', function() {
                    this.classList.remove('highlighted');
                });
            }
            
            // Добавляем обработчики на существующие маркеры
            var markers = document.querySelectorAll('.equipment-marker');
            markers.forEach(function(marker) {
                addHighlightListeners(marker);
            });
            
            // Обновляем подсветку при добавлении новых маркеров
            var observer = new MutationObserver(function() {
                var markers = document.querySelectorAll('.equipment-marker:not(.has-listener)');
                markers.forEach(function(marker) {
                    addHighlightListeners(marker);
                });
            });
            
            var container = document.getElementById('equipment-container');
            if (container) {
                observer.observe(container, { childList: true, subtree: true });
            }
            
            console.log('✅ Подсветка маркеров настроена (включая скрытые)');
        })();
    """.trimIndent())
    }

// ======================== ОБРАБОТЧИК КЛИКА ПО МАРКЕРУ (ДЛЯ ОТКРЫТИЯ КАРТОЧКИ) ========================

    private fun handleEquipmentClick(x: Double, y: Double) {
        val markerId = findClosestMarker(x, y)

        if (markerId != null) {
            val equipmentId = webView.engine.executeScript("""
        (function() {
            var marker = document.getElementById('$markerId');
            if (!marker) return null;
            return marker.dataset.equipmentId || marker.id;
        })();
    """.trimIndent()) as? String

            if (equipmentId != null) {
                val equipment = loadEquipment().find { it.id == equipmentId }
                if (equipment != null) {
                    val cardController = EquipmentCardController(
                        equipment = equipment,
                        database = database,
                        onDefectChanged = { }
                    )
                    cardController.show()
                } else {
                    showError("Оборудование не найдено")
                }
            }
        }
    }

// ======================== КОНТЕКСТНОЕ МЕНЮ (ПЕРЕПИСАНО) ========================

    private fun showContextMenu(x: Double, y: Double, markerId: String) {
        println("🔍 showContextMenu: markerId = $markerId")
        val contextMenu = ContextMenu()

        var equipment: EquipmentData? = null
        var isExtraMarker = false

        val markerInfo = webView.engine.executeScript("""
        (function() {
            var marker = document.getElementById('$markerId');
            if (!marker) return null;
            
            var realEquipmentId = marker.dataset.equipmentId || null;
            if (!realEquipmentId) {
                var parts = '$markerId'.split('-marker-');
                if (parts.length > 0 && parts[0].startsWith('equipment-')) {
                    realEquipmentId = parts[0];
                }
            }
            
            return {
                equipmentId: realEquipmentId,
                isExtra: marker.classList.contains('marker-extra')
            };
        })();
    """.trimIndent()) as? Map<*, *>

        if (markerInfo != null) {
            val realId = markerInfo["equipmentId"] as? String
            isExtraMarker = markerInfo["isExtra"] as? Boolean ?: false

            if (realId != null) {
                equipment = loadEquipment().find { it.id == realId }
            }
        }

        if (equipment == null) {
            // Ищем по позиции
            val foundId = webView.engine.executeScript("""
            (function() {
                var marker = document.getElementById('$markerId');
                if (!marker) return null;
                
                var left = parseFloat(marker.style.left);
                var top = parseFloat(marker.style.top);
                
                for (var i = 0; i < window.equipment.length; i++) {
                    var eq = window.equipment[i];
                    
                    if (Math.abs(eq.left - left) < 0.1 && Math.abs(eq.top - top) < 0.1) {
                        return eq.id;
                    }
                    if (eq.markers) {
                        for (var j = 0; j < eq.markers.length; j++) {
                            var m = eq.markers[j];
                            if (Math.abs(m.left - left) < 0.1 && Math.abs(m.top - top) < 0.1) {
                                return eq.id;
                            }
                        }
                    }
                }
                return null;
            })();
        """.trimIndent()) as? String

            if (foundId != null) {
                equipment = loadEquipment().find { it.id == foundId }
            }
        }

        if (equipment == null) {
            val baseId = markerId.replace(Regex("-marker-\\d+$"), "")
            if (baseId != markerId) {
                equipment = loadEquipment().find { it.id == baseId }
            }
        }

        if (equipment == null) {
            showError("Оборудование не найдено. ID: $markerId")
            return
        }

        val freshEquipment = loadEquipment().find { it.id == equipment.id }
        val markersCount = freshEquipment?.markers?.size ?: 1

        val editItem = MenuItem("✏️ Редактировать")
        editItem.setOnAction {
            editEquipment(equipment.id)
            contextMenu.hide()
        }

        val deleteItem = MenuItem("🗑️ Удалить оборудование")
        deleteItem.setOnAction {
            deleteEquipment(equipment.id)
            contextMenu.hide()
        }

        val addMarkerItem = MenuItem("➕ Добавить дополнительный маркер '${equipment.name}' на схему")
        addMarkerItem.setOnAction {
            addMarkerToEquipment(equipment.id)
            contextMenu.hide()
        }

        contextMenu.items.addAll(editItem, deleteItem, addMarkerItem)

        if (isExtraMarker && markersCount > 1) {
            val deleteMarkerItem = MenuItem("🗑️ Удалить маркер")
            deleteMarkerItem.setOnAction {
                deleteMarker(equipment.id, markerId)
                contextMenu.hide()
            }
            contextMenu.items.add(deleteMarkerItem)
        }

        contextMenu.show(webView, x, y)
    }

// ======================== ДОБАВЛЕНИЕ ОБОРУДОВАНИЯ (ПРОВЕРКА КЛИКА ПО МАРКЕРУ) ========================

    private fun addEquipmentAtPosition(x: Double, y: Double) {
        println("📍 Добавление оборудования: x=$x, y=$y")

        if (currentEditingEquipmentId != null) {
            addMarkerToExistingEquipment(x, y)
            return
        }

        // Проверяем, не кликнули ли по маркеру
        if (isClickOnMarker(x, y)) {
            println("⚠️ Клик по маркеру, пропускаем добавление")
            return
        }

        val result = webView.engine.executeScript("""
        (function() {
            var wrapper = document.getElementById('image-wrapper');
            var rect = wrapper.getBoundingClientRect();
            var cx = (($x - rect.left) / rect.width * 100).toFixed(1);
            var cy = (($y - rect.top) / rect.height * 100).toFixed(1);
            return cx + ',' + cy;
        })();
    """.trimIndent()) as? String

        if (result != null && result.contains(",")) {
            val parts = result.split(",")
            val left = parts[0]
            val top = parts[1]

            Platform.runLater {
                val nameDialog = TextInputDialog()
                nameDialog.title = "Новое оборудование"
                nameDialog.headerText = "Введите диспетчерское наименование"
                nameDialog.contentText = "Наименование:"
                nameDialog.editor?.text = ""

                val nameResult = nameDialog.showAndWait()
                if (nameResult.isPresent) {
                    val name = nameResult.get().trim()
                    if (name.isNotEmpty()) {

                        val cell = selectCell()
                        if (cell == null) {
                            println("❌ Выбор ячейки отменён")
                            return@runLater
                        }

                        val typeDialog = ChoiceDialog(
                            EquipmentTypes.ALL_TYPES.find { it.first == "v_500" }?.second ?: "Выключатель 500 кВ",
                            EquipmentTypes.ALL_TYPES.map { it.second }
                        )
                        typeDialog.title = "Тип оборудования"
                        typeDialog.headerText = "Выберите тип"
                        typeDialog.contentText = "Тип:"

                        val typeResult = typeDialog.showAndWait()
                        if (typeResult.isPresent) {
                            val typeName = typeResult.get()
                            val type = EquipmentTypes.ALL_TYPES.find { it.second == typeName }?.first ?: "other"
                            val typeLabel = EquipmentTypes.getLetter(type)

                            val sizeDialog = ChoiceDialog("normal", listOf("small", "normal", "large"))
                            sizeDialog.title = "Размер метки"
                            sizeDialog.headerText = "Выберите размер метки на схеме"
                            sizeDialog.contentText = "Размер (small - для ОРУ-220/35, large - для 500 кВ):"

                            val sizeResult = sizeDialog.showAndWait()
                            if (sizeResult.isPresent) {
                                val size = sizeResult.get()

                                val id = "equipment-${System.currentTimeMillis()}"
                                equipmentCounter++

                                val escapedName = name.replace("'", "\\'")
                                val escapedCell = cell.replace("'", "\\'")

                                webView.engine.executeScript("""
                                (function() {
                                    var container = document.getElementById('equipment-container');
                                    if (!container) {
                                        var wrapper = document.getElementById('image-wrapper');
                                        if (wrapper) {
                                            container = document.createElement('div');
                                            container.id = 'equipment-container';
                                            wrapper.appendChild(container);
                                        }
                                    }
                                    if (!container) return;
                                    
                                    var marker = document.createElement('div');
                                    marker.className = 'equipment-marker $type $size';
                                    marker.id = '$id';
                                    marker.style.left = '${left}%';
                                    marker.style.top = '${top}%';
                                    marker.dataset.equipmentId = '$id';
                                    marker.dataset.markerIndex = '0';
                                    
                                    marker.innerHTML = '<div class="dot">$typeLabel</div><span class="tooltip-text">$escapedName</span>';
                                    container.appendChild(marker);
                                    
                                    if (!window.equipment) window.equipment = [];
                                    window.equipment.push({
                                        id: '$id',
                                        left: parseFloat('${left}'),
                                        top: parseFloat('${top}'),
                                        type: '$type',
                                        name: '$escapedName',
                                        letter: '$typeLabel',
                                        cell: '$escapedCell',
                                        size: '$size',
                                        markers: [{left: parseFloat('${left}'), top: parseFloat('${top}'), isMain: true}]
                                    });
                                    console.log('✅ Добавлено оборудование: $escapedName (ячейка: $escapedCell, размер: $size)');
                                })();
                            """.trimIndent())

                                saveEquipmentDirect()
                                syncWindowEquipment()

                                currentEditingEquipmentId = null

                                Platform.runLater {
                                    showToast("✅ Добавлено: $name")
                                }
                            }
                        }
                    }
                }
            }
        } else {
            println("❌ Ошибка: результат null")
            showError("Не удалось определить позицию на схеме")
        }
    }

    // ======================== ОПТИМИЗИРОВАННОЕ СОХРАНЕНИЕ МАРКЕРА ========================

    private fun saveMarkerPositionOptimized(equipmentId: String, markerId: String, newLeftPercent: Double, newTopPercent: Double) {
        println("💾 saveMarkerPositionOptimized: $equipmentId -> ($newLeftPercent%, $newTopPercent%)")

        if (equipmentId.isEmpty()) {
            println("❌ equipmentId пустой")
            return
        }

        val allEquipment = loadEquipment()
        var equipment = allEquipment.find { it.id == equipmentId }

        if (equipment == null) {
            val baseId = markerId.replace(Regex("-marker-\\d+(-\\d+)?$"), "")
            equipment = allEquipment.find { it.id == baseId }
        }

        if (equipment == null) {
            println("❌ Оборудование не найдено: $equipmentId")
            showToast("⚠️ Оборудование не найдено")
            return
        }

        var markerIndex = -1
        val idParts = markerId.split("-marker-")
        if (idParts.size > 1) {
            val indexFromId = idParts[1].split("-").firstOrNull()?.toIntOrNull()
            if (indexFromId != null && indexFromId < equipment.markers.size) {
                markerIndex = indexFromId
            }
        }

        if (markerIndex == -1) {
            for (i in equipment.markers.indices) {
                val m = equipment.markers[i]
                if (Math.abs(m.left - newLeftPercent) < 0.5 && Math.abs(m.top - newTopPercent) < 0.5) {
                    markerIndex = i
                    break
                }
            }
        }

        if (markerIndex == -1 && equipment.markers.isNotEmpty()) {
            markerIndex = 0
        }

        if (markerIndex == -1) {
            val newMarkers = equipment.markers + MarkerPosition(newLeftPercent, newTopPercent, isMain = false)
            val updatedEquipment = equipment.copy(markers = newMarkers)
            val updatedList = allEquipment.map { if (it.id == equipment.id) updatedEquipment else it }
            database.saveEquipment(updatedList)

            // Обновляем только window.equipment, НЕ перерисовываем все маркеры
            val updatedJson = gson.toJson(updatedList)
            webView.engine.executeScript("window.equipment = $updatedJson;")

            println("✅ Добавлен новый маркер для ${equipment.name}")
            showToast("✅ Маркер добавлен для ${equipment.name}")
            return
        }

        val updatedMarkers = equipment.markers.toMutableList()
        updatedMarkers[markerIndex] = updatedMarkers[markerIndex].copy(left = newLeftPercent, top = newTopPercent)

        val updatedEquipment = equipment.copy(markers = updatedMarkers)
        val updatedList = allEquipment.map {
            if (it.id == equipment.id) updatedEquipment else it
        }

        database.saveEquipment(updatedList)

        // ===== ОПТИМИЗАЦИЯ: обновляем ТОЛЬКО этот маркер, а не все =====
        webView.engine.executeScript("""
        (function() {
            // Обновляем позицию маркера
            var marker = document.getElementById('$markerId');
            if (marker) {
                marker.style.left = '${newLeftPercent}%';
                marker.style.top = '${newTopPercent}%';
            }
            
            // Обновляем данные в window.equipment
            var allEquipment = ${gson.toJson(updatedList)};
            window.equipment = allEquipment;
            
            console.log('✅ Маркер обновлён (оптимизированно)');
        })();
        """.trimIndent())

        println("✅ Сохранено в БД для ${equipment.name}")
        val formattedLeft = "%.1f".format(newLeftPercent)
        val formattedTop = "%.1f".format(newTopPercent)
        showToast("✅ Маркер перемещён на ${formattedLeft}%, ${formattedTop}%")
    }

    // ======================== ОСТАЛЬНЫЕ МЕТОДЫ (БЕЗ ИЗМЕНЕНИЙ) ========================

    private fun saveEquipmentDirect() {
        val result = webView.engine.executeScript("""
        JSON.stringify(window.equipment || [])
    """.trimIndent()) as? String

        if (result != null) {
            try {
                val type = object : TypeToken<List<EquipmentData>>() {}.type
                val equipment: List<EquipmentData> = gson.fromJson(result, type)

                if (equipment.isEmpty()) {
                    println("⚠️ Нет данных для сохранения")
                    return
                }

                val currentHash = equipment.hashCode()
                if (currentHash == lastSavedHash && isInitialized) {
                    println("ℹ️ Данные не изменились, пропускаем сохранение")
                    return
                }

                println("💾 Сохраняем ${equipment.size} записей (прямое сохранение)")
                database.saveEquipment(equipment)
                lastSavedHash = currentHash
                showToast("✅ Данные сохранены (${equipment.size} записей)")

            } catch (e: Exception) {
                showError("Ошибка сохранения: ${e.message}")
                e.printStackTrace()
            }
        } else {
            println("❌ Ошибка: результат скрипта null")
        }
    }

    private fun addMarkerToExistingEquipment(x: Double, y: Double) {
        val equipmentId = currentEditingEquipmentId ?: return

        val result = webView.engine.executeScript("""
        (function() {
            var wrapper = document.getElementById('image-wrapper');
            var rect = wrapper.getBoundingClientRect();
            var cx = (($x - rect.left) / rect.width * 100).toFixed(1);
            var cy = (($y - rect.top) / rect.height * 100).toFixed(1);
            return cx + ',' + cy;
        })();
    """.trimIndent()) as? String

        if (result != null && result.contains(",")) {
            val parts = result.split(",")
            val left = parts[0].toDouble()
            val top = parts[1].toDouble()

            val allEquipment = loadEquipment()
            val equipment = allEquipment.find { it.id == equipmentId }

            if (equipment != null) {
                val newMarkers = equipment.markers + MarkerPosition(left, top, isMain = false)
                val updatedEquipment = equipment.copy(markers = newMarkers)

                val updatedList = allEquipment.map {
                    if (it.id == equipmentId) updatedEquipment else it
                }
                database.saveEquipment(updatedList)
                syncWindowEquipment()

                webView.engine.executeScript("""
                (function() {
                    var container = document.getElementById('equipment-container');
                    if (!container) return;
                    
                    var marker = document.createElement('div');
                    var sizeClass = '${equipment.size}' || 'normal';
                    marker.className = 'equipment-marker ${equipment.type} ' + sizeClass + ' marker-extra';
                    if (!${markersVisible}) {
                        marker.className += ' hidden';
                    }
                    marker.id = '${equipmentId}-marker-' + Date.now();
                    marker.style.left = '${left}%';
                    marker.style.top = '${top}%';
                    marker.style.border = '2px dashed rgba(255,255,255,0.5)';
                    marker.dataset.equipmentId = '${equipment.id}';
                    marker.dataset.markerIndex = '${newMarkers.size - 1}';
                    
                    marker.innerHTML = '<div class="dot">${equipment.letter}</div><span class="tooltip-text">${equipment.name}</span>';
                    container.appendChild(marker);
                    console.log('✅ Добавлен доп. маркер для: ${equipment.name}');
                })();
            """.trimIndent())

                println("✅ Добавлен маркер для: ${equipment.name}")
                showToast("✅ Маркер добавлен для ${equipment.name}")

                currentEditingEquipmentId = null
                isEditMode = false
                toggleEditMode(false)
                editModeMenuItem.text = "✏️ Режим редактирования"

            } else {
                showError("Оборудование не найдено. ID: $equipmentId")
            }
        } else {
            showError("Не удалось определить позицию на схеме")
        }

        val updatedList2 = loadEquipment()
        val equipmentJson = gson.toJson(updatedList2)
        webView.engine.executeScript("""
        window.equipment = $equipmentJson;
        console.log('✅ window.equipment обновлён, маркеров: ' + window.equipment.length);
    """.trimIndent())
    }

    // ======================== TOAST ========================

    private fun showToast(message: String, duration: Duration = Duration.seconds(2.5)) {
        Platform.runLater {
            try {
                val toastContainer = StackPane()
                toastContainer.isMouseTransparent = true
                toastContainer.style = "-fx-background-color: transparent;"

                val toast = Label(message)
                toast.style = """
                -fx-background-color: rgba(0, 0, 0, 0.85);
                -fx-text-fill: white;
                -fx-font-size: 14px;
                -fx-font-weight: bold;
                -fx-padding: 12px 24px;
                -fx-background-radius: 8px;
                -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.4), 15, 0, 0, 4);
                -fx-border-color: rgba(255,255,255,0.15);
                -fx-border-radius: 8px;
                -fx-border-width: 1px;
                -fx-max-width: 600px;
                -fx-wrap-text: true;
                -fx-text-alignment: center;
            """.trimIndent()
                toast.isWrapText = true
                toast.maxWidth = 600.0
                toast.alignment = Pos.CENTER

                val scene = webView.scene
                if (scene != null) {
                    val root = scene.root as? javafx.scene.layout.Pane
                    if (root != null) {
                        root.children.filter { it is StackPane && it.isMouseTransparent && it.children.size == 1 && it.children[0] is Label }
                            .forEach { root.children.remove(it) }

                        toastContainer.children.add(toast)
                        root.children.add(toastContainer)

                        StackPane.setAlignment(toastContainer, Pos.TOP_CENTER)
                        StackPane.setMargin(toastContainer, Insets(60.0, 20.0, 0.0, 20.0))

                        toast.opacityProperty().set(0.0)
                        val fadeIn = FadeTransition(Duration.millis(300.0), toast)
                        fadeIn.fromValue = 0.0
                        fadeIn.toValue = 1.0
                        fadeIn.play()

                        val pause = PauseTransition(duration)
                        pause.setOnFinished {
                            val fadeOut = FadeTransition(Duration.millis(300.0), toast)
                            fadeOut.fromValue = 1.0
                            fadeOut.toValue = 0.0
                            fadeOut.setOnFinished {
                                root.children.remove(toastContainer)
                            }
                            fadeOut.play()
                        }
                        pause.play()
                    }
                }
            } catch (e: Exception) {
                println("❌ Ошибка отображения Toast: ${e.message}")
                Platform.runLater {
                    Alert(AlertType.INFORMATION).apply {
                        title = "Уведомление"
                        headerText = null
                        contentText = message
                        showAndWait()
                    }
                }
            }
        }
    }

    private fun deleteMarker(equipmentId: String, markerId: String) {
        println("🗑️ Удаление маркера: $markerId для оборудования: $equipmentId")

        val allEquipment = loadEquipment()
        val equipment = allEquipment.find { it.id == equipmentId }

        if (equipment == null) {
            showError("Оборудование не найдено")
            return
        }

        if (equipment.markers.size <= 1) {
            showInfo("⚠️ Нельзя удалить единственный маркер оборудования. Используйте 'Удалить оборудование'")
            return
        }

        val markerIndex = webView.engine.executeScript("""
        (function() {
            var marker = document.getElementById('$markerId');
            if (marker && marker.dataset && marker.dataset.markerIndex) {
                return parseInt(marker.dataset.markerIndex);
            }
            return -1;
        })();
    """.trimIndent()) as? Int ?: -1

        if (markerIndex < 0 || markerIndex >= equipment.markers.size) {
            showError("Маркер не найден")
            return
        }

        val newMarkers = equipment.markers.toMutableList()
        newMarkers.removeAt(markerIndex)

        val updatedMarkers = newMarkers.mapIndexed { index, pos ->
            if (index == 0) pos.copy(isMain = true) else pos.copy(isMain = false)
        }

        val updatedEquipment = equipment.copy(markers = updatedMarkers)

        val updatedList = allEquipment.map {
            if (it.id == equipmentId) updatedEquipment else it
        }
        database.saveEquipment(updatedList)
        syncWindowEquipment()

        webView.engine.executeScript("""
        (function() {
            var marker = document.getElementById('$markerId');
            if (marker) marker.remove();
            console.log('🗑️ Маркер удалён');
        })();
    """.trimIndent())

        val updatedList2 = loadEquipment()
        val equipmentJson = gson.toJson(updatedList2)
        webView.engine.executeScript("window.equipment = $equipmentJson;")

        equipmentCounter = updatedList2.size
        showInfo("🗑️ Маркер удалён")
    }

    private fun editEquipment(equipmentId: String) {
        println("=".repeat(60))
        println("✏️ РЕДАКТИРОВАНИЕ ОБОРУДОВАНИЯ")
        println("📌 ID: $equipmentId")
        println("=".repeat(60))

        val allEquipment = loadEquipment()
        val equipment = allEquipment.find { it.id == equipmentId }

        if (equipment == null) {
            showError("Оборудование не найдено. ID: $equipmentId")
            return
        }

        val currentName = equipment.name
        val currentType = equipment.type
        val currentCell = equipment.cell
        val currentSize = equipment.size

        Platform.runLater {
            val nameDialog = TextInputDialog()
            nameDialog.title = "Редактирование оборудования"
            nameDialog.headerText = "Введите новое название"
            nameDialog.contentText = "Наименование:"
            nameDialog.editor?.text = currentName

            val nameResult = nameDialog.showAndWait()
            if (nameResult.isPresent) {
                val newName = nameResult.get().trim()
                if (newName.isNotEmpty()) {

                    val newCell = selectCell(currentCell)
                    if (newCell == null) {
                        println("❌ Выбор ячейки отменён")
                        return@runLater
                    }

                    val typeDialog = ChoiceDialog(
                        EquipmentTypes.ALL_TYPES.find { it.first == currentType }?.second ?: currentType,
                        EquipmentTypes.ALL_TYPES.map { it.second }
                    )
                    typeDialog.title = "Тип оборудования"
                    typeDialog.headerText = "Выберите тип"
                    typeDialog.contentText = "Тип:"

                    val typeResult = typeDialog.showAndWait()
                    if (typeResult.isPresent) {
                        val typeName = typeResult.get()
                        val newType = EquipmentTypes.ALL_TYPES.find { it.second == typeName }?.first ?: "other"
                        val newLetter = EquipmentTypes.getLetter(newType)

                        val sizeDialog = ChoiceDialog(currentSize, listOf("small", "normal", "large"))
                        sizeDialog.title = "Размер метки"
                        sizeDialog.headerText = "Выберите размер метки на схеме"
                        sizeDialog.contentText = "Размер (small - для ОРУ-220/35, large - для 500 кВ):"

                        val sizeResult = sizeDialog.showAndWait()
                        if (sizeResult.isPresent) {
                            val newSize = sizeResult.get()

                            val cellChanged = newCell != currentCell

                            val updatedList = allEquipment.map { item ->
                                if (item.id == equipmentId) {
                                    item.copy(
                                        name = newName,
                                        type = newType,
                                        letter = newLetter,
                                        cell = newCell,
                                        size = newSize
                                    )
                                } else if (cellChanged && item.parentId == equipmentId) {
                                    // ===== Обновляем ячейку у всех прямых детей =====
                                    item.copy(cell = newCell)
                                } else {
                                    item
                                }
                            }

                            database.saveEquipment(updatedList)
                            syncWindowEquipment()
                            val updatedListForWindow = loadEquipment()
                            val equipmentJson = gson.toJson(updatedListForWindow)
                            webView.engine.executeScript("window.equipment = $equipmentJson;")
                            println("✅ База данных обновлена")

                            val escapedName = newName.replace("'", "\\'")
                            val escapedType = newType.replace("'", "\\'")
                            val escapedLetter = newLetter.replace("'", "\\'")
                            val escapedCell = newCell.replace("'", "\\'")
                            val escapedSize = newSize.replace("'", "\\'")

                            webView.engine.executeScript("""
                        (function() {
                            var id = '$equipmentId';
                            var marker = document.getElementById(id);
                            if (marker) {
                                marker.className = 'equipment-marker $escapedType $escapedSize';
                                var dot = marker.querySelector('.dot');
                                if (dot) dot.textContent = '$escapedLetter';
                                var tooltip = marker.querySelector('.tooltip-text');
                                if (tooltip) {
                                    tooltip.textContent = '$escapedName';
                                }
                            }
                            if (window.equipment) {
                                for (var i = 0; i < window.equipment.length; i++) {
                                    if (window.equipment[i].id === id) {
                                        window.equipment[i].name = '$escapedName';
                                        window.equipment[i].type = '$escapedType';
                                        window.equipment[i].letter = '$escapedLetter';
                                        window.equipment[i].cell = '$escapedCell';
                                        window.equipment[i].size = '$escapedSize';
                                        break;
                                    }
                                }
                            }
                        })();
                        """.trimIndent())

                            equipmentCounter = updatedList.size
                            showInfo("Оборудование обновлено: $newName (размер: $newSize)")

                            Platform.runLater {
                                val stages = Stage.getWindows()
                                for (window in stages) {
                                    if (window is Stage && window.title == "📋 Список оборудования") {
                                        val root = window.scene?.root
                                        if (root is VBox) {
                                            val tableView = findTableView(root)
                                            if (tableView != null) {
                                                @Suppress("UNCHECKED_CAST")
                                                val table = tableView as TableView<EquipmentTableItem>

                                                val updatedData = loadEquipment()
                                                val items = updatedData.mapIndexed { index, eq ->
                                                    val typeDisplayName = EquipmentTypes.ALL_TYPES.toMap()[eq.type] ?: eq.type
                                                    EquipmentTableItem(
                                                        number = index + 1,
                                                        id = eq.id,
                                                        name = eq.name,
                                                        type = typeDisplayName,
                                                        cell = eq.cell,
                                                        left = eq.left,
                                                        top = eq.top
                                                    )
                                                }
                                                table.items = FXCollections.observableArrayList(items)

                                                val label = findCountLabel(root)
                                                label?.text = "Показано: ${updatedData.size} из ${updatedData.size}"
                                            }
                                        }
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun findTableView(node: javafx.scene.Node): TableView<*>? {
        if (node is TableView<*>) {
            return node
        }
        if (node is javafx.scene.layout.Pane) {
            for (child in node.children) {
                val result = findTableView(child)
                if (result != null) return result
            }
        }
        return null
    }

    private fun findCountLabel(node: javafx.scene.Node): Label? {
        if (node is Label && node.text?.startsWith("Показано:") == true) {
            return node
        }
        if (node is javafx.scene.layout.Pane) {
            for (child in node.children) {
                val result = findCountLabel(child)
                if (result != null) return result
            }
        }
        return null
    }

    private fun loadEquipment(): List<EquipmentData> {
        return database.loadAllEquipment()
    }

    private fun selectCell(currentCell: String = ""): String? {
        val allEquipment = loadEquipment()
        val existingCells = allEquipment
            .mapNotNull { it.cell.takeIf { cell -> cell.isNotEmpty() } }
            .distinct()
            .sorted()

        val cellOptions = existingCells + "➕ Создать новую"
        val defaultCell = if (currentCell.isNotEmpty() && existingCells.contains(currentCell)) {
            currentCell
        } else {
            cellOptions.firstOrNull() ?: "➕ Создать новую"
        }

        val cellDialog = ChoiceDialog(defaultCell, cellOptions)
        cellDialog.title = "Номер ячейки"
        cellDialog.headerText = "Выберите существующую ячейку или создайте новую"
        cellDialog.contentText = "Ячейка:"

        val cellResult = cellDialog.showAndWait()
        if (cellResult.isPresent) {
            val selectedCell = cellResult.get()

            if (selectedCell == "➕ Создать новую") {
                val newCellDialog = TextInputDialog()
                newCellDialog.title = "Новая ячейка"
                newCellDialog.headerText = "Введите номер новой ячейки"
                newCellDialog.contentText = "Ячейка:"
                newCellDialog.editor?.text = ""

                val newCellResult = newCellDialog.showAndWait()
                return if (newCellResult.isPresent) {
                    newCellResult.get().trim()
                } else {
                    null
                }
            } else {
                return selectedCell
            }
        }
        return null
    }

    private fun deleteEquipment(equipmentId: String) {
        val confirm = Alert(AlertType.CONFIRMATION)
        confirm.title = "Удаление оборудования"
        confirm.headerText = "🗑️ Вы уверены?"
        confirm.contentText = "Вы действительно хотите удалить это оборудование и ВСЕ его маркеры?\n\nЭто действие НЕЛЬЗЯ будет отменить!"

        val result = confirm.showAndWait()
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            println("❌ Удаление отменено")
            return
        }

        database.deleteById(equipmentId)
        syncWindowEquipment()

        webView.engine.executeScript("""
        (function() {
            var markers = document.querySelectorAll('[data-equipment-id="$equipmentId"]');
            markers.forEach(function(marker) {
                marker.remove();
            });
            
            if (window.equipment) {
                var index = -1;
                for (var i = 0; i < window.equipment.length; i++) {
                    if (window.equipment[i].id === '$equipmentId') {
                        index = i;
                        break;
                    }
                }
                if (index !== -1) window.equipment.splice(index, 1);
            }
        })();
    """.trimIndent())

        equipmentCounter = loadEquipment().size
        showToast("🗑️ Оборудование удалено")
    }

    // ======================== СПИСОК ОБОРУДОВАНИЯ ========================

    private fun viewEquipmentList() {
        equipmentListStage?.close()

        val result = webView.engine.executeScript("""
        JSON.stringify(window.equipment || [])
    """.trimIndent()) as? String

        if (result != null) {
            try {
                val type = object : TypeToken<List<EquipmentData>>() {}.type
                val allEquipment: List<EquipmentData> = gson.fromJson(result, type)

                if (allEquipment.isEmpty()) {
                    showInfo("📋 Нет сохраненного оборудования")
                    return
                }

                val cellSearchField = TextField()
                cellSearchField.promptText = "🔍 Поиск по ячейке..."
                cellSearchField.style = "-fx-pref-width: 180px; -fx-font-size: 13px; -fx-padding: 6px 10px; -fx-background-radius: 4px; -fx-border-color: #ced4da; -fx-border-radius: 4px;"

                val mainLayout = VBox(15.0)
                mainLayout.style = "-fx-background-color: white; -fx-padding: 20px;"
                mainLayout.prefWidth = 1080.0   // ← ШИРЕ
                mainLayout.prefHeight = 780.0

                val headerLabel = Label("📋 СПИСОК ОБОРУДОВАНИЯ")
                headerLabel.style = "-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #333;"

                val filterPanel = HBox(10.0)
                filterPanel.alignment = Pos.CENTER_LEFT
                filterPanel.style = "-fx-padding: 10px 0; -fx-background-color: #f8f9fa; -fx-border-color: #e9ecef; -fx-border-width: 0 0 1px 0;"

                val filterLabel = Label("🔍 Фильтр по типу:")
                filterLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"

                val typeFilter = ComboBox<String>()
                typeFilter.promptText = "Все типы"
                typeFilter.style = "-fx-pref-width: 180px; -fx-font-size: 13px; -fx-padding: 4px;"
                typeFilter.items.addAll(listOf("Все типы") + EquipmentTypes.ALL_TYPES.map { it.second })
                typeFilter.selectionModel.selectFirst()

                val countLabel = Label()
                countLabel.style = "-fx-text-fill: #6c757d; -fx-font-size: 13px; -fx-padding: 0 10px;"

                val searchField = TextField()
                searchField.promptText = "🔍 Поиск по названию или ID..."
                searchField.style = "-fx-pref-width: 250px; -fx-font-size: 13px; -fx-padding: 6px 10px; -fx-background-radius: 4px; -fx-border-color: #ced4da; -fx-border-radius: 4px;"

                val tableView = TableView<EquipmentTableItem>()
                tableView.style = "-fx-font-size: 13px; -fx-border-color: #dee2e6;"

                val colNumber = TableColumn<EquipmentTableItem, Int>("№")
                colNumber.cellValueFactory = PropertyValueFactory("number")
                colNumber.prefWidth = 45.0
                colNumber.style = "-fx-alignment: CENTER;"

                val colName = TableColumn<EquipmentTableItem, String>("Наименование")
                colName.cellValueFactory = PropertyValueFactory("name")
                colName.prefWidth = 200.0

                val colType = TableColumn<EquipmentTableItem, String>("Тип")
                colType.cellValueFactory = PropertyValueFactory("type")
                colType.prefWidth = 180.0

                val colCell = TableColumn<EquipmentTableItem, String>("Ячейка")
                colCell.cellValueFactory = PropertyValueFactory("cell")
                colCell.prefWidth = 70.0
                colCell.style = "-fx-alignment: CENTER;"

                val colX = TableColumn<EquipmentTableItem, Double>("X%")
                colX.cellValueFactory = PropertyValueFactory("left")
                colX.prefWidth = 60.0
                colX.style = "-fx-alignment: CENTER;"

                val colY = TableColumn<EquipmentTableItem, Double>("Y%")
                colY.cellValueFactory = PropertyValueFactory("top")
                colY.prefWidth = 60.0
                colY.style = "-fx-alignment: CENTER;"

                val colId = TableColumn<EquipmentTableItem, String>("ID")
                colId.cellValueFactory = PropertyValueFactory("id")
                colId.prefWidth = 120.0

                val colActions = TableColumn<EquipmentTableItem, Void>("Действие")
                colActions.prefWidth = 80.0
                colActions.style = "-fx-alignment: CENTER;"

                colActions.setCellFactory {
                    object : TableCell<EquipmentTableItem, Void>() {
                        private val deleteBtn = Button("✕")
                        private val hbox = HBox(5.0, deleteBtn)

                        init {
                            hbox.alignment = Pos.CENTER

                            deleteBtn.style = """
                            -fx-background-color: #dc3545;
                            -fx-text-fill: white;
                            -fx-font-size: 13px;
                            -fx-font-weight: bold;
                            -fx-padding: 2px 8px;
                            -fx-background-radius: 4px;
                            -fx-cursor: hand;
                        """.trimIndent()

                            deleteBtn.setOnAction {
                                val item = tableItem
                                if (item != null) {
                                    val confirm = Alert(AlertType.CONFIRMATION)
                                    confirm.title = "Удаление оборудования"
                                    confirm.headerText = "Удалить оборудование?"
                                    confirm.contentText = "Вы уверены, что хотите удалить '${item.name}'?"

                                    val result = confirm.showAndWait()
                                    if (result.isPresent && result.get() == ButtonType.OK) {
                                        deleteEquipment(item.id)
                                        val updatedData = loadEquipment()
                                        val updatedItems = updatedData.mapIndexed { index, eq ->
                                            val typeDisplayName = EquipmentTypes.ALL_TYPES.toMap()[eq.type] ?: eq.type
                                            EquipmentTableItem(
                                                number = index + 1,
                                                id = eq.id,
                                                name = eq.name,
                                                type = typeDisplayName,
                                                cell = eq.cell,
                                                left = eq.left,
                                                top = eq.top
                                            )
                                        }
                                        tableView.items = FXCollections.observableArrayList(updatedItems)
                                        countLabel.text = "Показано: ${updatedData.size} из ${allEquipment.size}"
                                    }
                                }
                            }

                            deleteBtn.hoverProperty().addListener { _, _, hovered ->
                                deleteBtn.style = if (hovered) """
                                -fx-background-color: #c82333;
                                -fx-text-fill: white;
                                -fx-font-size: 13px;
                                -fx-font-weight: bold;
                                -fx-padding: 2px 8px;
                                -fx-background-radius: 4px;
                                -fx-cursor: hand;
                            """.trimIndent()
                                else """
                                -fx-background-color: #dc3545;
                                -fx-text-fill: white;
                                -fx-font-size: 13px;
                                -fx-font-weight: bold;
                                -fx-padding: 2px 8px;
                                -fx-background-radius: 4px;
                                -fx-cursor: hand;
                            """.trimIndent()
                            }
                        }

                        override fun updateItem(item: Void?, empty: Boolean) {
                            super.updateItem(item, empty)
                            graphic = if (empty) null else hbox
                        }

                        private val tableItem: EquipmentTableItem?
                            get() = tableRow?.item
                    }
                }

                tableView.columns.addAll(
                    colNumber, colName, colType, colCell, colX, colY, colId, colActions
                )

                fun toTableItems(data: List<EquipmentData>): List<EquipmentTableItem> {
                    return data.mapIndexed { index, item ->
                        val typeDisplayName = EquipmentTypes.ALL_TYPES.toMap()[item.type] ?: item.type
                        EquipmentTableItem(
                            number = index + 1,
                            id = item.id,
                            name = item.name,
                            type = typeDisplayName,
                            cell = item.cell,
                            left = item.left,
                            top = item.top
                        )
                    }
                }

                fun updateTable(data: List<EquipmentData>) {
                    val items = toTableItems(data)
                    tableView.items = FXCollections.observableArrayList(items)
                    countLabel.text = "Показано: ${data.size} из ${allEquipment.size}"
                }

                fun applyFilter() {
                    val selectedType = typeFilter.value
                    val typeKey = when (selectedType) {
                        "Все типы" -> "all"
                        else -> EquipmentTypes.ALL_TYPES.find { it.second == selectedType }?.first ?: "all"
                    }

                    var filtered = allEquipment.filter { item ->
                        val typeMatch = typeKey == "all" || item.type == typeKey
                        typeMatch
                    }

                    val searchText = searchField.text
                    if (searchText.isNotEmpty()) {
                        filtered = filtered.filter {
                            it.name.contains(searchText, ignoreCase = true) ||
                                    it.id.contains(searchText, ignoreCase = true) ||
                                    it.type.contains(searchText, ignoreCase = true)
                        }
                    }

                    val cellSearchText = cellSearchField.text
                    if (cellSearchText.isNotEmpty()) {
                        filtered = filtered.filter {
                            it.cell.contains(cellSearchText, ignoreCase = true)
                        }
                    }

                    updateTable(filtered)
                }

                searchField.textProperty().addListener { _, _, _ -> applyFilter() }
                cellSearchField.textProperty().addListener { _, _, _ -> applyFilter() }

                val applyBtn = Button("Применить")
                applyBtn.style = "-fx-background-color: #007bff; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 4px 16px; -fx-background-radius: 4px;"
                applyBtn.setOnAction { applyFilter() }

                val resetBtn = Button("Сбросить")
                resetBtn.style = "-fx-background-color: #6c757d; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 4px 16px; -fx-background-radius: 4px;"
                resetBtn.setOnAction {
                    typeFilter.value = "Все типы"
                    searchField.clear()
                    cellSearchField.clear()
                    applyFilter()
                }

                tableView.setOnMouseClicked { event ->
                    if (event.clickCount == 2) {
                        val selected = tableView.selectionModel.selectedItem
                        if (selected != null) {
                            showEquipmentOnMap(selected.id)
                            (tableView.scene.window as Stage).close()
                        }
                    }
                }

                tableView.style = """
                -fx-font-size: 13px;
                -fx-border-color: #dee2e6;
                -fx-selection-bar: #d4edda;
                -fx-selection-bar-text: black;
            """.trimIndent()

                tableView.setRowFactory {
                    val row = TableRow<EquipmentTableItem>()

                    row.hoverProperty().addListener { _, _, hovered ->
                        if (hovered && !row.isSelected) {
                            row.style = "-fx-background-color: #e8f4f8; -fx-text-fill: black;"
                        } else if (!row.isSelected) {
                            row.style = "-fx-background-color: transparent; -fx-text-fill: black;"
                        }
                    }

                    row.selectedProperty().addListener { _, _, selected ->
                        if (selected) {
                            row.style = "-fx-background-color: #d4edda; -fx-text-fill: black; -fx-font-weight: bold;"
                        } else {
                            row.style = "-fx-background-color: transparent; -fx-text-fill: black;"
                        }
                    }

                    row
                }

                val contextMenu = ContextMenu()
                val editMenuItem = MenuItem("✏️ Редактировать")
                val showMenuItem = MenuItem("📍 Показать на карте")

                editMenuItem.setOnAction {
                    val selected = tableView.selectionModel.selectedItem
                    if (selected != null) {
                        editEquipmentFromList(selected.id)
                    }
                }

                showMenuItem.setOnAction {
                    val selected = tableView.selectionModel.selectedItem
                    if (selected != null) {
                        showEquipmentOnMap(selected.id)
                        (tableView.scene.window as Stage).close()
                    }
                }

                contextMenu.items.addAll(editMenuItem, showMenuItem)

                tableView.setOnMouseClicked { event ->
                    if (event.isSecondaryButtonDown) {
                        val row = tableView.lookup(".table-row-cell") as? TableRow<*>?
                        if (row != null && row.item != null) {
                            val index = row.index
                            tableView.selectionModel.select(index)
                            tableView.scrollTo(index)
                        }
                    }
                }

                tableView.setOnContextMenuRequested { event ->
                    if (tableView.selectionModel.selectedItem == null && tableView.items.isNotEmpty()) {
                        tableView.selectionModel.select(0)
                        tableView.scrollTo(0)
                    }
                    contextMenu.show(tableView, event.screenX, event.screenY)
                }

                applyFilter()

                val buttonPanel = HBox(10.0)
                buttonPanel.alignment = Pos.CENTER_RIGHT
                buttonPanel.style = "-fx-padding: 10px 0 0 0;"

                val closeBtn = Button("✕ Закрыть")
                closeBtn.style = "-fx-background-color: #dc3545; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6px 20px; -fx-background-radius: 4px;"
                closeBtn.setOnAction { (closeBtn.scene.window as Stage).close() }

                filterPanel.children.addAll(
                    filterLabel, typeFilter, applyBtn, resetBtn, countLabel,
                    searchField, cellSearchField
                )

                buttonPanel.children.addAll(closeBtn)
                mainLayout.children.addAll(headerLabel, filterPanel, tableView, buttonPanel)

                val popupStage = Stage()
                popupStage.title = "📋 Список оборудования"
                popupStage.scene = Scene(mainLayout, 880.0, 650.0)
                popupStage.isResizable = true
                popupStage.minWidth = 700.0
                popupStage.minHeight = 500.0

                equipmentListStage = popupStage
                popupStage.setOnHidden {
                    equipmentListStage = null
                }

                popupStage.showAndWait()

            } catch (e: Exception) {
                showError("Ошибка при чтении оборудования: ${e.message}")
            }
        } else {
            showInfo("📋 Нет сохраненного оборудования")
        }
    }

    private fun editEquipmentFromList(equipmentId: String) {
        println("✏️ Редактирование из списка: $equipmentId")
        editEquipment(equipmentId)
    }

    private fun showEquipmentOnMap(equipmentId: String) {
        webView.engine.executeScript("""
            (function() {
                var id = '$equipmentId';
                var marker = document.getElementById(id);
                if (!marker) {
                    var oldId = id.replace('equipment-', 'marker-');
                    marker = document.getElementById(oldId);
                }
                if (marker) {
                    var originalTransform = marker.style.transform;
                    marker.style.transform = 'translate(-50%, -50%) scale(2)';
                    marker.style.boxShadow = '0 0 30px rgba(255,255,0,0.8)';
                    marker.style.border = '3px solid yellow';
                    setTimeout(function() {
                        marker.style.transform = originalTransform;
                        marker.style.boxShadow = '';
                        marker.style.border = '';
                    }, 3000);
                    marker.scrollIntoView({ behavior: 'smooth', block: 'center' });
                }
            })();
        """.trimIndent())
    }

    private fun addMarkerToEquipment(equipmentId: String) {
        println("➕ Добавление маркера для: $equipmentId")

        if (currentEditingEquipmentId != null) {
            println("⚠️ Уже в режиме добавления маркера для: $currentEditingEquipmentId")
            showToast("⚠️ Сначала завершите добавление текущего маркера")
            return
        }

        val allEquipment = loadEquipment()
        val equipment = allEquipment.find { it.id == equipmentId }

        if (equipment == null) {
            showError("Оборудование не найдено")
            return
        }

        currentEditingEquipmentId = equipmentId
        isEditMode = true
        toggleEditMode(true)
        editModeMenuItem.text = "🔒 Закончить добавление маркера"

        showToast("Кликните на схеме, чтобы добавить маркер для '${equipment.name}'")
    }

    // ======================== СОХРАНЕНИЕ / ЗАГРУЗКА ========================

    private var lastSavedHash = 0

    private fun saveEquipment() {
        val result = webView.engine.executeScript("""
        JSON.stringify(window.equipment || [])
    """.trimIndent()) as? String

        if (result != null) {
            try {
                val type = object : TypeToken<List<EquipmentData>>() {}.type
                val equipment: List<EquipmentData> = gson.fromJson(result, type)

                if (equipment.isEmpty()) {
                    println("⚠️ Нет данных для сохранения")
                    return
                }

                val currentHash = equipment.hashCode()
                if (currentHash == lastSavedHash && isInitialized) {
                    println("ℹ️ Данные не изменились, пропускаем сохранение")
                    return
                }

                println("💾 Сохраняем ${equipment.size} записей")
                database.saveEquipment(equipment)
                lastSavedHash = currentHash
                syncWindowEquipment()
                showToast("✅ Данные сохранены (${equipment.size} записей)")

            } catch (e: Exception) {
                showError("Ошибка сохранения: ${e.message}")
                e.printStackTrace()
            }
        } else {
            println("❌ Ошибка: результат скрипта null")
        }
    }

    private fun syncWindowEquipment() {
        val freshData = loadEquipment()
        val freshJson = gson.toJson(freshData)

        webView.engine.executeScript("""
        (function() {
            var container = document.getElementById('equipment-container');
            if (!container) {
                var wrapper = document.getElementById('image-wrapper');
                if (wrapper) {
                    container = document.createElement('div');
                    container.id = 'equipment-container';
                    wrapper.appendChild(container);
                }
            }
            if (container) {
                container.innerHTML = '';
            } else {
                return;
            }
            
            var savedData = $freshJson;
            window.equipment = savedData;
            
            savedData.forEach(function(item) {
                var markers = item.markers;
                if (!markers || markers.length === 0) {
                    markers = [{left: item.left, top: item.top, isMain: true}];
                }
                
                markers.forEach(function(markerPos, index) {
                    var marker = document.createElement('div');
                    var sizeClass = item.size || 'normal';
                    marker.className = 'equipment-marker ' + item.type + ' ' + sizeClass;
                    if (index > 0) marker.className += ' marker-extra';
                    if (!${markersVisible}) {
                        marker.className += ' hidden';
                    }
                    marker.id = item.id + '-marker-' + index;
                    marker.style.left = markerPos.left + '%';
                    marker.style.top = markerPos.top + '%';
                    marker.dataset.equipmentId = item.id;
                    marker.dataset.markerIndex = index;
                    
                    if (index > 0) {
                        marker.style.border = '2px dashed rgba(255,255,255,0.5)';
                    }
                    
                    marker.innerHTML = '<div class="dot">' + item.letter + '</div><span class="tooltip-text">' + item.name + '</span>';
                    container.appendChild(marker);
                });
            });
            
            console.log('🔄 Синхронизация: пересоздано ' + savedData.length + ' маркеров');
        })();
    """.trimIndent())
    }

    // ======================== ВСПОМОГАТЕЛЬНЫЕ ========================

    private fun showInfo(message: String) {
        Platform.runLater {
            Alert(AlertType.INFORMATION).apply {
                title = "Информация"
                headerText = null
                contentText = message
                showAndWait()
            }
        }
    }

    private fun showError(message: String) {
        Platform.runLater {
            Alert(AlertType.ERROR).apply {
                title = "Ошибка"
                headerText = null
                contentText = message
                showAndWait()
            }
        }
    }

    // ======================== УПРАВЛЕНИЕ ТИПАМИ ========================

    private fun showManageTypesDialog() {
        val dialog = Stage()
        dialog.title = "📋 Управление типами оборудования"
        dialog.initModality(javafx.stage.Modality.WINDOW_MODAL)
        dialog.initOwner(webView.scene.window)
        dialog.minWidth = 680.0
        dialog.minHeight = 480.0

        val root = VBox(10.0)
        root.style = "-fx-background-color: white; -fx-padding: 20px;"

        val headerLabel = Label("📋 Типы оборудования (${EquipmentTypes.ALL_TYPES.size})")
        headerLabel.style = "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #333;"

        val tableView = TableView<EquipmentTypeData>()
        tableView.style = "-fx-font-size: 13px;"

        val colKey = TableColumn<EquipmentTypeData, String>("Ключ")
        colKey.cellValueFactory = PropertyValueFactory("key")
        colKey.prefWidth = 120.0

        val colDisplay = TableColumn<EquipmentTypeData, String>("Название")
        colDisplay.cellValueFactory = PropertyValueFactory("displayName")
        colDisplay.prefWidth = 220.0

        val colLetter = TableColumn<EquipmentTypeData, String>("Буква")
        colLetter.cellValueFactory = PropertyValueFactory("letter")
        colLetter.prefWidth = 100.0
        colLetter.style = "-fx-alignment: CENTER;"

        tableView.columns.addAll(colKey, colDisplay, colLetter)

        val allTypes = database.loadAllTypes()
        val typesList = allTypes.toMutableList()

        if (typesList.isEmpty()) {
            database.restoreDefaultTypes()
            typesList.addAll(database.loadAllTypes())
            EquipmentTypes.reloadDefaults()
        }

        val observableTypes = FXCollections.observableArrayList<EquipmentTypeData>(typesList)
        tableView.items = observableTypes

        val buttonPanel = HBox(10.0)
        buttonPanel.alignment = Pos.CENTER_RIGHT

        val addBtn = Button("➕ Добавить")
        addBtn.style = "-fx-background-color: #28a745; -fx-text-fill: white; -fx-padding: 6px 16px; -fx-background-radius: 4px;"
        addBtn.setOnAction {
            showAddTypeDialog { newType ->
                database.saveType(newType)
                EquipmentTypes.addType(newType.key, newType.displayName, newType.letter)
                observableTypes.add(newType)
                headerLabel.text = "📋 Типы оборудования (${EquipmentTypes.ALL_TYPES.size})"
                showToast("✅ Тип добавлен: ${newType.displayName}")
            }
        }

        val editBtn = Button("✏️ Редактировать")
        editBtn.style = "-fx-background-color: #007bff; -fx-text-fill: white; -fx-padding: 6px 16px; -fx-background-radius: 4px;"
        editBtn.setOnAction {
            val selected = tableView.selectionModel.selectedItem
            if (selected != null) {
                showEditTypeDialog(selected) { updatedType ->
                    database.saveType(updatedType)
                    EquipmentTypes.updateType(updatedType.key, updatedType.displayName, updatedType.letter)
                    val index = observableTypes.indexOfFirst { it.key == updatedType.key }
                    if (index >= 0) {
                        observableTypes[index] = updatedType
                    }
                    showToast("✅ Тип обновлён: ${updatedType.displayName}")
                }
            } else {
                showInfo("⚠️ Выберите тип для редактирования")
            }
        }

        val deleteBtn = Button("🗑️ Удалить")
        deleteBtn.style = "-fx-background-color: #dc3545; -fx-text-fill: white; -fx-padding: 6px 16px; -fx-background-radius: 4px;"
        deleteBtn.setOnAction {
            val selected = tableView.selectionModel.selectedItem
            if (selected != null) {
                val confirm = Alert(AlertType.CONFIRMATION)
                confirm.title = "Удаление типа"
                confirm.headerText = "Удалить тип '${selected.displayName}'?"
                confirm.contentText = "Оборудование с этим типом не удалится, но будет показываться как 'Другое'."
                val result = confirm.showAndWait()
                if (result.isPresent && result.get() == ButtonType.OK) {
                    database.deleteType(selected.key)
                    EquipmentTypes.removeType(selected.key)
                    observableTypes.remove(selected)
                    headerLabel.text = "📋 Типы оборудования (${EquipmentTypes.ALL_TYPES.size})"
                    showToast("🗑️ Тип удалён: ${selected.displayName}")
                }
            } else {
                showInfo("⚠️ Выберите тип для удаления")
            }
        }

        val restoreBtn = Button("🔄 Восстановить дефолтные")
        restoreBtn.style = "-fx-background-color: #ffc107; -fx-text-fill: #333; -fx-padding: 6px 16px; -fx-background-radius: 4px;"
        restoreBtn.setOnAction {
            val confirm = Alert(AlertType.CONFIRMATION)
            confirm.title = "Восстановление типов"
            confirm.headerText = "Восстановить дефолтные типы?"
            confirm.contentText = "Все пользовательские типы будут удалены. Продолжить?"
            val result = confirm.showAndWait()
            if (result.isPresent && result.get() == ButtonType.OK) {
                database.restoreDefaultTypes()
                EquipmentTypes.reloadDefaults()
                observableTypes.clear()
                observableTypes.addAll(database.loadAllTypes())
                headerLabel.text = "📋 Типы оборудования (${EquipmentTypes.ALL_TYPES.size})"
                showToast("✅ Дефолтные типы восстановлены")
            }
        }

        val closeBtn = Button("✕ Закрыть")
        closeBtn.style = "-fx-background-color: #6c757d; -fx-text-fill: white; -fx-padding: 6px 20px; -fx-background-radius: 4px;"
        closeBtn.setOnAction { dialog.close() }

        buttonPanel.children.addAll(addBtn, editBtn, deleteBtn, restoreBtn, closeBtn)

        val infoLabel = Label("💡 Типы загружаются из БД. При добавлении нового типа он сразу становится доступен в списке оборудования.")
        infoLabel.style = "-fx-text-fill: #6c757d; -fx-font-size: 12px; -fx-wrap-text: true;"

        root.children.addAll(headerLabel, tableView, buttonPanel, infoLabel)

        val scene = Scene(root, 680.0, 480.0)
        dialog.scene = scene
        dialog.showAndWait()
    }

    private fun showAddTypeDialog(onSave: (EquipmentTypeData) -> Unit) {
        val dialog = Dialog<ButtonType>()
        dialog.title = "Добавление типа"
        dialog.headerText = "Введите данные нового типа"

        val content = VBox(10.0)
        content.style = "-fx-padding: 20px; -fx-pref-width: 380px;"

        val keyField = TextField()
        keyField.promptText = "Ключ (например: v_110)"
        keyField.style = "-fx-padding: 8px;"

        val nameField = TextField()
        nameField.promptText = "Отображаемое имя (например: В-110)"
        nameField.style = "-fx-padding: 8px;"

        val letterField = TextField()
        letterField.promptText = "Буква на маркере (например: В-110)"
        letterField.style = "-fx-padding: 8px;"

        content.children.addAll(
            Label("Ключ (уникальный идентификатор):"), keyField,
            Label("Название:"), nameField,
            Label("Буква на маркере:"), letterField
        )

        dialog.dialogPane.content = content
        dialog.dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        dialog.showAndWait().ifPresent { result ->
            if (result == ButtonType.OK) {
                val key = keyField.text.trim().lowercase().replace(" ", "_")
                val displayName = nameField.text.trim()
                val letter = letterField.text.trim().ifEmpty { displayName.take(1) }

                if (key.isEmpty() || displayName.isEmpty()) {
                    showError("Заполните все поля")
                    return@ifPresent
                }

                val types = database.loadAllTypes()
                if (types.any { it.key == key }) {
                    showError("Тип с ключом '$key' уже существует")
                    return@ifPresent
                }

                onSave(EquipmentTypeData(key, displayName, letter, types.size))
            }
        }
    }

    private fun showEditTypeDialog(type: EquipmentTypeData, onSave: (EquipmentTypeData) -> Unit) {
        val dialog = Dialog<ButtonType>()
        dialog.title = "Редактирование типа"
        dialog.headerText = "Измените данные типа"

        val content = VBox(10.0)
        content.style = "-fx-padding: 20px; -fx-pref-width: 380px;"

        val nameField = TextField(type.displayName)
        nameField.style = "-fx-padding: 8px;"

        val letterField = TextField(type.letter)
        letterField.style = "-fx-padding: 8px;"

        val keyLabel = Label(type.key)
        keyLabel.style = "-fx-font-weight: bold; -fx-text-fill: #333;"

        content.children.addAll(
            Label("Ключ (нельзя изменить):"),
            keyLabel,
            Label("Название:"),
            nameField,
            Label("Буква на маркере:"),
            letterField
        )

        dialog.dialogPane.content = content
        dialog.dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        dialog.showAndWait().ifPresent { result ->
            if (result == ButtonType.OK) {
                val displayName = nameField.text.trim()
                val letter = letterField.text.trim().ifEmpty { displayName.take(1) }

                if (displayName.isEmpty()) {
                    showError("Введите название")
                    return@ifPresent
                }

                onSave(type.copy(displayName = displayName, letter = letter))
            }
        }
    }

    // ======================== УПРАВЛЕНИЕ ВИДАМИ ДЕФЕКТОВ ========================

    private fun showManageDefectTypesDialog() {
        val dialog = Stage()
        dialog.title = "🔧 Управление видами дефектов"
        dialog.initModality(javafx.stage.Modality.WINDOW_MODAL)
        dialog.initOwner(webView.scene.window)
        dialog.minWidth = 600.0
        dialog.minHeight = 500.0

        val root = VBox(10.0)
        root.style = "-fx-background-color: white; -fx-padding: 20px;"

        val headerLabel = Label("🔧 Виды дефектов (${DefectTypes.ALL_TYPES.size})")
        headerLabel.style = "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #333;"

        // ===== ПОИСК =====
        val searchField = TextField()
        searchField.promptText = "🔍 Поиск по названию..."
        searchField.style = "-fx-padding: 8px; -fx-font-size: 13px;"

        // ===== ТАБЛИЦА =====
        // ===== ТАБЛИЦА =====
        val tableView = TableView<String>()
        tableView.style = "-fx-font-size: 13px;"

        val colName = TableColumn<String, String>("Вид дефекта")
        colName.setCellValueFactory { data ->
            javafx.beans.property.SimpleStringProperty(data.value)
        }
        colName.prefWidth = 400.0

        tableView.columns.add(colName)

        val allTypes = database.loadAllDefectTypes().toMutableList()
        if (allTypes.isEmpty()) {
            database.restoreDefaultDefectTypes()
            allTypes.addAll(database.loadAllDefectTypes())
            DefectTypes.reloadDefaults()
        }
        val observableTypes = FXCollections.observableArrayList(allTypes)
        tableView.items = observableTypes

        // ===== ФИЛЬТРАЦИЯ =====
        searchField.textProperty().addListener { _, _, newValue ->
            val search = newValue.lowercase()
            val filtered = allTypes.filter { it.lowercase().contains(search) }
            tableView.items = FXCollections.observableArrayList(filtered)
        }

        // ===== КНОПКИ =====
        val buttonPanel = HBox(10.0)
        buttonPanel.alignment = Pos.CENTER_RIGHT

        val addBtn = Button("➕ Добавить")
        addBtn.style = "-fx-background-color: #28a745; -fx-text-fill: white; -fx-padding: 6px 16px; -fx-background-radius: 4px;"
        addBtn.setOnAction {
            showAddDefectTypeDialog { newName ->
                database.saveDefectType(newName, allTypes.size)
                DefectTypes.addType(newName)
                allTypes.add(newName)
                observableTypes.add(newName)
                headerLabel.text = "🔧 Виды дефектов (${DefectTypes.ALL_TYPES.size})"
                showToast("✅ Вид дефекта добавлен: $newName")
            }
        }

        val editBtn = Button("✏️ Редактировать")
        editBtn.style = "-fx-background-color: #007bff; -fx-text-fill: white; -fx-padding: 6px 16px; -fx-background-radius: 4px;"
        editBtn.setOnAction {
            val selected = tableView.selectionModel.selectedItem
            if (selected != null) {
                showEditDefectTypeDialog(selected) { oldName, newName ->
                    database.deleteDefectType(oldName)
                    database.saveDefectType(newName, allTypes.indexOf(oldName))
                    DefectTypes.removeType(oldName)
                    DefectTypes.addType(newName)

                    val index = allTypes.indexOf(oldName)
                    if (index >= 0) {
                        allTypes[index] = newName
                        observableTypes[index] = newName
                    }
                    showToast("✅ Вид дефекта обновлён: $newName")
                }
            } else {
                showInfo("⚠️ Выберите вид дефекта для редактирования")
            }
        }

        val deleteBtn = Button("🗑️ Удалить")
        deleteBtn.style = "-fx-background-color: #dc3545; -fx-text-fill: white; -fx-padding: 6px 16px; -fx-background-radius: 4px;"
        deleteBtn.setOnAction {
            val selected = tableView.selectionModel.selectedItem
            if (selected != null) {
                val confirm = Alert(AlertType.CONFIRMATION)
                confirm.title = "Удаление вида дефекта"
                confirm.headerText = "Удалить '$selected'?"
                confirm.contentText = "Существующие дефекты с этим видом не удалятся, но в списке его больше не будет."
                val result = confirm.showAndWait()
                if (result.isPresent && result.get() == ButtonType.OK) {
                    database.deleteDefectType(selected)
                    DefectTypes.removeType(selected)
                    allTypes.remove(selected)
                    observableTypes.remove(selected)
                    headerLabel.text = "🔧 Виды дефектов (${DefectTypes.ALL_TYPES.size})"
                    showToast("🗑️ Вид дефекта удалён: $selected")
                }
            } else {
                showInfo("⚠️ Выберите вид дефекта для удаления")
            }
        }

        val restoreBtn = Button("🔄 Восстановить дефолтные")
        restoreBtn.style = "-fx-background-color: #ffc107; -fx-text-fill: #333; -fx-padding: 6px 16px; -fx-background-radius: 4px;"
        restoreBtn.setOnAction {
            val confirm = Alert(AlertType.CONFIRMATION)
            confirm.title = "Восстановление видов дефектов"
            confirm.headerText = "Восстановить дефолтные виды?"
            confirm.contentText = "Все пользовательские виды дефектов будут удалены. Продолжить?"
            val result = confirm.showAndWait()
            if (result.isPresent && result.get() == ButtonType.OK) {
                database.restoreDefaultDefectTypes()
                DefectTypes.reloadDefaults()
                allTypes.clear()
                allTypes.addAll(DefectTypes.ALL_TYPES)
                observableTypes.clear()
                observableTypes.addAll(allTypes)
                headerLabel.text = "🔧 Виды дефектов (${DefectTypes.ALL_TYPES.size})"
                showToast("✅ Дефолтные виды восстановлены")
            }
        }

        val closeBtn = Button("✕ Закрыть")
        closeBtn.style = "-fx-background-color: #6c757d; -fx-text-fill: white; -fx-padding: 6px 20px; -fx-background-radius: 4px;"
        closeBtn.setOnAction { dialog.close() }

        buttonPanel.children.addAll(addBtn, editBtn, deleteBtn, restoreBtn, closeBtn)

        val infoLabel = Label("💡 Виды дефектов используются при добавлении/редактировании дефектов.")
        infoLabel.style = "-fx-text-fill: #6c757d; -fx-font-size: 12px; -fx-wrap-text: true;"

        root.children.addAll(headerLabel, searchField, tableView, buttonPanel, infoLabel)

        val scene = Scene(root, 650.0, 520.0)
        dialog.scene = scene
        dialog.showAndWait()
    }

// ===== ДОБАВЛЕНИЕ ВИДА ДЕФЕКТА =====

    private fun showAddDefectTypeDialog(onSave: (String) -> Unit) {
        val dialog = Dialog<ButtonType>()
        dialog.title = "Добавление вида дефекта"
        dialog.headerText = "Введите название нового вида дефекта"

        val content = VBox(10.0)
        content.style = "-fx-padding: 20px; -fx-pref-width: 400px;"

        val nameField = TextField()
        nameField.promptText = "Например: Скол изолятора"
        nameField.style = "-fx-padding: 8px; -fx-font-size: 14px;"

        content.children.addAll(
            Label("Название вида дефекта:"), nameField
        )

        dialog.dialogPane.content = content
        dialog.dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        dialog.showAndWait().ifPresent { result ->
            if (result == ButtonType.OK) {
                val name = nameField.text.trim()
                if (name.isEmpty()) {
                    showError("Введите название")
                    return@ifPresent
                }

                if (DefectTypes.ALL_TYPES.contains(name)) {
                    showError("Такой вид дефекта уже существует")
                    return@ifPresent
                }

                onSave(name)
            }
        }
    }

// ===== РЕДАКТИРОВАНИЕ ВИДА ДЕФЕКТА =====

    private fun showEditDefectTypeDialog(currentName: String, onSave: (String, String) -> Unit) {
        val dialog = Dialog<ButtonType>()
        dialog.title = "Редактирование вида дефекта"
        dialog.headerText = "Измените название вида дефекта"

        val content = VBox(10.0)
        content.style = "-fx-padding: 20px; -fx-pref-width: 400px;"

        val nameField = TextField(currentName)
        nameField.style = "-fx-padding: 8px; -fx-font-size: 14px;"

        content.children.addAll(
            Label("Название вида дефекта:"), nameField
        )

        dialog.dialogPane.content = content
        dialog.dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        dialog.showAndWait().ifPresent { result ->
            if (result == ButtonType.OK) {
                val newName = nameField.text.trim()
                if (newName.isEmpty()) {
                    showError("Введите название")
                    return@ifPresent
                }

                if (newName != currentName && DefectTypes.ALL_TYPES.contains(newName)) {
                    showError("Такой вид дефекта уже существует")
                    return@ifPresent
                }

                onSave(currentName, newName)
            }
        }
    }

    private fun buildImageMap(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val typeToFile = mapOf(
            "v_500" to "ВВБК-500.jfif",
            "v_220" to "ВВБК-220(3 фазы).jpg",
            "v_35" to "ВВБК-500.jfif",
            "v_10" to "ВВБК-500.jfif",
            "r_500" to "РНДЗ-220,500.jpg",
            "r_220" to "РНДЗ-220,500.jpg",
            "r_35" to "РНДЗ-220,500.jpg",
            "r_10" to "РНДЗ-220,500.jpg",
            "autotransformer" to "АОДЦТН-167000-500-220.jpg",
            "transformer" to "ТМ-35.jpg",
            "tsn" to "ТМ-35.jpg",
            "lightning" to "lightning_rod.jpg",
            "lightning_rod" to "lightning_rod.jpg",
            "opn_500" to "opn-500.jpg",
            "opn_220" to "ОПН-220.jpg",
            "opn_35" to "opn-35.jpg",
            "opn_10" to "opn-10.jpg",
            "tn_500" to "tn.jpg",
            "tn_220" to "tn.jpg",
            "tn_35" to "tn.jpg",
            "tn_10" to "tn.jpg",
            "tt_500" to "ТФЗМ-500.jpg",
            "tt_220" to "ТФЗМ-500.jpg",
            "tt_35" to "ТФЗМ-500.jpg",
            "tt_10" to "ТФЗМ-500.jpg",
            "ks_500" to "capacitor.jpg",
            "ks_220" to "capacitor.jpg",
            "coupling_capacitor" to "capacitor.jpg",
            "reactor_500" to "reactor.jpg",
            "reactor_220" to "reactor.jpg",
            "capacitor" to "capacitor.jpg",
            "compressor" to "compressor.jpg"
        )

        // Пробуем ресурсы
        typeToFile.forEach { (type, fileName) ->
            val url = javaClass.getResource("/org/example/defectmap/$fileName")
            if (url != null) {
                result[type] = url.toExternalForm()
            } else {
                // Пробуем файловую систему
                val file = File("images/$fileName")
                if (file.exists()) {
                    result[type] = file.toURI().toURL().toExternalForm()
                }
            }
        }

        // Fallback
        val fallbackUrl = javaClass.getResource("/org/example/defectmap/equipment.jpg")
        if (fallbackUrl != null) {
            result["_fallback"] = fallbackUrl.toExternalForm()
        }

        return result
    }

    /**
     * Возвращает корневое оборудование (самого верхнего предка).
     * Если parentId == null — возвращает само оборудование.
     */
    private fun findRootEquipment(
        eq: EquipmentData,
        allById: Map<String, EquipmentData>
    ): EquipmentData {
        var current = eq
        var guard = 0
        while (current.parentId != null && guard < 50) {
            val parent = allById[current.parentId] ?: break
            current = parent
            guard++
        }
        return current
    }

}

data class DefectViewItem(
    val equipmentName: String,
    val cell: String,
    val defectName: String,
    val description: String,
    val status: String,
    val equipmentId: String,
    val defectId: String
)