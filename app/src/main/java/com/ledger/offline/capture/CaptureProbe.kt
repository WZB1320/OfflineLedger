package com.ledger.offline.capture

import android.content.Context
import com.ledger.offline.parse.ProbeLog
import com.ledger.offline.parse.ProbeLog.ProbeEntry

/**
 * [ProbeLog] 在本机上的持久化，以及那个**必须由用户自己决定**的开关。
 *
 * 开关默认关：把通知原文（含金额、商户名）另存一份明文，与本项目
 * 「字段级加密」的基线是相悖的，所以不能替用户默认打开。
 * 它只在排查「有通知却没记账」时临时开启，用完即关、可一键清空。
 *
 * 存储仍走 SharedPreferences 私有目录——与数据库同为本应用私有，不涉及网络。
 */
object CaptureProbe {

    private const val PREF = "capture_probe"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LOG = "log"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    /**
     * 开关变化时**顺手清空**：关掉的语义是「不再留明文」，
     * 只停止写入而留着已经记录的原文，等于什么都没关。
     */
    fun setEnabled(context: Context, on: Boolean) {
        val editor = prefs(context).edit().putBoolean(KEY_ENABLED, on)
        if (!on) editor.remove(KEY_LOG)
        editor.apply()
    }

    fun record(context: Context, entry: ProbeEntry) {
        if (!isEnabled(context)) return
        val list = ProbeLog.push(recent(context), entry)
        prefs(context).edit().putString(KEY_LOG, ProbeLog.encodeAll(list)).apply()
    }

    /** 最近的通知记录，最新的一条在末尾 */
    fun recent(context: Context): List<ProbeEntry> =
        ProbeLog.decodeAll(prefs(context).getString(KEY_LOG, "").orEmpty())

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_LOG).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
