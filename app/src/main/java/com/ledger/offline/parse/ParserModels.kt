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
    val seedMap: Map<String, String>
)

/** 解析结果：还没归类、还没落库的中间态 */
data class ParsedTransaction(
    val amount: Double,
    val direction: Direction,
    /** 从文案里抠出来的原始收款方，可能带门店后缀、公司全称 */
    val merchantRaw: String,
    val occurredAt: Long,
    val sourceId: String
)
