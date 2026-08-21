package com.tangluobo.tomato.ssh;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Linux/macOS POSIX PTY 实现，使用 Foreign Function & Memory API。
 *
 * 通过 forkpty() 在 C 中完成 PTY 创建 + fork + 子进程设置（setsid/dup2/close），
 * 子进程只需调用 execvp，避免 fork 后做不安全的 Java/FFM 操作。
 * 支持 bash/zsh 等交互式 shell，以及 vim/ssh/top 等控制台程序。
 *
 * 适用于 Linux（libc.so.6 / libutil.so.1）和 macOS（libSystem.B.dylib）。
 */
public class LinuxPTY implements PseudoTerminal {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC;

    // ioctl 请求
    private static final int TIOCSWINSZ = 0x5414;  // Linux
    private static final int TIOCSWINSZ_MAC = 0x80087467;  // macOS

    // waitpid 选项
    private static final int WNOHANG = 1;

    // 信号
    private static final int SIGKILL = 9;

    // errno 值
    private static final int EINTR = 4;

    private static final boolean IS_MAC;

    // libc 函数句柄
    private static final MethodHandle forkpty;
    private static final MethodHandle close;
    private static final MethodHandle ioctl;
    private static final MethodHandle execvp;
    private static final MethodHandle _exit;
    private static final MethodHandle read;
    private static final MethodHandle write;
    private static final MethodHandle waitpid;
    private static final MethodHandle kill;
    private static final MethodHandle strerror;
    private static final MethodHandle errnoLocation;
    private static final MethodHandle setenv;

    static {
        boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        IS_MAC = isMac;

        SymbolLookup libc = null;
        try {
            if (isMac) {
                libc = SymbolLookup.libraryLookup("libSystem.B.dylib", Arena.global());
            } else {
                libc = SymbolLookup.libraryLookup("libc.so.6", Arena.global());
            }
        } catch (Exception e) {
            try {
                libc = SymbolLookup.libraryLookup("libc.so", Arena.global());
            } catch (Exception e2) {
                libc = null;
            }
        }
        LIBC = libc;

        if (libc != null) {
            try {
                close = lookup("close",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                ioctl = lookup("ioctl",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
                execvp = lookup("execvp",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                _exit = lookup("_exit",
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT));
                read = lookup("read",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
                write = lookup("write",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
                waitpid = lookup("waitpid",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                kill = lookup("kill",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                strerror = lookup("strerror",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                errnoLocation = lookup(IS_MAC ? "__error" : "__errno_location",
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
                setenv = lookup("setenv",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

                // forkpty: glibc 2.34+ 在 libc，旧版在 libutil；macOS 在 libSystem
                FunctionDescriptor forkptyDesc = FunctionDescriptor.of(
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
                Optional<MemorySegment> forkptyAddr = LIBC.find("forkpty");
                if (forkptyAddr.isEmpty() && !IS_MAC) {
                    try {
                        SymbolLookup libutil = SymbolLookup.libraryLookup("libutil.so.1", Arena.global());
                        forkptyAddr = libutil.find("forkpty");
                    } catch (Exception ignored) {}
                }
                forkpty = forkptyAddr.isPresent()
                    ? LINKER.downcallHandle(forkptyAddr.get(), forkptyDesc)
                    : null;
            } catch (Throwable t) {
                throw new ExceptionInInitializerError("Failed to load libc functions: " + t.getMessage());
            }
        } else {
            forkpty = null;
            close = null;
            ioctl = null;
            execvp = null;
            _exit = null;
            read = null;
            write = null;
            waitpid = null;
            kill = null;
            strerror = null;
            errnoLocation = null;
            setenv = null;
        }
    }

    private static MethodHandle lookup(String name, FunctionDescriptor desc) {
        MemorySegment addr = LIBC.find(name)
            .orElseThrow(() -> new UnsatisfiedLinkError("libc: " + name));
        return LINKER.downcallHandle(addr, desc);
    }

    // 实例状态
    private int masterFd = -1;
    private int childPid = -1;
    private volatile boolean closed = false;
    private Arena arena;

    @Override
    public boolean isAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return (os.contains("linux") || os.contains("mac") || os.contains("nix"))
            && LIBC != null && forkpty != null;
    }

    @Override
    public void start(String command, int cols, int rows) throws IOException {
        arena = Arena.ofShared();

        try {
            // 0. 设置 TERM 环境变量（top/vim 等全屏程序依赖此变量）
            //    setenv 修改 C 库的 environ，子进程通过 fork 继承
            if (setenv != null) {
                MemorySegment termName = allocateCString("TERM");
                MemorySegment termValue = allocateCString("xterm-256color");
                setenv.invoke(termName, termValue, 1);
            }

            // 1. 准备窗口大小（struct winsize: 4 shorts = 8 字节）
            MemorySegment winsize = arena.allocate(ValueLayout.JAVA_SHORT, 4);
            winsize.set(ValueLayout.JAVA_SHORT, 0, (short) rows);    // ws_row
            winsize.set(ValueLayout.JAVA_SHORT, 2, (short) cols);    // ws_col
            winsize.set(ValueLayout.JAVA_SHORT, 4, (short) 0);       // ws_xpixel
            winsize.set(ValueLayout.JAVA_SHORT, 6, (short) 0);       // ws_ypixel

            // 2. 准备命令行参数（execvp 需要 argv 数组）
            String[] parts = command.trim().split("\\s+");
            MemorySegment cmdSeg = allocateCString(parts[0]);
            long ptrSize = ValueLayout.ADDRESS.byteSize();
            MemorySegment argv = arena.allocate(ValueLayout.ADDRESS, (long) (parts.length + 1));
            for (int i = 0; i < parts.length; i++) {
                MemorySegment argSeg = allocateCString(parts[i]);
                argv.set(ValueLayout.ADDRESS, i * ptrSize, argSeg);
            }
            argv.set(ValueLayout.ADDRESS, parts.length * ptrSize, MemorySegment.NULL);

            // 3. forkpty：在 C 中完成 PTY 创建 + fork + 子进程设置
            //    （posix_openpt + grantpt + unlockpt + fork + setsid
            //     + open(slave) + ioctl(TIOCSCTTY) + dup2(0/1/2) + close(master)）
            //    子进程只需调用 execvp，避免 fork 后做不安全的 Java/FFM 操作
            MemorySegment masterFdPtr = arena.allocate(ValueLayout.JAVA_INT);
            int pid = (int) forkpty.invoke(masterFdPtr, MemorySegment.NULL, MemorySegment.NULL, winsize);
            if (pid < 0) {
                throw new IOException("forkpty failed: " + getError());
            }
            masterFd = masterFdPtr.get(ValueLayout.JAVA_INT, 0);

            if (pid == 0) {
                // ===== 子进程 =====
                // forkpty 已完成所有 PTY 设置，只需 execvp（async-signal-safe）
                execvp.invoke(cmdSeg, argv);
                _exit.invoke(127);
            }

            // ===== 父进程 =====
            childPid = pid;

            Thread.sleep(50);

            if (!isAlive()) {
                throw new IOException("Child process exited immediately (command: " + command + ")");
            }

        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("LinuxPTY start failed: " + t.getMessage(), t);
        }
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        if (closed || masterFd < 0) return -1;
        try {
            try (Arena localArena = Arena.ofConfined()) {
                MemorySegment buf = localArena.allocate(buffer.length);
                // EINTR 时重试（信号可能中断阻塞式 read）
                long n;
                do {
                    n = (long) read.invoke(masterFd, buf, (long) buffer.length);
                } while (n < 0 && getErrno() == EINTR);
                if (n <= 0) {
                    return -1;
                }
                int len = (int) n;
                MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0L, buffer, 0, len);
                return len;
            }
        } catch (Throwable t) {
            throw new IOException("LinuxPTY read failed: " + t.getMessage(), t);
        }
    }

    @Override
    public void write(byte[] data) throws IOException {
        if (closed || masterFd < 0) return;
        try {
            try (Arena localArena = Arena.ofConfined()) {
                MemorySegment buf = localArena.allocate(data.length);
                MemorySegment.copy(data, 0, buf, ValueLayout.JAVA_BYTE, 0L, data.length);
                long n;
                do {
                    n = (long) write.invoke(masterFd, buf, (long) data.length);
                } while (n < 0 && getErrno() == EINTR);
                if (n < 0) {
                    throw new IOException("write failed: " + getError());
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("LinuxPTY write failed: " + t.getMessage(), t);
        }
    }

    @Override
    public void resize(int cols, int rows) {
        if (closed || masterFd < 0) return;
        try {
            try (Arena localArena = Arena.ofConfined()) {
                MemorySegment winsize = localArena.allocate(ValueLayout.JAVA_SHORT, 4);
                winsize.set(ValueLayout.JAVA_SHORT, 0, (short) rows);
                winsize.set(ValueLayout.JAVA_SHORT, 2, (short) cols);
                winsize.set(ValueLayout.JAVA_SHORT, 4, (short) 0);
                winsize.set(ValueLayout.JAVA_SHORT, 6, (short) 0);
                long req = IS_MAC ? TIOCSWINSZ_MAC : TIOCSWINSZ;
                ioctl.invoke(masterFd, req, winsize);
            }
        } catch (Throwable t) {
            // 忽略 resize 失败
        }
    }

    @Override
    public boolean isAlive() {
        if (childPid < 0) return false;
        try {
            try (Arena localArena = Arena.ofConfined()) {
                MemorySegment status = localArena.allocate(ValueLayout.JAVA_INT);
                int ret = (int) waitpid.invoke(childPid, status, WNOHANG);
                if (ret == 0) {
                    return true; // 仍在运行
                }
                return false; // 已退出
            }
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public int getPid() {
        return childPid;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;

        // 终止子进程
        if (childPid > 0) {
            try {
                kill.invoke(childPid, SIGKILL);
            } catch (Throwable ignored) {}
            // 回收子进程
            try {
                try (Arena localArena = Arena.ofConfined()) {
                    MemorySegment status = localArena.allocate(ValueLayout.JAVA_INT);
                    waitpid.invoke(childPid, status, 0);
                }
            } catch (Throwable ignored) {}
            childPid = -1;
        }

        // 关闭主设备
        if (masterFd >= 0) {
            try {
                close.invoke(masterFd);
            } catch (Throwable ignored) {}
            masterFd = -1;
        }

        if (arena != null) {
            arena.close();
        }
    }

    /** 获取当前 errno 值 */
    private int getErrno() {
        try {
            if (errnoLocation == null) return 0;
            MemorySegment errnoPtr = (MemorySegment) errnoLocation.invoke();
            return errnoPtr.reinterpret(4).get(ValueLayout.JAVA_INT, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 获取 errno 对应的错误消息 */
    private String getError() {
        int err = getErrno();
        if (err == 0) return "errno=0";
        try {
            if (strerror != null) {
                MemorySegment msgSeg = (MemorySegment) strerror.invoke(err);
                if (msgSeg.address() != 0) {
                    return "errno=" + err + ": " + msgSeg.reinterpret(256).getString(0);
                }
            }
        } catch (Throwable t) {
            // 忽略
        }
        return "errno=" + err;
    }

    /** 分配以 null 结尾的 UTF-8 C 字符串 */
    private MemorySegment allocateCString(String str) {
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        MemorySegment seg = arena.allocate(ValueLayout.JAVA_BYTE, bytes.length + 1);
        MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0L, bytes.length);
        return seg;
    }
}
