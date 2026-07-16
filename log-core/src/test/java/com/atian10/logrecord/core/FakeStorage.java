package com.atian10.logrecord.core;

import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.query.LogQuery;
import com.atian10.logrecord.core.query.LogStatistics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存版 IStorage，用于单元测试。
 * <p>
 * 不实际访问数据库，所有数据存于内存 List。
 * 提供 getWrittenRecords() 助手方法供测试断言。
 * </p>
 */
public class FakeStorage implements IStorage {

    private final List<LogRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void write(LogRecord record) {
        if (record != null) {
            records.add(record);
        }
    }

    @Override
    public void writeBatch(List<LogRecord> records) {
        if (records != null) {
            this.records.addAll(records);
        }
    }

    @Override
    public List<LogRecord> query(LogQuery query) {
        List<LogRecord> result = new ArrayList<>();
        for (LogRecord r : records) {
            if (matches(r, query)) {
                result.add(r);
            }
        }
        return result;
    }

    @Override
    public LogStatistics statistics(LogQuery query) {
        List<LogRecord> matched = query(query);
        Map<LogLevel, Long> byLevel = new HashMap<>();
        Map<String, Long> byType = new HashMap<>();
        Map<String, Long> byTag = new HashMap<>();
        for (LogRecord r : matched) {
            if (r.getLevel() != null) {
                byLevel.merge(r.getLevel(), 1L, Long::sum);
            }
            if (r.getType() != null) {
                byType.merge(r.getType(), 1L, Long::sum);
            }
            if (r.getTag() != null) {
                byTag.merge(r.getTag(), 1L, Long::sum);
            }
        }
        return new LogStatistics(matched.size(), byLevel, byType, byTag);
    }

    @Override
    public int clean(CleanPolicy policy) {
        int size = records.size();
        records.clear();
        return size;
    }

    @Override
    public int cleanBefore(long timestamp) {
        int before = records.size();
        records.removeIf(r -> r.getTimestamp() < timestamp);
        return before - records.size();
    }

    @Override
    public int cleanByCount(int keepCount) {
        int before = records.size();
        if (keepCount >= before) {
            return 0;
        }
        // 保留最后 keepCount 条
        List<LogRecord> kept = new ArrayList<>(
                records.subList(before - keepCount, before));
        records.clear();
        records.addAll(kept);
        return before - keepCount;
    }

    @Override
    public long getRecordCount() {
        return records.size();
    }

    @Override
    public long getDbSizeBytes() {
        return records.size() * 100L;
    }

    @Override
    public long count(LogQuery query) {
        return query(query).size();
    }

    public List<LogRecord> getWrittenRecords() {
        return new ArrayList<>(records);
    }

    public void clear() {
        records.clear();
    }

    private boolean matches(LogRecord r, LogQuery q) {
        if (q == null) {
            return true;
        }
        if (q.getLevel() != null && q.getLevel() != r.getLevel()) {
            return false;
        }
        if (q.getType() != null && !q.getType().equals(r.getType())) {
            return false;
        }
        if (q.getTag() != null && !q.getTag().equals(r.getTag())) {
            return false;
        }
        if (q.getKeyword() != null && r.getMessage() != null
                && !r.getMessage().contains(q.getKeyword())) {
            return false;
        }
        if (q.getFromTime() != null && r.getTimestamp() < q.getFromTime()) {
            return false;
        }
        if (q.getToTime() != null && r.getTimestamp() > q.getToTime()) {
            return false;
        }
        return true;
    }
}
