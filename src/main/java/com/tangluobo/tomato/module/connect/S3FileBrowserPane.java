package com.tangluobo.tomato.module.connect;

import com.tangluobo.tomato.module.connect.service.OssService;
import com.tangluobo.tomato.module.connect.service.S3Service;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.SnapshotParameters;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.Scene;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * S3/OSS文件浏览器面板
 * 支持浏览Bucket列表、进入Bucket浏览文件/目录
 * 支持S3（AWS S3/MinIO）和阿里云OSS两种连接类型
 * 支持图标视图和列表视图两种模式
 * 支持图片文件预览
 */
public class S3FileBrowserPane extends BorderPane {

    private ConnectionConfig config;
    private final boolean isAliyunOSS;

    // 图片扩展名集合
    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>();
    static {
        IMAGE_EXTENSIONS.add("jpg"); IMAGE_EXTENSIONS.add("jpeg");
        IMAGE_EXTENSIONS.add("png"); IMAGE_EXTENSIONS.add("gif");
        IMAGE_EXTENSIONS.add("bmp"); IMAGE_EXTENSIONS.add("webp");
        IMAGE_EXTENSIONS.add("svg"); IMAGE_EXTENSIONS.add("ico");
        IMAGE_EXTENSIONS.add("tiff"); IMAGE_EXTENSIONS.add("tif");
    }

    // Markdown 扩展名集合
    private static final Set<String> MARKDOWN_EXTENSIONS = new HashSet<>();
    static {
        MARKDOWN_EXTENSIONS.add("md");
        MARKDOWN_EXTENSIONS.add("markdown");
        MARKDOWN_EXTENSIONS.add("mdown");
        MARKDOWN_EXTENSIONS.add("mkd");
    }

    // 视图模式
    private enum ViewMode { ICON, LIST, COLUMN }
    private ViewMode currentViewMode = ViewMode.ICON;

    // 状态栏组件
    private Circle statusDot;
    private Label stateLabel;
    private Label connLabel;

    // 路径导航
    private HBox pathBar;
    private TextField currentPathField;
    private Button refreshBtn;
    private Button upBtn;
    private Button createBucketBtn;
    private ToggleButton iconViewBtn;
    private ToggleButton listViewBtn;
    private ToggleButton columnViewBtn;

    // 列表视图
    private TableView<FileItem> fileTable;

    // 图标视图
    private ScrollPane iconScrollPane;
    private FlowPane iconFlowPane;

    // 列视图（macOS Column View）
    private ScrollPane columnScrollPane;
    private HBox columnContainer;
    private final List<ListView<FileItem>> columnListViews = new ArrayList<>();
    private final List<ObservableList<FileItem>> columnItems = new ArrayList<>();
    // 每列的 bucket（null 表示 Bucket 列表层）和 prefix
    private final List<String> columnBuckets = new ArrayList<>();
    private final List<String> columnPrefixes = new ArrayList<>();

    // 数据
    private ObservableList<FileItem> fileData = FXCollections.observableArrayList();

    // 当前浏览状态
    private String currentBucket = null;
    private String currentPrefix = "";
    private final List<String> pathHistory = new ArrayList<>();

    // 选中状态
    private FileItem selectedItem = null;
    private final Set<FileItem> selectedItems = new HashSet<>();

    // 图标视图框选状态
    private Rectangle selectionRect;
    private double rubberBandStartX, rubberBandStartY;
    private boolean isRubberBandActive = false;
    private boolean rubberBandAppendMode = false; // Ctrl/Shift 追加模式
    private Set<FileItem> rubberBandPreSelectedItems = new HashSet<>(); // 框选前已选中的项（追加模式用）
    // 自动滚动相关
    private javafx.animation.AnimationTimer autoScrollTimer = null;
    private double autoScrollDX = 0, autoScrollDY = 0;

    // 编辑器 Tab 页（中心区域：文件浏览 + 多个 markdown 编辑器）
    private TabPane editorTabPane;
    private Tab browseTab;

    // 图标
    private Image folderIcon;
    private Image folderLargeIcon;
    private Image bucketIcon;
    private Image bucketLargeIcon;
    private Image fileIcon;
    private Image fileLargeIcon;
    private Image imageFileIcon;
    private Image imageFileLargeIcon;

    // 剪贴板数据格式（用于S3文件复制粘贴）
    private static final DataFormat S3_COPY_FORMAT = new DataFormat("application/x-s3-file-copy");

    // 复制粘贴状态
    private Stage copyProgressStage;
    private ProgressBar copyProgressBar;
    private Label copyProgressLabel;
    private Label copyProgressDetailLabel;
    private AtomicBoolean copyCancelled = new AtomicBoolean(false);

    // 重命名编辑状态（参考 TableObjectsView 的"已选中再单击进入重命名"交互）
    private javafx.animation.Timeline singleClickTimer;
    private FileItem clickedBeforeItem;
    private FileItem editingItem;
    private javafx.stage.Popup iconEditPopup;
    private TextField iconEditField;

    public S3FileBrowserPane(ConnectionConfig config) {
        this.config = config;
        this.isAliyunOSS = config.getType() == ConnectType.ALIYUN_OSS;

        loadIcons();
        initializeUI();
        switchViewMode(currentViewMode);
        loadBuckets();

        // 添加全局快捷键支持（Ctrl+C 复制、Ctrl+V 粘贴）
        setupKeyboardShortcuts();
    }

    /**
     * 更新连接配置引用（编辑保存后调用，使已打开的标签页立即生效新配置）。
     * type 不会改变（S3 还是 S3、OSS 还是 OSS），因此 isAliyunOSS 无需重新计算。
     */
    public void updateConfig(ConnectionConfig newConfig) {
        this.config = newConfig;
    }

    /**
     * 设置全局键盘快捷键
     */
    private void setupKeyboardShortcuts() {
        KeyCodeCombination copyCombo = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
        KeyCodeCombination pasteCombo = new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);

        // 在场景中注册快捷键
        this.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.getAccelerators().put(copyCombo, this::handleCopy);
                newScene.getAccelerators().put(pasteCombo, this::handlePaste);
            }
        });
    }

    private void loadIcons() {
        try { folderIcon = new Image(getClass().getResourceAsStream("/images/connect/folder.png")); } catch (Exception e) { folderIcon = null; }
        try { bucketIcon = new Image(getClass().getResourceAsStream(isAliyunOSS ? "/images/connect/aliyun_oss.png" : "/images/connect/s3.png")); } catch (Exception e) { bucketIcon = null; }

        // 大图标版本（48x48）
        try { folderLargeIcon = new Image(getClass().getResourceAsStream("/images/connect/folder.png"), 48, 48, true, true); } catch (Exception e) { folderLargeIcon = null; }
        try { bucketLargeIcon = new Image(getClass().getResourceAsStream(isAliyunOSS ? "/images/connect/aliyun_oss.png" : "/images/connect/s3.png"), 48, 48, true, true); } catch (Exception e) { bucketLargeIcon = null; }

        // 文件图标
        fileIcon = createFileIcon(16);
        fileLargeIcon = createFileIcon(48);

        // 图片文件图标（带图片标识的文件图标）
        imageFileIcon = createImageFileIcon(16);
        imageFileLargeIcon = createImageFileIcon(48);
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

    private Image createImageFileIcon(int size) {
        javafx.scene.layout.Pane pane = new javafx.scene.layout.Pane();
        pane.setPrefSize(size, size);

        double s = size;
        // 文件主体
        javafx.scene.shape.Rectangle body = new javafx.scene.shape.Rectangle(s * 0.15, s * 0.05, s * 0.55, s * 0.9);
        body.setFill(Color.WHITE);
        body.setStroke(Color.valueOf("#4CAF50"));
        body.setStrokeWidth(Math.max(1, size * 0.04));
        body.setArcWidth(s * 0.06);
        body.setArcHeight(s * 0.06);

        // 折角
        javafx.scene.shape.Polygon ear = new javafx.scene.shape.Polygon();
        ear.getPoints().addAll(s * 0.55, s * 0.05, s * 0.55, s * 0.25, s * 0.85, s * 0.25);
        ear.setFill(Color.valueOf("#C8E6C9"));
        ear.setStroke(Color.valueOf("#4CAF50"));
        ear.setStrokeWidth(Math.max(1, size * 0.04));

        // 图片标识：小山和太阳
        // 太阳
        javafx.scene.shape.Circle sun = new javafx.scene.shape.Circle(s * 0.32, s * 0.35, s * 0.06);
        sun.setFill(Color.valueOf("#FFC107"));
        // 山
        javafx.scene.shape.Polygon mountain = new javafx.scene.shape.Polygon();
        mountain.getPoints().addAll(
                s * 0.22, s * 0.7,
                s * 0.38, s * 0.42,
                s * 0.54, s * 0.7
        );
        mountain.setFill(Color.valueOf("#66BB6A"));
        // 小山
        javafx.scene.shape.Polygon smallMountain = new javafx.scene.shape.Polygon();
        smallMountain.getPoints().addAll(
                s * 0.4, s * 0.7,
                s * 0.52, s * 0.5,
                s * 0.62, s * 0.7
        );
        smallMountain.setFill(Color.valueOf("#81C784"));

        pane.getChildren().addAll(body, ear, sun, mountain, smallMountain);

        javafx.scene.SnapshotParameters sp = new javafx.scene.SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        return pane.snapshot(sp, null);
    }

    /**
     * 判断文件名是否为图片
     */
    private boolean isImageFile(String name) {
        if (name == null) return false;
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == name.length() - 1) return false;
        String ext = name.substring(dotIdx + 1).toLowerCase();
        return IMAGE_EXTENSIONS.contains(ext);
    }

    /**
     * 判断文件名是否为 Markdown 文件
     */
    private boolean isMarkdownFile(String name) {
        if (name == null) return false;
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == name.length() - 1) return false;
        String ext = name.substring(dotIdx + 1).toLowerCase();
        return MARKDOWN_EXTENSIONS.contains(ext);
    }

    private Image getIconForItem(FileItem item, boolean large) {
        if (item.isDirectory()) {
            if (item.isBucket()) {
                return large ? bucketLargeIcon : bucketIcon;
            }
            return large ? folderLargeIcon : folderIcon;
        }
        // 图片文件使用图片图标
        if (isImageFile(item.getDisplayName())) {
            return large ? imageFileLargeIcon : imageFileIcon;
        }
        return large ? fileLargeIcon : fileIcon;
    }

    private void initializeUI() {
        // 当前路径输入框（可编辑，回车跳转；顶到视图切换按钮，始终显示文本框样式）
        currentPathField = new TextField("/");
        currentPathField.setPrefHeight(25);
        currentPathField.setMinWidth(0);
        currentPathField.setPrefWidth(0);
        currentPathField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(currentPathField, Priority.ALWAYS);
        currentPathField.setStyle("-fx-font-size: 12px; -fx-text-fill: #333; -fx-background-color: white; -fx-background-insets: 0; -fx-background-radius: 0; -fx-padding: 2 6; -fx-border-color: #3399ff; -fx-border-width: 1; -fx-border-insets: 0; -fx-border-radius: 0;");
        currentPathField.setTooltip(new Tooltip("点击编辑路径，回车进入目录"));
        // 获得焦点：全选文本；失去焦点：还原当前路径
        currentPathField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                Platform.runLater(currentPathField::selectAll);
            } else {
                updatePathLabel();
            }
        });
        // 回车：跳转到输入路径
        currentPathField.setOnAction(e -> {
            String input = currentPathField.getText();
            pathBar.requestFocus(); // 转移焦点以触发失焦恢复显示
            navigateToPath(input);
        });

        // 顶部：路径导航栏
        pathBar = new HBox(8);
        pathBar.setAlignment(Pos.CENTER_LEFT);
        pathBar.setPadding(new Insets(6, 10, 6, 10));
        pathBar.setStyle("-fx-background-color: #f8f8f8; -fx-border-color: #dddddd; -fx-border-width: 0 0 1 0;");

        pathBar.getChildren().add(currentPathField);

        // 视图切换按钮
        ToggleGroup viewToggleGroup = new ToggleGroup();

        iconViewBtn = new ToggleButton("⊞");
        iconViewBtn.setTooltip(new Tooltip("图标视图"));
        iconViewBtn.setToggleGroup(viewToggleGroup);
        iconViewBtn.setSelected(true);
        iconViewBtn.setStyle("-fx-font-size: 14px; -fx-padding: 2 6; -fx-background-radius: 4 0 0 4; -fx-border-radius: 4 0 0 4;");
        iconViewBtn.setOnAction(e -> switchViewMode(ViewMode.ICON));

        listViewBtn = new ToggleButton("≡");
        listViewBtn.setTooltip(new Tooltip("列表视图"));
        listViewBtn.setToggleGroup(viewToggleGroup);
        listViewBtn.setSelected(false);
        listViewBtn.setStyle("-fx-font-size: 14px; -fx-padding: 2 6; -fx-background-radius: 0; -fx-border-radius: 0;");
        listViewBtn.setOnAction(e -> switchViewMode(ViewMode.LIST));

        columnViewBtn = new ToggleButton("⫶");
        columnViewBtn.setTooltip(new Tooltip("列视图（多级目录）"));
        columnViewBtn.setToggleGroup(viewToggleGroup);
        columnViewBtn.setSelected(false);
        columnViewBtn.setStyle("-fx-font-size: 14px; -fx-padding: 2 6; -fx-background-radius: 0 4 4 0; -fx-border-radius: 0 4 4 0;");
        columnViewBtn.setOnAction(e -> switchViewMode(ViewMode.COLUMN));

        pathBar.getChildren().addAll(iconViewBtn, listViewBtn, columnViewBtn);

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

        createBucketBtn = new Button("+ 新建Bucket");
        createBucketBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8; -fx-text-fill: #07c160; -fx-border-color: #07c160; -fx-border-radius: 4; -fx-background-radius: 4;");
        createBucketBtn.setOnAction(e -> handleCreateBucket());
        pathBar.getChildren().add(createBucketBtn);

        setTop(pathBar);

        // 底部：状态栏（连接状态 + 主机信息）
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

        connLabel = new Label(config.getName() + " (" + (config.getEndpoint() != null ? config.getEndpoint() : config.getRegion()) + ")");
        connLabel.setStyle("-fx-font-size: 11px;");
        statusBar.getChildren().add(connLabel);

        setBottom(statusBar);

        // 中心区域：TabPane（第一个 Tab 为文件浏览，后续为 markdown 编辑器）
        editorTabPane = new TabPane();
        editorTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        browseTab = new Tab("文件浏览");
        browseTab.setClosable(false);
        editorTabPane.getTabs().add(browseTab);
        setCenter(editorTabPane);

        initListView();
        initIconView();
        initColumnView();
    }

    private void initListView() {
        fileTable = new TableView<>();
        fileTable.setItems(fileData);
        fileTable.setStyle("-fx-font-size: 12px;");
        fileTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        fileTable.setRowFactory(tv -> {
            TableRow<FileItem> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (row.isEmpty()) return;
                if (event.getButton() == MouseButton.SECONDARY) {
                    // 右键时先选中该行再弹出菜单
                    if (!fileTable.getSelectionModel().isSelected(row.getIndex())) {
                        fileTable.getSelectionModel().select(row.getItem());
                    }
                    selectedItem = row.getItem();
                    return;
                }
                if (event.getButton() != MouseButton.PRIMARY) return;

                // 双击：取消重命名定时器，执行打开
                if (event.getClickCount() == 2) {
                    if (singleClickTimer != null) {
                        singleClickTimer.stop();
                        singleClickTimer = null;
                    }
                    if (editingItem != null) {
                        cancelListEdit();
                    }
                    handleDoubleClick(row.getItem());
                    event.consume();
                    return;
                }

                // 单击：判断是否"已选中再点击"以进入重命名编辑
                if (event.getClickCount() == 1 && !event.isControlDown() && !event.isShiftDown()) {
                    int rowIdx = row.getIndex();
                    boolean wasAlreadySelected = clickedBeforeItem != null
                            && editingItem == null
                            && fileTable.getSelectionModel().getSelectedIndices().size() == 1
                            && fileTable.getSelectionModel().isSelected(rowIdx);
                    if (wasAlreadySelected) {
                        final int editRow = rowIdx;
                        if (singleClickTimer != null) {
                            singleClickTimer.stop();
                        }
                        singleClickTimer = new javafx.animation.Timeline(
                                new javafx.animation.KeyFrame(javafx.util.Duration.millis(300), ae -> {
                                    if (editingItem == null
                                            && fileTable.getSelectionModel().getSelectedIndices().size() == 1
                                            && fileTable.getSelectionModel().isSelected(editRow)) {
                                        startListEdit(editRow);
                                    }
                                    singleClickTimer = null;
                                }));
                        singleClickTimer.play();
                        event.consume();
                        return;
                    }
                }

                // 正常单击：记录主选中项
                selectedItem = row.getItem();
            });
            // 拖拽下载：从列表行拖出 -> 下载到临时目录后放入剪贴板
            row.setOnDragDetected(event -> {
                if (row.isEmpty()) return;
                FileItem item = row.getItem();
                if (!item.isDirectory()) {
                    File tempFile = downloadToTemp(item);
                    if (tempFile != null) {
                        Dragboard db = row.startDragAndDrop(TransferMode.COPY);
                        ClipboardContent content = new ClipboardContent();
                        content.putFiles(Collections.singletonList(tempFile));
                        db.setContent(content);
                    }
                }
                event.consume();
            });
            // 拖拽上传：拖到行上 -> 接受文件
            row.setOnDragOver(event -> {
                if (currentBucket != null && event.getDragboard().hasFiles()) {
                    event.acceptTransferModes(TransferMode.COPY);
                }
                event.consume();
            });
            row.setOnDragDropped(event -> {
                Dragboard db = event.getDragboard();
                boolean success = false;
                if (currentBucket != null && db.hasFiles()) {
                    uploadLocalFiles(db.getFiles());
                    success = true;
                }
                event.setDropCompleted(success);
                event.consume();
            });
            return row;
        });

        // 整个表格也支持拖拽上传
        fileTable.setOnDragOver(event -> {
            if (currentBucket != null && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        fileTable.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (currentBucket != null && db.hasFiles()) {
                uploadLocalFiles(db.getFiles());
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });

        // 名称列（图标 + 名，支持内联重命名编辑）
        TableColumn<FileItem, String> nameCol = new TableColumn<>("名称");
        nameCol.setEditable(true);
        nameCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getDisplayName()));
        nameCol.setCellFactory(col -> new TableCell<FileItem, String>() {
            private TextField editField;

            @Override
            public void startEdit() {
                super.startEdit();
                if (isEmpty()) return;
                final FileItem item = getTableView().getItems().get(getIndex());
                editingItem = item;
                editField = new TextField(item.getDisplayName());
                editField.setStyle("-fx-padding: 0 4; -fx-font-size: 12px; -fx-background-color: white; -fx-border-color: #3399ff; -fx-border-radius: 0; -fx-background-radius: 0;");

                editField.setOnAction(e -> commitRenameFromField(editField.getText()));
                editField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
                    if (!isNowFocused && editingItem == item) {
                        commitRenameFromField(editField.getText());
                    }
                });
                editField.setOnKeyReleased(e -> {
                    if (e.getCode() == KeyCode.ESCAPE) {
                        cancelListEdit();
                    }
                });

                // 保留图标 + TextField
                HBox box = new HBox(6);
                box.setAlignment(Pos.CENTER_LEFT);
                ImageView iv = new ImageView(getIconForItem(item, false));
                iv.setFitWidth(16);
                iv.setFitHeight(16);
                iv.setPreserveRatio(true);
                box.getChildren().add(iv);
                box.getChildren().add(editField);
                setText(null);
                setGraphic(box);
                editField.selectAll();
                Platform.runLater(() -> editField.requestFocus());
            }

            @Override
            public void commitEdit(String newValue) {
                // 实际提交由 commitRenameFromField 处理，这里不调用 super 以避免数据模型冲突
            }

            @Override
            public void cancelEdit() {
                super.cancelEdit();
                editingItem = null;
                fileTable.setEditable(false);
                updateItem(getItem(), false);
            }

            private void commitRenameFromField(String newName) {
                if (editingItem == null) {
                    cancelListEdit();
                    return;
                }
                FileItem item = editingItem;
                // 交给 commitRename：校验后由 onCancel（cancelListEdit）恢复 UI，再异步重命名
                // 注意：不能在此提前置 editingItem=null，否则 commitRename 的守卫会直接返回
                commitRename(item, newName, () -> cancelListEdit(), () -> refresh());
            }

            @Override
            protected void updateItem(String name, boolean empty) {
                super.updateItem(name, empty);
                if (empty || name == null) {
                    setText(null);
                    setGraphic(null);
                } else if (!isEditing()) {
                    setText(name);
                    FileItem item = getTableView().getItems().get(getIndex());
                    ImageView iv = new ImageView();
                    iv.setFitWidth(16);
                    iv.setFitHeight(16);
                    iv.setImage(getIconForItem(item, false));
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
        typeCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().isDirectory() ? (data.getValue().isBucket() ? "Bucket" : "目录") : (isImageFile(data.getValue().getDisplayName()) ? "图片" : "文件")));
        typeCol.setPrefWidth(80);

        fileTable.getColumns().addAll(nameCol, sizeCol, modifiedCol, typeCol);
        fileTable.setContextMenu(createContextMenu());

        // 记录点击前已选中的项（用于判断"已选中再点击"进入重命名编辑）
        fileTable.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 1) {
                var selItems = fileTable.getSelectionModel().getSelectedItems();
                if (selItems.size() == 1 && selItems.get(0) != null) {
                    clickedBeforeItem = selItems.get(0);
                } else {
                    clickedBeforeItem = null;
                }
            }
        });
    }

    private void initIconView() {
        iconFlowPane = new FlowPane();
        iconFlowPane.setHgap(8);
        iconFlowPane.setVgap(8);
        iconFlowPane.setPadding(new Insets(12));
        iconFlowPane.setStyle("-fx-background-color: white;");

        // 框选矩形（不参与布局，覆盖在图标上方）
        selectionRect = new Rectangle();
        selectionRect.setFill(Color.rgb(51, 153, 255, 0.15));
        selectionRect.setStroke(Color.rgb(51, 153, 255, 0.8));
        selectionRect.setStrokeWidth(1);
        selectionRect.setManaged(false);
        selectionRect.setMouseTransparent(true);
        selectionRect.setVisible(false);

        iconScrollPane = new ScrollPane(iconFlowPane);
        iconScrollPane.setFitToWidth(true);
        iconScrollPane.setFitToHeight(true);
        iconScrollPane.setStyle("-fx-background-color: white;");
        iconScrollPane.setContextMenu(createContextMenu());

        // 框选：在图标视图空白处按下鼠标开始框选
        // 绑定到 ScrollPane 的内容视口区域，使用事件过滤器（在子节点处理之前捕获）
        iconScrollPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            // 判断是否点击在空白区域：沿着事件链向上查找，找到的第一个 VBox 就是图标项，
            // 如果一直到 iconFlowPane 都没找到 VBox，说明是在空白处点击
            javafx.scene.Node targetNode = e.getTarget() instanceof javafx.scene.Node ? (javafx.scene.Node) e.getTarget() : null;
            boolean clickedOnIconBox = false;
            while (targetNode != null && targetNode != iconScrollPane) {
                if (targetNode instanceof VBox && targetNode.getProperties().containsKey("fileItem")) {
                    clickedOnIconBox = true;
                    break;
                }
                if (targetNode == iconFlowPane) break;
                targetNode = targetNode.getParent();
            }
            if (clickedOnIconBox) return; // 点在图标上，交给图标自身的点击处理

            // 点在空白处：开始框选
            // 将鼠标坐标从 ScrollPane 坐标系转换到 iconFlowPane 坐标系
            java.awt.geom.Point2D flowPoint = convertToFlowPane(e.getSceneX(), e.getSceneY());
            if (flowPoint == null) return;

            isRubberBandActive = true;
            rubberBandAppendMode = e.isControlDown() || e.isShiftDown();
            rubberBandStartX = flowPoint.getX();
            rubberBandStartY = flowPoint.getY();
            selectionRect.setX(rubberBandStartX);
            selectionRect.setY(rubberBandStartY);
            selectionRect.setWidth(0);
            selectionRect.setHeight(0);
            selectionRect.setVisible(true);

            // 保存之前的选中状态（追加模式下）
            rubberBandPreSelectedItems.clear();
            if (rubberBandAppendMode) {
                rubberBandPreSelectedItems.addAll(selectedItems);
            } else {
                clearIconSelection();
            }
            e.consume();
        });

        iconScrollPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, e -> {
            if (!isRubberBandActive) return;

            java.awt.geom.Point2D flowPoint = convertToFlowPane(e.getSceneX(), e.getSceneY());
            if (flowPoint == null) return;
            double fx = flowPoint.getX();
            double fy = flowPoint.getY();

            double x = Math.min(rubberBandStartX, fx);
            double y = Math.min(rubberBandStartY, fy);
            double w = Math.abs(fx - rubberBandStartX);
            double h = Math.abs(fy - rubberBandStartY);
            selectionRect.setX(x);
            selectionRect.setY(y);
            selectionRect.setWidth(w);
            selectionRect.setHeight(h);
            updateRubberBandSelection(x, y, w, h);

            // 自动滚动：检查是否靠近 ScrollPane 边缘
            updateAutoScrollVelocity(e.getSceneX(), e.getSceneY());
            startAutoScrollIfNeeded();

            e.consume();
        });

        iconScrollPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (!isRubberBandActive) return;
            isRubberBandActive = false;
            selectionRect.setVisible(false);
            stopAutoScroll();
            e.consume();
        });

        // 拖拽上传：拖到图标视图（FlowPane 和 ScrollPane 均支持）
        setupDragUpload(iconFlowPane);
        setupDragUpload(iconScrollPane);
    }

    /**
     * 将场景坐标转换为 iconFlowPane 内部的坐标
     */
    private java.awt.geom.Point2D convertToFlowPane(double sceneX, double sceneY) {
        try {
            javafx.geometry.Point2D localPoint = iconFlowPane.sceneToLocal(sceneX, sceneY, true);
            if (localPoint == null) return null;
            return new java.awt.geom.Point2D.Double(localPoint.getX(), localPoint.getY());
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 计算拖拽点是否靠近 ScrollPane 边缘，返回自动滚动速度
     */
    private void updateAutoScrollVelocity(double sceneX, double sceneY) {
        javafx.geometry.Point2D spPoint = iconScrollPane.sceneToLocal(sceneX, sceneY, true);
        if (spPoint == null) { autoScrollDX = 0; autoScrollDY = 0; return; }
        double spx = spPoint.getX();
        double spy = spPoint.getY();
        double spw = iconScrollPane.getViewportBounds().getWidth();
        double sph = iconScrollPane.getViewportBounds().getHeight();
        double edge = 25; // 边缘触发区域像素
        double speed = 8; // 滚动速度像素/帧
        autoScrollDX = 0;
        autoScrollDY = 0;
        if (spx < edge) autoScrollDX = -speed * (1 - spx / edge);
        else if (spx > spw - edge) autoScrollDX = speed * (1 - (spw - spx) / edge);
        if (spy < edge) autoScrollDY = -speed * (1 - spy / edge);
        else if (spy > sph - edge) autoScrollDY = speed * (1 - (sph - spy) / edge);
    }

    private void startAutoScrollIfNeeded() {
        if (autoScrollTimer != null) return;
        if (Math.abs(autoScrollDX) < 0.5 && Math.abs(autoScrollDY) < 0.5) return;
        autoScrollTimer = new javafx.animation.AnimationTimer() {
            @Override public void handle(long now) {
                if (!isRubberBandActive) { stop(); autoScrollTimer = null; return; }
                if (Math.abs(autoScrollDX) < 0.5 && Math.abs(autoScrollDY) < 0.5) return;
                // 直接用当前选择矩形的右下端点作为"当前"点继续扩展
                double rx = selectionRect.getX();
                double ry = selectionRect.getY();
                double rw = selectionRect.getWidth();
                double rh = selectionRect.getHeight();
                // 判断当前是往哪个方向拖（起点固定在 rubberBandStartX/Y）
                double curX, curY;
                if (rw <= 0 || rx == rubberBandStartX) curX = rx + rw; else curX = rx;
                if (rh <= 0 || ry == rubberBandStartY) curY = ry + rh; else curY = ry;
                // 根据自动滚动方向确定端点的移动方向
                double dx = autoScrollDX;
                double dy = autoScrollDY;
                // 保持端点相对于起点的方向
                if (curX < rubberBandStartX) dx = -Math.abs(dx);
                else if (curX > rubberBandStartX) dx = Math.abs(dx);
                else dx = 0; // 尚未确定横向方向，暂不移动
                if (curY < rubberBandStartY) dy = -Math.abs(dy);
                else if (curY > rubberBandStartY) dy = Math.abs(dy);
                else dy = 0;
                curX += dx;
                curY += dy;
                // 限制端点不能超出 FlowPane 范围
                double maxX = iconFlowPane.getBoundsInLocal().getWidth();
                double maxY = iconFlowPane.getBoundsInLocal().getHeight();
                curX = Math.max(0, Math.min(maxX, curX));
                curY = Math.max(0, Math.min(maxY, curY));
                // 先计算并执行 ScrollPane 滚动（比例值）
                double vmax = iconScrollPane.getVmax();
                double hmax = iconScrollPane.getHmax();
                double hval = iconScrollPane.getHvalue();
                double vval = iconScrollPane.getVvalue();
                double contentWidth = maxX;
                double contentHeight = maxY;
                double viewW = iconScrollPane.getViewportBounds().getWidth();
                double viewH = iconScrollPane.getViewportBounds().getHeight();
                if (contentWidth > viewW && hmax > 0) {
                    double dh = autoScrollDX / (contentWidth - viewW);
                    iconScrollPane.setHvalue(Math.max(0, Math.min(hmax, hval + dh)));
                }
                if (contentHeight > viewH && vmax > 0) {
                    double dv = autoScrollDY / (contentHeight - viewH);
                    iconScrollPane.setVvalue(Math.max(0, Math.min(vmax, vval + dv)));
                }
                // 更新选择矩形和选中项
                double nx = Math.min(rubberBandStartX, curX);
                double ny = Math.min(rubberBandStartY, curY);
                double nw = Math.abs(curX - rubberBandStartX);
                double nh = Math.abs(curY - rubberBandStartY);
                selectionRect.setX(nx);
                selectionRect.setY(ny);
                selectionRect.setWidth(nw);
                selectionRect.setHeight(nh);
                updateRubberBandSelection(nx, ny, nw, nh);
            }
        };
        autoScrollTimer.start();
    }

    private void stopAutoScroll() {
        if (autoScrollTimer != null) {
            autoScrollTimer.stop();
            autoScrollTimer = null;
        }
        autoScrollDX = 0;
        autoScrollDY = 0;
    }

    /**
     * 为节点绑定拖拽上传：从本地文件系统拖入文件即上传到当前 bucket/prefix
     */
    private void setupDragUpload(javafx.scene.Node node) {
        node.setOnDragOver(event -> {
            if (currentBucket != null && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        node.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (currentBucket != null && db.hasFiles()) {
                uploadLocalFiles(db.getFiles());
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    // ==================== 列视图（macOS Column View）====================

    private void initColumnView() {
        columnContainer = new HBox();
        columnContainer.setStyle("-fx-background-color: white;");

        columnScrollPane = new ScrollPane(columnContainer);
        columnScrollPane.setFitToHeight(true);
        columnScrollPane.setFitToWidth(false);
        columnScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        columnScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        columnScrollPane.setStyle("-fx-background-color: white;");
        columnScrollPane.setContextMenu(createContextMenu());
        setupDragUpload(columnContainer);
        setupDragUpload(columnScrollPane);
    }

    /**
     * 根据当前 currentBucket/currentPrefix 和 fileData 重建列视图（单列起始）
     */
    private void rebuildColumnView() {
        columnContainer.getChildren().clear();
        columnListViews.clear();
        columnItems.clear();
        columnBuckets.clear();
        columnPrefixes.clear();

        ObservableList<FileItem> colData = FXCollections.observableArrayList(fileData);
        columnBuckets.add(currentBucket);
        columnPrefixes.add(currentPrefix != null ? currentPrefix : "");
        columnItems.add(colData);
        ListView<FileItem> lv = createColumnListView(0);
        columnListViews.add(lv);
        columnContainer.getChildren().add(lv);
    }

    /**
     * 创建一列 ListView
     */
    private ListView<FileItem> createColumnListView(int colIndex) {
        ListView<FileItem> lv = new ListView<>(columnItems.get(colIndex));
        lv.setPrefWidth(220);
        lv.setMinWidth(180);
        lv.setMaxWidth(220);
        lv.setStyle("-fx-background-color: white; -fx-background-insets: 0; -fx-padding: 0; -fx-border-color: transparent #e5e5e5 transparent transparent; -fx-border-width: 0 1 0 0; -fx-hbar-policy: NEVER;");

        lv.setCellFactory(list -> new ListCell<FileItem>() {
            {
                setStyle("-fx-padding: 4 8;");
                // 右键时先选中该单元格再弹出菜单
                setOnMousePressed(e -> {
                    if (e.getButton() == MouseButton.SECONDARY && !isEmpty()) {
                        lv.getSelectionModel().select(getIndex());
                    }
                });
            }

            @Override
            protected void updateItem(FileItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    HBox row = new HBox(6);
                    row.setAlignment(Pos.CENTER_LEFT);
                    row.setMaxWidth(Double.MAX_VALUE);
                    ImageView iv = new ImageView(getIconForItem(item, false));
                    iv.setFitWidth(16);
                    iv.setFitHeight(16);
                    Label name = new Label(item.getDisplayName());
                    name.setStyle("-fx-font-size: 12px; -fx-text-fill: #333;");
                    name.setMaxWidth(Double.MAX_VALUE);
                    name.setWrapText(false);
                    name.setTextOverrun(OverrunStyle.ELLIPSIS);
                    HBox.setHgrow(name, Priority.ALWAYS);
                    row.getChildren().addAll(iv, name);
                    if (item.isDirectory()) {
                        Label arrow = new Label("›");
                        arrow.setStyle("-fx-text-fill: #999; -fx-font-size: 16px;");
                        row.getChildren().add(arrow);
                    }
                    setGraphic(row);
                    setText(null);
                }
            }
        });

        lv.getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> {
            if (val != null) {
                selectedItem = val;
                onColumnItemSelected(val, colIndex);
            }
        });

        lv.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                FileItem sel = lv.getSelectionModel().getSelectedItem();
                if (sel != null) handleDoubleClick(sel);
            }
        });

        // 拖拽上传
        lv.setOnDragOver(event -> {
            if (currentBucket != null && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        lv.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (currentBucket != null && db.hasFiles()) {
                uploadLocalFiles(db.getFiles());
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });

        // 拖拽下载
        lv.setOnDragDetected(event -> {
            FileItem sel = lv.getSelectionModel().getSelectedItem();
            if (sel != null && !sel.isDirectory()) {
                File tempFile = downloadToTemp(sel);
                if (tempFile != null) {
                    Dragboard db = lv.startDragAndDrop(TransferMode.COPY);
                    ClipboardContent content = new ClipboardContent();
                    content.putFiles(Collections.singletonList(tempFile));
                    db.setContent(content);
                }
            }
            event.consume();
        });

        lv.setContextMenu(createContextMenu());
        return lv;
    }

    /**
     * 列内选中项变化：截断右侧列，若为目录则异步加载子目录到新列
     */
    private void onColumnItemSelected(FileItem item, int colIndex) {
        truncateColumns(colIndex + 1);
        if (item.isDirectory()) {
            if (item.isBucket()) {
                // 点击 Bucket：新列加载该 Bucket 根目录
                loadColumnAsync(item.getName(), "");
            } else {
                // 点击文件夹：新列加载子目录
                loadColumnAsync(currentBucket, item.getKey());
            }
        }
        updatePathFromColumns();
    }

    /**
     * 根据列视图的状态更新路径输入框
     */
    private void updatePathFromColumns() {
        if (columnBuckets.isEmpty()) return;
        String bucket = columnBuckets.get(columnBuckets.size() - 1);
        String prefix = columnPrefixes.get(columnPrefixes.size() - 1);
        currentBucket = bucket;
        currentPrefix = prefix;
        if (bucket == null) {
            currentPathField.setText("/");
        } else {
            currentPathField.setText("/" + bucket + "/" + (prefix != null ? prefix : ""));
        }
    }

    /**
     * 截断列：只保留前 keepCount 列
     */
    private void truncateColumns(int keepCount) {
        while (columnListViews.size() > keepCount) {
            int last = columnListViews.size() - 1;
            columnContainer.getChildren().remove(columnListViews.get(last));
            columnListViews.remove(last);
            columnItems.remove(last);
            columnBuckets.remove(last);
            columnPrefixes.remove(last);
        }
    }

    /**
     * 异步加载并添加为新列
     * @param bucket null 表示加载 Bucket 列表；否则加载指定 Bucket 的 prefix 内容
     */
    private void loadColumnAsync(String bucket, String prefix) {
        new Thread(() -> {
            try {
                List<FileItem> items = new ArrayList<>();
                if (bucket == null) {
                    // 加载 Bucket 列表
                    List<String> buckets;
                    if (isAliyunOSS) {
                        buckets = OssService.listBuckets(config);
                    } else {
                        buckets = S3Service.listBuckets(config);
                    }
                    for (String bucketName : buckets) {
                        FileItem item = new FileItem();
                        item.setName(bucketName);
                        item.setKey(bucketName);
                        item.setDirectory(true);
                        item.setBucket(true);
                        items.add(item);
                    }
                } else {
                    // 加载 Bucket 内容
                    List<?> objects;
                    if (isAliyunOSS) {
                        objects = OssService.listObjects(config, bucket, prefix);
                    } else {
                        objects = S3Service.listObjects(config, bucket, prefix);
                    }
                    for (Object obj : objects) {
                        FileItem item = new FileItem();
                        if (isAliyunOSS) {
                            OssService.OssObjectInfo ossObj = (OssService.OssObjectInfo) obj;
                            item.setName(ossObj.getDisplayName());
                            item.setKey(ossObj.getKey());
                            item.setDirectory(ossObj.isDirectory());
                            item.setSize(ossObj.getSize());
                            item.setLastModified(ossObj.getLastModified() != null ? ossObj.getLastModified().toString() : "");
                            item.setBucket(false);
                        } else {
                            S3Service.S3ObjectInfo s3Obj = (S3Service.S3ObjectInfo) obj;
                            item.setName(s3Obj.getDisplayName());
                            item.setKey(s3Obj.getKey());
                            item.setDirectory(s3Obj.isDirectory());
                            item.setSize(s3Obj.getSize());
                            item.setLastModified(s3Obj.getLastModified() != null ? s3Obj.getLastModified().toString() : "");
                            item.setBucket(false);
                        }
                        items.add(item);
                    }
                }
                Platform.runLater(() -> {
                    addColumn(bucket, prefix != null ? prefix : "", items);
                    stateLabel.setText(items.size() + " 个条目");
                });
            } catch (Exception e) {
                Platform.runLater(() -> stateLabel.setText("错误: " + e.getMessage()));
            }
        }, "S3-ColumnLoad").start();
    }

    /**
     * 添加新列并滚动到最右侧
     */
    private void addColumn(String bucket, String prefix, List<FileItem> items) {
        ObservableList<FileItem> colData = FXCollections.observableArrayList(items);
        columnBuckets.add(bucket);
        columnPrefixes.add(prefix);
        columnItems.add(colData);
        int colIndex = columnListViews.size();
        ListView<FileItem> lv = createColumnListView(colIndex);
        columnListViews.add(lv);
        columnContainer.getChildren().add(lv);
        updatePathFromColumns();

        // 监听容器宽度变化，布局完成后自动滚到最右
        columnContainer.widthProperty().addListener(new javafx.beans.value.ChangeListener<Number>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends Number> obs, Number oldW, Number newW) {
                if (newW.doubleValue() > oldW.doubleValue()) {
                    columnScrollPane.setHvalue(1.0);
                    obs.removeListener(this);
                }
            }
        });
    }

    private void switchViewMode(ViewMode mode) {
        currentViewMode = mode;

        VBox centerBox = new VBox();

        if (mode == ViewMode.ICON) {
            rebuildIconView();
            centerBox.getChildren().add(iconScrollPane);
            VBox.setVgrow(iconScrollPane, Priority.ALWAYS);
        } else if (mode == ViewMode.COLUMN) {
            rebuildColumnView();
            centerBox.getChildren().add(columnScrollPane);
            VBox.setVgrow(columnScrollPane, Priority.ALWAYS);
        } else {
            centerBox.getChildren().add(fileTable);
            VBox.setVgrow(fileTable, Priority.ALWAYS);
        }

        // 切换浏览视图时回到浏览 Tab
        browseTab.setContent(centerBox);
        editorTabPane.getSelectionModel().select(browseTab);
    }

    private void rebuildIconView() {
        iconFlowPane.getChildren().clear();

        for (FileItem item : fileData) {
            VBox iconBox = createIconBox(item);
            iconFlowPane.getChildren().add(iconBox);
        }
        // 框选矩形最后添加，确保渲染在所有图标之上（setManaged(false) 不参与 FlowPane 布局）
        iconFlowPane.getChildren().add(selectionRect);
    }

    private VBox createIconBox(FileItem item) {
        VBox box = new VBox(4);
        box.setAlignment(Pos.TOP_CENTER);
        box.setPrefWidth(90);
        box.setPadding(new Insets(6, 4, 6, 4));
        box.setStyle("-fx-background-color: transparent; -fx-background-radius: 6; -fx-cursor: hand; -fx-border-color: transparent; -fx-border-width: 1; -fx-border-radius: 6; -fx-border-insets: 0;");
        box.getProperties().put("fileItem", item);

        // 图标
        ImageView iconView = new ImageView();
        iconView.setImage(getIconForItem(item, true));
        iconView.setFitWidth(48);
        iconView.setFitHeight(48);
        iconView.setPreserveRatio(true);
        iconView.setSmooth(true);
        box.getChildren().add(iconView);

        // 名称（过长显示省略号"…"，不换行）
        Label nameLabel = new Label(item.getDisplayName());
        nameLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #333; -fx-alignment: CENTER;");
        nameLabel.setWrapText(false);
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        nameLabel.setMaxWidth(82);
        nameLabel.setAlignment(Pos.CENTER);
        box.getChildren().add(nameLabel);

        // 记录点击前已选中的项（用于判断"已选中再点击"进入重命名编辑）
        box.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                clickedBeforeItem = (selectedItems.size() == 1 && selectedItems.contains(item)) ? item : null;
            }
        });

        // 鼠标事件
        box.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                // 双击：取消重命名定时器，执行打开
                if (e.getClickCount() == 2) {
                    if (singleClickTimer != null) {
                        singleClickTimer.stop();
                        singleClickTimer = null;
                    }
                    if (editingItem != null) {
                        cancelIconEdit();
                    }
                    handleDoubleClick(item);
                    e.consume();
                    return;
                }

                boolean ctrl = e.isControlDown();
                boolean shift = e.isShiftDown();

                // 单击：判断是否"已选中再点击"以进入重命名编辑
                boolean wasAlreadySelected = !ctrl && !shift
                        && clickedBeforeItem == item
                        && selectedItems.size() == 1
                        && selectedItems.contains(item)
                        && editingItem == null;
                if (wasAlreadySelected) {
                    final FileItem itemToEdit = item;
                    if (singleClickTimer != null) {
                        singleClickTimer.stop();
                    }
                    singleClickTimer = new javafx.animation.Timeline(
                            new javafx.animation.KeyFrame(javafx.util.Duration.millis(300), ae -> {
                                if (editingItem == null
                                        && selectedItems.size() == 1
                                        && selectedItems.contains(itemToEdit)) {
                                    startIconEdit(itemToEdit);
                                }
                                singleClickTimer = null;
                            }));
                    singleClickTimer.play();
                    e.consume();
                    return;
                }

                // 正常选择逻辑
                boolean append = ctrl || shift;
                if (!append) {
                    clearIconSelection();
                }
                // 追加模式下：如果已选中则取消；未选中则添加
                if (append && selectedItems.contains(item)) {
                    box.setUserData(null);
                    box.setStyle("-fx-background-color: transparent; -fx-background-radius: 6; -fx-cursor: hand; -fx-border-color: transparent; -fx-border-width: 1; -fx-border-radius: 6; -fx-border-insets: 0;");
                    selectedItems.remove(item);
                    if (selectedItem == item) {
                        selectedItem = selectedItems.isEmpty() ? null : selectedItems.iterator().next();
                    }
                } else {
                    selectIconBox(box, item);
                    selectedItems.add(item);
                    selectedItem = item;
                }
            } else if (e.getButton() == MouseButton.SECONDARY) {
                // 右键时：如果当前没选中才选中它（保持多选状态不变，如果已经是选中项则不切换）
                if (!selectedItems.contains(item)) {
                    boolean append = e.isControlDown() || e.isShiftDown();
                    if (!append) {
                        clearIconSelection();
                    }
                    selectIconBox(box, item);
                    selectedItems.add(item);
                    selectedItem = item;
                }
            }
        });

        // 拖拽下载：从图标拖出 -> 下载到临时目录后放入剪贴板
        box.setOnDragDetected(e -> {
            if (!item.isDirectory()) {
                File tempFile = downloadToTemp(item);
                if (tempFile != null) {
                    Dragboard db = box.startDragAndDrop(TransferMode.COPY);
                    ClipboardContent content = new ClipboardContent();
                    content.putFiles(Collections.singletonList(tempFile));
                    db.setContent(content);
                }
            }
            e.consume();
        });

        box.setOnMouseEntered(e -> {
            if (box.getUserData() != "selected") {
                box.setStyle("-fx-background-color: #f0f7ff; -fx-background-radius: 6; -fx-cursor: hand; -fx-border-color: transparent; -fx-border-width: 1; -fx-border-radius: 6; -fx-border-insets: 0;");
            }
        });
        box.setOnMouseExited(e -> {
            if (box.getUserData() != "selected") {
                box.setStyle("-fx-background-color: transparent; -fx-background-radius: 6; -fx-cursor: hand; -fx-border-color: transparent; -fx-border-width: 1; -fx-border-radius: 6; -fx-border-insets: 0;");
            }
        });

        return box;
    }

    private void selectIconBox(VBox box, FileItem item) {
        box.setUserData("selected");
        box.setStyle("-fx-background-color: #cce5ff; -fx-background-radius: 6; -fx-cursor: hand; -fx-border-color: #3399ff; -fx-border-width: 1; -fx-border-radius: 6; -fx-border-insets: 0;");
    }

    private void clearIconSelection() {
        for (var node : iconFlowPane.getChildren()) {
            if (node instanceof VBox box) {
                box.setUserData(null);
                box.setStyle("-fx-background-color: transparent; -fx-background-radius: 6; -fx-cursor: hand; -fx-border-color: transparent; -fx-border-width: 1; -fx-border-radius: 6; -fx-border-insets: 0;");
            }
        }
        selectedItems.clear();
        selectedItem = null;
    }

    /**
     * 框选过程中实时更新选中项：遍历所有图标，将与选区矩形相交的项设为选中
     * 支持追加模式（Ctrl/Shift 按下时）：保留框选之前就已选中的项
     */
    private void updateRubberBandSelection(double x, double y, double w, double h) {
        // 先清除所有视觉选中状态（不清除 selectedItems，下面重新填充）
        for (var node : iconFlowPane.getChildren()) {
            if (node instanceof VBox box) {
                box.setUserData(null);
                box.setStyle("-fx-background-color: transparent; -fx-background-radius: 6; -fx-cursor: hand; -fx-border-color: transparent; -fx-border-width: 1; -fx-border-radius: 6; -fx-border-insets: 0;");
            }
        }
        selectedItems.clear();

        // 追加模式：先恢复之前已选中的项
        if (rubberBandAppendMode && !rubberBandPreSelectedItems.isEmpty()) {
            selectedItems.addAll(rubberBandPreSelectedItems);
            // 重新渲染视觉选中状态
            for (var node : iconFlowPane.getChildren()) {
                if (node instanceof VBox box) {
                    FileItem item = (FileItem) box.getProperties().get("fileItem");
                    if (item != null && rubberBandPreSelectedItems.contains(item)) {
                        selectIconBox(box, item);
                    }
                }
            }
        }

        // 加上/切换本次框选到的项
        for (var node : iconFlowPane.getChildren()) {
            if (node instanceof VBox box) {
                if (box.getBoundsInParent().intersects(x, y, w, h)) {
                    FileItem item = (FileItem) box.getProperties().get("fileItem");
                    if (item != null) {
                        if (!selectedItems.contains(item)) {
                            selectIconBox(box, item);
                            selectedItems.add(item);
                        } else {
                            // 已在集合中，确保视觉选中
                            selectIconBox(box, item);
                        }
                    }
                }
            }
        }

        selectedItem = selectedItems.isEmpty() ? null : selectedItems.iterator().next();
    }

    private ContextMenu createContextMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem openItem = new MenuItem("打开");
        openItem.setOnAction(e -> {
            FileItem selected = getSelectedItem();
            if (selected != null) handleDoubleClick(selected);
        });

        // 预览菜单项（仅图片文件显示）
        MenuItem previewItem = new MenuItem("预览图片");
        previewItem.setOnAction(e -> handlePreview());

        // 编辑菜单项（仅 markdown 文件显示）
        MenuItem editMdItem = new MenuItem("编辑 Markdown");
        editMdItem.setOnAction(e -> {
            FileItem selected = getSelectedItem();
            if (selected != null) openMarkdownEditor(selected);
        });

        // 下载菜单项（仅文件可用）
        MenuItem downloadItem = new MenuItem("下载...");
        downloadItem.setOnAction(e -> {
            FileItem selected = getSelectedItem();
            if (selected != null && !selected.isDirectory()) handleDownload(selected);
        });

        // 复制访问地址（仅文件可用，需配置访问URL）
        MenuItem copyUrlItem = new MenuItem("复制访问地址");
        copyUrlItem.setOnAction(e -> {
            FileItem selected = getSelectedItem();
            if (selected != null && !selected.isDirectory()) handleCopyAccessUrl(selected);
        });

        // 复制菜单项
        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> handleCopy());

        // 粘贴菜单项
        MenuItem pasteItem = new MenuItem("粘贴");
        pasteItem.setOnAction(e -> handlePaste());

        // 创建目录（仅 Bucket 内可用）
        MenuItem mkdirItem = new MenuItem("新建目录");
        mkdirItem.setOnAction(e -> handleCreateDirectory());

        // 上传文件（仅 Bucket 内可用）
        MenuItem uploadItem = new MenuItem("上传文件...");
        uploadItem.setOnAction(e -> handleUploadFiles());

        // 创建文件（仅 Bucket 内可用）
        MenuItem createFileItem = new MenuItem("创建文件");
        createFileItem.setOnAction(e -> handleCreateFile());

        MenuItem deleteItem = new MenuItem("删除");
        deleteItem.setOnAction(e -> handleDelete());

        MenuItem renameItem = new MenuItem("重命名");
        renameItem.setOnAction(e -> handleRename());

        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> refresh());

        Menu viewMenu = new Menu("视图");
        MenuItem iconViewItem = new MenuItem("图标视图");
        iconViewItem.setOnAction(e -> switchViewMode(ViewMode.ICON));
        MenuItem listViewItem = new MenuItem("列表视图");
        listViewItem.setOnAction(e -> switchViewMode(ViewMode.LIST));
        MenuItem columnViewItem = new MenuItem("列视图");
        columnViewItem.setOnAction(e -> switchViewMode(ViewMode.COLUMN));
        viewMenu.getItems().addAll(iconViewItem, listViewItem, columnViewItem);

        menu.getItems().addAll(openItem, previewItem, editMdItem, downloadItem, copyUrlItem, copyItem, pasteItem, new SeparatorMenuItem(),
                mkdirItem, uploadItem, createFileItem, deleteItem, renameItem, new SeparatorMenuItem(), viewMenu, new SeparatorMenuItem(), refreshItem);

        // 右键菜单显示时动态控制各项可见性
        menu.setOnShowing(e -> {
            List<FileItem> selected = getSelectedItems();
            boolean single = selected.size() == 1;
            FileItem first = selected.isEmpty() ? null : selected.get(0);

            openItem.setVisible(single && first != null);
            previewItem.setVisible(single && first != null && !first.isDirectory() && isImageFile(first.getDisplayName()));
            editMdItem.setVisible(single && first != null && !first.isDirectory() && isMarkdownFile(first.getDisplayName()));
            downloadItem.setVisible(single && first != null && !first.isDirectory());
            copyUrlItem.setVisible(single && first != null && !first.isDirectory());

            // 复制菜单项：选中的文件不为空时可用
            copyItem.setVisible(!selected.isEmpty() && currentBucket != null);
            copyItem.setText(selected.size() > 1 ? "复制(" + selected.size() + "项)" : "复制");

            // 粘贴菜单项：剪贴板有S3复制数据且当前在Bucket内时可用（不需要选中项）
            boolean hasClipboard = hasS3CopyData();
            pasteItem.setVisible(hasClipboard && currentBucket != null);
            deleteItem.setVisible(!selected.isEmpty());
            deleteItem.setText(selected.size() > 1 ? "删除(" + selected.size() + "项)" : "删除");
            renameItem.setVisible(single && first != null && !first.isBucket());
            mkdirItem.setVisible(currentBucket != null);
            uploadItem.setVisible(currentBucket != null);
            createFileItem.setVisible(currentBucket != null);
        });

        return menu;
    }

    private FileItem getSelectedItem() {
        if (currentViewMode == ViewMode.LIST) {
            return fileTable.getSelectionModel().getSelectedItem();
        } else if (currentViewMode == ViewMode.COLUMN) {
            for (ListView<FileItem> lv : columnListViews) {
                FileItem sel = lv.getSelectionModel().getSelectedItem();
                if (sel != null) return sel;
            }
            return selectedItem;
        }
        return selectedItem;
    }

    /**
     * 获取所有选中项（支持多选：图标视图框选、列表视图 Ctrl/Shift 多选）
     */
    private List<FileItem> getSelectedItems() {
        if (currentViewMode == ViewMode.LIST) {
            return new ArrayList<>(fileTable.getSelectionModel().getSelectedItems());
        } else if (currentViewMode == ViewMode.COLUMN) {
            List<FileItem> items = new ArrayList<>();
            for (ListView<FileItem> lv : columnListViews) {
                FileItem sel = lv.getSelectionModel().getSelectedItem();
                if (sel != null) items.add(sel);
            }
            if (items.isEmpty() && selectedItem != null) {
                items.add(selectedItem);
            }
            return items;
        }
        // 图标视图
        if (!selectedItems.isEmpty()) {
            return new ArrayList<>(selectedItems);
        }
        if (selectedItem != null) {
            return List.of(selectedItem);
        }
        return new ArrayList<>();
    }

    // ==================== 重命名编辑 ====================

    /**
     * 右键菜单"重命名"入口：按当前视图分派到对应的内联编辑。
     * Bucket 不支持重命名（S3 Bucket 名称不可修改；OSS 请走控制台）。
     */
    private void handleRename() {
        FileItem selected = getSelectedItem();
        if (selected == null) return;
        if (selected.isBucket()) {
            Alert a = new Alert(Alert.AlertType.WARNING);
            a.setTitle("重命名");
            a.setHeaderText(null);
            a.setContentText(isAliyunOSS ? "Bucket不支持重命名，请通过管理控制台操作" : "Bucket不支持重命名（S3 Bucket名称不可修改）");
            a.showAndWait();
            return;
        }
        if (currentViewMode == ViewMode.ICON) {
            startIconEdit(selected);
        } else if (currentViewMode == ViewMode.LIST) {
            int idx = fileTable.getSelectionModel().getSelectedIndex();
            if (idx >= 0) startListEdit(idx);
        } else if (currentViewMode == ViewMode.COLUMN) {
            startColumnEdit(selected);
        }
    }

    /**
     * 图标视图：用 Popup 浮窗悬浮在名称 Label 上方编辑（不参与布局、不受裁剪）。
     */
    private void startIconEdit(FileItem item) {
        if (editingItem != null) return;
        VBox box = null;
        for (var node : iconFlowPane.getChildren()) {
            if (node instanceof VBox v && item.equals(v.getProperties().get("fileItem"))) {
                box = v;
                break;
            }
        }
        if (box == null) return;
        editingItem = item;
        // 编辑时图标不显示选中样式（恢复为默认透明样式）
        box.setStyle("-fx-background-color: transparent; -fx-background-radius: 6; -fx-cursor: hand; -fx-border-color: transparent; -fx-border-width: 1; -fx-border-radius: 6; -fx-border-insets: 0;");

        // 找到 Label（图标 ImageView 之后的第一个 Label）
        Label nameLabel = null;
        for (int i = 0; i < box.getChildren().size(); i++) {
            if (box.getChildren().get(i) instanceof Label) {
                nameLabel = (Label) box.getChildren().get(i);
                break;
            }
        }
        if (nameLabel == null) { editingItem = null; return; }

        javafx.geometry.Bounds labelSceneBounds = nameLabel.localToScene(nameLabel.getBoundsInLocal());
        Scene scene = nameLabel.getScene();
        if (scene == null || scene.getWindow() == null) { editingItem = null; return; }
        javafx.stage.Window window = scene.getWindow();
        double screenX = window.getX() + scene.getX() + labelSceneBounds.getMinX();
        double screenY = window.getY() + scene.getY() + labelSceneBounds.getMinY();

        iconEditField = new TextField(item.getDisplayName());
        iconEditField.setStyle("-fx-padding: 0 6; -fx-font-size: 11px; -fx-background-color: white; -fx-border-color: #3399ff; -fx-border-width: 1.5; -fx-border-radius: 0; -fx-background-radius: 0;");
        // 宽度按内容计算，允许比 Label 宽
        javafx.scene.text.Text measureText = new javafx.scene.text.Text(item.getDisplayName());
        measureText.setFont(javafx.scene.text.Font.font(11));
        double contentWidth = measureText.getLayoutBounds().getWidth() + 20;
        double labelW = nameLabel.getWidth();
        if (labelW <= 0) labelW = nameLabel.prefWidth(-1);
        double fieldWidth = Math.max(contentWidth, labelW);
        double labelH = nameLabel.getHeight();
        if (labelH <= 0) labelH = nameLabel.prefHeight(-1);
        // 文本框高度约为原来的 3/5，避免过高
        double fieldHeight = (labelH + 8) * 0.6;
        iconEditField.setPrefWidth(fieldWidth);
        iconEditField.setPrefHeight(fieldHeight);
        iconEditField.setMinWidth(fieldWidth);
        iconEditField.setAlignment(Pos.CENTER);

        iconEditField.setOnAction(e -> commitRename(item, iconEditField.getText(), this::cancelIconEdit, this::refresh));
        iconEditField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (!isNowFocused && editingItem == item) {
                commitRename(item, iconEditField.getText(), this::cancelIconEdit, this::refresh);
            }
        });
        iconEditField.setOnKeyReleased(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                cancelIconEdit();
            }
        });

        iconEditPopup = new javafx.stage.Popup();
        iconEditPopup.setAutoFix(false);
        iconEditPopup.setAutoHide(true);
        iconEditPopup.setHideOnEscape(false);
        double offsetX = (fieldWidth - labelW) / 2.0;
        // 垂直居中到名称 Label 上，使其与非编辑时文件名同位置
        double offsetY = (labelH - fieldHeight) / 2.0;
        iconEditPopup.getContent().add(iconEditField);
        iconEditPopup.show(window, screenX - offsetX, screenY + offsetY);
        iconEditField.selectAll();
        Platform.runLater(() -> iconEditField.requestFocus());
    }

    /** 取消图标视图编辑：隐藏并移除 Popup，清除选中状态 */
    private void cancelIconEdit() {
        if (editingItem == null) return;
        editingItem = null;
        if (iconEditPopup != null) {
            iconEditPopup.hide();
            iconEditPopup = null;
            iconEditField = null;
        }
        clearIconSelection();
    }

    /** 列表视图：在指定行号上启动名称列内联编辑 */
    private void startListEdit(int row) {
        if (editingItem != null) return;
        if (row < 0 || row >= fileTable.getItems().size()) return;
        fileTable.setEditable(true);
        fileTable.edit(row, fileTable.getColumns().get(0));
    }

    /** 取消列表视图编辑 */
    private void cancelListEdit() {
        editingItem = null;
        fileTable.setEditable(false);
        fileTable.edit(-1, null);
    }

    /**
     * 列视图：用 Popup 浮窗悬浮在选中单元格上方编辑。
     * 定位选中项所在列与单元格节点后定位浮窗。
     */
    private void startColumnEdit(FileItem item) {
        if (editingItem != null) return;
        ListView<FileItem> targetLv = null;
        int targetColIndex = -1;
        for (int i = 0; i < columnListViews.size(); i++) {
            ListView<FileItem> lv = columnListViews.get(i);
            FileItem sel = lv.getSelectionModel().getSelectedItem();
            if (item.equals(sel)) {
                targetLv = lv;
                targetColIndex = i;
                break;
            }
        }
        if (targetLv == null) return;

        ListCell<FileItem> targetCell = null;
        for (var node : targetLv.lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> c && item.equals(c.getItem())) {
                @SuppressWarnings("unchecked")
                ListCell<FileItem> cast = (ListCell<FileItem>) c;
                targetCell = cast;
                break;
            }
        }
        if (targetCell == null) return;
        editingItem = item;

        javafx.geometry.Bounds cellSceneBounds = targetCell.localToScene(targetCell.getBoundsInLocal());
        Scene scene = targetCell.getScene();
        if (scene == null || scene.getWindow() == null) { editingItem = null; return; }
        javafx.stage.Window window = scene.getWindow();
        double screenX = window.getX() + scene.getX() + cellSceneBounds.getMinX();
        double screenY = window.getY() + scene.getY() + cellSceneBounds.getMinY();

        iconEditField = new TextField(item.getDisplayName());
        iconEditField.setStyle("-fx-padding: 0 6; -fx-font-size: 12px; -fx-background-color: white; -fx-border-color: #3399ff; -fx-border-width: 1.5; -fx-border-radius: 0; -fx-background-radius: 0;");
        double cellW = targetCell.getWidth();
        double cellH = targetCell.getHeight();
        iconEditField.setPrefWidth(Math.max(cellW - 8, 80));
        // 文本框高度约为原来的 3/5，避免过高
        iconEditField.setPrefHeight(cellH * 0.6);
        iconEditField.setMinWidth(80);

        final int colIdx = targetColIndex;
        iconEditField.setOnAction(e -> commitRename(item, iconEditField.getText(), this::cancelColumnEdit, () -> reloadColumn(colIdx)));
        iconEditField.focusedProperty().addListener((obs, wasFocused, isNowFocused) -> {
            if (!isNowFocused && editingItem == item) {
                commitRename(item, iconEditField.getText(), this::cancelColumnEdit, () -> reloadColumn(colIdx));
            }
        });
        iconEditField.setOnKeyReleased(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                cancelColumnEdit();
            }
        });

        iconEditPopup = new javafx.stage.Popup();
        iconEditPopup.setAutoFix(false);
        iconEditPopup.setAutoHide(true);
        iconEditPopup.setHideOnEscape(false);
        // 垂直居中到单元格上，使其与非编辑时文件名同位置
        iconEditPopup.getContent().add(iconEditField);
        iconEditPopup.show(window, screenX + 4, screenY + (cellH - cellH * 0.6) / 2.0);
        iconEditField.selectAll();
        Platform.runLater(() -> iconEditField.requestFocus());
    }

    /** 取消列视图编辑 */
    private void cancelColumnEdit() {
        if (editingItem == null) return;
        editingItem = null;
        if (iconEditPopup != null) {
            iconEditPopup.hide();
            iconEditPopup = null;
            iconEditField = null;
        }
    }

    /**
     * 提交重命名：校验名称后异步执行。先恢复 UI（onCancel），再调用后端重命名，成功后回调 onSuccess 刷新。
     * @param item 待重命名的文件项
     * @param newName 新名称（仅叶子名，不能含 /）
     * @param onCancel 恢复 UI 的回调（取消编辑状态）
     * @param onSuccess 重命名成功后的刷新回调（不同视图刷新方式不同）
     */
    private void commitRename(FileItem item, String newName, Runnable onCancel, Runnable onSuccess) {
        if (editingItem == null) return;
        String name = newName == null ? "" : newName.trim();
        if (name.isEmpty() || name.equals(item.getDisplayName())) {
            onCancel.run();
            return;
        }
        if (name.contains("/")) {
            onCancel.run();
            Platform.runLater(() -> {
                Alert a = new Alert(Alert.AlertType.WARNING);
                a.setTitle("重命名失败");
                a.setHeaderText(null);
                a.setContentText("名称不能包含 \"/\"");
                a.showAndWait();
            });
            return;
        }
        if (item.isBucket()) {
            onCancel.run();
            Platform.runLater(() -> {
                Alert a = new Alert(Alert.AlertType.WARNING);
                a.setTitle("重命名失败");
                a.setHeaderText(null);
                a.setContentText(isAliyunOSS ? "Bucket不支持重命名，请通过管理控制台操作" : "Bucket不支持重命名（S3 Bucket名称不可修改）");
                a.showAndWait();
            });
            return;
        }

        final FileItem editItem = item;
        final String finalNewName = name;
        // 先恢复 UI，再异步重命名
        onCancel.run();

        new Thread(() -> {
            try {
                String sourceKey = editItem.getKey();
                if (editItem.isDirectory()) {
                    // 目录重命名：递归复制 prefix 下所有对象到新 prefix
                    String sourcePrefix = sourceKey; // 含末尾 /
                    int nameLen = editItem.getName().length();
                    String parentPrefix = sourcePrefix.length() > nameLen
                            ? sourcePrefix.substring(0, sourcePrefix.length() - nameLen - 1)
                            : "";
                    String destPrefix = parentPrefix + finalNewName + "/";
                    if (isAliyunOSS) {
                        OssService.renameDirectory(config, currentBucket, sourcePrefix, destPrefix);
                    } else {
                        S3Service.renameDirectory(config, currentBucket, sourcePrefix, destPrefix);
                    }
                } else {
                    // 文件重命名：复制到新 key 后删除原 key
                    int lastSlash = sourceKey.lastIndexOf('/');
                    String parentPrefix = lastSlash >= 0 ? sourceKey.substring(0, lastSlash + 1) : "";
                    String newKey = parentPrefix + finalNewName;
                    if (isAliyunOSS) {
                        OssService.renameObject(config, currentBucket, sourceKey, newKey);
                    } else {
                        S3Service.renameObject(config, currentBucket, sourceKey, newKey);
                    }
                }
                Platform.runLater(() -> { if (onSuccess != null) onSuccess.run(); });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert a = new Alert(Alert.AlertType.ERROR);
                    a.setTitle("重命名失败");
                    a.setHeaderText(null);
                    a.setContentText("重命名失败: " + e.getMessage());
                    a.showAndWait();
                });
            }
        }, "S3-Rename").start();
    }

    /**
     * 列视图：原地重新加载指定列的数据（重命名后刷新当前列，并移除右侧可能已失效的子列）。
     */
    private void reloadColumn(int colIndex) {
        if (colIndex < 0 || colIndex >= columnListViews.size()) return;
        final String bucket = columnBuckets.get(colIndex);
        final String prefix = columnPrefixes.get(colIndex);
        truncateColumns(colIndex + 1); // 移除右侧可能已失效的子列
        new Thread(() -> {
            try {
                List<FileItem> items = new ArrayList<>();
                if (bucket == null) {
                    List<String> buckets = isAliyunOSS ? OssService.listBuckets(config) : S3Service.listBuckets(config);
                    for (String bn : buckets) {
                        FileItem it = new FileItem();
                        it.setName(bn); it.setKey(bn); it.setDirectory(true); it.setBucket(true);
                        items.add(it);
                    }
                } else {
                    List<?> objects = isAliyunOSS ? OssService.listObjects(config, bucket, prefix) : S3Service.listObjects(config, bucket, prefix);
                    for (Object obj : objects) {
                        FileItem it = new FileItem();
                        if (isAliyunOSS) {
                            OssService.OssObjectInfo o = (OssService.OssObjectInfo) obj;
                            it.setName(o.getDisplayName()); it.setKey(o.getKey()); it.setDirectory(o.isDirectory());
                            it.setSize(o.getSize());
                            it.setLastModified(o.getLastModified() != null ? o.getLastModified().toString() : "");
                            it.setBucket(false);
                        } else {
                            S3Service.S3ObjectInfo o = (S3Service.S3ObjectInfo) obj;
                            it.setName(o.getDisplayName()); it.setKey(o.getKey()); it.setDirectory(o.isDirectory());
                            it.setSize(o.getSize());
                            it.setLastModified(o.getLastModified() != null ? o.getLastModified().toString() : "");
                            it.setBucket(false);
                        }
                        items.add(it);
                    }
                }
                Platform.runLater(() -> {
                    if (colIndex < columnItems.size()) {
                        columnItems.get(colIndex).setAll(items);
                        stateLabel.setText(items.size() + " 个条目");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> stateLabel.setText("错误: " + e.getMessage()));
            }
        }, "S3-ColumnReload").start();
    }

    /**
     * 加载Bucket列表
     */
    private void loadBuckets() {
        new Thread(() -> {
            try {
                List<String> buckets;
                if (isAliyunOSS) {
                    buckets = OssService.listBuckets(config);
                } else {
                    buckets = S3Service.listBuckets(config);
                }

                Platform.runLater(() -> {
                    statusDot.setFill(Color.GREEN);
                    stateLabel.setText("已连接");
                    currentBucket = null;
                    currentPrefix = "";
                    pathHistory.clear();
                    updatePathLabel();

                    fileData.clear();
                    for (String bucketName : buckets) {
                        FileItem item = new FileItem();
                        item.setName(bucketName);
                        item.setKey(bucketName);
                        item.setDirectory(true);
                        item.setBucket(true);
                        fileData.add(item);
                    }

                    upBtn.setDisable(true);
                    createBucketBtn.setVisible(true);
                    createBucketBtn.setManaged(true);

                    if (currentViewMode == ViewMode.ICON) {
                        rebuildIconView();
                        loadThumbnailsForIconView();
                    } else if (currentViewMode == ViewMode.COLUMN) {
                        rebuildColumnView();
                    }
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
        }, "S3-LoadBuckets").start();
    }

    /**
     * 加载Bucket中的对象列表
     */
    private void loadObjects(String bucketName, String prefix) {
        new Thread(() -> {
            try {
                List<?> objects;
                if (isAliyunOSS) {
                    objects = OssService.listObjects(config, bucketName, prefix);
                } else {
                    objects = S3Service.listObjects(config, bucketName, prefix);
                }

                Platform.runLater(() -> {
                    currentBucket = bucketName;
                    currentPrefix = prefix != null ? prefix : "";
                    updatePathLabel();

                    fileData.clear();
                    for (Object obj : objects) {
                        FileItem item = new FileItem();
                        if (isAliyunOSS) {
                            OssService.OssObjectInfo ossObj = (OssService.OssObjectInfo) obj;
                            item.setName(ossObj.getDisplayName());
                            item.setKey(ossObj.getKey());
                            item.setDirectory(ossObj.isDirectory());
                            item.setSize(ossObj.getSize());
                            item.setLastModified(ossObj.getLastModified() != null ? ossObj.getLastModified().toString() : "");
                            item.setBucket(false);
                        } else {
                            S3Service.S3ObjectInfo s3Obj = (S3Service.S3ObjectInfo) obj;
                            item.setName(s3Obj.getDisplayName());
                            item.setKey(s3Obj.getKey());
                            item.setDirectory(s3Obj.isDirectory());
                            item.setSize(s3Obj.getSize());
                            item.setLastModified(s3Obj.getLastModified() != null ? s3Obj.getLastModified().toString() : "");
                            item.setBucket(false);
                        }
                        fileData.add(item);
                    }

                    upBtn.setDisable(false);
                    createBucketBtn.setVisible(false);
                    createBucketBtn.setManaged(false);
                    selectedItem = null;

                    if (currentViewMode == ViewMode.ICON) {
                        rebuildIconView();
                        loadThumbnailsForIconView();
                    } else if (currentViewMode == ViewMode.COLUMN) {
                        rebuildColumnView();
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载文件列表: " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
        }, "S3-LoadObjects").start();
    }

    /**
     * 处理双击：目录进入，图片预览，markdown 编辑
     */
    private void handleDoubleClick(FileItem item) {
        if (item == null) return;

        if (item.isDirectory()) {
            if (item.isBucket()) {
                pathHistory.add("/");
                loadObjects(item.getName(), "");
            } else {
                pathHistory.add(currentPrefix);
                loadObjects(currentBucket, item.getKey());
            }
        } else if (isImageFile(item.getDisplayName())) {
            // 双击图片文件 -> 预览
            handlePreview(item);
        } else if (isMarkdownFile(item.getDisplayName())) {
            // 双击 markdown 文件 -> 编辑器
            openMarkdownEditor(item);
        }
    }

    /**
     * 打开 Markdown 编辑器 Tab：异步下载内容后新建编辑器 Tab
     * 若该 key 已有打开的 Tab，则直接选中
     */
    private void openMarkdownEditor(FileItem item) {
        if (currentBucket == null) return;
        String fileKey = item.getKey();
        String fileName = item.getDisplayName();

        // 复用已打开的 Tab
        for (Tab tab : editorTabPane.getTabs()) {
            if (tab.getUserData() instanceof String tabKey && tabKey.equals(fileKey)) {
                editorTabPane.getSelectionModel().select(tab);
                return;
            }
        }

        // 占位 Tab，先显示加载状态
        Tab editorTab = new Tab(fileName);
        editorTab.setUserData(fileKey);
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setPrefSize(40, 40);
        StackPane loading = new StackPane(indicator);
        loading.setStyle("-fx-background-color: white;");
        editorTab.setContent(loading);
        editorTabPane.getTabs().add(editorTab);
        editorTabPane.getSelectionModel().select(editorTab);

        MarkdownEditorPane.loadMarkdownContent(config, currentBucket, fileKey, (content, err) -> {
            if (err != null) {
                editorTab.setContent(new Label("加载失败: " + err));
                return;
            }
            MarkdownEditorPane editor = new MarkdownEditorPane(config, currentBucket, fileKey, fileName, content);
            editorTab.setContent(editor);
            editor.setOnTitleChange(title -> editorTab.setText(title));
            editorTab.setText(editor.getDisplayTitle());
            // 关闭前检查未保存
            editorTab.setOnCloseRequest(ev -> {
                if (editor.isModified()) {
                    ev.consume();
                    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                    confirm.setTitle("未保存");
                    confirm.setHeaderText("文件 \"" + fileName + "\" 已修改未保存，是否保存？");
                    ButtonType saveBtn = new ButtonType("保存", ButtonBar.ButtonData.YES);
                    ButtonType discardBtn = new ButtonType("不保存", ButtonBar.ButtonData.NO);
                    ButtonType cancelBtn = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
                    confirm.getButtonTypes().setAll(saveBtn, discardBtn, cancelBtn);
                    confirm.showAndWait().ifPresent(resp -> {
                        if (resp == saveBtn) {
                            editor.save();
                        } else if (resp == cancelBtn) {
                            return;
                        }
                        // 保存或不保存：移除 Tab
                        editorTabPane.getTabs().remove(editorTab);
                    });
                }
            });
        });
    }

    /**
     * 右键创建文件：弹窗输入文件名，在当前 bucket/prefix 下新建空 markdown 并打开编辑器
     */
    private void handleCreateFile() {
        if (currentBucket == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("请先进入一个 Bucket 再创建文件");
            alert.showAndWait();
            return;
        }
        TextInputDialog dialog = new TextInputDialog("新文件.md");
        dialog.setTitle("创建文件");
        dialog.setHeaderText(null);
        dialog.setContentText("文件名：");
        dialog.showAndWait().ifPresent(name -> {
            String fileName = name.trim();
            if (fileName.isEmpty()) return;
            // 拼接完整 key
            String fileKey = (currentPrefix != null ? currentPrefix : "") + fileName;
            new Thread(() -> {
                try {
                    if (isAliyunOSS) {
                        OssService.putObject(config, currentBucket, fileKey, "");
                    } else {
                        S3Service.putObject(config, currentBucket, fileKey, "");
                    }
                    Platform.runLater(() -> {
                        refresh();
                        // 直接打开编辑器 Tab
                        FileItem newItem = new FileItem();
                        newItem.setName(fileName);
                        newItem.setKey(fileKey);
                        newItem.setDirectory(false);
                        newItem.setBucket(false);
                        openMarkdownEditor(newItem);
                    });
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("创建失败");
                        alert.setHeaderText(null);
                        alert.setContentText("创建文件失败: " + e.getMessage());
                        alert.showAndWait();
                    });
                }
            }, "MD-CreateFile").start();
        });
    }

    /**
     * 右键新建目录：在当前 bucket/prefix 下创建子目录（S3/OSS 中为以 / 结尾的空对象）
     */
    private void handleCreateDirectory() {
        if (currentBucket == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("请先进入一个 Bucket 再创建目录");
            alert.showAndWait();
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("新建目录");
        dialog.setHeaderText(null);
        dialog.setContentText("目录名称：");
        dialog.showAndWait().ifPresent(name -> {
            String dirName = name.trim();
            if (dirName.isEmpty()) return;
            // 禁止包含路径分隔符，避免产生意外路径
            if (dirName.contains("/")) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("提示");
                alert.setHeaderText(null);
                alert.setContentText("目录名称不能包含 / 字符");
                alert.showAndWait();
                return;
            }
            // 拼接完整 prefix
            String dirKey = (currentPrefix != null ? currentPrefix : "") + dirName + "/";
            new Thread(() -> {
                try {
                    if (isAliyunOSS) {
                        OssService.createDirectory(config, currentBucket, dirKey);
                    } else {
                        S3Service.createDirectory(config, currentBucket, dirKey);
                    }
                    Platform.runLater(this::refresh);
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("创建失败");
                        alert.setHeaderText(null);
                        alert.setContentText("创建目录失败: " + e.getMessage());
                        alert.showAndWait();
                    });
                }
            }, "S3-Mkdir").start();
        });
    }

    /**
    /**
     * 右键上传文件：弹窗选择本地文件，逐个上传到当前 bucket/prefix
     */
    private void handleUploadFiles() {
        if (currentBucket == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("请先进入一个 Bucket 再上传文件");
            alert.showAndWait();
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要上传的文件");
        List<File> files = chooser.showOpenMultipleDialog(getScene().getWindow());
        if (files == null || files.isEmpty()) return;
        uploadLocalFiles(files);
    }

    /**
     * 上传本地文件列表到当前 bucket/prefix
     */
    private void uploadLocalFiles(List<File> files) {
        if (currentBucket == null || files == null || files.isEmpty()) return;
        stateLabel.setText("上传中... (0/" + files.size() + ")");
        new Thread(() -> {
            int success = 0;
            int failed = 0;
            String lastError = null;
            for (File file : files) {
                String key = (currentPrefix != null ? currentPrefix : "") + file.getName();
                try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                    long size = file.length();
                    String contentType = java.net.URLConnection.guessContentTypeFromName(file.getName());
                    if (contentType == null) contentType = "application/octet-stream";
                    if (isAliyunOSS) {
                        OssService.uploadFile(config, currentBucket, key, fis, size, contentType);
                    } else {
                        S3Service.uploadFile(config, currentBucket, key, fis, size, contentType);
                    }
                    success++;
                } catch (Exception e) {
                    failed++;
                    lastError = e.getMessage();
                    e.printStackTrace();
                }
                final int done = success + failed;
                Platform.runLater(() -> stateLabel.setText("上传中... (" + done + "/" + files.size() + ")"));
            }
            final int okCount = success;
            final int failCount = failed;
            final String err = lastError;
            Platform.runLater(() -> {
                if (failCount == 0) {
                    stateLabel.setText("上传完成: 成功 " + okCount + " 个");
                } else {
                    stateLabel.setText("上传结束: 成功 " + okCount + " 个, 失败 " + failCount + " 个");
                    Alert alert = new Alert(Alert.AlertType.WARNING);
                    alert.setTitle("部分上传失败");
                    alert.setHeaderText("成功 " + okCount + " 个, 失败 " + failCount + " 个");
                    alert.setContentText(err != null ? err : "");
                    alert.showAndWait();
                }
                refresh();
            });
        }, "S3-Upload").start();
    }

    /**
     * 右键下载文件：选择保存位置，下载指定文件
     */
    private void handleDownload(FileItem item) {
        if (currentBucket == null || item == null || item.isDirectory()) return;
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("保存文件");
        fileChooser.setInitialFileName(item.getDisplayName());
        File saveFile = fileChooser.showSaveDialog(getScene().getWindow());
        if (saveFile == null) return;

        stateLabel.setText("下载中: " + item.getDisplayName());
        new Thread(() -> {
            try (InputStream is = isAliyunOSS
                    ? OssService.getObjectStream(config, currentBucket, item.getKey())
                    : S3Service.getObjectStream(config, currentBucket, item.getKey())) {
                Files.copy(is, saveFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Platform.runLater(() -> {
                    stateLabel.setText("下载完成: " + item.getDisplayName());
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("下载完成");
                    alert.setHeaderText(null);
                    alert.setContentText("文件已保存到: " + saveFile.getAbsolutePath());
                    alert.showAndWait();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    stateLabel.setText("下载失败");
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("下载失败");
                    alert.setHeaderText(null);
                    alert.setContentText(e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "S3-Download").start();
    }

    /**
     * 复制访问地址到剪贴板。若未配置访问URL，提示用户先配置。
     */
    private void handleCopyAccessUrl(FileItem item) {
        String baseUrl = config.getPublicAccessUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("尚未配置访问URL，请在连接配置中设置访问URL字段");
            alert.showAndWait();
            return;
        }
        String trimmedBase = baseUrl.trim();
        while (trimmedBase.endsWith("/")) {
            trimmedBase = trimmedBase.substring(0, trimmedBase.length() - 1);
        }
        String key = item.getKey();
        while (key.startsWith("/")) {
            key = key.substring(1);
        }
        String fullUrl = trimmedBase + "/" + currentBucket + "/" + key;

        ClipboardContent content = new ClipboardContent();
        content.putString(fullUrl);
        Clipboard.getSystemClipboard().setContent(content);

        stateLabel.setText("已复制访问地址: " + fullUrl);
    }

    /**
     * 下载文件到临时目录（用于拖拽下载）
     */
    private File downloadToTemp(FileItem item) {
        if (currentBucket == null || item == null || item.isDirectory()) return null;
        try {
            File tempDir = new File(System.getProperty("java.io.tmpdir"), "tomato-s3");
            if (!tempDir.exists()) tempDir.mkdirs();
            File tempFile = new File(tempDir, item.getDisplayName());
            try (InputStream is = isAliyunOSS
                    ? OssService.getObjectStream(config, currentBucket, item.getKey())
                    : S3Service.getObjectStream(config, currentBucket, item.getKey())) {
                Files.copy(is, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return tempFile;
        } catch (Exception e) {
            Platform.runLater(() -> stateLabel.setText("拖拽下载失败: " + e.getMessage()));
            return null;
        }
    }

    /**
     * 预览图片（使用当前选中项）
     */
    private void handlePreview() {
        FileItem selected = getSelectedItem();
        if (selected == null || selected.isDirectory() || !isImageFile(selected.getDisplayName())) return;
        handlePreview(selected);
    }

    /**
     * 预览图片：从S3/OSS下载并显示
     */
    private void handlePreview(FileItem item) {
        if (currentBucket == null) return;

        // 收集当前目录中所有图片文件
        List<FileItem> imageItems = new ArrayList<>();
        int currentIndex = -1;
        for (int i = 0; i < fileData.size(); i++) {
            FileItem fi = fileData.get(i);
            if (!fi.isDirectory() && isImageFile(fi.getDisplayName())) {
                imageItems.add(fi);
                if (fi == item || fi.getKey().equals(item.getKey())) {
                    currentIndex = imageItems.size() - 1;
                }
            }
        }
        if (imageItems.isEmpty() || currentIndex < 0) return;

        Stage previewStage = new Stage();
        previewStage.setTitle("图片预览");
        previewStage.setMinWidth(600);
        previewStage.setMinHeight(500);
        previewStage.setWidth(800);
        previewStage.setHeight(600);

        // 初始加载动画
        ProgressIndicator loadingIndicator = new ProgressIndicator();
        loadingIndicator.setPrefSize(60, 60);
        StackPane loadingPane = new StackPane(loadingIndicator);
        loadingPane.setPrefSize(780, 520);
        loadingPane.setStyle("-fx-background-color: #2b2b2b;");
        previewStage.setScene(new Scene(loadingPane));

        // 使用数组以便在lambda中修改
        final int[] imageIndex = {currentIndex};

        // 加载图片的回调接口
        Runnable loadImage = new Runnable() {
            @Override
            public void run() {
                int idx = imageIndex[0];
                if (idx < 0 || idx >= imageItems.size()) return;
                FileItem currentItem = imageItems.get(idx);

                // 显示加载动画
                ProgressIndicator indicator = new ProgressIndicator();
                indicator.setPrefSize(60, 60);
                StackPane pane = new StackPane(indicator);
                pane.setPrefSize(780, 520);
                pane.setStyle("-fx-background-color: #2b2b2b;");
                previewStage.setScene(new Scene(pane));

                new Thread(() -> {
                    try {
                        InputStream is;
                        if (isAliyunOSS) {
                            is = OssService.getObjectStream(config, currentBucket, currentItem.getKey());
                        } else {
                            is = S3Service.getObjectStream(config, currentBucket, currentItem.getKey());
                        }

                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = is.read(buffer)) != -1) {
                            baos.write(buffer, 0, len);
                        }
                        is.close();
                        byte[] imageBytes = baos.toByteArray();

                        Platform.runLater(() -> {
                            try {
                                Image image = new Image(new java.io.ByteArrayInputStream(imageBytes));
                                if (image.isError()) {
                                    throw new Exception("图片格式不支持或文件已损坏");
                                }
                                showImageInPreviewStage(previewStage, image, currentItem, imageItems, imageIndex, this);
                            } catch (Exception e) {
                                Label errorLabel = new Label("图片加载失败: " + e.getMessage());
                                errorLabel.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 13px;");
                                StackPane errorPane = new StackPane(errorLabel);
                                errorPane.setPrefSize(780, 520);
                                errorPane.setStyle("-fx-background-color: #2b2b2b;");
                                previewStage.setScene(new Scene(errorPane));
                            }
                        });
                    } catch (Exception e) {
                        Platform.runLater(() -> {
                            Label errorLabel = new Label("图片下载失败: " + e.getMessage());
                            errorLabel.setStyle("-fx-text-fill: #ff6b6b; -fx-font-size: 13px;");
                            StackPane errorPane = new StackPane(errorLabel);
                            errorPane.setPrefSize(780, 520);
                            errorPane.setStyle("-fx-background-color: #2b2b2b;");
                            previewStage.setScene(new Scene(errorPane));
                        });
                    }
                }, "S3-LoadImage").start();
            }
        };

        // 首次加载
        loadImage.run();
        DialogPositionUtil.centerOnOwner(previewStage, this);
        previewStage.show();
    }

    /**
     * 在预览窗口中展示图片（支持缩放、拖拽、上一张/下一张、下载、删除）
     */
    private void showImageInPreviewStage(Stage stage, Image image, FileItem item,
                                          List<FileItem> imageItems, int[] imageIndex, Runnable loadImage) {
        double imgWidth = image.getWidth();
        double imgHeight = image.getHeight();

        ImageView imageView = new ImageView(image);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);

        // 检查是否需要生成缩略图用于图标视图
        if (currentViewMode == ViewMode.ICON && imgWidth > 0 && imgHeight > 0) {
            updateIconBoxWithThumbnail(item, image);
        }

        // 图片容器（可拖拽）
        StackPane imageContainer = new StackPane(imageView);
        imageContainer.setStyle("-fx-background-color: #2b2b2b;");

        // 初始适配：适应窗口大小
        double contentWidth = stage.getWidth() > 0 ? stage.getWidth() : 800;
        double contentHeight = stage.getHeight() > 0 ? stage.getHeight() - 40 : 560;

        double fitWidth = Math.min(imgWidth, contentWidth - 20);
        double fitHeight = Math.min(imgHeight, contentHeight - 60);
        double scale = Math.min(fitWidth / imgWidth, fitHeight / imgHeight);
        if (scale < 1) {
            imageView.setFitWidth(imgWidth * scale);
            imageView.setFitHeight(imgHeight * scale);
        }

        // 滚轮缩放
        imageContainer.setOnScroll((ScrollEvent event) -> {
            double zoomFactor = 1.05;
            double delta = event.getDeltaY();
            if (delta < 0) {
                zoomFactor = 1.0 / zoomFactor;
            }

            double currentFitW = imageView.getFitWidth() > 0 ? imageView.getFitWidth() : imgWidth;
            double currentFitH = imageView.getFitHeight() > 0 ? imageView.getFitHeight() : imgHeight;

            double newW = currentFitW * zoomFactor;
            double newH = currentFitH * zoomFactor;

            double minSize = 50;
            double maxSize = imgWidth * 10;
            if (newW < minSize || newH < minSize || newW > maxSize || newH > maxSize) return;

            imageView.setFitWidth(newW);
            imageView.setFitHeight(newH);
            event.consume();
        });

        // 拖拽移动
        final double[] dragStart = new double[2];
        final double[] translateStart = new double[2];
        imageContainer.setOnMousePressed(e -> {
            dragStart[0] = e.getSceneX();
            dragStart[1] = e.getSceneY();
            translateStart[0] = imageView.getTranslateX();
            translateStart[1] = imageView.getTranslateY();
        });
        imageContainer.setOnMouseDragged(e -> {
            double dx = e.getSceneX() - dragStart[0];
            double dy = e.getSceneY() - dragStart[1];
            imageView.setTranslateX(translateStart[0] + dx);
            imageView.setTranslateY(translateStart[1] + dy);
        });

        // 设置标题：文件名、尺寸、大小
        stage.setTitle(String.format("%s  |  %dx%d  |  %s  (%d/%d)",
                item.getDisplayName(), (int) imgWidth, (int) imgHeight, item.getFormattedSize(),
                imageIndex[0] + 1, imageItems.size()));

        // 工具栏
        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6, 10, 6, 10));
        toolbar.setStyle("-fx-background-color: #3c3c3c;");

        // 上一张按钮
        Button prevBtn = new Button("◀ 上一张");
        prevBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        prevBtn.setDisable(imageIndex[0] <= 0);
        prevBtn.setOnAction(e -> {
            if (imageIndex[0] > 0) {
                imageIndex[0]--;
                loadImage.run();
            }
        });
        toolbar.getChildren().add(prevBtn);

        // 下一张按钮
        Button nextBtn = new Button("下一张 ▶");
        nextBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        nextBtn.setDisable(imageIndex[0] >= imageItems.size() - 1);
        nextBtn.setOnAction(e -> {
            if (imageIndex[0] < imageItems.size() - 1) {
                imageIndex[0]++;
                loadImage.run();
            }
        });
        toolbar.getChildren().add(nextBtn);

        Region toolSpacer = new Region();
        HBox.setHgrow(toolSpacer, Priority.ALWAYS);
        toolbar.getChildren().add(toolSpacer);

        // 适配窗口按钮
        Button fitBtn = new Button("适配窗口");
        fitBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        fitBtn.setOnAction(e -> {
            double cw = imageContainer.getWidth();
            double ch = imageContainer.getHeight();
            if (cw <= 0 || ch <= 0) return;
            double s = Math.min((cw - 20) / imgWidth, (ch - 20) / imgHeight);
            if (s > 1) s = 1;
            imageView.setFitWidth(imgWidth * s);
            imageView.setFitHeight(imgHeight * s);
            imageView.setTranslateX(0);
            imageView.setTranslateY(0);
        });
        toolbar.getChildren().add(fitBtn);

        // 原始大小按钮
        Button originalBtn = new Button("1:1");
        originalBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        originalBtn.setOnAction(e -> {
            imageView.setFitWidth(imgWidth);
            imageView.setFitHeight(imgHeight);
            imageView.setTranslateX(0);
            imageView.setTranslateY(0);
        });
        toolbar.getChildren().add(originalBtn);

        // 分隔
        Separator sep2 = new Separator();
        sep2.setOrientation(javafx.geometry.Orientation.VERTICAL);
        toolbar.getChildren().add(sep2);

        // 下载按钮
        Button downloadBtn = new Button("下载");
        downloadBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        downloadBtn.setOnAction(e -> {
            javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
            fileChooser.setTitle("保存文件");
            fileChooser.setInitialFileName(item.getDisplayName());
            java.io.File saveFile = fileChooser.showSaveDialog(stage);
            if (saveFile == null) return;

            new Thread(() -> {
                try {
                    InputStream is;
                    if (isAliyunOSS) {
                        is = OssService.getObjectStream(config, currentBucket, item.getKey());
                    } else {
                        is = S3Service.getObjectStream(config, currentBucket, item.getKey());
                    }
                    java.nio.file.Files.copy(is, saveFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    is.close();
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
                        alert.setTitle("下载完成");
                        alert.setHeaderText(null);
                        alert.setContentText("文件已保存到: " + saveFile.getAbsolutePath());
                        alert.showAndWait();
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("下载失败");
                        alert.setHeaderText(null);
                        alert.setContentText(ex.getMessage());
                        alert.showAndWait();
                    });
                }
            }, "S3-Download").start();
        });
        toolbar.getChildren().add(downloadBtn);

        // 删除按钮
        Button deleteBtn = new Button("删除");
        deleteBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8; -fx-text-fill: #ff6b6b;");
        deleteBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("删除确认");
            confirm.setHeaderText(null);
            confirm.setContentText("确定要删除文件 \"" + item.getDisplayName() + "\" 吗？");
            confirm.showAndWait().ifPresent(response -> {
                if (response != ButtonType.OK) return;

                new Thread(() -> {
                    try {
                        if (isAliyunOSS) {
                            OssService.deleteObject(config, currentBucket, item.getKey());
                        } else {
                            S3Service.deleteObject(config, currentBucket, item.getKey());
                        }
                        Platform.runLater(() -> {
                            // 从列表中移除已删除项
                            imageItems.remove(imageIndex[0]);
                            fileData.remove(item);
                            if (imageItems.isEmpty()) {
                                stage.close();
                                refresh();
                            } else {
                                if (imageIndex[0] >= imageItems.size()) {
                                    imageIndex[0] = imageItems.size() - 1;
                                }
                                refresh();
                                loadImage.run();
                            }
                        });
                    } catch (Exception ex) {
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.ERROR);
                            alert.setTitle("删除失败");
                            alert.setHeaderText(null);
                            alert.setContentText(ex.getMessage());
                            alert.showAndWait();
                        });
                    }
                }, "S3-Delete").start();
            });
        });
        toolbar.getChildren().add(deleteBtn);

        VBox content = new VBox();
        content.getChildren().addAll(toolbar, imageContainer);
        VBox.setVgrow(imageContainer, Priority.ALWAYS);

        stage.setScene(new Scene(content));

        // 键盘快捷键：左右箭头切换图片
        stage.getScene().setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.LEFT && imageIndex[0] > 0) {
                imageIndex[0]--;
                loadImage.run();
            } else if (e.getCode() == javafx.scene.input.KeyCode.RIGHT && imageIndex[0] < imageItems.size() - 1) {
                imageIndex[0]++;
                loadImage.run();
            }
        });
    }

    /**
     * 更新图标视图中图片文件的缩略图
     */
    private void updateIconBoxWithThumbnail(FileItem item, Image fullImage) {
        // 生成缩略图
        double thumbSize = 48;
        double w = fullImage.getWidth();
        double h = fullImage.getHeight();
        if (w <= 0 || h <= 0) return;

        double scale = Math.min(thumbSize / w, thumbSize / h);
        ImageView thumbView = new ImageView(fullImage);
        thumbView.setFitWidth(w * scale);
        thumbView.setFitHeight(h * scale);
        thumbView.setPreserveRatio(true);
        thumbView.setSmooth(true);
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(javafx.scene.paint.Color.TRANSPARENT);
        Image thumbnail = thumbView.snapshot(params, null);

        // 在图标视图中找到对应的VBox并替换图标
        for (var node : iconFlowPane.getChildren()) {
            if (node instanceof VBox box) {
                // 找到对应的item（通过名称匹配）
                if (box.getChildren().size() >= 2
                        && box.getChildren().get(1) instanceof Label label
                        && item.getDisplayName().equals(label.getText())) {

                    // 替换图标为缩略图
                    if (box.getChildren().get(0) instanceof ImageView iconView) {
                        iconView.setImage(thumbnail);
                        // 添加圆角边框效果
                        iconView.setStyle("-fx-border-color: #ddd; -fx-border-width: 1; -fx-border-radius: 4;");
                    }
                    break;
                }
            }
        }
    }

    /**
     * 异步加载图标视图中所有图片文件的缩略图
     */
    private void loadThumbnailsForIconView() {
        if (currentBucket == null) return;
        for (FileItem item : fileData) {
            if (!item.isDirectory() && isImageFile(item.getDisplayName())) {
                new Thread(() -> {
                    try {
                        InputStream is;
                        if (isAliyunOSS) {
                            is = OssService.getObjectStream(config, currentBucket, item.getKey());
                        } else {
                            is = S3Service.getObjectStream(config, currentBucket, item.getKey());
                        }
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = is.read(buffer)) != -1) {
                            baos.write(buffer, 0, len);
                        }
                        is.close();
                        byte[] imageBytes = baos.toByteArray();

                        Platform.runLater(() -> {
                            try {
                                Image image = new Image(new java.io.ByteArrayInputStream(imageBytes));
                                if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0) return;
                                updateIconBoxWithThumbnail(item, image);
                            } catch (Exception ignored) {}
                        });
                    } catch (Exception ignored) {}
                }, "S3-Thumb-" + item.getName()).start();
            }
        }
    }

    /**
     * 返回上级目录
     */
    private void navigateUp() {
        if (currentBucket == null) return;

        if (currentPrefix == null || currentPrefix.isEmpty()) {
            pathHistory.clear();
            loadBuckets();
        } else {
            String parentPrefix = getParentPrefix(currentPrefix);
            loadObjects(currentBucket, parentPrefix);
        }
    }

    private String getParentPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return "";
        String trimmed = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        int lastSlash = trimmed.lastIndexOf('/');
        if (lastSlash < 0) return "";
        return trimmed.substring(0, lastSlash + 1);
    }

    /**
     * 刷新当前视图
     */
    public void refresh() {
        if (currentBucket == null) {
            loadBuckets();
        } else {
            loadObjects(currentBucket, currentPrefix);
        }
    }

    private void updatePathLabel() {
        if (currentBucket == null) {
            currentPathField.setText("/");
        } else {
            String path = "/" + currentBucket + "/" + (currentPrefix != null ? currentPrefix : "");
            currentPathField.setText(path);
        }
    }

    /**
     * 跳转到指定路径（由路径输入框回车触发）
     * 支持格式："/"（根=Bucket列表）、"/bucket"、"/bucket/prefix/"、"/bucket/prefix1/prefix2"
     */
    private void navigateToPath(String input) {
        if (input == null) return;
        String path = input.trim();
        if (path.isEmpty()) {
            updatePathLabel();
            return;
        }

        // 规范化：确保以 / 开头
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        // 去除多余的末尾 /
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        // 根目录：加载 Bucket 列表
        if (path.equals("/")) {
            pathHistory.clear();
            loadBuckets();
            return;
        }

        // /bucket 或 /bucket/prefix1/prefix2
        String rest = path.substring(1);
        int firstSlash = rest.indexOf('/');
        String bucket;
        String prefix;
        if (firstSlash < 0) {
            bucket = rest;
            prefix = "";
        } else {
            bucket = rest.substring(0, firstSlash);
            prefix = rest.substring(firstSlash + 1);
            if (!prefix.isEmpty() && !prefix.endsWith("/")) {
                prefix = prefix + "/";
            }
        }

        if (bucket.isEmpty()) {
            pathHistory.clear();
            loadBuckets();
            return;
        }

        loadObjects(bucket, prefix);
    }

    private void handleCreateBucket() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("新建Bucket");
        dialog.setHeaderText(null);
        dialog.setContentText("Bucket名称：");
        dialog.showAndWait().ifPresent(name -> {
            if (name.trim().isEmpty()) return;
            String bucketName = name.trim();

            new Thread(() -> {
                try {
                    if (isAliyunOSS) {
                        OssService.createBucket(config, bucketName);
                    } else {
                        S3Service.createBucket(config, bucketName);
                    }
                    Platform.runLater(() -> loadBuckets());
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("创建失败");
                        alert.setHeaderText(null);
                        alert.setContentText("创建Bucket失败: " + e.getMessage());
                        alert.showAndWait();
                    });
                }
            }, "S3-Rename").start();
        });
    }

    private void handleDelete() {
        List<FileItem> toDelete = getSelectedItems();
        if (toDelete.isEmpty()) return;

        // 构建确认消息
        String msg;
        if (toDelete.size() == 1) {
            FileItem selected = toDelete.get(0);
            if (selected.isBucket()) {
                msg = "确定要删除Bucket \"" + selected.getName() + "\" 吗？Bucket必须为空才能删除。";
            } else {
                msg = "确定要删除文件 \"" + selected.getName() + "\" 吗？";
            }
        } else {
            msg = "确定要删除选中的 " + toDelete.size() + " 个文件吗？";
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除确认");
        confirm.setHeaderText(null);
        confirm.setContentText(msg);

        confirm.showAndWait().ifPresent(response -> {
            if (response != ButtonType.OK) return;

            new Thread(() -> {
                int success = 0;
                int failed = 0;
                String lastError = null;
                for (FileItem selected : toDelete) {
                    try {
                        if (selected.isBucket()) {
                            if (isAliyunOSS) {
                                throw new Exception("请通过管理控制台删除Bucket");
                            } else {
                                S3Service.deleteBucket(config, selected.getName());
                            }
                        } else {
                            if (isAliyunOSS) {
                                OssService.deleteObject(config, currentBucket, selected.getKey());
                            } else {
                                S3Service.deleteObject(config, currentBucket, selected.getKey());
                            }
                        }
                        success++;
                    } catch (Exception e) {
                        failed++;
                        lastError = e.getMessage();
                    }
                }
                final int okCount = success;
                final int failCount = failed;
                final String err = lastError;
                Platform.runLater(() -> {
                    if (failCount == 0) {
                        stateLabel.setText("删除完成: 成功 " + okCount + " 个");
                    } else {
                        stateLabel.setText("删除结束: 成功 " + okCount + " 个, 失败 " + failCount + " 个");
                        Alert alert = new Alert(Alert.AlertType.WARNING);
                        alert.setTitle("部分删除失败");
                        alert.setHeaderText("成功 " + okCount + " 个, 失败 " + failCount + " 个");
                        alert.setContentText(err != null ? err : "");
                        alert.showAndWait();
                    }
                    refresh();
                });
            }, "S3-Delete").start();
        });
    }

    // ============ 文件复制/粘贴功能 ============

    /**
     * 复制文件/目录信息到系统剪贴板（只记录元信息，不读取文件内容）
     */
    private void handleCopy() {
        List<FileItem> selected = getSelectedItems();
        if (selected.isEmpty()) return;

        // 过滤掉Bucket级别，支持文件和目录
        List<FileItem> items = new ArrayList<>();
        for (FileItem item : selected) {
            if (!item.isBucket()) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            stateLabel.setText("无法复制Bucket");
            return;
        }

        // 构建剪贴板数据：每行一条记录，格式为 "TYPE|KEY"
        // TYPE: F=文件, D=目录
        StringBuilder sb = new StringBuilder();
        sb.append(config.getId()).append("\n");
        sb.append(config.getType().name()).append("\n");
        sb.append(currentBucket).append("\n");

        for (FileItem item : items) {
            String type = item.isDirectory() ? "D" : "F";
            sb.append(type).append("|").append(item.getKey()).append("\n");
        }

        ClipboardContent content = new ClipboardContent();
        content.put(S3_COPY_FORMAT, sb.toString());
        int fileCount = 0;
        int dirCount = 0;
        for (FileItem item : items) {
            if (item.isDirectory()) dirCount++;
            else fileCount++;
        }
        String desc = fileCount + " 个文件" + (dirCount > 0 ? ", " + dirCount + " 个目录" : "");
        content.putString(desc);
        Clipboard.getSystemClipboard().setContent(content);

        stateLabel.setText("已复制 " + desc + " 到剪贴板");
    }

    /**
     * 从剪贴板粘贴文件/目录到当前位置
     */
    private void handlePaste() {
        if (currentBucket == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("提示");
            alert.setHeaderText(null);
            alert.setContentText("请先进入一个 Bucket 再粘贴");
            alert.showAndWait();
            return;
        }

        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (!clipboard.hasContent(S3_COPY_FORMAT)) {
            return;
        }

        String data = (String) clipboard.getContent(S3_COPY_FORMAT);
        if (data == null || data.isEmpty()) return;

        try {
            parseAndExecuteCopy(data);
        } catch (Exception e) {
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("粘贴失败");
                alert.setHeaderText(null);
                alert.setContentText("解析剪贴板数据失败: " + e.getMessage());
                alert.showAndWait();
            });
        }
    }

    /**
     * 检查剪贴板是否有S3复制数据
     */
    private boolean hasS3CopyData() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        return clipboard.hasContent(S3_COPY_FORMAT);
    }

    /**
     * 复制项数据模型（记录元信息）
     */
    private static class CopyItem {
        String key;
        boolean isDirectory;

        CopyItem(String key, boolean isDirectory) {
            this.key = key;
            this.isDirectory = isDirectory;
        }

        /**
         * 获取显示名称（去掉路径前缀的最后一部分）
         */
        String getDisplayName() {
            if (key == null) return "";
            String displayKey = key;
            if (displayKey.endsWith("/")) {
                displayKey = displayKey.substring(0, displayKey.length() - 1);
            }
            int lastSlash = displayKey.lastIndexOf('/');
            if (lastSlash >= 0) {
                return displayKey.substring(lastSlash + 1);
            }
            return displayKey;
        }
    }

    /**
     * 解析剪贴板数据并执行复制
     */
    private void parseAndExecuteCopy(String data) throws Exception {
        String[] lines = data.split("\n");
        if (lines.length < 3) {
            throw new Exception("无效的复制数据");
        }

        String sourceConfigId = lines[0];
        String sourceTypeName = lines[1];
        String sourceBucket = lines[2];

        // 查找源连接配置
        ConnectionConfig sourceConfig = findConfigById(sourceConfigId);
        if (sourceConfig == null) {
            throw new Exception("找不到源连接配置: " + sourceConfigId);
        }

        // 解析复制项列表
        List<CopyItem> copyItems = new ArrayList<>();
        for (int i = 3; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            String[] parts = lines[i].split("\\|", 2);
            if (parts.length == 2) {
                boolean isDir = "D".equals(parts[0]);
                copyItems.add(new CopyItem(parts[1], isDir));
            }
        }

        if (copyItems.isEmpty()) {
            throw new Exception("没有要复制的项");
        }

        // 检查是否为相同连接
        boolean sameConnection = config.getId().equals(sourceConfigId);
        boolean sameType = config.getType().name().equals(sourceTypeName);

        System.out.println("[S3-Paste] 解析剪贴板: 源=" + sourceConfig.getName()
                + ", 类型=" + sourceTypeName + ", bucket=" + sourceBucket
                + ", 项数=" + copyItems.size()
                + ", sameConnection=" + sameConnection + ", sameType=" + sameType);

        // 先显示进度对话框（初始状态）
        showCopyProgressDialog(0);

        if (sameConnection && sameType) {
            // 相同连接同类型：使用服务端复制
            executeServerSideCopy(sourceConfig, sourceBucket, copyItems);
        } else {
            // 跨连接或跨类型复制
            executeCrossConnectionCopy(sourceConfig, sourceBucket, copyItems);
        }
    }

    /**
     * 展开复制项：递归遍历目录，生成最终的文件复制任务列表
     * 只在粘贴执行时才读取目录内容，符合"记录元信息，执行时再读取"的设计
     */
    private List<CopyTask> expandCopyItems(ConnectionConfig sourceConfig, String sourceBucket,
                                            List<CopyItem> copyItems, ProgressTracker tracker) throws Exception {
        List<CopyTask> tasks = new ArrayList<>();
        boolean sourceIsOSS = sourceConfig.getType() == ConnectType.ALIYUN_OSS;

        for (CopyItem item : copyItems) {
            if (copyCancelled.get()) break;

            if (!item.isDirectory) {
                // 文件：直接生成复制任务
                String destKey = buildDestKey(item.key);
                tasks.add(new CopyTask(item.key, destKey, item.getDisplayName(), false));

                // 更新进度（枚举阶段）
                if (tracker != null) {
                    tracker.onEnumerate(item.getDisplayName(), tasks.size());
                }
            } else {
                // 目录：递归列出所有文件
                String dirPrefix = item.key.endsWith("/") ? item.key : item.key + "/";
                String dirName = item.getDisplayName();
                String destDirPrefix = buildDestKey(dirPrefix);

                // 更新进度：正在扫描目录
                if (tracker != null) {
                    tracker.onEnumerate("扫描目录: " + dirName + "/...", tasks.size());
                }

                List<? extends Object> objects;
                System.out.println("[S3-Paste] 递归列出目录: " + dirPrefix + (sourceIsOSS ? " (OSS)" : " (S3)"));
                if (sourceIsOSS) {
                    objects = OssService.listObjectsRecursive(sourceConfig, sourceBucket, dirPrefix);
                } else {
                    objects = S3Service.listObjectsRecursive(sourceConfig, sourceBucket, dirPrefix);
                }
                System.out.println("[S3-Paste] 目录列出完成: " + dirPrefix + ", 对象数=" + objects.size());

                for (Object obj : objects) {
                    if (copyCancelled.get()) break;

                    String srcKey;
                    long size;
                    if (sourceIsOSS) {
                        OssService.OssObjectInfo o = (OssService.OssObjectInfo) obj;
                        srcKey = o.getKey();
                        size = o.getSize();
                    } else {
                        S3Service.S3ObjectInfo o = (S3Service.S3ObjectInfo) obj;
                        srcKey = o.getKey();
                        size = o.getSize();
                    }

                    // 计算目标key：将源路径中的目录前缀替换为目标路径前缀
                    String relativeKey = srcKey.substring(dirPrefix.length());
                    String destKey = destDirPrefix + relativeKey;

                    // 获取文件名用于进度显示
                    String fileName = relativeKey;
                    int lastSlash = relativeKey.lastIndexOf('/');
                    if (lastSlash >= 0) {
                        fileName = relativeKey.substring(lastSlash + 1);
                    }

                    tasks.add(new CopyTask(srcKey, destKey, dirName + "/" + fileName, true));

                    // 更新进度（枚举阶段）
                    if (tracker != null) {
                        tracker.onEnumerate(dirName + "/" + fileName, tasks.size());
                    }
                }
            }
        }

        return tasks;
    }

    /**
     * 构建目标key：将源项放到当前前缀下
     */
    private String buildDestKey(String sourceKey) {
        String base = currentPrefix != null ? currentPrefix : "";
        String displayKey = sourceKey;
        if (displayKey.endsWith("/")) {
            displayKey = displayKey.substring(0, displayKey.length() - 1);
        }
        int lastSlash = displayKey.lastIndexOf('/');
        String name = lastSlash >= 0 ? displayKey.substring(lastSlash + 1) : displayKey;

        if (sourceKey.endsWith("/")) {
            return base + name + "/";
        }
        return base + name;
    }

    /**
     * 复制任务数据模型
     */
    private static class CopyTask {
        String sourceKey;
        String destKey;
        String displayName;
        boolean fromDirectory;

        CopyTask(String sourceKey, String destKey, String displayName, boolean fromDirectory) {
            this.sourceKey = sourceKey;
            this.destKey = destKey;
            this.displayName = displayName;
            this.fromDirectory = fromDirectory;
        }
    }

    /**
     * 进度追踪接口
     */
    private interface ProgressTracker {
        void onEnumerate(String currentItem, int totalFound);
    }

    /**
     * 服务端复制（同连接同类型）
     */
    private void executeServerSideCopy(ConnectionConfig sourceConfig, String sourceBucket, List<CopyItem> copyItems) {
        copyCancelled.set(false);

        new Thread(() -> {
            // 第1阶段：递归展开目录，生成文件复制任务列表
            final List<CopyTask>[] taskHolder = new List[1];
            try {
                taskHolder[0] = expandCopyItems(sourceConfig, sourceBucket, copyItems, (item, total) -> {
                    Platform.runLater(() -> {
                        if (copyProgressLabel != null) {
                            copyProgressLabel.setText("正在扫描: " + item);
                        }
                        if (copyProgressDetailLabel != null) {
                            copyProgressDetailLabel.setText("已发现 " + total + " 个文件");
                        }
                    });
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    hideCopyProgressDialog();
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("扫描失败");
                    alert.setHeaderText(null);
                    alert.setContentText("扫描目录失败: " + e.getMessage());
                    alert.showAndWait();
                });
                return;
            }

            final List<CopyTask> tasks = taskHolder[0];
            if (tasks.isEmpty()) {
                Platform.runLater(() -> {
                    hideCopyProgressDialog();
                    stateLabel.setText("没有文件可复制");
                });
                return;
            }

            // 更新进度对话框：进入复制阶段
            Platform.runLater(() -> {
                if (copyProgressLabel != null) {
                    copyProgressLabel.setText("准备复制 " + tasks.size() + " 个文件...");
                }
                if (copyProgressBar != null) {
                    copyProgressBar.setProgress(0);
                }
            });

            // 第2阶段：逐个复制文件
            int success = 0;
            int failed = 0;
            String lastError = null;

            for (int i = 0; i < tasks.size(); i++) {
                if (copyCancelled.get()) break;

                final int index = i;
                CopyTask task = tasks.get(i);

                try {
                    if (isAliyunOSS) {
                        OssService.copyAcrossOSS(sourceConfig, sourceBucket, task.sourceKey,
                                config, currentBucket, task.destKey, null);
                    } else {
                        // 服务端复制：目标Bucket使用当前Bucket（支持跨Bucket）
                        S3Service.copyObjectAcrossBucket(sourceConfig, sourceBucket, task.sourceKey,
                                currentBucket, task.destKey);
                    }
                    success++;
                } catch (Throwable e) {
                    System.out.println("[S3-Paste] 服务端复制失败: " + task.sourceKey + " -> " + e);
                    e.printStackTrace();
                    failed++;
                    lastError = String.valueOf(e.getMessage());
                    if (lastError == null || lastError.isEmpty()) {
                        lastError = e.getClass().getSimpleName();
                    }
                }

                final int done = success + failed;
                Platform.runLater(() -> updateCopyProgress(index, task.displayName, done, tasks.size()));
            }

            final int okCount = success;
            final int failCount = failed;
            final String err = lastError;

            Platform.runLater(() -> {
                hideCopyProgressDialog();
                if (failCount == 0) {
                    stateLabel.setText("粘贴完成: 成功 " + okCount + " 个");
                } else {
                    stateLabel.setText("粘贴结束: 成功 " + okCount + " 个, 失败 " + failCount + " 个");
                    Alert alert = new Alert(Alert.AlertType.WARNING);
                    alert.setTitle("部分粘贴失败");
                    alert.setHeaderText("成功 " + okCount + " 个, 失败 " + failCount + " 个");
                    alert.setContentText(err != null ? err : "");
                    alert.showAndWait();
                }
                refresh();
            });
        }, "S3-Paste-Server").start();
    }

    /**
     * 跨连接/跨类型复制
     */
    private void executeCrossConnectionCopy(ConnectionConfig sourceConfig, String sourceBucket, List<CopyItem> copyItems) {
        copyCancelled.set(false);

        new Thread(() -> {
            System.out.println("[S3-Paste] 跨连接复制开始: 源=" + sourceConfig.getName()
                    + ", bucket=" + sourceBucket + ", 项数=" + copyItems.size());
            // 第1阶段：递归展开目录，生成文件复制任务列表
            final List<CopyTask>[] taskHolder = new List[1];
            try {
                taskHolder[0] = expandCopyItems(sourceConfig, sourceBucket, copyItems, (item, total) -> {
                    System.out.println("[S3-Paste] 扫描发现: " + item + " (共" + total + ")");
                    Platform.runLater(() -> {
                        if (copyProgressLabel != null) {
                            copyProgressLabel.setText("正在扫描: " + item);
                        }
                        if (copyProgressDetailLabel != null) {
                            copyProgressDetailLabel.setText("已发现 " + total + " 个文件");
                        }
                    });
                });
            } catch (Throwable e) {
                System.out.println("[S3-Paste] 扫描异常: " + e);
                e.printStackTrace();
                Platform.runLater(() -> {
                    hideCopyProgressDialog();
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("扫描失败");
                    alert.setHeaderText(null);
                    alert.setContentText("扫描目录失败: " + e);
                    alert.showAndWait();
                });
                return;
            }

            final List<CopyTask> tasks = taskHolder[0];
            if (tasks.isEmpty()) {
                Platform.runLater(() -> {
                    hideCopyProgressDialog();
                    stateLabel.setText("没有文件可复制");
                });
                return;
            }

            // 更新进度对话框：进入复制阶段
            Platform.runLater(() -> {
                if (copyProgressLabel != null) {
                    copyProgressLabel.setText("准备复制 " + tasks.size() + " 个文件...");
                }
                if (copyProgressBar != null) {
                    copyProgressBar.setProgress(0);
                }
            });

            // 目标连接预检：确认目标可达且Bucket存在（把"网络/凭证问题"从卡死变成明确报错）
            try {
                System.out.println("[S3-Paste] 预检目标连接...");
                if (isAliyunOSS) {
                    OssService.listBuckets(config);
                } else {
                    S3Service.listBuckets(config);
                }
                System.out.println("[S3-Paste] 目标连接预检通过");
            } catch (Throwable t) {
                System.out.println("[S3-Paste] 目标连接预检失败: " + t);
                t.printStackTrace();
                Platform.runLater(() -> {
                    hideCopyProgressDialog();
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("目标连接不可用");
                    alert.setHeaderText("无法连接目标 " + config.getName());
                    alert.setContentText(String.valueOf(t.getMessage()));
                    alert.showAndWait();
                });
                return;
            }

            // 看门狗：60秒无字节级进展则dump相关线程堆栈（定位挂起的唯一手段）
            final java.util.concurrent.atomic.AtomicLong lastActivity = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
            final java.util.concurrent.atomic.AtomicBoolean copyFinished = new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread watchdog = new Thread(() -> {
                int dumped = 0;
                while (!copyFinished.get() && dumped < 3) {
                    try { Thread.sleep(10000); } catch (InterruptedException e) { return; }
                    if (copyFinished.get()) return;
                    long idle = System.currentTimeMillis() - lastActivity.get();
                    if (idle > 60000) {
                        dumped++;
                        System.out.println("======== [S3-Paste-Watchdog] " + (idle / 1000) + "秒无进展，dump线程堆栈 (第" + dumped + "次) ========");
                        for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                            Thread t = e.getKey();
                            String n = t.getName();
                            if (n.contains("S3") || n.contains("OkHttp") || n.contains("MINIO")
                                    || n.contains("Paste") || n.contains("Copy") || n.contains("OSS")) {
                                System.out.println("--- 线程[" + n + "] 状态: " + t.getState());
                                for (StackTraceElement el : e.getValue()) {
                                    System.out.println("    at " + el);
                                }
                            }
                        }
                    }
                }
            }, "S3-Paste-Watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            // 第2阶段：逐个复制文件（支持进度回调）
            int success = 0;
            int failed = 0;
            String lastError = null;
            // 错误聚合：记录每种错误出现的次数，结果弹窗展示全部错误类型
            java.util.LinkedHashMap<String, Integer> errorCounts = new java.util.LinkedHashMap<>();

            for (int i = 0; i < tasks.size(); i++) {
                if (copyCancelled.get()) break;

                final int index = i;
                CopyTask task = tasks.get(i);

                try {
                    boolean sourceIsOSS = sourceConfig.getType() == ConnectType.ALIYUN_OSS;
                    boolean destIsOSS = isAliyunOSS;

                    S3Service.ProgressCallback progressCallback = new S3Service.ProgressCallback() {
                        private volatile String currentPhase = "";

                        @Override
                        public void onPhase(String phase) {
                            currentPhase = phase;
                            lastActivity.set(System.currentTimeMillis());
                            Platform.runLater(() -> {
                                if (copyProgressDetailLabel != null) {
                                    copyProgressDetailLabel.setText(phase + "中...");
                                }
                            });
                        }

                        @Override
                        public void onProgress(long transferred, long totalSize) {
                            lastActivity.set(System.currentTimeMillis());
                            String phase = currentPhase;
                            Platform.runLater(() -> updateCopyTransferProgress(index, task.displayName, phase, transferred, totalSize));
                        }
                    };

                    System.out.println("[S3-Paste] 复制文件 " + (i + 1) + "/" + tasks.size() + ": " + task.sourceKey
                            + " -> " + task.destKey + (sourceIsOSS ? " (OSS源)" : "") + (destIsOSS ? " (OSS目标)" : ""));
                    long fileStart = System.currentTimeMillis();

                    if (sourceIsOSS && destIsOSS) {
                        OssService.copyAcrossOSS(sourceConfig, sourceBucket, task.sourceKey,
                                config, currentBucket, task.destKey, progressCallback);
                    } else if (sourceIsOSS) {
                        copyFromOSStoS3(sourceConfig, sourceBucket, task.sourceKey,
                                config, currentBucket, task.destKey, progressCallback);
                    } else if (destIsOSS) {
                        copyFromS3toOSS(sourceConfig, sourceBucket, task.sourceKey,
                                config, currentBucket, task.destKey, progressCallback);
                    } else {
                        S3Service.copyAcrossS3(sourceConfig, sourceBucket, task.sourceKey,
                                config, currentBucket, task.destKey, progressCallback);
                    }
                    success++;
                    System.out.println("[S3-Paste] 文件完成 " + (i + 1) + "/" + tasks.size() + ", 耗时 "
                            + (System.currentTimeMillis() - fileStart) + "ms");
                } catch (Throwable e) {
                    // 用Throwable而非Exception：捕获NoClassDefFoundError等Error，避免线程被静默杀死导致弹窗卡死
                    System.out.println("[S3-Paste] 文件失败: " + task.sourceKey + " -> " + e);
                    e.printStackTrace();
                    failed++;
                    String msg = String.valueOf(e.getMessage());
                    if (msg == null || msg.isEmpty() || "null".equals(msg)) {
                        msg = e.getClass().getSimpleName();
                    }
                    lastError = msg;
                    errorCounts.merge(msg, 1, Integer::sum);
                }

                lastActivity.set(System.currentTimeMillis());
                final int done = success + failed;
                Platform.runLater(() -> updateCopyProgress(index, task.displayName, done, tasks.size()));
            }

            copyFinished.set(true);
            final int okCount = success;
            final int failCount = failed;
            final java.util.LinkedHashMap<String, Integer> errs = errorCounts;

            Platform.runLater(() -> {
                hideCopyProgressDialog();
                if (failCount == 0) {
                    stateLabel.setText("粘贴完成: 成功 " + okCount + " 个");
                } else {
                    stateLabel.setText("粘贴结束: 成功 " + okCount + " 个, 失败 " + failCount + " 个");
                    // 聚合展示错误类型（最多5种），便于定位根因
                    StringBuilder sb = new StringBuilder();
                    int shown = 0;
                    for (java.util.Map.Entry<String, Integer> en : errs.entrySet()) {
                        if (shown >= 5) { sb.append("...等共 ").append(errs.size()).append(" 种错误"); break; }
                        if (shown > 0) sb.append("\n");
                        sb.append("[").append(en.getValue()).append("次] ").append(en.getKey());
                        shown++;
                    }
                    Alert alert = new Alert(Alert.AlertType.WARNING);
                    alert.setTitle("部分粘贴失败");
                    alert.setHeaderText("成功 " + okCount + " 个, 失败 " + failCount + " 个");
                    alert.setContentText(sb.toString());
                    alert.showAndWait();
                }
                refresh();
            });
        }, "S3-Paste-Cross").start();
    }

    /**
     * 从OSS复制到S3（临时文件两阶段）
     */
    private void copyFromOSStoS3(ConnectionConfig sourceConfig, String sourceBucket, String sourceKey,
                                  ConnectionConfig destConfig, String destBucket, String destKey,
                                  S3Service.ProgressCallback callback) throws Exception {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("o2s-", ".part");
        try {
            // 第1阶段：下载OSS对象到临时文件
            java.io.InputStream sourceStream = OssService.getObjectStream(sourceConfig, sourceBucket, sourceKey);
            if (callback != null) callback.onPhase("下载");
            try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int len;
                long transferred = 0;
                long lastReportTime = 0;
                while ((len = sourceStream.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                    transferred += len;
                    long now = System.currentTimeMillis();
                    if (callback != null && now - lastReportTime > 200) {
                        lastReportTime = now;
                        callback.onProgress(transferred, -1);
                    }
                }
            } finally {
                try { sourceStream.close(); } catch (Exception e) { /* ignore */ }
            }

            // 第2阶段：从临时文件上传到S3（用OkHttp直传，绕开MinIO反射问题）
            long fileSize = java.nio.file.Files.size(tempFile);
            if (callback != null) {
                callback.onPhase("上传");
                callback.onProgress(0, fileSize);
            }
            S3Service.uploadFileDirect(destConfig, destBucket, destKey, tempFile.toFile(), fileSize, "application/octet-stream", callback);
        } finally {
            try { java.nio.file.Files.deleteIfExists(tempFile); } catch (Exception e) { /* ignore */ }
        }

        if (callback != null) callback.onComplete();
    }

    /**
     * 从S3复制到OSS（临时文件两阶段）
     */
    private void copyFromS3toOSS(ConnectionConfig sourceConfig, String sourceBucket, String sourceKey,
                                  ConnectionConfig destConfig, String destBucket, String destKey,
                                  S3Service.ProgressCallback callback) throws Exception {
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("s2o-", ".part");
        try {
            // 第1阶段：下载S3对象到临时文件
            java.io.InputStream sourceStream = S3Service.getObjectStream(sourceConfig, sourceBucket, sourceKey);
            if (callback != null) callback.onPhase("下载");
            try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int len;
                long transferred = 0;
                long lastReportTime = 0;
                while ((len = sourceStream.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                    transferred += len;
                    long now = System.currentTimeMillis();
                    if (callback != null && now - lastReportTime > 200) {
                        lastReportTime = now;
                        callback.onProgress(transferred, -1);
                    }
                }
            } finally {
                try { sourceStream.close(); } catch (Exception e) { /* ignore */ }
            }

            // 第2阶段：从临时文件上传到OSS
            long fileSize = java.nio.file.Files.size(tempFile);
            if (callback != null) {
                callback.onPhase("上传");
                callback.onProgress(0, fileSize);
            }
            try (java.io.InputStream upStream = new java.io.FileInputStream(tempFile.toFile())) {
                OssService.uploadFile(destConfig, destBucket, destKey, upStream, fileSize, "application/octet-stream");
            }
        } finally {
            try { java.nio.file.Files.deleteIfExists(tempFile); } catch (Exception e) { /* ignore */ }
        }

        if (callback != null) callback.onComplete();
    }

    /**
     * 显示复制进度对话框
     */
    private void showCopyProgressDialog(int totalFiles) {
        copyProgressStage = new Stage();
        copyProgressStage.setTitle("粘贴文件...");
        copyProgressStage.setWidth(500);
        copyProgressStage.setHeight(200);
        copyProgressStage.setResizable(false);
        copyProgressStage.initOwner(getScene().getWindow());

        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(15));
        vbox.setAlignment(Pos.CENTER_LEFT);

        Label titleLabel = new Label("正在粘贴...");
        titleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        copyProgressLabel = new Label("正在扫描目录结构...");
        copyProgressLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #333;");
        copyProgressLabel.setMaxWidth(470);
        copyProgressLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

        copyProgressBar = new ProgressBar(0);
        copyProgressBar.setMaxWidth(Double.MAX_VALUE);
        copyProgressBar.setStyle("-fx-accent: #3592CB;");

        copyProgressDetailLabel = new Label("");
        copyProgressDetailLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        copyProgressDetailLabel.setMaxWidth(470);
        copyProgressDetailLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);

        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #ddd;");
        cancelBtn.setOnAction(e -> copyCancelled.set(true));

        HBox buttonBox = new HBox();
        buttonBox.setAlignment(Pos.CENTER_RIGHT);
        buttonBox.getChildren().add(cancelBtn);

        vbox.getChildren().addAll(titleLabel, copyProgressLabel, copyProgressBar,
                copyProgressDetailLabel, buttonBox);

        Scene scene = new Scene(vbox);
        copyProgressStage.setScene(scene);
        DialogPositionUtil.centerOnOwner(copyProgressStage, this);
        copyProgressStage.show();
    }

    /**
     * 隐藏复制进度对话框
     */
    private void hideCopyProgressDialog() {
        if (copyProgressStage != null) {
            copyProgressStage.close();
            copyProgressStage = null;
        }
    }

    /**
     * 更新复制进度（文件级）
     */
    private void updateCopyProgress(int currentIndex, String fileName, int done, int total) {
        if (copyProgressBar == null || copyProgressLabel == null) return;

        double progress = (double) done / total;
        copyProgressBar.setProgress(progress);
        copyProgressLabel.setText(String.format("正在复制: %s (%d/%d)", fileName, done, total));
    }

    /**
     * 更新传输进度（字节级，含下载/上传阶段）
     */
    private void updateCopyTransferProgress(int currentIndex, String fileName, String phase, long transferred, long totalSize) {
        if (copyProgressDetailLabel == null) return;

        String phaseStr = (phase == null || phase.isEmpty()) ? "传输" : phase;
        String transferredStr = formatFileSize(transferred);
        if (totalSize > 0) {
            String totalStr = formatFileSize(totalSize);
            double percent = (double) transferred / totalSize * 100;
            copyProgressDetailLabel.setText(String.format("%s中: %s / %s (%.0f%%)", phaseStr, transferredStr, totalStr, percent));
        } else {
            copyProgressDetailLabel.setText(String.format("%s中: %s", phaseStr, transferredStr));
        }
    }

    /**
     * 格式化文件大小
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * 根据ID查找连接配置
     */
    private ConnectionConfig findConfigById(String id) {
        try {
            List<ConnectionConfig> all = ConfigManager.loadConnections();
            for (ConnectionConfig c : all) {
                if (id.equals(c.getId())) return c;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    // ============ 文件复制/粘贴功能结束 ============

    /**
     * 文件项数据模型
     */
    public static class FileItem {
        private String name;
        private String key;
        private boolean isDirectory;
        private boolean isBucket;
        private long size;
        private String lastModified;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }

        public boolean isDirectory() { return isDirectory; }
        public void setDirectory(boolean directory) { this.isDirectory = directory; }

        public boolean isBucket() { return isBucket; }
        public void setBucket(boolean bucket) { this.isBucket = bucket; }

        public long getSize() { return size; }
        public void setSize(long size) { this.size = size; }

        public String getLastModified() { return lastModified; }
        public void setLastModified(String lastModified) { this.lastModified = lastModified; }

        public String getDisplayName() { return name; }

        public String getFormattedSize() {
            if (isDirectory) return "";
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return (size / 1024) + " KB";
            if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
            return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
        }

        public String getLastModifiedDisplay() {
            if (lastModified == null || lastModified.isEmpty()) return "";
            try {
                if (lastModified.length() > 19) {
                    return lastModified.substring(0, 19);
                }
                return lastModified;
            } catch (Exception e) {
                return lastModified;
            }
        }
    }
}
