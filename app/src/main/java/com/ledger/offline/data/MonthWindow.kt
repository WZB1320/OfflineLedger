package com.ledger.offline.data

import java.util.Calendar
import java.util.Locale

/**
 * 月份窗口与日期分档。纯 Kotlin，不碰 Android——这样 JVM 单测得覆盖它，
 * 不必等到真机才发现「跨月账单看不到」这类问题。
 *
 * ## 为什么必须是可偏移的
 * 微信默认导出「最近一个月」的账单，天然跨月（如 8/10~9/10）。若界面只看自然月，
 * 落在上个月的那部分就跟"没导入"一样——2026-09-21 报的「只导入了 5 条」就是这么来的：
 * 数据全在库里，只是窗口写死了当月。所以月份必须能前后翻。
 */
object MonthWindow {

    /**
     * 指定月份的窗口 [月初, 下月初)。
     *
     * @param offsetMonths 0 当前月，-1 上一个月，-12 去年同月。正数会指向未来，
     *        界面层负责拦住（未来没有账可看），这里不拦——窗口本身不含业务约束。
     */
    fun range(now: Long, offsetMonths: Int = 0): Pair<Long, Long> {
        val cal = startOfMonth(now, offsetMonths)
        val from = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        return from to cal.timeInMillis
    }

    /** 「2026年9月」——月份切换条中间那个标签 */
    fun label(now: Long, offsetMonths: Int = 0): String {
        val cal = startOfMonth(now, offsetMonths)
        return "${cal.get(Calendar.YEAR)}年${cal.get(Calendar.MONTH) + 1}月"
    }

    /**
     * 目标时刻相对当前月是第几个月（0 = 本月，-1 = 上月）。
     *
     * 用途：导入完账单后跳到**真的有账的那个月**。微信账单默认导出「最近一个月」
     * 天然跨月，导完若仍停在当前月，用户会看到「只进来几条」——
     * 这正是 2026-09-21 那个「只导入 5 条」的观感来源（数据其实全在库里）。
     */
    fun offsetOf(now: Long, target: Long): Int = monthIndex(target) - monthIndex(now)

    private fun monthIndex(millis: Long): Int {
        val cal = Calendar.getInstance(Locale.CHINA).apply { timeInMillis = millis }
        return cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
    }

    /** 当天 00:00 的时间戳，用作分组 key（同一天的记录必须落进同一组） */
    fun dayStart(millis: Long): Long {
        val cal = Calendar.getInstance(Locale.CHINA).apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    /** 「9月21日 周一」——列表里的分组头。周几按中文习惯取 Calendar 的 DAY_OF_WEEK 映射 */
    fun dayLabel(millis: Long): String {
        val cal = Calendar.getInstance(Locale.CHINA).apply { timeInMillis = millis }
        val week = WEEK_NAMES[(cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY + 7) % 7]
        return "${cal.get(Calendar.MONTH) + 1}月${cal.get(Calendar.DAY_OF_MONTH)}日 $week"
    }

    private fun startOfMonth(now: Long, offsetMonths: Int): Calendar =
        Calendar.getInstance(Locale.CHINA).apply {
            timeInMillis = now
            add(Calendar.MONTH, offsetMonths)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private val WEEK_NAMES = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
}
