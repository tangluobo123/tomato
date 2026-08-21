package com.tangluobo.tomato.module.tools.extractor.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 文件操作工具类
 */
public final class FileUtils {

    private FileUtils() {}

    private static final int BUFFER_SIZE = 64 * 1024;

    /** 复制 InputStream 到 OutputStream */
    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    /** 读取整个文件为字节数组 (仅在文件较小时使用) */
    public static byte[] readAllBytes(Path path) throws IOException {
        return Files.readAllBytes(path);
    }

    /** 安全创建目录 */
    public static void ensureDir(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    /**
     * 生成唯一的输出文件名 (避免冲突)
     * @param outDir 输出目录
     * @param baseName 基础名
     * @param ext 扩展名 (不含点)
     * @param offset 用于区分的偏移量
     */
    public static Path uniquePath(Path outDir, String baseName, String ext, long offset) {
        String name = String.format("%s_%06d.%s", baseName, offset, ext);
        Path p = outDir.resolve(name);
        int suffix = 1;
        while (Files.exists(p)) {
            name = String.format("%s_%06d_%d.%s", baseName, offset, suffix++, ext);
            p = outDir.resolve(name);
        }
        return p;
    }

    /** 人类可读的文件大小 */
    public static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / 1024.0 / 1024.0);
        return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}