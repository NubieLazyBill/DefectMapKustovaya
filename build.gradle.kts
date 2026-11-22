import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    kotlin("jvm") version "1.9.23"
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "org.example"
version = "1.1.0"

// ======================== КОНСТАНТЫ ========================

val appName = "DefectMap"
val appMainClass = "org.example.defectmap.Launcher"
val appVendor = "DefectMap"
val appDescription = "Карта дефектов оборудования подстанций"
val appCopyright = "2024-2026"

repositories {
    mavenCentral()
}

application {
    mainClass.set(appMainClass)
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8")
}

kotlin {
    jvmToolchain(17)
}

javafx {
    version = "21"
    modules = listOf(
        "javafx.controls",
        "javafx.fxml",
        "javafx.web",
        "javafx.graphics",
        "javafx.base",
    )
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.42.0.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Apache POI для Excel
    implementation("org.apache.poi:poi:5.2.5")
    implementation("org.apache.poi:poi-ooxml:5.2.5")

    // POI логирует через log4j-api без реализации и пишет ERROR в stderr при первом
    // обращении к Workbook — на Linux это выглядит как падение. Версию держим той же,
    // что тянет POI, чтобы не было двух разных API в одном JAR.
    implementation("org.apache.logging.log4j:log4j-api:2.21.1")

    // javafxsvg (de.codecentric:javafxsvg) УДАЛЁН: он нигде не использовался,
    // а тянул за собой batik и регистрировал ImageIO-плагин.
    // SVG рисуется через WebView (javafx.web) — нативных .so ему не нужно.

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    // Тесты работают на изолированной копии данных, а не на рабочей БД
    systemProperty("defectmap.home", layout.buildDirectory.dir("test-home").get().asFile.absolutePath)
    systemProperty("file.encoding", "UTF-8")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

// ======================== FAT JAR ========================

tasks.shadowJar {
    manifest {
        attributes(
            // ВАЖНО: класс-обёртка, НЕ наследник Application.
            // Иначе java -jar падает: "JavaFX runtime components are missing"
            "Main-Class" to appMainClass,
            "Implementation-Title" to appName,
            "Implementation-Version" to project.version.toString(),
            "Implementation-Vendor" to appVendor,
        )
    }
    archiveFileName.set("$appName.jar")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST")
}

// ======================== ПАКЕТИРОВАНИЕ ========================

val osName = System.getProperty("os.name").lowercase()
val isWindows = osName.contains("win")
val isLinux = osName.contains("linux") || osName.contains("nux") || osName.contains("freebsd")
val isMac = osName.contains("mac") || osName.contains("darwin")
val exeSuffix = if (isWindows) ".exe" else ""

/** Разовая команда с выводом в консоль Gradle. ProcessBuilder — без deprecated Project.exec. */
fun runCommand(command: List<String>, workDir: File = projectDir): Int {
    println("▶ " + command.joinToString(" "))
    val process = ProcessBuilder(command)
        .directory(workDir)
        .redirectErrorStream(true)
        .start()
    process.inputStream.bufferedReader().forEachLine { line ->
        if (line.isNotBlank()) println("  │ $line")
    }
    return process.waitFor()
}

fun requireCommand(command: List<String>, what: String, workDir: File = projectDir) {
    val code = runCommand(command, workDir)
    if (code != 0) throw GradleException("$what завершилось с кодом $code")
}

/**
 * jpackage/jlink берём из текущего JDK (`java.home`), затем из toolchain 17,
 * затем из JAVA_HOME. Никаких хардкодных `D:\Dev\JDK17\bin\jpackage.exe`.
 */
fun resolveJdkTool(tool: String): File {
    val candidates = mutableListOf<File>()

    System.getProperty("java.home")?.let { candidates += File(it, "bin/$tool$exeSuffix") }
    System.getenv("JAVA_HOME")?.let { candidates += File(it, "bin/$tool$exeSuffix") }
    System.getProperty("org.gradle.java.home")?.let { candidates += File(it, "bin/$tool$exeSuffix") }
    runCatching {
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
            .get().metadata.installationPath.file("bin/$tool$exeSuffix").asFile
    }.getOrNull()?.let { candidates += it }

    return candidates.firstOrNull { it.isFile }
        ?: throw GradleException(
            "Не найден '$tool'. Установите JDK 17+ и укажите его: " +
                "./gradlew -Dorg.gradle.java.home=/path/to/jdk17 ${project.name}:buildPortable " +
                "(или переменной JAVA_HOME). Искали: ${candidates.joinToString { it.absolutePath }}"
        )
}

/** Каталог, из которого jpackage забирает содержимое приложения (JAR + данные + схемы). */
val stageAppInput by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Готовит build/app-input: JAR, БД, substations.json, schemas/, images/"

    dependsOn(tasks.shadowJar)
    into(layout.buildDirectory.dir("app-input"))

    from(tasks.shadowJar.flatMap { it.archiveFile })

    // Данные и схемы, которые должны попасть в поставку.
    // На Linux при установке они копируются в каталог пользователя (AppPaths.ensureDataDirSeeded).
    from(projectDir) {
        include("substations.json")
        include("equipment*.db")
        include("equipment_export.json")
        include("schemas/**")
        include("images/**")
    }

    // Пояснения в schemas/ и images/ — чтобы пользователь видел, куда класть файлы
    from("packaging/data")

    doLast {
        val count = layout.buildDirectory.dir("app-input").get().asFile.walkTopDown().count { it.isFile }
        println("📦 build/app-input: $count файлов")
    }
}

/** Файл иконки, если он есть для текущей ОС. */
fun packagingIcon(): File? {
    val candidate = when {
        isWindows -> file("packaging/windows/$appName.ico")
        isMac -> file("packaging/macos/$appName.icns")
        else -> file("packaging/linux/defectmap.png")
    }
    return candidate.takeIf { it.isFile }
}

/** Общие аргументы jpackage (одинаковы для app-image, deb, rpm). */
fun commonJpackageArgs(): List<String> {
    val args = mutableListOf(
        "--input", layout.buildDirectory.dir("app-input").get().asFile.absolutePath,
        "--main-jar", "$appName.jar",
        "--main-class", appMainClass,
        "--name", appName,
        "--app-version", project.version.toString(),
        "--vendor", appVendor,
        "--description", appDescription,
        "--copyright", appCopyright,
    )

    packagingIcon()?.let { args += listOf("--icon", it.absolutePath) }

    // UTF-8 обязателен для JDK 17 на Linux с LANG=C — иначе ломается кириллица
    args += listOf("--java-options", "-Dfile.encoding=UTF-8")

    // Программный рендеринг: предсказуемо работает в VM/RDP/Xvfb.
    // Переопределяется: ./gradlew buildPortable -Pprism.order=hw
    val prismOrder = (project.findProperty("prism.order") as String?) ?: "sw"
    args += listOf("--java-options", "-Dprism.order=$prismOrder")

    // Готовый jlink-образ (необязательно): ./gradlew buildPortable -PruntimeImage=build/jre
    (project.findProperty("runtimeImage") as String?)?.let { runtime ->
        val dir = File(runtime)
        if (dir.isDirectory) {
            args += listOf("--runtime-image", dir.absolutePath)
        } else {
            logger.warn("⚠️ -PruntimeImage=$runtime не найден — используется runtime из java.home")
        }
    }

    return args
}

/** Портативная app-image: `build/dist/DefectMap/` (bin/DefectMap или bin/DefectMap.exe). */
val buildPortable by tasks.registering {
    group = "distribution"
    description = "Кроссплатформенная портативная сборка (jpackage --type app-image)"
    dependsOn(stageAppInput)

    doLast {
        val jpackage = resolveJdkTool("jpackage")
        val dest = layout.buildDirectory.dir("dist").get().asFile
        dest.mkdirs()
        // Чистим прошлую сборку, иначе jpackage ругается на существующий каталог
        File(dest, appName).deleteRecursively()

        val command = mutableListOf(jpackage.absolutePath, "--type", "app-image")
        command += commonJpackageArgs()
        command += listOf("--dest", dest.absolutePath)

        requireCommand(command, "jpackage (app-image)")

        val launcher = when {
            isWindows -> File(dest, "$appName/$appName.exe")
            isMac -> File(dest, "$appName.app/Contents/MacOS/$appName")
            else -> File(dest, "$appName/bin/$appName")
        }
        println()
        println("✅ Портативная сборка готова: ${File(dest, appName).absolutePath}")
        println("   Запуск: ${launcher.absolutePath}")
    }
}

/** Debian-пакет (jpackage --type deb). Собирается только на Linux. */
val buildDeb by tasks.registering {
    group = "distribution"
    description = "Сборка .deb (только Linux, jpackage --type deb)"
    dependsOn(stageAppInput)

    doLast {
        if (!isLinux) {
            throw GradleException(
                "Тип 'deb' собирается только на Linux. Текущая ОС: ${System.getProperty("os.name")}. " +
                    "Соберите пакет на ALT Linux или в контейнере: ./gradlew buildDeb"
            )
        }

        val jpackage = resolveJdkTool("jpackage")
        val dest = layout.buildDirectory.dir("dist").get().asFile
        dest.mkdirs()

        val command = mutableListOf(jpackage.absolutePath, "--type", "deb")
        command += commonJpackageArgs()
        command += listOf(
            "--dest", dest.absolutePath,
            "--linux-package-name", "defectmap",
            "--linux-shortcut",
            "--linux-menu-group", "Utility",
            "--linux-app-category", "Utility",
            "--install-dir", "/opt/defectmap",
        )

        requireCommand(command, "jpackage (deb)")

        val deb = dest.listFiles { f -> f.isFile && f.name.endsWith(".deb") }?.firstOrNull()
        println()
        println("✅ DEB готов: ${deb?.absolutePath ?: dest.absolutePath}")
        println("   Установка: sudo apt-get install -y ${deb?.name ?: "defectmap_*.deb"}")
    }
}

/** RPM-пакет (jpackage --type rpm). Для ALT Linux это родной формат пакета. */
val buildRpm by tasks.registering {
    group = "distribution"
    description = "Сборка .rpm (только Linux; родной формат для ALT Linux)"
    dependsOn(stageAppInput)

    doLast {
        if (!isLinux) {
            throw GradleException("Тип 'rpm' собирается только на Linux.")
        }
        val jpackage = resolveJdkTool("jpackage")
        val dest = layout.buildDirectory.dir("dist").get().asFile
        dest.mkdirs()

        val command = mutableListOf(jpackage.absolutePath, "--type", "rpm")
        command += commonJpackageArgs()
        command += listOf(
            "--dest", dest.absolutePath,
            "--linux-package-name", "defectmap",
            "--linux-shortcut",
            "--linux-menu-group", "Utility",
            "--linux-app-category", "Utility",
            "--install-dir", "/opt/defectmap",
        )
        requireCommand(command, "jpackage (rpm)")

        val rpm = dest.listFiles { f -> f.isFile && f.name.endsWith(".rpm") }?.firstOrNull()
        println()
        println("✅ RPM готов: ${rpm?.absolutePath ?: dest.absolutePath}")
        println("   Установка: sudo apt-get install -y ./${rpm?.name ?: "defectmap-*.rpm"}")
    }
}

/** AppImage из уже собранной app-image (нужен appimagetool, только Linux). */
val buildAppImage by tasks.registering {
    group = "distribution"
    description = "Сборка AppImage (только Linux, нужен appimagetool)"
    dependsOn(buildPortable)

    doLast {
        if (!isLinux) {
            throw GradleException("AppImage собирается только на Linux (нужен appimagetool/FUSE).")
        }
        val script = file("packaging/linux/build-appimage.sh")
        if (!script.isFile) throw GradleException("Не найден ${script.absolutePath}")

        val appImageDir = layout.buildDirectory.dir("dist/$appName").get().asFile
        requireCommand(
            listOf(
                "bash", script.absolutePath,
                appImageDir.absolutePath,
                layout.buildDirectory.dir("dist").get().asFile.absolutePath,
                project.version.toString(),
            ),
            "Сборка AppImage",
        )
    }
}

/** Всё, что можно собрать на текущей ОС. */
val packageCurrentOs by tasks.registering {
    group = "distribution"
    description = "Портативная сборка + нативный пакет для текущей ОС"
    if (isLinux) {
        dependsOn(buildPortable, buildDeb)
    } else {
        dependsOn(buildPortable)
    }
}

// ======================== ПРОВЕРКИ ========================

/** `./gradlew runSelfTest` — проверка окружения без GUI (код возврата 0/1). */
val runSelfTest by tasks.registering {
    group = "verification"
    description = "Запуск java -jar DefectMap.jar --selftest"
    dependsOn(tasks.shadowJar)

    doLast {
        val jar = tasks.shadowJar.get().archiveFile.get().asFile
        val java = resolveJdkTool("java")
        requireCommand(listOf(java.absolutePath, "-jar", jar.absolutePath, "--selftest"), "Self-test")
    }
}

/** `./gradlew runUiCheck` — проверка JavaFX/WebView (на Linux нужен xvfb-run). */
val runUiCheck by tasks.registering {
    group = "verification"
    description = "Запуск --uicheck (Linux: xvfb-run -a ./gradlew runUiCheck)"
    dependsOn(tasks.shadowJar)

    doLast {
        val jar = tasks.shadowJar.get().archiveFile.get().asFile
        val java = resolveJdkTool("java")
        val command = if (isLinux) {
            listOf("xvfb-run", "-a", java.absolutePath, "-jar", jar.absolutePath, "--uicheck")
        } else {
            listOf(java.absolutePath, "-jar", jar.absolutePath, "--uicheck")
        }
        requireCommand(command, "UI-check")
    }
}

/**
 * `./gradlew runAppSmoke` — реальный запуск окна на несколько секунд:
 * FXML + контроллер + БД + отрисовка схемы, затем авто-выход.
 */
val runAppSmoke by tasks.registering {
    group = "verification"
    description = "Запуск --appsmoke (Linux: xvfb-run -a ./gradlew runAppSmoke)"
    dependsOn(tasks.shadowJar)

    doLast {
        val jar = tasks.shadowJar.get().archiveFile.get().asFile
        val java = resolveJdkTool("java")
        val shot = layout.buildDirectory.file("tmp/app-smoke.png").get().asFile
        shot.parentFile.mkdirs()
        val appArgs = listOf("--appsmoke", "--snapshot", shot.absolutePath)
        val command = if (isLinux) {
            listOf("xvfb-run", "-a", java.absolutePath, "-jar", jar.absolutePath) + appArgs
        } else {
            listOf(java.absolutePath, "-jar", jar.absolutePath) + appArgs
        }
        requireCommand(command, "App smoke-test")
    }
}

// ======================== МИГРАЦИЯ КООРДИНАТ МАРКЕРОВ ========================
//
// Старые версии сохраняли `top` маркеров в процентах от окна, а не от схемы
// (в данных встречаются значения до 190%). Эти задачи пересчитывают координаты
// в БД, лежащих рядом с проектом, с резервной копией в backups/marker-fix-*.

private fun JavaExec.configureMarkerFix(dryRun: Boolean) {
    group = "verification"
    mainClass.set(appMainClass)
    workingDir = projectDir
    classpath = sourceSets["main"].runtimeClasspath
    args = buildList {
        add("--fix-markers")
        if (dryRun) add("--dry-run")
    }
    description = if (dryRun) {
        "Показать, какие координаты маркеров будут исправлены (--fix-markers --dry-run)"
    } else {
        "Пересчитать координаты старых маркеров в БД проекта (--fix-markers, с бэкапом)"
    }
    javaLauncher.set(
        javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
    )
}

val fixMarkersDryRun by tasks.registering(JavaExec::class) {
    configureMarkerFix(dryRun = true)
}

val fixMarkers by tasks.registering(JavaExec::class) {
    configureMarkerFix(dryRun = false)
}
