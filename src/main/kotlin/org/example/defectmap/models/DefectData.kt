package org.example.defectmap

data class DefectData(
    val id: String,
    val equipmentId: String,
    val name: String,
    val description: String = "",
    val severity: String = "medium",   // critical, high, medium, low
    val status: String = "open",       // open, in_progress, fixed
    val detectionDate: Long = System.currentTimeMillis(),
    val repairDate: Long? = null,
    val photoPath: String? = null,
    val notes: String = "",
    val markerLeft: Double? = null,    // Позиция на картинке (X %)
    val markerTop: Double? = null      // Позиция на картинке (Y %)
)