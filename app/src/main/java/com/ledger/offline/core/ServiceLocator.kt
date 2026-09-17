package com.ledger.offline.core

import android.content.Context
import com.ledger.offline.classify.Classifier
import com.ledger.offline.classify.MerchantNormalizer
import com.ledger.offline.crypto.FieldCipher
import com.ledger.offline.data.LedgerDb
import com.ledger.offline.data.TransactionDao
import com.ledger.offline.data.model.Direction
import com.ledger.offline.data.model.Transaction
import com.ledger.offline.parse.ParsedTransaction
import com.ledger.offline.parse.RuleStore
import java.util.Calendar
import java.util.Locale

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

    val classifier: Classifier by lazy {
        Classifier(
            rules = RuleStore.classifyRules(appContext),
            memoryProvider = { dao.loadMemory() },
            merchantHasher = { FieldCipher.merchantHash(it) }
        )
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 采集层 → 归类层 → 存储层的完整落库链路。通知服务与无障碍服务都走这里。 */
    fun persist(parsed: ParsedTransaction, rawText: String = ""): Boolean = persistRecord(
        amount = parsed.amount,
        direction = parsed.direction,
        merchantRaw = parsed.merchantRaw,
        occurredAt = parsed.occurredAt,
        sourceId = parsed.sourceId,
        rawText = rawText
    )

    /**
     * 直接落库。CSV 导入走这条——因为账单文件里已经给了干净的
     * 商户名、金额和收支方向，再拿去跑通知正则反而是画蛇添足。
     */
    fun persistRecord(
        amount: Double,
        direction: Direction,
        merchantRaw: String,
        occurredAt: Long,
        sourceId: String,
        txnNo: String = "",
        rawText: String = "",
        seedCategoryId: String? = null
    ): Boolean {
        val merchant = MerchantNormalizer.normalize(
            merchantRaw.ifBlank { MerchantNormalizer.UNKNOWN_MERCHANT }
        )
        val classification = classifier.classify(merchant, rawText, seedCategoryId)
        val txn = Transaction(
            amount = amount,
            direction = direction,
            merchant = merchant,
            categoryId = classification.categoryId,
            categoryName = classification.categoryName,
            occurredAt = occurredAt,
            sourceId = sourceId,
            txnNo = txnNo,
            autoClassified = classification.auto
        )
        return dao.insert(txn)
    }

    /** 用户手动改分类：同时写入修正记忆，下次同商户自动命中 */
    fun correctCategory(txn: Transaction, categoryId: String, categoryName: String) {
        dao.updateCategory(txn.id, categoryId, categoryName)
        dao.rememberMerchant(txn.merchant, categoryId, categoryName)
    }

    fun monthRange(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val cal = Calendar.getInstance(Locale.CHINA).apply {
            timeInMillis = now
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        return start to cal.timeInMillis
    }
}
