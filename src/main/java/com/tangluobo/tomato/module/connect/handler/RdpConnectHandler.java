package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.ConnectModule;
import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.dialog.PasswordPromptDialog;
import com.tangluobo.tomato.module.connect.dialog.SessionConfigDialog;
import com.tangluobo.tomato.rdp.RdpPane;
import javafx.scene.control.*;
import javafx.stage.Stage;

/**
 * RDP 远程桌面连接处理器。
 * 完整封装 RDP tab 创建、密码输入、连接建立逻辑。
 */
public class RdpConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.RDP;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        // 若已有打开的 RDP tab，直接切换选中
        for (Tab tab : module.getTerminalTabPane().getTabs()) {
            if (config.getId().equals(tab.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(tab);
                module.showTerminalView();
                return;
            }
        }
        createRdpTab(module, config);
    }

    /**
     * 创建 RDP tab 并发起连接
     */
    private void createRdpTab(ConnectModule module, ConnectionConfig config) {
        String password = config.getPassword();
        if (password == null || password.isEmpty()) {
            PasswordPromptDialog.Result pwdResult = PasswordPromptDialog.show(
                    "输入密码",
                    config.getName() + " (" + config.getUsername() + "@" + config.getHost() + ")",
                    "密码：", null, "保存密码");
            if (pwdResult == null || pwdResult.getPassword() == null || pwdResult.getPassword().isEmpty()) return;
            password = pwdResult.getPassword();
            if (pwdResult.isSavePassword()) {
                config.setPassword(password);
                config.setSavePassword(true);
                module.saveConnections();
            }
        }

        RdpPane rdpPane = new RdpPane();

        Tab tab = new Tab(config.getName());
        tab.setContent(rdpPane);
        tab.setUserData(config.getId());

        ContextMenu tabContextMenu = new ContextMenu();

        MenuItem sessionConfigItem = new MenuItem("会话配置");
        sessionConfigItem.setOnAction(e -> {
            Stage stage = (Stage) module.getTerminalTabPane().getScene().getWindow();
            SessionConfigDialog.show(stage, config);
            module.saveConnections();
        });

        tabContextMenu.getItems().add(sessionConfigItem);
        tab.setContextMenu(tabContextMenu);

        tab.setOnClosed(e -> {
            rdpPane.disconnect();
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == tab) {
                rdpPane.requestRdpFocus();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showTerminalView();

        int rdpPort = config.getPort() > 0 ? config.getPort() : 3389;
        int width = config.getScreenWidth() > 0 ? config.getScreenWidth() : 1024;
        int height = config.getScreenHeight() > 0 ? config.getScreenHeight() : 768;
        int bpp = config.getColorDepth() > 0 ? config.getColorDepth() : 24;
        String domain = config.getDomain();

        rdpPane.connect(config.getHost(), rdpPort, config.getUsername(), password,
                domain, width, height, bpp, config.isUseSsl());
    }
}
