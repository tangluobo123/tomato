package com.tangluobo.tomato.module.server;

import com.tangluobo.tomato.module.Module;
import com.tangluobo.tomato.module.tools.server.ServerType;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

/**
 * 服务器模块
 * 左侧 sidebar 显示三个服务类型选项：HTTP / FTP / SMB
 * 右侧 content 为对应的服务器管理面板
 */
public class ServerModule implements Module {

    private VBox contentArea;
    private HBox currentSelectedBox = null;

    // 三种服务类型对应的侧边栏列表项
    private static class ServerItem {
        final ServerType type;
        final String description;

        ServerItem(ServerType type, String description) {
            this.type = type;
            this.description = description;
        }
    }

    private final ServerItem[] serverItems = {
            new ServerItem(ServerType.HTTP, "HTTP 文件服务器，支持浏览器访问"),
            new ServerItem(ServerType.FTP, "FTP 文件服务器，支持 FTP 客户端访问"),
            new ServerItem(ServerType.SMB, "SMB 文件共享服务器，局域网文件共享"),
    };

    @Override
    public String getName() {
        return "服务器";
    }

    @Override
    public void loadSidebar(VBox sidebarContainer) {
        sidebarContainer.setStyle("-fx-background-color: #ffffff; -fx-border-color: #e5e5e5; -fx-border-width: 0 1 0 0;");

        // 搜索栏（标题栏）
        HBox searchBar = new HBox(8);
        searchBar.setAlignment(Pos.CENTER_LEFT);
        searchBar.setPrefHeight(52);
        searchBar.setMinHeight(52);
        searchBar.setMaxHeight(52);
        searchBar.setPadding(new Insets(10, 15, 10, 15));
        searchBar.setStyle("-fx-background-color: #ffffff; -fx-border-color: #D9D9D7; -fx-border-width: 0 0 1 0;");

        SVGPath titleIcon = new SVGPath();
        titleIcon.setContent("M20 13H4c-1.1 0-2 .9-2 2v4c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2v-4c0-1.1-.9-2-2-2zM7 19c-.83 0-1.5-.67-1.5-1.5S6.17 16 7 16s1.5.67 1.5 1.5S7.83 19 7 19zm13-11H4c-1.1 0-2 .9-2 2v4c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2v-4c0-1.1-.9-2-2-2zM7 13c-.83 0-1.5-.67-1.5-1.5S6.17 10 7 10s1.5.67 1.5 1.5S7.83 13 7 13zM20 1H4c-1.1 0-2 .9-2 2v4c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V3c0-1.1-.9-2-2-2zM7 7c-.83 0-1.5-.67-1.5-1.5S6.17 4 7 4s1.5.67 1.5 1.5S7.83 7 7 7z");
        titleIcon.setFill(Color.web("#1976D2"));
        titleIcon.setScaleX(0.6);
        titleIcon.setScaleY(0.6);

        Label titleLabel = new Label("服务类型");
        titleLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #333;");

        searchBar.getChildren().addAll(titleIcon, titleLabel);

        // 服务类型列表
        VBox serverList = new VBox(0);
        serverList.setPadding(new Insets(0));
        serverList.setStyle("-fx-background-color: #ffffff;");

        for (ServerItem item : serverItems) {
            VBox itemBox = createServerItemBox(item);
            serverList.getChildren().add(itemBox);
        }

        ScrollPane scrollPane = new ScrollPane(serverList);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-border-width: 0; -fx-padding: 0; -fx-background-insets: 0; -fx-border-insets: 0;");
        scrollPane.getStyleClass().add("session-scroll-pane");
        scrollPane.getStylesheets().add(getClass().getResource("/css/connect-tree.css").toExternalForm());
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        sidebarContainer.getChildren().addAll(searchBar, scrollPane);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
    }

    @Override
    public void loadContent(VBox contentArea) {
        this.contentArea = contentArea;
        contentArea.getChildren().clear();

        // 默认显示欢迎界面
        VBox welcomeBox = new VBox(20);
        welcomeBox.setAlignment(Pos.CENTER);
        welcomeBox.setPadding(new Insets(40));

        Label welcomeLabel = new Label("选择一个服务类型开始使用");
        welcomeLabel.setStyle("-fx-font-size: 18px; -fx-text-fill: #999;");

        Label hintLabel = new Label("从左侧选择 HTTP / FTP / SMB 服务类型");
        hintLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #bbb;");

        welcomeBox.getChildren().addAll(welcomeLabel, hintLabel);
        contentArea.getChildren().add(welcomeBox);
        VBox.setVgrow(welcomeBox, Priority.ALWAYS);
    }

    private VBox createServerItemBox(ServerItem item) {
        VBox itemBox = new VBox(0);
        itemBox.setPadding(new Insets(0));

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

        ImageView icon = new ImageView();
        icon.setFitWidth(24);
        icon.setFitHeight(24);
        try {
            Image img = new Image(getClass().getResourceAsStream(item.type.getIconPath()));
            icon.setImage(img);
        } catch (Exception ignored) {}
        iconContainer.getChildren().add(icon);

        // 右侧文字
        VBox textContainer = new VBox(2);
        Label nameLabel = new Label(item.type.getDisplayName());
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

        row.setOnMouseClicked(e -> handleServerClick(item, row));

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

    private void handleServerClick(ServerItem item, HBox row) {
        // 清除之前选中状态
        if (currentSelectedBox != null) {
            currentSelectedBox.setStyle("-fx-background-color: transparent; -fx-cursor: hand; -fx-background-radius: 0;");
        }
        currentSelectedBox = row;
        row.setStyle("-fx-background-color: #e8f4ff; -fx-cursor: hand; -fx-background-radius: 0;");

        if (contentArea == null) return;

        contentArea.getChildren().clear();
        contentArea.setFillWidth(true);
        contentArea.setMaxWidth(Double.MAX_VALUE);
        contentArea.setMaxHeight(Double.MAX_VALUE);

        // 创建对应类型的服务器管理面板
        com.tangluobo.tomato.module.tools.ServerManagerPane pane =
                new com.tangluobo.tomato.module.tools.ServerManagerPane(item.type);
        contentArea.getChildren().add(pane);
        VBox.setVgrow(pane, Priority.ALWAYS);
    }
}
