package com.ledger.offline.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ledger.offline.R
import com.ledger.offline.data.MergeMatcher
import com.ledger.offline.data.model.Direction
import com.ledger.offline.data.model.Transaction
import com.ledger.offline.databinding.ItemTransactionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TransactionAdapter(
    private var items: List<Transaction>,
    private val onLongClick: (Transaction) -> Unit
) : RecyclerView.Adapter<TransactionAdapter.Holder>() {

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    fun submit(list: List<Transaction>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemTransactionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemTransactionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(txn: Transaction) = with(binding) {
            tvMerchant.text = txn.merchant.ifBlank { "未识别商户" }

            // 用前缀判断，而不是把 "bill_xlsx" / "bill_csv" 再抄一遍：
            // 这个前缀是 MergeMatcher 判重的硬判据（决定宽松指纹那扇门给谁开），
            // 标签只是它的另一种读法。抄一份字面量就等于造出第四处定义，
            // 下次加新来源时必然再漂一次（2026-09-17 的 "csv" 就是这么留下来的死分支）。
            val sourceLabel = when {
                txn.sourceId.startsWith(MergeMatcher.BILL_SOURCE_PREFIX) -> "账单导入"
                txn.sourceId == "wechat" -> "微信"
                txn.sourceId == "alipay" -> "支付宝"
                else -> "其它"
            }
            val autoLabel = if (txn.autoClassified) "" else " · 已手动修正"
            tvMeta.text = buildString {
                append(timeFormat.format(Date(txn.occurredAt)))
                append(" · ").append(txn.categoryName)
                append(" · ").append(sourceLabel)
                append(autoLabel)
            }

            val sign = if (txn.direction == Direction.EXPENSE) "-" else "+"
            tvAmount.text = "$sign¥${String.format(Locale.US, "%.2f", txn.amount)}"
            tvAmount.setTextColor(
                root.context.getColor(
                    if (txn.direction == Direction.EXPENSE) R.color.expense else R.color.income
                )
            )

            root.setOnLongClickListener { onLongClick(txn); true }
        }
    }
}
