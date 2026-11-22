package org.example.defectmap.platform

import java.io.File
import java.util.Locale

/**
 * Сведения о платформе, на которой запущено приложение.
 *
 * Заменяет разбросанные по коду проверки `os.name` и хардкод Windows-путей:
 * всё ветвление "Windows / Linux / macOS" должно идти через этот объект.
 */
object PlatformInfo {

    private val osNameRaw: String =
        (System.getProperty("os.name") ?: "").lowercase(Locale.ROOT)

    val isWindows: Boolean = osNameRaw.contains("win")
    val isMac: Boolean = osNameRaw.contains("mac") || osNameRaw.contains("darwin")
    val isLinux: Boolean =
        osNameRaw.contains("linux") || osNameRaw.contains("nux") || osNameRaw.contains("freebsd")

    /** Человекочитаемое имя ОС для логов и сообщений. */
    val name: String
        get() = when {
            isWindows -> "Windows"
            isMac -> "macOS"
            isLinux -> "Linux"
            osNameRaw.isBlank() -> "unknown"
            else -> osNameRaw
        }

    /** Идентификатор платформы в нотации JavaFX-классификаторов (windows/linux/mac). */
    val javafxClassifier: String
        get() = when {
            isWindows -> "windows"
            isMac -> "mac"
            else -> "linux"
        }

    val arch: String = System.getProperty("os.arch") ?: "unknown"

    /** Суффикс исполняемых файлов: `.exe` только на Windows. */
    val exeSuffix: String = if (isWindows) ".exe" else ""

    val javaHome: File = File(System.getProperty("java.home") ?: ".").absoluteFile

    val userHome: File = File(System.getProperty("user.home") ?: ".").absoluteFile

    val fileEncoding: String = System.getProperty("file.encoding") ?: "unknown"
    val jnuEncoding: String = System.getProperty("sun.jnu.encoding") ?: "unknown"
    val javaVersion: String = System.getProperty("java.version") ?: "unknown"
    val javaVendor: String = System.getProperty("java.vendor") ?: "unknown"

    /** true, если AWT работает без графического дисплея (типично для серверов/CI). */
    val isHeadless: Boolean by lazy {
        runCatching { java.awt.GraphicsEnvironment.isHeadless() }.getOrDefault(true)
    }

    fun property(name: String): String? =
        System.getProperty(name)?.takeIf { it.isNotBlank() }

    fun env(name: String): String? =
        System.getenv(name)?.takeIf { it.isNotBlank() }

    /** Путь к утилите внутри текущего JDK (`bin/jpackage`, `bin/java`, ...). */
    fun jdkTool(tool: String): File = File(javaHome, "bin/$tool$exeSuffix")

    /** Одна строка с параметрами окружения — печатается при старте и в self-test. */
    fun describe(): String = buildString {
        append("OS=").append(name).append(" (").append(System.getProperty("os.version")).append("), ")
        append("arch=").append(arch).append(", ")
        append("java=").append(javaVersion).append(", ")
        append("file.encoding=").append(fileEncoding).append(", ")
        append("sun.jnu.encoding=").append(jnuEncoding)
    }
}
