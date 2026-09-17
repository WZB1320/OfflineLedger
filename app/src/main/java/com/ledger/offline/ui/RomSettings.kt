package com.ledger.offline.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 跳系统「自启动 / 后台运行 / 电池」管理页的引导。
 *
 * ## 为什么必须有这一步
 * 通知监听服务能不能活着，不取决于我们的代码，取决于 ROM 的后台策略。
 * 国产 ROM 默认会把这类服务在息屏一段时间后清掉，清掉之后不会再自动拉起，
 * 期间用户的所有支付都不会被记录，而界面上看不出任何异常。
 *
 * 这是方案 §1.3 里明写的天花板（「国产 ROM 会杀后台，需一次性白名单配置」），
 * 唯一能做的就是**把用户送到正确的设置页**——让用户自己在系统设置里翻三层菜单，
 * 大多数人是不会去做的。
 *
 * ## 各家的坑
 * 自启动管理页是厂商私有实现，**没有统一 Action**，只能按包名/类名逐个试。
 * 因此这里列的是「候选清单」而非「确定答案」：
 * 先 resolveActivity 确认目标存在，再 startActivity；任何一步失败就试下一个，
 * 全部失败时退回到 `应用详情页`（这是 AOSP 标准页，一定有），
 * 用户仍能从「电池 / 权限」里找到同样的开关。
 * 不做「猜中才有用」的设计，保证任何机型上点了都有反馈。
 */
object RomSettings {

    /** 厂商自启动管理页候选。顺序 = 常见度，命中即止 */
    private val AUTOSTART_PAGES = listOf(
        // 小米 / 红米
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity"
        ),
        // 华为 / 荣耀
        ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
        ),
        ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.optimize.process.ProtectActivity"
        ),
        // OPPO / 一加
        ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
        ),
        ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.startupapp.StartupAppListActivity"
        ),
        ComponentName(
            "com.oneplus.security",
            "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
        ),
        // vivo / iQOO
        ComponentName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        ),
        ComponentName(
            "com.iqoo.secure",
            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
        ),
        // 三星
        ComponentName(
            "com.samsung.android.lool",
            "com.samsung.android.sm.ui.battery.BatteryActivity"
        ),
        // 联想 / 中兴等常见备用入口
        ComponentName(
            "com.lenovo.security",
            "com.lenovo.security.purebackground.PureBackgroundActivity"
        )
    )

    /**
     * 打开后台白名单设置页。
     * @return true = 打开了厂商自启动页；false = 厂商页都不在，已退到应用详情页
     */
    fun openBackgroundWhitelist(context: Context): Boolean {
        for (page in AUTOSTART_PAGES) {
            val intent = Intent().setComponent(page).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // resolveActivity 必须先做：不检查就 startActivity，
            // 在没装该组件的机型上会抛 ActivityNotFoundException；
            // 有些 ROM 甚至把组件声明了却禁止外部启动，所以 start 也要 runCatching
            if (context.packageManager.resolveActivity(intent, 0) == null) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        openAppDetails(context)
        return false
    }

    /** 应用详情页：AOSP 标准页，任何机型都有。自启动入口找不到时的兜底 */
    fun openAppDetails(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }
}
