package com.ledger.offline.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 金额指纹的「分」换算。
 *
 * 这是全项目最容易被忽略、后果又最隐蔽的一处：算错不会崩、不会报错，
 * 只会让两笔不同的金额共用一个指纹，进而在去重时把后一笔当成重复丢掉。
 *
 * 只测 [FieldCipher.amountCents]（纯函数，不碰 Android API）。
 * [FieldCipher.amountHash] 需要 AndroidKeyStore，只能真机验证。
 */
class FieldCipherAmountTest {

    /**
     * 下列金额在 IEEE754 下 `amount * 100` 会**略小于**整数，
     * 用 `toLong()` 截断就少一分。它们曾经全都算错。
     */
    @Test
    fun `浮点误差金额必须四舍五入而不能截断`() {
        assertEquals("0.29*100 在 IEEE754 下是 28.999...，截断会得到 28", 29L, FieldCipher.amountCents(0.29))
        assertEquals(28L, FieldCipher.amountCents(0.28))
        assertEquals(57L, FieldCipher.amountCents(0.57))
        assertEquals(58L, FieldCipher.amountCents(0.58))
        assertEquals(113L, FieldCipher.amountCents(1.13))
        assertEquals(114L, FieldCipher.amountCents(1.14))
        assertEquals("2.00 与 2.01 曾是高频碰撞对", 201L, FieldCipher.amountCents(2.01))
        assertEquals(200L, FieldCipher.amountCents(2.00))
    }

    /** 常规金额与边界，确认没把本来对的算错 */
    @Test
    fun `常规金额与边界值`() {
        assertEquals(1L, FieldCipher.amountCents(0.01))
        assertEquals(10L, FieldCipher.amountCents(0.10))
        assertEquals("25.0 与 25.00 必须同指纹", 2500L, FieldCipher.amountCents(25.0))
        assertEquals(2500L, FieldCipher.amountCents(25.00))
        assertEquals(2510L, FieldCipher.amountCents(25.10))
        assertEquals(129900L, FieldCipher.amountCents(1299.00))
        assertEquals(390727L, FieldCipher.amountCents(3907.27))
        assertEquals("整数金额", 100000L, FieldCipher.amountCents(1000.0))
        assertEquals(0L, FieldCipher.amountCents(0.0))
    }

    /**
     * 穷举扫描：任意两个不同的金额都不得落进同一个指纹。
     *
     * 截断实现下这一条会失败——实测 0.01~1000.00 区间有 3426 个碰撞桶、
     * 涉及 6852 个金额（6.85%），首个碰撞对是 0.28/0.29。
     * 这个用例是它的事实来源，也是回归防线。
     */
    @Test
    fun `全金额区间内不允许两个不同金额撞同一指纹`() {
        val seen = HashMap<Long, Int>(200_000)
        var collisions = 0
        var firstPair = ""

        for (cents in 1..100_000) {                        // 0.01 ~ 1000.00
            val amount = cents / 100.0
            val key = FieldCipher.amountCents(amount)
            assertEquals("金额 ${cents / 100.0} 的分值算错", cents.toLong(), key)
            val prev = seen.put(key, cents)
            if (prev != null) {
                collisions++
                if (firstPair.isEmpty()) firstPair = "$prev 分 与 $cents 分 → 同一指纹 $key"
            }
        }

        assertEquals("存在金额碰撞（首个：$firstPair）", 0, collisions)
    }
}
