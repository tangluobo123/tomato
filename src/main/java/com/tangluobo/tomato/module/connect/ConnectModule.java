package com.tangluobo.tomato.module.connect;

import com.tangluobo.tomato.module.Module;
import com.tangluobo.tomato.module.connect.dialog.BackupDialog;
import com.tangluobo.tomato.module.connect.dialog.ConnectionConfigDialog;
import com.tangluobo.tomato.module.connect.dialog.ExportConnectionDialog;
import com.tangluobo.tomato.module.connect.dialog.FolderDialog;
import com.tangluobo.tomato.module.connect.dialog.RestoreDialog;
import com.tangluobo.tomato.module.connect.handler.*;
import com.tangluobo.tomato.module.connect.ToolType;
import com.tangluobo.tomato.module.connect.service.BackupService;
import com.tangluobo.tomato.module.connect.service.DatabaseService;
import com.tangluobo.tomato.module.connect.service.RedisService;
import com.tangluobo.tomato.module.connect.service.RocketmqService;
import com.tangluobo.tomato.module.connect.service.KafkaService;
import com.tangluobo.tomato.module.connect.view.RocketmqDataView;
import com.tangluobo.tomato.module.connect.view.SqlEditorView;
import com.tangluobo.tomato.module.connect.view.ToolPane;
import com.tangluobo.tomato.module.connect.view.ColorTransposeGamePane;
import com.tangluobo.tomato.module.tools.ServerManagerPane;
import com.tangluobo.tomato.module.tools.server.ServerConfig;
import com.tangluobo.tomato.ssh.LocalTerminalPane;
import com.tangluobo.tomato.ssh.SSHTerminalPane;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import com.tangluobo.tomato.utils.SecurityUtils;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.beans.value.ChangeListener;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class ConnectModule implements Module {
    private TreeView<String> treeView;
    TreeItem<String> root;
    private List<ConnectionConfig> connections;
    private Map<TreeItem<String>, ConnectionConfig> itemConfigMap;
    private Map<TreeItem<String>, DatabaseNodeData> dbNodeDataMap;
    private Map<TreeItem<String>, Boolean> connectionStateMap;
    private Set<TreeItem<String>> connectingHosts;
    private TreeItem<String> editingItem;
    private TreeItem<String> selectedItemBeforeClick;
    private TreeItem<String> recentlyEditedItem;
    /** 底部空白占位节点：始终作为 root 最后一个子节点，在滚动区域内保留空白方便右键创建根节点 */
    private final TreeItem<String> bottomSpacer = new TreeItem<>("");
    private Timeline singleClickTimer;
    private Image folderIcon;
    private Image dbIcon;
    private Image dbIconGray;
    private Image schemaIcon;
    private Image schemaOpenIcon;
    private Image tableIcon;
    private Image viewIcon;
    private Image functionIcon;
    private Image backupIcon;
    private Image queryIcon;
    private Image rocketmqTopicIcon;
    private Image rocketmqConsumerIcon;
    private Image rocketmqClusterIcon;
    private Image rocketmqMessageIcon;
    private Image rocketmqTopicItemIcon;
    private Image rocketmqConsumerItemIcon;
    private Image rocketmqBrokerItemIcon;
    private Image rocketmqMessageItemIcon;
    // ===== Kafka 图标（复用现有图片资源，与 RocketMQ 风格一致）=====
    private Image kafkaTopicIcon;
    private Image kafkaConsumerIcon;
    private Image kafkaClusterIcon;
    private Image kafkaTopicItemIcon;
    private Image kafkaConsumerItemIcon;
    private Image kafkaBrokerItemIcon;
    private Image aliyunProductIcon;
    private Image aliyunEcsIcon;
    private Image aliyunDomainIcon;
    private Image localFileIcon;
    private Image mdFileIcon;
    private TextField searchField;

    // 内容区域
    private VBox contentArea;
    TabPane terminalTabPane;

    @Override
    public String getName() {
        return "连接";
    }

    @Override
    public void loadSidebar(VBox sidebarContainer) {
        folderIcon = loadFolderIcon();
        loadDbIcons();

        sidebarContainer.setStyle("-fx-background-color: #ffffff; -fx-padding: 0; -fx-border-color: transparent; -fx-border-width: 0;");
        sidebarContainer.setSpacing(0);

        HBox headerBar = new HBox();
        headerBar.setStyle("-fx-background-color: #ffffff; -fx-border-color: #D9D9D7; -fx-border-width: 0 0 1 0;");
        headerBar.setPrefHeight(52);
        headerBar.setMinHeight(52);
        headerBar.setMaxHeight(52);
        headerBar.setPadding(new Insets(10, 15, 10, 15));

        TextField searchField = new TextField();
        searchField.setPromptText("搜索");
        searchField.setStyle("-fx-background-color: #f0f0f0; -fx-border-radius: 6px; -fx-background-radius: 6px; -fx-padding: 6 10; -fx-font-size: 13px; -fx-border-color: transparent;");
        searchField.prefWidthProperty().bind(headerBar.widthProperty());
        this.searchField = searchField;

        headerBar.getChildren().add(searchField);

        treeView = new TreeView<>();
        treeView.setStyle("-fx-background-color: transparent; -fx-cell-size: 35px;");
        treeView.setFixedCellSize(35);
        treeView.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        root = new TreeItem<>("连接");
        root.setExpanded(true);
        treeView.setRoot(root);
        treeView.setShowRoot(false);
        treeView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        itemConfigMap = new HashMap<>();
        dbNodeDataMap = new HashMap<>();
        connectionStateMap = new HashMap<>();
        connectingHosts = new HashSet<>();
        connections = ConfigManager.loadConnections();
        loadTree();

        // 注册 JVM 退出钩子：应用关闭前最后一次兜底保存连接配置，避免异常情况下丢失修改
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    ConfigManager.saveConnections(connections);
                } catch (ConfigManager.SaveException e) {
                    System.err.println("[ConnectModule-ShutdownHook] 退出时保存连接配置失败: " + e.getMessage());
                    e.printStackTrace();
                }
            }, "tomato-connect-save-hook"));
        } catch (IllegalStateException | SecurityException ignored) {
            // JVM 已在关闭中或无权限，忽略
        }

        setupContextMenu();
        setupDragAndDrop();

        searchField.textProperty().addListener((obs, oldVal, newVal) -> filterTree(newVal));

        sidebarContainer.getChildren().addAll(headerBar, treeView);
        treeView.prefHeightProperty().bind(sidebarContainer.heightProperty().subtract(50));
    }

    private Image loadFolderIcon() {
        try {
            return new Image(getClass().getResourceAsStream("/images/connect/folder.png"));
        } catch (Exception e) {
            return null;
        }
    }

    private void loadDbIcons() {
        try { dbIcon = new Image(getClass().getResourceAsStream("/images/connect/database.png")); } catch (Exception e) { dbIcon = null; }
        try { dbIconGray = new Image(getClass().getResourceAsStream("/images/connect/database_gray.png")); } catch (Exception e) { dbIconGray = null; }
        try { schemaIcon = new Image(getClass().getResourceAsStream("/images/connect/mod.png")); } catch (Exception e) { schemaIcon = null; }
        try { schemaOpenIcon = new Image(getClass().getResourceAsStream("/images/connect/mod_open.png")); } catch (Exception e) { schemaOpenIcon = null; }
        try { tableIcon = new Image(getClass().getResourceAsStream("/images/connect/table.png")); } catch (Exception e) { tableIcon = null; }
        try { viewIcon = new Image(getClass().getResourceAsStream("/images/connect/view.png")); } catch (Exception e) { viewIcon = null; }
        try { queryIcon = new Image(getClass().getResourceAsStream("/images/connect/query.png")); } catch (Exception e) { queryIcon = null; }
        try { functionIcon = new Image(getClass().getResourceAsStream("/images/connect/function.png")); } catch (Exception e) { functionIcon = null; }
        try { backupIcon = new Image(getClass().getResourceAsStream("/images/connect/backup.png")); } catch (Exception e) { backupIcon = null; }
        try { rocketmqTopicIcon = new Image(getClass().getResourceAsStream("/images/connect/table.png")); } catch (Exception e) { rocketmqTopicIcon = null; }
        try { rocketmqConsumerIcon = new Image(getClass().getResourceAsStream("/images/connect/user.png")); } catch (Exception e) { rocketmqConsumerIcon = null; }
        try { rocketmqClusterIcon = new Image(getClass().getResourceAsStream("/images/connect/monitor.png")); } catch (Exception e) { rocketmqClusterIcon = null; }
        try { rocketmqMessageIcon = new Image(getClass().getResourceAsStream("/images/connect/code.png")); } catch (Exception e) { rocketmqMessageIcon = null; }
        try { rocketmqTopicItemIcon = new Image(getClass().getResourceAsStream("/images/connect/table.png")); } catch (Exception e) { rocketmqTopicItemIcon = null; }
        try { rocketmqConsumerItemIcon = new Image(getClass().getResourceAsStream("/images/connect/user.png")); } catch (Exception e) { rocketmqConsumerItemIcon = null; }
        try { rocketmqBrokerItemIcon = new Image(getClass().getResourceAsStream("/images/connect/monitor.png")); } catch (Exception e) { rocketmqBrokerItemIcon = null; }
        try { rocketmqMessageItemIcon = new Image(getClass().getResourceAsStream("/images/connect/code.png")); } catch (Exception e) { rocketmqMessageItemIcon = null; }
        try { kafkaTopicIcon = new Image(getClass().getResourceAsStream("/images/connect/table.png")); } catch (Exception e) { kafkaTopicIcon = null; }
        try { kafkaConsumerIcon = new Image(getClass().getResourceAsStream("/images/connect/user.png")); } catch (Exception e) { kafkaConsumerIcon = null; }
        try { kafkaClusterIcon = new Image(getClass().getResourceAsStream("/images/connect/monitor.png")); } catch (Exception e) { kafkaClusterIcon = null; }
        try { kafkaTopicItemIcon = new Image(getClass().getResourceAsStream("/images/connect/table.png")); } catch (Exception e) { kafkaTopicItemIcon = null; }
        try { kafkaConsumerItemIcon = new Image(getClass().getResourceAsStream("/images/connect/user.png")); } catch (Exception e) { kafkaConsumerItemIcon = null; }
        try { kafkaBrokerItemIcon = new Image(getClass().getResourceAsStream("/images/connect/monitor.png")); } catch (Exception e) { kafkaBrokerItemIcon = null; }
        try { aliyunProductIcon = new Image(getClass().getResourceAsStream("/images/connect/monitor.png")); } catch (Exception e) { aliyunProductIcon = null; }
        try { aliyunEcsIcon = new Image(getClass().getResourceAsStream("/images/connect/server.png")); } catch (Exception e) { aliyunEcsIcon = null; }
        try { aliyunDomainIcon = new Image(getClass().getResourceAsStream("/images/connect/s3.png")); } catch (Exception e) { aliyunDomainIcon = null; }
        try { localFileIcon = new Image(getClass().getResourceAsStream("/images/connect/code.png")); } catch (Exception e) { localFileIcon = null; }
        try { mdFileIcon = new Image(getClass().getResourceAsStream("/images/connect/md.png")); } catch (Exception e) { mdFileIcon = null; }
    }

    /**
     * 根据连接类型创建对应的数据库处理器
     */
    AbstractDbHandler createDbHandler(ConnectionConfig config) {
        return switch (config.getType()) {
            case MYSQL -> new MysqlDbHandler(this);
            case POSTGRESQL -> new PostgresDbHandler(this);
            case ORACLE -> new OracleDbHandler(this);
            default -> null;
        };
    }

    /** 供 handler 调用：根据节点数据获取图标 */
    public ImageView getDbNodeIcon(DatabaseNodeData data) {
        ImageView iv = new ImageView();
        iv.setFitWidth(20);
        iv.setFitHeight(20);
        Image icon = switch (data.getType()) {
            case DATABASE -> data.isOpened() ? dbIcon : dbIconGray;
            case REDIS_DB -> data.isOpened() ? dbIcon : dbIconGray;
            case SCHEMA -> data.isOpened() ? schemaOpenIcon : schemaIcon;
            case TABLES_FOLDER -> tableIcon;
            case VIEWS_FOLDER -> viewIcon;
            case QUERY_FOLDER -> queryIcon;
            case FUNCTION_FOLDER -> functionIcon;
            case BACKUP_FOLDER -> backupIcon;
            case TABLE -> tableIcon;
            case VIEW -> viewIcon;
            case BACKUP -> backupIcon;
            case QUERY -> queryIcon;
            case QUERY_DIR -> folderIcon;
            case BACKUP_DIR -> folderIcon;
            case ROCKETMQ_TOPICS_FOLDER -> rocketmqTopicIcon;
            case ROCKETMQ_CONSUMERS_FOLDER -> rocketmqConsumerIcon;
            case ROCKETMQ_CLUSTER_FOLDER -> rocketmqClusterIcon;
            case ROCKETMQ_MESSAGES_FOLDER -> rocketmqMessageIcon;
            case ROCKETMQ_TOPIC -> rocketmqTopicItemIcon;
            case ROCKETMQ_CONSUMER -> rocketmqConsumerItemIcon;
            case ROCKETMQ_BROKER -> rocketmqBrokerItemIcon;
            case ROCKETMQ_MESSAGE -> rocketmqMessageItemIcon;
            case KAFKA_TOPICS_FOLDER -> kafkaTopicIcon;
            case KAFKA_CONSUMERS_FOLDER -> kafkaConsumerIcon;
            case KAFKA_CLUSTER_FOLDER -> kafkaClusterIcon;
            case KAFKA_TOPIC -> kafkaTopicItemIcon;
            case KAFKA_CONSUMER -> kafkaConsumerItemIcon;
            case KAFKA_BROKER -> kafkaBrokerItemIcon;
            case ALIYUN_PRODUCT_FOLDER -> aliyunProductIcon;
            case ALIYUN_ECS_INSTANCE -> aliyunEcsIcon;
            case ALIYUN_DOMAIN -> aliyunDomainIcon;
            case LOCAL_DIR_FOLDER -> folderIcon;
            case LOCAL_DIR_FILE -> {
                String n = data.getName();
                if (n != null) {
                    String lower = n.toLowerCase();
                    if (lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".mdown") || lower.endsWith(".mkd")) {
                        yield mdFileIcon != null ? mdFileIcon : localFileIcon;
                    }
                }
                yield localFileIcon;
            }
        };
        if (icon != null) iv.setImage(icon);
        return iv;
    }

    /** 供 handler 调用：获取表图标（原始 Image） */
    public Image getTableIcon() { return tableIcon; }

    /** 供 handler 调用：获取视图图标（原始 Image） */
    public Image getViewIcon() { return viewIcon; }

    /** 供 handler 调用：获取查询图标（原始 Image） */
    public Image getQueryIcon() { return queryIcon; }

    /** 供 handler 调用：获取连接配置列表 */
    public List<ConnectionConfig> getConnections() { return connections; }

    /** 供 handler 调用：判断主机节点是否已连接 */
    public boolean isHostConnected(TreeItem<String> hostItem) {
        Boolean connected = connectionStateMap.get(hostItem);
        return connected != null && connected;
    }

    private void loadTree() {
        root.getChildren().clear();
        itemConfigMap.clear();
        dbNodeDataMap.clear();
        buildConnectionTree();
        root.getChildren().add(bottomSpacer);
    }

    /**
     * 根据 connections 列表构建连接树。
     * 先创建所有节点并建立 id→item 映射，再按 parentId 连接父子关系，
     * 避免列表中子连接排在父连接之前时（如拖动移动后）找不到父节点而丢失。
     */
    private void buildConnectionTree() {
        Map<String, TreeItem<String>> idToItem = new HashMap<>();
        for (ConnectionConfig config : connections) {
            TreeItem<String> item = createTreeItem(config);
            idToItem.put(config.getId(), item);
        }
        for (ConnectionConfig config : connections) {
            TreeItem<String> item = idToItem.get(config.getId());
            String pid = config.getParentId();
            if (pid == null || pid.isEmpty()) {
                root.getChildren().add(item);
            } else {
                TreeItem<String> parent = idToItem.get(pid);
                if (parent != null) {
                    parent.getChildren().add(item);
                } else {
                    // 父节点不存在，作为根节点的子节点兜底，避免连接丢失
                    root.getChildren().add(item);
                }
            }
        }
    }

    private void filterTree(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            loadTree();
            return;
        }
        String kw = keyword.trim().toLowerCase();

        root.getChildren().clear();
        itemConfigMap.clear();
        buildConnectionTree();
        root.getChildren().add(bottomSpacer);

        filterTreeItem(root, kw);
        expandAll(root);
    }

    private boolean filterTreeItem(TreeItem<String> item, String keyword) {
        if (item == bottomSpacer) {
            return true;
        }
        boolean selfMatch = item.getValue() != null && item.getValue().toLowerCase().contains(keyword);

        if (selfMatch) {
            for (TreeItem<String> child : item.getChildren()) {
                filterTreeItem(child, keyword);
            }
            return true;
        }

        boolean childMatch = false;
        List<TreeItem<String>> toRemove = new ArrayList<>();
        for (TreeItem<String> child : item.getChildren()) {
            boolean match = filterTreeItem(child, keyword);
            if (match) {
                childMatch = true;
            } else {
                toRemove.add(child);
            }
        }
        item.getChildren().removeAll(toRemove);
        return childMatch;
    }

    private void expandAll(TreeItem<String> item) {
        item.setExpanded(true);
        for (TreeItem<String> child : item.getChildren()) {
            expandAll(child);
        }
    }

    private TreeItem<String> createTreeItem(ConnectionConfig config) {
        TreeItem<String> item = new TreeItem<>(config.getName());
        item.setGraphic(getIconForConfig(config));
        itemConfigMap.put(item, config);
        connectionStateMap.put(item, false);

        if (config.getType() == ConnectType.MYSQL) {
            item.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
                updateHostIcon(item, config, connectionStateMap.getOrDefault(item, false));
            });
        }

        if (config.getType() == ConnectType.REDIS) {
            item.expandedProperty().addListener((obs, wasExpanded, isExpanded) -> {
                Boolean connected = connectionStateMap.get(item);
                if (connected != null && connected) {
                    updateHostIcon(item, config, true);
                }
            });
        }

        return item;
    }

    /** 供 handler 调用：根据连接配置获取图标 */
    public ImageView getIconForConfig(ConnectionConfig config) {
        ImageView imageView = new ImageView();
        imageView.setFitWidth(20);
        imageView.setFitHeight(20);

        if (config.getType() == null) {
            if (folderIcon != null) {
                imageView.setImage(folderIcon);
            }
        } else if (config.getType() == ConnectType.TOOL) {
            // 工具节点：按 toolType 显示对应图标
            ToolType toolType = ToolType.fromCode(config.getToolType());
            String iconPath = toolType != null ? toolType.getIconPath() : ConnectType.TOOL.getIconPath();
            try {
                Image icon = new Image(getClass().getResourceAsStream(iconPath));
                if (icon != null) {
                    imageView.setImage(icon);
                }
            } catch (Exception e) {
            }
        } else {
            try {
                String iconPath = config.getType().getIconPath();
                Image icon = new Image(getClass().getResourceAsStream(iconPath));
                if (icon != null) {
                    imageView.setImage(icon);
                }
            } catch (Exception e) {
            }
        }
        return imageView;
    }

    /** 供 handler 调用：获取树根节点 */
    public TreeItem<String> getRoot() {
        return root;
    }

    /** 供 handler 调用：获取连接中的主机节点集合 */
    public Set<TreeItem<String>> getConnectingHosts() {
        return connectingHosts;
    }

    /** 供 handler 调用：获取节点数据映射 */
    public Map<TreeItem<String>, DatabaseNodeData> getDbNodeDataMap() {
        return dbNodeDataMap;
    }

    /** 供 handler 调用：获取树视图 */
    public TreeView<String> getTreeView() {
        return treeView;
    }

    /** 供 handler 调用：更新连接状态映射 */
    public void markConnectionState(TreeItem<String> hostItem, boolean connected) {
        connectionStateMap.put(hostItem, connected);
    }

    /** 供 handler 调用：根据 ID 查找树节点 */
    public TreeItem<String> findItemById(TreeItem<String> root, String id) {
        ConnectionConfig config = itemConfigMap.get(root);
        if (config != null && config.getId().equals(id)) {
            return root;
        }
        for (TreeItem<String> child : root.getChildren()) {
            TreeItem<String> found = findItemById(child, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private ContextMenu contextMenu;

    private void setupContextMenu() {
        contextMenu = new ContextMenu();

        treeView.setOnContextMenuRequested(event -> {
            contextMenu.hide();
            contextMenu.getItems().clear();

            Node node = event.getPickResult().getIntersectedNode();
            TreeItem<String> clickedItem = null;
            while (node != null && !(node instanceof TreeCell)) {
                node = node.getParent();
            }
            if (node instanceof TreeCell<?> cell) {
                clickedItem = (TreeItem<String>) cell.getTreeItem();
            }

            final TreeItem<String> targetItem = clickedItem;

            if (targetItem == null || targetItem == bottomSpacer) {
                MenuItem addFolder = new MenuItem("新建目录");
                addFolder.setOnAction(e -> handleAddFolder(root));
                MenuItem addConnection = new MenuItem("新建连接");
                addConnection.setOnAction(e -> handleAddConnection(root));
                MenuItem importItem = new MenuItem("导入连接");
                importItem.setOnAction(e -> handleImportConnections());
                MenuItem exportItem = new MenuItem("导出连接");
                exportItem.setOnAction(e -> handleExportConnections());
                contextMenu.getItems().addAll(addFolder, addConnection, new SeparatorMenuItem(), importItem, exportItem);
            } else {
                DatabaseNodeData dbData = dbNodeDataMap.get(targetItem);
                if (dbData != null) {
                    // 委托给对应连接类型的 handler 构建节点右键菜单
                    ConnectHandler handler = createConnectHandler(dbData.getConnectionConfig());
                    if (handler != null) {
                        handler.populateNodeContextMenu(this, contextMenu, targetItem, dbData);
                    }
                } else {
                    ConnectionConfig targetConfig = itemConfigMap.get(targetItem);
                    if (targetConfig != null && targetConfig.getType() != null) {
                        boolean isDatabase = targetConfig.getType() == ConnectType.MYSQL
                                || targetConfig.getType() == ConnectType.POSTGRESQL
                                || targetConfig.getType() == ConnectType.ORACLE;
                        boolean isRedis = targetConfig.getType() == ConnectType.REDIS;
                        boolean isRocketmq = targetConfig.getType() == ConnectType.ROCKETMQ;
                        boolean isKafka = targetConfig.getType() == ConnectType.KAFKA;
                        boolean isLocalDirectory = targetConfig.getType() == ConnectType.LOCAL_DIRECTORY;
                        if (isDatabase) {
                            MenuItem createDbItem = new MenuItem("新建数据库");
                            boolean connOpened = !targetItem.getChildren().isEmpty();
                            createDbItem.setDisable(!connOpened);
                            createDbItem.setOnAction(e -> {
                                AbstractDbHandler h = createDbHandler(targetConfig);
                                if (h != null) h.handleCreateDatabase(targetItem, targetConfig);
                            });
                            contextMenu.getItems().add(createDbItem);
                            if (connOpened) {
                                MenuItem closeConnItem = new MenuItem("关闭连接");
                                closeConnItem.setOnAction(e -> closeHostConnection(targetItem, targetConfig));
                                contextMenu.getItems().addAll(closeConnItem, new SeparatorMenuItem());
                            }
                        }
                        if (isRedis) {
                            if (!targetItem.getChildren().isEmpty()) {
                                MenuItem closeConnItem = new MenuItem("关闭连接");
                                closeConnItem.setOnAction(e -> closeHostConnection(targetItem, targetConfig));
                                contextMenu.getItems().addAll(closeConnItem, new SeparatorMenuItem());
                            }
                        }
                        if (isRocketmq) {
                            if (!targetItem.getChildren().isEmpty()) {
                                MenuItem closeConnItem = new MenuItem("关闭连接");
                                closeConnItem.setOnAction(e -> closeHostConnection(targetItem, targetConfig));
                                contextMenu.getItems().addAll(closeConnItem, new SeparatorMenuItem());
                            }
                        }
                        if (isKafka) {
                            if (!targetItem.getChildren().isEmpty()) {
                                MenuItem closeConnItem = new MenuItem("关闭连接");
                                closeConnItem.setOnAction(e -> closeHostConnection(targetItem, targetConfig));
                                contextMenu.getItems().addAll(closeConnItem, new SeparatorMenuItem());
                            }
                        }
                        if (isLocalDirectory) {
                            MenuItem createDirItem = new MenuItem("新建目录");
                            createDirItem.setGraphic(createMenuIcon("folder.png"));
                            createDirItem.setOnAction(e -> {
                                ConnectHandler dh = createConnectHandler(targetConfig);
                                if (dh instanceof LocalDirectoryConnectHandler ld) {
                                    ld.handleCreateSubdirectoryAtHost(this, targetItem, targetConfig);
                                }
                            });
                            contextMenu.getItems().add(createDirItem);

                            MenuItem createMdItem = new MenuItem("新建 Markdown 文档");
                            createMdItem.setGraphic(createMenuIcon("md_add.png"));
                            createMdItem.setOnAction(e -> {
                                ConnectHandler h = createConnectHandler(targetConfig);
                                if (h instanceof LocalDirectoryConnectHandler ld) {
                                    ld.handleCreateMarkdownAtHost(this, targetItem, targetConfig);
                                }
                            });
                            contextMenu.getItems().add(createMdItem);
                        }
                        MenuItem renameItem = new MenuItem("重命名");
                        renameItem.setOnAction(e -> {
                            editingItem = targetItem;
                            Platform.runLater(() -> {
                                treeView.requestFocus();
                                treeView.setEditable(true);
                                treeView.edit(targetItem);
                            });
                        });
                        MenuItem copyItem = new MenuItem("复制连接");
                        copyItem.setOnAction(e -> handleCopyConnection(targetItem, targetConfig));
                        MenuItem deleteItem = new MenuItem("删除");
                        deleteItem.setOnAction(e -> handleDelete(targetItem));
                        boolean isTool = targetConfig.getType() == ConnectType.TOOL;
                        if (isTool) {
                            // 工具节点：仅支持重命名、复制、删除（无连接/编辑）
                            contextMenu.getItems().addAll(renameItem, copyItem, new SeparatorMenuItem(), deleteItem);
                        } else {
                            MenuItem connectItem = new MenuItem("连接");
                            connectItem.setOnAction(e -> handleConnect(targetConfig));
                            MenuItem editItem = new MenuItem("编辑");
                            editItem.setOnAction(e -> handleEdit(targetItem));
                            contextMenu.getItems().addAll(connectItem, new SeparatorMenuItem(), editItem, renameItem, copyItem, new SeparatorMenuItem(), deleteItem);
                        }
                        // 刷新统一放在菜单最底部（连接已打开时）
                        if (!targetItem.getChildren().isEmpty() && !isTool) {
                            MenuItem refreshItem = new MenuItem("刷新");
                            refreshItem.setOnAction(e -> refreshDbHost(targetItem, targetConfig));
                            contextMenu.getItems().addAll(new SeparatorMenuItem(), refreshItem);
                        }
                    } else {
                        MenuItem addFolder = new MenuItem("新建目录");
                        addFolder.setOnAction(e -> handleAddFolder(targetItem));
                        MenuItem addConnection = new MenuItem("新建连接");
                        addConnection.setOnAction(e -> handleAddConnection(targetItem));
                        MenuItem renameItem = new MenuItem("重命名");
                        renameItem.setOnAction(e -> {
                            editingItem = targetItem;
                            Platform.runLater(() -> {
                                treeView.requestFocus();
                                treeView.setEditable(true);
                                treeView.edit(targetItem);
                            });
                        });
                        MenuItem deleteItem = new MenuItem("删除");
                        deleteItem.setOnAction(e -> handleDelete(targetItem));
                        contextMenu.getItems().addAll(addFolder, addConnection, new SeparatorMenuItem(), renameItem, deleteItem);
                    }
                }
            }

            contextMenu.show(treeView, event.getScreenX(), event.getScreenY());
        });

        treeView.setOnMousePressed(event -> contextMenu.hide());

        treeView.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            if (event.getClickCount() == 1) {
                selectedItemBeforeClick = treeView.getSelectionModel().getSelectedItem();
            }
        });

        treeView.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;

            TreeItem<String> selectedItem = treeView.getSelectionModel().getSelectedItem();
            if (selectedItem == null) return;

            // 判断点击是否在选中项的文本区域上（排除箭头和图标区域）
            Node clickedNode = event.getPickResult().getIntersectedNode();
            TreeItem<String> clickedItem = null;
            boolean isTextClick = false;
            Node n = clickedNode;
            while (n != null && !(n instanceof TreeCell)) {
                if (n.getClass().getName().equals("com.sun.javafx.scene.control.LabeledText")) {
                    isTextClick = true;
                }
                n = n.getParent();
            }
            if (n instanceof TreeCell<?> cell) {
                clickedItem = (TreeItem<String>) cell.getTreeItem();
            }
            boolean clickOnSelectedItem = isTextClick && selectedItem == clickedItem;

            DatabaseNodeData dbData = dbNodeDataMap.get(selectedItem);
            ConnectionConfig config = itemConfigMap.get(selectedItem);
            boolean isTableOrView = dbData != null
                    && (dbData.getType() == DatabaseNodeData.NodeType.TABLE || dbData.getType() == DatabaseNodeData.NodeType.VIEW);
            boolean isLocalDirNode = dbData != null
                    && (dbData.getType() == DatabaseNodeData.NodeType.LOCAL_DIR_FOLDER || dbData.getType() == DatabaseNodeData.NodeType.LOCAL_DIR_FILE);
            boolean isFolder = dbData == null && config != null && config.getType() == null;
            boolean isHost = dbData == null && config != null && config.getType() != null;

            boolean wasAlreadySelected = clickOnSelectedItem && selectedItem == selectedItemBeforeClick;
            boolean canReedit = wasAlreadySelected || (clickOnSelectedItem && selectedItem == recentlyEditedItem);
            if (selectedItem != recentlyEditedItem) {
                recentlyEditedItem = null;
            }

            if (dbData != null) {
                if (event.getClickCount() == 2) {
                    if (singleClickTimer != null) {
                        singleClickTimer.stop();
                        singleClickTimer = null;
                    }
                    event.consume();
                    handleDbNodeDoubleClick(selectedItem, dbData);
                    selectedItemBeforeClick = null;
                    recentlyEditedItem = null;
                    if (editingItem != null) {
                        editingItem = null;
                        treeView.setEditable(false);
                    }
                    return;
                }

                if (event.getClickCount() == 1) {
                    // TABLES_FOLDER（"表"文件夹）单击立即打开对象视图（无双击重命名需求，无需定时器）
                    if (dbData.getType() == DatabaseNodeData.NodeType.TABLES_FOLDER) {
                        AbstractDbHandler h = createDbHandler(dbData.getConnectionConfig());
                        if (h != null) h.openObjectsView(selectedItem, dbData);
                        return;
                    }
                    if ((isTableOrView || isLocalDirNode) && canReedit && editingItem == null) {
                        TreeItem<String> itemToEdit = selectedItem;
                        if (singleClickTimer != null) {
                            singleClickTimer.stop();
                        }
                        singleClickTimer = new Timeline(new KeyFrame(
                                javafx.util.Duration.millis(300),
                                ae -> {
                                    if (editingItem == null && itemToEdit == treeView.getSelectionModel().getSelectedItem()) {
                                        editingItem = itemToEdit;
                                        recentlyEditedItem = null;
                                        treeView.requestFocus();
                                        treeView.setEditable(true);
                                        treeView.edit(itemToEdit);
                                    }
                                    singleClickTimer = null;
                                }
                        ));
                        singleClickTimer.play();
                        selectedItemBeforeClick = null;
                        return;
                    }
                }
            } else if (event.getClickCount() == 2) {
                if (singleClickTimer != null) {
                    singleClickTimer.stop();
                    singleClickTimer = null;
                }
                if (editingItem != null) {
                    editingItem = null;
                    treeView.setEditable(false);
                }
                if (isHost) {
                    triggerHostDoubleClick(selectedItem, config);
                }
                selectedItemBeforeClick = null;
                recentlyEditedItem = null;
            } else if (isHost && config.getType() == ConnectType.TOOL && event.getClickCount() == 1) {
                // 工具节点单击即打开工具标签页（与双击效果一致）
                if (singleClickTimer != null) {
                    singleClickTimer.stop();
                    singleClickTimer = null;
                }
                triggerHostDoubleClick(selectedItem, config);
                selectedItemBeforeClick = null;
                recentlyEditedItem = null;
            } else if ((isFolder || isHost) && event.getClickCount() == 1 && canReedit && editingItem == null) {
                TreeItem<String> itemToEdit = selectedItem;
                if (singleClickTimer != null) {
                    singleClickTimer.stop();
                }
                singleClickTimer = new Timeline(new KeyFrame(
                        javafx.util.Duration.millis(300),
                        ae -> {
                            if (editingItem == null && itemToEdit == treeView.getSelectionModel().getSelectedItem()) {
                                editingItem = itemToEdit;
                                recentlyEditedItem = null;
                                treeView.requestFocus();
                                treeView.setEditable(true);
                                treeView.edit(itemToEdit);
                            }
                            singleClickTimer = null;
                        }
                ));
                singleClickTimer.play();
                selectedItemBeforeClick = null;
            }
        });
    }

    private static final String DRAG_PREFIX = "ConnectItem|";
    private static final String LOCAL_DIR_DRAG = "LocalDirDrag|";
    /** 本地目录拖动中的源节点（拖动期间临时持有） */
    private TreeItem<String> draggedLocalDirItem;

    private void setupDragAndDrop() {
        treeView.setCellFactory(tv -> {
            TreeCell<String> cell = new TreeCell<>() {
                private final javafx.scene.shape.Path arrowPath;
                private final StackPane disclosurePane;
                private TreeItem<String> currentTreeItem;
                private ChangeListener<Boolean> expandedListener;
                private TextField editField;

                {
                    arrowPath = new javafx.scene.shape.Path(
                            new MoveTo(2, 0),
                            new LineTo(7, 5),
                            new LineTo(2, 10)
                    );
                    arrowPath.setStroke(Color.valueOf("#888888"));
                    arrowPath.setStrokeWidth(1.8);
                    arrowPath.setFill(null);
                    arrowPath.setStrokeLineCap(StrokeLineCap.ROUND);
                    arrowPath.setStrokeLineJoin(StrokeLineJoin.ROUND);

                    disclosurePane = new StackPane(arrowPath);
                    disclosurePane.setAlignment(Pos.CENTER);
                    disclosurePane.setPrefSize(16, 35);
                    disclosurePane.setMinSize(16, 35);

                    setDisclosureNode(disclosurePane);
                }

                @Override
                public void startEdit() {
                    TreeItem<String> treeItem = getTreeItem();
                    if (treeItem == null || editingItem != treeItem) {
                        return;
                    }
                    super.startEdit();

                    String currentName = treeItem.getValue();
                    editField = new TextField(currentName);
                    editField.setStyle("-fx-padding: 1 4; -fx-font-size: 13px; -fx-background-color: white; -fx-border-color: #07c160; -fx-border-radius: 3; -fx-background-radius: 3;");
                    editField.setPrefWidth(getWidth() - 40);

                    editField.setOnAction(e -> commitEdit(editField.getText()));

                    editField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                        if (!isNowFocused && editingItem == treeItem) {
                            commitEdit(editField.getText());
                        }
                    });

                    editField.setOnKeyReleased(e -> {
                        if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                            cancelEdit();
                        }
                    });

                    setText(null);
                    Node icon = treeItem.getGraphic();
                    HBox editBox = new HBox(icon, editField);
                    editBox.setAlignment(Pos.CENTER_LEFT);
                    editBox.setSpacing(4);
                    setGraphic(editBox);
                    editField.selectAll();
                    Platform.runLater(() -> editField.requestFocus());
                }

                @Override
                public void commitEdit(String newValue) {
                    TreeItem<String> treeItem = getTreeItem();
                    if (treeItem == null || editingItem != treeItem) return;

                    String oldName = treeItem.getValue();
                    String newName = newValue.trim();
                    recentlyEditedItem = treeItem;
                    editingItem = null;
                    editField = null;
                    treeView.edit(null);
                    treeView.setEditable(false);

                    setText(oldName);
                    setGraphic(treeItem.getGraphic());

                    if (newName.isEmpty() || newName.equals(oldName)) return;

                    DatabaseNodeData dbData = dbNodeDataMap.get(treeItem);
                    if (dbData != null) {
                        commitTableNameRename(treeItem, dbData, oldName, newName);
                    } else {
                        ConnectionConfig cfg = itemConfigMap.get(treeItem);
                        if (cfg != null) {
                            cfg.setName(newName);
                            saveConnectionsWithFeedback();
                            treeItem.setValue(newName);
                        }
                    }
                }

                @Override
                public void cancelEdit() {
                    TreeItem<String> treeItem = getTreeItem();
                    recentlyEditedItem = treeItem;
                    editingItem = null;
                    editField = null;
                    treeView.edit(null);
                    treeView.setEditable(false);

                    super.cancelEdit();
                    if (treeItem != null) {
                        setText(treeItem.getValue());
                        setGraphic(treeItem.getGraphic());
                    }
                }

                @Override
                protected void updateItem(String item, boolean empty) {
                    if (currentTreeItem != null && expandedListener != null) {
                        currentTreeItem.expandedProperty().removeListener(expandedListener);
                        expandedListener = null;
                    }
                    currentTreeItem = null;

                    super.updateItem(item, empty);
                    if (getTreeItem() == bottomSpacer) {
                        setText(null);
                        setGraphic(null);
                        if (!getStyleClass().contains("bottom-spacer")) {
                            getStyleClass().add("bottom-spacer");
                        }
                        return;
                    }
                    getStyleClass().remove("bottom-spacer");
                    if (empty || item == null) {
                        setText(null);
                        setGraphic(null);
                    } else {
                        TreeItem<String> treeItem = getTreeItem();
                        if (editingItem == treeItem && editField != null) {
                            setText(null);
                            setGraphic(editField);
                        } else {
                            setText(item);
                            if (treeItem != null) {
                                setGraphic(treeItem.getGraphic());
                            }
                        }
                        if (treeItem != null) {
                            arrowPath.setRotate(treeItem.isExpanded() ? 90 : 0);
                            currentTreeItem = treeItem;
                            expandedListener = (obs, wasExpanded, isExpanded) ->
                                    arrowPath.setRotate(isExpanded ? 90 : 0);
                            treeItem.expandedProperty().addListener(expandedListener);
                        }
                    }
                }
            };

            cell.setOnDragDetected(event -> {
                if (cell.isEmpty()) {
                    event.consume();
                    return;
                }
                TreeItem<String> item = cell.getTreeItem();
                if (item == null || item == root || item == bottomSpacer) {
                    event.consume();
                    return;
                }
                // 本地目录文件/文件夹拖动：用于跨目录移动
                DatabaseNodeData dd = dbNodeDataMap.get(item);
                if (dd != null && (dd.getType() == DatabaseNodeData.NodeType.LOCAL_DIR_FOLDER
                        || dd.getType() == DatabaseNodeData.NodeType.LOCAL_DIR_FILE)) {
                    Dragboard db = cell.startDragAndDrop(TransferMode.MOVE);
                    ClipboardContent content = new ClipboardContent();
                    content.putString(LOCAL_DIR_DRAG);
                    db.setContent(content);
                    draggedLocalDirItem = item;
                    event.consume();
                    return;
                }
                // 连接节点拖动（原有：用于重排连接顺序）
                Dragboard db = cell.startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                ConnectionConfig config = itemConfigMap.get(item);
                if (config == null) {
                    event.consume();
                    return;
                }
                content.putString(DRAG_PREFIX + config.getId());
                db.setContent(content);
                event.consume();
            });

            cell.setOnDragOver(event -> {
                Dragboard db = event.getDragboard();
                if (db.hasString() && db.getString().startsWith(DRAG_PREFIX)) {
                    TreeItem<String> targetItem = cell.getTreeItem();
                    if (targetItem == null || targetItem == root) {
                        event.acceptTransferModes(TransferMode.MOVE);
                    } else {
                        ConnectionConfig targetConfig = itemConfigMap.get(targetItem);
                        if (targetConfig != null && targetConfig.getType() == null) {
                            event.acceptTransferModes(TransferMode.MOVE);
                        }
                    }
                }
                event.consume();
            });

            cell.setOnDragEntered(event -> {
                Dragboard db = event.getDragboard();
                if (db.hasString()) {
                    String s = db.getString();
                    if (s.equals(LOCAL_DIR_DRAG)) {
                        TreeItem<String> targetItem = cell.getTreeItem();
                        if (isValidLocalDirDropTarget(draggedLocalDirItem, targetItem)) {
                            cell.setStyle("-fx-background-color: #e0e0e0;");
                        }
                    } else if (s.startsWith(DRAG_PREFIX)) {
                        TreeItem<String> targetItem = cell.getTreeItem();
                        if (targetItem == null || targetItem == root) {
                            cell.setStyle("-fx-background-color: #e0e0e0;");
                        } else {
                            ConnectionConfig targetConfig = itemConfigMap.get(targetItem);
                            if (targetConfig != null && targetConfig.getType() == null) {
                                cell.setStyle("-fx-background-color: #e0e0e0;");
                            }
                        }
                    }
                }
                event.consume();
            });

            cell.setOnDragExited(event -> {
                cell.setStyle("");
                event.consume();
            });

            cell.setOnDragDropped(event -> {
                Dragboard db = event.getDragboard();
                boolean success = false;
                if (db.hasString()) {
                    String s = db.getString();
                    if (s.equals(LOCAL_DIR_DRAG)) {
                        // 目录连接文件/文件夹移动到目标目录（本地目录或 S3 后端）
                        TreeItem<String> targetItem = cell.getTreeItem();
                        if (draggedLocalDirItem != null && isValidLocalDirDropTarget(draggedLocalDirItem, targetItem)) {
                            DatabaseNodeData dd = dbNodeDataMap.get(draggedLocalDirItem);
                            if (dd != null) {
                                ConnectHandler h = createConnectHandler(dd.getConnectionConfig());
                                if (h instanceof LocalDirectoryConnectHandler ld) {
                                    if (dd.getConnectionConfig().isS3Directory()) {
                                        ld.moveS3NodeFromTree(this, draggedLocalDirItem, targetItem);
                                        success = true;
                                    } else {
                                        Path sourcePath = Path.of(dd.getDatabaseName());
                                        Path targetDir = getLocalDirTargetPath(targetItem);
                                        if (targetDir != null) {
                                            Path destPath = targetDir.resolve(sourcePath.getFileName());
                                            ld.moveNode(this, draggedLocalDirItem, targetItem, sourcePath, destPath);
                                            success = true;
                                        }
                                    }
                                }
                            }
                        }
                        draggedLocalDirItem = null;
                    } else if (s.startsWith(DRAG_PREFIX)) {
                        String dragId = s.substring(DRAG_PREFIX.length());
                        TreeItem<String> targetItem = cell.getTreeItem();

                        TreeItem<String> newParent;
                        if (targetItem == null || targetItem == root) {
                            newParent = root;
                        } else {
                            ConnectionConfig targetConfig = itemConfigMap.get(targetItem);
                            if (targetConfig != null && targetConfig.getType() == null) {
                                newParent = targetItem;
                            } else {
                                newParent = root;
                            }
                        }

                        TreeItem<String> draggedItem = findItemById(root, dragId);
                        if (draggedItem != null && draggedItem != newParent && !isDescendant(draggedItem, newParent)) {
                            moveItem(draggedItem, newParent);
                            success = true;
                        }
                    }
                }
                event.setDropCompleted(success);
                event.consume();
            });

            return cell;
        });

        treeView.setOnDragOver(event -> {
            Dragboard db = event.getDragboard();
            if (db.hasString() && db.getString().startsWith(DRAG_PREFIX)) {
                event.acceptTransferModes(TransferMode.MOVE);
            }
            event.consume();
        });

        treeView.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasString() && db.getString().startsWith(DRAG_PREFIX)) {
                String dragId = db.getString().substring(DRAG_PREFIX.length());
                TreeItem<String> draggedItem = findItemById(root, dragId);
                if (draggedItem != null && draggedItem.getParent() != root) {
                    moveItem(draggedItem, root);
                    success = true;
                }
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    /** 本地目录拖放目标是否有效：须为 LOCAL_DIR_FOLDER 或本地目录连接根，且与源同一连接、非自身/后代 */
    private boolean isValidLocalDirDropTarget(TreeItem<String> draggedItem, TreeItem<String> targetItem) {
        if (targetItem == null || targetItem == root) return false;
        if (draggedItem == null || draggedItem == targetItem) return false;
        if (isDescendant(draggedItem, targetItem)) return false; // 不能拖入自身后代
        ConnectionConfig targetCfg = getConfigForItem(targetItem);
        ConnectionConfig draggedCfg = getConfigForItem(draggedItem);
        if (targetCfg == null || draggedCfg == null) return false;
        if (!java.util.Objects.equals(targetCfg.getId(), draggedCfg.getId())) return false; // 仅限同一连接
        DatabaseNodeData td = dbNodeDataMap.get(targetItem);
        if (td != null && td.getType() == DatabaseNodeData.NodeType.LOCAL_DIR_FOLDER) return true;
        return targetCfg.getType() == ConnectType.LOCAL_DIRECTORY; // 连接根
    }

    /** 取本地目录拖放目标路径（目标为目录节点或连接根） */
    private Path getLocalDirTargetPath(TreeItem<String> targetItem) {
        DatabaseNodeData td = dbNodeDataMap.get(targetItem);
        if (td != null && td.getType() == DatabaseNodeData.NodeType.LOCAL_DIR_FOLDER) {
            return Path.of(td.getDatabaseName());
        }
        ConnectionConfig cfg = itemConfigMap.get(targetItem);
        if (cfg != null && cfg.getType() == ConnectType.LOCAL_DIRECTORY) {
            return Path.of(cfg.getLocalDirectoryPath());
        }
        return null;
    }

    /** 取节点关联的连接配置（连接根或 db/local-dir 子节点） */
    private ConnectionConfig getConfigForItem(TreeItem<String> item) {
        if (item == null) return null;
        ConnectionConfig cfg = itemConfigMap.get(item);
        if (cfg != null) return cfg;
        DatabaseNodeData d = dbNodeDataMap.get(item);
        return d != null ? d.getConnectionConfig() : null;
    }

    private boolean isDescendant(TreeItem<String> ancestor, TreeItem<String> possibleDescendant) {
        if (ancestor == possibleDescendant) return true;
        for (TreeItem<String> child : ancestor.getChildren()) {
            if (isDescendant(child, possibleDescendant)) {
                return true;
            }
        }
        return false;
    }

    private void moveItem(TreeItem<String> item, TreeItem<String> newParent) {
        ConnectionConfig config = itemConfigMap.get(item);
        if (config == null) return;

        item.getParent().getChildren().remove(item);

        if (newParent == root) {
            config.setParentId(null);
        } else {
            ConnectionConfig parentConfig = itemConfigMap.get(newParent);
            if (parentConfig != null) {
                config.setParentId(parentConfig.getId());
            }
        }

        addChildToParent(newParent, item);
        newParent.setExpanded(true);

        saveConnectionsWithFeedback();
    }

    /** 向 parent 添加子节点；若 parent 为 root 则插入到 bottomSpacer 之前，确保空白占位始终在末尾 */
    private void addChildToParent(TreeItem<String> parent, TreeItem<String> child) {
        if (parent == root) {
            int spacerIdx = root.getChildren().indexOf(bottomSpacer);
            if (spacerIdx >= 0) {
                root.getChildren().add(spacerIdx, child);
                return;
            }
        }
        parent.getChildren().add(child);
    }

    private void handleDbNodeDoubleClick(TreeItem<String> item, DatabaseNodeData data) {
        switch (data.getType()) {
            case DATABASE -> {
                AbstractDbHandler h = createDbHandler(data.getConnectionConfig());
                if (h != null) h.handleDatabaseDoubleClick(item, data);
            }
            case REDIS_DB -> {
                ConnectHandler h = createConnectHandler(data.getConnectionConfig());
                if (h instanceof RedisConnectHandler r) {
                    r.handleRedisDbDoubleClick(this, item, data);
                }
            }
            case SCHEMA -> {
                AbstractDbHandler h = createDbHandler(data.getConnectionConfig());
                if (h != null) h.handleSchemaDoubleClick(item, data);
            }
            case TABLES_FOLDER -> {
                AbstractDbHandler dbh = createDbHandler(data.getConnectionConfig());
                if (dbh != null) dbh.handleTablesFolderDoubleClick(item, data);
            }
            case VIEWS_FOLDER -> {
                AbstractDbHandler dbh = createDbHandler(data.getConnectionConfig());
                if (dbh != null) dbh.handleViewsFolderDoubleClick(item, data);
            }
            case TABLE, VIEW -> {
                AbstractDbHandler h = createDbHandler(data.getConnectionConfig());
                if (h != null) h.handleTableDataDoubleClick(item, data);
            }
            case QUERY -> {
                AbstractDbHandler h = createDbHandler(data.getConnectionConfig());
                if (h != null) h.handleQueryDoubleClick(item, data);
            }
            case BACKUP -> {
                AbstractDbHandler h = createDbHandler(data.getConnectionConfig());
                if (h != null) h.handleRestoreBackup(item, data);
            }
            case QUERY_FOLDER -> item.setExpanded(!item.isExpanded());
            case BACKUP_FOLDER -> {
                AbstractDbHandler handler = createDbHandler(data.getConnectionConfig());
                if (handler != null) {
                    handler.loadBackupsForFolder(item, data.getConnectionConfig(), data.getDatabaseName(), "");
                }
                item.setExpanded(!item.isExpanded());
            }
            case QUERY_DIR -> item.setExpanded(!item.isExpanded());
            case BACKUP_DIR -> {
                AbstractDbHandler handler = createDbHandler(data.getConnectionConfig());
                if (handler != null) {
                    handler.loadBackupsForFolder(item, data.getConnectionConfig(), data.getDatabaseName(), data.getPath());
                }
                item.setExpanded(!item.isExpanded());
            }
            case ROCKETMQ_TOPICS_FOLDER -> {
                ConnectHandler rqH = createConnectHandler(data.getConnectionConfig());
                if (rqH instanceof RocketmqConnectHandler rq) {
                    rq.handleTopicsFolderDoubleClick(this, item, data);
                }
            }
            case ROCKETMQ_CONSUMERS_FOLDER -> {
                ConnectHandler handler = createConnectHandler(data.getConnectionConfig());
                if (handler instanceof RocketmqConnectHandler rqHandler) {
                    rqHandler.handleConsumersFolderDoubleClick(this, item, data);
                }
            }
            case ROCKETMQ_CLUSTER_FOLDER -> {
                ConnectHandler rqHandler = createConnectHandler(data.getConnectionConfig());
                if (rqHandler instanceof RocketmqConnectHandler rq) {
                    rq.handleClusterFolderDoubleClick(this, item, data);
                }
            }
            case ROCKETMQ_TOPIC -> {
                ConnectHandler rqH = createConnectHandler(data.getConnectionConfig());
                if (rqH instanceof RocketmqConnectHandler rq) {
                    rq.handleTopicDoubleClick(this, item, data);
                }
            }
            case ROCKETMQ_CONSUMER -> {
                ConnectHandler rqH = createConnectHandler(data.getConnectionConfig());
                if (rqH instanceof RocketmqConnectHandler rq) {
                    rq.handleConsumerDoubleClick(this, item, data);
                }
            }
            case ROCKETMQ_BROKER -> {
                ConnectHandler rqH = createConnectHandler(data.getConnectionConfig());
                if (rqH instanceof RocketmqConnectHandler rq) {
                    rq.handleBrokerDoubleClick(this, item, data);
                }
            }
            case KAFKA_TOPICS_FOLDER -> {
                ConnectHandler kfH = createConnectHandler(data.getConnectionConfig());
                if (kfH instanceof KafkaConnectHandler kf) {
                    kf.handleTopicsFolderDoubleClick(this, item, data);
                }
            }
            case KAFKA_CONSUMERS_FOLDER -> {
                ConnectHandler kfH = createConnectHandler(data.getConnectionConfig());
                if (kfH instanceof KafkaConnectHandler kf) {
                    kf.handleConsumersFolderDoubleClick(this, item, data);
                }
            }
            case KAFKA_CLUSTER_FOLDER -> {
                ConnectHandler kfH = createConnectHandler(data.getConnectionConfig());
                if (kfH instanceof KafkaConnectHandler kf) {
                    kf.handleClusterFolderDoubleClick(this, item, data);
                }
            }
            case KAFKA_TOPIC -> {
                ConnectHandler kfH = createConnectHandler(data.getConnectionConfig());
                if (kfH instanceof KafkaConnectHandler kf) {
                    kf.handleTopicDoubleClick(this, item, data);
                }
            }
            case KAFKA_CONSUMER -> {
                ConnectHandler kfH = createConnectHandler(data.getConnectionConfig());
                if (kfH instanceof KafkaConnectHandler kf) {
                    kf.handleConsumerDoubleClick(this, item, data);
                }
            }
            case KAFKA_BROKER -> {
                ConnectHandler kfH = createConnectHandler(data.getConnectionConfig());
                if (kfH instanceof KafkaConnectHandler kf) {
                    kf.handleBrokerDoubleClick(this, item, data);
                }
            }
            case ALIYUN_PRODUCT_FOLDER -> {
                ConnectHandler alH = createConnectHandler(data.getConnectionConfig());
                if (alH instanceof AliyunConnectHandler al) {
                    al.handleProductFolderDoubleClick(this, item, data);
                }
            }
            case ALIYUN_ECS_INSTANCE -> { /* TODO: show ECS instance detail */ }
            case ALIYUN_DOMAIN -> {
                ConnectHandler alHandler = createConnectHandler(data.getConnectionConfig());
                if (alHandler instanceof AliyunConnectHandler al) {
                    al.handleAliyunDomainDoubleClick(this, item, data);
                }
            }
            case LOCAL_DIR_FOLDER -> {
                ConnectHandler ldH = createConnectHandler(data.getConnectionConfig());
                if (ldH instanceof LocalDirectoryConnectHandler ld) {
                    ld.handleFolderDoubleClick(this, item, data);
                }
            }
            case LOCAL_DIR_FILE -> {
                ConnectHandler ldHandler = createConnectHandler(data.getConnectionConfig());
                if (ldHandler instanceof LocalDirectoryConnectHandler ld) {
                    ld.handleFileDoubleClick(this, item, data);
                }
            }
        }
    }

    /** 加载连接图标为 16x16 ImageView，供右键菜单项使用 */
    public ImageView createMenuIcon(String fileName) {
        try {
            ImageView iv = new ImageView(new Image(getClass().getResourceAsStream("/images/connect/" + fileName)));
            iv.setFitWidth(16);
            iv.setFitHeight(16);
            return iv;
        } catch (Exception e) {
            return null;
        }
    }

    /** 进入节点重命名编辑状态（供连接处理器构建右键菜单时调用） */
    public void startRenameEdit(TreeItem<String> item) {
        editingItem = item;
        Platform.runLater(() -> {
            treeView.requestFocus();
            treeView.setEditable(true);
            treeView.edit(item);
        });
    }

    private void commitTableNameRename(TreeItem<String> item, DatabaseNodeData dbData, String oldName, String newName) {
        ConnectionConfig config = dbData.getConnectionConfig();
        String dbName = dbData.getDatabaseName();
        // 本地目录文件/文件夹重命名：委托给 LocalDirectoryConnectHandler 改名磁盘文件并更新节点
        if (dbData.getType() == DatabaseNodeData.NodeType.LOCAL_DIR_FOLDER
                || dbData.getType() == DatabaseNodeData.NodeType.LOCAL_DIR_FILE) {
            ConnectHandler h = createConnectHandler(config);
            if (h instanceof LocalDirectoryConnectHandler ld) {
                ld.renameNode(this, item, dbData, oldName, newName);
            }
            return;
        }
        new Thread(() -> {
            try {
                if (dbData.getType() == DatabaseNodeData.NodeType.TABLE) {
                    DatabaseService.renameTable(config, dbName, oldName, newName);
                } else {
                    DatabaseService.renameView(config, dbName, oldName, newName);
                }
                Platform.runLater(() -> {
                    item.setValue(newName);
                    dbNodeDataMap.put(item, new DatabaseNodeData(dbData.getType(), newName, config, dbName));
                    item.getChildren().clear();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    item.setValue(oldName);
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("重命名失败");
                    alert.setHeaderText(null);
                    alert.setContentText("重命名失败: " + e.getMessage());
                    DialogPositionUtil.centerOnOwner(alert, getStage());
                    alert.showAndWait();
                });
            }
        }, "DB-RenameTable").start();
    }

    public void handleNewBackup(TreeItem<String> folderItem, DatabaseNodeData data) {
        BackupDialog dialog = new BackupDialog(getStage(),
                data.getConnectionConfig(), data.getDatabaseName(), data.getPath());
        dialog.showAndWait();

        AbstractDbHandler handler = createDbHandler(data.getConnectionConfig());
        if (handler != null) {
            handler.loadBackupsForFolder(folderItem, data.getConnectionConfig(), data.getDatabaseName(), data.getPath());
        }
    }

    /** 供 handler 调用：更新主机节点图标（根据连接状态分发到具体 handler） */
    public void updateHostIcon(TreeItem<String> hostItem, ConnectionConfig config, boolean connected) {
        connectionStateMap.put(hostItem, connected);
        AbstractDbHandler handler = createDbHandler(config);
        if (handler != null) {
            handler.updateHostIcon(hostItem, config, connected);
        } else {
            updateHostIconGeneric(hostItem, config, connected);
        }
    }

    /** 供非数据库类型(SSH/RDP 等)及 PG/Oracle handler 调用：通用主机图标更新 */
    public void updateHostIconGeneric(TreeItem<String> hostItem, ConnectionConfig config, boolean connected) {
        ImageView imageView = new ImageView();
        imageView.setFitWidth(16);
        imageView.setFitHeight(16);
        try {
            String iconPath = config.getType().getIconPath();
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

    /**
     * 关闭主机连接：释放底层连接资源（JDBC/Jedis/MQAdmin），清空树子节点并重置图标。
     * 适用于已展开（已连接）的数据库/Redis/RocketMQ 主机节点。
     */
    private void closeHostConnection(TreeItem<String> hostItem, ConnectionConfig config) {
        // 后台关闭底层连接（避免阻塞UI线程）
        new Thread(() -> {
            try {
                AbstractDbHandler handler = createDbHandler(config);
                if (handler != null) {
                    handler.closeConnection(config);
                } else if (config.getType() == ConnectType.REDIS) {
                    RedisService.closeJedisCluster(config);
                    RedisService.closeSshTunnel(config.getId());
                } else if (config.getType() == ConnectType.ROCKETMQ) {
                    RocketmqService.closeAdmin(config);
                } else if (config.getType() == ConnectType.KAFKA) {
                    KafkaService.closeAdmin(config);
                }
            } catch (Exception ignored) {
            }
        }, "CloseHostConnection").start();

        // 清空子节点及其 dbNodeDataMap 映射
        for (TreeItem<String> child : hostItem.getChildren()) {
            removeDbNodeDataRecursive(child);
        }
        hostItem.getChildren().clear();
        hostItem.setExpanded(false);

        // 重置图标为未连接状态
        connectionStateMap.put(hostItem, false);
        updateHostIcon(hostItem, config, false);
        treeView.refresh();
    }

    /** 供 handler 调用：递归移除节点映射 */
    public void removeDbNodeDataRecursive(TreeItem<String> item) {
        dbNodeDataMap.remove(item);
        for (TreeItem<String> child : item.getChildren()) {
            removeDbNodeDataRecursive(child);
        }
    }

    /** 供 handler 调用：注册节点数据映射 */
    public void putDbNodeData(TreeItem<String> item, DatabaseNodeData data) {
        dbNodeDataMap.put(item, data);
    }

    /** 供 handler 调用：根据 id 查找连接配置 */
    public ConnectionConfig findConnectionById(String id) {
        return connections.stream().filter(c -> c.getId().equals(id)).findFirst().orElse(null);
    }

    private void handleConnect(ConnectionConfig config) {
        if (contentArea == null || terminalTabPane == null) return;

        if (!ensureTabPaneInstalled()) return;

        ConnectHandler handler = createConnectHandler(config);
        if (handler != null) {
            handler.handleConnect(this, config);
        }
    }

    /**
     * 根据连接类型创建对应的连接处理器
     */
    private ConnectHandler createConnectHandler(ConnectionConfig config) {
        ConnectType type = config.getType();
        // 数据库类型（MySQL/PostgreSQL/Oracle）复用 AbstractDbHandler
        AbstractDbHandler dbHandler = createDbHandler(config);
        if (dbHandler != null) {
            return dbHandler;
        }
        // 其他类型
        if (type == ConnectType.REDIS) return new RedisConnectHandler();
        if (type == ConnectType.ROCKETMQ) return new RocketmqConnectHandler();
        if (type == ConnectType.KAFKA) return new KafkaConnectHandler();
        if (type == ConnectType.ALIYUN) return new AliyunConnectHandler();
        if (type == ConnectType.LOCAL_TERMINAL) return new LocalTerminalConnectHandler();
        if (type == ConnectType.LOCAL_DIRECTORY) return new LocalDirectoryConnectHandler();
        if (type == ConnectType.S3 || type == ConnectType.ALIYUN_OSS) return new S3ConnectHandler();
        if (type == ConnectType.RDP) return new RdpConnectHandler();
        if (type == ConnectType.SSH) return new SshTerminalConnectHandler();
        if (type == ConnectType.SFTP) return new SFTPConnectHandler();
        if (type == ConnectType.FTP) return new FTPConnectHandler();
        return null;
    }

    /** 供 handler 调用：获取终端 Tab 面板 */
    public TabPane getTerminalTabPane() {
        return terminalTabPane;
    }

    /** 供 RdpConnectHandler 调用 */
    public void showTerminalView() {
        // 已直接使用terminalTabPane，无需隐藏/显示其他元素
        if (terminalTabPane != null) {
            terminalTabPane.setVisible(true);
            terminalTabPane.setManaged(true);
        }
    }

    /** 供 handler 调用：显示欢迎视图 */
    public void showWelcomeView() {
        // 无标签时保持TabPane可见，但可以清空标签或显示提示
        if (terminalTabPane != null) {
            terminalTabPane.setVisible(true);
            terminalTabPane.setManaged(true);
        }
    }

    /** 供 handler 调用：保存连接配置 */
    public void saveConnections() {
        saveConnectionsWithFeedback();
    }

    /**
     * 内部统一使用：保存连接配置并在失败时弹窗提示用户。
     * 返回 true 表示保存成功，false 表示保存失败。
     */
    private boolean saveConnectionsWithFeedback() {
        try {
            ConfigManager.saveConnections(connections);
            return true;
        } catch (ConfigManager.SaveException e) {
            System.err.println("[ConnectModule] 保存连接配置失败: " + e.getMessage());
            e.printStackTrace();
            try {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("保存失败");
                alert.setHeaderText("连接配置未保存");
                alert.setContentText("原因: " + e.getMessage() + "\n\n配置文件路径: " + ConfigManager.getConfigFilePath());
                DialogPositionUtil.centerOnOwner(alert, getStage());
                alert.showAndWait();
            } catch (Exception uiEx) {
                uiEx.printStackTrace();
            }
            return false;
        }
    }

    /** 供 handler 调用：触发连接（用于"复制会话"菜单） */
    public void triggerConnect(ConnectionConfig config) {
        handleConnect(config);
    }

    /** 双击主机节点：通过对应 handler 加载主机资源列表 */
    public void triggerHostDoubleClick(TreeItem<String> hostItem, ConnectionConfig config) {
        if (config.getType() == ConnectType.TOOL) {
            handleToolDoubleClick(hostItem, config);
            return;
        }
        if (config.getType() == ConnectType.HTTP_SERVER
                || config.getType() == ConnectType.FTP_SERVER
                || config.getType() == ConnectType.SMB_SERVER) {
            handleServerDoubleClick(hostItem, config);
            return;
        }
        ConnectHandler handler = createConnectHandler(config);
        if (handler != null) {
            handler.handleHostDoubleClick(this, hostItem, config);
        }
    }

    /** 服务器节点双击：打开服务器管理面板 */
    private void handleServerDoubleClick(TreeItem<String> item, ConnectionConfig config) {
        if (!ensureTabPaneInstalled()) return;
        String tabTitle = config.getName() != null && !config.getName().isEmpty()
                ? config.getName()
                : (config.getType() != null ? config.getType().getDisplayName() : "服务器");

        // 避免重复打开同一服务器标签
        for (Tab t : terminalTabPane.getTabs()) {
            if (tabTitle.equals(t.getText()) && t.getUserData() == config.getId()) {
                terminalTabPane.getSelectionModel().select(t);
                return;
            }
        }

        // 转换为 com.tangluobo.tomato.module.tools.server.ServerType
        com.tangluobo.tomato.module.tools.server.ServerType serverType =
                toServerType(config.getType());
        ServerManagerPane serverPane;
        if (serverType != null) {
            serverPane = new ServerManagerPane(serverType);
            // 加载已保存的完整服务器配置
            if (config.getServerConfig() != null) {
                serverPane.loadFromServerConfig(config.getServerConfig());
            } else if (config.getPort() > 0) {
                serverPane.setConfigPort(config.getPort());
            }
        } else {
            serverPane = new ServerManagerPane();
        }

        // 配置变更时自动保存到连接树（在 loadFromServerConfig 之后设置，避免加载时触发）
        serverPane.setOnConfigChanged(() -> {
            ServerConfig sc = serverPane.getServerConfig();
            config.setServerConfig(sc);
            config.setPort(sc.getPort());
            connections.removeIf(c -> c.getId().equals(config.getId()));
            connections.add(config);
            saveConnectionsWithFeedback();
        });

        Tab serverTab = new Tab(tabTitle);
        serverTab.setUserData(config.getId());
        serverTab.setContent(serverPane);
        // 图标
        if (config.getType() != null) {
            try {
                ImageView icon = new ImageView(new Image(getClass().getResourceAsStream(config.getType().getIconPath())));
                icon.setFitWidth(16);
                icon.setFitHeight(16);
                serverTab.setGraphic(createFixedSizeGraphic(icon));
            } catch (Exception ignored) {}
        }

        // Tab 关闭时也保存一次（兜底）
        serverTab.setOnCloseRequest(e -> {
            ServerConfig sc = serverPane.getServerConfig();
            config.setServerConfig(sc);
            config.setPort(sc.getPort());
            connections.removeIf(c -> c.getId().equals(config.getId()));
            connections.add(config);
            saveConnectionsWithFeedback();
        });

        terminalTabPane.getTabs().add(serverTab);
        terminalTabPane.getSelectionModel().select(serverTab);
    }

    private static com.tangluobo.tomato.module.tools.server.ServerType toServerType(ConnectType t) {
        if (t == null) return null;
        return switch (t) {
            case HTTP_SERVER -> com.tangluobo.tomato.module.tools.server.ServerType.HTTP;
            case FTP_SERVER -> com.tangluobo.tomato.module.tools.server.ServerType.FTP;
            case SMB_SERVER -> com.tangluobo.tomato.module.tools.server.ServerType.SMB;
            default -> null;
        };
    }

    /** 工具节点双击：打开工具视图 */
    private void handleToolDoubleClick(TreeItem<String> item, ConnectionConfig config) {
        if (!ensureTabPaneInstalled()) return;
        ToolType toolType = ToolType.fromCode(config.getToolType());
        String toolName = toolType != null ? toolType.getDisplayName() : "工具";

        // 避免重复打开同一工具标签
        for (Tab t : terminalTabPane.getTabs()) {
            if (toolName.equals(t.getText()) && t.getUserData() == config.getId()) {
                terminalTabPane.getSelectionModel().select(t);
                return;
            }
        }

        Tab toolTab = new Tab(toolName);
        toolTab.setUserData(config.getId());
        if (toolType == ToolType.COLOR_TRANSPOSE_GAME) {
            // 游戏：在右侧内容区打开游戏界面（配置维度并开始）
            toolTab.setContent(new ColorTransposeGamePane());
        } else {
            ToolPane toolPane = new ToolPane(toolType);
            toolTab.setContent(toolPane);
        }
        terminalTabPane.getTabs().add(toolTab);
        terminalTabPane.getSelectionModel().select(toolTab);
    }

    /** 在右侧内容区以可关闭标签页形式打开"设置"界面 */
    public void openSettingsTab(java.util.function.Consumer<Boolean> onSidebarToggle) {
        if (!ensureTabPaneInstalled()) return;

        // 避免重复打开
        for (Tab t : terminalTabPane.getTabs()) {
            if ("__settings__".equals(t.getUserData())) {
                terminalTabPane.getSelectionModel().select(t);
                return;
            }
        }

        Tab settingsTab = new Tab("设置");
        settingsTab.setUserData("__settings__");

        // 图标
        try {
            ImageView icon = new ImageView(new Image(getClass().getResourceAsStream("/images/settings.png")));
            icon.setFitWidth(16);
            icon.setFitHeight(16);
            settingsTab.setGraphic(createFixedSizeGraphic(icon));
        } catch (Exception ignored) {}

        // 设置内容：内嵌 TabPane，包含"系统设置"和"SSH终端"两个子标签页
        TabPane settingsTabPane = new TabPane();
        settingsTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        settingsTabPane.setStyle("-fx-padding: 0; -fx-background-color: #ffffff;");

        // ===== 系统设置 Tab =====
        VBox systemRoot = new VBox();
        systemRoot.setStyle("-fx-background-color: #ffffff; -fx-padding: 20;");
        systemRoot.setSpacing(15);

        Label systemTitle = new Label("系统设置");
        systemTitle.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        VBox systemSettings = new VBox(15);
        systemSettings.setPadding(new Insets(20, 0, 0, 0));

        CheckBox sidebarVisible = new CheckBox("开启侧边栏");
        sidebarVisible.setStyle("-fx-font-size: 14px;");
        sidebarVisible.setSelected(GlobalConfig.getInstance().isSidebarVisible());
        sidebarVisible.selectedProperty().addListener((obs, wasSelected, isNowSelected) -> {
            if (onSidebarToggle != null) {
                onSidebarToggle.accept(isNowSelected);
            }
        });

        CheckBox autoStart = new CheckBox("开机自动启动");
        autoStart.setStyle("-fx-font-size: 14px;");

        CheckBox autoUpdate = new CheckBox("自动检查更新");
        autoUpdate.setStyle("-fx-font-size: 14px;");

        TextField themeField = new TextField();
        themeField.setPromptText("主题颜色");
        themeField.setStyle("-fx-background-color: #f0f0f0; -fx-border-radius: 6px; -fx-background-radius: 6px; -fx-padding: 8 12;");

        Button saveBtn = new Button("保存设置");
        saveBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 100px;");

        systemSettings.getChildren().addAll(sidebarVisible, autoStart, autoUpdate, themeField, saveBtn);
        systemRoot.getChildren().addAll(systemTitle, systemSettings);

        Tab systemTab = new Tab("系统设置");
        systemTab.setContent(systemRoot);
        settingsTabPane.getTabs().add(systemTab);

        // ===== SSH终端 Tab =====
        VBox sshRoot = new VBox();
        sshRoot.setStyle("-fx-background-color: #ffffff; -fx-padding: 20;");
        sshRoot.setSpacing(15);

        Label sshTitle = new Label("SSH终端");
        sshTitle.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        GridPane sshGrid = new GridPane();
        sshGrid.setHgap(10);
        sshGrid.setVgap(12);
        sshGrid.setPadding(new Insets(20, 0, 0, 0));

        Label fontNameLabel = new Label("字体名称");
        fontNameLabel.setStyle("-fx-font-size: 14px;");
        GridPane.setConstraints(fontNameLabel, 0, 0);

        ComboBox<String> fontNameCombo = new ComboBox<>();
        fontNameCombo.setEditable(true);
        fontNameCombo.setPrefWidth(220);
        // 填充系统等宽字体 + 通用字体
        fontNameCombo.getItems().addAll(
                javafx.scene.text.Font.getFamilies().stream()
                        .filter(f -> {
                            String lf = f.toLowerCase();
                            return lf.contains("mono") || lf.contains("consol")
                                    || lf.contains("courier") || lf.contains("menlo")
                                    || lf.contains("dejavu") || lf.contains("liberation")
                                    || lf.contains("sarasa") || lf.contains("cascadia")
                                    || lf.contains("jetbrains");
                        })
                        .toList()
        );
        fontNameCombo.setValue(GlobalConfig.getInstance().getSshTerminalFontName());
        GridPane.setConstraints(fontNameCombo, 1, 0);

        Label fontSizeLabel = new Label("字体大小");
        fontSizeLabel.setStyle("-fx-font-size: 14px;");
        GridPane.setConstraints(fontSizeLabel, 0, 1);

        Spinner<Integer> fontSizeSpinner = new Spinner<>(6, 48, (int) GlobalConfig.getInstance().getSshTerminalFontSize());
        fontSizeSpinner.setEditable(true);
        fontSizeSpinner.setPrefWidth(100);
        GridPane.setConstraints(fontSizeSpinner, 1, 1);

        sshGrid.getChildren().addAll(fontNameLabel, fontNameCombo, fontSizeLabel, fontSizeSpinner);

        // 预览
        Label previewLabel = new Label("预览：");
        previewLabel.setStyle("-fx-font-size: 14px; -fx-padding: 10 0 0 0;");
        javafx.scene.text.Text previewText = new javafx.scene.text.Text("abcdefghijklmnopqrstuvwxyz 0123456789\nABCDEFGHIJKLMNOPQRSTUVWXYZ ~!@#$%^&*()");
        previewText.setFont(javafx.scene.text.Font.font(
                GlobalConfig.getInstance().getSshTerminalFontName(),
                GlobalConfig.getInstance().getSshTerminalFontSize()));
        Runnable updatePreview = () -> {
            String f = fontNameCombo.getValue();
            Integer s = fontSizeSpinner.getValue();
            if (f == null || f.isBlank()) f = "monospace";
            if (s == null || s <= 0) s = 13;
            previewText.setFont(javafx.scene.text.Font.font(f, s));
        };
        fontNameCombo.valueProperty().addListener((obs, o, n) -> updatePreview.run());
        fontSizeSpinner.valueProperty().addListener((obs, o, n) -> updatePreview.run());

        Button applyFontBtn = new Button("应用并保存");
        applyFontBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 100px;");
        applyFontBtn.setOnAction(e -> {
            String f = fontNameCombo.getValue();
            Integer s = fontSizeSpinner.getValue();
            if (f == null || f.isBlank()) f = "monospace";
            if (s == null || s <= 0) s = 13;

            GlobalConfig cfg = GlobalConfig.getInstance();
            cfg.setSshTerminalFontName(f);
            cfg.setSshTerminalFontSize(s);
            cfg.save();

            // 应用到所有已打开的终端
            applyTerminalFontToAllTabs(f, s);
        });

        sshRoot.getChildren().addAll(sshTitle, sshGrid, previewLabel, previewText, applyFontBtn);

        Tab sshTab = new Tab("SSH终端");
        sshTab.setContent(sshRoot);
        settingsTabPane.getTabs().add(sshTab);

        settingsTab.setContent(settingsTabPane);
        terminalTabPane.getTabs().add(settingsTab);
        terminalTabPane.getSelectionModel().select(settingsTab);
    }

    /** 将字体应用到 terminalTabPane 中所有已打开的 SSH/本地终端 */
    private void applyTerminalFontToAllTabs(String family, double size) {
        if (terminalTabPane == null) return;
        for (Tab tab : terminalTabPane.getTabs()) {
            Object content = tab.getContent();
            if (content instanceof com.tangluobo.tomato.ssh.SSHTerminalPane pane) {
                pane.updateTerminalFont(family, size);
            } else if (content instanceof com.tangluobo.tomato.ssh.LocalTerminalPane pane) {
                pane.updateTerminalFont(family, size);
            }
        }
    }

    /** 刷新主机节点 dispatcher：根据连接类型分发到对应处理器 */
    public void refreshDbHost(TreeItem<String> hostItem, ConnectionConfig config) {
        ConnectType type = config.getType();
        boolean isDatabase = type == ConnectType.MYSQL
                || type == ConnectType.POSTGRESQL
                || type == ConnectType.ORACLE;
        if (isDatabase) {
            AbstractDbHandler handler = createDbHandler(config);
            if (handler != null) {
                handler.refreshDbHost(hostItem, config);
            }
        } else {
            // Redis/RocketMQ/Aliyun 等：清空子节点后重新触发双击连接
            for (TreeItem<String> child : hostItem.getChildren()) {
                removeDbNodeDataRecursive(child);
            }
            hostItem.getChildren().clear();
            triggerHostDoubleClick(hostItem, config);
        }
    }

    /** 刷新节点 dispatcher：根据节点类型分发到对应处理器 */
    public void refreshDbNode(TreeItem<String> item, DatabaseNodeData data) {
        ConnectionConfig config = data.getConnectionConfig();
        switch (data.getType()) {
            case DATABASE, SCHEMA, TABLES_FOLDER, VIEWS_FOLDER, QUERY_FOLDER, BACKUP_FOLDER -> {
                AbstractDbHandler handler = createDbHandler(config);
                if (handler != null) {
                    handler.refreshDbNode(item, data);
                }
            }
            case ROCKETMQ_TOPICS_FOLDER, ROCKETMQ_CONSUMERS_FOLDER, ROCKETMQ_CLUSTER_FOLDER -> {
                item.getChildren().clear();
                ConnectHandler handler = createConnectHandler(config);
                if (handler instanceof RocketmqConnectHandler rq) {
                    rq.refreshDbNode(this, item, data);
                }
            }
            case KAFKA_TOPICS_FOLDER, KAFKA_CONSUMERS_FOLDER, KAFKA_CLUSTER_FOLDER -> {
                item.getChildren().clear();
                ConnectHandler handler = createConnectHandler(config);
                if (handler instanceof KafkaConnectHandler kf) {
                    kf.refreshDbNode(this, item, data);
                }
            }
            case ALIYUN_PRODUCT_FOLDER, ALIYUN_DOMAIN -> {
                ConnectHandler handler = createConnectHandler(config);
                if (handler instanceof AliyunConnectHandler al) {
                    al.refreshDbNode(this, item, data);
                }
            }
            case LOCAL_DIR_FOLDER -> {
                ConnectHandler handler = createConnectHandler(config);
                if (handler instanceof LocalDirectoryConnectHandler ld) {
                    ld.refreshDbNode(this, item, data);
                }
            }
            default -> {}
        }
    }

    /** 删除节点 dispatcher：根据选中项的连接配置分发到对应数据库处理器 */
    public void deleteDbNodes() {
        ObservableList<TreeItem<String>> selectedItems = treeView.getSelectionModel().getSelectedItems();
        ConnectionConfig cfg = null;
        for (TreeItem<String> item : selectedItems) {
            DatabaseNodeData data = dbNodeDataMap.get(item);
            if (data != null) {
                cfg = data.getConnectionConfig();
                break;
            }
        }
        if (cfg == null) return;
        AbstractDbHandler handler = createDbHandler(cfg);
        if (handler != null) {
            handler.handleDeleteDbNodes();
        }
    }

    /** 删除本地目录文件/文件夹 dispatcher：支持多选，委托给 LocalDirectoryConnectHandler */
    public void deleteLocalDirNodes() {
        ObservableList<TreeItem<String>> selectedItems = treeView.getSelectionModel().getSelectedItems();
        ConnectionConfig cfg = null;
        for (TreeItem<String> item : selectedItems) {
            DatabaseNodeData data = dbNodeDataMap.get(item);
            if (data != null && (data.getType() == DatabaseNodeData.NodeType.LOCAL_DIR_FOLDER
                    || data.getType() == DatabaseNodeData.NodeType.LOCAL_DIR_FILE)) {
                cfg = data.getConnectionConfig();
                break;
            }
        }
        if (cfg == null) return;
        ConnectHandler h = createConnectHandler(cfg);
        if (h instanceof LocalDirectoryConnectHandler ld) {
            ld.handleDeleteNodes(this);
        }
    }

    /** 供 handler 调用：显示数据视图（实际为终端视图） */
    public void showDataView() {
        showTerminalView();
    }

    private void handleAddFolder(TreeItem<String> parent) {
        Stage stage = getStage();
        if (stage == null) return;

        FolderDialog dialog = new FolderDialog(stage);
        String folderName = dialog.showAndWait();
        if (folderName != null) {
            ConnectionConfig folderConfig = new ConnectionConfig();
            folderConfig.setId(ConfigManager.generateId());
            folderConfig.setName(folderName);
            ConnectionConfig parentConfig = itemConfigMap.get(parent);
            if (parent != root && parentConfig != null) {
                folderConfig.setParentId(parentConfig.getId());
            }
            folderConfig.setType(null);

            connections.add(folderConfig);
            saveConnectionsWithFeedback();

            TreeItem<String> folderItem = new TreeItem<>(folderName);
            if (folderIcon != null) {
                ImageView icon = new ImageView(folderIcon);
                icon.setFitWidth(16);
                icon.setFitHeight(16);
                folderItem.setGraphic(icon);
            }
            itemConfigMap.put(folderItem, folderConfig);
            addChildToParent(parent, folderItem);
        }
    }

    private void handleAddConnection(TreeItem<String> parent) {
        Stage stage = getStage();
        if (stage == null) return;

        ConnectionConfigDialog configDialog = new ConnectionConfigDialog(stage);
        ConnectionConfig config = configDialog.showAndWait();
        if (config != null) {
            config.setId(ConfigManager.generateId());
            ConnectionConfig parentConfig = itemConfigMap.get(parent);
            if (parent != root && parentConfig != null) {
                config.setParentId(parentConfig.getId());
            }

            connections.add(config);
            saveConnectionsWithFeedback();

            TreeItem<String> connectionItem = createTreeItem(config);
            addChildToParent(parent, connectionItem);
        }
    }

    private void handleEdit(TreeItem<String> item) {
        ConnectionConfig existingConfig = itemConfigMap.get(item);
        if (existingConfig == null || existingConfig.getType() == null) return;
        if (existingConfig.getType() == ConnectType.TOOL) return; // 工具不支持编辑

        Stage stage = getStage();
        if (stage == null) return;

        ConnectionConfigDialog dialog = new ConnectionConfigDialog(stage, existingConfig);
        ConnectionConfig updatedConfig = dialog.showAndWait();
        if (updatedConfig != null) {
            connections.removeIf(c -> c.getId().equals(existingConfig.getId()));
            connections.add(updatedConfig);
            saveConnectionsWithFeedback();

            itemConfigMap.remove(item);
            itemConfigMap.put(item, updatedConfig);

            item.setValue(updatedConfig.getName());

            // 更新已打开的 S3/OSS 标签页持有的配置引用，使编辑立即生效
            // （如修改了访问URL后无需重开标签页即可复制访问地址）
            if (terminalTabPane != null) {
                for (Tab t : terminalTabPane.getTabs()) {
                    if (existingConfig.getId().equals(t.getUserData())
                            && t.getContent() instanceof S3FileBrowserPane) {
                        ((S3FileBrowserPane) t.getContent()).updateConfig(updatedConfig);
                        break;
                    }
                }
            }
        }
    }

    private void handleCopyConnection(TreeItem<String> item, ConnectionConfig sourceConfig) {
        Gson gson = new Gson();
        ConnectionConfig copiedConfig = gson.fromJson(gson.toJson(sourceConfig), ConnectionConfig.class);
        copiedConfig.setId(ConfigManager.generateId());
        copiedConfig.setName(sourceConfig.getName() + " - 副本");

        // 保持与原连接相同的 parentId：如果原连接是根级（parentId==null），则副本也是根级
        copiedConfig.setParentId(sourceConfig.getParentId());

        connections.add(copiedConfig);
        if (!saveConnectionsWithFeedback()) {
            // 保存失败，回滚 connections，保持内存与磁盘一致
            connections.remove(copiedConfig);
            return;
        }

        TreeItem<String> copiedItem = createTreeItem(copiedConfig);
        TreeItem<String> parent = item.getParent();
        if (parent != null) {
            int index = parent.getChildren().indexOf(item);
            parent.getChildren().add(index + 1, copiedItem);
        } else {
            // 理论上不会出现（item 必有 parent），兜底作为根节点子项
            addChildToParent(root, copiedItem);
        }
    }

    private void handleDelete(TreeItem<String> item) {
        ConnectionConfig config = itemConfigMap.get(item);
        if (config == null) return;

        boolean isFolder = config.getType() == null;
        boolean hasChildren = !item.getChildren().isEmpty();

        if (isFolder && hasChildren) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("删除目录");
            alert.setHeaderText("确定要删除目录 \"" + config.getName() + "\" 吗？");
            alert.setContentText("该目录下包含子节点，请选择操作：");

            ButtonType keepChildrenBtn = new ButtonType("保留子节点");
            ButtonType deleteAllBtn = new ButtonType("连同子节点一起删除");
            ButtonType cancelBtn = ButtonType.CANCEL;

            alert.getButtonTypes().setAll(keepChildrenBtn, deleteAllBtn, cancelBtn);

            DialogPositionUtil.centerOnOwner(alert, getStage());
            Optional<ButtonType> result = alert.showAndWait();
            if (result.isPresent()) {
                if (result.get() == keepChildrenBtn) {
                    String parentId = config.getParentId();
                    for (TreeItem<String> child : item.getChildren()) {
                        ConnectionConfig childConfig = itemConfigMap.get(child);
                        if (childConfig != null) {
                            childConfig.setParentId(parentId);
                        }
                    }
                    connections.removeIf(c -> c.getId().equals(config.getId()));
                    saveConnectionsWithFeedback();
                    loadTree();
                } else if (result.get() == deleteAllBtn) {
                    removeConfigAndChildren(config.getId());
                    saveConnectionsWithFeedback();
                    loadTree();
                }
            }
        } else {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("删除确认");
            alert.setHeaderText("确定要删除 \"" + config.getName() + "\" 吗？");

            DialogPositionUtil.centerOnOwner(alert, getStage());
            Optional<ButtonType> result = alert.showAndWait();
            if (result.isPresent() && result.get() == ButtonType.OK) {
                removeConfigAndChildren(config.getId());
                saveConnectionsWithFeedback();
                loadTree();
            }
        }
    }

    private void removeConfigAndChildren(String parentId) {
        connections.removeIf(config -> {
            if (config.getId().equals(parentId)) {
                return true;
            }
            if (parentId.equals(config.getParentId())) {
                removeConfigAndChildren(config.getId());
                return true;
            }
            return false;
        });
    }

    /** 供 handler 调用：获取所属 Stage */
    public Stage getStage() {
        Node node = treeView;
        while (node != null && !(node.getScene() != null && node.getScene().getWindow() instanceof Stage)) {
            node = node.getParent();
        }
        if (node != null && node.getScene() != null && node.getScene().getWindow() instanceof Stage) {
            return (Stage) node.getScene().getWindow();
        }
        return null;
    }

    /** 供 handler 调用：确认 TabPane 已安装 */
    public boolean ensureTabPaneInstalled() {
        // 现在 terminalTabPane 始终在 contentArea 中，直接返回 true
        return terminalTabPane != null;
    }

    private void handleExportConnections() {
        if (connections.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("没有可导出的连接");
            DialogPositionUtil.centerOnOwner(alert, getStage());
            alert.showAndWait();
            return;
        }

        Stage stage = getStage();
        if (stage == null) return;

        ExportConnectionDialog dialog = new ExportConnectionDialog(stage, connections);
        if (!dialog.showAndWait()) return;

        List<ConnectionConfig> selected = dialog.getSelectedConfigs();
        boolean includePasswords = dialog.includePasswords();

        // 深拷贝选中的配置，避免修改内存中的原始对象
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        List<ConnectionConfig> exportConfigs = new ArrayList<>();
        for (ConnectionConfig config : selected) {
            ConnectionConfig copy = gson.fromJson(gson.toJson(config), ConnectionConfig.class);
            if (!includePasswords) {
                copy.setPassword(null);
                copy.setSshTunnelPassword(null);
            }
            exportConfigs.add(copy);
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("导出连接");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JSON 文件", "*.json")
        );
        fileChooser.setInitialFileName("connections_export.json");

        java.io.File file = fileChooser.showSaveDialog(stage);
        if (file == null) return;

        try {
            JsonObject exportObj = new JsonObject();
            exportObj.addProperty("format", "tomato-connections-export");
            exportObj.addProperty("version", 1);
            exportObj.add("connections", gson.toJsonTree(exportConfigs));

            String json = gson.toJson(exportObj);
            String encryptedContent = "TOMATO_ENCRYPTED" + SecurityUtils.encrypt(json);
            Files.writeString(file.toPath(), encryptedContent, StandardCharsets.UTF_8);

            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("导出成功");
            alert.setHeaderText(null);
            alert.setContentText("已导出 " + exportConfigs.size() + " 个连接到:\n" + file.getAbsolutePath());
            DialogPositionUtil.centerOnOwner(alert, getStage());
            alert.showAndWait();
        } catch (Exception e) {
            e.printStackTrace();
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("导出失败");
            alert.setHeaderText(null);
            alert.setContentText("导出失败: " + e.getMessage());
            DialogPositionUtil.centerOnOwner(alert, getStage());
            alert.showAndWait();
        }
    }

    private void handleImportConnections() {
        Stage stage = getStage();
        if (stage == null) return;

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("导入连接");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JSON 文件", "*.json")
        );

        java.io.File file = fileChooser.showOpenDialog(stage);
        if (file == null) return;

        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            // 解密：如果文件以加密标记开头，先解密
            if (content.startsWith("TOMATO_ENCRYPTED")) {
                content = SecurityUtils.decrypt(content.substring("TOMATO_ENCRYPTED".length()));
            }
            com.google.gson.JsonElement jsonElement = JsonParser.parseString(content);

            List<ConnectionConfig> importConfigs;
            if (jsonElement.isJsonObject()) {
                JsonObject importObj = jsonElement.getAsJsonObject();
                String format = importObj.has("format") ? importObj.get("format").getAsString() : null;
                if ("tomato-connections-export".equals(format)) {
                    ConnectionConfig[] configs = new Gson().fromJson(importObj.getAsJsonArray("connections"), ConnectionConfig[].class);
                    importConfigs = configs != null ? new ArrayList<>(List.of(configs)) : new ArrayList<>();
                } else {
                    // 尝试从对象中直接读取 connections 数组
                    if (importObj.has("connections")) {
                        ConnectionConfig[] configs = new Gson().fromJson(importObj.getAsJsonArray("connections"), ConnectionConfig[].class);
                        importConfigs = configs != null ? new ArrayList<>(List.of(configs)) : new ArrayList<>();
                    } else {
                        importConfigs = new ArrayList<>();
                    }
                }
            } else if (jsonElement.isJsonArray()) {
                // 兼容纯数组格式
                ConnectionConfig[] configs = new Gson().fromJson(jsonElement.getAsJsonArray(), ConnectionConfig[].class);
                importConfigs = configs != null ? new ArrayList<>(List.of(configs)) : new ArrayList<>();
            } else {
                importConfigs = new ArrayList<>();
            }

            if (importConfigs.isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("导入");
                alert.setHeaderText(null);
                alert.setContentText("文件中没有可导入的连接");
                DialogPositionUtil.centerOnOwner(alert, getStage());
                alert.showAndWait();
                return;
            }

            // 生成新 ID 并重映射 parentId，保留目录结构
            Map<String, String> idMapping = new HashMap<>();
            for (ConnectionConfig config : importConfigs) {
                String oldId = config.getId();
                String newId = ConfigManager.generateId();
                config.setId(newId);
                if (oldId != null) {
                    idMapping.put(oldId, newId);
                }
            }
            for (ConnectionConfig config : importConfigs) {
                String oldParentId = config.getParentId();
                if (oldParentId != null && !oldParentId.isEmpty()) {
                    String newParentId = idMapping.get(oldParentId);
                    if (newParentId != null) {
                        config.setParentId(newParentId);
                    } else {
                        // 父节点不在导入范围内，设为根级
                        config.setParentId(null);
                    }
                }
            }

            // 检查名称冲突，重名时添加后缀
            Set<String> existingNames = new HashSet<>();
            for (ConnectionConfig c : connections) {
                existingNames.add(c.getName());
            }
            for (ConnectionConfig config : importConfigs) {
                String baseName = config.getName();
                String name = baseName;
                int suffix = 1;
                while (existingNames.contains(name)) {
                    name = baseName + " (" + suffix + ")";
                    suffix++;
                }
                config.setName(name);
                existingNames.add(name);
            }

            connections.addAll(importConfigs);
            saveConnectionsWithFeedback();
            loadTree();

            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("导入成功");
            alert.setHeaderText(null);
            alert.setContentText("已成功导入 " + importConfigs.size() + " 个连接");
            DialogPositionUtil.centerOnOwner(alert, getStage());
            alert.showAndWait();
        } catch (Exception e) {
            e.printStackTrace();
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("导入失败");
            alert.setHeaderText(null);
            alert.setContentText("导入失败: " + e.getMessage());
            DialogPositionUtil.centerOnOwner(alert, getStage());
            alert.showAndWait();
        }
    }

    @Override
    public void loadContent(VBox contentArea) {
        this.contentArea = contentArea;
        contentArea.getChildren().clear();
        contentArea.setSpacing(0);
        contentArea.setPadding(Insets.EMPTY);
        contentArea.setStyle("-fx-background-color: #ffffff; -fx-background-insets: 0; -fx-padding: 0; -fx-border-insets: 0;");

        // 直接创建并添加 TabPane
        terminalTabPane = new TabPane();
        terminalTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        terminalTabPane.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        terminalTabPane.setFocusTraversable(false);
        terminalTabPane.setPadding(Insets.EMPTY);
        terminalTabPane.setStyle("-fx-padding: 0; -fx-background-insets: 0; -fx-border-insets: 0; -fx-tab-content-padding: 0;");
        VBox.setVgrow(terminalTabPane, Priority.ALWAYS);

        contentArea.getChildren().add(terminalTabPane);
        terminalTabPane.setVisible(true);
        terminalTabPane.setManaged(true);

        // 切换标签时自动将输入焦点转移到终端视图，无需再点击终端区域
        terminalTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == null) return;
            if (newTab.getContent() instanceof SSHTerminalPane pane) {
                pane.requestTerminalFocus();
            } else if (newTab.getContent() instanceof LocalTerminalPane pane) {
                pane.requestTerminalFocus();
            }
        });
    }

    /**
     * 用固定尺寸的 StackPane 包装 tab 图标：ImageView 在 TabPane 下拉菜单 popup 中
     * fitWidth/fitHeight 可能不生效，用 StackPane（Region）包装并锁定 maxSize/minSize，
     * 确保图标在 popup 中也保持固定尺寸。
     * 必须在 tab 添加到 TabPane 之前调用，避免 relayout 闪烁。
     */
    public static Region createFixedSizeGraphic(ImageView iv) {
        double size = iv.getFitWidth() > 0 ? iv.getFitWidth() : 18;
        iv.setPreserveRatio(true);
        StackPane pane = new StackPane(iv);
        pane.setMaxSize(size, size);
        pane.setMinSize(size, size);
        pane.setPrefSize(size, size);
        pane.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        return pane;
    }
}