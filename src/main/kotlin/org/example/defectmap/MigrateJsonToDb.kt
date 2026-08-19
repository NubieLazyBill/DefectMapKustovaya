package org.example.defectmap

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Скрипт для миграции данных из JSON в SQLite
 * Запустите один раз для переноса существующих меток
 */
fun main() {
    println("=".repeat(60))
    println("🔄 МИГРАЦИЯ ДАННЫХ ИЗ JSON В SQLite")
    println("=".repeat(60))

    // 1. Ищем JSON файл
    val jsonFiles = listOf(
        File("equipment.json"),
        File("data.json"),
        File(System.getProperty("user.home"), ".defectmap/equipment.json"),
        File(System.getProperty("user.home"), ".defectmap/data.json")
    )

    var jsonFile: File? = null
    for (file in jsonFiles) {
        if (file.exists()) {
            jsonFile = file
            println("✅ Найден JSON файл: ${file.absolutePath}")
            break
        }
    }

    if (jsonFile == null) {
        println("❌ JSON файл не найден!")
        println("Проверьте папки:")
        jsonFiles.forEach { println("  - ${it.absolutePath}") }
        return
    }

    // 2. Читаем JSON
    val gson = GsonBuilder().setPrettyPrinting().create()
    val jsonContent = jsonFile.readText(Charsets.UTF_8)

    try {
        val type = object : TypeToken<List<EquipmentData>>() {}.type
        val equipmentList: List<EquipmentData> = gson.fromJson(jsonContent, type)

        println("📊 Найдено ${equipmentList.size} записей в JSON")

        if (equipmentList.isEmpty()) {
            println("⚠️ JSON пустой, миграция не требуется")
            return
        }

        // 3. Сохраняем в БД
        val db = Database()
        db.saveEquipment(equipmentList)

        // 4. Проверяем сохранение
        val saved = db.loadAllEquipment()
        println("✅ Сохранено ${saved.size} записей в БД")

        // 5. Показываем первые 5 записей для проверки
        println("\n📋 Первые 5 записей:")
        saved.take(5).forEachIndexed { index, item ->
            println("  ${index + 1}. ${item.name} (${item.type}) - ячейка: ${item.cell}")
        }

        db.close()
        println("\n✅ Миграция завершена успешно!")

        // 6. Опционально: создаём бэкап JSON
        val backupFile = File(jsonFile.parent, "${jsonFile.nameWithoutExtension}_backup_${System.currentTimeMillis()}.json")
        jsonFile.copyTo(backupFile, overwrite = false)
        println("📦 Бэкап сохранён: ${backupFile.absolutePath}")

    } catch (e: Exception) {
        println("❌ Ошибка при миграции: ${e.message}")
        e.printStackTrace()
    }
}