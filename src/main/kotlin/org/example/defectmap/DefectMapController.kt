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
                    setupPan()
                    setupClickHandler()
                    setupButtons()
                    initEquipment()
                }
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
                cursor: pointer;
                z-index: 10;
                pointer-events: auto;
                transform: translate(-50%, -50%);
                width: 28px;
                height: 28px;
                transition: all 0.2s ease;
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
        val exportFile = File(System.getProperty("user.home"), ".defectmap/equipment_export.json")

        // 1. Если БД НЕ существует или ПУСТАЯ — пробуем импортировать
        if (!dbFile.exists() || database.getCount() == 0) {
            if (exportFile.exists()) {
                Platform.runLater {
                    val alert = Alert(AlertType.CONFIRMATION)
                    alert.title = "Импорт данных"
                    alert.headerText = "📥 Найдены экспортированные данные"
                    alert.contentText = """
                    Обнаружен файл с экспортированными данными:
                    ${exportFile.absolutePath}
                    
                    База данных пуста. Хотите импортировать данные?
                """.trimIndent()

                    val result = alert.showAndWait()
                    if (result.isPresent && result.get() == ButtonType.OK) {
                        importData()
                    }
                }
            }
            return
        }

        // 2. Если БД есть — проверяем, есть ли реальные изменения
        if (exportFile.exists()) {
            val dbData = database.loadAllEquipment()
            val jsonData = database.importFromJson()

            if (jsonData == null || jsonData.isEmpty()) {
                return // JSON пустой — ничего не делаем
            }

            // Находим различия
            val added = jsonData.filter { new -> dbData.none { it.id == new.id } }
            val removed = dbData.filter { old -> jsonData.none { it.id == old.id } }
            val changed = jsonData.filter { new ->
                dbData.find { it.id == new.id }?.let { old ->
                    // Сравниваем все поля (кроме created_at)
                    old.name != new.name ||
                            old.type != new.type ||
                            old.letter != new.letter ||
                            old.cell != new.cell ||
                            old.size != new.size ||
                            old.left != new.left ||
                            old.top != new.top
                } ?: false
            }

            // Если изменений нет — ничего не делаем
            if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) {
                println("✅ Данные синхронизированы, изменений нет")
                return
            }

            // 3. Есть изменения — показываем диалог
            Platform.runLater {
                val message = buildString {
                    append("📊 Обнаружены изменения в экспортированных данных:\n\n")
                    if (added.isNotEmpty()) {
                        append("✅ Добавлено: ${added.size} записей\n")
                        added.take(5).forEach { append("   - ${it.name}\n") }
                        if (added.size > 5) append("   ... и еще ${added.size - 5}\n")
                    }
                    if (removed.isNotEmpty()) {
                        append("❌ Удалено: ${removed.size} записей\n")
                        removed.take(5).forEach { append("   - ${it.name}\n") }
                        if (removed.size > 5) append("   ... и еще ${removed.size - 5}\n")
                    }
                    if (changed.isNotEmpty()) {
                        append("🔄 Изменено: ${changed.size} записей\n")
                        changed.take(5).forEach { append("   - ${it.name}\n") }
                        if (changed.size > 5) append("   ... и еще ${changed.size - 5}\n")
                    }
                    append("\nИмпортировать изменения?")
                }

                val alert = Alert(AlertType.CONFIRMATION)
                alert.title = "Обновление данных"
                alert.headerText = "📥 Найдены новые данные для импорта"
                alert.contentText = message

                val result = alert.showAndWait()
                if (result.isPresent && result.get() == ButtonType.OK) {
                    importData()
                }
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
                
                Нажмите "OK" для обновления отображения.
            """.trimIndent()
                alert.showAndWait()

                // Перезагружаем метки
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
        webView.setOnMousePressed { event: MouseEvent ->
            if (event.isPrimaryButtonDown) {
                if (!isEditMode) {
                    isDragging = true
                    lastMouseX = event.x
                    lastMouseY = event.y
                    webView.engine.executeScript("""
                    document.getElementById('container').classList.add('dragging');
                """.trimIndent())
                }
            }
        }

        webView.setOnMouseDragged { event: MouseEvent ->
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
            }
        }

        webView.setOnMouseReleased { event: MouseEvent ->
            if (isDragging) {
                isDragging = false
                webView.engine.executeScript("""
                document.getElementById('container').classList.remove('dragging');
            """.trimIndent())
            }
        }

        webView.setOnMouseExited {
            if (isDragging) {
                isDragging = false
                webView.engine.executeScript("""
                document.getElementById('container').classList.remove('dragging');
            """.trimIndent())
            }
        }

        webView.setOnMouseClicked { event: MouseEvent ->
            if (event.clickCount == 2) {
                zoomLevel = 1.0
                currentTranslateX = 0.0
                currentTranslateY = 0.0
                webView.engine.executeScript("""
                document.getElementById('image-wrapper').style.transform = 'translate(0px, 0px) scale(1)';
                document.getElementById('image-wrapper').style.transformOrigin = 'center center';
            """.trimIndent())
            }
        }
    }

    // ======================== СПИСОК ДЕФЕКТОВ ========================

    private fun showDefectsList() {
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
        popupStage.showAndWait()
    }

    // ======================== КЛИКИ ========================

    private fun setupClickHandler() {
        // Левый клик - просмотр (только не в режиме редактирования)
        webView.setOnMouseClicked { event: MouseEvent ->
            if (event.clickCount == 1 && !isEditMode) {
                if (event.button == javafx.scene.input.MouseButton.PRIMARY) {
                    handleEquipmentClick(event.x, event.y)
                }
            }
            // В режиме редактирования - добавляем оборудование по левому клику
            if (event.clickCount == 1 && isEditMode) {
                if (event.button == javafx.scene.input.MouseButton.PRIMARY) {
                    println("🖱️ Клик в режиме редактирования!")
                    addEquipmentAtPosition(event.x, event.y)
                }
            }
        }

        // Контекстное меню по правому клику (только в режиме редактирования)
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
            
            return {
                equipmentId: marker.dataset.equipmentId || null,
                isExtra: marker.classList.contains('marker-extra'),
                markerId: marker.id
            };
        })();
    """.trimIndent()) as? Map<*, *>

        if (markerInfo != null) {
            val realId = markerInfo["equipmentId"] as? String
            isExtraMarker = markerInfo["isExtra"] as? Boolean ?: false
            markerId = markerInfo["markerId"] as? String ?: equipmentId

            if (realId != null) {
                equipment = loadEquipment().find { it.id == realId }
            }
        }

        // 2. Если не нашли — ищем по позиции
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
                // Проверяем, является ли маркер дополнительным
                val isExtra = webView.engine.executeScript("""
                (function() {
                    var marker = document.getElementById('$equipmentId');
                    return marker ? marker.classList.contains('marker-extra') : false;
                })();
            """.trimIndent()) as? Boolean ?: false
                isExtraMarker = isExtra
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

                        // 2. Выбор ячейки (ОБЩАЯ ФУНКЦИЯ!)
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
                                    size: '$size'
                                });
                                console.log('✅ Добавлено оборудование: $escapedName (ячейка: $escapedCell, размер: $size)');
                            })();
                            """.trimIndent())
                                saveEquipment()
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

                webView.engine.executeScript("""
            (function() {
                var container = document.getElementById('equipment-container');
                if (!container) return;
                
                var marker = document.createElement('div');
                var sizeClass = '${equipment.size}' || 'normal';
                marker.className = 'equipment-marker ${equipment.type} ' + sizeClass + ' marker-extra';
                // Добавляем класс hidden, если маркеры скрыты
                if (!${markersVisible}) {
                    marker.className += ' hidden';
                }
                marker.id = '${equipmentId}-marker-' + Date.now();
                marker.style.left = '$left%';
                marker.style.top = '$top%';
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

                val updatedList2 = loadEquipment()
                val equipmentJson = gson.toJson(updatedList2)
                webView.engine.executeScript("""
                window.equipment = $equipmentJson;
                console.log('✅ window.equipment обновлён, маркеров: ' + window.equipment.length);
            """.trimIndent())
            } else {
                showError("Оборудование не найдено. ID: $equipmentId")
            }
        } else {
            showError("Не удалось определить позицию на схеме")
        }
    }

    // ======================== ВСПЛЫВАЮЩАЯ ПОДСКАЗКА (TOAST) ========================

    private fun showToast(message: String, duration: javafx.util.Duration = javafx.util.Duration.seconds(2.5)) {
        Platform.runLater {
            val toast = Label(message)
            toast.style = """
            -fx-background-color: rgba(0, 0, 0, 0.8);
            -fx-text-fill: white;
            -fx-font-size: 14px;
            -fx-padding: 12px 24px;
            -fx-background-radius: 8px;
            -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 10, 0, 0, 0);
        """.trimIndent()
            toast.isWrapText = true
            toast.maxWidth = 500.0
            toast.alignment = Pos.CENTER

            val scene = webView.scene
            if (scene != null) {
                val stackPane = StackPane()
                stackPane.children.add(toast)
                stackPane.isMouseTransparent = true

                val root = scene.root as? javafx.scene.layout.Pane
                if (root != null) {
                    root.children.add(stackPane)
                    StackPane.setAlignment(stackPane, Pos.TOP_CENTER)
                    StackPane.setMargin(stackPane, Insets(60.0, 0.0, 0.0, 0.0))

                    // PauseTransition — альтернатива Timeline
                    val pause = PauseTransition(duration)
                    pause.setOnFinished {
                        root.children.remove(stackPane)
                    }
                    pause.play()
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
                val tableView = javafx.scene.control.TableView<EquipmentTableItem>()
                tableView.style = "-fx-font-size: 13px; -fx-border-color: #dee2e6;"

                // ======================== КОЛОНКИ ТАБЛИЦЫ ========================

                val colNumber = javafx.scene.control.TableColumn<EquipmentTableItem, Int>("№")
                colNumber.cellValueFactory = javafx.scene.control.cell.PropertyValueFactory("number")
                colNumber.prefWidth = 45.0
                colNumber.style = "-fx-alignment: CENTER;"

                val colName = javafx.scene.control.TableColumn<EquipmentTableItem, String>("Наименование")
                colName.cellValueFactory = javafx.scene.control.cell.PropertyValueFactory("name")
                colName.prefWidth = 200.0

                val colType = javafx.scene.control.TableColumn<EquipmentTableItem, String>("Тип")
                colType.cellValueFactory = javafx.scene.control.cell.PropertyValueFactory("type")
                colType.prefWidth = 180.0

                val colCell = javafx.scene.control.TableColumn<EquipmentTableItem, String>("Ячейка")
                colCell.cellValueFactory = javafx.scene.control.cell.PropertyValueFactory("cell")
                colCell.prefWidth = 70.0
                colCell.style = "-fx-alignment: CENTER;"

                val colX = javafx.scene.control.TableColumn<EquipmentTableItem, Double>("X%")
                colX.cellValueFactory = javafx.scene.control.cell.PropertyValueFactory("left")
                colX.prefWidth = 60.0
                colX.style = "-fx-alignment: CENTER;"

                val colY = javafx.scene.control.TableColumn<EquipmentTableItem, Double>("Y%")
                colY.cellValueFactory = javafx.scene.control.cell.PropertyValueFactory("top")
                colY.prefWidth = 60.0
                colY.style = "-fx-alignment: CENTER;"

                val colId = javafx.scene.control.TableColumn<EquipmentTableItem, String>("ID")
                colId.cellValueFactory = javafx.scene.control.cell.PropertyValueFactory("id")
                colId.prefWidth = 120.0

                // ======================== КОЛОНКА: ДЕЙСТВИЯ (С КНОПКАМИ) ========================

                val colActions = javafx.scene.control.TableColumn<EquipmentTableItem, Void>("Действие")
                colActions.prefWidth = 120.0
                colActions.style = "-fx-alignment: CENTER;"

                colActions.setCellFactory {
                    object : javafx.scene.control.TableCell<EquipmentTableItem, Void>() {
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
                                        tableView.items = javafx.collections.FXCollections.observableArrayList(updatedItems)
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
                    tableView.items = javafx.collections.FXCollections.observableArrayList(items)
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
                    val row = javafx.scene.control.TableRow<EquipmentTableItem>()
                    row.styleProperty().bind(
                        javafx.beans.binding.Bindings.`when`(row.hoverProperty())
                            .then("-fx-background-color: #e8f4f8;")
                            .otherwise("-fx-background-color: transparent;")
                    )
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
                            // Обновляем таблицу через перезагрузку данных из БД
                            val updatedData = loadEquipment()
                            val updatedItems = toTableItems(updatedData)
                            tableView.items = javafx.collections.FXCollections.observableArrayList(updatedItems)
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
                database.saveEquipment(equipment)
                println("💾 Сохранено в БД: ${equipment.size} шт.")

                // Показываем уведомление об автоэкспорте
                showExportNotification(equipment.size)
            } catch (e: Exception) {
                showError("Ошибка сохранения: ${e.message}")
            }
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