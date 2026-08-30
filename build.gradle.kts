plugins {
    kotlin("jvm") version "1.9.23"
    application
    id("org.openjfx.javafxplugin") version "0.0.13"
    id("org.beryx.runtime") version "1.13.0"
}

group = "org.example"
version = "1.0.0"

repositories {
    mavenCentral()
}

application {
    mainClass.set("org.example.defectmap.DefectMapApp")
}

kotlin {
    jvmToolchain(17)
}

// Настройка JavaFX: плагин сам скачает нужные файлы для твоей Windows
javafx {
    version = "21"
    modules = listOf(
        "javafx.controls",
        "javafx.fxml",
        "javafx.web",
        "javafx.media",
        "javafx.swing",
        "javafx.graphics",
        "javafx.base"
    )
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.42.0.0")
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

// Настройка создания портативной версии со встроенной Java
runtime {
    options.set(listOf("--strip-debug", "--compress", "2", "--no-header-files", "--no-man-pages"))

    modules.set(listOf(
        "javafx.controls", "javafx.fxml", "javafx.web", "javafx.media",
        "javafx.swing", "javafx.graphics", "javafx.base",
        "java.sql", "java.desktop", "jdk.xml.dom"
    ))

    jpackage {
        imageName = "DefectMap"
        appVersion = "1.0.0"
        installerType = "image" // Создаёт папку с .exe и встроенной Java, а не установщик
        installerOptions = listOf(
            "--win-per-user-install",
            "--win-dir-chooser",
            "--win-menu",
            "--win-shortcut"
        )
    }
}