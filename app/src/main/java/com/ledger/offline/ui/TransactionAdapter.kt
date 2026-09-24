package com.ledger.offline.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ledger.offline.R
import com.ledger.offline.classify.MerchantNormalizer
import com.ledger.offline.data.CategoryBreakdown
import com.ledger.offline.data.FlowList
import com.ledger.offline.data.MergeMatcher
import com.ledger.offline.data.model.Direction
import com.ledger.offline.data.model.Transaction
import com.ledger.offline.databinding.ItemDayHeaderBinding
import com.ledger.offline.databinding.ItemTransactionBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 流水列表：日期分组头 + 交易行，两种类型。
 *
 * 分组头带上当日支出小计（设计稿屏 1），所以「分档」与「求和」是同一遍扫描完成的
 * （[FlowList.group]），这里只负责渲染，不再自己算一遍——两处各算一次迟早对不上。
 *
 * 点行＝打开编辑（修改 / 删除都在编辑弹窗里，0.2.10 简化）；
 * 长按＝快速修正分类。[selectedId] 只剩「定位高亮」一个用途：
 * 记一笔保存后把刚落库的那行摆到眼前（见 MainActivity.revealPendingRow）。
 */
class TransactionAdapter(
    private val onRowClick: (Transaction) -> Unit,
    private val onLongPress: (Transaction) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var entries: List<FlowList.Entry> = emptyList()

    /** 分类 id → 识别色。由外部注入（来自 classify_rules.json），本类不内置分类名单 */
    private var colorOf: (String) -> String = { "#8A8A82" }

    /** 兜底分类 id。判定「这笔算不算未分类」要用到，由外部注入（来自 classify_rules.json） */
    private var fallbackCategoryId: String = "other"

    private var selectedId: Long? = null

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.CHINA)

    fun submit(entries: List<FlowList.Entry>, colorOf: (String) -> String, fallbackCategoryId: String) {
        this.entries = entries
        this.colorOf = colorOf
        this.fallbackCategoryId = fallbackCategoryId
        notifyDataSetChanged()
    }

    /**
     * 切换选中行。同一 id 再传一次＝不变。
     *
     * 只刷新受影响的两格而不是整表：整表 notifyDataSetChanged 会让 RecyclerView
     * 重绑所有可见行，点一下闪一下，长时间看很累。
     */
    fun setSelected(id: Long?) {
        if (selectedId == id) return
        val old = positionOf(selectedId)
        val new = positionOf(id)
        selectedId = id
        if (old >= 0) notifyItemChanged(old)
        if (new >= 0) notifyItemChanged(new)
    }

    fun selectedId(): Long? = selectedId

    /** @return 该 id 的行在 entries 中的位置；不在列表里返回 -1 */
    fun positionOf(id: Long?): Int {
        id ?: return -1
        return entries.indexOfFirst { it is FlowList.Entry.Row && it.txn.id == id }
    }

    override fun getItemViewType(position: Int): Int =
        if (entries[position] is FlowList.Entry.DayHead) TYPE_HEAD else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEAD) {
            HeadHolder(ItemDayHeaderBinding.inflate(inflater, parent, false))
        } else {
            RowHolder(ItemTransactionBinding.inflate(inflater, parent, false))
        }
    }

    override fun getItemCount(): Int = entries.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val e = entries[position]) {
            is FlowList.Entry.DayHead -> (holder as HeadHolder).bind(e)
            is FlowList.Entry.Row -> (holder as RowHolder).bind(e.txn)
        }
    }

    private inner class HeadHolder(private val binding: ItemDayHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(head: FlowList.Entry.DayHead) {
            binding.tvDay.text = head.label
            binding.tvDaySum.text = buildString {
                append("支出 ¥").append(money(head.expense))
                if (head.income > 0.0) append(" · 收入 ¥").append(money(head.income))
            }
        }
    }

    private inner class RowHolder(private val binding: ItemTransactionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(txn: Transaction) = with(binding) {
            val manual = txn.sourceId.startsWith(MANUAL_PREFIX)
            tvTag.visibility = if (manual) View.VISIBLE else View.GONE

            val unknown =
                FlowList.isUnclassified(txn, MerchantNormalizer.UNKNOWN_MERCHANT, fallbackCategoryId)
            tvMerchant.text = if (unknown) MerchantNormalizer.UNKNOWN_MERCHANT else txn.merchant
            tvMerchant.setTextColor(
                root.context.getColor(if (unknown) R.color.warn else R.color.text_primary)
            )

            val selected = selectedId == txn.id

            // 无商户名的行给一层极浅底色：这是「该补商户名」的信号，
            // 也是通知监听那条路的天花板——用户得看得见它有多少
            rowContent.setBackgroundColor(
                root.context.getColor(
                    when {
                        selected -> R.color.row_selected
                        unknown -> R.color.uncat_bg
                        else -> R.color.surface
                    }
                )
            )
            vSelectBar.visibility = if (selected) View.VISIBLE else View.GONE

            vDot.background = dotDrawable(colorOf(txn.categoryId), hollow = unknown)

            // 用前缀判断，而不是把 "bill_xlsx" / "bill_csv" 再抄一遍：
            // 这个前缀是 MergeMatcher 判重的硬判据（决定宽松指纹那扇门给谁开），
            // 标签只是它的另一种读法。抄一份字面量就等于造出第四处定义，
            // 下次加新来源时必然再漂一次（2026-09-17 的 "csv" 就是这么留下来的死分支）。
            // 手动那笔的来源由 tvTag 单独标记，meta 里不再重复一遍「手动」
            val sourceLabel = when {
                manual -> ""
                txn.sourceId.startsWith(MergeMatcher.BILL_SOURCE_PREFIX) ->
                    root.context.getString(R.string.source_bill)
                txn.sourceId == SOURCE_WECHAT -> root.context.getString(R.string.source_wechat)
                txn.sourceId == SOURCE_ALIPAY -> root.context.getString(R.string.source_alipay)
                else -> root.context.getString(R.string.source_other)
            }
            tvMeta.text = buildString {
                append(timeFormat.format(Date(txn.occurredAt)))
                append(" · ").append(txn.categoryName)
                if (sourceLabel.isNotEmpty()) append(" · ").append(sourceLabel)
                if (unknown) append(" · ").append(root.context.getString(R.string.meta_fix_hint))
            }

            val expense = txn.direction == Direction.EXPENSE
            tvAmount.text = (if (expense) "-" else "+") + "¥" + money(txn.amount)
            tvAmount.setTextColor(
                root.context.getColor(if (expense) R.color.expense else R.color.income)
            )

            root.setOnClickListener { onRowClick(txn) }
            root.setOnLongClickListener { onLongPress(txn); true }
        }
    }

    private fun money(v: Double): String = String.format(Locale.US, "%.2f", v)

    /**
     * 分类圆点。无商户名时画成虚线空心圆——它不是某个分类，而是「分类还没定」，
     * 用实心色会让人误以为已经归好类了。
     */
    private fun dotDrawable(colorHex: String, hollow: Boolean): GradientDrawable {
        val color = runCatching { Color.parseColor(colorHex) }.getOrDefault(Color.GRAY)
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (hollow) {
                setColor(Color.TRANSPARENT)
                setStroke(2, Color.parseColor(CategoryBreakdown.UNCLASSIFIED_COLOR), 4f, 3f)
            } else {
                setColor(color)
            }
        }
    }

    companion object {
        private const val TYPE_HEAD = 0
        private const val TYPE_ROW = 1

        /**
         * 手动记账的来源前缀。判重语义：非 `bill_` 前缀 ⇒ 按「不完整来源」处理。
         *
         * 直接引用 [MergeMatcher.MANUAL_SOURCE_PREFIX]：这个前缀同时是 L2 宽松指纹
         * 的开闸判据，抄一份字面量就等于造出第二处定义——改了判重不改标签，
         * 行上的「手动」标记就会指向一批判重行为完全不同的记录。
         */
        const val MANUAL_PREFIX = MergeMatcher.MANUAL_SOURCE_PREFIX

        private const val SOURCE_WECHAT = "wechat"
        private const val SOURCE_ALIPAY = "alipay"
    }
}
