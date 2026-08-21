package org.example.defectmap

import javafx.application.Platform
import javafx.geometry.Pos
import javafx.scene.Scene
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
import javafx.scene.shape.Circle

class EquipmentCardController(
    private val equipment: EquipmentData,
    private val database: Database,
    private val onDefectChanged: (() -> Unit)? = null
) {

    private val defects: MutableList<DefectData> = mutableListOf()
    private val defectsListView = ListView<DefectData>()
    private var isMarkerMode = false
    private var selectedDefectId: String? = null
    private val imageView = ImageView()
    private var markerNodes: MutableList<Circle> = mutableListOf()

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
        val imageView = createEquipmentImageView()
        this.imageView.image = imageView.image
        this.imageView.fitWidth = imageView.fitWidth
        this.imageView.fitHeight = imageView.fitHeight
        this.imageView.isPreserveRatio = imageView.isPreserveRatio

        val stackPane = StackPane()
        stackPane.children.add(imageView)
        stackPane.style = "-fx-border-color: #dee2e6; -fx-border-radius: 8px; -fx-background-color: white;"

        // Загружаем существующие маркеры
        loadMarkers(stackPane)

        // Клик по картинке для добавления маркера
        stackPane.addEventHandler(MouseEvent.MOUSE_CLICKED) { event ->
            if (isMarkerMode && selectedDefectId != null) {
                val x = (event.x / imageView.fitWidth) * 100
                val y = (event.y / imageView.fitHeight) * 100
                addMarkerToDefect(selectedDefectId!!, x, y, stackPane)
                isMarkerMode = false
                selectedDefectId = null
            }
        }

        val imageContainer = VBox(10.0, stackPane)
        imageContainer.alignment = Pos.TOP_CENTER
        imageContainer.prefWidth = 500.0
        imageContainer.style = "-fx-padding: 15px;"

        val infoLabel = Label("${equipment.type} | ${equipment.size}")
        infoLabel.style = "-fx-font-size: 13px; -fx-text-fill: #6c757d;"
        imageContainer.children.add(infoLabel)

        return imageContainer
    }

    private fun createEquipmentImageView(): ImageView {
        var imagePath = "/org/example/defectmap/equipment.jpg"

        imagePath = when (equipment.type) {
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
            else -> "/org/example/defectmap/equipment.jpg"
        }

        val imageUrl = javaClass.getResource(imagePath)
        return if (imageUrl != null) {
            val image = Image(imageUrl.toExternalForm())
            ImageView(image).apply {
                isPreserveRatio = true
                fitWidth = 450.0
                fitHeight = 450.0
            }
        } else {
            ImageView().apply {
                fitWidth = 450.0
                fitHeight = 450.0
                style = "-fx-background-color: #f8f9fa; -fx-border-color: #dee2e6; -fx-border-radius: 8px;"
            }
        }
    }

    private fun loadMarkers(stackPane: StackPane) {
        // Удаляем старые маркеры
        markerNodes.forEach { stackPane.children.remove(it) }
        markerNodes.clear()

        val defects = database.getDefectsByEquipment(equipment.id)
        defects.forEach { defect ->
            if (defect.markerLeft != null && defect.markerTop != null) {
                val circle = Circle(8.0, Color.RED)
                circle.style = "-fx-stroke: white; -fx-stroke-width: 2px;"
                val markerX = (defect.markerLeft!! / 100.0) * imageView.fitWidth
                val markerY = (defect.markerTop!! / 100.0) * imageView.fitHeight
                circle.centerX = markerX
                circle.centerY = markerY
                stackPane.children.add(circle)
                markerNodes.add(circle)
            }
        }
    }

    private fun addMarkerToDefect(defectId: String, x: Double, y: Double, stackPane: StackPane) {
        val defect = defects.find { it.id == defectId }
        if (defect != null) {
            val updatedDefect = defect.copy(
                markerLeft = x,
                markerTop = y
            )
            database.updateDefect(updatedDefect)

            // Обновляем в списке
            val index = defects.indexOfFirst { it.id == defectId }
            if (index >= 0) {
                defects[index] = updatedDefect
                defectsListView.items[index] = updatedDefect
            }

            // Рисуем маркер на картинке
            val circle = Circle(8.0, Color.RED)
            circle.style = "-fx-stroke: white; -fx-stroke-width: 2px;"
            val markerX = (x / 100.0) * imageView.fitWidth
            val markerY = (y / 100.0) * imageView.fitHeight
            circle.centerX = markerX
            circle.centerY = markerY
            stackPane.children.add(circle)
            markerNodes.add(circle)

            updateDefectsCount()
            showToast("✅ Метка добавлена для дефекта '${defect.name}'")
        }
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
                        val severityIcon = when (defect.severity) {
                            "critical" -> "🔴"
                            "high" -> "🟠"
                            "medium" -> "🟡"
                            "low" -> "🟢"
                            else -> "⚪"
                        }
                        val statusText = when (defect.status) {
                            "open" -> "Открыт"
                            "in_progress" -> "В работе"
                            "fixed" -> "✅ Исправлен"
                            else -> defect.status
                        }
                        val markerIcon = if (defect.markerLeft != null && defect.markerTop != null) " 📍" else ""
                        text = "$severityIcon ${defect.name} [$statusText]$markerIcon"

                        if (defect.description.isNotEmpty()) {
                            tooltip = Tooltip(defect.description)
                        } else {
                            tooltip = null
                        }
                    }
                }
            }
        }

        // ===== КОНТЕКСТНОЕ МЕНЮ =====
        val contextMenu = ContextMenu()
        val editItem = MenuItem("✏️ Редактировать")
        val deleteItem = MenuItem("🗑️ Удалить")
        val addMarkerItem = MenuItem("📌 Отметить на оборудовании")

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
                confirm.contentText = "Вы уверены, что хотите удалить '${selected.name}'?"
                val result = confirm.showAndWait()
                if (result.isPresent && result.get() == ButtonType.OK) {
                    database.deleteDefect(selected.id)
                    defects.remove(selected)
                    defectsListView.items.remove(selected)
                    updateDefectsCount()
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

        contextMenu.items.addAll(editItem, deleteItem, addMarkerItem)
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

        val severityLabel = Label("Важность:")
        severityLabel.style = "-fx-font-weight: bold; -fx-font-size: 13px;"
        val severityCombo = ComboBox<String>()
        severityCombo.items.addAll("critical", "high", "medium", "low")
        severityCombo.value = "medium"
        severityCombo.style = "-fx-pref-width: 120px; -fx-padding: 6px; -fx-font-size: 14px;"

        val severityBox = HBox(10.0, severityLabel, severityCombo)
        severityBox.alignment = Pos.CENTER_LEFT

        content.children.addAll(
            nameLabel, nameField,
            descLabel, descField,
            severityBox
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
                    severity = severityCombo.value ?: "medium"
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

        val severityCombo = ComboBox<String>()
        severityCombo.items.addAll("critical", "high", "medium", "low")
        severityCombo.value = defect.severity
        severityCombo.style = "-fx-pref-width: 120px; -fx-padding: 6px; -fx-font-size: 14px;"

        val statusCombo = ComboBox<String>()
        statusCombo.items.addAll("open", "in_progress", "fixed")
        statusCombo.value = defect.status
        statusCombo.style = "-fx-pref-width: 120px; -fx-padding: 6px; -fx-font-size: 14px;"

        content.children.addAll(
            Label("Название:"), nameField,
            Label("Описание:"), descField,
            Label("Важность:"), severityCombo,
            Label("Статус:"), statusCombo
        )

        dialog.dialogPane.content = content
        dialog.dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val result = dialog.showAndWait()
        if (result.isPresent && result.get() == ButtonType.OK) {
            val updatedDefect = defect.copy(
                name = nameField.text.trim().ifEmpty { defect.name },
                description = descField.text.trim(),
                severity = severityCombo.value ?: defect.severity,
                status = statusCombo.value ?: defect.status
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
            val alert = Alert(AlertType.INFORMATION)
            alert.title = ""
            alert.headerText = null
            alert.contentText = message
            alert.showAndWait()
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