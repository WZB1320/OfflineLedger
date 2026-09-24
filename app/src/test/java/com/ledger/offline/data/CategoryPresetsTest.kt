package com.ledger.offline.data

import com.google.gson.JsonParser
import com.ledger.offline.data.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 预置分类清单（[CategoryPresets]）的完整性。
 *
 * 这份清单是 DB v4 迁移的 seed 源、也是「历史 category_id 迁移后仍是合法值」
 * 这条硬约束的载体——三处锚点都必须在表里：
 *   1. classify_rules.json 的全部一级 id（关键词规则的命中目标）
 *   2. parser_rules.json 官方种子映射的全部目标 id（支付宝「交易分类」列）
 *   3. 兜底 id（其它两处文件里的 fallback）
 * 少一个，迁移当天就有一批旧账指向不存在的分类。
 *
 * 直接读 assets 原始文件做比对（不走 RuleStore——org.json 在 JVM 单测里是 stub）。
 */
class CategoryPresetsTest {

    private fun locate(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("找不到文件：${candidates.joinToString()} —— 拿不到证据就判失败，不跳过")

    private fun classifyRules(): com.google.gson.JsonObject =
        JsonParser.parseString(
            locate(
                "src/main/assets/classify_rules.json",
                "app/src/main/assets/classify_rules.json"
            ).readText()
        ).asJsonObject

    private fun parserRules(): com.google.gson.JsonObject =
        JsonParser.parseString(
            locate(
                "src/main/assets/parser_rules.json",
                "app/src/main/assets/parser_rules.json"
            ).readText()
        ).asJsonObject

    private fun categories(): List<Category> = CategoryPresets.ALL.map {
        Category(id = it.id, name = it.name, parentId = it.parentId, isCustom = false, sort = it.sort)
    }

    @Test
    fun `id 与 name 全表唯一`() {
        val presets = CategoryPresets.ALL
        assertEquals(
            "预置 id 有重复——id 重复会让 seed 的 INSERT OR IGNORE 静默丢行",
            presets.size, presets.map { it.id }.toSet().size
        )
        assertEquals(
            "预置 name 有重复——categories.name 有 UNIQUE 约束，重名 seed 会静默丢行",
            presets.size, presets.map { it.name }.toSet().size
        )
    }

    @Test
    fun `每个二级的父级都存在且是一级`() {
        val tops = CategoryPresets.TOP_IDS
        val orphans = CategoryPresets.ALL.filter { it.parentId.isNotEmpty() && it.parentId !in tops }
        assertTrue("二级的 parentId 指向不存在的分类：$orphans", orphans.isEmpty())
    }

    @Test
    fun `sort 全表唯一且单调`() {
        val sorts = CategoryPresets.ALL.map { it.sort }
        assertEquals("sort 有重复，全表按 sort 排序的展示顺序就不确定", sorts.size, sorts.toSet().size)
        assertEquals(
            "预置清单的声明顺序必须与 sort 排序一致（UI 直接依赖这份顺序）",
            CategoryPresets.ALL.map { it.id },
            CategoryPresets.ALL.sortedBy { it.sort }.map { it.id }
        )
    }

    @Test
    fun `classify_rules 的全部一级 id 与 fallback 都在预置一级里`() {
        val root = classifyRules()
        val ids = root.getAsJsonArray("categories")
            .map { it.asJsonObject.get("id").asString }
        val fallbackId = root.getAsJsonObject("fallback").get("id").asString

        val missing = (ids + fallbackId).filter { it !in CategoryPresets.TOP_IDS }
        assertTrue(
            "关键词规则引用了一级 id，但预置表里没有——旧账/新规则会指向不存在的分类：$missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `支付宝官方种子的全部目标 id 都在预置一级里`() {
        val root = parserRules()
        val targets = root.getAsJsonArray("importProfiles")
            .flatMap { it.asJsonObject.getAsJsonObject("categorySeed")?.getAsJsonObject("map")
                ?.entrySet()?.map { e -> e.value.asString } ?: emptyList() }

        val missing = targets.filter { it !in CategoryPresets.TOP_IDS }
        assertTrue(
            "官方分类种子指向了预置表之外的 id——回填后那批账会指向不存在的分类：$missing",
            missing.isEmpty()
        )
    }

    @Test
    fun `兜底 id 是预置一级`() {
        assertTrue(
            "FALLBACK_ID 必须是预置一级（未知商户与未命中关键词的账都落到它）",
            CategoryPresets.FALLBACK_ID in CategoryPresets.TOP_IDS
        )
        assertNotNull(CategoryPresets.ALL.firstOrNull { it.id == CategoryPresets.FALLBACK_ID })
    }

    @Test
    fun `新增的社交与旅行两个一级已就位`() {
        assertTrue("social（社交）一级缺失", "social" in CategoryPresets.TOP_IDS)
        assertTrue("travel（旅行）一级缺失", "travel" in CategoryPresets.TOP_IDS)
    }

    @Test
    fun `电费-车挂在生活缴费下`() {
        val preset = CategoryPresets.ALL.firstOrNull { it.id == CategoryPresets.CAR_POWER_ID }
        assertNotNull(
            "预置里没有「电费-车」——DB v6 迁移对已装用户补种的就是这条，丢了它升级后用户看不到该分类",
            preset
        )
        assertEquals("「电费-车」必须挂在 living（生活缴费）下", "living", preset?.parentId)
        assertEquals("「电费-车」的显示名与用户要求不符", "电费-车", preset?.name)
    }

    @Test
    fun `记一笔默认分类在预置里且是二级`() {
        val preset = CategoryPresets.ALL.firstOrNull { it.id == CategoryPresets.DEFAULT_ADD_CATEGORY_ID }
        assertNotNull(
            "记一笔默认分类不在预置里——进记一笔页 CategoryPicker 会静默回退到「其他」",
            preset
        )
        assertTrue(
            "记一笔默认分类应为二级（用户要求默认「餐饮-买菜」），一级无法体现高频场景的精度",
            preset != null && preset.parentId.isNotEmpty()
        )
    }
}
