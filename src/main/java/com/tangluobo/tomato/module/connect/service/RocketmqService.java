package com.tangluobo.tomato.module.connect.service;

import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.SshTunnelManager;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.QueryResult;
import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.consumer.PullResult;
import org.apache.rocketmq.client.consumer.PullStatus;
import org.apache.rocketmq.client.impl.MQClientAPIImpl;
import org.apache.rocketmq.client.impl.consumer.DefaultMQPullConsumerImpl;
import org.apache.rocketmq.client.impl.factory.MQClientInstance;
import org.apache.rocketmq.client.impl.producer.DefaultMQProducerImpl;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.remoting.InvokeCallback;
import org.apache.rocketmq.remoting.RemotingClient;
import org.apache.rocketmq.remoting.RPCHook;
import org.apache.rocketmq.remoting.netty.NettyRequestProcessor;
import org.apache.rocketmq.remoting.pipeline.RequestPipeline;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.apache.rocketmq.remoting.protocol.admin.ConsumeStats;
import org.apache.rocketmq.remoting.protocol.admin.OffsetWrapper;
import org.apache.rocketmq.remoting.protocol.admin.TopicOffset;
import org.apache.rocketmq.remoting.protocol.admin.TopicStatsTable;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.remoting.protocol.body.ClusterInfo;
import org.apache.rocketmq.remoting.protocol.body.ConsumerConnection;
import org.apache.rocketmq.remoting.protocol.body.Connection;
import org.apache.rocketmq.remoting.protocol.body.KVTable;
import org.apache.rocketmq.remoting.protocol.body.SubscriptionGroupWrapper;
import org.apache.rocketmq.remoting.protocol.body.TopicList;
import org.apache.rocketmq.remoting.protocol.route.BrokerData;
import org.apache.rocketmq.remoting.protocol.route.TopicRouteData;
import org.apache.rocketmq.remoting.protocol.route.QueueData;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExtImpl;
import org.apache.rocketmq.common.message.MessageQueue;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

public class RocketmqService {
    // 缓存MQAdminExt实例，key为nameServer地址
    private static final Map<String, DefaultMQAdminExt> adminCache = new ConcurrentHashMap<>();

    /**
     * 获取或创建MQAdminExt实例。
     * 启用SSH通道时，通过跳板机建立本地端口转发，Admin连接 localhost:转发端口。
     * Admin 长生命周期持有隧道引用计数（closeAdmin 时释放）。
     */
    public static DefaultMQAdminExt getAdmin(ConnectionConfig config) throws MQClientException {
        String cacheKey = adminCacheKey(config);
        DefaultMQAdminExt cached = adminCache.get(cacheKey);
        if (cached != null) return cached;
        synchronized (RocketmqService.class) {
            cached = adminCache.get(cacheKey);
            if (cached != null) return cached;
            String nameServer = resolveNameServer(config); // 启用隧道则建立/复用并获取引用计数
            DefaultMQAdminExt admin = new DefaultMQAdminExt();
            admin.setNamesrvAddr(nameServer);
            admin.setAdminExtGroup("tomato_admin_group");
            try {
                admin.start();
            } catch (MQClientException e) {
                SshTunnelManager.release(config); // 启动失败则释放隧道引用
                throw e;
            }
            // 安装隧道 RemotingClient 装饰器：broker 地址经跳板隧道转发
            installTunnelingRemotingClient(admin, config);
            adminCache.put(cacheKey, admin);
            return admin;
        }
    }

    /**
     * 测试NameServer连接（启用SSH通道时建立临时隧道，测试后释放）。
     */
    public static boolean testConnection(ConnectionConfig config) {
        DefaultMQAdminExt admin = new DefaultMQAdminExt();
        boolean tunnelAcquired = false;
        try {
            String nameServer = resolveNameServer(config); // 启用隧道则建立并获取引用计数
            tunnelAcquired = config.isUseSshTunnel() && config.getSshTunnelHostId() != null;
            admin.setNamesrvAddr(nameServer);
            admin.setAdminExtGroup("tomato_admin_test_" + System.currentTimeMillis());
            admin.start();
            // 尝试获取集群信息来验证连接
            admin.examineBrokerClusterInfo();
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try { admin.shutdown(); } catch (Exception ignored) {}
            if (tunnelAcquired) SshTunnelManager.release(config);
        }
    }

    /**
     * 关闭指定配置的Admin连接，并释放SSH跳板隧道引用。
     */
    public static void closeAdmin(ConnectionConfig config) {
        String cacheKey = adminCacheKey(config);
        DefaultMQAdminExt admin = adminCache.remove(cacheKey);
        if (admin != null) {
            try { admin.shutdown(); } catch (Exception ignored) {}
        }
        SshTunnelManager.release(config);
        // 关闭所有 broker 跳板隧道（NameServer 隧道由 release 释放）
        SshTunnelManager.closeBrokerTunnels(config);
    }

    /**
     * Admin缓存键：隧道配置不同则按 configId 区分，避免不同跳板机共用同一Admin。
     */
    private static String adminCacheKey(ConnectionConfig config) {
        String base = config.getHost() + ":" + config.getPort();
        if (config.isUseSshTunnel() && config.getSshTunnelHostId() != null) {
            return config.getId() + "_tunnel_" + base;
        }
        return base;
    }

    /**
     * 解析实际连接的 NameServer 地址：启用隧道则建立/复用跳板隧道并获取引用计数，返回 localhost:转发端口。
     * 调用方需在连接生命周期结束时调用 SshTunnelManager.release(config) 释放引用。
     */
    private static String resolveNameServer(ConnectionConfig config) {
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
     * 获取 NameServer 地址（短生命周期消费者用）：复用 getAdmin 已建立的隧道（peek，不增减引用计数）。
     * 隧道未建立时回退到原始地址。
     */
    private static String nameServer(ConnectionConfig config) {
        int localPort = SshTunnelManager.peek(config);
        if (localPort != -1) {
            return "localhost:" + localPort;
        }
        return config.getHost() + ":" + config.getPort();
    }

    // ==================== RocketMQ broker 跳板隧道 RemotingClient 装饰器 ====================
    //
    // 问题：NameServer 走隧道后，NameServer 返回的 broker 地址仍是内网 IP（如 172.17.0.13:10911），
    //       客户端直连 broker 失败。NettyRemotingClient.getAndCreateChannel 为 private 无法重写，
    //       brokerAddrTable 会被 30s 路由刷新覆盖。
    // 方案：用装饰器包装 MQClientAPIImpl.remotingClient（private final，反射注入），
    //       在 invokeSync/invokeAsync/invokeOneway 等连接点将 broker 地址 remap 到 localhost:转发端口。
    //       装饰器在连接时 remap，路由刷新不影响，Admin/PullConsumer 均生效。

    /**
     * 为 Admin 安装隧道 RemotingClient 装饰器
     */
    private static void installTunnelingRemotingClient(DefaultMQAdminExt admin, ConnectionConfig config) {
        if (!config.isUseSshTunnel() || config.getSshTunnelHostId() == null) return;
        try {
            DefaultMQAdminExtImpl impl = admin.getDefaultMQAdminExtImpl();
            if (impl == null) return;
            MQClientInstance mqi = impl.getMqClientInstance();
            installTunnelingRemotingClient(mqi, config);
        } catch (Exception e) {
            throw new RuntimeException("安装Admin隧道RemotingClient失败: " + e.getMessage(), e);
        }
    }

    /**
     * 为 PullConsumer 安装隧道 RemotingClient 装饰器
     */
    private static void installTunnelingRemotingClient(DefaultMQPullConsumer pullConsumer, ConnectionConfig config) {
        if (!config.isUseSshTunnel() || config.getSshTunnelHostId() == null) return;
        try {
            DefaultMQPullConsumerImpl impl = pullConsumer.getDefaultMQPullConsumerImpl();
            if (impl == null) return;
            Field f = DefaultMQPullConsumerImpl.class.getDeclaredField("mQClientFactory");
            f.setAccessible(true);
            MQClientInstance mqi = (MQClientInstance) f.get(impl);
            installTunnelingRemotingClient(mqi, config);
        } catch (Exception e) {
            throw new RuntimeException("安装PullConsumer隧道RemotingClient失败: " + e.getMessage(), e);
        }
    }

    /**
     * 为 Producer 安装隧道 RemotingClient 装饰器
     */
    private static void installTunnelingRemotingClient(DefaultMQProducer producer, ConnectionConfig config) {
        if (!config.isUseSshTunnel() || config.getSshTunnelHostId() == null) return;
        try {
            DefaultMQProducerImpl impl = producer.getDefaultMQProducerImpl();
            if (impl == null) return;
            Field f = DefaultMQProducerImpl.class.getDeclaredField("mQClientFactory");
            f.setAccessible(true);
            MQClientInstance mqi = (MQClientInstance) f.get(impl);
            installTunnelingRemotingClient(mqi, config);
        } catch (Exception e) {
            throw new RuntimeException("安装Producer隧道RemotingClient失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 MQClientInstance 的 remotingClient 替换为隧道装饰器（反射注入 final 字段）。
     */
    private static void installTunnelingRemotingClient(MQClientInstance mqi, ConnectionConfig config) throws Exception {
        if (mqi == null) return;
        MQClientAPIImpl api = mqi.getMQClientAPIImpl();
        RemotingClient original = api.getRemotingClient();
        if (original instanceof TunnelingRemotingClient) return; // 已包装
        TunnelingRemotingClient decorator = new TunnelingRemotingClient(original, config);
        Field f = MQClientAPIImpl.class.getDeclaredField("remotingClient");
        f.setAccessible(true);
        f.set(api, decorator);
    }

    /**
     * RemotingClient 装饰器：对 broker 地址（非 localhost）remap 到 localhost:转发端口。
     * NameServer 地址已是 localhost:转发端口（setNamesrvAddr 设置），跳过 remap。
     */
    private static final class TunnelingRemotingClient implements RemotingClient {
        private final RemotingClient delegate;
        private final ConnectionConfig config;

        TunnelingRemotingClient(RemotingClient delegate, ConnectionConfig config) {
            this.delegate = delegate;
            this.config = config;
        }

        /** 将 broker 地址（ip:port）remap 到 localhost:转发端口；NameServer(localhost)/异常地址原样返回 */
        private String remap(String addr) {
            if (addr == null || addr.isEmpty()) return addr;
            if (addr.startsWith("localhost") || addr.startsWith("127.")) return addr;
            String[] hp = addr.split(":");
            if (hp.length != 2) return addr;
            try {
                int brokerPort = Integer.parseInt(hp[1].trim());
                int localPort = SshTunnelManager.ensureBrokerTunnel(config, hp[0].trim(), brokerPort);
                return "localhost:" + localPort;
            } catch (NumberFormatException e) {
                return addr;
            }
        }

        @Override
        public void updateNameServerAddressList(List<String> nameServerAddressList) {
            delegate.updateNameServerAddressList(nameServerAddressList);
        }

        @Override
        public List<String> getNameServerAddressList() {
            return delegate.getNameServerAddressList();
        }

        @Override
        public List<String> getAvailableNameSrvList() {
            return delegate.getAvailableNameSrvList();
        }

        @Override
        public RemotingCommand invokeSync(String addr, RemotingCommand request, long timeout) throws InterruptedException,
                org.apache.rocketmq.remoting.exception.RemotingConnectException,
                org.apache.rocketmq.remoting.exception.RemotingSendRequestException,
                org.apache.rocketmq.remoting.exception.RemotingTimeoutException {
            return delegate.invokeSync(remap(addr), request, timeout);
        }

        @Override
        public void invokeAsync(String addr, RemotingCommand request, long timeout, InvokeCallback callback) throws InterruptedException,
                org.apache.rocketmq.remoting.exception.RemotingConnectException,
                org.apache.rocketmq.remoting.exception.RemotingTooMuchRequestException,
                org.apache.rocketmq.remoting.exception.RemotingTimeoutException,
                org.apache.rocketmq.remoting.exception.RemotingSendRequestException {
            delegate.invokeAsync(remap(addr), request, timeout, callback);
        }

        @Override
        public void invokeOneway(String addr, RemotingCommand request, long timeout) throws InterruptedException,
                org.apache.rocketmq.remoting.exception.RemotingConnectException,
                org.apache.rocketmq.remoting.exception.RemotingTooMuchRequestException,
                org.apache.rocketmq.remoting.exception.RemotingTimeoutException,
                org.apache.rocketmq.remoting.exception.RemotingSendRequestException {
            delegate.invokeOneway(remap(addr), request, timeout);
        }

        @Override
        public CompletableFuture<RemotingCommand> invoke(String addr, RemotingCommand request, long timeout) {
            return delegate.invoke(remap(addr), request, timeout);
        }

        @Override
        public void registerProcessor(int requestCode, NettyRequestProcessor processor, ExecutorService executor) {
            delegate.registerProcessor(requestCode, processor, executor);
        }

        @Override
        public void setCallbackExecutor(ExecutorService executor) {
            delegate.setCallbackExecutor(executor);
        }

        @Override
        public boolean isChannelWritable(String addr) {
            return delegate.isChannelWritable(remap(addr));
        }

        @Override
        public boolean isAddressReachable(String addr) {
            return delegate.isAddressReachable(remap(addr));
        }

        @Override
        public void closeChannels(List<String> addrs) {
            if (addrs == null || addrs.isEmpty()) {
                delegate.closeChannels(addrs);
                return;
            }
            List<String> remapped = new ArrayList<>(addrs.size());
            for (String a : addrs) remapped.add(remap(a));
            delegate.closeChannels(remapped);
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public void registerRPCHook(RPCHook rpcHook) {
            delegate.registerRPCHook(rpcHook);
        }

        @Override
        public void setRequestPipeline(RequestPipeline pipeline) {
            delegate.setRequestPipeline(pipeline);
        }

        @Override
        public void clearRPCHook() {
            delegate.clearRPCHook();
        }
    }

    /**
     * 关闭所有Admin连接
     */
    public static void closeAllAdmins() {
        for (Map.Entry<String, DefaultMQAdminExt> entry : adminCache.entrySet()) {
            try { entry.getValue().shutdown(); } catch (Exception ignored) {}
        }
        adminCache.clear();
    }

    // ==================== Topic管理 ====================

    /**
     * 获取所有Topic列表
     */
    public static List<Map<String, Object>> getTopicList(ConnectionConfig config) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        TopicList topicList = admin.fetchAllTopicList();
        List<Map<String, Object>> result = new ArrayList<>();
        if (topicList != null && topicList.getTopicList() != null) {
            for (String topic : topicList.getTopicList()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("topic", topic);
                item.put("topicType", topic.startsWith("%") ? "SYSTEM" : "NORMAL");
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 获取Topic统计信息
     */
    public static Map<String, Object> getTopicStats(ConnectionConfig config, String topic) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        TopicStatsTable statsTable = admin.examineTopicStats(topic);
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> offsetList = new ArrayList<>();
        if (statsTable != null && statsTable.getOffsetTable() != null) {
            for (Map.Entry<MessageQueue, TopicOffset> entry : statsTable.getOffsetTable().entrySet()) {
                Map<String, Object> offsetInfo = new LinkedHashMap<>();
                offsetInfo.put("brokerName", entry.getKey().getBrokerName());
                offsetInfo.put("queueId", entry.getKey().getQueueId());
                offsetInfo.put("minOffset", entry.getValue().getMinOffset());
                offsetInfo.put("maxOffset", entry.getValue().getMaxOffset());
                offsetInfo.put("lastUpdateTimestamp", entry.getValue().getLastUpdateTimestamp());
                offsetList.add(offsetInfo);
            }
        }
        result.put("offsetTable", offsetList);
        return result;
    }

    /**
     * 获取Topic路由信息
     */
    public static Map<String, Object> getTopicRoute(ConnectionConfig config, String topic) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        TopicRouteData routeData = admin.examineTopicRouteInfo(topic);
        Map<String, Object> result = new LinkedHashMap<>();
        if (routeData != null) {
            List<Map<String, Object>> brokerList = new ArrayList<>();
            if (routeData.getBrokerDatas() != null) {
                for (BrokerData broker : routeData.getBrokerDatas()) {
                    Map<String, Object> brokerInfo = new LinkedHashMap<>();
                    brokerInfo.put("brokerName", broker.getBrokerName());
                    brokerInfo.put("brokerAddrs", broker.getBrokerAddrs());
                    brokerList.add(brokerInfo);
                }
            }
            result.put("brokers", brokerList);
            List<Map<String, Object>> queueList = new ArrayList<>();
            if (routeData.getQueueDatas() != null) {
                for (QueueData qd : routeData.getQueueDatas()) {
                    Map<String, Object> queueInfo = new LinkedHashMap<>();
                    queueInfo.put("brokerName", qd.getBrokerName());
                    queueInfo.put("readQueueNums", qd.getReadQueueNums());
                    queueInfo.put("writeQueueNums", qd.getWriteQueueNums());
                    queueInfo.put("perm", qd.getPerm());
                    queueList.add(queueInfo);
                }
            }
            result.put("queues", queueList);
        }
        return result;
    }

    /**
     * 创建Topic
     */
    public static void createTopic(ConnectionConfig config, String topic, int queueNum) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        ClusterInfo clusterInfo = admin.examineBrokerClusterInfo();
        if (clusterInfo != null && clusterInfo.getBrokerAddrTable() != null) {
            for (Map.Entry<String, BrokerData> entry : clusterInfo.getBrokerAddrTable().entrySet()) {
                String brokerName = entry.getKey();
                BrokerData brokerData = entry.getValue();
                if (brokerData.getBrokerAddrs() != null) {
                    for (Map.Entry<Long, String> addrEntry : brokerData.getBrokerAddrs().entrySet()) {
                        if (addrEntry.getKey() == 0L) {
                            admin.createTopic(addrEntry.getValue(), topic, queueNum, new HashMap<>());
                        }
                    }
                }
            }
        }
    }

    /**
     * 删除Topic
     */
    public static void deleteTopic(ConnectionConfig config, String topic) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        ClusterInfo clusterInfo = admin.examineBrokerClusterInfo();
        if (clusterInfo != null && clusterInfo.getBrokerAddrTable() != null) {
            Set<String> masterAddrs = new HashSet<>();
            for (BrokerData brokerData : clusterInfo.getBrokerAddrTable().values()) {
                String masterAddr = brokerData.getBrokerAddrs().get(0L);
                if (masterAddr != null) {
                    masterAddrs.add(masterAddr);
                }
            }
            admin.deleteTopicInBroker(masterAddrs, topic);
        }
    }

    // ==================== 消息查询 ====================

    /**
     * 按Message ID查询消息
     */
    public static Map<String, Object> queryMessageById(ConnectionConfig config, String topic, String msgId) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        MessageExt msg = null;
        // 先按原topic查
        try {
            msg = admin.viewMessage(topic, msgId);
        } catch (Exception ignored) {}
        // 延迟消息可能在SCHEDULE_TOPIC中，按msgId全局搜索
        if (msg == null) {
            try {
                long end = System.currentTimeMillis();
                long begin = end - 7 * 24 * 3600 * 1000L;
                QueryResult qr = admin.queryMessage(null, topic, msgId, 1, begin, end);
                if (qr != null && !qr.getMessageList().isEmpty()) {
                    msg = qr.getMessageList().get(0);
                }
            } catch (Exception ignored) {}
        }
        if (msg == null) {
            throw new RuntimeException("未找到消息: " + msgId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("msgId", msg.getMsgId());
        result.put("keys", msg.getKeys());
        result.put("tags", msg.getTags());
        result.put("topic", msg.getTopic());
        result.put("queueId", msg.getQueueId());
        result.put("queueOffset", msg.getQueueOffset());
        result.put("storeSize", msg.getStoreSize());
        result.put("bornTimestamp", msg.getBornTimestamp());
        result.put("storeTimestamp", msg.getStoreTimestamp());
        result.put("bornHost", String.valueOf(msg.getBornHost()));
        result.put("storeHost", String.valueOf(msg.getStoreHost()));
        result.put("body", new String(msg.getBody(), "UTF-8"));
        result.put("reconsumeTimes", msg.getReconsumeTimes());
        return result;
    }

    /**
     * 按Key查询消息 - 使用queryMessage按key+最近3天时间范围查询
     */
    public static List<Map<String, Object>> queryMessageByKey(ConnectionConfig config, String topic, String key) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        long end = System.currentTimeMillis();
        long begin = end - 3 * 24 * 3600 * 1000L;
        QueryResult queryResult = admin.queryMessage(null, topic, key, 64, begin, end);
        return convertMessages(queryResult != null ? queryResult.getMessageList() : null);
    }

    /**
     * 按时间范围查询消息 - 直接用PullConsumer遍历Queue拉取
     * queryMessage不支持空key查询，所以直接用Pull方式
     */
    public static List<Map<String, Object>> queryMessageByTime(ConnectionConfig config, String topic, long begin, long end) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        tryPullMessages(config, topic, begin, end, result);
        // 额外查询SCHEDULE_TOPIC中的延迟消息（属于该topic的）
        tryPullDelayedMessages(config, topic, begin, end, result);
        return result;
    }

    /**
     * 用PullConsumer遍历Queue拉取指定时间范围内的消息
     */
    private static void tryPullMessages(ConnectionConfig config, String topic, long begin, long end, List<Map<String, Object>> result) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        TopicStatsTable statsTable = admin.examineTopicStats(topic);
        if (statsTable == null || statsTable.getOffsetTable() == null) return;

        String consumerGroup = "tomato_query";
        DefaultMQPullConsumer pullConsumer = new DefaultMQPullConsumer(consumerGroup);
        pullConsumer.setNamesrvAddr(nameServer(config));
        pullConsumer.start();
        installTunnelingRemotingClient(pullConsumer, config);

        try {
            for (Map.Entry<MessageQueue, TopicOffset> entry : statsTable.getOffsetTable().entrySet()) {
                MessageQueue mq = entry.getKey();
                long minOffset = entry.getValue().getMinOffset();
                long maxOffset = entry.getValue().getMaxOffset();
                if (minOffset >= maxOffset) continue;

                long offset = minOffset;
                int pullBatch = 32;
                boolean foundInRange = false;

                while (offset < maxOffset && result.size() < 256) {
                    PullResult pullResult = pullConsumer.pull(mq, "*", offset, pullBatch);
                    if (pullResult == null) break;

                    if (pullResult.getPullStatus() == PullStatus.FOUND) {
                        for (MessageExt msg : pullResult.getMsgFoundList()) {
                            if (msg.getStoreTimestamp() >= begin && msg.getStoreTimestamp() <= end) {
                                foundInRange = true;
                                result.add(convertMessage(msg));
                            } else if (foundInRange && msg.getStoreTimestamp() > end) {
                                return;
                            }
                        }
                        offset = pullResult.getNextBeginOffset();
                    } else if (pullResult.getPullStatus() == PullStatus.NO_NEW_MSG
                            || pullResult.getPullStatus() == PullStatus.OFFSET_ILLEGAL) {
                        break;
                    } else {
                        offset = pullResult.getNextBeginOffset();
                    }
                }
            }
        } finally {
            pullConsumer.shutdown();
        }
    }

    private static Map<String, Object> convertMessage(MessageExt msg, boolean includeBody) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("msgId", msg.getMsgId());
        item.put("keys", msg.getKeys());
        item.put("tags", msg.getTags());
        // 延迟消息还原原始topic
        String realTopic = msg.getProperty(org.apache.rocketmq.common.message.MessageConst.PROPERTY_REAL_TOPIC);
        item.put("topic", (realTopic != null && !realTopic.isEmpty()) ? realTopic : msg.getTopic());
        item.put("queueId", msg.getQueueId());
        item.put("queueOffset", msg.getQueueOffset());
        item.put("storeTimestamp", msg.getStoreTimestamp());
        item.put("bornTimestamp", msg.getBornTimestamp());
        item.put("bornHost", String.valueOf(msg.getBornHost()));
        item.put("storeHost", String.valueOf(msg.getStoreHost()));
        item.put("storeSize", msg.getStoreSize());
        item.put("reconsumeTimes", msg.getReconsumeTimes());
        // 延迟消息标记
        if (realTopic != null && !realTopic.isEmpty()) {
            item.put("delayed", true);
        }
        if (includeBody) {
            try {
                item.put("body", new String(msg.getBody(), "UTF-8"));
            } catch (Exception e) {
                item.put("body", "[无法解码]");
            }
        }
        return item;
    }

    /**
     * 从SCHEDULE_TOPIC中查询属于指定topic的延迟消息
     */
    private static void tryPullDelayedMessages(ConnectionConfig config, String topic, long begin, long end, List<Map<String, Object>> result) {
        try {
            DefaultMQAdminExt admin = getAdmin(config);
            // SCHEDULE_TOPIC的队列格式: SCHEDULE_TOPIC_XXXX
            // 遍历所有SCHEDULE_TOPIC开头的topic
            TopicList allTopics = admin.fetchAllTopicList();
            if (allTopics == null || allTopics.getTopicList() == null) return;

            for (String scheduleTopic : allTopics.getTopicList()) {
                if (!scheduleTopic.startsWith("SCHEDULE_TOPIC")) continue;

                try {
                    TopicStatsTable statsTable = admin.examineTopicStats(scheduleTopic);
                    if (statsTable == null || statsTable.getOffsetTable() == null) continue;

                    String consumerGroup = "tomato_delayed";
                    DefaultMQPullConsumer pullConsumer = new DefaultMQPullConsumer(consumerGroup);
                    pullConsumer.setNamesrvAddr(nameServer(config));
                    pullConsumer.start();
                    installTunnelingRemotingClient(pullConsumer, config);

                    try {
                        for (Map.Entry<MessageQueue, TopicOffset> entry : statsTable.getOffsetTable().entrySet()) {
                            MessageQueue mq = entry.getKey();
                            long minOffset = entry.getValue().getMinOffset();
                            long maxOffset = entry.getValue().getMaxOffset();
                            if (minOffset >= maxOffset) continue;

                            long offset = minOffset;
                            boolean foundInRange = false;

                            while (offset < maxOffset && result.size() < 256) {
                                PullResult pullResult = pullConsumer.pull(mq, "*", offset, 32);
                                if (pullResult == null) break;

                                if (pullResult.getPullStatus() == PullStatus.FOUND) {
                                    for (MessageExt msg : pullResult.getMsgFoundList()) {
                                        if (msg.getStoreTimestamp() >= begin && msg.getStoreTimestamp() <= end) {
                                            foundInRange = true;
                                            // 只添加属于目标topic的延迟消息
                                            String realTopic = msg.getProperty(org.apache.rocketmq.common.message.MessageConst.PROPERTY_REAL_TOPIC);
                                            if (topic.equals(realTopic)) {
                                                result.add(convertMessage(msg));
                                            }
                                        } else if (foundInRange && msg.getStoreTimestamp() > end) {
                                            return;
                                        }
                                    }
                                    offset = pullResult.getNextBeginOffset();
                                } else if (pullResult.getPullStatus() == PullStatus.NO_NEW_MSG
                                        || pullResult.getPullStatus() == PullStatus.OFFSET_ILLEGAL) {
                                    break;
                                } else {
                                    offset = pullResult.getNextBeginOffset();
                                }
                            }
                        }
                    } finally {
                        pullConsumer.shutdown();
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private static Map<String, Object> convertMessage(MessageExt msg) {
        return convertMessage(msg, true);
    }

    private static List<Map<String, Object>> convertMessages(List<MessageExt> msgs) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (msgs != null) {
            for (MessageExt msg : msgs) {
                result.add(convertMessage(msg));
            }
        }
        return result;
    }

    // ==================== 消费者组管理 ====================

    /**
     * 获取消费者组列表
     */
    public static List<Map<String, Object>> getConsumerGroupList(ConnectionConfig config) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        // getAllSubscriptionGroup是Broker级别操作，需要传入Broker地址
        ClusterInfo clusterInfo = admin.examineBrokerClusterInfo();
        if (clusterInfo == null || clusterInfo.getBrokerAddrTable() == null) {
            throw new RuntimeException("无法获取集群信息");
        }
        Set<String> groupSet = new LinkedHashSet<>();
        for (BrokerData brokerData : clusterInfo.getBrokerAddrTable().values()) {
            String masterAddr = brokerData.getBrokerAddrs().get(0L);
            if (masterAddr != null) {
                try {
                    SubscriptionGroupWrapper wrapper = admin.getAllSubscriptionGroup(masterAddr, 3000L);
                    if (wrapper != null && wrapper.getSubscriptionGroupTable() != null) {
                        groupSet.addAll(wrapper.getSubscriptionGroupTable().keySet());
                    }
                } catch (Exception ignored) {
                    // 某个Broker获取失败不影响其他Broker
                }
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String group : groupSet) {
            // 过滤本工具内部临时 pull consumer 组（tomato_query / tomato_delayed / tomato_unconsumed_* 等）。
            // 这些组用于消息浏览场景的本地拉取，从未在 Broker 上真正订阅注册：
            // - NameServer 上没有对应的 %RETRY%<group> topic 路由，examineConsumeStats 会抛出
            //   CODE: 17 DESC: No topic route info in name server for the topic: %RETRY%tomato_xxx
            // - 用户也不希望看到这些内部组
            if (group != null && group.startsWith("tomato_")) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("group", group);
            try {
                ConsumeStats stats = admin.examineConsumeStats(group);
                if (stats != null) {
                    item.put("consumeTps", String.format("%.2f", stats.getConsumeTps()));
                    item.put("diffTotal", stats.computeTotalDiff());
                }
            } catch (Exception ignored) {
                item.put("consumeTps", "0");
                item.put("diffTotal", 0);
            }
            result.add(item);
        }
        return result;
    }

    /**
     * 获取消费者组详情（消费偏移信息）
     */
    public static Map<String, Object> getConsumerGroupDetail(ConnectionConfig config, String group) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        ConsumeStats stats;
        try {
            stats = admin.examineConsumeStats(group);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            // CODE 17: No topic route info in name server for the topic: %RETRY%<group>
            // 通常出现于：该消费者组从未真正消费/订阅过任何topic（Broker上未创建%RETRY%主题），
            // 或本工具内部临时 pull consumer 组残留。此处不抛异常，返回空数据让UI友好展示。
            if (msg.contains("CODE: 17") || msg.contains("No topic route info")) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("consumeTps", 0.0);
                empty.put("totalDiff", 0L);
                empty.put("offsetTable", Collections.emptyList());
                empty.put("warning", "该消费者组尚未在Broker上真正消费，%RETRY%主题未创建，无消费统计数据。");
                return empty;
            }
            throw e;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (stats != null) {
            result.put("consumeTps", stats.getConsumeTps());
            result.put("totalDiff", stats.computeTotalDiff());
            List<Map<String, Object>> offsetList = new ArrayList<>();
            if (stats.getOffsetTable() != null) {
                for (Map.Entry<MessageQueue, OffsetWrapper> entry : stats.getOffsetTable().entrySet()) {
                    Map<String, Object> offsetInfo = new LinkedHashMap<>();
                    offsetInfo.put("topic", entry.getKey().getTopic());
                    offsetInfo.put("brokerName", entry.getKey().getBrokerName());
                    offsetInfo.put("queueId", entry.getKey().getQueueId());
                    offsetInfo.put("brokerOffset", entry.getValue().getBrokerOffset());
                    offsetInfo.put("consumerOffset", entry.getValue().getConsumerOffset());
                    long diff = entry.getValue().getBrokerOffset() - entry.getValue().getConsumerOffset();
                    offsetInfo.put("diff", diff);
                    offsetList.add(offsetInfo);
                }
            }
            result.put("offsetTable", offsetList);
        }
        return result;
    }

    /**
     * 获取消费者组下的客户端连接
     */
    public static List<Map<String, Object>> getConsumerConnection(ConnectionConfig config, String group) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        ConsumerConnection conn = admin.examineConsumerConnectionInfo(group);
        List<Map<String, Object>> result = new ArrayList<>();
        if (conn != null && conn.getConnectionSet() != null) {
            for (Connection connection : conn.getConnectionSet()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("clientId", connection.getClientId());
                item.put("clientAddr", String.valueOf(connection.getClientAddr()));
                item.put("language", String.valueOf(connection.getLanguage()));
                item.put("version", connection.getVersion());
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 删除消费者组
     */
    public static void deleteConsumerGroup(ConnectionConfig config, String group) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        ClusterInfo clusterInfo = admin.examineBrokerClusterInfo();
        if (clusterInfo != null && clusterInfo.getBrokerAddrTable() != null) {
            for (BrokerData brokerData : clusterInfo.getBrokerAddrTable().values()) {
                String masterAddr = brokerData.getBrokerAddrs().get(0L);
                if (masterAddr != null) {
                    admin.deleteSubscriptionGroup(masterAddr, group);
                }
            }
        }
    }

    // ==================== 集群信息 ====================

    /**
     * 获取集群信息
     */
    public static List<Map<String, Object>> getClusterInfo(ConnectionConfig config) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        ClusterInfo clusterInfo = admin.examineBrokerClusterInfo();
        List<Map<String, Object>> result = new ArrayList<>();
        if (clusterInfo != null && clusterInfo.getBrokerAddrTable() != null) {
            for (Map.Entry<String, BrokerData> entry : clusterInfo.getBrokerAddrTable().entrySet()) {
                String brokerName = entry.getKey();
                BrokerData brokerData = entry.getValue();
                if (brokerData.getBrokerAddrs() != null) {
                    for (Map.Entry<Long, String> addrEntry : brokerData.getBrokerAddrs().entrySet()) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("brokerName", brokerName);
                        item.put("brokerId", addrEntry.getKey());
                        item.put("address", addrEntry.getValue());
                        item.put("role", addrEntry.getKey() == 0L ? "MASTER" : "SLAVE");
                        // 获取Broker版本信息
                        try {
                            KVTable kvTable = admin.fetchBrokerRuntimeStats(addrEntry.getValue());
                            if (kvTable != null && kvTable.getTable() != null) {
                                String version = kvTable.getTable().get("brokerVersionDesc");
                                if (version != null) {
                                    item.put("version", version);
                                }
                            }
                        } catch (Exception ignored) {}
                        result.add(item);
                    }
                }
            }
        }
        return result;
    }

    /**
     * 获取Broker运行时信息
     */
    public static Map<String, Object> getBrokerInfo(ConnectionConfig config, String brokerAddr) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        KVTable kvTable = admin.fetchBrokerRuntimeStats(brokerAddr);
        Map<String, Object> result = new LinkedHashMap<>();
        if (kvTable != null && kvTable.getTable() != null) {
            for (Map.Entry<String, String> e : kvTable.getTable().entrySet()) {
                result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    // ==================== 消息消费状态与重发 ====================

    /**
     * 获取Topic的消费状态（各消费者组的积压情况）
     * 返回: [{group, consumeTps, diffTotal, delayTime}]
     */
    public static List<Map<String, Object>> getTopicConsumeStatus(ConnectionConfig config, String topic) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        List<Map<String, Object>> result = new ArrayList<>();
        // 获取所有消费者组
        ClusterInfo clusterInfo = admin.examineBrokerClusterInfo();
        Set<String> groupSet = new LinkedHashSet<>();
        if (clusterInfo != null && clusterInfo.getBrokerAddrTable() != null) {
            for (BrokerData brokerData : clusterInfo.getBrokerAddrTable().values()) {
                String masterAddr = brokerData.getBrokerAddrs().get(0L);
                if (masterAddr != null) {
                    try {
                        SubscriptionGroupWrapper wrapper = admin.getAllSubscriptionGroup(masterAddr, 3000L);
                        if (wrapper != null && wrapper.getSubscriptionGroupTable() != null) {
                            groupSet.addAll(wrapper.getSubscriptionGroupTable().keySet());
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        // 过滤出订阅了该topic的消费者组
        for (String group : groupSet) {
            try {
                ConsumeStats stats = admin.examineConsumeStats(group);
                if (stats == null || stats.getOffsetTable() == null) continue;
                boolean subscribed = false;
                long totalDiff = 0;
                for (Map.Entry<MessageQueue, OffsetWrapper> entry : stats.getOffsetTable().entrySet()) {
                    if (topic.equals(entry.getKey().getTopic())) {
                        subscribed = true;
                        totalDiff += entry.getValue().getBrokerOffset() - entry.getValue().getConsumerOffset();
                    }
                }
                if (subscribed) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("group", group);
                    item.put("consumeTps", String.format("%.2f", stats.getConsumeTps()));
                    item.put("diffTotal", totalDiff);
                    result.add(item);
                }
            } catch (Exception ignored) {}
        }
        return result;
    }

    /**
     * 查询未消费的消息列表（指定消费者组+Topic，拉取consumerOffset到brokerOffset之间的消息）
     */
    public static List<Map<String, Object>> queryUnconsumedMessages(ConnectionConfig config, String topic, String group, int maxCount) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        ConsumeStats stats = admin.examineConsumeStats(group);
        if (stats == null || stats.getOffsetTable() == null) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        DefaultMQPullConsumer pullConsumer = new DefaultMQPullConsumer("tomato_unconsumed_" + System.currentTimeMillis());
        pullConsumer.setNamesrvAddr(nameServer(config));
        pullConsumer.start();
        installTunnelingRemotingClient(pullConsumer, config);

        try {
            for (Map.Entry<MessageQueue, OffsetWrapper> entry : stats.getOffsetTable().entrySet()) {
                if (!topic.equals(entry.getKey().getTopic())) continue;
                if (result.size() >= maxCount) break;

                MessageQueue mq = entry.getKey();
                long consumerOffset = entry.getValue().getConsumerOffset();
                long brokerOffset = entry.getValue().getBrokerOffset();
                if (consumerOffset >= brokerOffset) continue;

                long offset = consumerOffset;
                while (offset < brokerOffset && result.size() < maxCount) {
                    PullResult pullResult = pullConsumer.pull(mq, "*", offset, Math.min(32, maxCount - result.size()));
                    if (pullResult == null) break;
                    if (pullResult.getPullStatus() == PullStatus.FOUND) {
                        for (MessageExt msg : pullResult.getMsgFoundList()) {
                            result.add(convertMessage(msg));
                        }
                        offset = pullResult.getNextBeginOffset();
                    } else {
                        break;
                    }
                }
            }
        } finally {
            pullConsumer.shutdown();
        }
        return result;
    }

    /**
     * 查询消息消费轨迹（每个消费者组的消费状态）
     * 返回: [{group, trackType, exceptionDesc}]
     * trackType: CONSUMED / NOT_CONSUME_YET / CONSUME_BUT_DIED / UNKNOWN
     */
    public static List<Map<String, Object>> getMessageTrack(ConnectionConfig config, String topic, String msgId) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        MessageExt msg = null;
        try { msg = admin.viewMessage(topic, msgId); } catch (Exception ignored) {}
        if (msg == null) {
            try {
                long end = System.currentTimeMillis();
                long begin = end - 7 * 24 * 3600 * 1000L;
                QueryResult qr = admin.queryMessage(null, topic, msgId, 1, begin, end);
                if (qr != null && !qr.getMessageList().isEmpty()) {
                    msg = qr.getMessageList().get(0);
                }
            } catch (Exception ignored) {}
        }
        if (msg == null) {
            throw new RuntimeException("未找到消息，无法查询消费轨迹: " + msgId);
        }
        List<org.apache.rocketmq.tools.admin.api.MessageTrack> tracks = admin.messageTrackDetail(msg);
        List<Map<String, Object>> result = new ArrayList<>();
        if (tracks != null) {
            for (org.apache.rocketmq.tools.admin.api.MessageTrack track : tracks) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("group", track.getConsumerGroup());
                item.put("trackType", track.getTrackType() != null ? track.getTrackType().name() : "UNKNOWN");
                item.put("exceptionDesc", track.getExceptionDesc() != null ? track.getExceptionDesc() : "");
                result.add(item);
            }
        }
        return result;
    }

    /**
     * 重新消费消息（通过consumeMessageDirectly让指定消费者组直接重新消费，不产生新msgId）
     * 返回消费结果
     */
    public static Map<String, Object> reconsumeMessage(ConnectionConfig config, String group, String topic, String msgId) throws Exception {
        DefaultMQAdminExt admin = getAdmin(config);
        // 先获取该消费者组的客户端连接，找到可用的clientId
        ConsumerConnection conn = admin.examineConsumerConnectionInfo(group);
        if (conn == null || conn.getConnectionSet() == null || conn.getConnectionSet().isEmpty()) {
            throw new RuntimeException("消费者组 " + group + " 没有在线的消费者实例");
        }
        // 取第一个连接的clientId
        Connection connection = conn.getConnectionSet().iterator().next();
        String clientId = connection.getClientId();

        // 使用consumeMessageDirectly让消费者直接重新消费
        var result = admin.consumeMessageDirectly(group, clientId, topic, msgId);
        Map<String, Object> map = new LinkedHashMap<>();
        if (result != null) {
            map.put("consumeResult", result.getConsumeResult() != null ? result.getConsumeResult().name() : "UNKNOWN");
            map.put("remark", result.getRemark() != null ? result.getRemark() : "");
        } else {
            map.put("consumeResult", "FAILED");
            map.put("remark", "返回结果为空");
        }
        return map;
    }

    // ==================== 消息发送 ====================

    /**
     * 发送消息到指定主题。
     * tags/keys 可为空；delayLevel=0 表示普通消息，>0 表示延迟消息。
     * 返回包含 msgId/queueId/queueOffset/topic 的 Map。
     */
    public static Map<String, Object> sendMessage(ConnectionConfig config, String topic, String tags, String keys, String body, int delayLevel) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer("tomato_producer_" + System.currentTimeMillis());
        producer.setNamesrvAddr(nameServer(config));
        producer.start();
        installTunnelingRemotingClient(producer, config);
        try {
            Message msg = new Message(topic, tags != null ? tags : "", body.getBytes("UTF-8"));
            if (keys != null && !keys.isEmpty()) {
                msg.setKeys(keys);
            }
            if (delayLevel > 0) {
                msg.setDelayTimeLevel(delayLevel);
            }
            SendResult result = producer.send(msg);
            Map<String, Object> ret = new LinkedHashMap<>();
            ret.put("msgId", result.getMsgId());
            ret.put("queueId", result.getMessageQueue().getQueueId());
            ret.put("queueOffset", result.getQueueOffset());
            ret.put("topic", result.getMessageQueue().getTopic());
            return ret;
        } finally {
            producer.shutdown();
        }
    }
}
