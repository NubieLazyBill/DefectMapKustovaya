plugins {
    java
    application
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

application {
    mainClass.set("org.example.defectmap.DefectMapApp")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("de.codecentric.centerdevice:javafxsvg:1.2.0")
    implementation("org.xerial:sqlite-jdbc:3.42.0.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // JavaFX как обычные зависимости (Windows)
    implementation("org.openjfx:javafx-controls:21:win")
    implementation("org.openjfx:javafx-fxml:21:win")
    implementation("org.openjfx:javafx-web:21:win")
    implementation("org.openjfx:javafx-media:21:win")
    implementation("org.openjfx:javafx-swing:21:win")
    implementation("org.openjfx:javafx-graphics:21:win")
    implementation("org.openjfx:javafx-base:21:win")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// ========== СБОРКА FAT JAR С SHADOW ==========
tasks.shadowJar {
    manifest {
        attributes["Main-Class"] = "org.example.defectmap.DefectMapApp"
    }
    archiveFileName.set("DefectMap.jar")
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// ========== ЗАДАЧА ДЛЯ LAUNCH4J ==========
tasks.register<Exec>("buildExe") {
    group = "distribution"
    description = "Сборка .exe с помощью Launch4j"

    dependsOn("shadowJar")

    val launch4jPath = "D:/Launch4j/launch4j.exe"
    val configFile = file("launch4j.xml")
    val jarFile = file("build/libs/DefectMap.jar")
    val outputDir = file("build/exe")

    doFirst {
        val launch4jExe = File(launch4jPath)
        if (!launch4jExe.exists()) {
            throw GradleException("Launch4j не найден: $launch4jPath")
        }
        if (!jarFile.exists()) {
            throw GradleException("JAR не найден: ${jarFile.absolutePath}")
        }
        outputDir.mkdirs()

        configFile.writeText("""
<?xml version="1.0" encoding="UTF-8"?>
<launch4jConfig>
  <dontWrapJar>false</dontWrapJar>
  <headerType>gui</headerType>
  <jar>build/libs/DefectMap.jar</jar>
  <outfile>build/exe/DefectMap.exe</outfile>
  <errTitle>DefectMap</errTitle>
  <jre>
    <minVersion>17</minVersion>
    <maxVersion></maxVersion>
    <jdkPreference>preferJre</jdkPreference>
  </jre>
  <versionInfo>
    <fileVersion>1.0.0.0</fileVersion>
    <txtFileVersion>1.0</txtFileVersion>
    <fileDescription>Карта дефектов оборудования</fileDescription>
    <copyright>2024</copyright>
    <productVersion>1.0.0.0</productVersion>
    <txtProductVersion>1.0</txtProductVersion>
    <productName>DefectMap</productName>
    <internalName>DefectMap</internalName>
    <originalFilename>DefectMap.exe</originalFilename>
  </versionInfo>
</launch4jConfig>
        """.trimIndent())

        println("📦 Сборка EXE...")
    }

    commandLine = listOf(launch4jPath, configFile.absolutePath)

    doLast {
        val exeFile = file("build/exe/DefectMap.exe")
        if (exeFile.exists()) {
            println("✅ EXE создан: ${exeFile.absolutePath}")
        } else {
            println("❌ EXE не создан")
        }
    }
}