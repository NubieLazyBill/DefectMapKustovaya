package org.example.defectmap.report

/**
 * Строка отчёта по дефектам.
 *
 * Раньше класс был вложенным в `DefectMapController`; вынесен в отдельный пакет,
 * чтобы экспорт в Excel и печать можно было использовать из self-test и юнит-тестов
 * без запуска JavaFX.
 */
data class ReportItem(
    val equipmentName: String,
    val equipmentType: String,
    val equipmentCell: String,
    val defectName: String,
    val defectDescription: String,
    val defectStatus: String,
    val markerLeft: Double?,
    val markerTop: Double?,
    val detectionDate: Long?,
    val parentEquipmentName: String? = null,
    val rootEquipmentType: String? = null,
    /** Тип изделия (модель), например «ТФЗМ-220». */
    val equipmentModel: String = "",
)
