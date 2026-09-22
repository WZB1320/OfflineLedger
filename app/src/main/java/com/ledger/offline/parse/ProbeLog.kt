package com.ledger.offline.parse

/**
 * 通知采集的「黑匣子」：最近若干条目标 App 通知的原文与判定结果。
 *
 * ## 为什么需要它
 * 采集链路是**被动旁听**：通知来了才干活，没来就完全静默。
 * 于是「用户说有通知但没记账」这种报障，在没有任何记录的前提下
 * 根本无法区分下面三种情况——而它们的修法完全不同：
 *
 *   1. 服务压根没连上（ROM 杀了 / 从未 bind）      → 要去加白名单
 *   2. 收到了通知，但包名不在白名单里              → 要补 packageNames
 *   3. 收到了也命中包名，但被正则的某一关拦下       → 要改 parser_rules.json
 *
 * 前两种一个字节都不会写进数据库，第三种写之前就被丢了。
 * 没有这个黑匣子，三种都表现为同一个现象：「什么都没发生」。
 *
 * ## 为什么默认是关的
 * 它要存的是通知**原文**（标题 + 正文），里面有金额、商户名。
 * 本 App 的库是字段级加密的，把明文原文另存一份属于退让，
 * 所以默认关闭、开启时要说清楚、并且随时可清空。
 * 数据仍然只在本机：存 SharedPreferences 私有目录，与网络无关。
 */
object ProbeLog {

    /** 环形缓冲上限。够看清最近的几次支付，又不至于长期囤积明文 */
    const val LIMIT = 20

    /**
     * 「同一通知重发」的判定窗口：同标题同正文、且到达间隔在这个窗口内，
     * 才算同一条通知的反复推送（微信刷时间、支付宝更新进度，都发生在几秒内）。
     * 间隔超过窗口的同文案通知是**另一笔同金额的交易**——连付两笔 1 元时
     * 通知原文一字不差，按文案无限期去重会让诊断列表只剩一条，
     * 「第二笔没被记录」的假象会把排查带偏（2026-09-22 真机实测踩中）。
     */
    const val RESEND_WINDOW_MS = 60_000L

    private const val SEP = '\u0001'
    private const val NULL = ""

    data class ProbeEntry(
        val at: Long,
        val pkg: String,
        val sourceId: String,
        val title: String,
        val text: String,
        /** 入账结果：ADDED / BACKFILLED / DUPLICATE；被丢弃时为 null */
        val outcome: String?,
        /** 被丢弃的原因；入账成功时为 null */
        val drop: DropReason?
    ) {
        val accepted: Boolean get() = outcome != null
    }

    // ------------------------------------------------------------ 编解码

    /**
     * 自己实现分隔编码，不用 org.json：
     * org.json 在 JVM 单测里是 stub（走了什么都验不到），而这个编解码
     * 恰好是最需要被单测钉住的部分——分隔与转义错了会静默丢字段。
     */
    fun encodeAll(list: List<ProbeEntry>): String = list.joinToString("\n") { encode(it) }

    fun decodeAll(text: String): List<ProbeEntry> =
        if (text.isBlank()) emptyList()
        else text.split('\n').mapNotNull { decode(it) }

    /** 追加一条并截断到 [limit]，保留最新的（末尾是最新） */
    fun push(list: List<ProbeEntry>, entry: ProbeEntry, limit: Int = LIMIT): List<ProbeEntry> {
        // 先去重再加，顺序不能反——先加再去重的话，data class 的结构相等
        // 会把刚加的那条也一起删掉。去重受 [RESEND_WINDOW_MS] 约束（见其文档）。
        val out = ArrayList(list.filterNot {
            it.title == entry.title && it.text == entry.text &&
                entry.at - it.at < RESEND_WINDOW_MS
        })
        out.add(entry)
        return if (out.size > limit) out.takeLast(limit) else out
    }

    private fun encode(e: ProbeEntry): String = listOf(
        e.at.toString(),
        e.pkg,
        e.sourceId,
        e.title,
        e.text,
        e.outcome ?: NULL,
        e.drop?.name ?: NULL
    ).joinToString(SEP.toString()) { escape(it) }

    private fun decode(line: String): ProbeEntry? {
        val parts = line.split(SEP)
        if (parts.size != 7) return null
        val at = parts[0].toLongOrNull() ?: return null
        val outcome = parts[5].takeIf { it.isNotEmpty() }
        val drop = parts[6].takeIf { it.isNotEmpty() }?.let {
            runCatching { DropReason.valueOf(it) }.getOrNull()
        }
        return ProbeEntry(
            at = at,
            pkg = unescape(parts[1]),
            sourceId = unescape(parts[2]),
            title = unescape(parts[3]),
            text = unescape(parts[4]),
            outcome = outcome,
            drop = drop
        )
    }

    /** 换行会破坏「一行一条」的格式，分隔符本身也要转义 */
    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace(SEP.toString(), "")

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> append('\n')
                    'r' -> append('\r')
                    '\\' -> append('\\')
                    else -> append(s[i + 1])
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }
}

/**
 * 一条通知被丢弃的原因，对应解析链路上的每一道闸。
 *
 * 标签直接给用户看，所以写「人话」而不是枚举名：
 * 用户要能自己读出「哦，是金额没匹配上」，而不是拿到一个 NO_AMOUNT 去猜。
 */
enum class DropReason(val label: String) {
    /** 通知是空的（静默通知 / 进度条类），连文本都取不到 */
    EMPTY("通知没有可解析的文本"),

    /** 命中 ignoreIfContains：营销、红包、签到之类 */
    IGNORED("命中忽略词（营销 / 红包类推送）"),

    /** amountPattern 没匹配到。最常见的原因是文案里的金额写作「¥128.50」而规则要求「元」 */
    NO_AMOUNT("没匹配到金额（检查 amountPattern）"),

    /** 金额小于 minAmount */
    AMOUNT_TOO_SMALL("金额低于阈值"),

    /** 收支关键词一个都没命中。生活缴费的「缴费成功」就是典型——表里只有付款 / 支付 / 扣款 */
    NO_DIRECTION("判断不出收支方向（缺关键词）")
}
