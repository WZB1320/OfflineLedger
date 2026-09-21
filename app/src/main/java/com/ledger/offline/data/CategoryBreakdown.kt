package com.ledger.offline.data

import com.ledger.offline.data.model.Direction
import com.ledger.offline.data.model.Transaction
import com.ledger.offline.parse.CategoryRule

/**
 * 统计页的分类占比。纯 Kotlin，可在 JVM 单测里断言口径。
 *
 * ## 为什么不能在 SQL 里 GROUP BY
 * `amount_enc` / `merchant_enc` 是密文，SQL 既不能 `SUM(amount)` 也不能
 * `GROUP BY merchant`。只能先把明文 `category_id` 取出来，再在内存里累加
 * （几千行量级毫无压力）。
 *
 * ## 「未分类」与「其他」的关系（做统计前必读）
 * 没有商户名 ⇒ `MerchantNormalizer` 返回常量「未识别商户」⇒ 必然不命中任何关键词
 * ⇒ `category_id` 同样落到 fallback `other`。所以两者在库里是**同一个 id**，
 * 是包含关系、不是并列。条形图拆开画（提示该补商户名），
 * 但 §9 的「未分类占比 < 15%」验收必须按**合并口径**算，只算「未分类」会漏掉一半。
 */
object CategoryBreakdown {

    /** 验收线：方案 §9「未分类占比 < 15%」。UI 层不许再写一遍这个数字 */
    const val ACCEPT_RATIO = 0.15

    /** 无商户名的那一片（未分类）在条形图上的颜色，取自设计稿的 warn 色 */
    const val UNCLASSIFIED_COLOR = "#B45309"

    const val UNCLASSIFIED_LABEL = "未分类（无商户名）"
    const val OTHER_LABEL = "其他（有商户·未命中）"

    data class Slice(
        val categoryId: String,
        val name: String,
        val amount: Double,
        val count: Int,
        /** true = 无商户名那一片（未分类）；false = 有商户名但关键词没命中（其他）或普通分类 */
        val unclassified: Boolean,
        val colorHex: String,
        /** 占支出总额的比例；总额为 0 时统一为 0.0，避免出现 NaN */
        val ratio: Double
    )

    data class Result(
        val slices: List<Slice>,
        val totalExpense: Double,
        val unclassifiedAmount: Double,
        /** 合并口径：未分类 + 其他，除以支出总额 */
        val unclassifiedRatio: Double,
        val count: Int
    ) {
        fun accepted(): Boolean = unclassifiedRatio < ACCEPT_RATIO
    }

    fun of(
        txns: List<Transaction>,
        categories: List<CategoryRule>,
        fallback: CategoryRule,
        unknownMerchant: String
    ): Result {
        val expense = txns.filter { it.direction == Direction.EXPENSE }
        val total = expense.sumOf { it.amount }
        val byId = categories.associateBy { it.id }

        // 有商户名 / 无商户名分开累加：两者 category_id 相同，只有 merchant 能区分
        var fallbackKnown = 0.0
        var fallbackKnownCount = 0
        var fallbackUnknown = 0.0
        var fallbackUnknownCount = 0
        val sums = HashMap<String, Double>()
        val counts = HashMap<String, Int>()

        for (t in expense) {
            if (t.categoryId == fallback.id) {
                if (FlowList.isUnclassified(t, unknownMerchant)) {
                    fallbackUnknown += t.amount
                    fallbackUnknownCount++
                } else {
                    fallbackKnown += t.amount
                    fallbackKnownCount++
                }
                continue
            }
            sums[t.categoryId] = (sums[t.categoryId] ?: 0.0) + t.amount
            counts[t.categoryId] = (counts[t.categoryId] ?: 0) + 1
        }

        fun ratio(v: Double) = if (total <= 0.0) 0.0 else v / total

        val slices = ArrayList<Slice>()
        for ((id, amount) in sums) {
            val rule = byId[id] ?: continue
            slices += Slice(
                categoryId = id,
                name = rule.name,
                amount = amount,
                count = counts[id] ?: 0,
                unclassified = false,
                colorHex = rule.color.ifBlank { fallback.color },
                ratio = ratio(amount)
            )
        }
        if (fallbackUnknown > 0.0) {
            slices += Slice(
                categoryId = fallback.id,
                name = UNCLASSIFIED_LABEL,
                amount = fallbackUnknown,
                count = fallbackUnknownCount,
                unclassified = true,
                colorHex = UNCLASSIFIED_COLOR,
                ratio = ratio(fallbackUnknown)
            )
        }
        if (fallbackKnown > 0.0) {
            slices += Slice(
                categoryId = fallback.id,
                name = OTHER_LABEL,
                amount = fallbackKnown,
                count = fallbackKnownCount,
                unclassified = false,
                colorHex = fallback.color.ifBlank { CategoryRule.DEFAULT_FALLBACK_COLOR },
                ratio = ratio(fallbackKnown)
            )
        }
        slices.sortByDescending { it.amount }

        val unclassifiedTotal = fallbackUnknown + fallbackKnown
        return Result(
            slices = slices,
            totalExpense = total,
            unclassifiedAmount = unclassifiedTotal,
            unclassifiedRatio = ratio(unclassifiedTotal),
            count = expense.size
        )
    }
}
