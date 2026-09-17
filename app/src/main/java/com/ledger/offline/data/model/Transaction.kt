package com.ledger.offline.data.model

enum class Direction(val code: Int) {
    EXPENSE(0),
    INCOME(1);

    companion object {
        fun of(code: Int): Direction = if (code == INCOME.code) INCOME else EXPENSE
    }
}

data class Transaction(
    val id: Long = 0L,
    val amount: Double = 0.0,
    val direction: Direction = Direction.EXPENSE,
    /** 归一化后的商户名，如「星巴克」 */
    val merchant: String = "",
    val categoryId: String = "other",
    val categoryName: String = "其他",
    /** epoch millis，明文存储以便建索引和按时间范围查询 */
    val occurredAt: Long = 0L,
    /** wechat / alipay / bank_sms / csv */
    val sourceId: String = "",
    val txnNo: String = "",
    val note: String = "",
    /** false 表示这条分类是用户手动改过的 */
    val autoClassified: Boolean = true
)

/** 一次汇总结果，金额在内存里算完后解密，不在 SQL 层做聚合。 */
data class LedgerStats(
    val expense: Double = 0.0,
    val income: Double = 0.0,
    val count: Int = 0
)
