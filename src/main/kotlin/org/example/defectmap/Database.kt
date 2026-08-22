package org.example.defectmap

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.io.File

class Database {
    private var connection: Connection? = null

    init {
        Class.forName("org.sqlite.JDBC")
        connect()
        createTable()
        createDefectsTable()
        addSizeColumnIfNotExists()
        addMarkersColumnIfNotExists()
    }

    private fun createDefectsTable() {
        val sql = """
        CREATE TABLE IF NOT EXISTS defects (
            id TEXT PRIMARY KEY,
            equipment_id TEXT NOT NULL,
            name TEXT NOT NULL,
            description TEXT,
            severity TEXT DEFAULT 'medium',
            status TEXT DEFAULT 'open',
            detection_date INTEGER,
            repair_date INTEGER,
            photo_path TEXT,
            notes TEXT,
            marker_left REAL,
            marker_top REAL,
            created_at INTEGER DEFAULT (strftime('%s', 'now')),
            updated_at INTEGER DEFAULT (strftime('%s', 'now')),
            FOREIGN KEY (equipment_id) REFERENCES equipment(id) ON DELETE CASCADE
        )
    """.trimIndent()
        executeUpdate(sql)
        println("✅ Таблица defects создана")
    }

    // ======================== ДЕФЕКТЫ ========================

    fun saveDefect(defect: DefectData) {
        val sql = """
        INSERT OR REPLACE INTO defects 
        (id, equipment_id, name, description, severity, status, detection_date, repair_date, photo_path, notes, marker_left, marker_top, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, strftime('%s', 'now'))
    """.trimIndent()

        connection?.prepareStatement(sql)?.use { stmt ->
            stmt.setString(1, defect.id)
            stmt.setString(2, defect.equipmentId)
            stmt.setString(3, defect.name)
            stmt.setString(4, defect.description)
            stmt.setString(5, defect.severity)
            stmt.setString(6, defect.status)
            stmt.setLong(7, defect.detectionDate)
            if (defect.repairDate != null) stmt.setLong(8, defect.repairDate) else stmt.setNull(8, java.sql.Types.INTEGER)
            stmt.setString(9, defect.photoPath)
            stmt.setString(10, defect.notes)
            if (defect.markerLeft != null) stmt.setDouble(11, defect.markerLeft) else stmt.setNull(11, java.sql.Types.REAL)
            if (defect.markerTop != null) stmt.setDouble(12, defect.markerTop) else stmt.setNull(12, java.sql.Types.REAL)
            stmt.executeUpdate()
        }
        println("💾 Дефект сохранён: ${defect.name}")
        // После сохранения — автоэкспорт
        exportAllToJson()
    }

    fun getDefectsByEquipment(equipmentId: String): List<DefectData> {
        val result = mutableListOf<DefectData>()
        val sql = "SELECT * FROM defects WHERE equipment_id = ? ORDER BY severity DESC, detection_date DESC"

        connection?.prepareStatement(sql)?.use { stmt ->
            stmt.setString(1, equipmentId)
            val rs = stmt.executeQuery()
            while (rs.next()) {
                result.add(mapRowToDefect(rs))
            }
        }
        return result
    }

    fun deleteDefect(defectId: String) {
        val sql = "DELETE FROM defects WHERE id = ?"
        connection?.prepareStatement(sql)?.use { stmt ->
            stmt.setString(1, defectId)
            stmt.executeUpdate()
        }
        println("🗑️ Дефект удалён: $defectId")
        exportAllToJson()
    }

    fun updateDefect(defect: DefectData) {
        saveDefect(defect)
    }

    private fun mapRowToDefect(rs: ResultSet): DefectData {
        return DefectData(
            id = rs.getString("id"),
            equipmentId = rs.getString("equipment_id"),
            name = rs.getString("name"),
            description = rs.getString("description") ?: "",
            severity = rs.getString("severity") ?: "medium",
            status = rs.getString("status") ?: "open",
            detectionDate = rs.getLong("detection_date"),
            repairDate = if (rs.getObject("repair_date") != null) rs.getLong("repair_date") else null,
            photoPath = rs.getString("photo_path"),
            notes = rs.getString("notes") ?: "",
            markerLeft = if (rs.getObject("marker_left") != null) rs.getDouble("marker_left") else null,
            markerTop = if (rs.getObject("marker_top") != null) rs.getDouble("marker_top") else null
        )
    }

    private fun addMarkersColumnIfNotExists() {
        try {
            executeUpdate("ALTER TABLE equipment ADD COLUMN markers TEXT DEFAULT '[]'")
            println("✅ Колонка markers добавлена")
        } catch (e: Exception) {
            println("ℹ️ Колонка markers уже существует")
        }
    }

    private fun connect() {
        val dbFile = File(System.getProperty("user.home"), ".defectmap/equipment.db")
        val dbPath = dbFile.absolutePath
        connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        connection?.autoCommit = true
        println("✅ База данных подключена: $dbPath")
    }

    private fun createTable() {
        val sql = """
            CREATE TABLE IF NOT EXISTS equipment (
                id TEXT PRIMARY KEY,
                left_pos REAL NOT NULL,
                top_pos REAL NOT NULL,
                type TEXT NOT NULL,
                name TEXT NOT NULL,
                letter TEXT NOT NULL,
                cell TEXT DEFAULT '',
                size TEXT DEFAULT 'normal',
                markers TEXT DEFAULT '[]',  -- <-- НОВАЯ КОЛОНКА
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER DEFAULT (strftime('%s', 'now'))
            )
        """.trimIndent()
        executeUpdate(sql)
        println("✅ Таблица equipment создана")
    }

    private fun addSizeColumnIfNotExists() {
        try {
            executeUpdate("ALTER TABLE equipment ADD COLUMN size TEXT DEFAULT 'normal'")
            println("✅ Колонка size добавлена")
        } catch (e: Exception) {
            // Колонка уже существует — ничего не делаем
            println("ℹ️ Колонка size уже существует")
        }
    }

    // ======================== СОХРАНЕНИЕ ========================

    private val exportFile: File by lazy {
        val dir = File(System.getProperty("user.home"), ".defectmap")
        if (!dir.exists()) dir.mkdirs()
        File(dir, "equipment_export.json")
    }

    fun saveEquipment(equipment: List<EquipmentData>) {
        if (equipment.isEmpty()) {
            println("⚠️ Нет данных для сохранения")
            return
        }

        val gson = GsonBuilder().create()
        val sql = """
        INSERT OR REPLACE INTO equipment 
        (id, left_pos, top_pos, type, name, letter, cell, size, markers, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, strftime('%s', 'now'))
    """.trimIndent()

        connection?.prepareStatement(sql)?.use { stmt ->
            equipment.forEach { item ->
                // ===== БЕЗОПАСНАЯ ПРОВЕРКА: если markers == null, создаём пустой список =====
                val safeMarkers = item.markers ?: listOf()

                // Сохраняем основной маркер (первый или isMain=true)
                val mainMarker = if (safeMarkers.isNotEmpty()) {
                    safeMarkers.firstOrNull { it.isMain } ?: safeMarkers.first()
                } else {
                    // Если маркеров нет — создаём из left/top
                    MarkerPosition(item.left, item.top, true)
                }

                // Сохраняем все маркеры в JSON (если null — сохраняем пустой массив)
                val markersJson = if (safeMarkers.isNotEmpty()) {
                    gson.toJson(safeMarkers)
                } else {
                    // Если маркеров нет — сохраняем один из left/top
                    gson.toJson(listOf(MarkerPosition(item.left, item.top, true)))
                }

                stmt.setString(1, item.id)
                stmt.setDouble(2, mainMarker.left)
                stmt.setDouble(3, mainMarker.top)
                stmt.setString(4, item.type)
                stmt.setString(5, item.name)
                stmt.setString(6, item.letter)
                stmt.setString(7, item.cell)
                stmt.setString(8, item.size)
                stmt.setString(9, markersJson)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
        println("💾 Сохранено ${equipment.size} записей в БД")
        exportToJson(equipment)
        println("📤 Автоэкспорт в JSON выполнен")
    }

    // ======================== ЗАГРУЗКА ========================

    fun loadAllEquipment(): List<EquipmentData> {
        val result = mutableListOf<EquipmentData>()
        val sql = "SELECT * FROM equipment ORDER BY name"

        connection?.prepareStatement(sql)?.use { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) {
                result.add(mapRowToEquipment(rs))
            }
        }
        println("📂 Загружено ${result.size} записей из БД")
        return result
    }

    fun findByType(type: String): List<EquipmentData> {
        val result = mutableListOf<EquipmentData>()
        val sql = "SELECT * FROM equipment WHERE type = ? ORDER BY name"

        connection?.prepareStatement(sql)?.use { stmt ->
            stmt.setString(1, type)
            val rs = stmt.executeQuery()
            while (rs.next()) {
                result.add(mapRowToEquipment(rs))
            }
        }
        return result
    }

    fun searchByName(query: String): List<EquipmentData> {
        val result = mutableListOf<EquipmentData>()
        val sql = "SELECT * FROM equipment WHERE name LIKE ? ORDER BY name"

        connection?.prepareStatement(sql)?.use { stmt ->
            stmt.setString(1, "%$query%")
            val rs = stmt.executeQuery()
            while (rs.next()) {
                result.add(mapRowToEquipment(rs))
            }
        }
        return result
    }

    fun findByCell(cell: String): List<EquipmentData> {
        val result = mutableListOf<EquipmentData>()
        val sql = "SELECT * FROM equipment WHERE cell = ? ORDER BY name"

        connection?.prepareStatement(sql)?.use { stmt ->
            stmt.setString(1, cell)
            val rs = stmt.executeQuery()
            while (rs.next()) {
                result.add(mapRowToEquipment(rs))
            }
        }
        return result
    }

    // ======================== УДАЛЕНИЕ ========================

    fun deleteById(id: String): Boolean {
        val sql = "DELETE FROM equipment WHERE id = ?"
        return connection?.prepareStatement(sql)?.use { stmt ->
            stmt.setString(1, id)
            stmt.executeUpdate() > 0
        } ?: false
    }

    fun deleteAll(): Boolean {
        val sql = "DELETE FROM equipment"
        return connection?.prepareStatement(sql)?.use { stmt ->
            stmt.executeUpdate() > 0
        } ?: false
    }

    // ======================== СТАТИСТИКА ========================

    fun getStatistics(): Map<String, Int> {
        val stats = mutableMapOf<String, Int>()
        val sql = "SELECT type, COUNT(*) as count FROM equipment GROUP BY type ORDER BY count DESC"

        connection?.prepareStatement(sql)?.use { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) {
                val type = rs.getString("type")
                val count = rs.getInt("count")
                val typeName = EquipmentTypes.getTypeName(type)
                stats[typeName] = count
            }
        }
        return stats
    }

    fun getCount(): Int {
        val sql = "SELECT COUNT(*) as count FROM equipment"
        connection?.prepareStatement(sql)?.use { stmt ->
            val rs = stmt.executeQuery()
            return rs.getInt("count")
        }
        return 0
    }

    fun getCountBySize(size: String): Int {
        val sql = "SELECT COUNT(*) as count FROM equipment WHERE size = ?"
        connection?.prepareStatement(sql)?.use { stmt ->
            stmt.setString(1, size)
            val rs = stmt.executeQuery()
            return rs.getInt("count")
        }
        return 0
    }

    // ======================== ВСПОМОГАТЕЛЬНЫЕ ========================

    private fun mapRowToEquipment(rs: ResultSet): EquipmentData {
        val gson = GsonBuilder().create()
        val markersJson = rs.getString("markers") ?: "[]"
        val markers: List<MarkerPosition> = try {
            val type = object : TypeToken<List<MarkerPosition>>() {}.type
            gson.fromJson(markersJson, type)
        } catch (e: Exception) {
            // Если не удалось распарсить — создаём из left/top
            listOf(MarkerPosition(rs.getDouble("left_pos"), rs.getDouble("top_pos"), true))
        }

        return EquipmentData(
            id = rs.getString("id"),
            left = rs.getDouble("left_pos"),
            top = rs.getDouble("top_pos"),
            type = rs.getString("type"),
            name = rs.getString("name"),
            letter = rs.getString("letter"),
            cell = rs.getString("cell") ?: "",
            size = rs.getString("size") ?: "normal",
            markers = markers  // <-- НОВОЕ!
        )
    }

    private fun executeUpdate(sql: String) {
        connection?.createStatement()?.use { stmt ->
            stmt.executeUpdate(sql)
        }
    }

    fun close() {
        connection?.close()
        println("🔒 База данных закрыта")
    }

    // ======================== ЭКСПОРТ/ИМПОРТ ========================

    fun getLastExportTimestamp(): Long {
        return if (exportFile.exists()) {
            exportFile.lastModified()
        } else {
            0L
        }
    }

    fun getLastDbUpdate(): Long {
        val sql = "SELECT MAX(updated_at) as max_updated FROM equipment"
        connection?.prepareStatement(sql)?.use { stmt ->
            val rs = stmt.executeQuery()
            if (rs.next()) {
                return rs.getLong("max_updated") * 1000 // Unix timestamp в миллисекунды
            }
        }
        return 0L
    }

    fun exportToJson(equipment: List<EquipmentData>) {
        try {
            val gson = GsonBuilder().setPrettyPrinting().create()
            val exportData = equipment.map { eq ->
                // Загружаем дефекты для этого оборудования
                val defects = getDefectsByEquipment(eq.id)
                mapOf(
                    "id" to eq.id,
                    "left" to eq.left,
                    "top" to eq.top,
                    "type" to eq.type,
                    "name" to eq.name,
                    "letter" to eq.letter,
                    "cell" to eq.cell,
                    "size" to eq.size,
                    "markers" to eq.markers,
                    "defects" to defects  // <-- ДОБАВЛЯЕМ ДЕФЕКТЫ!
                )
            }
            val json = gson.toJson(exportData)
            val exportFile = File(System.getProperty("user.home"), ".defectmap/equipment_export.json")
            exportFile.parentFile?.mkdirs()
            exportFile.writeText(json, Charsets.UTF_8)
            println("📤 Экспортировано ${equipment.size} записей с дефектами в JSON")
        } catch (e: Exception) {
            println("❌ Ошибка экспорта: ${e.message}")
        }
    }

    fun importFromJson(): List<EquipmentData>? {
        try {
            val importFile = File(System.getProperty("user.home"), ".defectmap/equipment_export.json")
            if (!importFile.exists()) {
                println("⚠️ Файл экспорта не найден")
                return null
            }

            val json = importFile.readText(Charsets.UTF_8)
            val gson = GsonBuilder().create()
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val data: List<Map<String, Any>> = gson.fromJson(json, type)

            val result = data.map { map ->
                val markersJson = map["markers"] as? String ?: "[]"
                val markersType = object : TypeToken<List<MarkerPosition>>() {}.type
                val markers: List<MarkerPosition> = try {
                    gson.fromJson(markersJson, markersType)
                } catch (e: Exception) {
                    listOf(MarkerPosition(
                        (map["left"] as? Double) ?: 0.0,
                        (map["top"] as? Double) ?: 0.0,
                        true
                    ))
                }

                val equipmentData = EquipmentData(
                    id = map["id"] as? String ?: "",
                    left = (map["left"] as? Double) ?: 0.0,
                    top = (map["top"] as? Double) ?: 0.0,
                    type = map["type"] as? String ?: "",
                    name = map["name"] as? String ?: "",
                    letter = map["letter"] as? String ?: "",
                    cell = map["cell"] as? String ?: "",
                    size = map["size"] as? String ?: "normal",
                    markers = markers
                )

                // ===== ИМПОРТ ДЕФЕКТОВ (БЕЗ УДАЛЕНИЯ) =====
                val defectsJson = map["defects"] as? String ?: "[]"
                val defectsType = object : TypeToken<List<DefectData>>() {}.type
                val defects: List<DefectData> = try {
                    gson.fromJson(defectsJson, defectsType)
                } catch (e: Exception) {
                    emptyList()
                }

                // Просто сохраняем дефекты (INSERT OR REPLACE)
                defects.forEach { saveDefect(it) }

                equipmentData
            }

            println("📥 Импортировано ${result.size} записей из JSON")
            return result
        } catch (e: Exception) {
            println("❌ Ошибка импорта: ${e.message}")
            return null
        }
    }

    // ======================== ЭКСПОРТ ВСЕХ ДАННЫХ ========================

    fun exportAllToJson() {
        val allEquipment = loadAllEquipment()
        println("📊 Экспортируем ${allEquipment.size} записей")
        allEquipment.forEach { eq ->
            val defects = getDefectsByEquipment(eq.id)
            println("  📌 ${eq.name}: ${defects.size} дефектов")
        }
        exportToJson(allEquipment)
    }
}

// ======================== DATA CLASSES ========================

data class EquipmentData(
    val id: String,
    val left: Double,
    val top: Double,
    val type: String,
    val name: String,
    val letter: String,
    val cell: String = "",
    val size: String = "normal",
    val markers: List<MarkerPosition> = listOf()  // НОВОЕ ПОЛЕ
)

data class MarkerPosition(
    val left: Double,
    val top: Double,
    val isMain: Boolean = false
)

data class EquipmentTableItem(
    val number: Int,
    val id: String,
    val name: String,
    val type: String,
    val left: Double,
    val top: Double,
    val cell: String,
    val size: String = "normal"
)