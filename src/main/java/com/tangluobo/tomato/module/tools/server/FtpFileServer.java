package com.tangluobo.tomato.module.tools.server;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 简单FTP服务器实现（基于Java Socket，支持基本文件传输命令）
 * 支持: USER, PASS, QUIT, PASV, PORT, LIST, NLST, RETR, STOR, DELE, RMD, MKD, CWD, PWD, TYPE, SYST, NOOP, FEAT
 */
public class FtpFileServer implements FileServer {

    private ServerSocket serverSocket;
    private ServerConfig config;
    private volatile boolean running = false;
    private ExecutorService executor;
    private Thread acceptThread;

    @Override
    public ServerType getType() {
        return ServerType.FTP;
    }

    @Override
    public void start(ServerConfig config) throws Exception {
        this.config = config;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(config.getBindAddress(), config.getPort()));
        executor = Executors.newCachedThreadPool();
        running = true;

        acceptThread = new Thread(() -> {
            while (running && !serverSocket.isClosed()) {
                try {
                    Socket client = serverSocket.accept();
                    executor.submit(() -> handleClient(client));
                } catch (IOException e) {
                    if (!running) break;
                }
            }
        }, "FTP-Acceptor");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    @Override
    public void stop() throws Exception {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignored) {}
            serverSocket = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public String getListenAddress() {
        if (config == null) return "";
        return "ftp://" + config.getBindAddress() + ":" + config.getPort();
    }

    // ====== 客户端处理 ======

    private void handleClient(Socket client) {
        try (client) {
            client.setSoTimeout(0);
            PrintWriter out = new PrintWriter(new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));

            send(out, 220, "Welcome to Tomato FTP Server");

            FtpSession session = new FtpSession();

            String line;
            while ((line = in.readLine()) != null) {
                if (line.isEmpty()) continue;
                String cmd = line;
                String arg = "";
                int sp = line.indexOf(' ');
                if (sp > 0) {
                    cmd = line.substring(0, sp).toUpperCase();
                    arg = line.substring(sp + 1).trim();
                } else {
                    cmd = cmd.toUpperCase();
                }

                switch (cmd) {
                    case "USER" -> handleUser(out, session, arg);
                    case "PASS" -> handlePass(out, session, arg);
                    case "QUIT" -> { send(out, 221, "Goodbye"); return; }
                    case "SYST" -> send(out, 215, "UNIX Type: L8");
                    case "FEAT" -> handleFeat(out);
                    case "NOOP" -> send(out, 200, "OK");
                    case "TYPE" -> send(out, 200, "Type set to " + arg);
                    case "OPTS" -> send(out, 200, "OK");
                    case "PWD", "XPWD" -> send(out, 257, "\"" + session.currentPath + "\" is current directory");
                    case "CWD", "XCWD" -> handleCwd(out, session, arg);
                    case "CDUP" -> handleCdup(out, session);
                    case "PASV" -> handlePasv(out, session, client);
                    case "EPSV" -> handleEpsv(out, session);
                    case "PORT" -> handlePort(out, session, arg);
                    case "LIST", "NLST" -> handleList(out, session, arg, cmd.equals("NLST"));
                    case "RETR" -> handleRetr(out, session, arg);
                    case "STOR" -> handleStor(out, session, arg);
                    case "DELE" -> handleDele(out, session, arg);
                    case "RMD", "XRMD" -> handleRmd(out, session, arg);
                    case "MKD", "XMKD" -> handleMkd(out, session, arg);
                    case "SIZE" -> handleSize(out, session, arg);
                    case "MDTM" -> handleMdtm(out, session, arg);
                    case "REST" -> send(out, 350, "Restarting at " + arg + " (NOTE: actually ignored)");
                    case "RNFR" -> handleRnfr(out, session, arg);
                    case "RNTO" -> handleRnto(out, session, arg);
                    case "ABOR" -> send(out, 226, "ABOR command successful");
                    default -> send(out, 502, "Command not implemented: " + cmd);
                }
            }
        } catch (Exception e) {
            System.err.println("FTP 客户端连接异常: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void send(PrintWriter out, int code, String msg) {
        out.println(code + " " + msg);
    }

    private void handleUser(PrintWriter out, FtpSession sess, String user) {
        sess.username = user;
        if (config.isAnonymousAccess() && "anonymous".equalsIgnoreCase(user)) {
            sess.authenticated = true;
            setDefaultRoot(sess);
            send(out, 230, "Anonymous user logged in");
        } else {
            send(out, 331, "Password required for " + user);
        }
    }

    private void handlePass(PrintWriter out, FtpSession sess, String pass) {
        if (sess.username == null) {
            send(out, 503, "Login with USER first");
            return;
        }
        // 检查账号列表
        boolean ok = false;
        if (config.getAccounts() != null) {
            for (ServerAccount acc : config.getAccounts()) {
                if (acc.isEnabled() && acc.getUsername().equals(sess.username) && acc.getPassword().equals(pass)) {
                    ok = true;
                    // 使用用户主目录（如果设置了）
                    if (acc.getHomeDirectory() != null && !acc.getHomeDirectory().isEmpty()) {
                        sess.basePath = Paths.get(acc.getHomeDirectory()).toAbsolutePath().normalize();
                    } else {
                        setDefaultRoot(sess);
                    }
                    break;
                }
            }
        }
        if (ok) {
            sess.authenticated = true;
            send(out, 230, "User logged in");
        } else {
            send(out, 530, "Login incorrect");
        }
    }

    private void setDefaultRoot(FtpSession sess) {
        if (config.getRootDirectory() != null && !config.getRootDirectory().isEmpty()) {
            sess.basePath = Paths.get(config.getRootDirectory()).toAbsolutePath().normalize();
        } else if (config.getSharedDirectories() != null && !config.getSharedDirectories().isEmpty()) {
            sess.basePath = Paths.get(config.getSharedDirectories().get(0).getPath()).toAbsolutePath().normalize();
        } else {
            sess.basePath = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        }
    }

    private void handleFeat(PrintWriter out) {
        out.println("211-Features:");
        out.println(" PASV");
        out.println(" EPSV");
        out.println(" UTF8");
        out.println(" SIZE");
        out.println(" MDTM");
        out.println(" REST STREAM");
        send(out, 211, "End");
    }

    private boolean requireAuth(PrintWriter out, FtpSession sess) {
        if (!sess.authenticated) {
            send(out, 530, "Please login with USER and PASS");
            return false;
        }
        return true;
    }

    private Path resolvePath(FtpSession sess, String rel) {
        Path base = sess.basePath;
        // 解析当前路径（去掉前导/和尾部/）
        String cp = sess.currentPath;
        if (cp.length() > 1 && cp.endsWith("/")) cp = cp.substring(0, cp.length() - 1);
        String cpRel = cp.length() > 1 ? cp.substring(1) : "";

        Path current = cpRel.isEmpty() ? base : base.resolve(cpRel).normalize();
        Path target;
        if (rel == null || rel.isEmpty()) {
            target = current;
        } else if (rel.startsWith("/")) {
            target = base.resolve(rel.substring(1)).normalize();
        } else {
            target = current.resolve(rel).normalize();
        }
        if (!target.startsWith(base)) return null;
        return target;
    }

    private void handleCwd(PrintWriter out, FtpSession sess, String arg) {
        if (!requireAuth(out, sess)) return;
        if ("..".equals(arg)) { handleCdup(out, sess); return; }
        Path target = resolvePath(sess, arg);
        if (target == null || !Files.isDirectory(target)) {
            send(out, 550, "Failed to change directory");
            return;
        }
        String rel = "/" + sess.basePath.relativize(target).toString().replace('\\', '/');
        sess.currentPath = rel;
        send(out, 250, "CWD command successful");
    }

    private void handleCdup(PrintWriter out, FtpSession sess) {
        if (!requireAuth(out, sess)) return;
        if ("/".equals(sess.currentPath)) {
            send(out, 250, "Already at root");
            return;
        }
        String p = sess.currentPath;
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int last = p.lastIndexOf('/');
        sess.currentPath = (last <= 0) ? "/" : p.substring(0, last);
        send(out, 250, "CDUP command successful");
    }

    private void handlePasv(PrintWriter out, FtpSession sess, Socket client) {
        if (!requireAuth(out, sess)) return;
        try {
            ServerSocket ss = new ServerSocket(0);
            ss.setSoTimeout(30000);
            sess.dataServerSocket = ss;
            sess.dataMode = FtpSession.DataMode.PASV;

            // 使用服务端本地地址（客户端连接的目标地址），IPv6 回退到 127.0.0.1
            String host = client.getLocalAddress().getHostAddress();
            String[] octets = host.split("\\.");
            if (octets.length != 4) {
                host = "127.0.0.1";
                octets = host.split("\\.");
            }
            int port = ss.getLocalPort();
            int p1 = port >> 8;
            int p2 = port & 0xff;
            send(out, 227, "Entering Passive Mode (" + octets[0] + "," + octets[1] + "," + octets[2] + "," + octets[3] + "," + p1 + "," + p2 + ")");
        } catch (Exception e) {
            send(out, 425, "Can't open data connection");
        }
    }

    /** EPSV: 扩展被动模式，只返回端口号，不返回 IP（客户端自动使用控制连接的 IP） */
    private void handleEpsv(PrintWriter out, FtpSession sess) {
        if (!requireAuth(out, sess)) return;
        try {
            ServerSocket ss = new ServerSocket(0);
            ss.setSoTimeout(30000);
            sess.dataServerSocket = ss;
            sess.dataMode = FtpSession.DataMode.PASV;
            int port = ss.getLocalPort();
            send(out, 229, "Entering Extended Passive Mode (|||" + port + "|)");
        } catch (Exception e) {
            send(out, 425, "Can't open data connection");
        }
    }

    private void handlePort(PrintWriter out, FtpSession sess, String arg) {
        if (!requireAuth(out, sess)) return;
        try {
            String[] parts = arg.split(",");
            if (parts.length != 6) { send(out, 501, "Syntax error"); return; }
            String host = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3];
            int port = (Integer.parseInt(parts[4]) << 8) | Integer.parseInt(parts[5]);
            sess.dataHost = host;
            sess.dataPort = port;
            sess.dataMode = FtpSession.DataMode.PORT;
            send(out, 200, "PORT command successful");
        } catch (Exception e) {
            send(out, 501, "Syntax error");
        }
    }

    private Socket openDataConnection(PrintWriter out, FtpSession sess) throws IOException {
        if (sess.dataMode == FtpSession.DataMode.PASV) {
            if (sess.dataServerSocket == null) { send(out, 425, "Use PASV first"); return null; }
            Socket s = sess.dataServerSocket.accept();
            sess.dataServerSocket.close();
            sess.dataServerSocket = null;
            return s;
        } else if (sess.dataMode == FtpSession.DataMode.PORT) {
            return new Socket(sess.dataHost, sess.dataPort);
        }
        send(out, 425, "No data connection established");
        return null;
    }

    private void handleList(PrintWriter out, FtpSession sess, String arg, boolean nlst) {
        if (!requireAuth(out, sess)) return;
        // 先发 150，再开数据连接，避免部分客户端等待响应导致死锁
        send(out, 150, "Opening data connection for file list");
        try (Socket ds = openDataConnection(out, sess)) {
            if (ds == null) return;
            PrintWriter dout = new PrintWriter(new OutputStreamWriter(ds.getOutputStream(), StandardCharsets.UTF_8), true);
            Path target = resolvePath(sess, arg);
            if (target != null && Files.isDirectory(target)) {
                try (var stream = Files.list(target)) {
                    for (Path p : stream.sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString())).toList()) {
                        String name = p.getFileName().toString();
                        if (nlst) {
                            dout.println(name);
                        } else {
                            boolean isDir = Files.isDirectory(p);
                            String perms = isDir ? "drwxr-xr-x" : "-rw-r--r--";
                            long size = isDir ? 4096 : Files.size(p);
                            // 标准 Unix ls -l 日期格式：英文月份缩写 + 空格填充天数
                            java.time.Instant mtime = Files.getLastModifiedTime(p).toInstant();
                            java.time.ZoneId zone = java.time.ZoneId.systemDefault();
                            java.time.LocalDateTime ldt = mtime.atZone(zone).toLocalDateTime();
                            java.time.format.DateTimeFormatter monthFmt = java.time.format.DateTimeFormatter
                                    .ofPattern("MMM", java.util.Locale.ENGLISH);
                            String monthStr = ldt.format(monthFmt);
                            int day = ldt.getDayOfMonth();
                            int hour = ldt.getHour();
                            int minute = ldt.getMinute();
                            String dateStr = String.format("%s %2d %02d:%02d", monthStr, day, hour, minute);
                            dout.printf("%s %3d %-8s %-8s %8d %s %s%n", perms, 1, "ftp", "ftp", size, dateStr, name);
                        }
                    }
                }
            }
            dout.flush();
            send(out, 226, "Transfer complete");
        } catch (Exception e) {
            send(out, 426, "Connection closed; transfer aborted");
        }
    }

    private void handleRetr(PrintWriter out, FtpSession sess, String arg) {
        if (!requireAuth(out, sess)) return;
        Path target = resolvePath(sess, arg);
        if (target == null || !Files.exists(target) || Files.isDirectory(target)) {
            send(out, 550, "File not found");
            return;
        }
        send(out, 150, "Opening data connection");
        try (Socket ds = openDataConnection(out, sess)) {
            if (ds == null) return;
            try (OutputStream os = ds.getOutputStream();
                 InputStream is = Files.newInputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
            }
            send(out, 226, "Transfer complete");
        } catch (Exception e) {
            send(out, 426, "Connection closed; transfer aborted");
        }
    }

    private void handleStor(PrintWriter out, FtpSession sess, String arg) {
        if (!requireAuth(out, sess)) return;
        // 检查是否只读
        if (isCurrentReadOnly(sess)) {
            send(out, 550, "Permission denied (read-only share)");
            return;
        }
        Path target = resolvePath(sess, arg);
        if (target == null) { send(out, 550, "Invalid path"); return; }
        try {
            Files.createDirectories(target.getParent());
            send(out, 150, "Opening data connection");
            try (Socket ds = openDataConnection(out, sess)) {
                if (ds == null) return;
                try (OutputStream os = Files.newOutputStream(target);
                     InputStream is = ds.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
                }
                send(out, 226, "Transfer complete");
            }
        } catch (Exception e) {
            send(out, 426, "Transfer aborted: " + e.getMessage());
        }
    }

    private void handleDele(PrintWriter out, FtpSession sess, String arg) {
        if (!requireAuth(out, sess)) return;
        if (isCurrentReadOnly(sess)) { send(out, 550, "Permission denied"); return; }
        Path target = resolvePath(sess, arg);
        if (target == null || !Files.exists(target) || Files.isDirectory(target)) {
            send(out, 550, "File not found");
            return;
        }
        try {
            Files.delete(target);
            send(out, 250, "DELE command successful");
        } catch (Exception e) {
            send(out, 550, "Delete failed: " + e.getMessage());
        }
    }

    private void handleRmd(PrintWriter out, FtpSession sess, String arg) {
        if (!requireAuth(out, sess)) return;
        if (isCurrentReadOnly(sess)) { send(out, 550, "Permission denied"); return; }
        Path target = resolvePath(sess, arg);
        if (target == null || !Files.isDirectory(target)) { send(out, 550, "Directory not found"); return; }
        try {
            Files.delete(target);
            send(out, 250, "RMD command successful");
        } catch (Exception e) {
            send(out, 550, "Remove directory failed: " + e.getMessage());
        }
    }

    private void handleMkd(PrintWriter out, FtpSession sess, String arg) {
        if (!requireAuth(out, sess)) return;
        if (isCurrentReadOnly(sess)) { send(out, 550, "Permission denied"); return; }
        Path target = resolvePath(sess, arg);
        if (target == null) { send(out, 550, "Invalid path"); return; }
        try {
            Files.createDirectories(target);
            send(out, 257, "\"" + arg + "\" created");
        } catch (Exception e) {
            send(out, 550, "Create directory failed: " + e.getMessage());
        }
    }

    private void handleSize(PrintWriter out, FtpSession sess, String arg) {
        if (!requireAuth(out, sess)) return;
        Path target = resolvePath(sess, arg);
        if (target == null || !Files.exists(target) || Files.isDirectory(target)) {
            send(out, 550, "File not found");
            return;
        }
        try {
            send(out, 213, String.valueOf(Files.size(target)));
        } catch (Exception e) {
            send(out, 550, "Size check failed");
        }
    }

    private void handleMdtm(PrintWriter out, FtpSession sess, String arg) {
        if (!requireAuth(out, sess)) return;
        Path target = resolvePath(sess, arg);
        if (target == null || !Files.exists(target)) {
            send(out, 550, "File not found");
            return;
        }
        try {
            java.time.Instant mtime = Files.getLastModifiedTime(target).toInstant();
            String ts = java.time.format.DateTimeFormatter
                    .ofPattern("yyyyMMddHHmmss")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(mtime);
            send(out, 213, ts);
        } catch (Exception e) {
            send(out, 550, "MDTM check failed");
        }
    }

    /** RNFR: 指定要重命名的源文件/目录 */
    private void handleRnfr(PrintWriter out, FtpSession sess, String arg) {
        if (!requireAuth(out, sess)) return;
        Path target = resolvePath(sess, arg);
        if (target == null || !Files.exists(target)) {
            send(out, 550, "File not found");
            return;
        }
        sess.renameFrom = target;
        send(out, 350, "Ready for RNTO");
    }

    /** RNTO: 指定重命名的目标路径，执行重命名 */
    private void handleRnto(PrintWriter out, FtpSession sess, String arg) {
        if (!requireAuth(out, sess)) return;
        if (sess.renameFrom == null) {
            send(out, 503, "Use RNFR first");
            return;
        }
        if (isCurrentReadOnly(sess)) {
            send(out, 550, "Permission denied (read-only share)");
            sess.renameFrom = null;
            return;
        }
        Path target = resolvePath(sess, arg);
        if (target == null) {
            send(out, 550, "Invalid target path");
            sess.renameFrom = null;
            return;
        }
        try {
            Files.move(sess.renameFrom, target);
            send(out, 250, "Rename successful");
        } catch (Exception e) {
            send(out, 550, "Rename failed: " + e.getMessage());
        }
        sess.renameFrom = null;
    }

    /** 判断当前目录所在共享是否只读（简化判断：若配置了共享目录且第一个共享标记只读） */
    private boolean isCurrentReadOnly(FtpSession sess) {
        if (config.getSharedDirectories() != null && !config.getSharedDirectories().isEmpty()) {
            for (SharedDirectory d : config.getSharedDirectories()) {
                if (Paths.get(d.getPath()).toAbsolutePath().normalize().equals(sess.basePath)) {
                    return d.isReadOnly();
                }
            }
        }
        return false;
    }

    /** FTP 会话状态 */
    private static class FtpSession {
        enum DataMode { NONE, PASV, PORT }
        String username;
        boolean authenticated;
        Path basePath;
        String currentPath = "/";
        DataMode dataMode = DataMode.NONE;
        ServerSocket dataServerSocket;
        String dataHost;
        int dataPort;
        Path renameFrom; // RNFR 暂存的源路径
    }
}
