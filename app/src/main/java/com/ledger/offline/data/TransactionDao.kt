package com.ledger.offline.data

import android.content.ContentValues
import android.database.Cursor
import com.ledger.offline.crypto.FieldCipher
import com.ledger.offline.data.model.Direction
import com.ledger.offline.data.model.LedgerStats
import com.ledger.offline.data.model.Transaction

class TransactionDao(private val db: LedgerDb) {

    /** 去重时间窗：同金额 + 同商户 + 同方向，落在 ±3 分钟内视为同一笔。 */
    private val dedupWindowMs = 3 * 60 * 1000L

    /**
     * 写入一条记录。返回 true 表示真的新增，false 表示被去重拦下。
     *
     * 为什么必须去重：同一笔支付可能同时产生「微信支付通知」和「账单页无障碍事件」，
     * 两条来源的时间戳通常差几十毫秒到几秒。不去重用户会看到双份流水。
     */
    fun insert(txn: Transaction): Boolean {
        val amountHash = FieldCipher.amountHash(txn.amount)
        val merchantHash = FieldCipher.merchantHash(txn.merchant)

        if (isDuplicate(amountHash, merchantHash, txn.direction, txn.occurredAt, txn.txnNo)) {
            return false
        }

        val values = ContentValues().apply {
            put("amount_enc", FieldCipher.encrypt(formatAmount(txn.amount)))
            put("amount_hash", amountHash)
            put("direction", txn.direction.code)
            put("merchant_enc", FieldCipher.encrypt(txn.merchant))
            put("merchant_hash", merchantHash)
            put("category_id", txn.categoryId)
            put("category_name", txn.categoryName)
            put("occurred_at", txn.occurredAt)
            put("source_id", txn.sourceId)
            put("txn_no", txn.txnNo)
            put("note_enc", FieldCipher.encrypt(txn.note))
            put("auto_classified", if (txn.autoClassified) 1 else 0)
        }
        return db.writableDatabase.insert("txn", null, values) != -1L
    }

    private fun isDuplicate(
        amountHash: String,
        merchantHash: String,
        direction: Direction,
        occurredAt: Long,
        txnNo: String
    ): Boolean {
        // 有平台交易单号时优先按单号判重（最准确，CSV 导入走这条）
        if (txnNo.isNotEmpty()) {
            db.readableDatabase.rawQuery(
                "SELECT 1 FROM txn WHERE txn_no = ? LIMIT 1", arrayOf(txnNo)
            ).use { if (it.moveToFirst()) return true }
        }
        db.readableDatabase.rawQuery(
            """
            SELECT 1 FROM txn
             WHERE amount_hash = ? AND merchant_hash = ? AND direction = ?
               AND occurred_at BETWEEN ? AND ?
             LIMIT 1
            """.trimIndent(),
            arrayOf(
                amountHash, merchantHash, direction.code.toString(),
                (occurredAt - dedupWindowMs).toString(),
                (occurredAt + dedupWindowMs).toString()
            )
        ).use { return it.moveToFirst() }
    }

    /** 按平台交易单号判重（CSV 导入走这条，最可靠） */
    fun existsByTxnNo(txnNo: String): Boolean {
        if (txnNo.isEmpty()) return false
        db.readableDatabase.rawQuery(
            "SELECT 1 FROM txn WHERE txn_no = ? LIMIT 1", arrayOf(txnNo)
        ).use { return it.moveToFirst() }
    }

    fun queryRange(from: Long, to: Long, limit: Int = 500): List<Transaction> {
        val list = ArrayList<Transaction>()
        db.readableDatabase.rawQuery(
            """
            SELECT id, amount_enc, direction, merchant_enc, category_id, category_name,
                   occurred_at, source_id, txn_no, note_enc, auto_classified
              FROM txn
             WHERE occurred_at BETWEEN ? AND ?
             ORDER BY occurred_at DESC
             LIMIT ?
            """.trimIndent(),
            arrayOf(from.toString(), to.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) list.add(c.toTransaction())
        }
        return list
    }

    /** 汇总。金额是密文，无法在 SQL 里 SUM，所以取出后在内存累加——几千行量级毫无压力。 */
    fun stats(from: Long, to: Long): LedgerStats {
        var expense = 0.0
        var income = 0.0
        var count = 0
        db.readableDatabase.rawQuery(
            "SELECT amount_enc, direction FROM txn WHERE occurred_at BETWEEN ? AND ?",
            arrayOf(from.toString(), to.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val amount = FieldCipher.decrypt(c.getString(0)).toDoubleOrNull() ?: 0.0
                if (Direction.of(c.getInt(1)) == Direction.EXPENSE) expense += amount else income += amount
                count++
            }
        }
        return LedgerStats(expense, income, count)
    }

    fun updateCategory(id: Long, categoryId: String, categoryName: String) {
        val values = ContentValues().apply {
            put("category_id", categoryId)
            put("category_name", categoryName)
            put("auto_classified", 0)
        }
        db.writableDatabase.update("txn", values, "id = ?", arrayOf(id.toString()))
    }

    fun deleteById(id: Long) {
        db.writableDatabase.delete("txn", "id = ?", arrayOf(id.toString()))
    }

    // ------------------------------------------------- 用户修正记忆

    fun rememberMerchant(merchant: String, categoryId: String, categoryName: String) {
        if (merchant.isBlank()) return
        val values = ContentValues().apply {
            put("merchant_hash", FieldCipher.merchantHash(merchant))
            put("category_id", categoryId)
            put("category_name", categoryName)
            put("updated_at", System.currentTimeMillis())
        }
        db.writableDatabase.insertWithOnConflict(
            "merchant_memory", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** 返回 merchantHash -> (categoryId, categoryName) */
    fun loadMemory(): Map<String, Pair<String, String>> {
        val map = HashMap<String, Pair<String, String>>()
        db.readableDatabase.rawQuery(
            "SELECT merchant_hash, category_id, category_name FROM merchant_memory", null
        ).use { c ->
            while (c.moveToNext()) {
                map[c.getString(0)] = c.getString(1) to c.getString(2)
            }
        }
        return map
    }

    private fun Cursor.toTransaction() = Transaction(
        id = getLong(0),
        amount = FieldCipher.decrypt(getString(1)).toDoubleOrNull() ?: 0.0,
        direction = Direction.of(getInt(2)),
        merchant = FieldCipher.decrypt(getString(3)),
        categoryId = getString(4),
        categoryName = getString(5),
        occurredAt = getLong(6),
        sourceId = getString(7),
        txnNo = getString(8),
        note = FieldCipher.decrypt(getString(9)),
        autoClassified = getInt(10) == 1
    )

    companion object {
        /** 统一金额格式，避免 25.0 / 25.00 / 25 在解密后比对不上 */
        fun formatAmount(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
    }
}
