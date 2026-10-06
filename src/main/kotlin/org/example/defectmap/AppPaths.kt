package org.example.defectmap

import java.awt.Desktop
import java.io.File

object AppPaths {
    /** Папка приложения: рядом с JAR, либо рабочая директория при запуске из IDE */
    val appDir: String by lazy {
        try {
            val location = AppPaths::class.java.protectionDomain.codeSource.location.toURI().path
            val jarFile = File(location)
            if (jarFile.isFile) {
                jarFile.parent
            } else {
                System.getProperty("user.dir")
            }
        } catch (e: Exception) {
            System.getProperty("user.dir")
        }
    }

    /** Папка со схемами подстанций. Создаётся при первом обращении, если её нет. */
    val schemasDir: File by lazy {
        File(appDir, "schemas").also { it.mkdirs() }
    }

    /** Папка с картинками оборудования (опционально). */
    val imagesDir: File by lazy {
        File(appDir, "images")
    }

    fun schemaFile(substationKey: String): File = File(schemasDir, "schema_$substationKey.svg")
    fun fallbackSchemaFile(): File = File(schemasDir, "schema.svg")

    /**
     * Открывает папку в системном проводнике.
     * Если папки нет — создаёт её.
     * Возвращает true, если удалось открыть.
     */
    fun openFolder(folder: File): Boolean {
        return try {
            if (!folder.exists()) folder.mkdirs()
            if (!Desktop.isDesktopSupported()) {
                println("⚠️ Desktop API не поддерживается")
                return false
            }
            val desktop = Desktop.getDesktop()
            if (!desktop.isSupported(Desktop.Action.OPEN)) {
                println("⚠️ Desktop.OPEN не поддерживается")
                return false
            }
            desktop.open(folder)
            true
        } catch (e: Exception) {
            println("⚠️ Не удалось открыть папку ${folder.absolutePath}: ${e.message}")
            false
        }
    }
}