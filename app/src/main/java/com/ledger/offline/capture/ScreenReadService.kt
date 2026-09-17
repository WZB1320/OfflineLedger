package com.ledger.offline.capture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.ledger.offline.core.ServiceLocator
import com.ledger.offline.parse.RuleStore
import com.ledger.offline.parse.TransactionParser

/**
 * 补充采集通道：读取账单 / 支付详情页面的文本。
 *
 * ⚠️ 这是「自用侧载」才能用的通道。
 * Google Play 对 Accessibility API 的用途审核极严，国内应用商店更严，
 * 一个财务记账 App 想带着无障碍权限上架基本不可能过审。
 *
 * 现实预期：微信和支付宝大量使用自绘控件，节点树里经常读不到文本，
 * 命中率远低于通知监听。所以它只是**补漏**，不是主力。
 *
 * 为了避免误抓和耗电，这里加了三道闸：
 *   1. 只在 微信 / 支付宝 前台时工作
 *   2. 只有页面出现明确的「支付成功 / 账单详情」类字样才尝试解析
 *   3. 同包名 1.5 秒内只处理一次
 */
class ScreenReadService : AccessibilityService() {

    private var lastPackage: String = ""
    private var lastHandledAt: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val pkg = e.packageName?.toString() ?: return
        val rule = runCatching { RuleStore.parserRules(this).sourceFor(pkg) }.getOrNull() ?: return

        val now = System.currentTimeMillis()
        if (pkg == lastPackage && now - lastHandledAt < 1_500L) return

        val root = rootInActiveWindow ?: return
        val page = collectText(root).joinToString(" ")
        if (page.isBlank()) return

        // 闸门 2：页面必须自证「这是一张支付结果 / 账单详情页」
        if (PAGE_MARKERS.none { page.contains(it) }) return

        lastPackage = pkg
        lastHandledAt = now

        val parsed = TransactionParser.parse(rule, e.className?.toString(), page, now) ?: return
        runCatching { ServiceLocator.persist(parsed, page) }
    }

    override fun onInterrupt() = Unit

    /** 深度优先收集页面上所有可见文本节点 */
    private fun collectText(node: AccessibilityNodeInfo?, sink: MutableList<String> = ArrayList()): List<String> {
        if (node == null) return sink
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { sink += it }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { sink += it }
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), sink)
        }
        return sink
    }

    companion object {
        private val PAGE_MARKERS = listOf(
            "支付成功", "付款成功", "收款成功", "账单详情", "交易详情",
            "交易记录", "已支付", "交易成功"
        )
    }
}
