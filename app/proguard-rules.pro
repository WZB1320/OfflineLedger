# 保留解析规则的数据类字段名（如果将来用 Gson/Moshi 反射解析规则文件）
-keepclassmembers class com.ledger.offline.parse.** { <fields>; }

# 通知监听 / 无障碍服务由系统通过反射实例化，必须保留
-keep class com.ledger.offline.capture.NotificationCaptureService { *; }
-keep class com.ledger.offline.capture.ScreenReadService { *; }
-keep class com.ledger.offline.capture.BootReceiver { *; }

# 移除所有日志（release 版本）
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# 确认工程里没有任何网络相关类残留（编译期兜底）
-dontwarn java.net.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
