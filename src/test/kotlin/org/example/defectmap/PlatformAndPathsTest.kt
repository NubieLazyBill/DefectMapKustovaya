package org.example.defectmap

import org.example.defectmap.platform.PlatformInfo
import org.example.defectmap.platform.UiFonts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** Пути и платформа: никаких `C:\` и `user.dir` в коде приложения. */
class PlatformAndPathsTest {

    @Test
    fun `каталог данных существует и доступен на запись`() {
        val dir = AppPaths.dataDir
        assertTrue(dir.isDirectory, "каталог данных должен существовать: ${dir.absolutePath}")

        val probe = File.createTempFile("probe-", ".txt", dir)
        probe.writeText("ok", Charsets.UTF_8)
        assertEquals("ok", probe.readText(Charsets.UTF_8))
        probe.delete()
    }

    @Test
    fun `каталог данных не внутри ресурсов сборки`() {
        val dataDir = AppPaths.dataDir.canonicalPath
        assertFalse(dataDir.contains("${File.separator}build${File.separator}libs"), dataDir)
    }

    @Test
    fun `схемы и картинки доступны как File`() {
        assertTrue(AppPaths.schemasDir.isDirectory)
        assertTrue(AppPaths.imagesDir.isDirectory)
        assertEquals(
            "schema_kustovaya.svg",
            AppPaths.schemaFile("kustovaya").name
        )
    }

    @Test
    fun `опасные имена файлов отсекаются`() {
        assertTrue(AppPaths.isSafeFileName("schema_kustovaya.svg"))
        assertTrue(AppPaths.isSafeFileName("ПС №1.svg"))

        assertFalse(AppPaths.isSafeFileName("../etc/passwd"))
        assertFalse(AppPaths.isSafeFileName("a/b.svg"))
        assertFalse(AppPaths.isSafeFileName("a\\b.svg"))
        assertFalse(AppPaths.isSafeFileName(""))
        assertFalse(AppPaths.isSafeFileName("file|name.svg"))
    }

    @Test
    fun `суффикс исполняемых файлов соответствует ОС`() {
        if (PlatformInfo.isWindows) {
            assertEquals(".exe", PlatformInfo.exeSuffix)
        } else {
            assertEquals("", PlatformInfo.exeSuffix)
        }
        assertTrue(PlatformInfo.name.isNotBlank())
    }

    @Test
    fun `CSS-стек шрифтов содержит системный fallback`() {
        val css = UiFonts.cssSans
        assertTrue(css.startsWith("\"Arial\""), css)
        assertTrue(css.endsWith("sans-serif"), css)
        assertTrue(css.contains("\"Liberation Sans\""), css)
        assertTrue(UiFonts.cssSerif.endsWith("serif"))
        assertTrue(UiFonts.cssMono.endsWith("monospace"))
    }
}
