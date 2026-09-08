package com.atian10.logrecord.sample.android;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Button;

import com.atian10.logrecord.core.LogManager;
import com.atian10.logrecord.core.config.LogConfigUpdater;
import com.atian10.logrecord.core.engine.FlushResult;
import com.atian10.logrecord.core.export.ExportCallback;
import com.atian10.logrecord.core.export.ExportFormat;
import com.atian10.logrecord.core.model.LogLevel;
import com.atian10.logrecord.core.model.LogRecord;
import com.atian10.logrecord.core.model.StandardLogType;
import com.atian10.logrecord.core.query.ExceptionQuery;
import com.atian10.logrecord.core.query.LogQuery;
import com.atian10.logrecord.core.query.LogStatistics;
import com.atian10.logrecord.core.query.OrderBy;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Android 示例 Activity
 * <p>
 * 通过按钮触发各种日志操作：
 * <ul>
 *   <li>写入日志（5 级别 + 携带异常 + 携带用户字段）</li>
 *   <li>查询日志（条件 + 统计 + 异常）</li>
 *   <li>导出日志（JSON/CSV/TXT，导出到应用缓存目录）</li>
 *   <li>动态修改配置（运行时切换 versionTag / captureMethodLine）</li>
 *   <li>手动清理</li>
 * </ul>
 * </p>
 * <p>
 * <b>线程模型（ISSUE-06 修复）</b>：导出、清理、flush 等待与关闭等待等数据库/文件/
 * 阻塞操作统一提交到本 Activity 的受控后台执行器（单线程串行，避免并发导出），
 * 只在主线程更新界面；页面销毁后忽略晚到回调并释放执行器（排队任务执行完毕，
 * 其中 flush 作为收尾任务保留）。错误同时输出到界面，不只依赖 Logcat。
 * </p>
 * <p>
 * 注意：日志库初始化已在 {@link SampleApp#onCreate()} 中完成，Activity 直接使用 LogManager.get()。
 * </p>
 */
public class SampleActivity extends Activity {

    private static final String TAG = "SampleActivity";

    /** flush 等待时限（毫秒）：后台等待，不阻塞主线程 */
    private static final long FLUSH_TIMEOUT_MILLIS = 5000L;

    private TextView mOutputView;
    private LogManager mLogger;

    /** 受控后台执行器：导出/清理/flush 等待串行执行，避免主线程数据库访问与并发导出 */
    private final ExecutorService backgroundExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "sample-bg-executor");
                t.setDaemon(true);
                return t;
            });
    /** 页面是否已销毁：销毁后忽略晚到回调，不再触碰界面 */
    private volatile boolean destroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mLogger = LogManager.get();

        // 简单的 UI：垂直布局 + 按钮 + 滚动文本输出
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);
        // ScrollView 通过 addView 添加唯一子视图（setContentView 是既有缺陷，ScrollView 无此方法）
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("LogRecord Android 示例");
        title.setTextSize(18f);
        root.addView(title);

        mOutputView = new TextView(this);
        mOutputView.setTextSize(12f);
        mOutputView.setPadding(0, 32, 0, 32);
        root.addView(mOutputView);

        addButton(root, "1. 写入各级别日志", v -> writeLogs());
        addButton(root, "2. 写入异常 + 用户字段", v -> writeExceptionLog());
        addButton(root, "3. 查询 ERROR 日志", v -> queryErrorLogs());
        addButton(root, "4. 聚合统计", v -> showStatistics());
        addButton(root, "5. 查询异常", v -> queryExceptions());
        addButton(root, "6. 导出为 JSON", v -> exportLogs(ExportFormat.JSON));
        addButton(root, "7. 导出为 CSV", v -> exportLogs(ExportFormat.CSV));
        addButton(root, "8. 动态修改 versionTag", v -> updateConfig());
        addButton(root, "9. 手动清理", v -> cleanNow());

        setContentView(scroll);
    }

    private void addButton(LinearLayout root, String text, View.OnClickListener listener) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 16;
        root.addView(btn, lp);
    }

    // ===== 写入示例 =====

    private void writeLogs() {
        try {
            mLogger.d(TAG, "调试信息：用户点击按钮");
            mLogger.i(TAG, "普通信息：界面加载完成");
            mLogger.w(TAG, "警告信息：内存占用较高");
            mLogger.e(TAG, "错误信息：网络请求失败");
            mLogger.f(TAG, "致命错误：核心组件崩溃");
            appendOutput("已写入 5 条各级别日志");
            // flush 是等待操作：放到后台执行器，不阻塞主线程（ISSUE-06）
            flushInBackground("writeLogs");
        } catch (Throwable t) {
            reportError("writeLogs", t);
        }
    }

    private void writeExceptionLog() {
        try {
            // 模拟业务异常
            try {
                throw new IllegalStateException("用户会话已过期");
            } catch (IllegalStateException e) {
                mLogger.e(TAG, "登录失败", e);
            }

            // 携带用户键值对
            Map<String, String> fields = new HashMap<>();
            fields.put("userId", "10086");
            fields.put("ip", "192.168.1.100");
            fields.put("errorCode", "AUTH_FAIL");
            mLogger.log(LogLevel.ERROR,
                    StandardLogType.NETWORK.code(),
                    "LoginService",
                    "用户登录失败",
                    fields);

            appendOutput("已写入异常日志和带用户字段的日志");
            flushInBackground("writeExceptionLog");
        } catch (Throwable t) {
            reportError("writeExceptionLog", t);
        }
    }

    // ===== 查询示例 =====

    private void queryErrorLogs() {
        // 数据库查询必须在子线程执行，避免主线程阻塞导致 ANR
        new Thread(() -> {
            try {
                long now = System.currentTimeMillis();
                long oneHourAgo = now - 3600_000L;
                List<LogRecord> records = mLogger.queryLogs(LogQuery.builder()
                        .level(LogLevel.ERROR)
                        .fromTime(oneHourAgo)
                        .toTime(now)
                        .orderBy(OrderBy.DESC)
                        .limit(20)
                        .build());
                StringBuilder sb = new StringBuilder();
                sb.append("查询到 ").append(records.size()).append(" 条 ERROR 日志：\n");
                for (LogRecord r : records) {
                    sb.append("  [").append(r.getLevel()).append("] ")
                            .append(r.getTag()).append(" : ").append(r.getMessage())
                            .append("\n");
                }
                postToUi(() -> appendOutput(sb.toString()));
            } catch (Throwable t) {
                reportError("queryErrorLogs", t);
            }
        }, "query-error-logs").start();
    }

    private void showStatistics() {
        // 数据库统计必须在子线程执行，避免主线程阻塞导致 ANR
        new Thread(() -> {
            try {
                long now = System.currentTimeMillis();
                long oneDayAgo = now - 86_400_000L;
                LogStatistics stats = mLogger.statistics(LogQuery.builder()
                        .fromTime(oneDayAgo)
                        .toTime(now)
                        .build());
                StringBuilder sb = new StringBuilder();
                sb.append("聚合统计（最近 24h）：\n");
                sb.append("  总数: ").append(stats.getTotalCount()).append("\n");
                sb.append("  按级别: ").append(stats.getCountByLevel()).append("\n");
                sb.append("  按类型: ").append(stats.getCountByType()).append("\n");
                sb.append("  按Tag: ").append(stats.getCountByTag()).append("\n");
                postToUi(() -> appendOutput(sb.toString()));
            } catch (Throwable t) {
                reportError("showStatistics", t);
            }
        }, "show-statistics").start();
    }

    private void queryExceptions() {
        // 数据库查询必须在子线程执行，避免主线程阻塞导致 ANR
        new Thread(() -> {
            try {
                long now = System.currentTimeMillis();
                long oneHourAgo = now - 3600_000L;
                List<com.atian10.logrecord.core.model.ExceptionRecord> exceptions =
                        mLogger.queryExceptions(ExceptionQuery.builder()
                                .tag(TAG)
                                .fromTime(oneHourAgo)
                                .toTime(now)
                                .orderBy(OrderBy.DESC)
                                .limit(10)
                                .build());
                StringBuilder sb = new StringBuilder();
                sb.append("查询到 ").append(exceptions.size()).append(" 条异常记录：\n");
                for (com.atian10.logrecord.core.model.ExceptionRecord e : exceptions) {
                    sb.append("  ").append(e.getExceptionClass())
                            .append(": ").append(e.getExceptionMessage())
                            .append("\n");
                }
                postToUi(() -> appendOutput(sb.toString()));
            } catch (Throwable t) {
                reportError("queryExceptions", t);
            }
        }, "query-exceptions").start();
    }

    // ===== 导出示例 =====

    private void exportLogs(ExportFormat format) {
        // 导出含快照复制、数据库分页与文件写入：必须离开主线程（ISSUE-06）
        backgroundExecutor.execute(() -> {
            try {
                // 导出到应用缓存目录（无需存储权限）
                File exportDir = new File(getExternalCacheDir(), "log-export");
                if (!exportDir.exists()) {
                    exportDir.mkdirs();
                }
                String ext = format == ExportFormat.JSON ? "json"
                        : format == ExportFormat.CSV ? "csv" : "txt";
                File exportFile = new File(exportDir,
                        "logs_" + System.currentTimeMillis() + "." + ext);

                long now = System.currentTimeMillis();
                long oneHourAgo = now - 3600_000L;
                mLogger.exportLogs(
                        LogQuery.builder()
                                .fromTime(oneHourAgo)
                                .toTime(now)
                                .orderBy(OrderBy.ASC)
                                .build(),
                        format,
                        exportFile.getAbsolutePath(),
                        new ExportCallback() {
                            @Override
                            public void onProgress(int exported, int total) {
                                postToUi(() -> appendOutput(
                                        "导出进度: " + exported + "/" + total));
                            }

                            @Override
                            public void onSuccess(String filePath, int totalCount) {
                                postToUi(() -> appendOutput(
                                        "导出 " + format + " 成功: " + filePath
                                                + " (" + totalCount + " 条)"));
                            }

                            @Override
                            public void onFailure(Throwable error, int exportedCount) {
                                postToUi(() -> appendOutput(
                                        "导出失败: " + error));
                            }
                        });
            } catch (Throwable t) {
                reportError("exportLogs", t);
            }
        });
    }

    // ===== 动态配置示例 =====

    private void updateConfig() {
        try {
            LogConfigUpdater updater = mLogger.getConfigUpdater();
            // 切换 versionTag（立即 CAS 生效，无需 apply）
            String current = mLogger.getConfig().getVersionTag();
            String next = "android-sample-1.0.1".equals(current)
                    ? "android-sample-1.0.0" : "android-sample-1.0.1";
            updater.updateVersionTag(next);

            // 切换方法行号捕获
            boolean capture = mLogger.getConfig().isCaptureMethodLine();
            updater.updateCaptureMethodLine(!capture);

            appendOutput("动态配置已更新: versionTag=" + next
                    + ", captureMethodLine=" + (!capture));
        } catch (Throwable t) {
            reportError("updateConfig", t);
        }
    }

    // ===== 清理示例 =====

    private void cleanNow() {
        // 清理含数据库删除与全库容量维护：必须离开主线程（ISSUE-06）
        backgroundExecutor.execute(() -> {
            try {
                mLogger.cleanNow(new com.atian10.logrecord.core.clean.CleanCallback() {
                    @Override
                    public void onSuccess(int cleanedCount) {
                        postToUi(() -> appendOutput("清理完成，清理 " + cleanedCount + " 条"));
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        postToUi(() -> appendOutput("清理失败: " + error));
                    }
                });
            } catch (Throwable t) {
                reportError("cleanNow", t);
            }
        });
    }

    // ===== 后台等待与收尾 =====

    /**
     * 在后台执行器上等待 flush 完成，并在界面反馈结果
     * <p>flush 是阻塞等待（含存储写入完成确认），不得在主线程执行</p>
     */
    private void flushInBackground(String source) {
        backgroundExecutor.execute(() -> {
            try {
                FlushResult result = mLogger.flush(FLUSH_TIMEOUT_MILLIS);
                if (result.isAllPersisted()) {
                    postToUi(() -> appendOutput(
                            "[" + source + "] flush 完成: 保存 " + result.getSaved() + " 条"));
                } else {
                    postToUi(() -> appendOutput(
                            "[" + source + "] flush 未完全落盘: " + result));
                }
            } catch (Throwable t) {
                reportError("flush(" + source + ")", t);
            }
        });
    }

    // ===== UI 辅助 =====

    /**
     * 安全地把操作转到主线程：页面销毁后忽略，不触碰已废弃界面
     */
    private void postToUi(Runnable action) {
        if (destroyed) {
            return;
        }
        runOnUiThread(() -> {
            if (!destroyed) {
                action.run();
            }
        });
    }

    private void appendOutput(String text) {
        CharSequence old = mOutputView.getText();
        String newText = (old.length() == 0 ? "" : old + "\n") + text;
        mOutputView.setText(newText);
    }

    /**
     * 统一错误反馈：界面输出 + Logcat（不只依赖 Logcat，ISSUE-06）
     */
    private void reportError(String where, Throwable t) {
        Log.e(TAG, where + " failed", t);
        postToUi(() -> appendOutput(where + " 失败: " + t));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 销毁后忽略晚到回调；flush 作为收尾任务入队，执行器关闭后排队任务仍会执行完
        destroyed = true;
        try {
            if (LogManager.isInitialized()) {
                backgroundExecutor.execute(() -> {
                    try {
                        LogManager.get().flush(FLUSH_TIMEOUT_MILLIS);
                    } catch (Throwable ignored) {
                        // 收尾 flush 失败不再反馈界面（页面已销毁）
                    }
                });
            }
        } catch (Throwable ignored) {
            // 初始化竞态下跳过收尾 flush
        }
        backgroundExecutor.shutdown();
        // 不阻塞 onDestroy 等待线程退出；Application 销毁时由 AndroidLogInit.shutdown 关闭日志库
    }
}
