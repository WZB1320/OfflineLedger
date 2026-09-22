package com.ledger.offline.data

import android.content.ContentValues
import android.database.Cursor
import com.ledger.offline.crypto.FieldCipher
import com.ledger.offline.data.model.Direction
import com.ledger.offline.data.model.LedgerStats
import com.ledger.offline.data.model.Transaction

class TransactionDao(private val db: LedgerDb) {

    /** 去重时间窗：同金额 + 同商户 + 同方向，落在 ±3 分钟内视为同一笔。 */
    private val dedupWindowMs = MergeMatcher.MATCH_WINDOW_MS

    enum class MergeOutcome { ADDED, BACKFILLED, DUPLICATE }

    /**
     * 融合结论 + 新增记录的 rowId。
     *
     * insertedId 只在 ADDED 时非空：手动记账「先融合落库、再按用户选的分类修正」需要拿到
     * 刚插入那条的 id；BACKFILLED / DUPLICATE 时融合目标是既有记录，不该再被手动表单覆盖。
     */
    data class MergeResult(val outcome: MergeOutcome, val insertedId: Long?) {
        companion object {
            fun duplicate() = MergeResult(MergeOutcome.DUPLICATE, null)
        }
    }

    /**
     * 双路融合入口：账单导入必须走这条，而不是直接 [insert]。
     *
     * 先按 [MergeMatcher] 的四级规则找既有记录：
     *   - 找到 → 只补空字段（商户名 / 交易单号 / 官方分类），**绝不覆盖用户修正**；
     *   - 找不到 → 正常新增。
     *
     * 这样「白天收通知记一笔、月底导账单又记一笔」才会被合并成一笔，
     * 而不是在账本里留下两条。
     *
     * @param categoryFromOfficialSeed 新记录的分类是否**确实来自**平台官方分类列
     *        （取 Classification.fromSeed，而不是「账单里有没有这一列」）。
     * @param fallbackCategoryId 兜底分类 id；只有兜底分类才允许被官方种子替换，见 [MergeMatcher.backfillPlan]
     */
    fun mergeOrInsert(
        txn: Transaction,
        categoryFromOfficialSeed: Boolean = false,
        fallbackCategoryId: String = "other"
    ): MergeResult {
        val incoming = MergeMatcher.Candidate(
            amount = txn.amount,
            direction = txn.direction,
            merchant = txn.merchant,
            occurredAt = txn.occurredAt,
            txnNo = txn.txnNo,
            categoryId = txn.categoryId,
            categoryName = txn.categoryName,
            autoClassified = txn.autoClassified,
            sourceId = txn.sourceId
        )
        val candidates = loadCandidates(
            FieldCipher.amountHash(txn.amount), txn.direction, txn.occurredAt, txn.txnNo
        )
        val target = MergeMatcher.decide(incoming, candidates).target
            ?: return insertReturningId(txn).let { id ->
                if (id != -1L) MergeResult(MergeOutcome.ADDED, id)
                else MergeResult.duplicate()
            }

        val plan = MergeMatcher.backfillPlan(target, incoming, categoryFromOfficialSeed, fallbackCategoryId)
            ?: return MergeResult.duplicate()
        return if (applyBackfill(target.id, plan)) {
            MergeResult(MergeOutcome.BACKFILLED, null)
        } else MergeResult.duplicate()
    }

    private fun loadCandidates(
        amountHash: String,
        direction: Direction,
        occurredAt: Long,
        txnNo: String
    ): List<MergeMatcher.Candidate> {
        val out = LinkedHashMap<Long, MergeMatcher.Candidate>()
        val rdb = db.readableDatabase

        // 单号精确：不加时间窗——账单上的「交易时间」与通知时刻可能差很远（隔夜入账）
        if (txnNo.isNotBlank()) {
            rdb.rawQuery("$CANDIDATE_SQL WHERE txn_no = ?", arrayOf(txnNo)).use { c ->
                while (c.moveToNext()) c.toCandidate().also { out[it.id] = it }
            }
        }
        // 金额 + 方向 + 时间窗：走 idx_txn_match(amount_hash, direction, occurred_at)
        rdb.rawQuery(
            "$CANDIDATE_SQL WHERE amount_hash = ? AND direction = ? AND occurred_at BETWEEN ? AND ?",
            arrayOf(
                amountHash, direction.code.toString(),
                (occurredAt - dedupWindowMs).toString(),
                (occurredAt + dedupWindowMs).toString()
            )
        ).use { c ->
            while (c.moveToNext()) c.toCandidate().also { out[it.id] = it }
        }
        return out.values.toList()
    }

    /** 回填：只写 plan 里非空的字段。金额、时间、方向永不改——那是识别同一笔的依据 */
    private fun applyBackfill(id: Long, plan: MergeMatcher.Backfill): Boolean {
        val values = ContentValues()
        plan.merchant?.let {
            values.put("merchant_enc", FieldCipher.encrypt(it))
            values.put("merchant_hash", FieldCipher.merchantHash(it))
        }
        plan.txnNo?.let { values.put("txn_no", it) }
        plan.categoryId?.let { values.put("category_id", it) }
        plan.categoryName?.let { values.put("category_name", it) }
        if (values.size() == 0) return false
        return db.writableDatabase.update("txn", values, "id = ?", arrayOf(id.toString())) > 0
    }

    private fun Cursor.toCandidate() = MergeMatcher.Candidate(
        id = getLong(0),
        amount = FieldCipher.decrypt(getString(1)).toDoubleOrNull() ?: 0.0,
        direction = Direction.of(getInt(2)),
        merchant = FieldCipher.decrypt(getString(3)),
        occurredAt = getLong(4),
        txnNo = getString(5),
        categoryId = getString(6),
        categoryName = getString(7),
        autoClassified = getInt(8) == 1,
        sourceId = getString(9)
    )

    /**
     * 写入一条记录。返回 true 表示真的新增，false 表示被去重拦下。
     *
     * 为什么必须去重：同一笔支付可能同时产生「微信支付通知」和「账单页无障碍事件」，
     * 两条来源的时间戳通常差几十毫秒到几秒。不去重用户会看到双份流水。
     *
     * 注意：账单导入不要直接用它，要走 [mergeOrInsert]——否则「通知缺商户名」
     * 那批记录匹配不上，会重复记账。
     */
    fun insert(txn: Transaction): Boolean = insertReturningId(txn) != -1L

    /**
     * [insert] 的 rowId 版：-1 表示被去重拦下或写库失败。
     * 主体只此一份，[insert] 是它的 Boolean 包装——两份实现必然漂移，不复制。
     */
    private fun insertReturningId(txn: Transaction): Long {
        val amountHash = FieldCipher.amountHash(txn.amount)
        val merchantHash = FieldCipher.merchantHash(txn.merchant)

        if (isDuplicate(amountHash, merchantHash, txn.direction, txn.occurredAt, txn.txnNo)) {
            return -1L
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
        return db.writableDatabase.insert("txn", null, values)
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
        // 指纹判重。若本条带单号，则只在「既有记录无单号」或「单号相同」时才算重复——
        // 与 MergeMatcher.orderNumbersCompatible 同一语义：两边都有单号且不一致，
        // 就是**可以证明的两笔不同交易**，不许再靠指纹合并。
        //
        // 缺了这层守卫会出现这样一条漏账链路：MergeMatcher 已按单号正确判为「新增」，
        // 落到这里却又被指纹拦下判成重复 —— 同商户 3 分钟内的两笔同金额消费
        // （自动售货机连买两瓶水、连扫两次码）后一笔就这样被静默丢弃。
        val sql = duplicateProbeSql(txnNo.isNotEmpty())
        val args = buildList {
            add(amountHash)
            add(merchantHash)
            add(direction.code.toString())
            add((occurredAt - dedupWindowMs).toString())
            add((occurredAt + dedupWindowMs).toString())
            if (txnNo.isNotEmpty()) add(txnNo)
        }.toTypedArray()
        db.readableDatabase.rawQuery(sql, args).use { return it.moveToFirst() }
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

    /**
     * 用户在界面上直接改一条既有记录（金额 / 方向 / 商户 / 时间 / 分类）。
     *
     * 与 [applyBackfill] 的区别必须分清：那是**机器合并**，只补空字段、绝不覆盖既有值；
     * 这是**用户显式修改**，用户的判断就是最终结果，整行覆盖。
     *
     * 为什么 hash 必须跟着重算：amount_hash / merchant_hash 是判重的索引字段
     * （idx_txn_match 就建在 amount_hash + direction + occurred_at 上）。
     * 只改密文不改 hash，等于把索引留在旧值上——之后这条记录永远匹配不上任何东西，
     * 表现为「导了账单却合并不进来，平白多一笔重复」。
     */
    fun updateRecord(txn: Transaction) {
        val values = ContentValues().apply {
            put("amount_enc", FieldCipher.encrypt(formatAmount(txn.amount)))
            put("amount_hash", FieldCipher.amountHash(txn.amount))
            put("direction", txn.direction.code)
            put("merchant_enc", FieldCipher.encrypt(txn.merchant))
            put("merchant_hash", FieldCipher.merchantHash(txn.merchant))
            put("category_id", txn.categoryId)
            put("category_name", txn.categoryName)
            put("occurred_at", txn.occurredAt)
            put("note_enc", FieldCipher.encrypt(txn.note))
            // 用户亲手定过分类，这一笔就不再算「自动分类」
            put("auto_classified", 0)
        }
        db.writableDatabase.update("txn", values, "id = ?", arrayOf(txn.id.toString()))
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

        /** 匹配候选的取数语句；列顺序必须与 Cursor.toCandidate() 一致 */
        private const val CANDIDATE_SQL =
            "SELECT id, amount_enc, direction, merchant_enc, occurred_at, txn_no, " +
                "category_id, category_name, auto_classified, source_id FROM txn"

        /**
         * 指纹判重的探测语句（含单号守卫）。
         *
         * @param hasTxnNo 本条是否带官方交易单号。带单号时追加
         *        `txn_no = '' OR txn_no = ?` —— 既有记录**有**单号且与本条不同，
         *        就是可证明的两笔不同交易，不得判成重复。
         *        这条守卫与 [MergeMatcher.orderNumbersCompatible] 同一语义，
         *        两者必须同时存在：少了它，MergeMatcher 判出的「新增」会在这一层
         *        被指纹重新吞掉，表现为同商户 3 分钟内第二笔同金额消费静默消失。
         *
         * 抽成函数是为了能在纯 JVM 单测里断言守卫存在（SQL 本身需真机才跑得起来，
         * 但「守卫有没有被误删」不该等到真机才发现）。
         */
        internal fun duplicateProbeSql(hasTxnNo: Boolean): String = buildString {
            append(
                "SELECT 1 FROM txn WHERE amount_hash = ? AND merchant_hash = ? " +
                    "AND direction = ? AND occurred_at BETWEEN ? AND ?"
            )
            if (hasTxnNo) append(" AND (txn_no = '' OR txn_no = ?)")
            append(" LIMIT 1")
        }
    }
}
