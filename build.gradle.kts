plugins {
    java
    application
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.openjfx.javafxplugin") version "0.1.0"  // <-- Добавить эту строку
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

javafx {
    version = "21"
    modules = listOf("javafx.controls", "javafx.fxml", "javafx.web", "javafx.media", "javafx.swing")
}

application {
    mainClass.set("org.example.defectmap.DefectMapApp")
    applicationDefaultJvmArgs = listOf("-Djavafx.verbose=false")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("de.codecentric.centerdevice:javafxsvg:1.2.0")
    implementation("org.xerial:sqlite-jdbc:3.42.0.0")

    // Добавьте Gson для работы с JSON
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}