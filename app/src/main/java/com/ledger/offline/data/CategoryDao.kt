package com.ledger.offline.data

import android.content.ContentValues
import com.ledger.offline.data.model.Category
import java.util.UUID

/**
 * 分类树的读写。
 *
 * - 查询：[all] 一份名单推导一切（选择器、管理页、统计聚合），不在 UI 层另存。
 * - 自定义：可增、可删、可同级排序；预置（is_custom=0）不可删。
 * - 删除回退：删自定义分类时，指向它的历史 txn 与商户记忆都改指其父级
 *   （删的是自定义一级则回退「其他」）——分类没了，账一条都不丢。
 *
 * SQL 本身要真机才跑得起来，但「回退到哪、和谁交换 sort」这类判定是纯逻辑，
 * 全部抽到 companion，在 JVM 单测里钉死（参照 TransactionDao.duplicateProbeSql 的做法）。
 */
class CategoryDao(private val db: LedgerDb) {

    /** 全部分类，按 sort 升序（一级与其二级天然聚在一起，见 CategoryPresets 的 sort 设计） */
    fun all(): List<Category> {
        val out = ArrayList<Category>()
        db.readableDatabase.rawQuery(
            "SELECT id, name, parent_id, is_custom, sort FROM categories ORDER BY sort ASC", null
        ).use { c ->
            while (c.moveToNext()) {
                out += Category(
                    id = c.getString(0),
                    name = c.getString(1),
                    parentId = c.getString(2),
                    isCustom = c.getInt(3) == 1,
                    sort = c.getInt(4)
                )
            }
        }
        return out
    }

    /**
     * 新增自定义分类。
     *
     * @param parentId 一级 id（挂成二级）；空串 = 新建一级
     * @return false = 名字已被占用（预置与自定义共用一条 UNIQUE 约束，重名一律拦）
     */
    fun addCustom(name: String, parentId: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        // parentId 必须是空串（一级）或某个一级的 id——不允许三级
        if (parentId.isNotEmpty()) {
            val parent = all().firstOrNull { it.id == parentId }
                ?.takeIf { it.parentId.isEmpty() }
                ?: return false
        }
        db.readableDatabase.rawQuery(
            "SELECT 1 FROM categories WHERE name = ? LIMIT 1", arrayOf(trimmed)
        ).use { if (it.moveToFirst()) return false }

        val values = ContentValues().apply {
            put("id", "c_" + UUID.randomUUID().toString().replace("-", ""))
            put("name", trimmed)
            put("parent_id", parentId)
            put("is_custom", 1)
            put("sort", nextSort())
        }
        return db.writableDatabase.insert("categories", null, values) != -1L
    }

    /** 删除自定义分类。其下历史记录回退到父级（自定义一级删则回「其他」），不丢账 */
    fun removeCustom(id: String): Removal {
        val target = all().firstOrNull { it.id == id } ?: return Removal.NOT_FOUND
        if (!target.isCustom) return Removal.NOT_CUSTOM
        if (all().any { it.parentId == id }) return Removal.HAS_CHILDREN

        val back = fallbackTarget(target, all())
        val wdb = db.writableDatabase
        // txn.category_name 是反范式冗余列，id 和 name 必须一起改，否则列表显示旧名
        wdb.execSQL(
            "UPDATE txn SET category_id = ?, category_name = ? WHERE category_id = ?",
            arrayOf(back.id, back.name, id)
        )
        // 修正记忆同样回退：不清的话，下次同商户又会命中已删除的分类
        wdb.execSQL(
            "UPDATE merchant_memory SET category_id = ?, category_name = ? WHERE category_id = ?",
            arrayOf(back.id, back.name, id)
        )
        wdb.execSQL("DELETE FROM categories WHERE id = ?", arrayOf(id))
        return Removal.OK
    }

    /** 同级内上移/下移（交换相邻同级的 sort）。已在边界时返回 false */
    fun move(id: String, up: Boolean): Boolean {
        val plan = movePlan(all(), id, up) ?: return false
        val wdb = db.writableDatabase
        wdb.execSQL("UPDATE categories SET sort = ? WHERE id = ?", arrayOf(plan.sortB.toString(), plan.idA))
        wdb.execSQL("UPDATE categories SET sort = ? WHERE id = ?", arrayOf(plan.sortA.toString(), plan.idB))
        return true
    }

    /** id → 顶级 id 的映射（含一级映射到自身）。调用方拿 `map[id] ?: id` 兜住未知 id */
    fun rollupMap(): Map<String, String> = rollup(all())

    /** 新分类排在全表末尾（跨级跳号 +10，避免与任何既有 sort 相撞） */
    private fun nextSort(): Int =
        ((all().maxOfOrNull { it.sort } ?: 0) / 10 + 1) * 10

    enum class Removal { OK, NOT_FOUND, NOT_CUSTOM, HAS_CHILDREN }

    companion object {

        /**
         * 统计聚合用的 id 折叠表：任何 id → 一级 id。
         *
         * - 一级 → 自身
         * - 二级（含自定义二级）→ 沿 parent_id 上溯到一级
         * - 孤儿（父 id 不在表里，理论上 removeCustom 已杜绝）→ 兜底「其他」，
         *   绝不让一笔账因为映射缺失而从统计里消失
         */
        fun rollup(categories: List<Category>): Map<String, String> {
            val byId = categories.associateBy { it.id }
            val out = HashMap<String, String>(categories.size * 2)
            for (c in categories) {
                var cur = c
                var hops = 0
                // 上溯最多两级（树就两级深），保险丝防自引用环把循环挂死
                while (cur.parentId.isNotEmpty() && hops < 4) {
                    cur = byId[cur.parentId] ?: break
                    hops++
                }
                out[c.id] = if (cur.parentId.isEmpty()) cur.id else CategoryPresets.FALLBACK_ID
            }
            return out
        }

        /**
         * 删除自定义分类时历史记录的回退目标：
         * 二级 → 其父级；一级（无父）→ 兜底「其他」。
         */
        data class FallbackTarget(val id: String, val name: String)

        fun fallbackTarget(target: Category, categories: List<Category>): FallbackTarget {
            if (target.parentId.isEmpty()) {
                val fb = categories.firstOrNull { it.id == CategoryPresets.FALLBACK_ID }
                return FallbackTarget(CategoryPresets.FALLBACK_ID, fb?.name ?: "其他")
            }
            val parent = categories.firstOrNull { it.id == target.parentId }
                ?: return FallbackTarget(CategoryPresets.FALLBACK_ID, "其他")
            return FallbackTarget(parent.id, parent.name)
        }

        /** move 的纯逻辑：找出要交换 sort 的另一行。已在同级边界 / id 不存在 → null */
        data class MovePlan(val idA: String, val sortA: Int, val idB: String, val sortB: Int)

        fun movePlan(categories: List<Category>, id: String, up: Boolean): MovePlan? {
            val target = categories.firstOrNull { it.id == id } ?: return null
            val siblings = categories
                .filter { it.parentId == target.parentId }
                .sortedBy { it.sort }
            val idx = siblings.indexOfFirst { it.id == id }
            if (idx < 0) return null
            val other = if (up) idx - 1 else idx + 1
            if (other < 0 || other >= siblings.size) return null
            val neighbor = siblings[other]
            return MovePlan(target.id, target.sort, neighbor.id, neighbor.sort)
        }

        /**
         * 「常用分类」的入选规则（0.2.11）：
         * 出现 ≥ [minCount] 次的分类，按频次降序取前 [limit] 个。
         *
         * - 过滤 < minCount：偶发一次的不算「常用」，也避免新装机器把第一笔顶上常用区
         * - 并列按 id 字典序决胜：展示顺序不随 SQLite GROUP BY 的返回顺序漂移
         * - 「选中态」不在 chips 上表达：那是一份额外状态，与下方网格的选中不同步
         *   就是一个新 bug。chips 只是快捷入口，选中仍由网格表达
         */
        fun topUsed(counts: Map<String, Int>, minCount: Int = 2, limit: Int = 8): List<String> =
            counts.entries
                .filter { it.value >= minCount }
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(limit)
                .map { it.key }
    }
}
