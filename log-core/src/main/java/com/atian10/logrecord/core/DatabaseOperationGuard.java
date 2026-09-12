package com.atian10.logrecord.core;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 一个数据库所有者的操作存活期和维护互斥保护。
 * 锁序：操作许可 → 表导出锁（日志、异常）→ 维护锁 → JDBC/Room 内部锁。
 * 许可与快照仅在创建线程使用和关闭；关闭等待不持有后三级锁。
 */
public final class DatabaseOperationGuard {
    /** 每个外层操作计数一次，嵌套调用在关闭开始后仍可完成。 */
    private final ThreadLocal<Integer> depth = new ThreadLocal<>();
    /** 普通数据库访问共享、容量维护独占；不负责操作生命周期计数。 */
    private final ReentrantReadWriteLock maintenance = new ReentrantReadWriteLock();
    /** 关闭准入标志及仍存活的外层操作数，由本对象 monitor 保护。 */
    private boolean closing;
    private int active;
    /** 唯一工作线程可在关闭期间排空；注册期间另持一个存活计数。 */
    private volatile Thread drainThread;

    /** 获取当前线程操作许可；关闭后只允许已经准入的嵌套操作与排空线程。 */
    public synchronized Scope enter() {
        // 线程局部深度不等于数据库活动数，防止复合查询的嵌套计数失衡。
        Integer current = depth.get();
        int nesting = current == null ? 0 : current;
        if (closing && nesting == 0 && Thread.currentThread() != drainThread)
            throw new IllegalStateException("database is closing");
        if (nesting == 0) active++;
        depth.set(nesting + 1);
        return new Scope(Thread.currentThread());
    }

    /** 启动 worker 前预留排空权；必须在停止准入之前且只能注册一次。 */
    public synchronized void registerDrainThread(Thread worker) {
        if (closing || drainThread != null) throw new IllegalStateException("drain already registered or closing");
        drainThread = worker;
        active++;
    }

    /** worker 的最终出口释放排空权，不长期占用维护读锁。 */
    public synchronized void finishDrainThread() {
        if (Thread.currentThread() != drainThread) throw new IllegalStateException("wrong drain owner");
        drainThread = null;
        active--;
        notifyAll();
    }

    /** 封闭外层新操作准入；不等待任何数据库锁。 */
    public synchronized void beginClosing() { closing = true; }

    /** 原始资源关闭不得等待仍运行的 worker；使用管理器入口先停止接收和排空。 */
    public synchronized void beginResourceClose() {
        if (drainThread != null)
            throw new IllegalStateException("database is owned by a live engine; use LogManager.shutdown()");
        closing = true;
    }

    /** 当前线程是否持有操作，关闭入口用它避免等待自身释放。 */
    public boolean isCurrentThreadActive() { return depth.get() != null || Thread.currentThread() == drainThread; }

    /** 按共享时限等待所有许可归还；零时限立即返回，不允许等待自身。 */
    public synchronized boolean awaitIdle(long timeoutMillis) {
        if (isCurrentThreadActive()) return false;
        // 使用 elapsed 差值避免绝对截止时间相加溢出。
        long started = System.nanoTime();
        long budget = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        while (active != 0) {
            long remaining = budget - (System.nanoTime() - started);
            if (remaining <= 0L) return false;
            try { TimeUnit.NANOSECONDS.timedWait(this, remaining); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return true;
    }

    /** 取得普通数据库访问锁；调用者先持操作许可及所需表锁。 */
    public Access read() {
        // Access 只拥有这次锁获取，关闭时恰好释放一次。
        Lock lock = maintenance.readLock();
        lock.lock();
        return new Access(lock);
    }

    /** 尝试取得维护独占锁；失败返回 null，不做读锁升级或等待。 */
    public Access tryMaintenance() {
        Lock lock = maintenance.writeLock();
        return lock.tryLock() ? new Access(lock) : null;
    }

    /** 在创建线程幂等归还操作许可。 */
    public final class Scope implements AutoCloseable {
        /** 创建线程是 ThreadLocal 计数及表锁的共同所有者。 */
        private final Thread owner;
        private boolean closed;
        /** 仅由 enter 创建，调用方不能伪造许可。 */
        private Scope(Thread owner) { this.owner = owner; }
        /** 先释放操作持有的数据库/表锁，最后调用本方法。 */
        @Override public void close() {
            synchronized (DatabaseOperationGuard.this) {
                if (closed) return;
                if (owner != Thread.currentThread()) throw new IllegalStateException("operation belongs to another thread");
                closed = true;
                int nesting = depth.get() - 1;
                if (nesting == 0) { depth.remove(); active--; }
                else depth.set(nesting);
                DatabaseOperationGuard.this.notifyAll();
            }
        }
    }

    /** 一次维护读/写锁的作用域，必须在获取锁的线程释放。 */
    public static final class Access implements AutoCloseable {
        /** 非 null 表示仍持有这一次锁获取。 */
        private Lock lock;
        /** 由 guard 工厂方法创建已经持锁的作用域。 */
        private Access(Lock lock) { this.lock = lock; }
        /** 幂等释放本作用域持有的锁。 */
        @Override public void close() { if (lock != null) { lock.unlock(); lock = null; } }
    }
}
