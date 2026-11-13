package com.demo.charge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * 电池状态监听。
 *
 * 只注册一条 ACTION_BATTERY_CHANGED（它是 sticky 广播，
 * 注册瞬间就会回调一次当前状态，所以不用额外初始化）。
 *
 * 从这里派生 8 个事件里的 7 个（插电/拔电被无线充替换）：
 *
 *   插电       plugged 从 0 变成 AC/USB
 *   无线充     plugged 从 0 变成 WIRELESS
 *   拔电       plugged 从非 0 变成 0
 *   充满       STATUS == FULL（一次充电会话只响一次）
 *   过热       HEALTH == OVERHEAT（恢复后可以再次触发）
 *   低电量 21 / 11 / 3   放电时跨过阈值各响一次，同一轮放电不重复
 *
 * 用引用计数管理生命周期：前台服务和无障碍服务都可以 start，
 * 只有一个真的注册，两边都 stop 之后才注销。
 */
object BatteryWatcher {

    private const val TAG = "ChargeSound"
    private const val LOW_21 = 21
    private const val LOW_11 = 11
    private const val LOW_3 = 3

    private var refs = 0
    private var receiver: BroadcastReceiver? = null

    private var lastPlugged = -1
    private var fullFired = false
    private var overheatFired = false
    private var low21Fired = false
    private var low11Fired = false
    private var low3Fired = false

    @Synchronized
    fun start(ctx: Context) {
        refs++
        if (receiver != null) return
        val app = ctx.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent == null) return
                val ctx = c ?: app
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.i(TAG, "screen OFF")
                        SoundHub.onEvent(ctx, ChargeEvent.SCREEN_OFF)
                        return
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        Log.i(TAG, "user PRESENT（解锁）")
                        SoundHub.onEvent(ctx, ChargeEvent.UNLOCK)
                        return
                    }
                }
                runCatching { handle(ctx, intent) }
                    .onFailure { Log.w(TAG, "battery handle failed", it) }
            }
        }
        receiver = r
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        app.registerReceiver(r, filter)
        Log.i(TAG, "battery watcher started, refs=$refs")
    }

    @Synchronized
    fun stop() {
        refs--
        if (refs > 0) return
        refs = 0
        receiver?.let { runCatching { appCtx?.unregisterReceiver(it) } }
        receiver = null
        lastPlugged = -1
        fullFired = false
        overheatFired = false
        low21Fired = false
        low11Fired = false
        low3Fired = false
        Log.i(TAG, "battery watcher stopped")
    }

    @Volatile
    private var appCtx: Context? = null

    private fun handle(ctx: Context, intent: Intent) {
        appCtx = ctx.applicationContext

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)

        // 1) 插拔 / 无线充：只在「状态翻转」时触发
        if (lastPlugged != plugged) {
            val before = lastPlugged
            lastPlugged = plugged
            if (before != -1) {
                when {
                    plugged == 0 -> SoundHub.onEvent(ctx, ChargeEvent.PLUG_OUT, dedupe = false)
                    plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS ->
                        SoundHub.onEvent(ctx, ChargeEvent.WIRELESS, dedupe = false)
                    else -> SoundHub.onEvent(ctx, ChargeEvent.PLUG_IN, dedupe = false)
                }
            }
        }

        // 2) 充满：一次充电只响一次，拔电后重新武装
        if (status == BatteryManager.BATTERY_STATUS_FULL) {
            if (!fullFired) {
                fullFired = true
                Log.i(TAG, "battery FULL")
                SoundHub.onEvent(ctx, ChargeEvent.FULL)
            }
        } else if (plugged == 0) {
            fullFired = false
        }

        // 3) 过热：两条判据都算
        //    一、健康判定 HEALTH == OVERHEAT（系统自己认定）
        //    二、温度 >= 45℃（很多机型发热时 health 仍然是 GOOD，这一条兜底）
        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val tempC = if (tempTenths > 0) tempTenths / 10.0 else -1.0
        val tooHot = health == BatteryManager.BATTERY_HEALTH_OVERHEAT ||
                (tempC >= 45.0)
        if (tooHot) {
            if (!overheatFired) {
                overheatFired = true
                Log.i(TAG, "battery OVERHEAT health=$health temp=${tempC}C")
                SoundHub.onEvent(ctx, ChargeEvent.OVERHEAT)
            }
        } else if (health != -1 && tempC < 43.0) {
            // 回落到 43℃ 以下才重新武装，避免在 45℃ 边缘反复触发
            overheatFired = false
        }

        // 4) 低电量：放电时只触发「当前最低的那一档」，避免三声连响
        //
        // 重置条件只看「电量回升到 21% 以上」——
        // 不能只看「插上电就重置」，因为 dumpsys / 系统抖动时 plugged 会短暂跳变，
        // 那样同一档会反复触发（实测复现过）。
        if (pct > LOW_21) {
            low21Fired = false
            low11Fired = false
            low3Fired = false
        }
        if (plugged != 0) {
            // 充电中不提示低电量，但也不清标记（等电量真的涨上去再清）
        } else if (pct in 0..100) {
            when {
                pct <= LOW_3 -> if (!low3Fired) {
                    low3Fired = true
                    low21Fired = true
                    low11Fired = true
                    Log.i(TAG, "battery low 3%: $pct")
                    SoundHub.onEvent(ctx, ChargeEvent.LOW_3)
                }
                pct <= LOW_11 -> if (!low11Fired) {
                    low11Fired = true
                    low21Fired = true
                    Log.i(TAG, "battery low 11%: $pct")
                    SoundHub.onEvent(ctx, ChargeEvent.LOW_11)
                }
                pct <= LOW_21 -> if (!low21Fired) {
                    low21Fired = true
                    Log.i(TAG, "battery low 21%: $pct")
                    SoundHub.onEvent(ctx, ChargeEvent.LOW_21)
                }
            }
        }
    }

    /** 界面显示用：当前电量百分比 */
    fun levelPct(intent: Intent?): Int {
        val i = intent ?: return -1
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        return if (level >= 0 && scale > 0) level * 100 / scale else -1
    }
}
