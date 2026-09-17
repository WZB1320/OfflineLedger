package com.ledger.offline.classify

import com.ledger.offline.parse.CategoryRule
import com.ledger.offline.parse.ClassifyRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantNormalizerTest {

    @Test
    fun `剥掉门店后缀`() {
        assertEquals("星巴克", MerchantNormalizer.normalize("星巴克(浦东世纪汇店)"))
        assertEquals("星巴克", MerchantNormalizer.normalize("星巴克（上海）有限公司"))
    }

    @Test
    fun `剥掉平台前缀`() {
        assertEquals("星巴克", MerchantNormalizer.normalize("支付宝-星巴克"))
        assertEquals("全家", MerchantNormalizer.normalize("微信支付 - 全家"))
    }

    @Test
    fun `空输入返回未识别商户而不是空串`() {
        assertEquals(MerchantNormalizer.UNKNOWN_MERCHANT, MerchantNormalizer.normalize("   "))
    }

    @Test
    fun `不该把两家不同的店合并`() {
        assertTrue(MerchantNormalizer.normalize("海底捞火锅") != MerchantNormalizer.normalize("海底捞"))
    }
}

class ClassifierTest {

    private val rules = ClassifyRules(
        version = 1,
        categories = listOf(
            CategoryRule("food", "餐饮", listOf("美团", "星巴克", "肯德基", "外卖")),
            CategoryRule("transport", "交通", listOf("滴滴", "地铁", "加油")),
            CategoryRule("living", "生活缴费", listOf("话费", "电费", "充值"))
        ),
        fallback = CategoryRule("other", "其他", emptyList())
    )

    private val emptyMemory = { emptyMap<String, Pair<String, String>>() }

    private fun classifier(memory: Map<String, Pair<String, String>> = emptyMap()) =
        Classifier(rules, { memory }, { it })   // 测试里用恒等哈希，避免依赖 Keystore

    @Test
    fun `关键词命中商户名`() {
        val result = classifier().classify("星巴克", "")
        assertEquals("food", result.categoryId)
    }

    @Test
    fun `长关键词优先`() {
        // 「美团外卖」同时含「美团」和「外卖」，但两者同属餐饮，这里验证不会误判到别类
        val result = classifier().classify("美团外卖", "")
        assertEquals("food", result.categoryId)
    }

    @Test
    fun `商户名认不出时退一步看整段文案`() {
        val result = classifier().classify("某某科技", "话费充值")
        assertEquals("living", result.categoryId)
    }

    @Test
    fun `都不命中走兜底`() {
        val result = classifier().classify("张三", "随手一转")
        assertEquals("other", result.categoryId)
    }

    @Test
    fun `用户修正记忆优先级最高`() {
        // 「滴滴」按规则是交通，但用户手动改成「餐饮」后应永久生效
        val memory = mapOf("滴滴" to ("food" to "餐饮"))
        val result = classifier(memory).classify("滴滴", "")
        assertEquals("food", result.categoryId)
        assertTrue(result.fromMemory)
    }
}
