package org.example.defectmap.diagnostics

import org.example.defectmap.AppPaths
import org.example.defectmap.Database
import org.example.defectmap.EquipmentModels
import java.io.File

/**
 * `--migrate-db`: применяет миграции схемы ко всем БД в каталоге данных.
 *
 * Нужно после обновления приложения: добавляются таблица `equipment_models`
 * и колонка `equipment.model_id`, существующие фото привязываются к моделям
 * по умолчанию. Данные оборудования и дефектов не переписываются.
 *
 * Каталог данных берётся из `AppPaths` (переопределяется
 * `-Ddefectmap.home=/path` или `DEFECTMAP_HOME`).
 */
object DbMigrate {

    fun run(args: List<String>): Int {
        println("=".repeat(70))
        println("DefectMap — миграция схемы БД (типы изделий)")
        println("=".repeat(70))
        println("  Каталог данных: ${AppPaths.dataDir.absolutePath}")

        val dbs = AppPaths.dataDir.listFiles { f ->
            f.isFile && f.name.startsWith("equipment") && f.name.endsWith(".db")
        }?.sortedBy { it.name } ?: emptyList()

        if (dbs.isEmpty()) {
            println("  ✗ Файлы equipment*.db не найдены")
            return 1
        }

        var failed = 0
        dbs.forEach { file ->
            val key = file.name.removePrefix("equipment_").removeSuffix(".db")
            try {
                val db = Database(key)
                try {
                    val models = db.loadAllModels()
                    val bound = db.countEquipmentWithModel()
                    println(
                        "  [OK]   ${file.name}: моделей ${models.size}, " +
                            "привязано оборудования $bound из ${db.getCount()}"
                    )
                } finally {
                    db.close()
                }
            } catch (t: Throwable) {
                failed++
                println("  [FAIL] ${file.name}: ${t.message}")
            }
        }

        println("-".repeat(70))
        println(if (failed == 0) "Результат: OK" else "Результат: FAIL (ошибок: $failed)")
        println("-".repeat(70))
        return if (failed == 0) 0 else 1
    }
}
