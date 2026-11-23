package org.example.defectmap

import org.example.defectmap.platform.PlatformInfo
import java.io.File
import java.util.Locale

/**
 * Единая точка правды о путях приложения. Работает и на Windows, и на Linux/macOS.
 *
 * Две папки:
 *  - [appDir]  — откуда запущен код (рядом с JAR, либо каталог классов в IDE).
 *                В .deb/AppImage это `/opt/defectmap/lib/app` или `/tmp/.mount_.../lib/app`,
 *                то есть папка **только для чтения**.
 *  - [dataDir] — куда приложение пишет БД, схемы, бэкапы и настройки.
 *                Если рядом с JAR писать можно (портативный режим, Windows) —
 *                это та же папка, что и [appDir], и поведение не меняется.
 *                Если нельзя (установка в /opt, /usr) — XDG-каталог пользователя:
 *                `$XDG_DATA_HOME/defectmap` или `~/.local/share/defectmap`.
 *
 * Переопределение (полезно и для тестов):
 *  - `-Ddefectmap.home=/path`  или  переменная окружения `DEFECTMAP_HOME`.
 */
object AppPaths {

    private const val PROP_HOME = "defectmap.home"
    private const val PROP_APP_DIR = "defectmap.appdir"
    private const val ENV_HOME = "DEFECTMAP_HOME"

    private val overrideDir: File? by lazy {
        val explicit = PlatformInfo.property(PROP_HOME) ?: PlatformInfo.env(ENV_HOME)
        explicit?.let { File(it).absoluteFile.apply { mkdirs() } }
    }

    /** Отдельное переопределение каталога приложения (для диагностики и тестов). */
    private val overrideAppDir: File? by lazy {
        PlatformInfo.property(PROP_APP_DIR)?.let { File(it).absoluteFile }
    }

    /** Папка, рядом с которой лежит JAR (или каталог проекта при запуске из IDE). */
    val appDir: File by lazy { overrideAppDir ?: overrideDir ?: detectAppDir() }

    /**
     * Запуск из AppImage. Обычный запуск монтирует образ в read-only каталог,
     * а `APPIMAGE_EXTRACT_AND_RUN=1` распаковывает его во **временную** папку,
     * которая доступна для записи. Если в этом случае писать рядом с JAR,
     * данные окажутся в /tmp и пропадут после закрытия приложения, поэтому
     * для AppImage всегда используем пользовательский каталог.
     */
    private val launchedFromAppImage: Boolean by lazy {
        PlatformInfo.env("APPIMAGE") != null || PlatformInfo.env("APPDIR") != null
    }

    /**
     * Приложение запущено из временного каталога (распакованный AppImage,
     * установщик, тесты). Данные там хранить нельзя: каталог удалят.
     */
    private val appDirInTemp: Boolean by lazy {
        val tmp = File(System.getProperty("java.io.tmpdir") ?: "/tmp").absolutePath
        val path = appDir.absolutePath
        path.startsWith(tmp + File.separator, ignoreCase = PlatformInfo.isWindows) ||
            path.startsWith("/tmp/") || path.startsWith("/var/tmp/")
    }

    /** Папка для записи: БД, схемы, бэкапы, настройки. */
    private val dataDirBase: File by lazy {
        val dir = overrideDir ?: run {
            val portable = !launchedFromAppImage && !appDirInTemp && isWritable(appDir)
            if (portable) appDir else userDataDir()
        }
        dir.mkdirs()
        if (!dir.isDirectory) {
            System.err.println("⚠️ Не удалось создать каталог данных: ${dir.absolutePath}")
        }
        dir
    }

    @Volatile
    private var seeded = false

    /**
     * Каталог данных. При первом обращении разворачивает поставку
     * (БД, схемы, картинки) в пользовательский каталог, если он нужен.
     */
    val dataDir: File
        get() {
            val dir = dataDirBase
            if (!seeded) {
                seeded = true
                ensureDataDirSeeded()
            }
            return dir
        }

    /** true — данные лежат рядом с приложением (портативный режим). */
    val isPortable: Boolean by lazy { sameFile(dataDir, appDir) }

    /** Пользовательские схемы (запись). Создаётся при первом обращении. */
    val schemasDir: File by lazy { File(dataDir, "schemas").also { it.mkdirs() } }

    /** Схемы, вложенные в поставку (только чтение). */
    val bundledSchemasDir: File get() = File(appDir, "schemas")

    /** Пользовательские картинки оборудования (запись). */
    val imagesDir: File by lazy { File(dataDir, "images").also { it.mkdirs() } }

    /** Картинки, вложенные в поставку (только чтение). */
    val bundledImagesDir: File get() = File(appDir, "images")

    /** Каталог автобэкапов БД. */
    val backupsDir: File get() = File(dataDir, "backups")

    /**
     * Каталог готовых отчётов — папка «отчёты» рядом с приложением.
     *
     * Для AppImage образ смонтирован только для чтения, поэтому берём путь к
     * самому файлу .AppImage (`APPIMAGE`) и кладём отчёты рядом с ним. Если
     * рядом писать нельзя — отчёты уходят в каталог данных.
     */
    val reportsDir: File by lazy {
        val candidates = buildList {
            PlatformInfo.env("APPIMAGE")?.let { appImage ->
                File(appImage).absoluteFile.parentFile?.let { add(File(it, "отчёты")) }
            }
            add(File(appDir, "отчёты"))
            add(File(dataDir, "отчёты"))
        }
        for (dir in candidates) {
            try {
                dir.mkdirs()
                if (dir.isDirectory) {
                    val probe = File.createTempFile(".probe-", ".tmp", dir)
                    probe.delete()
                    return@lazy dir
                }
            } catch (_: Throwable) {
                // пробуем следующий вариант
            }
        }
        File(dataDir, "отчёты").also { it.mkdirs() }
    }

    /** Куда открывать диалоги сохранения файлов. */
    val documentsDir: File by lazy {
        val docs = File(PlatformInfo.userHome, "Documents")
        when {
            docs.isDirectory -> docs
            PlatformInfo.userHome.isDirectory -> PlatformInfo.userHome
            else -> dataDir
        }
    }

    fun schemaFile(substationKey: String): File = File(schemasDir, "schema_$substationKey.svg")

    fun bundledSchemaFile(substationKey: String): File =
        File(bundledSchemasDir, "schema_$substationKey.svg")

    fun fallbackSchemaFile(): File = File(schemasDir, "schema.svg")

    fun bundledFallbackSchemaFile(): File = File(bundledSchemasDir, "schema.svg")

    /**
     * Копирует БД / схемы / картинки из поставки в каталог данных при первом запуске.
     * Нужно для .deb и AppImage, где папка приложения read-only.
     */
    fun ensureDataDirSeeded() {
        try {
            if (isPortable) return
            val copied = DataSeeder.seed(appDir, dataDirBase)
            if (copied.isNotEmpty()) {
                println("📦 Данные развёрнуты в ${dataDirBase.absolutePath} (файлов: ${copied.size})")
                println("   ${copied.joinToString()}")
            }
        } catch (t: Throwable) {
            System.err.println("⚠️ Не удалось развернуть данные в ${dataDirBase.absolutePath}: ${t.message}")
        }
    }

    /**
     * Открывает папку в системном файловом менеджере.
     * `java.awt.Desktop` на Linux без GTK/GNOME часто не поддерживается,
     * поэтому есть запасные варианты: xdg-open / open / explorer.
     */
    fun openFolder(folder: File): Boolean {
        if (!folder.exists()) folder.mkdirs()

        val commands = when {
            PlatformInfo.isWindows -> listOf(listOf("explorer.exe", folder.absolutePath))
            PlatformInfo.isMac -> listOf(listOf("open", folder.absolutePath))
            else -> listOf(listOf("xdg-open", folder.absolutePath), listOf("gio", "open", folder.absolutePath))
        }

        // ВАЖНО: всё выполняется в фоновом потоке. Раньше здесь был
        // process.waitFor(5, SECONDS) прямо в потоке JavaFX — интерфейс
        // замирал на несколько секунд (а с xdg-open мог и дольше).
        val worker = Thread {
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    val desktop = java.awt.Desktop.getDesktop()
                    if (desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
                        desktop.open(folder)
                        println("✅ Папка открыта: ${folder.absolutePath}")
                        return@Thread
                    }
                }
            } catch (t: Throwable) {
                println("ℹ️ Desktop.OPEN недоступен (${t.message}), пробуем внешнюю утилиту")
            }

            for (cmd in commands) {
                try {
                    ProcessBuilder(cmd)
                        .redirectInput(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                    println("✅ Папка открыта через ${cmd.first()}: ${folder.absolutePath}")
                    return@Thread
                } catch (t: Throwable) {
                    println("ℹ️ ${cmd.first()} недоступен: ${t.message}")
                }
            }
            println("⚠️ Не удалось открыть папку ${folder.absolutePath}")
        }
        worker.isDaemon = true
        worker.name = "open-folder"
        worker.start()
        return true
    }


    /** Строка для лога/self-test: где лежат данные и почему. */
    fun describe(): String = buildString {
        append("appDir=").append(appDir.absolutePath)
        append(", dataDir=").append(dataDir.absolutePath)
        append(", режим=").append(if (isPortable) "портативный" else "пользовательский каталог")
        if (overrideDir != null) append(" (переопределён DEFECTMAP_HOME/defectmap.home)")
    }

    // ======================== ВНУТРЕННЕЕ ========================

    private fun detectAppDir(): File {
        // 1. Код запущен из JAR — берём папку JAR
        val jar = jarLocation()
        if (jar != null) return jar.parentFile?.absoluteFile ?: jar.absoluteFile

        // 2. Запуск из IDE / classes — рабочая директория
        val userDir = System.getProperty("user.dir")
        if (!userDir.isNullOrBlank()) return File(userDir).absoluteFile

        return PlatformInfo.userHome
    }

    /** Файл JAR, из которого загружен этот класс, либо null (IDE/classes). */
    private fun jarLocation(): File? {
        return try {
            val source = AppPaths::class.java.protectionDomain?.codeSource?.location ?: return null
            val uri = source.toURI()
            if (uri.scheme != "file") return null
            val file = File(uri)
            if (file.isFile) file.absoluteFile else null
        } catch (t: Throwable) {
            null
        }
    }

    /** Пользовательский каталог данных по правилам платформы (XDG на Linux). */
    private fun userDataDir(): File {
        if (PlatformInfo.isWindows) {
            val localAppData = PlatformInfo.env("LOCALAPPDATA")
            if (localAppData != null) return File(localAppData, "DefectMap")
            return File(PlatformInfo.userHome, "AppData/Local/DefectMap")
        }

        if (PlatformInfo.isMac) {
            return File(PlatformInfo.userHome, "Library/Application Support/DefectMap")
        }

        val xdg = PlatformInfo.env("XDG_DATA_HOME")
        if (xdg != null) return File(xdg, "defectmap")

        return File(PlatformInfo.userHome, ".local/share/defectmap")
    }

    /** Пробное создание файла — единственный надёжный способ проверить запись. */
    private fun isWritable(dir: File): Boolean {
        return try {
            if (!dir.isDirectory) return false
            val probe = File.createTempFile(".defectmap-write-", ".tmp", dir)
            probe.delete()
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun sameFile(a: File, b: File): Boolean {
        return try {
            a.canonicalPath.equals(b.canonicalPath, ignoreCase = PlatformInfo.isWindows)
        } catch (t: Throwable) {
            a.absolutePath.equals(b.absolutePath, ignoreCase = PlatformInfo.isWindows)
        }
    }

    /** true, если имя файла содержит только безопасные для Linux/Windows символы. */
    fun isSafeFileName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return name.isNotBlank() &&
            !lower.contains("..") &&
            name.none { it in "\\/:*?\"<>|" } &&
            name.none { it.code < 32 }
    }
}
