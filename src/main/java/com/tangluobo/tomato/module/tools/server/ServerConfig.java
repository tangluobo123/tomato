package com.tangluobo.tomato.module.tools.server;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务器配置
 */
public class ServerConfig {
    private ServerType type;
    private int port;
    private String bindAddress; // 绑定地址，默认 0.0.0.0
    private boolean anonymousAccess; // 是否允许匿名访问
    private String rootDirectory;   // 根目录（用于无共享目录配置时的默认目录）

    private List<SharedDirectory> sharedDirectories;
    private List<ServerAccount> accounts;

    public ServerConfig() {
        this.bindAddress = "0.0.0.0";
        this.anonymousAccess = false;
        this.sharedDirectories = new ArrayList<>();
        this.accounts = new ArrayList<>();
    }

    public ServerConfig(ServerType type) {
        this();
        this.type = type;
        this.port = type.getDefaultPort();
    }

    public ServerType getType() {
        return type;
    }

    public void setType(ServerType type) {
        this.type = type;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }

    public boolean isAnonymousAccess() {
        return anonymousAccess;
    }

    public void setAnonymousAccess(boolean anonymousAccess) {
        this.anonymousAccess = anonymousAccess;
    }

    public String getRootDirectory() {
        return rootDirectory;
    }

    public void setRootDirectory(String rootDirectory) {
        this.rootDirectory = rootDirectory;
    }

    public List<SharedDirectory> getSharedDirectories() {
        return sharedDirectories;
    }

    public void setSharedDirectories(List<SharedDirectory> sharedDirectories) {
        this.sharedDirectories = sharedDirectories != null ? sharedDirectories : new ArrayList<>();
    }

    public List<ServerAccount> getAccounts() {
        return accounts;
    }

    public void setAccounts(List<ServerAccount> accounts) {
        this.accounts = accounts != null ? accounts : new ArrayList<>();
    }
}
