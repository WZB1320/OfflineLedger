package com.ledger.offline.data

/**
 * 金额输入框的文本 → 金额。
 *
 * 为什么单独抽出来（且刻意不放 android 依赖）：记一笔与「修改记录」两处都要判
 * 「这段文本是不是一个能入账的金额」，各自写一条正则迟早有一处漏掉多位小数。
 * 放在 data 包是为了能在 JVM 单测里把边界钉住。
 *
 * 规则刻意**宽容**：只挡「根本构不成金额」的输入（含非数字、超过两位小数、零、
 * 超出量级），不自动改写用户正在敲的文本——改写会把光标弹到末尾，
 * 中间插入一个字符就被顺手挪走，这在手机上很难受。
 */
object AmountInput {

    /** 上限：10 亿。日常记账到不了这个量级，超过基本是手滑多点几个 0 */
    const val MAX_VALUE = 1_000_000_000.0

    /** 形状：整数部分任意位 + 可选小数点 + 至多两位小数 */
    private val SHAPE = Regex("""\d*\.?\d{0,2}""")

    /**
     * @return 合法金额，或 null（空 / 非数字 / 小数超过两位 / ≤ 0 / 超上限）
     */
    fun parse(raw: String): Double? {
        val s = raw.trim()
        if (s.isEmpty() || !s.matches(SHAPE)) return null
        if (s.none { it.isDigit() }) return null          // 单独一个小数点
        val value = s.toDoubleOrNull() ?: return null
        if (value <= 0.0 || value > MAX_VALUE) return null
        return value
    }
}
