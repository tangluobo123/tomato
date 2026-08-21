package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.ConnectModule;
import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.GlobalConfig;
import com.tangluobo.tomato.module.connect.SshTunnelManager;
import com.tangluobo.tomato.module.connect.dialog.GlobalConfigDialog;
import com.tangluobo.tomato.module.connect.dialog.PasswordPromptDialog;
import com.tangluobo.tomato.module.connect.dialog.SessionConfigDialog;
import com.tangluobo.tomato.ssh.SSHTerminalPane;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.stage.Stage;

import java.util.List;

/**
 * SSH 终端连接处理器（默认分支）。
 * 完整封装 SSH 终端 tab 创建、密码输入、连接建立逻辑。
 */
public class SshTerminalConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.SSH;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        createSshTerminalTab(module, config);
    }

    /**
     * 创建 SSH 终端 tab 并发起连接
     */
    private void createSshTerminalTab(ConnectModule module, ConnectionConfig config) {
        SSHTerminalPane terminalPane = new SSHTerminalPane();

        int scrollback = config.getScrollbackLines() != null ?
                config.getScrollbackLines() : GlobalConfig.getInstance().getScrollbackLines();
        terminalPane.setScrollbackLines(scrollback);

        Tab tab = new Tab(config.getName());
        tab.setContent(terminalPane);
        tab.setUserData(config.getId());

        ContextMenu tabContextMenu = new ContextMenu();

        MenuItem copySessionItem = new MenuItem("复制会话");
        copySessionItem.setOnAction(e -> module.triggerConnect(config));

        MenuItem sessionConfigItem = new MenuItem("会话配置");
        sessionConfigItem.setOnAction(e -> {
            Stage stage = (Stage) module.getTerminalTabPane().getScene().getWindow();
            SessionConfigDialog.show(stage, config);
            int newScrollback = config.getScrollbackLines() != null ?
                    config.getScrollbackLines() : GlobalConfig.getInstance().getScrollbackLines();
            terminalPane.setScrollbackLines(newScrollback);
            module.saveConnections();
        });

        MenuItem globalConfigItem = new MenuItem("终端配置");
        globalConfigItem.setOnAction(e -> {
            Stage stage = (Stage) module.getTerminalTabPane().getScene().getWindow();
            GlobalConfigDialog.show(stage, GlobalConfigDialog.ConfigMode.SSH);
            if (config.getScrollbackLines() == null) {
                terminalPane.setScrollbackLines(GlobalConfig.getInstance().getScrollbackLines());
            }
        });

        tabContextMenu.getItems().addAll(copySessionItem, new SeparatorMenuItem(), sessionConfigItem, globalConfigItem);
        tab.setContextMenu(tabContextMenu);

        tab.setOnClosed(e -> {
            terminalPane.disconnect();
            SshTunnelManager.release(config);
            if (module.getTerminalTabPane().getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        module.getTerminalTabPane().getTabs().add(tab);
        module.getTerminalTabPane().getSelectionModel().select(tab);
        module.showTerminalView();

        // 注入跳板隧道解析回调：重连时判断是否重建隧道。
        // 先 peek 复用活跃隧道（不改变引用计数，避免误断共享隧道）；
        // 隧道已失效则 release 旧引用并 resolve 重建（引用计数保持平衡，未使用隧道时两步均为 -1）。
        terminalPane.setTunnelResolver(() -> {
            int p = SshTunnelManager.peek(config);
            if (p != -1) {
                return p;
            }
            SshTunnelManager.release(config);
            return SshTunnelManager.resolve(config);
        });

        doConnect(module, terminalPane, config);
    }

    /**
     * 处理密码输入并触发连接
     */
    private void doConnect(ConnectModule module, SSHTerminalPane terminalPane, ConnectionConfig config) {
        if (config.isUsePassword() && config.getPassword() == null) {
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
            connectWithAuth(terminalPane, config, pwdResult.getPassword());
        } else {
            connectWithAuth(terminalPane, config, config.getPassword());
        }
    }

    /**
     * 后台建立 SSH 连接（若配置了SSH跳板隧道则先建立隧道，再连接 localhost:转发端口）
     */
    private void connectWithAuth(SSHTerminalPane terminalPane, ConnectionConfig config, String password) {
        List<String> keyPaths = config.isUseKey() ? config.getPrivateKeyPaths() : null;
        new Thread(() -> {
            // 先建立/复用跳板隧道（引用方式，按 configId+host:port 缓存并引用计数）
            int tunnelLocalPort = -1;
            try {
                tunnelLocalPort = SshTunnelManager.resolve(config);
            } catch (Exception te) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("连接失败");
                    alert.setHeaderText(null);
                    alert.setContentText("建立SSH跳板隧道失败: " + te.getMessage());
                    alert.showAndWait();
                    terminalPane.disconnect();
                });
                te.printStackTrace();
                return;
            }
            try {
                String host = config.getHost();
                int port = config.getPort();
                if (tunnelLocalPort != -1) {
                    host = "localhost";
                    port = tunnelLocalPort;
                }
                terminalPane.connect(host, port, config.getUsername(), password, keyPaths);
            } catch (Exception e) {
                if (tunnelLocalPort != -1) {
                    SshTunnelManager.release(config);
                }
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("连接失败");
                    alert.setHeaderText(null);
                    alert.setContentText("SSH连接失败: " + e.getMessage());
                    alert.showAndWait();
                    terminalPane.disconnect();
                });
                e.printStackTrace();
            }
        }, "SSH-Connect").start();
    }
}
