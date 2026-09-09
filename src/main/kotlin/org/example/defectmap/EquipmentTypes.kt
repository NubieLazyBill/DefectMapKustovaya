package org.example.defectmap

object EquipmentTypes {
    // ===== ДИНАМИЧЕСКИЙ СПИСОК =====
    private var _allTypes: MutableList<Pair<String, String>> = mutableListOf()
    private var _typeToLetter: MutableMap<String, String> = mutableMapOf()

    // ===== ПУБЛИЧНЫЙ ДОСТУП =====
    val ALL_TYPES: List<Pair<String, String>>
        get() = _allTypes.toList()

    val TYPE_TO_LETTER: Map<String, String>
        get() = _typeToLetter.toMap()

    val TYPE_FILTER_MAP: Map<String, String>
        get() = _allTypes.associate { it.second to it.first }

    // ===== МЕТОДЫ =====
    fun getLetter(type: String): String = _typeToLetter[type] ?: "О"

    fun getTypeName(type: String): String = _allTypes.toMap()[type] ?: type

    // ===== ЗАГРУЗКА ИЗ БД =====
    fun loadFromDatabase(database: Database) {
        val types = database.loadAllTypes()
        if (types.isNotEmpty()) {
            _allTypes.clear()
            _typeToLetter.clear()
            types.forEach { typeData ->
                _allTypes.add(typeData.key to typeData.displayName)
                _typeToLetter[typeData.key] = typeData.letter
            }
            println("✅ Загружено ${_allTypes.size} типов из БД")
        } else {
            println("ℹ️ Таблица types пуста, загружаем дефолтные значения")
            loadDefaults()
        }
    }

    // ===== ЗАГРУЗКА ДЕФОЛТНЫХ ЗНАЧЕНИЙ =====
    fun loadDefaults() {
        _allTypes.clear()
        _typeToLetter.clear()
        defaultTypes.forEach { (key, displayName, letter) ->
            _allTypes.add(key to displayName)
            _typeToLetter[key] = letter
        }
        println("✅ Загружено ${_allTypes.size} дефолтных типов")
    }

    fun reloadDefaults() = loadDefaults()

    fun getDefaultTypes(): List<Triple<String, String, String>> = defaultTypes.toList()

    // ===== ДИНАМИЧЕСКОЕ УПРАВЛЕНИЕ =====
    fun addType(key: String, displayName: String, letter: String) {
        _allTypes.add(key to displayName)
        _typeToLetter[key] = letter
        println("➕ Добавлен тип: $key → $displayName ($letter)")
    }

    fun updateType(key: String, displayName: String, letter: String) {
        val index = _allTypes.indexOfFirst { it.first == key }
        if (index >= 0) {
            _allTypes[index] = key to displayName
            _typeToLetter[key] = letter
            println("✏️ Обновлён тип: $key → $displayName ($letter)")
        }
    }

    fun removeType(key: String) {
        _allTypes.removeAll { it.first == key }
        _typeToLetter.remove(key)
        println("🗑️ Удалён тип: $key")
    }

    fun getAllKeys(): List<String> = _allTypes.map { it.first }

    fun getAllDisplayNames(): List<String> = _allTypes.map { it.second }

    // ===== ДЕФОЛТНЫЕ ЗНАЧЕНИЯ (СОХРАНЯЕМ ВСЕ ВАШИ ТИПЫ) =====
    private val defaultTypes = listOf(
        // --- 500 кВ ---
        Triple("v_500", "В-500", "В"),
        Triple("v_500_ABB", "В-500 элегаз", "В"),
        Triple("r_500", "Разъединитель 500 кВ", "Р"),
        Triple("autotransformer", "АТГ", "АТ"),
        Triple("tn_500", "ТН-500", "ТН"),
        Triple("tt_500", "ТТ-500", "ТТ"),
        Triple("ks_500", "КС-500", "КС"),
        Triple("opn_500", "ОПН-500", "ОПН"),
        Triple("reactor_500", "Р-500", "Р"),
        Triple("fpz_500", "ФПЗ-500", "ФПЗ"),
        Triple("s", "С", "С"),
        Triple("ls", "ЛС", "ЛС"),

        // --- 220 кВ ---
        Triple("v_220", "В-220", "В"),
        Triple("r_220", "Разъединитель 220 кВ", "Р"),
        Triple("opn_220", "ОПН 220 кВ", "ОПН"),
        Triple("tn_220", "ТН 220 кВ", "ТН"),
        Triple("tt_220", "ТТ 220 кВ", "ТТ"),
        Triple("ks_220", "КС 220 кВ", "КС"),
        Triple("line_220", "ВЛ 220 кВ", "Л"),
        Triple("fp_220", "ФП-220", "ФП"),
        Triple("zn_KC_220", "ЗН КС-220", "ЗН"),

        // --- 35 кВ ---
        Triple("v_35", "В-35", "В"),
        Triple("r_35", "Разъединитель 35 кВ (Р-35)", "Р"),
        Triple("tn_35", "ТН-35 кВ", "ТН"),
        Triple("tt_35", "ТТ-35 кВ", "ТТ"),
        Triple("tsn", "ТСН", "ТСН"),
        Triple("opn_35", "ОПН-35", "ОПН"),

        // --- Здания ---
        Triple("Buildings", "Здания", "*"),

        // --- Молниеотводы ---
        Triple("lightning", "Молниеотвод (М)", "М"),

        // --- Другое оборудование ---
        Triple("MO", "Мачта освещения", "MO"),
        Triple("capacitor", "Конденсатор (К)", "К"),
        Triple("arrester", "Разрядник (РВ)", "РВ"),
        Triple("line_trap", "Заградитель (З)", "З"),
        Triple("coupling_capacitor", "Конденсатор связи (КС)", "КС"),
        Triple("earthing_switch", "Заземляющий нож (ЗН)", "ЗН"),
        Triple("load_switch", "Нагрузочный выключатель (ВН)", "ВН"),
        Triple("fuse", "Предохранитель (Пр)", "Пр"),
        Triple("sf6_breaker", "Элегазовый выключатель (ВЭ)", "ВЭ"),
        Triple("vacuum_breaker", "Вакуумный выключатель (ВВ)", "ВВ"),
        Triple("compressor", "Компрессорная (К)", "К"),
        Triple("pump", "Насос (Н)", "Н"),
        Triple("generator", "Генератор (Г)", "Г"),
        Triple("motor", "Электродвигатель (М)", "М"),
        Triple("other", "Другое (О)", "О"),

        // ===== 110 кВ (ДОБАВЛЯЕМ НОВЫЕ) =====
        Triple("v_110", "В-110", "В-110"),
        Triple("r_110", "Разъединитель 110 кВ", "Р-110"),
        Triple("opn_110", "ОПН 110 кВ", "ОПН"),
        Triple("tn_110", "ТН 110 кВ", "ТН"),
        Triple("tt_110", "ТТ 110 кВ", "ТТ"),
        Triple("ks_110", "КС 110 кВ", "КС"),
        Triple("line_110", "ВЛ 110 кВ", "Л-110")
    )
}