package org.example.defectmap

import java.io.File

/**
 * Разворачивание «поставки» в пользовательский каталог данных.
 *
 * Зачем: в .deb/.rpm/AppImage приложение лежит в `/opt/defectmap` (только для чтения).
 * БД, схемы, `substations.json` и картинки должны попасть в каталог пользователя,
 * иначе приложение не сможет писать и «потеряет» стартовые данные.
 *
 * Копирование никогда не перезаписывает существующие файлы пользователя.
 */
object DataSeeder {

    /** Файлы в корне поставки, которые нужно перенести. */
    val SEED_FILES: List<String> = listOf("substations.json", "equipment_export.json")

    /** Каталоги поставки, содержимое которых копируется целиком. */
    val SEED_DIRS: List<String> = listOf("schemas", "images")

    /**
     * Копирует недостающие файлы из [fromDir] в [toDir].
     * Возвращает список скопированных относительных путей (пусто — копировать было нечего).
     */
    fun seed(fromDir: File, toDir: File): List<String> {
        val copied = mutableListOf<String>()
        if (!fromDir.isDirectory || !fromDir.canRead()) return copied
        toDir.mkdirs()

        SEED_FILES.forEach { name ->
            val src = File(fromDir, name)
            val dst = File(toDir, name)
            if (src.isFile && !dst.exists() && copy(src, dst)) copied += name
        }

        fromDir.listFiles { f: File ->
            f.isFile && isDatabase(f.name)
        }?.forEach { src ->
            val dst = File(toDir, src.name)
            if (!dst.exists() && copy(src, dst)) copied += src.name
        }

        SEED_DIRS.forEach { dirName ->
            copied += seedDirectory(File(fromDir, dirName), File(toDir, dirName))
        }

        return copied
    }

    fun isDatabase(fileName: String): Boolean =
        fileName.startsWith("equipment") && fileName.endsWith(".db", ignoreCase = true)

    private fun seedDirectory(fromDir: File, toDir: File): List<String> {
        if (!fromDir.isDirectory) return emptyList()
        val copied = mutableListOf<String>()
        toDir.mkdirs()
        fromDir.listFiles()?.forEach { src ->
            if (!src.isFile) return@forEach
            val dst = File(toDir, src.name)
            if (!dst.exists() && copy(src, dst)) copied += "${fromDir.name}/${src.name}"
        }
        return copied
    }

    private fun copy(src: File, dst: File): Boolean {
        return try {
            src.copyTo(dst, overwrite = false)
            true
        } catch (t: Throwable) {
            System.err.println("⚠️ Не удалось скопировать ${src.absolutePath} → ${dst.absolutePath}: ${t.message}")
            false
        }
    }
}
