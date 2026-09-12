package com.atian10.logrecord.core.export;

import java.io.File;
import java.io.IOException;

/**
 * 基于 java.nio.file 的原子替换移动（仅桌面/服务器 JVM 路径）
 * <p>
 * 本类独立存放 java.nio.file 引用：Android API 21 上 {@code java.nio.file}
 * 不可用，Android 使用 Os.rename；仅桌面路径反射加载本类。
 * </p>
 */
final class NioFileMove {

    private NioFileMove() {
        // 工具类禁止实例化
    }

    /**
     * 原子移动文件；文件系统不支持原子替换已有目标时明确失败。
     * <p>方法为 public 以便导出器经 {@link Class#getMethod} 反射定位（类保持包私有）</p>
     *
     * @param source 源文件
     * @param target 目标文件
     * @throws IOException 移动失败（原目标保持不变）
     */
    public static void move(File source, File target) throws IOException {
        java.nio.file.Files.move(source.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
