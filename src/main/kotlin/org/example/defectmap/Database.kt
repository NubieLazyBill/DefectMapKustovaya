package org.example.defectmap

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
        addSizeColumnIfNotExists()
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

    fun saveEquipment(equipment: List<EquipmentData>) {
        if (equipment.isEmpty()) {
            println("⚠️ Нет данных для сохранения")
            return
        }

        val sql = """
            INSERT OR REPLACE INTO equipment 
            (id, left_pos, top_pos, type, name, letter, cell, size, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, strftime('%s', 'now'))
        """.trimIndent()

        connection?.prepareStatement(sql)?.use { stmt ->
            equipment.forEach { item ->
                stmt.setString(1, item.id)
                stmt.setDouble(2, item.left)
                stmt.setDouble(3, item.top)
                stmt.setString(4, item.type)
                stmt.setString(5, item.name)
                stmt.setString(6, item.letter)
                stmt.setString(7, item.cell)
                stmt.setString(8, item.size)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
        println("💾 Сохранено ${equipment.size} записей в БД")
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
        return EquipmentData(
            id = rs.getString("id"),
            left = rs.getDouble("left_pos"),
            top = rs.getDouble("top_pos"),
            type = rs.getString("type"),
            name = rs.getString("name"),
            letter = rs.getString("letter"),
            cell = rs.getString("cell") ?: "",
            size = rs.getString("size") ?: "normal"
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
    val size: String = "normal"  // small, normal, large
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