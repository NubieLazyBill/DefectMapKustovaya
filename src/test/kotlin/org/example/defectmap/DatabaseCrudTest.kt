package org.example.defectmap

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * БД должна открываться и работать в пользовательском каталоге
 * (`~/.local/share/defectmap` на Linux), а не только рядом с JAR.
 */
class DatabaseCrudTest {

    private val key = "test_unit_${System.nanoTime()}"
    private lateinit var db: Database
    private lateinit var dbFile: File

    @BeforeEach
    fun setUp() {
        db = Database(key)
        dbFile = File(AppPaths.dataDir, "equipment_$key.db")
    }

    @AfterEach
    fun tearDown() {
        runCatching { db.close() }
        dbFile.delete()
        File(AppPaths.backupsDir, key).deleteRecursively()
    }

    @Test
    fun `БД создаётся в каталоге данных`() {
        assertTrue(dbFile.isFile, "ожидался файл ${dbFile.absolutePath}")
        assertTrue(
            dbFile.parentFile.canonicalPath == AppPaths.dataDir.canonicalPath,
            "БД должна лежать в dataDir, а не рядом с JAR"
        )
        assertEquals(0, db.getCount())
    }

    @Test
    fun `полный цикл оборудования и дефекта`() {
        val equipment = listOf(
            EquipmentData(
                id = "root-1",
                left = 10.0,
                top = 20.0,
                type = "autotransformer",
                name = "АТГ-1",
                letter = "АТ",
                cell = "1",
                size = "normal",
            ),
            EquipmentData(
                id = "child-1",
                left = 11.0,
                top = 21.0,
                type = "v_220",
                name = "В-220 №1",
                letter = "В",
                parentId = "root-1",
            ),
        )
        db.saveEquipment(equipment)
        assertEquals(2, db.getCount())

        val loaded = db.loadAllEquipment()
        assertEquals(2, loaded.size)
        assertEquals("root-1", loaded.first { it.name == "В-220 №1" }.parentId)
        assertTrue(loaded.first { it.id == "root-1" }.markers.isNotEmpty(), "маркер должен создаться автоматически")

        val defect = DefectData(
            id = "defect-1",
            equipmentId = "root-1",
            name = "Течь масла",
            description = "Кириллица и символы: Ёё №—«»",
            status = "open",
            markerLeft = 15.0,
            markerTop = 25.0,
        )
        db.saveDefect(defect)

        val defects = db.getDefectsByEquipment("root-1")
        assertEquals(1, defects.size)
        assertEquals("Кириллица и символы: Ёё №—«»", defects.first().description)
        assertEquals(15.0, defects.first().markerLeft!!, 1e-9)

        db.updateDefect(defects.first().copy(status = "fixed", description = "Устранено"))
        assertEquals("fixed", db.getDefectsByEquipment("root-1").first().status)

        db.autoBackup()
        val backups = File(AppPaths.backupsDir, key).listFiles()?.filter { it.name.endsWith(".db") }
        assertNotNull(backups)
        assertTrue(backups!!.isNotEmpty(), "автобэкап должен создаваться")

        db.deleteDefect("defect-1")
        assertTrue(db.getDefectsByEquipment("root-1").isEmpty())
        assertTrue(db.deleteById("root-1"))
        assertEquals(1, db.getCount())

        db.saveSetting("marker_scale", "1.4")
        assertEquals(1.4, db.getMarkerScale())
    }

    @Test
    fun `подстановка значений по умолчанию не падает на повторном открытии`() {
        // Повторная инициализация (миграции ALTER TABLE) должна быть идемпотентной
        db.close()
        db = Database(key)
        assertEquals(0, db.getCount())
        assertFalse(File(AppPaths.dataDir, "equipment_$key.db-journal").isFile, "журнал не должен оставаться")
    }
}
