package com.ledger.offline.data

import com.ledger.offline.data.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CategoryDao] companion 里纯逻辑的口径：统计折叠、删除回退、同级排序。
 * SQL 要真机才跑得起来，但这三条判定直接决定「账会不会丢、顺序对不对」，
 * 必须在 JVM 单测里钉死（参照 TransactionDao.duplicateProbeSql 的做法）。
 */
class CategoryDaoLogicTest {

    private fun cat(id: String, parentId: String = "", sort: Int = 0, custom: Boolean = false) =
        Category(id = id, name = id, parentId = parentId, isCustom = custom, sort = sort)

    // ------------------------------------------------------------ rollup

    @Test
    fun `二级折叠到一级`() {
        val cats = listOf(
            cat("food"), cat("food.takeout", "food"),
            cat("transport"), cat("transport.taxi", "transport")
        )
        val map = CategoryDao.rollup(cats)
        assertEquals("food", map["food.takeout"])
        assertEquals("transport", map["transport.taxi"])
    }

    @Test
    fun `一级映射到自身`() {
        val map = CategoryDao.rollup(listOf(cat("food"), cat("other")))
        assertEquals("food", map["food"])
        assertEquals("other", map["other"])
    }

    @Test
    fun `孤儿折叠到兜底而不是从统计里消失`() {
        // removeCustom 已保证不会产生孤儿，但统计层不能建立在「绝不会」上：
        // 一旦真出现，账要落进「其他」而不是凭空消失
        val map = CategoryDao.rollup(listOf(cat("other"), cat("ghost.child", "no-such-parent")))
        assertEquals(CategoryPresets.FALLBACK_ID, map["ghost.child"])
    }

    @Test
    fun `未知 id 由调用方兜底，不进映射`() {
        val map = CategoryDao.rollup(listOf(cat("food")))
        assertNull("未在表里的 id 不该出现在映射里", map["not-in-table"])
    }

    // ------------------------------------------------------------ 删除回退

    @Test
    fun `删自定义二级回退到其父级`() {
        val cats = listOf(
            cat("food", sort = 10), cat("food.takeout", "food", custom = true),
            cat("other", sort = 130)
        )
        val back = CategoryDao.fallbackTarget(cats[1], cats)
        assertEquals("food", back.id)
        assertEquals("food", back.name)
    }

    @Test
    fun `删自定义一级回退到兜底其他`() {
        val cats = listOf(
            cat("other", sort = 130).let { Category(it.id, "其他", it.parentId, false, it.sort) },
            cat("mine", custom = true)
        )
        val back = CategoryDao.fallbackTarget(cats[1], cats)
        assertEquals("other", back.id)
        assertEquals("其他", back.name)
    }

    // ------------------------------------------------------------ 同级排序

    private val siblings = listOf(
        cat("a", sort = 10),
        cat("b", sort = 20),
        cat("c", sort = 30),
        cat("food", sort = 40),
        cat("food.x", "food", sort = 41),
        cat("food.y", "food", sort = 42)
    )

    @Test
    fun `上移与相邻同级交换`() {
        val plan = CategoryDao.movePlan(siblings, "b", up = true)!!
        assertEquals("b", plan.idA)
        assertEquals("a", plan.idB)
        assertEquals(20, plan.sortA)
        assertEquals(10, plan.sortB)
    }

    @Test
    fun `下移与相邻同级交换`() {
        val plan = CategoryDao.movePlan(siblings, "b", up = false)!!
        assertEquals("c", plan.idB)
    }

    @Test
    fun `只在同级内找交换对象，不跨级`() {
        // food.x 的同级是 food.y；它下移绝不能换到一级 b 头上
        val plan = CategoryDao.movePlan(siblings, "food.x", up = false)!!
        assertEquals("food.y", plan.idB)
        assertEquals(42, plan.sortB)
    }

    @Test
    fun `同级边界返回 null 不动`() {
        assertNull(CategoryDao.movePlan(siblings, "a", up = true))
        assertNull(CategoryDao.movePlan(siblings, "food.y", up = false))
    }

    @Test
    fun `不存在的 id 返回 null`() {
        assertNull(CategoryDao.movePlan(siblings, "no-such", up = true))
    }

    // ------------------------------------------------------------ 预置清单喂进纯逻辑的联动

    @Test
    fun `预置清单整体 rollup 后每个二级都指回自己的一级`() {
        val cats = CategoryPresets.ALL.map {
            Category(it.id, it.name, it.parentId, false, it.sort)
        }
        val map = CategoryDao.rollup(cats)
        for (p in CategoryPresets.ALL) {
            if (p.parentId.isEmpty()) continue
            assertEquals("二级 ${p.id} 折叠错了", p.parentId, map[p.id])
        }
        assertTrue(map.values.all { it in CategoryPresets.TOP_IDS })
    }

    // ------------------------------------------------------------ 常用分类（topUsed）

    @Test
    fun `常用分类按频次降序取前八个`() {
        val counts = mapOf(
            "food.grocery" to 12, "food.takeout" to 30, "transport.taxi" to 5,
            "shopping.daily" to 9, "food" to 8, "medical.medicine" to 2,
            "housing.rent" to 3, "social.gifts" to 4, "living.phone" to 6
        )
        assertEquals(
            listOf(
                "food.takeout", "food.grocery", "shopping.daily", "food",
                "living.phone", "transport.taxi", "social.gifts", "housing.rent"
            ),
            CategoryDao.topUsed(counts)
        )
    }

    @Test
    fun `少于两次的偶发分类进不了常用区`() {
        val counts = mapOf("food.grocery" to 1, "food.takeout" to 2)
        assertEquals(
            "记过一次就上榜，新装机器的第一笔会立刻占住常用区——偶发不算常用",
            listOf("food.takeout"), CategoryDao.topUsed(counts)
        )
    }

    @Test
    fun `频次并列按 id 字典序决胜`() {
        // SQLite GROUP BY 的返回顺序不保证，并列时若无决胜规则，展示顺序会漂移
        val counts = mapOf("transport.taxi" to 3, "food.takeout" to 3, "food" to 3)
        assertEquals(
            listOf("food", "food.takeout", "transport.taxi"),
            CategoryDao.topUsed(counts)
        )
    }

    @Test
    fun `空账本与全部未达标都返回空表`() {
        assertTrue(CategoryDao.topUsed(emptyMap()).isEmpty())
        assertTrue(CategoryDao.topUsed(mapOf("food" to 1, "other" to 1)).isEmpty())
    }
}
