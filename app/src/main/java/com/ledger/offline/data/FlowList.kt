package com.ledger.offline.data

import com.ledger.offline.data.model.Direction
import com.ledger.offline.data.model.Transaction

/**
 * 流水页的筛选与按日分档。纯 Kotlin（不碰 Android），可在 JVM 单测里直接断言。
 *
 * 分档头要带当日小计（设计稿屏 1 的「9月21日 周一 / 支出 ¥86.50」），
 * 所以分组与求和必须一起做——分完组再回头扫一遍列表，很容易算出不一样的数。
 */
object FlowList {

    enum class Filter { ALL, EXPENSE, INCOME, UNCLASSIFIED }

    sealed class Entry {
        data class DayHead(
            val dayStart: Long,
            val label: String,
            val expense: Double,
            val income: Double,
            val count: Int
        ) : Entry()

        data class Row(val txn: Transaction) : Entry()
    }

    /**
     * 「未分类」= 没有商户名。
     *
     * 注意它与 `category_id = other` 是包含关系而不是并列：没有商户名 ⇒ 关键词必然不命中
     * ⇒ 分类同样落到 fallback。UI 上拆开显示只是为了提示「该补商户名」，
     * 统计口径必须合并算（见 [CategoryBreakdown]）。
     */
    fun isUnclassified(txn: Transaction, unknownMerchant: String): Boolean =
        txn.merchant.isBlank() || txn.merchant == unknownMerchant

    fun filter(txns: List<Transaction>, f: Filter, unknownMerchant: String): List<Transaction> =
        when (f) {
            Filter.ALL -> txns
            Filter.EXPENSE -> txns.filter { it.direction == Direction.EXPENSE }
            Filter.INCOME -> txns.filter { it.direction == Direction.INCOME }
            Filter.UNCLASSIFIED -> txns.filter { isUnclassified(it, unknownMerchant) }
        }

    /**
     * 按天分档。入参不必预先排序——这里自己排一次：
     * 分组依赖「同一天的记录必须相邻」，把排序的正确性交给调用方迟早会漏。
     */
    fun group(txns: List<Transaction>): List<Entry> {
        if (txns.isEmpty()) return emptyList()
        val sorted = txns.sortedByDescending { it.occurredAt }
        val out = ArrayList<Entry>(sorted.size + 8)
        var dayStart = Long.MIN_VALUE
        var label = ""
        var expense = 0.0
        var income = 0.0
        var count = 0
        var rows = ArrayList<Transaction>()

        fun flush() {
            if (rows.isEmpty()) return
            out += Entry.DayHead(dayStart, label, expense, income, count)
            rows.forEach { out += Entry.Row(it) }
            rows = ArrayList()
            expense = 0.0
            income = 0.0
            count = 0
        }

        for (t in sorted) {
            val d = MonthWindow.dayStart(t.occurredAt)
            if (d != dayStart) {
                flush()
                dayStart = d
                label = MonthWindow.dayLabel(t.occurredAt)
            }
            if (t.direction == Direction.EXPENSE) expense += t.amount else income += t.amount
            count++
            rows += t
        }
        flush()
        return out
    }
}
