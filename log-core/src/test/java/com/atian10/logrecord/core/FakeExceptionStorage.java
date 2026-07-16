package com.atian10.logrecord.core;

import com.atian10.logrecord.core.config.CleanPolicy;
import com.atian10.logrecord.core.model.ExceptionRecord;
import com.atian10.logrecord.core.query.ExceptionQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存版 IExceptionStorage，用于单元测试。
 * <p>
 * 不实际访问数据库，所有数据存于内存 List。
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
