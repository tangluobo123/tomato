package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.*;
import com.tangluobo.tomato.module.connect.dialog.PasswordPromptDialog;
import com.tangluobo.tomato.module.connect.service.RedisService;
import com.tangluobo.tomato.module.connect.view.RedisDataView;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.util.List;

/**
 * Redis 连接处理器
 */
public class RedisConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.REDIS;
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

        if (config.getPassword() == null) {
            PasswordPromptDialog.Result pwdResult = PasswordPromptDialog.show(
                    "输入密码",
                    config.getName() + " (" + config.getHost() + ":" + config.getPort() + ")",
                    "密码：", null, "保存密码");
            if (pwdResult == null || pwdResult.getPassword() == null || pwdResult.getPassword().isEmpty()) return;
            config.setPassword(pwdResult.getPassword());
            if (pwdResult.isSavePassword()) {
                config.setSavePassword(true);
                module.saveConnections();
            }
        }

        ProgressIndicator loadingIndicator = new ProgressIndicator();
        loadingIndicator.setPrefSize(16, 16);
        loadingIndicator.setMaxSize(16, 16);
        loadingIndicator.setStyle("-fx-progress-color: #4CAF50;");
        hostItem.setGraphic(loadingIndicator);

        new Thread(() -> {
            try {
                List<String> databases = RedisService.getDatabases(config);
                Platform.runLater(() -> {
                    module.updateHostIcon(hostItem, config, true);

                    hostItem.getChildren().clear();
                    for (String dbIndex : databases) {
                        String dbName = "db" + dbIndex;
                        TreeItem<String> dbItem = new TreeItem<>(dbName);
                        DatabaseNodeData data = new DatabaseNodeData(DatabaseNodeData.NodeType.REDIS_DB, dbName, config, dbName);
                        dbItem.setGraphic(module.getDbNodeIcon(data));
                        module.getDbNodeDataMap().put(dbItem, data);
                        hostItem.getChildren().add(dbItem);
                    }
                    hostItem.setExpanded(true);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    hostItem.setGraphic(module.getIconForConfig(config));
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("连接失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法连接到Redis " + config.getName() + ": " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
        }, "Redis-LoadDatabases").start();
    }

    /** 刷新 Redis 主机：清空子节点并重新触发双击连接 */
    public void refreshHost(ConnectModule module, TreeItem<String> hostItem, ConnectionConfig config) {
        for (TreeItem<String> child : hostItem.getChildren()) {
            module.removeDbNodeDataRecursive(child);
        }
        hostItem.getChildren().clear();
        module.triggerHostDoubleClick(hostItem, config);
    }

    /** 双击 Redis 数据库节点：打开 Redis 数据 Tab */
    public void handleRedisDbDoubleClick(ConnectModule module, TreeItem<String> dbItem, DatabaseNodeData data) {
        if (module.getTerminalTabPane() == null) return;
        if (!module.ensureTabPaneInstalled()) return;

        String dbName = data.getDatabaseName();
        int dbIndex = 0;
        if (dbName.startsWith("db")) {
            try {
                dbIndex = Integer.parseInt(dbName.substring(2));
            } catch (NumberFormatException ignored) {}
        }

        String tabId = "redis_" + data.getConnectionConfig().getId() + "_" + dbName;
        for (Tab tab : module.getTerminalTabPane().getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(tab);
                module.showDataView();
                return;
            }
        }

        RedisDataView dataView = new RedisDataView(data.getConnectionConfig(), dbIndex);

        ConnectionConfig config = data.getConnectionConfig();
        String tabTitle = dbName + "(" + config.getHost() + ":" + config.getPort() + ")-Redis";
        Tab tab = new Tab(tabTitle);

        try {
            Image redisIcon = new Image(getClass().getResourceAsStream("/images/connect/redis.png"));
            ImageView tabIconView = new ImageView(redisIcon);
            tabIconView.setFitWidth(18);
            tabIconView.setFitHeight(18);
            tab.setGraphic(ConnectModule.createFixedSizeGraphic(tabIconView));
        } catch (Exception ignored) {}

        tab.setContent(dataView);
        tab.setUserData(tabId);

        ContextMenu redisTabContextMenu = new ContextMenu();
        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> dataView.loadKeyTree());
        redisTabContextMenu.getItems().add(refreshItem);
        tab.setContextMenu(redisTabContextMenu);

        tab.setOnClosed(e -> {
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showDataView();
    }

    /** 构建 Redis 节点右键菜单：REDIS_DB 提供"打开" */
    @Override
    public void populateNodeContextMenu(ConnectModule module, ContextMenu contextMenu, TreeItem<String> item, DatabaseNodeData data) {
        if (data.getType() == DatabaseNodeData.NodeType.REDIS_DB) {
            MenuItem openItem = new MenuItem("打开");
            openItem.setOnAction(e -> handleRedisDbDoubleClick(module, item, data));
            contextMenu.getItems().add(openItem);
        }
    }
}
