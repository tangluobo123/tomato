package com.tangluobo.tomato.ssh;

import javafx.application.Platform;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 本地终端组件，使用 PseudoTerminal + TerminalEmulator + TerminalView。
 *
 * 架构与 SSHTerminalPane 一致：
 * - PseudoTerminal 提供伪终端（Windows ConPTY / Linux-macOS forkpty），shell 在其中以真正的交互模式运行
 * - TerminalEmulator 解析 ANSI 转义序列并维护字符缓冲区
 * - TerminalView 渲染终端画面（Canvas）
 *
 * 这样 telnet/ssh/vim 等需要控制台的交互式程序能正常工作，
 * 且 ANSI 颜色/光标控制能正确渲染。
 */
public class LocalTerminalPane extends BorderPane {

    private final TerminalEmulator emulator;
    private final TerminalView terminalView;
    private PseudoTerminal pty;
    private volatile boolean running = false;
    private Thread readThread;

    // 滚动条
    private final ScrollBar scrollBar;
    private boolean updatingScrollbar = false;

    // 状态栏
    private final Circle statusDot;
    private final Label stateLabel;
    private final Label connLabel;

    // 右键菜单
    private final ContextMenu contextMenu;

    // 渲染节流
    private long lastRenderTime = 0;
    private static final long RENDER_INTERVAL = 33; // ~30fps
    private boolean renderPending = false;

    public LocalTerminalPane() {
        emulator = new TerminalEmulator();
        terminalView = new TerminalView(emulator);
        com.tangluobo.tomato.module.connect.GlobalConfig gcfg = com.tangluobo.tomato.module.connect.GlobalConfig.getInstance();
        terminalView.setTerminalFont(gcfg.getSshTerminalFontName(), gcfg.getSshTerminalFontSize());

        // 调试：输出 emulator 接收的原始数据
        // emulator.setDebugWriter(line -> System.err.print("[EMU-RECV] " + line));

        // 状态栏
        HBox statusBar = new HBox();
        statusBar.setStyle("-fx-background-color: #FFFFFB; -fx-padding: 2 10; -fx-alignment: center-left; -fx-border-color: #e0e0e0; -fx-border-width: 1 0 0 0;");

        statusDot = new Circle(4, Color.GRAY);
        HBox.setMargin(statusDot, new javafx.geometry.Insets(0, 4, 0, 0));

        stateLabel = new Label("未连接");
        stateLabel.setStyle("-fx-text-fill: #333333; -fx-font-size: 11px;");
        HBox.setMargin(stateLabel, new javafx.geometry.Insets(0, 8, 0, 0));

        connLabel = new Label(getShellDisplayName());
        connLabel.setStyle("-fx-text-fill: #333333; -fx-font-size: 11px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label encodingLabel = new Label("UTF-8");
        encodingLabel.setStyle("-fx-text-fill: #333333; -fx-font-size: 11px;");

        statusBar.getChildren().addAll(statusDot, stateLabel, connLabel, spacer, encodingLabel);

        // 终端区域 + 滚动条
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
            if (emulator.isUsingAltBuffer()) return;
            double visibleAmt = scrollBar.getVisibleAmount();
            int scrollbackSize = emulator.getScrollbackSize();
            double val = newVal.doubleValue();
            int offset = (int) Math.round(scrollbackSize - val + visibleAmt - 1);
            terminalView.setScrollOffset(Math.max(0, Math.min(offset, scrollbackSize)));
        });

        Pane terminalPane = new Pane() {
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
        terminalPane.setStyle("-fx-background-color: #1e1e1e; -fx-padding: 0; -fx-border-color: transparent; -fx-border-width: 0;");
        terminalPane.setMaxWidth(Double.MAX_VALUE);
        terminalPane.setMaxHeight(Double.MAX_VALUE);
        terminalPane.setPrefWidth(800);
        terminalPane.setPrefHeight(600);

        // 滚动条回调
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

        setCenter(terminalPane);
        setBottom(statusBar);
        setStyle("-fx-background-color: #1e1e1e; -fx-padding: 0; -fx-border-color: transparent; -fx-border-width: 0;");
        setMaxWidth(Double.MAX_VALUE);
        setMaxHeight(Double.MAX_VALUE);
        setPrefWidth(800);
        setPrefHeight(600);

        // 终端响应回调（DA查询、DSR查询等回传给 PTY）
        emulator.setResponseHandler(data -> {
            if (pty != null && running) {
                try {
                    pty.write(data);
                } catch (IOException e) {
                    // 静默忽略
                }
            }
        });

        // 终端尺寸变化时通知 PTY
        terminalView.setResizeHandler((cols, rows, width, height) -> {
            if (pty != null && running) {
                try {
                    pty.resize(cols, rows);
                } catch (Exception e) {
                    // 静默忽略
                }
            }
        });

        // 粘贴
        terminalView.setPasteHandler(() -> doPaste());

        // 键盘输入 → PTY
        terminalView.setKeyInputHandler(data -> {
            if (pty != null && running) {
                try {
                    pty.write(data);
                } catch (IOException e) {
                    // 静默忽略
                }
            }
        });

        // 右键菜单
        contextMenu = new ContextMenu();
        MenuItem copyItem = new MenuItem("复制");
        MenuItem pasteItem = new MenuItem("粘贴");
        MenuItem copyPasteItem = new MenuItem("复制并粘贴");
        MenuItem clearItem = new MenuItem("清屏");

        copyItem.setOnAction(e -> terminalView.copySelection());
        pasteItem.setOnAction(e -> doPaste());
        copyPasteItem.setOnAction(e -> {
            terminalView.copySelection();
            doPaste();
        });
        clearItem.setOnAction(e -> {
            emulator.process("\033[2J\033[H".getBytes(StandardCharsets.UTF_8));
            scheduleRender();
        });

        contextMenu.getItems().addAll(copyItem, pasteItem, copyPasteItem, new SeparatorMenuItem(), clearItem);
        contextMenu.setOnShowing(e -> {
            copyItem.setDisable(!terminalView.hasSelection());
            copyPasteItem.setDisable(!terminalView.hasSelection());
            pasteItem.setDisable(!Clipboard.getSystemClipboard().hasString());
        });
        terminalView.setOnContextMenuRequested(e -> {
            contextMenu.show(terminalView, e.getScreenX(), e.getScreenY());
            e.consume();
        });

        updateStatusBar("未连接");
    }

    /** 请求终端输入焦点 */
    public void requestTerminalFocus() {
        Platform.runLater(() -> terminalView.requestFocus());
    }

    /** 更新终端字体并重绘 */
    public void updateTerminalFont(String family, double size) {
        terminalView.setTerminalFont(family, size);
    }

    /** 设置回滚行数 */
    public void setScrollbackLines(int lines) {
        emulator.setMaxScrollback(lines);
    }

    /** 根据平台获取终端显示名称 */
    private String getShellDisplayName() {
        String shell = PseudoTerminal.getDefaultShell();
        if (shell == null || shell.isEmpty()) return "Terminal";
        // "powershell.exe -NoProfile" → "PowerShell"
        if (shell.toLowerCase().contains("powershell")) return "PowerShell";
        if (shell.toLowerCase().contains("cmd.exe")) return "CMD";
        // "/bin/bash" → "bash", "/usr/bin/zsh" → "zsh"
        String name = shell.trim();
        int spaceIdx = name.indexOf(' ');
        if (spaceIdx > 0) name = name.substring(0, spaceIdx);
        int slashIdx = name.lastIndexOf('/');
        if (slashIdx >= 0) name = name.substring(slashIdx + 1);
        return name.isEmpty() ? "Terminal" : name;
    }

    /** 连接本地终端 */
    public void connect(String terminalType) {
        // 延迟到下一帧，确保 UI 已布局（Canvas 有正确宽高）
        Platform.runLater(() -> doConnect(terminalType));
    }

    private void doConnect(String terminalType) {
        // 创建跨平台 PTY 实例
        pty = PseudoTerminal.create();
        if (pty == null) {
            String os = System.getProperty("os.name", "");
            emulator.process(("当前平台不支持本地终端: " + os + "\r\n").getBytes(StandardCharsets.UTF_8));
            scheduleRender();
            updateStatusBar("不可用");
            return;
        }
        try {
            String shell = PseudoTerminal.getDefaultShell();
            // emulator.process(("[调试] 正在启动终端: " + shell + "\r\n").getBytes(StandardCharsets.UTF_8));
            // scheduleRender();
            pty.start(shell, emulator.getCols(), emulator.getRows());
            // emulator.process("[调试] 终端启动成功，开始读取\r\n".getBytes(StandardCharsets.UTF_8));
            // scheduleRender();
            running = true;
            startReadThread();
            updateStatusBar("已连接");
            terminalView.requestFocus();
        } catch (IOException e) {
            emulator.process(("[调试] 终端启动失败: " + e.getMessage() + "\r\n").getBytes(StandardCharsets.UTF_8));
            scheduleRender();
            updateStatusBar("启动失败");
        }
    }

    /** 断开连接 */
    public void disconnect() {
        running = false;
        if (pty != null) {
            pty.close();
            pty = null;
        }
        updateStatusBar("已断开");
    }

    /** 读取线程：PTY 输出 → TerminalEmulator */
    private void startReadThread() {
        readThread = new Thread(() -> {
            // 文件日志（确保能看到 read 线程的输出）
            PrintWriter fileLog = null;
            try {
                Path logPath = Path.of(System.getProperty("java.io.tmpdir"), "tomato_terminal_debug.log");
                fileLog = new PrintWriter(Files.newBufferedWriter(logPath,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
                fileLog.println("[READ-THREAD] 启动, time=" + System.currentTimeMillis());
                fileLog.flush();
            } catch (Exception e) {
                // 忽略日志创建失败
            }

            byte[] buffer = new byte[4096];
            try {
                // 等待 shell 启动
                Thread.sleep(200);

                // 检查进程状态
                if (pty != null) {
                    boolean alive = pty.isAlive();
                    int pid = pty.getPid();
                    if (fileLog != null) { fileLog.println("[READ-THREAD] 进程 pid=" + pid + " alive=" + alive); fileLog.flush(); }
                    // Platform.runLater(() -> {
                    //     emulator.process(("[调试] 进程 pid=" + pid + " alive=" + alive + "\r\n").getBytes(StandardCharsets.UTF_8));
                    //     scheduleRender();
                    // });
                }

                int readCount = 0;
                if (fileLog != null) { fileLog.println("[READ-THREAD] 进入读取循环"); fileLog.flush(); }
                while (running && pty != null) {
                    int len = pty.read(buffer);
                    if (fileLog != null && len > 0) {
                        if (readCount < 10) {
                            StringBuilder hex = new StringBuilder();
                            for (int i = 0; i < len && i < 64; i++) {
                                hex.append(String.format("%02x ", buffer[i]));
                            }
                            fileLog.println("[READ-THREAD] read#" + (readCount+1) + " len=" + len + " hex=" + hex);
                        } else {
                            fileLog.println("[READ-THREAD] read#" + (readCount+1) + " len=" + len);
                        }
                        fileLog.flush();
                    }
                    if (len == -1) break;
                    if (len <= 0) continue;
                    readCount++;

                    final byte[] data = new byte[len];
                    System.arraycopy(buffer, 0, data, 0, len);
                    Platform.runLater(() -> {
                        emulator.process(data);
                        scheduleRender();
                    });
                }
                if (fileLog != null) { fileLog.println("[READ-THREAD] 退出读取循环, readCount=" + readCount); fileLog.flush(); }
                final int totalReads = readCount;
                // Platform.runLater(() -> {
                //     emulator.process(("[调试] 读取线程结束, 共读取" + totalReads + "次\r\n").getBytes(StandardCharsets.UTF_8));
                //     scheduleRender();
                // });
            } catch (IOException e) {
                if (fileLog != null) { fileLog.println("[READ-THREAD] IOException: " + e.getMessage()); fileLog.flush(); }
                Platform.runLater(() -> {
                    emulator.process(("[调试] 读取异常: " + e.getMessage() + "\r\n").getBytes(StandardCharsets.UTF_8));
                    scheduleRender();
                });
                if (running) {
                    Platform.runLater(() -> {
                        emulator.process(("\r\n[进程已终止: " + e.getMessage() + "]\r\n").getBytes(StandardCharsets.UTF_8));
                        scheduleRender();
                    });
                }
            } catch (InterruptedException e) {
                // 忽略
            } finally {
                if (fileLog != null) { fileLog.println("[READ-THREAD] 线程结束"); fileLog.flush(); fileLog.close(); }
            }
            running = false;
            Platform.runLater(() -> updateStatusBar("已断开"));
        }, "LocalTerminal-Read");
        readThread.setDaemon(true);
        readThread.start();
    }

    // 调试计数器
    private int renderDebugCount = 0;

    /** 节流渲染，避免每收到一个字节就渲染一次，但保证最后一次总会渲染 */
    private void scheduleRender() {
        terminalView.render();
        // 调试：前10次打印 Canvas 尺寸、emulator 状态和 buffer 第一行内容
        // if (renderDebugCount < 10) {
        //     renderDebugCount++;
        //     double cw = terminalView.getWidth();
        //     double ch = terminalView.getHeight();
        //     int ecols = emulator.getCols();
        //     int erows = emulator.getRows();
        //     int cx = emulator.getCursorX();
        //     int cy = emulator.getCursorY();
        //     boolean alt = emulator.isUsingAltBuffer();
        //     int sb = emulator.getScrollbackSize();
        //     StringBuilder bufStr = new StringBuilder();
        //     for (int y = 0; y < Math.min(3, erows); y++) {
        //         StringBuilder line = new StringBuilder();
        //         for (int x = 0; x < Math.min(60, ecols); x++) {
        //             char c = emulator.getChar(x, y);
        //             line.append(c == '\0' ? '·' : (c == ' ' ? ' ' : c));
        //         }
        //         bufStr.append("  [").append(y).append("] ").append(line).append("\n");
        //     }
        //     System.err.println("[RENDER#" + renderDebugCount + "] canvas=" + (int)cw + "x" + (int)ch +
        //         " emulator=" + ecols + "x" + erows + " cursor=(" + cx + "," + cy + ")" +
        //         " altBuf=" + alt + " scrollback=" + sb + "\n" + bufStr);
        // }
    }

    /** 粘贴剪贴板内容到 ConPTY */
    private void doPaste() {
        Clipboard clipboard = Clipboard.getSystemClipboard();
        if (clipboard.hasString()) {
            String text = clipboard.getString();
            if (text != null && !text.isEmpty()) {
                // 括号粘贴模式：用 \033[200~ ... \033[201~ 包裹
                if (emulator.isBracketedPasteMode()) {
                    text = "\033[200~" + text + "\033[201~";
                }
                if (pty != null && running) {
                    try {
                        pty.write(text.getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        // 静默忽略
                    }
                }
            }
        }
        Platform.runLater(() -> terminalView.clearSelection());
    }

    /** 更新状态栏 */
    private void updateStatusBar(String state) {
        Platform.runLater(() -> {
            stateLabel.setText(state);
            switch (state) {
                case "已连接":
                    statusDot.setFill(Color.GREEN);
                    break;
                case "运行中":
                    statusDot.setFill(Color.ORANGE);
                    break;
                case "已断开":
                case "启动失败":
                case "不可用":
                    statusDot.setFill(Color.RED);
                    break;
                default:
                    statusDot.setFill(Color.GRAY);
                    break;
            }
        });
    }
}
