package com.ledger.offline.core

import android.content.Context
import com.ledger.offline.classify.Classification
import com.ledger.offline.classify.Classifier
import com.ledger.offline.classify.MerchantNormalizer
import com.ledger.offline.crypto.FieldCipher
import com.ledger.offline.data.LedgerDb
import com.ledger.offline.data.MonthWindow
import com.ledger.offline.data.TransactionDao
import com.ledger.offline.data.model.Direction
import com.ledger.offline.data.model.Transaction
import com.ledger.offline.parse.ClassifyRules
import com.ledger.offline.parse.ParsedTransaction
import com.ledger.offline.parse.RuleStore

/**
 * 极简服务定位器。
 *
 * 不用 Hilt / Koin：那类 DI 框架会引入注解处理器和运行时开销，
 * 对「把 APK 压到 2 MB 以内」这个目标来说是反向操作。
 * 这个 App 的依赖图只有 3 个对象，一个 object 足够。
 */
object ServiceLocator {

    private lateinit var appContext: Context

    val dao: TransactionDao by lazy { TransactionDao(LedgerDb(appContext)) }

    val classifyRules: ClassifyRules by lazy { RuleStore.classifyRules(appContext) }

    val classifier: Classifier by lazy {
        Classifier(
            rules = classifyRules,
            memoryProvider = { dao.loadMemory() },
            merchantHasher = { FieldCipher.merchantHash(it) }
        )
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 采集层 → 归类层 → 存储层的完整落库链路。通知服务与无障碍服务都走这里。
     *
     * 2026-09-17 修订：这里也走**融合**，不再直插。
     * 早先只有账单导入走融合，通知走直插，理由是「通知是实时来的，撞不上已有记录」。
     * 但那个理由只在「先通知后账单」的顺序下成立；一旦用户先补录了历史账单、
     * 之后日常收通知（P1 的典型用法），同金额同方向的那条通知就会因为
     * 商户名对不上而**再记一遍**。两条路必须共用同一套融合判据。
     *
     * 返回融合结论：真的新增 / 回填了既有记录 / 判定为重复并丢弃。
     */
    fun persist(parsed: ParsedTransaction, rawText: String = ""): TransactionDao.MergeResult =
        mergeRecord(
            amount = parsed.amount,
            direction = parsed.direction,
            merchantRaw = parsed.merchantRaw,
            occurredAt = parsed.occurredAt,
            sourceId = parsed.sourceId,
            rawText = rawText
        )

    /**
     * 落库唯一入口：**先融合、后判重**（方案 §4.3）。
     *
     * 不直接插，而是先让 DAO 用四级匹配去找同一笔的既有记录：
     * 找到了就把商户名 / 单号 / 官方分类补回去（正向），
     * 或者判定本次这条信息量更少、直接丢弃副本（反向），找不到才新增。
     * 少了这一步，「通知缺商户名」的账目会与账单里的同一笔并存。
     *
     * 刻意不再提供「绕过融合直接插」的公开方法——那种捷径一旦存在，
     * 迟早会有新的调用点图省事走上去，把这里辛苦拦住的重复记账重新放回来。
     */
    fun mergeRecord(
        amount: Double,
        direction: Direction,
        merchantRaw: String,
        occurredAt: Long,
        sourceId: String,
        txnNo: String = "",
        rawText: String = "",
        note: String = "",
        seedCategoryId: String? = null
    ): TransactionDao.MergeResult {
        val built = build(amount, direction, merchantRaw, occurredAt, sourceId, txnNo, rawText, note, seedCategoryId)
        return dao.mergeOrInsert(
            built.txn,
            // 用分类结果**实际来自哪里**，而不是「账单里有没有分类列」——
            // 前者才能保证 §4.4 的优先级（关键词规则 > 官方种子）不被回填悄悄颠倒
            categoryFromOfficialSeed = built.classification.fromSeed,
            fallbackCategoryId = classifyRules.fallback.id
        )
    }

    private class Built(val txn: Transaction, val classification: Classification)

    private fun build(
        amount: Double,
        direction: Direction,
        merchantRaw: String,
        occurredAt: Long,
        sourceId: String,
        txnNo: String,
        rawText: String,
        note: String,
        seedCategoryId: String?
    ): Built {
        val merchant = MerchantNormalizer.normalize(
            merchantRaw.ifBlank { MerchantNormalizer.UNKNOWN_MERCHANT }
        )
        val classification = classifier.classify(merchant, rawText, seedCategoryId)
        return Built(
            txn = Transaction(
                amount = amount,
                direction = direction,
                merchant = merchant,
                categoryId = classification.categoryId,
                categoryName = classification.categoryName,
                occurredAt = occurredAt,
                sourceId = sourceId,
                txnNo = txnNo,
                note = note,
                autoClassified = classification.auto
            ),
            classification = classification
        )
    }

    /** 用户手动改分类：同时写入修正记忆，下次同商户自动命中 */
    fun correctCategory(txn: Transaction, categoryId: String, categoryName: String) {
        correctCategory(txn.id, txn.merchant, categoryId, categoryName)
    }

    /**
     * 上面那条的 id 版：手动记账刚落库时手里只有 id（[TransactionDao.MergeResult.rowId]）——
     * 新增时是新的 rowId，并进既有记录时是被补那条的 id，两种情况都要把用户选的分类写上去。
     *
     * 修正记忆只在 merchant 是真实商户名时写：给「未识别商户」这个常量桶写记忆，
     * 会让**所有**无商户名的记录（通知监听的天花板，量很大）被同一条记忆带偏分类。
     *
     * @param remember false = 只改这一笔，不写记忆（弹层里的「仅改这一笔」）。
     */
    fun correctCategory(
        id: Long,
        merchant: String,
        categoryId: String,
        categoryName: String,
        remember: Boolean = true
    ) {
        dao.updateCategory(id, categoryId, categoryName)
        if (remember && merchant != MerchantNormalizer.UNKNOWN_MERCHANT) {
            dao.rememberMerchant(merchant, categoryId, categoryName)
        }
    }

    /**
     * 指定月份的窗口 [月初, 下月初)。offsetMonths = 0 是当前月，-1 上一个月。
     *
     * 实现委托给 [MonthWindow]：它纯 Kotlin，能在 JVM 单测里直接断言跨月边界，
     * 而 ServiceLocator 本身依赖数据库，本地测不了。同一份逻辑不放两处。
     */
    fun monthRange(now: Long = System.currentTimeMillis(), offsetMonths: Int = 0): Pair<Long, Long> =
        MonthWindow.range(now, offsetMonths)
}
