package com.tangluobo.tomato.module.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.tangluobo.tomato.module.connect.ConfigManager;
import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 端口映射工具
 * 通过 SSH 主机建立本地/远程端口转发隧道：
 * - 本地访问远程服务 (L)：本机监听 localHost:localPort -> SSH 服务器 -> 连接 remoteHost:remotePort（用于在本地访问远程服务）
 * - 远程访问本地服务 (R)：SSH 服务器监听 remoteHost:remotePort -> SSH 隧道 -> 连接本机 localHost:localPort（用于让远程访问本地服务）
 *
 * 每条映射引用一个已有 SSH 主机连接，支持添加、删除、启动、停止。
 * 持久化存储于 ~/.tomata/port-mappings.json，运行态会话不持久化（应用重启后默认停止）。
 */
public class PortMappingPane extends VBox {

    // ==================== 数据模型 ====================

    /** 端口映射配置项 */
    public static class PortMapping {
        private String id;
        private String name;
        /** 引用的 SSH 主机连接 ID */
        private String sshHostId;
        /** "L" 本地访问远程服务 / "R" 远程访问本地服务 */
        private String direction = "L";
        /** 本地绑定/转发地址，默认 127.0.0.1 */
        private String localHost = "127.0.0.1";
        private int localPort;
        /** 远程绑定/转发地址，默认 127.0.0.1 */
        private String remoteHost = "127.0.0.1";
        private int remotePort;

        public PortMapping() {
            this.id = UUID.randomUUID().toString();
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSshHostId() { return sshHostId; }
        public void setSshHostId(String sshHostId) { this.sshHostId = sshHostId; }
        public String getDirection() { return direction; }
        public void setDirection(String direction) { this.direction = direction; }
        public String getLocalHost() { return localHost; }
        public void setLocalHost(String localHost) { this.localHost = localHost; }
        public int getLocalPort() { return localPort; }
        public void setLocalPort(int localPort) { this.localPort = localPort; }
        public String getRemoteHost() { return remoteHost; }
        public void setRemoteHost(String remoteHost) { this.remoteHost = remoteHost; }
        public int getRemotePort() { return remotePort; }
        public void setRemotePort(int remotePort) { this.remotePort = remotePort; }
    }

    /** 表格行模型（包装运行态） */
    private static class MappingRow {
        final PortMapping mapping;
        final SimpleStringProperty name = new SimpleStringProperty();
        final SimpleStringProperty sshHost = new SimpleStringProperty();
        final SimpleStringProperty direction = new SimpleStringProperty();
        final SimpleStringProperty local = new SimpleStringProperty();
        final SimpleStringProperty remote = new SimpleStringProperty();
        final SimpleBooleanProperty running = new SimpleBooleanProperty(false);

        MappingRow(PortMapping m, String sshHostDisplay) {
            this.mapping = m;
            this.name.set(m.getName() != null && !m.getName().isEmpty() ? m.getName() : defaultName(m));
            this.sshHost.set(sshHostDisplay);
            this.direction.set("L".equals(m.getDirection()) ? "本地→远程" : "远程→本地");
            this.local.set(m.getLocalHost() + ":" + m.getLocalPort());
            this.remote.set(m.getRemoteHost() + ":" + m.getRemotePort());
        }

        static String defaultName(PortMapping m) {
            return ("L".equals(m.getDirection()) ? "L" : "R") + " " + m.getLocalPort() + "→" + m.getRemotePort();
        }

        void refreshSshHost(String display) {
            this.sshHost.set(display);
        }
    }

    // ==================== 持久化 ====================

    private static final String CONFIG_DIR = System.getProperty("user.home") + File.separator + ".tomata";
    private static final String CONFIG_FILE = CONFIG_DIR + File.separator + "port-mappings.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAPPING_LIST_TYPE = new TypeToken<List<PortMapping>>() {}.getType();

    // ==================== 运行态 ====================

    /** 运行态 SSH 会话，按映射 ID 索引；应用重启后不持久化 */
    private final Map<String, Session> activeSessions = new HashMap<>();

    // ==================== UI 组件 ====================

    private final ObservableList<MappingRow> rows = FXCollections.observableArrayList();
    private final TableView<MappingRow> table;
    private final Label statusLabel;
    private Button addBtn;
    private Button editBtn;
    private Button deleteBtn;
    private Button startBtn;
    private Button stopBtn;

    public PortMappingPane() {
        setStyle("-fx-background-color: #ffffff;");
        setFillWidth(true);
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);

        // ===== 标题栏（#f7f8fa，在连接树标签页中会被 ToolPane 移除） =====
        HBox titleBar = new HBox(10);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(14, 10, 14, 10));
        titleBar.setStyle("-fx-background-color: #f7f8fa; -fx-border-color: #e8e8e8; -fx-border-width: 0 0 1 0;");
        SVGPath titleIcon = new SVGPath();
        titleIcon.setContent("M3 9h6V3H3v6zm0 6h6V9H3v6zm0 6h6v-6H3v6zm8 0h6v-6h-6v6zm0-6h6V9h-6v6zm0-12v6h6V3h-6z");
        titleIcon.setFill(Color.web("#1976D2"));
        titleIcon.setScaleX(0.7);
        titleIcon.setScaleY(0.7);
        Label titleText = new Label("端口映射");
        titleText.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333;");
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        Label subtitleLabel = new Label("通过 SSH 主机建立本地/远程端口转发隧道");
        subtitleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #999;");
        titleBar.getChildren().addAll(titleIcon, titleText, titleSpacer, subtitleLabel);

        // ===== 工具栏 =====
        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8, 10, 8, 10));
        toolbar.setStyle("-fx-background-color: #fafafa; -fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0;");

        addBtn = new Button("添加");
        addBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4; -fx-cursor: hand;");
        editBtn = new Button("修改");
        editBtn.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #d0d0d0; -fx-background-radius: 4; -fx-cursor: hand;");
        deleteBtn = new Button("删除");
        deleteBtn.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #d0d0d0; -fx-background-radius: 4; -fx-cursor: hand;");
        startBtn = new Button("启动");
        startBtn.setStyle("-fx-background-color: #1976D2; -fx-text-fill: white; -fx-background-radius: 4; -fx-cursor: hand;");
        stopBtn = new Button("停止");
        stopBtn.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #d0d0d0; -fx-background-radius: 4; -fx-cursor: hand;");

        toolbar.getChildren().addAll(addBtn, editBtn, deleteBtn, startBtn, stopBtn);

        // ===== 表格 =====
        table = new TableView<>();
        table.setItems(rows);
        table.setStyle("-fx-font-size: 12px; -fx-background-color: #FFFFFF;");
        table.setFixedCellSize(30);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());

        TableColumn<MappingRow, String> nameCol = new TableColumn<>("名称");
        nameCol.setCellValueFactory(c -> c.getValue().name);
        nameCol.setPrefWidth(150);

        TableColumn<MappingRow, String> sshCol = new TableColumn<>("SSH主机");
        sshCol.setCellValueFactory(c -> c.getValue().sshHost);
        sshCol.setPrefWidth(180);

        TableColumn<MappingRow, String> dirCol = new TableColumn<>("方向");
        dirCol.setCellValueFactory(c -> c.getValue().direction);
        dirCol.setPrefWidth(110);

        TableColumn<MappingRow, String> localCol = new TableColumn<>("本地");
        localCol.setCellValueFactory(c -> c.getValue().local);
        localCol.setPrefWidth(140);

        TableColumn<MappingRow, String> remoteCol = new TableColumn<>("远程");
        remoteCol.setCellValueFactory(c -> c.getValue().remote);
        remoteCol.setPrefWidth(140);

        // 状态列：绿点 = 运行中；灰点 = 已停止
        TableColumn<MappingRow, Boolean> statusCol = new TableColumn<>("状态");
        statusCol.setPrefWidth(90);
        statusCol.setCellValueFactory(c -> c.getValue().running);
        statusCol.setCellFactory(col -> new TableCell<>() {
            private final Circle dot = new Circle(5);
            private final Label label = new Label();
            private final HBox box = new HBox(6, dot, label);
            {
                box.setAlignment(Pos.CENTER_LEFT);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            }
            @Override
            protected void updateItem(Boolean running, boolean empty) {
                super.updateItem(running, empty);
                if (empty || running == null) {
                    setGraphic(null);
                } else {
                    dot.setFill(running ? Color.valueOf("#4CAF50") : Color.valueOf("#BDBDBD"));
                    label.setText(running ? "运行中" : "已停止");
                    label.setStyle("-fx-font-size: 12px; -fx-text-fill: " + (running ? "#2e7d32" : "#999") + ";");
                    setGraphic(box);
                }
            }
        });

        // 操作列：根据状态显示启动/停止按钮
        TableColumn<MappingRow, Boolean> opCol = new TableColumn<>("操作");
        opCol.setPrefWidth(80);
        opCol.setCellValueFactory(c -> c.getValue().running);
        opCol.setCellFactory(col -> new TableCell<>() {
            private final Button toggleBtn = new Button();
            private final ChangeListener<Boolean> listener = (obs, old, val) -> updateButton();
            private MappingRow boundRow = null;
            {
                toggleBtn.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #d0d0d0; -fx-background-radius: 4; -fx-cursor: hand; -fx-font-size: 11px; -fx-padding: 3 10;");
                toggleBtn.setOnAction(e -> {
                    MappingRow row = getTableRow() != null ? getTableRow().getItem() : null;
                    if (row == null) return;
                    if (row.running.get()) {
                        stopMapping(row);
                    } else {
                        startMapping(row);
                    }
                });
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            }
            private void updateButton() {
                boolean running = boundRow != null && boundRow.running.get();
                toggleBtn.setText(running ? "停止" : "启动");
                if (running) {
                    toggleBtn.setStyle("-fx-background-color: #ff5722; -fx-text-fill: white; -fx-background-radius: 4; -fx-cursor: hand; -fx-font-size: 11px; -fx-padding: 3 10;");
                } else {
                    toggleBtn.setStyle("-fx-background-color: #1976D2; -fx-text-fill: white; -fx-background-radius: 4; -fx-cursor: hand; -fx-font-size: 11px; -fx-padding: 3 10;");
                }
            }
            @Override
            protected void updateItem(Boolean running, boolean empty) {
                if (boundRow != null) {
                    boundRow.running.removeListener(listener);
                    boundRow = null;
                }
                super.updateItem(running, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                    return;
                }
                boundRow = getTableRow().getItem();
                boundRow.running.addListener(listener);
                updateButton();
                setGraphic(toggleBtn);
            }
        });

        table.getColumns().addAll(nameCol, sshCol, dirCol, localCol, remoteCol, statusCol, opCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        // 行双击：选中并尝试启动
        table.setRowFactory(tv -> {
            TableRow<MappingRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    MappingRow r = row.getItem();
                    if (!r.running.get()) {
                        startMapping(r);
                    }
                }
            });
            return row;
        });

        VBox contentBox = new VBox(toolbar, table);
        VBox.setVgrow(table, Priority.ALWAYS);

        statusLabel = new Label("就绪");
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888; -fx-padding: 4 10; -fx-background-color: #f5f5f5;");
        statusLabel.setMaxWidth(Double.MAX_VALUE);

        getChildren().addAll(titleBar, contentBox, statusLabel);

        // 事件绑定
        addBtn.setOnAction(e -> showAddDialog());
        editBtn.setOnAction(e -> editSelected());
        deleteBtn.setOnAction(e -> deleteSelected());
        startBtn.setOnAction(e -> {
            for (MappingRow r : table.getSelectionModel().getSelectedItems()) {
                if (r != null && !r.running.get()) {
                    startMapping(r);
                }
            }
        });
        stopBtn.setOnAction(e -> {
            for (MappingRow r : table.getSelectionModel().getSelectedItems()) {
                if (r != null && r.running.get()) {
                    stopMapping(r);
                }
            }
        });

        // 加载已持久化映射
        loadMappings();
        refreshRowsView();
        updateButtonStates();

        table.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<MappingRow>) c -> updateButtonStates());
    }

    // ==================== 加载/保存 ====================

    private final List<PortMapping> mappings = new ArrayList<>();

    private void loadMappings() {
        mappings.clear();
        Path file = Paths.get(CONFIG_FILE);
        if (!Files.exists(file)) {
            return;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            List<PortMapping> loaded = GSON.fromJson(content, MAPPING_LIST_TYPE);
            if (loaded != null) {
                for (PortMapping m : loaded) {
                    if (m.getId() == null) m.setId(UUID.randomUUID().toString());
                    if (m.getDirection() == null) m.setDirection("L");
                    if (m.getLocalHost() == null) m.setLocalHost("127.0.0.1");
                    if (m.getRemoteHost() == null) m.setRemoteHost("127.0.0.1");
                    mappings.add(m);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            setStatus("加载端口映射配置失败: " + e.getMessage());
        }
    }

    private void saveMappings() {
        try {
            Files.createDirectories(Paths.get(CONFIG_DIR));
            String json = GSON.toJson(mappings);
            Path tmp = Paths.get(CONFIG_FILE + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, Paths.get(CONFIG_FILE),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            e.printStackTrace();
            setStatus("保存端口映射配置失败: " + e.getMessage());
        }
    }

    // ==================== 可用 SSH 主机 ====================

    /** 加载引用的 SSH 主机列表（SSH/SFTP 类型） */
    private List<ConnectionConfig> loadSshHosts() {
        List<ConnectionConfig> all = ConfigManager.loadConnections();
        List<ConnectionConfig> sshHosts = new ArrayList<>();
        for (ConnectionConfig c : all) {
            if (c.getType() == ConnectType.SSH || c.getType() == ConnectType.SFTP) {
                sshHosts.add(c);
            }
        }
        return sshHosts;
    }

    /** 根据 sshHostId 查找 SSH 主机配置 */
    private ConnectionConfig findSshHost(String hostId) {
        if (hostId == null) return null;
        for (ConnectionConfig c : loadSshHosts()) {
            if (hostId.equals(c.getId())) return c;
        }
        return null;
    }

    /** 显示 SSH 主机的友好名称（带 host:port） */
    private String displaySshHost(ConnectionConfig c) {
        if (c == null) return "(未知主机)";
        String name = c.getName() != null ? c.getName() : c.getHost();
        return name + " (" + c.getHost() + ":" + c.getPort() + ")";
    }

    /** 构建异常链摘要（异常类名 + message + cause 链），便于诊断根因 */
    private static String buildExceptionChain(Throwable t) {
        if (t == null) return "未知错误";
        StringBuilder sb = new StringBuilder();
        Throwable cur = t;
        int depth = 0;
        while (cur != null && depth < 5) {
            if (sb.length() > 0) sb.append(" <- ");
            String cn = cur.getClass().getSimpleName();
            String msg = cur.getMessage();
            sb.append(cn);
            if (msg != null && !msg.isEmpty()) {
                sb.append(": ").append(msg);
            }
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }

    /** 获取异常的完整堆栈字符串 */
    private static String getStackTrace(Throwable t) {
        if (t == null) return "";
        java.io.StringWriter sw = new java.io.StringWriter();
        java.io.PrintWriter pw = new java.io.PrintWriter(sw);
        t.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    private String displaySshHostById(String hostId) {
        return displaySshHost(findSshHost(hostId));
    }

    // ==================== UI 刷新 ====================

    private void refreshRowsView() {
        // 保留运行态：遍历已存在 row，若运行中则保持 running=true
        Map<String, MappingRow> oldById = new HashMap<>();
        for (MappingRow r : rows) {
            oldById.put(r.mapping.getId(), r);
        }
        rows.clear();
        for (PortMapping m : mappings) {
            MappingRow row = new MappingRow(m, displaySshHostById(m.getSshHostId()));
            if (activeSessions.containsKey(m.getId())) {
                row.running.set(true);
            }
            rows.add(row);
        }
        // 显式刷新表格，确保新行立即渲染
        table.refresh();
    }

    private void updateButtonStates() {
        List<MappingRow> selected = table.getSelectionModel().getSelectedItems();
        boolean hasSelected = !selected.isEmpty();
        boolean allRunning = hasSelected && selected.stream().allMatch(r -> r != null && r.running.get());
        boolean allStopped = hasSelected && selected.stream().allMatch(r -> r != null && !r.running.get());
        // 修改按钮：仅单选且未运行时可用
        boolean singleStopped = selected.size() == 1 && selected.stream().allMatch(r -> r != null && !r.running.get());
        editBtn.setDisable(!singleStopped);
        deleteBtn.setDisable(!allStopped);
        startBtn.setDisable(!allStopped);
        stopBtn.setDisable(!allRunning);
    }

    /** 直接设置状态文本（调用方需在 JavaFX Application Thread） */
    private void setStatusDirect(String msg) {
        statusLabel.setText(msg);
    }

    private void setStatus(String msg) {
        if (Platform.isFxApplicationThread()) {
            statusLabel.setText(msg);
        } else {
            Platform.runLater(() -> statusLabel.setText(msg));
        }
    }

    // ==================== 添加映射对话框 ====================

    private void showAddDialog() {
        showEditDialog(null);
    }

    private void showEditDialog(PortMapping existing) {
        List<ConnectionConfig> sshHosts = loadSshHosts();
        if (sshHosts.isEmpty()) {
            setStatus("没有可用的 SSH/SFTP 主机连接，请先创建");
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.WARNING,
                    "没有可用的 SSH/SFTP 主机连接。\n请先在连接树中创建一个 SSH 连接。", ButtonType.OK);
            DialogPositionUtil.centerOnOwner(alert, this);
            alert.setHeaderText("无法" + (existing == null ? "添加" : "修改") + "端口映射");
            alert.showAndWait();
            return;
        }

        final boolean isEdit = existing != null;
        // 添加/修改对话框 Stage
        Stage dialog = new Stage();
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initOwner(getScene() != null ? getScene().getWindow() : null);
        dialog.setTitle(isEdit ? "修改端口映射" : "添加端口映射");

        VBox root = new VBox(10);
        root.setPadding(new Insets(16));
        root.setStyle("-fx-background-color: #ffffff;");
        root.setPrefWidth(420);

        javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
        grid.setHgap(8);
        grid.setVgap(10);

        int row = 0;

        // SSH 主机选择
        Label sshLabel = new Label("SSH 主机:");
        ComboBox<ConnectionConfig> sshCombo = new ComboBox<>(FXCollections.observableArrayList(sshHosts));
        sshCombo.setPrefWidth(280);
        sshCombo.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(ConnectionConfig item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : displaySshHost(item));
            }
        });
        sshCombo.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(ConnectionConfig item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : displaySshHost(item));
            }
        });
        if (isEdit) {
            // 修改时预选当前 SSH 主机
            for (int i = 0; i < sshHosts.size(); i++) {
                ConnectionConfig c = sshHosts.get(i);
                if (c.getId() != null && c.getId().equals(existing.getSshHostId())) {
                    sshCombo.getSelectionModel().select(i);
                    break;
                }
            }
        }
        if (sshCombo.getSelectionModel().getSelectedItem() == null && !sshHosts.isEmpty()) {
            sshCombo.getSelectionModel().select(0);
        }
        grid.add(sshLabel, 0, row);
        grid.add(sshCombo, 1, row);
        row++;

        // 方向选择
        Label dirLabel = new Label("方向:");
        ComboBox<String> dirCombo = new ComboBox<>(FXCollections.observableArrayList(
                "本地访问远程服务 (L)", "远程访问本地服务 (R)"));
        dirCombo.getSelectionModel().select(isEdit && "R".equals(existing.getDirection()) ? 1 : 0);
        grid.add(dirLabel, 0, row);
        grid.add(dirCombo, 1, row);
        row++;

        // 名称（可选）
        Label nameLabel = new Label("名称 (可选):");
        TextField nameField = new TextField();
        nameField.setPromptText("如 MySQL跳板");
        grid.add(nameLabel, 0, row);
        grid.add(nameField, 1, row);
        row++;

        // 本地主机
        Label lhLabel = new Label("本地主机:");
        TextField lhField = new TextField("127.0.0.1");
        grid.add(lhLabel, 0, row);
        grid.add(lhField, 1, row);
        row++;

        // 本地端口
        Label lpLabel = new Label("本地端口:");
        TextField lpField = new TextField();
        lpField.setPromptText("如 13306");
        grid.add(lpLabel, 0, row);
        grid.add(lpField, 1, row);
        row++;

        // 远程主机
        Label rhLabel = new Label("远程主机:");
        TextField rhField = new TextField("127.0.0.1");
        grid.add(rhLabel, 0, row);
        grid.add(rhField, 1, row);
        row++;

        // 远程端口
        Label rpLabel = new Label("远程端口:");
        TextField rpField = new TextField();
        rpField.setPromptText("如 3306");
        grid.add(rpLabel, 0, row);
        grid.add(rpField, 1, row);
        row++;

        // 修改时预填各字段
        if (isEdit) {
            if (existing.getName() != null) nameField.setText(existing.getName());
            if (existing.getLocalHost() != null) lhField.setText(existing.getLocalHost());
            lpField.setText(String.valueOf(existing.getLocalPort()));
            if (existing.getRemoteHost() != null) rhField.setText(existing.getRemoteHost());
            rpField.setText(String.valueOf(existing.getRemotePort()));
        }

        root.getChildren().add(grid);

        // 提示文字
        Label hintLabel = new Label();
        hintLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888; -fx-padding: 4 0 0 0;");
        hintLabel.setWrapText(true);
        updateDirectionHint(hintLabel, dirCombo.getValue());
        dirCombo.valueProperty().addListener((obs, old, val) -> updateDirectionHint(hintLabel, val));
        root.getChildren().add(hintLabel);

        // 按钮
        HBox btnBox = new HBox(8);
        btnBox.setAlignment(Pos.CENTER_RIGHT);
        Button okBtn = new Button(isEdit ? "保存" : "确定");
        okBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 4; -fx-cursor: hand;");
        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #d0d0d0; -fx-background-radius: 4; -fx-cursor: hand;");
        btnBox.getChildren().addAll(cancelBtn, okBtn);
        root.getChildren().add(btnBox);

        Scene scene = new Scene(root);
        dialog.setScene(scene);
        dialog.sizeToScene();
        dialog.setResizable(false);
        DialogPositionUtil.centerOnOwner(dialog, this);

        okBtn.setOnAction(e -> {
            try {
                ConnectionConfig sshHost = sshCombo.getValue();
                if (sshHost == null) {
                    setStatusDirect("请选择 SSH 主机");
                    return;
                }
                int localPort, remotePort;
                try {
                    localPort = Integer.parseInt(lpField.getText().trim());
                    remotePort = Integer.parseInt(rpField.getText().trim());
                } catch (NumberFormatException ex) {
                    setStatusDirect("端口号必须为数字");
                    return;
                }
                if (localPort <= 0 || localPort > 65535 || remotePort <= 0 || remotePort > 65535) {
                    setStatusDirect("端口号范围 1-65535");
                    return;
                }
                // 方向：null 安全处理，优先用选中索引（0=L，1=R），避免 getValue() 未同步返回 null
                String dirValue = dirCombo.getValue();
                String dir;
                if (dirValue != null && dirValue.contains("(R)")) {
                    dir = "R";
                } else {
                    // 默认/兜底为 L（包含 getValue()==null 或选中第一项的情况）
                    dir = "L";
                }
                String lh = lhField.getText().trim();
                String rh = rhField.getText().trim();
                if (lh.isEmpty()) lh = "127.0.0.1";
                if (rh.isEmpty()) rh = "127.0.0.1";

                // 冲突检测：同一 SSH 主机 + 方向 + 端口组合不可重复（null 安全），修改时排除自身
                for (PortMapping ex : mappings) {
                    if (isEdit && ex.getId() != null && ex.getId().equals(existing.getId())) continue;
                    if (ex.getSshHostId() != null && ex.getSshHostId().equals(sshHost.getId())
                            && dir.equals(ex.getDirection())
                            && (("L".equals(dir) && ex.getLocalPort() == localPort)
                                || ("R".equals(dir) && ex.getRemotePort() == remotePort))) {
                        setStatusDirect("已存在相同 SSH 主机+方向+端口的映射");
                        return;
                    }
                }

                // 运行中的映射不允许修改（避免与活跃 SSH 会话不一致）
                boolean running = isEdit && activeSessions.containsKey(existing.getId());
                if (running) {
                    setStatusDirect("映射正在运行，请先停止再修改");
                    return;
                }

                PortMapping target = isEdit ? existing : new PortMapping();
                if (isEdit) target.setId(existing.getId());
                target.setSshHostId(sshHost.getId());
                target.setDirection(dir);
                target.setName(nameField.getText().trim());
                target.setLocalHost(lh);
                target.setLocalPort(localPort);
                target.setRemoteHost(rh);
                target.setRemotePort(remotePort);

                if (!isEdit) mappings.add(target);
                saveMappings();
                refreshRowsView();
                updateButtonStates();
                setStatusDirect((isEdit ? "已修改端口映射：" : "已添加端口映射：") + MappingRow.defaultName(target));
                dialog.close();
            } catch (Exception ex) {
                ex.printStackTrace();
                setStatusDirect((isEdit ? "修改" : "添加") + "失败: " + ex.getMessage());
                dialog.close();
            }
        });
        cancelBtn.setOnAction(e -> dialog.close());

        dialog.showAndWait();
    }

    private void updateDirectionHint(Label hintLabel, String direction) {
        boolean isLocal = direction == null || direction.contains("(L)");
        if (isLocal) {
            hintLabel.setText("L：本机监听 本地主机:本地端口 → 经 SSH → 连接远程主机:远程端口\n"
                    + "场景：本地连远程数据库/网站。如本地 4444 → 服务器 80，本机浏览器访问 localhost:4444 即可连到服务器 80\n"
                    + "远程主机填服务在服务器上监听的地址（如 127.0.0.1 或 ::1）");
        } else {
            hintLabel.setText("R：SSH 服务器监听 远程主机:远程端口 → 经隧道 → 连接本机 本地主机:本地端口\n"
                    + "场景：把本地服务暴露给远程。如本地 8080 → 服务器 9090，服务器上访问 9090 即连到本地 8080\n"
                    + "远程主机填 SSH 服务器上的绑定地址（如 127.0.0.1）；服务器端口需未被占用");
        }
    }

    // ==================== 修改映射 ====================

    private void editSelected() {
        MappingRow row = table.getSelectionModel().getSelectedItem();
        if (row == null) {
            setStatus("请先选择一条映射");
            return;
        }
        if (row.running.get()) {
            setStatus("映射正在运行，请先停止再修改");
            return;
        }
        showEditDialog(row.mapping);
    }

    // ==================== 删除映射 ====================

    private void deleteSelected() {
        List<MappingRow> selected = new ArrayList<>(table.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) return;
        // 必须先停止运行中的映射才能删除
        for (MappingRow r : selected) {
            if (r != null && r.running.get()) {
                setStatus("请先停止运行中的映射再删除");
                return;
            }
        }
        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "确定删除选中的 " + selected.size() + " 条端口映射？", ButtonType.OK, ButtonType.CANCEL);
        DialogPositionUtil.centerOnOwner(confirm, this);
        confirm.setHeaderText("删除确认");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        for (MappingRow r : selected) {
            if (r == null) continue;
            mappings.removeIf(m -> m.getId().equals(r.mapping.getId()));
        }
        saveMappings();
        refreshRowsView();
        updateButtonStates();
        setStatus("已删除 " + selected.size() + " 条映射");
    }

    // ==================== 启动/停止映射 ====================

    private void startMapping(MappingRow row) {
        if (row == null || row.running.get()) return;
        ConnectionConfig sshHost = findSshHost(row.mapping.getSshHostId());
        if (sshHost == null) {
            setStatus("找不到引用的 SSH 主机: " + row.mapping.getSshHostId());
            return;
        }
        // R 方向防误用：弹确认对话框，明确语义，避免选反导致"服务器端口被占用"失败
        if ("R".equals(row.mapping.getDirection())) {
            int rp = row.mapping.getRemotePort();
            // 常见"本地访问远程服务"端口，用户多半是想用 L 却选了 R
            boolean likelyMisuse = rp == 80 || rp == 443 || rp == 22 || rp == 3306
                    || rp == 6379 || rp == 5432 || rp == 8080 || rp == 9001
                    || rp == 27017 || rp == 1433 || rp == 1521 || rp == 8443;
            javafx.scene.control.Alert warn = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.WARNING,
                    (likelyMisuse
                            ? "⚠ 检测到 R 方向（让远程访问本地服务），但远程端口 " + rp
                              + " 通常是你要访问的远程服务端口，很可能方向选反了！\n\n"
                            : "当前为 R 方向（让远程访问本地服务）：\n\n")
                    + "R 方向会在 SSH 服务器的 远程主机:" + rp + " 上【监听】。\n"
                    + "如果服务器该端口已被服务占用，会启动失败（remote port forwarding failed）。\n\n"
                    + "你的场景如果是「在本地访问服务器的某端口」，请改用 L 方向：\n"
                    + "  - L：本机监听 本地端口 → SSH → 连接远程端口（本地访问远程服务）\n"
                    + "  - R：服务器监听 远程端口 → SSH → 连接本地端口（让远程访问本地服务）\n\n"
                    + "确认要继续用 R 方向启动吗？",
                    ButtonType.NO, ButtonType.YES);
            warn.setHeaderText(likelyMisuse ? "方向可能选反了！" : "确认 R 方向");
            warn.getDialogPane().setPrefWidth(520);
            DialogPositionUtil.centerOnOwner(warn, PortMappingPane.this);
            if (warn.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) {
                setStatus("已取消启动 R 方向映射 " + row.name.get());
                return;
            }
        }
        setStatus("正在启动映射 " + row.name.get() + " ...");
        row.refreshSshHost(displaySshHost(sshHost));
        new Thread(() -> {
            Session session = null;
            try {
                session = openSshSession(sshHost);
                synchronized (activeSessions) {
                    // 防重复启动
                    if (activeSessions.containsKey(row.mapping.getId())) {
                        setStatus("映射 " + row.name.get() + " 已在运行");
                        return;
                    }
                    applyPortForwarding(session, row.mapping);
                    activeSessions.put(row.mapping.getId(), session);
                    session = null; // 已托管给 activeSessions，避免 finally 误关闭
                }
                Platform.runLater(() -> {
                    row.running.set(true);
                    updateButtonStates();
                    setStatus("映射 " + row.name.get() + " 启动成功");
                });
            } catch (Exception e) {
                // 打印完整堆栈到 stderr 便于诊断
                e.printStackTrace();
                String detail = buildExceptionChain(e);
                String fullStack = getStackTrace(e);
                Platform.runLater(() -> {
                    setStatus("启动映射 " + row.name.get() + " 失败: " + detail);
                    updateButtonStates();
                    // 弹窗显示完整错误，用 TextArea 支持选中复制
                    javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                            javafx.scene.control.Alert.AlertType.ERROR);
                    alert.setHeaderText("启动端口映射失败：" + row.name.get());
                    alert.getDialogPane().setPrefWidth(620);
                    alert.getDialogPane().setPrefHeight(420);
                    // 自定义按钮：复制 + 关闭
                    ButtonType copyBtnType = new ButtonType("复制全部", ButtonBar.ButtonData.LEFT);
                    alert.getButtonTypes().setAll(copyBtnType, ButtonType.CLOSE);
                    // 用 TextArea 显示错误，可选中复制
                    TextArea errorArea = new TextArea(
                            "映射：" + row.name.get() + "\n\n" +
                            "错误：" + detail + "\n\n" +
                            "完整堆栈：\n" + fullStack);
                    errorArea.setEditable(false);
                    errorArea.setWrapText(true);
                    errorArea.setPrefRowCount(18);
                    errorArea.setStyle("-fx-font-family: monospace; -fx-font-size: 11px; -fx-background-color: #fafafa;");
                    alert.getDialogPane().setContent(errorArea);
                    DialogPositionUtil.centerOnOwner(alert, PortMappingPane.this);
                    // 复制按钮：把完整错误写入剪贴板
                    final String copyText = "映射：" + row.name.get() + "\n" +
                            "错误：" + detail + "\n\n" +
                            "完整堆栈：\n" + fullStack;
                    alert.setResultConverter(btn -> {
                        if (btn == copyBtnType) {
                            javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
                            javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                            content.putString(copyText);
                            clipboard.setContent(content);
                            setStatus("错误信息已复制到剪贴板");
                        }
                        return btn;
                    });
                    alert.showAndWait();
                });
            } finally {
                // 启动失败时确保 session 被关闭，避免资源泄漏
                if (session != null) {
                    try { session.disconnect(); } catch (Exception ignored) {}
                }
            }
        }, "PortMapping-Start").start();
    }

    private void stopMapping(MappingRow row) {
        if (row == null || !row.running.get()) return;
        setStatus("正在停止映射 " + row.name.get() + " ...");
        new Thread(() -> {
            Session session;
            synchronized (activeSessions) {
                session = activeSessions.remove(row.mapping.getId());
            }
            if (session == null) {
                Platform.runLater(() -> {
                    row.running.set(false);
                    updateButtonStates();
                    setStatus("映射 " + row.name.get() + " 未在运行");
                });
                return;
            }
            try {
                removePortForwarding(session, row.mapping);
            } catch (Exception ignored) {
            }
            try {
                session.disconnect();
            } catch (Exception ignored) {
            }
            Platform.runLater(() -> {
                row.running.set(false);
                updateButtonStates();
                setStatus("映射 " + row.name.get() + " 已停止");
            });
        }, "PortMapping-Stop").start();
    }

    // ==================== JSch 端口转发 ====================

    /**
     * 使用引用的 SSH 主机建立 JSch 会话。
     * 启用 keep-alive (30s 内反映连接状态)，与项目其他隧道实现保持一致。
     */
    private Session openSshSession(ConnectionConfig sshHost) throws Exception {
        JSch jsch = new JSch();
        List<String> keyPaths = sshHost.isUseKey() ? sshHost.getPrivateKeyPaths() : null;
        String password = sshHost.isUsePassword() ? sshHost.getPassword() : null;
        // 仅密钥认证时，密码作为 passphrase
        if (!sshHost.isUsePassword() && sshHost.isUseKey() && sshHost.getPassword() != null) {
            password = sshHost.getPassword();
        }
        if (keyPaths != null && !keyPaths.isEmpty()) {
            for (String keyPath : keyPaths) {
                if (keyPath != null && !keyPath.isEmpty()) {
                    if (password != null && !password.isEmpty()) {
                        jsch.addIdentity(keyPath, password);
                    } else {
                        jsch.addIdentity(keyPath);
                    }
                }
            }
        }
        Session session = jsch.getSession(sshHost.getUsername(), sshHost.getHost(), sshHost.getPort());
        if (keyPaths == null || keyPaths.isEmpty()) {
            session.setPassword(password);
        }
        session.setConfig("StrictHostKeyChecking", "no");
        // 与 SshTunnel.connect() 一致：开启 keep-alive，使 isConnected() 真实反映连接状态
        session.setServerAliveInterval(10000);
        session.setServerAliveCountMax(3);
        session.connect(30000);
        return session;
    }

    /**
     * 根据方向设置端口转发
     * - L (本地访问远程服务)：session.setPortForwardingL(localHost, localPort, remoteHost, remotePort)
     * - R (远程访问本地服务)：session.setPortForwardingR(remoteHost, remotePort, localHost, localPort)
     */
    private void applyPortForwarding(Session session, PortMapping m) throws Exception {
        if ("L".equals(m.getDirection())) {
            // 本地端口转发：本机 localHost:localPort -> remoteHost:remotePort（经 SSH 服务器）
            session.setPortForwardingL(m.getLocalHost(), m.getLocalPort(), m.getRemoteHost(), m.getRemotePort());
        } else {
            // 远程端口转发：SSH 服务器 remoteHost:remotePort -> localHost:localPort（经 SSH 隧道回本机）
            // JSch setPortForwardingR 签名: setPortForwardingR(String bindAddress, int rport, String lhost, int lport)
            session.setPortForwardingR(m.getRemoteHost(), m.getRemotePort(), m.getLocalHost(), m.getLocalPort());
        }
    }

    /** 根据方向移除端口转发 */
    private void removePortForwarding(Session session, PortMapping m) throws Exception {
        if ("L".equals(m.getDirection())) {
            session.delPortForwardingL(m.getLocalHost(), m.getLocalPort());
        } else {
            session.delPortForwardingR(m.getRemoteHost(), m.getRemotePort());
        }
    }

    // ==================== 生命周期 ====================

    /** 应用关闭时调用，停止所有运行中的映射并释放 SSH 会话 */
    public void shutdownAll() {
        synchronized (activeSessions) {
            for (Map.Entry<String, Session> entry : activeSessions.entrySet()) {
                Session s = entry.getValue();
                PortMapping m = findMappingById(entry.getKey());
                if (m != null) {
                    try { removePortForwarding(s, m); } catch (Exception ignored) {}
                }
                try { s.disconnect(); } catch (Exception ignored) {}
            }
            activeSessions.clear();
        }
    }

    private PortMapping findMappingById(String id) {
        if (id == null) return null;
        for (PortMapping m : mappings) {
            if (id.equals(m.getId())) return m;
        }
        return null;
    }
}
