package org.example.defectmap

import javafx.fxml.FXML
import javafx.scene.web.WebView

class HelloController {

    @FXML
    private lateinit var webView: WebView

    @FXML
    private fun initialize() {
        loadSvgIntoWebView()
    }

    private fun loadSvgIntoWebView() {
        val svgFile = javaClass.getResource("/org/example/defectmap/schema.svg")
        if (svgFile != null) {
            val html = """
                <!DOCTYPE html>
                <html>
                  <body>
                    <img src="${svgFile.toExternalForm()}" alt="Schema" style="width:100%; height:auto;" />
                  </body>
                </html>
            """.trimIndent()

            webView.engine.loadContent(html)
        } else {
            println("SVG file not found!")
        }
    }
}