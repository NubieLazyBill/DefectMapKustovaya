plugins {
    kotlin("jvm") version "1.9.23"
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
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

javafx {
    version = "21"
    modules = listOf(
        "javafx.controls", "javafx.fxml", "javafx.web", "javafx.media",
        "javafx.swing", "javafx.graphics", "javafx.base"
    )
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.42.0.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("de.codecentric.centerdevice:javafxsvg:1.2.0")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

// ========== FAT JAR ==========
tasks.shadowJar {
    manifest {
        attributes["Main-Class"] = "org.example.defectmap.DefectMapApp"
    }
    archiveFileName.set("DefectMap.jar")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// ========== СОЗДАНИЕ ПОРТАТИВНОЙ ВЕРСИИ ==========
tasks.register("buildPortable") {
    group = "distribution"
    description = "Создание портативной папки с приложением"

    dependsOn("shadowJar")

    doLast {
        val outputDir = file("build/portable")
        val runtimeDir = file("build/jre")

        // 1. Создаём JRE с помощью jlink
        println("📦 Создаём кастомный JRE...")
        exec {
            commandLine(
                "jlink",
                "--output", runtimeDir.absolutePath,
                "--module-path", "C:\\Program Files\\Java\\javafx-sdk-21.0.11\\lib",
                "--add-modules",
                "java.base,java.desktop,java.logging,java.sql,java.xml,javafx.controls,javafx.fxml,javafx.web,javafx.media,javafx.swing,javafx.graphics,javafx.base",
                "--strip-debug",
                "--no-man-pages",
                "--no-header-files",
                "--compress", "2"
            )
        }

        // 2. Создаём портативную папку через jpackage
        println("📦 Создаём портативную версию...")
        outputDir.mkdirs()

        exec {
            commandLine(
                "D:\\Dev\\JDK17\\bin\\jpackage.exe",
                "--type", "app-image",
                "--input", "build/libs",
                "--main-jar", "DefectMap.jar",
                "--main-class", "org.example.defectmap.DefectMapApp",
                "--name", "DefectMap",
                "--dest", outputDir.absolutePath,
                "--runtime-image", runtimeDir.absolutePath,
                "--java-options", "-Dprism.order=sw",
                "--java-options", "-Dprism.forceGPU=true"
            )
        }

        println("\n✅ ПОРТАТИВНАЯ ВЕРСИЯ ГОТОВА!")
        println("📁 ${outputDir.absolutePath}/DefectMap/DefectMap.exe")
    }
}