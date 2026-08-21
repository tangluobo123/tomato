package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.ConnectModule;
import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.SFTPFileBrowserPane;
import com.tangluobo.tomato.module.connect.dialog.PasswordPromptDialog;
import com.tangluobo.tomato.module.connect.dialog.SessionConfigDialog;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

/**
 * SFTP 文件浏览器连接处理器
 * 建立SSH会话后通过SFTP通道浏览远程文件系统，展示样式参考S3
 */
public class SFTPConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.SFTP;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        // 已打开则切换到对应 Tab
        for (Tab tab : module.getTerminalTabPane().getTabs()) {
            if (config.getId().equals(tab.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(tab);
                module.showTerminalView();
                return;
            }
        }

        // 密码认证时若未保存密码，弹窗输入
        if (config.isUsePassword() && (config.getPassword() == null || config.getPassword().isEmpty())) {
            PasswordPromptDialog.Result pwdResult = PasswordPromptDialog.show(
                    "输入密码",
                    config.getName() + " (" + config.getUsername() + "@" + config.getHost() + ")",
                    "密码：", null, "保存密码");
            if (pwdResult == null || pwdResult.getPassword() == null || pwdResult.getPassword().isEmpty()) return;
            config.setPassword(pwdResult.getPassword());
            if (pwdResult.isSavePassword()) {
                config.setSavePassword(true);
                module.saveConnections();
            }
        }

        SFTPFileBrowserPane fileBrowserPane = new SFTPFileBrowserPane(config);

        Tab tab = new Tab(config.getName());
        tab.setContent(fileBrowserPane);
        tab.setUserData(config.getId());

        // Tab 图标
        try {
            Image tabIcon = new Image(getClass().getResourceAsStream(config.getType().getIconPath()));
            if (tabIcon != null) {
                ImageView tabIconView = new ImageView(tabIcon);
                tabIconView.setFitWidth(16);
                tabIconView.setFitHeight(16);
                tab.setGraphic(ConnectModule.createFixedSizeGraphic(tabIconView));
            }
        } catch (Exception e) {}

        ContextMenu tabContextMenu = new ContextMenu();
        MenuItem refreshItem = new MenuItem("刷新");
        refreshItem.setOnAction(e -> fileBrowserPane.refresh());
        MenuItem sessionConfigItem = new MenuItem("会话配置");
        sessionConfigItem.setOnAction(e -> {
            Stage stage = module.getStage();
            SessionConfigDialog.show(stage, config);
            module.saveConnections();
        });
        tabContextMenu.getItems().addAll(refreshItem, new SeparatorMenuItem(), sessionConfigItem);
        tab.setContextMenu(tabContextMenu);

        // 关闭 Tab 时释放 SFTP/SSH 连接
        tab.setOnClosed(e -> {
            fileBrowserPane.disconnect();
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showTerminalView();
    }
}
