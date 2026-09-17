package com.ledger.offline.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.DividerItemDecoration
import com.ledger.offline.R
import com.ledger.offline.capture.BillImporter
import com.ledger.offline.capture.CaptureStatus
import com.ledger.offline.capture.CsvIo
import com.ledger.offline.core.ServiceLocator
import com.ledger.offline.data.model.Transaction
import com.ledger.offline.databinding.ActivityMainBinding
import com.ledger.offline.parse.RuleStore
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: TransactionAdapter

    private val openCsv = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // 账单导入：xlsx / csv 由 BillImporter 按文件头自动分流
        val result = BillImporter.import(this, uri)
        if (result.error != null) {
            toast(result.error)
        } else {
            val summary = result.summary()
            // 记下「最近一次导入」：首页状态条靠它提醒用户别让账目长期残缺
            CaptureStatus.markImport(this, summary)
            toast(summary)
            refresh()
        }
    }

    /**
     * POST_NOTIFICATIONS 的运行时授权（Android 13+）。
     * 唯一用途是重启后那条「已恢复运行」的提醒——拒绝它不影响记账本身，
     * 所以这里不做解释弹窗，用户点了拒绝就算了，不反复打扰。
     */
    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 拒绝也不影响记账 */ }

    private val createCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@registerForActivityResult
        val count = CsvIo.export(this, uri)
        toast("已导出 $count 条记录")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = TransactionAdapter(emptyList()) { txn -> showCategoryPicker(txn) }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        binding.recycler.adapter = adapter

        binding.btnGrant.setOnClickListener { openListenerSettings() }
        binding.btnRestartListener.setOnClickListener { openListenerSettings() }
        binding.btnWhitelist.setOnClickListener { showWhitelistGuide() }
        // 状态条本身就是入口：点它能看到「到底哪一环没在跑」
        binding.statusBar.setOnClickListener { showCaptureSettings() }

        binding.btnImport.setOnClickListener {
            openCsv.launch(
                arrayOf(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",  // .xlsx
                    "text/csv",
                    "text/*",
                    "*/*"
                )
            )
        }
        binding.btnExport.setOnClickListener { createCsv.launch("ledger-backup.csv") }

        askNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val status = captureSnapshot()

        binding.permissionBanner.visibility = if (status.granted) View.GONE else View.VISIBLE
        // 「授权在、服务死」必须显式说出来：此时界面其余部分一切正常，
        // 但一笔都不会自动记录，是整条链路里最隐蔽的失效方式
        binding.serviceBanner.visibility = if (status.listenerStalled) View.VISIBLE else View.GONE

        binding.tvListenStatus.text = when {
            !status.granted -> getString(R.string.status_listen_off)
            status.listenerStalled -> getString(R.string.status_listen_stalled)
            else -> getString(R.string.status_listen_on)
        } + " · " + when (val days = status.daysSinceEvent()) {
            -1L -> getString(R.string.status_event_never)
            else -> getString(R.string.status_event_days, days)
        }

        val importDays = status.daysSinceImport()
        binding.tvImportStatus.text = when {
            importDays < 0L -> getString(R.string.status_import_never)
            status.importStale() -> getString(R.string.status_import_stale, importDays)
            else -> getString(R.string.status_import_days, importDays)
        }
        binding.tvImportStatus.setTextColor(
            ContextCompat.getColor(
                this,
                // 超期未导入标红：账目完整性在滑坡，这比任何统计数字都更该被看见
                if (status.importStale()) R.color.expense else R.color.text_secondary
            )
        )

        val (from, to) = ServiceLocator.monthRange()
        val stats = ServiceLocator.dao.stats(from, to)
        binding.tvExpense.text = "本月支出 ¥${String.format(Locale.US, "%.2f", stats.expense)}"
        binding.tvIncome.text = "本月收入 ¥${String.format(Locale.US, "%.2f", stats.income)}"

        val list = ServiceLocator.dao.queryRange(from, to, 500)
        adapter.submit(list)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.recycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    /** 长按改分类 → 写入修正记忆，下次同商户自动命中 */
    private fun showCategoryPicker(txn: Transaction) {
        val categories = RuleStore.classifyRules(this).all()
        val labels = categories.map { "${it.name}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("把「${txn.merchant}」归到哪一类？")
            .setItems(labels) { _, index ->
                val picked = categories[index]
                ServiceLocator.correctCategory(txn, picked.id, picked.name)
                toast("已记住：${txn.merchant} → ${picked.name}")
                refresh()
            }
            .setNegativeButton("取消", null)
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

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
