package com.tangluobo.tomato.module.connect.view;

import com.tangluobo.tomato.utils.DialogPositionUtil;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.service.KafkaService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.util.StringConverter;

import com.tangluobo.tomato.module.tools.JsonFoldableTextView;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Kafka 数据展示视图，参考 RocketmqDataView 结构。
 * 主题 Tab：主题列表 / 队列偏移信息；消息 Tab：按时间 / 按Offset 查询 + 消费状态。
 */
public class KafkaDataView extends VBox {
    private final ConnectionConfig config;
    private final TabPane mainTabPane;
    private final String topicName;

    /** 把日志同时写入 stdout 和 kafka_diag.log 文件，方便排查 */
    private static void log(String msg) {
        System.out.println(msg);
        try (java.io.FileWriter fw = new java.io.FileWriter("kafka_diag.log", true)) {
            fw.write(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new java.util.Date()) + " " + msg + "\n");
        } catch (Exception ignored) {}
    }

    // Topic tab
    private TableView<TopicItem> topicTable;
    private final ObservableList<TopicItem> topicData = FXCollections.observableArrayList();
    private TextArea topicDetailArea;
    private VBox topicListSection;
    private HBox topicToolbar;
    private TableView<TopicOffsetItem> topicOffsetTable;
    private final ObservableList<TopicOffsetItem> topicOffsetData = FXCollections.observableArrayList();
    private VBox topicOffsetSection;

    // Message tab
    private TabPane queryTabPane;
    private String currentMessageTopic;
    private TextField offsetPartitionField;
    private TextField offsetOffsetField;
    private TextField sendKeyField;
    private TextField sendPartitionField;
    private TextArea sendHeadersArea;
    private TextArea sendBodyArea;
    private DatePicker beginDatePicker;
    private DatePicker endDatePicker;
    private TableView<MessageItem> messageTable;
    private final ObservableList<MessageItem> messageData = FXCollections.observableArrayList();
    private GridPane messageInfoGrid;
    private JsonFoldableTextView jsonBodyView;
    private Button formatBodyBtn;
    private Button compressBodyBtn;
    private Button copyAllBodyBtn;
    private Button expandAllBtn;
    private Button collapseAllBtn;
    private String rawBodyText = "";
    private boolean bodyFormatted = false;
    private Map<String, Object> currentMessageDetail;
    private javafx.collections.ObservableList<ObservableList<String>> consumeStatusData;
    private Label infoTitle;

    public KafkaDataView(ConnectionConfig config, String topicName) {
        this.config = config;
        this.topicName = topicName;
        this.mainTabPane = new TabPane();
        this.mainTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        setupTopicTab();
        setupMessageTab();

        this.getChildren().add(mainTabPane);
        VBox.setVgrow(mainTabPane, Priority.ALWAYS);

        String css = getClass().getResource("/css/tab-nogap.css") != null
                ? getClass().getResource("/css/tab-nogap.css").toExternalForm()
                : null;
        if (css != null) {
            mainTabPane.getStylesheets().add(css);
            this.getStylesheets().add(css);
        }

        Platform.runLater(this::applyNoGapStyles);
        loadTopics();
    }

    private void applyNoGapStyles() {
        applyNoGapToTabPane(mainTabPane);
    }

    private void applyNoGapToTabPane(TabPane tabPane) {
        tabPane.setStyle("-fx-padding: 0; -fx-border-insets: 0; -fx-background-insets: 0;");
        tabPane.lookupAll(".tab-content-area").forEach(n -> n.setStyle(
                "-fx-padding: 0; -fx-background-color: transparent;"));
        tabPane.lookupAll(".tab-header-area").forEach(n -> n.setStyle(
                "-fx-padding: 0;"));
        tabPane.lookupAll(".tab-pane").forEach(n -> {
            if (n instanceof TabPane && n != tabPane) {
                applyNoGapToTabPane((TabPane) n);
            }
        });
    }

    public TabPane getMainTabPane() {
        return mainTabPane;
    }

    public void selectTopicTab(String topicName) {
        mainTabPane.getSelectionModel().select(0);
        String t = (topicName != null && !topicName.isEmpty()) ? topicName : this.topicName;
        if (t != null && !t.isEmpty()) {
            showTopicOffsetSection(t);
            currentMessageTopic = t;
        } else {
            showTopicListSection();
        }
    }

    public void selectMessageTab(String topicName) {
        mainTabPane.getSelectionModel().select(1);
        String t = (topicName != null && !topicName.isEmpty()) ? topicName : this.topicName;
        currentMessageTopic = t;
    }

    public void selectSendTab(String topicName) {
        mainTabPane.getSelectionModel().select(1);
        String t = (topicName != null && !topicName.isEmpty()) ? topicName : this.topicName;
        currentMessageTopic = t;
        if (queryTabPane != null) {
            queryTabPane.getSelectionModel().select(2);
            queryTabPane.setMaxHeight(300);
        }
    }

    // ==================== Topic Tab ====================

    private void setupTopicTab() {
        VBox content = new VBox(0);
        content.setPadding(Insets.EMPTY);

        topicToolbar = new HBox(8);
        topicToolbar.setAlignment(Pos.CENTER_LEFT);

        Button refreshBtn = new Button("刷新");
        refreshBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-font-size: 12px;");
        refreshBtn.setOnAction(e -> loadTopics());

        Button createBtn = new Button("创建主题");
        createBtn.setStyle("-fx-font-size: 12px;");
        createBtn.setOnAction(e -> showCreateTopicDialog());

        Button deleteBtn = new Button("删除主题");
        deleteBtn.setStyle("-fx-font-size: 12px; -fx-text-fill: #cc0000;");
        deleteBtn.setOnAction(e -> deleteSelectedTopic());

        Button statsBtn = new Button("查看统计");
        statsBtn.setStyle("-fx-font-size: 12px;");
        statsBtn.setOnAction(e -> showTopicStats());

        topicToolbar.getChildren().addAll(refreshBtn, createBtn, deleteBtn, statsBtn);

        topicTable = new TableView<>();
        topicTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        topicTable.setPlaceholder(new Label("无数据"));

        TableColumn<TopicItem, String> nameCol = new TableColumn<>("主题名称");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("topic"));
        nameCol.setPrefWidth(350);

        TableColumn<TopicItem, String> partitionsCol = new TableColumn<>("分区数");
        partitionsCol.setCellValueFactory(new PropertyValueFactory<>("partitions"));
        partitionsCol.setPrefWidth(100);

        TableColumn<TopicItem, String> rfCol = new TableColumn<>("副本数");
        rfCol.setCellValueFactory(new PropertyValueFactory<>("replicationFactor"));
        rfCol.setPrefWidth(100);

        TableColumn<TopicItem, String> typeCol = new TableColumn<>("类型");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("topicType"));
        typeCol.setPrefWidth(100);

        topicTable.getColumns().addAll(nameCol, partitionsCol, rfCol, typeCol);
        topicTable.setItems(topicData);

        topicTable.setRowFactory(tv -> {
            TableRow<TopicItem> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    showTopicStats();
                }
            });
            return row;
        });

        topicDetailArea = new TextArea();
        topicDetailArea.setPromptText("选择主题后点击\"查看统计\"或双击查看分区偏移信息");
        topicDetailArea.setPrefHeight(200);
        topicDetailArea.setEditable(false);
        topicDetailArea.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");

        topicListSection = new VBox(8);
        topicListSection.getChildren().addAll(topicToolbar, topicTable, new Label("统计信息:"), topicDetailArea);
        VBox.setVgrow(topicTable, Priority.ALWAYS);

        topicOffsetTable = new TableView<>();
        topicOffsetTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        topicOffsetTable.setPlaceholder(new Label("无数据"));

        TableColumn<TopicOffsetItem, String> partitionCol = new TableColumn<>("Partition");
        partitionCol.setCellValueFactory(new PropertyValueFactory<>("partition"));
        partitionCol.setPrefWidth(100);

        TableColumn<TopicOffsetItem, String> leaderCol = new TableColumn<>("Leader");
        leaderCol.setCellValueFactory(new PropertyValueFactory<>("leader"));
        leaderCol.setPrefWidth(100);

        TableColumn<TopicOffsetItem, String> replicaCol = new TableColumn<>("副本数");
        replicaCol.setCellValueFactory(new PropertyValueFactory<>("replicaCount"));
        replicaCol.setPrefWidth(100);

        TableColumn<TopicOffsetItem, String> minOffsetCol = new TableColumn<>("MinOffset");
        minOffsetCol.setCellValueFactory(new PropertyValueFactory<>("minOffset"));
        minOffsetCol.setPrefWidth(120);

        TableColumn<TopicOffsetItem, String> maxOffsetCol = new TableColumn<>("MaxOffset");
        maxOffsetCol.setCellValueFactory(new PropertyValueFactory<>("maxOffset"));
        maxOffsetCol.setPrefWidth(120);

        topicOffsetTable.getColumns().addAll(partitionCol, leaderCol, replicaCol, minOffsetCol, maxOffsetCol);
        topicOffsetTable.setItems(topicOffsetData);

        Label topicTitleLabel = new Label();
        topicTitleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        HBox offsetToolbar = new HBox(10);
        offsetToolbar.setAlignment(Pos.CENTER_LEFT);
        offsetToolbar.getChildren().addAll(topicTitleLabel);

        topicOffsetSection = new VBox(8);
        topicOffsetSection.getChildren().addAll(offsetToolbar, topicOffsetTable);
        VBox.setVgrow(topicOffsetTable, Priority.ALWAYS);
        topicOffsetSection.setVisible(false);
        topicOffsetSection.setManaged(false);

        content.getChildren().addAll(topicListSection, topicOffsetSection);

        Tab tab = new Tab("主题");
        tab.setContent(content);
        mainTabPane.getTabs().add(tab);
    }

    private void showTopicListSection() {
        topicOffsetSection.setVisible(false);
        topicOffsetSection.setManaged(false);
        topicListSection.setVisible(true);
        topicListSection.setManaged(true);
    }

    private void showTopicOffsetSection(String topicName) {
        topicListSection.setVisible(false);
        topicListSection.setManaged(false);
        topicOffsetSection.setVisible(true);
        topicOffsetSection.setManaged(true);

        Label titleLabel = (Label) ((HBox) topicOffsetSection.getChildren().get(0)).getChildren().get(0);
        titleLabel.setText("主题: " + topicName + " - 分区偏移信息");

        loadTopicOffsetData(topicName);
    }

    private void loadTopicOffsetData(String topicName) {
        new Thread(() -> {
            try {
                Map<String, Object> stats = KafkaService.getTopicStats(config, topicName);
                Object offsetTable = stats.get("offsetTable");
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> offsetList = offsetTable instanceof List ? (List<Map<String, Object>>) offsetTable : null;

                Platform.runLater(() -> {
                    topicOffsetData.clear();
                    if (offsetList != null) {
                        for (Map<String, Object> offset : offsetList) {
                            String partition = String.valueOf(offset.getOrDefault("partition", ""));
                            String leader = String.valueOf(offset.getOrDefault("leader", ""));
                            String replicaCount = String.valueOf(offset.getOrDefault("replicaCount", ""));
                            String minOffset = String.valueOf(offset.getOrDefault("minOffset", ""));
                            String maxOffset = String.valueOf(offset.getOrDefault("maxOffset", ""));
                            topicOffsetData.add(new TopicOffsetItem(partition, leader, replicaCount, minOffset, maxOffset));
                        }
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    topicOffsetData.clear();
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("错误");
                    alert.setHeaderText(null);
                    alert.setContentText("获取主题统计信息失败: " + e.getMessage());
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
            }
        }, "Kafka-TopicOffset").start();
    }

    private void loadTopics() {
        new Thread(() -> {
            try {
                List<Map<String, Object>> topics = KafkaService.getTopicList(config);
                Platform.runLater(() -> {
                    topicData.clear();
                    for (Map<String, Object> t : topics) {
                        String name = String.valueOf(t.getOrDefault("topic", ""));
                        if (name.startsWith("__")) continue;
                        String partitions = String.valueOf(t.getOrDefault("partitions", ""));
                        String replicationFactor = String.valueOf(t.getOrDefault("replicationFactor", ""));
                        String type = String.valueOf(t.getOrDefault("topicType", ""));
                        topicData.add(new TopicItem(name, partitions, replicationFactor, type));
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> showError("加载主题列表失败: " + e.getMessage()));
            }
        }, "Kafka-LoadTopics").start();
    }

    private void showCreateTopicDialog() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("创建主题");
        dialog.setHeaderText("创建新的Kafka主题");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 10, 10, 10));

        TextField topicField = new TextField();
        topicField.setPromptText("主题名称");
        TextField partitionsField = new TextField("3");
        partitionsField.setPromptText("分区数");
        TextField rfField = new TextField("1");
        rfField.setPromptText("副本数");

        grid.add(new Label("主题名称："), 0, 0);
        grid.add(topicField, 1, 0);
        grid.add(new Label("分区数："), 0, 1);
        grid.add(partitionsField, 1, 1);
        grid.add(new Label("副本数："), 0, 2);
        grid.add(rfField, 1, 2);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(btn -> btn == ButtonType.OK
                ? topicField.getText() + "|" + partitionsField.getText() + "|" + rfField.getText() : null);
        DialogPositionUtil.centerOnOwner(dialog, this);
        dialog.showAndWait().ifPresent(result -> {
            String[] parts = result.split("\\|");
            String topic = parts[0].trim();
            int partitions = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 3;
            short replicationFactor = parts.length > 2 ? Short.parseShort(parts[2].trim()) : 1;
            if (topic.isEmpty()) return;
            new Thread(() -> {
                try {
                    KafkaService.createTopic(config, topic, partitions, replicationFactor);
                    Platform.runLater(() -> {
                        showInfo("主题 " + topic + " 创建成功");
                        loadTopics();
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> showError("创建主题失败: " + e.getMessage()));
                }
            }, "Kafka-CreateTopic").start();
        });
    }

    private void deleteSelectedTopic() {
        TopicItem selected = topicTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showWarning("请先选择要删除的主题");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认删除");
        confirm.setHeaderText("删除主题: " + selected.getTopic());
        confirm.setContentText("删除后不可恢复，确定要删除吗？");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                new Thread(() -> {
                    try {
                        KafkaService.deleteTopic(config, selected.getTopic());
                        Platform.runLater(() -> {
                            showInfo("主题 " + selected.getTopic() + " 已删除");
                            loadTopics();
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> showError("删除主题失败: " + e.getMessage()));
                    }
                }, "Kafka-DeleteTopic").start();
            }
        });
    }

    private void showTopicStats() {
        TopicItem selected = topicTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showWarning("请先选择主题");
            return;
        }
        showTopicOffsetSection(selected.getTopic());
    }

    // ==================== Message Tab ====================

    private void setupMessageTab() {
        VBox content = new VBox(0);
        content.setPadding(Insets.EMPTY);

        TabPane queryTabPane = new TabPane();
        this.queryTabPane = queryTabPane;

        String queryCss = getClass().getResource("/css/tab-nogap.css") != null
                ? getClass().getResource("/css/tab-nogap.css").toExternalForm()
                : null;
        if (queryCss != null) {
            queryTabPane.getStylesheets().add(queryCss);
        }

        // --- 按时间查询 ---
        VBox timeQueryContent = new VBox(5);
        timeQueryContent.setPadding(Insets.EMPTY);
        timeQueryContent.setAlignment(Pos.CENTER_LEFT);

        beginDatePicker = createDateTimePicker(LocalDate.now().minusDays(3).atStartOfDay());
        beginDatePicker.setPrefWidth(190);

        endDatePicker = createDateTimePicker(LocalDate.now().atTime(23, 59, 59));
        endDatePicker.setPrefWidth(190);

        Button timeSearchBtn = new Button("查询");
        timeSearchBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-font-size: 12px;");
        timeSearchBtn.setOnAction(e -> queryByTime());

        Button last1DayBtn = new Button("近1天");
        last1DayBtn.setStyle("-fx-font-size: 11px;");
        last1DayBtn.setOnAction(e -> setQuickRange(1));
        Button last3DaysBtn = new Button("近3天");
        last3DaysBtn.setStyle("-fx-font-size: 11px;");
        last3DaysBtn.setOnAction(e -> setQuickRange(3));
        Button last7DaysBtn = new Button("近7天");
        last7DaysBtn.setStyle("-fx-font-size: 11px;");
        last7DaysBtn.setOnAction(e -> setQuickRange(7));
        Button lastMonthBtn = new Button("近1月");
        lastMonthBtn.setStyle("-fx-font-size: 11px;");
        lastMonthBtn.setOnAction(e -> setQuickRange(30));

        HBox timeRangeBar = new HBox(5);
        timeRangeBar.setAlignment(Pos.CENTER_LEFT);
        timeRangeBar.getChildren().addAll(
                new Label("开始:"), beginDatePicker,
                new Label("结束:"), endDatePicker,
                timeSearchBtn
        );

        HBox quickRangeBar = new HBox(5);
        quickRangeBar.setAlignment(Pos.CENTER_LEFT);
        quickRangeBar.getChildren().addAll(new Label("快捷:"), last1DayBtn, last3DaysBtn, last7DaysBtn, lastMonthBtn);

        timeQueryContent.getChildren().addAll(timeRangeBar, quickRangeBar);

        Tab timeQueryTab = new Tab("按时间查询");
        timeQueryTab.setContent(timeQueryContent);
        timeQueryTab.setClosable(false);

        // --- 按Offset查询 ---
        VBox offsetQueryContent = new VBox(5);
        offsetQueryContent.setPadding(Insets.EMPTY);
        offsetQueryContent.setAlignment(Pos.CENTER_LEFT);

        offsetPartitionField = new TextField();
        offsetPartitionField.setPromptText("分区ID (如 0)");
        offsetPartitionField.setPrefWidth(100);

        offsetOffsetField = new TextField("0");
        offsetOffsetField.setPromptText("起始Offset");
        offsetOffsetField.setPrefWidth(120);

        Button offsetSearchBtn = new Button("查询");
        offsetSearchBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-font-size: 12px;");
        offsetSearchBtn.setOnAction(e -> queryByOffset());

        HBox offsetQueryBar = new HBox(10);
        offsetQueryBar.setAlignment(Pos.CENTER_LEFT);
        offsetQueryBar.getChildren().addAll(
                new Label("分区:"), offsetPartitionField,
                new Label("Offset:"), offsetOffsetField,
                offsetSearchBtn
        );
        offsetQueryContent.getChildren().add(offsetQueryBar);

        Tab offsetQueryTab = new Tab("按Offset查询");
        offsetQueryTab.setContent(offsetQueryContent);
        offsetQueryTab.setClosable(false);

        // --- 发送消息 ---
        VBox sendContent = new VBox(5);
        sendContent.setPadding(Insets.EMPTY);
        sendContent.setAlignment(Pos.CENTER_LEFT);

        sendKeyField = new TextField();
        sendKeyField.setPromptText("消息 Key (可选)");
        sendKeyField.setPrefWidth(200);
        sendKeyField.setStyle("-fx-font-size: 12px;");

        sendPartitionField = new TextField();
        sendPartitionField.setPromptText("分区ID (留空=默认)");
        sendPartitionField.setPrefWidth(120);
        sendPartitionField.setStyle("-fx-font-size: 12px;");

        Button sendBtn = new Button("发送");
        sendBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-font-size: 12px;");
        sendBtn.setOnAction(e -> doSendMessage());

        HBox sendConfigBar = new HBox(10);
        sendConfigBar.setAlignment(Pos.CENTER_LEFT);
        sendConfigBar.getChildren().addAll(new Label("Key:"), sendKeyField, new Label("Partition:"), sendPartitionField, sendBtn);

        sendHeadersArea = new TextArea();
        sendHeadersArea.setPromptText("Headers (可选，每行 key=value)");
        sendHeadersArea.setPrefHeight(50);
        sendHeadersArea.setStyle("-fx-font-size: 12px;");

        sendBodyArea = new TextArea();
        sendBodyArea.setPromptText("消息内容");
        sendBodyArea.setPrefHeight(100);
        sendBodyArea.setStyle("-fx-font-size: 12px;");

        sendContent.getChildren().addAll(sendConfigBar, new Label("Headers:"), sendHeadersArea, new Label("Body:"), sendBodyArea);

        Tab sendTab = new Tab("发送消息");
        sendTab.setContent(sendContent);
        sendTab.setClosable(false);

        queryTabPane.getTabs().addAll(timeQueryTab, offsetQueryTab, sendTab);

        final Tab finalSendTab = sendTab;
        queryTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) ->
                Platform.runLater(() -> {
                    if (newTab == finalSendTab) {
                        queryTabPane.setMaxHeight(300);
                    } else {
                        queryTabPane.setMaxHeight(120);
                    }
                    applyNoGapStyles();
                }));

        // 消息表格
        messageTable = new TableView<>();
        messageTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        messageTable.setPlaceholder(new Label("无数据"));

        TableColumn<MessageItem, String> partitionCol = new TableColumn<>("Partition");
        partitionCol.setCellValueFactory(new PropertyValueFactory<>("partition"));
        partitionCol.setPrefWidth(100);

        TableColumn<MessageItem, String> offsetCol = new TableColumn<>("Offset");
        offsetCol.setCellValueFactory(new PropertyValueFactory<>("offset"));
        offsetCol.setPrefWidth(120);

        TableColumn<MessageItem, String> keyCol = new TableColumn<>("Key");
        keyCol.setCellValueFactory(new PropertyValueFactory<>("key"));
        keyCol.setPrefWidth(150);

        TableColumn<MessageItem, String> timeCol = new TableColumn<>("时间");
        timeCol.setCellValueFactory(new PropertyValueFactory<>("storeTime"));
        timeCol.setPrefWidth(180);

        TableColumn<MessageItem, String> tsTypeCol = new TableColumn<>("时间类型");
        tsTypeCol.setCellValueFactory(new PropertyValueFactory<>("timestampType"));
        tsTypeCol.setPrefWidth(100);

        messageTable.getColumns().addAll(partitionCol, offsetCol, keyCol, timeCol, tsTypeCol);
        messageTable.setItems(messageData);

        messageTable.setRowFactory(tv -> {
            TableRow<MessageItem> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    showMessageDetail(row.getItem());
                }
            });
            return row;
        });

        // 右侧详情面板
        VBox detailPanel = new VBox(5);
        detailPanel.setPadding(new Insets(5));

        messageInfoGrid = new GridPane();
        messageInfoGrid.setHgap(8);
        messageInfoGrid.setVgap(4);
        messageInfoGrid.setStyle("-fx-background-color: #f8f8f8; -fx-padding: 8; -fx-border-color: #e0e0e0; -fx-border-radius: 4;");

        infoTitle = new Label("基本信息");
        infoTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        HBox bodyToolbar = new HBox(8);
        bodyToolbar.setAlignment(Pos.CENTER_LEFT);
        Label bodyTitle = new Label("Body");
        bodyTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");
        formatBodyBtn = new Button("格式化");
        formatBodyBtn.setStyle("-fx-font-size: 11px;");
        formatBodyBtn.setDisable(true);
        formatBodyBtn.setOnAction(e -> toggleFormatBody());
        compressBodyBtn = new Button("压缩");
        compressBodyBtn.setStyle("-fx-font-size: 11px;");
        compressBodyBtn.setDisable(true);
        compressBodyBtn.setOnAction(e -> toggleFormatBody());
        copyAllBodyBtn = new Button("复制全部");
        copyAllBodyBtn.setStyle("-fx-font-size: 11px;");
        copyAllBodyBtn.setDisable(true);
        copyAllBodyBtn.setOnAction(e -> jsonBodyView.copyAll());
        expandAllBtn = new Button("展开全部");
        expandAllBtn.setStyle("-fx-font-size: 11px;");
        expandAllBtn.setDisable(true);
        expandAllBtn.setOnAction(e -> jsonBodyView.expandAll());
        collapseAllBtn = new Button("折叠全部");
        collapseAllBtn.setStyle("-fx-font-size: 11px;");
        collapseAllBtn.setDisable(true);
        collapseAllBtn.setOnAction(e -> jsonBodyView.collapseAll());
        bodyToolbar.getChildren().addAll(bodyTitle, formatBodyBtn, compressBodyBtn, copyAllBodyBtn, expandAllBtn, collapseAllBtn);

        jsonBodyView = new JsonFoldableTextView();

        // 消费状态区域
        Label consumeTitle = new Label("消费状态");
        consumeTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        HBox consumeToolbar = new HBox(8);
        consumeToolbar.setAlignment(Pos.CENTER_LEFT);
        Button refreshConsumeBtn = new Button("刷新");
        refreshConsumeBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-font-size: 11px;");
        Button viewUnconsumedBtn = new Button("查看未消费消息");
        viewUnconsumedBtn.setStyle("-fx-font-size: 11px;");
        viewUnconsumedBtn.setDisable(true);
        consumeToolbar.getChildren().addAll(refreshConsumeBtn, viewUnconsumedBtn);

        TableView<ObservableList<String>> consumeStatusTable = new TableView<>();
        consumeStatusTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        consumeStatusTable.setPlaceholder(new Label("双击消息后点击刷新加载消费状态"));
        consumeStatusTable.setPrefHeight(140);
        consumeStatusTable.setMaxHeight(200);

        TableColumn<ObservableList<String>, String> groupCol = new TableColumn<>("消费者组");
        groupCol.setCellValueFactory(param -> new javafx.beans.property.SimpleStringProperty(param.getValue().get(0)));
        groupCol.setPrefWidth(220);

        TableColumn<ObservableList<String>, String> diffCol = new TableColumn<>("积压量");
        diffCol.setCellValueFactory(param -> new javafx.beans.property.SimpleStringProperty(param.getValue().get(1)));
        diffCol.setPrefWidth(150);

        consumeStatusTable.getColumns().addAll(groupCol, diffCol);

        consumeStatusData = javafx.collections.FXCollections.observableArrayList();
        consumeStatusTable.setItems(consumeStatusData);

        final String[] selectedConsumeGroup = {null};
        consumeStatusTable.setRowFactory(tv -> {
            TableRow<ObservableList<String>> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (!row.isEmpty()) {
                    selectedConsumeGroup[0] = row.getItem().get(0);
                    viewUnconsumedBtn.setDisable(false);
                }
            });
            return row;
        });

        refreshConsumeBtn.setOnAction(e -> loadConsumeStatus());

        viewUnconsumedBtn.setOnAction(e -> {
            String topic = getCurrentTopic();
            String group = selectedConsumeGroup[0];
            if (topic == null || topic.isEmpty() || group == null) return;
            new Thread(() -> {
                try {
                    List<Map<String, Object>> msgs = KafkaService.queryUnconsumedMessages(config, topic, group, 100);
                    Platform.runLater(() -> {
                        if (msgs.isEmpty()) {
                            Alert info = new Alert(Alert.AlertType.INFORMATION);
                            info.setTitle("提示");
                            info.setHeaderText(null);
                            info.setContentText("消费者组 " + group + " 没有未消费的消息");
                            info.showAndWait();
                        } else {
                            messageData.clear();
                            for (Map<String, Object> m : msgs) {
                                messageData.add(new MessageItem(
                                    String.valueOf(m.getOrDefault("partition", "")),
                                    String.valueOf(m.getOrDefault("offset", "")),
                                    String.valueOf(m.getOrDefault("key", "")),
                                    String.valueOf(m.getOrDefault("timestamp", "")),
                                    String.valueOf(m.getOrDefault("timestampType", "")),
                                    m
                                ));
                            }
                        }
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("查询失败");
                        alert.setHeaderText(null);
                        alert.setContentText("查询未消费消息失败: " + ex.getMessage());
                        alert.showAndWait();
                    });
                }
            }, "Kafka-Unconsumed").start();
        });

        detailPanel.getChildren().addAll(infoTitle, messageInfoGrid,
                bodyToolbar, jsonBodyView,
                consumeTitle, consumeToolbar, consumeStatusTable);
        VBox.setVgrow(jsonBodyView, Priority.ALWAYS);

        SplitPane splitPane = new SplitPane();
        splitPane.setOrientation(javafx.geometry.Orientation.HORIZONTAL);

        VBox leftPane = new VBox(0);
        leftPane.getChildren().addAll(queryTabPane, messageTable);
        VBox.setVgrow(messageTable, Priority.ALWAYS);
        VBox.setVgrow(queryTabPane, Priority.NEVER);
        queryTabPane.setMaxHeight(120);
        queryTabPane.setMinHeight(80);

        splitPane.getItems().addAll(leftPane, detailPanel);
        splitPane.setDividerPositions(0.55);

        content.getChildren().add(splitPane);
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        Tab tab = new Tab("消息");
        tab.setContent(content);
        mainTabPane.getTabs().add(tab);
    }

    private String getCurrentTopic() {
        if (currentMessageTopic != null && !currentMessageTopic.isEmpty()) {
            return currentMessageTopic;
        }
        return topicName;
    }

    private void queryByTime() {
        final String topic = getCurrentTopic();
        if (topic == null || topic.isEmpty()) {
            showWarning("当前标签未关联主题");
            return;
        }
        long begin = getDateTimeFromPicker(beginDatePicker);
        long end = getDateTimeFromPicker(endDatePicker);
        if (begin == 0 || end == 0) {
            showWarning("请输入正确的时间格式 (yyyy-MM-dd HH:mm:ss)");
            return;
        }
        if (begin >= end) {
            showWarning("开始时间必须早于结束时间");
            return;
        }

        final long beginFinal = begin;
        final long endFinal = end;
        new Thread(() -> {
            try {
                List<Map<String, Object>> messages = KafkaService.queryMessageByTime(config, topic, begin, end);
                displayQueryResults(messages);
                if (messages == null || messages.isEmpty()) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
                        alert.setTitle("查询结果");
                        alert.setHeaderText("未查询到消息");
                        String diag = "topic: " + topic + "\n"
                                + "时间范围: " + java.time.Instant.ofEpochMilli(beginFinal)
                                + " ~ " + java.time.Instant.ofEpochMilli(endFinal) + "\n"
                                + "SSH隧道: " + (config.isUseSshTunnel() ? "已启用" : "未启用") + "\n\n"
                                + "可能原因:\n"
                                + "  1) 消息时间早于开始时间（命令行 --from-beginning 能看历史消息，按时间查询只返回 begin~end 之间的消息）\n"
                                + "  2) 消息时间晚于结束时间\n"
                                + "  3) 该主题无消息或 KafkaConsumer 无法连接到 broker（advertised.listeners 问题）\n\n"
                                + "详细诊断见控制台 stdout 日志（[KafkaService] queryMessageByTime 开头）";
                        TextArea area = new TextArea(diag);
                        area.setEditable(false);
                        area.setWrapText(true);
                        area.setPrefWidth(560);
                        area.setPrefRowCount(12);
                        alert.getDialogPane().setContent(area);
                        DialogPositionUtil.centerOnOwner(alert, this);
                        alert.showAndWait();
                    });
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    messageData.clear();
                    showErrorInBody("查询消息失败: " + e.getMessage());
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("查询消息失败");
                    alert.setHeaderText(null);
                    TextArea area = new TextArea("topic: " + topic + "\n"
                            + "时间范围: " + java.time.Instant.ofEpochMilli(beginFinal)
                            + " ~ " + java.time.Instant.ofEpochMilli(endFinal) + "\n"
                            + "异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    area.setEditable(false);
                    area.setWrapText(true);
                    area.setPrefWidth(560);
                    area.setPrefRowCount(8);
                    alert.getDialogPane().setContent(area);
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
            }
        }, "Kafka-QueryByTime").start();
    }

    private void queryByOffset() {
        final String topic = getCurrentTopic();
        if (topic == null || topic.isEmpty()) {
            showWarning("当前标签未关联主题");
            return;
        }
        int partition;
        long offset;
        try {
            partition = Integer.parseInt(offsetPartitionField.getText().trim());
            offset = Long.parseLong(offsetOffsetField.getText().trim());
        } catch (NumberFormatException ex) {
            showWarning("分区ID与Offset必须为数字");
            return;
        }

        final int partitionFinal = partition;
        final long offsetFinal = offset;
        new Thread(() -> {
            try {
                log("[KafkaDataView] queryByOffset: 调用 KafkaService.queryMessageByOffset...");
                List<Map<String, Object>> messages = KafkaService.queryMessageByOffset(config, topic, partition, offset, 256);
                log("[KafkaDataView] queryByOffset: KafkaService 返回 " + (messages == null ? "null" : messages.size()) + " 条消息，即将调用 displayQueryResults");
                displayQueryResults(messages);
                log("[KafkaDataView] queryByOffset: displayQueryResults 已调用完毕");
                if (messages == null || messages.isEmpty()) {
                    // 在 worker 线程读 ThreadLocal 诊断（在 JavaFX 线程读会拿到另一个 ThreadLocal）
                    final String consumerDiag = KafkaService.getLastDiag();
                    // 拿集群 broker 地址（即 broker 注册到 ZK/KRaft 的 advertised.listeners）展示给用户
                    String brokerInfo;
                    try {
                        List<Map<String, Object>> cluster = KafkaService.getClusterInfo(config);
                        StringBuilder sb = new StringBuilder();
                        for (Map<String, Object> b : cluster) {
                            sb.append("  broker-").append(b.get("brokerId"))
                              .append("  address=").append(b.get("address"))
                              .append("  role=").append(b.get("role"))
                              .append("\n");
                        }
                        brokerInfo = sb.length() == 0 ? "  (集群无 broker 信息)" : sb.toString();
                    } catch (Exception ce) {
                        brokerInfo = "  获取集群信息失败: " + ce.getClass().getSimpleName() + ": " + ce.getMessage();
                    }
                    final String brokerInfoFinal = brokerInfo;
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.WARNING);
                        alert.setTitle("查询结果");
                        alert.setHeaderText("未查询到消息");
                        String diag = "topic: " + topic + "  partition: " + partitionFinal + "  offset: " + offsetFinal + "\n\n"
                                + "=== Kafka 集群 broker 注册地址 ===\n"
                                + brokerInfoFinal
                                + "你配置的连接地址: " + config.getHost() + ":" + config.getPort() + "\n\n"
                                + "=== KafkaConsumer 内部诊断 ===\n"
                                + consumerDiag;
                        TextArea area = new TextArea(diag);
                        area.setEditable(false);
                        area.setWrapText(true);
                        area.setPrefWidth(600);
                        area.setPrefRowCount(20);
                        alert.getDialogPane().setContent(area);
                        DialogPositionUtil.centerOnOwner(alert, this);
                        alert.showAndWait();
                    });
                }
            } catch (Exception e) {
                log("[KafkaDataView] queryByOffset 捕获异常: " + e.getClass().getName() + ": " + e.getMessage());
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                log("[KafkaDataView] 异常堆栈:\n" + sw.toString());
                Platform.runLater(() -> {
                    messageData.clear();
                    showErrorInBody("查询消息失败: " + e.getMessage());
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("查询消息失败");
                    alert.setHeaderText(null);
                    TextArea area = new TextArea("topic: " + topic + "  partition: " + partitionFinal + "  offset: " + offsetFinal + "\n"
                            + "异常: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                    area.setEditable(false);
                    area.setWrapText(true);
                    area.setPrefWidth(560);
                    area.setPrefRowCount(8);
                    alert.getDialogPane().setContent(area);
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
            }
        }, "Kafka-QueryByOffset").start();
    }

    private void doSendMessage() {
        final String topic = getCurrentTopic();
        if (topic == null || topic.isEmpty()) {
            showWarning("当前标签未关联主题");
            return;
        }
        final String body = sendBodyArea.getText();
        if (body == null || body.trim().isEmpty()) {
            showWarning("消息内容不能为空");
            return;
        }
        final String key = sendKeyField.getText().trim();
        final String partitionText = sendPartitionField.getText().trim();
        final Integer partition;
        try {
            partition = partitionText.isEmpty() ? null : Integer.parseInt(partitionText);
        } catch (NumberFormatException ex) {
            showWarning("分区ID必须为数字");
            return;
        }
        final Map<String, String> headers = new LinkedHashMap<>();
        String headerText = sendHeadersArea.getText();
        if (headerText != null && !headerText.trim().isEmpty()) {
            for (String line : headerText.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    headers.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        }

        new Thread(() -> {
            try {
                Map<String, Object> result = KafkaService.sendMessage(config, topic, partition,
                        key.isEmpty() ? null : key, body, headers.isEmpty() ? null : headers);
                Platform.runLater(() -> {
                    String partitionStr = String.valueOf(result.getOrDefault("partition", ""));
                    String offsetStr = String.valueOf(result.getOrDefault("offset", ""));
                    String timestamp = formatTimestamp(result.get("timestamp"));
                    Alert info = new Alert(Alert.AlertType.INFORMATION);
                    info.setTitle("发送成功");
                    info.setHeaderText("消息已发送到 " + topic);
                    info.setContentText("Partition: " + partitionStr + "\nOffset: " + offsetStr + "\n时间: " + timestamp);
                    DialogPositionUtil.centerOnOwner(info, this);
                    info.showAndWait();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("发送失败");
                    alert.setHeaderText(null);
                    alert.setContentText("发送消息失败: " + e.getMessage());
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
            }
        }, "Kafka-SendMessage").start();
    }

    private void displayQueryResults(List<Map<String, Object>> messages) {
        log("[KafkaDataView] displayQueryResults 调用: messages=" + (messages == null ? "null" : messages.size()));
        Platform.runLater(() -> {
            messageData.clear();
            if (messages == null || messages.isEmpty()) {
                showErrorInBody("没有消息");
                log("[KafkaDataView] UI 更新: messages 为空，显示'没有消息'");
            } else {
                clearBody();
            }
            for (Map<String, Object> m : messages) {
                String partition = String.valueOf(m.getOrDefault("partition", ""));
                String offset = String.valueOf(m.getOrDefault("offset", ""));
                String key = String.valueOf(m.getOrDefault("key", ""));
                String storeTime = formatTimestamp(m.get("timestamp"));
                String tsType = String.valueOf(m.getOrDefault("timestampType", ""));
                messageData.add(new MessageItem(partition, offset, key, storeTime, tsType, m));
            }
            log("[KafkaDataView] UI 更新完成: messageData.size=" + messageData.size()
                    + " (Thread=" + Thread.currentThread().getName() + ")");
        });
    }

    private void showMessageDetail(MessageItem item) {
        if (item.getDetail() != null && !item.getDetail().isEmpty()) {
            fillMessageDetailPanel(item.getDetail());
            return;
        }
        // Kafka 消息无独立查询API，直接使用列表中已携带的详情
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("partition", item.getPartition());
        fallback.put("offset", item.getOffset());
        fallback.put("key", item.getKey());
        fallback.put("timestamp", item.getStoreTime());
        fillMessageDetailPanel(fallback);
    }

    private void fillMessageDetailPanel(Map<String, Object> detail) {
        currentMessageDetail = detail;
        loadConsumeStatus();
        messageInfoGrid.getChildren().clear();
        int row = 0;
        String bodyText = null;
        for (Map.Entry<String, Object> entry : detail.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if ("body".equals(key)) {
                bodyText = value != null ? String.valueOf(value) : "";
                continue;
            }
            if ("headers".equals(key)) {
                // 头部单独展示为多行
                Label keyLabel = new Label("headers:");
                keyLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
                messageInfoGrid.add(keyLabel, 0, row);
                StringBuilder sb = new StringBuilder();
                if (value instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> headers = (List<Map<String, Object>>) value;
                    for (Map<String, Object> h : headers) {
                        sb.append(h.getOrDefault("key", "")).append("=")
                          .append(h.getOrDefault("value", "")).append("\n");
                    }
                }
                Label valueLabel = new Label(sb.toString().trim());
                valueLabel.setWrapText(true);
                valueLabel.setMaxWidth(Double.MAX_VALUE);
                valueLabel.setStyle("-fx-font-size: 12px; -fx-font-family: monospace;");
                messageInfoGrid.add(valueLabel, 1, row);
                row++;
                continue;
            }
            if ("timestamp".equals(key)) {
                value = formatTimestamp(value);
            }
            Label keyLabel = new Label(key + ":");
            keyLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
            Label valueLabel = new Label(String.valueOf(value));
            valueLabel.setWrapText(true);
            valueLabel.setMaxWidth(Double.MAX_VALUE);
            valueLabel.setStyle("-fx-font-size: 12px;");
            // offset 行加复制按钮
            if ("offset".equals(key)) {
                HBox keyBox = new HBox(2);
                keyBox.setAlignment(Pos.CENTER_LEFT);
                keyBox.getChildren().add(keyLabel);
                ImageView copyIcon = new ImageView(new Image(getClass().getResourceAsStream("/images/copy.png")));
                copyIcon.setFitWidth(14);
                copyIcon.setFitHeight(14);
                Button copyBtn = new Button();
                copyBtn.setGraphic(copyIcon);
                copyBtn.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 1 2; -fx-cursor: hand;");
                copyBtn.setTooltip(new Tooltip("复制"));
                String copyValue = String.valueOf(value);
                copyBtn.setOnAction(e -> {
                    javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                    javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                    content.putString(copyValue);
                    clipboard.setContent(content);
                });
                keyBox.getChildren().add(copyBtn);
                messageInfoGrid.add(keyBox, 0, row);
            } else {
                messageInfoGrid.add(keyLabel, 0, row);
            }
            messageInfoGrid.add(valueLabel, 1, row);
            row++;
        }

        if (bodyText != null) {
            rawBodyText = bodyText;
            boolean isJson = isJsonString(bodyText);
            bodyFormatted = true;
            if (isJson) {
                formatBodyBtn.setDisable(false);
                compressBodyBtn.setDisable(false);
                copyAllBodyBtn.setDisable(false);
                expandAllBtn.setDisable(false);
                collapseAllBtn.setDisable(false);
                jsonBodyView.setText(bodyText);
            } else {
                formatBodyBtn.setDisable(true);
                compressBodyBtn.setDisable(true);
                copyAllBodyBtn.setDisable(false);
                expandAllBtn.setDisable(true);
                collapseAllBtn.setDisable(true);
                jsonBodyView.setText(bodyText);
            }
        } else {
            rawBodyText = "";
            bodyFormatted = false;
            jsonBodyView.clear();
            formatBodyBtn.setDisable(true);
            compressBodyBtn.setDisable(true);
            copyAllBodyBtn.setDisable(true);
            expandAllBtn.setDisable(true);
            collapseAllBtn.setDisable(true);
        }
    }

    private void loadConsumeStatus() {
        final String topic = getCurrentTopic();
        if (topic == null || topic.isEmpty()) return;
        new Thread(() -> {
            try {
                List<Map<String, Object>> tracks = KafkaService.getTopicConsumeStatus(config, topic);
                Platform.runLater(() -> {
                    consumeStatusData.clear();
                    for (Map<String, Object> t : tracks) {
                        String group = String.valueOf(t.getOrDefault("group", ""));
                        ObservableList<String> row = javafx.collections.FXCollections.observableArrayList();
                        row.add(group);
                        row.add(String.valueOf(t.getOrDefault("diffTotal", "0")));
                        consumeStatusData.add(row);
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("加载消费状态失败: " + ex.getMessage());
                    alert.showAndWait();
                });
            }
        }, "Kafka-ConsumeStatus").start();
    }

    private void toggleFormatBody() {
        if (rawBodyText == null || rawBodyText.isEmpty()) return;
        if (bodyFormatted) {
            bodyFormatted = false;
            try {
                com.google.gson.JsonElement element = new com.google.gson.JsonParser().parseString(rawBodyText);
                String compact = new com.google.gson.Gson().toJson(element);
                jsonBodyView.setText(compact);
            } catch (Exception e) {
                jsonBodyView.setText(rawBodyText);
            }
        } else {
            bodyFormatted = true;
            jsonBodyView.setText(rawBodyText);
        }
    }

    private boolean isJsonString(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String trimmed = text.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}"))
                || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    // ==================== 辅助方法 ====================

    private void showErrorInBody(String msg) {
        jsonBodyView.setError(msg);
    }

    private void clearBody() {
        jsonBodyView.clear();
        messageInfoGrid.getChildren().clear();
        formatBodyBtn.setDisable(true);
        compressBodyBtn.setDisable(true);
        copyAllBodyBtn.setDisable(true);
        expandAllBtn.setDisable(true);
        collapseAllBtn.setDisable(true);
        rawBodyText = "";
        bodyFormatted = false;
    }

    private String formatTimestamp(Object ts) {
        if (ts == null) return "";
        try {
            long millis;
            if (ts instanceof Number) {
                millis = ((Number) ts).longValue();
            } else {
                millis = Long.parseLong(String.valueOf(ts));
            }
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return String.valueOf(ts);
        }
    }

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DatePicker createDateTimePicker(LocalDateTime defaultDateTime) {
        DatePicker picker = new DatePicker();
        picker.setPrefWidth(190);
        picker.setConverter(new StringConverter<LocalDate>() {
            @Override
            public String toString(LocalDate date) {
                if (date == null) return "";
                String editorText = picker.getEditor().getText();
                String timePart = " 00:00:00";
                if (editorText != null && editorText.trim().length() >= 19) {
                    timePart = " " + editorText.trim().substring(11);
                }
                return date.format(DateTimeFormatter.ISO_LOCAL_DATE) + timePart;
            }

            @Override
            public LocalDate fromString(String string) {
                if (string == null || string.trim().isEmpty()) return null;
                String trimmed = string.trim();
                try {
                    return LocalDateTime.parse(trimmed, DATETIME_FORMATTER).toLocalDate();
                } catch (Exception e) {
                    try {
                        return LocalDate.parse(trimmed);
                    } catch (Exception e2) {
                        return null;
                    }
                }
            }
        });
        picker.setValue(defaultDateTime.toLocalDate());
        picker.getEditor().setText(defaultDateTime.format(DATETIME_FORMATTER));
        return picker;
    }

    private void setDateTimePickerValue(DatePicker picker, LocalDateTime dateTime) {
        picker.setValue(dateTime.toLocalDate());
        picker.getEditor().setText(dateTime.format(DATETIME_FORMATTER));
    }

    private long getDateTimeFromPicker(DatePicker picker) {
        try {
            String text = picker.getEditor().getText();
            if (text == null || text.trim().isEmpty()) return 0;
            LocalDateTime dt = LocalDateTime.parse(text.trim(), DATETIME_FORMATTER);
            return dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    private void setQuickRange(int days) {
        setDateTimePickerValue(beginDatePicker, LocalDate.now().minusDays(days).atStartOfDay());
        setDateTimePickerValue(endDatePicker, LocalDate.now().atTime(23, 59, 59));
    }

    private void showError(String msg) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("错误");
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    private void showWarning(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    private void showInfo(String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("成功");
        alert.setHeaderText(null);
        alert.setContentText(msg);
        DialogPositionUtil.centerOnOwner(alert, this);
        alert.showAndWait();
    }

    // ==================== 数据模型类 ====================

    public static class TopicItem {
        private final String topic;
        private final String partitions;
        private final String replicationFactor;
        private final String topicType;
        public TopicItem(String topic, String partitions, String replicationFactor, String topicType) {
            this.topic = topic; this.partitions = partitions; this.replicationFactor = replicationFactor; this.topicType = topicType;
        }
        public String getTopic() { return topic; }
        public String getPartitions() { return partitions; }
        public String getReplicationFactor() { return replicationFactor; }
        public String getTopicType() { return topicType; }
    }

    public static class MessageItem {
        private final String partition;
        private final String offset;
        private final String key;
        private final String storeTime;
        private final String timestampType;
        private final Map<String, Object> detail;
        public MessageItem(String partition, String offset, String key, String storeTime, String timestampType, Map<String, Object> detail) {
            this.partition = partition; this.offset = offset; this.key = key; this.storeTime = storeTime; this.timestampType = timestampType; this.detail = detail;
        }
        public String getPartition() { return partition; }
        public String getOffset() { return offset; }
        public String getKey() { return key; }
        public String getStoreTime() { return storeTime; }
        public String getTimestampType() { return timestampType; }
        public Map<String, Object> getDetail() { return detail; }
    }

    public static class TopicOffsetItem {
        private final String partition;
        private final String leader;
        private final String replicaCount;
        private final String minOffset;
        private final String maxOffset;
        public TopicOffsetItem(String partition, String leader, String replicaCount, String minOffset, String maxOffset) {
            this.partition = partition; this.leader = leader; this.replicaCount = replicaCount; this.minOffset = minOffset; this.maxOffset = maxOffset;
        }
        public String getPartition() { return partition; }
        public String getLeader() { return leader; }
        public String getReplicaCount() { return replicaCount; }
        public String getMinOffset() { return minOffset; }
        public String getMaxOffset() { return maxOffset; }
    }
}
