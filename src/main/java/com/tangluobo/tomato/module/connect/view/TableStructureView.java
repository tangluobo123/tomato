package com.tangluobo.tomato.module.connect.view;

import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.DataTypeProvider;
import com.tangluobo.tomato.module.connect.GlobalConfig;
import com.tangluobo.tomato.module.connect.service.DatabaseService;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import com.tangluobo.tomato.utils.RowSelectorDragSelection;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Polygon;

import java.util.*;

/**
 * 表结构展示视图：以表格形式显示表的列信息（字段名、类型、长度、是否可空、是否主键、自增、默认值、注释）
 * "类型"列支持可编辑ComboBox，下拉项根据数据库类型和版本动态加载
 */
public class TableStructureView extends BorderPane {

    private static final String ROW_SELECTOR_COL = "__ROW_SELECTOR__";

    private final ConnectionConfig config;
    private final String databaseName;
    private final String schemaName;
    private String tableName;
    /** 新建表模式：tableName 为空时为 true，跳过数据库加载，初始化空字段表格 */
    private boolean isNewTable;
    /** 新建表保存成功回调，参数为新建的表名 */
    private java.util.function.Consumer<String> onTableCreated;
    /** 字段脏状态回调，参数为 dirty 状态（true=有未保存变更，false=已保存） */
    private java.util.function.Consumer<Boolean> onDirtyChange;

    private TableView<ObservableList<String>> tableView;
    private ProgressIndicator loadingIndicator;
    private TextField statusLabel;

    /** 索引/外键/触发器/SQL预览 各标签页的组件 */
    private TableView<ObservableList<String>> indexesTableView;
    private ProgressIndicator indexesLoadingIndicator;
    private TableView<ObservableList<String>> foreignKeysTableView;
    private ProgressIndicator foreignKeysLoadingIndicator;
    private TableView<ObservableList<String>> triggersTableView;
    private ProgressIndicator triggersLoadingIndicator;

    /** 选项标签页组件 */
    private ComboBox<String> engineComboBox;
    private ComboBox<String> charsetComboBox;
    private ComboBox<String> collationComboBox;
    private TextField autoIncrementField;
    private Label autoIncrementLabel;
    private ComboBox<String> rowFormatComboBox;
    private TextField avgRowLengthField;
    private Label rowFormatLabel;
    private Label avgRowLengthLabel;
    private ProgressIndicator optionsLoadingIndicator;

    /** 字段属性面板（字段标签页下方） */
    private VBox fieldPropsBox;
    private Label fieldPropsPlaceholder;
    private CheckBox autoIncrementCheckBox;
    private ComboBox<String> defaultValueComboBox;
    private CheckBox unsignedCheckBox;
    private CheckBox zeroFillCheckBox;
    private ComboBox<String> fieldCharsetComboBox;
    private ComboBox<String> fieldCollationComboBox;
    private TextField keyLengthField;
    private CheckBox binaryCheckBox;
    /** 字符集/排序规则/键长度 行容器：用于按类型整体隐藏（含Label），隐藏时不占位 */
    private HBox charsetRow;
    private HBox collationRow;
    private HBox keyLengthRow;

    /** 注释标签页 */
    private TextArea commentTextArea;

    /** SQL预览标签页 */
    private SqlPreviewViewer sqlPreviewViewer;
    /** SQL预览模式下拉框：保存（ALTER）/ 另存为（CREATE TABLE） */
    private ComboBox<String> sqlPreviewModeBox;
    /** 下拉框弹出位置锁定（防止autoFix跳位） */
    private double popupTargetY = -1;
    private boolean popupListenerAdded = false;

    /** 缓存的字符集->排序规则映射（用于选项标签页字符集联动，避免在FX线程查询数据库） */
    private Map<String, List<String>> cachedCharsets;

    /** 已加载标签页状态标记，避免重复加载 */
    private boolean indexesLoaded = false;
    private boolean foreignKeysLoaded = false;
    private boolean triggersLoaded = false;
    private boolean optionsLoaded = false;
    private boolean commentLoaded = false;
    private boolean sqlPreviewLoaded = false;

    /** 数据列数量（不含行选择器列） */
    private int dataColumnCount;

    /** 字段表列标题（字段名、类型、长度、非空、主键、自增、默认值、注释） */
    private List<String> columnTitles;

    /** 列注释原始值缓存（字段名 → 原始注释），用于检测变更 */
    private Map<String, String> originalColumnComments = new HashMap<>();

    /** 复制字段的JSON缓存（避免被JavaFX默认复制行为覆盖剪贴板导致粘贴失败） */
    private String copiedFieldsJson = null;

    /** 当前编辑单元格的提交回调（由可编辑单元格在 startEdit 时注入，供方向键导航时提交当前编辑值） */
    private Runnable currentEditCommit;
    /** 当前编辑单元格是否应跳过方向键导航（例如 ComboBox 下拉打开时，方向键用于选择下拉项） */
    private java.util.function.Supplier<Boolean> skipArrowNavigation;

    /** 表注释原始值，用于检测变更 */
    private String originalTableComment = null;

    /** 字段表原始数据快照（深拷贝），用于检测字段增删改并生成ALTER SQL */
    private List<ObservableList<String>> originalColumnsSnapshot = new ArrayList<>();
    /** 是否有未保存的字段变更（增/删/改/移动），控制Tab标题星号 */
    private boolean dirty = false;
    /** 检测到字段顺序变更但当前数据库类型不支持直接重排序（PG/Oracle） */
    private volatile boolean reorderUnsupported = false;
    /** Shift+点击范围选择的锚点单元格 {row, col} */
    private final int[] anchorCell = {-1, -1};

    /** 缓存的数据类型列表（基于当前连接的数据库类型和版本） */
    private List<String> cachedDataTypes;
    /** 缓存的数据库版本字符串 */
    private String cachedDbVersion;

    public TableStructureView(ConnectionConfig config, String databaseName, String tableName) {
        this(config, databaseName, null, tableName);
    }

    public TableStructureView(ConnectionConfig config, String databaseName, String schemaName, String tableName) {
        this.config = config;
        this.databaseName = databaseName;
        this.schemaName = schemaName;
        this.tableName = tableName;
        this.isNewTable = tableName == null || tableName.trim().isEmpty();

        initializeUI();
        if (isNewTable) {
            initNewTableStructure();
        } else {
            loadStructure();
        }
    }

    /** 设置新建表保存成功回调（由 ConnectModule 设置，用于更新 tab 标题/userData 并刷新表树） */
    public void setOnTableCreated(java.util.function.Consumer<String> callback) {
        this.onTableCreated = callback;
    }

    /** 设置字段脏状态回调（由 AbstractDbHandler 设置，用于在Tab标题前加/去 *） */
    public void setOnDirtyChange(java.util.function.Consumer<Boolean> callback) {
        this.onDirtyChange = callback;
    }

    /** 标记字段已修改：设置dirty=true并通知回调，若SQL预览已加载则刷新预览 */
    private void markDirty() {
        if (!dirty) {
            dirty = true;
            notifyDirtyChange();
        }
        // SQL预览面板已加载时实时刷新ALTER预览
        if (sqlPreviewLoaded && !isNewTable) {
            loadSqlPreview();
        }
    }

    /** 清除脏状态：重建原始快照并通知回调 */
    private void clearDirty() {
        if (dirty) {
            dirty = false;
            notifyDirtyChange();
        }
        snapshotColumns();
    }

    private void notifyDirtyChange() {
        if (onDirtyChange != null) onDirtyChange.accept(dirty);
    }

    /** 对当前字段表数据做深拷贝快照（作为"已保存"基准） */
    private void snapshotColumns() {
        originalColumnsSnapshot = new ArrayList<>();
        if (tableView.getItems() == null) return;
        for (ObservableList<String> row : tableView.getItems()) {
            originalColumnsSnapshot.add(FXCollections.observableArrayList(row));
        }
    }

    private void initializeUI() {
        // 工具栏：保存、添加字段、插入字段、主键、上移、下移、刷新（图标+名称）
        HBox toolBar = new HBox(2);
        toolBar.setPadding(new Insets(4, 8, 4, 8));
        toolBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        toolBar.setAlignment(Pos.CENTER_LEFT);

        Button saveBtn = createToolBarButton("保存", createSaveIcon());
        saveBtn.setOnAction(e -> handleSave());

        Button addFieldBtn = createToolBarButton("添加字段", createAddIcon());
        addFieldBtn.setOnAction(e -> handleAddField());

        Button insertFieldBtn = createToolBarButton("插入字段", createInsertIcon());
        insertFieldBtn.setOnAction(e -> handleInsertField());

        Button deleteFieldBtn = createToolBarButton("删除", createDeleteIcon());
        deleteFieldBtn.setOnAction(e -> handleDeleteField());

        Button primaryKeyBtn = createToolBarButton("主键", createPrimaryKeyIcon());
        primaryKeyBtn.setOnAction(e -> handleTogglePrimaryKey());

        Button moveUpBtn = createToolBarButton("上移", createMoveUpIcon());
        moveUpBtn.setOnAction(e -> handleMoveUp());

        Button moveDownBtn = createToolBarButton("下移", createMoveDownIcon());
        moveDownBtn.setOnAction(e -> handleMoveDown());

        Separator separator = new Separator();
        separator.setOrientation(javafx.geometry.Orientation.VERTICAL);
        separator.setPadding(new Insets(2, 4, 2, 4));

        Button refreshBtn = createToolBarButton("刷新", createRefreshIcon());
        refreshBtn.setOnAction(e -> loadStructure());

        toolBar.getChildren().addAll(
                saveBtn, addFieldBtn, insertFieldBtn, deleteFieldBtn,
                primaryKeyBtn, moveUpBtn, moveDownBtn, separator, refreshBtn);

        // TableView
        tableView = new TableView<>();
        tableView.setEditable(true);
        GlobalConfig globalConfig = GlobalConfig.getInstance();
        int rowHeight = globalConfig.getTableFontSize() + 18;
        tableView.setFixedCellSize(rowHeight);
        tableView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tableView.getSelectionModel().setCellSelectionEnabled(true);
        String fontStyle = String.format("-fx-font-family: '%s'; -fx-font-size: %dpx;",
                globalConfig.getTableFontName(), globalConfig.getTableFontSize());
        tableView.setStyle(fontStyle + " -fx-padding: 0; -fx-background-insets: 0; -fx-background-color: transparent; -fx-border-color: transparent; -fx-border-insets: 0; -fx-table-header-height: " + rowHeight + ";");
        tableView.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        // 设计表使用 TableView 内部水平滚动条（未被 ScrollPane 包裹），需恢复被全局规则隐藏的内部水平滚动条
        tableView.getStyleClass().add("design-table-view");

        // Ctrl+C 复制字段、Ctrl+V 粘贴字段
        // 使用Scene加速器（最高优先级，在所有事件处理之前触发），
        // 解决ComboBox内TextField拦截Ctrl+C导致TableView事件过滤器不触发的问题
        setupCopyPasteAccelerators();

        // 右键菜单：复制/粘贴/添加/插入/删除/主键
        ContextMenu tableContextMenu = new ContextMenu();
        MenuItem copyItem = new MenuItem("复制字段");
        copyItem.setOnAction(e -> handleCopyFields());
        MenuItem pasteItem = new MenuItem("粘贴字段");
        pasteItem.setOnAction(e -> handlePasteFields());
        MenuItem addFieldItem = new MenuItem("添加字段");
        addFieldItem.setOnAction(e -> handleAddField());
        MenuItem insertFieldItem = new MenuItem("插入字段");
        insertFieldItem.setOnAction(e -> handleInsertField());
        MenuItem deleteFieldItem = new MenuItem("删除字段");
        deleteFieldItem.setOnAction(e -> handleDeleteField());
        MenuItem primaryKeyItem = new MenuItem("切换主键");
        primaryKeyItem.setOnAction(e -> handleTogglePrimaryKey());
        tableContextMenu.getItems().addAll(copyItem, pasteItem, new SeparatorMenuItem(),
                addFieldItem, insertFieldItem, deleteFieldItem, new SeparatorMenuItem(), primaryKeyItem);
        tableView.setContextMenu(tableContextMenu);

        // 非编辑状态下，方向键在单元格间切换（选中单元格而非行）
        tableView.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (tableView.getEditingCell() != null) return;
            KeyCode code = event.getCode();
            if (code != KeyCode.UP && code != KeyCode.DOWN
                    && code != KeyCode.LEFT && code != KeyCode.RIGHT) return;
            if (event.isControlDown() || event.isShiftDown() || event.isAltDown()) return;

            ObservableList<ObservableList<String>> items = tableView.getItems();
            if (items == null || items.isEmpty()) return;
            int rowCount = items.size();

            // 获取当前焦点单元格位置
            TablePosition<?, ?> focusedCell = tableView.getFocusModel().getFocusedCell();
            int curRow = focusedCell != null && focusedCell.getRow() >= 0 ? focusedCell.getRow() : 0;
            int curCol = focusedCell != null && focusedCell.getColumn() >= 0
                    ? focusedCell.getColumn() : findFirstNavigableColumn();

            int newRow = curRow;
            int newCol = curCol;
            if (code == KeyCode.UP) newRow = curRow - 1;
            else if (code == KeyCode.DOWN) newRow = curRow + 1;
            else if (code == KeyCode.LEFT) newCol = findNavigableColumn(curCol, -1);
            else if (code == KeyCode.RIGHT) newCol = findNavigableColumn(curCol, 1);

            // 最后一行按 DOWN 自动追加空行
            if (code == KeyCode.DOWN && newRow >= rowCount) {
                event.consume();
                handleAddField();
                return;
            }

            // 边界检查
            if (newRow < 0) newRow = 0;
            if (newRow >= rowCount) newRow = rowCount - 1;

            // 消费事件防止默认行选择行为
            event.consume();
            if (newRow != curRow || newCol != curCol) {
                tableView.getSelectionModel().clearSelection();
                TableColumn<ObservableList<String>, ?> col = tableView.getColumns().get(newCol);
                tableView.getSelectionModel().select(newRow, col);
                tableView.getFocusModel().focus(newRow, col);
                tableView.scrollTo(newRow);
            }
        });

        // 编辑状态下，按方向键在单元格间切换；最后一行按向下键追加新行
        // 在 TableView 上拦截（捕获阶段早于 TextField 内的行为过滤器，避免 caret 移动消费事件）
        tableView.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (tableView.getEditingCell() == null) return;
            KeyCode code = event.getCode();
            if (code != KeyCode.UP && code != KeyCode.DOWN
                    && code != KeyCode.LEFT && code != KeyCode.RIGHT) {
                return;
            }
            // ComboBox 下拉打开时方向键用于选择下拉项，不导航
            if (skipArrowNavigation != null && skipArrowNavigation.get()) return;
            event.consume();
            int deltaRow = (code == KeyCode.UP) ? -1 : (code == KeyCode.DOWN) ? 1 : 0;
            int deltaCol = (code == KeyCode.LEFT) ? -1 : (code == KeyCode.RIGHT) ? 1 : 0;
            // 先计算目标（含最后一行追加），再提交当前编辑，最后通过 runLater 进入目标单元格编辑
            Runnable commit = currentEditCommit;
            navigateFromEditCell(deltaRow, deltaCol);
            if (commit != null) {
                commit.run();
            }
        });

        // 加载指示器
        loadingIndicator = new ProgressIndicator();
        loadingIndicator.setMaxSize(40, 40);
        loadingIndicator.setVisible(false);

        // 字段标签页内容：上方表格 + 下方字段属性面板（SplitPane上下拆分）
        StackPane tablePane = new StackPane(tableView, loadingIndicator);

        // 字段属性面板：主键选中时显示自增复选框和默认值设置
        Node fieldPropsPane = createFieldPropertiesPane();

        SplitPane fieldsSplitPane = new SplitPane();
        fieldsSplitPane.setOrientation(javafx.geometry.Orientation.VERTICAL);
        fieldsSplitPane.getItems().addAll(tablePane, fieldPropsPane);
        fieldsSplitPane.setDividerPositions(0.72);
        fieldsSplitPane.setStyle("-fx-background-color: white; -fx-padding: 0; -fx-background-insets: 0;");

        // 鼠标拖拽范围选择（与打开表一致的交互）
        setupDragSelection();
        // 点击表头选中整列（替代默认的排序行为）
        setupHeaderClickSelection();

        // 监听选中行变化，更新字段属性面板
        tableView.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldSel, newSel) -> updateFieldPropertiesPane());

        // 多标签页：字段、索引、外键、触发器、选项、注释、SQL预览
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getStyleClass().add("design-tab-pane");
        tabPane.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());

        Tab fieldsTab = new Tab("字段");
        fieldsTab.setContent(fieldsSplitPane);

        Tab indexesTab = new Tab("索引");
        indexesTab.setContent(createIndexesPane());

        Tab foreignKeysTab = new Tab("外键");
        foreignKeysTab.setContent(createForeignKeysPane());

        Tab triggersTab = new Tab("触发器");
        triggersTab.setContent(createTriggersPane());

        Tab optionsTab = new Tab("选项");
        optionsTab.setContent(createOptionsPane());

        Tab commentTab = new Tab("注释");
        commentTab.setContent(createCommentPane());

        Tab sqlPreviewTab = new Tab("SQL预览");
        sqlPreviewTab.setContent(createSqlPreviewPane());

        tabPane.getTabs().addAll(fieldsTab, indexesTab, foreignKeysTab,
                triggersTab, optionsTab, commentTab, sqlPreviewTab);

        // 切换标签页时懒加载对应数据
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == null) return;
            if (newTab == indexesTab && !indexesLoaded) {
                loadIndexes();
            } else if (newTab == foreignKeysTab && !foreignKeysLoaded) {
                loadForeignKeys();
            } else if (newTab == triggersTab && !triggersLoaded) {
                loadTriggers();
            } else if (newTab == optionsTab && !optionsLoaded) {
                loadOptions();
            } else if (newTab == commentTab && !commentLoaded) {
                loadComment();
            } else if (newTab == sqlPreviewTab) {
                loadSqlPreview();
            }
        });

        // 状态栏（使用 TextField 支持选择复制错误信息，外观保持与 Label 一致）
        statusLabel = new TextField();
        statusLabel.setEditable(false);
        statusLabel.setFocusTraversable(false);
        statusLabel.setStyle("-fx-font-size: 12px; -fx-background-color: transparent; -fx-background-insets: 0; -fx-border-color: transparent; -fx-border-insets: 0; -fx-padding: 0;");
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        HBox statusBar = new HBox(statusLabel);
        statusBar.setPadding(new Insets(6, 12, 6, 12));
        statusBar.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #ddd; -fx-border-width: 1 0 0 0;");
        statusBar.setAlignment(Pos.CENTER_LEFT);

        this.setTop(toolBar);
        this.setCenter(tabPane);
        this.setBottom(statusBar);
        this.setPadding(Insets.EMPTY);
    }

    /**
     * 创建工具栏按钮（图标+文字）
     */
    private Button createToolBarButton(String text, Node icon) {
        Button btn = new Button(text);
        btn.getStyleClass().add("toolbar-button");
        btn.setStyle("-fx-font-size: 12px; -fx-padding: 4 8; -fx-content-display: LEFT; -fx-graphic-text-gap: 4;");
        if (icon != null) {
            btn.setGraphic(icon);
        }
        return btn;
    }

    /**
     * 从资源目录加载图标图片，返回指定尺寸的ImageView。
     * @param resourcePath 资源路径（如 /images/connect/col_add.png）
     * @param size 图标边长（像素）
     */
    private Node createImageIcon(String resourcePath, int size) {
        try {
            Image img = new Image(getClass().getResourceAsStream(resourcePath));
            ImageView iv = new ImageView(img);
            iv.setFitWidth(size);
            iv.setFitHeight(size);
            iv.setPreserveRatio(true);
            return iv;
        } catch (Exception e) {
            // 加载失败时返回空Label，避免按钮显示异常
            return new Label("");
        }
    }

    /** 保存图标：蓝色上箭头 */
    private Node createSaveIcon() {
        return createImageIcon("/images/connect/save.png", 16);
    }

    /** 添加字段图标 */
    private Node createAddIcon() {
        return createImageIcon("/images/connect/col_add.png", 16);
    }

    /** 插入字段图标 */
    private Node createInsertIcon() {
        return createImageIcon("/images/connect/col_jump.png", 16);
    }

    /** 主键图标 */
    private Node createPrimaryKeyIcon() {
        return createImageIcon("/images/connect/primary_key.png", 16);
    }

    /** 上移图标 */
    private Node createMoveUpIcon() {
        return createImageIcon("/images/connect/up.png", 16);
    }

    /** 下移图标 */
    private Node createMoveDownIcon() {
        return createImageIcon("/images/connect/down.png", 16);
    }

    /** 删除图标 */
    private Node createDeleteIcon() {
        return createImageIcon("/images/connect/col_del.png", 16);
    }

    /** 刷新图标：灰色环形箭头 */
    private Node createRefreshIcon() {
        javafx.scene.Group g = new javafx.scene.Group();
        Arc arc = new Arc(7, 7, 6, 6, 45, 270);
        arc.setType(ArcType.OPEN);
        arc.setStroke(Color.valueOf("#666666"));
        arc.setStrokeWidth(2);
        arc.setFill(null);
        Polygon arrowHead = new Polygon(12, 3, 14, 7, 10, 6);
        arrowHead.setFill(Color.valueOf("#666666"));
        g.getChildren().addAll(arc, arrowHead);
        return g;
    }

    /**
     * 创建只读信息表格的通用方法（用于索引/外键/触发器标签页）
     */
    private TableView<ObservableList<String>> createInfoTableView() {
        TableView<ObservableList<String>> tv = new TableView<>();
        tv.setEditable(false);
        GlobalConfig globalConfig = GlobalConfig.getInstance();
        int rowHeight = globalConfig.getTableFontSize() + 18;
        tv.setFixedCellSize(rowHeight);
        String fontStyle = String.format("-fx-font-family: '%s'; -fx-font-size: %dpx;",
                globalConfig.getTableFontName(), globalConfig.getTableFontSize());
        tv.setStyle(fontStyle + " -fx-padding: 0; -fx-background-insets: 0; -fx-background-color: transparent; -fx-border-color: transparent; -fx-border-insets: 0; -fx-table-header-height: " + rowHeight + ";");
        tv.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        tv.setPlaceholder(new Label("暂无数据"));
        return tv;
    }

    /**
     * 创建小型工具栏（添加/删除/刷新）
     */
    private HBox createInfoToolBar(Button addBtn, Button deleteBtn, Button refreshBtn) {
        HBox toolBar = new HBox(2);
        toolBar.setPadding(new Insets(4, 8, 4, 8));
        toolBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #ddd; -fx-border-width: 0 0 1 0;");
        toolBar.setAlignment(Pos.CENTER_LEFT);
        if (addBtn != null) toolBar.getChildren().add(addBtn);
        if (deleteBtn != null) toolBar.getChildren().add(deleteBtn);
        if (refreshBtn != null) toolBar.getChildren().add(refreshBtn);
        return toolBar;
    }

    /**
     * 字段属性面板：位于字段标签页下方。
     * - 默认值：所有类型可见
     * - 字符集/排序规则/键长度/二进制：字符串类型可见
     * - 自增复选框：主键可见
     * - 无符号/填充零：数字类型可见
     */
    private VBox createFieldPropertiesPane() {
        VBox box = new VBox(4);
        box.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #ddd; -fx-border-width: 1 0 0 0;");
        box.setPadding(new Insets(8, 12, 8, 12));

        // 行标签统一宽度，保证对齐
        double labelWidth = 70;

        // 默认值行（所有类型可见）
        // 下拉项参考 Navicat：空、NULL、CURRENT_TIMESTAMP；宽度调细
        Label defaultLabel = new Label("默认:");
        defaultLabel.setStyle("-fx-font-size: 12px;");
        defaultLabel.setPrefWidth(labelWidth);
        defaultValueComboBox = new ComboBox<>();
        defaultValueComboBox.setEditable(true);
        defaultValueComboBox.setPrefWidth(200);
        defaultValueComboBox.getItems().addAll("", "NULL", "CURRENT_TIMESTAMP");
        defaultValueComboBox.valueProperty().addListener((obs, oldVal, nv) -> {
            ObservableList<String> selected = tableView.getSelectionModel().getSelectedItem();
            if (selected == null || columnTitles == null) return;
            int dvIdx = columnTitles.indexOf("默认值");
            if (dvIdx >= 0 && dvIdx < selected.size()) {
                String current = selected.get(dvIdx);
                String val = nv != null ? nv : "";
                if (!val.equals(current != null ? current : "")) {
                    selected.set(dvIdx, val);
                    tableView.refresh();
                    markDirty();
                }
            }
        });
        HBox defaultRow = new HBox(8, defaultLabel, defaultValueComboBox);
        defaultRow.setAlignment(Pos.CENTER_LEFT);

        // 字符集行（仅字符串类型可见）
        // 参考 Navicat：可编辑，允许手动输入或从列表选择
        Label charsetLabel = new Label("字符集:");
        charsetLabel.setStyle("-fx-font-size: 12px;");
        charsetLabel.setPrefWidth(labelWidth);
        fieldCharsetComboBox = new ComboBox<>();
        fieldCharsetComboBox.setEditable(true);
        fieldCharsetComboBox.setPrefWidth(200);
        fieldCharsetComboBox.setVisibleRowCount(15);
        fieldCharsetComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            ObservableList<String> selected = tableView.getSelectionModel().getSelectedItem();
            if (selected == null || columnTitles == null) return;
            int csIdx = columnTitles.indexOf("字符集");
            if (csIdx >= 0 && csIdx < selected.size()) {
                String current = selected.get(csIdx);
                String val = newVal != null ? newVal : "";
                if (!val.equals(current != null ? current : "")) {
                    selected.set(csIdx, val);
                    // 字符集变化时联动更新排序规则
                    if (cachedCharsets != null && newVal != null) {
                        List<String> collations = cachedCharsets.get(newVal);
                        if (collations != null) {
                            String currentColl = null;
                            int coIdx = columnTitles.indexOf("排序规则");
                            if (coIdx >= 0 && coIdx < selected.size()) {
                                currentColl = selected.get(coIdx);
                            }
                            fieldCollationComboBox.getItems().setAll(collations);
                            if (currentColl != null && collations.contains(currentColl)) {
                                fieldCollationComboBox.setValue(currentColl);
                            } else if (!collations.isEmpty()) {
                                fieldCollationComboBox.setValue(collations.get(0));
                            }
                        }
                    }
                    tableView.refresh();
                    markDirty();
                }
            }
        });
        charsetRow = new HBox(8, charsetLabel, fieldCharsetComboBox);
        charsetRow.setAlignment(Pos.CENTER_LEFT);

        // 排序规则行（仅字符串类型可见）
        // 参考 Navicat：可编辑，允许手动输入或从列表选择
        Label collationLabel = new Label("排序规则:");
        collationLabel.setStyle("-fx-font-size: 12px;");
        collationLabel.setPrefWidth(labelWidth);
        fieldCollationComboBox = new ComboBox<>();
        fieldCollationComboBox.setEditable(true);
        fieldCollationComboBox.setPrefWidth(200);
        fieldCollationComboBox.setVisibleRowCount(15);
        fieldCollationComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            ObservableList<String> selected = tableView.getSelectionModel().getSelectedItem();
            if (selected == null || columnTitles == null) return;
            int coIdx = columnTitles.indexOf("排序规则");
            if (coIdx >= 0 && coIdx < selected.size()) {
                String current = selected.get(coIdx);
                String val = newVal != null ? newVal : "";
                if (!val.equals(current != null ? current : "")) {
                    selected.set(coIdx, val);
                    tableView.refresh();
                    markDirty();
                }
            }
        });
        collationRow = new HBox(8, collationLabel, fieldCollationComboBox);
        collationRow.setAlignment(Pos.CENTER_LEFT);

        // 键长度行（仅字符串类型可见）
        Label keyLenLabel = new Label("键长度:");
        keyLenLabel.setStyle("-fx-font-size: 12px;");
        keyLenLabel.setPrefWidth(labelWidth);
        keyLengthField = new TextField();
        keyLengthField.setPrefWidth(200);
        keyLengthField.setStyle("-fx-font-size: 12px;");
        keyLengthField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (!isNowFocused) {
                ObservableList<String> selected = tableView.getSelectionModel().getSelectedItem();
                if (selected == null || columnTitles == null) return;
                int idx = columnTitles.indexOf("键长度");
                if (idx >= 0 && idx < selected.size()) {
                    String newVal = keyLengthField.getText();
                    if (!newVal.equals(selected.get(idx))) {
                        selected.set(idx, newVal);
                        tableView.refresh();
                        markDirty();
                    }
                }
            }
        });
        keyLengthRow = new HBox(8, keyLenLabel, keyLengthField);
        keyLengthRow.setAlignment(Pos.CENTER_LEFT);

        // 复选框：二进制 + 自增 + 无符号 + 填充零
        // 每个复选框单独成行，padding 设为 0，让方框紧贴左侧，与"默认"文本对齐
        // 直接加入 fieldPropsBox（不嵌套 VBox），保证与上方各行间距统一
        binaryCheckBox = new CheckBox("二进制");
        binaryCheckBox.setStyle("-fx-font-size: 12px; -fx-padding: 0;");
        binaryCheckBox.setOnAction(e -> {
            ObservableList<String> selected = tableView.getSelectionModel().getSelectedItem();
            if (selected == null || columnTitles == null) return;
            int idx = columnTitles.indexOf("二进制");
            if (idx >= 0 && idx < selected.size()) {
                selected.set(idx, binaryCheckBox.isSelected() ? "是" : "否");
                markDirty();
            }
        });

        autoIncrementCheckBox = new CheckBox("自动递增");
        autoIncrementCheckBox.setStyle("-fx-font-size: 12px; -fx-padding: 0;");
        // 减小与上方元素间距（VBox spacing 6 + margin -2 = 4）
        VBox.setMargin(autoIncrementCheckBox, new Insets(-2, 0, 0, 0));
        autoIncrementCheckBox.setOnAction(e -> {
            ObservableList<String> selected = tableView.getSelectionModel().getSelectedItem();
            if (selected == null || columnTitles == null) return;
            int aiIdx = columnTitles.indexOf("自增");
            if (aiIdx >= 0 && aiIdx < selected.size()) {
                selected.set(aiIdx, autoIncrementCheckBox.isSelected() ? "是" : "否");
                markDirty();
            }
        });

        unsignedCheckBox = new CheckBox("无符号");
        unsignedCheckBox.setStyle("-fx-font-size: 12px; -fx-padding: 0;");
        unsignedCheckBox.setOnAction(e -> {
            ObservableList<String> selected = tableView.getSelectionModel().getSelectedItem();
            if (selected == null || columnTitles == null) return;
            int idx = columnTitles.indexOf("无符号");
            if (idx >= 0 && idx < selected.size()) {
                selected.set(idx, unsignedCheckBox.isSelected() ? "是" : "否");
                markDirty();
            }
        });

        zeroFillCheckBox = new CheckBox("填充零");
        zeroFillCheckBox.setStyle("-fx-font-size: 12px; -fx-padding: 0;");
        zeroFillCheckBox.setOnAction(e -> {
            ObservableList<String> selected = tableView.getSelectionModel().getSelectedItem();
            if (selected == null || columnTitles == null) return;
            int idx = columnTitles.indexOf("填充零");
            if (idx >= 0 && idx < selected.size()) {
                selected.set(idx, zeroFillCheckBox.isSelected() ? "是" : "否");
                markDirty();
            }
        });

        fieldPropsBox = new VBox(6);
        fieldPropsBox.getChildren().addAll(
                defaultRow, charsetRow, collationRow, keyLengthRow,
                binaryCheckBox, autoIncrementCheckBox, unsignedCheckBox, zeroFillCheckBox);

        // 占位提示
        fieldPropsPlaceholder = new Label("请选择字段以编辑属性");
        fieldPropsPlaceholder.setStyle("-fx-font-size: 12px; -fx-text-fill: #999;");

        StackPane stack = new StackPane();
        stack.getChildren().addAll(fieldPropsBox, fieldPropsPlaceholder);
        StackPane.setAlignment(fieldPropsPlaceholder, Pos.CENTER_LEFT);

        box.getChildren().add(stack);
        // 初始状态：显示占位提示
        fieldPropsBox.setVisible(false);
        fieldPropsPlaceholder.setVisible(true);
        return box;
    }

    /**
     * 字段表格的鼠标拖拽范围选择（单元格级，与打开表交互一致）。
     * 在数据单元格上按下左键并拖动，实现矩形范围多单元格选中；
     * 行选择器列按下则选中整行；Shift+点击从锚点到当前单元格矩形选中；
     * Ctrl+点击交给默认行为处理（非连续选择）。
     */
    private void setupDragSelection() {
        final int[] dragStart = {-1, -1}; // [row, col]

        tableView.setOnMousePressed(event -> {
            if (event.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            int[] cellPos = getCellPositionAt(event);
            if (cellPos == null) {
                // 点击空白区域（右侧空白或表格下方空白）：清除选中，不选中任何cell或行
                tableView.getSelectionModel().clearSelection();
                anchorCell[0] = -1;
                anchorCell[1] = -1;
                return;
            }

            if (event.isShiftDown() && anchorCell[0] >= 0) {
                // Shift+点击：从锚点到当前cell的矩形范围选中
                dragStart[0] = -1;
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
            // 更新锚点
            anchorCell[0] = cellPos[0];
            anchorCell[1] = cellPos[1];
            // 清除已有选中，选中起始cell
            tableView.getSelectionModel().clearSelection();
            TableColumn<ObservableList<String>, ?> col = tableView.getColumns().get(cellPos[1]);
            tableView.getSelectionModel().select(cellPos[0], col);
        });

        tableView.setOnMouseDragged(event -> {
            if (event.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            if (dragStart[0] < 0) return;
            int[] cellPos = getCellPositionAt(event);
            if (cellPos == null) return;
            int endRow = cellPos[0];
            int endCol = cellPos[1];
            // 范围选中
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

        tableView.setOnMouseReleased(event -> dragStart[0] = -1);
    }

    /**
     * 点击表头选中整列（替代默认的排序行为，已通过 col.setSortable(false) 禁用排序）。
     * 检测点击命中的 column-header 节点，通过表头文本匹配对应的 TableColumn，
     * 然后用 selectRange(0, col, lastRow, col) 选中整列。
     */
    private void setupHeaderClickSelection() {
        tableView.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != javafx.scene.input.MouseButton.PRIMARY) return;
            Node target = event.getPickResult().getIntersectedNode();
            while (target != null && target != tableView) {
                if (target.getStyleClass().contains("column-header")) {
                    String headerText = findHeaderText(target);
                    if (headerText == null || headerText.isEmpty()) return; // 行选择器列等无文本表头
                    for (TableColumn<ObservableList<String>, ?> col : tableView.getColumns()) {
                        if (headerText.equals(col.getText())) {
                            selectEntireColumn(col);
                            event.consume();
                            return;
                        }
                    }
                    return;
                }
                target = target.getParent();
            }
        });
    }

    /**
     * 递归查找 column-header 节点中的文本（LabeledText 继承自 Text）。
     */
    private String findHeaderText(Node node) {
        if (node instanceof javafx.scene.text.Text text) {
            String t = text.getText();
            if (t != null && !t.isEmpty()) return t;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                String t = findHeaderText(child);
                if (t != null && !t.isEmpty()) return t;
            }
        }
        return null;
    }

    /**
     * 选中指定列的所有数据单元格。
     */
    private void selectEntireColumn(TableColumn<ObservableList<String>, ?> col) {
        int lastRow = tableView.getItems().size() - 1;
        if (lastRow < 0) return;
        tableView.getSelectionModel().clearSelection();
        tableView.getSelectionModel().selectRange(0, col, lastRow, col);
    }

    /**
     * 根据鼠标事件位置获取对应的单元格位置 [row, col]。
     * 通过遍历 PickResult 节点链找到 TableCell，直接获取行列索引。
     * 点击非TableCell区域（如右侧空白）时返回 null，不选中任何cell或行。
     */
    private int[] getCellPositionAt(javafx.scene.input.MouseEvent event) {
        Node target = event.getPickResult().getIntersectedNode();
        while (target != null && target != tableView) {
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
        // 点击的不是TableCell（如右侧空白区域），不选中任何cell
        return null;
    }

    /**
     * 查找指定方向上的下一个可导航列（跳过行选择器列和不可见列）
     * @param startCol 起始列索引
     * @param direction 方向（-1 向左，1 向右）
     * @return 下一个可导航列索引，未找到则返回 startCol
     */
    private int findNavigableColumn(int startCol, int direction) {
        for (int i = startCol + direction; i >= 0 && i < tableView.getColumns().size(); i += direction) {
            TableColumn<ObservableList<String>, ?> col = tableView.getColumns().get(i);
            if (col.isVisible() && !ROW_SELECTOR_COL.equals(col.getUserData())) {
                return i;
            }
        }
        return startCol;
    }

    /**
     * 查找第一个可导航列（跳过行选择器列和不可见列）
     */
    private int findFirstNavigableColumn() {
        for (int i = 0; i < tableView.getColumns().size(); i++) {
            TableColumn<ObservableList<String>, ?> col = tableView.getColumns().get(i);
            if (col.isVisible() && !ROW_SELECTOR_COL.equals(col.getUserData())) {
                return i;
            }
        }
        return 0;
    }

    /**
     * 根据当前选中行更新字段属性面板：
     * - 默认值：所有类型可见
     * - 字符集/排序规则/键长度/二进制：字符串类型可见
     * - 自增复选框：主键可见
     * - 无符号/填充零：数字类型可见
     */
    private void updateFieldPropertiesPane() {
        ObservableList<String> selected = tableView.getSelectionModel().getSelectedItem();
        if (selected == null || columnTitles == null) {
            fieldPropsBox.setVisible(false);
            fieldPropsPlaceholder.setVisible(true);
            return;
        }

        fieldPropsBox.setVisible(true);
        fieldPropsPlaceholder.setVisible(false);

        int pkIdx = columnTitles.indexOf("主键");
        int typeIdx = columnTitles.indexOf("类型");
        int aiIdx = columnTitles.indexOf("自增");
        int dvIdx = columnTitles.indexOf("默认值");
        int usIdx = columnTitles.indexOf("无符号");
        int zfIdx = columnTitles.indexOf("填充零");
        int csIdx = columnTitles.indexOf("字符集");
        int coIdx = columnTitles.indexOf("排序规则");
        int klIdx = columnTitles.indexOf("键长度");
        int biIdx = columnTitles.indexOf("二进制");

        boolean isPk = pkIdx >= 0 && pkIdx < selected.size() && "是".equals(selected.get(pkIdx));
        String typeName = typeIdx >= 0 && typeIdx < selected.size() ? selected.get(typeIdx) : "";
        boolean isNumeric = isNumericType(typeName);
        boolean isString = isStringType(typeName);

        // 加载默认值
        if (dvIdx >= 0 && dvIdx < selected.size()) {
            String val = selected.get(dvIdx);
            defaultValueComboBox.setValue(val != null ? val : "");
        } else {
            defaultValueComboBox.setValue("");
        }

        // 字符串类型：加载字符集、排序规则、键长度、二进制
        // 整行隐藏（含Label且不占位）：仅字符串类型显示字符集/排序规则/键长度
        charsetRow.setVisible(isString);
        charsetRow.setManaged(isString);
        collationRow.setVisible(isString);
        collationRow.setManaged(isString);
        keyLengthRow.setVisible(isString);
        keyLengthRow.setManaged(isString);
        fieldCharsetComboBox.setVisible(isString);
        fieldCollationComboBox.setVisible(isString);
        keyLengthField.setVisible(isString);
        binaryCheckBox.setVisible(isString);
        if (isString) {
            // 填充字符集下拉项
            if (cachedCharsets != null && !cachedCharsets.isEmpty()) {
                fieldCharsetComboBox.getItems().setAll(cachedCharsets.keySet());
            }
            if (csIdx >= 0 && csIdx < selected.size()) {
                String cs = selected.get(csIdx);
                fieldCharsetComboBox.setValue(cs != null ? cs : "");
                // 联动填充排序规则
                if (cachedCharsets != null && cs != null && !cs.isEmpty()) {
                    List<String> collations = cachedCharsets.get(cs);
                    if (collations != null) {
                        fieldCollationComboBox.getItems().setAll(collations);
                    }
                }
            } else {
                fieldCharsetComboBox.setValue("");
            }
            if (coIdx >= 0 && coIdx < selected.size()) {
                fieldCollationComboBox.setValue(selected.get(coIdx));
            } else {
                fieldCollationComboBox.setValue("");
            }
            if (klIdx >= 0 && klIdx < selected.size()) {
                keyLengthField.setText(selected.get(klIdx));
            } else {
                keyLengthField.setText("");
            }
            if (biIdx >= 0 && biIdx < selected.size()) {
                binaryCheckBox.setSelected("是".equals(selected.get(biIdx)));
            } else {
                binaryCheckBox.setSelected(false);
            }
        }

        // 自增复选框：仅主键显示（setManaged 让隐藏后不占位，下方项目自动上移）
        autoIncrementCheckBox.setVisible(isPk);
        autoIncrementCheckBox.setManaged(isPk);
        if (isPk && aiIdx >= 0 && aiIdx < selected.size()) {
            autoIncrementCheckBox.setSelected("是".equals(selected.get(aiIdx)));
        } else {
            autoIncrementCheckBox.setSelected(false);
        }

        // 无符号和填充零复选框：仅数字类型显示
        unsignedCheckBox.setVisible(isNumeric);
        zeroFillCheckBox.setVisible(isNumeric);
        if (isNumeric) {
            if (usIdx >= 0 && usIdx < selected.size()) {
                unsignedCheckBox.setSelected("是".equals(selected.get(usIdx)));
            } else {
                unsignedCheckBox.setSelected(false);
            }
            if (zfIdx >= 0 && zfIdx < selected.size()) {
                zeroFillCheckBox.setSelected("是".equals(selected.get(zfIdx)));
            } else {
                zeroFillCheckBox.setSelected(false);
            }
        }
    }

    /**
     * 判断类型名是否为数字类型（用于显示无符号/填充零复选框）
     */
    private boolean isNumericType(String typeName) {
        if (typeName == null) return false;
        String t = typeName.toLowerCase();
        return t.contains("int") || t.contains("decimal") || t.contains("float")
                || t.contains("double") || t.contains("numeric") || t.contains("number")
                || t.contains("bit") || t.contains("real") || t.contains("serial");
    }

    /**
     * 判断类型名是否为字符串/二进制类型（用于显示字符集/排序规则/键长度/二进制）
     */
    private boolean isStringType(String typeName) {
        if (typeName == null) return false;
        String t = typeName.toLowerCase();
        return t.contains("char") || t.contains("text") || t.contains("enum")
                || t.contains("set") || t.contains("binary") || t.contains("blob")
                || t.contains("clob") || t.contains("string");
    }

    /**
     * 索引标签页：工具栏 + 表格 + 加载指示器
     */
    private BorderPane createIndexesPane() {
        Button addBtn = createToolBarButton("添加索引", createAddIcon());
        addBtn.setOnAction(e -> statusLabel.setText("添加索引功能待实现"));
        Button deleteBtn = createToolBarButton("删除", createDeleteIcon());
        deleteBtn.setOnAction(e -> statusLabel.setText("删除索引功能待实现"));
        Button refreshBtn = createToolBarButton("刷新", createRefreshIcon());
        refreshBtn.setOnAction(e -> { indexesLoaded = false; loadIndexes(); });

        indexesTableView = createInfoTableView();
        indexesLoadingIndicator = new ProgressIndicator();
        indexesLoadingIndicator.setMaxSize(40, 40);
        indexesLoadingIndicator.setVisible(false);
        StackPane center = new StackPane(indexesTableView, indexesLoadingIndicator);

        BorderPane pane = new BorderPane();
        pane.setTop(createInfoToolBar(addBtn, deleteBtn, refreshBtn));
        pane.setCenter(center);
        return pane;
    }

    /**
     * 外键标签页：工具栏 + 表格 + 加载指示器
     */
    private BorderPane createForeignKeysPane() {
        Button addBtn = createToolBarButton("添加外键", createAddIcon());
        addBtn.setOnAction(e -> statusLabel.setText("添加外键功能待实现"));
        Button deleteBtn = createToolBarButton("删除", createDeleteIcon());
        deleteBtn.setOnAction(e -> statusLabel.setText("删除外键功能待实现"));
        Button refreshBtn = createToolBarButton("刷新", createRefreshIcon());
        refreshBtn.setOnAction(e -> { foreignKeysLoaded = false; loadForeignKeys(); });

        foreignKeysTableView = createInfoTableView();
        foreignKeysLoadingIndicator = new ProgressIndicator();
        foreignKeysLoadingIndicator.setMaxSize(40, 40);
        foreignKeysLoadingIndicator.setVisible(false);
        StackPane center = new StackPane(foreignKeysTableView, foreignKeysLoadingIndicator);

        BorderPane pane = new BorderPane();
        pane.setTop(createInfoToolBar(addBtn, deleteBtn, refreshBtn));
        pane.setCenter(center);
        return pane;
    }

    /**
     * 触发器标签页：工具栏 + 表格 + 加载指示器
     */
    private BorderPane createTriggersPane() {
        Button addBtn = createToolBarButton("添加触发器", createAddIcon());
        addBtn.setOnAction(e -> statusLabel.setText("添加触发器功能待实现"));
        Button deleteBtn = createToolBarButton("删除", createDeleteIcon());
        deleteBtn.setOnAction(e -> statusLabel.setText("删除触发器功能待实现"));
        Button refreshBtn = createToolBarButton("刷新", createRefreshIcon());
        refreshBtn.setOnAction(e -> { triggersLoaded = false; loadTriggers(); });

        triggersTableView = createInfoTableView();
        triggersLoadingIndicator = new ProgressIndicator();
        triggersLoadingIndicator.setMaxSize(40, 40);
        triggersLoadingIndicator.setVisible(false);
        StackPane center = new StackPane(triggersTableView, triggersLoadingIndicator);

        BorderPane pane = new BorderPane();
        pane.setTop(createInfoToolBar(addBtn, deleteBtn, refreshBtn));
        pane.setCenter(center);
        return pane;
    }

    /**
     * 选项标签页：表选项表单（引擎、字符集、排序规则、自增值、行格式等）
     */
    private StackPane createOptionsPane() {
        VBox formBox = new VBox(8);
        formBox.setPadding(new Insets(12));
        formBox.setStyle("-fx-background-color: white;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);

        // 引擎
        Label engineLabel = new Label("引擎:");
        engineLabel.setStyle("-fx-font-size: 12px;");
        engineComboBox = new ComboBox<>();
        engineComboBox.setEditable(true);
        engineComboBox.setPrefWidth(220);
        engineComboBox.setVisibleRowCount(15);

        // 字符集
        Label charsetLabel = new Label("字符集:");
        charsetLabel.setStyle("-fx-font-size: 12px;");
        charsetComboBox = new ComboBox<>();
        charsetComboBox.setEditable(true);
        charsetComboBox.setPrefWidth(220);
        charsetComboBox.setVisibleRowCount(15);
        // 字符集变化时联动更新排序规则下拉项
        charsetComboBox.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) return;
            String currentCollation = collationComboBox.getValue();
            collationComboBox.getItems().clear();
            if (cachedCharsets != null) {
                List<String> collations = cachedCharsets.get(newVal);
                if (collations != null) {
                    collationComboBox.getItems().addAll(collations);
                }
                if (currentCollation != null && collationComboBox.getItems().contains(currentCollation)) {
                    collationComboBox.setValue(currentCollation);
                } else if (!collationComboBox.getItems().isEmpty()) {
                    collationComboBox.setValue(collationComboBox.getItems().get(0));
                }
            }
        });

        // 排序规则
        Label collationLabel = new Label("排序规则:");
        collationLabel.setStyle("-fx-font-size: 12px;");
        collationComboBox = new ComboBox<>();
        collationComboBox.setEditable(true);
        collationComboBox.setPrefWidth(220);
        collationComboBox.setVisibleRowCount(15);

        // 自增值
        autoIncrementLabel = new Label("自增值:");
        autoIncrementLabel.setStyle("-fx-font-size: 12px;");
        autoIncrementField = new TextField();
        autoIncrementField.setPrefWidth(220);
        autoIncrementField.setStyle("-fx-font-size: 12px;");

        // 行格式
        rowFormatLabel = new Label("行格式:");
        rowFormatLabel.setStyle("-fx-font-size: 12px;");
        rowFormatComboBox = new ComboBox<>();
        rowFormatComboBox.setEditable(true);
        rowFormatComboBox.setPrefWidth(220);
        rowFormatComboBox.getItems().addAll("Compact", "Dynamic", "Fixed", "Compressed", "Redundant", "Default");

        // 平均行长
        avgRowLengthLabel = new Label("平均行长:");
        avgRowLengthLabel.setStyle("-fx-font-size: 12px;");
        avgRowLengthField = new TextField();
        avgRowLengthField.setPrefWidth(220);
        avgRowLengthField.setStyle("-fx-font-size: 12px;");

        int row = 0;
        grid.add(engineLabel, 0, row);
        grid.add(engineComboBox, 1, row++);
        grid.add(charsetLabel, 0, row);
        grid.add(charsetComboBox, 1, row++);
        grid.add(collationLabel, 0, row);
        grid.add(collationComboBox, 1, row++);
        grid.add(autoIncrementLabel, 0, row);
        grid.add(autoIncrementField, 1, row++);
        grid.add(rowFormatLabel, 0, row);
        grid.add(rowFormatComboBox, 1, row++);
        grid.add(avgRowLengthLabel, 0, row);
        grid.add(avgRowLengthField, 1, row++);

        formBox.getChildren().add(grid);

        // 加载指示器
        optionsLoadingIndicator = new ProgressIndicator();
        optionsLoadingIndicator.setMaxSize(40, 40);
        optionsLoadingIndicator.setVisible(false);

        return new StackPane(formBox, optionsLoadingIndicator);
    }

    /**
     * 注释标签页：可编辑文本区域
     */
    private VBox createCommentPane() {
        VBox box = new VBox();
        box.setStyle("-fx-background-color: white;");
        commentTextArea = new TextArea();
        commentTextArea.setPromptText("请输入表注释");
        commentTextArea.setWrapText(true);
        commentTextArea.getStyleClass().add("comment-text-area");
        VBox.setVgrow(commentTextArea, javafx.scene.layout.Priority.ALWAYS);
        box.getChildren().add(commentTextArea);
        return box;
    }

    /**
     * SQL预览标签页：展示生成SQL的只读文本区域
     */
    private VBox createSqlPreviewPane() {
        VBox box = new VBox(4);
        box.setStyle("-fx-background-color: white;");
        sqlPreviewViewer = new SqlPreviewViewer();
        sqlPreviewViewer.setText("-- 加载中...");
        VBox.setVgrow(sqlPreviewViewer.getNode(), javafx.scene.layout.Priority.ALWAYS);

        // 模式下拉框：保存（ALTER语句）/ 另存为（CREATE TABLE完整SQL）
        sqlPreviewModeBox = new ComboBox<>();
        sqlPreviewModeBox.getItems().addAll("保存", "另存为");
        sqlPreviewModeBox.getSelectionModel().selectFirst();
        sqlPreviewModeBox.setMaxWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        sqlPreviewModeBox.setVisibleRowCount(2);
        sqlPreviewModeBox.setOnAction(e -> loadSqlPreview());
        // 修复弹出位置：弹出后禁用autoFix、重新定位、并锁定Y坐标防止跳位
        sqlPreviewModeBox.setOnShown(e -> Platform.runLater(() -> {
            for (javafx.stage.Window w : javafx.stage.Window.getWindows()) {
                if (w instanceof javafx.stage.PopupWindow && w.isShowing()) {
                    javafx.stage.PopupWindow popup = (javafx.stage.PopupWindow) w;
                    popup.setAutoFix(false);
                    // 覆盖CSS中的min-height:200px，消除空白选项
                    if (popup.getScene() != null && popup.getScene().getRoot() != null) {
                        popup.getScene().getRoot().lookupAll(".list-view").forEach(n ->
                                n.setStyle("-fx-min-height: 0; -fx-pref-height: 65px; -fx-max-height: 65px;"));
                    }
                    javafx.geometry.Point2D pos = sqlPreviewModeBox.localToScreen(0, sqlPreviewModeBox.getHeight());
                    if (pos != null) {
                        popupTargetY = pos.getY();
                        popup.setX(pos.getX());
                        popup.setY(popupTargetY);
                        // 只添加一次Y坐标监听器，防止JavaFX后续自动重定位
                        if (!popupListenerAdded) {
                            popup.yProperty().addListener((obs, old, newY) -> {
                                if (popup.isShowing() && popupTargetY >= 0
                                        && Math.abs(newY.doubleValue() - popupTargetY) > 1) {
                                    Platform.runLater(() -> {
                                        if (popup.isShowing()) popup.setY(popupTargetY);
                                    });
                                }
                            });
                            popupListenerAdded = true;
                        }
                    }
                    break;
                }
            }
        }));

        HBox modeBox = new HBox(sqlPreviewModeBox);
        modeBox.setPadding(new Insets(2, 0, 0, 0));
        modeBox.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        box.getChildren().addAll(sqlPreviewViewer.getNode(), modeBox);
        return box;
    }

    // ====== 各标签页数据加载 ======

    /**
     * 加载索引数据并填充表格
     */
    private void loadIndexes() {
        indexesLoadingIndicator.setVisible(true);
        indexesTableView.setDisable(true);
        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, databaseName);
            connLock.lock();
            try {
                List<Map<String, String>> indexes = DatabaseService.getTableIndexes(config, databaseName, schemaName, tableName);
                Platform.runLater(() -> {
                    populateInfoTable(indexesTableView, indexes, java.util.List.of("名称", "字段", "类型", "方法", "唯一", "注释"),
                            java.util.Map.of("名称", 180, "字段", 200, "类型", 100, "方法", 100, "唯一", 60, "注释", 200));
                    indexesLoaded = true;
                    indexesLoadingIndicator.setVisible(false);
                    indexesTableView.setDisable(false);
                    statusLabel.setText("共 " + indexes.size() + " 个索引");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    indexesLoadingIndicator.setVisible(false);
                    indexesTableView.setDisable(false);
                    statusLabel.setText("加载索引失败: " + e.getMessage());
                });
            } finally {
                connLock.unlock();
            }
        }, "DB-LoadIndexes").start();
    }

    /**
     * 加载外键数据并填充表格
     */
    private void loadForeignKeys() {
        foreignKeysLoadingIndicator.setVisible(true);
        foreignKeysTableView.setDisable(true);
        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, databaseName);
            connLock.lock();
            try {
                List<Map<String, String>> fks = DatabaseService.getTableForeignKeys(config, databaseName, schemaName, tableName);
                Platform.runLater(() -> {
                    populateInfoTable(foreignKeysTableView, fks,
                            java.util.List.of("名称", "字段", "参考数据库", "参考表", "参考字段", "删除时", "更新时"),
                            java.util.Map.of("名称", 160, "字段", 150, "参考数据库", 120, "参考表", 150, "参考字段", 150, "删除时", 100, "更新时", 100));
                    foreignKeysLoaded = true;
                    foreignKeysLoadingIndicator.setVisible(false);
                    foreignKeysTableView.setDisable(false);
                    statusLabel.setText("共 " + fks.size() + " 个外键");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    foreignKeysLoadingIndicator.setVisible(false);
                    foreignKeysTableView.setDisable(false);
                    statusLabel.setText("加载外键失败: " + e.getMessage());
                });
            } finally {
                connLock.unlock();
            }
        }, "DB-LoadForeignKeys").start();
    }

    /**
     * 加载触发器数据并填充表格
     */
    private void loadTriggers() {
        triggersLoadingIndicator.setVisible(true);
        triggersTableView.setDisable(true);
        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, databaseName);
            connLock.lock();
            try {
                List<Map<String, String>> triggers = DatabaseService.getTableTriggers(config, databaseName, schemaName, tableName);
                Platform.runLater(() -> {
                    populateInfoTable(triggersTableView, triggers,
                            java.util.List.of("名称", "时机", "事件", "语句"),
                            java.util.Map.of("名称", 180, "时机", 100, "事件", 100, "语句", 500));
                    triggersLoaded = true;
                    triggersLoadingIndicator.setVisible(false);
                    triggersTableView.setDisable(false);
                    statusLabel.setText("共 " + triggers.size() + " 个触发器");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    triggersLoadingIndicator.setVisible(false);
                    triggersTableView.setDisable(false);
                    statusLabel.setText("加载触发器失败: " + e.getMessage());
                });
            } finally {
                connLock.unlock();
            }
        }, "DB-LoadTriggers").start();
    }

    /**
     * 加载表选项并填充表单控件
     */
    private void loadOptions() {
        optionsLoadingIndicator.setVisible(true);
        engineComboBox.setDisable(true);
        charsetComboBox.setDisable(true);
        collationComboBox.setDisable(true);
        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, databaseName);
            connLock.lock();
            try {
                Map<String, String> options = isNewTable ? new LinkedHashMap<>()
                        : DatabaseService.getTableOptions(config, databaseName, schemaName, tableName);
                // 加载可用引擎列表
                List<String> engines = DatabaseService.getEngines(config);
                // 加载字符集列表
                Map<String, List<String>> charsets = DatabaseService.getCharsets(config);
                Platform.runLater(() -> {
                    // 先缓存字符集映射，供字符集联动监听器使用
                    cachedCharsets = charsets;

                    // 引擎
                    engineComboBox.getItems().setAll(engines);
                    if (isNewTable) {
                        // 新建表默认选 InnoDB（若可用）
                        engineComboBox.setValue(engines.contains("InnoDB") ? "InnoDB"
                                : (!engines.isEmpty() ? engines.get(0) : ""));
                    } else {
                        engineComboBox.setValue(options.getOrDefault("引擎", ""));
                    }

                    // 字符集（设置value会触发监听器自动填充排序规则下拉项）
                    charsetComboBox.getItems().setAll(charsets.keySet());
                    String charset = isNewTable ? "" : options.getOrDefault("字符集", "");
                    if (!charset.isEmpty()) {
                        charsetComboBox.setValue(charset);
                    } else if (isNewTable && charsets.containsKey("utf8mb4")) {
                        charsetComboBox.setValue("utf8mb4");
                    }

                    // 排序规则（监听器已填充下拉项，这里仅设置当前值）
                    String collation = isNewTable ? "" : options.getOrDefault("排序规则", "");
                    if (!collation.isEmpty()) {
                        collationComboBox.setValue(collation);
                    }

                    // 自增值
                    autoIncrementField.setText(options.getOrDefault("自增值", ""));

                    // 行格式
                    rowFormatComboBox.setValue(options.getOrDefault("行格式", ""));

                    // 平均行长
                    avgRowLengthField.setText(options.getOrDefault("平均行长", ""));

                    optionsLoaded = true;
                    optionsLoadingIndicator.setVisible(false);
                    engineComboBox.setDisable(false);
                    charsetComboBox.setDisable(false);
                    collationComboBox.setDisable(false);
                    statusLabel.setText(isNewTable ? "新建表选项已加载" : "表选项已加载");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    optionsLoadingIndicator.setVisible(false);
                    engineComboBox.setDisable(false);
                    charsetComboBox.setDisable(false);
                    collationComboBox.setDisable(false);
                    statusLabel.setText("加载表选项失败: " + e.getMessage());
                    e.printStackTrace();
                });
            } finally {
                connLock.unlock();
            }
        }, "DB-LoadOptions").start();
    }

    /**
     * 加载表注释
     */
    private void loadComment() {
        if (isNewTable) {
            commentTextArea.setText("");
            originalTableComment = "";
            commentLoaded = true;
            statusLabel.setText("新建表注释");
            return;
        }
        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, databaseName);
            connLock.lock();
            try {
                String comment = DatabaseService.getTableComment(config, databaseName, schemaName, tableName);
                Platform.runLater(() -> {
                    commentTextArea.setText(comment != null ? comment : "");
                    originalTableComment = comment != null ? comment : "";
                    commentLoaded = true;
                    statusLabel.setText("表注释已加载");
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("加载表注释失败: " + e.getMessage()));
            } finally {
                connLock.unlock();
            }
        }, "DB-LoadComment").start();
    }

    /**
     * 加载SQL预览（SHOW CREATE TABLE）
     */
    /**
     * 对比字段表原始快照与当前数据，生成ALTER SQL列表（字段增删改 + 主键变更）。
     * 纯数据计算，不访问JavaFX UI，可在任意线程执行。
     * @param titles 列标题
     * @param originalRows 原始行快照
     * @param currentRows 当前行数据
     * @return ALTER SQL列表（每条不带末尾分号）
     */
    private List<String> collectAlterStatements(List<String> titles,
                                                 List<ObservableList<String>> originalRows,
                                                 List<ObservableList<String>> currentRows) {
        List<String> sqlList = new ArrayList<>();
        reorderUnsupported = false;
        if (titles == null || tableName == null || tableName.isEmpty()) return sqlList;
        int nameIdx = titles.indexOf("字段名");
        int pkIdx = titles.indexOf("主键");
        if (nameIdx < 0) return sqlList;

        // 按字段名建立 original 索引（同名取第一个）
        java.util.Map<String, ObservableList<String>> originalByName = new java.util.LinkedHashMap<>();
        for (ObservableList<String> row : originalRows) {
            String n = nameIdx < row.size() ? row.get(nameIdx) : "";
            if (n != null && !n.isEmpty() && !originalByName.containsKey(n)) {
                originalByName.put(n, row);
            }
        }
        java.util.Map<String, ObservableList<String>> currentByName = new java.util.LinkedHashMap<>();
        for (ObservableList<String> row : currentRows) {
            String n = nameIdx < row.size() ? row.get(nameIdx) : "";
            if (n != null && !n.isEmpty() && !currentByName.containsKey(n)) {
                currentByName.put(n, row);
            }
        }

        // 1) 删除：原快照中存在但当前不存在
        for (java.util.Map.Entry<String, ObservableList<String>> e : originalByName.entrySet()) {
            if (!currentByName.containsKey(e.getKey())) {
                try {
                    sqlList.add(DatabaseService.generateDropColumnSql(config, databaseName, schemaName, tableName, e.getKey()));
                } catch (Exception ex) {
                    sqlList.add("-- 生成删除列SQL失败(" + e.getKey() + "): " + ex.getMessage());
                }
            }
        }

        // 构建原始/当前共同列名顺序列表（用于检测字段顺序变更）
        java.util.List<String> origCommonNames = new java.util.ArrayList<>();
        for (ObservableList<String> row : originalRows) {
            String n = nameIdx < row.size() ? row.get(nameIdx) : "";
            if (n != null && !n.isEmpty() && currentByName.containsKey(n)) {
                origCommonNames.add(n);
            }
        }
        java.util.List<String> curCommonNames = new java.util.ArrayList<>();
        for (ObservableList<String> row : currentRows) {
            String n = nameIdx < row.size() ? row.get(nameIdx) : "";
            if (n != null && !n.isEmpty() && originalByName.containsKey(n)) {
                curCommonNames.add(n);
            }
        }

        // 2) 新增 + 修改 + 顺序变更：按当前行顺序处理
        for (int i = 0; i < currentRows.size(); i++) {
            ObservableList<String> cur = currentRows.get(i);
            String colName = nameIdx < cur.size() ? cur.get(nameIdx) : "";
            if (colName == null || colName.isEmpty()) continue;
            if (!originalByName.containsKey(colName)) {
                // 新增列：取 UI 中当前行的前一个有效字段名作为 AFTER 锚点（仅 MySQL 生效），
                // 确保用户在已有字段中间插入新列时，新列按 UI 顺序插入而非被追加到表末尾。
                // 事务内按 currentRows 顺序执行：若前一个也是新增列，则先于本列被 ADD，AFTER 引用安全。
                String afterCol = null;
                for (int j = i - 1; j >= 0; j--) {
                    ObservableList<String> prev = currentRows.get(j);
                    String pn = nameIdx < prev.size() ? prev.get(nameIdx) : "";
                    if (pn != null && !pn.isEmpty()) {
                        afterCol = pn;
                        break;
                    }
                }
                try {
                    sqlList.add(DatabaseService.generateAddColumnSql(config, databaseName, schemaName, tableName, titles, cur, afterCol));
                } catch (Exception ex) {
                    sqlList.add("-- 生成新增列SQL失败(" + colName + "): " + ex.getMessage());
                }
            } else {
                ObservableList<String> orig = originalByName.get(colName);
                int origPos = origCommonNames.indexOf(colName);
                int curPos = curCommonNames.indexOf(colName);
                boolean positionChanged = (origPos != curPos);

                if (positionChanged && config.getType() == com.tangluobo.tomato.module.connect.ConnectType.MYSQL) {
                    // MySQL: MODIFY COLUMN ... AFTER/FIRST（含完整列定义，覆盖属性变更）
                    String afterCol = (curPos > 0) ? curCommonNames.get(curPos - 1) : null;
                    try {
                        String reorderSql = DatabaseService.generateReorderColumnSql(config, databaseName, schemaName, tableName, titles, cur, afterCol);
                        if (reorderSql != null) {
                            sqlList.add(reorderSql);
                        }
                    } catch (Exception ex) {
                        sqlList.add("-- 生成列顺序变更SQL失败(" + colName + "): " + ex.getMessage());
                    }
                } else {
                    if (positionChanged) {
                        reorderUnsupported = true;
                    }
                    // 常规属性变更
                    try {
                        sqlList.addAll(DatabaseService.generateModifyColumnSql(config, databaseName, schemaName, tableName, titles, orig, cur));
                    } catch (Exception ex) {
                        sqlList.add("-- 生成修改列SQL失败(" + colName + "): " + ex.getMessage());
                    }
                }
            }
        }

        // 3) 主键变更：对比原始主键集合与当前主键集合
        if (pkIdx >= 0) {
            java.util.List<String> origPk = new java.util.ArrayList<>();
            for (ObservableList<String> row : originalRows) {
                String n = nameIdx < row.size() ? row.get(nameIdx) : "";
                String isPk = pkIdx < row.size() ? row.get(pkIdx) : "";
                if ("是".equals(isPk) && n != null && !n.isEmpty()) origPk.add(n);
            }
            java.util.List<String> curPk = new java.util.ArrayList<>();
            for (ObservableList<String> row : currentRows) {
                String n = nameIdx < row.size() ? row.get(nameIdx) : "";
                String isPk = pkIdx < row.size() ? row.get(pkIdx) : "";
                if ("是".equals(isPk) && n != null && !n.isEmpty()) curPk.add(n);
            }
            boolean pkChanged = !origPk.equals(curPk);
            if (pkChanged) {
                String tableRef = buildTableRef();
                if (!origPk.isEmpty()) {
                    sqlList.add("ALTER TABLE " + tableRef + " DROP PRIMARY KEY");
                }
                if (!curPk.isEmpty()) {
                    StringBuilder pkSql = new StringBuilder("ALTER TABLE ").append(tableRef).append(" ADD PRIMARY KEY (");
                    for (int i = 0; i < curPk.size(); i++) {
                        if (i > 0) pkSql.append(", ");
                        if (config.getType() == com.tangluobo.tomato.module.connect.ConnectType.MYSQL) {
                            pkSql.append("`").append(curPk.get(i)).append("`");
                        } else {
                            pkSql.append("\"").append(curPk.get(i)).append("\"");
                        }
                    }
                    pkSql.append(")");
                    sqlList.add(pkSql.toString());
                }
            }
        }
        return sqlList;
    }

    /**
     * 构造表引用字符串（MySQL用`db`.`table`，PG/Oracle用"schema"."table"）
     */
    private String buildTableRef() {
        String schema = schemaName != null ? schemaName : databaseName;
        return switch (config.getType()) {
            case MYSQL -> "`" + databaseName + "`.`" + tableName + "`";
            case POSTGRESQL -> "\"" + schema + "\".\"" + tableName + "\"";
            case ORACLE -> "\"" + databaseName + "\".\"" + tableName + "\"";
            default -> tableName;
        };
    }

    private void loadSqlPreview() {
        sqlPreviewViewer.setText("-- 加载中...");
        // 新建表模式始终生成CREATE TABLE预览
        boolean isSaveAs = isNewTable || "另存为".equals(sqlPreviewModeBox.getSelectionModel().getSelectedItem());

        // 在FX线程采集当前字段表数据快照（避免后台线程访问JavaFX集合）
        List<String> titles = columnTitles != null ? new ArrayList<>(columnTitles) : new ArrayList<>();
        List<ObservableList<String>> currentRows = new ArrayList<>();
        if (tableView.getItems() != null) {
            for (ObservableList<String> r : tableView.getItems()) {
                currentRows.add(FXCollections.observableArrayList(r));
            }
        }
        List<ObservableList<String>> originalRows = new ArrayList<>();
        for (ObservableList<String> r : originalColumnsSnapshot) {
            originalRows.add(FXCollections.observableArrayList(r));
        }
        int fieldCount = currentRows.size();
        String tableComment = commentLoaded ? commentTextArea.getText() : null;
        String originalTc = originalTableComment;

        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, databaseName);
            connLock.lock();
            try {
                if (isSaveAs) {
                    // 另存为模式：显示完整的CREATE TABLE DDL
                    String result;
                    if (isNewTable) {
                        // 新建表模式：根据当前字段表格内容生成CREATE TABLE SQL
                        List<Map<String, String>> cols = collectColumnsForCreate();
                        if (cols.isEmpty()) {
                            result = "-- 请先添加至少一个字段（字段名不能为空）";
                        } else {
                            List<String> validationErrors = validateColumnsForCreate();
                            if (!validationErrors.isEmpty()) {
                                StringBuilder sb = new StringBuilder("-- 字段设置不完整，请检查：\n");
                                for (String err : validationErrors) {
                                    sb.append("-- ").append(err).append("\n");
                                }
                                result = sb.toString();
                            } else {
                                Map<String, String> opts = collectOptionsForCreate();
                                String cmt = commentLoaded ? tableComment : null;
                                result = DatabaseService.generateCreateTableSql(config, databaseName, schemaName, "新表名", cols, opts, cmt);
                            }
                        }
                    } else {
                        String ddl = DatabaseService.getTableDdl(config, databaseName, schemaName, tableName);
                        result = ddl != null && !ddl.isEmpty() ? ddl : "-- 无法获取CREATE TABLE DDL";
                    }
                    final String r = result;
                    Platform.runLater(() -> {
                        sqlPreviewViewer.setText(r);
                        sqlPreviewLoaded = true;
                        statusLabel.setText("共 " + fieldCount + " 个字段");
                    });
                    return;
                }

                // 保存模式：显示ALTER语句（字段增删改 + 主键 + 表注释）
                List<String> alterStatements = new ArrayList<>();
                try {
                    alterStatements.addAll(collectAlterStatements(titles, originalRows, currentRows));
                } catch (Exception e) {
                    alterStatements.add("-- 生成字段变更SQL失败: " + e.getMessage());
                }

                // 表注释变更
                if (commentLoaded) {
                    String original = originalTc != null ? originalTc : "";
                    if (!original.equals(tableComment != null ? tableComment : "")) {
                        try {
                            alterStatements.add(DatabaseService.generateUpdateTableCommentSql(config, databaseName, schemaName, tableName, tableComment));
                        } catch (Exception e) {
                            alterStatements.add("-- 生成表注释SQL失败: " + e.getMessage());
                        }
                    }
                }

                StringBuilder preview = new StringBuilder();
                for (String sql : alterStatements) {
                    preview.append(sql).append(";\n");
                }

                String result = preview.length() > 0 ? preview.toString() : "-- 无变更";
                Platform.runLater(() -> {
                    sqlPreviewViewer.setText(result);
                    sqlPreviewLoaded = true;
                    statusLabel.setText("共 " + fieldCount + " 个字段");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    sqlPreviewViewer.setText("-- 加载失败: " + e.getMessage());
                    statusLabel.setText("加载SQL预览失败: " + e.getMessage());
                });
            } finally {
                connLock.unlock();
            }
        }, "DB-LoadSqlPreview").start();
    }

    /**
     * 填充只读信息表格（索引/外键/触发器通用方法）
     * @param tv 目标TableView
     * @param data 数据列表（每个元素为属性Map）
     * @param columnTitles 列标题顺序
     * @param columnWidths 列宽映射
     */
    private void populateInfoTable(TableView<ObservableList<String>> tv, List<Map<String, String>> data,
                                    List<String> columnTitles, Map<String, Integer> columnWidths) {
        tv.getColumns().clear();
        tv.getItems().clear();
        if (data.isEmpty()) return;

        for (int i = 0; i < columnTitles.size(); i++) {
            final int colIndex = i;
            String title = columnTitles.get(i);
            TableColumn<ObservableList<String>, String> col = new TableColumn<>(title);
            col.setPrefWidth(columnWidths.getOrDefault(title, 100));
            col.setMinWidth(50);
            col.setCellValueFactory(param -> {
                ObservableList<String> row = param.getValue();
                if (colIndex < row.size()) {
                    return new SimpleStringProperty(row.get(colIndex));
                }
                return new SimpleStringProperty("");
            });
            col.setCellFactory(tc -> new TableCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setStyle("");
                    } else {
                        setText(item);
                        setStyle("-fx-alignment: CENTER_LEFT;");
                    }
                }
            });
            tv.getColumns().add(col);
        }

        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
        for (Map<String, String> map : data) {
            ObservableList<String> row = FXCollections.observableArrayList();
            for (String title : columnTitles) {
                row.add(map.getOrDefault(title, ""));
            }
            rows.add(row);
        }
        tv.setItems(rows);
    }

    // ====== 工具栏动作处理（占位实现，后续对接业务逻辑） ======

    private void handleSave() {
        if (isNewTable) {
            handleCreateNewTable();
            return;
        }

        // 在FX线程采集当前字段表数据快照
        List<String> titles = columnTitles != null ? new ArrayList<>(columnTitles) : new ArrayList<>();
        List<ObservableList<String>> currentRows = new ArrayList<>();
        if (tableView.getItems() != null) {
            for (ObservableList<String> r : tableView.getItems()) {
                currentRows.add(FXCollections.observableArrayList(r));
            }
        }
        List<ObservableList<String>> originalRows = new ArrayList<>();
        for (ObservableList<String> r : originalColumnsSnapshot) {
            originalRows.add(FXCollections.observableArrayList(r));
        }
        String tableComment = commentLoaded ? commentTextArea.getText() : null;
        String originalTc = originalTableComment != null ? originalTableComment : "";
        boolean tableCommentChanged = commentLoaded && !originalTc.equals(tableComment != null ? tableComment : "");

        // 生成ALTER语句（字段增删改 + 主键）
        List<String> alterStatements = new ArrayList<>();
        try {
            alterStatements.addAll(collectAlterStatements(titles, originalRows, currentRows));
        } catch (Exception e) {
            statusLabel.setText("生成变更SQL失败: " + e.getMessage());
            return;
        }
        // 表注释SQL
        String tableCommentSql = null;
        if (tableCommentChanged) {
            try {
                tableCommentSql = DatabaseService.generateUpdateTableCommentSql(config, databaseName, schemaName, tableName, tableComment);
            } catch (Exception e) {
                statusLabel.setText("生成表注释SQL失败: " + e.getMessage());
                return;
            }
        }

        if (alterStatements.isEmpty() && tableCommentSql == null) {
            if (reorderUnsupported) {
                String dbType = config.getType().toString();
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("保存表结构");
                alert.setHeaderText("当前数据库类型不支持直接调整字段顺序");
                alert.setContentText(dbType + " 不支持通过 ALTER TABLE 调整列顺序，如需调整需重建表。");
                DialogPositionUtil.centerOnOwner(alert, this);
                alert.showAndWait();
                statusLabel.setText(dbType + " 不支持调整字段顺序");
            } else {
                statusLabel.setText("没有需要保存的变更");
            }
            return;
        }

        statusLabel.setText("正在保存变更（" + (alterStatements.size() + (tableCommentSql != null ? 1 : 0)) + " 条SQL，事务模式）...");
        String finalTableCommentSql = tableCommentSql;
        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, databaseName);
            connLock.lock();
            try {
                // 收集所有SQL到同一事务执行：任一失败则自动回滚，DB状态保持与保存前一致，
                // 因此失败时无需刷新表结构，可保留用户当前编辑内容，允许在原有基础上修改后重试。
                List<String> allSqls = new ArrayList<>(alterStatements);
                if (finalTableCommentSql != null) allSqls.add(finalTableCommentSql);

                try {
                    DatabaseService.executeDdlsInTransaction(config, databaseName, allSqls);
                    Platform.runLater(() -> {
                        // 更新原始注释缓存（兼容旧逻辑）
                        int commentIdx = columnTitles != null ? columnTitles.indexOf("注释") : -1;
                        int nameIdx = columnTitles != null ? columnTitles.indexOf("字段名") : -1;
                        if (commentIdx >= 0 && nameIdx >= 0 && tableView.getItems() != null) {
                            for (ObservableList<String> row : tableView.getItems()) {
                                String colName = nameIdx < row.size() ? row.get(nameIdx) : "";
                                String comment = commentIdx < row.size() ? row.get(commentIdx) : "";
                                originalColumnComments.put(colName, comment != null ? comment : "");
                            }
                        }
                        if (tableCommentChanged) {
                            originalTableComment = tableComment != null ? tableComment : "";
                        }
                        // 清除脏状态（重建快照、去星号、刷新预览）
                        clearDirty();
                        if (sqlPreviewLoaded) loadSqlPreview();
                        statusLabel.setText("变更已保存");
                    });
                } catch (Exception e) {
                    // 事务已回滚，DB状态与保存前完全一致，无需刷新表结构。
                    // 不调用 clearDirty()/loadStructure()，保留用户编辑内容以便修改后重试。
                    Platform.runLater(() -> {
                        statusLabel.setText("保存失败（已回滚，可在原编辑基础上修改后重试）");
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("保存表结构");
                        alert.setHeaderText("保存失败，所有变更已回滚（DB未发生变更）");
                        alert.setContentText(e.getMessage());
                        DialogPositionUtil.centerOnOwner(alert, this);
                        alert.showAndWait();
                    });
                }
            } finally {
                connLock.unlock();
            }
        }, "DB-SaveAlter").start();
    }

    /**
     * 新建表保存：弹出表名输入对话框，收集字段数据生成CREATE TABLE并执行
     */
    private void handleCreateNewTable() {
        List<Map<String, String>> columns = collectColumnsForCreate();
        if (columns.isEmpty()) {
            statusLabel.setText("请先添加至少一个字段（字段名不能为空）");
            return;
        }

        // 验证字段完整性（类型必填、需要长度的类型是否已指定长度）
        List<String> validationErrors = validateColumnsForCreate();
        if (!validationErrors.isEmpty()) {
            statusLabel.setText("字段设置不完整: " + String.join("; ", validationErrors));
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("新建表");
            alert.setHeaderText("字段设置不完整，请检查以下问题");
            alert.setContentText(String.join("\n", validationErrors));
            DialogPositionUtil.centerOnOwner(alert, this);
            alert.showAndWait();
            return;
        }

        // 弹出表名输入对话框
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("新建表");
        dialog.setHeaderText("请输入表名");
        dialog.setContentText("表名:");
        DialogPositionUtil.centerOnOwner(dialog, this);
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) return;

        String newTableName = result.get().trim();
        if (newTableName.isEmpty()) {
            statusLabel.setText("表名不能为空");
            return;
        }

        Map<String, String> options = collectOptionsForCreate();
        String tableComment = commentLoaded ? commentTextArea.getText() : null;

        statusLabel.setText("正在创建表 " + newTableName + "...");
        String finalNewTableName = newTableName;
        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, databaseName);
            connLock.lock();
            try {
                try {
                    DatabaseService.createTable(config, databaseName, schemaName, finalNewTableName, columns, options, tableComment);
                    Platform.runLater(() -> {
                        // 切换为正常设计表模式
                        tableName = finalNewTableName;
                        isNewTable = false;
                        indexesLoaded = false;
                        foreignKeysLoaded = false;
                        triggersLoaded = false;
                        optionsLoaded = false;
                        commentLoaded = false;
                        sqlPreviewLoaded = false;
                        loadStructure();
                        statusLabel.setText("表 " + finalNewTableName + " 创建成功");
                        // 通知 ConnectModule 更新 tab 标题/userData 并刷新表树
                        if (onTableCreated != null) {
                            onTableCreated.accept(finalNewTableName);
                        }
                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
                        alert.setTitle("新建表");
                        alert.setHeaderText(null);
                        alert.setContentText("表 " + finalNewTableName + " 创建成功");
                        DialogPositionUtil.centerOnOwner(alert, this);
                        alert.showAndWait();
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        statusLabel.setText("创建表失败: " + e.getMessage());
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("新建表");
                        alert.setHeaderText("创建表失败");
                        alert.setContentText(e.getMessage());
                        DialogPositionUtil.centerOnOwner(alert, this);
                        alert.showAndWait();
                    });
                }
            } finally {
                connLock.unlock();
            }
        }, "DB-CreateTable").start();
    }

    /**
     * 收集字段表格数据为List<Map>（用于生成CREATE TABLE SQL），仅包含字段名非空的行
     */
    private List<Map<String, String>> collectColumnsForCreate() {
        List<Map<String, String>> columns = new ArrayList<>();
        if (columnTitles != null && tableView.getItems() != null) {
            int nameIdx = columnTitles.indexOf("字段名");
            if (nameIdx >= 0) {
                for (ObservableList<String> row : tableView.getItems()) {
                    String colName = nameIdx < row.size() ? row.get(nameIdx) : "";
                    if (colName != null && !colName.trim().isEmpty()) {
                        Map<String, String> col = new LinkedHashMap<>();
                        for (int i = 0; i < columnTitles.size(); i++) {
                            col.put(columnTitles.get(i), i < row.size() ? row.get(i) : "");
                        }
                        columns.add(col);
                    }
                }
            }
        }
        return columns;
    }

    /**
     * 收集表选项（引擎、字符集、排序规则），仅当选项标签页已加载时
     */
    private Map<String, String> collectOptionsForCreate() {
        Map<String, String> options = new LinkedHashMap<>();
        if (optionsLoaded) {
            String engine = engineComboBox.getValue();
            if (engine != null && !engine.isEmpty()) options.put("引擎", engine);
            String charset = charsetComboBox.getValue();
            if (charset != null && !charset.isEmpty()) options.put("字符集", charset);
            String collation = collationComboBox.getValue();
            if (collation != null && !collation.isEmpty()) options.put("排序规则", collation);
        }
        return options;
    }

    /**
     * 验证字段完整性（用于新建表前的检查），返回错误信息列表（空列表表示通过）
     * 检查：类型必填、需要长度的类型是否已指定长度
     */
    private List<String> validateColumnsForCreate() {
        List<String> errors = new ArrayList<>();
        if (columnTitles == null || tableView.getItems() == null) return errors;
        int nameIdx = columnTitles.indexOf("字段名");
        int typeIdx = columnTitles.indexOf("类型");
        int lenIdx = columnTitles.indexOf("长度");
        int decIdx = columnTitles.indexOf("小数点");
        for (int i = 0; i < tableView.getItems().size(); i++) {
            ObservableList<String> row = tableView.getItems().get(i);
            String colName = nameIdx >= 0 && nameIdx < row.size() ? row.get(nameIdx) : "";
            if (colName == null || colName.trim().isEmpty()) continue;

            String type = typeIdx >= 0 && typeIdx < row.size() ? row.get(typeIdx) : "";
            if (type == null || type.trim().isEmpty()) {
                errors.add("第" + (i + 1) + "行字段\"" + colName + "\"：未设置类型");
                continue;
            }
            String length = lenIdx >= 0 && lenIdx < row.size() ? row.get(lenIdx) : "";
            if (needsLength(type) && (length == null || length.trim().isEmpty())) {
                errors.add("第" + (i + 1) + "行字段\"" + colName + "\"：类型\"" + type + "\"需要指定长度");
            }
            String decimal = decIdx >= 0 && decIdx < row.size() ? row.get(decIdx) : "";
            if (DatabaseService.needsDecimalPlaces(type) && (decimal == null || decimal.trim().isEmpty())) {
                errors.add("第" + (i + 1) + "行字段\"" + colName + "\"：类型\"" + type + "\"需要指定小数点");
            }
        }
        return errors;
    }

    /** 判断类型是否需要指定长度 */
    private boolean needsLength(String type) {
        if (type == null) return false;
        String t = type.toLowerCase();
        return t.contains("varchar") || t.contains("char") || t.contains("decimal")
                || t.contains("numeric") || t.contains("varbinary") || t.contains("binary")
                || t.contains("bit");
    }

    /**
     * 在表格末尾追加一个空字段行，返回新行索引。
     * 不改变 selection（供方向键导航使用，避免 select(整行) 干扰目标单元格选中）。
     * @return 新行索引；若无法追加返回 -1
     */
    private int appendEmptyRow() {
        ObservableList<ObservableList<String>> items = tableView.getItems();
        if (items.isEmpty() && !isNewTable) {
            statusLabel.setText("请先加载表结构");
            return -1;
        }
        ObservableList<String> newRow = FXCollections.observableArrayList();
        for (int i = 0; i < dataColumnCount; i++) {
            newRow.add("");
        }
        items.add(newRow);
        statusLabel.setText("已添加字段行（未保存）");
        markDirty();
        return items.size() - 1;
    }

    private void handleAddField() {
        int newIndex = appendEmptyRow();
        if (newIndex < 0) return;
        selectNewRowCell(newIndex);
    }

    /**
     * 编辑状态下按方向键在单元格间切换：
     * - UP/DOWN：同列上下移动；DOWN 到最后一行时追加新行并切入新行同列
     * - LEFT/RIGHT：同行左右移动，跳过非可编辑列（行选择器、主键、非空、只读列）
     * 必须在提交当前编辑之前计算目标（提交后 tableView.getEditingCell() 为 null），
     * 通过 runLater 在提交完成后再进入目标单元格编辑。
     */
    private void navigateFromEditCell(int deltaRow, int deltaCol) {
        TablePosition<?, ?> editPos = tableView.getEditingCell();
        if (editPos == null) return;
        int curRow = editPos.getRow();
        int curCol = editPos.getColumn();
        ObservableList<ObservableList<String>> items = tableView.getItems();
        if (items == null || items.isEmpty()) return;

        // 计算目标列
        TableColumn<ObservableList<String>, String> targetCol;
        if (deltaCol != 0) {
            targetCol = findNavigableColumn(curCol, deltaCol > 0);
            if (targetCol == null) return; // 该方向无更多可编辑列
        } else {
            @SuppressWarnings("unchecked")
            TableColumn<ObservableList<String>, String> c =
                    (TableColumn<ObservableList<String>, String>) tableView.getColumns().get(curCol);
            targetCol = c;
        }

        // 计算目标行
        int targetRow = curRow;
        if (deltaRow > 0) {
            int lastRow = items.size() - 1;
            if (curRow >= lastRow) {
                // 最后一行按向下：追加新行（不在此处选中整行，由后续 runLater 统一选中目标单元格，
                // 否则在 cellSelectionEnabled 模式下 select(整行) 会瞬间高亮整行所有 cell，
                // 视觉上表现为"光标跳到右下角/右上角"的乱跑现象）
                targetRow = appendEmptyRow();
                if (targetRow < 0) return;
            } else {
                targetRow = curRow + 1;
            }
        } else if (deltaRow < 0) {
            if (curRow <= 0) return; // 第一行无法上移
            targetRow = curRow - 1;
        }

        final int finalTargetRow = targetRow;
        final TableColumn<ObservableList<String>, String> finalTargetCol = targetCol;
        Platform.runLater(() -> {
            // 单元格选择模式下，选中目标单元格（而非整行），与非编辑状态的导航行为一致
            tableView.getSelectionModel().clearSelection();
            tableView.getSelectionModel().select(finalTargetRow, finalTargetCol);
            tableView.getFocusModel().focus(finalTargetRow, finalTargetCol);
            // 滚动到目标行，确保新行（尤其追加的末尾行）可见，避免目标行在视口外
            tableView.scrollTo(finalTargetRow);
            tableView.edit(finalTargetRow, finalTargetCol);
        });
    }

    /**
     * 从 fromIndex 起（不含）向前/向后查找下一个可编辑列（字段名、类型、长度、小数点、注释），
     * 跳过行选择器列（userData 为 String）及其他只读列。
     */
    private TableColumn<ObservableList<String>, String> findNavigableColumn(int fromIndex, boolean forward) {
        ObservableList<TableColumn<ObservableList<String>, ?>> columns = tableView.getColumns();
        int size = columns.size();
        int step = forward ? 1 : -1;
        for (int i = fromIndex + step; i >= 0 && i < size; i += step) {
            TableColumn<ObservableList<String>, ?> col = columns.get(i);
            if (isNavigableColumn(col)) {
                @SuppressWarnings("unchecked")
                TableColumn<ObservableList<String>, String> typed =
                        (TableColumn<ObservableList<String>, String>) col;
                return typed;
            }
        }
        return null;
    }

    private boolean isNavigableColumn(TableColumn<?, ?> col) {
        // 行选择器列的 userData 为 String（ROW_SELECTOR_COL），跳过
        if (!(col.getUserData() instanceof Integer)) return false;
        String title = col.getText();
        return "字段名".equals(title) || "类型".equals(title) || "长度".equals(title)
                || "小数点".equals(title) || "注释".equals(title);
    }

    /** 返回第一个可编辑列（字段名/类型/长度/小数点/注释）的 TableColumn 对象，未找到返回 null */
    private TableColumn<ObservableList<String>, String> getFirstEditableColumn() {
        for (TableColumn<ObservableList<String>, ?> col : tableView.getColumns()) {
            if (isNavigableColumn(col)) {
                @SuppressWarnings("unchecked")
                TableColumn<ObservableList<String>, String> typed =
                        (TableColumn<ObservableList<String>, String>) col;
                return typed;
            }
        }
        return null;
    }

    /**
     * 选中新行（添加/插入）的第一个可编辑单元格并聚焦。
     * 必须使用 select(row, col) 选中单个单元格：cellSelectionEnabled 模式下 select(int) 会
     * 选中整行所有 cell 且使 focusModel.focusedCell.column 变为 -1，
     * 随后按方向键时 eventFilter 会读到 column=-1 走 findFirstNavigableColumn() 兜底，
     * 导致目标列错位（视觉上"光标乱跑"）。
     */
    private void selectNewRowCell(int rowIndex) {
        TableColumn<ObservableList<String>, String> col = getFirstEditableColumn();
        tableView.getSelectionModel().clearSelection();
        if (col != null) {
            tableView.getSelectionModel().select(rowIndex, col);
            tableView.getFocusModel().focus(rowIndex, col);
        } else {
            tableView.getSelectionModel().select(rowIndex);
        }
        tableView.scrollTo(rowIndex);
    }

    /** 清除当前编辑回调（在编辑结束/取消时调用，避免后续误用） */
    private void clearEditCommitCallback() {
        currentEditCommit = null;
        skipArrowNavigation = null;
    }

    private void handleInsertField() {
        // 在选中行之前插入一个空字段行
        ObservableList<ObservableList<String>> items = tableView.getItems();
        if (items.isEmpty()) {
            handleAddField();
            return;
        }
        ObservableList<String> selected = tableView.getSelectionModel().getSelectedItem();
        int insertIndex = selected != null ? items.indexOf(selected) : items.size();
        ObservableList<String> newRow = FXCollections.observableArrayList();
        for (int i = 0; i < dataColumnCount; i++) {
            newRow.add("");
        }
        items.add(insertIndex, newRow);
        statusLabel.setText("已插入字段行（未保存）");
        markDirty();
        selectNewRowCell(insertIndex);
    }

    private void handleTogglePrimaryKey() {
        // 先取消正在编辑的单元格（cancelEdit会保留编辑值到数据模型，避免refresh丢失输入）
        if (tableView.getEditingCell() != null) {
            tableView.edit(-1, null);
        }
        ObservableList<String> selected = tableView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("请先选择一个字段");
            return;
        }
        int pkColIndex = findColumnIndexByTitle("主键");
        if (pkColIndex < 0 || pkColIndex >= selected.size()) {
            statusLabel.setText("未找到主键列");
            return;
        }
        String current = selected.get(pkColIndex);
        selected.set(pkColIndex, "是".equals(current) ? "否" : "是");
        tableView.refresh();
        updateFieldPropertiesPane();
        statusLabel.setText("已切换主键（未保存）");
        markDirty();
    }

    private void handleMoveUp() {
        ObservableList<ObservableList<String>> items = tableView.getItems();
        ObservableList<String> selected = tableView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("请先选择一个字段");
            return;
        }
        int index = items.indexOf(selected);
        if (index <= 0) {
            statusLabel.setText("已在顶部");
            return;
        }
        items.remove(index);
        items.add(index - 1, selected);
        tableView.getSelectionModel().clearSelection();
        tableView.getSelectionModel().select(index - 1);
        statusLabel.setText("已上移字段（未保存）");
        markDirty();
    }

    private void handleMoveDown() {
        ObservableList<ObservableList<String>> items = tableView.getItems();
        ObservableList<String> selected = tableView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            statusLabel.setText("请先选择一个字段");
            return;
        }
        int index = items.indexOf(selected);
        if (index < 0 || index >= items.size() - 1) {
            statusLabel.setText("已在底部");
            return;
        }
        items.remove(index);
        items.add(index + 1, selected);
        tableView.getSelectionModel().clearSelection();
        tableView.getSelectionModel().select(index + 1);
        statusLabel.setText("已下移字段（未保存）");
        markDirty();
    }

    private void handleDeleteField() {
        ObservableList<ObservableList<String>> items = tableView.getItems();
        if (items.isEmpty()) {
            statusLabel.setText("请先加载表结构");
            return;
        }
        List<Integer> selectedIndices = new ArrayList<>(tableView.getSelectionModel().getSelectedIndices());
        if (selectedIndices.isEmpty()) {
            statusLabel.setText("请先选择要删除的字段");
            return;
        }
        selectedIndices.sort(Collections.reverseOrder());
        for (int index : selectedIndices) {
            items.remove(index);
        }
        int count = selectedIndices.size();
        tableView.getSelectionModel().clearSelection();
        statusLabel.setText("已删除 " + count + " 个字段（未保存）");
        markDirty();
    }

    /**
     * 注册Scene加速器处理Ctrl+C/Ctrl+V。
     * Scene加速器在所有事件分发之前触发，确保ComboBox内TextField不会拦截快捷键。
     * 通过当前选中的Tab定位TableStructureView，不依赖焦点判断，
     * 避免多Tab下焦点不在表格内时复制/粘贴失效。
     */
    private void setupCopyPasteAccelerators() {
        final KeyCombination copyCombo = KeyCombination.keyCombination("Ctrl+C");
        final KeyCombination pasteCombo = KeyCombination.keyCombination("Ctrl+V");

        // 在Scene上注册加速器：捕获scene引用，回调时通过选中Tab定位结构视图
        java.util.function.Consumer<Scene> registerOnScene = scene -> {
            scene.getAccelerators().put(copyCombo, () -> handleAcceleratorCopy(scene));
            scene.getAccelerators().put(pasteCombo, () -> handleAcceleratorPaste(scene));
        };

        tableView.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                registerOnScene.accept(newScene);
            }
        });

        Scene scene = tableView.getScene();
        if (scene != null) {
            registerOnScene.accept(scene);
        }

        // Ctrl+S 保存表结构（事件过滤器，仅在本视图子树内触发，不影响SQL编辑器等其他Tab的Ctrl+S）
        final KeyCombination saveCombo = KeyCombination.keyCombination("Ctrl+S");
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (saveCombo.match(event)) {
                event.consume();
                handleSave();
            }
        });
    }

    /** Ctrl+C加速器处理：优先复制TextInputControl选中文本，否则复制当前选中Tab中表格的选中行 */
    private void handleAcceleratorCopy(Scene scene) {
        if (scene == null) return;
        Node focusOwner = scene.getFocusOwner();
        // 1. 焦点在文本输入组件且有选中文本 → 复制文本
        if (focusOwner instanceof TextInputControl tic) {
            String selected = tic.getSelectedText();
            if (selected != null && !selected.isEmpty()) {
                ClipboardContent content = new ClipboardContent();
                content.putString(selected);
                Clipboard.getSystemClipboard().setContent(content);
                // 清除活动结构视图的字段缓存，避免粘贴时误用
                TableStructureView activeView = findActiveStructureView(scene);
                if (activeView != null) activeView.copiedFieldsJson = null;
                return;
            }
        }
        // 2. 获取当前选中Tab中的TableStructureView，复制其选中行（不依赖焦点判断）
        TableStructureView activeView = findActiveStructureView(scene);
        if (activeView != null
            && !activeView.tableView.getSelectionModel().getSelectedIndices().isEmpty()
            && activeView.tableView.getEditingCell() == null) {
            activeView.handleCopyFields();
        }
    }

    /** Ctrl+V加速器处理：焦点在TextInputControl时粘贴文本，否则粘贴到当前选中Tab的表格 */
    private void handleAcceleratorPaste(Scene scene) {
        if (scene == null) return;
        Node focusOwner = scene.getFocusOwner();
        // 1. 焦点在文本输入组件 → 粘贴文本
        if (focusOwner instanceof TextInputControl tic) {
            String text = Clipboard.getSystemClipboard().getString();
            if (text != null) {
                tic.replaceSelection(text);
            }
            return;
        }
        // 2. 否则 → 粘贴到当前选中Tab的TableStructureView
        TableStructureView activeView = findActiveStructureView(scene);
        if (activeView != null && activeView.tableView.getEditingCell() == null) {
            activeView.handlePasteFields();
        }
    }

    /**
     * 查找当前选中Tab中的TableStructureView。
     * 不依赖焦点，而是通过TabPane的选中状态确定用户当前操作的结构视图，
     * 避免多Tab下焦点不在表格内时复制/粘贴失效。
     */
    private TableStructureView findActiveStructureView(Scene scene) {
        if (scene == null || scene.getRoot() == null) return null;
        for (Node n : scene.getRoot().lookupAll(".tab-pane")) {
            if (n instanceof TabPane tp) {
                Tab selectedTab = tp.getSelectionModel().getSelectedItem();
                if (selectedTab != null && selectedTab.getContent() instanceof TableStructureView tsv) {
                    return tsv;
                }
            }
        }
        return null;
    }

    /**
     * 复制选中字段到系统剪贴板（JSON格式，支持跨表粘贴）
     */
    private void handleCopyFields() {
        System.out.println("[TableStructureView] handleCopyFields called");
        try {
            if (columnTitles == null) {
                statusLabel.setText("表结构未加载");
                return;
            }
            ObservableList<ObservableList<String>> items = tableView.getItems();
            List<Integer> selectedIndices = new ArrayList<>(tableView.getSelectionModel().getSelectedIndices());
            // 选中为空时回退到焦点行（避免ComboBox获得焦点时行选择丢失导致Ctrl+C无效）
            if (selectedIndices.isEmpty()) {
                int focusedRow = tableView.getFocusModel().getFocusedIndex();
                if (focusedRow >= 0 && focusedRow < items.size()) {
                    selectedIndices.add(focusedRow);
                }
            }
            if (selectedIndices.isEmpty()) {
                statusLabel.setText("请先选择要复制的字段");
                return;
            }
            Collections.sort(selectedIndices);
            // 将选中行转换为 List<Map<String,String>>（列标题 -> 值）
            List<Map<String, String>> copiedRows = new ArrayList<>();
            for (int idx : selectedIndices) {
                if (idx < 0 || idx >= items.size()) continue;
                ObservableList<String> row = items.get(idx);
                Map<String, String> rowMap = new LinkedHashMap<>();
                for (int c = 0; c < columnTitles.size() && c < row.size(); c++) {
                    rowMap.put(columnTitles.get(c), row.get(c) != null ? row.get(c) : "");
                }
                copiedRows.add(rowMap);
            }
            if (copiedRows.isEmpty()) {
                statusLabel.setText("无可复制的字段");
                return;
            }
            // 序列化为JSON并存入剪贴板
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().create();
            String json = gson.toJson(copiedRows);
            copiedFieldsJson = json; // 缓存到成员变量，防止剪贴板被默认行为覆盖
            ClipboardContent content = new ClipboardContent();
            content.putString(json);
            Clipboard.getSystemClipboard().setContent(content);
            statusLabel.setText("已复制 " + copiedRows.size() + " 个字段到剪贴板");
        } catch (Exception e) {
            e.printStackTrace();
            statusLabel.setText("复制失败: " + e.getMessage());
        }
    }

    /**
     * 从系统剪贴板粘贴字段到表格（支持跨表粘贴，按列标题匹配）
     */
    private void handlePasteFields() {
        try {
            if (columnTitles == null) {
                statusLabel.setText("表结构未加载");
                return;
            }
            ObservableList<ObservableList<String>> items = tableView.getItems();
            if (items.isEmpty() && !isNewTable) {
                statusLabel.setText("请先加载表结构");
                return;
            }
            // 先取消正在编辑的单元格
            if (tableView.getEditingCell() != null) {
                tableView.edit(-1, null);
            }
            // 优先从成员变量读取（避免JavaFX默认复制行为覆盖剪贴板），其次从剪贴板读取
            String json = copiedFieldsJson;
            if (json == null || json.trim().isEmpty()) {
                json = Clipboard.getSystemClipboard().getString();
            }
            if (json == null || json.trim().isEmpty()) {
                statusLabel.setText("剪贴板无内容");
                return;
            }
            List<Map<String, String>> copiedRows;
            com.google.gson.Gson gson = new com.google.gson.Gson();
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<List<Map<String, String>>>() {}.getType();
            copiedRows = gson.fromJson(json, type);
            if (copiedRows == null || copiedRows.isEmpty()) {
                statusLabel.setText("剪贴板中无可粘贴的字段");
                return;
            }
            // 确定插入位置：选中行之后，否则末尾
            ObservableList<String> selected = tableView.getSelectionModel().getSelectedItem();
            int insertIndex = selected != null ? items.indexOf(selected) + 1 : items.size();
            if (insertIndex < 0 || insertIndex > items.size()) insertIndex = items.size();

            int pastedCount = 0;
            for (Map<String, String> rowMap : copiedRows) {
                ObservableList<String> newRow = FXCollections.observableArrayList();
                for (int c = 0; c < dataColumnCount; c++) {
                    String title = c < columnTitles.size() ? columnTitles.get(c) : null;
                    String val = title != null ? rowMap.getOrDefault(title, "") : "";
                    newRow.add(val != null ? val : "");
                }
                items.add(insertIndex + pastedCount, newRow);
                pastedCount++;
            }
            // 选中新粘贴的行
            tableView.getSelectionModel().clearSelection();
            for (int i = 0; i < pastedCount; i++) {
                tableView.getSelectionModel().select(insertIndex + i);
            }
            tableView.refresh();
            statusLabel.setText("已粘贴 " + pastedCount + " 个字段（未保存）");
            markDirty();
        } catch (Exception e) {
            e.printStackTrace();
            statusLabel.setText("粘贴失败: " + e.getMessage());
        }
    }

    /**
     * 根据列标题查找列在数据模型中的索引（跳过行选择器列）
     */
    private int findColumnIndexByTitle(String title) {
        for (TableColumn<ObservableList<String>, ?> col : tableView.getColumns()) {
            if (ROW_SELECTOR_COL.equals(col.getUserData())) continue;
            if (title.equals(col.getText())) {
                Integer idx = (Integer) col.getUserData();
                return idx != null ? idx : -1;
            }
        }
        return -1;
    }

    public void loadStructure() {
        if (isNewTable) {
            initNewTableStructure();
            return;
        }
        loadingIndicator.setVisible(true);
        tableView.setDisable(true);

        // 重置各标签页加载状态，刷新后需重新加载
        indexesLoaded = false;
        foreignKeysLoaded = false;
        triggersLoaded = false;
        optionsLoaded = false;
        commentLoaded = false;
        sqlPreviewLoaded = false;
        originalTableComment = null;

        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, databaseName);
            connLock.lock();
            try {
                // 首次加载时获取数据库版本和数据类型列表
                if (cachedDataTypes == null) {
                    try {
                        cachedDbVersion = DatabaseService.getDatabaseProductVersion(config);
                    } catch (Exception e) {
                        cachedDbVersion = null; // 获取失败时使用默认列表
                    }
                    cachedDataTypes = DataTypeProvider.getDataTypes(config.getType(), cachedDbVersion);
                }

                // 加载字符集映射（供底部面板和选项标签页使用）
                if (cachedCharsets == null) {
                    try {
                        cachedCharsets = DatabaseService.getCharsets(config);
                    } catch (Exception e) {
                        cachedCharsets = new HashMap<>();
                    }
                }

                List<Map<String, String>> columns = DatabaseService.getTableColumns(config, databaseName, schemaName, tableName);
                Platform.runLater(() -> {
                    updateTableView(columns);
                    updateFieldPropertiesPane();
                    String versionInfo = cachedDbVersion != null ? " | 版本: " + cachedDbVersion : "";
                    statusLabel.setText("共 " + columns.size() + " 个字段" + versionInfo);
                    loadingIndicator.setVisible(false);
                    tableView.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("加载失败: " + e.getMessage());
                    loadingIndicator.setVisible(false);
                    tableView.setDisable(false);
                });
            } finally {
                connLock.unlock();
            }
        }, "DB-LoadTableStructure").start();
    }

    /**
     * 初始化新建表模式：后台加载数据类型/字符集后，初始化空字段表格
     */
    private void initNewTableStructure() {
        loadingIndicator.setVisible(true);
        tableView.setDisable(true);

        new Thread(() -> {
            java.util.concurrent.locks.ReentrantLock connLock = DatabaseService.acquireUsageLock(config, databaseName);
            connLock.lock();
            try {
                if (cachedDataTypes == null) {
                    try {
                        cachedDbVersion = DatabaseService.getDatabaseProductVersion(config);
                    } catch (Exception e) {
                        cachedDbVersion = null;
                    }
                    cachedDataTypes = DataTypeProvider.getDataTypes(config.getType(), cachedDbVersion);
                }
                if (cachedCharsets == null) {
                    try {
                        cachedCharsets = DatabaseService.getCharsets(config);
                    } catch (Exception e) {
                        cachedCharsets = new HashMap<>();
                    }
                }
            } finally {
                connLock.unlock();
                Platform.runLater(this::initEmptyTable);
            }
        }, "DB-InitNewTable").start();
    }

    /**
     * 初始化空字段表格（新建表模式）：构造一个空行触发标准列标题创建
     */
    private void initEmptyTable() {
        Map<String, String> emptyRow = new LinkedHashMap<>();
        emptyRow.put("字段名", "");
        emptyRow.put("类型", "");
        emptyRow.put("长度", "");
        emptyRow.put("小数点", "");
        emptyRow.put("非空", "否");
        emptyRow.put("主键", "否");
        emptyRow.put("自增", "否");
        emptyRow.put("默认值", "");
        emptyRow.put("注释", "");
        // 以下列不在表格中显示（由字段属性面板编辑），但需存在以便面板读写
        emptyRow.put("无符号", "否");
        emptyRow.put("填充零", "否");
        emptyRow.put("字符集", "");
        emptyRow.put("排序规则", "");
        emptyRow.put("键长度", "");
        emptyRow.put("二进制", "否");
        updateTableView(List.of(emptyRow));
        updateFieldPropertiesPane();
        String versionInfo = cachedDbVersion != null ? " | 版本: " + cachedDbVersion : "";
        statusLabel.setText("新建表" + versionInfo);
        loadingIndicator.setVisible(false);
        tableView.setDisable(false);
    }

    private void updateTableView(List<Map<String, String>> columns) {
        tableView.getColumns().clear();
        tableView.getItems().clear();
        originalColumnComments.clear();

        if (columns.isEmpty()) return;

        // 列标题名（从第一行的key集合获取，保持LinkedHashMap的插入顺序）
        this.columnTitles = new ArrayList<>(columns.get(0).keySet());
        dataColumnCount = this.columnTitles.size();

        // 创建行选择器列：选中行显示黑色实心三角箭头
        TableColumn<ObservableList<String>, String> selectorCol = new TableColumn<>();
        selectorCol.setPrefWidth(15);
        selectorCol.setMaxWidth(15);
        selectorCol.setMinWidth(15);
        selectorCol.setSortable(false);
        selectorCol.setReorderable(false);
        selectorCol.setStyle("-fx-alignment: CENTER;");
        selectorCol.getStyleClass().add("row-selector-col");
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
                    tableView.getSelectionModel().getSelectedCells().removeListener(selectionListener);
                    selectionListener = null;
                }
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    arrow.setVisible(false);
                    setStyle("-fx-border-color: transparent; -fx-border-width: 0;");
                    return;
                }
                setStyle("-fx-border-color: transparent #BEBEBC #BEBEBC #BEBEBC; -fx-border-width: 0 1 1 1;");
                arrow.setVisible(isRowSelected(getTableRow().getIndex()));
                selectionListener = obs -> {
                    if (getTableRow() != null) {
                        arrow.setVisible(isRowSelected(getTableRow().getIndex()));
                    }
                };
                tableView.getSelectionModel().getSelectedCells().addListener(selectionListener);
            }
        });
        tableView.getColumns().add(selectorCol);

        // 创建数据列
        for (int i = 0; i < columnTitles.size(); i++) {
            final int dataColIndex = i;
            String title = columnTitles.get(i);

            // 不在表中显示的列（仅在下方字段属性面板中编辑）
            if ("自增".equals(title) || "无符号".equals(title) || "填充零".equals(title)
                    || "字符集".equals(title) || "排序规则".equals(title)
                    || "键长度".equals(title) || "二进制".equals(title)) {
                continue;
            }

            TableColumn<ObservableList<String>, String> col = new TableColumn<>(title);

            // 存储数据列索引到userData，避免行选择器列导致的索引偏移
            col.setUserData(dataColIndex);

            // 根据标题设置列宽
            int prefWidth = switch (title) {
                case "字段名" -> 150;
                case "类型" -> 120;
                case "长度", "小数点" -> 60;
                case "可为空", "非空", "自增" -> 60;
                case "主键" -> 70;
                case "默认值" -> 120;
                case "注释" -> 200;
                default -> 80;
            };
            col.setPrefWidth(prefWidth);
            col.setMinWidth(50);
            // 禁用排序，点击表头改为选中整列（见 setupHeaderClickSelection）
            col.setSortable(false);

            col.setCellValueFactory(param -> {
                ObservableList<String> row = param.getValue();
                if (dataColIndex < row.size()) {
                    return new SimpleStringProperty(row.get(dataColIndex));
                }
                return new SimpleStringProperty("");
            });

            if ("类型".equals(title)) {
                // "类型"列使用可编辑ComboBox单元格
                List<String> dataTypes = cachedDataTypes != null ? cachedDataTypes : Collections.emptyList();
                col.setCellFactory(tc -> new DataTypeComboBoxTableCell(dataTypes, columnTitles));
                col.setOnEditCommit(event -> {
                    ObservableList<String> row = event.getRowValue();
                    String oldValue = row.get(dataColIndex);
                    String newValue = event.getNewValue();
                    if (!oldValue.equals(newValue)) {
                        row.set(dataColIndex, newValue);
                        markDirty();
                    }
                });
            } else if ("主键".equals(title)) {
                // "主键"列使用主键图标+序号显示（支持多主键，按行序号编号）
                col.setCellFactory(tc -> new PrimaryKeyIconTableCell());
            } else if ("非空".equals(title)) {
                // "非空"列使用复选框，点击直接切换
                col.setCellFactory(tc -> new PrimaryKeyCheckBoxTableCell());
            } else if ("字段名".equals(title) || "长度".equals(title) || "小数点".equals(title) || "注释".equals(title)) {
                // "字段名"/"长度"/"小数点"/"注释"列使用可编辑TextField单元格
                col.setCellFactory(tc -> new EditableTextFieldTableCell(columnTitles));
                col.setOnEditCommit(event -> {
                    ObservableList<String> row = event.getRowValue();
                    String oldValue = row.get(dataColIndex);
                    String newValue = event.getNewValue();
                    if (!oldValue.equals(newValue)) {
                        row.set(dataColIndex, newValue);
                        markDirty();
                    }
                });
            } else {
                // 其他列保持只读
                col.setCellFactory(tc -> new TableCell<>() {
                    @Override
                    protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty || item == null) {
                            setText(null);
                            setStyle("");
                        } else {
                            setText(item);
                            // 主键列高亮
                            int pkColIndex = columnTitles.indexOf("主键");
                            if (pkColIndex >= 0) {
                                TableRow<?> currentRow = getTableRow();
                                if (currentRow != null && currentRow.getItem() instanceof ObservableList row) {
                                    String isPk = pkColIndex < row.size() ? (String) row.get(pkColIndex) : "";
                                    if ("是".equals(isPk)) {
                                        setStyle("-fx-font-weight: bold;");
                                        return;
                                    }
                                }
                            }
                            setStyle("");
                        }
                    }
                });
            }

            tableView.getColumns().add(col);
        }

        // 填充数据行
        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
        for (Map<String, String> colMap : columns) {
            ObservableList<String> row = FXCollections.observableArrayList();
            for (String title : columnTitles) {
                row.add(colMap.getOrDefault(title, ""));
            }
            rows.add(row);
        }
        tableView.setItems(rows);

        // 缓存列注释原始值（用于检测变更）
        int commentIdx = columnTitles.indexOf("注释");
        int nameIdx = columnTitles.indexOf("字段名");
        if (commentIdx >= 0 && nameIdx >= 0) {
            for (ObservableList<String> row : rows) {
                String colName = nameIdx < row.size() ? row.get(nameIdx) : "";
                String comment = commentIdx < row.size() ? row.get(commentIdx) : "";
                originalColumnComments.put(colName, comment != null ? comment : "");
            }
        }
        // 建立字段表数据快照作为"已保存"基准（用于检测增删改并生成ALTER SQL）
        snapshotColumns();
        dirty = false;
        notifyDirtyChange();
    }

    /**
     * 判断指定行是否有任何cell被选中
     */
    private boolean isRowSelected(int rowIndex) {
        for (TablePosition<?, ?> pos : tableView.getSelectionModel().getSelectedCells()) {
            if (pos.getRow() == rowIndex) return true;
        }
        return false;
    }

    public void applyTableConfig(GlobalConfig config) {
        int rowHeight = config.getTableFontSize() + 18;
        tableView.setFixedCellSize(rowHeight);
        String fontStyle = String.format("-fx-font-family: '%s'; -fx-font-size: %dpx;",
                config.getTableFontName(), config.getTableFontSize());
        tableView.setStyle(fontStyle + " -fx-padding: 0; -fx-background-insets: 0; -fx-background-color: transparent; -fx-border-color: transparent; -fx-border-insets: 0; -fx-table-header-height: " + rowHeight + ";");
    }

    /**
     * "非空"列的复选框单元格，点击直接切换，无需先进入编辑模式
     */
    private class PrimaryKeyCheckBoxTableCell extends TableCell<ObservableList<String>, String> {
        private final CheckBox checkBox;

        public PrimaryKeyCheckBoxTableCell() {
            this.checkBox = new CheckBox();
            checkBox.setStyle("-fx-padding: 0; -fx-alignment: center;");
            // 点击复选框时先选中行
            checkBox.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
                tableView.getSelectionModel().select(getIndex());
            });
            // 点击复选框直接更新数据模型
            checkBox.setOnAction(e -> {
                TableRow<?> tableRow = getTableRow();
                if (tableRow == null || tableRow.getItem() == null) return;
                @SuppressWarnings("unchecked")
                ObservableList<String> row = (ObservableList<String>) tableRow.getItem();
                Integer dataColIndex = (Integer) getTableColumn().getUserData();
                if (dataColIndex != null && dataColIndex >= 0 && dataColIndex < row.size()) {
                    row.set(dataColIndex, checkBox.isSelected() ? "是" : "否");
                    updateFieldPropertiesPane();
                    markDirty();
                }
            });
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                setStyle("-fx-border-color: transparent; -fx-padding: 0;");
            } else {
                checkBox.setSelected("是".equals(item));
                setGraphic(checkBox);
                setText(null);
                setStyle("-fx-alignment: center; -fx-border-color: transparent #e0e0e0 #e0e0e0 transparent; -fx-border-width: 0 1 1 0; -fx-padding: 0;");
            }
        }
    }

    /**
     * "主键"列的图标+序号单元格：
     * - 主键字段显示主键图标 + 序号（多主键时按表格中行顺序依次编号 1,2,3...）
     * - 非主键字段显示空白
     * - 点击时在"主键"和"非主键"之间切换，切换后刷新整个表格以重新编号
     */
    private class PrimaryKeyIconTableCell extends TableCell<ObservableList<String>, String> {
        private final ImageView pkIcon;
        private final Label numberLabel;
        private final HBox graphic;
        /** 选中状态监听器，用于在行选中/取消选中时更新样式（避免选中时数字看不见） */
        private javafx.beans.InvalidationListener selectionListener;

        PrimaryKeyIconTableCell() {
            Image img = new Image(getClass().getResourceAsStream("/images/connect/primary_key.png"));
            pkIcon = new ImageView(img);
            pkIcon.setFitWidth(14);
            pkIcon.setFitHeight(14);
            pkIcon.setPreserveRatio(true);
            numberLabel = new Label();
            numberLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 0;");
            graphic = new HBox(2, pkIcon, numberLabel);
            graphic.setAlignment(Pos.CENTER);
            graphic.setMouseTransparent(false);

            // 点击切换主键状态
            setOnMousePressed(e -> {
                if (e.isConsumed()) return;
                if (isEmpty() || getItem() == null) return;
                // 先取消正在编辑的单元格（cancelEdit会保留编辑值到数据模型，避免refresh丢失输入）
                if (tableView.getEditingCell() != null) {
                    tableView.edit(-1, null);
                }
                TableRow<?> tableRow = getTableRow();
                if (tableRow == null || tableRow.getItem() == null) return;
                tableView.getSelectionModel().select(getIndex());
                @SuppressWarnings("unchecked")
                ObservableList<String> row = (ObservableList<String>) tableRow.getItem();
                Integer dataColIndex = (Integer) getTableColumn().getUserData();
                if (dataColIndex == null || dataColIndex < 0 || dataColIndex >= row.size()) return;
                String current = row.get(dataColIndex);
                boolean becomePk = !"是".equals(current);
                row.set(dataColIndex, becomePk ? "是" : "否");
                // 设置为主键时自动将列设为 NOT NULL（主键列不允许 NULL）
                if (becomePk && columnTitles != null) {
                    int nullableIdx = columnTitles.indexOf("非空");
                    if (nullableIdx >= 0 && nullableIdx < row.size() && !"是".equals(row.get(nullableIdx))) {
                        row.set(nullableIdx, "是");
                    }
                }
                // 刷新整表以重新编号所有主键
                tableView.refresh();
                updateFieldPropertiesPane();
                markDirty();
                e.consume();
            });
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            // 清理旧的选中状态监听器
            if (selectionListener != null) {
                tableView.getSelectionModel().getSelectedCells().removeListener(selectionListener);
                selectionListener = null;
            }
            super.updateItem(item, empty);
            if (empty) {
                // 空单元格（表格底部无数据行）：完全清空，不显示边框，避免延伸到下方空白区
                setGraphic(null);
                setText(null);
                numberLabel.setText("");
                setStyle("-fx-border-color: transparent; -fx-padding: 0;");
            } else if (item == null || !"是".equals(item)) {
                // 非主键数据行：清空 graphic，显示边框
                setGraphic(null);
                setText(null);
                numberLabel.setText("");
                setStyle("-fx-alignment: center; -fx-border-color: transparent #e0e0e0 #e0e0e0 transparent; -fx-border-width: 0 1 1 0; -fx-padding: 0;");
            } else {
                int seq = computePrimaryKeySequence();
                numberLabel.setText(String.valueOf(seq));
                setGraphic(graphic);
                setText(null);
                applyRowStateStyle();
                // 注册选中状态监听器，选中/取消选中时更新数字颜色（避免选中时蓝色数字看不清）
                selectionListener = obs -> applyRowStateStyle();
                tableView.getSelectionModel().getSelectedCells().addListener(selectionListener);
            }
        }

        /**
         * 根据行状态应用视觉样式：
         * - 选中：蓝色背景 + 白色数字
         * - 未选中：透明背景 + 蓝色数字
         */
        private void applyRowStateStyle() {
            TableRow<?> currentRow = getTableRow();
            boolean selected = currentRow != null && currentRow.isSelected();
            if (selected) {
                setStyle("-fx-alignment: center; -fx-background-color: #3592CB; -fx-border-color: transparent #e0e0e0 #e0e0e0 transparent; -fx-border-width: 0 1 1 0; -fx-padding: 0;");
                numberLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 0;");
            } else {
                setStyle("-fx-alignment: center; -fx-border-color: transparent #e0e0e0 #e0e0e0 transparent; -fx-border-width: 0 1 1 0; -fx-padding: 0;");
                numberLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 0;");
            }
        }

        /**
         * 计算当前行在所有主键字段中的序号（从1开始，按表格行顺序）
         */
        private int computePrimaryKeySequence() {
            TableRow<?> tableRow = getTableRow();
            if (tableRow == null || tableRow.getItem() == null) return 1;
            Integer dataColIndex = (Integer) getTableColumn().getUserData();
            if (dataColIndex == null) return 1;
            int seq = 0;
            for (ObservableList<String> row : tableView.getItems()) {
                if (dataColIndex < row.size() && "是".equals(row.get(dataColIndex))) {
                    seq++;
                    if (row == tableRow.getItem()) return seq;
                }
            }
            return seq > 0 ? seq : 1;
        }
    }

    /**
     * "类型"列的可编辑ComboBox单元格（始终显示ComboBox，点击即展开下拉）
     */
    private class DataTypeComboBoxTableCell extends TableCell<ObservableList<String>, String> {
        private ComboBox<String> comboBox;
        private FilteredList<String> filteredItems;
        private final List<String> dataTypes;
        private final List<String> columnTitles;
        /** 标记用户是否按下了Escape键（真正取消编辑） */
        private boolean escapePressed = false;
        /** 选中状态监听器，用于在行选中/取消选中时更新样式 */
        private javafx.beans.InvalidationListener selectionListener;

        public DataTypeComboBoxTableCell(List<String> dataTypes, List<String> columnTitles) {
            this.dataTypes = dataTypes;
            this.columnTitles = columnTitles;
            setStyle("-fx-padding: 0; -fx-border-color: transparent; -fx-alignment: CENTER;");
        }

        @Override
        public void startEdit() {
            escapePressed = false;
            super.startEdit();
            if (comboBox == null) {
                createComboBox();
            }
            // 重置过滤，显示全部类型
            filteredItems.setPredicate(p -> true);
            comboBox.setValue(getItem() != null ? getItem() : "");
            setText(null);
            setGraphic(comboBox);
            // 编辑状态：白色背景+蓝色边框
            comboBox.setStyle(
                "-fx-background-radius: 0; -fx-border-radius: 0; " +
                "-fx-border-color: transparent; " +
                "-fx-padding: 0; " +
                "-fx-pref-height: 24px;"
            );
            setStyle("-fx-background-color: white; -fx-border-color: #3592CB; -fx-border-width: 1; -fx-padding: 0; -fx-text-fill: black;");
            // 注入提交回调供方向键导航使用；下拉打开时方向键不导航（用于选择下拉项）
            currentEditCommit = () -> commitEdit(comboBox.getValue() != null ? comboBox.getValue() : "");
            skipArrowNavigation = () -> comboBox.isShowing();
            // 延迟展开下拉，并预选当前值对应项、让下拉 ListView 获得焦点
            Platform.runLater(() -> {
                comboBox.show();
                // 预选当前值对应的下拉项，让用户看到当前选中位置
                String currentValue = comboBox.getValue();
                if (currentValue != null) {
                    int idx = comboBox.getItems().indexOf(currentValue);
                    if (idx >= 0) {
                        comboBox.getSelectionModel().select(idx);
                        comboBox.getEditor().deselect();
                    }
                }
                // 让下拉 ListView 获得焦点，便于上下箭头切换选中项
                requestPopupListViewFocus();
            });
        }

        @Override
        public void cancelEdit() {
            // 非Escape触发的cancel（如点击其他cell导致失焦），保留编辑值到数据模型
            if (!escapePressed && comboBox != null) {
                String newValue = comboBox.getValue();
                String currentValue = getItem() != null ? getItem() : "";
                if (newValue != null && !newValue.equals(currentValue)) {
                    updateCellData(newValue);
                }
            }
            escapePressed = false;
            super.cancelEdit();
            // cancelEdit后getItem()返回原值，但数据模型可能已更新，需从数据模型读取显示值
            String displayValue = getCellData();
            if (comboBox != null) {
                comboBox.setValue(displayValue != null ? displayValue : "");
            }
            setText(null);
            setGraphic(comboBox);
            applyRowStateStyle();
            clearEditCommitCallback();
        }

        @Override
        public void commitEdit(String newValue) {
            // 提交编辑时同步更新数据模型，避免refresh()后丢失输入值
            String oldValue = getCellData();
            updateCellData(newValue);
            super.commitEdit(newValue);
            // 值真正变化时标记脏状态（updateCellData已改row，setOnEditCommit里读到的新值==旧值，无法判断，故在此判断）
            if (oldValue == null ? newValue != null : !oldValue.equals(newValue)) {
                markDirty();
            }
            clearEditCommitCallback();
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            // 清理旧的选中状态监听器
            if (selectionListener != null) {
                tableView.getSelectionModel().getSelectedCells().removeListener(selectionListener);
                selectionListener = null;
            }
            super.updateItem(item, empty);
            if (empty) {
                setText(null);
                setGraphic(null);
                setStyle("-fx-border-color: transparent; -fx-padding: 0; -fx-alignment: CENTER;");
            } else {
                if (comboBox == null) {
                    createComboBox();
                }
                String value = item != null ? item : "";
                comboBox.setValue(value);
                setText(null);
                setGraphic(comboBox);
                if (isEditing()) {
                    setStyle("-fx-background-color: white; -fx-border-color: #3592CB; -fx-border-width: 1; -fx-padding: 0; -fx-text-fill: black; -fx-alignment: CENTER;");
                } else {
                    // 非编辑状态：ComboBox看起来像普通文本
                    comboBox.setStyle(
                        "-fx-background-radius: 0; -fx-border-radius: 0; " +
                        "-fx-border-color: transparent; " +
                        "-fx-padding: 0; " +
                        "-fx-pref-height: 24px;"
                    );
                    applyRowStateStyle();
                    // 注册选中状态监听器，选中/取消选中时更新样式
                    selectionListener = obs -> {
                        if (!isEditing()) {
                            applyRowStateStyle();
                        }
                    };
                    tableView.getSelectionModel().getSelectedCells().addListener(selectionListener);
                }
            }
        }

        private void createComboBox() {
            comboBox = new ComboBox<>();
            comboBox.setEditable(true);
            // 使用FilteredList包装，底层列表始终保持全部类型，通过predicate控制下拉显示
            ObservableList<String> sourceItems = FXCollections.observableArrayList(dataTypes);
            filteredItems = new FilteredList<>(sourceItems, p -> true);
            comboBox.setItems(filteredItems);
            comboBox.setVisibleRowCount(20);
            comboBox.getStyleClass().add("combo-box-table-cell");
            comboBox.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());

            // 紧凑样式：透明边框，适配表格行高
            comboBox.setStyle(
                "-fx-background-radius: 0; -fx-border-radius: 0; " +
                "-fx-border-color: transparent; " +
                "-fx-padding: 0; " +
                "-fx-pref-height: 24px;"
            );

            // 点击ComboBox时先选中单元格（而非整行），再进入编辑模式并展开下拉
            comboBox.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
                if (!isEditing()) {
                    TableView<ObservableList<String>> tv = getTableView();
                    if (tv != null) {
                        tv.getSelectionModel().clearSelection();
                        tv.getSelectionModel().select(getIndex(), getTableColumn());
                        tv.edit(getIndex(), getTableColumn());
                        e.consume();
                    }
                }
            });

            // 输入时过滤下拉项（仅当编辑器有焦点时，即用户主动输入）
            comboBox.getEditor().textProperty().addListener((obs, oldValue, newValue) -> {
                if (!comboBox.getEditor().isFocused()) return;
                String input = newValue != null ? newValue.trim() : "";
                if (input.isEmpty()) {
                    filteredItems.setPredicate(p -> true);
                } else {
                    String lowerInput = input.toLowerCase();
                    filteredItems.setPredicate(t -> t.toLowerCase().contains(lowerInput));
                }
                // 延迟确保过滤后下拉列表保持显示
                Platform.runLater(() -> {
                    if (comboBox.getEditor().isFocused() && !comboBox.isShowing()) {
                        comboBox.show();
                    }
                });
            });

            // 键盘交互：上下箭头只移动下拉 popup 的 focus 高亮（不修改类型值），
            // 回车提交 focus 项的值，Escape 取消编辑
            // 用 eventFilter 在捕获阶段处理，consume 阻止 ComboBox 默认行为（默认会改 value）
            comboBox.getEditor().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
                KeyCode code = event.getCode();
                if (code == KeyCode.ESCAPE) {
                    escapePressed = true;
                    return; // 让默认行为 cancelEdit
                }
                if (code == KeyCode.ENTER) {
                    commitPopupFocusedValue();
                    event.consume();
                    return;
                }
                if (!comboBox.isShowing()) return;
                if (code == KeyCode.UP) {
                    movePopupFocus(-1);
                    event.consume();
                } else if (code == KeyCode.DOWN) {
                    movePopupFocus(1);
                    event.consume();
                }
            });

            // 鼠标点击下拉项时提交编辑（ENTER 已在 eventFilter 中处理）
            comboBox.setOnAction(e -> {
                if (comboBox.getValue() != null) {
                    commitEdit(comboBox.getValue());
                }
            });

            // 失焦时提交编辑（弹窗显示时不提交，用户可能在选择项）
            comboBox.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                if (!isNowFocused && !escapePressed && !comboBox.isShowing()) {
                    commitEdit(comboBox.getValue() != null ? comboBox.getValue() : "");
                }
            });
        }

        /**
         * 更新当前单元格对应的数据模型值
         */
        private void updateCellData(String newValue) {
            TableRow<?> tableRow = getTableRow();
            if (tableRow == null) return;
            @SuppressWarnings("unchecked")
            ObservableList<String> row = (ObservableList<String>) tableRow.getItem();
            if (row == null) return;
            Integer dataColIndex = (Integer) getTableColumn().getUserData();
            if (dataColIndex != null && dataColIndex >= 0 && dataColIndex < row.size()) {
                row.set(dataColIndex, newValue);
            }
        }

        /**
         * 从数据模型获取当前单元格的值
         */
        private String getCellData() {
            TableRow<?> tableRow = getTableRow();
            if (tableRow == null) return getItem();
            @SuppressWarnings("unchecked")
            ObservableList<String> row = (ObservableList<String>) tableRow.getItem();
            if (row == null) return getItem();
            Integer dataColIndex = (Integer) getTableColumn().getUserData();
            if (dataColIndex != null && dataColIndex >= 0 && dataColIndex < row.size()) {
                return row.get(dataColIndex);
            }
            return getItem();
        }

        /**
         * 根据行状态应用视觉样式（主键行高亮、选中行蓝色背景白色文字）
         * 注意：单元格选择模式下，仅选中本单元格时 TableRow.isSelected() 返回 false，
         * 需额外通过 isSelected(row, col) 检查当前单元格的选中状态。
         */
        private void applyRowStateStyle() {
            boolean selected = isCurrentCellSelected();
            int pkColIndex = columnTitles.indexOf("主键");
            if (pkColIndex >= 0) {
                TableRow<?> currentRow = getTableRow();
                if (currentRow != null && currentRow.getItem() instanceof ObservableList row) {
                    String isPk = pkColIndex < row.size() ? (String) row.get(pkColIndex) : "";
                    if ("是".equals(isPk)) {
                        if (selected) {
                            setStyle("-fx-background-color: #3592CB; -fx-text-fill: white; -fx-font-weight: bold; -fx-alignment: CENTER;");
                            if (comboBox != null) {
                                comboBox.setStyle(
                                    "-fx-background-radius: 0; -fx-border-radius: 0; " +
                                    "-fx-border-color: transparent; -fx-padding: 0; " +
                                    "-fx-pref-height: 24px; -fx-background-color: #3592CB;"
                                );
                                comboBox.getEditor().setStyle("-fx-text-fill: white; -fx-background-color: #3592CB; -fx-padding: 0 4; -fx-border-color: transparent;");
                            }
                        } else {
                            setStyle("-fx-font-weight: bold; -fx-alignment: CENTER;");
                            if (comboBox != null) {
                                comboBox.setStyle(
                                    "-fx-background-radius: 0; -fx-border-radius: 0; " +
                                    "-fx-border-color: transparent; -fx-padding: 0; " +
                                    "-fx-pref-height: 24px;"
                                );
                                comboBox.getEditor().setStyle("-fx-padding: 0 4; -fx-border-color: transparent; -fx-background-color: transparent;");
                            }
                        }
                        return;
                    }
                }
            }
            // 非主键行：检查是否选中
            if (selected) {
                setStyle("-fx-background-color: #3592CB; -fx-text-fill: white; -fx-alignment: CENTER;");
                if (comboBox != null) {
                    comboBox.setStyle(
                        "-fx-background-radius: 0; -fx-border-radius: 0; " +
                        "-fx-border-color: transparent; -fx-padding: 0; " +
                        "-fx-pref-height: 24px; -fx-background-color: #3592CB;"
                    );
                    comboBox.getEditor().setStyle("-fx-text-fill: white; -fx-background-color: #3592CB; -fx-padding: 0 4; -fx-border-color: transparent;");
                }
            } else {
                setStyle("-fx-alignment: CENTER;");
                if (comboBox != null) {
                    comboBox.setStyle(
                        "-fx-background-radius: 0; -fx-border-radius: 0; " +
                        "-fx-border-color: transparent; -fx-padding: 0; " +
                        "-fx-pref-height: 24px;"
                    );
                    comboBox.getEditor().setStyle("-fx-padding: 0 4; -fx-border-color: transparent; -fx-background-color: transparent;");
                }
            }
        }

        /**
         * 判断当前单元格是否处于选中状态：
         * 行选中（整行选中）或本单元格被选中（单元格选择模式下仅选中本单元格）均视为选中。
         */
        private boolean isCurrentCellSelected() {
            TableView.TableViewSelectionModel<ObservableList<String>> sm = tableView.getSelectionModel();
            if (sm == null) return false;
            // 单元格选择模式：精确检查当前 (row, col) 是否被选中
            if (sm.isCellSelectionEnabled()) {
                return sm.isSelected(getIndex(), getTableColumn());
            }
            // 行选择模式：依赖 TableRow 选中状态
            TableRow<?> currentRow = getTableRow();
            return currentRow != null && currentRow.isSelected();
        }

        /**
         * 让 ComboBox 下拉弹出的 ListView 获得键盘焦点，便于直接用上下箭头切换选中项。
         * 通过 ComboBoxListViewSkin.getPopupContent() 获取 popup 的 ListView。
         * ListView 获得焦点后，UP/DOWN 只移动 focus 高亮（不改 selection/类型值），
         * ENTER 提交 focus 项、ESCAPE 取消编辑在此监听。
         * 事件过滤器仅安装一次（用 properties 标记），避免重复添加。
         */
        private void requestPopupListViewFocus() {
            if (!(comboBox.getSkin() instanceof ComboBoxListViewSkin)) return;
            @SuppressWarnings("unchecked")
            ComboBoxListViewSkin<String> skin = (ComboBoxListViewSkin<String>) comboBox.getSkin();
            Node popupContent = skin.getPopupContent();
            if (!(popupContent instanceof ListView)) return;
            @SuppressWarnings("unchecked")
            ListView<String> listView = (ListView<String>) popupContent;
            // 聚焦当前值对应的项（若已 select 过），否则聚焦第 0 项
            int focusIdx = listView.getSelectionModel().getSelectedIndex();
            if (focusIdx < 0) focusIdx = 0;
            listView.getFocusModel().focus(focusIdx);
            listView.scrollTo(focusIdx);
            listView.requestFocus();
            // 仅安装一次键盘事件过滤器（ComboBox 复用同一个 popup ListView）
            if (listView.getProperties().get("tomatoKeyFilterInstalled") != null) return;
            listView.getProperties().put("tomatoKeyFilterInstalled", true);
            listView.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
                KeyCode code = event.getCode();
                if (code == KeyCode.UP) {
                    movePopupFocus(-1);
                    event.consume();
                } else if (code == KeyCode.DOWN) {
                    movePopupFocus(1);
                    event.consume();
                } else if (code == KeyCode.ENTER) {
                    commitPopupFocusedValue();
                    event.consume();
                } else if (code == KeyCode.ESCAPE) {
                    escapePressed = true;
                    if (comboBox.isShowing()) comboBox.hide();
                    event.consume();
                }
            });
        }

        /**
         * 移动下拉 popup 的 focus 高亮（不改变 selection/类型值）。
         * delta 为 -1（上）或 +1（下）。
         */
        private void movePopupFocus(int delta) {
            ListView<String> listView = getPopupListView();
            if (listView == null) return;
            int size = listView.getItems().size();
            if (size == 0) return;
            int cur = listView.getFocusModel().getFocusedIndex();
            int newIdx;
            if (delta < 0) {
                newIdx = (cur <= 0) ? 0 : cur - 1;
            } else {
                newIdx = (cur < 0) ? 0 : Math.min(cur + 1, size - 1);
            }
            if (newIdx != cur) {
                listView.getFocusModel().focus(newIdx);
                listView.scrollTo(newIdx);
            }
        }

        /**
         * 提交下拉 popup 中当前 focus 项的值。
         * 若无法获取 popup ListView，回退到 comboBox.getValue()。
         * 提交后隐藏下拉框。
         */
        private void commitPopupFocusedValue() {
            String value = null;
            ListView<String> listView = getPopupListView();
            if (listView != null) {
                int fIdx = listView.getFocusModel().getFocusedIndex();
                if (fIdx >= 0 && fIdx < listView.getItems().size()) {
                    value = listView.getItems().get(fIdx);
                }
            }
            if (value == null || value.isEmpty()) {
                value = comboBox.getValue();
            }
            // 先隐藏下拉，再提交（避免提交过程中下拉残留）
            if (comboBox.isShowing()) comboBox.hide();
            if (value != null && !value.isEmpty()) {
                commitEdit(value);
            }
        }

        /**
         * 获取 ComboBox 下拉弹出的 ListView（通过 ComboBoxListViewSkin.getPopupContent）。
         */
        @SuppressWarnings("unchecked")
        private ListView<String> getPopupListView() {
            if (!(comboBox.getSkin() instanceof ComboBoxListViewSkin)) return null;
            ComboBoxListViewSkin<String> skin = (ComboBoxListViewSkin<String>) comboBox.getSkin();
            Node popupContent = skin.getPopupContent();
            if (popupContent instanceof ListView) {
                return (ListView<String>) popupContent;
            }
            return null;
        }
    }

    /**
     * "字段名"/"长度"列的可编辑TextField单元格（双击进入编辑模式）
     */
    private class EditableTextFieldTableCell extends TableCell<ObservableList<String>, String> {
        private TextField textField;
        private final List<String> columnTitles;
        /** 标记用户是否按下了Escape键（真正取消编辑） */
        private boolean escapePressed = false;
        /** 选中状态监听器，用于在行选中/取消选中时更新样式 */
        private javafx.beans.InvalidationListener selectionListener;

        public EditableTextFieldTableCell(List<String> columnTitles) {
            this.columnTitles = columnTitles;
            setStyle("-fx-padding: 0; -fx-border-color: transparent;");
        }

        @Override
        public void startEdit() {
            escapePressed = false;
            super.startEdit();
            if (textField == null) {
                createTextField();
            }
            setText(null);
            setGraphic(textField);
            textField.setText(getItem() != null ? getItem() : "");
            // 编辑状态：白色背景+蓝色边框，内容垂直居中、水平左对齐
            setStyle("-fx-background-color: white; -fx-border-color: #3592CB; -fx-border-width: 1; -fx-padding: 0; -fx-text-fill: black; -fx-alignment: CENTER_LEFT;");
            // 注入提交回调供方向键导航使用
            currentEditCommit = () -> commitEdit(textField.getText());
            skipArrowNavigation = () -> false;
            Platform.runLater(() -> {
                textField.requestFocus();
                textField.selectAll();
            });
        }

        @Override
        public void cancelEdit() {
            // 非Escape触发的cancel（如点击其他cell导致失焦），保留编辑值到数据模型
            if (!escapePressed && textField != null) {
                String newValue = textField.getText();
                String currentValue = getItem() != null ? getItem() : "";
                if (!newValue.equals(currentValue)) {
                    updateCellData(newValue);
                }
            }
            escapePressed = false;
            super.cancelEdit();
            String displayValue = getCellData();
            setText(displayValue != null ? displayValue : "");
            setGraphic(null);
            applyRowStateStyle();
            clearEditCommitCallback();
        }

        @Override
        public void commitEdit(String newValue) {
            // 提交编辑时同步更新数据模型，避免refresh()后丢失输入值
            String oldValue = getCellData();
            updateCellData(newValue);
            super.commitEdit(newValue);
            // 值真正变化时标记脏状态（updateCellData已改row，setOnEditCommit里读到的新值==旧值，无法判断，故在此判断）
            if (oldValue == null ? newValue != null : !oldValue.equals(newValue)) {
                markDirty();
            }
            clearEditCommitCallback();
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            // 清理旧的选中状态监听器
            if (selectionListener != null) {
                tableView.getSelectionModel().getSelectedCells().removeListener(selectionListener);
                selectionListener = null;
            }
            super.updateItem(item, empty);
            if (empty) {
                setText(null);
                setGraphic(null);
                setStyle("-fx-border-color: transparent; -fx-padding: 0;");
            } else {
                if (isEditing()) {
                    if (textField != null) {
                        textField.setText(getItem() != null ? getItem() : "");
                    }
                    setText(null);
                    setGraphic(textField);
                    setStyle("-fx-background-color: white; -fx-border-color: #3592CB; -fx-border-width: 1; -fx-padding: 0; -fx-text-fill: black; -fx-alignment: CENTER_LEFT;");
                } else {
                    setText(item != null ? item : "");
                    setGraphic(null);
                    applyRowStateStyle();
                    // 注册选中状态监听器，选中/取消选中时更新样式
                    selectionListener = obs -> {
                        if (!isEditing()) {
                            applyRowStateStyle();
                        }
                    };
                    tableView.getSelectionModel().getSelectedCells().addListener(selectionListener);
                }
            }
        }

        private void createTextField() {
            textField = new TextField(getItem() != null ? getItem() : "");
            textField.setMinWidth(this.getWidth() - this.getGraphicTextGap() * 2);
            textField.setStyle("-fx-background-color: white; -fx-border-color: transparent; -fx-border-width: 0; -fx-padding: 0 4; -fx-focus-color: transparent; -fx-faint-focus-color: transparent; -fx-text-fill: black; -fx-alignment: CENTER_LEFT;");
            textField.setOnKeyPressed(event -> {
                escapePressed = (event.getCode() == KeyCode.ESCAPE);
            });
            textField.setOnAction(e -> commitEdit(textField.getText()));
            textField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                if (!isNowFocused && !escapePressed) {
                    commitEdit(textField.getText());
                }
            });
        }

        private void updateCellData(String newValue) {
            TableRow<?> tableRow = getTableRow();
            if (tableRow == null) return;
            @SuppressWarnings("unchecked")
            ObservableList<String> row = (ObservableList<String>) tableRow.getItem();
            if (row == null) return;
            Integer dataColIndex = (Integer) getTableColumn().getUserData();
            if (dataColIndex != null && dataColIndex >= 0 && dataColIndex < row.size()) {
                row.set(dataColIndex, newValue);
            }
        }

        private String getCellData() {
            TableRow<?> tableRow = getTableRow();
            if (tableRow == null) return getItem();
            @SuppressWarnings("unchecked")
            ObservableList<String> row = (ObservableList<String>) tableRow.getItem();
            if (row == null) return getItem();
            Integer dataColIndex = (Integer) getTableColumn().getUserData();
            if (dataColIndex != null && dataColIndex >= 0 && dataColIndex < row.size()) {
                return row.get(dataColIndex);
            }
            return getItem();
        }

        private void applyRowStateStyle() {
            int pkColIndex = columnTitles.indexOf("主键");
            if (pkColIndex >= 0) {
                TableRow<?> currentRow = getTableRow();
                if (currentRow != null && currentRow.getItem() instanceof ObservableList row) {
                    String isPk = pkColIndex < row.size() ? (String) row.get(pkColIndex) : "";
                    if ("是".equals(isPk)) {
                        if (currentRow.isSelected()) {
                            setStyle("-fx-background-color: #3592CB; -fx-text-fill: white; -fx-font-weight: bold;");
                        } else {
                            setStyle("-fx-font-weight: bold;");
                        }
                        return;
                    }
                }
            }
            // 非主键行：检查是否选中
            TableRow<?> currentRow = getTableRow();
            if (currentRow != null && currentRow.isSelected()) {
                setStyle("-fx-background-color: #3592CB; -fx-text-fill: white;");
            } else {
                setStyle("");
            }
        }
    }

    /**
     * SQL预览查看器：基于RichTextFX InlineCssTextArea
     * 支持SQL关键字高亮、行号显示、括号折叠
     */
    private static class SqlPreviewViewer {
        private final org.fxmisc.richtext.InlineCssTextArea textArea;
        private final org.fxmisc.flowless.VirtualizedScrollPane<org.fxmisc.richtext.InlineCssTextArea> scrollPane;
        private final HBox container;
        private final VBox gutterBox;

        private String[] paragraphs = new String[0];
        private final List<int[]> foldRanges = new ArrayList<>();
        private final Set<Integer> foldedStarts = new HashSet<>();

        private static final String STYLE_KEYWORD = "-fx-fill: #0000FF; -fx-font-weight: bold;";
        private static final String STYLE_STRING = "-fx-fill: #A31515;";
        private static final String STYLE_COMMENT = "-fx-fill: #6A9955; -fx-font-style: italic;";
        private static final String STYLE_NUMBER = "-fx-fill: #098658;";

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
                "CHARACTER", "COLLATE", "REPLACE", "COMMENT", "COLUMN", "MODIFY", "ADD"
        };

        private static final String KEYWORD_PATTERN = "(?i)\\b(" + String.join("|", KEYWORDS) + ")\\b";
        private static final java.util.regex.Pattern SYNTAX_PATTERN = java.util.regex.Pattern.compile(
                "(?<KEYWORD>" + KEYWORD_PATTERN + ")" +
                        "|(?<STRING>'[^']*')" +
                        "|(?<COMMENT1>--[^\n]*)" +
                        "|(?<COMMENT2>/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/)" +
                        "|(?<NUMBER>\\b\\d+(\\.\\d+)?\\b)"
        );

        SqlPreviewViewer() {
            textArea = new org.fxmisc.richtext.InlineCssTextArea();
            textArea.setEditable(false);
            textArea.setWrapText(false);
            textArea.setStyle(
                    "-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px; " +
                            "-fx-background-color: white; -fx-padding: 2; -fx-text-fill: #333; " +
                            "-fx-border-color: transparent; -fx-border-width: 0; " +
                            "-fx-background-insets: 0; -fx-background-radius: 0;"
            );

            scrollPane = new org.fxmisc.flowless.VirtualizedScrollPane<>(textArea);
            scrollPane.setStyle("-fx-background-color: white; -fx-border-color: transparent; -fx-border-width: 0;");

            gutterBox = new VBox();
            gutterBox.setStyle("-fx-background-color: #f8f8f8; -fx-padding: 2 0 0 0;");
            gutterBox.setPrefWidth(60);
            gutterBox.setMinWidth(60);
            gutterBox.setMaxWidth(60);

            container = new HBox();
            container.getChildren().addAll(gutterBox, scrollPane);
            HBox.setHgrow(scrollPane, javafx.scene.layout.Priority.ALWAYS);
            container.setMinHeight(0);
            container.setPrefHeight(200);

            // 行号区与文本区滚动同步
            textArea.estimatedScrollYProperty().addListener((obs, old, val) ->
                    gutterBox.setTranslateY(-val.doubleValue()));
        }

        Node getNode() {
            return container;
        }

        void setText(String text) {
            paragraphs = text.split("\n", -1);
            detectFoldRanges();
            foldedStarts.clear();
            rebuild();
        }

        private void detectFoldRanges() {
            foldRanges.clear();
            Deque<int[]> stack = new ArrayDeque<>();
            for (int i = 0; i < paragraphs.length; i++) {
                String line = paragraphs[i];
                boolean inString = false;
                boolean inLineComment = false;
                for (int j = 0; j < line.length(); j++) {
                    char c = line.charAt(j);
                    if (inLineComment) break;
                    if (inString) {
                        if (c == '\'') inString = false;
                        continue;
                    }
                    if (c == '\'') { inString = true; continue; }
                    if (c == '-' && j + 1 < line.length() && line.charAt(j + 1) == '-') {
                        inLineComment = true;
                        continue;
                    }
                    if (c == '(') {
                        stack.push(new int[]{i, j});
                    } else if (c == ')' && !stack.isEmpty()) {
                        int[] open = stack.pop();
                        if (i > open[0]) {
                            foldRanges.add(new int[]{open[0], i});
                        }
                    }
                }
            }
        }

        private boolean isFoldStart(int para) {
            for (int[] r : foldRanges) {
                if (r[0] == para) return true;
            }
            return false;
        }

        private int getFoldEnd(int para) {
            for (int[] r : foldRanges) {
                if (r[0] == para) return r[1];
            }
            return -1;
        }

        private boolean isInFoldedRegion(int para) {
            for (int start : foldedStarts) {
                int end = getFoldEnd(start);
                if (para > start && para <= end) return true;
            }
            return false;
        }

        private void toggleFold(int para) {
            if (foldedStarts.contains(para)) {
                foldedStarts.remove(para);
            } else {
                foldedStarts.add(para);
            }
            rebuild();
        }

        private void rebuild() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < paragraphs.length; i++) {
                if (isInFoldedRegion(i)) continue;
                if (sb.length() > 0) sb.append("\n");
                sb.append(paragraphs[i]);
                if (foldedStarts.contains(i)) {
                    sb.append(" ...");
                }
            }
            textArea.replaceText(sb.toString());
            applyHighlighting();
            rebuildGutter();
        }

        private void rebuildGutter() {
            gutterBox.getChildren().clear();
            for (int i = 0; i < paragraphs.length; i++) {
                if (isInFoldedRegion(i)) continue;

                HBox cell = new HBox();
                cell.setAlignment(Pos.CENTER_LEFT);

                if (isFoldStart(i)) {
                    Label foldBtn = new Label(foldedStarts.contains(i) ? "\u25B6" : "\u25BC");
                    foldBtn.setStyle("-fx-font-size: 10px; -fx-text-fill: #555; -fx-cursor: hand; -fx-padding: 0 2 0 4;");
                    final int paraIdx = i;
                    foldBtn.setOnMouseClicked(e -> {
                        toggleFold(paraIdx);
                        e.consume();
                    });
                    cell.getChildren().add(foldBtn);
                } else {
                    cell.getChildren().add(new Label("  "));
                }

                Label lineNum = new Label(String.valueOf(i + 1));
                lineNum.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px; " +
                        "-fx-text-fill: #888888; -fx-padding: 0 8 0 4;");
                cell.getChildren().add(lineNum);

                gutterBox.getChildren().add(cell);
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
                System.err.println("SQL预览高亮异常: " + e.getMessage());
            }
        }
    }
}
