package org.example.defectmap

import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.stage.Stage

class DefectMapApp : Application() {  // <-- переименовали класс
    override fun start(stage: Stage) {
        val fxmlLoader = FXMLLoader(DefectMapApp::class.java.getResource("defectmap-view.fxml"))  // <-- обновили ссылку
        val scene = Scene(fxmlLoader.load())

        stage.title = "ДефектыПС - Карта дефектов"
        stage.scene = scene
        stage.isMaximized = true
        stage.show()
    }
}

fun main() {
    Application.launch(DefectMapApp::class.java)  // <-- обновили ссылку
}