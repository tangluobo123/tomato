package com.tangluobo.tomato.module.connect.dialog;

import com.tangluobo.tomato.module.connect.service.BackupService;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.service.DatabaseService;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.*;

public class BackupDialog {

    private Stage dialogStage;

    private final ConnectionConfig config;
    private final String databaseName;
    private final String path;

    private TextArea commentArea;
    private CheckBox lockTablesCheck;
    private CheckBox singleTransactionCheck;
    private CheckBox useCustomFilenameCheck;
    private TextField customFilenameField;
    private TextArea logArea;
    private Label objectCountLabel;
    private Label processedCountLabel;
    private Label recordCountLabel;
    private Label timeLabel;

    private TreeView<BackupObject> objectTree;

    private final List<BackupObject> selectedObjects = new ArrayList<>();
    private BackupTask currentTask;

    public BackupDialog(Stage parent, ConnectionConfig config, String databaseName, String path) {
        this.config = config;
        this.databaseName = databaseName;
        this.path = path == null ? "" : path;
        initUI(parent);
        loadObjects();
    }

    private void initUI(Stage parent) {
        dialogStage = new Stage();
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.initOwner(parent);
        dialogStage.setTitle("新建备份");
        dialogStage.setResizable(true);
        dialogStage.setMinWidth(720);
        dialogStage.setMinHeight(580);
        if (parent != null && parent.getIcons() != null && !parent.getIcons().isEmpty()) {
            dialogStage.getIcons().add(parent.getIcons().get(0));
        }

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #f4f4f4;");

        TabPane tabPane = createTabPane();
        root.setCenter(tabPane);
        BorderPane.setMargin(tabPane, new Insets(0, 0, 0, 0));

        HBox bottomBar = createBottomBar();
        root.setBottom(bottomBar);

        Scene scene = new Scene(root, 720, 580);
        dialogStage.setScene(scene);
        DialogPositionUtil.centerOnOwner(dialogStage, parent);
    }

    private TabPane createTabPane() {
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());

        Tab generalTab = new Tab("常规");
        generalTab.setContent(createGeneralTab());

        Tab objectTab = new Tab("对象选择");
        objectTab.setContent(createObjectTab());

        Tab advancedTab = new Tab("高级");
        advancedTab.setContent(createAdvancedTab());

        Tab logTab = new Tab("信息日志");
        logTab.setContent(createLogTab());

        tabPane.getTabs().addAll(generalTab, objectTab, advancedTab, logTab);
        return tabPane;
    }

    private VBox createGeneralTab() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(15));
        box.setStyle("-fx-background-color: white;");

        HBox serverRow = new HBox(10);
        serverRow.setAlignment(Pos.CENTER_LEFT);
        Label serverLabel = new Label("服务器:");
        serverLabel.setMinWidth(80);
        serverLabel.setStyle("-fx-font-weight: normal; -fx-font-size: 13px;");
        Label serverValue = new Label(config.getName());
        serverValue.setStyle("-fx-font-size: 13px;");
        serverRow.getChildren().addAll(serverLabel, serverValue);

        HBox dbRow = new HBox(10);
        dbRow.setAlignment(Pos.CENTER_LEFT);
        Label dbLabel = new Label("数据库:");
        dbLabel.setMinWidth(80);
        dbLabel.setStyle("-fx-font-weight: normal; -fx-font-size: 13px;");
        Label dbValue = new Label(databaseName);
        dbValue.setStyle("-fx-font-size: 13px;");
        dbRow.getChildren().addAll(dbLabel, dbValue);

        TitledPane fileOptionsPane = new TitledPane();
        fileOptionsPane.setText("备份文件选项");
        fileOptionsPane.setStyle(
            "-fx-border-color: #cccccc; -fx-border-radius: 2px; -fx-background-color: white;" +
            "-fx-accordion-header: #f0f0f0;"
        );
        fileOptionsPane.setExpanded(true);
        fileOptionsPane.setCollapsible(false);

        VBox fileContent = new VBox(8);
        fileContent.setPadding(new Insets(10));

        Label commentLabel = new Label("注释:");
        commentLabel.setStyle("-fx-font-size: 13px;");
        commentArea = new TextArea();
        commentArea.setPrefRowCount(5);
        commentArea.setStyle("-fx-font-size: 13px;");

        fileContent.getChildren().addAll(commentLabel, commentArea);
        fileOptionsPane.setContent(fileContent);

        Label noteLabel = new Label("注意：这将只备份表和数据。不包括查询和报表。");
        noteLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888888;");
        noteLabel.setWrapText(true);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        box.getChildren().addAll(serverRow, dbRow, fileOptionsPane, noteLabel, spacer);
        return box;
    }

    private VBox createObjectTab() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(15));
        box.setStyle("-fx-background-color: white;");

        Label topLabel = new Label("你可以选择要备份哪个数据库对象。");
        topLabel.setStyle("-fx-font-size: 13px;");

        Label objectLabel = new Label("对象:");
        objectLabel.setStyle("-fx-font-size: 13px;");

        objectTree = new TreeView<>();
        objectTree.setShowRoot(false);
        objectTree.setStyle("-fx-font-size: 12px;");
        objectTree.setFixedCellSize(26);
        objectTree.setCellFactory(tv -> new TreeCell<>() {
            private final CheckBox checkBox = new CheckBox();
            private final HBox hbox = new HBox(2);
            private final ImageView iconView = new ImageView();
            private BackupObject currentItem;
            private javafx.beans.value.ChangeListener<Boolean> selectedListener;
            private javafx.beans.value.ChangeListener<Boolean> indeterminateListener;
            private javafx.beans.value.ChangeListener<String> labelListener;

            {
                iconView.setFitWidth(20);
                iconView.setFitHeight(20);
                hbox.setAlignment(Pos.CENTER_LEFT);
                hbox.getChildren().addAll(checkBox, iconView);
                // 点击复选框 → 写回模型
                checkBox.setOnAction(e -> {
                    if (currentItem != null) {
                        currentItem.setIndeterminate(false);
                        currentItem.setSelected(checkBox.isSelected());
                    }
                });
            }

            @Override
            public void updateItem(BackupObject item, boolean empty) {
                // 先清理旧监听
                if (selectedListener != null && currentItem != null) {
                    currentItem.selectedProperty().removeListener(selectedListener);
                    selectedListener = null;
                }
                if (indeterminateListener != null && currentItem != null) {
                    currentItem.indeterminateProperty().removeListener(indeterminateListener);
                    indeterminateListener = null;
                }
                if (labelListener != null && currentItem != null) {
                    currentItem.customLabelProperty().removeListener(labelListener);
                    labelListener = null;
                }
                currentItem = null;

                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    // 文本
                    if (item.getSpecialType() == BackupObject.SpecialType.CUSTOMIZE) {
                        String label = item.getCustomLabel();
                        setText(item.getDisplayName() + (label != null ? label : ""));
                    } else {
                        setText(item.getDisplayName());
                    }

                    // 图标
                    iconView.setImage(createIcon(item.getType()).getImage());

                    if (item.isGroupNode()) {
                        setGraphic(createIcon(item.getType()));
                    } else {
                        currentItem = item;
                        checkBox.setSelected(item.isSelected());
                        checkBox.setIndeterminate(item.isIndeterminate());

                        // 模型→UI：选中状态
                        selectedListener = (obs, old, val) -> checkBox.setSelected(val);
                        item.selectedProperty().addListener(selectedListener);

                        // 模型→UI：半选状态
                        indeterminateListener = (obs, old, val) -> checkBox.setIndeterminate(val);
                        item.indeterminateProperty().addListener(indeterminateListener);

                        setGraphic(hbox);
                    }

                    // "自定义"计数监听
                    if (item.getSpecialType() == BackupObject.SpecialType.CUSTOMIZE) {
                        labelListener = (obs, oldVal, newVal) -> {
                            String lbl = item.getCustomLabel();
                            setText(item.getDisplayName() + (lbl != null ? lbl : ""));
                        };
                        item.customLabelProperty().addListener(labelListener);
                    }
                }
            }
        });

        VBox.setVgrow(objectTree, Priority.ALWAYS);
        objectTree.setPrefHeight(250);

        HBox buttonRow = new HBox(10);
        buttonRow.setAlignment(Pos.CENTER);
        buttonRow.setPadding(new Insets(10, 0, 0, 0));

        Button selectAllBtn = new Button("全选");
        selectAllBtn.setPrefWidth(100);
        selectAllBtn.setStyle(
            "-fx-background-color: #f8f8f8; -fx-border-color: #cccccc; -fx-border-radius: 0;" +
            "-fx-background-radius: 0; -fx-font-size: 13px;"
        );
        selectAllBtn.setOnAction(e -> selectAllObjects(true));

        Button deselectAllBtn = new Button("取消全选");
        deselectAllBtn.setPrefWidth(100);
        deselectAllBtn.setStyle(
            "-fx-background-color: #f8f8f8; -fx-border-color: #cccccc; -fx-border-radius: 0;" +
            "-fx-background-radius: 0; -fx-font-size: 13px;"
        );
        deselectAllBtn.setOnAction(e -> selectAllObjects(false));

        buttonRow.getChildren().addAll(selectAllBtn, deselectAllBtn);

        box.getChildren().addAll(topLabel, objectLabel, objectTree, buttonRow);
        return box;
    }

    private VBox createAdvancedTab() {
        VBox box = new VBox(15);
        box.setPadding(new Insets(15));
        box.setStyle("-fx-background-color: white;");

        lockTablesCheck = new CheckBox("锁定全部表");
        lockTablesCheck.setStyle("-fx-font-size: 13px;");

        singleTransactionCheck = new CheckBox("使用单一事务（只限 InnoDB）");
        singleTransactionCheck.setStyle("-fx-font-size: 13px;");

        useCustomFilenameCheck = new CheckBox("使用指定文件名");
        useCustomFilenameCheck.setStyle("-fx-font-size: 13px;");

        customFilenameField = new TextField();
        customFilenameField.setStyle("-fx-font-size: 13px; -fx-border-radius: 0;");
        customFilenameField.setDisable(true);

        useCustomFilenameCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            customFilenameField.setDisable(!newVal);
        });

        box.getChildren().addAll(lockTablesCheck, singleTransactionCheck, useCustomFilenameCheck, customFilenameField);
        return box;
    }

    private VBox createLogTab() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(15));
        box.setStyle("-fx-background-color: white;");

        GridPane infoGrid = new GridPane();
        infoGrid.setHgap(10);
        infoGrid.setVgap(5);
        infoGrid.setStyle("-fx-font-size: 13px;");

        Label objLabel = new Label("对象:");
        objLabel.setMinWidth(80);
        objectCountLabel = new Label("-");
        Label procLabel = new Label("已处理对象:");
        procLabel.setMinWidth(80);
        processedCountLabel = new Label("-");
        Label recLabel = new Label("已处理记录:");
        recLabel.setMinWidth(80);
        recordCountLabel = new Label("-");
        Label timeLabelPrefix = new Label("时间:");
        timeLabelPrefix.setMinWidth(80);
        timeLabel = new Label("-");

        infoGrid.add(objLabel, 0, 0);
        infoGrid.add(objectCountLabel, 1, 0);
        infoGrid.add(procLabel, 0, 1);
        infoGrid.add(processedCountLabel, 1, 1);
        infoGrid.add(recLabel, 0, 2);
        infoGrid.add(recordCountLabel, 1, 2);
        infoGrid.add(timeLabelPrefix, 0, 3);
        infoGrid.add(timeLabel, 1, 3);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");
        VBox.setVgrow(logArea, Priority.ALWAYS);

        box.getChildren().addAll(infoGrid, logArea);
        return box;
    }

    private HBox createBottomBar() {
        HBox bar = new HBox(10);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(10));
        bar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #dddddd; -fx-border-width: 1 0 0 0;");

        MenuButton saveMenuBtn = new MenuButton("保存");
        saveMenuBtn.setStyle(
            "-fx-background-color: #f8f8f8; -fx-border-color: #cccccc; -fx-border-radius: 0;" +
            "-fx-background-radius: 0; -fx-font-size: 13px; -fx-cursor: hand;"
        );

        MenuItem saveAsTemplate = new MenuItem("保存为模板");
        saveAsTemplate.setOnAction(e -> showSaveAsTemplate());
        MenuItem loadTemplate = new MenuItem("加载模板");
        loadTemplate.setOnAction(e -> showLoadTemplate());
        MenuItem clearTemplate = new MenuItem("清除模板");
        clearTemplate.setOnAction(e -> clearTemplate());

        saveMenuBtn.getItems().addAll(saveAsTemplate, loadTemplate, clearTemplate);

        Button backupBtn = new Button("备份");
        backupBtn.setStyle(
            "-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-color: #06ad56;" +
            "-fx-border-radius: 0; -fx-background-radius: 0; -fx-font-size: 13px; -fx-cursor: hand;"
        );
        backupBtn.setPrefWidth(80);
        backupBtn.setOnAction(e -> startBackup(backupBtn));

        Button closeBtn = new Button("关闭");
        closeBtn.setStyle(
            "-fx-background-color: #f8f8f8; -fx-border-color: #cccccc; -fx-border-radius: 0;" +
            "-fx-background-radius: 0; -fx-font-size: 13px; -fx-cursor: hand;"
        );
        closeBtn.setPrefWidth(80);
        closeBtn.setOnAction(e -> {
            if (currentTask != null && !currentTask.isDone()) {
                currentTask.cancel(true);
            }
            dialogStage.close();
        });

        bar.getChildren().addAll(saveMenuBtn, backupBtn, closeBtn);
        return bar;
    }

    private void loadObjects() {
        new Thread(() -> {
            try {
                List<BackupObject> tables = loadObjectList(BackupObject.Type.TABLE);
                List<BackupObject> views = loadObjectList(BackupObject.Type.VIEW);
                List<BackupObject> functions = loadObjectList(BackupObject.Type.FUNCTION);
                List<BackupObject> events = loadObjectList(BackupObject.Type.EVENT);

                Platform.runLater(() -> {
                    buildObjectTree(tables, views, functions, events);
                    int totalCount = tables.size() + views.size() + functions.size() + events.size();
                    objectCountLabel.setText(String.valueOf(totalCount));
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    logArea.appendText("加载对象列表失败: " + e.getMessage() + "\n");
                });
            }
        }, "Backup-LoadObjects").start();
    }

    private List<BackupObject> loadObjectList(BackupObject.Type type) throws Exception {
        List<BackupObject> result = new ArrayList<>();
        List<String> names = switch (type) {
            case TABLE -> DatabaseService.getTables(config, databaseName);
            case VIEW -> DatabaseService.getViews(config, databaseName);
            case FUNCTION -> DatabaseService.getFunctions(config, databaseName);
            case EVENT -> DatabaseService.getEvents(config, databaseName);
        };
        for (String name : names) {
            result.add(new BackupObject(type, name));
        }
        return result;
    }

    private void buildObjectTree(List<BackupObject> tables, List<BackupObject> views,
                                  List<BackupObject> functions, List<BackupObject> events) {
        TreeItem<BackupObject> root = new TreeItem<>(null);

        root.getChildren().add(createTypeGroup(BackupObject.Type.TABLE, tables));
        root.getChildren().add(createTypeGroup(BackupObject.Type.VIEW, views));
        root.getChildren().add(createTypeGroup(BackupObject.Type.FUNCTION, functions));
        root.getChildren().add(createTypeGroup(BackupObject.Type.EVENT, events));

        objectTree.setRoot(root);
        root.setExpanded(true);
    }

    private TreeItem<BackupObject> createTypeGroup(BackupObject.Type type, List<BackupObject> objects) {
        String typeDisplayName = type.getDisplayName();

        // --- 创建节点 ---
        // 大类节点（无复选框）
        BackupObject groupObj = new BackupObject(type, typeDisplayName, BackupObject.SpecialType.NONE, true);
        TreeItem<BackupObject> groupItem = new TreeItem<>(groupObj);
        groupItem.setExpanded(true);

        // "运行期间的全部" 节点
        BackupObject selectAllObj = new BackupObject(type, "运行期间的全部" + typeDisplayName + "(*)", BackupObject.SpecialType.SELECT_ALL);
        TreeItem<BackupObject> selectAllItem = new TreeItem<>(selectAllObj);

        // "自定义" 节点
        BackupObject customizeObj = new BackupObject(type, "自定义", BackupObject.SpecialType.CUSTOMIZE);
        TreeItem<BackupObject> customizeItem = new TreeItem<>(customizeObj);
        customizeItem.setExpanded(true);

        // 初始化计数
        customizeObj.setCustomLabel(objects.isEmpty() ? " (0/0)" : " (0/" + objects.size() + ")");

        // 子对象加入"自定义"下
        for (BackupObject obj : objects) {
            customizeItem.getChildren().add(new TreeItem<>(obj));
        }

        final boolean[] suppress = {false};

        // --- 规则1: 选中"运行期间的全部" → 取消"自定义" + 取消所有子对象 ---
        selectAllObj.selectedProperty().addListener((obs, old, val) -> {
            if (suppress[0]) return;
            if (val) {
                suppress[0] = true;
                customizeObj.setSelected(false);
                customizeObj.setIndeterminate(false);
                for (TreeItem<BackupObject> c : customizeItem.getChildren()) c.getValue().setSelected(false);
                suppress[0] = false;
                refreshCustomizeCount(customizeItem);
            }
        });

        // --- 规则2: 选中"自定义" → 取消"运行期间的全部"（互斥）+ 全选子对象 ---
        //           取消"自定义" → 取消所有子对象 ---
        customizeObj.selectedProperty().addListener((obs, old, val) -> {
            if (suppress[0]) return;
            suppress[0] = true;
            customizeObj.setIndeterminate(false);
            if (val) {
                selectAllObj.setSelected(false);
                for (TreeItem<BackupObject> c : customizeItem.getChildren()) c.getValue().setSelected(true);
            } else {
                for (TreeItem<BackupObject> c : customizeItem.getChildren()) c.getValue().setSelected(false);
            }
            suppress[0] = false;
            refreshCustomizeCount(customizeItem);
        });

        // --- 规则3: 手动勾/取消子对象 → 仅更新计数 ---
        for (TreeItem<BackupObject> child : customizeItem.getChildren()) {
            child.getValue().selectedProperty().addListener((obs, old, val) -> {
                if (suppress[0]) return;
                refreshCustomizeCount(customizeItem);
            });
        }

        groupItem.getChildren().addAll(selectAllItem, customizeItem);
        return groupItem;
    }

    /** 更新"自定义"节点上的计数和半选状态 */
    private void refreshCustomizeCount(TreeItem<BackupObject> customizeItem) {
        int total = customizeItem.getChildren().size();
        int selected = 0;
        for (TreeItem<BackupObject> c : customizeItem.getChildren()) {
            if (c.getValue().isSelected()) selected++;
        }
        BackupObject customizeObj = customizeItem.getValue();
        customizeObj.setCustomLabel(" (" + selected + "/" + total + ")");
        // 半选：部分选中但未全选
        customizeObj.setIndeterminate(selected > 0 && selected < total);
        // 全选时设为选中态
        if (selected == total && total > 0) {
            customizeObj.setSelected(true);
            customizeObj.setIndeterminate(false);
        }
    }

    private ImageView createIcon(BackupObject.Type type) {
        String resourcePath = switch (type) {
            case TABLE -> "/images/connect/table.png";
            case VIEW -> "/images/connect/view.png";
            case FUNCTION -> "/images/connect/function.png";
            case EVENT -> "/images/connect/event.png";
        };
        try {
            Image img = new Image(getClass().getResourceAsStream(resourcePath));
            ImageView iv = new ImageView(img);
            iv.setFitWidth(20);
            iv.setFitHeight(20);
            return iv;
        } catch (Exception e) {
            return new ImageView();
        }
    }

    private void selectAllObjects(boolean select) {
        TreeItem<BackupObject> root = objectTree.getRoot();
        if (root == null) return;
        for (TreeItem<BackupObject> group : root.getChildren()) {
            if (group.getValue() == null) continue;
            for (TreeItem<BackupObject> sub : group.getChildren()) {
                BackupObject subObj = sub.getValue();
                if (subObj.getSpecialType() == BackupObject.SpecialType.SELECT_ALL) {
                    subObj.setSelected(select);
                } else if (subObj.getSpecialType() == BackupObject.SpecialType.CUSTOMIZE) {
                    if (!select) {
                        subObj.setSelected(false);
                    }
                }
            }
        }
    }

    private void showSaveAsTemplate() {
        String templateName = "backup_template_" + System.currentTimeMillis();
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("保存模板");
        alert.setHeaderText(null);
        alert.setContentText("模板已保存");
        DialogPositionUtil.centerOnOwner(alert, dialogStage);
        alert.showAndWait();
    }

    private void showLoadTemplate() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("加载模板");
        alert.setHeaderText(null);
        alert.setContentText("暂无已保存的模板");
        DialogPositionUtil.centerOnOwner(alert, dialogStage);
        alert.showAndWait();
    }

    private void clearTemplate() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("清除模板");
        alert.setHeaderText(null);
        alert.setContentText("模板已清除");
        DialogPositionUtil.centerOnOwner(alert, dialogStage);
        alert.showAndWait();
    }

    private void startBackup(Button backupBtn) {
        List<BackupObject> selected = getSelectedBackupObjects();
        if (selected.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("备份");
            alert.setHeaderText(null);
            alert.setContentText("请至少选择一个备份对象");
            DialogPositionUtil.centerOnOwner(alert, dialogStage);
        alert.showAndWait();
            return;
        }

        backupBtn.setDisable(true);
        logArea.clear();
        processedCountLabel.setText("0");
        recordCountLabel.setText("0");

        String comment = commentArea.getText();
        boolean lockTables = lockTablesCheck.isSelected();
        boolean singleTx = singleTransactionCheck.isSelected();
        String customFile = useCustomFilenameCheck.isSelected() ? customFilenameField.getText() : null;

        currentTask = new BackupTask(config, databaseName, selected, comment, lockTables, singleTx, customFile, path);

        currentTask.messageProperty().addListener((obs, oldMsg, newMsg) -> {
            Platform.runLater(() -> logArea.appendText(newMsg + "\n"));
        });

        currentTask.progressProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> processedCountLabel.setText(String.valueOf(newVal.intValue())));
        });

        currentTask.recordCountProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> recordCountLabel.setText(String.valueOf(newVal.longValue())));
        });

        currentTask.runningTimeProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> timeLabel.setText(newVal));
        });

        currentTask.setOnSucceeded(e -> {
            backupBtn.setDisable(false);
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("备份完成");
                alert.setHeaderText(null);
                String result = currentTask.getValue();
                alert.setContentText("备份文件已保存:\n" + result);
                DialogPositionUtil.centerOnOwner(alert, dialogStage);
        alert.showAndWait();
                dialogStage.close();
            });
        });

        currentTask.setOnFailed(e -> {
            backupBtn.setDisable(false);
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("备份失败");
                alert.setHeaderText(null);
                alert.setContentText("错误: " + currentTask.getException().getMessage());
                DialogPositionUtil.centerOnOwner(alert, dialogStage);
        alert.showAndWait();
            });
        });

        new Thread(currentTask, "Backup-Task").start();
    }

    private List<BackupObject> getSelectedBackupObjects() {
        List<BackupObject> result = new ArrayList<>();
        TreeItem<BackupObject> root = objectTree.getRoot();
        if (root == null) return result;

        for (TreeItem<BackupObject> group : root.getChildren()) {
            if (group.getValue() == null) continue;
            boolean groupSelectAll = false;
            boolean groupCustomizeAny = false;

            for (TreeItem<BackupObject> sub : group.getChildren()) {
                BackupObject subObj = sub.getValue();
                if (subObj.getSpecialType() == BackupObject.SpecialType.SELECT_ALL && subObj.isSelected()) {
                    groupSelectAll = true;
                }
                if (subObj.getSpecialType() == BackupObject.SpecialType.CUSTOMIZE && subObj.isSelected()) {
                    groupCustomizeAny = true;
                }
            }

            for (TreeItem<BackupObject> sub : group.getChildren()) {
                if (sub.getValue().getSpecialType() != BackupObject.SpecialType.CUSTOMIZE) continue;
                for (TreeItem<BackupObject> child : sub.getChildren()) {
                    BackupObject childObj = child.getValue();
                    if (groupSelectAll || groupCustomizeAny) {
                        if (groupSelectAll) {
                            childObj.setSelected(true);
                        }
                        if (childObj.isSelected()) {
                            result.add(childObj);
                        }
                    }
                }
            }
        }
        return result;
    }

    public void showAndWait() {
        dialogStage.showAndWait();
    }

    public static class BackupObject {
        public enum Type {
            TABLE("表"),
            VIEW("视图"),
            FUNCTION("函数"),
            EVENT("事件");

            private final String displayName;

            Type(String displayName) {
                this.displayName = displayName;
            }

            public String getDisplayName() {
                return displayName;
            }
        }

        public enum SpecialType {
            NONE,
            SELECT_ALL,
            CUSTOMIZE
        }

        private final Type type;
        private final String name;
        private String displayName;
        private final SimpleBooleanProperty selected = new SimpleBooleanProperty(false);
        private final SimpleBooleanProperty indeterminate = new SimpleBooleanProperty(false);
        private final javafx.beans.property.SimpleStringProperty customLabel = new javafx.beans.property.SimpleStringProperty();
        private final SpecialType specialType;
        private final boolean groupNode;

        public BackupObject(Type type, String name) {
            this(type, name, SpecialType.NONE, false);
        }

        public BackupObject(Type type, String name, SpecialType specialType) {
            this(type, name, specialType, false);
        }

        public BackupObject(Type type, String name, SpecialType specialType, boolean groupNode) {
            this.type = type;
            this.name = name;
            this.displayName = name;
            this.specialType = specialType;
            this.groupNode = groupNode;
        }

        public Type getType() { return type; }
        public String getName() { return name; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getCustomLabel() { return customLabel.get(); }
        public void setCustomLabel(String customLabel) { this.customLabel.set(customLabel); }
        public javafx.beans.property.SimpleStringProperty customLabelProperty() { return customLabel; }
        public SpecialType getSpecialType() { return specialType; }
        public boolean isSpecial() { return specialType != SpecialType.NONE; }
        public boolean isGroupNode() { return groupNode; }
        public SimpleBooleanProperty selectedProperty() { return selected; }
        public boolean isSelected() { return selected.get(); }
        public void setSelected(boolean selected) { this.selected.set(selected); }
        public SimpleBooleanProperty indeterminateProperty() { return indeterminate; }
        public boolean isIndeterminate() { return indeterminate.get(); }
        public void setIndeterminate(boolean indeterminate) { this.indeterminate.set(indeterminate); }
    }

    public static class BackupTask extends javafx.concurrent.Task<String> {

        private final ConnectionConfig config;
        private final String databaseName;
        private final List<BackupObject> objects;
        private final String comment;
        private final boolean lockTables;
        private final boolean singleTransaction;
        private final String customFilename;
        private final String path;

        private int recordCount = 0;
        private long startTime;

        private final javafx.beans.property.SimpleLongProperty recordCountProp = new javafx.beans.property.SimpleLongProperty();
        private final javafx.beans.property.SimpleStringProperty runningTime = new javafx.beans.property.SimpleStringProperty();

        public BackupTask(ConnectionConfig config, String databaseName, List<BackupObject> objects,
                          String comment, boolean lockTables, boolean singleTransaction, String customFilename, String path) {
            this.config = config;
            this.databaseName = databaseName;
            this.objects = objects;
            this.comment = comment;
            this.lockTables = lockTables;
            this.singleTransaction = singleTransaction;
            this.customFilename = customFilename;
            this.path = path == null ? "" : path;
        }

        public javafx.beans.property.SimpleLongProperty recordCountProperty() { return recordCountProp; }
        public javafx.beans.property.SimpleStringProperty runningTimeProperty() { return runningTime; }

        @Override
        protected String call() throws Exception {
            startTime = System.currentTimeMillis();
            updateMessage("开始备份 " + databaseName);

            String filename = customFilename;
            if (filename == null || filename.isEmpty()) {
                filename = new java.text.SimpleDateFormat("yyyyMMddHHmmss").format(new java.util.Date());
            }
            filename = filename.replaceAll("[\\\\/:*?\"<>|]", "_") + ".nb3";

            String result = BackupService.createBackup(config, databaseName, objects, comment,
                    lockTables, singleTransaction, filename, path, this);

            long elapsed = System.currentTimeMillis() - startTime;
            Platform.runLater(() -> runningTime.set(String.format("%d.%d 秒", elapsed / 1000, (elapsed % 1000) / 100)));
            updateMessage("备份完成: " + result);

            return result;
        }

        public void incrementRecordCount(long count) {
            recordCount += count;
            Platform.runLater(() -> recordCountProp.set(recordCount));
        }

        public void log(String msg) {
            updateMessage(msg);
        }

        public void updateProgress(int done) {
            updateProgress(done, objects.size());
        }
    }
}