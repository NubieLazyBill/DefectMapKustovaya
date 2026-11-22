package org.example.defectmap.diagnostics

import javafx.scene.Node
import javafx.scene.image.WritableImage
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Снимок JavaFX-узла в PNG без `javafx.swing` (SwingFXUtils в сборку не тянем).
 * Используется в `--uicheck` и `--appsmoke`, чтобы в CI оставался артефакт-скриншот.
 */
object Snapshots {

    /** Сохраняет снимок узла. Возвращает долю небелых пикселей (0..100). */
    fun save(node: Node, target: File): Double {
        val image: WritableImage = node.snapshot(null, null)
        val width = image.width.toInt().coerceAtLeast(1)
        val height = image.height.toInt().coerceAtLeast(1)

        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val reader = image.pixelReader
        var nonWhite = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val argb = reader.getArgb(x, y)
                buffered.setRGB(x, y, argb)
                if ((argb and 0x00FFFFFF) != 0x00FFFFFF) nonWhite++
            }
        }

        target.parentFile?.mkdirs()
        ImageIO.write(buffered, "png", target)

        val percent = nonWhite * 100.0 / (width.toLong() * height.toLong())
        return percent
    }

    /** Достаёт путь из аргументов вида `--snapshot <path>`. */
    fun snapshotArg(args: List<String>): File? {
        val index = args.indexOf("--snapshot")
        if (index < 0 || index + 1 >= args.size) return null
        return File(args[index + 1]).absoluteFile
    }
}
