package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.*;
import com.tangluobo.tomato.module.connect.dialog.CopyTableDialog;
import com.tangluobo.tomato.module.connect.dialog.CreateDatabaseDialog;
import com.tangluobo.tomato.module.connect.dialog.EditDatabaseDialog;
import com.tangluobo.tomato.module.connect.dialog.PasswordPromptDialog;
import com.tangluobo.tomato.module.connect.dialog.GlobalConfigDialog;
import com.tangluobo.tomato.module.connect.dialog.RestoreDialog;
import com.tangluobo.tomato.module.connect.service.BackupService;
import com.tangluobo.tomato.module.connect.service.DatabaseService;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import com.tangluobo.tomato.module.connect.view.SqlEditorView;
import com.tangluobo.tomato.module.connect.view.TableDataView;
import com.tangluobo.tomato.module.connect.view.TableStructureView;
import com.tangluobo.tomato.module.connect.view.TableObjectsView;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.event.Event;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeItem;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据库连接处理器抽象基类。
 * 封装 MySQL/PostgreSQL/Oracle 等关系型数据库在连接树中的公共行为，
 * 差异点（数据库节点展开方式、主机图标更新、模式加载等）由子类实现。
 *
 * 设计说明：
 * - 公共逻辑（加载数据库列表、表/视图文件夹、新建表/查询、删除节点等）放在此基类
 * - 抽象方法定义差异点，由 MysqlDbHandler/PostgresDbHandler/OracleDbHandler 实现
 * - 通过持有的 ConnectModule 引用访问共享 UI 状态（树、tab 面板、图标等）
 * - 实现 ConnectHandler 接口，使 handleConnect 也可通过统一分发机制调用
 */
public abstract class AbstractDbHandler implements ConnectHandler {

    /** 关联的连接模块，提供共享 UI 状态与公共回调 */
    protected final ConnectModule module;

    protected AbstractDbHandler(ConnectModule module) {
        this.module = module;
    }

    /**
     * 此处理器对应的数据库连接类型
     */
    public abstract ConnectType getConnectType();

    // ==================== 抽象方法：差异点 ====================

    /**
     * 打开数据库节点：展开下级目录结构。
     * PostgreSQL 实现为加载模式(schema)节点；MySQL/Oracle 实现为直接加载表/视图/函数/查询/备份文件夹。
     */
    public abstract void openDatabase(TreeItem<String> dbItem, DatabaseNodeData data);

    /**
     * 更新主机节点图标（根据连接状态）。
     * MySQL 有特殊的图标更新逻辑；其他数据库使用通用逻辑。
     */
    public abstract void updateHostIcon(TreeItem<String> hostItem, ConnectionConfig config, boolean connected);

    /**
     * 是否支持模式(schema)层级。
     * PostgreSQL 返回 true（数据库→模式→表）；MySQL/Oracle 返回 false。
     */
    public boolean supportsSchema() {
        return false;
    }

    /**
     * 双击模式节点处理。仅 PostgreSQL 实现；其他数据库默认空操作。
     */
    public void handleSchemaDoubleClick(TreeItem<String> schemaItem, DatabaseNodeData data) {
        // 默认无操作：MySQL/Oracle 无 schema 层级
    }

    /**
     * 刷新模式节点。仅 PostgreSQL 实现；其他数据库默认空操作。
     */
    public void refreshSchema(TreeItem<String> schemaItem, DatabaseNodeData data) {
        // 默认无操作
    }

    // ==================== 公共方法 ====================

    /**
     * 双击主机节点：加载数据库列表。
     * MySQL/PostgreSQL/Oracle 逻辑一致：密码输入 → 后台调 DatabaseService.getDatabases → 填充数据库节点。
     */
    public void handleHostDoubleClick(TreeItem<String> hostItem, ConnectionConfig config) {
        doHandleDbHostDoubleClick(hostItem, config);
    }

    /**
     * 执行数据库主机连接的公共流程（密码输入 → 加载数据库列表 → 填充节点）。
     * MySQL/PostgreSQL/Oracle 逻辑一致。
     */
    void doHandleDbHostDoubleClick(TreeItem<String> hostItem, ConnectionConfig config) {
        if (module.getConnectingHosts().contains(hostItem)) {
            return;
        }
        if (!hostItem.getChildren().isEmpty()) {
            hostItem.setExpanded(!hostItem.isExpanded());
            return;
        }

        if (config.getPassword() == null) {
            PasswordPromptDialog.Result pwdResult = PasswordPromptDialog.show(
                    "输入密码",
                    config.getName() + " (" + config.getUsername() + "@" + config.getHost() + ")",
                    "密码：", null, "保存密码");
            if (pwdResult == null || pwdResult.getPassword() == null || pwdResult.getPassword().isEmpty()) return;
            config.setPassword(pwdResult.getPassword());
            if (pwdResult.isSavePassword()) {
                config.setSavePassword(true);
                module.saveConnections();
            }
        }

        module.getConnectingHosts().add(hostItem);

        ProgressIndicator loadingIndicator = new ProgressIndicator();
        loadingIndicator.setPrefSize(16, 16);
        loadingIndicator.setMaxSize(16, 16);
        loadingIndicator.setStyle("-fx-progress-color: #4CAF50;");
        hostItem.setGraphic(loadingIndicator);
        module.getTreeView().refresh();

        new Thread(() -> {
            try {
                List<String> databases = DatabaseService.getDatabases(config);
                Platform.runLater(() -> {
                    module.getConnectingHosts().remove(hostItem);
                    module.markConnectionState(hostItem, true);
                    updateHostIcon(hostItem, config, true);

                    hostItem.getChildren().clear();
                    for (String dbName : databases) {
                        TreeItem<String> dbItem = new TreeItem<>(dbName);
                        DatabaseNodeData data = new DatabaseNodeData(DatabaseNodeData.NodeType.DATABASE, dbName, config, dbName);
                        dbItem.setGraphic(module.getDbNodeIcon(data));
                        module.getDbNodeDataMap().put(dbItem, data);
                        hostItem.getChildren().add(dbItem);
                    }
                    hostItem.setExpanded(true);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    module.getConnectingHosts().remove(hostItem);
                    hostItem.setGraphic(module.getIconForConfig(config));
                    module.getTreeView().refresh();
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("连接失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法连接到 " + config.getName() + ": " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
        }, "DB-LoadDatabases").start();
    }

    /**
     * 关闭主机连接：释放 JDBC 连接资源。
     * 关系型数据库统一调用 DatabaseService.closeConnection。
     */
    public void closeConnection(ConnectionConfig config) {
        try {
            DatabaseService.closeConnection(config.getId());
        } catch (Exception ignored) {
        }
    }

    /**
     * 判断给定的连接配置是否由本处理器处理
     */
    public boolean handles(ConnectionConfig config) {
        return config.getType() == getConnectType();
    }

    // ==================== ConnectHandler 接口实现 ====================

    @Override
    public boolean supports(ConnectType type) {
        return type == getConnectType();
    }

    /**
     * 执行连接：找到主机树节点并触发双击连接流程
     */
    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        TreeItem<String> hostItem = module.findItemById(module.getRoot(), config.getId());
        if (hostItem != null) {
            handleHostDoubleClick(hostItem, config);
        }
    }

    /**
     * 接口方法：双击主机节点。
     * 委托给本类已有的 handleHostDoubleClick(hostItem, config)（持有 module 引用，无需重复查找）。
     */
    @Override
    public void handleHostDoubleClick(ConnectModule module, TreeItem<String> hostItem, ConnectionConfig config) {
        handleHostDoubleClick(hostItem, config);
    }

    // ==================== 新建/编辑/删除数据库 ====================

    /** 新建数据库 */
    public void handleCreateDatabase(TreeItem<String> hostItem, ConnectionConfig config) {
        if (config.getPassword() == null) {
            PasswordPromptDialog.Result pwdResult = PasswordPromptDialog.show(
                    "输入密码",
                    config.getName() + " (" + config.getUsername() + "@" + config.getHost() + ")",
                    "密码：", null, "保存密码");
            if (pwdResult == null || pwdResult.getPassword() == null || pwdResult.getPassword().isEmpty()) return;
            config.setPassword(pwdResult.getPassword());
            if (pwdResult.isSavePassword()) {
                config.setSavePassword(true);
                module.saveConnections();
            }
        }

        Stage stage = module.getStage();
        if (stage == null) return;

        CreateDatabaseDialog dialog = new CreateDatabaseDialog(stage, config);
        dialog.showAndWait();

        if (!dialog.isConfirmed()) return;

        String dbName = dialog.getDatabaseName();
        String charset = dialog.getCharset();
        String collation = dialog.getCollation();

        new Thread(() -> {
            try {
                DatabaseService.createDatabase(config, dbName, charset, collation);
                Platform.runLater(() -> {
                    if (!hostItem.getChildren().isEmpty()) {
                        refreshDbHost(hostItem, config);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("创建失败");
                    alert.setHeaderText(null);
                    alert.setContentText("创建数据库失败: " + e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "DB-CreateDatabase").start();
    }

    /** 编辑数据库（修改字符集/排序规则） */
    public void handleEditDatabase(TreeItem<String> dbItem, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();

        if (config.getPassword() == null) {
            PasswordPromptDialog.Result pwdResult = PasswordPromptDialog.show(
                    "输入密码",
                    config.getName() + " (" + config.getUsername() + "@" + config.getHost() + ")",
                    "密码：", null, "保存密码");
            if (pwdResult == null || pwdResult.getPassword() == null || pwdResult.getPassword().isEmpty()) return;
            config.setPassword(pwdResult.getPassword());
            if (pwdResult.isSavePassword()) {
                config.setSavePassword(true);
                module.saveConnections();
            }
        }

        Stage stage = module.getStage();
        if (stage == null) return;

        String dbName = data.getDatabaseName();

        new Thread(() -> {
            try {
                String[] charsetCollation = DatabaseService.getDatabaseCharsetCollation(config, dbName);
                String currentCharset = charsetCollation[0];
                String currentCollation = charsetCollation[1];

                Platform.runLater(() -> {
                    EditDatabaseDialog dialog = new EditDatabaseDialog(stage, config, dbName, currentCharset, currentCollation);
                    dialog.showAndWait();

                    if (!dialog.isConfirmed()) return;

                    String charset = dialog.getCharset();
                    String collation = dialog.getCollation();

                    new Thread(() -> {
                        try {
                            DatabaseService.alterDatabase(config, dbName, charset, collation);
                            Platform.runLater(() -> {
                                refreshDbNode(dbItem, data);
                            });
                        } catch (Exception e) {
                            Platform.runLater(() -> {
                                Alert alert = new Alert(Alert.AlertType.ERROR);
                                alert.setTitle("修改失败");
                                alert.setHeaderText(null);
                                alert.setContentText("修改数据库失败: " + e.getMessage());
                                alert.showAndWait();
                            });
                        }
                    }, "DB-AlterDatabase").start();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("查询失败");
                    alert.setHeaderText(null);
                    alert.setContentText("获取数据库信息失败: " + e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "DB-GetDbInfo").start();
    }

    /** 删除数据库 */
    public void handleDeleteDatabase(TreeItem<String> dbItem, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();
        String dbName = data.getDatabaseName();

        Alert confirm = new Alert(Alert.AlertType.WARNING);
        confirm.setTitle("删除数据库");
        confirm.setHeaderText("确定要删除数据库 \"" + dbName + "\" 吗？");
        confirm.setContentText("此操作不可撤销，该数据库中的所有数据将被永久删除！");
        confirm.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
        confirm.showAndWait().ifPresent(response -> {
            if (response != ButtonType.YES) return;

            if (config.getPassword() == null) {
                PasswordPromptDialog.Result pwdResult = PasswordPromptDialog.show(
                        "输入密码",
                        config.getName() + " (" + config.getUsername() + "@" + config.getHost() + ")",
                        "密码：", null, "保存密码");
                if (pwdResult == null || pwdResult.getPassword() == null || pwdResult.getPassword().isEmpty()) return;
                config.setPassword(pwdResult.getPassword());
                if (pwdResult.isSavePassword()) {
                    config.setSavePassword(true);
                    module.saveConnections();
                }
            }

            new Thread(() -> {
                try {
                    DatabaseService.dropDatabase(config, dbName);
                    Platform.runLater(() -> {
                        module.removeDbNodeDataRecursive(dbItem);
                        dbItem.getParent().getChildren().remove(dbItem);
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("删除失败");
                        alert.setHeaderText(null);
                        alert.setContentText("删除数据库失败: " + e.getMessage());
                        alert.showAndWait();
                    });
                }
            }, "DB-DropDatabase").start();
        });
    }

    /** 批量删除表/视图节点 */
    public void handleDeleteDbNodes() {
        ObservableList<TreeItem<String>> selectedItems = module.getTreeView().getSelectionModel().getSelectedItems();
        List<TreeItem<String>> tableItems = new ArrayList<>();
        List<TreeItem<String>> viewItems = new ArrayList<>();

        for (TreeItem<String> item : selectedItems) {
            DatabaseNodeData data = module.getDbNodeDataMap().get(item);
            if (data != null) {
                if (data.getType() == DatabaseNodeData.NodeType.TABLE) {
                    tableItems.add(item);
                } else if (data.getType() == DatabaseNodeData.NodeType.VIEW) {
                    viewItems.add(item);
                }
            }
        }

        if (tableItems.isEmpty() && viewItems.isEmpty()) return;

        StringBuilder msg = new StringBuilder("确定要删除以下对象吗？此操作不可恢复！\n\n");
        if (!tableItems.isEmpty()) {
            msg.append("表：\n");
            for (TreeItem<String> item : tableItems) {
                msg.append("  - ").append(item.getValue()).append("\n");
            }
        }
        if (!viewItems.isEmpty()) {
            msg.append("视图：\n");
            for (TreeItem<String> item : viewItems) {
                msg.append("  - ").append(item.getValue()).append("\n");
            }
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认删除");
        confirm.setHeaderText(null);
        confirm.setContentText(msg.toString());

        ButtonType deleteBtn = new ButtonType("确认删除");
        confirm.getButtonTypes().setAll(deleteBtn, ButtonType.CANCEL);

        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != deleteBtn) return;

        if (!tableItems.isEmpty()) {
            Map<String, List<TreeItem<String>>> groupedTables = new HashMap<>();
            for (TreeItem<String> item : tableItems) {
                DatabaseNodeData data = module.getDbNodeDataMap().get(item);
                String schemaName = data.getSchemaName() != null ? data.getSchemaName() : "";
                String key = data.getConnectionConfig().getId() + "|" + data.getDatabaseName() + "|" + schemaName;
                groupedTables.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
            }
            for (Map.Entry<String, List<TreeItem<String>>> entry : groupedTables.entrySet()) {
                String[] parts = entry.getKey().split("\\|");
                String configId = parts[0];
                String dbName = parts[1];
                String schemaName = parts.length > 2 && !parts[2].isEmpty() ? parts[2] : null;
                List<String> tableNames = entry.getValue().stream()
                        .map(TreeItem::getValue).toList();
                ConnectionConfig cfg = module.findConnectionById(configId);
                if (cfg == null) continue;

                try {
                    DatabaseService.dropTables(cfg, dbName, schemaName, tableNames);
                    Platform.runLater(() -> {
                        for (TreeItem<String> item : entry.getValue()) {
                            module.getDbNodeDataMap().remove(item);
                            item.getParent().getChildren().remove(item);
                        }
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert err = new Alert(Alert.AlertType.ERROR);
                        err.setTitle("删除失败");
                        err.setHeaderText(null);
                        err.setContentText(e.getMessage());
                        err.showAndWait();
                    });
                }
            }
        }

        if (!viewItems.isEmpty()) {
            Map<String, List<TreeItem<String>>> groupedViews = new HashMap<>();
            for (TreeItem<String> item : viewItems) {
                DatabaseNodeData data = module.getDbNodeDataMap().get(item);
                String schemaName = data.getSchemaName() != null ? data.getSchemaName() : "";
                String key = data.getConnectionConfig().getId() + "|" + data.getDatabaseName() + "|" + schemaName;
                groupedViews.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
            }
            for (Map.Entry<String, List<TreeItem<String>>> entry : groupedViews.entrySet()) {
                String[] parts = entry.getKey().split("\\|");
                String configId = parts[0];
                String dbName = parts[1];
                String schemaName = parts.length > 2 && !parts[2].isEmpty() ? parts[2] : null;
                List<String> viewNames = entry.getValue().stream()
                        .map(TreeItem::getValue).toList();
                ConnectionConfig cfg = module.findConnectionById(configId);
                if (cfg == null) continue;

                try {
                    DatabaseService.dropViews(cfg, dbName, schemaName, viewNames);
                    Platform.runLater(() -> {
                        for (TreeItem<String> item : entry.getValue()) {
                            module.getDbNodeDataMap().remove(item);
                            item.getParent().getChildren().remove(item);
                        }
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert err = new Alert(Alert.AlertType.ERROR);
                        err.setTitle("删除失败");
                        err.setHeaderText(null);
                        err.setContentText(e.getMessage());
                        err.showAndWait();
                    });
                }
            }
        }
    }

    /**
     * 删除对象视图传入的表/视图（不依赖左侧树选中状态）。
     * 用于对象视图（TableObjectsView）的"删除表"按钮：根据传入的 DatabaseNodeData 列表
     * 分组调用 DatabaseService.dropTables/dropViews，删除完成后回调刷新视图。
     */
    public void handleDeleteObjects(List<DatabaseNodeData> dataList, Runnable onComplete) {
        if (dataList == null || dataList.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }

        // 按表/视图分组收集名称（对象视图同属一个连接/数据库/schema）
        List<String> tableNames = new ArrayList<>();
        List<String> viewNames = new ArrayList<>();
        DatabaseNodeData sample = null;
        for (DatabaseNodeData d : dataList) {
            if (d.getType() == DatabaseNodeData.NodeType.TABLE) {
                tableNames.add(d.getName());
                if (sample == null) sample = d;
            } else if (d.getType() == DatabaseNodeData.NodeType.VIEW) {
                viewNames.add(d.getName());
                if (sample == null) sample = d;
            }
        }
        if (sample == null) {
            if (onComplete != null) onComplete.run();
            return;
        }

        // 确认对话框（仅提示数量，不列出表名）
        StringBuilder msg = new StringBuilder("确定要删除选中的 ");
        if (!tableNames.isEmpty()) {
            msg.append(tableNames.size()).append(" 张表");
        }
        if (!viewNames.isEmpty()) {
            if (!tableNames.isEmpty()) msg.append("、");
            msg.append(viewNames.size()).append(" 个视图");
        }
        msg.append("吗？此操作不可恢复！");
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认删除");
        confirm.setHeaderText(null);
        confirm.setContentText(msg.toString());
        ButtonType deleteBtn = new ButtonType("确认删除");
        confirm.getButtonTypes().setAll(deleteBtn, ButtonType.CANCEL);
        DialogPositionUtil.centerOnOwner(confirm, module.getStage());
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != deleteBtn) {
            if (onComplete != null) onComplete.run();
            return;
        }

        ConnectionConfig cfg = sample.getConnectionConfig();
        String dbName = sample.getDatabaseName();
        String schema = sample.getSchemaName();

        new Thread(() -> {
            try {
                if (!tableNames.isEmpty()) {
                    DatabaseService.dropTables(cfg, dbName, schema, tableNames);
                }
                if (!viewNames.isEmpty()) {
                    DatabaseService.dropViews(cfg, dbName, schema, viewNames);
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert err = new Alert(Alert.AlertType.ERROR);
                    err.setTitle("删除失败");
                    err.setHeaderText(null);
                    err.setContentText(e.getMessage());
                    DialogPositionUtil.centerOnOwner(err, module.getStage());
                    err.showAndWait();
                });
            } finally {
                if (onComplete != null) Platform.runLater(onComplete);
            }
        }, "DB-DeleteObjects").start();
    }

    /**
     * 清空表数据（DELETE FROM）：根据传入的 DatabaseNodeData 列表收集表名，
     * 调用 DatabaseService.clearTables 删除所有数据，完成后回调刷新视图。
     * 仅处理 TABLE 类型对象，视图会被忽略。
     */
    public void handleClearTables(List<DatabaseNodeData> dataList, Runnable onComplete) {
        if (dataList == null || dataList.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        List<String> tableNames = collectTableNames(dataList);
        if (tableNames.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        DatabaseNodeData sample = findFirstTable(dataList);
        confirmAndRun("清空确认",
                "确定要清空选中的 " + tableNames.size() + " 张表的数据吗？\n" +
                        "此操作将删除所有数据（DELETE FROM），不可恢复！",
                "确认清空", tableNames, sample, "DB-ClearTables",
                (cfg, db, schema, names) -> DatabaseService.clearTables(cfg, db, schema, names),
                onComplete);
    }

    /**
     * 截断表（TRUNCATE TABLE）：根据传入的 DatabaseNodeData 列表收集表名，
     * 调用 DatabaseService.truncateTables 删除所有数据并重置自增列，完成后回调刷新视图。
     * 仅处理 TABLE 类型对象，视图会被忽略。
     */
    public void handleTruncateTables(List<DatabaseNodeData> dataList, Runnable onComplete) {
        if (dataList == null || dataList.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        List<String> tableNames = collectTableNames(dataList);
        if (tableNames.isEmpty()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        DatabaseNodeData sample = findFirstTable(dataList);
        confirmAndRun("截断确认",
                "确定要截断选中的 " + tableNames.size() + " 张表吗？\n" +
                        "此操作将删除所有数据（TRUNCATE TABLE），不可恢复，且会重置自增列！",
                "确认截断", tableNames, sample, "DB-TruncateTables",
                (cfg, db, schema, names) -> DatabaseService.truncateTables(cfg, db, schema, names),
                onComplete);
    }

    /** 从 dataList 中收集表（TABLE）类型名称列表 */
    private List<String> collectTableNames(List<DatabaseNodeData> dataList) {
        List<String> tableNames = new ArrayList<>();
        for (DatabaseNodeData d : dataList) {
            if (d.getType() == DatabaseNodeData.NodeType.TABLE) {
                tableNames.add(d.getName());
            }
        }
        return tableNames;
    }

    /** 从 dataList 中查找第一个表（TABLE）类型对象作为连接/库/schema 取样 */
    private DatabaseNodeData findFirstTable(List<DatabaseNodeData> dataList) {
        for (DatabaseNodeData d : dataList) {
            if (d.getType() == DatabaseNodeData.NodeType.TABLE) return d;
        }
        return null;
    }

    /** 批量执行表操作的通用流程：确认对话框 → 后台线程执行 → 错误提示 → 完成回调 */
    private void confirmAndRun(String title, String message, String confirmBtnText,
                               List<String> tableNames, DatabaseNodeData sample,
                               String threadName,
                               TableBatchAction action, Runnable onComplete) {
        Alert confirm = new Alert(Alert.AlertType.WARNING);
        confirm.setTitle(title);
        confirm.setHeaderText(null);
        confirm.setContentText(message);
        ButtonType okBtn = new ButtonType(confirmBtnText);
        confirm.getButtonTypes().setAll(okBtn, ButtonType.CANCEL);
        DialogPositionUtil.centerOnOwner(confirm, module.getStage());
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != okBtn) {
            if (onComplete != null) onComplete.run();
            return;
        }

        ConnectionConfig cfg = sample.getConnectionConfig();
        String dbName = sample.getDatabaseName();
        String schema = sample.getSchemaName();

        new Thread(() -> {
            try {
                action.execute(cfg, dbName, schema, tableNames);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert err = new Alert(Alert.AlertType.ERROR);
                    err.setTitle("操作失败");
                    err.setHeaderText(null);
                    err.setContentText(e.getMessage());
                    DialogPositionUtil.centerOnOwner(err, module.getStage());
                    err.showAndWait();
                });
            } finally {
                if (onComplete != null) Platform.runLater(onComplete);
            }
        }, threadName).start();
    }

    /** 表批量操作函数式接口 */
    @FunctionalInterface
    private interface TableBatchAction {
        void execute(ConnectionConfig cfg, String dbName, String schema, List<String> tableNames) throws Exception;
    }

    // ==================== 刷新 ====================

    /** 刷新数据库主机：重新加载数据库列表 */
    public void refreshDbHost(TreeItem<String> hostItem, ConnectionConfig config) {
        if (config.getPassword() == null) {
            module.triggerHostDoubleClick(hostItem, config);
            return;
        }
        new Thread(() -> {
            try {
                List<String> databases = DatabaseService.getDatabases(config);
                Platform.runLater(() -> {
                    for (TreeItem<String> child : hostItem.getChildren()) {
                        module.removeDbNodeDataRecursive(child);
                    }
                    hostItem.getChildren().clear();
                    for (String dbName : databases) {
                        TreeItem<String> dbItem = new TreeItem<>(dbName);
                        dbItem.setGraphic(module.getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.DATABASE, dbName, config, dbName)));
                        module.getDbNodeDataMap().put(dbItem, new DatabaseNodeData(DatabaseNodeData.NodeType.DATABASE, dbName, config, dbName));
                        hostItem.getChildren().add(dbItem);
                    }
                    hostItem.setExpanded(true);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("刷新失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法刷新数据库列表: " + e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "DB-RefreshDatabases").start();
    }

    /** 刷新数据库节点（仅处理数据库相关类型） */
    public void refreshDbNode(TreeItem<String> item, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();
        switch (data.getType()) {
            case DATABASE -> {
                if (data.isOpened()) {
                    module.removeDbNodeDataRecursive(item);
                    item.getChildren().clear();
                    openDatabase(item, data);
                }
            }
            case SCHEMA -> {
                module.removeDbNodeDataRecursive(item);
                item.getChildren().clear();
                data.setOpened(false);
                item.setGraphic(module.getDbNodeIcon(data));
                handleSchemaDoubleClick(item, data);
            }
            case TABLES_FOLDER -> {
                item.getChildren().clear();
                loadTablesForFolder(item, config, data.getDatabaseName(), data.getSchemaName(), false);
            }
            case VIEWS_FOLDER -> {
                item.getChildren().clear();
                loadViewsForFolder(item, config, data.getDatabaseName(), data.getSchemaName(), false);
            }
            case QUERY_FOLDER -> {
                loadQueriesForFolder(item, config, data.getDatabaseName(), "");
            }
            case BACKUP_FOLDER -> {
                loadBackupsForFolder(item, config, data.getDatabaseName(), "");
            }
            case QUERY_DIR -> {
                module.removeDbNodeDataRecursive(item);
                loadQueriesForFolder(item, config, data.getDatabaseName(), data.getPath());
            }
            case BACKUP_DIR -> {
                module.removeDbNodeDataRecursive(item);
                loadBackupsForFolder(item, config, data.getDatabaseName(), data.getPath());
            }
            default -> {}
        }
    }

    // ==================== 加载列表到 folder 节点 ====================

    /** 加载表列表到指定文件夹节点 */
    public void loadTablesForFolder(TreeItem<String> folderItem, ConnectionConfig config, String dbName, String schemaName, boolean autoExpand) {
        new Thread(() -> {
            try {
                List<String> tables = DatabaseService.getTables(config, dbName, schemaName);
                Platform.runLater(() -> {
                    folderItem.getChildren().clear();
                    for (String tableName : tables) {
                        TreeItem<String> tableItem = new TreeItem<>(tableName);
                        DatabaseNodeData tableData = new DatabaseNodeData(DatabaseNodeData.NodeType.TABLE, tableName, config, dbName, schemaName);
                        tableItem.setGraphic(module.getDbNodeIcon(tableData));
                        module.getDbNodeDataMap().put(tableItem, tableData);
                        folderItem.getChildren().add(tableItem);
                    }
                    folderItem.setExpanded(autoExpand);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载表列表: " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
        }, "DB-LoadTables").start();
    }

    /** 加载视图列表到指定文件夹节点 */
    public void loadViewsForFolder(TreeItem<String> folderItem, ConnectionConfig config, String dbName, String schemaName, boolean autoExpand) {
        new Thread(() -> {
            try {
                List<String> views = DatabaseService.getViews(config, dbName, schemaName);
                Platform.runLater(() -> {
                    folderItem.getChildren().clear();
                    for (String viewName : views) {
                        TreeItem<String> viewItem = new TreeItem<>(viewName);
                        DatabaseNodeData viewData = new DatabaseNodeData(DatabaseNodeData.NodeType.VIEW, viewName, config, dbName, schemaName);
                        viewItem.setGraphic(module.getDbNodeIcon(viewData));
                        module.getDbNodeDataMap().put(viewItem, viewData);
                        folderItem.getChildren().add(viewItem);
                    }
                    folderItem.setExpanded(autoExpand);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载视图列表: " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
        }, "DB-LoadViews").start();
    }

    /**
     * 在单个线程中顺序加载表和视图列表，避免两个线程并发使用同一JDBC连接。
     * JDBC Connection不是线程安全的，并发使用会导致协议损坏和挂起。
     */
    public void loadTablesAndViewsForFolder(TreeItem<String> tablesFolder, TreeItem<String> viewsFolder,
                                             ConnectionConfig config, String dbName, String schemaName, boolean autoExpand) {
        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, dbName);
            connLock.lock();
            try {
            // 顺序加载表列表
            try {
                List<String> tables = DatabaseService.getTables(config, dbName, schemaName);
                Platform.runLater(() -> {
                    tablesFolder.getChildren().clear();
                    for (String tableName : tables) {
                        TreeItem<String> tableItem = new TreeItem<>(tableName);
                        DatabaseNodeData tableData = new DatabaseNodeData(DatabaseNodeData.NodeType.TABLE, tableName, config, dbName, schemaName);
                        tableItem.setGraphic(module.getDbNodeIcon(tableData));
                        module.getDbNodeDataMap().put(tableItem, tableData);
                        tablesFolder.getChildren().add(tableItem);
                    }
                    tablesFolder.setExpanded(autoExpand);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载表列表: " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }

            // 顺序加载视图列表（在表列表加载完成后，确保不并发使用连接）
            try {
                List<String> views = DatabaseService.getViews(config, dbName, schemaName);
                Platform.runLater(() -> {
                    viewsFolder.getChildren().clear();
                    for (String viewName : views) {
                        TreeItem<String> viewItem = new TreeItem<>(viewName);
                        DatabaseNodeData viewData = new DatabaseNodeData(DatabaseNodeData.NodeType.VIEW, viewName, config, dbName, schemaName);
                        viewItem.setGraphic(module.getDbNodeIcon(viewData));
                        module.getDbNodeDataMap().put(viewItem, viewData);
                        viewsFolder.getChildren().add(viewItem);
                    }
                    viewsFolder.setExpanded(autoExpand);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载视图列表: " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
            } finally {
                connLock.unlock();
            }
        }, "DB-LoadTablesAndViews").start();
    }

    /** 加载查询列表（含子目录）到指定文件夹节点。path 为相对 query 根目录的相对路径，""表示根 */
    public void loadQueriesForFolder(TreeItem<String> folderItem, ConnectionConfig config, String dbName, String path) {
        String currentPath = path == null ? "" : path;
        folderItem.getChildren().clear();

        // 先加载子目录
        for (String dirName : SqlEditorView.listQueryDirs(config.getName(), dbName, currentPath)) {
            String dirPath = currentPath.isEmpty() ? dirName : currentPath + "/" + dirName;
            TreeItem<String> dirItem = new TreeItem<>(dirName);
            DatabaseNodeData dirData = new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY_DIR, dirName, config, dbName, null, dirPath);
            dirItem.setGraphic(module.getDbNodeIcon(dirData));
            module.getDbNodeDataMap().put(dirItem, dirData);
            folderItem.getChildren().add(dirItem);
        }

        // 再加载查询文件
        for (String queryName : SqlEditorView.listQueries(config.getName(), dbName, currentPath)) {
            TreeItem<String> queryItem = new TreeItem<>(queryName);
            DatabaseNodeData queryData = new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY, queryName, config, dbName, null, currentPath);
            queryItem.setGraphic(module.getDbNodeIcon(queryData));
            module.getDbNodeDataMap().put(queryItem, queryData);
            folderItem.getChildren().add(queryItem);
        }
    }

    /** 加载备份列表（含子目录）到指定文件夹节点。path 为相对 backup 根目录的相对路径，""表示根 */
    public void loadBackupsForFolder(TreeItem<String> folderItem, ConnectionConfig config, String dbName, String path) {
        String currentPath = path == null ? "" : path;
        folderItem.getChildren().clear();

        // 先加载子目录
        for (String dirName : BackupService.listBackupDirs(config.getName(), dbName, currentPath)) {
            String dirPath = currentPath.isEmpty() ? dirName : currentPath + "/" + dirName;
            TreeItem<String> dirItem = new TreeItem<>(dirName);
            DatabaseNodeData dirData = new DatabaseNodeData(DatabaseNodeData.NodeType.BACKUP_DIR, dirName, config, dbName, null, dirPath);
            dirItem.setGraphic(module.getDbNodeIcon(dirData));
            module.getDbNodeDataMap().put(dirItem, dirData);
            folderItem.getChildren().add(dirItem);
        }

        // 再加载备份文件
        for (String backupName : BackupService.listBackups(config.getName(), dbName, currentPath)) {
            TreeItem<String> backupItem = new TreeItem<>(backupName);
            DatabaseNodeData backupData = new DatabaseNodeData(DatabaseNodeData.NodeType.BACKUP, backupName, config, dbName, null, currentPath);
            backupItem.setGraphic(module.getDbNodeIcon(backupData));
            module.getDbNodeDataMap().put(backupItem, backupData);
            folderItem.getChildren().add(backupItem);
        }
    }

    // ==================== 打开数据库/构建文件夹 ====================

    /**
     * 打开数据库节点并直接加载 5 个文件夹（表/视图/函数/查询/备份）。
     * 供 MySQL/Oracle 等无 schema 层级的数据库处理器调用。
     */
    protected void openDatabaseWithFolders(TreeItem<String> dbItem, DatabaseNodeData data) {
        data.setOpened(true);
        dbItem.setGraphic(module.getDbNodeIcon(data));

        ConnectionConfig config = data.getConnectionConfig();
        String dbName = data.getDatabaseName();

        TreeItem<String> tablesFolder = new TreeItem<>("表");
        tablesFolder.setGraphic(module.getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.TABLES_FOLDER, "表", config, dbName)));
        module.getDbNodeDataMap().put(tablesFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.TABLES_FOLDER, "表", config, dbName));

        TreeItem<String> viewsFolder = new TreeItem<>("视图");
        viewsFolder.setGraphic(module.getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.VIEWS_FOLDER, "视图", config, dbName)));
        module.getDbNodeDataMap().put(viewsFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.VIEWS_FOLDER, "视图", config, dbName));

        TreeItem<String> functionFolder = new TreeItem<>("函数");
        functionFolder.setGraphic(module.getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.FUNCTION_FOLDER, "函数", config, dbName)));
        module.getDbNodeDataMap().put(functionFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.FUNCTION_FOLDER, "函数", config, dbName));

        TreeItem<String> queryFolder = new TreeItem<>("查询");
        queryFolder.setGraphic(module.getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY_FOLDER, "查询", config, dbName)));
        module.getDbNodeDataMap().put(queryFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY_FOLDER, "查询", config, dbName));

        loadQueriesForFolder(queryFolder, config, dbName, "");

        TreeItem<String> backupFolder = new TreeItem<>("备份");
        backupFolder.setGraphic(module.getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.BACKUP_FOLDER, "备份", config, dbName)));
        module.getDbNodeDataMap().put(backupFolder, new DatabaseNodeData(DatabaseNodeData.NodeType.BACKUP_FOLDER, "备份", config, dbName));

        loadBackupsForFolder(backupFolder, config, dbName, "");

        dbItem.getChildren().addAll(tablesFolder, viewsFolder, functionFolder, queryFolder, backupFolder);
        dbItem.setExpanded(true);

        // 使用单线程顺序加载表和视图，避免并发使用同一JDBC连接
        loadTablesAndViewsForFolder(tablesFolder, viewsFolder, config, dbName, null, false);
    }

    /**
     * 为 schema 节点构建 5 个子文件夹（表/视图/函数/查询/备份）。
     * 供 PostgreSQL 等支持 schema 层级的数据库处理器调用。
     */
    protected void buildSchemaFolders(TreeItem<String> schemaItem, ConnectionConfig config, String dbName, String schemaName) {
        TreeItem<String> tablesFolder = new TreeItem<>("表");
        DatabaseNodeData tablesData = new DatabaseNodeData(DatabaseNodeData.NodeType.TABLES_FOLDER, "表", config, dbName, schemaName);
        tablesFolder.setGraphic(module.getDbNodeIcon(tablesData));
        module.getDbNodeDataMap().put(tablesFolder, tablesData);

        TreeItem<String> viewsFolder = new TreeItem<>("视图");
        DatabaseNodeData viewsData = new DatabaseNodeData(DatabaseNodeData.NodeType.VIEWS_FOLDER, "视图", config, dbName, schemaName);
        viewsFolder.setGraphic(module.getDbNodeIcon(viewsData));
        module.getDbNodeDataMap().put(viewsFolder, viewsData);

        TreeItem<String> functionFolder = new TreeItem<>("函数");
        DatabaseNodeData functionData = new DatabaseNodeData(DatabaseNodeData.NodeType.FUNCTION_FOLDER, "函数", config, dbName, schemaName);
        functionFolder.setGraphic(module.getDbNodeIcon(functionData));
        module.getDbNodeDataMap().put(functionFolder, functionData);

        TreeItem<String> queryFolder = new TreeItem<>("查询");
        DatabaseNodeData queryData = new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY_FOLDER, "查询", config, dbName, schemaName);
        queryFolder.setGraphic(module.getDbNodeIcon(queryData));
        module.getDbNodeDataMap().put(queryFolder, queryData);
        loadQueriesForFolder(queryFolder, config, dbName, "");

        TreeItem<String> backupFolder = new TreeItem<>("备份");
        DatabaseNodeData backupData = new DatabaseNodeData(DatabaseNodeData.NodeType.BACKUP_FOLDER, "备份", config, dbName, schemaName);
        backupFolder.setGraphic(module.getDbNodeIcon(backupData));
        module.getDbNodeDataMap().put(backupFolder, backupData);
        loadBackupsForFolder(backupFolder, config, dbName, "");

        schemaItem.getChildren().addAll(tablesFolder, viewsFolder, functionFolder, queryFolder, backupFolder);
    }

    // ==================== 文件夹双击：加载表/视图列表 ====================

    /** 双击表文件夹：打开对象视图 Tab，并展开节点显示表列表（未加载则先加载） */
    public void handleTablesFolderDoubleClick(TreeItem<String> folderItem, DatabaseNodeData data) {
        // 打开对象视图 Tab（已存在则激活）
        openObjectsView(folderItem, data);

        if (folderItem.getChildren().isEmpty()) {
            loadTablesForFolder(folderItem, data.getConnectionConfig(), data.getDatabaseName(), data.getSchemaName(), true);
        } else {
            // 已加载则展开节点，方便查看表列表
            folderItem.setExpanded(true);
        }
    }

    /**
     * 打开对象视图 Tab：展示当前数据库/Schema 的所有表和视图对象。
     * 支持 Tab 去重（相同 schema 的对象视图只开一个），且 Tab 不可关闭。
     */
    public void openObjectsView(TreeItem<String> folderItem, DatabaseNodeData data) {
        if (module.getTerminalTabPane() == null) return;
        if (!module.ensureTabPaneInstalled()) return;

        String tabId = "objects_" + data.getConnectionConfig().getId() + "_" + data.getDatabaseName()
                + (data.getSchemaName() != null ? "_" + data.getSchemaName() : "");
        for (Tab tab : module.getTerminalTabPane().getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(tab);
                module.showDataView();
                return;
            }
        }

        // 创建视图，注入对象操作回调（复用 handler 已有的去重/业务逻辑）
        // 使用 holder 数组避免"变量尚未初始化"错误（匿名内部类需在构造时引用视图自身）
        final TableObjectsView[] holder = new TableObjectsView[1];
        TableObjectsView objectsView = new TableObjectsView(
                data.getConnectionConfig(), data.getDatabaseName(), data.getSchemaName(),
                new TableObjectsView.ObjectOperations() {
                    @Override
                    public void openObject(DatabaseNodeData objData) {
                        handleTableDataDoubleClick(folderItem, objData);
                    }

                    @Override
                    public void designObject(DatabaseNodeData objData) {
                        handleTableStructureDoubleClick(folderItem, objData);
                    }

                    @Override
                    public void createTable() {
                        handleNewTable(folderItem, data);
                    }

                    @Override
                    public void deleteObjects(List<DatabaseNodeData> dataList) {
                        handleDeleteObjects(dataList, () -> {
                            if (holder[0] != null) holder[0].notifyObjectDeleted();
                        });
                    }

                    @Override
                    public void clearTables(List<DatabaseNodeData> dataList) {
                        handleClearTables(dataList, () -> {
                            if (holder[0] != null) holder[0].notifyObjectDeleted();
                        });
                    }

                    @Override
                    public void truncateTables(List<DatabaseNodeData> dataList) {
                        handleTruncateTables(dataList, () -> {
                            if (holder[0] != null) holder[0].notifyObjectDeleted();
                        });
                    }

                    @Override
                    public void renameObject(DatabaseNodeData objData, String newName, Runnable onSuccess) {
                        handleRenameObject(folderItem, data, objData, newName, onSuccess);
                    }

                    @Override
                    public void importWizard() {
                        handleRestoreBackup(null, data);
                    }

                    @Override
                    public void exportWizard() {
                        module.handleNewBackup(folderItem, data);
                    }

                    @Override
                    public void pasteTables() {
                        // 目标为当前对象视图对应的连接/数据库
                        handlePasteTables(folderItem, data, () -> {
                            if (holder[0] != null) holder[0].notifyObjectDeleted();
                        });
                    }
                });
        holder[0] = objectsView;

        ConnectionConfig config = data.getConnectionConfig();
        String tabTitle = "对象@" + data.getDatabaseName()
                + (data.getSchemaName() != null ? "/" + data.getSchemaName() : "")
                + "(" + config.getHost() + ":" + config.getPort() + ")";
        Tab tab = new Tab(tabTitle);
        try {
            Image folderIcon = new Image(getClass().getResourceAsStream("/images/connect/folder.png"));
            if (folderIcon != null) {
                ImageView tabIconView = new ImageView(folderIcon);
                tabIconView.setFitWidth(18);
                tabIconView.setFitHeight(18);
                tab.setGraphic(ConnectModule.createFixedSizeGraphic(tabIconView));
            }
        } catch (Exception ignored) {}
        tab.setContent(objectsView);
        tab.setUserData(tabId);
        tab.setClosable(false);  // 不可关闭

        // 右键菜单：仅刷新
        ContextMenu ctxMenu = new ContextMenu();
        MenuItem refreshItem = new MenuItem("刷新对象");
        refreshItem.setOnAction(e -> objectsView.refreshData());
        ctxMenu.getItems().add(refreshItem);
        appendCloseMenuItems(tab, ctxMenu);
        tab.setContextMenu(ctxMenu);

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showDataView();
    }

    /**
     * 重命名表/视图：调用 DatabaseService 执行重命名，更新树节点，刷新对象视图。
     */
    public void handleRenameObject(TreeItem<String> folderItem, DatabaseNodeData folderData,
                                    DatabaseNodeData objData, String newName, Runnable onSuccess) {
        ConnectionConfig config = objData.getConnectionConfig();
        String dbName = objData.getDatabaseName();
        String schemaName = objData.getSchemaName();
        String oldName = objData.getName();
        boolean isTable = objData.getType() == DatabaseNodeData.NodeType.TABLE;

        new Thread(() -> {
            try {
                if (isTable) {
                    DatabaseService.renameTable(config, dbName, schemaName, oldName, newName);
                } else {
                    DatabaseService.renameView(config, dbName, schemaName, oldName, newName);
                }
                Platform.runLater(() -> {
                    // 更新树节点：查找并更新表/视图节点的名称
                    updateTreeNodeName(folderItem, oldName, newName, config, dbName, schemaName);
                    // 回调刷新对象视图
                    if (onSuccess != null) onSuccess.run();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("重命名失败");
                    alert.setHeaderText(null);
                    alert.setContentText("重命名失败: " + e.getMessage());
                    DialogPositionUtil.centerOnOwner(alert, module.getStage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
        }, "DB-RenameFromObjects").start();
    }

    /**
     * 查找树中表/视图节点并更新名称。
     * 在 folderItem 的子节点中查找名称匹配的节点，更新其值和 dbNodeDataMap。
     */
    private void updateTreeNodeName(TreeItem<String> folderItem, String oldName, String newName,
                                     ConnectionConfig config, String dbName, String schemaName) {
        // folderItem 是"表"或"视图"文件夹，遍历其子节点查找匹配的表/视图
        for (TreeItem<String> child : folderItem.getChildren()) {
            if (oldName.equals(child.getValue())) {
                child.setValue(newName);
                DatabaseNodeData oldData = module.getDbNodeDataMap().get(child);
                if (oldData != null) {
                    DatabaseNodeData newData = new DatabaseNodeData(
                            oldData.getType(), newName, config, dbName,
                            schemaName != null ? schemaName : null);
                    module.getDbNodeDataMap().put(child, newData);
                }
                child.getChildren().clear();
                return;
            }
        }
    }

    /** 双击视图文件夹：若已加载则切换展开状态，否则加载视图列表 */
    public void handleViewsFolderDoubleClick(TreeItem<String> folderItem, DatabaseNodeData data) {
        if (!folderItem.getChildren().isEmpty()) {
            folderItem.setExpanded(!folderItem.isExpanded());
            return;
        }
        loadViewsForFolder(folderItem, data.getConnectionConfig(), data.getDatabaseName(), data.getSchemaName(), true);
    }

    // ==================== 表/视图 Tab 打开 ====================

    /** 新建表：打开表结构设计 Tab */
    public void handleNewTable(TreeItem<String> item, DatabaseNodeData data) {
        if (module.getTerminalTabPane() == null) return;
        if (!module.ensureTabPaneInstalled()) return;

        String tabId = "newtable_" + data.getConnectionConfig().getId() + "_" + data.getDatabaseName()
                + (data.getSchemaName() != null ? "_" + data.getSchemaName() : "");
        for (Tab tab : module.getTerminalTabPane().getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(tab);
                module.showDataView();
                return;
            }
        }

        TableStructureView structView = new TableStructureView(data.getConnectionConfig(), data.getDatabaseName(), data.getSchemaName(), null);

        ConnectionConfig config = data.getConnectionConfig();
        String tabTitle = "新建表@" + data.getDatabaseName() + "(" + config.getHost() + ":" + config.getPort() + ")-表结构";
        Tab tab = new Tab(tabTitle);
        Image tableIcon = module.getTableIcon();
        if (tableIcon != null) {
            ImageView tabIconView = new ImageView(tableIcon);
            tabIconView.setFitWidth(18);
            tabIconView.setFitHeight(18);
            tab.setGraphic(ConnectModule.createFixedSizeGraphic(tabIconView));
        }
        tab.setContent(structView);
        tab.setUserData(tabId);

        // 新建表保存成功后：更新 tab 标题/userData（切换为设计表标识）并刷新表树
        final Tab finalTab = tab;
        // 字段脏状态：未保存时Tab标题前加*，保存后去除
        final String[] baseTitle = {tabTitle};
        structView.setOnDirtyChange(dirty -> finalTab.setText((dirty ? "*" : "") + baseTitle[0]));

        structView.setOnTableCreated(newTableName -> {
            baseTitle[0] = newTableName + "@" + data.getDatabaseName() + "(" + config.getHost() + ":" + config.getPort() + ")-表结构";
            finalTab.setText(baseTitle[0]);
            finalTab.setUserData("struct_" + config.getId() + "_" + data.getDatabaseName() + "_" + newTableName);
            refreshDbNode(item, data);
            // 同步刷新已打开的对象视图列表，使新建的表立即出现
            refreshObjectsView(data);
        });

        ContextMenu structTabContextMenu = new ContextMenu();
        MenuItem structConfigItem = new MenuItem("表格配置");
        structConfigItem.setOnAction(e -> {
            Stage stage = (Stage) module.getTerminalTabPane().getScene().getWindow();
            GlobalConfigDialog.show(stage, GlobalConfigDialog.ConfigMode.TABLE);
            GlobalConfig globalConfig = GlobalConfig.getInstance();
            structView.applyTableConfig(globalConfig);
        });
        MenuItem structRefreshItem = new MenuItem("刷新结构");
        structRefreshItem.setOnAction(e -> structView.loadStructure());
        structTabContextMenu.getItems().addAll(structConfigItem, structRefreshItem);
        appendCloseMenuItems(tab, structTabContextMenu);
        tab.setContextMenu(structTabContextMenu);

        tab.setOnClosed(e -> {
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showDataView();
    }

    /**
     * 刷新与指定连接/数据库/Schema 对应的对象视图 Tab（若已打开）。
     * 用于新建表等操作成功后，使对象列表自动同步。
     */
    private void refreshObjectsView(DatabaseNodeData data) {
        if (module.getTerminalTabPane() == null) return;
        ConnectionConfig config = data.getConnectionConfig();
        String tabId = "objects_" + config.getId() + "_" + data.getDatabaseName()
                + (data.getSchemaName() != null ? "_" + data.getSchemaName() : "");
        for (Tab tab : module.getTerminalTabPane().getTabs()) {
            if (tabId.equals(tab.getUserData()) && tab.getContent() instanceof TableObjectsView objectsView) {
                objectsView.refreshData();
                return;
            }
        }
    }

    /** 设计表：打开表/视图结构 Tab */
    public void handleTableStructureDoubleClick(TreeItem<String> item, DatabaseNodeData data) {
        if (module.getTerminalTabPane() == null) return;
        if (!module.ensureTabPaneInstalled()) return;

        String tabId = "struct_" + data.getConnectionConfig().getId() + "_" + data.getDatabaseName()
                + (data.getSchemaName() != null ? "_" + data.getSchemaName() : "") + "_" + data.getName();
        for (Tab tab : module.getTerminalTabPane().getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(tab);
                module.showDataView();
                return;
            }
        }

        TableStructureView structView = new TableStructureView(data.getConnectionConfig(), data.getDatabaseName(), data.getSchemaName(), data.getName());

        ConnectionConfig config = data.getConnectionConfig();
        String typeLabel = data.getType() == DatabaseNodeData.NodeType.VIEW ? "视图" : "表";
        String tabTitle = data.getName() + "@" + data.getDatabaseName() + "(" + config.getHost() + ":" + config.getPort() + ")-" + typeLabel + "结构";
        Tab tab = new Tab(tabTitle);
        Image tabIcon = data.getType() == DatabaseNodeData.NodeType.VIEW ? module.getViewIcon() : module.getTableIcon();
        if (tabIcon != null) {
            ImageView tabIconView = new ImageView(tabIcon);
            tabIconView.setFitWidth(18);
            tabIconView.setFitHeight(18);
            tab.setGraphic(ConnectModule.createFixedSizeGraphic(tabIconView));
        }
        tab.setContent(structView);
        tab.setUserData(tabId);

        // 字段脏状态：未保存时Tab标题前加*，保存后去除
        final String[] baseTitle = {tabTitle};
        structView.setOnDirtyChange(dirty -> tab.setText((dirty ? "*" : "") + baseTitle[0]));

        ContextMenu structTabContextMenu = new ContextMenu();
        MenuItem structConfigItem = new MenuItem("表格配置");
        structConfigItem.setOnAction(e -> {
            Stage stage = (Stage) module.getTerminalTabPane().getScene().getWindow();
            GlobalConfigDialog.show(stage, GlobalConfigDialog.ConfigMode.TABLE);
            GlobalConfig globalConfig = GlobalConfig.getInstance();
            structView.applyTableConfig(globalConfig);
        });
        MenuItem structRefreshItem = new MenuItem("刷新结构");
        structRefreshItem.setOnAction(e -> structView.loadStructure());
        structTabContextMenu.getItems().addAll(structConfigItem, structRefreshItem);
        appendCloseMenuItems(tab, structTabContextMenu);
        tab.setContextMenu(structTabContextMenu);

        tab.setOnClosed(e -> {
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showDataView();
    }

    /** 打开数据：打开表/视图数据 Tab */
    public void handleTableDataDoubleClick(TreeItem<String> item, DatabaseNodeData data) {
        if (module.getTerminalTabPane() == null) return;
        if (!module.ensureTabPaneInstalled()) return;

        String tabId = data.getConnectionConfig().getId() + "_" + data.getDatabaseName()
                + (data.getSchemaName() != null ? "_" + data.getSchemaName() : "") + "_" + data.getName();
        for (Tab tab : module.getTerminalTabPane().getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(tab);
                module.showDataView();
                return;
            }
        }

        TableDataView dataView = new TableDataView(data.getConnectionConfig(), data.getDatabaseName(), data.getSchemaName(), data.getName());

        ConnectionConfig config = data.getConnectionConfig();
        String typeLabel = data.getType() == DatabaseNodeData.NodeType.VIEW ? "视图" : "表";
        String tabTitle = data.getName() + "@" + data.getDatabaseName() + "(" + config.getHost() + ":" + config.getPort() + ")-" + typeLabel;
        Tab tab = new Tab(tabTitle);
        Image tabIcon = data.getType() == DatabaseNodeData.NodeType.VIEW ? module.getViewIcon() : module.getTableIcon();
        if (tabIcon != null) {
            ImageView tabIconView = new ImageView(tabIcon);
            tabIconView.setFitWidth(18);
            tabIconView.setFitHeight(18);
            tab.setGraphic(ConnectModule.createFixedSizeGraphic(tabIconView));
        }
        tab.setContent(dataView);
        tab.setUserData(tabId);

        ContextMenu tableTabContextMenu = new ContextMenu();
        MenuItem tableConfigItem = new MenuItem("表格配置");
        tableConfigItem.setOnAction(e -> {
            Stage stage = (Stage) module.getTerminalTabPane().getScene().getWindow();
            GlobalConfigDialog.show(stage, GlobalConfigDialog.ConfigMode.TABLE);
            GlobalConfig globalConfig = GlobalConfig.getInstance();
            dataView.applyTableConfig(globalConfig);
        });
        MenuItem tableRefreshItem = new MenuItem("刷新数据");
        tableRefreshItem.setOnAction(e -> dataView.refreshData());
        tableTabContextMenu.getItems().addAll(tableConfigItem, tableRefreshItem);
        appendCloseMenuItems(tab, tableTabContextMenu);
        tab.setContextMenu(tableTabContextMenu);

        tab.setOnClosed(e -> {
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showDataView();
    }

    // ==================== Tab 批量关闭 ====================

    /** 向 Tab 右键菜单追加"关闭左侧/关闭右侧/关闭全部"菜单项 */
    protected void appendCloseMenuItems(Tab currentTab, ContextMenu contextMenu) {
        MenuItem closeLeftItem = new MenuItem("关闭左侧");
        MenuItem closeRightItem = new MenuItem("关闭右侧");
        MenuItem closeAllItem = new MenuItem("关闭全部");

        closeLeftItem.setOnAction(e -> closeTabsBySide(currentTab, true));
        closeRightItem.setOnAction(e -> closeTabsBySide(currentTab, false));
        closeAllItem.setOnAction(e -> closeAllClosableTabs());

        contextMenu.getItems().addAll(new SeparatorMenuItem(), closeLeftItem, closeRightItem, closeAllItem);

        // 菜单显示前更新禁用状态
        contextMenu.setOnShowing(e -> {
            TabPane pane = module.getTerminalTabPane();
            if (pane == null) return;
            int idx = pane.getTabs().indexOf(currentTab);
            closeLeftItem.setDisable(idx <= 0);
            closeRightItem.setDisable(idx < 0 || idx >= pane.getTabs().size() - 1);
        });
    }

    /** 关闭 currentTab 左侧或右侧的全部可关闭 Tab */
    private void closeTabsBySide(Tab currentTab, boolean left) {
        TabPane pane = module.getTerminalTabPane();
        if (pane == null) return;
        int idx = pane.getTabs().indexOf(currentTab);
        if (idx < 0) return;
        List<Tab> toClose;
        if (left) {
            if (idx == 0) return;
            toClose = new ArrayList<>(pane.getTabs().subList(0, idx));
        } else {
            if (idx >= pane.getTabs().size() - 1) return;
            toClose = new ArrayList<>(pane.getTabs().subList(idx + 1, pane.getTabs().size()));
        }
        for (Tab t : toClose) closeTabProgrammatically(t);
    }

    /** 关闭全部可关闭 Tab */
    private void closeAllClosableTabs() {
        TabPane pane = module.getTerminalTabPane();
        if (pane == null) return;
        List<Tab> toClose = new ArrayList<>(pane.getTabs());
        for (Tab t : toClose) closeTabProgrammatically(t);
    }

    /**
     * 程序化关闭单个 Tab：触发 onCloseRequest（可被消费阻止），移除 Tab，触发 onClosed（释放连接/隧道等资源）。
     * 跳过不可关闭的 Tab（setClosable(false)）。
     */
    private void closeTabProgrammatically(Tab tab) {
        if (!tab.isClosable()) return;
        Event closeRequestEvent = new Event(tab, tab, Tab.TAB_CLOSE_REQUEST_EVENT);
        Event.fireEvent(tab, closeRequestEvent);
        if (closeRequestEvent.isConsumed()) return;
        module.getTerminalTabPane().getTabs().remove(tab);
        Event.fireEvent(tab, new Event(tab, tab, Tab.CLOSED_EVENT));
    }

    // ==================== 右键菜单 ====================

    /**
     * 为数据库相关节点构建右键菜单项。
     * 处理 DATABASE/SCHEMA/TABLES_FOLDER/VIEWS_FOLDER/QUERY_FOLDER/BACKUP_FOLDER/TABLE/VIEW 类型节点。
     */
    @Override
    public void populateNodeContextMenu(ConnectModule module, ContextMenu contextMenu, TreeItem<String> item, DatabaseNodeData data) {
        switch (data.getType()) {
            case DATABASE -> {
                if (data.isOpened()) {
                    MenuItem closeDbItem = new MenuItem("关闭");
                    closeDbItem.setOnAction(e -> closeDatabase(item, data));
                    contextMenu.getItems().add(closeDbItem);
                } else {
                    MenuItem openDbItem = new MenuItem("打开");
                    openDbItem.setOnAction(e -> openDatabase(item, data));
                    contextMenu.getItems().add(openDbItem);
                }
                MenuItem editDbItem = new MenuItem("编辑");
                editDbItem.setOnAction(e -> handleEditDatabase(item, data));
                MenuItem deleteDbItem = new MenuItem("删除");
                deleteDbItem.setOnAction(e -> handleDeleteDatabase(item, data));
                MenuItem refreshItem = new MenuItem("刷新");
                refreshItem.setOnAction(e -> refreshDbNode(item, data));
                // 粘贴表：剪贴板中有 TOMATO_COPY_TABLES 内容时启用
                MenuItem pasteTablesItem = new MenuItem("粘贴表");
                pasteTablesItem.setOnAction(e -> handlePasteTables(item, data));
                pasteTablesItem.setDisable(!isClipboardHasTables());
                contextMenu.getItems().addAll(new SeparatorMenuItem(), editDbItem, deleteDbItem, new SeparatorMenuItem(), pasteTablesItem, new SeparatorMenuItem(), refreshItem);
            }
            case SCHEMA -> {
                MenuItem openItem = new MenuItem("打开");
                openItem.setOnAction(e -> handleSchemaDoubleClick(item, data));
                MenuItem refreshItem = new MenuItem("刷新");
                refreshItem.setOnAction(e -> refreshDbNode(item, data));
                contextMenu.getItems().addAll(openItem, new SeparatorMenuItem(), refreshItem);
            }
            case TABLES_FOLDER -> {
                MenuItem openObjectsItem = new MenuItem("打开对象");
                openObjectsItem.setOnAction(e -> openObjectsView(item, data));
                MenuItem newTableItem = new MenuItem("新建表");
                newTableItem.setOnAction(e -> handleNewTable(item, data));
                MenuItem refreshItem = new MenuItem("刷新");
                refreshItem.setOnAction(e -> refreshDbNode(item, data));
                // 粘贴表：剪贴板中有 TOMATO_COPY_TABLES 内容时启用
                MenuItem pasteTablesItem = new MenuItem("粘贴表");
                pasteTablesItem.setOnAction(e -> handlePasteTables(item, data));
                pasteTablesItem.setDisable(!isClipboardHasTables());
                contextMenu.getItems().addAll(openObjectsItem, new SeparatorMenuItem(), newTableItem, new SeparatorMenuItem(), pasteTablesItem, new SeparatorMenuItem(), refreshItem);
            }
            case VIEWS_FOLDER -> {
                MenuItem refreshItem = new MenuItem("刷新");
                refreshItem.setOnAction(e -> refreshDbNode(item, data));
                contextMenu.getItems().add(refreshItem);
            }
            case QUERY_FOLDER -> {
                MenuItem newQueryItem = new MenuItem("新建查询");
                newQueryItem.setOnAction(e -> handleNewQuery(item, data));
                MenuItem newDirItem = new MenuItem("新建目录");
                newDirItem.setOnAction(e -> handleNewQueryDir(item, data));
                MenuItem refreshItem = new MenuItem("刷新");
                refreshItem.setOnAction(e -> refreshDbNode(item, data));
                contextMenu.getItems().addAll(newQueryItem, newDirItem, new SeparatorMenuItem(), refreshItem);
            }
            case BACKUP_FOLDER -> {
                MenuItem newBackupItem = new MenuItem("新建备份");
                newBackupItem.setOnAction(e -> module.handleNewBackup(item, data));
                MenuItem newDirItem = new MenuItem("新建目录");
                newDirItem.setOnAction(e -> handleNewBackupDir(item, data));
                MenuItem refreshItem = new MenuItem("刷新");
                refreshItem.setOnAction(e -> refreshDbNode(item, data));
                contextMenu.getItems().addAll(newBackupItem, newDirItem, new SeparatorMenuItem(), refreshItem);
            }
            case QUERY_DIR -> {
                MenuItem newQueryItem = new MenuItem("新建查询");
                newQueryItem.setOnAction(e -> handleNewQuery(item, data));
                MenuItem newDirItem = new MenuItem("新建目录");
                newDirItem.setOnAction(e -> handleNewQueryDir(item, data));
                MenuItem renameItem = new MenuItem("重命名");
                renameItem.setOnAction(e -> handleRenameQueryDir(item, data));
                MenuItem deleteItem = new MenuItem("删除");
                deleteItem.setOnAction(e -> handleDeleteQueryDir(item, data));
                MenuItem refreshItem = new MenuItem("刷新");
                refreshItem.setOnAction(e -> refreshDbNode(item, data));
                contextMenu.getItems().addAll(newQueryItem, newDirItem, new SeparatorMenuItem(), renameItem, deleteItem, new SeparatorMenuItem(), refreshItem);
            }
            case BACKUP_DIR -> {
                MenuItem newBackupItem = new MenuItem("新建备份");
                newBackupItem.setOnAction(e -> module.handleNewBackup(item, data));
                MenuItem newDirItem = new MenuItem("新建目录");
                newDirItem.setOnAction(e -> handleNewBackupDir(item, data));
                MenuItem renameItem = new MenuItem("重命名");
                renameItem.setOnAction(e -> handleRenameBackupDir(item, data));
                MenuItem deleteItem = new MenuItem("删除");
                deleteItem.setOnAction(e -> handleDeleteBackupDir(item, data));
                MenuItem refreshItem = new MenuItem("刷新");
                refreshItem.setOnAction(e -> refreshDbNode(item, data));
                contextMenu.getItems().addAll(newBackupItem, newDirItem, new SeparatorMenuItem(), renameItem, deleteItem, new SeparatorMenuItem(), refreshItem);
            }
            case TABLE, VIEW -> {
                MenuItem openTableItem = new MenuItem("打开表");
                openTableItem.setOnAction(e -> handleTableDataDoubleClick(item, data));
                MenuItem designItem = new MenuItem("设计表");
                designItem.setOnAction(e -> handleTableStructureDoubleClick(item, data));
                MenuItem copyTableItem = new MenuItem("复制表");
                copyTableItem.setOnAction(e -> handleCopyTable(item, data));
                MenuItem deleteItem = new MenuItem("删除");
                deleteItem.setOnAction(e -> module.deleteDbNodes());
                contextMenu.getItems().addAll(openTableItem, designItem, new SeparatorMenuItem(), copyTableItem, new SeparatorMenuItem(), deleteItem);
            }
            case QUERY -> {
                MenuItem openQueryItem = new MenuItem("打开");
                openQueryItem.setOnAction(e -> handleQueryDoubleClick(item, data));
                MenuItem renameQueryItem = new MenuItem("重命名");
                renameQueryItem.setOnAction(e -> handleRenameQuery(item, data));
                MenuItem deleteQueryItem = new MenuItem("删除");
                deleteQueryItem.setOnAction(e -> handleDeleteQuery(item, data));
                contextMenu.getItems().addAll(openQueryItem, new SeparatorMenuItem(), renameQueryItem, deleteQueryItem);
            }
            case BACKUP -> {
                MenuItem restoreItem = new MenuItem("还原备份");
                restoreItem.setOnAction(e -> handleRestoreBackup(item, data));
                MenuItem openDirItem = new MenuItem("打开备份目录");
                openDirItem.setOnAction(e -> handleOpenBackupDir(data));
                MenuItem renameBackupItem = new MenuItem("重命名");
                renameBackupItem.setOnAction(e -> handleRenameBackup(item, data));
                MenuItem deleteBackupItem = new MenuItem("删除");
                deleteBackupItem.setOnAction(e -> handleDeleteBackup(item, data));
                contextMenu.getItems().addAll(restoreItem, new SeparatorMenuItem(), openDirItem, new SeparatorMenuItem(), renameBackupItem, deleteBackupItem);
            }
            default -> {}
        }
    }

    /** 双击数据库节点：已打开则切换展开状态，未打开则打开 */
    public void handleDatabaseDoubleClick(TreeItem<String> dbItem, DatabaseNodeData data) {
        if (data.isOpened()) {
            dbItem.setExpanded(!dbItem.isExpanded());
            return;
        }
        openDatabase(dbItem, data);
    }

    /** 关闭数据库节点：清理子节点数据并恢复未打开状态 */
    public void closeDatabase(TreeItem<String> dbItem, DatabaseNodeData data) {
        // 仅清理子节点的映射，保留 dbItem 自身的 dbNodeDataMap 映射，
        // 否则关闭后双击该节点时 dbNodeDataMap.get(dbItem) 返回 null，无法触发 openDatabase 重新打开
        for (TreeItem<String> child : dbItem.getChildren()) {
            module.removeDbNodeDataRecursive(child);
        }
        dbItem.getChildren().clear();
        data.setOpened(false);
        dbItem.setGraphic(module.getDbNodeIcon(data));
        dbItem.setExpanded(false);

        // 关闭该数据库对应的所有标签：
        //   - 对象标签（setClosable(false) 不可手动关闭，必须程序清理）
        //   - 表数据标签（tabId 无前缀: configId_dbName[_schemaName]_tableName）
        //   - 表结构/设计标签（tabId: struct_configId_dbName[_schemaName]_tableName）
        //   - 新建表标签（tabId: newtable_configId_dbName[_schemaName]）
        // 注意：查询标签（query_*）独立于数据库打开状态，不在此处关闭
        if (module.getTerminalTabPane() != null
                && data.getConnectionConfig() != null
                && data.getDatabaseName() != null) {
            String configId = data.getConnectionConfig().getId();
            String dbName = data.getDatabaseName();
            // 4 种基础前缀（MySQL 无 schema 直接匹配；PostgreSQL/Oracle 带 schema 也会以此开头）
            String basePrefix = configId + "_" + dbName + "_";           // 表数据
            String objectsPrefix = "objects_" + configId + "_" + dbName; // 对象（精确 + 带 schema 前缀）
            String structPrefix = "struct_" + basePrefix;                // 表结构
            String newtablePrefix = "newtable_" + basePrefix;            // 新建表
            // MySQL 对象标签无 schema：精确匹配
            String mysqlObjectsTabId = "objects_" + configId + "_" + dbName;

            ObservableList<Tab> tabs = module.getTerminalTabPane().getTabs();
            tabs.removeIf(t -> {
                Object ud = t.getUserData();
                if (ud instanceof String tabId) {
                    return tabId.equals(mysqlObjectsTabId)        // MySQL 对象标签
                            || tabId.startsWith(objectsPrefix + "_") // PostgreSQL/Oracle 对象标签（带 schema）
                            || tabId.startsWith(basePrefix)          // 表数据标签
                            || tabId.startsWith(structPrefix)        // 表结构/设计标签
                            || tabId.startsWith(newtablePrefix);     // 新建表标签
                }
                return false;
            });
            if (tabs.isEmpty()) {
                module.showWelcomeView();
            }
        }
    }

    /**
     * MySQL 专用主机图标更新：
     * 已连接时使用 mysql_open.png（彩色图标），无论展开还是折叠；
     * 未连接时使用 mysql.png（灰色图标）。
     */
    protected void updateMysqlHostIcon(TreeItem<String> hostItem, ConnectionConfig config) {
        ImageView imageView = new ImageView();
        imageView.setFitWidth(16);
        imageView.setFitHeight(16);
        try {
            boolean connected = module.isHostConnected(hostItem);
            // 根据连接状态选择图标：已连接用彩色图标，未连接用灰色图标
            String iconPath = connected ? "/images/connect/mysql_open.png" : "/images/connect/mysql.png";
            Image icon = new Image(getClass().getResourceAsStream(iconPath));
            if (icon != null) {
                imageView.setImage(icon);
                if (connected) {
                    imageView.setStyle("-fx-effect: dropshadow(gaussian, #4CAF50, 2, 0.5, 0, 0);");
                }
            }
        } catch (Exception e) {
            // fallback
        }
        hostItem.setGraphic(imageView);
    }

    // ==================== 复制表 ====================

    /**
     * 复制表处理：
     *  - 打开配置对话框（CopyTableDialog）让用户选择目标连接/数据库/表名
     *  - 同连接直接复制（CREATE TABLE LIKE + INSERT SELECT）
     *  - 跨连接迁移（DDL + 分页迁移数据）
     */
    public void handleCopyTable(TreeItem<String> tableItem, DatabaseNodeData data) {
        ConnectionConfig srcConfig = data.getConnectionConfig();
        String srcDb = data.getDatabaseName();
        String srcSchema = data.getSchemaName();
        String srcTable = data.getName();
        if (srcConfig == null || srcDb == null || srcTable == null) {
            return;
        }

        // 1. 打开数据传输配置对话框
        CopyTableDialog dialog = new CopyTableDialog(
                module.getStage(),
                module.getConnections(),
                srcConfig, srcDb, srcSchema, java.util.Collections.singletonList(srcTable)
        );
        dialog.showAndWait();
        if (!dialog.isConfirmed()) {
            return;
        }

        ConnectionConfig dstConfig = dialog.getTargetConfig();
        String dstDb = dialog.getTargetDatabase();
        String dstSchema = dialog.getTargetSchema();
        boolean copyStructure = dialog.isCopyStructure();
        boolean copyData = dialog.isCopyData();
        boolean dropIfExists = dialog.isDropIfExists();

        if (dstConfig == null || dstDb == null) {
            return;
        }

        // 2. 后台执行复制操作（单表/多表统一走批量复制）
        final List<String> srcTables = dialog.getSourceTables();
        final List<String> dstTables = dialog.getTargetTables();
        new Thread(() -> {
            try {
                DatabaseService.copyTables(
                        srcConfig, srcDb, srcSchema, srcTables,
                        dstConfig, dstDb, dstSchema, dstTables,
                        copyStructure, copyData, dropIfExists
                );
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION, "表复制成功！", ButtonType.OK);
                    alert.setTitle("复制表");
                    alert.setHeaderText(null);
                    alert.initOwner(module.getStage());
                    alert.showAndWait();

                    // 刷新目标数据库节点
                    refreshDbNodeForConfig(dstConfig, dstDb, dstSchema);
                });
            } catch (Exception ex) {
                String errMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "表复制失败: " + errMsg, ButtonType.OK);
                    alert.setTitle("复制表");
                    alert.setHeaderText(null);
                    alert.initOwner(module.getStage());
                    alert.showAndWait();
                });
            }
        }, "DB-CopyTable").start();
    }

    /** 判断系统剪贴板中是否有 TOMATO_COPY_TABLES 内容 */
    private boolean isClipboardHasTables() {
        Clipboard cb = Clipboard.getSystemClipboard();
        if (cb.hasContent(TableObjectsView.COPY_TABLES_FORMAT)) {
            return true;
        }
        // 兼容纯文本模式（跨进程/重启后）
        if (cb.hasString()) {
            String s = cb.getString();
            return s != null && s.startsWith(TableObjectsView.COPY_TABLES_PREFIX + "\n");
        }
        return false;
    }

    /**
     * 粘贴表处理：从剪贴板读取复制的表元信息，弹出数据传输对话框进行批量复制。
     * 目标连接/数据库由用户在对话框中选择；目标 schema 由目标数据库类型推断。
     */
    public void handlePasteTables(TreeItem<String> targetItem, DatabaseNodeData targetData) {
        handlePasteTables(targetItem, targetData, null);
    }

    /**
     * 粘贴表处理（带完成回调）：传输成功或失败后在 JavaFX 线程回调 onComplete。
     */
    public void handlePasteTables(TreeItem<String> targetItem, DatabaseNodeData targetData, Runnable onComplete) {
        Clipboard cb = Clipboard.getSystemClipboard();
        String content = null;
        if (cb.hasContent(TableObjectsView.COPY_TABLES_FORMAT)) {
            Object raw = cb.getContent(TableObjectsView.COPY_TABLES_FORMAT);
            if (raw instanceof String s) content = s;
        }
        if (content == null && cb.hasString()) {
            String s = cb.getString();
            if (s != null && s.startsWith(TableObjectsView.COPY_TABLES_PREFIX + "\n")) {
                content = s;
            }
        }
        if (content == null) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, "剪贴板中没有可粘贴的表", ButtonType.OK);
            alert.setTitle("粘贴表");
            alert.setHeaderText(null);
            alert.initOwner(module.getStage());
            alert.showAndWait();
            return;
        }

        // 解析剪贴板内容：TOMATO_COPY_TABLES\n{connId}\n{srcDb}\n{srcSchema}\n{table1}\n{table2}...
        String[] lines = content.split("\n", -1);
        if (lines.length < 5) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "剪贴板内容格式不正确", ButtonType.OK);
            alert.setTitle("粘贴表");
            alert.setHeaderText(null);
            alert.initOwner(module.getStage());
            alert.showAndWait();
            return;
        }
        String srcConnId = lines[1];
        String srcDb = lines[2];
        String srcSchema = lines[3].isEmpty() ? null : lines[3];
        List<String> srcTables = new ArrayList<>();
        for (int i = 4; i < lines.length; i++) {
            if (!lines[i].isEmpty()) {
                srcTables.add(lines[i]);
            }
        }
        if (srcTables.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "剪贴板中没有表可粘贴", ButtonType.OK);
            alert.setTitle("粘贴表");
            alert.setHeaderText(null);
            alert.initOwner(module.getStage());
            alert.showAndWait();
            return;
        }

        // 根据 connId 在连接列表中反查源连接配置
        ConnectionConfig srcConfig = null;
        if (srcConnId != null && !srcConnId.isEmpty()) {
            for (ConnectionConfig cfg : module.getConnections()) {
                if (srcConnId.equals(cfg.getId())) {
                    srcConfig = cfg;
                    break;
                }
            }
        }
        if (srcConfig == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING, "找不到源连接（可能已被删除）", ButtonType.OK);
            alert.setTitle("粘贴表");
            alert.setHeaderText(null);
            alert.initOwner(module.getStage());
            alert.showAndWait();
            return;
        }

        // 判断源和目标是否同一连接：同一连接直接传输，不弹对话框
        ConnectionConfig targetCfg = targetData != null ? targetData.getConnectionConfig() : null;
        if (targetCfg != null && targetData.getDatabaseName() != null
                && srcConfig.getId() != null && srcConfig.getId().equals(targetCfg.getId())) {
            // 同一连接：直接进行传输
            executePasteTablesCopy(srcConfig, srcDb, srcSchema, srcTables,
                    targetCfg, targetData.getDatabaseName(), targetData.getSchemaName(),
                    new ArrayList<>(srcTables), true, true, false, onComplete);
            return;
        }

        // 不同连接：打开数据传输配置对话框（目标默认为当前右键的数据库节点）
        CopyTableDialog dialog = new CopyTableDialog(
                module.getStage(),
                module.getConnections(),
                srcConfig, srcDb, srcSchema, srcTables
        );
        // 若右键的是 DATABASE/TABLES_FOLDER 节点，预填目标为该节点对应的连接/数据库
        if (targetData != null && targetData.getConnectionConfig() != null && targetData.getDatabaseName() != null) {
            dialog.presetTarget(targetData.getConnectionConfig(), targetData.getDatabaseName(), targetData.getSchemaName());
        }
        dialog.showAndWait();
        if (!dialog.isConfirmed()) {
            return;
        }

        ConnectionConfig dstConfig = dialog.getTargetConfig();
        String dstDb = dialog.getTargetDatabase();
        String dstSchema = dialog.getTargetSchema();
        boolean copyStructure = dialog.isCopyStructure();
        boolean copyData = dialog.isCopyData();
        boolean dropIfExists = dialog.isDropIfExists();
        if (dstConfig == null || dstDb == null) {
            return;
        }

        executePasteTablesCopy(srcConfig, srcDb, srcSchema, dialog.getSourceTables(),
                dstConfig, dstDb, dstSchema, dialog.getTargetTables(),
                copyStructure, copyData, dropIfExists, onComplete);
    }

    /** 后台执行表粘贴/复制操作 */
    private void executePasteTablesCopy(
            ConnectionConfig srcConfig, String srcDb, String srcSchema, List<String> srcTables,
            ConnectionConfig dstConfig, String dstDb, String dstSchema, List<String> dstTables,
            boolean copyStructure, boolean copyData, boolean dropIfExists, Runnable onComplete) {
        new Thread(() -> {
            try {
                DatabaseService.copyTables(
                        srcConfig, srcDb, srcSchema, srcTables,
                        dstConfig, dstDb, dstSchema, dstTables,
                        copyStructure, copyData, dropIfExists
                );
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION, "表粘贴成功！", ButtonType.OK);
                    alert.setTitle("粘贴表");
                    alert.setHeaderText(null);
                    alert.initOwner(module.getStage());
                    alert.showAndWait();
                    refreshDbNodeForConfig(dstConfig, dstDb, dstSchema);
                    if (onComplete != null) onComplete.run();
                });
            } catch (Exception ex) {
                String errMsg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR, "表粘贴失败: " + errMsg, ButtonType.OK);
                    alert.setTitle("粘贴表");
                    alert.setHeaderText(null);
                    alert.initOwner(module.getStage());
                    alert.showAndWait();
                    if (onComplete != null) onComplete.run();
                });
            }
        }, "DB-PasteTables").start();
    }

    /**
     * 按连接配置+数据库名刷新对应的数据库节点（如果节点已打开）
     */
    private void refreshDbNodeForConfig(ConnectionConfig config, String dbName, String schemaName) {
        if (config == null || dbName == null) return;
        Map<TreeItem<String>, DatabaseNodeData> dataMap = module.getDbNodeDataMap();
        for (Map.Entry<TreeItem<String>, DatabaseNodeData> entry : dataMap.entrySet()) {
            DatabaseNodeData d = entry.getValue();
            if (d.getType() == DatabaseNodeData.NodeType.DATABASE
                    && config.getId() != null
                    && config.getId().equals(d.getConnectionConfig() != null ? d.getConnectionConfig().getId() : null)
                    && dbName.equals(d.getDatabaseName())) {
                if (d.isOpened()) {
                    refreshDbNode(entry.getKey(), d);
                }
                return;
            }
        }
    }

    // ==================== 查询节点 ====================

    /** 新建查询：打开未保存的 SQL 编辑器 Tab，保存时创建查询节点 */
    public void handleNewQuery(TreeItem<String> folderItem, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();
        String dbName = data.getDatabaseName();
        String path = data.getPath();

        SqlEditorView editorView = new SqlEditorView(module.getConnections(), config, dbName);
        editorView.setPath(path);

        Tab tab = new Tab("*未保存查询");
        Image tabIcon = module.getQueryIcon();
        if (tabIcon != null) {
            ImageView tabIconView = new ImageView(tabIcon);
            tabIconView.setFitWidth(14);
            tabIconView.setFitHeight(14);
            tab.setGraphic(ConnectModule.createFixedSizeGraphic(tabIconView));
        }

        String tabId = "query_new_" + System.currentTimeMillis();
        tab.setUserData(tabId);
        tab.setContent(editorView);

        editorView.setOnTitleChange(title -> tab.setText(title));

        editorView.setOnSaveRequest(() -> {
            TextInputDialog dialog = new TextInputDialog("查询" + (folderItem.getChildren().size() + 1));
            dialog.setTitle("保存查询");
            dialog.setHeaderText(null);
            dialog.setContentText("查询名称：");
            dialog.showAndWait().ifPresent(name -> {
                if (name.trim().isEmpty()) return;

                String queryName = name.trim();
                editorView.doSave(queryName);

                TreeItem<String> queryItem = new TreeItem<>(queryName);
                DatabaseNodeData queryData = new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY, queryName, config, dbName, null, path);
                queryItem.setGraphic(module.getDbNodeIcon(queryData));
                module.getDbNodeDataMap().put(queryItem, queryData);
                folderItem.getChildren().add(queryItem);
                folderItem.setExpanded(true);

                editorView.setQueryNode(queryItem);

                String newTabId = "query_" + config.getId() + "_" + dbName + "_" + path + "_" + queryName;
                tab.setUserData(newTabId);
            });
        });

        tab.setOnClosed(e -> {
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        editorView.markModified();

        if (!module.ensureTabPaneInstalled()) return;
        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showDataView();
    }

    /** 双击查询节点：打开 SQL 编辑器 Tab */
    public void handleQueryDoubleClick(TreeItem<String> queryItem, DatabaseNodeData data) {
        if (module.getTerminalTabPane() == null) return;
        if (!module.ensureTabPaneInstalled()) return;

        String tabId = "query_" + data.getConnectionConfig().getId() + "_" + data.getDatabaseName() + "_" + data.getPath() + "_" + data.getName();
        for (Tab tab : module.getTerminalTabPane().getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(tab);
                module.showDataView();
                return;
            }
        }

        SqlEditorView editorView = new SqlEditorView(module.getConnections(), data.getConnectionConfig(), data.getDatabaseName());
        editorView.setQueryName(data.getName());
        editorView.setQueryNode(queryItem);
        editorView.setPath(data.getPath());
        editorView.loadFromFile(data.getConnectionConfig().getName(), data.getDatabaseName(), data.getName(), data.getPath());

        Tab tab = new Tab(data.getName());
        Image tabIcon = module.getQueryIcon();
        if (tabIcon != null) {
            ImageView tabIconView = new ImageView(tabIcon);
            tabIconView.setFitWidth(14);
            tabIconView.setFitHeight(14);
            tab.setGraphic(ConnectModule.createFixedSizeGraphic(tabIconView));
        }
        tab.setContent(editorView);
        tab.setUserData(tabId);

        editorView.setOnTitleChange(title -> tab.setText(title));

        editorView.setOnSaveRequest(() -> {
            TextInputDialog dialog = new TextInputDialog(data.getName());
            dialog.setTitle("保存查询");
            dialog.setHeaderText(null);
            dialog.setContentText("查询名称：");
            dialog.showAndWait().ifPresent(name -> {
                if (name.trim().isEmpty()) return;
                editorView.doSave(name.trim());
                queryItem.setValue(name.trim());
            });
        });

        tab.setOnClosed(e -> {
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showDataView();
    }

    /** 重命名查询节点：重命名文件并更新节点数据 */
    public void handleRenameQuery(TreeItem<String> queryItem, DatabaseNodeData data) {
        TextInputDialog dialog = new TextInputDialog(data.getName());
        dialog.setTitle("重命名查询");
        dialog.setHeaderText(null);
        dialog.setContentText("新名称：");
        dialog.showAndWait().ifPresent(name -> {
            if (name.trim().isEmpty()) return;
            String newName = name.trim();

            String newSanitizedQuery = sanitizeForFs(newName);

            java.nio.file.Path oldFile = SqlEditorView.resolveQueryDir(
                    data.getConnectionConfig().getName(), data.getDatabaseName(), data.getPath())
                    .resolve(sanitizeForFs(data.getName()) + ".sql");
            java.nio.file.Path newFile = SqlEditorView.resolveQueryDir(
                    data.getConnectionConfig().getName(), data.getDatabaseName(), data.getPath())
                    .resolve(newSanitizedQuery + ".sql");

            try {
                if (Files.exists(oldFile)) {
                    String content = Files.readString(oldFile, StandardCharsets.UTF_8);
                    Files.createDirectories(newFile.getParent());
                    Files.writeString(newFile, content, StandardCharsets.UTF_8);
                    Files.deleteIfExists(oldFile);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            queryItem.setValue(newName);
            DatabaseNodeData newData = new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY, newName, data.getConnectionConfig(), data.getDatabaseName(), null, data.getPath());
            module.getDbNodeDataMap().remove(queryItem);
            module.getDbNodeDataMap().put(queryItem, newData);
        });
    }

    /** 删除查询节点：清理文件并移除节点 */
    public void handleDeleteQuery(TreeItem<String> queryItem, DatabaseNodeData data) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除查询");
        confirm.setHeaderText("确定要删除查询 \"" + data.getName() + "\" 吗？");
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                SqlEditorView.cleanupQueryFile(data.getConnectionConfig().getName(), data.getDatabaseName(), data.getName(), data.getPath());
                module.getDbNodeDataMap().remove(queryItem);
                queryItem.getParent().getChildren().remove(queryItem);
            }
        });
    }

    // ==================== 备份节点 ====================

    /** 还原备份：打开还原对话框 */
    public void handleRestoreBackup(TreeItem<String> backupItem, DatabaseNodeData data) {
        Stage stage = module.getStage();
        if (stage == null) return;

        RestoreDialog dialog = new RestoreDialog(stage,
                data.getConnectionConfig(), data.getDatabaseName(), data.getName(), data.getPath());
        dialog.showAndWait();
    }

    /** 打开备份所在目录 */
    public void handleOpenBackupDir(DatabaseNodeData data) {
        java.nio.file.Path backupDir = BackupService.resolveBackupDir(
                data.getConnectionConfig().getName(), data.getDatabaseName(), data.getPath());
        java.nio.file.Path backupFile = backupDir.resolve(data.getName() + ".nb3");

        new Thread(() -> {
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                    if (backupFile.toFile().exists()) {
                        if (desktop.isSupported(java.awt.Desktop.Action.BROWSE_FILE_DIR)) {
                            desktop.browseFileDirectory(backupFile.toFile());
                        } else {
                            desktop.open(backupDir.toFile());
                        }
                    } else {
                        desktop.open(backupDir.toFile());
                    }
                }
            } catch (Exception e) {
                try {
                    String[] cmd = {
                            "xdg-open", backupDir.toAbsolutePath().toString()
                    };
                    Runtime.getRuntime().exec(cmd);
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("打开目录失败");
                        alert.setHeaderText(null);
                        alert.setContentText("无法打开备份目录: " + ex.getMessage());
                        alert.showAndWait();
                    });
                }
            }
        }, "OpenBackupDir").start();
    }

    /** 重命名备份：重命名文件并更新节点数据 */
    public void handleRenameBackup(TreeItem<String> backupItem, DatabaseNodeData data) {
        TextInputDialog dialog = new TextInputDialog(data.getName());
        dialog.setTitle("重命名备份");
        dialog.setHeaderText(null);
        dialog.setContentText("新名称：");
        dialog.showAndWait().ifPresent(name -> {
            if (name.trim().isEmpty()) return;
            String newName = name.trim();
            try {
                BackupService.renameBackupFile(data.getConnectionConfig().getName(),
                        data.getDatabaseName(), data.getName(), newName, data.getPath());
                backupItem.setValue(newName);
                DatabaseNodeData newData = new DatabaseNodeData(DatabaseNodeData.NodeType.BACKUP,
                        newName, data.getConnectionConfig(), data.getDatabaseName(), null, data.getPath());
                module.getDbNodeDataMap().remove(backupItem);
                module.getDbNodeDataMap().put(backupItem, newData);
            } catch (Exception e) {
                Alert err = new Alert(Alert.AlertType.ERROR);
                err.setTitle("重命名失败");
                err.setHeaderText(null);
                err.setContentText(e.getMessage());
                err.showAndWait();
            }
        });
    }

    /** 删除备份节点：删除文件并移除节点 */
    public void handleDeleteBackup(TreeItem<String> backupItem, DatabaseNodeData data) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除备份");
        confirm.setHeaderText("确定要删除备份 \"" + data.getName() + "\" 吗？");
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                BackupService.deleteBackupFile(data.getConnectionConfig().getName(),
                        data.getDatabaseName(), data.getName(), data.getPath());
                module.getDbNodeDataMap().remove(backupItem);
                backupItem.getParent().getChildren().remove(backupItem);
            }
        });
    }

    // ==================== 查询/备份目录节点 ====================

    /** 新建查询目录：在父目录（QUERY_FOLDER 或 QUERY_DIR）下创建子目录 */
    public void handleNewQueryDir(TreeItem<String> folderItem, DatabaseNodeData data) {
        TextInputDialog dialog = new TextInputDialog("新目录");
        dialog.setTitle("新建目录");
        dialog.setHeaderText(null);
        dialog.setContentText("目录名称：");
        dialog.showAndWait().ifPresent(name -> {
            if (name.trim().isEmpty()) return;
            String dirName = name.trim();
            String parentPath = data.getPath();
            String dirPath = (parentPath == null || parentPath.isEmpty()) ? dirName : parentPath + "/" + dirName;

            try {
                Files.createDirectories(SqlEditorView.resolveQueryDir(
                        data.getConnectionConfig().getName(), data.getDatabaseName(), dirPath));
            } catch (IOException e) {
                Alert err = new Alert(Alert.AlertType.ERROR);
                err.setTitle("创建目录失败");
                err.setHeaderText(null);
                err.setContentText(e.getMessage());
                err.showAndWait();
                return;
            }

            TreeItem<String> dirItem = new TreeItem<>(dirName);
            DatabaseNodeData dirData = new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY_DIR, dirName,
                    data.getConnectionConfig(), data.getDatabaseName(), null, dirPath);
            dirItem.setGraphic(module.getDbNodeIcon(dirData));
            module.getDbNodeDataMap().put(dirItem, dirData);
            folderItem.getChildren().add(0, dirItem);
            folderItem.setExpanded(true);
        });
    }

    /** 新建备份目录 */
    public void handleNewBackupDir(TreeItem<String> folderItem, DatabaseNodeData data) {
        TextInputDialog dialog = new TextInputDialog("新目录");
        dialog.setTitle("新建目录");
        dialog.setHeaderText(null);
        dialog.setContentText("目录名称：");
        dialog.showAndWait().ifPresent(name -> {
            if (name.trim().isEmpty()) return;
            String dirName = name.trim();
            String parentPath = data.getPath();
            String dirPath = (parentPath == null || parentPath.isEmpty()) ? dirName : parentPath + "/" + dirName;

            try {
                Files.createDirectories(BackupService.resolveBackupDir(
                        data.getConnectionConfig().getName(), data.getDatabaseName(), dirPath));
            } catch (IOException e) {
                Alert err = new Alert(Alert.AlertType.ERROR);
                err.setTitle("创建目录失败");
                err.setHeaderText(null);
                err.setContentText(e.getMessage());
                err.showAndWait();
                return;
            }

            TreeItem<String> dirItem = new TreeItem<>(dirName);
            DatabaseNodeData dirData = new DatabaseNodeData(DatabaseNodeData.NodeType.BACKUP_DIR, dirName,
                    data.getConnectionConfig(), data.getDatabaseName(), null, dirPath);
            dirItem.setGraphic(module.getDbNodeIcon(dirData));
            module.getDbNodeDataMap().put(dirItem, dirData);
            folderItem.getChildren().add(0, dirItem);
            folderItem.setExpanded(true);
        });
    }

    /** 重命名查询目录：移动磁盘目录并刷新子节点 */
    public void handleRenameQueryDir(TreeItem<String> dirItem, DatabaseNodeData data) {
        TextInputDialog dialog = new TextInputDialog(data.getName());
        dialog.setTitle("重命名目录");
        dialog.setHeaderText(null);
        dialog.setContentText("新名称：");
        dialog.showAndWait().ifPresent(name -> {
            if (name.trim().isEmpty()) return;
            String newName = name.trim();
            String currentPath = data.getPath();
            int lastSlash = currentPath.lastIndexOf('/');
            String parentOf = lastSlash < 0 ? "" : currentPath.substring(0, lastSlash);
            String newPath = parentOf.isEmpty() ? newName : parentOf + "/" + newName;

            try {
                java.nio.file.Path oldDir = SqlEditorView.resolveQueryDir(
                        data.getConnectionConfig().getName(), data.getDatabaseName(), currentPath);
                java.nio.file.Path newDir = SqlEditorView.resolveQueryDir(
                        data.getConnectionConfig().getName(), data.getDatabaseName(), newPath);
                Files.move(oldDir, newDir);
            } catch (IOException e) {
                Alert err = new Alert(Alert.AlertType.ERROR);
                err.setTitle("重命名失败");
                err.setHeaderText(null);
                err.setContentText(e.getMessage());
                err.showAndWait();
                return;
            }

            dirItem.setValue(newName);
            DatabaseNodeData newData = new DatabaseNodeData(DatabaseNodeData.NodeType.QUERY_DIR, newName,
                    data.getConnectionConfig(), data.getDatabaseName(), null, newPath);
            module.getDbNodeDataMap().remove(dirItem);
            module.getDbNodeDataMap().put(dirItem, newData);
            refreshDbNode(dirItem, newData);
        });
    }

    /** 重命名备份目录 */
    public void handleRenameBackupDir(TreeItem<String> dirItem, DatabaseNodeData data) {
        TextInputDialog dialog = new TextInputDialog(data.getName());
        dialog.setTitle("重命名目录");
        dialog.setHeaderText(null);
        dialog.setContentText("新名称：");
        dialog.showAndWait().ifPresent(name -> {
            if (name.trim().isEmpty()) return;
            String newName = name.trim();
            String currentPath = data.getPath();
            int lastSlash = currentPath.lastIndexOf('/');
            String parentOf = lastSlash < 0 ? "" : currentPath.substring(0, lastSlash);
            String newPath = parentOf.isEmpty() ? newName : parentOf + "/" + newName;

            try {
                java.nio.file.Path oldDir = BackupService.resolveBackupDir(
                        data.getConnectionConfig().getName(), data.getDatabaseName(), currentPath);
                java.nio.file.Path newDir = BackupService.resolveBackupDir(
                        data.getConnectionConfig().getName(), data.getDatabaseName(), newPath);
                Files.move(oldDir, newDir);
            } catch (IOException e) {
                Alert err = new Alert(Alert.AlertType.ERROR);
                err.setTitle("重命名失败");
                err.setHeaderText(null);
                err.setContentText(e.getMessage());
                err.showAndWait();
                return;
            }

            dirItem.setValue(newName);
            DatabaseNodeData newData = new DatabaseNodeData(DatabaseNodeData.NodeType.BACKUP_DIR, newName,
                    data.getConnectionConfig(), data.getDatabaseName(), null, newPath);
            module.getDbNodeDataMap().remove(dirItem);
            module.getDbNodeDataMap().put(dirItem, newData);
            refreshDbNode(dirItem, newData);
        });
    }

    /** 删除查询目录：递归删除磁盘目录与所有内容 */
    public void handleDeleteQueryDir(TreeItem<String> dirItem, DatabaseNodeData data) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除目录");
        confirm.setHeaderText("确定要删除目录 \"" + data.getName() + "\" 及其所有内容吗？");
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                SqlEditorView.deleteQueryDir(data.getConnectionConfig().getName(),
                        data.getDatabaseName(), data.getPath());
                module.removeDbNodeDataRecursive(dirItem);
                dirItem.getParent().getChildren().remove(dirItem);
            }
        });
    }

    /** 删除备份目录 */
    public void handleDeleteBackupDir(TreeItem<String> dirItem, DatabaseNodeData data) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除目录");
        confirm.setHeaderText("确定要删除目录 \"" + data.getName() + "\" 及其所有内容吗？");
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                BackupService.deleteBackupDir(data.getConnectionConfig().getName(),
                        data.getDatabaseName(), data.getPath());
                module.removeDbNodeDataRecursive(dirItem);
                dirItem.getParent().getChildren().remove(dirItem);
            }
        });
    }

    // ==================== 工具方法 ====================

    /** 文件系统名称清理：替换非法字符 */
    private String sanitizeForFs(String name) {
        if (name == null || name.isEmpty()) return "unnamed";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("_{2,}", "_")
                .replaceAll("^_|_$", "");
    }
}
