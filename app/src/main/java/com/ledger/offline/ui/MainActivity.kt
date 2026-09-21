package com.ledger.offline.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Html
import android.util.Log
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ledger.offline.BuildConfig
import com.ledger.offline.R
import com.ledger.offline.capture.BillImporter
import com.ledger.offline.capture.CaptureStatus
import com.ledger.offline.classify.MerchantNormalizer
import com.ledger.offline.core.ServiceLocator
import com.ledger.offline.data.CategoryBreakdown
import com.ledger.offline.data.FlowList
import com.ledger.offline.data.MergeMatcher
import com.ledger.offline.data.MonthWindow
import com.ledger.offline.data.TransactionDao
import com.ledger.offline.data.model.Direction
import com.ledger.offline.data.model.Transaction
import com.ledger.offline.databinding.ActivityMainBinding
import com.ledger.offline.parse.CategoryRule
import com.ledger.offline.parse.RuleStore
import java.util.Calendar
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var flowAdapter: TransactionAdapter
    private lateinit var statAdapter: StatBarAdapter
    private var categoryAdapter: CategoryGridAdapter? = null

    /** 一旦置位就停掉后续逻辑，界面停留在诊断视图上 */
    private var startupFailed = false

    private var page = Page.FLOW
    /** 0 = 当前月，-1 = 上一个月。正数指向未来，没有账可看，所以只允许 ≤ 0 */
    private var monthOffset = 0
    private var filter = FlowList.Filter.ALL

    // —— 记一笔的临时状态 ——
    private var addDirection = Direction.EXPENSE
    private var addAmount = StringBuilder()
    private var addOccurredAt = System.currentTimeMillis()

    private val openBill = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // 账单导入：xlsx / csv 由 BillImporter 按文件头自动分流
        val result = BillImporter.import(this, uri)
        if (result.error != null) {
            showImportDialog(result.error.orEmpty())
        } else {
            // 记下「最近一次导入」：首页状态条靠它提醒用户别让账目长期残缺
            CaptureStatus.markImport(this, result.summary())
            showImportDialog(result.summary())
            // 跳到「真的有账的那个月」：账单默认导出最近一个月，天然跨月。
            // 导完还停在当前月，用户会看到「只进来几条」——数据其实全在库里，
            // 只是窗口没对上（2026-09-21 报的「只导入 5 条」就是这个观感）。
            monthOffset = newestMonthOffset()
            refresh()
        }
    }

    /**
     * POST_NOTIFICATIONS 的运行时授权（Android 13+）。
     * 唯一用途是重启后那条「已恢复运行」的提醒——拒绝它不影响记账本身。
     */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也不影响记账 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            bindViews()
            askNotificationPermissionIfNeeded()
        } catch (t: Throwable) {
            showStartupFailure("onCreate", t)
        }
    }

    override fun onResume() {
        super.onResume()
        if (startupFailed) return
        try {
            refresh()
        } catch (t: Throwable) {
            // 建库、Keystore 解密、规则加载都在这条路上，且都是真机才有的行为
            showStartupFailure("onResume → refresh", t)
        }
    }

    // ------------------------------------------------------------ 绑定

    private fun bindViews() {
        flowAdapter = TransactionAdapter { txn -> showCategorySheet(txn) }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = flowAdapter

        statAdapter = StatBarAdapter()
        binding.statBars.layoutManager = LinearLayoutManager(this)
        binding.statBars.adapter = statAdapter

        binding.btnGrant.setOnClickListener { openListenerSettings() }
        binding.tvAuthHelp.setOnClickListener { showAuthHelp() }
        binding.btnRestartListener.setOnClickListener { openListenerSettings() }
        binding.btnWhitelist.setOnClickListener { showWhitelistGuide() }
        // 状态条本身就是入口：点它能看到「到底哪一环没在跑」
        binding.statusBar.setOnClickListener { showCaptureSettings() }

        binding.tvImport.setOnClickListener { launchImport() }
        binding.tvImport2.setOnClickListener { launchImport() }

        binding.tvMonthPrev.setOnClickListener { shiftMonth(-1) }
        binding.tvMonthNext.setOnClickListener { shiftMonth(1) }
        binding.tvMonthPrev2.setOnClickListener { shiftMonth(-1) }
        binding.tvMonthNext2.setOnClickListener { shiftMonth(1) }

        binding.chipAll.setOnClickListener { setFilter(FlowList.Filter.ALL) }
        binding.chipExpense.setOnClickListener { setFilter(FlowList.Filter.EXPENSE) }
        binding.chipIncome.setOnClickListener { setFilter(FlowList.Filter.INCOME) }
        binding.chipUncat.setOnClickListener { setFilter(FlowList.Filter.UNCLASSIFIED) }

        binding.navFlow.setOnClickListener { switchPage(Page.FLOW) }
        binding.navAdd.setOnClickListener { switchPage(Page.ADD) }
        binding.navStats.setOnClickListener { switchPage(Page.STATS) }

        bindAddPage()
    }

    private fun launchImport() {
        openBill.launch(
            arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",  // .xlsx
                "text/csv",
                "text/*",
                "*/*"
            )
        )
    }

    /** 库里最新一笔所在的月份相对当前月的偏移（≤ 0） */
    private fun newestMonthOffset(): Int {
        val newest = ServiceLocator.dao.queryRange(0L, Long.MAX_VALUE, 1).firstOrNull()
            ?: return 0
        return MonthWindow.offsetOf(System.currentTimeMillis(), newest.occurredAt)
            .coerceIn(MIN_MONTH_OFFSET, 0)
    }

    private fun shiftMonth(delta: Int) {
        val next = monthOffset + delta
        // 未来没有账可看；往前最多翻 24 个月，避免一路点到空月份
        if (next > 0 || next < MIN_MONTH_OFFSET) return
        monthOffset = next
        refresh()
    }

    private fun setFilter(f: FlowList.Filter) {
        filter = f
        refresh()
    }

    private fun switchPage(target: Page) {
        if (target == page && target != Page.ADD) return
        page = target
        renderPage()
        if (target != Page.ADD) refresh()
    }

    private fun renderPage() {
        binding.pageFlow.visibility = if (page == Page.FLOW) View.VISIBLE else View.GONE
        binding.pageAdd.visibility = if (page == Page.ADD) View.VISIBLE else View.GONE
        binding.pageStats.visibility = if (page == Page.STATS) View.VISIBLE else View.GONE

        // 采集告警只在流水页上方出现：它们是「账目可能不完整」的提示，
        // 摆在记一笔 / 统计页上会挤掉内容，也与该页的任务无关
        val status = captureSnapshot()
        val onFlow = page == Page.FLOW
        binding.permissionBanner.visibility =
            if (onFlow && !status.granted) View.VISIBLE else View.GONE
        binding.serviceBanner.visibility =
            if (onFlow && status.listenerStalled) View.VISIBLE else View.GONE

        binding.navFlowInd.setBackgroundColor(indicatorColor(page == Page.FLOW))
        binding.navStatsInd.setBackgroundColor(indicatorColor(page == Page.STATS))
        binding.navFlowLabel.setTextColor(getColor(if (page == Page.FLOW) R.color.brand else R.color.text_secondary))
        binding.navStatsLabel.setTextColor(getColor(if (page == Page.STATS) R.color.brand else R.color.text_secondary))
        binding.navFlowLabel.setTypeface(null, if (page == Page.FLOW) Typeface.BOLD else Typeface.NORMAL)
        binding.navStatsLabel.setTypeface(null, if (page == Page.STATS) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun indicatorColor(on: Boolean): Int =
        if (on) getColor(R.color.brand) else Color.TRANSPARENT

    // ------------------------------------------------------------ 刷新

    private fun refresh() {
        renderPage()
        renderMonthLabels()
        renderStatus()
        renderFlow()
        if (page == Page.STATS) renderStats()
    }

    private fun monthRange(): Pair<Long, Long> =
        ServiceLocator.monthRange(System.currentTimeMillis(), monthOffset)

    private fun renderMonthLabels() {
        val label = MonthWindow.label(System.currentTimeMillis(), monthOffset)
        // 两处月份标签必须写同一个值：它们是同一个状态的两个视图，
        // 各写一次迟早会有一个没跟上
        binding.tvMonth.text = label
        binding.tvMonth2.text = label
        binding.tvMonthNext.alpha = if (monthOffset == 0) 0.3f else 1f
        binding.tvMonthNext2.alpha = if (monthOffset == 0) 0.3f else 1f
    }

    private fun renderStatus() {
        val status = captureSnapshot()

        binding.tvListenStatus.text = when {
            !status.granted -> getString(R.string.status_listen_off)
            status.listenerStalled -> getString(R.string.status_listen_stalled)
            else -> getString(R.string.status_listen_on)
        } + " · " + when (val days = status.daysSinceEvent()) {
            -1L -> getString(R.string.status_event_never)
            else -> getString(R.string.status_event_days, days)
        }
        binding.vStatusDot.setBackgroundColor(
            getColor(
                when {
                    !status.granted -> R.color.dot_off
                    status.listenerStalled -> R.color.expense
                    else -> R.color.income
                }
            )
        )

        val importDays = status.daysSinceImport()
        binding.tvImportStatus.text = when {
            importDays < 0L -> getString(R.string.status_import_never)
            status.importStale() -> getString(R.string.status_import_stale, importDays)
            else -> getString(R.string.status_import_days, importDays)
        }
        binding.tvImportStatus.setTextColor(
            getColor(
                // 超期未导入标红：账目完整性在滑坡，这比任何统计数字都更该被看见
                if (status.importStale()) R.color.warn else R.color.text_secondary
            )
        )
    }

    private fun renderFlow() {
        val rules = RuleStore.classifyRules(this)
        val (from, to) = monthRange()
        val all = ServiceLocator.dao.queryRange(from, to, MONTH_ROW_LIMIT)

        val stats = ServiceLocator.dao.stats(from, to)
        binding.tvSummaryLabel.text = getString(
            if (monthOffset == 0) R.string.stat_month_expense else R.string.stat_expense
        )
        binding.tvExpense.text = "¥" + money(stats.expense)
        binding.tvIncome.text = "¥" + money(stats.income)
        val balance = stats.income - stats.expense
        binding.tvBalance.text = (if (balance >= 0) "+" else "-") + "¥" + money(kotlin.math.abs(balance))

        val uncat = all.count { FlowList.isUnclassified(it, MerchantNormalizer.UNKNOWN_MERCHANT) }
        binding.chipUncat.text = getString(R.string.filter_uncat, uncat)
        renderChips()

        val shown = FlowList.filter(all, filter, MerchantNormalizer.UNKNOWN_MERCHANT)
        flowAdapter.submit(FlowList.group(shown)) { rules.colorHex(it) }

        binding.tvEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        binding.recycler.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderChips() {
        applyChip(binding.chipAll, filter == FlowList.Filter.ALL)
        applyChip(binding.chipExpense, filter == FlowList.Filter.EXPENSE)
        applyChip(binding.chipIncome, filter == FlowList.Filter.INCOME)
        applyChip(binding.chipUncat, filter == FlowList.Filter.UNCLASSIFIED)
    }

    private fun applyChip(chip: TextView, on: Boolean) {
        chip.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
        chip.setTextColor(getColor(if (on) R.color.brand else R.color.text_secondary))
        chip.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun renderStats() {
        val rules = RuleStore.classifyRules(this)
        val (from, to) = monthRange()
        val all = ServiceLocator.dao.queryRange(from, to, MONTH_ROW_LIMIT)

        val stats = ServiceLocator.dao.stats(from, to)
        binding.tvStatExpense.text = "¥" + money(stats.expense)
        binding.tvStatIncome.text = "¥" + money(stats.income)
        val balance = stats.income - stats.expense
        binding.tvStatBalance.text = (if (balance >= 0) "+" else "-") + "¥" + money(kotlin.math.abs(balance))

        val result = CategoryBreakdown.of(
            all, rules.categories, rules.fallback, MerchantNormalizer.UNKNOWN_MERCHANT
        )
        binding.tvStatCount.text = getString(R.string.stat_count_hint, result.count)
        statAdapter.submit(result.slices)

        // 验收卡：条形按「占验收线的比例」画，一眼看出还有多少余量
        binding.tvCheckTitle.text = getString(R.string.check_title) + "　¥" + money(result.unclassifiedAmount) +
            " · " + String.format(Locale.US, "%.1f%%", result.unclassifiedRatio * 100)
        val ok = result.accepted()
        binding.tvCheckVerdict.text = getString(if (ok) R.string.check_ok else R.string.check_fail)
        binding.tvCheckVerdict.setTextColor(getColor(if (ok) R.color.income else R.color.expense))
        val used = (result.unclassifiedRatio / CategoryBreakdown.ACCEPT_RATIO).coerceIn(0.0, 1.0)
        binding.vCheckFill.layoutParams = android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, used.toFloat()
        )
        binding.vCheckFill.setBackgroundColor(getColor(if (ok) R.color.income else R.color.expense))

        binding.tvStatEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------ 记一笔

    private fun bindAddPage() {
        val rules = RuleStore.classifyRules(this).all()
        categoryAdapter = CategoryGridAdapter(rules) { /* 选中即生效，保存时读取 */ }
        binding.catGrid.layoutManager = GridLayoutManager(this, CATEGORY_SPAN)
        binding.catGrid.adapter = categoryAdapter
        binding.catGrid.isNestedScrollingEnabled = false

        binding.tvAddCancel.setOnClickListener { switchPage(Page.FLOW) }
        binding.tvSegExpense.setOnClickListener { setAddDirection(Direction.EXPENSE) }
        binding.tvSegIncome.setOnClickListener { setAddDirection(Direction.INCOME) }
        setAddDirection(Direction.EXPENSE)

        bindKey(binding.key0, "0")
        bindKey(binding.key1, "1")
        bindKey(binding.key2, "2")
        bindKey(binding.key3, "3")
        bindKey(binding.key4, "4")
        bindKey(binding.key5, "5")
        bindKey(binding.key6, "6")
        bindKey(binding.key7, "7")
        bindKey(binding.key8, "8")
        bindKey(binding.key9, "9")
        bindKey(binding.keyDot, ".")
        binding.keyDel.setOnClickListener {
            if (addAmount.isNotEmpty()) addAmount.deleteCharAt(addAmount.length - 1)
            renderAmount()
        }
        binding.keySave.setOnClickListener { saveManual() }
        binding.tvAddTime.setOnClickListener { pickTime() }
        renderAmount()
        renderAddTime()
    }

    /** key0 / key1 / key2 走同一个实现，避免三个近乎相同的 lambda */
    private fun bindKey(view: TextView, digit: String) {
        view.setOnClickListener { appendAmount(digit) }
    }

    private fun setAddDirection(direction: Direction) {
        addDirection = direction
        binding.tvSegExpense.setBackgroundResource(
            if (direction == Direction.EXPENSE) R.drawable.bg_seg_on_expense else 0
        )
        binding.tvSegIncome.setBackgroundResource(
            if (direction == Direction.INCOME) R.drawable.bg_seg_on_income else 0
        )
        binding.tvSegExpense.setTextColor(
            getColor(if (direction == Direction.EXPENSE) R.color.surface else R.color.text_secondary)
        )
        binding.tvSegIncome.setTextColor(
            getColor(if (direction == Direction.INCOME) R.color.surface else R.color.text_secondary)
        )
    }

    private fun appendAmount(token: String) {
        if (token == "." && addAmount.contains(".")) return
        if (token != "." && addAmount.contains(".")) {
            val decimals = addAmount.toString().substringAfter('.')
            if (decimals.length >= 2) return
        }
        // 首位不允许就是小数点：显示成「¥.5」没有意义
        if (token == "." && addAmount.isEmpty()) addAmount.append("0")
        if (addAmount.length >= 12) return
        addAmount.append(token)
        renderAmount()
    }

    private fun renderAmount() {
        binding.tvAmountInput.text = "¥" + (addAmount.toString().ifEmpty { "0" })
    }

    private fun renderAddTime() {
        val cal = Calendar.getInstance().apply { timeInMillis = addOccurredAt }
        binding.tvAddTime.text = String.format(
            Locale.CHINA, "%d月%d日 %02d:%02d",
            cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
        )
    }

    private fun pickTime() {
        val cal = Calendar.getInstance().apply { timeInMillis = addOccurredAt }
        DatePickerDialog(
            this,
            { _, y, m, d ->
                TimePickerDialog(
                    this,
                    { _, h, min ->
                        addOccurredAt = Calendar.getInstance().apply {
                            set(y, m, d, h, min, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        renderAddTime()
                    },
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true
                ).show()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    /**
     * 手动记账落库。
     *
     * 写入必须走 `mergeRecord()`，不能绕过融合直接 insert：否则「白天手记一笔、
     * 月底导账单又来一笔」会在账本里留下两条。融合结论决定后续动作——
     * 只有真的新增（ADDED）才按表单里的分类去修正；合并到既有记录时
     * 绝不能拿表单覆盖那条既有记录（用户可能早就手动改过它）。
     */
    private fun saveManual() {
        val amount = addAmount.toString().toDoubleOrNull()
        if (amount == null || amount <= 0.0) {
            toast(getString(R.string.add_need_amount))
            return
        }
        val rule = categoryAdapter?.selected() ?: return
        val merchant = binding.etMerchant.text.toString().trim()

        val result = ServiceLocator.mergeRecord(
            amount = amount,
            direction = addDirection,
            merchantRaw = merchant,
            occurredAt = addOccurredAt,
            sourceId = TransactionAdapter.MANUAL_PREFIX + "entry",
            txnNo = "",
            rawText = "",
            note = merchant
        )
        when (result.outcome) {
            TransactionDao.MergeOutcome.ADDED -> {
                val id = result.insertedId
                if (id != null) {
                    ServiceLocator.correctCategory(
                        id,
                        merchant.ifBlank { MerchantNormalizer.UNKNOWN_MERCHANT },
                        rule.id,
                        rule.name
                    )
                }
                toast(getString(R.string.add_saved))
            }
            else -> toast(getString(R.string.add_dup_hint))
        }
        resetAddForm()
        switchPage(Page.FLOW)
    }

    private fun resetAddForm() {
        addAmount.setLength(0)
        addOccurredAt = System.currentTimeMillis()
        binding.etMerchant.setText("")
        renderAmount()
        renderAddTime()
        setAddDirection(Direction.EXPENSE)
    }

    // ------------------------------------------------------------ 分类修正

    /** 长按改分类 → 写入修正记忆，下次同商户自动命中 */
    private fun showCategorySheet(txn: Transaction) {
        val rules = RuleStore.classifyRules(this).all()
        var picked: CategoryRule = rules.firstOrNull { it.id == txn.categoryId } ?: rules.first()
        val adapter = CategoryGridAdapter(rules) { picked = it }
        adapter.select(picked.id)

        val grid = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            this.adapter = adapter
            isNestedScrollingEnabled = false
        }

        val container = verticalContainer().apply {
            addView(txnCaption(txn))
            if (FlowList.isUnclassified(txn, MerchantNormalizer.UNKNOWN_MERCHANT)) {
                addView(noteView(getString(R.string.fix_unknown_note)))
            }
            addView(grid)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.fix_category_title)
            .setView(container)
            .setPositiveButton(R.string.fix_remember) { _, _ ->
                applyCorrection(txn, picked, remember = true)
            }
            .setNegativeButton(R.string.fix_once) { _, _ ->
                applyCorrection(txn, picked, remember = false)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyCorrection(txn: Transaction, rule: CategoryRule, remember: Boolean) {
        ServiceLocator.correctCategory(
            txn.id, txn.merchant, rule.id, rule.name, remember = remember
        )
        if (remember && txn.merchant != MerchantNormalizer.UNKNOWN_MERCHANT) {
            toast(getString(R.string.fixed_toast, txn.merchant, rule.name))
        } else {
            toast(rule.name)
        }
        refresh()
    }

    // ------------------------------------------------------------ 导入结果

    /**
     * 导入结果用可滚动 Dialog，不用 Toast。
     *
     * 导入笔数、丢弃了多少、为什么丢——这些都是「账目完整性」的证据，
     * 一闪而过的 Toast 既读不完也没法复核（方案 §9：丢弃必须对用户可见）。
     */
    private fun showImportDialog(detail: String) {
        val body = TextView(this).apply {
            text = detail
            textSize = 13f
            setTextIsSelectable(true)
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val scroll = ScrollView(this).apply { addView(body) }
        AlertDialog.Builder(this)
            .setTitle(R.string.import_result_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------ 采集状态与引导

    private fun captureSnapshot(): CaptureStatus.Snapshot =
        CaptureStatus.snapshot(this, isListenerEnabled())

    private fun openListenerSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }.onFailure { toast("请在系统设置 → 通知 → 通知使用权 中开启") }
    }

    /**
     * 「列表里没有微信 / 支付宝？」
     *
     * 系统的通知使用权页面列出的是**申请读取通知的应用**，里面只会有「离线记账」。
     * 挑微信 / 支付宝是代码里的包名白名单（parser_rules.json 的 packageNames），
     * 不是用户在系统里勾的。用户第一次进去必然找不到，所以把解释做成入口。
     */
    private fun showAuthHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_help_title)
            .setMessage(Html.fromHtml(permissionHelpHtml(), Html.FROM_HTML_MODE_LEGACY))
            .setPositiveButton(R.string.permission_grant) { _, _ -> openListenerSettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun permissionHelpHtml(): String =
        getString(R.string.permission_help_body).replace("\n", "<br>")

    private fun showWhitelistGuide() {
        AlertDialog.Builder(this)
            .setTitle(R.string.whitelist_dialog_title)
            .setMessage(R.string.whitelist_dialog_body)
            .setPositiveButton("去设置") { _, _ ->
                if (!RomSettings.openBackgroundWhitelist(this)) {
                    toast("已打开应用详情页，请在「电池 / 权限」里设置")
                }
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    /** 点状态条弹出：把两个通道的真实状态摆清楚，再给对应的跳转入口 */
    private fun showCaptureSettings() {
        val status = captureSnapshot()
        val message = buildString {
            append(if (status.granted) "通知使用权：已开启\n" else "通知使用权：未开启\n")
            append(
                if (status.everConnected) "监听服务：已连上系统通知总线\n"
                else "监听服务：从未连上（授权后需要系统重新绑定一次）\n"
            )
            when (val d = status.daysSinceEvent()) {
                -1L -> append("最近监听到支付通知：无记录\n")
                else -> append("最近监听到支付通知：$d 天前\n")
            }
            when (val d = status.daysSinceImport()) {
                -1L -> append("最近导入账单：从未导入")
                else -> append("最近导入账单：$d 天前")
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.capture_settings)
            .setMessage(message)
            .setPositiveButton(R.string.service_restart) { _, _ -> openListenerSettings() }
            .setNeutralButton(R.string.service_whitelist) { _, _ -> showWhitelistGuide() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun isListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    // ------------------------------------------------------------ 小工具

    private fun verticalContainer() = android.widget.LinearLayout(this).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        val pad = (16 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
    }

    private fun txnCaption(txn: Transaction) = TextView(this).apply {
        text = buildString {
            append(if (txn.sourceId.startsWith(MergeMatcher.BILL_SOURCE_PREFIX)) "账单" else "采集")
            append(" · ").append(txn.categoryName)
            append(" · ¥").append(money(txn.amount))
        }
        textSize = 12f
        setTextColor(getColor(R.color.text_secondary))
        setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
    }

    private fun noteView(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(getColor(R.color.text_secondary))
        setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
    }

    private fun money(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * 启动期失败诊断视图。
     *
     * 为什么需要：真机「点开就闪退」时，屏幕上什么都不会留下；而本工程不可能接入
     * 崩溃上报（离线是硬约束，数据也不该出户），只能靠人肉复现。于是干脆把未捕获
     * 异常直接画在界面上——可长按选中复制，能截图发出来，比对着黑屏猜快得多。
     *
     * 注意这不是「吞掉异常」：诊断页出来后 [startupFailed] 会挡住后续逻辑，
     * App 处于明确的失败态，而不是半死不活地继续跑。
     */
    private fun showStartupFailure(stage: String, t: Throwable) {
        startupFailed = true
        val detail = buildString {
            append("启动失败\n\n")
            append("位置：").append(stage).append("\n\n")
            append(Log.getStackTraceString(t))
            append("\n\n—— 环境 ——\n")
            append("versionName = ").append(BuildConfig.VERSION_NAME).append('\n')
            append("Android ").append(Build.VERSION.RELEASE)
                .append("  API ").append(Build.VERSION.SDK_INT).append('\n')
            append("机型 ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        }
        val view = TextView(this).apply {
            text = detail
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(32, 32, 32, 32)
        }
        setContentView(ScrollView(this).apply { addView(view) })
    }

    private enum class Page { FLOW, ADD, STATS }

    companion object {
        /** 单月上限。账单一个月几百笔，500 足够；再多就该翻月而不是一屏拉到底 */
        private const val MONTH_ROW_LIMIT = 500

        /** 记一笔的分类网格列数：10 个分类 + 兜底共 11 项，6 列两行放得下 */
        private const val CATEGORY_SPAN = 6

        /** 最多往前翻多少个月 */
        private const val MIN_MONTH_OFFSET = -24
    }
}
