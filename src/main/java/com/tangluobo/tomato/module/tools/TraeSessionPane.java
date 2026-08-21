package com.tangluobo.tomato.module.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.tangluobo.tomato.utils.DialogPositionUtil;
import com.tangluobo.tomato.utils.SqliteReader;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Trae 会话管理工具
 * 基于 Trae CN 的 User 目录（C:\Users\{用户}\AppData\Roaming\Trae CN\User）管理多账号会话。
 * - 添加当前用户：将当前 User 目录中登录的账号登记为一条会话（标记为活动）。
 * - 切换会话：将当前 User 目录重命名为 User{手机号}，再把目标 User{手机号} 重命名为 User。
 * - 若当前用户已添加（存在活动会话），则禁用"添加当前用户"按钮。
 */
public class TraeSessionPane extends VBox {

    // ==================== 数据模型 ====================

    public static class TraeSession {
        private String phone;
        private String name;
        private boolean active;

        public TraeSession(String phone, String name, boolean active) {
            this.phone = phone;
            this.name = name;
            this.active = active;
        }

        public String getPhone() { return phone; }
        public void setPhone(String phone) { this.phone = phone; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public boolean isActive() { return active; }
        public void setActive(boolean active) { this.active = active; }
    }

    // ==================== 成员变量 ====================

    private final List<TraeSession> sessions = new ArrayList<>();
    private TraeSession selectedSession;

    /** Trae CN 的 User 目录路径，如 C:\Users\xxx\AppData\Roaming\Trae CN\User */
    private String traeUserDir;

    // 会话列表 UI
    private VBox sessionListContainer;
    private ScrollPane sessionScrollPane;
    private Button addOrSwitchBtn;
    private Label addHintLabel;

    // 详情面板 UI
    private VBox detailPanel;
    private Label detailTitleLabel;
    private Label detailPhoneLabel;
    private Label detailStatusLabel;
    private Label detailDirLabel;
    private Label detailDirExistsLabel;
    private Button switchBtn;
    private Button openExplorerBtn;
    private HBox currentSelectedRow = null;

    // 历史任务 UI
    private ListView<TaskItem> taskListView;
    private Label taskCountLabel;

    // 加载取消机制：每次加载递增，后台线程完成后检查是否过期
    private final AtomicInteger taskLoadId = new AtomicInteger(0);

    // 状态标签
    private Label statusLabel;

    // 分隔条拖拽
    private double dividerStartX;
    private double dividerStartWidth;

    // 列表项拖拽排序状态
    private TraeSession draggedSession = null;

    // 持久化
    private static final String CONFIG_DIR = System.getProperty("user.home") + File.separator + ".tomata";
    private static final String CONFIG_FILE = CONFIG_DIR + File.separator + "trae_sessions.json";

    // ==================== 构造函数 ====================

    public TraeSessionPane() {
        this.traeUserDir = computeDefaultTraeUserDir();
        initializeUI();
        loadSessions();
        verifyActiveSession();
        refreshSessionList();
        updateAddButtonState();
        if (!sessions.isEmpty() && selectedSession == null) {
            // 默认选中第一个
            selectSession(sessions.get(0));
        } else {
            updateDetailPanel();
        }
    }

    // ==================== UI 初始化 ====================

    private void initializeUI() {
        setStyle("-fx-background-color: #ffffff;");
        setFillWidth(true);
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);

        // 自定义标题栏（样式与 HostsFilePane 保持一致，便于 ToolPane 移除）
        HBox titleBar = new HBox(10);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(14, 10, 14, 10));
        titleBar.setStyle("-fx-background-color: #f7f8fa; -fx-border-color: #e8e8e8; -fx-border-width: 0 0 1 0;");

        SVGPath titleIcon = new SVGPath();
        titleIcon.setContent("M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z");
        titleIcon.setFill(Color.web("#1976D2"));
        titleIcon.setScaleX(0.75);
        titleIcon.setScaleY(0.75);

        Label titleText = new Label("Trae 会话管理");
        titleText.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333;");

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);

        Label subtitleLabel = new Label("管理 Trae CN 多账号会话，一键切换 User 目录");
        subtitleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #999;");

        titleBar.getChildren().addAll(titleIcon, titleText, titleSpacer, subtitleLabel);

        // 主体内容 - HBox + Region 分隔条（与连接树/内容页样式一致）
        VBox leftPanel = createLeftPanel();
        VBox rightPanel = createRightPanel();

        Region divider = new Region();
        divider.setStyle("-fx-background-color: #E5E5E5;");
        divider.setPrefWidth(1);
        divider.setMinWidth(1);
        divider.setMaxWidth(1);
        divider.setCursor(Cursor.H_RESIZE);
        setupDivider(divider, leftPanel);

        HBox contentBox = new HBox();
        contentBox.setStyle("-fx-padding: 0; -fx-background-insets: 0; -fx-border-color: transparent; -fx-border-width: 0;");
        contentBox.setFillHeight(true);
        contentBox.setMaxHeight(Double.MAX_VALUE);
        contentBox.setMaxWidth(Double.MAX_VALUE);
        contentBox.setMinHeight(0);
        contentBox.setMinWidth(0);
        contentBox.setPadding(Insets.EMPTY);
        contentBox.setSpacing(0);
        contentBox.getChildren().addAll(leftPanel, divider, rightPanel);
        HBox.setHgrow(rightPanel, Priority.ALWAYS);
        HBox.setHgrow(leftPanel, Priority.NEVER);
        // 确保两个面板都无限制地填充高度
        leftPanel.setMaxHeight(Double.MAX_VALUE);
        leftPanel.setMinHeight(0);
        rightPanel.setMaxHeight(Double.MAX_VALUE);
        rightPanel.setMinHeight(0);

        // 状态标签：空内容时不占据空间
        statusLabel = new Label("");
        statusLabel.setPadding(new Insets(5, 10, 10, 10));
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);
        // 监听文本变化：有内容时显示，无内容时隐藏
        statusLabel.textProperty().addListener((obs, oldVal, newVal) -> {
            boolean hasText = newVal != null && !newVal.trim().isEmpty();
            statusLabel.setVisible(hasText);
            statusLabel.setManaged(hasText);
        });

        // 最外层容器：避免自身有任何内边距
        setPadding(Insets.EMPTY);
        setSpacing(0);

        getChildren().addAll(titleBar, contentBox, statusLabel);
        VBox.setVgrow(contentBox, Priority.ALWAYS);
    }

    /**
     * 创建左侧会话列表面板
     */
    private VBox createLeftPanel() {
        VBox panel = new VBox(10);
        panel.setPrefWidth(280);
        panel.setMinWidth(150);
        panel.setStyle("-fx-background-color: #ffffff; -fx-padding: 0;");

        // 标题
        Label listTitle = new Label("会话列表");
        listTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333; -fx-padding: 10 10 5 10;");

        // 添加账号按钮：将当前 User 目录重命名为 User{手机号}，登记为非活动会话
        addOrSwitchBtn = new Button("+ 添加当前账号");
        addOrSwitchBtn.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 5 12; -fx-background-radius: 4; -fx-cursor: hand;");
        addOrSwitchBtn.setMaxWidth(Double.MAX_VALUE);
        addOrSwitchBtn.setOnAction(e -> showAddDialog());

        // 提示标签
        addHintLabel = new Label("User 目录不存在，请先登录 Trae CN 账号");
        addHintLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #999; -fx-padding: 2 0 0 0;");
        addHintLabel.setWrapText(true);
        addHintLabel.setVisible(false);
        addHintLabel.setManaged(false);

        VBox btnBox = new VBox(4);
        btnBox.setPadding(new Insets(5, 10, 10, 10));
        btnBox.getChildren().addAll(addOrSwitchBtn, addHintLabel);

        // 会话列表容器
        sessionListContainer = new VBox(0);
        sessionListContainer.setPadding(new Insets(0));
        sessionListContainer.setStyle("-fx-background-color: #ffffff;");

        // 支持拖拽到列表空白区域（移动到末尾）
        sessionListContainer.setOnDragOver(e -> {
            if (draggedSession == null) {
                e.consume();
                return;
            }
            e.acceptTransferModes(TransferMode.MOVE);
            e.consume();
        });
        sessionListContainer.setOnDragDropped(e -> {
            if (draggedSession == null) {
                e.consume();
                return;
            }
            int fromIndex = sessions.indexOf(draggedSession);
            if (fromIndex != -1 && fromIndex < sessions.size() - 1) {
                sessions.remove(fromIndex);
                sessions.add(draggedSession);
                saveSessions();
                refreshSessionList();
            }
            e.setDropCompleted(true);
            draggedSession = null;
            e.consume();
        });

        sessionScrollPane = new ScrollPane(sessionListContainer);
        sessionScrollPane.setFitToWidth(true);
        sessionScrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-border-width: 0; -fx-padding: 0; -fx-background-insets: 0; -fx-border-insets: 0;");
        sessionScrollPane.getStyleClass().add("session-scroll-pane");
        sessionScrollPane.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        sessionScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sessionScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        panel.getChildren().addAll(listTitle, btnBox, sessionScrollPane);
        VBox.setVgrow(sessionScrollPane, Priority.ALWAYS);

        return panel;
    }

    /**
     * 创建右侧详情面板
     */
    private VBox createRightPanel() {
        detailPanel = new VBox(10);
        detailPanel.setStyle("-fx-background-color: #ffffff;");
        detailPanel.setPadding(new Insets(10, 10, 0, 10));
        detailPanel.setMaxHeight(Double.MAX_VALUE);
        detailPanel.setMaxWidth(Double.MAX_VALUE);
        detailPanel.setMinHeight(0);
        detailPanel.setMinWidth(0);
        detailPanel.setFillWidth(true);
        VBox.setVgrow(detailPanel, Priority.ALWAYS);

        detailTitleLabel = new Label("请选择一个会话");
        detailTitleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333;");

        detailPhoneLabel = new Label("");
        detailPhoneLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #555;");

        detailStatusLabel = new Label("");
        detailStatusLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold;");

        detailDirLabel = new Label("");
        detailDirLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #777;");
        detailDirLabel.setWrapText(true);

        detailDirExistsLabel = new Label("");
        detailDirExistsLabel.setStyle("-fx-font-size: 12px;");

        // 操作按钮区
        HBox actionBox = new HBox(10);
        actionBox.setAlignment(Pos.CENTER_LEFT);
        actionBox.setPadding(new Insets(8, 0, 0, 0));

        switchBtn = new Button("切换到此会话");
        switchBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6 16; -fx-background-radius: 4; -fx-cursor: hand;");
        switchBtn.setOnAction(e -> {
            if (selectedSession != null) switchToSession(selectedSession);
        });

        openExplorerBtn = new Button("在资源管理器中打开");
        openExplorerBtn.setStyle("-fx-background-color: #FF9800; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6 16; -fx-background-radius: 4; -fx-cursor: hand;");
        openExplorerBtn.setOnAction(e -> openSessionDirectory());

        actionBox.getChildren().addAll(switchBtn, openExplorerBtn);

        // 历史任务区
        Separator taskSeparator = new Separator();

        HBox taskHeader = new HBox(8);
        taskHeader.setAlignment(Pos.CENTER_LEFT);
        Label taskTitle = new Label("历史任务");
        taskTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");
        taskCountLabel = new Label("");
        taskCountLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #999;");
        taskHeader.getChildren().addAll(taskTitle, taskCountLabel);

        taskListView = new ListView<>();
        taskListView.setCellFactory(lv -> new TaskListCell());
        taskListView.getStyleClass().add("trae-session-task-list");
        taskListView.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        taskListView.setPlaceholder(new Label("请选择会话以查看历史任务"));
        taskListView.setMaxHeight(Double.MAX_VALUE);
        taskListView.setMaxWidth(Double.MAX_VALUE);
        taskListView.setMinHeight(0);
        taskListView.setMinWidth(0);
        taskListView.setPadding(Insets.EMPTY);

        // 用 AnchorPane 包装 ListView，强制四边对齐，不留任何缝隙
        AnchorPane listWrapper = new AnchorPane(taskListView);
        listWrapper.setPadding(Insets.EMPTY);
        listWrapper.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; -fx-border-insets: 0;");
        AnchorPane.setTopAnchor(taskListView, 0.0);
        AnchorPane.setBottomAnchor(taskListView, 0.0);
        AnchorPane.setLeftAnchor(taskListView, 0.0);
        AnchorPane.setRightAnchor(taskListView, 0.0);

        // 对非增长元素显式设置 NEVER，避免任何意外的空间分配
        VBox.setVgrow(detailTitleLabel, Priority.NEVER);
        VBox.setVgrow(detailPhoneLabel, Priority.NEVER);
        VBox.setVgrow(detailStatusLabel, Priority.NEVER);
        VBox.setVgrow(actionBox, Priority.NEVER);
        VBox.setVgrow(detailDirLabel, Priority.NEVER);
        VBox.setVgrow(detailDirExistsLabel, Priority.NEVER);
        VBox.setVgrow(taskSeparator, Priority.NEVER);
        VBox.setVgrow(taskHeader, Priority.NEVER);
        VBox.setVgrow(listWrapper, Priority.ALWAYS);

        detailPanel.getChildren().addAll(
                detailTitleLabel,
                detailPhoneLabel,
                detailStatusLabel,
                actionBox,
                new Separator(),
                detailDirLabel,
                detailDirExistsLabel,
                taskSeparator,
                taskHeader,
                listWrapper
        );

        return detailPanel;
    }

    /**
     * 设置分隔条拖拽调整左侧面板宽度
     */
    private void setupDivider(Region divider, VBox leftPanel) {
        divider.setOnMousePressed(e -> {
            dividerStartX = e.getScreenX();
            dividerStartWidth = leftPanel.getWidth();
        });
        divider.setOnMouseDragged(e -> {
            double delta = e.getScreenX() - dividerStartX;
            double newWidth = dividerStartWidth + delta;
            if (newWidth < 150) newWidth = 150;
            if (newWidth > 500) newWidth = 500;
            leftPanel.setPrefWidth(newWidth);
        });
    }

    // ==================== 会话列表渲染 ====================

    private void refreshSessionList() {
        sessionListContainer.getChildren().clear();

        if (sessions.isEmpty()) {
            Label emptyLabel = new Label("暂无会话，点击\"添加当前用户\"开始");
            emptyLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #999; -fx-padding: 20 10 20 10;");
            emptyLabel.setWrapText(true);
            sessionListContainer.getChildren().add(emptyLabel);
            currentSelectedRow = null;
            return;
        }

        for (TraeSession session : sessions) {
            VBox itemBox = createSessionItemBox(session);
            sessionListContainer.getChildren().add(itemBox);
        }
    }

    private VBox createSessionItemBox(TraeSession session) {
        VBox itemBox = new VBox(0);
        itemBox.setMaxWidth(Double.MAX_VALUE);
        itemBox.setFillWidth(true);

        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(8, 10, 8, 10));
        row.setMaxWidth(Double.MAX_VALUE);
        row.setStyle("-fx-background-color: transparent; -fx-cursor: hand;");

        // 选中样式
        if (selectedSession == session) {
            row.setStyle("-fx-background-color: #e8f4ff; -fx-cursor: hand;");
            currentSelectedRow = row;
        }

        // 左侧头像图标
        VBox iconContainer = new VBox();
        iconContainer.setAlignment(Pos.CENTER);
        iconContainer.setPrefSize(40, 40);
        iconContainer.setStyle("-fx-background-color: #e8f4ff; -fx-background-radius: 8;");
        iconContainer.setPadding(new Insets(6));

        SVGPath personIcon = new SVGPath();
        personIcon.setContent("M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z");
        personIcon.setFill(Color.web("#1976D2"));
        personIcon.setScaleX(0.85);
        personIcon.setScaleY(0.85);
        iconContainer.getChildren().add(personIcon);

        // 中间名称 + 手机号
        VBox textContainer = new VBox(2);
        textContainer.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = new Label(session.getName());
        nameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");

        Label phoneLabel = new Label(session.getPhone());
        phoneLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #999;");

        textContainer.getChildren().addAll(nameLabel, phoneLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // 活动开关（iOS 风格，参考 HostsFilePane）
        Switch activeSwitch = new Switch();
        activeSwitch.syncSelected(session.isActive());
        activeSwitch.setOnToggle(() -> {
            if (activeSwitch.isSelected()) {
                // 打开此会话：切换到它（switchToSession 内部会先把已打开的会话关闭）
                selectedSession = session;
                switchToSession(session);
            } else {
                // 关闭当前活动会话：将 User 目录重命名为 User{手机号}
                closeActiveSession(session, activeSwitch);
            }
        });

        row.getChildren().addAll(iconContainer, textContainer, spacer, activeSwitch);

        // 右键菜单
        ContextMenu contextMenu = new ContextMenu();
        MenuItem switchItem = new MenuItem("切换到此会话");
        switchItem.setOnAction(e -> switchToSession(session));
        MenuItem editItem = new MenuItem("编辑名称");
        editItem.setOnAction(e -> editSessionName(session));
        MenuItem openItem = new MenuItem("在资源管理器中打开");
        openItem.setOnAction(e -> {
            selectedSession = session;
            refreshSessionList();
            openSessionDirectory();
        });
        MenuItem deleteItem = new MenuItem("删除会话");
        deleteItem.setStyle("-fx-text-fill: #e53935;");
        deleteItem.setOnAction(e -> {
            if (showConfirm("删除确认", "确定要删除会话 \"" + session.getName() + "\" 吗？")) {
                deleteSession(session);
            }
        });
        contextMenu.getItems().addAll(switchItem, editItem, openItem, deleteItem);

        row.setOnContextMenuRequested(e -> contextMenu.show(row, e.getScreenX(), e.getScreenY()));

        // 底部分隔线
        Region separator = new Region();
        separator.setStyle("-fx-background-color: #f0f0f0; -fx-pref-height: 1px;");
        separator.setPrefHeight(1);

        itemBox.getChildren().addAll(row, separator);

        row.setOnMouseClicked(e -> selectSession(session));
        row.setOnMouseEntered(e -> {
            if (selectedSession != session) {
                row.setStyle("-fx-background-color: #f5f5f5; -fx-cursor: hand;");
            }
        });
        row.setOnMouseExited(e -> {
            if (selectedSession != session) {
                row.setStyle("-fx-background-color: transparent; -fx-cursor: hand;");
            }
        });

        // ===== 拖拽排序支持 =====
        // 在 row 上启动拖拽（Switch 控件会消费鼠标事件，不会误触发）
        row.setOnDragDetected(e -> {
            Dragboard db = row.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString(session.getPhone());
            db.setContent(content);
            draggedSession = session;
            row.setOpacity(0.4);
            e.consume();
        });

        row.setOnDragDone(e -> {
            row.setOpacity(1.0);
            draggedSession = null;
            e.consume();
        });

        // 在 itemBox 上接收拖拽（覆盖 row + separator 整个区域）
        itemBox.setOnDragOver(e -> {
            if (draggedSession == null || draggedSession == session) {
                e.consume();
                return;
            }
            e.acceptTransferModes(TransferMode.MOVE);
            row.setStyle("-fx-background-color: #d6eaff; -fx-cursor: move;");
            e.consume();
        });

        itemBox.setOnDragExited(e -> {
            if (selectedSession == session) {
                row.setStyle("-fx-background-color: #e8f4ff; -fx-cursor: hand;");
            } else {
                row.setStyle("-fx-background-color: transparent; -fx-cursor: hand;");
            }
            e.consume();
        });

        itemBox.setOnDragDropped(e -> {
            if (draggedSession == null || draggedSession == session) {
                e.consume();
                return;
            }
            int fromIndex = sessions.indexOf(draggedSession);
            int toIndex = sessions.indexOf(session);
            if (fromIndex != -1 && toIndex != -1 && fromIndex != toIndex) {
                // 根据鼠标在目标项的位置决定插入点：上半部分插入前，下半部分插入后
                double itemHeight = itemBox.getHeight();
                boolean insertAfter = itemHeight > 0 && e.getY() > itemHeight / 2;

                sessions.remove(fromIndex);
                // 移除后重新查找目标索引
                toIndex = sessions.indexOf(session);
                if (insertAfter) {
                    toIndex++;
                }
                sessions.add(toIndex, draggedSession);
                saveSessions();
                refreshSessionList();
            }
            e.setDropCompleted(true);
            draggedSession = null;
            e.consume();
        });

        return itemBox;
    }

    // ==================== 选择与详情 ====================

    private void selectSession(TraeSession session) {
        selectedSession = session;
        refreshSessionList();
        updateDetailPanel();
    }

    private void updateDetailPanel() {
        if (selectedSession == null) {
            detailTitleLabel.setText("请选择一个会话");
            detailPhoneLabel.setText("");
            detailStatusLabel.setText("");
            detailDirLabel.setText("");
            detailDirExistsLabel.setText("");
            switchBtn.setDisable(true);
            openExplorerBtn.setDisable(true);
            taskListView.getItems().clear();
            taskListView.setPlaceholder(new Label("请选择会话以查看历史任务"));
            taskCountLabel.setText("");
            return;
        }

        detailTitleLabel.setText(selectedSession.getName());
        detailPhoneLabel.setText("手机号: " + selectedSession.getPhone());

        if (selectedSession.isActive()) {
            detailStatusLabel.setText("状态: 当前活动会话");
            detailStatusLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #4CAF50;");
            switchBtn.setDisable(true);
        } else {
            detailStatusLabel.setText("状态: 未激活");
            detailStatusLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #999;");
            switchBtn.setDisable(false);
        }

        // 目录路径
        Path dir = getSessionDirectory(selectedSession);
        detailDirLabel.setText("目录: " + normalizePath(dir.toString()));
        boolean exists = Files.exists(dir);
        if (exists) {
            detailDirExistsLabel.setText("● 目录已存在");
            detailDirExistsLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #4CAF50;");
            openExplorerBtn.setDisable(false);
        } else {
            detailDirExistsLabel.setText("● 目录不存在");
            detailDirExistsLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #e53935;");
            openExplorerBtn.setDisable(true);
        }

        // 加载历史任务
        loadHistoricalTasks(selectedSession);
    }

    // ==================== 添加账号 ====================

    /**
     * 添加账号：将当前 User 目录重命名为 User{手机号}，登记为非活动会话。
     * 若手机号已存在则提示，不重复添加。
     * 切换功能在会话列表项的右键菜单和详情面板的"切换到此会话"按钮中。
     */
    private void showAddDialog() {
        Path userPath = Paths.get(traeUserDir);
        if (!Files.exists(userPath)) {
            showError("当前 User 目录不存在:\n" + normalizePath(userPath.toString())
                    + "\n请先登录 Trae CN 账号。");
            return;
        }

        TraeSession active = findActiveSession();

        Dialog<Optional<String[]>> dialog = new Dialog<>();
        dialog.setTitle("添加账号");
        dialog.setHeaderText("将当前 User 目录重命名为 User{手机号}，登记为非活动会话");
        dialog.getDialogPane().setPrefWidth(460);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 20, 10, 20));

        int row = 0;

        // 手机号（存在活动会话时预填）
        grid.add(new Label("手机号:"), 0, row);
        TextField phoneField = new TextField(active != null ? active.getPhone() : "");
        phoneField.setPromptText("请输入手机号");
        phoneField.setStyle("-fx-font-size: 13px;");
        phoneField.setPrefWidth(280);
        grid.add(phoneField, 1, row);
        row++;

        // 名称（存在活动会话时预填）
        grid.add(new Label("名称:"), 0, row);
        TextField nameField = new TextField(active != null ? active.getName() : "");
        nameField.setPromptText("请输入名称（如：工作账号）");
        nameField.setStyle("-fx-font-size: 13px;");
        grid.add(nameField, 1, row);
        row++;

        // 归档目录预览
        grid.add(new Label("归档目录:"), 0, row);
        Label archiveDirLabel = new Label();
        archiveDirLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");
        archiveDirLabel.setWrapText(true);
        Runnable updateArchivePreview = () -> {
            String phone = phoneField.getText().trim();
            Path parent = userPath.getParent();
            String preview = phone.isEmpty()
                    ? normalizePath(userPath.toString())
                    : normalizePath(parent.resolve("User" + phone).toString());
            archiveDirLabel.setText(preview);
        };
        updateArchivePreview.run();
        phoneField.textProperty().addListener((obs, o, n) -> updateArchivePreview.run());
        grid.add(archiveDirLabel, 1, row);
        row++;

        // 状态提示
        Label dirStatusLabel = new Label();
        dirStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #4CAF50;");
        Runnable updateStatus = () -> {
            String phone = phoneField.getText().trim();
            if (phone.isEmpty()) {
                dirStatusLabel.setText("● User 目录已存在，归档后将被重命名");
                dirStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #4CAF50;");
            } else {
                Path archivePath = userPath.getParent().resolve("User" + phone);
                if (Files.exists(archivePath)) {
                    dirStatusLabel.setText("● 归档目录 User" + phone + " 已存在，无法归档");
                    dirStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #e53935;");
                } else {
                    dirStatusLabel.setText("● 将把 User 目录重命名为 User" + phone);
                    dirStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #4CAF50;");
                }
            }
        };
        updateStatus.run();
        phoneField.textProperty().addListener((obs, o, n) -> updateStatus.run());
        grid.add(dirStatusLabel, 1, row);

        dialog.getDialogPane().setContent(grid);

        ButtonType okBtn = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelBtn = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(okBtn, cancelBtn);

        // 输入校验：手机号和名称非空，且归档目录不存在
        final Button okButton = (Button) dialog.getDialogPane().lookupButton(okBtn);
        okButton.setDisable(true);
        Runnable validate = () -> {
            String phone = phoneField.getText().trim();
            Path archivePath = phone.isEmpty() ? null : userPath.getParent().resolve("User" + phone);
            boolean archiveExists = archivePath != null && Files.exists(archivePath);
            boolean ok = !phone.isEmpty() && !nameField.getText().trim().isEmpty() && !archiveExists;
            okButton.setDisable(!ok);
        };
        phoneField.textProperty().addListener((obs, o, n) -> validate.run());
        nameField.textProperty().addListener((obs, o, n) -> validate.run());

        dialog.setResultConverter(btn -> {
            if (btn == okBtn) {
                String phone = phoneField.getText().trim();
                String name = nameField.getText().trim();
                if (phone.isEmpty() || name.isEmpty()) return Optional.empty();
                return Optional.of(new String[]{phone, name});
            }
            return Optional.empty();
        });

        DialogPositionUtil.centerOnOwner(dialog, this);
        dialog.showAndWait().ifPresent(result -> {
            if (result.isPresent()) {
                archiveAndAdd(result.get()[0], result.get()[1]);
            }
        });
    }

    /**
     * 归档当前 User 目录为 User{手机号}，登记为非活动会话。
     * 若该手机号已是活动会话，则将其标记为非活动并更新名称（不新建重复记录）。
     */
    private void archiveAndAdd(String phone, String name) {
        Path userPath = Paths.get(traeUserDir);
        Path parent = userPath.getParent();
        Path archivePath = parent.resolve("User" + phone);

        // 归档目录已存在则冲突
        if (Files.exists(archivePath)) {
            showError("归档目录已存在:\n" + normalizePath(archivePath.toString())
                    + "\n请先处理该目录后再归档。");
            return;
        }

        // 重命名 User → User{手机号}
        try {
            Files.move(userPath, archivePath);
        } catch (Exception e) {
            showError("归档失败：无法重命名 User 目录。\n" + e.getMessage()
                    + "\n请确认 Trae CN 已完全关闭。");
            return;
        }

        // 所有会话标记为非活动（User 目录已归档）
        for (TraeSession s : sessions) {
            s.setActive(false);
        }

        // 若该手机号已存在会话（原活动会话），更新名称；否则新建非活动会话
        TraeSession existing = findSessionByPhone(phone);
        if (existing != null) {
            existing.setName(name);
            selectedSession = existing;
        } else {
            TraeSession newSession = new TraeSession(phone, name, false);
            sessions.add(newSession);
            selectedSession = newSession;
        }

        saveSessions();
        refreshSessionList();
        updateDetailPanel();
        updateAddButtonState();
        showSuccess("已添加会话: " + name + "\n请重启 Trae CN 登录新账号。");
    }

    // ==================== 切换会话 ====================

    private void switchToSession(TraeSession target) {
        if (target.isActive()) {
            showInfo("该会话已是当前活动会话");
            return;
        }

        TraeSession currentActive = findActiveSession();
        Path userPath = Paths.get(traeUserDir);
        Path parent = userPath.getParent();

        if (parent == null) {
            showError("无效的 User 目录路径: " + traeUserDir);
            return;
        }

        Path targetDir = parent.resolve("User" + target.getPhone());

        if (!Files.exists(targetDir)) {
            showError("目标会话目录不存在:\n" + normalizePath(targetDir.toString()) + "\n请确认该会话数据未被删除。");
            return;
        }

        boolean userDirExists = Files.exists(userPath);
        boolean hasCurrentActive = currentActive != null;

        // 当前目录与活动会话不一致时，按以下策略处理：
        // 1. 有活动会话：先把现有 User 目录归档为 User{currentActive.phone}（标准切换流程）
        // 2. 无活动会话但 User 目录存在：找个空闲的 User{tmpName} 先归档（避免覆盖）
        // 3. 无活动会话且 User 目录不存在：直接激活目标
        if (userDirExists) {
            Path archivePath;
            if (hasCurrentActive) {
                archivePath = parent.resolve("User" + currentActive.getPhone());
                if (Files.exists(archivePath)) {
                    // 活动会话的备份目录也存在，说明活动会话状态其实已经不匹配
                    // 尝试找一个可用的临时归档名
                    String base = "User_archive_" + currentActive.getPhone();
                    int idx = 1;
                    archivePath = parent.resolve(base + "_" + idx);
                    while (Files.exists(archivePath)) {
                        idx++;
                        archivePath = parent.resolve(base + "_" + idx);
                    }
                }
            } else {
                // 无活动会话但存在未登记的 User 目录，自动归档
                String base = "User_unknown_archive";
                int idx = 1;
                archivePath = parent.resolve(base + "_" + idx);
                while (Files.exists(archivePath)) {
                    idx++;
                    archivePath = parent.resolve(base + "_" + idx);
                }
            }

            try {
                Files.move(userPath, archivePath);
            } catch (Exception e) {
                showError("切换失败：无法重命名当前 User 目录。\n" + e.getMessage()
                        + "\n请确认 Trae CN 已完全关闭。");
                return;
            }
        }

        // 激活目标：将 User{phone} 重命名为 User
        try {
            Files.move(targetDir, userPath);
        } catch (Exception e) {
            // 回滚：把归档的 User 目录还原
            // （此处不回滚是安全的：上面归档操作只是移动了现有未登记目录，
            //  用户下次可在资源管理器中手动恢复，不影响数据本身。）
            showError("切换失败：无法激活目标会话目录。\n" + e.getMessage()
                    + "\n请确认 Trae CN 已完全关闭。");
            return;
        }

        // 更新配置：所有会话置为非活动，目标置为活动
        for (TraeSession s : sessions) {
            s.setActive(false);
        }
        target.setActive(true);

        // 如果原活动会话被自动归档成新名字，标记它非活动（已处理）
        saveSessions();
        refreshSessionList();
        updateDetailPanel();
        updateAddButtonState();
        showSuccess("已切换到会话: " + target.getName());
    }

    // ==================== 关闭活动会话 ====================

    /**
     * 关闭当前活动会话：将 User 目录重命名为 User{手机号}，标记为非活动。
     */
    private void closeActiveSession(TraeSession session, Switch activeSwitch) {
        Path userPath = Paths.get(traeUserDir);
        Path parent = userPath.getParent();

        if (!Files.exists(userPath)) {
            activeSwitch.syncSelected(true);
            showError("User 目录不存在，无需关闭");
            return;
        }

        if (parent == null) {
            activeSwitch.syncSelected(true);
            showError("无效的 User 目录路径: " + traeUserDir);
            return;
        }

        Path archivePath = parent.resolve("User" + session.getPhone());
        if (Files.exists(archivePath)) {
            activeSwitch.syncSelected(true);
            showError("归档目录已存在:\n" + normalizePath(archivePath.toString())
                    + "\n请先处理该目录后再关闭。");
            return;
        }

        try {
            Files.move(userPath, archivePath);
        } catch (Exception e) {
            activeSwitch.syncSelected(true);
            showError("关闭会话失败：无法重命名 User 目录。\n" + e.getMessage()
                    + "\n请确认 Trae CN 已完全关闭。");
            return;
        }

        session.setActive(false);
        saveSessions();
        refreshSessionList();
        updateDetailPanel();
        updateAddButtonState();
        showSuccess("已关闭会话: " + session.getName() + "\nUser 目录已重命名为 User" + session.getPhone());
    }

    // ==================== 编辑 / 删除 ====================

    private void editSessionName(TraeSession session) {
        TextInputDialog dialog = new TextInputDialog(session.getName());
        dialog.setTitle("编辑名称");
        dialog.setHeaderText("修改会话名称");
        dialog.setContentText("名称:");

        DialogPositionUtil.centerOnOwner(dialog, this);
        dialog.showAndWait().ifPresent(name -> {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                session.setName(trimmed);
                saveSessions();
                refreshSessionList();
                updateDetailPanel();
                showSuccess("名称已更新");
            }
        });
    }

    private void deleteSession(TraeSession session) {
        Path sessionDir = getSessionDirectory(session);

        if (session.isActive()) {
            // 活动会话：仅从列表移除，User 目录保留（变为未管理状态）
            sessions.remove(session);
            if (selectedSession == session) {
                selectedSession = sessions.isEmpty() ? null : sessions.get(0);
            }
            saveSessions();
            refreshSessionList();
            updateDetailPanel();
            updateAddButtonState();
            showSuccess("已移除当前会话（User 目录已保留）");
            return;
        }

        // 非活动会话：询问是否删除数据目录
        boolean deleteDir = showConfirm("删除会话",
                "是否同时删除该会话的数据目录？\n" + normalizePath(sessionDir.toString()));
        if (deleteDir && Files.exists(sessionDir)) {
            try {
                deleteDirectoryRecursively(sessionDir);
            } catch (Exception e) {
                showError("删除目录失败: " + e.getMessage());
            }
        }
        sessions.remove(session);
        if (selectedSession == session) {
            selectedSession = sessions.isEmpty() ? null : sessions.get(0);
        }
        saveSessions();
        refreshSessionList();
        updateDetailPanel();
        showSuccess("会话已删除");
    }

    // ==================== 按钮状态 ====================

    private void updateAddButtonState() {
        boolean userDirExists = Files.exists(Paths.get(traeUserDir));

        // 添加或切换账号按钮：User 目录存在时可用
        if (userDirExists) {
            addOrSwitchBtn.setDisable(false);
            addOrSwitchBtn.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 5 12; -fx-background-radius: 4; -fx-cursor: hand;");
            addHintLabel.setVisible(false);
            addHintLabel.setManaged(false);
        } else {
            addOrSwitchBtn.setDisable(true);
            addOrSwitchBtn.setStyle("-fx-background-color: #bdbdbd; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 5 12; -fx-background-radius: 4;");
            addHintLabel.setVisible(true);
            addHintLabel.setManaged(true);
        }
    }

    // ==================== 资源管理器 ====================

    private void openSessionDirectory() {
        if (selectedSession == null) return;
        Path dir = getSessionDirectory(selectedSession);
        if (!Files.exists(dir)) {
            showError("目录不存在: " + normalizePath(dir.toString()));
            return;
        }
        try {
            // 优先使用 Windows explorer
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("explorer.exe", dir.toString().replace('/', '\\'));
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", dir.toString());
            } else {
                pb = new ProcessBuilder("xdg-open", dir.toString());
            }
            pb.start();
        } catch (Exception e) {
            showError("无法打开目录: " + e.getMessage());
        }
    }

    // ==================== 历史任务加载 ====================

    /**
     * 异步加载会话的历史任务。
     * 1. 读取会话目录下 globalStorage/storage.json，从 windowSplashWorkspaceOverride.layoutInfo.workspaces 提取 workspace key。
     * 2. 若 storage.json 中无 workspace key，则扫描 workspaceStorage 目录下所有子目录作为 fallback。
     * 3. 打开 workspaceStorage/{key}/state.vscdb (SQLite)，查询 icube-ai-agent-storage-input-history 获取用户历史输入。
     */
    private void loadHistoricalTasks(TraeSession session) {
        // 取消之前的加载请求
        int currentId = taskLoadId.incrementAndGet();

        taskListView.getItems().clear();
        taskCountLabel.setText("");
        taskListView.setPlaceholder(new Label("加载中..."));

        Thread thread = new Thread(() -> {
            List<TaskItem> tasks = new ArrayList<>();
            String workspaceFolder = "";

            try {
                Path sessionDir = getSessionDirectory(session);
                if (!Files.exists(sessionDir)) {
                    if (currentId != taskLoadId.get()) return;
                    Platform.runLater(() -> {
                        if (currentId != taskLoadId.get()) return;
                        taskListView.setPlaceholder(new Label("会话目录不存在"));
                        taskCountLabel.setText("");
                    });
                    return;
                }

                Path workspaceStorageDir = sessionDir.resolve("workspaceStorage");
                if (!Files.exists(workspaceStorageDir)) {
                    if (currentId != taskLoadId.get()) return;
                    Platform.runLater(() -> {
                        if (currentId != taskLoadId.get()) return;
                        taskListView.setPlaceholder(new Label("未找到 workspaceStorage 目录"));
                        taskCountLabel.setText("");
                    });
                    return;
                }

                // 从 storage.json 读取 workspace key
                List<String> workspaceKeys = new ArrayList<>();
                Path storageJson = sessionDir.resolve("globalStorage").resolve("storage.json");
                if (Files.exists(storageJson)) {
                    String content = Files.readString(storageJson);
                    JsonObject json = JsonParser.parseString(content).getAsJsonObject();
                    if (json.has("windowSplashWorkspaceOverride")) {
                        JsonObject override = json.getAsJsonObject("windowSplashWorkspaceOverride");
                        if (override.has("layoutInfo")) {
                            JsonObject layoutInfo = override.getAsJsonObject("layoutInfo");
                            if (layoutInfo.has("workspaces")) {
                                JsonObject workspaces = layoutInfo.getAsJsonObject("workspaces");
                                for (String key : workspaces.keySet()) {
                                    workspaceKeys.add(key);
                                }
                            }
                        }
                    }
                }

                // Fallback: 扫描 workspaceStorage 目录
                if (workspaceKeys.isEmpty()) {
                    try (Stream<Path> dirs = Files.list(workspaceStorageDir)) {
                        dirs.filter(Files::isDirectory)
                                .forEach(d -> workspaceKeys.add(d.getFileName().toString()));
                    }
                }

                // 从每个 workspace 的 state.vscdb 读取历史任务
                for (String key : workspaceKeys) {
                    if (currentId != taskLoadId.get()) return; // 已被新请求取消
                    Path dbPath = workspaceStorageDir.resolve(key).resolve("state.vscdb");
                    if (!Files.exists(dbPath)) continue;

                    // 读取 workspace.json 获取文件夹路径
                    Path workspaceJson = workspaceStorageDir.resolve(key).resolve("workspace.json");
                    if (Files.exists(workspaceJson) && workspaceFolder.isEmpty()) {
                        try {
                            String wsContent = Files.readString(workspaceJson);
                            JsonObject wsJson = JsonParser.parseString(wsContent).getAsJsonObject();
                            if (wsJson.has("folder")) {
                                workspaceFolder = wsJson.get("folder").getAsString()
                                        .replace("file:///", "")
                                        .replace("%3A", ":")
                                        .replace("/", "\\");
                            }
                        } catch (Exception ignored) {
                        }
                    }

                    tasks.addAll(readTasksFromVscdb(dbPath));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            if (currentId != taskLoadId.get()) return; // 已被新请求取消

            final List<TaskItem> finalTasks = tasks;
            final String finalWorkspaceFolder = workspaceFolder;
            Platform.runLater(() -> {
                if (currentId != taskLoadId.get()) return; // 已被新请求取消
                displayTasks(finalTasks, finalWorkspaceFolder);
            });
        });
        thread.setDaemon(true);
        thread.setName("TraeTaskLoader-" + currentId);
        thread.start();
    }

    /**
     * 从 state.vscdb 读取历史任务。
     * 使用 SqliteReader 直接解析 SQLite 文件格式，查询 ItemTable。
     */
    private List<TaskItem> readTasksFromVscdb(Path dbPath) {
        List<TaskItem> tasks = new ArrayList<>();

        try (SqliteReader reader = new SqliteReader(dbPath)) {
            // 一次读取所有条目，避免多次全表扫描
            Map<String, String> map = new HashMap<>();
            for (String[] entry : reader.getAllItemTableEntries()) {
                if (entry.length >= 2) {
                    map.put(entry[0], entry[1]);
                }
            }

            // 读取用户输入历史
            String inputHistory = map.get("icube-ai-agent-storage-input-history");
            if (inputHistory != null && !inputHistory.trim().isEmpty()) {
                JsonArray arr = JsonParser.parseString(inputHistory).getAsJsonArray();
                for (JsonElement elem : arr) {
                    if (elem.isJsonObject()) {
                        JsonObject obj = elem.getAsJsonObject();
                        if (obj.has("inputText")) {
                            String text = obj.get("inputText").getAsString();
                            tasks.add(new TaskItem(text));
                        }
                    }
                }
            }

            // 如果输入历史为空，尝试从 memento/icube-ai-agent-storage 读取会话列表
            if (tasks.isEmpty()) {
                String agentStorage = map.get("memento/icube-ai-agent-storage");
                if (agentStorage != null && !agentStorage.trim().isEmpty()) {
                    JsonObject storage = JsonParser.parseString(agentStorage).getAsJsonObject();
                    if (storage.has("list") && storage.get("list").isJsonArray()) {
                        JsonArray list = storage.getAsJsonArray("list");
                        for (JsonElement elem : list) {
                            if (elem.isJsonObject()) {
                                JsonObject obj = elem.getAsJsonObject();
                                String sessionId = obj.has("sessionId") ? obj.get("sessionId").getAsString() : "";
                                boolean isCurrent = obj.has("isCurrent") && obj.get("isCurrent").getAsBoolean();
                                if (!sessionId.isEmpty()) {
                                    tasks.add(new TaskItem("会话: " + sessionId + (isCurrent ? " (当前)" : "")));
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 反转顺序，最新在前
        Collections.reverse(tasks);
        return tasks;
    }

    /**
     * 在右侧面板显示历史任务列表。
     */
    private void displayTasks(List<TaskItem> tasks, String workspaceFolder) {
        if (tasks.isEmpty()) {
            taskListView.getItems().clear();
            taskListView.setPlaceholder(new Label("暂无历史任务"));
            taskCountLabel.setText("");
            return;
        }

        taskCountLabel.setText("(共 " + tasks.size() + " 条)");
        taskListView.getItems().setAll(tasks);
    }

    private VBox createTaskItemBox(TaskItem task, int index) {
        VBox item = new VBox(3);
        item.setPadding(new Insets(6, 8, 6, 8));
        item.setStyle("-fx-background-color: #f9f9f9; -fx-background-radius: 4;");

        HBox header = new HBox(6);
        header.setAlignment(Pos.CENTER_LEFT);

        Label indexLabel = new Label("#" + index);
        indexLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #1976D2;");

        header.getChildren().add(indexLabel);

        Label textLabel = new Label(task.text);
        textLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #333;");
        textLabel.setWrapText(true);

        item.getChildren().addAll(header, textLabel);

        // 悬停效果
        item.setOnMouseEntered(e -> item.setStyle("-fx-background-color: #e8f4ff; -fx-background-radius: 4; -fx-cursor: hand;"));
        item.setOnMouseExited(e -> item.setStyle("-fx-background-color: #f9f9f9; -fx-background-radius: 4;"));

        // 点击复制
        item.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                Clipboard clipboard = Clipboard.getSystemClipboard();
                ClipboardContent content = new ClipboardContent();
                content.putString(task.text);
                clipboard.setContent(content);
                showInfo("已复制到剪贴板");
            }
        });

        return item;
    }

    /** 历史任务数据项 */
    private static class TaskItem {
        final String text;
        TaskItem(String text) { this.text = text; }
    }

    /**
     * ListView 的单元格，虚拟化渲染历史任务项。
     * 只有可见的单元格才会创建 UI 节点，避免大量数据时卡顿。
     */
    private class TaskListCell extends ListCell<TaskItem> {
        @Override
        protected void updateItem(TaskItem task, boolean empty) {
            super.updateItem(task, empty);
            if (empty || task == null) {
                setGraphic(null);
                setText(null);
                setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0; -fx-background-insets: 0; -fx-border-insets: 0;");
            } else {
                setGraphic(createTaskItemBox(task, getIndex() + 1));
                setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0; -fx-background-insets: 0; -fx-border-insets: 0;");
            }
        }
    }

    // ==================== 辅助方法 ====================

    private TraeSession findActiveSession() {
        for (TraeSession s : sessions) {
            if (s.isActive()) return s;
        }
        return null;
    }

    private TraeSession findSessionByPhone(String phone) {
        if (phone == null) return null;
        for (TraeSession s : sessions) {
            if (phone.equals(s.getPhone())) return s;
        }
        return null;
    }

    /**
     * 获取会话对应的数据目录路径。
     * 活动会话 -> User 目录；非活动会话 -> User{手机号} 目录。
     */
    private Path getSessionDirectory(TraeSession session) {
        Path userPath = Paths.get(traeUserDir);
        if (session.isActive()) {
            return userPath;
        }
        Path parent = userPath.getParent();
        if (parent == null) return userPath;
        return parent.resolve("User" + session.getPhone());
    }

    /**
     * 将路径统一规范化为正斜杠分隔符，避免 UI 与存储中出现正反斜杠混用。
     */
    private String normalizePath(String path) {
        if (path == null) return null;
        return path.replace('\\', '/');
    }

    private String computeDefaultTraeUserDir() {
        String home = normalizePath(System.getProperty("user.home"));
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return normalizePath(home + "/AppData/Roaming/Trae CN/User");
        }
        // 非 Windows 默认路径（可能不存在，用户可自行修改）
        return home + "/.config/Trae CN/User";
    }

    private void deleteDirectoryRecursively(Path dir) throws Exception {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }

    /**
     * 加载时校验活动会话一致性：
     * 若活动会话的 User{手机号} 目录存在，说明其已被手动重命名出 User，应标记为非活动。
     */
    private void verifyActiveSession() {
        Path userPath = Paths.get(traeUserDir);
        Path parent = userPath.getParent();
        TraeSession active = findActiveSession();
        if (active != null && parent != null) {
            Path activeBackup = parent.resolve("User" + active.getPhone());
            if (Files.exists(activeBackup)) {
                // 活动会话的备份目录存在 -> 实际已不在 User 中
                active.setActive(false);
                saveSessions();
            }
        }
        // 防御：保证最多一个活动会话
        TraeSession firstActive = null;
        for (TraeSession s : sessions) {
            if (s.isActive()) {
                if (firstActive == null) {
                    firstActive = s;
                } else {
                    s.setActive(false);
                }
            }
        }
    }

    // ==================== 持久化 ====================

    private void saveSessions() {
        try {
            File dir = new File(CONFIG_DIR);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            StringBuilder json = new StringBuilder();
            json.append("{\"traeUserDir\":\"").append(escapeJson(normalizePath(traeUserDir))).append("\",\"sessions\":[");
            for (int i = 0; i < sessions.size(); i++) {
                TraeSession s = sessions.get(i);
                if (i > 0) json.append(",");
                json.append("{\"phone\":\"").append(escapeJson(s.getPhone())).append("\",");
                json.append("\"name\":\"").append(escapeJson(s.getName())).append("\",");
                json.append("\"active\":").append(s.isActive()).append("}");
            }
            json.append("]}");

            Files.writeString(Paths.get(CONFIG_FILE), json.toString());
        } catch (Exception e) {
            System.err.println("保存 Trae 会话配置失败: " + e.getMessage());
        }
    }

    private void loadSessions() {
        sessions.clear();
        try {
            File configFile = new File(CONFIG_FILE);
            if (!configFile.exists()) return;

            String content = Files.readString(configFile.toPath());
            String storedDir = extractString(content, "traeUserDir");
            if (storedDir != null && !storedDir.isEmpty()) {
                traeUserDir = normalizePath(storedDir);
            }

            int sessionsStart = content.indexOf("\"sessions\":[");
            if (sessionsStart == -1) return;
            int arrayStart = content.indexOf("[", sessionsStart);
            int arrayEnd = findMatchingBracket(content, arrayStart);
            if (arrayStart == -1 || arrayEnd == -1) return;

            String arrayStr = content.substring(arrayStart + 1, arrayEnd);
            List<String> objs = splitJsonObjects(arrayStr);
            for (String obj : objs) {
                String phone = extractString(obj, "phone");
                String name = extractString(obj, "name");
                boolean active = extractBoolean(obj, "active");
                if (phone != null) {
                    sessions.add(new TraeSession(phone, name != null ? name : "", active));
                }
            }
        } catch (Exception e) {
            System.err.println("加载 Trae 会话配置失败: " + e.getMessage());
        }
    }

    // ==================== 简易 JSON 工具 ====================

    private String extractString(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int start = json.indexOf(searchKey);
        if (start == -1) return null;
        start += searchKey.length();
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == '"' && json.charAt(end - 1) != '\\') break;
            end++;
        }
        if (end >= json.length()) return null;
        return unescapeJson(json.substring(start, end));
    }

    private boolean extractBoolean(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int start = json.indexOf(searchKey);
        if (start == -1) return false;
        start += searchKey.length();
        int end = start;
        while (end < json.length() && (Character.isLetter(json.charAt(end)) || json.charAt(end) == ' ')) {
            end++;
        }
        return "true".equalsIgnoreCase(json.substring(start, end).trim());
    }

    private int findMatchingBracket(String json, int start) {
        if (start < 0 || start >= json.length()) return -1;
        char openChar = json.charAt(start);
        char closeChar = openChar == '[' ? ']' : '}';
        int count = 0;
        boolean inString = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString) {
                if (c == openChar) count++;
                if (c == closeChar) {
                    count--;
                    if (count == 0) return i;
                }
            }
        }
        return -1;
    }

    private List<String> splitJsonObjects(String json) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            }
            if (!inString) {
                if (c == '{') {
                    if (depth == 0) start = i;
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0 && start != -1) {
                        result.add(json.substring(start, i + 1));
                        start = -1;
                    }
                }
            }
        }
        return result;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String unescapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t");
    }

    // ==================== 提示信息 ====================

    private boolean showConfirm(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        DialogPositionUtil.centerOnOwner(alert, this);
        return alert.showAndWait().filter(r -> r == ButtonType.OK).isPresent();
    }

    private void showInfo(String msg) {
        statusLabel.setText(msg);
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #1976D2;");
        autoClearStatus(msg);
    }

    private void showError(String msg) {
        statusLabel.setText(msg);
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #e53935;");
        autoClearStatus(msg);
    }

    private void showSuccess(String msg) {
        statusLabel.setText(msg);
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #388E3C;");
        autoClearStatus(msg);
    }

    private void autoClearStatus(String msg) {
        javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(Duration.millis(4000));
        pause.setOnFinished(e -> {
            if (statusLabel.getText().equals(msg)) {
                Platform.runLater(() -> {
                    statusLabel.setText("");
                    statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");
                });
            }
        });
        pause.play();
    }

    // ==================== iOS 风格开关控件 ====================

    /**
     * iOS 风格的开关控件（参考 HostsFilePane 中的 Switch）
     */
    private static class Switch extends StackPane {
        private static final double W = 38, H = 20, THUMB = 16;
        private final Region track = new Region();
        private final Circle thumb = new Circle(THUMB / 2.0);
        private boolean selected = false;
        private Runnable onToggle;

        Switch() {
            setPrefSize(W, H);
            setMinSize(W, H);
            setMaxSize(W, H);

            track.setPrefSize(W, H);
            track.setStyle("-fx-background-radius: 10;");

            thumb.setFill(Color.WHITE);
            thumb.setEffect(new DropShadow(4, 0, 1, Color.rgb(0, 0, 0, 0.25)));
            thumb.setTranslateX(-9);

            getChildren().addAll(track, thumb);
            updateVisual(false);

            disabledProperty().addListener((o, a, d) -> updateVisual(false));

            setOnMouseClicked(e -> {
                if (isDisabled()) return;
                e.consume();
                toggle();
            });
        }

        private void toggle() {
            selected = !selected;
            updateVisual(true);
            if (onToggle != null) onToggle.run();
        }

        void syncSelected(boolean s) {
            this.selected = s;
            updateVisual(false);
        }

        boolean isSelected() { return selected; }

        void setOnToggle(Runnable r) { this.onToggle = r; }

        private void updateVisual(boolean animate) {
            String bg = selected ? "#4CAF50" : "#bdbdbd";
            if (isDisabled()) bg = "#e0e0e0";
            track.setStyle("-fx-background-color: " + bg + "; -fx-background-radius: 10;");
            double tx = selected ? 9 : -9;
            if (animate) {
                Timeline tl = new Timeline(new KeyFrame(Duration.millis(150),
                        new KeyValue(thumb.translateXProperty(), tx)));
                tl.play();
            } else {
                thumb.setTranslateX(tx);
            }
        }
    }
}
