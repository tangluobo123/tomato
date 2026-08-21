package com.tangluobo.tomato.module.tools.server;

/**
 * 文件服务器接口
 */
public interface FileServer {
    /**
     * 获取服务器类型
     */
    ServerType getType();

    /**
     * 启动服务器
     * @param config 服务器配置
     * @throws Exception 启动失败时抛出异常
     */
    void start(ServerConfig config) throws Exception;

    /**
     * 停止服务器
     * @throws Exception 停止失败时抛出异常
     */
    void stop() throws Exception;

    /**
     * 服务器是否正在运行
     */
    boolean isRunning();

    /**
     * 获取服务器监听地址（如 "http://0.0.0.0:8080"）
     */
    String getListenAddress();
}
