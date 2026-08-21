package com.tangluobo.tomato.module.tools.extractor.format;

/**
 * 描述一种可识别/可提取的文件格式
 */
public class FileFormatInfo {

    private final String extension;
    private final String description;
    private final FormatCategory category;

    public FileFormatInfo(String extension, String description, FormatCategory category) {
        this.extension = extension;
        this.description = description;
        this.category = category;
    }

    public String getExtension() {
        return extension;
    }

    public String getDescription() {
        return description;
    }

    public FormatCategory getCategory() {
        return category;
    }

    @Override
    public String toString() {
        return extension + " (" + description + ")";
    }
}