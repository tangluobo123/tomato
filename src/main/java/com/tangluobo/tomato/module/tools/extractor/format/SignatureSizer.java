package com.tangluobo.tomato.module.tools.extractor.format;

/**
 * 计算嵌入式资源长度的策略接口
 */
@FunctionalInterface
public interface SignatureSizer {

    /**
     * 给定数据缓冲区、起始偏移和总长度, 返回该资源的总字节数
     * @return 资源长度, 若无法确定返回 -1
     */
    long size(byte[] data, int off, int dataLen);
}