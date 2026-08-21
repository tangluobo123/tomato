package com.tangluobo.tomato.module.connect.dialog;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.GridPane;

/**
 * 密码输入对话框：可选"保存密码"。
 * 用于连接未保存密码时临时输入密码的场景（如导入连接后首次使用）。
 */
public class PasswordPromptDialog {

    /**
     * 密码输入结果
     */
    public static class Result {
        private final String password;
        private final boolean savePassword;

        public Result(String password, boolean savePassword) {
            this.password = password;
            this.savePassword = savePassword;
        }

        public String getPassword() { return password; }
        public boolean isSavePassword() { return savePassword; }
    }

    /**
     * 显示密码输入对话框
     *
     * @param title         对话框标题
     * @param header        对话框头部文本
     * @param passwordLabel 密码标签文本（如"密码："或"Secret Key："）
     * @param promptText    密码框提示文本（可为null）
     * @param saveLabel     "保存密码"复选框文本（如"保存密码"或"保存密钥"）
     * @return 结果；用户取消返回null
     */
    public static Result show(String title, String header, String passwordLabel,
                              String promptText, String saveLabel) {
        Dialog<Result> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(header);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 10, 10, 10));

        PasswordField pf = new PasswordField();
        pf.setPrefWidth(250);
        if (promptText != null) {
            pf.setPromptText(promptText);
        }
        grid.add(new Label(passwordLabel), 0, 0);
        grid.add(pf, 1, 0);

        CheckBox savePwdCheck = new CheckBox(saveLabel);
        savePwdCheck.setStyle("-fx-font-size: 11px; -fx-text-fill: #666;");
        grid.add(savePwdCheck, 1, 1);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(btn -> btn == ButtonType.OK
                ? new Result(pf.getText(), savePwdCheck.isSelected())
                : null);

        return dialog.showAndWait().orElse(null);
    }
}
