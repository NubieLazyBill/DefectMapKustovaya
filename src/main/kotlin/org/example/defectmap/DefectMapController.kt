package org.example.defectmap

import javafx.fxml.FXML
import javafx.scene.web.WebView
import javafx.scene.input.ScrollEvent
import javafx.scene.input.MouseEvent
import javafx.concurrent.Worker
import javafx.application.Platform

class DefectMapController {

    @FXML
    private lateinit var webView: WebView

    private var zoomLevel = 1.0
    private val MIN_ZOOM = 0.5
    private val MAX_ZOOM = 5.0
    private val ZOOM_STEP = 0.1

    // Для перетаскивания
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
                  }
                  #container.dragging {
                    cursor: grabbing;
                  }
                  #image {
                    max-width: 100%;
                    max-height: 100%;
                    object-fit: contain;
                    transition: transform 0.15s ease;
                    transform-origin: center center;
                    will-change: transform;
                  }
                </style>
              </head>
              <body>
                <div id="container">
                  <img id="image" src="${svgFile.toExternalForm()}" alt="Schema" />
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

                // Масштабируем через CSS
                webView.engine.executeScript("""
                    document.getElementById('image').style.transform = 'scale($zoomLevel) translate(${currentTranslateX}px, ${currentTranslateY}px)';
                    document.getElementById('image').style.transformOrigin = '${event.x / webView.width * 100}% ${event.y / webView.height * 100}%';
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

                webView.engine.executeScript("""
                    (function() {
                        var img = document.getElementById('image');
                        var translateX = ${currentTranslateX};
                        var translateY = ${currentTranslateY};
                        var zoom = ${zoomLevel};
                        
                        img.style.transform = 'scale(' + zoom + ') translate(' + translateX + 'px, ' + translateY + 'px)';
                        img.style.transformOrigin = 'center center';
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

        // Сброс позиции двойным кликом
        webView.setOnMouseClicked { event: MouseEvent ->
            if (event.clickCount == 2) {
                zoomLevel = 1.0
                currentTranslateX = 0.0
                currentTranslateY = 0.0

                webView.engine.executeScript("""
                    (function() {
                        var img = document.getElementById('image');
                        img.style.transform = 'scale(1) translate(0px, 0px)';
                        img.style.transformOrigin = 'center center';
                    })();
                """.trimIndent())
            }
        }
    }
}