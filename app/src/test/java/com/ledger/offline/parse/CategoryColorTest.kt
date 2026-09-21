package com.ledger.offline.parse

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 分类识别色的两条硬约束（2026-09-21 定）：
 *
 *  1. **分类名单与识别色同源**：都在 `classify_rules.json` 里。UI 层不另存一份名单，
 *     所以「改了规则文件、UI 没跟上」这类漂移从结构上就不可能发生——前提是这份文件
 *     真的给每个分类都写了 color。
 *  2. **识别色不得与基础色同值**。犯过一次：交通 `#185FA5` = brand、医疗 `#C0392B` = expense、
 *     教育 `#1E8449` = income，结果同屏里金额数字和分类圆点一个颜色，读不出来。
 *
 * 这两条靠人眼在真机上撞见太晚，所以钉在这里：直接读 assets 与 res 的**原始文件**
 * 做比对（不走 RuleStore——org.json 在 JVM 单测里是 stub，走了就什么都验不到）。
 */
class CategoryColorTest {

    private fun locate(vararg candidates: String): File =
        candidates.map(::File).firstOrNull { it.exists() }
            ?: error("找不到文件：${candidates.joinToString()} —— 拿不到证据就判失败，不跳过")

    private val rulesFile = locate(
        "src/main/assets/classify_rules.json",
        "app/src/main/assets/classify_rules.json",
        "../app/src/main/assets/classify_rules.json"
    )

    private val colorsFile = locate(
        "src/main/res/values/colors.xml",
        "app/src/main/res/values/colors.xml",
        "../app/src/main/res/values/colors.xml"
    )

    private val HEX = Regex("#[0-9A-Fa-f]{6}")

    @Test
    fun `每个分类和兜底分类都写了识别色`() {
        val root = JsonParser.parseString(rulesFile.readText()).asJsonObject
        val cats = root.getAsJsonArray("categories")
        assertTrue("分类集不该为空", cats.size() > 0)
        for (i in 0 until cats.size()) {
            val o = cats[i].asJsonObject
            val id = o.get("id").asString
            val color = o.get("color")?.asString.orEmpty()
            assertTrue("分类 $id 缺少 color", HEX.matches(color))
        }
        val fb = root.getAsJsonObject("fallback")
        assertTrue("fallback 缺少 color", HEX.matches(fb.get("color")?.asString.orEmpty()))
    }

    @Test
    fun `识别色不与任何基础色同值`() {
        val base = baseColors()
        assertTrue("colors.xml 里没解析到颜色，比对无从谈起", base.isNotEmpty())

        val root = JsonParser.parseString(rulesFile.readText()).asJsonObject
        val offenders = ArrayList<String>()
        fun check(id: String, color: String) {
            val hit = base.entries.firstOrNull { it.value.equals(color, ignoreCase = true) }
            if (hit != null) offenders += "$id 的 $color 与基础色 ${hit.key} 同值"
        }
        val cats = root.getAsJsonArray("categories")
        for (i in 0 until cats.size()) {
            val o = cats[i].asJsonObject
            check(o.get("id").asString, o.get("color").asString)
        }
        val fb = root.getAsJsonObject("fallback")
        check(fb.get("id").asString, fb.get("color").asString)

        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `识别色之间互不重复`() {
        val root = JsonParser.parseString(rulesFile.readText()).asJsonObject
        val colors = ArrayList<String>()
        val cats = root.getAsJsonArray("categories")
        for (i in 0 until cats.size()) colors += cats[i].asJsonObject.get("color").asString.uppercase()
        colors += root.getAsJsonObject("fallback").get("color").asString.uppercase()
        assertEquals("有两个分类用了同一个识别色", colors.size, colors.toSet().size)
    }

    /**
     * 「未分类」不是分类，是一种状态，所以它的色不在规则文件里，
     * 定在 `CategoryBreakdown.UNCLASSIFIED_COLOR`，而 res 里有 `warn`。
     * 两处定义的是同一个语义色——那就必须相等，否则同屏会出现两种警告色。
     */
    @Test
    fun `未分类色与 res 里的 warn 是同一个色`() {
        val warn = baseColors()["warn"]
        assertEquals(
            "CategoryBreakdown.UNCLASSIFIED_COLOR 与 colors.xml 的 warn 不一致",
            warn,
            com.ledger.offline.data.CategoryBreakdown.UNCLASSIFIED_COLOR.uppercase()
        )
    }

    private fun baseColors(): Map<String, String> {
        val text = colorsFile.readText()
        val out = LinkedHashMap<String, String>()
        Regex("<color\\s+name=\"([^\"]+)\">([^<]+)</color>").findAll(text).forEach {
            out[it.groupValues[1]] = it.groupValues[2].trim().uppercase()
        }
        return out
    }
}
