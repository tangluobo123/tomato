package com.tangluobo.tomato.module.connect.view;

import com.tangluobo.tomato.module.connect.*;
import com.tangluobo.tomato.module.connect.service.DatabaseService;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import com.tangluobo.tomato.utils.RowSelectorDragSelection;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Polygon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * SQL编辑器视图
 * 优先使用 RichTextFX CodeArea（语法高亮），加载失败时回退到 TextArea
 */
public class SqlEditorView extends BorderPane {

    /** 行选择器列的标识名，用于在获取数据列名时跳过 */
    private static final String ROW_SELECTOR_COL = "__ROW_SELECTOR__";

    private final SqlEditor editor;

    private ComboBox<ConnectionConfig> connectionCombo;
    private ComboBox<String> databaseCombo;
    private TabPane resultTabPane;

    private boolean modified = false;
    private String queryName = null;
    private String savedSql = "";
    private TreeItem<String> queryNode;
    // 当前查询所属目录的相对路径（相对于 query 根目录），""表示根目录
    private String path = "";

    private Consumer<String> onTitleChange;
    private Runnable onSaveRequest;

    public SqlEditorView(List<ConnectionConfig> connections, ConnectionConfig initialConfig, String initialDatabase) {
        // ---- 顶部工具栏 ----
        HBox toolbar = new HBox(6);
        toolbar.setPadding(new Insets(4, 8, 4, 8));
        toolbar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0;");
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Button saveBtn = createToolbarButton("保存", "/images/connect/save.png");
        saveBtn.setOnAction(e -> handleSave());

        Button beautifyBtn = createToolbarButton("美化", "/images/connect/beautiful.png");
        beautifyBtn.setOnAction(e -> beautifySql());

        Button createQueryToolBtn = createToolbarButton("创建查询工具", "/images/connect/create_query_tool.png");
        createQueryToolBtn.setOnAction(e -> createQueryTool());

        Button runBtn = createToolbarButton("运行", "/images/connect/execute.png");
        runBtn.setOnAction(e -> executeQuery());

        Button explainBtn = createToolbarButton("解释", "/images/connect/code.png");
        explainBtn.setOnAction(e -> explainQuery());

        Separator sep1 = new Separator();
        sep1.setOrientation(javafx.geometry.Orientation.VERTICAL);
        sep1.setPrefHeight(22);

        connectionCombo = new ComboBox<>();
        connectionCombo.setPrefWidth(140);
        connectionCombo.setEditable(true);
        connectionCombo.setStyle("-fx-background-radius: 0; -fx-border-radius: 0;");
        connectionCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(ConnectionConfig c) {
                return c == null ? "" : c.getName();
            }

            @Override
            public ConnectionConfig fromString(String s) {
                if (s == null || s.trim().isEmpty()) return null;
                return connections.stream()
                        .filter(c -> c.getName().equals(s.trim()))
                        .findFirst()
                        .orElse(null);
            }
        });

        Image connectionIcon = new Image(getClass().getResourceAsStream("/images/connect/mysql_open.png"));
        if (connectionIcon != null) {
            connectionCombo.setCellFactory(lv -> new ListCell<>() {
                @Override
                protected void updateItem(ConnectionConfig item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText("");
                        setGraphic(null);
                    } else {
                        setText(item.getName());
                        ImageView cellIcon = new ImageView(connectionIcon);
                        cellIcon.setFitWidth(16);
                        cellIcon.setFitHeight(16);
                        setGraphic(cellIcon);
                        setContentDisplay(ContentDisplay.LEFT);
                        setGraphicTextGap(4);
                    }
                }
            });
        }

        if (connections != null) connectionCombo.getItems().addAll(connections);
        if (initialConfig != null) connectionCombo.setValue(initialConfig);

        connectionCombo.getStyleClass().add("combo-box-connection");
        connectionCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) refreshDatabaseList();
        });

        databaseCombo = new ComboBox<>();
        databaseCombo.setPrefWidth(120);
        databaseCombo.setEditable(true);
        databaseCombo.setStyle("-fx-background-radius: 0; -fx-border-radius: 0;");

        Image databaseIcon = new Image(getClass().getResourceAsStream("/images/connect/database.png"));
        if (databaseIcon != null) {
            databaseCombo.setCellFactory(lv -> new ListCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText("");
                        setGraphic(null);
                    } else {
                        setText(item);
                        ImageView cellIcon = new ImageView(databaseIcon);
                        cellIcon.setFitWidth(16);
                        cellIcon.setFitHeight(16);
                        setGraphic(cellIcon);
                        setContentDisplay(ContentDisplay.LEFT);
                        setGraphicTextGap(4);
                    }
                }
            });
        }

        if (initialDatabase != null) {
            databaseCombo.getItems().add(initialDatabase);
            databaseCombo.setValue(initialDatabase);
        }

        databaseCombo.getStyleClass().add("combo-box-database");

        toolbar.getChildren().addAll(connectionCombo, databaseCombo, sep1, saveBtn, createQueryToolBtn, beautifyBtn, runBtn, explainBtn);

        // ---- 编辑器区域 ----
        // 优先尝试 RichTextFX CodeArea，失败回退到 TextArea
        SqlEditor createdEditor = null;
        try {
            createdEditor = new RichTextSqlEditor(this::markModified);
            System.out.println("SQL编辑器: RichTextFX CodeArea 加载成功");
        } catch (Throwable t) {
            // RichTextFX 加载失败，使用普通 TextArea
            System.err.println("RichTextFX 不可用，使用普通编辑器: " + t.getMessage());
            t.printStackTrace();
            createdEditor = new PlainSqlEditor(this::markModified);
        }
        editor = createdEditor;

        // ---- 结果区域 ----
        resultTabPane = new TabPane();
        resultTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        resultTabPane.setStyle("-fx-font-size: 12px;");
        resultTabPane.setMinHeight(0);

        // 初始占位标签
        Tab placeholderTab = new Tab("信息");
        Label placeholder = new Label("执行查询以查看结果");
        placeholder.setStyle("-fx-text-fill: #888; -fx-padding: 16;");
        placeholderTab.setContent(placeholder);
        resultTabPane.getTabs().add(placeholderTab);

        // ---- 主布局 ----
        SplitPane splitPane = new SplitPane();
        splitPane.setOrientation(javafx.geometry.Orientation.VERTICAL);
        splitPane.getItems().addAll(editor.getNode(), resultTabPane);
        splitPane.setDividerPositions(0.6);

        this.setTop(toolbar);
        setCenter(splitPane);

        // 初始加载数据库列表
        if (initialConfig != null) {
            refreshDatabaseList();
        }

        getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
    }

    // ==================== 工具栏按钮创建 ====================

    private Button createToolbarButton(String text, String iconPath) {
        Button button = new Button(text);
        button.getStyleClass().add("toolbar-button");

        Image icon = new Image(getClass().getResourceAsStream(iconPath));
        if (icon != null) {
            ImageView iconView = new ImageView(icon);
            iconView.setFitWidth(16);
            iconView.setFitHeight(16);
            button.setGraphic(iconView);
        }

        return button;
    }

    // ==================== 保存逻辑 ====================

    public void markModified() {
        String current = editor.getText();
        boolean nowModified = !current.equals(savedSql);
        if (nowModified != this.modified) {
            this.modified = nowModified;
            notifyTitleChange();
        }
    }

    private void notifyTitleChange() {
        if (onTitleChange != null) onTitleChange.accept(getDisplayTitle());
    }

    private String getDisplayTitle() {
        String name = queryName != null ? queryName : "未保存查询";
        return (modified ? "*" : "") + name;
    }

    private void handleSave() {
        if (queryName == null) {
            if (onSaveRequest != null) {
                onSaveRequest.run();
                return;
            }
            TextInputDialog dialog = new TextInputDialog("查询1");
            dialog.setTitle("保存查询");
            dialog.setHeaderText(null);
            dialog.setContentText("查询名称：");
            DialogPositionUtil.centerOnOwner(dialog, this);
            dialog.showAndWait().ifPresent(name -> {
                if (!name.trim().isEmpty()) doSave(name.trim());
            });
        } else {
            doSave(queryName);
        }
    }

    private static final String APP_DIR = System.getProperty("user.home") + "/.tomato";
    private static final String QUERY_DIR = "query";

    public void doSave(String name) {
        this.queryName = name;
        this.savedSql = editor.getText();
        this.modified = false;
        notifyTitleChange();

        persistToFile(name, this.path);
    }

    /** 设置当前查询所属目录的相对路径（相对于 query 根目录） */
    public void setPath(String path) { this.path = path == null ? "" : path; }
    public String getPath() { return this.path; }

    /** 解析查询目录：~/.tomato/<conn>/<db>/query/<path> */
    public static Path resolveQueryDir(String connectionName, String dbName, String path) {
        String sanitizedConn = sanitizeFileName(connectionName);
        String sanitizedDb = sanitizeFileName(dbName);
        Path dir = Paths.get(APP_DIR, sanitizedConn, sanitizedDb, QUERY_DIR);
        if (path != null && !path.isEmpty()) {
            for (String part : path.split("/")) {
                dir = dir.resolve(sanitizeFileName(part));
            }
        }
        return dir;
    }

    private void persistToFile(String name, String path) {
        ConnectionConfig config = connectionCombo.getValue();
        String dbName = databaseCombo.getValue();
        if (config == null || dbName == null) return;

        String sanitizedQuery = sanitizeFileName(name);

        Path dir = resolveQueryDir(config.getName(), dbName, path);
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(sanitizedQuery + ".sql");
            Files.writeString(file, savedSql, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("保存查询文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return "unnamed";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_")
                   .replaceAll("\\s+", "_")
                   .replaceAll("_{2,}", "_")
                   .replaceAll("^_|_$", "");
    }

    public void loadFromFile(String connectionName, String dbName, String queryName, String path) {
        this.path = path == null ? "" : path;
        String sanitizedQuery = sanitizeFileName(queryName);

        Path file = resolveQueryDir(connectionName, dbName, this.path).resolve(sanitizedQuery + ".sql");
        if (Files.exists(file)) {
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                setSqlText(content);
                this.savedSql = content;
                this.modified = false;
            } catch (IOException e) {
                System.err.println("加载查询文件失败: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void deleteQueryFile(String connectionName, String dbName, String queryName, String path) {
        String sanitizedQuery = sanitizeFileName(queryName);
        Path file = resolveQueryDir(connectionName, dbName, path).resolve(sanitizedQuery + ".sql");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.println("删除查询文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void cleanupQueryFile(String connectionName, String dbName, String queryName, String path) {
        String sanitizedQuery = sanitizeFileName(queryName);
        Path file = resolveQueryDir(connectionName, dbName, path).resolve(sanitizedQuery + ".sql");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.err.println("删除查询文件失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /** 列出查询目录下的查询名（.sql 文件，去掉扩展名） */
    public static List<String> listQueries(String connectionName, String dbName, String path) {
        Path dir = resolveQueryDir(connectionName, dbName, path);
        List<String> queries = new ArrayList<>();
        if (!Files.isDirectory(dir)) return queries;

        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".sql"))
                  .forEach(p -> {
                      String fileName = p.getFileName().toString();
                      queries.add(fileName.substring(0, fileName.length() - 4));
                  });
        } catch (IOException e) {
            System.err.println("加载查询列表失败: " + e.getMessage());
        }
        return queries;
    }

    /** 列出查询目录下的子目录名 */
    public static List<String> listQueryDirs(String connectionName, String dbName, String path) {
        Path dir = resolveQueryDir(connectionName, dbName, path);
        List<String> dirs = new ArrayList<>();
        if (!Files.isDirectory(dir)) return dirs;

        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isDirectory)
                  .forEach(p -> dirs.add(p.getFileName().toString()));
        } catch (IOException e) {
            System.err.println("加载查询子目录失败: " + e.getMessage());
        }
        return dirs;
    }

    /** 递归删除查询目录（磁盘上的子目录及其所有内容） */
    public static void deleteQueryDir(String connectionName, String dbName, String path) {
        Path dir = resolveQueryDir(connectionName, dbName, path);
        if (!Files.isDirectory(dir)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
        } catch (IOException e) {
            System.err.println("删除查询目录失败: " + e.getMessage());
        }
    }

    // ==================== 数据库列表 ====================

    private void refreshDatabaseList() {
        ConnectionConfig config = connectionCombo.getValue();
        if (config == null) return;
        String currentDb = databaseCombo.getValue();
        databaseCombo.getItems().clear();
        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, null);
            connLock.lock();
            try {
                try {
                    if (config.getPassword() == null) return;
                    List<String> databases = DatabaseService.getDatabases(config);
                    Platform.runLater(() -> {
                        databaseCombo.getItems().addAll(databases);
                        if (currentDb != null && databases.contains(currentDb)) databaseCombo.setValue(currentDb);
                        else if (!databases.isEmpty()) databaseCombo.setValue(databases.get(0));
                    });
                } catch (Exception e) { /* 静默 */ }
            } finally {
                connLock.unlock();
            }
        }, "DB-RefreshDbList").start();
    }

    // ==================== SQL操作 ====================

    private void executeQuery() {
        ConnectionConfig config = connectionCombo.getValue();
        String dbName = databaseCombo.getValue();
        if (config == null || dbName == null) {
            showInfo("请先选择连接和数据库");
            return;
        }
        String sql = getEffectiveSql();
        if (sql.isEmpty()) return;

        // 显示执行中状态
        resultTabPane.getTabs().clear();
        Tab loadingTab = new Tab("信息");
        Label loadingLabel = new Label("执行中...");
        loadingLabel.setStyle("-fx-text-fill: #888; -fx-padding: 16;");
        loadingTab.setContent(loadingLabel);
        resultTabPane.getTabs().add(loadingTab);

        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, dbName);
            connLock.lock();
            try {
                try {
                    MultiStatementResult multiResult = DatabaseService.executeMultiSqlQuery(config, dbName, sql, 1000);

                    // 收集剖析结果（对SELECT语句执行EXPLAIN）
                    List<TableRowData> explainResults = new java.util.ArrayList<>();
                    List<String> explainSqls = new java.util.ArrayList<>();
                    for (SqlStatementResult sr : multiResult.getResults()) {
                        if (sr.isSuccess() && sr.isSelect() && sr.isHasResultSet()) {
                            explainSqls.add(sr.getSql());
                            explainResults.add(DatabaseService.executeExplainQuery(config, dbName, sr.getSql()));
                        }
                    }

                    // 获取服务器状态
                    TableRowData statusResult = DatabaseService.executeStatusQuery(config, dbName);

                    Platform.runLater(() -> buildResultTabs(multiResult, explainResults, explainSqls, statusResult));
                } catch (Exception e) {
                    Platform.runLater(() -> showInfo("执行失败: " + e.getMessage()));
                }
            } finally {
                connLock.unlock();
            }
        }, "DB-ExecuteQuery").start();
    }

    private void explainQuery() {
        ConnectionConfig config = connectionCombo.getValue();
        String dbName = databaseCombo.getValue();
        if (config == null || dbName == null) {
            showInfo("请先选择连接和数据库");
            return;
        }
        String sql = getEffectiveSql();
        if (sql.isEmpty()) return;

        resultTabPane.getTabs().clear();
        Tab loadingTab = new Tab("剖析");
        Label loadingLabel = new Label("执行解释...");
        loadingLabel.setStyle("-fx-text-fill: #888; -fx-padding: 16;");
        loadingTab.setContent(loadingLabel);
        resultTabPane.getTabs().add(loadingTab);

        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, dbName);
            connLock.lock();
            try {
                try {
                    List<String> statements = SqlSplitter.split(sql);
                    List<TableRowData> explainResults = new java.util.ArrayList<>();
                    List<String> explainSqls = new java.util.ArrayList<>();
                    for (String stmt : statements) {
                        if (SqlSplitter.isSelectStatement(stmt)) {
                            explainSqls.add(stmt);
                            explainResults.add(DatabaseService.executeExplainQuery(config, dbName, stmt));
                        }
                    }

                    Platform.runLater(() -> {
                        resultTabPane.getTabs().clear();
                        if (explainResults.isEmpty()) {
                            showInfo("没有可解释的SELECT语句");
                        } else {
                            resultTabPane.getTabs().add(buildExplainTab(explainResults, explainSqls));
                            resultTabPane.getSelectionModel().select(0);
                        }
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> showInfo("解释失败: " + e.getMessage()));
                }
            } finally {
                connLock.unlock();
            }
        }, "DB-ExplainQuery").start();
    }

    private void beautifySql() {
        String sql = editor.getText().trim();
        if (sql.isEmpty()) return;
        editor.setText(formatSql(sql));
    }

    private void createQueryTool() {
        String sql = editor.getText().trim();
        if (sql.isEmpty()) return;
        editor.setText(formatSql(sql));
    }

    private String formatSql(String sql) {
        sql = sql.replaceAll("\\s+", " ").trim();
        String[] lineBreakBefore = {
                " SELECT ", " FROM ", " WHERE ", " INNER JOIN ", " LEFT JOIN ",
                " RIGHT JOIN ", " CROSS JOIN ", " FULL JOIN ", " ON ",
                " GROUP BY ", " ORDER BY ", " HAVING ", " LIMIT ", " OFFSET ",
                " UNION ", " INSERT INTO ", " VALUES ", " UPDATE ", " SET ",
                " DELETE FROM ", " CREATE TABLE ", " DROP TABLE ", " ALTER TABLE "
        };
        for (String keyword : lineBreakBefore) {
            String upper = keyword.toUpperCase();
            String lower = keyword.toLowerCase();
            sql = sql.replace(keyword, "\n" + keyword.trim() + "\n    ");
            sql = sql.replace(upper, "\n" + upper.trim() + "\n    ");
            sql = sql.replace(lower, "\n" + lower.trim() + "\n    ");
        }
        sql = sql.replaceAll("\n\\s*\n", "\n").trim();
        if (sql.startsWith("    ")) sql = sql.substring(4);
        return sql;
    }

    private String getEffectiveSql() {
        String sql = editor.getText().trim();
        if (sql.isEmpty()) return "";
        String selected = editor.getSelectedText();
        if (selected != null && !selected.trim().isEmpty()) sql = selected.trim();
        return sql;
    }

    // ==================== 结果标签页构建 ====================

    private void showInfo(String message) {
        resultTabPane.getTabs().clear();
        Tab tab = new Tab("信息");
        Label label = new Label(message);
        label.setStyle("-fx-text-fill: #c00; -fx-padding: 16;");
        label.setWrapText(true);
        tab.setContent(label);
        resultTabPane.getTabs().add(tab);
    }

    private void buildResultTabs(MultiStatementResult multiResult,
                                  List<TableRowData> explainResults,
                                  List<String> explainSqls,
                                  TableRowData statusResult) {
        resultTabPane.getTabs().clear();

        List<SqlStatementResult> selectResults = multiResult.getSelectResults();

        // 1. 信息标签
        resultTabPane.getTabs().add(buildInfoTab(multiResult));

        // 2. 结果标签（每个有结果集的语句一个）
        for (int i = 0; i < selectResults.size(); i++) {
            String tabName = selectResults.size() == 1 ? "结果" : "结果" + (i + 1);
            resultTabPane.getTabs().add(buildResultTab(tabName, selectResults.get(i)));
        }

        // 3. 剖析标签（有SELECT语句时生成）
        if (!explainResults.isEmpty()) {
            resultTabPane.getTabs().add(buildExplainTab(explainResults, explainSqls));
        }

        // 4. 状态标签
        resultTabPane.getTabs().add(buildStatusTab(statusResult));

        // 默认选中策略
        if (multiResult.getFailCount() > 0) {
            resultTabPane.getSelectionModel().select(0); // 有错误选信息
        } else if (!selectResults.isEmpty()) {
            resultTabPane.getSelectionModel().select(1); // 选第一个结果
        } else {
            resultTabPane.getSelectionModel().select(0); // 选信息
        }
    }

    private Tab buildInfoTab(MultiStatementResult multiResult) {
        Tab tab = new Tab("信息");
        TextArea infoArea = new TextArea();
        infoArea.setEditable(false);
        infoArea.setWrapText(true);
        infoArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px; " +
                "-fx-control-inner-background: white; -fx-padding: 8; -fx-background-color: white;");

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < multiResult.getResults().size(); i++) {
            SqlStatementResult sr = multiResult.getResults().get(i);
            if (i > 0) sb.append("\n");

            // 显示SQL文本
            sb.append(sr.getSql()).append("\n");

            // 显示执行状态
            if (sr.isSuccess()) {
                sb.append(" > OK\n");
                sb.append(" > 时间: ").append(String.format("%.3fs", sr.getQueryTime() / 1000.0)).append("\n");
                if (sr.isHasResultSet() && sr.getResultData() != null) {
                    sb.append(" > 行数: ").append(sr.getResultData().getTotalCount()).append("\n");
                } else if (!sr.isHasResultSet()) {
                    int updateCount = sr.getUpdateCount();
                    if (updateCount >= 0) {
                        sb.append(" > 影响: ").append(updateCount).append(" 行\n");
                    }
                }
            } else {
                sb.append(" > 错误\n");
                sb.append(" > ").append(sr.getErrorMessage()).append("\n");
            }
        }

        // 汇总
        sb.append("\n--- 汇总 ---\n");
        sb.append("总耗时: ").append(String.format("%.3fs", multiResult.getTotalTime() / 1000.0)).append("\n");
        sb.append("成功: ").append(multiResult.getSuccessCount());
        sb.append("  失败: ").append(multiResult.getFailCount());

        infoArea.setText(sb.toString());
        tab.setContent(infoArea);
        return tab;
    }

    private Tab buildResultTab(String tabName, SqlStatementResult stmtResult) {
        Tab tab = new Tab(tabName);
        if (stmtResult.getResultData() != null) {
            tab.setContent(createTableView(stmtResult.getResultData(), stmtResult.getSourceTableName()));
        } else {
            Label label = new Label("无结果集");
            label.setStyle("-fx-text-fill: #888; -fx-padding: 16;");
            tab.setContent(label);
        }
        return tab;
    }

    private Tab buildExplainTab(List<TableRowData> explainResults, List<String> explainSqls) {
        Tab tab = new Tab("剖析");
        if (explainResults.size() == 1) {
            tab.setContent(createTableView(explainResults.get(0)));
        } else {
            VBox vbox = new VBox(8);
            vbox.setStyle("-fx-padding: 4; -fx-background-color: white;");
            for (int i = 0; i < explainResults.size(); i++) {
                if (i > 0) {
                    Separator sep = new Separator();
                    vbox.getChildren().add(sep);
                }
                // 显示对应的SQL片段
                String sqlSnippet = explainSqls.get(i);
                if (sqlSnippet.length() > 80) sqlSnippet = sqlSnippet.substring(0, 80) + "...";
                Label sqlLabel = new Label("SQL: " + sqlSnippet);
                sqlLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666; -fx-padding: 2 0;");
                vbox.getChildren().add(sqlLabel);

                Node tableView = createTableView(explainResults.get(i));
                VBox.setVgrow(tableView, Priority.ALWAYS);
                vbox.getChildren().add(tableView);
            }
            tab.setContent(vbox);
        }
        return tab;
    }

    private Tab buildStatusTab(TableRowData statusResult) {
        Tab tab = new Tab("状态");
        if (statusResult != null) {
            tab.setContent(createTableView(statusResult));
        } else {
            Label label = new Label("无法获取状态信息");
            label.setStyle("-fx-text-fill: #888; -fx-padding: 16;");
            tab.setContent(label);
        }
        return tab;
    }

    /**
     * 从TableRowData创建TableView（复用逻辑）
     */
    private Node createTableView(TableRowData result) {
        return createTableView(result, null);
    }

    /**
     * 从TableRowData创建TableView，可指定源表名以支持右键删除行
     * @param result 表格数据
     * @param sourceTableName 源表名，若非null且有主键则启用右键删除
     */
    private Node createTableView(TableRowData result, String sourceTableName) {
        TableView<ObservableList<String>> tableView = new TableView<>();
        GlobalConfig globalConfig = GlobalConfig.getInstance();
        // 固定行高（读取全局配置 tableFontSize 派生）：避免内容多的行把整行撑得过高
        int rowHeight = globalConfig.getTableFontSize() + 18;
        tableView.setFixedCellSize(rowHeight);
        String fontStyle = String.format("-fx-font-family: '%s'; -fx-font-size: %dpx;",
                globalConfig.getTableFontName(), globalConfig.getTableFontSize());
        tableView.setStyle(fontStyle + " -fx-padding: 0; -fx-background-insets: 0; -fx-background-color: transparent; -fx-border-color: transparent; -fx-border-insets: 0; -fx-table-header-height: " + rowHeight + ";");
        tableView.setPlaceholder(new Label("无数据"));
        tableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tableView.getSelectionModel().setCellSelectionEnabled(true);
        // 布局后移除内部节点的默认padding/border，消除左侧间隔
        tableView.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin != null) {
                stripPaddingRecursive(tableView);
            }
        });

        List<String> columns = result.getColumnNames();

        // 创建行选择器列：选中行显示黑色实心三角箭头
        TableColumn<ObservableList<String>, String> selectorCol = new TableColumn<>();
        selectorCol.setPrefWidth(15);
        selectorCol.setMaxWidth(15);
        selectorCol.setMinWidth(15);
        selectorCol.setSortable(false);
        selectorCol.setReorderable(false);
        selectorCol.setStyle("-fx-alignment: CENTER;");
        selectorCol.setUserData(ROW_SELECTOR_COL);
        selectorCol.setCellFactory(col -> new TableCell<>() {
            private final Polygon arrow = new Polygon(0, -0.5, 5, 4.5, 0, 9.5);
            private javafx.beans.InvalidationListener selectionListener;

            {
                arrow.setFill(Color.BLACK);
                setGraphic(arrow);
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                setAlignment(Pos.CENTER);
                arrow.setVisible(false);
                setStyle("-fx-border-color: transparent #BEBEBC transparent #BEBEBC; -fx-border-width: 0 1 0 1;");
                // 行选择器列拖拽多行选中的起始行（-1 表示未从行选择器发起拖拽）
                final int[] dragStart = RowSelectorDragSelection.install(tableView, this);
                addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
                    if (getTableRow() != null && getTableRow().getItem() != null) {
                        int row = getTableRow().getIndex();
                        if (event.isControlDown()) {
                            dragStart[0] = -1;
                            if (tableView.getSelectionModel().isSelected(row)) {
                                tableView.getSelectionModel().clearSelection(row);
                            } else {
                                tableView.getSelectionModel().select(row);
                            }
                        } else if (event.isShiftDown()) {
                            dragStart[0] = -1;
                            int anchor = tableView.getSelectionModel().getFocusedIndex();
                            if (anchor >= 0) {
                                int start = Math.min(row, anchor);
                                int end = Math.max(row, anchor);
                                tableView.getSelectionModel().clearSelection();
                                tableView.getSelectionModel().selectRange(start, end + 1);
                            } else {
                                tableView.getSelectionModel().clearSelection();
                                tableView.getSelectionModel().select(row);
                            }
                        } else {
                            tableView.getSelectionModel().clearSelection();
                            tableView.getSelectionModel().select(row);
                            dragStart[0] = row;
                        }
                        event.consume();
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                if (selectionListener != null) {
                    tableView.getSelectionModel().getSelectedItems().removeListener(selectionListener);
                    selectionListener = null;
                }
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    arrow.setVisible(false);
                    setStyle("-fx-border-color: transparent; -fx-border-width: 0;");
                    return;
                }
                setStyle("-fx-border-color: transparent #BEBEBC #BEBEBC #BEBEBC; -fx-border-width: 0 1 1 1;");
                arrow.setVisible(tableView.getSelectionModel().getSelectedIndices().contains(getTableRow().getIndex()));
                selectionListener = obs -> {
                    if (getTableRow() != null) {
                        arrow.setVisible(tableView.getSelectionModel().getSelectedIndices().contains(getTableRow().getIndex()));
                    }
                };
                tableView.getSelectionModel().getSelectedItems().addListener(selectionListener);
            }
        });
        tableView.getColumns().add(selectorCol);
        for (int i = 0; i < columns.size(); i++) {
            final int colIndex = i;
            TableColumn<ObservableList<String>, String> col = new TableColumn<>(columns.get(i));
            // 根据表头文字长度动态设置列宽
            int headerLen = columns.get(i).length();
            col.setPrefWidth(Math.max(headerLen * 8 + 16, 60));
            col.setCellValueFactory(param -> {
                ObservableList<String> row = param.getValue();
                return new javafx.beans.property.SimpleStringProperty(colIndex < row.size() ? row.get(colIndex) : "");
            });
            tableView.getColumns().add(col);
        }
        if (result.getRows() != null) {
            tableView.getItems().addAll(result.getRows());
        }

        // 如果有源表名，异步加载主键并设置右键删除菜单
        if (sourceTableName != null) {
            setupQueryResultDeleteMenu(tableView, sourceTableName, columns);
        }

        ScrollPane scrollPane = new ScrollPane(tableView);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");
        scrollPane.setFitToHeight(true);
        scrollPane.setFitToWidth(false);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // TableView宽度跟随视口（让垂直滚动条位于面板最右，右侧空白属于表格）
        tableView.minWidthProperty().bind(scrollPane.widthProperty());
        // 鼠标拖拽选中多个cell
        setupDragSelection(tableView);
        // Ctrl+C 复制选中cell
        setupKeyboardShortcuts(tableView);
        return scrollPane;
    }

    /**
     * 为查询结果TableView设置右键删除菜单
     */
    private void setupQueryResultDeleteMenu(TableView<ObservableList<String>> tableView, String tableName, List<String> columnNames) {
        ConnectionConfig config = connectionCombo.getValue();
        String dbName = databaseCombo.getValue();
        if (config == null || dbName == null) return;

        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, dbName);
            connLock.lock();
            try {
                try {
                    List<String> pks = DatabaseService.getPrimaryKeys(config, dbName, tableName);
                    if (pks.isEmpty()) return;
                    Platform.runLater(() -> {
                        ContextMenu contextMenu = new ContextMenu();
                        MenuItem deleteItem = new MenuItem();
                        deleteItem.setStyle("-fx-text-fill: #c00;");
                        deleteItem.setOnAction(e -> handleQueryResultDeleteRows(tableView, tableName, pks, columnNames));
                        contextMenu.getItems().add(deleteItem);
                        tableView.setContextMenu(contextMenu);

                        // 右键时根据选中行数动态更新菜单文字
                        tableView.setOnContextMenuRequested(event -> {
                            int count = (int) tableView.getSelectionModel().getSelectedItems().stream().distinct().count();
                            deleteItem.setText("删除" + (count > 0 ? count : 1) + "条数据");
                        });
                    });
                } catch (Exception e) {
                    // 获取主键失败，不提供删除功能
                }
            } finally {
                connLock.unlock();
            }
        }, "DB-LoadPrimaryKeys-Query").start();
    }

    /**
     * 处理查询结果表格中的行删除
     */
    private void handleQueryResultDeleteRows(TableView<ObservableList<String>> tableView, String tableName,
                                              List<String> primaryKeyColumns, List<String> columnNames) {
        ConnectionConfig config = connectionCombo.getValue();
        String dbName = databaseCombo.getValue();
        if (config == null || dbName == null) return;

        List<ObservableList<String>> selectedRows = tableView.getSelectionModel().getSelectedItems()
                .stream().distinct().toList();
        if (selectedRows.isEmpty()) return;

        int count = selectedRows.size();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除行");
        confirm.setHeaderText(null);
        confirm.setContentText("确定要从表 " + tableName + " 中删除选中的 " + count + " 行吗？此操作不可撤销！");
        DialogPositionUtil.centerOnOwner(confirm, this);
        confirm.showAndWait().ifPresent(response -> {
            if (response != ButtonType.OK) return;

            List<ObservableList<String>> rowsToDelete = new ArrayList<>(selectedRows);

            new Thread(() -> {
                java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, dbName);
                connLock.lock();
                try {
                    try {
                        int deleted = DatabaseService.deleteRowsByPrimaryKeys(
                                config, dbName, tableName,
                                primaryKeyColumns, columnNames, rowsToDelete);
                        Platform.runLater(() -> {
                            tableView.getItems().removeAll(rowsToDelete);
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> {
                            Alert err = new Alert(Alert.AlertType.ERROR);
                            err.setTitle("删除失败");
                            err.setHeaderText(null);
                            err.setContentText("删除行失败: " + e.getMessage());
                            DialogPositionUtil.centerOnOwner(err, this);
                            err.showAndWait();
                        });
                    }
                } finally {
                    connLock.unlock();
                }
            }, "DB-DeleteRows-Query").start();
        });
    }

    // ==================== Getter/Setter ====================

    public String getSqlText() {
        return editor.getText();
    }

    public void setSqlText(String sql) {
        editor.setText(sql);
    }

    public String getQueryName() {
        return queryName;
    }

    public void setQueryName(String name) {
        this.queryName = name;
        notifyTitleChange();
    }

    public boolean isModified() {
        return modified;
    }

    public boolean isNamed() {
        return queryName != null;
    }

    public TreeItem<String> getQueryNode() {
        return queryNode;
    }

    public void setQueryNode(TreeItem<String> node) {
        this.queryNode = node;
    }

    public ConnectionConfig getSelectedConnection() {
        return connectionCombo.getValue();
    }

    public String getSelectedDatabase() {
        return databaseCombo.getValue();
    }

    public void setOnTitleChange(Consumer<String> callback) {
        this.onTitleChange = callback;
    }

    public void setOnSaveRequest(Runnable callback) {
        this.onSaveRequest = callback;
    }

    // ==================== 编辑器接口 ====================

    private interface SqlEditor {
        javafx.scene.Node getNode();

        String getText();

        void setText(String text);

        String getSelectedText();
    }

    /**
     * 基于 RichTextFX InlineCssTextArea 的语法高亮编辑器
     * 使用内联CSS字符串，不需要外部CSS文件
     */
    private static class RichTextSqlEditor implements SqlEditor {
        private final org.fxmisc.richtext.InlineCssTextArea textArea;
        private final org.fxmisc.flowless.VirtualizedScrollPane<org.fxmisc.richtext.InlineCssTextArea> scrollPane;
        private final javafx.scene.layout.HBox editorContainer;
        private final javafx.scene.layout.VBox lineNumberBox;
        private static final int MAX_LINES = 500;

        // 内联CSS样式字符串，直接应用到文本段
        private static final String STYLE_KEYWORD = "-fx-fill: #0000FF; -fx-font-weight: bold;";
        private static final String STYLE_STRING = "-fx-fill: #A31515;";
        private static final String STYLE_COMMENT = "-fx-fill: #6A9955; -fx-font-style: italic;";
        private static final String STYLE_NUMBER = "-fx-fill: #098658;";

        RichTextSqlEditor(Runnable onModified) {
            textArea = new org.fxmisc.richtext.InlineCssTextArea();
            textArea.setStyle(
                    "-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px; " +
                            "-fx-background-color: white; -fx-padding: 0; -fx-text-fill: #333;"
            );

            scrollPane = new org.fxmisc.flowless.VirtualizedScrollPane<>(textArea);

            lineNumberBox = new javafx.scene.layout.VBox();
            lineNumberBox.setStyle("-fx-background-color: #f8f8f8; -fx-padding: 0;");
            lineNumberBox.setPrefWidth(40);
            lineNumberBox.setMinWidth(40);
            lineNumberBox.setMaxWidth(40);
            // 不驱动父布局高度，由父容器(HBox)分配空间后被动填充
            lineNumberBox.setMinHeight(0);
            lineNumberBox.setPrefHeight(0);

            java.util.List<Label> lineNumberLabels = new java.util.ArrayList<>();
            for (int i = 1; i <= MAX_LINES; i++) {
                Label label = new Label(Integer.toString(i));
                label.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px; " +
                        "-fx-text-fill: #888888; -fx-alignment: CENTER_RIGHT; -fx-padding: 0 8 0 4;");
                label.setVisible(false);
                label.setManaged(false);
                lineNumberLabels.add(label);
                lineNumberBox.getChildren().add(label);
            }
            javafx.scene.layout.Region filler = new javafx.scene.layout.Region();
            javafx.scene.layout.VBox.setVgrow(filler, javafx.scene.layout.Priority.ALWAYS);
            lineNumberBox.getChildren().add(filler);

            // 初始显示第1行
            updateLineNumbers(lineNumberLabels, 1);

            editorContainer = new javafx.scene.layout.HBox();
            editorContainer.getChildren().addAll(lineNumberBox, scrollPane);
            javafx.scene.layout.HBox.setHgrow(scrollPane, javafx.scene.layout.Priority.ALWAYS);
            // 不驱动SplitPane分配，被动接受SplitPane给的空间
            editorContainer.setMinHeight(0);
            editorContainer.setPrefHeight(200);

            textArea.estimatedScrollYProperty().addListener((obs, oldVal, newVal) -> {
                lineNumberBox.setTranslateY(-newVal.doubleValue());
            });

            // 内容变化
            textArea.textProperty().addListener((obs, oldVal, newVal) -> {
                onModified.run();
                applyHighlighting();
                updateLineNumbers(lineNumberLabels, textArea.getParagraphs().size());
            });

            // Tab缩进
            textArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.TAB) {
                    e.consume();
                    textArea.insertText(textArea.getCaretPosition(), "    ");
                }
            });
            // Ctrl+Enter 运行
            textArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.isControlDown() && e.getCode() == KeyCode.ENTER) e.consume();
            });
            // Ctrl+S 保存
            textArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.isControlDown() && e.getCode() == KeyCode.S) e.consume();
            });

            // 初始应用一次高亮（空文本时也设置默认样式）
            applyHighlighting();
        }

        private static final String[] KEYWORDS = {
                "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET",
                "DELETE", "CREATE", "DROP", "ALTER", "TABLE", "INDEX", "VIEW", "DATABASE",
                "AND", "OR", "NOT", "IN", "EXISTS", "BETWEEN", "LIKE", "IS", "NULL",
                "JOIN", "INNER", "LEFT", "RIGHT", "OUTER", "FULL", "CROSS", "ON",
                "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "UNION", "ALL",
                "AS", "DISTINCT", "CASE", "WHEN", "THEN", "ELSE", "END",
                "COUNT", "SUM", "AVG", "MIN", "MAX",
                "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "CONSTRAINT",
                "DEFAULT", "CHECK", "UNIQUE", "AUTO_INCREMENT",
                "IF", "CASCADE", "RENAME", "TO",
                "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION",
                "GRANT", "REVOKE", "PRIVILEGES",
                "SHOW", "DESCRIBE", "EXPLAIN", "USE", "TRUNCATE",
                "CHARACTER", "COLLATE", "REPLACE"
        };

        // 关键词不区分大小写
        private static final String KEYWORD_PATTERN = "(?i)\\b(" + String.join("|", KEYWORDS) + ")\\b";
        private static final java.util.regex.Pattern SYNTAX_PATTERN = java.util.regex.Pattern.compile(
                "(?<KEYWORD>" + KEYWORD_PATTERN + ")" +
                        "|(?<STRING>'[^']*')" +
                        "|(?<COMMENT1>--[^\n]*)" +
                        "|(?<COMMENT2>/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/)" +
                        "|(?<NUMBER>\\b\\d+(\\.\\d+)?\\b)"
        );

        private static void updateLineNumbers(java.util.List<Label> labels, int lineCount) {
            int visibleCount = Math.min(lineCount, labels.size());
            for (int i = 0; i < labels.size(); i++) {
                boolean show = i < visibleCount;
                labels.get(i).setVisible(show);
                labels.get(i).setManaged(show);
            }
        }

        private void applyHighlighting() {
            String text = textArea.getText();
            if (text.isEmpty()) return;
            try {
                java.util.regex.Matcher matcher = SYNTAX_PATTERN.matcher(text);
                int lastKwEnd = 0;
                org.fxmisc.richtext.model.StyleSpansBuilder<String> spansBuilder =
                        new org.fxmisc.richtext.model.StyleSpansBuilder<>();
                while (matcher.find()) {
                    String style;
                    if (matcher.group("KEYWORD") != null) style = STYLE_KEYWORD;
                    else if (matcher.group("STRING") != null) style = STYLE_STRING;
                    else if (matcher.group("COMMENT1") != null) style = STYLE_COMMENT;
                    else if (matcher.group("COMMENT2") != null) style = STYLE_COMMENT;
                    else if (matcher.group("NUMBER") != null) style = STYLE_NUMBER;
                    else style = "";
                    if (matcher.start() > lastKwEnd) {
                        spansBuilder.add("", matcher.start() - lastKwEnd);
                    }
                    spansBuilder.add(style, matcher.end() - matcher.start());
                    lastKwEnd = matcher.end();
                }
                if (lastKwEnd < text.length()) {
                    spansBuilder.add("", text.length() - lastKwEnd);
                }
                textArea.setStyleSpans(0, spansBuilder.create());
            } catch (Exception e) {
                System.err.println("SQL高亮异常: " + e.getMessage());
                e.printStackTrace();
            }
        }

        @Override
        public javafx.scene.Node getNode() {
            return editorContainer;
        }

        @Override
        public String getText() {
            return textArea.getText();
        }

        @Override
        public void setText(String text) {
            textArea.replaceText(text);
        }

        @Override
        public String getSelectedText() {
            return textArea.getSelectedText();
        }
    }

    /**
     * 基于 TextArea 的普通编辑器（fallback）
     */
    private static class PlainSqlEditor implements SqlEditor {
        private final TextArea textArea;

        PlainSqlEditor(Runnable onModified) {
            textArea = new TextArea();
            textArea.setStyle(
                    "-fx-background-color: white; " +
                            "-fx-text-fill: #333; " +
                            "-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px; " +
                            "-fx-padding: 8; " +
                            "-fx-border-color: transparent; " +
                            "-fx-focus-color: transparent; " +
                            "-fx-faint-focus-color: transparent;"
            );
            textArea.setWrapText(false);
            textArea.setPromptText("输入SQL语句...");

            textArea.textProperty().addListener((obs, oldVal, newVal) -> onModified.run());

            textArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.TAB) {
                    e.consume();
                    textArea.insertText(textArea.getCaretPosition(), "    ");
                }
            });
            textArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.isControlDown() && e.getCode() == KeyCode.ENTER) e.consume();
            });
            textArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.isControlDown() && e.getCode() == KeyCode.S) e.consume();
            });
        }

        @Override
        public javafx.scene.Node getNode() {
            return textArea;
        }

        @Override
        public String getText() {
            return textArea.getText();
        }

        @Override
        public void setText(String text) {
            textArea.setText(text);
        }

        @Override
        public String getSelectedText() {
            return textArea.getSelectedText();
        }
    }

    /**
     * 鼠标拖拽选中多个cell + Shift点击范围选中
     */
    private void setupDragSelection(TableView<ObservableList<String>> tableView) {
        final int[] dragStart = {-1, -1};
        final int[] anchorCell = {-1, -1};

        tableView.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            int[] cellPos = getCellPositionAt(tableView, event);
            if (cellPos == null) return;

            if (event.isShiftDown() && anchorCell[0] >= 0) {
                int minRow = Math.min(anchorCell[0], cellPos[0]);
                int maxRow = Math.max(anchorCell[0], cellPos[0]);
                int minCol = Math.min(anchorCell[1], cellPos[1]);
                int maxCol = Math.max(anchorCell[1], cellPos[1]);
                tableView.getSelectionModel().clearSelection();
                for (int r = minRow; r <= maxRow; r++) {
                    for (int c = minCol; c <= maxCol; c++) {
                        TableColumn<ObservableList<String>, ?> col = tableView.getColumns().get(c);
                        tableView.getSelectionModel().select(r, col);
                    }
                }
                event.consume();
                return;
            }

            dragStart[0] = cellPos[0];
            dragStart[1] = cellPos[1];
            anchorCell[0] = cellPos[0];
            anchorCell[1] = cellPos[1];
            tableView.getSelectionModel().clearSelection();
            TableColumn<ObservableList<String>, ?> col = tableView.getColumns().get(cellPos[1]);
            tableView.getSelectionModel().select(cellPos[0], col);
        });

        tableView.setOnMouseDragged(event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            if (dragStart[0] < 0) return;
            int[] cellPos = getCellPositionAt(tableView, event);
            if (cellPos == null) return;
            int endRow = cellPos[0];
            int endCol = cellPos[1];
            int minRow = Math.min(dragStart[0], endRow);
            int maxRow = Math.max(dragStart[0], endRow);
            int minCol = Math.min(dragStart[1], endCol);
            int maxCol = Math.max(dragStart[1], endCol);
            tableView.getSelectionModel().clearSelection();
            for (int r = minRow; r <= maxRow; r++) {
                for (int c = minCol; c <= maxCol; c++) {
                    TableColumn<ObservableList<String>, ?> col = tableView.getColumns().get(c);
                    tableView.getSelectionModel().select(r, col);
                }
            }
        });

        tableView.setOnMouseReleased(event -> {
            dragStart[0] = -1;
        });
    }

    /**
     * 根据鼠标事件位置获取对应的cell坐标 [row, colIndex]
     * 点击右侧空白区域（TableRow 但非 TableCell）时返回该行和最后一列
     */
    private int[] getCellPositionAt(TableView<ObservableList<String>> tableView, javafx.scene.input.MouseEvent event) {
        Node target = event.getPickResult().getIntersectedNode();
        TableRow<?> clickedRow = null;
        while (target != null && target != tableView) {
            if (clickedRow == null && target instanceof TableRow<?> row) {
                clickedRow = row;
            }
            if (target instanceof TableCell<?, ?> cell) {
                if (cell.getTableColumn() != null && cell.getTableRow() != null) {
                    int row = cell.getTableRow().getIndex();
                    int col = tableView.getColumns().indexOf(cell.getTableColumn());
                    if (col >= 0) {
                        return new int[]{row, col};
                    }
                }
            }
            target = target.getParent();
        }
        if (clickedRow != null) {
            int rowIndex = clickedRow.getIndex();
            int lastCol = getLastVisibleDataColumnIndex(tableView);
            if (lastCol >= 0) {
                return new int[]{rowIndex, lastCol};
            }
        }
        return null;
    }

    /**
     * 获取最后一个可见数据列在 tableView.getColumns() 中的索引
     */
    private int getLastVisibleDataColumnIndex(TableView<ObservableList<String>> tableView) {
        for (int i = tableView.getColumns().size() - 1; i >= 0; i--) {
            TableColumn<ObservableList<String>, ?> col = tableView.getColumns().get(i);
            if (col.isVisible() && !ROW_SELECTOR_COL.equals(col.getUserData())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 键盘快捷键：Ctrl+C复制
     */
    private void setupKeyboardShortcuts(TableView<ObservableList<String>> tableView) {
        tableView.setOnKeyPressed(event -> {
            if (event.isControlDown() && event.getCode() == javafx.scene.input.KeyCode.C) {
                handleCopySelectedCells(tableView);
                event.consume();
            }
        });
    }

    /**
     * 复制选中的cell到剪贴板，按行列排列，Tab分隔列，换行分隔行
     */
    private void handleCopySelectedCells(TableView<ObservableList<String>> tableView) {
        @SuppressWarnings("unchecked")
        ObservableList<TablePosition<ObservableList<String>, ?>> selectedCells =
                (ObservableList<TablePosition<ObservableList<String>, ?>>) (ObservableList<?>) tableView.getSelectionModel().getSelectedCells();
        if (selectedCells.isEmpty()) return;

        int minRow = Integer.MAX_VALUE, maxRow = -1;
        int minCol = Integer.MAX_VALUE, maxCol = -1;
        for (TablePosition<?, ?> pos : selectedCells) {
            int row = pos.getRow();
            int col = tableView.getColumns().indexOf(pos.getTableColumn());
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);
            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);
        }

        java.util.Set<String> selectedSet = new java.util.HashSet<>();
        for (TablePosition<?, ?> pos : selectedCells) {
            int col = tableView.getColumns().indexOf(pos.getTableColumn());
            selectedSet.add(pos.getRow() + "," + col);
        }

        StringBuilder sb = new StringBuilder();
        for (int r = minRow; r <= maxRow; r++) {
            ObservableList<String> rowData = tableView.getItems().get(r);
            boolean firstCol = true;
            for (int c = minCol; c <= maxCol; c++) {
                if (!selectedSet.contains(r + "," + c)) continue;
                if (!firstCol) sb.append('\t');
                firstCol = false;
                int dataColIndex = c - 1; // 减去行选择器列
                if (dataColIndex >= 0 && dataColIndex < rowData.size()) {
                    String value = rowData.get(dataColIndex);
                    sb.append(value != null ? value : "");
                }
            }
            if (r < maxRow) sb.append('\n');
        }

        javafx.scene.input.Clipboard clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(sb.toString());
        clipboard.setContent(content);
    }

    private void stripPaddingRecursive(Node node) {
        if (node instanceof Region region) {
            if (!region.getStyleClass().contains("table-cell")
                    && !region.getStyleClass().contains("column-header")
                    && !region.getStyleClass().contains("table-row-cell")) {
                region.setPadding(Insets.EMPTY);
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                stripPaddingRecursive(child);
            }
        }
    }
}
