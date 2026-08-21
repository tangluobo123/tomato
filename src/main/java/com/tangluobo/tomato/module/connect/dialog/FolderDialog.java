package com.tangluobo.tomato.module.connect.dialog;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.stage.Modality;
import javafx.stage.Stage;

public class FolderDialog {
    private Stage dialogStage;
    private TextField nameField;
    private String folderName;
    private boolean confirmed = false;

    public FolderDialog(Stage parent) {
        dialogStage = new Stage();
        dialogStage.initModality(Modality.WINDOW_MODAL);
        dialogStage.initOwner(parent);
        dialogStage.setTitle("新建目录");
        dialogStage.setResizable(false);

        VBox root = new VBox(15);
        root.setPadding(new Insets(20));
        root.setMinWidth(300);

        Label title = new Label("新建目录");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label nameLabel = new Label("目录名称：");
        nameField = new TextField();
        nameField.setPromptText("请输入目录名称");

        HBox buttons = new HBox(10);
        buttons.setAlignment(Pos.CENTER_RIGHT);

        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 80px;");
        cancelBtn.setOnAction(e -> dialogStage.close());

        Button okBtn = new Button("确定");
        okBtn.setStyle("-fx-background-color: #07c160; -fx-text-fill: white; -fx-border-radius: 4px; -fx-background-radius: 4px; -fx-pref-width: 80px;");
        okBtn.setOnAction(e -> {
            if (nameField.getText().trim().isEmpty()) {
                return;
            }
            folderName = nameField.getText().trim();
            confirmed = true;
            dialogStage.close();
        });

        buttons.getChildren().addAll(cancelBtn, okBtn);
        root.getChildren().addAll(title, nameLabel, nameField, buttons);

        Scene scene = new Scene(root);
        dialogStage.setScene(scene);
        DialogPositionUtil.centerOnOwner(dialogStage, parent);
    }

    public String showAndWait() {
        dialogStage.showAndWait();
        return confirmed ? folderName : null;
    }
}