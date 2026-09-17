package com.ledger.offline.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.DividerItemDecoration
import com.ledger.offline.R
import com.ledger.offline.capture.BillImporter
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
            toast(result.summary())
            refresh()
        }
    }

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

        binding.btnGrant.setOnClickListener {
            runCatching {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }.onFailure { toast("请在系统设置中开启通知使用权") }
        }

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
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        binding.permissionBanner.visibility =
            if (isListenerEnabled()) View.GONE else View.VISIBLE

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

    private fun isListenerEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
