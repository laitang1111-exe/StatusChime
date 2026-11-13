package com.demo.charge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 开机自启。
 *
 * 为什么只拉起前台服务、不管无障碍：
 *   无障碍服务由系统自己在开机时重新绑定，不需要我们插手；
 *   前台服务必须有人点一下才能起来，所以需要一个 BOOT_COMPLETED 接收器。
 *
 * 为什么要判断 bootEnabled：
 *   用户关掉监听之后，开机就不该再自己起来，
 *   所以「是否自启」跟着前台服务的开关走。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context?, intent: Intent?) {
        val c = ctx ?: return
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        if (!SoundHub.enabled(c)) {
            Log.i("ChargeSound", "boot: 自启开关是关的，跳过")
            return
        }
        if (SoundHub.hideNotification(c)) {
            Log.i("ChargeSound", "boot: 隐藏通知模式，不拉前台服务（靠无障碍常驻）")
            return
        }
        Log.i("ChargeSound", "boot: 拉起前台服务")
        val i = Intent(c, ChargeService::class.java).setAction(ChargeService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { c.startForegroundService(i) }
        } else {
            runCatching { c.startService(i) }
        }
    }
}
