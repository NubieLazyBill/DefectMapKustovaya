package org.example.defectmap

import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.canvas.Canvas
import javafx.scene.control.*
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.scene.control.Alert.AlertType
import javafx.scene.input.MouseEvent
import javafx.scene.paint.Color
import javafx.animation.PauseTransition
import javafx.collections.FXCollections
import javafx.util.Duration
import java.io.File

class EquipmentCardController(
    private val equipment: EquipmentData,
    private val database: Database,
    private val onDefectChanged: (() -> Unit)? = null,
    private val parentEquipment: EquipmentData? = null,
) {

    private val defects: MutableList<DefectData> = mutableListOf()
    private val defectsListView = ListView<DefectData>()
    private var isMarkerMode = false
    private var selectedDefectId: String? = null

    private var equipmentListStage: Stage? = null
    private var defectsListStage: Stage? = null

    // Параметры для Canvas
    private var drawWidth = 0.0
    private var drawHeight = 0.0
    private var offsetX = 0.0
    private var offsetY = 0.0
    private var canvas: Canvas? = null

    private var previewPopup: javafx.stage.Popup? = null
    private var previewImageView: ImageView? = null
    private var previewLabel: Label? = null

    private var isChildMarkerMode = false
    private var childMarkers: MutableList<ChildMarker> = mutableListOf()
    private var currentParentId: String? = null  // ID родителя (если мы открыты как дочерний)
    private var breadcrumbs: MutableList<EquipmentData> = mutableListOf()  // ← для навигации

    private var currentEditingChildId: String? = null

    fun show() {
        initPreviewPopup()

        defects.clear()
        defects.addAll(database.getDefectsByEquipment(equipment.id))

        // ===== ЗАГРУЖАЕМ ДОЧЕРНИЕ ЭЛЕМЕНТЫ (один раз, кэшируем) =====
        val allEquipment = database.loadAllEquipment()
        cachedChildren = allEquipment.filter { it.parentId == equipment.id }
        println("📂 Дочерних элементов: ${cachedChildren.size}")

        val mainLayout = VBox(15.0)
        mainLayout.style = "-fx-background-color: white; -fx-padding: 25px;"
        mainLayout.prefWidth = 1100.0
        mainLayout.prefHeight = 750.0

        val headerLabel = Label("📌 ${equipment.name} (${equipment.cell})")
        headerLabel.style = "-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #333;"

        val contentHBox = HBox(25.0)
        contentHBox.alignment = Pos.TOP_LEFT

        val imageContainer = createImagePanel()
        val rightPanel = createRightPanel()

        contentHBox.children.addAll(imageContainer, rightPanel)

        mainLayout.children.addAll(headerLabel, contentHBox)

        val popupStage = Stage()
        popupStage.title = "Карточка оборудования"
        popupStage.scene = Scene(mainLayout, 1100.0, 750.0)
        popupStage.isResizable = true
        popupStage.minWidth = 900.0
        popupStage.minHeight = 600.0

        popupStage.setOnHidden {
            previewPopup?.hide()
        }

        popupStage.showAndWait()

        // ===== ХЛЕБНЫЕ КРОШКИ =====
        val breadcrumbsBox = HBox(8.0)
        breadcrumbsBox.alignment = Pos.CENTER_LEFT
        breadcrumbsBox.style = "-fx-padding: 5px 0;"

        if (parentEquipment != null) {
            val parentLink = Label("📌 ${parentEquipment.name}")
            parentLink.style = "-fx-text-fill: #007bff; -fx-cursor: hand; -fx-underline: true;"
            parentLink.setOnMouseClicked {
                parentLink.scene.window.hide()
                openParentCard(parentEquipment)
            }

            val separator = Label(" → ")
            separator.style = "-fx-text-fill: #6c757d;"

            breadcrumbsBox.children.addAll(parentLink, separator)
        }

        val currentLink = Label("📌 ${equipment.name}")
        currentLink.style = "-fx-text-fill: #333; -fx-font-weight: bold;"
        breadcrumbsBox.children.add(currentLink)

        mainLayout.children.add(0, breadcrumbsBox)
    }

    private fun initPreviewPopup() {
        val img = ImageView()
        img.fitWidth = 160.0
        img.fitHeight = 160.0
        img.isPreserveRatio = true
        img.isSmooth = true

        val lbl = Label()
        lbl.style = "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #333; -fx-wrap-text: true; -fx-max-width: 160px;"
        lbl.isWrapText = true

        val box = VBox(4.0, img, lbl)
        box.alignment = Pos.CENTER
        box.style = """
        -fx-background-color: white;
        -fx-border-color: #333;
        -fx-border-width: 2px;
        -fx-border-radius: 8px;
        -fx-background-radius: 8px;
        -fx-padding: 6px;
        -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 12, 0, 0, 3);
    """.trimIndent()

        val popup = javafx.stage.Popup()
        popup.isAutoHide = false
        popup.isHideOnEscape = false
        popup.content.add(box)

        this.previewPopup = popup
        this.previewImageView = img
        this.previewLabel = lbl
    }

    // ======================== ПАНЕЛЬ С КАРТИНКОЙ ========================

    private fun createImagePanel(): javafx.scene.layout.Region {
        val image = createEquipmentImage()
        if (image == null) {
            val err = StackPane(Label("❌ Не удалось загрузить изображение"))
            err.style = "-fx-padding: 20px;"
            return err
        }

        val canvasWidth = 450.0
        val canvasHeight = 450.0

        val scale = minOf(canvasWidth / image.width, canvasHeight / image.height)
        val drawWidth = image.width * scale
        val drawHeight = image.height * scale
        val offsetX = (canvasWidth - drawWidth) / 2
        val offsetY = (canvasHeight - drawHeight) / 2

        this.drawWidth = drawWidth
        this.drawHeight = drawHeight
        this.offsetX = offsetX
        this.offsetY = offsetY

        val canvas = Canvas(canvasWidth, canvasHeight)
        this.canvas = canvas
        val gc = canvas.graphicsContext2D

        gc.drawImage(image, offsetX, offsetY, drawWidth, drawHeight)
        loadMarkersOnCanvas(gc)

        val imageWrapper = StackPane()
        imageWrapper.children.add(canvas)
        imageWrapper.style = "-fx-border-color: #dee2e6; -fx-border-radius: 8px; -fx-background-color: white;"
        this.imageWrapperRef = imageWrapper

        val imageContainer = VBox(10.0, imageWrapper)
        imageContainer.alignment = Pos.TOP_CENTER
        imageContainer.prefWidth = 500.0
        imageContainer.style = "-fx-padding: 15px;"

        // ===== КЛИК ПО МАРКЕРУ ДОЧЕРНЕГО ЭЛЕМЕНТА =====
        canvas.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (!isMarkerMode && !isChildMarkerMode) {
                val clickX = event.x
                val clickY = event.y
                for (child in cachedChildren) {
                    val mainMarker = child.markers.firstOrNull() ?: MarkerPosition(child.left, child.top, true)
                    val markerX = (mainMarker.left / 100.0) * drawWidth + offsetX
                    val markerY = (mainMarker.top / 100.0) * drawHeight + offsetY
                    val radius = 12.0
                    val dx = clickX - markerX
                    val dy = clickY - markerY
                    if (dx * dx + dy * dy <= radius * radius) {
                        openChildCard(child)
                        break
                    }
                }
            }
        }

        // ===== КЛИК ДЛЯ ДОБАВЛЕНИЯ МАРКЕРА ДЕФЕКТА =====
        canvas.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (isMarkerMode && selectedDefectId != null) {
                val clickX = event.x
                val clickY = event.y
                val xInImage = clickX - offsetX
                val yInImage = clickY - offsetY
                if (xInImage >= 0 && xInImage <= drawWidth && yInImage >= 0 && yInImage <= drawHeight) {
                    val xPercent = (xInImage / drawWidth) * 100
                    val yPercent = (yInImage / drawHeight) * 100
                    addMarkerToDefect(
                        selectedDefectId!!,
                        xPercent.coerceIn(0.0, 100.0),
                        yPercent.coerceIn(0.0, 100.0)
                    )
                    isMarkerMode = false
                    selectedDefectId = null
                } else {
                    showToast("⚠️ Кликните внутри картинки")
                }
            }
        }

        // ===== КЛИК ДЛЯ ДОБАВЛЕНИЯ МАРКЕРА ДОЧЕРНЕГО ЭЛЕМЕНТА =====
        canvas.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (isChildMarkerMode && currentEditingChildId != null) {
                val clickX = event.x
                val clickY = event.y
                val xInImage = clickX - offsetX
                val yInImage = clickY - offsetY
                if (xInImage >= 0 && xInImage <= drawWidth && yInImage >= 0 && yInImage <= drawHeight) {
                    val xPercent = (xInImage / drawWidth) * 100
                    val yPercent = (yInImage / drawHeight) * 100
                    updateChildMarkerPosition(
                        currentEditingChildId!!,
                        xPercent.coerceIn(0.0, 100.0),
                        yPercent.coerceIn(0.0, 100.0)
                    )
                    isChildMarkerMode = false
                    currentEditingChildId = null
                }
            }
        }

        // ===== КЛИК ПО МАРКЕРУ ДЕФЕКТА =====
        canvas.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (!isMarkerMode && !isChildMarkerMode) {
                val clickX = event.x
                val clickY = event.y
                for ((index, defect) in defects.withIndex()) {
                    if (defect.markerLeft != null && defect.markerTop != null) {
                        val markerX = (defect.markerLeft!! / 100.0) * drawWidth + offsetX
                        val markerY = (defect.markerTop!! / 100.0) * drawHeight + offsetY
                        val radius = 10.0
                        val dx = clickX - markerX
                        val dy = clickY - markerY
                        if (dx * dx + dy * dy <= radius * radius) {
                            if (event.clickCount == 1) {
                                defectsListView.selectionModel.select(index)
                                defectsListView.scrollTo(index)
                                showToast("📍 ${defect.name}")
                            } else if (event.clickCount == 2) {
                                editDefectDialog(defect)
                            }
                            break
                        }
                    }
                }
            }
        }

        // ===== ПРЕВЬЮ ПРИ НАВЕДЕНИИ (Popup) =====
        canvas.addEventHandler(MouseEvent.MOUSE_MOVED) { event ->
            if (isMarkerMode || isChildMarkerMode) {
                previewPopup?.hide()
                return@addEventHandler
            }
            handleMarkerHover(event.x, event.y)
        }

        canvas.addEventHandler(MouseEvent.MOUSE_EXITED) {
            previewPopup?.hide()
        }

        // ===== ИНФО-ЛЕЙБЛ =====
        val infoLabel = Label("${equipment.type} | ${equipment.size}")
        infoLabel.style = "-fx-font-size: 13px; -fx-text-fill: #6c757d;"
        imageContainer.children.add(infoLabel)

        // ===== ПАНЕЛЬ КНОПОК ДЛЯ ДОЧЕРНИХ ЭЛЕМЕНТОВ =====
        val childPanel = HBox(10.0)
        childPanel.alignment = Pos.CENTER

        val addChildBtn = Button("➕ Добавить оборудование")
        addChildBtn.style = "-fx-background-color: #17a2b8; -fx-text-fill: white; -fx-padding: 8px 16px; -fx-background-radius: 4px; -fx-font-size: 13px;"
        addChildBtn.setOnAction {
            addChildElementDialog()
        }

        val listChildrenBtn = Button("📋 Оборудование (${cachedChildren.size})")
        listChildrenBtn.style = "-fx-background-color: #6c757d; -fx-text-fill: white; -fx-padding: 8px 16px; -fx-background-radius: 4px; -fx-font-size: 13px;"
        listChildrenBtn.setOnAction {
            showChildrenList()
        }

        childPanel.children.addAll(addChildBtn, listChildrenBtn)
        imageContainer.children.add(childPanel)

        return imageContainer
    }

    private var cachedChildren: List<EquipmentData> = emptyList()

    /**
     * Загружает изображение для оборудования.
     * Сначала ищет в ресурсах JAR, потом в папке images рядом с JAR.
     * Если ничего нет — создаёт заглушку.
     */
    private fun createEquipmentImage(): Image {
        // Определяем путь к картинке по типу оборудования
        val imagePath = when (equipment.type) {
            "v_500" -> "ВВБК-500.jfif"
            "v_500_ABB" -> "В-500 элегаз"
            "v_220" -> "ВВБК-220(3 фазы).jpg"
            "v_35" -> "ВВБК-500.jfif"
            "v_10" -> "ВВБК-500.jfif"
            "r_500", "r_220", "r_35", "r_10" -> "РНДЗ-220,500.jpg"
            "autotransformer" -> "АОДЦТН-167000-500-220.jpg"
            "transformer" -> "ТМ-35.jpg"
            "tsn" -> "ТМ-35.jpg"
            "lightning", "lightning_rod" -> "lightning_rod.jpg"
            "opn_500" -> "opn-500.jpg"
            "opn_220" -> "ОПН-220.jpg"
            "opn_35" -> "opn-35.jpg"
            "opn_10" -> "opn-10.jpg"
            "tn_500", "tn_220", "tn_35", "tn_10" -> "tn.jpg"
            "tt_500", "tt_220", "tt_35", "tt_10" -> "ТФЗМ-500.jpg"
            "ks_500", "ks_220", "coupling_capacitor" -> "capacitor.jpg"
            "reactor_500", "reactor_220" -> "reactor.jpg"
            "capacitor" -> "capacitor.jpg"
            "compressor" -> "compressor.jpg"
            else -> null
        }

        // 1. Пробуем загрузить из ресурсов JAR
        imagePath?.let {
            val resourcePath = "/org/example/defectmap/$it"
            try {
                val url = javaClass.getResource(resourcePath)
                if (url != null) {
                    return Image(url.toExternalForm())
                }
            } catch (e: Exception) {
                println("⚠️ Не удалось загрузить из ресурсов $it: ${e.message}")
            }
        }

        // 2. Пробуем загрузить из папки images рядом с JAR (для портативной версии)
        imagePath?.let {
            try {
                val file = File("images/$it")
                if (file.exists()) {
                    return Image(file.toURI().toURL().toExternalForm())
                }
            } catch (e: Exception) {
                println("⚠️ Не удалось загрузить из файла images/$it: ${e.message}")
            }
        }

        // 3. Fallback: equipment.jpg из ресурсов
        try {
            val url = javaClass.getResource("/org/example/defectmap/equipment.jpg")
            if (url != null) {
                return Image(url.toExternalForm())
            }
        } catch (e: Exception) {
            println("⚠️ Не удалось загрузить equipment.jpg из ресурсов: ${e.message}")
        }

        // 4. Fallback: equipment.jpg из папки images
        try {
            val file = File("images/equipment.jpg")
            if (file.exists()) {
                return Image(file.toURI().toURL().toExternalForm())
            }
        } catch (e: Exception) {
            println("⚠️ Не удалось загрузить images/equipment.jpg: ${e.message}")
        }

        // 5. Заглушка
        println("⚠️ Нет ни одной картинки, создаю заглушку")
        return createPlaceholderImage()
    }

    /**
     * Загружает картинку по типу оборудования.
     * Логика та же, что в createEquipmentImage(), но тип передаётся параметром.
     */
    private fun createImageForType(type: String): Image {
        val imagePath = when (type) {
            "v_500" -> "ВВБК-500.jfif"
            "v_500_ABB" -> "В-500 элегаз"
            "v_220" -> "ВВБК-220(3 фазы).jpg"
            "v_35" -> "ВВБК-500.jfif"
            "v_10" -> "ВВБК-500.jfif"
            "r_500", "r_220", "r_35", "r_10" -> "РНДЗ-220,500.jpg"
            "autotransformer" -> "АОДЦТН-167000-500-220.jpg"
            "transformer" -> "ТМ-35.jpg"
            "tsn" -> "ТМ-35.jpg"
            "lightning", "lightning_rod" -> "lightning_rod.jpg"
            "opn_500" -> "opn-500.jpg"
            "opn_220" -> "ОПН-220.jpg"
            "opn_35" -> "opn-35.jpg"
            "opn_10" -> "opn-10.jpg"
            "tn_500", "tn_220", "tn_35", "tn_10" -> "tn.jpg"
            "tt_500", "tt_220", "tt_35", "tt_10" -> "ТФЗМ-500.jpg"
            "ks_500", "ks_220", "coupling_capacitor" -> "capacitor.jpg"
            "reactor_500", "reactor_220" -> "reactor.jpg"
            "capacitor" -> "capacitor.jpg"
            "compressor" -> "compressor.jpg"
            else -> null
        }

        imagePath?.let {
            val url = javaClass.getResource("/org/example/defectmap/$it")
            if (url != null) return Image(url.toExternalForm())
            val file = File("images/$it")
            if (file.exists()) return Image(file.toURI().toURL().toExternalForm())
        }

        // Fallback
        val fallbackUrl = javaClass.getResource("/org/example/defectmap/equipment.jpg")
        if (fallbackUrl != null) return Image(fallbackUrl.toExternalForm())

        val fallbackFile = File("images/equipment.jpg")
        if (fallbackFile.exists()) return Image(fallbackFile.toURI().toURL().toExternalForm())

        return createPlaceholderImage()
    }

    /**
     * Создаёт изображение-заглушку (серый квадрат с текстом "Нет изображения")
     */
    private fun createPlaceholderImage(): Image {
        val width = 450
        val height = 450
        val bufferedImage = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = bufferedImage.createGraphics()

        // Серый фон
        graphics.color = java.awt.Color(200, 200, 200)
        graphics.fillRect(0, 0, width, height)

        // Текст
        graphics.color = java.awt.Color(100, 100, 100)
        val font = java.awt.Font("Arial", java.awt.Font.BOLD, 24)
        graphics.font = font
        val metrics = graphics.fontMetrics
        val text = "Нет изображения"
        val x = (width - metrics.stringWidth(text)) / 2
        val y = (height - metrics.height) / 2 + metrics.ascent
        graphics.drawString(text, x, y)

        graphics.dispose()

        // Конвертируем BufferedImage в JavaFX Image
        val baos = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(bufferedImage, "png", baos)
        return Image(java.io.ByteArrayInputStream(baos.toByteArray()))
    }

    private fun loadMarkersOnCanvas(gc: javafx.scene.canvas.GraphicsContext) {
        // ===== Маркеры дефектов =====
        val defects = database.getDefectsByEquipment(equipment.id)
        defects.forEach { defect ->
            if (defect.markerLeft != null && defect.markerTop != null) {
                val x = (defect.markerLeft!! / 100.0) * drawWidth + offsetX
                val y = (defect.markerTop!! / 100.0) * drawHeight + offsetY

                gc.fill = Color.RED
                gc.stroke = Color.WHITE
                gc.lineWidth = 2.0
                gc.fillOval(x - 8, y - 8, 16.0, 16.0)
                gc.strokeOval(x - 8, y - 8, 16.0, 16.0)
            }
        }

        // ===== Маркеры дочерних элементов (из кэша) =====
        cachedChildren.forEach { child ->
            val mainMarker = child.markers.firstOrNull() ?: MarkerPosition(child.left, child.top, true)
            val x = (mainMarker.left / 100.0) * drawWidth + offsetX
            val y = (mainMarker.top / 100.0) * drawHeight + offsetY

            // Синий маркер для дочерних элементов
            gc.fill = Color.web("#17a2b8")
            gc.stroke = Color.WHITE
            gc.lineWidth = 2.0
            gc.fillOval(x - 10, y - 10, 20.0, 20.0)
            gc.strokeOval(x - 10, y - 10, 20.0, 20.0)

            // Буква внутри
            gc.fill = Color.WHITE
            gc.font = javafx.scene.text.Font.font("Arial", javafx.scene.text.FontWeight.BOLD, 11.0)
            gc.textAlign = javafx.scene.text.TextAlignment.CENTER
            gc.fillText(child.letter, x, y + 4)
        }
    }

    private fun addMarkerToDefect(defectId: String, xPercent: Double, yPercent: Double) {
        val defect = defects.find { it.id == defectId }
        if (defect != null) {
            val updatedDefect = defect.copy(
                markerLeft = xPercent,
                markerTop = yPercent
            )
            database.updateDefect(updatedDefect)

            val index = defects.indexOfFirst { it.id == defectId }
            if (index >= 0) {
                defects[index] = updatedDefect
                defectsListView.items[index] = updatedDefect
            }

            val gc = canvas?.graphicsContext2D
            if (gc != null) {
                val x = (xPercent / 100.0) * drawWidth + offsetX
                val y = (yPercent / 100.0) * drawHeight + offsetY

                gc.fill = Color.RED
                gc.stroke = Color.WHITE
                gc.lineWidth = 2.0
                gc.fillOval(x - 8, y - 8, 16.0, 16.0)
                gc.strokeOval(x - 8, y - 8, 16.0, 16.0)
            }

            updateDefectsCount()
            showToast("✅ Метка добавлена для '${defect.name}'")
        }
    }

    private var imageWrapperRef: StackPane? = null

    private fun refreshImagePanel() {
        val wrapper = imageWrapperRef ?: return
        wrapper.children.clear()

        val image = createEquipmentImage()
        val canvasWidth = 450.0
        val canvasHeight = 450.0

        val scale = minOf(canvasWidth / image.width, canvasHeight / image.height)
        val drawWidth = image.width * scale
        val drawHeight = image.height * scale
        val offsetX = (canvasWidth - drawWidth) / 2
        val offsetY = (canvasHeight - drawHeight) / 2

        this.drawWidth = drawWidth
        this.drawHeight = drawHeight
        this.offsetX = offsetX
        this.offsetY = offsetY

        val newCanvas = Canvas(canvasWidth, canvasHeight)
        this.canvas = newCanvas
        val gc = newCanvas.graphicsContext2D

        gc.drawImage(image, offsetX, offsetY, drawWidth, drawHeight)
        loadMarkersOnCanvas(gc)

        // ===== ОБРАБОТЧИКИ =====
        newCanvas.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (isMarkerMode && selectedDefectId != null) {
                val clickX = event.x
                val clickY = event.y
                val xInImage = clickX - offsetX
                val yInImage = clickY - offsetY
                if (xInImage >= 0 && xInImage <= drawWidth && yInImage >= 0 && yInImage <= drawHeight) {
                    val xPercent = (xInImage / drawWidth) * 100
                    val yPercent = (yInImage / drawHeight) * 100
                    addMarkerToDefect(
                        selectedDefectId!!,
                        xPercent.coerceIn(0.0, 100.0),
                        yPercent.coerceIn(0.0, 100.0)
                    )
                    isMarkerMode = false
                    selectedDefectId = null
                } else {
                    showToast("⚠️ Кликните внутри картинки")
                }
            }
        }

        newCanvas.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (isChildMarkerMode && currentEditingChildId != null) {
                val clickX = event.x
                val clickY = event.y
                val xInImage = clickX - offsetX
                val yInImage = clickY - offsetY
                if (xInImage >= 0 && xInImage <= drawWidth && yInImage >= 0 && yInImage <= drawHeight) {
                    val xPercent = (xInImage / drawWidth) * 100
                    val yPercent = (yInImage / drawHeight) * 100
                    updateChildMarkerPosition(
                        currentEditingChildId!!,
                        xPercent.coerceIn(0.0, 100.0),
                        yPercent.coerceIn(0.0, 100.0)
                    )
                    isChildMarkerMode = false
                    currentEditingChildId = null
                }
            }
        }

        newCanvas.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (!isMarkerMode && !isChildMarkerMode) {
                val clickX = event.x
                val clickY = event.y
                val allEquipment = database.loadAllEquipment()
                val children = allEquipment.filter { it.parentId == equipment.id }
                for (child in children) {
                    val mainMarker = child.markers.firstOrNull() ?: MarkerPosition(child.left, child.top, true)
                    val markerX = (mainMarker.left / 100.0) * drawWidth + offsetX
                    val markerY = (mainMarker.top / 100.0) * drawHeight + offsetY
                    val radius = 12.0
                    val dx = clickX - markerX
                    val dy = clickY - markerY
                    if (dx * dx + dy * dy <= radius * radius) {
                        openChildCard(child)
                        return@addEventHandler
                    }
                }
                for ((index, defect) in defects.withIndex()) {
                    if (defect.markerLeft != null && defect.markerTop != null) {
                        val markerX = (defect.markerLeft!! / 100.0) * drawWidth + offsetX
                        val markerY = (defect.markerTop!! / 100.0) * drawHeight + offsetY
                        val radius = 10.0
                        val dx = clickX - markerX
                        val dy = clickY - markerY
                        if (dx * dx + dy * dy <= radius * radius) {
                            if (event.clickCount == 1) {
                                defectsListView.selectionModel.select(index)
                                defectsListView.scrollTo(index)
                                showToast("📍 ${defect.name}")
                            } else if (event.clickCount == 2) {
                                editDefectDialog(defect)
                            }
                            break
                        }
                    }
                }
            }
        }

        // ===== ПРЕВЬЮ ПРИ НАВЕДЕНИИ (Popup) =====
        newCanvas.addEventHandler(MouseEvent.MOUSE_MOVED) { event ->
            if (isMarkerMode || isChildMarkerMode) {
                previewPopup?.hide()
                return@addEventHandler
            }
            handleMarkerHover(event.x, event.y)
        }

        newCanvas.addEventHandler(MouseEvent.MOUSE_EXITED) {
            previewPopup?.hide()
        }

        wrapper.children.add(newCanvas)
    }

    private fun findImageWrapper(node: javafx.scene.Node): StackPane? {
        if (node is StackPane && node.children.isNotEmpty() && node.children[0] is Canvas) {
            return node
        }
        if (node is javafx.scene.layout.Pane) {
            for (child in node.children) {
                val result = findImageWrapper(child)
                if (result != null) return result
            }
        }
        return null
    }

    // ======================== ПАНЕЛЬ С ДЕФЕКТАМИ ========================

    private fun collectAllDefectsRecursive(equipmentId: String, visited: MutableSet<String> = mutableSetOf()): List<Pair<EquipmentData, DefectData>> {
        if (!visited.add(equipmentId)) return emptyList()

        val result = mutableListOf<Pair<EquipmentData, DefectData>>()
        val allEquipment = database.loadAllEquipment()
        val currentEquipment = allEquipment.find { it.id == equipmentId } ?: return emptyList()

        // Свои дефекты
        database.getDefectsByEquipment(equipmentId).forEach { defect ->
            result.add(currentEquipment to defect)
        }

        // Дефекты дочерних
        allEquipment.filter { it.parentId == equipmentId }.forEach { child ->
            result.addAll(collectAllDefectsRecursive(child.id, visited))
        }

        return result
    }

    private fun setupDefectsListView() {
        defectsListView.prefHeight = 350.0
        defectsListView.style = "-fx-font-size: 14px; -fx-border-color: #dee2e6; -fx-border-radius: 4px;"

        defectsListView.items = javafx.collections.FXCollections.observableArrayList(defects)

        defectsListView.setCellFactory {
            object : javafx.scene.control.ListCell<DefectData>() {
                override fun updateItem(defect: DefectData?, empty: Boolean) {
                    super.updateItem(defect, empty)
                    if (empty || defect == null) {
                        text = null
                        tooltip = null
                    } else {
                        val statusText = when (defect.status) {
                            "open" -> "🟡 Обнаружен"
                            "fixed" -> "✅ Устранён"
                            else -> defect.status
                        }
                        val markerIcon = if (defect.markerLeft != null && defect.markerTop != null) " 📍" else ""
                        text = "${defect.name} [$statusText]$markerIcon"

                        if (defect.description.isNotEmpty()) {
                            tooltip = Tooltip(defect.description)
                        } else {
                            tooltip = null
                        }
                    }
                }
            }
        }

        // ===== ДВОЙНОЙ КЛИК ПО ДЕФЕКТУ =====
        defectsListView.setOnMouseClicked { event ->
            if (event.clickCount == 2) {
                val selected = defectsListView.selectionModel.selectedItem
                if (selected != null) {
                    editDefectDialog(selected)
                }
            }
        }

        // ===== КОНТЕКСТНОЕ МЕНЮ =====
        val contextMenu = ContextMenu()
        val editItem = MenuItem("✏️ Редактировать")
        val deleteItem = MenuItem("🗑️ Удалить дефект")
        val addMarkerItem = MenuItem("📌 Отметить на оборудовании")
        val removeMarkerItem = MenuItem("🗑️ Удалить маркер")

        editItem.setOnAction {
            val selected = defectsListView.selectionModel.selectedItem
            if (selected != null) {
                editDefectDialog(selected)
            }
        }

        deleteItem.setOnAction {
            val selected = defectsListView.selectionModel.selectedItem
            if (selected != null) {
                val confirm = Alert(AlertType.CONFIRMATION)
                confirm.title = "Удаление дефекта"
                confirm.headerText = "Удалить дефект?"
                confirm.contentText = "Вы уверены, что хотите удалить '${selected.name}'?\n\nВместе с дефектом будет удалён и его маркер на картинке."
                val result = confirm.showAndWait()
                if (result.isPresent && result.get() == ButtonType.OK) {
                    database.deleteDefect(selected.id)
                    defects.remove(selected)
                    defectsListView.items.remove(selected)
                    refreshImagePanel()
                    updateDefectsCount()
                    showToast("🗑️ Дефект и маркер удалены")
                    onDefectChanged?.invoke()
                }
            }
        }

        addMarkerItem.setOnAction {
            val selected = defectsListView.selectionModel.selectedItem
            if (selected != null) {
                isMarkerMode = true
                selectedDefectId = selected.id
                showToast("📌 Кликните на картинке, чтобы отметить '${selected.name}'")
            }
        }

        removeMarkerItem.setOnAction {
            val selected = defectsListView.selectionModel.selectedItem
            if (selected != null) {
                if (selected.markerLeft != null && selected.markerTop != null) {
                    val confirm = Alert(AlertType.CONFIRMATION)
                    confirm.title = "Удаление маркера"
                    confirm.headerText = "Удалить маркер?"
                    confirm.contentText = "Вы уверены, что хотите удалить маркер для '${selected.name}'?"
                    val result = confirm.showAndWait()
                    if (result.isPresent && result.get() == ButtonType.OK) {
                        val updatedDefect = selected.copy(
                            markerLeft = null,
                            markerTop = null
                        )
                        database.updateDefect(updatedDefect)

                        val index = defects.indexOfFirst { it.id == selected.id }
                        if (index >= 0) {
                            defects[index] = updatedDefect
                            defectsListView.items[index] = updatedDefect
                        }

                        refreshImagePanel()
                        showToast("🗑️ Маркер удалён")
                        onDefectChanged?.invoke()
                    }
                } else {
                    showToast("⚠️ У этого дефекта нет маркера")
                }
            }
        }

        contextMenu.items.addAll(editItem, deleteItem, addMarkerItem, removeMarkerItem)
        defectsListView.contextMenu = contextMenu
    }

    // ======================== ФОРМА ДОБАВЛЕНИЯ ДЕФЕКТА ========================

    private fun createAddDefectPanel(): HBox {
        val panel = HBox(10.0)
        panel.alignment = Pos.CENTER_LEFT
        panel.style = "-fx-padding: 12px 0; -fx-background-color: #f8f9fa; -fx-border-color: #e9ecef; -fx-border-width: 1px 0 0 0;"

        val addBtn = Button("➕ Добавить дефект")
        addBtn.style = "-fx-background-color: #28a745; -fx-text-fill: white; -fx-padding: 10px 24px; -fx-font-size: 14px; -fx-background-radius: 4px;"

        addBtn.setOnAction {
            showAddDefectDialog()
        }

        panel.children.addAll(addBtn)
        return panel
    }

    private fun showAddDefectDialog() {
        val dialog = Dialog<ButtonType>()
        dialog.title = "Добавление дефекта"
        dialog.headerText = "📝 Введите данные дефекта"

        val content = VBox(15.0)
        content.style = "-fx-padding: 20px; -fx-pref-width: 500px;"

        // ===== ВИД ДЕФЕКТА (выбор из списка + возможность ввести своё) =====
        val typeLabel = Label("Вид дефекта:")
        typeLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"

        val typeCombo = ComboBox<String>()
        typeCombo.isEditable = true  // ← Разрешаем вводить своё
        typeCombo.items.addAll(DefectTypes.ALL_TYPES)
        typeCombo.value = DefectTypes.ALL_TYPES.firstOrNull() ?: "Прочее"
        typeCombo.style = "-fx-pref-width: 350px; -fx-font-size: 14px;"
        typeCombo.promptText = "Выберите или введите свой вид дефекта"

        // Подсказка
        val typeHint = Label("💡 Можно выбрать из списка или ввести свой вариант")
        typeHint.style = "-fx-font-size: 11px; -fx-text-fill: #6c757d;"

        // ===== ОПИСАНИЕ =====
        val descLabel = Label("Описание:")
        descLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val descField = TextArea()
        descField.promptText = "Введите подробное описание дефекта..."
        descField.prefHeight = 120.0
        descField.style = "-fx-padding: 8px 12px; -fx-font-size: 14px; -fx-border-color: #ced4da; -fx-border-radius: 4px;"

        // ===== СТАТУС =====
        val statusLabel = Label("Статус:")
        statusLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val statusCombo = ComboBox<String>()
        statusCombo.items.addAll("обнаружен", "устранён")
        statusCombo.value = "обнаружен"
        statusCombo.style = "-fx-pref-width: 150px; -fx-padding: 6px; -fx-font-size: 14px;"

        val statusBox = HBox(10.0, statusLabel, statusCombo)
        statusBox.alignment = Pos.CENTER_LEFT

        content.children.addAll(
            typeLabel, typeCombo, typeHint,
            descLabel, descField,
            statusBox
        )

        dialog.dialogPane.content = content
        dialog.dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val result = dialog.showAndWait()
        if (result.isPresent && result.get() == ButtonType.OK) {
            val defectType = typeCombo.value?.trim() ?: ""

            if (defectType.isEmpty()) {
                showError("Выберите или введите вид дефекта")
                return
            }

            val newDefect = DefectData(
                id = "defect-${System.currentTimeMillis()}",
                equipmentId = equipment.id,
                name = defectType,  // ← Используем вид дефекта как имя
                description = descField.text.trim(),
                severity = "medium",
                status = if (statusCombo.value == "устранён") "fixed" else "open"
            )
            database.saveDefect(newDefect)
            defects.add(newDefect)
            defectsListView.items.add(newDefect)
            updateDefectsCount()
            showToast("✅ Дефект '$defectType' добавлен")
            onDefectChanged?.invoke()
        }
    }

    // ======================== РЕДАКТИРОВАНИЕ ДЕФЕКТА ========================

    private fun editDefectDialog(defect: DefectData) {
        val dialog = Dialog<ButtonType>()
        dialog.title = "Редактирование дефекта"
        dialog.headerText = "Измените данные дефекта"

        val content = VBox(10.0)
        content.style = "-fx-padding: 20px; -fx-pref-width: 500px;"

        // ===== ВИД ДЕФЕКТА =====
        val typeLabel = Label("Вид дефекта:")
        typeLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"

        val typeCombo = ComboBox<String>()
        typeCombo.isEditable = true
        typeCombo.items.addAll(DefectTypes.ALL_TYPES)
        typeCombo.value = defect.name
        typeCombo.style = "-fx-pref-width: 350px; -fx-font-size: 14px;"

        // ===== ОПИСАНИЕ =====
        val descLabel = Label("Описание:")
        descLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val descField = TextArea(defect.description)
        descField.promptText = "Описание"
        descField.prefHeight = 100.0
        descField.style = "-fx-padding: 8px 12px; -fx-font-size: 14px; -fx-border-color: #ced4da; -fx-border-radius: 4px;"

        // ===== СТАТУС =====
        val statusLabel = Label("Статус:")
        statusLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val statusCombo = ComboBox<String>()
        statusCombo.items.addAll("обнаружен", "устранён")
        statusCombo.value = if (defect.status == "fixed") "устранён" else "обнаружен"
        statusCombo.style = "-fx-pref-width: 150px; -fx-padding: 6px; -fx-font-size: 14px;"

        content.children.addAll(
            typeLabel, typeCombo,
            descLabel, descField,
            statusLabel, statusCombo
        )

        dialog.dialogPane.content = content
        dialog.dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val result = dialog.showAndWait()
        if (result.isPresent && result.get() == ButtonType.OK) {
            val defectType = typeCombo.value?.trim() ?: ""

            if (defectType.isEmpty()) {
                showError("Выберите или введите вид дефекта")
                return
            }

            val updatedDefect = defect.copy(
                name = defectType,
                description = descField.text.trim(),
                status = if (statusCombo.value == "устранён") "fixed" else "open"
            )
            database.updateDefect(updatedDefect)

            val index = defects.indexOfFirst { it.id == defect.id }
            if (index >= 0) {
                defects[index] = updatedDefect
                defectsListView.items[index] = updatedDefect
            }
            showToast("✅ Дефект обновлён")
            onDefectChanged?.invoke()
        }
    }

    // ======================== ВСПОМОГАТЕЛЬНЫЕ ========================

    private fun updateDefectsCount() {
        val parent = defectsListView.parent
        if (parent is VBox && parent.children.isNotEmpty()) {
            val label = parent.children[0] as? Label
            val all = collectAllDefectsRecursive(equipment.id)
            label?.text = "📋 Дефекты (${all.size})"
        }
    }

    private fun showToast(message: String) {
        Platform.runLater {
            val toast = Label(message)
            toast.style = """
                -fx-background-color: rgba(0, 0, 0, 0.85);
                -fx-text-fill: white;
                -fx-font-size: 14px;
                -fx-padding: 12px 24px;
                -fx-background-radius: 8px;
                -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 10, 0, 0, 0);
            """.trimIndent()
            toast.isWrapText = true
            toast.maxWidth = 500.0
            toast.alignment = Pos.CENTER
            toast.isMouseTransparent = true

            val scene = defectsListView.scene ?: return@runLater
            val root = scene.root as? javafx.scene.layout.Pane ?: return@runLater

            val stackPane = StackPane()
            stackPane.children.add(toast)
            stackPane.isMouseTransparent = true
            root.children.add(stackPane)
            StackPane.setAlignment(stackPane, Pos.TOP_CENTER)
            StackPane.setMargin(stackPane, Insets(80.0, 0.0, 0.0, 0.0))

            val pause = PauseTransition(Duration.seconds(2.5))
            pause.setOnFinished {
                root.children.remove(stackPane)
            }
            pause.play()
        }
    }

    private fun showError(message: String) {
        Platform.runLater {
            Alert(AlertType.ERROR).apply {
                title = "Ошибка"
                headerText = null
                contentText = message
                showAndWait()
            }
        }
    }

    private fun addChildElementDialog() {
        // 1. Диалог для названия
        val nameDialog = TextInputDialog()
        nameDialog.title = "Новый дочерний элемент"
        nameDialog.headerText = "Введите название дочернего элемента"
        nameDialog.contentText = "Название:"
        nameDialog.editor?.text = ""

        val nameResult = nameDialog.showAndWait()
        if (nameResult.isEmpty || nameResult.get().trim().isEmpty()) return

        val childName = nameResult.get().trim()

        // 2. Диалог выбора типа
        val typeDialog = ChoiceDialog(
            EquipmentTypes.ALL_TYPES.firstOrNull()?.second ?: "Другое",
            EquipmentTypes.ALL_TYPES.map { it.second }
        )
        typeDialog.title = "Тип дочернего элемента"
        typeDialog.headerText = "Выберите тип"

        val typeResult = typeDialog.showAndWait()
        if (typeResult.isEmpty) return

        val typeName = typeResult.get()
        val childType = EquipmentTypes.ALL_TYPES.find { it.second == typeName }?.first ?: "other"
        val childLetter = EquipmentTypes.getLetter(childType)

        // 3. Создаём дочерний элемент в БД
        val childId = "equipment-${System.currentTimeMillis()}"
        val child = EquipmentData(
            id = childId,
            left = 50.0,
            top = 50.0,
            type = childType,
            name = childName,
            letter = childLetter,
            cell = equipment.cell,
            size = "normal",
            markers = listOf(MarkerPosition(50.0, 50.0, true)),
            parentId = equipment.id  // ← ПРИВЯЗКА К РОДИТЕЛЮ
        )

        val allEquipment = database.loadAllEquipment()
        database.saveEquipment(allEquipment + child)

// обновляем кэш
        cachedChildren = database.loadAllEquipment().filter { it.parentId == equipment.id }

        showToast("✅ Дочерний элемент добавлен: $childName")

        // Включаем режим установки маркера для дочернего элемента
        isChildMarkerMode = true
        currentEditingChildId = childId
        showToast("📌 Кликните на картинке, чтобы отметить '$childName'")
    }

    private fun updateChildMarkerPosition(childId: String, xPercent: Double, yPercent: Double) {
        val allEquipment = database.loadAllEquipment()
        val updatedList = allEquipment.map { eq ->
            if (eq.id == childId) {
                eq.copy(
                    left = xPercent,
                    top = yPercent,
                    markers = listOf(MarkerPosition(xPercent, yPercent, true))
                )
            } else eq
        }
        database.saveEquipment(updatedList)
        cachedChildren = database.loadAllEquipment().filter { it.parentId == equipment.id }

        // Перерисовываем
        refreshImagePanel()
        showToast("✅ Маркер дочернего элемента установлен")
    }

    private fun openChildCard(child: EquipmentData) {
        println("📂 Открытие дочерней карточки: ${child.name}")
        val cardController = EquipmentCardController(
            equipment = child,
            database = database,
            onDefectChanged = {
                // Обновляем текущую карточку
            },
            parentEquipment = equipment  // ← передаём родителя для breadcrumbs
        )
        cardController.show()
    }

    private fun openParentCard(parent: EquipmentData) {
        val grandParent = parent.parentId?.let { pid ->
            database.loadAllEquipment().find { it.id == pid }
        }

        val cardController = EquipmentCardController(
            equipment = parent,
            database = database,
            onDefectChanged = { },
            parentEquipment = grandParent
        )
        cardController.show()
    }

    private fun showChildrenList() {
        val allEquipment = database.loadAllEquipment()
        val children = allEquipment.filter { it.parentId == equipment.id }

        if (children.isEmpty()) {
            showToast("📋 Нет дочерних элементов")
            return
        }

        val dialog = Stage()
        dialog.title = "📋 Дочерние элементы: ${equipment.name}"
        dialog.initModality(javafx.stage.Modality.WINDOW_MODAL)
        dialog.initOwner(defectsListView.scene.window)
        dialog.minWidth = 600.0
        dialog.minHeight = 400.0

        val root = VBox(10.0)
        root.style = "-fx-background-color: white; -fx-padding: 20px;"

        val headerLabel = Label("📋 Дочерние элементы (${children.size})")
        headerLabel.style = "-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #333;"

        val listView = ListView<EquipmentData>()
        listView.items = javafx.collections.FXCollections.observableArrayList(children)
        listView.style = "-fx-font-size: 13px;"

        listView.setCellFactory {
            object : javafx.scene.control.ListCell<EquipmentData>() {
                override fun updateItem(child: EquipmentData?, empty: Boolean) {
                    super.updateItem(child, empty)
                    if (empty || child == null) {
                        text = null
                    } else {
                        val typeName = EquipmentTypes.getTypeName(child.type)
                        text = "📌 ${child.name} — $typeName"
                    }
                }
            }
        }

        listView.setOnMouseClicked { event ->
            if (event.clickCount == 2) {
                val selected = listView.selectionModel.selectedItem
                if (selected != null) {
                    dialog.close()
                    openChildCard(selected)
                }
            }
        }

        val buttonPanel = HBox(10.0)
        buttonPanel.alignment = Pos.CENTER_RIGHT

        val openBtn = Button("📂 Открыть")
        openBtn.style = "-fx-background-color: #007bff; -fx-text-fill: white; -fx-padding: 6px 16px; -fx-background-radius: 4px;"
        openBtn.setOnAction {
            val selected = listView.selectionModel.selectedItem
            if (selected != null) {
                dialog.close()
                openChildCard(selected)
            }
        }

        val deleteBtn = Button("🗑️ Удалить")
        deleteBtn.style = "-fx-background-color: #dc3545; -fx-text-fill: white; -fx-padding: 6px 16px; -fx-background-radius: 4px;"
        deleteBtn.setOnAction {
            val selected = listView.selectionModel.selectedItem
            if (selected != null) {
                val confirm = Alert(AlertType.CONFIRMATION)
                confirm.title = "Удаление"
                confirm.headerText = "Удалить '${selected.name}'?"
                confirm.contentText = "Дочерний элемент будет удалён. Продолжить?"
                val result = confirm.showAndWait()
                if (result.isPresent && result.get() == ButtonType.OK) {
                    database.deleteById(selected.id)
                    cachedChildren = database.loadAllEquipment().filter { it.parentId == equipment.id }
                    listView.items.remove(selected)
                    showToast("🗑️ Удалено: ${selected.name}")
                }
            }
        }

        val closeBtn = Button("✕ Закрыть")
        closeBtn.style = "-fx-background-color: #6c757d; -fx-text-fill: white; -fx-padding: 6px 20px; -fx-background-radius: 4px;"
        closeBtn.setOnAction { dialog.close() }

        buttonPanel.children.addAll(openBtn, deleteBtn, closeBtn)
        root.children.addAll(headerLabel, listView, buttonPanel)

        dialog.scene = Scene(root, 600.0, 400.0)
        dialog.showAndWait()
    }

    private fun setupDefectsListViewWithChildren(allDefectsWithEquipment: List<Pair<EquipmentData, DefectData>>) {
        defectsListView.prefHeight = 350.0
        defectsListView.style = "-fx-font-size: 14px; -fx-border-color: #dee2e6; -fx-border-radius: 4px;"

        defectsListView.items = javafx.collections.FXCollections.observableArrayList(
            allDefectsWithEquipment.map { it.second }
        )

        defectsListView.setCellFactory {
            object : javafx.scene.control.ListCell<DefectData>() {
                override fun updateItem(defect: DefectData?, empty: Boolean) {
                    super.updateItem(defect, empty)

                    if (empty || defect == null) {
                        text = null
                        graphic = null
                        tooltip = null
                        return
                    }

                    val pair = allDefectsWithEquipment.find { it.second.id == defect.id }
                    val eqName = pair?.first?.name ?: ""
                    val isChild = pair?.first?.id != equipment.id

                    val statusText = when (defect.status) {
                        "open" -> "🟡 Обнаружен"
                        "fixed" -> "✅ Устранён"
                        else -> defect.status
                    }
                    val markerIcon = if (defect.markerLeft != null && defect.markerTop != null) " 📍" else ""
                    val prefix = if (isChild) "└─ " else ""

                    // ===== ЗАГОЛОВОК =====
                    val titleLbl = Label("$prefix${defect.name} [$statusText]$markerIcon")
                    titleLbl.style = "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;"
                    titleLbl.isWrapText = true
                    titleLbl.maxWidth = 420.0

                    // ===== ПОДЗАГОЛОВОК (оборудование + описание) =====
                    val subtitleText = buildString {
                        if (isChild) append("Оборудование: $eqName")
                        if (defect.description.isNotEmpty()) {
                            if (isNotEmpty()) append(" • ")
                            append(defect.description)
                        }
                    }

                    val subtitleLbl = Label(subtitleText.ifEmpty { "—" })
                    subtitleLbl.style = "-fx-font-size: 11px; -fx-text-fill: #6c757d;"
                    subtitleLbl.isWrapText = true
                    subtitleLbl.maxWidth = 420.0

                    val box = VBox(2.0, titleLbl, subtitleLbl)
                    box.style = "-fx-padding: 4px 0;"

                    graphic = box
                    text = null
                    tooltip = null
                }
            }
        }

        // ===== ДВОЙНОЙ КЛИК ПО ДЕФЕКТУ =====
        defectsListView.setOnMouseClicked { event ->
            if (event.clickCount == 2) {
                val selected = defectsListView.selectionModel.selectedItem
                if (selected != null) {
                    editDefectDialog(selected)
                }
            }
        }

        // ===== КОНТЕКСТНОЕ МЕНЮ =====
        val contextMenu = ContextMenu()
        val editItem = MenuItem("✏️ Редактировать")
        val deleteItem = MenuItem("🗑️ Удалить дефект")
        val addMarkerItem = MenuItem("📌 Отметить на оборудовании")
        val removeMarkerItem = MenuItem("🗑️ Удалить маркер")

        editItem.setOnAction {
            val selected = defectsListView.selectionModel.selectedItem
            if (selected != null) editDefectDialog(selected)
        }

        deleteItem.setOnAction {
            val selected = defectsListView.selectionModel.selectedItem
            if (selected != null) {
                val confirm = Alert(AlertType.CONFIRMATION)
                confirm.title = "Удаление дефекта"
                confirm.headerText = "Удалить дефект?"
                confirm.contentText = "Вы уверены, что хотите удалить '${selected.name}'?"
                val result = confirm.showAndWait()
                if (result.isPresent && result.get() == ButtonType.OK) {
                    database.deleteDefect(selected.id)

                    // Перестраиваем список целиком
                    val fresh = collectAllDefectsRecursive(equipment.id)
                    defectsListView.items = FXCollections.observableArrayList(fresh.map { it.second })

                    // Обновляем заголовок
                    val parent = defectsListView.parent
                    if (parent is VBox && parent.children.isNotEmpty()) {
                        (parent.children[0] as? Label)?.text = "📋 Дефекты (${fresh.size})"
                    }

                    refreshImagePanel()
                    showToast("🗑️ Дефект удалён")
                    onDefectChanged?.invoke()
                }
            }
        }

        addMarkerItem.setOnAction {
            val selected = defectsListView.selectionModel.selectedItem
            if (selected != null) {
                isMarkerMode = true
                selectedDefectId = selected.id
                showToast("📌 Кликните на картинке, чтобы отметить '${selected.name}'")
            }
        }

        removeMarkerItem.setOnAction {
            val selected = defectsListView.selectionModel.selectedItem
            if (selected != null) {
                if (selected.markerLeft != null && selected.markerTop != null) {
                    val confirm = Alert(AlertType.CONFIRMATION)
                    confirm.title = "Удаление маркера"
                    confirm.headerText = "Удалить маркер?"
                    confirm.contentText = "Вы уверены, что хотите удалить маркер для '${selected.name}'?"
                    val result = confirm.showAndWait()
                    if (result.isPresent && result.get() == ButtonType.OK) {
                        val updatedDefect = selected.copy(markerLeft = null, markerTop = null)
                        database.updateDefect(updatedDefect)

                        val index = defects.indexOfFirst { it.id == selected.id }
                        if (index >= 0) {
                            defects[index] = updatedDefect
                            defectsListView.items[index] = updatedDefect
                        }

                        refreshImagePanel()
                        showToast("🗑️ Маркер удалён")
                        onDefectChanged?.invoke()
                    }
                } else {
                    showToast("⚠️ У этого дефекта нет маркера")
                }
            }
        }

        contextMenu.items.addAll(editItem, deleteItem, addMarkerItem, removeMarkerItem)
        defectsListView.contextMenu = contextMenu
    }

    private fun createRightPanel(): VBox {
        val rightPanel = VBox(10.0)
        rightPanel.prefWidth = 500.0

        // ===== СОБИРАЕМ ВСЕ ДЕФЕКТЫ (включая дочерние) =====
        val allDefectsWithEquipment = collectAllDefectsRecursive(equipment.id)
        println("📋 Всего дефектов (включая дочерние): ${allDefectsWithEquipment.size}")

        val defectsLabel = Label("📋 Дефекты (${allDefectsWithEquipment.size})")
        defectsLabel.style = "-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: #333;"

        setupDefectsListViewWithChildren(allDefectsWithEquipment)
        val addDefectPanel = createAddDefectPanel()

        rightPanel.children.addAll(defectsLabel, defectsListView, addDefectPanel)
        return rightPanel
    }


    /**
     * Проверяет, находится ли курсор над маркером (дефекта или дочернего элемента).
     * Если да — показывает превью с картинкой и названием.
     * Использует cachedChildren, чтобы не читать БД на каждое движение мыши.
     */
    private fun handleMarkerHover(mouseX: Double, mouseY: Double) {
        val popup = previewPopup ?: return
        val imgView = previewImageView ?: return
        val lbl = previewLabel ?: return

        // ===== 1. Маркеры дочерних =====
        var hoveredChild: EquipmentData? = null
        for (child in cachedChildren) {
            val mainMarker = child.markers.firstOrNull() ?: MarkerPosition(child.left, child.top, true)
            val mx = (mainMarker.left / 100.0) * drawWidth + offsetX
            val my = (mainMarker.top / 100.0) * drawHeight + offsetY
            val dx = mouseX - mx
            val dy = mouseY - my
            if (dx * dx + dy * dy <= 12.0 * 12.0) {
                hoveredChild = child
                break
            }
        }

        // ===== 2. Маркеры дефектов =====
        var hoveredDefect: DefectData? = null
        if (hoveredChild == null) {
            for (defect in defects) {
                if (defect.markerLeft == null || defect.markerTop == null) continue
                val mx = (defect.markerLeft / 100.0) * drawWidth + offsetX
                val my = (defect.markerTop / 100.0) * drawHeight + offsetY
                val dx = mouseX - mx
                val dy = mouseY - my
                if (dx * dx + dy * dy <= 10.0 * 10.0) {
                    hoveredDefect = defect
                    break
                }
            }
        }

        when {
            hoveredChild != null -> {
                imgView.image = null
                imgView.isVisible = false
                imgView.isManaged = false
                lbl.text = hoveredChild.name
                lbl.style = "-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #17a2b8; -fx-wrap-text: true; -fx-max-width: 200px; -fx-padding: 6px 4px;"
                showPreviewPopup(mouseX, mouseY)
            }
            hoveredDefect != null -> {
                imgView.image = createEquipmentImage()
                imgView.isVisible = true
                imgView.isManaged = true
                lbl.text = "🔴 ${hoveredDefect.name}"
                lbl.style = "-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #333; -fx-wrap-text: true; -fx-max-width: 160px;"
                showPreviewPopup(mouseX, mouseY)
            }
            else -> {
                popup.hide()
            }
        }
    }

    private fun showPreviewPopup(mouseX: Double, mouseY: Double) {
        val popup = previewPopup ?: return
        val wrapper = imageWrapperRef ?: return
        val scene = wrapper.scene ?: return

        // Точка в сцене
        val scenePoint = wrapper.localToScene(mouseX, mouseY)

        // Координаты на экране
        val window = scene.window ?: return
        val screenX = window.x + scenePoint.x + 20
        val screenY = window.y + scenePoint.y + 20

        // Показываем popup (если ещё не показан)
        if (!popup.isShowing) {
            popup.show(window, screenX, screenY)
        } else {
            popup.x = screenX
            popup.y = screenY
        }
    }


}

data class ChildMarker(
    val childId: String,
    val left: Double,
    val top: Double
)