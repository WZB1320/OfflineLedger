package com.ledger.offline.parse

import com.ledger.offline.data.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 纯 JVM 测试，不依赖 Android 运行时。
 * 覆盖的是整个项目风险最高的一环：通知文案能不能稳定解析出金额与收款方。
 */
class TransactionParserTest {

    private val wechat = source(
        id = "wechat",
        packages = listOf("com.tencent.mm"),
        ignore = listOf("红包", "零钱通收益"),
        amountPattern = "(?:¥|￥)\\s?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
        expense = listOf("已支付", "支付成功", "付款成功"),
        income = listOf("已收款", "收款到账", "退款"),
        merchants = listOf(
            "(?:向|付给|付款给)\\s*([^，,。\\s]+)",
            "^([^，,。]+?)(?:已支付|支付成功)"
        )
    )

    private val alipay = source(
        id = "alipay",
        packages = listOf("com.eg.android.AlipayGphone"),
        ignore = listOf("扫码领红包", "集五福"),
        amountPattern = "([0-9]+(?:\\.[0-9]{1,2})?)\\s?元",
        expense = listOf("付款成功", "支出", "已付款"),
        income = listOf("收款成功", "到账", "退款"),
        merchants = listOf(
            "在\\s*([^，,。]+?)\\s*(?:消费|付款|支出)",
            "(?:商户|收款方)[：:]\\s*([^，,。\\s]+)"
        )
    )

    @Test
    fun `微信付款通知能解析金额和收款方`() {
        val parsed = TransactionParser.parse(
            wechat,
            title = "微信支付",
            text = "向星巴克(浦东世纪汇店)付款成功 ¥25.00",
            postedAt = 1_700_000_000_000L
        ).txn
        assertNotNull(parsed)
        assertEquals(25.0, parsed!!.amount, 0.001)
        assertEquals(Direction.EXPENSE, parsed.direction)
        assertEquals("星巴克(浦东世纪汇店)", parsed.merchantRaw)
    }

    @Test
    fun `通知里没有收款方时不该瞎猜`() {
        // 真实情况里微信经常只推一条「已支付¥25.00」，不带商户。
        // 这时 merchantRaw 必须为空，交给上层标成「未识别商户」，
        // 而不是把「微信支付」当成商户名写进账本。
        val parsed = TransactionParser.parse(wechat, "微信支付", "已支付¥25.00", 0L).txn
        assertNotNull(parsed)
        assertEquals("", parsed!!.merchantRaw)
    }

    @Test
    fun `营销推送应被黑名单拦下`() {
        val parsed = TransactionParser.parse(wechat, "微信支付", "恭喜获得红包 ¥8.88", 0L).txn
        assertNull(parsed)
    }

    @Test
    fun `支付宝消费通知能解析`() {
        val parsed = TransactionParser.parse(
            alipay,
            title = "支付宝",
            text = "你在星巴克消费25.00元，付款成功",
            postedAt = 0L
        ).txn
        assertNotNull(parsed)
        assertEquals(25.0, parsed!!.amount, 0.001)
        assertEquals(Direction.EXPENSE, parsed.direction)
        assertEquals("星巴克", parsed.merchantRaw)
    }

    @Test
    fun `退款应判为收入而不是支出`() {
        val parsed = TransactionParser.parse(alipay, "支付宝", "退款成功，25.00元已到账", 0L).txn
        assertNotNull(parsed)
        assertEquals(Direction.INCOME, parsed!!.direction)
    }

    @Test
    fun `判断不了收支方向时宁缺毋滥`() {
        // 文案里有金额，但既没有支出关键词也没有收入关键词 —— 丢弃
        val parsed = TransactionParser.parse(alipay, "支付宝", "您有一笔25.00元的待处理事项", 0L).txn
        assertNull(parsed)
    }

    @Test
    fun `金额带千分位也能解析`() {
        val parsed = TransactionParser.parse(wechat, "微信支付", "已支付¥1,299.00", 0L).txn
        assertNotNull(parsed)
        assertEquals(1299.0, parsed!!.amount, 0.001)
    }

    // ------------------------------------------------------------ 丢弃原因

    @Test
    fun `丢弃时要说清死在哪一关`() {
        assertEquals(DropReason.IGNORED, drop(wechat, "微信支付", "恭喜获得红包 ¥8.88"))
        assertEquals(DropReason.NO_AMOUNT, drop(wechat, "微信支付", "支付成功，请查收"))
        assertEquals(DropReason.AMOUNT_TOO_SMALL, drop(wechat, "微信支付", "已支付¥0.00"))
        assertEquals(DropReason.NO_DIRECTION, drop(alipay, "支付宝", "您有一笔25.00元的待处理事项"))
        assertEquals(DropReason.EMPTY, drop(alipay, "", ""))
    }

    /**
     * 已知缺口：生活缴费（水 / 电 / 燃气）目前会被丢在方向这一关。
     *
     * 把缺口写成可执行断言，而不是只写在注释里——
     * 等拿到真实样本把「缴费 / 缴纳」补进关键词表后，这条会失败，
     * 逼着来更新它；否则规则改了、测试却还绿着，等于没记录。
     */
    @Test
    fun `生活缴费文案当前会被丢弃（已知缺口，待真实样本校准）`() {
        assertEquals(DropReason.NO_DIRECTION, drop(alipay, "生活缴费", "您已成功缴纳电费128.50元"))
        assertEquals(DropReason.NO_DIRECTION, drop(alipay, "支付宝", "缴费成功，电费 128.50 元"))
        // 金额写成 ¥ 时，连方向那一关都走不到——amountPattern 只认「数字+元」
        assertEquals(DropReason.NO_AMOUNT, drop(alipay, "支付宝", "燃气费缴费成功 ¥128.50"))
    }

    private fun drop(rule: SourceRule, title: String, text: String): DropReason? =
        TransactionParser.parse(rule, title, text, 0L).drop

    private fun source(
        id: String,
        packages: List<String>,
        ignore: List<String>,
        amountPattern: String,
        expense: List<String>,
        income: List<String>,
        merchants: List<String>
    ) = SourceRule(
        id = id,
        enabled = true,
        packageNames = packages,
        titlePatterns = emptyList(),
        ignoreIfContains = ignore,
        amountRegex = Regex(amountPattern),
        expenseKeywords = expense,
        incomeKeywords = income,
        merchantRegexes = merchants.map { Regex(it) },
        minAmount = 0.01
    )
}
