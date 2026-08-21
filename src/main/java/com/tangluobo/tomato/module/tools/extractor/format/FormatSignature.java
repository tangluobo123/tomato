package com.tangluobo.tomato.module.tools.extractor.format;

import java.util.Arrays;

/**
 * 文件格式签名 - 描述如何识别并计算某种格式的长度
 *
 * <p>一个签名由以下几部分组成:
 * <ul>
 *   <li>magic: 起始魔数字节 (支持通配 '?')</li>
 *   <li>minSize: 该格式允许的最小长度</li>
 *   <li>sizer: 可选的长度计算器 (当 null 时, 长度由后续内容推断或使用整段剩余字节)</li>
 * </ul>
 */
public class FormatSignature {

    /** 通配字节 */
    public static final byte ANY = 0x00;

    private final FileFormatInfo format;
    private final byte[] magic;
    private final boolean[] mask;
    private final long minSize;
    private final SignatureSizer sizer;

    public FormatSignature(FileFormatInfo format, byte[] magic, long minSize, SignatureSizer sizer) {
        this.format = format;
        this.magic = magic.clone();
        this.mask = new boolean[magic.length];
        for (int i = 0; i < magic.length; i++) {
            this.mask[i] = true;
        }
        this.minSize = minSize;
        this.sizer = sizer;
    }

    /**
     * 构建签名 - magic 中 '?' 字符表示通配位
     */
    public FormatSignature(FileFormatInfo format, String magicHex, long minSize, SignatureSizer sizer) {
        this.format = format;
        byte[] parsed = parseHex(magicHex);
        this.magic = new byte[parsed.length];
        this.mask = new boolean[parsed.length];
        for (int i = 0; i < parsed.length; i++) {
            if (parsed[i] == ANY) {
                this.mask[i] = false;
                this.magic[i] = 0;
            } else {
                this.mask[i] = true;
                this.magic[i] = parsed[i];
            }
        }
        this.minSize = minSize;
        this.sizer = sizer;
    }

    public FileFormatInfo getFormat() {
        return format;
    }

    public int getMagicLength() {
        return magic.length;
    }

    /** 测试 data 在 off 处是否匹配本签名 */
    public boolean matches(byte[] data, int off, int dataLen) {
        if (off + magic.length > dataLen) return false;
        for (int i = 0; i < magic.length; i++) {
            if (mask[i] && data[off + i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /** 计算从 off 开始的资源长度; 若无法确定返回 -1 */
    public long sizeOf(byte[] data, int off, int dataLen) {
        if (sizer != null) {
            try {
                long sz = sizer.size(data, off, dataLen);
                if (sz < minSize) return -1;
                return sz;
            } catch (Exception e) {
                return -1;
            }
        }
        return -1;
    }

    public long getMinSize() {
        return minSize;
    }

    /** 解析十六进制字符串, 支持 '?' 作为通配; 空格分隔 */
    private static byte[] parseHex(String hex) {
        String cleaned = hex.trim().replaceAll("\\s+", "");
        int len = cleaned.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException("Invalid hex magic: " + hex);
        }
        byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; i++) {
            char hi = cleaned.charAt(i * 2);
            char lo = cleaned.charAt(i * 2 + 1);
            if (hi == '?' || lo == '?') {
                out[i] = ANY;
            } else {
                out[i] = (byte) ((hexVal(hi) << 4) | hexVal(lo));
            }
        }
        return out;
    }

    private static int hexVal(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        throw new IllegalArgumentException("Invalid hex char: " + c);
    }

    @Override
    public String toString() {
        return "Signature{" + format + ", magic=" + Arrays.toString(magic) + "}";
    }
}