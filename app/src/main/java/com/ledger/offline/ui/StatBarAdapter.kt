package com.ledger.offline.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import android.widget.LinearLayout
import com.ledger.offline.R
import com.ledger.offline.databinding.ItemStatBarBinding
import com.ledger.offline.data.CategoryBreakdown
import java.util.Locale

/** 统计页「分类占比」的条形。占比用 LinearLayout 权重表达，不做像素换算。 */
class StatBarAdapter : RecyclerView.Adapter<StatBarAdapter.Holder>() {

    private var slices: List<CategoryBreakdown.Slice> = emptyList()

    fun submit(list: List<CategoryBreakdown.Slice>) {
        slices = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemStatBarBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = slices.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(slices[position])

    class Holder(private val binding: ItemStatBarBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(slice: CategoryBreakdown.Slice) = with(binding) {
            tvName.text = slice.name
            tvAmount.text = "¥" + money(slice.amount)
            tvAmount.setTextColor(
                root.context.getColor(
                    if (slice.unclassified) R.color.warn else R.color.text_primary
                )
            )
            tvPct.text = String.format(Locale.US, "%.1f%%", slice.ratio * 100)

            // 未分类那一片用虚线空心圆，与流水列表里的记号保持一致
            vDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (slice.unclassified) {
                    setColor(Color.TRANSPARENT)
                    setStroke(2, Color.parseColor(CategoryBreakdown.UNCLASSIFIED_COLOR), 4f, 3f)
                } else {
                    setColor(
                        runCatching { Color.parseColor(slice.colorHex) }.getOrDefault(Color.GRAY)
                    )
                }
            }

            val fill = GradientDrawable().apply {
                setColor(
                    runCatching { Color.parseColor(slice.colorHex) }.getOrDefault(Color.GRAY)
                )
                cornerRadius = 4f * root.resources.displayMetrics.density
            }
            vFill.background = fill

            // 权重 = 比例；剩余部分留空。比例为 0 时给一个极小的权重，避免条形完全消失
            val ratio = slice.ratio.coerceIn(0.0, 1.0)
            vFill.layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, ratio.toFloat())
            vRest.layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, (1.0 - ratio).toFloat()
            )
        }

        private fun money(v: Double): String = String.format(Locale.US, "%.2f", v)
    }
}
