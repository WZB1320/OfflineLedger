package com.ledger.offline.capture

import com.ledger.offline.parse.ImportProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * 支付宝交易明细 CSV 的导入测试。
 *
 * 结构与取值全部来自 2026-09-17 校准用的真实导出文件
 * （samples/支付宝交易明细(20260810-20260910).csv，21154 字节，GBK 编码）：
 * 132 数据行 → 支出 51 笔 3907.27 元 / 不计收支 71 笔 / 退款·关闭 3 笔 /
 * 金额 0.00 共 7 笔未入账，笔数平衡 132 = 132；
 * 金额与账单自带汇总（支出 58 笔 3907.27 元、不计收支 74 笔 7176.56 元）逐项一致。
 *
 * 这个文件单独存在的理由：支付宝和微信踩的**不是同一批坑**。
 *   微信：xlsx，时间是 Excel 序列号，说明区 15 行
 *   支付宝：CSV，GBK 编码，时间是字符串，说明区 23 行，
 *          且「交易订单号」列 132/132 尾部带制表符、「商家订单号」为空时是 '\t' 而非空串
 * 这些差异靠单元测试拍不出来，只能拿真实样本对齐——所以把它们固化成用例。
 */
class AlipayImportTest {

    /** 与 assets/parser_rules.json 的 alipay_bill 分支逐字段一致 */
    private val alipay = ImportProfile(
        id = "alipay_bill",
        displayName = "支付宝交易明细",
        headerSignatures = listOf("交易时间", "交易分类", "交易对方", "商品说明", "收/支", "金额", "交易订单号", "商家订单号"),
        minSignatureHits = 5,
        columns = mapOf(
            "time" to listOf("交易时间"),
            "type" to listOf("交易分类"),
            "counterparty" to listOf("交易对方"),
            "product" to listOf("商品说明", "商品名称"),
            "direction" to listOf("收/支"),
            "amount" to listOf("金额", "金额(元)"),
            "payMethod" to listOf("收/付款方式", "付款方式", "支付方式"),
            "status" to listOf("交易状态", "当前状态"),
            "txnNo" to listOf("交易订单号", "交易号"),
            "merchantNo" to listOf("商家订单号"),
            "remark" to listOf("备注")
        ),
        incomeTokens = listOf("收入"),
        expenseTokens = listOf("支出"),
        neutralTokens = listOf("不计收支", "/"),
        dropStatusTokens = listOf("交易关闭", "已关闭", "已退款", "退款成功", "已撤销", "失败"),
        seedColumn = "交易分类",
        // 刻意不含「退款」：退款行一律是「不计收支」+「退款成功」，进不了账本，
        // 强行给它一个支出类别反而危险（详见 parser_rules.json 的 unmappedNote）
        seedMap = mapOf(
            "餐饮美食" to "food", "爱车养车" to "transport", "公共服务" to "living",
            "投资理财" to "investment", "信用借还" to "transfer"
        )
    )

    /** 微信档案（精简）：只用于确认支付宝文件不会被误判成微信 */
    private val wechat = ImportProfile(
        id = "wechat_bill",
        displayName = "微信支付账单",
        headerSignatures = listOf("交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)", "交易单号", "商户单号"),
        minSignatureHits = 5,
        columns = mapOf(
            "time" to listOf("交易时间"),
            "type" to listOf("交易类型"),
            "counterparty" to listOf("交易对方"),
            "product" to listOf("商品"),
            "direction" to listOf("收/支"),
            "amount" to listOf("金额(元)", "金额"),
            "status" to listOf("当前状态")
        ),
        incomeTokens = listOf("收入"),
        expenseTokens = listOf("支出"),
        neutralTokens = listOf("不计收支", "/"),
        dropStatusTokens = listOf("交易关闭", "已退款"),
        seedColumn = "",
        seedMap = emptyMap()
    )

    private val header = listOf(
        "交易时间", "交易分类", "交易对方", "对方账号", "商品说明", "收/支",
        "金额", "收/付款方式", "交易状态", "交易订单号", "商家订单号", "备注", ""
    )

    /**
     * 真实文件的前 23 行说明区：
     * 1 行分隔线 + 10 行导出信息 + 空行 + 提示标题 + 8 条特别提示 + 空行 + 1 行回单分隔线。
     * 微信那份只有 15 行——表头行号写死就必然有一家对不上。
     */
    private val preamble: List<List<String>> = buildList {
        add(listOf("-".repeat(84)))
        add(listOf("导出信息："))
        add(listOf("姓名：测试"))
        add(listOf("支付宝账户：139****3337"))
        add(listOf("起始时间：[2026-08-10 00:00:00]    终止时间：[2026-09-10 23:59:59]"))
        add(listOf("导出交易类型：[全部]"))
        add(listOf("导出时间：[2026-09-10 17:10:08]"))
        add(listOf("共132笔记录"))
        add(listOf("收入：0笔 0.00元"))
        add(listOf("支出：58笔 3907.27元"))
        add(listOf("不计收支：74笔 7176.56元"))
        add(listOf(""))
        add(listOf("特别提示："))
        add(listOf("1.本回单内容可表明支付宝受理了相应支付交易申请；"))
        add(listOf("2.请勿将本回单作为收款方发货的凭据使用；"))
        add(listOf("3.请勿使用本回单进行重复记账；"))
        add(listOf("4.本回单如经任何涂改、编造，均立即失去效力；"))
        add(listOf("5.部分账单如：充值提现、账户转存或者个人设置收支等不计入为收入或者支出，记为不计收支类；"))
        add(listOf("6.因统计逻辑不同，明细金额直接累加后，可能会和下方统计金额不一致；"))
        add(listOf("7.禁止将本回单用于非法用途；"))
        add(listOf("8.本明细仅供个人对账使用。"))
        add(listOf(""))
        add(listOf("------------------------支付宝支付科技有限公司  电子客户回单------------------------"))
    }

    // ------------------------------------------------------------ 结构

    @Test
    fun `说明区有23行也能定位表头`() {
        assertEquals(23, preamble.size)
        // 「导出交易类型」「不计收支：74笔」这些说明行不能把表头定位带偏
        assertEquals(23, BillImporter.locateHeaderRow(preamble + listOf(header)))
    }

    @Test
    fun `支付宝表头不会被误判成微信`() {
        assertEquals("alipay_bill", BillImporter.matchProfile(header, listOf(wechat, alipay))?.id)
    }

    @Test
    fun `列按表头名对齐而非列号`() {
        val cols = BillImporter.buildColumns(header.map { BillImporter.normalizeHeader(it) }, alipay)
        // 列名对上了才是按名取列；对不上返回 -1，测试直接失败
        assertEquals(0, cols["time"] ?: -1)
        assertEquals(5, cols["direction"] ?: -1)
        assertEquals(6, cols["amount"] ?: -1)
        assertEquals(8, cols["status"] ?: -1)
        assertEquals(9, cols["txnNo"] ?: -1)
        assertEquals(1, cols["seed"] ?: -1)      // 交易分类同时是种子列
    }

    // ------------------------------------------------------------ 编码

    /**
     * 支付宝 CSV 是 GBK。按 UTF-8 硬读会抛异常或全乱码，
     * 而金额/时间照样解析得出来——账目看着正常、商户名和分类全废。
     */
    @Test
    fun `GBK与UTF8两种编码都能解码`() {
        val text = "交易时间,交易分类,交易对方\n2026-09-10 12:00:00,餐饮美食,星巴克\n"
        assertEquals(text, CsvIo.decode(text.toByteArray(Charset.forName("GBK"))))
        assertEquals(text, CsvIo.decode(text.toByteArray(Charsets.UTF_8)))
    }

    // ------------------------------------------------------------ 单号

    @Test
    fun `交易单号尾部的制表符必须清掉`() {
        val rows = preamble + listOf(header) + listOf(
            dataRow("2026-09-10 14:35:40", "爱车养车", "中国石化", "92号车用汽油", "支出", "295.03", "交易成功", "2026091023001471351420469171\t", "\t")
        )

        val parsed = BillImporter.parseRows(rows, listOf(alipay))

        assertEquals(1, parsed.records.size)
        // 不清 Tab，单号精确去重永远匹配不上 → 同一笔被记两次
        assertEquals("2026091023001471351420469171", parsed.records[0].txnNo)
        assertFalse(parsed.records[0].txnNo.contains('\t'))
    }

    // ------------------------------------------------------------ 端到端

    @Test
    fun `端到端解析笔数平衡且金额与账单汇总一致`() {
        val rows = preamble + listOf(header) + listOf(
            // 石油/停车 → 爱车养车（真实样本里这类共 6 笔，此前全部落进「其他」）
            dataRow("2026-09-10 14:35:40", "爱车养车", "中国石化销售股份有限公司上海石油分公司", "嘉定沪太加油站-92号车用汽油(VIB)", "支出", "295.03", "交易成功", "A001\t", "\t"),
            // 社保缴费 → 公共服务（此前同样无映射）
            dataRow("2026-08-12 09:00:00", "公共服务", "国家税务总局上海市税务局", "社保缴费", "支出", "1492.00", "交易成功", "A002\t", "\t"),
            dataRow("2026-09-07 10:00:00", "餐饮美食", "星巴克", "拿铁", "支出", "33.00", "支付成功", "A003\t", "\t"),
            // 平台全 0 元订单：金额解析成功但为 0，单列一支计数
            dataRow("2026-09-03 09:27:35", "餐饮美食", "天**", "伊利舒化牛奶整箱", "支出", "0.00", "支付成功", "A004\t", "\t"),
            dataRow("2026-09-10 04:26:30", "投资理财", "余额宝", "余额宝-收益发放", "不计收支", "0.32", "交易成功", "A005\t", "\t"),
            dataRow("2026-09-10 08:12:06", "信用借还", "花呗", "花呗自动还款-2026年09月账单", "不计收支", "1616.03", "还款成功", "A006\t", "\t"),
            dataRow("2026-08-14 14:17:06", "退款", "坚朗**店", "退款-坚朗门窗五金件", "不计收支", "146.24", "退款成功", "A007\t", "\t"),
            dataRow("2026-08-15 22:41:26", "投资理财", "余额宝", "余额宝-单次转入", "不计收支", "1028.00", "交易关闭", "A008\t", "\t")
        )

        val parsed = BillImporter.parseRows(rows, listOf(alipay))

        assertEquals("alipay_bill", parsed.profile?.id)
        assertEquals(23, parsed.headerRow)
        assertEquals(8, parsed.totalRows)
        assertEquals(3, parsed.records.size)        // 中石化 295.03 / 社保 1492.00 / 星巴克 33.00
        // 注意：状态丢弃在方向判定之前执行，所以「不计收支 + 交易关闭」那行
        // 算进 droppedRefund 而不是 neutral——真实样本里 71 + 3 = 74 也遵循同一口径
        assertEquals(2, parsed.neutral)             // 余额宝收益、花呗还款
        assertEquals(2, parsed.droppedRefund)       // 退款成功、交易关闭
        assertEquals(1, parsed.zeroAmount)          // 金额 0.00
        assertEquals(0, parsed.droppedUnparsed)     // 没有一行是「解析不出来」

        // 笔数平衡：每一行都必须有归宿，不允许静默消失
        assertEquals(
            8,
            parsed.records.size + parsed.neutral + parsed.droppedRefund +
                parsed.zeroAmount + parsed.droppedUnparsed
        )

        assertEquals(1820.03, parsed.records.sumOf { it.amount }, 0.0001)
        assertTrue(parsed.records.all { it.txnNo.isNotEmpty() })
        assertTrue(parsed.records.none { it.txnNo.contains('\t') })

        // 官方分类种子：真实样本里出现过的分类值必须都能落到有效 categoryId
        assertEquals("transport", parsed.records.first { it.merchantRaw.contains("中国石化") }.seedCategoryId)
        assertEquals("living", parsed.records.first { it.merchantRaw.contains("税务") }.seedCategoryId)
        assertEquals("food", parsed.records.first { it.merchantRaw.contains("星巴克") }.seedCategoryId)
    }

    // ------------------------------------------------------------ 工具

    /** 13 列，末列空——支付宝每行都以逗号结尾，split 出来会多一个空列 */
    private fun dataRow(
        time: String, category: String, party: String, product: String,
        direction: String, amount: String, status: String, txn: String, merchantNo: String
    ) = listOf(time, category, party, "/", product, direction, amount, "花呗", status, txn, merchantNo, "", "")
}
