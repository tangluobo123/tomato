package com.tangluobo.tomato.module.tools;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import java.io.*;
import java.nio.file.*;

/**
 * Linux 桌面快捷方式创建工具
 * 用于生成 .desktop 文件，支持选择图标、执行路径和其他参数
 */
public class DesktopShortcutPane extends VBox {

    // 基本信息
    private TextField nameField;
    private TextField commentField;
    private TextField genericNameField;

    // 执行路径
    private TextField execPathField;
    private TextField execArgsField;

    // 图标
    private TextField iconPathField;

    // 工作目录
    private TextField workingDirField;

    // 分类
    private ComboBox<String> categoryCombo;
    private TextField customCategoryField;

    // 其他选项
    private CheckBox terminalCheckBox;
    private CheckBox notifyCheckBox;
    private TextField startupWmClassField;

    // 输出设置
    private TextField outputDirField;
    private TextField outputFileNameField;

    // 预览区域
    private TextArea previewArea;

    // 状态标签
    private Label statusLabel;

    // 快捷方式名称（用于文件名，自动从Name生成）
    private Label fileNameLabel;

    public DesktopShortcutPane() {
        initializeUI();
    }

    private void initializeUI() {
        setStyle("-fx-background-color: #ffffff;");
        setFillWidth(true);
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);

        // 自定义标题栏
        HBox titleBar = new HBox(10);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(14, 20, 14, 20));
        titleBar.setStyle("-fx-background-color: #f7f8fa; -fx-border-color: #e8e8e8; -fx-border-width: 0 0 0 0;");
        SVGPath titleIcon = new SVGPath();
        titleIcon.setContent("M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-5 14H7v-2h7v2zm3-4H7v-2h10v2zm0-4H7V7h10v2z");
        titleIcon.setFill(Color.web("#1976D2"));
        titleIcon.setScaleX(0.75);
        titleIcon.setScaleY(0.75);
        Label titleLabel = new Label("桌面快捷方式");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333;");
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        Label subtitleLabel = new Label("创建 Linux .desktop 快捷方式");
        subtitleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #999;");
        titleBar.getChildren().addAll(titleIcon, titleLabel, titleSpacer, subtitleLabel);

        // 创建标签页 - 使用设计表的Firefox风格标签样式
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        tabPane.setStyle("-fx-background-color: transparent; -fx-padding: 0; -fx-border-color: transparent; -fx-border-width: 0; -fx-tab-content-padding: 0;");
        tabPane.getStyleClass().add("no-gap-tab-pane");

        // 基础设置标签页
        Tab basicTab = new Tab("基础设置");
        basicTab.setContent(createBasicTabContent());

        // 高级设置标签页
        Tab advancedTab = new Tab("高级设置");
        advancedTab.setContent(createAdvancedTabContent());

        // 预览标签页
        Tab previewTab = new Tab("预览");
        previewTab.setContent(createPreviewTabContent());

        tabPane.getTabs().addAll(basicTab, advancedTab, previewTab);
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        // 底部操作区域
        VBox actionArea = createActionArea();

        getChildren().addAll(titleBar, tabPane, actionArea);
    }

    // ==================== 标签页内容 ====================

    private ScrollPane createBasicTabContent() {
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-border-width: 0; -fx-padding: 0; -fx-background-insets: 0; -fx-border-insets: 0;");
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        VBox contentBox = new VBox(12);
        contentBox.setPadding(new Insets(15, 25, 15, 25));
        contentBox.setFillWidth(true);
        contentBox.setMaxWidth(Double.MAX_VALUE);

        // 使用GridPane实现紧凑的两列表单
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setAlignment(Pos.TOP_LEFT);

        // 设置列约束：第一列标签，第二列输入框
        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(90);
        labelCol.setMaxWidth(90);
        grid.getColumnConstraints().add(labelCol);

        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().add(fieldCol);

        int row = 0;

        // 应用名称
        Label nameLabel = new Label("应用名称 *");
        nameLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");
        grid.add(nameLabel, 0, row);

        nameField = createCompactTextField("请输入应用名称");
        nameField.textProperty().addListener((obs, oldVal, newVal) -> {
            updateFileNamePreview();
            updatePreview();
        });
        HBox nameBox = new HBox(nameField);
        HBox.setHgrow(nameField, Priority.ALWAYS);
        grid.add(nameBox, 1, row++);

        // 执行路径
        Label execLabel = new Label("执行路径 *");
        execLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");
        grid.add(execLabel, 0, row);

        execPathField = createCompactTextField("选择或输入可执行文件路径");
        execPathField.setPromptText("/usr/bin/app 或 /home/user/app");
        execPathField.textProperty().addListener((obs, oldVal, newVal) -> updatePreview());

        Button browseExecBtn = new Button("浏览...");
        browseExecBtn.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 4 10; -fx-background-radius: 3; -fx-cursor: hand;");
        browseExecBtn.setOnAction(e -> chooseExecFile());

        HBox execBox = new HBox(8);
        execBox.getChildren().addAll(execPathField, browseExecBtn);
        HBox.setHgrow(execPathField, Priority.ALWAYS);
        grid.add(execBox, 1, row++);

        // 图标文件（必需）
        Label iconLabel = new Label("图标文件 *");
        iconLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");
        grid.add(iconLabel, 0, row);

        iconPathField = createCompactTextField("选择或输入图标文件路径");
        iconPathField.setPromptText("/usr/share/icons/hicolor/.../app.png");
        iconPathField.textProperty().addListener((obs, oldVal, newVal) -> updatePreview());

        Button browseIconBtn = new Button("浏览...");
        browseIconBtn.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 4 10; -fx-background-radius: 3; -fx-cursor: hand;");
        browseIconBtn.setOnAction(e -> chooseIconFile());

        HBox iconBox = new HBox(8);
        iconBox.getChildren().addAll(iconPathField, browseIconBtn);
        HBox.setHgrow(iconPathField, Priority.ALWAYS);
        grid.add(iconBox, 1, row++);

        // 保存目录
        Label outDirLabel = new Label("保存目录 *");
        outDirLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");
        grid.add(outDirLabel, 0, row);

        outputDirField = createCompactTextField("保存位置");
        String defaultDir = System.getProperty("user.home") + "/.local/share/applications";
        outputDirField.setText(defaultDir);

        Button browseOutBtn = new Button("浏览...");
        browseOutBtn.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 4 10; -fx-background-radius: 3; -fx-cursor: hand;");
        browseOutBtn.setOnAction(e -> chooseOutputDir());

        Button homeBtn = new Button("用户目录");
        homeBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 4 8; -fx-background-radius: 3; -fx-cursor: hand;");
        homeBtn.setOnAction(e -> outputDirField.setText(System.getProperty("user.home") + "/.local/share/applications"));

        Button deskBtn = new Button("桌面");
        deskBtn.setStyle("-fx-background-color: #FF9800; -fx-text-fill: white; -fx-font-size: 11px; -fx-padding: 4 8; -fx-background-radius: 3; -fx-cursor: hand;");
        deskBtn.setOnAction(e -> outputDirField.setText(System.getProperty("user.home") + "/Desktop"));

        HBox outDirBox = new HBox(6);
        outDirBox.getChildren().addAll(outputDirField, browseOutBtn, homeBtn, deskBtn);
        HBox.setHgrow(outputDirField, Priority.ALWAYS);
        grid.add(outDirBox, 1, row++);

        // 窗口类名（必需）
        Label wmClassLabel = new Label("窗口类名 *");
        wmClassLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");
        grid.add(wmClassLabel, 0, row);

        startupWmClassField = createCompactTextField("窗口类名");
        startupWmClassField.setPromptText("用于任务栏图标匹配，如：jetbrains-idea");
        startupWmClassField.textProperty().addListener((obs, oldVal, newVal) -> updatePreview());
        grid.add(startupWmClassField, 1, row);

        contentBox.getChildren().add(grid);

        scrollPane.setContent(contentBox);
        return scrollPane;
    }

    // 紧凑样式的文本框
    private TextField createCompactTextField(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.setStyle("-fx-font-size: 12px; -fx-padding: 4 8; -fx-border-color: #d0d0d0; -fx-border-radius: 3; -fx-background-radius: 3;");
        return field;
    }

    private ScrollPane createAdvancedTabContent() {
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-border-width: 0; -fx-padding: 0; -fx-background-insets: 0; -fx-border-insets: 0;");
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        VBox contentBox = new VBox(12);
        contentBox.setPadding(new Insets(15, 25, 15, 25));
        contentBox.setFillWidth(true);
        contentBox.setMaxWidth(Double.MAX_VALUE);

        // 使用GridPane实现紧凑的两列表单
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.setAlignment(Pos.TOP_LEFT);

        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(90);
        labelCol.setMaxWidth(90);
        grid.getColumnConstraints().add(labelCol);

        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().add(fieldCol);

        int row = 0;

        // 通用名称
        Label gnLabel = new Label("通用名称");
        gnLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");
        grid.add(gnLabel, 0, row);

        genericNameField = createCompactTextField("可选，如：文本编辑器");
        grid.add(genericNameField, 1, row++);

        // 应用描述
        Label descLabel = new Label("应用描述");
        descLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");
        grid.add(descLabel, 0, row);

        commentField = createCompactTextField("请输入应用描述");
        grid.add(commentField, 1, row++);

        // 执行参数
        Label argsLabel = new Label("执行参数");
        argsLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");
        grid.add(argsLabel, 0, row);

        execArgsField = createCompactTextField("可选，如：--config /path/to/config");
        execArgsField.textProperty().addListener((obs, oldVal, newVal) -> updatePreview());
        grid.add(execArgsField, 1, row++);

        // 工作目录
        Label dirLabel = new Label("工作目录");
        dirLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");
        grid.add(dirLabel, 0, row);

        workingDirField = createCompactTextField("可选，应用运行时的工作目录");
        workingDirField.textProperty().addListener((obs, oldVal, newVal) -> updatePreview());

        Button browseDirBtn = new Button("浏览...");
        browseDirBtn.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-size: 12px; -fx-padding: 4 10; -fx-background-radius: 3; -fx-cursor: hand;");
        browseDirBtn.setOnAction(e -> chooseWorkingDir());

        HBox dirBox = new HBox(8);
        dirBox.getChildren().addAll(workingDirField, browseDirBtn);
        HBox.setHgrow(workingDirField, Priority.ALWAYS);
        grid.add(dirBox, 1, row++);

        // 文件名
        Label fileLabel = new Label("文件名");
        fileLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");
        grid.add(fileLabel, 0, row);

        outputFileNameField = createCompactTextField("自动根据应用名称生成");
        outputFileNameField.setPromptText("自动根据应用名称生成");
        outputFileNameField.textProperty().addListener((obs, oldVal, newVal) -> {
            updateFileNamePreview();
            updatePreview();
        });

        fileNameLabel = new Label("将生成: ");
        fileNameLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");

        VBox fileNameBox = new VBox(3);
        fileNameBox.getChildren().addAll(outputFileNameField, fileNameLabel);
        grid.add(fileNameBox, 1, row++);

        // 应用分类
        Label categoryLabel = new Label("应用分类");
        categoryLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");
        grid.add(categoryLabel, 0, row);

        VBox categoryBox = new VBox(6);
        categoryCombo = new ComboBox<>();
        categoryCombo.getItems().addAll(
                "网络应用", "办公", "开发", "游戏", "图形", "多媒体",
                "系统工具", "实用工具", "科学", "教育", "社会", "无障碍", "收藏"
        );
        categoryCombo.setValue("实用工具");
        categoryCombo.setStyle("-fx-font-size: 12px; -fx-padding: 3 6;");
        categoryCombo.valueProperty().addListener((obs, oldVal, newVal) -> updatePreview());

        customCategoryField = createCompactTextField("自定义分类，用分号分隔");
        customCategoryField.setPromptText("如：Utility;Development");
        customCategoryField.textProperty().addListener((obs, oldVal, newVal) -> updatePreview());

        categoryBox.getChildren().addAll(categoryCombo, customCategoryField);
        grid.add(categoryBox, 1, row++);

        // 终端模式
        terminalCheckBox = new CheckBox("在终端中运行（适用于命令行工具）");
        terminalCheckBox.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");
        terminalCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> updatePreview());

        Label terminalLabel = new Label("终端模式");
        terminalLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");
        grid.add(terminalLabel, 0, row);
        grid.add(terminalCheckBox, 1, row++);

        // 通知支持
        notifyCheckBox = new CheckBox("支持应用通知");
        notifyCheckBox.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");
        notifyCheckBox.selectedProperty().addListener((obs, oldVal, newVal) -> updatePreview());

        Label notifyLabel = new Label("通知支持");
        notifyLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");
        grid.add(notifyLabel, 0, row);
        grid.add(notifyCheckBox, 1, row);

        contentBox.getChildren().add(grid);

        scrollPane.setContent(contentBox);
        return scrollPane;
    }

    private VBox createPreviewTabContent() {
        VBox container = new VBox(15);
        container.setPadding(new Insets(20, 25, 20, 25));
        container.setFillWidth(true);

        // 标题
        Label previewTitle = new Label(".desktop 文件预览");
        previewTitle.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");
        container.getChildren().add(previewTitle);

        // 预览区域
        previewArea = new TextArea();
        previewArea.setEditable(false);
        previewArea.setStyle("-fx-font-family: 'Consolas', 'Courier New', monospace; -fx-font-size: 13px; -fx-padding: 10; -fx-background-color: #f8f9fa;");
        previewArea.setPrefRowCount(20);
        previewArea.setWrapText(false);
        VBox.setVgrow(previewArea, Priority.ALWAYS);
        container.getChildren().add(previewArea);

        return container;
    }

    // ==================== 底部操作区域 ====================

    private VBox createActionArea() {
        VBox actionArea = new VBox(10);
        actionArea.setPadding(new Insets(15, 25, 15, 25));
        actionArea.setStyle("-fx-background-color: #f7f8fa; -fx-border-color: #e8e8e8; -fx-border-width: 1 0 0 0;");

        // 按钮组
        HBox buttonRow = new HBox(15);
        buttonRow.setAlignment(Pos.CENTER);

        Button previewBtn = new Button("刷新预览");
        previewBtn.setStyle("-fx-background-color: #2196F3; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 30; -fx-background-radius: 4; -fx-cursor: hand;");
        previewBtn.setOnAction(e -> updatePreview());

        Button copyBtn = new Button("复制到剪贴板");
        copyBtn.setStyle("-fx-background-color: #9C27B0; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 30; -fx-background-radius: 4; -fx-cursor: hand;");
        copyBtn.setOnAction(e -> copyToClipboard());

        Button createBtn = new Button("创建快捷方式");
        createBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 8 30; -fx-background-radius: 4; -fx-cursor: hand;");
        createBtn.setOnAction(e -> createShortcut());

        buttonRow.getChildren().addAll(previewBtn, copyBtn, createBtn);
        actionArea.getChildren().add(buttonRow);

        // 状态标签
        statusLabel = new Label("");
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        actionArea.getChildren().add(statusLabel);

        // 初始预览
        updatePreview();

        return actionArea;
    }

    // ==================== 辅助方法 ====================

    private VBox createSection(String title, String description) {
        VBox section = new VBox(12);
        section.setPadding(new Insets(0));
        section.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e0e0e0; -fx-border-width: 0 0 1 0;");

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #333;");

        if (description != null && !description.isEmpty()) {
            Label descLabel = new Label(description);
            descLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #888;");
            section.getChildren().addAll(titleLabel, descLabel);
        } else {
            section.getChildren().add(titleLabel);
        }

        return section;
    }

    private TextField createTextField(String prompt, int prefWidth) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        field.setStyle("-fx-font-size: 13px; -fx-padding: 6 10; -fx-border-color: #d0d0d0; -fx-border-radius: 4; -fx-background-radius: 4;");
        field.setPrefWidth(prefWidth);
        return field;
    }

    private HBox createFieldRow(String labelText, Control control) {
        Label label = new Label(labelText);
        label.setStyle("-fx-font-size: 13px; -fx-text-fill: #555; -fx-min-width: 100;");

        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        row.getChildren().addAll(label, control);
        HBox.setHgrow(control, Priority.ALWAYS);

        return row;
    }

    private HBox createCheckRow(CheckBox checkBox) {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(5, 0, 5, 100));
        row.getChildren().add(checkBox);
        return row;
    }

    // ==================== 文件选择 ====================

    private void chooseExecFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择可执行文件");

        // 从常用目录开始
        String homeDir = System.getProperty("user.home");
        if (new File(homeDir + "/bin").exists()) {
            chooser.setInitialDirectory(new File(homeDir + "/bin"));
        } else if (new File("/usr/bin").exists()) {
            chooser.setInitialDirectory(new File("/usr/bin"));
        }

        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file != null) {
            execPathField.setText(file.getAbsolutePath());
            if (nameField.getText().isEmpty()) {
                String appName = file.getName();
                // 移除常见扩展名
                if (appName.endsWith(".sh")) appName = appName.substring(0, appName.length() - 3);
                else if (appName.endsWith(".AppImage")) appName = appName.substring(0, appName.length() - 9);
                nameField.setText(appName);
            }
            updateFileNamePreview();
            updatePreview();
        }
    }

    private void chooseWorkingDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择工作目录");

        String path = workingDirField.getText().trim();
        if (!path.isEmpty() && new File(path).exists()) {
            chooser.setInitialDirectory(new File(path));
        }

        File dir = chooser.showDialog(getScene().getWindow());
        if (dir != null) {
            workingDirField.setText(dir.getAbsolutePath());
            updatePreview();
        }
    }

    private void chooseIconFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择图标文件");

        // 图标文件过滤器
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("图标文件", "*.png", "*.svg", "*.ico", "*.jpg", "*.jpeg", "*.gif"),
                new FileChooser.ExtensionFilter("PNG 图标", "*.png"),
                new FileChooser.ExtensionFilter("SVG 图标", "*.svg"),
                new FileChooser.ExtensionFilter("ICO 图标", "*.ico"),
                new FileChooser.ExtensionFilter("所有文件", "*.*")
        );

        // 从常用图标目录开始
        String[] iconDirs = {
                "/usr/share/icons",
                "/usr/share/pixmaps",
                System.getProperty("user.home") + "/.local/share/icons",
                System.getProperty("user.home") + "/.icons"
        };
        for (String dir : iconDirs) {
            if (new File(dir).exists()) {
                chooser.setInitialDirectory(new File(dir));
                break;
            }
        }

        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file != null) {
            iconPathField.setText(file.getAbsolutePath());
            updatePreview();
        }
    }

    private void chooseOutputDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择保存目录");

        String path = outputDirField.getText().trim();
        if (!path.isEmpty() && new File(path).exists()) {
            chooser.setInitialDirectory(new File(path));
        }

        File dir = chooser.showDialog(getScene().getWindow());
        if (dir != null) {
            outputDirField.setText(dir.getAbsolutePath());
            updatePreview();
        }
    }

    // ==================== 文件名预览 ====================

    private void updateFileNamePreview() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            fileNameLabel.setText("将生成:  (请先输入应用名称)");
            return;
        }

        // 如果用户没有手动设置文件名，自动生成
        String currentFile = outputFileNameField.getText();
        if (currentFile.isEmpty() || currentFile.equals(autoGenerateFileName(name))) {
            outputFileNameField.setText(autoGenerateFileName(name));
        }

        String fileName = outputFileNameField.getText();
        if (!fileName.endsWith(".desktop")) {
            fileName += ".desktop";
        }
        fileNameLabel.setText("将生成: " + fileName);
    }

    private String autoGenerateFileName(String name) {
        // 将名称转换为适合文件名的格式
        return name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    // ==================== 预览生成 ====================

    private void updatePreview() {
        StringBuilder desktop = new StringBuilder();
        desktop.append("[Desktop Entry]\n");

        // Type
        desktop.append("Type=Application\n");

        // Name (必须)
        String name = nameField.getText().trim();
        if (!name.isEmpty()) {
            desktop.append("Name=").append(escapeValue(name)).append("\n");
        }

        // GenericName
        String genericName = genericNameField.getText().trim();
        if (!genericName.isEmpty()) {
            desktop.append("GenericName=").append(escapeValue(genericName)).append("\n");
        }

        // Comment
        String comment = commentField.getText().trim();
        if (!comment.isEmpty()) {
            desktop.append("Comment=").append(escapeValue(comment)).append("\n");
        }

        // Exec (必须)
        String exec = execPathField.getText().trim();
        if (!exec.isEmpty()) {
            String args = execArgsField.getText().trim();
            String execValue = escapeValue(exec);
            if (!args.isEmpty()) {
                execValue += " " + escapeValue(args);
            }
            desktop.append("Exec=").append(execValue).append("\n");
        }

        // Path
        String path = workingDirField.getText().trim();
        if (!path.isEmpty()) {
            desktop.append("Path=").append(escapeValue(path)).append("\n");
        }

        // Icon
        String icon = iconPathField.getText().trim();
        if (!icon.isEmpty()) {
            File iconFile = new File(icon);
            if (iconFile.exists()) {
                desktop.append("Icon=").append(iconFile.getAbsolutePath()).append("\n");
            } else {
                desktop.append("Icon=").append(icon).append("\n");
            }
        }

        // Terminal
        desktop.append("Terminal=").append(terminalCheckBox.isSelected() ? "true" : "false").append("\n");

        // Categories
        StringBuilder categories = new StringBuilder();
        String selectedCategory = categoryCombo.getValue();
        if (selectedCategory != null && !selectedCategory.isEmpty()) {
            String mapped = mapCategory(selectedCategory);
            if (mapped != null && !mapped.isEmpty()) {
                categories.append(mapped).append(";");
            }
        }
        String customCat = customCategoryField.getText().trim();
        if (!customCat.isEmpty()) {
            if (!categories.toString().contains(";") || customCat.contains(";")) {
                categories.append(customCat);
                if (!customCat.endsWith(";")) {
                    categories.append(";");
                }
            } else {
                categories.append(customCat).append(";");
            }
        }
        if (categories.length() > 0) {
            desktop.append("Categories=").append(categories.toString()).append("\n");
        }

        // StartupNotify
        if (notifyCheckBox.isSelected()) {
            desktop.append("StartupNotify=true\n");
        }

        // StartupWMClass
        String wmClass = startupWmClassField.getText().trim();
        if (!wmClass.isEmpty()) {
            desktop.append("StartupWMClass=").append(escapeValue(wmClass)).append("\n");
        }

        // Actions
        // 添加一个默认的启动操作
        if (!name.isEmpty()) {
            String actionName = name.replaceAll("\"", "");
            desktop.append("Actions=Launch;\n");
            desktop.append("\n");
            desktop.append("[Desktop Action Launch]\n");
            desktop.append("Name=").append(escapeValue(actionName)).append("\n");
            desktop.append("Exec=").append(exec != null ? escapeValue(exec) : "").append("\n");
        }

        previewArea.setText(desktop.toString());
        updateFileNamePreview();
    }

    private String escapeValue(String value) {
        if (value == null) return "";
        // .desktop 文件格式中，值如果包含特殊字符需要转义
        // 这里处理常见情况
        if (value.contains(" ") || value.contains("\t") || value.contains("\\") || value.contains("\"")) {
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\\' || c == '\"') {
                    sb.append('\\').append(c);
                } else if (c == '\t') {
                    sb.append("\\t");
                } else {
                    sb.append(c);
                }
            }
            sb.append("\"");
            return sb.toString();
        }
        return value;
    }

    private String mapCategory(String chineseCategory) {
        return switch (chineseCategory) {
            case "网络应用" -> "Network";
            case "办公" -> "Office";
            case "开发" -> "Development";
            case "游戏" -> "Game";
            case "图形" -> "Graphics";
            case "多媒体" -> "AudioVideo";
            case "系统工具" -> "System";
            case "实用工具" -> "Utility";
            case "科学" -> "Science";
            case "教育" -> "Education";
            case "社会" -> "Social";
            case "无障碍" -> "Accessibility";
            case "收藏" -> "Favorites";
            default -> null;
        };
    }

    // ==================== 操作方法 ====================

    private void copyToClipboard() {
        String content = previewArea.getText();
        if (content == null || content.isEmpty()) {
            showStatus("没有可复制的内容", true);
            return;
        }
        ClipboardContent clipboardContent = new ClipboardContent();
        clipboardContent.putString(content);
        Clipboard.getSystemClipboard().setContent(clipboardContent);
        showStatus("已复制到剪贴板！", false);
    }

    private void createShortcut() {
        // 验证必填字段
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            showStatus("请输入应用名称", true);
            return;
        }

        String exec = execPathField.getText().trim();
        if (exec.isEmpty()) {
            showStatus("请选择或输入执行路径", true);
            return;
        }

        String icon = iconPathField.getText().trim();
        if (icon.isEmpty()) {
            showStatus("请选择图标文件", true);
            return;
        }

        String wmClass = startupWmClassField.getText().trim();
        if (wmClass.isEmpty()) {
            showStatus("请输入窗口类名", true);
            return;
        }

        String outputDir = outputDirField.getText().trim();
        if (outputDir.isEmpty()) {
            showStatus("请设置保存目录", true);
            return;
        }

        // 获取完整的 .desktop 内容
        String content = previewArea.getText();
        if (content == null || content.isEmpty()) {
            showStatus("没有可创建的内容", true);
            return;
        }

        // 确定文件名
        String fileName = outputFileNameField.getText().trim();
        if (fileName.isEmpty()) {
            fileName = autoGenerateFileName(name);
        }
        if (!fileName.endsWith(".desktop")) {
            fileName += ".desktop";
        }

        try {
            // 确保输出目录存在
            File dir = new File(outputDir);
            if (!dir.exists()) {
                boolean created = dir.mkdirs();
                if (!created) {
                    showStatus("无法创建输出目录: " + outputDir, true);
                    return;
                }
            }

            // 检查图标文件
            String iconPath = iconPathField.getText().trim();
            if (!iconPath.isEmpty()) {
                File iconFile = new File(iconPath);
                if (!iconFile.exists()) {
                    showStatus("警告: 图标文件不存在: " + iconPath, true);
                    // 继续创建，但给出警告
                }
            }

            // 写入 .desktop 文件
            File targetFile = new File(dir, fileName);
            try (FileWriter writer = new FileWriter(targetFile)) {
                writer.write(content);
            }

            // 设置可执行权限（部分桌面环境需要）
            targetFile.setExecutable(true, false);

            // 如果是桌面目录，还需要设置可执行权限
            if (outputDir.contains("Desktop") || outputDir.contains("桌面")) {
                targetFile.setReadable(true, false);
                targetFile.setExecutable(true, false);
            }

            String successMsg = "快捷方式创建成功！\n位置: " + targetFile.getAbsolutePath();

            // 如果在桌面，提示需要设置权限
            if (outputDir.contains("Desktop") || outputDir.contains("桌面")) {
                successMsg += "\n\n提示: 在某些桌面环境下，右键点击图标选择'允许启动'即可使用。";
            }

            showStatus(successMsg, false);

            // 尝试刷新桌面数据库（如果是系统应用目录）
            if (outputDir.contains("applications")) {
                try {
                    // 可选：使用 update-desktop-database
                    String[] commands = {"update-desktop-database", outputDir};
                    ProcessBuilder pb = new ProcessBuilder(commands);
                    pb.start();
                } catch (Exception e) {
                    // 忽略，这不是必须的
                }
            }

        } catch (IOException e) {
            showStatus("创建失败: " + e.getMessage(), true);
        }
    }

    private void showStatus(String message, boolean isError) {
        statusLabel.setText(message);
        if (isError) {
            statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #e53935;");
        } else {
            statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #388E3C;");
            PauseTransition pause = new PauseTransition(Duration.millis(5000));
            pause.setOnFinished(e -> {
                Platform.runLater(() -> {
                    statusLabel.setText("");
                    statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");
                });
            });
            pause.play();
        }
    }
}
