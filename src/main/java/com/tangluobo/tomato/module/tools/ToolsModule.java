package com.tangluobo.tomato.module.tools;

import com.tangluobo.tomato.module.Module;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具模块
 * 左侧sidebar为类似微信消息列表的工具列表
 * 右侧content为对应的工具界面
 */
public class ToolsModule implements Module {

    // 工具列表项数据
    private static class ToolItem {
        String id;
        String name;
        String description;
        Node icon;

        ToolItem(String id, String name, String description, Node icon) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.icon = icon;
        }
    }

    private List<ToolItem> toolItems = new ArrayList<>();
    private HBox currentSelectedBox = null;

    // 保存loadContent传入的contentArea引用
    private VBox contentArea;

    @Override
    public String getName() {
        return "工具";
    }

    @Override
    public void loadSidebar(VBox sidebarContainer) {
        sidebarContainer.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e5e5e5; -fx-border-width: 0 1 0 0;");

        // 搜索栏
        HBox searchBar = new HBox(8);
        searchBar.setAlignment(Pos.CENTER_LEFT);
        searchBar.setPrefHeight(52);
        searchBar.setMinHeight(52);
        searchBar.setMaxHeight(52);
        searchBar.setPadding(new Insets(10, 15, 10, 15));
        searchBar.setStyle("-fx-background-color: #ffffff; -fx-border-color: #D9D9D7; -fx-border-width: 0 0 1 0;");

        SVGPath searchIcon = new SVGPath();
        searchIcon.setContent("M15.5 14h-.79l-.28-.27A6.471 6.471 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z");
        searchIcon.setFill(Color.web("#999999"));
        searchIcon.setScaleX(0.7);
        searchIcon.setScaleY(0.7);

        TextField searchField = new TextField();
        searchField.setPromptText("搜索工具...");
        searchField.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0; -fx-font-size: 12px; -fx-prompt-text-fill: #999; -fx-text-fill: #333;");

        searchBar.getChildren().addAll(searchIcon, searchField);

        // 工具列表
        VBox toolList = new VBox(0);
        toolList.setPadding(new Insets(0));
        toolList.setStyle("-fx-background-color: #ffffff;");

        // 初始化工具项
        initToolItems();

        for (ToolItem item : toolItems) {
            VBox itemBox = createToolItemBox(item);
            toolList.getChildren().add(itemBox);
        }

        ScrollPane scrollPane = new ScrollPane(toolList);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-border-width: 0; -fx-padding: 0; -fx-background-insets: 0; -fx-border-insets: 0;");
        // 应用 session-scroll-pane 样式类：清除 .viewport 默认 background-insets，消除列表项两侧空白
        scrollPane.getStyleClass().add("session-scroll-pane");
        scrollPane.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        // 搜索过滤
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            String keyword = newVal.trim().toLowerCase();
            toolList.getChildren().clear();
            for (ToolItem item : toolItems) {
                if (keyword.isEmpty() || item.name.toLowerCase().contains(keyword) || item.description.toLowerCase().contains(keyword)) {
                    VBox itemBox = createToolItemBox(item);
                    toolList.getChildren().add(itemBox);
                }
            }
        });

        sidebarContainer.getChildren().addAll(searchBar, scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
    }

    @Override
    public void loadContent(VBox contentArea) {
        // 保存引用，后续点击工具时使用
        this.contentArea = contentArea;
        contentArea.getChildren().clear();

        // 默认显示欢迎界面
        VBox welcomeBox = new VBox(20);
        welcomeBox.setAlignment(Pos.CENTER);
        welcomeBox.setPadding(new Insets(40));

        Label welcomeLabel = new Label("选择一个工具开始使用");
        welcomeLabel.setStyle("-fx-font-size: 18px; -fx-text-fill: #999;");

        Label hintLabel = new Label("从左侧工具列表中选择需要的功能");
        hintLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #bbb;");

        welcomeBox.getChildren().addAll(welcomeLabel, hintLabel);
        contentArea.getChildren().add(welcomeBox);
        VBox.setVgrow(welcomeBox, Priority.ALWAYS);
    }

    private void initToolItems() {
        toolItems.clear();

        // 图片格式置换工具
        Node imageConvertIcon = createImageConvertIcon();
        toolItems.add(new ToolItem("image_format_converter", "图片格式转换", "SVG转PNG等图片格式转换", imageConvertIcon));

        // 数据集格式转换工具
        Node datasetConvertIcon = createDatasetConvertIcon();
        toolItems.add(new ToolItem("dataset_converter", "数据集格式转换", "PASCAL VOC转COCO格式", datasetConvertIcon));

        // JSON处理工具
        Node jsonToolIcon = createJsonToolIcon();
        toolItems.add(new ToolItem("json_tool", "JSON处理工具", "格式化、压缩、编码转换", jsonToolIcon));

        // Linux桌面快捷方式创建工具
        Node shortcutIcon = createShortcutIcon();
        toolItems.add(new ToolItem("desktop_shortcut", "桌面快捷方式", "创建Linux .desktop快捷方式", shortcutIcon));

        // Hosts 文件管理工具
        Node hostsFileIcon = createHostsFileIcon();
        toolItems.add(new ToolItem("hosts_file", "Hosts文件管理", "管理和切换不同环境的Hosts配置", hostsFileIcon));

        // 图片背景透明化工具
        Node imageBgRemoverIcon = createImageBackgroundRemoverIcon();
        toolItems.add(new ToolItem("image_background_remover", "图片背景透明化", "JPG/PNG白灰背景转透明PNG", imageBgRemoverIcon));

        // Trae 会话管理工具
        Node traeSessionIcon = createTraeSessionIcon();
        toolItems.add(new ToolItem("trae_session", "Trae会话管理", "管理 Trae CN 多账号会话切换", traeSessionIcon));

        // 资源图标提取工具
        Node extractIcon = createExtractIcon();
        toolItems.add(new ToolItem("resource_extractor", "资源图标提取", "从EXE/DLL中提取嵌入资源", extractIcon));
    }

    private Node createImageConvertIcon() {
        // Material Icons: image
        SVGPath path = new SVGPath();
        path.setContent("M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z");
        path.setFill(Color.web("#1976D2"));
        path.setScaleX(0.9);
        path.setScaleY(0.9);
        return path;
    }

    private Node createDatasetConvertIcon() {
        // Material Icons: transform (from ironware project)
        SVGPath path = new SVGPath();
        path.setContent("M22 2v14H8V2h14zm0-2H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V2c0-1.1-.9-2-2-2zm-9 17v-2h2v-2h-2v-2h2V9h-2V7h2V5H13v14zm-7 0H4V5H2v14c0 1.1.9 2 2 2h14v-2H6z");
        path.setFill(Color.web("#1976D2"));
        path.setScaleX(0.9);
        path.setScaleY(0.9);
        return path;
    }

    private Node createJsonToolIcon() {
        // Material Icons: data_object
        SVGPath path = new SVGPath();
        path.setContent("M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm-1 7V3.5L18.5 9H13zM6 20V4h5v7h7v9H6zm2-6h8v2H8v-2zm0 4h5v2H8v-2z");
        path.setFill(Color.web("#1976D2"));
        path.setScaleX(0.9);
        path.setScaleY(0.9);
        return path;
    }

    private Node createShortcutIcon() {
        // Material Icons: shortcut (desktop shortcut icon)
        SVGPath path = new SVGPath();
        path.setContent("M20 6h-8l-2-2H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2zm0 12H4V8h16v10z");
        path.setFill(Color.web("#1976D2"));
        path.setScaleX(0.9);
        path.setScaleY(0.9);
        return path;
    }

    private Node createHostsFileIcon() {
        // Material Icons: dns (域名解析/Hosts 文件图标)
        SVGPath path = new SVGPath();
        path.setContent("M20 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zM9 17H7v-5h2v5zm4 0h-2V7h2v10zm4 0h-2v-7h2v7z");
        path.setFill(Color.web("#1976D2"));
        path.setScaleX(0.9);
        path.setScaleY(0.9);
        return path;
    }

    private Node createImageBackgroundRemoverIcon() {
        // Material Icons: layers (层叠，代表透明图层)
        SVGPath path = new SVGPath();
        path.setContent("M11.99 18.54l-7.37-5.73L3 14.07l9 7 9-7-1.63-1.27-7.38 5.74zM12 16l7.36-5.73L21 9l-9-7-9 7 1.63 1.27L12 16z");
        path.setFill(Color.web("#1976D2"));
        path.setScaleX(0.9);
        path.setScaleY(0.9);
        return path;
    }

    private Node createTraeSessionIcon() {
        // Material Icons: people (多账号会话管理)
        SVGPath path = new SVGPath();
        path.setContent("M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z");
        path.setFill(Color.web("#1976D2"));
        path.setScaleX(0.9);
        path.setScaleY(0.9);
        return path;
    }

    private Node createExtractIcon() {
        // Material Icons: extraction (资源提取图标)
        SVGPath path = new SVGPath();
        path.setContent("M21 9v10c0 1.1-.9 2-2 2H5c-1.1 0-2-.9-2-2V9c0-1.1.9-2 2-2h3.17L8 4.17 9.41 5.58 11.83 3.17 13.24 4.58 14.66 3.17 16 4.58 15.83 7H19c1.1 0 2 .9 2 2zm-1 0H4v10h16V9z");
        path.setFill(Color.web("#1976D2"));
        path.setScaleX(0.9);
        path.setScaleY(0.9);
        return path;
    }

    private VBox createToolItemBox(ToolItem item) {
        VBox itemBox = new VBox(0);
        itemBox.setPadding(new Insets(0));

        // 每个列表项是微信风格的横向布局：图标 + 名称/描述
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(10, 12, 10, 12));
        row.setStyle("-fx-background-color: transparent; -fx-cursor: hand; -fx-background-radius: 0;");

        // 左侧图标容器
        VBox iconContainer = new VBox();
        iconContainer.setAlignment(Pos.CENTER);
        iconContainer.setPrefSize(40, 40);
        iconContainer.setStyle("-fx-background-color: #e8f4ff; -fx-background-radius: 8;");
        iconContainer.setPadding(new Insets(6));

        if (item.icon instanceof Label labelIcon) {
            Label clonedIcon = new Label(labelIcon.getText());
            clonedIcon.setStyle(labelIcon.getStyle());
            iconContainer.getChildren().add(clonedIcon);
        } else if (item.icon instanceof SVGPath svgIcon) {
            SVGPath clonedIcon = new SVGPath();
            clonedIcon.setContent(svgIcon.getContent());
            clonedIcon.setFill(svgIcon.getFill());
            clonedIcon.setScaleX(svgIcon.getScaleX());
            clonedIcon.setScaleY(svgIcon.getScaleY());
            iconContainer.getChildren().add(clonedIcon);
        } else {
            iconContainer.getChildren().add(item.icon);
        }

        // 右侧文字
        VBox textContainer = new VBox(2);
        Label nameLabel = new Label(item.name);
        nameLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");

        Label descLabel = new Label(item.description);
        descLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888;");

        textContainer.getChildren().addAll(nameLabel, descLabel);

        row.getChildren().addAll(iconContainer, textContainer);

        // 底部分隔线
        Region separator = new Region();
        separator.setStyle("-fx-background-color: #f0f0f0; -fx-pref-height: 1px;");
        separator.setPrefHeight(1);

        itemBox.getChildren().addAll(row, separator);

        // 选中状态
        row.setOnMouseClicked(e -> {
            handleToolClick(item, row);
        });

        row.setOnMouseEntered(e -> {
            if (currentSelectedBox != row) {
                row.setStyle("-fx-background-color: #f5f5f5; -fx-cursor: hand; -fx-background-radius: 0;");
            }
        });

        row.setOnMouseExited(e -> {
            if (currentSelectedBox != row) {
                row.setStyle("-fx-background-color: transparent; -fx-cursor: hand; -fx-background-radius: 0;");
            }
        });

        return itemBox;
    }

    private void handleToolClick(ToolItem item, HBox row) {
        // 清除之前选中状态
        if (currentSelectedBox != null) {
            currentSelectedBox.setStyle("-fx-background-color: transparent; -fx-cursor: hand; -fx-background-radius: 0;");
        }
        // 设置当前选中
        currentSelectedBox = row;
        row.setStyle("-fx-background-color: #e8f4ff; -fx-cursor: hand; -fx-background-radius: 0;");

        // 使用保存的contentArea引用来更新内容
        if (contentArea == null) return;

        contentArea.getChildren().clear();
        contentArea.setFillWidth(true);
        contentArea.setMaxWidth(Double.MAX_VALUE);
        contentArea.setMaxHeight(Double.MAX_VALUE);

        switch (item.id) {
            case "image_format_converter":
                ImageFormatConverterPane imgPane = new ImageFormatConverterPane();
                contentArea.getChildren().add(imgPane);
                VBox.setVgrow(imgPane, Priority.ALWAYS);
                break;
            case "dataset_converter":
                DatasetConverterPane datasetPane = new DatasetConverterPane();
                contentArea.getChildren().add(datasetPane);
                VBox.setVgrow(datasetPane, Priority.ALWAYS);
                break;
            case "json_tool":
                JsonToolPane jsonPane = new JsonToolPane();
                contentArea.getChildren().add(jsonPane);
                VBox.setVgrow(jsonPane, Priority.ALWAYS);
                break;
            case "desktop_shortcut":
                DesktopShortcutPane shortcutPane = new DesktopShortcutPane();
                contentArea.getChildren().add(shortcutPane);
                VBox.setVgrow(shortcutPane, Priority.ALWAYS);
                break;
            case "hosts_file":
                HostsFilePane hostsFilePane = new HostsFilePane();
                contentArea.getChildren().add(hostsFilePane);
                VBox.setVgrow(hostsFilePane, Priority.ALWAYS);
                break;
            case "image_background_remover":
                ImageBackgroundRemoverPane bgRemoverPane = new ImageBackgroundRemoverPane();
                contentArea.getChildren().add(bgRemoverPane);
                VBox.setVgrow(bgRemoverPane, Priority.ALWAYS);
                break;
            case "trae_session":
                TraeSessionPane traeSessionPane = new TraeSessionPane();
                contentArea.getChildren().add(traeSessionPane);
                VBox.setVgrow(traeSessionPane, Priority.ALWAYS);
                break;
            case "resource_extractor":
                ResourceExtractorPane extractorPane = new ResourceExtractorPane();
                contentArea.getChildren().add(extractorPane);
                VBox.setVgrow(extractorPane, Priority.ALWAYS);
                break;
            default:
                VBox placeholderBox = new VBox();
                placeholderBox.setAlignment(Pos.CENTER);
                placeholderBox.setMaxWidth(Double.MAX_VALUE);
                placeholderBox.setMaxHeight(Double.MAX_VALUE);
                Label placeholder = new Label("工具开发中...");
                placeholder.setStyle("-fx-font-size: 16px; -fx-text-fill: #999;");
                placeholderBox.getChildren().add(placeholder);
                contentArea.getChildren().add(placeholderBox);
                VBox.setVgrow(placeholderBox, Priority.ALWAYS);
                break;
        }
    }
}
