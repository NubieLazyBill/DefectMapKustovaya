import org.example.defectmap.Database

// MigrateAddSizeField.kt
fun main() {
    val db = Database()
    val all = db.loadAllEquipment()

    // Добавляем size = "normal" для всех существующих записей
    val updated = all.map {
        it.copy(size = if (it.type.contains("220") || it.type.contains("35")) "small" else "normal")
    }

    db.saveEquipment(updated)
    println("✅ Обновлено ${updated.size} записей")
    println("📊 Из них small: ${updated.count { it.size == "small" }}")
    db.close()
}