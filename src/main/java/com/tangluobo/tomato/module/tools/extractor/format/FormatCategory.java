package com.tangluobo.tomato.module.tools.extractor.format;

/**
 * 支持的文件格式分类
 */
public enum FormatCategory {
    GFX("图像"),
    MUSIC("音频"),
    VIDEO("视频"),
    DOCUMENTS("文档"),
    FONTS("字体"),
    ARCHIVE("压缩包"),
    OTHER("其他");

    private final String displayName;

    FormatCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}