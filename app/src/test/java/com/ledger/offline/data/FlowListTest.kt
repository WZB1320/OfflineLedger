package com.ledger.offline.data

import com.ledger.offline.data.model.Direction
import com.ledger.offline.data.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class FlowListTest {

    private val unknown = "未识别商户"

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance(Locale.CHINA).apply {
            set(y, m, d, h, min, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun txn(
        id: Long,
        amount: Double,
        direction: Direction = Direction.EXPENSE,
        merchant: String = "星巴克",
        day: Int = 21,
        hour: Int = 9
    ) = Transaction(
        id = id,
        amount = amount,
        direction = direction,
        merchant = merchant,
        occurredAt = at(2026, Calendar.SEPTEMBER, day, hour, 0)
    )

    @Test
    fun `筛选按方向切分`() {
        val list = listOf(
            txn(1, 10.0, Direction.EXPENSE),
            txn(2, 20.0, Direction.INCOME)
        )
        assertEquals(2, FlowList.filter(list, FlowList.Filter.ALL, unknown).size)
        assertEquals(1, FlowList.filter(list, FlowList.Filter.EXPENSE, unknown).size)
        assertEquals(1, FlowList.filter(list, FlowList.Filter.INCOME, unknown).size)
    }

    @Test
    fun `未分类是空商户名和常量商户名的合集`() {
        val list = listOf(
            txn(1, 10.0, merchant = ""),
            txn(2, 10.0, merchant = unknown),
            txn(3, 10.0, merchant = "全家")
        )
        assertEquals(2, FlowList.filter(list, FlowList.Filter.UNCLASSIFIED, unknown).size)
    }

    @Test
    fun `分组按天倒序，且当天小计与行内金额一致`() {
        val list = listOf(
            txn(1, 32.0, day = 21, hour = 9),
            txn(2, 54.5, day = 21, hour = 12),
            txn(3, 18.6, day = 20, hour = 8),
            txn(4, 200.0, Direction.INCOME, day = 20, hour = 15)
        )
        val entries = FlowList.group(list)

        // 2 个分组头 + 4 行
        assertEquals(6, entries.size)
        val heads = entries.filterIsInstance<FlowList.Entry.DayHead>()
        assertEquals(2, heads.size)
        assertTrue(heads[0].dayStart > heads[1].dayStart)

        assertEquals(86.5, heads[0].expense, 0.001)
        assertEquals(0.0, heads[0].income, 0.001)
        assertEquals(18.6, heads[1].expense, 0.001)
        assertEquals(200.0, heads[1].income, 0.001)

        // 分组头后面紧跟的就是它那天的行
        val rows = entries.filterIsInstance<FlowList.Entry.Row>()
        assertEquals(4, rows.map { it.txn.id }.toSet().size)
    }

    @Test
    fun `入参未排序也能正确分组`() {
        val list = listOf(
            txn(1, 1.0, day = 19),
            txn(2, 2.0, day = 21),
            txn(3, 3.0, day = 20)
        )
        val heads = FlowList.group(list).filterIsInstance<FlowList.Entry.DayHead>()
        assertEquals(listOf(21, 20, 19), heads.map {
            Calendar.getInstance(Locale.CHINA).apply { timeInMillis = it.dayStart }
                .get(Calendar.DAY_OF_MONTH)
        })
    }

    @Test
    fun `空列表不产生分组头`() {
        assertTrue(FlowList.group(emptyList()).isEmpty())
    }
}
