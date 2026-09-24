package com.ledger.offline.ui

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.ledger.offline.data.CategoryDao
import com.ledger.offline.data.CategoryPresets
import com.ledger.offline.data.FlowList
import com.ledger.offline.data.MergeMatcher
import com.ledger.offline.data.MonthWindow
import com.ledger.offline.data.TransactionDao
import com.ledger.offline.data.model.Category
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

    /** 记一笔页的两级分类选择器（一级网格 + 二级网格联动） */
    private var addPicker: CategoryPicker? = null

    /** 一旦置位就停掉后续逻辑，界面停留在诊断视图上 */
    private var startupFailed = false

    private var page = Page.FLOW
    /** 0 = 当前月，-1 = 上一个月。正数指向未来，没有账可看，所以只允许 ≤ 0 */
    private var monthOffset = 0
    private var filter = FlowList.Filter.ALL

    /** 刚落库那一笔的 id。切回流水页时要滚到它并选中，见 [revealPendingRow] */
    private var pendingRowId: Long? = null

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
            // 点行直接进编辑（0.2.10 按用户要求简化：原先要先点开选中态再点「修改」，
            // 两步变一步）。删除入口也收进编辑弹窗——一次点行，改与删都在手边。
            onRowClick = { txn -> showEditSheet(txn) },
            onLongPress = { txn -> showCategorySheet(txn) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = flowAdapter

        statAdapter = StatBarAdapter()
        binding.statBars.layoutManager = LinearLayoutManager(this)
        binding.statBars.adapter = statAdapter

        // 授权条：按钮**不**直接跳系统页，先弹说明。
        // 那个页面列的是「申请读通知的应用」，用户进去必然找不到微信 / 支付宝，
        // 于是不知所措地勾了别的条目就退出来——App 这边永远显示未授权。
        // 解释如果只挂在左侧那行小字上，等于没有：用户看到按钮就点。
        binding.btnAuth.setOnClickListener { showAuthHelp() }
        binding.tvAuthText.setOnClickListener { showAuthHelp() }
        binding.btnService.setOnClickListener { showAuthHelp() }
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
        if (target == Page.ADD) {
            // 进页即重算常用分类：离开期间可能导入过账单、改过分类或删过记录
            refreshUsageRow()
            focusAmountField()
        }
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

        val uncat = all.count {
            FlowList.isUnclassified(it, MerchantNormalizer.UNKNOWN_MERCHANT, rules.fallback.id)
        }
        binding.chipUncat.text = getString(R.string.filter_uncat, uncat)
        renderChips()

        val shown = FlowList.filter(all, filter, MerchantNormalizer.UNKNOWN_MERCHANT, rules.fallback.id)
        // 记录可能落在二级（txn.category_id 指向最细粒度），圆点颜色取其一级的识别色
        val rollup = ServiceLocator.categoryDao.rollupMap()
        flowAdapter.submit(FlowList.group(shown), { rules.colorHex(rollup[it] ?: it) }, rules.fallback.id)

        // 选中的那一行可能已经不在窗口里了：改了时间翻到别的月、删掉、或切了筛选。
        // 不清的话会出现「有一行高亮着，但屏幕上找不到它」的状态。
        val selected = flowAdapter.selectedId()
        if (selected != null && shown.none { it.id == selected }) {
            flowAdapter.setSelected(null)
        }

        binding.tvEmpty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        binding.recycler.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE

        revealPendingRow(shown)
    }

    /**
     * 把刚记下的那一笔摆到用户眼前：滚动到它所在的行并选中。
     *
     * 为什么必须做：列表按时间倒序，而「记一笔」的时间默认是**现在**——
     * 用户若把时间改成上个月，或当前筛选是「支出」而他记的是收入，
     * 保存后切回流水页会「什么都没发生」，那笔其实已经进库了，只是不在视野里。
     * 用户只能靠再翻一遍月份来确认自己到底记进去没有。
     *
     * 选中它（而不只是滚到那儿）是刻意的：高亮指出「是这一条」；
     * 点行即进编辑，用户要改刚记错的数也就是再点一下的事。
     */
    private fun revealPendingRow(shown: List<Transaction>) {
        val id = pendingRowId ?: return
        pendingRowId = null
        if (shown.none { it.id == id }) return
        val pos = flowAdapter.positionOf(id)
        if (pos < 0) return
        binding.recycler.scrollToPosition(pos)
        flowAdapter.setSelected(id)
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
            all, rules.categories, rules.fallback, MerchantNormalizer.UNKNOWN_MERCHANT,
            // 记在二级的账按一级聚合出条形，明细仍留在流水里
            ServiceLocator.categoryDao.rollupMap()
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
        // 两级分类选择：一级网格常驻，点选某一级后在其下展开二级。
        // 默认选中「餐饮-买菜」：用户高频场景是买菜做饭（0.2.10 按用户要求，
        // 由「不替用户猜」的「其他」改为替他省一次点击）。
        addPicker = CategoryPicker(
            binding.catGrid, binding.catGrid2, CategoryPresets.DEFAULT_ADD_CATEGORY_ID
        )
        binding.tvManageCategories.setOnClickListener { showCategoryManager() }

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
        // 常用区跟方向走：支出显示高频支出分类，收入显示高频收入分类
        refreshUsageRow()
    }

    /**
     * 常用分类条（0.2.11）：历史高频分类一键直达。
     *
     * - 频次按**当前方向**统计、范围为全部来源（含账单导入）——买得多的品类
     *   自然靠前，比只数手动记的更贴近真实消费习惯
     * - 点 chip 与点网格同源同效（resetTo 即选中 + 展开其父级的二级）
     * - chips 不做选中态：选中由下方网格表达，两处状态迟早对不上（见 topUsed 注释）
     * - 达不到门槛（至少 2 次）→ 整行隐藏，新装机器不显示空壳
     */
    private fun refreshUsageRow() {
        val topIds = CategoryDao.topUsed(ServiceLocator.dao.categoryUsageCount(addDirection))
        // 已删自定义分类的历史 id 理论不会出现（删除时已回退），mapNotNull 兜住脏数据
        val byId = ServiceLocator.categoryDao.all().associateBy { it.id }
        val chips = topIds.mapNotNull { byId[it] }

        binding.usageChips.removeAllViews()
        binding.usageRow.visibility = if (chips.isEmpty()) View.GONE else View.VISIBLE
        for (c in chips) {
            binding.usageChips.addView(TextView(this).apply {
                text = c.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setBackgroundResource(R.drawable.bg_chip)
                setTextColor(getColor(R.color.text_primary))
                setPadding(dp(12), dp(5), dp(12), dp(5))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(7) }
                setOnClickListener { addPicker?.resetTo(c.id) }
            })
        }
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
     * 月底导账单又来一笔」会在账本里留下两条。
     *
     * 三种融合结论的处理：
     * - ADDED：真的新增 → 把表单里的分类写到这一笔
     * - BACKFILLED：并进了既有记录（多半是通知抓到的、缺商户名那批）→
     *   **同样要应用表单里的分类**。用户在「记一笔」里亲手选了「餐饮」，
     *   这条信息不能因为合并就丢了，否则界面上仍是兜底分类，
     *   表现为「我明明选了，它怎么没变」。
     * - DUPLICATE：库里那条已经很完整，没什么可补 → 不动它，也不清表单。
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
        val pickedCategory = addPicker?.selected() ?: return
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
            TransactionDao.MergeOutcome.ADDED, TransactionDao.MergeOutcome.BACKFILLED -> {
                val id = result.rowId
                if (id != null) {
                    ServiceLocator.correctCategory(
                        id,
                        merchant.ifBlank { MerchantNormalizer.UNKNOWN_MERCHANT },
                        pickedCategory.id,
                        pickedCategory.name
                    )
                }
                // 让这一笔一定出现在视野里：时间可能不是当月，筛选也可能把它挡在外面。
                // 三件事一起做，否则「记完了却看不见」和「没记进去」在界面上没法区分。
                pendingRowId = id
                monthOffset = MonthWindow
                    .offsetOf(System.currentTimeMillis(), addOccurredAt)
                    .coerceIn(MIN_MONTH_OFFSET, 0)
                filter = FlowList.Filter.ALL

                // 保存反馈只要一句「保存成功」（0.2.10 按用户要求）：
                // 新增与并进既有是两种结局，但用户此刻关心的都只是「存上了没有」，
                // 区分措辞反而让「并入」这种内部术语打断他。
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
        // 分类同样归位到默认「餐饮-买菜」：表单清空就是「回到起点」，
        // 分类停在上一笔的选择上，等于没清干净
        addPicker?.resetTo(CategoryPresets.DEFAULT_ADD_CATEGORY_ID)
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
        val grid = RecyclerView(this)
        val childGrid = RecyclerView(this)
        val picker = CategoryPicker(grid, childGrid, txn.categoryId)
        var direction = txn.direction
        var occurredAt = txn.occurredAt

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
            addView(childGrid)
            addView(manageCategoryLink())
            addView(cbRemember)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.edit_title)
            .setView(ScrollView(this).apply { addView(container) })
            .setNegativeButton(R.string.add_cancel, null)
            // 0.2.10：删除入口收进编辑弹窗（点行即达），不再依赖列表行的操作按钮。
            // 仍走 confirmDelete 的二次确认——删除不可逆，确认框是最后一道闸。
            .setNeutralButton(R.string.row_delete) { d, _ ->
                d.dismiss()
                confirmDelete(txn)
            }
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
                val picked = picker.selected()
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
     * 必须二次确认：删除不可逆（本机不留回收站，也没有云备份——离线是硬约束
     * 的一部分）。入口在编辑弹窗的「删除」键，从弹窗到确认框是两道独立操作，
     * 连点两下不会贯穿。
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
        val classifies = RuleStore.classifyRules(this)
        val grid = RecyclerView(this)
        val childGrid = RecyclerView(this)
        val picker = CategoryPicker(grid, childGrid, txn.categoryId)

        val container = verticalContainer().apply {
            addView(txnCaption(txn))
            if (FlowList.isUnclassified(
                    txn, MerchantNormalizer.UNKNOWN_MERCHANT, classifies.fallback.id
                )
            ) {
                addView(noteView(getString(R.string.fix_unknown_note)))
            }
            addView(grid)
            addView(childGrid)
            addView(manageCategoryLink())
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.fix_category_title)
            .setView(container)
            .setPositiveButton(R.string.fix_remember) { _, _ ->
                applyCorrection(txn, picker.selected(), remember = true)
            }
            .setNegativeButton(R.string.fix_once) { _, _ ->
                applyCorrection(txn, picker.selected(), remember = false)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyCorrection(txn: Transaction, cat: Category, remember: Boolean) {
        ServiceLocator.correctCategory(
            txn.id, txn.merchant, cat.id, cat.name, remember = remember
        )
        if (remember && txn.merchant != MerchantNormalizer.UNKNOWN_MERCHANT) {
            toast(getString(R.string.fixed_toast, txn.merchant, cat.name))
        } else {
            toast(cat.name)
        }
        refresh()
    }

    // ------------------------------------------------------------ 分类（两级选择 + 自定义管理）

    /** 「管理分类」入口链接：记一笔页与两处弹窗共用一份 */
    private fun manageCategoryLink() = TextView(this).apply {
        text = getString(R.string.category_manage)
        setTextColor(getColor(R.color.brand))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(8), 0, dp(4))
        setOnClickListener { showCategoryManager() }
    }

    /**
     * 两级分类选择器：一级网格常驻，二级网格随一级切换展开。
     *
     * 落库永远存**最细粒度**（选了二级存二级 id，只选一级存一级 id），
     * 统计层再按一级聚合——存储按明细、展示按汇总，两边不打架。
     * 三处选择场景（记一笔 / 修改弹窗 / 长按修正）共用这一个实现。
     */
    private inner class CategoryPicker(
        private val parentGrid: RecyclerView,
        private val childGrid: RecyclerView,
        initialId: String?
    ) {
        private var cats: List<Category> = ServiceLocator.categoryDao.all()
        private var rollup: Map<String, String> = CategoryDao.rollup(cats)
        private var picked: Category = resolve(initialId ?: CategoryPresets.FALLBACK_ID)

        /** 二级与自定义分类没有专属识别色——取其一级的色，视觉上聚成一组 */
        private val colorOf: (String) -> String = {
            RuleStore.classifyRules(this@MainActivity).colorHex(rollup[it] ?: it)
        }

        private val parentAdapter = CategoryGridAdapter(parents().toRules()) { rule ->
            picked = cats.first { it.id == rule.id }
            applySelection()
        }
        private val childAdapter = CategoryGridAdapter(childrenOf(topOf(picked)).toRules()) { rule ->
            picked = cats.first { it.id == rule.id }
            applySelection()
        }

        init {
            parentGrid.layoutManager = GridLayoutManager(this@MainActivity, CATEGORY_SPAN)
            parentGrid.adapter = parentAdapter
            parentGrid.isNestedScrollingEnabled = false
            childGrid.layoutManager = GridLayoutManager(this@MainActivity, CATEGORY_CHILD_SPAN)
            childGrid.adapter = childAdapter
            childGrid.isNestedScrollingEnabled = false
            applySelection()
        }

        fun selected(): Category = picked

        /** 归位到指定分类（表单重置用）。不存在的 id 回退兜底，逻辑与构造时一致 */
        fun resetTo(id: String) {
            picked = resolve(id)
            applySelection()
        }

        /** 管理页增删排序后调用：从库里重载，尽量保住原选中（被删则回退兜底） */
        fun reload() {
            val keepId = picked.id
            cats = ServiceLocator.categoryDao.all()
            rollup = CategoryDao.rollup(cats)
            picked = resolve(keepId)
            parentAdapter.replaceItems(parents().toRules())
            applySelection()
        }

        private fun applySelection() {
            val topId = topOf(picked)
            parentAdapter.select(topId)
            val children = childrenOf(topId)
            if (children.isEmpty()) {
                childGrid.visibility = View.GONE
            } else {
                childGrid.visibility = View.VISIBLE
                childAdapter.replaceItems(children.toRules())
                childAdapter.select(picked.id)
            }
        }

        private fun topOf(c: Category): String = rollup[c.id] ?: c.id

        private fun parents(): List<Category> = cats.filter { it.parentId.isEmpty() }

        private fun childrenOf(parentId: String): List<Category> =
            cats.filter { it.parentId == parentId }

        private fun resolve(id: String): Category =
            cats.firstOrNull { it.id == id }
                ?: cats.firstOrNull { it.id == CategoryPresets.FALLBACK_ID }
                ?: cats.first()

        private fun List<Category>.toRules(): List<CategoryRule> =
            map { CategoryRule(it.id, it.name, emptyList(), colorOf(it.id)) }
    }

    /**
     * 分类管理：新增 / 删除（仅自定义）/ 同级排序。
     * 预置分类不可删——既有账目、关键词规则、官方种子三处都锚在这批 id 上，
     * 删掉一个就是三处同时断。
     */
    private fun showCategoryManager() {
        val container = verticalContainer()

        fun rebuild() {
            container.removeAllViews()
            val all = ServiceLocator.categoryDao.all()
            val rollup = CategoryDao.rollup(all)
            val rules = RuleStore.classifyRules(this)
            for (cat in all) {
                container.addView(
                    categoryManagerRow(cat, rollup, { rules.colorHex(it) }, ::rebuild)
                )
            }
            // 记一笔页的选择器还开着，名单变了必须跟上
            addPicker?.reload()
        }
        rebuild()

        val scroll = ScrollView(this).apply {
            addView(container)
            setPadding(0, dp(4), 0, dp(4))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.category_manage_title)
            .setView(scroll)
            .setPositiveButton(R.string.category_add) { _, _ -> showAddCategoryDialog() }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** 管理列表的一行：色点 + 名称（二级缩进）+ 自定义的 ↑↓删 */
    private fun categoryManagerRow(
        cat: Category,
        rollup: Map<String, String>,
        colorOf: (String) -> String,
        onChange: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    runCatching { Color.parseColor(colorOf(rollup[cat.id] ?: cat.id)) }
                        .getOrDefault(Color.GRAY)
                )
            }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                rightMargin = dp(10)
            }
        })
        row.addView(TextView(this).apply {
            text = cat.name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (cat.parentId.isNotEmpty()) marginStart = dp(16) }
        })
        if (cat.isCustom) {
            fun actionBtn(label: String, onTap: () -> Unit) = TextView(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(getColor(R.color.brand))
                setPadding(dp(10), dp(2), dp(10), dp(2))
                setOnClickListener { onTap() }
            }
            row.addView(actionBtn("↑") { ServiceLocator.categoryDao.move(cat.id, true); onChange() })
            row.addView(actionBtn("↓") { ServiceLocator.categoryDao.move(cat.id, false); onChange() })
            row.addView(actionBtn("删") { confirmRemoveCategory(cat) { onChange() } })
        } else {
            row.addView(TextView(this).apply {
                text = "预置"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(getColor(R.color.text_secondary))
            })
        }
        return row
    }

    /** 删除确认：先讲清「账会归到哪」，删除才不可怕 */
    private fun confirmRemoveCategory(cat: Category, onDone: () -> Unit) {
        val back = CategoryDao.fallbackTarget(cat, ServiceLocator.categoryDao.all())
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.category_delete_title, cat.name))
            .setMessage(getString(R.string.category_delete_body, back.name))
            .setPositiveButton(R.string.row_delete) { _, _ ->
                when (ServiceLocator.categoryDao.removeCustom(cat.id)) {
                    CategoryDao.Removal.OK -> {
                        toast(getString(R.string.category_delete_done, cat.name))
                        onDone()
                    }
                    CategoryDao.Removal.HAS_CHILDREN ->
                        toast(getString(R.string.category_delete_has_children))
                    else -> toast(getString(R.string.category_preset_no_delete))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 新增自定义分类：起名 + 选挂载位置（新一级 / 某个一级之下，最多两级） */
    private fun showAddCategoryDialog() {
        val et = editField(getString(R.string.category_add_name_hint)).apply {
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val parents = ServiceLocator.categoryDao.all().filter { it.parentId.isEmpty() }
        val radioGroup = android.widget.RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        fun option(label: String, value: String, checked: Boolean) =
            android.widget.RadioButton(this@MainActivity).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(getColor(R.color.text_primary))
                isChecked = checked
                setOnClickListener { tag = value }
                tag = value
            }
        radioGroup.addView(option(getString(R.string.category_add_as_top), "", true))
        for (p in parents) {
            radioGroup.addView(option(p.name, p.id, false))
        }

        val container = verticalContainer().apply {
            addView(et)
            addView(fieldLabel(getString(R.string.category_add_parent_label)))
            addView(radioGroup)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.category_add)
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton(R.string.add_save) { _, _ ->
                val name = et.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                // RadioGroup 里选中项的 tag 即目标父级 id（空串 = 新建一级）
                val parentId = radioGroup.findViewById<android.widget.RadioButton>(
                    radioGroup.checkedRadioButtonId
                )?.tag as? String ?: ""
                if (!ServiceLocator.categoryDao.addCustom(name, parentId)) {
                    toast(getString(R.string.category_add_dup))
                } else {
                    toast(getString(R.string.category_add_done, name))
                    showCategoryManager()
                    addPicker?.reload()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
     * 系统的通知使用权页面列出的是**申请读取通知的应用**，里面只会有「我的账单」。
     * 挑微信 / 支付宝是代码里的包名白名单（parser_rules.json 的 packageNames），
     * 不是用户在系统里勾的。用户第一次进去必然找不到，所以把解释做成入口。
     */
    private fun showAuthHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.permission_help_title)
            .setMessage(Html.fromHtml(permissionHelpHtml(), Html.FROM_HTML_MODE_LEGACY))
            .setPositiveButton(R.string.permission_grant) { _, _ -> openListenerSettings() }
            // 「勾了却没用」是这里的常态，必须给一条不用回话就能自查的路：
            // 进采集设置能看到系统到底记录了谁，比再来一轮"你确定勾了吗"有用
            .setNeutralButton(R.string.auth_still_failing) { _, _ -> showCaptureSettings() }
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
        val entries = CaptureProbe.recent(this)
        val unknown = CaptureProbe.unknownSources(this).asReversed()

        // 分诊结论放在最上面：把「授权 / 心跳 / 最近通知 / 未被放行的来源」合成一句人话。
        // 把四行原始数据摊开让人自己推导已经失败过一次——上一轮只看到「心跳有、列表空」
        // 就判成链路打通，漏掉了「包名不在白名单」这条本该先排除的可能。
        container.addView(TextView(this).apply {
            text = triageConclusion(status, entries, unknown)
            setTextColor(getColor(if (status.granted) R.color.brand else R.color.warn))
            textSize = 13f
            setPadding(0, 0, 0, dp(8))
        })

        // 系统授权记录的原文。「明明勾了却显示未授权」的唯一仲裁者：
        // 这里空着＝系统里确实没有授权记录，去勾；有内容但没有本 App＝勾的是别的应用。
        // 两种情况的界面表现完全一样，不摆出原文就只能靠猜。
        val raw = enabledListenersRaw()
        container.addView(TextView(this).apply {
            text = "系统授权记录：${raw.ifBlank { "（空）" }}\n本应用包名：$packageName"
            setTextColor(getColor(if (status.granted) R.color.text_secondary else R.color.warn))
            textSize = 11f
            setPadding(0, 0, 0, dp(8))
        })

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

        // 未被放行的通知来源（只有包名，没有原文）。
        // 「最近通知」全空时这是唯一的线索：里面出现了支付宝 / 微信的包名 ⇒ 包名不在
        // 白名单，早筛就把它丢了；连它们的影子都没有 ⇒ 通知压根没到服务，
        // 该去处理授权 / ROM 后台限制。两种情况界面上完全一样，没有这一栏只能靠猜。
        val unknownFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
        val suspect = unknown.firstOrNull { looksLikePaymentPkg(it.pkg) }
        container.addView(TextView(this).apply {
            text = buildString {
                append(getString(R.string.probe_unknown_label)).append("：")
                if (unknown.isEmpty()) {
                    append(
                        if (CaptureProbe.isEnabled(this@MainActivity)) {
                            getString(R.string.probe_unknown_none)
                        } else {
                            getString(R.string.probe_unknown_off)
                        }
                    )
                } else {
                    append("\n")
                    for (u in unknown.take(6)) {
                        append("  ").append(u.pkg)
                        append("  ").append(getString(R.string.probe_unknown_count, u.count))
                        append("  ").append(unknownFmt.format(Date(u.lastAt)))
                        append("\n")
                    }
                    if (unknown.size > 6) append("  …").append(getString(R.string.probe_unknown_more, unknown.size - 6))
                }
                if (suspect != null) append("\n").append(getString(R.string.probe_unknown_suspect))
            }
            setTextColor(getColor(if (suspect != null) R.color.warn else R.color.text_secondary))
            textSize = 11f
            setPadding(0, 0, 0, dp(8))
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
     * 「有通知却没记账」的分诊结论，按顺序逐个排除。
     *
     * 四个原始信号（是否授权 / 服务有没有连上过 / 最近一次目标通知 / 未被放行的来源）
     * 之前分散在状态条和诊断开关两处，靠人拼成结论已经失败过一次：
     * 上一轮只看到「心跳有、列表空」就宣布链路打通，漏掉了「包名不在白名单」
     * 这条本该第一个排除的可能。这里按可能性从高到低排除，直接给出下一步动作。
     */
    private fun triageConclusion(
        status: CaptureStatus.Snapshot,
        entries: List<ProbeLog.ProbeEntry>,
        unknown: List<ProbeLog.UnknownSource>
    ): String {
        if (!status.granted) {
            return "① 系统里还没有本应用的授权记录：点底部「重新绑定」去系统页勾选「我的账单 · 支付通知监听」。"
        }
        if (!status.everConnected) {
            return "② 已授权但监听服务从未连上：装包后系统要重新绑定一次。先点「重新绑定」（在系统页把开关关掉再打开），不行的话重启手机。"
        }
        val paymentUnknown = unknown.firstOrNull { looksLikePaymentPkg(it.pkg) }
        if (paymentUnknown != null) {
            return "③ 收到过「${paymentUnknown.pkg}」的通知共 ${paymentUnknown.count} 次，但包名不在白名单里，早筛阶段就被丢了。把这个包名发我，去 parser_rules.json 补 packageNames。"
        }
        if (entries.isEmpty() && status.lastEventAt > 0L) {
            return "④ 最近收到过目标 App 的通知（${status.daysSinceEvent()} 天前），但诊断里没有它：那条是在打开诊断开关之前到的，开关不追溯历史。请留在本页，再付一笔（换个金额），然后回来刷新。"
        }
        if (entries.isNotEmpty()) {
            val dropped = entries.count { !it.accepted }
            return "⑤ 最近 ${entries.size} 条通知，其中 $dropped 条没入账。点「查看最近通知」，每条下面写着卡在哪一关。"
        }
        if (unknown.isEmpty()) {
            return "⑥ 服务连上了，但一条通知都没收到过（连未被放行的 App 都没有）：去确认支付宝 / 微信的「系统通知」开关是开着的，并检查本 App 有没有被 ROM 的省电策略冻结。"
        }
        return "⑦ 服务在收通知（有 ${unknown.size} 个未被放行的来源），但里面没有微信 / 支付宝。留在本页再付一笔，看它会不会出现。"
    }

    /** 包名看着像支付 App：用于在「未被放行的来源」里把真正该看的几个标红 */
    private fun looksLikePaymentPkg(pkg: String): Boolean {
        val p = pkg.lowercase(Locale.ROOT)
        return PAYMENT_PKG_HINTS.any { p.contains(it) }
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
            // 列表是「打开时读一次」的快照，付款完还停在同一个弹窗里是看不到新记录的——
            // 给一个显式刷新，别让用户把快照陈旧误读成「没采集到」
            .setPositiveButton(R.string.probe_refresh) { d, _ ->
                d.dismiss()
                showProbeList()
            }
            .setNeutralButton(R.string.probe_clear) { d, _ ->
                CaptureProbe.clear(this)
                d.dismiss()
                showProbeList()
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

    /**
     * 系统里 `enabled_notification_listeners` 的原文。
     *
     * 只读 Settings.Secure 而不直接用 [NotificationManagerCompat.getEnabledListenerPackages]：
     * 后者已经把 ComponentName 拆成了包名，正好抹掉了判断"勾的是谁"所需的信息——
     * 而用户在系统页里勾错条目，恰恰是「明明授权了却显示未授权」最常见的原因。
     */
    private fun enabledListenersRaw(): String = runCatching {
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
    }.getOrDefault("(读取失败)")

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

        /** 记一笔的一级分类网格列数：13 个一级，6 列三行放得下 */
        private const val CATEGORY_SPAN = 6

        /** 二级分类网格列数：每个一级的二级最多 6 个，4 列两行内放得下 */
        private const val CATEGORY_CHILD_SPAN = 4

        /** 最多往前翻多少个月 */
        private const val MIN_MONTH_OFFSET = -24

        /**
         * 包名里出现这些字样就当「疑似支付 App」。
         * 只用来在未被放行的来源里把该看的几行标红，不做任何放行判断——
         * 放行只能由 `parser_rules.json` 的 packageNames 决定。
         */
        private val PAYMENT_PKG_HINTS = listOf("alipay", "tencent", "weixin", "unionpay", "pay")
    }
}
