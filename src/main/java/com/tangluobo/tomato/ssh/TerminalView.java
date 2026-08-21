package com.tangluobo.tomato.ssh;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Point2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.InputMethodEvent;
import javafx.scene.input.InputMethodRequests;
import javafx.scene.input.InputMethodTextRun;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/**
 * 终端视图组件，使用Canvas渲染TerminalEmulator的字符缓冲区
 * Canvas宽高由SSHTerminalPane通过绑定控制
 */
public class TerminalView extends Canvas {

    private final TerminalEmulator emulator;
    private final GraphicsContext gc;

    // 字体设置
    private double charWidth = 8;
    private double charHeight = 16;
    private double fontAscent = 12;
    private String fontFamily = "monospace";
    private double fontSize = 13;

    // 颜色缓存（256色）
    private static final Color[] FX_COLORS = new Color[256];
    static {
        for (int i = 0; i < 256; i++) {
            int rgb = TerminalEmulator.COLOR_TABLE_256[i];
            FX_COLORS[i] = Color.rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        }
    }

    // 默认前景色和背景色
    private Color defaultFg = FX_COLORS[7];
    private Color defaultBg = Color.rgb(0x1e, 0x1e, 0x1e);

    // 键盘输入回调
    private KeyInputHandler keyInputHandler;

    // resize回调（通知SSH服务器终端大小变化）
    private ResizeHandler resizeHandler;

    // 光标闪烁
    private boolean cursorBlinkOn = true;
    private final Timeline cursorBlinkTimer;

    // 文本选择
    // selectionStartRow/selectionEndRow 存储绝对行号（scrollback行0~size-1 + 主缓冲区行size~size+rows-1）
    // 这样选中内容锚定到实际文本行，滚动时高亮跟随内容移动，复制也能取到正确字符
    private int selectionStartCol = -1;
    private int selectionStartRow = -1;
    private int selectionEndCol = -1;
    private int selectionEndRow = -1;
    private boolean isSelecting = false;
    // 记录鼠标按下前是否已有选择，用于避免单击时不必要重绘
    private boolean hadSelectionBeforePress = false;

    // 鼠标拖拽渲染节流（避免每次mouse-dragged都全屏重绘）
    private long lastDragRenderTime = 0;
    private static final long DRAG_RENDER_INTERVAL = 16; // ~60fps
    private boolean dragRenderPending = false;

    // 滚动条回调
    public interface ScrollbarHandler {
        void onScrollChanged(int scrollbackSize, int scrollOffset, int visibleRows);
    }
    private ScrollbarHandler scrollbarHandler;

    public interface KeyInputHandler {
        void handleInput(byte[] data);
    }

    public interface ResizeHandler {
        void onResize(int cols, int rows, int width, int height);
    }

    // 粘贴回调
    public interface PasteHandler {
        void onPaste();
    }
    private PasteHandler pasteHandler;

    // 自动滚动回调（用户输入时触发，滚动到底部）
    public interface AutoScrollHandler {
        void onAutoScroll();
    }
    private AutoScrollHandler autoScrollHandler;

    public TerminalView(TerminalEmulator emulator) {
        this.emulator = emulator;
        this.gc = getGraphicsContext2D();

        // 初始化字体度量
        updateFontMetrics();

        // 键盘事件
        setFocusTraversable(true);
        setOnKeyPressed(this::handleKeyPressed);
        setOnKeyTyped(this::handleKeyTyped);

        // 输入法事件（Linux下fcitx/ibus等输入法通过InputMethodEvent提交中文）
        setOnInputMethodTextChanged(this::handleInputMethodTextChanged);

        // 设置输入法请求处理器（关键：Linux下必须设置此项，输入法才能激活并正确定位候选词窗口）
        // InputMethodRequests 被输入法（fcitx/ibus）调用以获取文本插入点位置等信息
        // 未设置时输入法无法激活，导致终端无法输入中文
        setInputMethodRequests(new InputMethodRequests() {
            @Override
            public Point2D getTextLocation(int offset) {
                // 返回光标在屏幕上的位置，用于输入法候选词窗口定位
                double x = 2 + emulator.getCursorX() * charWidth;
                double y = 2 + emulator.getCursorY() * charHeight + fontAscent;
                Point2D screenPos = localToScreen(x, y);
                return screenPos != null ? screenPos : new Point2D(x, y);
            }

            @Override
            public int getLocationOffset(int x, int y) {
                return 0;
            }

            @Override
            public void cancelLatestCommittedText() {
                // 终端不支持撤销已提交文本，空实现
            }

            @Override
            public String getSelectedText() {
                return "";
            }
        });

        // 鼠标事件处理
        setOnMousePressed(this::handleMousePressed);
        setOnMouseDragged(this::handleMouseDragged);
        setOnMouseReleased(this::handleMouseReleased);
        setOnMouseClicked(this::handleMouseClicked);

        // 鼠标滚轮滚动（回滚历史）
        setOnScroll(this::handleScroll);

        // 光标闪烁定时器，每500ms切换一次
        cursorBlinkTimer = new Timeline(new KeyFrame(Duration.millis(500), e -> {
            cursorBlinkOn = !cursorBlinkOn;
            render();
        }));
        cursorBlinkTimer.setCycleCount(Animation.INDEFINITE);
        cursorBlinkTimer.play();

        // Canvas大小变化时重新计算行列数
        widthProperty().addListener((obs, oldVal, newVal) -> {
            int newCols = (int) (newVal.doubleValue() / charWidth);
            if (newCols > 1 && newCols != emulator.getCols()) {
                emulator.resize(newCols, emulator.getRows());
                render();
                notifyResize();
            }
        });
        heightProperty().addListener((obs, oldVal, newVal) -> {
            int newRows = (int) (newVal.doubleValue() / charHeight);
            if (newRows > 1 && newRows != emulator.getRows()) {
                emulator.resize(emulator.getCols(), newRows);
                render();
                notifyResize();
            }
        });
    }

    /**
     * 关键：让Canvas可被父容器调整大小
     */
    @Override
    public boolean isResizable() {
        return true;
    }

    /**
     * 父容器调整Canvas大小时调用
     */
    @Override
    public void resize(double width, double height) {
        if (width > 0 && height > 0) {
            setWidth(width);
            setHeight(height);
        }
    }

    private void notifyResize() {
        if (resizeHandler != null) {
            resizeHandler.onResize(
                    emulator.getCols(),
                    emulator.getRows(),
                    (int) (emulator.getCols() * charWidth),
                    (int) (emulator.getRows() * charHeight)
            );
        }
    }

    public void setKeyInputHandler(KeyInputHandler handler) {
        this.keyInputHandler = handler;
    }

    public void setResizeHandler(ResizeHandler handler) {
        this.resizeHandler = handler;
    }

    public void setScrollbarHandler(ScrollbarHandler handler) {
        this.scrollbarHandler = handler;
    }

    public void setPasteHandler(PasteHandler handler) {
        this.pasteHandler = handler;
    }

    public void setAutoScrollHandler(AutoScrollHandler handler) {
        this.autoScrollHandler = handler;
    }

    /**
     * 触发自动滚动到底部（当用户有实际输入时调用）
     */
    private void fireAutoScroll() {
        if (autoScrollHandler != null) {
            autoScrollHandler.onAutoScroll();
        }
    }

    /**
     * 由外部滚动条驱动滚动
     */
    public void setScrollOffset(int offset) {
        emulator.setScrollOffset(offset);
        render();
    }

    private void updateFontMetrics() {
        gc.setFont(javafx.scene.text.Font.font(fontFamily, fontSize));
        gc.save();
        javafx.scene.text.Text text = new javafx.scene.text.Text("M");
        text.setFont(javafx.scene.text.Font.font(fontFamily, fontSize));
        charWidth = text.getLayoutBounds().getWidth();
        charHeight = Math.max(fontSize + 5, 18);
        fontAscent = fontSize + 1;
        gc.restore();
    }

    /** 设置终端字体族与字号，会重新计算字符度量并触发重绘 */
    public void setTerminalFont(String family, double size) {
        if (family != null && !family.isBlank()) {
            this.fontFamily = family;
        }
        if (size > 0) {
            this.fontSize = size;
        }
        updateFontMetrics();
        resize((int) (getWidth() / charWidth), (int) (getHeight() / charHeight));
        render();
    }

    public String getFontFamily() {
        return fontFamily;
    }

    public double getFontSize() {
        return fontSize;
    }

    private void handleKeyPressed(KeyEvent event) {
        if (keyInputHandler == null) return;

        // 键盘输入时重置光标闪烁（立即显示光标）
        resetCursorBlink();

        // 用户按键，清除屏幕修改抑制标志（shell已接管终端）
        emulator.onUserInput();

        byte[] data = null;
        if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
            data = "\033".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
            data = "\r".getBytes();
            fireAutoScroll();
        } else if (event.getCode() == javafx.scene.input.KeyCode.BACK_SPACE) {
            data = "\b".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.TAB) {
            data = "\t".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.UP) {
            data = emulator.isApplicationCursorKeys() ? "\033OA".getBytes() : "\033[A".getBytes();
            // 非应用光标键模式（shell模式）下切换历史命令时滚动到底部
            if (!emulator.isApplicationCursorKeys()) fireAutoScroll();
        } else if (event.getCode() == javafx.scene.input.KeyCode.DOWN) {
            data = emulator.isApplicationCursorKeys() ? "\033OB".getBytes() : "\033[B".getBytes();
            // 非应用光标键模式（shell模式）下切换历史命令时滚动到底部
            if (!emulator.isApplicationCursorKeys()) fireAutoScroll();
        } else if (event.getCode() == javafx.scene.input.KeyCode.RIGHT) {
            data = emulator.isApplicationCursorKeys() ? "\033OC".getBytes() : "\033[C".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.LEFT) {
            data = emulator.isApplicationCursorKeys() ? "\033OD".getBytes() : "\033[D".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.HOME) {
            data = emulator.isApplicationCursorKeys() ? "\033OH".getBytes() : "\033[H".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.END) {
            data = emulator.isApplicationCursorKeys() ? "\033OF".getBytes() : "\033[F".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.DELETE) {
            data = "\033[3~".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.PAGE_UP) {
            data = "\033[5~".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.PAGE_DOWN) {
            data = "\033[6~".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.F1) {
            data = "\033OP".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.F2) {
            data = "\033OQ".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.F3) {
            data = "\033OR".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.F4) {
            data = "\033OS".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.C && event.isControlDown() && event.isShiftDown()) {
            // Ctrl+Shift+C: 复制选中文本
            copySelection();
            event.consume();
            return;
        } else if (event.getCode() == javafx.scene.input.KeyCode.V && event.isControlDown() && event.isShiftDown()) {
            // Ctrl+Shift+V: 粘贴剪贴板内容
            if (pasteHandler != null) {
                pasteHandler.onPaste();
            }
            event.consume();
            return;
        } else if (event.getCode() == javafx.scene.input.KeyCode.C && event.isControlDown() && !event.isShiftDown()) {
            data = "\003".getBytes();
            fireAutoScroll();
        } else if (event.getCode() == javafx.scene.input.KeyCode.D && event.isControlDown() && !event.isShiftDown()) {
            data = "\004".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.Z && event.isControlDown()) {
            data = "\032".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.L && event.isControlDown()) {
            data = "\014".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.A && event.isControlDown()) {
            data = "\001".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.E && event.isControlDown()) {
            data = "\005".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.B && event.isControlDown()) {
            data = "\002".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.F && event.isControlDown()) {
            data = "\006".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.N && event.isControlDown()) {
            data = "\016".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.P && event.isControlDown()) {
            data = "\020".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.T && event.isControlDown()) {
            data = "\024".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.Y && event.isControlDown()) {
            data = "\031".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.G && event.isControlDown()) {
            data = "\007".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.O && event.isControlDown()) {
            data = "\017".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.V && event.isControlDown() && !event.isShiftDown()) {
            data = "\026".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.X && event.isControlDown()) {
            data = "\030".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.S && event.isControlDown()) {
            data = "\023".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.Q && event.isControlDown()) {
            data = "\021".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.BACK_SLASH && event.isControlDown()) {
            data = "\034".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.U && event.isControlDown()) {
            data = "\025".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.K && event.isControlDown()) {
            data = "\013".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.W && event.isControlDown()) {
            data = "\027".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.R && event.isControlDown()) {
            data = "\022".getBytes();
        } else if (event.getCode() == javafx.scene.input.KeyCode.D && event.isControlDown() && event.isShiftDown()) {
            // Ctrl+Shift+D: 调试 - 导出终端缓冲区到文件
            try {
                String dump = emulator.dumpBuffer();
                java.nio.file.Files.writeString(
                    java.nio.file.Path.of("/tmp/terminal_dump.txt"),
                    dump + "\n"
                );
                System.out.println("Terminal buffer dumped to /tmp/terminal_dump.txt");
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            event.consume();
            return;
        }

        if (data != null) {
            keyInputHandler.handleInput(data);
            event.consume();
        }
    }

    private void handleKeyTyped(KeyEvent event) {
        if (keyInputHandler == null) return;

        // 键盘输入时重置光标闪烁
        resetCursorBlink();

        // 用户按键，清除屏幕修改抑制标志
        emulator.onUserInput();

        // 过滤所有Ctrl组合键：
        // 1. Ctrl+Shift组合键（如Ctrl+Shift+C/V复制粘贴）
        // 2. 纯Ctrl组合键（如Ctrl+A/E/C）
        // 在Linux/GTK上，handleKeyPressed中event.consume()不能完全阻止handleKeyTyped被触发，
        // 若不过滤，Ctrl+A会产生额外的"A"字符发送给shell，导致光标位置错乱
        // （shell先收到0x01移动光标，再收到"A"插入字符）
        if (event.isControlDown()) {
            event.consume();
            return;
        }

        String ch = event.getCharacter();
        if (ch != null && !ch.isEmpty()) {
            char c = ch.charAt(0);
            if (c >= 0x20 && c != 0x7F) {
                keyInputHandler.handleInput(ch.getBytes());
                fireAutoScroll();
                event.consume();
            }
        }
    }

    /**
     * 输入法事件处理（Linux下fcitx/ibus等输入法通过此事件提交中文）
     * InputMethodEvent携带两类文本：
     * - committed: 已确认提交的文本（如五笔选字后确认的中文字符）
     * - composed: 正在组合的文本（如五笔输入编码时的预编辑文本，终端不显示）
     */
    private void handleInputMethodTextChanged(InputMethodEvent event) {
        if (keyInputHandler == null) return;

        // 重置光标闪烁
        resetCursorBlink();

        // 用户按键（输入法提交），清除屏幕修改抑制标志
        emulator.onUserInput();

        // 处理已提交的文本（输入法确认的中文字符）
        String committed = event.getCommitted();
        if (committed != null && !committed.isEmpty()) {
            keyInputHandler.handleInput(committed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            fireAutoScroll();
            event.consume();
        }
    }

    /**
     * 渲染终端
     */
    public void render() {
        int cols = emulator.getCols();
        int rows = emulator.getRows();
        int scrollOffset = emulator.getScrollOffset();
        // 交替屏幕缓冲区模式下不显示主缓冲区的scrollback
        int scrollbackSize = emulator.isUsingAltBuffer() ? 0 : emulator.getScrollbackSize();
        double x0 = 2;
        double y0 = 2;

        gc.setFill(defaultBg);
        gc.fillRect(0, 0, getWidth(), getHeight());

        gc.setFont(javafx.scene.text.Font.font(fontFamily, fontSize));

        // 复用StringBuilder，避免每个run都创建对象
        StringBuilder segBuf = new StringBuilder(256);

        for (int y = 0; y < rows; y++) {
            double py = y0 + y * charHeight;

            int scrollbackStart = scrollbackSize - scrollOffset;
            int lineInScrollback = scrollbackStart + y;

            char[] lineChars;
            int[] lineAttrs;
            int bufY = -1;
            if (lineInScrollback >= 0 && lineInScrollback < scrollbackSize) {
                lineChars = emulator.getScrollbackLine(lineInScrollback);
                lineAttrs = emulator.getScrollbackAttrLine(lineInScrollback);
                if (lineChars == null) continue;
            } else if (lineInScrollback >= scrollbackSize) {
                bufY = lineInScrollback - scrollbackSize;
                if (bufY >= rows) continue;
                lineChars = null;
                lineAttrs = null;
            } else {
                continue;
            }

            int runStart = 0;
            int firstAttr;
            if (lineChars != null) {
                firstAttr = (lineAttrs != null && lineAttrs.length > 0) ? lineAttrs[0] : 0;
            } else {
                firstAttr = emulator.getAttr(0, bufY);
            }

            for (int x = 0; x <= cols; x++) {
                int attr;
                if (lineChars != null) {
                    attr = (x < cols && x < lineAttrs.length) ? lineAttrs[x] : -1;
                } else {
                    attr = (x < cols) ? emulator.getAttr(x, bufY) : -1;
                }

                if (attr != firstAttr || x == cols) {
                    if (x > runStart) {
                        Color bg;
                        Color fg;
                        if (lineChars != null) {
                            int bgIdx = emulator.getBg(firstAttr);
                            bg = (bgIdx <= 0 || bgIdx >= FX_COLORS.length) ? defaultBg : FX_COLORS[bgIdx];
                            int fgIdx = emulator.getFg(firstAttr);
                            fg = (fgIdx >= 0 && fgIdx < FX_COLORS.length) ? FX_COLORS[fgIdx] : defaultFg;
                            if (emulator.isReverse(firstAttr)) {
                                Color tmp = fg; fg = bg; bg = tmp;
                            }
                        } else {
                            bg = getRenderBg(firstAttr, runStart, bufY);
                            fg = getRenderFg(firstAttr, runStart, bufY);
                        }
                        gc.setFill(bg);
                        gc.fillRect(x0 + runStart * charWidth, py, (x - runStart) * charWidth, charHeight);

                        gc.setFill(fg);
                        // 批量绘制：将连续字符拼接为字符串一次绘制，宽字符占位符处分段
                        int segStart = runStart;
                        segBuf.setLength(0);
                        for (int i = runStart; i < x; i++) {
                            char c;
                            if (lineChars != null) {
                                c = (i < lineChars.length) ? lineChars[i] : ' ';
                            } else {
                                c = emulator.getChar(i, bufY);
                            }
                            if (c == '\0') {
                                // 宽字符占位符：先输出已累积的字符串
                                if (segBuf.length() > 0) {
                                    gc.fillText(segBuf.toString(), x0 + segStart * charWidth, py + fontAscent);
                                    segBuf.setLength(0);
                                }
                                segStart = i + 1;
                                continue;
                            }
                            if (segBuf.length() == 0) {
                                segStart = i;
                            }
                            segBuf.append(c);
                        }
                        if (segBuf.length() > 0) {
                            gc.fillText(segBuf.toString(), x0 + segStart * charWidth, py + fontAscent);
                        }
                    }
                    runStart = x;
                    firstAttr = attr;
                }
            }
        }

        // 渲染光标（使用反转色确保在任何背景下都可见）
        if (emulator.isCursorVisible() && cursorBlinkOn && scrollOffset == 0) {
            int curX = emulator.getCursorX();
            int curY = emulator.getCursorY();
            char cursorChar = emulator.getChar(curX, curY);
            int cursorCol = curX;
            if (cursorChar == '\0' && curX > 0) {
                cursorCol = curX - 1;
                cursorChar = emulator.getChar(cursorCol, curY);
            }
            double cx = x0 + cursorCol * charWidth;
            double cy = y0 + curY * charHeight;
            double cursorWidth = (cursorChar != '\0' && emulator.isWideChar(cursorChar)) ? 2 * charWidth : charWidth;

            // 获取光标位置单元格的前景色和背景色，反转后作为光标颜色
            int cursorAttr = emulator.getAttr(cursorCol, curY);
            Color cursorBg = getAttrFg(cursorAttr, cursorCol, curY);
            Color cursorFg = getAttrBg(cursorAttr, cursorCol, curY);
            // 如果前景和背景相同（极端情况），使用默认的黑白反转
            if (cursorBg.equals(cursorFg)) {
                cursorBg = Color.WHITE;
                cursorFg = Color.BLACK;
            }
            gc.setFill(cursorBg);
            gc.fillRect(cx, cy, cursorWidth, charHeight);
            gc.setFill(cursorFg);
            gc.fillText(String.valueOf(cursorChar == '\0' ? ' ' : cursorChar), cx, cy + fontAscent);
        }

        // 渲染选择高亮
        if (hasSelection()) {
            int startRow, endRow, startCol, endCol;
            if (selectionStartRow < selectionEndRow ||
                (selectionStartRow == selectionEndRow && selectionStartCol <= selectionEndCol)) {
                startRow = selectionStartRow;
                startCol = selectionStartCol;
                endRow = selectionEndRow;
                endCol = selectionEndCol;
            } else {
                startRow = selectionEndRow;
                startCol = selectionEndCol;
                endRow = selectionStartRow;
                endCol = selectionStartCol;
            }

            gc.setFill(Color.rgb(0x42, 0x85, 0xF4, 0.5)); // 蓝色半透明
            // startRow/endRow 为绝对行号，需转为屏幕行号渲染；只绘制可见范围内的行
            for (int absRow = startRow; absRow <= endRow; absRow++) {
                int screenRow = absoluteRowToScreen(absRow);
                if (screenRow < 0) continue; // 不在可见范围，跳过
                int lineStart = (absRow == startRow) ? startCol : 0;
                int lineEnd = (absRow == endRow) ? endCol : cols - 1;
                // 限制高亮范围不超过行的实际内容末尾，不延伸到行尾填充空格
                int contentEnd = -1;
                for (int c = cols - 1; c >= 0; c--) {
                    char ch = getCharAtAbsolute(c, absRow);
                    if (ch != ' ' && ch != '\0') {
                        contentEnd = c;
                        break;
                    }
                }
                if (contentEnd < lineStart) continue; // 选择范围内无实际内容
                if (lineEnd > contentEnd) lineEnd = contentEnd;
                double sx = x0 + lineStart * charWidth;
                double sy = y0 + screenRow * charHeight;
                double sw = (lineEnd - lineStart + 1) * charWidth;
                gc.fillRect(sx, sy, sw, charHeight);
            }
        }

        // 通知滚动条更新
        notifyScrollbar();
    }

    /**
     * 获取指定单元格的前景色（支持256色、真彩色和反转视频）
     */
    private Color getAttrFg(int attr, int x, int y) {
        // 真彩色优先
        if (emulator.isFgTrueColor(attr)) {
            int rgb = emulator.getFgTrueColor(x, y);
            if (rgb >= 0) {
                return Color.rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
            }
        }
        int fgIdx = emulator.getFg(attr);
        if (fgIdx < 0 || fgIdx >= FX_COLORS.length) return defaultFg;
        return FX_COLORS[fgIdx];
    }

    /**
     * 获取指定单元格的背景色（支持256色、真彩色和反转视频）
     */
    private Color getAttrBg(int attr, int x, int y) {
        // 真彩色优先
        if (emulator.isBgTrueColor(attr)) {
            int rgb = emulator.getBgTrueColor(x, y);
            if (rgb >= 0) {
                return Color.rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
            }
        }
        int bgIdx = emulator.getBg(attr);
        if (bgIdx < 0 || bgIdx >= FX_COLORS.length || bgIdx == 0) return defaultBg;
        return FX_COLORS[bgIdx];
    }

    /**
     * 获取渲染用的前景色（考虑反转视频属性）
     */
    private Color getRenderFg(int attr, int x, int y) {
        Color fg = getAttrFg(attr, x, y);
        if (emulator.isReverse(attr)) {
            Color bg = getAttrBg(attr, x, y);
            fg = bg;
        }
        return fg;
    }

    /**
     * 获取渲染用的背景色（考虑反转视频属性）
     */
    private Color getRenderBg(int attr, int x, int y) {
        Color bg = getAttrBg(attr, x, y);
        if (emulator.isReverse(attr)) {
            Color fg = getAttrFg(attr, x, y);
            bg = fg;
        }
        return bg;
    }

    public TerminalEmulator getEmulator() {
        return emulator;
    }

    public double getCharWidth() {
        return charWidth;
    }

    public double getCharHeight() {
        return charHeight;
    }

    /**
     * 重置光标闪烁，立即显示光标
     */
    private void resetCursorBlink() {
        cursorBlinkOn = true;
        cursorBlinkTimer.stop();
        cursorBlinkTimer.play();
    }

    /**
     * 停止光标闪烁（断开连接时调用）
     */
    public void stopBlink() {
        cursorBlinkTimer.stop();
    }

    // ==================== 文本选择功能 ====================

    /**
     * 将鼠标像素坐标转换为字符坐标
     */
    private int mouseToCol(double mouseX) {
        int col = (int) ((mouseX - 2) / charWidth);
        return Math.max(0, Math.min(col, emulator.getCols() - 1));
    }

    private int mouseToRow(double mouseY) {
        int row = (int) ((mouseY - 2) / charHeight);
        return Math.max(0, Math.min(row, emulator.getRows() - 1));
    }

    /**
     * 屏幕行号转绝对行号
     * 绝对行号：0~scrollbackSize-1 为scrollback历史行，scrollbackSize~scrollbackSize+rows-1 为当前主缓冲区行
     */
    private int screenRowToAbsolute(int screenRow) {
        int scrollbackSize = emulator.isUsingAltBuffer() ? 0 : emulator.getScrollbackSize();
        int scrollOffset = emulator.getScrollOffset();
        return (scrollbackSize - scrollOffset) + screenRow;
    }

    /**
     * 绝对行号转屏幕行号，不在可见范围内返回-1
     */
    private int absoluteRowToScreen(int absoluteRow) {
        int scrollbackSize = emulator.isUsingAltBuffer() ? 0 : emulator.getScrollbackSize();
        int scrollOffset = emulator.getScrollOffset();
        int screenRow = absoluteRow - (scrollbackSize - scrollOffset);
        if (screenRow < 0 || screenRow >= emulator.getRows()) return -1;
        return screenRow;
    }

    /**
     * 按绝对行号取字符（自动区分scrollback和主缓冲区）
     */
    private char getCharAtAbsolute(int col, int absoluteRow) {
        int scrollbackSize = emulator.isUsingAltBuffer() ? 0 : emulator.getScrollbackSize();
        if (absoluteRow < scrollbackSize) {
            char[] line = emulator.getScrollbackLine(absoluteRow);
            if (line == null || col >= line.length) return ' ';
            return line[col];
        } else {
            int bufY = absoluteRow - scrollbackSize;
            if (bufY < 0 || bufY >= emulator.getRows()) return ' ';
            return emulator.getChar(col, bufY);
        }
    }

    /**
     * 绝对行号所在的行是否为宽字符主cell的占位符
     */
    private boolean isWideCharAtAbsolute(int col, int absoluteRow) {
        char c = getCharAtAbsolute(col, absoluteRow);
        return emulator.isWideChar(c);
    }

    private void handleMousePressed(MouseEvent e) {
        requestFocus();
        if (e.getButton() == MouseButton.PRIMARY) {
            // 双击/三击时不在此处重置选择，由handleMouseClicked处理
            if (e.getClickCount() > 1) return;
            isSelecting = true;
            // 记录按下前是否有选择，用于决定是否需要重绘清除旧高亮
            hadSelectionBeforePress = hasSelection();
            selectionStartCol = mouseToCol(e.getX());
            selectionStartRow = screenRowToAbsolute(mouseToRow(e.getY()));
            selectionEndCol = selectionStartCol;
            selectionEndRow = selectionStartRow;
            // 仅当之前有选择高亮时才需要重绘清除；无选择时单击不触发渲染
            if (hadSelectionBeforePress) render();
        }
    }

    private void handleMouseDragged(MouseEvent e) {
        if (isSelecting && e.getButton() == MouseButton.PRIMARY) {
            selectionEndCol = mouseToCol(e.getX());
            selectionEndRow = screenRowToAbsolute(mouseToRow(e.getY()));
            // 节流渲染：限制重绘频率为~60fps，避免鼠标高频移动导致卡顿
            scheduleDragRender();
        }
    }

    /**
     * 鼠标拖拽时节流渲染：合并高频mouse-dragged事件的渲染请求
     */
    private void scheduleDragRender() {
        long now = System.currentTimeMillis();
        if (now - lastDragRenderTime >= DRAG_RENDER_INTERVAL) {
            lastDragRenderTime = now;
            render();
            dragRenderPending = false;
        } else if (!dragRenderPending) {
            dragRenderPending = true;
            javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(
                    javafx.util.Duration.millis(DRAG_RENDER_INTERVAL - (now - lastDragRenderTime)));
            delay.setOnFinished(ev -> {
                lastDragRenderTime = System.currentTimeMillis();
                render();
                dragRenderPending = false;
            });
            delay.play();
        }
    }

    private void handleMouseReleased(MouseEvent e) {
        if (isSelecting && e.getButton() == MouseButton.PRIMARY) {
            selectionEndCol = mouseToCol(e.getX());
            selectionEndRow = screenRowToAbsolute(mouseToRow(e.getY()));
            isSelecting = false;
            // 如果起点和终点相同，视为单击，清除选择
            if (selectionStartCol == selectionEndCol && selectionStartRow == selectionEndRow) {
                // 单击：若之前无选择则mousePressed未渲染，此处也无需渲染；
                // 若之前有选择则mousePressed已渲染清除旧高亮，此处只需重置坐标
                selectionStartCol = -1;
                selectionStartRow = -1;
                selectionEndCol = -1;
                selectionEndRow = -1;
            } else {
                // 拖拽选择完成，确保最终渲染（取消任何pending的节流渲染）
                dragRenderPending = false;
                render();
            }
        }
    }

    private void handleMouseClicked(MouseEvent e) {
        requestFocus();
        if (e.getButton() != MouseButton.PRIMARY) return;

        int col = mouseToCol(e.getX());
        int absRow = screenRowToAbsolute(mouseToRow(e.getY()));
        int cols = emulator.getCols();

        if (e.getClickCount() == 2) {
            // 双击选中单词（以不可见字符为分隔）
            int startCol = col;
            int endCol = col;
            // 如果点击在宽字符占位符(\0)上，回退到宽字符主cell
            if (getCharAtAbsolute(startCol, absRow) == '\0' && startCol > 0) {
                startCol--;
                endCol = startCol;
            }
            // 向左查找单词边界
            while (startCol > 0 && isWordChar(getCharAtAbsolute(startCol - 1, absRow))) {
                startCol--;
            }
            // 向右查找单词边界
            while (endCol < cols - 1 && isWordChar(getCharAtAbsolute(endCol + 1, absRow))) {
                endCol++;
            }
            // 确保选中范围包含完整的宽字符（如果endCol停在宽字符主cell上，需要包含其占位符）
            if (isWideCharAtAbsolute(endCol, absRow) && endCol + 1 < cols) {
                endCol++;
            }
            selectionStartRow = absRow;
            selectionStartCol = startCol;
            selectionEndRow = absRow;
            selectionEndCol = endCol;
            isSelecting = false;
            render();
        } else if (e.getClickCount() == 3) {
            // 三击选中整行
            selectionStartRow = absRow;
            selectionStartCol = 0;
            selectionEndRow = absRow;
            selectionEndCol = cols - 1;
            isSelecting = false;
            render();
        }
    }

    /**
     * 判断字符是否为单词字符（用于双击选词）
     * 宽字符占位符(\0)视为单词字符（属于宽字符的延续部分）
     * 空格、制表符等空白字符和控制字符作为单词分隔符
     */
    private boolean isWordChar(char c) {
        if (c == '\0') return true; // 宽字符占位符，属于宽字符的一部分
        return !Character.isWhitespace(c) && c > 0x1F && c != 0x7F;
    }

    /**
     * 鼠标滚轮滚动回滚历史
     */
    private void handleScroll(ScrollEvent e) {
        // 交替屏幕缓冲区模式下不允许滚动scrollback
        if (emulator.isUsingAltBuffer()) return;

        int scrollbackSize = emulator.getScrollbackSize();
        if (scrollbackSize == 0) return;

        int delta = (int) e.getDeltaY();
        if (delta == 0) return;

        // 标准化滚动量
        int lines = Math.max(1, Math.abs(delta / 40));
        int oldOffset = emulator.getScrollOffset();
        int newOffset;
        if (delta > 0) {
            // 滚轮向上（回看更早的历史）
            newOffset = Math.min(oldOffset + lines, scrollbackSize);
        } else {
            // 滚轮向下（回到最新的输出）
            newOffset = Math.max(oldOffset - lines, 0);
        }

        if (newOffset != oldOffset) {
            emulator.setScrollOffset(newOffset);
            render();
            notifyScrollbar();
        }
    }

    private void notifyScrollbar() {
        if (scrollbarHandler != null) {
            // 交替屏幕缓冲区模式下报告scrollback为0，使滚动条隐藏
            int sbSize = emulator.isUsingAltBuffer() ? 0 : emulator.getScrollbackSize();
            int sbOffset = emulator.isUsingAltBuffer() ? 0 : emulator.getScrollOffset();
            // 只在值变化时通知，避免循环
            if (sbSize != lastNotifiedScrollbackSize || sbOffset != lastNotifiedScrollOffset) {
                lastNotifiedScrollbackSize = sbSize;
                lastNotifiedScrollOffset = sbOffset;
                scrollbarHandler.onScrollChanged(sbSize, sbOffset, emulator.getRows());
            }
        }
    }

    // 上次通知的值，避免重复通知
    private int lastNotifiedScrollbackSize = -1;
    private int lastNotifiedScrollOffset = -1;

    /**
     * 是否有选中文本
     */
    public boolean hasSelection() {
        return selectionStartCol >= 0 && selectionStartRow >= 0
                && selectionEndCol >= 0 && selectionEndRow >= 0
                && (selectionStartCol != selectionEndCol || selectionStartRow != selectionEndRow);
    }

    /**
     * 获取选中的文本
     */
    public String getSelectedText() {
        if (!hasSelection()) return "";

        int cols = emulator.getCols();
        int startRow, endRow, startCol, endCol;

        if (selectionStartRow < selectionEndRow ||
            (selectionStartRow == selectionEndRow && selectionStartCol <= selectionEndCol)) {
            startRow = selectionStartRow;
            startCol = selectionStartCol;
            endRow = selectionEndRow;
            endCol = selectionEndCol;
        } else {
            startRow = selectionEndRow;
            startCol = selectionEndCol;
            endRow = selectionStartRow;
            endCol = selectionStartCol;
        }

        StringBuilder sb = new StringBuilder();
        for (int row = startRow; row <= endRow; row++) {
            int lineStart = (row == startRow) ? startCol : 0;
            int lineEnd = (row == endRow) ? endCol : cols - 1;
            int lineLenBefore = sb.length();
            for (int col = lineStart; col <= lineEnd; col++) {
                char c = getCharAtAbsolute(col, row);
                if (c == '\0') continue; // 跳过宽字符占位符
                sb.append(c);
            }
            // 去除行末填充空格（终端buffer用空格填充到列宽，复制时不应包含）
            int end = sb.length();
            while (end > lineLenBefore && sb.charAt(end - 1) == ' ') {
                end--;
            }
            sb.delete(end, sb.length());
            if (row < endRow) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 全选（覆盖scrollback历史行 + 当前主缓冲区所有行）
     */
    public void selectAll() {
        int scrollbackSize = emulator.isUsingAltBuffer() ? 0 : emulator.getScrollbackSize();
        selectionStartRow = 0;
        selectionStartCol = 0;
        selectionEndRow = scrollbackSize + emulator.getRows() - 1;
        selectionEndCol = emulator.getCols() - 1;
        render();
    }

    /**
     * 清除选择
     */
    public void clearSelection() {
        selectionStartCol = -1;
        selectionStartRow = -1;
        selectionEndCol = -1;
        selectionEndRow = -1;
        isSelecting = false;
        render();
    }

    /**
     * 复制选中文本到系统剪贴板
     */
    public void copySelection() {
        String text = getSelectedText();
        if (!text.isEmpty()) {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            clipboard.setContent(content);
        }
    }
}
