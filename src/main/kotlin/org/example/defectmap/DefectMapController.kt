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

class DefectMapController {

    @FXML
    private lateinit var webView: WebView

    private var zoomLevel = 1.0
    private val MIN_ZOOM = 0.5
    private val MAX_ZOOM = 5.0
    private val ZOOM_STEP = 0.1

    private var isDragging = false
    private var lastMouseX = 0.0
    private var lastMouseY = 0.0
    private var currentTranslateX = 0.0
    private var currentTranslateY = 0.0

    @FXML
    private fun initialize() {
        loadSvgIntoWebView()

        webView.engine.getLoadWorker().stateProperty().addListener { _, _, newState ->
            if (newState == Worker.State.SUCCEEDED) {
                Platform.runLater {
                    setupZoom()
                    setupPan()
                    setupClickHandler()
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
              /* Метки - позиционируются относительно image-wrapper */
              .marker {
                position: absolute;
                cursor: pointer;
                z-index: 10;
                transition: transform 0.2s ease;
                pointer-events: auto;
                transform: translate(-50%, -50%);
                /* Важно: метки не должны масштабироваться отдельно */
                width: 28px;
                height: 28px;
              }
              .marker:hover {
                transform: translate(-50%, -50%) scale(1.3);
              }
              .marker .circle {
                width: 28px;
                height: 28px;
                background: #ff4444;
                border: 3px solid white;
                border-radius: 50%;
                display: flex;
                justify-content: center;
                align-items: center;
                color: white;
                font-weight: bold;
                font-size: 11px;
                box-shadow: 0 4px 15px rgba(255, 68, 68, 0.5);
                animation: pulse 2s infinite;
              }
              .marker .circle.green {
                background: #44bb44;
                box-shadow: 0 4px 15px rgba(68, 187, 68, 0.5);
              }
              .marker .label {
                position: absolute;
                bottom: -20px;
                left: 50%;
                transform: translateX(-50%);
                background: rgba(0,0,0,0.7);
                color: white;
                padding: 2px 8px;
                border-radius: 4px;
                font-size: 10px;
                white-space: nowrap;
                pointer-events: none;
              }
              @keyframes pulse {
                0% { box-shadow: 0 0 0 0 rgba(255, 68, 68, 0.7); }
                70% { box-shadow: 0 0 0 15px rgba(255, 68, 68, 0); }
                100% { box-shadow: 0 0 0 0 rgba(255, 68, 68, 0); }
              }
            </style>
          </head>
          <body>
            <div id="container">
              <div id="image-wrapper">
                <img id="image" src="${svgFile.toExternalForm()}" alt="Schema" />
                
                <!-- МЕТКИ: координаты в процентах от изображения -->
                <!-- Важно: все метки внутри image-wrapper -->
                <div class="marker" id="marker-1" data-id="1" 
                     style="left: 45%; top: 35%;">
                  <div class="circle">В</div>
                  <div class="label">В-500</div>
                </div>
                
                <div class="marker" id="marker-2" data-id="2" 
                     style="left: 55%; top: 45%;">
                  <div class="circle green">В</div>
                  <div class="label">В-500</div>
                </div>
                
                <div class="marker" id="marker-3" data-id="3" 
                     style="left: 65%; top: 55%;">
                  <div class="circle">В</div>
                  <div class="label">В-500</div>
                </div>
                
                <div class="marker" id="marker-4" data-id="4" 
                     style="left: 20%; top: 70%;">
                  <div class="circle">М</div>
                  <div class="label">М-1</div>
                </div>
                
                <div class="marker" id="marker-5" data-id="5" 
                     style="left: 75%; top: 20%;">
                  <div class="circle">М</div>
                  <div class="label">М-2</div>
                </div>
                
                <div class="marker" id="marker-6" data-id="6" 
                     style="left: 30%; top: 25%;">
                  <div class="circle">Т</div>
                  <div class="label">Т-1</div>
                </div>
                
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

    private fun setupZoom() {
        webView.setOnScroll { event: ScrollEvent ->
            val delta = if (event.deltaY > 0) ZOOM_STEP else -ZOOM_STEP
            val newZoom = (zoomLevel + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)

            if (newZoom != zoomLevel) {
                zoomLevel = newZoom

                // Масштабируем ВЕСЬ wrapper с изображением и метками
                webView.engine.executeScript("""
                    (function() {
                        var wrapper = document.getElementById('image-wrapper');
                        var img = document.getElementById('image');
                        var zoom = $zoomLevel;
                        
                        // Масштабируем wrapper
                        wrapper.style.transform = 'scale(' + zoom + ')';
                        wrapper.style.transformOrigin = 'center center';
                        
                        // Убираем масштаб у изображения (оно масштабируется через wrapper)
                        img.style.transform = 'scale(1)';
                    })();
                """.trimIndent())
            }

            event.consume()
        }
    }

    private fun setupPan() {
        webView.setOnMousePressed { event: MouseEvent ->
            if (event.isPrimaryButtonDown) {
                isDragging = true
                lastMouseX = event.x
                lastMouseY = event.y

                webView.engine.executeScript("""
                    document.getElementById('container').classList.add('dragging');
                """.trimIndent())
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

                // Перемещаем wrapper
                webView.engine.executeScript("""
                    (function() {
                        var wrapper = document.getElementById('image-wrapper');
                        var translateX = ${currentTranslateX};
                        var translateY = ${currentTranslateY};
                        var zoom = ${zoomLevel};
                        
                        wrapper.style.transform = 'translate(' + translateX + 'px, ' + translateY + 'px) scale(' + zoom + ')';
                        wrapper.style.transformOrigin = 'center center';
                    })();
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
                    (function() {
                        var wrapper = document.getElementById('image-wrapper');
                        wrapper.style.transform = 'translate(0px, 0px) scale(1)';
                        wrapper.style.transformOrigin = 'center center';
                    })();
                """.trimIndent())
            }
        }
    }

    private fun setupClickHandler() {
        webView.setOnMouseClicked { event: MouseEvent ->
            if (event.clickCount == 1) {
                handleMarkerClick(event.x, event.y)
            }
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
                    
                    // Проверяем попадание в область маркера
                    var padding = 15;
                    if (clickX >= markerRect.left - rect.left - padding &&
                        clickX <= markerRect.right - rect.left + padding &&
                        clickY >= markerRect.top - rect.top - padding &&
                        clickY <= markerRect.bottom - rect.top + padding) {
                        return marker.dataset.id;
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
        val imageUrl = javaClass.getResource("/org/example/defectmap/breaker_500kv.jpg")

        if (imageUrl != null) {
            try {
                val image = Image(imageUrl.toExternalForm())
                val imageView = ImageView(image)
                imageView.isPreserveRatio = true
                imageView.fitWidth = 600.0
                imageView.fitHeight = 400.0

                val closeButton = Button("✕ Закрыть")
                closeButton.style = "-fx-font-size: 14px; -fx-background-color: #ff4444; -fx-text-fill: white; -fx-padding: 8px 20px; -fx-background-radius: 6px;"

                val layout = VBox(15.0, imageView, closeButton)
                layout.alignment = Pos.CENTER
                layout.style = "-fx-background-color: white; -fx-padding: 20px; -fx-background-radius: 12px; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 20, 0, 0, 0);"

                val popupStage = Stage()
                popupStage.title = "Выключатель 500 кВ"
                popupStage.scene = Scene(layout, 680.0, 520.0)
                popupStage.isResizable = false

                closeButton.setOnAction {
                    popupStage.close()
                }

                popupStage.scene.setOnKeyPressed { event ->
                    if (event.code == KeyCode.ESCAPE) {
                        popupStage.close()
                    }
                }

                popupStage.showAndWait()

            } catch (e: Exception) {
                println("Error loading image: ${e.message}")
                showError("Не удалось загрузить изображение выключателя")
            }
        } else {
            showError("Изображение выключателя не найдено!")
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