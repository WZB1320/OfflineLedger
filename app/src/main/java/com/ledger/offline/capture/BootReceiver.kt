package com.ledger.offline.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.ledger.offline.R
import com.ledger.offline.ui.MainActivity

/**
 * 重启后自动把通知监听服务拉起来。
 *
 * 为什么需要：NotificationListenerService 的「授权」在系统里是持久的，
 * 但服务实例在重启后不一定被立即绑定。用户不会记得每次重启都去手动开一次，
 * 所以这里发一条本地通知提醒（这也是我们申请 POST_NOTIFICATIONS 的唯一用途）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // 没授权通知使用权的话，发这条提醒毫无意义（服务本来就不会工作），
        // 只会给用户添一条无法关闭的噪声通知
        val granted = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        if (!granted) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channelId = "ledger_status"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "运行状态",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "记账服务状态提示" }
            )
        }

        // 这里刻意不说「已恢复运行」——接收器只能知道「开机了」，
        // 无法确认监听服务是否真的被系统绑上。说死了就是在骗用户，
        // 而这类谎话的代价是：用户以为在自动记账，两个月后对账才发现全是空的。
        val tap = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("已随开机启动。若长时间没有自动记账，点开应用查看采集状态")
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        runCatching { manager.notify(1001, notification) }
    }
}
