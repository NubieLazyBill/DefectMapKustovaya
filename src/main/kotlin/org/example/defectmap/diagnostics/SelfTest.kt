package org.example.defectmap.diagnostics

import org.example.defectmap.AppPaths
import org.example.defectmap.Database
import org.example.defectmap.EquipmentData
import org.example.defectmap.DefectData
import org.example.defectmap.Schemas
import org.example.defectmap.Substations
import org.example.defectmap.platform.PlatformInfo
import org.example.defectmap.platform.UiFonts
import org.example.defectmap.report.ExcelReportWriter
import org.example.defectmap.report.ReportItem
import java.io.File
import java.util.jar.JarFile
import javax.print.PrintServiceLookup

/**
 * `--selftest`: проверка готовности сборки к работе на текущей ОС **без** запуска GUI.
 *
 * Зачем: критерий приёмки «`java -jar DefectMap.jar` запускается на ALT,
 * открывается БД, работает печать/экспорт Excel» нужно уметь проверять на сервере
 * без дисплея и без ручных кликов. Команда возвращает 0 при успехе и 1 при ошибках,
 * поэтому её можно ставить в CI и в postinst-проверку.
 *
 * Проверяется: платформа и кодировки, нативные библиотеки JavaFX в JAR
 * (частая ошибка — JAR собран под другую ОС), шрифты и кириллица,
 * открытие/запись БД, экспорт Excel, наличие схем, службы печати CUPS.
 *
 * По умолчанию работает на временной копии данных (`build/tmp/selftest-*`),
 * поэтому реальную БД не трогает. Флаг `--in-place` — проверка на живых данных.
 */
object SelfTest {

    private enum class Status { OK, WARN, FAIL }

    private data class Result(val name: String, val status: Status, val detail: String)

    private val results = mutableListOf<Result>()

    fun run(args: List<String>): Int {
        val keep = args.contains("--keep")
        val inPlace = args.contains("--in-place")
        val tempHome = if (inPlace) null else prepareTempHome()

        println()
        println("=".repeat(78))
        println("DefectMap — самопроверка (--selftest)")
        println("=".repeat(78))
        println("Режим данных: " + if (inPlace) "живые данные приложения" else "временная копия: ${tempHome?.absolutePath}")

        if (!inPlace && tempHome == null) {
            checkPlatform()
            record(
                "tmpdir",
                Status.FAIL,
                "нет доступного для записи временного каталога (java.io.tmpdir=${System.getProperty("java.io.tmpdir")}). " +
                    "Проверка остановлена, данные приложения не тронуты. " +
                    "Если каталог должен быть доступен — проверьте права/антивирус, либо запустите с --in-place",
            )
            printSummary()
            return 1
        }

        checkPlatform()
        checkDirs()
        checkEncoding()
        checkJarNatives()
        checkJavaFxVersion()
        checkRequiredClasses()
        checkFonts()
        checkSchemas()
        checkSubstations()
        checkDatabase()
        checkExcel()
        checkPrinting()

        val code = printSummary()

        if (!keep) tempHome?.deleteRecursively()

        return code
    }

    private fun printSummary(): Int {
        println()
        println("-".repeat(78))
        val failed = results.count { it.status == Status.FAIL }
        val warned = results.count { it.status == Status.WARN }
        val ok = results.count { it.status == Status.OK }
        println("ИТОГО: $ok OK, $warned WARN, $failed FAIL")
        if (failed > 0) {
            println("РЕЗУЛЬТАТ: FAIL")
            println("Подсказки по типовым причинам — docs/LINUX-ALT.md, раздел «Если не запускается».")
        } else {
            println("РЕЗУЛЬТАТ: OK")
        }
        println("-".repeat(78))
        return if (failed > 0) 1 else 0
    }

    // ======================== ПОДГОТОВКА ========================

    /**
     * Временный «домашний каталог» приложения. Туда копируется настоящая БД,
     * чтобы проверить миграции на реальных данных, не портя оригинал.
     *
     * Каталог обязательно проверяется пробной записью: если TEMP недоступен
     * (антивирус, read-only /tmp), проверка честно останавливается, а не пишет
     * в рабочую БД.
     */
    private fun prepareTempHome(): File? {
        val stamp = System.currentTimeMillis()
        val candidates = listOf(
            File(System.getProperty("java.io.tmpdir") ?: ".", "defectmap-selftest-$stamp"),
            File(System.getProperty("user.home") ?: ".", ".defectmap-selftest-$stamp"),
        )

        for (base in candidates) {
            try {
                base.mkdirs()
                if (!base.isDirectory) continue

                // Проверка записи — иначе все дальнейшие проверки бессмысленны
                val probe = File.createTempFile(".dm-", ".tmp", base)
                probe.delete()

                System.setProperty("defectmap.home", base.absolutePath)
                copySeedData(base)
                return base
            } catch (t: Throwable) {
                System.err.println("⚠️ Каталог ${base.absolutePath} не подходит: ${t.message}")
            }
        }
        return null
    }

    /** Копирует реальные БД/справочник в тестовый каталог (проверка миграций на живых данных). */
    private fun copySeedData(target: File) {
        val realAppDir = detectRealAppDir() ?: return
        if (!realAppDir.isDirectory) return
        realAppDir.listFiles { f: File -> f.isFile && f.name.startsWith("equipment") && f.name.endsWith(".db", true) }
            ?.forEach { runCatching { it.copyTo(File(target, it.name), overwrite = true) } }
        val substations = File(realAppDir, "substations.json")
        if (substations.isFile) runCatching { substations.copyTo(File(target, "substations.json"), overwrite = true) }
    }

    /** Папка приложения без учёта `DEFECTMAP_HOME` — чтобы найти исходные БД. */
    private fun detectRealAppDir(): File? {
        return try {
            val source = SelfTest::class.java.protectionDomain?.codeSource?.location
            val uri = source?.toURI()
            if (uri != null && uri.scheme == "file") {
                val file = File(uri)
                if (file.isFile) file.parentFile else File(System.getProperty("user.dir") ?: ".")
            } else {
                File(System.getProperty("user.dir") ?: ".")
            }
        } catch (t: Throwable) {
            null
        }
    }

    // ======================== ПРОВЕРКИ ========================

    private fun checkPlatform() {
        section("Платформа")
        println("  ${PlatformInfo.describe()}")
        println("  Java: ${PlatformInfo.javaVendor}")
        println("  headless: ${PlatformInfo.isHeadless}")
        println("  user.home=${System.getProperty("user.home")}")
        println("  java.io.tmpdir=${System.getProperty("java.io.tmpdir")}")
        val display = PlatformInfo.env("DISPLAY") ?: "нет"
        val wayland = PlatformInfo.env("WAYLAND_DISPLAY") ?: "нет"
        println("  DISPLAY=$display, WAYLAND_DISPLAY=$wayland")
        record("platform", Status.OK, "${PlatformInfo.name} / ${PlatformInfo.arch}")
    }

    private fun checkDirs() {
        section("Каталоги")
        println("  ${AppPaths.describe()}")

        val dir = AppPaths.dataDir
        val writable = try {
            val probe = File.createTempFile(".dm-selftest-", ".tmp", dir)
            probe.delete()
            true
        } catch (t: Throwable) {
            false
        }

        val dbs = dir.listFiles { f: File -> f.isFile && f.name.endsWith(".db", true) }
            ?.map { it.name }
            .orEmpty()
        println("  БД в каталоге данных: ${if (dbs.isEmpty()) "нет" else dbs.joinToString()}")

        if (writable) {
            record("dataDir", Status.OK, "${dir.absolutePath} (запись разрешена)")
        } else {
            record("dataDir", Status.FAIL, "${dir.absolutePath} — нет прав на запись")
        }

        record(
            "режим",
            Status.OK,
            if (AppPaths.isPortable) "портативный (данные рядом с JAR)" else "пользовательский каталог (XDG)",
        )

        if (AppPaths.bundledSchemasDir.isDirectory) {
            record("поставка", Status.OK, "${AppPaths.bundledSchemasDir.absolutePath} — есть вложенные схемы")
        } else {
            record("поставка", Status.WARN, "папка schemas/ рядом с приложением отсутствует (не критично)")
        }
    }

    private fun checkEncoding() {
        section("Кодировки и имена файлов")

        val fileEncoding = PlatformInfo.fileEncoding
        val jnu = PlatformInfo.jnuEncoding
        if (fileEncoding.equals("UTF-8", ignoreCase = true)) {
            record("file.encoding", Status.OK, fileEncoding)
        } else {
            record("file.encoding", Status.WARN, "$fileEncoding — желательно UTF-8 (см. -Dfile.encoding=UTF-8)")
        }

        if (jnu.equals("UTF-8", ignoreCase = true)) {
            record("sun.jnu.encoding", Status.OK, jnu)
        } else {
            record(
                "sun.jnu.encoding",
                Status.WARN,
                "$jnu — имена файлов с кириллицей могут ломаться; запускайте с LANG=C.UTF-8 или ru_RU.UTF-8",
            )
        }

        val name = "проверка-кириллицы-${System.nanoTime()}.txt"
        try {
            val file = File(AppPaths.dataDir, name)
            file.writeText("Кириллица: АБВГДЕЁЖЗ №—«»", Charsets.UTF_8)
            val ok = file.isFile && file.readText(Charsets.UTF_8).startsWith("Кириллица")
            val realName = file.name
            file.delete()
            if (ok) {
                record("файлы", Status.OK, "запись/чтение UTF-8 с кириллическим именем работает")
            } else {
                record("файлы", Status.FAIL, "прочитанное содержимое не совпало")
            }
            if (realName != name) {
                record(
                    "имена файлов",
                    Status.WARN,
                    "имя искажено: '$name' → '$realName' (проблема кодировки ФС/JVM)",
                )
            }
        } catch (t: Throwable) {
            record("файлы", Status.FAIL, "ошибка работы с кириллическим именем: ${t.message}")
        }

        val svg = Schemas.find("kustovaya")?.content
        if (svg != null) {
            val cyrillic = svg.any { it in 'А'..'я' || it == 'Ё' || it == 'ё' }
            record(
                "кириллица в схеме",
                if (cyrillic) Status.OK else Status.WARN,
                if (cyrillic) "схема содержит кириллицу — прочитана корректно" else "в схеме нет кириллицы",
            )
        }
    }

    /**
     * Самая частая причина «на Linux не запускается»: fat JAR собран на Windows,
     * внутри только .dll, а нужны .so. Проверяем содержимое собственного JAR.
     */
    private fun checkJarNatives() {
        section("Нативные библиотеки JavaFX")

        val jar = try {
            val source = SelfTest::class.java.protectionDomain?.codeSource?.location
            val uri = source?.toURI()
            if (uri != null && uri.scheme == "file") File(uri).takeIf { it.isFile } else null
        } catch (t: Throwable) {
            null
        }

        if (jar == null) {
            record("JAR", Status.WARN, "запуск не из JAR (IDE/classes) — проверка нативов пропущена")
            return
        }

        val expectedSuffix = when {
            PlatformInfo.isWindows -> ".dll"
            PlatformInfo.isMac -> ".dylib"
            else -> ".so"
        }
        val modules = listOf("glass", "prism_sw", "javafx_font", "jfxwebkit")

        try {
            JarFile(jar).use { archive ->
                val natives = archive.entries().toList()
                    .filter { it.name.endsWith(".dll") || it.name.endsWith(".so") || it.name.endsWith(".dylib") }
                    .map { it.name.substringAfterLast('/') }

                val found = modules.mapNotNull { module ->
                    natives.firstOrNull { name ->
                        name.endsWith(expectedSuffix) && name.contains(module, ignoreCase = true)
                    }
                }
                val foreign = natives.filterNot { it.endsWith(expectedSuffix) }

                println("  JAR: ${jar.name} (${jar.length() / 1024 / 1024} МБ)")
                println("  Нативов для ${PlatformInfo.name}: ${found.size}/4 ${found.joinToString()}")

                when {
                    found.size >= 3 -> record(
                        "natives",
                        Status.OK,
                        "найдено ${found.size}/4 для $expectedSuffix: ${found.joinToString()}",
                    )

                    else -> record(
                        "natives",
                        Status.FAIL,
                        buildString {
                            append("в JAR нет нативных библиотек JavaFX для ${PlatformInfo.name} ($expectedSuffix). ")
                            append("Найдено ${found.size}/4. ")
                            if (foreign.isNotEmpty()) {
                                append("Есть чужие нативы (${foreign.first()}, всего ${foreign.size}) — ")
                                append("JAR собран под другую ОС. Пересоберите на целевой системе: ./gradlew shadowJar")
                            }
                        },
                    )
                }
            }
        } catch (t: Throwable) {
            record("natives", Status.FAIL, "не удалось прочитать JAR: ${t.message}")
        }
    }

    private fun checkJavaFxVersion() {
        section("JavaFX")
        try {
            val props = javaClass.getResourceAsStream("/javafx.properties")
            if (props == null) {
                record("javafx", Status.FAIL, "нет /javafx.properties — JavaFX не в classpath")
                return
            }
            val version = props.use { input ->
                val p = java.util.Properties()
                p.load(input)
                p.getProperty("javafx.runtime.version")
            }
            println("  javafx.runtime.version = $version")
            record("javafx", Status.OK, version ?: "версия не указана")
        } catch (t: Throwable) {
            record("javafx", Status.FAIL, "не удалось прочитать javafx.properties: ${t.message}")
        }
    }

    private fun checkRequiredClasses() {
        section("Обязательные классы")
        val required = listOf(
            "javafx.application.Application",
            "javafx.scene.web.WebView",
            "javafx.print.PrinterJob",
            "javafx.fxml.FXMLLoader",
            "javax.print.PrintServiceLookup",
            "org.sqlite.JDBC",
            "org.apache.poi.xssf.usermodel.XSSFWorkbook",
            "com.google.gson.Gson",
            "javax.imageio.ImageIO",
        )
        val missing = mutableListOf<String>()
        required.forEach { className ->
            try {
                Class.forName(className, false, javaClass.classLoader)
            } catch (t: Throwable) {
                missing.add(className)
            }
        }
        if (missing.isEmpty()) {
            record("classes", Status.OK, "${required.size} классов доступны")
        } else {
            record("classes", Status.FAIL, "не найдены: ${missing.joinToString()}")
        }
    }

    private fun checkFonts() {
        section("Шрифты (AWT; JavaFX-семейства проверяются в --uicheck)")
        println("  ${UiFonts.describe(probeJavaFx = false)}")
        val (coverage, family) = UiFonts.cyrillicCoverage(probeJavaFx = false)
        if (coverage) {
            record("кириллица", Status.OK, "шрифт '$family' содержит кириллицу")
        } else {
            record(
                "кириллица",
                Status.WARN,
                "в шрифте '$family' не хватает части кириллических символов — установите fonts-liberation/fonts-dejavu",
            )
        }

        val arial = UiFonts.SANS_CANDIDATES.firstOrNull { candidate ->
            runCatching {
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .availableFontFamilyNames.any { it.equals(candidate, ignoreCase = true) }
            }.getOrDefault(false)
        }
        if (arial == null) {
            record(
                "Arial",
                Status.WARN,
                "Arial не установлен — используется метрически совместимый fallback (Liberation Sans/DejaVu)",
            )
        } else {
            record("Arial", Status.OK, "используется '$arial'")
        }
    }

    private fun checkSchemas() {
        section("Схемы подстанций")
        val available = Schemas.listAvailable()
        println("  Файлы .svg в каталогах схем: ${if (available.isEmpty()) "нет" else available.joinToString()}")

        val key = runCatching { Substations.DEFAULT.key }.getOrDefault("kustovaya")
        val schema = Schemas.find(key)
        if (schema == null) {
            record("схема", Status.FAIL, "не найдена ни одна схема для '$key' (ни в schemas/, ни в ресурсах)")
            return
        }
        println("  Источник: ${schema.source} (${schema.sizeKb} КБ)")
        val wellFormed = Schemas.isWellFormed(schema.content)
        if (wellFormed) {
            record("схема", Status.OK, "'$key' — ${schema.sizeKb} КБ, XML корректен")
        } else {
            record("схема", Status.FAIL, "'$key' — файл не является корректным XML: ${schema.source}")
        }

        val normalized = schema.content.contains("\"Liberation Sans\"")
        record(
            "шрифты в SVG",
            Status.OK,
            if (normalized) "подставлены свободные аналоги Arial/Times" else "явных Arial/Times в схеме нет",
        )
    }

    private fun checkSubstations() {
        section("Справочник подстанций")
        try {
            val all = Substations.ALL
            if (all.isEmpty()) {
                record("substations", Status.FAIL, "список подстанций пуст")
            } else {
                record("substations", Status.OK, "${all.size} ПС: ${all.joinToString { it.key }}")
            }
        } catch (t: Throwable) {
            record("substations", Status.FAIL, "не удалось прочитать substations.json: ${t.message}")
        }
    }

    private fun checkDatabase() {
        section("База данных SQLite")
        var db: Database? = null
        try {
            val key = runCatching { Substations.DEFAULT.key }.getOrDefault("kustovaya")
            val started = System.currentTimeMillis()
            db = Database(key)
            val elapsed = System.currentTimeMillis() - started

            val equipmentCount = db.getCount()
            val defectsCount = db.getDefectsCount()
            val types = db.loadAllTypes()
            val defectTypes = db.loadAllDefectTypes()

            println("  ПС=$key: оборудования $equipmentCount, дефектов $defectsCount")
            println("  Типов оборудования: ${types.size}, видов дефектов: ${defectTypes.size}")
            println("  Открытие/миграции: $elapsed мс")

            // CRUD-проба: пишем и удаляем тестовые записи
            val probeId = "selftest-${System.currentTimeMillis()}"
            db.saveEquipment(
                listOf(
                    EquipmentData(
                        id = probeId,
                        left = 12.5,
                        top = 34.5,
                        type = "v_220",
                        name = "ПРОВЕРКА SELFTEST",
                        letter = "Т",
                        cell = "1",
                        size = "small",
                    )
                )
            )
            db.saveDefect(
                DefectData(
                    id = "$probeId-defect",
                    equipmentId = probeId,
                    name = "Проверка дефекта",
                    description = "Кириллица: Ёжик №1 — «тест»",
                    status = "open",
                )
            )
            val written = db.loadAllEquipment().any { it.id == probeId }
            val defectWritten = db.getDefectsByEquipment(probeId).size == 1
            val backupOk = try {
                db.autoBackup()
                true
            } catch (t: Throwable) {
                false
            }
            db.deleteDefect("$probeId-defect")
            db.deleteById(probeId)
            val cleaned = db.loadAllEquipment().none { it.id == probeId }

            if (written && defectWritten && cleaned) {
                record(
                    "БД",
                    Status.OK,
                    "открытие, миграции, запись/чтение/удаление оборудования и дефектов, бэкап${if (backupOk) "" else " (ошибка)"}",
                )
            } else {
                record(
                    "БД",
                    Status.FAIL,
                    "CRUD не сошёлся: запись=$written, дефект=$defectWritten, удаление=$cleaned",
                )
            }
        } catch (t: Throwable) {
            record("БД", Status.FAIL, "${t::class.java.simpleName}: ${t.message}")
            t.printStackTrace()
        } finally {
            runCatching { db?.close() }
        }
    }

    private fun checkExcel() {
        section("Экспорт Excel (Apache POI)")
        val target = File(System.getProperty("java.io.tmpdir"), "defectmap-selftest-${System.currentTimeMillis()}.xlsx")
        try {
            val items = listOf(
                ReportItem(
                    equipmentName = "АТГ-1",
                    equipmentType = "АТГ",
                    equipmentCell = "1",
                    defectName = "Течь масла",
                    defectDescription = "Капельная течь по фланцу",
                    defectStatus = "Обнаружен",
                    markerLeft = 12.5,
                    markerTop = 34.5,
                    detectionDate = System.currentTimeMillis(),
                    parentEquipmentName = "ОРУ-500",
                ),
                ReportItem(
                    equipmentName = "В-220 №3",
                    equipmentType = "В-220",
                    equipmentCell = "7",
                    defectName = "Износ контактов",
                    defectDescription = "Требуется ревизия",
                    defectStatus = "Устранён",
                    markerLeft = 55.0,
                    markerTop = 66.0,
                    detectionDate = System.currentTimeMillis(),
                ),
            )

            val started = System.currentTimeMillis()
            ExcelReportWriter.write(items, target)
            val elapsed = System.currentTimeMillis() - started

            if (!target.isFile || target.length() == 0L) {
                record("excel", Status.FAIL, "файл не создан: ${target.absolutePath}")
                return
            }

            val workbook = org.apache.poi.xssf.usermodel.XSSFWorkbook(target.inputStream())
            workbook.use { wb ->
                val sheet = wb.getSheet(ExcelReportWriter.SHEET_NAME) ?: wb.getSheetAt(0)
                val header = sheet.getRow(0)?.getCell(0)?.stringCellValue
                val firstName = sheet.getRow(1)?.getCell(1)?.stringCellValue
                val totalCell = sheet.getRow(items.size + 2)?.getCell(1)?.stringCellValue
                val ok = sheet.lastRowNum >= items.size && header == "№" && firstName == "АТГ-1"
                println("  Файл: ${target.absolutePath} (${target.length()} байт, $elapsed мс)")
                println("  Лист '${sheet.sheetName}', шапка='$header', строка 1='$firstName', итог='$totalCell'")
                if (ok) {
                    record("excel", Status.OK, "xlsx создан и прочитан обратно, лист '${sheet.sheetName}'")
                } else {
                    record("excel", Status.FAIL, "содержимое xlsx не совпадает с ожидаемым")
                }
            }
        } catch (t: Throwable) {
            record("excel", Status.FAIL, "${t::class.java.simpleName}: ${t.message}")
            t.printStackTrace()
        } finally {
            runCatching { target.delete() }
        }
    }

    private fun checkPrinting() {
        section("Службы печати")
        try {
            val services = PrintServiceLookup.lookupPrintServices(null, null)
            val default = PrintServiceLookup.lookupDefaultPrintService()
            println("  Принтеров найдено: ${services.size}; по умолчанию: ${default?.name ?: "не выбран"}")
            when {
                services.isEmpty() -> record(
                    "печать",
                    Status.WARN,
                    "принтеры не найдены (нет CUPS/принтера). Печать из приложения потребует настроенного принтера; " +
                        "экспорт в Excel работает независимо",
                )

                else -> record("печать", Status.OK, "${services.size} принтер(ов): ${services.joinToString { it.name } }")
            }
        } catch (t: Throwable) {
            record("печать", Status.WARN, "не удалось опросить службы печати: ${t.message}")
        }
    }

    // ======================== ВЫВОД ========================

    private fun section(title: String) {
        println()
        println("[$title]")
    }

    private fun record(name: String, status: Status, detail: String) {
        results.add(Result(name, status, detail))
        val mark = when (status) {
            Status.OK -> "OK  "
            Status.WARN -> "WARN"
            Status.FAIL -> "FAIL"
        }
        println("  [$mark] $name: $detail")
    }
}
