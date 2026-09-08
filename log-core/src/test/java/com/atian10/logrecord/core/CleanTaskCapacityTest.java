package com.atian10.logrecord.core;

import com.atian10.logrecord.core.clean.CapacityBudget;
import com.atian10.logrecord.core.clean.CapacityCoordinator;
import com.atian10.logrecord.core.clean.CleanCallback;
import com.atian10.logrecord.core.clean.CleanResult;
import com.atian10.logrecord.core.clean.CleanTask;
import com.atian10.logrecord.core.config.CleanPolicy;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * CleanTask 容量阶段集成测试（T-07 / ISSUE-05 core 部分）
 * <p>
 * 覆盖：表级清理后执行容量阶段、无预算跳过、未达标记录 lastError 且回调仍成功、
 * 缺少协调器时记录可观察原因、getLastCapacityResult 可观察。
 * </p>
 */
public class CleanTaskCapacityTest {

    /** 记录调用的协调器桩 */
    static final class RecordingCoordinator implements CapacityCoordinator {
        final List<CapacityBudget> calls = new ArrayList<>();
        CapacityBudget lastBudget;

        @Override
        public CleanResult enforceCapacity(CapacityBudget budget) {
            calls.add(budget);
            lastBudget = budget;
            return new CleanResult(3, 1, 100, 50, budget.getBudgetBytes(),
                    CleanResult.Outcome.MET, null);
        }
    }

    /** 恒未达标的协调器桩 */
    static final class NotMetCoordinator implements CapacityCoordinator {
        @Override
        public CleanResult enforceCapacity(CapacityBudget budget) {
            return new CleanResult(0, 0, 100, 100, budget.getBudgetBytes(),
                    CleanResult.Outcome.NOT_MET, "no deletable data within eligible tables");
        }
    }

    @Test
    public void runOnce_withBudget_invokesCoordinatorAfterTableRules() {
        FakeStorage storage = new FakeStorage();
        FakeExceptionStorage exStorage = new FakeExceptionStorage();
        RecordingCoordinator coordinator = new RecordingCoordinator();
        // 预算供应者返回固定预算（绕过配置解析，聚焦 CleanTask 阶段编排）
        CapacityBudget budget = new CapacityBudget(1024L, true, false);
        CleanTask task = new CleanTask(storage, exStorage, coordinator, () -> budget);

        final int[] successCount = {0};
        int cleaned = task.runOnce(
                CleanPolicy.builder().enable(true).maxRecordCount(0).build(),
                CleanPolicy.builder().enable(false).build(),
                new CleanCallback() {
                    @Override
                    public void onSuccess(int cleanedCount) {
                        successCount[0]++;
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        throw new IllegalStateException("unexpected failure", error);
                    }
                });

        // 表级 keepDays=0/maxRecordCount=0 无删除；容量阶段由协调器执行
        assertEquals(0, cleaned);
        assertEquals(1, coordinator.calls.size());
        assertSame(budget, coordinator.lastBudget);
        assertEquals(1, successCount[0]);
        assertSame(CleanResult.Outcome.MET, task.getLastCapacityResult().getOutcome());
        assertNull(task.getLastError());
        task.shutdown(1000L);
    }

    @Test
    public void runOnce_notMet_recordsLastError_butCallbackStillSucceeds() {
        FakeStorage storage = new FakeStorage();
        FakeExceptionStorage exStorage = new FakeExceptionStorage();
        NotMetCoordinator coordinator = new NotMetCoordinator();
        CleanTask task = new CleanTask(storage, exStorage, coordinator,
                () -> new CapacityBudget(1L, true, true));

        final int[] successCount = {0};
        task.runOnce(CleanPolicy.builder().enable(false).build(),
                CleanPolicy.builder().enable(false).build(),
                new CleanCallback() {
                    @Override
                    public void onSuccess(int cleanedCount) {
                        successCount[0]++;
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        throw new IllegalStateException("unexpected failure", error);
                    }
                });

        assertEquals(1, successCount[0]);
        assertEquals(CleanResult.Outcome.NOT_MET, task.getLastCapacityResult().getOutcome());
        assertTrue("lastError should describe not-met capacity",
                task.getLastError() != null && task.getLastError().contains("NOT_MET"));
        task.shutdown(1000L);
    }

    @Test
    public void budgetWithoutCoordinator_recordsObservableReason() {
        FakeStorage storage = new FakeStorage();
        FakeExceptionStorage exStorage = new FakeExceptionStorage();
        // 未注入协调器但预算存在（对应核心直连场景）
        CleanTask task = new CleanTask(storage, exStorage, null,
                () -> new CapacityBudget(1024L, true, true));
        task.runOnce(CleanPolicy.builder().enable(false).build(),
                CleanPolicy.builder().enable(false).build(), null);
        assertTrue("missing coordinator should be observable",
                task.getLastError() != null && task.getLastError().contains("no CapacityCoordinator"));
        assertNull(task.getLastCapacityResult());
        task.shutdown(1000L);
    }

    @Test
    public void noBudget_skipsCapacityPhase() {
        FakeStorage storage = new FakeStorage();
        FakeExceptionStorage exStorage = new FakeExceptionStorage();
        RecordingCoordinator coordinator = new RecordingCoordinator();
        CleanTask task = new CleanTask(storage, exStorage, coordinator, () -> null);
        task.runOnce(CleanPolicy.builder().enable(false).build(),
                CleanPolicy.builder().enable(false).build(), null);
        assertEquals(0, coordinator.calls.size());
        assertNull(task.getLastCapacityResult());
        task.shutdown(1000L);
    }
}
