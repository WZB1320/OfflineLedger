package com.ledger.offline.parse

import com.ledger.offline.data.model.Direction

/**
 * 一条来源规则。对应 assets/parser_rules.json 里的一个 sources 元素。
 *
 * 之所以把正则、关键词全部外置成 JSON，而不是写死在 Kotlin 里：
 * 微信 / 支付宝大约每几个月就会调整一次通知文案或账单页结构。
 * 硬编码意味着每次都要重新编译、重新装包；外置规则只要让用户把
 * 新规则文件丢到 filesDir/parser_rules.json 就能热替换。
 */
data class SourceRule(
    val id: String,
    val enabled: Boolean,
    val packageNames: List<String>,
    val titlePatterns: List<String>,
    val ignoreIfContains: List<String>,
    val amountRegex: Regex,
    val expenseKeywords: List<String>,
    val incomeKeywords: List<String>,
    val merchantRegexes: List<Regex>,
    val minAmount: Double
) {
    fun matchesPackage(pkg: String): Boolean = packageNames.any { pattern ->
        when {
            pattern == "*" -> true
            pattern.startsWith("*") && pattern.endsWith("*") ->
                pkg.contains(pattern.trim('*'), ignoreCase = true)
            pattern.endsWith("*") -> pkg.startsWith(pattern.trimEnd('*'), ignoreCase = true)
            else -> pkg.equals(pattern, ignoreCase = true)
        }
    }
}

data class ParserRules(
    val version: Int,
    val sources: List<SourceRule>,
    /** 账单文件（xlsx / csv）的导入档案，见 ImportProfile */
    val importProfiles: List<ImportProfile> = emptyList()
) {

    /** 按包名找来源规则；命中后才值得做正则解析，省 CPU 和电量 */
    fun sourceFor(packageName: String): SourceRule? =
        sources.firstOrNull { it.enabled && it.matchesPackage(packageName) }

    /**
     * 有些来源（例如「服务通知」）标题不固定，
     * 需要在解析失败时再按标题关键词兜底匹配一次。
     */
    fun sourceForTitle(title: String): SourceRule? =
        sources.firstOrNull { it.enabled && it.titlePatterns.any { p -> title.contains(p) } }
}

/**
 * 一份「账单导入档案」——描述某个平台的导出文件长什么样、怎么读。
 *
 * 之所以也外置成 JSON：账单格式改动不需重新出包；
 * 而且两家的列名差异（"金额(元)" vs "金额"、有无「交易分类」列）
 * 全部体现在配置里，代码里只有一套通用逻辑。
 */
data class ImportProfile(
    val id: String,
    val displayName: String,
    /** 表头特征列，用于识别这份文件属于哪个平台 */
    val headerSignatures: List<String>,
    /** 命中多少个特征才算识别成功（防止误判） */
    val minSignatureHits: Int,
    /** 字段名 → 可能的表头名（按顺序取第一个命中的） */
    val columns: Map<String, List<String>>,
    val incomeTokens: List<String>,
    val expenseTokens: List<String>,
    /** 不计收支（转账/提现/理财申购等），跳过但单独计数 */
    val neutralTokens: List<String>,
    /** 状态里出现这些词的单子不入账（退款/关闭/失败），否则会虚增支出 */
    val dropStatusTokens: List<String>,
    /** 官方分类列名，空表示该平台没有 */
    val seedColumn: String,
    /** 官方分类值 → 本 App 的 categoryId */
    val seedMap: Map<String, String>,
    /** 0 元「下单占位行」的识别特征与处置 */
    val placeholder: PlaceholderPolicy = PlaceholderPolicy(),
    /** 退款/关闭行的处置口径，目前只支持 count_only，见 Policy.REFUND_COUNT_ONLY */
    val refundPolicy: String = Policy.REFUND_COUNT_ONLY
)

/**
 * 0 元「下单占位行」的处置规则。
 *
 * 背景（2026-09-17 用真实支付宝账单测出）：淘宝/天猫走**担保交易**——
 * 下单时先落一行「交易创建」，金额 0.00、支付方式为空、状态却是「支付成功」；
 * 确认收货后（实测恰好 10 天）才真实扣款，并生成**独立的第二行**（带真金额 + 花呗）。
 * 所以那行 0 元不是消费，把它入账就是与 10 天后的付款行重复记账。
 *
 * 把「怎么认出它」也做成配置而不是写死 if：平台换个状态词或改成填支付方式，
 * 只改 JSON 即可，不必重新出包。
 */
data class PlaceholderPolicy(
    /** 命中后怎么办。目前只实现 drop */
    val action: String = Policy.PLACEHOLDER_DROP,
    /** 状态列必须包含其中之一（空列表 = 不校验状态） */
    val statusTokens: List<String> = emptyList(),
    /** 是否要求「收/付款方式」列为空 */
    val paymentMustBeBlank: Boolean = false
) {
    /**
     * 能否用这套签名断定「这行确实是下单占位」。
     * 返回 false 时调用方会把它记进「无法解析」——那是**规则没覆盖到**的信号，
     * 需要有别于「已确认的占位行」，不能混为一谈。
     */
    fun signatureMatches(status: String, payment: String): Boolean {
        if (action != Policy.PLACEHOLDER_DROP) return false
        if (statusTokens.isNotEmpty() && statusTokens.none { status.contains(it) }) return false
        if (paymentMustBeBlank && payment.isNotBlank()) return false
        return true
    }
}

/** 账务口径常量。与 parser_rules.json 里的取值一一对应，改动需同步校验脚本 */
object Policy {
    const val PLACEHOLDER_DROP = "drop"
    /** 退款不冲减支出，仅计数提示 —— 理由见 parser_rules.json 的 policies.refundNote */
    const val REFUND_COUNT_ONLY = "count_only"
}

/** 解析结果：还没归类、还没落库的中间态 */
data class ParsedTransaction(
    val amount: Double,
    val direction: Direction,
    /** 从文案里抠出来的原始收款方，可能带门店后缀、公司全称 */
    val merchantRaw: String,
    val occurredAt: Long,
    val sourceId: String
)
