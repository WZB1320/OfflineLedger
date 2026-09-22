package com.ledger.offline.parse

import com.ledger.offline.data.model.Direction

/**
 * 通知 / 账单文本解析器。
 *
 * 设计取舍：**宁缺毋滥**。
 * 解析不出来、或者判断不了收支方向时直接返回 null 丢弃，
 * 而不是猜一个默认值。错误的账目比缺失的账目更糟糕——
 * 用户会失去对账本的信任，然后彻底不用它。
 */
object TransactionParser {

    /** 商户名上限，防止正则贪婪匹配吃进一整段文案 */
    private const val MAX_MERCHANT_LEN = 40

    fun parse(rule: SourceRule, title: String?, text: String?, postedAt: Long): ParseOutcome {
        val t = title?.trim().orEmpty()
        val c = text?.trim().orEmpty()
        val body = "$t $c".trim()
        if (body.isEmpty()) return ParseOutcome(null, DropReason.EMPTY)

        // 1. 黑名单：营销推送、红包、收益播报之类，直接扔掉
        if (rule.ignoreIfContains.any { body.contains(it) }) return ParseOutcome(null, DropReason.IGNORED)

        // 2. 金额
        val amount = rule.amountRegex.find(body)
            ?.groupValues?.getOrNull(1)
            ?.replace(",", "")
            ?.toDoubleOrNull()
            ?: return ParseOutcome(null, DropReason.NO_AMOUNT)
        if (amount < rule.minAmount) return ParseOutcome(null, DropReason.AMOUNT_TOO_SMALL)

        // 3. 收支方向。两个方向的关键词都不命中就不敢下判断，丢弃。
        val direction = detectDirection(rule, body)
            ?: return ParseOutcome(null, DropReason.NO_DIRECTION)

        // 4. 收款方
        val merchant = extractMerchant(rule, t, c)

        return ParseOutcome(
            ParsedTransaction(
                amount = amount,
                direction = direction,
                merchantRaw = merchant,
                occurredAt = postedAt,
                sourceId = rule.id
            ),
            null
        )
    }

    private fun detectDirection(rule: SourceRule, body: String): Direction? {
        // 退款 / 入账类文案优先判定为收入，避免「已退款」被误判成支出
        if (rule.incomeKeywords.any { body.contains(it) }) return Direction.INCOME
        if (rule.expenseKeywords.any { body.contains(it) }) return Direction.EXPENSE
        return null
    }

    private fun extractMerchant(rule: SourceRule, title: String, content: String): String {
        val haystack = "$title $content"
        for (regex in rule.merchantRegexes) {
            val raw = regex.find(haystack)?.groupValues?.getOrNull(1)?.trim()
            if (!raw.isNullOrBlank()) return clean(raw)
        }
        // 兜底：文案本身没给收款方，只留空，交给上层标成「未识别商户」
        return ""
    }

    private fun clean(raw: String): String {
        var s = raw.trim().trim('，', ',', '。', '、', '：', ':', '-', '—', ' ', '\t')

        // 正则很容易把「微信支付」「支付宝」这类应用名当成商户名抓出来。
        // 这类词直接丢弃，宁可标成「未识别商户」，也不要写进账本污染分类。
        if (s in NOISE_MERCHANTS) return ""

        // 从动词处截断：「星巴克(浦东世纪汇店)付款成功」→「星巴克(浦东世纪汇店)」
        for (word in STOP_WORDS) {
            val index = s.indexOf(word)
            if (index == 0) return ""
            if (index > 0) {
                s = s.substring(0, index)
                break
            }
        }

        s = s.trim().trim('，', ',', '。', '、', '：', ':', '-', '—', ' ')
        // 单字结果基本都是截断产生的噪声，丢弃
        return if (s.length < 2) "" else s.take(MAX_MERCHANT_LEN)
    }

    /** 会被正则误抓成商户名的应用名 */
    private val NOISE_MERCHANTS = setOf(
        "微信支付", "微信", "支付宝", "支付宝安全中心", "财付通",
        "微信收款助手", "服务通知", "云闪付", "银联", "余额宝"
    )

    /** 出现即代表商户名结束的动词 / 符号 */
    private val STOP_WORDS = listOf("付款", "支付", "消费", "支出", "已", "成功", "收款", "¥", "￥")
}
