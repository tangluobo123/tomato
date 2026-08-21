package com.tangluobo.tomato.module.connect;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SSH跳板隧道管理器（引用模式 + 引用计数）。
 *
 * 用于SSH/SFTP连接通过引用的已有SSH主机（sshTunnelHostId）建立本地端口转发，
 * 再连接到 localhost:转发端口，实现跳板机（Jump Host）访问。
 *
 * 引用计数支持同一连接的多个会话（如"复制会话"）共享同一条隧道，
 * 仅当最后一个会话释放时才真正断开隧道。
 */
public class SshTunnelManager {

    // key: configId + "_" + targetHost:targetPort
    private static final Map<String, TunnelEntry> cache = new ConcurrentHashMap<>();

    private static final class TunnelEntry {
        final SshTunnel tunnel;
        final AtomicInteger refCount = new AtomicInteger(0);
        TunnelEntry(SshTunnel tunnel) { this.tunnel = tunnel; }
    }

    /**
     * 建立/复用跳板隧道，返回本地转发端口；无需隧道时返回 -1。
     * 调用者获得一次引用，连接结束/失败时必须调用 release() 释放。
     */
    public static synchronized int resolve(ConnectionConfig config) {
        if (!config.isUseSshTunnel() || config.getSshTunnelHostId() == null) {
            return -1;
        }
        String key = config.getId() + "_" + config.getHost() + ":" + config.getPort();
        TunnelEntry entry = cache.get(key);
        if (entry != null && entry.tunnel.isActive()) {
            entry.refCount.incrementAndGet();
            return entry.tunnel.getForwardedLocalPort();
        }
        // 隧道已失效或不存在：先清理失效的旧隧道并移除缓存，确保重建得到干净的新隧道，避免端口占用与资源泄漏
        if (entry != null) {
            cache.remove(key);
            try { entry.tunnel.disconnect(); } catch (Exception ignored) {}
        }

        try {
            ConnectionConfig sshHost = findSshHostConfig(config.getSshTunnelHostId());
            if (sshHost == null) {
                throw new RuntimeException("找不到引用的SSH主机配置(ID: " + config.getSshTunnelHostId() + ")");
            }
            List<String> keyPaths = sshHost.isUseKey() ? sshHost.getPrivateKeyPaths() : null;
            String password = sshHost.isUsePassword() ? sshHost.getPassword() : null;
            if (!sshHost.isUsePassword() && sshHost.isUseKey() && sshHost.getPassword() != null) {
                password = sshHost.getPassword();
            }
            SshTunnel tunnel = new SshTunnel(
                    sshHost.getHost(),
                    sshHost.getPort(),
                    sshHost.getUsername(),
                    password,
                    keyPaths,
                    config.getHost(),
                    config.getPort()
            );
            int localPort = tunnel.connect();
            entry = new TunnelEntry(tunnel);
            entry.refCount.set(1);
            cache.put(key, entry);
            return localPort;
        } catch (Exception e) {
            throw new RuntimeException("建立SSH跳板隧道失败: " + e.getMessage(), e);
        }
    }

    /**
     * 释放一次引用；引用计数归零时断开隧道。
     */
    public static synchronized void release(ConnectionConfig config) {
        if (!config.isUseSshTunnel() || config.getSshTunnelHostId() == null) {
            return;
        }
        String key = config.getId() + "_" + config.getHost() + ":" + config.getPort();
        TunnelEntry entry = cache.get(key);
        if (entry == null) {
            return;
        }
        if (entry.refCount.decrementAndGet() <= 0) {
            entry.tunnel.disconnect();
            cache.remove(key);
        }
    }

    /**
     * 查看已建立的活跃隧道本地端口（不改变引用计数）。
     * 用于短生命周期消费者（如RocketMQ PullConsumer）复用长生命周期消费者（如Admin）已建立的隧道。
     * 无隧道或隧道未建立时返回 -1。
     */
    public static synchronized int peek(ConnectionConfig config) {
        if (!config.isUseSshTunnel() || config.getSshTunnelHostId() == null) {
            return -1;
        }
        String key = config.getId() + "_" + config.getHost() + ":" + config.getPort();
        TunnelEntry entry = cache.get(key);
        if (entry != null && entry.tunnel.isActive()) {
            return entry.tunnel.getForwardedLocalPort();
        }
        return -1;
    }

    /**
     * 建立/复用 RocketMQ broker 跳板隧道（按 configId+brokerHost:port 缓存，不使用引用计数）。
     * NameServer 返回的 broker 地址常为内网 IP，客户端直连失败；通过隧道转发到跳板机可达 broker。
     * 由 closeBrokerTunnels(config) 统一关闭（在 closeAdmin 时调用）。
     * 返回本地转发端口；未启用隧道返回 -1。
     */
    public static synchronized int ensureBrokerTunnel(ConnectionConfig config, String brokerHost, int brokerPort) {
        if (!config.isUseSshTunnel() || config.getSshTunnelHostId() == null) {
            return -1;
        }
        String key = config.getId() + "_broker_" + brokerHost + ":" + brokerPort;
        TunnelEntry entry = cache.get(key);
        if (entry != null && entry.tunnel.isActive()) {
            return entry.tunnel.getForwardedLocalPort();
        }
        // 隧道已失效或不存在：先清理失效的旧隧道并移除缓存，确保重建得到干净的新隧道，避免端口占用与资源泄漏
        if (entry != null) {
            cache.remove(key);
            try { entry.tunnel.disconnect(); } catch (Exception ignored) {}
        }
        try {
            ConnectionConfig sshHost = findSshHostConfig(config.getSshTunnelHostId());
            if (sshHost == null) {
                throw new RuntimeException("找不到引用的SSH主机配置(ID: " + config.getSshTunnelHostId() + ")");
            }
            List<String> keyPaths = sshHost.isUseKey() ? sshHost.getPrivateKeyPaths() : null;
            String password = sshHost.isUsePassword() ? sshHost.getPassword() : null;
            if (!sshHost.isUsePassword() && sshHost.isUseKey() && sshHost.getPassword() != null) {
                password = sshHost.getPassword();
            }
            SshTunnel tunnel = new SshTunnel(
                    sshHost.getHost(),
                    sshHost.getPort(),
                    sshHost.getUsername(),
                    password,
                    keyPaths,
                    brokerHost,
                    brokerPort
            );
            int localPort = tunnel.connect();
            entry = new TunnelEntry(tunnel);
            cache.put(key, entry);
            return localPort;
        } catch (Exception e) {
            throw new RuntimeException("建立broker跳板隧道失败(" + brokerHost + ":" + brokerPort + "): " + e.getMessage(), e);
        }
    }

    /**
     * 关闭指定配置的所有 broker 跳板隧道（在 closeAdmin 时调用）。
     */
    public static synchronized void closeBrokerTunnels(ConnectionConfig config) {
        if (!config.isUseSshTunnel() || config.getSshTunnelHostId() == null) {
            return;
        }
        String prefix = config.getId() + "_broker_";
        cache.entrySet().removeIf(e -> {
            if (e.getKey().startsWith(prefix)) {
                try { e.getValue().tunnel.disconnect(); } catch (Exception ignored) {}
                return true;
            }
            return false;
        });
    }

    /**
     * 根据 sshTunnelHostId 查找引用的 SSH 主机配置
     */
    private static ConnectionConfig findSshHostConfig(String hostId) {
        if (hostId == null) return null;
        try {
            List<ConnectionConfig> all = ConfigManager.loadConnections();
            for (ConnectionConfig c : all) {
                if (hostId.equals(c.getId())) return c;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
