package test;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.event.Event;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 直接在终端区域输入命令的 PowerShell 模拟器，类似真实打开的 PowerShell 窗口。
 */
public class PowerShellTerminal extends Application {

    private TextArea terminalArea;
    private Process powerShellProcess;
    private BufferedWriter processWriter;
    private BufferedReader processReader;
    private final List<String> commandHistory = new ArrayList<>();
    private int historyIndex = 0;
    private volatile boolean historyPending = false;
    private String currentWorkingDir = System.getProperty("user.home");
    /** Tab 补全进行中标志，避免并发请求 */
    private volatile boolean completionPending = false;
    /** 命令是否正在运行（用于 Ctrl+C 判断是否需要终止进程） */
    private volatile boolean commandRunning = false;
    /** 当前 Tab 补全会话：记录候选列表、索引、触发时的输入前缀，用于循环切换 */
    private List<String> tabCandidates = new ArrayList<>();
    private int tabIndex = -1;
    private String tabPrefix = null;

    /** 提示符固定前缀，用户无法编辑该区域之前的内容 */
    private int promptStart = 0;

    @Override
    public void start(Stage primaryStage) {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #1e1e1e;");

        terminalArea = new TextArea();
        terminalArea.setFont(Font.font("Consolas", 14));
        terminalArea.setWrapText(true);
        terminalArea.setStyle(
            "-fx-control-inner-background: #1e1e1e; " +
            "-fx-text-fill: #d4d4d4; " +
            "-fx-border-color: transparent; " +
            "-fx-background-color: #1e1e1e;"
        );

        root.setCenter(terminalArea);

        Scene scene = new Scene(root, 900, 600);
        primaryStage.setTitle("PowerShell");
        primaryStage.setScene(scene);
        primaryStage.show();

        initializeTerminal();
        setupEventHandlers();

        // 确保 TextArea 获得焦点，否则键盘事件不会到达
        terminalArea.requestFocus();

        primaryStage.setOnCloseRequest(e -> {
            if (powerShellProcess != null) {
                powerShellProcess.destroyForcibly();
            }
            Platform.exit();
        });
    }

    private void initializeTerminal() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                "powershell.exe",
                "-NoLogo",
                "-NoProfile",
                "-Command",
                "-"
            );
            processBuilder.redirectErrorStream(true);
            processBuilder.directory(new File(currentWorkingDir));

            powerShellProcess = processBuilder.start();
            processWriter = new BufferedWriter(
                new OutputStreamWriter(powerShellProcess.getOutputStream())
            );
            processReader = new BufferedReader(
                new InputStreamReader(powerShellProcess.getInputStream())
            );

            Thread readerThread = new Thread(this::readProcessOutput);
            readerThread.setDaemon(true);
            readerThread.start();

            // 显示欢迎信息
            terminalArea.appendText("Windows PowerShell\n");
            terminalArea.appendText("版权所有 (C) Microsoft Corporation。保留所有权利。\n");
            terminalArea.appendText("安装最新的 PowerShell，了解新功能和改进！https://aka.ms/PSWindows\n");
            terminalArea.appendText("\n");
            showPrompt();

        } catch (IOException e) {
            terminalArea.appendText("无法启动 PowerShell: " + e.getMessage() + "\n");
            terminalArea.appendText("请确保 PowerShell 已安装并在系统路径中。\n");
        }
    }

    /** 显示新的命令提示符，并锁定光标位置 */
    private void showPrompt() {
        terminalArea.appendText("\nPS " + currentWorkingDir + "> ");
        promptStart = terminalArea.getText().length();
        terminalArea.positionCaret(promptStart);
    }

    /** 读取 PowerShell 输出并追加到终端 */
    private void readProcessOutput() {
        try {
            char[] buf = new char[1024];
            int n;
            while ((n = processReader.read(buf)) != -1) {
                final String output = new String(buf, 0, n);
                Platform.runLater(() -> {
                    terminalArea.appendText(output);
                    // 命令执行完毕后重新显示提示符
                    promptStart = terminalArea.getText().length();
                    terminalArea.positionCaret(promptStart);
                    // PowerShell 输出结束，标记命令运行结束
                    commandRunning = false;
                });
            }
        } catch (IOException e) {
            Platform.runLater(() -> terminalArea.appendText("\n[PowerShell 进程已终止: " + e.getMessage() + "]\n"));
        }
    }

    private void setupEventHandlers() {
        // 用事件过滤器在捕获阶段拦截所有特殊键，避免 TextArea 执行默认行为
        terminalArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            KeyCode code = event.getCode();
            if (code == KeyCode.TAB) {
                event.consume();
                requestTabCompletion();
            } else if (code == KeyCode.UP) {
                event.consume();
                navigateHistory(-1);
            } else if (code == KeyCode.DOWN) {
                event.consume();
                navigateHistory(1);
            } else if (code == KeyCode.ENTER) {
                event.consume();
                handleEnter();
            } else if (code == KeyCode.C && event.isControlDown()) {
                event.consume();
                handleCtrlC();
            } else if (code == KeyCode.BACK_SPACE) {
                if (terminalArea.getCaretPosition() <= promptStart) {
                    event.consume();
                }
            } else if (code == KeyCode.DELETE) {
                if (terminalArea.getCaretPosition() < promptStart) {
                    event.consume();
                }
            } else if (code == KeyCode.LEFT || code == KeyCode.HOME) {
                if (terminalArea.getCaretPosition() <= promptStart) {
                    event.consume();
                    terminalArea.positionCaret(promptStart);
                }
            } else if (terminalArea.getCaretPosition() < promptStart && !event.isControlDown()) {
                event.consume();
                terminalArea.positionCaret(promptStart);
            }
        });
        terminalArea.setOnKeyTyped(this::handleKeyTyped);
    }

    /** 回车处理：提取命令并执行 */
    private void handleEnter() {
        String text = terminalArea.getText();
        String command = text.substring(promptStart).replace("\n", "").trim();
        terminalArea.appendText("\n");
        if (!command.isEmpty()) {
            executeCommand(command);
        } else {
            showPrompt();
        }
    }

    /** 拦截普通字符输入，禁止编辑提示符之前的内容 */
    private void handleKeyTyped(KeyEvent event) {
        // 拦截 Tab 字符，避免插入缩进
        if ("\t".equals(event.getCharacter())) {
            event.consume();
            return;
        }
        int caret = terminalArea.getCaretPosition();
        if (caret < promptStart) {
            // 不允许在提示符之前输入
            event.consume();
            terminalArea.positionCaret(promptStart);
        }
    }

    /** Ctrl+C 处理：取消正在运行的命令，或清除当前输入行 */
    private void handleCtrlC() {
        if (commandRunning) {
            // 有命令在运行：杀掉 PowerShell 进程并重新启动
            if (powerShellProcess != null && powerShellProcess.isAlive()) {
                powerShellProcess.destroyForcibly();
            }
            commandRunning = false;
            terminalArea.appendText("^C\n");
            // 重新初始化 PowerShell 进程
            initializeTerminal();
        } else {
            // 无命令运行：清除当前输入行，重新显示提示符
            terminalArea.appendText("^C\n");
            // 清空当前 Tab 补全会话
            tabCandidates.clear();
            tabIndex = -1;
            tabPrefix = null;
            showPrompt();
        }
    }

    private void executeCommand(String command) {
        // 保存到历史
        commandHistory.add(command);
        historyIndex = commandHistory.size();

        // 处理内部命令
        if (command.equalsIgnoreCase("exit") || command.equalsIgnoreCase("quit")) {
            terminalArea.appendText("正在退出...\n");
            if (powerShellProcess != null) {
                powerShellProcess.destroyForcibly();
            }
            Platform.exit();
            return;
        }

        if (command.equalsIgnoreCase("clear") || command.equalsIgnoreCase("cls")) {
            terminalArea.clear();
            showPrompt();
            return;
        }

        if (command.toLowerCase().startsWith("cd ")) {
            String path = command.substring(3).trim();
            if (path.startsWith("\"") && path.endsWith("\"")) {
                path = path.substring(1, path.length() - 1);
            }
            File newDir = new File(path);
            if (!newDir.isAbsolute()) {
                newDir = new File(currentWorkingDir, path);
            }
            if (newDir.exists() && newDir.isDirectory()) {
                currentWorkingDir = newDir.getAbsolutePath();
                if (powerShellProcess != null) {
                    powerShellProcess.destroyForcibly();
                    initializeTerminal();
                }
            } else {
                terminalArea.appendText("找不到路径 '" + path + "'，请确认路径是否正确。\n");
                showPrompt();
            }
            return;
        }

        // 发送命令到 PowerShell
        try {
            if (processWriter != null) {
                processWriter.write(command);
                processWriter.newLine();
                processWriter.flush();
                commandRunning = true;
                // 追加到 PSReadLine 历史文件，与真实 PowerShell 共享历史
                appendToHistoryFile(command);
            }
        } catch (IOException e) {
            terminalArea.appendText("执行命令时出错: " + e.getMessage() + "\n");
            showPrompt();
        }
        // 提示符由输出读取线程在命令完成后显示
    }

    /** 把命令追加到 PSReadLine 历史文件，与真实 PowerShell 控制台共享历史 */
    private void appendToHistoryFile(String command) {
        try {
            String appData = System.getenv("APPDATA");
            if (appData == null) {
                return;
            }
            File histDir = new File(appData,
                "Microsoft\\Windows\\PowerShell\\PSReadLine");
            if (!histDir.exists()) {
                histDir.mkdirs();
            }
            File histFile = new File(histDir, "ConsoleHost_history.txt");
            try (java.io.Writer w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(histFile, true), "UTF-8")) {
                w.write(command);
                w.write("\r\n");
            }
        } catch (Exception e) {
            // 忽略
        }
    }

    /** Tab 补全：若当前输入命中已有候选会话则循环切换，否则发起新请求 */
    private void requestTabCompletion() {
        if (completionPending) {
            return; // 已有补全在进行中
        }
        String currentInput = terminalArea.getText().substring(promptStart);
        if (currentInput.isEmpty()) {
            return;
        }
        // 若当前输入正好是上次候选之一，说明是循环切换
        if (tabCandidates != null && !tabCandidates.isEmpty()
                && tabCandidates.contains(currentInput)) {
            int idx = tabCandidates.indexOf(currentInput);
            int next = (idx + 1) % tabCandidates.size();
            replaceCurrentInput(tabCandidates.get(next));
            tabIndex = next;
            return;
        }
        // 否则发起新的补全请求
        tabCandidates.clear();
        tabIndex = -1;
        tabPrefix = null;

        int caretInInput = terminalArea.getCaretPosition() - promptStart;
        if (caretInInput < 0) caretInInput = 0;
        if (caretInInput > currentInput.length()) caretInInput = currentInput.length();

        completionPending = true;
        final String inputSnapshot = currentInput;
        final int cursorSnapshot = caretInInput;

        Thread t = new Thread(() -> {
            List<String> completions = fetchCompletions(inputSnapshot, cursorSnapshot);
            Platform.runLater(() -> {
                completionPending = false;
                startTabSession(completions, inputSnapshot);
            });
        });
        t.setDaemon(true);
        t.start();
    }

    /** 开始新的 Tab 补全会话：单候选直接替换；多候选切到第一个并记录会话 */
    private void startTabSession(List<String> completions, String originalInput) {
        if (completions.isEmpty()) {
            return;
        }
        // 读取当前输入，若用户在等待期间改动了输入导致不再以原输入开头，则放弃
        String currentInput = terminalArea.getText().substring(promptStart);
        if (!currentInput.startsWith(originalInput)) {
            return;
        }
        tabCandidates = new ArrayList<>(completions);
        tabPrefix = originalInput;
        tabIndex = 0;
        replaceCurrentInput(completions.get(0));
    }

    /** 启动独立 PowerShell 进程获取补全候选列表（用临时脚本文件避免命令行转义问题） */
    private List<String> fetchCompletions(String input, int cursorPos) {
        List<String> completions = new ArrayList<>();
        File scriptFile = null;
        try {
            // 单引号转义：PowerShell 单引号字符串中 ' 写成 ''
            String escapedInput = input.replace("'", "''");
            String script =
                "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8\r\n" +
                "$r = TabExpansion2 -inputScript '" + escapedInput + "' -cursorColumn " + cursorPos + "\r\n" +
                "\"__TAB_START__\"\r\n" +
                "if ($r) { $r.CompletionMatches | ForEach-Object { $_.CompletionText } }\r\n" +
                "\"__TAB_END__\"\r\n";

            // 写入临时脚本文件，UTF-8 无 BOM
            scriptFile = File.createTempFile("tomato_tab_", ".ps1");
            try (java.io.Writer w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(scriptFile), "UTF-8")) {
                w.write(script);
            }

            ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass",
                "-File", scriptFile.getAbsolutePath()
            );
            pb.directory(new File(currentWorkingDir));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // 用 UTF-8 读取，与脚本中设置的输出编码一致
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), "UTF-8")
            );

            boolean inBlock = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals("__TAB_START__")) {
                    inBlock = true;
                    continue;
                }
                if (line.equals("__TAB_END__")) {
                    break;
                }
                if (inBlock && !line.isEmpty()) {
                    completions.add(line);
                }
            }
            p.waitFor(5, TimeUnit.SECONDS);
            p.destroyForcibly();
        } catch (Exception e) {
            // 补全失败时静默忽略
        } finally {
            if (scriptFile != null) {
                scriptFile.delete();
            }
        }
        return completions;
    }

    /** 上下箭头切换历史：读取 PowerShell 的 PSReadLine 历史文件（真实 PowerShell 控制台使用的历史） */
    private void navigateHistory(int direction) {
        if (historyPending) {
            return;
        }
        // 在历史中间导航时，使用缓存快速切换
        if (!commandHistory.isEmpty() && historyIndex < commandHistory.size()) {
            int newIndex = historyIndex + direction;
            if (newIndex >= 0 && newIndex < commandHistory.size()) {
                historyIndex = newIndex;
                replaceCurrentInput(commandHistory.get(historyIndex));
                return;
            } else if (direction > 0 && newIndex >= commandHistory.size()) {
                // 回到底部：清空输入
                historyIndex = commandHistory.size();
                replaceCurrentInput("");
                return;
            } else if (direction < 0 && newIndex < 0) {
                // 已经到顶部
                return;
            }
        }
        // 处于历史底部（historyIndex >= size）或缓存为空时：
        // 按上箭头则从文件刷新历史再切换；按下箭头到底部则清空
        if (direction > 0) {
            // 已经在底部，继续按向下没意义
            replaceCurrentInput("");
            return;
        }
        // 按上箭头：异步从 PSReadLine 历史文件刷新
        historyPending = true;
        Thread t = new Thread(() -> {
            List<String> realHistory = fetchPowerShellHistory();
            Platform.runLater(() -> {
                historyPending = false;
                if (realHistory != null && !realHistory.isEmpty()) {
                    commandHistory.clear();
                    commandHistory.addAll(realHistory);
                    historyIndex = commandHistory.size();
                    // 切换到最后一条（上箭头 = 上一条命令）
                    int newIndex = historyIndex + direction;
                    if (newIndex >= 0 && newIndex < commandHistory.size()) {
                        historyIndex = newIndex;
                        replaceCurrentInput(commandHistory.get(historyIndex));
                    }
                }
            });
        });
        t.setDaemon(true);
        t.start();
    }

    /** 读取 PowerShell 的 PSReadLine 历史文件（真实 PowerShell 控制台上下箭头使用的历史） */
    private List<String> fetchPowerShellHistory() {
        List<String> history = new ArrayList<>();
        try {
            String appData = System.getenv("APPDATA");
            if (appData == null) {
                return history;
            }
            File histFile = new File(appData,
                "Microsoft\\Windows\\PowerShell\\PSReadLine\\ConsoleHost_history.txt");
            if (!histFile.exists()) {
                return history;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new java.io.FileInputStream(histFile), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isEmpty()) {
                        history.add(line);
                    }
                }
            }
        } catch (Exception e) {
            // 忽略
        }
        return history;
    }

    /** 替换当前提示符后的输入内容 */
    private void replaceCurrentInput(String text) {
        String fullText = terminalArea.getText();
        String beforePrompt = fullText.substring(0, promptStart);
        terminalArea.setText(beforePrompt + text);
        terminalArea.positionCaret(terminalArea.getText().length());
    }

    public static void main(String[] args) {
        launch(args);
    }
}
