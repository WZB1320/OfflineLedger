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
    /**
     * 来源标识。**前缀是判重判据，不只是显示用的标签**，取值分两类：
     * - `bill_*`（目前 bill_xlsx / bill_csv）：官方账单导入，字段完整
     * - 其余（wechat / alipay / bank_sms）：通知等采集来源，实时但常缺商户名
     *
     * 判据见 `MergeMatcher.BILL_SOURCE_PREFIX` —— 它是**白名单取反**：
     * 一切非 `bill_` 前缀都按「通知来源」处理，并据此决定宽松指纹那扇门开不开。
     * 所以新增来源时不能随便起前缀，先确认它该被当成完整来源还是不完整来源。
     */
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
