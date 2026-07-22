# log-sample-android 混淆规则
# 应用自身规则（库模块的 consumer-rules.pro 会自动传递）

# 示例 Application 和 Activity 需保留（Android 入口）
-keep class com.atian10.logrecord.sample.android.** { *; }
