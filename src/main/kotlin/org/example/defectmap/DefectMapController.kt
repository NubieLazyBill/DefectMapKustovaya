package org.example.defectmap

import javafx.fxml.FXML
import javafx.scene.web.WebView
import javafx.scene.input.ScrollEvent
import javafx.concurrent.Worker
import javafx.application.Platform

class DefectMapController {

    @FXML
    private lateinit var webView: WebView

    private var zoomLevel = 1.0
    private val MIN_ZOOM = 0.5
    private val MAX_ZOOM = 5.0
    private val ZOOM_STEP = 0.1

    @FXML
    private fun initialize() {
        loadSvgIntoWebView()

        webView.engine.getLoadWorker().stateProperty().addListener { _, _, newState ->
            if (newState == Worker.State.SUCCEEDED) {
                Platform.runLater {
                    setupZoom()
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
                  }
                  #image {
                    max-width: 100%;
                    max-height: 100%;
                    object-fit: contain;
                    transition: transform 0.15s ease;
                    transform-origin: center center;
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
                    document.getElementById('image').style.transform = 'scale($zoomLevel)';
                    document.getElementById('image').style.transformOrigin = '${event.x / webView.width * 100}% ${event.y / webView.height * 100}%';
                """.trimIndent())
            }

            event.consume()
        }
    }
}