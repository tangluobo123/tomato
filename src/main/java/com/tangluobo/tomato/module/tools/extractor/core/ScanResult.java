package com.tangluobo.tomato.module.tools.extractor.core;

import com.tangluobo.tomato.module.tools.extractor.format.FileFormatInfo;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 一次扫描发现的嵌入式资源记录
 *
 * <p>支持两种数据来源:
 * <ul>
 *   <li>从源文件指定偏移读取 (sourceFile + offset + size)</li>
 *   <li>内联数据 (inlineData 不为 null 时, 直接使用该字节数组)</li>
 * </ul>
 */
public class ScanResult {

    private final Path sourceFile;
    private final long offset;
    private final long size;
    private final FileFormatInfo format;
    private final boolean stored;
    private final String resourceName;
    private final byte[] inlineData;
    private final String archiveEntry;

    public ScanResult(Path sourceFile, long offset, long size, FileFormatInfo format,
                      boolean stored, String resourceName) {
        this(sourceFile, offset, size, format, stored, resourceName, null, null);
    }

    public ScanResult(Path sourceFile, long offset, long size, FileFormatInfo format,
                      boolean stored, String resourceName,
                      byte[] inlineData, String archiveEntry) {
        this.sourceFile = sourceFile;
        this.offset = offset;
        this.size = size;
        this.format = format;
        this.stored = stored;
        this.resourceName = resourceName;
        this.inlineData = inlineData;
        this.archiveEntry = archiveEntry;
    }

    public Path getSourceFile() {
        return sourceFile;
    }

    public long getOffset() {
        return offset;
    }

    public long getSize() {
        return size;
    }

    public FileFormatInfo getFormat() {
        return format;
    }

    public boolean isStored() {
        return stored;
    }

    public String getResourceName() {
        return resourceName;
    }

    public boolean isInline() {
        return inlineData != null;
    }

    public byte[] getInlineData() {
        return inlineData;
    }

    public String getArchiveEntry() {
        return archiveEntry;
    }

    public String defaultOutputName() {
        String baseName;
        if (archiveEntry != null && !archiveEntry.isEmpty()) {
            String entryName = archiveEntry;
            int slash = Math.max(entryName.lastIndexOf('/'), entryName.lastIndexOf('\\'));
            if (slash >= 0) entryName = entryName.substring(slash + 1);
            baseName = stripExt(entryName);
            if (baseName.isEmpty()) baseName = "entry";
        } else if (sourceFile != null) {
            baseName = stripExt(sourceFile.getFileName().toString());
        } else {
            baseName = "extract";
        }
        if (resourceName != null && !resourceName.isEmpty()) {
            baseName = baseName + "_" + sanitize(resourceName);
        }
        return String.format("%s_%06d.%s", baseName, offset, format.getExtension());
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (char c : name.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ScanResult)) return false;
        ScanResult r = (ScanResult) o;
        return offset == r.offset && size == r.size
                && Objects.equals(sourceFile, r.sourceFile)
                && Objects.equals(format.getExtension(), r.format.getExtension())
                && Objects.equals(archiveEntry, r.archiveEntry);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceFile, offset, size, format.getExtension(), archiveEntry);
    }

    @Override
    public String toString() {
        String src = archiveEntry != null ? archiveEntry
                : (sourceFile != null ? sourceFile.getFileName().toString() : "<mem>");
        return String.format("%s @ %s size=%d [%s]",
                format.getExtension(),
                "0x" + Long.toHexString(offset),
                size,
                src);
    }
}