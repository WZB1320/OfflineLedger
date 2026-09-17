package com.ledger.offline.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class LedgerDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        // ---------------------------------------------------------------
        // 敏感字段（金额、商户名、备注）一律存密文，列名加 _enc 后缀。
        // 需要建索引的字段（时间、分类、收支方向）保持明文，但本身不含隐私内容。
        // 商户名既要能查又不能明文落库，所以额外存一列 HMAC 摘要 merchant_hash。
        // ---------------------------------------------------------------
        db.execSQL(
            """
            CREATE TABLE txn (
                id               INTEGER PRIMARY KEY AUTOINCREMENT,
                amount_enc       TEXT    NOT NULL,
                amount_hash      TEXT    NOT NULL,
                direction        INTEGER NOT NULL,
                merchant_enc     TEXT    NOT NULL,
                merchant_hash    TEXT    NOT NULL,
                category_id      TEXT    NOT NULL,
                category_name    TEXT    NOT NULL,
                occurred_at      INTEGER NOT NULL,
                source_id        TEXT    NOT NULL,
                txn_no           TEXT    NOT NULL DEFAULT '',
                note_enc         TEXT    NOT NULL DEFAULT '',
                auto_classified  INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_txn_time ON txn(occurred_at DESC)")
        db.execSQL("CREATE INDEX idx_txn_cat  ON txn(category_id, occurred_at DESC)")
        db.execSQL("CREATE INDEX idx_txn_dedup ON txn(amount_hash, merchant_hash, direction, occurred_at)")
        // 合并回填的候选查询：`amount_hash = ? AND direction = ? AND occurred_at BETWEEN ? AND ?`。
        // 已有的 idx_txn_dedup 把 merchant_hash 夹在中间，后两列没法用于范围扫描，故单建一条。
        db.execSQL("CREATE INDEX idx_txn_match ON txn(amount_hash, direction, occurred_at)")

        // 用户修正记忆：改过一次分类，以后同一商户自动沿用
        db.execSQL(
            """
            CREATE TABLE merchant_memory (
                merchant_hash TEXT PRIMARY KEY,
                category_id   TEXT NOT NULL,
                category_name TEXT NOT NULL,
                updated_at    INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // 规则版本等元信息
        db.execSQL("CREATE TABLE rule_meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 记账数据不做破坏性迁移：只补增量
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_txn_dedup ON txn(amount_hash, merchant_hash, direction, occurred_at)")
        }
        if (oldVersion < 3) {
            // 合并回填的候选查询索引。加索引不动数据，纯增量，无迁移风险。
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_txn_match ON txn(amount_hash, direction, occurred_at)")
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    companion object {
        const val DB_NAME = "ledger.db"
        const val DB_VERSION = 3
    }
}
