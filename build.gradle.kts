plugins {
    java
    application
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    //id("org.openjfx.javafxplugin") version "0.2.0"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val junitVersion = "5.10.2"

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

application {
    mainClass.set("org.example.defectmap.HelloApplication")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.openjfx:javafx-base:21:win")
    implementation("org.openjfx:javafx-graphics:21:win")
    implementation("org.openjfx:javafx-controls:21:win")
    implementation("org.openjfx:javafx-fxml:21:win")
    implementation("org.openjfx:javafx-web:21:win")
    implementation("org.openjfx:javafx-swing:21:win")
    implementation("org.openjfx:javafx-media:21:win")

    implementation("de.codecentric.centerdevice:javafxsvg:1.2.0")

    implementation("org.xerial:sqlite-jdbc:3.42.0.0")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
}

tasks.withType<Test> {
    useJUnitPlatform()
}