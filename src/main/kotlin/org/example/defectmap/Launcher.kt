package org.example.defectmap

import javafx.application.Application
import org.example.defectmap.diagnostics.AppSmoke
import org.example.defectmap.diagnostics.DbMigrate
import org.example.defectmap.diagnostics.MarkerMigration
import org.example.defectmap.diagnostics.SelfTest
import org.example.defectmap.diagnostics.UiCheck
import org.example.defectmap.platform.PlatformInfo
import kotlin.system.exitProcess

/**
 * Единственная точка входа приложения.
 *
 * ВАЖНО: этот класс **не наследует** `javafx.application.Application`.
 * Иначе `java -jar DefectMap.jar` падает с
 * «JavaFX runtime components are missing, and are required to run this application»:
 * лаунчер JDK требует, чтобы JavaFX был именованным модулем, а в fat JAR он в classpath.
 * Класс-обёртка обходит эту проверку — именно она указана в `Main-Class` манифеста.
 *
 * Дополнительно здесь разбираются служебные команды:
 *   --selftest  проверка окружения без GUI (код возврата 0/1)
 *   --uicheck   проверка JavaFX/WebView (нужен дисплей или xvfb-run)
 */
object Launcher {

    val VERSION: String by lazy {
        Launcher::class.java.`package`?.implementationVersion?.takeIf { it.isNotBlank() } ?: "dev"
    }

    @JvmStatic
    fun main(args: Array<String>) {
        configureLogging()

        val command = args.firstOrNull()?.lowercase() ?: ""

        when (command) {
            "--selftest", "-t", "selftest" -> exitProcess(SelfTest.run(args.drop(1)))
            "--uicheck", "uicheck" -> UiCheck.run(args.drop(1))
            "--appsmoke", "appsmoke" -> exitProcess(AppSmoke.run(args.drop(1).toTypedArray()))
            "--fix-markers" -> exitProcess(MarkerMigration.run(args.drop(1)))
            "--migrate-db", "migrate-db" -> exitProcess(DbMigrate.run(args.drop(1)))
            "--version", "-v" -> {
                println("DefectMap $VERSION")
                println(PlatformInfo.describe())
                return
            }
            "--paths", "paths" -> {
                println("DefectMap $VERSION")
                println("  ${AppPaths.describe()}")
                println("  каталог данных: ${AppPaths.dataDir.absolutePath}")
                println("  appDir доступен для записи: ${AppPaths.appDir.canWrite()}")
                println("  APPIMAGE=${System.getenv("APPIMAGE")}")
                println("  APPDIR=${System.getenv("APPDIR")}")
                println("  HOME=${System.getProperty("user.home")}")
                return
            }
            "--help", "-h", "help", "/?" -> {
                printHelp()
                return
            }
        }

        printBanner()

        // Куда JavaFX распакует нативные .so/.dll — до старта toolkit
        org.example.defectmap.platform.FxNativeCache.configure()
            ?.let { println("  кэш нативов JavaFX: $it") }

        Application.launch(DefectMapApp::class.java, *args)
    }

    private fun printBanner() {
        println("=".repeat(78))
        println("DefectMap $VERSION — ${PlatformInfo.describe()}")
        println("  ${AppPaths.describe()}")
        println("=".repeat(78))
    }

    /**
     * Apache POI тянет log4j-api без реализации и печатает в stderr
     * «ERROR StatusLogger Log4j2 could not find a logging implementation» —
     * на Linux это выглядит как ошибка запуска.
     *
     * В log4j 2.21 свойство `log4j2.StatusLogger.level` управляет только уровнем
     * слушателей, а собственный логгер StatusLogger жёстко ERROR (LOG4J2-3340),
     * поэтому уровень выставляется программно — до первой загрузки классов POI.
     */
    private fun configureLogging() {
        runCatching {
            val logger = org.apache.logging.log4j.status.StatusLogger.getLogger()
            logger.level = org.apache.logging.log4j.Level.OFF
        }.onFailure {
            // log4j может отсутствовать — это не повод падать
            System.setProperty("log4j2.StatusLogger.level", "OFF")
        }
    }

    private fun printHelp() {
        println(
            """
            DefectMap $VERSION — карта дефектов оборудования подстанций.

            Запуск:
              java -jar DefectMap.jar                 открыть приложение (нужен дисплей)
              java -jar DefectMap.jar --selftest      проверка окружения без GUI; код возврата 0/1
              java -jar DefectMap.jar --uicheck       проверка JavaFX/WebView (в CI: xvfb-run -a)
              java -jar DefectMap.jar --appsmoke      запуск окна на несколько секунд и авто-проверка
              java -jar DefectMap.jar --fix-markers   перенести координаты старых маркеров (с бэкапом)
              java -jar DefectMap.jar --version       версия и сведения о платформе
              java -jar DefectMap.jar --help          эта справка

            Параметры --selftest:
              --in-place   проверять живые данные (по умолчанию — временная копия)
              --keep       не удалять временный каталог проверки
            Параметры --uicheck:
              --show                  показать окно (иначе проверка без окна)
              --snapshot <файл.png>   сохранить снимок отрисованной схемы
              --timeout <сек>         таймаут загрузки схемы (по умолчанию 60)

            Переопределение каталога данных:
              -Ddefectmap.home=/path   или переменная окружения DEFECTMAP_HOME
            """.trimIndent()
        )
    }
}
