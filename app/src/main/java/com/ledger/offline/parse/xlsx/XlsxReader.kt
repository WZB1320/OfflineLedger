package com.ledger.offline.parse.xlsx

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.File
import java.io.InputStream
import java.util.Calendar
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory

/**
 * 最小 xlsx 读取器。
 *
 * xlsx 本质是一个 zip 包，里面是若干 XML：
 *   xl/sharedStrings.xml      字符串池（单元格用 t="s" + 索引引用它）
 *   xl/worksheets/sheet1.xml  表格数据
 *
 * 用系统自带的 java.util.zip + SAX 就能读，**零新增依赖**。
 *
 * 为什么不用 Apache POI：
 * 那是 10 MB 级的 JVM 库，Android 上既超体积（本 App 全部预算才 2.5 MB）
 * 又有兼容问题。这是经过评估的结论，不要重新引入。
 *
 * 实现上刻意保持「纯 JVM」——不 import 任何 android.*，
 * 这样它能在普通单元测试里直接跑，不必等真机。
 */
object XlsxReader {

    private val SHEET_ENTRY = Regex("^xl/worksheets/sheet(\\d+)\\.xml$")

    /**
     * 读取第一张工作表，返回按行、按列对齐的字符串矩阵。
     * 空单元格补 ""，因此可以直接按列索引取值。
     */
    fun readRows(file: File): List<List<String>> {
        ZipFile(file).use { zip ->
            val shared = zip.getEntry("xl/sharedStrings.xml")
                ?.let { readSharedStrings(zip.getInputStream(it)) }
                ?: emptyList()
            val sheet = firstSheetEntry(zip) ?: return emptyList()
            return readSheet(zip.getInputStream(sheet), shared)
        }
    }

    // ------------------------------------------------------------ 内部实现

    /** 取编号最小的工作表；微信/支付宝导出的账单都只有一张表 */
    private fun firstSheetEntry(zip: ZipFile): java.util.zip.ZipEntry? =
        zip.entries().asSequence()
            .mapNotNull { e ->
                SHEET_ENTRY.matchEntire(e.name)?.let { it.groupValues[1].toInt() to e }
            }
            .minByOrNull { it.first }
            ?.second

    /**
     * 解析字符串池。注意富文本：一个 si 里可能有多个 t，
     * 例如 <si><r><t>a</t></r><r><t>b</t></r></si> 应当拼成 "ab"。
     */
    private fun readSharedStrings(input: InputStream): List<String> {
        val out = ArrayList<String>()
        parse(input, object : DefaultHandler() {
            private var inSi = false
            private var sb: StringBuilder? = null
            private val parts = StringBuilder()

            override fun startElement(uri: String?, local: String?, q: String, a: Attributes?) {
                when (q) {
                    "si" -> { inSi = true; parts.setLength(0) }
                    "t" -> if (inSi) sb = StringBuilder()
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                sb?.append(ch, start, length)
            }

            override fun endElement(uri: String?, local: String?, q: String) {
                when (q) {
                    "t" -> { sb?.let { parts.append(it) }; sb = null }
                    "si" -> { out += parts.toString(); inSi = false }
                }
            }
        })
        return out
    }

    private fun readSheet(input: InputStream, shared: List<String>): List<List<String>> {
        val rows = ArrayList<Map<Int, String>>()
        parse(input, object : DefaultHandler() {
            private var cells: HashMap<Int, String>? = null
            private var ref = ""
            private var type = ""
            private var sb: StringBuilder? = null
            private var lastValue: String? = null
            private var inInlineStr = false
            private val inline = StringBuilder()

            override fun startElement(uri: String?, local: String?, q: String, a: Attributes?) {
                when (q) {
                    "row" -> cells = HashMap()
                    "c" -> {
                        ref = a?.getValue("r").orEmpty()
                        type = a?.getValue("t").orEmpty()
                        lastValue = null
                        inline.setLength(0)
                    }
                    "v" -> sb = StringBuilder()
                    "is" -> inInlineStr = true
                    "t" -> if (inInlineStr) sb = StringBuilder()
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                sb?.append(ch, start, length)
            }

            override fun endElement(uri: String?, local: String?, q: String) {
                when (q) {
                    "v" -> { lastValue = sb?.toString(); sb = null }
                    "t" -> { if (inInlineStr) sb?.let { inline.append(it) }; sb = null }
                    "is" -> inInlineStr = false
                    "c" -> {
                        val text = when (type) {
                            "s" -> lastValue?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                            "inlineStr" -> inline.toString()
                            else -> lastValue ?: ""
                        }
                        val idx = columnIndex(ref)
                        if (idx >= 0) cells?.put(idx, text.trim())
                        lastValue = null
                    }
                    "row" -> { rows += cells ?: HashMap(); cells = null }
                }
            }
        })

        if (rows.isEmpty()) return emptyList()
        val width = (rows.maxOfOrNull { r -> (r.keys.maxOrNull() ?: -1) } ?: -1) + 1
        return rows.map { r -> List(width) { i -> r[i] ?: "" } }
    }

    private fun parse(input: InputStream, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        factory.newSAXParser().parse(input, handler)
    }

    /** "K19" → 10；解析失败返回 -1 */
    internal fun columnIndex(ref: String): Int {
        var idx = 0
        var sawLetter = false
        for (ch in ref) {
            when {
                ch in 'A'..'Z' -> { idx = idx * 26 + (ch - 'A' + 1); sawLetter = true }
                ch in 'a'..'z' -> { idx = idx * 26 + (ch - 'a' + 1); sawLetter = true }
                else -> break
            }
        }
        return if (sawLetter) idx - 1 else -1
    }

    // ------------------------------------------------------------ Excel 日期序列号

    /**
     * Excel 序列号 → epoch millis。
     *
     * 两个必须小心的点：
     *  1) **墙上时钟语义**：Excel 存的是「本机墙上时间」，不含时区信息。
     *     必须用本地时区的 Calendar 还原，而不是按 UTC 直接换算——
     *     否则 UTC+8 下会整体偏移 8 小时，±3 分钟的去重窗口会彻底失效。
     *  2) **1900 假闰日**：Excel 认为 1900-02-29 存在（历史 bug），
     *     故序列号 > 59 时基准取 1899-12-30，<= 59 时取 1899-12-31。
     *
     * 合理区间限定 20000(1954) ~ 80000(2119)，超出判为异常返回 null——
     * 不猜、不兜底。
     */
    fun serialToEpochMillis(serial: Double): Long? {
        if (serial.isNaN() || serial < 20_000.0 || serial > 80_000.0) return null
        val wholeDays = Math.floor(serial)
        val fraction = serial - wholeDays
        val days = wholeDays.toInt()

        val cal = Calendar.getInstance()
        cal.clear()
        if (days > 59) {
            cal.set(1899, Calendar.DECEMBER, 30, 0, 0, 0)
        } else {
            cal.set(1899, Calendar.DECEMBER, 31, 0, 0, 0)
        }
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, days)

        val millisOfDay = Math.round(fraction * 86_400_000.0)
        return cal.timeInMillis + millisOfDay
    }
}
