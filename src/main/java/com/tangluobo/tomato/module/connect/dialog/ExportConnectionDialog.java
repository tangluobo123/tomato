package com.tangluobo.tomato.module.connect.dialog;

import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTreeCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.*;

public class ExportConnectionDialog {

    private Stage dialogStage;
    private CheckBoxTreeItem<String> rootItem;
    private CheckBox includePasswordCheck;
    private boolean confirmed = false;

    private final List<ConnectionConfig> connections;
    private final Map<CheckBoxTreeItem<String>, ConnectionConfig> itemConfigMap = new HashMap<>();

    public ExportConnectionDialog(Stage parent, List<ConnectionConfig> connections) {
        this.connections = connections;
        initUI(parent);
        buildTree();
    }

    private void initUI(Stage parent) {
        dialogStage = new Stage();
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.initOwner(parent);
        dialogStage.setTitle("导出连接");
        dialogStage.setResizable(true);
        dialogStage.setMinWidth(500);
        dialogStage.setMinHeight(400);
        if (parent != null && parent.getIcons() != null && !parent.getIcons().isEmpty()) {
            dialogStage.getIcons().add(parent.getIcons().get(0));
        }

        VBox root = new VBox(10);
        root.setPadding(new Insets(15));
        root.setStyle("-fx-background-color: #f4f4f4;");

        Label titleLabel = new Label("选择要导出的连接：");
        titleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        TreeView<String> tree = new TreeView<>();
        tree.setShowRoot(false);
        tree.setStyle("-fx-background-color: white; -fx-border-color: #d9d9d9; -fx-border-radius: 2px; -fx-background-radius: 2px;");
        tree.setFixedCellSize(30);

        tree.setCellFactory(CheckBoxTreeCell.forTreeView());

        VBox.setVgrow(tree, Priority.ALWAYS);

        rootItem = new CheckBoxTreeItem<>(null);
        rootItem.setExpanded(true);
        tree.setRoot(rootItem);

        HBox buttonRow = new HBox(10);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        Button selectAllBtn = new Button("全选");
        selectAllBtn.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #cccccc; -fx-font-size: 13px;");
        selectAllBtn.setOnAction(e -> setAllSelected(true));

        Button deselectAllBtn = new Button("取消全选");
        deselectAllBtn.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #cccccc; -fx-font-size: 13px;");
        deselectAllBtn.setOnAction(e -> setAllSelected(false));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        includePasswordCheck = new CheckBox("包含密码");
        includePasswordCheck.setStyle("-fx-font-size: 13px;");
        includePasswordCheck.setSelected(false);

        buttonRow.getChildren().addAll(selectAllBtn, deselectAllBtn, spacer, includePasswordCheck);

        HBox bottomBar = new HBox(10);
        bottomBar.setAlignment(Pos.CENTER_RIGHT);
        bottomBar.setPadding(new Insets(5, 0, 0, 0));

        Button cancelBtn = new Button("取消");
        cancelBtn.setPrefWidth(80);
        cancelBtn.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #cccccc; -fx-font-size: 13px;");
        cancelBtn.setOnAction(e -> dialogStage.close());

        Button exportBtn = new Button("导出");
        exportBtn.setPrefWidth(80);
        exportBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-font-size: 13px;");
        exportBtn.setOnAction(e -> {
            if (getSelectedConfigs().isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("提示");
                alert.setHeaderText(null);
                alert.setContentText("请至少选择一个连接");
                alert.showAndWait();
                return;
            }
            confirmed = true;
            dialogStage.close();
        });

        bottomBar.getChildren().addAll(cancelBtn, exportBtn);

        root.getChildren().addAll(titleLabel, tree, buttonRow, bottomBar);

        Scene scene = new Scene(root, 500, 450);
        dialogStage.setScene(scene);
        DialogPositionUtil.centerOnOwner(dialogStage, parent);
    }

    private void buildTree() {
        Map<String, CheckBoxTreeItem<String>> idToItem = new HashMap<>();

        for (ConnectionConfig config : connections) {
            CheckBoxTreeItem<String> item = new CheckBoxTreeItem<>(config.getName());
            item.setGraphic(getIconForConfig(config));
            idToItem.put(config.getId(), item);
            itemConfigMap.put(item, config);
        }

        for (ConnectionConfig config : connections) {
            CheckBoxTreeItem<String> item = idToItem.get(config.getId());
            if (config.getParentId() == null || config.getParentId().isEmpty()) {
                rootItem.getChildren().add(item);
            } else {
                CheckBoxTreeItem<String> parent = idToItem.get(config.getParentId());
                if (parent != null) {
                    parent.getChildren().add(item);
                } else {
                    rootItem.getChildren().add(item);
                }
            }
        }

        for (CheckBoxTreeItem<String> item : idToItem.values()) {
            if (!item.getChildren().isEmpty()) {
                item.setExpanded(true);
            }
        }
    }

    private ImageView getIconForConfig(ConnectionConfig config) {
        ImageView iv = new ImageView();
        iv.setFitWidth(16);
        iv.setFitHeight(16);
        if (config.getType() == null) {
            try {
                iv.setImage(new Image(getClass().getResourceAsStream("/images/connect/folder.png")));
            } catch (Exception e) {
            }
        } else {
            try {
                iv.setImage(new Image(getClass().getResourceAsStream(config.getType().getIconPath())));
            } catch (Exception e) {
            }
        }
        return iv;
    }

    private void setAllSelected(boolean selected) {
        for (TreeItem<String> child : rootItem.getChildren()) {
            if (child instanceof CheckBoxTreeItem<?> cbChild) {
                cbChild.setSelected(selected);
            }
        }
    }

    /**
     * 收集选中的连接配置（包括选中状态和半选状态的文件夹，以保留目录结构）
     */
    public List<ConnectionConfig> getSelectedConfigs() {
        List<ConnectionConfig> result = new ArrayList<>();
        collectSelected(rootItem, result);
        return result;
    }

    private void collectSelected(TreeItem<String> item, List<ConnectionConfig> result) {
        if (item instanceof CheckBoxTreeItem<?> cbItem && item != rootItem) {
            @SuppressWarnings("unchecked")
            CheckBoxTreeItem<String> typed = (CheckBoxTreeItem<String>) item;
            ConnectionConfig config = itemConfigMap.get(typed);
            if (config != null && (cbItem.isSelected() || cbItem.isIndeterminate())) {
                result.add(config);
            }
        }
        for (TreeItem<String> child : item.getChildren()) {
            collectSelected(child, result);
        }
    }

    public boolean includePasswords() {
        return includePasswordCheck.isSelected();
    }

    public boolean showAndWait() {
        dialogStage.showAndWait();
        return confirmed;
    }
}
