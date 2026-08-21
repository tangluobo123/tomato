package com.tangluobo.tomato.module.connect.dialog;

import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.service.DatabaseService;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.*;

/**
 * 新建数据库对话框：常规标签（名称、字符集、排序规则）+ SQL预览标签
 */
public class CreateDatabaseDialog {

    private Stage dialogStage;
    private boolean confirmed = false;

    private TextField nameField;
    private ComboBox<String> charsetCombo;
    private ComboBox<String> collationCombo;
    private TextFlow sqlPreviewFlow;
    private String currentSql;

    private final ConnectionConfig config;

    // SQL关键字（用于语法高亮）
    private static final Set<String> SQL_KEYWORDS = Set.of(
        "CREATE", "DATABASE", "CHARACTER", "SET", "COLLATE", "ENCODING", "LC_COLLATE", "USER", "IDENTIFIED", "BY"
    );

    // MySQL常见字符集及其对应排序规则
    private static final Map<String, String[]> CHARSET_COLLATIONS = new LinkedHashMap<>();
    static {
        CHARSET_COLLATIONS.put("utf8mb4", new String[]{"utf8mb4_general_ci", "utf8mb4_unicode_ci", "utf8mb4_0900_ai_ci", "utf8mb4_bin", "utf8mb4_unicode_520_ci"});
        CHARSET_COLLATIONS.put("utf8mb3", new String[]{"utf8mb3_general_ci", "utf8mb3_unicode_ci", "utf8mb3_bin"});
        CHARSET_COLLATIONS.put("utf8", new String[]{"utf8_general_ci", "utf8_unicode_ci", "utf8_bin"});
        CHARSET_COLLATIONS.put("latin1", new String[]{"latin1_swedish_ci", "latin1_general_ci", "latin1_general_cs", "latin1_bin"});
        CHARSET_COLLATIONS.put("ascii", new String[]{"ascii_general_ci", "ascii_bin"});
        CHARSET_COLLATIONS.put("gbk", new String[]{"gbk_chinese_ci", "gbk_bin"});
        CHARSET_COLLATIONS.put("gb2312", new String[]{"gb2312_chinese_ci", "gb2312_bin"});
        CHARSET_COLLATIONS.put("gb18030", new String[]{"gb18030_chinese_ci", "gb18030_bin", "gb18030_unicode_520_ci"});
        CHARSET_COLLATIONS.put("big5", new String[]{"big5_chinese_ci", "big5_bin"});
        CHARSET_COLLATIONS.put("euckr", new String[]{"euckr_korean_ci", "euckr_bin"});
        CHARSET_COLLATIONS.put("sjis", new String[]{"sjis_japanese_ci", "sjis_bin"});
        CHARSET_COLLATIONS.put("binary", new String[]{"binary"});
    }

    public CreateDatabaseDialog(Stage parent, ConnectionConfig config) {
        this.config = config;
        initUI(parent);
    }

    private void initUI(Stage parent) {
        dialogStage = new Stage();
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.initOwner(parent);
        dialogStage.setTitle("新建数据库");
        dialogStage.setResizable(false);

        VBox root = new VBox(10);
        root.setPadding(new Insets(15));
        root.setMinWidth(450);

        // 标签页
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.setStyle(
            "-fx-tab-min-height: 26px; -fx-tab-max-height: 26px;" +
            "-fx-border-width: 0; -fx-padding: 0;" +
            "-fx-tab-header-background-color: transparent;"
        );
        tabPane.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());

        // ---- 常规标签 ----
        Tab generalTab = new Tab("常规");
        VBox generalBox = new VBox(12);
        generalBox.setPadding(new Insets(15));

        // 数据库名称
        Label nameLabel = new Label("数据库名称：");
        nameField = new TextField();
        nameField.setPromptText("请输入数据库名称");
        nameField.textProperty().addListener((obs, oldVal, newVal) -> updateSqlPreview());

        // 字符集
        Label charsetLabel = new Label("字符集：");
        charsetCombo = new ComboBox<>();
        charsetCombo.getItems().add("(默认)");
        charsetCombo.getItems().addAll(CHARSET_COLLATIONS.keySet());
        charsetCombo.setValue("(默认)");
        charsetCombo.setMaxWidth(Double.MAX_VALUE);

        // 排序规则
        Label collationLabel = new Label("排序规则：");
        collationCombo = new ComboBox<>();
        collationCombo.getItems().add("(默认)");
        collationCombo.setValue("(默认)");
        collationCombo.setMaxWidth(Double.MAX_VALUE);

        // 字符集变化时更新排序规则列表
        charsetCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            collationCombo.getItems().clear();
            collationCombo.getItems().add("(默认)");
            if (newVal != null && CHARSET_COLLATIONS.containsKey(newVal)) {
                collationCombo.getItems().addAll(CHARSET_COLLATIONS.get(newVal));
            }
            collationCombo.setValue("(默认)");
            updateSqlPreview();
        });

        collationCombo.valueProperty().addListener((obs, oldVal, newVal) -> updateSqlPreview());

        // 异步加载服务器端字符集和排序规则
        loadServerCharsets();

        generalBox.getChildren().addAll(nameLabel, nameField, charsetLabel, charsetCombo, collationLabel, collationCombo);
        generalTab.setContent(generalBox);

        // ---- SQL预览标签 ----
        Tab sqlTab = new Tab("SQL预览");
        VBox sqlBox = new VBox(10);
        sqlBox.setPadding(new Insets(15));

        sqlPreviewFlow = new TextFlow();
        sqlPreviewFlow.setStyle("-fx-background-color: white; -fx-padding: 8; -fx-background-radius: 4px; -fx-border-color: #ddd; -fx-border-width: 1; -fx-border-radius: 4px;");
        sqlPreviewFlow.setMinHeight(36);

        // 右键菜单复制
        ContextMenu copyMenu = new ContextMenu();
        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> copySqlToClipboard());
        copyMenu.getItems().add(copyItem);
        sqlPreviewFlow.setOnContextMenuRequested(e -> copyMenu.show(sqlPreviewFlow, e.getScreenX(), e.getScreenY()));

        // Ctrl+C 快捷键复制
        sqlPreviewFlow.setFocusTraversable(true);
        sqlPreviewFlow.setOnKeyPressed(e -> {
            if (e.isControlDown() && e.getCode() == KeyCode.C) {
                copySqlToClipboard();
            }
        });

        sqlBox.getChildren().add(sqlPreviewFlow);
        sqlTab.setContent(sqlBox);

        tabPane.getTabs().addAll(generalTab, sqlTab);

        // ---- 按钮 ----
        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        buttons.setPadding(new Insets(5, 0, 0, 0));

        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 80px;");
        cancelBtn.setOnAction(e -> dialogStage.close());

        Button okBtn = new Button("确定");
        okBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 80px;");
        okBtn.setOnAction(e -> {
            if (nameField.getText().trim().isEmpty()) {
                return;
            }
            confirmed = true;
            dialogStage.close();
        });

        buttons.getChildren().addAll(cancelBtn, okBtn);

        root.getChildren().addAll(tabPane, buttons);

        Scene scene = new Scene(root);
        dialogStage.setScene(scene);
        DialogPositionUtil.centerOnOwner(dialogStage, parent);

        // 初始SQL预览
        updateSqlPreview();
    }

    /**
     * 复制SQL到剪贴板
     */
    private void copySqlToClipboard() {
        if (currentSql != null && !currentSql.isEmpty()) {
            ClipboardContent content = new ClipboardContent();
            content.putString(currentSql);
            Clipboard.getSystemClipboard().setContent(content);
        }
    }

    /**
     * 异步从服务器加载可用的字符集和排序规则
     */
    private void loadServerCharsets() {
        new Thread(() -> {
            try {
                Map<String, List<String>> serverCharsets = DatabaseService.getCharsets(config);
                Platform.runLater(() -> {
                    charsetCombo.getItems().clear();
                    charsetCombo.getItems().add("(默认)");
                    collationCombo.getItems().clear();
                    collationCombo.getItems().add("(默认)");

                    for (Map.Entry<String, List<String>> entry : serverCharsets.entrySet()) {
                        charsetCombo.getItems().add(entry.getKey());
                        CHARSET_COLLATIONS.put(entry.getKey(), entry.getValue().toArray(new String[0]));
                    }
                    charsetCombo.setValue("(默认)");
                    collationCombo.setValue("(默认)");
                });
            } catch (Exception e) {
                // 加载失败使用本地默认列表，不做额外处理
            }
        }, "DB-LoadCharsets").start();
    }

    private void updateSqlPreview() {
        if (sqlPreviewFlow == null) return;
        String sql = generateSql();
        currentSql = sql;
        renderSqlPreview(sql);
    }

    /**
     * SQL语法高亮渲染（白色背景）
     */
    private void renderSqlPreview(String sql) {
        sqlPreviewFlow.getChildren().clear();

        if (sql.startsWith("--")) {
            Text comment = new Text(sql);
            comment.setStyle("-fx-font-family: monospace; -fx-font-size: 13px;");
            comment.setFill(Color.valueOf("#6A9955"));
            sqlPreviewFlow.getChildren().add(comment);
            return;
        }

        List<String> tokens = tokenizeSql(sql);
        for (String token : tokens) {
            Text text = new Text(token);
            text.setStyle("-fx-font-family: monospace; -fx-font-size: 13px;");

            String upperToken = token.toUpperCase();
            if (SQL_KEYWORDS.contains(upperToken)) {
                // SQL关键字：蓝色
                text.setFill(Color.valueOf("#0000FF"));
            } else if (token.equals(";")) {
                // 分号：深灰
                text.setFill(Color.valueOf("#333333"));
            } else if (token.startsWith("`") || token.startsWith("\"")) {
                // 标识符：深绿
                text.setFill(Color.valueOf("#008000"));
            } else if (token.startsWith("'")) {
                // 字符串：橙红
                text.setFill(Color.valueOf("#A31515"));
            } else {
                // 普通文本：黑色
                text.setFill(Color.valueOf("#000000"));
            }
            sqlPreviewFlow.getChildren().add(text);
        }
    }

    /**
     * 将SQL字符串分割为token列表（保留空格和特殊字符）
     */
    private List<String> tokenizeSql(String sql) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inBacktick = false;
        boolean inDoubleQuote = false;
        boolean inSingleQuote = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if (c == '`' && !inDoubleQuote && !inSingleQuote) {
                inBacktick = !inBacktick;
                current.append(c);
            } else if (c == '"' && !inBacktick && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                current.append(c);
            } else if (c == '\'' && !inBacktick && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                current.append(c);
            } else if ((c == ' ' || c == ';' || c == '(' || c == ')') && !inBacktick && !inDoubleQuote && !inSingleQuote) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current = new StringBuilder();
                }
                if (c != ' ') {
                    tokens.add(String.valueOf(c));
                } else {
                    tokens.add(" ");
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    private String generateSql() {
        String name = nameField != null ? nameField.getText().trim() : "";
        if (name.isEmpty()) {
            return "-- 请输入数据库名称";
        }

        String charset = charsetCombo != null ? charsetCombo.getValue() : "(默认)";
        String collation = collationCombo != null ? collationCombo.getValue() : "(默认)";

        StringBuilder sql = new StringBuilder();
        sql.append("CREATE DATABASE ");

        if (config.getType() == ConnectType.MYSQL) {
            sql.append("`").append(name).append("`");
            if (!"(默认)".equals(charset)) {
                sql.append(" CHARACTER SET ").append(charset);
            }
            if (!"(默认)".equals(collation)) {
                sql.append(" COLLATE ").append(collation);
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            sql.append("\"").append(name).append("\"");
            if (!"(默认)".equals(charset)) {
                sql.append(" ENCODING '").append(charset).append("'");
            }
            if (!"(默认)".equals(collation)) {
                sql.append(" LC_COLLATE '").append(collation).append("'");
            }
        } else if (config.getType() == ConnectType.ORACLE) {
            sql.append("\"").append(name).append("\"");
        }

        sql.append(";");
        return sql.toString();
    }

    /**
     * 获取生成的SQL语句
     */
    public String getSql() {
        return generateSql();
    }

    /**
     * 获取数据库名称
     */
    public String getDatabaseName() {
        return confirmed && nameField != null ? nameField.getText().trim() : null;
    }

    /**
     * 获取选中的字符集
     */
    public String getCharset() {
        String val = charsetCombo != null ? charsetCombo.getValue() : null;
        return "(默认)".equals(val) ? null : val;
    }

    /**
     * 获取选中的排序规则
     */
    public String getCollation() {
        String val = collationCombo != null ? collationCombo.getValue() : null;
        return "(默认)".equals(val) ? null : val;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public void showAndWait() {
        dialogStage.showAndWait();
    }
}
