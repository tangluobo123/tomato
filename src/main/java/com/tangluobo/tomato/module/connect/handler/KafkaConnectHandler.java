package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.*;
import com.tangluobo.tomato.module.connect.service.KafkaService;
import com.tangluobo.tomato.module.connect.view.KafkaDataView;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Kafka 连接处理器，参考 RocketmqConnectHandler 结构。
 */
public class KafkaConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.KAFKA;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        TreeItem<String> hostItem = module.findItemById(module.getRoot(), config.getId());
        if (hostItem != null) {
            handleHostDoubleClick(module, hostItem, config);
        }
    }

    @Override
    public void handleHostDoubleClick(ConnectModule module, TreeItem<String> hostItem, ConnectionConfig config) {
        if (!hostItem.getChildren().isEmpty()) {
            hostItem.setExpanded(!hostItem.isExpanded());
            return;
        }

        ProgressIndicator loadingIndicator = new ProgressIndicator();
        loadingIndicator.setPrefSize(16, 16);
        loadingIndicator.setMaxSize(16, 16);
        loadingIndicator.setStyle("-fx-progress-color: #4CAF50;");
        hostItem.setGraphic(loadingIndicator);

        new Thread(() -> {
            try {
                String error = KafkaService.testConnection(config);
                if (error != null) {
                    Platform.runLater(() -> {
                        hostItem.setGraphic(module.getIconForConfig(config));
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("连接失败");
                        alert.setHeaderText("无法连接到 Kafka " + config.getHost() + ":" + config.getPort());
                        // 错误信息可能较长（含诊断提示），用 TextArea 包装便于滚动与复制
                        TextArea errorArea = new TextArea("无法连接到Kafka " + config.getHost() + ":" + config.getPort() + "\n\n错误: " + error);
                        errorArea.setEditable(false);
                        errorArea.setWrapText(true);
                        errorArea.setPrefWidth(560);
                        errorArea.setPrefRowCount(12);
                        alert.getDialogPane().setContent(errorArea);
                        showDialog(alert);
                    });
                    return;
                }
                Platform.runLater(() -> {
                    module.updateHostIcon(hostItem, config, true);
                    hostItem.getChildren().clear();

                    // 主题节点
                    TreeItem<String> topicsFolder = new TreeItem<>("主题");
                    DatabaseNodeData topicsData = new DatabaseNodeData(DatabaseNodeData.NodeType.KAFKA_TOPICS_FOLDER, "主题", config, "");
                    topicsFolder.setGraphic(module.getDbNodeIcon(topicsData));
                    module.getDbNodeDataMap().put(topicsFolder, topicsData);

                    // 消费者组节点
                    TreeItem<String> consumersFolder = new TreeItem<>("消费者组");
                    DatabaseNodeData consumersData = new DatabaseNodeData(DatabaseNodeData.NodeType.KAFKA_CONSUMERS_FOLDER, "消费者组", config, "");
                    consumersFolder.setGraphic(module.getDbNodeIcon(consumersData));
                    module.getDbNodeDataMap().put(consumersFolder, consumersData);

                    // 集群节点
                    TreeItem<String> clusterFolder = new TreeItem<>("集群");
                    DatabaseNodeData clusterData = new DatabaseNodeData(DatabaseNodeData.NodeType.KAFKA_CLUSTER_FOLDER, "集群", config, "");
                    clusterFolder.setGraphic(module.getDbNodeIcon(clusterData));
                    module.getDbNodeDataMap().put(clusterFolder, clusterData);

                    hostItem.getChildren().addAll(topicsFolder, consumersFolder, clusterFolder);
                    hostItem.setExpanded(true);
                });
            } catch (Throwable e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    hostItem.setGraphic(module.getIconForConfig(config));
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("连接失败");
                    alert.setHeaderText("无法连接到 Kafka " + config.getName());
                    // 异常 message 可能为 null（如某些 ExecutionException），回退到 toString 保留类名
                    String msg = e.getMessage();
                    if (msg == null || msg.isEmpty()) msg = e.toString();
                    TextArea errorArea = new TextArea("无法连接到Kafka " + config.getName() + ": " + msg);
                    errorArea.setEditable(false);
                    errorArea.setWrapText(true);
                    errorArea.setPrefWidth(560);
                    errorArea.setPrefRowCount(8);
                    alert.getDialogPane().setContent(errorArea);
                    showDialog(alert);
                });
            }
        }, "Kafka-Connect").start();
    }

    /**
     * 双击"消费者组"folder节点：创建/选中"消费者组"一级标签，并加载子节点到树。
     */
    public void handleConsumersFolderDoubleClick(ConnectModule module, TreeItem<String> item, DatabaseNodeData data) {
        TabPane terminalTabPane = module.getTerminalTabPane();
        if (terminalTabPane == null) return;
        if (!module.ensureTabPaneInstalled()) return;

        ConnectionConfig config = data.getConnectionConfig();
        String tabId = "kafka_consumers_" + config.getId();

        for (Tab tab : terminalTabPane.getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                terminalTabPane.getSelectionModel().select(tab);
                module.showDataView();
                return;
            }
        }

        VBox consumerContent = new VBox(0);
        consumerContent.setPadding(new Insets(8));

        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Button refreshBtn = new Button("刷新");
        refreshBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-font-size: 12px;");

        TextField filterField = new TextField();
        filterField.setPromptText("过滤消费者组...");
        filterField.setPrefWidth(200);
        filterField.setStyle("-fx-font-size: 12px;");

        Button deleteBtn = new Button("批量删除");
        deleteBtn.setStyle("-fx-font-size: 12px; -fx-text-fill: #cc0000;");

        toolbar.getChildren().addAll(refreshBtn, filterField, deleteBtn);

        TableView<ObservableList<String>> consumerTable = new TableView<>();
        consumerTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        consumerTable.setPlaceholder(new Label("无数据"));
        consumerTable.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);

        TableColumn<ObservableList<String>, String> groupCol = new TableColumn<>("消费者组");
        groupCol.setCellValueFactory(param -> new javafx.beans.property.SimpleStringProperty(param.getValue().get(0)));
        groupCol.setPrefWidth(400);

        TableColumn<ObservableList<String>, String> diffCol = new TableColumn<>("积压量");
        diffCol.setCellValueFactory(param -> new javafx.beans.property.SimpleStringProperty(param.getValue().get(1)));
        diffCol.setPrefWidth(200);

        consumerTable.getColumns().addAll(groupCol, diffCol);

        javafx.collections.ObservableList<ObservableList<String>> consumerAllData = javafx.collections.FXCollections.observableArrayList();
        javafx.collections.ObservableList<ObservableList<String>> consumerFilteredData = javafx.collections.FXCollections.observableArrayList();
        consumerTable.setItems(consumerFilteredData);

        filterField.textProperty().addListener((obs, oldVal, newVal) -> {
            consumerFilteredData.clear();
            String keyword = newVal == null ? "" : newVal.trim().toLowerCase();
            for (ObservableList<String> row : consumerAllData) {
                if (keyword.isEmpty() || row.get(0).toLowerCase().contains(keyword)) {
                    consumerFilteredData.add(row);
                }
            }
        });

        TextArea consumerDetailArea = new TextArea();
        consumerDetailArea.setPromptText("双击消费者组查看消费详情");
        consumerDetailArea.setPrefHeight(200);
        consumerDetailArea.setEditable(false);
        consumerDetailArea.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");

        consumerTable.setRowFactory(tv -> {
            TableRow<ObservableList<String>> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    String group = row.getItem().get(0);
                    loadConsumerDetailInTab(config, group, consumerDetailArea);
                }
            });
            return row;
        });

        Runnable loadConsumers = () -> {
            new Thread(() -> {
                try {
                    List<Map<String, Object>> consumers = KafkaService.getConsumerGroupList(config);
                    Platform.runLater(() -> {
                        consumerAllData.clear();
                        for (Map<String, Object> c : consumers) {
                            ObservableList<String> row = javafx.collections.FXCollections.observableArrayList();
                            row.add(String.valueOf(c.getOrDefault("group", "")));
                            row.add(String.valueOf(c.getOrDefault("diffTotal", "0")));
                            consumerAllData.add(row);
                        }
                        String keyword = filterField.getText() == null ? "" : filterField.getText().trim().toLowerCase();
                        consumerFilteredData.clear();
                        for (ObservableList<String> row : consumerAllData) {
                            if (keyword.isEmpty() || row.get(0).toLowerCase().contains(keyword)) {
                                consumerFilteredData.add(row);
                            }
                        }
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("加载失败");
                        alert.setHeaderText(null);
                        alert.setContentText("无法加载消费者组列表: " + e.getMessage());
                        showDialog(alert);
                    });
                }
            }, "Kafka-LoadConsumersTab").start();
        };

        refreshBtn.setOnAction(e -> loadConsumers.run());

        deleteBtn.setOnAction(e -> {
            javafx.collections.ObservableList<ObservableList<String>> selectedItems = consumerTable.getSelectionModel().getSelectedItems();
            if (selectedItems.isEmpty()) {
                Alert warn = new Alert(Alert.AlertType.WARNING);
                warn.setTitle("提示");
                warn.setHeaderText(null);
                warn.setContentText("请先选择要删除的消费者组（支持Ctrl/Shift多选）");
                showDialog(warn);
                return;
            }
            List<String> groups = new ArrayList<>();
            for (ObservableList<String> row : selectedItems) {
                groups.add(row.get(0));
            }
            String groupListStr = String.join("\n", groups);
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("确认批量删除");
            confirm.setHeaderText("删除 " + groups.size() + " 个消费者组");
            confirm.setContentText(groupListStr + "\n\n删除后不可恢复，确定要删除吗？");
            DialogPositionUtil.centerOnOwner(confirm, DialogPositionUtil.getMainWindow());
            confirm.showAndWait().ifPresent(btn -> {
                if (btn == ButtonType.OK) {
                    new Thread(() -> {
                        List<String> failed = new ArrayList<>();
                        for (String group : groups) {
                            try {
                                KafkaService.deleteConsumerGroup(config, group);
                            } catch (Exception ex) {
                                failed.add(group + ": " + ex.getMessage());
                            }
                        }
                        Platform.runLater(() -> {
                            if (failed.isEmpty()) {
                                Alert info = new Alert(Alert.AlertType.INFORMATION);
                                info.setTitle("成功");
                                info.setHeaderText(null);
                                info.setContentText("已删除 " + groups.size() + " 个消费者组");
                                showDialog(info);
                            } else {
                                Alert err = new Alert(Alert.AlertType.ERROR);
                                err.setTitle("部分删除失败");
                                err.setHeaderText(null);
                                TextArea area = new TextArea(String.join("\n", failed));
                                area.setEditable(false);
                                area.setWrapText(true);
                                area.setPrefRowCount(Math.min(failed.size() + 1, 10));
                                err.getDialogPane().setContent(area);
                                showDialog(err);
                            }
                            loadConsumers.run();
                        });
                    }, "Kafka-BatchDeleteConsumer").start();
                }
            });
        });

        consumerContent.getChildren().addAll(toolbar, consumerTable, new Label("消费详情:"), consumerDetailArea);
        VBox.setVgrow(consumerTable, Priority.ALWAYS);

        String tabTitle = "消费者组(" + config.getHost() + ":" + config.getPort() + ")";
        Tab tab = new Tab(tabTitle);

        try {
            Image kafkaIcon = new Image(getClass().getResourceAsStream("/images/connect/kafka.png"));
            ImageView tabIconView = new ImageView(kafkaIcon);
            tabIconView.setFitWidth(18);
            tabIconView.setFitHeight(18);
            tab.setGraphic(ConnectModule.createFixedSizeGraphic(tabIconView));
        } catch (Exception ignored) {}

        tab.setContent(consumerContent);
        tab.setUserData(tabId);
        tab.setOnClosed(e -> {
            if (terminalTabPane.getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        module.showDataView();

        loadConsumers.run();
        loadConsumersForFolder(module, item, config);
        item.setExpanded(true);
    }

    /** 加载消费者组子节点到树中 */
    void loadConsumersForFolder(ConnectModule module, TreeItem<String> folderItem, ConnectionConfig config) {
        new Thread(() -> {
            try {
                List<Map<String, Object>> consumers = KafkaService.getConsumerGroupList(config);
                Platform.runLater(() -> {
                    folderItem.getChildren().clear();
                    for (Map<String, Object> c : consumers) {
                        String group = String.valueOf(c.getOrDefault("group", ""));
                        TreeItem<String> consumerItem = new TreeItem<>(group);
                        consumerItem.setGraphic(module.getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.KAFKA_CONSUMER, group, config, "")));
                        module.getDbNodeDataMap().put(consumerItem, new DatabaseNodeData(DatabaseNodeData.NodeType.KAFKA_CONSUMER, group, config, ""));
                        folderItem.getChildren().add(consumerItem);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载消费者组列表: " + e.getMessage());
                    showDialog(alert);
                });
            }
        }, "Kafka-LoadConsumers").start();
    }

    /** 在标签页中加载指定消费者组的消费详情 */
    private void loadConsumerDetailInTab(ConnectionConfig config, String group, TextArea detailArea) {
        new Thread(() -> {
            try {
                Map<String, Object> detail = KafkaService.getConsumerGroupDetail(config, group);
                StringBuilder sb = new StringBuilder();
                sb.append("消费者组: ").append(group).append("\n");
                sb.append("状态: ").append(detail.getOrDefault("state", "UNKNOWN")).append("\n");
                sb.append("成员数: ").append(detail.getOrDefault("members", 0)).append("\n");
                sb.append("总积压量: ").append(detail.getOrDefault("totalDiff", 0)).append("\n\n");

                @SuppressWarnings("unchecked")
                List<Map<String, Object>> offsetList = (List<Map<String, Object>>) detail.get("offsetTable");
                if (offsetList != null) {
                    sb.append("消费偏移详情:\n");
                    for (Map<String, Object> offset : offsetList) {
                        sb.append("  Topic: ").append(offset.getOrDefault("topic", ""))
                          .append(" | Partition: ").append(offset.getOrDefault("partition", ""))
                          .append(" | BrokerOffset: ").append(offset.getOrDefault("brokerOffset", ""))
                          .append(" | ConsumerOffset: ").append(offset.getOrDefault("consumerOffset", ""))
                          .append(" | Diff: ").append(offset.getOrDefault("diff", ""))
                          .append("\n");
                    }
                }
                if (detail.containsKey("warning")) {
                    sb.append("\n⚠️ ").append(detail.get("warning")).append("\n");
                }
                Platform.runLater(() -> detailArea.setText(sb.toString()));
            } catch (Exception e) {
                Platform.runLater(() -> detailArea.setText("加载消费详情失败: " + e.getMessage()));
            }
        }, "Kafka-ConsumerDetail").start();
    }

    /** 刷新 Kafka 主机 */
    public void refreshHost(ConnectModule module, TreeItem<String> hostItem, ConnectionConfig config) {
        for (TreeItem<String> child : hostItem.getChildren()) {
            module.removeDbNodeDataRecursive(child);
        }
        hostItem.getChildren().clear();
        module.triggerHostDoubleClick(hostItem, config);
    }

    /** 刷新 Kafka folder 节点 */
    public void refreshDbNode(ConnectModule module, TreeItem<String> item, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();
        item.getChildren().clear();
        switch (data.getType()) {
            case KAFKA_TOPICS_FOLDER -> loadTopicsForFolder(module, item, config);
            case KAFKA_CONSUMERS_FOLDER -> loadConsumersForFolder(module, item, config);
            case KAFKA_CLUSTER_FOLDER -> loadClusterForFolder(module, item, config);
            default -> {}
        }
    }

    /** 加载主题列表到 folder 节点 */
    public void loadTopicsForFolder(ConnectModule module, TreeItem<String> folderItem, ConnectionConfig config) {
        new Thread(() -> {
            try {
                List<Map<String, Object>> topics = KafkaService.getTopicList(config);
                Platform.runLater(() -> {
                    folderItem.getChildren().clear();
                    for (Map<String, Object> t : topics) {
                        String name = String.valueOf(t.getOrDefault("topic", ""));
                        if (name.startsWith("__")) continue;
                        TreeItem<String> topicItem = new TreeItem<>(name);
                        topicItem.setGraphic(module.getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.KAFKA_TOPIC, name, config, "")));
                        module.getDbNodeDataMap().put(topicItem, new DatabaseNodeData(DatabaseNodeData.NodeType.KAFKA_TOPIC, name, config, ""));
                        folderItem.getChildren().add(topicItem);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载主题列表: " + e.getMessage());
                    showDialog(alert);
                });
            }
        }, "Kafka-LoadTopics").start();
    }

    /** 加载集群信息到 folder 节点 */
    public void loadClusterForFolder(ConnectModule module, TreeItem<String> folderItem, ConnectionConfig config) {
        new Thread(() -> {
            try {
                List<Map<String, Object>> cluster = KafkaService.getClusterInfo(config);
                Platform.runLater(() -> {
                    folderItem.getChildren().clear();
                    for (Map<String, Object> c : cluster) {
                        String brokerName = String.valueOf(c.getOrDefault("brokerName", ""));
                        String address = String.valueOf(c.getOrDefault("address", ""));
                        String displayName = brokerName + " (" + address + ")";
                        TreeItem<String> brokerItem = new TreeItem<>(displayName);
                        brokerItem.setGraphic(module.getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.KAFKA_BROKER, displayName, config, "")));
                        module.getDbNodeDataMap().put(brokerItem, new DatabaseNodeData(DatabaseNodeData.NodeType.KAFKA_BROKER, displayName, config, ""));
                        folderItem.getChildren().add(brokerItem);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载集群信息: " + e.getMessage());
                    showDialog(alert);
                });
            }
        }, "Kafka-LoadCluster").start();
    }

    /** 双击"集群"folder节点：创建/选中"集群"一级标签，并加载子节点到树。 */
    public void handleClusterFolderDoubleClick(ConnectModule module, TreeItem<String> item, DatabaseNodeData data) {
        javafx.scene.control.TabPane terminalTabPane = module.getTerminalTabPane();
        if (terminalTabPane == null) return;
        if (!module.ensureTabPaneInstalled()) return;

        ConnectionConfig config = data.getConnectionConfig();
        String tabId = "kafka_cluster_" + config.getId();

        for (javafx.scene.control.Tab tab : terminalTabPane.getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                terminalTabPane.getSelectionModel().select(tab);
                module.showDataView();
                return;
            }
        }

        VBox clusterContent = new VBox(0);
        clusterContent.setPadding(new Insets(8));

        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Button refreshBtn = new Button("刷新");
        refreshBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-font-size: 12px;");

        toolbar.getChildren().add(refreshBtn);

        TableView<ObservableList<String>> clusterTable = new TableView<>();
        clusterTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        clusterTable.setPlaceholder(new Label("无数据"));

        TableColumn<ObservableList<String>, String> brokerIdCol = new TableColumn<>("BrokerId");
        brokerIdCol.setCellValueFactory(param -> new javafx.beans.property.SimpleStringProperty(param.getValue().get(0)));
        brokerIdCol.setPrefWidth(100);

        TableColumn<ObservableList<String>, String> brokerNameCol = new TableColumn<>("BrokerName");
        brokerNameCol.setCellValueFactory(param -> new javafx.beans.property.SimpleStringProperty(param.getValue().get(1)));
        brokerNameCol.setPrefWidth(200);

        TableColumn<ObservableList<String>, String> addressCol = new TableColumn<>("地址");
        addressCol.setCellValueFactory(param -> new javafx.beans.property.SimpleStringProperty(param.getValue().get(2)));
        addressCol.setPrefWidth(250);

        TableColumn<ObservableList<String>, String> rackCol = new TableColumn<>("Rack");
        rackCol.setCellValueFactory(param -> new javafx.beans.property.SimpleStringProperty(param.getValue().get(3)));
        rackCol.setPrefWidth(120);

        TableColumn<ObservableList<String>, String> roleCol = new TableColumn<>("角色");
        roleCol.setCellValueFactory(param -> new javafx.beans.property.SimpleStringProperty(param.getValue().get(4)));
        roleCol.setPrefWidth(120);

        clusterTable.getColumns().addAll(brokerIdCol, brokerNameCol, addressCol, rackCol, roleCol);

        javafx.collections.ObservableList<ObservableList<String>> clusterData = javafx.collections.FXCollections.observableArrayList();

        Runnable loadCluster = () -> {
            new Thread(() -> {
                try {
                    List<Map<String, Object>> cluster = KafkaService.getClusterInfo(config);
                    Platform.runLater(() -> {
                        clusterData.clear();
                        for (Map<String, Object> c : cluster) {
                            ObservableList<String> row = javafx.collections.FXCollections.observableArrayList();
                            row.add(String.valueOf(c.getOrDefault("brokerId", "")));
                            row.add(String.valueOf(c.getOrDefault("brokerName", "")));
                            row.add(String.valueOf(c.getOrDefault("address", "")));
                            row.add(String.valueOf(c.getOrDefault("rack", "")));
                            row.add(String.valueOf(c.getOrDefault("role", "")));
                            clusterData.add(row);
                        }
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("加载失败");
                        alert.setHeaderText(null);
                        alert.setContentText("无法加载集群信息: " + e.getMessage());
                        showDialog(alert);
                    });
                }
            }, "Kafka-LoadClusterTab").start();
        };

        refreshBtn.setOnAction(e -> loadCluster.run());
        clusterTable.setItems(clusterData);

        clusterContent.getChildren().addAll(toolbar, clusterTable);
        VBox.setVgrow(clusterTable, Priority.ALWAYS);

        String tabTitle = "集群(" + config.getHost() + ":" + config.getPort() + ")";
        javafx.scene.control.Tab tab = new javafx.scene.control.Tab(tabTitle);

        try {
            Image kafkaIcon = new Image(getClass().getResourceAsStream("/images/connect/kafka.png"));
            ImageView tabIconView = new ImageView(kafkaIcon);
            tabIconView.setFitWidth(18);
            tabIconView.setFitHeight(18);
            tab.setGraphic(ConnectModule.createFixedSizeGraphic(tabIconView));
        } catch (Exception ignored) {}

        tab.setContent(clusterContent);
        tab.setUserData(tabId);
        tab.setOnClosed(e -> {
            if (terminalTabPane.getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        module.showDataView();

        loadCluster.run();
        loadClusterForFolder(module, item, config);
        item.setExpanded(true);
    }

    /** 双击"主题"folder节点：若已加载则切换展开状态，否则加载主题列表 */
    public void handleTopicsFolderDoubleClick(ConnectModule module, TreeItem<String> folderItem, DatabaseNodeData data) {
        if (!folderItem.getChildren().isEmpty()) {
            folderItem.setExpanded(!folderItem.isExpanded());
            return;
        }
        loadTopicsForFolder(module, folderItem, data.getConnectionConfig());
        folderItem.setExpanded(true);
    }

    /** 双击主题节点：打开主题详情 Tab */
    public void handleTopicDoubleClick(ConnectModule module, TreeItem<String> item, DatabaseNodeData data) {
        javafx.scene.control.TabPane terminalTabPane = module.getTerminalTabPane();
        if (terminalTabPane == null) return;
        if (!module.ensureTabPaneInstalled()) return;

        ConnectionConfig config = data.getConnectionConfig();
        String topicName = data.getName();
        String tabId = "kafka_topic_" + config.getId() + "_" + topicName;

        for (javafx.scene.control.Tab tab : terminalTabPane.getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                terminalTabPane.getSelectionModel().select(tab);
                module.showDataView();
                return;
            }
        }

        KafkaDataView dataView = new KafkaDataView(config, topicName);
        dataView.selectTopicTab(topicName);

        String tabTitle = topicName + "(" + config.getHost() + ":" + config.getPort() + ")";
        javafx.scene.control.Tab tab = new javafx.scene.control.Tab(tabTitle);

        try {
            Image kafkaIcon = new Image(getClass().getResourceAsStream("/images/connect/kafka.png"));
            ImageView tabIconView = new ImageView(kafkaIcon);
            tabIconView.setFitWidth(18);
            tabIconView.setFitHeight(18);
            tab.setGraphic(ConnectModule.createFixedSizeGraphic(tabIconView));
        } catch (Exception ignored) {}

        tab.setContent(dataView);
        tab.setUserData(tabId);
        tab.setOnClosed(e -> {
            if (terminalTabPane.getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        module.showDataView();
    }

    /** 右键"发送消息"：打开主题详情 Tab 并切换到发送消息子标签 */
    public void handleSendTopic(ConnectModule module, TreeItem<String> item, DatabaseNodeData data) {
        javafx.scene.control.TabPane terminalTabPane = module.getTerminalTabPane();
        if (terminalTabPane == null) return;
        if (!module.ensureTabPaneInstalled()) return;

        ConnectionConfig config = data.getConnectionConfig();
        String topicName = data.getName();
        String tabId = "kafka_topic_" + config.getId() + "_" + topicName;

        for (javafx.scene.control.Tab tab : terminalTabPane.getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                if (tab.getContent() instanceof KafkaDataView) {
                    ((KafkaDataView) tab.getContent()).selectSendTab(topicName);
                }
                terminalTabPane.getSelectionModel().select(tab);
                module.showDataView();
                return;
            }
        }

        KafkaDataView dataView = new KafkaDataView(config, topicName);
        dataView.selectSendTab(topicName);

        String tabTitle = topicName + "(" + config.getHost() + ":" + config.getPort() + ")";
        javafx.scene.control.Tab tab = new javafx.scene.control.Tab(tabTitle);

        try {
            Image kafkaIcon = new Image(getClass().getResourceAsStream("/images/connect/kafka.png"));
            ImageView tabIconView = new ImageView(kafkaIcon);
            tabIconView.setFitWidth(18);
            tabIconView.setFitHeight(18);
            tab.setGraphic(ConnectModule.createFixedSizeGraphic(tabIconView));
        } catch (Exception ignored) {}

        tab.setContent(dataView);
        tab.setUserData(tabId);
        tab.setOnClosed(e -> {
            if (terminalTabPane.getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        module.showDataView();
    }

    /** 双击消费者组节点：打开消费者组一级标签 */
    public void handleConsumerDoubleClick(ConnectModule module, TreeItem<String> item, DatabaseNodeData data) {
        TreeItem<String> parent = item.getParent();
        if (parent != null) {
            DatabaseNodeData parentData = module.getDbNodeDataMap().get(parent);
            if (parentData != null) {
                handleConsumersFolderDoubleClick(module, parent, parentData);
                return;
            }
        }
        javafx.scene.control.TabPane terminalTabPane = module.getTerminalTabPane();
        if (terminalTabPane == null) return;
        if (!module.ensureTabPaneInstalled()) return;

        ConnectionConfig config = data.getConnectionConfig();
        String tabId = "kafka_consumers_" + config.getId();
        for (javafx.scene.control.Tab tab : terminalTabPane.getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                terminalTabPane.getSelectionModel().select(tab);
                module.showDataView();
                return;
            }
        }
    }

    /** 双击 Broker 节点：打开集群一级标签 */
    public void handleBrokerDoubleClick(ConnectModule module, TreeItem<String> item, DatabaseNodeData data) {
        TreeItem<String> parent = item.getParent();
        if (parent != null) {
            DatabaseNodeData parentData = module.getDbNodeDataMap().get(parent);
            if (parentData != null) {
                handleClusterFolderDoubleClick(module, parent, parentData);
                return;
            }
        }
        javafx.scene.control.TabPane terminalTabPane = module.getTerminalTabPane();
        if (terminalTabPane == null) return;
        if (!module.ensureTabPaneInstalled()) return;

        ConnectionConfig config = data.getConnectionConfig();
        String tabId = "kafka_cluster_" + config.getId();
        for (javafx.scene.control.Tab tab : terminalTabPane.getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                terminalTabPane.getSelectionModel().select(tab);
                module.showDataView();
                return;
            }
        }
    }

    /** 创建主题：弹出对话框收集主题名/分区数/副本数，调用 KafkaService.createTopic，成功后刷新主题列表 */
    public void handleCreateTopic(ConnectModule module, TreeItem<String> folderItem, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("创建 Kafka 主题");
        dialog.setHeaderText("在 " + config.getHost() + ":" + config.getPort() + " 上创建新的主题");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 20, 10, 20));

        TextField topicField = new TextField();
        topicField.setPromptText("如：order-events");
        topicField.setPrefWidth(280);

        Spinner<Integer> partitionsSpinner = new Spinner<>();
        partitionsSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 100, 1));
        partitionsSpinner.setEditable(false);
        partitionsSpinner.setPrefWidth(80);

        Spinner<Integer> replicationSpinner = new Spinner<>();
        replicationSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 10, 1));
        replicationSpinner.setEditable(false);
        replicationSpinner.setPrefWidth(80);

        grid.add(new Label("主题名:"), 0, 0);
        grid.add(topicField, 1, 0);
        grid.add(new Label("分区数:"), 0, 1);
        grid.add(partitionsSpinner, 1, 1);
        grid.add(new Label("副本数:"), 0, 2);
        grid.add(replicationSpinner, 1, 2);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        final Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);
        okButton.setText("创建");
        final Button cancelButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        cancelButton.setText("取消");
        topicField.textProperty().addListener((obs, oldVal, newVal) ->
                okButton.setDisable(newVal == null || newVal.trim().isEmpty()));

        Platform.runLater(topicField::requestFocus);

        DialogPositionUtil.centerOnOwner(dialog, DialogPositionUtil.getMainWindow());
        dialog.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                String topic = topicField.getText().trim();
                int partitions = partitionsSpinner.getValue();
                int replication = replicationSpinner.getValue();
                new Thread(() -> {
                    try {
                        KafkaService.createTopic(config, topic, partitions, (short) replication);
                        Platform.runLater(() -> {
                            Alert info = new Alert(Alert.AlertType.INFORMATION);
                            info.setTitle("成功");
                            info.setHeaderText(null);
                            info.setContentText("主题 " + topic + " 已创建");
                            showDialog(info);
                            loadTopicsForFolder(module, folderItem, config);
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setTitle("创建失败");
                            alert.setHeaderText(null);
                            alert.setContentText("创建主题失败: " + e.getMessage());
                            showDialog(alert);
                        });
                    }
                }, "Kafka-CreateTopic").start();
            }
        });
    }

    /** 删除主题节点 */
    public void handleDeleteTopic(ConnectModule module, TreeItem<String> item, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();
        String topicName = data.getName();

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认删除");
        confirm.setHeaderText("删除主题: " + topicName);
        confirm.setContentText("删除后不可恢复，确定要删除吗？");
        DialogPositionUtil.centerOnOwner(confirm, DialogPositionUtil.getMainWindow());
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                new Thread(() -> {
                    try {
                        KafkaService.deleteTopic(config, topicName);
                        Platform.runLater(() -> {
                            Alert info = new Alert(Alert.AlertType.INFORMATION);
                            info.setTitle("成功");
                            info.setHeaderText(null);
                            info.setContentText("主题 " + topicName + " 已删除");
                            showDialog(info);
                            TreeItem<String> parent = item.getParent();
                            if (parent != null) {
                                parent.getChildren().remove(item);
                                module.getDbNodeDataMap().remove(item);
                            }
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setTitle("删除失败");
                            alert.setHeaderText(null);
                            alert.setContentText("删除主题失败: " + e.getMessage());
                            showDialog(alert);
                        });
                    }
                }, "Kafka-DeleteTopic").start();
            }
        });
    }

    /** 构建 Kafka 节点右键菜单 */
    @Override
    public void populateNodeContextMenu(ConnectModule module, ContextMenu contextMenu, TreeItem<String> item, DatabaseNodeData data) {
        switch (data.getType()) {
            case KAFKA_TOPICS_FOLDER -> {
                MenuItem createItem = new MenuItem("创建主题");
                createItem.setOnAction(e -> handleCreateTopic(module, item, data));
                MenuItem refreshItem = new MenuItem("刷新");
                refreshItem.setOnAction(e -> module.refreshDbNode(item, data));
                contextMenu.getItems().addAll(createItem, new SeparatorMenuItem(), refreshItem);
            }
            case KAFKA_CONSUMERS_FOLDER, KAFKA_CLUSTER_FOLDER -> {
                MenuItem refreshItem = new MenuItem("刷新");
                refreshItem.setOnAction(e -> module.refreshDbNode(item, data));
                contextMenu.getItems().add(refreshItem);
            }
            case KAFKA_TOPIC -> {
                MenuItem openItem = new MenuItem("查看详情");
                openItem.setOnAction(e -> handleTopicDoubleClick(module, item, data));
                MenuItem sendItem = new MenuItem("发送消息");
                sendItem.setOnAction(e -> handleSendTopic(module, item, data));
                MenuItem deleteItem = new MenuItem("删除主题");
                deleteItem.setOnAction(e -> handleDeleteTopic(module, item, data));
                contextMenu.getItems().addAll(openItem, sendItem, new SeparatorMenuItem(), deleteItem);
            }
            case KAFKA_CONSUMER -> {
                MenuItem openItem = new MenuItem("查看详情");
                openItem.setOnAction(e -> handleConsumerDoubleClick(module, item, data));
                contextMenu.getItems().add(openItem);
            }
            case KAFKA_BROKER -> {
                MenuItem openItem = new MenuItem("查看详情");
                openItem.setOnAction(e -> handleBrokerDoubleClick(module, item, data));
                contextMenu.getItems().add(openItem);
            }
            default -> {}
        }
    }

    /** 在主窗口所在屏幕居中显示对话框（多屏环境下避免弹窗总出现在主屏） */
    private static void showDialog(Dialog<?> dialog) {
        DialogPositionUtil.centerOnOwner(dialog, DialogPositionUtil.getMainWindow());
        dialog.showAndWait();
    }
}
