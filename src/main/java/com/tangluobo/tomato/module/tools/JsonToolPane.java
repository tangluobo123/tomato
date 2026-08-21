package com.tangluobo.tomato.module.tools;

import com.google.gson.*;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.util.Duration;
import org.fxmisc.richtext.InlineCssTextArea;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.fxmisc.flowless.VirtualizedScrollPane;

import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JsonToolPane extends VBox {

    private static final String STYLE_KEY = "-fx-fill: #0451A5; -fx-font-weight: bold;";
    private static final String STYLE_STRING = "-fx-fill: #067D17;";
    private static final String STYLE_NUMBER = "-fx-fill: #098658; -fx-font-weight: bold;";
    private static final String STYLE_BOOLEAN = "-fx-fill: #0000FF; -fx-font-weight: bold;";
    private static final String STYLE_NULL = "-fx-fill: #808080; -fx-font-weight: bold;";
    private static final String STYLE_BRACE = "-fx-fill: #000000; -fx-font-weight: bold;";
    private static final String STYLE_DEFAULT = "-fx-fill: #000000;";
    private static final String STYLE_FOLDED = "-fx-fill: #808080; -fx-font-style: italic;";
    private static final String STYLE_COMMA = "-fx-fill: #000000;";

    private TextArea inputArea;
    private TextArea rawOutputArea;
    private InlineCssTextArea jsonRichArea;
    private VirtualizedScrollPane<InlineCssTextArea> jsonScrollPane;
    private Label statusLabel;
    private SplitPane splitPane;
    private StackPane outputPane;

    // History
    private static final int MAX_HISTORY = 50;
    private static final String HISTORY_FILE = System.getProperty("user.home") + File.separator + ".tomata" + File.separator + "json_history.json";
    private ListView<JsonHistoryItem> historyListView;
    private ObservableList<JsonHistoryItem> historyData;
    // 历史记录分隔条拖拽状态
    private double historyDividerStartX;
    private double historyDividerStartWidth;

    // Fold state
    private Set<String> foldedIds = new HashSet<>();
    // Each rendered paragraph -> metadata (for fold arrow and copy button)
    private List<LineMeta> lineMetaList = new ArrayList<>();
    // Paragraph graphic info map: paragraph index -> graphic components
    private Map<Integer, ParagraphGraphicInfo> paragraphGraphics = new HashMap<>();
    // Paragraph graphic factory
    private ParagraphGraphicFactory graphicFactory;
    // Gson
    private final Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
    private final Gson compactGson = new Gson();

    public JsonToolPane() {
        initializeUI();
        Platform.runLater(this::loadHistory);
    }

    // ============================================================
    // UI BUILDING
    // ============================================================

    private void initializeUI() {
        setStyle("-fx-background-color: #ffffff;");
        setFillWidth(true);
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);

        // 加载统一样式表，使 SplitPane 分隔条使用 #E5E5E5 细线样式
        getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());

        // 自定义标题栏
        HBox titleBar = new HBox(10);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(14, 20, 10, 20));
        titleBar.setStyle("-fx-background-color: #f7f8fa;");
        SVGPath titleIcon = new SVGPath();
        titleIcon.setContent("M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm-1 7V3.5L18.5 9H13zM6 20V4h5v7h7v9H6zm2-6h8v2H8v-2zm0 4h5v2H8v-2z");
        titleIcon.setFill(Color.web("#1976D2"));
        titleIcon.setScaleX(0.75);
        titleIcon.setScaleY(0.75);
        Label titleLabel = new Label("JSON处理工具");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333;");
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        Label subtitleLabel = new Label("格式化、压缩、编码转换、转义处理");
        subtitleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #999;");
        titleBar.getChildren().addAll(titleIcon, titleLabel, titleSpacer, subtitleLabel);

        // 操作按钮区
        HBox buttonBar = new HBox(10);
        buttonBar.setPadding(new Insets(8, 20, 10, 20));
        buttonBar.setAlignment(Pos.CENTER_LEFT);

        Button formatBtn = createButton("格式化", "#4CAF50");
        formatBtn.setOnAction(e -> formatJSON());

        Button minifyBtn = createButton("压缩", "#4CAF50");
        minifyBtn.setOnAction(e -> minifyJSON());

        Button encodeBtn = createButton("中文转Unicode", "#4CAF50");
        encodeBtn.setOnAction(e -> convertChineseToUnicode());

        Button decodeBtn = createButton("Unicode转中文", "#4CAF50");
        decodeBtn.setOnAction(e -> convertUnicodeToChinese());

        Button addEscapeBtn = createButton("添加转义", "#4CAF50");
        addEscapeBtn.setOnAction(e -> addEscape());

        Button removeEscapeBtn = createButton("去除转义", "#4CAF50");
        removeEscapeBtn.setOnAction(e -> removeEscape());

        buttonBar.getChildren().addAll(formatBtn, minifyBtn, encodeBtn, decodeBtn, addEscapeBtn, removeEscapeBtn);

        // 历史记录面板
        VBox historyPanel = createHistoryPanel();

        // 输入+输出 面板
        VBox inputPanel = createInputPanel();
        VBox outputPanel = createOutputPanel();

        // 内部分割：输入 | 输出
        splitPane = new SplitPane();
        splitPane.setPadding(new Insets(0));
        splitPane.getItems().addAll(inputPanel, outputPanel);
        splitPane.setDividerPositions(0.5);
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        // 外部分割：历史 | (输入+输出) —— 使用 Region 分隔条，与连接树/内容页分隔样式一致
        HBox contentBox = new HBox();
        contentBox.setPadding(new Insets(0));
        contentBox.setStyle("-fx-background-insets: 0; -fx-padding: 0;");

        // 分隔条：1px 宽，#E5E5E5，可拖拽
        Region historyDivider = new Region();
        historyDivider.setStyle("-fx-background-color: #E5E5E5;");
        historyDivider.setPrefWidth(1.0);
        historyDivider.setMaxWidth(1.0);
        historyDivider.setMinWidth(1.0);
        historyDivider.setCursor(Cursor.H_RESIZE);
        setupHistoryDivider(historyDivider, historyPanel);

        contentBox.getChildren().addAll(historyPanel, historyDivider, splitPane);
        HBox.setHgrow(splitPane, Priority.ALWAYS);

        // 标题+按钮与内容区之间的分隔线
        Separator topSeparator = new Separator();
        topSeparator.setStyle("-fx-background-color: #E5E5E5;");
        topSeparator.setPrefHeight(1);
        topSeparator.setMaxHeight(1);
        topSeparator.setMinHeight(1);

        // 状态标签
        statusLabel = new Label("");
        statusLabel.setPadding(new Insets(5, 20, 10, 20));
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");

        getChildren().addAll(titleBar, buttonBar, topSeparator, contentBox, statusLabel);
        VBox.setVgrow(contentBox, Priority.ALWAYS);
    }

    private Button createButton(String text, String color) {
        Button btn = new Button(text);
        btn.setStyle(String.format("-fx-background-color: %s; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6 14; -fx-background-radius: 4; -fx-cursor: hand;", color));
        return btn;
    }

    /**
     * 设置历史记录分隔条的拖拽行为，与连接树/内容页分隔条逻辑一致。
     */
    private void setupHistoryDivider(Region divider, VBox historyPanel) {
        divider.setOnMouseEntered(e -> divider.setCursor(Cursor.H_RESIZE));
        divider.setOnMouseExited(e -> divider.setCursor(Cursor.DEFAULT));

        divider.setOnMousePressed(e -> {
            historyDividerStartX = e.getScreenX();
            historyDividerStartWidth = historyPanel.getWidth();
        });

        divider.setOnMouseDragged(e -> {
            double deltaX = e.getScreenX() - historyDividerStartX;
            double newWidth = historyDividerStartWidth + deltaX;
            if (newWidth >= 120 && newWidth <= 400) {
                historyPanel.setPrefWidth(newWidth);
                historyPanel.setMinWidth(newWidth);
            }
        });
    }

    private VBox createInputPanel() {
        VBox panel = new VBox(8);
        panel.setFillWidth(true);

        Label lbl = new Label("JSON输入");
        lbl.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");

        inputArea = new TextArea();
        inputArea.setPromptText("请输入JSON数据");
        inputArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px; " +
                "-fx-focus-color: transparent; -fx-faint-focus-color: transparent;");
        VBox.setVgrow(inputArea, Priority.ALWAYS);

        panel.getChildren().addAll(lbl, inputArea);
        return panel;
    }

    private VBox createOutputPanel() {
        VBox panel = new VBox(8);
        panel.setFillWidth(true);

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label lbl = new Label("JSON输出");
        lbl.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");

        Button copyBtn = new Button("复制全部");
        copyBtn.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 4 10; -fx-background-radius: 4; -fx-cursor: hand;");
        copyBtn.setOnAction(e -> copyOutput());

        Button expandAllBtn = new Button("展开全部");
        expandAllBtn.setStyle("-fx-background-color: #FF9800; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 4 10; -fx-background-radius: 4; -fx-cursor: hand;");
        expandAllBtn.setOnAction(e -> expandAll());

        Button collapseAllBtn = new Button("折叠全部");
        collapseAllBtn.setStyle("-fx-background-color: #9C27B0; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 4 10; -fx-background-radius: 4; -fx-cursor: hand;");
        collapseAllBtn.setOnAction(e -> collapseAll());

        header.getChildren().addAll(lbl, spacer, expandAllBtn, collapseAllBtn, copyBtn);

        // 输出容器
        outputPane = new StackPane();
        VBox.setVgrow(outputPane, Priority.ALWAYS);

        // Rich text JSON area
        jsonRichArea = new InlineCssTextArea();
        jsonRichArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 12px; " +
                "-fx-background-color: white; -fx-padding: 0 0 0 0; -fx-text-fill: #000;");
        jsonRichArea.setEditable(false);

        // Set paragraph graphic factory for line numbers, fold arrows, copy buttons
        graphicFactory = new ParagraphGraphicFactory();
        jsonRichArea.setParagraphGraphicFactory(graphicFactory);

        jsonScrollPane = new VirtualizedScrollPane<>(jsonRichArea);

        // 原始文本输出
        rawOutputArea = new TextArea();
        rawOutputArea.setEditable(false);
        rawOutputArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px;");

        outputPane.getChildren().addAll(jsonScrollPane);

        panel.getChildren().addAll(header, outputPane);
        return panel;
    }

    // ============================================================
    // HISTORY PANEL
    // ============================================================

    /**
     * Simple data class for a history item.
     */
    private static class JsonHistoryItem {
        final String jsonText;
        final String timestamp;
        final String summary;

        JsonHistoryItem(String jsonText, String timestamp, String summary) {
            this.jsonText = jsonText;
            this.timestamp = timestamp;
            this.summary = summary;
        }

        @Override
        public String toString() {
            return timestamp + "  " + summary;
        }
    }

    private VBox createHistoryPanel() {
        VBox panel = new VBox(6);
        panel.setFillWidth(true);
        panel.setStyle("-fx-background-color: #fafafa;");
        panel.setMinWidth(150);
        panel.setPrefWidth(180);

        // Header
        HBox header = new HBox(6);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 10, 4, 10));

        Label lbl = new Label("历史记录");
        lbl.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #555;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button clearBtn = new Button("清空");
        clearBtn.setStyle("-fx-background-color: #f44336; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 3 8; -fx-background-radius: 3; -fx-cursor: hand;");
        clearBtn.setOnAction(e -> clearHistory());

        header.getChildren().addAll(lbl, spacer, clearBtn);

        // History list
        historyData = FXCollections.observableArrayList();
        historyListView = new ListView<>(historyData);
        historyListView.setStyle("-fx-background-color: #fafafa; -fx-border-color: transparent; -fx-font-size: 11px;");
        historyListView.setEditable(false);
        historyListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(JsonHistoryItem item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString());
                setGraphic(null);
            }
        });
        VBox.setVgrow(historyListView, Priority.ALWAYS);

        // Click to restore
        historyListView.setOnMouseClicked(event -> {
            JsonHistoryItem selected = historyListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                restoreFromHistory(selected);
            }
        });

        // Context menu for delete
        ContextMenu contextMenu = new ContextMenu();
        MenuItem deleteItem = new MenuItem("删除此记录");
        deleteItem.setOnAction(e -> {
            JsonHistoryItem selected = historyListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                removeFromHistory(selected);
            }
        });
        MenuItem copyItem = new MenuItem("复制JSON");
        copyItem.setOnAction(e -> {
            JsonHistoryItem selected = historyListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                ClipboardContent content = new ClipboardContent();
                content.putString(selected.jsonText);
                Clipboard.getSystemClipboard().setContent(content);
                showSuccess("已复制到剪贴板！");
            }
        });
        contextMenu.getItems().addAll(copyItem, deleteItem);

        historyListView.setContextMenu(contextMenu);

        panel.getChildren().addAll(header, historyListView);
        return panel;
    }

    private void addToHistory(String json) {
        if (json == null || json.trim().isEmpty()) return;

        // Don't add duplicates (exact match)
        for (JsonHistoryItem item : historyData) {
            if (item.jsonText.equals(json)) return;
        }

        // Create summary (compressed, truncated)
        String summary = json.replaceAll("\\s+", " ").trim();
        if (summary.length() > 40) {
            summary = summary.substring(0, 40) + "...";
        }

        String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
        JsonHistoryItem item = new JsonHistoryItem(json, timestamp, summary);

        // Add to the beginning
        historyData.add(0, item);

        // Trim to max size
        while (historyData.size() > MAX_HISTORY) {
            historyData.remove(historyData.size() - 1);
        }

        // Persist to file
        saveHistory();
    }

    private void restoreFromHistory(JsonHistoryItem item) {
        if (item == null) return;
        inputArea.setText(item.jsonText);
        formatJSON();
        showSuccess("已从历史恢复！");
    }

    private void removeFromHistory(JsonHistoryItem item) {
        historyData.remove(item);
        saveHistory();
    }

    private void clearHistory() {
        if (!historyData.isEmpty()) {
            historyData.clear();
            saveHistory();
            showSuccess("历史记录已清空");
        }
    }

    // ============================================================
    // HISTORY PERSISTENCE
    // ============================================================

    private void loadHistory() {
        try {
            File file = new File(HISTORY_FILE);
            if (!file.exists()) {
                return;
            }
            String json = new String(Files.readAllBytes(file.toPath()));
            if (json == null || json.trim().isEmpty()) {
                return;
            }
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonArray()) {
                return;
            }
            JsonArray array = root.getAsJsonArray();
            for (JsonElement elem : array) {
                if (!elem.isJsonObject()) continue;
                JsonObject obj = elem.getAsJsonObject();
                String jsonText = obj.has("json") ? obj.get("json").getAsString() : "";
                String timestamp = obj.has("timestamp") ? obj.get("timestamp").getAsString() : "";
                String summary = obj.has("summary") ? obj.get("summary").getAsString() : "";
                if (!jsonText.isEmpty()) {
                    historyData.add(new JsonHistoryItem(jsonText, timestamp, summary));
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load JSON history: " + e.getMessage());
        }
    }

    private void saveHistory() {
        try {
            File file = new File(HISTORY_FILE);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            JsonArray array = new JsonArray();
            for (JsonHistoryItem item : historyData) {
                JsonObject obj = new JsonObject();
                obj.addProperty("json", item.jsonText);
                obj.addProperty("timestamp", item.timestamp);
                obj.addProperty("summary", item.summary);
                array.add(obj);
            }
            Files.write(file.toPath(), prettyGson.toJson(array).getBytes());
        } catch (Exception e) {
            System.err.println("Failed to save JSON history: " + e.getMessage());
        }
    }

    // ============================================================
    // OUTPUT MODES
    // ============================================================

    private void showTreeView(String jsonStr) {
        try {
            JsonElement root = JsonParser.parseString(jsonStr.trim());
            renderFoldableJson(root);
            outputPane.getChildren().clear();
            outputPane.getChildren().add(jsonScrollPane);
        } catch (Exception e) {
            showRawView("JSON 格式错误: " + e.getMessage());
        }
    }

    private void showRawView(String text) {
        rawOutputArea.setText(text);
        outputPane.getChildren().clear();
        outputPane.getChildren().add(rawOutputArea);
    }

    // ============================================================
    // FOLDABLE JSON RENDERING
    // ============================================================

    /**
     * Metadata per rendered line.
     */
    private static class LineMeta {
        /** If true, this line starts a foldable container. */
        boolean isContainerStart;
        /** Node id for fold state tracking. */
        String nodeId;
        /** JsonElement reference for COPY feature. */
        JsonElement element;
        /** Key name (for building copy content). */
        String keyName;
        /** True if this container is currently folded. */
        boolean isFolded;
        /** Container type: "object", "array", or null for leaf. */
        String containerType;
        /** For unfolded container: the total count of children for folded summary. */
        int childCount;

        LineMeta() {}
    }

    private static final int UNIQUE_SALT_BASE = 1_000_000;

    private void renderFoldableJson(JsonElement root) {
        lineMetaList.clear();
        paragraphGraphics.clear();

        // 1. Build display lines + metadata
        StringBuilder sb = new StringBuilder();
        buildLines(root, "", null, 0, sb, true, true, new int[]{UNIQUE_SALT_BASE});

        // Remove trailing newline if any
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }

        // 2. Set text (this will trigger paragraph graphic factory to create new graphics)
        jsonRichArea.replaceText(sb.toString());

        // 3. Apply syntax highlighting
        applySyntaxHighlighting();

        // 4. Store line metadata for the graphic factory to use
        graphicFactory.setLineMetaList(lineMetaList);
    }

    /**
     * Recursively build lines for a JSON element.
     *
     * @param element       element to render
     * @param keyPrefix     the "key: " prefix for object children, "" if none
     * @param explicitKey   key name (without prefix) for metadata, null if root
     * @param depth         current depth (0 = root)
     * @param sb            string builder to append lines to
     * @param isLast        whether this is the last child of parent (affects trailing comma)
     * @param isRootOrValue if true, this element is the root or a value (not child of object)
     * @param idCounter     counter for unique IDs
     * @return The nodeId assigned to this element (if container), else null
     */
    private String buildLines(JsonElement element, String keyPrefix, String explicitKey,
                              int depth, StringBuilder sb, boolean isLast, boolean isRootOrValue,
                              int[] idCounter) {
        String indent = "  ".repeat(depth);

        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            String nodeId = "o_" + (++idCounter[0]);
            boolean isFolded = foldedIds.contains(nodeId);
            int childCount = obj.size();

            sb.append(indent);
            // Metadata for line: container start
            LineMeta meta = new LineMeta();
            meta.isContainerStart = true;
            meta.nodeId = nodeId;
            meta.element = element;
            meta.keyName = explicitKey;
            meta.isFolded = isFolded;
            meta.containerType = "object";
            meta.childCount = childCount;
            lineMetaList.add(meta);

            // Line content: keyPrefix + { ... } or keyPrefix + { + children + }
            if (!keyPrefix.isEmpty()) {
                // Append key with syntax marking: we'll highlight via regex later,
                // but use distinct delimiters that we can match.
                sb.append("__KEY__").append(jsonEscapeKey(explicitKey)).append("__KEY__").append(": ");
            }
            sb.append("{");
            if (isFolded) {
                // Folded: show "  ... n items  }" summary on same line
                sb.append(" ").append("__FOLDED__").append("/* ").append(childCount).append(childCount == 1 ? " field */" : " fields */").append("__FOLDED__").append(" }");
                if (!isLast) sb.append(",");
                sb.append("\n");
            } else {
                sb.append("\n");
                // Render children
                int idx = 0;
                List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(obj.entrySet());
                for (Map.Entry<String, JsonElement> e : entries) {
                    boolean childLast = (idx == entries.size() - 1);
                    idx++;
                    buildLines(e.getValue(), "__KEY__" + jsonEscapeKey(e.getKey()) + "__KEY__: ",
                            e.getKey(), depth + 1, sb, childLast, false, idCounter);
                }
                // Close brace on its own line
                LineMeta closeMeta = new LineMeta();
                closeMeta.isContainerStart = false;
                lineMetaList.add(closeMeta);
                sb.append(indent).append("}");
                if (!isLast) sb.append(",");
                sb.append("\n");
            }
            return nodeId;
        } else if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            String nodeId = "a_" + (++idCounter[0]);
            boolean isFolded = foldedIds.contains(nodeId);
            int childCount = arr.size();

            sb.append(indent);
            LineMeta meta = new LineMeta();
            meta.isContainerStart = true;
            meta.nodeId = nodeId;
            meta.element = element;
            meta.keyName = explicitKey;
            meta.isFolded = isFolded;
            meta.containerType = "array";
            meta.childCount = childCount;
            lineMetaList.add(meta);

            if (!keyPrefix.isEmpty()) {
                sb.append("__KEY__").append(jsonEscapeKey(explicitKey)).append("__KEY__").append(": ");
            }
            sb.append("[");
            if (isFolded) {
                sb.append(" ").append("__FOLDED__").append("/* ").append(childCount).append(childCount == 1 ? " item */" : " items */").append("__FOLDED__").append(" ]");
                if (!isLast) sb.append(",");
                sb.append("\n");
            } else {
                sb.append("\n");
                for (int i = 0; i < arr.size(); i++) {
                    boolean childLast = (i == arr.size() - 1);
                    buildLines(arr.get(i), "", "[" + i + "]", depth + 1, sb, childLast, false, idCounter);
                }
                LineMeta closeMeta = new LineMeta();
                closeMeta.isContainerStart = false;
                lineMetaList.add(closeMeta);
                sb.append(indent).append("]");
                if (!isLast) sb.append(",");
                sb.append("\n");
            }
            return nodeId;
        } else {
            // Leaf
            LineMeta meta = new LineMeta();
            meta.isContainerStart = false;
            meta.element = element;
            meta.keyName = explicitKey;
            lineMetaList.add(meta);

            sb.append(indent);
            if (!keyPrefix.isEmpty()) {
                sb.append("__KEY__").append(jsonEscapeKey(explicitKey)).append("__KEY__").append(": ");
            }
            if (element.isJsonPrimitive()) {
                JsonPrimitive p = element.getAsJsonPrimitive();
                if (p.isString()) {
                    sb.append("__STR__\"").append(jsonEscapeString(p.getAsString())).append("\"__STR__");
                } else if (p.isBoolean()) {
                    sb.append("__BOOL__").append(p.getAsString()).append("__BOOL__");
                } else if (p.isNumber()) {
                    sb.append("__NUM__").append(p.getAsString()).append("__NUM__");
                } else {
                    sb.append(p.getAsString());
                }
            } else if (element.isJsonNull()) {
                sb.append("__NULL__").append("null").append("__NULL__");
            }
            if (!isLast) sb.append(",");
            sb.append("\n");
            return null;
        }
    }

    private static String jsonEscapeKey(String s) {
        return "\"" + jsonEscapeString(s) + "\"";
    }

    private static String jsonEscapeString(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    // ============================================================
    // PARAGRAPH GRAPHICS (line number + fold arrow + copy button)
    // ============================================================

    /**
     * Info holder for paragraph graphic components, allowing dynamic updates.
     */
    private static class ParagraphGraphicInfo {
        final HBox container;
        final Label lineNoLabel;
        final Label arrowLabel;
        final Label copyLabel;
        final Region arrowPlaceholder;
        final Region copyPlaceholder;
        LineMeta meta;
        int lineNumber;

        ParagraphGraphicInfo(HBox container, Label lineNoLabel, Label arrowLabel,
                             Label copyLabel, Region arrowPlaceholder, Region copyPlaceholder) {
            this.container = container;
            this.lineNoLabel = lineNoLabel;
            this.arrowLabel = arrowLabel;
            this.copyLabel = copyLabel;
            this.arrowPlaceholder = arrowPlaceholder;
            this.copyPlaceholder = copyPlaceholder;
        }
    }

    /**
     * RichTextFX paragraph graphic factory that creates gutter components for each paragraph.
     * Uses native API to ensure perfect alignment with text lines.
     */
    private class ParagraphGraphicFactory implements java.util.function.IntFunction<Node> {
        private List<LineMeta> metaList = new ArrayList<>();

        void setLineMetaList(List<LineMeta> list) {
            this.metaList = list;
        }

        @Override
        public Node apply(int paragraphIndex) {
            return createGraphic(paragraphIndex);
        }

        /**
         * Create graphic for a specific paragraph index.
         * Since setParagraphGraphicFactory calls this for each paragraph,
         * we track created graphics by paragraph index.
         */
        Node createGraphic(int paragraphIndex) {
            // Retrieve or create info
            ParagraphGraphicInfo info = paragraphGraphics.get(paragraphIndex);
            if (info != null) {
                updateGraphic(info);
                return info.container;
            }

            HBox box = new HBox(2);
            box.setAlignment(Pos.CENTER_LEFT);
            box.setPrefWidth(70);
            box.setMinWidth(70);
            box.setMaxWidth(70);
            box.setStyle("-fx-background-color: transparent;");

            // Line number
            Label lineNo = new Label();
            lineNo.setPrefWidth(38);
            lineNo.setMinWidth(38);
            lineNo.setMaxWidth(38);
            lineNo.setStyle("-fx-text-fill: #aaaaaa; -fx-alignment: CENTER_RIGHT; -fx-padding: 0 4 0 0;");
            lineNo.setAlignment(Pos.CENTER_RIGHT);

            // Arrow (or placeholder)
            Label arrow = new Label();
            arrow.setPrefWidth(16);
            arrow.setMinWidth(16);
            arrow.setMaxWidth(16);

            // Copy (or placeholder)
            Label copy = new Label();
            copy.setPrefWidth(18);
            copy.setMinWidth(18);
            copy.setMaxWidth(18);

            // Placeholders for non-container lines
            Region arrowPh = new Region();
            arrowPh.setPrefWidth(16);
            arrowPh.setMinWidth(16);
            arrowPh.setMaxWidth(16);

            Region copyPh = new Region();
            copyPh.setPrefWidth(18);
            copyPh.setMinWidth(18);
            copyPh.setMaxWidth(18);

            info = new ParagraphGraphicInfo(box, lineNo, arrow, copy, arrowPh, copyPh);
            info.lineNumber = paragraphIndex + 1;
            paragraphGraphics.put(paragraphIndex, info);

            updateGraphic(info);
            return box;
        }

        /**
         * Update graphic based on current metadata.
         */
        void updateGraphic(ParagraphGraphicInfo info) {
            int idx = info.lineNumber - 1;
            if (idx >= 0 && idx < metaList.size()) {
                info.meta = metaList.get(idx);
            }
            info.lineNoLabel.setText(String.valueOf(info.lineNumber));

            if (info.meta != null && info.meta.isContainerStart) {
                // Container line: show arrow and copy button
                info.arrowLabel.setText(info.meta.isFolded ? "▶" : "▼");
                info.arrowLabel.setStyle("-fx-text-fill: #808080; -fx-cursor: hand; -fx-alignment: CENTER;");
                info.arrowLabel.setOnMouseClicked(ev -> toggleFold(info));
                info.arrowLabel.setOnMouseEntered(ev -> info.arrowLabel.setStyle("-fx-text-fill: #1976D2; -fx-cursor: hand; -fx-alignment: CENTER;"));
                info.arrowLabel.setOnMouseExited(ev -> info.arrowLabel.setStyle("-fx-text-fill: #808080; -fx-cursor: hand; -fx-alignment: CENTER;"));

                info.copyLabel.setText("📋");
                info.copyLabel.setStyle("-fx-text-fill: #909090; -fx-cursor: hand; -fx-alignment: CENTER;");
                info.copyLabel.setTooltip(new Tooltip("复制节点为JSON"));
                info.copyLabel.setOnMouseClicked(ev -> copyNodeToClipboard(info.meta));
                info.copyLabel.setOnMouseEntered(ev -> info.copyLabel.setStyle("-fx-text-fill: #1976D2; -fx-cursor: hand; -fx-alignment: CENTER;"));
                info.copyLabel.setOnMouseExited(ev -> info.copyLabel.setStyle("-fx-text-fill: #909090; -fx-cursor: hand; -fx-alignment: CENTER;"));

                info.container.getChildren().clear();
                info.container.getChildren().addAll(info.lineNoLabel, info.arrowLabel, info.copyLabel);
            } else {
                // Non-container line: show placeholders
                info.container.getChildren().clear();
                info.container.getChildren().addAll(info.lineNoLabel, info.arrowPlaceholder, info.copyPlaceholder);
            }
        }
    }

    private void toggleFold(ParagraphGraphicInfo info) {
        if (info.meta == null || info.meta.nodeId == null) return;
        if (info.meta.isFolded) {
            foldedIds.remove(info.meta.nodeId);
        } else {
            foldedIds.add(info.meta.nodeId);
        }
        JsonElement root = findRootElement();
        if (root != null) {
            renderFoldableJson(root);
        }
    }

    private JsonElement findRootElement() {
        try {
            String input = inputArea.getText().trim();
            if (input.isEmpty()) return null;
            return JsonParser.parseString(input);
        } catch (Exception e) {
            return null;
        }
    }

    private void copyNodeToClipboard(LineMeta meta) {
        if (meta.element == null) {
            showError("节点数据丢失");
            return;
        }
        try {
            String json;
            if (meta.element.isJsonObject() || meta.element.isJsonArray()) {
                json = prettyGson.toJson(meta.element);
            } else {
                json = compactGson.toJson(meta.element);
            }
            ClipboardContent content = new ClipboardContent();
            content.putString(json);
            Clipboard.getSystemClipboard().setContent(content);
            showSuccess("已复制节点JSON到剪贴板！");
        } catch (Exception e) {
            showError("复制失败: " + e.getMessage());
        }
    }

    // ============================================================
    // SYNTAX HIGHLIGHTING
    // ============================================================

    // Pattern for our placeholders
    private static final Pattern HIGHLIGHT_PATTERN = Pattern.compile(
            "__KEY__" + "(.*?)" + "__KEY__" +
            "|__STR__" + "(.*?)" + "__STR__" +
            "|__NUM__" + "(-?\\d+\\.?\\d*(?:[eE][+-]?\\d+)?)" + "__NUM__" +
            "|__BOOL__" + "(true|false)" + "__BOOL__" +
            "|__NULL__" + "(null)" + "__NULL__" +
            "|__FOLDED__" + "(.*?)" + "__FOLDED__" +
            "|__COMMA__"
    );

    private void applySyntaxHighlighting() {
        String text = jsonRichArea.getText();
        if (text == null || text.isEmpty()) return;

        StyleSpansBuilder<String> spans = new StyleSpansBuilder<>();
        Matcher m = HIGHLIGHT_PATTERN.matcher(text);
        int lastEnd = 0;

        try {
            while (m.find()) {
                int start = m.start();
                int end = m.end();
                if (start > lastEnd) {
                    // Non-matched text (braces, brackets, whitespace, commas left outside placeholders)
                    String middle = text.substring(lastEnd, start);
                    applySpanForPlain(middle, spans);
                }

                if (m.group(1) != null) {
                    // KEY: group(1) is inner key (including quotes)
                    String keyContent = m.group(1);
                    // The entire placeholder: __KEY__"key"__KEY__
                    // We want to apply key style to just the key content.
                    // For simplicity, apply STYLE_KEY to all matched chars but with proper prefix lengths.
                    // Use precise approach:
                    int p1 = "__KEY__".length();
                    int p2 = "__KEY__".length();
                    // 6 chars before, 7 chars after
                    spans.add(STYLE_DEFAULT, p1); // placeholder prefix
                    spans.add(STYLE_KEY, keyContent.length()); // key text
                    spans.add(STYLE_DEFAULT, p2); // placeholder suffix
                } else if (m.group(2) != null) {
                    String val = m.group(2);
                    spans.add(STYLE_DEFAULT, "__STR__".length());
                    spans.add(STYLE_STRING, val.length());
                    spans.add(STYLE_DEFAULT, "__STR__".length());
                } else if (m.group(3) != null) {
                    String val = m.group(3);
                    spans.add(STYLE_DEFAULT, "__NUM__".length());
                    spans.add(STYLE_NUMBER, val.length());
                    spans.add(STYLE_DEFAULT, "__NUM__".length());
                } else if (m.group(4) != null) {
                    String val = m.group(4);
                    spans.add(STYLE_DEFAULT, "__BOOL__".length());
                    spans.add(STYLE_BOOLEAN, val.length());
                    spans.add(STYLE_DEFAULT, "__BOOL__".length());
                } else if (m.group(5) != null) {
                    String val = m.group(5);
                    spans.add(STYLE_DEFAULT, "__NULL__".length());
                    spans.add(STYLE_NULL, val.length());
                    spans.add(STYLE_DEFAULT, "__NULL__".length());
                } else if (m.group(6) != null) {
                    String val = m.group(6);
                    spans.add(STYLE_FOLDED, "__FOLDED__".length());
                    spans.add(STYLE_FOLDED, val.length());
                    spans.add(STYLE_FOLDED, "__FOLDED__".length());
                } else if (m.group().equals("__COMMA__")) {
                    spans.add(STYLE_COMMA, "__COMMA__".length());
                }

                lastEnd = end;
            }
            if (lastEnd < text.length()) {
                applySpanForPlain(text.substring(lastEnd), spans);
            }

            jsonRichArea.setStyleSpans(0, spans.create());

            // Now we need to remove the placeholders from the displayed text while keeping
            // the styling on the real content.
            // Strategy: rebuild text with placeholders stripped, mapping styles correctly.
            stripPlaceholdersAndRebuild();

        } catch (Exception e) {
            // Fallback: clear styles
            System.err.println("JSON 高亮异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void applySpanForPlain(String plain, StyleSpansBuilder<String> spans) {
        // Braces are bold
        // For each character, detect type; for simplicity, iterate chars
        StringBuilder sb = new StringBuilder();
        String currentStyle = STYLE_DEFAULT;
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            String style;
            if (c == '{' || c == '}' || c == '[' || c == ']' || c == ':') {
                style = STYLE_BRACE;
            } else if (c == ',') {
                style = STYLE_COMMA;
            } else {
                style = STYLE_DEFAULT;
            }
            if (!style.equals(currentStyle) || sb.length() == 0) {
                if (sb.length() > 0) {
                    spans.add(currentStyle, sb.length());
                    sb.setLength(0);
                }
                currentStyle = style;
            }
            sb.append(c);
        }
        if (sb.length() > 0) {
            spans.add(currentStyle, sb.length());
        }
    }

    /**
     * After applying styles based on placeholders, strip the placeholders from the text.
     * This is done by iterating through the paragraphs and replacing placeholder strings
     * with their real content, while preserving style spans using a per-character list approach.
     */
    private void stripPlaceholdersAndRebuild() {
        try {
            // Rebuild from scratch: parse text, rebuild without placeholders, build per-char style list.
            String original = jsonRichArea.getText();
            List<String> perCharStyle = new ArrayList<>(original.length());
            // Init with default
            for (int i = 0; i < original.length(); i++) perCharStyle.add(STYLE_DEFAULT);

            // Get all style spans from area and apply to per-char
            int pos = 0;
            var doc = jsonRichArea.getDocument();
            // Use StyleSpans from area
            try {
                var styleSpans = jsonRichArea.getStyleSpans(0, original.length());
                int cursor = 0;
                for (var span : styleSpans) {
                    String style = span.getStyle();
                    int len = span.getLength();
                    for (int i = 0; i < len; i++) {
                        if (cursor + i < perCharStyle.size()) {
                            perCharStyle.set(cursor + i, style);
                        }
                    }
                    cursor += len;
                }
            } catch (Exception ignore) {}

            // Now build new text and new per-char style by stripping placeholders
            StringBuilder newText = new StringBuilder();
            List<String> newStyle = new ArrayList<>();

            Matcher m = HIGHLIGHT_PATTERN.matcher(original);
            int lastEnd = 0;
            while (m.find()) {
                int s = m.start();
                int e = m.end();
                // Copy preceding plain
                for (int i = lastEnd; i < s; i++) {
                    newText.append(original.charAt(i));
                    newStyle.add(perCharStyle.get(i));
                }
                // Copy only inner content of placeholders
                if (m.group(1) != null) {
                    String content = m.group(1);
                    int startIdx = s + "__KEY__".length();
                    for (int i = 0; i < content.length(); i++) {
                        newText.append(content.charAt(i));
                        newStyle.add(perCharStyle.get(startIdx + i));
                    }
                } else if (m.group(2) != null) {
                    String content = m.group(2);
                    int startIdx = s + "__STR__".length();
                    for (int i = 0; i < content.length(); i++) {
                        newText.append(content.charAt(i));
                        newStyle.add(perCharStyle.get(startIdx + i));
                    }
                } else if (m.group(3) != null) {
                    String content = m.group(3);
                    int startIdx = s + "__NUM__".length();
                    for (int i = 0; i < content.length(); i++) {
                        newText.append(content.charAt(i));
                        newStyle.add(perCharStyle.get(startIdx + i));
                    }
                } else if (m.group(4) != null) {
                    String content = m.group(4);
                    int startIdx = s + "__BOOL__".length();
                    for (int i = 0; i < content.length(); i++) {
                        newText.append(content.charAt(i));
                        newStyle.add(perCharStyle.get(startIdx + i));
                    }
                } else if (m.group(5) != null) {
                    String content = m.group(5);
                    int startIdx = s + "__NULL__".length();
                    for (int i = 0; i < content.length(); i++) {
                        newText.append(content.charAt(i));
                        newStyle.add(perCharStyle.get(startIdx + i));
                    }
                } else if (m.group(6) != null) {
                    String content = m.group(6);
                    int startIdx = s + "__FOLDED__".length();
                    for (int i = 0; i < content.length(); i++) {
                        newText.append(content.charAt(i));
                        newStyle.add(perCharStyle.get(startIdx + i));
                    }
                } else if (m.group().equals("__COMMA__")) {
                    // Replace with actual comma
                    newText.append(',');
                    // Use the style of first char of the placeholder
                    newStyle.add(perCharStyle.get(s));
                }
                lastEnd = e;
            }
            for (int i = lastEnd; i < original.length(); i++) {
                newText.append(original.charAt(i));
                newStyle.add(perCharStyle.get(i));
            }

            // Apply new text and styles
            String finalText = newText.toString();
            jsonRichArea.replaceText(finalText);

            // Build style spans
            StyleSpansBuilder<String> spansBuilder = new StyleSpansBuilder<>();
            if (!newStyle.isEmpty()) {
                String current = newStyle.get(0);
                int count = 1;
                for (int i = 1; i < newStyle.size(); i++) {
                    if (Objects.equals(current, newStyle.get(i))) {
                        count++;
                    } else {
                        spansBuilder.add(current, count);
                        current = newStyle.get(i);
                        count = 1;
                    }
                }
                spansBuilder.add(current, count);
            }
            jsonRichArea.setStyleSpans(0, spansBuilder.create());

        } catch (Exception e) {
            System.err.println("JSON strip placeholders 异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ============================================================
    // EXPAND / COLLAPSE ALL
    // ============================================================

    private void expandAll() {
        JsonElement root = findRootElement();
        if (root == null) {
            showError("没有可展开的内容");
            return;
        }
        foldedIds.clear();
        renderFoldableJson(root);
        showSuccess("已展开所有节点");
    }

    private void collapseAll() {
        JsonElement root = findRootElement();
        if (root == null) {
            showError("没有可折叠的内容");
            return;
        }
        // Collect all container IDs by traversing the full JSON tree
        // (not just currently-visible lineMetaList, which skips children of already-folded nodes)
        foldedIds.clear();
        collectAllContainerIds(root, new int[]{UNIQUE_SALT_BASE});
        renderFoldableJson(root);
        showSuccess("已折叠所有节点");
    }

    /**
     * Recursively traverse the full JSON tree and assign IDs to every container
     * in the same order that buildLines does, adding them to foldedIds.
     */
    private void collectAllContainerIds(JsonElement e, int[] idCounter) {
        if (e.isJsonObject()) {
            String id = "o_" + (++idCounter[0]);
            foldedIds.add(id);
            for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
                collectAllContainerIds(entry.getValue(), idCounter);
            }
        } else if (e.isJsonArray()) {
            String id = "a_" + (++idCounter[0]);
            foldedIds.add(id);
            for (JsonElement child : e.getAsJsonArray()) {
                collectAllContainerIds(child, idCounter);
            }
        }
    }

    // ============================================================
    // MESSAGES
    // ============================================================

    private void showError(String msg) {
        statusLabel.setText(msg);
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #e53935;");
    }

    private void showSuccess(String msg) {
        statusLabel.setText(msg);
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #388E3C;");
        PauseTransition pause = new PauseTransition(Duration.millis(3000));
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

    // ============================================================
    // OPERATIONS
    // ============================================================

    private void formatJSON() {
        String input = inputArea.getText().trim();
        if (input.isEmpty()) {
            showError("请输入JSON数据");
            return;
        }
        try {
            JsonElement element = JsonParser.parseString(input);
            String formatted = prettyGson.toJson(element);
            foldedIds.clear();
            showTreeView(formatted);
            addToHistory(formatted);
            showSuccess("格式化成功！");
        } catch (Exception e) {
            showError("JSON 格式错误: " + e.getMessage());
            showRawView("JSON 格式错误: " + e.getMessage());
        }
    }

    private void minifyJSON() {
        String input = inputArea.getText().trim();
        if (input.isEmpty()) {
            showError("请输入JSON数据");
            return;
        }
        try {
            JsonElement element = JsonParser.parseString(input);
            String minified = compactGson.toJson(element);
            showRawView(minified);
            showSuccess("压缩成功！");
        } catch (Exception e) {
            showError("JSON 格式错误: " + e.getMessage());
            showRawView("JSON 格式错误: " + e.getMessage());
        }
    }

    private void convertChineseToUnicode() {
        String input = inputArea.getText().trim();
        if (input.isEmpty()) {
            showError("请输入要转换的文本");
            return;
        }
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c > 127) {
                output.append(String.format("\\u%04x", (int) c));
            } else {
                output.append(c);
            }
        }
        showRawView(output.toString());
        showSuccess("中文转Unicode成功！");
    }

    private void convertUnicodeToChinese() {
        String input = inputArea.getText().trim();
        if (input.isEmpty()) {
            showError("请输入要转换的Unicode文本");
            return;
        }
        StringBuilder output = new StringBuilder();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(input);
        while (matcher.find()) {
            int codePoint = Integer.parseInt(matcher.group(1), 16);
            matcher.appendReplacement(output, String.valueOf((char) codePoint));
        }
        matcher.appendTail(output);
        showRawView(output.toString());
        showSuccess("Unicode转中文成功！");
    }

    private void addEscape() {
        String input = inputArea.getText().trim();
        if (input.isEmpty()) {
            showError("请输入要添加转义的文本");
            return;
        }
        String output = input
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        showRawView(output);
        showSuccess("添加转义成功！");
    }

    private void removeEscape() {
        String input = inputArea.getText().trim();
        if (input.isEmpty()) {
            showError("请输入要去除转义的文本");
            return;
        }
        String output = input
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
        showRawView(output);
        showSuccess("去除转义成功！");
    }

    private void copyOutput() {
        String text;
        if (outputPane.getChildren().contains(rawOutputArea)) {
            text = rawOutputArea.getText();
        } else {
            text = jsonRichArea.getText();
        }
        if (text == null || text.isEmpty()) {
            showError("没有可复制的内容");
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        showSuccess("已复制到剪贴板！");
    }
}