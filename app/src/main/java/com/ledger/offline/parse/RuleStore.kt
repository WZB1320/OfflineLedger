package com.ledger.offline.parse

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class CategoryRule(val id: String, val name: String, val keywords: List<String>)

data class ClassifyRules(
    val version: Int,
    val categories: List<CategoryRule>,
    val fallback: CategoryRule
) {
    fun byId(id: String): CategoryRule? =
        categories.firstOrNull { it.id == id } ?: if (fallback.id == id) fallback else null

    fun all(): List<CategoryRule> = categories + fallback
}

/**
 * 规则仓库。
 *
 * 加载优先级：filesDir 下的同名文件 > assets 内置文件。
 * 这样用户可以在不重新装包的前提下热更新解析规则——
 * 对「绝对离线」的 App 来说，这是唯一可行的热修方案（不能走网络下发）。
 */
object RuleStore {

    private const val PARSER_FILE = "parser_rules.json"
    private const val CLASSIFY_FILE = "classify_rules.json"

    @Volatile private var parserCache: ParserRules? = null
    @Volatile private var classifyCache: ClassifyRules? = null

    fun parserRules(context: Context): ParserRules =
        parserCache ?: synchronized(this) {
            parserCache ?: loadParser(context).also { parserCache = it }
        }

    fun classifyRules(context: Context): ClassifyRules =
        classifyCache ?: synchronized(this) {
            classifyCache ?: loadClassify(context).also { classifyCache = it }
        }

    /** 从外部文件导入新的解析规则包（离线热更新入口） */
    fun installParserRules(context: Context, source: File): Boolean {
        return runCatching {
            val text = source.readText(Charsets.UTF_8)
            parseParserRules(JSONObject(text))            // 先校验能否解析
            File(context.filesDir, PARSER_FILE).writeText(text, Charsets.UTF_8)
            reload()
            true
        }.getOrDefault(false)
    }

    fun reload() {
        parserCache = null
        classifyCache = null
    }

    // ------------------------------------------------------------ 内部

    private fun readRuleText(context: Context, name: String): String {
        val override = File(context.filesDir, name)
        if (override.exists() && override.length() > 0) {
            return override.readText(Charsets.UTF_8)
        }
        return context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun loadParser(context: Context): ParserRules =
        parseParserRules(JSONObject(readRuleText(context, PARSER_FILE)))

    private fun parseParserRules(root: JSONObject): ParserRules {
        val array = root.optJSONArray("sources") ?: JSONArray()
        val sources = ArrayList<SourceRule>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val direction = o.optJSONObject("direction") ?: JSONObject()
            sources += SourceRule(
                id = o.getString("id"),
                enabled = o.optBoolean("enabled", true),
                packageNames = o.optJSONArray("packageNames").toStringList(),
                titlePatterns = o.optJSONArray("titlePatterns").toStringList(),
                ignoreIfContains = o.optJSONArray("ignoreIfContains").toStringList(),
                amountRegex = Regex(o.getString("amountPattern")),
                expenseKeywords = direction.optJSONArray("expense").toStringList(),
                incomeKeywords = direction.optJSONArray("income").toStringList(),
                merchantRegexes = o.optJSONArray("merchantPatterns").toStringList().map { Regex(it) },
                minAmount = o.optDouble("minAmount", 0.01)
            )
        }
        return ParserRules(
            version = root.optInt("version", 1),
            sources = sources,
            importProfiles = parseImportProfiles(root)
        )
    }

    private fun parseImportProfiles(root: JSONObject): List<ImportProfile> {
        val array = root.optJSONArray("importProfiles") ?: return emptyList()
        val out = ArrayList<ImportProfile>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val colsObj = o.optJSONObject("columns") ?: JSONObject()
            val columns = HashMap<String, List<String>>()
            val keys = colsObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                columns[k] = colsObj.optJSONArray(k).toStringList()
            }
            val dirObj = o.optJSONObject("directionTokens") ?: JSONObject()
            val seedObj = o.optJSONObject("categorySeed")
            val seedMap = HashMap<String, String>()
            seedObj?.optJSONObject("map")?.let { m ->
                val mk = m.keys()
                while (mk.hasNext()) {
                    val k = mk.next()
                    seedMap[k] = m.optString(k)
                }
            }
            out += ImportProfile(
                id = o.getString("id"),
                displayName = o.optString("displayName", o.getString("id")),
                headerSignatures = o.optJSONArray("headerSignatures").toStringList(),
                minSignatureHits = o.optInt("minSignatureHits", 3),
                columns = columns,
                incomeTokens = dirObj.optJSONArray("income").toStringList(),
                expenseTokens = dirObj.optJSONArray("expense").toStringList(),
                neutralTokens = dirObj.optJSONArray("neutral").toStringList(),
                dropStatusTokens = o.optJSONArray("dropStatusTokens").toStringList(),
                seedColumn = seedObj?.optString("column").orEmpty(),
                seedMap = seedMap
            )
        }
        return out
    }

    private fun loadClassify(context: Context): ClassifyRules {
        val root = JSONObject(readRuleText(context, CLASSIFY_FILE))
        val array = root.optJSONArray("categories") ?: JSONArray()
        val categories = ArrayList<CategoryRule>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            categories += CategoryRule(
                id = o.getString("id"),
                name = o.getString("name"),
                keywords = o.optJSONArray("keywords").toStringList()
            )
        }
        val fb = root.optJSONObject("fallback")
        val fallback = if (fb != null) {
            CategoryRule(fb.getString("id"), fb.getString("name"), emptyList())
        } else {
            CategoryRule("other", "其他", emptyList())
        }
        return ClassifyRules(root.optInt("version", 1), categories, fallback)
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val list = ArrayList<String>(length())
        for (i in 0 until length()) list += optString(i)
        return list
    }
}
