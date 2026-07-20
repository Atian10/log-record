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
 * 注意：日志库初始化已在 {@link SampleApp#onCreate()} 中完成，Activity 直接使用 LogManager.get()。
 * </p>
 */
public class SampleActivity extends Activity {

    private static final String TAG = "SampleActivity";

    private TextView mOutputView;
    private LogManager mLogger;

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
            mLogger.flush();   // 立即触发落盘（演示用，生产环境无需频繁 flush）
            appendOutput("已写入 5 条各级别日志");
        } catch (Throwable t) {
            Log.e(TAG, "writeLogs failed", t);
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

            mLogger.flush();
            appendOutput("已写入异常日志和带用户字段的日志");
        } catch (Throwable t) {
            Log.e(TAG, "writeExceptionLog failed", t);
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
                runOnUiThread(() -> appendOutput(sb.toString()));
            } catch (Throwable t) {
                Log.e(TAG, "queryErrorLogs failed", t);
                runOnUiThread(() -> appendOutput("查询失败: " + t.getMessage()));
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
                runOnUiThread(() -> appendOutput(sb.toString()));
            } catch (Throwable t) {
                Log.e(TAG, "showStatistics failed", t);
                runOnUiThread(() -> appendOutput("统计失败: " + t.getMessage()));
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
                runOnUiThread(() -> appendOutput(sb.toString()));
            } catch (Throwable t) {
                Log.e(TAG, "queryExceptions failed", t);
                runOnUiThread(() -> appendOutput("查询异常失败: " + t.getMessage()));
            }
        }, "query-exceptions").start();
    }

    // ===== 导出示例 =====

    private void exportLogs(ExportFormat format) {
        try {
            // 导出到应用缓存目录（无需存储权限）
            File exportDir = new File(getExternalCacheDir(), "log-export");
            if (!exportDir.exists()) {
                exportDir.mkdirs();
            }
            String ext = format == ExportFormat.JSON ? "json"
                    : format == ExportFormat.CSV ? "csv" : "txt";
            File exportFile = new File(exportDir, "logs_" + System.currentTimeMillis() + "." + ext);

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
                        }

                        @Override
                        public void onSuccess(String filePath, int totalCount) {
                            runOnUiThread(() -> appendOutput(
                                    "导出 " + format + " 成功: " + filePath
                                            + " (" + totalCount + " 条)"));
                        }

                        @Override
                        public void onFailure(Throwable error, int exportedCount) {
                            runOnUiThread(() -> appendOutput(
                                    "导出失败: " + error.getMessage()));
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "exportLogs failed", t);
        }
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
            Log.e(TAG, "updateConfig failed", t);
        }
    }

    // ===== 清理示例 =====

    private void cleanNow() {
        try {
            mLogger.cleanNow(new com.atian10.logrecord.core.clean.CleanCallback() {
                @Override
                public void onSuccess(int cleanedCount) {
                    runOnUiThread(() -> appendOutput("清理完成，清理 " + cleanedCount + " 条"));
                }

                @Override
                public void onFailure(Throwable error) {
                    runOnUiThread(() -> appendOutput("清理失败: " + error.getMessage()));
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "cleanNow failed", t);
        }
    }

    // ===== UI 辅助 =====

    private void appendOutput(String text) {
        runOnUiThread(() -> {
            CharSequence old = mOutputView.getText();
            String newText = (old.length() == 0 ? "" : old + "\n") + text;
            mOutputView.setText(newText);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Activity 销毁时 flush 一下，避免队列残留（Application 销毁时由 AndroidLogInit.shutdown 关闭）
        try {
            if (LogManager.isInitialized()) {
                LogManager.get().flush();
            }
        } catch (Throwable ignored) {
        }
    }
}
