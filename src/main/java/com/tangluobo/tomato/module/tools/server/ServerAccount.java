package com.tangluobo.tomato.module.tools.server;

/**
 * 服务器账号
 */
public class ServerAccount {
    private String username;
    private String password;
    private boolean enabled;
    private String homeDirectory; // 用户主目录（可选，为空使用默认根目录）

    public ServerAccount() {
        this.enabled = true;
    }

    public ServerAccount(String username, String password) {
        this.username = username;
        this.password = password;
        this.enabled = true;
    }

    public ServerAccount(String username, String password, String homeDirectory) {
        this.username = username;
        this.password = password;
        this.homeDirectory = homeDirectory;
        this.enabled = true;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHomeDirectory() {
        return homeDirectory;
    }

    public void setHomeDirectory(String homeDirectory) {
        this.homeDirectory = homeDirectory;
    }
}
