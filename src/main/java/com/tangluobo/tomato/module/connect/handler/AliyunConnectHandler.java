package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.*;
import com.tangluobo.tomato.module.connect.dialog.PasswordPromptDialog;
import com.tangluobo.tomato.module.connect.service.AliyunService;
import com.tangluobo.tomato.module.connect.view.AliyunDomainDataView;
import javafx.application.Platform;
import javafx.scene.control.*;
import java.util.List;
import java.util.Map;

/**
 * 阿里云连接处理器
 */
public class AliyunConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.ALIYUN;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        TreeItem<String> hostItem = module.findItemById(module.getRoot(), config.getId());
        if (hostItem != null) {
            handleHostDoubleClick(module, hostItem, config);
        }
    }

    @Override
    public void handleHostDoubleClick(ConnectModule module, TreeItem<String> hostItem, ConnectionConfig config) {
        if (!hostItem.getChildren().isEmpty()) {
            hostItem.setExpanded(!hostItem.isExpanded());
            return;
        }

        // 如果SK未保存，弹窗输入
        if (config.getPassword() == null || config.getPassword().isEmpty()) {
            PasswordPromptDialog.Result pwdResult = PasswordPromptDialog.show(
                    "输入Secret Key",
                    config.getName() + " (" + config.getUsername() + ")",
                    "Secret Key：", "AccessKey Secret", "保存密钥");
            if (pwdResult == null || pwdResult.getPassword() == null || pwdResult.getPassword().isEmpty()) return;
            config.setPassword(pwdResult.getPassword());
            if (pwdResult.isSavePassword()) {
                config.setSavePassword(true);
                module.saveConnections();
            }
        }

        ProgressIndicator loadingIndicator = new ProgressIndicator();
        loadingIndicator.setPrefSize(16, 16);
        loadingIndicator.setMaxSize(16, 16);
        loadingIndicator.setStyle("-fx-progress-color: #4CAF50;");
        hostItem.setGraphic(loadingIndicator);

        new Thread(() -> {
            try {
                boolean authenticated = AliyunService.verifyCredentials(config);
                if (!authenticated) {
                    Platform.runLater(() -> {
                        hostItem.setGraphic(module.getIconForConfig(config));
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("认证失败");
                        alert.setHeaderText(null);
                        alert.setContentText("阿里云OAuth2认证失败，请检查AccessKey和SecretKey是否正确");
                        alert.showAndWait();
                    });
                    return;
                }
                Platform.runLater(() -> {
                    module.updateHostIcon(hostItem, config, true);
                    hostItem.getChildren().clear();

                    // 加载可访问的云服务产品列表为子节点
                    for (AliyunService.AliyunProduct product : AliyunService.getSupportedProducts()) {
                        TreeItem<String> productItem = new TreeItem<>(product.getName());
                        DatabaseNodeData data = new DatabaseNodeData(DatabaseNodeData.NodeType.ALIYUN_PRODUCT_FOLDER, product.getName(), config, product.getCode());
                        productItem.setGraphic(module.getDbNodeIcon(data));
                        module.getDbNodeDataMap().put(productItem, data);
                        hostItem.getChildren().add(productItem);
                    }
                    hostItem.setExpanded(true);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    hostItem.setGraphic(module.getIconForConfig(config));
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("连接失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法连接到阿里云 " + config.getName() + ": " + e.getMessage());
                    alert.showAndWait();
                });
                e.printStackTrace();
            }
        }, "Aliyun-Connect").start();
    }

    /** 刷新阿里云主机：清空子节点并重新触发双击连接 */
    public void refreshHost(ConnectModule module, TreeItem<String> hostItem, ConnectionConfig config) {
        for (TreeItem<String> child : hostItem.getChildren()) {
            module.removeDbNodeDataRecursive(child);
        }
        hostItem.getChildren().clear();
        module.triggerHostDoubleClick(hostItem, config);
    }

    /** 刷新阿里云节点（产品folder或域名） */
    public void refreshDbNode(ConnectModule module, TreeItem<String> item, DatabaseNodeData data) {
        switch (data.getType()) {
            case ALIYUN_PRODUCT_FOLDER -> {
                item.getChildren().clear();
                loadAliyunProductChildren(module, item, data);
            }
            case ALIYUN_DOMAIN -> handleAliyunDomainDoubleClick(module, item, data);
            default -> {}
        }
    }

    /** 加载阿里云产品子节点（ECS实例/域名等） */
    public void loadAliyunProductChildren(ConnectModule module, TreeItem<String> productItem, DatabaseNodeData data) {
        String productCode = data.getDatabaseName(); // product code stored in databaseName field
        ConnectionConfig config = data.getConnectionConfig();

        new Thread(() -> {
            try {
                switch (productCode) {
                    case "ecs" -> {
                        List<Map<String, Object>> instances = AliyunService.getEcsInstances(config, "cn-hangzhou");
                        Platform.runLater(() -> {
                            productItem.getChildren().clear();
                            for (Map<String, Object> instance : instances) {
                                String name = (String) instance.getOrDefault("instanceName", instance.get("instanceId"));
                                String status = (String) instance.getOrDefault("status", "");
                                String displayName = name + " (" + status + ")";
                                TreeItem<String> instanceItem = new TreeItem<>(displayName);
                                instanceItem.setGraphic(module.getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.ALIYUN_ECS_INSTANCE, displayName, config, (String) instance.get("instanceId"))));
                                module.getDbNodeDataMap().put(instanceItem, new DatabaseNodeData(DatabaseNodeData.NodeType.ALIYUN_ECS_INSTANCE, displayName, config, (String) instance.get("instanceId")));
                                productItem.getChildren().add(instanceItem);
                            }
                            productItem.setExpanded(true);
                        });
                    }
                    case "domain" -> {
                        List<Map<String, Object>> domains = AliyunService.getDomainList(config);
                        Platform.runLater(() -> {
                            productItem.getChildren().clear();
                            for (Map<String, Object> domain : domains) {
                                String domainName = String.valueOf(domain.getOrDefault("domainName", ""));
                                TreeItem<String> domainItem = new TreeItem<>(domainName);
                                domainItem.setGraphic(module.getDbNodeIcon(new DatabaseNodeData(DatabaseNodeData.NodeType.ALIYUN_DOMAIN, domainName, config, String.valueOf(domain.getOrDefault("instanceId", "")))));
                                module.getDbNodeDataMap().put(domainItem, new DatabaseNodeData(DatabaseNodeData.NodeType.ALIYUN_DOMAIN, domainName, config, String.valueOf(domain.getOrDefault("instanceId", ""))));
                                productItem.getChildren().add(domainItem);
                            }
                            productItem.setExpanded(true);
                        });
                    }
                    default -> {
                        // 其他产品暂不支持展开
                        Platform.runLater(() -> {
                            Label placeholder = new Label("暂不支持查看" + data.getName() + "详情");
                            placeholder.setStyle("-fx-text-fill: #999; -fx-font-size: 11px;");
                        });
                    }
                }
            } catch (Exception e) {
                Platform.runLater(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("加载失败");
                    alert.setHeaderText(null);
                    alert.setContentText("无法加载" + data.getName() + "列表: " + e.getMessage());
                    alert.showAndWait();
                });
            }
        }, "Aliyun-LoadProduct").start();
    }

    /** 双击阿里云域名节点：打开子域名数据视图tab */
    public void handleAliyunDomainDoubleClick(ConnectModule module, TreeItem<String> item, DatabaseNodeData data) {
        javafx.scene.control.TabPane terminalTabPane = module.getTerminalTabPane();
        if (terminalTabPane == null) return;
        if (!module.ensureTabPaneInstalled()) return;

        ConnectionConfig config = data.getConnectionConfig();
        String domainName = data.getName();
        String tabId = "aliyun_domain_" + config.getId() + "_" + domainName;

        // 如果已有该标签，直接选中
        for (javafx.scene.control.Tab tab : terminalTabPane.getTabs()) {
            if (tabId.equals(tab.getUserData())) {
                terminalTabPane.getSelectionModel().select(tab);
                module.showDataView();
                return;
            }
        }

        AliyunDomainDataView domainView = new AliyunDomainDataView(config, domainName);

        String tabTitle = "子域名(" + domainName + ")";
        javafx.scene.control.Tab tab = new javafx.scene.control.Tab(tabTitle);

        try {
            javafx.scene.image.Image aliyunIcon = new javafx.scene.image.Image(getClass().getResourceAsStream("/images/connect/aliyun.png"));
            javafx.scene.image.ImageView tabIconView = new javafx.scene.image.ImageView(aliyunIcon);
            tabIconView.setFitWidth(18);
            tabIconView.setFitHeight(18);
            tab.setGraphic(ConnectModule.createFixedSizeGraphic(tabIconView));
        } catch (Exception ignored) {}

        tab.setContent(domainView);
        tab.setUserData(tabId);
        tab.setOnClosed(e -> {
            if (terminalTabPane.getTabs().isEmpty()) {
                module.showWelcomeView();
            }
        });

        terminalTabPane.getTabs().add(tab);
        terminalTabPane.getSelectionModel().select(tab);
        module.showDataView();
    }

    /** 双击产品 folder 节点：若已加载则切换展开状态，否则加载子节点 */
    public void handleProductFolderDoubleClick(ConnectModule module, TreeItem<String> folderItem, DatabaseNodeData data) {
        if (!folderItem.getChildren().isEmpty()) {
            folderItem.setExpanded(!folderItem.isExpanded());
            return;
        }
        loadAliyunProductChildren(module, folderItem, data);
        folderItem.setExpanded(true);
    }

    /** 构建阿里云节点右键菜单：产品文件夹、域名均提供"刷新" */
    @Override
    public void populateNodeContextMenu(ConnectModule module, ContextMenu contextMenu, TreeItem<String> item, DatabaseNodeData data) {
        switch (data.getType()) {
            case ALIYUN_PRODUCT_FOLDER, ALIYUN_DOMAIN -> {
                MenuItem refreshItem = new MenuItem("刷新");
                refreshItem.setOnAction(e -> module.refreshDbNode(item, data));
                contextMenu.getItems().add(refreshItem);
            }
            default -> {}
        }
    }
}
