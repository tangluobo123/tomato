package com.tangluobo.tomato.module.tools.server;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * HTTP文件服务器实现（基于JDK内置HttpServer）
 */
public class HttpFileServer implements FileServer {

    private HttpServer httpServer;
    private ServerConfig config;
    private volatile boolean running = false;

    @Override
    public ServerType getType() {
        return ServerType.HTTP;
    }

    @Override
    public void start(ServerConfig config) throws Exception {
        this.config = config;
        InetSocketAddress addr = new InetSocketAddress(config.getBindAddress(), config.getPort());
        httpServer = HttpServer.create(addr, 0);
        httpServer.setExecutor(Executors.newCachedThreadPool());

        // 创建根上下文处理所有请求
        if (config.getSharedDirectories() != null && !config.getSharedDirectories().isEmpty()) {
            // 多共享目录模式：每个alias对应一个context
            for (SharedDirectory dir : config.getSharedDirectories()) {
                String ctxPath = "/" + URLEncoder.encode(dir.getAlias(), StandardCharsets.UTF_8);
                httpServer.createContext(ctxPath, new FileHandler(dir, config));
            }
            // 根路径显示目录列表
            httpServer.createContext("/", new RootHandler(config));
        } else if (config.getRootDirectory() != null && !config.getRootDirectory().isEmpty()) {
            // 单根目录模式
            SharedDirectory defaultDir = new SharedDirectory("root", config.getRootDirectory(), false);
            httpServer.createContext("/", new FileHandler(defaultDir, config));
        } else {
            // 无目录配置时显示欢迎页
            httpServer.createContext("/", new WelcomeHandler());
        }

        httpServer.start();
        running = true;
    }

    @Override
    public void stop() throws Exception {
        if (httpServer != null) {
            httpServer.stop(2);
            httpServer = null;
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public String getListenAddress() {
        if (config == null) return "";
        return "http://" + config.getBindAddress() + ":" + config.getPort();
    }

    /**
     * 基础认证检查
     */
    private boolean checkAuth(HttpExchange exchange, SharedDirectory dir, ServerConfig cfg) {
        if (cfg.isAnonymousAccess()) return true;
        if (dir != null && dir.getAllowedUsers() != null && dir.getAllowedUsers().isEmpty() && cfg.getAccounts() == null) return true;

        Headers headers = exchange.getRequestHeaders();
        List<String> authHeader = headers.get("Authorization");
        if (authHeader == null || authHeader.isEmpty()) {
            sendUnauthorized(exchange);
            return false;
        }

        String auth = authHeader.get(0);
        if (!auth.startsWith("Basic ")) {
            sendUnauthorized(exchange);
            return false;
        }

        try {
            String decoded = new String(Base64.getDecoder().decode(auth.substring(6)), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon <= 0) {
                sendUnauthorized(exchange);
                return false;
            }
            String user = decoded.substring(0, colon);
            String pass = decoded.substring(colon + 1);

            // 验证账号
            if (cfg.getAccounts() != null) {
                for (ServerAccount acc : cfg.getAccounts()) {
                    if (acc.isEnabled() && acc.getUsername().equals(user) && acc.getPassword().equals(pass)) {
                        // 检查该用户是否被允许访问该目录
                        if (dir != null && dir.getAllowedUsers() != null && !dir.getAllowedUsers().isEmpty()) {
                            return dir.getAllowedUsers().contains(user);
                        }
                        return true;
                    }
                }
            }
            sendUnauthorized(exchange);
            return false;
        } catch (Exception e) {
            sendUnauthorized(exchange);
            return false;
        }
    }

    private void sendUnauthorized(HttpExchange exchange) {
        try {
            Headers respHeaders = exchange.getResponseHeaders();
            respHeaders.set("WWW-Authenticate", "Basic realm=\"File Server\"");
            exchange.sendResponseHeaders(401, -1);
        } catch (Exception ignored) {}
    }

    /**
     * 欢迎页处理器
     */
    private static class WelcomeHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String html = """
                <html><head><title>HTTP File Server</title>
                <style>body{font-family:Segoe UI,Arial;padding:40px;background:#f5f5f5}
                .box{background:#fff;padding:40px;border-radius:8px;box-shadow:0 2px 8px rgba(0,0,0,.08);max-width:500px;margin:0 auto;text-align:center}
                h1{color:#07c160;margin:0 0 16px}p{color:#666;line-height:1.8}</style></head>
                <body><div class="box"><h1>HTTP File Server</h1>
                <p>服务器运行正常。<br>请在软件中配置共享目录后访问。</p></div></body></html>
                """;
            byte[] data = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }
    }

    /**
     * 根路径处理器：显示共享目录列表
     */
    private static class RootHandler implements HttpHandler {
        private final ServerConfig config;

        RootHandler(ServerConfig config) {
            this.config = config;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("""
                <html><head><title>HTTP File Server - 共享目录</title>
                <style>body{font-family:Segoe UI,Arial;padding:30px;background:#f5f5f5}
                h1{color:#333;margin:0 0 24px;font-size:22px}
                .card{background:#fff;padding:20px;margin:12px 0;border-radius:8px;box-shadow:0 2px 6px rgba(0,0,0,.06)}
                a{color:#07c160;text-decoration:none;font-weight:bold;font-size:16px}
                a:hover{text-decoration:underline}.desc{color:#888;font-size:12px;margin-top:6px}</style></head>
                <body><h1>📁 共享目录列表</h1>
                """);

            for (SharedDirectory dir : config.getSharedDirectories()) {
                String link = "/" + URLEncoder.encode(dir.getAlias(), StandardCharsets.UTF_8) + "/";
                sb.append("<div class=\"card\"><a href=\"").append(link).append("\">📂 ").append(dir.getAlias()).append("</a>");
                sb.append("<div class=\"desc\">路径: ").append(dir.getPath());
                sb.append(" | ").append(dir.isReadOnly() ? "🔒 只读" : "✏️ 可写").append("</div></div>");
            }
            sb.append("</body></html>");

            byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }
    }

    /**
     * 文件/目录处理器
     */
    private class FileHandler implements HttpHandler {
        private final SharedDirectory dir;
        private final ServerConfig cfg;

        FileHandler(SharedDirectory dir, ServerConfig cfg) {
            this.dir = dir;
            this.cfg = cfg;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // 认证检查
            if (!checkAuth(exchange, dir, cfg)) {
                return;
            }

            String method = exchange.getRequestMethod();
            String ctxPath = exchange.getHttpContext().getPath();
            String uriPath = exchange.getRequestURI().getPath();
            String relPath = uriPath.startsWith(ctxPath) ? uriPath.substring(ctxPath.length()) : uriPath;
            if (relPath.startsWith("/")) relPath = relPath.substring(1);
            relPath = URLDecoder.decode(relPath, StandardCharsets.UTF_8);

            // 规范化防止路径穿越
            Path basePath = Paths.get(dir.getPath()).toAbsolutePath().normalize();
            Path targetPath = basePath.resolve(relPath).normalize();
            if (!targetPath.startsWith(basePath)) {
                sendError(exchange, 403, "Forbidden");
                return;
            }

            if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
                handleGet(exchange, targetPath, dir);
            } else if ("PUT".equalsIgnoreCase(method)) {
                if (dir.isReadOnly()) {
                    sendError(exchange, 403, "Read Only");
                    return;
                }
                handlePut(exchange, targetPath);
            } else if ("DELETE".equalsIgnoreCase(method)) {
                if (dir.isReadOnly()) {
                    sendError(exchange, 403, "Read Only");
                    return;
                }
                handleDelete(exchange, targetPath);
            } else if ("POST".equalsIgnoreCase(method)) {
                // POST用于创建目录：?action=mkdir
                String query = exchange.getRequestURI().getQuery();
                if (query != null && query.contains("action=mkdir")) {
                    if (dir.isReadOnly()) {
                        sendError(exchange, 403, "Read Only");
                        return;
                    }
                    handleMkdir(exchange, targetPath);
                } else {
                    sendError(exchange, 405, "Method Not Allowed");
                }
            } else {
                sendError(exchange, 405, "Method Not Allowed");
            }
        }

        private void handleGet(HttpExchange exchange, Path targetPath, SharedDirectory dir) throws IOException {
            if (!Files.exists(targetPath)) {
                sendError(exchange, 404, "Not Found");
                return;
            }

            if (Files.isDirectory(targetPath)) {
                // 目录列表
                StringBuilder sb = new StringBuilder();
                String title = "📂 " + targetPath.getFileName();
                sb.append("""
                    <html><head><title>""").append(title).append("""
                    </title><style>body{font-family:Segoe UI,Arial;padding:20px;background:#f5f5f5}
                    h1{color:#333;font-size:18px;margin:0 0 16px}
                    table{width:100%;background:#fff;border-radius:8px;overflow:hidden;box-shadow:0 2px 6px rgba(0,0,0,.06);border-collapse:collapse}
                    th,td{padding:10px 14px;text-align:left;border-bottom:1px solid #f0f0f0;font-size:13px}
                    th{background:#fafafa;color:#666;font-weight:600}tr:hover td{background:#f9f9f9}
                    a{color:#07c160;text-decoration:none}.up{color:#999;font-size:12px;margin-bottom:12px;display:inline-block}</style></head>
                    <body><h1>""").append(title).append("</h1>");

                // 返回上级链接
                String ctxPath = exchange.getHttpContext().getPath();
                String currentUri = exchange.getRequestURI().getPath();
                if (!currentUri.endsWith("/")) currentUri += "/";
                if (!currentUri.equals(ctxPath + "/")) {
                    String parent = currentUri.substring(0, currentUri.length() - 1);
                    int lastSlash = parent.lastIndexOf('/');
                    parent = parent.substring(0, lastSlash + 1);
                    sb.append("<a class=\"up\" href=\"").append(parent).append("\">⬆️ 返回上级</a>");
                }

                sb.append("<table><tr><th>名称</th><th>大小</th><th>修改时间</th></tr>");

                DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
                try (var stream = Files.list(targetPath)) {
                    var items = stream.sorted((a, b) -> {
                        boolean aDir = Files.isDirectory(a);
                        boolean bDir = Files.isDirectory(b);
                        if (aDir != bDir) return aDir ? -1 : 1;
                        return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
                    }).toList();
                    for (Path p : items) {
                        String name = p.getFileName().toString();
                        boolean isDir = Files.isDirectory(p);
                        String icon = isDir ? "📁" : "📄";
                        String size = isDir ? "-" : formatSize(Files.size(p));
                        String mtime = fmt.format(Files.getLastModifiedTime(p).toInstant());
                        String link = URLEncoder.encode(name, StandardCharsets.UTF_8);
                        if (isDir) link += "/";
                        sb.append("<tr><td><a href=\"").append(link).append("\">").append(icon).append(" ").append(name).append("</a></td>")
                                .append("<td>").append(size).append("</td>")
                                .append("<td>").append(mtime).append("</td></tr>");
                    }
                }
                sb.append("</table></body></html>");

                byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, data.length);
                if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(data);
                    }
                }
            } else {
                // 下载文件
                String contentType = Files.probeContentType(targetPath);
                if (contentType == null) contentType = "application/octet-stream";
                long length = Files.size(targetPath);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Content-Disposition",
                        "inline; filename*=UTF-8''" + URLEncoder.encode(targetPath.getFileName().toString(), StandardCharsets.UTF_8));
                exchange.getResponseHeaders().set("Last-Modified",
                        DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneId.of("GMT")).format(Files.getLastModifiedTime(targetPath).toInstant()));
                exchange.sendResponseHeaders(200, length);
                if (!"HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                    try (OutputStream os = exchange.getResponseBody();
                         InputStream is = Files.newInputStream(targetPath)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
                    }
                }
            }
        }

        private void handlePut(HttpExchange exchange, Path targetPath) throws IOException {
            Files.createDirectories(targetPath.getParent());
            long total = 0;
            try (OutputStream os = Files.newOutputStream(targetPath);
                 InputStream is = exchange.getRequestBody()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) {
                    os.write(buf, 0, n);
                    total += n;
                }
            }
            String resp = "{\"ok\":true,\"size\":" + total + "}";
            byte[] data = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }

        private void handleDelete(HttpExchange exchange, Path targetPath) throws IOException {
            if (!Files.exists(targetPath)) {
                sendError(exchange, 404, "Not Found");
                return;
            }
            boolean ok;
            if (Files.isDirectory(targetPath)) {
                // 递归删除
                try (var walk = Files.walk(targetPath)) {
                    var list = walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList();
                    for (Path p : list) Files.delete(p);
                }
                ok = true;
            } else {
                ok = Files.deleteIfExists(targetPath);
            }
            String resp = "{\"ok\":" + ok + "}";
            byte[] data = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }

        private void handleMkdir(HttpExchange exchange, Path targetPath) throws IOException {
            Files.createDirectories(targetPath);
            String resp = "{\"ok\":true}";
            byte[] data = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }

        private void sendError(HttpExchange exchange, int code, String msg) throws IOException {
            String html = "<html><body><h1>" + code + " " + msg + "</h1></body></html>";
            byte[] data = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html");
            exchange.sendResponseHeaders(code, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }

        private String formatSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
            if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
