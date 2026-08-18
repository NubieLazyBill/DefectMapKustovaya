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

class DefectMapController {

    @FXML
    private lateinit var webView: WebView

    @FXML
    private lateinit var addMarkerBtn: Button

    @FXML
    private lateinit var editModeBtn: Button

    @FXML
    private lateinit var saveMarkersBtn: Button

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
    private var markerCounter = 0

    private val markersFile: File by lazy {
        val userHome = System.getProperty("user.home")
        val appDir = File(userHome, ".defectmap")
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
        File(appDir, "markers.json")
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
                    setupZoom()
                    setupPan()
                    setupClickHandler()
                    setupButtons()
                    initMarkers()
                }
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
              
              /* КРУГЛЫЕ МЕТКИ */
              .marker {
                position: absolute;
                cursor: pointer;
                z-index: 10;
                pointer-events: auto;
                transform: translate(-50%, -50%);
                width: 28px;
                height: 28px;
                transition: transform 0.2s ease;
              }
              .marker:hover {
                transform: translate(-50%, -50%) scale(1.3);
              }
              .marker .dot {
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
              .marker.breaker .dot { background: #ff4444; }
              .marker.disconnector .dot { background: #ff8800; }
              .marker.transformer .dot { background: #44bb44; }
              .marker.lightning .dot { background: #ffcc00; color: #333; }
              .marker.other .dot { background: #8888ff; }
              
              .marker .tooltip-text {
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
              .marker .tooltip-text::after {
                content: '';
                position: absolute;
                top: 100%;
                left: 50%;
                transform: translateX(-50%);
                border: 5px solid transparent;
                border-top-color: rgba(0, 0, 0, 0.85);
              }
              .marker:hover .tooltip-text {
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
                <div id="markers-container"></div>
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

    private fun initMarkers() {
        val savedMarkers = loadMarkers()

        if (savedMarkers.isNotEmpty()) {
            val markersJson = gson.toJson(savedMarkers)
            webView.engine.executeScript("""
                window.markers = [];
                window.markerIdCounter = 0;
                
                var savedData = $markersJson;
                savedData.forEach(function(item) {
                    var container = document.getElementById('markers-container');
                    var marker = document.createElement('div');
                    marker.className = 'marker ' + item.type;
                    marker.id = item.id;
                    marker.dataset.id = item.id;
                    marker.style.left = item.left + '%';
                    marker.style.top = item.top + '%';
                    
                    marker.innerHTML = '<div class=\"dot\">' + item.letter + '</div><span class=\"tooltip-text\">' + item.name + '</span>';
                    
                    container.appendChild(marker);
                    
                    window.markers.push({
                        id: item.id,
                        left: item.left,
                        top: item.top,
                        type: item.type,
                        name: item.name,
                        letter: item.letter
                    });
                    window.markerIdCounter = Math.max(window.markerIdCounter, parseInt(item.id.split('-')[1]) + 1);
                });
                
                console.log('✅ Загружено меток: ' + savedData.length);
            """.trimIndent())

            markerCounter = savedMarkers.size
        } else {
            webView.engine.executeScript("""
                window.markers = [];
                window.markerIdCounter = 0;
                console.log('✅ Режим меток инициализирован');
            """.trimIndent())
        }
    }

    private fun setupButtons() {
        addMarkerBtn.setOnAction {
            toggleEditMode(true)
            //showInfo("Кликните на схеме, чтобы добавить метку")
        }

        editModeBtn.setOnAction {
            toggleEditMode(!isEditMode)
        }

        saveMarkersBtn.setOnAction {
            saveMarkers()
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
            } else if (isEditMode) {
                addMarkerAtPosition(event.x, event.y)
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
        webView.setOnMouseClicked { event: MouseEvent ->
            if (event.clickCount == 1 && !isEditMode) {
                handleMarkerClick(event.x, event.y)
            }
        }
    }

    private fun addMarkerAtPosition(x: Double, y: Double) {
        println("📍 Добавление метки: x=$x, y=$y")

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
                dialog.title = "Новая метка"
                dialog.headerText = "Введите диспетчерское наименование"
                dialog.contentText = "Наименование:"

                val result2 = dialog.showAndWait()
                if (result2.isPresent) {
                    val name = result2.get()
                    if (name.isNotEmpty()) {
                        // ПОЛНЫЙ СПИСОК ТИПОВ ОБОРУДОВАНИЯ
// ============================================================
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
// ============================================================

                        typeDialog.title = "Тип оборудования"
                        typeDialog.headerText = "Выберите тип"
                        typeDialog.contentText = "Тип:"

                        val typeResult = typeDialog.showAndWait()
                        if (typeResult.isPresent) {
                            val type = typeResult.get()
                            val typeLabel = when (type) {
                                "breaker" -> "В"
                                "disconnector" -> "Р"
                                "transformer" -> "Т"
                                "lightning" -> "М"
                                else -> "О"
                            }

                            val id = "marker-${System.currentTimeMillis()}"
                            markerCounter++

                            webView.engine.executeScript("""
                            (function() {
                                var container = document.getElementById('markers-container');
                                var marker = document.createElement('div');
                                marker.className = 'marker $type';
                                marker.id = '$id';
                                marker.dataset.id = '${markerCounter}';
                                marker.style.left = '${left}%';
                                marker.style.top = '${top}%';
                                
                                marker.innerHTML = '<div class=\"dot\">${typeLabel}</div><span class=\"tooltip-text\">${name}</span>';
                                
                                container.appendChild(marker);
                                
                                if (!window.markers) window.markers = [];
                                window.markers.push({
                                    id: '$id',
                                    left: parseFloat('${left}'),
                                    top: parseFloat('${top}'),
                                    type: '$type',
                                    name: '${name}',
                                    letter: '$typeLabel'
                                });
                                
                                console.log('✅ Добавлена метка: ${name}');
                            })();
                        """.trimIndent())

                            saveMarkers()
                            // Убираем showInfo - тихо сохраняем
                            // showInfo("Метка добавлена: $name")
                        }
                    }
                }
            }
        } else {
            println("❌ Ошибка: результат null")
            // showInfo("Не удалось определить координаты. Попробуйте еще раз.")
        }
    }

    private fun saveMarkers() {
        val result = webView.engine.executeScript("""
            JSON.stringify(window.markers || [])
        """.trimIndent()) as? String

        if (result != null) {
            try {
                val type = object : TypeToken<List<MarkerData>>() {}.type
                val markers: List<MarkerData> = gson.fromJson(result, type)
                val json = gson.toJson(markers)
                markersFile.writeText(json)
                println("💾 Сохранено меток: ${markers.size}")
                //showInfo("Метки сохранены! (${markers.size} шт.)")
            } catch (e: Exception) {
                println("❌ Ошибка сохранения: ${e.message}")
                showError("Ошибка сохранения: ${e.message}")
            }
        } else {
            showError("Нет данных для сохранения")
        }
    }

    private fun loadMarkers(): List<MarkerData> {
        return try {
            if (markersFile.exists()) {
                val json = markersFile.readText()
                val type = object : TypeToken<List<MarkerData>>() {}.type
                gson.fromJson(json, type)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            println("❌ Ошибка загрузки: ${e.message}")
            emptyList()
        }
    }

    private fun handleMarkerClick(x: Double, y: Double) {
        val result = webView.engine.executeScript("""
        (function() {
            var container = document.getElementById('container');
            var rect = container.getBoundingClientRect();
            var markers = document.querySelectorAll('.marker');
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

        val markerId = result as? String
        if (markerId != null) {
            showBreakerImage(markerId)
        }
    }

    private fun showBreakerImage(markerId: String) {
        // Получаем информацию о метке
        val markerInfo = webView.engine.executeScript("""
        (function() {
            var markers = window.markers || [];
            for (var i = 0; i < markers.length; i++) {
                if (markers[i].id === '$markerId') {
                    return markers[i];
                }
            }
            return null;
        })();
    """.trimIndent()) as? Map<*, *>

        // Определяем путь к картинке в зависимости от типа и названия
        var imagePath = "/org/example/defectmap/ВВБК-500.jfif" // по умолчанию

        if (markerInfo != null) {
            val type = markerInfo["type"] as? String ?: ""
            val name = markerInfo["name"] as? String ?: ""

            // Определяем картинку по типу
            imagePath = when {
                // Выключатели
                type == "breaker" || type == "breaker_500" || type == "breaker_220" || type == "breaker_110" ||
                        type == "breaker_35" || type == "breaker_10" || type == "sf6_breaker" || type == "vacuum_breaker" -> {
                    "/org/example/defectmap/breaker_500kv.jpg"
                }
                /*// Разъединители
                type == "disconnector" || type == "disconnector_500" || type == "disconnector_220" ||
                        type == "disconnector_110" || type == "disconnector_35" || type == "disconnector_10" -> {
                    "/org/example/defectmap/disconnector.jpg"
                }
                // Трансформаторы
                type == "transformer" || type == "transformer_500" || type == "transformer_220" ||
                        type == "transformer_110" || type == "transformer_35" || type == "transformer_10" ||
                        type == "autotransformer" || type == "autotransformer_500" || type == "autotransformer_220" -> {
                    "/org/example/defectmap/transformer.jpg"
                }
                // Молниеотводы
                type == "lightning" || type == "lightning_rod" -> {
                    "/org/example/defectmap/lightning_rod.jpg"
                }
                // ОПН
                type == "opn_500" || type == "opn_220" || type == "opn_110" || type == "opn_35" || type == "opn_10" -> {
                    "/org/example/defectmap/opn.jpg"
                }
                // ТН
                type == "tn_500" || type == "tn_220" || type == "tn_110" || type == "tn_35" || type == "tn_10" -> {
                    "/org/example/defectmap/tn.jpg"
                }
                // ТТ
                type == "tt_500" || type == "tt_220" || type == "tt_110" || type == "tt_35" || type == "tt_10" -> {
                    "/org/example/defectmap/tt.jpg"
                }
                // АТГ
                type == "atg_500" || type == "atg_220" -> {
                    "/org/example/defectmap/atg.jpg"
                }
                // Реакторы
                type == "reactor_500" || type == "reactor_220" || type == "shunt_500" || type == "shunt_220" -> {
                    "/org/example/defectmap/reactor.jpg"
                }
                // Конденсаторы
                type == "capacitor" || type == "coupling_capacitor" || type == "ks_500" || type == "ks_220" -> {
                    "/org/example/defectmap/capacitor.jpg"
                }*/
                // Другое
                else -> "/org/example/defectmap/equipment.jpg"
            }
        }

        // Загружаем картинку
        val imageUrl = javaClass.getResource(imagePath)
        if (imageUrl != null) {
            try {
                val image = Image(imageUrl.toExternalForm())
                val imageView = ImageView(image)
                imageView.isPreserveRatio = true
                imageView.fitWidth = 600.0
                imageView.fitHeight = 400.0

                // Информация об оборудовании
                val infoLabel = javafx.scene.control.Label()
                if (markerInfo != null) {
                    val name = markerInfo["name"] as? String ?: "Оборудование"
                    val type = markerInfo["type"] as? String ?: ""
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
                popupStage.title = markerInfo?.let { it["name"] as? String } ?: "Информация об оборудовании"
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

data class MarkerData(
    val id: String,
    val left: Double,
    val top: Double,
    val type: String,
    val name: String,
    val letter: String
)