package com.tangluobo.tomato.module.tools;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.TextAlignment;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 图片背景透明化工具面板
 * 通过亮度阈值将白/灰背景像素转为透明，并保留彩色主体；
 * 边缘过渡区按亮度线性映射 alpha 以消除锯齿。
 *
 * 功能：
 *  - 预览：单文件模式下并排显示原图与透明后效果（棋盘格背景）
 *  - 手动裁切：预览图上叠加可拖拽选区（8 个手柄 + 遮罩）
 *  - 自动裁切：可选自动裁切周边透明区域（批量生效）
 *  - 保存：单文件直接保存到源文件同目录；目录模式批量处理
 */
public class ImageBackgroundRemoverPane extends VBox {

    private TextField sourcePathField;
    private Slider thresholdSlider;
    private Label thresholdValueLabel;
    private Label statusLabel;
    private Button convertButton;

    private List<File> sourceFiles = new ArrayList<>();
    private boolean isDirMode = true;

    // 自动裁切选项
    private CheckBox autoCropCheckBox;

    // 预览相关
    private VBox previewSection;
    private Canvas originalCanvas;
    private ImageView resultImageView;
    private Label originalInfoLabel;
    private Label resultInfoLabel;
    private Label previewHintLabel;
    private File currentPreviewFile;
    private final PauseTransition previewDebounce = new PauseTransition(Duration.millis(300));

    // 手动裁切选区
    private CropOverlay cropOverlay;
    private Button applyCropButton;
    private Button resetCropButton;
    private CheckBox lockSquareCheckBox;
    private TextField topField, bottomField, leftField, rightField;
    private Label cropInfoLabel;
    private BufferedImage currentOriginalImage;   // 原图
    private BufferedImage currentTransparentImage; // 透明化后的完整图（裁切基准）
    private int currentImgW, currentImgH;
    private boolean isSyncingFields = false;

    // 支持 jpg、jpeg、png
    private static final String[] SUPPORTED_EXT = {".jpg", ".jpeg", ".png"};

    // 预览容器固定尺寸
    private static final double PREVIEW_SIZE = 280;
    private static final double CHECKER_CELL = 10;
    private static final double IMG_FIT = PREVIEW_SIZE - 10;

    public ImageBackgroundRemoverPane() {
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
        titleBar.setStyle("-fx-background-color: #f7f8fa; -fx-border-color: #e8e8e8; -fx-border-width: 0 0 1 0;");
        SVGPath titleIcon = new SVGPath();
        titleIcon.setContent("M11.99 18.54l-7.37-5.73L3 14.07l9 7 9-7-1.63-1.27-7.38 5.74zM12 16l7.36-5.73L21 9l-9-7-9 7 1.63 1.27L12 16z");
        titleIcon.setFill(Color.web("#1976D2"));
        titleIcon.setScaleX(0.75);
        titleIcon.setScaleY(0.75);
        Label titleLabel = new Label("图片背景透明化");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333;");
        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);
        Label subtitleLabel = new Label("JPG/PNG → 透明PNG（支持预览与裁切）");
        subtitleLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #999;");
        titleBar.getChildren().addAll(titleIcon, titleLabel, titleSpacer, subtitleLabel);

        // 内容区域
        VBox contentBox = new VBox(20);
        contentBox.setPadding(new Insets(20, 25, 25, 25));
        contentBox.setFillWidth(true);
        contentBox.setMaxWidth(Double.MAX_VALUE);

        // 转换说明
        VBox typeBox = createSection("转换说明");
        HBox typeContent = new HBox(10);
        typeContent.setAlignment(Pos.CENTER_LEFT);
        typeContent.setMaxWidth(Double.MAX_VALUE);
        typeContent.setPadding(new Insets(10, 15, 10, 15));
        typeContent.setStyle("-fx-background-color: #f8f9fa; -fx-border-color: #e0e0e0; -fx-border-radius: 4; -fx-background-radius: 4;");
        Label typeLabel = new Label("亮度阈值法：≥阈值 → 透明；< 阈值-40 → 不透明；过渡区线性 alpha 抗锯齿");
        typeLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #555;");
        typeLabel.setWrapText(true);
        typeContent.getChildren().add(typeLabel);
        typeBox.getChildren().add(typeContent);

        // 阈值滑块
        VBox thresholdBox = createSection("背景亮度阈值");
        HBox thresholdRow = new HBox(10);
        thresholdRow.setAlignment(Pos.CENTER_LEFT);
        thresholdRow.setMaxWidth(Double.MAX_VALUE);
        thresholdRow.setPadding(new Insets(10, 15, 10, 15));
        thresholdRow.setStyle("-fx-background-color: #f8f9fa; -fx-border-color: #e0e0e0; -fx-border-radius: 4; -fx-background-radius: 4;");
        thresholdSlider = new Slider(180, 254, 235);
        thresholdSlider.setShowTickLabels(true);
        thresholdSlider.setShowTickMarks(true);
        thresholdSlider.setMajorTickUnit(10);
        thresholdSlider.setMinorTickCount(1);
        thresholdSlider.setSnapToTicks(false);
        HBox.setHgrow(thresholdSlider, Priority.ALWAYS);
        thresholdValueLabel = new Label("235");
        thresholdValueLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #333; -fx-font-weight: bold;");
        thresholdValueLabel.setPrefWidth(30);
        thresholdValueLabel.setAlignment(Pos.CENTER_RIGHT);
        thresholdSlider.valueProperty().addListener((obs, o, n) -> {
            thresholdValueLabel.setText(String.valueOf((int) n.doubleValue()));
            schedulePreviewUpdate();
        });
        thresholdRow.getChildren().addAll(thresholdSlider, thresholdValueLabel);
        thresholdBox.getChildren().add(thresholdRow);

        // 输出选项（自动裁切）
        VBox optionBox = createSection("输出选项");
        HBox optionRow = new HBox(10);
        optionRow.setAlignment(Pos.CENTER_LEFT);
        optionRow.setMaxWidth(Double.MAX_VALUE);
        optionRow.setPadding(new Insets(10, 15, 10, 15));
        optionRow.setStyle("-fx-background-color: #f8f9fa; -fx-border-color: #e0e0e0; -fx-border-radius: 4; -fx-background-radius: 4;");
        autoCropCheckBox = new CheckBox("自动裁切周边透明区域（按非透明像素最小包围矩形，批量生效）");
        autoCropCheckBox.setStyle("-fx-font-size: 13px; -fx-text-fill: #555;");
        autoCropCheckBox.setSelected(true);
        autoCropCheckBox.setOnAction(e -> schedulePreviewUpdate());
        optionRow.getChildren().add(autoCropCheckBox);
        optionBox.getChildren().add(optionRow);

        // 源文件/目录
        VBox sourceBox = createSourceSection();
        sourcePathField = (TextField) ((HBox) sourceBox.getChildren().get(1)).getChildren().get(0);

        // 预览区
        previewSection = createPreviewSection();

        // 保存按钮 + 状态
        convertButton = new Button("保存");
        convertButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 14px; -fx-padding: 8 30; -fx-background-radius: 4; -fx-cursor: hand;");
        convertButton.setOnAction(e -> startSave());

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #666;");
        statusLabel.setWrapText(true);

        HBox buttonBox = new HBox(15);
        buttonBox.setAlignment(Pos.CENTER_LEFT);
        buttonBox.setMaxWidth(Double.MAX_VALUE);
        buttonBox.getChildren().addAll(convertButton, statusLabel);

        contentBox.getChildren().addAll(typeBox, thresholdBox, optionBox, sourceBox, previewSection, buttonBox);

        getChildren().addAll(titleBar, contentBox);
        VBox.setVgrow(contentBox, Priority.ALWAYS);

        previewDebounce.setOnFinished(e -> updatePreview());
        hidePreviewSection();
    }

    private VBox createSection(String title) {
        VBox box = new VBox(8);
        box.setFillWidth(true);
        box.setMaxWidth(Double.MAX_VALUE);
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #333;");
        box.getChildren().add(titleLabel);
        return box;
    }

    private VBox createSourceSection() {
        VBox box = createSection("选择源文件/目录");

        HBox pathRow = new HBox(8);
        pathRow.setAlignment(Pos.CENTER_LEFT);
        pathRow.setMaxWidth(Double.MAX_VALUE);

        TextField pathField = new TextField();
        pathField.setPromptText("选择 JPG/JPEG/PNG 文件或目录");
        pathField.setStyle("-fx-font-size: 13px; -fx-padding: 6 10; -fx-border-color: #d0d0d0; -fx-border-radius: 4; -fx-background-radius: 4;");
        HBox.setHgrow(pathField, Priority.ALWAYS);

        Button fileBtn = new Button("选择文件");
        fileBtn.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6 16; -fx-background-radius: 4; -fx-cursor: hand;");
        fileBtn.setOnAction(e -> chooseSourceFile());

        Button browseButton = new Button("浏览目录");
        browseButton.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6 16; -fx-background-radius: 4; -fx-cursor: hand;");
        browseButton.setOnAction(e -> chooseSourceDir());

        pathRow.getChildren().addAll(pathField, fileBtn, browseButton);
        box.getChildren().add(pathRow);
        return box;
    }

    /**
     * 预览区块：左侧原图，中间透明后效果（Canvas 直接绘制 + 裁切选区），右侧裁切控制面板。
     * 用 Canvas 像素级绘制透明图像 + 棋盘格，彻底避免 ImageView/StackPane 透明渲染问题。
     */
    private VBox createPreviewSection() {
        VBox box = createSection("预览（仅单文件模式）");

        HBox previewRow = new HBox(20);
        previewRow.setAlignment(Pos.TOP_CENTER);
        previewRow.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(previewRow, Priority.ALWAYS);

        // 左：原图 Canvas
        VBox leftBox = new VBox(6);
        leftBox.setAlignment(Pos.CENTER);
        originalCanvas = new Canvas(PREVIEW_SIZE, PREVIEW_SIZE);
        originalCanvas.setStyle("-fx-border-color: #d0d0d0; -fx-border-radius: 4; -fx-background-radius: 4; -fx-background-color: #f5f5f5;");
        originalInfoLabel = new Label("原图：未选择");
        originalInfoLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #888;");
        originalInfoLabel.setWrapText(true);
        originalInfoLabel.setMaxWidth(PREVIEW_SIZE);
        originalInfoLabel.setAlignment(Pos.CENTER);
        originalInfoLabel.setTextAlignment(TextAlignment.CENTER);
        leftBox.getChildren().addAll(originalCanvas, originalInfoLabel);

        // 中：透明后效果 ImageView + 裁切选区
        VBox middleBox = new VBox(6);
        middleBox.setAlignment(Pos.CENTER);
        StackPane resultContainer = new StackPane();
        resultContainer.setPrefSize(PREVIEW_SIZE, PREVIEW_SIZE);
        resultContainer.setMaxSize(PREVIEW_SIZE, PREVIEW_SIZE);
        resultContainer.setMinSize(PREVIEW_SIZE, PREVIEW_SIZE);
        resultContainer.setStyle("-fx-background-color: white; -fx-border-color: #d0d0d0; -fx-border-radius: 4; -fx-background-radius: 4;");
        resultImageView = new ImageView();
        resultImageView.setFitWidth(IMG_FIT);
        resultImageView.setFitHeight(IMG_FIT);
        resultImageView.setPreserveRatio(true);
        resultImageView.setSmooth(true);
        resultContainer.getChildren().add(resultImageView);
        cropOverlay = new CropOverlay();
        cropOverlay.setOnSelectionChanged(this::syncDistanceFields);
        resultContainer.getChildren().add(cropOverlay);
        resultInfoLabel = new Label("透明后：未选择");
        resultInfoLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #888;");
        resultInfoLabel.setWrapText(true);
        resultInfoLabel.setMaxWidth(PREVIEW_SIZE);
        resultInfoLabel.setAlignment(Pos.CENTER);
        resultInfoLabel.setTextAlignment(TextAlignment.CENTER);
        middleBox.getChildren().addAll(resultContainer, resultInfoLabel);

        // 右：裁切控制面板
        VBox controlBox = createCropControlPanel();

        previewRow.getChildren().addAll(leftBox, middleBox, controlBox);
        box.getChildren().add(previewRow);

        previewHintLabel = new Label("提示：选择单个文件可在此预览原图与透明后效果，棋盘格代表透明区域。拖动选区或修改右侧距离值可手动裁切。");
        previewHintLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #999;");
        previewHintLabel.setWrapText(true);
        previewHintLabel.setMaxWidth(Double.MAX_VALUE);
        box.getChildren().add(previewHintLabel);

        return box;
    }

    /**
     * 裁切控制面板：锁定正方形 + 上下左右距离输入 + 应用/重置按钮 + 信息标签。
     */
    private VBox createCropControlPanel() {
        VBox panel = new VBox(10);
        panel.setPrefWidth(170);
        panel.setMaxWidth(170);
        panel.setMinWidth(170);
        panel.setPadding(new Insets(10));
        panel.setStyle("-fx-background-color: #f8f9fa; -fx-border-color: #e0e0e0; -fx-border-radius: 4; -fx-background-radius: 4;");

        lockSquareCheckBox = new CheckBox("锁定正方形");
        lockSquareCheckBox.setStyle("-fx-font-size: 13px; -fx-text-fill: #333; -fx-font-weight: bold; -fx-cursor: hand;");
        lockSquareCheckBox.setSelected(true);
        lockSquareCheckBox.setOnAction(e -> {
            cropOverlay.setSquareLocked(lockSquareCheckBox.isSelected());
            syncDistanceFields();
        });
        panel.getChildren().add(lockSquareCheckBox);

        panel.getChildren().add(new Separator());

        Label marginTitle = new Label("距各边距离 (px)");
        marginTitle.setStyle("-fx-font-size: 12px; -fx-text-fill: #666; -fx-font-weight: bold;");
        panel.getChildren().add(marginTitle);

        GridPane marginGrid = new GridPane();
        marginGrid.setHgap(8);
        marginGrid.setVgap(8);
        ColumnConstraints col = new ColumnConstraints();
        col.setHgrow(Priority.ALWAYS);
        marginGrid.getColumnConstraints().addAll(col, col);

        topField = createMarginField();
        bottomField = createMarginField();
        leftField = createMarginField();
        rightField = createMarginField();

        marginGrid.add(buildMarginRow("上", topField), 0, 0);
        marginGrid.add(buildMarginRow("下", bottomField), 1, 0);
        marginGrid.add(buildMarginRow("左", leftField), 0, 1);
        marginGrid.add(buildMarginRow("右", rightField), 1, 1);
        panel.getChildren().add(marginGrid);

        panel.getChildren().add(new Separator());

        applyCropButton = new Button("应用裁切");
        applyCropButton.setStyle("-fx-background-color: #1976D2; -fx-text-fill: white; -fx-font-size: 13px; -fx-padding: 6 0; -fx-background-radius: 4; -fx-cursor: hand;");
        applyCropButton.setMaxWidth(Double.MAX_VALUE);
        applyCropButton.setDisable(true);
        applyCropButton.setOnAction(e -> applyManualCrop());

        resetCropButton = new Button("重置选区");
        resetCropButton.setStyle("-fx-background-color: #f5f5f5; -fx-text-fill: #333; -fx-font-size: 13px; -fx-padding: 6 0; -fx-background-radius: 4; -fx-cursor: hand; -fx-border-color: #d0d0d0;");
        resetCropButton.setMaxWidth(Double.MAX_VALUE);
        resetCropButton.setDisable(true);
        resetCropButton.setOnAction(e -> resetCrop());

        panel.getChildren().addAll(applyCropButton, resetCropButton);

        panel.getChildren().add(new Separator());

        cropInfoLabel = new Label("拖动选区或修改距离值调整裁切范围");
        cropInfoLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #666;");
        cropInfoLabel.setWrapText(true);
        panel.getChildren().add(cropInfoLabel);

        return panel;
    }

    private TextField createMarginField() {
        TextField f = new TextField("0");
        f.setPrefWidth(60);
        f.setStyle("-fx-font-size: 13px; -fx-padding: 4 6; -fx-border-color: #d0d0d0; -fx-border-radius: 3; -fx-background-radius: 3;");
        f.setOnAction(e -> onMarginFieldEdit());
        f.focusedProperty().addListener((obs, old, focused) -> {
            if (!focused) onMarginFieldEdit();
        });
        return f;
    }

    private HBox buildMarginRow(String text, TextField field) {
        HBox row = new HBox(4);
        row.setAlignment(Pos.CENTER_LEFT);
        Label lbl = new Label(text);
        lbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #555;");
        lbl.setPrefWidth(14);
        HBox.setHgrow(field, Priority.ALWAYS);
        row.getChildren().addAll(lbl, field);
        return row;
    }

    private void hidePreviewSection() {
        previewSection.setVisible(false);
        previewSection.setManaged(false);
    }

    private void showPreviewSection() {
        previewSection.setVisible(true);
        previewSection.setManaged(true);
    }

    // ===================== 交互逻辑 =====================

    private void chooseSourceFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择图片文件");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("图片文件", "*.jpg", "*.jpeg", "*.png"));
        File file = chooser.showOpenDialog(getScene().getWindow());
        if (file != null) {
            isDirMode = false;
            sourcePathField.setText(file.getAbsolutePath());
            sourceFiles.clear();
            sourceFiles.add(file);
            currentPreviewFile = file;
            showPreviewSection();
            schedulePreviewUpdate();
        }
    }

    private void chooseSourceDir() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择图片目录");
        File dir = chooser.showDialog(getScene().getWindow());
        if (dir != null) {
            isDirMode = true;
            sourcePathField.setText(dir.getAbsolutePath());
            sourceFiles.clear();
            try {
                Files.walk(dir.toPath())
                        .filter(p -> isSupported(p.toString()))
                        .forEach(p -> sourceFiles.add(p.toFile()));
            } catch (IOException e) {
                // ignore
            }
            currentPreviewFile = null;
            currentOriginalImage = null;
            currentTransparentImage = null;
            cropOverlay.clearImage();
            hidePreviewSection();
        }
    }

    private boolean isSupported(String name) {
        String lower = name.toLowerCase();
        for (String ext : SUPPORTED_EXT) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    private void schedulePreviewUpdate() {
        if (currentPreviewFile == null || isDirMode) {
            return;
        }
        previewDebounce.playFromStart();
    }

    /**
     * 异步加载原图与透明后效果，绘制到 Canvas。
     */
    private void updatePreview() {
        final File file = currentPreviewFile;
        if (file == null || isDirMode) {
            return;
        }
        final int threshold = (int) thresholdSlider.getValue();

        originalInfoLabel.setText("原图：加载中...");
        resultInfoLabel.setText("透明后：加载中...");
        clearCanvas(originalCanvas);
        resultImageView.setImage(null);
        applyCropButton.setDisable(true);
        resetCropButton.setDisable(true);
        cropOverlay.clearImage();

        Thread thread = new Thread(() -> {
            try {
                BufferedImage src = ImageIO.read(file);
                if (src == null) {
                    Platform.runLater(() -> {
                        originalInfoLabel.setText("原图：读取失败");
                        resultInfoLabel.setText("透明后：读取失败");
                    });
                    return;
                }
                final int srcW = src.getWidth();
                final int srcH = src.getHeight();
                // 预览始终基于完整透明化后的图（不应用 autoCrop）
                BufferedImage result = processImage(src, threshold, false);

                final BufferedImage srcFinal = src;
                final BufferedImage resultFinal = result;
                Platform.runLater(() -> {
                    currentOriginalImage = srcFinal;
                    currentTransparentImage = resultFinal;
                    currentImgW = srcW;
                    currentImgH = srcH;

                    // 绘制原图（无棋盘格）
                    drawImageOnCanvas(originalCanvas, srcFinal);
                    // 绘制透明后效果（棋盘格 + 图像合成 → ImageView）
                    drawTransparentImageWithChecker(resultImageView, resultFinal);

                    double[] disp = computeImageDisplayRect(srcW, srcH);
                    cropOverlay.setImageRect(srcW, srcH, disp[0], disp[1], disp[2], disp[3]);

                    originalInfoLabel.setText(String.format("原图：%s  %d × %d", file.getName(), srcW, srcH));
                    resultInfoLabel.setText(String.format("透明后：%d × %d（拖动选区裁切）", srcW, srcH));
                    applyCropButton.setDisable(false);
                    resetCropButton.setDisable(false);
                    syncDistanceFields();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    originalInfoLabel.setText("原图：加载失败");
                    resultInfoLabel.setText("透明后：" + e.getMessage());
                });
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 清空 Canvas（填充白色，避免透明区域渲染为黑色）。
     */
    private void clearCanvas(Canvas canvas) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
    }

    /**
     * 将 BufferedImage 绘制到 Canvas（缩放到 IMG_FIT 居中）。
     * 原图可能有 alpha 通道，先合成到白色背景生成不透明 RGB 图像，
     * 再用 PixelFormat.getByteBgraInstance() 转 WritableImage 绘制，
     * 彻底避免透明像素渲染为黑色。
     */
    private void drawImageOnCanvas(Canvas canvas, BufferedImage img) {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        if (img == null) return;

        // 合成到不透明 RGB BufferedImage
        BufferedImage opaque = flattenToOpaque(img, 255, 255, 255);
        double[] disp = computeImageDisplayRect(opaque.getWidth(), opaque.getHeight());
        WritableImage fxImg = toWritableImage(opaque);
        gc.drawImage(fxImg, disp[0], disp[1], disp[2], disp[3]);
    }

    /**
     * 将透明 BufferedImage 绘制到 ImageView（带棋盘格背景）。
     * 完全在 AWT/BufferedImage 层面合成：
     *   1. 创建白色底的 TYPE_INT_RGB 图像（避免默认黑色）
     *   2. 用 Graphics2D 画棋盘格
     *   3. 用 Graphics2D 的 SRC_OVER 合成透明图像
     *   4. 用 PixelFormat.getByteBgraInstance() 转 WritableImage（alpha=255 不透明）
     *   5. ImageView.setImage 显示不透明图像
     * ImageView 只接收不透明像素，绝无黑色。
     */
    private void drawTransparentImageWithChecker(ImageView view, BufferedImage img) {
        if (img == null) {
            view.setImage(null);
            return;
        }

        int w = img.getWidth();
        int h = img.getHeight();
        int cell = (int) CHECKER_CELL;

        // 1. 创建白色底 RGB 图像（TYPE_INT_RGB 默认是黑色，必须显式填充白色）
        BufferedImage composited = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2d = composited.createGraphics();
        g2d.setColor(java.awt.Color.WHITE);
        g2d.fillRect(0, 0, w, h);

        // 2. 画棋盘格
        for (int y = 0; y < h; y += cell) {
            for (int x = 0; x < w; x += cell) {
                g2d.setColor((((x / cell + y / cell) % 2) == 0) ? java.awt.Color.WHITE : new java.awt.Color(224, 224, 224));
                g2d.fillRect(x, y, cell, cell);
            }
        }

        // 3. 画透明图像（Graphics2D 自动 SRC_OVER alpha 合成）
        g2d.drawImage(img, 0, 0, null);
        g2d.dispose();

        // 4. 转 WritableImage 并设置到 ImageView
        WritableImage fxImg = toWritableImage(composited);
        view.setImage(fxImg);
    }

    /**
     * 将 BufferedImage 转为 WritableImage。
     * 用 PixelFormat.getByteBgraInstance() 字节方式写入，完全绕过 SwingFXUtils。
     * 所有像素 alpha=255（不透明），绝无透明渲染问题。
     */
    private static WritableImage toWritableImage(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] argb = new int[w * h];
        img.getRGB(0, 0, w, h, argb, 0, w);
        byte[] bgra = new byte[w * h * 4];
        for (int i = 0; i < w * h; i++) {
            int p = argb[i];
            bgra[i * 4] = (byte) (p & 0xFF);         // B
            bgra[i * 4 + 1] = (byte) ((p >> 8) & 0xFF);  // G
            bgra[i * 4 + 2] = (byte) ((p >> 16) & 0xFF); // R
            bgra[i * 4 + 3] = (byte) 0xFF;            // A (不透明)
        }
        WritableImage fxImg = new WritableImage(w, h);
        fxImg.getPixelWriter().setPixels(0, 0, w, h,
                PixelFormat.getByteBgraInstance(), bgra, 0, w * 4);
        return fxImg;
    }

    /**
     * 将可能带 alpha 的 BufferedImage 合成到不透明 RGB BufferedImage。
     * 用 java.awt.Graphics2D 的 SRC_OVER 合成（AWT 层面，不依赖 JavaFX）。
     */
    private static BufferedImage flattenToOpaque(BufferedImage src, int bgR, int bgG, int bgB) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2d = out.createGraphics();
        // 填充背景色
        g2d.setColor(new java.awt.Color(bgR, bgG, bgB));
        g2d.fillRect(0, 0, w, h);
        // 绘制源图（AWT Graphics2D 自动 alpha 合成）
        g2d.drawImage(src, 0, 0, null);
        g2d.dispose();
        return out;
    }

    /**
     * 计算 Canvas 中图片的显示矩形（居中缩放到 IMG_FIT）。
     */
    private double[] computeImageDisplayRect(int imgW, int imgH) {
        double ratio = (double) imgW / (double) imgH;
        double dispW, dispH;
        if (ratio >= 1) {
            dispW = IMG_FIT;
            dispH = IMG_FIT / ratio;
        } else {
            dispH = IMG_FIT;
            dispW = IMG_FIT * ratio;
        }
        double dispX = (PREVIEW_SIZE - dispW) / 2.0;
        double dispY = (PREVIEW_SIZE - dispH) / 2.0;
        return new double[]{dispX, dispY, dispW, dispH};
    }

    private void applyManualCrop() {
        int[] sel = cropOverlay.getSelection();
        if (sel == null || currentTransparentImage == null) {
            return;
        }
        int x = sel[0], y = sel[1], w = sel[2], h = sel[3];
        if (w <= 0 || h <= 0) return;
        x = clampInt(x, 0, currentImgW - 1);
        y = clampInt(y, 0, currentImgH - 1);
        w = Math.min(w, currentImgW - x);
        h = Math.min(h, currentImgH - y);
        if (w <= 0 || h <= 0) return;

        BufferedImage cropped = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        // 用 getRGB/setRGB 直接复制像素，避免 getSubimage 共享 raster 导致数据不完整
        int[] pixels = new int[w * h];
        currentTransparentImage.getRGB(x, y, w, h, pixels, 0, w);
        cropped.setRGB(0, 0, w, h, pixels, 0, w);

        currentTransparentImage = cropped;
        currentImgW = w;
        currentImgH = h;
        drawTransparentImageWithChecker(resultImageView, cropped);

        double[] disp = computeImageDisplayRect(w, h);
        cropOverlay.setImageRect(w, h, disp[0], disp[1], disp[2], disp[3]);

        resultInfoLabel.setText(String.format("透明后：%d × %d（已裁切）", w, h));
        cropInfoLabel.setText(String.format("已应用裁切：%d,%d  %d×%d", x, y, w, h));
        syncDistanceFields();
    }

    private void resetCrop() {
        if (currentImgW <= 0) return;
        cropOverlay.resetSelection();
        syncDistanceFields();
    }

    /**
     * 将当前选区同步到 4 个距离输入框与信息标签。
     */
    private void syncDistanceFields() {
        if (currentImgW <= 0 || currentImgH <= 0) return;
        int[] sel = cropOverlay.getSelection();
        if (sel == null) {
            cropInfoLabel.setText("拖动选区或修改距离值调整裁切范围");
            return;
        }
        int left = sel[0];
        int top = sel[1];
        int right = currentImgW - sel[0] - sel[2];
        int bottom = currentImgH - sel[1] - sel[3];

        isSyncingFields = true;
        topField.setText(String.valueOf(Math.max(0, top)));
        bottomField.setText(String.valueOf(Math.max(0, bottom)));
        leftField.setText(String.valueOf(Math.max(0, left)));
        rightField.setText(String.valueOf(Math.max(0, right)));
        isSyncingFields = false;

        boolean square = sel[2] == sel[3];
        String shape = square ? "正方形" : "矩形";
        cropInfoLabel.setText(String.format("选区：%d,%d  %d×%d（%s）",
                sel[0], sel[1], sel[2], sel[3], shape));
    }

    /**
     * 距离输入框编辑（Enter / 失焦）：按 4 个边距值重新设置选区。
     */
    private void onMarginFieldEdit() {
        if (isSyncingFields) return;
        if (currentImgW <= 0 || currentImgH <= 0) return;
        int top = parseMargin(topField.getText());
        int bottom = parseMargin(bottomField.getText());
        int left = parseMargin(leftField.getText());
        int right = parseMargin(rightField.getText());
        cropOverlay.setSelectionByMargins(left, top, right, bottom);
    }

    private static int parseMargin(String s) {
        if (s == null) return 0;
        String t = s.trim();
        if (t.isEmpty()) return 0;
        try {
            int v = Integer.parseInt(t);
            return Math.max(0, v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 保存：单文件保存到源文件同目录，目录模式批量处理（保留目录结构）。
     */
    private void startSave() {
        String sourcePath = sourcePathField.getText().trim();
        if (sourcePath.isEmpty()) {
            setStatus("请选择源文件或目录", true);
            return;
        }

        // 目录模式：重新扫描
        if (isDirMode && sourceFiles.isEmpty()) {
            File sourceDir = new File(sourcePath);
            try {
                Files.walk(sourceDir.toPath())
                        .filter(p -> isSupported(p.toString()))
                        .forEach(p -> sourceFiles.add(p.toFile()));
            } catch (IOException e) {
                // ignore
            }
        }

        if (sourceFiles.isEmpty()) {
            setStatus("未找到支持的图片文件", true);
            return;
        }

        final int threshold = (int) thresholdSlider.getValue();
        final boolean autoCrop = autoCropCheckBox.isSelected();
        // 单文件模式：保存预览当前显示的图（所见即所得）
        // 用 final 引用直接保存，避免复制过程中丢失像素数据
        final BufferedImage imgToSave = (!isDirMode) ? currentTransparentImage : null;
        final int total = sourceFiles.size();
        final String sourcePathFinal = sourcePath;

        convertButton.setDisable(true);
        setStatus("正在保存...", false, "#1976D2");

        new Thread(() -> {
            int successCount = 0;
            int failCount = 0;

            for (int i = 0; i < sourceFiles.size(); i++) {
                File srcFile = sourceFiles.get(i);
                File outFile;
                if (isDirMode) {
                    String baseName = srcFile.getName().replaceAll("(?i)\\.(jpg|jpeg|png)$", "");
                    outFile = new File(srcFile.getParentFile(), baseName + "_transparent.png");
                } else {
                    String baseName = srcFile.getName().replaceAll("(?i)\\.(jpg|jpeg|png)$", "");
                    outFile = new File(srcFile.getParentFile(), baseName + "_transparent.png");
                }
                try {
                    if (!isDirMode && imgToSave != null) {
                        // 单文件模式：直接保存预览图（所见即所得）
                        savePng(imgToSave, outFile);
                    } else {
                        // 目录模式：重新处理 + autoCrop
                        removeBackground(srcFile, outFile, threshold, autoCrop);
                    }
                    successCount++;
                } catch (Exception e) {
                    failCount++;
                }
            }

            int s = successCount;
            int f = failCount;
            Platform.runLater(() -> {
                convertButton.setDisable(false);
                if (f > 0) {
                    setStatus(String.format("保存完成，有失败项！总计: %d, 成功: %d, 失败: %d", total, s, f), true);
                } else {
                    setStatus(String.format("保存完成！总计: %d, 成功: %d（已保存到源文件同目录，文件名加 _transparent）", total, s), false, "#388E3C");
                }
            });
        }).start();
    }

    private void setStatus(String text, boolean error) {
        setStatus(text, error, error ? "#e53935" : "#666");
    }

    private void setStatus(String text, boolean error, String color) {
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: " + color + ";");
        statusLabel.setText(text);
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * 亮度阈值法去背景。
     * 透明像素的 RGB 设为白色（0xFFFFFF），避免某些渲染器把透明区域显示为黑色。
     */
    private BufferedImage processImage(BufferedImage src, int threshold, boolean crop) {
        int w = src.getWidth();
        int h = src.getHeight();

        // 先把源图合成到白色背景的 ARGB BufferedImage，确保所有像素 alpha=255 且 RGB 正确
        BufferedImage normalized = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D normG = normalized.createGraphics();
        normG.setColor(java.awt.Color.WHITE);
        normG.fillRect(0, 0, w, h);
        normG.drawImage(src, 0, 0, null);
        normG.dispose();

        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int lowBound = threshold - 40;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = normalized.getRGB(x, y);
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                int outArgb;
                if (lum >= threshold) {
                    // 透明像素：alpha=0，RGB 设为白色（避免黑色背景）
                    outArgb = 0x00FFFFFF;
                } else if (lum > lowBound) {
                    float af = 1f - (float) (lum - lowBound) / 40f;
                    int alpha = Math.max(0, Math.min(255, (int) (af * 255)));
                    outArgb = (alpha << 24) | (r << 16) | (g << 8) | b;
                } else {
                    outArgb = (0xFF << 24) | (r << 16) | (g << 8) | b;
                }
                dst.setRGB(x, y, outArgb);
            }
        }

        if (crop) {
            BufferedImage cropped = cropTransparentBorder(dst);
            if (cropped != null) {
                return cropped;
            }
        }
        return dst;
    }

    /**
     * 目录模式：重新处理源文件并保存。
     */
    private void removeBackground(File srcFile, File outFile, int threshold, boolean autoCrop) throws Exception {
        BufferedImage src = ImageIO.read(srcFile);
        if (src == null) {
            throw new IOException("无法读取图片: " + srcFile.getName());
        }
        BufferedImage dst = processImage(src, threshold, autoCrop);
        savePng(dst, outFile);
    }

    /**
     * 保存 BufferedImage 为 PNG。
     * 用 ImageWriter 逐行写入，避免 ImageIO.write 对大图批量编码的不完整问题。
     */
    private void savePng(BufferedImage img, File outFile) throws Exception {
        // 确保图像数据独立完整（复制一份，避免共享 raster）
        int w = img.getWidth();
        int h = img.getHeight();
        BufferedImage safeImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, pixels, 0, w);
            safeImg.setRGB(0, y, w, 1, pixels, 0, w);
        }

        java.util.Iterator<javax.imageio.ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) {
            throw new IOException("找不到 PNG Writer");
        }
        javax.imageio.ImageWriter writer = writers.next();
        javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
        javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(outFile);
        writer.setOutput(ios);
        writer.write(null, new javax.imageio.IIOImage(safeImg, null, null), param);
        ios.flush();
        ios.close();
        writer.dispose();
    }

    /**
     * 复制 BufferedImage（用 getRGB/setRGB 直接复制像素，避免子图共享 raster 问题）。
     */
    private static BufferedImage copyBufferedImage(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage copy = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[w * h];
        src.getRGB(0, 0, w, h, pixels, 0, w);
        copy.setRGB(0, 0, w, h, pixels, 0, w);
        return copy;
    }

    /**
     * 裁切周边透明区域。
     */
    private BufferedImage cropTransparentBorder(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int minX = w;
        int minY = h;
        int maxX = -1;
        int maxY = -1;
        final int alphaThreshold = 8;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int alpha = (img.getRGB(x, y) >>> 24) & 0xFF;
                if (alpha > alphaThreshold) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) {
            return null;
        }
        int newW = maxX - minX + 1;
        int newH = maxY - minY + 1;
        // 用 getRGB/setRGB 直接复制像素，避免 getSubimage 共享 raster 导致数据不完整
        BufferedImage result = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[newW * newH];
        img.getRGB(minX, minY, newW, newH, pixels, 0, newW);
        result.setRGB(0, 0, newW, newH, pixels, 0, newW);
        return result;
    }

    // ===================== 裁切选区覆盖层 =====================
    /**
     * 在预览 Canvas 上叠加可拖拽选区。
     * 选区坐标存储为图片像素坐标（int），渲染时按显示矩形换算为屏幕坐标。
     * 8 个手柄：TL, T, TR, R, BR, B, BL, L；选区内部拖动可整体移动。
     */
    private static class CropOverlay extends Pane {
        private static final double HANDLE_SIZE = 9;
        private static final int MIN_SELECT = 4;

        private final Rectangle maskTop = new Rectangle();
        private final Rectangle maskBottom = new Rectangle();
        private final Rectangle maskLeft = new Rectangle();
        private final Rectangle maskRight = new Rectangle();
        private final Rectangle selection = new Rectangle();
        private final Rectangle[] handles = new Rectangle[8];

        private double dispX, dispY, dispW, dispH;
        private int imgW, imgH;
        private int selX, selY, selW, selH;

        private int dragHandle = -1;
        private double dragStartX, dragStartY;
        private int dragStartSelX, dragStartSelY, dragStartSelW, dragStartSelH;

        private boolean squareLocked = true;
        private Runnable onSelectionChanged;

        CropOverlay() {
            setMouseTransparent(false);
            setPickOnBounds(false);
            setBackground(Background.EMPTY);
            setStyle("-fx-background-color: transparent;");
            setPrefSize(PREVIEW_SIZE, PREVIEW_SIZE);
            setMaxSize(PREVIEW_SIZE, PREVIEW_SIZE);
            setMinSize(PREVIEW_SIZE, PREVIEW_SIZE);

            for (int i = 0; i < 8; i++) {
                handles[i] = new Rectangle(HANDLE_SIZE, HANDLE_SIZE);
                handles[i].setFill(Color.WHITE);
                handles[i].setStroke(Color.web("#1976D2"));
                handles[i].setStrokeWidth(1.5);
                handles[i].setPickOnBounds(true);
            }
            selection.setFill(null);
            selection.setStroke(Color.web("#1976D2"));
            selection.setStrokeWidth(2);
            selection.getStrokeDashArray().addAll(4.0, 4.0);
            selection.setPickOnBounds(true);

            // mask 矩形设为完全透明，不遮挡下层 Canvas 内容
            for (Rectangle r : new Rectangle[]{maskTop, maskBottom, maskLeft, maskRight}) {
                r.setFill(Color.TRANSPARENT);
                r.setMouseTransparent(true);
                r.setPickOnBounds(false);
            }

            getChildren().addAll(maskTop, maskBottom, maskLeft, maskRight, selection);
            for (Rectangle h : handles) getChildren().add(h);

            setupHandlers();
            setAllInvisible();
        }

        void setImageRect(int imgW, int imgH, double dispX, double dispY, double dispW, double dispH) {
            this.imgW = imgW;
            this.imgH = imgH;
            this.dispX = dispX;
            this.dispY = dispY;
            this.dispW = dispW;
            this.dispH = dispH;
            resetSelection();
        }

        void setSquareLocked(boolean locked) {
            this.squareLocked = locked;
            resetSelection();
        }

        void setOnSelectionChanged(Runnable cb) {
            this.onSelectionChanged = cb;
        }

        void setSelectionByMargins(int left, int top, int right, int bottom) {
            if (imgW <= 0 || imgH <= 0) return;
            left = clampInt(left, 0, imgW - MIN_SELECT);
            right = clampInt(right, 0, imgW - MIN_SELECT - left);
            top = clampInt(top, 0, imgH - MIN_SELECT);
            bottom = clampInt(bottom, 0, imgH - MIN_SELECT - top);

            int availW = imgW - left - right;
            int availH = imgH - top - bottom;
            if (availW < MIN_SELECT) availW = MIN_SELECT;
            if (availH < MIN_SELECT) availH = MIN_SELECT;

            if (squareLocked) {
                int size = Math.min(availW, availH);
                int offX = (availW - size) / 2;
                int offY = (availH - size) / 2;
                selX = left + offX;
                selY = top + offY;
                selW = size;
                selH = size;
            } else {
                selX = left;
                selY = top;
                selW = availW;
                selH = availH;
            }
            selX = clampInt(selX, 0, imgW - selW);
            selY = clampInt(selY, 0, imgH - selH);
            layoutOverlay();
        }

        void clearImage() {
            imgW = 0;
            imgH = 0;
            setAllInvisible();
        }

        void resetSelection() {
            if (imgW > 0 && imgH > 0) {
                if (squareLocked) {
                    int size = Math.min(imgW, imgH);
                    selW = size;
                    selH = size;
                    selX = (imgW - size) / 2;
                    selY = (imgH - size) / 2;
                } else {
                    selX = 0;
                    selY = 0;
                    selW = imgW;
                    selH = imgH;
                }
            }
            layoutOverlay();
        }

        int[] getSelection() {
            if (imgW <= 0 || selW <= 0 || selH <= 0) return null;
            return new int[]{selX, selY, selW, selH};
        }

        private void setupHandlers() {
            selection.setOnMousePressed(e -> {
                if (imgW <= 0) return;
                dragHandle = 8;
                dragStartX = e.getSceneX();
                dragStartY = e.getSceneY();
                dragStartSelX = selX;
                dragStartSelY = selY;
                e.consume();
            });
            selection.setOnMouseDragged(e -> {
                if (dragHandle == 8 && dispW > 0 && dispH > 0) {
                    double dx = (e.getSceneX() - dragStartX) * imgW / dispW;
                    double dy = (e.getSceneY() - dragStartY) * imgH / dispH;
                    selX = clampInt((int) Math.round(dragStartSelX + dx), 0, imgW - selW);
                    selY = clampInt((int) Math.round(dragStartSelY + dy), 0, imgH - selH);
                    layoutOverlay();
                }
                e.consume();
            });
            selection.setOnMouseReleased(e -> {
                dragHandle = -1;
                e.consume();
            });

            for (int i = 0; i < 8; i++) {
                final int idx = i;
                Rectangle h = handles[i];
                h.setOnMousePressed(e -> {
                    if (imgW <= 0) return;
                    dragHandle = idx;
                    dragStartX = e.getSceneX();
                    dragStartY = e.getSceneY();
                    dragStartSelX = selX;
                    dragStartSelY = selY;
                    dragStartSelW = selW;
                    dragStartSelH = selH;
                    e.consume();
                });
                h.setOnMouseDragged(e -> {
                    if (dragHandle == idx && dispW > 0 && dispH > 0) {
                        double dx = (e.getSceneX() - dragStartX) * imgW / dispW;
                        double dy = (e.getSceneY() - dragStartY) * imgH / dispH;
                        applyResize(idx, (int) Math.round(dx), (int) Math.round(dy));
                        layoutOverlay();
                    }
                    e.consume();
                });
                h.setOnMouseReleased(e -> {
                    dragHandle = -1;
                    e.consume();
                });
            }
        }

        private void applyResize(int handle, int dx, int dy) {
            if (squareLocked) {
                applySquareResize(handle, dx, dy);
                return;
            }
            int newX = dragStartSelX, newY = dragStartSelY;
            int newW = dragStartSelW, newH = dragStartSelH;
            switch (handle) {
                case 0:
                    newX = clampInt(dragStartSelX + dx, 0, dragStartSelX + dragStartSelW - MIN_SELECT);
                    newY = clampInt(dragStartSelY + dy, 0, dragStartSelY + dragStartSelH - MIN_SELECT);
                    newW = dragStartSelX + dragStartSelW - newX;
                    newH = dragStartSelY + dragStartSelH - newY;
                    break;
                case 1:
                    newY = clampInt(dragStartSelY + dy, 0, dragStartSelY + dragStartSelH - MIN_SELECT);
                    newH = dragStartSelY + dragStartSelH - newY;
                    break;
                case 2:
                    newY = clampInt(dragStartSelY + dy, 0, dragStartSelY + dragStartSelH - MIN_SELECT);
                    newW = clampInt(dragStartSelW + dx, MIN_SELECT, imgW - dragStartSelX);
                    newH = dragStartSelY + dragStartSelH - newY;
                    break;
                case 3:
                    newW = clampInt(dragStartSelW + dx, MIN_SELECT, imgW - dragStartSelX);
                    break;
                case 4:
                    newW = clampInt(dragStartSelW + dx, MIN_SELECT, imgW - dragStartSelX);
                    newH = clampInt(dragStartSelH + dy, MIN_SELECT, imgH - dragStartSelY);
                    break;
                case 5:
                    newH = clampInt(dragStartSelH + dy, MIN_SELECT, imgH - dragStartSelY);
                    break;
                case 6:
                    newX = clampInt(dragStartSelX + dx, 0, dragStartSelX + dragStartSelW - MIN_SELECT);
                    newW = dragStartSelX + dragStartSelW - newX;
                    newH = clampInt(dragStartSelH + dy, MIN_SELECT, imgH - dragStartSelY);
                    break;
                case 7:
                    newX = clampInt(dragStartSelX + dx, 0, dragStartSelX + dragStartSelW - MIN_SELECT);
                    newW = dragStartSelX + dragStartSelW - newX;
                    break;
            }
            selX = newX;
            selY = newY;
            selW = newW;
            selH = newH;
        }

        private void applySquareResize(int handle, int dx, int dy) {
            double anchorX, anchorY;
            double handleStartX, handleStartY;
            switch (handle) {
                case 0:
                    handleStartX = dragStartSelX; handleStartY = dragStartSelY;
                    anchorX = dragStartSelX + dragStartSelW; anchorY = dragStartSelY + dragStartSelH;
                    break;
                case 1:
                    handleStartX = dragStartSelX + dragStartSelW / 2.0; handleStartY = dragStartSelY;
                    anchorX = dragStartSelX + dragStartSelW / 2.0; anchorY = dragStartSelY + dragStartSelH;
                    break;
                case 2:
                    handleStartX = dragStartSelX + dragStartSelW; handleStartY = dragStartSelY;
                    anchorX = dragStartSelX; anchorY = dragStartSelY + dragStartSelH;
                    break;
                case 3:
                    handleStartX = dragStartSelX + dragStartSelW; handleStartY = dragStartSelY + dragStartSelH / 2.0;
                    anchorX = dragStartSelX; anchorY = dragStartSelY + dragStartSelH / 2.0;
                    break;
                case 4:
                    handleStartX = dragStartSelX + dragStartSelW; handleStartY = dragStartSelY + dragStartSelH;
                    anchorX = dragStartSelX; anchorY = dragStartSelY;
                    break;
                case 5:
                    handleStartX = dragStartSelX + dragStartSelW / 2.0; handleStartY = dragStartSelY + dragStartSelH;
                    anchorX = dragStartSelX + dragStartSelW / 2.0; anchorY = dragStartSelY;
                    break;
                case 6:
                    handleStartX = dragStartSelX; handleStartY = dragStartSelY + dragStartSelH;
                    anchorX = dragStartSelX + dragStartSelW; anchorY = dragStartSelY;
                    break;
                case 7:
                    handleStartX = dragStartSelX; handleStartY = dragStartSelY + dragStartSelH / 2.0;
                    anchorX = dragStartSelX + dragStartSelW; anchorY = dragStartSelY + dragStartSelH / 2.0;
                    break;
                default:
                    return;
            }
            double mouseCurX = handleStartX + dx;
            double mouseCurY = handleStartY + dy;
            double distX = Math.abs(mouseCurX - anchorX);
            double distY = Math.abs(mouseCurY - anchorY);
            int maxSize = Math.min(imgW, imgH);
            int newSize = clampInt((int) Math.round(Math.max(distX, distY)), MIN_SELECT, maxSize);

            switch (handle) {
                case 0:
                    selX = (int) Math.round(anchorX - newSize);
                    selY = (int) Math.round(anchorY - newSize);
                    break;
                case 1:
                    selX = (int) Math.round(anchorX - newSize / 2.0);
                    selY = (int) Math.round(anchorY - newSize);
                    break;
                case 2:
                    selX = (int) Math.round(anchorX);
                    selY = (int) Math.round(anchorY - newSize);
                    break;
                case 3:
                    selX = (int) Math.round(anchorX);
                    selY = (int) Math.round(anchorY - newSize / 2.0);
                    break;
                case 4:
                    selX = (int) Math.round(anchorX);
                    selY = (int) Math.round(anchorY);
                    break;
                case 5:
                    selX = (int) Math.round(anchorX - newSize / 2.0);
                    selY = (int) Math.round(anchorY);
                    break;
                case 6:
                    selX = (int) Math.round(anchorX - newSize);
                    selY = (int) Math.round(anchorY);
                    break;
                case 7:
                    selX = (int) Math.round(anchorX - newSize);
                    selY = (int) Math.round(anchorY - newSize / 2.0);
                    break;
            }
            selW = newSize;
            selH = newSize;
            selX = clampInt(selX, 0, imgW - selW);
            selY = clampInt(selY, 0, imgH - selH);
        }

        private void layoutOverlay() {
            if (imgW <= 0 || imgH <= 0 || dispW <= 0 || dispH <= 0) {
                setAllInvisible();
                return;
            }
            double sx = dispX + selX * dispW / imgW;
            double sy = dispY + selY * dispH / imgH;
            double sw = selW * dispW / imgW;
            double sh = selH * dispH / imgH;

            selection.setX(sx);
            selection.setY(sy);
            selection.setWidth(sw);
            selection.setHeight(sh);
            selection.setVisible(true);

            double paneW = getWidth();
            double paneH = getHeight();
            if (paneW <= 0) paneW = PREVIEW_SIZE;
            if (paneH <= 0) paneH = PREVIEW_SIZE;
            maskTop.setX(0); maskTop.setY(0); maskTop.setWidth(paneW); maskTop.setHeight(sy);
            maskBottom.setX(0); maskBottom.setY(sy + sh); maskBottom.setWidth(paneW); maskBottom.setHeight(paneH - sy - sh);
            maskLeft.setX(0); maskLeft.setY(sy); maskLeft.setWidth(sx); maskLeft.setHeight(sh);
            maskRight.setX(sx + sw); maskRight.setY(sy); maskRight.setWidth(paneW - sx - sw); maskRight.setHeight(sh);
            for (Rectangle r : new Rectangle[]{maskTop, maskBottom, maskLeft, maskRight}) r.setVisible(true);

            double hs = HANDLE_SIZE / 2;
            handles[0].setX(sx - hs); handles[0].setY(sy - hs);
            handles[1].setX(sx + sw / 2 - hs); handles[1].setY(sy - hs);
            handles[2].setX(sx + sw - hs); handles[2].setY(sy - hs);
            handles[3].setX(sx + sw - hs); handles[3].setY(sy + sh / 2 - hs);
            handles[4].setX(sx + sw - hs); handles[4].setY(sy + sh - hs);
            handles[5].setX(sx + sw / 2 - hs); handles[5].setY(sy + sh - hs);
            handles[6].setX(sx - hs); handles[6].setY(sy + sh - hs);
            handles[7].setX(sx - hs); handles[7].setY(sy + sh / 2 - hs);
            for (Rectangle h : handles) h.setVisible(true);

            if (onSelectionChanged != null) {
                Platform.runLater(onSelectionChanged);
            }
        }

        private void setAllInvisible() {
            selection.setVisible(false);
            for (Rectangle r : new Rectangle[]{maskTop, maskBottom, maskLeft, maskRight}) r.setVisible(false);
            for (Rectangle h : handles) h.setVisible(false);
        }

        @Override
        protected void layoutChildren() {
            super.layoutChildren();
            layoutOverlay();
        }
    }
}
