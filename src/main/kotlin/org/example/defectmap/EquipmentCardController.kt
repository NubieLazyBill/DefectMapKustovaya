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
import javafx.util.Duration

class EquipmentCardController(
    private val equipment: EquipmentData,
    private val database: Database,
    private val onDefectChanged: (() -> Unit)? = null
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

    fun show() {
        defects.clear()
        defects.addAll(database.getDefectsByEquipment(equipment.id))

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
        popupStage.showAndWait()
    }

    // ======================== ПАНЕЛЬ С КАРТИНКОЙ ========================

    private fun createImagePanel(): VBox {
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

        val canvas = Canvas(canvasWidth, canvasHeight)
        this.canvas = canvas
        val gc = canvas.graphicsContext2D

        gc.drawImage(image, offsetX, offsetY, drawWidth, drawHeight)
        loadMarkersOnCanvas(gc)

        // Обработчик клика по Canvas (добавление маркера)
        canvas.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (isMarkerMode && selectedDefectId != null) {
                val clickX = event.x
                val clickY = event.y
                val xInImage = clickX - offsetX
                val yInImage = clickY - offsetY

                if (xInImage >= 0 && xInImage <= drawWidth &&
                    yInImage >= 0 && yInImage <= drawHeight) {

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

        // Обработчик клика по Canvas (клик по маркеру)
        canvas.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (!isMarkerMode) {
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
                                // Одиночный клик — выделяем в списке
                                defectsListView.selectionModel.select(index)
                                defectsListView.scrollTo(index)
                                showToast("📍 ${defect.name}")
                            } else if (event.clickCount == 2) {
                                // Двойной клик — открываем редактирование
                                editDefectDialog(defect)
                            }
                            break
                        }
                    }
                }
            }
        }

        val imageWrapper = StackPane()
        imageWrapper.children.add(canvas)
        imageWrapper.style = "-fx-border-color: #dee2e6; -fx-border-radius: 8px; -fx-background-color: white;"

        val imageContainer = VBox(10.0, imageWrapper)
        imageContainer.alignment = Pos.TOP_CENTER
        imageContainer.prefWidth = 500.0
        imageContainer.style = "-fx-padding: 15px;"

        val infoLabel = Label("${equipment.type} | ${equipment.size}")
        infoLabel.style = "-fx-font-size: 13px; -fx-text-fill: #6c757d;"
        imageContainer.children.add(infoLabel)

        return imageContainer
    }

    private fun createEquipmentImage(): Image {
        val imagePath = when (equipment.type) {
            "v_500", "v_220", "v_35", "v_10" -> "/org/example/defectmap/ВВБК-500.jfif"
            "r_500", "r_220", "r_35", "r_10" -> "/org/example/defectmap/disconnector.jpg"
            "autotransformer", "transformer" -> "/org/example/defectmap/transformer.jpg"
            "lightning", "lightning_rod" -> "/org/example/defectmap/lightning_rod.jpg"
            "opn_500", "opn_220", "opn_35", "opn_10" -> "/org/example/defectmap/opn.jpg"
            "tn_500", "tn_220", "tn_35", "tn_10" -> "/org/example/defectmap/tn.jpg"
            "tt_500", "tt_220", "tt_35", "tt_10" -> "/org/example/defectmap/tt.jpg"
            "ks_500", "ks_220", "coupling_capacitor" -> "/org/example/defectmap/capacitor.jpg"
            "reactor_500", "reactor_220" -> "/org/example/defectmap/reactor.jpg"
            "capacitor" -> "/org/example/defectmap/capacitor.jpg"
            "compressor" -> "/org/example/defectmap/compressor.jpg"
            else -> null
        }

        return try {
            if (imagePath != null) {
                val url = javaClass.getResource(imagePath)
                if (url != null) {
                    Image(url.toExternalForm())
                } else {
                    // fallback
                    val defaultUrl = javaClass.getResource("/org/example/defectmap/equipment.jpg")
                    if (defaultUrl != null) Image(defaultUrl.toExternalForm())
                    else throw RuntimeException("Нет ни одной картинки")
                }
            } else {
                val defaultUrl = javaClass.getResource("/org/example/defectmap/equipment.jpg")
                if (defaultUrl != null) Image(defaultUrl.toExternalForm())
                else throw RuntimeException("Нет ни одной картинки")
            }
        } catch (e: Exception) {
            println("❌ Ошибка загрузки картинки: ${e.message}")
            // Создаём пустое изображение 1x1
            Image(javaClass.getResourceAsStream("/org/example/defectmap/equipment.jpg"))
        }
    }

    private fun loadMarkersOnCanvas(gc: javafx.scene.canvas.GraphicsContext) {
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

    private fun refreshImagePanel() {
        val root = defectsListView.scene?.root as? javafx.scene.layout.Pane ?: return
        val imageWrapper = findImageWrapper(root) ?: return

        imageWrapper.children.clear()

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

        val canvas = Canvas(canvasWidth, canvasHeight)
        this.canvas = canvas
        val gc = canvas.graphicsContext2D

        gc.drawImage(image, offsetX, offsetY, drawWidth, drawHeight)
        loadMarkersOnCanvas(gc)

        // Обработчик клика по Canvas (добавление маркера)
        canvas.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (isMarkerMode && selectedDefectId != null) {
                val clickX = event.x
                val clickY = event.y
                val xInImage = clickX - offsetX
                val yInImage = clickY - offsetY

                if (xInImage >= 0 && xInImage <= drawWidth &&
                    yInImage >= 0 && yInImage <= drawHeight) {

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

        // Обработчик клика по Canvas (клик по маркеру)
        canvas.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (!isMarkerMode) {
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

        imageWrapper.children.add(canvas)
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

    private fun createRightPanel(): VBox {
        val rightPanel = VBox(10.0)
        rightPanel.prefWidth = 500.0

        val defectsLabel = Label("📋 Дефекты (${defects.size})")
        defectsLabel.style = "-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: #333;"

        setupDefectsListView()
        val addDefectPanel = createAddDefectPanel()

        rightPanel.children.addAll(defectsLabel, defectsListView, addDefectPanel)
        return rightPanel
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
                        // Убираем иконку важности, оставляем только статус
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

        // ===== ДВОЙНОЙ КЛИК ПО ДЕФЕКТУ (ОТКРЫВАЕТ РЕДАКТИРОВАНИЕ) =====
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
        content.style = "-fx-padding: 20px; -fx-pref-width: 450px;"

        val nameLabel = Label("Название дефекта:")
        nameLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val nameField = TextField()
        nameField.promptText = "Введите название дефекта..."
        nameField.style = "-fx-padding: 8px 12px; -fx-font-size: 14px;"

        val descLabel = Label("Описание:")
        descLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val descField = TextArea()
        descField.promptText = "Введите описание дефекта..."
        descField.prefHeight = 100.0
        descField.style = "-fx-padding: 8px 12px; -fx-font-size: 14px; -fx-border-color: #ced4da; -fx-border-radius: 4px;"

        // ===== СТАТУС (только два пункта) =====
        val statusLabel = Label("Статус:")
        statusLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val statusCombo = ComboBox<String>()
        statusCombo.items.addAll("обнаружен", "устранён")
        statusCombo.value = "обнаружен"
        statusCombo.style = "-fx-pref-width: 120px; -fx-padding: 6px; -fx-font-size: 14px;"

        val statusBox = HBox(10.0, statusLabel, statusCombo)
        statusBox.alignment = Pos.CENTER_LEFT

        content.children.addAll(
            nameLabel, nameField,
            descLabel, descField,
            statusBox
        )

        dialog.dialogPane.content = content
        dialog.dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val result = dialog.showAndWait()
        if (result.isPresent && result.get() == ButtonType.OK) {
            val name = nameField.text.trim()
            if (name.isNotEmpty()) {
                val newDefect = DefectData(
                    id = "defect-${System.currentTimeMillis()}",
                    equipmentId = equipment.id,
                    name = name,
                    description = descField.text.trim(),
                    severity = "medium",  // по умолчанию
                    status = if (statusCombo.value == "устранён") "fixed" else "open"
                )
                database.saveDefect(newDefect)
                defects.add(newDefect)
                defectsListView.items.add(newDefect)
                updateDefectsCount()
                showToast("✅ Дефект '$name' добавлен")
                onDefectChanged?.invoke()
            } else {
                showError("Введите название дефекта")
            }
        }
    }

    // ======================== РЕДАКТИРОВАНИЕ ДЕФЕКТА ========================

    private fun editDefectDialog(defect: DefectData) {
        val dialog = Dialog<ButtonType>()
        dialog.title = "Редактирование дефекта"
        dialog.headerText = "Измените данные дефекта"

        val content = VBox(10.0)
        content.style = "-fx-padding: 20px; -fx-pref-width: 450px;"

        val nameField = TextField(defect.name)
        nameField.promptText = "Название"
        nameField.style = "-fx-padding: 8px 12px; -fx-font-size: 14px;"

        val descField = TextArea(defect.description)
        descField.promptText = "Описание"
        descField.prefHeight = 80.0
        descField.style = "-fx-padding: 8px 12px; -fx-font-size: 14px; -fx-border-color: #ced4da; -fx-border-radius: 4px;"

        // ===== СТАТУС (только два пункта) =====
        val statusLabel = Label("Статус:")
        statusLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val statusCombo = ComboBox<String>()
        statusCombo.items.addAll("обнаружен", "устранён")
        // Устанавливаем текущее значение
        statusCombo.value = if (defect.status == "fixed") "устранён" else "обнаружен"
        statusCombo.style = "-fx-pref-width: 120px; -fx-padding: 6px; -fx-font-size: 14px;"

        content.children.addAll(
            Label("Название:"), nameField,
            Label("Описание:"), descField,
            Label("Статус:"), statusCombo
        )

        dialog.dialogPane.content = content
        dialog.dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val result = dialog.showAndWait()
        if (result.isPresent && result.get() == ButtonType.OK) {
            val updatedDefect = defect.copy(
                name = nameField.text.trim().ifEmpty { defect.name },
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
            label?.text = "📋 Дефекты (${defects.size})"
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
}