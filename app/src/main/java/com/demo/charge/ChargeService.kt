package com.demo.charge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * 常驻前台服务：撑住进程，让 BatteryWatcher 的广播能一直收到。
 *
 * 事件本身由 BatteryWatcher 统一分发（插拔/无线充/充满/过热/低电量），
 * 这里只负责三件事：
 *   一、startForeground（否则后台活不久）
 *   二、BatteryWatcher.start / stop（引用计数）
 *   三、开机自启时被 BootReceiver 拉起来
 */
class ChargeService : Service() {

    companion object {
        private const val TAG = "ChargeSound"
        private const val CHANNEL_ID = "charge_sound"
        // 通道重要性创建后 App 改不了（旧的 charge_sound_hidden 已经被建成 LOW），
        // 所以换一个新 id，保证 IMPORTANCE_MIN 真的生效
        private const val CHANNEL_HIDDEN = "charge_sound_min_v2"
        private const val NOTIF_ID = 71

        const val ACTION_START = "com.demo.charge.START"
        const val ACTION_STOP = "com.demo.charge.STOP"

        @Volatile
        var running = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                teardown()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundNotif()
                setup()
                return START_STICKY
            }
        }
    }

    private fun startForegroundNotif() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // 通知的「显不显示」和「跑不跑前台服务」是两件事：
        //   hideNotification=true  → IMPORTANCE_MIN：折叠、静音、无状态栏图标
        //   hideNotification=false → IMPORTANCE_LOW：正常显示
        //
        // 说明：Android 不允许 App 自己把通道设成 IMPORTANCE_NONE
        // （实测 dumpsys 里 mImportance=2，我们请求的 NONE 被系统夹到 LOW），
        // 所以「前台服务 + 完全零通知」在平台层面做不到，
        // 能压到的最小程度就是 MIN —— 状态栏没有图标，通知栏里一条折叠的静默项。
        val hide = SoundHub.hideNotification(this)
        val chId = if (hide) CHANNEL_HIDDEN else CHANNEL_ID
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val importance = if (hide) NotificationManager.IMPORTANCE_MIN
                             else NotificationManager.IMPORTANCE_LOW
            val ch = NotificationChannel(chId, "充电提示音", importance).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            nm.createNotificationChannel(ch)
        }
        val n: Notification = Notification.Builder(this, chId)
            .setContentTitle(SoundHub.notifTitle(this))
            .setContentText(SoundHub.notifText(this))
            .setSmallIcon(SoundHub.notifIconRes(this))
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
        startForeground(NOTIF_ID, n)
    }

    /** 点通知 → 打开主界面 */
    private fun openAppIntent(): android.app.PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        else android.app.PendingIntent.FLAG_UPDATE_CURRENT
        return android.app.PendingIntent.getActivity(this, 0, i, flags)
    }

    private fun setup() {
        if (running) return
        SoundHub.load(this)
        BatteryWatcher.start(this)
        running = true
        Log.i(TAG, "service started, battery watcher attached")
    }

    private fun teardown() {
        if (running) {
            BatteryWatcher.stop()
            running = false
        }
        // 关键：stopSelf 不会自动撤掉 startForeground 挂上去的那条通知，
        // 必须显式 stopForeground(REMOVE) + cancel，否则隐蔽模式还会留一条
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NOTIF_ID)
        }
        Log.i(TAG, "service stopped")
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }
}
