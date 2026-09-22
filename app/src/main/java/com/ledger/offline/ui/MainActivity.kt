package com.ledger.offline.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Html
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
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
import com.ledger.offline.capture.CaptureProbe
import com.ledger.offline.capture.CaptureStatus
import com.ledger.offline.classify.MerchantNormalizer
import com.ledger.offline.core.ServiceLocator
import com.ledger.offline.data.AmountInput
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
import com.ledger.offline.parse.ProbeLog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
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
        flowAdapter = TransactionAdapter(
            onRowClick = { txn ->
                // 点同一行＝收起，点另一行＝换一行选中。选中态由 adapter 自己持有，
                // Activity 不另存一份 id——两处存同一件事迟早对不上。
                val next = if (flowAdapter.selectedId() == txn.id) null else txn.id
                flowAdapter.setSelected(next)
            },
            onEdit = { txn -> showEditSheet(txn) },
            onDelete = { txn -> confirmDelete(txn) },
            onLongPress = { txn -> showCategorySheet(txn) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = flowAdapter

        statAdapter = StatBarAdapter()
        binding.statBars.layoutManager = LinearLayoutManager(this)
        binding.statBars.adapter = statAdapter

        // 授权条：按钮直接跳授权页，左侧文案是解释入口（为什么不占一行标题 + 一段正文：
        // 它是「还差一步」的提醒，不是说明书，且授权后整条要消失）
        binding.btnAuth.setOnClickListener { openListenerSettings() }
        binding.tvAuthText.setOnClickListener { showAuthHelp() }
        binding.btnService.setOnClickListener { openListenerSettings() }
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
        val leavingAdd = page == Page.ADD
        page = target
        renderPage()
        // 离开记一笔必须收起键盘：不收的话它会赖在别的页面上，
        // 而系统键盘不会因为没有输入框就自己消失
        if (leavingAdd) hideKeyboard()
        if (target != Page.ADD) refresh()
        if (target == Page.ADD) focusAmountField()
    }

    /**
     * 进「记一笔」就把光标落在金额上——点进来就是为了录一笔数，多一步点击没意义。
     *
     * 用 post 而不是直接 requestFocus：这一帧 pageAdd 刚从 GONE 变 VISIBLE，
     * 布局还没量完，此时弹键盘会算出错误的可见区域，输入框被压到看不见的位置。
     */
    private fun focusAmountField() {
        binding.etAmount.post {
            if (binding.etAmount.requestFocus()) {
                keyboard().showSoftInput(binding.etAmount, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun renderPage() {
        binding.pageFlow.visibility = if (page == Page.FLOW) View.VISIBLE else View.GONE
        binding.pageAdd.visibility = if (page == Page.ADD) View.VISIBLE else View.GONE
        binding.pageStats.visibility = if (page == Page.STATS) View.VISIBLE else View.GONE

        // 采集告警只在流水页上方出现：它们是「账目可能不完整」的提示，
        // 摆在记一笔 / 统计页上会挤掉内容，也与该页的任务无关
        val status = captureSnapshot()
        val onFlow = page == Page.FLOW
        binding.authBar.visibility =
            if (onFlow && !status.granted) View.VISIBLE else View.GONE
        binding.serviceBar.visibility =
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

        // 选中的那一行可能已经不在窗口里了：改了时间翻到别的月、删掉、或切了筛选。
        // 不清的话会出现「有一行高亮着，但屏幕上找不到它」的状态。
        val selected = flowAdapter.selectedId()
        if (selected != null && shown.none { it.id == selected }) {
            flowAdapter.setSelected(null)
        }

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

        binding.tvAddCancel.setOnClickListener {
            // 取消等于「放弃这次录入」，表单必须清空，否则下次点进来看到的是上一次的半截内容
            resetAddForm()
            switchPage(Page.FLOW)
        }
        binding.tvAddSave.setOnClickListener { saveManual() }
        binding.tvSegExpense.setOnClickListener { setAddDirection(Direction.EXPENSE) }
        binding.tvSegIncome.setOnClickListener { setAddDirection(Direction.INCOME) }
        setAddDirection(Direction.EXPENSE)

        binding.tvAddTime.setOnClickListener { pickTime(addOccurredAt) { addOccurredAt = it; renderAddTime() } }
        renderAddTime()
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

    /**
     * 「9月21日 14:05」。
     *
     * 记一笔与修改弹窗两处显示同一个字段，各写一遍格式化就会有一处漏掉补零。
     */
    private fun timeLabel(millis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return String.format(
            Locale.CHINA, "%d月%d日 %02d:%02d",
            cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
        )
    }

    private fun renderAddTime() {
        binding.tvAddTime.text = timeLabel(addOccurredAt)
    }

    /** 选日期 + 时间。[current] 是初始值，选定后回调新时间戳 */
    private fun pickTime(current: Long, onPicked: (Long) -> Unit) {
        val cal = Calendar.getInstance().apply { timeInMillis = current }
        DatePickerDialog(
            this,
            { _, y, m, d ->
                TimePickerDialog(
                    this,
                    { _, h, min ->
                        onPicked(
                            Calendar.getInstance().apply {
                                set(y, m, d, h, min, 0)
                                set(Calendar.MILLISECOND, 0)
                            }.timeInMillis
                        )
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
        val rawAmount = binding.etAmount.text.toString()
        val amount = AmountInput.parse(rawAmount)
        if (amount == null) {
            // 空＝还没填；填了但不合法＝格式有问题。两种提示分开，别让人对着「请输入金额」反复试
            toast(
                getString(
                    if (rawAmount.isBlank()) R.string.add_need_amount else R.string.amount_invalid
                )
            )
            binding.etAmount.requestFocus()
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
                resetAddForm()
                switchPage(Page.FLOW)
            }
            else -> {
                // 刻意不清空表单：撞指纹多半是金额敲错或多点了一个零，
                // 让用户改一位再来，比让他从头重录一遍强
                toast(getString(R.string.add_dup_hint))
            }
        }
    }

    private fun resetAddForm() {
        addOccurredAt = System.currentTimeMillis()
        binding.etAmount.setText("")
        binding.etMerchant.setText("")
        renderAddTime()
        setAddDirection(Direction.EXPENSE)
    }

    // ------------------------------------------------------------ 修改 / 删除一笔

    /**
     * 改一条**已经入库**的记录。
     *
     * 这里刻意不走 `mergeRecord()`：融合的语义是「新来的一笔 vs 库里既有的一笔」，
     * 而这是用户在纠正已经存在的东西——拿判重规则去决定要不要写入，
     * 等于允许用户手改的结果被机器否决。所以直接 [TransactionDao.updateRecord]。
     */
    private fun showEditSheet(txn: Transaction) {
        val rules = RuleStore.classifyRules(this).all()
        var picked: CategoryRule = rules.firstOrNull { it.id == txn.categoryId } ?: rules.first()
        var direction = txn.direction
        var occurredAt = txn.occurredAt

        val gridAdapter = CategoryGridAdapter(rules) { picked = it }
        gridAdapter.select(picked.id)
        val grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            adapter = gridAdapter
            isNestedScrollingEnabled = false
        }

        val etAmount = editField(getString(R.string.add_amount_hint)).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(Locale.US, "%.2f", txn.amount))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setTypeface(null, Typeface.BOLD)
        }
        val etMerchant = editField(getString(R.string.add_merchant_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            // 未知商户不显示那个常量桶名，否则用户以为那就是店名
            val known = txn.merchant.takeIf { it != MerchantNormalizer.UNKNOWN_MERCHANT }.orEmpty()
            setText(known)
        }
        val tvTime = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.text_primary))
            setPadding(0, dp(4), 0, dp(4))
        }
        fun renderSheetTime() { tvTime.text = timeLabel(occurredAt) }
        renderSheetTime()
        tvTime.setOnClickListener { pickTime(occurredAt) { occurredAt = it; renderSheetTime() } }

        val cbRemember = CheckBox(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) }
        fun refreshRememberState() {
            val name = etMerchant.text.toString().trim()
            val memorable = name.isNotBlank() && name != MerchantNormalizer.UNKNOWN_MERCHANT
            cbRemember.isEnabled = memorable
            cbRemember.isChecked = memorable
            cbRemember.text =
                getString(if (memorable) R.string.edit_remember else R.string.edit_remember_unknown)
        }
        etMerchant.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) = refreshRememberState()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        refreshRememberState()

        val segBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.bg_seg)
        }
        val segExpense = segmentCell(getString(R.string.filter_expense))
        val segIncome = segmentCell(getString(R.string.filter_income))
        segBar.addView(segExpense, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        segBar.addView(segIncome, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fun paintSegment() {
            segExpense.setBackgroundResource(
                if (direction == Direction.EXPENSE) R.drawable.bg_seg_on_expense else 0
            )
            segIncome.setBackgroundResource(
                if (direction == Direction.INCOME) R.drawable.bg_seg_on_income else 0
            )
            segExpense.setTextColor(
                getColor(if (direction == Direction.EXPENSE) R.color.surface else R.color.text_secondary)
            )
            segIncome.setTextColor(
                getColor(if (direction == Direction.INCOME) R.color.surface else R.color.text_secondary)
            )
        }
        segExpense.setOnClickListener { direction = Direction.EXPENSE; paintSegment() }
        segIncome.setOnClickListener { direction = Direction.INCOME; paintSegment() }
        paintSegment()

        val container = verticalContainer().apply {
            addView(segBar)
            addView(fieldLabel(getString(R.string.add_amount_label)))
            addView(etAmount)
            addView(fieldLabel(getString(R.string.add_merchant_label)))
            addView(etMerchant)
            addView(fieldLabel(getString(R.string.add_time_label)))
            addView(tvTime)
            addView(grid)
            addView(cbRemember)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.edit_title)
            .setView(ScrollView(this).apply { addView(container) })
            .setNegativeButton(R.string.add_cancel, null)
            // 正向按钮自己接管点击：默认实现无论校验结果如何都会关掉弹窗，
            // 金额填错就把整个表单吹掉，用户得重新点进来
            .setPositiveButton(R.string.add_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val amount = AmountInput.parse(etAmount.text.toString())
                if (amount == null) {
                    toast(getString(R.string.amount_invalid))
                    etAmount.requestFocus()
                    return@setOnClickListener
                }
                val merchant = etMerchant.text.toString().trim()
                    .ifBlank { MerchantNormalizer.UNKNOWN_MERCHANT }
                ServiceLocator.dao.updateRecord(
                    txn.copy(
                        amount = amount,
                        direction = direction,
                        merchant = merchant,
                        note = merchant,
                        categoryId = picked.id,
                        categoryName = picked.name,
                        occurredAt = occurredAt,
                        autoClassified = false
                    )
                )
                if (cbRemember.isChecked && merchant != MerchantNormalizer.UNKNOWN_MERCHANT) {
                    ServiceLocator.dao.rememberMerchant(merchant, picked.id, picked.name)
                }
                dialog.dismiss()
                toast(getString(R.string.edit_saved))
                refresh()
            }
        }
        dialog.show()
    }

    /**
     * 删除一笔。
     *
     * 必须二次确认：列表行是容易误触的地方，而删除不可逆（本机不留回收站，
     * 也没有云备份——离线是硬约束的一部分）。
     */
    private fun confirmDelete(txn: Transaction) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(R.string.delete_body)
            .setPositiveButton(R.string.row_delete) { _, _ ->
                ServiceLocator.dao.deleteById(txn.id)
                flowAdapter.setSelected(null)
                toast(getString(R.string.delete_done))
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        val container = verticalContainer()

        container.addView(TextView(this).apply {
            text = buildString {
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
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
        })

        // 诊断开关：默认关。存的是通知原文（含金额 / 商户名），
        // 与「字段级加密」的基线相悖，只能由用户自己决定是否临时开启。
        val toggle = CheckBox(this).apply {
            text = getString(R.string.probe_toggle)
            isChecked = CaptureProbe.isEnabled(this@MainActivity)
            setTextColor(getColor(R.color.text_primary))
            textSize = 13f
            setPadding(0, dp(10), 0, dp(4))
        }
        container.addView(toggle)

        container.addView(TextView(this).apply {
            text = getString(R.string.probe_toggle_note, ProbeLog.LIMIT)
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
        })

        val entries = CaptureProbe.recent(this)
        val viewLink = TextView(this).apply {
            text = if (CaptureProbe.isEnabled(this@MainActivity)) {
                getString(R.string.probe_view, entries.size)
            } else {
                getString(R.string.probe_view_off)
            }
            setTextColor(getColor(R.color.brand))
            textSize = 13f
            setPadding(0, dp(12), 0, dp(4))
        }
        container.addView(viewLink)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.capture_settings)
            .setView(container)
            .setPositiveButton(R.string.service_restart) { _, _ -> openListenerSettings() }
            .setNeutralButton(R.string.service_whitelist) { _, _ -> showWhitelistGuide() }
            .setNegativeButton("关闭", null)
            .show()

        toggle.setOnCheckedChangeListener { _, on ->
            CaptureProbe.setEnabled(this, on)
            dialog.dismiss()
            showCaptureSettings()          // 重开一次，让「最近通知」入口跟着更新
        }
        viewLink.setOnClickListener {
            if (CaptureProbe.isEnabled(this)) showProbeList()
        }
    }

    /**
     * 最近通知的原文与判定结果。
     *
     * 这是「有通知却没记账」的分诊台：列表里出现过这条通知 → 通道是活的，
     * 问题在规则；列表里根本没有 → 服务压根没收到，要去处理 ROM 的后台限制。
     * 两种情况界面上都表现为「什么都没发生」，只能靠这里分开。
     */
    private fun showProbeList() {
        val entries = CaptureProbe.recent(this).asReversed()   // 最新的排在最上面
        val container = verticalContainer()

        if (entries.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.probe_empty)
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
            })
        } else {
            val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
            for (e in entries) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = dp(8)
                    layoutParams = lp
                }
                card.addView(TextView(this).apply {
                    text = "${fmt.format(Date(e.at))}  ${e.sourceId}"
                    setTextColor(getColor(R.color.text_secondary))
                    textSize = 11f
                })
                card.addView(TextView(this).apply {
                    text = "「${e.title}」${e.text.ifBlank { getString(R.string.probe_no_text) }}"
                    setTextColor(getColor(R.color.text_primary))
                    textSize = 13f
                    setPadding(0, dp(4), 0, 0)
                })
                card.addView(TextView(this).apply {
                    text = e.outcome?.let { getString(R.string.probe_accepted, it) }
                        ?: getString(R.string.probe_dropped, e.drop?.label.orEmpty())
                    // 已入账用主色，被丢弃用告警色——一眼扫得出哪条没进去
                    setTextColor(getColor(if (e.accepted) R.color.brand else R.color.warn))
                    textSize = 12f
                    setPadding(0, dp(4), 0, 0)
                })
                container.addView(card)
            }
        }

        val scroll = ScrollView(this).apply {
            addView(container)
            setPadding(0, dp(8), 0, dp(8))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.probe_title)
            .setView(scroll)
            .setPositiveButton(R.string.probe_clear) { _, _ ->
                CaptureProbe.clear(this)
                toast("已清空")
            }
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

    private fun keyboard(): InputMethodManager =
        getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private fun hideKeyboard() {
        val token = currentFocus?.windowToken ?: return
        keyboard().hideSoftInputFromWindow(token, 0)
    }

    private fun verticalContainer() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = dp(16)
        setPadding(pad, pad / 2, pad, pad / 2)
    }

    /** 弹窗里的字段标题 */
    private fun fieldLabel(text: String) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(getColor(R.color.text_secondary))
        setPadding(0, dp(12), 0, dp(4))
    }

    /** 弹窗里的输入框。用系统键盘：金额 / 商户各自声明 inputType，输入法自己切换盘面 */
    private fun editField(hint: String) = EditText(this).apply {
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(getColor(R.color.text_primary))
        setHintTextColor(getColor(R.color.text_secondary))
        maxLines = 1
        setSingleLine(true)
        background = null
    }

    /** 收支分段的一格 */
    private fun segmentCell(text: String) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
