package com.tangluobo.tomato.module.connect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tangluobo.tomato.utils.SecurityUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ConfigManager {
    private static final String CONFIG_DIR = System.getProperty("user.home") + "/.tomato";
    private static final String CONFIG_FILE = CONFIG_DIR + "/connections.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ENCRYPTION_MARKER = "TOMATO_ENCRYPTED";

    public static String getConfigFilePath() {
        return CONFIG_FILE;
    }

    public static List<ConnectionConfig> loadConnections() {
        Path filePath = Paths.get(CONFIG_FILE);
        if (!Files.exists(filePath)) {
            return new ArrayList<>();
        }

        try {
            long size = Files.size(filePath);
            if (size == 0) {
                return new ArrayList<>();
            }
        } catch (IOException e) {
            return new ArrayList<>();
        }

        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            if (content.startsWith(ENCRYPTION_MARKER)) {
                String encryptedContent = content.substring(ENCRYPTION_MARKER.length());
                String decryptedContent = SecurityUtils.decrypt(encryptedContent);
                return parseJson(decryptedContent);
            } else {
                List<ConnectionConfig> configs = parseJson(content);
                for (ConnectionConfig config : configs) {
                    if (config.getPassword() != null && !config.getPassword().isEmpty()) {
                        try {
                            config.setPassword(SecurityUtils.decrypt(config.getPassword()));
                        } catch (Exception e) {
                        }
                    }
                }
                try {
                    saveConnections(configs);
                } catch (SaveException ignored) {
                }
                return configs;
            }
        } catch (Exception e) {
            System.err.println("[ConfigManager] 加载连接配置失败: " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    private static List<ConnectionConfig> parseJson(String json) {
        try {
            ConnectionConfig[] configs = GSON.fromJson(json, ConnectionConfig[].class);
            if (configs == null) {
                return new ArrayList<>();
            }
            return new ArrayList<>(List.of(configs));
        } catch (Exception e) {
            System.err.println("[ConfigManager] 解析连接配置 JSON 失败: " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    /** 保存连接配置，失败时抛出 SaveException 以便调用者向用户提示错误。 */
    public static void saveConnections(List<ConnectionConfig> connections) throws SaveException {
        try {
            Files.createDirectories(Paths.get(CONFIG_DIR));
        } catch (IOException e) {
            throw new SaveException("创建配置目录失败: " + e.getMessage(), e);
        }

        String json;
        String encryptedContent;
        try {
            json = GSON.toJson(connections);
            encryptedContent = ENCRYPTION_MARKER + SecurityUtils.encrypt(json);
        } catch (Exception e) {
            throw new SaveException("序列化或加密连接配置失败: " + e.getMessage(), e);
        }

        Path tempFile = Paths.get(CONFIG_FILE + ".tmp");
        Path targetFile = Paths.get(CONFIG_FILE);
        try {
            Files.writeString(tempFile, encryptedContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            throw new SaveException("写入临时配置文件失败: " + e.getMessage(), e);
        }

        try {
            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.copy(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                Files.deleteIfExists(tempFile);
            } catch (IOException ex) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
                throw new SaveException("替换配置文件失败 (move+copy均失败): " + ex.getMessage(), ex);
            }
        }

        long writtenSize;
        try {
            writtenSize = Files.size(targetFile);
            if (writtenSize == 0) {
                throw new SaveException("保存后配置文件大小为 0，可能写入不完整", null);
            }
        } catch (IOException e) {
            throw new SaveException("校验保存结果失败: " + e.getMessage(), e);
        }
    }

    public static String generateId() {
        return UUID.randomUUID().toString();
    }

    /** 保存失败时抛出的运行时异常，便于上层通过 try-catch 给用户提示 */
    public static class SaveException extends RuntimeException {
        public SaveException(String msg, Throwable cause) { super(msg, cause); }
    }
}