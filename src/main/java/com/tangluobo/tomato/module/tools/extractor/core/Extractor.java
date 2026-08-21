package com.tangluobo.tomato.module.tools.extractor.core;

import com.tangluobo.tomato.module.tools.extractor.utils.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 提取器 - 将扫描发现的资源写入到磁盘
 *
 * <p>支持基于内容 SHA-256 的去重: 当多个扫描结果指向同一份数据时
 * (如 PE 字节扫描和 PE 资源段扫描都找到同一 PNG), 只写出一份.
 */
public class Extractor {

    private final Path outputDir;
    private final boolean overwrite;
    private final boolean verbose;
    private final boolean dedup;

    private final Set<String> seenOffsets = new HashSet<>();

    public Extractor(Path outputDir, boolean overwrite, boolean verbose) {
        this(outputDir, overwrite, verbose, false);
    }

    public Extractor(Path outputDir, boolean overwrite, boolean verbose, boolean dedup) {
        this.outputDir = outputDir;
        this.overwrite = overwrite;
        this.verbose = verbose;
        this.dedup = dedup;
    }

    /**
     * 提取单个资源到磁盘
     * @return 写出的文件路径, 跳过(去重)或失败返回 null
     */
    public Path extract(ScanResult result) throws IOException {
        FileUtils.ensureDir(outputDir);

        byte[] data;
        if (result.isInline()) {
            data = result.getInlineData();
        } else {
            data = readFromSource(result);
        }
        if (data == null || data.length == 0) return null;

        if (dedup) {
            String key = dedupKey(result);
            if (seenOffsets.contains(key)) {
                if (verbose) {
                    System.out.printf("[skip-dup] %s (same offset already written)%n", result);
                }
                return null;
            }
            seenOffsets.add(key);
        }

        String ext = result.getFormat().getExtension().toLowerCase();
        Path typeDir = outputDir.resolve(ext);
        FileUtils.ensureDir(typeDir);

        Path outFile = typeDir.resolve(result.defaultOutputName());
        if (Files.exists(outFile) && !overwrite) {
            int n = 1;
            String name = outFile.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String nameExt = dot > 0 ? name.substring(dot) : "";
            while (Files.exists(outFile)) {
                outFile = typeDir.resolve(base + "_" + (n++) + nameExt);
            }
        }

        Files.write(outFile, data,
                overwrite ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING}
                          : new StandardOpenOption[]{StandardOpenOption.CREATE_NEW});
        return outFile;
    }

    private byte[] readFromSource(ScanResult result) throws IOException {
        long size = result.getSize();
        long offset = result.getOffset();
        if (result.getSourceFile() == null) return null;

        byte[] data = new byte[(int) size];
        try (InputStream in = Files.newInputStream(result.getSourceFile())) {
            long skipped = 0;
            while (skipped < offset) {
                long s = in.skip(offset - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            if (skipped != offset) {
                throw new IOException("Cannot skip to offset " + offset + " in " + result.getSourceFile());
            }
            int read = 0;
            while (read < data.length) {
                int r = in.read(data, read, data.length - read);
                if (r <= 0) break;
                read += r;
            }
            if (read < data.length) {
                byte[] trimmed = new byte[read];
                System.arraycopy(data, 0, trimmed, 0, read);
                return trimmed;
            }
        }
        return data;
    }

    private static String dedupKey(ScanResult result) {
        String src = result.getSourceFile() != null ? result.getSourceFile().toString() : "<mem>";
        return src + ":" + result.getOffset();
    }

    /**
     * 批量提取所有结果
     * @return 成功写出的文件路径列表
     */
    public List<Path> extractAll(List<ScanResult> results) {
        List<Path> written = new ArrayList<>();
        int ok = 0;
        int dup = 0;
        int fail = 0;
        for (ScanResult r : results) {
            try {
                Path p = extract(r);
                if (p != null) {
                    written.add(p);
                    ok++;
                    if (verbose) {
                        System.out.printf("[ok] %s -> %s (%s)%n",
                                r, p.getFileName(), FileUtils.humanSize(r.getSize()));
                    }
                } else {
                    dup++;
                }
            } catch (IOException e) {
                fail++;
                if (verbose) {
                    System.err.println("[fail] " + r + ": " + e.getMessage());
                }
            }
        }
        if (verbose) {
            System.out.printf("[summary] extracted=%d duplicates=%d failed=%d%n", ok, dup, fail);
        }
        return written;
    }

    public Path getOutputDir() {
        return outputDir;
    }
}