package com.ledger.offline.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ledger.offline.R
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

            val sourceLabel = when (txn.sourceId) {
                "wechat" -> "微信"
                "alipay" -> "支付宝"
                "csv" -> "账单导入"
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
