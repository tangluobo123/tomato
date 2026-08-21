package com.tangluobo.tomato.module.connect.service;

import com.tangluobo.tomato.module.connect.ConfigManager;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.SshTunnel;
import redis.clients.jedis.*;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import redis.clients.jedis.resps.Tuple;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RedisService {

    // 缓存JedisCluster实例，key为集群节点配置的字符串表示
    private static final Map<String, JedisCluster> clusterCache = new ConcurrentHashMap<>();

    // SSH隧道缓存：configId + "_" + targetHost:targetPort -> SshTunnel
    private static final Map<String, SshTunnel> tunnelCache = new ConcurrentHashMap<>();

    /**
     * 获取Jedis连接（单机模式）
     */
    public static Jedis getJedis(ConnectionConfig config) {
        try {
            String host = config.getHost();
            int port = config.getPort();

            // SSH隧道（引用方式：根据 sshTunnelHostId 查找SSH主机配置建立端口转发）
            if (config.isUseSshTunnel() && config.getSshTunnelHostId() != null) {
                int localPort = setupSshTunnel(config, host, port);
                host = "localhost";
                port = localPort;
            }

            Jedis jedis = new Jedis(host, port);
            String password = config.getPassword();
            String username = config.getUsername();
            if (password != null && !password.isEmpty()) {
                if (username != null && !username.isEmpty()) {
                    jedis.auth(username, password);
                } else {
                    jedis.auth(password);
                }
            }
            return jedis;
        } catch (Exception e) {
            throw new RuntimeException("连接Redis失败: " + e.getMessage(), e);
        }
    }

    /**
     * 建立/复用 SSH 隧道，返回本地转发端口号。
     * 隧道通过引用的 SSH 主机（sshTunnelHostId）建立，目标为 Redis 的 host:port。
     */
    private static int setupSshTunnel(ConnectionConfig config, String targetHost, int targetPort) throws Exception {
        String tunnelKey = config.getId() + "_" + targetHost + ":" + targetPort;
        SshTunnel tunnel = tunnelCache.get(tunnelKey);
        if (tunnel != null && tunnel.isActive()) {
            return tunnel.getForwardedLocalPort();
        }

        // 查找引用的 SSH 主机配置
        ConnectionConfig sshHost = findSshHostConfig(config.getSshTunnelHostId());
        if (sshHost == null) {
            throw new RuntimeException("找不到引用的SSH主机配置(ID: " + config.getSshTunnelHostId() + ")");
        }

        // 用 SSH 主机的认证信息建立隧道，目标为 Redis 的 host:port
        List<String> keyPaths = sshHost.isUseKey() ? sshHost.getPrivateKeyPaths() : null;
        String password = sshHost.isUsePassword() ? sshHost.getPassword() : null;
        if (!sshHost.isUsePassword() && sshHost.isUseKey() && sshHost.getPassword() != null) {
            password = sshHost.getPassword();
        }

        tunnel = new SshTunnel(
            sshHost.getHost(),
            sshHost.getPort(),
            sshHost.getUsername(),
            password,
            keyPaths,
            targetHost,
            targetPort
        );
        int localPort = tunnel.connect();
        tunnelCache.put(tunnelKey, tunnel);

        return localPort;
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

    /**
     * 关闭指定连接的所有 SSH 隧道（关闭Redis主机连接/标签页时调用）
     */
    public static void closeSshTunnel(String configId) {
        tunnelCache.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(configId + "_")) {
                entry.getValue().disconnect();
                return true;
            }
            return false;
        });
    }

    /**
     * 获取Jedis连接并选择指定数据库
     */
    private static Jedis getJedis(ConnectionConfig config, int database) {
        Jedis jedis = getJedis(config);
        try {
            jedis.select(database);
        } catch (Exception e) {
            jedis.close();
            throw new RuntimeException("选择数据库失败: " + e.getMessage(), e);
        }
        return jedis;
    }

    /**
     * 获取Jedis集群连接（缓存复用，不需要每次关闭）
     */
    public static JedisCluster getJedisCluster(ConnectionConfig config) {
        String cacheKey = buildClusterCacheKey(config);
        return clusterCache.computeIfAbsent(cacheKey, k -> createJedisCluster(config));
    }

    private static String buildClusterCacheKey(ConnectionConfig config) {
        return config.getRedisClusterNodes() + "|" + config.getUsername() + "|" + config.getPassword();
    }

    private static JedisCluster createJedisCluster(ConnectionConfig config) {
        try {
            String nodesStr = config.getRedisClusterNodes();
            if (nodesStr == null || nodesStr.isEmpty()) {
                throw new RuntimeException("集群节点配置为空");
            }
            Set<HostAndPort> nodes = new HashSet<>();
            for (String node : nodesStr.split(",")) {
                String[] parts = node.trim().split(":");
                if (parts.length == 2) {
                    nodes.add(new HostAndPort(parts[0].trim(), Integer.parseInt(parts[1].trim())));
                }
            }
            if (nodes.isEmpty()) {
                throw new RuntimeException("未解析到有效的集群节点");
            }
            String password = config.getPassword();
            String username = config.getUsername();
            if (password != null && !password.isEmpty()) {
                JedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                        .user(username != null && !username.isEmpty() ? username : null)
                        .password(password)
                        .build();
                return new JedisCluster(nodes, clientConfig);
            }
            return new JedisCluster(nodes);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("连接Redis集群失败: " + e.getMessage(), e);
        }
    }

    /**
     * 关闭指定配置的集群连接
     */
    public static void closeJedisCluster(ConnectionConfig config) {
        String cacheKey = buildClusterCacheKey(config);
        JedisCluster cluster = clusterCache.remove(cacheKey);
        if (cluster != null) {
            try {
                cluster.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 关闭所有集群连接
     */
    public static void closeAllClusters() {
        for (Map.Entry<String, JedisCluster> entry : clusterCache.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception ignored) {
            }
        }
        clusterCache.clear();
    }

    /**
     * 获取数据库列表（返回所有数据库编号，附带key数量）
     */
    public static List<String> getDatabases(ConnectionConfig config) {
        if (config.isRedisCluster()) {
            // 集群模式只支持db0
            return List.of("0");
        }

        List<String> databases = new ArrayList<>();
        try (Jedis jedis = getJedis(config)) {
            int maxDb = 16;
            try {
                String dbCountStr = jedis.configGet("databases").get("databases");
                if (dbCountStr != null) {
                    maxDb = Integer.parseInt(dbCountStr);
                }
            } catch (Exception ignored) {
            }

            for (int i = 0; i < maxDb; i++) {
                databases.add(String.valueOf(i));
            }
        }
        return databases;
    }

    /**
     * 获取指定数据库的key列表（使用SCAN）
     */
    public static List<String> scanKeys(ConnectionConfig config, int database, String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            pattern = "*";
        }

        if (config.isRedisCluster()) {
            return scanKeysCluster(config, pattern);
        }

        // 单机模式
        List<String> keys = new ArrayList<>();
        try (Jedis jedis = getJedis(config, database)) {
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams scanParams = new ScanParams().match(pattern).count(1000);
            do {
                ScanResult<String> scanResult = jedis.scan(cursor, scanParams);
                keys.addAll(scanResult.getResult());
                cursor = scanResult.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        }
        return keys;
    }

    /**
     * 集群模式下扫描所有key
     */
    private static List<String> scanKeysCluster(ConnectionConfig config, String pattern) {
        List<String> keys = new ArrayList<>();
        ScanParams scanParams = new ScanParams().match(pattern).count(1000);

        // 解析集群节点并逐个连接执行SCAN
        String nodesStr = config.getRedisClusterNodes();
        if (nodesStr == null || nodesStr.isEmpty()) {
            return keys;
        }
        String password = config.getPassword();
        String username = config.getUsername();

        for (String node : nodesStr.split(",")) {
            String[] parts = node.trim().split(":");
            if (parts.length != 2) continue;
            String host = parts[0].trim();
            int port = Integer.parseInt(parts[1].trim());

            try (Jedis jedis = new Jedis(host, port)) {
                if (password != null && !password.isEmpty()) {
                    if (username != null && !username.isEmpty()) {
                        jedis.auth(username, password);
                    } else {
                        jedis.auth(password);
                    }
                }
                // 只扫描主节点，避免重复
                String info = jedis.info("replication");
                if (!info.contains("role:master")) {
                    continue;
                }
                String cursor = ScanParams.SCAN_POINTER_START;
                do {
                    ScanResult<String> scanResult = jedis.scan(cursor, scanParams);
                    keys.addAll(scanResult.getResult());
                    cursor = scanResult.getCursor();
                } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
            } catch (Exception ignored) {
                // 跳过不可用的节点
            }
        }
        return keys;
    }

    /**
     * 获取key的类型
     */
    public static String getKeyType(ConnectionConfig config, int database, String key) {
        if (config.isRedisCluster()) {
            JedisCluster cluster = getJedisCluster(config);
            return cluster.type(key);
        }
        try (Jedis jedis = getJedis(config, database)) {
            return jedis.type(key);
        }
    }

    /**
     * 获取key的详细信息（包含type、value、ttl）
     */
    public static Map<String, Object> getKeyDetail(ConnectionConfig config, int database, String key) {
        Map<String, Object> detail = new LinkedHashMap<>();

        if (config.isRedisCluster()) {
            JedisCluster cluster = getJedisCluster(config);
            String type = cluster.type(key);
            long ttl = cluster.ttl(key);
            Object value = getValueByTypeCluster(cluster, key, type);
            detail.put("type", type.toLowerCase());
            detail.put("value", value);
            detail.put("ttl", ttl);
            return detail;
        }

        try (Jedis jedis = getJedis(config, database)) {
            String type = jedis.type(key);
            long ttl = jedis.ttl(key);
            Object value = getValueByType(jedis, key, type);
            detail.put("type", type.toLowerCase());
            detail.put("value", value);
            detail.put("ttl", ttl);
        }
        return detail;
    }

    /**
     * 根据类型获取值（单机模式）
     */
    private static Object getValueByType(Jedis jedis, String key, String type) {
        if (type == null) {
            return null;
        }
        switch (type.toLowerCase()) {
            case "string":
                return jedis.get(key);
            case "list":
                return jedis.lrange(key, 0, -1);
            case "set":
                return new ArrayList<>(jedis.smembers(key));
            case "zset":
                List<Tuple> tuples = jedis.zrangeWithScores(key, 0, -1);
                List<Map<String, Object>> zsetResult = new ArrayList<>();
                for (Tuple tuple : tuples) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("member", tuple.getElement());
                    item.put("score", tuple.getScore());
                    zsetResult.add(item);
                }
                return zsetResult;
            case "hash":
                return jedis.hgetAll(key);
            case "stream":
                try {
                    return jedis.xrange(key, StreamEntryID.MINIMUM_ID, StreamEntryID.MAXIMUM_ID, 100);
                } catch (Exception e) {
                    return Collections.emptyList();
                }
            default:
                return null;
        }
    }

    /**
     * 根据类型获取值（集群模式）
     */
    private static Object getValueByTypeCluster(JedisCluster cluster, String key, String type) {
        if (type == null) {
            return null;
        }
        switch (type.toLowerCase()) {
            case "string":
                return cluster.get(key);
            case "list":
                return cluster.lrange(key, 0, -1);
            case "set":
                return new ArrayList<>(cluster.smembers(key));
            case "zset":
                List<Tuple> tuples = cluster.zrangeWithScores(key, 0, -1);
                List<Map<String, Object>> zsetResult = new ArrayList<>();
                for (Tuple tuple : tuples) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("member", tuple.getElement());
                    item.put("score", tuple.getScore());
                    zsetResult.add(item);
                }
                return zsetResult;
            case "hash":
                return cluster.hgetAll(key);
            case "stream":
                try {
                    return cluster.xrange(key, StreamEntryID.MINIMUM_ID, StreamEntryID.MAXIMUM_ID, 100);
                } catch (Exception e) {
                    return Collections.emptyList();
                }
            default:
                return null;
        }
    }

    /**
     * 设置key的值（覆盖写入）
     * 对于已存在的key，先获取TTL，再DEL+写入，最后恢复TTL
     */
    @SuppressWarnings("unchecked")
    public static void setKeyValue(ConnectionConfig config, int database, String key, String type, Object value) {
        if (config.isRedisCluster()) {
            JedisCluster cluster = getJedisCluster(config);
            long ttl = cluster.ttl(key);
            cluster.del(key);
            setValueByTypeCluster(cluster, key, type, value);
            restoreTtlCluster(cluster, key, ttl);
            return;
        }

        try (Jedis jedis = getJedis(config, database)) {
            long ttl = jedis.ttl(key);
            jedis.del(key);
            setValueByType(jedis, key, type, value);
            restoreTtl(jedis, key, ttl);
        }
    }

    private static void restoreTtl(Jedis jedis, String key, long ttl) {
        if (ttl > 0) {
            jedis.expire(key, ttl);
        }
    }

    private static void restoreTtlCluster(JedisCluster cluster, String key, long ttl) {
        if (ttl > 0) {
            cluster.expire(key, ttl);
        }
    }

    /**
     * 根据类型设置值（单机模式）
     */
    @SuppressWarnings("unchecked")
    private static void setValueByType(Jedis jedis, String key, String type, Object value) {
        if (type == null || value == null) {
            return;
        }
        switch (type.toLowerCase()) {
            case "string":
                jedis.set(key, value.toString());
                break;
            case "list":
                if (value instanceof List) {
                    List<String> list = (List<String>) value;
                    if (!list.isEmpty()) {
                        jedis.rpush(key, list.toArray(new String[0]));
                    }
                }
                break;
            case "set":
                if (value instanceof Collection) {
                    Set<String> set = new HashSet<>((Collection<String>) value);
                    if (!set.isEmpty()) {
                        jedis.sadd(key, set.toArray(new String[0]));
                    }
                }
                break;
            case "zset":
                if (value instanceof List) {
                    List<Map<String, Object>> zsetList = (List<Map<String, Object>>) value;
                    Map<String, Double> scoreMembers = new LinkedHashMap<>();
                    for (Map<String, Object> item : zsetList) {
                        String member = String.valueOf(item.get("member"));
                        double score = item.get("score") instanceof Number
                                ? ((Number) item.get("score")).doubleValue()
                                : Double.parseDouble(String.valueOf(item.get("score")));
                        scoreMembers.put(member, score);
                    }
                    if (!scoreMembers.isEmpty()) {
                        jedis.zadd(key, scoreMembers);
                    }
                }
                break;
            case "hash":
                if (value instanceof Map) {
                    Map<String, String> hash = (Map<String, String>) value;
                    if (!hash.isEmpty()) {
                        jedis.hset(key, hash);
                    }
                }
                break;
            case "stream":
                if (value instanceof Map) {
                    Map<String, String> fields = (Map<String, String>) value;
                    jedis.xadd(key, new StreamEntryID(), fields);
                }
                break;
            default:
                throw new RuntimeException("不支持的类型: " + type);
        }
    }

    /**
     * 根据类型设置值（集群模式）
     */
    @SuppressWarnings("unchecked")
    private static void setValueByTypeCluster(JedisCluster cluster, String key, String type, Object value) {
        if (type == null || value == null) {
            return;
        }
        switch (type.toLowerCase()) {
            case "string":
                cluster.set(key, value.toString());
                break;
            case "list":
                if (value instanceof List) {
                    List<String> list = (List<String>) value;
                    if (!list.isEmpty()) {
                        cluster.rpush(key, list.toArray(new String[0]));
                    }
                }
                break;
            case "set":
                if (value instanceof Collection) {
                    Set<String> set = new HashSet<>((Collection<String>) value);
                    if (!set.isEmpty()) {
                        cluster.sadd(key, set.toArray(new String[0]));
                    }
                }
                break;
            case "zset":
                if (value instanceof List) {
                    List<Map<String, Object>> zsetList = (List<Map<String, Object>>) value;
                    Map<String, Double> scoreMembers = new LinkedHashMap<>();
                    for (Map<String, Object> item : zsetList) {
                        String member = String.valueOf(item.get("member"));
                        double score = item.get("score") instanceof Number
                                ? ((Number) item.get("score")).doubleValue()
                                : Double.parseDouble(String.valueOf(item.get("score")));
                        scoreMembers.put(member, score);
                    }
                    if (!scoreMembers.isEmpty()) {
                        cluster.zadd(key, scoreMembers);
                    }
                }
                break;
            case "hash":
                if (value instanceof Map) {
                    Map<String, String> hash = (Map<String, String>) value;
                    if (!hash.isEmpty()) {
                        cluster.hset(key, hash);
                    }
                }
                break;
            case "stream":
                if (value instanceof Map) {
                    Map<String, String> fields = (Map<String, String>) value;
                    cluster.xadd(key, new StreamEntryID(), fields);
                }
                break;
            default:
                throw new RuntimeException("不支持的类型: " + type);
        }
    }

    /**
     * 删除key
     */
    public static boolean deleteKey(ConnectionConfig config, int database, String key) {
        if (config.isRedisCluster()) {
            JedisCluster cluster = getJedisCluster(config);
            return cluster.del(key) > 0;
        }
        try (Jedis jedis = getJedis(config, database)) {
            return jedis.del(key) > 0;
        }
    }

    /**
     * 重命名key
     */
    public static boolean renameKey(ConnectionConfig config, int database, String oldKey, String newKey) {
        if (config.isRedisCluster()) {
            try {
                JedisCluster cluster = getJedisCluster(config);
                cluster.rename(oldKey, newKey);
                return true;
            } catch (Exception e) {
                throw new RuntimeException("重命名key失败: " + e.getMessage(), e);
            }
        }
        try (Jedis jedis = getJedis(config, database)) {
            jedis.rename(oldKey, newKey);
            return true;
        } catch (Exception e) {
            throw new RuntimeException("重命名key失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取TTL
     */
    public static long getTtl(ConnectionConfig config, int database, String key) {
        if (config.isRedisCluster()) {
            JedisCluster cluster = getJedisCluster(config);
            return cluster.ttl(key);
        }
        try (Jedis jedis = getJedis(config, database)) {
            return jedis.ttl(key);
        }
    }

    /**
     * 设置TTL
     */
    public static boolean setTtl(ConnectionConfig config, int database, String key, long seconds) {
        if (config.isRedisCluster()) {
            JedisCluster cluster = getJedisCluster(config);
            return cluster.expire(key, seconds) == 1L;
        }
        try (Jedis jedis = getJedis(config, database)) {
            return jedis.expire(key, seconds) == 1L;
        }
    }

    /**
     * 获取数据库大小（key数量）
     */
    public static long getDbSize(ConnectionConfig config, int database) {
        if (config.isRedisCluster()) {
            JedisCluster cluster = getJedisCluster(config);
            return cluster.dbSize();
        }
        try (Jedis jedis = getJedis(config, database)) {
            return jedis.dbSize();
        }
    }

    /**
     * 按 ":" 分隔符组织key为层级结构
     * 返回以 ":" 分隔的key前缀树结构
     */
    public static Map<String, Object> buildKeyHierarchy(List<String> keys) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (String key : keys) {
            String[] parts = key.split(":");
            Map<String, Object> current = root;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                if (i == parts.length - 1) {
                    // 叶子节点，存储完整key
                    current.put(part, key);
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> child = (Map<String, Object>) current.get(part);
                    if (child == null) {
                        child = new LinkedHashMap<>();
                        current.put(part, child);
                    }
                    current = child;
                }
            }
        }
        return root;
    }
}
