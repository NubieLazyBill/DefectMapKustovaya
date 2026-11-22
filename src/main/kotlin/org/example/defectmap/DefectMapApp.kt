package org.example.defectmap

import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.stage.Stage
import org.example.defectmap.platform.PlatformInfo
import org.example.defectmap.platform.UiFonts

class DefectMapApp : Application() {

    override fun start(stage: Stage) {
        // Подложенные TTF (если есть в ресурсах) — до создания сцены
        UiFonts.installBundledFonts()

        val fxmlLoader = FXMLLoader(DefectMapApp::class.java.getResource("defectmap-view.fxml"))
        val scene = Scene(fxmlLoader.load())
        applyBaseFont(scene)

        stage.title = "ДефектыПС - Карта дефектов"
        stage.scene = scene
        stage.isMaximized = true
        stage.minWidth = 1000.0
        stage.minHeight = 640.0
        stage.show()

        println("🪟 Окно открыто (${PlatformInfo.name})")
    }

    /** На Linux подставляем метрически совместимый аналог, чтобы не «прыгали» размеры. */
    private fun applyBaseFont(scene: Scene) {
        val family = UiFonts.sansFamily ?: return
        if (family.equals("Arial", ignoreCase = true)) return
        runCatching {
            scene.root.style = "-fx-font-family: \"$family\";"
            println("🔤 Базовый шрифт интерфейса: $family")
        }
    }

    companion object {
        /**
         * Точка входа на случай, если IDE или другой лаунчер запускает именно
         * класс `DefectMapApp`. Делегируем в [Launcher], чтобы сохранить единое
         * поведение (аргументы, баннер, кэш нативов).
         *
         * Для `java -jar` по-прежнему используется [Launcher]: JDK не разрешает
         * запускать класс-наследник Application из classpath.
         */
        @JvmStatic
        fun main(args: Array<String>) {
            Launcher.main(args)
        }
    }
}

/**
 * Точка входа для запуска из IDE.
 *
 * IntelliJ IDEA создаёт run-конфигурацию на top-level `main` (класс
 * `DefectMapAppKt`), поэтому функция сохранена для совместимости и просто
 * делегирует в [Launcher]. Именно по этой причине файл называется
 * DefectMapApp.kt, хотя основной класс приложения — DefectMapApp.
 */
fun main(args: Array<String>) {
    Launcher.main(args)
}
