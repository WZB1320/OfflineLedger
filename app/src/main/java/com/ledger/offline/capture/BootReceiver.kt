package com.ledger.offline.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ledger.offline.R

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

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText("已恢复运行，继续监听支付通知")
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        runCatching { manager.notify(1001, notification) }
    }
}
