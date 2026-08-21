package com.tangluobo.tomato.module.connect.dialog;

import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

public class ConnectTypeDialog {
    private Stage dialogStage;
    private ConnectType selectedType;
    private boolean confirmed = false;

    public ConnectTypeDialog(Stage parent) {
        dialogStage = new Stage();
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.initOwner(parent);
        dialogStage.setTitle("选择连接类型");
        dialogStage.setResizable(false);

        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setMinWidth(420);

        Label title = new Label("选择连接类型");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        // 方块网格布局
        FlowPane tilePane = new FlowPane();
        tilePane.setHgap(10);
        tilePane.setVgap(10);
        tilePane.setPadding(new Insets(5, 0, 5, 0));
        tilePane.setAlignment(Pos.CENTER);

        for (ConnectType type : ConnectType.values()) {
            VBox tile = new VBox(8);
            tile.setAlignment(Pos.CENTER);
            tile.setPadding(new Insets(14, 18, 14, 18));
            tile.setPrefWidth(120);
            tile.setPrefHeight(90);
            tile.setStyle("-fx-background-color: #f5f5f5; -fx-background-radius: 8; -fx-border-color: #e0e0e0; -fx-border-radius: 8; -fx-cursor: hand;");

            // 图标
            ImageView icon = new ImageView();
            icon.setFitWidth(32);
            icon.setFitHeight(32);
            try {
                Image img = new Image(getClass().getResourceAsStream(type.getIconPath()));
                icon.setImage(img);
            } catch (Exception ignored) {}

            // 名称
            Label nameLabel = new Label(type.getDisplayName());
            nameLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #333; -fx-font-weight: bold;");

            tile.getChildren().addAll(icon, nameLabel);

            // 悬停效果
            tile.setOnMouseEntered(e ->
                tile.setStyle("-fx-background-color: #e8f5e9; -fx-background-radius: 8; -fx-border-color: #07c160; -fx-border-radius: 8; -fx-cursor: hand; -fx-border-width: 2;")
            );
            tile.setOnMouseExited(e ->
                tile.setStyle("-fx-background-color: #f5f5f5; -fx-background-radius: 8; -fx-border-color: #e0e0e0; -fx-border-radius: 8; -fx-cursor: hand;")
            );
            tile.setOnMousePressed(e ->
                tile.setStyle("-fx-background-color: #c8e6c9; -fx-background-radius: 8; -fx-border-color: #07c160; -fx-border-radius: 8; -fx-cursor: hand; -fx-border-width: 2;")
            );
            tile.setOnMouseReleased(e ->
                tile.setStyle("-fx-background-color: #e8f5e9; -fx-background-radius: 8; -fx-border-color: #07c160; -fx-border-radius: 8; -fx-cursor: hand; -fx-border-width: 2;")
            );

            // 点击选择
            tile.setOnMouseClicked(e -> {
                selectedType = type;
                confirmed = true;
                dialogStage.close();
            });

            tilePane.getChildren().add(tile);
        }

        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 80px;");
        cancelBtn.setOnAction(e -> dialogStage.close());

        buttons.getChildren().add(cancelBtn);
        root.getChildren().addAll(title, tilePane, buttons);

        Scene scene = new Scene(root);
        dialogStage.setScene(scene);
        DialogPositionUtil.centerOnOwner(dialogStage, parent);
    }

    public ConnectType showAndWait() {
        dialogStage.showAndWait();
        return confirmed ? selectedType : null;
    }
}
