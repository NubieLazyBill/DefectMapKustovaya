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
import javafx.util.Duration


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

    private var markersVisible = false

    private var isDraggingMarker = false

    private var currentEditingEquipmentId: String? = null  // Для добавления маркеров


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

    private val database: Database by lazy { Database() }

    private val gson: Gson by lazy {
        GsonBuilder().setPrettyPrinting().create()
    }

    @FXML
    private fun initialize() {
        loadSvgIntoWebView()
        checkAndImportData()

        // Устанавливаем правильный текст для меню при запуске
        toggleMarkersMenuItem.text = if (markersVisible) "👁️ Скрыть маркеры" else "👁️ Показать маркеры"

        webView.engine.getLoadWorker().stateProperty().addListener { _, _, newState ->
            if (newState == Worker.State.SUCCEEDED) {
                Platform.runLater {
                    setupZoom()
                    setupClickHandler()
                    setupButtons()
                    initEquipment()
                }
            }
        }
        // ===== ДОБАВЛЯЕМ ОБРАБОТЧИК ЗАКРЫТИЯ =====
        Platform.runLater {
            val stage = webView.scene?.window as? Stage
            stage?.setOnCloseRequest {
                println("🔄 Приложение закрывается, синхронизируем данные...")
                saveEquipment()
                database.exportAllToJson()
                println("✅ Данные синхронизированы перед закрытием")
            }
        }
    }

    // ======================== РЕЖИМ РЕДАКТИРОВАНИЯ (из меню) ========================

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

        // Текст показывает ДЕЙСТВИЕ при следующем нажатии
        toggleMarkersMenuItem.text = if (markersVisible) "👁️ Скрыть маркеры" else "👁️ Показать маркеры"
    }

    // ======================== ЗАГРУЗКА SVG ========================

    private fun loadSvgIntoWebView() {
        val svgFile = javaClass.getResource("/org/example/defectmap/schema.svg")
        if (svgFile != null) {
            val html = """
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
              }
              #image {
                display: block;
                max-width: 100%;
                max-height: 100%;
                object-fit: contain;
                transform-origin: center center;
                will-change: transform;
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
              .equipment-marker:active {
                cursor: grabbing;
              }
              /* Скрытый маркер — убираем всё визуальное, но оставляем область для наведения */
              .equipment-marker.hidden .dot {
                  opacity: 0 !important;
                  pointer-events: none;
              }
              .equipment-marker.hidden .tooltip-text {
                  opacity: 0 !important;
                  visibility: hidden !important;
              }
              /* При наведении на скрытый маркер — показываем тултип */
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
              /* Размеры меток */
              .equipment-marker.small {
                  width: 20px;
                  height: 20px;
              }
              .equipment-marker.small .dot {
                  width: 16px;
                  height: 16px;
                  font-size: 8px;
              }
              .equipment-marker.normal {
                  width: 28px;
                  height: 28px;
              }
              .equipment-marker.normal .dot {
                  width: 24px;
                  height: 24px;
                  font-size: 11px;
              }
              .equipment-marker.large {
                  width: 36px;
                  height: 36px;
              }
              .equipment-marker.large .dot {
                  width: 32px;
                  height: 32px;
                  font-size: 14px;
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
            </style>
          </head>
          <body>
            <div id="container">
              <div id="image-wrapper">
                <img id="image" src="${svgFile.toExternalForm()}" alt="Schema" />
                <div id="equipment-container"></div>
              </div>
            </div>
          </body>
        </html>
    """.trimIndent()
            webView.engine.loadContent(html)
        } else {
            println("SVG file not found!")
        }
    }

    private fun checkAndImportData() {
        val dbFile = File(System.getProperty("user.home"), ".defectmap/equipment.db")
        val exportFile = File("equipment_export.json")

        if (!dbFile.exists() || database.getCount() == 0) {
            if (exportFile.exists()) {
                println("📥 БД пуста, импортируем из JSON")
                importData()
            }
            return
        }

        if (!exportFile.exists()) {
            println("📤 JSON не найден, экспортируем БД")
            database.exportAllToJson()
            return
        }

        val dbData = database.loadAllEquipment()
        val jsonData = database.importFromJson()

        if (jsonData == null || jsonData.isEmpty()) {
            println("📤 JSON пуст, экспортируем БД")
            database.exportAllToJson()
            return
        }

        // Проверяем изменения с выводом конкретных расхождений
        val added = jsonData.filter { new -> dbData.none { it.id == new.id } }
        val removed = dbData.filter { old -> jsonData.none { it.id == old.id } }
        val changed = jsonData.filter { new ->
            dbData.find { it.id == new.id }?.let { old ->
                val nameChanged = old.name != new.name
                val typeChanged = old.type != new.type
                val letterChanged = old.letter != new.letter
                val cellChanged = old.cell != new.cell
                val sizeChanged = old.size != new.size
                val leftChanged = Math.abs(old.left - new.left) > 0.01
                val topChanged = Math.abs(old.top - new.top) > 0.01

                if (nameChanged || typeChanged || letterChanged || cellChanged || sizeChanged || leftChanged || topChanged) {
                    println("🔄 Изменение в ${old.name}:")
                    if (leftChanged) println("   left: ${old.left} -> ${new.left}")
                    if (topChanged) println("   top: ${old.top} -> ${new.top}")
                    if (nameChanged) println("   name: ${old.name} -> ${new.name}")
                    if (typeChanged) println("   type: ${old.type} -> ${new.type}")
                    if (letterChanged) println("   letter: ${old.letter} -> ${new.letter}")
                    if (cellChanged) println("   cell: ${old.cell} -> ${new.cell}")
                    if (sizeChanged) println("   size: ${old.size} -> ${new.size}")
                }
                nameChanged || typeChanged || letterChanged || cellChanged || sizeChanged || leftChanged || topChanged
            } ?: false
        }

        if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) {
            println("✅ Данные синхронизированы (содержание совпадает)")
            return
        }

        println("📊 Найдены расхождения между БД и JSON")
        println("  Добавлено: ${added.size}")
        println("  Удалено: ${removed.size}")
        println("  Изменено: ${changed.size}")

        if (changed.isNotEmpty()) {
            println("  Первые 5 изменений:")
            changed.take(5).forEach { eq ->
                val old = dbData.find { it.id == eq.id }
                if (old != null) {
                    println("    - ${eq.name}: left ${old.left}->${eq.left}, top ${old.top}->${eq.top}")
                }
            }
        }

        // Показываем диалог
        Platform.runLater {
            val message = buildString {
                append("📊 Обнаружены расхождения между БД и JSON:\n\n")
                if (added.isNotEmpty()) append("➕ В JSON добавлено: ${added.size}\n")
                if (removed.isNotEmpty()) append("➖ В JSON удалено: ${removed.size}\n")
                if (changed.isNotEmpty()) append("🔄 Изменено: ${changed.size}\n")
                if (changed.isNotEmpty() && changed.size <= 10) {
                    append("\nИзменения:\n")
                    changed.forEach { eq ->
                        val old = dbData.find { it.id == eq.id }
                        if (old != null) {
                            append("  - ${eq.name}: (${old.left},${old.top}) → (${eq.left},${eq.top})\n")
                        }
                    }
                }
                append("\nЧто делаем?")
            }

            val alert = Alert(AlertType.CONFIRMATION)
            alert.title = "Синхронизация данных"
            alert.headerText = "📊 Обнаружены расхождения"
            alert.contentText = message

            val importBtn = ButtonType("📥 Импорт из JSON")
            val exportBtn = ButtonType("📤 Экспорт в JSON")
            val cancelBtn = ButtonType("Отмена", ButtonBar.ButtonData.CANCEL_CLOSE)

            alert.buttonTypes.setAll(importBtn, exportBtn, cancelBtn)

            val result = alert.showAndWait()
            when (result.orElse(null)) {
                importBtn -> {
                    println("📥 Импортируем из JSON")
                    importData()
                    showToast("✅ Импортировано из JSON")
                }
                exportBtn -> {
                    println("📤 Экспортируем БД в JSON")
                    database.exportAllToJson()
                    showToast("✅ БД экспортирована в JSON")
                }
                else -> println("❌ Синхронизация отменена")
            }
        }
    }


    private fun importData() {
        val imported = database.importFromJson()
        if (imported != null && imported.isNotEmpty()) {
            database.saveEquipment(imported)
            Platform.runLater {
                val alert = Alert(AlertType.INFORMATION)
                alert.title = "Импорт завершен"
                alert.headerText = "✅ Данные успешно импортированы"
                alert.contentText = """
                Импортировано ${imported.size} записей.
                
                📊 Статистика:
                - small: ${imported.count { it.size == "small" }}
                - normal: ${imported.count { it.size == "normal" }}
                - large: ${imported.count { it.size == "large" }}
            """.trimIndent()
                alert.showAndWait()

                // Обновляем отображение
                initEquipment()
            }
        } else {
            Platform.runLater {
                Alert(AlertType.WARNING).apply {
                    title = "Импорт данных"
                    headerText = "⚠️ Данные для импорта не найдены"
                    contentText = "Файл экспорта пуст или отсутствует."
                    showAndWait()
                }
            }
        }
    }

    private fun showExportNotification(count: Int, message: String = "") {
        val text = buildString {
            append("💾 Экспортировано $count записей")
            if (message.isNotEmpty()) append("\n$message")
            append("\nФайл: ~/.defectmap/equipment_export.json")
        }
        showToast(text, javafx.util.Duration.seconds(2.5))
    }

    private fun showStatistics() {
        val stats = database.getStatistics()
        val total = database.getCount()

        val sb = StringBuilder()
        sb.append("📊 СТАТИСТИКА ОБОРУДОВАНИЯ\n")
        sb.append("=".repeat(40) + "\n")
        sb.append("Всего: $total шт.\n\n")

        // stats уже содержит typeName -> count
        stats.forEach { (typeName, count) ->
            sb.append("  $typeName: $count шт.\n")
        }

        showInfo(sb.toString())
    }

    // ======================== ИНИЦИАЛИЗАЦИЯ ========================

    private fun initEquipment() {
        val savedEquipment = loadEquipment()
        println("📂 Загружено из файла: ${savedEquipment.size} шт.")

        if (savedEquipment.isNotEmpty()) {
            val equipmentJson = gson.toJson(savedEquipment)
            webView.engine.executeScript("""
        (function() {
            window.equipment = [];
            var savedData = $equipmentJson;
            var container = document.getElementById('equipment-container');
            if (!container) {
                var wrapper = document.getElementById('image-wrapper');
                if (wrapper) {
                    container = document.createElement('div');
                    container.id = 'equipment-container';
                    wrapper.appendChild(container);
                }
            }
            if (container) container.innerHTML = '';
            if (!container) return;
            
            savedData.forEach(function(item) {
                // Если markers пустой массив или отсутствует — создаём из left/top
                var markers = item.markers;
                if (!markers || markers.length === 0) {
                    markers = [{left: item.left, top: item.top, isMain: true}];
                }
                
                markers.forEach(function(markerPos, index) {
                    var marker = document.createElement('div');
                    var sizeClass = item.size || 'normal';
                    marker.className = 'equipment-marker ' + item.type + ' ' + sizeClass;
                    if (index > 0) marker.className += ' marker-extra';
                    // Добавляем класс hidden, если маркеры скрыты
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
                
                window.equipment.push({
                    id: item.id,
                    left: item.left,
                    top: item.top,
                    type: item.type,
                    name: item.name,
                    letter: item.letter,
                    cell: item.cell || '',
                    size: item.size || 'normal',
                    markers: markers
                });
            });
            console.log('✅ Загружено: ' + savedData.length + ' единиц оборудования');
            console.log('📊 Всего маркеров: ' + window.equipment.reduce(function(sum, eq) {
                return sum + (eq.markers ? eq.markers.length : 1);
            }, 0));
        })();
        """.trimIndent())
            equipmentCounter = savedEquipment.size
        } else {
            webView.engine.executeScript("""
        (function() {
            window.equipment = [];
        })();
        """.trimIndent())
        }
    }
    // ======================== КНОПКИ ========================

    @FXML
    private fun onForceImport() {
        println("=".repeat(60))
        println("📥 ПРИНУДИТЕЛЬНЫЙ ИМПОРТ")
        println("=".repeat(60))

        try {
            // Проверяем, есть ли JSON файл
            val exportFile = File(System.getProperty("user.home"), ".defectmap/equipment_export.json")
            if (!exportFile.exists()) {
                showError("⚠️ Файл экспорта не найден:\n${exportFile.absolutePath}")
                return
            }

            // Загружаем данные из JSON
            val imported = database.importFromJson()

            if (imported == null || imported.isEmpty()) {
                showError("⚠️ Данные для импорта не найдены или пустые")
                return
            }

            // Текущие данные в БД
            val currentData = database.loadAllEquipment()

            // Анализируем изменения
            val added = imported.filter { new -> currentData.none { it.id == new.id } }
            val changed = imported.filter { new ->
                currentData.find { it.id == new.id }?.let { old ->
                    old.name != new.name ||
                            old.type != new.type ||
                            old.letter != new.letter ||
                            old.cell != new.cell ||
                            old.size != new.size ||
                            old.left != new.left ||
                            old.top != new.top
                } ?: false
            }
            val removed = currentData.filter { old -> imported.none { it.id == old.id } }

            // Формируем сообщение
            val message = buildString {
                append("📊 Найдено ${imported.size} записей в JSON\n")
                append("📂 В БД: ${currentData.size} записей\n\n")
                if (added.isNotEmpty()) append("✅ Добавлено: ${added.size}\n")
                if (changed.isNotEmpty()) append("🔄 Изменено: ${changed.size}\n")
                if (removed.isNotEmpty()) append("❌ Удалено: ${removed.size}\n")
                if (added.isEmpty() && changed.isEmpty() && removed.isEmpty()) {
                    append("⚠️ Изменений нет, данные уже синхронизированы")
                }
            }

            // Показываем диалог подтверждения
            val confirm = Alert(AlertType.CONFIRMATION)
            confirm.title = "Принудительный импорт"
            confirm.headerText = "📥 Импорт данных из JSON"
            confirm.contentText = message + "\n\nПродолжить импорт?"

            val result = confirm.showAndWait()
            if (result.isPresent && result.get() == ButtonType.OK) {
                // Сохраняем в БД
                database.saveEquipment(imported)
                println("✅ Импортировано ${imported.size} записей в БД")

                // Показываем уведомление
                Platform.runLater {
                    Alert(AlertType.INFORMATION).apply {
                        title = "Импорт завершен"
                        headerText = "✅ Данные успешно импортированы"
                        contentText = """
                        Импортировано ${imported.size} записей.
                        
                        📊 Статистика:
                        - small:  ${imported.count { it.size == "small" }}
                        - normal: ${imported.count { it.size == "normal" }}
                        - large:  ${imported.count { it.size == "large" }}
                    """.trimIndent()
                        showAndWait()
                    }
                }

                // Перезагружаем метки
                initEquipment()
            }

        } catch (e: Exception) {
            showError("Ошибка импорта: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun setupButtons() {
        viewEquipmentBtn.setOnAction { viewEquipmentList() }
        defectsBtn.setOnAction { showDefectsList() }
        // Режим редактирования теперь через меню
    }

    private fun toggleEditMode(enable: Boolean) {
        isEditMode = enable

        // Если выходим из режима редактирования — сбрасываем ID
        if (!enable) {
            currentEditingEquipmentId = null
            editModeMenuItem.text = "✏️ Режим редактирования"
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

        editModeMenuItem.text = if (enable) "🔒 Выйти из редактирования" else "✏️ Режим редактирования"
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

    private fun setupPan() {
        // Все обработчики теперь в setupClickHandler()
        // Эта функция остаётся пустой или удаляем её вызов из initialize()
        // Но оставляем для обратной совместимости
    }

    // ======================== СПИСОК ДЕФЕКТОВ ========================

    private fun showDefectsList() {
        // Если окно уже открыто — закрываем его
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

        // Фильтры
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

        // Колонки
        val colEquipment = TableColumn<DefectViewItem, String>("Оборудование")
        colEquipment.cellValueFactory = PropertyValueFactory("equipmentName")
        colEquipment.prefWidth = 200.0

        val colCell = TableColumn<DefectViewItem, String>("Ячейка")
        colCell.cellValueFactory = PropertyValueFactory("cell")
        colCell.prefWidth = 80.0
        colCell.style = "-fx-alignment: CENTER;"

        val colDefect = TableColumn<DefectViewItem, String>("Дефект")
        colDefect.cellValueFactory = PropertyValueFactory("defectName")
        colDefect.prefWidth = 200.0

        val colDescription = TableColumn<DefectViewItem, String>("Описание")
        colDescription.cellValueFactory = PropertyValueFactory("description")
        colDescription.prefWidth = 200.0

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

        // Двойной клик — открыть карточку оборудования
        tableView.setOnMouseClicked { event ->
            if (event.clickCount == 2) {
                val selected = tableView.selectionModel.selectedItem
                if (selected != null) {
                    val equipment = loadEquipment().find { it.id == selected.equipmentId }
                    if (equipment != null) {
                        val cardController = EquipmentCardController(equipment, database) {
                            showDefectsList()
                        }
                        cardController.show()
                        (tableView.scene.window as Stage).close()
                    }
                }
            }
        }

        val closeBtn = Button("✕ Закрыть")
        closeBtn.style = "-fx-background-color: #dc3545; -fx-text-fill: white; -fx-padding: 8px 20px; -fx-background-radius: 6px;"
        closeBtn.setOnAction { (closeBtn.scene.window as Stage).close() }

        val bottomPanel = HBox(closeBtn)
        bottomPanel.alignment = Pos.CENTER_RIGHT

        mainLayout.children.addAll(headerLabel, filterPanel, tableView, bottomPanel)

        val popupStage = Stage()
        popupStage.title = "📊 Список дефектов"
        popupStage.scene = Scene(mainLayout, 920.0, 650.0)
        popupStage.isResizable = true

        // Сохраняем ссылку на окно
        defectsListStage = popupStage
        popupStage.setOnHidden {
            defectsListStage = null
        }

        popupStage.showAndWait()
    }

    // ======================== КЛИКИ ========================

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
                    webView.engine.executeScript("""
                    document.getElementById('container').classList.add('dragging');
                """.trimIndent())
                }
            } else {
                if (event.isPrimaryButtonDown) {
                    val markerId = webView.engine.executeScript("""
                    (function() {
                        var container = document.getElementById('container');
                        var rect = container.getBoundingClientRect();
                        var markers = document.querySelectorAll('.equipment-marker');
                        var clickX = ${event.x};
                        var clickY = ${event.y};
                        for (var i = 0; i < markers.length; i++) {
                            var marker = markers[i];
                            var markerRect = marker.getBoundingClientRect();
                            if (clickX >= markerRect.left - rect.left - 15 &&
                                clickX <= markerRect.right - rect.left + 15 &&
                                clickY >= markerRect.top - rect.top - 15 &&
                                clickY <= markerRect.bottom - rect.top + 15) {
                                return marker.id;
                            }
                        }
                        return null;
                    })();
                """.trimIndent()) as? String

                    if (markerId != null) {
                        println("🖱️ НАЖАТИЕ НА МАРКЕР: $markerId")
                        isDraggingMarker = true
                        webView.engine.executeScript("""
                        window.draggingMarkerId = '$markerId';
                        window.dragStartX = ${event.x};
                        window.dragStartY = ${event.y};
                        var marker = document.getElementById('$markerId');
                        if (marker) {
                            // Сохраняем ТЕКУЩУЮ позицию в процентах
                            var leftStr = marker.style.left;
                            var topStr = marker.style.top;
                            // Убираем '%' и парсим как число
                            window.dragOrigLeftPercent = parseFloat(leftStr);
                            window.dragOrigTopPercent = parseFloat(topStr);
                            
                            // Если не получилось - пробуем через getComputedStyle
                            if (isNaN(window.dragOrigLeftPercent) || isNaN(window.dragOrigTopPercent)) {
                                var computed = window.getComputedStyle(marker);
                                window.dragOrigLeftPercent = parseFloat(computed.left);
                                window.dragOrigTopPercent = parseFloat(computed.top);
                            }
                            
                            // Если всё ещё NaN - пробуем через bounding rect
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
//  ОБРАБОТЧИК ДВИЖЕНИЯ МЫШИ (ИСПРАВЛЕННЫЙ)
// ============================================================
        webView.setOnMouseDragged { event: MouseEvent ->
            if (!isEditMode) {
                // === ОБЫЧНЫЙ РЕЖИМ: панорамирование ===
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
                // === РЕЖИМ РЕДАКТИРОВАНИЯ: перетаскивание маркера ===
                if (isDraggingMarker) {
                    webView.engine.executeScript("""
                (function() {
                    var marker = document.getElementById(window.draggingMarkerId);
                    if (!marker) return;
                    
                    // Получаем размеры wrapper
                    var wrapper = document.getElementById('image-wrapper');
                    var wrapperRect = wrapper.getBoundingClientRect();
                    
                    // Вычисляем дельту в пикселях
                    var deltaX = ${event.x} - window.dragStartX;
                    var deltaY = ${event.y} - window.dragStartY;
                    
                    // Переводим дельту в проценты
                    var deltaPercentX = (deltaX / wrapperRect.width) * 100;
                    var deltaPercentY = (deltaY / wrapperRect.height) * 100;
                    
                    // Новая позиция в процентах (БЕЗ ОГРАНИЧЕНИЙ!)
                    var newLeftPercent = window.dragOrigLeftPercent + deltaPercentX;
                    var newTopPercent = window.dragOrigTopPercent + deltaPercentY;
                    
                    // Применяем (без ограничений, чтобы можно было двигать за пределы)
                    marker.style.left = newLeftPercent + '%';
                    marker.style.top = newTopPercent + '%';
                    
                    // Обновляем сохранённую позицию
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
//  ОБРАБОТЧИК ОТПУСКАНИЯ МЫШИ (ИСПРАВЛЕННЫЙ)
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
                println("🔄 ОТПУСКАНИЕ: isDraggingMarker=$isDraggingMarker")
                if (isDraggingMarker) {
                    // Получаем ID маркера
                    val markerId = webView.engine.executeScript("""
                (function() {
                    return window.draggingMarkerId || null;
                })();
            """.trimIndent()) as? String

                    println("🔍 markerId: $markerId")

                    if (markerId == null) {
                        println("❌ window.draggingMarkerId = null")
                        isDraggingMarker = false
                        return@setOnMouseReleased
                    }

                    // Получаем данные маркера через JSON (БЕЗ ОГРАНИЧЕНИЙ!)
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
                    
                    // Получаем позицию маркера
                    var rect = marker.getBoundingClientRect();
                    var wrapper = document.getElementById('image-wrapper');
                    var wrapperRect = wrapper.getBoundingClientRect();
                    
                    // Центр маркера относительно wrapper в пикселях
                    var leftPx = rect.left - wrapperRect.left + rect.width / 2;
                    var topPx = rect.top - wrapperRect.top + rect.height / 2;
                    
                    // Вычисляем проценты (БЕЗ ОГРАНИЧЕНИЙ!)
                    var leftPercent = (leftPx / wrapperRect.width) * 100;
                    var topPercent = (topPx / wrapperRect.height) * 100;
                    
                    // НЕ ОГРАНИЧИВАЕМ значения!
                    // leftPercent = Math.max(0, Math.min(100, leftPercent));
                    // topPercent = Math.max(0, Math.min(100, topPercent));
                    
                    var data = {
                        equipmentId: equipmentId,
                        leftPercent: leftPercent,
                        topPercent: topPercent,
                        leftPx: leftPx,
                        topPx: topPx,
                        wrapperWidth: wrapperRect.width,
                        wrapperHeight: wrapperRect.height
                    };
                    
                    return JSON.stringify(data);
                })();
            """.trimIndent()) as? String

                    println("📊 JSON результат: $jsonResult")

                    if (jsonResult != null && jsonResult != "null" && !jsonResult.contains("error")) {
                        try {
                            val gson = Gson()
                            val type = object : TypeToken<Map<String, Any>>() {}.type
                            val data: Map<String, Any> = gson.fromJson(jsonResult, type)

                            val equipmentId = data["equipmentId"] as? String ?: ""
                            val leftPercent = (data["leftPercent"] as? Double) ?: 0.0
                            val topPercent = (data["topPercent"] as? Double) ?: 0.0
                            val leftPx = (data["leftPx"] as? Double) ?: 0.0
                            val topPx = (data["topPx"] as? Double) ?: 0.0
                            val wrapperWidth = (data["wrapperWidth"] as? Double) ?: 1.0
                            val wrapperHeight = (data["wrapperHeight"] as? Double) ?: 1.0

                            println("📊 ПАРСИНГ УСПЕШЕН:")
                            println("  equipmentId: $equipmentId")
                            println("  leftPx: $leftPx, topPx: $topPx")
                            println("  wrapperWidth: $wrapperWidth, wrapperHeight: $wrapperHeight")
                            println("  leftPercent: $leftPercent%, topPercent: $topPercent%")

                            if (equipmentId.isNotEmpty()) {
                                saveMarkerPosition(equipmentId, markerId, leftPercent, topPercent)
                                showToast("✅ Маркер перемещён")
                            } else {
                                println("❌ Неверные данные: equipmentId=$equipmentId")
                                showToast("⚠️ Ошибка при перетаскивании маркера")
                            }
                        } catch (e: Exception) {
                            println("❌ Ошибка парсинга JSON: ${e.message}")
                            e.printStackTrace()
                            showToast("⚠️ Ошибка при перетаскивании маркера")
                        }
                    } else {
                        println("❌ Невалидный JSON: $jsonResult")
                        showToast("⚠️ Ошибка при перетаскивании маркера")
                    }

                    // Очищаем состояние
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

        // ============================================================
        //  КЛИК (добавление оборудования в режиме редактирования)
        // ============================================================
        webView.setOnMouseClicked { event: MouseEvent ->
            if (!isEditMode) {
                // === ОБЫЧНЫЙ РЕЖИМ: показываем карточку оборудования ===
                if (event.clickCount == 1 && event.button == javafx.scene.input.MouseButton.PRIMARY) {
                    handleEquipmentClick(event.x, event.y)
                }
                // Двойной клик для сброса зума
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
                // === РЕЖИМ РЕДАКТИРОВАНИЯ: добавляем оборудование ===
                if (event.clickCount == 1 && event.button == javafx.scene.input.MouseButton.PRIMARY) {
                    // Проверяем, не кликнули ли по маркеру
                    val isMarker = webView.engine.executeScript("""
                    (function() {
                        var container = document.getElementById('container');
                        var rect = container.getBoundingClientRect();
                        var markers = document.querySelectorAll('.equipment-marker');
                        var clickX = ${event.x};
                        var clickY = ${event.y};
                        for (var i = 0; i < markers.length; i++) {
                            var marker = markers[i];
                            var markerRect = marker.getBoundingClientRect();
                            if (clickX >= markerRect.left - rect.left - 15 &&
                                clickX <= markerRect.right - rect.left + 15 &&
                                clickY >= markerRect.top - rect.top - 15 &&
                                clickY <= markerRect.bottom - rect.top + 15) {
                                return true;
                            }
                        }
                        return false;
                    })();
                """.trimIndent()) as? Boolean ?: false

                    if (!isMarker) {
                        println("🖱️ Клик в режиме редактирования!")
                        addEquipmentAtPosition(event.x, event.y)
                    }
                }
            }
        }

        // ============================================================
        //  КОНТЕКСТНОЕ МЕНЮ (только в режиме редактирования)
        // ============================================================
        webView.setOnContextMenuRequested { event ->
            if (isEditMode) {
                val result = webView.engine.executeScript("""
                (function() {
                    var container = document.getElementById('container');
                    var rect = container.getBoundingClientRect();
                    var markers = document.querySelectorAll('.equipment-marker');
                    var clickX = ${event.x};
                    var clickY = ${event.y};
                    for (var i = 0; i < markers.length; i++) {
                        var marker = markers[i];
                        var markerRect = marker.getBoundingClientRect();
                        if (clickX >= markerRect.left - rect.left - 15 &&
                            clickX <= markerRect.right - rect.left + 15 &&
                            clickY >= markerRect.top - rect.top - 15 &&
                            clickY <= markerRect.bottom - rect.top + 15) {
                            return marker.id;
                        }
                    }
                    return null;
                })();
            """.trimIndent()) as? String

                if (result != null) {
                    showContextMenu(event.x, event.y, result)
                }
            }
        }

        // ============================================================
        //  ВЫХОД МЫШИ ЗА ПРЕДЕЛЫ
        // ============================================================
        webView.setOnMouseExited {
            if (!isEditMode && isDragging) {
                isDragging = false
                webView.engine.executeScript("""
                document.getElementById('container').classList.remove('dragging');
            """.trimIndent())
            }
        }
    }

    // В saveMarkerPosition уберите ограничение или сделайте его более широким:
    private fun saveMarkerPosition(equipmentId: String, markerId: String, newLeftPercent: Double, newTopPercent: Double) {
        println("=".repeat(60))
        println("💾 saveMarkerPosition вызван")
        println("  equipmentId: $equipmentId")
        println("  markerId: $markerId")
        println("  newLeftPercent: $newLeftPercent%, newTopPercent: $newTopPercent%")
        println("=".repeat(60))

        if (equipmentId.isEmpty()) {
            println("❌ equipmentId пустой, пропускаем сохранение")
            return
        }

        // НЕ ОГРАНИЧИВАЕМ значения - сохраняем как есть
        val allEquipment = loadEquipment()

        // Ищем оборудование по ID
        var equipment = allEquipment.find { it.id == equipmentId }

        // Если не нашли - пробуем найти по ID маркера
        if (equipment == null) {
            val baseId = if (equipmentId.startsWith("marker-")) {
                equipmentId
            } else {
                markerId.replace(Regex("-marker-\\d+$"), "")
            }
            equipment = allEquipment.find { it.id == baseId }
            println("🔍 Ищем по baseId: $baseId, найдено: ${equipment?.name ?: "нет"}")
        }

        // Если всё ещё не нашли - ищем по части ID
        if (equipment == null) {
            val found = allEquipment.find { equipmentId.startsWith(it.id) }
            if (found != null) {
                equipment = found
                println("🔍 Найдено по части ID: ${found.id} (${found.name})")
            }
        }

        if (equipment == null) {
            println("❌ Оборудование не найдено: $equipmentId")
            showToast("⚠️ Оборудование не найдено")
            return
        }

        println("📊 Найдено оборудование: ${equipment.name} (${equipment.id})")
        println("📊 Текущие маркеры: ${equipment.markers.size}")

        // Находим индекс маркера
        var markerIndex = -1

        // Сначала ищем по ID маркера
        val idParts = markerId.split("-marker-")
        if (idParts.size > 1) {
            val indexFromId = idParts[1].toIntOrNull()
            if (indexFromId != null && indexFromId < equipment.markers.size) {
                markerIndex = indexFromId
                println("📊 Найден маркер по ID: индекс $markerIndex")
            }
        }

        // Если не нашли - ищем по координатам (с допуском)
        if (markerIndex == -1) {
            for (i in equipment.markers.indices) {
                val m = equipment.markers[i]
                if (Math.abs(m.left - newLeftPercent) < 0.5 && Math.abs(m.top - newTopPercent) < 0.5) {
                    markerIndex = i
                    println("📊 Найден маркер по координатам: индекс $markerIndex")
                    break
                }
            }
        }

        // Если не нашли - берём первый маркер
        if (markerIndex == -1 && equipment.markers.isNotEmpty()) {
            markerIndex = 0
            println("📊 Используем первый маркер (основной): индекс $markerIndex")
        }

        if (markerIndex == -1) {
            println("❌ Маркер не найден, добавляем новый")
            val newMarkers = equipment.markers + MarkerPosition(newLeftPercent, newTopPercent, isMain = false)
            val updatedEquipment = equipment.copy(markers = newMarkers)
            val updatedList = allEquipment.map { if (it.id == equipment.id) updatedEquipment else it }
            database.saveEquipment(updatedList)
            syncWindowEquipment()
            println("✅ Добавлен новый маркер для ${equipment.name}")
            showToast("✅ Маркер добавлен для ${equipment.name}")
            return
        }

        println("📊 Обновляем маркер с индексом: $markerIndex")

        // Обновляем маркер (БЕЗ ОГРАНИЧЕНИЙ)
        val updatedMarkers = equipment.markers.toMutableList()
        updatedMarkers[markerIndex] = updatedMarkers[markerIndex].copy(left = newLeftPercent, top = newTopPercent)

        val updatedEquipment = equipment.copy(markers = updatedMarkers)
        val updatedList = allEquipment.map {
            if (it.id == equipment.id) updatedEquipment else it
        }

        database.saveEquipment(updatedList)
        syncWindowEquipment()
        syncFileTimestamps()
        println("✅ Сохранено в БД для ${equipment.name}")

        // Обновляем маркер на схеме
        webView.engine.executeScript("""
        (function() {
            var marker = document.getElementById('$markerId');
            if (marker) {
                marker.style.left = '${newLeftPercent}%';
                marker.style.top = '${newTopPercent}%';
                marker.dataset.equipmentId = '${equipment.id}';
                console.log('✅ Маркер обновлён на схеме');
            }
            
            var allEquipment = ${gson.toJson(updatedList)};
            window.equipment = allEquipment;
        })();
    """.trimIndent())

        // ИСПРАВЛЕННАЯ СТРОКА - используем Kotlin format
        val formattedLeft = "%.1f".format(newLeftPercent)
        val formattedTop = "%.1f".format(newTopPercent)
        showToast("✅ Маркер ${equipment.name} перемещён на ${formattedLeft}%, ${formattedTop}%")
    }

    private fun showContextMenu(x: Double, y: Double, equipmentId: String) {
        println("🔍 showContextMenu: equipmentId = $equipmentId")
        val contextMenu = ContextMenu()

        var equipment: EquipmentData? = null
        var isExtraMarker = false
        var markerId = equipmentId

        // 1. Проверяем, есть ли data-equipment-id у маркера
        val markerInfo = webView.engine.executeScript("""
        (function() {
            var marker = document.getElementById('$equipmentId');
            if (!marker) return null;
            
            // Получаем equipmentId из data-атрибута
            var realEquipmentId = marker.dataset.equipmentId || null;
            // Если data-equipment-id нет — пробуем найти по ID маркера (отрезаем -marker-N)
            if (!realEquipmentId) {
                var parts = '$equipmentId'.split('-marker-');
                if (parts.length > 0 && parts[0].startsWith('equipment-')) {
                    realEquipmentId = parts[0];
                }
            }
            
            return {
                equipmentId: realEquipmentId,
                isExtra: marker.classList.contains('marker-extra'),
                markerId: marker.id
            };
        })();
    """.trimIndent()) as? Map<*, *>

        if (markerInfo != null) {
            val realId = markerInfo["equipmentId"] as? String
            isExtraMarker = markerInfo["isExtra"] as? Boolean ?: false
            markerId = markerInfo["markerId"] as? String ?: equipmentId

            println("🔍 realId: $realId, isExtraMarker: $isExtraMarker")

            if (realId != null) {
                equipment = loadEquipment().find { it.id == realId }
                println("🔍 Найдено оборудование по data-equipment-id: ${equipment?.name}")
            }
        }

        // 2. Если не нашли — ищем по позиции (старый способ)
        if (equipment == null) {
            val foundId = webView.engine.executeScript("""
            (function() {
                var marker = document.getElementById('$equipmentId');
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
                println("🔍 Найдено оборудование по позиции: ${equipment?.name}")
            }
        }

        // 3. Если всё ещё не нашли — пробуем отрезать суффикс от ID маркера
        if (equipment == null) {
            val baseId = equipmentId.replace(Regex("-marker-\\d+$"), "")
            if (baseId != equipmentId) {
                equipment = loadEquipment().find { it.id == baseId }
                if (equipment != null) {
                    println("🔍 Найдено оборудование по ID маркера (без суффикса): ${equipment.name}")
                }
            }
        }

        if (equipment == null) {
            showError("Оборудование не найдено. ID: $equipmentId")
            return
        }

        val freshEquipment = loadEquipment().find { it.id == equipment.id }
        val markersCount = freshEquipment?.markers?.size ?: 1

        println("🔍 Найдено оборудование: ${equipment.name}, маркеров: $markersCount, isExtraMarker: $isExtraMarker")

        // ===== ПУНКТЫ МЕНЮ =====
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

        // ===== Показываем "Удалить маркер" ТОЛЬКО для дополнительных маркеров =====
        if (isExtraMarker && markersCount > 1) {
            val deleteMarkerItem = MenuItem("🗑️ Удалить маркер")
            deleteMarkerItem.setOnAction {
                deleteMarker(equipment.id, markerId)
                contextMenu.hide()
            }
            contextMenu.items.add(deleteMarkerItem)
            println("➕ Добавлен пункт 'Удалить маркер'")
        } else {
            println("ℹ️ Пункт 'Удалить маркер' НЕ добавлен: isExtraMarker=$isExtraMarker, markersCount=$markersCount")
        }

        contextMenu.show(webView, x, y)
    }

    // ======================== ДОБАВЛЕНИЕ ========================

    private fun addEquipmentAtPosition(x: Double, y: Double) {
        println("📍 Добавление оборудования: x=$x, y=$y")

        // Если мы в режиме добавления маркера к существующему оборудованию
        if (currentEditingEquipmentId != null) {
            addMarkerToExistingEquipment(x, y)
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
                // 1. Диалог для названия
                val nameDialog = TextInputDialog()
                nameDialog.title = "Новое оборудование"
                nameDialog.headerText = "Введите диспетчерское наименование"
                nameDialog.contentText = "Наименование:"
                nameDialog.editor?.text = ""

                val nameResult = nameDialog.showAndWait()
                if (nameResult.isPresent) {
                    val name = nameResult.get().trim()
                    if (name.isNotEmpty()) {

                        // 2. Выбор ячейки
                        val cell = selectCell()
                        if (cell == null) {
                            println("❌ Выбор ячейки отменён")
                            return@runLater
                        }

                        // 3. Диалог для типа
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

                            // 4. Диалог для размера
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

                                // Создаём маркер в DOM с процентами
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

                                // Сохраняем в БД
                                saveEquipment()
                                syncWindowEquipment()

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
                showToast("✅ Маркер добавлен. Кликните ещё раз для следующего.")
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

    // ======================== ВСПЛЫВАЮЩАЯ ПОДСКАЗКА (TOAST) ========================

    private fun showToast(message: String, duration: javafx.util.Duration = javafx.util.Duration.seconds(2.5)) {
        Platform.runLater {
            try {
                // Создаём контейнер для тоста
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
                        // Удаляем старые тосты
                        root.children.filter { it is StackPane && it.isMouseTransparent && it.children.size == 1 && it.children[0] is Label }
                            .forEach { root.children.remove(it) }

                        toastContainer.children.add(toast)
                        root.children.add(toastContainer)

                        StackPane.setAlignment(toastContainer, Pos.TOP_CENTER)
                        StackPane.setMargin(toastContainer, Insets(60.0, 20.0, 0.0, 20.0))

                        // Анимация появления
                        toast.opacityProperty().set(0.0)
                        val fadeIn = javafx.animation.FadeTransition(javafx.util.Duration.millis(300.0), toast)
                        fadeIn.fromValue = 0.0
                        fadeIn.toValue = 1.0
                        fadeIn.play()

                        // Автоматическое скрытие
                        val pause = PauseTransition(duration)
                        pause.setOnFinished {
                            val fadeOut = javafx.animation.FadeTransition(javafx.util.Duration.millis(300.0), toast)
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
                // fallback - используем Alert
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

        // Загружаем оборудование
        val allEquipment = loadEquipment()
        val equipment = allEquipment.find { it.id == equipmentId }

        if (equipment == null) {
            showError("Оборудование не найдено")
            return
        }

        // Проверяем, есть ли у оборудования несколько маркеров
        if (equipment.markers.size <= 1) {
            showInfo("⚠️ Нельзя удалить единственный маркер оборудования. Используйте 'Удалить оборудование'")
            return
        }

        // Находим индекс маркера по ID (храним в data-marker-index)
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

        // Удаляем маркер из списка
        val newMarkers = equipment.markers.toMutableList()
        newMarkers.removeAt(markerIndex)

        // Если удалённый маркер был основным (isMain=true) — делаем первый маркер основным
        val updatedMarkers = newMarkers.mapIndexed { index, pos ->
            if (index == 0) pos.copy(isMain = true) else pos.copy(isMain = false)
        }

        val updatedEquipment = equipment.copy(markers = updatedMarkers)

        // Сохраняем
        val updatedList = allEquipment.map {
            if (it.id == equipmentId) updatedEquipment else it
        }
        database.saveEquipment(updatedList)
        syncWindowEquipment()

        // Удаляем маркер из DOM
        webView.engine.executeScript("""
        (function() {
            var marker = document.getElementById('$markerId');
            if (marker) marker.remove();
            console.log('🗑️ Маркер удалён');
        })();
    """.trimIndent())

        // Обновляем window.equipment
        val updatedList2 = loadEquipment()
        val equipmentJson = gson.toJson(updatedList2)
        webView.engine.executeScript("window.equipment = $equipmentJson;")

        equipmentCounter = updatedList2.size
        showInfo("🗑️ Маркер удалён")
    }

    // ======================== РЕДАКТИРОВАНИЕ ========================

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
            // 1. Диалог для названия
            val nameDialog = TextInputDialog()
            nameDialog.title = "Редактирование оборудования"
            nameDialog.headerText = "Введите новое название"
            nameDialog.contentText = "Наименование:"
            nameDialog.editor?.text = currentName

            val nameResult = nameDialog.showAndWait()
            if (nameResult.isPresent) {
                val newName = nameResult.get().trim()
                if (newName.isNotEmpty()) {

                    // 2. Выбор ячейки (ОБЩАЯ ФУНКЦИЯ!)
                    val newCell = selectCell(currentCell)
                    if (newCell == null) {
                        println("❌ Выбор ячейки отменён")
                        return@runLater
                    }

                    // 3. Диалог для типа
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

                        // 4. Диалог для размера
                        val sizeDialog = ChoiceDialog(currentSize, listOf("small", "normal", "large"))
                        sizeDialog.title = "Размер метки"
                        sizeDialog.headerText = "Выберите размер метки на схеме"
                        sizeDialog.contentText = "Размер (small - для ОРУ-220/35, large - для 500 кВ):"

                        val sizeResult = sizeDialog.showAndWait()
                        if (sizeResult.isPresent) {
                            val newSize = sizeResult.get()

                            // Обновляем список
                            val updatedList = allEquipment.map { item ->
                                if (item.id == equipmentId) {
                                    item.copy(
                                        name = newName,
                                        type = newType,
                                        letter = newLetter,
                                        cell = newCell,
                                        size = newSize
                                    )
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

                            // Экранируем строки для JavaScript
                            val escapedName = newName.replace("'", "\\'")
                            val escapedType = newType.replace("'", "\\'")
                            val escapedLetter = newLetter.replace("'", "\\'")
                            val escapedCell = newCell.replace("'", "\\'")
                            val escapedSize = newSize.replace("'", "\\'")

                            // Обновляем в DOM
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

                            // ===== ОБНОВЛЯЕМ ОКНО СПИСКА =====
                            Platform.runLater {
                                val stages = Stage.getWindows()
                                for (window in stages) {
                                    if (window is Stage && window.title == "📋 Список оборудования") {
                                        val root = window.scene?.root
                                        if (root is VBox) {
                                            val tableView = findTableView(root)
                                            if (tableView != null) {
                                                @Suppress("UNCHECKED_CAST")
                                                val table = tableView as javafx.scene.control.TableView<EquipmentTableItem>

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
                                                table.items = javafx.collections.FXCollections.observableArrayList(items)

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

// ===== ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ ДЛЯ ПОИСКА В ОКНЕ =====

    private fun findTableView(node: javafx.scene.Node): javafx.scene.control.TableView<*>? {
        if (node is javafx.scene.control.TableView<*>) {
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

    // ======================== ОБЩАЯ ФУНКЦИЯ ДЛЯ ВЫБОРА ЯЧЕЙКИ ========================

    /**
     * Открывает диалог выбора ячейки.
     * @param currentCell Текущая ячейка (для редактирования). Если пустая, будет предложено выбрать или создать.
     * @return Выбранная ячейка или null, если пользователь отменил выбор.
     */
    private fun selectCell(currentCell: String = ""): String? {
        // Загружаем все существующие ячейки из БД
        val allEquipment = loadEquipment()
        val existingCells = allEquipment
            .mapNotNull { it.cell.takeIf { cell -> cell.isNotEmpty() } }
            .distinct()
            .sorted()

        // Создаём список для выбора: существующие ячейки + "➕ Создать новую"
        val cellOptions = existingCells + "➕ Создать новую"

        // Если есть текущая ячейка и она не пустая — выбираем её по умолчанию
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
                // Если выбрано "Создать новую" — открываем диалог для ввода
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

    // Вместо deleteEquipment()
    private fun deleteEquipment(equipmentId: String) {
        // ===== ПОДТВЕРЖДЕНИЕ УДАЛЕНИЯ =====
        val confirm = Alert(AlertType.CONFIRMATION)
        confirm.title = "Удаление оборудования"
        confirm.headerText = "🗑️ Вы уверены?"
        confirm.contentText = "Вы действительно хотите удалить это оборудование и ВСЕ его маркеры?\n\nЭто действие НЕЛЬЗЯ будет отменить!"

        val result = confirm.showAndWait()
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            println("❌ Удаление отменено")
            return
        }

        // Удаляем из БД
        database.deleteById(equipmentId)
        syncWindowEquipment()

        // Удаляем все маркеры из DOM (по data-equipment-id)
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

    // ======================== СПИСОК И ЭКСПОРТ ========================

    private fun viewEquipmentList() {
        // Если окно уже открыто — закрываем его
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
                mainLayout.prefWidth = 850.0
                mainLayout.prefHeight = 650.0

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

                // Таблица
                val tableView = TableView<EquipmentTableItem>()
                tableView.style = "-fx-font-size: 13px; -fx-border-color: #dee2e6;"

                // ======================== КОЛОНКИ ТАБЛИЦЫ ========================

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

                // ======================== КОЛОНКА: ДЕЙСТВИЯ (С КНОПКАМИ) ========================

                val colActions = TableColumn<EquipmentTableItem, Void>("Действие")
                colActions.prefWidth = 120.0
                colActions.style = "-fx-alignment: CENTER;"

                colActions.setCellFactory {
                    object : TableCell<EquipmentTableItem, Void>() {
                        private val editBtn = Button("✏️")
                        private val deleteBtn = Button("🗑️")
                        private val hbox = HBox(5.0, editBtn, deleteBtn)

                        init {
                            hbox.alignment = Pos.CENTER

                            editBtn.style = "-fx-background-color: #007bff; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 2px 8px; -fx-background-radius: 4px;"
                            deleteBtn.style = "-fx-background-color: #dc3545; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 2px 8px; -fx-background-radius: 4px;"

                            editBtn.setOnAction {
                                val item = tableItem
                                if (item != null) {
                                    editEquipmentFromList(item.id)
                                }
                            }

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
                                        // Обновляем таблицу через перезагрузку данных из БД
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
                                        countLabel.text = "Показано: ${updatedData.size} из ${updatedData.size}"
                                    }
                                }
                            }

                            editBtn.hoverProperty().addListener { _, _, hovered ->
                                editBtn.style = if (hovered)
                                    "-fx-background-color: #0056b3; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 2px 8px; -fx-background-radius: 4px;"
                                else
                                    "-fx-background-color: #007bff; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 2px 8px; -fx-background-radius: 4px;"
                            }

                            deleteBtn.hoverProperty().addListener { _, _, hovered ->
                                deleteBtn.style = if (hovered)
                                    "-fx-background-color: #c82333; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 2px 8px; -fx-background-radius: 4px;"
                                else
                                    "-fx-background-color: #dc3545; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 2px 8px; -fx-background-radius: 4px;"
                            }
                        }

                        override fun updateItem(item: Void?, empty: Boolean) {
                            super.updateItem(item, empty)
                            if (empty) {
                                graphic = null
                            } else {
                                graphic = hbox
                            }
                        }

                        private val tableItem: EquipmentTableItem?
                            get() = tableRow?.item
                    }
                }

                // ======================== ДОБАВЛЯЕМ ВСЕ КОЛОНКИ ========================

                tableView.columns.addAll(
                    colNumber, colName, colType, colCell, colX, colY, colId, colActions
                )

                // ======================== ПРЕОБРАЗОВАНИЕ ДАННЫХ ========================

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

                // ======================== ФУНКЦИЯ ОБНОВЛЕНИЯ ТАБЛИЦЫ ========================

                fun updateTable(data: List<EquipmentData>) {
                    val items = toTableItems(data)
                    tableView.items = FXCollections.observableArrayList(items)
                    countLabel.text = "Показано: ${data.size} из ${allEquipment.size}"
                }

                // ======================== ФУНКЦИЯ ПРИМЕНЕНИЯ ФИЛЬТРОВ ========================

                fun applyFilter() {
                    val selectedType = typeFilter.value
                    println("🔍 Выбран тип: $selectedType")

                    val typeKey = when (selectedType) {
                        "Все типы" -> "all"
                        else -> EquipmentTypes.ALL_TYPES.find { it.second == selectedType }?.first ?: "all"
                    }
                    println("🔑 Ключ типа: $typeKey")

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

                    println("📊 Найдено: ${filtered.size} из ${allEquipment.size}")
                    updateTable(filtered)
                }

                // ======================== СЛУШАТЕЛИ ========================

                searchField.textProperty().addListener { _, _, _ ->
                    applyFilter()
                }

                cellSearchField.textProperty().addListener { _, _, _ ->
                    applyFilter()
                }

                // ======================== КНОПКИ ========================

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

                // ======================== ДВОЙНОЙ КЛИК ПО СТРОКЕ ========================

                tableView.setOnMouseClicked { event ->
                    if (event.clickCount == 2) {
                        val selected = tableView.selectionModel.selectedItem
                        if (selected != null) {
                            showEquipmentOnMap(selected.id)
                            (tableView.scene.window as Stage).close()
                        }
                    }
                }

                // ======================== СТИЛЬ СТРОК ========================

                tableView.setRowFactory {
                    val row = TableRow<EquipmentTableItem>()
                    row.hoverProperty().addListener { _, _, hovered ->
                        if (hovered) {
                            row.style = "-fx-background-color: #e8f4f8;"
                        } else {
                            row.style = "-fx-background-color: transparent;"
                        }
                    }
                    row
                }

                // ======================== КОНТЕКСТНОЕ МЕНЮ ========================

                val contextMenu = ContextMenu()
                val editMenuItem = MenuItem("✏️ Редактировать")
                val deleteMenuItem = MenuItem("🗑️ Удалить")
                val showMenuItem = MenuItem("📍 Показать на карте")

                editMenuItem.setOnAction {
                    val selected = tableView.selectionModel.selectedItem
                    if (selected != null) {
                        editEquipmentFromList(selected.id)
                    }
                }

                deleteMenuItem.setOnAction {
                    val selected = tableView.selectionModel.selectedItem
                    if (selected != null) {
                        val confirm = Alert(AlertType.CONFIRMATION)
                        confirm.title = "Удаление оборудования"
                        confirm.headerText = "Удалить оборудование?"
                        confirm.contentText = "Вы уверены, что хотите удалить '${selected.name}'?"
                        val result = confirm.showAndWait()
                        if (result.isPresent && result.get() == ButtonType.OK) {
                            deleteEquipment(selected.id)
                            val updatedData = loadEquipment()
                            val updatedItems = toTableItems(updatedData)
                            tableView.items = FXCollections.observableArrayList(updatedItems)
                            countLabel.text = "Показано: ${updatedData.size} из ${allEquipment.size}"
                        }
                    }
                }

                showMenuItem.setOnAction {
                    val selected = tableView.selectionModel.selectedItem
                    if (selected != null) {
                        showEquipmentOnMap(selected.id)
                        (tableView.scene.window as Stage).close()
                    }
                }

                contextMenu.items.addAll(editMenuItem, deleteMenuItem, showMenuItem)
                tableView.contextMenu = contextMenu

                // ======================== ПРИМЕНЯЕМ ФИЛЬТРЫ ПРИ ЗАПУСКЕ ========================

                applyFilter()

                // ======================== ПАНЕЛЬ КНОПОК ========================

                val buttonPanel = HBox(10.0)
                buttonPanel.alignment = Pos.CENTER_RIGHT
                buttonPanel.style = "-fx-padding: 10px 0 0 0;"

                val exportBtn = Button("📤 Экспорт CSV")
                exportBtn.style = "-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6px 20px; -fx-background-radius: 4px;"
                exportBtn.setOnAction { exportEquipmentToCsv() }

                val closeBtn = Button("✕ Закрыть")
                closeBtn.style = "-fx-background-color: #dc3545; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6px 20px; -fx-background-radius: 4px;"
                closeBtn.setOnAction { (closeBtn.scene.window as Stage).close() }

                // ======================== СБОРКА ОКНА ========================

                filterPanel.children.addAll(
                    filterLabel, typeFilter, applyBtn, resetBtn, countLabel,
                    searchField, cellSearchField
                )

                buttonPanel.children.addAll(exportBtn, closeBtn)
                mainLayout.children.addAll(headerLabel, filterPanel, tableView, buttonPanel)

                val popupStage = Stage()
                popupStage.title = "📋 Список оборудования"
                popupStage.scene = Scene(mainLayout, 880.0, 650.0)
                popupStage.isResizable = true
                popupStage.minWidth = 700.0
                popupStage.minHeight = 500.0

                // Сохраняем ссылку на окно
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

        val allEquipment = loadEquipment()
        val equipment = allEquipment.find { it.id == equipmentId }

        if (equipment == null) {
            showError("Оборудование не найдено. ID: $equipmentId")
            return
        }

        // Просто открываем диалог редактирования, НЕ закрывая окно списка
        editEquipment(equipmentId)
    }

    private fun getVoltageFromType(type: String): String {
        return when {
            type.contains("500") || type == "v_500" || type == "r_500" || type == "autotransformer" ||
                    type == "tn_500" || type == "tt_500" || type == "ks_500" || type == "opn_500" || type == "reactor_500" -> "500 кВ"
            type.contains("220") || type == "v_220" || type == "r_220" || type == "opn_220" ||
                    type == "tn_220" || type == "tt_220" || type == "ks_220" || type == "line_220" -> "220 кВ"
            type.contains("110") || type == "v_110" || type == "r_110" || type == "opn_110" ||
                    type == "tn_110" || type == "tt_110" -> "110 кВ"
            type.contains("35") || type == "v_35" || type == "r_35" || type == "opn_35" ||
                    type == "tn_35" || type == "tt_35" -> "35 кВ"
            type.contains("10") || type == "v_10" || type == "r_10" || type == "opn_10" ||
                    type == "tn_10" || type == "tt_10" -> "10 кВ"
            type == "lightning" || type == "lightning_rod" -> "Без напряжения"
            else -> "Без напряжения"
        }
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

    private fun exportEquipmentToCsv() {
        val result = webView.engine.executeScript("""
        JSON.stringify(window.equipment || [])
    """.trimIndent()) as? String

        if (result != null) {
            try {
                val type = object : TypeToken<List<EquipmentData>>() {}.type
                val equipment: List<EquipmentData> = gson.fromJson(result, type)
                if (equipment.isEmpty()) {
                    showInfo("Нет данных для экспорта")
                    return
                }
                val sb = StringBuilder()
                sb.append("№;Наименование;Тип;Ячейка;X%;Y%;ID\n")
                equipment.forEachIndexed { index, item ->
                    sb.append("${index + 1};${item.name};${item.type};${item.cell};${item.left};${item.top};${item.id}\n")
                }
                val csvFile = File(System.getProperty("user.home"), ".defectmap/equipment_export.csv")
                csvFile.writeText(sb.toString(), Charsets.UTF_8)
                showInfo("✅ Экспортировано ${equipment.size} единиц оборудования в файл:\n${csvFile.absolutePath}")
            } catch (e: Exception) {
                showError("Ошибка экспорта: ${e.message}")
            }
        }
    }

    // ======================== КЛИК ПО МЕТКЕ ========================

    private fun handleEquipmentClick(x: Double, y: Double) {
        val result = webView.engine.executeScript("""
        (function() {
            var container = document.getElementById('container');
            var rect = container.getBoundingClientRect();
            var markers = document.querySelectorAll('.equipment-marker');
            var clickX = $x;
            var clickY = $y;
            for (var i = 0; i < markers.length; i++) {
                var marker = markers[i];
                var markerRect = marker.getBoundingClientRect();
                if (clickX >= markerRect.left - rect.left - 15 &&
                    clickX <= markerRect.right - rect.left + 15 &&
                    clickY >= markerRect.top - rect.top - 15 &&
                    clickY <= markerRect.bottom - rect.top + 15) {
                    return marker.id;
                }
            }
            return null;
        })();
    """.trimIndent()) as? String

        if (result != null) {
            // Ищем оборудование по ID маркера
            val equipmentId = webView.engine.executeScript("""
            (function() {
                var marker = document.getElementById('$result');
                if (!marker) return null;
                return marker.dataset.equipmentId || marker.id;
            })();
        """.trimIndent()) as? String

            if (equipmentId != null) {
                val equipment = loadEquipment().find { it.id == equipmentId }
                if (equipment != null) {
                    val cardController = EquipmentCardController(equipment, database) {
                        // Callback после изменения дефектов (обновляем схему при необходимости)
                    }
                    cardController.show()
                } else {
                    showError("Оборудование не найдено")
                }
            }
        }
    }

    private fun addMarkerToEquipment(equipmentId: String) {
        println("➕ Добавление маркера для: $equipmentId")

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

                println("💾 Сохраняем ${equipment.size} записей")
                equipment.forEach {
                    println("  ${it.name}: markers=${it.markers.size}")
                }

                // Сохраняем в БД
                database.saveEquipment(equipment)

                // Принудительно синхронизируем window.equipment с БД
                syncWindowEquipment()

                // Принудительно экспортируем в JSON и обновляем время файла
                database.exportAllToJson()

                // Обновляем время модификации JSON, чтобы оно совпадало с БД
                val dbFile = File(System.getProperty("user.home"), ".defectmap/equipment.db")
                val exportFile = File("equipment_export.json")
                if (dbFile.exists() && exportFile.exists()) {
                    // Устанавливаем время JSON равным времени БД
                    exportFile.setLastModified(dbFile.lastModified())
                    println("🔄 Время JSON синхронизировано с БД")
                }

                showExportNotification(equipment.size)
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
        window.equipment = $freshJson;
        console.log('🔄 Синхронизация: загружено ' + window.equipment.length + ' записей из БД');
    """.trimIndent())
    }

    private fun syncFileTimestamps() {
        try {
            val dbFile = File(System.getProperty("user.home"), ".defectmap/equipment.db")
            val exportFile = File("equipment_export.json")
            if (dbFile.exists() && exportFile.exists()) {
                exportFile.setLastModified(dbFile.lastModified())
                println("🔄 Время JSON синхронизировано с БД")
            }
        } catch (e: Exception) {
            println("⚠️ Не удалось синхронизировать время файлов: ${e.message}")
        }
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