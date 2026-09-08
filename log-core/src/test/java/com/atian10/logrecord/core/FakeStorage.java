package com.atian10.logrecord.core;

import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.query.LogQuery;
import com.atian10.logrecord.core.query.LogStatistics;
import com.atian10.logrecord.core.query.OrderBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存版 IStorage，用于单元测试。
 * <p>
 * 不实际访问数据库，所有数据存于内存 List。
 * 提供 getWrittenRecords() 助手方法供测试断言；
 * 实现导出快照（打开时冻结匹配集合），供导出链路测试使用。
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

    /**
     * 打开导出快照：打开时冻结匹配集合（内存实现，按 (timestamp, 到达序) 稳定排序）
     */
    @Override
    public IExportSnapshot<LogRecord> openExportSnapshot(LogQuery query) {
        final LogQuery q = query != null ? query : LogQuery.builder().build();
        List<LogRecord> matched = new ArrayList<>(query(q));
        // 稳定次序：时间戳为主键，写入到达序（索引）为次键，与真实存储的 (timestamp, id) 对应
        final Map<LogRecord, Integer> arrivalIndex = new HashMap<>();
        List<LogRecord> snapshotAll = getWrittenRecords();
        for (int i = 0; i < snapshotAll.size(); i++) {
            arrivalIndex.put(snapshotAll.get(i), i);
        }
        final boolean descending = q.getOrderBy() == OrderBy.DESC;
        Comparator<LogRecord> comparator = Comparator
                .comparingLong(LogRecord::getTimestamp)
                .thenComparing(r -> arrivalIndex.getOrDefault(r, 0));
        if (descending) {
            comparator = comparator.reversed();
        }
        Collections.sort(matched, comparator);
        final List<LogRecord> frozen = Collections.unmodifiableList(matched);
        return new IExportSnapshot<LogRecord>() {
            /** 已读取的偏移（冻结集合上偏移分页即一致） */
            private int offset = 0;
            private boolean exhausted = frozen.isEmpty();

            @Override
            public long getCapturedCount() {
                return frozen.size();
            }

            @Override
            public boolean isExhausted() {
                return exhausted;
            }

            @Override
            public List<LogRecord> nextBatch(int maxRows) {
                if (exhausted || maxRows <= 0) {
                    exhausted = true;
                    return new ArrayList<>();
                }
                int end = Math.min(frozen.size(), offset + maxRows);
                List<LogRecord> page = new ArrayList<>(frozen.subList(offset, end));
                offset = end;
                if (offset >= frozen.size()) {
                    exhausted = true;
                }
                return page;
            }

            @Override
            public void close() {
                exhausted = true;
            }
        };
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
