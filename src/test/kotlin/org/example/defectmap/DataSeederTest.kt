package org.example.defectmap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Разворачивание поставки в пользовательский каталог — путь, которым идёт
 * приложение из .deb/.rpm/AppImage (папка /opt только для чтения).
 */
class DataSeederTest {

    private fun tempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").also { it.mkdirs() }

    @Test
    fun `копирует БД схемы и картинки`() {
        val from = tempDir("seed-from")
        val to = tempDir("seed-to")
        try {
            File(from, "schemas/schema_a.svg").apply {
                parentFile.mkdirs()
                writeText("<svg/>", Charsets.UTF_8)
            }
            File(from, "images/equipment.jpg").apply {
                parentFile.mkdirs()
                writeText("jpeg", Charsets.UTF_8)
            }
            File(from, "equipment_kustovaya.db").writeText("db", Charsets.UTF_8)
            File(from, "substations.json").writeText("[]", Charsets.UTF_8)
            File(from, "DefectMap.jar").writeText("jar", Charsets.UTF_8)

            val copied = DataSeeder.seed(from, to)

            assertTrue(File(to, "equipment_kustovaya.db").isFile)
            assertTrue(File(to, "substations.json").isFile)
            assertTrue(File(to, "schemas/schema_a.svg").isFile)
            assertTrue(File(to, "images/equipment.jpg").isFile)
            // Посторонние файлы поставки не размножаем
            assertTrue(!File(to, "DefectMap.jar").exists())
            assertEquals(4, copied.size, copied.joinToString())
        } finally {
            from.deleteRecursively()
            to.deleteRecursively()
        }
    }

    @Test
    fun `не перезаписывает данные пользователя`() {
        val from = tempDir("seed-from")
        val to = tempDir("seed-to")
        try {
            File(from, "equipment_kustovaya.db").writeText("старая поставка", Charsets.UTF_8)
            File(to, "equipment_kustovaya.db").writeText("данные пользователя", Charsets.UTF_8)

            val copied = DataSeeder.seed(from, to)

            assertTrue(copied.isEmpty(), copied.joinToString())
            assertEquals(
                "данные пользователя",
                File(to, "equipment_kustovaya.db").readText(Charsets.UTF_8),
            )
        } finally {
            from.deleteRecursively()
            to.deleteRecursively()
        }
    }

    @Test
    fun `пустая поставка не создаёт мусора`() {
        val from = tempDir("seed-from")
        val to = tempDir("seed-to")
        try {
            assertTrue(DataSeeder.seed(from, to).isEmpty())
            assertTrue(DataSeeder.seed(File(from, "nope"), to).isEmpty())
        } finally {
            from.deleteRecursively()
            to.deleteRecursively()
        }
    }

    @Test
    fun `распознаёт имена БД`() {
        assertTrue(DataSeeder.isDatabase("equipment_kustovaya.db"))
        assertTrue(DataSeeder.isDatabase("equipment.DB"))
        assertTrue(!DataSeeder.isDatabase("substations.json"))
        assertTrue(!DataSeeder.isDatabase("equipment_kustovaya.db-wal"))
    }
}
