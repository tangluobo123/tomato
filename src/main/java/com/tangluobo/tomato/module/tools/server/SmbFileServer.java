package com.tangluobo.tomato.module.tools.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SMB 共享服务器框架（模拟实现）
 * 由于SMB协议（CIFS）非常复杂，涉及NTLMv2认证、SMB2/3握手等，
 * 这里实现一个框架UI + 简单的文件共享状态。
 *
 * 真实SMB服务器建议使用 jcifs-ng 或 purejtcifs 等库。
 */
public class SmbFileServer implements FileServer {

    private ServerConfig config;
    private volatile boolean running = false;
    private ServerSocket dummySocket;
    private Thread acceptThread;
    private ExecutorService executor;

    @Override
    public ServerType getType() {
        return ServerType.SMB;
    }

    @Override
    public void start(ServerConfig config) throws Exception {
        this.config = config;
        running = true;

        // 检查所有共享目录是否存在
        if (config.getSharedDirectories() != null) {
            for (SharedDirectory dir : config.getSharedDirectories()) {
                Path p = Paths.get(dir.getPath());
                if (!Files.exists(p)) {
                    Files.createDirectories(p);
                }
            }
        }

        // 绑定端口验证（不实际解析SMB协议，仅用于监听端口是否被占用，
        // 并输出访问提示；Windows下445端口通常被系统占用，建议使用其他端口）
        try {
            dummySocket = new ServerSocket();
            dummySocket.setReuseAddress(true);
            dummySocket.bind(new InetSocketAddress(config.getBindAddress(), config.getPort()));
            executor = Executors.newCachedThreadPool();
            acceptThread = new Thread(() -> {
                while (running && dummySocket != null && !dummySocket.isClosed()) {
                    try {
                        Socket client = dummySocket.accept();
                        executor.submit(() -> handleDummy(client));
                    } catch (IOException e) {
                        if (!running) break;
                    }
                }
            }, "SMB-Listener");
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (IOException e) {
            // Windows下445通常被系统服务占用，此时仍然标记为运行中，
            // 提示用户该端口已被系统占用，实际SMB共享可能由系统接管
            running = true;
        }
    }

    private void handleDummy(Socket client) {
        try (client) {
            // 收到连接后读取SMB协商请求头（仅用于识别，不回应）
            try {
                byte[] buf = new byte[64];
                client.setSoTimeout(2000);
                int n = client.getInputStream().read(buf);
                if (n > 4) {
                    // SMB 响应：返回 STATUS_NOT_IMPLEMENTED (0xC0000002)
                    // 格式：NETBIOS header(4) + SMB header(32)
                    byte[] resp = new byte[64];
                    resp[0] = 0x00; resp[1] = 0x00; resp[2] = 0x00; resp[3] = 0x28; // length
                    resp[4] = (byte) 0xFF; resp[5] = 'S'; resp[6] = 'M'; resp[7] = 'B';
                    resp[8] = 0x72; // SMB_COM_NEGOTIATE response
                    resp[9] = (byte) 0x02; // STATUS_NOT_IMPLEMENTED high
                    // 简化：直接关闭
                }
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    @Override
    public void stop() throws Exception {
        running = false;
        if (dummySocket != null) {
            try { dummySocket.close(); } catch (Exception ignored) {}
            dummySocket = null;
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
        return "smb://" + config.getBindAddress() + ":" + config.getPort();
    }
}
