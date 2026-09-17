package com.ledger.offline.data

import com.ledger.offline.classify.MerchantNormalizer
import com.ledger.offline.data.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 合并回填（方案 §4.3 四级匹配）的单测。
 *
 * 要防的那个 bug 很具体、也很常见：
 *   上午用户收到「微信支付 ¥25.00」通知 → 落一行，**商户名是「未识别商户」**
 *   月底导入官方账单 → 同一笔有真实商户名「星巴克」
 * 若判重只看「金额 + 商户名」，两边的 merchant_hash 不同 → 判不出来 → **同一笔记两次**。
 * 所以必须有 L2 这扇「只对缺商户名的通知记录开」的门。
 *
 * 反过来，L2 开得太宽会**丢账**（把三分钟内两笔同额消费并成一笔），
 * 因此下面同时覆盖了各种「不该命中」的负例。
 */
class MergeMatcherTest {

    /** 2026-09-10 14:35:40 的 epoch millis，取固定值避免依赖运行时区 */
    private val base = 1786468540000L
    private val unknown = MerchantNormalizer.UNKNOWN_MERCHANT

    private fun notification(
        amount: Double = 25.0,
        merchant: String = unknown,
        at: Long = base,
        txnNo: String = "",
        categoryId: String = "other",
        autoClassified: Boolean = true
    ) = MergeMatcher.Candidate(
        id = 1L, amount = amount, direction = Direction.EXPENSE, merchant = merchant,
        occurredAt = at, txnNo = txnNo, categoryId = categoryId, categoryName = "其他",
        autoClassified = autoClassified, sourceId = "wechat"
    )

    private fun incoming(
        amount: Double = 25.0,
        merchant: String = "星巴克",
        at: Long = base,
        txnNo: String = "",
        categoryId: String = "food",
        sourceId: String = "bill_xlsx"
    ) = MergeMatcher.Candidate(
        amount = amount, direction = Direction.EXPENSE, merchant = merchant,
        occurredAt = at, txnNo = txnNo, categoryId = categoryId, categoryName = "餐饮",
        sourceId = sourceId
    )

    // ------------------------------------------------------------ L1

    @Test
    fun `L1 单号精确命中并补全缺失的商户名`() {
        val target = notification(merchant = unknown, at = base - 90_000L, txnNo = "WX001")
        val fromBill = incoming(txnNo = "WX001", at = base)
        val decision = MergeMatcher.decide(fromBill, listOf(target))

        assertEquals(MergeMatcher.Level.TXN_NO, decision.level)
        val plan = MergeMatcher.backfillPlan(decision.target!!, fromBill, false)
        assertNotNull(plan)
        assertEquals("星巴克", plan!!.merchant)
        // 单号本来就相同，无需回填
        assertNull(plan.txnNo)
    }

    @Test
    fun `L1 不受时间窗限制——隔夜入账的单号仍然对得上`() {
        // 账单上的「交易时间」与通知时刻可以差几小时（夜里下单、次日入账）
        val target = notification(at = base - 6 * 3600_000L, txnNo = "WX002")
        val decision = MergeMatcher.decide(
            incoming(at = base, txnNo = "WX002", merchant = "星巴克"), listOf(target)
        )
        assertEquals(MergeMatcher.Level.TXN_NO, decision.level)
    }

    // ------------------------------------------------------------ L2

    @Test
    fun `L2 能救回缺商户名的通知记录`() {
        val target = notification(merchant = unknown)
        val decision = MergeMatcher.decide(incoming(), listOf(target))

        assertEquals(MergeMatcher.Level.LOOSE, decision.level)
        assertEquals(1L, decision.target?.id)
    }

    @Test
    fun `L2 也能处理商户名是空串的历史记录`() {
        val decision = MergeMatcher.decide(incoming(), listOf(notification(merchant = "")))
        assertEquals(MergeMatcher.Level.LOOSE, decision.level)
    }

    @Test
    fun `L2 只对通知来源开门——账单来源即使缺商户名也不合并`() {
        // 两条都来自账单却商户名为空，那是账单本身没给收款方；
        // 把它们并成一笔会丢账，所以必须各记各的
        val target = notification(merchant = unknown).copy(sourceId = "bill_csv")
        val decision = MergeMatcher.decide(incoming(), listOf(target))
        assertEquals(MergeMatcher.Level.NEW, decision.level)
    }

    @Test
    fun `L2 时间超出三分钟窗口不命中`() {
        val target = notification(at = base - 4 * 60 * 1000L)
        val decision = MergeMatcher.decide(incoming(at = base), listOf(target))
        assertEquals(MergeMatcher.Level.NEW, decision.level)
    }

    @Test
    fun `L2 金额不同不命中`() {
        val decision = MergeMatcher.decide(incoming(amount = 25.01), listOf(notification(amount = 25.0)))
        assertEquals(MergeMatcher.Level.NEW, decision.level)
    }

    @Test
    fun `L2 两边都有单号且不一致时不命中——可以证明是两笔不同的订单`() {
        // 这条守卫防的是「同金额 + 同方向 + 三分钟内」的两笔不同消费被误并成一笔
        val target = notification(merchant = unknown, txnNo = "WX900")
        val decision = MergeMatcher.decide(incoming(txnNo = "WX901"), listOf(target))
        assertEquals(MergeMatcher.Level.NEW, decision.level)
    }

    @Test
    fun `多个候选时取时间最近的那条`() {
        val far = notification(merchant = unknown, at = base - 120_000L).copy(id = 1L)
        val near = notification(merchant = unknown, at = base - 1_000L).copy(id = 2L)
        val decision = MergeMatcher.decide(incoming(at = base), listOf(far, near))
        assertEquals(2L, decision.target?.id)
    }

    // ------------------------------------------- L2 的反向方向（2026-09-17 新增）
    // 这两条覆盖的是「先补录历史账单、之后日常收通知」这个 P1 的典型用法。
    // 修好之前，这里的每一条都会变成账本里的一笔重复记账。

    @Test
    fun `L2 反向——账单先入库、缺商户名的通知后到，判定为同一笔`() {
        // 既有记录是账单（有商户名、有单号），新来的是通知（既没商户名也没单号）
        val existingBill = incoming(merchant = "星巴克", txnNo = "WX001")
        val lateNotice = notification(merchant = unknown, at = base)

        val decision = MergeMatcher.decide(lateNotice, listOf(existingBill))
        assertEquals(MergeMatcher.Level.LOOSE, decision.level)
        assertEquals("星巴克", decision.target?.merchant)
    }

    @Test
    fun `L2 反向——命中的通知没有可回填的内容，调用方据此判为重复丢弃`() {
        val existingBill = incoming(merchant = "星巴克", txnNo = "WX001")
        val lateNotice = notification(merchant = unknown, at = base)
        val decision = MergeMatcher.decide(lateNotice, listOf(existingBill))

        val plan = MergeMatcher.backfillPlan(
            decision.target!!, lateNotice, categoryFromOfficialSeed = false
        )
        // 商户名、单号账单本来就有；分类是账单的来源不是通知能给的 → 无可补
        assertNull(plan)
    }

    @Test
    fun `L2 反向——通知自己带了商户名就不走宽松门，商户对不上照样各记各的`() {
        // 缺商户名的那一方不存在，宽松门就不该开：否则「瑞幸」的通知会被并进「星巴克」的账单
        val existingBill = incoming(merchant = "星巴克", txnNo = "WX001")
        val noticeWithMerchant = notification(merchant = "瑞幸咖啡", at = base)
        val decision = MergeMatcher.decide(noticeWithMerchant, listOf(existingBill))
        assertEquals(MergeMatcher.Level.NEW, decision.level)
    }

    @Test
    fun `L2 反向——来的是缺商户名的账单记录时仍不开门`() {
        // 对称化只放宽「通知那一侧缺商户名」，绝不等于「谁缺都行」。
        // 两条账单记录商户名都空是账单本身的问题，合并它们就是丢账。
        val existingBill = incoming(merchant = "星巴克", txnNo = "WX001")
        val anotherBill = incoming(merchant = "", txnNo = "WX002")
        val decision = MergeMatcher.decide(anotherBill, listOf(existingBill))
        assertEquals(MergeMatcher.Level.NEW, decision.level)
    }

    @Test
    fun `L2 反向——超出三分钟窗口仍然不合并`() {
        val existingBill = incoming(merchant = "星巴克", txnNo = "WX001")
        val farNotice = notification(merchant = unknown, at = base - 4 * 60 * 1000L)
        val decision = MergeMatcher.decide(farNotice, listOf(existingBill))
        assertEquals(MergeMatcher.Level.NEW, decision.level)
    }

    // ------------------------------------------------------------ L3

    @Test
    fun `L3 商户名相同时走完整指纹`() {
        val target = notification(merchant = "星巴克")
        val decision = MergeMatcher.decide(incoming(merchant = "星巴克"), listOf(target))
        assertEquals(MergeMatcher.Level.FULL, decision.level)
    }

    @Test
    fun `L3 商户名不同不命中`() {
        val target = notification(merchant = "瑞幸咖啡")
        val decision = MergeMatcher.decide(incoming(merchant = "星巴克"), listOf(target))
        assertEquals(MergeMatcher.Level.NEW, decision.level)
    }

    // ------------------------------------------------------------ 回填纪律

    @Test
    fun `回填只补空字段——已有商户名绝不被覆盖`() {
        val target = notification(merchant = "星巴克")
        val plan = MergeMatcher.backfillPlan(target, incoming(merchant = "星巴克(浦东店)"), false)
        // 商户名不空 → 不回填；单号也已有/或双方都空 → 计划为空
        assertNull(plan)
    }

    @Test
    fun `回填不覆盖用户修正过的分类`() {
        // autoClassified = false 表示「用户手动改过」，权重永远高于平台自动分类
        val target = notification(
            merchant = unknown, categoryId = "entertainment", autoClassified = false
        )
        val plan = MergeMatcher.backfillPlan(target, incoming(categoryId = "food"), categoryFromOfficialSeed = true)

        assertNotNull(plan)
        assertEquals("星巴克", plan!!.merchant)   // 商户名照补
        assertNull(plan.categoryId)               // 分类不许动
    }

    @Test
    fun `兜底分类才允许被官方种子替换`() {
        // 既有记录当初没判出来，落的是兜底「其他」→ 官方分类是更强的信息，可以补上
        val fallback = notification(merchant = unknown, categoryId = "other", autoClassified = true)

        val bySeed = MergeMatcher.backfillPlan(fallback, incoming(categoryId = "food"), categoryFromOfficialSeed = true)
        assertEquals("food", bySeed?.categoryId)
        assertEquals("餐饮", bySeed?.categoryName)

        // 不来自官方种子 → 没有更权威的信息可给，不动
        val byKeyword = MergeMatcher.backfillPlan(fallback, incoming(categoryId = "food"), categoryFromOfficialSeed = false)
        assertNull(byKeyword?.categoryId)
    }

    @Test
    fun `已在关键词命中类目的记录不被官方种子覆盖 —— 优先级不可颠倒`() {
        // §4.4 定的优先级是 用户记忆 > 关键词规则 > 官方种子 > 兜底。
        // 既有记录已是「entertainment」（当初由关键词规则判出），
        // 即便新分类确实来自平台官方列，也**不许**把它改成 food，否则等于把官方种子抬到了关键词之上。
        val keywordHit = notification(merchant = unknown, categoryId = "entertainment", autoClassified = true)

        val plan = MergeMatcher.backfillPlan(keywordHit, incoming(categoryId = "food"), categoryFromOfficialSeed = true)
        assertNotNull(plan)                       // 商户名仍要补
        assertEquals("星巴克", plan!!.merchant)
        assertNull(plan.categoryId)               // 但分类不动
    }

    @Test
    fun `分类与既有相同则不必回填`() {
        val target = notification(merchant = "星巴克", categoryId = "food")
        val plan = MergeMatcher.backfillPlan(
            target, incoming(merchant = "星巴克", categoryId = "food"), categoryFromOfficialSeed = true
        )
        assertNull(plan)
    }

    @Test
    fun `全额命中且无可补字段时返回 null —— 调用方据此判定为重复`() {
        val target = notification(merchant = "星巴克", txnNo = "WX001")
        val plan = MergeMatcher.backfillPlan(target, incoming(merchant = "星巴克", txnNo = "WX001"), false)
        assertNull(plan)
    }

    // ------------------------------------------------------------ 端到端语义

    @Test
    fun `先通知后导入账单——同一笔只留一条且商户名被补上`() {
        // ① 白天：通知先落库，商户名缺失
        val existing = listOf(notification(amount = 25.0, merchant = unknown))

        // ② 月底：账单里同一笔，带真实商户名与订单号
        val fromBill = incoming(amount = 25.0, merchant = "星巴克", txnNo = "WX001")

        val decision = MergeMatcher.decide(fromBill, existing)
        assertEquals(MergeMatcher.Level.LOOSE, decision.level)

        val plan = MergeMatcher.backfillPlan(decision.target!!, fromBill, categoryFromOfficialSeed = false)
        assertNotNull(plan)
        // 这两步落地后：账本里仍是 1 条，且商户名从「未识别商户」变成「星巴克」
        assertEquals("星巴克", plan!!.merchant)
        assertEquals("WX001", plan.txnNo)
    }

    @Test
    fun `先导入账单后收通知——同一笔不会被记第二遍`() {
        // ① 用户先补录了历史账单，这条已经在账本里了
        val existing = listOf(incoming(amount = 25.0, merchant = "星巴克", txnNo = "WX001"))

        // ② 之后某天，支付宝/微信推了一条不带商户名的通知（同一笔的迟到副本）
        val lateNotice = notification(amount = 25.0, merchant = unknown, at = base)

        val decision = MergeMatcher.decide(lateNotice, existing)
        assertEquals(MergeMatcher.Level.LOOSE, decision.level)

        // ③ 无可回填 → 调用方把这条通知当重复丢弃，账本保持 1 条
        assertNull(MergeMatcher.backfillPlan(decision.target!!, lateNotice, false))
    }
}
