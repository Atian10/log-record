package com.atian10.logrecord.core;

import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.query.ExceptionQuery;
import com.atian10.logrecord.core.query.OrderBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存版 IExceptionStorage，用于单元测试。
 * <p>
 * 不实际访问数据库，所有数据存于内存 List。
 * 实现导出快照（打开时冻结匹配集合），供导出链路测试使用。
 * </p>
 */
public class FakeExceptionStorage implements IExceptionStorage {

    private final List<ExceptionRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public void write(ExceptionRecord record) {
        if (record != null) {
            records.add(record);
        }
    }

    @Override
    public void writeBatch(List<ExceptionRecord> records) {
        if (records != null) {
            this.records.addAll(records);
        }
    }

    @Override
    public List<ExceptionRecord> query(ExceptionQuery query) {
        List<ExceptionRecord> result = new ArrayList<>();
        for (ExceptionRecord r : records) {
            if (matches(r, query)) {
                result.add(r);
            }
        }
        return result;
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
        List<ExceptionRecord> kept = new ArrayList<>(
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
        return records.size() * 200L;
    }

    @Override
    public long count(ExceptionQuery query) {
        return query(query).size();
    }

    /**
     * 打开导出快照：打开时冻结匹配集合（内存实现，按 (timestamp, 到达序) 稳定排序）
     */
    @Override
    public IExportSnapshot<ExceptionRecord> openExportSnapshot(ExceptionQuery query) {
        final ExceptionQuery q = query != null ? query : ExceptionQuery.builder().build();
        List<ExceptionRecord> matched = new ArrayList<>(query(q));
        // 稳定次序：时间戳为主键，写入到达序（索引）为次键，与真实存储的 (timestamp, id) 对应
        final Map<ExceptionRecord, Integer> arrivalIndex = new HashMap<>();
        List<ExceptionRecord> snapshotAll = new ArrayList<>(records);
        for (int i = 0; i < snapshotAll.size(); i++) {
            arrivalIndex.put(snapshotAll.get(i), i);
        }
        final boolean descending = q.getOrderBy() == OrderBy.DESC;
        Comparator<ExceptionRecord> comparator = Comparator
                .comparingLong(ExceptionRecord::getTimestamp)
                .thenComparing(r -> arrivalIndex.getOrDefault(r, 0));
        if (descending) {
            comparator = comparator.reversed();
        }
        Collections.sort(matched, comparator);
        final List<ExceptionRecord> frozen = Collections.unmodifiableList(matched);
        return new IExportSnapshot<ExceptionRecord>() {
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
            public List<ExceptionRecord> nextBatch(int maxRows) {
                if (exhausted || maxRows <= 0) {
                    exhausted = true;
                    return new ArrayList<>();
                }
                int end = Math.min(frozen.size(), offset + maxRows);
                List<ExceptionRecord> page = new ArrayList<>(frozen.subList(offset, end));
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

    public List<ExceptionRecord> getWrittenRecords() {
        return new ArrayList<>(records);
    }

    public void clear() {
        records.clear();
    }

    private boolean matches(ExceptionRecord r, ExceptionQuery q) {
        if (q == null) {
            return true;
        }
        if (q.getTag() != null && !q.getTag().equals(r.getLogTag())) {
            return false;
        }
        if (q.getExceptionClass() != null
                && !q.getExceptionClass().equals(r.getExceptionClass())) {
            return false;
        }
        if (q.getKeyword() != null && r.getExceptionMessage() != null
                && !r.getExceptionMessage().contains(q.getKeyword())) {
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
