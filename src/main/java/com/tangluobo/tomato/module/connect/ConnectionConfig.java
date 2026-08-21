package com.tangluobo.tomato.module.connect;

import java.util.ArrayList;
import java.util.List;

import com.tangluobo.tomato.module.tools.server.ServerConfig;

public class ConnectionConfig {
    private String id;
    private String name;
    private String parentId;
    private ConnectType type;
    // 工具类型代码（仅当 type == ConnectType.TOOL 时使用），对应 ToolType.code
    private String toolType;
    private String host;
    private int port;
    private String username;
    private boolean usePassword = true;
    private String password;
    private boolean savePassword = true;
    private boolean useKey = false;
    private List<String> privateKeyPaths = new ArrayList<>();
    private String database;
    private String description;
    private Integer scrollbackLines;

    // 服务器配置（HTTP_SERVER/FTP_SERVER/SMB_SERVER 专用，保存共享目录、账号列表等完整配置）
    private ServerConfig serverConfig;

    // RDP专属配置
    private String domain;
    private int screenWidth = 1024;
    private int screenHeight = 768;
    private int colorDepth = 24;
    private boolean useSsl = true; // 是否使用SSL/TLS加密（无TLS服务器需设为false）

    // 本地终端配置
    private String terminalType; // Windows: "cmd" 或 "powershell"; Linux/macOS: "system"

    // 本地目录配置
    private String localDirectoryPath; // 本地目录路径

    // 目录类型连接的存储后端类型：LOCAL（本地目录）或 S3（S3兼容存储）。
    // 旧配置无此字段时按 LOCAL 处理。
    private String directoryType = "LOCAL";
    // S3 目录后端：bucket 名（用户配置的"目录"）
    private String bucket;
    // S3 目录后端：bucket 内子目录前缀（可空，以 / 结尾）
    private String s3Prefix;

    // S3/OSS专属配置
    private String region;
    private boolean pathStyleAccess = false; // S3路径风格访问（MinIO需要）
    private String endpoint; // 自定义端点URL（MinIO等S3兼容服务）
    private String publicAccessUrl; // S3/OSS 公共访问URL前缀（如 CDN 域名）

    // Redis专属配置
    private boolean redisCluster = false; // 是否集群模式
    private String redisClusterNodes; // 集群节点，格式: host1:port1,host2:port2,...
    private int redisDatabase = 0; // 默认数据库编号

    // SSH通道配置
    private boolean useSshTunnel = false;
    private String sshTunnelHost;
    private int sshTunnelPort = 22;
    private String sshTunnelUsername;
    private boolean sshTunnelUsePassword = true;
    private String sshTunnelPassword;
    private boolean sshTunnelSavePassword = true;
    private boolean sshTunnelUseKey = false;
    private List<String> sshTunnelPrivateKeyPaths = new ArrayList<>();
    // SSH通道引用的已有SSH主机连接ID（S3等通过引用方式使用，不复制具体连接信息）
    private String sshTunnelHostId;

    public ConnectionConfig() {
    }

    public ConnectionConfig(String id, String name, String parentId, ConnectType type) {
        this.id = id;
        this.name = name;
        this.parentId = parentId;
        this.type = type;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }

    public ConnectType getType() { return type; }
    public void setType(ConnectType type) { this.type = type; }

    public String getToolType() { return toolType; }
    public void setToolType(String toolType) { this.toolType = toolType; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public boolean isUsePassword() { return usePassword; }
    public void setUsePassword(boolean usePassword) { this.usePassword = usePassword; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public boolean isSavePassword() { return savePassword; }
    public void setSavePassword(boolean savePassword) { this.savePassword = savePassword; }

    public boolean isUseKey() { return useKey; }
    public void setUseKey(boolean useKey) { this.useKey = useKey; }

    public List<String> getPrivateKeyPaths() { return privateKeyPaths; }
    public void setPrivateKeyPaths(List<String> privateKeyPaths) { this.privateKeyPaths = privateKeyPaths != null ? privateKeyPaths : new ArrayList<>(); }

    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Integer getScrollbackLines() { return scrollbackLines; }
    public void setScrollbackLines(Integer scrollbackLines) { this.scrollbackLines = scrollbackLines; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public int getScreenWidth() { return screenWidth; }
    public void setScreenWidth(int screenWidth) { this.screenWidth = screenWidth; }

    public int getScreenHeight() { return screenHeight; }
    public void setScreenHeight(int screenHeight) { this.screenHeight = screenHeight; }

    public int getColorDepth() { return colorDepth; }
    public void setColorDepth(int colorDepth) { this.colorDepth = colorDepth; }

    public boolean isUseSsl() { return useSsl; }
    public void setUseSsl(boolean useSsl) { this.useSsl = useSsl; }

    public String getTerminalType() { return terminalType; }
    public void setTerminalType(String terminalType) { this.terminalType = terminalType; }

    public String getLocalDirectoryPath() { return localDirectoryPath; }
    public void setLocalDirectoryPath(String localDirectoryPath) { this.localDirectoryPath = localDirectoryPath; }

    /** 目录类型连接的存储后端类型："LOCAL" 或 "S3"；null 视为 "LOCAL" */
    public String getDirectoryType() { return directoryType; }
    public void setDirectoryType(String directoryType) { this.directoryType = directoryType; }

    /** 是否为 S3 目录后端 */
    public boolean isS3Directory() { return "S3".equalsIgnoreCase(directoryType); }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public String getS3Prefix() { return s3Prefix; }
    public void setS3Prefix(String s3Prefix) { this.s3Prefix = s3Prefix; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public boolean isPathStyleAccess() { return pathStyleAccess; }
    public void setPathStyleAccess(boolean pathStyleAccess) { this.pathStyleAccess = pathStyleAccess; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getPublicAccessUrl() { return publicAccessUrl; }
    public void setPublicAccessUrl(String publicAccessUrl) { this.publicAccessUrl = publicAccessUrl; }

    public boolean isRedisCluster() { return redisCluster; }
    public void setRedisCluster(boolean redisCluster) { this.redisCluster = redisCluster; }

    public String getRedisClusterNodes() { return redisClusterNodes; }
    public void setRedisClusterNodes(String redisClusterNodes) { this.redisClusterNodes = redisClusterNodes; }

    public int getRedisDatabase() { return redisDatabase; }
    public void setRedisDatabase(int redisDatabase) { this.redisDatabase = redisDatabase; }


    public boolean isUseSshTunnel() { return useSshTunnel; }
    public void setUseSshTunnel(boolean useSshTunnel) { this.useSshTunnel = useSshTunnel; }

    public String getSshTunnelHost() { return sshTunnelHost; }
    public void setSshTunnelHost(String sshTunnelHost) { this.sshTunnelHost = sshTunnelHost; }

    public int getSshTunnelPort() { return sshTunnelPort; }
    public void setSshTunnelPort(int sshTunnelPort) { this.sshTunnelPort = sshTunnelPort; }

    public String getSshTunnelUsername() { return sshTunnelUsername; }
    public void setSshTunnelUsername(String sshTunnelUsername) { this.sshTunnelUsername = sshTunnelUsername; }

    public boolean isSshTunnelUsePassword() { return sshTunnelUsePassword; }
    public void setSshTunnelUsePassword(boolean sshTunnelUsePassword) { this.sshTunnelUsePassword = sshTunnelUsePassword; }

    public String getSshTunnelPassword() { return sshTunnelPassword; }
    public void setSshTunnelPassword(String sshTunnelPassword) { this.sshTunnelPassword = sshTunnelPassword; }

    public boolean isSshTunnelSavePassword() { return sshTunnelSavePassword; }
    public void setSshTunnelSavePassword(boolean sshTunnelSavePassword) { this.sshTunnelSavePassword = sshTunnelSavePassword; }

    public boolean isSshTunnelUseKey() { return sshTunnelUseKey; }
    public void setSshTunnelUseKey(boolean sshTunnelUseKey) { this.sshTunnelUseKey = sshTunnelUseKey; }

    public List<String> getSshTunnelPrivateKeyPaths() { return sshTunnelPrivateKeyPaths; }
    public void setSshTunnelPrivateKeyPaths(List<String> sshTunnelPrivateKeyPaths) { this.sshTunnelPrivateKeyPaths = sshTunnelPrivateKeyPaths != null ? sshTunnelPrivateKeyPaths : new ArrayList<>(); }

    public String getSshTunnelHostId() { return sshTunnelHostId; }
    public void setSshTunnelHostId(String sshTunnelHostId) { this.sshTunnelHostId = sshTunnelHostId; }

    public ServerConfig getServerConfig() { return serverConfig; }
    public void setServerConfig(ServerConfig serverConfig) { this.serverConfig = serverConfig; }
}
