package com.ledger.offline.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * 月份窗口。
 *
 * 这里最该守的是「跨月」：微信账单默认导出最近一个月（如 8/10~9/10），
 * 界面如果只看自然月，落在上个月的那部分就跟"没导入"一样——
 * 2026-09-21 报的「只导入了 5 条」就是这么来的。所以窗口必须能前后翻，
 * 且翻出去的窗口要正好是那个月。
 */
class MonthWindowTest {

    /** 2026-09-21 12:00:00（本地时区） */
    private val now: Long = Calendar.getInstance(Locale.CHINA).apply {
        set(2026, Calendar.SEPTEMBER, 21, 12, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `当前月窗口是月初到下月初`() {
        val (from, to) = MonthWindow.range(now)
        val start = Calendar.getInstance(Locale.CHINA).apply { timeInMillis = from }
        assertEquals(2026, start.get(Calendar.YEAR))
        assertEquals(Calendar.SEPTEMBER, start.get(Calendar.MONTH))
        assertEquals(1, start.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, start.get(Calendar.HOUR_OF_DAY))

        val end = Calendar.getInstance(Locale.CHINA).apply { timeInMillis = to }
        assertEquals(Calendar.OCTOBER, end.get(Calendar.MONTH))
        assertEquals(1, end.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `上一个月窗口落在上个月`() {
        val (from, to) = MonthWindow.range(now, -1)
        val start = Calendar.getInstance(Locale.CHINA).apply { timeInMillis = from }
        assertEquals(Calendar.AUGUST, start.get(Calendar.MONTH))
        assertEquals(1, start.get(Calendar.DAY_OF_MONTH))

        val end = Calendar.getInstance(Locale.CHINA).apply { timeInMillis = to }
        assertEquals(Calendar.SEPTEMBER, end.get(Calendar.MONTH))
        assertEquals(1, end.get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun `跨年往回翻不会算错年份`() {
        val jan = Calendar.getInstance(Locale.CHINA).apply {
            set(2026, Calendar.JANUARY, 15, 10, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val (from, _) = MonthWindow.range(jan, -1)
        val start = Calendar.getInstance(Locale.CHINA).apply { timeInMillis = from }
        assertEquals(2025, start.get(Calendar.YEAR))
        assertEquals(Calendar.DECEMBER, start.get(Calendar.MONTH))
    }

    @Test
    fun `相邻两个月的窗口首尾相接，不重叠也不留缝`() {
        val (_, to) = MonthWindow.range(now, -1)
        val (nextFrom, _) = MonthWindow.range(now, 0)
        assertEquals(nextFrom, to)
    }

    @Test
    fun `月份标签是中文年月`() {
        assertEquals("2026年9月", MonthWindow.label(now))
        assertEquals("2026年8月", MonthWindow.label(now, -1))
        assertEquals("2025年12月", MonthWindow.label(now, -9))
    }

    @Test
    fun `同一天的不同时刻落在同一个分组 key`() {
        val morning = Calendar.getInstance(Locale.CHINA).apply {
            set(2026, Calendar.SEPTEMBER, 21, 8, 5, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val night = Calendar.getInstance(Locale.CHINA).apply {
            set(2026, Calendar.SEPTEMBER, 21, 23, 47, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(MonthWindow.dayStart(morning), MonthWindow.dayStart(night))
    }

    @Test
    fun `导入后能定位到真的有账的那个月`() {
        val aug = at(2026, Calendar.AUGUST, 10, 9, 0)
        val sep = at(2026, Calendar.SEPTEMBER, 10, 9, 0)
        assertEquals(0, MonthWindow.offsetOf(now, sep))
        assertEquals(-1, MonthWindow.offsetOf(now, aug))
        // 跨年也算得对
        val decLastYear = at(2025, Calendar.DECEMBER, 31, 23, 0)
        assertEquals(-9, MonthWindow.offsetOf(now, decLastYear))
    }

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance(Locale.CHINA).apply {
            set(y, m, d, h, min, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `日期标签带周几`() {
        // 2026-09-21 是周一
        assertEquals("9月21日 周一", MonthWindow.dayLabel(now))
    }
}
