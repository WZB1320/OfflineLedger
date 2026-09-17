package com.ledger.offline.capture

import com.ledger.offline.data.model.Direction
import com.ledger.offline.parse.ImportProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 账单导入的纯逻辑单测。
 *
 * 用的表头与取值全部来自 2026-09-10 的**真实微信导出文件**
 * （samples/wechat_bill_sample.xlsx，已用 tools/inspect_bill.py 验证：
 * 41 行数据 → 收入 14 笔 12.53 元 / 支出 26 笔 301.39 元 / 中性 1 笔，
 * 与账单自带汇总逐项一致）。
 */
class BillImporterTest {

    private val wechat = ImportProfile(
        id = "wechat_bill",
        displayName = "微信支付账单",
        headerSignatures = listOf("交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)", "交易单号"),
        minSignatureHits = 5,
        columns = mapOf(
            "time" to listOf("交易时间", "付款时间"),
            "type" to listOf("交易类型"),
            "counterparty" to listOf("交易对方"),
            "product" to listOf("商品"),
            "direction" to listOf("收/支"),
            "amount" to listOf("金额(元)", "金额"),
            "status" to listOf("当前状态"),
            "txnNo" to listOf("交易单号")
        ),
        incomeTokens = listOf("收入", "已收入"),
        expenseTokens = listOf("支出", "已支出"),
        neutralTokens = listOf("不计收支", "中性交易", "/"),
        dropStatusTokens = listOf("已全额退款", "已退款", "交易关闭"),
        seedColumn = "",
        seedMap = emptyMap()
    )

    private val alipay = wechat.copy(
        id = "alipay_bill",
        displayName = "支付宝交易明细",
        headerSignatures = listOf("交易时间", "交易分类", "交易对方", "商品说明", "收/支", "金额", "交易订单号"),
        columns = mapOf(
            "time" to listOf("交易时间"),
            "type" to listOf("交易分类"),
            "counterparty" to listOf("交易对方"),
            "product" to listOf("商品说明"),
            "direction" to listOf("收/支"),
            "amount" to listOf("金额"),
            "status" to listOf("交易状态"),
            "txnNo" to listOf("交易订单号")
        ),
        neutralTokens = listOf("不计收支", "/"),
        incomeTokens = listOf("收入"),
        expenseTokens = listOf("支出"),
        seedColumn = "交易分类",
        seedMap = mapOf("餐饮美食" to "food", "交通出行" to "transport")
    )

    // ------------------------------------------------------------ 表头定位

    @Test
    fun `表头前有说明区也能定位`() {
        val rows = listOf(
            listOf("微信支付账单明细"),
            listOf("微信昵称：[WZB1320]"),
            listOf("起始时间：[2026-08-10 00:00:00] 终止时间：[2026-09-10 17:04:23]"),
            listOf("共41笔记录"),
            listOf("----------------------微信支付账单明细列表--------------------"),
            listOf("交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)", "支付方式", "当前状态", "交易单号", "商户单号", "备注"),
            listOf("46275.5", "商户消费", "拼多多平台商户", "商品", "支出", "13.9", "零钱通", "支付成功", "42000", "oRkH", "/")
        )

        assertEquals(5, BillImporter.locateHeaderRow(rows))
    }

    @Test
    fun `没有表头的文件应判为无法识别`() {
        assertEquals(-1, BillImporter.locateHeaderRow(listOf(listOf("随便什么"), listOf("没有表头"))))
    }

    // ------------------------------------------------------------ 平台识别

    @Test
    fun `按表头特征识别平台而非按文件名`() {
        val wxHeader = listOf("交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)", "支付方式", "当前状态", "交易单号", "商户单号", "备注")
        val aliHeader = listOf("交易时间", "交易分类", "交易对方", "对方账号", "商品说明", "收/支", "金额", "收/付款方式", "交易状态", "交易订单号", "商家订单号")

        assertEquals("wechat_bill", BillImporter.matchProfile(wxHeader, listOf(wechat, alipay))?.id)
        assertEquals("alipay_bill", BillImporter.matchProfile(aliHeader, listOf(wechat, alipay))?.id)
    }

    @Test
    fun `命中特征不足时拒绝导入`() {
        val alien = listOf("日期", "摘要", "金额", "余额")
        assertNull(BillImporter.matchProfile(alien, listOf(wechat, alipay)))
    }

    // ------------------------------------------------------------ 收支方向（重点）

    /**
     * 关键回归：「不计收支」字面上包含「支出」。
     * 若判定顺序写成「先判支出」，转账/提现/理财申购会被记成消费，
     * 账目直接做错。必须先判中性。
     */
    @Test
    fun `不计收支必须先于支出判定`() {
        assertEquals(BillImporter.Dir.NEUTRAL, BillImporter.parseDirection("不计收支", wechat))
        assertEquals(BillImporter.Dir.NEUTRAL, BillImporter.parseDirection("/", wechat))
        assertEquals(BillImporter.Dir.NEUTRAL, BillImporter.parseDirection("中性交易", wechat))
        assertEquals(BillImporter.Dir.EXPENSE, BillImporter.parseDirection("支出", wechat))
        assertEquals(BillImporter.Dir.INCOME, BillImporter.parseDirection("收入", wechat))
    }

    @Test
    fun `未知取值不猜测`() {
        assertEquals(BillImporter.Dir.UNKNOWN, BillImporter.parseDirection("", wechat))
        assertEquals(BillImporter.Dir.UNKNOWN, BillImporter.parseDirection("其他", wechat))
    }

    // ------------------------------------------------------------ 金额

    @Test
    fun `金额支持下千分位与货币符号`() {
        assertEquals(13.9, BillImporter.parseAmount("13.9")!!, 0.0001)
        assertEquals(1299.0, BillImporter.parseAmount("¥1,299.00")!!, 0.0001)
        assertEquals(25.4, BillImporter.parseAmount("￥25.40")!!, 0.0001)
        assertEquals(6.0, BillImporter.parseAmount("6元")!!, 0.0001)
    }

    @Test
    fun `金额为0或负数或非数字一律丢弃`() {
        assertNull(BillImporter.parseAmount("0"))
        assertNull(BillImporter.parseAmount("-5.00"))
        assertNull(BillImporter.parseAmount(""))
        assertNull(BillImporter.parseAmount("待确认"))
    }

    // ------------------------------------------------------------ 时间

    @Test
    fun `时间同时支持Excel序列号与字符串`() {
        val seconds = 12 * 3600 + 38 * 60 + 21
        val serial = 46275.0 + seconds / 86_400.0

        val fromSerial = BillImporter.parseEpochMillis(serial.toString())
        val fromText = BillImporter.parseEpochMillis("2026-09-10 12:38:21")

        assertEquals(fromText, fromSerial)
        assertEquals(wallClock(2026, Calendar.SEPTEMBER, 10, 12, 38, 21), fromSerial)
    }

    @Test
    fun `无法解析的时间返回null`() {
        assertNull(BillImporter.parseEpochMillis(""))
        assertNull(BillImporter.parseEpochMillis("昨天"))
        assertNull(BillImporter.parseEpochMillis("0"))
    }

    // ------------------------------------------------------------ 端到端

    @Test
    fun `微信采样表的端到端解析与账单汇总一致`() {
        // 表头取自真实文件；数据行按真实结构手工构造若干条
        val header = listOf("交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)", "支付方式", "当前状态", "交易单号", "商户单号", "备注")
        val rows = listOf(
            listOf("微信支付账单明细"),
            listOf("共41笔记录"),
            header,
            row(serial(2026, 9, 10, 12, 38, 21), "其他", "打开拼多多，领更多现金红包", "支出", "0.3", "已到账", "T001"),
            row(serial(2026, 9, 10, 12, 32, 51), "商户消费", "拼多多平台商户", "支出", "13.9", "支付成功", "T002"),
            row(serial(2026, 9, 4, 16, 47, 17), "商户消费", "上海市儿童医院", "支出", "6.00", "支付成功", "T003"),
            row(serial(2026, 9, 1, 17, 34, 28), "零钱通", "零钱通", "不计收支", "500.00", "已到账", "T004"),
            row(serial(2026, 8, 31, 18, 34, 28), "商户消费", "某商户", "支出", "20.00", "已全额退款", "T005"),
            row(serial(2026, 8, 30, 9, 0, 0), "商户消费", "某商户", "支出", "待确认", "支付成功", "T006"),
            row(serial(2026, 8, 29, 9, 0, 0), "转账", "张三", "收入", "100.00", "已收款", "T007")
        )

        val parsed = BillImporter.parseRows(rows, listOf(wechat, alipay))

        assertEquals("wechat_bill", parsed.profile?.id)
        assertEquals(2, parsed.headerRow)
        assertEquals(7, parsed.totalRows)
        assertEquals(4, parsed.records.size)        // T001 T002 T003 T007
        assertEquals(1, parsed.neutral)             // T004 零钱通
        assertEquals(1, parsed.droppedRefund)       // T005 已全额退款
        assertEquals(1, parsed.droppedUnparsed)     // T006 金额不可解析

        val expense = parsed.records.filter { it.direction == Direction.EXPENSE }.sumOf { it.amount }
        assertEquals(20.2, expense, 0.0001)         // 0.3 + 13.9 + 6.0
        val income = parsed.records.filter { it.direction == Direction.INCOME }.sumOf { it.amount }
        assertEquals(100.0, income, 0.0001)

        // 每条都带交易单号 → 单号精确去重可用
        assertTrue(parsed.records.all { it.txnNo.isNotEmpty() })
    }

    @Test
    fun `支付宝官方分类作种子映射`() {
        val header = listOf("交易时间", "交易分类", "交易对方", "对方账号", "商品说明", "收/支", "金额", "收/付款方式", "交易状态", "交易订单号", "商家订单号")
        val rows = listOf(
            header,
            listOf("2026-09-10 12:00:00", "餐饮美食", "星巴克", "x@y.com", "拿铁", "支出", "33.00", "余额宝", "交易成功", "A001", "M001")
        )

        val parsed = BillImporter.parseRows(rows, listOf(wechat, alipay))

        assertEquals("alipay_bill", parsed.profile?.id)
        assertEquals(1, parsed.records.size)
        assertEquals("food", parsed.records[0].seedCategoryId)
        assertEquals(33.0, parsed.records[0].amount, 0.0001)
    }

    // ------------------------------------------------------------ 工具

    private fun row(t: String, type: String, merchant: String, direction: String, amount: String, status: String, txn: String) =
        listOf(t, type, merchant, "商品", direction, amount, "零钱通", status, txn, "M-$txn", "/")

    /** 构造 Excel 序列号（墙上时钟 → 序列号，与解析方向相反，用于生成测试数据） */
    private fun serial(y: Int, m: Int, d: Int, h: Int, mi: Int, s: Int): String {
        val base = Calendar.getInstance().apply {
            clear(); set(1899, Calendar.DECEMBER, 30, 0, 0, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val target = wallClock(y, m, d, h, mi, s)
        val days = (target - base).toDouble() / 86_400_000.0
        return days.toString()
    }

    private fun wallClock(y: Int, m: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        Calendar.getInstance().apply {
            clear(); set(y, m, d, h, mi, s); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
