package com.tangluobo.tomato.ssh;

import java.io.IOException;

/**
 * 伪终端抽象接口，支持跨平台本地终端连接。
 *
 * 实现类：
 * - WindowsConPTY: Windows 10 1809+ 使用 ConPTY
 * - LinuxPTY: Linux/macOS 使用 POSIX PTY (posix_openpt + fork + exec)
 */
public interface PseudoTerminal extends AutoCloseable {

    /**
     * 根据当前平台创建合适的 PTY 实例
     * @return PTY 实例，如果平台不支持返回 null
     */
    static PseudoTerminal create() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            WindowsConPTY pty = new WindowsConPTY();
            if (pty.isAvailable()) return pty;
        } else if (os.contains("linux") || os.contains("mac") || os.contains("nix")) {
            LinuxPTY pty = new LinuxPTY();
            if (pty.isAvailable()) return pty;
        }
        return null;
    }

    /**
     * 获取当前平台的默认 shell 命令
     */
    static String getDefaultShell() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "powershell.exe -NoProfile";
        }
        // Linux/macOS: 优先使用 $SHELL 环境变量，否则用 /bin/bash
        String shell = System.getenv("SHELL");
        if (shell != null && !shell.isEmpty()) {
            return shell;
        }
        return "/bin/bash";
    }

    /**
     * 检测当前平台是否支持此 PTY 实现
     */
    boolean isAvailable();

    /**
     * 启动伪终端和子进程
     * @param command 命令行（如 "powershell.exe -NoProfile" 或 "/bin/bash"）
     * @param cols 初始列数
     * @param rows 初始行数
     * @throws IOException 启动失败
     */
    void start(String command, int cols, int rows) throws IOException;

    /**
     * 从终端输出读取数据（阻塞）
     * @param buffer 读取缓冲区
     * @return 读取的字节数，-1 表示 EOF
     * @throws IOException 读取失败
     */
    int read(byte[] buffer) throws IOException;

    /**
     * 向终端输入写入数据
     * @param data 字节数据
     * @throws IOException 写入失败
     */
    void write(byte[] data) throws IOException;

    /**
     * 调整终端大小
     * @param cols 列数
     * @param rows 行数
     */
    void resize(int cols, int rows);

    /**
     * 检查子进程是否仍在运行
     */
    boolean isAlive();

    /**
     * 获取子进程 PID
     */
    int getPid();

    /**
     * 关闭终端和子进程
     */
    @Override
    void close();
}
