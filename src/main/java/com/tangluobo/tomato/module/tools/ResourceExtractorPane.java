package com.tangluobo.tomato.module.tools;

import com.tangluobo.tomato.module.tools.extractor.core.Extractor;
import com.tangluobo.tomato.module.tools.extractor.core.FileScanner;
import com.tangluobo.tomato.module.tools.extractor.core.ScanResult;
import com.tangluobo.tomato.module.tools.extractor.format.FormatCategory;
import com.tangluobo.tomato.module.tools.extractor.format.FormatRegistry;
import com.tangluobo.tomato.module.tools.extractor.format.FileFormatInfo;
import com.tangluobo.tomato.module.tools.extractor.pe.PEFile;
import com.tangluobo.tomato.module.tools.extractor.pe.PEResourceExtractor;
import com.tangluobo.tomato.module.tools.extractor.utils.FileUtils;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;

public class ResourceExtractorPane extends VBox {

    private TextField sourcePathField;
    private TextField outputPathField;
    private Label statusLabel;
    private Button runButton;
    private ComboBox<String> modeCombo;
    private CheckBox recursiveCheck;
    private CheckBox overwriteCheck;
    private CheckBox unpackCheck;
    private CheckBox peScanCheck;
    private CheckBox peAlsoCheck;
    private CheckBox dedupCheck;
    private CheckBox verboseCheck;
    private Spinner<Double> minSizeSpinner;
    private Spinner<Double> maxFileSizeSpinner;
    private ListView<String> resultListView;
    private TextArea logArea;
    private CheckBox showLogCheck;
    private List<ScanResult> currentResults = new ArrayList<>();
    private boolean isRunning = false;

    private CheckBox gfxCheck, musicCheck, videoCheck, documentsCheck, fontsCheck, archiveCheck, otherCheck;
    private Map<String, CheckBox> formatCheckBoxes = new LinkedHashMap<>();
    private VBox formatDetailBox;
    private Map<FormatCategory, List<CheckBox>> categoryFormatCheckboxes = new LinkedHashMap<>();

    public ResourceExtractorPane() {
        initializeUI();
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #ffffff; -fx-padding: 0; -fx-background-insets: 0; -fx-border-insets: 0;");
        setFillWidth(true);
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);
        // 加载统一样式表，使 ScrollPane viewport 等组件清除默认 background-insets
        getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());

        HBox titleBar = new HBox(10);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(14, 20, 14, 20));
        titleBar.setStyle("-fx-background-color: #f7f8fa; -fx-border-color: #e8e8e8; -fx-border-width: 0 0 1 0;");
        SVGPath titleIcon = new SVGPath();
        titleIcon.setContent("M19 3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-8 12H9.5v-2h-2v2H6V9h1.5v2.5h2V9H11v6zm7-1c0 .55-.45 1-1 1h-.75v1.5h-1.5V15H14c-.55 0-1-.45-1-1v-4c0-.55.45-1 1-1h3c.55 0 1 .45 1 1v4zm-3.5-.5h2v-3h-2v3zM19 9h-1.5v1.5H19V9z");
        titleIcon.setFill(Color.web("#1976D2"));
        titleIcon.setScaleX(0.75);
        titleIcon.setScaleY(0.75);
        Label titleLabel = new Label("资源图标提取");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333;");
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        Label subtitleLabel = new Label("从二进制文件中提取嵌入资源");
        subtitleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #999;");
        titleBar.getChildren().addAll(titleIcon, titleLabel, titleSpacer, subtitleLabel);

        ScrollPane mainScroll = new ScrollPane();
        mainScroll.setFitToWidth(true);
        mainScroll.setFitToHeight(true);
        mainScroll.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-border-width: 0; -fx-padding: 0; -fx-background-insets: 0; -fx-border-insets: 0;");
        // 应用 session-scroll-pane 样式类：清除 .viewport 默认 background-insets，消除内容左侧/顶部的1-2px留白
        mainScroll.getStyleClass().add("session-scroll-pane");
        mainScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        mainScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        VBox contentBox = new VBox(15);
        contentBox.setPadding(new Insets(15, 20, 20, 20));
        contentBox.setFillWidth(true);
        contentBox.setMaxWidth(Double.MAX_VALUE);

        contentBox.getChildren().addAll(
                createModeSection(),
                createSourceSection(),
                createOutputSection(),
                createFilterSection(),
                createAdvancedSection(),
                createActionSection()
        );

        mainScroll.setContent(contentBox);

        VBox resultBox = new VBox(0);
        resultBox.setStyle("-fx-background-color: #fafafa; -fx-border-color: #e8e8e8; -fx-border-width: 1 0 0 0;");

        HBox resultHeader = new HBox(10);
        resultHeader.setAlignment(Pos.CENTER_LEFT);
        resultHeader.setPadding(new Insets(8, 15, 8, 15));
        resultHeader.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #e8e8e8; -fx-border-width: 0 0 1 0;");
        Label resultTitle = new Label("扫描结果");
        resultTitle.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #555;");
        Region resultSpacer = new Region();
        HBox.setHgrow(resultSpacer, Priority.ALWAYS);
        showLogCheck = new CheckBox("显示详细日志");
        showLogCheck.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        showLogCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            logArea.setVisible(newVal);
            logArea.setManaged(newVal);
        });
        resultHeader.getChildren().addAll(resultTitle, resultSpacer, showLogCheck);

        resultListView = new ListView<>();
        resultListView.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e8e8e8;");
        resultListView.setEditable(false);
        resultListView.setPlaceholder(new Label("等待运行..."));
        resultListView.getStyleClass().add("result-list");
        VBox.setVgrow(resultListView, Priority.ALWAYS);

        logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setStyle("-fx-font-family: Consolas, monospace; -fx-font-size: 12px; -fx-control-inner-background: #1e1e1e; -fx-text-fill: #d4d4d4;");
        logArea.setPrefRowCount(8);
        logArea.setVisible(false);
        logArea.setManaged(false);
        VBox.setVgrow(logArea, Priority.ALWAYS);

        resultBox.getChildren().addAll(resultHeader, resultListView, logArea);
        VBox.setVgrow(resultBox, Priority.ALWAYS);

        getChildren().addAll(titleBar, mainScroll, resultBox);
        VBox.setVgrow(mainScroll, Priority.ALWAYS);
    }

    private VBox createModeSection() {
        VBox box = new VBox(8);
        box.setPadding(new Insets(0));
        box.setFillWidth(true);
        box.setMaxWidth(Double.MAX_VALUE);
        Label label = new Label("运行模式");
        label.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");
        box.getChildren().add(label);

        modeCombo = new ComboBox<>(FXCollections.observableArrayList(
                "扫描 - 仅列出发现的资源",
                "提取 - 扫描并保存到输出目录",
                "PE提取 - 仅提取PE资源(EXE/DLL/SCR)"
        ));
        modeCombo.setStyle("-fx-font-size: 13px; -fx-padding: 6 10;");
        modeCombo.setMaxWidth(Double.MAX_VALUE);
        modeCombo.setValue("扫描 - 仅列出发现的资源");
        HBox.setHgrow(modeCombo, Priority.ALWAYS);
        box.getChildren().add(modeCombo);
        return box;
    }

    private VBox createSourceSection() {
        VBox box = new VBox(8);
        box.setFillWidth(true);
        box.setMaxWidth(Double.MAX_VALUE);
        Label label = new Label("源文件/目录");
        label.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");
        box.getChildren().add(label);

        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);

        sourcePathField = new TextField();
        sourcePathField.setPromptText("选择要扫描的文件或目录");
        sourcePathField.setStyle("-fx-font-size: 13px; -fx-padding: 6 10; -fx-border-color: #d0d0d0; -fx-border-radius: 4; -fx-background-radius: 4;");
        HBox.setHgrow(sourcePathField, Priority.ALWAYS);

        Button fileBtn = new Button("选择文件");
        fileBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6 14; -fx-background-radius: 4; -fx-cursor: hand;");
        fileBtn.setOnAction(e -> chooseSourceFile());

        Button dirBtn = new Button("选择目录");
        dirBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6 14; -fx-background-radius: 4; -fx-cursor: hand;");
        dirBtn.setOnAction(e -> chooseSourceDir());

        row.getChildren().addAll(sourcePathField, fileBtn, dirBtn);
        box.getChildren().add(row);
        return box;
    }

    private VBox createOutputSection() {
        VBox box = new VBox(8);
        box.setFillWidth(true);
        box.setMaxWidth(Double.MAX_VALUE);
        Label label = new Label("输出目录");
        label.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");
        box.getChildren().add(label);

        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);

        outputPathField = new TextField();
        outputPathField.setPromptText("选择提取输出目录（可选）");
        outputPathField.setStyle("-fx-font-size: 13px; -fx-padding: 6 10; -fx-border-color: #d0d0d0; -fx-border-radius: 4; -fx-background-radius: 4;");
        HBox.setHgrow(outputPathField, Priority.ALWAYS);

        Button browseBtn = new Button("浏览");
        browseBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6 14; -fx-background-radius: 4; -fx-cursor: hand;");
        browseBtn.setOnAction(e -> chooseOutputDir());

        row.getChildren().addAll(outputPathField, browseBtn);
        box.getChildren().add(row);
        return box;
    }

    private VBox createFilterSection() {
        VBox box = new VBox(8);
        box.setFillWidth(true);
        box.setMaxWidth(Double.MAX_VALUE);
        Label label = new Label("格式过滤");
        label.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");
        box.getChildren().add(label);

        Label hint = new Label("勾选要包含的分类与具体格式（不勾选则扫描所有格式）");
        hint.setStyle("-fx-font-size: 12px; -fx-text-fill: #999;");
        box.getChildren().add(hint);

        GridPane grid = new GridPane();
        grid.setHgap(15);
        grid.setVgap(5);
        grid.setPadding(new Insets(5, 0, 5, 0));

        gfxCheck = createCategoryCheck("图像 (GFX)");
        musicCheck = createCategoryCheck("音频 (MUSIC)");
        videoCheck = createCategoryCheck("视频 (VIDEO)");
        documentsCheck = createCategoryCheck("文档 (DOCUMENTS)");
        fontsCheck = createCategoryCheck("字体 (FONTS)");
        archiveCheck = createCategoryCheck("压缩包 (ARCHIVE)");
        otherCheck = createCategoryCheck("其他 (OTHER)");

        grid.add(gfxCheck, 0, 0);
        grid.add(musicCheck, 1, 0);
        grid.add(videoCheck, 0, 1);
        grid.add(documentsCheck, 1, 1);
        grid.add(fontsCheck, 0, 2);
        grid.add(archiveCheck, 1, 2);
        grid.add(otherCheck, 0, 3);

        box.getChildren().add(grid);

        // 创建具体格式选择区域
        formatDetailBox = new VBox(10);
        formatDetailBox.setPadding(new Insets(5, 0, 5, 0));
        formatDetailBox.setStyle("-fx-background-color: #f8f9fa; -fx-border-color: #e0e0e0; -fx-border-radius: 4; -fx-background-radius: 4;");
        formatDetailBox.setMaxWidth(Double.MAX_VALUE);

        // 添加各分类的具体格式选项
        addFormatOptions(FormatCategory.GFX, new String[]{"png", "jpg", "bmp", "gif", "ico", "cur", "dds", "tif", "emf", "wmf"});
        addFormatOptions(FormatCategory.MUSIC, new String[]{"wav", "mp3", "ogg", "mid", "xm", "mod", "s3m"});
        addFormatOptions(FormatCategory.VIDEO, new String[]{"avi", "mov", "mpg", "mp4", "3gp", "bik", "smk", "swf", "asf"});
        addFormatOptions(FormatCategory.DOCUMENTS, new String[]{"pdf", "doc", "rtf"});
        addFormatOptions(FormatCategory.FONTS, new String[]{"ttf", "otf", "ttc", "woff"});
        addFormatOptions(FormatCategory.ARCHIVE, new String[]{"zip", "rar", "7z", "gz", "bz2", "cab", "jar", "chm"});
        addFormatOptions(FormatCategory.OTHER, new String[]{"3ds", "upk", "iff", "dat", "mpq"});

        box.getChildren().add(formatDetailBox);

        // 分类勾选变化时更新格式选项
        gfxCheck.selectedProperty().addListener((obs, oldVal, newVal) -> updateFormatOptions());
        musicCheck.selectedProperty().addListener((obs, oldVal, newVal) -> updateFormatOptions());
        videoCheck.selectedProperty().addListener((obs, oldVal, newVal) -> updateFormatOptions());
        documentsCheck.selectedProperty().addListener((obs, oldVal, newVal) -> updateFormatOptions());
        fontsCheck.selectedProperty().addListener((obs, oldVal, newVal) -> updateFormatOptions());
        archiveCheck.selectedProperty().addListener((obs, oldVal, newVal) -> updateFormatOptions());
        otherCheck.selectedProperty().addListener((obs, oldVal, newVal) -> updateFormatOptions());

        updateFormatOptions();
        return box;
    }

    private void addFormatOptions(FormatCategory category, String[] extensions) {
        List<CheckBox> checkboxes = new ArrayList<>();
        for (String ext : extensions) {
            CheckBox cb = new CheckBox(ext.toUpperCase());
            cb.setStyle("-fx-font-size: 11px; -fx-text-fill: #555;");
            cb.setSelected(true);
            checkboxes.add(cb);
            categoryFormatCheckboxes.putIfAbsent(category, new ArrayList<>());
            categoryFormatCheckboxes.get(category).add(cb);
        }
    }

    private void updateFormatOptions() {
        formatDetailBox.getChildren().clear();
        formatDetailBox.setManaged(true);
        formatDetailBox.setVisible(true);

        addCategoryFormats(FormatCategory.GFX, gfxCheck.isSelected());
        addCategoryFormats(FormatCategory.MUSIC, musicCheck.isSelected());
        addCategoryFormats(FormatCategory.VIDEO, videoCheck.isSelected());
        addCategoryFormats(FormatCategory.DOCUMENTS, documentsCheck.isSelected());
        addCategoryFormats(FormatCategory.FONTS, fontsCheck.isSelected());
        addCategoryFormats(FormatCategory.ARCHIVE, archiveCheck.isSelected());
        addCategoryFormats(FormatCategory.OTHER, otherCheck.isSelected());

        // 如果所有分类都没勾选，隐藏详细选项
        if (!gfxCheck.isSelected() && !musicCheck.isSelected() && !videoCheck.isSelected()
                && !documentsCheck.isSelected() && !fontsCheck.isSelected()
                && !archiveCheck.isSelected() && !otherCheck.isSelected()) {
            formatDetailBox.getChildren().add(new Label("（未选择任何分类，将扫描所有格式）"));
        }
    }

    private void addCategoryFormats(FormatCategory category, boolean show) {
        List<CheckBox> checkboxes = categoryFormatCheckboxes.get(category);
        if (checkboxes == null || checkboxes.isEmpty()) return;

        if (!show) {
            // 取消勾选时，将格式复选框设为禁用
            for (CheckBox cb : checkboxes) {
                cb.setDisable(true);
            }
            return;
        }

        // 重新启用并添加到面板（保留用户之前的选择）
        for (CheckBox cb : checkboxes) {
            cb.setDisable(false);
        }

        Label catLabel = new Label(category.getDisplayName() + ":");
        catLabel.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #666;");

        FlowPane flowPane = new FlowPane(5, 3);
        flowPane.setPadding(new Insets(2, 5, 2, 5));
        flowPane.getChildren().addAll(checkboxes);

        VBox catBox = new VBox(3);
        catBox.getChildren().addAll(catLabel, flowPane);
        formatDetailBox.getChildren().add(catBox);
    }

    private CheckBox createCategoryCheck(String text) {
        CheckBox cb = new CheckBox(text);
        cb.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");
        return cb;
    }

    private VBox createAdvancedSection() {
        VBox box = new VBox(8);
        box.setFillWidth(true);
        box.setMaxWidth(Double.MAX_VALUE);
        Label label = new Label("高级选项");
        label.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");
        box.getChildren().add(label);

        GridPane grid = new GridPane();
        grid.setHgap(30);
        grid.setVgap(8);
        grid.setPadding(new Insets(5, 0, 5, 0));

        recursiveCheck = new CheckBox("递归扫描子目录");
        recursiveCheck.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");

        overwriteCheck = new CheckBox("覆盖已存在的文件");
        overwriteCheck.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");

        unpackCheck = new CheckBox("解包ZIP并扫描内容");
        unpackCheck.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");

        peScanCheck = new CheckBox("扫描PE资源段 (Qt/Electron必备)");
        peScanCheck.setSelected(true);
        peScanCheck.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");

        peAlsoCheck = new CheckBox("同时提取PE资源 (图标/位图等)");
        peAlsoCheck.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");

        dedupCheck = new CheckBox("按源+偏移去重");
        dedupCheck.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");

        verboseCheck = new CheckBox("详细日志输出");
        verboseCheck.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");

        grid.add(recursiveCheck, 0, 0);
        grid.add(overwriteCheck, 1, 0);
        grid.add(unpackCheck, 0, 1);
        grid.add(peScanCheck, 1, 1);
        grid.add(peAlsoCheck, 0, 2);
        grid.add(dedupCheck, 1, 2);
        grid.add(verboseCheck, 0, 3);

        box.getChildren().add(grid);

        HBox sizeRow = new HBox(15);
        sizeRow.setAlignment(Pos.CENTER_LEFT);
        sizeRow.setPadding(new Insets(5, 0, 0, 0));

        Label minSizeLabel = new Label("最小资源大小:");
        minSizeLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");

        minSizeSpinner = new Spinner<>(16.0, 1024.0, 16.0, 16.0);
        minSizeSpinner.setEditable(true);
        minSizeSpinner.setStyle("-fx-font-size: 12px;");
        minSizeSpinner.setPrefWidth(100);

        Label maxFileSizeLabel = new Label("最大文件大小(MB):");
        maxFileSizeLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");

        maxFileSizeSpinner = new Spinner<>(64.0, 2048.0, 512.0, 64.0);
        maxFileSizeSpinner.setEditable(true);
        maxFileSizeSpinner.setStyle("-fx-font-size: 12px;");
        maxFileSizeSpinner.setPrefWidth(100);

        sizeRow.getChildren().addAll(minSizeLabel, minSizeSpinner, maxFileSizeLabel, maxFileSizeSpinner);
        box.getChildren().add(sizeRow);

        return box;
    }

    private VBox createActionSection() {
        VBox box = new VBox(10);
        box.setFillWidth(true);
        box.setMaxWidth(Double.MAX_VALUE);

        HBox row = new HBox(15);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);

        runButton = new Button("开始扫描");
        runButton.setStyle("-fx-background-color: #1976D2; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 30; -fx-background-radius: 4; -fx-cursor: hand;");
        runButton.setOnAction(e -> startExtraction());

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        row.getChildren().addAll(runButton, statusLabel, spacer);
        box.getChildren().add(row);

        return box;
    }

    private void chooseSourceFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择源文件");
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file != null) {
            sourcePathField.setText(file.getAbsolutePath());
        }
    }

    private void chooseSourceDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择源目录");
        File dir = chooser.showDialog(getScene().getWindow());
        if (dir != null) {
            sourcePathField.setText(dir.getAbsolutePath());
        }
    }

    private void chooseOutputDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择输出目录");
        File dir = chooser.showDialog(getScene().getWindow());
        if (dir != null) {
            outputPathField.setText(dir.getAbsolutePath());
        }
    }

    private void startExtraction() {
        if (isRunning) return;

        String sourcePath = sourcePathField.getText().trim();
        if (sourcePath.isEmpty()) {
            setStatus("请选择源文件或目录", "#e53935");
            return;
        }

        Path source = Paths.get(sourcePath);
        if (!Files.exists(source)) {
            setStatus("源路径不存在: " + sourcePath, "#e53935");
            return;
        }

        String mode = modeCombo.getValue();
        boolean isExtract = mode.startsWith("提取");
        boolean isPeOnly = mode.startsWith("PE提取");

        if (isExtract || isPeOnly) {
            String outPath = outputPathField.getText().trim();
            if (outPath.isEmpty()) {
                setStatus("请选择输出目录", "#e53935");
                return;
            }
        }

        isRunning = true;
        runButton.setDisable(true);
        runButton.setText("运行中...");
        setStatus("正在运行...", "#1976D2");
        logArea.clear();
        resultListView.getItems().clear();

        new Thread(() -> {
            try {
                runExtraction(source, mode);
            } catch (Exception e) {
                appendLog("错误: " + e.getMessage());
                Platform.runLater(() -> setStatus("运行失败: " + e.getMessage(), "#e53935"));
            } finally {
                isRunning = false;
                Platform.runLater(() -> {
                    runButton.setDisable(false);
                    runButton.setText("开始扫描");
                });
            }
        }, "extractor-thread").start();
    }

    private void runExtraction(Path source, String mode) throws Exception {
        boolean isExtract = mode.startsWith("提取");
        boolean isPeOnly = mode.startsWith("PE提取");

        FormatRegistry registry = new FormatRegistry();

        // 收集选中的分类
        List<FormatCategory> selectedCategories = new ArrayList<>();
        if (gfxCheck.isSelected()) selectedCategories.add(FormatCategory.GFX);
        if (musicCheck.isSelected()) selectedCategories.add(FormatCategory.MUSIC);
        if (videoCheck.isSelected()) selectedCategories.add(FormatCategory.VIDEO);
        if (documentsCheck.isSelected()) selectedCategories.add(FormatCategory.DOCUMENTS);
        if (fontsCheck.isSelected()) selectedCategories.add(FormatCategory.FONTS);
        if (archiveCheck.isSelected()) selectedCategories.add(FormatCategory.ARCHIVE);
        if (otherCheck.isSelected()) selectedCategories.add(FormatCategory.OTHER);

        // 收集选中的具体格式扩展名
        Set<String> selectedExtensions = new HashSet<>();
        for (Map.Entry<FormatCategory, List<CheckBox>> entry : categoryFormatCheckboxes.entrySet()) {
            for (CheckBox cb : entry.getValue()) {
                if (cb.isSelected() && !cb.isDisabled()) {
                    selectedExtensions.add(cb.getText().toLowerCase());
                }
            }
        }

        // 根据分类和具体格式过滤
        if (!selectedCategories.isEmpty()) {
            registry.enableAll();
            for (FormatCategory c : FormatCategory.values()) {
                if (!selectedCategories.contains(c)) {
                    for (FileFormatInfo info : registry.getAllFormats()) {
                        if (info.getCategory() == c) {
                            registry.disable(info.getExtension());
                        }
                    }
                }
            }

            // 对于选中的分类，如果指定了具体格式，则只启用选中的格式
            if (!selectedExtensions.isEmpty()) {
                for (FormatCategory c : selectedCategories) {
                    for (FileFormatInfo info : registry.getAllFormats()) {
                        if (info.getCategory() == c && !selectedExtensions.contains(info.getExtension())) {
                            registry.disable(info.getExtension());
                        }
                    }
                }
            }
        } else if (!selectedExtensions.isEmpty()) {
            // 没有选中分类，但选中了具体格式
            registry.enableAll();
            Set<String> allExtensions = new HashSet<>();
            for (FileFormatInfo info : registry.getAllFormats()) {
                allExtensions.add(info.getExtension());
            }
            for (String ext : allExtensions) {
                if (!selectedExtensions.contains(ext)) {
                    registry.disable(ext);
                }
            }
        }

        long minSize = minSizeSpinner.getValue().longValue();
        long maxFileSizeMB = maxFileSizeSpinner.getValue().longValue();
        long maxFileSize = maxFileSizeMB * 1024L * 1024L;
        boolean verbose = verboseCheck.isSelected();
        boolean recursive = recursiveCheck.isSelected();
        boolean unpack = unpackCheck.isSelected();
        boolean peScan = peScanCheck.isSelected();
        boolean peAlso = peAlsoCheck.isSelected();
        boolean overwrite = overwriteCheck.isSelected();
        boolean dedup = dedupCheck.isSelected();

        appendLog("配置参数:");
        appendLog("  源: " + source);
        appendLog("  模式: " + mode);
        appendLog("  递归: " + recursive);
        appendLog("  最小大小: " + minSize + " bytes");
        appendLog("  最大文件: " + maxFileSizeMB + " MB");
        appendLog("  ZIP解包: " + unpack);
        appendLog("  PE段扫描: " + peScan);
        appendLog("  PE资源提取: " + peAlso);
        appendLog("  覆盖: " + overwrite);
        appendLog("  去重: " + dedup);
        appendLog("  详细: " + verbose);

        if (isPeOnly) {
            runPeExtract(source, recursive, overwrite, verbose);
            return;
        }

        FileScanner scanner = new FileScanner(registry);
        scanner.setVerbose(verbose);
        scanner.setMinResourceSize(minSize);
        scanner.setMaxFileSize(maxFileSize);
        scanner.setUnpackArchives(unpack);
        scanner.setPeScan(peScan);

        List<ScanResult> results;
        if (Files.isDirectory(source)) {
            appendLog("扫描目录: " + source + (recursive ? " (递归)" : ""));
            results = scanner.scanDirectory(source, recursive);
        } else {
            appendLog("扫描文件: " + source);
            results = scanner.scan(source);
        }

        final List<ScanResult> finalResults = results;
        currentResults = finalResults;

        appendLog("找到 " + finalResults.size() + " 个资源");

        Platform.runLater(() -> {
            resultListView.getItems().clear();
            int limit = Math.min(finalResults.size(), 500);
            for (int i = 0; i < limit; i++) {
                ScanResult r = finalResults.get(i);
                String sourceStr = r.getArchiveEntry() != null ? r.getArchiveEntry()
                        : (r.getSourceFile() != null ? r.getSourceFile().getFileName().toString() : "<mem>");
                String display = String.format("[%-4s] 0x%08X  %-10s  %s",
                        r.getFormat().getExtension(),
                        r.getOffset(),
                        FileUtils.humanSize(r.getSize()),
                        sourceStr);
                resultListView.getItems().add(display);
            }
            if (finalResults.size() > limit) {
                resultListView.getItems().add("... 还有 " + (finalResults.size() - limit) + " 个结果");
            }
            // 自动滚动到底部
            resultListView.scrollTo(resultListView.getItems().size() - 1);
        });

        if (isExtract) {
            String outPath = outputPathField.getText().trim();
            Path outDir = Paths.get(outPath);
            appendLog("提取到: " + outDir);

            Extractor extractor = new Extractor(outDir, overwrite, verbose, dedup);
            List<Path> written = extractor.extractAll(finalResults);
            appendLog("已提取 " + written.size() + " 个文件");

            Platform.runLater(() -> setStatus("提取完成！共 " + finalResults.size() + " 个资源，成功提取 " + written.size() + " 个", "#388E3C"));
        } else {
            Platform.runLater(() -> setStatus("扫描完成！共发现 " + finalResults.size() + " 个资源", "#388E3C"));
        }

        if (peAlso) {
            appendLog("PE资源提取:");
            Path outDir;
            String outPath = outputPathField.getText().trim();
            if (outPath.isEmpty()) {
                outDir = Paths.get(System.getProperty("user.dir"), "extracted_pe");
            } else {
                outDir = Paths.get(outPath).resolve("pe");
            }
            runPeExtract(source, recursive, overwrite, verbose);
        }
    }

    private void runPeExtract(Path source, boolean recursive, boolean overwrite, boolean verbose) {
        List<Path> peFiles = new ArrayList<>();
        if (Files.isDirectory(source)) {
            try {
                Files.walk(source, recursive ? Integer.MAX_VALUE : 1)
                        .filter(Files::isRegularFile)
                        .forEach(peFiles::add);
            } catch (IOException e) {
                appendLog("遍历错误: " + e.getMessage());
            }
        } else {
            peFiles.add(source);
        }

        List<Path> peTargets = new ArrayList<>();
        for (Path p : peFiles) {
            String name = p.getFileName().toString().toLowerCase();
            if (name.endsWith(".exe") || name.endsWith(".dll") || name.endsWith(".scr")
                    || name.endsWith(".fon") || name.endsWith(".sys") || name.endsWith(".ocx")) {
                peTargets.add(p);
            }
        }

        if (peTargets.isEmpty()) {
            appendLog("未找到PE文件 (.exe/.dll/.scr等)");
            return;
        }

        String outPath = outputPathField.getText().trim();
        Path outDir;
        if (outPath.isEmpty()) {
            outDir = Paths.get(System.getProperty("user.dir"), "extracted_pe");
        } else {
            outDir = Paths.get(outPath).resolve("pe");
        }

        try {
            FileUtils.ensureDir(outDir);
        } catch (IOException e) {
            appendLog("创建输出目录失败: " + e.getMessage());
            return;
        }

        appendLog("PE资源提取目录: " + outDir);

        PEResourceExtractor peExtractor = new PEResourceExtractor();
        int total = 0;
        for (Path peFile : peTargets) {
            appendLog("处理: " + peFile.getFileName());
            try (PEFile pe = new PEFile(peFile)) {
                List<PEResourceExtractor.ExtractedResource> resList = peExtractor.extract(pe);
                if (resList.isEmpty()) {
                    appendLog("  (无资源)");
                    continue;
                }
                String baseName = stripExt(peFile.getFileName().toString());
                for (PEResourceExtractor.ExtractedResource r : resList) {
                    String ext = r.extension.toLowerCase();
                    Path peOutDir = outDir.resolve(ext);
                    FileUtils.ensureDir(peOutDir);
                    Path outFile = peOutDir.resolve(baseName + "_" + r.name + "." + r.extension);
                    int n = 1;
                    while (Files.exists(outFile) && !overwrite) {
                        outFile = peOutDir.resolve(baseName + "_" + r.name + "_" + (n++) + "." + r.extension);
                    }
                    try (OutputStream os = Files.newOutputStream(outFile,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        os.write(r.data);
                    }
                    total++;
                    if (verbose) {
                        appendLog("  [ok] " + outFile.getFileName() + " (" + FileUtils.humanSize(r.data.length) + ")");
                    }
                }
            } catch (IOException e) {
                appendLog("  [错误] " + e.getMessage());
            }
        }
        appendLog("PE资源提取完成: " + total + " 个文件");
    }

    private String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private void setStatus(String text, String color) {
        Platform.runLater(() -> {
            statusLabel.setText(text);
            statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: " + color + ";");
        });
    }

    private void appendLog(String text) {
        Platform.runLater(() -> {
            logArea.appendText(text + "\n");
            // 自动滚动到底部
            logArea.setScrollTop(Double.MAX_VALUE);
        });
    }
}