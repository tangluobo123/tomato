package com.tangluobo.tomato.module.connect.view;

import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.service.RedisService;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.util.Duration;
import java.util.*;
import java.util.List;

public class RedisDataView extends BorderPane {
    
    private final ConnectionConfig config;
    private final int database;
    
    // 左侧目录树
    private TreeView<String> keyTreeView;
    private TreeItem<String> keyTreeRoot;
    
    // 右侧值编辑区
    private VBox valueEditorPane;
    private Label keyLabel;
    private Label typeLabel;
    private Label ttlLabel;
    private VBox valueContent;  // 动态值编辑区域
    
    // 工具栏
    private ToolBar toolBar;
    
    // 当前选中key的信息
    private String currentKey;
    private String currentType;

    // TTL倒计时
    private Timeline ttlTimeline;
    private long currentTtlSeconds = -1;
    
    // 图标
    private static Image folderIcon;
    private static Image keyIcon;
    private static Image stringIcon;
    private static Image listIcon;
    private static Image setIcon;
    private static Image zsetIcon;
    private static Image hashIcon;
    
    public RedisDataView(ConnectionConfig config, int database) {
        this.config = config;
        this.database = database;
        
        loadIcons();
        initComponents();
        loadKeyTree();
    }
    
    private void loadIcons() {
        // 尝试加载图标，失败则使用null
        try { folderIcon = new Image(getClass().getResourceAsStream("/images/connect/folder.png")); } catch (Exception e) {}
        // key图标使用数据库图标代替
        try { keyIcon = new Image(getClass().getResourceAsStream("/images/connect/database.png")); } catch (Exception e) {}
    }
    
    private void initComponents() {
        // 创建工具栏
        createToolBar();
        
        // 创建左侧目录树
        createKeyTree();
        
        // 创建右侧值编辑区
        createValueEditor();
        
        // 创建分割面板
        SplitPane splitPane = new SplitPane();
        splitPane.setDividerPositions(0.3);
        
        // 左侧：目录树（带搜索框）
        VBox leftPane = new VBox(5);
        TextField searchField = new TextField();
        searchField.setPromptText("搜索Key...");
        searchField.setStyle("-fx-background-color: #f0f0f0; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-padding: 4 8;");
        
        Button searchBtn = new Button("搜索");
        searchBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px;");
        searchBtn.setOnAction(e -> searchKeys(searchField.getText().trim()));
        
        HBox searchBox = new HBox(5, searchField, searchBtn);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        leftPane.getChildren().addAll(searchBox, keyTreeView);
        VBox.setVgrow(keyTreeView, Priority.ALWAYS);
        
        // 右侧：值编辑区
        ScrollPane rightScrollPane = new ScrollPane(valueEditorPane);
        rightScrollPane.setFitToWidth(true);
        rightScrollPane.setFitToHeight(true);
        
        splitPane.getItems().addAll(leftPane, rightScrollPane);
        
        this.setTop(toolBar);
        this.setCenter(splitPane);
    }
    
    private void createToolBar() {
        toolBar = new ToolBar();
        toolBar.setStyle("-fx-background-color: #fafafa; -fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0;");
        
        Button refreshBtn = new Button("刷新");
        refreshBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-padding: 4 12;");
        refreshBtn.setOnAction(e -> loadKeyTree());
        
        Button addKeyBtn = new Button("添加Key");
        addKeyBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-padding: 4 12;");
        addKeyBtn.setOnAction(e -> showAddKeyDialog());
        
        Button deleteKeyBtn = new Button("删除Key");
        deleteKeyBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-padding: 4 12;");
        deleteKeyBtn.setOnAction(e -> deleteSelectedKeys());
        
        Button ttlBtn = new Button("TTL");
        ttlBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-padding: 4 12;");
        ttlBtn.setOnAction(e -> showTtlDialog());
        
        toolBar.getItems().addAll(refreshBtn, addKeyBtn, deleteKeyBtn, ttlBtn);
    }
    
    private void createKeyTree() {
        keyTreeView = new TreeView<>();
        keyTreeView.setStyle("-fx-background-color: transparent;");
        keyTreeRoot = new TreeItem<>("Keys");
        keyTreeRoot.setExpanded(true);
        keyTreeView.setRoot(keyTreeRoot);
        keyTreeView.setShowRoot(false);

        // 启用多选
        keyTreeView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        // 自定义CellFactory：使节点内容和箭头垂直居中
        keyTreeView.setCellFactory(tree -> new TreeCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                    // 复制TreeItem的图标
                    TreeItem<String> treeItem = getTreeItem();
                    if (treeItem != null && treeItem.getGraphic() != null) {
                        ImageView src = (ImageView) treeItem.getGraphic();
                        ImageView iv = new ImageView(src.getImage());
                        iv.setFitWidth(src.getFitWidth());
                        iv.setFitHeight(src.getFitHeight());
                        setGraphic(iv);
                    } else {
                        setGraphic(null);
                    }
                    // 内容垂直居中
                    setAlignment(Pos.CENTER_LEFT);
                }
            }

            @Override
            protected void layoutChildren() {
                super.layoutChildren();
                // 将disclosure arrow垂直居中
                lookupAll(".tree-disclosure-node").forEach(node -> {
                    if (node instanceof StackPane disclosureNode) {
                        double cellHeight = getHeight();
                        double arrowHeight = disclosureNode.prefHeight(-1);
                        if (cellHeight > 0 && arrowHeight > 0) {
                            double newY = (cellHeight - arrowHeight) / 2.0;
                            disclosureNode.setLayoutY(newY);
                        }
                    }
                });
            }
        });

        keyTreeView.setFixedCellSize(30);

        // 左键单击选择key
        keyTreeView.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 1) {
                TreeItem<String> selected = keyTreeView.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getGraphic() != null) {
                    handleKeySelection(selected);
                }
            }
        });

        // 右键菜单
        setupTreeContextMenu();
    }

    // 设置右键菜单
    private void setupTreeContextMenu() {
        ContextMenu contextMenu = new ContextMenu();

        MenuItem addMenuItem = new MenuItem("添加Key");
        addMenuItem.setOnAction(e -> showAddKeyDialog());

        MenuItem deleteMenuItem = new MenuItem("删除");
        deleteMenuItem.setStyle("-fx-text-fill: #cc0000;");
        deleteMenuItem.setOnAction(e -> deleteSelectedKeys());

        MenuItem renameMenuItem = new MenuItem("重命名");
        renameMenuItem.setOnAction(e -> renameSelectedKey());

        MenuItem ttlMenuItem = new MenuItem("设置TTL");
        ttlMenuItem.setOnAction(e -> showTtlDialog());

        MenuItem copyKeyMenuItem = new MenuItem("复制Key名");
        copyKeyMenuItem.setOnAction(e -> copySelectedKeyName());

        contextMenu.getItems().addAll(addMenuItem, new SeparatorMenuItem(), deleteMenuItem, renameMenuItem, ttlMenuItem, new SeparatorMenuItem(), copyKeyMenuItem);

        keyTreeView.setContextMenu(contextMenu);

        // 菜单显示前动态控制菜单项可用性
        contextMenu.setOnShowing(event -> {
            ObservableList<TreeItem<String>> selectedItems = keyTreeView.getSelectionModel().getSelectedItems();
            int selectedCount = (int) selectedItems.stream()
                    .filter(item -> item != null && item != keyTreeRoot)
                    .count();

            deleteMenuItem.setDisable(selectedCount == 0);
            renameMenuItem.setDisable(selectedCount != 1);
            ttlMenuItem.setDisable(selectedCount != 1);
            copyKeyMenuItem.setDisable(selectedCount == 0);

            if (selectedCount > 1) {
                deleteMenuItem.setText("删除 (" + selectedCount + "个)");
            } else {
                deleteMenuItem.setText("删除");
            }
        });
    }

    // 复制选中的Key名到剪贴板
    private void copySelectedKeyName() {
        ObservableList<TreeItem<String>> selectedItems = keyTreeView.getSelectionModel().getSelectedItems();
        List<String> keyNames = new ArrayList<>();
        for (TreeItem<String> item : selectedItems) {
            if (item != null && item != keyTreeRoot) {
                String key = buildFullKey(item);
                if (key != null) keyNames.add(key);
            }
        }
        if (!keyNames.isEmpty()) {
            javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
            content.putString(String.join("\n", keyNames));
            clipboard.setContent(content);
        }
    }
    
    private void createValueEditor() {
        valueEditorPane = new VBox(15);
        valueEditorPane.setPadding(new Insets(20));
        valueEditorPane.setStyle("-fx-background-color: white;");
        
        keyLabel = new Label("Key: ");
        keyLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        
        typeLabel = new Label("Type: ");
        typeLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        
        ttlLabel = new Label("TTL: ");
        ttlLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        
        Separator separator = new Separator();
        
        valueContent = new VBox(10);
        
        // 提示信息
        Label hintLabel = new Label("选择左侧Key查看值");
        hintLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #999;");
        valueContent.getChildren().add(hintLabel);
        
        valueEditorPane.getChildren().addAll(keyLabel, typeLabel, ttlLabel, separator, valueContent);
    }
    
    // 加载key树
    public void loadKeyTree() {
        keyTreeRoot.getChildren().clear();
        
        new Thread(() -> {
            try {
                List<String> keys = RedisService.scanKeys(config, database, "*");
                Platform.runLater(() -> buildKeyHierarchy(keys));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载Key列表: " + e.getMessage());
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
            }
        }, "Redis-LoadKeys").start();
    }
    
    // 按":"分隔构建层级目录树
    private void buildKeyHierarchy(List<String> keys) {
        keyTreeRoot.getChildren().clear();
        
        // 使用Map来构建层级结构
        // key: 路径前缀（如"user:session"）, value: TreeItem
        Map<String, TreeItem<String>> pathNodeMap = new LinkedHashMap<>();
        
        for (String key : keys) {
            String[] parts = key.split(":");
            StringBuilder pathBuilder = new StringBuilder();
            TreeItem<String> parent = keyTreeRoot;
            
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                if (pathBuilder.length() > 0) pathBuilder.append(":");
                pathBuilder.append(part);
                
                String currentPath = pathBuilder.toString();
                boolean isLeaf = (i == parts.length - 1);
                
                if (!pathNodeMap.containsKey(currentPath)) {
                    TreeItem<String> node = new TreeItem<>(part);
                    if (isLeaf) {
                        // 叶子节点 - 设置key图标
                        setKeyIcon(node, currentPath);
                    } else {
                        // 目录节点 - 设置文件夹图标
                        if (folderIcon != null) {
                            ImageView iv = new ImageView(folderIcon);
                            iv.setFitWidth(16);
                            iv.setFitHeight(16);
                            node.setGraphic(iv);
                        }
                    }
                    node.setExpanded(false);
                    parent.getChildren().add(node);
                    pathNodeMap.put(currentPath, node);
                }
                parent = pathNodeMap.get(currentPath);
            }
        }
        
        keyTreeRoot.setExpanded(true);
    }
    
    // 设置key节点的图标（根据类型）
    private void setKeyIcon(TreeItem<String> node, String fullKey) {
        // 默认key图标
        ImageView iv = new ImageView();
        iv.setFitWidth(16);
        iv.setFitHeight(16);
        if (keyIcon != null) {
            iv.setImage(keyIcon);
        }
        node.setGraphic(iv);
    }
    
    // 处理key选择
    private void handleKeySelection(TreeItem<String> item) {
        // 从树节点重建完整key路径
        String fullKey = buildFullKey(item);
        if (fullKey == null) return;
        
        // 检查是否为叶子节点（实际key）
        // 叶子节点的判断：该节点没有目录类型的子节点，或者该key在Redis中存在
        currentKey = fullKey;
        
        new Thread(() -> {
            try {
                String type = RedisService.getKeyType(config, database, fullKey);
                if ("none".equals(type)) {
                    // 不是实际的key，可能是目录前缀
                    Platform.runLater(() -> showEmptyValue(fullKey));
                    return;
                }
                currentType = type;
                Map<String, Object> detail = RedisService.getKeyDetail(config, database, fullKey);
                Platform.runLater(() -> showValue(fullKey, detail));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载Key值: " + e.getMessage());
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
            }
        }, "Redis-LoadValue").start();
    }
    
    // 从树节点重建完整key路径
    private String buildFullKey(TreeItem<String> item) {
        if (item == null || item == keyTreeRoot) return null;
        List<String> parts = new ArrayList<>();
        TreeItem<String> current = item;
        while (current != null && current != keyTreeRoot) {
            parts.add(0, current.getValue());
            current = current.getParent();
        }
        return String.join(":", parts);
    }
    
    // 显示空值（目录节点被选中时）
    private void showEmptyValue(String keyPath) {
        // 停止TTL倒计时
        if (ttlTimeline != null) {
            ttlTimeline.stop();
            ttlTimeline = null;
        }
        keyLabel.setText("Key: " + keyPath);
        typeLabel.setText("Type: 目录");
        ttlLabel.setText("TTL: -");
        ttlLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        valueContent.getChildren().clear();
        Label hintLabel = new Label("这是一个目录前缀，不是实际的Key");
        hintLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #999;");
        valueContent.getChildren().add(hintLabel);
    }
    
    // 显示值
    private void showValue(String key, Map<String, Object> detail) {
        keyLabel.setText("Key: " + key);
        String type = (String) detail.getOrDefault("type", "unknown");
        typeLabel.setText("Type: " + type.toUpperCase());

        // TTL倒计时
        Object ttlObj = detail.get("ttl");
        if (ttlObj instanceof Long) {
            currentTtlSeconds = (Long) ttlObj;
        } else {
            currentTtlSeconds = -1;
        }
        startTtlCountdown();

        valueContent.getChildren().clear();

        Object value = detail.get("value");

        switch (type.toLowerCase()) {
            case "string" -> showStringValue(value != null ? value.toString() : "");
            case "list" -> showListValue(value);
            case "set" -> showSetValue(value);
            case "zset" -> showZSetValue(value);
            case "hash" -> showHashValue(value);
            default -> {
                Label unknownLabel = new Label("不支持的类型: " + type);
                unknownLabel.setStyle("-fx-text-fill: #cc0000;");
                valueContent.getChildren().add(unknownLabel);
            }
        }
    }

    // 启动TTL倒计时
    private void startTtlCountdown() {
        // 停止之前的倒计时
        if (ttlTimeline != null) {
            ttlTimeline.stop();
            ttlTimeline = null;
        }

        updateTtlText();

        if (currentTtlSeconds > 0) {
            ttlTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> {
                currentTtlSeconds--;
                if (currentTtlSeconds <= 0) {
                    ttlLabel.setText("TTL: 已过期");
                    ttlLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #cc0000;");
                    if (ttlTimeline != null) {
                        ttlTimeline.stop();
                        ttlTimeline = null;
                    }
                    return;
                }
                updateTtlText();
            }));
            ttlTimeline.setCycleCount(Animation.INDEFINITE);
            ttlTimeline.play();
        }
    }

    // 更新TTL显示文本
    private void updateTtlText() {
        if (currentTtlSeconds == -1) {
            ttlLabel.setText("TTL: 永不过期");
            ttlLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        } else if (currentTtlSeconds == -2) {
            ttlLabel.setText("TTL: Key不存在");
            ttlLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #cc0000;");
        } else if (currentTtlSeconds <= 0) {
            ttlLabel.setText("TTL: 已过期");
            ttlLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #cc0000;");
        } else {
            String formatted = formatTtl(currentTtlSeconds);
            ttlLabel.setText("TTL: " + formatted);
            // 根据剩余时间变色：<60秒红色，<300秒橙色，其余默认
            if (currentTtlSeconds < 60) {
                ttlLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #cc0000;");
            } else if (currentTtlSeconds < 300) {
                ttlLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #e6a700;");
            } else {
                ttlLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
            }
        }
    }

    // 格式化TTL为可读格式
    private String formatTtl(long seconds) {
        if (seconds < 60) {
            return seconds + "秒";
        } else if (seconds < 3600) {
            long m = seconds / 60;
            long s = seconds % 60;
            return m + "分" + s + "秒";
        } else if (seconds < 86400) {
            long h = seconds / 3600;
            long m = (seconds % 3600) / 60;
            long s = seconds % 60;
            return h + "时" + m + "分" + s + "秒";
        } else {
            long d = seconds / 86400;
            long h = (seconds % 86400) / 3600;
            long m = (seconds % 3600) / 60;
            return d + "天" + h + "时" + m + "分";
        }
    }
    
    // === STRING 类型编辑 ===
    private void showStringValue(String value) {
        Label valueLabel = new Label("值：");
        valueLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        
        TextArea valueArea = new TextArea(value != null ? value : "");
        valueArea.setPrefRowCount(10);
        valueArea.setWrapText(true);
        valueArea.setStyle("-fx-font-family: monospace; -fx-font-size: 13px;");
        
        Button saveBtn = new Button("保存");
        saveBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px;");
        saveBtn.setOnAction(e -> {
            String newValue = valueArea.getText();
            saveValue("string", newValue);
        });
        
        Button resetBtn = new Button("重置");
        resetBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px;");
        resetBtn.setOnAction(e -> handleKeySelection(keyTreeView.getSelectionModel().getSelectedItem()));
        
        HBox buttonBox = new HBox(10, saveBtn, resetBtn);
        valueContent.getChildren().addAll(valueLabel, valueArea, buttonBox);
    }
    
    // === LIST 类型编辑 ===
    private void showListValue(Object value) {
        Label valueLabel = new Label("值（每行一个元素）：");
        valueLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        
        TextArea valueArea = new TextArea();
        valueArea.setPrefRowCount(10);
        valueArea.setStyle("-fx-font-family: monospace; -fx-font-size: 13px;");
        
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                sb.append(item.toString()).append("\n");
            }
            valueArea.setText(sb.toString().stripTrailing());
        }
        
        Button saveBtn = new Button("保存");
        saveBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px;");
        saveBtn.setOnAction(e -> {
            String text = valueArea.getText();
            List<String> items = Arrays.asList(text.split("\\n"));
            saveValue("list", items);
        });
        
        Button resetBtn = new Button("重置");
        resetBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px;");
        resetBtn.setOnAction(e -> handleKeySelection(keyTreeView.getSelectionModel().getSelectedItem()));
        
        HBox buttonBox = new HBox(10, saveBtn, resetBtn);
        valueContent.getChildren().addAll(valueLabel, valueArea, buttonBox);
    }
    
    // === SET 类型编辑 ===
    private void showSetValue(Object value) {
        Label valueLabel = new Label("值（每行一个元素）：");
        valueLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        
        TextArea valueArea = new TextArea();
        valueArea.setPrefRowCount(10);
        valueArea.setStyle("-fx-font-family: monospace; -fx-font-size: 13px;");
        
        if (value instanceof Collection) {
            Collection<?> coll = (Collection<?>) value;
            StringBuilder sb = new StringBuilder();
            for (Object item : coll) {
                sb.append(item.toString()).append("\n");
            }
            valueArea.setText(sb.toString().stripTrailing());
        }
        
        Button saveBtn = new Button("保存");
        saveBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px;");
        saveBtn.setOnAction(e -> {
            String text = valueArea.getText();
            List<String> items = Arrays.asList(text.split("\\n"));
            saveValue("set", items);
        });
        
        Button resetBtn = new Button("重置");
        resetBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px;");
        resetBtn.setOnAction(e -> handleKeySelection(keyTreeView.getSelectionModel().getSelectedItem()));
        
        HBox buttonBox = new HBox(10, saveBtn, resetBtn);
        valueContent.getChildren().addAll(valueLabel, valueArea, buttonBox);
    }
    
    // === ZSET 类型编辑 ===
    private void showZSetValue(Object value) {
        Label valueLabel = new Label("值（格式: score member，每行一个）：");
        valueLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        
        TextArea valueArea = new TextArea();
        valueArea.setPrefRowCount(10);
        valueArea.setStyle("-fx-font-family: monospace; -fx-font-size: 13px;");
        
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            StringBuilder sb = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Map) {
                    Map<?, ?> map = (Map<?, ?>) item;
                    String member = String.valueOf(map.get("member"));
                    String score = String.valueOf(map.get("score"));
                    sb.append(score).append(" ").append(member).append("\n");
                }
            }
            valueArea.setText(sb.toString().stripTrailing());
        }
        
        Button saveBtn = new Button("保存");
        saveBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px;");
        saveBtn.setOnAction(e -> {
            String text = valueArea.getText();
            List<String> lines = Arrays.asList(text.split("\\n"));
            List<Map<String, String>> items = new ArrayList<>();
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int spaceIdx = line.indexOf(' ');
                if (spaceIdx > 0) {
                    Map<String, String> entry = new HashMap<>();
                    entry.put("score", line.substring(0, spaceIdx));
                    entry.put("member", line.substring(spaceIdx + 1));
                    items.add(entry);
                }
            }
            saveValue("zset", items);
        });
        
        Button resetBtn = new Button("重置");
        resetBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px;");
        resetBtn.setOnAction(e -> handleKeySelection(keyTreeView.getSelectionModel().getSelectedItem()));
        
        HBox buttonBox = new HBox(10, saveBtn, resetBtn);
        valueContent.getChildren().addAll(valueLabel, valueArea, buttonBox);
    }
    
    // === HASH 类型编辑 ===
    private void showHashValue(Object value) {
        Label valueLabel = new Label("值（格式: field value，每行一个，空格分隔）：");
        valueLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        
        TextArea valueArea = new TextArea();
        valueArea.setPrefRowCount(10);
        valueArea.setStyle("-fx-font-family: monospace; -fx-font-size: 13px;");
        
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sb.append(entry.getKey()).append(" ").append(entry.getValue()).append("\n");
            }
            valueArea.setText(sb.toString().stripTrailing());
        }
        
        Button saveBtn = new Button("保存");
        saveBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px;");
        saveBtn.setOnAction(e -> {
            String text = valueArea.getText();
            List<String> lines = Arrays.asList(text.split("\\n"));
            Map<String, String> map = new LinkedHashMap<>();
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int spaceIdx = line.indexOf(' ');
                if (spaceIdx > 0) {
                    map.put(line.substring(0, spaceIdx), line.substring(spaceIdx + 1));
                }
            }
            saveValue("hash", map);
        });
        
        Button resetBtn = new Button("重置");
        resetBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px;");
        resetBtn.setOnAction(e -> handleKeySelection(keyTreeView.getSelectionModel().getSelectedItem()));
        
        HBox buttonBox = new HBox(10, saveBtn, resetBtn);
        valueContent.getChildren().addAll(valueLabel, valueArea, buttonBox);
    }
    
    // 保存值
    private void saveValue(String type, Object value) {
        if (currentKey == null) return;
        new Thread(() -> {
            try {
                RedisService.setKeyValue(config, database, currentKey, type, value);
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("保存成功");
                    alert.setHeaderText(null);
                    alert.setContentText("Key \"" + currentKey + "\" 已保存");
                    alert.showAndWait();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("保存失败");
                    alert.setHeaderText(null);
                    alert.setContentText("保存失败: " + e.getMessage());
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
            }
        }, "Redis-SaveValue").start();
    }
    
    // 搜索keys
    private void searchKeys(String pattern) {
        if (pattern.isEmpty()) pattern = "*";
        if (!pattern.contains("*")) pattern = "*" + pattern + "*";
        
        String finalPattern = pattern;
        new Thread(() -> {
            try {
                List<String> keys = RedisService.scanKeys(config, database, finalPattern);
                Platform.runLater(() -> buildKeyHierarchy(keys));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("搜索失败");
                    alert.setHeaderText(null);
                    alert.setContentText("搜索失败: " + e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "Redis-SearchKeys").start();
    }
    
    // 添加key对话框
    private void showAddKeyDialog() {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("添加Key");
        dialog.setHeaderText(null);
        
        VBox content = new VBox(10);
        content.setPadding(new Insets(10));
        
        TextField keyField = new TextField();
        keyField.setPromptText("Key名称");
        
        ComboBox<String> typeCombo = new ComboBox<>();
        typeCombo.getItems().addAll("STRING", "LIST", "SET", "ZSET", "HASH");
        typeCombo.setValue("STRING");
        
        TextArea valueArea = new TextArea();
        valueArea.setPromptText("值");
        valueArea.setPrefRowCount(5);
        
        content.getChildren().addAll(new Label("Key:"), keyField, new Label("类型:"), typeCombo, new Label("值:"), valueArea);
        
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                return keyField.getText().trim() + "|" + typeCombo.getValue() + "|" + valueArea.getText();
            }
            return null;
        });
        
        dialog.showAndWait().ifPresent(result -> {
            String[] parts = result.split("\\|", 3);
            String key = parts[0];
            String type = parts[1].toLowerCase();
            String value = parts.length > 2 ? parts[2] : "";
            
            if (key.isEmpty()) return;
            
            Object valueObj = switch (type) {
                case "string" -> value;
                case "list", "set" -> Arrays.asList(value.split("\\n"));
                case "zset" -> {
                    List<Map<String, String>> items = new ArrayList<>();
                    for (String line : value.split("\\n")) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        int spaceIdx = line.indexOf(' ');
                        if (spaceIdx > 0) {
                            Map<String, String> entry = new HashMap<>();
                            entry.put("score", line.substring(0, spaceIdx));
                            entry.put("member", line.substring(spaceIdx + 1));
                            items.add(entry);
                        }
                    }
                    yield items;
                }
                case "hash" -> {
                    Map<String, String> map = new LinkedHashMap<>();
                    for (String line : value.split("\\n")) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        int spaceIdx = line.indexOf(' ');
                        if (spaceIdx > 0) {
                            map.put(line.substring(0, spaceIdx), line.substring(spaceIdx + 1));
                        }
                    }
                    yield map;
                }
                default -> value;
            };
            
            new Thread(() -> {
                try {
                    RedisService.setKeyValue(config, database, key, type, valueObj);
                    Platform.runLater(this::loadKeyTree);
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("添加失败");
                        alert.setHeaderText(null);
                        alert.setContentText("添加Key失败: " + e.getMessage());
                        alert.showAndWait();
                    });
                }
            }, "Redis-AddKey").start();
        });
    }
    
    // 删除选中的key（支持多选批量删除）
    private void deleteSelectedKeys() {
        ObservableList<TreeItem<String>> selectedItems = keyTreeView.getSelectionModel().getSelectedItems();
        List<String> keysToDelete = new ArrayList<>();
        for (TreeItem<String> item : selectedItems) {
            if (item != null && item != keyTreeRoot) {
                String key = buildFullKey(item);
                if (key != null) keysToDelete.add(key);
            }
        }

        if (keysToDelete.isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("请先选择要删除的Key");
            alert.showAndWait();
            return;
        }

        String message = keysToDelete.size() == 1
                ? "确定要删除Key \"" + keysToDelete.get(0) + "\" 吗？"
                : "确定要删除选中的 " + keysToDelete.size() + " 个Key吗？";

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除Key");
        confirm.setHeaderText(message);
        if (keysToDelete.size() > 1 && keysToDelete.size() <= 10) {
            confirm.setContentText("将删除以下Key：\n" + String.join("\n", keysToDelete));
        } else if (keysToDelete.size() > 10) {
            confirm.setContentText("将删除 " + keysToDelete.size() + " 个Key");
        }

        DialogPositionUtil.centerOnOwner(confirm, this);
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                new Thread(() -> {
                    List<String> failedKeys = new ArrayList<>();
                    for (String key : keysToDelete) {
                        try {
                            RedisService.deleteKey(config, database, key);
                        } catch (Exception e) {
                            failedKeys.add(key + ": " + e.getMessage());
                        }
                    }
                    Platform.runLater(() -> {
                        currentKey = null;
                        currentType = null;
                        loadKeyTree();
                        showEmptyValue("");
                        if (!failedKeys.isEmpty()) {
                            Alert alert = new Alert(Alert.AlertType.WARNING);
                            alert.setTitle("部分删除失败");
                            alert.setHeaderText(null);
                            alert.setContentText("以下Key删除失败：\n" + String.join("\n", failedKeys));
                            alert.showAndWait();
                        }
                    });
                }, "Redis-DeleteKeys").start();
            }
        });
    }

    // 重命名选中的key
    private void renameSelectedKey() {
        TreeItem<String> selectedItem = keyTreeView.getSelectionModel().getSelectedItem();
        if (selectedItem == null || selectedItem == keyTreeRoot) return;

        String oldKey = buildFullKey(selectedItem);
        if (oldKey == null) return;

        TextInputDialog dialog = new TextInputDialog(oldKey);
        dialog.setTitle("重命名Key");
        dialog.setHeaderText("原Key名: " + oldKey);
        dialog.setContentText("新Key名：");
        dialog.showAndWait().ifPresent(input -> {
            String newKey = input.trim();
            if (newKey.isEmpty() || newKey.equals(oldKey)) return;

            final String finalNewKey = newKey;
            new Thread(() -> {
                try {
                    RedisService.renameKey(config, database, oldKey, finalNewKey);
                    Platform.runLater(() -> {
                        currentKey = finalNewKey;
                        loadKeyTree();
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("重命名失败");
                        alert.setHeaderText(null);
                        alert.setContentText("重命名失败: " + e.getMessage());
                        alert.showAndWait();
                    });
                }
            }, "Redis-RenameKey").start();
        });
    }
    
    // TTL设置对话框
    private void showTtlDialog() {
        if (currentKey == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("请先选择一个Key");
            alert.showAndWait();
            return;
        }
        
        TextInputDialog dialog = new TextInputDialog("-1");
        dialog.setTitle("设置TTL");
        dialog.setHeaderText("Key: " + currentKey);
        dialog.setContentText("TTL（秒，-1表示永不过期）：");
        dialog.showAndWait().ifPresent(input -> {
            try {
                long seconds = Long.parseLong(input.trim());
                new Thread(() -> {
                    try {
                        RedisService.setTtl(config, database, currentKey, seconds);
                        Platform.runLater(() -> handleKeySelection(keyTreeView.getSelectionModel().getSelectedItem()));
                    } catch (Exception e) {
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setTitle("设置失败");
                            alert.setHeaderText(null);
                            alert.setContentText("设置TTL失败: " + e.getMessage());
                            alert.showAndWait();
                        });
                    }
                }, "Redis-SetTTL").start();
            } catch (NumberFormatException e) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("提示");
                alert.setHeaderText(null);
                alert.setContentText("请输入有效的数字");
                alert.showAndWait();
            }
        });
    }
}
