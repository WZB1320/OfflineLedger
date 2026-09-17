package com.ledger.offline.parse.xlsx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * XlsxReader 的单测。
 *
 * 全部是纯 JVM 测试——XlsxReader 刻意不依赖任何 android.*，
 * 所以这里可以直接构造最小 xlsx 二进制来验证，
 * 不必等真机、不必连 Android Studio。
 */
class XlsxReaderTest {

    // ------------------------------------------------------------ 基本读取

    @Test
    fun `读取共享字符串与稀疏单元格`() {
        val file = buildXlsx(
            sharedStrings = listOf("标题", "交易时间", "金额(元)", "收/支"),
            sheetXml = """
                <row r="1"><c r="A1" t="s"><v>0</v></c></row>
                <row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2" t="s"><v>2</v></c><c r="C2" t="s"><v>3</v></c></row>
                <row r="3"><c r="A3" s="1"><v>46275.5</v></c><c r="C3" s="3"><v>13.9</v></c></row>
            """.trimIndent()
        )

        val rows = XlsxReader.readRows(file)
        file.delete()

        assertEquals(3, rows.size)
        // 整表按最大列宽对齐（这里 3 列），因此只有 1 个单元格的行也会被补齐——
        // 这是刻意的：保证按列索引取值永远不会错位
        assertEquals(listOf("标题", "", ""), rows[0])
        assertEquals(listOf("交易时间", "金额(元)", "收/支"), rows[1])
        // B3 缺失（稀疏行），必须补空占位，否则按列取值会错位
        assertEquals(listOf("46275.5", "", "13.9"), rows[2])
    }

    @Test
    fun `富文本 si 的多个 t 应拼接`() {
        val file = buildXlsx(
            sharedStrings = listOf("微信支付", "账单明细"),
            sheetXml = """<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>""",
            rawSharedStrings = """
                <si><r><t>微信</t></r><r><t>支付</t></r></si>
                <si><t>账单明细</t></si>
            """.trimIndent()
        )

        val rows = XlsxReader.readRows(file)
        file.delete()

        assertEquals(listOf("微信支付", "账单明细"), rows[0])
    }

    @Test
    fun `内联字符串与公式结果`() {
        val file = buildXlsx(
            sharedStrings = emptyList(),
            sheetXml = """
                <row r="1"><c r="A1" t="inlineStr"><is><t>内联商户</t></is></c><c r="B1" t="str"><v>公式结果</v></c></row>
            """.trimIndent()
        )

        val rows = XlsxReader.readRows(file)
        file.delete()

        assertEquals(listOf("内联商户", "公式结果"), rows[0])
    }

    @Test
    fun `列引用转索引`() {
        assertEquals(0, XlsxReader.columnIndex("A1"))
        assertEquals(9, XlsxReader.columnIndex("J19"))
        assertEquals(10, XlsxReader.columnIndex("K3"))
        assertEquals(26, XlsxReader.columnIndex("AA2"))
        assertEquals(-1, XlsxReader.columnIndex("1"))
    }

    // ------------------------------------------------------------ Excel 日期序列号

    /**
     * 关键回归：Excel 序列号是「墙上时钟」语义，不含时区。
     * 如果实现里按 UTC 直接换算，在 UTC+8 下会整体偏 8 小时——
     * 账单时间错 8 小时，±3 分钟的去重窗口就永远匹配不上，
     * 同一笔交易会被记两次。这个断言就是为了钉死这个坑。
     */
    @Test
    fun `序列号映射到同一墙上时钟`() {
        val secondsOfDay = 12 * 3600 + 38 * 60 + 21      // 12:38:21
        val serial = 46275.0 + secondsOfDay / 86_400.0

        val actual = XlsxReader.serialToEpochMillis(serial)
        val expected = wallClock(2026, Calendar.SEPTEMBER, 10, 12, 38, 21)

        assertEquals(expected, actual)
    }

    @Test
    fun `序列号支持整日与午夜`() {
        val actual = XlsxReader.serialToEpochMillis(46275.0)
        assertEquals(wallClock(2026, Calendar.SEPTEMBER, 10, 0, 0, 0), actual)
    }

    @Test
    fun `越界序列号判为异常不猜测`() {
        assertNull(XlsxReader.serialToEpochMillis(0.0))
        assertNull(XlsxReader.serialToEpochMillis(1234.5))
        assertNull(XlsxReader.serialToEpochMillis(999_999.0))
        assertNull(XlsxReader.serialToEpochMillis(Double.NaN))
    }

    // ------------------------------------------------------------ 工具

    private fun wallClock(year: Int, month: Int, day: Int, h: Int, m: Int, s: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, h, m, s)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /** 构造一个最小可读的 xlsx 文件 */
    private fun buildXlsx(
        sharedStrings: List<String>,
        sheetXml: String,
        rawSharedStrings: String? = null
    ): File {
        val file = File.createTempFile("xlsx_test_", ".xlsx")
        val stringsBody = rawSharedStrings ?: sharedStrings.joinToString("") { "<si><t>$it</t></si>" }
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8"?><sst count="${sharedStrings.size}">$stringsBody</sst>"""
                    .toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(
                """<?xml version="1.0" encoding="UTF-8"?><worksheet><sheetData>$sheetXml</sheetData></worksheet>"""
                    .toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
        }
        return file
    }
}
