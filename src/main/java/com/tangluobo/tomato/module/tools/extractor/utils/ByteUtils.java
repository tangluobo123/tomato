package com.tangluobo.tomato.module.tools.extractor.utils;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 字节操作工具类 - 提供读取/比较字节的辅助方法
 */
public final class ByteUtils {

    private ByteUtils() {}

    /** 从字节数组读取小端 16 位无符号整数 */
    public static int readUInt16LE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    /** 从字节数组读取小端 32 位无符号整数 */
    public static long readUInt32LE(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16)
                | ((b[off + 3] & 0xFFL) << 24);
    }

    /** 从字节数组读取大端 32 位无符号整数 */
    public static long readUInt32BE(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24)
                | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8)
                | (b[off + 3] & 0xFFL);
    }

    /** 比较指定位置的子数组是否与给定 magic 完全匹配 */
    public static boolean matches(byte[] data, int off, byte[] magic) {
        if (off < 0 || off + magic.length > data.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (data[off + i] != magic[i]) return false;
        }
        return true;
    }

    /** 从 RandomAccessFile 读取一个 int (小端) */
    public static int readIntLE(RandomAccessFile raf) throws IOException {
        return raf.read() | (raf.read() << 8) | (raf.read() << 16) | (raf.read() << 24);
    }

    /** 从 RandomAccessFile 读取一个 long (小端) */
    public static long readLongLE(RandomAccessFile raf) throws IOException {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v |= ((long) raf.read() & 0xFF) << (8 * i);
        }
        return v;
    }

    /** 十六进制格式化 */
    public static String hex(long v) {
        return "0x" + Long.toHexString(v).toUpperCase();
    }

    /** 对齐到指定边界 */
    public static long align(long v, long alignment) {
        return (v + alignment - 1) & ~(alignment - 1);
    }
}