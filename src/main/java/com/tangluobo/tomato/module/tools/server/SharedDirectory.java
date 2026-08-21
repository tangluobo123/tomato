package com.tangluobo.tomato.module.tools.server;

import java.util.ArrayList;
import java.util.List;

/**
 * 共享目录配置
 */
public class SharedDirectory {
    private String alias;       // 共享别名（如 "files"）
    private String path;        // 本地目录路径
    private boolean readOnly;   // 是否只读
    private List<String> allowedUsers; // 允许访问的用户名列表（空表示所有用户）

    public SharedDirectory() {
        this.allowedUsers = new ArrayList<>();
    }

    public SharedDirectory(String alias, String path, boolean readOnly) {
        this.alias = alias;
        this.path = path;
        this.readOnly = readOnly;
        this.allowedUsers = new ArrayList<>();
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public List<String> getAllowedUsers() {
        return allowedUsers;
    }

    public void setAllowedUsers(List<String> allowedUsers) {
        this.allowedUsers = allowedUsers != null ? allowedUsers : new ArrayList<>();
    }
}
