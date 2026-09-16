package org.example.defectmap

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.io.File
import kotlin.io.use

class Database(private val substationKey: String = "kustovaya") {
    private var connection: Connection? = null

    // ===== ПОРТАТИВНЫЙ ПУТЬ: БД В ПАПКЕ С JAR =====
    private val APP_DIR: String by lazy { getAppDirectory() }

    // Путь к БД зависит от подстанции
    private val DB_PATH: String by lazy {
        File(APP_DIR, "equipment_$substationKey.db").absolutePath
    }

    // Папка бэкапов тоже раздельная по подстанциям
    private val BACKUP_DIR: File by lazy {
        File(APP_DIR, "backups/$substationKey").also { it.mkdirs() }
    }

    init {
        Class.forName("org.sqlite.JDBC")
        connect()
        createTable()
        createDefectsTable()
        createTypesTable()
        addSizeColumnIfNotExists()
        addMarkersColumnIfNotExists()
        migrateMarkerIds()
        createDefectTypesTable()
        addParentIdColumnIfNotExists()
        createSettingsTable()
    }

    private fun addParentIdColumnIfNotExists() {
        try {
            executeUpdate("ALTER TABLE equipment ADD COLUMN parent_id TEXT DEFAULT NULL")
            println("✅ Колонка parent_id добавлена")
        } catch (e: Exception) {
            println("ℹ️ Колонка parent_id уже существует")
        }
    }

    // ======================== ОПРЕДЕЛЕНИЕ ПАПКИ ПРИЛОЖЕНИЯ ========================

    private fun getAppDirectory(): String {
        return try {
            val codeSource = Database::class.java.protectionDomain.codeSource
            val location = codeSource.location.toURI().path
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

    // ======================== ПОДКЛЮЧЕНИЕ ========================

    private fun connect() {
        val dbFile = File(DB_PATH)
        dbFile.parentFile?.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:$DB_PATH")
        connection?.autoCommit = true
        println("✅ База данных подключена: $DB_PATH")
    }

    fun close() {
        connection?.close()
        println("🔒 База данных закрыта")
    }

    fun reconnect() {
        close()
        connect()
        println("🔄 Соединение с БД переустановлено")
    }

    // ======================== ТАБЛИЦЫ ========================

    private fun createTable() {
        val sql = """
            CREATE TABLE IF NOT EXISTS equipment (
                id TEXT PRIMARY KEY,
                type TEXT NOT NULL,
                name TEXT NOT NULL,
                letter TEXT NOT NULL,
                cell TEXT DEFAULT '',
                size TEXT DEFAULT 'normal',
                markers TEXT DEFAULT '[]',
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER DEFAULT (strftime('%s', 'now'))
            )
        """.trimIndent()
        executeUpdate(sql)
        println("✅ Таблица equipment создана")
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

    private fun addSizeColumnIfNotExists() {
        try {
            executeUpdate("ALTER TABLE equipment ADD COLUMN size TEXT DEFAULT 'normal'")
            println("✅ Колонка size добавлена")
        } catch (e: Exception) {
            println("ℹ️ Колонка size уже существует")
        }
    }

    private fun addMarkersColumnIfNotExists() {
        try {
            executeUpdate("ALTER TABLE equipment ADD COLUMN markers TEXT DEFAULT '[]'")
            println("✅ Колонка markers добавлена")
        } catch (e: Exception) {
            println("ℹ️ Колонка markers уже существует")
        }
    }

    // ======================== БЭКАП ========================

    fun autoBackup() {
        val dbFile = File(DB_PATH)
        if (!dbFile.exists()) return

        BACKUP_DIR.mkdirs()

        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(java.util.Date())
        val backupFile = File(BACKUP_DIR, "equipment_$timestamp.db")
        dbFile.copyTo(backupFile, overwrite = false)
        println("💾 Автобэкап создан: ${backupFile.absolutePath}")
    }

    // ======================== ОБОРУДОВАНИЕ ========================

    fun saveEquipment(equipment: List<EquipmentData>) {
        if (equipment.isEmpty()) {
            println("⚠️ Нет данных для сохранения")
            return
        }

        val gson = GsonBuilder().create()
        val sql = """
        INSERT OR REPLACE INTO equipment 
        (id, type, name, letter, cell, size, markers, parent_id, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, strftime('%s', 'now'))
    """.trimIndent()

        connection?.prepareStatement(sql)?.use { stmt ->
            equipment.forEach { item ->
                val safeMarkers = item.markers
                val markersJson = if (safeMarkers.isNotEmpty()) {
                    gson.toJson(safeMarkers)
                } else {
                    gson.toJson(listOf(MarkerPosition(item.left, item.top, true)))
                }

                stmt.setString(1, item.id)
                stmt.setString(2, item.type)
                stmt.setString(3, item.name)
                stmt.setString(4, item.letter)
                stmt.setString(5, item.cell)
                stmt.setString(6, item.size)
                stmt.setString(7, markersJson)
                stmt.setString(8, item.parentId)  // ← может быть null
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
        println("💾 Сохранено ${equipment.size} записей в БД")
    }

    fun loadAllEquipment(): List<EquipmentData> {
        val result = mutableListOf<EquipmentData>()
        val sql = "SELECT * FROM equipment ORDER BY name"

        connection?.prepareStatement(sql)?.use { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) {
                result.add(mapRowToEquipment(rs))
            }
        }
        return result
    }

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

    fun getCount(): Int {
        val sql = "SELECT COUNT(*) as count FROM equipment"
        connection?.prepareStatement(sql)?.use { stmt ->
            val rs = stmt.executeQuery()
            return rs.getInt("count")
        }
        return 0
    }

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

    private fun mapRowToEquipment(rs: ResultSet): EquipmentData {
        val gson = GsonBuilder().create()
        val markersJson = rs.getString("markers") ?: "[]"
        val markers: List<MarkerPosition> = try {
            val type = object : TypeToken<List<MarkerPosition>>() {}.type
            gson.fromJson(markersJson, type)
        } catch (e: Exception) {
            listOf(MarkerPosition(
                rs.getDouble("left"),
                rs.getDouble("top"),
                true
            ))
        }

        return EquipmentData(
            id = rs.getString("id"),
            left = markers.firstOrNull()?.left ?: 0.0,
            top = markers.firstOrNull()?.top ?: 0.0,
            type = rs.getString("type"),
            name = rs.getString("name"),
            letter = rs.getString("letter"),
            cell = rs.getString("cell") ?: "",
            size = rs.getString("size") ?: "normal",
            markers = markers,
            parentId = rs.getString("parent_id")  // ← ДОБАВЛЕНО
        )
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
    }

    fun updateDefect(defect: DefectData) {
        saveDefect(defect)
    }

    fun getDefectsCount(): Int {
        val sql = "SELECT COUNT(*) as count FROM defects"
        connection?.prepareStatement(sql)?.use { stmt ->
            val rs = stmt.executeQuery()
            return rs.getInt("count")
        }
        return 0
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

    // ======================== МИГРАЦИЯ ========================

    private fun migrateMarkerIds() {
        try {
            val sql = "SELECT id FROM equipment WHERE id LIKE 'marker-%'"
            val stmt = connection?.prepareStatement(sql)
            val rs = stmt?.executeQuery()
            val idsToUpdate = mutableListOf<String>()
            while (rs?.next() == true) {
                idsToUpdate.add(rs.getString("id"))
            }
            rs?.close()
            stmt?.close()

            if (idsToUpdate.isNotEmpty()) {
                println("🔄 Найдено ${idsToUpdate.size} записей с marker- ID, исправляем...")
                idsToUpdate.forEach { oldId ->
                    val newId = oldId.replace("marker-", "equipment-")
                    val updateSql = "UPDATE equipment SET id = ? WHERE id = ?"
                    connection?.prepareStatement(updateSql)?.use { updateStmt ->
                        updateStmt.setString(1, newId)
                        updateStmt.setString(2, oldId)
                        updateStmt.executeUpdate()
                        println("  ✅ $oldId → $newId")
                    }
                }
                println("✅ Миграция ID завершена")
            }
        } catch (e: Exception) {
            println("⚠️ Ошибка миграции ID: ${e.message}")
        }
    }

    // ======================== РУЧНОЙ ИМПОРТ/ЭКСПОРТ (ПО ТРЕБОВАНИЮ) ========================

    fun exportToJson(equipment: List<EquipmentData>) {
        try {
            val gson = GsonBuilder().setPrettyPrinting().create()
            val exportData = equipment.map { eq ->
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
                    "defects" to defects
                )
            }
            val json = gson.toJson(exportData)
            val exportFile = File(APP_DIR, "equipment_export.json")
            exportFile.writeText(json, Charsets.UTF_8)
            println("📤 Экспортировано ${equipment.size} записей с дефектами в JSON")
            println("📁 Файл: ${exportFile.absolutePath}")
        } catch (e: Exception) {
            println("❌ Ошибка экспорта: ${e.message}")
        }
    }

    fun exportAllToJson() {
        val allEquipment = loadAllEquipment()
        println("📊 Экспортируем ${allEquipment.size} записей")
        allEquipment.forEach { eq ->
            val defects = getDefectsByEquipment(eq.id)
            println("  📌 ${eq.name}: ${defects.size} дефектов")
        }
        exportToJson(allEquipment)
    }

    fun importFromJson(): List<EquipmentData>? {
        try {
            val importFile = File(APP_DIR, "equipment_export.json")
            if (!importFile.exists()) {
                println("⚠️ Файл экспорта не найден: ${importFile.absolutePath}")
                return null
            }

            val json = importFile.readText(Charsets.UTF_8)
            val gson = GsonBuilder().create()
            val type = object : TypeToken<List<Map<String, Any>>>() {}.type
            val data: List<Map<String, Any>> = gson.fromJson(json, type)

            val result = mutableListOf<EquipmentData>()

            data.forEach { map ->
                val id = map["id"] as? String ?: ""
                if (id.isEmpty()) return@forEach

                val markersData = map["markers"]
                val markers: List<MarkerPosition> = when (markersData) {
                    is List<*> -> {
                        markersData.mapNotNull {
                            when (it) {
                                is Map<*, *> -> {
                                    val left = (it["left"] as? Number)?.toDouble() ?: 0.0
                                    val top = (it["top"] as? Number)?.toDouble() ?: 0.0
                                    val isMain = (it["isMain"] as? Boolean) ?: false
                                    MarkerPosition(left, top, isMain)
                                }
                                else -> null
                            }
                        }
                    }
                    is String -> {
                        try {
                            val markersType = object : TypeToken<List<MarkerPosition>>() {}.type
                            gson.fromJson(markersData, markersType)
                        } catch (e: Exception) {
                            listOf(MarkerPosition(
                                (map["left"] as? Number)?.toDouble() ?: 0.0,
                                (map["top"] as? Number)?.toDouble() ?: 0.0,
                                true
                            ))
                        }
                    }
                    else -> {
                        listOf(MarkerPosition(
                            (map["left"] as? Number)?.toDouble() ?: 0.0,
                            (map["top"] as? Number)?.toDouble() ?: 0.0,
                            true
                        ))
                    }
                }

                val equipment = EquipmentData(
                    id = id,
                    left = (map["left"] as? Number)?.toDouble() ?: 0.0,
                    top = (map["top"] as? Number)?.toDouble() ?: 0.0,
                    type = map["type"] as? String ?: "",
                    name = map["name"] as? String ?: "",
                    letter = map["letter"] as? String ?: "",
                    cell = map["cell"] as? String ?: "",
                    size = map["size"] as? String ?: "normal",
                    markers = markers
                )
                result.add(equipment)
            }

            data.forEach { map ->
                val defectsJson = map["defects"] as? String ?: "[]"
                try {
                    val defectsType = object : TypeToken<List<DefectData>>() {}.type
                    val defects: List<DefectData> = gson.fromJson(defectsJson, defectsType)
                    defects.forEach { saveDefect(it) }
                } catch (e: Exception) {
                    // Игнорируем ошибки парсинга дефектов
                }
            }

            println("📥 Импортировано ${result.size} записей из JSON")
            return result
        } catch (e: Exception) {
            println("❌ Ошибка импорта: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    fun syncFileTimestamps() {
        try {
            val dbFile = File(DB_PATH)
            val exportFile = File(APP_DIR, "equipment_export.json")
            if (dbFile.exists() && exportFile.exists()) {
                exportFile.setLastModified(dbFile.lastModified())
                println("🔄 Время JSON синхронизировано с БД")
            }
        } catch (e: Exception) {
            println("⚠️ Не удалось синхронизировать время файлов: ${e.message}")
        }
    }

    // ======================== ВСПОМОГАТЕЛЬНЫЕ ========================

    private fun executeUpdate(sql: String) {
        connection?.createStatement()?.use { stmt ->
            stmt.executeUpdate(sql)
        }
    }

    // Добавить в Database.kt

// ======================== ТИПЫ ОБОРУДОВАНИЯ (ДИНАМИЧЕСКИЕ) ========================

    private fun createTypesTable() {
        val sql = """
        CREATE TABLE IF NOT EXISTS equipment_types (
            key TEXT PRIMARY KEY,
            display_name TEXT NOT NULL,
            letter TEXT NOT NULL,
            sort_order INTEGER DEFAULT 0,
            created_at INTEGER DEFAULT (strftime('%s', 'now'))
        )
    """.trimIndent()
        executeUpdate(sql)
        println("✅ Таблица equipment_types создана")
    }

    fun loadAllTypes(): List<EquipmentTypeData> {
        val result = mutableListOf<EquipmentTypeData>()
        val sql = "SELECT * FROM equipment_types ORDER BY sort_order, display_name"

        connection?.prepareStatement(sql)?.use { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) {
                result.add(
                    EquipmentTypeData(
                        key = rs.getString("key"),
                        displayName = rs.getString("display_name"),
                        letter = rs.getString("letter"),
                        sortOrder = rs.getInt("sort_order")
                    )
                )
            }
        }
        return result
    }

    fun saveType(type: EquipmentTypeData) {
        val sql = """
        INSERT OR REPLACE INTO equipment_types (key, display_name, letter, sort_order)
        VALUES (?, ?, ?, ?)
    """.trimIndent()
        connection?.prepareStatement(sql)?.use { stmt ->
            stmt.setString(1, type.key)
            stmt.setString(2, type.displayName)
            stmt.setString(3, type.letter)
            stmt.setInt(4, type.sortOrder)
            stmt.executeUpdate()
        }
        println("💾 Тип сохранён: ${type.key} → ${type.displayName}")
    }

    fun deleteType(key: String) {
        val sql = "DELETE FROM equipment_types WHERE key = ?"
        connection?.prepareStatement(sql)?.use { stmt ->
            stmt.setString(1, key)
            stmt.executeUpdate()
        }
        println("🗑️ Тип удалён: $key")
    }

    fun restoreDefaultTypes() {
        // Очищаем таблицу
        executeUpdate("DELETE FROM equipment_types")

        // Добавляем дефолтные типы из EquipmentTypes.ALL_TYPES
        EquipmentTypes.ALL_TYPES.forEachIndexed { index, (key, displayName) ->
            val letter = EquipmentTypes.TYPE_TO_LETTER[key] ?: "О"
            saveType(
                EquipmentTypeData(
                    key = key,
                    displayName = displayName,
                    letter = letter,
                    sortOrder = index
                )
            )
        }
        println("✅ Дефолтные типы восстановлены (${EquipmentTypes.ALL_TYPES.size} шт.)")
    }

    // ======================== ВИДЫ ДЕФЕКТОВ (ДИНАМИЧЕСКИЕ) ========================

    private fun createDefectTypesTable() {
        val sql = """
        CREATE TABLE IF NOT EXISTS defect_types (
            name TEXT PRIMARY KEY,
            sort_order INTEGER DEFAULT 0,
            created_at INTEGER DEFAULT (strftime('%s', 'now'))
        )
    """.trimIndent()
        executeUpdate(sql)
        println("✅ Таблица defect_types создана")
    }

    fun loadAllDefectTypes(): List<String> {
        val result = mutableListOf<String>()
        val sql = "SELECT name FROM defect_types ORDER BY sort_order, name"

        connection?.prepareStatement(sql)?.use { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) {
                result.add(rs.getString("name"))
            }
        }
        return result
    }

    fun saveDefectType(name: String, sortOrder: Int = 0) {
        val sql = """
        INSERT OR REPLACE INTO defect_types (name, sort_order)
        VALUES (?, ?)
    """.trimIndent()
        connection?.prepareStatement(sql)?.use { stmt ->
            stmt.setString(1, name)
            stmt.setInt(2, sortOrder)
            stmt.executeUpdate()
        }
        println("💾 Вид дефекта сохранён: $name")
    }

    fun deleteDefectType(name: String) {
        val sql = "DELETE FROM defect_types WHERE name = ?"
        connection?.prepareStatement(sql)?.use { stmt ->
            stmt.setString(1, name)
            stmt.executeUpdate()
        }
        println("🗑️ Вид дефекта удалён: $name")
    }

    fun restoreDefaultDefectTypes() {
        executeUpdate("DELETE FROM defect_types")

        val defaultTypes = DefectTypes.getDefaultTypes()
        defaultTypes.forEachIndexed { index, name ->
            saveDefectType(name, index)
        }
        println("✅ Дефолтные виды дефектов восстановлены (${defaultTypes.size} шт.)")
    }

    // ======================== НАСТРОЙКИ (settings) ========================

    private fun createSettingsTable() {
        val sql = """
        CREATE TABLE IF NOT EXISTS settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        )
    """.trimIndent()
        executeUpdate(sql)
        println("✅ Таблица settings создана")
    }

    fun getSetting(key: String): String? {
        val sql = "SELECT value FROM settings WHERE key = ?"
        return connection?.prepareStatement(sql)?.use { stmt ->
            stmt.setString(1, key)
            val rs = stmt.executeQuery()
            if (rs.next()) rs.getString("value") else null
        }
    }

    fun saveSetting(key: String, value: String) {
        val sql = "INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)"
        connection?.prepareStatement(sql)?.use { stmt ->
            stmt.setString(1, key)
            stmt.setString(2, value)
            stmt.executeUpdate()
        }
        println("💾 Настройка сохранена: $key = $value")
    }

    fun getMarkerScale(): Double {
        val value = getSetting("marker_scale")
        return value?.toDoubleOrNull() ?: 1.0
    }

    fun setMarkerScale(scale: Double) {
        saveSetting("marker_scale", scale.toString())
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
    val markers: List<MarkerPosition> = listOf(),
    val parentId: String? = null,
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

// ===== В КОНЦЕ ФАЙЛА, ПОСЛЕ ЗАКРЫТИЯ КЛАССА Database =====

data class EquipmentTypeData(
    val key: String,
    val displayName: String,
    val letter: String,
    val sortOrder: Int = 0
)


