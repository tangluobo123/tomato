package com.tangluobo.tomato.module.tools.extractor.core;

import com.tangluobo.tomato.module.tools.extractor.format.FormatRegistry;
import com.tangluobo.tomato.module.tools.extractor.format.FormatSignature;
import com.tangluobo.tomato.module.tools.extractor.pe.PEFile;
import com.tangluobo.tomato.module.tools.extractor.utils.ByteUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 文件扫描引擎 - 基于文件签名 (magic bytes) 在二进制文件中查找嵌入式资源.
 *
 * <p>工作原理:
 * <ol>
 *   <li>将整个源文件读入内存 (受最大文件大小限制)</li>
 *   <li>从每个偏移开始测试所有启用的格式签名</li>
 *   <li>匹配成功后调用 sizer 计算资源长度, 无法计算时跳过该偏移</li>
 *   <li>跳过已匹配资源占据的字节范围, 避免嵌套误报</li>
 *   <li>当 unpackArchives=true 时, 对 ZIP 文件还会解压每个条目并扫描</li>
 * </ol>
 */
public class FileScanner {

    private final FormatRegistry registry;
    private long maxFileSize = 512L * 1024 * 1024;
    private long minResourceSize = 16;
    private boolean verbose = false;
    private boolean unpackArchives = false;
    private boolean peScan = true;
    private int maxArchiveEntries = 5000;

    public FileScanner(FormatRegistry registry) {
        this.registry = registry;
    }

    public void setMaxFileSize(long maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public void setMinResourceSize(long minResourceSize) {
        this.minResourceSize = minResourceSize;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public void setUnpackArchives(boolean unpackArchives) {
        this.unpackArchives = unpackArchives;
    }

    public void setPeScan(boolean peScan) {
        this.peScan = peScan;
    }

    public boolean isVerbose() {
        return verbose;
    }

    /**
     * 扫描单个文件, 返回所有发现的资源
     */
    public List<ScanResult> scan(Path file) throws IOException {
        long fileSize = Files.size(file);
        if (fileSize <= 0) {
            return Collections.emptyList();
        }
        if (fileSize > maxFileSize) {
            if (verbose) {
                System.err.println("[skip] file too large: " + file + " (" + fileSize + " bytes)");
            }
            return Collections.emptyList();
        }

        byte[] data = Files.readAllBytes(file);
        List<ScanResult> results = new ArrayList<>(scanBuffer(file, data));

        if (unpackArchives) {
            results.addAll(scanZipEntries(file, data));
        }
        if (peScan) {
            results.addAll(scanPeResources(file, data));
        }
        return results;
    }

    /**
     * 直接对内存中的字节数组进行扫描
     */
    public List<ScanResult> scanBuffer(Path sourceFile, byte[] data) {
        List<ScanResult> results = new ArrayList<>();
        List<FormatSignature> sigs = registry.getEnabledSignatures();

        int len = data.length;
        int i = 0;
        while (i < len) {
            int remaining = len - i;
            if (remaining < 2) break;

            ScanResult hit = null;
            long hitSize = -1;

            for (FormatSignature sig : sigs) {
                if (sig.getMagicLength() > remaining) continue;
                if (!sig.matches(data, i, len)) continue;

                long size = sig.sizeOf(data, i, len);
                if (size < 0) {
                    if (sig.getMagicLength() >= 4) {
                        size = sig.getMagicLength();
                    } else {
                        continue;
                    }
                }
                if (size < minResourceSize) continue;
                if (i + size > len) {
                    size = len - i;
                }

                hit = new ScanResult(sourceFile, i, size, sig.getFormat(), false, null);
                hitSize = size;
                break;
            }

            if (hit != null) {
                results.add(hit);
                i += (int) hitSize;
            } else {
                i++;
            }
        }
        return results;
    }

    /**
     * 解包 ZIP 文件并扫描每个条目的解压后数据.
     */
    private List<ScanResult> scanZipEntries(Path zipFile, byte[] zipData) {
        List<ScanResult> results = new ArrayList<>();
        if (zipData.length < 4
                || zipData[0] != 0x50 || zipData[1] != 0x4B
                || zipData[2] != 0x03 || zipData[3] != 0x04) {
            return results;
        }

        if (verbose) {
            System.out.println("[unpack] ZIP: " + zipFile.getFileName());
        }

        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(zipData);
        try (ZipInputStream zis = new ZipInputStream(bais)) {
            ZipEntry entry;
            int entryCount = 0;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                entryCount++;
                if (entryCount > maxArchiveEntries) {
                    if (verbose) {
                        System.err.println("[unpack] too many entries, stopping at " + maxArchiveEntries);
                    }
                    break;
                }

                String entryName = entry.getName();
                byte[] entryData = readZipEntry(zis, entry);
                if (entryData == null || entryData.length == 0) continue;

                if (isDirectResource(entryName)) {
                    String ext = getExtension(entryName);
                    com.tangluobo.tomato.module.tools.extractor.format.FileFormatInfo fmt = findFormatByExtension(ext);
                    if (fmt != null && registry.isEnabled(fmt)) {
                        ScanResult sr = new ScanResult(
                                zipFile, 0, entryData.length, fmt, true,
                                entryName, entryData, entryName);
                        results.add(sr);
                        if (verbose) {
                            System.out.println("  [entry] " + entryName
                                    + " (" + entryData.length + " bytes)");
                        }
                        continue;
                    }
                }

                List<ScanResult> embedded = scanBuffer(zipFile, entryData);
                for (ScanResult sr : embedded) {
                    byte[] inline = new byte[(int) sr.getSize()];
                    System.arraycopy(entryData, (int) sr.getOffset(), inline, 0, inline.length);
                    ScanResult inlineSr = new ScanResult(
                            zipFile, sr.getOffset(), sr.getSize(), sr.getFormat(), false,
                            entryName, inline, entryName);
                    results.add(inlineSr);
                    if (verbose) {
                        System.out.println("  [embed] " + entryName
                                + " -> " + sr.getFormat().getExtension()
                                + " @" + ByteUtils.hex(sr.getOffset())
                                + " (" + sr.getSize() + " bytes)");
                    }
                }
            }
        } catch (IOException e) {
            if (verbose) {
                System.err.println("[unpack error] " + zipFile + ": " + e.getMessage());
            }
        }
        return results;
    }

    private byte[] readZipEntry(ZipInputStream zis, ZipEntry entry) throws IOException {
        long maxSize = 64L * 1024 * 1024;
        long size = entry.getSize();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(
                size > 0 && size < maxSize ? (int) size : 8192);
        byte[] buf = new byte[32 * 1024];
        int read;
        long total = 0;
        while ((read = zis.read(buf)) > 0) {
            total += read;
            if (total > maxSize) {
                if (verbose) System.err.println("[unpack] entry too large, truncating: " + entry.getName());
                break;
            }
            baos.write(buf, 0, read);
        }
        return baos.toByteArray();
    }

    private boolean isDirectResource(String name) {
        String ext = getExtension(name);
        if (ext.isEmpty()) return false;
        switch (ext) {
            case "js": case "json": case "xml": case "html": case "htm":
            case "css": case "txt": case "cfg": case "ini": case "md":
            case "py": case "c": case "cpp": case "h": case "java":
                return false;
            default:
                return findFormatByExtension(ext) != null;
        }
    }

    private String getExtension(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(i + 1).toLowerCase() : "";
    }

    private com.tangluobo.tomato.module.tools.extractor.format.FileFormatInfo findFormatByExtension(String ext) {
        for (com.tangluobo.tomato.module.tools.extractor.format.FileFormatInfo info : registry.getAllFormats()) {
            if (info.getExtension().equalsIgnoreCase(ext)) {
                return info;
            }
        }
        return null;
    }

    /**
     * 解析 PE 文件的资源段, 把每个资源数据当作独立字节数组重新扫描.
     */
    private List<ScanResult> scanPeResources(Path peFile, byte[] fileData) {
        List<ScanResult> results = new ArrayList<>();
        if (fileData.length < 0x40 || fileData[0] != 'M' || fileData[1] != 'Z') {
            return results;
        }

        PEFile pe;
        try {
            pe = new PEFile(peFile);
        } catch (IOException e) {
            if (verbose) {
                System.err.println("[pe-scan] parse failed: " + peFile + " - " + e.getMessage());
            }
            return results;
        }

        if (verbose) {
            System.out.println("[pe-scan] " + peFile.getFileName()
                    + " resources=" + pe.getResources().size());
        }

        try {
            for (PEFile.ResourceEntry re : pe.getResources()) {
                if (re.dataSize < minResourceSize) continue;
                if (re.dataOffset + re.dataSize > fileData.length) continue;

                int size = (int) re.dataSize;
                byte[] entryData = new byte[size];
                System.arraycopy(fileData, (int) re.dataOffset, entryData, 0, size);

                List<ScanResult> embedded = scanBuffer(peFile, entryData);
                for (ScanResult sr : embedded) {
                    int absOffset = (int) re.dataOffset + (int) sr.getOffset();
                    byte[] inline = new byte[(int) sr.getSize()];
                    System.arraycopy(fileData, absOffset, inline, 0, inline.length);
                    String entryName = "RT" + re.type + "_"
                            + (re.nameIsString && re.nameString != null ? re.nameString
                                                                       : Integer.toString(re.nameId));
                    ScanResult inlineSr = new ScanResult(
                            peFile, absOffset, sr.getSize(), sr.getFormat(), false,
                            entryName, inline, entryName);
                    results.add(inlineSr);
                }
            }
        } finally {
            try { pe.close(); } catch (IOException ignored) {}
        }
        return results;
    }

    /**
     * 扫描目录 (递归可选)
     */
    public List<ScanResult> scanDirectory(Path dir, boolean recursive) throws IOException {
        List<ScanResult> all = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        collectFiles(dir, recursive, files);
        for (Path f : files) {
            if (verbose) {
                System.out.println("[scan] " + f);
            }
            try {
                all.addAll(scan(f));
            } catch (IOException e) {
                if (verbose) {
                    System.err.println("[error] " + f + ": " + e.getMessage());
                }
            }
        }
        return all;
    }

    private void collectFiles(Path dir, boolean recursive, List<Path> out) throws IOException {
        Files.walk(dir, recursive ? Integer.MAX_VALUE : 1)
                .filter(Files::isRegularFile)
                .forEach(out::add);
    }
}