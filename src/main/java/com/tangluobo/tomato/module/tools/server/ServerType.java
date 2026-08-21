package com.tangluobo.tomato.module.tools.server;

/**
 * 服务器类型枚举
 */
public enum ServerType {
    HTTP("HTTP", "HTTP文件服务器", 8080, "/images/connect/code.png"),
    FTP("FTP", "FTP文件服务器", 2121, "/images/connect/ftp.png"),
    SMB("SMB", "SMB文件共享服务器", 445, "/images/connect/server.png");

    private final String code;
    private final String displayName;
    private final int defaultPort;
    private final String iconPath;

    ServerType(String code, String displayName, int defaultPort, String iconPath) {
        this.code = code;
        this.displayName = displayName;
        this.defaultPort = defaultPort;
        this.iconPath = iconPath;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getDefaultPort() {
        return defaultPort;
    }

    public String getIconPath() {
        return iconPath;
    }

    public static ServerType fromCode(String code) {
        if (code == null) return HTTP;
        for (ServerType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return HTTP;
    }
}
