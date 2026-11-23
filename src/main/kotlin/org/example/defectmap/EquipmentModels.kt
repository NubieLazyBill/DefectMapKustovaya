package org.example.defectmap

/**
 * Тип изделия (модель) внутри категории оборудования.
 *
 * Категория — это привычный «тип оборудования» из `equipment_types`
 * («В-500», «Разъединитель 220 кВ», «ТТ 220 кВ»…), а модель уточняет изделие:
 * «ВВБК-500», «ВВУ-35», «РНДЗ-220», «ТФЗМ-500».
 *
 * @param id       стабильный ключ (используется в `equipment.model_id`)
 * @param typeKey  ключ категории из `equipment_types`
 * @param name     название модели, как его видит пользователь
 * @param photo    файл фотографии (в ресурсах JAR или в каталоге `images/`)
 */
data class EquipmentModelData(
    val id: String,
    val typeKey: String,
    val name: String,
    val photo: String? = null,
    val sortOrder: Int = 0
)

/**
 * Справочник типов изделий: кэш моделей из БД + операции поиска.
 *
 * Модель можно завести прямо из карточки оборудования (свободный ввод),
 * поэтому [ensureModel] создаёт запись на лету.
 */
object EquipmentModels {

    /**
     * Модели, которые создаются при миграции старой БД: категория → (название, фото).
     * Нужны, чтобы уже существующие фото не пропали у оборудования.
     */
    val DEFAULT_PHOTO_MODELS: Map<String, List<Pair<String, String>>> = linkedMapOf(
        "v_500" to listOf("ВВБК-500" to "ВВБК-500.jfif"),
        "v_220" to listOf("ВВБК-220" to "ВВБК-220(3 фазы).jpg"),
        "v_35" to listOf("ВВУ-35" to ""),
        "r_500" to listOf("РНДЗ-500" to "РНДЗ-220,500.jpg"),
        "r_220" to listOf("РНДЗ-220" to "РНДЗ-220,500.jpg"),
        "r_35" to listOf("РНДЗ-35" to "РНДЗ-220,500.jpg"),
        "autotransformer" to listOf("АОДЦТН-167000-500-220" to "АОДЦТН-167000-500-220.jpg"),
        "tsn" to listOf("ТМ-35" to "ТМ-35.jpg"),
        "tt_500" to listOf("ТФЗМ-500" to "ТФЗМ-500.jpg"),
        "opn_220" to listOf("ОПН-220" to "ОПН-220.jpg")
    )

    private var models: List<EquipmentModelData> = emptyList()

    fun loadFromDatabase(database: Database) {
        models = database.loadAllModels()
        if (models.isNotEmpty()) {
            println("✅ Загружено ${models.size} типов изделий")
        }
    }

    fun all(): List<EquipmentModelData> = models

    fun forType(typeKey: String): List<EquipmentModelData> =
        models.filter { it.typeKey == typeKey }.sortedBy { it.name.lowercase() }

    fun names(typeKey: String): List<String> = forType(typeKey).map { it.name }

    fun byId(id: String?): EquipmentModelData? =
        if (id.isNullOrBlank()) null else models.firstOrNull { it.id == id }

    fun displayName(id: String?): String? = byId(id)?.name

    fun photoName(id: String?): String? =
        byId(id)?.photo?.takeIf { it.isNotBlank() }

    fun find(typeKey: String, name: String): EquipmentModelData? {
        val wanted = name.trim()
        if (wanted.isEmpty()) return null
        return forType(typeKey).firstOrNull { it.name.equals(wanted, ignoreCase = true) }
    }

    /** Ключ модели: детерминированный, без спецсимволов. */
    fun makeId(typeKey: String, name: String): String {
        var hash = 0x811c9dc5.toInt()
        for (ch in "$typeKey|${name.trim().lowercase()}") {
            hash = hash xor ch.code
            hash *= 16777619
        }
        return "model_" + Integer.toHexString(hash)
    }

    fun addLocal(model: EquipmentModelData) {
        models = models.filterNot { it.id == model.id } + model
    }

    fun removeLocal(id: String) {
        models = models.filterNot { it.id == id }
    }

    /** Найти модель по имени или создать её в БД (свободный ввод в карточке). */
    fun ensureModel(database: Database, typeKey: String, name: String): EquipmentModelData? {
        val clean = name.trim()
        if (clean.isEmpty() || typeKey.isBlank()) return null
        find(typeKey, clean)?.let { return it }

        val model = EquipmentModelData(
            id = makeId(typeKey, clean),
            typeKey = typeKey,
            name = clean,
            photo = null,
            sortOrder = forType(typeKey).size
        )
        database.saveModel(model)
        addLocal(model)
        return model
    }
}
