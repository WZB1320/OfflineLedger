package com.ledger.offline.capture

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ledger.offline.data.model.Direction
import com.ledger.offline.parse.ImportProfile
import com.ledger.offline.parse.PlaceholderPolicy
import com.ledger.offline.parse.Policy
import com.ledger.offline.parse.xlsx.XlsxReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 真实账单样本回归测试（黄金样本）。
 *
 * ## 为什么必须单独存在
 * 在此之前，「**真实 assets 配置 + 真实账单文件**」这个组合没有任何测试覆盖：
 *
 *   - `tools/verify_parser_rules.py` 的 9 条用例**全部是模拟通知文案**，
 *     整个脚本连一次文件读取都没有（grep 不到 open/glob/argv）。它验的是
 *     通知解析规则，与账单导入是两条完全不同的代码路径。
 *   - `AlipayImportTest` / `BillImporterTest` 用的是**手写 9 行数据**和
 *     **手写 profile 副本**。手写副本会与 assets 漂移，而 9 行的分布和真实的
 *     132 行差一个量级——真实数据里「不计收支 71 笔 / 占位 7 笔 / 退款 3 笔」，
 *     手写数据里对应只有「2 / 1 / 2」。**光靠手写样本，永远测不出分布性缺陷。**
 *   - `parser_rules.json` 里那句「132 数据行 / 支出 51 笔 3907.27 元」是当年
 *     手工跑 Python 探针得出的，之后**没有任何机制能重新验证它**——
 *     它是一句注释，不是一个断言。
 *
 * 本测试把那句注释变成可执行断言：读**同一份 assets 配置**、
 * **同一份真实账单**，跑 Kotlin 的 [BillImporter]，逐项比对 Python 探针的
 * 输出。两份互相独立的实现（Kotlin 生产代码 / Python 探针）给出同一组数字，
 * 才算证明解析正确；只要有一项对不上，就说明其中一边错了。
 *
 * ## 样本缺失时自动跳过
 * `samples/` 在 .gitignore 中（个人账单不进版本库），换台机器就没有样本。
 * 那种情况下用 [assumeTrue] 跳过而非失败——但不会静默：JUnit 会把测试标记为
 * skipped，一眼能看出「这条根本没跑」，避免制造「绿色=已验证」的假象。
 */
class RealSampleRegressionTest {

    // ------------------------------------------------------------ 资源定位

    /**
     * 单测的工作目录随 Gradle 配置而变（默认是 `app/` 模块目录），
     * 因此按候选路径探测，而不是写死一条。
     */
    private fun locate(vararg candidates: String): File? =
        candidates.map(::File).firstOrNull { it.exists() }

    private val parserRules: File? = locate(
        "src/main/assets/parser_rules.json",
        "app/src/main/assets/parser_rules.json"
    )

    private val classifyRules: File? = locate(
        "src/main/assets/classify_rules.json",
        "app/src/main/assets/classify_rules.json"
    )

    /** 按文件名前缀探测样本：不把中文全名写进代码，改名/重导出也能命中 */
    private fun sample(prefix: String, suffix: String): File? =
        listOf("samples", "../samples", "app/../samples")
            .map(::File)
            .firstOrNull { it.isDirectory }
            ?.listFiles()
            ?.firstOrNull { it.name.startsWith(prefix) && it.name.endsWith(suffix) }

    private fun wechatXlsx(): File? = sample("微信支付账单流水文件", ".xlsx")
    private fun alipayCsv(): File? = sample("支付宝交易明细", ".csv")

    // ------------------------------------------------------------ 配置解析

    /**
     * 用 Gson 把 assets 里的 importProfiles 读成 [ImportProfile]。
     *
     * 字段映射与生产侧 `RuleStore.parseImportProfiles` 逐条对应。
     * 之所以不用生产代码直接读：`RuleStore` 依赖 `org.json`，而它在 JVM 单测里
     * 是 stub（调用即抛 "not mocked"）——这正是过去这条路径测不到的原因。
     *
     * 映射重复带来的漂移风险由 [配置结构完整且与生产解析口径一致] 与下面两个
     * 端到端用例共同兜底：真实数据的结果一旦偏离 Python 探针，断言就会失败。
     */
    private fun loadProfiles(file: File): List<ImportProfile> {
        val root = JsonParser.parseString(file.readText(Charsets.UTF_8)).asJsonObject
        val array = root.getAsJsonArray("importProfiles") ?: return emptyList()
        return array.map { element ->
            val o = element.asJsonObject
            val columns = o.getAsJsonObject("columns")?.entrySet()
                ?.associate { (k, v) -> k to v.asJsonArray.strList() }
                .orEmpty()
            val dir = o.getAsJsonObject("directionTokens")
            val seed = o.getAsJsonObject("categorySeed")
            val seedMap = seed?.getAsJsonObject("map")?.entrySet()
                ?.associate { (k, v) -> k to v.asString }
                .orEmpty()
            val ph = o.getAsJsonObject("placeholderPolicy")
            ImportProfile(
                id = o.get("id").asString,
                displayName = o.get("displayName")?.asString ?: o.get("id").asString,
                headerSignatures = o.getAsJsonArray("headerSignatures")?.strList().orEmpty(),
                minSignatureHits = o.get("minSignatureHits")?.asInt ?: 3,
                columns = columns,
                incomeTokens = dir?.getAsJsonArray("income")?.strList().orEmpty(),
                expenseTokens = dir?.getAsJsonArray("expense")?.strList().orEmpty(),
                neutralTokens = dir?.getAsJsonArray("neutral")?.strList().orEmpty(),
                dropStatusTokens = o.getAsJsonArray("dropStatusTokens")?.strList().orEmpty(),
                seedColumn = seed?.get("column")?.asString.orEmpty(),
                seedMap = seedMap,
                placeholder = PlaceholderPolicy(
                    action = ph?.get("action")?.asString ?: Policy.PLACEHOLDER_DROP,
                    statusTokens = ph?.getAsJsonArray("statusTokens")?.strList().orEmpty(),
                    paymentMustBeBlank = ph?.get("paymentMustBeBlank")?.asBoolean ?: false
                ),
                refundPolicy = o.getAsJsonObject("policies")?.get("refund")?.asString
                    ?: Policy.REFUND_COUNT_ONLY
            )
        }
    }

    private fun JsonArray.strList(): List<String> = map { it.asString }

    private fun profiles(): List<ImportProfile> {
        val f = parserRules
        assumeTrue("assets/parser_rules.json 不存在，跳过", f != null)
        return loadProfiles(f!!)
    }

    // ------------------------------------------------------------ 读取账单

    /** 与生产 `BillImporter.import()` 里的读法逐字一致，避免测的是另一条路径 */
    private fun readCsvRows(file: File): List<List<String>> =
        CsvIo.decode(file.readBytes()).split('\n').map { line ->
            CsvIo.splitCsvLine(line.trimEnd('\r').trimEnd())
        }

    private fun fmt(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(ms))

    private fun sumOf(parsed: BillImporter.Parsed, dir: Direction): Double =
        parsed.records.filter { it.direction == dir }.sumOf { it.amount }

    // ------------------------------------------------------------ 用例

    /**
     * 配置本身的结构校验：不依赖样本，任何机器上都会跑。
     * 它同时是「测试侧映射没漂移」的守卫——若 assets 改了结构而这里没跟上，先在这里失败。
     */
    @Test
    fun `配置结构完整且与生产解析口径一致`() {
        val all = profiles()
        assertEquals("importProfiles 应有 2 份档案（微信 + 支付宝）", 2, all.size)

        val wechat = all.firstOrNull { it.id == "wechat_bill" }
        val alipay = all.firstOrNull { it.id == "alipay_bill" }
        assertNotNull("缺少 wechat_bill 档案", wechat)
        assertNotNull("缺少 alipay_bill 档案", alipay)

        // 微信：xlsx，没有官方分类列；支付宝：CSV，带「交易分类」列
        assertEquals("微信侧不应有官方分类列", "", wechat!!.seedColumn)
        assertEquals("交易分类", alipay!!.seedColumn)
        assertTrue("微信档案应含交易单号列映射", wechat.columns.containsKey("txnNo"))
        assertTrue("支付宝档案应含收/付款方式列映射", alipay.columns.containsKey("payMethod"))

        // 账务口径：两家都必须带各自的 0 元占位签名与退款口径，
        // 缺一个都会让 0 元行落进「无法解析」（配置缺失必须被看见，而不是静默放行）
        assertEquals(
            "支付宝占位签名应实测收紧到「支付成功 + 付款方式为空」",
            listOf("支付成功"), alipay.placeholder.statusTokens
        )
        assertTrue("支付宝占位签名应要求付款方式为空", alipay.placeholder.paymentMustBeBlank)
        assertEquals(Policy.REFUND_COUNT_ONLY, alipay.refundPolicy)

        // 官方分类种子的映射目标必须是有效分类 id（与 classify_rules.json 交叉校验）
        val cr = classifyRules
        assumeTrue("assets/classify_rules.json 不存在，跳过种子校验", cr != null)
        val rulesRoot = JsonParser.parseString(cr!!.readText(Charsets.UTF_8)).asJsonObject
        // 有效 id = categories 各 id ∪ fallback.id。
        // 必须带上 fallback：parser_rules.json 有 `"其他": "other"` 这条种子映射，
        // 而 "other" 在 classify_rules.json 里是 fallback、不属于 categories ——
        // 漏掉它会让这条合法映射被误判成「无效分类」。
        val valid = rulesRoot.getAsJsonArray("categories")
            .map { it.asJsonObject.get("id").asString }.toMutableSet()
        rulesRoot.getAsJsonObject("fallback")?.get("id")?.asString?.let { valid += it }
        val bad = alipay.seedMap.filterValues { it !in valid }
        assertTrue(
            "官方分类种子指向了不存在的 categoryId，回填后会留下无效分类：$bad",
            bad.isEmpty()
        )
    }

    // ------------------------------------------------------ 微信（xlsx）

    @Test
    fun `微信账单端到端与独立探针逐项一致`() {
        val f = wechatXlsx()
        assumeTrue("samples/微信支付账单流水文件*.xlsx 不存在，跳过（样本已 gitignore）", f != null)

        val parsed = BillImporter.parseRows(XlsxReader.readRows(f!!), profiles())

        assertEquals("档案识别错误", "wechat_bill", parsed.profile?.id)
        // 期望值来自 tools/inspect_bill.py（独立 Python 实现，读同一份 assets 配置）
        assertEquals("数据行数", 41, parsed.totalRows)
        assertEquals("入库笔数（收入 14 + 支出 26）", 40, parsed.records.size)
        assertEquals("收入笔数", 14, parsed.records.count { it.direction == Direction.INCOME })
        assertEquals("收入金额", 12.53, sumOf(parsed, Direction.INCOME), 0.001)
        assertEquals("支出笔数", 26, parsed.records.count { it.direction == Direction.EXPENSE })
        assertEquals("支出金额", 301.39, sumOf(parsed, Direction.EXPENSE), 0.001)

        assertEquals("不计收支笔数", 1, parsed.neutral)
        assertEquals("退款/关闭笔数", 0, parsed.droppedRefund)
        assertEquals("0 元占位笔数", 0, parsed.droppedPlaceholder)
        assertEquals("无法解析笔数（应恒为 0：任何一行都必须有归宿）", 0, parsed.droppedUnparsed)
        assertEquals(
            "笔数必须平衡，不允许有行静默消失",
            41,
            parsed.records.size + parsed.neutral + parsed.droppedRefund +
                parsed.droppedPlaceholder + parsed.droppedUnparsed
        )

        // 时间断言是这一条的关键：xlsx 里时间是 Excel 序列号（墙上时钟语义）。
        // 若按 UTC 换算，全部时间会偏 8 小时——而 8 小时偏移在日期层面看不出来，
        // 必须钉到秒，才抓得住这个坑。
        assertEquals("最早一笔时间", "2026-08-10 12:44:10", fmt(parsed.records.minOf { it.occurredAt }))
        assertEquals("最晚一笔时间", "2026-09-10 12:38:21", fmt(parsed.records.maxOf { it.occurredAt }))

        assertTrue("每笔都应带交易单号", parsed.records.all { it.txnNo.isNotEmpty() })
    }

    // ------------------------------------------------------ 支付宝（csv）

    @Test
    fun `支付宝账单端到端与独立探针逐项一致`() {
        val f = alipayCsv()
        assumeTrue("samples/支付宝交易明细*.csv 不存在，跳过（样本已 gitignore）", f != null)

        val parsed = BillImporter.parseRows(readCsvRows(f!!), profiles())

        assertEquals("档案识别错误", "alipay_bill", parsed.profile?.id)
        assertEquals("数据行数", 132, parsed.totalRows)
        assertEquals("入库笔数（全部为支出）", 51, parsed.records.size)
        assertEquals("收入笔数", 0, parsed.records.count { it.direction == Direction.INCOME })
        assertEquals("支出笔数", 51, parsed.records.count { it.direction == Direction.EXPENSE })
        // 3907.27 与支付宝自带汇总逐项一致——这是账本可信度的地基
        assertEquals("支出金额", 3907.27, sumOf(parsed, Direction.EXPENSE), 0.001)

        // 分布才是重点：真实数据里这四类占比极大，手写样本完全模拟不出来
        assertEquals("不计收支笔数（余额宝/还款等，必须丢弃而不是记成消费）", 71, parsed.neutral)
        assertEquals("退款/关闭笔数（只计数不冲减）", 3, parsed.droppedRefund)
        assertEquals("0 元下单占位笔数（担保交易，真实付款 10 天后另起一行）", 7, parsed.droppedPlaceholder)
        assertEquals("无法解析笔数（应恒为 0）", 0, parsed.droppedUnparsed)
        assertEquals(
            "笔数必须平衡",
            132,
            parsed.records.size + parsed.neutral + parsed.droppedRefund +
                parsed.droppedPlaceholder + parsed.droppedUnparsed
        )

        // 支付宝是字符串时间，不像 xlsx 有序列号换算风险；仍钉到秒，
        // 因为「时间解析错」会直接摧毁 ±3 分钟去重窗口
        assertEquals("最早一笔时间", "2026-08-10 17:29:30", fmt(parsed.records.minOf { it.occurredAt }))
        assertEquals("最晚一笔时间", "2026-09-10 14:35:40", fmt(parsed.records.maxOf { it.occurredAt }))

        // 单号必须干净：真实文件里「交易订单号」132/132 尾部带制表符。
        // 不清理则单号精确匹配永远对不上 → 同一笔记两遍
        assertTrue("每笔都应带交易单号", parsed.records.all { it.txnNo.isNotEmpty() })
        assertTrue("单号不应残留制表符", parsed.records.none { it.txnNo.contains('\t') })
        assertTrue("单号不应重复（重复说明源数据有坑）",
            parsed.records.map { it.txnNo }.toSet().size == parsed.records.size)

        // 官方分类种子：51 笔支出全部命中「交易分类」列
        assertTrue("应有 51 笔带官方分类种子",
            parsed.records.all { !it.seedCategoryId.isNullOrBlank() })
    }
}
