package com.atian10.logrecord.core.export;

import com.atian10.logrecord.core.IExceptionStorage;
import com.atian10.logrecord.core.IFormatter;
import com.atian10.logrecord.core.IStorage;
import com.atian10.logrecord.core.formatter.DefaultFormatter;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.query.ExceptionQuery;
import com.atian10.logrecord.core.query.LogQuery;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;

/**
 * 导出器
 * <p>
 * 将日志/异常按指定格式导出到文件。采用分页查询（每页 {@link #PAGE_SIZE} 条）避免 OOM。
 * 支持 TXT/JSON/CSV 三种格式和多种编码。
 * 通过 {@link ExportCallback} 通知进度与结果。
 * </p>
 */
public final class Exporter {

    /** 分页大小，避免一次性加载过多数据导致 OOM */
    public static final int PAGE_SIZE = 1000;

    private final IStorage storage;
    private final IExceptionStorage exceptionStorage;
    private final IFormatter formatter;

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
     * 导出日志到文件
     * @param query 查询条件（分页参数会被覆盖）
     * @param format 导出格式
     * @param encoding 导出编码
     * @param filePath 目标文件路径
     * @param callback 回调；可为 null
     * @return 导出总条数
     */
    public int exportLogs(LogQuery query, ExportFormat format, ExportEncoding encoding,
                          String filePath, ExportCallback callback) {
        if (query == null) {
            query = LogQuery.builder().build();
        }
        if (format == null) {
            format = ExportFormat.TXT;
        }
        if (encoding == null) {
            encoding = ExportEncoding.UTF_8;
        }
        long total = storage.count(query);
        int totalInt = total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
        int exported = 0;

        FileOutputStream fos = null;
        OutputStreamWriter osw = null;
        BufferedWriter bw = null;
        try {
            File file = ensureFile(filePath);
            fos = new FileOutputStream(file, false);
            osw = new OutputStreamWriter(fos, encoding.getCharset());
            bw = new BufferedWriter(osw);

            writeLogHeader(bw, format);
            boolean isFirst = true;

            int offset = 0;
            while (exported < totalInt || (totalInt == 0 && offset == 0)) {
                LogQuery.Builder builder = LogQuery.builder()
                        .level(query.getLevel())
                        .type(query.getType())
                        .tag(query.getTag())
                        .keyword(query.getKeyword())
                        .fromTime(query.getFromTime())
                        .toTime(query.getToTime())
                        .versionTag(query.getVersionTag())
                        .orderBy(query.getOrderBy())
                        .offset(offset)
                        .limit(PAGE_SIZE);
                // 复制 userFields 查询条件
                if (query.getUserFields() != null) {
                    for (java.util.Map.Entry<String, String> e : query.getUserFields().entrySet()) {
                        builder.userField(e.getKey(), e.getValue());
                    }
                }
                LogQuery pageQuery = builder.build();
                List<LogRecord> page = storage.query(pageQuery);
                if (page == null || page.isEmpty()) {
                    break;
                }
                for (LogRecord record : page) {
                    String line = formatter.format(record, format);
                    writeRecordLine(bw, line, format, isFirst);
                    isFirst = false;
                    exported++;
                }
                if (callback != null) {
                    callback.onProgress(exported, totalInt);
                }
                if (page.size() < PAGE_SIZE) {
                    break;
                }
                offset += PAGE_SIZE;
            }

            writeLogFooter(bw, format, isFirst);
            bw.flush();
            if (callback != null) {
                callback.onSuccess(filePath, exported);
            }
            return exported;
        } catch (Throwable t) {
            if (callback != null) {
                callback.onFailure(t, exported);
            }
            return exported;
        } finally {
            closeQuietly(bw);
            closeQuietly(osw);
            closeQuietly(fos);
        }
    }

    /**
     * 导出异常到文件
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
        if (query == null) {
            query = ExceptionQuery.builder().build();
        }
        if (format == null) {
            format = ExportFormat.TXT;
        }
        if (encoding == null) {
            encoding = ExportEncoding.UTF_8;
        }
        long total = exceptionStorage.count(query);
        int totalInt = total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
        int exported = 0;

        FileOutputStream fos = null;
        OutputStreamWriter osw = null;
        BufferedWriter bw = null;
        try {
            File file = ensureFile(filePath);
            fos = new FileOutputStream(file, false);
            osw = new OutputStreamWriter(fos, encoding.getCharset());
            bw = new BufferedWriter(osw);

            writeExceptionHeader(bw, format);
            boolean isFirst = true;

            int offset = 0;
            while (exported < totalInt || (totalInt == 0 && offset == 0)) {
                ExceptionQuery pageQuery = ExceptionQuery.builder()
                        .tag(query.getTag())
                        .exceptionClass(query.getExceptionClass())
                        .keyword(query.getKeyword())
                        .fromTime(query.getFromTime())
                        .toTime(query.getToTime())
                        .orderBy(query.getOrderBy())
                        .offset(offset)
                        .limit(PAGE_SIZE)
                        .build();
                List<ExceptionRecord> page = exceptionStorage.query(pageQuery);
                if (page == null || page.isEmpty()) {
                    break;
                }
                for (ExceptionRecord record : page) {
                    String line = formatter.format(record, format);
                    writeRecordLine(bw, line, format, isFirst);
                    isFirst = false;
                    exported++;
                }
                if (callback != null) {
                    callback.onProgress(exported, totalInt);
                }
                if (page.size() < PAGE_SIZE) {
                    break;
                }
                offset += PAGE_SIZE;
            }

            writeExceptionFooter(bw, format, isFirst);
            bw.flush();
            if (callback != null) {
                callback.onSuccess(filePath, exported);
            }
            return exported;
        } catch (Throwable t) {
            if (callback != null) {
                callback.onFailure(t, exported);
            }
            return exported;
        } finally {
            closeQuietly(bw);
            closeQuietly(osw);
            closeQuietly(fos);
        }
    }

    // ===== 内部方法 =====

    private File ensureFile(String filePath) throws IOException {
        File file = new File(filePath);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        if (!file.exists()) {
            file.createNewFile();
        }
        return file;
    }

    private void writeLogHeader(BufferedWriter bw, ExportFormat format) throws IOException {
        if (format == ExportFormat.CSV && formatter instanceof DefaultFormatter) {
            bw.write(((DefaultFormatter) formatter).getLogCsvHeader());
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

    private void writeExceptionHeader(BufferedWriter bw, ExportFormat format) throws IOException {
        if (format == ExportFormat.CSV && formatter instanceof DefaultFormatter) {
            bw.write(((DefaultFormatter) formatter).getExceptionCsvHeader());
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

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
    }
}
