package com.ledger.offline.data

import com.ledger.offline.classify.MerchantNormalizer
import com.ledger.offline.data.model.Direction
import kotlin.math.abs

/**
 * 双路融合的匹配器（方案 §4.3「四级匹配 + 回填」）。
 *
 * ## 为什么必须存在
 * 通知监听与账单导入是两条互相独立的通路：
 * 用户白天收到支付通知（记一笔），月底又导入官方账单（同一笔再记一笔）。
 * 单靠现有去重拦不住，因为通知记录有一个致命特征——**经常没有收款方**。
 * 于是它落库时 merchant_hash 是「未识别商户」的哈希，
 * 永远匹配不上账单里那条有真实商户名的记录 → 同一笔记两次，账目虚高。
 *
 * ## 四级匹配（从严到宽，命中即停）
 * - L1 单号精确：有官方交易单号，最强证据（账单里几乎都有）
 * - L2 宽松指纹：金额 + 方向 + ±3min，两扇门取并集：
 *   「缺商户名的那一方是通知来源」或「一边手动、一边自动（见 [MANUAL_SOURCE_PREFIX]）」
 * - L3 完整指纹：金额 + 方向 + 商户名 + ±3min（通知里带了商户名时走这条）
 * - L4 新增
 *
 * ## L2 为什么必须对称（2026-09-17 修订）
 * 最初 L2 只检查**既有记录**那一侧（`target.merchantUnknown && target.fromNotification`），
 * 即只救得了「通知先落库、账单后到」。反过来的顺序——账单先入库、通知后到——
 * 会掉进一个必然的坑：通知侧此时是**新来的那一条**，它缺商户名，
 * 而既有账单记录有商户名，两边的 merchant_hash 永远对不上，
 * 于是这条通知作为「新记录」被插进去，同一笔记两遍。
 * 修法是把判据从「既有记录缺商户名」改成「**至少有一方是通知来源且该方没有商户名**」，
 * 两个方向就都能被同一扇门罩住。
 *
 * ## 回填纪律
 * 只补空字段，且**分类只在「这条是自动分的」+「新分类来自平台官方分类列」时才覆盖**。
 * 用户手动改过的分类（autoClassified = false）任何情况下都不许动——
 * 用户修正的权重永远高于平台的自动分类。
 *
 * ## 为什么是纯 Kotlin
 * 匹配逻辑是整条链路最易错、最需要单测的部分。刻意不碰任何 Android API，
 * 这样它能在纯 JVM 上跑测试，不必为了验证一个 if 分支去开模拟器。
 */
object MergeMatcher {

    /** 指纹匹配时间窗。通知与账单的时间戳通常只差几秒，3 分钟足以覆盖时区/舍入误差 */
    const val MATCH_WINDOW_MS = 3 * 60 * 1000L

    /** 官方账单来源的前缀；通知 / 无障碍来源不带这个前缀 */
    const val BILL_SOURCE_PREFIX = "bill_"

    /**
     * 手动记账的来源前缀。
     *
     * 它也是判重语义的一部分：手动记录是「用户凭记忆补的」，
     * 商户名是他自己敲的简称（"水电费"），与官方账单里的全称（"国网上海市电力公司"）
     * 字面永远对不上——所以 L2 必须为「一边手动、一边自动」再开一扇门，见 [looseHit]。
     */
    const val MANUAL_SOURCE_PREFIX = "manual_"

    /** 金额比较容差：两边都是从字符串解析出来的 Double，不能用 == */
    private const val AMOUNT_EPSILON = 0.005

    enum class Level { TXN_NO, LOOSE, FULL, NEW }

    /** 参与匹配的一行。金额与商户名是**解密后**的明文 */
    data class Candidate(
        val id: Long = 0L,
        val amount: Double = 0.0,
        val direction: Direction = Direction.EXPENSE,
        val merchant: String = "",
        val occurredAt: Long = 0L,
        val txnNo: String = "",
        val categoryId: String = "other",
        val categoryName: String = "其他",
        val autoClassified: Boolean = true,
        val sourceId: String = ""
    ) {
        /** 「不知道收款方是谁」——空串与占位名都算 */
        val merchantUnknown: Boolean
            get() = merchant.isBlank() || merchant == MerchantNormalizer.UNKNOWN_MERCHANT

        /** 是否来自通知 / 无障碍这类「实时但不完整」的来源 */
        val fromNotification: Boolean
            get() = !sourceId.startsWith(BILL_SOURCE_PREFIX)

        /** 是否用户手动补录的。商户名是自由文本，与官方账单对不上是常态 */
        val fromManual: Boolean
            get() = sourceId.startsWith(MANUAL_SOURCE_PREFIX)
    }

    data class Decision(val level: Level, val target: Candidate? = null)

    /** 要回填的字段。null = 该字段不动 */
    data class Backfill(
        val merchant: String? = null,
        val txnNo: String? = null,
        val categoryId: String? = null,
        val categoryName: String? = null
    ) {
        val isEmpty: Boolean
            get() = merchant == null && txnNo == null && categoryId == null
    }

    fun decide(incoming: Candidate, candidates: List<Candidate>): Decision {
        // ---- L1 单号精确：不受时间窗限制，账单与通知的时间可能相差很远
        if (incoming.txnNo.isNotBlank()) {
            candidates.firstOrNull { it.txnNo.isNotBlank() && it.txnNo == incoming.txnNo }
                ?.let { return Decision(Level.TXN_NO, it) }
        }

        // ---- L2 宽松指纹
        candidates
            .filter { looseHit(it, incoming) }
            .minByOrNull { abs(it.occurredAt - incoming.occurredAt) }
            ?.let { return Decision(Level.LOOSE, it) }

        // ---- L3 完整指纹
        candidates
            .filter { fullHit(it, incoming) }
            .minByOrNull { abs(it.occurredAt - incoming.occurredAt) }
            ?.let { return Decision(Level.FULL, it) }

        return Decision(Level.NEW, null)
    }

    /**
     * 命中的既有记录要补哪些字段。返回 null 表示无需回填——
     * 调用方据此判定「确实已有同一笔，只是没什么可补」。
     *
     * @param categoryFromOfficialSeed 新记录的分类是否来自平台官方分类列。
     * @param fallbackCategoryId 「兜底分类」的 id（classify_rules.json 的 fallback）。
     *        只有兜底分类才允许被官方种子替换：§4.4 定的优先级是
     *        用户记忆 > 关键词规则 > 官方种子 > 兜底。若既有记录的分类是关键词命中的，
     *        官方种子**不许**覆盖它，否则等于把优先级悄悄颠倒。
     */
    fun backfillPlan(
        target: Candidate,
        incoming: Candidate,
        categoryFromOfficialSeed: Boolean,
        fallbackCategoryId: String = "other"
    ): Backfill? {
        val plan = Backfill(
            // 商户名：通知常常没有收款方，账单有 → 只补空，绝不覆盖已有名字
            merchant = incoming.merchant.takeIf { target.merchantUnknown && !it.isNullOrBlank() },
            // 交易单号：只补空
            txnNo = incoming.txnNo.takeIf { target.txnNo.isBlank() && it.isNotBlank() },
            // 分类：三个条件全满足才动
            //   (a) 这条的分类是引擎自动判的（用户手动改过的永远不动）
            //   (b) 这条的分类是「兜底」，即当初压根没判出来
            //   (c) 新分类确实来自平台官方分类列
            categoryId = incoming.categoryId.takeIf {
                target.autoClassified &&
                    target.categoryId == fallbackCategoryId &&
                    categoryFromOfficialSeed &&
                    it.isNotBlank() && it != target.categoryId
            }
        )
        val withName = if (plan.categoryId != null) {
            plan.copy(categoryName = incoming.categoryName)
        } else {
            plan
        }
        return if (withName.isEmpty) null else withName
    }

    // ------------------------------------------------------------ 内部判定

    /** L2：宽到可以救回「通知没带商户名」那批，但不能宽到把两笔不同交易并成一笔 */
    private fun looseHit(c: Candidate, incoming: Candidate): Boolean =
        looseGate(c, incoming) &&
            sameAmount(c, incoming) && c.direction == incoming.direction &&
            inWindow(c, incoming) && orderNumbersCompatible(c, incoming)

    /**
     * L2 的开闸条件，两扇门取并集。
     *
     * 门一（[unknownSideIsFromNotification]）：**至少有一方是通知来源，且这一方没有商户名**。
     * 刻意写成对称形式，而不是只看 [c]（既有记录）——
     * 两个方向都会发生，且都必然导致重复记账：
     *   - 通知先落库（缺商户名），账单后到 → 缺商户名的是 c
     *   - 账单先入库（有商户名），通知后到 → 缺商户名的是 incoming
     * 后一种在「先补录历史账单、再在日常收通知」的用法下是常态。
     *
     * 门二（[manualMeetsAuto]）：一边手动、一边自动。
     */
    private fun looseGate(c: Candidate, incoming: Candidate): Boolean =
        unknownSideIsFromNotification(c, incoming) || manualMeetsAuto(c, incoming)

    private fun unknownSideIsFromNotification(c: Candidate, incoming: Candidate): Boolean =
        (c.merchantUnknown && c.fromNotification) ||
            (incoming.merchantUnknown && incoming.fromNotification)

    /**
     * 一边手动、一边自动 ⇒ 放宽到不看商户名。
     *
     * 为什么必须开：手动记账的商户名是用户敲的简称（"水电费""楼下超市"），
     * 官方账单里是全称（"国网上海市电力公司"）。两边**都有**商户名，
     * 于是门一不开（没有"缺商户名的那一方"）、L3 也不中（名字对不上），
     * 结果同一笔在账本里留下两条——用户手动记过，月底导账单又来一遍。
     *
     * 为什么只放宽到「一边手动一边自动」而不是「谁都行」：
     * 用户连续手动补录两笔同金额（分两次扫码付的、两杯咖啡）**必须是两笔**，
     * 所以「两边都是手动」不给开。同理账单 vs 账单、账单 vs 通知仍按原判据走。
     */
    private fun manualMeetsAuto(c: Candidate, incoming: Candidate): Boolean =
        c.fromManual != incoming.fromManual

    /**
     * L3：商户名也对得上，属于强证据。
     * 单号守卫与 L2 同源（方案 §4.3：**任何指纹层**都不许并「两边都有单号且不一致」的记录）——
     * 没有它，同一家店 3 分钟内连付两笔同金额（单号必然不同、商户名都来自账单）会被误并成一笔。
     */
    private fun fullHit(c: Candidate, incoming: Candidate): Boolean =
        !c.merchantUnknown && c.merchant == incoming.merchant &&
            sameAmount(c, incoming) && c.direction == incoming.direction &&
            inWindow(c, incoming) && orderNumbersCompatible(c, incoming)

    private fun sameAmount(c: Candidate, incoming: Candidate): Boolean =
        abs(c.amount - incoming.amount) < AMOUNT_EPSILON

    private fun inWindow(c: Candidate, incoming: Candidate): Boolean =
        abs(c.occurredAt - incoming.occurredAt) <= MATCH_WINDOW_MS

    /**
     * 两边都有单号且不一致 → 这是**可以证明的不同订单**，不允许再靠指纹合并。
     * 没有这条守卫，同一金额、同一方向、三分钟内的两笔不同消费会被误并成一笔（丢账）。
     */
    private fun orderNumbersCompatible(c: Candidate, incoming: Candidate): Boolean {
        if (c.txnNo.isBlank() || incoming.txnNo.isBlank()) return true
        return c.txnNo == incoming.txnNo
    }
}
