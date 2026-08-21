package com.tangluobo.tomato.module.connect.service;

import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.SshTunnelManager;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Kafka 管理服务，参考 RocketmqService 结构。
 *
 * SSH通道：仅对 bootstrap server 建立跳板隧道（SshTunnelManager.resolve），
 * AdminClient/KafkaConsumer 连接 localhost:转发端口。
 *
 * 说明：Kafka 与 RocketMQ 不同，客户端的 NetworkClient 对 broker 地址
 * （advertised.listeners）没有公开的装饰器/重映射扩展点。因此当集群多 Broker
 * 且 advertised 为内网 IP 时，仅 bootstrap 隧道无法让消费者访问其它 Broker。
 * 本实现适用于：单 Broker 集群，或 Broker advertised 地址可达的场景。
 */
public class KafkaService {
    private static final Map<String, AdminClient> adminCache = new ConcurrentHashMap<>();

    /** 最近一次查询的内部诊断信息（每个线程独立），UI 在查询失败时可读取展示 */
    private static final ThreadLocal<StringBuilder> lastDiag = ThreadLocal.withInitial(StringBuilder::new);

    public static String getLastDiag() {
        return lastDiag.get().toString();
    }

    /** 把诊断日志同时写入工作目录下的 kafka_diag.log 文件，方便用户排查 */
    private static void logFile(String msg) {
        try (java.io.FileWriter fw = new java.io.FileWriter("kafka_diag.log", true)) {
            fw.write(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new java.util.Date()) + " " + msg + "\n");
        } catch (Exception ignored) {}
    }

    private static void log(String msg) {
        System.out.println(msg);
        logFile(msg);
    }

    private static void logErr(String msg, Throwable t) {
        System.err.println(msg);
        t.printStackTrace();
        try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter("kafka_diag.log", true))) {
            pw.println(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new java.util.Date()) + " " + msg);
            t.printStackTrace(pw);
        } catch (Exception ignored) {}
    }

    private KafkaService() {}

    static {
        // 强制使用 IPv4，避免 Java 客户端优先尝试 IPv6 导致连接超时
        // （Kafka 命令行工具默认走 IPv4，所以能连但 Java 客户端超时）
        System.setProperty("java.net.preferIPv4Stack", "true");
    }

    // ==================== AdminClient 生命周期（含 SSH 隧道） ====================

    /**
     * 获取或创建 AdminClient。启用 SSH 通道时建立/复用跳板隧道并获取引用计数。
     */
    public static AdminClient getAdmin(ConnectionConfig config) {
        String cacheKey = adminCacheKey(config);
        AdminClient cached = adminCache.get(cacheKey);
        if (cached != null) return cached;
        synchronized (KafkaService.class) {
            cached = adminCache.get(cacheKey);
            if (cached != null) return cached;
            String bootstrap = resolveBootstrap(config); // 启用隧道则建立并获取引用计数
            AdminClient admin = AdminClient.create(buildAdminProps(bootstrap));
            adminCache.put(cacheKey, admin);
            return admin;
        }
    }

    /**
     * 测试连接（启用 SSH 通道时建立临时隧道，测试后释放）。
     * 返回 null 表示成功，返回非空字符串表示失败原因。
     */
    public static String testConnection(ConnectionConfig config) {
        boolean tunnelAcquired = false;
        AdminClient admin = null;
        String bootstrap = null;
        try {
            bootstrap = resolveBootstrap(config);
            tunnelAcquired = config.isUseSshTunnel() && config.getSshTunnelHostId() != null;

            // 第一步：原始 TCP 连接测试，区分网络问题与 Kafka 客户端问题
            String[] hp = bootstrap.split(":");
            String tcpHost = hp[0];
            int tcpPort = Integer.parseInt(hp[1]);
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(tcpHost, tcpPort), 5000);
                // 同时打印 DNS 解析后的 IP，便于排查 IPv6 / DNS 问题
                java.net.InetAddress resolved = java.net.InetAddress.getByName(tcpHost);
                System.out.println("[KafkaService] TCP 连接成功: " + tcpHost + ":" + tcpPort
                        + " (解析地址: " + resolved.getHostAddress() + ")");
            } catch (Exception tcpEx) {
                System.err.println("[KafkaService] TCP 连接失败: " + tcpHost + ":" + tcpPort + " - " + tcpEx.getMessage());
                return "TCP连接失败(" + tcpHost + ":" + tcpPort + "): " + tcpEx.getClass().getSimpleName() + ": " + tcpEx.getMessage();
            }

            // 第二步：Kafka AdminClient 连接测试
            admin = AdminClient.create(buildAdminProps(bootstrap));
            admin.listTopics().names().get(30, TimeUnit.SECONDS);
            return null;
        } catch (java.util.concurrent.TimeoutException te) {
            // TimeoutException.getMessage() 默认返回 null，导致原显示为 "TimeoutException:null"。
            // 这里补充诊断信息，便于用户定位 advertised.listeners / 端口 / SASL 等常见问题。
            String diag = "Kafka AdminClient 连接超时(30s)\n"
                    + "  bootstrap=" + bootstrap + "\n"
                    + "  SSH隧道=" + (tunnelAcquired ? "已启用" : "未启用") + "\n"
                    + "常见原因:\n"
                    + "  1) broker 的 advertised.listeners 配置为 localhost/主机名/内网IP，客户端无法访问\n"
                    + "  2) 端口错误（如连了 ZooKeeper 的 2181 端口，TCP 通但 Kafka 协议不通）\n"
                    + "  3) broker 启用了 SASL/SSL，但客户端未提供凭证\n"
                    + "  4) 客户端到 broker 网络存在 accept 但不转发的假连接";
            System.err.println("[KafkaService] " + diag);
            return "TimeoutException: " + diag;
        } catch (Throwable e) {
            // 异常 message 可能为 null，回退到 toString() 至少保留类名
            String msg = e.getMessage();
            if (msg == null || msg.isEmpty()) {
                msg = e.toString();
            }
            e.printStackTrace();
            return e.getClass().getSimpleName() + ": " + msg;
        } finally {
            if (admin != null) {
                try { admin.close(Duration.ofSeconds(3)); } catch (Exception ignored) {}
            }
            if (tunnelAcquired) SshTunnelManager.release(config);
        }
    }

    /**
     * 关闭指定配置的 AdminClient，并释放 SSH 跳板隧道引用。
     */
    public static void closeAdmin(ConnectionConfig config) {
        String cacheKey = adminCacheKey(config);
        AdminClient admin = adminCache.remove(cacheKey);
        if (admin != null) {
            try { admin.close(Duration.ofSeconds(3)); } catch (Exception ignored) {}
        }
        SshTunnelManager.release(config);
    }

    public static void closeAllAdmins() {
        for (AdminClient admin : adminCache.values()) {
            try { admin.close(Duration.ofSeconds(3)); } catch (Exception ignored) {}
        }
        adminCache.clear();
    }

    /**
     * AdminClient 缓存键：隧道配置按 configId 区分。
     */
    private static String adminCacheKey(ConnectionConfig config) {
        String base = config.getHost() + ":" + config.getPort();
        if (config.isUseSshTunnel() && config.getSshTunnelHostId() != null) {
            return config.getId() + "_tunnel_" + base;
        }
        return base;
    }

    /**
     * 解析实际连接的 bootstrap 地址：启用隧道则建立/复用跳板隧道并获取引用计数，返回 localhost:转发端口。
     */
    private static String resolveBootstrap(ConnectionConfig config) {
        if (config.isUseSshTunnel() && config.getSshTunnelHostId() != null) {
            try {
                int localPort = SshTunnelManager.resolve(config);
                return "localhost:" + localPort;
            } catch (Exception e) {
                throw new RuntimeException("建立SSH跳板隧道失败: " + e.getMessage(), e);
            }
        }
        return config.getHost() + ":" + config.getPort();
    }

    /**
     * 获取 bootstrap 地址（短生命周期消费者用）：复用 getAdmin 已建立的隧道（peek，不增减引用计数）。
     */
    private static String bootstrap(ConnectionConfig config) {
        int localPort = SshTunnelManager.peek(config);
        if (localPort != -1) {
            return "localhost:" + localPort;
        }
        return config.getHost() + ":" + config.getPort();
    }

    private static Properties buildAdminProps(String bootstrap) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 60000);
        props.put(AdminClientConfig.CLIENT_ID_CONFIG, "tomato-kafka-admin");
        props.put(AdminClientConfig.RECONNECT_BACKOFF_MS_CONFIG, 50);
        props.put(AdminClientConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, 3000);
        props.put(AdminClientConfig.RETRY_BACKOFF_MS_CONFIG, 100);
        return props;
    }

    private static Properties buildConsumerProps(String bootstrap, String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                OffsetResetStrategy.EARLIEST.name().toLowerCase());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 60000);
        props.put(ConsumerConfig.RECONNECT_BACKOFF_MS_CONFIG, 50);
        props.put(ConsumerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, 3000);
        props.put(ConsumerConfig.RETRY_BACKOFF_MS_CONFIG, 100);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 256);
        return props;
    }

    // ==================== 主题管理 ====================

    /**
     * 获取所有主题列表（含分区数、副本数）。
     */
    public static List<Map<String, Object>> getTopicList(ConnectionConfig config) throws Exception {
        AdminClient admin = getAdmin(config);
        Set<String> names = admin.listTopics().names().get();
        // 过滤内部主题
        List<String> external = new ArrayList<>();
        for (String n : names) {
            if (!n.startsWith("__") && !n.equals("_confluent")) external.add(n);
        }
        Map<String, TopicDescription> desc = external.isEmpty()
                ? Collections.emptyMap()
                : admin.describeTopics(external).allTopicNames().get();
        List<Map<String, Object>> result = new ArrayList<>();
        for (String topic : external) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("topic", topic);
            TopicDescription td = desc.get(topic);
            if (td != null) {
                item.put("partitions", td.partitions().size());
                item.put("replicationFactor", td.partitions().isEmpty() ? 0
                        : td.partitions().get(0).replicas().size());
                item.put("topicType", td.isInternal() ? "SYSTEM" : "NORMAL");
            } else {
                item.put("partitions", 0);
                item.put("replicationFactor", 0);
                item.put("topicType", "NORMAL");
            }
            result.add(item);
        }
        result.sort(Comparator.comparing(m -> String.valueOf(m.get("topic"))));
        return result;
    }

    /**
     * 获取主题分区偏移信息（min/max offset）。
     */
    public static Map<String, Object> getTopicStats(ConnectionConfig config, String topic) throws Exception {
        AdminClient admin = getAdmin(config);
        TopicDescription td = admin.describeTopics(Collections.singleton(topic))
                .allTopicNames().get().get(topic);
        List<Map<String, Object>> offsetList = new ArrayList<>();
        if (td != null) {
            List<TopicPartition> partitions = new ArrayList<>();
            for (int i = 0; i < td.partitions().size(); i++) {
                partitions.add(new TopicPartition(topic, i));
            }
            // 使用临时 consumer 获取 end offsets
            Map<TopicPartition, Long> endOffsets = endOffsets(config, topic, partitions);
            for (TopicPartitionInfo pi : td.partitions()) {
                Map<String, Object> info = new LinkedHashMap<>();
                TopicPartition tp = new TopicPartition(topic, pi.partition());
                info.put("partition", pi.partition());
                info.put("leader", pi.leader() != null ? pi.leader().id() : -1);
                info.put("replicaCount", pi.replicas() != null ? pi.replicas().size() : 0);
                info.put("minOffset", 0);
                info.put("maxOffset", endOffsets.getOrDefault(tp, 0L));
                offsetList.add(info);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("offsetTable", offsetList);
        return result;
    }

    /** 创建主题 */
    public static void createTopic(ConnectionConfig config, String topic, int partitions, short replicationFactor) throws Exception {
        AdminClient admin = getAdmin(config);
        NewTopic newTopic = new NewTopic(topic, partitions, replicationFactor);
        admin.createTopics(Collections.singleton(newTopic)).all().get();
    }

    /** 删除主题 */
    public static void deleteTopic(ConnectionConfig config, String topic) throws Exception {
        AdminClient admin = getAdmin(config);
        admin.deleteTopics(Collections.singleton(topic)).all().get();
    }

    // ==================== 消费者组管理 ====================

    /**
     * 获取消费者组列表（含总积压量）。
     */
    public static List<Map<String, Object>> getConsumerGroupList(ConnectionConfig config) throws Exception {
        AdminClient admin = getAdmin(config);
        List<String> groups = new ArrayList<>(admin.listGroups(ListGroupsOptions.forConsumerGroups()).all().get()
                .stream().map(g -> g.groupId()).collect(java.util.stream.Collectors.toSet()));
        Collections.sort(groups);
        // 过滤本工具内部临时组
        groups.removeIf(g -> g != null && g.startsWith("tomato_kafka_"));

        List<Map<String, Object>> result = new ArrayList<>();
        for (String group : groups) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("group", group);
            try {
                long diff = computeGroupLag(config, group);
                item.put("diffTotal", diff);
            } catch (Exception ignored) {
                item.put("diffTotal", 0);
            }
            result.add(item);
        }
        return result;
    }

    /**
     * 计算消费者组总积压量（endOffset - consumerOffset 之和）。
     */
    private static long computeGroupLag(ConnectionConfig config, String group) throws Exception {
        AdminClient admin = getAdmin(config);
        ListConsumerGroupOffsetsResult o = admin.listConsumerGroupOffsets(group);
        Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets;
        try {
            offsets = o.partitionsToOffsetAndMetadata().get();
        } catch (Exception e) {
            return 0;
        }
        if (offsets == null || offsets.isEmpty()) return 0;
        // 按 topic 分组取 end offsets
        Map<String, List<TopicPartition>> byTopic = new HashMap<>();
        for (TopicPartition tp : offsets.keySet()) {
            byTopic.computeIfAbsent(tp.topic(), k -> new ArrayList<>()).add(tp);
        }
        long totalDiff = 0;
        for (Map.Entry<String, List<TopicPartition>> e : byTopic.entrySet()) {
            Map<TopicPartition, Long> endOffsets = endOffsets(config, e.getKey(), e.getValue());
            for (TopicPartition tp : e.getValue()) {
                long consumerOffset = offsets.containsKey(tp) ? offsets.get(tp).offset() : 0L;
                long endOffset = endOffsets.getOrDefault(tp, 0L);
                totalDiff += Math.max(0, endOffset - consumerOffset);
            }
        }
        return totalDiff;
    }

    /**
     * 获取消费者组详情（各分区消费偏移、积压量）。
     */
    public static Map<String, Object> getConsumerGroupDetail(ConnectionConfig config, String group) throws Exception {
        AdminClient admin = getAdmin(config);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets =
                    admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();
            // 消费者组状态/协议类型
            try {
                ConsumerGroupDescription desc =
                        admin.describeConsumerGroups(Collections.singleton(group)).all().get().get(group);
                result.put("state", desc.state() != null ? desc.state().name() : "UNKNOWN");
                result.put("members", desc.members() != null ? desc.members().size() : 0);
            } catch (Exception ignored) {}

            Map<String, List<TopicPartition>> byTopic = new HashMap<>();
            for (TopicPartition tp : offsets.keySet()) {
                byTopic.computeIfAbsent(tp.topic(), k -> new ArrayList<>()).add(tp);
            }
            List<Map<String, Object>> offsetList = new ArrayList<>();
            long totalDiff = 0;
            for (Map.Entry<String, List<TopicPartition>> e : byTopic.entrySet()) {
                Map<TopicPartition, Long> endOffsets = endOffsets(config, e.getKey(), e.getValue());
                for (TopicPartition tp : e.getValue()) {
                    long consumerOffset = offsets.containsKey(tp) ? offsets.get(tp).offset() : 0L;
                    long endOffset = endOffsets.getOrDefault(tp, 0L);
                    long diff = Math.max(0, endOffset - consumerOffset);
                    totalDiff += diff;
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("topic", tp.topic());
                    info.put("partition", tp.partition());
                    info.put("brokerOffset", endOffset);
                    info.put("consumerOffset", consumerOffset);
                    info.put("diff", diff);
                    offsetList.add(info);
                }
            }
            result.put("totalDiff", totalDiff);
            result.put("offsetTable", offsetList);
        } catch (Exception e) {
            // 无偏移提交的消费者组
            result.put("totalDiff", 0L);
            result.put("offsetTable", Collections.emptyList());
            result.put("warning", "该消费者组无已提交偏移，可能尚未消费或使用 assign 模式。");
        }
        return result;
    }

    /** 删除消费者组 */
    public static void deleteConsumerGroup(ConnectionConfig config, String group) throws Exception {
        AdminClient admin = getAdmin(config);
        admin.deleteConsumerGroups(Collections.singleton(group)).all().get();
    }

    // ==================== 集群信息 ====================

    /**
     * 获取集群 Broker 列表。
     */
    public static List<Map<String, Object>> getClusterInfo(ConnectionConfig config) throws Exception {
        AdminClient admin = getAdmin(config);
        Collection<Node> nodes = admin.describeCluster().nodes().get();
        // 控制器
        Node controller = admin.describeCluster().controller().get();
        int controllerId = controller != null ? controller.id() : -1;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Node n : nodes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("brokerId", n.id());
            item.put("brokerName", "broker-" + n.id());
            item.put("address", n.host() + ":" + n.port());
            item.put("rack", n.rack() != null ? n.rack() : "");
            item.put("role", n.id() == controllerId ? "CONTROLLER" : "BROKER");
            result.add(item);
        }
        result.sort(Comparator.comparing(m -> (Integer) m.get("brokerId")));
        return result;
    }

    // ==================== 消息查询 ====================

    /**
     * 按时间范围查询消息（seek 到 begin 时间，poll 直到超过 end 时间）。
     */
    public static List<Map<String, Object>> queryMessageByTime(ConnectionConfig config, String topic, long begin, long end) throws Exception {
        List<TopicPartition> partitions = topicPartitions(config, topic);
        if (partitions.isEmpty()) {
            System.out.println("[KafkaService] queryMessageByTime: 主题 " + topic + " 无分区，返回空");
            return Collections.emptyList();
        }
        String bootstrap = bootstrap(config);
        System.out.println("[KafkaService] queryMessageByTime 开始: topic=" + topic
                + " partitions=" + partitions.size()
                + " begin=" + java.time.Instant.ofEpochMilli(begin)
                + " end=" + java.time.Instant.ofEpochMilli(end)
                + " bootstrap=" + bootstrap);
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(buildConsumerProps(bootstrap, "tomato_kafka_time_" + System.currentTimeMillis()));
        try {
            consumer.assign(partitions);
            // seek 到 begin 时间
            Map<TopicPartition, Long> timestamps = new HashMap<>();
            for (TopicPartition tp : partitions) timestamps.put(tp, begin);
            Map<TopicPartition, Long> seekOffsets = new HashMap<>();
            for (Map.Entry<TopicPartition, OffsetAndTimestamp> e : consumer.offsetsForTimes(timestamps).entrySet()) {
                if (e.getValue() != null) {
                    seekOffsets.put(e.getKey(), e.getValue().offset());
                    System.out.println("[KafkaService]   分区 " + e.getKey().partition()
                            + " 命中 begin: offset=" + e.getValue().offset());
                } else {
                    System.out.println("[KafkaService]   分区 " + e.getKey().partition()
                            + " 无 begin 命中（分区所有消息都早于 begin，将 seekToBeginning）");
                }
            }
            for (Map.Entry<TopicPartition, Long> e : seekOffsets.entrySet()) {
                consumer.seek(e.getKey(), e.getValue());
            }
            // 对于无时间命中分区，从最早开始
            for (TopicPartition tp : partitions) {
                if (!seekOffsets.containsKey(tp)) consumer.seekToBeginning(Collections.singleton(tp));
            }

            List<Map<String, Object>> messages = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 10000; // 最多查询10秒
            int emptyAfterData = 0;
            boolean gotData = false;
            // assign 模式下前几次 poll 可能因 metadata 加载返回空，不提前退出；
            // 但拿到数据后 1 次空 poll 即视为已读完。
            while (System.currentTimeMillis() < deadline && messages.size() < 256) {
                var records = consumer.poll(Duration.ofMillis(200));
                if (records.isEmpty()) {
                    if (gotData && ++emptyAfterData >= 1) break;
                    continue;
                }
                emptyAfterData = 0;
                gotData = true;
                boolean allPastEnd = true;
                for (var record : records) {
                    if (record.timestamp() <= end) {
                        allPastEnd = false;
                        if (record.timestamp() >= begin) {
                            messages.add(convertRecord(record));
                        }
                    }
                }
                // 本次 poll 的所有 records 都超过 end 时间，说明涉及的所有分区已读到尾部
                if (allPastEnd) break;
            }
            System.out.println("[KafkaService] queryMessageByTime 完成: 共 " + messages.size() + " 条消息");
            return messages;
        } finally {
            Thread t = new Thread(() -> {
                try { consumer.close(Duration.ofMillis(1000)); } catch (Exception ignored) {}
            }, "Kafka-ConsumerCloser");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * 按分区+起始偏移查询消息（从指定 offset 开始拉取 maxCount 条）。
     */
    public static List<Map<String, Object>> queryMessageByOffset(ConnectionConfig config, String topic, int partition, long offset, int maxCount) throws Exception {
        StringBuilder diag = lastDiag.get();
        diag.setLength(0);
        TopicPartition tp = new TopicPartition(topic, partition);
        String bootstrap = bootstrap(config);
        diag.append("bootstrap=").append(bootstrap).append("\n")
            .append("topic=").append(topic).append(" partition=").append(partition)
            .append(" offset=").append(offset).append("\n");
        log("[KafkaService] ====== queryMessageByOffset 开始 ======");
        log("[KafkaService] topic=" + topic + " partition=" + partition + " offset=" + offset
                + " bootstrap=" + bootstrap);
        // 打印调用方堆栈，确认是谁调用的 queryMessageByOffset
        java.io.StringWriter sw = new java.io.StringWriter();
        new Throwable().printStackTrace(new java.io.PrintWriter(sw));
        log("[KafkaService] 调用方堆栈:\n" + sw.toString());
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(buildConsumerProps(bootstrap, "tomato_kafka_offset_" + System.currentTimeMillis()));
        try {
            if (offset < 0) offset = 0;
            List<Map<String, Object>> messages = new ArrayList<>();

            // 方案 1：assign + seek + poll（手动分配，直接定位到指定 offset）
            log("[KafkaService] === 方案 1: assign + seek + poll ===");
            consumer.assign(Collections.singleton(tp));
            consumer.seek(tp, offset);
            log("[KafkaService] assign+seek 完成");
            long deadline1 = System.currentTimeMillis() + 10000;
            int pollCount1 = 0;
            int recordsCount1 = 0;
            int emptyAfterData = 0;   // 拿到数据后连续空 poll 的次数
            boolean gotData = false;
            while (System.currentTimeMillis() < deadline1 && messages.size() < maxCount) {
                try {
                    var records = consumer.poll(Duration.ofMillis(200));
                    pollCount1++;
                    recordsCount1 += records.count();
                    log("[KafkaService] [assign] poll#" + pollCount1 + " records=" + records.count() + " total=" + messages.size());
                    if (records.isEmpty()) {
                        // 拿到数据后 1 次空 poll 即视为已读完，提前退出
                        if (gotData && ++emptyAfterData >= 1) {
                            log("[KafkaService] [assign] 拿到数据后空 poll，提前退出");
                            break;
                        }
                        continue;
                    }
                    emptyAfterData = 0;
                    gotData = true;
                    for (var record : records) {
                        messages.add(convertRecord(record));
                        if (messages.size() >= maxCount) break;
                    }
                } catch (Exception e) {
                    logErr("[KafkaService] [assign] poll#" + pollCount1 + " 异常", e);
                    throw e;
                }
            }
            diag.append("[assign+seek] poll次数=").append(pollCount1)
                .append(" 累计records=").append(recordsCount1)
                .append(" 最终messages=").append(messages.size()).append("\n");
            log("[KafkaService] [assign] 结束: poll=" + pollCount1 + " records=" + recordsCount1 + " messages=" + messages.size());

            // 方案 2：assign 模式拿不到数据时，fallback 到 subscribe 模式（与 console-consumer 一致）
            // 注意：这里完全不过滤 partition/offset，先看能不能拿到任何数据
            if (messages.isEmpty()) {
                log("[KafkaService] === 方案 2: subscribe + poll (不过滤，所有 partition 全收) ===");
                diag.append("assign 模式拿不到数据，fallback 到 subscribe 模式（不过滤，所有 partition 全收）\n");
                consumer.unsubscribe();
                consumer.subscribe(Collections.singleton(topic));
                long deadline2 = System.currentTimeMillis() + 10000;
                int pollCount2 = 0;
                int recordsCount2 = 0;
                while (System.currentTimeMillis() < deadline2 && messages.size() < maxCount) {
                    try {
                        var records = consumer.poll(Duration.ofMillis(200));
                        pollCount2++;
                        recordsCount2 += records.count();
                        log("[KafkaService] [subscribe] poll#" + pollCount2 + " records=" + records.count() + " total=" + messages.size());
                        if (records.isEmpty() && !messages.isEmpty()) break;
                        for (var record : records) {
                            messages.add(convertRecord(record));
                            if (messages.size() >= maxCount) break;
                        }
                    } catch (Exception e) {
                        logErr("[KafkaService] [subscribe] poll#" + pollCount2 + " 异常", e);
                        throw e;
                    }
                }
                diag.append("[subscribe] poll次数=").append(pollCount2)
                    .append(" 累计records=").append(recordsCount2)
                    .append(" 最终messages=").append(messages.size()).append("\n");
                log("[KafkaService] [subscribe] 结束: poll=" + pollCount2 + " records=" + recordsCount2 + " messages=" + messages.size());
                if (!messages.isEmpty()) {
                    var first = messages.get(0);
                    diag.append("  第一条: partition=").append(first.get("partition"))
                        .append(" offset=").append(first.get("offset"))
                        .append(" timestamp=").append(first.get("timestamp")).append("\n");
                    diag.append("  ⚠️ subscribe 拿到数据了！但你查询的 partition=").append(partition)
                        .append(" offset=").append(offset).append(" 可能不匹配\n");
                    log("[KafkaService] subscribe 拿到数据了！第一条 partition=" + first.get("partition")
                            + " offset=" + first.get("offset") + " timestamp=" + first.get("timestamp"));
                }
            }

            if (messages.isEmpty()) {
                diag.append("⚠️ assign 和 subscribe 模式都拿不到数据\n");
                diag.append("可能原因:\n");
                diag.append("  1) partition 无数据或 offset 超过 endOffset\n");
                diag.append("  2) broker 端启用了 SASL/SSL，客户端未提供凭证\n");
                diag.append("  3) docker 容器端口映射异常\n");
                log("[KafkaService] ⚠️ assign 和 subscribe 模式都拿不到数据！详见上面的 poll 日志");
            }
            log("[KafkaService] ====== queryMessageByOffset 完成: 共 " + messages.size() + " 条消息 ======");
            return messages;
        } finally {
            Thread t = new Thread(() -> {
                try { consumer.close(Duration.ofMillis(1000)); } catch (Exception ignored) {}
            }, "Kafka-ConsumerCloser");
            t.setDaemon(true);
            t.start();
        }
    }

    /**
     * 查询消费者组未消费消息（consumerOffset ~ endOffset）。
     */
    public static List<Map<String, Object>> queryUnconsumedMessages(ConnectionConfig config, String topic, String group, int maxCount) throws Exception {
        AdminClient admin = getAdmin(config);
        Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets =
                admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();
        List<TopicPartition> topicPartitions = new ArrayList<>();
        Map<TopicPartition, Long> consumerOffsets = new HashMap<>();
        for (Map.Entry<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> e : offsets.entrySet()) {
            if (topic.equals(e.getKey().topic())) {
                topicPartitions.add(e.getKey());
                consumerOffsets.put(e.getKey(), e.getValue().offset());
            }
        }
        if (topicPartitions.isEmpty()) return Collections.emptyList();

        String bootstrap = bootstrap(config);
        List<Map<String, Object>> result = new ArrayList<>();
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(buildConsumerProps(bootstrap, "tomato_kafka_unconsumed_" + System.currentTimeMillis()));
        try {
            consumer.assign(topicPartitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(topicPartitions);
            for (TopicPartition tp : topicPartitions) {
                long start = consumerOffsets.getOrDefault(tp, 0L);
                long end = endOffsets.getOrDefault(tp, 0L);
                if (start >= end) continue;
                consumer.seek(tp, start);
            }
            long deadline = System.currentTimeMillis() + 10000;
            boolean gotData = false;
            while (System.currentTimeMillis() < deadline && result.size() < maxCount) {
                var records = consumer.poll(Duration.ofMillis(200));
                if (records.isEmpty()) {
                    if (gotData) break;
                    continue;
                }
                gotData = true;
                for (var record : records) {
                    // 超过 endOffset 的不再计入
                    if (record.offset() >= endOffsets.getOrDefault(new TopicPartition(record.topic(), record.partition()), 0L)) continue;
                    result.add(convertRecord(record));
                    if (result.size() >= maxCount) break;
                }
            }
        } finally {
            Thread t = new Thread(() -> {
                try { consumer.close(Duration.ofMillis(1000)); } catch (Exception ignored) {}
            }, "Kafka-ConsumerCloser");
            t.setDaemon(true);
            t.start();
        }
        return result;
    }

    /**
     * 获取主题的消费状态（各消费者组积压情况）。
     */
    public static List<Map<String, Object>> getTopicConsumeStatus(ConnectionConfig config, String topic) throws Exception {
        AdminClient admin = getAdmin(config);
        List<String> groups = new ArrayList<>(admin.listGroups(ListGroupsOptions.forConsumerGroups()).all().get()
                .stream().map(g -> g.groupId()).collect(java.util.stream.Collectors.toSet()));
        groups.removeIf(g -> g != null && g.startsWith("tomato_kafka_"));
        List<TopicPartition> partitions = topicPartitions(config, topic);
        Map<TopicPartition, Long> endOffsets = partitions.isEmpty() ? Collections.emptyMap() : endOffsets(config, topic, partitions);

        List<Map<String, Object>> result = new ArrayList<>();
        for (String group : groups) {
            try {
                Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets =
                        admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();
                long totalDiff = 0;
                boolean subscribed = false;
                for (Map.Entry<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> e : offsets.entrySet()) {
                    if (!topic.equals(e.getKey().topic())) continue;
                    subscribed = true;
                    long end = endOffsets.getOrDefault(e.getKey(), 0L);
                    totalDiff += Math.max(0, end - e.getValue().offset());
                }
                if (subscribed) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("group", group);
                    item.put("diffTotal", totalDiff);
                    result.add(item);
                }
            } catch (Exception ignored) {}
        }
        return result;
    }

    // ==================== 消息发送 ====================

    /**
     * 发送消息到指定主题。
     * partition 为 null 时由默认分区器选择分区；key/headers 可为 null。
     * 返回包含 topic/partition/offset/timestamp 的 Map。
     */
    public static Map<String, Object> sendMessage(ConnectionConfig config, String topic, Integer partition, String key, String value, Map<String, String> headers) throws Exception {
        String bootstrap = bootstrap(config);
        System.out.println("[KafkaService] sendMessage: topic=" + topic + " partition=" + partition
                + " key=" + key + " valueLen=" + (value != null ? value.length() : 0)
                + " bootstrap=" + bootstrap);
        KafkaProducer<String, String> producer = new KafkaProducer<>(buildProducerProps(bootstrap));
        try {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, partition, key, value);
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    if (h.getKey() == null || h.getKey().isEmpty()) continue;
                    String hVal = h.getValue() != null ? h.getValue() : "";
                    record.headers().add(new RecordHeader(h.getKey(), hVal.getBytes("UTF-8")));
                }
            }
            RecordMetadata metadata = producer.send(record).get();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("topic", metadata.topic());
            result.put("partition", metadata.partition());
            result.put("offset", metadata.offset());
            result.put("timestamp", metadata.timestamp());
            System.out.println("[KafkaService] sendMessage 成功: partition=" + metadata.partition()
                    + " offset=" + metadata.offset());
            return result;
        } finally {
            Thread t = new Thread(() -> {
                try { producer.close(Duration.ofMillis(1000)); } catch (Exception ignored) {}
            }, "Kafka-ProducerCloser");
            t.setDaemon(true);
            t.start();
        }
    }

    private static Properties buildProducerProps(String bootstrap) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "tomato-kafka-producer");
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);
        props.put(ProducerConfig.RECONNECT_BACKOFF_MS_CONFIG, 50);
        props.put(ProducerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, 3000);
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 100);
        return props;
    }

    // ==================== 辅助方法 ====================

    /** 获取主题分区列表 */
    private static List<TopicPartition> topicPartitions(ConnectionConfig config, String topic) throws Exception {
        AdminClient admin = getAdmin(config);
        TopicDescription td = admin.describeTopics(Collections.singleton(topic))
                .allTopicNames().get().get(topic);
        if (td == null) return Collections.emptyList();
        List<TopicPartition> list = new ArrayList<>();
        for (int i = 0; i < td.partitions().size(); i++) {
            list.add(new TopicPartition(topic, i));
        }
        return list;
    }

    /** 使用临时 consumer 获取 end offsets */
    private static Map<TopicPartition, Long> endOffsets(ConnectionConfig config, String topic, List<TopicPartition> partitions) {
        if (partitions.isEmpty()) return Collections.emptyMap();
        String bootstrap = bootstrap(config);
        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(buildConsumerProps(bootstrap, "tomato_kafka_offsets_" + System.currentTimeMillis()));
        try {
            return consumer.endOffsets(partitions);
        } catch (Exception e) {
            return Collections.emptyMap();
        } finally {
            Thread t = new Thread(() -> {
                try { consumer.close(Duration.ofMillis(1000)); } catch (Exception ignored) {}
            }, "Kafka-ConsumerCloser");
            t.setDaemon(true);
            t.start();
        }
    }

    /** 转换 ConsumerRecord 为 Map */
    private static Map<String, Object> convertRecord(org.apache.kafka.clients.consumer.ConsumerRecord<String, byte[]> record) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("topic", record.topic());
        item.put("partition", record.partition());
        item.put("offset", record.offset());
        item.put("timestamp", record.timestamp());
        item.put("timestampType", record.timestampType() != null ? record.timestampType().name() : "");
        item.put("key", record.key() != null ? record.key() : "");
        item.put("headers", convertHeaders(record.headers()));
        try {
            item.put("body", new String(record.value(), "UTF-8"));
        } catch (Exception e) {
            item.put("body", "[二进制数据 " + (record.value() != null ? record.value().length : 0) + " bytes]");
        }
        return item;
    }

    private static List<Map<String, Object>> convertHeaders(Headers headers) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (headers == null) return list;
        for (var h : headers) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", h.key());
            try {
                m.put("value", new String(h.value(), "UTF-8"));
            } catch (Exception e) {
                m.put("value", "[binary]");
            }
            list.add(m);
        }
        return list;
    }
}
