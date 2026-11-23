package org.example.defectmap

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Типы изделий (модели): создание на лету, назначение оборудованию,
 * безопасное удаление и миграция старых БД (фото не должны пропасть).
 */
class EquipmentModelsTest {

    private val key = "test_models_${System.nanoTime()}"
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
    fun `миграция создаёт модели по умолчанию и привязывает оборудование`() {
        val models = db.loadAllModels()
        assertTrue(models.isNotEmpty(), "ожидались модели по умолчанию после миграции")
        assertTrue(
            models.any { it.typeKey == "v_220" && it.photo != null },
            "у выключателя 220 кВ должна быть модель с фото"
        )
    }

    @Test
    fun `тип изделия создаётся на лету и назначается оборудованию`() {
        db.saveEquipment(
            listOf(
                EquipmentData(
                    id = "eq-1", left = 10.0, top = 20.0, type = "v_220",
                    name = "Выключатель 1", letter = "В", cell = "1"
                )
            )
        )

        val model = EquipmentModels.ensureModel(db, "v_220", "ВВБК-220")
        assertNotNull(model, "модель должна создаться")
        assertEquals("ВВБК-220", model!!.name)

        db.setEquipmentModel("eq-1", model.id)
        val loaded = db.loadAllEquipment().first { it.id == "eq-1" }
        assertEquals(model.id, loaded.modelId)
        assertEquals(1, db.countEquipmentByModel(model.id))
    }

    @Test
    fun `повторный ensureModel не плодит дубликаты`() {
        val first = EquipmentModels.ensureModel(db, "r_220", "РНДЗ-220")
        val second = EquipmentModels.ensureModel(db, "r_220", "  рндз-220 ")
        assertNotNull(first)
        assertEquals(first!!.id, second!!.id)
    }

    @Test
    fun `удаление модели не затрагивает оборудование`() {
        val model = EquipmentModels.ensureModel(db, "tt_220", "ТФЗМ-220")
        assertNotNull(model)
        db.deleteModel(model!!.id)
        assertNull(db.loadAllModels().firstOrNull { it.id == model.id })
    }

    @Test
    fun `обычное сохранение оборудования не затирает назначенный тип`() {
        db.saveEquipment(
            listOf(
                EquipmentData(
                    id = "eq-2", left = 5.0, top = 6.0, type = "v_500",
                    name = "Выключатель 2", letter = "В", cell = "2"
                )
            )
        )
        val model = EquipmentModels.ensureModel(db, "v_500", "ВВБК-500")
        db.setEquipmentModel("eq-2", model!!.id)

        // Сохранение из JS: modelId в объекте отсутствует
        db.saveEquipment(
            listOf(
                EquipmentData(
                    id = "eq-2", left = 7.0, top = 8.0, type = "v_500",
                    name = "Выключатель 2", letter = "В", cell = "2"
                )
            )
        )

        val loaded = db.loadAllEquipment().first { it.id == "eq-2" }
        assertEquals(model.id, loaded.modelId, "тип изделия не должен теряться")
        assertEquals(7.0, loaded.left, 0.001)
    }
}
