package com.tangluobo.tomato.module.connect;

/**
 * 数据库树节点的元数据，用于标识动态加载的节点类型（数据库、表、视图等）
 */
public class DatabaseNodeData {
    public enum NodeType {
        DATABASE,       // 数据库节点
        REDIS_DB,       // Redis数据库节点
        SCHEMA,         // 模式节点（PostgreSQL schema）
        TABLES_FOLDER,  // "表"文件夹节点
        VIEWS_FOLDER,   // "视图"文件夹节点
        QUERY_FOLDER,   // "查询"文件夹节点
        TABLE,          // 表节点
        VIEW,           // 视图节点
        FUNCTION_FOLDER, BACKUP_FOLDER, QUERY, BACKUP,
        QUERY_DIR,      // 查询目录节点（查询文件夹下的子目录）
        BACKUP_DIR,     // 备份目录节点（备份文件夹下的子目录）
        ROCKETMQ_TOPICS_FOLDER,   // RocketMQ主题文件夹
        ROCKETMQ_CONSUMERS_FOLDER, // RocketMQ消费者组文件夹
        ROCKETMQ_CLUSTER_FOLDER,   // RocketMQ集群文件夹
        ROCKETMQ_MESSAGES_FOLDER,  // RocketMQ消息文件夹
        ROCKETMQ_TOPIC,            // RocketMQ单个主题
        ROCKETMQ_CONSUMER,         // RocketMQ单个消费者组
        ROCKETMQ_BROKER,           // RocketMQ单个Broker
        ROCKETMQ_MESSAGE,           // RocketMQ单条消息
        KAFKA_TOPICS_FOLDER,       // Kafka主题文件夹
        KAFKA_CONSUMERS_FOLDER,    // Kafka消费者组文件夹
        KAFKA_CLUSTER_FOLDER,      // Kafka集群文件夹
        KAFKA_TOPIC,               // Kafka单个主题
        KAFKA_CONSUMER,            // Kafka单个消费者组
        KAFKA_BROKER,              // Kafka单个Broker
        ALIYUN_PRODUCT_FOLDER,      // 阿里云产品文件夹（如ECS、RDS等）
        ALIYUN_ECS_INSTANCE,        // 阿里云ECS实例
        ALIYUN_DOMAIN,              // 阿里云域名
        LOCAL_DIR_FOLDER,           // 本地目录文件夹
        LOCAL_DIR_FILE              // 本地目录文件
    }

    private final NodeType type;
    private final String name;
    private final ConnectionConfig connectionConfig;
    private final String databaseName;
    // PostgreSQL 模式名（schema）。MySQL/Oracle 忽略；PostgreSQL 表相关操作使用 schema 名，
    // databaseName 仅用于建立绑定到具体数据库的连接。
    private final String schemaName;
    // 查询/备份目录的相对路径（相对于 query/backup 根目录），""表示根目录。
    // 仅 QUERY/QUERY_DIR/BACKUP/BACKUP_DIR 节点使用；QUERY_FOLDER/BACKUP_FOLDER 固定为""。
    private final String path;
    private boolean opened;

    public DatabaseNodeData(NodeType type, String name, ConnectionConfig connectionConfig, String databaseName) {
        this(type, name, connectionConfig, databaseName, null, "");
    }

    public DatabaseNodeData(NodeType type, String name, ConnectionConfig connectionConfig, String databaseName, String schemaName) {
        this(type, name, connectionConfig, databaseName, schemaName, "");
    }

    public DatabaseNodeData(NodeType type, String name, ConnectionConfig connectionConfig, String databaseName, String schemaName, String path) {
        this.type = type;
        this.name = name;
        this.connectionConfig = connectionConfig;
        this.databaseName = databaseName;
        this.schemaName = schemaName;
        this.path = path == null ? "" : path;
        this.opened = false;
    }

    public NodeType getType() { return type; }
    public String getName() { return name; }
    public ConnectionConfig getConnectionConfig() { return connectionConfig; }
    public String getDatabaseName() { return databaseName; }
    public String getSchemaName() { return schemaName; }
    public String getPath() { return path; }
    public boolean isOpened() { return opened; }
    public void setOpened(boolean opened) { this.opened = opened; }
}
