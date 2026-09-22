package com.ledger.offline.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountInputTest {

    private val EPS = 0.0

    @Test
    fun `正常金额`() {
        assertEquals(12.0, AmountInput.parse("12")!!, EPS)
        assertEquals(12.5, AmountInput.parse("12.5")!!, EPS)
        assertEquals(12.05, AmountInput.parse("12.05")!!, EPS)
        assertEquals(0.01, AmountInput.parse("0.01")!!, EPS)
    }

    @Test
    fun `带空白也能过：输入法常在末尾留空格`() {
        assertEquals(20.0, AmountInput.parse(" 20 ")!!, EPS)
    }

    @Test
    fun `小数超过两位一律拒绝`() {
        assertNull(AmountInput.parse("12.056"))
        assertNull(AmountInput.parse("1.234"))
    }

    @Test
    fun `零与负数不入账`() {
        assertNull(AmountInput.parse("0"))
        assertNull(AmountInput.parse("0.00"))
        assertNull(AmountInput.parse("-5"))
    }

    @Test
    fun `空串与孤立小数点`() {
        assertNull(AmountInput.parse(""))
        assertNull(AmountInput.parse("   "))
        assertNull(AmountInput.parse("."))
    }

    @Test
    fun `多个小数点拒绝`() {
        assertNull(AmountInput.parse("1.2.3"))
        assertNull(AmountInput.parse("..5"))
    }

    @Test
    fun `非数字字符拒绝`() {
        assertNull(AmountInput.parse("abc"))
        assertNull(AmountInput.parse("12a"))
        assertNull(AmountInput.parse("¥12"))
    }

    @Test
    fun `超出量级拒绝：手滑多点几个零不该落库`() {
        assertNull(AmountInput.parse("1000000001"))
        assertEquals(1_000_000_000.0, AmountInput.parse("1000000000")!!, EPS)
    }
}
