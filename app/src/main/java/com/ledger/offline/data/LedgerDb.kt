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

        createCategories(db)
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
        if (oldVersion < 4) {
            // 分类树：两级分类（一级 + 二级）+ 用户自定义。
            // 建表 + 预置 seed，不动既有 txn / merchant_memory 的任何一行——
            // 历史 category_id（全部是预置一级 id）在新表里仍是合法值。
            createCategories(db)
        }
        if (oldVersion < 5) {
            // 预置分类清单调整（0.2.8→0.2.9）：
            //   娱乐二级「影音会员」(entertainment.media) 移除，换成「旅游」等
            //   餐饮 +水果/买菜/油盐酱醋、娱乐 +旅游/花鸟宠物、教育 +幼儿教育、社交 +孝敬
            // 先把指向 entertainment.media 的历史 txn / 商户记忆回退到父级 entertainment，
            // 再删该行，最后 INSERT OR IGNORE 补入全部新预置（已有的不动，新的补进去）。
            upgradeCategoriesV5(db)
        }
        if (oldVersion < 6) {
            // 0.2.9→0.2.10：生活缴费新增二级「电费-车」（用户电动车电费走生活缴费口径）。
            // 纯增量补种：INSERT OR IGNORE，用户若已自建同名分类则不动（用户数据优先）。
            val p = CategoryPresets.ALL.first { it.id == CategoryPresets.CAR_POWER_ID }
            db.execSQL(
                "INSERT OR IGNORE INTO categories(id, name, parent_id, is_custom, sort) VALUES(?, ?, ?, ?, ?)",
                arrayOf(p.id, p.name, p.parentId, "0", p.sort.toString())
            )
        }
    }

    /**
     * 建 categories 表并写入预置清单。
     *
     * onCreate（新装）与 onUpgrade v4（老用户）共用同一条路，所以必须幂等：
     * CREATE TABLE IF NOT EXISTS + INSERT OR IGNORE。后者还顺带兜住一种边角——
     * 用户自建了与预置同名的分类时（理论上有 UNIQUE 拦着，不该发生），
     * 被忽略的是预置那行，用户的自定义永远优先。
     */
    private fun createCategories(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS categories (
                id        TEXT PRIMARY KEY,
                name      TEXT NOT NULL UNIQUE,
                parent_id TEXT NOT NULL DEFAULT '',
                is_custom INTEGER NOT NULL DEFAULT 0,
                sort      INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        seedCategories(db)
    }

    /**
     * v5 迁移：调整预置分类清单。
     *
     * 1) entertainment.media「影音会员」被移除——先回退其下 txn / merchant_memory
     *    到父级 entertainment（不丢账），再 DELETE 该行。
     * 2) 全表 INSERT OR IGNORE 补入新预置（水果/买菜/油盐酱醋/旅游/花鸟宠物/
     *    幼儿教育/孝敬）。已存在的行（包括用户在 0.2.8 上新建的同名自定义）
     *    不被覆盖——用户数据永远优先。
     * 3) 更新既有预置行的 sort，使展示顺序与新清单一致。
     */
    private fun upgradeCategoriesV5(db: SQLiteDatabase) {
        // 回退 entertainment.media 的账目到父级
        db.execSQL(
            "UPDATE txn SET category_id = 'entertainment', category_name = '娱乐' WHERE category_id = 'entertainment.media'"
        )
        db.execSQL(
            "UPDATE merchant_memory SET category_id = 'entertainment', category_name = '娱乐' WHERE category_id = 'entertainment.media'"
        )
        db.execSQL("DELETE FROM categories WHERE id = 'entertainment.media'")

        // 补入新预置 + 更新 sort
        for (p in CategoryPresets.ALL) {
            db.execSQL(
                "INSERT OR IGNORE INTO categories(id, name, parent_id, is_custom, sort) VALUES(?, ?, ?, ?, ?)",
                arrayOf(p.id, p.name, p.parentId, "0", p.sort.toString())
            )
            // 已存在的预置行：更新 sort 保持展示顺序
            db.execSQL(
                "UPDATE categories SET sort = ? WHERE id = ? AND is_custom = 0",
                arrayOf(p.sort.toString(), p.id)
            )
        }
    }

    /** 把 [CategoryPresets.ALL] 写入 categories 表（INSERT OR IGNORE，幂等） */
    private fun seedCategories(db: SQLiteDatabase) {
        for (p in CategoryPresets.ALL) {
            db.execSQL(
                "INSERT OR IGNORE INTO categories(id, name, parent_id, is_custom, sort) VALUES(?, ?, ?, ?, ?)",
                arrayOf(p.id, p.name, p.parentId, "0", p.sort.toString())
            )
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    companion object {
        const val DB_NAME = "ledger.db"
        const val DB_VERSION = 6
    }
}
