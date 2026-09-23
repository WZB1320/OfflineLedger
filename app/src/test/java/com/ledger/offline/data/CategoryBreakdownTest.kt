package com.ledger.offline.data

import com.ledger.offline.data.model.Direction
import com.ledger.offline.data.model.Transaction
import com.ledger.offline.parse.CategoryRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统计口径。
 *
 * 最该守的是「未分类 ⊂ 其他」：两者在库里是同一个 category_id，
 * 没有商户名 ⇒ 关键词必然不命中 ⇒ 分类落到 fallback。
 * 条形图为了提示「该补商户名」把它们拆开画，但 §9 的验收线必须按**合并口径**算，
 * 只算「未分类」会漏掉一半。
 */
class CategoryBreakdownTest {

    private val food = CategoryRule("food", "餐饮", listOf("星巴克"), "#D9730D")
    private val transport = CategoryRule("transport", "交通", listOf("滴滴"), "#3A7CA5")
    private val fallback = CategoryRule("other", "其他", emptyList(), "#8A8A82")
    private val unknown = "未识别商户"

    private fun t(amount: Double, categoryId: String, merchant: String) = Transaction(
        amount = amount,
        direction = Direction.EXPENSE,
        merchant = merchant,
        categoryId = categoryId
    )

    @Test
    fun `分类金额与占比`() {
        val list = listOf(
            t(700.0, "food", "星巴克"),
            t(300.0, "transport", "滴滴")
        )
        val r = CategoryBreakdown.of(list, listOf(food, transport), fallback, unknown)
        assertEquals(1000.0, r.totalExpense, 0.001)
        assertEquals(700.0, r.slices.first { it.categoryId == "food" }.amount, 0.001)
        assertEquals(0.7, r.slices.first { it.categoryId == "food" }.ratio, 0.001)
        assertEquals(2, r.count)
    }

    /**
     * 数字取自设计稿屏 3（当月支出 ¥3,281.40，未分类 206.00 + 其他 178.50）：
     * 直接用那组数字，等于把设计稿的「11.7% 达标」变成可执行断言。
     */
    @Test
    fun `未分类与其他拆开画，但验收按合并口径算`() {
        val list = listOf(
            t(2896.90, "food", "星巴克"),
            t(206.0, "other", unknown),   // 无商户名 → 未分类
            t(178.5, "other", "某某店")    // 有商户名但没命中 → 其他
        )
        val r = CategoryBreakdown.of(list, listOf(food, transport), fallback, unknown)

        assertEquals(3281.40, r.totalExpense, 0.001)
        val unclassifiedSlice = r.slices.first { it.unclassified }
        val otherSlice = r.slices.first { it.categoryId == "other" && !it.unclassified }
        assertEquals(206.0, unclassifiedSlice.amount, 0.001)
        assertEquals(178.5, otherSlice.amount, 0.001)

        // 合并口径：(206 + 178.5) / 3281.4 ≈ 11.7% → 达标
        assertEquals(384.5, r.unclassifiedAmount, 0.001)
        assertEquals(0.1172, r.unclassifiedRatio, 0.001)
        assertTrue(r.accepted())
    }

    @Test
    fun `只算未分类会漏掉一半，验收结论可能反过来`() {
        val list = listOf(
            t(700.0, "food", "星巴克"),
            t(100.0, "other", unknown),
            t(400.0, "other", "某某店")
        )
        val r = CategoryBreakdown.of(list, listOf(food, transport), fallback, unknown)
        // 只看「未分类」：100/1200 = 8.3% → 误判达标
        // 合并口径：500/1200 = 41.7% → 实际严重超标
        assertEquals(0.083, r.slices.first { it.unclassified }.ratio, 0.001)
        assertEquals(0.4167, r.unclassifiedRatio, 0.001)
        assertFalse(r.accepted())
    }

    @Test
    fun `收入不计入分类占比`() {
        val list = listOf(
            t(700.0, "food", "星巴克"),
            Transaction(amount = 12000.0, direction = Direction.INCOME, merchant = "公司", categoryId = "other")
        )
        val r = CategoryBreakdown.of(list, listOf(food), fallback, unknown)
        assertEquals(700.0, r.totalExpense, 0.001)
        assertEquals(1, r.count)
    }

    @Test
    fun `没有支出时不产生 NaN`() {
        val r = CategoryBreakdown.of(emptyList(), listOf(food), fallback, unknown)
        assertEquals(0.0, r.totalExpense, 0.001)
        assertEquals(0.0, r.unclassifiedRatio, 0.001)
        assertTrue(r.slices.isEmpty())
    }

    @Test
    fun `占比按金额从大到小排`() {
        val list = listOf(
            t(100.0, "food", "星巴克"),
            t(900.0, "transport", "滴滴")
        )
        val r = CategoryBreakdown.of(list, listOf(food, transport), fallback, unknown)
        assertEquals(listOf("transport", "food"), r.slices.map { it.categoryId })
    }

    // ------------------------------------------------------------ 二级按一级聚合（v4 分类树）

    /**
     * 记录可以落在二级（txn.category_id 指向最细粒度），
     * 统计必须折叠回一级出条形——否则「早餐 + 午餐」会和「餐饮」并列，
     * 同一笔钱的口径被拆成三个条目，占比加起来不是 100%。
     */
    @Test
    fun `二级折叠到一级后聚合`() {
        val rollup = mapOf(
            "food.breakfast" to "food",
            "food.takeout" to "food",
            "transport.taxi" to "transport"
        )
        val list = listOf(
            t(20.0, "food.breakfast", "包子铺"),
            t(35.0, "food.takeout", "美团"),
            t(15.0, "transport.taxi", "滴滴"),
            t(30.0, "food", "星巴克")
        )
        val r = CategoryBreakdown.of(list, listOf(food, transport), fallback, unknown, rollup)

        val foodSlice = r.slices.first { it.categoryId == "food" }
        // 20 + 35 + 30：二级的两笔与一级的一笔都归到「餐饮」
        assertEquals(85.0, foodSlice.amount, 0.001)
        assertEquals(3, foodSlice.count)
        val transportSlice = r.slices.first { it.categoryId == "transport" }
        assertEquals(15.0, transportSlice.amount, 0.001)
        assertEquals(100.0, r.totalExpense, 0.001)
    }

    @Test
    fun `未折叠的二级 id 回退到一级口径`() {
        // rollup 缺了映射（调用方兜底失败时的防御）：未知 id 保持原样单独成条，
        // 不能让这笔账从统计里消失
        val list = listOf(t(10.0, "food.breakfast", "包子铺"))
        val r = CategoryBreakdown.of(list, listOf(food), fallback, unknown)
        // 不传 rollup 时 food.breakfast 不在 byId 里 —— sum 条目被跳过但不丢总额
        assertEquals(10.0, r.totalExpense, 0.001)
    }
}
