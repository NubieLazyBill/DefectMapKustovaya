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
import java.io.File
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import javafx.scene.layout.HBox
import javafx.scene.control.TextArea
import javafx.scene.control.TextField

class DefectMapController {

    @FXML
    private lateinit var viewEquipmentBtn: Button

    @FXML
    private lateinit var webView: WebView

    @FXML
    private lateinit var addEquipmentBtn: Button   // было addMarkerBtn

    @FXML
    private lateinit var editModeBtn: Button

    @FXML
    private lateinit var saveEquipmentBtn: Button  // было saveMarkersBtn

    @FXML
    private lateinit var cancelEditBtn: Button

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
    private var equipmentCounter = 0  // было markerCounter

    private val equipmentFile: File by lazy {  // было markersFile
        val userHome = System.getProperty("user.home")
        val appDir = File(userHome, ".defectmap")
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
        File(appDir, "equipment.json")  // было markers.json
    }

    private val gson: Gson by lazy {
        GsonBuilder().setPrettyPrinting().create()
    }

    @FXML
    private fun initialize() {
        loadSvgIntoWebView()

        webView.engine.getLoadWorker().stateProperty().addListener { _, _, newState ->
            if (newState == Worker.State.SUCCEEDED) {
                Platform.runLater {
                    // ДАЖЕ ЕСЛИ МЕТКИ УЖЕ ЗАГРУЖЕНЫ - ПЕРЕЗАГРУЖАЕМ
                    setupZoom()
                    setupPan()
                    setupClickHandler()
                    setupButtons()
                    initEquipment()  // <-- ЭТО ЗАГРУЖАЕТ МЕТКИ ИЗ ФАЙЛА
                }
            }
        }
    }

    private fun viewEquipmentList() {
        val result = webView.engine.executeScript("""
        (function() {
            return JSON.stringify(window.equipment || []);
        })();
    """.trimIndent()) as? String

        if (result != null) {
            try {
                val type = object : TypeToken<List<EquipmentData>>() {}.type
                val equipment: List<EquipmentData> = gson.fromJson(result, type)

                if (equipment.isEmpty()) {
                    showInfo("📋 Нет сохраненного оборудования")
                    return
                }

                // Создаем простую текстовую область вместо TableView (проще и надежнее)
                val textArea = javafx.scene.control.TextArea()
                textArea.isEditable = false
                textArea.style = "-fx-font-family: 'Courier New', monospace; -fx-font-size: 13px;"

                // Формируем текст
                val sb = StringBuilder()
                sb.append("=".repeat(80) + "\n")
                sb.append("📋 СПИСОК ОБОРУДОВАНИЯ (${equipment.size} шт.)\n")
                sb.append("=".repeat(80) + "\n\n")

                equipment.forEachIndexed { index, item ->
                    sb.append(String.format("%3d. %-25s | Тип: %-15s | X: %6.1f%% | Y: %6.1f%%\n",
                        index + 1,
                        item.name.take(25),
                        item.type.take(15),
                        item.left,
                        item.top
                    ))
                }

                sb.append("\n" + "=".repeat(80) + "\n")
                sb.append("💡 Двойной клик по ID для поиска на схеме\n")
                sb.append("=".repeat(80))

                textArea.text = sb.toString()
                textArea.prefHeight = 400.0
                textArea.prefWidth = 650.0

                // ID для поиска
                val idField = javafx.scene.control.TextField()
                idField.promptText = "Введите ID оборудования для поиска"
                idField.style = "-fx-font-size: 13px; -fx-padding: 8px;"
                idField.prefWidth = 400.0

                val searchButton = Button("🔍 Найти")
                searchButton.style = "-fx-font-size: 14px; -fx-background-color: #2196F3; -fx-text-fill: white; -fx-padding: 8px 20px; -fx-background-radius: 6px;"
                searchButton.setOnAction {
                    val searchId = idField.text.trim()
                    if (searchId.isNotEmpty()) {
                        val found = equipment.find { it.id == searchId || it.name.contains(searchId, ignoreCase = true) }
                        if (found != null) {
                            showEquipmentOnMap(found.id)
                            // Закрываем окно после поиска
                            val stage = searchButton.scene.window as Stage
                            stage.close()
                        } else {
                            showInfo("Оборудование не найдено: $searchId")
                        }
                    }
                }

                // Экспорт в CSV
                val exportButton = Button("📤 Экспорт CSV")
                exportButton.style = "-fx-font-size: 14px; -fx-background-color: #4CAF50; -fx-text-fill: white; -fx-padding: 8px 20px; -fx-background-radius: 6px;"
                exportButton.setOnAction {
                    exportEquipmentToCsv()
                }

                val closeButton = Button("✕ Закрыть")
                closeButton.style = "-fx-font-size: 14px; -fx-background-color: #f44336; -fx-text-fill: white; -fx-padding: 8px 20px; -fx-background-radius: 6px;"

                // Панель поиска
                val searchBox = HBox(10.0, idField, searchButton)
                searchBox.alignment = Pos.CENTER

                // Панель кнопок
                val buttonBox = HBox(20.0, exportButton, closeButton)
                buttonBox.alignment = Pos.CENTER

                val layout = VBox(15.0, textArea, searchBox, buttonBox)
                layout.alignment = Pos.CENTER
                layout.style = "-fx-background-color: white; -fx-padding: 20px; -fx-background-radius: 12px;"
                layout.prefWidth = 700.0
                layout.prefHeight = 550.0

                val popupStage = Stage()
                popupStage.title = "📋 Список оборудования"
                popupStage.scene = Scene(layout, 750.0, 550.0)
                popupStage.isResizable = true
                popupStage.minWidth = 600.0
                popupStage.minHeight = 400.0

                closeButton.setOnAction { popupStage.close() }
                popupStage.showAndWait()

            } catch (e: Exception) {
                println("❌ Ошибка: ${e.message}")
                e.printStackTrace()
                showError("Ошибка при чтении оборудования: ${e.message}")
            }
        } else {
            showInfo("📋 Нет сохраненного оборудования")
        }
    }

    private fun showEquipmentOnMap(equipmentId: String) {
        // Подсвечиваем оборудование на схеме
        webView.engine.executeScript("""
        (function() {
            var id = '$equipmentId';
            var marker = document.getElementById(id);
            if (!marker) {
                // Пробуем найти по старому ID
                var oldId = id.replace('equipment-', 'marker-');
                marker = document.getElementById(oldId);
            }
            
            if (marker) {
                // Подсветка
                var originalTransform = marker.style.transform;
                marker.style.transform = 'translate(-50%, -50%) scale(2)';
                marker.style.boxShadow = '0 0 30px rgba(255,255,0,0.8)';
                marker.style.border = '3px solid yellow';
                
                // Возвращаем через 2 секунды
                setTimeout(function() {
                    marker.style.transform = originalTransform;
                    marker.style.boxShadow = '';
                    marker.style.border = '';
                }, 3000);
                
                // Прокручиваем к метке
                marker.scrollIntoView({ behavior: 'smooth', block: 'center' });
            }
        })();
    """.trimIndent())
    }

    private fun exportEquipmentToCsv() {
        val result = webView.engine.executeScript("""
        (function() {
            return JSON.stringify(window.equipment || []);
        })();
    """.trimIndent()) as? String

        if (result != null) {
            try {
                val type = object : TypeToken<List<EquipmentData>>() {}.type
                val equipment: List<EquipmentData> = gson.fromJson(result, type)

                if (equipment.isEmpty()) {
                    showInfo("Нет данных для экспорта")
                    return
                }

                // Формируем CSV
                val sb = StringBuilder()
                sb.append("№;Наименование;Тип;X%;Y%;ID\n")
                equipment.forEachIndexed { index, item ->
                    sb.append("${index + 1};${item.name};${item.type};${item.left};${item.top};${item.id}\n")
                }

                // Сохраняем в файл
                val csvFile = File(equipmentFile.parent, "equipment_export.csv")
                csvFile.writeText(sb.toString(), Charsets.UTF_8)

                showInfo("✅ Экспортировано ${equipment.size} единиц оборудования в файл:\n${csvFile.absolutePath}")

            } catch (e: Exception) {
                showError("Ошибка экспорта: ${e.message}")
            }
        }
    }


    private fun loadSvgIntoWebView() {
        val svgFile = javaClass.getResource("/org/example/defectmap/schema.svg")
        if (svgFile != null) {
            val html = """
        <!DOCTYPE html>
        <html>
          <head>
            <style>
              * { margin: 0; padding: 0; }
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
              #container.dragging {
                cursor: grabbing;
              }
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
              
              /* КРУГЛЫЕ МЕТКИ ОБОРУДОВАНИЯ */
              .equipment-marker {  /* было .marker */
                position: absolute;
                cursor: pointer;
                z-index: 10;
                pointer-events: auto;
                transform: translate(-50%, -50%);
                width: 28px;
                height: 28px;
                transition: transform 0.2s ease;
              }
              .equipment-marker:hover {
                transform: translate(-50%, -50%) scale(1.3);
              }
              .equipment-marker .dot {
                width: 24px;
                height: 24px;
                border-radius: 50%;
                border: 2px solid white;
                box-shadow: 0 2px 10px rgba(0,0,0,0.3);
                display: flex;
                justify-content: center;
                align-items: center;
                color: white;
                font-weight: bold;
                font-size: 11px;
                font-family: Arial, sans-serif;
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
              
              .edit-mode #container {
                cursor: crosshair;
              }
            </style>
          </head>
          <body>
            <div id="container">
              <div id="image-wrapper">
                <img id="image" src="${svgFile.toExternalForm()}" alt="Schema" />
                <div id="equipment-container"></div>  <!-- было markers-container -->
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

    private fun initEquipment() {
        val savedEquipment = loadEquipment()
        println("📂 Загружено из файла: ${savedEquipment.size} шт.")

        if (savedEquipment.isNotEmpty()) {
            val equipmentJson = gson.toJson(savedEquipment)

            webView.engine.executeScript("""
            (function() {
                window.equipment = [];
                window.equipmentIdCounter = 0;
                
                var savedData = $equipmentJson;
                console.log('📂 Загружаем в JavaScript: ' + savedData.length + ' шт.');
                
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
                }
                
                if (!container) {
                    console.log('❌ Нет контейнера');
                    return;
                }
                
                savedData.forEach(function(item) {
                    var marker = document.createElement('div');
                    marker.className = 'equipment-marker ' + item.type;
                    marker.id = item.id;
                    marker.style.left = item.left + '%';
                    marker.style.top = item.top + '%';
                    
                    marker.innerHTML = '<div class=\"dot\">' + item.letter + '</div><span class=\"tooltip-text\">' + item.name + '</span>';
                    
                    container.appendChild(marker);
                    
                    window.equipment.push({
                        id: item.id,
                        left: item.left,
                        top: item.top,
                        type: item.type,
                        name: item.name,
                        letter: item.letter
                    });
                });
                
                console.log('✅ Загружено: ' + savedData.length);
            })();
        """.trimIndent())

            equipmentCounter = savedEquipment.size
        } else {
            webView.engine.executeScript("""
            (function() {
                window.equipment = [];
                window.equipmentIdCounter = 0;
            })();
        """.trimIndent())
        }
    }

    private fun setupButtons() {
        addEquipmentBtn.setOnAction {
            toggleEditMode(true)
        }

        editModeBtn.setOnAction {
            toggleEditMode(!isEditMode)
        }

        saveEquipmentBtn.setOnAction {
            saveEquipment()
        }

        viewEquipmentBtn.setOnAction {  // Новая кнопка
            viewEquipmentList()
        }

        cancelEditBtn.setOnAction {
            toggleEditMode(false)
        }
    }

    private fun toggleEditMode(enable: Boolean) {
        isEditMode = enable
        cancelEditBtn.isVisible = enable
        cancelEditBtn.isManaged = enable

        webView.engine.executeScript("""
            var container = document.getElementById('container');
            if (${enable}) {
                container.classList.add('edit-mode');
                window.editMode = true;
            } else {
                container.classList.remove('edit-mode');
                window.editMode = false;
            }
        """.trimIndent())

        editModeBtn.text = if (enable) "🔒 Выйти из редактирования" else "✏️ Режим редактирования"
    }

    // Остальные методы остаются без изменений, кроме переименований в тексте

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
            if (event.isPrimaryButtonDown) {  // Только левая кнопка
                if (isEditMode) {
                    // В режиме редактирования - ничего не делаем
                } else {
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
            // Добавляем оборудование только если не было перетаскивания и это левая кнопка
            if (!isDragging && event.isPrimaryButtonDown && isEditMode) {
                // Проверяем, было ли движение мыши (не клик)
                // Если мышь не двигалась - это клик
                if (Math.abs(event.x - lastMouseX) < 5 && Math.abs(event.y - lastMouseY) < 5) {
                    addEquipmentAtPosition(event.x, event.y)
                }
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

    private fun setupClickHandler() {
        // Левый клик - просмотр (только не в режиме редактирования)
        webView.setOnMouseClicked { event: MouseEvent ->
            if (event.clickCount == 1 && !isEditMode) {
                if (event.button == javafx.scene.input.MouseButton.PRIMARY) {
                    handleEquipmentClick(event.x, event.y)
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
        println("📌 Контекстное меню для: $equipmentId")

        val contextMenu = javafx.scene.control.ContextMenu()

        val editItem = javafx.scene.control.MenuItem("✏️ Редактировать")
        editItem.setOnAction {
            editEquipment(equipmentId)
        }

        val deleteItem = javafx.scene.control.MenuItem("🗑️ Удалить")
        deleteItem.setOnAction {
            deleteEquipment(equipmentId)
        }

        contextMenu.items.addAll(editItem, deleteItem)
        contextMenu.show(webView, x, y)
    }

    private fun editEquipment(equipmentId: String) {
        println("=".repeat(60))
        println("✏️ РЕДАКТИРОВАНИЕ ОБОРУДОВАНИЯ")
        println("📌 ID: $equipmentId")
        println("=".repeat(60))

        val allEquipment = loadEquipment()
        println("📂 Всего в файле: ${allEquipment.size} шт.")

        val equipment = allEquipment.find { it.id == equipmentId }

        if (equipment == null) {
            println("❌ Не найдено в файле!")
            showError("Оборудование не найдено. ID: $equipmentId")
            return
        }

        println("✅ Найдено: ${equipment.name} (${equipment.type})")

        val currentName = equipment.name
        val currentType = equipment.type

        Platform.runLater {
            val nameDialog = TextInputDialog(currentName)
            nameDialog.title = "Редактирование оборудования"
            nameDialog.headerText = "Введите новое название"
            nameDialog.contentText = "Наименование:"

            val nameResult = nameDialog.showAndWait()
            if (nameResult.isPresent) {
                val newName = nameResult.get().toString()  // Явно приводим к String
                if (newName.isNotEmpty()) {
                    println("📝 Новое имя: $newName")

                    val typeDialog = ChoiceDialog(currentType, listOf(
                        "v_500" to "Выключатель 500 кВ (В-500)",
                        "r_500" to "Разъединитель 500 кВ (Р-500)",
                        "autotransformer" to "Автотрансформатор 500 кВ (АТ)",
                        "tn_500" to "ТН_500",
                        "tt_500" to "ТТ_500",
                        "ks_500" to "КС_500",
                        "opn_500" to "ОПН-500",
                        "reactor_500" to "Реактор 500 кВ (Р-500)",
                        "v_220" to "Выключатель 220 кВ (В-220)",
                        "r_220" to "Разъединитель 220 кВ (Р-220)",
                        "opn_220" to "ОПН 220 кВ",
                        "tn_220" to "ТН 220 кВ",
                        "tt_220" to "ТТ 220 кВ",
                        "ks_220" to "КС 220 кВ",
                        "line_220" to "Линия 220 кВ (Л-220)",
                        "v_35" to "Выключатель 35 кВ (В-35)",
                        "r_35" to "Разъединитель 35 кВ (Р-35)",
                        "tn_35" to "ТН 35 кВ",
                        "tt_35" to "ТТ 35 кВ",
                        "lightning" to "Молниеотвод (М)",
                        "capacitor" to "Конденсатор (К)",
                        "arrester" to "Разрядник (РВ)",
                        "line_trap" to "Заградитель (З)",
                        "coupling_capacitor" to "Конденсатор связи (КС)",
                        "earthing_switch" to "Заземляющий нож (ЗН)",
                        "load_switch" to "Нагрузочный выключатель (ВН)",
                        "fuse" to "Предохранитель (Пр)",
                        "sf6_breaker" to "Элегазовый выключатель (ВЭ)",
                        "vacuum_breaker" to "Вакуумный выключатель (ВВ)",
                        "compressor" to "Компрессорная (К)",
                        "pump" to "Насос (Н)",
                        "generator" to "Генератор (Г)",
                        "motor" to "Электродвигатель (М)",
                        "other" to "Другое (О)"
                    ))
                    typeDialog.title = "Тип оборудования"
                    typeDialog.headerText = "Выберите тип"
                    typeDialog.contentText = "Тип:"

                    val typeResult = typeDialog.showAndWait()
                    if (typeResult.isPresent) {
                        val newType = typeResult.get().toString()  // Явно приводим к String
                        val newLetter = when (newType) {
                            "v_500" -> "В"
                            "r_500" -> "Р"
                            "autotransformer" -> "АТ"
                            "tn_500" -> "ТН"
                            "tt_500" -> "ТТ"
                            "ks_500" -> "КС"
                            "opn_500" -> "ОПН"
                            "reactor_500" -> "Р"
                            "v_220" -> "В"
                            "r_220" -> "Р"
                            "opn_220" -> "ОПН"
                            "tn_220" -> "ТН"
                            "tt_220" -> "ТТ"
                            "ks_220" -> "КС"
                            "line_220" -> "Л"
                            "v_35" -> "В"
                            "r_35" -> "Р"
                            "tn_35" -> "ТН"
                            "tt_35" -> "ТТ"
                            "lightning" -> "М"
                            "capacitor" -> "К"
                            "arrester" -> "РВ"
                            "line_trap" -> "З"
                            "coupling_capacitor" -> "КС"
                            "earthing_switch" -> "ЗН"
                            "load_switch" -> "ВН"
                            "fuse" -> "Пр"
                            "sf6_breaker" -> "ВЭ"
                            "vacuum_breaker" -> "ВВ"
                            "compressor" -> "К"
                            "pump" -> "Н"
                            "generator" -> "Г"
                            "motor" -> "М"
                            else -> "О"
                        }

                        // Обновляем список
                        val updatedList = allEquipment.map {
                            if (it.id == equipmentId) {
                                EquipmentData(
                                    id = it.id,
                                    left = it.left,
                                    top = it.top,
                                    type = newType,
                                    name = newName,
                                    letter = newLetter
                                )
                            } else {
                                it
                            }
                        }

                        // Сохраняем в файл
                        val json = gson.toJson(updatedList)
                        equipmentFile.writeText(json)
                        println("✅ Файл обновлен")

                        // Обновляем в JavaScript
                        webView.engine.executeScript("""
                        (function() {
                            var id = '$equipmentId';
                            var newName = '$newName';
                            var newType = '$newType';
                            var newLetter = '$newLetter';
                            
                            var marker = document.getElementById(id);
                            if (marker) {
                                marker.className = 'equipment-marker ' + newType;
                                var dot = marker.querySelector('.dot');
                                if (dot) dot.textContent = newLetter;
                                var tooltip = marker.querySelector('.tooltip-text');
                                if (tooltip) tooltip.textContent = newName;
                            }
                            
                            if (window.equipment) {
                                for (var i = 0; i < window.equipment.length; i++) {
                                    if (window.equipment[i].id === id) {
                                        window.equipment[i].name = newName;
                                        window.equipment[i].type = newType;
                                        window.equipment[i].letter = newLetter;
                                        break;
                                    }
                                }
                            }
                        })();
                    """.trimIndent())

                        equipmentCounter = updatedList.size
                        showInfo("Оборудование обновлено: $newName")
                    }
                }
            }
        }
    }

    private fun deleteEquipment(equipmentId: String) {
        // Загружаем данные из файла
        val allEquipment = loadEquipment()
        val updatedList = allEquipment.filter { it.id != equipmentId }

        // Сохраняем в файл
        val json = gson.toJson(updatedList)
        equipmentFile.writeText(json)
        println("🗑️ Удалено: $equipmentId, осталось: ${updatedList.size}")

        // Удаляем из DOM и window.equipment
        webView.engine.executeScript("""
        (function() {
            var id = '$equipmentId';
            
            var marker = document.getElementById(id);
            if (marker) marker.remove();
            
            if (window.equipment) {
                var index = -1;
                for (var i = 0; i < window.equipment.length; i++) {
                    if (window.equipment[i].id === id) {
                        index = i;
                        break;
                    }
                }
                if (index !== -1) {
                    window.equipment.splice(index, 1);
                }
            }
        })();
    """.trimIndent())

        equipmentCounter = updatedList.size
        showInfo("Оборудование удалено")
    }


    private fun addEquipmentAtPosition(x: Double, y: Double) {  // было addMarkerAtPosition
        println("📍 Добавление оборудования: x=$x, y=$y")  // было "Добавление метки"

        val result = webView.engine.executeScript("""
        (function() {
            var wrapper = document.getElementById('image-wrapper');
            var rect = wrapper.getBoundingClientRect();
            var cx = (($x - rect.left) / rect.width * 100).toFixed(1);
            var cy = (($y - rect.top) / rect.height * 100).toFixed(1);
            return cx + ',' + cy;
        })();
    """.trimIndent()) as? String

        println("📐 Результат: $result")

        if (result != null && result.contains(",")) {
            val parts = result.split(",")
            val left = parts[0]
            val top = parts[1]

            Platform.runLater {
                val dialog = TextInputDialog("Введите название")
                dialog.title = "Новое оборудование"  // было "Новая метка"
                dialog.headerText = "Введите диспетчерское наименование"
                dialog.contentText = "Наименование:"

                val result2 = dialog.showAndWait()
                if (result2.isPresent) {
                    val name = result2.get()
                    if (name.isNotEmpty()) {
                        val typeDialog = ChoiceDialog("breaker", listOf(
                            // --- 500 кВ ---
                            "v_500" to "Выключатель 500 кВ (В-500)",
                            "r_500" to "Разъединитель 500 кВ (Р-500)",
                            "autotransformer" to "Автотрансформатор 500 кВ (АТ)",
                            "tn_500" to "ТН_500",
                            "tt_500" to "ТТ_500",
                            "ks_500" to "КС_500",
                            "opn_500" to "ОПН-500",
                            "reactor_500" to "Реактор 500 кВ (Р-500)",
                            // --- 220 кВ ---
                            "v_220" to "Выключатель 220 кВ (В-220)",
                            "r_220" to "Разъединитель 220 кВ (Р-220)",
                            "opn_220" to "ОПН 220 кВ",
                            "tn_220" to "ТН 220 кВ",
                            "tt_220" to "ТТ 220 кВ",
                            "ks_220" to "КС 220 кВ",
                            "line_220" to "Линия 220 кВ (Л-220)",
                            // --- 35 кВ ---
                            "v_35" to "Выключатель 35 кВ (В-35)",
                            "r_35" to "Разъединитель 35 кВ (Р-35)",
                            "tn_35" to "ТН 35 кВ",
                            "tt_35" to "ТТ 35 кВ",
                            // --- Молниеотводы ---
                            "lightning" to "Молниеотвод (М)",
                            // --- Другое оборудование ---
                            "capacitor" to "Конденсатор (К)",
                            "arrester" to "Разрядник (РВ)",
                            "line_trap" to "Заградитель (З)",
                            "coupling_capacitor" to "Конденсатор связи (КС)",
                            "earthing_switch" to "Заземляющий нож (ЗН)",
                            "load_switch" to "Нагрузочный выключатель (ВН)",
                            "fuse" to "Предохранитель (Пр)",
                            "sf6_breaker" to "Элегазовый выключатель (ВЭ)",
                            "vacuum_breaker" to "Вакуумный выключатель (ВВ)",
                            // --- Вспомогательное ---
                            "compressor" to "Компрессорная (К)",
                            "pump" to "Насос (Н)",
                            "generator" to "Генератор (Г)",
                            "motor" to "Электродвигатель (М)",
                            // --- Прочее ---
                            "other" to "Другое (О)"
                        ))

                        typeDialog.title = "Тип оборудования"
                        typeDialog.headerText = "Выберите тип"
                        typeDialog.contentText = "Тип:"

                        val typeResult = typeDialog.showAndWait()
                        if (typeResult.isPresent) {
                            val type = typeResult.get()
                            val typeLabel = when (type) {
                                "v_500" -> "В"
                                "r_500" -> "Р"
                                "autotransformer" -> "АТ"
                                "tn_500" -> "ТН"
                                "tt_500" -> "ТТ"
                                "ks_500" -> "КС"
                                "opn_500" -> "ОПН"
                                "reactor_500" -> "Р"
                                "v_220" -> "В"
                                "r_220" -> "Р"
                                "opn_220" -> "ОПН"
                                "tn_220" -> "ТН"
                                "tt_220" -> "ТТ"
                                "ks_220" -> "КС"
                                "line_220" -> "Л"
                                "v_35" -> "В"
                                "r_35" -> "Р"
                                "tn_35" -> "ТН"
                                "tt_35" -> "ТТ"
                                "lightning" -> "М"
                                "capacitor" -> "К"
                                "arrester" -> "РВ"
                                "line_trap" -> "З"
                                "coupling_capacitor" -> "КС"
                                "earthing_switch" -> "ЗН"
                                "load_switch" -> "ВН"
                                "fuse" -> "Пр"
                                "sf6_breaker" -> "ВЭ"
                                "vacuum_breaker" -> "ВВ"
                                "compressor" -> "К"
                                "pump" -> "Н"
                                "generator" -> "Г"
                                "motor" -> "М"
                                else -> "О"
                            }

                            val id = "equipment-${System.currentTimeMillis()}"  // было marker-
                            equipmentCounter++

                            webView.engine.executeScript("""
                            (function() {
                                var container = document.getElementById('equipment-container');
                                var marker = document.createElement('div');
                                marker.className = 'equipment-marker $type';
                                marker.id = '$id';
                                marker.dataset.id = '${equipmentCounter}';
                                marker.style.left = '${left}%';
                                marker.style.top = '${top}%';
                                
                                marker.innerHTML = '<div class=\"dot\">${typeLabel}</div><span class=\"tooltip-text\">${name}</span>';
                                
                                container.appendChild(marker);
                                
                                if (!window.equipment) window.equipment = [];
                                window.equipment.push({
                                    id: '$id',
                                    left: parseFloat('${left}'),
                                    top: parseFloat('${top}'),
                                    type: '$type',
                                    name: '${name}',
                                    letter: '$typeLabel'
                                });
                                
                                console.log('✅ Добавлено оборудование: ${name}');
                            })();
                        """.trimIndent())

                            saveEquipment()
                        }
                    }
                }
            }
        } else {
            println("❌ Ошибка: результат null")
        }
    }

    private fun saveEquipment() {
        val result = webView.engine.executeScript("""
        (function() {
            return JSON.stringify(window.equipment || []);
        })();
    """.trimIndent()) as? String

        if (result != null) {
            try {
                val type = object : TypeToken<List<EquipmentData>>() {}.type
                val equipment: List<EquipmentData> = gson.fromJson(result, type)
                val json = gson.toJson(equipment)
                equipmentFile.writeText(json)
                println("💾 Сохранено оборудования: ${equipment.size}")
            } catch (e: Exception) {
                println("❌ Ошибка сохранения: ${e.message}")
                showError("Ошибка сохранения: ${e.message}")
            }
        } else {
            showError("Нет данных для сохранения")
        }
    }

    private fun loadEquipment(): List<EquipmentData> {  // было loadMarkers, MarkerData
        return try {
            if (equipmentFile.exists()) {
                val json = equipmentFile.readText()
                val type = object : TypeToken<List<EquipmentData>>() {}.type
                gson.fromJson(json, type)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            println("❌ Ошибка загрузки: ${e.message}")
            emptyList()
        }
    }

    private fun handleEquipmentClick(x: Double, y: Double) {  // было handleMarkerClick
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
    """.trimIndent())

        val equipmentId = result as? String
        if (equipmentId != null) {
            showEquipmentImage(equipmentId)  // было showBreakerImage
        }
    }

    private fun showEquipmentImage(equipmentId: String) {  // было showBreakerImage
        val equipmentInfo = webView.engine.executeScript("""
        (function() {
            var equipment = window.equipment || [];
            for (var i = 0; i < equipment.length; i++) {
                if (equipment[i].id === '$equipmentId') {
                    return equipment[i];
                }
            }
            return null;
        })();
    """.trimIndent()) as? Map<*, *>

        var imagePath = "/org/example/defectmap/ВВБК-500.jfif"

        if (equipmentInfo != null) {
            val type = equipmentInfo["type"] as? String ?: ""

            imagePath = when {
                type == "v_500" || type == "v_220" || type == "v_35" -> "/org/example/defectmap/breaker_500kv.jpg"
                type == "r_500" || type == "r_220" || type == "r_35" -> "/org/example/defectmap/disconnector.jpg"
                type == "autotransformer" -> "/org/example/defectmap/transformer.jpg"
                type == "lightning" -> "/org/example/defectmap/lightning_rod.jpg"
                type == "opn_500" || type == "opn_220" -> "/org/example/defectmap/opn.jpg"
                type == "tn_500" || type == "tn_220" || type == "tn_35" -> "/org/example/defectmap/tn.jpg"
                type == "tt_500" || type == "tt_220" || type == "tt_35" -> "/org/example/defectmap/tt.jpg"
                type == "ks_500" || type == "ks_220" || type == "coupling_capacitor" -> "/org/example/defectmap/capacitor.jpg"
                type == "reactor_500" -> "/org/example/defectmap/reactor.jpg"
                type == "capacitor" -> "/org/example/defectmap/capacitor.jpg"
                type == "compressor" -> "/org/example/defectmap/compressor.jpg"
                else -> "/org/example/defectmap/equipment.jpg"
            }
        }

        val imageUrl = javaClass.getResource(imagePath)
        if (imageUrl != null) {
            try {
                val image = Image(imageUrl.toExternalForm())
                val imageView = ImageView(image)
                imageView.isPreserveRatio = true
                imageView.fitWidth = 600.0
                imageView.fitHeight = 400.0

                val infoLabel = javafx.scene.control.Label()
                if (equipmentInfo != null) {
                    val name = equipmentInfo["name"] as? String ?: "Оборудование"
                    val type = equipmentInfo["type"] as? String ?: ""
                    infoLabel.text = "📌 $name\nТип: $type"
                    infoLabel.style = "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333; -fx-text-alignment: center;"
                    infoLabel.isWrapText = true
                    infoLabel.maxWidth = 600.0
                    infoLabel.alignment = Pos.CENTER
                } else {
                    infoLabel.text = "Оборудование"
                    infoLabel.style = "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;"
                }

                val closeButton = Button("✕ Закрыть")
                closeButton.style = "-fx-font-size: 14px; -fx-background-color: #ff4444; -fx-text-fill: white; -fx-padding: 8px 20px; -fx-background-radius: 6px;"

                val layout = VBox(15.0, imageView, infoLabel, closeButton)
                layout.alignment = Pos.CENTER
                layout.style = "-fx-background-color: white; -fx-padding: 20px; -fx-background-radius: 12px; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 20, 0, 0, 0);"

                val popupStage = Stage()
                popupStage.title = equipmentInfo?.let { it["name"] as? String } ?: "Информация об оборудовании"
                popupStage.scene = Scene(layout, 680.0, 560.0)
                popupStage.isResizable = false

                closeButton.setOnAction { popupStage.close() }
                popupStage.scene.setOnKeyPressed { event ->
                    if (event.code == KeyCode.ESCAPE) popupStage.close()
                }

                popupStage.showAndWait()
            } catch (e: Exception) {
                println("❌ Ошибка загрузки изображения: ${e.message}")
                showError("Не удалось загрузить изображение")
            }
        } else {
            showError("Изображение не найдено: $imagePath")
        }
    }

    private fun showInfo(message: String) {
        Platform.runLater {
            val alert = Alert(AlertType.INFORMATION)
            alert.title = "Информация"
            alert.headerText = null
            alert.contentText = message
            alert.showAndWait()
        }
    }

    private fun showError(message: String) {
        Platform.runLater {
            val alert = Alert(AlertType.ERROR)
            alert.title = "Ошибка"
            alert.headerText = null
            alert.contentText = message
            alert.showAndWait()
        }
    }


}

data class EquipmentData(  // было MarkerData
    val id: String,
    val left: Double,
    val top: Double,
    val type: String,
    val name: String,
    val letter: String
)