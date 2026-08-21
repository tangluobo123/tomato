package com.tangluobo.tomato.module.tools.extractor.format;

import com.tangluobo.tomato.module.tools.extractor.utils.ByteUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 格式签名注册中心 - 集中注册所有可识别格式的 magic 签名与长度计算策略
 *
 * <p>这是整个扫描引擎的核心配置. 每种格式由 magic header + 长度计算器构成,
 * 长度计算器决定在嵌入式扫描中如何切分出该资源的边界.
 */
public class FormatRegistry {

    private final List<FormatSignature> signatures = new ArrayList<>();
    private final Map<FormatCategory, List<FormatSignature>> byCategory = new EnumMap<>(FormatCategory.class);
    private final Map<String, FileFormatInfo> byExtension = new HashMap<>();
    private final Set<String> enabled = new HashSet<>();

    public FormatRegistry() {
        registerAll();
        enableAll();
    }

    /** 启用全部格式 */
    public void enableAll() {
        enabled.clear();
        for (FileFormatInfo info : byExtension.values()) {
            enabled.add(info.getExtension());
        }
    }

    /** 启用指定分类下的所有格式 */
    public void enableCategory(FormatCategory cat) {
        for (FormatSignature s : byCategory.getOrDefault(cat, Collections.emptyList())) {
            enabled.add(s.getFormat().getExtension());
        }
    }

    /** 仅启用给定扩展名列表 */
    public void enableOnly(Set<String> exts) {
        enabled.clear();
        for (String e : exts) {
            enabled.add(e.toLowerCase());
        }
    }

    public void enable(String ext) {
        enabled.add(ext.toLowerCase());
    }

    public void disable(String ext) {
        enabled.remove(ext.toLowerCase());
    }

    public boolean isEnabled(FileFormatInfo info) {
        return enabled.contains(info.getExtension());
    }

    public List<FormatSignature> getEnabledSignatures() {
        List<FormatSignature> out = new ArrayList<>();
        for (FormatSignature s : signatures) {
            if (isEnabled(s.getFormat())) {
                out.add(s);
            }
        }
        return out;
    }

    public List<FileFormatInfo> getAllFormats() {
        return new ArrayList<>(byExtension.values());
    }

    public List<FormatSignature> getAllSignatures() {
        return signatures;
    }

    // ---------- 注册 API ----------

    private void add(FileFormatInfo info, String magic, long minSize, SignatureSizer sizer) {
        FormatSignature sig = new FormatSignature(info, magic, minSize, sizer);
        signatures.add(sig);
        byCategory.computeIfAbsent(info.getCategory(), k -> new ArrayList<>()).add(sig);
        byExtension.putIfAbsent(info.getExtension(), info);
    }

    private void add(FileFormatInfo info, String magic) {
        add(info, magic, 0L, null);
    }

    // ============================================================
    //  全部格式签名注册表
    // ============================================================
    private void registerAll() {
        registerImages();
        registerAudio();
        registerVideo();
        registerDocuments();
        registerFonts();
        registerArchives();
        registerOther();
    }

    private void registerImages() {
        add(new FileFormatInfo("bmp", "Bitmap Image", FormatCategory.GFX),
                "42 4D", 54,
                (d, o, n) -> ByteUtils.readUInt32LE(d, o + 2));

        add(new FileFormatInfo("png", "Portable Network Graphics", FormatCategory.GFX),
                "89 50 4E 47 0D 0A 1A 0A", 8, new PngSizer());

        add(new FileFormatInfo("gif", "GIF Image", FormatCategory.GFX),
                "47 49 46 38 37 61", 6, new GifSizer());
        add(new FileFormatInfo("gif", "GIF Image", FormatCategory.GFX),
                "47 49 46 38 39 61", 6, new GifSizer());

        add(new FileFormatInfo("jpg", "JPEG Image", FormatCategory.GFX),
                "FF D8 FF", 4, new JpegSizer());

        add(new FileFormatInfo("ico", "Icon File", FormatCategory.GFX),
                "00 00 01 00", 6, new IconSizer(1));
        add(new FileFormatInfo("cur", "Cursor File", FormatCategory.GFX),
                "00 00 02 00", 6, new IconSizer(2));

        add(new FileFormatInfo("dds", "DirectDraw Surface", FormatCategory.GFX),
                "44 44 53 20 7C 00 00 00", 8, null);

        add(new FileFormatInfo("pcx", "PCX Image", FormatCategory.GFX),
                "0A ?? 01 ??", 4, null);

        add(new FileFormatInfo("tif", "TIFF Image", FormatCategory.GFX),
                "49 49 2A 00", 4, null);
        add(new FileFormatInfo("tif", "TIFF Image", FormatCategory.GFX),
                "4D 4D 00 2A", 4, null);

        add(new FileFormatInfo("emf", "Enhanced Metafile", FormatCategory.GFX),
                "01 00 00 00", 8, null);

        add(new FileFormatInfo("wmf", "Windows Metafile", FormatCategory.GFX),
                "D7 CD C6 9A", 4, null);
        add(new FileFormatInfo("wmf", "Windows Metafile", FormatCategory.GFX),
                "01 00 09 00 00 03", 6, null);
    }

    private void registerAudio() {
        add(new FileFormatInfo("wav", "Wave Audio", FormatCategory.MUSIC),
                "52 49 46 46 ?? ?? ?? ?? 57 41 56 45", 12,
                (d, o, n) -> ByteUtils.readUInt32LE(d, o + 4) + 8);

        add(new FileFormatInfo("mp3", "MP3 Audio", FormatCategory.MUSIC),
                "49 44 33", 10, new Mp3Id3Sizer());

        add(new FileFormatInfo("ogg", "OGG Vorbis Audio", FormatCategory.MUSIC),
                "4F 67 67 53", 4, null);

        add(new FileFormatInfo("mid", "MIDI Audio", FormatCategory.MUSIC),
                "4D 54 68 64", 4, null);

        add(new FileFormatInfo("xm", "Extended Module", FormatCategory.MUSIC),
                "45 78 74 65 6E 64 65 64 20 4D 6F 64 75 6C 65 3A", 16, null);

        add(new FileFormatInfo("mod", "Amiga Module", FormatCategory.MUSIC),
                "4D 2E 4B 2E", 4, null);

        add(new FileFormatInfo("s3m", "Scream Tracker 3", FormatCategory.MUSIC),
                "53 43 52 4D", 4, null);
    }

    private void registerVideo() {
        add(new FileFormatInfo("avi", "AVI Video", FormatCategory.VIDEO),
                "52 49 46 46 ?? ?? ?? ?? 41 56 49 20", 12,
                (d, o, n) -> ByteUtils.readUInt32LE(d, o + 4) + 8);

        add(new FileFormatInfo("asf", "Advanced Streaming Format", FormatCategory.VIDEO),
                "30 26 B2 75 8E 66 CF 11", 8, null);

        add(new FileFormatInfo("mov", "QuickTime Movie", FormatCategory.VIDEO),
                "?? ?? ?? ?? 6D 6F 6F 76", 8, null);
        add(new FileFormatInfo("mov", "QuickTime Movie", FormatCategory.VIDEO),
                "?? ?? ?? ?? 66 72 65 65", 8, null);
        add(new FileFormatInfo("mov", "QuickTime Movie", FormatCategory.VIDEO),
                "?? ?? ?? ?? 6D 64 61 74", 8, null);

        add(new FileFormatInfo("mpg", "MPEG Video", FormatCategory.VIDEO),
                "00 00 01 BA", 4, null);

        add(new FileFormatInfo("3gp", "3GPP Video", FormatCategory.VIDEO),
                "?? ?? ?? ?? 66 74 79 70 33 67 70", 11, null);

        add(new FileFormatInfo("mp4", "MPEG-4 Video", FormatCategory.VIDEO),
                "?? ?? ?? ?? 66 74 79 70", 8, null);

        add(new FileFormatInfo("bik", "Bink Video", FormatCategory.VIDEO),
                "42 49 4B", 3, null);

        add(new FileFormatInfo("smk", "Smacker Video", FormatCategory.VIDEO),
                "53 4D 4B", 3, null);

        add(new FileFormatInfo("swf", "Shockwave Flash", FormatCategory.VIDEO),
                "46 57 53", 3, new SwfSizer());
        add(new FileFormatInfo("swf", "Shockwave Flash (CWS)", FormatCategory.VIDEO),
                "43 57 53", 3, null);
        add(new FileFormatInfo("swf", "Shockwave Flash (ZWS)", FormatCategory.VIDEO),
                "5A 57 53", 3, null);
    }

    private void registerDocuments() {
        add(new FileFormatInfo("pdf", "PDF Document", FormatCategory.DOCUMENTS),
                "25 50 44 46 2D", 5, new PdfSizer());

        add(new FileFormatInfo("doc", "Word Document (CFB)", FormatCategory.DOCUMENTS),
                "D0 CF 11 E0 A1 B1 1A E1", 8, null);

        add(new FileFormatInfo("zip", "ZIP Archive", FormatCategory.ARCHIVE),
                "50 4B 03 04", 4, null);
        add(new FileFormatInfo("zip", "ZIP Empty", FormatCategory.ARCHIVE),
                "50 4B 05 06", 4, null);
        add(new FileFormatInfo("zip", "ZIP Spanned", FormatCategory.ARCHIVE),
                "50 4B 07 08", 4, null);

        add(new FileFormatInfo("rtf", "Rich Text Format", FormatCategory.DOCUMENTS),
                "7B 5C 72 74 66 31", 6, null);
    }

    private void registerFonts() {
        add(new FileFormatInfo("ttf", "TrueType Font", FormatCategory.FONTS),
                "00 01 00 00 00", 5, null);

        add(new FileFormatInfo("otf", "OpenType Font", FormatCategory.FONTS),
                "4F 54 54 4F", 4, null);

        add(new FileFormatInfo("ttc", "TrueType Collection", FormatCategory.FONTS),
                "74 72 75 65", 4, null);

        add(new FileFormatInfo("woff", "Web Open Font", FormatCategory.FONTS),
                "77 4F 46 46", 4, null);
    }

    private void registerArchives() {
        add(new FileFormatInfo("rar", "RAR Archive", FormatCategory.ARCHIVE),
                "52 61 72 21 1A 07 00", 7, null);
        add(new FileFormatInfo("rar", "RAR v5 Archive", FormatCategory.ARCHIVE),
                "52 61 72 21 1A 07 01 00", 8, null);

        add(new FileFormatInfo("cab", "Cabinet File", FormatCategory.ARCHIVE),
                "4D 53 43 46", 4, null);

        add(new FileFormatInfo("jar", "Java Archive", FormatCategory.ARCHIVE),
                "50 4B 03 04", 4, null);

        add(new FileFormatInfo("gz", "GZip Archive", FormatCategory.ARCHIVE),
                "1F 8B 08", 3, null);

        add(new FileFormatInfo("7z", "7-Zip Archive", FormatCategory.ARCHIVE),
                "37 7A BC AF 27 1C", 6, null);

        add(new FileFormatInfo("bz2", "BZip2 Archive", FormatCategory.ARCHIVE),
                "42 5A 68", 3, null);

        add(new FileFormatInfo("chm", "Compiled HTML Help", FormatCategory.ARCHIVE),
                "49 54 53 46", 4, null);

        add(new FileFormatInfo("arj", "ARJ Archive", FormatCategory.ARCHIVE),
                "60 EA", 2, null);
    }

    private void registerOther() {
        add(new FileFormatInfo("3ds", "3D Studio Mesh", FormatCategory.OTHER),
                "4D 4D ?? ?? 02 00 0A 00", 6, null);

        add(new FileFormatInfo("mpq", "Mo'PaQ Archive", FormatCategory.ARCHIVE),
                "4D 50 51 1A", 4, null);

        add(new FileFormatInfo("upk", "Unreal Package", FormatCategory.OTHER),
                "C2 30 1E C3 02 00 00 00", 4, null);

        add(new FileFormatInfo("dat", "Video CD Data", FormatCategory.VIDEO),
                "52 49 46 46 ?? ?? ?? ?? 43 44 58 41", 12,
                (d, o, n) -> ByteUtils.readUInt32LE(d, o + 4) + 8);

        add(new FileFormatInfo("iff", "IFF Interchange File", FormatCategory.OTHER),
                "46 4F 52 4D", 4, null);
    }

    // ============================================================
    //  特殊长度计算器
    // ============================================================

    private static final class JpegSizer implements SignatureSizer {
        @Override
        public long size(byte[] d, int o, int n) {
            int i = o + 2;
            while (i + 1 < n) {
                if (d[i] == (byte) 0xFF && d[i + 1] == (byte) 0xD9) {
                    return (i + 2) - o;
                }
                i++;
            }
            return -1;
        }
    }

    private static final class IconSizer implements SignatureSizer {
        private final int type;

        IconSizer(int type) {
            this.type = type;
        }

        @Override
        public long size(byte[] d, int o, int n) {
            if (o + 6 > n) return -1;
            int count = ByteUtils.readUInt16LE(d, o + 4);
            if (count == 0 || count > 1024) return -1;
            long end = 0;
            int entryBase = o + 6;
            for (int i = 0; i < count; i++) {
                int p = entryBase + i * 16;
                if (p + 16 > n) return -1;
                long size = ByteUtils.readUInt32LE(d, p + 8);
                long offset = ByteUtils.readUInt32LE(d, p + 12);
                long e = offset + size;
                if (e > end) end = e;
            }
            long total = end;
            if (total <= 0 || total > n - o) {
                total = (entryBase + count * 16L) - o;
            }
            return total;
        }
    }

    private static final class Mp3Id3Sizer implements SignatureSizer {
        @Override
        public long size(byte[] d, int o, int n) {
            if (o + 10 > n) return -1;
            long size = ((d[o + 6] & 0x7FL) << 21)
                    | ((d[o + 7] & 0x7FL) << 14)
                    | ((d[o + 8] & 0x7FL) << 7)
                    | (d[o + 9] & 0x7FL);
            return 10 + size;
        }
    }

    private static final class PdfSizer implements SignatureSizer {
        @Override
        public long size(byte[] d, int o, int n) {
            byte[] eof = {'%', '%', 'E', 'O', 'F'};
            int i = o + 5;
            int last = -1;
            while (i + eof.length <= n) {
                boolean ok = true;
                for (int k = 0; k < eof.length; k++) {
                    if (d[i + k] != eof[k]) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    last = i + eof.length;
                }
                i++;
                if (last > 0 && i - last > 1024) break;
            }
            return last > 0 ? (last - o) : -1;
        }
    }

    private static final class PngSizer implements SignatureSizer {
        private static final byte[] IEND = {
            0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82
        };

        @Override
        public long size(byte[] d, int o, int n) {
            int i = o + 8;
            while (i + IEND.length <= n) {
                boolean found = true;
                for (int k = 0; k < IEND.length; k++) {
                    if (d[i + k] != IEND[k]) {
                        found = false;
                        break;
                    }
                }
                if (found) {
                    return (i + IEND.length) - o;
                }
                i++;
            }
            return -1;
        }
    }

    private static final class GifSizer implements SignatureSizer {
        @Override
        public long size(byte[] d, int o, int n) {
            int i = o + 6;
            while (i < n) {
                if (d[i] == 0x3B) {
                    return (i + 1) - o;
                }
                i++;
            }
            return -1;
        }
    }

    private static final class SwfSizer implements SignatureSizer {
        @Override
        public long size(byte[] d, int o, int n) {
            if (o + 8 > n) return -1;
            long len = ByteUtils.readUInt32LE(d, o + 4) & 0x3FFFFFFF;
            if (len < 8 || len > n - o) return -1;
            return len;
        }
    }
}