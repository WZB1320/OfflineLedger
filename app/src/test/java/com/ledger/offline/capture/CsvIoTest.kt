package com.ledger.offline.capture

import com.ledger.offline.data.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 只测纯函数。CsvIo 里涉及 ContentResolver 的部分属于集成测试，
 * 要在真机 / 仪器测试里跑。
 */
class CsvIoTest {

    @Test
    fun `带引号和逗号的字段能正确切分`() {
        val cells = CsvIo.splitCsvLine("""2026-01-15 12:30:00,星巴克(浦东店),"咖啡,拿铁",支出,¥25.00""")
        assertEquals(5, cells.size)
        assertEquals("咖啡,拿铁", cells[2])
        assertEquals("支出", cells[3])
    }

    @Test
    fun `双引号转义`() {
        val cells = CsvIo.splitCsvLine("""a,"说""好""的",c""")
        assertEquals("说\"好\"的", cells[1])
    }

    @Test
    fun `金额能剥掉货币符号和千分位`() {
        assertEquals(25.0, CsvIo.parseAmount("¥25.00")!!, 0.001)
        assertEquals(1299.0, CsvIo.parseAmount("￥1,299.00")!!, 0.001)
        assertEquals(9.9, CsvIo.parseAmount("9.90元")!!, 0.001)
        assertNull(CsvIo.parseAmount("待确认"))
    }

    @Test
    fun `时间能解析微信和支付宝两种格式`() {
        assertNotNull(CsvIo.parseTime("2026-01-15 12:30:00"))
        assertNotNull(CsvIo.parseTime("2026/01/15 12:30:00"))
        assertNull(CsvIo.parseTime(""))
    }

    @Test
    fun `收支方向按文案判定`() {
        assertEquals(Direction.EXPENSE, CsvIo.parseDirection("支出", "25.00"))
        assertEquals(Direction.INCOME, CsvIo.parseDirection("收入", "25.00"))
        // 表格没给收支列时看金额符号
        assertEquals(Direction.EXPENSE, CsvIo.parseDirection("/", "-25.00"))
        assertNull(CsvIo.parseDirection("/", "25.00"))
    }
}
