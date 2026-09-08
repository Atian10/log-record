package com.atian10.logrecord.core.export;

import com.atian10.logrecord.core.ExportSnapshotException;
import com.atian10.logrecord.core.IExceptionStorage;
import com.atian10.logrecord.core.IExportSnapshot;
import com.atian10.logrecord.core.IFormatter;
import com.atian10.logrecord.core.IStorage;
import com.atian10.logrecord.core.formatter.DefaultFormatter;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.query.ExceptionQuery;
import com.atian10.logrecord.core.query.LogQuery;
import com.atian10.logrecord.core.util.JsonUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 导出器
 * <p>
 * 将日志/异常按指定格式导出到文件。导出分三个阶段（ISSUE-03 / ISSUE-04 修复）：
 * </p>
 * <ol>
 *   <li><b>快照复制</b>：经 {@link IStorage#openExportSnapshot(LogQuery)} 打开
 *       一致性快照（捕获已提交最大 ID 边界），持有读取保护把匹配记录分批复制到
 *       本次操作私有的原始快照文件（JSON 行，UTF-8）。复制期间允许追加写入、
 *       阻止目标表清理；边界外新增不进入本次导出。</li>
 *   <li><b>格式化输出</b>：释放读取保护后，从快照文件读取记录并格式化，写入
 *       目标目录内的本次专属临时文件。formatter 与回调的耗时在保护范围外执行，
 *       且不会长时间阻挡清理或 WAL 回收。</li>
 *   <li><b>发布</b>：输出文件完整关闭后发布到目标路径（优先 rename 原子替换），
 *       发布成功才触发 onSuccess。</li>
 * </ol>
 * <p>
 * <b>失败契约</b>：快照打开/读取、临时文件创建、格式化（单条失败即终止）、写入、
 * 关闭、发布任一必要步骤失败，导出即失败：onFailure 恰好触发一次，本次的快照与
 * 输出临时文件按归属清理，原目标文件保持不受损，不删除旧文件后再移动。
 * 存储未提供快照适配时明确失败，不回退 OFFSET 分页。线程中断视为取消并同样清理。
 * </p>
 * <p>
 * <b>回调契约</b>：onSuccess 仅在发布完成后触发；onFailure 至多一次；进度回调
 * 与终态回调自身的异常被捕获并单独记录（见 {@link #getLastCallbackError()}），
 * 不影响导出结果，也不会触发相反终态。
 * </p>
 */
public final class Exporter {

    /** 分页大小，避免一次性加载过多数据导致 OOM */
    public static final int PAGE_SIZE = 1000;

    private final IStorage storage;
    private final IExceptionStorage exceptionStorage;
    private final IFormatter formatter;
    /** 最近一次回调自身抛出的异常描述；null 表示无 */
    private volatile String lastCallbackError = null;

    /**
     * 构造导出器
     * @param storage 日志存储
     * @param exceptionStorage 异常存储
     * @param formatter 格式化器；为 null 时使用 {@link DefaultFormatter}
     */
    public Exporter(IStorage storage, IExceptionStorage exceptionStorage,
                    IFormatter formatter) {
        if (storage == null) {
            throw new NullPointerException("storage == null");
        }
        if (exceptionStorage == null) {
            throw new NullPointerException("exceptionStorage == null");
        }
        this.storage = storage;
        this.exceptionStorage = exceptionStorage;
        this.formatter = formatter != null ? formatter : new DefaultFormatter();
    }

    /**
     * 最近一次回调自身抛出的异常描述
     * @return 描述；无则返回 null
     */
    public String getLastCallbackError() {
        return lastCallbackError;
    }

    /**
     * 导出日志到文件（使用构造时指定的格式化器）
     * @param query 查询条件（分页参数会被覆盖）
     * @param format 导出格式
     * @param encoding 导出编码
     * @param filePath 目标文件路径
     * @param callback 回调；可为 null
     * @return 导出总条数
     */
    public int exportLogs(LogQuery query, ExportFormat format, ExportEncoding encoding,
                          String filePath, ExportCallback callback) {
        return exportLogs(query, format, encoding, formatter, filePath, callback);
    }

    /**
     * 导出日志到文件（使用本次调用指定的格式化器）
     * <p>
     * formatter 在导出开始时捕获，本次导出的所有记录使用同一实例；
     * 调用方在导出进行中修改配置只影响后续导出，不会让同一文件混用两种格式（ISSUE-08）。
     * </p>
     * @param query 查询条件（分页参数会被覆盖）
     * @param format 导出格式
     * @param encoding 导出编码
     * @param formatter 本次导出使用的格式化器；为 null 时回退到构造时指定的格式化器
     * @param filePath 目标文件路径
     * @param callback 回调；可为 null
     * @return 导出总条数
     */
    public int exportLogs(LogQuery query, ExportFormat format, ExportEncoding encoding,
                          IFormatter formatter, String filePath, ExportCallback callback) {
        // 本次导出的格式化器快照：null 回退到构造默认（构造已保证非 null）
        final IFormatter fmt = formatter != null ? formatter : this.formatter;
        if (query == null) {
            query = LogQuery.builder().build();
        }
        if (format == null) {
            format = ExportFormat.TXT;
        }
        if (encoding == null) {
            encoding = ExportEncoding.UTF_8;
        }
        final LogQuery finalQuery = query;
        final ExportFormat finalFormat = format;
        return runExport(
                () -> storage.openExportSnapshot(finalQuery),
                LogRecord.class,
                (record, f) -> fmt.format(record, f),
                bw -> writeLogHeader(bw, finalFormat, fmt),
                (bw, isFirst) -> writeLogFooter(bw, finalFormat, isFirst),
                finalFormat, encoding, filePath, callback);
    }

    /**
     * 导出异常到文件（使用构造时指定的格式化器）
     * @param query 查询条件（分页参数会被覆盖）
     * @param format 导出格式
     * @param encoding 导出编码
     * @param filePath 目标文件路径
     * @param callback 回调；可为 null
     * @return 导出总条数
     */
    public int exportExceptions(ExceptionQuery query, ExportFormat format,
                                ExportEncoding encoding, String filePath,
                                ExportCallback callback) {
        return exportExceptions(query, format, encoding, formatter, filePath, callback);
    }

    /**
     * 导出异常到文件（使用本次调用指定的格式化器）
     * <p>formatter 在导出开始时捕获，本次导出全程使用同一实例（ISSUE-08）</p>
     * @param query 查询条件（分页参数会被覆盖）
     * @param format 导出格式
     * @param encoding 导出编码
     * @param formatter 本次导出使用的格式化器；为 null 时回退到构造时指定的格式化器
     * @param filePath 目标文件路径
     * @param callback 回调；可为 null
     * @return 导出总条数
     */
    public int exportExceptions(ExceptionQuery query, ExportFormat format,
                                ExportEncoding encoding, IFormatter formatter,
                                String filePath, ExportCallback callback) {
        // 本次导出的格式化器快照：null 回退到构造默认（构造已保证非 null）
        final IFormatter fmt = formatter != null ? formatter : this.formatter;
        if (query == null) {
            query = ExceptionQuery.builder().build();
        }
        if (format == null) {
            format = ExportFormat.TXT;
        }
        if (encoding == null) {
            encoding = ExportEncoding.UTF_8;
        }
        final ExceptionQuery finalQuery = query;
        final ExportFormat finalFormat = format;
        return runExport(
                () -> exceptionStorage.openExportSnapshot(finalQuery),
                ExceptionRecord.class,
                (record, f) -> fmt.format(record, f),
                bw -> writeExceptionHeader(bw, finalFormat, fmt),
                (bw, isFirst) -> writeExceptionFooter(bw, finalFormat, isFirst),
                finalFormat, encoding, filePath, callback);
    }

    // ===== 通用导出管线 =====

    /** 打开快照的函数（抛出的异常统一按导出失败处理） */
    private interface SnapshotOpener<T> {
        IExportSnapshot<T> open();
    }

    /** 单条记录格式化函数 */
    private interface RecordFormatter<T> {
        String format(T record, ExportFormat format);
    }

    /** 文件头写入函数 */
    private interface HeaderWriter {
        void write(BufferedWriter bw) throws IOException;
    }

    /** 文件尾写入函数 */
    private interface FooterWriter {
        void write(BufferedWriter bw, boolean isFirst) throws IOException;
    }

    /**
     * 通用导出管线：快照复制 → 临时输出 → 发布
     *
     * @param opener 快照打开函数
     * @param recordType 快照记录类型（用于 JSON 反序列化）
     * @param recordFormatter 单条格式化函数
     * @param header 文件头
     * @param footer 文件尾
     * @param format 导出格式
     * @param encoding 输出编码
     * @param filePath 目标路径
     * @param callback 回调；可为 null
     * @return 成功导出的条数；失败返回 0
     */
    private <T> int runExport(SnapshotOpener<T> opener,
                              Class<T> recordType,
                              RecordFormatter<T> recordFormatter,
                              HeaderWriter header,
                              FooterWriter footer,
                              ExportFormat format,
                              ExportEncoding encoding,
                              String filePath,
                              ExportCallback callback) {
        // 1. 打开快照：捕获边界与匹配数量（不支持快照的存储在此明确失败）
        final IExportSnapshot<T> snapshot;
        try {
            snapshot = opener.open();
        } catch (Throwable t) {
            return fail(callback, t, 0);
        }
        final long capturedCount = snapshot.getCapturedCount();

        // 2. 复制阶段（持有读取保护）：匹配记录 → 私有原始快照文件
        File snapshotFile = null;
        try {
            checkInterrupted();
            snapshotFile = File.createTempFile("log-record-snapshot-", ".json");
            copyToSnapshotFile(snapshot, snapshotFile);
        } catch (Throwable t) {
            closeQuietly(snapshot);
            deleteQuietly(snapshotFile);
            return fail(callback, t, 0);
        } finally {
            // 复制完成即释放读取保护：格式化与回调在保护范围外执行
            snapshot.close();
        }

        // 3. 输出阶段（无读取保护）：快照文件 → 目标目录内本次专属临时文件
        final AtomicLong written = new AtomicLong(0);
        File outputTemp = null;
        try {
            checkInterrupted();
            outputTemp = createOutputTempFile(filePath);
            writeOutputFile(snapshotFile, outputTemp, recordType, recordFormatter,
                    header, footer, format, encoding, capturedCount, written, callback);
        } catch (Throwable t) {
            deleteQuietly(outputTemp);
            deleteQuietly(snapshotFile);
            return fail(callback, t, safeInt(written.get()));
        }

        // 4. 发布：完整关闭后替换目标；失败保留原目标并清理本次临时文件
        try {
            publish(outputTemp, new File(filePath));
        } catch (Throwable t) {
            deleteQuietly(outputTemp);
            deleteQuietly(snapshotFile);
            return fail(callback, t, safeInt(written.get()));
        }
        deleteQuietly(snapshotFile);
        notifySuccess(callback, filePath, safeInt(written.get()));
        return safeInt(written.get());
    }

    /**
     * 把快照内容分批复制到私有原始快照文件（JSON 行，UTF-8）
     */
    private <T> void copyToSnapshotFile(IExportSnapshot<T> snapshot, File snapshotFile)
            throws IOException {
        try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(snapshotFile), StandardCharsets.UTF_8))) {
            while (!snapshot.isExhausted()) {
                checkInterrupted();
                List<T> batch = snapshot.nextBatch(PAGE_SIZE);
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                for (T record : batch) {
                    bw.write(JsonUtil.toJson(record));
                    bw.write('\n');
                }
            }
            bw.flush();
        }
    }

    /**
     * 从原始快照文件读取记录，格式化后写入输出临时文件
     * <p>单条格式化失败即抛出并终止整个导出（不再跳过后报告成功）</p>
     */
    private <T> void writeOutputFile(File snapshotFile, File outputTemp,
                                     Class<T> recordType,
                                     RecordFormatter<T> recordFormatter,
                                     HeaderWriter header, FooterWriter footer,
                                     ExportFormat format, ExportEncoding encoding,
                                     long capturedCount,
                                     AtomicLong written, ExportCallback callback)
            throws IOException {
        int totalInt = safeInt(capturedCount);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                new FileInputStream(snapshotFile), StandardCharsets.UTF_8));
             BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(
                     new FileOutputStream(outputTemp), encoding.getCharset()))) {
            header.write(bw);
            boolean isFirst = true;
            String line;
            while ((line = br.readLine()) != null) {
                checkInterrupted();
                T record = JsonUtil.fromJson(line, recordType);
                if (record == null) {
                    throw new IOException("snapshot file corrupted at line: "
                            + line.substring(0, Math.min(64, line.length())));
                }
                String out = recordFormatter.format(record, format);
                writeRecordLine(bw, out, format, isFirst);
                isFirst = false;
                long n = written.incrementAndGet();
                if (n % PAGE_SIZE == 0) {
                    notifyProgress(callback, safeInt(n), totalInt);
                }
            }
            footer.write(bw, isFirst);
            bw.flush();
        }
        notifyProgress(callback, safeInt(written.get()), totalInt);
    }

    /**
     * 创建目标目录内的本次专属输出临时文件（不提前创建目标文件本身）
     */
    private File createOutputTempFile(String filePath) throws IOException {
        File target = new File(filePath).getAbsoluteFile();
        File parent = target.getParentFile();
        if (parent == null) {
            parent = target.isDirectory() ? target : new File(".").getAbsoluteFile();
        }
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create export target directory: " + parent);
        }
        File temp = new File(parent, target.getName()
                + ".log-record-export-" + Long.toHexString(System.nanoTime()) + ".tmp");
        if (!temp.createNewFile() && !temp.exists()) {
            throw new IOException("cannot create export temp file: " + temp);
        }
        return temp;
    }

    /**
     * 发布输出文件到目标路径
     * <p>
     * 先尝试 rename 原子替换（POSIX/Linux/Android 上可覆盖已存在目标）；
     * 失败时（如 Windows 目标已存在）经反射调用 java.nio Files.move 的
     * REPLACE_EXISTING 路径——反射隔离保证 Android API 21 不加载该类；
     * 均失败则保留原目标并抛出，不采用"先删除旧文件再移动"。
     * </p>
     */
    private void publish(File source, File target) throws IOException {
        if (!source.renameTo(target)) {
            nioMoveReflective(source, target);
        }
    }

    /**
     * 反射调用 NioFileMove（隔离 java.nio.file 引用，避免 Android API 21 加载失败）
     */
    private static void nioMoveReflective(File source, File target) throws IOException {
        try {
            Class<?> mover = Class.forName(
                    "com.atian10.logrecord.core.export.NioFileMove");
            mover.getMethod("move", File.class, File.class).invoke(null, source, target);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause instanceof IOException ? (IOException) cause
                    : new IOException("publish failed", cause);
        } catch (ReflectiveOperationException e) {
            throw new IOException("atomic replace unavailable on this platform", e);
        }
    }

    /**
     * 导出失败统一出口：清理已由调用方完成，触发一次 onFailure 并返回 0
     */
    private int fail(ExportCallback callback, Throwable error, int exportedBeforeFailure) {
        if (callback != null) {
            try {
                callback.onFailure(error, exportedBeforeFailure);
            } catch (Throwable t) {
                recordCallbackError("onFailure", t);
            }
        }
        return 0;
    }

    /**
     * 成功通知（仅在发布完成后触发）
     */
    private void notifySuccess(ExportCallback callback, String filePath, int count) {
        if (callback != null) {
            try {
                callback.onSuccess(filePath, count);
            } catch (Throwable t) {
                recordCallbackError("onSuccess", t);
            }
        }
    }

    /**
     * 进度通知（异常被捕获并单独记录，不影响导出）
     */
    private void notifyProgress(ExportCallback callback, int exported, int total) {
        if (callback != null) {
            try {
                callback.onProgress(exported, total);
            } catch (Throwable t) {
                recordCallbackError("onProgress", t);
            }
        }
    }

    /**
     * 记录回调自身抛出的异常描述（不触发任何相反终态）
     */
    private void recordCallbackError(String where, Throwable t) {
        lastCallbackError = where + " callback failed: "
                + t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    /**
     * 线程中断视为取消：抛出异常走统一失败清理
     */
    private static void checkInterrupted() throws IOException {
        if (Thread.interrupted()) {
            throw new IOException("export cancelled by thread interrupt");
        }
    }

    /**
     * long 收敛到 int（超上限按 Integer.MAX_VALUE）
     */
    private static int safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private void closeQuietly(IExportSnapshot<?> snapshot) {
        try {
            snapshot.close();
        } catch (Throwable ignored) {
            // 关闭失败不影响失败主流程
        }
    }

    private void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    // ===== 输出头尾与行格式 =====

    /**
     * 写日志文件头（CSV 表头使用本次导出的格式化器）
     */
    private void writeLogHeader(BufferedWriter bw, ExportFormat format, IFormatter fmt)
            throws IOException {
        if (format == ExportFormat.CSV && fmt instanceof DefaultFormatter) {
            bw.write(((DefaultFormatter) fmt).getLogCsvHeader());
            bw.write('\n');
        } else if (format == ExportFormat.JSON) {
            bw.write("[");
        }
    }

    private void writeLogFooter(BufferedWriter bw, ExportFormat format, boolean isFirst) throws IOException {
        if (format == ExportFormat.JSON) {
            if (!isFirst) {
                bw.write('\n');
            }
            bw.write("]");
            bw.write('\n');
        }
    }

    /**
     * 写异常文件头（CSV 表头使用本次导出的格式化器）
     */
    private void writeExceptionHeader(BufferedWriter bw, ExportFormat format, IFormatter fmt)
            throws IOException {
        if (format == ExportFormat.CSV && fmt instanceof DefaultFormatter) {
            bw.write(((DefaultFormatter) fmt).getExceptionCsvHeader());
            bw.write('\n');
        } else if (format == ExportFormat.JSON) {
            bw.write("[");
        }
    }

    private void writeExceptionFooter(BufferedWriter bw, ExportFormat format, boolean isFirst) throws IOException {
        if (format == ExportFormat.JSON) {
            if (!isFirst) {
                bw.write('\n');
            }
            bw.write("]");
            bw.write('\n');
        }
    }

    /**
     * 写入单条记录行，按格式处理分隔符：
     * <ul>
     *   <li>TXT/CSV：直接写记录 + 换行</li>
     *   <li>JSON：首条前写换行，非首条前写逗号+换行（保证数组元素合法分隔）</li>
     * </ul>
     */
    private void writeRecordLine(BufferedWriter bw, String line, ExportFormat format,
                                 boolean isFirst) throws IOException {
        if (format == ExportFormat.JSON) {
            if (!isFirst) {
                bw.write(',');
            }
            bw.write('\n');
            bw.write(line);
        } else {
            bw.write(line);
            bw.write('\n');
        }
    }
}
