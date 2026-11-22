package org.example.defectmap.platform

import org.example.defectmap.AppPaths
import java.io.File

/**
 * Каталог, куда JavaFX распаковывает свои нативные библиотеки из fat JAR.
 *
 * По умолчанию это `~/.openjfx/cache/<версия>`. Если домашний каталог только для
 * чтения (сервисный аккаунт, NFS/AD, защищённый профиль Windows) распаковка падает
 * с `AccessDeniedException`, и приложение не стартует. Поэтому кэш переносим
 * в каталог данных приложения: он гарантированно доступен на запись.
 *
 * Должно вызываться ДО инициализации JavaFX (`Application.launch`, `Platform.startup`).
 */
object FxNativeCache {

    private const val PROP = "javafx.cachedir"

    /** Настраивает кэш, если пользователь не задал его сам. Возвращает путь или null. */
    fun configure(): String? {
        if (System.getProperty(PROP) != null) return null

        return try {
            val dir = File(AppPaths.dataDir, ".openjfx-cache")
            dir.mkdirs()
            if (!dir.isDirectory || !dir.canWrite()) {
                System.err.println("⚠️ Кэш JavaFX недоступен для записи: ${dir.absolutePath}")
                return null
            }
            System.setProperty(PROP, dir.absolutePath)
            dir.absolutePath
        } catch (t: Throwable) {
            System.err.println("⚠️ Не удалось настроить кэш JavaFX: ${t.message}")
            null
        }
    }
}
