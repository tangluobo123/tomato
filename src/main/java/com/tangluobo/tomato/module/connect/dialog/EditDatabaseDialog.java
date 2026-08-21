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
 * 编辑数据库对话框：显示数据库名称（只读）、字符集、排序规则 + SQL预览标签
 */
public class EditDatabaseDialog {

    private Stage dialogStage;
    private boolean confirmed = false;

    private Label nameLabel;
    private ComboBox<String> charsetCombo;
    private ComboBox<String> collationCombo;
    private TextFlow sqlPreviewFlow;
    private String currentSql;

    private final ConnectionConfig config;
    private final String databaseName;

    private static final Set<String> SQL_KEYWORDS = Set.of(
        "ALTER", "DATABASE", "CHARACTER", "SET", "COLLATE", "ENCODING"
    );

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

    public EditDatabaseDialog(Stage parent, ConnectionConfig config, String databaseName, String currentCharset, String currentCollation) {
        this.config = config;
        this.databaseName = databaseName;
        initUI(parent, currentCharset, currentCollation);
    }

    private void initUI(Stage parent, String currentCharset, String currentCollation) {
        dialogStage = new Stage();
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.initOwner(parent);
        dialogStage.setTitle("编辑数据库 - " + databaseName);
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

        // 数据库名称（只读）
        Label dbLabel = new Label("数据库名称：");
        nameLabel = new Label(databaseName);
        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        // 字符集
        Label charsetLabel = new Label("字符集：");
        charsetCombo = new ComboBox<>();
        charsetCombo.getItems().add("(默认)");
        charsetCombo.getItems().addAll(CHARSET_COLLATIONS.keySet());
        charsetCombo.setMaxWidth(Double.MAX_VALUE);
        if (currentCharset != null && CHARSET_COLLATIONS.containsKey(currentCharset)) {
            charsetCombo.setValue(currentCharset);
        } else {
            charsetCombo.setValue("(默认)");
        }

        // 排序规则
        Label collationLabel = new Label("排序规则：");
        collationCombo = new ComboBox<>();
        collationCombo.getItems().add("(默认)");
        if (currentCharset != null && CHARSET_COLLATIONS.containsKey(currentCharset)) {
            collationCombo.getItems().addAll(CHARSET_COLLATIONS.get(currentCharset));
        }
        collationCombo.setMaxWidth(Double.MAX_VALUE);
        if (currentCollation != null && collationCombo.getItems().contains(currentCollation)) {
            collationCombo.setValue(currentCollation);
        } else {
            collationCombo.setValue("(默认)");
        }

        // 字符集变化时更新排序规则
        charsetCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            String currentColl = collationCombo.getValue();
            collationCombo.getItems().clear();
            collationCombo.getItems().add("(默认)");
            if (newVal != null && CHARSET_COLLATIONS.containsKey(newVal)) {
                collationCombo.getItems().addAll(CHARSET_COLLATIONS.get(newVal));
            }
            if (currentColl != null && collationCombo.getItems().contains(currentColl)) {
                collationCombo.setValue(currentColl);
            } else {
                collationCombo.setValue("(默认)");
            }
            updateSqlPreview();
        });

        collationCombo.valueProperty().addListener((obs, oldVal, newVal) -> updateSqlPreview());

        // 异步加载服务器字符集
        loadServerCharsets(currentCharset, currentCollation);

        generalBox.getChildren().addAll(dbLabel, nameLabel, charsetLabel, charsetCombo, collationLabel, collationCombo);
        generalTab.setContent(generalBox);

        // ---- SQL预览标签 ----
        Tab sqlTab = new Tab("SQL预览");
        VBox sqlBox = new VBox(10);
        sqlBox.setPadding(new Insets(15));

        sqlPreviewFlow = new TextFlow();
        sqlPreviewFlow.setStyle("-fx-background-color: white; -fx-padding: 8; -fx-background-radius: 4px; -fx-border-color: #ddd; -fx-border-width: 1; -fx-border-radius: 4px;");
        sqlPreviewFlow.setMinHeight(36);

        ContextMenu copyMenu = new ContextMenu();
        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> copySqlToClipboard());
        copyMenu.getItems().add(copyItem);
        sqlPreviewFlow.setOnContextMenuRequested(e -> copyMenu.show(sqlPreviewFlow, e.getScreenX(), e.getScreenY()));
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

        Button okBtn = new Button("保存");
        okBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 80px;");
        okBtn.setOnAction(e -> {
            confirmed = true;
            dialogStage.close();
        });

        buttons.getChildren().addAll(cancelBtn, okBtn);

        root.getChildren().addAll(tabPane, buttons);

        Scene scene = new Scene(root);
        dialogStage.setScene(scene);
        DialogPositionUtil.centerOnOwner(dialogStage, parent);

        updateSqlPreview();
    }

    private void loadServerCharsets(String currentCharset, String currentCollation) {
        new Thread(() -> {
            try {
                Map<String, List<String>> serverCharsets = DatabaseService.getCharsets(config);
                Platform.runLater(() -> {
                    charsetCombo.getItems().clear();
                    charsetCombo.getItems().add("(默认)");
                    for (Map.Entry<String, List<String>> entry : serverCharsets.entrySet()) {
                        charsetCombo.getItems().add(entry.getKey());
                        CHARSET_COLLATIONS.put(entry.getKey(), entry.getValue().toArray(new String[0]));
                    }
                    if (currentCharset != null && charsetCombo.getItems().contains(currentCharset)) {
                        charsetCombo.setValue(currentCharset);
                    } else {
                        charsetCombo.setValue("(默认)");
                    }
                    // 刷新排序规则列表
                    String ch = charsetCombo.getValue();
                    collationCombo.getItems().clear();
                    collationCombo.getItems().add("(默认)");
                    if (ch != null && CHARSET_COLLATIONS.containsKey(ch)) {
                        collationCombo.getItems().addAll(CHARSET_COLLATIONS.get(ch));
                    }
                    if (currentCollation != null && collationCombo.getItems().contains(currentCollation)) {
                        collationCombo.setValue(currentCollation);
                    } else {
                        collationCombo.setValue("(默认)");
                    }
                });
            } catch (Exception e) {
                // 加载失败使用本地默认列表
            }
        }, "DB-LoadCharsets").start();
    }

    private void copySqlToClipboard() {
        if (currentSql != null && !currentSql.isEmpty()) {
            ClipboardContent content = new ClipboardContent();
            content.putString(currentSql);
            Clipboard.getSystemClipboard().setContent(content);
        }
    }

    private void updateSqlPreview() {
        if (sqlPreviewFlow == null) return;
        String sql = generateSql();
        currentSql = sql;
        renderSqlPreview(sql);
    }

    private void renderSqlPreview(String sql) {
        sqlPreviewFlow.getChildren().clear();
        List<String> tokens = tokenizeSql(sql);
        for (String token : tokens) {
            Text text = new Text(token);
            text.setStyle("-fx-font-family: monospace; -fx-font-size: 13px;");
            String upperToken = token.toUpperCase();
            if (SQL_KEYWORDS.contains(upperToken)) {
                text.setFill(Color.valueOf("#0000FF"));
            } else if (token.equals(";")) {
                text.setFill(Color.valueOf("#333333"));
            } else if (token.startsWith("`") || token.startsWith("\"")) {
                text.setFill(Color.valueOf("#008000"));
            } else if (token.startsWith("'")) {
                text.setFill(Color.valueOf("#A31515"));
            } else {
                text.setFill(Color.valueOf("#000000"));
            }
            sqlPreviewFlow.getChildren().add(text);
        }
    }

    private List<String> tokenizeSql(String sql) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inBacktick = false, inDoubleQuote = false, inSingleQuote = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '`' && !inDoubleQuote && !inSingleQuote) { inBacktick = !inBacktick; current.append(c); }
            else if (c == '"' && !inBacktick && !inSingleQuote) { inDoubleQuote = !inDoubleQuote; current.append(c); }
            else if (c == '\'' && !inBacktick && !inDoubleQuote) { inSingleQuote = !inSingleQuote; current.append(c); }
            else if ((c == ' ' || c == ';') && !inBacktick && !inDoubleQuote && !inSingleQuote) {
                if (current.length() > 0) { tokens.add(current.toString()); current = new StringBuilder(); }
                tokens.add(c == ' ' ? " " : ";");
            } else { current.append(c); }
        }
        if (current.length() > 0) tokens.add(current.toString());
        return tokens;
    }

    private String generateSql() {
        String charset = charsetCombo != null ? charsetCombo.getValue() : "(默认)";
        String collation = collationCombo != null ? collationCombo.getValue() : "(默认)";

        StringBuilder sql = new StringBuilder();
        sql.append("ALTER DATABASE ");

        if (config.getType() == ConnectType.MYSQL) {
            sql.append("`").append(databaseName).append("`");
            if (!"(默认)".equals(charset)) {
                sql.append(" CHARACTER SET ").append(charset);
            }
            if (!"(默认)".equals(collation)) {
                sql.append(" COLLATE ").append(collation);
            }
        } else if (config.getType() == ConnectType.POSTGRESQL) {
            sql.append("\"").append(databaseName).append("\"");
            if (!"(默认)".equals(charset)) {
                sql.append(" ENCODING '").append(charset).append("'");
            }
        }

        sql.append(";");
        return sql.toString();
    }

    public String getCharset() {
        String val = charsetCombo != null ? charsetCombo.getValue() : null;
        return "(默认)".equals(val) ? null : val;
    }

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
