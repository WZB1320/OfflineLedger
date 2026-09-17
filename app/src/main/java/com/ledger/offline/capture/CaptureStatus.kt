package com.ledger.offline.capture

import android.content.Context
import android.content.SharedPreferences

/**
 * 采集通道的运行状态快照（轻量持久化，不放数据库）。
 *
 * ## 为什么需要它
 * 系统**没有**提供「通知监听服务此刻是否真的活着」的查询接口。
 * `NotificationManagerCompat.getEnabledListenerPackages()` 只回答「授权过没有」，
 * 而这个授权在系统里是持久的：服务被 ROM 杀掉、被省电策略冻结之后，它照样返回 true。
 * 结果就是 App 一直显示「已开启」，用户却收不到任何自动记账，
 * 等到月底对账才发现漏了整整两个月——这是 silent failure，最伤信任。
 *
 * 对冲办法：让服务自己留心跳。连上时记一笔，真正监听到目标 App 的通知时再记一笔。
 * 界面读不到「连上过」的痕迹，就能判定为「授权在、服务死」，这时才提示用户处理。
 *
 * ## 为什么不判定「掉线」
 * 心跳时间戳只用来判断**有没有连上过**（`connectedAt > 0`），不用来判断
 * 「多久没消息所以掉线了」：用户可能十天半月不消费，拿静默时长当掉线证据必然误报。
 * 后者只作为中性信息展示（「最近监听到支付通知：N 天前」），由用户自己判断。
 */
object CaptureStatus {

    private const val PREF = "capture_status"
    private const val KEY_CONNECTED_AT = "listener_connected_at"
    private const val KEY_LAST_EVENT_AT = "listener_last_event_at"
    private const val KEY_LAST_IMPORT_AT = "last_import_at"
    private const val KEY_LAST_IMPORT_SUMMARY = "last_import_summary"

    /** 超过这个天数没导入账单，就认为账目完整性在滑坡，需要显式告警（方案 §10 风险登记册） */
    const val IMPORT_STALE_DAYS = 40

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    data class Snapshot(
        /** 系统里是否已授权通知使用权 */
        val granted: Boolean = false,
        /** 服务是否至少成功连上过一次（有心跳） */
        val everConnected: Boolean = false,
        /** 最近一次真正监听到目标 App 通知的时刻，0 = 还没有过 */
        val lastEventAt: Long = 0L,
        /** 最近一次成功导入账单的时刻，0 = 从未导入 */
        val lastImportAt: Long = 0L,
        /** 最近一次导入的结果摘要，用于状态条回显 */
        val lastImportSummary: String = ""
    ) {
        /** 已授权但服务没起来 —— 需要显式提示，否则用户以为在自动记账 */
        val listenerStalled: Boolean get() = granted && !everConnected

        /** 从未导入过账单，或已超过 [IMPORT_STALE_DAYS] 天 */
        fun importStale(now: Long = System.currentTimeMillis()): Boolean =
            lastImportAt == 0L || (now - lastImportAt) > IMPORT_STALE_DAYS * DAY_MS

        fun daysSinceImport(now: Long = System.currentTimeMillis()): Long =
            if (lastImportAt == 0L) -1L else (now - lastImportAt) / DAY_MS

        fun daysSinceEvent(now: Long = System.currentTimeMillis()): Long =
            if (lastEventAt == 0L) -1L else (now - lastEventAt) / DAY_MS
    }

    // ------------------------------------------------------------ 写入（由服务调用）

    /** 服务连上系统通知总线。这是一次性的「我起来了」证据，进程重启后仍留着 */
    fun markConnected(context: Context) {
        prefs(context).edit().putLong(KEY_CONNECTED_AT, System.currentTimeMillis()).apply()
    }

    /**
     * 真正监听到一条目标 App 的通知。
     * 刻意在**包名早筛通过之后**就记，而不是等解析成功——
     * 我们要的是「通道活着」的证据，不是「解析规则够用」的证据。
     */
    fun markNotificationSeen(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_EVENT_AT, System.currentTimeMillis()).apply()
    }

    /** 账单导入成功（有任何一条新增/合并/重复判定都算成功跑完） */
    fun markImport(context: Context, summary: String) {
        prefs(context).edit()
            .putLong(KEY_LAST_IMPORT_AT, System.currentTimeMillis())
            .putString(KEY_LAST_IMPORT_SUMMARY, summary)
            .apply()
    }

    // ------------------------------------------------------------ 读取

    fun snapshot(context: Context, granted: Boolean): Snapshot {
        val p = prefs(context)
        return Snapshot(
            granted = granted,
            everConnected = p.getLong(KEY_CONNECTED_AT, 0L) > 0L,
            lastEventAt = p.getLong(KEY_LAST_EVENT_AT, 0L),
            lastImportAt = p.getLong(KEY_LAST_IMPORT_AT, 0L),
            lastImportSummary = p.getString(KEY_LAST_IMPORT_SUMMARY, "").orEmpty()
        )
    }

    /** 供「采集设置」里的重置用：清掉心跳，让状态条重新回到真实状态 */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
