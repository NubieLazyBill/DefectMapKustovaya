package org.example.defectmap

object EquipmentTypes {
    // Единый список типов оборудования - ТОЛЬКО КЛЮЧИ!
    val ALL_TYPES = listOf(
        // --- 500 кВ ---
        "v_500" to "Выключатель 500 кВ (В-500)",
        "r_500" to "Разъединитель 500 кВ (Р-500)",
        "autotransformer" to "Автотрансформатор 500 кВ (АТ)",
        "tn_500" to "ТН_500",
        "tt_500" to "ТТ_500",
        "ks_500" to "КС_500",
        "opn_500" to "ОПН-500",
        "reactor_500" to "Реактор 500 кВ (Р-500)",
        // --- 220 кВ ---
        "v_220" to "Выключатель 220 кВ (В-220)",
        "r_220" to "Разъединитель 220 кВ (Р-220)",
        "opn_220" to "ОПН 220 кВ",
        "tn_220" to "ТН 220 кВ",
        "tt_220" to "ТТ 220 кВ",
        "ks_220" to "КС 220 кВ",
        "line_220" to "Линия 220 кВ (Л-220)",
        // --- 35 кВ ---
        "v_35" to "Выключатель 35 кВ (В-35)",
        "r_35" to "Разъединитель 35 кВ (Р-35)",
        "tn_35" to "ТН 35 кВ",
        "tt_35" to "ТТ 35 кВ",
        // --- Молниеотводы ---
        "lightning" to "Молниеотвод (М)",
        // --- Другое оборудование ---
        "capacitor" to "Конденсатор (К)",
        "arrester" to "Разрядник (РВ)",
        "line_trap" to "Заградитель (З)",
        "coupling_capacitor" to "Конденсатор связи (КС)",
        "earthing_switch" to "Заземляющий нож (ЗН)",
        "load_switch" to "Нагрузочный выключатель (ВН)",
        "fuse" to "Предохранитель (Пр)",
        "sf6_breaker" to "Элегазовый выключатель (ВЭ)",
        "vacuum_breaker" to "Вакуумный выключатель (ВВ)",
        "compressor" to "Компрессорная (К)",
        "pump" to "Насос (Н)",
        "generator" to "Генератор (Г)",
        "motor" to "Электродвигатель (М)",
        "other" to "Другое (О)"
    )

    val TYPE_TO_LETTER = mapOf(
        "v_500" to "В",
        "r_500" to "Р",
        "autotransformer" to "АТ",
        "tn_500" to "ТН",
        "tt_500" to "ТТ",
        "ks_500" to "КС",
        "opn_500" to "ОПН",
        "reactor_500" to "Р",
        "v_220" to "В",
        "r_220" to "Р",
        "opn_220" to "ОПН",
        "tn_220" to "ТН",
        "tt_220" to "ТТ",
        "ks_220" to "КС",
        "line_220" to "Л",
        "v_35" to "В",
        "r_35" to "Р",
        "tn_35" to "ТН",
        "tt_35" to "ТТ",
        "lightning" to "М",
        "capacitor" to "К",
        "arrester" to "РВ",
        "line_trap" to "З",
        "coupling_capacitor" to "КС",
        "earthing_switch" to "ЗН",
        "load_switch" to "ВН",
        "fuse" to "Пр",
        "sf6_breaker" to "ВЭ",
        "vacuum_breaker" to "ВВ",
        "compressor" to "К",
        "pump" to "Н",
        "generator" to "Г",
        "motor" to "М",
        "other" to "О"
    )

    // Для фильтрации - название -> ключ
    val TYPE_FILTER_MAP = ALL_TYPES.associate { it.second to it.first }

    fun getLetter(type: String): String = TYPE_TO_LETTER[type] ?: "О"
    fun getTypeName(type: String): String = ALL_TYPES.toMap()[type] ?: type
}