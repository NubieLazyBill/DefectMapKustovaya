package org.example.defectmap.diagnostics

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.example.defectmap.AppPaths
import org.example.defectmap.MarkerPosition
import java.io.File
import java.sql.DriverManager
import java.text.SimpleDateFormat
import java.util.Date

/**
 * `--fix-markers`: разовая миграция координат маркеров, сохранённых старой версией.
 *
 * Проблема: раньше и отрисовка, и клик считали проценты от всего окна, а не от
 * области схемы. По вертикали окно ниже схемы (схема не влезала), поэтому
 * сохранённые `top` оказались завышены примерно в 1.9–2 раза (в данных встречаются
 * значения 110–190%). По горизонтали искажения не было — `left` в норме.
 *
 * Миграция делит `top` на коэффициент (по умолчанию 1.94 — окно 1920×1017 при
 * соотношении сторон схемы 2466×2384) и ограничивает результат 0..100.
 * Перед изменением БД копируется в `backups/marker-fix-<дата>/`.
 *
 * Примеры:
 *   java -jar DefectMap.jar --fix-markers --dry-run
 *   java -jar DefectMap.jar --fix-markers --factor=1.94
 */
object MarkerMigration {

    const val DEFAULT_FACTOR = 1.94

    private val gson = Gson()
    private val markersType = object : TypeToken<List<MarkerPosition>>() {}.type

    fun run(args: List<String>): Int {
        val dryRun = args.contains("--dry-run")
        val force = args.contains("--force")
        val factor = args
            .firstOrNull { it.startsWith("--factor=") }
            ?.substringAfter("=")
            ?.toDoubleOrNull()
            ?: DEFAULT_FACTOR
        val dir = args
            .firstOrNull { it.startsWith("--dir=") }
            ?.substringAfter("=")
            ?.let { File(it) }
            ?: AppPaths.dataDir

        println()
        println("=".repeat(74))
        println("DefectMap — миграция координат маркеров (--fix-markers)")
        println("=".repeat(74))
        println("  Каталог данных: ${dir.absolutePath}")
        println("  Коэффициент:    $factor${if (dryRun) "  (dry-run: без записи)" else ""}")

        if (!dir.isDirectory) {
            println("  [FAIL] каталог не найден")
            return 1
        }

        // Без этого DriverManager не знает про sqlite и тихо падает в needsMigration()
        try {
            Class.forName("org.sqlite.JDBC")
        } catch (t: Throwable) {
            println("  [FAIL] драйвер SQLite не загрузился: ${t.message}")
            return 1
        }

        val databases = dir.listFiles { f: File ->
            f.isFile && f.name.startsWith("equipment") && f.name.endsWith(".db", ignoreCase = true)
        }?.sorted() ?: emptyList()

        if (databases.isEmpty()) {
            println("  [WARN] баз equipment*.db в каталоге нет — мигрировать нечего")
            return 0
        }

        var failed = 0
        var totalChanged = 0

        databases.forEach { db ->
            val result = migrateDatabase(
                db = db,
                factor = factor,
                process = force || needsMigration(db),
                dryRun = dryRun,
            )
            if (result == null) {
                failed++
            } else {
                totalChanged += result
            }
        }

        println()
        println("-".repeat(74))
        if (failed > 0) {
            println("  [FAIL] ошибок: $failed")
            return 1
        }
        println(
            if (dryRun) {
                "  Готово (dry-run). Изменилось бы записей: $totalChanged. Для записи уберите --dry-run."
            } else {
                "  Готово. Изменено записей: $totalChanged. Резервные копии — в ${File(dir, "backups").absolutePath}"
            }
        )
        return 0
    }

    /** Есть ли в БД заведомо искажённые координаты (top > 100). */
    fun needsMigration(db: File): Boolean {
        return try {
            connect(db).use { con ->
                con.prepareStatement("SELECT markers FROM equipment").use { st ->
                    st.executeQuery().use { rs ->
                        while (rs.next()) {
                            if (parse(rs.getString(1)).any { it.top > 100.0 }) return true
                        }
                    }
                }
            }
            false
        } catch (t: Throwable) {
            false
        }
    }

    /** Пересчёт: top делится на коэффициент и ограничивается 0..100. */
    fun rescaleTop(top: Double, factor: Double): Double {
        if (factor <= 0.0) return top
        return Math.round((top / factor).coerceIn(0.0, 100.0) * 10.0) / 10.0
    }

    fun parse(json: String?): List<MarkerPosition> = try {
        gson.fromJson<List<MarkerPosition>>(json ?: "[]", markersType) ?: emptyList()
    } catch (t: Throwable) {
        emptyList()
    }

    // ======================== ВНУТРЕННЕЕ ========================

    private fun connect(db: File) =
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}")

    private fun migrateDatabase(db: File, factor: Double, process: Boolean, dryRun: Boolean): Int? {
        println()
        println("[${db.name}]")
        if (!process) {
            println("  [SKIP] искажённых координат не найдено (нет top > 100). Нужно принудительно — добавьте --force")
            return 0
        }

        return try {
            Class.forName("org.sqlite.JDBC")
            val rows = mutableListOf<Pair<String, List<MarkerPosition>>>()
            connect(db).use { con ->
                con.prepareStatement("SELECT id, markers FROM equipment").use { st ->
                    st.executeQuery().use { rs ->
                        while (rs.next()) {
                            rows += rs.getString(1) to parse(rs.getString(2))
                        }
                    }
                }
            }

            val before = rows.flatMap { it.second }.maxOfOrNull { it.top } ?: 0.0
            val changes = rows.map { (id, markers) ->
                id to markers.map { it.copy(top = rescaleTop(it.top, factor)) }
            }
            val changed = changes.count { (id, markers) ->
                val old = rows.first { it.first == id }.second
                old != markers
            }
            val after = changes.flatMap { it.second }.maxOfOrNull { it.top } ?: 0.0

            println("  Маркеров всего: ${rows.sumOf { it.second.size }}, изменят координаты: $changed")
            println("  Максимальный top: было ${"%.1f".format(before)}% → станет ${"%.1f".format(after)}%")

            if (changed == 0) {
                println("  [OK] нечего менять")
                return 0
            }

            if (dryRun) {
                println("  [DRY-RUN] запись не выполнялась; для применения уберите --dry-run")
                return changed
            }

            val backupDir = File(db.parentFile, "backups/marker-fix-${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Date())}")
            backupDir.mkdirs()
            db.copyTo(File(backupDir, db.name), overwrite = true)
            println("  Бэкап: ${File(backupDir, db.name).absolutePath}")

            connect(db).use { con ->
                con.autoCommit = false
                try {
                    con.prepareStatement("UPDATE equipment SET markers = ? WHERE id = ?").use { st ->
                        changes.forEach { (id, markers) ->
                            st.setString(1, gson.toJson(markers))
                            st.setString(2, id)
                            st.addBatch()
                        }
                        st.executeBatch()
                    }
                    con.commit()
                } catch (t: Throwable) {
                    con.rollback()
                    throw t
                }
            }
            println("  [OK] обновлено записей: $changed")
            changed
        } catch (t: Throwable) {
            println("  [FAIL] ${t::class.java.simpleName}: ${t.message}")
            null
        }
    }
}
