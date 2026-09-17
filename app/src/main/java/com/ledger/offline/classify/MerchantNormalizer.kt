package com.ledger.offline.classify

/**
 * 商户名归一化。
 *
 * 原始文本可能是「星巴克(浦东世纪汇店)」「星巴克（上海）有限公司」
 * 「支付宝-星巴克」，归一化后统一成「星巴克」，
 * 这样同一家店的历史记录才会被规则引擎和用户记忆归到一起。
 *
 * 这里刻意做得保守：只剥掉明确无意义的后缀，
 * 宁可少归一，也不要把两家不同的店合并成一家。
 */
object MerchantNormalizer {

    /** 括号内容：(浦东世纪汇店)、（上海）有限公司、【xx】 */
    private val BRACKET = Regex("[（(【\\[][^）)】\\]]{0,30}[）)】\\]]")

    /** 平台前缀：支付宝-星巴克 / 微信支付-星巴克 / 银联-星巴克 */
    private val PLATFORM_PREFIX = Regex("^(支付宝|微信支付|微信|银联|云闪付|财付通)\\s*[-—·:：]\\s*")

    /** 无意义结尾 */
    private val TAIL_NOISE = Regex("(门店|分店|专营店|旗舰店|直营店|店|公司|分公司)$")

    /** 企业全称尾巴 */
    private val COMPANY_SUFFIX = Regex(
        "(有限责任公司|股份有限公司|有限公司|科技有限公司|商贸有限公司|商贸公司|服务有限公司)$"
    )

    private val CONSECUTIVE_SPACE = Regex("\\s{2,}")

    fun normalize(raw: String): String {
        if (raw.isBlank()) return UNKNOWN_MERCHANT

        var s = raw.trim()
        s = PLATFORM_PREFIX.replace(s, "")
        s = BRACKET.replace(s, "")
        s = COMPANY_SUFFIX.replace(s, "")
        s = TAIL_NOISE.replace(s, "")
        s = CONSECUTIVE_SPACE.replace(s, " ")
        s = s.trim().trim('-', '—', '·', ':', '：')

        return if (s.isEmpty()) raw.trim().take(20) else s
    }

    const val UNKNOWN_MERCHANT = "未识别商户"
}
