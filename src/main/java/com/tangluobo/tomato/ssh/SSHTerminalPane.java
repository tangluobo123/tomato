package com.tangluobo.tomato.ssh;

import com.tangluobo.tomato.ssh.zmodem.ZModem;
import com.tangluobo.tomato.ssh.zmodem.util.CustomFile;
import com.tangluobo.tomato.ssh.zmodem.util.FileAdapter;
import com.tangluobo.tomato.ssh.zmodem.xfer.zm.util.ZModemCharacter;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.Cursor;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.SnapshotParameters;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSH终端组件，使用VT100终端模拟器，支持ZModem协议（rz/sz文件传输）
 * 继承BorderPane，中间放终端，底部放状态栏，右侧可展开SFTP文件浏览器
 */
public class SSHTerminalPane extends BorderPane {

    // ZModem协议前缀: ** ZDLE
    private static final char[] ZMODEM_PREFIX = new char[]{
            (char) ZModemCharacter.ZPAD.value(),
            (char) ZModemCharacter.ZPAD.value(),
            (char) ZModemCharacter.ZDLE.value()
    };

    private final TerminalEmulator emulator;
    private final TerminalView terminalView;
    private SSHSession sshSession;
    private Thread readThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ZModem zmodem;
    private volatile boolean inZModemMode = false;

    // 断开连接回调
    private Runnable onDisconnect;

    // SSH跳板隧道解析回调：重连时判断是否需要重建隧道（先peek复用活跃隧道，失效则release+resolve重建），
    // 返回本地转发端口；未使用隧道返回 -1。由持有ConnectionConfig的连接处理器注入，避免ssh包反向依赖module.connect。
    private TunnelResolver tunnelResolver;

    // 粘贴回调
    private Runnable onPaste;

    // 右键菜单
    private final ContextMenu contextMenu;

    // 渲染节流
    private long lastRenderTime = 0;
    private static final long RENDER_INTERVAL = 33; // ~30fps
    private boolean renderPending = false;

    // 状态栏
    private final Label stateLabel;
    private final Label connLabel;
    private final Label encodingLabel;
    private final Circle statusDot;
    private final Button portBtn;
    private final Button folderBtn;
    private final Button monitorBtn;

    // 终端容器
    private final Pane terminalPane;
    private final SplitPane splitPane;
    private final ScrollBar scrollBar;
    // 右侧面板：VBox 垂直排列，外层用 rightPanelScroll 整体滚动；面板内部不加单独滚动条。
    // monitorPanel 固定高度；fileBrowser/portPanel 高度随 TableView 内容增长。面板间用 1px #E5E5E5 Region 分隔，可拖拽调整 monitorPanel 高度。
    private final javafx.scene.layout.VBox rightPanel;
    private final javafx.scene.control.ScrollPane rightPanelScroll;
    // 高度分隔条拖拽状态
    private double heightDividerStartY;
    private double heightDividerStartHeight;

    // SFTP文件浏览器
    private SFTPFileBrowser fileBrowser;
    private SFTPClient sftpClient;
    private boolean fileBrowserVisible = false;

    // 监控视图
    private boolean monitorVisible = false;
    private MonitorPanel monitorPanel;

    // 端口视图
    private boolean portVisible = false;
    private PortPanel portPanel;

    // 防止scrollbar↔render循环
    private boolean updatingScrollbar = false;

    // 交替缓冲区状态跟踪
    private boolean lastAltBufferState = false;

    // 连接信息
    private String host;
    private int port;
    private String username;
    private String password;
    private List<String> privateKeyPaths;

    // 连接丢失标志（非用户主动断开）
    private volatile boolean connectionLost = false;

    // rz 命令预选文件：用户输入 rz 时先选好文件，再发送 rz 到远端
    private volatile CompletableFuture<List<File>> pendingUploadFuture = null;
    // rz 命令行缓冲（用于检测用户输入 rz 命令）
    private final StringBuilder rzCommandBuffer = new StringBuilder();

    public SSHTerminalPane() {
        emulator = new TerminalEmulator();
        terminalView = new TerminalView(emulator);
        // 应用全局配置的终端字体
        com.tangluobo.tomato.module.connect.GlobalConfig gcfg = com.tangluobo.tomato.module.connect.GlobalConfig.getInstance();
        terminalView.setTerminalFont(gcfg.getSshTerminalFontName(), gcfg.getSshTerminalFontSize());

        // 状态栏
        HBox statusBar = new HBox();
        statusBar.setStyle("-fx-background-color: #FFFFFB; -fx-padding: 2 10; -fx-alignment: center-left; -fx-border-color: #e0e0e0; -fx-border-width: 1 0 0 0;");

        statusDot = new Circle(4, Color.RED);
        HBox.setMargin(statusDot, new javafx.geometry.Insets(0, 4, 0, 0));

        stateLabel = new Label("未连接");
        stateLabel.setStyle("-fx-text-fill: #333333; -fx-font-size: 11px;");
        HBox.setMargin(stateLabel, new javafx.geometry.Insets(0, 8, 0, 0));

        connLabel = new Label("");
        connLabel.setStyle("-fx-text-fill: #333333; -fx-font-size: 11px;");
        HBox.setMargin(connLabel, new javafx.geometry.Insets(0, 8, 0, 0));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        encodingLabel = new Label("UTF-8");
        encodingLabel.setStyle("-fx-text-fill: #333333; -fx-font-size: 11px;");

        // SFTP文件浏览器开关按钮
        folderBtn = new Button();
        folderBtn.setStyle("-fx-background-color: transparent; -fx-padding: 2 4; -fx-border-color: transparent; -fx-cursor: hand;");
        folderBtn.setGraphic(createIcon("/images/connect/folder.png", false));
        folderBtn.setTooltip(new Tooltip("文件"));
        folderBtn.setOnAction(e -> toggleFileBrowser());

        // 监控视图开关按钮
        monitorBtn = new Button();
        monitorBtn.setStyle("-fx-background-color: transparent; -fx-padding: 2 4; -fx-border-color: transparent; -fx-cursor: hand;");
        monitorBtn.setGraphic(createIcon("/images/connect/monitor.png", false));
        monitorBtn.setTooltip(new Tooltip("监控"));
        monitorBtn.setOnAction(e -> toggleMonitor());

        // 端口视图开关按钮
        portBtn = new Button();
        portBtn.setStyle("-fx-background-color: transparent; -fx-padding: 2 4; -fx-border-color: transparent; -fx-cursor: hand;");
        portBtn.setGraphic(createPortIcon(false));
        portBtn.setTooltip(new Tooltip("端口"));
        portBtn.setOnAction(e -> togglePort());

        statusBar.getChildren().addAll(statusDot, stateLabel, connLabel, encodingLabel, spacer, portBtn, folderBtn, monitorBtn);

        // 终端区域 + 右侧滚动条
        scrollBar = new ScrollBar();
        scrollBar.setOrientation(javafx.geometry.Orientation.VERTICAL);
        scrollBar.setStyle("-fx-background-color: #2d2d2d;");
        scrollBar.setPrefWidth(12);
        scrollBar.setMin(0);
        scrollBar.setMax(0);
        scrollBar.setValue(0);
        scrollBar.setVisible(false);
        scrollBar.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (updatingScrollbar) return;
            // 交替屏幕缓冲区模式下禁止滚动条操作scrollback
            if (emulator.isUsingAltBuffer()) return;
            double visibleAmt = scrollBar.getVisibleAmount();
            int scrollbackSize = emulator.getScrollbackSize();
            double val = newVal.doubleValue();
            int offset = (int) Math.round(scrollbackSize - val + visibleAmt - 1);
            terminalView.setScrollOffset(Math.max(0, Math.min(offset, scrollbackSize)));
        });

        terminalPane = new Pane() {
            @Override
            protected void layoutChildren() {
                super.layoutChildren();
                double w = getWidth();
                double h = getHeight();
                if (w > 0 && h > 0) {
                    double sbWidth = scrollBar.isVisible() ? scrollBar.getWidth() : 0;
                    terminalView.relocate(0, 0);
                    terminalView.resize(w - sbWidth, h);
                    scrollBar.resizeRelocate(w - scrollBar.getPrefWidth(), 0, scrollBar.getPrefWidth(), h);
                }
            }
        };
        terminalPane.getChildren().addAll(terminalView, scrollBar);
        terminalPane.setStyle("-fx-background-color: #1e1e1e; -fx-background-insets: 0; -fx-padding: 0; -fx-border-color: transparent; -fx-border-width: 0; -fx-border-insets: 0;");
        terminalPane.setMaxWidth(Double.MAX_VALUE);
        terminalPane.setMaxHeight(Double.MAX_VALUE);
        terminalPane.setPrefWidth(800);
        terminalPane.setPrefHeight(600);

        // 滚动条回调：更新滚动条状态
        terminalView.setScrollbarHandler((scrollbackSize, scrollOffset, visibleRows) -> {
            Platform.runLater(() -> {
                updatingScrollbar = true;
                try {
                    int currentSbSize = emulator.getScrollbackSize();
                    int currentOffset = emulator.getScrollOffset();
                    if (currentSbSize > 0) {
                        scrollBar.setVisible(true);
                        int totalContent = currentSbSize + visibleRows;
                        scrollBar.setMin(0);
                        scrollBar.setMax(totalContent - 1);
                        scrollBar.setVisibleAmount(visibleRows);
                        scrollBar.setValue(currentSbSize - currentOffset + visibleRows - 1);
                    } else {
                        scrollBar.setVisible(false);
                    }
                } finally {
                    updatingScrollbar = false;
                }
            });
        });

        // 右侧面板：文件浏览器 + 监控面板 + 端口面板（垂直排列）
        // 外层用 rightPanelScroll 整体滚动；面板内部不加单独滚动条
        // monitorPanel 固定高度；fileBrowser/portPanel 高度随 TableView 内容增长
        rightPanel = new javafx.scene.layout.VBox();
        rightPanel.setStyle("-fx-background-color: #FFFFFF; -fx-padding: 0; -fx-spacing: 0;");
        rightPanel.setFillWidth(true);

        rightPanelScroll = new javafx.scene.control.ScrollPane(rightPanel);
        rightPanelScroll.setFitToWidth(true);
        rightPanelScroll.setFitToHeight(false);
        rightPanelScroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        rightPanelScroll.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        rightPanelScroll.setStyle("-fx-background-color: #FFFFFF; -fx-background-insets: 0; -fx-padding: 0; -fx-border-color: transparent; -fx-border-width: 0;");

        // SplitPane: 终端 + 右侧面板，支持拖拽调整宽度
        splitPane = new SplitPane();
        splitPane.getItems().add(terminalPane);
        splitPane.setDividerPositions(1.0);
        splitPane.setStyle("-fx-background-color: #1e1e1e; -fx-background-insets: 0; -fx-padding: 0; -fx-border-color: transparent; -fx-border-width: 0; -fx-border-insets: 0;");

        setCenter(splitPane);
        setBottom(statusBar);
        setStyle("-fx-background-color: #1e1e1e; -fx-background-insets: 0; -fx-padding: 0; -fx-border-color: transparent; -fx-border-width: 0; -fx-border-insets: 0;");

        // 关键：默认maxWidth/maxHeight=USE_COMPUTED_SIZE=prefSize=0
        // 必须设为MAX_VALUE，否则任何布局容器都不会给它分配空间
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);
        setPrefWidth(800);
        setPrefHeight(600);

        // 设置终端响应回调（DA查询、DSR查询等需要回传数据）
        emulator.setResponseHandler(data -> {
            if (sshSession != null && sshSession.isConnected()) {
                try {
                    OutputStream os = sshSession.getOutputStream();
                    if (os != null) {
                        os.write(data);
                        os.flush();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        // 设置OSC7目录变化回调，通知文件浏览器跟随
        emulator.setCwdChangeListener(path -> {
            if (fileBrowser != null) {
                fileBrowser.onTerminalCwdChanged(path);
            }
        });

        // 终端大小变化时通知SSH服务器
        terminalView.setResizeHandler((cols, rows, width, height) -> {
            if (sshSession != null && sshSession.isConnected()) {
                sshSession.resize(cols, rows, width, height);
            }
        });

        // 设置粘贴回调（Ctrl+Shift+V触发）
        terminalView.setPasteHandler(() -> doPaste());

        // 设置自动滚动回调（用户输入时自动滚动到底部）
        terminalView.setAutoScrollHandler(() -> {
            if (!emulator.isUsingAltBuffer() && emulator.getScrollOffset() != 0) {
                terminalView.setScrollOffset(0);
            }
        });

        // 设置键盘输入回调
        terminalView.setKeyInputHandler(data -> {
            // 连接丢失时，按回车重新连接
            if (connectionLost) {
                if (data.length == 1 && (data[0] == '\r' || data[0] == '\n')) {
                    reconnect();
                }
                return;
            }
            if (sshSession == null || !sshSession.isConnected()) return;
            if (inZModemMode) {
                // ZModem传输中，Ctrl+C取消
                if (data.length == 1 && data[0] == 0x03) {
                    try { if (zmodem != null) zmodem.cancel(); } catch (IOException ignored) {}
                }
                return;
            }

            // rz 命令检测：在主缓冲区模式下拦截 rz 命令，先选文件再发送到远端
            if (!emulator.isUsingAltBuffer() && pendingUploadFuture == null) {
                for (byte b : data) {
                    if (b == '\r' || b == '\n') {
                        String line = rzCommandBuffer.toString().trim();
                        if (line.equals("rz") || line.startsWith("rz ")) {
                            // 拦截 rz 命令：先发送回车执行 rz，再异步弹出文件选择框
                            rzCommandBuffer.setLength(0);
                            try {
                                OutputStream os = sshSession.getOutputStream();
                                if (os != null) {
                                    os.write(b);
                                    os.flush();
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                            // 异步弹出文件选择框（FX线程），结果存入 pendingUploadFuture
                            // supplier 中通过 future.get() 等待结果，避免弹出第二个文件选择框
                            pendingUploadFuture = new CompletableFuture<>();
                            Platform.runLater(() -> {
                                try {
                                    FileChooser fileChooser = new FileChooser();
                                    fileChooser.setTitle("选择要上传的文件");
                                    fileChooser.getExtensionFilters().addAll(
                                        new FileChooser.ExtensionFilter("所有文件", "*.*")
                                    );
                                    List<File> files = fileChooser.showOpenMultipleDialog(getStage());
                                    pendingUploadFuture.complete(files != null ? files : List.of());
                                } catch (Exception e) {
                                    pendingUploadFuture.complete(List.of());
                                }
                            });
                            return;
                        }
                        rzCommandBuffer.setLength(0);
                    } else if (b >= 0x20 && b < 0x7F) {
                        rzCommandBuffer.append((char) b);
                    } else if (b == 0x7F || b == 0x08) {
                        if (rzCommandBuffer.length() > 0) {
                            rzCommandBuffer.deleteCharAt(rzCommandBuffer.length() - 1);
                        }
                    } else {
                        rzCommandBuffer.setLength(0);
                    }
                }
            }

            try {
                OutputStream os = sshSession.getOutputStream();
                if (os != null) {
                    os.write(data);
                    os.flush();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });

        // 右键菜单（CRT风格）
        contextMenu = new ContextMenu();
        MenuItem copyItem = new MenuItem("复制");
        copyItem.setOnAction(e -> terminalView.copySelection());
        MenuItem pasteItem = new MenuItem("粘贴");
        pasteItem.setOnAction(e -> doPaste());
        MenuItem copyPasteItem = new MenuItem("复制并粘贴");
        copyPasteItem.setOnAction(e -> {
            terminalView.copySelection();
            doPaste();
        });
        MenuItem selectAllItem = new MenuItem("全选");
        selectAllItem.setOnAction(e -> terminalView.selectAll());
        MenuItem clearItem = new MenuItem("清除选择");
        clearItem.setOnAction(e -> terminalView.clearSelection());
        contextMenu.getItems().addAll(copyItem, pasteItem, copyPasteItem, new SeparatorMenuItem(), selectAllItem, clearItem);

        // 右键弹出菜单
        setOnContextMenuRequested(e -> {
            copyItem.setDisable(!terminalView.hasSelection());
            copyPasteItem.setDisable(!terminalView.hasSelection());
            clearItem.setDisable(!terminalView.hasSelection());
            contextMenu.show(this, e.getScreenX(), e.getScreenY());
            e.consume();
        });

        // 点击其他位置时隐藏右键菜单
        setOnMousePressed(e -> {
            if (contextMenu.isShowing()) {
                contextMenu.hide();
            }
        });
        // Canvas会拦截鼠标事件，需要在terminalView上也监听
        // 使用addEventHandler而非setOnMousePressed，避免覆盖TerminalView中的选择逻辑
        terminalView.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (contextMenu.isShowing()) {
                contextMenu.hide();
            }
        });
    }

    /**
     * 切换文件浏览器显示
     */
    private void toggleFileBrowser() {
        if (fileBrowserVisible) {
            // 关闭文件浏览器
            fileBrowserVisible = false;
            folderBtn.setStyle("-fx-background-color: transparent; -fx-padding: 2 4; -fx-border-color: transparent; -fx-cursor: hand;");
            folderBtn.setGraphic(createIcon("/images/connect/folder.png", false));
            rebuildRightPanel();
        } else {
            // 打开文件浏览器
            if (sshSession == null || !sshSession.isConnected()) return;
            if (fileBrowser == null) {
                sftpClient = new SFTPClient();
                fileBrowser = new SFTPFileBrowser(sshSession, sftpClient);
            }
            // fileBrowser 设 Vgrow 填满剩余高度，TableView 自带滚动条，不产生外层滚动条
            fileBrowserVisible = true;
            rebuildRightPanel();
            splitPane.setDividerPositions(0.7);
            fileBrowser.initConnection();
            folderBtn.setStyle("-fx-background-color: #e0e0e0; -fx-padding: 2 4; -fx-border-color: transparent; -fx-cursor: hand; -fx-border-radius: 3;");
            folderBtn.setGraphic(createIcon("/images/connect/folder.png", true));
        }
    }

    /**
     * 切换监控视图显示
     */
    private void toggleMonitor() {
        if (monitorVisible) {
            // 关闭监控视图
            if (monitorPanel != null) {
                monitorPanel.stopMonitoring();
            }
            monitorVisible = false;
            monitorBtn.setStyle("-fx-background-color: transparent; -fx-padding: 2 4; -fx-border-color: transparent; -fx-cursor: hand;");
            monitorBtn.setGraphic(createIcon("/images/connect/monitor.png", false));
            rebuildRightPanel();
        } else {
            // 打开监控视图
            if (sshSession == null || !sshSession.isConnected()) return;
            if (monitorPanel == null) {
                monitorPanel = new MonitorPanel(sshSession);
            }
            monitorVisible = true;
            rebuildRightPanel();
            monitorPanel.startMonitoring();
            monitorBtn.setStyle("-fx-background-color: #e0e0e0; -fx-padding: 2 4; -fx-border-color: transparent; -fx-cursor: hand; -fx-border-radius: 3;");
            monitorBtn.setGraphic(createIcon("/images/connect/monitor.png", true));
        }
    }

    /**
     * 切换端口视图显示
     */
    private void togglePort() {
        if (portVisible) {
            // 关闭端口视图
            portVisible = false;
            portBtn.setStyle("-fx-background-color: transparent; -fx-padding: 2 4; -fx-border-color: transparent; -fx-cursor: hand;");
            portBtn.setGraphic(createPortIcon(false));
            rebuildRightPanel();
        } else {
            // 打开端口视图
            if (sshSession == null || !sshSession.isConnected()) return;
            if (portPanel == null) {
                portPanel = new PortPanel(sshSession);
            }
            // portPanel 设 Vgrow 填满剩余高度，TableView 自带滚动条，不产生外层滚动条
            portVisible = true;
            rebuildRightPanel();
            portPanel.refresh();
            portBtn.setStyle("-fx-background-color: #e0e0e0; -fx-padding: 2 4; -fx-border-color: transparent; -fx-cursor: hand; -fx-border-radius: 3;");
            portBtn.setGraphic(createPortIcon(true));
        }
    }

    /**
     * 重建右侧面板布局：按 fileBrowser → monitorPanel → portPanel 顺序排列。
     * - 外层 rightPanelScroll 整体滚动；面板内部不加单独滚动条
     * - monitorPanel 固定 prefHeight；fileBrowser/portPanel 高度随 TableView 内容增长
     * - 仅在 monitorPanel 与相邻面板之间插入 1px #E5E5E5 Region 分隔条，拖拽调整 monitorPanel 高度
     */
    private void rebuildRightPanel() {
        rightPanel.getChildren().clear();

        // 按 fileBrowser → monitorPanel → portPanel 顺序收集可见面板
        java.util.List<javafx.scene.layout.Region> panels = new java.util.ArrayList<>();
        if (fileBrowserVisible && fileBrowser != null) panels.add(fileBrowser);
        if (monitorVisible && monitorPanel != null) panels.add(monitorPanel);
        if (portVisible && portPanel != null) panels.add(portPanel);

        for (int i = 0; i < panels.size(); i++) {
            javafx.scene.layout.Region panel = panels.get(i);
            // 面板不用 Vgrow，高度由内容决定；外层 rightPanelScroll 整体滚动
            javafx.scene.layout.VBox.setVgrow(panel, null);

            // 在 monitorPanel 前后插入可拖拽的高度分隔条
            if (panel == monitorPanel) {
                if (i > 0) {
                    rightPanel.getChildren().add(createHeightDivider(monitorPanel, false));
                }
            } else if (i > 0 && panels.get(i - 1) == monitorPanel) {
                rightPanel.getChildren().add(createHeightDivider(monitorPanel, true));
            }

            rightPanel.getChildren().add(panel);
        }

        // 更新 SplitPane 中右侧面板的可见性
        if (panels.isEmpty()) {
            if (splitPane.getItems().contains(rightPanelScroll)) {
                splitPane.getItems().remove(rightPanelScroll);
            }
        } else if (!splitPane.getItems().contains(rightPanelScroll)) {
            splitPane.getItems().add(rightPanelScroll);
            splitPane.setDividerPositions(panels.size() >= 2 ? 0.4 : 0.6);
        }
    }

    /**
     * 创建高度分隔条：1px #E5E5E5，可垂直拖拽调整 panelToAdjust 的高度（200~800px）。
     * 与项目内 JSON/Hosts 工具的水平分隔条样式一致，仅方向改为垂直。
     *
     * @param panelToAdjust 被调整高度的面板（monitorPanel）
     * @param panelAbove    该面板是否在分隔条上方：
     *                      true=面板在上方，拖拽下移→增大高度；false=面板在下方，拖拽下移→减小高度
     */
    private Region createHeightDivider(javafx.scene.layout.Region panelToAdjust, boolean panelAbove) {
        Region divider = new Region();
        divider.setStyle("-fx-background-color: #E5E5E5;");
        divider.setPrefHeight(1);
        divider.setMaxHeight(1);
        divider.setMinHeight(1);
        divider.setCursor(Cursor.V_RESIZE);

        divider.setOnMousePressed(e -> {
            heightDividerStartY = e.getScreenY();
            heightDividerStartHeight = panelToAdjust.getHeight();
        });

        divider.setOnMouseDragged(e -> {
            double deltaY = e.getScreenY() - heightDividerStartY;
            // 面板在上方：下移增大；面板在下方：下移减小
            double newHeight = panelAbove ? heightDividerStartHeight + deltaY : heightDividerStartHeight - deltaY;
            if (newHeight >= 200 && newHeight <= 800) {
                panelToAdjust.setPrefHeight(newHeight);
            }
        });

        return divider;
    }

    /**
     * 设置回滚行数
     */
    public void setScrollbackLines(int lines) {
        emulator.setMaxScrollback(lines);
    }

    /**
     * 请求终端输入焦点（切换标签时调用）
     */
    public void requestTerminalFocus() {
        Platform.runLater(() -> terminalView.requestFocus());
    }

    /**
     * 连接SSH
     */
    public void connect(String host, int port, String username, String password) throws Exception {
        connect(host, port, username, password, (List<String>) null);
    }

    public void connect(String host, int port, String username, String password, String privateKeyPath) throws Exception {
        connect(host, port, username, password, privateKeyPath != null ? List.of(privateKeyPath) : null);
    }

    public void connect(String host, int port, String username, String password, List<String> privateKeyPaths) throws Exception {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.privateKeyPaths = privateKeyPaths;
        this.connectionLost = false;

        sshSession = new SSHSession(host, port, username, password, privateKeyPaths);
        sshSession.connect();
        running.set(true);

        updateStatusBar("已连接");

        // 启用调试日志（写入用户目录下的terminal_debug.log）
        try {
            String logPath = System.getProperty("user.home") + java.io.File.separator + "terminal_debug.log";
            PrintWriter pw = new PrintWriter(new FileWriter(logPath, false));
            emulator.setDebugWriter(line -> {
                pw.print(line);
                pw.flush();
            });
            emulator.setFileLogger(pw);
            pw.println("=== SSH Terminal Debug Log - " + new java.util.Date() + " ===");
            pw.flush();
        } catch (Exception ignored) {}

        // 通知SSH服务器终端大小
        sshSession.resize(emulator.getCols(), emulator.getRows(),
                (int) terminalView.getCharWidth() * emulator.getCols(),
                (int) terminalView.getCharHeight() * emulator.getRows());

        startReadThread();
        // requestFocus必须在FX线程执行
        Platform.runLater(() -> terminalView.requestFocus());
    }

    /**
     * 更新状态栏
     */
    private void updateStatusBar(String state) {
        Platform.runLater(() -> {
            boolean connected = state.equals("已连接") || state.startsWith("ZModem");
            statusDot.setFill(connected ? Color.valueOf("#4CAF50") : Color.RED);
            stateLabel.setText(state);
            if (host != null) {
                connLabel.setText(username + "@" + host + ":" + port);
            }
        });
    }

    /**
     * 创建图标ImageView
     * @param path 图标资源路径
     * @param active 是否激活状态
     */
    private ImageView createIcon(String path, boolean active) {
        Image image = new Image(getClass().getResourceAsStream(path));
        ImageView iv = new ImageView(image);
        iv.setFitWidth(16);
        iv.setFitHeight(16);
        iv.setOpacity(active ? 1.0 : 0.6);
        return iv;
    }

    /**
     * 创建端口图标（程序化绘制：网络插口样式）
     * @param active 是否激活状态
     */
    private ImageView createPortIcon(boolean active) {
        Canvas canvas = new Canvas(16, 16);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, 16, 16);

        // 外框：网络端口插口
        gc.setStroke(Color.valueOf("#4a90d9"));
        gc.setLineWidth(1.2);
        gc.strokeRoundRect(2, 3, 12, 10, 2, 2);

        // 顶部接口线
        gc.strokeLine(5, 3, 5, 1);
        gc.strokeLine(8, 3, 8, 1);
        gc.strokeLine(11, 3, 11, 1);

        // 内部触点
        gc.setFill(Color.valueOf("#4a90d9"));
        gc.fillRoundRect(4, 6, 8, 4, 1, 1);

        // 底部标识点
        gc.setFill(Color.valueOf("#2d7d46"));
        gc.fillOval(7, 11, 2, 2);

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        Image image = canvas.snapshot(params, null);
        ImageView iv = new ImageView(image);
        iv.setFitWidth(16);
        iv.setFitHeight(16);
        iv.setOpacity(active ? 1.0 : 0.6);
        return iv;
    }

    /**
     * 粘贴剪贴板内容到终端
     */
    private void doPaste() {
        if (sshSession == null || !sshSession.isConnected()) return;
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (clipboard.hasString()) {
            String text = clipboard.getString();
            if (text != null && !text.isEmpty()) {
                // 将换行符转换为回车，适配终端输入
                text = text.replace("\r\n", "\r").replace("\n", "\r");
                // 括号粘贴模式：用\033[200~...\033[201~包裹内容，
                // 让应用程序（如Claude CLI、bash）识别为粘贴而非逐字符输入
                if (terminalView.getEmulator().isBracketedPasteMode()) {
                    text = "\033[200~" + text + "\033[201~";
                }
                try {
                    OutputStream os = sshSession.getOutputStream();
                    if (os != null) {
                        os.write(text.getBytes());
                        os.flush();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                terminalView.clearSelection();
                // 粘贴后自动滚动到底部
                if (!emulator.isUsingAltBuffer() && emulator.getScrollOffset() != 0) {
                    terminalView.setScrollOffset(0);
                }
            }
        }
    }

    /**
     * 断开连接（用户主动关闭标签时调用）
     */
    public void disconnect() {
        running.set(false);
        connectionLost = false;
        terminalView.stopBlink();
        if (zmodem != null) {
            try { zmodem.cancel(); } catch (IOException ignored) {}
        }
        if (readThread != null) {
            readThread.interrupt();
        }
        if (sftpClient != null) {
            sftpClient.disconnect();
        }
        if (sshSession != null) {
            sshSession.disconnect();
            sshSession = null;
        }
        // 关闭文件浏览器
        fileBrowserVisible = false;
        fileBrowser = null;
        sftpClient = null;

        // 关闭监控视图
        if (monitorPanel != null) {
            monitorPanel.stopMonitoring();
        }
        monitorVisible = false;
        monitorPanel = null;

        // 关闭端口视图
        portVisible = false;
        portPanel = null;

        // 清空右侧面板内容并从 SplitPane 移除
        rebuildRightPanel();

        updateStatusBar("已断开");
    }

    /**
     * 重新连接
     */
    private void reconnect() {
        // host必须存在，且（密码存在 或 私钥路径存在），否则无法重连
        if (host == null || (password == null && (privateKeyPaths == null || privateKeyPaths.isEmpty()))) {
            return;
        }
        connectionLost = false;
        updateStatusBar("重新连接中...");

        new Thread(() -> {
            try {
                // 清理旧会话
                if (sftpClient != null) {
                    sftpClient.disconnect();
                }
                if (sshSession != null) {
                    sshSession.disconnect();
                    sshSession = null;
                }
                if (readThread != null) {
                    readThread.interrupt();
                    readThread = null;
                }

                // 走跳板隧道时，重连必须重新解析隧道：旧隧道可能已随SSH断开而失效，
                // 直接复用初始连接缓存的 localhost:旧转发端口 会因本地转发端口无监听而 Connection refused。
                // 回调内部先 peek 复用活跃隧道，失效则 release 旧引用并 resolve 重建（引用计数保持平衡）。
                if (tunnelResolver != null) {
                    int tunnelPort = tunnelResolver.resolve();
                    if (tunnelPort != -1) {
                        host = "localhost";
                        port = tunnelPort;
                    }
                }

                sshSession = new SSHSession(host, port, username, password, privateKeyPaths);
                sshSession.connect();
                running.set(true);

                // 通知SSH服务器终端大小
                sshSession.resize(emulator.getCols(), emulator.getRows(),
                        (int) terminalView.getCharWidth() * emulator.getCols(),
                        (int) terminalView.getCharHeight() * emulator.getRows());

                // 如果文件浏览器打开，重新初始化SFTP
                if (fileBrowserVisible) {
                    sftpClient = new SFTPClient();
                    fileBrowser = new SFTPFileBrowser(sshSession, sftpClient);
                    Platform.runLater(() -> {
                        rebuildRightPanel();
                        splitPane.setDividerPositions(0.7);
                        fileBrowser.initConnection();
                    });
                }

                // 如果端口视图打开，重新绑定新会话
                if (portVisible) {
                    portPanel = new PortPanel(sshSession);
                    Platform.runLater(() -> {
                        rebuildRightPanel();
                        portPanel.refresh();
                    });
                }

                // 如果监控视图打开，重新绑定新会话并重启监控
                if (monitorVisible) {
                    if (monitorPanel != null) {
                        monitorPanel.stopMonitoring();
                    }
                    monitorPanel = new MonitorPanel(sshSession);
                    Platform.runLater(() -> {
                        rebuildRightPanel();
                        monitorPanel.startMonitoring();
                    });
                }

                Platform.runLater(() -> {
                    emulator.process(("\r\n[重新连接成功]\r\n").getBytes());
                    scheduleRender();
                    updateStatusBar("已连接");
                    terminalView.requestFocus();
                });

                startReadThread();
            } catch (Exception e) {
                connectionLost = true;
                Platform.runLater(() -> {
                    emulator.process(("\r\n[重新连接失败: " + e.getMessage() + "]\r\n").getBytes());
                    scheduleRender();
                    updateStatusBar("重连失败 - 按回车重试");
                });
            }
        }, "SSH-Reconnect").start();
    }

    public void setOnDisconnect(Runnable callback) {
        this.onDisconnect = callback;
    }

    /**
     * 注入SSH跳板隧道解析回调，重连时由回调判断是否重建隧道并返回本地转发端口。
     * 仅当连接使用跳板隧道时由连接处理器设置。
     */
    public void setTunnelResolver(TunnelResolver resolver) {
        this.tunnelResolver = resolver;
    }

    /**
     * SSH跳板隧道解析回调：重连时调用，返回本地转发端口；未使用隧道返回 -1。
     * 实现应先 peek 复用活跃隧道，失效时 release 旧引用并 resolve 重建。
     */
    @FunctionalInterface
    public interface TunnelResolver {
        int resolve() throws Exception;
    }

    public boolean isConnected() {
        return sshSession != null && sshSession.isConnected();
    }

    private void startReadThread() {
        readThread = new Thread(() -> {
            byte[] buffer = new byte[4096];

            while (running.get() && sshSession != null && sshSession.isConnected()) {
                try {
                    InputStream is = sshSession.getInputStream();
                    if (is == null) break;

                    int len = is.read(buffer);
                    if (len == -1) break;

                    // 检测ZModem协议前缀
                    int zmodemStart = indexOfZModem(buffer, len);
                    if (zmodemStart != -1 && !inZModemMode) {
                        // 先处理ZModem前缀之前的数据
                        if (zmodemStart > 0) {
                            final byte[] beforeData = new byte[zmodemStart];
                            System.arraycopy(buffer, 0, beforeData, 0, zmodemStart);
                            Platform.runLater(() -> {
                                emulator.process(beforeData);
                                scheduleRender();
                            });
                        }

                        // 解析ZModem帧类型: ** ZDLE B frame_type
                        // frame[5]: 48='0'=sz, 49='1'=rz
                        byte[] frame = new byte[len - zmodemStart];
                        System.arraycopy(buffer, zmodemStart, frame, 0, frame.length);
                        boolean isSz = frame.length > 5 && frame[5] == 48;

                        // 创建ZModem输入流（将帧数据预存到缓冲）
                        ZModemInputStream zmodemIn = new ZModemInputStream(is, frame);

                        if (isSz) {
                            handleSzDownload(zmodemIn, sshSession.getOutputStream());
                        } else {
                            handleRzUpload(zmodemIn, sshSession.getOutputStream());
                        }
                        continue;
                    }

                    // 普通输出，交给终端模拟器处理
                    final byte[] data = new byte[len];
                    System.arraycopy(buffer, 0, data, 0, len);
                    Platform.runLater(() -> {
                        emulator.process(data);
                        scheduleRender();
                    });

                } catch (IOException e) {
                    if (running.get()) {
                        e.printStackTrace();
                    }
                    break;
                }
            }
            running.set(false);

            // 连接丢失，显示提示并等待用户按回车重连
            connectionLost = true;
            Platform.runLater(() -> {
                emulator.process(("\r\n[连接已断开 - 按回车重新连接]\r\n").getBytes());
                scheduleRender();
                updateStatusBar("已断开 - 按回车重连");
                // 主动请求焦点，确保用户按回车时键盘事件能被 terminalView 捕获
                terminalView.requestFocus();
            });
        }, "SSH-Read-Thread");
        readThread.setDaemon(true);
        readThread.start();
    }

    /**
     * 节流渲染，避免每收到一个字节就渲染一次
     */
    private void scheduleRender() {
        long now = System.currentTimeMillis();
        if (now - lastRenderTime >= RENDER_INTERVAL) {
            lastRenderTime = now;
            terminalView.render();
            renderPending = false;
            checkAltBufferState();
        } else if (!renderPending) {
            renderPending = true;
            // 延迟渲染
            javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(
                    javafx.util.Duration.millis(RENDER_INTERVAL - (now - lastRenderTime)));
            delay.setOnFinished(e -> {
                lastRenderTime = System.currentTimeMillis();
                terminalView.render();
                renderPending = false;
                checkAltBufferState();
            });
            delay.play();
        }
    }

    /**
     * 检测交替缓冲区状态变化，更新状态栏显示
     */
    private void checkAltBufferState() {
        boolean altBuffer = emulator.isUsingAltBuffer();
        if (altBuffer != lastAltBufferState) {
            lastAltBufferState = altBuffer;
            if (altBuffer) {
                stateLabel.setText("已连接 [ALT]");
                System.err.println("[Terminal] Status bar: ALT buffer active");
            } else {
                stateLabel.setText("已连接");
                System.err.println("[Terminal] Status bar: MAIN buffer active");
            }
        }
    }

    /**
     * 处理rz上传文件（远端执行rz，本地发送文件给远端）
     */
    private void handleRzUpload(ZModemInputStream zmodemIn, OutputStream outputStream) {
        inZModemMode = true;
        updateStatusBar("ZModem 上传中...");
        Platform.runLater(() -> {
            emulator.process(("\r\n[ZModem] 检测到rz上传请求，请选择要上传的文件...\r\n").getBytes());
            scheduleRender();
        });

        try {
            zmodem = new ZModem(zmodemIn, outputStream);
            zmodem.send(() -> {
                // 等待预选文件结果（rz 命令拦截时已弹出文件选择框）
                List<File> selectedFiles = null;
                if (pendingUploadFuture != null) {
                    try {
                        selectedFiles = pendingUploadFuture.get(30, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        pendingUploadFuture = null;
                    }
                }
                if (selectedFiles == null || selectedFiles.isEmpty()) {
                    // 预选文件不可用（非用户输入rz触发，或超时/取消），回退到弹框选择
                    selectedFiles = openFileDialog();
                }
                if (selectedFiles == null || selectedFiles.isEmpty()) {
                    Platform.runLater(() -> {
                        emulator.process(("\r\n[ZModem] 未选择文件，取消上传\r\n").getBytes());
                        scheduleRender();
                    });
                    return new ArrayList<>();
                }
                Platform.runLater(() -> {
                    emulator.process(("\r\n[ZModem] 正在上传文件...\r\n").getBytes());
                    scheduleRender();
                });
                List<FileAdapter> files = new ArrayList<>();
                for (File f : selectedFiles) {
                    files.add(new CustomFile(f));
                }
                return files;
            });

            Platform.runLater(() -> {
                emulator.process(("\r\n[ZModem] 上传完成\r\n").getBytes());
                scheduleRender();
            });
        } catch (Exception e) {
            Platform.runLater(() -> {
                emulator.process(("\r\n[ZModem] 上传失败: " + e.getMessage() + "\r\n").getBytes());
                scheduleRender();
            });
            e.printStackTrace();
        } finally {
            inZModemMode = false;
            zmodem = null;
            pendingUploadFuture = null;
            updateStatusBar("已连接");
        }
    }

    /**
     * 处理sz下载文件（远端执行sz，本地接收远端文件）
     */
    private void handleSzDownload(ZModemInputStream zmodemIn, OutputStream outputStream) {
        inZModemMode = true;
        updateStatusBar("ZModem 下载中...");
        Platform.runLater(() -> {
            emulator.process(("\r\n[ZModem] 检测到sz下载请求，请选择保存目录...\r\n").getBytes());
            scheduleRender();
        });

        try {
            zmodem = new ZModem(zmodemIn, outputStream);
            File saveDir = openDirDialog();
            if (saveDir == null) {
                Platform.runLater(() -> {
                    emulator.process(("\r\n[ZModem] 未选择保存目录，取消下载\r\n").getBytes());
                    scheduleRender();
                });
                zmodem.cancel();
                return;
            }
            if (!saveDir.exists()) saveDir.mkdirs();

            Platform.runLater(() -> {
                emulator.process(("\r\n[ZModem] 正在下载文件到: " + saveDir.getAbsolutePath() + "\r\n").getBytes());
                scheduleRender();
            });

            zmodem.receive(() -> new CustomFile(saveDir));

            Platform.runLater(() -> {
                emulator.process(("\r\n[ZModem] 下载完成\r\n").getBytes());
                scheduleRender();
            });
        } catch (Exception e) {
            Platform.runLater(() -> {
                emulator.process(("\r\n[ZModem] 下载失败: " + e.getMessage() + "\r\n").getBytes());
                scheduleRender();
            });
            e.printStackTrace();
        } finally {
            inZModemMode = false;
            zmodem = null;
            updateStatusBar("已连接");
        }
    }

    private List<File> openFileDialog() {
        CompletableFuture<List<File>> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                FileChooser fileChooser = new FileChooser();
                fileChooser.setTitle("选择要上传的文件");
                fileChooser.getExtensionFilters().addAll(
                        new FileChooser.ExtensionFilter("所有文件", "*.*")
                );
                List<File> files = fileChooser.showOpenMultipleDialog(getStage());
                future.complete(files != null ? files : List.of());
            } catch (Exception e) {
                future.complete(List.of());
            }
        });
        try {
            return future.get();
        } catch (Exception e) {
            return List.of();
        }
    }

    private File openDirDialog() {
        CompletableFuture<File> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                javafx.stage.DirectoryChooser dirChooser = new javafx.stage.DirectoryChooser();
                dirChooser.setTitle("选择保存目录");
                File dir = dirChooser.showDialog(getStage());
                future.complete(dir);
            } catch (Exception e) {
                future.complete(null);
            }
        });
        try {
            return future.get();
        } catch (Exception e) {
            return null;
        }
    }

    private Stage getStage() {
        return (Stage) getScene().getWindow();
    }

    /**
     * 在buffer中查找ZModem协议前缀位置
     */
    private static int indexOfZModem(byte[] buffer, int len) {
        if (len < ZMODEM_PREFIX.length) return -1;
        for (int i = 0; i <= len - ZMODEM_PREFIX.length; i++) {
            boolean match = true;
            for (int j = 0; j < ZMODEM_PREFIX.length; j++) {
                if ((buffer[i + j] & 0xFF) != ZMODEM_PREFIX[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    /**
     * ZModem输入流，将已读取的帧数据预存到缓冲
     */
    private static class ZModemInputStream extends InputStream {
        private final InputStream input;
        private final List<Byte> buffer;

        public ZModemInputStream(InputStream input, byte[] initialData) {
            this.input = input;
            this.buffer = new ArrayList<>();
            for (byte b : initialData) {
                this.buffer.add(b);
            }
        }

        @Override
        public int read() throws IOException {
            if (!buffer.isEmpty()) {
                return buffer.removeFirst() & 0xFF;
            }
            return input.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (!buffer.isEmpty()) {
                int count = 0;
                while (!buffer.isEmpty() && count < len) {
                    b[off + count] = buffer.removeFirst();
                    count++;
                }
                return count;
            }
            return input.read(b, off, len);
        }
    }

    /**
     * 获取终端模拟器
     */
    public TerminalEmulator getEmulator() {
        return emulator;
    }

    /**
     * 获取终端视图
     */
    public TerminalView getTerminalView() {
        return terminalView;
    }

    /**
     * 更新终端字体并重绘
     */
    public void updateTerminalFont(String family, double size) {
        terminalView.setTerminalFont(family, size);
    }

    /**
     * 导出终端缓冲区内容（调试用）
     */
    public String dumpBuffer() {
        return emulator.dumpBuffer();
    }
}
