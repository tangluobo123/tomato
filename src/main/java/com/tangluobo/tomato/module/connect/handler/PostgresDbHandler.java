package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.*;
import com.tangluobo.tomato.module.connect.service.DatabaseService;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.TreeItem;

import java.util.List;

/**
 * PostgreSQL 数据库处理器。
 * - openDatabase: 数据库节点下加载模式(schema)节点列表（动态获取）
 * - 模式节点下再加载表/视图/函数/查询/备份 5 个文件夹
 * - 支持 schema 层级
 */
public class PostgresDbHandler extends AbstractDbHandler {

    public PostgresDbHandler(ConnectModule module) {
        super(module);
    }

    @Override
    public ConnectType getConnectType() {
        return ConnectType.POSTGRESQL;
    }

    /**
     * 打开 PostgreSQL 数据库节点：加载模式(schema)列表
     */
    @Override
    public void openDatabase(TreeItem<String> dbItem, DatabaseNodeData data) {
        data.setOpened(true);
        dbItem.setGraphic(module.getDbNodeIcon(data));
        dbItem.setExpanded(true);
        loadSchemasForDatabase(dbItem, data.getConnectionConfig(), data.getDatabaseName());
    }

    @Override
    public boolean supportsSchema() {
        return true;
    }

    /**
     * 双击模式节点：加载表/视图/函数/查询/备份 5 个文件夹
     */
    @Override
    public void handleSchemaDoubleClick(TreeItem<String> schemaItem, DatabaseNodeData data) {
        if (!data.isOpened()) {
            data.setOpened(true);
            schemaItem.setGraphic(module.getDbNodeIcon(data));

            ConnectionConfig config = data.getConnectionConfig();
            String dbName = data.getDatabaseName();
            String schemaName = data.getSchemaName();

            buildSchemaFolders(schemaItem, config, dbName, schemaName);
            schemaItem.setExpanded(true);

            loadTablesAndViewsForFolder(schemaItem.getChildren().get(0), schemaItem.getChildren().get(1), config, dbName, schemaName, true);
        } else {
            schemaItem.setExpanded(!schemaItem.isExpanded());
        }
    }

    /**
     * 刷新模式节点：重置后重新加载
     */
    @Override
    public void refreshSchema(TreeItem<String> schemaItem, DatabaseNodeData data) {
        module.removeDbNodeDataRecursive(schemaItem);
        schemaItem.getChildren().clear();
        data.setOpened(false);
        schemaItem.setGraphic(module.getDbNodeIcon(data));
        handleSchemaDoubleClick(schemaItem, data);
    }

    /**
     * PostgreSQL 主机图标更新：使用通用逻辑
     */
    @Override
    public void updateHostIcon(TreeItem<String> hostItem, ConnectionConfig config, boolean connected) {
        module.updateHostIconGeneric(hostItem, config, connected);
    }

    /**
     * 加载数据库下的模式(schema)列表
     */
    private void loadSchemasForDatabase(TreeItem<String> dbItem, ConnectionConfig config, String dbName) {
        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, dbName);
            connLock.lock();
            try {
                List<String> schemas = DatabaseService.getSchemas(config, dbName);
                Platform.runLater(() -> {
                    dbItem.getChildren().clear();
                    for (String schemaName : schemas) {
                        TreeItem<String> schemaItem = new TreeItem<>(schemaName);
                        DatabaseNodeData schemaData = new DatabaseNodeData(DatabaseNodeData.NodeType.SCHEMA, schemaName, config, dbName, schemaName);
                        schemaItem.setGraphic(module.getDbNodeIcon(schemaData));
                        module.putDbNodeData(schemaItem, schemaData);
                        dbItem.getChildren().add(schemaItem);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载模式列表: " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            } finally {
                connLock.unlock();
            }
        }, "PG-LoadSchemas").start();
    }
}
