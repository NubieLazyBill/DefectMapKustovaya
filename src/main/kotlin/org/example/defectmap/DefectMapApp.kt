package org.example.defectmap

import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.stage.Stage

class HelloApplication : Application() {
    override fun start(stage: Stage) {
        val fxmlLoader = FXMLLoader(HelloApplication::class.java.getResource("defectmap-view.fxml"))
        val scene = Scene(fxmlLoader.load())

        stage.title = "ДефектыПС - Карта дефектов"
        stage.scene = scene
        stage.isMaximized = true  // Открыть на весь экран
        stage.show()
    }
}

fun main() {
    Application.launch(HelloApplication::class.java)
}