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

class DefectMapController {

    @FXML
    private lateinit var statsBtn: Button

    @FXML
    private lateinit var webView: WebView

    @FXML
    private lateinit var addEquipmentBtn: Button

    @FXML
    private lateinit var editModeBtn: Button

    @FXML
    private lateinit var saveEquipmentBtn: Button

    @FXML
    private lateinit var viewEquipmentBtn: Button

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
    private var equipmentCounter = 0

    private val database: Database by lazy { Database() }

    private val gson: Gson by lazy {
        GsonBuilder().setPrettyPrinting().create()
    }

    @FXML
    private fun initialize() {
        loadSvgIntoWebView()

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

    // ======================== ЗАГРУЗКА SVG ========================

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
                transition: transform 0.2s ease;
            }
            .equipment-marker:hover {
                transform: translate(-50%, -50%) scale(1.2);
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
            window.equipmentIdCounter = 0;
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
                var marker = document.createElement('div');
                var sizeClass = item.size || 'normal';
                marker.className = 'equipment-marker ' + item.type + ' ' + sizeClass;
                marker.id = item.id;
                marker.style.left = item.left + '%';
                marker.style.top = item.top + '%';
                
                var tooltipText = item.name;
                if (item.cell && item.cell.length > 0) {
                    tooltipText = item.name + ' (яч.' + item.cell + ')';
                }
                marker.innerHTML = '<div class="dot">' + item.letter + '</div><span class="tooltip-text">' + tooltipText + '</span>';
                container.appendChild(marker);
                
                window.equipment.push({
                    id: item.id,
                    left: item.left,
                    top: item.top,
                    type: item.type,
                    name: item.name,
                    letter: item.letter,
                    cell: item.cell || '',
                    size: item.size || 'normal'
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
    // ======================== КНОПКИ ========================

    private fun setupButtons() {
        addEquipmentBtn.setOnAction { toggleEditMode(true) }
        editModeBtn.setOnAction { toggleEditMode(!isEditMode) }
        saveEquipmentBtn.setOnAction { saveEquipment() }
        viewEquipmentBtn.setOnAction { viewEquipmentList() }
        cancelEditBtn.setOnAction { toggleEditMode(false) }
        statsBtn.setOnAction { showStatistics() }
    }

    private fun toggleEditMode(enable: Boolean) {
        isEditMode = enable
        cancelEditBtn.isVisible = enable
        cancelEditBtn.isManaged = enable

        // Добавляем/убираем класс edit-mode у контейнера
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

        editModeBtn.text = if (enable) "🔒 Выйти из редактирования" else "✏️ Режим редактирования"
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
        val contextMenu = ContextMenu()

        val editItem = MenuItem("✏️ Редактировать")
        editItem.setOnAction { editEquipment(equipmentId) }

        val deleteItem = MenuItem("🗑️ Удалить")
        deleteItem.setOnAction { deleteEquipment(equipmentId) }

        contextMenu.items.addAll(editItem, deleteItem)
        contextMenu.show(webView, x, y)
    }

    // ======================== ДОБАВЛЕНИЕ ========================

    private fun addEquipmentAtPosition(x: Double, y: Double) {
        println("📍 Добавление оборудования: x=$x, y=$y")

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
                // Диалог для названия
                val nameDialog = TextInputDialog()
                nameDialog.title = "Новое оборудование"
                nameDialog.headerText = "Введите диспетчерское наименование"
                nameDialog.contentText = "Наименование:"
                nameDialog.editor?.text = ""

                val nameResult = nameDialog.showAndWait()
                if (nameResult.isPresent) {
                    val name = nameResult.get().trim()
                    if (name.isNotEmpty()) {
                        // Диалог для ячейки
                        val cellDialog = TextInputDialog()
                        cellDialog.title = "Номер ячейки"
                        cellDialog.headerText = "Введите номер ячейки (опционально)"
                        cellDialog.contentText = "Ячейка:"
                        cellDialog.editor?.text = ""

                        val cellResult = cellDialog.showAndWait()
                        val cell = cellResult.orElse("").trim()

                        // Диалог для типа
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

                            // Диалог для выбора размера
                            val sizeDialog = ChoiceDialog("normal", listOf("small", "normal", "large"))
                            sizeDialog.title = "Размер метки"
                            sizeDialog.headerText = "Выберите размер метки на схеме"
                            sizeDialog.contentText = "Размер (small - для ОРУ-220/35, large - для 500 кВ):"

                            val sizeResult = sizeDialog.showAndWait()
                            if (sizeResult.isPresent) {
                                val size = sizeResult.get()

                                val id = "equipment-${System.currentTimeMillis()}"
                                equipmentCounter++

                                // Экранируем строки для JavaScript
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
                                
                                var tooltipText = '$escapedName';
                                if ('$escapedCell'.length > 0) {
                                    tooltipText = '$escapedName (яч.$escapedCell)';
                                }
                                
                                marker.innerHTML = '<div class="dot">$typeLabel</div><span class="tooltip-text">' + tooltipText + '</span>';
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
                                console.log('✅ Добавлено оборудование: $escapedName (размер: $size)');
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

                    // 2. Диалог для ячейки
                    val cellDialog = TextInputDialog()
                    cellDialog.title = "Номер ячейки"
                    cellDialog.headerText = "Введите номер ячейки"
                    cellDialog.contentText = "Ячейка:"
                    cellDialog.editor?.text = currentCell

                    val cellResult = cellDialog.showAndWait()
                    val newCell = cellResult.orElse("").trim()

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
                                // Обновляем классы (тип + размер)
                                marker.className = 'equipment-marker $escapedType $escapedSize';
                                
                                var dot = marker.querySelector('.dot');
                                if (dot) dot.textContent = '$escapedLetter';
                                
                                var tooltip = marker.querySelector('.tooltip-text');
                                if (tooltip) {
                                    var text = '$escapedName';
                                    if ('$escapedCell'.length > 0) {
                                        text = '$escapedName (яч.$escapedCell)';
                                    }
                                    tooltip.textContent = text;
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
                        }
                    }
                }
            }
        }
    }

    private fun loadEquipment(): List<EquipmentData> {
        return database.loadAllEquipment()
    }

    // Вместо deleteEquipment()
    private fun deleteEquipment(equipmentId: String) {
        // Удаляем из БД
        database.deleteById(equipmentId)

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
                if (index !== -1) window.equipment.splice(index, 1);
            }
        })();
    """.trimIndent())

        equipmentCounter = loadEquipment().size
        showInfo("Оборудование удалено")
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

                // Колонки
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

                tableView.columns.addAll(colNumber, colName, colType, colCell, colX, colY, colId)

                // Преобразование EquipmentData в EquipmentTableItem
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

                // Функция обновления таблицы
                fun updateTable(data: List<EquipmentData>) {
                    val items = toTableItems(data)
                    tableView.items = javafx.collections.FXCollections.observableArrayList(items)
                    countLabel.text = "Показано: ${data.size} из ${allEquipment.size}"
                }

                // Функция применения фильтров
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
                                    it.type.contains(searchText, ignoreCase = true) ||
                                    it.cell.contains(searchText, ignoreCase = true)
                        }
                    }

                    println("📊 Найдено: ${filtered.size} из ${allEquipment.size}")
                    updateTable(filtered)
                }

                searchField.textProperty().addListener { _, _, _ ->
                    applyFilter()
                }

                val applyBtn = Button("Применить")
                applyBtn.style = "-fx-background-color: #007bff; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 4px 16px; -fx-background-radius: 4px;"
                applyBtn.setOnAction { applyFilter() }

                val resetBtn = Button("Сбросить")
                resetBtn.style = "-fx-background-color: #6c757d; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 4px 16px; -fx-background-radius: 4px;"
                resetBtn.setOnAction {
                    typeFilter.value = "Все типы"
                    searchField.clear()
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

                tableView.setRowFactory {
                    val row = javafx.scene.control.TableRow<EquipmentTableItem>()
                    row.styleProperty().bind(
                        javafx.beans.binding.Bindings.`when`(row.hoverProperty())
                            .then("-fx-background-color: #e8f4f8;")
                            .otherwise("-fx-background-color: transparent;")
                    )
                    row
                }

                applyFilter()

                val buttonPanel = HBox(10.0)
                buttonPanel.alignment = Pos.CENTER_RIGHT
                buttonPanel.style = "-fx-padding: 10px 0 0 0;"

                val exportBtn = Button("📤 Экспорт CSV")
                exportBtn.style = "-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6px 20px; -fx-background-radius: 4px;"
                exportBtn.setOnAction { exportEquipmentToCsv() }

                val closeBtn = Button("✕ Закрыть")
                closeBtn.style = "-fx-background-color: #dc3545; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6px 20px; -fx-background-radius: 4px;"
                closeBtn.setOnAction { (closeBtn.scene.window as Stage).close() }

                filterPanel.children.addAll(
                    filterLabel, typeFilter, applyBtn, resetBtn, countLabel, searchField
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
            showEquipmentImage(result)
        }
    }

    private fun showEquipmentImage(equipmentId: String) {
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

                val infoLabel = Label()
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
                showError("Не удалось загрузить изображение")
            }
        } else {
            showError("Изображение не найдено: $imagePath")
        }
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