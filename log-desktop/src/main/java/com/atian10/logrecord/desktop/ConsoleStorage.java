package com.atian10.logrecord.desktop;

import com.atian10.logrecord.core.IStorage;
import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.query.LogQuery;
import com.atian10.logrecord.core.query.LogStatistics;
import com.atian10.logrecord.core.util.TimeUtil;

import java.io.PrintStream;
import java.util.List;

/**
 * 控制台输出装饰器（桌面/服务器）
 * <p>
 * 实现 {@link IStorage}，作为 {@link JdbcStorage} 的装饰器。
 * 写入时先输出到 {@link System#out} / {@link System#err}，再转发给 delegate 持久化。
 * 级别映射：DEBUG/INFO → stdout，WARN/ERROR/FATAL → stderr。
 * </p>
 * <p>
 * 使用方式：{@code new ConsoleStorage(new JdbcStorage(helper), consoleEnabled)}
 * DesktopLogInit 根据 LogConfig.consoleEnabled 决定是否启用控制台输出，
 * 同时将 LogManager 自身的 consoleEnabled 置为 false，避免装饰器与 LogManager 重复输出。
 * </p>
 */
public final class ConsoleStorage implements IStorage {

    private final IStorage delegate;
    private final boolean enable;
    private final PrintStream out;
    private final PrintStream err;

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
     * 输出单条日志到控制台
     */
    private void printConsole(LogRecord r) {
        String line = formatLine(r);
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
    private String formatLine(LogRecord r) {
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
