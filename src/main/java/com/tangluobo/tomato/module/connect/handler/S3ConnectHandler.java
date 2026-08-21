package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.ConnectModule;
import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.S3FileBrowserPane;
import com.tangluobo.tomato.module.connect.dialog.PasswordPromptDialog;
import com.tangluobo.tomato.module.connect.dialog.SessionConfigDialog;
import com.tangluobo.tomato.module.connect.service.S3Service;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

/**
 * S3/OSS 对象存储连接处理器
 */
public class S3ConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.S3 || type == ConnectType.ALIYUN_OSS;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        for (Tab tab : module.getTerminalTabPane().getTabs()) {
            if (config.getId().equals(tab.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(tab);
                module.showTerminalView();
                return;
            }
        }

        if (config.getPassword() == null || config.getPassword().isEmpty()) {
            PasswordPromptDialog.Result pwdResult = PasswordPromptDialog.show(
                    "输入Secret Key",
                    config.getName() + " (" + config.getUsername() + "@" + (config.getEndpoint() != null ? config.getEndpoint() : config.getRegion()) + ")",
                    "Secret Key：", "Secret Key", "保存密钥");
            if (pwdResult == null || pwdResult.getPassword() == null || pwdResult.getPassword().isEmpty()) return;
            config.setPassword(pwdResult.getPassword());
            if (pwdResult.isSavePassword()) {
                config.setSavePassword(true);
                module.saveConnections();
            }
        }

        S3FileBrowserPane fileBrowserPane = new S3FileBrowserPane(config);

        Tab tab = new Tab(config.getName());
        tab.setContent(fileBrowserPane);
        tab.setUserData(config.getId());

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

        tab.setOnClosed(e -> {
            S3Service.closeSshTunnel(config.getId());
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showTerminalView();
    }
}
