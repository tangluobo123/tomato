package com.tangluobo.tomato.module.connect.view;

import com.tangluobo.tomato.module.connect.ToolType;
import com.tangluobo.tomato.module.tools.DatasetConverterPane;
import com.tangluobo.tomato.module.tools.DesktopShortcutPane;
import com.tangluobo.tomato.module.tools.HostsFilePane;
import com.tangluobo.tomato.module.tools.ImageBackgroundRemoverPane;
import com.tangluobo.tomato.module.tools.ImageFormatConverterPane;
import com.tangluobo.tomato.module.tools.JsonToolPane;
import com.tangluobo.tomato.module.tools.PortMappingPane;
import com.tangluobo.tomato.module.tools.ResourceExtractorPane;
import com.tangluobo.tomato.module.tools.TraeSessionPane;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 工具视图面板：根据 ToolType 渲染对应的实际功能界面。
 * 直接复用 ToolsModule 中的工具 Pane，保证与「工具」导航模块的界面一致。
 * 在连接树中打开时，自动移除工具 Pane 顶部的标题栏（titleBar）
 * 和副标题提示，因为标签页本身已经显示了工具名称。
 */
public class ToolPane extends BorderPane {

    private final ToolType toolType;

    public ToolPane(ToolType toolType) {
        this.toolType = toolType;
        setPadding(Insets.EMPTY);
        setStyle("-fx-background-color: #ffffff; -fx-background-insets: 0; -fx-border-insets: 0;");
        if (toolType == null) {
            setCenter(buildPlaceholder());
            return;
        }
        Node content = createToolContent(toolType);
        if (content instanceof VBox vbox) {
            vbox.setFillWidth(true);
            vbox.setMaxWidth(Double.MAX_VALUE);
            vbox.setStyle("-fx-background-color: #ffffff; -fx-background-insets: 0; -fx-padding: 0;");
        }
        setCenter(content);
    }

    /** 根据工具类型创建对应的内容（与 ToolsModule 中的界面一致） */
    private Node createToolContent(ToolType type) {
        VBox pane;
        switch (type) {
            case IMAGE_FORMAT_CONVERTER -> pane = new ImageFormatConverterPane();
            case DATASET_CONVERTER -> pane = new DatasetConverterPane();
            case JSON_TOOL -> pane = new JsonToolPane();
            case DESKTOP_SHORTCUT -> pane = new DesktopShortcutPane();
            case HOSTS_FILE -> pane = new HostsFilePane();
            case IMAGE_BACKGROUND_REMOVER -> pane = new ImageBackgroundRemoverPane();
            case TRAE_SESSION -> pane = new TraeSessionPane();
            case RESOURCE_EXTRACTOR -> pane = new ResourceExtractorPane();
            case PORT_MAPPING -> pane = new PortMappingPane();
            default -> {
                return buildPlaceholder();
            }
        }
        // 移除工具 Pane 顶部的标题栏（titleBar）和副标题提示
        stripTitleBar(pane);
        VBox.setVgrow(pane, Priority.ALWAYS);
        return pane;
    }

    /**
     * 移除 VBox 顶部第一个 HBox 类型的标题栏节点。
     * 工具 Pane 的标题栏都是第一个子节点且为 HBox，
     * 样式包含 "-fx-background-color: #f7f8fa"。
     */
    private void stripTitleBar(VBox pane) {
        if (pane.getChildren().isEmpty()) return;
        Node first = pane.getChildren().get(0);
        if (first instanceof HBox hbox) {
            String style = hbox.getStyle();
            if (style != null && style.contains("#f7f8fa")) {
                pane.getChildren().remove(0);
            }
        }
    }

    private Node buildPlaceholder() {
        Label label = new Label("未知工具");
        label.setStyle("-fx-font-size: 14px; -fx-text-fill: #999;");
        StackPane sp = new StackPane(label);
        return sp;
    }
}
