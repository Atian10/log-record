package com.atian10.logrecord.android;

import android.util.Log;

import com.atian10.logrecord.core.IExportSnapshot;
import com.atian10.logrecord.core.IStorage;
import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.query.LogQuery;
import com.atian10.logrecord.core.query.LogStatistics;

import java.util.List;

/**
 * Logcat 控制台输出装饰器
 * <p>
 * 实现 {@link IStorage}，作为 {@link RoomStorage} 的装饰器。
 * 写入时先输出到 Logcat，再转发给 delegate 持久化。
 * 其他方法（查询/统计/清理/容量）直接转发 delegate。
 * </p>
 * <p>
 * 使用方式：{@code new LogcatStorage(new RoomStorage(db), consoleEnabled)}
 * AndroidLogInit 根据 LogConfig.consoleEnabled 决定是否启用 Logcat 输出，
 * 同时将 LogManager 自身的 consoleEnabled 置为 false，避免 System.out 重复输出。
 * </p>
 */
public final class LogcatStorage implements IStorage {

    private static final String DEFAULT_TAG = "LogRecord";

    private final IStorage delegate;
    private final boolean enable;

    /**
     * 构造 Logcat 装饰器
     * @param delegate 被装饰的真实存储
     * @param enable 是否启用 Logcat 输出（false 时纯转发不输出）
     */
    public LogcatStorage(IStorage delegate, boolean enable) {
        if (delegate == null) {
            throw new NullPointerException("delegate == null");
        }
        this.delegate = delegate;
        this.enable = enable;
    }

    @Override
    public void write(LogRecord record) {
        if (enable && record != null) {
            printLogcat(record);
        }
        delegate.write(record);
    }

    @Override
    public void writeBatch(List<LogRecord> records) {
        if (enable && records != null) {
            for (LogRecord r : records) {
                if (r != null) {
                    printLogcat(r);
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
     * 输出单条日志到 Logcat
     * <p>级别映射：DEBUG→d, INFO→i, WARN→w, ERROR→e, FATAL→e</p>
     */
    private void printLogcat(LogRecord record) {
        String tag = record.getTag() == null ? DEFAULT_TAG : record.getTag();
        String msg = formatMessage(record);
        LogLevel level = record.getLevel();
        if (level == null) {
            Log.d(tag, msg);
            return;
        }
        switch (level) {
            case DEBUG:
                Log.d(tag, msg);
                break;
            case INFO:
                Log.i(tag, msg);
                break;
            case WARN:
                Log.w(tag, msg);
                break;
            case ERROR:
                Log.e(tag, msg);
                break;
            case FATAL:
                // Logcat 无 FATAL 级别，用 e 输出并加前缀
                Log.e(tag, "[FATAL] " + msg);
                break;
            default:
                Log.d(tag, msg);
                break;
        }
    }

    /**
     * 格式化 Logcat 消息（含类型/线程/方法信息）
     */
    private String formatMessage(LogRecord r) {
        StringBuilder sb = new StringBuilder(64);
        sb.append('[').append(r.getType() == null ? "" : r.getType()).append(']');
        sb.append(' ').append(r.getMessage() == null ? "" : r.getMessage());
        if (r.getMethodName() != null) {
            sb.append(" (").append(r.getMethodName()).append(':').append(r.getLineNumber()).append(')');
        }
        if (r.getVersionTag() != null) {
            sb.append(" <v=").append(r.getVersionTag()).append('>');
        }
        return sb.toString();
    }
}
