package com.tangluobo.tomato.module.tools.extractor.pe;

import com.tangluobo.tomato.module.tools.extractor.utils.ByteUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PE 资源提取器 - 将 PE 文件 .rsrc 段中的资源转换为可读文件.
 *
 * <p>转换规则:
 * <ul>
 *   <li>RT_BITMAP (2) -> .bmp (前置 BITMAPFILEHEADER)</li>
 *   <li>RT_GROUP_ICON (14) + RT_ICON (3) -> .ico</li>
 *   <li>RT_GROUP_CURSOR (12) + RT_CURSOR (1) -> .cur</li>
 *   <li>RT_MANIFEST (24) -> .xml</li>
 *   <li>RT_VERSION (16) -> .bin</li>
 *   <li>RT_STRING (6) -> .txt</li>
 *   <li>其他 -> .bin</li>
 * </ul>
 */
public class PEResourceExtractor {

    public static final int RT_CURSOR = 1;
    public static final int RT_BITMAP = 2;
    public static final int RT_ICON = 3;
    public static final int RT_MENU = 4;
    public static final int RT_DIALOG = 5;
    public static final int RT_STRING = 6;
    public static final int RT_ACCELERATOR = 9;
    public static final int RT_RCDATA = 10;
    public static final int RT_GROUP_CURSOR = 12;
    public static final int RT_GROUP_ICON = 14;
    public static final int RT_VERSION = 16;
    public static final int RT_HTML = 23;
    public static final int RT_MANIFEST = 24;

    /** 提取结果 - 已转换好的字节数据 */
    public static class ExtractedResource {
        public final String name;
        public final String extension;
        public final byte[] data;
        public final int type;

        public ExtractedResource(String name, String extension, byte[] data, int type) {
            this.name = name;
            this.extension = extension;
            this.data = data;
            this.type = type;
        }
    }

    /**
     * 从 PE 文件中提取全部资源, 返回已转换的字节数据列表
     */
    public List<ExtractedResource> extract(PEFile pe) {
        List<ExtractedResource> out = new ArrayList<>();
        byte[] data = pe.getData();

        Map<Integer, List<PEFile.ResourceEntry>> groupIcons = collectByType(pe, RT_GROUP_ICON);
        Map<Integer, List<PEFile.ResourceEntry>> groupCursors = collectByType(pe, RT_GROUP_CURSOR);
        Map<Integer, List<PEFile.ResourceEntry>> icons = collectByType(pe, RT_ICON);
        Map<Integer, List<PEFile.ResourceEntry>> cursors = collectByType(pe, RT_CURSOR);

        for (PEFile.ResourceEntry re : pe.getResources()) {
            if (re.type == RT_BITMAP) {
                byte[] bmp = convertBitmap(data, re);
                out.add(new ExtractedResource(
                        "bitmap_" + (re.nameIsString ? re.nameString : Integer.toString(re.nameId)),
                        "bmp", bmp, RT_BITMAP));
            } else if (re.type == RT_MANIFEST) {
                byte[] d = slice(data, re);
                out.add(new ExtractedResource("manifest", "xml", d, RT_MANIFEST));
            } else if (re.type == RT_VERSION) {
                byte[] d = slice(data, re);
                out.add(new ExtractedResource("version", "bin", d, RT_VERSION));
            } else if (re.type == RT_HTML) {
                byte[] d = slice(data, re);
                out.add(new ExtractedResource("html_" + re.nameId, "html", d, RT_HTML));
            } else if (re.type == RT_STRING) {
                byte[] d = slice(data, re);
                out.add(new ExtractedResource("strings_" + re.nameId, "txt", d, RT_STRING));
            } else if (re.type != RT_ICON && re.type != RT_CURSOR
                    && re.type != RT_GROUP_ICON && re.type != RT_GROUP_CURSOR) {
                byte[] d = slice(data, re);
                String n = re.nameIsString ? re.nameString : Integer.toString(re.nameId);
                out.add(new ExtractedResource("rt" + re.type + "_" + n, "bin", d, re.type));
            }
        }

        for (Map.Entry<Integer, List<PEFile.ResourceEntry>> e : groupIcons.entrySet()) {
            for (PEFile.ResourceEntry group : e.getValue()) {
                byte[] ico = buildIcoFile(data, group, icons, false);
                if (ico != null) {
                    String name = group.nameIsString ? group.nameString : "icon_" + group.nameId;
                    out.add(new ExtractedResource(name, "ico", ico, RT_GROUP_ICON));
                }
            }
        }

        for (Map.Entry<Integer, List<PEFile.ResourceEntry>> e : groupCursors.entrySet()) {
            for (PEFile.ResourceEntry group : e.getValue()) {
                byte[] cur = buildIcoFile(data, group, cursors, true);
                if (cur != null) {
                    String name = group.nameIsString ? group.nameString : "cursor_" + group.nameId;
                    out.add(new ExtractedResource(name, "cur", cur, RT_GROUP_CURSOR));
                }
            }
        }

        return out;
    }

    // ============================================================
    //  转换辅助
    // ============================================================

    private Map<Integer, List<PEFile.ResourceEntry>> collectByType(PEFile pe, int type) {
        Map<Integer, List<PEFile.ResourceEntry>> map = new HashMap<>();
        for (PEFile.ResourceEntry re : pe.getResources()) {
            if (re.type == type) {
                map.computeIfAbsent(re.nameId, k -> new ArrayList<>()).add(re);
            }
        }
        return map;
    }

    private byte[] slice(byte[] data, PEFile.ResourceEntry re) {
        int size = (int) Math.min(re.dataSize, data.length - re.dataOffset);
        if (size <= 0) return new byte[0];
        byte[] out = new byte[size];
        System.arraycopy(data, (int) re.dataOffset, out, 0, size);
        return out;
    }

    /**
     * 将 RT_BITMAP 的原始数据 (BITMAPINFOHEADER + 颜色表 + 位数据) 转换为完整 .bmp 文件
     * 通过前置 14 字节的 BITMAPFILEHEADER
     */
    private byte[] convertBitmap(byte[] data, PEFile.ResourceEntry re) {
        int bmpSize = (int) re.dataSize + 14;
        if (re.dataOffset + re.dataSize > data.length) {
            return slice(data, re);
        }
        byte[] bmp = new byte[bmpSize];
        bmp[0] = 'B'; bmp[1] = 'M';
        writeUInt32LE(bmp, 2, bmpSize);
        writeUInt32LE(bmp, 10, 14 + 40);
        System.arraycopy(data, (int) re.dataOffset, bmp, 14, (int) re.dataSize);
        long headerSize = ByteUtils.readUInt32LE(data, (int) re.dataOffset);
        long pixelOffset = 14 + headerSize;
        writeUInt32LE(bmp, 10, pixelOffset);
        return bmp;
    }

    /**
     * 用 GROUP_ICON / GROUP_CURSOR 目录 + 各 ICON/CURSOR 数据构建完整 .ico / .cur 文件
     *
     * @param isCursor true=cursor, false=icon
     */
    private byte[] buildIcoFile(byte[] data, PEFile.ResourceEntry group,
                                Map<Integer, List<PEFile.ResourceEntry>> items, boolean isCursor) {
        int gOff = (int) group.dataOffset;
        int gSize = (int) group.dataSize;
        if (gOff + 6 > data.length) return null;
        int count = ByteUtils.readUInt16LE(data, gOff + 4);
        if (count == 0 || count > 1024) return null;

        int dirSize = 6 + count * 16;
        int[] iconIds = new int[count];
        for (int i = 0; i < count; i++) {
            int p = gOff + 6 + i * 14;
            if (p + 14 > data.length) return null;
            iconIds[i] = ByteUtils.readUInt16LE(data, p + 12);
        }

        byte[][] iconData = new byte[count][];
        long totalDataSize = 0;
        for (int i = 0; i < count; i++) {
            int id = iconIds[i];
            List<PEFile.ResourceEntry> list = items.get(id);
            if (list == null || list.isEmpty()) {
                return null;
            }
            PEFile.ResourceEntry item = list.get(0);
            iconData[i] = slice(data, item);
            totalDataSize += iconData[i].length;
        }

        byte[] ico = new byte[(int) (dirSize + totalDataSize)];
        int type = isCursor ? 2 : 1;
        writeUInt16LE(ico, 0, 0);
        writeUInt16LE(ico, 2, type);
        writeUInt16LE(ico, 4, count);

        long dataOffset = dirSize;
        for (int i = 0; i < count; i++) {
            int p = gOff + 6 + i * 14;
            int entry = 6 + i * 16;
            if (isCursor) {
                int w = data[p] & 0xFF;
                int h = data[p + 1] & 0xFF;
                ico[entry] = (byte) (w == 0 ? 0 : w);
                ico[entry + 1] = (byte) (h == 0 ? 0 : h);
                ico[entry + 2] = 0;
                ico[entry + 3] = 0;
                writeUInt16LE(ico, entry + 4, ByteUtils.readUInt16LE(data, p + 2));
                writeUInt16LE(ico, entry + 6, ByteUtils.readUInt16LE(data, p + 4));
            } else {
                ico[entry] = data[p];
                ico[entry + 1] = data[p + 1];
                ico[entry + 2] = data[p + 2];
                ico[entry + 3] = data[p + 3];
                writeUInt16LE(ico, entry + 4, ByteUtils.readUInt16LE(data, p + 4));
                writeUInt16LE(ico, entry + 6, ByteUtils.readUInt16LE(data, p + 6));
            }
            writeUInt32LE(ico, entry + 8, iconData[i].length);
            writeUInt32LE(ico, entry + 12, (int) dataOffset);
            System.arraycopy(iconData[i], 0, ico, (int) dataOffset, iconData[i].length);
            dataOffset += iconData[i].length;
        }
        return ico;
    }

    private static void writeUInt16LE(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
    }

    private static void writeUInt32LE(byte[] b, int off, long v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }
}