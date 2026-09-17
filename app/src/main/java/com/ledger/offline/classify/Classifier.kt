package com.ledger.offline.classify

import com.ledger.offline.parse.CategoryRule
import com.ledger.offline.parse.ClassifyRules

data class Classification(
    val categoryId: String,
    val categoryName: String,
    /** true = 引擎自动判定；false = 用户改过 */
    val auto: Boolean = true,
    /** true = 命中用户修正记忆 */
    val fromMemory: Boolean = false
)

/**
 * 本地归类引擎。三级判定，优先级从高到低：
 *
 *   1) 用户修正记忆 —— 用户手动改过一次，永久生效。用得越久越准。
 *   2) 关键词规则   —— 匹配商户名；匹配不到再退一步匹配整段文案。
 *   3) fallback     —— 「其他」。
 *
 * 为什么不用 AI 模型：
 * 一个最小的文本分类模型（TFLite）加上词表也要 5~30 MB，
 * 是本 App 全部体积的十倍以上；而准确率并不比「规则 + 用户修正记忆」高。
 * 对「绝对离线 + 极小体积」这个约束来说，模型是纯负收益。
 *
 * 匹配策略：按命中关键词的**长度**决胜，而不是按类目顺序。
 * 「美团外卖」同时包含「美团」和「外卖」时，长关键词更具体、更可信。
 */
class Classifier(
    private val rules: ClassifyRules,
    private val memoryProvider: () -> Map<String, Pair<String, String>>,
    private val merchantHasher: (String) -> String
) {

    /**
     * @param seedCategoryId 账单文件自带的官方分类（目前只有支付宝有「交易分类」列）。
     *        定位是**种子而非终裁**：用户修正记忆与自有关键词规则优先，
     *        它们都没命中时才用官方分类兜住，避免大量「其他」。
     */
    fun classify(
        rawMerchant: String,
        rawText: String = "",
        seedCategoryId: String? = null
    ): Classification {
        val merchant = MerchantNormalizer.normalize(rawMerchant)

        // 1) 用户修正记忆
        memoryProvider()[merchantHasher(merchant)]?.let { (id, name) ->
            return Classification(id, name, auto = true, fromMemory = true)
        }

        // 2) 关键词：先只看商户名
        matchByKeyword(merchant)?.let { return Classification(it.id, it.name) }

        // 3) 再看整段文案（通知正文里经常带业务描述，比如「话费充值」）
        if (rawText.isNotBlank()) {
            matchByKeyword(rawText)?.let { return Classification(it.id, it.name) }
        }

        // 4) 账单自带的官方分类兜底
        if (!seedCategoryId.isNullOrBlank()) {
            rules.byId(seedCategoryId)?.let { return Classification(it.id, it.name) }
        }

        // 5) 兜底
        return Classification(rules.fallback.id, rules.fallback.name)
    }

    private fun matchByKeyword(haystack: String): CategoryRule? {
        var best: CategoryRule? = null
        var bestLength = 0
        for (category in rules.categories) {
            for (keyword in category.keywords) {
                if (keyword.length > bestLength && haystack.contains(keyword)) {
                    best = category
                    bestLength = keyword.length
                }
            }
        }
        return best
    }
}
