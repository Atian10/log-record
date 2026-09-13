package com.atian10.logrecord.desktop;

import com.atian10.logrecord.core.IConsoleOutput;
import com.atian10.logrecord.core.IExportSnapshot;
import com.atian10.logrecord.core.IFormatter;
import com.atian10.logrecord.core.IStorage;
import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.export.ExportFormat;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.query.LogQuery;
import com.atian10.logrecord.core.query.LogStatistics;
import com.atian10.logrecord.core.util.TimeUtil;

import java.io.PrintStream;
import java.util.List;

/**
 * 控制台输出策略工厂及兼容存储装饰器（桌面/服务器）
 * <p>
 * 实现 {@link IStorage}，作为 {@link JdbcStorage} 的装饰器。
 * 写入时先输出到 {@link System#out} / {@link System#err}，再转发给 delegate 持久化。
 * 级别映射：DEBUG/INFO → stdout，WARN/ERROR/FATAL → stderr。
 * </p>
 * <p>
 * 使用方式：{@code new ConsoleStorage(new JdbcStorage(helper), consoleEnabled)}
 * 此构造器的开关在创建后固定。DesktopLogInit 使用 {@link #consoleOutput()} 提供独立策略，
 * 由 LogManager 统一处理动态开关，默认初始化不再装饰存储。
 * 显式使用装饰器时，它是额外输出通道，不应同时开启核心输出以免重复打印。
 * </p>
 */
public final class ConsoleStorage implements IStorage {

    private final IStorage delegate;
    private final boolean enable;
    private final PrintStream out;
    private final PrintStream err;

    /**
     * 创建使用当前 System.out / System.err 的独立控制台输出策略。
     * <p>不持有存储，不创建或关闭输出流；动态开关由 LogManager 统一控制。</p>
     * @return 不拥有存储或关闭资源的输出策略
     */
    public static IConsoleOutput consoleOutput() {
        return consoleOutput(System.out, System.err);
    }

    /**
     * 创建使用指定输出流的独立策略，不取得流的关闭权。
     * <p>在调用线程使用同次配置的 formatter：非 null 时使用 TXT 格式，null 时保留默认文本。
     * 普通记录保留级别路由，异常记录仅输出到 err；不缓存开关、不提交记录、不捕获输出异常。</p>
     * @param out 正常级别输出流，null 时使用当前 System.out
     * @param err 错误级别输出流，null 时使用当前 System.err
     * @return 可被并发调用的策略；自定义 formatter 和输出流应自行保证线程安全
     */
    public static IConsoleOutput consoleOutput(PrintStream out, PrintStream err) {
        final PrintStream normalOutput = out == null ? System.out : out;
        final PrintStream errorOutput = err == null ? System.err : err;
        return new IConsoleOutput() {
            @Override
            public void print(LogRecord record, IFormatter formatter) {
                String line = formatter == null
                        ? formatLine(record) : formatter.format(record, ExportFormat.TXT);
                printConsole(record, line, normalOutput, errorOutput);
            }

            @Override
            public void print(ExceptionRecord record, IFormatter formatter) {
                String line = formatter == null
                        ? "[EXCEPTION] " + record.getLogTag() + " " + record.getExceptionClass()
                                + ": " + record.getExceptionMessage() + "\n" + record.getStackTrace()
                        : formatter.format(record, ExportFormat.TXT);
                errorOutput.println(line);
            }
        };
    }

    /**
     * 构造 Console 装饰器（使用 System.out / System.err）
     * @param delegate 被装饰的真实存储
     * @param enable 是否启用控制台输出
     */
    public ConsoleStorage(IStorage delegate, boolean enable) {
        this(delegate, enable, System.out, System.err);
    }

    /**
     * 构造 Console 装饰器（自定义输出流，便于测试）
     * @param delegate 被装饰的真实存储
     * @param enable 是否启用控制台输出
     * @param out 正常级别输出流
     * @param err 错误级别输出流
     */
    public ConsoleStorage(IStorage delegate, boolean enable, PrintStream out, PrintStream err) {
        if (delegate == null) {
            throw new NullPointerException("delegate == null");
        }
        if (out == null) {
            out = System.out;
        }
        if (err == null) {
            err = System.err;
        }
        this.delegate = delegate;
        this.enable = enable;
        this.out = out;
        this.err = err;
    }

    @Override
    public void write(LogRecord record) {
        if (enable && record != null) {
            printConsole(record);
        }
        delegate.write(record);
    }

    @Override
    public void writeBatch(List<LogRecord> records) {
        if (enable && records != null) {
            for (LogRecord r : records) {
                if (r != null) {
                    printConsole(r);
                }
            }
        }
        delegate.writeBatch(records);
    }

    @Override
    public List<LogRecord> query(LogQuery query) {
        return delegate.query(query);
    }

    @Override
    public LogStatistics statistics(LogQuery query) {
        return delegate.statistics(query);
    }

    @Override
    public int clean(CleanPolicy policy) {
        return delegate.clean(policy);
    }

    @Override
    public int cleanBefore(long timestamp) {
        return delegate.cleanBefore(timestamp);
    }

    @Override
    public int cleanByCount(int keepCount) {
        return delegate.cleanByCount(keepCount);
    }

    @Override
    public long getRecordCount() {
        return delegate.getRecordCount();
    }

    @Override
    public long getDbSizeBytes() {
        return delegate.getDbSizeBytes();
    }

    @Override
    public long count(LogQuery query) {
        return delegate.count(query);
    }

    /**
     * 转发导出快照能力到被装饰存储（包装层不引入额外一致性语义）
     */
    @Override
    public IExportSnapshot<LogRecord> openExportSnapshot(LogQuery query) {
        return delegate.openExportSnapshot(query);
    }

    /**
     * 输出单条日志到控制台
     */
    private void printConsole(LogRecord r) {
        printConsole(r, formatLine(r), out, err);
    }

    /** 独立策略和旧装饰器共享同一级别到标准流的映射。 */
    private static void printConsole(LogRecord r, String line, PrintStream out, PrintStream err) {
        LogLevel level = r.getLevel();
        if (level == LogLevel.WARN || level == LogLevel.ERROR || level == LogLevel.FATAL) {
            err.println(line);
        } else {
            out.println(line);
        }
    }

    /**
     * 格式化控制台行
     */
    private static String formatLine(LogRecord r) {
        StringBuilder sb = new StringBuilder(64);
        sb.append('[').append(TimeUtil.formatUtc(r.getTimestamp())).append(']');
        sb.append(' ').append(r.getLevel() == null ? "?" : r.getLevel().name());
        sb.append('/').append(r.getType() == null ? "" : r.getType());
        sb.append('[').append(r.getTag() == null ? "" : r.getTag()).append(']');
        sb.append('[').append(r.getThreadName() == null ? "" : r.getThreadName())
                .append(':').append(r.getThreadId()).append(']');
        if (r.getMethodName() != null) {
            sb.append(" (").append(r.getMethodName()).append(':').append(r.getLineNumber()).append(')');
        }
        if (r.getVersionTag() != null) {
            sb.append(" <v=").append(r.getVersionTag()).append('>');
        }
        sb.append(": ").append(r.getMessage() == null ? "" : r.getMessage());
        if (r.getUserFields() != null && !r.getUserFields().isEmpty()) {
            sb.append(' ').append(r.getUserFields());
        }
        return sb.toString();
    }
}
