package org.example.defectmap

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.sql.ResultSet
import java.io.File

class Database {
    private var connection: Connection? = null

    init {
        // Загружаем драйвер SQLite
        Class.forName("org.sqlite.JDBC")
        connect()
        createTable()
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
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER DEFAULT (strftime('%s', 'now'))
            )
        """.trimIndent()

        executeUpdate(sql)
        println("✅ Таблица equipment создана")
    }

    // Сохранение оборудования
    fun saveEquipment(equipment: List<EquipmentData>) {
        val sql = """
            INSERT OR REPLACE INTO equipment (id, left_pos, top_pos, type, name, letter, cell, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, strftime('%s', 'now'))
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
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
        println("💾 Сохранено ${equipment.size} записей в БД")
    }

    // Загрузка всего оборудования
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

    // Поиск по типу
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

    // Поиск по названию (содержит)
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

    // Поиск по ячейке
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

    // Удаление одной записи
    fun deleteById(id: String): Boolean {
        val sql = "DELETE FROM equipment WHERE id = ?"
        return connection?.prepareStatement(sql)?.use { stmt ->
            stmt.setString(1, id)
            stmt.executeUpdate() > 0
        } ?: false
    }

    // Получение статистики
    fun getStatistics(): Map<String, Int> {
        val stats = mutableMapOf<String, Int>()
        val sql = "SELECT type, COUNT(*) as count FROM equipment GROUP BY type ORDER BY count DESC"

        connection?.prepareStatement(sql)?.use { stmt ->
            val rs = stmt.executeQuery()
            while (rs.next()) {
                val type = rs.getString("type")
                val count = rs.getInt("count")
                val typeName = EquipmentTypes.getTypeName(type) // преобразуем ключ в название
                stats[typeName] = count
            }
        }
        return stats
    }

    // Получение количества записей
    fun getCount(): Int {
        val sql = "SELECT COUNT(*) as count FROM equipment"
        connection?.prepareStatement(sql)?.use { stmt ->
            val rs = stmt.executeQuery()
            return rs.getInt("count")
        }
        return 0
    }

    private fun mapRowToEquipment(rs: ResultSet): EquipmentData {
        return EquipmentData(
            id = rs.getString("id"),
            left = rs.getDouble("left_pos"),
            top = rs.getDouble("top_pos"),
            type = rs.getString("type"),
            name = rs.getString("name"),
            letter = rs.getString("letter"),
            cell = rs.getString("cell") ?: ""
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