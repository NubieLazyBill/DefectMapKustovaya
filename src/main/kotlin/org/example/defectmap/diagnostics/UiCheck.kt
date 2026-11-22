package org.example.defectmap.diagnostics

import javafx.application.Platform
import javafx.concurrent.Worker
import javafx.print.Printer
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.text.Font
import javafx.scene.web.WebView
import javafx.stage.Stage
import org.example.defectmap.Schemas
import org.example.defectmap.platform.PlatformInfo
import org.example.defectmap.platform.UiFonts
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * `--uicheck`: проверка, что JavaFX реально поднимается и WebView рисует схему.
 *
 * Это то, что `--selftest` проверить не может: загрузка нативных библиотек
 * (libglass.so, libjfxwebkit.so), WebKit, шрифты в рендере и наличие принтеров
 * через JavaFX. На Linux требует графической сессии — в CI запускается под `xvfb-run`.
 *
 * Примеры:
 *   xvfb-run -a java -jar DefectMap.jar --uicheck
 *   xvfb-run -a java -jar DefectMap.jar --uicheck --show --snapshot build/tmp/ui.png
 */
object UiCheck {

    fun run(args: List<String>): Int {
        val snapshotPath = args
            .windowedArg("--snapshot")
            ?.let { File(it).absoluteFile }
        val showStage = args.contains("--show")
        val timeoutSeconds = args.windowedArg("--timeout")?.toLongOrNull() ?: 60L

        println()
        println("=".repeat(78))
        println("DefectMap — проверка JavaFX/WebView (--uicheck)")
        println("=".repeat(78))
        println("  ${PlatformInfo.describe()}")
        println("  show=$showStage, snapshot=${snapshotPath?.absolutePath ?: "нет"}, таймаут=${timeoutSeconds}s")

        // Кэш нативных библиотек — строго до запуска JavaFX
        org.example.defectmap.platform.FxNativeCache.configure()
            ?.let { println("  кэш нативов JavaFX: $it") }

        val schema = Schemas.find("kustovaya")
        if (schema == null) {
            System.err.println("  [FAIL] схема не найдена — сначала запустите --selftest")
            return 1
        }
        println("  Схема: ${schema.source} (${schema.sizeKb} КБ)")

        val html = buildHtml(schema.content)

        val loaded = CountDownLatch(1)
        val inspected = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val webViewRef = AtomicReference<WebView?>(null)
        val stageRef = AtomicReference<Stage?>(null)
        val result = AtomicReference("проверка не выполнена")
        var ok = false

        try {
            Platform.startup {
                try {
                    UiFonts.installBundledFonts()

                    val webView = WebView()
                    webViewRef.set(webView)

                    val scene = Scene(StackPane(webView), 1280.0, 800.0)
                    webView.engine.loadContent(html)

                    if (showStage) {
                        val stage = Stage()
                        stage.title = "DefectMap UI check"
                        stage.scene = scene
                        stage.show()
                        stageRef.set(stage)
                    }

                    webView.engine.loadWorker.stateProperty().addListener { _, _, state ->
                        if (state == Worker.State.SUCCEEDED || state == Worker.State.FAILED) {
                            if (state == Worker.State.FAILED) {
                                failure.set("WebEngine: ${webView.engine.loadWorker.exception?.message ?: "FAILED"}")
                            }
                            loaded.countDown()
                        }
                    }

                    // Снимаем отчёт после загрузки (в отдельной задаче, без блокировки FX-потока)
                    val inspector = object : Runnable {
                        override fun run() {
                            try {
                                val svgCount = (webView.engine.executeScript(
                                    "document.getElementsByTagName('svg').length"
                                ) as? Number)?.toInt() ?: -1
                                val textLength = (webView.engine.executeScript(
                                    "document.body.textContent.trim().length"
                                ) as? Number)?.toInt() ?: -1
                                val markerCount = (webView.engine.executeScript(
                                    "document.querySelectorAll('*').length"
                                ) as? Number)?.toInt() ?: -1

                                val families = runCatching { Font.getFamilies().size }.getOrDefault(0)
                                val printers = runCatching { Printer.getAllPrinters().size }.getOrDefault(0)
                                val sans = runCatching { UiFonts.sansFamily }.getOrNull()

                                println("  SVG-элементов в документе: $svgCount")
                                println("  Длина текста в документе: $textLength")
                                println("  Всего DOM-узлов: $markerCount")
                                println("  Семейств шрифтов JavaFX: $families; sans=$sans")
                                println("  Принтеров (JavaFX): $printers")

                                if (snapshotPath != null) {
                                    saveSnapshot(webView, snapshotPath)
                                }

                                ok = svgCount > 0 && textLength > 0
                                result.set(
                                    if (ok) {
                                        "WebView загрузил схему: SVG=$svgCount, текста=$textLength, шрифтов=$families, принтеров=$printers"
                                    } else {
                                        "WebView загрузился, но схема не отрисована (SVG=$svgCount, текста=$textLength)"
                                    }
                                )
                            } catch (t: Throwable) {
                                failure.set("${t::class.java.simpleName}: ${t.message}")
                                t.printStackTrace()
                            } finally {
                                inspected.countDown()
                            }
                        }
                    }

                    val waiter = Thread {
                        try {
                            if (loaded.await(timeoutSeconds, TimeUnit.SECONDS)) {
                                Platform.runLater {
                                    if (showStage) {
                                        // Даём WebView отрисовать первый кадр (иначе snapshot белый)
                                        val pause = javafx.animation.PauseTransition(
                                            javafx.util.Duration.millis(2000.0)
                                        )
                                        pause.setOnFinished { inspector.run() }
                                        pause.play()
                                    } else {
                                        inspector.run()
                                    }
                                }
                            } else {
                                failure.set("таймаут загрузки WebEngine ($timeoutSeconds c)")
                                inspected.countDown()
                            }
                        } catch (t: Throwable) {
                            failure.set("прервано: ${t.message}")
                            inspected.countDown()
                        }
                    }
                    waiter.isDaemon = true
                    waiter.start()
                } catch (t: Throwable) {
                    failure.set("${t::class.java.simpleName}: ${t.message}")
                    t.printStackTrace()
                    loaded.countDown()
                    inspected.countDown()
                }
            }

            if (!inspected.await(timeoutSeconds + 30, TimeUnit.SECONDS)) {
                failure.set("проверка не завершилась за ${timeoutSeconds + 30} c")
            }
        } catch (t: Throwable) {
            failure.set("не удалось запустить JavaFX: ${t::class.java.simpleName}: ${t.message}")
            t.printStackTrace()
        }

        runCatching { Platform.runLater { stageRef.get()?.close() } }
        runCatching {
            Platform.exit()
        }

        println()
        println("-".repeat(78))
        val error = failure.get()
        if (error != null) {
            println("  [FAIL] $error")
        } else if (ok) {
            println("  [OK] ${result.get()}")
        } else {
            println("  [FAIL] ${result.get()}")
        }
        println("-".repeat(78))

        val code = if (error == null && ok) 0 else 1
        exitProcess(code)
    }

    /** Минимальная HTML-обёртка: схема должна рисоваться так же, как в приложении. */
    private fun buildHtml(svg: String): String = """
<!DOCTYPE html>
<html>
  <head>
    <meta charset="utf-8">
    <style>
      html, body { margin: 0; padding: 0; background: #ffffff; font-family: ${UiFonts.cssSans}; }
      #wrap { width: 100%; height: 100%; }
      svg { width: 100%; height: 100%; display: block; }
    </style>
  </head>
  <body>
    <div id="wrap">$svg</div>
  </body>
</html>
""".trimIndent()

    private fun saveSnapshot(webView: WebView, target: File) {
        try {
            // WebView.snapshot иногда отдаёт белый кадр — тогда пробуем снимок всей сцены
            var percent = Snapshots.save(webView, target)
            if (percent < 0.5) {
                val root = webView.scene?.root
                if (root != null) {
                    val scenePercent = Snapshots.save(root, target)
                    if (scenePercent > percent) percent = scenePercent
                }
            }

            val image = ImageIO.read(target)
            println(
                "  Снимок: ${target.absolutePath} " +
                    "(${image?.width}x${image?.height}, небелых пикселей ${"%.1f".format(percent)}%)"
            )
            if (percent < 0.5) {
                println("  [WARN] снимок почти пустой — WebView не отрисовался в этом окружении (для CI это не ошибка)")
            }
        } catch (t: Throwable) {
            println("  [WARN] снимок не создан: ${t.message}")
        }
    }

    private fun List<String>.windowedArg(name: String): String? {
        val index = indexOf(name)
        return if (index >= 0 && index + 1 < size) this[index + 1] else null
    }
}
