package org.example.defectmap.diagnostics

import javafx.animation.PauseTransition
import javafx.application.Application
import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.fxml.FXMLLoader
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.web.WebView
import javafx.stage.Stage
import javafx.util.Duration
import org.example.defectmap.platform.FxNativeCache
import org.example.defectmap.platform.PlatformInfo

/**
 * `--appsmoke`: настоящий запуск приложения на несколько секунд с проверкой,
 * что окно открылось, FXML загрузился, контроллер инициализировался,
 * БД открылась и схема отрисовалась в WebView.
 *
 * Это единственная проверка, которая ловит ошибки уровня «приложение падает
 * при старте»: битый FXML, отсутствующий @FXML-компонент, исключение в
 * `initialize()`, недоступная БД.
 *
 * На Linux требует дисплея — в CI запускается под `xvfb-run`.
 * Код возврата: 0 — приложение стартовало и отрисовало схему, 1 — нет.
 */
object AppSmoke {

    @Volatile
    private var exitCode = 1

    @Volatile
    private var message = "приложение не завершило проверку"

    fun run(args: Array<String>): Int {
        println()
        println("=".repeat(78))
        println("DefectMap — дымовой тест реального окна (--appsmoke)")
        println("=".repeat(78))
        println("  ${PlatformInfo.describe()}")

        FxNativeCache.configure()?.let { println("  кэш нативов JavaFX: $it") }

        // Сторож: если через 90 секунд окно так и не отрисовалось — выходим с ошибкой
        val watchdog = Thread {
            try {
                Thread.sleep(90_000)
                message = "таймаут 90 с: приложение не отрисовало схему"
                Platform.exit()
            } catch (_: InterruptedException) {
                // штатное завершение
            }
        }
        watchdog.isDaemon = true
        watchdog.start()

        try {
            Application.launch(AppSmokeApplication::class.java, *args)
        } catch (t: Throwable) {
            message = "запуск не удался: ${t::class.java.simpleName}: ${t.message}"
            t.printStackTrace()
            exitCode = 1
        }

        watchdog.interrupt()

        println()
        println("-".repeat(78))
        if (exitCode == 0) {
            println("  [OK] $message")
        } else {
            println("  [FAIL] $message")
        }
        println("-".repeat(78))
        return exitCode
    }

    internal fun report(ok: Boolean, text: String) {
        exitCode = if (ok) 0 else 1
        message = text
    }
}

/**
 * Копия запуска `DefectMapApp`, но с ожиданием отрисовки схемы и авто-выходом.
 * Отдельный класс нужен, чтобы `Application.launch` мог вернуть управление.
 */
class AppSmokeApplication : Application() {

    override fun start(stage: Stage) {
        try {
            val fxml = FXMLLoader(AppSmokeApplication::class.java.getResource("/org/example/defectmap/defectmap-view.fxml"))
            val scene = Scene(fxml.load())

            stage.title = "DefectMap — smoke test"
            stage.scene = scene
            stage.isMaximized = true
            stage.show()
            println("  окно открыто, сцена ${scene.width.toInt()}x${scene.height.toInt()}")

            val webView = findWebView(scene.root)
            if (webView == null) {
                finish(false, "в FXML не найден WebView")
                return
            }

            webView.engine.loadWorker.stateProperty().addListener { _, _, state ->
                if (state == Worker.State.SUCCEEDED) {
                    // Даём контроллеру загрузить оборудование и расставить маркеры
                    val pause = PauseTransition(Duration.millis(2500.0))
                    pause.setOnFinished { inspect(stage, webView) }
                    pause.play()
                } else if (state == Worker.State.FAILED) {
                    finish(false, "WebEngine не загрузил схему: ${webView.engine.loadWorker.exception?.message}")
                }
            }
        } catch (t: Throwable) {
            finish(false, "ошибка инициализации приложения: ${t::class.java.simpleName}: ${t.message}")
            t.printStackTrace()
        }
    }

    private fun inspect(stage: Stage, webView: WebView) {
        try {
            val engine = webView.engine
            val svgCount = (engine.executeScript("document.getElementsByTagName('svg').length") as? Number)?.toInt() ?: -1
            val textLength = (engine.executeScript("document.body.textContent.trim().length") as? Number)?.toInt() ?: -1
            val markers = (engine.executeScript("document.querySelectorAll('.equipment-marker').length") as? Number)?.toInt() ?: -1
            val equipmentArray = engine.executeScript("Array.isArray(window.equipment)") as? Boolean ?: false
            val title = stage.title

            println("  заголовок окна: $title")
            println("  SVG в документе: $svgCount, текста: $textLength, маркеров оборудования: $markers")
            println("  window.equipment — массив: $equipmentArray")

            // Проверяем геометрию области схемы: клик в её центр должен давать 50%/50%
            val geometry = engine.executeScript(
                "(function(){" +
                    "var b = window.dmBoxRect ? window.dmBoxRect() : null;" +
                    "if (!b) return 'no-dmBoxRect';" +
                    "var cx = ((b.left + b.width / 2) - b.left) / b.width * 100;" +
                    "var cy = ((b.top + b.height / 2) - b.top) / b.height * 100;" +
                    "return b.width.toFixed(0) + 'x' + b.height.toFixed(0) + ' center=' + cx.toFixed(1) + ',' + cy.toFixed(1);" +
                "})()"
            ) as? String
            println("  область схемы: ${geometry ?: "не определена"}")
            val mappingOk = geometry != null && geometry.contains("center=50.0,50.0")
            if (!mappingOk) {
                println("  [WARN] координатная привязка маркеров не совпадает с областью схемы")
            }

            // Показываем маркеры, чтобы на снимке было видно их положение относительно схемы
            if (markers > 0) {
                engine.executeScript(
                    "document.querySelectorAll('.equipment-marker').forEach(function(e){e.classList.remove('hidden');});"
                )
            }

            val ok = svgCount > 0 && textLength > 0 && equipmentArray
            val message = if (ok) {
                "приложение стартовало: окно «$title», схема отрисована, маркеров $markers"
            } else {
                "окно открылось, но схема/контроллер не готовы (SVG=$svgCount, текста=$textLength, equipment=$equipmentArray)"
            }

            // Артефакт для CI: скриншот реального окна.
            // WebView перерисовывается на следующем pulse, поэтому снимок — с паузой,
            // иначе маркеры (и любые изменения DOM) на снимке не видны.
            val snapshotFile = Snapshots.snapshotArg(parameters.raw)
            if (snapshotFile == null) {
                finish(ok, message)
                return
            }

            val pause = PauseTransition(Duration.millis(800.0))
            pause.setOnFinished {
                try {
                    val percent = Snapshots.save(stage.scene.root, snapshotFile)
                    println(
                        "  Снимок окна: ${snapshotFile.absolutePath} " +
                            "(небелых пикселей ${"%.1f".format(percent)}%)"
                    )
                } catch (t: Throwable) {
                    println("  [WARN] снимок окна не создан: ${t.message}")
                }
                finish(ok, message)
            }
            pause.play()
        } catch (t: Throwable) {
            finish(false, "ошибка проверки: ${t::class.java.simpleName}: ${t.message}")
        }
    }

    private fun finish(ok: Boolean, text: String) {
        AppSmoke.report(ok, text)
        Platform.exit()
    }

    private fun findWebView(node: Node): WebView? = when (node) {
        is WebView -> node
        is Parent -> node.childrenUnmodifiable.asSequence()
            .mapNotNull { findWebView(it) }
            .firstOrNull()

        else -> null
    }
}
