package com.ledger.offline.capture

import android.content.Context
import android.net.Uri
import com.ledger.offline.core.ServiceLocator
import com.ledger.offline.data.TransactionDao
import com.ledger.offline.data.model.Direction
import com.ledger.offline.data.model.Transaction
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 账单 CSV 的导入与导出。
 *
 * 这条路是整条数据链里**唯一 100% 完整且 100% 合规**的来源：
 * 微信「我 - 服务 - 钱包 - 账单 - 常见问题 - 下载账单」和支付宝
 * 「我的 - 账单 - 开具交易流水证明」都能导出官方 CSV。
 *
 * 代价是要手动操作，所以定位是：
 *  - 首次使用时把历史账目一次性回填
 *  - 每月对一次账，补上通知没抓到的部分
 *
 * 两个容易踩的坑，这里都处理了：
 *  1. **编码**：微信/支付宝导出的 CSV 是 GBK，直接按 UTF-8 读会全是乱码。
 *     这里先按 UTF-8 严格解码，失败再退 GBK。
 *  2. **表头位置**：文件前面有十几行说明文字，表头在第 16 行左右，
 *     不能假设第一行就是表头，得靠「交易时间 / 交易创建时间」去找。
 */
object CsvIo {

    private const val SOURCE_ID = "csv"

    data class ImportResult(val added: Int, val skipped: Int, val error: String? = null)

    // ------------------------------------------------------------ 导入

    fun import(context: Context, uri: Uri): ImportResult {
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { decode(it.readBytes()) }
        }.getOrNull() ?: return ImportResult(0, 0, "无法读取文件")

        if (text.isBlank()) return ImportResult(0, 0, "文件为空")

        val lines = text.split('\n').map { it.trimEnd('\r') }
        val headerIndex = lines.indexOfFirst { isHeaderRow(it) }
        if (headerIndex < 0) return ImportResult(0, 0, "未识别到账单表头，请确认导出来源")

        val header = splitCsvLine(lines[headerIndex])
        val col = Columns.resolve(header)
        if (col.time < 0 || col.amount < 0) return ImportResult(0, 0, "账单缺少必要列")

        var added = 0
        var skipped = 0

        for (i in headerIndex + 1 until lines.size) {
            val line = lines[i]
            if (line.isBlank() || line.startsWith("---")) continue

            val cells = splitCsvLine(line)
            if (cells.size <= col.amount) continue
            if (col.status >= 0 && cells.size > col.status &&
                DROP_STATUS.any { cells[col.status].contains(it) }
            ) {
                skipped++
                continue
            }

            val occurredAt = parseTime(cells.getOrNull(col.time).orEmpty()) ?: continue
            val amount = parseAmount(cells.getOrNull(col.amount).orEmpty()) ?: continue
            if (amount <= 0.0) continue

            val direction = parseDirection(
                cells.getOrNull(col.direction).orEmpty(),
                cells.getOrNull(col.amount).orEmpty()
            ) ?: continue

            val merchant = cells.getOrNull(col.merchant).orEmpty().trim()
            val goods = cells.getOrNull(col.goods).orEmpty().trim()
            val txnNo = cells.getOrNull(col.txnNo).orEmpty().trim()

            if (txnNo.isNotEmpty() && ServiceLocator.dao.existsByTxnNo(txnNo)) {
                skipped++
                continue
            }

            val ok = ServiceLocator.persistRecord(
                amount = amount,
                direction = direction,
                merchantRaw = merchant,
                occurredAt = occurredAt,
                sourceId = SOURCE_ID,
                txnNo = txnNo,
                rawText = goods
            )
            if (ok) added++ else skipped++
        }

        return ImportResult(added, skipped)
    }

    // ------------------------------------------------------------ 导出

    fun export(context: Context, uri: Uri): Int {
        val all = ServiceLocator.dao.queryRange(0L, Long.MAX_VALUE, limit = 100_000)
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        val sb = StringBuilder()
        sb.append('\uFEFF')                                   // BOM，避免 Excel 打开乱码
        sb.append("交易时间,交易对方,商品,收/支,金额(元),分类,交易单号,来源\n")
        for (t in all) {
            sb.append(formatter.format(Date(t.occurredAt))).append(',')
            sb.append(escape(t.merchant)).append(',')
            sb.append(escape(t.note)).append(',')
            sb.append(if (t.direction == Direction.EXPENSE) "支出" else "收入").append(',')
            sb.append(TransactionDao.formatAmount(t.amount)).append(',')
            sb.append(escape(t.categoryName)).append(',')
            sb.append(escape(t.txnNo)).append(',')
            sb.append(escape(t.sourceId)).append('\n')
        }
        return runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
            all.size
        }.getOrDefault(0)
    }

    fun exportToFile(context: Context, file: java.io.File): Int =
        export(context, Uri.fromFile(file))

    fun allForBackup(): List<Transaction> = ServiceLocator.dao.queryRange(0L, Long.MAX_VALUE, 100_000)

    // ------------------------------------------------------------ 工具

    private fun isHeaderRow(line: String): Boolean {
        val cells = splitCsvLine(line)
        return cells.any { it.contains("交易时间") || it.contains("交易创建时间") }
    }

    /** 微信/支付宝导出都是 GBK；先试 UTF-8，失败再退 GBK。 */
    internal fun decode(bytes: ByteArray): String {
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
        if (utf8 != null && !utf8.contains('\uFFFD')) return utf8

        return runCatching { String(bytes, Charset.forName("GBK")) }
            .getOrElse { String(bytes, Charsets.ISO_8859_1) }
    }

    /** RFC4180 风格的 CSV 切分，支持双引号包裹与转义 */
    internal fun splitCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"'); i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ch == ',' && !inQuotes -> {
                    out += sb.toString(); sb.setLength(0)
                }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    internal fun parseAmount(raw: String): Double? {
        val cleaned = raw.replace("¥", "").replace("￥", "")
            .replace(",", "").replace("元", "").replace("\"", "").trim()
        return cleaned.toDoubleOrNull()
    }

    internal fun parseTime(raw: String): Long? {
        val s = raw.trim().trim('"')
        if (s.isEmpty()) return null
        for (pattern in TIME_PATTERNS) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.CHINA).parse(s)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    internal fun parseDirection(raw: String, amountRaw: String): Direction? {
        val v = raw.trim()
        if (v.contains("支出") || v.contains("付款") || v.contains("借")) return Direction.EXPENSE
        if (v.contains("收入") || v.contains("收款") || v.contains("贷")) return Direction.INCOME
        // 表格没给收支列时，看金额符号
        return when {
            amountRaw.contains('-') -> Direction.EXPENSE
            amountRaw.contains('+') -> Direction.INCOME
            else -> null
        }
    }

    private fun escape(v: String): String =
        if (v.contains(',') || v.contains('"')) "\"" + v.replace("\"", "\"\"") + "\"" else v

    private val TIME_PATTERNS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm"
    )

    /** 退款 / 关闭 / 失败的单子不入账，否则会虚增支出 */
    private val DROP_STATUS = listOf("已退款", "已全额退款", "全额退款", "交易关闭", "已关闭", "失败", "对方已退还")

    /** 按表头名自适应定位列，同时兼容微信和支付宝两种导出格式 */
    private class Columns(
        val time: Int, val merchant: Int, val goods: Int,
        val amount: Int, val direction: Int, val status: Int, val txnNo: Int
    ) {
        companion object {
            fun resolve(header: List<String>): Columns {
                fun find(vararg names: String): Int = header.indexOfFirst { cell ->
                    val c = cell.trim().trim('"').replace("（", "(").replace("）", ")")
                    names.any { c.contains(it) }
                }
                return Columns(
                    time = find("交易时间", "交易创建时间", "付款时间"),
                    merchant = find("交易对方", "对方"),
                    goods = find("商品", "备注"),
                    amount = find("金额"),
                    direction = find("收/支", "收支"),
                    status = find("当前状态", "交易状态"),
                    txnNo = find("交易单号", "交易号")
                )
            }
        }
    }
}
