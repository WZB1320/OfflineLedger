package com.ledger.offline.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ledger.offline.R
import com.ledger.offline.databinding.ItemCategoryBinding
import com.ledger.offline.parse.CategoryRule

/**
 * 分类格子。记一笔的分类网格（6 列）与分类修正弹层（4 列）共用，
 * 只是外层给的 LayoutManager 列数不同。
 *
 * 分类名单由外部传入（来自 classify_rules.json）——这里不内置任何分类，
 * 加分类只改规则文件，UI 自动跟上。
 */
class CategoryGridAdapter(
    private var items: List<CategoryRule>,
    private val onPick: (CategoryRule) -> Unit
) : RecyclerView.Adapter<CategoryGridAdapter.Holder>() {

    private var selectedId: String = items.firstOrNull()?.id.orEmpty()

    fun selected(): CategoryRule? = items.firstOrNull { it.id == selectedId }

    /**
     * 整体替换条目（两级联动时二级网格随一级切换）。
     * 选中项不在新列表里时落到首项，保持「永远有一个选中」的交互约定。
     */
    fun replaceItems(next: List<CategoryRule>) {
        items = next
        if (items.none { it.id == selectedId }) {
            selectedId = items.firstOrNull()?.id.orEmpty()
        }
        notifyDataSetChanged()
    }

    fun select(id: String) {
        val old = items.indexOfFirst { it.id == selectedId }
        val new = items.indexOfFirst { it.id == id }
        if (new < 0) return
        selectedId = id
        if (old >= 0) notifyItemChanged(old)
        notifyItemChanged(new)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemCategoryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val binding: ItemCategoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(rule: CategoryRule) = with(binding) {
            tvName.text = rule.name
            vDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    runCatching { Color.parseColor(rule.color) }
                        .getOrDefault(Color.parseColor(CategoryRule.DEFAULT_FALLBACK_COLOR))
                )
            }

            val on = rule.id == selectedId
            val dp = root.resources.displayMetrics.density
            catRoot.background = GradientDrawable().apply {
                cornerRadius = 8f * dp
                if (on) {
                    setColor(root.context.getColor(R.color.brand_soft))
                    setStroke((1 * dp).toInt(), root.context.getColor(R.color.brand))
                } else {
                    setColor(root.context.getColor(R.color.surface))
                    setStroke((1 * dp).toInt(), root.context.getColor(R.color.divider))
                }
            }
            tvName.setTextColor(
                root.context.getColor(if (on) R.color.brand else R.color.text_primary)
            )

            root.setOnClickListener {
                select(rule.id)
                onPick(rule)
            }
        }
    }
}
