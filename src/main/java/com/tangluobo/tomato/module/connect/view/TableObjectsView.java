package com.tangluobo.tomato.module.connect.view;

import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.DatabaseNodeData;
import com.tangluobo.tomato.module.connect.service.DatabaseService;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 对象视图：展示当前数据库/Schema 下所有表和视图对象。
 * 支持三种视图方式：图标列表、详细列表（表格）、ER视图（实体关系图），
 * 通过右下角状态栏最右侧的切换控件切换。
 * 工具栏提供：打开表、设计表、新建表、删除表、导入向导、导出向导、刷新。
 */
public class TableObjectsView extends BorderPane {

    /** 视图类型 */
    private enum ViewType { ICON, DETAIL, ER }

    /** 对象类型 */
    private enum ObjectType { TABLE, VIEW }

    /**
     * 表复制剪贴板自定义数据格式。
     * 内容格式（UTF-8 字符串）：
     *   TOMATO_COPY_TABLES\n{connId}\n{srcDb}\n{srcSchema}\n{table1}\n{table2}...
     * srcSchema 为空时占位为空字符串。
     */
    public static final DataFormat COPY_TABLES_FORMAT = new DataFormat("application/x-tomato-copy-tables");
    /** 剪贴板内容前缀，用于在纯文本模式下识别 */
    public static final String COPY_TABLES_PREFIX = "TOMATO_COPY_TABLES";

    /** 对象信息 POJO（详细列表所有列） */
    private static class ObjectInfo {
        final String name;
        final ObjectType type;
        final String comment;
        final String engine;
        final String autoIncrement;
        final String updateTime;
        final String dataLength;
        final String rows;
        ObjectInfo(String name, ObjectType type, String comment,
                   String engine, String autoIncrement, String updateTime,
                   String dataLength, String rows) {
            this.name = name; this.type = type; this.comment = comment;
            this.engine = engine; this.autoIncrement = autoIncrement;
            this.updateTime = updateTime; this.dataLength = dataLength;
            this.rows = rows;
        }
    }

    /** 外键关系 POJO（用于 ER 视图） */
    private static class FkRelation {
        final String sourceTable;
        final String sourceColumns;
        final String refTable;
        final String refColumns;
        FkRelation(String sourceTable, String sourceColumns, String refTable, String refColumns) {
            this.sourceTable = sourceTable;
            this.sourceColumns = sourceColumns;
            this.refTable = refTable;
            this.refColumns = refColumns;
        }
    }

    /**
     * 对象操作回调接口：由 handler 注入实现，解耦视图与业务逻辑。
     */
    public interface ObjectOperations {
        /** 打开表/视图数据 */
        void openObject(DatabaseNodeData data);
        /** 设计表结构 */
        void designObject(DatabaseNodeData data);
        /** 新建表 */
        void createTable();
        /** 删除表/视图（批量，视图内部已刷新，无需再刷新） */
        void deleteObjects(List<DatabaseNodeData> dataList);
        /** 清空表数据（DELETE FROM，仅作用于表） */
        void clearTables(List<DatabaseNodeData> dataList);
        /** 截断表（TRUNCATE TABLE，仅作用于表） */
        void truncateTables(List<DatabaseNodeData> dataList);
        /** 重命名表/视图，成功后回调 onSuccess */
        void renameObject(DatabaseNodeData data, String newName, Runnable onSuccess);
        /** 导入向导 */
        void importWizard();
        /** 导出向导 */
        void exportWizard();
        /** 粘贴表：从剪贴板读取复制的表元信息，弹出数据传输对话框进行批量复制（目标为当前视图对应的连接/数据库） */
        void pasteTables();
    }

    private final ConnectionConfig config;
    private final String databaseName;
    private final String schemaName;
    private final ObjectOperations operations;

    // 三种视图容器
    private ScrollPane iconScroll;
    private FlowPane iconFlowPane;
    private TableView<ObservableList<String>> detailTableView;
    private ScrollPane erScroll;
    private Pane erCanvas;
    /** 中间容器（三种视图 + 加载指示器 + 详细列表橡皮筋矩形） */
    private StackPane centerStack;

    // 状态栏组件
    private Label countLabel;
    private ToggleButton iconBtn, detailBtn, erBtn;
    private ToggleGroup viewToggleGroup;
    private ProgressIndicator loadingIndicator;

    // 右键菜单
    private ContextMenu contextMenu;

    // 工具栏按钮（按选中状态启用/禁用）
    private Button openTableBtn, designTableBtn, deleteTableBtn;

    // 数据缓存
    private List<ObjectInfo> objects = new ArrayList<>();
    /** 全量对象缓存（搜索过滤前的原始列表） */
    private List<ObjectInfo> allObjects = new ArrayList<>();
    private List<FkRelation> foreignKeys = new ArrayList<>();
    /** 主选中对象（最后操作的，用于打开/设计/删除按钮） */
    private ObjectInfo selectedObject;
    /** 全部选中对象集合（支持多选、Ctrl+A、拖动范围选择） */
    private final Set<ObjectInfo> selectedObjects = new LinkedHashSet<>();
    /** 图标项映射：ObjectInfo → VBox，用于高亮和范围选择命中检测 */
    private final Map<ObjectInfo, VBox> iconBoxMap = new HashMap<>();
    /** Shift 范围选择锚点 */
    private ObjectInfo anchorObject;
    /** 防止详细列表选择监听器与图标视图循环同步的标志 */
    private boolean syncingDetail = false;

    // 重命名编辑状态
    /** 单点击定时器（用于区分单击编辑 vs 双击打开） */
    private javafx.animation.Timeline singleClickTimer;
    /** 上一次点击前已选中的对象（用于判断"已选中再点击"进入编辑） */
    private ObjectInfo clickedBeforeObject;
    /** 上一次点击前已选中的行索引（-1 = 无单选）；与 clickedBeforeObject 配合，
     *  确保只有"按下前该行已选中"才进入编辑，避免从 A 点击到 B 时误入编辑 */
    private int clickedBeforeIndex = -1;
    /** 正在编辑的对象（null = 未编辑） */
    private ObjectInfo editingObject;
    /** 图标视图悬浮编辑浮窗（Popup，不参与布局且不受裁剪） */
    private javafx.stage.Popup iconEditPopup;
    /** 浮窗内的 TextField */
    private TextField iconEditField;
    /** 详细列表悬浮编辑浮窗（Popup，不进入 cell 编辑态，避免影响行高） */
    private javafx.stage.Popup detailEditPopup;
    /** 详细列表浮窗内的 TextField */
    private TextField detailEditField;

    // 橡皮筋选择框（图标视图，位于 iconScroll 内部）
    private Rectangle rubberBandRect;
    private double rubberBandStartX, rubberBandStartY;
    private boolean isRubberBanding = false;

    // 橡皮筋选择框（详细列表视图，位于 centerStack 最上层）
    private Rectangle detailRubberRect;
    /** 详细列表橡皮筋起点（centerStack坐标） */
    private double detailMarqueeStartX, detailMarqueeStartY;
    /** 详细列表当前拖拽是否显示橡皮筋（仅从数据行下方空白区域开始时） */
    private boolean detailMarqueeActive = false;

    // 搜索相关
    private TextField searchField;
    /** 字段缓存：key=对象名, value=该对象所有字段 [字段名, 字段注释] 列表 */
    private final Map<String, List<String[]>> columnsCache = new HashMap<>();
    /** 字段缓存是否已加载完成（用于搜索时判断是否可按字段名/字段注释过滤） */
    private volatile boolean columnsLoaded = false;

    // 图标缓存
    private Image tableImg;
    private Image viewImg;

    public TableObjectsView(ConnectionConfig config, String databaseName, String schemaName,
                            ObjectOperations operations) {
        this.config = config;
        this.databaseName = databaseName;
        this.schemaName = schemaName;
        this.operations = operations;

        try { tableImg = new Image(getClass().getResourceAsStream("/images/connect/table.png")); } catch (Exception e) { tableImg = null; }
        try { viewImg = new Image(getClass().getResourceAsStream("/images/connect/view.png")); } catch (Exception e) { viewImg = null; }

        setPadding(Insets.EMPTY);
        setStyle("-fx-padding: 0; -fx-background-insets: 0; -fx-border-insets: 0; -fx-background-color: #ffffff;");
        getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());

        setTop(createToolBar());

        // 中间容器：StackPane 容纳三种视图 + 加载指示器
        loadingIndicator = new ProgressIndicator();
        loadingIndicator.setMaxSize(40, 40);
        loadingIndicator.setVisible(false);

        centerStack = new StackPane();
        centerStack.setStyle("-fx-background-color: #ffffff; -fx-padding: 0; -fx-background-insets: 0;");

        // 详细列表拖拽范围选择矩形：半透明蓝色，鼠标透明，置于最上层（样式与图标视图橡皮筋一致）
        detailRubberRect = new Rectangle();
        detailRubberRect.setFill(Color.rgb(53, 146, 203, 0.15));
        detailRubberRect.setStroke(Color.web("#3592CB"));
        detailRubberRect.setStrokeWidth(1);
        detailRubberRect.setMouseTransparent(true);
        detailRubberRect.setManaged(false);
        detailRubberRect.setVisible(false);

        centerStack.getChildren().addAll(createIconView(), createDetailView(), createErView(), loadingIndicator, detailRubberRect);
        setCenter(centerStack);

        setBottom(createStatusBar());

        // 全局事件过滤器：在捕获阶段拦截 Ctrl+A / Ctrl+C / Ctrl+V，防止事件传递到左侧连接树
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown() && e.getCode() == javafx.scene.input.KeyCode.A) {
                selectAll();
                e.consume();
            } else if (e.isControlDown() && e.getCode() == javafx.scene.input.KeyCode.C) {
                // 仅当焦点在对象视图内部时复制选中表元信息到剪贴板
                if (!selectedObjects.isEmpty() && isFocusInside()) {
                    copySelectedTablesToClipboard();
                    e.consume();
                }
            } else if (e.isControlDown() && e.getCode() == javafx.scene.input.KeyCode.V) {
                // 粘贴表：焦点在对象视图内部且剪贴板有内容时，弹出数据传输对话框
                if (isFocusInside() && isClipboardHasTables() && operations != null) {
                    operations.pasteTables();
                    e.consume();
                }
            }
        });

        // 注册右键菜单：图标视图 + 详细列表
        setupContextMenu();

        switchView(ViewType.ICON);
        updateButtonStates();
        loadData();
    }

    // ==================== 顶部工具栏 ====================

    private HBox createToolBar() {
        HBox toolBar = new HBox(2);
        toolBar.setPadding(new Insets(4, 8, 4, 8));
        toolBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        toolBar.setAlignment(Pos.CENTER_LEFT);

        openTableBtn = createToolBarButton("打开表", createImageIcon("/images/connect/table_open.png", 16));
        openTableBtn.setOnAction(e -> handleOpenTable());

        designTableBtn = createToolBarButton("设计表", createImageIcon("/images/connect/table_edit.png", 16));
        designTableBtn.setOnAction(e -> handleDesignTable());

        Button createTableBtn = createToolBarButton("新建表", createImageIcon("/images/connect/table_add.png", 16));
        createTableBtn.setOnAction(e -> {
            if (operations != null) operations.createTable();
        });

        deleteTableBtn = createToolBarButton("删除表", createImageIcon("/images/connect/table_drop.png", 16));
        deleteTableBtn.setOnAction(e -> handleDeleteTable());

        Separator sep1 = new Separator();
        sep1.setOrientation(javafx.geometry.Orientation.VERTICAL);
        sep1.setPadding(new Insets(2, 4, 2, 4));

        Button importBtn = createToolBarButton("导入向导", createImageIcon("/images/connect/table_import.png", 16));
        importBtn.setOnAction(e -> {
            if (operations != null) operations.importWizard();
        });

        Button exportBtn = createToolBarButton("导出向导", createImageIcon("/images/connect/table_export.png", 16));
        exportBtn.setOnAction(e -> {
            if (operations != null) operations.exportWizard();
        });

        Separator sep2 = new Separator();
        sep2.setOrientation(javafx.geometry.Orientation.VERTICAL);
        sep2.setPadding(new Insets(2, 4, 2, 4));

        Button refreshBtn = createToolBarButton("刷新", createImageIcon("/images/connect/refresh.png", 16));
        refreshBtn.setOnAction(e -> loadData());

        // 弹性间隔：将搜索框推到工具栏右侧
        Region toolBarSpacer = new Region();
        HBox.setHgrow(toolBarSpacer, Priority.ALWAYS);

        Node searchBox = createSearchBox();

        toolBar.getChildren().addAll(
                openTableBtn, designTableBtn, createTableBtn, deleteTableBtn,
                sep1, importBtn, exportBtn, sep2, refreshBtn,
                toolBarSpacer, searchBox);
        return toolBar;
    }

    /**
     * 创建搜索框：样式参考工具模块左上角搜索栏（放大镜图标 + 透明输入框）。
     * 支持按表名、表注释、字段名、字段注释过滤对象列表。
     */
    private Node createSearchBox() {
        HBox box = new HBox(6);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(4, 8, 4, 8));
        box.setStyle("-fx-background-color: #f0f0f0; -fx-background-radius: 4; -fx-border-radius: 4;");
        box.setPrefWidth(220);
        box.setMaxWidth(220);

        SVGPath searchIcon = new SVGPath();
        searchIcon.setContent("M15.5 14h-.79l-.28-.27A6.471 6.471 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z");
        searchIcon.setFill(Color.web("#999999"));
        searchIcon.setScaleX(0.7);
        searchIcon.setScaleY(0.7);

        searchField = new TextField();
        searchField.setPromptText("搜索表名/注释/字段...");
        searchField.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0; -fx-font-size: 12px; -fx-prompt-text-fill: #999; -fx-text-fill: #333;");
        HBox.setHgrow(searchField, Priority.ALWAYS);

        // 输入即过滤（防抖：输入停止 300ms 后执行，避免频繁过滤）
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            applySearchDelayed(newVal);
        });

        box.getChildren().addAll(searchIcon, searchField);
        return box;
    }

    private javafx.animation.Timeline searchTimeline;

    /** 防抖搜索：输入停止 300ms 后执行过滤 */
    private void applySearchDelayed(String keyword) {
        if (searchTimeline != null) {
            searchTimeline.stop();
        }
        searchTimeline = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(300), e -> applySearch(keyword)));
        searchTimeline.play();
    }

    /**
     * 按关键字过滤对象列表。
     * 匹配范围：表名、表注释、字段名、字段注释（字段缓存加载完成后生效）。
     * 关键字为空时恢复显示全部对象。
     */
    private void applySearch(String keyword) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        if (kw.isEmpty()) {
            objects = new ArrayList<>(allObjects);
        } else {
            List<ObjectInfo> filtered = new ArrayList<>();
            for (ObjectInfo obj : allObjects) {
                if (matchesKeyword(obj, kw)) {
                    filtered.add(obj);
                }
            }
            objects = filtered;
        }

        // 移除已不在过滤结果中的选中项
        selectedObjects.removeIf(o -> !objects.contains(o));
        if (selectedObject != null && !objects.contains(selectedObject)) {
            selectedObject = selectedObjects.isEmpty() ? null : selectedObjects.iterator().next();
        }

        populateIconView();
        populateDetailView();
        populateErView();
        syncDetailSelection();
        updateCountLabel();
        updateButtonStates();
    }

    /** 判断对象是否匹配关键字（表名/表注释/字段名/字段注释） */
    private boolean matchesKeyword(ObjectInfo obj, String kw) {
        if (obj.name != null && obj.name.toLowerCase().contains(kw)) return true;
        if (obj.comment != null && obj.comment.toLowerCase().contains(kw)) return true;
        // 字段名/字段注释：依赖 columnsCache，未加载完成则跳过字段匹配
        if (columnsLoaded) {
            List<String[]> cols = columnsCache.get(obj.name);
            if (cols != null) {
                for (String[] col : cols) {
                    if (col.length >= 1 && col[0] != null && col[0].toLowerCase().contains(kw)) return true;
                    if (col.length >= 2 && col[1] != null && col[1].toLowerCase().contains(kw)) return true;
                }
            }
        }
        return false;
    }

    /** 后台预加载所有对象的字段信息（字段名+注释），用于搜索字段名/字段注释 */
    private void preloadColumns() {
        columnsLoaded = false;
        columnsCache.clear();
        if (allObjects.isEmpty()) {
            columnsLoaded = true;
            return;
        }
        new Thread(() -> {
            ReentrantLock connLock = DatabaseService.acquireUsageLock(config, databaseName);
            connLock.lock();
            try {
                Map<String, List<String[]>> cache = new HashMap<>();
                for (ObjectInfo obj : allObjects) {
                    try {
                        List<Map<String, String>> cols = DatabaseService.getTableColumns(config, databaseName, schemaName, obj.name);
                        List<String[]> arr = new ArrayList<>();
                        for (Map<String, String> c : cols) {
                            String colName = c.get("字段名");
                            String colComment = c.get("注释");
                            arr.add(new String[]{
                                    colName != null ? colName : "",
                                    colComment != null ? colComment : ""
                            });
                        }
                        cache.put(obj.name, arr);
                    } catch (Exception e) {
                        cache.put(obj.name, new ArrayList<>());
                    }
                }
                Platform.runLater(() -> {
                    columnsCache.clear();
                    columnsCache.putAll(cache);
                    columnsLoaded = true;
                    // 若搜索框已有内容，重新过滤以纳入字段匹配结果
                    if (searchField != null && !searchField.getText().trim().isEmpty()) {
                        applySearch(searchField.getText());
                    }
                });
            } finally {
                connLock.unlock();
            }
        }, "DB-PreloadColumns").start();
    }

    /** 更新底部状态栏计数（搜索时附加过滤提示） */
    private void updateCountLabel() {
        if (countLabel == null) return;
        long tableCount = objects.stream().filter(o -> o.type == ObjectType.TABLE).count();
        long viewCount = objects.size() - tableCount;
        String text = "共 " + objects.size() + " 个对象（表 " + tableCount + "，视图 " + viewCount + "）"
                + (foreignKeys.isEmpty() ? "" : "  |  " + foreignKeys.size() + " 个外键关系");
        if (!selectedObjects.isEmpty()) {
            text += "  |  已选中 " + selectedObjects.size() + " 个";
        }
        if (searchField != null && !searchField.getText().trim().isEmpty()) {
            text += "  |  已筛选（共 " + allObjects.size() + " 个）";
            if (!columnsLoaded) {
                text += "  |  字段加载中...";
            }
        }
        countLabel.setText(text);
    }

    /** 创建工具栏按钮（图标+文字），样式参考 TableStructureView.createToolBarButton */
    private Button createToolBarButton(String text, Node icon) {
        Button btn = new Button(text);
        btn.getStyleClass().add("toolbar-button");
        btn.setStyle("-fx-font-size: 12px; -fx-padding: 4 8; -fx-content-display: LEFT; -fx-graphic-text-gap: 4;");
        if (icon != null) {
            btn.setGraphic(icon);
        }
        return btn;
    }

    /** 从资源目录加载图标图片，返回指定尺寸的 ImageView */
    private Node createImageIcon(String resourcePath, int size) {
        try {
            Image img = new Image(getClass().getResourceAsStream(resourcePath));
            ImageView iv = new ImageView(img);
            iv.setFitWidth(size);
            iv.setFitHeight(size);
            iv.setPreserveRatio(true);
            return iv;
        } catch (Exception e) {
            return new Label("");
        }
    }

    // ==================== 工具栏按钮动作 ====================

    private void handleOpenTable() {
        if (selectedObjects.isEmpty() || operations == null) return;
        for (ObjectInfo obj : selectedObjects) {
            operations.openObject(buildNodeData(obj));
        }
    }

    private void handleDesignTable() {
        if (selectedObjects.isEmpty() || operations == null) return;
        for (ObjectInfo obj : selectedObjects) {
            operations.designObject(buildNodeData(obj));
        }
    }

    private void handleDeleteTable() {
        if (selectedObjects.isEmpty() || operations == null) return;
        List<DatabaseNodeData> list = new ArrayList<>();
        for (ObjectInfo obj : selectedObjects) {
            list.add(buildNodeData(obj));
        }
        operations.deleteObjects(list);
    }

    /** 清空表数据（DELETE FROM），仅作用于表类型对象 */
    private void handleClearTable() {
        if (selectedObjects.isEmpty() || operations == null) return;
        List<DatabaseNodeData> list = collectTableNodeData();
        if (list.isEmpty()) return;
        operations.clearTables(list);
    }

    /** 截断表（TRUNCATE TABLE），仅作用于表类型对象 */
    private void handleTruncateTable() {
        if (selectedObjects.isEmpty() || operations == null) return;
        List<DatabaseNodeData> list = collectTableNodeData();
        if (list.isEmpty()) return;
        operations.truncateTables(list);
    }

    /** 从选中对象中收集表类型（排除视图）的 DatabaseNodeData 列表 */
    private List<DatabaseNodeData> collectTableNodeData() {
        List<DatabaseNodeData> list = new ArrayList<>();
        for (ObjectInfo obj : selectedObjects) {
            if (obj.type == ObjectType.TABLE) {
                list.add(buildNodeData(obj));
            }
        }
        return list;
    }

    private DatabaseNodeData buildNodeData(ObjectInfo obj) {
        return new DatabaseNodeData(
                obj.type == ObjectType.TABLE ? DatabaseNodeData.NodeType.TABLE : DatabaseNodeData.NodeType.VIEW,
                obj.name, config, databaseName, schemaName);
    }

    /** 选中对象变化时更新按钮启用状态和状态栏计数 */
    private void updateButtonStates() {
        boolean hasSelection = !selectedObjects.isEmpty();
        if (openTableBtn != null) openTableBtn.setDisable(!hasSelection);
        if (designTableBtn != null) designTableBtn.setDisable(!hasSelection);
        if (deleteTableBtn != null) deleteTableBtn.setDisable(!hasSelection);
        updateCountLabel();
    }

    // ==================== 图标列表视图 ====================

    private Node createIconView() {
        iconFlowPane = new FlowPane();
        iconFlowPane.setPadding(new Insets(12, 12, 12, 12));
        iconFlowPane.setHgap(16);
        iconFlowPane.setVgap(16);
        iconFlowPane.setStyle("-fx-background-color: #ffffff;");

        // 橡皮筋选择框（半透明蓝色矩形）
        rubberBandRect = new Rectangle();
        rubberBandRect.setFill(Color.rgb(53, 146, 203, 0.15));
        rubberBandRect.setStroke(Color.web("#3592CB"));
        rubberBandRect.setStrokeWidth(1);
        rubberBandRect.setMouseTransparent(true);
        rubberBandRect.setManaged(false); // 不参与 StackPane 布局，由 setX/setY 精确定位
        rubberBandRect.setVisible(false);

        // StackPane 叠放 FlowPane 和橡皮筋矩形
        StackPane iconStack = new StackPane(iconFlowPane, rubberBandRect);
        iconStack.setStyle("-fx-background-color: #ffffff;");
        // FlowPane 顶左对齐，使其坐标原点与 StackPane 一致（修复橡皮筋矩形位置偏移）
        StackPane.setAlignment(iconFlowPane, Pos.TOP_LEFT);

        // 橡皮筋选择：在 FlowPane 背景按下时启动
        iconFlowPane.setOnMousePressed(e -> {
            iconScroll.requestFocus(); // 转移焦点到对象视图，防止 Ctrl+A 传到左侧树
            if (e.getTarget() == iconFlowPane) {
                // 在空白处按下：启动橡皮筋选择
                isRubberBanding = true;
                rubberBandStartX = e.getX();
                rubberBandStartY = e.getY();
                rubberBandRect.setX(e.getX());
                rubberBandRect.setY(e.getY());
                rubberBandRect.setWidth(0);
                rubberBandRect.setHeight(0);
                rubberBandRect.setVisible(true);
                // 非 Ctrl 时清除已有选择
                if (!e.isControlDown()) {
                    clearSelection();
                }
                e.consume();
            }
        });
        iconFlowPane.setOnMouseDragged(e -> {
            if (isRubberBanding) {
                double x = Math.min(e.getX(), rubberBandStartX);
                double y = Math.min(e.getY(), rubberBandStartY);
                double w = Math.abs(e.getX() - rubberBandStartX);
                double h = Math.abs(e.getY() - rubberBandStartY);
                rubberBandRect.setX(x);
                rubberBandRect.setY(y);
                rubberBandRect.setWidth(w);
                rubberBandRect.setHeight(h);
                // 实时高亮命中项
                updateRubberBandSelection(x, y, w, h, e.isControlDown());
                e.consume();
            }
        });
        iconFlowPane.setOnMouseReleased(e -> {
            if (isRubberBanding) {
                isRubberBanding = false;
                rubberBandRect.setVisible(false);
                e.consume();
            }
        });

        iconScroll = new ScrollPane(iconStack);
        iconScroll.setFitToWidth(true);
        iconScroll.setFitToHeight(true);
        iconScroll.setFocusTraversable(true);
        iconScroll.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-background-insets: 0; -fx-padding: 0; -fx-border-insets: 0;");
        iconScroll.getStyleClass().add("session-scroll-pane");

        return iconScroll;
    }

    private void populateIconView() {
        iconFlowPane.getChildren().clear();
        iconBoxMap.clear();
        for (ObjectInfo obj : objects) {
            VBox item = new VBox(4);
            item.setAlignment(Pos.CENTER);
            item.setPrefWidth(80);
            item.setCursor(Cursor.HAND);
            // 默认透明边框，与选中态几何尺寸一致，避免切换时闪烁/重排
            item.setStyle("-fx-background-color: transparent; -fx-background-radius: 4; -fx-border-color: transparent; -fx-border-radius: 4; -fx-border-width: 1px;");

            Image img = obj.type == ObjectType.TABLE ? tableImg : viewImg;
            if (img != null) {
                ImageView iv = new ImageView(img);
                iv.setFitWidth(32);
                iv.setFitHeight(32);
                item.getChildren().add(iv);
            }

            Label name = new Label(obj.name);
            name.setStyle("-fx-font-size: 12px;");
            name.setWrapText(false);
            name.setEllipsisString("...");
            name.setMaxWidth(76);
            name.setAlignment(Pos.CENTER);
            name.setTextAlignment(TextAlignment.CENTER);
            item.getChildren().add(name);

            // 鼠标悬停时显示完整表名，避免过长被截断
            Tooltip.install(item, new Tooltip(obj.name));

            item.setOnMousePressed(e -> {
                iconScroll.requestFocus(); // 转移焦点到对象视图，防止 Ctrl+A 传到左侧树
                // 记录本次点击前已选中的对象（用于判断"已选中再点击"进入编辑）
                clickedBeforeObject = (selectedObjects.size() == 1) ? selectedObjects.iterator().next() : null;
            });
            item.setOnMouseClicked(e -> {
                if (isRubberBanding) return; // 橡皮筋拖动中忽略点击
                // 右键：保持已选中项的多选状态（便于对多表操作）
                if (e.getButton() == javafx.scene.input.MouseButton.SECONDARY) {
                    if (!selectedObjects.contains(obj)) {
                        clearSelection();
                        addToSelection(obj);
                    }
                    e.consume();
                    return;
                }
                boolean ctrl = e.isControlDown();
                boolean shift = e.isShiftDown();

                // 双击：取消编辑定时器，执行打开
                if (e.getClickCount() == 2) {
                    if (singleClickTimer != null) {
                        singleClickTimer.stop();
                        singleClickTimer = null;
                    }
                    if (editingObject != null) {
                        cancelIconEdit();
                    }
                    handleOpenTable();
                    e.consume();
                    return;
                }

                // 单击：判断是否"已选中再点击"以进入重命名编辑
                boolean wasAlreadySelected = !ctrl && !shift
                        && clickedBeforeObject == obj
                        && selectedObjects.size() == 1
                        && selectedObjects.contains(obj)
                        && editingObject == null;
                if (wasAlreadySelected) {
                    final ObjectInfo objToEdit = obj;
                    if (singleClickTimer != null) {
                        singleClickTimer.stop();
                    }
                    singleClickTimer = new javafx.animation.Timeline(
                            new javafx.animation.KeyFrame(javafx.util.Duration.millis(300), ae -> {
                                if (editingObject == null
                                        && selectedObjects.size() == 1
                                        && selectedObjects.contains(objToEdit)) {
                                    startIconEdit(objToEdit);
                                }
                                singleClickTimer = null;
                            }));
                    singleClickTimer.play();
                    e.consume();
                    return;
                }

                // 正常选择逻辑
                if (shift && anchorObject != null) {
                    selectRange(anchorObject, obj);
                } else if (ctrl) {
                    toggleSelection(obj);
                } else {
                    clearSelection();
                    addToSelection(obj);
                }
                e.consume();
            });
            iconBoxMap.put(obj, item);
            iconFlowPane.getChildren().add(item);
        }
        updateIconHighlights();
    }

    // ==================== 重命名编辑（图标视图） ====================

    /** 开始图标视图编辑：用 Popup 浮窗悬浮在 Label 上方，完全不参与布局且不受裁剪 */
    private void startIconEdit(ObjectInfo obj) {
        VBox box = iconBoxMap.get(obj);
        if (box == null) return;
        editingObject = obj;

        // 找到 Label（第2个子节点，第1个是 ImageView）
        Label nameLabel = null;
        for (int i = 0; i < box.getChildren().size(); i++) {
            if (box.getChildren().get(i) instanceof Label) {
                nameLabel = (Label) box.getChildren().get(i);
                break;
            }
        }
        if (nameLabel == null) return;

        // 计算 Label 在屏幕上的位置（Popup 用屏幕坐标）
        Bounds labelSceneBounds = nameLabel.localToScene(nameLabel.getBoundsInLocal());
        Scene scene = nameLabel.getScene();
        if (scene == null || scene.getWindow() == null) return;
        Window window = scene.getWindow();
        double screenX = window.getX() + scene.getX() + labelSceneBounds.getMinX();
        double screenY = window.getY() + scene.getY() + labelSceneBounds.getMinY();

        // 创建 TextField
        iconEditField = new TextField(obj.name);
        iconEditField.setStyle("-fx-padding: 2 6; -fx-font-size: 12px; -fx-background-color: white; -fx-border-color: #3592CB; -fx-border-width: 1.5; -fx-border-radius: 0; -fx-background-radius: 0;");
        // 宽度根据内容计算，允许比 Label 宽（覆盖其他表名）
        Text measureText = new Text(obj.name);
        measureText.setFont(Font.font(12));
        double contentWidth = measureText.getLayoutBounds().getWidth() + 20;
        double labelW = nameLabel.getWidth();
        if (labelW <= 0) labelW = nameLabel.prefWidth(-1);
        double fieldWidth = Math.max(contentWidth, labelW);
        double labelH = nameLabel.getHeight();
        if (labelH <= 0) labelH = nameLabel.prefHeight(-1);
        double fieldHeight = labelH + 8;
        iconEditField.setPrefWidth(fieldWidth);
        iconEditField.setPrefHeight(fieldHeight);
        iconEditField.setMinWidth(fieldWidth);
        iconEditField.setMinHeight(fieldHeight);
        iconEditField.setAlignment(Pos.CENTER);

        iconEditField.setOnAction(e -> commitRename(obj, iconEditField.getText(), () -> cancelIconEdit()));
        iconEditField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (!isNowFocused && editingObject == obj) {
                commitRename(obj, iconEditField.getText(), () -> cancelIconEdit());
            }
        });
        iconEditField.setOnKeyReleased(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                cancelIconEdit();
            }
        });

        // 用 Popup 悬浮显示，不受父容器裁剪
        iconEditPopup = new javafx.stage.Popup();
        iconEditPopup.setAutoFix(false);
        iconEditPopup.setAutoHide(true);
        iconEditPopup.setHideOnEscape(false);
        // 居中对齐 Label
        double offsetX = (fieldWidth - labelW) / 2.0;
        iconEditPopup.getContent().add(iconEditField);
        // Popup.show 需要在下一帧定位后才准确，先 show 再 relocate
        iconEditPopup.show(window, screenX - offsetX, screenY - 6);
        iconEditField.selectAll();
        Platform.runLater(() -> iconEditField.requestFocus());
        // 编辑开始后刷新高亮，去除选中背景样式
        updateIconHighlights();
    }

    /** 取消图标视图编辑：隐藏并移除 Popup，并清除选中状态 */
    private void cancelIconEdit() {
        if (editingObject == null) return;
        editingObject = null;

        if (iconEditPopup != null) {
            iconEditPopup.hide();
            iconEditPopup = null;
            iconEditField = null;
        }
        // 编辑结束清除选中，避免表仍显示选中效果
        clearSelection();
    }

    // ==================== 重命名编辑（详细列表视图） ====================

    /** 开始详细列表编辑：用 Popup 悬浮 TextField 定位到名称 Label，不进入 cell 编辑态，避免影响行高 */
    private void startDetailEdit(int row) {
        if (editingObject != null) return;

        ObservableList<String> rowData = detailTableView.getItems().get(row);
        if (rowData == null || rowData.size() <= COL_KEY_TYPE) return;
        String name = rowData.get(COL_KEY_NAME);
        String typeKey = rowData.get(COL_KEY_TYPE);
        ObjectType type = "VIEW".equals(typeKey) ? ObjectType.VIEW : ObjectType.TABLE;
        ObjectInfo obj = findObjectInfo(name, type);
        if (obj == null) return;
        editingObject = obj;

        // 去掉行选中背景，避免编辑态下整行蓝色干扰 TextField
        syncingDetail = true;
        try {
            detailTableView.getSelectionModel().clearSelection();
        } finally {
            syncingDetail = false;
        }

        // 找到该行名称列的 TableCell，从其 graphic(HBox) 取出名称 Label
        Object nameColRef = detailTableView.getColumns().get(0);
        Label nameLabel = null;
        for (var node : detailTableView.lookupAll(".table-cell")) {
            if (node instanceof TableCell<?, ?> tc && tc.getIndex() == row) {
                // 用 Object 引用比较列，规避泛型 capture 不兼容
                if (tc.getTableColumn() != nameColRef) continue;
                if (tc.getGraphic() instanceof HBox hbox) {
                    for (var c : hbox.getChildren()) {
                        if (c instanceof Label l) {
                            nameLabel = l;
                            break;
                        }
                    }
                }
                break;
            }
        }
        if (nameLabel == null) {
            editingObject = null;
            return;
        }

        // 计算 Label 在屏幕上的位置
        Bounds labelSceneBounds = nameLabel.localToScene(nameLabel.getBoundsInLocal());
        Scene scene = nameLabel.getScene();
        if (scene == null || scene.getWindow() == null) {
            editingObject = null;
            return;
        }
        Window window = scene.getWindow();
        double screenX = window.getX() + scene.getX() + labelSceneBounds.getMinX();
        double screenY = window.getY() + scene.getY() + labelSceneBounds.getMinY();

        // 创建 TextField（参考 S3 图标视图重命名样式）
        detailEditField = new TextField(obj.name);
        detailEditField.setStyle("-fx-padding: 0 6; -fx-font-size: 12px; -fx-background-color: white; -fx-border-color: #3592CB; -fx-border-width: 1; -fx-border-radius: 0; -fx-background-radius: 0; -fx-focus-color: transparent; -fx-faint-focus-color: transparent;");
        Text measureText = new Text(obj.name);
        measureText.setFont(Font.font(12));
        double contentWidth = measureText.getLayoutBounds().getWidth() + 20;
        double labelW = nameLabel.getWidth();
        if (labelW <= 0) labelW = nameLabel.prefWidth(-1);
        double fieldWidth = Math.max(contentWidth, labelW);
        double labelH = nameLabel.getHeight();
        if (labelH <= 0) labelH = nameLabel.prefHeight(-1);
        double fieldHeight = labelH + 4;
        detailEditField.setPrefWidth(fieldWidth);
        detailEditField.setPrefHeight(fieldHeight);
        detailEditField.setMinWidth(fieldWidth);
        detailEditField.setMinHeight(fieldHeight);
        detailEditField.setAlignment(Pos.CENTER_LEFT);

        detailEditField.setOnAction(e -> commitRename(obj, detailEditField.getText(), () -> cancelDetailEdit()));
        detailEditField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (!isNowFocused && editingObject == obj) {
                commitRename(obj, detailEditField.getText(), () -> cancelDetailEdit());
            }
        });
        detailEditField.setOnKeyReleased(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                cancelDetailEdit();
            }
        });

        // Popup 悬浮显示，不进入 cell 编辑态，行高不受影响
        detailEditPopup = new javafx.stage.Popup();
        detailEditPopup.setAutoFix(false);
        detailEditPopup.setAutoHide(true);
        detailEditPopup.setHideOnEscape(false);
        // 水平：TextField 左边比 Label 左边再左移 3px，使文本框与左侧图标间距为 3px
        // （Label 与图标间距由 HBox spacing=6 决定，左移 3px 后净距 = 6-3 = 3px）
        double offsetX = -3;
        double offsetY = (labelH - fieldHeight) / 2.0;
        detailEditPopup.getContent().add(detailEditField);
        detailEditPopup.show(window, screenX + offsetX, screenY + offsetY);
        detailEditField.selectAll();
        Platform.runLater(() -> detailEditField.requestFocus());
    }

    /** 取消详细列表编辑：隐藏并移除 Popup */
    private void cancelDetailEdit() {
        editingObject = null;
        if (detailEditPopup != null) {
            detailEditPopup.hide();
            detailEditPopup = null;
            detailEditField = null;
        }
    }

    // ==================== 重命名通用逻辑 ====================

    /** 提交重命名：校验名称后通过回调执行。先恢复 UI 再调用异步重命名。 */
    private void commitRename(ObjectInfo obj, String newName, Runnable onCancel) {
        String name = newName.trim();
        if (editingObject == null) return;
        if (name.isEmpty() || name.equals(obj.name)) {
            onCancel.run();
            return;
        }
        final ObjectInfo editObj = obj;
        // 先恢复 UI（onCancel 即 cancelIconEdit：设置 editingObject=null，恢复 Label）
        onCancel.run();
        // 异步重命名：成功后 loadData 刷新；失败时 handler 弹框，UI 已恢复
        if (operations != null) {
            operations.renameObject(buildNodeData(editObj), name, () -> loadData());
        }
    }

    /** 重命名成功后由 handler 调用，刷新视图 */
    public void notifyObjectRenamed() {
        loadData();
    }

    // ==================== 复制表到剪贴板 ====================

    /** 判断当前焦点是否在本视图内部（用于 Ctrl+C/Ctrl+V 触发判断） */
    private boolean isFocusInside() {
        Node focusOwner = getScene() != null ? getScene().getFocusOwner() : null;
        if (focusOwner == null) return false;
        Node n = focusOwner;
        while (n != null) {
            if (n == this) return true;
            n = n.getParent();
        }
        return false;
    }

    /** 判断系统剪贴板中是否有 TOMATO_COPY_TABLES 内容 */
    private boolean isClipboardHasTables() {
        Clipboard cb = Clipboard.getSystemClipboard();
        if (cb.hasContent(COPY_TABLES_FORMAT)) {
            return true;
        }
        if (cb.hasString()) {
            String s = cb.getString();
            return s != null && s.startsWith(COPY_TABLES_PREFIX + "\n");
        }
        return false;
    }

    /** 注册右键菜单：图标视图 + 详细列表 */
    private void setupContextMenu() {
        contextMenu = new ContextMenu();
        MenuItem openTableItem = new MenuItem("打开表", createImageIcon("/images/connect/table_open.png", 16));
        openTableItem.setOnAction(e -> handleOpenTable());
        MenuItem designItem = new MenuItem("设计表", createImageIcon("/images/connect/table_edit.png", 16));
        designItem.setOnAction(e -> handleDesignTable());
        MenuItem newTableItem = new MenuItem("新建表", createImageIcon("/images/connect/table_add.png", 16));
        newTableItem.setOnAction(e -> { if (operations != null) operations.createTable(); });
        MenuItem copyItem = new MenuItem("复制表", createImageIcon("/images/connect/copy_tables.png", 16));
        copyItem.setOnAction(e -> copySelectedTablesToClipboard());
        MenuItem clearItem = new MenuItem("清空表", createImageIcon("/images/connect/table_drop.png", 16));
        clearItem.setOnAction(e -> handleClearTable());
        MenuItem truncateItem = new MenuItem("截断表", createImageIcon("/images/connect/table_drop.png", 16));
        truncateItem.setOnAction(e -> handleTruncateTable());
        MenuItem deleteItem = new MenuItem("删除表", createImageIcon("/images/connect/table_drop.png", 16));
        deleteItem.setOnAction(e -> handleDeleteTable());
        MenuItem refreshItem = new MenuItem("刷新", createImageIcon("/images/connect/refresh.png", 16));
        refreshItem.setOnAction(e -> loadData());
        contextMenu.getItems().addAll(openTableItem, designItem, newTableItem, new SeparatorMenuItem(),
                copyItem, new SeparatorMenuItem(), clearItem, truncateItem, new SeparatorMenuItem(),
                deleteItem, new SeparatorMenuItem(), refreshItem);

        iconScroll.setOnContextMenuRequested(e -> {
            boolean empty = selectedObjects.isEmpty();
            boolean hasTable = hasSelectedTable();
            openTableItem.setDisable(empty);
            designItem.setDisable(empty);
            copyItem.setDisable(empty);
            clearItem.setDisable(!hasTable);
            truncateItem.setDisable(!hasTable);
            deleteItem.setDisable(empty);
            contextMenu.show(this, e.getScreenX(), e.getScreenY());
            e.consume();
        });
        detailTableView.setOnContextMenuRequested(e -> {
            boolean empty = selectedObjects.isEmpty();
            boolean hasTable = hasSelectedTable();
            openTableItem.setDisable(empty);
            designItem.setDisable(empty);
            copyItem.setDisable(empty);
            clearItem.setDisable(!hasTable);
            truncateItem.setDisable(!hasTable);
            deleteItem.setDisable(empty);
            contextMenu.show(this, e.getScreenX(), e.getScreenY());
            e.consume();
        });

        // 点击其他位置时隐藏右键菜单（捕获阶段，早于节点 handler 的 consume）
        addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (contextMenu.isShowing()) {
                contextMenu.hide();
            }
        });
    }

    /** 当前选中对象中是否至少包含一个表（排除视图） */
    private boolean hasSelectedTable() {
        for (ObjectInfo obj : selectedObjects) {
            if (obj.type == ObjectType.TABLE) return true;
        }
        return false;
    }

    /**
     * 将当前选中的表/视图元信息写入系统剪贴板。
     * 内容格式：TOMATO_COPY_TABLES\n{connId}\n{srcDb}\n{srcSchema}\n{table1}\n{table2}...
     * 同时写入纯文本格式（兼容跨进程粘贴）。
     */
    private void copySelectedTablesToClipboard() {
        if (selectedObjects.isEmpty()) return;

        String connId = config != null ? config.getId() : "";
        String srcDb = databaseName != null ? databaseName : "";
        String srcSchema = schemaName != null ? schemaName : "";

        StringBuilder sb = new StringBuilder();
        sb.append(COPY_TABLES_PREFIX).append('\n');
        sb.append(connId).append('\n');
        sb.append(srcDb).append('\n');
        sb.append(srcSchema);
        for (ObjectInfo obj : selectedObjects) {
            sb.append('\n').append(obj.name);
        }
        String content = sb.toString();

        ClipboardContent cc = new ClipboardContent();
        cc.put(COPY_TABLES_FORMAT, content);
        cc.putString(content);
        Clipboard.getSystemClipboard().setContent(cc);
    }

    // ==================== 多选逻辑 ====================

    /** 清除所有选中 */
    private void clearSelection() {
        selectedObjects.clear();
        selectedObject = null;
        anchorObject = null;
        updateIconHighlights();
        syncDetailSelection();
        updateButtonStates();
    }

    /** 添加到选中集合，设为主选中对象和锚点 */
    private void addToSelection(ObjectInfo obj) {
        if (obj == null) return;
        selectedObjects.add(obj);
        selectedObject = obj;
        anchorObject = obj;
        updateIconHighlights();
        syncDetailSelection();
        updateButtonStates();
    }

    /** Ctrl 切换选中状态 */
    private void toggleSelection(ObjectInfo obj) {
        if (obj == null) return;
        if (selectedObjects.contains(obj)) {
            selectedObjects.remove(obj);
            if (selectedObject == obj) {
                selectedObject = selectedObjects.isEmpty() ? null : selectedObjects.iterator().next();
            }
        } else {
            selectedObjects.add(obj);
            selectedObject = obj;
            anchorObject = obj;
        }
        updateIconHighlights();
        syncDetailSelection();
        updateButtonStates();
    }

    /** Shift 范围选择：从 anchor 到 target（按 objects 列表顺序） */
    private void selectRange(ObjectInfo anchor, ObjectInfo target) {
        if (anchor == null || target == null) return;
        int anchorIdx = -1, targetIdx = -1;
        for (int i = 0; i < objects.size(); i++) {
            if (objects.get(i) == anchor) anchorIdx = i;
            if (objects.get(i) == target) targetIdx = i;
        }
        if (anchorIdx < 0 || targetIdx < 0) return;
        int from = Math.min(anchorIdx, targetIdx);
        int to = Math.max(anchorIdx, targetIdx);
        selectedObjects.clear();
        for (int i = from; i <= to; i++) {
            selectedObjects.add(objects.get(i));
        }
        selectedObject = target;
        updateIconHighlights();
        syncDetailSelection();
        updateButtonStates();
    }

    /** 全选当前列表中的所有对象 */
    private void selectAll() {
        selectedObjects.clear();
        selectedObjects.addAll(objects);
        if (!selectedObjects.isEmpty()) {
            selectedObject = selectedObjects.iterator().next();
        }
        updateIconHighlights();
        syncDetailSelection();
        updateButtonStates();
    }

    /** 更新所有图标项的高亮状态 */
    private void updateIconHighlights() {
        for (Map.Entry<ObjectInfo, VBox> entry : iconBoxMap.entrySet()) {
            VBox box = entry.getValue();
            // 编辑中的对象不显示选中背景样式
            if (editingObject == entry.getKey()) {
                box.setStyle("-fx-background-color: transparent; -fx-background-radius: 4; -fx-border-color: transparent; -fx-border-radius: 4; -fx-border-width: 1px;");
            } else if (selectedObjects.contains(entry.getKey())) {
                box.setStyle("-fx-background-color: #d4edda; -fx-background-radius: 4; -fx-border-color: #07c160; -fx-border-radius: 4; -fx-border-width: 1px;");
            } else {
                box.setStyle("-fx-background-color: transparent; -fx-background-radius: 4; -fx-border-color: transparent; -fx-border-radius: 4; -fx-border-width: 1px;");
            }
        }
    }

    /** 橡皮筋拖动中实时选择命中项 */
    private void updateRubberBandSelection(double x, double y, double w, double h, boolean ctrlDown) {
        if (!ctrlDown) {
            selectedObjects.clear();
        }
        for (Map.Entry<ObjectInfo, VBox> entry : iconBoxMap.entrySet()) {
            VBox box = entry.getValue();
            double bx = box.getLayoutX();
            double by = box.getLayoutY();
            double bw = box.getWidth();
            double bh = box.getHeight();
            // 矩形相交检测
            if (bx < x + w && bx + bw > x && by < y + h && by + bh > y) {
                selectedObjects.add(entry.getKey());
            }
        }
        if (!selectedObjects.isEmpty() && selectedObject == null) {
            selectedObject = selectedObjects.iterator().next();
        }
        updateIconHighlights();
        syncDetailSelection();
        updateButtonStates();
    }

    /** 同步图标视图选中状态到详细列表 */
    private void syncDetailSelection() {
        if (detailTableView == null) return;
        syncingDetail = true;
        try {
            detailTableView.getSelectionModel().clearSelection();
            for (ObjectInfo obj : selectedObjects) {
                String expectedType = obj.type == ObjectType.TABLE ? "TABLE" : "VIEW";
                for (ObservableList<String> row : detailTableView.getItems()) {
                    if (row.size() > COL_KEY_TYPE
                            && obj.name.equals(row.get(COL_KEY_NAME))
                            && expectedType.equals(row.get(COL_KEY_TYPE))) {
                        detailTableView.getSelectionModel().select(row);
                        break;
                    }
                }
            }
        } finally {
            syncingDetail = false;
        }
    }

    // ==================== 详细列表视图 ====================

    /** 详细列表行数据：0=名(显示用), 1=自动递增值, 2=修改日期, 3=数据长度, 4=引擎, 5=行, 6=注释, 7=名(原始key,选中同步用), 8=类型(原始key,选中同步用) */
    private static final int COL_NAME = 0;
    private static final int COL_AUTOINC = 1;
    private static final int COL_UPDATETIME = 2;
    private static final int COL_DATALEN = 3;
    private static final int COL_ENGINE = 4;
    private static final int COL_ROWS = 5;
    private static final int COL_COMMENT = 6;
    private static final int COL_KEY_NAME = 7;
    private static final int COL_KEY_TYPE = 8;

    private Node createDetailView() {
        detailTableView = new TableView<>();
        detailTableView.setEditable(false);
        detailTableView.getStyleClass().add("objects-detail-table");
        detailTableView.setStyle("-fx-padding: 0; -fx-background-color: transparent; -fx-border-color: transparent; -fx-background-insets: 0;");
        detailTableView.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        detailTableView.setPlaceholder(new Label("暂无对象"));

        // 列定义：名、自动递增值、修改日期、数据长度、引擎、行、注释
        // 列宽自适应：初始给默认值避免首帧闪烁，数据加载后由 autoFitColumns 按内容+表头计算
        String[] titles = {"名", "自动递增值", "修改日期", "数据长度", "引擎", "行", "注释"};
        final double colMin = 60;
        final double colMax = 400;

        // 0. 名称列（图标 + 名，自定义单元格；重命名通过 Popup 悬浮编辑，不进入 cell 编辑态）
        {
            TableColumn<ObservableList<String>, String> col = new TableColumn<>(titles[0]);
            col.setMinWidth(colMin);
            col.setMaxWidth(colMax);
            col.setPrefWidth(160); // 初始值，autoFitColumns 会覆盖
            col.setCellValueFactory(param -> new SimpleStringProperty(param.getValue().get(COL_NAME)));
            col.setCellFactory(tc -> new TableCell<ObservableList<String>, String>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setGraphic(null);
                        setText(null);
                        return;
                    }
                    ObservableList<String> rowData = getTableView().getItems().get(getIndex());
                    String typeKey = rowData.size() > COL_KEY_TYPE ? rowData.get(COL_KEY_TYPE) : "TABLE";
                    boolean isView = "VIEW".equals(typeKey);
                    Image img = isView ? viewImg : tableImg;
                    HBox box = new HBox(6);
                    box.setAlignment(Pos.CENTER_LEFT);
                    if (img != null) {
                        ImageView iv = new ImageView(img);
                        iv.setFitWidth(16);
                        iv.setFitHeight(16);
                        iv.setPreserveRatio(true);
                        box.getChildren().add(iv);
                    }
                    Label nameLbl = new Label(item);
                    nameLbl.setStyle("-fx-font-size: 12px;");
                    Tooltip.install(nameLbl, new Tooltip(item));
                    box.getChildren().add(nameLbl);
                    setGraphic(box);
                    setText(null);
                }
            });
            detailTableView.getColumns().add(col);
        }

        // 1-6. 其余列（普通文本）
        for (int i = 1; i <= 6; i++) {
            final int colIndex = i;
            TableColumn<ObservableList<String>, String> col = new TableColumn<>(titles[i]);
            col.setMinWidth(colMin);
            col.setMaxWidth(colMax);
            col.setPrefWidth(100); // 初始值，autoFitColumns 会覆盖
            col.setCellValueFactory(param -> new SimpleStringProperty(param.getValue().get(colIndex)));
            col.setCellFactory(tc -> new TableCell<ObservableList<String>, String>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setGraphic(null);
                    } else {
                        setText(item);
                        setGraphic(null);
                    }
                }
            });
            detailTableView.getColumns().add(col);
        }

        // 启用多选模式（支持 Ctrl+点击、Shift+点击、Ctrl+A、拖拽选择）
        detailTableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        // 选中行变化时同步到图标视图（仅在用户操作详细列表时触发，避免循环同步）
        detailTableView.getSelectionModel().getSelectedItems().addListener((javafx.collections.ListChangeListener<ObservableList<String>>) change -> {
            if (syncingDetail) return; // 程序化同步中，跳过
            syncFromDetail();
        });

        detailTableView.setRowFactory(tv -> {
            TableRow<ObservableList<String>> row = new TableRow<>();
            // 行划过效果：鼠标在 cell 上时整行 cell 变淡蓝，鼠标在行内右侧空白区域时移除。
            // 用 MOUSE_MOVED 事件过滤器（capturing 阶段，子节点无法阻止）+ PickResult 判断 target 是否落在 TableCell 上。
            // 单事件源，无 cell 间 mouseEntered/mouseExited 时序竞态。
            // 选中行不显示划过效果（保持选中蓝色），故选中时跳过添加并立即移除已有 custom-hover。
            row.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED, e -> {
                if (row.isEmpty() || row.isSelected()) {
                    if (row.getStyleClass().contains("custom-hover")) {
                        row.getStyleClass().remove("custom-hover");
                    }
                    return;
                }
                javafx.scene.Node target = e.getPickResult().getIntersectedNode();
                boolean onCell = false;
                while (target != null && target != row) {
                    if (target instanceof TableCell<?, ?>) {
                        onCell = true;
                        break;
                    }
                    target = target.getParent();
                }
                if (onCell) {
                    if (!row.getStyleClass().contains("custom-hover")) {
                        row.getStyleClass().add("custom-hover");
                    }
                } else {
                    row.getStyleClass().remove("custom-hover");
                }
            });
            // 选中状态变化时立即移除 custom-hover，避免选中瞬间残留淡蓝
            row.selectedProperty().addListener((obs, wasSel, isSel) -> {
                if (isSel) {
                    row.getStyleClass().remove("custom-hover");
                }
            });
            row.setOnMouseExited(e -> row.getStyleClass().remove("custom-hover"));
            row.setOnMouseClicked(e -> {
                if (row.isEmpty()) return;
                if (e.getButton() == javafx.scene.input.MouseButton.SECONDARY) return;

                // 双击：取消编辑定时器，打开表
                if (e.getClickCount() == 2) {
                    // 空白区域（行内右侧无 cell 部分）双击不响应
                    if (getRowIndexOf(e) < 0) return;
                    if (singleClickTimer != null) {
                        singleClickTimer.stop();
                        singleClickTimer = null;
                    }
                    if (editingObject != null) {
                        cancelDetailEdit();
                    }
                    ObservableList<String> rowData = row.getItem();
                    if (rowData.size() > COL_KEY_TYPE) {
                        String name = rowData.get(COL_KEY_NAME);
                        String typeKey = rowData.get(COL_KEY_TYPE);
                        ObjectType type = "VIEW".equals(typeKey) ? ObjectType.VIEW : ObjectType.TABLE;
                        ObjectInfo obj = findObjectInfo(name, type);
                        if (obj != null) {
                            selectedObject = obj;
                            if (operations != null) operations.openObject(buildNodeData(obj));
                        }
                    }
                    e.consume();
                    return;
                }

                // 单击：判断是否"已选中再点击"以进入重命名编辑
                if (e.getClickCount() == 1 && !e.isControlDown() && !e.isShiftDown()) {
                    int rowIdx = row.getIndex();
                    boolean wasAlreadySelected = clickedBeforeObject != null
                            && clickedBeforeIndex == rowIdx
                            && editingObject == null
                            && detailTableView.getSelectionModel().getSelectedIndices().size() == 1
                            && detailTableView.getSelectionModel().isSelected(rowIdx);
                    if (wasAlreadySelected) {
                        final int editRow = rowIdx;
                        if (singleClickTimer != null) {
                            singleClickTimer.stop();
                        }
                        singleClickTimer = new javafx.animation.Timeline(
                                new javafx.animation.KeyFrame(javafx.util.Duration.millis(300), ae -> {
                                    if (editingObject == null
                                            && detailTableView.getSelectionModel().getSelectedIndices().size() == 1
                                            && detailTableView.getSelectionModel().isSelected(editRow)) {
                                        startDetailEdit(editRow);
                                    }
                                    singleClickTimer = null;
                                }));
                        singleClickTimer.play();
                        e.consume();
                        return;
                    }
                }
            });
            return row;
        });

        // 记录点击前已选中的对象和索引（用于判断"已选中再点击"进入编辑）
        detailTableView.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY && event.getClickCount() == 1) {
                var selItems = detailTableView.getSelectionModel().getSelectedItems();
                if (selItems.size() == 1 && selItems.get(0) != null && selItems.get(0).size() > COL_KEY_TYPE) {
                    String name = selItems.get(0).get(COL_KEY_NAME);
                    String typeKey = selItems.get(0).get(COL_KEY_TYPE);
                    ObjectType type = "VIEW".equals(typeKey) ? ObjectType.VIEW : ObjectType.TABLE;
                    clickedBeforeObject = findObjectInfo(name, type);
                    clickedBeforeIndex = detailTableView.getSelectionModel().getSelectedIndex();
                } else {
                    clickedBeforeObject = null;
                    clickedBeforeIndex = -1;
                }
            }
        });

        // 鼠标拖拽范围选择（参考 TableDataView.setupDragSelection）
        setupDetailDragSelection();

        return detailTableView;
    }

    /**
     * 详细列表的鼠标拖拽范围选择。
     * 参考 TableDataView 的实现：用 setOnMousePressed + setOnMouseDragged，
     * 通过 PickResult 节点链遍历获取行索引，用 clearSelection + selectRange 更新选中。
     * 从数据行下方空白区域按下拖拽时，额外显示橡皮筋矩形浮层（Windows资源管理器风格）。
     */
    private void setupDetailDragSelection() {
        final int[] dragStart = {-1};

        detailTableView.setOnMousePressed(e -> {
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            if (e.isControlDown() || e.isShiftDown()) {
                detailMarqueeActive = false;
                detailRubberRect.setVisible(false);
                return;
            }
            int rowIdx = getRowIndexOf(e);
            // 数据行下方空白区域按下（非表头）时启用橡皮筋矩形
            detailMarqueeActive = rowIdx < 0 && !isPressOnHeader(e);
            if (rowIdx < 0) {
                // 点击空白区域（数据行之外）：清除选中，不选中任何行；
                // 橡皮筋拖拽起点视为最后一行（拖入数据行时从该行选到最后一行）
                dragStart[0] = detailTableView.getItems().size() - 1;
                syncingDetail = true;
                try {
                    detailTableView.getSelectionModel().clearSelection();
                } finally {
                    syncingDetail = false;
                }
                syncFromDetail();
                if (detailMarqueeActive) {
                    Point2D p = centerStack.sceneToLocal(e.getSceneX(), e.getSceneY());
                    detailMarqueeStartX = p.getX();
                    detailMarqueeStartY = p.getY();
                    updateDetailRubberRect(p.getX(), p.getY());
                }
                return;
            }
            dragStart[0] = rowIdx;
            // 选中起点行
            syncingDetail = true;
            try {
                detailTableView.getSelectionModel().clearSelection();
                detailTableView.getSelectionModel().select(rowIdx);
            } finally {
                syncingDetail = false;
            }
            syncFromDetail();
            if (detailMarqueeActive) {
                Point2D p = centerStack.sceneToLocal(e.getSceneX(), e.getSceneY());
                detailMarqueeStartX = p.getX();
                detailMarqueeStartY = p.getY();
                updateDetailRubberRect(p.getX(), p.getY());
            }
        });

        detailTableView.setOnMouseDragged(e -> {
            if (e.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            if (dragStart[0] < 0) return;
            // 矩形框跟随鼠标（即使经过表头等无行区域也持续更新）
            if (detailMarqueeActive) {
                Point2D p = centerStack.sceneToLocal(e.getSceneX(), e.getSceneY());
                updateDetailRubberRect(p.getX(), p.getY());
            }
            int rowIdx = getRowIndexOf(e);
            if (rowIdx < 0 && detailMarqueeActive) {
                // 橡皮筋仍在空白区域：矩形未覆盖任何行，保持空选中
                syncingDetail = true;
                try {
                    detailTableView.getSelectionModel().clearSelection();
                } finally {
                    syncingDetail = false;
                }
                syncFromDetail();
                return;
            }
            if (rowIdx < 0) {
                // 拖到空白区域：用最后一行
                int lastRowIdx = detailTableView.getItems().size() - 1;
                if (lastRowIdx < 0) return;
                rowIdx = lastRowIdx;
            }
            int min = Math.min(dragStart[0], rowIdx);
            int max = Math.max(dragStart[0], rowIdx);
            syncingDetail = true;
            try {
                detailTableView.getSelectionModel().clearSelection();
                detailTableView.getSelectionModel().selectRange(min, max + 1);
            } finally {
                syncingDetail = false;
            }
            syncFromDetail();
        });

        detailTableView.setOnMouseReleased(e -> {
            dragStart[0] = -1;
            detailMarqueeActive = false;
            detailRubberRect.setVisible(false);
        });
    }

    /**
     * 更新详细列表橡皮筋矩形：从按下起点到当前鼠标位置（clamp在centerStack范围内）
     */
    private void updateDetailRubberRect(double x, double y) {
        double viewW = centerStack.getWidth();
        double viewH = centerStack.getHeight();
        x = Math.max(0, Math.min(x, viewW));
        y = Math.max(0, Math.min(y, viewH));
        detailRubberRect.setX(Math.min(x, detailMarqueeStartX));
        detailRubberRect.setY(Math.min(y, detailMarqueeStartY));
        detailRubberRect.setWidth(Math.abs(x - detailMarqueeStartX));
        detailRubberRect.setHeight(Math.abs(y - detailMarqueeStartY));
        detailRubberRect.setVisible(true);
    }

    /**
     * 判断鼠标按下位置是否在详细列表表头区域（列头/表头背景/右侧filler）
     */
    private boolean isPressOnHeader(javafx.scene.input.MouseEvent e) {
        Node target = e.getPickResult().getIntersectedNode();
        while (target != null && target != detailTableView) {
            if (target.getStyleClass().contains("column-header")
                    || target.getStyleClass().contains("column-header-background")
                    || target.getStyleClass().contains("filler")
                    || target.getStyleClass().contains("nested-column-header")) {
                return true;
            }
            target = target.getParent();
        }
        return false;
    }

    /**
     * 根据鼠标事件位置获取对应的行索引。
     * 通过遍历 PickResult 节点链找到 TableCell，直接获取行索引。
     * 仅命中 TableCell 时才视为点击数据行；命中 TableRow 但无 TableCell
     * （如数据行右侧空白区域）视为空白区域，不选中任何行/单元格。
     * 参考 TableDataView.getCellPositionAt 的实现方式。
     */
    private int getRowIndexOf(javafx.scene.input.MouseEvent event) {
        javafx.scene.Node target = event.getPickResult().getIntersectedNode();
        while (target != null && target != detailTableView) {
            if (target instanceof TableCell<?, ?> cell
                    && cell.getTableRow() != null && !cell.getTableRow().isEmpty()) {
                return cell.getTableRow().getIndex();
            }
            target = target.getParent();
        }
        return -1;
    }

    /** 从详细列表选中行反向同步到图标视图 */
    private void syncFromDetail() {
        Set<ObjectInfo> newSelection = new LinkedHashSet<>();
        for (ObservableList<String> row : detailTableView.getSelectionModel().getSelectedItems()) {
            if (row != null && row.size() > COL_KEY_TYPE) {
                String name = row.get(COL_KEY_NAME);
                String typeKey = row.get(COL_KEY_TYPE);
                ObjectType type = "VIEW".equals(typeKey) ? ObjectType.VIEW : ObjectType.TABLE;
                ObjectInfo obj = findObjectInfo(name, type);
                if (obj != null) newSelection.add(obj);
            }
        }
        selectedObjects.clear();
        selectedObjects.addAll(newSelection);
        selectedObject = newSelection.isEmpty() ? null : newSelection.iterator().next();
        updateIconHighlights();
        updateButtonStates();
    }

    private ObjectInfo findObjectInfo(String name, ObjectType type) {
        for (ObjectInfo obj : objects) {
            if (obj.name.equals(name) && obj.type == type) {
                return obj;
            }
        }
        return null;
    }

    private void populateDetailView() {
        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
        for (ObjectInfo obj : objects) {
            rows.add(FXCollections.observableArrayList(
                    obj.name,                                                // 0 名(显示)
                    obj.autoIncrement != null ? obj.autoIncrement : "",      // 1 自动递增值
                    obj.updateTime != null ? obj.updateTime : "",            // 2 修改日期
                    obj.dataLength != null ? obj.dataLength : "",            // 3 数据长度
                    obj.engine != null ? obj.engine : "",                    // 4 引擎
                    obj.rows != null ? obj.rows : "",                        // 5 行
                    obj.comment != null ? obj.comment : "",                  // 6 注释
                    obj.name,                                                // 7 名(key,选中同步用)
                    obj.type == ObjectType.TABLE ? "TABLE" : "VIEW"          // 8 类型(key,选中同步用)
            ));
        }
        detailTableView.setItems(rows);
        autoFitColumns();
    }

    /**
     * 根据表头文字和实际数据内容自适应每列宽度。
     * 取 max(表头宽度, 数据最大宽度)，并限制在 [colMin, colMax] 范围内，
     * 避免列太宽或太窄。
     */
    private void autoFitColumns() {
        if (detailTableView.getColumns().isEmpty()) return;

        // CSS: .table-cell padding = 4 8 4 8 -> 左右各 8px = 16
        // .column-header .label padding = 4 8 4 8 -> 左右各 8px = 16
        // 表头额外空间：排序图标/箭头约 20px
        final double cellHPad = 16;
        final double headerExtra = 20;
        final double minColWidth = 60;
        final double maxColWidth = 400;
        final double nameIconExtra = 22; // 第一列图标(16) + HBox gap(6)

        Font dataFont = Font.font("System", 12);

        for (int colIdx = 0; colIdx < detailTableView.getColumns().size(); colIdx++) {
            TableColumn<ObservableList<String>, ?> column = detailTableView.getColumns().get(colIdx);

            // 表头宽度
            double headerWidth = measureTextWidth(column.getText(), dataFont) + cellHPad + headerExtra;

            // 数据最大宽度
            double maxDataWidth = 0;
            for (ObservableList<String> row : detailTableView.getItems()) {
                if (colIdx < row.size() && row.get(colIdx) != null) {
                    double w = measureTextWidth(row.get(colIdx), dataFont) + cellHPad;
                    if (colIdx == COL_NAME) w += nameIconExtra;
                    if (w > maxDataWidth) maxDataWidth = w;
                }
            }

            double prefWidth = Math.max(headerWidth, maxDataWidth);
            prefWidth = Math.max(minColWidth, Math.min(maxColWidth, prefWidth));

            column.setPrefWidth(prefWidth);
        }
    }

    /** 测量文本在指定字体下的渲染宽度（基于字体 metrics，无需加入 scene graph） */
    private double measureTextWidth(String text, Font font) {
        if (text == null || text.isEmpty()) return 0;
        Text textNode = new Text(text);
        textNode.setFont(font);
        return textNode.getLayoutBounds().getWidth();
    }

    // ==================== ER 视图 ====================

    private Node createErView() {
        erCanvas = new Pane();
        erCanvas.setStyle("-fx-background-color: #fafafa;");
        erCanvas.setPrefSize(2000, 1500);

        erScroll = new ScrollPane(erCanvas);
        erScroll.setFitToWidth(false);
        erScroll.setFitToHeight(false);
        erScroll.setPannable(true);
        erScroll.setStyle("-fx-background-color: #fafafa; -fx-border-color: transparent; -fx-background-insets: 0; -fx-padding: 0; -fx-border-insets: 0;");
        erScroll.getStyleClass().add("session-scroll-pane");
        return erScroll;
    }

    private void populateErView() {
        erCanvas.getChildren().clear();
        if (objects.isEmpty()) {
            return;
        }

        // 仅表参与 ER（视图不参与）
        List<ObjectInfo> tables = new ArrayList<>();
        for (ObjectInfo obj : objects) {
            if (obj.type == ObjectType.TABLE) {
                tables.add(obj);
            }
        }
        if (tables.isEmpty()) {
            return;
        }

        // 网格布局
        Map<String, VBox> tableBoxes = new HashMap<>();
        int colSize = (int) Math.ceil(Math.sqrt(tables.size()));
        int colSpacing = 280;
        int rowSpacing = 180;
        int col = 0, row = 0;

        for (ObjectInfo obj : tables) {
            VBox tableBox = createErTableBox(obj);
            double x = col * colSpacing + 20;
            double y = row * rowSpacing + 20;
            tableBox.setLayoutX(x);
            tableBox.setLayoutY(y);
            tableBoxes.put(obj.name, tableBox);
            erCanvas.getChildren().add(tableBox);

            col++;
            if (col >= colSize) {
                col = 0;
                row++;
            }
        }

        // 绘制外键连线（布局生效后读取坐标）
        Platform.runLater(() -> {
            for (FkRelation fk : foreignKeys) {
                VBox srcBox = tableBoxes.get(fk.sourceTable);
                VBox refBox = tableBoxes.get(fk.refTable);
                if (srcBox == null || refBox == null) {
                    continue;
                }
                double sx = srcBox.getLayoutX() + srcBox.getWidth() / 2;
                double sy = srcBox.getLayoutY() + srcBox.getHeight() / 2;
                double rx = refBox.getLayoutX() + refBox.getWidth() / 2;
                double ry = refBox.getLayoutY() + refBox.getHeight() / 2;

                CubicCurve curve = new CubicCurve(sx, sy, sx, (sy + ry) / 2, rx, (sy + ry) / 2, rx, ry);
                curve.setStroke(Color.valueOf("#07c160"));
                curve.setStrokeWidth(1.5);
                curve.setFill(null);
                curve.setCursor(Cursor.HAND);
                Tooltip tooltip = new Tooltip(fk.sourceTable + "." + fk.sourceColumns
                        + "  →  " + fk.refTable + "." + fk.refColumns);
                Tooltip.install(curve, tooltip);
                curve.setOnMouseEntered(e -> curve.setStrokeWidth(2.5));
                curve.setOnMouseExited(e -> curve.setStrokeWidth(1.5));

                erCanvas.getChildren().add(curve);
                curve.toBack();
            }
        });
    }

    private VBox createErTableBox(ObjectInfo obj) {
        VBox box = new VBox();
        box.setStyle("-fx-border-color: #888; -fx-border-width: 1; -fx-background-color: white; -fx-border-radius: 4; -fx-background-radius: 4; -fx-effect: dropshadow(gaussian, #ccc, 3, 0, 1, 1);");

        Label title = new Label(obj.name);
        title.setStyle("-fx-font-weight: bold; -fx-background-color: #07c160; -fx-text-fill: white; -fx-padding: 4 8; -fx-background-radius: 4 4 0 0;");
        title.setPrefWidth(180);
        title.setMaxWidth(180);
        title.setCursor(Cursor.HAND);
        title.setOnMouseClicked(e -> {
            clearSelection();
            addToSelection(obj);
            if (e.getClickCount() == 2) {
                handleOpenTable();
            }
        });
        box.getChildren().add(title);
        return box;
    }

    // ==================== 底部状态栏 ====================

    private HBox createStatusBar() {
        HBox statusBar = new HBox(10);
        statusBar.setPadding(new Insets(3, 12, 3, 12));
        statusBar.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #ddd; -fx-border-width: 1 0 0 0;");
        statusBar.setAlignment(Pos.CENTER_LEFT);

        countLabel = new Label("加载中...");
        countLabel.setStyle("-fx-font-size: 12px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toggleBox = new HBox(2);
        toggleBox.setStyle("-fx-background-color: transparent;");

        viewToggleGroup = new ToggleGroup();
        iconBtn = createViewToggleButton(createListIconSvg(), "图标视图");
        detailBtn = createViewToggleButton(createDetailIconSvg(), "详细列表");
        erBtn = createViewToggleButton(createErIconSvg(), "ER视图");

        viewToggleGroup.getToggles().addAll(iconBtn, detailBtn, erBtn);
        iconBtn.setSelected(true);

        // 强制保持选中（避免全部取消选中）
        viewToggleGroup.selectedToggleProperty().addListener((obs, oldToggle, newToggle) -> {
            if (newToggle == null && oldToggle != null) {
                oldToggle.setSelected(true);
            }
        });

        iconBtn.setOnAction(e -> { if (iconBtn.isSelected()) switchView(ViewType.ICON); });
        detailBtn.setOnAction(e -> { if (detailBtn.isSelected()) switchView(ViewType.DETAIL); });
        erBtn.setOnAction(e -> { if (erBtn.isSelected()) switchView(ViewType.ER); });

        toggleBox.getChildren().addAll(iconBtn, detailBtn, erBtn);
        statusBar.getChildren().addAll(countLabel, spacer, toggleBox);
        return statusBar;
    }

    private ToggleButton createViewToggleButton(Node icon, String tooltip) {
        ToggleButton btn = new ToggleButton();
        btn.setGraphic(icon);
        btn.setTooltip(new Tooltip(tooltip));
        btn.getStyleClass().add("view-toggle-btn");
        btn.setCursor(Cursor.HAND);
        // 背景始终透明，仅通过图标颜色区分选中状态
        btn.selectedProperty().addListener((obs, wasSel, isSel) ->
                applyIconColor(icon, isSel ? Color.valueOf("#07c160") : Color.valueOf("#555")));
        return btn;
    }

    /** 递归设置 SVGPath 的 stroke 颜色 */
    private void applyIconColor(Node icon, Color color) {
        if (icon instanceof SVGPath svg) {
            svg.setStroke(color);
        }
    }

    // ==================== SVGPath 图标 ====================

    private SVGPath createListIconSvg() {
        SVGPath p = new SVGPath();
        // 11x11 框，三条线垂直居中
        p.setContent("M0 1.2h11M0 5.5h11M0 9.8h11");
        p.setStroke(Color.valueOf("#555"));
        p.setStrokeWidth(2);
        p.setFill(null);
        return p;
    }

    private SVGPath createDetailIconSvg() {
        SVGPath p = new SVGPath();
        // 11x11 网格
        p.setContent("M0 0h11v11H0z M0 3.7h11 M0 7.3h11 M3.7 0v11 M7.3 0v11");
        p.setStroke(Color.valueOf("#555"));
        p.setStrokeWidth(1.5);
        p.setFill(null);
        return p;
    }

    private SVGPath createErIconSvg() {
        SVGPath p = new SVGPath();
        // 11x11 框，两框+连线居中
        p.setContent("M0.6 0.9h3.1v3.1H0.6z M7.3 7h3.1v3.1h-3.1z M3.7 2.4h3.7 M2.1 3.7v3.1");
        p.setStroke(Color.valueOf("#555"));
        p.setStrokeWidth(1.5);
        p.setFill(null);
        return p;
    }

    // ==================== 视图切换 ====================

    private void switchView(ViewType type) {
        iconScroll.setVisible(type == ViewType.ICON);
        detailTableView.setVisible(type == ViewType.DETAIL);
        erScroll.setVisible(type == ViewType.ER);
    }

    // ==================== 数据加载 ====================

    public void refreshData() {
        loadData();
    }

    /** 删除对象后由 handler 调用，刷新列表 */
    public void notifyObjectDeleted() {
        loadData();
    }

    private void loadData() {
        loadingIndicator.setVisible(true);
        countLabel.setText("加载中...");

        new Thread(() -> {
            ReentrantLock connLock = DatabaseService.acquireUsageLock(config, databaseName);
            connLock.lock();
            try {
                // 批量获取所有表/视图元数据（代替逐表 getTableComment）
                List<Map<String, String>> info = DatabaseService.getTablesInfo(config, databaseName, schemaName);
                List<ObjectInfo> objs = new ArrayList<>();
                for (Map<String, String> m : info) {
                    String name = m.get("name");
                    String typeKey = m.get("type");
                    ObjectType type = "VIEW".equals(typeKey) ? ObjectType.VIEW : ObjectType.TABLE;
                    objs.add(new ObjectInfo(
                            name,
                            type,
                            m.get("comment") != null ? m.get("comment") : "",
                            m.get("engine") != null ? m.get("engine") : "",
                            m.get("autoIncrement") != null ? m.get("autoIncrement") : "",
                            m.get("updateTime") != null ? m.get("updateTime") : "",
                            m.get("dataLength") != null ? m.get("dataLength") : "",
                            m.get("rows") != null ? m.get("rows") : ""
                    ));
                }

                List<FkRelation> fks = new ArrayList<>();
                try {
                    List<Map<String, String>> fkMaps = DatabaseService.getDatabaseForeignKeys(config, databaseName, schemaName);
                    for (Map<String, String> fk : fkMaps) {
                        fks.add(new FkRelation(
                                fk.get("表名"),
                                fk.get("字段"),
                                fk.get("参考表"),
                                fk.get("参考字段")
                        ));
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }

                final List<ObjectInfo> finalObjs = objs;
                final List<FkRelation> finalFks = fks;
                Platform.runLater(() -> {
                    this.allObjects = finalObjs;
                    this.objects = new ArrayList<>(finalObjs);
                    this.foreignKeys = finalFks;
                    // 若已删除选中对象，清除选中状态；否则更新为新引用（保持 contains 有效）
                    if (selectedObject != null) {
                        ObjectInfo found = findObjectInfo(selectedObject.name, selectedObject.type);
                        if (found == null) {
                            clearSelection();
                        } else {
                            // 更新选中集合中的引用
                            Set<ObjectInfo> updated = new LinkedHashSet<>();
                            for (ObjectInfo sel : selectedObjects) {
                                ObjectInfo f = findObjectInfo(sel.name, sel.type);
                                if (f != null) updated.add(f);
                            }
                            selectedObjects.clear();
                            selectedObjects.addAll(updated);
                            selectedObject = found;
                        }
                    }
                    // 若搜索框有内容则按关键字过滤，否则直接填充
                    if (searchField != null && !searchField.getText().trim().isEmpty()) {
                        applySearch(searchField.getText());
                    } else {
                        populateIconView();
                        populateDetailView();
                        populateErView();
                        updateCountLabel();
                    }
                    loadingIndicator.setVisible(false);
                    updateButtonStates();
                    // 后台预加载字段信息（用于搜索字段名/字段注释）
                    preloadColumns();
                });
            } catch (Exception e) {
                e.printStackTrace();
                Platform.runLater(() -> {
                    loadingIndicator.setVisible(false);
                    countLabel.setText("加载失败: " + e.getMessage());
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("加载对象列表失败: " + e.getMessage());
                    DialogPositionUtil.centerOnOwner(alert, this);
                    alert.showAndWait();
                });
            } finally {
                connLock.unlock();
            }
        }, "DB-LoadObjects").start();
    }
}
