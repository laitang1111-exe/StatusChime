package com.demo.charge

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍常驻服务。
 *
 * 作用跟前台服务一样：撑着进程，让 BatteryWatcher 能一直收到电池广播。
 * 但它是由系统绑定的，优先级高于普通服务，而且【开机自动绑定】——
 * 用户授权一次，之后一直在，不需要 BOOT_COMPLETED。
 *
 * 它不读屏幕内容、不注入操作（见 res/xml/a11y_service.xml，canRetrieveWindowContent=false）。
 */
class ChargeA11yService : AccessibilityService() {

    companion object {
        private const val TAG = "ChargeSound"

        @Volatile
        var connected = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        SoundHub.load(this)
        BatteryWatcher.start(this)
        // 无障碍接上后，按「隐藏通知」决定前台服务要不要跑：
        //   隐藏通知开  → 撤掉兜底服务，回到零通知
        //   隐藏通知关  → 确保前台服务在跑（否则通知就永远不出现了）
        if (SoundHub.enabled(this)) {
            if (SoundHub.hideNotification(this)) {
                if (ChargeService.running) {
                    runCatching {
                        startService(Intent(this, ChargeService::class.java)
                            .setAction(ChargeService.ACTION_STOP))
                    }
                }
            } else if (!ChargeService.running) {
                Log.i(TAG, "a11y 连上，补起前台服务（隐藏通知=关）")
                val i = Intent(this, ChargeService::class.java)
                    .setAction(ChargeService.ACTION_START)
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                        startForegroundService(i) else startService(i)
                }
            }
        }
        Log.i(TAG, "a11y service connected, battery watcher attached")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不处理任何事件
    }

    override fun onInterrupt() {
        // 不需要中断处理
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = false
        BatteryWatcher.stop()
        Log.i(TAG, "a11y service unbound")
        startFallbackIfNeeded()
        return super.onUnbind(intent)
    }

    /**
     * 无障碍掉了 → 自动拉起前台服务兜底。
     * 这样「隐藏通知」不再是「放弃守护」，而是「平时零通知，降级时才出通知」。
     */
    private fun startFallbackIfNeeded() {
        if (!SoundHub.enabled(this)) return
        if (!SoundHub.hideNotification(this)) return
        if (ChargeService.running) return
        Log.i(TAG, "a11y 断开，拉起前台服务兜底")
        val i = Intent(this, ChargeService::class.java).setAction(ChargeService.ACTION_START)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                startForegroundService(i) else startService(i)
        }
    }

    override fun onDestroy() {
        connected = false
        BatteryWatcher.stop()
        Log.i(TAG, "a11y service destroyed")
        startFallbackIfNeeded()
        super.onDestroy()
    }
}
