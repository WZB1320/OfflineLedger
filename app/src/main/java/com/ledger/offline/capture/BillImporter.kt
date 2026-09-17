package com.ledger.offline.capture

import android.content.Context
import android.net.Uri
import com.ledger.offline.core.ServiceLocator
import com.ledger.offline.data.model.Direction
import com.ledger.offline.parse.ImportProfile
import com.ledger.offline.parse.RuleStore
import com.ledger.offline.parse.xlsx.XlsxReader
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 账单文件导入器（xlsx 与 csv 共用一套语义）。
 *
 * 定位：**整条数据链的地基**。通知监听只能覆盖 70~80%，
 * 而官方账单是 100% 完整、且自带收款方与收支方向的数据源。
 *
 * 与通知解析的关键区别——账单里的信息是「干净的」：
 * 不需要正则抠金额、不需要猜收支方向、商户名直接就有。
 * 但账单文件有三个必须处理的坑：
 *   1) **编码**：csv 是 GBK；xlsx 内部 XML 是 UTF-8
 *   2) **表头不在第一行**：前面有十几行说明文字，必须按内容找表头
 *   3) **金额/时间是数字型单元格**：时间是 Excel 序列号（墙上时钟语义，
 *      按 UTC 换算会让全部时间偏 8 小时，直接摧毁 ±3 分钟去重窗口）
 *
 * 设计原则「宁缺毋滥」：金额解析不出、时间解析不出、收支方向判不出，
 * 一律丢弃并**分类计数**（不是静默忽略）——错误的账目比缺失的账目更糟。
 */
object BillImporter {

    private const val SOURCE_XLSX = "bill_xlsx"
    private const val SOURCE_CSV = "bill_csv"

    data class ImportResult(
        val platform: String = "",
        val headerRow: Int = -1,
        val totalRows: Int = 0,
        val added: Int = 0,
        val duplicated: Int = 0,
        val neutral: Int = 0,
        val droppedRefund: Int = 0,
        val zeroAmount: Int = 0,
        val droppedUnparsed: Int = 0,
        val error: String? = null
    ) {
        fun summary(): String = when {
            error != null -> error
            else -> "识别为${platform}账单；共 ${totalRows} 行，入库 $added 笔" +
                (if (duplicated > 0) "，重复跳过 $duplicated 笔" else "") +
                (if (neutral > 0) "，不计收支 $neutral 笔" else "") +
                (if (droppedRefund > 0) "，退款/关闭 $droppedRefund 笔" else "") +
                // 与「无法解析」分开报：零金额是平台真实的 0 元订单（如全额抵扣），
                // 不是规则不够用；混在一起会把「笔数比账单少」误读成解析失败
                (if (zeroAmount > 0) "，金额 0.00 未入账 $zeroAmount 笔" else "") +
                (if (droppedUnparsed > 0) "，无法解析丢弃 $droppedUnparsed 笔" else "")
        }
    }

    // ------------------------------------------------------------ 入口

    fun import(context: Context, uri: Uri): ImportResult {
        val profiles = RuleStore.parserRules(context).importProfiles
        if (profiles.isEmpty()) return ImportResult(error = "规则文件缺少 importProfiles 配置")

        val displayName = queryDisplayName(context, uri)
        val temp = File.createTempFile("bill_import_", if (displayName.endsWith(".csv", true)) ".csv" else ".bin", context.cacheDir)

        return try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return ImportResult(error = "无法读取文件")
            if (bytes.isEmpty()) return ImportResult(error = "文件为空")
            temp.writeBytes(bytes)

            val rows: List<List<String>> = if (isZip(bytes)) {
                XlsxReader.readRows(temp)
            } else {
                CsvIo.decode(bytes).split('\n').map { line ->
                    CsvIo.splitCsvLine(line.trimEnd('\r').trimEnd())
                }
            }
            if (rows.isEmpty()) return ImportResult(error = "文件内容为空或格式无法识别")

            val parsed = parseRows(rows, profiles)
            parsed.error?.let { return ImportResult(error = it) }

            val sourceId = if (isZip(bytes)) SOURCE_XLSX else SOURCE_CSV
            var added = 0
            var duplicated = 0
            for (rec in parsed.records) {
                if (rec.txnNo.isNotEmpty() && ServiceLocator.dao.existsByTxnNo(rec.txnNo)) {
                    duplicated++
                    continue
                }
                val ok = ServiceLocator.persistRecord(
                    amount = rec.amount,
                    direction = rec.direction,
                    merchantRaw = rec.merchantRaw,
                    occurredAt = rec.occurredAt,
                    sourceId = sourceId,
                    txnNo = rec.txnNo,
                    rawText = rec.text,
                    seedCategoryId = rec.seedCategoryId
                )
                if (ok) added++ else duplicated++
            }

            parsed.copy(added = added, duplicated = duplicated)
        } catch (t: Throwable) {
            ImportResult(error = "导入失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            temp.delete()
        }
    }

    // ------------------------------------------------------------ 纯逻辑（可单测）

    internal data class Record(
        val amount: Double,
        val direction: Direction,
        val merchantRaw: String,
        val occurredAt: Long,
        val txnNo: String,
        val text: String,
        val seedCategoryId: String?
    )

    internal class Parsed(
        val profile: ImportProfile? = null,
        val headerRow: Int = -1,
        val totalRows: Int = 0,
        val records: List<Record> = emptyList(),
        val neutral: Int = 0,
        val droppedRefund: Int = 0,
        val zeroAmount: Int = 0,
        val droppedUnparsed: Int = 0,
        val error: String? = null
    ) {
        fun copy(added: Int, duplicated: Int) = ImportResult(
            platform = profile?.displayName.orEmpty(),
            headerRow = headerRow,
            totalRows = totalRows,
            added = added,
            duplicated = duplicated,
            neutral = neutral,
            droppedRefund = droppedRefund,
            zeroAmount = zeroAmount,
            droppedUnparsed = droppedUnparsed
        )
    }

    internal fun parseRows(rows: List<List<String>>, profiles: List<ImportProfile>): Parsed {
        val headerRow = locateHeaderRow(rows)
        if (headerRow < 0) return Parsed(error = "未识别到账单表头，请确认导出来源")

        val header = rows[headerRow].map { normalizeHeader(it) }
        val profile = matchProfile(header, profiles)
            ?: return Parsed(headerRow = headerRow, error = "未能匹配任何导入档案（表头：${header.take(6).joinToString("/")}）")

        val cols = buildColumns(header, profile)
        val timeCol = cols[FIELD_TIME]
        val amountCol = cols[FIELD_AMOUNT]
        val dirCol = cols[FIELD_DIRECTION]
        if (timeCol == null || amountCol == null || dirCol == null) {
            return Parsed(profile = profile, headerRow = headerRow, error = "${profile.displayName} 缺少必要列（时间/金额/收支）")
        }

        val dataRows = rows.drop(headerRow + 1).filter { r -> r.any { it.isNotBlank() } }
        val records = ArrayList<Record>(dataRows.size)
        var neutral = 0
        var droppedRefund = 0
        var zeroAmount = 0
        var droppedUnparsed = 0

        for (row in dataRows) {
            fun cell(field: String): String = cols[field]?.let { row.getOrNull(it)?.trim().orEmpty() } ?: ""

            if (profile.dropStatusTokens.any { cell(FIELD_STATUS).contains(it) }) {
                droppedRefund++
                continue
            }
            val dir = parseDirection(cell(FIELD_DIRECTION), profile)
            when (dir) {
                Dir.NEUTRAL -> { neutral++; continue }
                Dir.UNKNOWN -> { droppedUnparsed++; continue }
                else -> Unit
            }
            val direction = if (dir == Dir.INCOME) Direction.INCOME else Direction.EXPENSE
            val rawAmount = cell(FIELD_AMOUNT)
            val amount = parseAmount(rawAmount)
            val occurredAt = parseEpochMillis(cell(FIELD_TIME))
            if (amount == null || amount <= 0.0 || occurredAt == null) {
                // 分类计数，不让任何一行静默消失。
                // 零金额单列一支：它是平台真实存在的 0 元订单（如全额抵扣、0 元试用），
                // 金额解析本身没失败——混进「无法解析」会掩盖真正的规则缺陷。
                if (occurredAt != null && cleanNumber(rawAmount) == 0.0) zeroAmount++ else droppedUnparsed++
                continue
            }

            val merchant = cell(FIELD_COUNTERPARTY).ifBlank { cell(FIELD_PRODUCT) }
            val seedRaw = if (profile.seedColumn.isNotEmpty()) cell(FIELD_SEED) else ""
            val seedId = profile.seedMap[seedRaw]?.takeIf { it.isNotBlank() }

            records += Record(
                amount = amount,
                direction = direction,
                merchantRaw = merchant,
                occurredAt = occurredAt,
                txnNo = cell(FIELD_TXN_NO),
                text = listOf(cell(FIELD_HEADING), cell(FIELD_SEED)).firstOrNull { it.isNotBlank() }.orEmpty(),
                seedCategoryId = seedId
            )
        }

        return Parsed(
            profile = profile,
            headerRow = headerRow,
            totalRows = dataRows.size,
            records = records,
            neutral = neutral,
            droppedRefund = droppedRefund,
            zeroAmount = zeroAmount,
            droppedUnparsed = droppedUnparsed
        )
    }

    /** 表头行：含「交易时间」的行；退一步找含「收/支」的行 */
    internal fun locateHeaderRow(rows: List<List<String>>): Int {
        rows.forEachIndexed { i, row ->
            if (row.any { it.contains("交易时间") || it.contains("交易创建时间") }) return i
        }
        rows.forEachIndexed { i, row ->
            if (row.any { it.contains("收/支") }) return i
        }
        return -1
    }

    internal fun matchProfile(header: List<String>, profiles: List<ImportProfile>): ImportProfile? {
        val set = header.toSet()
        var best: ImportProfile? = null
        var bestHits = 0
        for (p in profiles) {
            val hits = p.headerSignatures.count { set.contains(normalizeHeader(it)) }
            if (hits >= p.minSignatureHits && hits > bestHits) {
                best = p
                bestHits = hits
            }
        }
        return best
    }

    internal fun buildColumns(header: List<String>, profile: ImportProfile): Map<String, Int> {
        val out = HashMap<String, Int>()
        for ((field, aliases) in profile.columns) {
            val idx = header.indexOfFirst { h -> aliases.any { normalizeHeader(it) == h } }
            if (idx >= 0) out[field] = idx
        }
        // 官方分类列由 categorySeed.column 指定，不在 columns 里
        if (profile.seedColumn.isNotEmpty()) {
            val idx = header.indexOfFirst { it == normalizeHeader(profile.seedColumn) }
            if (idx >= 0) out[FIELD_SEED] = idx
        }
        return out
    }

    internal enum class Dir { INCOME, EXPENSE, NEUTRAL, UNKNOWN }

    /**
     * 收支方向判定。**顺序至关重要**：必须先判「不计收支」——
     * 因为它字面上包含「支出」，若先判支出就会把转账/提现误记成消费，
     * 直接把账目做错。
     */
    internal fun parseDirection(raw: String, profile: ImportProfile): Dir {
        val v = raw.trim()
        if (v.isEmpty()) return Dir.UNKNOWN
        if (profile.neutralTokens.any { v == it || v.contains(it) }) return Dir.NEUTRAL
        if (profile.incomeTokens.any { v.contains(it) }) return Dir.INCOME
        if (profile.expenseTokens.any { v.contains(it) }) return Dir.EXPENSE
        return Dir.UNKNOWN
    }

    /** 金额：去掉货币符号、千分位、空格、"元"，只认正数 */
    internal fun parseAmount(raw: String): Double? {
        val v = cleanNumber(raw) ?: return null
        return if (v > 0.0) v else null
    }

    /** 只清洗不判正负：用来区分「金额就是 0」和「金额根本解析不出」这两种截然不同的情况 */
    internal fun cleanNumber(raw: String): Double? {
        val cleaned = raw
            .replace("¥", "").replace("￥", "")
            .replace(",", "").replace("，", "")
            .replace("元", "").replace("\"", "")
            .replace(" ", "").trim()
        if (cleaned.isEmpty()) return null
        return cleaned.toDoubleOrNull()
    }

    /** 时间：数字型按 Excel 序列号（墙上时钟），字符串型按多种格式尝试 */
    internal fun parseEpochMillis(raw: String): Long? {
        val s = raw.trim().trim('"')
        if (s.isEmpty()) return null
        s.toDoubleOrNull()?.let { num -> return XlsxReader.serialToEpochMillis(num) }
        for (pattern in TIME_PATTERNS) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.CHINA).parse(s)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    internal fun normalizeHeader(raw: String): String =
        raw.trim().trim('"').replace("（", "(").replace("）", ")").replace(" ", "")

    internal fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()

    /** 字段名常量，与 parser_rules.json 的 columns 键一一对应 */
    private const val FIELD_TIME = "time"
    private const val FIELD_AMOUNT = "amount"
    private const val FIELD_DIRECTION = "direction"
    private const val FIELD_COUNTERPARTY = "counterparty"
    private const val FIELD_PRODUCT = "product"
    private const val FIELD_STATUS = "status"
    private const val FIELD_TXN_NO = "txnNo"
    private const val FIELD_HEADING = "type"
    private const val FIELD_SEED = "seed"

    private val TIME_PATTERNS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm",
        "yyyy-MM-dd",
        "yyyy/MM/dd",
        "yyyyMMddHHmmss"
    )

    // ------------------------------------------------------------ 细节

    private fun queryDisplayName(context: Context, uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else ""
            }.orEmpty()
        }.getOrDefault("")
}
