package com.atian10.logrecord.core.export;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 导出编码枚举
 * <p>
 * 提供常用编码选项，默认 UTF_8。
 * </p>
 */
public enum ExportEncoding {

    /** UTF-8 编码（默认） */
    UTF_8(StandardCharsets.UTF_8),

    /** GBK 编码（中文 Windows 常用） */
    GBK(Charset.forName("GBK")),

    /** UTF-16 编码 */
    UTF_16(StandardCharsets.UTF_16),

    /** ISO-8859-1 编码 */
    ISO_8859_1(StandardCharsets.ISO_8859_1);

    private final Charset charset;

    ExportEncoding(Charset charset) {
        this.charset = charset;
    }

    /**
     * 获取对应的 Charset
     * @return NIO Charset
     */
    public Charset getCharset() {
        return charset;
    }
}
