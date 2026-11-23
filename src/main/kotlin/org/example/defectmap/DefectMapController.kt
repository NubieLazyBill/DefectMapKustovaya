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
import javafx.scene.input.KeyEvent
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyCodeCombination
import javafx.scene.control.TextInputDialog
import javafx.scene.control.ChoiceDialog
import javafx.scene.layout.GridPane
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
import javafx.stage.FileChooser
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.time.Instant
import org.example.defectmap.platform.UiFonts
import org.example.defectmap.report.ExcelReportWriter
import org.example.defectmap.report.ReportItem
import org.example.defectmap.report.ReportPrinter

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

    private var currentSubstation: Substation = loadLastSubstation()
    private var database: Database = Database(currentSubstation.key)

    private var markerScale: Double = 1.0

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
        reportStage.minWidth = 900.0
        reportStage.minHeight = 700.0

        val ownerStage = webView.scene.window as Stage
        reportStage.initOwner(ownerStage)
        reportStage.initModality(javafx.stage.Modality.NONE)

        val root = VBox(12.0)
        root.style = "-fx-background-color: white; -fx-padding: 20px;"

        val headerLabel = Label("📊 Настройка отчёта")
        headerLabel.style = "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #333;"

        // ======================== ФИЛЬТРЫ (СЕТКА 2×N) ========================
        val filtersGrid = javafx.scene.layout.GridPane()
        filtersGrid.hgap = 20.0
        filtersGrid.vgap = 10.0
        filtersGrid.style = "-fx-padding: 10px 0;"

        // ===== Строка 1: Статус | Тип оборудования =====
        val statusLabel = Label("Статус дефектов:")
        statusLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val statusCombo = ComboBox<String>()
        statusCombo.items.addAll("Все", "Обнаружен", "Устранён")
        statusCombo.value = "Все"
        statusCombo.style = "-fx-pref-width: 220px;"

        val typeLabel = Label("Тип оборудования:")
        typeLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val typeCombo = ComboBox<String>()
        val allTypes = listOf("Все") + EquipmentTypes.ALL_TYPES.map { it.second }
        typeCombo.items.addAll(allTypes)
        typeCombo.value = "Все"
        typeCombo.style = "-fx-pref-width: 220px;"

        // ===== Строка 2: Ячейка | Вид дефекта =====
        val cellLabel = Label("Ячейка:")
        cellLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val cellCombo = ComboBox<String>()

        val allCells = loadEquipment()
            .mapNotNull { it.cell.takeIf { cell -> cell.isNotEmpty() } }
            .distinct()
            .sorted()

        cellCombo.items.add("Все ячейки")
        cellCombo.items.addAll(allCells)
        cellCombo.value = "Все ячейки"
        cellCombo.style = "-fx-pref-width: 220px;"

        val defectTypeLabel = Label("Вид дефекта:")
        defectTypeLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val defectTypeCombo = ComboBox<String>()
        val defectTypesList = listOf("Все") + DefectTypes.ALL_TYPES
        defectTypeCombo.items.addAll(defectTypesList)
        defectTypeCombo.value = "Все"
        defectTypeCombo.style = "-fx-pref-width: 220px;"

        // ===== Строка 3: Период создания =====
        val dateLabel = Label("Период создания:")
        dateLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val dateFrom = DatePicker()
        dateFrom.promptText = "с"
        dateFrom.style = "-fx-pref-width: 105px;"
        val dateTo = DatePicker()
        dateTo.promptText = "по"
        dateTo.style = "-fx-pref-width: 105px;"
        val dateBox = HBox(8.0, dateFrom, dateTo)
        dateBox.alignment = Pos.CENTER_LEFT

        filtersGrid.add(statusLabel, 0, 0)
        filtersGrid.add(statusCombo, 0, 1)
        filtersGrid.add(typeLabel, 1, 0)
        filtersGrid.add(typeCombo, 1, 1)

        filtersGrid.add(cellLabel, 0, 2)
        filtersGrid.add(cellCombo, 0, 3)
        filtersGrid.add(defectTypeLabel, 1, 2)
        filtersGrid.add(defectTypeCombo, 1, 3)

        filtersGrid.add(dateLabel, 0, 4)
        filtersGrid.add(dateBox, 0, 5)

        // ======================== СЧЁТЧИК + ПРЕВЬЮ ========================
        val countLabel = Label("Найдено: ${reportData.size} дефектов")
        countLabel.style = "-fx-font-size: 15px; -fx-text-fill: #28a745; -fx-font-weight: bold; -fx-padding: 5px 0;"

        val previewLabel = Label("Предпросмотр:")
        previewLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"

        val previewText = TextArea()
        previewText.isEditable = false
        previewText.isWrapText = false
        previewText.style = "-fx-font-size: 12px; -fx-font-family: 'Consolas', 'Monospaced', monospace;"
        // Растянем на всё доступное место
        VBox.setVgrow(previewText, javafx.scene.layout.Priority.ALWAYS)
        previewText.minHeight = 200.0    // ← минимальная высота

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
                val preview = filtered.joinToString("\n") { item ->
                    val dateStr = item.detectionDate?.let {
                        Instant.ofEpochMilli(it)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDate()
                            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                    } ?: "—"
                    val parentStr = item.parentEquipmentName?.let { " (в составе: $it)" } ?: ""
                    "${item.equipmentName}$parentStr | ${item.defectName} | ${item.defectStatus} | $dateStr"
                }
                previewText.text = preview
            } else {
                previewText.text = "Нет дефектов по выбранным фильтрам"
            }
        }

        statusCombo.valueProperty().addListener { _, _, _ -> updatePreview() }
        typeCombo.valueProperty().addListener { _, _, _ -> updatePreview() }
        cellCombo.valueProperty().addListener { _, _, _ -> updatePreview() }
        defectTypeCombo.valueProperty().addListener { _, _, _ -> updatePreview() }
        dateFrom.valueProperty().addListener { _, _, _ -> updatePreview() }
        dateTo.valueProperty().addListener { _, _, _ -> updatePreview() }

        // ======================== КНОПКИ ========================
        val buttonBox = HBox(10.0)
        buttonBox.alignment = Pos.CENTER_RIGHT
        buttonBox.style = "-fx-padding: 10px 0 0 0;"

        val createBtn = Button("📊 Создать отчёт")
        createBtn.style = "-fx-background-color: #28a745; -fx-text-fill: white; -fx-padding: 8px 20px; -fx-background-radius: 4px; -fx-font-weight: bold; -fx-font-size: 13px;"
        createBtn.setOnAction {
            val status = statusCombo.value
            val typeName = typeCombo.value
            val cell = cellCombo.value ?: "Все ячейки"
            val defectType = defectTypeCombo.value
            val from = dateFrom.value
            val to = dateTo.value

            val filtered = filterReportData(reportData, status, typeName, cell, defectType, from, to)
            if (filtered.isEmpty()) {
                showInfo("⚠️ Нет дефектов по выбранным фильтрам")
                return@setOnAction
            }
            reportStage.close()
            saveReportToExcel(filtered)
        }

        val printBtn = Button("🖨️ Печать")
        printBtn.style = "-fx-background-color: #007bff; -fx-text-fill: white; -fx-padding: 8px 20px; -fx-background-radius: 4px; -fx-font-weight: bold; -fx-font-size: 13px;"
        printBtn.setOnAction {
            val filtered = filterReportData(
                reportData,
                statusCombo.value,
                typeCombo.value,
                cellCombo.value ?: "Все ячейки",
                defectTypeCombo.value,
                dateFrom.value,
                dateTo.value
            )
            if (filtered.isEmpty()) {
                showInfo("⚠️ Нет дефектов по выбранным фильтрам")
                return@setOnAction
            }
            // Печать работает и без сети, и без Excel: нужен только принтер в системе
            printReport(filtered, reportStage)
        }

        val cancelBtn = Button("✕ Закрыть")
        cancelBtn.style = "-fx-background-color: #6c757d; -fx-text-fill: white; -fx-padding: 8px 20px; -fx-background-radius: 4px; -fx-font-size: 13px;"
        cancelBtn.setOnAction { reportStage.close() }

        buttonBox.children.addAll(createBtn, printBtn, cancelBtn)

        // ======================== СБОРКА ========================
        root.children.addAll(
            headerLabel,
            filtersGrid,
            countLabel,
            previewLabel,
            previewText,
            buttonBox
        )

        val scene = Scene(root, 900.0, 750.0)
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

    // ======================== ЭКСПОРТ И ПЕЧАТЬ ОТЧЁТА ========================

    /** Последняя папка сохранения отчёта (на Linux GTK-диалог не запоминает её сам). */
    private var lastExportDirPath: String? = null

    private fun saveReportToExcel(data: List<ReportItem>) {
        try {
            val fileChooser = javafx.stage.FileChooser()
            fileChooser.title = "Сохранить отчёт"
            fileChooser.initialFileName = "report_defects_${LocalDate.now()}.xlsx"
            fileChooser.initialDirectory = lastExportDir()
            fileChooser.extensionFilters.add(
                javafx.stage.FileChooser.ExtensionFilter("Excel files (*.xlsx)", "*.xlsx")
            )

            val owner = webView.scene?.window
            val chosen = fileChooser.showSaveDialog(owner)
            if (chosen == null) {
                println("❌ Сохранение отменено")
                return
            }

            // GTK-диалог в Linux не дописывает расширение — делаем это сами
            val target = ensureXlsxExtension(chosen)
            lastExportDirPath = target.parentFile?.absolutePath

            ExcelReportWriter.write(data, target)

            val total = data.size
            val fixed = data.count { it.defectStatus == "Устранён" }
            val open = total - fixed
            showInfo(
                "✅ Отчёт сохранён:\n${target.absolutePath}\n\n" +
                    "Всего дефектов: $total\nОбнаружено: $open\nУстранено: $fixed"
            )
            println("✅ Отчёт сохранён: ${target.absolutePath} ($total записей)")
        } catch (e: Exception) {
            showError("Ошибка сохранения отчёта: ${e.message}")
            e.printStackTrace()
        }
    }

    /** Печать отчёта через JavaFX PrinterJob: CUPS на Linux, winspool на Windows. */
    private fun printReport(data: List<ReportItem>, owner: javafx.stage.Window?) {
        when (val outcome = ReportPrinter.print(data, owner)) {
            is ReportPrinter.PrintOutcome.Printed ->
                showInfo("🖨️ Отправлено на печать: ${outcome.pages} стр.\nПринтер: ${outcome.printer}")

            is ReportPrinter.PrintOutcome.NoPrinters ->
                showError(
                    "В системе не найден ни один принтер.\n\n" +
                        "Linux: установите и настройте CUPS (пакет cups, принтер — http://localhost:631).\n" +
                        "Проверка из консоли: lpstat -p -d"
                )

            is ReportPrinter.PrintOutcome.Cancelled ->
                println("ℹ️ Печать отменена пользователем")

            is ReportPrinter.PrintOutcome.Failed ->
                showError("Не удалось напечатать отчёт:\n${outcome.message}")
        }
    }

    private fun ensureXlsxExtension(file: File): File =
        if (file.name.endsWith(".xlsx", ignoreCase = true)) file
        else File(file.parentFile ?: AppPaths.dataDir, file.name + ".xlsx")

    private fun lastExportDir(): File {
        val saved = lastExportDirPath?.let { File(it) }
        return if (saved != null && saved.isDirectory) saved else AppPaths.documentsDir
    }

    // ======================== ИНИЦИАЛИЗАЦИЯ ========================

    @FXML
    private fun initialize() {
        database.autoBackup()

        // ===== ЗАГРУЖАЕМ ТИПЫ ИЗ БД =====
        EquipmentTypes.loadFromDatabase(database)
        EquipmentModels.loadFromDatabase(database)
        DefectTypes.loadFromDatabase(database)

        // ===== Загружаем масштаб маркеров для текущей ПС =====
        markerScale = database.getMarkerScale()
        println("🔍 Стартовый масштаб маркеров: $markerScale (ПС: ${currentSubstation.displayName})")

        loadSvgIntoWebViewForSubstation(currentSubstation.key)
        updateWindowTitle()

        toggleMarkersMenuItem.text = if (markersVisible) "👁️ Скрыть маркеры" else "👁️ Показать маркеры"

        val openSchemasItem = MenuItem("📂 Открыть папку schemas")
        openSchemasItem.setOnAction {
            AppPaths.schemasDir.mkdirs()
            val opened = AppPaths.openFolder(AppPaths.schemasDir)
            if (!opened) {
                showError("Не удалось открыть папку:\n${AppPaths.schemasDir.absolutePath}")
            }
        }
        devMenuBtn.items.add(openSchemasItem)

        val openImagesItem = MenuItem("📂 Открыть папку images")
        openImagesItem.setOnAction {
            AppPaths.imagesDir.mkdirs()
            val opened = AppPaths.openFolder(AppPaths.imagesDir)
            if (!opened) {
                showError("Не удалось открыть папку:\n${AppPaths.imagesDir.absolutePath}")
            }
        }
        devMenuBtn.items.add(openImagesItem)

        val openDataItem = MenuItem("📂 Открыть папку данных (БД)")
        openDataItem.setOnAction {
            val dir = AppPaths.dataDir
            dir.mkdirs()
            val opened = AppPaths.openFolder(dir)
            if (!opened) {
                showError("Не удалось открыть папку:\n${dir.absolutePath}")
            } else {
                println("📂 Каталог данных: ${dir.absolutePath}")
            }
        }
        devMenuBtn.items.add(openDataItem)

        val showPathsItem = MenuItem("ℹ️ Где лежат данные (в консоль)")
        showPathsItem.setOnAction {
            println("Каталог данных: ${AppPaths.dataDir.absolutePath}")
            println(AppPaths.describe())
        }
        devMenuBtn.items.add(showPathsItem)

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
            when {
                event.isControlDown && event.code == KeyCode.S -> {
                    saveEquipment()
                    showToast("✅ Данные сохранены")
                    event.consume()
                }
                // Запасной путь для Ctrl+0: акселератор может перехватываться WebView
                event.isControlDown &&
                    (event.code == KeyCode.DIGIT0 || event.code == KeyCode.NUMPAD0) -> {
                    resetView()
                    event.consume()
                }
            }
        }

        Platform.runLater {
            val stage = webView.scene?.window as? Stage
            stage?.setOnCloseRequest {
                println("🔄 Приложение закрывается...")
                if (isInitialized) {
                    saveEquipment()
                }
                // ===== ЗАПОМИНАЕМ ПОСЛЕДНЮЮ ПС =====
                AppSettings.setLastSubstationKey(currentSubstation.key)
                database.close()
                println("✅ Завершено")
            }

            // Горячие клавиши масштаба схемы. Работают на уровне сцены,
            // поэтому не зависят от того, перехватывает ли WebView клавиатуру.
            val accelerators = stage?.scene?.accelerators
            if (accelerators != null) {
                accelerators[KeyCodeCombination(KeyCode.PLUS, KeyCombination.CONTROL_DOWN)] =
                    Runnable { zoomBy(ZOOM_STEP) }
                accelerators[KeyCodeCombination(KeyCode.EQUALS, KeyCombination.CONTROL_DOWN)] =
                    Runnable { zoomBy(ZOOM_STEP) }
                accelerators[KeyCodeCombination(KeyCode.ADD, KeyCombination.CONTROL_DOWN)] =
                    Runnable { zoomBy(ZOOM_STEP) }
                accelerators[KeyCodeCombination(KeyCode.MINUS, KeyCombination.CONTROL_DOWN)] =
                    Runnable { zoomBy(-ZOOM_STEP) }
                accelerators[KeyCodeCombination(KeyCode.SUBTRACT, KeyCombination.CONTROL_DOWN)] =
                    Runnable { zoomBy(-ZOOM_STEP) }
                accelerators[KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.CONTROL_DOWN)] =
                    Runnable { resetView() }
                accelerators[KeyCodeCombination(KeyCode.NUMPAD0, KeyCombination.CONTROL_DOWN)] =
                    Runnable { resetView() }
                println("⌨️ Горячие клавиши масштаба зарегистрированы: Ctrl + , Ctrl - , Ctrl 0")
            } else {
                println("⚠️ Не удалось зарегистрировать горячие клавиши масштаба (нет сцены)")
            }

            // Фильтр фазы перехвата: WebView забирает Ctrl+0 себе, поэтому
            // обрабатываем масштаб раньше, чем событие дойдёт до страницы.
            stage?.scene?.addEventFilter(KeyEvent.KEY_PRESSED) { e ->
                if (e.isControlDown) {
                    when (e.code) {
                        KeyCode.DIGIT0, KeyCode.NUMPAD0 -> {
                            resetView()
                            e.consume()
                        }
                        KeyCode.PLUS, KeyCode.EQUALS, KeyCode.ADD -> {
                            zoomBy(ZOOM_STEP)
                            e.consume()
                        }
                        KeyCode.MINUS, KeyCode.SUBTRACT -> {
                            zoomBy(-ZOOM_STEP)
                            e.consume()
                        }
                        else -> Unit
                    }
                }
            }
        }

        // Пункт меню "Сменить подстанцию"
        val switchSubstationItem = MenuItem("🔌 Сменить подстанцию")
        switchSubstationItem.setOnAction { showSwitchSubstationDialog() }
        devMenuBtn.items.add(switchSubstationItem)

        // ===== НОВЫЙ ПУНКТ: добавить ПС =====
        val addSubstationItem = MenuItem("➕ Добавить подстанцию")
        addSubstationItem.setOnAction { showAddSubstationDialog() }
        devMenuBtn.items.add(addSubstationItem)

        // Пункт меню "Удалить подстанцию"
        val removeSubstationItem = MenuItem("🗑️ Удалить подстанцию")
        removeSubstationItem.setOnAction { showRemoveSubstationDialog() }
        devMenuBtn.items.add(removeSubstationItem)

        // ===== Пункты меню для масштаба маркеров =====
        devMenuBtn.items.add(javafx.scene.control.SeparatorMenuItem())

        val increaseMarkersItem = MenuItem("🔍 Увеличить маркеры (+10%)")
        increaseMarkersItem.setOnAction { applyMarkerScale(markerScale + 0.2) }
        devMenuBtn.items.add(increaseMarkersItem)

        val decreaseMarkersItem = MenuItem("🔍 Уменьшить маркеры (−10%)")
        decreaseMarkersItem.setOnAction { applyMarkerScale(markerScale - 0.2) }
        devMenuBtn.items.add(decreaseMarkersItem)

        val resetMarkersItem = MenuItem("🔍 Сбросить масштаб маркеров (100%)")
        resetMarkersItem.setOnAction { applyMarkerScale(1.0) }
        devMenuBtn.items.add(resetMarkersItem)

        // ===== Масштаб схемы: надёжные способы кроме колеса мыши =====
        devMenuBtn.items.add(javafx.scene.control.SeparatorMenuItem())

        val zoomInItem = MenuItem("🔎 Приблизить схему (Ctrl +)")
        zoomInItem.setOnAction { zoomBy(ZOOM_STEP) }
        devMenuBtn.items.add(zoomInItem)

        val zoomOutItem = MenuItem("🔎 Отдалить схему (Ctrl −)")
        zoomOutItem.setOnAction { zoomBy(-ZOOM_STEP) }
        devMenuBtn.items.add(zoomOutItem)

        val zoomResetItem = MenuItem("🔎 Масштаб схемы 100% (Ctrl 0)")
        zoomResetItem.setOnAction { resetView() }
        devMenuBtn.items.add(zoomResetItem)
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
            // Порядок поиска: dataDir/schemas → appDir/schemas → ресурсы JAR → src/... (IDE)
            val schema = Schemas.find(substationKey)
            if (schema != null) {
                webView.engine.loadContent(buildSvgHtml(schema.content))
                println("✅ SVG загружен: ${schema.source} (${schema.sizeKb} КБ)")
                return
            }
            println("❌ Не найдена ни одна схема для ПС '$substationKey'")
        } catch (e: Exception) {
            println("❌ Ошибка загрузки SVG: ${e.message}")
            e.printStackTrace()
        }

        // 6. Совсем ничего не нашли — заглушка с подсказкой, куда положить файл
        val schemasPath = AppPaths.schemasDir.absolutePath
        val emptyHtml = """
    <!DOCTYPE html>
    <html>
      <head>
        <meta charset="utf-8">
        <style>
          body { font-family: ${UiFonts.cssSans}; padding: 40px; color: #999; }
          code { color: #333; background: #f4f4f4; padding: 2px 6px; border-radius: 3px; }
        </style>
      </head>
      <body>
        <h2>⚠️ Схема для ПС '$substationKey' не найдена</h2>
        <p>Положите файл <code>schema_${substationKey}.svg</code> в папку <code>schemas/</code>:</p>
        <p><code>$schemasPath</code></p>
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

    private fun showAddSubstationDialog() {
        val dialog = Dialog<ButtonType>()
        dialog.title = "➕ Добавить подстанцию"
        dialog.headerText = "Введите название новой ПС"

        val content = VBox(12.0)
        content.style = "-fx-padding: 20px; -fx-pref-width: 540px;"

        val nameField = TextField()
        nameField.promptText = "Например: Новая ПС"
        nameField.style = "-fx-padding: 8px; -fx-font-size: 14px;"

        val keyLabel = Label("Ключ: —")
        keyLabel.style = "-fx-font-family: 'Consolas', monospace; -fx-text-fill: #007bff; -fx-font-size: 13px;"

        val schemaLabel = Label("Файл схемы: —")
        schemaLabel.style = "-fx-font-family: 'Consolas', monospace; -fx-text-fill: #28a745; -fx-font-size: 13px;"

        val hint = Label(
            "💡 После добавления положите SVG-файл со схемой в папку schemas/ " +
                    "рядом с приложением. Имя файла — точь-в-точь как указано выше."
        )
        hint.style = "-fx-text-fill: #6c757d; -fx-font-size: 11px; -fx-wrap-text: true;"
        hint.maxWidth = 500.0
        hint.isWrapText = true

        // ===== Кнопка «Открыть папку schemas» =====
        val openSchemasBtn = Button("📂 Открыть папку schemas")
        openSchemasBtn.style = """
        -fx-background-color: #17a2b8;
        -fx-text-fill: white;
        -fx-padding: 6px 14px;
        -fx-background-radius: 4px;
        -fx-font-size: 12px;
        -fx-cursor: hand;
    """.trimIndent()
        openSchemasBtn.setOnAction {
            AppPaths.schemasDir.mkdirs()
            val opened = AppPaths.openFolder(AppPaths.schemasDir)
            if (!opened) {
                showError("Не удалось открыть папку:\n${AppPaths.schemasDir.absolutePath}")
            }
        }

        val schemasPathLabel = Label(AppPaths.schemasDir.absolutePath)
        schemasPathLabel.style = "-fx-font-family: 'Consolas', monospace; -fx-text-fill: #6c757d; -fx-font-size: 10px;"
        schemasPathLabel.isWrapText = true
        schemasPathLabel.maxWidth = 500.0

        val pathBox = VBox(4.0, schemasPathLabel, openSchemasBtn)
        pathBox.style = "-fx-padding: 8px 0 0 0;"

        // Пересчёт ключа и имени файла «на лету»
        nameField.textProperty().addListener { _, _, newValue ->
            val trimmed = newValue.trim()
            if (trimmed.isEmpty()) {
                keyLabel.text = "Ключ: —"
                schemaLabel.text = "Файл схемы: —"
            } else {
                val key = Substations.generateKey(trimmed)
                keyLabel.text = "Ключ: $key"
                schemaLabel.text = "Файл схемы: schemas${File.separator}schema_$key.svg"
            }
        }

        content.children.addAll(
            Label("Название ПС:"), nameField,
            keyLabel,
            schemaLabel,
            hint,
            pathBox
        )

        dialog.dialogPane.content = content
        dialog.dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        dialog.showAndWait().ifPresent { result ->
            if (result == ButtonType.OK) {
                val displayName = nameField.text.trim()
                if (displayName.isEmpty()) {
                    showError("Введите название ПС")
                    return@ifPresent
                }

                val key = Substations.generateKey(displayName)

                if (Substations.ALL.any { it.key == key }) {
                    showError("ПС с ключом '$key' уже существует")
                    return@ifPresent
                }
                if (Substations.ALL.any { it.displayName.equals(displayName, ignoreCase = true) }) {
                    showError("ПС с названием '$displayName' уже существует")
                    return@ifPresent
                }

                val newSub = Substation(key = key, displayName = displayName)
                val added = Substations.add(newSub)
                if (!added) {
                    showError("Не удалось добавить ПС (ключ занят)")
                    return@ifPresent
                }

                AppPaths.schemasDir.mkdirs()

                println("➕ Добавлена ПС: ${newSub.displayName} (key=${newSub.key})")

                showSubstationAddedDialog(newSub)
            }
        }
    }

    private fun showRemoveSubstationDialog() {
        if (Substations.ALL.size <= 1) {
            showInfo("⚠️ Нельзя удалить единственную ПС")
            return
        }

        val choices = Substations.ALL.map { "${it.displayName} (${it.key})" }
        val dialog = ChoiceDialog(choices.first(), choices)
        dialog.title = "🗑️ Удалить подстанцию"
        dialog.headerText = "Выберите ПС для удаления"
        dialog.contentText = "Подстанция:"

        val result = dialog.showAndWait()
        if (result.isEmpty) return

        val selected = result.get()
        val key = selected.substringAfter("(").substringBefore(")").trim()
        val sub = Substations.byKey(key) ?: return

        if (sub.key == currentSubstation.key) {
            showError("Нельзя удалить текущую открытую ПС. Сначала переключитесь на другую.")
            return
        }

        val confirm = Alert(AlertType.CONFIRMATION)
        confirm.title = "Удаление ПС"
        confirm.headerText = "Удалить '${sub.displayName}'?"
        confirm.contentText = """
        Будут удалены:
        • запись из substations.json
        • файл БД equipment_${sub.key}.db
        • папка бэкапов backups/${sub.key}
        • файл схемы schemas/schema_${sub.key}.svg

        Продолжить?
    """.trimIndent()

        val confirmResult = confirm.showAndWait()
        if (confirmResult.isEmpty || confirmResult.get() != ButtonType.OK) return

        // 1. Удаляем из списка
        Substations.remove(sub.key)

        // 2. Удаляем файлы
        try {
            File(AppPaths.dataDir, "equipment_${sub.key}.db").delete()
            File(AppPaths.backupsDir, sub.key).deleteRecursively()
            File(AppPaths.schemasDir, "schema_${sub.key}.svg").delete()
            println("🗑️ Удалены файлы ПС: ${sub.key}")
        } catch (e: Exception) {
            println("⚠️ Ошибка удаления файлов ПС: ${e.message}")
        }

        showToast("🗑️ ПС удалена: ${sub.displayName}")
    }

    private fun switchSubstation(newSub: Substation) {
        println("🔌 Смена подстанции: ${currentSubstation.displayName} → ${newSub.displayName}")

        if (isInitialized) {
            try { saveEquipment() } catch (e: Exception) {
                println("⚠️ Не удалось сохранить перед сменой ПС: ${e.message}")
            }
        }

        clearWebViewEquipment()
        try { database.close() } catch (e: Exception) {
            println("⚠️ Ошибка закрытия БД: ${e.message}")
        }

        currentSubstation = newSub
        database = Database(newSub.key)
        database.autoBackup()

        // ===== ЗАПОМИНАЕМ ВЫБОР =====
        AppSettings.setLastSubstationKey(newSub.key)

        EquipmentTypes.loadFromDatabase(database)
        EquipmentModels.loadFromDatabase(database)
        DefectTypes.loadFromDatabase(database)

        markerScale = database.getMarkerScale()
        println("🔍 Масштаб маркеров для ${newSub.displayName}: $markerScale")

        lastSavedHash = 0
        isEditMode = false
        currentEditingEquipmentId = null

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
          :root {
              --marker-scale: 1.0;
          }
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
          #image {
            width: 100%;
            height: 100%;
          }
          #image svg {
            display: block;
            width: 100%;
            height: 100%;
            transform-origin: center center;
            will-change: transform;
          }
          /*
           * Слой маркеров должен совпадать не с окном, а с реально видимым
           * прямоугольником схемы: SVG вписывается с сохранением пропорций
           * (preserveAspectRatio="xMidYMid meet"), поэтому по краям остаются поля.
           * Геометрию выставляет JS-функция fitMarkerLayer() (см. <script> ниже)
           * при загрузке, ресайзе окна и пересоздании слоя.
           */
          #equipment-container {
            position: absolute;
            left: 0;
            top: 0;
            width: 100%;
            height: 100%;
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
              font-family: ${UiFonts.cssSans};
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
            font-family: ${UiFonts.cssSans};
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
            font-family: ${UiFonts.cssSans};
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
          /* ===== РАЗМЕРЫ МАРКЕРОВ С УЧЁТОМ МАСШТАБА ПС ===== */
          .equipment-marker.small {
              width:  calc(20px * var(--marker-scale));
              height: calc(20px * var(--marker-scale));
          }
          .equipment-marker.small .dot {
              width:  calc(16px * var(--marker-scale));
              height: calc(16px * var(--marker-scale));
              font-size: calc(8px * var(--marker-scale));
          }
          .equipment-marker.normal {
              width:  calc(28px * var(--marker-scale));
              height: calc(28px * var(--marker-scale));
          }
          .equipment-marker.normal .dot {
              width:  calc(24px * var(--marker-scale));
              height: calc(24px * var(--marker-scale));
              font-size: calc(11px * var(--marker-scale));
          }
          .equipment-marker.large {
              width:  calc(36px * var(--marker-scale));
              height: calc(36px * var(--marker-scale));
          }
          .equipment-marker.large .dot {
              width:  calc(32px * var(--marker-scale));
              height: calc(32px * var(--marker-scale));
              font-size: calc(14px * var(--marker-scale));
          }
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

          /* ===== ПУЛЬСАЦИЯ И ПОДСВЕТКА МАРКЕРА ===== */
          @keyframes defectmap-pulse {
              0% {
                  box-shadow:
                      0 0 0 4px rgba(255, 235, 59, 0.45),
                      0 0 20px 8px rgba(255, 235, 59, 0.85),
                      0 0 40px 14px rgba(255, 200, 0, 0.45);
              }
              50% {
                  box-shadow:
                      0 0 0 8px rgba(255, 235, 59, 0.55),
                      0 0 30px 14px rgba(255, 235, 59, 0.95),
                      0 0 60px 22px rgba(255, 200, 0, 0.60);
              }
              100% {
                  box-shadow:
                      0 0 0 4px rgba(255, 235, 59, 0.45),
                      0 0 20px 8px rgba(255, 235, 59, 0.85),
                      0 0 40px 14px rgba(255, 200, 0, 0.45);
              }
          }

          /* Подсветка контейнера: только масштаб и z-index */
          .equipment-marker.highlighted {
              transform: translate(-50%, -50%) scale(1.6) !important;
              z-index: 9999 !important;
              pointer-events: none;
          }

          /* Подсветка самого кружка .dot: рамка + пульсация */
          .equipment-marker.highlighted .dot {
              border: 3px solid #ffeb3b !important;
              border-radius: 50%;
              animation: defectmap-pulse 1.2s ease-in-out infinite;
              transition: all 0.25s ease;
          }

          /* Если маркер скрыт, но его подсвечивают — показываем */
          .equipment-marker.hidden.highlighted .dot {
              opacity: 1 !important;
              pointer-events: none;
          }
          .equipment-marker.hidden.highlighted .tooltip-text {
              opacity: 1 !important;
              visibility: visible !important;
          }
        </style>
      </head>
      <body>
        <div id="container">
          <div id="image-wrapper">
            <div id="image">$svgContent</div>
            <div id="equipment-container"></div>
          </div>
        </div>
        <script>
          (function () {
            // Подгоняет слой маркеров под реально видимый прямоугольник схемы:
            // SVG вписан с сохранением пропорций, по краям остаются поля.
            function fitMarkerLayer() {
              var wrapper = document.getElementById('image-wrapper');
              var layer = document.getElementById('equipment-container');
              var svg = document.querySelector('#image svg');
              if (!wrapper || !layer) return;

              var W = wrapper.clientWidth || 0;
              var H = wrapper.clientHeight || 0;
              if (!W || !H) return;

              var ar = 1;
              if (svg) {
                var vb = svg.viewBox && svg.viewBox.baseVal;
                if (vb && vb.width > 0 && vb.height > 0) {
                  ar = vb.width / vb.height;
                } else if (svg.width && svg.height && svg.width.baseVal && svg.height.baseVal &&
                           svg.width.baseVal.value > 0 && svg.height.baseVal.value > 0) {
                  ar = svg.width.baseVal.value / svg.height.baseVal.value;
                }
              }

              var w = W, h = W / ar;
              if (h > H) { h = H; w = H * ar; }

              layer.style.position = 'absolute';
              layer.style.left = ((W - w) / 2) + 'px';
              layer.style.top = ((H - h) / 2) + 'px';
              layer.style.width = w + 'px';
              layer.style.height = h + 'px';
            }

            window.fitMarkerLayer = fitMarkerLayer;

            // Прямоугольник, в котором реально нарисована схема.
            // По нему считаются проценты координат маркеров (клик, перетаскивание),
            // иначе при полях вокруг схемы координаты "уезжают".
            window.dmBoxRect = function () {
              var layer = document.getElementById('equipment-container');
              if (layer) {
                var r = layer.getBoundingClientRect();
                if (r.width > 0 && r.height > 0) return r;
              }
              var wrapper = document.getElementById('image-wrapper');
              return wrapper.getBoundingClientRect();
            };

            window.addEventListener('resize', function () { setTimeout(fitMarkerLayer, 0); });

            // Контейнер маркеров пересоздаётся из Kotlin — следим за этим
            var root = document.getElementById('image-wrapper');
            if (root && window.MutationObserver) {
              new MutationObserver(function () { setTimeout(fitMarkerLayer, 0); })
                .observe(root, { childList: true });
            }
            setTimeout(fitMarkerLayer, 0);
          })();
        </script>
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
        // ===== Применяем масштаб маркеров =====
        webView.engine.executeScript("""
        document.documentElement.style.setProperty('--marker-scale', '${markerScale}');
    """.trimIndent())

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

        // ===== Применяем масштаб =====
        webView.engine.executeScript("""
        document.documentElement.style.setProperty('--marker-scale', '${markerScale}');
    """.trimIndent())

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
            val now = System.currentTimeMillis()
            val dy = event.deltaY

            if (debugScroll) {
                println(
                    "🖱️ wheel deltaY=${"%.1f".format(dy)} deltaX=${"%.1f".format(event.deltaX)}" +
                        " zoom=${"%.0f".format(zoomLevel * 100)}% dt=${now - lastAppliedAt}ms" +
                        " (invert=$wheelInvert)"
                )
            }

            // В X11/VirtualBox на каждый щелчок приходит ещё и «фантомное» событие
            // с deltaY = 0. Раньше оно трактовалось как прокрутка вниз и уменьшало
            // масштаб — из-за этого колесо вверх тоже отдаляло. Такие события игнорируем.
            if (dy == 0.0) {
                event.consume()
                return@setOnScroll
            }

            // Защита от дублей одного и того же щелчка
            if ((now - lastAppliedAt) < WHEEL_THROTTLE_MS) {
                if (debugScroll) println("🖱️ wheel: дубль подавлен троттлингом")
                event.consume()
                return@setOnScroll
            }
            lastAppliedAt = now

            // Колесо от себя (deltaY > 0) — приблизить
            val step = if (dy > 0) ZOOM_STEP else -ZOOM_STEP
            zoomBy(if (wheelInvert) -step else step)
            event.consume()
        }
    }

    /** true, если приложение запущено с -Ddefectmap.debugScroll=true */
    private val debugScroll: Boolean =
        System.getProperty("defectmap.debugScroll") == "true"

    /** Направление колеса можно инвертировать: -Ddefectmap.wheelInvert=true */
    private val wheelInvert: Boolean =
        System.getProperty("defectmap.wheelInvert") == "true"

    private companion object {
        /** Минимальный интервал между шагами зума колесом, мс. */
        const val WHEEL_THROTTLE_MS = 40L
    }

    /** Изменяет масштаб схемы на [delta] и применяет трансформацию. */
    private fun zoomBy(delta: Double) {
        val newZoom = (zoomLevel + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (newZoom == zoomLevel) return
        zoomLevel = newZoom
        applyViewTransform()
        println("🔍 Масштаб схемы: ${"%.0f".format(zoomLevel * 100)}%")
    }

    private fun applyViewTransform() {
        webView.engine.executeScript("""
            (function() {
                var wrapper = document.getElementById('image-wrapper');
                if (!wrapper) return;
                wrapper.style.transform = 'translate(${currentTranslateX}px, ${currentTranslateY}px) scale($zoomLevel)';
                wrapper.style.transformOrigin = 'center center';
            })();
        """.trimIndent())
    }

    /** Сброс масштаба и сдвига к 100%. */
    private fun resetView() {
        zoomLevel = 1.0
        currentTranslateX = 0.0
        currentTranslateY = 0.0
        applyViewTransform()
        println("🔍 Масштаб схемы сброшен")
    }

    private var lastAppliedAt = 0L

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
                            onDefectChanged = {
                                // Обновляем список дефектов, если в карточке что-то поменялось
                                val freshDefects = mutableListOf<DefectViewItem>()
                                loadEquipment().forEach { eq ->
                                    database.getDefectsByEquipment(eq.id).forEach { defect ->
                                        freshDefects.add(
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
                                // Обновляем исходный список и переприменяем фильтр
                                allDefects.clear()
                                allDefects.addAll(freshDefects)
                                applyFilter()
                                headerLabel.text = "📊 ВСЕ ДЕФЕКТЫ (${allDefects.size})"
                            }
                        )
                        cardController.show()
                        // окно списка дефектов НЕ закрываем
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
                                var wrapperRect = (window.dmBoxRect ? window.dmBoxRect() : wrapper.getBoundingClientRect());
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
                        var wrapperRect = (window.dmBoxRect ? window.dmBoxRect() : wrapper.getBoundingClientRect());
                        
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
                            var wrapperRect = (window.dmBoxRect ? window.dmBoxRect() : wrapper.getBoundingClientRect());
                            
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
            editEquipmentDialog(equipment.id)
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
            var rect = (window.dmBoxRect ? window.dmBoxRect() : wrapper.getBoundingClientRect());
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

                            val (modelConfirmed, modelId) = askEquipmentModel(type, typeName, null)
                            if (!modelConfirmed) {
                                println("❌ Выбор типа изделия отменён")
                                return@runLater
                            }

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
                                database.setEquipmentModel(id, modelId)
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
            var rect = (window.dmBoxRect ? window.dmBoxRect() : wrapper.getBoundingClientRect());
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

    // ======================== ЕДИНОЕ ОКНО РЕДАКТИРОВАНИЯ ========================

    /** Результат единого окна параметров оборудования. */
    private data class EquipmentEditorResult(
        val name: String,
        val typeKey: String,
        val modelId: String?,
        val cell: String,
        val size: String,
        val photo: String?,
        val pickCell: Boolean = false
    )

    /**
     * Одно окно со всеми параметрами оборудования: ДН, категория, тип изделия,
     * ячейка, размер метки и индивидуальное фото. Используется для
     * редактирования (добавление по-прежнему идёт через клик по схеме).
     */
    private fun showEquipmentEditor(
        dialogTitle: String,
        initial: EquipmentEditorResult
    ): EquipmentEditorResult? {
        val stage = Stage()
        stage.title = dialogTitle
        stage.initModality(javafx.stage.Modality.WINDOW_MODAL)
        stage.initOwner(webView.scene.window)

        var photoName: String? = initial.photo

        val nameField = TextField(initial.name)
        nameField.promptText = "Диспетчерское наименование (ДН)"
        nameField.prefWidth = 380.0

        val typeCombo = ComboBox<String>()
        typeCombo.items.addAll(EquipmentTypes.ALL_TYPES.map { it.second })
        typeCombo.value = EquipmentTypes.getTypeName(initial.typeKey)
        typeCombo.prefWidth = 380.0

        val modelCombo = ComboBox<String>()
        modelCombo.isEditable = true
        modelCombo.prefWidth = 380.0
        modelCombo.promptText = "Например, ТФЗМ-220 — можно ввести новый"

        val cellField = TextField(initial.cell)
        cellField.promptText = "Ячейка"

        val sizeCombo = ComboBox<String>()
        sizeCombo.items.addAll(listOf("small", "normal", "large"))
        sizeCombo.value = initial.size

        val photoLabel = Label(photoName ?: "не задано — будет фото типа изделия")
        photoLabel.style = "-fx-text-fill: #6c757d; -fx-font-size: 12px;"
        photoLabel.isWrapText = true

        fun refreshModels() {
            val typeKey = EquipmentTypes.ALL_TYPES.find { it.second == typeCombo.value }?.first ?: initial.typeKey
            modelCombo.items.setAll(EquipmentModels.names(typeKey))
            if (modelCombo.value.isNullOrBlank()) {
                modelCombo.value = EquipmentModels.displayName(initial.modelId) ?: ""
            }
        }
        typeCombo.valueProperty().addListener { _, _, _ -> refreshModels() }
        refreshModels()

        val pickCellBtn = Button("🗺️ Выбрать на схеме")
        pickCellBtn.setOnAction {
            stage.close()
        }

        val photoBtn = Button("🖼️ Фото оборудования…")
        photoBtn.setOnAction {
            val chooser = FileChooser()
            chooser.title = "Фото оборудования"
            chooser.extensionFilters.addAll(
                FileChooser.ExtensionFilter("Изображения", "*.jpg", "*.jpeg", "*.png", "*.jfif"),
                FileChooser.ExtensionFilter("Все файлы", "*.*")
            )
            AppPaths.imagesDir.mkdirs()
            chooser.initialDirectory = AppPaths.imagesDir
            val file = chooser.showOpenDialog(stage) ?: return@setOnAction
            try {
                val safeName = file.name.replace(Regex("[^\\p{L}\\p{N}._() -]"), "_")
                val target = File(AppPaths.imagesDir, safeName)
                if (file.absolutePath != target.absolutePath) file.copyTo(target, overwrite = true)
                photoName = safeName
                photoLabel.text = safeName
            } catch (t: Throwable) {
                showError("Не удалось скопировать файл:\n${t.message}")
            }
        }

        val clearPhotoBtn = Button("🚫 Без фото")
        clearPhotoBtn.setOnAction {
            photoName = null
            photoLabel.text = "не задано — будет фото типа изделия"
        }

        val grid = GridPane()
        grid.hgap = 10.0
        grid.vgap = 10.0
        grid.style = "-fx-padding: 15px;"
        grid.add(Label("ДН:"), 0, 0); grid.add(nameField, 1, 0)
        grid.add(Label("Оборудование:"), 0, 1); grid.add(typeCombo, 1, 1)
        grid.add(Label("Тип изделия:"), 0, 2); grid.add(modelCombo, 1, 2)
        grid.add(Label("Ячейка:"), 0, 3)
        grid.add(HBox(8.0, cellField, pickCellBtn), 1, 3)
        grid.add(Label("Размер метки:"), 0, 4); grid.add(sizeCombo, 1, 4)
        grid.add(Label("Фото:"), 0, 5)
        grid.add(HBox(8.0, photoBtn, clearPhotoBtn), 1, 5)
        grid.add(photoLabel, 1, 6)

        val okBtn = Button("💾 Сохранить")
        val cancelBtn = Button("Отмена")
        val buttons = HBox(10.0, okBtn, cancelBtn)
        buttons.alignment = Pos.CENTER_RIGHT
        buttons.style = "-fx-padding: 10px 15px;"

        val root = VBox(10.0, grid, buttons)
        root.style = "-fx-background-color: white;"
        stage.scene = Scene(root)
        stage.isResizable = false

        var result: EquipmentEditorResult? = null
        var pickCellRequested = false

        okBtn.setOnAction {
            val name = nameField.text.trim()
            if (name.isEmpty()) {
                showInfo("⚠️ Укажите диспетчерское наименование")
                return@setOnAction
            }
            val typeName = typeCombo.value ?: ""
            val typeKey = EquipmentTypes.ALL_TYPES.find { it.second == typeName }?.first ?: "other"
            val modelText = modelCombo.value?.trim().orEmpty()
            val model = if (modelText.isEmpty()) null else EquipmentModels.ensureModel(database, typeKey, modelText)
            result = EquipmentEditorResult(
                name = name,
                typeKey = typeKey,
                modelId = model?.id,
                cell = cellField.text.trim(),
                size = sizeCombo.value ?: "normal",
                photo = photoName
            )
            stage.close()
        }

        pickCellBtn.setOnAction {
            pickCellRequested = true
            stage.close()
        }
        cancelBtn.setOnAction { stage.close() }

        stage.showAndWait()
        if (pickCellRequested) {
            return EquipmentEditorResult(
                name = nameField.text.trim(),
                typeKey = EquipmentTypes.ALL_TYPES.find { it.second == typeCombo.value }?.first ?: initial.typeKey,
                modelId = EquipmentModels.find(
                    EquipmentTypes.ALL_TYPES.find { it.second == typeCombo.value }?.first ?: initial.typeKey,
                    modelCombo.value ?: ""
                )?.id,
                cell = cellField.text.trim(),
                size = sizeCombo.value ?: "normal",
                photo = photoName,
                pickCell = true
            )
        }
        return result
    }

    /**
     * Редактирование оборудования в одном окне: все параметры сохраняются
     * сразу, маркер на схеме обновляется, фото применяется в карточке.
     */
    private fun editEquipmentDialog(equipmentId: String) {
        val allEquipment = loadEquipment()
        val equipment = allEquipment.find { it.id == equipmentId }
        if (equipment == null) {
            println("❌ Оборудование не найдено: $equipmentId")
            showToast("⚠️ Оборудование не найдено")
            return
        }

        var state = EquipmentEditorResult(
            name = equipment.name,
            typeKey = equipment.type,
            modelId = equipment.modelId,
            cell = equipment.cell,
            size = equipment.size,
            photo = equipment.photo
        )

        val edited: EquipmentEditorResult
        while (true) {
            val r = showEquipmentEditor("✏️ Редактирование: ${equipment.name}", state)
                ?: run {
                    println("❌ Редактирование отменено")
                    return
                }
            if (r.pickCell) {
                val picked = selectCell(state.cell) ?: return
                state = r.copy(cell = picked, pickCell = false)
                continue
            }
            edited = r
            break
        }

        val letter = EquipmentTypes.getLetter(edited.typeKey)
        val updated = equipment.copy(
            name = edited.name,
            type = edited.typeKey,
            letter = letter,
            cell = edited.cell,
            size = edited.size,
            modelId = edited.modelId,
            photo = edited.photo
        )
        val updatedList = allEquipment.map { if (it.id == equipment.id) updated else it }
        database.saveEquipment(updatedList)
        database.setEquipmentModel(equipment.id, edited.modelId)
        database.setEquipmentPhoto(equipment.id, edited.photo)

        val escapedName = edited.name.replace("'", "\\'")
        val escapedCell = edited.cell.replace("'", "\\'")
        val escapedType = edited.typeKey.replace("'", "\\'")
        val escapedLetter = letter.replace("'", "\\'")
        val escapedSize = edited.size.replace("'", "\\'")

        webView.engine.executeScript(
            """
            (function() {
                var marker = document.getElementById('$equipmentId');
                if (marker) {
                    marker.className = 'equipment-marker $escapedType $escapedSize';
                    var dot = marker.querySelector('.dot');
                    if (dot) dot.textContent = '$escapedLetter';
                    var tip = marker.querySelector('.tooltip-text');
                    if (tip) tip.textContent = '$escapedName';
                    marker.style.left = '${updated.left}%';
                    marker.style.top = '${updated.top}%';
                }
                var all = window.equipment || [];
                for (var i = 0; i < all.length; i++) {
                    if (all[i].id === '$equipmentId') {
                        all[i].name = '$escapedName';
                        all[i].type = '$escapedType';
                        all[i].letter = '$escapedLetter';
                        all[i].cell = '$escapedCell';
                        all[i].size = '$escapedSize';
                        break;
                    }
                }
                console.log('✅ Оборудование обновлено из единого окна');
            })();
            """.trimIndent()
        )
        syncWindowEquipment()

        showToast("✅ Сохранено: ${edited.name}")
        println("✅ Оборудование обновлено: ${edited.name} (${edited.typeKey}, ${edited.modelId ?: "без типа"})")
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

                        val (modelConfirmed, newModelId) = askEquipmentModel(newType, typeName, null)
                        if (!modelConfirmed) {
                            println("❌ Выбор типа изделия отменён")
                            return@runLater
                        }

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
                            database.setEquipmentModel(equipmentId, newModelId)
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
                                                        model = EquipmentModels.displayName(eq.modelId) ?: "",
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
                mainLayout.prefWidth = 1080.0
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
                searchField.promptText = "🔍 Поиск по названию..."
                searchField.style = "-fx-pref-width: 250px; -fx-font-size: 13px; -fx-padding: 6px 10px; -fx-background-radius: 4px; -fx-border-color: #ced4da; -fx-border-radius: 4px;"

                val tableView = TableView<EquipmentTableItem>()
                tableView.style = "-fx-font-size: 13px; -fx-border-color: #dee2e6;"

                // ===== КОЛОНКИ =====
                val colNumber = TableColumn<EquipmentTableItem, Int>("№")
                colNumber.cellValueFactory = PropertyValueFactory("number")
                colNumber.prefWidth = 50.0
                colNumber.minWidth = 50.0
                colNumber.maxWidth = 50.0
                colNumber.style = "-fx-alignment: CENTER;"

                val colName = TableColumn<EquipmentTableItem, String>("ДН")
                colName.cellValueFactory = PropertyValueFactory("name")
                colName.prefWidth = 420.0
                colName.minWidth = 200.0

                val colType = TableColumn<EquipmentTableItem, String>("Оборудование")
                colType.cellValueFactory = PropertyValueFactory("type")
                colType.prefWidth = 300.0
                colType.minWidth = 150.0

                val colModel = TableColumn<EquipmentTableItem, String>("Тип")
                colModel.cellValueFactory = PropertyValueFactory("model")
                colModel.prefWidth = 190.0
                colModel.minWidth = 120.0

                val colCell = TableColumn<EquipmentTableItem, String>("Ячейка")
                colCell.cellValueFactory = PropertyValueFactory("cell")
                colCell.prefWidth = 120.0
                colCell.minWidth = 80.0
                colCell.style = "-fx-alignment: CENTER;"

                val colActions = TableColumn<EquipmentTableItem, Void>("Действие")
                colActions.prefWidth = 90.0
                colActions.minWidth = 90.0
                colActions.maxWidth = 90.0
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
                                                model = EquipmentModels.displayName(eq.modelId) ?: "",
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
                    colNumber, colName, colType, colModel, colCell, colActions
                )

                // ===== РАСТЯГИВАЕМ ТАБЛИЦУ ПО ШИРИНЕ =====
                tableView.columnResizePolicy =
                    javafx.scene.control.TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN

                fun toTableItems(data: List<EquipmentData>): List<EquipmentTableItem> {
                    return data.mapIndexed { index, item ->
                        val typeDisplayName = EquipmentTypes.ALL_TYPES.toMap()[item.type] ?: item.type
                        EquipmentTableItem(
                            number = index + 1,
                            id = item.id,
                            name = item.name,
                            type = typeDisplayName,
                            model = EquipmentModels.displayName(item.modelId) ?: "",
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
                                    it.type.contains(searchText, ignoreCase = true) ||
                                    (EquipmentModels.displayName(it.modelId) ?: "").contains(searchText, ignoreCase = true)
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
                        editEquipmentDialog(selected.id)
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
                    // ===== Двойной клик — открыть карточку =====
                    if (event.clickCount == 2 && event.button == javafx.scene.input.MouseButton.PRIMARY) {
                        val selected = tableView.selectionModel.selectedItem
                        if (selected != null) {
                            val equipment = loadEquipment().find { it.id == selected.id }
                            if (equipment != null) {
                                val cardController = EquipmentCardController(
                                    equipment = equipment,
                                    database = database,
                                    onDefectChanged = {
                                        val updatedData = loadEquipment()
                                        val updatedItems = updatedData.mapIndexed { index, eq ->
                                            val typeDisplayName = EquipmentTypes.ALL_TYPES.toMap()[eq.type] ?: eq.type
                                            EquipmentTableItem(
                                                number = index + 1,
                                                id = eq.id,
                                                name = eq.name,
                                                type = typeDisplayName,
                                                model = EquipmentModels.displayName(eq.modelId) ?: "",
                                                cell = eq.cell,
                                                left = eq.left,
                                                top = eq.top
                                            )
                                        }
                                        tableView.items = FXCollections.observableArrayList(updatedItems)
                                        countLabel.text = "Показано: ${updatedData.size} из ${allEquipment.size}"
                                    }
                                )
                                cardController.show()
                                // окно списка НЕ закрываем
                            } else {
                                showError("Оборудование не найдено")
                            }
                        }
                    }

                    // ===== ПКМ — выбрать строку под курсором (для контекстного меню) =====
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
        println("📍 Показать на карте: $equipmentId")

        val escapedId = equipmentId.replace("'", "\\'")

        // ===== Шаг 1. Сброс зума и панорамы + поиск маркера + расчёт сдвига для центрирования =====
        val jsonResult = webView.engine.executeScript("""
        (function() {
            var id = '$escapedId';
            var wrapper = document.getElementById('image-wrapper');
            var container = document.getElementById('container');

            if (!wrapper || !container) {
                return JSON.stringify({ error: 'wrapper_or_container_not_found' });
            }

            // Сброс трансформа, чтобы замеры были честными
            wrapper.style.transform = 'translate(0px, 0px) scale(1)';
            wrapper.style.transformOrigin = 'center center';

            // Ищем маркер по data-equipment-id
            var marker = document.querySelector('[data-equipment-id="' + id + '"]');
            if (!marker) {
                return JSON.stringify({ error: 'marker_not_found', id: id });
            }

            // Замеряем положение
            var containerRect = container.getBoundingClientRect();
            var markerRect = marker.getBoundingClientRect();

            var markerCenterX = markerRect.left + markerRect.width / 2;
            var markerCenterY = markerRect.top + markerRect.height / 2;
            var containerCenterX = containerRect.left + containerRect.width / 2;
            var containerCenterY = containerRect.top + containerRect.height / 2;

            var dx = containerCenterX - markerCenterX;
            var dy = containerCenterY - markerCenterY;

            // Запоминаем, был ли маркер скрыт
            var wasHidden = marker.classList.contains('hidden');

            return JSON.stringify({
                dx: dx,
                dy: dy,
                wasHidden: wasHidden,
                markerId: marker.id || null,
                found: true
            });
        })();
    """.trimIndent()) as? String

        if (jsonResult == null || jsonResult.contains("\"error\"")) {
            println("⚠️ Не удалось найти маркер: $jsonResult")
            showToast("⚠️ Маркер не найден на схеме")
            return
        }

        val mapType = object : TypeToken<Map<String, Any>>() {}.type
        val data: Map<String, Any> = try {
            gson.fromJson(jsonResult, mapType)
        } catch (e: Exception) {
            println("❌ Ошибка парсинга JSON центрирования: ${e.message}")
            return
        }

        val dx = (data["dx"] as? Double) ?: 0.0
        val dy = (data["dy"] as? Double) ?: 0.0
        val wasHidden = (data["wasHidden"] as? Boolean) ?: false

        // ===== Шаг 2. Применяем сдвиг в Kotlin-переменных и в DOM =====
        currentTranslateX = dx
        currentTranslateY = dy
        zoomLevel = 1.0

        webView.engine.executeScript("""
        (function() {
            var wrapper = document.getElementById('image-wrapper');
            if (wrapper) {
                wrapper.style.transform = 'translate(${dx}px, ${dy}px) scale(1)';
                wrapper.style.transformOrigin = 'center center';
            }
        })();
    """.trimIndent())

        // ===== Шаг 3. Подсветка маркера =====
        webView.engine.executeScript("""
    (function() {
        var id = '$escapedId';
        var marker = document.querySelector('[data-equipment-id="' + id + '"]');
        if (!marker) return;

        var dot = marker.querySelector('.dot');
        if (!dot) return;

        var wasHidden = ${if (wasHidden) "true" else "false"};
        if (wasHidden) {
            marker.classList.remove('hidden');
        }

        // Сохраняем исходные стили
        var origMarkerTransform = marker.style.transform;
        var origMarkerZIndex = marker.style.zIndex;
        var origDotBoxShadow = dot.style.boxShadow;
        var origDotBorder = dot.style.border;
        var origDotTransform = dot.style.transform;

        // Подсветка: круглая, по контуру .dot
        marker.style.zIndex = '9999';
        marker.style.transform = 'translate(-50%, -50%) scale(1.6)';

        dot.style.borderRadius = '50%';
        dot.style.border = '3px solid #ffeb3b';
        dot.style.boxShadow =
            '0 0 0 6px rgba(255, 235, 59, 0.35), ' +
            '0 0 24px 10px rgba(255, 235, 59, 0.85), ' +
            '0 0 48px 18px rgba(255, 200, 0, 0.55)';
        dot.style.transition = 'all 0.25s ease';

        // Пульсация (по желанию)
        dot.style.animation = 'defectmap-pulse 1.2s ease-in-out infinite';

        // Через 3 секунды вернуть всё назад
        setTimeout(function() {
            marker.style.transform = origMarkerTransform;
            marker.style.zIndex = origMarkerZIndex;

            dot.style.boxShadow = origDotBoxShadow;
            dot.style.border = origDotBorder;
            dot.style.transform = origDotTransform;
            dot.style.animation = '';

            if (wasHidden) {
                marker.classList.add('hidden');
            }
        }, 3000);

        console.log('✅ Подсвечен маркер: ' + id);
    })();
""".trimIndent())

        showToast("📍 ${equipmentId}")
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

    private fun showSubstationAddedDialog(newSub: Substation) {
        val schemaPath = File(AppPaths.schemasDir, "schema_${newSub.key}.svg")

        val dialog = Dialog<ButtonType>()
        dialog.title = "✅ ПС добавлена"
        dialog.headerText = "Подстанция «${newSub.displayName}» добавлена"

        val content = VBox(12.0)
        content.style = "-fx-padding: 20px; -fx-pref-width: 580px;"

        val keyLabel = Label("Ключ: ${newSub.key}")
        keyLabel.style = "-fx-font-family: 'Consolas', monospace; -fx-font-size: 13px; -fx-text-fill: #007bff;"

        val fileLabel = Label("Имя файла схемы: schema_${newSub.key}.svg")
        fileLabel.style = "-fx-font-family: 'Consolas', monospace; -fx-font-size: 13px; -fx-text-fill: #28a745;"

        val hint = Label(
            "Положите SVG-файл со схемой в папку schemas/ рядом с приложением. " +
                    "Файл должен называться точно так, как указано выше."
        )
        hint.style = "-fx-text-fill: #6c757d; -fx-font-size: 11px; -fx-wrap-text: true;"
        hint.maxWidth = 540.0
        hint.isWrapText = true

        val pathLabel = Label(schemaPath.absolutePath)
        pathLabel.style = "-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px; -fx-text-fill: #333; -fx-background-color: #f8f9fa; -fx-padding: 8px; -fx-border-color: #dee2e6; -fx-border-radius: 4px; -fx-background-radius: 4px;"
        pathLabel.isWrapText = true
        pathLabel.maxWidth = 540.0

        val openSchemasBtn = Button("📂 Открыть папку schemas")
        openSchemasBtn.style = """
        -fx-background-color: #17a2b8;
        -fx-text-fill: white;
        -fx-padding: 8px 18px;
        -fx-background-radius: 4px;
        -fx-font-size: 13px;
        -fx-font-weight: bold;
        -fx-cursor: hand;
    """.trimIndent()
        openSchemasBtn.setOnAction {
            AppPaths.schemasDir.mkdirs()
            val opened = AppPaths.openFolder(AppPaths.schemasDir)
            if (!opened) {
                showError("Не удалось открыть папку:\n${AppPaths.schemasDir.absolutePath}")
            }
        }

        val nextSteps = Label(
            "После того как схема будет на месте:\n" +
                    "1. Закройте это окно\n" +
                    "2. Меню → 🔌 Сменить подстанцию\n" +
                    "3. Выберите «${newSub.displayName}»"
        )
        nextSteps.style = "-fx-text-fill: #6c757d; -fx-font-size: 11px; -fx-wrap-text: true;"
        nextSteps.maxWidth = 540.0
        nextSteps.isWrapText = true

        content.children.addAll(
            keyLabel,
            fileLabel,
            hint,
            pathLabel,
            openSchemasBtn,
            nextSteps
        )

        dialog.dialogPane.content = content
        dialog.dialogPane.buttonTypes.add(ButtonType.OK)

        dialog.showAndWait()
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

    /**
     * Спрашивает тип изделия (модель) для категории [typeKey].
     *
     * Возвращает пару: подтвердил ли пользователь и id модели (null — без типа).
     * Введённое название модели при необходимости создаётся в справочнике.
     */
    private fun askEquipmentModel(
        typeKey: String,
        typeName: String,
        currentModelId: String?
    ): Pair<Boolean, String?> {
        val dialog = Dialog<ButtonType>()
        dialog.title = "Тип изделия"
        dialog.headerText = "Оборудование: $typeName"

        val combo = ComboBox<String>()
        combo.isEditable = true
        combo.items.addAll(EquipmentModels.names(typeKey))
        combo.value = EquipmentModels.displayName(currentModelId)
            ?: EquipmentModels.names(typeKey).firstOrNull().orEmpty()
        combo.promptText = "Например, ВВБК-220 — можно ввести свой"
        combo.prefWidth = 340.0

        val content = VBox(
            8.0,
            Label("Тип изделия (модель):"),
            combo,
            Label("Можно выбрать из списка или ввести новый — он сохранится в справочнике.")
        )
        content.style = "-fx-padding: 10px;"
        dialog.dialogPane.content = content
        dialog.dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val result = dialog.showAndWait()
        if (result.isEmpty || result.get() != ButtonType.OK) return false to null

        val name = combo.value?.trim().orEmpty()
        if (name.isEmpty()) return true to null
        val model = EquipmentModels.ensureModel(database, typeKey, name)
        return true to model?.id
    }

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

        // ===== ТИПЫ ИЗДЕЛИЙ (МОДЕЛИ) ВЫБРАННОЙ КАТЕГОРИИ =====
        val modelsLabel = Label("🔧 Типы изделий: выберите категорию в таблице выше")
        modelsLabel.style = "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #333; -fx-padding: 8px 0 0 0;"

        val modelsTable = TableView<EquipmentModelData>()
        modelsTable.style = "-fx-font-size: 13px;"
        modelsTable.prefHeight = 200.0

        val colModelName = TableColumn<EquipmentModelData, String>("Тип изделия")
        colModelName.cellValueFactory = PropertyValueFactory("name")
        colModelName.prefWidth = 220.0

        val colModelPhoto = TableColumn<EquipmentModelData, String>("Фото")
        colModelPhoto.cellValueFactory = PropertyValueFactory("photo")
        colModelPhoto.prefWidth = 260.0

        modelsTable.columns.addAll(colModelName, colModelPhoto)
        val modelItems = FXCollections.observableArrayList<EquipmentModelData>()
        modelsTable.items = modelItems

        fun refreshModels(typeKey: String?) {
            modelItems.clear()
            if (typeKey == null) {
                modelsLabel.text = "🔧 Типы изделий: выберите категорию в таблице выше"
                return
            }
            val typeName = EquipmentTypes.getTypeName(typeKey)
            modelsLabel.text = "🔧 Типы изделий для «$typeName» (${EquipmentModels.forType(typeKey).size})"
            modelItems.addAll(EquipmentModels.forType(typeKey))
        }

        tableView.selectionModel.selectedItemProperty().addListener { _, _, sel ->
            refreshModels(sel?.key)
        }

        val modelButtons = HBox(10.0)
        modelButtons.alignment = Pos.CENTER_RIGHT

        val addModelBtn = Button("➕ Тип изделия")
        addModelBtn.style = "-fx-background-color: #17a2b8; -fx-text-fill: white; -fx-padding: 6px 14px; -fx-background-radius: 4px;"
        addModelBtn.setOnAction {
            val category = tableView.selectionModel.selectedItem
            if (category == null) {
                showInfo("⚠️ Сначала выберите категорию в таблице выше")
                return@setOnAction
            }
            val input = TextInputDialog()
            input.title = "Новый тип изделия"
            input.headerText = "Категория: ${category.displayName}"
            input.contentText = "Название типа изделия (модель):"
            input.editor.promptText = "Например, ВВБК-220"
            val res = input.showAndWait()
            if (res.isPresent && res.get().isNotBlank()) {
                val model = EquipmentModels.ensureModel(database, category.key, res.get())
                refreshModels(category.key)
                showToast("✅ Тип изделия добавлен: ${model?.name}")
            }
        }

        val photoBtn = Button("🖼️ Назначить фото")
        photoBtn.style = "-fx-background-color: #007bff; -fx-text-fill: white; -fx-padding: 6px 14px; -fx-background-radius: 4px;"
        photoBtn.setOnAction {
            val model = modelsTable.selectionModel.selectedItem
            if (model == null) {
                showInfo("⚠️ Выберите тип изделия")
                return@setOnAction
            }
            val chooser = FileChooser()
            chooser.title = "Фото для «${model.name}»"
            chooser.extensionFilters.addAll(
                FileChooser.ExtensionFilter("Изображения", "*.jpg", "*.jpeg", "*.png", "*.jfif"),
                FileChooser.ExtensionFilter("Все файлы", "*.*")
            )
            AppPaths.imagesDir.mkdirs()
            chooser.initialDirectory = AppPaths.imagesDir
            val file = chooser.showOpenDialog(dialog) ?: return@setOnAction
            try {
                val safeName = file.name.replace(Regex("[^\\p{L}\\p{N}._() -]"), "_")
                val target = File(AppPaths.imagesDir, safeName)
                if (file.absolutePath != target.absolutePath) file.copyTo(target, overwrite = true)
                val updated = model.copy(photo = safeName)
                database.saveModel(updated)
                EquipmentModels.addLocal(updated)
                refreshModels(model.typeKey)
                showToast("✅ Фото назначено: $safeName")
            } catch (t: Throwable) {
                showError("Не удалось скопировать файл:\n${t.message}")
            }
        }

        val clearPhotoBtn = Button("🚫 Без фото")
        clearPhotoBtn.style = "-fx-background-color: #6c757d; -fx-text-fill: white; -fx-padding: 6px 14px; -fx-background-radius: 4px;"
        clearPhotoBtn.setOnAction {
            val model = modelsTable.selectionModel.selectedItem
            if (model == null) {
                showInfo("⚠️ Выберите тип изделия")
                return@setOnAction
            }
            val updated = model.copy(photo = null)
            database.saveModel(updated)
            EquipmentModels.addLocal(updated)
            refreshModels(model.typeKey)
            showToast("Фото убрано у «${model.name}»")
        }

        val deleteModelBtn = Button("🗑️ Удалить тип изделия")
        deleteModelBtn.style = "-fx-background-color: #dc3545; -fx-text-fill: white; -fx-padding: 6px 14px; -fx-background-radius: 4px;"
        deleteModelBtn.setOnAction {
            val model = modelsTable.selectionModel.selectedItem
            if (model == null) {
                showInfo("⚠️ Выберите тип изделия")
                return@setOnAction
            }
            val used = database.countEquipmentByModel(model.id)
            if (used > 0) {
                showInfo("⚠️ Тип «${model.name}» используется оборудованием ($used шт.).\nСначала смените тип у этих единиц.")
                return@setOnAction
            }
            val confirm = Alert(AlertType.CONFIRMATION)
            confirm.title = "Удаление типа изделия"
            confirm.headerText = "Удалить «${model.name}»?"
            val res = confirm.showAndWait()
            if (res.isPresent && res.get() == ButtonType.OK) {
                database.deleteModel(model.id)
                EquipmentModels.removeLocal(model.id)
                refreshModels(model.typeKey)
                showToast("🗑️ Тип изделия удалён: ${model.name}")
            }
        }

        modelButtons.children.addAll(addModelBtn, photoBtn, clearPhotoBtn, deleteModelBtn)

        val infoLabel = Label("💡 Типы изделий назначаются оборудованию в карточке/диалоге типа. Фото берётся по типу изделия, иначе — по категории, иначе заглушка.")
        infoLabel.style = "-fx-text-fill: #6c757d; -fx-font-size: 12px; -fx-wrap-text: true;"

        root.children.addAll(headerLabel, tableView, buttonPanel, modelsLabel, modelsTable, modelButtons, infoLabel)

        val scene = Scene(root, 760.0, 700.0)
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
                val file = File(AppPaths.imagesDir, fileName)
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

    private fun applyMarkerScale(newScale: Double) {
        val clamped = newScale.coerceIn(0.5, 4.0)
        markerScale = clamped

        webView.engine.executeScript("""
        document.documentElement.style.setProperty('--marker-scale', '${clamped}');
    """.trimIndent())

        database.setMarkerScale(clamped)

        val percent = (clamped * 100).toInt()
        showToast("🔍 Масштаб маркеров: ${"%.1f".format(clamped)}x ($percent%)")
        println("🔍 Масштаб маркеров: $clamped (ПС: ${currentSubstation.displayName})")
    }

    private fun loadLastSubstation(): Substation {
        val savedKey = AppSettings.getLastSubstationKey()
        return Substations.ALL.find { it.key == savedKey } ?: Substations.DEFAULT
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
