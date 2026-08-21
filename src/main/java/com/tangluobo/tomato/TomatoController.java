package com.tangluobo.tomato;

import com.tangluobo.tomato.module.Module;
import com.tangluobo.tomato.module.connect.ConnectModule;
import com.tangluobo.tomato.module.connect.GlobalConfig;
import com.tangluobo.tomato.module.server.ServerModule;
import com.tangluobo.tomato.module.settings.SettingsModule;
import com.tangluobo.tomato.module.tools.ToolsModule;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TomatoController {
    @FXML
    private HBox rootPane;
    @FXML
    private VBox navPane;
    @FXML
    private VBox sidebarPane;
    @FXML
    private Region divider2;
    @FXML
    private VBox contentPane;
    @FXML
    private HBox titleBar;
    @FXML
    private VBox chatContent;
    @FXML
    private ScrollPane chatScrollPane;
    @FXML
    private Button minimizeBtn;
    @FXML
    private Button maximizeBtn;
    @FXML
    private Button closeBtn;
    @FXML
    private Button settingsBtn;
    @FXML
    private ImageView settingsIcon;

    private double xOffset = 0;
    private double yOffset = 0;
    private double startWidth = 0;
    private double startHeight = 0;
    private double startX = 0;
    private double startY = 0;
    private double startWindowX = 0;
    private double startWindowY = 0;

    private double dividerStartX = 0;
    private double dividerStartWidth = 0;

    private boolean resizingLeft = false;
    private boolean resizingRight = false;
    private boolean resizingTop = false;
    private boolean resizingBottom = false;
    private boolean resizingDivider2 = false;
    private boolean windowManagementActive = false;
    private boolean customMaximized = false;
    private boolean draggingFromMaximized = false;
    /** 关闭按钮按下状态标志：按下期间忽略 hover/exit 对样式的修改，直到松开才恢复 */
    private boolean closeBtnPressed = false;

    private double savedX = 0;
    private double savedY = 0;
    private double savedWidth = 0;
    private double savedHeight = 0;

    private double dragStartX = 0;
    private double dragStartY = 0;

    /** 窗口四侧边缘 resize 命中范围，统一较小值避免覆盖内容区边缘的交互控件 */
    private static final int HORIZONTAL_EDGE_THRESHOLD = 3;
    private static final int MAXIMIZE_THRESHOLD = 5;

    // 模块缓存：保留每个模块的实例及其侧边栏子节点/内容容器，切换模块时复用，保留原有窗口状态
    private final Map<String, Module> moduleCache = new HashMap<>();
    private final Map<String, List<Node>> moduleSidebarChildrenCache = new HashMap<>();
    private final Map<String, String> moduleSidebarStyleCache = new HashMap<>();
    private final Map<String, VBox> moduleContentCache = new HashMap<>();
    private String currentModuleId = null;

    @FXML
    protected void onHelloButtonClick() {
        Charset.availableCharsets().forEach((s, charset) -> {
            System.out.println(charset);
        });

        System.out.println("----------------");
        System.out.println("默认编码：" + Charset.defaultCharset());
    }

    @FXML
    protected void onModuleClick(javafx.event.ActionEvent event) {
        Button source = (Button) event.getSource();
        String moduleId = (String) source.getUserData();
        loadModule(moduleId);
    }

    private Module getOrCreateModule(String moduleId) {
        return moduleCache.computeIfAbsent(moduleId, id -> {
            switch (id) {
                case "connect":
                    return new ConnectModule();
                case "tools":
                    return new ToolsModule();
                case "server":
                    return new ServerModule();
                case "settings":
                    return new SettingsModule();
                default:
                    return null;
            }
        });
    }

    private void loadModule(String moduleId) {
        if (moduleId.equals(currentModuleId)) {
            return;
        }

        Module module = getOrCreateModule(moduleId);
        if (module == null) {
            return;
        }

        // 移除当前模块的侧边栏子节点（保留节点到缓存，不销毁状态）
        sidebarPane.getChildren().clear();
        contentPane.getChildren().removeIf(n -> n != titleBar && n != chatScrollPane);

        // 隐藏 ScrollPane，直接使用模块内容容器占满右侧
        chatScrollPane.setManaged(false);
        chatScrollPane.setVisible(false);
        contentPane.setFillWidth(true);

        // 获取或创建该模块缓存的侧边栏子节点/内容容器
        List<Node> sidebarChildren = moduleSidebarChildrenCache.get(moduleId);
        VBox moduleContent = moduleContentCache.get(moduleId);

        if (sidebarChildren == null) {
            // 首次加载该模块：构建其 UI 并缓存
            sidebarPane.setStyle("-fx-background-color: #ffffff;");
            module.loadSidebar(sidebarPane);
            moduleSidebarChildrenCache.put(moduleId, new ArrayList<>(sidebarPane.getChildren()));
            moduleSidebarStyleCache.put(moduleId, sidebarPane.getStyle());

            moduleContent = new VBox();
            moduleContent.setSpacing(0);
            moduleContent.setStyle("-fx-background-color: #ffffff; -fx-background-insets: 0; -fx-padding: 0; -fx-border-insets: 0;");
            moduleContent.setFillWidth(true);
            moduleContent.setMaxWidth(Double.MAX_VALUE);
            moduleContent.setMaxHeight(Double.MAX_VALUE);
            VBox.setVgrow(moduleContent, Priority.ALWAYS);
            module.loadContent(moduleContent);
            moduleContentCache.put(moduleId, moduleContent);
        } else {
            // 恢复缓存的样式和子节点到 sidebarPane
            sidebarPane.setStyle(moduleSidebarStyleCache.getOrDefault(moduleId, "-fx-background-color: #ffffff;"));
            sidebarPane.getChildren().addAll(sidebarChildren);
        }

        if (sidebarPane.getChildren().isEmpty()) {
            sidebarPane.setVisible(false);
            sidebarPane.setManaged(false);
            divider2.setVisible(false);
            divider2.setManaged(false);
        } else {
            sidebarPane.setVisible(true);
            sidebarPane.setManaged(true);
            divider2.setVisible(true);
            divider2.setManaged(true);
        }

        contentPane.getChildren().add(moduleContent);

        currentModuleId = moduleId;
    }

    @FXML
    protected void onSettings() {
        // 确保连接模块已加载（terminalTabPane 在 connect 模块的 loadContent 中创建）
        if (!"connect".equals(currentModuleId)) {
            loadModule("connect");
        }
        Module module = moduleCache.get("connect");
        if (module instanceof ConnectModule cm) {
            cm.openSettingsTab(show -> applySidebarVisible(show, true));
        }
    }

    @FXML
    protected void onMinimize() {
        Stage stage = (Stage) rootPane.getScene().getWindow();
        stage.setIconified(true);
    }

    /**
     * 应用侧边栏显隐状态：同步 navPane 可见性，并可持久化到全局配置。
     */
    private void applySidebarVisible(boolean show, boolean persist) {
        navPane.setVisible(show);
        navPane.setManaged(show);
        if (persist) {
            GlobalConfig cfg = GlobalConfig.getInstance();
            if (cfg.isSidebarVisible() != show) {
                cfg.setSidebarVisible(show);
                cfg.save();
            }
        }
    }

    @FXML
    protected void onMaximize() {
        Stage stage = (Stage) rootPane.getScene().getWindow();

        if (customMaximized) {
            restoreWindow(stage);
        } else {
            maximizeWindow(stage);
        }
    }

    private void maximizeWindow(Stage stage) {
        savedX = stage.getX();
        savedY = stage.getY();
        savedWidth = stage.getWidth();
        savedHeight = stage.getHeight();

        // 使用窗口中心点所在的屏幕（而非主屏），这样双屏时在副屏最大化不会跳到主屏
        Screen screen = getScreenForStage(stage);
        Rectangle2D visualBounds = screen.getVisualBounds();

        stage.setX(visualBounds.getMinX());
        stage.setY(visualBounds.getMinY());
        stage.setWidth(visualBounds.getWidth());
        stage.setHeight(visualBounds.getHeight());

        customMaximized = true;
        rootPane.setStyle("-fx-border-color: transparent; -fx-border-width: 0;");

        // 最大化后图标切换为"两个错开的空心方框"（还原图标样式）：
        // 右上方框 (7,3)-(19,15) 只绘制不被左下方框覆盖的外框边线段（左下角被遮挡）
        // 左下方框 (4,6)-(16,18) 完整绘制（环形空心），覆盖在右上方框之上
        // 边框厚度1（与左下方框一致）
        javafx.scene.shape.SVGPath topRightBox = new javafx.scene.shape.SVGPath();
        // 右上方框可见外框边线（4条边，去掉被左下方框覆盖的左下角段）：
        // 上边 y=3 x[7,19]；右边 x[18,19] y[3,15]；左边上段 x[7,8] y[3,6]；下边右段 x[16,19] y[15,16]
        topRightBox.setContent("M7 3h12v1H7z M18 3h1v12h-1z M7 3h1v3h-1z M16 15h3v1h-3z");
        topRightBox.setFill(javafx.scene.paint.Color.web("#131313"));
        javafx.scene.shape.SVGPath bottomLeftBox = new javafx.scene.shape.SVGPath();
        bottomLeftBox.setContent("M4 6h12v12H4V6zm1 1v10h10V7H5z");
        bottomLeftBox.setFill(javafx.scene.paint.Color.web("#131313"));
        javafx.scene.Group restoreIcon = new javafx.scene.Group(topRightBox, bottomLeftBox);
        maximizeBtn.setGraphic(restoreIcon);
    }

    /**
     * 根据窗口中心点定位窗口当前所在的屏幕。
     * 用于多屏环境下：在副屏点击最大化按钮或拖到顶部时，
     * 应在副屏上最大化，而不是强制弹回主屏。
     */
    private Screen getScreenForStage(Stage stage) {
        double centerX = stage.getX() + stage.getWidth() / 2.0;
        double centerY = stage.getY() + stage.getHeight() / 2.0;
        for (Screen screen : Screen.getScreens()) {
            if (screen.getBounds().contains(centerX, centerY)) {
                return screen;
            }
        }
        return Screen.getPrimary();
    }

    private void restoreWindow(Stage stage) {
        stage.setX(savedX);
        stage.setY(savedY);
        stage.setWidth(savedWidth);
        stage.setHeight(savedHeight);
        customMaximized = false;
        rootPane.setStyle("-fx-border-color: #D9D9D7; -fx-border-width: 0 0 1 1;");

        // 还原窗口后图标恢复为单矩形（最大化图标样式），重建 SVGPath 替换 Group
        javafx.scene.shape.SVGPath maximizeIcon = new javafx.scene.shape.SVGPath();
        maximizeIcon.setContent("M5 6h14v12H5V6zm1 1v10h12V7H6z");
        maximizeIcon.setFill(javafx.scene.paint.Color.web("#131313"));
        maximizeBtn.setGraphic(maximizeIcon);
    }

    @FXML
    protected void onClose() {
        Stage stage = (Stage) rootPane.getScene().getWindow();
        stage.close();
    }

    @FXML
    public void initialize() {
        Image settingsImage = new Image(getClass().getResourceAsStream("/images/settings.png"));
        if (settingsImage != null && !settingsImage.isError()) {
            settingsIcon.setImage(settingsImage);
        }

        // 侧边栏显隐从全局配置读取（默认显示）
        applySidebarVisible(GlobalConfig.getInstance().isSidebarVisible(), false);

        divider2.setViewOrder(-1);
        divider2.setMouseTransparent(false);

        setupDivider(divider2);

        // 关闭按钮交互效果：
        // - 鼠标划过(hover)：背景色 #C42B1C，图标白色
        // - 鼠标按下(pressed)：背景色 #C42B1C，图标颜色 #EDBFBB；按下期间保持该态，忽略 hover/exit
        // - 鼠标松开(released)：根据是否仍在按钮内恢复为 hover 态或默认态
        // - 鼠标移出：背景透明，图标黑色
        closeBtn.setOnMouseEntered(e -> {
            if (closeBtnPressed) {
                return;
            }
            closeBtn.setStyle("-fx-background-color: #C42B1C; -fx-background-radius: 0; -fx-pref-width: 30px; -fx-pref-height: 26px; -fx-padding: 0 0px 0 0;");
            if (closeBtn.getGraphic() instanceof javafx.scene.shape.SVGPath) {
                ((javafx.scene.shape.SVGPath) closeBtn.getGraphic()).fillProperty().set(javafx.scene.paint.Color.WHITE);
            }
        });
        closeBtn.setOnMouseExited(e -> {
            if (closeBtnPressed) {
                return;
            }
            closeBtn.setStyle("-fx-background-color: transparent; -fx-background-radius: 0; -fx-pref-width: 30px; -fx-pref-height: 26px; -fx-padding: 0 0px 0 0;");
            if (closeBtn.getGraphic() instanceof javafx.scene.shape.SVGPath) {
                ((javafx.scene.shape.SVGPath) closeBtn.getGraphic()).fillProperty().set(javafx.scene.paint.Color.web("#131313"));
            }
        });
        closeBtn.setOnMousePressed(e -> {
            closeBtnPressed = true;
            closeBtn.setStyle("-fx-background-color: #C42B1C; -fx-background-radius: 0; -fx-pref-width: 30px; -fx-pref-height: 26px; -fx-padding: 0 0px 0 0;");
            if (closeBtn.getGraphic() instanceof javafx.scene.shape.SVGPath) {
                ((javafx.scene.shape.SVGPath) closeBtn.getGraphic()).fillProperty().set(javafx.scene.paint.Color.web("#EDBFBB"));
            }
        });
        closeBtn.setOnMouseReleased(e -> {
            closeBtnPressed = false;
            // 鼠标松开时根据是否仍在按钮内恢复正常态
            if (closeBtn.isHover()) {
                closeBtn.setStyle("-fx-background-color: #C42B1C; -fx-background-radius: 0; -fx-pref-width: 30px; -fx-pref-height: 26px; -fx-padding: 0 0px 0 0;");
                if (closeBtn.getGraphic() instanceof javafx.scene.shape.SVGPath) {
                    ((javafx.scene.shape.SVGPath) closeBtn.getGraphic()).fillProperty().set(javafx.scene.paint.Color.WHITE);
                }
            } else {
                closeBtn.setStyle("-fx-background-color: transparent; -fx-background-radius: 0; -fx-pref-width: 30px; -fx-pref-height: 26px; -fx-padding: 0 0px 0 0;");
                if (closeBtn.getGraphic() instanceof javafx.scene.shape.SVGPath) {
                    ((javafx.scene.shape.SVGPath) closeBtn.getGraphic()).fillProperty().set(javafx.scene.paint.Color.web("#131313"));
                }
            }
        });

        // 最小化/最大化按钮 hover 背景色：#E1E7F5；鼠标移出恢复透明。图标颜色保持原样。
        final String minMaxHoverStyle = "-fx-background-color: #E1E7F5; -fx-background-radius: 0; -fx-pref-width: 30px; -fx-pref-height: 26px; -fx-padding: 0 0px 0 0;";
        final String minMaxNormalStyle = "-fx-background-color: transparent; -fx-background-radius: 0; -fx-pref-width: 30px; -fx-pref-height: 26px; -fx-padding: 0 0px 0 0;";
        minimizeBtn.setOnMouseEntered(e -> minimizeBtn.setStyle(minMaxHoverStyle));
        minimizeBtn.setOnMouseExited(e -> minimizeBtn.setStyle(minMaxNormalStyle));
        maximizeBtn.setOnMouseEntered(e -> maximizeBtn.setStyle(minMaxHoverStyle));
        maximizeBtn.setOnMouseExited(e -> maximizeBtn.setStyle(minMaxNormalStyle));
        settingsBtn.setOnMouseEntered(e -> settingsBtn.setStyle(minMaxHoverStyle));
        settingsBtn.setOnMouseExited(e -> settingsBtn.setStyle(minMaxNormalStyle));

        titleBar.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                Stage stage = (Stage) titleBar.getScene().getWindow();
                if (customMaximized) {
                    restoreWindow(stage);
                } else {
                    maximizeWindow(stage);
                }
                event.consume();
            }
        });

        rootPane.sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                newScene.addEventFilter(MouseEvent.MOUSE_PRESSED, this::onMousePressed);
                newScene.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onMouseDragged);
                newScene.addEventFilter(MouseEvent.MOUSE_MOVED, this::onMouseMoved);
                newScene.addEventFilter(MouseEvent.MOUSE_EXITED, this::onMouseExited);
                newScene.addEventFilter(MouseEvent.MOUSE_RELEASED, this::onMouseReleased);
            }
        });

        loadModule("connect");
    }

    private void setupDivider(Region divider) {
        // 初始化时锁定sidebar最小宽度，防止被内容区压缩
        sidebarPane.setMinWidth(sidebarPane.getPrefWidth());

        divider.setOnMouseEntered(e -> divider.setCursor(Cursor.H_RESIZE));
        divider.setOnMouseExited(e -> divider.setCursor(Cursor.DEFAULT));

        divider.setOnMousePressed(e -> {
            dividerStartX = e.getScreenX();
            dividerStartWidth = sidebarPane.getWidth();
            resizingDivider2 = true;
        });

        divider.setOnMouseDragged(e -> {
            double deltaX = e.getScreenX() - dividerStartX;
            double newWidth = dividerStartWidth + deltaX;
            if (newWidth >= 60 && newWidth <= 500) {
                sidebarPane.setPrefWidth(newWidth);
                sidebarPane.setMinWidth(newWidth);
            }
        });

        divider.setOnMouseReleased(e -> {
            resizingDivider2 = false;
        });
    }

    private void onMouseMoved(MouseEvent event) {
        if (resizingDivider2) {
            return;
        }

        if (customMaximized) {
            rootPane.setCursor(Cursor.DEFAULT);
            return;
        }

        double sceneX = event.getSceneX();
        double sceneY = event.getSceneY();
        Stage stage = (Stage) rootPane.getScene().getWindow();
        double width = stage.getWidth();
        double height = stage.getHeight();

        Cursor cursor = determineCursor(sceneX, sceneY, width, height);
        rootPane.setCursor(cursor);
    }

    private void onMouseExited(MouseEvent event) {
        rootPane.setCursor(Cursor.DEFAULT);
    }

    private Cursor determineCursor(double x, double y, double width, double height) {
        boolean nearLeft = x <= HORIZONTAL_EDGE_THRESHOLD;
        boolean nearRight = x >= width - HORIZONTAL_EDGE_THRESHOLD;
        boolean nearTop = y <= HORIZONTAL_EDGE_THRESHOLD;
        boolean nearBottom = y >= height - HORIZONTAL_EDGE_THRESHOLD;

        if (nearLeft && nearTop) return Cursor.NW_RESIZE;
        if (nearRight && nearTop) return Cursor.NE_RESIZE;
        if (nearLeft && nearBottom) return Cursor.SW_RESIZE;
        if (nearRight && nearBottom) return Cursor.SE_RESIZE;
        if (nearLeft || nearRight) return Cursor.E_RESIZE;
        if (nearTop || nearBottom) return Cursor.N_RESIZE;

        return Cursor.DEFAULT;
    }

    private void onMousePressed(MouseEvent event) {
        if (resizingDivider2) {
            return;
        }

        // 向上遍历父节点链查找交互控件（ButtonBase/TextInputControl/ListCell），
        // 这样点击按钮内的子节点（如 SVGPath 图标）也能正确过滤，避免在按钮上按下拖动时移动窗体
        boolean onInteractiveControl = false;
        if (event.getTarget() instanceof javafx.scene.Node) {
            javafx.scene.Node node = (javafx.scene.Node) event.getTarget();
            while (node != null) {
                if (node instanceof javafx.scene.control.TextInputControl ||
                    node instanceof javafx.scene.control.ButtonBase ||
                    node instanceof javafx.scene.control.ListCell) {
                    onInteractiveControl = true;
                    break;
                }
                node = node.getParent();
            }
        }
        if (onInteractiveControl) {
            windowManagementActive = false;
            return;
        }

        Stage stage = (Stage) rootPane.getScene().getWindow();

        if (customMaximized) {
            if (isInTitleBar(event)) {
                draggingFromMaximized = true;
                dragStartX = event.getScreenX();
                dragStartY = event.getScreenY();
                windowManagementActive = true;
                resizingLeft = false;
                resizingRight = false;
                resizingTop = false;
                resizingBottom = false;
                xOffset = dragStartX - savedX;
                yOffset = dragStartY - savedY;
            } else {
                windowManagementActive = false;
            }
            return;
        }

        double sceneX = event.getSceneX();
        double sceneY = event.getSceneY();
        double width = stage.getWidth();
        double height = stage.getHeight();

        resizingLeft = sceneX <= HORIZONTAL_EDGE_THRESHOLD;
        resizingRight = sceneX >= width - HORIZONTAL_EDGE_THRESHOLD;
        resizingTop = sceneY <= HORIZONTAL_EDGE_THRESHOLD;
        resizingBottom = sceneY >= height - HORIZONTAL_EDGE_THRESHOLD;

        if (resizingLeft || resizingRight || resizingTop || resizingBottom) {
            windowManagementActive = true;
            draggingFromMaximized = false;
            startWidth = width;
            startHeight = height;
            startX = event.getScreenX();
            startY = event.getScreenY();
            startWindowX = stage.getX();
            startWindowY = stage.getY();
        } else if (isInTitleBar(event)) {
            windowManagementActive = true;
            draggingFromMaximized = false;
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        } else {
            windowManagementActive = false;
        }
    }

    private boolean isInTitleBar(MouseEvent event) {
        if (titleBar == null) return false;
        Object target = event.getTarget();
        if (target == titleBar) return true;
        if (target instanceof Node) {
            Node node = (Node) target;
            while (node != null) {
                if (node == titleBar) return true;
                node = node.getParent();
            }
        }
        return false;
    }

    private void onMouseDragged(MouseEvent event) {
        if (resizingDivider2) {
            return;
        }

        if (!windowManagementActive) {
            return;
        }

        Stage stage = (Stage) rootPane.getScene().getWindow();

        if (draggingFromMaximized) {
            double currentY = event.getScreenY();
            if (currentY > MAXIMIZE_THRESHOLD) {
                restoreWindow(stage);
                double deltaX = event.getScreenX() - dragStartX;
                double deltaY = event.getScreenY() - dragStartY;
                stage.setX(savedX + deltaX);
                stage.setY(savedY + deltaY);
                draggingFromMaximized = false;
                windowManagementActive = false;
            }
            return;
        }

        if (customMaximized) {
            return;
        }

        if (resizingLeft || resizingRight || resizingTop || resizingBottom) {
            double deltaX = event.getScreenX() - startX;
            double deltaY = event.getScreenY() - startY;

            double newWidth = startWidth;
            double newHeight = startHeight;
            double newX = startWindowX;
            double newY = startWindowY;

            if (resizingRight) {
                newWidth = startWidth + deltaX;
            } else if (resizingLeft) {
                newWidth = startWidth - deltaX;
                newX = startWindowX + deltaX;
            }

            if (resizingBottom) {
                newHeight = startHeight + deltaY;
            } else if (resizingTop) {
                newHeight = startHeight - deltaY;
                newY = startWindowY + deltaY;
            }

            if (newWidth >= 400) stage.setWidth(newWidth);
            if (newHeight >= 300) stage.setHeight(newHeight);
            if (resizingLeft) stage.setX(newX);
            if (resizingTop) stage.setY(newY);
        } else {
            double newX = event.getScreenX() - xOffset;
            double newY = event.getScreenY() - yOffset;

            // 按窗口中心点定位当前屏幕，副屏顶部（minY!=0）也能触发最大化
            Screen screen = getScreenForStage(stage);
            Rectangle2D visualBounds = screen.getVisualBounds();
            double screenTop = visualBounds.getMinY();

            if (newY <= screenTop + MAXIMIZE_THRESHOLD && newX >= visualBounds.getMinX()
                && newX + stage.getWidth() <= visualBounds.getMaxX()) {
                maximizeWindow(stage);
                windowManagementActive = false;
            } else {
                stage.setX(newX);
                stage.setY(newY);
            }
        }
    }

    private void onMouseReleased(MouseEvent event) {
        if (resizingDivider2) {
            return;
        }

        windowManagementActive = false;
        draggingFromMaximized = false;
        resizingLeft = false;
        resizingRight = false;
        resizingTop = false;
        resizingBottom = false;
        rootPane.setCursor(Cursor.DEFAULT);
    }
}