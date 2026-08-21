package com.tangluobo.tomato.module.connect;

import com.tangluobo.tomato.module.connect.service.OssService;
import com.tangluobo.tomato.module.connect.service.S3Service;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.print.PageLayout;
import javafx.print.PageOrientation;
import javafx.print.Paper;
import javafx.print.PrinterJob;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.effect.DropShadow;
import javafx.scene.shape.Circle;
import javafx.util.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.Emphasis;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.ListItem;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlNodeRendererFactory;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import de.jensd.fx.glyphs.fontawesome.FontAwesomeIcon;
import de.jensd.fx.glyphs.fontawesome.utils.FontAwesomeIconFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import com.tangluobo.tomato.module.connect.markdown.editor.MarkdownTextArea;

/**
 * Markdown 编辑器面板
 * 基于 RichTextFX MarkdownTextArea 编辑 + commonmark 解析 + TextFlow/VBox 预览
 * 支持三种模式：编辑 / 编辑+预览 / 预览
 * 保存到 S3/OSS
 */
public class MarkdownEditorPane extends BorderPane {

    /**
     * 可插拔存储：保存 Markdown 内容到任意后端（S3/OSS/本地文件等）。
     * onSuccess 在 JavaFX 线程之外执行完成后由实现负责切回 JavaFX 线程调用；
     * onError 接收错误消息。
     */
    @FunctionalInterface
    public interface Storage {
        void save(String content, Runnable onSuccess, Consumer<String> onError);
    }

    private final String displayName;
    private final Storage storage;

    private final MarkdownTextArea editor;
    private final VirtualizedScrollPane<MarkdownTextArea> editorScroll;
    private final HBox editorBox;
    private final VBox lineNumberBox;
    private final java.util.List<Label> lineNumberLabels = new java.util.ArrayList<>();
    private static final int LINE_HEIGHT_PX = 17; // Consolas 13px 行高，与编辑区一致
    private static final int MAX_PREALLOC_LINES = 5000;
    // 预览面板：VBox 内混合 TextFlow/VBox/StackPane（段落/标题/引用/代码块等块级结构）+ GridPane（可视表格）
    private final VBox previewBox;
    private final ScrollPane previewScroll;
    private final StackPane centerContainer;
    private final SplitPane splitPane;

    private String originalContent = "";
    private boolean modified = false;
    private boolean saving = false;
    private Consumer<String> onTitleChange;

    private enum Mode { EDIT, EDIT_PREVIEW, PREVIEW }
    private Mode currentMode = Mode.EDIT_PREVIEW;

    // 查找 / 替换对话框状态
    private Stage findReplaceStage;
    private TabPane findReplaceTabs;
    private TextField findFieldFind;      // 查找标签内的查找框
    private TextField findFieldReplace;   // 替换标签内的查找框
    private TextField replaceField;
    private Switch findRegexSwitch;       // 查找标签的正则开关
    private Switch replaceRegexSwitch;    // 替换标签的正则开关
    private Label findStatusLabel;
    private Label replaceStatusLabel;
    private boolean regexMode = false;
    private boolean syncingFind = false; // 防止两个查找框双向同步时递归
    private final java.util.List<int[]> findMatches = new java.util.ArrayList<>();
    private int findIndex = -1;

    // 编辑器语法高亮样式（CSS 类名方式，配合 markdown-editor-highlight.css）
    private static final Collection<String> STYLE_HEADING = List.of("md-heading");
    private static final Collection<String> STYLE_CODE = List.of("md-code");
    private static final Collection<String> STYLE_LINK = List.of("md-link");
    private static final Collection<String> STYLE_BOLD = List.of("md-bold");
    private static final Collection<String> STYLE_ITALIC = List.of("md-italic");
    private static final Collection<String> STYLE_LISTMARK = List.of("md-listmark");
    private static final Collection<String> STYLE_QUOTEMARK = List.of("md-quotemark");
    private static final Collection<String> STYLE_EMPTY = List.of();

    // 预览解析器：解析非表格片段的 Markdown（Parser 线程安全，构建一次复用）。
    // 表格不依赖 commonmark 扩展，由 renderMarkdown 自行检测并渲染。
    private static final Parser PREVIEW_PARSER = Parser.builder().build();

    private static final java.util.regex.Pattern MD_PATTERN = java.util.regex.Pattern.compile(
            "(?<HEADING>^#{1,6}\\s.*$)" +
            "|(?<CODE>`[^`\\n]+`)" +
            "|(?<LINK>\\[[^\\]\\n]*\\]\\([^)\\n]+\\))" +
            "|(?<BOLD>\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__)" +
            "|(?<ITALIC>\\*[^*\\n]+\\*|_[^_\\n]+_)" +
            "|(?<LISTMARK>^\\s*[-*+]\\s)" +
            "|(?<QUOTEMARK>^>\\s)",
            java.util.regex.Pattern.MULTILINE
    );

    // 自动续行匹配：前缀（列表/引用/任务标记）+ 行内容
    private static final java.util.regex.Pattern AUTO_INDENT_PATTERN = java.util.regex.Pattern.compile(
            "^(?<prefix>\\s*(?:[-*+]|\\d+\\.)\\s+(?:\\[[ xX]]\\s+)?|\\s*(?:>\\s*)+)(?<content>.*)$"
    );
    private static final java.util.regex.Pattern ORDERED_PREFIX_PATTERN =
            java.util.regex.Pattern.compile("^(\\s*)(\\d+)\\.(.*)$");

    // 预览渲染防抖
    private final PauseTransition previewDebounce = new PauseTransition(new Duration(250));

    // ==================== Alt+鼠标矩形（列）选择 ====================
    private boolean rectSelecting = false;           // 是否正在进行矩形选择
    private int rectStartRow = -1, rectStartCol = -1; // 起点（鼠标按下时）
    private int rectEndRow = -1, rectEndCol = -1;     // 终点（拖动中）
    private static final Collection<String> STYLE_RECT_SELECT = List.of("md-rect-select"); // 矩形选择高亮样式

    /**
     * S3/OSS 编辑器构造：通过 config/bucket/key 保存到对象存储。
     * 委托给通用 {@link #MarkdownEditorPane(String, String, Storage)}。
     */
    public MarkdownEditorPane(ConnectionConfig config, String bucket, String key, String displayName, String initialContent) {
        this(displayName, initialContent, (content, onSuccess, onError) -> new Thread(() -> {
            try {
                boolean isOSS = config.getType() == ConnectType.ALIYUN_OSS;
                if (isOSS) {
                    OssService.putObject(config, bucket, key, content);
                } else {
                    S3Service.putObject(config, bucket, key, content);
                }
                Platform.runLater(onSuccess);
            } catch (Exception e) {
                Platform.runLater(() -> onError.accept(e.getMessage()));
            }
        }, "MD-Save").start());
    }

    /**
     * 通用 Markdown 编辑器构造：保存逻辑由 {@link Storage} 注入，
     * 可对接 S3/OSS/本地文件等任意后端。
     */
    public MarkdownEditorPane(String displayName, String initialContent, Storage storage) {
        this.displayName = displayName;
        this.storage = storage;

        this.editor = new MarkdownTextArea();
        this.editor.setWrapText(false);
        this.editor.setStyle(
                "-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px; " +
                "-fx-background-color: white; -fx-padding: 0; -fx-text-fill: #333;"
        );
        this.editor.getStylesheets().add(getClass().getResource("/css/markdown-editor-highlight.css").toExternalForm());

        this.editorScroll = new VirtualizedScrollPane<>(editor);
        this.editorScroll.getStyleClass().add("session-scroll-pane");
        this.editorScroll.setStyle(
                "-fx-background-color: white; -fx-background-insets: 0; " +
                "-fx-padding: 0; -fx-border-color: transparent; -fx-border-width: 0; -fx-border-insets: 0;"
        );

        // 编辑器行号区：VBox + Label 列表（参考 SqlEditorView，避免 TextArea 自带多余滚动条）
        this.lineNumberBox = new VBox();
        this.lineNumberBox.setStyle("-fx-background-color: #f5f5f5; -fx-padding: 0;");
        this.lineNumberBox.setMinWidth(40);
        this.lineNumberBox.setPrefWidth(40);
        this.lineNumberBox.setMaxWidth(40);
        // 不驱动父布局高度，由父容器(HBox)分配空间后被动填充
        this.lineNumberBox.setMinHeight(0);
        this.lineNumberBox.setPrefHeight(0);

        // 预分配 MAX_PREALLOC_LINES 个 Label，避免运行时频繁创建
        for (int i = 1; i <= MAX_PREALLOC_LINES; i++) {
            Label label = new Label(Integer.toString(i));
            label.setStyle(
                    "-fx-font-family: 'Consolas', monospace; -fx-font-size: 13px; " +
                    "-fx-text-fill: #999; -fx-alignment: CENTER_RIGHT; " +
                    "-fx-padding: 0 6 0 4; -fx-pref-height: " + LINE_HEIGHT_PX + "; -fx-min-height: " + LINE_HEIGHT_PX + ";"
            );
            label.setVisible(false);
            label.setManaged(false);
            lineNumberLabels.add(label);
            lineNumberBox.getChildren().add(label);
        }
        // 底部占位，撑住高度
        Region filler = new Region();
        VBox.setVgrow(filler, Priority.ALWAYS);
        lineNumberBox.getChildren().add(filler);

        // 将行号区和编辑器滚动面板组合
        this.editorBox = new HBox(0);
        this.editorBox.getChildren().addAll(lineNumberBox, editorScroll);
        this.editorBox.setStyle("-fx-background-color: white; -fx-background-insets: 0; -fx-padding: 0;");
        HBox.setHgrow(lineNumberBox, Priority.NEVER);
        HBox.setHgrow(editorScroll, Priority.ALWAYS);
        HBox.setHgrow(editorBox, Priority.ALWAYS);
        // 不驱动SplitPane分配，被动接受SplitPane给的空间
        this.editorBox.setMinHeight(0);

        // 同步行号内容
        editor.textProperty().addListener((obs, oldVal, newVal) -> {
            updateLineNumbers(newVal);
        });
        
        // 同步滚动：监听编辑器的 estimatedScrollYProperty，行号直接 translateY 反平移（参考 SqlEditorView）
        editor.estimatedScrollYProperty().addListener((obs, oldVal, newVal) -> {
            lineNumberBox.setTranslateY(-newVal.doubleValue());
        });

        // 初始行号
        updateLineNumbers("");

        // 编辑器右键菜单：剪切/复制/粘贴/全选
        setupEditorContextMenu();

        // 预览区：VBox + ScrollPane，子节点为 TextFlow/VBox/StackPane（块级结构）与 GridPane（表格）混合
        this.previewBox = new VBox(6);
        this.previewBox.setPadding(new Insets(0));
        this.previewBox.setStyle("-fx-background-color: white;");
        this.previewScroll = new ScrollPane(previewBox);
        this.previewScroll.setFitToWidth(true);
        this.previewScroll.setFitToHeight(false);
        this.previewScroll.getStyleClass().add("session-scroll-pane");
        this.previewScroll.setStyle(
                "-fx-background-color: white; -fx-background-insets: 0; " +
                "-fx-padding: 0; -fx-border-color: transparent; -fx-border-width: 0; -fx-border-insets: 0;"
        );

        this.splitPane = new SplitPane();
        this.splitPane.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        this.splitPane.getItems().addAll(editorBox, previewScroll);
        this.splitPane.setDividerPositions(0.5);
        this.splitPane.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());

        this.centerContainer = new StackPane();
        setCenter(centerContainer);

        // 顶部工具栏
        setTop(buildToolbar());

        // 初始化编辑器内容与高亮
        if (initialContent == null) initialContent = "";
        this.originalContent = initialContent;
        this.editor.replaceText(initialContent);
        refreshHighlightWithRect();
        updatePreview();

        // 编辑器内容变化：实时更新高亮与预览（纯编辑模式下不渲染预览以省开销）
        editor.textProperty().addListener((obs, oldVal, newVal) -> {
            // 文本改变后矩形选择的偏移不再可靠，清除之
            if (hasRectSelection()) clearRectSelection();
            refreshHighlightWithRect();
            if (currentMode != Mode.EDIT) {
                updatePreview();
            }
            boolean nowModified = !newVal.equals(originalContent);
            if (nowModified != modified) {
                modified = nowModified;
                notifyTitleChange();
            }
        });

        // 快捷键（参考 markdown-writer-fx）
        editor.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown()) {
                boolean shift = e.isShiftDown();
                switch (e.getCode()) {
                    case S:             e.consume(); save();                          return;
                    case F:             e.consume(); showFindReplace(false);         return;
                    case R:             e.consume(); showFindReplace(true);          return;
                    case B:             e.consume(); toggleWrap("**", "**");          return;
                    case I:             e.consume(); toggleWrap("*", "*");            return;
                    case T:             e.consume(); toggleWrap("~~", "~~");          return;
                    case BACK_QUOTE:    e.consume();
                        if (shift) insertCodeBlock(); else toggleWrap("`", "`");
                        return;
                    case L:             e.consume(); insertLink();                    return;
                    case G:             e.consume(); insertImage();                   return;
                    case Q:             e.consume(); togglePrefix("> ");             return;
                    case U:             e.consume(); togglePrefix("- ");             return;
                    case DIGIT1:        e.consume(); togglePrefix("# ");             return;
                    case DIGIT2:        e.consume(); togglePrefix("## ");            return;
                    case DIGIT3:        e.consume(); togglePrefix("### ");           return;
                    default: break;
                }
            }
            if (e.getCode() == KeyCode.TAB) {
                e.consume();
                handleTab(e.isShiftDown());
                return;
            }
            if (e.getCode() == KeyCode.ENTER && !e.isShiftDown()) {
                if (handleEnter()) {
                    e.consume();
                }
            }
            // 矩形选择时的按键处理：删除/插入
            if (hasRectSelection()) {
                if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) {
                    e.consume();
                    deleteRectSelection();
                    return;
                }
                // 可打印字符：插入到每一行的矩形起始列（如果是 Shift+Enter 等特殊组合不处理）
                if (!e.isControlDown() && !e.isMetaDown() && !e.isAltDown() && e.getCode().isLetterKey()) {
                    // 由 KEY_TYPED 处理字符插入；这里仅阻止默认选择消失
                }
            }
        });

        // Alt+鼠标 矩形（列）选择
        editor.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.isAltDown() && e.getButton() == MouseButton.PRIMARY) {
                e.consume();
                clearRectSelection();
                editor.deselect(); // 清除普通选择
                int[] rc = mousePositionToRowCol(e);
                rectStartRow = rc[0];
                rectStartCol = rc[1];
                rectEndRow = rc[0];
                rectEndCol = rc[1];
                rectSelecting = true;
                refreshHighlightWithRect();
            }
        });
        editor.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (rectSelecting && e.getButton() == MouseButton.PRIMARY) {
                e.consume();
                int[] rc = mousePositionToRowCol(e);
                rectEndRow = rc[0];
                rectEndCol = rc[1];
                refreshHighlightWithRect();
            }
        });
        editor.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (rectSelecting && e.getButton() == MouseButton.PRIMARY) {
                e.consume();
                rectSelecting = false;
                // 如果选择退化为单点，清除矩形选择
                int r1 = Math.min(rectStartRow, rectEndRow), r2 = Math.max(rectStartRow, rectEndRow);
                int c1 = Math.min(rectStartCol, rectEndCol), c2 = Math.max(rectStartCol, rectEndCol);
                if (r1 == r2 && c1 == c2) {
                    clearRectSelection();
                    refreshHighlightWithRect();
                }
            }
        });
        // 普通鼠标按下（无 Alt）时清除矩形选择
        editor.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (!e.isAltDown() && hasRectSelection()) {
                clearRectSelection();
                refreshHighlightWithRect();
            }
        });

        // 矩形选择时的字符插入：监听 KEY_TYPED（可获取实际输入字符，支持中文等）
        editor.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (hasRectSelection() && !e.isControlDown() && !e.isMetaDown() && !e.isAltDown()) {
                String ch = e.getCharacter();
                if (ch != null && !ch.isEmpty() && !ch.equals("\r") && !ch.equals("\n") && !ch.equals("\t")) {
                    e.consume();
                    insertIntoRectSelection(ch);
                }
            }
        });

        applyMode();
    }

    // ==================== 工具栏 ====================
    // 参考 markdown-writer-fx：原生 ToolBar + FontAwesome 图标 + 透明/hover/selected 样式
    // ActionUtils.createToolBarButton 同款实现：graphic=图标、tooltip=文字+快捷键、focusTraversable=false

    private Node buildToolbar() {
        ToolBar toolBar = new ToolBar();
        toolBar.getStyleClass().add("markdown-tool-bar");
        toolBar.getStylesheets().add(getClass().getResource("/css/markdown-editor-toolbar.css").toExternalForm());

        // 撤销 / 重做
        toolBar.getItems().addAll(
                iconBtn(FontAwesomeIcon.UNDO, "撤销", "Ctrl+Z", editor::undo),
                iconBtn(FontAwesomeIcon.REPEAT, "重做", "Ctrl+Y", editor::redo),
                new Separator());

        // 行内格式
        toolBar.getItems().addAll(
                iconBtn(FontAwesomeIcon.BOLD, "加粗", "Ctrl+B", () -> toggleWrap("**", "**")),
                iconBtn(FontAwesomeIcon.ITALIC, "斜体", "Ctrl+I", () -> toggleWrap("*", "*")),
                iconBtn(FontAwesomeIcon.STRIKETHROUGH, "删除线", "Ctrl+T", () -> toggleWrap("~~", "~~")),
                iconBtn(FontAwesomeIcon.CODE, "行内代码", "Ctrl+`", () -> toggleWrap("`", "`")),
                new Separator());

        // 链接 / 图片
        toolBar.getItems().addAll(
                iconBtn(FontAwesomeIcon.LINK, "链接", "Ctrl+L", this::insertLink),
                iconBtn(FontAwesomeIcon.PICTURE_ALT, "图片", "Ctrl+G", this::insertImage),
                new Separator());

        // 标题（图标相同，用 tooltip 区分级别）
        toolBar.getItems().addAll(
                iconBtn(FontAwesomeIcon.HEADER, "标题1", "Ctrl+1", () -> togglePrefix("# ")),
                iconBtn(FontAwesomeIcon.HEADER, "标题2", "Ctrl+2", () -> togglePrefix("## ")),
                iconBtn(FontAwesomeIcon.HEADER, "标题3", "Ctrl+3", () -> togglePrefix("### ")),
                new Separator());

        // 块级
        toolBar.getItems().addAll(
                iconBtn(FontAwesomeIcon.LIST_UL, "无序列表", "Ctrl+U", () -> togglePrefix("- ")),
                iconBtn(FontAwesomeIcon.LIST_OL, "有序列表", null, () -> togglePrefix("1. ")),
                iconBtn(FontAwesomeIcon.CHECK_SQUARE, "任务列表", null, () -> togglePrefix("- [ ] ")),
                iconBtn(FontAwesomeIcon.QUOTE_LEFT, "引用", "Ctrl+Q", () -> togglePrefix("> ")),
                iconBtn(FontAwesomeIcon.FILE_CODE_ALT, "代码块", "Ctrl+Shift+`", this::insertCodeBlock),
                iconBtn(FontAwesomeIcon.MINUS, "分隔线", null, this::insertHr),
                iconBtn(FontAwesomeIcon.TABLE, "表格", null, this::insertTable));

        // 导出
        toolBar.getItems().addAll(
                new Separator(),
                iconBtn(FontAwesomeIcon.FILE_CODE_ALT, "导出HTML", null, this::exportHtml),
                iconBtn(FontAwesomeIcon.FILE_PDF_ALT, "导出PDF", null, this::exportPdf));

        // 弹性空白（ToolBar 内部为 HBox，HBox.setHgrow 有效）
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        toolBar.getItems().add(spacer);

        // 模式切换
        ToggleGroup modeGroup = new ToggleGroup();
        ToggleButton editBtn = modeToggle(modeGroup, FontAwesomeIcon.PENCIL, "编辑", Mode.EDIT);
        ToggleButton splitBtn = modeToggle(modeGroup, FontAwesomeIcon.COLUMNS, "编辑+预览", Mode.EDIT_PREVIEW);
        ToggleButton previewBtn = modeToggle(modeGroup, FontAwesomeIcon.EYE, "预览", Mode.PREVIEW);
        splitBtn.setSelected(true);
        toolBar.getItems().addAll(editBtn, splitBtn, previewBtn, new Separator());

        // 保存
        Button saveBtn = iconBtn(FontAwesomeIcon.FLOPPY_ALT, "保存", "Ctrl+S", this::save);
        saveBtn.getStyleClass().add("save-button");
        toolBar.getItems().add(saveBtn);

        return toolBar;
    }

    /** 工具栏图标按钮：FontAwesome 图标 + tooltip(文字+快捷键) + 不抢焦点 */
    private Button iconBtn(FontAwesomeIcon icon, String text, String shortcut, Runnable action) {
        Button b = new Button();
        b.setGraphic(FontAwesomeIconFactory.get().createIcon(icon, "1.2em"));
        b.setTooltip(new Tooltip(shortcut != null ? text + " (" + shortcut + ")" : text));
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        return b;
    }

    private ToggleButton modeToggle(ToggleGroup group, FontAwesomeIcon icon, String text, Mode mode) {
        ToggleButton b = new ToggleButton();
        b.setToggleGroup(group);
        b.setGraphic(FontAwesomeIconFactory.get().createIcon(icon, "1.2em"));
        b.setTooltip(new Tooltip(text));
        b.setFocusTraversable(false);
        b.setOnAction(e -> { currentMode = mode; applyMode(); });
        return b;
    }

    private void applyMode() {
        centerContainer.getChildren().clear();
        switch (currentMode) {
            case EDIT -> centerContainer.getChildren().setAll(editorBox);
            case EDIT_PREVIEW -> {
                centerContainer.getChildren().setAll(splitPane);
                updatePreview();
            }
            case PREVIEW -> {
                centerContainer.getChildren().setAll(previewScroll);
                updatePreview();
            }
        }
    }

    // ==================== 编辑器操作 ====================
    // 参考 markdown-writer-fx SmartEdit：智能切换（已包裹/已有前缀则取消）

    /** 行内包裹：选区已包裹 before/after 则去除，否则包裹。无选区时插入占位并选中占位文本。 */
    private void toggleWrap(String before, String after) {
        var sel = editor.getSelection();
        if (sel.getLength() == 0) {
            int pos = editor.getCaretPosition();
            editor.insertText(pos, before + after);
            editor.moveTo(pos + before.length());
        } else {
            String selected = editor.getSelectedText();
            int s = sel.getStart(), e = sel.getEnd();
            String text = editor.getText();
            boolean wrapped =
                    s + before.length() <= e - after.length() + after.length()
                    && s + before.length() <= text.length()
                    && e - after.length() >= 0
                    && text.startsWith(before, s)
                    && text.startsWith(after, e - after.length())
                    && (e - after.length()) >= (s + before.length());
            if (wrapped) {
                String inner = text.substring(s + before.length(), e - after.length());
                editor.replaceText(s, e, inner);
                editor.selectRange(s, s + inner.length());
            } else {
                editor.replaceText(s, e, before + selected + after);
                editor.selectRange(s + before.length(), s + before.length() + selected.length());
            }
        }
        editor.requestFocus();
    }

    /** 行首前缀：当前行已有该前缀则去除，否则添加。支持多行选区逐行切换。 */
    private void togglePrefix(String prefix) {
        var sel = editor.getSelection();
        String text = editor.getText();
        int start = sel.getStart();
        int end = sel.getEnd();
        if (start == end) {
            // 单行：以光标所在行为准
            start = lineStart(text, start);
            end = (text.indexOf('\n', start) < 0 ? text.length() : text.indexOf('\n', start));
        } else {
            start = lineStart(text, start);
            if (end < text.length() && text.charAt(end) != '\n') {
                end = (text.indexOf('\n', end) < 0 ? text.length() : text.indexOf('\n', end));
            }
        }
        String block = text.substring(start, end);
        boolean allPrefixed = true;
        for (String ln : block.split("\n", -1)) {
            if (!ln.startsWith(prefix)) { allPrefixed = false; break; }
        }
        StringBuilder nb = new StringBuilder();
        for (String ln : block.split("\n", -1)) {
            if (nb.length() > 0) nb.append('\n');
            if (allPrefixed) {
                if (ln.startsWith(prefix)) nb.append(ln.substring(prefix.length()));
                else nb.append(ln);
            } else {
                nb.append(prefix).append(ln);
            }
        }
        editor.replaceText(start, end, nb.toString());
        editor.selectRange(start, start + nb.length());
        editor.requestFocus();
    }

    private void insertLink() {
        var sel = editor.getSelection();
        String selected = sel.getLength() == 0 ? "链接文本" : editor.getSelectedText();
        int s = sel.getStart(), e = sel.getEnd();
        String url = "url";
        String md = "[" + selected + "](" + url + ")";
        editor.replaceText(s, e, md);
        // 选中 url 便于直接输入
        int urlStart = s + selected.length() + 3;
        editor.selectRange(urlStart, urlStart + url.length());
        editor.requestFocus();
    }

    private void insertImage() {
        var sel = editor.getSelection();
        String selected = sel.getLength() == 0 ? "替代文本" : editor.getSelectedText();
        int s = sel.getStart(), e = sel.getEnd();
        String url = "url";
        String md = "![" + selected + "](" + url + ")";
        editor.replaceText(s, e, md);
        int urlStart = s + selected.length() + 4;
        editor.selectRange(urlStart, urlStart + url.length());
        editor.requestFocus();
    }

    private void insertCodeBlock() {
        String block = "\n```\n\n```\n";
        int pos = editor.getCaretPosition();
        editor.insertText(pos, block);
        // 光标放到代码块中间空行
        editor.moveTo(pos + 5);
        editor.requestFocus();
    }

    private void insertHr() {
        int pos = editor.getCaretPosition();
        String text = editor.getText();
        String ins = (pos > 0 && text.charAt(pos - 1) != '\n' ? "\n\n" : "\n") + "---\n";
        editor.insertText(pos, ins);
        editor.moveTo(pos + ins.length());
        editor.requestFocus();
    }

    private void insertTable() {
        String tbl = "\n| 列1 | 列2 | 列3 |\n|---|---|---|\n|  |  |  |\n|  |  |  |\n";
        int pos = editor.getCaretPosition();
        editor.insertText(pos, tbl);
        editor.moveTo(pos + tbl.length());
        editor.requestFocus();
    }

    private static int lineStart(String text, int pos) {
        if (pos <= 0) return 0;
        int idx = text.lastIndexOf('\n', pos - 1);
        return idx < 0 ? 0 : idx + 1;
    }

    /** ENTER 自动续行：列表/引用/任务标记行续上相同前缀，有序列表数字递增；空标记行清空标记。 */
    private boolean handleEnter() {
        int caret = editor.getCaretPosition();
        String text = editor.getText();
        int ls = lineStart(text, caret);
        int nl = text.indexOf('\n', caret);
        String line = nl < 0 ? text.substring(ls) : text.substring(ls, nl);

        java.util.regex.Matcher m = AUTO_INDENT_PATTERN.matcher(line);
        if (!m.matches()) return false;
        String prefix = m.group("prefix");
        String content = m.group("content");

        if (content.isEmpty()) {
            // 空标记行：清除当前行标记
            int end = nl < 0 ? text.length() : nl;
            editor.replaceText(ls, end, "");
            editor.moveTo(ls);
            return true;
        }
        String newPrefix = incrementPrefix(prefix);
        String insert = "\n" + newPrefix;
        editor.insertText(caret, insert);
        editor.moveTo(caret + insert.length());
        return true;
    }

    private static String incrementPrefix(String prefix) {
        java.util.regex.Matcher m = ORDERED_PREFIX_PATTERN.matcher(prefix);
        if (m.matches()) {
            int n = Integer.parseInt(m.group(2)) + 1;
            return m.group(1) + n + "." + m.group(3);
        }
        return prefix;
    }

    /** Tab：无选区插入 4 空格；多行选区逐行加 4 空格。Shift+Tab：逐行去前导最多 4 空格。 */
    private void handleTab(boolean shift) {
        var sel = editor.getSelection();
        String text = editor.getText();
        int s = sel.getStart(), e = sel.getEnd();
        if (s == e) {
            if (shift) {
                int ls = lineStart(text, s);
                int max = Math.min(4, text.length() - ls);
                int cnt = 0;
                while (cnt < max && text.charAt(ls + cnt) == ' ') cnt++;
                if (cnt > 0) {
                    editor.replaceText(ls, ls + cnt, "");
                    editor.moveTo(s - cnt);
                }
            } else {
                editor.insertText(s, "    ");
            }
            return;
        }
        int start = lineStart(text, s);
        int end = e;
        if (end < text.length() && text.charAt(end) != '\n') {
            int n = text.indexOf('\n', end);
            end = n < 0 ? text.length() : n;
        }
        String block = text.substring(start, end);
        StringBuilder nb = new StringBuilder();
        for (String ln : block.split("\n", -1)) {
            if (nb.length() > 0) nb.append('\n');
            if (shift) {
                int cnt = 0;
                int max = Math.min(4, ln.length());
                while (cnt < max && ln.charAt(cnt) == ' ') cnt++;
                nb.append(ln.substring(cnt));
            } else {
                nb.append("    ").append(ln);
            }
        }
        editor.replaceText(start, end, nb.toString());
        editor.selectRange(start, start + nb.length());
    }

    // ==================== 语法高亮 ====================

    private void applyHighlighting() {
        String text = editor.getText();
        if (text.isEmpty()) return;
        try {
            java.util.regex.Matcher m = MD_PATTERN.matcher(text);
            StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
            int last = 0;
            while (m.find()) {
                Collection<String> style;
                if (m.group("HEADING") != null) style = STYLE_HEADING;
                else if (m.group("CODE") != null) style = STYLE_CODE;
                else if (m.group("LINK") != null) style = STYLE_LINK;
                else if (m.group("BOLD") != null) style = STYLE_BOLD;
                else if (m.group("ITALIC") != null) style = STYLE_ITALIC;
                else if (m.group("LISTMARK") != null) style = STYLE_LISTMARK;
                else if (m.group("QUOTEMARK") != null) style = STYLE_QUOTEMARK;
                else style = STYLE_EMPTY;
                if (m.start() > last) b.add(STYLE_EMPTY, m.start() - last);
                b.add(style, m.end() - m.start());
                last = m.end();
            }
            if (last < text.length()) b.add(STYLE_EMPTY, text.length() - last);
            editor.setStyleSpans(0, b.create());
        } catch (Exception e) {
            System.err.println("Markdown高亮异常: " + e.getMessage());
        }
    }

    // ==================== Alt+鼠标矩形选择辅助方法 ====================

    /** 是否有有效的矩形选择（非退化） */
    private boolean hasRectSelection() {
        if (rectStartRow < 0 || rectStartCol < 0 || rectEndRow < 0 || rectEndCol < 0) return false;
        int r1 = Math.min(rectStartRow, rectEndRow), r2 = Math.max(rectStartRow, rectEndRow);
        int c1 = Math.min(rectStartCol, rectEndCol), c2 = Math.max(rectStartCol, rectEndCol);
        return !(r1 == r2 && c1 == c2);
    }

    /** 清除矩形选择状态 */
    private void clearRectSelection() {
        rectSelecting = false;
        rectStartRow = rectStartCol = rectEndRow = rectEndCol = -1;
    }

    /** 规范化矩形：返回 [r1, c1, r2, c2]，保证 r1<=r2, c1<=c2，并修正行号到实际范围 */
    private int[] getNormalizedRect() {
        int paraCount = editor.getParagraphs().size();
        int r1 = Math.min(rectStartRow, rectEndRow);
        int r2 = Math.max(rectStartRow, rectEndRow);
        int c1 = Math.min(rectStartCol, rectEndCol);
        int c2 = Math.max(rectStartCol, rectEndCol);
        r1 = Math.max(0, Math.min(r1, paraCount - 1));
        r2 = Math.max(0, Math.min(r2, paraCount - 1));
        c1 = Math.max(0, c1);
        c2 = Math.max(0, c2);
        return new int[]{r1, c1, r2, c2};
    }

    /** 将鼠标屏幕坐标转换为编辑器中的 [行, 列]（paragraph, column） */
    private int[] mousePositionToRowCol(MouseEvent e) {
        double sceneX = e.getSceneX(), sceneY = e.getSceneY();
        javafx.geometry.Point2D local = editor.sceneToLocal(sceneX, sceneY, true);
        if (local == null) local = new javafx.geometry.Point2D(0, 0);
        try {
            // RichTextFX 0.11.7: hit(x,y) 返回 CharacterHit，getInsertionIndex() 得到全局字符偏移
            org.fxmisc.richtext.CharacterHit hit = editor.hit(local.getX(), local.getY());
            int offset = hit.getInsertionIndex();
            // offsetToPosition 将全局偏移转为 (paragraph, column)：getMajor()=行号, getMinor()=列号
            org.fxmisc.richtext.model.TwoDimensional.Position pos =
                    editor.offsetToPosition(offset, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward);
            return new int[]{pos.getMajor(), pos.getMinor()};
        } catch (Exception ignore) {}
        // 回退：根据当前光标位置
        return new int[]{editor.getCurrentParagraph(), editor.getCaretColumn()};
    }

    /** 重新计算语法高亮并叠加矩形选择高亮。需要时调用此方法而非直接 applyHighlighting。 */
    private void refreshHighlightWithRect() {
        String text = editor.getText();
        if (text.isEmpty()) {
            editor.setStyleSpans(0, new StyleSpansBuilder<Collection<String>>().add(STYLE_EMPTY, 0).create());
            return;
        }
        try {
            // 第一步：生成语法高亮 spans（与 applyHighlighting 相同）
            java.util.regex.Matcher m = MD_PATTERN.matcher(text);
            // 用列表保存每个字符的样式，以便后续叠加
            int len = text.length();
            @SuppressWarnings("unchecked")
            Collection<String>[] baseStyles = new Collection[len];
            java.util.Arrays.fill(baseStyles, STYLE_EMPTY);
            while (m.find()) {
                Collection<String> style;
                if (m.group("HEADING") != null) style = STYLE_HEADING;
                else if (m.group("CODE") != null) style = STYLE_CODE;
                else if (m.group("LINK") != null) style = STYLE_LINK;
                else if (m.group("BOLD") != null) style = STYLE_BOLD;
                else if (m.group("ITALIC") != null) style = STYLE_ITALIC;
                else if (m.group("LISTMARK") != null) style = STYLE_LISTMARK;
                else if (m.group("QUOTEMARK") != null) style = STYLE_QUOTEMARK;
                else style = STYLE_EMPTY;
                for (int i = m.start(); i < m.end() && i < len; i++) {
                    baseStyles[i] = style;
                }
            }

            // 第二步：若存在矩形选择，叠加选择高亮样式
            if (hasRectSelection()) {
                int[] rc = getNormalizedRect();
                int r1 = rc[0], c1 = rc[1], r2 = rc[2], c2 = rc[3];
                for (int r = r1; r <= r2; r++) {
                    String para = editor.getParagraph(r).getText();
                    int paraLen = para.length();
                    int startCol = Math.min(c1, paraLen);
                    int endCol = Math.min(c2, paraLen);
                    if (startCol >= endCol) continue;
                    int paraOffset = editor.getAbsolutePosition(r, 0);
                    for (int c = startCol; c < endCol; c++) {
                        int idx = paraOffset + c;
                        if (idx < len) {
                            Collection<String> existing = baseStyles[idx];
                            // 合并样式：若已有语法高亮，追加矩形选择类名
                            if (existing == null || existing.isEmpty()) {
                                baseStyles[idx] = STYLE_RECT_SELECT;
                            } else if (!existing.contains("md-rect-select")) {
                                java.util.List<String> merged = new java.util.ArrayList<>(existing);
                                merged.add("md-rect-select");
                                baseStyles[idx] = merged;
                            }
                        }
                    }
                }
            }

            // 第三步：将 per-char 样式数组压缩为 StyleSpansBuilder
            StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
            int i = 0;
            while (i < len) {
                Collection<String> cur = baseStyles[i];
                int j = i + 1;
                while (j < len && safeEq(baseStyles[j], cur)) j++;
                b.add(cur == null ? STYLE_EMPTY : cur, j - i);
                i = j;
            }
            editor.setStyleSpans(0, b.create());
        } catch (Exception e) {
            System.err.println("矩形高亮异常: " + e.getMessage());
            e.printStackTrace();
            // 失败时回退到普通语法高亮
            applyHighlighting();
        }
    }

    private static boolean safeEq(Collection<String> a, Collection<String> b) {
        if (a == null) return b == null;
        return a.equals(b);
    }

    /** 删除矩形选择区域：从后往前逐行替换，避免前序修改影响行/列偏移。 */
    private void deleteRectSelection() {
        if (!hasRectSelection()) return;
        int[] rc = getNormalizedRect();
        int r1 = rc[0], c1 = rc[1], r2 = rc[2], c2 = rc[3];
        // 从最后一行往前行处理，这样前面行的修改不会影响后面行的位置
        for (int r = r2; r >= r1; r--) {
            String para = editor.getParagraph(r).getText();
            int paraLen = para.length();
            int startCol = Math.min(c1, paraLen);
            int endCol = Math.min(c2, paraLen);
            if (startCol >= endCol) continue;
            int startOffset = editor.getAbsolutePosition(r, startCol);
            int endOffset = editor.getAbsolutePosition(r, endCol);
            editor.replaceText(startOffset, endOffset, "");
        }
        clearRectSelection();
        refreshHighlightWithRect();
    }

    /** 在矩形选择区域每一行的 c1 列位置插入 text；若矩形有宽度则先删除宽度部分再插入。 */
    private void insertIntoRectSelection(String text) {
        if (text == null || text.isEmpty()) return;
        if (!hasRectSelection()) {
            // 无矩形选择时按普通插入处理（但此方法仅在有矩形选择时被调用）
            int pos = editor.getCaretPosition();
            editor.insertText(pos, text);
            return;
        }
        int[] rc = getNormalizedRect();
        int r1 = rc[0], c1 = rc[1], r2 = rc[2], c2 = rc[3];
        // 先删除矩形选择区域（逐行从后往前）
        if (c2 > c1) {
            for (int r = r2; r >= r1; r--) {
                String para = editor.getParagraph(r).getText();
                int paraLen = para.length();
                int sc = Math.min(c1, paraLen);
                int ec = Math.min(c2, paraLen);
                if (sc < ec) {
                    int so = editor.getAbsolutePosition(r, sc);
                    int eo = editor.getAbsolutePosition(r, ec);
                    editor.replaceText(so, eo, "");
                }
            }
        }
        // 从后往前逐行在 c1 位置插入，保证前面行的插入不影响后续行的段落号
        for (int r = r2; r >= r1; r--) {
            String para = editor.getParagraph(r).getText();
            int paraLen = para.length();
            int col = Math.min(c1, paraLen);
            int offset = editor.getAbsolutePosition(r, col);
            editor.insertText(offset, text);
        }
        clearRectSelection();
        refreshHighlightWithRect();
    }

    // ==================== 预览渲染 ====================

    private void updatePreview() {
        String md = editor.getText();
        previewBox.getChildren().clear();
        try {
            renderMarkdown(md, new InlineStyle(), previewBox.getChildren());
        } catch (Exception e) {
            e.printStackTrace();
            Label err = new Label("预览渲染失败: " + e.getMessage());
            err.setStyle("-fx-text-fill: #c00; -fx-font-size: 11px;");
            previewBox.getChildren().add(err);
        }
    }

    /**
     * 分段渲染 Markdown（预览 + PDF 导出共用）：自行检测 GFM 表格块并渲染为 GridPane，
     * 其余文本交给 commonmark 解析，由 renderBlock 产出 TextFlow/VBox/StackPane 等带块级结构的节点。
     * 不依赖 commonmark-ext-gfm-tables 扩展。
     */
    private void renderMarkdown(String md, InlineStyle base, ObservableList<Node> target) {
        String[] lines = md.split("\n", -1);
        int i = 0;
        StringBuilder textBuf = new StringBuilder();
        while (i < lines.length) {
            // 表格块：第 i 行为表头行，第 i+1 行为分隔行，且分隔行有效
            if (i + 1 < lines.length && isTableRow(lines[i]) && isDelimiterRow(lines[i + 1])) {
                // 先把累积的文本片段渲染出来
                flushText(textBuf, base, target);
                // 收集表格块：表头 + 分隔行 + 连续的数据行
                java.util.List<String> tableLines = new ArrayList<>();
                tableLines.add(lines[i]);
                tableLines.add(lines[i + 1]);
                int j = i + 2;
                while (j < lines.length && isTableRow(lines[j])) {
                    tableLines.add(lines[j]);
                    j++;
                }
                renderTableLines(tableLines, base, target);
                i = j;
            } else {
                if (textBuf.length() > 0) textBuf.append('\n');
                textBuf.append(lines[i]);
                i++;
            }
        }
        flushText(textBuf, base, target);
    }

    /** 将累积的文本片段交给 commonmark 解析并渲染为块 */
    private void flushText(StringBuilder textBuf, InlineStyle base, ObservableList<Node> target) {
        if (textBuf.length() == 0) return;
        String text = textBuf.toString();
        textBuf.setLength(0);
        org.commonmark.node.Node document = PREVIEW_PARSER.parse(text);
        renderBlocks(document, base, target);
    }

    /** 是否为表格行：非空且包含 | */
    private boolean isTableRow(String line) {
        String t = line.trim();
        return !t.isEmpty() && t.indexOf('|') >= 0;
    }

    /** 是否为表格分隔行：形如 |---|:---:|---:| 或 ---|--- ，每段至少一个 - */
    private static final java.util.regex.Pattern DELIM_ROW =
            java.util.regex.Pattern.compile("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$");

    private boolean isDelimiterRow(String line) {
        if (line == null || !line.contains("-")) return false;
        if (!DELIM_ROW.matcher(line).matches()) return false;
        // 至少有一个 | 或本身就是 ---...（单列无 | 也允许）
        return line.contains("|") || line.contains("-");
    }

    /** 拆分表格行为单元格：去掉首尾 | 后按 | 切分，保留转义 \\| */
    private java.util.List<String> splitTableRow(String line) {
        String t = line.trim();
        // 去掉首尾的 |（仅当两端都有时）
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.substring(0, t.length() - 1);
        java.util.List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            if (c == '\\' && k + 1 < t.length() && t.charAt(k + 1) == '|') {
                cur.append('|');
                k++;
            } else if (c == '|') {
                cells.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString().trim());
        return cells;
    }

    /** 单元格对齐方式 */
    private enum CellAlign { LEFT, CENTER, RIGHT }

    private CellAlign parseAlign(String delimCell) {
        String c = delimCell.trim();
        boolean left = c.startsWith(":");
        boolean right = c.endsWith(":");
        if (left && right) return CellAlign.CENTER;
        if (right) return CellAlign.RIGHT;
        return CellAlign.LEFT; // 默认/仅左冒号都按左
    }

    private void renderBlocks(org.commonmark.node.Node parent, InlineStyle base, ObservableList<Node> target) {
        for (org.commonmark.node.Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            renderBlock(child, base, target);
        }
    }

    private void renderBlock(org.commonmark.node.Node node, InlineStyle base, ObservableList<Node> target) {
        if (node instanceof Paragraph p) {
            List<Text> inlines = new ArrayList<>();
            renderInline(p, base, inlines);
            TextFlow flow = new TextFlow(inlines.toArray(new Text[0]));
            setupSelectableTextFlow(flow);
            target.add(flow);
        } else if (node instanceof Heading h) {
            List<Text> inlines = new ArrayList<>();
            renderInline(h, base, inlines);
            int size = headingSize(h.getLevel());
            for (Text t : inlines) {
                String s = t.getStyle() == null ? "" : t.getStyle();
                t.setStyle(s + " -fx-font-size: " + size + "px; -fx-font-weight: bold;");
            }
            TextFlow flow = new TextFlow(inlines.toArray(new Text[0]));
            flow.setPadding(new Insets(6, 0, 4, 0));
            setupSelectableTextFlow(flow);
            target.add(flow);
        } else if (node instanceof BlockQuote bq) {
            VBox quoteBox = new VBox(4);
            quoteBox.setPadding(new Insets(4, 0, 4, 12));
            quoteBox.setStyle("-fx-border-color: #1a73e8; -fx-border-width: 0 0 0 3; -fx-background-color: #f8f9fa; -fx-background-radius: 0;");
            InlineStyle qs = base.copy();
            qs.quote = true;
            renderBlocks(bq, qs, quoteBox.getChildren());
            target.add(quoteBox);
        } else if (node instanceof BulletList bl) {
            renderList(bl, base, target, false, 1);
        } else if (node instanceof OrderedList ol) {
            renderList(ol, base, target, true, ol.getStartNumber());
        } else if (node instanceof FencedCodeBlock fcb) {
            target.add(renderCodeBlock(fcb.getLiteral(), fcb.getInfo()));
        } else if (node instanceof IndentedCodeBlock icb) {
            target.add(renderCodeBlock(icb.getLiteral(), ""));
        } else if (node instanceof ThematicBreak) {
            Separator sep = new Separator();
            sep.setPadding(new Insets(8, 0, 8, 0));
            target.add(sep);
        } else if (node instanceof HtmlBlock hb) {
            Label l = new Label(hb.getLiteral());
            l.setStyle("-fx-font-family: 'Consolas',monospace; -fx-font-size: 11px; -fx-text-fill: #888;");
            l.setWrapText(true);
            target.add(l);
        } else {
            renderBlocks(node, base, target);
        }
    }

    /** 更新编辑器行号显示：复用 Label，根据行数切换 visible/managed */
    private void updateLineNumbers(String text) {
        int lineCount = 1;
        if (text != null && !text.isEmpty()) {
            lineCount = text.split("\n", -1).length;
        }
        int visibleCount = Math.min(lineCount, lineNumberLabels.size());
        for (int i = 0; i < lineNumberLabels.size(); i++) {
            boolean show = i < visibleCount;
            lineNumberLabels.get(i).setVisible(show);
            lineNumberLabels.get(i).setManaged(show);
        }
    }

    /** 编辑器右键菜单：剪切/复制/粘贴/全选，菜单项根据当前状态动态启用 */
    private void setupEditorContextMenu() {
        javafx.scene.input.Clipboard systemClipboard = javafx.scene.input.Clipboard.getSystemClipboard();
        MenuItem cutItem = new MenuItem("剪切");
        MenuItem copyItem = new MenuItem("复制");
        MenuItem pasteItem = new MenuItem("粘贴");
        MenuItem selectAllItem = new MenuItem("全选");

        cutItem.setOnAction(e -> editor.cut());
        copyItem.setOnAction(e -> editor.copy());
        pasteItem.setOnAction(e -> editor.paste());
        selectAllItem.setOnAction(e -> editor.selectAll());

        ContextMenu contextMenu = new ContextMenu(cutItem, copyItem, pasteItem,
                new SeparatorMenuItem(), selectAllItem);
        contextMenu.setOnShowing(e -> {
            boolean hasSelection = !editor.getSelectedText().isEmpty();
            cutItem.setDisable(!hasSelection);
            copyItem.setDisable(!hasSelection);
            pasteItem.setDisable(!systemClipboard.hasString());
            selectAllItem.setDisable(editor.getLength() == 0);
        });
        editor.setContextMenu(contextMenu);
    }

    /** 为预览区 TextFlow 启用鼠标拖选：拖动时高亮选中字符，释放后复制到剪贴板；并提供右键菜单"复制整段" */
    private void setupSelectableTextFlow(TextFlow flow) {
        final int[] selStart = {-1};
        final List<Text> highlighted = new ArrayList<>();
        final java.util.Map<Text, String> originalStyles = new java.util.IdentityHashMap<>();

        // 右键菜单：复制整段（TextFlow 不是 Control，无 setContextMenu，用 ContextMenuRequested 事件手动弹出）
        MenuItem copyAllItem = new MenuItem("复制整段");
        copyAllItem.setOnAction(e -> {
            StringBuilder sb = new StringBuilder();
            for (javafx.scene.Node n : flow.getChildren()) {
                if (n instanceof Text t) sb.append(t.getText());
            }
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(sb.toString());
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
        });
        ContextMenu flowMenu = new ContextMenu(copyAllItem);
        flow.setOnContextMenuRequested(e -> {
            flowMenu.show(flow, e.getScreenX(), e.getScreenY());
            e.consume();
        });

        // 鼠标按下：记录起始字符索引，清除上次高亮
        flow.setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            clearSelection(highlighted, originalStyles);
            javafx.scene.text.HitInfo hit = flow.hitTest(new javafx.geometry.Point2D(e.getX(), e.getY()));
            selStart[0] = hit.getInsertionIndex();
            flow.requestFocus();
        });

        // 鼠标拖动：高亮范围内的 Text（用浅蓝背景效果，这里改 fill 为蓝色并加 underline）
        flow.setOnMouseDragged(e -> {
            if (selStart[0] < 0) return;
            javafx.scene.text.HitInfo hit = flow.hitTest(new javafx.geometry.Point2D(e.getX(), e.getY()));
            int selEnd = hit.getInsertionIndex();
            int start = Math.min(selStart[0], selEnd);
            int end = Math.max(selStart[0], selEnd);
            clearSelection(highlighted, originalStyles);
            int cum = 0;
            for (javafx.scene.Node n : flow.getChildren()) {
                if (!(n instanceof Text)) continue;
                Text t = (Text) n;
                int tStart = cum;
                int tEnd = cum + t.getText().length();
                cum = tEnd;
                if (tEnd <= start || tStart >= end) continue;
                originalStyles.putIfAbsent(t, t.getStyle() == null ? "" : t.getStyle());
                t.setStyle(originalStyles.get(t) + " -fx-fill: #1565c0; -fx-underline: true;");
                highlighted.add(t);
            }
        });

        // 鼠标释放：拼接选中字符并复制到剪贴板，1.5 秒后清除高亮
        flow.setOnMouseReleased(e -> {
            if (selStart[0] < 0) return;
            javafx.scene.text.HitInfo hit = flow.hitTest(new javafx.geometry.Point2D(e.getX(), e.getY()));
            int selEnd = hit.getInsertionIndex();
            int start = Math.min(selStart[0], selEnd);
            int end = Math.max(selStart[0], selEnd);
            selStart[0] = -1;
            if (start == end) return;
            StringBuilder sb = new StringBuilder();
            int cum = 0;
            for (javafx.scene.Node n : flow.getChildren()) {
                if (!(n instanceof Text)) continue;
                Text t = (Text) n;
                int tStart = cum;
                int tEnd = cum + t.getText().length();
                cum = tEnd;
                int s = Math.max(start, tStart);
                int en = Math.min(end, tEnd);
                if (s < en) sb.append(t.getText(), s - tStart, en - tStart);
            }
            if (sb.length() > 0) {
                javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
                cc.putString(sb.toString());
                javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            }
            PauseTransition clear = new PauseTransition(javafx.util.Duration.millis(1500));
            clear.setOnFinished(ev -> clearSelection(highlighted, originalStyles));
            clear.play();
        });
    }

    private void clearSelection(List<Text> highlighted, java.util.Map<Text, String> originalStyles) {
        for (Text t : highlighted) {
            t.setStyle(originalStyles.getOrDefault(t, ""));
        }
        highlighted.clear();
        originalStyles.clear();
    }

    /** 渲染代码块：按语言做轻量语法高亮，放入带背景的容器，左侧带行号 */
    private Node renderCodeBlock(String literal, String info) {
        String lang = info == null ? "" : info.trim().toLowerCase();
        List<Text> parts = new ArrayList<>();
        highlightCode(literal, lang, parts);
        if (parts.isEmpty()) {
            Text t = new Text(literal);
            t.setStyle("-fx-fill: #333; -fx-font-family: 'Consolas','Courier New',monospace; -fx-font-size: 12px;");
            parts.add(t);
        }
        TextFlow flow = new TextFlow(parts.toArray(new Text[0]));
        flow.setPadding(new Insets(8, 12, 8, 12));

        // 生成代码块行号
        String[] codeLines = literal.split("\n", -1);
        StringBuilder lineNumText = new StringBuilder();
        for (int i = 1; i <= codeLines.length; i++) {
            lineNumText.append(i).append("\n");
        }
        Text lineNumTextNode = new Text(lineNumText.toString());
        lineNumTextNode.setStyle("-fx-fill: #aaa; -fx-font-family: 'Consolas','Courier New',monospace; -fx-font-size: 12px;");
        TextFlow lineNumFlow = new TextFlow(lineNumTextNode);
        lineNumFlow.setPadding(new Insets(8, 6, 8, 4));

        // 使用 HBox 包裹行号和代码
        HBox codeBox = new HBox(lineNumFlow, flow);
        codeBox.setStyle("-fx-background-color: #f6f8fa;");
        codeBox.setPadding(new Insets(0));

        // 右上角复制按钮：点击复制代码内容到剪贴板
        Label copyBtn = new Label();
        copyBtn.setGraphic(FontAwesomeIconFactory.get().createIcon(FontAwesomeIcon.COPY, "12"));
        copyBtn.setStyle("-fx-background-color: transparent; -fx-cursor: hand; " +
                "-fx-text-fill: #888; -fx-padding: 2;");
        copyBtn.setTooltip(new Tooltip("复制代码"));
        copyBtn.setOnMouseClicked(e -> {
            javafx.scene.input.ClipboardContent cc = new javafx.scene.input.ClipboardContent();
            cc.putString(literal);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cc);
            // 切换图标为对勾反馈
            copyBtn.setGraphic(FontAwesomeIconFactory.get().createIcon(FontAwesomeIcon.CHECK, "12"));
            copyBtn.setStyle("-fx-background-color: transparent; -fx-cursor: hand; " +
                    "-fx-text-fill: #28a745; -fx-padding: 2;");
            PauseTransition revert = new PauseTransition(javafx.util.Duration.millis(1200));
            revert.setOnFinished(ev -> {
                copyBtn.setGraphic(FontAwesomeIconFactory.get().createIcon(FontAwesomeIcon.COPY, "12"));
                copyBtn.setStyle("-fx-background-color: transparent; -fx-cursor: hand; " +
                        "-fx-text-fill: #888; -fx-padding: 2;");
            });
            revert.play();
        });

        // 整体容器带圆角边框，复制按钮悬浮于右上角
        StackPane pane = new StackPane(codeBox, copyBtn);
        pane.setStyle("-fx-background-color: #f6f8fa; -fx-background-radius: 4; " +
                "-fx-border-color: #e0e0e0; -fx-border-radius: 4;");
        StackPane.setAlignment(codeBox, Pos.CENTER);
        StackPane.setAlignment(copyBtn, Pos.TOP_RIGHT);
        StackPane.setMargin(copyBtn, new Insets(4, 6, 0, 0));
        VBox.setMargin(pane, new Insets(0, 5, 0, 5));
        return pane;
    }

    // 代码高亮配色
    private static final String HL_KEYWORD = "-fx-fill: #d73a49;";   // 关键字 红
    private static final String HL_STRING = "-fx-fill: #032f62;";   // 字符串 深蓝
    private static final String HL_COMMENT = "-fx-fill: #6a737d;";  // 注释 灰
    private static final String HL_NUMBER = "-fx-fill: #005cc5;";    // 数字 蓝
    private static final String HL_ANNOT = "-fx-fill: #6f42c1;";    // 注解/装饰器 紫
    private static final String HL_BASE = "-fx-fill: #24292e;";     // 默认文本
    private static final String HL_FUNC = "-fx-fill: #6f42c1;";     // 函数名 紫

    /** 各语言关键字集合（按规范语言名分组，每组包含该语言全部关键字）。 */
    private static final java.util.Map<String, java.util.Set<String>> LANG_KEYWORDS = new java.util.HashMap<>();
    static {
        // Java
        LANG_KEYWORDS.put("java", new java.util.HashSet<>(java.util.Arrays.asList(
                "abstract","assert","boolean","break","byte","case","catch","char","class","const",
                "continue","default","do","double","else","enum","extends","final","finally","float",
                "for","goto","if","implements","import","instanceof","int","interface","long","native",
                "new","package","private","protected","public","return","short","static","strictfp",
                "super","switch","synchronized","this","throw","throws","transient","try","void",
                "volatile","while","true","false","null","var","yield","record","sealed","permits"
        )));
        // Kotlin
        LANG_KEYWORDS.put("kotlin", new java.util.HashSet<>(java.util.Arrays.asList(
                "as","break","class","continue","do","else","false","for","fun","if","in","interface",
                "is","null","object","package","return","super","this","throw","true","try","typealias",
                "val","var","when","while","by","catch","finally","get","import","init","out","override",
                "private","protected","public","internal","sealed","data","lateinit","open","abstract",
                "companion","inline","operator","infix","crossinline","suspend","tailrec","vararg","reified"
        )));
        // Scala
        LANG_KEYWORDS.put("scala", new java.util.HashSet<>(java.util.Arrays.asList(
                "abstract","case","catch","class","def","do","else","extends","false","final","finally",
                "for","if","implicit","import","lazy","match","new","null","object","override","package",
                "private","protected","return","sealed","super","this","throw","trait","try","true","type",
                "val","var","while","with","yield","given","using","enum","export","then"
        )));
        // JavaScript / TypeScript
        java.util.Set<String> jsKeywords = new java.util.HashSet<>(java.util.Arrays.asList(
                "break","case","catch","class","const","continue","debugger","default","delete","do",
                "else","export","extends","finally","for","function","if","import","in","instanceof",
                "new","return","super","switch","this","throw","try","typeof","var","void","while","with",
                "yield","let","static","true","false","null","undefined","async","await","of","as"
        ));
        LANG_KEYWORDS.put("javascript", jsKeywords);
        LANG_KEYWORDS.put("js", jsKeywords);
        java.util.Set<String> tsKeywords = new java.util.HashSet<>(jsKeywords);
        tsKeywords.addAll(java.util.Arrays.asList(
                "interface","type","enum","implements","private","protected","public","readonly","abstract",
                "is","keyof","infer","namespace","declare","module","symbol","bigint","never","unknown","any"
        ));
        LANG_KEYWORDS.put("typescript", tsKeywords);
        LANG_KEYWORDS.put("ts", tsKeywords);
        // Python
        java.util.Set<String> pyKeywords = new java.util.HashSet<>(java.util.Arrays.asList(
                "False","None","True","and","as","assert","async","await","break","class","continue",
                "def","del","elif","else","except","finally","for","from","global","if","import","in",
                "is","lambda","nonlocal","not","or","pass","raise","return","try","while","with","yield",
                "print","match","case","self","cls"
        ));
        LANG_KEYWORDS.put("python", pyKeywords);
        LANG_KEYWORDS.put("py", pyKeywords);
        // SQL
        LANG_KEYWORDS.put("sql", new java.util.HashSet<>(java.util.Arrays.asList(
                "select","where","insert","update","delete","create","table","drop","alter","into","values",
                "set","join","left","right","inner","outer","group","by","order","having","limit","distinct",
                "primary","key","foreign","references","index","unique","between","like","exists","union",
                "all","and","or","not","in","is","as","from","on","using","with","case","when","then","else",
                "end","if","begin","commit","rollback","grant","revoke","database","schema","view","trigger",
                "procedure","function","null","true","false","asc","desc","count","sum","avg","min","max"
        )));
        // Go
        java.util.Set<String> goKeywords = new java.util.HashSet<>(java.util.Arrays.asList(
                "break","case","chan","const","continue","default","defer","else","fallthrough","for",
                "func","go","goto","if","import","interface","map","package","range","return","select",
                "struct","switch","type","var","true","false","nil","iota","make","len","cap","new","append",
                "panic","recover","print","println"
        ));
        LANG_KEYWORDS.put("go", goKeywords);
        LANG_KEYWORDS.put("golang", goKeywords);
        // Rust
        java.util.Set<String> rsKeywords = new java.util.HashSet<>(java.util.Arrays.asList(
                "as","break","const","continue","crate","else","enum","extern","false","fn","for","if",
                "impl","in","let","loop","match","mod","move","mut","pub","ref","return","self","Self",
                "static","struct","super","trait","true","type","unsafe","use","where","while","async",
                "await","dyn","union","box","macro","yield"
        ));
        LANG_KEYWORDS.put("rust", rsKeywords);
        LANG_KEYWORDS.put("rs", rsKeywords);
        // C / C++
        java.util.Set<String> cppKeywords = new java.util.HashSet<>(java.util.Arrays.asList(
                "alignas","alignof","and","auto","bool","break","case","catch","char","class","const",
                "constexpr","continue","decltype","default","delete","do","double","else","enum","explicit",
                "export","extern","false","float","for","friend","goto","if","inline","int","long","mutable",
                "namespace","new","noexcept","nullptr","operator","or","private","protected","public",
                "register","reinterpret_cast","return","short","signed","sizeof","static","static_cast",
                "struct","switch","template","this","throw","true","try","typedef","typename","union",
                "unsigned","using","virtual","void","volatile","while","std","size_t"
        ));
        LANG_KEYWORDS.put("cpp", cppKeywords);
        LANG_KEYWORDS.put("c++", cppKeywords);
        LANG_KEYWORDS.put("c", new java.util.HashSet<>(java.util.Arrays.asList(
                "auto","break","case","char","const","continue","default","do","double","else","enum",
                "extern","float","for","goto","if","inline","int","long","register","restrict","return",
                "short","signed","sizeof","static","struct","switch","typedef","union","unsigned","void",
                "volatile","while","NULL","size_t","malloc","free","printf","scanf"
        )));
        // PHP
        LANG_KEYWORDS.put("php", new java.util.HashSet<>(java.util.Arrays.asList(
                "abstract","and","array","as","break","callable","case","catch","class","clone","const",
                "continue","declare","default","die","do","echo","else","elseif","empty","enddeclare",
                "endfor","endforeach","endif","endswitch","endwhile","eval","exit","extends","final","finally",
                "fn","for","foreach","function","global","goto","if","implements","include","include_once",
                "instanceof","insteadof","interface","isset","list","match","namespace","new","or","print",
                "private","protected","public","require","require_once","return","static","switch","throw",
                "trait","try","unset","use","var","while","xor","yield","true","false","null"
        )));
        // Shell
        java.util.Set<String> shKeywords = new java.util.HashSet<>(java.util.Arrays.asList(
                "if","then","else","elif","fi","for","do","done","while","until","case","esac","in","function",
                "return","break","continue","exit","echo","printf","read","local","declare","export","unset",
                "source","alias","shift","test","true","false","cd","pwd","ls","grep","sed","awk","cat",
                "mkdir","rm","cp","mv","chmod","chown","find","xargs","which","env","set","trap"
        ));
        LANG_KEYWORDS.put("shell", shKeywords);
        LANG_KEYWORDS.put("sh", shKeywords);
        LANG_KEYWORDS.put("bash", shKeywords);
        LANG_KEYWORDS.put("zsh", shKeywords);
        // Ruby
        java.util.Set<String> rbKeywords = new java.util.HashSet<>(java.util.Arrays.asList(
                "BEGIN","END","alias","and","begin","break","case","class","def","defined?","do","else","elsif",
                "end","ensure","false","for","if","in","module","next","nil","not","or","redo","rescue","retry",
                "return","self","super","then","true","undef","unless","until","when","while","yield","require",
                "require_relative","include","extend","attr_accessor","attr_reader","attr_writer","puts","print"
        ));
        LANG_KEYWORDS.put("ruby", rbKeywords);
        LANG_KEYWORDS.put("rb", rbKeywords);
        // C#
        java.util.Set<String> csKeywords = new java.util.HashSet<>(java.util.Arrays.asList(
                "abstract","as","base","bool","break","byte","case","catch","char","checked","class","const",
                "continue","decimal","default","delegate","do","double","else","enum","event","explicit",
                "extern","false","finally","fixed","float","for","foreach","goto","if","implicit","in","int",
                "interface","internal","is","lock","long","namespace","new","null","object","operator","out",
                "override","params","private","protected","public","readonly","ref","return","sbyte","sealed",
                "short","sizeof","stackalloc","static","string","struct","switch","this","throw","true","try",
                "typeof","uint","ulong","unchecked","unsafe","ushort","using","virtual","void","volatile","while",
                "var","async","await","yield","get","set"
        ));
        LANG_KEYWORDS.put("csharp", csKeywords);
        LANG_KEYWORDS.put("cs", csKeywords);
        // Swift
        LANG_KEYWORDS.put("swift", new java.util.HashSet<>(java.util.Arrays.asList(
                "associatedtype","class","deinit","enum","extension","fileprivate","func","import","init",
                "inout","internal","let","open","operator","private","protocol","public","static","struct",
                "subscript","typealias","var","break","case","continue","default","defer","do","else","fallthrough",
                "for","guard","if","in","repeat","return","switch","where","while","as","Any","catch","false","is",
                "nil","rethrows","super","self","Self","throw","throws","true","try","async","await","actor","some"
        )));
    }

    /** 通用关键字集合：未识别语言时回退使用。 */
    private static final java.util.Set<String> COMMON_KEYWORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "if","else","for","while","do","return","break","continue","switch","case","default",
            "true","false","null","class","def","func","function","fn","fun","import","package","new",
            "this","super","self","try","catch","finally","throw","throws","public","private","protected",
            "static","const","let","var","val","void","int","string","bool","boolean","float","double"
    ));

    /** 按语言获取关键字集合；未识别时返回通用集合。 */
    private static java.util.Set<String> keywordsFor(String lang) {
        java.util.Set<String> kws = LANG_KEYWORDS.get(lang);
        return kws != null ? kws : COMMON_KEYWORDS;
    }

    /** 行注释前缀（按语言）：`//` 用于 C 系，`#` 用于脚本/配置类，`--` 用于 SQL */
    private static String lineCommentPrefix(String lang) {
        return switch (lang) {
            case "sql" -> "--";
            case "python", "py", "ruby", "rb", "perl", "pl", "shell", "sh", "bash", "zsh",
                 "yaml", "yml", "toml", "ini", "properties", "conf", "dockerfile", "makefile",
                 "ps1", "powershell", "r", "plaintext" -> "#";
            default -> "//"; // java, js, ts, go, rust, c, cpp, php, css, json, kotlin, scala, swift...
        };
    }

    /** 是否支持块注释 `/* *\/`：C 系语言及 SQL 支持，脚本类（Python/Shell/Ruby 等）不支持。 */
    private static boolean hasBlockComment(String lang) {
        return switch (lang) {
            case "python", "py", "ruby", "rb", "perl", "pl", "shell", "sh", "bash", "zsh",
                 "yaml", "yml", "toml", "ini", "properties", "conf", "dockerfile", "makefile",
                 "ps1", "powershell", "r" -> false;
            default -> true; // java, js, ts, go, rust, c, cpp, php, css, json, kotlin, scala, swift, sql...
        };
    }

    /** 轻量正则语法高亮：按语言做差异化关键字/注释/字符串着色，结果追加到 out。非线程安全（仅 JavaFX 线程调用）。 */
    private void highlightCode(String code, String lang, List<Text> out) {
        if (code == null || code.isEmpty()) return;
        String linePrefix = lineCommentPrefix(lang);
        java.util.Set<String> keywords = keywordsFor(lang);
        boolean blockCommentEnabled = hasBlockComment(lang);
        // Python 三引号字符串（多行，含文档字符串）
        boolean pythonStrings = "python".equals(lang) || "py".equals(lang);

        // token 顺序：块注释 → 三引号字符串(Python) → 字符串(含模板/原始) → 行注释 → 数字 → 注解 → 标识符(关键字/函数)
        String blockComment = "/\\*[\\s\\S]*?\\*/";
        String tripleString = pythonStrings ? "(\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?''')" : null;
        String stringPat = "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`";
        String lineComment = java.util.regex.Pattern.quote(linePrefix) + "[^\\n]*";
        String number = "\\b\\d[\\d_]*\\.?\\d*([eE][+-]?\\d+)?[fFdDuUlL]?\\b|0[xX][0-9a-fA-F_]+|0[bB][01_]+";
        String annotation = "@[A-Za-z_][A-Za-z0-9_]*";
        String ident = "[A-Za-z_$][A-Za-z0-9_$]*";

        StringBuilder pat = new StringBuilder();
        if (blockCommentEnabled) {
            pat.append("(?<BLOCK>").append(blockComment).append(")|");
        }
        if (tripleString != null) {
            pat.append("(?<TRIPLE>").append(tripleString).append(")|");
        }
        pat.append("(?<STRING>").append(stringPat).append(")");
        pat.append("|(?<LINE>").append(lineComment).append(")");
        pat.append("|(?<NUMBER>").append(number).append(")");
        pat.append("|(?<ANNOT>").append(annotation).append(")");
        pat.append("|(?<IDENT>").append(ident).append(")");

        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pat.toString());
        java.util.regex.Matcher m = p.matcher(code);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                out.add(codeText(code.substring(last, m.start()), HL_BASE));
            }
            String style;
            if (blockCommentEnabled && m.group("BLOCK") != null) {
                style = HL_COMMENT;
            } else if (tripleString != null && m.group("TRIPLE") != null) {
                style = HL_STRING;
            } else if (m.group("LINE") != null) {
                style = HL_COMMENT;
            } else if (m.group("STRING") != null) {
                style = HL_STRING;
            } else if (m.group("NUMBER") != null) {
                style = HL_NUMBER;
            } else if (m.group("ANNOT") != null) {
                style = HL_ANNOT;
            } else {
                String word = m.group("IDENT");
                if (keywords.contains(word)) {
                    style = HL_KEYWORD;
                } else {
                    // 函数调用：标识符后跟空白*(
                    int end = m.end();
                    int j = end;
                    while (j < code.length() && (code.charAt(j) == ' ' || code.charAt(j) == '\t')) j++;
                    style = (j < code.length() && code.charAt(j) == '(') ? HL_FUNC : HL_BASE;
                }
            }
            out.add(codeText(m.group(), style));
            last = m.end();
        }
        if (last < code.length()) {
            out.add(codeText(code.substring(last), HL_BASE));
        }
    }

    private Text codeText(String content, String style) {
        Text t = new Text(content);
        t.setStyle(style + " -fx-font-family: 'Consolas','Courier New',monospace; -fx-font-size: 12px;");
        return t;
    }

    /**
     * 由原始表格行（表头行、分隔行、数据行）渲染为 JavaFX GridPane。
     * 表头加粗+浅灰底，单元格按分隔行声明的对齐方式排版，带边框。
     */
    private void renderTableLines(java.util.List<String> tableLines, InlineStyle base, ObservableList<Node> target) {
        if (tableLines.size() < 2) return;
        // 第 0 行：表头；第 1 行：分隔（决定对齐）；其余：数据行
        java.util.List<String> headerCells = splitTableRow(tableLines.get(0));
        java.util.List<String> delimCells = splitTableRow(tableLines.get(1));
        int colCount = headerCells.size();
        // 解析每列对齐
        CellAlign[] aligns = new CellAlign[colCount];
        for (int c = 0; c < colCount; c++) {
            aligns[c] = (c < delimCells.size()) ? parseAlign(delimCells.get(c)) : CellAlign.LEFT;
        }

        GridPane grid = new GridPane();
        grid.setHgap(0);
        grid.setVgap(0);
        grid.setStyle("-fx-border-color: #dfe2e5; -fx-border-width: 1 1 0 0; -fx-background-color: white;");
        // 表格宽度贴合内容（最右侧边框跟随最后一列），不被 VBox/ScrollPane 拉伸到面板最右侧
        grid.setMaxWidth(Region.USE_PREF_SIZE);

        int row = 0;
        // 表头
        for (int c = 0; c < colCount; c++) {
            grid.add(tableCell(headerCells.get(c), aligns[c], true, base), c, row);
        }
        row++;
        // 数据行
        for (int r = 2; r < tableLines.size(); r++) {
            java.util.List<String> cells = splitTableRow(tableLines.get(r));
            for (int c = 0; c < colCount; c++) {
                String content = c < cells.size() ? cells.get(c) : "";
                grid.add(tableCell(content, aligns[c], false, base), c, row);
            }
            row++;
        }
        target.add(grid);
    }

    /** 渲染单个表格单元格为带边框/背景的 StackPane，内部为解析行内格式的 TextFlow */
    private StackPane tableCell(String content, CellAlign align, boolean header, InlineStyle base) {
        List<Text> inlines = new ArrayList<>();
        if (content.isEmpty()) {
            inlines.add(styledText("", base));
        } else {
            // 用 commonmark 解析单元格内的行内格式（粗体/代码/链接等）
            org.commonmark.node.Node cellDoc = PREVIEW_PARSER.parse(content);
            org.commonmark.node.Node first = cellDoc.getFirstChild();
            if (first instanceof Paragraph p) {
                renderInline(p, base, inlines);
            } else {
                inlines.add(styledText(content, base));
            }
        }
        if (header) {
            for (Text t : inlines) {
                String s = t.getStyle() == null ? "" : t.getStyle();
                t.setStyle(s + " -fx-font-weight: bold;");
            }
        }
        TextFlow flow = new TextFlow(inlines.toArray(new Text[0]));
        setupSelectableTextFlow(flow);
        StackPane pane = new StackPane(flow);
        pane.setPadding(new Insets(6, 10, 6, 10));
        String bg = header ? "#f6f8fa" : "white";
        String alignCss = switch (align) {
            case CENTER -> "-fx-alignment: center; -fx-text-alignment: center;";
            case RIGHT -> "-fx-alignment: center-right; -fx-text-alignment: right;";
            default -> "-fx-alignment: center-left; -fx-text-alignment: left;";
        };
        pane.setStyle("-fx-border-color: #dfe2e5; -fx-border-width: 0 0 1 1; " +
                "-fx-background-color: " + bg + "; " + alignCss);
        return pane;
    }

    private void renderList(org.commonmark.node.Node list, InlineStyle base, ObservableList<Node> target, boolean ordered, int start) {
        VBox listBox = new VBox(2);
        int index = start;
        for (org.commonmark.node.Node item = list.getFirstChild(); item != null; item = item.getNext()) {
            if (!(item instanceof ListItem)) continue;
            String marker = ordered ? (index + ". ") : "• ";
            org.commonmark.node.Node firstChild = item.getFirstChild();

            List<Text> inlines = new ArrayList<>();
            inlines.add(styledText(marker, base));
            if (firstChild instanceof Paragraph p) {
                renderInline(p, base, inlines);
            } else {
                renderInline(item, base, inlines);
            }
            TextFlow flow = new TextFlow(inlines.toArray(new Text[0]));
            setupSelectableTextFlow(flow);
            HBox itemBox = new HBox(flow);
            itemBox.setPadding(new Insets(0, 0, 0, 16));
            listBox.getChildren().add(itemBox);

            // 处理 list item 中的后续子块（嵌套列表/段落等）
            org.commonmark.node.Node child = firstChild != null ? firstChild.getNext() : null;
            while (child != null) {
                if (child instanceof BulletList) {
                    renderList(child, base, listBox.getChildren(), false, 1);
                } else if (child instanceof OrderedList ol2) {
                    renderList(ol2, base, listBox.getChildren(), true, ol2.getStartNumber());
                } else if (child instanceof Paragraph p2) {
                    List<Text> sub = new ArrayList<>();
                    renderInline(p2, base, sub);
                    TextFlow subFlow = new TextFlow(sub.toArray(new Text[0]));
                    subFlow.setPadding(new Insets(0, 0, 0, 16));
                    setupSelectableTextFlow(subFlow);
                    listBox.getChildren().add(subFlow);
                } else {
                    renderBlock(child, base, listBox.getChildren());
                }
                child = child.getNext();
            }
            index++;
        }
        target.add(listBox);
    }

    private void renderInline(org.commonmark.node.Node node, InlineStyle style, List<Text> out) {
        for (org.commonmark.node.Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof org.commonmark.node.Text textNode) {
                out.add(styledText(textNode.getLiteral(), style));
            } else if (child instanceof Code codeNode) {
                InlineStyle cs = style.copy();
                cs.code = true;
                out.add(styledText(codeNode.getLiteral(), cs));
            } else if (child instanceof Emphasis) {
                InlineStyle cs = style.copy();
                cs.italic = true;
                renderInline(child, cs, out);
            } else if (child instanceof StrongEmphasis) {
                InlineStyle cs = style.copy();
                cs.bold = true;
                renderInline(child, cs, out);
            } else if (child instanceof Link link) {
                InlineStyle cs = style.copy();
                cs.linkUrl = link.getDestination();
                renderInline(child, cs, out);
            } else if (child instanceof Image) {
                InlineStyle cs = style.copy();
                cs.italic = true;
                out.add(styledText("[图片]", cs));
                renderInline(child, cs, out);
            } else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
                out.add(new Text("\n"));
            } else if (child instanceof HtmlInline html) {
                out.add(styledText(html.getLiteral(), style));
            } else {
                renderInline(child, style, out);
            }
        }
    }

    private Text styledText(String content, InlineStyle style) {
        Text t = new Text(content);
        StringBuilder sb = new StringBuilder("-fx-font-size: 13px;");
        if (style.bold) sb.append(" -fx-font-weight: bold;");
        if (style.italic) sb.append(" -fx-font-posture: italic;");
        if (style.code) {
            sb.append(" -fx-font-family: 'Consolas','Courier New',monospace;");
            sb.append(" -fx-fill: #c7254e;");
        } else if (style.linkUrl != null) {
            sb.append(" -fx-fill: #1a73e8; -fx-underline: true;");
        } else if (style.quote) {
            sb.append(" -fx-fill: #666;");
        } else {
            sb.append(" -fx-fill: #333;");
        }
        t.setStyle(sb.toString());
        return t;
    }

    private int headingSize(int level) {
        return switch (level) {
            case 1 -> 24;
            case 2 -> 20;
            case 3 -> 17;
            case 4 -> 15;
            case 5 -> 13;
            case 6 -> 12;
            default -> 13;
        };
    }

    private static class InlineStyle {
        boolean bold = false;
        boolean italic = false;
        boolean code = false;
        String linkUrl = null;
        boolean quote = false;

        InlineStyle copy() {
            InlineStyle c = new InlineStyle();
            c.bold = bold;
            c.italic = italic;
            c.code = code;
            c.linkUrl = linkUrl;
            c.quote = quote;
            return c;
        }
    }

    // ==================== 保存 ====================

    public void save() {
        if (saving) return;
        saving = true;
        final String content = editor.getText();
        storage.save(content, () -> {
            // 成功回调（已在 JavaFX 线程）
            saving = false;
            originalContent = content;
            if (modified) {
                modified = false;
                notifyTitleChange();
            }
            // 保存成功不再弹窗，标题栏的 * 消失即为成功反馈
        }, (errMsg) -> {
            // 错误回调（已在 JavaFX 线程）
            saving = false;
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("保存失败");
            alert.setHeaderText(null);
            alert.setContentText(errMsg);
            alert.showAndWait();
        });
    }

    // ==================== 状态回调 ====================

    public boolean isModified() {
        return modified;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDisplayTitle() {
        return (modified ? "*" : "") + displayName;
    }

    public void setOnTitleChange(Consumer<String> callback) {
        this.onTitleChange = callback;
    }

    private void notifyTitleChange() {
        if (onTitleChange != null) onTitleChange.accept(getDisplayTitle());
    }

    // ==================== 静态工具：下载文件内容 ====================

    /**
     * 异步下载 markdown 文件内容
     * @param onLoaded 回调（在 JavaFX 线程），传入内容或异常 message（第二个参数非null表示失败）
     */
    public static void loadMarkdownContent(ConnectionConfig config, String bucket, String key,
                                            java.util.function.BiConsumer<String, String> onLoaded) {
        new Thread(() -> {
            try {
                boolean isOSS = config.getType() == ConnectType.ALIYUN_OSS;
                InputStream is = isOSS
                        ? OssService.getObjectStream(config, bucket, key)
                        : S3Service.getObjectStream(config, bucket, key);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                is.close();
                String content = baos.toString(StandardCharsets.UTF_8);
                Platform.runLater(() -> onLoaded.accept(content, null));
            } catch (Exception e) {
                Platform.runLater(() -> onLoaded.accept(null, e.getMessage()));
            }
        }, "MD-Load").start();
    }

    // ==================== 导出 HTML / PDF ====================

    /** 导出 HTML：复用 commonmark HtmlRenderer + 自定义代码块高亮渲染器 + 自渲染 GFM 表格 */
    private static final HtmlRenderer HTML_RENDERER = HtmlRenderer.builder()
            .nodeRendererFactory(ctx -> new CodeBlockHtmlRenderer(ctx))
            .build();

    private static final String EXPORT_CSS =
            "body{font-family:'Segoe UI','Microsoft YaHei',-apple-system,sans-serif;color:#24292e;" +
            "font-size:15px;line-height:1.6;max-width:780px;margin:16px auto;padding:0 16px;}" +
            "h1{font-size:2em;margin:0.6em 0 0.3em;}h2{font-size:1.5em;}h3{font-size:1.25em;}" +
            "h4{font-size:1em;}h5{font-size:0.875em;}h6{font-size:0.85em;color:#666;}" +
            "h1,h2,h3,h4,h5,h6{color:#1163a6;font-weight:600;}" +
            "a{color:#1a73e8;text-decoration:none;}a:hover{text-decoration:underline;}" +
            "code{background:#f6f8fa;padding:2px 6px;border-radius:3px;font-family:Consolas,'Courier New',monospace;color:#c725e9;font-size:90%;}" +
            "pre{background:#f6f8fa;border:1px solid #e0e0e0;border-radius:4px;padding:10px;overflow:auto;}" +
            "pre code{background:none;color:#24292e;padding:0;font-size:12px;}" +
            "blockquote{border-left:3px solid #1a73e8;margin:0.4em 0;padding:0.2em 0 0.2em 12px;color:#666;}" +
            "table{border-collapse:collapse;margin:0.5em 0;}" +
            "th,td{border:1px solid #dfe2e5;padding:6px 10px;}" +
            "th{background:#f6f8fa;font-weight:bold;}" +
            "img{max-width:100%;}hr{border:none;border-top:1px solid #e0e0e0;margin:1em 0;}";

    /** 代码块 HTML 渲染器：把 ```/缩进 代码块渲染为带语法高亮 span 的 <pre><code> */
    private static final class CodeBlockHtmlRenderer implements NodeRenderer {
        private final HtmlWriter html;

        CodeBlockHtmlRenderer(HtmlNodeRendererContext ctx) {
            this.html = ctx.getWriter();
        }

        @Override
        public java.util.Set<Class<? extends org.commonmark.node.Node>> getNodeTypes() {
            return java.util.Set.of(FencedCodeBlock.class, IndentedCodeBlock.class);
        }

        @Override
        public void render(org.commonmark.node.Node node) {
            if (node instanceof FencedCodeBlock fcb) {
                html.raw("<pre><code>" + codeToHtml(fcb.getLiteral(), fcb.getInfo()) + "</code></pre>\n");
            } else if (node instanceof IndentedCodeBlock icb) {
                html.raw("<pre><code>" + codeToHtml(icb.getLiteral(), "") + "</code></pre>\n");
            }
        }
    }

    /** 导出 HTML：FileChooser 选目标文件，写入完整 HTML 文档 */
    private void exportHtml() {
        FileChooser fc = new FileChooser();
        fc.setTitle("导出 HTML");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("HTML 文件", "*.html"));
        fc.setInitialFileName(safeFileName(displayName) + ".html");
        File f = fc.showSaveDialog(getScene().getWindow());
        if (f == null) return;
        try {
            Files.writeString(f.toPath(), buildFullHtml(editor.getText()), StandardCharsets.UTF_8);
            Alert a = new Alert(Alert.AlertType.INFORMATION);
            a.setTitle("导出成功");
            a.setHeaderText(null);
            a.setContentText("已导出到：" + f.getAbsolutePath());
            a.showAndWait();
        } catch (Exception e) {
            Alert a = new Alert(Alert.AlertType.ERROR);
            a.setTitle("导出失败");
            a.setHeaderText(null);
            a.setContentText(e.getMessage());
            a.showAndWait();
        }
    }

    /** 拼装完整 HTML 文档：样式 + 分段（文本交 commonmark，表格自渲染） */
    private String buildFullHtml(String md) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"zh\">\n<head>\n<meta charset=\"utf-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        sb.append("<title>").append(escapeHtml(displayName)).append("</title>\n");
        sb.append("<style>").append(EXPORT_CSS).append("</style>\n");
        sb.append("</head>\n<body>\n");
        String[] lines = md.split("\n", -1);
        int i = 0;
        StringBuilder textBuf = new StringBuilder();
        while (i < lines.length) {
            if (i + 1 < lines.length && isTableRow(lines[i]) && isDelimiterRow(lines[i + 1])) {
                flushTextToHtml(textBuf, sb);
                List<String> tableLines = new ArrayList<>();
                tableLines.add(lines[i]);
                tableLines.add(lines[i + 1]);
                int j = i + 2;
                while (j < lines.length && isTableRow(lines[j])) {
                    tableLines.add(lines[j]);
                    j++;
                }
                sb.append(tableToHtml(tableLines));
                i = j;
            } else {
                if (textBuf.length() > 0) textBuf.append('\n');
                textBuf.append(lines[i]);
                i++;
            }
        }
        flushTextToHtml(textBuf, sb);
        sb.append("</body>\n</html>\n");
        return sb.toString();
    }

    private void flushTextToHtml(StringBuilder textBuf, StringBuilder out) {
        if (textBuf.length() == 0) return;
        String text = textBuf.toString();
        textBuf.setLength(0);
        out.append(HTML_RENDERER.render(PREVIEW_PARSER.parse(text)));
    }

    /** 表格行 → <table> HTML（表头/对齐/转义均处理） */
    private String tableToHtml(List<String> tableLines) {
        if (tableLines.size() < 2) return "";
        List<String> headerCells = splitTableRow(tableLines.get(0));
        List<String> delimCells = splitTableRow(tableLines.get(1));
        int colCount = headerCells.size();
        CellAlign[] aligns = new CellAlign[colCount];
        for (int c = 0; c < colCount; c++) {
            aligns[c] = (c < delimCells.size()) ? parseAlign(delimCells.get(c)) : CellAlign.LEFT;
        }
        StringBuilder sb = new StringBuilder("<table>\n<thead>\n<tr>");
        for (int c = 0; c < colCount; c++) {
            sb.append("<th").append(alignStyle(aligns[c])).append(">")
              .append(escapeHtml(headerCells.get(c))).append("</th>");
        }
        sb.append("</tr>\n</thead>\n<tbody>\n");
        for (int r = 2; r < tableLines.size(); r++) {
            List<String> cells = splitTableRow(tableLines.get(r));
            sb.append("<tr>");
            for (int c = 0; c < colCount; c++) {
                String content = c < cells.size() ? cells.get(c) : "";
                sb.append("<td").append(alignStyle(aligns[c])).append(">")
                  .append(escapeHtml(content)).append("</td>");
            }
            sb.append("</tr>\n");
        }
        sb.append("</tbody>\n</table>\n");
        return sb.toString();
    }

    private static String alignStyle(CellAlign align) {
        return switch (align) {
            case CENTER -> " style=\"text-align:center\"";
            case RIGHT -> " style=\"text-align:right\"";
            default -> " style=\"text-align:left\"";
        };
    }

    /** 代码 → 带语法高亮 span 的 HTML（与预览区 highlightCode 同款分词与配色） */
    private static String codeToHtml(String code, String lang) {
        if (code == null || code.isEmpty()) return "";
        String l = lang == null ? "" : lang.trim().toLowerCase();
        String linePrefix = lineCommentPrefix(l);
        java.util.Set<String> keywords = keywordsFor(l);
        boolean blockCommentEnabled = hasBlockComment(l);
        boolean pythonStrings = "python".equals(l) || "py".equals(l);

        String blockComment = "/\\*[\\s\\S]*?\\*/";
        String tripleString = pythonStrings ? "(\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?''')" : null;
        String stringPat = "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`";
        String lineComment = java.util.regex.Pattern.quote(linePrefix) + "[^\\n]*";
        String number = "\\b\\d[\\d_]*\\.?\\d*([eE][+-]?\\d+)?[fFdDuUlL]?\\b|0[xX][0-9a-fA-F_]+|0[bB][01_]+";
        String annotation = "@[A-Za-z_][A-Za-z0-9_]*";
        String ident = "[A-Za-z_$][A-Za-z0-9_$]*";

        StringBuilder pat = new StringBuilder();
        if (blockCommentEnabled) {
            pat.append("(?<BLOCK>").append(blockComment).append(")|");
        }
        if (tripleString != null) {
            pat.append("(?<TRIPLE>").append(tripleString).append(")|");
        }
        pat.append("(?<STRING>").append(stringPat).append(")");
        pat.append("|(?<LINE>").append(lineComment).append(")");
        pat.append("|(?<NUMBER>").append(number).append(")");
        pat.append("|(?<ANNOT>").append(annotation).append(")");
        pat.append("|(?<IDENT>").append(ident).append(")");

        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pat.toString());
        java.util.regex.Matcher m = p.matcher(code);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            if (m.start() > last) sb.append(escapeHtml(code.substring(last, m.start())));
            String color;
            if (blockCommentEnabled && m.group("BLOCK") != null) {
                color = "#6a737d";
            } else if (tripleString != null && m.group("TRIPLE") != null) {
                color = "#032f62";
            } else if (m.group("LINE") != null) {
                color = "#6a737d";
            } else if (m.group("STRING") != null) {
                color = "#032f62";
            } else if (m.group("NUMBER") != null) {
                color = "#005cc5";
            } else if (m.group("ANNOT") != null) {
                color = "#6f42c1";
            } else {
                String word = m.group("IDENT");
                if (keywords.contains(word)) {
                    color = "#d73a49";
                } else {
                    int end = m.end();
                    int j = end;
                    while (j < code.length() && (code.charAt(j) == ' ' || code.charAt(j) == '\t')) j++;
                    color = (j < code.length() && code.charAt(j) == '(') ? "#6f42c1" : "#24292e";
                }
            }
            sb.append("<span style=\"color:").append(color).append("\">")
              .append(escapeHtml(m.group())).append("</span>");
            last = m.end();
        }
        if (last < code.length()) sb.append(escapeHtml(code.substring(last)));
        return sb.toString();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 导出 PDF：通过系统打印对话框（可选 Microsoft Print to PDF 等虚拟打印机）输出 */
    private void exportPdf() {
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) {
            Alert a = new Alert(Alert.AlertType.ERROR);
            a.setTitle("导出 PDF");
            a.setHeaderText(null);
            a.setContentText("未找到可用打印机，无法导出。");
            a.showAndWait();
            return;
        }
        if (!job.showPrintDialog(getScene().getWindow())) {
            job.cancelJob();
            return;
        }
        // 复用预览渲染（VBox + TextFlow + GridPane）作为打印内容
        VBox printRoot = new VBox(6);
        printRoot.setPadding(new Insets(12, 16, 12, 16));
        printRoot.setStyle("-fx-background-color: white;");
        renderMarkdown(editor.getText(), new InlineStyle(), printRoot.getChildren());

        PageLayout pl = job.getPrinter().createPageLayout(Paper.A4, PageOrientation.PORTRAIT, 36, 36, 36, 36);
        job.getJobSettings().setPageLayout(pl);
        printRoot.setPrefWidth(pl.getPrintableWidth());

        boolean ok = job.printPage(printRoot);
        if (ok) {
            ok = job.endJob();
        } else {
            job.cancelJob();
        }
        Alert a = new Alert(ok ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR);
        a.setTitle(ok ? "导出成功" : "导出失败");
        a.setHeaderText(null);
        a.setContentText(ok ? "已发送到打印机：" + job.getJobSettings().getJobName() : "打印失败，请重试。");
        a.showAndWait();
    }

    // ==================== 查找 / 替换 ====================
    // Ctrl+F 弹出查找标签，Ctrl+R 弹出替换标签；查找内容输入框与正则开关在标签上方共享；
    // 正则开关为 iOS 风格自定义控件（参考 HostsFilePane.Switch）。查找通过选中当前匹配 + 状态计数呈现。

    /** 显示查找/替换对话框，replace=true 进入“替换”标签 */
    private void showFindReplace(boolean replace) {
        if (findReplaceStage == null) {
            buildFindReplaceDialog();
        }
        findReplaceTabs.getSelectionModel().select(replace ? 1 : 0);
        if (!findReplaceStage.isShowing()) {
            findReplaceStage.show();
        } else {
            findReplaceStage.toFront();
        }
        getActiveFindField().requestFocus();
        updateMatches();
    }

    /** 构建查找/替换对话框（仅一次）。每个标签各自拥有查找框与正则开关，二者状态双向同步。 */
    private void buildFindReplaceDialog() {
        findReplaceStage = new Stage();
        findReplaceStage.initOwner(getScene().getWindow());
        findReplaceStage.initModality(Modality.NONE);
        findReplaceStage.setTitle("查找与替换");
        findReplaceStage.setResizable(false);

        // 文本框统一样式：1px 蓝边、白底、无聚焦毛玻璃效果
        String fieldStyle = "-fx-border-color:#1a73e8; -fx-border-width:1px; -fx-background-color:white; " +
                "-fx-background-insets:0; -fx-background-radius:0; -fx-focus-color:transparent; -fx-faint-focus-color:transparent;";

        // 两个标签各自的查找框
        findFieldFind = new TextField();
        findFieldFind.setPrefWidth(320);
        findFieldFind.setStyle(fieldStyle);
        findFieldFind.setPromptText("输入查找内容（回车=下一个，Shift+回车=上一个）");
        findFieldReplace = new TextField();
        findFieldReplace.setPrefWidth(320);
        findFieldReplace.setStyle(fieldStyle);
        findFieldReplace.setPromptText("输入查找内容（回车=下一个，Shift+回车=上一个）");
        replaceField = new TextField();
        replaceField.setPrefWidth(320);
        replaceField.setStyle(fieldStyle);
        replaceField.setPromptText("替换为（正则模式下支持 $1 等回引用）");

        // 两个正则开关：状态同步到 regexMode
        findRegexSwitch = new Switch();
        findRegexSwitch.setOnToggle(() -> {
            regexMode = findRegexSwitch.isSelected();
            replaceRegexSwitch.syncSelected(regexMode);
            updateMatches();
        });
        replaceRegexSwitch = new Switch();
        replaceRegexSwitch.setOnToggle(() -> {
            regexMode = replaceRegexSwitch.isSelected();
            findRegexSwitch.syncSelected(regexMode);
            updateMatches();
        });

        findStatusLabel = new Label();
        findStatusLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");
        replaceStatusLabel = new Label();
        replaceStatusLabel.setStyle("-fx-text-fill: #666; -fx-font-size: 12px;");

        // 两个查找框的回车/Shift+回车 导航
        for (TextField f : new TextField[]{findFieldFind, findFieldReplace}) {
            f.setOnAction(e -> goToMatch(true));
            f.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.ENTER && e.isShiftDown()) {
                    goToMatch(false);
                    e.consume();
                }
            });
        }

        // 查找标签内容：查找框 + 正则开关 + 按钮 + 状态
        Button findPrev = new Button("上一个");
        Button findNext = new Button("下一个");
        findPrev.setOnAction(e -> goToMatch(false));
        findNext.setOnAction(e -> goToMatch(true));
        HBox findSearchRow = new HBox(8, fieldLabel("查找:", 80), findFieldFind);
        findSearchRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(findFieldFind, Priority.ALWAYS);
        HBox findOptRow = new HBox(8, fieldLabel("正则表达式", 80), findRegexSwitch);
        findOptRow.setAlignment(Pos.CENTER_LEFT);
        HBox findBtns = new HBox(8, findPrev, findNext);
        VBox findContent = new VBox(8, findSearchRow, findOptRow, findBtns, findStatusLabel);
        findContent.setPadding(new Insets(5));
        findContent.setStyle("-fx-background-color: white;");

        // 替换标签内容：查找框 + 正则开关 + 替换为 + 按钮 + 状态
        Button repPrev = new Button("上一个");
        Button repNext = new Button("下一个");
        Button repOne = new Button("替换");
        Button repAll = new Button("全部替换");
        repPrev.setOnAction(e -> goToMatch(false));
        repNext.setOnAction(e -> goToMatch(true));
        repOne.setOnAction(e -> replaceCurrent());
        repAll.setOnAction(e -> replaceAll());
        HBox repSearchRow = new HBox(8, fieldLabel("查找:", 80), findFieldReplace);
        repSearchRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(findFieldReplace, Priority.ALWAYS);
        HBox repOptRow = new HBox(8, fieldLabel("正则表达式", 80), replaceRegexSwitch);
        repOptRow.setAlignment(Pos.CENTER_LEFT);
        HBox replaceRow = new HBox(8, fieldLabel("替换为:", 80), replaceField);
        replaceRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(replaceField, Priority.ALWAYS);
        HBox repBtns = new HBox(8, repPrev, repNext, repOne, repAll);
        VBox replaceContent = new VBox(8, repSearchRow, replaceRow, repOptRow, repBtns, replaceStatusLabel);
        replaceContent.setPadding(new Insets(5));
        replaceContent.setStyle("-fx-background-color: white;");

        Tab tabFind = new Tab("查找", findContent);
        Tab tabReplace = new Tab("替换", replaceContent);
        tabFind.setClosable(false);
        tabReplace.setClosable(false);
        findReplaceTabs = new TabPane(tabFind, tabReplace);

        VBox root = new VBox(8);
        root.setPadding(new Insets(0));
        root.setStyle("-fx-background-color: white;");
        root.getChildren().add(findReplaceTabs);

        Scene scene = new Scene(root);
        // 标签样式参考设计表（connect-tree.css 的 Firefox 风格标签）
        scene.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        // Esc 关闭
        root.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                findReplaceStage.hide();
                e.consume();
            }
        });
        findReplaceStage.setScene(scene);
        findReplaceStage.setOnHidden(e -> {
            findMatches.clear();
            findIndex = -1;
        });

        // 两个查找框文本双向同步，任一变化 → 重新计算匹配
        findFieldFind.textProperty().addListener((o, a, b) -> {
            if (syncingFind) return;
            syncingFind = true;
            findFieldReplace.setText(b);
            syncingFind = false;
            updateMatches();
        });
        findFieldReplace.textProperty().addListener((o, a, b) -> {
            if (syncingFind) return;
            syncingFind = true;
            findFieldFind.setText(b);
            syncingFind = false;
            updateMatches();
        });
        // 编辑器文本变化时（如手动编辑/替换后）刷新匹配
        editor.textProperty().addListener((o, a, b) -> {
            if (findReplaceStage.isShowing()) updateMatches();
        });

        DialogPositionUtil.centerOnOwner(findReplaceStage, this);
    }

    /** 当前选中标签对应的查找框 */
    private TextField getActiveFindField() {
        if (findReplaceTabs == null) return findFieldFind;
        return findReplaceTabs.getSelectionModel().getSelectedIndex() == 0 ? findFieldFind : findFieldReplace;
    }

    /** 重新计算全部匹配，并定位到光标之后的第一处 */
    private void updateMatches() {
        findMatches.clear();
        findIndex = -1;
        String needle = getActiveFindField().getText();
        String hay = editor.getText();
        if (needle.isEmpty()) {
            setStatus("");
            return;
        }
        try {
            if (regexMode) {
                Matcher m = Pattern.compile(needle).matcher(hay);
                while (m.find()) findMatches.add(new int[]{m.start(), m.end()});
            } else {
                int from = 0;
                while (true) {
                    int idx = hay.indexOf(needle, from);
                    if (idx < 0) break;
                    findMatches.add(new int[]{idx, idx + needle.length()});
                    from = idx + needle.length();
                }
            }
        } catch (Exception ex) {
            setStatus("正则错误: " + ex.getMessage());
            return;
        }
        if (findMatches.isEmpty()) {
            setStatus("无匹配");
            return;
        }
        // 定位到 caret 之后（含）的第一处
        int caret = editor.getCaretPosition();
        findIndex = 0;
        for (int i = 0; i < findMatches.size(); i++) {
            if (findMatches.get(i)[0] >= caret) { findIndex = i; break; }
            findIndex = i;
        }
        showCurrentMatch();
    }

    /** 跳转到上一处/下一处匹配 */
    private void goToMatch(boolean forward) {
        if (findMatches.isEmpty()) { updateMatches(); return; }
        if (forward) findIndex = (findIndex + 1) % findMatches.size();
        else findIndex = (findIndex - 1 + findMatches.size()) % findMatches.size();
        showCurrentMatch();
    }

    /** 选中并滚动到当前匹配 */
    private void showCurrentMatch() {
        if (findIndex < 0 || findIndex >= findMatches.size()) return;
        int[] r = findMatches.get(findIndex);
        editor.selectRange(r[0], r[1]);
        editor.requestFollowCaret();
        setStatus((findIndex + 1) + " / " + findMatches.size());
    }

    /** 替换当前匹配（正则模式下支持 $1 等回引用） */
    private void replaceCurrent() {
        if (findIndex < 0 || findIndex >= findMatches.size()) return;
        int[] r = findMatches.get(findIndex);
        String rep = replaceField.getText();
        String replacement;
        if (regexMode) {
            try {
                Matcher m = Pattern.compile(getActiveFindField().getText()).matcher(editor.getText());
                if (m.find(r[0])) {
                    StringBuffer sbuf = new StringBuffer();
                    m.appendReplacement(sbuf, rep);
                    replacement = sbuf.substring(r[0]);
                } else {
                    replacement = rep;
                }
            } catch (Exception ex) {
                setStatus("替换错误: " + ex.getMessage());
                return;
            }
        } else {
            replacement = rep;
        }
        editor.replaceText(r[0], r[1], replacement);
        // replaceText 触发 editor 文本变化监听 → updateMatches 会自动刷新
        goToMatch(true);
    }

    /** 全部替换 */
    private void replaceAll() {
        String needle = getActiveFindField().getText();
        String rep = replaceField.getText();
        String text = editor.getText();
        String result;
        try {
            if (regexMode) {
                result = Pattern.compile(needle).matcher(text).replaceAll(rep);
            } else {
                result = text.replace(needle, rep);
            }
        } catch (Exception ex) {
            setStatus("替换错误: " + ex.getMessage());
            return;
        }
        editor.replaceText(result);
        setStatus("已替换");
    }

    private void setStatus(String s) {
        if (findStatusLabel != null) findStatusLabel.setText(s);
        if (replaceStatusLabel != null) replaceStatusLabel.setText(s);
    }

    /** 固定宽度、右对齐的标签：使多行 "xx:" 的冒号垂直对齐，后续输入框起点一致 */
    private static Label fieldLabel(String text, double width) {
        Label l = new Label(text);
        l.setPrefWidth(width);
        l.setMinWidth(width);
        l.setMaxWidth(width);
        l.setAlignment(Pos.CENTER_RIGHT);
        return l;
    }

    /** 文件名安全化：去除 Windows 非法字符 */
    private static String safeFileName(String name) {
        if (name == null || name.isBlank()) return "导出";
        String s = name.trim();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append("\\/:*?\"<>|".indexOf(c) >= 0 ? '_' : c);
        }
        String r = sb.toString();
        return r.isBlank() ? "导出" : r;
    }

    /**
     * iOS 风格的开关控件（参考 HostsFilePane.Switch：StackPane + Region 轨道 + Circle 滑块）。
     * 用于查找/替换对话框的“正则表达式”开关。
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
