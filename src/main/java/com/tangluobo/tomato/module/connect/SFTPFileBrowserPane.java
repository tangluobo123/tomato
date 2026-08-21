package com.tangluobo.tomato.module.connect;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.tangluobo.tomato.ssh.SFTPClient;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.SnapshotParameters;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
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

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SFTP文件浏览器面板
 * 通过SSH会话建立SFTP通道，浏览远程文件系统
 * 展示样式参考S3FileBrowserPane：支持图标视图和列表视图、图片预览、Markdown编辑、拖拽上传/下载
 */
public class SFTPFileBrowserPane extends BorderPane {

    private final ConnectionConfig config;

    // SSH/SFTP
    private Session jschSession;
    private final SFTPClient sftpClient = new SFTPClient();

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
    private Button mkdirBtn;
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
    private final List<String> columnPaths = new ArrayList<>();

    // 数据
    private final ObservableList<FileItem> fileData = FXCollections.observableArrayList();

    // 当前路径
    private String currentPath = "/";

    // 选中状态
    private FileItem selectedItem = null;
    private final Set<FileItem> selectedItems = new HashSet<>();

    // 编辑器 Tab 页（中心区域：文件浏览 + 多个 markdown 编辑器）
    private TabPane editorTabPane;
    private Tab browseTab;

    // 图标
    private Image folderIcon;
    private Image folderLargeIcon;
    private Image fileIcon;
    private Image fileLargeIcon;
    private Image imageFileIcon;
    private Image imageFileLargeIcon;

    // 框选
    private Rectangle selectionRect;
    private double rubberBandStartX, rubberBandStartY;

    public SFTPFileBrowserPane(ConnectionConfig config) {
        this.config = config;

        loadIcons();
        initializeUI();
        switchViewMode(currentViewMode);
        connectAndLoad();
    }

    private void loadIcons() {
        try { folderIcon = new Image(getClass().getResourceAsStream("/images/connect/folder.png")); } catch (Exception e) { folderIcon = null; }
        try { folderLargeIcon = new Image(getClass().getResourceAsStream("/images/connect/folder.png"), 48, 48, true, true); } catch (Exception e) { folderLargeIcon = null; }

        fileIcon = createFileIcon(16);
        fileLargeIcon = createFileIcon(48);

        imageFileIcon = createImageFileIcon(16);
        imageFileLargeIcon = createImageFileIcon(48);
    }

    private Image createFileIcon(int size) {
        Pane pane = new Pane();
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

        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        return pane.snapshot(sp, null);
    }

    private Image createImageFileIcon(int size) {
        Pane pane = new Pane();
        pane.setPrefSize(size, size);

        double s = size;
        javafx.scene.shape.Rectangle body = new javafx.scene.shape.Rectangle(s * 0.15, s * 0.05, s * 0.55, s * 0.9);
        body.setFill(Color.WHITE);
        body.setStroke(Color.valueOf("#4CAF50"));
        body.setStrokeWidth(Math.max(1, size * 0.04));
        body.setArcWidth(s * 0.06);
        body.setArcHeight(s * 0.06);

        javafx.scene.shape.Polygon ear = new javafx.scene.shape.Polygon();
        ear.getPoints().addAll(s * 0.55, s * 0.05, s * 0.55, s * 0.25, s * 0.85, s * 0.25);
        ear.setFill(Color.valueOf("#C8E6C9"));
        ear.setStroke(Color.valueOf("#4CAF50"));
        ear.setStrokeWidth(Math.max(1, size * 0.04));

        javafx.scene.shape.Circle sun = new javafx.scene.shape.Circle(s * 0.32, s * 0.35, s * 0.06);
        sun.setFill(Color.valueOf("#FFC107"));
        javafx.scene.shape.Polygon mountain = new javafx.scene.shape.Polygon();
        mountain.getPoints().addAll(
                s * 0.22, s * 0.7,
                s * 0.38, s * 0.42,
                s * 0.54, s * 0.7
        );
        mountain.setFill(Color.valueOf("#66BB6A"));
        javafx.scene.shape.Polygon smallMountain = new javafx.scene.shape.Polygon();
        smallMountain.getPoints().addAll(
                s * 0.4, s * 0.7,
                s * 0.52, s * 0.5,
                s * 0.62, s * 0.7
        );
        smallMountain.setFill(Color.valueOf("#81C784"));

        pane.getChildren().addAll(body, ear, sun, mountain, smallMountain);

        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        return pane.snapshot(sp, null);
    }

    private boolean isImageFile(String name) {
        if (name == null) return false;
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == name.length() - 1) return false;
        String ext = name.substring(dotIdx + 1).toLowerCase();
        return IMAGE_EXTENSIONS.contains(ext);
    }

    private boolean isMarkdownFile(String name) {
        if (name == null) return false;
        int dotIdx = name.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == name.length() - 1) return false;
        String ext = name.substring(dotIdx + 1).toLowerCase();
        return MARKDOWN_EXTENSIONS.contains(ext);
    }

    private Image getIconForItem(FileItem item, boolean large) {
        if (item.isDirectory()) {
            return large ? folderLargeIcon : folderIcon;
        }
        if (isImageFile(item.getDisplayName())) {
            return large ? imageFileLargeIcon : imageFileIcon;
        }
        return large ? fileLargeIcon : fileIcon;
    }

    private void initializeUI() {
        // 当前路径输入框
        currentPathField = new TextField("/");
        currentPathField.setPrefHeight(25);
        currentPathField.setMinWidth(0);
        currentPathField.setPrefWidth(0);
        currentPathField.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(currentPathField, Priority.ALWAYS);
        currentPathField.setStyle("-fx-font-size: 12px; -fx-text-fill: #333; -fx-background-color: white; -fx-background-insets: 0; -fx-background-radius: 0; -fx-padding: 2 6; -fx-border-color: #3399ff; -fx-border-width: 1; -fx-border-insets: 0; -fx-border-radius: 0;");
        currentPathField.setTooltip(new Tooltip("点击编辑路径，回车进入目录"));
        currentPathField.focusedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                Platform.runLater(currentPathField::selectAll);
            } else {
                updatePathLabel();
            }
        });
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

        // 中心区域：TabPane
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
        fileTable.setRowFactory(tv -> {
            TableRow<FileItem> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !row.isEmpty()) {
                    FileItem item = row.getItem();
                    handleDoubleClick(item);
                } else if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 1) {
                    selectedItem = row.getItem();
                } else if (event.getButton() == MouseButton.SECONDARY && !row.isEmpty()) {
                    // 右键先选中行，再弹出右键菜单
                    fileTable.getSelectionModel().select(row.getIndex());
                    selectedItem = row.getItem();
                }
            });
            // 拖拽下载
            row.setOnDragDetected(event -> {
                if (row.isEmpty()) return;
                FileItem item = row.getItem();
                if (!item.isDirectory()) {
                    File tempFile = downloadToTemp(item);
                    if (tempFile != null) {
                        Dragboard db = row.startDragAndDrop(TransferMode.COPY);
                        ClipboardContent content = new ClipboardContent();
                        content.putFiles(java.util.Collections.singletonList(tempFile));
                        db.setContent(content);
                    }
                }
                event.consume();
            });
            // 拖拽上传
            row.setOnDragOver(event -> {
                if (event.getDragboard().hasFiles()) {
                    event.acceptTransferModes(TransferMode.COPY);
                }
                event.consume();
            });
            row.setOnDragDropped(event -> {
                Dragboard db = event.getDragboard();
                boolean success = false;
                if (db.hasFiles()) {
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
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        fileTable.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                uploadLocalFiles(db.getFiles());
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
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
        typeCol.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().isDirectory() ? "目录" : (isImageFile(data.getValue().getDisplayName()) ? "图片" : "文件")));
        typeCol.setPrefWidth(80);

        fileTable.getColumns().addAll(nameCol, sizeCol, modifiedCol, typeCol);
        fileTable.setContextMenu(createContextMenu());
    }

    private void initIconView() {
        iconFlowPane = new FlowPane();
        iconFlowPane.setHgap(8);
        iconFlowPane.setVgap(8);
        iconFlowPane.setPadding(new Insets(12));
        iconFlowPane.setStyle("-fx-background-color: white;");

        // 框选矩形
        selectionRect = new Rectangle();
        selectionRect.setFill(Color.rgb(51, 153, 255, 0.15));
        selectionRect.setStroke(Color.rgb(51, 153, 255, 0.8));
        selectionRect.setStrokeWidth(1);
        selectionRect.setManaged(false);
        selectionRect.setMouseTransparent(true);
        selectionRect.setVisible(false);
        iconFlowPane.getChildren().add(selectionRect);

        iconScrollPane = new ScrollPane(iconFlowPane);
        iconScrollPane.setFitToWidth(true);
        iconScrollPane.setFitToHeight(true);
        iconScrollPane.setStyle("-fx-background-color: white;");
        iconScrollPane.setContextMenu(createContextMenu());

        // 框选：空白区域按住左键拖动
        iconFlowPane.setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getTarget() == iconFlowPane) {
                // 开始框选
                clearIconSelection();
                selectedItem = null;

                rubberBandStartX = e.getX();
                rubberBandStartY = e.getY();
                selectionRect.setX(e.getX());
                selectionRect.setY(e.getY());
                selectionRect.setWidth(0);
                selectionRect.setHeight(0);
                selectionRect.setVisible(true);
                e.consume();
            }
        });

        iconFlowPane.setOnMouseDragged(e -> {
            if (!selectionRect.isVisible()) return;
            double x = Math.min(rubberBandStartX, e.getX());
            double y = Math.min(rubberBandStartY, e.getY());
            double w = Math.abs(e.getX() - rubberBandStartX);
            double h = Math.abs(e.getY() - rubberBandStartY);
            selectionRect.setX(x);
            selectionRect.setY(y);
            selectionRect.setWidth(w);
            selectionRect.setHeight(h);
            updateRubberBandSelection(x, y, w, h);
            e.consume();
        });

        iconFlowPane.setOnMouseReleased(e -> {
            if (selectionRect.isVisible()) {
                selectionRect.setVisible(false);
                selectionRect.setWidth(0);
                selectionRect.setHeight(0);
                e.consume();
            }
        });

        setupDragUpload(iconFlowPane);
        setupDragUpload(iconScrollPane);
    }

    private void setupDragUpload(Node node) {
        node.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        node.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
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
     * 根据当前 currentPath 和 fileData 重建列视图（单列起始）
     */
    private void rebuildColumnView() {
        columnContainer.getChildren().clear();
        columnListViews.clear();
        columnItems.clear();
        columnPaths.clear();

        ObservableList<FileItem> colData = FXCollections.observableArrayList(fileData);
        columnPaths.add(currentPath != null ? currentPath : "/");
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
            if (event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        lv.setOnDragDropped(event -> {
            Dragboard db = event.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
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
                    content.putFiles(java.util.Collections.singletonList(tempFile));
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
            loadColumnAsync(item.getPath());
            updatePathFromColumns();
        } else {
            updatePathFromColumns();
        }
    }

    /**
     * 根据列视图的路径栈更新路径输入框
     */
    private void updatePathFromColumns() {
        if (columnPaths.isEmpty()) return;
        String lastPath = columnPaths.get(columnPaths.size() - 1);
        currentPath = lastPath;
        currentPathField.setText(lastPath);
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
            columnPaths.remove(last);
        }
    }

    /**
     * 异步加载子目录并添加为新列
     */
    private void loadColumnAsync(String path) {
        new Thread(() -> {
            try {
                if (!sftpClient.isConnected()) sftpClient.reconnect();
                sftpClient.cd(path);
                String realPath = sftpClient.pwd();
                List<SFTPClient.FileEntry> entries = sftpClient.listFiles(realPath);
                List<FileItem> items = new ArrayList<>();
                for (SFTPClient.FileEntry entry : entries) {
                    FileItem item = new FileItem();
                    item.setName(entry.getName());
                    item.setPath(entry.getPath());
                    item.setDirectory(entry.isDirectory());
                    item.setSize(entry.getSize());
                    item.setLastModified(entry.getModifyTime());
                    items.add(item);
                }
                Platform.runLater(() -> {
                    addColumn(realPath, items);
                    stateLabel.setText(items.size() + " 个条目");
                });
            } catch (Exception e) {
                Platform.runLater(() -> stateLabel.setText("错误: " + e.getMessage()));
            }
        }, "SFTP-ColumnLoad").start();
    }

    /**
     * 添加新列并滚动到最右侧
     */
    private void addColumn(String path, List<FileItem> items) {
        ObservableList<FileItem> colData = FXCollections.observableArrayList(items);
        columnPaths.add(path);
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

        browseTab.setContent(centerBox);
        editorTabPane.getSelectionModel().select(browseTab);
    }

    private void rebuildIconView() {
        iconFlowPane.getChildren().clear();

        for (FileItem item : fileData) {
            VBox iconBox = createIconBox(item);
            iconFlowPane.getChildren().add(iconBox);
        }

        // 重新添加框选矩形（保持在最上层）
        if (selectionRect != null) {
            iconFlowPane.getChildren().add(selectionRect);
        }
        selectedItems.clear();
    }

    private VBox createIconBox(FileItem item) {
        VBox box = new VBox(4);
        box.setAlignment(Pos.TOP_CENTER);
        box.setPrefWidth(90);
        box.setPadding(new Insets(6, 4, 6, 4));
        box.setStyle("-fx-background-color: transparent; -fx-background-radius: 6; -fx-cursor: hand;");
        box.setPickOnBounds(true);
        box.getProperties().put("fileItem", item);

        ImageView iconView = new ImageView();
        iconView.setImage(getIconForItem(item, true));
        iconView.setFitWidth(48);
        iconView.setFitHeight(48);
        iconView.setPreserveRatio(true);
        iconView.setSmooth(true);
        iconView.setMouseTransparent(true);
        box.getChildren().add(iconView);

        Label nameLabel = new Label(item.getDisplayName());
        nameLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #333; -fx-alignment: CENTER;");
        nameLabel.setWrapText(false);
        nameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        nameLabel.setMaxWidth(82);
        nameLabel.setAlignment(Pos.CENTER);
        nameLabel.setMouseTransparent(true);
        box.getChildren().add(nameLabel);

        box.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                clearIconSelection();
                selectIconBox(box, item);
                selectedItem = item;
                selectedItems.clear();
                selectedItems.add(item);

                if (e.getClickCount() == 2) {
                    handleDoubleClick(item);
                }
            } else if (e.getButton() == MouseButton.SECONDARY) {
                // 右键先选中，再弹出右键菜单
                if (!selectedItems.contains(item)) {
                    clearIconSelection();
                    selectIconBox(box, item);
                    selectedItem = item;
                    selectedItems.clear();
                    selectedItems.add(item);
                }
            }
        });

        box.setOnDragDetected(e -> {
            if (!item.isDirectory()) {
                File tempFile = downloadToTemp(item);
                if (tempFile != null) {
                    Dragboard db = box.startDragAndDrop(TransferMode.COPY);
                    ClipboardContent content = new ClipboardContent();
                    content.putFiles(java.util.Collections.singletonList(tempFile));
                    db.setContent(content);
                }
            }
            e.consume();
        });

        box.setOnMouseEntered(e -> {
            if (box.getUserData() != "selected") {
                box.setStyle("-fx-background-color: #f0f7ff; -fx-background-radius: 6; -fx-cursor: hand;");
            }
        });
        box.setOnMouseExited(e -> {
            if (box.getUserData() != "selected") {
                box.setStyle("-fx-background-color: transparent; -fx-background-radius: 6; -fx-cursor: hand;");
            }
        });

        return box;
    }

    private void selectIconBox(VBox box, FileItem item) {
        box.setUserData("selected");
        box.setStyle("-fx-background-color: #cce5ff; -fx-background-radius: 6; -fx-cursor: hand; -fx-border-color: #3399ff; -fx-border-width: 1; -fx-border-radius: 6;");
    }

    private void clearIconSelection() {
        for (var node : iconFlowPane.getChildren()) {
            if (node instanceof VBox box) {
                box.setUserData(null);
                box.setStyle("-fx-background-color: transparent; -fx-background-radius: 6; -fx-cursor: hand;");
            }
        }
        selectedItems.clear();
    }

    /**
     * 框选过程中更新选中项：框选矩形与图标相交则选中，不相交则取消
     */
    private void updateRubberBandSelection(double rx, double ry, double rw, double rh) {
        selectedItems.clear();
        for (var node : iconFlowPane.getChildren()) {
            if (!(node instanceof VBox box)) continue;
            if (node == selectionRect) continue;

            double bx = box.getLayoutX();
            double by = box.getLayoutY();
            double bw = box.getWidth();
            double bh = box.getHeight();

            // 矩形相交判断
            boolean intersects = rx < bx + bw && rx + rw > bx && ry < by + bh && ry + rh > by;
            if (intersects) {
                selectIconBox(box, null);
                FileItem item = (FileItem) box.getProperties().get("fileItem");
                if (item != null) {
                    selectedItems.add(item);
                    selectedItem = item;
                }
            } else {
                box.setUserData(null);
                box.setStyle("-fx-background-color: transparent; -fx-background-radius: 6; -fx-cursor: hand;");
            }
        }
    }

    private ContextMenu createContextMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem openItem = new MenuItem("打开");
        openItem.setOnAction(e -> {
            FileItem selected = getSelectedItem();
            if (selected != null) handleDoubleClick(selected);
        });

        MenuItem previewItem = new MenuItem("预览图片");
        previewItem.setOnAction(e -> handlePreview());

        MenuItem editMdItem = new MenuItem("编辑 Markdown");
        editMdItem.setOnAction(e -> {
            FileItem selected = getSelectedItem();
            if (selected != null) openMarkdownEditor(selected);
        });

        MenuItem downloadItem = new MenuItem("下载...");
        downloadItem.setOnAction(e -> {
            FileItem selected = getSelectedItem();
            if (selected != null && !selected.isDirectory()) handleDownload(selected);
        });

        MenuItem mkdirItem = new MenuItem("新建目录");
        mkdirItem.setOnAction(e -> handleCreateDirectory());

        MenuItem uploadItem = new MenuItem("上传文件...");
        uploadItem.setOnAction(e -> handleUploadFiles());

        MenuItem createFileItem = new MenuItem("创建文件");
        createFileItem.setOnAction(e -> handleCreateFile());

        MenuItem deleteItem = new MenuItem("删除");
        deleteItem.setOnAction(e -> handleDelete());

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

        menu.getItems().addAll(openItem, previewItem, editMdItem, downloadItem, new SeparatorMenuItem(),
                mkdirItem, uploadItem, createFileItem, deleteItem, new SeparatorMenuItem(), viewMenu, new SeparatorMenuItem(), refreshItem);

        menu.setOnShowing(e -> {
            FileItem selected = getSelectedItem();
            previewItem.setVisible(selected != null && !selected.isDirectory() && isImageFile(selected.getDisplayName()));
            editMdItem.setVisible(selected != null && !selected.isDirectory() && isMarkdownFile(selected.getDisplayName()));
            downloadItem.setVisible(selected != null && !selected.isDirectory());
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
     * 建立SSH连接并加载home目录
     */
    private void connectAndLoad() {
        new Thread(() -> {
            int tunnelLocalPort = -1;
            try {
                // 先建立/复用跳板隧道（引用方式，按 configId+host:port 缓存并引用计数）
                try {
                    tunnelLocalPort = SshTunnelManager.resolve(config);
                } catch (Exception te) {
                    Platform.runLater(() -> {
                        statusDot.setFill(Color.RED);
                        stateLabel.setText("连接失败");
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("连接失败");
                        alert.setHeaderText(null);
                        alert.setContentText("建立SSH跳板隧道失败: " + te.getMessage());
                        alert.showAndWait();
                    });
                    te.printStackTrace();
                    return;
                }
                String host = config.getHost();
                int port = config.getPort();
                if (tunnelLocalPort != -1) {
                    host = "localhost";
                    port = tunnelLocalPort;
                }
                JSch jsch = new JSch();
                List<String> keyPaths = config.isUseKey() ? config.getPrivateKeyPaths() : null;
                if (keyPaths != null && !keyPaths.isEmpty()) {
                    for (String keyPath : keyPaths) {
                        if (keyPath != null && !keyPath.isEmpty()) {
                            String pwd = config.getPassword();
                            if (pwd != null && !pwd.isEmpty()) {
                                jsch.addIdentity(keyPath, pwd);
                            } else {
                                jsch.addIdentity(keyPath);
                            }
                        }
                    }
                }
                jschSession = jsch.getSession(config.getUsername(), host, port);
                if (keyPaths == null || keyPaths.isEmpty()) {
                    jschSession.setPassword(config.getPassword());
                }
                jschSession.setConfig("StrictHostKeyChecking", "no");
                jschSession.connect(30000);

                sftpClient.connect(jschSession);

                String home = sftpClient.pwd();
                Platform.runLater(() -> {
                    statusDot.setFill(Color.GREEN);
                    stateLabel.setText("已连接");
                    navigateTo(home);
                });
            } catch (Exception e) {
                if (tunnelLocalPort != -1) {
                    SshTunnelManager.release(config);
                }
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
        }, "SFTP-Connect").start();
    }

    /**
     * 导航到指定路径
     */
    private void navigateTo(String path) {
        new Thread(() -> {
            try {
                // 通道未连接时尝试重连
                if (!sftpClient.isConnected()) {
                    sftpClient.reconnect();
                }
                sftpClient.cd(path);
                String realPath = sftpClient.pwd();
                List<SFTPClient.FileEntry> entries = sftpClient.listFiles(realPath);
                Platform.runLater(() -> {
                    currentPath = realPath;
                    updatePathLabel();
                    fileData.clear();
                    for (SFTPClient.FileEntry entry : entries) {
                        FileItem item = new FileItem();
                        item.setName(entry.getName());
                        item.setPath(entry.getPath());
                        item.setDirectory(entry.isDirectory());
                        item.setSize(entry.getSize());
                        item.setLastModified(entry.getModifyTime());
                        fileData.add(item);
                    }
                    upBtn.setDisable("/".equals(currentPath));
                    selectedItem = null;

                    if (currentViewMode == ViewMode.ICON) {
                        rebuildIconView();
                        loadThumbnailsForIconView();
                    } else if (currentViewMode == ViewMode.COLUMN) {
                        rebuildColumnView();
                    }
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
        }, "SFTP-Navigate").start();
    }

    /**
     * 处理双击：目录进入，图片预览，markdown 编辑
     */
    private void handleDoubleClick(FileItem item) {
        if (item == null) return;

        if (item.isDirectory()) {
            navigateTo(item.getPath());
        } else if (isImageFile(item.getDisplayName())) {
            handlePreview(item);
        } else if (isMarkdownFile(item.getDisplayName())) {
            openMarkdownEditor(item);
        }
    }

    /**
     * 打开 Markdown 编辑器 Tab
     */
    private void openMarkdownEditor(FileItem item) {
        String filePath = item.getPath();
        String fileName = item.getDisplayName();

        // 复用已打开的 Tab
        for (Tab tab : editorTabPane.getTabs()) {
            if (tab.getUserData() instanceof String tabKey && tabKey.equals(filePath)) {
                editorTabPane.getSelectionModel().select(tab);
                return;
            }
        }

        // 占位 Tab
        Tab editorTab = new Tab(fileName);
        editorTab.setUserData(filePath);
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setPrefSize(40, 40);
        StackPane loading = new StackPane(indicator);
        loading.setStyle("-fx-background-color: white;");
        editorTab.setContent(loading);
        editorTabPane.getTabs().add(editorTab);
        editorTabPane.getSelectionModel().select(editorTab);

        // 异步下载文件内容
        new Thread(() -> {
            try {
                File tempFile = File.createTempFile("tomato-sftp-md-", ".md");
                sftpClient.download(filePath, tempFile.getAbsolutePath());
                String content = new String(Files.readAllBytes(tempFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                if (!tempFile.delete()) tempFile.deleteOnExit();

                Platform.runLater(() -> {
                    MarkdownEditorPane.Storage storage = (c, onSuccess, onError) -> new Thread(() -> {
                        try {
                            File tmp = File.createTempFile("tomato-sftp-save-", ".md");
                            Files.write(tmp.toPath(), c.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            sftpClient.upload(tmp.getAbsolutePath(), filePath);
                            if (!tmp.delete()) tmp.deleteOnExit();
                            Platform.runLater(onSuccess);
                        } catch (Exception e) {
                            Platform.runLater(() -> onError.accept(e.getMessage()));
                        }
                    }, "SFTP-MDSave").start();

                    MarkdownEditorPane editor = new MarkdownEditorPane(fileName, content, storage);
                    editorTab.setContent(editor);
                    editor.setOnTitleChange(title -> editorTab.setText(title));
                    editorTab.setText(editor.getDisplayTitle());
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
                                editorTabPane.getTabs().remove(editorTab);
                            });
                        }
                    });
                });
            } catch (Exception e) {
                Platform.runLater(() -> editorTab.setContent(new Label("加载失败: " + e.getMessage())));
            }
        }, "SFTP-MDLoad").start();
    }

    /**
     * 右键创建文件：弹窗输入文件名，在当前目录下新建空 markdown 并打开编辑器
     */
    private void handleCreateFile() {
        TextInputDialog dialog = new TextInputDialog("新文件.md");
        dialog.setTitle("创建文件");
        dialog.setHeaderText(null);
        dialog.setContentText("文件名：");
        dialog.showAndWait().ifPresent(name -> {
            String fileName = name.trim();
            if (fileName.isEmpty()) return;
            String filePath = joinPath(currentPath, fileName);
            new Thread(() -> {
                try {
                    // 上传空内容创建文件
                    File tmp = File.createTempFile("tomato-sftp-new-", ".tmp");
                    Files.write(tmp.toPath(), new byte[0]);
                    sftpClient.upload(tmp.getAbsolutePath(), filePath);
                    if (!tmp.delete()) tmp.deleteOnExit();
                    Platform.runLater(() -> {
                        refresh();
                        FileItem newItem = new FileItem();
                        newItem.setName(fileName);
                        newItem.setPath(filePath);
                        newItem.setDirectory(false);
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
            }, "SFTP-CreateFile").start();
        });
    }

    /**
     * 右键新建目录
     */
    private void handleCreateDirectory() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("新建目录");
        dialog.setHeaderText(null);
        dialog.setContentText("目录名称：");
        dialog.showAndWait().ifPresent(name -> {
            String dirName = name.trim();
            if (dirName.isEmpty()) return;
            if (dirName.contains("/")) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("提示");
                alert.setHeaderText(null);
                alert.setContentText("目录名称不能包含 / 字符");
                alert.showAndWait();
                return;
            }
            String dirPath = joinPath(currentPath, dirName);
            new Thread(() -> {
                try {
                    sftpClient.mkdir(dirPath);
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
            }, "SFTP-Mkdir").start();
        });
    }

    /**
     * 右键上传文件
     */
    private void handleUploadFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择要上传的文件");
        List<File> files = chooser.showOpenMultipleDialog(getScene().getWindow());
        if (files == null || files.isEmpty()) return;
        uploadLocalFiles(files);
    }

    /**
     * 上传本地文件列表到当前目录
     */
    private void uploadLocalFiles(List<File> files) {
        if (files == null || files.isEmpty()) return;
        stateLabel.setText("上传中... (0/" + files.size() + ")");
        new Thread(() -> {
            int success = 0;
            int failed = 0;
            String lastError = null;
            for (File file : files) {
                String remotePath = joinPath(currentPath, file.getName());
                try {
                    sftpClient.upload(file.getAbsolutePath(), remotePath);
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
        }, "SFTP-Upload").start();
    }

    /**
     * 右键下载文件
     */
    private void handleDownload(FileItem item) {
        if (item == null || item.isDirectory()) return;
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("保存文件");
        fileChooser.setInitialFileName(item.getDisplayName());
        File saveFile = fileChooser.showSaveDialog(getScene().getWindow());
        if (saveFile == null) return;

        stateLabel.setText("下载中: " + item.getDisplayName());
        new Thread(() -> {
            try {
                sftpClient.download(item.getPath(), saveFile.getAbsolutePath());
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
        }, "SFTP-Download").start();
    }

    /**
     * 下载文件到临时目录（用于拖拽下载）
     */
    private File downloadToTemp(FileItem item) {
        if (item == null || item.isDirectory()) return null;
        try {
            File tempDir = new File(System.getProperty("java.io.tmpdir"), "tomato-sftp");
            if (!tempDir.exists()) tempDir.mkdirs();
            File tempFile = new File(tempDir, item.getDisplayName());
            sftpClient.download(item.getPath(), tempFile.getAbsolutePath());
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
     * 预览图片：从SFTP下载并显示
     */
    private void handlePreview(FileItem item) {
        // 收集当前目录中所有图片文件
        List<FileItem> imageItems = new ArrayList<>();
        int currentIndex = -1;
        for (int i = 0; i < fileData.size(); i++) {
            FileItem fi = fileData.get(i);
            if (!fi.isDirectory() && isImageFile(fi.getDisplayName())) {
                imageItems.add(fi);
                if (fi == item || fi.getPath().equals(item.getPath())) {
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

        ProgressIndicator loadingIndicator = new ProgressIndicator();
        loadingIndicator.setPrefSize(60, 60);
        StackPane loadingPane = new StackPane(loadingIndicator);
        loadingPane.setPrefSize(780, 520);
        loadingPane.setStyle("-fx-background-color: #2b2b2b;");
        previewStage.setScene(new Scene(loadingPane));

        final int[] imageIndex = {currentIndex};

        Runnable loadImage = new Runnable() {
            @Override
            public void run() {
                int idx = imageIndex[0];
                if (idx < 0 || idx >= imageItems.size()) return;
                FileItem currentItem = imageItems.get(idx);

                ProgressIndicator indicator = new ProgressIndicator();
                indicator.setPrefSize(60, 60);
                StackPane pane = new StackPane(indicator);
                pane.setPrefSize(780, 520);
                pane.setStyle("-fx-background-color: #2b2b2b;");
                previewStage.setScene(new Scene(pane));

                new Thread(() -> {
                    try {
                        File tempFile = File.createTempFile("tomato-sftp-preview-", ".img");
                        sftpClient.download(currentItem.getPath(), tempFile.getAbsolutePath());
                        byte[] imageBytes = Files.readAllBytes(tempFile.toPath());
                        if (!tempFile.delete()) tempFile.deleteOnExit();

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
                }, "SFTP-LoadImage").start();
            }
        };

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

        StackPane imageContainer = new StackPane(imageView);
        imageContainer.setStyle("-fx-background-color: #2b2b2b;");

        double contentWidth = stage.getWidth() > 0 ? stage.getWidth() : 800;
        double contentHeight = stage.getHeight() > 0 ? stage.getHeight() - 40 : 560;

        double fitWidth = Math.min(imgWidth, contentWidth - 20);
        double fitHeight = Math.min(imgHeight, contentHeight - 60);
        double scale = Math.min(fitWidth / imgWidth, fitHeight / imgHeight);
        if (scale < 1) {
            imageView.setFitWidth(imgWidth * scale);
            imageView.setFitHeight(imgHeight * scale);
        }

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

        stage.setTitle(String.format("%s  |  %dx%d  |  %s  (%d/%d)",
                item.getDisplayName(), (int) imgWidth, (int) imgHeight, item.getFormattedSize(),
                imageIndex[0] + 1, imageItems.size()));

        HBox toolbar = new HBox(8);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6, 10, 6, 10));
        toolbar.setStyle("-fx-background-color: #3c3c3c;");

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

        Button originalBtn = new Button("1:1");
        originalBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8;");
        originalBtn.setOnAction(e -> {
            imageView.setFitWidth(imgWidth);
            imageView.setFitHeight(imgHeight);
            imageView.setTranslateX(0);
            imageView.setTranslateY(0);
        });
        toolbar.getChildren().add(originalBtn);

        Separator sep2 = new Separator();
        sep2.setOrientation(javafx.geometry.Orientation.VERTICAL);
        toolbar.getChildren().add(sep2);

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
                    sftpClient.download(item.getPath(), saveFile.getAbsolutePath());
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
            }, "SFTP-Download").start();
        });
        toolbar.getChildren().add(downloadBtn);

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
                        sftpClient.rm(item.getPath());
                        Platform.runLater(() -> {
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
                }, "SFTP-Delete").start();
            });
        });
        toolbar.getChildren().add(deleteBtn);

        VBox content = new VBox();
        content.getChildren().addAll(toolbar, imageContainer);
        VBox.setVgrow(imageContainer, Priority.ALWAYS);

        stage.setScene(new Scene(content));

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
        params.setFill(Color.TRANSPARENT);
        Image thumbnail = thumbView.snapshot(params, null);

        for (var node : iconFlowPane.getChildren()) {
            if (node instanceof VBox box) {
                if (box.getChildren().size() >= 2
                        && box.getChildren().get(1) instanceof Label label
                        && item.getDisplayName().equals(label.getText())) {

                    if (box.getChildren().get(0) instanceof ImageView iconView) {
                        iconView.setImage(thumbnail);
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
        // 收集所有需要加载缩略图的图片文件
        List<FileItem> imageItems = new ArrayList<>();
        for (FileItem item : fileData) {
            if (!item.isDirectory() && isImageFile(item.getDisplayName())) {
                imageItems.add(item);
            }
        }
        if (imageItems.isEmpty()) return;

        // 单线程顺序下载，避免并发访问SFTP通道
        new Thread(() -> {
            for (FileItem item : imageItems) {
                try {
                    File tempFile = File.createTempFile("tomato-sftp-thumb-", ".img");
                    sftpClient.download(item.getPath(), tempFile.getAbsolutePath());
                    byte[] imageBytes = Files.readAllBytes(tempFile.toPath());
                    if (!tempFile.delete()) tempFile.deleteOnExit();

                    Platform.runLater(() -> {
                        try {
                            Image image = new Image(new java.io.ByteArrayInputStream(imageBytes));
                            if (image.isError() || image.getWidth() <= 0 || image.getHeight() <= 0) return;
                            updateIconBoxWithThumbnail(item, image);
                        } catch (Exception ignored) {}
                    });
                } catch (Exception ignored) {}
            }
        }, "SFTP-Thumbnails").start();
    }

    /**
     * 返回上级目录
     */
    private void navigateUp() {
        if (currentPath == null || "/".equals(currentPath)) return;
        int lastSlash = currentPath.lastIndexOf('/');
        String parent = lastSlash <= 0 ? "/" : currentPath.substring(0, lastSlash);
        if (parent.isEmpty()) parent = "/";
        navigateTo(parent);
    }

    /**
     * 刷新当前视图
     */
    public void refresh() {
        navigateTo(currentPath);
    }

    /**
     * 断开连接，释放资源
     */
    public void disconnect() {
        new Thread(() -> {
            try {
                sftpClient.disconnect();
            } catch (Exception ignored) {}
            if (jschSession != null && jschSession.isConnected()) {
                jschSession.disconnect();
            }
            jschSession = null;
            // 释放跳板隧道引用（引用计数归零时才真正断开隧道，支持多会话共享）
            SshTunnelManager.release(config);
        }, "SFTP-Disconnect").start();
    }

    private void updatePathLabel() {
        currentPathField.setText(currentPath != null ? currentPath : "/");
    }

    /**
     * 跳转到指定路径（由路径输入框回车触发）
     */
    private void navigateToPath(String input) {
        if (input == null) return;
        String path = input.trim();
        if (path.isEmpty()) {
            updatePathLabel();
            return;
        }

        // 相对路径：基于当前路径解析
        if (!path.startsWith("/")) {
            String base = currentPath.endsWith("/") ? currentPath : currentPath + "/";
            path = base + path;
        }

        // 规范化：去除多余的末尾 /
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }

        if (path.isEmpty()) path = "/";
        navigateTo(path);
    }

    private void handleDelete() {
        // 优先使用框选的多选结果，否则使用单选
        List<FileItem> toDelete = new ArrayList<>();
        if (!selectedItems.isEmpty()) {
            toDelete.addAll(selectedItems);
        } else {
            FileItem selected = getSelectedItem();
            if (selected != null) toDelete.add(selected);
        }
        if (toDelete.isEmpty()) return;

        String msg = toDelete.size() == 1
                ? "确定要删除 \"" + toDelete.get(0).getName() + "\" 吗？"
                : "确定要删除选中的 " + toDelete.size() + " 个文件/目录吗？";

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("删除确认");
        confirm.setHeaderText(null);
        confirm.setContentText(msg);

        confirm.showAndWait().ifPresent(response -> {
            if (response != ButtonType.OK) return;

            new Thread(() -> {
                int success = 0;
                String lastError = null;
                for (FileItem item : toDelete) {
                    try {
                        if (item.isDirectory()) {
                            sftpClient.rmdir(item.getPath());
                        } else {
                            sftpClient.rm(item.getPath());
                        }
                        success++;
                    } catch (Exception e) {
                        lastError = e.getMessage();
                    }
                }
                final int okCount = success;
                final int failCount = toDelete.size() - success;
                final String err = lastError;
                Platform.runLater(() -> {
                    if (failCount > 0) {
                        Alert alert = new Alert(Alert.AlertType.WARNING);
                        alert.setTitle("部分删除失败");
                        alert.setHeaderText("成功 " + okCount + " 个, 失败 " + failCount + " 个");
                        alert.setContentText(err != null ? err : "");
                        alert.showAndWait();
                    }
                    refresh();
                });
            }, "SFTP-Delete").start();
        });
    }

    /**
     * 拼接路径：确保中间有且仅有一个 /
     */
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
                return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(lastModified));
            } catch (Exception e) {
                return "";
            }
        }
    }
}
