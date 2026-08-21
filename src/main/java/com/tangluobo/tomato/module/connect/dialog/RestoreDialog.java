package com.tangluobo.tomato.module.connect.dialog;

import com.tangluobo.tomato.module.connect.service.BackupService;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

public class RestoreDialog {

    private Stage dialogStage;

    private final ConnectionConfig config;
    private final String databaseName;
    private final String backupName;
    private final String path;

    private Label serverValue;
    private Label dbValue;
    private Label backupValue;
    private TextArea logArea;
    private Label objectCountLabel;
    private Label processedCountLabel;
    private Label recordCountLabel;
    private Label timeLabel;
    private ProgressBar progressBar;

    private BackupService.RestoreTask currentTask;

    public RestoreDialog(Stage parent, ConnectionConfig config, String databaseName, String backupName, String path) {
        this.config = config;
        this.databaseName = databaseName;
        this.backupName = backupName;
        this.path = path == null ? "" : path;
        initUI(parent);
    }

    private void initUI(Stage parent) {
        dialogStage = new Stage();
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.initOwner(parent);
        dialogStage.setTitle("还原备份");
        dialogStage.setResizable(true);
        dialogStage.setMinWidth(620);
        dialogStage.setMinHeight(480);
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

        Scene scene = new Scene(root, 620, 480);
        dialogStage.setScene(scene);
        DialogPositionUtil.centerOnOwner(dialogStage, parent);
    }

    private TabPane createTabPane() {
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());

        Tab generalTab = new Tab("常规");
        generalTab.setContent(createGeneralTab());

        Tab logTab = new Tab("信息日志");
        logTab.setContent(createLogTab());

        tabPane.getTabs().addAll(generalTab, logTab);
        return tabPane;
    }

    private VBox createGeneralTab() {
        VBox box = new VBox(12);
        box.setPadding(new Insets(15));
        box.setStyle("-fx-background-color: white;");

        GridPane infoGrid = new GridPane();
        infoGrid.setHgap(10);
        infoGrid.setVgap(8);
        infoGrid.setStyle("-fx-font-size: 13px;");

        Label serverLabel = new Label("服务器:");
        serverLabel.setMinWidth(80);
        serverLabel.setStyle("-fx-font-weight: normal;");
        serverValue = new Label(config.getName());
        serverValue.setStyle("-fx-font-size: 13px;");

        Label dbLabel = new Label("数据库:");
        dbLabel.setMinWidth(80);
        dbLabel.setStyle("-fx-font-weight: normal;");
        dbValue = new Label(databaseName);
        dbValue.setStyle("-fx-font-size: 13px;");

        Label backupLabel = new Label("备份文件:");
        backupLabel.setMinWidth(80);
        backupLabel.setStyle("-fx-font-weight: normal;");
        backupValue = new Label(backupName);
        backupValue.setStyle("-fx-font-size: 13px;");

        infoGrid.add(serverLabel, 0, 0);
        infoGrid.add(serverValue, 1, 0);
        infoGrid.add(dbLabel, 0, 1);
        infoGrid.add(dbValue, 1, 1);
        infoGrid.add(backupLabel, 0, 2);
        infoGrid.add(backupValue, 1, 2);

        Separator sep = new Separator();
        sep.setPadding(new Insets(8, 0, 8, 0));

        // 进度区域
        Label progressLabel = new Label("进度:");
        progressLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");

        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(400);
        progressBar.setStyle("-fx-accent: #07c160;");

        GridPane progressGrid = new GridPane();
        progressGrid.setHgap(10);
        progressGrid.setVgap(5);
        progressGrid.setStyle("-fx-font-size: 13px;");

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

        progressGrid.add(objLabel, 0, 0);
        progressGrid.add(objectCountLabel, 1, 0);
        progressGrid.add(procLabel, 0, 1);
        progressGrid.add(processedCountLabel, 1, 1);
        progressGrid.add(recLabel, 0, 2);
        progressGrid.add(recordCountLabel, 1, 2);
        progressGrid.add(timeLabelPrefix, 0, 3);
        progressGrid.add(timeLabel, 1, 3);

        Label warningLabel = new Label("注意：还原操作将覆盖目标数据库中已存在的同名表/视图/函数/事件。");
        warningLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #cc0000;");
        warningLabel.setWrapText(true);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        box.getChildren().addAll(infoGrid, sep, progressLabel, progressBar, progressGrid, warningLabel, spacer);
        return box;
    }

    private VBox createLogTab() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(15));
        box.setStyle("-fx-background-color: white;");

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");
        VBox.setVgrow(logArea, Priority.ALWAYS);

        box.getChildren().add(logArea);
        return box;
    }

    private HBox createBottomBar() {
        HBox bar = new HBox(10);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(10));
        bar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #dddddd; -fx-border-width: 1 0 0 0;");

        Button restoreBtn = new Button("开始还原");
        restoreBtn.setStyle(
            "-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-color: #06ad56;" +
            "-fx-border-radius: 0; -fx-background-radius: 0; -fx-font-size: 13px; -fx-cursor: hand;"
        );
        restoreBtn.setPrefWidth(100);
        restoreBtn.setOnAction(e -> startRestore(restoreBtn));

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

        bar.getChildren().addAll(restoreBtn, closeBtn);
        return bar;
    }

    private void startRestore(Button restoreBtn) {
        // 确认还原操作
        Alert confirm = new Alert(Alert.AlertType.WARNING);
        confirm.setTitle("确认还原");
        confirm.setHeaderText("确定要还原备份 \"" + backupName + "\" 吗？");
        confirm.setContentText("此操作将覆盖数据库 \"" + databaseName + "\" 中已存在的同名对象！");
        confirm.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
        DialogPositionUtil.centerOnOwner(confirm, dialogStage);
        confirm.showAndWait().ifPresent(response -> {
            if (response != ButtonType.YES) return;

            restoreBtn.setDisable(true);
            logArea.clear();
            processedCountLabel.setText("0");
            recordCountLabel.setText("0");
            progressBar.setProgress(0);

            currentTask = new BackupService.RestoreTask(config, databaseName, backupName, path);

            currentTask.messageProperty().addListener((obs, oldMsg, newMsg) -> {
                Platform.runLater(() -> logArea.appendText(newMsg + "\n"));
            });

            currentTask.progressProperty().addListener((obs, oldVal, newVal) -> {
                Platform.runLater(() -> {
                    processedCountLabel.setText(String.valueOf(newVal.intValue()));
                    progressBar.setProgress(newVal.doubleValue());
                });
            });

            currentTask.recordCountProperty().addListener((obs, oldVal, newVal) -> {
                Platform.runLater(() -> recordCountLabel.setText(String.valueOf(newVal.longValue())));
            });

            currentTask.runningTimeProperty().addListener((obs, oldVal, newVal) -> {
                Platform.runLater(() -> timeLabel.setText(newVal));
            });

            currentTask.setOnSucceeded(e -> {
                restoreBtn.setDisable(false);
                progressBar.setProgress(1.0);
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("还原完成");
                    alert.setHeaderText(null);
                    alert.setContentText("备份 \"" + backupName + "\" 已成功还原到数据库 \"" + databaseName + "\"");
                    DialogPositionUtil.centerOnOwner(alert, dialogStage);
                    alert.showAndWait();
                    dialogStage.close();
                });
            });

            currentTask.setOnFailed(e -> {
                restoreBtn.setDisable(false);
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("还原失败");
                    alert.setHeaderText(null);
                    alert.setContentText("错误: " + currentTask.getException().getMessage());
                    DialogPositionUtil.centerOnOwner(alert, dialogStage);
                    alert.showAndWait();
                });
            });

            currentTask.setOnCancelled(e -> {
                restoreBtn.setDisable(false);
                Platform.runLater(() -> {
                    logArea.appendText("还原已被取消\n");
                });
            });

            new Thread(currentTask, "Restore-Task").start();
        });
    }

    public void showAndWait() {
        dialogStage.showAndWait();
    }
}
