package com.tangluobo.tomato.module.connect;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FTP文件浏览器面板
 * 通过原生Socket实现FTP协议，连接FTP服务器并浏览远程文件系统
 * 展示样式参考SFTPFileBrowserPane的列表视图
 */
public class FTPFileBrowserPane extends BorderPane {

    private final ConnectionConfig config;

    // FTP 客户端
    private final SimpleFTPClient ftpClient = new SimpleFTPClient();

    // 数据
    private final ObservableList<FileItem> fileData = FXCollections.observableArrayList();
    private String currentPath = "/";

    // 状态栏组件
    private Circle statusDot;
    private Label stateLabel;
    private Label connLabel;

    // 路径导航
    private HBox pathBar;
    private TextField currentPathField;
    private Button refreshBtn;
    private Button upBtn;
    private Button mkdirBtn;

    // 列表视图
    private TableView<FileItem> fileTable;

    // 选中状态
    private FileItem selectedItem = null;

    // 图标
    private Image folderIcon;
    private Image fileIcon;

    public FTPFileBrowserPane(ConnectionConfig config) {
        this.config = config;
        loadIcons();
        initializeUI();
        connectAndLoad();
    }

    private void loadIcons() {
        try { folderIcon = new Image(getClass().getResourceAsStream("/images/connect/folder.png")); } catch (Exception e) { folderIcon = null; }
        fileIcon = createFileIcon(16);
    }

    private Image createFileIcon(int size) {
        javafx.scene.layout.Pane pane = new javafx.scene.layout.Pane();
        pane.setPrefSize(size, size);

        double s = size;
        javafx.scene.shape.Rectangle body = new javafx.scene.shape.Rectangle(s * 0.15, s * 0.05, s * 0.55, s * 0.9);
        body.setFill(Color.WHITE);
        body.setStroke(Color.valueOf("#90CAF9"));
        body.setStrokeWidth(Math.max(1, size * 0.04));
        body.setArcWidth(s * 0.06);
        body.setArcHeight(s * 0.06);

        javafx.scene.shape.Polygon ear = new javafx.scene.shape.Polygon();
        ear.getPoints().addAll(s * 0.55, s * 0.05, s * 0.55, s * 0.25, s * 0.85, s * 0.25);
        ear.setFill(Color.valueOf("#E3F2FD"));
        ear.setStroke(Color.valueOf("#90CAF9"));
        ear.setStrokeWidth(Math.max(1, size * 0.04));

        javafx.scene.shape.Line line1 = new javafx.scene.shape.Line(s * 0.25, s * 0.4, s * 0.6, s * 0.4);
        line1.setStroke(Color.valueOf("#BBDEFB"));
        line1.setStrokeWidth(Math.max(1, size * 0.03));
        javafx.scene.shape.Line line2 = new javafx.scene.shape.Line(s * 0.25, s * 0.55, s * 0.55, s * 0.55);
        line2.setStroke(Color.valueOf("#BBDEFB"));
        line2.setStrokeWidth(Math.max(1, size * 0.03));
        javafx.scene.shape.Line line3 = new javafx.scene.shape.Line(s * 0.25, s * 0.7, s * 0.5, s * 0.7);
        line3.setStroke(Color.valueOf("#BBDEFB"));
        line3.setStrokeWidth(Math.max(1, size * 0.03));

        pane.getChildren().addAll(body, ear, line1, line2, line3);

        javafx.scene.SnapshotParameters sp = new javafx.scene.SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        return pane.snapshot(sp, null);
    }

    private Image getIconForItem(FileItem item) {
        if (item.isDirectory()) {
            return folderIcon;
        }
        return fileIcon;
    }

    private void initializeUI() {
        // 当前路径输入框
        currentPathField = new TextField("/");
        currentPathField.setPrefHeight(25);
        currentPathField.setMinWidth(0);
        currentPathField.setPrefWidth(0);
        currentPathField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(currentPathField, Priority.ALWAYS);
        currentPathField.setStyle("-fx-font-size: 12px; -fx-text-fill: #333; -fx-background-color: white; -fx-background-insets: 0; -fx-background-radius: 0; -fx-padding: 2 6; -fx-border-color: #E5E5E5; -fx-border-width: 1; -fx-border-insets: 0; -fx-border-radius: 0;");
        currentPathField.setTooltip(new Tooltip("点击编辑路径，回车进入目录"));
        currentPathField.setOnAction(e -> {
            String input = currentPathField.getText();
            pathBar.requestFocus();
            navigateToPath(input);
        });

        // 顶部：路径导航栏
        pathBar = new HBox(8);
        pathBar.setAlignment(Pos.CENTER_LEFT);
        pathBar.setPadding(new Insets(6, 10, 6, 10));
        pathBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #dddddd; -fx-border-width: 0 0 1 0;");

        pathBar.getChildren().add(currentPathField);

        Label sep2 = new Label("|");
        sep2.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 11px;");
        pathBar.getChildren().add(sep2);

        upBtn = new Button("↑ 上级");
        upBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        upBtn.setOnAction(e -> navigateUp());
        pathBar.getChildren().add(upBtn);

        refreshBtn = new Button("⟳ 刷新");
        refreshBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        refreshBtn.setOnAction(e -> refresh());
        pathBar.getChildren().add(refreshBtn);

        mkdirBtn = new Button("+ 新建目录");
        mkdirBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8; -fx-text-fill: #07c160; -fx-border-color: #07c160; -fx-border-radius: 4; -fx-background-radius: 4;");
        mkdirBtn.setOnAction(e -> handleCreateDirectory());
        pathBar.getChildren().add(mkdirBtn);

        setTop(pathBar);

        // 底部：状态栏
        HBox statusBar = new HBox(8);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(4, 10, 4, 10));
        statusBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #dddddd; -fx-border-width: 1 0 0 0;");

        statusDot = new Circle(5);
        statusDot.setFill(Color.GRAY);
        statusBar.getChildren().add(statusDot);

        stateLabel = new Label("连接中...");
        stateLabel.setStyle("-fx-font-size: 11px;");
        statusBar.getChildren().add(stateLabel);

        Label sep1 = new Label("|");
        sep1.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 11px;");
        statusBar.getChildren().add(sep1);

        connLabel = new Label(config.getName() + " (" + config.getUsername() + "@" + config.getHost() + ":" + config.getPort() + ")");
        connLabel.setStyle("-fx-font-size: 11px;");
        statusBar.getChildren().add(connLabel);

        setBottom(statusBar);

        // 中心区域：文件列表
        initListView();
    }

    private void initListView() {
        fileTable = new TableView<>();
        fileTable.setItems(fileData);
        fileTable.setStyle("-fx-font-size: 12px;");
        fileTable.setRowFactory(tv -> {
            TableRow<FileItem> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY && event.getClickCount() == 2 && !row.isEmpty()) {
                    FileItem item = row.getItem();
                    handleDoubleClick(item);
                } else if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY && event.getClickCount() == 1) {
                    selectedItem = row.getItem();
                } else if (event.getButton() == javafx.scene.input.MouseButton.SECONDARY && !row.isEmpty()) {
                    fileTable.getSelectionModel().select(row.getIndex());
                    selectedItem = row.getItem();
                }
            });
            return row;
        });

        // 名称列
        TableColumn<FileItem, String> nameCol = new TableColumn<>("名称");
        nameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getDisplayName()));
        nameCol.setCellFactory(col -> new TableCell<FileItem, String>() {
            @Override
            protected void updateItem(String name, boolean empty) {
                super.updateItem(name, empty);
                if (empty || name == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(name);
                    FileItem item = getTableView().getItems().get(getIndex());
                    ImageView iv = new ImageView();
                    iv.setFitWidth(16);
                    iv.setFitHeight(16);
                    iv.setImage(getIconForItem(item));
                    if (iv.getImage() != null) setGraphic(iv);
                }
            }
        });
        nameCol.setPrefWidth(300);

        // 大小列
        TableColumn<FileItem, String> sizeCol = new TableColumn<>("大小");
        sizeCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getFormattedSize()));
        sizeCol.setPrefWidth(100);

        // 修改时间列
        TableColumn<FileItem, String> modifiedCol = new TableColumn<>("修改时间");
        modifiedCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getLastModifiedDisplay()));
        modifiedCol.setPrefWidth(180);

        // 类型列
        TableColumn<FileItem, String> typeCol = new TableColumn<>("类型");
        typeCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().isDirectory() ? "目录" : "文件"));
        typeCol.setPrefWidth(80);

        fileTable.getColumns().addAll(nameCol, sizeCol, modifiedCol, typeCol);
        fileTable.setContextMenu(createContextMenu());

        VBox centerBox = new VBox(fileTable);
        VBox.setVgrow(fileTable, Priority.ALWAYS);
        setCenter(centerBox);
    }

    private ContextMenu createContextMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem openItem = new MenuItem("打开");
        openItem.setOnAction(e -> {
            FileItem selected = getSelectedItem();
            if (selected != null) handleDoubleClick(selected);
        });

        MenuItem renameItem = new MenuItem("重命名");
        renameItem.setOnAction(e -> handleRename());

        MenuItem deleteItem = new MenuItem("删除");
        deleteItem.setOnAction(e -> handleDelete());

        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> refresh());

        MenuItem mkdirItem = new MenuItem("新建目录");
        mkdirItem.setOnAction(e -> handleCreateDirectory());

        menu.getItems().addAll(openItem, renameItem, deleteItem, new SeparatorMenuItem(), refreshItem, mkdirItem);
        return menu;
    }

    private FileItem getSelectedItem() {
        if (selectedItem != null) return selectedItem;
        return fileTable.getSelectionModel().getSelectedItem();
    }

    private void connectAndLoad() {
        new Thread(() -> {
            try {
                ftpClient.connect(config.getHost(), config.getPort());
                ftpClient.login(config.getUsername(), config.getPassword());
                String home = ftpClient.pwd();
                Platform.runLater(() -> {
                    statusDot.setFill(Color.GREEN);
                    stateLabel.setText("已连接");
                    navigateTo(home);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusDot.setFill(Color.RED);
                    stateLabel.setText("连接失败");
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("连接失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法连接到 " + config.getName() + ": " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
        }, "FTP-Connect").start();
    }

    /**
     * 导航到指定路径
     */
    private void navigateTo(String path) {
        new Thread(() -> {
            try {
                if (!ftpClient.isConnected()) {
                    ftpClient.connect(config.getHost(), config.getPort());
                    ftpClient.login(config.getUsername(), config.getPassword());
                }
                ftpClient.cwd(path);
                String realPath = ftpClient.pwd();
                List<FileItem> entries = ftpClient.listFiles();
                Platform.runLater(() -> {
                    currentPath = realPath;
                    updatePathLabel();
                    fileData.clear();
                    for (FileItem entry : entries) {
                        fileData.add(entry);
                    }
                    upBtn.setDisable("/".equals(currentPath));
                    selectedItem = null;
                    stateLabel.setText(entries.size() + " 个条目");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    stateLabel.setText("错误: " + e.getMessage());
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载文件列表: " + e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "FTP-Navigate").start();
    }

    /**
     * 处理双击：目录进入
     */
    private void handleDoubleClick(FileItem item) {
        if (item == null) return;
        if (item.isDirectory()) {
            navigateTo(item.getPath());
        }
    }

    private void navigateUp() {
        if (currentPath == null || "/".equals(currentPath)) return;
        int lastSlash = currentPath.lastIndexOf('/');
        String parent = lastSlash <= 0 ? "/" : currentPath.substring(0, lastSlash);
        if (parent.isEmpty()) parent = "/";
        navigateTo(parent);
    }

    public void refresh() {
        if (currentPath == null || currentPath.isEmpty()) currentPath = "/";
        navigateTo(currentPath);
    }

    public void disconnect() {
        new Thread(() -> {
            try {
                ftpClient.disconnect();
            } catch (Exception ignored) {}
        }, "FTP-Disconnect").start();
    }

    private void updatePathLabel() {
        currentPathField.setText(currentPath != null ? currentPath : "/");
    }

    private void navigateToPath(String input) {
        if (input == null) return;
        String path = input.trim();
        if (path.isEmpty()) {
            updatePathLabel();
            return;
        }
        if (!path.startsWith("/")) {
            String base = currentPath.endsWith("/") ? currentPath : currentPath + "/";
            path = base + path;
        }
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.isEmpty()) path = "/";
        navigateTo(path);
    }

    private void handleCreateDirectory() {
        TextInputDialog dialog = new TextInputDialog("新建目录");
        dialog.setTitle("创建目录");
        dialog.setHeaderText(null);
        dialog.setContentText("目录名：");
        dialog.showAndWait().ifPresent(name -> {
            String dirName = name.trim();
            if (dirName.isEmpty()) return;
            new Thread(() -> {
                try {
                    String newPath = joinPath(currentPath, dirName);
                    ftpClient.mkd(newPath);
                    Platform.runLater(this::refresh);
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("创建失败");
                        alert.setHeaderText(null);
                        alert.setContentText("无法创建目录: " + e.getMessage());
                        alert.showAndWait();
                    });
                }
            }, "FTP-Mkdir").start();
        });
    }

    private void handleDelete() {
        FileItem selected = getSelectedItem();
        if (selected == null) return;

        String msg = "确定要删除 \"" + selected.getName() + "\" 吗？";
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除确认");
        confirm.setHeaderText(null);
        confirm.setContentText(msg);

        confirm.showAndWait().ifPresent(response -> {
            if (response != ButtonType.OK) return;
            new Thread(() -> {
                try {
                    if (selected.isDirectory()) {
                        ftpClient.rmd(selected.getPath());
                    } else {
                        ftpClient.dele(selected.getPath());
                    }
                    Platform.runLater(this::refresh);
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("删除失败");
                        alert.setHeaderText(null);
                        alert.setContentText("无法删除: " + e.getMessage());
                        alert.showAndWait();
                    });
                }
            }, "FTP-Delete").start();
        });
    }

    private void handleRename() {
        FileItem selected = getSelectedItem();
        if (selected == null) return;

        TextInputDialog dialog = new TextInputDialog(selected.getName());
        dialog.setTitle("重命名");
        dialog.setHeaderText(null);
        dialog.setContentText("新名称：");
        dialog.showAndWait().ifPresent(newName -> {
            String name = newName.trim();
            if (name.isEmpty() || name.equals(selected.getName())) return;
            new Thread(() -> {
                try {
                    String newPath = joinPath(currentPath, name);
                    ftpClient.rename(selected.getPath(), newPath);
                    Platform.runLater(this::refresh);
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("重命名失败");
                        alert.setHeaderText(null);
                        alert.setContentText("无法重命名: " + e.getMessage());
                        alert.showAndWait();
                    });
                }
            }, "FTP-Rename").start();
        });
    }

    private static String joinPath(String base, String name) {
        if (base == null || base.isEmpty() || "/".equals(base)) return "/" + name;
        if (base.endsWith("/")) return base + name;
        return base + "/" + name;
    }

    /**
     * 文件项数据模型
     */
    public static class FileItem {
        private String name;
        private String path;
        private boolean isDirectory;
        private long size;
        private long lastModified;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public boolean isDirectory() { return isDirectory; }
        public void setDirectory(boolean directory) { isDirectory = directory; }

        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }

        public long getLastModified() { return lastModified; }
        public void setLastModified(long lastModified) { this.lastModified = lastModified; }

        public String getDisplayName() { return name; }

        public String getFormattedSize() {
            if (isDirectory) return "";
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return (size / 1024) + " KB";
            if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
            return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
        }

        public String getLastModifiedDisplay() {
            if (lastModified <= 0) return "";
            try {
                return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(lastModified));
            } catch (Exception e) {
                return "";
            }
        }
    }

    /**
     * 简单 FTP 客户端实现（基于原生Socket，支持PASV模式）
     * 支持: USER, PASS, QUIT, PASV, LIST, CWD, PWD, MKD, RMD, DELE, RNFR, RNTO, TYPE I, SYST
     */
    private static class SimpleFTPClient {
        private Socket controlSocket;
        private BufferedReader controlReader;
        private Writer controlWriter;
        private volatile boolean connected = false;

        public boolean isConnected() {
            return connected && controlSocket != null && controlSocket.isConnected() && !controlSocket.isClosed();
        }

        public void connect(String host, int port) throws IOException {
            controlSocket = new Socket();
            controlSocket.connect(new InetSocketAddress(host, port), 15000);
            controlSocket.setSoTimeout(30000);
            controlReader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream(), StandardCharsets.UTF_8));
            controlWriter = new OutputStreamWriter(controlSocket.getOutputStream(), StandardCharsets.UTF_8);

            String welcome = readResponse();
            if (!welcome.startsWith("220")) {
                throw new IOException("FTP服务器拒绝连接: " + welcome);
            }
            connected = true;
        }

        public void login(String user, String pass) throws IOException {
            sendCommand("USER " + (user == null ? "anonymous" : user));
            String resp = readResponse();
            if (resp.startsWith("230")) {
                return; // 无需密码
            }
            if (!resp.startsWith("331")) {
                throw new IOException("用户名错误: " + resp);
            }
            sendCommand("PASS " + (pass == null ? "" : pass));
            String passResp = readResponse();
            if (!passResp.startsWith("230")) {
                throw new IOException("登录失败: " + passResp);
            }
            // 设置二进制传输 & UTF8
            try { sendCommand("TYPE I"); readResponse(); } catch (Exception ignored) {}
            try { sendCommand("OPTS UTF8 ON"); readResponse(); } catch (Exception ignored) {}
        }

        public String pwd() throws IOException {
            sendCommand("PWD");
            String resp = readResponse();
            // 257 "/path" is current directory
            if (!resp.startsWith("257")) {
                throw new IOException("PWD失败: " + resp);
            }
            int firstQuote = resp.indexOf('"');
            int lastQuote = resp.lastIndexOf('"');
            if (firstQuote >= 0 && lastQuote > firstQuote) {
                return resp.substring(firstQuote + 1, lastQuote);
            }
            return "/";
        }

        public void cwd(String path) throws IOException {
            sendCommand("CWD " + path);
            String resp = readResponse();
            if (!resp.startsWith("250")) {
                throw new IOException("CWD失败: " + resp);
            }
        }

        public void mkd(String path) throws IOException {
            sendCommand("MKD " + path);
            String resp = readResponse();
            if (!resp.startsWith("257")) {
                throw new IOException("MKD失败: " + resp);
            }
        }

        public void rmd(String path) throws IOException {
            sendCommand("RMD " + path);
            String resp = readResponse();
            if (!resp.startsWith("250")) {
                throw new IOException("RMD失败: " + resp);
            }
        }

        public void dele(String path) throws IOException {
            sendCommand("DELE " + path);
            String resp = readResponse();
            if (!resp.startsWith("250")) {
                throw new IOException("DELE失败: " + resp);
            }
        }

        public void rename(String fromPath, String toPath) throws IOException {
            sendCommand("RNFR " + fromPath);
            String resp1 = readResponse();
            if (!resp1.startsWith("350")) {
                throw new IOException("RNFR失败: " + resp1);
            }
            sendCommand("RNTO " + toPath);
            String resp2 = readResponse();
            if (!resp2.startsWith("250")) {
                throw new IOException("RNTO失败: " + resp2);
            }
        }

        /**
         * 列出当前目录文件
         */
        public List<FileItem> listFiles() throws IOException {
            // 进入被动模式
            InetSocketAddress dataAddr = enterPassiveMode();
            // 发送LIST命令前先读取150响应，然后建立数据连接
            sendCommand("LIST");

            String resp150 = readResponse();
            if (!resp150.startsWith("150") && !resp150.startsWith("125")) {
                throw new IOException("LIST失败: " + resp150);
            }

            Socket dataSocket = new Socket();
            dataSocket.connect(dataAddr, 15000);
            dataSocket.setSoTimeout(30000);

            List<String> lines = new ArrayList<>();
            try (BufferedReader dataReader = new BufferedReader(new InputStreamReader(dataSocket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = dataReader.readLine()) != null) {
                    if (!line.isEmpty()) lines.add(line);
                }
            } finally {
                try { dataSocket.close(); } catch (Exception ignored) {}
            }

            // 读取226完成响应
            readResponse();

            return parseListLines(lines);
        }

        private InetSocketAddress enterPassiveMode() throws IOException {
            sendCommand("PASV");
            String resp = readResponse();
            if (!resp.startsWith("227")) {
                throw new IOException("PASV失败: " + resp);
            }
            // 227 Entering Passive Mode (h1,h2,h3,h4,p1,p2)
            int start = resp.indexOf('(');
            int end = resp.indexOf(')');
            if (start < 0 || end <= start) {
                throw new IOException("PASV响应格式错误: " + resp);
            }
            String[] parts = resp.substring(start + 1, end).split(",");
            if (parts.length != 6) {
                throw new IOException("PASV响应格式错误: " + resp);
            }
            String host = parts[0].trim() + "." + parts[1].trim() + "." + parts[2].trim() + "." + parts[3].trim();
            int port = (Integer.parseInt(parts[4].trim()) << 8) | Integer.parseInt(parts[5].trim());
            return new InetSocketAddress(host, port);
        }

        private List<FileItem> parseListLines(List<String> lines) {
            List<FileItem> items = new ArrayList<>();
            String currentDir;
            try {
                currentDir = pwd();
            } catch (Exception e) {
                currentDir = "/";
            }

            for (String line : lines) {
                FileItem item = parseListLine(line, currentDir);
                if (item != null) {
                    items.add(item);
                }
            }
            // 目录排前面
            items.sort((a, b) -> {
                if (a.isDirectory != b.isDirectory) return a.isDirectory ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            });
            return items;
        }

        /**
         * 解析Unix风格 LIST 行：drwxr-xr-x 2 user group 4096 Jan 1 12:00 name
         * 也兼容Windows风格
         */
        private FileItem parseListLine(String line, String currentDir) {
            if (line == null || line.isEmpty()) return null;
            line = line.trim();

            // Unix风格：以 d 或 - 开头
            if (line.length() > 10 && (line.charAt(0) == 'd' || line.charAt(0) == '-' || line.charAt(0) == 'l')) {
                return parseUnixListLine(line, currentDir);
            }
            // Windows风格：MM-DD-YY  HH:mmaM  <DIR>  name  或  MM-DD-YY  HH:mmaM  size  name
            return parseWindowsListLine(line, currentDir);
        }

        private FileItem parseUnixListLine(String line, String currentDir) {
            // 权限 链接数 所有者 组 大小 日期 时间 名称
            // drwxr-xr-x 2 user group 4096 Jan 1 12:00 dirname
            Pattern p = Pattern.compile(
                    "^([dl-][rwxst-]{9})\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+" +
                    "(\\w{3}\\s+\\d{1,2}\\s+\\d{1,2}:\\d{2}|\\w{3}\\s+\\d{1,2}\\s+\\d{4})\\s+(.*)$"
            );
            Matcher m = p.matcher(line);
            if (!m.matches()) {
                // 简化匹配：只取权限和名称
                String[] tokens = line.split("\\s+", 2);
                if (tokens.length < 2) return null;
                boolean isDir = tokens[0].startsWith("d");
                String name = line.substring(line.lastIndexOf(' ') + 1).trim();
                if (name.isEmpty() || name.equals(".") || name.equals("..")) return null;
                FileItem item = new FileItem();
                item.setName(name);
                item.setPath(joinPath(currentDir, name));
                item.setDirectory(isDir);
                item.setSize(0);
                item.setLastModified(0);
                return item;
            }

            String perm = m.group(1);
            long size = 0;
            try { size = Long.parseLong(m.group(2)); } catch (Exception ignored) {}
            String dateStr = m.group(3);
            String name = m.group(4).trim();

            // 软链接：name -> target
            int arrow = name.indexOf(" -> ");
            if (arrow > 0) {
                name = name.substring(0, arrow);
            }

            if (name.isEmpty() || name.equals(".") || name.equals("..")) return null;

            FileItem item = new FileItem();
            item.setName(name);
            item.setPath(joinPath(currentDir, name));
            item.setDirectory(perm.startsWith("d") || perm.startsWith("l"));
            item.setSize(size);
            item.setLastModified(parseUnixDate(dateStr));
            return item;
        }

        private long parseUnixDate(String dateStr) {
            try {
                // "Jan 1 12:00" 或 "Jan 1 2024"
                // 注意：带时间的格式默认缺年份，需补当前年份，否则SimpleDateFormat会回退到1970年
                String year;
                String time = null;
                String[] parts = dateStr.split("\\s+");
                if (parts.length >= 3) {
                    if (parts[2].contains(":")) {
                        year = String.valueOf(java.time.Year.now().getValue());
                        time = parts[2];
                    } else {
                        year = parts[2];
                    }
                    String fmt = time != null ? "MMM d yyyy H:mm" : "MMM d yyyy";
                    SimpleDateFormat sdf = new SimpleDateFormat(fmt, Locale.ENGLISH);
                    String toParse = time != null
                            ? parts[0] + " " + parts[1] + " " + year + " " + time
                            : parts[0] + " " + parts[1] + " " + year;
                    return sdf.parse(toParse).getTime();
                }
            } catch (Exception ignored) {}
            return 0;
        }

        private FileItem parseWindowsListLine(String line, String currentDir) {
            // 08-12-24  03:00PM <DIR> dirname
            // 08-12-24  03:00PM 1024 filename
            Pattern p = Pattern.compile("(\\d{2}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}[AP]M)\\s+(<DIR>|\\d+)\\s+(.*)");
            Matcher m = p.matcher(line);
            if (!m.matches()) return null;
            String name = m.group(4).trim();
            if (name.isEmpty() || name.equals(".") || name.equals("..")) return null;
            FileItem item = new FileItem();
            item.setName(name);
            item.setPath(joinPath(currentDir, name));
            item.setDirectory("<DIR>".equals(m.group(3)));
            if (!item.isDirectory()) {
                try { item.setSize(Long.parseLong(m.group(3))); } catch (Exception ignored) {}
            }
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("MM-dd-yy hh:mma", Locale.ENGLISH);
                item.setLastModified(sdf.parse(m.group(1) + " " + m.group(2)).getTime());
            } catch (Exception ignored) {}
            return item;
        }

        private void sendCommand(String cmd) throws IOException {
            controlWriter.write(cmd + "\r\n");
            controlWriter.flush();
        }

        /**
         * 读取FTP响应（支持多行，如 230-... 230 ...）
         */
        private String readResponse() throws IOException {
            String line = controlReader.readLine();
            if (line == null) {
                throw new IOException("FTP连接已关闭");
            }
            // 多行响应：第一行 code- 表示还有后续，最后一行 code(空格) 表示结束
            if (line.length() >= 4 && line.charAt(3) == '-') {
                String expectedCode = line.substring(0, 3);
                StringBuilder sb = new StringBuilder(line);
                while (true) {
                    String next = controlReader.readLine();
                    if (next == null) break;
                    sb.append("\n").append(next);
                    if (next.length() >= 4 && next.substring(0, 3).equals(expectedCode) && next.charAt(3) == ' ') {
                        break;
                    }
                }
                return sb.toString();
            }
            return line;
        }

        public void disconnect() {
            connected = false;
            try {
                if (controlSocket != null && !controlSocket.isClosed()) {
                    try { sendCommand("QUIT"); } catch (Exception ignored) {}
                    controlSocket.close();
                }
            } catch (Exception ignored) {}
            controlSocket = null;
            controlReader = null;
            controlWriter = null;
        }
    }
}
