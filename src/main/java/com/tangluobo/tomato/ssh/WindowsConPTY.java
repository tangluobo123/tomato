package com.tangluobo.tomato.ssh;

import java.io.IOException;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Windows ConPTY (Console Pseudo-terminal) 封装，使用 Foreign Function & Memory API。
 *
 * ConPTY 为 CMD/PowerShell 进程创建伪控制台，使 Tab 补全、ANSI 转义序列等
 * 控制台功能正常工作（普通管道方式无法实现）。
 *
 * 需要 Windows 10 1809+（2018年10月）。
 * ConPTY 管道通信使用 UTF-8 编码。
 */
public class WindowsConPTY implements PseudoTerminal {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup KERNEL32;

    // Windows 常量
    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
    private static final int CREATE_NO_WINDOW = 0x08000000;
    private static final int STARTF_USESTDHANDLES = 0x00000100;
    private static final long PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE = 0x00020016L;
    private static final int STILL_ACTIVE = 259;
    private static final int WAIT_TIMEOUT = 0x102;
    private static final int WAIT_OBJECT_0 = 0;

    // 句柄布局（64位系统上 HANDLE 为 8 字节指针）
    private static final AddressLayout HANDLE_LAYOUT = ValueLayout.ADDRESS;

    // STARTUPINFOW 布局（104字节）
    // https://docs.microsoft.com/en-us/windows/win32/api/processthreadsapi/ns-processthreadsapi-startupinfow
    private static final long STARTUPINFO_SIZE = 104;
    private static final long STARTUPINFOEX_SIZE = 112; // STARTUPINFO + lpAttributeList 指针

    // 字段偏移量
    private static final long OFFSET_SI_CB = 0;
    private static final long OFFSET_SI_LPRESERVED = 8;
    private static final long OFFSET_SI_DESKTOP = 16;
    private static final long OFFSET_SI_TITLE = 24;
    private static final long OFFSET_SI_X = 32;
    private static final long OFFSET_SI_Y = 36;
    private static final long OFFSET_SI_XSIZE = 40;
    private static final long OFFSET_SI_YSIZE = 44;
    private static final long OFFSET_SI_XCOUNTCHARS = 48;
    private static final long OFFSET_SI_YCOUNTCHARS = 52;
    private static final long OFFSET_SI_FILLATTRIBUTE = 56;
    private static final long OFFSET_SI_DWFLAGS = 60;
    private static final long OFFSET_SI_SHOWWINDOW = 64;
    private static final long OFFSET_SI_CBRESERVED2 = 68;
    private static final long OFFSET_SI_LPRESERVED2 = 72;
    private static final long OFFSET_SI_HSTDINPUT = 80;
    private static final long OFFSET_SI_HSTDOUTPUT = 88;
    private static final long OFFSET_SI_HSTDERROR = 96;

    private static final long OFFSET_SIEX_ATTR_LIST = STARTUPINFO_SIZE; // 104

    private static final long OFFSET_PI_HPROCESS = 0;
    private static final long OFFSET_PI_HTHREAD = 8;
    private static final long OFFSET_PI_PID = 16;

    // 方法句柄
    private static final MethodHandle CreatePipe;
    private static final MethodHandle CreatePseudoConsole;
    private static final MethodHandle ResizePseudoConsole;
    private static final MethodHandle ClosePseudoConsole;
    private static final MethodHandle InitializeProcThreadAttributeList;
    private static final MethodHandle UpdateProcThreadAttribute;
    private static final MethodHandle DeleteProcThreadAttributeList;
    private static final MethodHandle CreateProcessW;
    private static final MethodHandle CloseHandle;
    private static final MethodHandle ReadFile;
    private static final MethodHandle WriteFile;
    private static final MethodHandle PeekNamedPipe;
    private static final MethodHandle GetExitCodeProcess;
    private static final MethodHandle WaitForSingleObject;
    private static final MethodHandle TerminateProcess;

    // 检测 ConPTY 是否可用
    private static final boolean AVAILABLE;

    static {
        boolean available = false;
        SymbolLookup kernel32 = null;
        try {
            kernel32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
            available = kernel32.find("CreatePseudoConsole").isPresent();
        } catch (Exception e) {
            available = false;
        }
        AVAILABLE = available;
        KERNEL32 = kernel32;

        if (available) {
            try {
                CreatePipe = lookup("CreatePipe",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, HANDLE_LAYOUT, HANDLE_LAYOUT, HANDLE_LAYOUT, ValueLayout.JAVA_INT));
                CreatePseudoConsole = lookup("CreatePseudoConsole",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
                ResizePseudoConsole = lookup("ResizePseudoConsole",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, HANDLE_LAYOUT, ValueLayout.JAVA_LONG));
                ClosePseudoConsole = lookup("ClosePseudoConsole",
                    FunctionDescriptor.ofVoid(HANDLE_LAYOUT));
                InitializeProcThreadAttributeList = lookup("InitializeProcThreadAttributeList",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, HANDLE_LAYOUT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, HANDLE_LAYOUT));
                UpdateProcThreadAttribute = lookup("UpdateProcThreadAttribute",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, HANDLE_LAYOUT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, HANDLE_LAYOUT, ValueLayout.JAVA_LONG, HANDLE_LAYOUT, HANDLE_LAYOUT));
                DeleteProcThreadAttributeList = lookup("DeleteProcThreadAttributeList",
                    FunctionDescriptor.ofVoid(HANDLE_LAYOUT));
                CreateProcessW = lookup("CreateProcessW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, HANDLE_LAYOUT, HANDLE_LAYOUT, HANDLE_LAYOUT, HANDLE_LAYOUT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, HANDLE_LAYOUT, HANDLE_LAYOUT, HANDLE_LAYOUT, HANDLE_LAYOUT));
                CloseHandle = lookup("CloseHandle",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, HANDLE_LAYOUT));
                ReadFile = lookup("ReadFile",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, HANDLE_LAYOUT, HANDLE_LAYOUT, ValueLayout.JAVA_INT, HANDLE_LAYOUT, HANDLE_LAYOUT));
                WriteFile = lookup("WriteFile",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, HANDLE_LAYOUT, HANDLE_LAYOUT, ValueLayout.JAVA_INT, HANDLE_LAYOUT, HANDLE_LAYOUT));
                PeekNamedPipe = lookup("PeekNamedPipe",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, HANDLE_LAYOUT, HANDLE_LAYOUT, ValueLayout.JAVA_INT, HANDLE_LAYOUT, HANDLE_LAYOUT, HANDLE_LAYOUT));
                GetExitCodeProcess = lookup("GetExitCodeProcess",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, HANDLE_LAYOUT, HANDLE_LAYOUT));
                WaitForSingleObject = lookup("WaitForSingleObject",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, HANDLE_LAYOUT, ValueLayout.JAVA_INT));
                TerminateProcess = lookup("TerminateProcess",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, HANDLE_LAYOUT, ValueLayout.JAVA_INT));
            } catch (Throwable t) {
                throw new ExceptionInInitializerError("Failed to load ConPTY functions: " + t.getMessage());
            }
        } else {
            CreatePipe = null;
            CreatePseudoConsole = null;
            ResizePseudoConsole = null;
            ClosePseudoConsole = null;
            InitializeProcThreadAttributeList = null;
            UpdateProcThreadAttribute = null;
            DeleteProcThreadAttributeList = null;
            CreateProcessW = null;
            CloseHandle = null;
            ReadFile = null;
            WriteFile = null;
            PeekNamedPipe = null;
            GetExitCodeProcess = null;
            WaitForSingleObject = null;
            TerminateProcess = null;
        }
    }

    private static MethodHandle lookup(String name, FunctionDescriptor desc) {
        MemorySegment addr = KERNEL32.find(name)
            .orElseThrow(() -> new UnsatisfiedLinkError("kernel32: " + name));
        return LINKER.downcallHandle(addr, desc);
    }

    // 实例状态
    private Arena arena;
    private long hPC;               // HPCON 伪控制台句柄
    private long inputWriteHandle;  // 我们写入的管道句柄（→ ConPTY读取 → 进程stdin）
    private long outputReadHandle;  // 我们读取的管道句柄（ConPTY写入 ← 进程stdout）
    private long processHandle;     // 进程句柄
    private long threadHandle;      // 主线程句柄
    private int processPid;         // 进程ID
    private MemorySegment attrList; // 进程线程属性列表
    private volatile boolean closed = false;

    // 预分配的辅助内存（避免每次调用都分配）
    private MemorySegment bytesRead;
    private MemorySegment bytesWritten;
    private MemorySegment ioReadBuffer;   // 预分配的读取缓冲区（native内存）
    private MemorySegment ioWriteBuffer;  // 预分配的写入缓冲区（native内存）
    private MemorySegment exitCodeBuf;    // 预分配的退出码缓冲区
    private MemorySegment peekTotalAvail; // PeekNamedPipe: 可用字节数
    private MemorySegment peekBytesLeft;  // PeekNamedPipe: 剩余字节数
    private static final int IO_BUFFER_SIZE = 64 * 1024; // 64KB

    /**
     * 检测 ConPTY 是否可用（仅 Windows 10 1809+）
     */
    public static boolean isPlatformSupported() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) return false;
        return AVAILABLE;
    }

    @Override
    public boolean isAvailable() {
        return isPlatformSupported();
    }

    /**
     * 启动伪控制台和子进程
     * @param command 命令行（如 "cmd.exe" 或 "powershell.exe"）
     * @param cols 初始列数
     * @param rows 初始行数
     * @throws IOException 启动失败
     */
    @Override
    public void start(String command, int cols, int rows) throws IOException {
        arena = Arena.ofShared();

        try {
            // 1. 创建两对管道
            // 管道1: 我们写 → ConPTY读 (进程stdin)
            // 管道2: ConPTY写 (进程stdout) → 我们读
            MemorySegment hPipePTYIn = arena.allocate(HANDLE_LAYOUT);   // ConPTY读取端
            MemorySegment hOurWrite = arena.allocate(HANDLE_LAYOUT);    // 我们写入端
            MemorySegment hOurRead = arena.allocate(HANDLE_LAYOUT);     // 我们读取端
            MemorySegment hPipePTYOut = arena.allocate(HANDLE_LAYOUT);  // ConPTY写入端

            // SECURITY_ATTRIBUTES: 用 NULL（默认属性），CreatePseudoConsole 不要求管道句柄可继承
            // 它内部会复制句柄
            // CreatePipe(hReadPipe, hWritePipe, lpPipeAttributes=NULL, nSize=0)
            int result = (int) CreatePipe.invoke(hPipePTYIn, hOurWrite, MemorySegment.NULL, 0);
            if (result == 0) throw new IOException("CreatePipe(input) failed");

            result = (int) CreatePipe.invoke(hOurRead, hPipePTYOut, MemorySegment.NULL, 0);
            if (result == 0) throw new IOException("CreatePipe(output) failed");

            long pipePtyIn = hPipePTYIn.get(HANDLE_LAYOUT, 0).address();
            inputWriteHandle = hOurWrite.get(HANDLE_LAYOUT, 0).address();
            outputReadHandle = hOurRead.get(HANDLE_LAYOUT, 0).address();
            long pipePtyOut = hPipePTYOut.get(HANDLE_LAYOUT, 0).address();

            // 2. 创建伪控制台
            // COORD { SHORT X, SHORT Y } → X | (Y << 16)，用 JAVA_LONG 传递避免 ABI 不匹配
            long coord = (cols & 0xFFFF) | ((long)(rows & 0xFFFF) << 16);
            MemorySegment phPC = arena.allocate(ValueLayout.JAVA_LONG);
            int hr = (int) CreatePseudoConsole.invoke(coord, pipePtyIn, pipePtyOut, 0, phPC.address());
            if (hr != 0) throw new IOException("CreatePseudoConsole failed: hr=0x" + Integer.toHexString(hr));
            hPC = phPC.get(ValueLayout.JAVA_LONG, 0);

            // ConPTY已接管pipePtyIn和pipePtyOut，关闭它们
            CloseHandle(MemorySegment.ofAddress(pipePtyIn));
            CloseHandle(MemorySegment.ofAddress(pipePtyOut));

            // 3. 初始化进程线程属性列表
            // 第一次调用获取所需大小
            MemorySegment sizePtr = arena.allocate(ValueLayout.JAVA_LONG);
            sizePtr.set(ValueLayout.JAVA_LONG, 0, 0L);
            InitializeProcThreadAttributeList.invoke(MemorySegment.NULL, 1, 0, sizePtr);
            long attrListSize = sizePtr.get(ValueLayout.JAVA_LONG, 0);

            attrList = arena.allocate(attrListSize);
            sizePtr.set(ValueLayout.JAVA_LONG, 0, attrListSize);
            result = (int) InitializeProcThreadAttributeList.invoke(attrList, 1, 0, sizePtr);
            if (result == 0) throw new IOException("InitializeProcThreadAttributeList failed");

            // 4. 设置伪控制台属性
            // lpValue = HPCON值（按C示例直接传递句柄值）
            result = (int) UpdateProcThreadAttribute.invoke(
                attrList, 0, PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE,
                MemorySegment.ofAddress(hPC), 8L, // sizeof(HPCON) = sizeof(pointer) = 8
                MemorySegment.NULL, MemorySegment.NULL);
            if (result == 0) throw new IOException("UpdateProcThreadAttribute failed");

            // 5. 准备 STARTUPINFOEX
            MemorySegment startupInfo = arena.allocate(STARTUPINFOEX_SIZE);
            // cb = sizeof(STARTUPINFOEX) = 112
            startupInfo.set(ValueLayout.JAVA_INT, OFFSET_SI_CB, (int) STARTUPINFOEX_SIZE);
            // dwFlags = 0（不使用 STARTF_USESTDHANDLES，ConPTY管理标准句柄）
            startupInfo.set(ValueLayout.JAVA_INT, OFFSET_SI_DWFLAGS, 0);
            // lpAttributeList
            startupInfo.set(HANDLE_LAYOUT, OFFSET_SIEX_ATTR_LIST, attrList);

            // 6. 准备命令行（UTF-16宽字符，CreateProcessW可修改命令行）
            MemorySegment cmdLine = arena.allocate(ValueLayout.JAVA_CHAR, (long) (command.length() + 1));
            for (int i = 0; i < command.length(); i++) {
                cmdLine.set(ValueLayout.JAVA_CHAR, (long) i * 2, command.charAt(i));
            }

            // 7. 创建进程
            // CREATE_NO_WINDOW: 确保进程不继承父进程控制台窗口，强制使用 ConPTY
            // EXTENDED_STARTUPINFO_PRESENT: 使用 STARTUPINFOEX 和属性列表
            int creationFlags = EXTENDED_STARTUPINFO_PRESENT | CREATE_NO_WINDOW;
            MemorySegment processInfo = arena.allocate(24); // PROCESS_INFORMATION: 24字节
            result = (int) CreateProcessW.invoke(
                MemorySegment.NULL,       // lpApplicationName
                cmdLine,                  // lpCommandLine
                MemorySegment.NULL,       // lpProcessAttributes
                MemorySegment.NULL,       // lpThreadAttributes
                0,                        // bInheritHandles = FALSE
                creationFlags,            // dwCreationFlags
                MemorySegment.NULL,       // lpEnvironment（继承父进程环境）
                MemorySegment.NULL,       // lpCurrentDirectory
                startupInfo,              // lpStartupInfo
                processInfo);             // lpProcessInformation
            if (result == 0) throw new IOException("CreateProcessW failed for: " + command);

            processHandle = processInfo.get(HANDLE_LAYOUT, OFFSET_PI_HPROCESS).address();
            threadHandle = processInfo.get(HANDLE_LAYOUT, OFFSET_PI_HTHREAD).address();
            processPid = processInfo.get(ValueLayout.JAVA_INT, OFFSET_PI_PID);

            // 关闭主线程句柄（不需要）
            CloseHandle(MemorySegment.ofAddress(threadHandle));

            // 预分配读写辅助内存（native段，FFM API不允许heap段传给native函数）
            bytesRead = arena.allocate(ValueLayout.JAVA_INT);
            bytesWritten = arena.allocate(ValueLayout.JAVA_INT);
            ioReadBuffer = arena.allocate(IO_BUFFER_SIZE);
            ioWriteBuffer = arena.allocate(IO_BUFFER_SIZE);
            exitCodeBuf = arena.allocate(ValueLayout.JAVA_INT);
            peekTotalAvail = arena.allocate(ValueLayout.JAVA_INT);
            peekBytesLeft = arena.allocate(ValueLayout.JAVA_INT);

        } catch (IOException e) {
            cleanup();
            throw e;
        } catch (Throwable t) {
            cleanup();
            throw new IOException("ConPTY start failed: " + t.getMessage(), t);
        }
    }

    /**
     * 检查管道中是否有数据可读（非阻塞）
     * @return 可读字节数，-1表示管道已关闭
     */
    public int peekAvailable() {
        if (closed) return -1;
        try {
            int result = (int) PeekNamedPipe.invoke(
                MemorySegment.ofAddress(outputReadHandle),
                MemorySegment.NULL,    // lpBuffer (NULL = 不读取数据)
                0,                     // nBufferSize
                MemorySegment.NULL,    // lpBytesRead
                peekTotalAvail,        // lpTotalBytesAvail
                peekBytesLeft);        // lpBytesLeftThisMessage
            if (result == 0) {
                // PeekNamedPipe失败，管道可能已关闭
                return -1;
            }
            return peekTotalAvail.get(ValueLayout.JAVA_INT, 0);
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 从进程输出读取数据（阻塞调用）
     * @param buffer 读取缓冲区
     * @return 读取的字节数，-1表示EOF（进程已退出）
     * @throws IOException 读取失败
     */
    @Override
    public int read(byte[] buffer) throws IOException {
        if (closed || outputReadHandle == 0) return -1;
        try {
            int toRead = Math.min(buffer.length, IO_BUFFER_SIZE);
            int result = (int) ReadFile.invoke(
                MemorySegment.ofAddress(outputReadHandle),
                ioReadBuffer,
                toRead,
                bytesRead,
                MemorySegment.NULL);
            if (result == 0) {
                // ReadFile失败，通常是管道已关闭（进程退出）
                return -1;
            }
            int count = bytesRead.get(ValueLayout.JAVA_INT, 0);
            if (count == 0) return -1; // EOF
            // 从native段拷贝到Java数组
            MemorySegment.copy(ioReadBuffer, ValueLayout.JAVA_BYTE, 0L, buffer, 0, count);
            return count;
        } catch (Throwable t) {
            throw new IOException("ConPTY read failed: " + t.getMessage(), t);
        }
    }

    /**
     * 向进程输入写入数据
     * @param data 字节数据（UTF-8编码）
     * @throws IOException 写入失败
     */
    @Override
    public void write(byte[] data) throws IOException {
        if (closed || inputWriteHandle == 0) return;
        try {
            int result;
            if (data.length <= IO_BUFFER_SIZE) {
                // 使用预分配的native缓冲区
                MemorySegment.copy(data, 0, ioWriteBuffer, ValueLayout.JAVA_BYTE, 0L, data.length);
                result = (int) WriteFile.invoke(
                    MemorySegment.ofAddress(inputWriteHandle),
                    ioWriteBuffer,
                    data.length,
                    bytesWritten,
                    MemorySegment.NULL);
            } else {
                // 数据太大（如大段粘贴），使用临时arena
                try (Arena localArena = Arena.ofConfined()) {
                    MemorySegment bufSeg = localArena.allocate(data.length);
                    MemorySegment.copy(data, 0, bufSeg, ValueLayout.JAVA_BYTE, 0L, data.length);
                    result = (int) WriteFile.invoke(
                        MemorySegment.ofAddress(inputWriteHandle),
                        bufSeg,
                        data.length,
                        bytesWritten,
                        MemorySegment.NULL);
                }
            }
            int written = bytesWritten.get(ValueLayout.JAVA_INT, 0);
            if (result == 0) {
                throw new IOException("WriteFile failed");
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("ConPTY write failed: " + t.getMessage(), t);
        }
    }

    /**
     * 调整伪控制台大小
     * @param cols 新列数
     * @param rows 新行数
     */
    @Override
    public void resize(int cols, int rows) {
        if (closed || hPC == 0) return;
        try {
            long coord = (cols & 0xFFFF) | ((long)(rows & 0xFFFF) << 16);
            ResizePseudoConsole.invoke(MemorySegment.ofAddress(hPC), coord);
        } catch (Throwable t) {
            // 非关键操作，忽略错误
        }
    }

    /**
     * 检查进程是否仍在运行
     */
    @Override
    public boolean isAlive() {
        if (closed || processHandle == 0) return false;
        try {
            int result = (int) GetExitCodeProcess.invoke(
                MemorySegment.ofAddress(processHandle), exitCodeBuf);
            if (result == 0) return false;
            return exitCodeBuf.get(ValueLayout.JAVA_INT, 0) == STILL_ACTIVE;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 获取进程ID
     */
    @Override
    public int getPid() {
        return processPid;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;

        // 1. 关闭输入写入端（向进程发送EOF）
        if (inputWriteHandle != 0) {
            CloseHandle(MemorySegment.ofAddress(inputWriteHandle));
            inputWriteHandle = 0;
        }

        // 2. 关闭伪控制台（通知进程控制台已关闭）
        if (hPC != 0) {
            try {
                ClosePseudoConsole.invoke(MemorySegment.ofAddress(hPC));
            } catch (Throwable ignored) {}
            hPC = 0;
        }

        // 3. 等待进程退出（最多2秒）
        if (processHandle != 0) {
            try {
                int waitResult = (int) WaitForSingleObject.invoke(
                    MemorySegment.ofAddress(processHandle), 2000);
                if (waitResult == WAIT_TIMEOUT) {
                    // 进程未退出，强制终止
                    try {
                        TerminateProcess.invoke(MemorySegment.ofAddress(processHandle), 1);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}

            // 4. 关闭进程句柄
            CloseHandle(MemorySegment.ofAddress(processHandle));
            processHandle = 0;
        }

        // 5. 删除属性列表
        if (attrList != null) {
            try {
                DeleteProcThreadAttributeList.invoke(attrList);
            } catch (Throwable ignored) {}
            attrList = null;
        }

        // 6. 关闭输出读取端
        if (outputReadHandle != 0) {
            CloseHandle(MemorySegment.ofAddress(outputReadHandle));
            outputReadHandle = 0;
        }

        // 7. 关闭arena
        if (arena != null) {
            arena.close();
            arena = null;
        }
    }

    /**
     * 清理资源（启动失败时调用）
     */
    private void cleanup() {
        if (hPC != 0) {
            try { ClosePseudoConsole.invoke(MemorySegment.ofAddress(hPC)); } catch (Throwable ignored) {}
            hPC = 0;
        }
        if (attrList != null) {
            try { DeleteProcThreadAttributeList.invoke(attrList); } catch (Throwable ignored) {}
            attrList = null;
        }
        if (inputWriteHandle != 0) { CloseHandle(MemorySegment.ofAddress(inputWriteHandle)); inputWriteHandle = 0; }
        if (outputReadHandle != 0) { CloseHandle(MemorySegment.ofAddress(outputReadHandle)); outputReadHandle = 0; }
        if (processHandle != 0) { CloseHandle(MemorySegment.ofAddress(processHandle)); processHandle = 0; }
        if (arena != null) { arena.close(); arena = null; }
    }

    private static void CloseHandle(MemorySegment handle) {
        try {
            CloseHandle.invoke(handle);
        } catch (Throwable ignored) {}
    }
}
