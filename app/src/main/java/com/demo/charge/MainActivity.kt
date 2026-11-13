package com.demo.charge

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 主界面：
 *   状态面板（电量 / 前后台 / 无障碍 / 通道 / 8 个事件的铃声）
 *   音量通道切换
 *   8 个事件的试听按钮：点一下试听，长按换铃声
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var master: Switch
    private lateinit var channelBtn: Button
    private lateinit var a11yBtn: Button
    private lateinit var hideRecentsSwitch: Switch
    private lateinit var hideNotifSwitch: Switch
    private val eventButtons = HashMap<ChargeEvent, Button>()
    private val eventSwitches = HashMap<ChargeEvent, Switch>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 正在跳转到「自己的」界面（铃声筛选页）时为 true。
     *
     * 隐蔽模式会在「用户离开」时 finishAndRemoveTask()，
     * 但启动自己的子界面也会被算作离开 —— 结果子界面刚起来就被连坐干掉，
     * 表现就是长按后「立即闪退」。用这个标记把这种情况排除掉。
     */
    private var internalNavigation = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SoundHub.load(this)
        setContentView(buildUi())
        askNotif()
        // 进来就对一次状态：确保「总开关 / 隐藏后台 / 隐藏通知」的实际行为和界面一致
        status.postDelayed({ applyServiceState() }, 300)
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onResume() {
        super.onResume()
        internalNavigation = false
        refresh()
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1000L)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    /**
     * 隐蔽模式下，离开界面时把任务从「最近任务」里移除。
     *
     * 单靠 manifest 的 excludeFromRecents 在部分 ROM（含 MIUI）上对
     * adb/launcher 启动的任务不生效，实测卡片还在，
     * 所以再加一手 finishAndRemoveTask()，保证卡片立刻消失。
     */
    override fun onStop() {
        hideTaskIfHidden()
        super.onStop()
    }

    /**
     * 用户按 HOME / 最近任务键时会先回调这里。
     *
     * 只写在 onStop 里不够快 —— 按「最近任务」的瞬间，
     * 卡片已经被系统截了一张快照，看起来像「没隐藏」。
     * 所以这里也调一次，让任务在进入后台的第一步就被移除。
     */
    override fun onUserLeaveHint() {
        hideTaskIfHidden()
        super.onUserLeaveHint()
    }

    private fun hideTaskIfHidden() {
        if (internalNavigation) return          // 是自己的子界面，不要连坐
        if (!SoundHub.hideRecents(this)) return
        if (isChangingConfigurations) return
        runCatching { finishAndRemoveTask() }
    }

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
        }

        root.addView(TextView(this).apply {
            text = "充电提示音"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        })

        root.addView(TextView(this).apply {
            text = "插拔 / 充满 / 过热 / 无线充 / 低电量，都能各自配一个声音。"
            setTextColor(Color.parseColor("#8FA3B8"))
            textSize = 13f
            setPadding(0, dp(6), 0, dp(16))
        })

        status = TextView(this).apply {
            setTextColor(Color.parseColor("#7CFFB2"))
            textSize = 14f
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.parseColor("#1A2634"))
        }
        root.addView(status)

        master = Switch(this).apply {
            text = "开启前台监听（含开机自启）"
            setPadding(0, dp(18), 0, dp(6))
            setOnCheckedChangeListener { _, on ->
                SoundHub.setEnabled(this@MainActivity, on)
                SoundHub.setBootEnabled(this@MainActivity, on)
                applyServiceState()
                status.postDelayed({ refresh() }, 300)
            }
        }
        root.addView(master)

        hideRecentsSwitch = Switch(this).apply {
            text = "隐藏后台（离开时从最近任务移除）"
            setPadding(0, dp(14), 0, dp(2))
            setOnCheckedChangeListener { _, on ->
                SoundHub.setHideRecents(this@MainActivity, on)
                status.postDelayed({ refresh() }, 300)
            }
        }
        root.addView(hideRecentsSwitch)

        hideNotifSwitch = Switch(this).apply {
            text = "隐藏通知（不跑前台服务，靠无障碍常驻）"
            setPadding(0, dp(6), 0, dp(6))
            setOnCheckedChangeListener { _, on ->
                SoundHub.setHideNotification(this@MainActivity, on)
                // 换通道必须重启服务，通道重要性创建后不能改
                if (ChargeService.running) {
                    stopServiceNow()
                    status.postDelayed({ applyServiceState() }, 500)
                }
                status.postDelayed({ refresh() }, 800)
            }
        }
        root.addView(hideNotifSwitch)

        channelBtn = Button(this).apply {
            setOnClickListener {
                val next = SoundHub.nextChannel(SoundHub.channel)
                SoundHub.setChannel(this@MainActivity, next)
                refresh()
                Toast.makeText(this@MainActivity,
                    "已切到「" + SoundHub.nameOf(next) + "」", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(channelBtn)

        root.addView(TextView(this).apply {
            text = "各事件的铃声（点一下试听，长按换）"
            setTextColor(Color.parseColor("#8FA3B8"))
            textSize = 12f
            setPadding(0, dp(16), 0, dp(6))
        })

        for (e in ChargeEvent.values()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val b = Button(this).apply {
                setOnClickListener {
                    SoundHub.onEvent(this@MainActivity, e, dedupe = false)
                }
                setOnLongClickListener {
                    internalNavigation = true
                    startActivityForResult(
                        Intent(this@MainActivity, TonePickerActivity::class.java)
                            .putExtra(TonePickerActivity.EXTRA_EVENT, e.id),
                        100 + e.ordinal
                    )
                    true
                }
            }
            val sw = Switch(this).apply {
                setOnCheckedChangeListener { _, on ->
                    SoundHub.setEventEnabled(this@MainActivity, e, on)
                }
            }
            eventButtons[e] = b
            eventSwitches[e] = sw
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(sw)
            root.addView(row)
        }

        root.addView(TextView(this).apply {
            text = "通知标题 / 内容（前台服务那条通知显示的文字）"
            setTextColor(Color.parseColor("#8FA3B8"))
            textSize = 12f
            setPadding(0, dp(16), 0, dp(4))
        })

        val titleBox = android.widget.EditText(this).apply {
            setText(SoundHub.notifTitle(this@MainActivity))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val textBox = android.widget.EditText(this).apply {
            setText(SoundHub.notifText(this@MainActivity))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val saveNotif = Runnable {
            SoundHub.setNotifContent(this@MainActivity,
                titleBox.text.toString(), textBox.text.toString())
            // 服务在跑的话重启一下，让通知文字立刻更新
            if (ChargeService.running) {
                stopServiceNow()
                status.postDelayed({ applyServiceState() }, 400)
            }
        }
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                handler.removeCallbacks(saveNotif)
                handler.postDelayed(saveNotif, 800)
            }
        }
        titleBox.addTextChangedListener(watcher)
        textBox.addTextChangedListener(watcher)
        root.addView(titleBox)
        root.addView(textBox)

        a11yBtn = Button(this).apply {
            setOnClickListener {
                Toast.makeText(this@MainActivity,
                    "在列表里找到「充电提示音（常驻）」并打开", Toast.LENGTH_LONG).show()
                internalNavigation = true
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
        }
        root.addView(a11yBtn)

        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B1118"))
            addView(root)
        }
    }

    private fun refresh() {
        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager
        val pluggedType = runCatching {
            registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        }.getOrDefault(0)
        val pluggedText = when (pluggedType) {
            BatteryManager.BATTERY_PLUGGED_AC -> "已插电（AC）"
            BatteryManager.BATTERY_PLUGGED_USB -> "已插电（USB）"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线充电中"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "底座充电中"
            else -> "未插电"
        }
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

        status.text = buildString {
            append("电量      ").append(if (pct in 0..100) "$pct%" else "未知").append('\n')
            append("电源      ").append(pluggedText).append('\n')
            append("运行方式  ").append(
                when {
                    !SoundHub.enabled(this@MainActivity) -> "总开关已关"
                    ChargeService.running -> "前台服务 + 无障碍"
                    ChargeA11yService.connected -> "仅无障碍（无通知）"
                    else -> "无载体（需开无障碍）"
                }
            ).append('\n')
            append("前台监听  ").append(if (ChargeService.running) "运行中" else "已停止").append('\n')
            append("隐藏后台  ").append(if (SoundHub.hideRecents(this@MainActivity)) "开" else "关").append('\n')
            append("隐藏通知  ").append(if (SoundHub.hideNotification(this@MainActivity)) "开" else "关").append('\n')
            append("无障碍    ").append(if (ChargeA11yService.connected) "已连接" else "未开启").append('\n')
            append("开机自启  ").append(if (SoundHub.bootEnabled(this@MainActivity)) "开" else "关").append('\n')
            append("耳机      ").append(
                if (SoundHub.isHeadsetConnected()) "已连接（原声 20%）" else "未连接（按通道补偿）"
            ).append('\n')
            append("插电增幅  ").append(SoundHub.boostText(ChargeEvent.PLUG_IN)).append('\n')
            append("音量通道  ").append(SoundHub.nameOf(SoundHub.channel)).append('\n')
            append("模式      ").append(if (SoundHub.isSilent(this@MainActivity)) "静音（改振动）" else "出声")
        }

        master.isChecked = SoundHub.enabled(this@MainActivity)
        hideRecentsSwitch.isChecked = SoundHub.hideRecents(this@MainActivity)
        hideNotifSwitch.isChecked = SoundHub.hideNotification(this@MainActivity)
        channelBtn.text = "音量通道：" + SoundHub.nameOf(SoundHub.channel) + "（点击切换）"
        a11yBtn.text = if (ChargeA11yService.connected)
            "无障碍常驻：已开启（点击查看）" else "开启无障碍常驻（开机自动生效）"

        for ((e, b) in eventButtons) {
            val custom = SoundHub.customUri(this, e) != null
            b.text = e.label + "（" + (if (custom) "自定义" else "内置") + "）"
        }
        for ((e, sw) in eventSwitches) {
            val on = SoundHub.eventEnabled(this, e)
            if (sw.isChecked != on) sw.isChecked = on
        }
    }

    /**
     * 按「总开关 + 隐蔽模式」决定前台服务要不要跑。
     *
     *   总开关关          →  服务停，什么都不响
     *   总开关开 + 隐蔽    →  服务停（只靠无障碍常驻，没有通知、没有后台卡片）
     *   总开关开 + 非隐蔽  →  前台服务跑（有常驻通知）
     */
    private fun applyServiceState() {
        val on = SoundHub.enabled(this)
        val hideNotif = SoundHub.hideNotification(this)
        // 「隐藏通知」的语义：
        //   无障碍在 → 不跑前台服务（零通知）
        //   无障碍不在 → 自动拉前台服务兜底（此时会出现一条通知，这是降级信号）
        val needFgs = on && (!hideNotif || !ChargeA11yService.connected)
        if (needFgs) {
            // 无条件再发一次 start：startService 是幂等的，
            // 已经在跑就只是刷新一次通知。原来判断 running 会漏启动
            // （刚 stop 还没回调 teardown 时 running 仍是 true）。
            startServiceNow()
        } else {
            if (ChargeService.running) stopServiceNow()
        }
    }

    private fun startServiceNow() {
        val i = Intent(this, ChargeService::class.java).setAction(ChargeService.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
    }

    private fun stopServiceNow() {
        startService(Intent(this, ChargeService::class.java).setAction(ChargeService.ACTION_STOP))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val idx = requestCode - 100
        val events = ChargeEvent.values()
        if (idx !in events.indices) return
        val uriStr = data?.getStringExtra(TonePickerActivity.RESULT_URI) ?: return
        val uri = android.net.Uri.parse(uriStr)
        SoundHub.setCustomUri(this, events[idx], uri)
        Toast.makeText(this, "已设置「" + events[idx].label + "」", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun askNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runCatching {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 11)
                }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
