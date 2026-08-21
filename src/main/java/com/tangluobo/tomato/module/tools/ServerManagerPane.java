package com.tangluobo.tomato.module.tools;

import com.tangluobo.tomato.module.tools.server.*;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件服务器管理工具（HTTP / FTP / SMB）
 * 支持：目录共享、账号管理、启动/停止服务
 */
public class ServerManagerPane extends VBox {

    // ============ 成员变量 ============
    private final ServerType currentType;
    private final ServerConfig config;
    private FileServer currentServer;

    // UI 组件
    private Circle statusDot;
    private Label statusLabel;
    private Button startStopBtn;
    private Label listenAddrLabel;

    private TextField portField;
    private TextField bindField;
    private CheckBox anonymousCheck;
    private TextField rootDirField;

    // 目录列表
    private ObservableList<SharedDirectory> directoryList = FXCollections.observableArrayList();
    private TableView<SharedDirectory> dirTable;

    // 账号列表
    private ObservableList<ServerAccount> accountList = FXCollections.observableArrayList();
    private TableView<ServerAccount> accountTable;

    // 日志
    private TextArea logArea;

    private final SimpleBooleanProperty runningProperty = new SimpleBooleanProperty(false);

    /** 配置变更回调：每次修改目录/账号/端口等配置时触发，由外部保存到连接树 */
    private Runnable onConfigChanged;

    // ============ 构造 ============
    public ServerManagerPane() {
        this(ServerType.HTTP);
    }

    public ServerManagerPane(ServerType type) {
        this.currentType = type;
        this.config = new ServerConfig(type);
        initializeUI();
        bindEvents();
    }

    /** 设置监听端口（由外部回填配置保存的端口） */
    public void setConfigPort(int port) {
        if (port > 0 && portField != null) {
            portField.setText(String.valueOf(port));
        }
    }

    /** 返回当前服务类型 */
    public ServerType getServerType() {
        return currentType;
    }

    /** 获取当前面板的完整服务器配置（用于保存到连接树） */
    public ServerConfig getServerConfig() {
        config.setType(currentType);
        try {
            config.setPort(Integer.parseInt(portField.getText().trim()));
        } catch (NumberFormatException e) {
            config.setPort(currentType.getDefaultPort());
        }
        config.setBindAddress(bindField.getText().trim());
        config.setAnonymousAccess(anonymousCheck.isSelected());
        config.setRootDirectory(rootDirField.getText().trim());
        config.setSharedDirectories(new ArrayList<>(directoryList));
        config.setAccounts(new ArrayList<>(accountList));
        return config;
    }

    /** 从已保存的配置恢复面板状态 */
    public void loadFromServerConfig(ServerConfig saved) {
        if (saved == null) return;
        if (saved.getPort() > 0) {
            portField.setText(String.valueOf(saved.getPort()));
        }
        if (saved.getBindAddress() != null && !saved.getBindAddress().isEmpty()) {
            bindField.setText(saved.getBindAddress());
        }
        anonymousCheck.setSelected(saved.isAnonymousAccess());
        if (saved.getRootDirectory() != null) {
            rootDirField.setText(saved.getRootDirectory());
        }
        directoryList.clear();
        if (saved.getSharedDirectories() != null) {
            directoryList.addAll(saved.getSharedDirectories());
        }
        accountList.clear();
        if (saved.getAccounts() != null) {
            accountList.addAll(saved.getAccounts());
        }
    }

    /** 设置配置变更回调 */
    public void setOnConfigChanged(Runnable r) {
        this.onConfigChanged = r;
    }

    /** 通知外部配置已变更 */
    private void notifyConfigChanged() {
        if (onConfigChanged != null) {
            onConfigChanged.run();
        }
    }

    // ============ UI 初始化 ============
    private void initializeUI() {
        setStyle("-fx-background-color: #ffffff;");
        setFillWidth(true);
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);
        setPadding(new Insets(0));
        setSpacing(0);

        // ------------- 标题栏 -------------
        HBox titleBar = new HBox(10);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(14, 16, 14, 16));
        titleBar.setStyle("-fx-background-color: #f7f8fa; -fx-border-color: #e8e8e8; -fx-border-width: 0 0 1 0;");

        // 当前服务类型图标
        ImageView typeIcon = new ImageView();
        typeIcon.setFitWidth(24);
        typeIcon.setFitHeight(24);
        try {
            typeIcon.setImage(new Image(getClass().getResourceAsStream(currentType.getIconPath())));
        } catch (Exception ignored) {}

        Label titleText = new Label(currentType.getDisplayName() + " 服务器");
        titleText.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333;");

        Label typeBadge = new Label("默认端口 " + currentType.getDefaultPort());
        typeBadge.setStyle("-fx-font-size: 11px; -fx-text-fill: #888; -fx-background-color: #f0f0f0; -fx-background-radius: 4; -fx-padding: 2 8;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // 运行状态
        HBox statusBox = new HBox(6);
        statusBox.setAlignment(Pos.CENTER_RIGHT);
        statusDot = new Circle(5);
        statusDot.setFill(Color.web("#BDBDBD"));
        statusLabel = new Label("已停止");
        statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #999;");
        statusBox.getChildren().addAll(statusDot, statusLabel);

        // 启动/停止按钮
        startStopBtn = new Button("启动服务");
        startStopBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 90px; -fx-font-weight: bold;");
        startStopBtn.setCursor(Cursor.HAND);

        // 监听地址
        listenAddrLabel = new Label("");
        listenAddrLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888; -fx-font-family: Consolas, monospace;");

        titleBar.getChildren().addAll(typeIcon, titleText, typeBadge, spacer, statusBox, startStopBtn, listenAddrLabel);

        // ------------- 主体内容 -------------
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; -fx-padding: 0; -fx-border-color: transparent;");
        scrollPane.getStyleClass().add("session-scroll-pane");
        scrollPane.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());

        VBox content = new VBox(18);
        content.setPadding(new Insets(16, 18, 20, 18));
        content.setStyle("-fx-background-color: #ffffff;");

        // ---- 基本配置 ----
        Label configTitle = new Label("基本配置");
        configTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #333;");

        GridPane basicGrid = new GridPane();
        basicGrid.setHgap(12);
        basicGrid.setVgap(12);
        basicGrid.setPadding(new Insets(10, 4, 4, 4));

        basicGrid.add(new Label("监听端口："), 0, 0);
        portField = new TextField(String.valueOf(config.getPort()));
        portField.setPromptText("端口号，如 8080");
        portField.setPrefWidth(160);
        basicGrid.add(portField, 1, 0);

        basicGrid.add(new Label("绑定地址："), 2, 0);
        bindField = new TextField(config.getBindAddress());
        bindField.setPromptText("0.0.0.0 表示全部网卡");
        bindField.setPrefWidth(180);
        basicGrid.add(bindField, 3, 0);

        basicGrid.add(new Label("允许匿名："), 0, 1);
        anonymousCheck = new CheckBox("未登录用户可访问目录（只读）");
        anonymousCheck.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");
        basicGrid.add(anonymousCheck, 1, 1, 3, 1);

        basicGrid.add(new Label("根目录："), 0, 2);
        HBox rootBox = new HBox(6);
        rootBox.setAlignment(Pos.CENTER_LEFT);
        rootDirField = new TextField();
        rootDirField.setPromptText("未配置共享目录时使用的默认根目录（可选）");
        rootDirField.setPrefWidth(420);
        Button rootBrowseBtn = new Button("浏览");
        rootBrowseBtn.setStyle("-fx-font-size: 11px; -fx-padding: 4 10;");
        rootBrowseBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("选择根目录");
            String current = rootDirField.getText();
            if (current != null && !current.isEmpty()) {
                File f = new File(current);
                if (f.isDirectory()) dc.setInitialDirectory(f);
            }
            File s = dc.showDialog(this.getScene() != null ? (Stage) this.getScene().getWindow() : null);
            if (s != null) rootDirField.setText(s.getAbsolutePath());
        });
        rootBox.getChildren().addAll(rootDirField, rootBrowseBtn);
        basicGrid.add(rootBox, 1, 2, 3, 1);

        Label basicHint = new Label("提示：HTTP/FTP/SMB 的端口如被系统占用，请改用其他端口（如 HTTP:8080, FTP:2121, SMB:4445）");
        basicHint.setStyle("-fx-font-size: 10px; -fx-text-fill: #999;");
        basicHint.setWrapText(true);

        VBox basicBox = new VBox(6, configTitle, basicGrid, basicHint);

        // ---- 三个 Tab：目录管理 / 账号管理 / 日志 ----
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        tabPane.setStyle("-fx-border-color: transparent; -fx-background-insets: 0;");

        Tab dirTab = new Tab("📁 共享目录");
        dirTab.setContent(createDirectoryPanel());
        Tab accTab = new Tab("👥 账号管理");
        accTab.setContent(createAccountPanel());
        Tab logTab = new Tab("📋 服务日志");
        logTab.setContent(createLogPanel());

        tabPane.getTabs().addAll(dirTab, accTab, logTab);

        // 组装
        content.getChildren().addAll(basicBox, createSeparator(), tabPane);
        scrollPane.setContent(content);

        getChildren().addAll(titleBar, scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
    }

    /** 灰色分隔条（带文字那种不要，保持简洁） */
    private Region createSeparator() {
        Region r = new Region();
        r.setStyle("-fx-background-color: #F0F0F0; -fx-pref-height: 1px; -fx-padding: 0;");
        r.setPrefHeight(1);
        return r;
    }

    // ============ 共享目录面板 ============
    private VBox createDirectoryPanel() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(10, 4, 4, 4));

        // 工具栏
        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Button addBtn = new Button("+ 添加目录");
        addBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4; -fx-background-radius: 4; -fx-padding: 5 12; -fx-font-size: 12px;");
        addBtn.setCursor(Cursor.HAND);
        addBtn.setOnAction(e -> showDirectoryDialog(null));

        Button editBtn = new Button("编辑");
        editBtn.setStyle("-fx-padding: 5 12; -fx-font-size: 12px; -fx-border-radius: 4; -fx-background-radius: 4;");
        editBtn.setCursor(Cursor.HAND);
        editBtn.setOnAction(e -> {
            SharedDirectory sel = dirTable.getSelectionModel().getSelectedItem();
            if (sel != null) showDirectoryDialog(sel);
        });

        Button delBtn = new Button("删除");
        delBtn.setStyle("-fx-padding: 5 12; -fx-font-size: 12px; -fx-text-fill: #d32f2f; -fx-border-radius: 4; -fx-background-radius: 4;");
        delBtn.setCursor(Cursor.HAND);
        delBtn.setOnAction(e -> {
            ObservableList<SharedDirectory> sel = dirTable.getSelectionModel().getSelectedItems();
            if (sel != null && !sel.isEmpty()) {
                Alert a = new Alert(Alert.AlertType.CONFIRMATION, "确定删除选中的 " + sel.size() + " 个共享目录？", ButtonType.YES, ButtonType.NO);
                a.setHeaderText(null);
                a.initOwner(this.getScene() != null ? (Stage) this.getScene().getWindow() : null);
                a.initModality(Modality.WINDOW_MODAL);
                DialogPositionUtil.centerOnOwner(a, this);
                if (a.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
                    directoryList.removeAll(sel);
                    log("删除共享目录：" + sel.stream().map(SharedDirectory::getAlias).toList());
                    notifyConfigChanged();
                }
            }
        });

        toolbar.getChildren().addAll(addBtn, editBtn, new Label("  "), delBtn);

        // 表格
        dirTable = new TableView<>(directoryList);
        dirTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        dirTable.setEditable(false);
        dirTable.setStyle("-fx-border-color: #eee; -fx-border-radius: 4; -fx-background-color: white;");
        dirTable.setPrefHeight(240);

        TableColumn<SharedDirectory, String> aliasCol = new TableColumn<>("别名");
        aliasCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getAlias()));
        aliasCol.setPrefWidth(120);

        TableColumn<SharedDirectory, String> pathCol = new TableColumn<>("本地路径");
        pathCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getPath()));
        pathCol.setPrefWidth(340);

        TableColumn<SharedDirectory, Boolean> roCol = new TableColumn<>("只读");
        roCol.setCellValueFactory(c -> new SimpleBooleanProperty(c.getValue().isReadOnly()));
        roCol.setCellFactory(CheckBoxTableCell.forTableColumn(roCol));
        roCol.setPrefWidth(60);
        roCol.setEditable(false);

        TableColumn<SharedDirectory, String> usersCol = new TableColumn<>("允许用户");
        usersCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getAllowedUsers() == null || c.getValue().getAllowedUsers().isEmpty()
                        ? "所有用户"
                        : String.join(", ", c.getValue().getAllowedUsers())
        ));
        usersCol.setPrefWidth(160);

        dirTable.getColumns().addAll(aliasCol, pathCol, roCol, usersCol);

        dirTable.setRowFactory(tv -> {
            TableRow<SharedDirectory> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !row.isEmpty()) {
                    showDirectoryDialog(row.getItem());
                }
            });
            return row;
        });

        box.getChildren().addAll(toolbar, dirTable);
        VBox.setVgrow(dirTable, Priority.ALWAYS);
        return box;
    }

    private void showDirectoryDialog(SharedDirectory editing) {
        Stage dlg = new Stage();
        dlg.initModality(Modality.WINDOW_MODAL);
        if (this.getScene() != null) dlg.initOwner((Stage) this.getScene().getWindow());
        dlg.setTitle(editing == null ? "添加共享目录" : "编辑共享目录");
        dlg.setResizable(false);

        VBox root = new VBox(14);
        root.setPadding(new Insets(18));
        root.setMinWidth(440);

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(12);

        g.add(new Label("别名："), 0, 0);
        TextField aliasF = new TextField(editing != null ? editing.getAlias() : "");
        aliasF.setPromptText("如 files, share, public");
        aliasF.setPrefWidth(280);
        g.add(aliasF, 1, 0);

        g.add(new Label("本地路径："), 0, 1);
        HBox pb = new HBox(6);
        TextField pathF = new TextField(editing != null ? editing.getPath() : "");
        pathF.setPromptText("选择要共享的本地目录");
        pathF.setPrefWidth(220);
        Button browse = new Button("浏览");
        browse.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        browse.setOnAction(ev -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("选择目录");
            String cur = pathF.getText();
            if (cur != null && !cur.isEmpty()) { File f = new File(cur); if (f.isDirectory()) dc.setInitialDirectory(f); }
            File s = dc.showDialog(dlg);
            if (s != null) pathF.setText(s.getAbsolutePath());
        });
        pb.getChildren().addAll(pathF, browse);
        g.add(pb, 1, 1);

        g.add(new Label("权限："), 0, 2);
        CheckBox ro = new CheckBox("只读（禁止写入、删除、新建）");
        ro.setSelected(editing != null && editing.isReadOnly());
        ro.setStyle("-fx-font-size: 12px;");
        g.add(ro, 1, 2);

        g.add(new Label("允许用户："), 0, 3);
        TextField usersF = new TextField(editing != null && editing.getAllowedUsers() != null
                ? String.join(",", editing.getAllowedUsers()) : "");
        usersF.setPromptText("逗号分隔，留空表示所有账号");
        usersF.setPrefWidth(280);
        g.add(usersF, 1, 3);

        HBox btns = new HBox(10);
        btns.setAlignment(Pos.CENTER_RIGHT);
        Button cancel = new Button("取消");
        cancel.setStyle("-fx-border-radius: 4; -fx-background-radius: 4; -fx-pref-width: 80;");
        cancel.setOnAction(ev -> dlg.close());
        Button ok = new Button("确定");
        ok.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4; -fx-background-radius: 4; -fx-pref-width: 80; -fx-font-weight: bold;");
        ok.setOnAction(ev -> {
            String alias = aliasF.getText().trim();
            String path = pathF.getText().trim();
            if (alias.isEmpty() || path.isEmpty()) {
                Alert a = new Alert(Alert.AlertType.WARNING, "别名和路径都不能空", ButtonType.OK);
                DialogPositionUtil.centerOnOwner(a, this);
                a.showAndWait();
                return;
            }
            List<String> users = new ArrayList<>();
            String us = usersF.getText().trim();
            if (!us.isEmpty()) {
                for (String u : us.split("[,，]")) {
                    String u1 = u.trim();
                    if (!u1.isEmpty()) users.add(u1);
                }
            }
            if (editing == null) {
                SharedDirectory nd = new SharedDirectory(alias, path, ro.isSelected());
                nd.setAllowedUsers(users);
                directoryList.add(nd);
                log("添加共享目录：" + alias + " → " + path);
            } else {
                editing.setAlias(alias);
                editing.setPath(path);
                editing.setReadOnly(ro.isSelected());
                editing.setAllowedUsers(users);
                directoryList.remove(editing); // 触发刷新
                directoryList.add(editing);
                log("修改共享目录：" + alias);
            }
            notifyConfigChanged();
            dlg.close();
        });
        btns.getChildren().addAll(cancel, ok);

        root.getChildren().addAll(g, btns);
        dlg.setScene(new Scene(root));
        DialogPositionUtil.centerOnOwner(dlg, this);
        dlg.showAndWait();
    }

    // ============ 账号管理面板 ============
    private VBox createAccountPanel() {
        VBox box = new VBox(10);
        box.setPadding(new Insets(10, 4, 4, 4));

        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Button addBtn = new Button("+ 添加账号");
        addBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4; -fx-background-radius: 4; -fx-padding: 5 12; -fx-font-size: 12px;");
        addBtn.setCursor(Cursor.HAND);
        addBtn.setOnAction(e -> showAccountDialog(null));

        Button editBtn = new Button("编辑");
        editBtn.setStyle("-fx-padding: 5 12; -fx-font-size: 12px; -fx-border-radius: 4; -fx-background-radius: 4;");
        editBtn.setCursor(Cursor.HAND);
        editBtn.setOnAction(e -> {
            ServerAccount sel = accountTable.getSelectionModel().getSelectedItem();
            if (sel != null) showAccountDialog(sel);
        });

        Button delBtn = new Button("删除");
        delBtn.setStyle("-fx-padding: 5 12; -fx-font-size: 12px; -fx-text-fill: #d32f2f; -fx-border-radius: 4; -fx-background-radius: 4;");
        delBtn.setCursor(Cursor.HAND);
        delBtn.setOnAction(e -> {
            ObservableList<ServerAccount> sel = accountTable.getSelectionModel().getSelectedItems();
            if (sel != null && !sel.isEmpty()) {
                Alert a = new Alert(Alert.AlertType.CONFIRMATION, "确定删除选中的 " + sel.size() + " 个账号？", ButtonType.YES, ButtonType.NO);
                a.setHeaderText(null);
                a.initOwner(this.getScene() != null ? (Stage) this.getScene().getWindow() : null);
                a.initModality(Modality.WINDOW_MODAL);
                DialogPositionUtil.centerOnOwner(a, this);
                if (a.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
                    accountList.removeAll(sel);
                    log("删除账号：" + sel.stream().map(ServerAccount::getUsername).toList());
                    notifyConfigChanged();
                }
            }
        });

        toolbar.getChildren().addAll(addBtn, editBtn, new Label("  "), delBtn);

        accountTable = new TableView<>(accountList);
        accountTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        accountTable.setStyle("-fx-border-color: #eee; -fx-border-radius: 4; -fx-background-color: white;");
        accountTable.setPrefHeight(240);

        TableColumn<ServerAccount, String> nameCol = new TableColumn<>("用户名");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getUsername()));
        nameCol.setPrefWidth(140);

        TableColumn<ServerAccount, String> pwdCol = new TableColumn<>("密码");
        pwdCol.setCellValueFactory(c -> new SimpleStringProperty("●●●●●●"));
        pwdCol.setPrefWidth(100);

        TableColumn<ServerAccount, Boolean> enCol = new TableColumn<>("启用");
        enCol.setCellValueFactory(c -> new SimpleBooleanProperty(c.getValue().isEnabled()));
        enCol.setCellFactory(CheckBoxTableCell.forTableColumn(enCol));
        enCol.setPrefWidth(60);
        enCol.setEditable(false);

        TableColumn<ServerAccount, String> homeCol = new TableColumn<>("主目录");
        homeCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().getHomeDirectory() == null || c.getValue().getHomeDirectory().isEmpty()
                        ? "（默认）" : c.getValue().getHomeDirectory()));
        homeCol.setPrefWidth(320);

        accountTable.getColumns().addAll(nameCol, pwdCol, enCol, homeCol);
        accountTable.setRowFactory(tv -> {
            TableRow<ServerAccount> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !row.isEmpty()) {
                    showAccountDialog(row.getItem());
                }
            });
            return row;
        });

        Label hint = new Label("匿名访问无需账号；若勾选“允许匿名”，未登录用户可浏览目录。FTP/HTTP 均支持账号密码登录。SMB 由于 Windows 445 端口常被系统占用，可能需管理员权限启动。");
        hint.setStyle("-fx-font-size: 10px; -fx-text-fill: #999;");
        hint.setWrapText(true);

        box.getChildren().addAll(toolbar, accountTable, hint);
        VBox.setVgrow(accountTable, Priority.ALWAYS);
        return box;
    }

    private void showAccountDialog(ServerAccount editing) {
        Stage dlg = new Stage();
        dlg.initModality(Modality.WINDOW_MODAL);
        if (this.getScene() != null) dlg.initOwner((Stage) this.getScene().getWindow());
        dlg.setTitle(editing == null ? "添加账号" : "编辑账号");
        dlg.setResizable(false);

        VBox root = new VBox(14);
        root.setPadding(new Insets(18));
        root.setMinWidth(440);

        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(12);

        g.add(new Label("用户名："), 0, 0);
        TextField nameF = new TextField(editing != null ? editing.getUsername() : "");
        nameF.setPrefWidth(240);
        g.add(nameF, 1, 0);

        g.add(new Label("密码："), 0, 1);
        PasswordField pwdF = new PasswordField();
        pwdF.setText(editing != null ? editing.getPassword() : "");
        pwdF.setPrefWidth(240);
        g.add(pwdF, 1, 1);

        g.add(new Label("启用账号："), 0, 2);
        CheckBox en = new CheckBox("启用该账号（取消后无法登录）");
        en.setSelected(editing == null || editing.isEnabled());
        en.setStyle("-fx-font-size: 12px;");
        g.add(en, 1, 2);

        g.add(new Label("主目录："), 0, 3);
        HBox hb = new HBox(6);
        TextField homeF = new TextField(editing != null && editing.getHomeDirectory() != null ? editing.getHomeDirectory() : "");
        homeF.setPromptText("留空表示使用默认根目录/共享目录");
        homeF.setPrefWidth(180);
        Button br = new Button("浏览");
        br.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        br.setOnAction(ev -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("选择用户主目录");
            String cur = homeF.getText();
            if (cur != null && !cur.isEmpty()) { File f = new File(cur); if (f.isDirectory()) dc.setInitialDirectory(f); }
            File s = dc.showDialog(dlg);
            if (s != null) homeF.setText(s.getAbsolutePath());
        });
        hb.getChildren().addAll(homeF, br);
        g.add(hb, 1, 3);

        HBox btns = new HBox(10);
        btns.setAlignment(Pos.CENTER_RIGHT);
        Button cancel = new Button("取消");
        cancel.setStyle("-fx-border-radius: 4; -fx-background-radius: 4; -fx-pref-width: 80;");
        cancel.setOnAction(ev -> dlg.close());
        Button ok = new Button("确定");
        ok.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4; -fx-background-radius: 4; -fx-pref-width: 80; -fx-font-weight: bold;");
        ok.setOnAction(ev -> {
            String name = nameF.getText().trim();
            String pwd = pwdF.getText();
            if (name.isEmpty()) {
                Alert a = new Alert(Alert.AlertType.WARNING, "用户名不能为空", ButtonType.OK);
                DialogPositionUtil.centerOnOwner(a, this);
                a.showAndWait();
                return;
            }
            if (editing == null) {
                ServerAccount na = new ServerAccount(name, pwd, homeF.getText().trim());
                na.setEnabled(en.isSelected());
                accountList.add(na);
                log("添加账号：" + name);
            } else {
                editing.setUsername(name);
                editing.setPassword(pwd);
                editing.setEnabled(en.isSelected());
                editing.setHomeDirectory(homeF.getText().trim());
                accountList.remove(editing);
                accountList.add(editing);
                log("修改账号：" + name);
            }
            notifyConfigChanged();
            dlg.close();
        });
        btns.getChildren().addAll(cancel, ok);

        root.getChildren().addAll(g, btns);
        dlg.setScene(new Scene(root));
        DialogPositionUtil.centerOnOwner(dlg, this);
        dlg.showAndWait();
    }

    // ============ 日志面板 ============
    private VBox createLogPanel() {
        VBox box = new VBox(8);
        box.setPadding(new Insets(10, 4, 4, 4));

        HBox tb = new HBox(8);
        tb.setAlignment(Pos.CENTER_LEFT);
        Button clearBtn = new Button("清空日志");
        clearBtn.setStyle("-fx-padding: 5 12; -fx-font-size: 12px; -fx-border-radius: 4; -fx-background-radius: 4;");
        clearBtn.setOnAction(e -> logArea.clear());
        tb.getChildren().add(clearBtn);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setStyle("-fx-font-family: Consolas, monospace; -fx-font-size: 12px; -fx-background-color: #fafafa; -fx-border-color: #eee;");
        logArea.setPrefRowCount(14);

        box.getChildren().addAll(tb, logArea);
        VBox.setVgrow(logArea, Priority.ALWAYS);
        return box;
    }

    private void log(String msg) {
        Platform.runLater(() -> {
            String t = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            logArea.appendText("[" + t + "] " + msg + "\n");
        });
    }

    // ============ 事件绑定 ============
    private void bindEvents() {
        startStopBtn.setOnAction(e -> {
            if (runningProperty.get()) stopServer();
            else startServer();
        });

        runningProperty.addListener((obs, o, n) -> {
            if (n) {
                statusDot.setFill(Color.web("#07c160"));
                statusLabel.setText("运行中");
                statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #07c160; -fx-font-weight: bold;");
                startStopBtn.setText("停止服务");
                startStopBtn.setStyle("-fx-background-color: #f44336; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 90px; -fx-font-weight: bold;");
            } else {
                statusDot.setFill(Color.web("#BDBDBD"));
                statusLabel.setText("已停止");
                statusLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #999;");
                startStopBtn.setText("启动服务");
                startStopBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 90px; -fx-font-weight: bold;");
            }
        });

        // 端口/绑定地址/匿名/根目录 变更时自动保存
        portField.textProperty().addListener((obs, o, n) -> notifyConfigChanged());
        bindField.textProperty().addListener((obs, o, n) -> notifyConfigChanged());
        anonymousCheck.selectedProperty().addListener((obs, o, n) -> notifyConfigChanged());
        rootDirField.textProperty().addListener((obs, o, n) -> notifyConfigChanged());
    }

    // ============ 启动/停止 ============
    private void collectConfigFromUI() {
        try {
            int p = Integer.parseInt(portField.getText().trim());
            config.setPort(p);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("端口号必须是数字");
        }
        config.setBindAddress(bindField.getText().trim().isEmpty() ? "0.0.0.0" : bindField.getText().trim());
        config.setAnonymousAccess(anonymousCheck.isSelected());
        config.setRootDirectory(rootDirField.getText().trim().isEmpty() ? null : rootDirField.getText().trim());
        config.setSharedDirectories(new ArrayList<>(directoryList));
        config.setAccounts(new ArrayList<>(accountList));
    }

    private FileServer createServer() {
        return switch (currentType) {
            case HTTP -> new HttpFileServer();
            case FTP -> new FtpFileServer();
            case SMB -> new SmbFileServer();
        };
    }

    private void startServer() {
        try {
            collectConfigFromUI();
        } catch (Exception e) {
            Alert a = new Alert(Alert.AlertType.ERROR, "配置错误：" + e.getMessage(), ButtonType.OK);
            DialogPositionUtil.centerOnOwner(a, this);
            a.showAndWait();
            return;
        }

        // 验证目录
        if ((config.getSharedDirectories() == null || config.getSharedDirectories().isEmpty())
                && (config.getRootDirectory() == null || config.getRootDirectory().isEmpty())
                && (config.getAccounts() == null || config.getAccounts().stream().allMatch(a -> a.getHomeDirectory() == null || a.getHomeDirectory().isEmpty()))) {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                    "未配置共享目录、根目录或账号主目录。\nHTTP将显示欢迎页，FTP默认使用用户主目录。继续？",
                    ButtonType.YES, ButtonType.NO);
            a.setHeaderText(null);
            if (this.getScene() != null) { a.initOwner((Stage) this.getScene().getWindow()); a.initModality(Modality.WINDOW_MODAL); }
            DialogPositionUtil.centerOnOwner(a, this);
            if (a.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
        }

        try {
            currentServer = createServer();
            currentServer.start(config);
            runningProperty.set(true);
            String addr = currentServer.getListenAddress();
            listenAddrLabel.setText(" " + addr + " ");
            log("==== 启动 " + currentType.getDisplayName() + " 服务 ====");
            log("监听地址：" + addr);
            if (!config.getSharedDirectories().isEmpty()) {
                log("共享目录数：" + config.getSharedDirectories().size());
            }
            if (!config.getAccounts().isEmpty()) {
                log("账号数：" + config.getAccounts().size() + "（匿名访问：" + (config.isAnonymousAccess() ? "允许" : "禁用") + "）");
            }
            if (currentType == ServerType.SMB && config.getPort() == 445) {
                log("⚠️ SMB 445端口通常被Windows系统占用，可能需要使用其他端口或系统已有SMB服务。");
            }
        } catch (Exception e) {
            log("启动失败：" + e.getMessage());
            Alert a = new Alert(Alert.AlertType.ERROR, "启动失败：" + e.getMessage(), ButtonType.OK);
            DialogPositionUtil.centerOnOwner(a, this);
            a.showAndWait();
            runningProperty.set(false);
            listenAddrLabel.setText("");
        }
    }

    private void stopServer() {
        if (currentServer != null) {
            try {
                currentServer.stop();
                log("==== 停止服务 ====");
            } catch (Exception e) {
                log("停止异常：" + e.getMessage());
            }
            currentServer = null;
        }
        runningProperty.set(false);
        listenAddrLabel.setText("");
    }
}
