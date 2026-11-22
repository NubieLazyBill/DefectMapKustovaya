package org.example.defectmap

import com.google.gson.GsonBuilder
import java.io.File

object AppSettings {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    // Каталог данных: рядом с JAR (портативно) или XDG-каталог на Linux/macOS
    private val SETTINGS_FILE: File by lazy { File(AppPaths.dataDir, "app_settings.json") }

    private var data: SettingsData = load()

    data class SettingsData(
        val lastSubstationKey: String = Substations.DEFAULT.key
    )

    private fun load(): SettingsData {
        return try {
            if (SETTINGS_FILE.exists()) {
                val json = SETTINGS_FILE.readText(Charsets.UTF_8)
                gson.fromJson(json, SettingsData::class.java) ?: SettingsData()
            } else {
                SettingsData()
            }
        } catch (e: Exception) {
            println("⚠️ Не удалось прочитать app_settings.json: ${e.message}")
            SettingsData()
        }
    }

    private fun save() {
        try {
            SETTINGS_FILE.parentFile?.mkdirs()
            SETTINGS_FILE.writeText(gson.toJson(data), Charsets.UTF_8)
            println("💾 Настройки приложения сохранены: ${SETTINGS_FILE.absolutePath}")
        } catch (e: Exception) {
            println("⚠️ Не удалось сохранить app_settings.json: ${e.message}")
        }
    }

    fun getLastSubstationKey(): String {
        return data.lastSubstationKey
    }

    fun setLastSubstationKey(key: String) {
        if (data.lastSubstationKey == key) return
        data = data.copy(lastSubstationKey = key)
        save()
    }
}
