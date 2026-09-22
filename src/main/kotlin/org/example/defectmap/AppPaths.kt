package org.example.defectmap

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
}