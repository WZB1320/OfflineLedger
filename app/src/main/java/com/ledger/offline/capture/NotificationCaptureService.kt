package com.ledger.offline.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ledger.offline.core.ServiceLocator
import com.ledger.offline.parse.RuleStore
import com.ledger.offline.parse.TransactionParser

/**
 * 主力采集通道：被动旁听支付通知。
 *
 * 工作方式：微信 / 支付宝在支付成功后通常会推一条通知，
 * 里面的标题、正文里就带着金额和收款方。这个服务被系统回调时读一眼文本即可。
 * 不 hook、不注入、不修改对方进程，只是「听见」系统本来就发给用户的通知。
 *
 * 已知局限（务必知悉，这不是 bug 而是方案天花板）：
 *  1. 用户关掉微信/支付宝的通知 → 完全抓不到
 *  2. 只存在于通知出现的那一刻 → 无法回溯历史（历史要靠 CSV 导入补）
 *  3. 部分国产 ROM 会限制后台服务存活 → 需要引导用户加白名单
 *  4. 微信/支付宝改通知文案 → 需要更新 parser_rules.json
 *
 * 这里刻意做了「包名早筛」：只有微信 / 支付宝的通知才会走到正则解析，
 * 其它 App 的通知连字符串拼接都不做，省电。
 */
class NotificationCaptureService : NotificationListenerService() {

    @Volatile
    private var lastSignature: String = ""
    @Volatile
    private var lastSignatureAt: Long = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        // 预热解析规则，避免第一条通知进来时才做 IO
        runCatching { RuleStore.parserRules(this) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        val pkg = notification.packageName ?: return

        val rule = runCatching { RuleStore.parserRules(this).sourceFor(pkg) }.getOrNull() ?: return

        val extras = notification.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = buildString {
            extras.getCharSequence(Notification.EXTRA_TEXT)?.let { append(it) }
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let {
                if (isNotEmpty()) append(' ')
                append(it)
            }
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach {
                if (isNotEmpty()) append(' ')
                append(it)
            }
        }.trim()

        if (title.isNullOrBlank() && text.isBlank()) return

        // 微信会为了刷新时间反复重发同一条通知，这里做一次轻量短路，
        // 避免每条重发都走一遍正则。
        val signature = "$pkg|$title|$text"
        val now = System.currentTimeMillis()
        if (signature == lastSignature && now - lastSignatureAt < 10_000L) return
        lastSignature = signature
        lastSignatureAt = now

        val occurredAt = if (notification.postTime > 0) notification.postTime else now
        val parsed = TransactionParser.parse(rule, title, text, occurredAt) ?: return

        runCatching { ServiceLocator.persist(parsed, "$title $text") }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // 系统可能会在内存吃紧时断开监听，尝试请求重连
        runCatching { requestRebind(android.content.ComponentName(this, NotificationCaptureService::class.java)) }
    }
}
