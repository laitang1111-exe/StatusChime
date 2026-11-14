package com.demo.charge

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import java.io.File

/**
 * 提示音引擎 + 全局去重 + 设置持久化。
 *
 * 事件从「插电/拔电两种」扩到 8 种（见 ChargeEvent），
 * 每个事件可以各自指定一个铃声，没指定就用 res/raw 里的默认音效。
 *
 * 声音统一走 MediaPlayer（内置音效和用户选的铃声都是这一条路），
 * 因为只有 MediaPlayer 能拿到 audioSessionId，进而挂 LoudnessEnhancer 做响度补偿。
 */
object SoundHub {

    private const val TAG = "ChargeSound"
    private const val PREFS = "charge_sound"

    // 编号保持不变（老偏好里存的是 0/1/2），只新增 RING = 3
    const val CHANNEL_NOTIFICATION = 0
    const val CHANNEL_MEDIA = 1
    const val CHANNEL_ALARM = 2
    const val CHANNEL_RING = 3

    /** 界面显示顺序：通知 → 铃声 → 媒体 → 闹钟 */
    private val CHANNEL_ORDER = intArrayOf(
        CHANNEL_NOTIFICATION, CHANNEL_RING, CHANNEL_MEDIA, CHANNEL_ALARM
    )

    /** 同一事件的去重窗口：低电量/过热这类给长一点，避免系统抖动连响 */
    private fun dedupeWindowMs(e: ChargeEvent): Long = when (e) {
        ChargeEvent.LOW_3, ChargeEvent.LOW_11, ChargeEvent.LOW_21,
        ChargeEvent.OVERHEAT, ChargeEvent.FULL -> 5000L
        else -> 800L
    }

    @Volatile var channel: Int = CHANNEL_NOTIFICATION
    private var lastEvent = ""
    private var lastEventAt = 0L

    private var player: MediaPlayer? = null

    /**
     * 熄屏音 / 解锁音走 SoundPool（预加载，延迟几毫秒）。
     *
     * 王桑 2026-11-08 说这两个事件必须用预加载：
     * 熄屏、解锁都是「一按就要有反应」的动作，MediaPlayer 每次
     * setDataSource + prepare + start 要 20 毫秒以上，听起来会慢半拍。
     * 这两个事件本来就没有响度补偿（boost=0），所以不需要 LoudnessEnhancer，
     * 用 SoundPool 没有损失。
     */
    private var pool: SoundPool? = null
    private val poolLoaded = HashMap<Int, Boolean>()
    private var spScreenOff = 0
    private var spUnlock = 0

    /** 自定义铃声的预加载样本（key = 文件绝对路径） */
    private val customPoolIds = HashMap<String, Int>()

    private fun poolIdForPath(ctx: Context, path: String): Int {
        customPoolIds[path]?.let { return it }
        val sp = pool ?: return 0
        val id = runCatching { sp.load(path, 1) }.getOrDefault(0)
        if (id != 0) {
            customPoolIds[path] = id
            Log.i(TAG, "SoundPool 预加载自定义铃声 $path -> id=$id")
        }
        return id
    }

    /** 这两个事件走预加载通道 */
    private fun isPreloaded(e: ChargeEvent?): Boolean =
        e == ChargeEvent.SCREEN_OFF || e == ChargeEvent.UNLOCK
    private var deviceCallback: AudioDeviceCallback? = null

    /** 当前有没有接着耳机（有线 / 蓝牙 / USB / 助听器） */
    @Volatile private var headsetConnected = false
    @Volatile private var headsetKnown = false
    private var enhancer: LoudnessEnhancer? = null

    private fun nameOfValue(ch: Int): String = when (ch) {
        CHANNEL_RING -> "铃声音量"
        CHANNEL_MEDIA -> "媒体音量"
        CHANNEL_ALARM -> "闹钟音量"
        else -> "通知音量"
    }

    val CHANNEL_NAMES: Array<String> = CHANNEL_ORDER.map { nameOfValue(it) }.toTypedArray()

    fun nameOf(ch: Int): String = nameOfValue(ch)

    fun nextChannel(ch: Int): Int {
        val idx = CHANNEL_ORDER.indexOf(ch).let { if (it < 0) 0 else it }
        return CHANNEL_ORDER[(idx + 1) % CHANNEL_ORDER.size]
    }

    /**
     * 响度补偿，单位 mB（100 mB = 1 dB），按「通道 + 事件」两维决定。
     *
     * 王桑 2026-11-08：
     *   「当铃声通道时，插电音增幅保持 5x，其他提示音为 3x」
     *
     *   5x 线性 = 20*log10(5) = 13.98 dB  → 1398 mB（取 1400）
     *   3x 线性 = 20*log10(3) =  9.54 dB  →  954 mB
     *
     * 通知通道和铃声通道在系统里是同一种「15 档」的流，听感也接近，
     * 所以这两档套同一套规则；媒体本来最响，不补；闹钟给一点点点缀。
     */
    /**
     * 戴耳机时的音量系数。
     *
     * 王桑 2026-11-08：「20%」
     * 20% 线性 = -13.98 dB。LoudnessEnhancer 只能加不能减，
     * 所以衰减走 MediaPlayer.setVolume（它就是线性增益）。
     */
    private fun volumeGain(): Float = if (headsetConnected) 0.2f else 1.0f

    /**
     * 单个事件的基础音量系数。
     *
     * 王桑 2026-11-08：「低电量 3% 的基础音量改为 50%，就是削减一半」
     * 50% 线性 = -6.02 dB。这个系数和「耳机 20%」是相乘关系：
     *   不戴耳机  → 0.5
     *   戴耳机    → 0.2 × 0.5 = 0.1（-20 dB）
     */
    private fun eventVolumeGain(e: ChargeEvent?): Float = when (e) {
        ChargeEvent.LOW_3 -> 0.5f
        else -> 1.0f
    }

    private fun boostMbFor(ch: Int, e: ChargeEvent?): Int {
        // 戴耳机：不放大，同时把音量压到一半（见 volumeGain）
        if (headsetConnected) return 0
        return when (ch) {
            CHANNEL_MEDIA -> 0
            CHANNEL_ALARM -> 400          // ≈ 1.6x
            // 王桑 2026-11-08：铃声通道下，除「插电音」以外的全部改成 1x（不放大）
            CHANNEL_RING -> when (e) {
                ChargeEvent.PLUG_IN -> 1400   // 5x
                // 王桑 2026-11-08：低电量 21% 给 1.5x
                //   1.5x 线性 = 20*log10(1.5) = 3.52 dB → 352 mB
                ChargeEvent.LOW_21 -> 352
                else -> 0                     // 1x：什么都不加
            }
            // 通知通道暂时保持 3x，等王桑确认要不要跟铃声同步
            CHANNEL_NOTIFICATION -> when (e) {
                ChargeEvent.PLUG_IN -> 1400   // 5x
                null -> 0
                else -> 954                   // 3x
            }
            else -> 0
        }
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        ensureDeviceWatcher(ctx)
        ensurePool(ctx)
        val p = prefs(ctx)
        channel = p.getInt("channel", CHANNEL_NOTIFICATION)

        // 旧版本把自定义铃声存在 uri_in / uri_out 两个键里，
        // 新版按事件存（uri_plug_in / uri_plug_out ...）。
        // 这里做一次迁移，否则升级之后原来的铃声看起来就「丢了」。
        migrateLegacy(ctx, p, "uri_in", ChargeEvent.PLUG_IN)
        migrateLegacy(ctx, p, "uri_out", ChargeEvent.PLUG_OUT)

        // 把所有自定义音频复制进 App 私有目录：
        //   files/tones/<事件>.<扩展名>
        // 这样即使外部文件被删、MediaStore 重新编号，升级后也还在。
        for (e in ChargeEvent.values()) {
            repairBinName(ctx, e)
            ensureLocalCopy(ctx, e)
        }
    }

    private fun migrateLegacy(ctx: Context, p: SharedPreferences, oldKey: String, e: ChargeEvent) {
        val newKey = "uri_" + e.id
        val old = p.getString(oldKey, null) ?: return
        if (p.getString(newKey, null) != null) return
        val uri = runCatching { Uri.parse(old) }.getOrNull() ?: return
        val local = copyIntoTones(ctx, e, uri)
        val saved = local ?: old
        p.edit().putString(newKey, saved.toString()).apply()
        Log.i(TAG, "migrate $oldKey -> $newKey = $saved")
    }

    /** 如果还是外部 Uri，就复制一份到私有目录，然后改写偏好 */
    private fun ensureLocalCopy(ctx: Context, e: ChargeEvent) {
        val cur = prefs(ctx).getString("uri_" + e.id, null) ?: return
        val uri = runCatching { Uri.parse(cur) }.getOrNull() ?: return
        val path = uri.path ?: return
        val tonesDir = File(ctx.filesDir, "tones").absolutePath
        if (path.startsWith(tonesDir)) return
        val local = copyIntoTones(ctx, e, uri) ?: return
        prefs(ctx).edit().putString("uri_" + e.id, local.toString()).apply()
        Log.i(TAG, "ensureLocalCopy ${e.id}: $cur -> $local")
    }

    private fun copyIntoTones(ctx: Context, e: ChargeEvent, src: Uri): Uri? = runCatching {
        val dir = File(ctx.filesDir, "tones").apply { mkdirs() }
        val ext = guessExt(ctx, src)
        val out = File(dir, e.id + "." + ext)
        ctx.contentResolver.openInputStream(src)?.use { input ->
            out.outputStream().use { o -> input.copyTo(o) }
        } ?: return@runCatching null
        if (out.length() <= 0L) return@runCatching null
        Log.i(TAG, "copied ${e.id} -> ${out.absolutePath} (${out.length()} B)")
        Uri.fromFile(out)
    }.getOrNull()

    /**
     * 推断扩展名。
     *
     * 不能只看路径：MediaStore 给的 content:// 里根本没有文件名，
     * 早先版本因此把音频存成了 .bin（能播，但不好看，个别机型还可能识别失败）。
     * 这里先看路径，再看 MIME，最后读一遍媒体元数据。
     */
    private fun guessExt(ctx: Context, u: Uri): String {
        val known = listOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus", "amr", "mp4")
        val path = (u.lastPathSegment ?: u.path ?: "").lowercase()
        for (k in known) if (path.endsWith("." + k)) return k

        val mime = runCatching { ctx.contentResolver.getType(u) }.getOrNull()
            ?: runCatching {
                val mmr = android.media.MediaMetadataRetriever()
                mmr.setDataSource(ctx, u)
                val m = mmr.extractMetadata(
                    android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                mmr.release()
                m
            }.getOrNull()

        return when {
            mime == null -> "bin"
            mime.contains("mpeg") || mime.contains("mp3") -> "mp3"
            mime.contains("wav") || mime.contains("x-wav") -> "wav"
            mime.contains("ogg") -> "ogg"
            mime.contains("mp4") || mime.contains("m4a") -> "m4a"
            mime.contains("aac") -> "aac"
            mime.contains("flac") -> "flac"
            mime.contains("opus") -> "opus"
            mime.contains("amr") -> "amr"
            else -> "bin"
        }
    }

    /**
     * 修补早期版本留下的 .bin：能读出 MIME 就改名成正确扩展名。
     * 改名后同步更新偏好里的路径。
     */
    private fun repairBinName(ctx: Context, e: ChargeEvent) {
        val cur = prefs(ctx).getString("uri_" + e.id, null) ?: return
        if (!cur.endsWith(".bin")) return
        val old = File(Uri.parse(cur).path ?: return)
        if (!old.exists()) return
        val ext = runCatching {
            val mmr = android.media.MediaMetadataRetriever()
            mmr.setDataSource(old.absolutePath)
            val m = mmr.extractMetadata(
                android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            mmr.release()
            m
        }.getOrNull()
        val newExt = when {
            ext == null -> return
            ext.contains("mpeg") || ext.contains("mp3") -> "mp3"
            ext.contains("wav") -> "wav"
            ext.contains("ogg") -> "ogg"
            ext.contains("mp4") || ext.contains("m4a") -> "m4a"
            ext.contains("aac") -> "aac"
            ext.contains("flac") -> "flac"
            else -> return
        }
        val dst = File(old.parentFile, e.id + "." + newExt)
        if (runCatching { old.renameTo(dst) }.getOrDefault(false)) {
            prefs(ctx).edit().putString("uri_" + e.id, Uri.fromFile(dst).toString()).apply()
            Log.i(TAG, "repair ${e.id}: .bin -> .$newExt")
        }
    }

    /**
     * 建池并预加载两个短音。
     *
     * 注意：SoundPool 的 AudioAttributes 一旦建好就改不了，
     * 所以这里必须按【当前通道】建池；通道一换就把池丢掉重建，
     * 否则选「铃声」通道却会从通知通道出声。
     */
    @Synchronized
    private fun ensurePool(ctx: Context) {
        if (pool != null) return
        val attrs = attrs()
        val sp = SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attrs).build()
        Log.i(TAG, "SoundPool 建池 usage=${attrs.usage} channel=${nameOf(channel)}")
        sp.setOnLoadCompleteListener { _, sampleId, status ->
            poolLoaded[sampleId] = status == 0
            Log.i(TAG, "SoundPool loaded id=$sampleId ok=${status == 0}")
        }
        pool = sp
        val idOff = ctx.resources.getIdentifier(ChargeEvent.SCREEN_OFF.rawName, "raw", ctx.packageName)
        val idUnlock = ctx.resources.getIdentifier(ChargeEvent.UNLOCK.rawName, "raw", ctx.packageName)
        if (idOff != 0) spScreenOff = sp.load(ctx, idOff, 1)
        if (idUnlock != 0) spUnlock = sp.load(ctx, idUnlock, 1)
        Log.i(TAG, "SoundPool 预加载: screen_off=$spScreenOff unlock=$spUnlock")
        // 已经设过自定义铃声的，也一起预热
        for (e in arrayOf(ChargeEvent.SCREEN_OFF, ChargeEvent.UNLOCK)) {
            val u = customUri(ctx, e)
            val p = u?.path ?: continue
            if (u.scheme == "file") poolIdForPath(ctx, p)
        }
    }

    fun setChannel(ctx: Context, ch: Int) {
        val changed = ch != channel
        channel = ch
        prefs(ctx).edit().putInt("channel", ch).apply()
        // 通道变了必须把 SoundPool 丢掉，否则还挂在旧通道上
        if (changed) releasePool()
    }

    /** 释放预加载池（通道切换时调用） */
    @Synchronized
    private fun releasePool() {
        runCatching { pool?.release() }
        pool = null
        poolLoaded.clear()
        spScreenOff = 0
        spUnlock = 0
        Log.i(TAG, "SoundPool 已释放，等待按新通道重建")
    }

    /** 兜底用的系统提示音：跟着当前通道走，而不是永远用通知音 */
    private fun defaultToneUri(): Uri = when (channel) {
        CHANNEL_RING -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        CHANNEL_ALARM -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        else -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    // ───────── 每个事件的铃声 ─────────

    fun setCustomUri(ctx: Context, e: ChargeEvent, uri: Uri?) {
        if (uri == null) {
            prefs(ctx).edit().remove("uri_" + e.id).apply()
            Log.i(TAG, "custom uri ${e.id} = null（恢复内置）")
            return
        }
        // 关键：先复制到 App 私有目录再存路径。
        // 直接存外部 content:// 的话，App 一升级、媒体库一重建就可能失效。
        val local = copyIntoTones(ctx, e, uri)
        val saved = local ?: uri
        prefs(ctx).edit().putString("uri_" + e.id, saved.toString()).apply()
        if (isPreloaded(e) && saved.scheme == "file" && saved.path != null) {
            ensurePool(ctx)
            poolIdForPath(ctx, saved.path!!)
        }
        Log.i(TAG, "custom uri ${e.id} = $saved (local=${local != null})")
    }

    fun customUri(ctx: Context, e: ChargeEvent): Uri? {
        val s = prefs(ctx).getString("uri_" + e.id, null)
        return if (s.isNullOrBlank()) null else Uri.parse(s)
    }

    // ───────── 播放 ─────────

    private fun attrs(): AudioAttributes {
        val usage = when (channel) {
            CHANNEL_MEDIA -> AudioAttributes.USAGE_MEDIA
            CHANNEL_ALARM -> AudioAttributes.USAGE_ALARM
            CHANNEL_RING -> AudioAttributes.USAGE_NOTIFICATION_RINGTONE
            else -> AudioAttributes.USAGE_NOTIFICATION_EVENT
        }
        val contentType = if (channel == CHANNEL_MEDIA)
            AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SONIFICATION
        return AudioAttributes.Builder().setUsage(usage).setContentType(contentType).build()
    }

    private fun mediaAttrs(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

    private fun streamForChannel(): Int = when (channel) {
        CHANNEL_MEDIA -> AudioManager.STREAM_MUSIC
        CHANNEL_ALARM -> AudioManager.STREAM_ALARM
        CHANNEL_RING -> AudioManager.STREAM_RING
        else -> AudioManager.STREAM_NOTIFICATION
    }

    /**
     * 事件入口：BatteryWatcher、界面试听按钮都走这里。
     *
     * @param dedupe true 表示同一事件 800ms 内只响一次
     */
    @Synchronized
    fun onEvent(ctx: Context, e: ChargeEvent, dedupe: Boolean = true): Boolean {
        if (!enabled(ctx)) return false
        if (!eventEnabled(ctx, e)) return false
        val now = System.currentTimeMillis()
        if (dedupe && e.id == lastEvent && now - lastEventAt < dedupeWindowMs(e)) return false
        lastEvent = e.id
        lastEventAt = now

        Log.i(TAG, "event=${e.id} custom=${customUri(ctx, e) != null} silent=${isSilent(ctx)}")
        val custom = customUri(ctx, e)

        // 熄屏 / 解锁：一律先走预加载（自定义铃声也预加载），延迟几毫秒
        if (isPreloaded(e)) {
            val id = if (custom != null) {
                val u = custom
                if (u.scheme == "file" && u.path != null) poolIdForPath(ctx, u.path!!) else 0
            } else {
                if (e == ChargeEvent.SCREEN_OFF) spScreenOff else spUnlock
            }
            if (id != 0 && poolLoaded[id] == true) {
                val vol = (volumeGain() * eventVolumeGain(e)).coerceIn(0f, 1f)
                pool?.play(id, vol, vol, 1, 0, 1f)
                Log.i(TAG, "play SOUNDPOOL event=${e.id} volume=$vol custom=${custom != null}")
                return true
            }
            Log.w(TAG, "SoundPool 未就绪，退回 MediaPlayer（${e.id} id=$id）")
        }

        if (custom != null) {
            playUri(ctx, custom, attrs(), e)
            return true
        }

        if (isSilent(ctx)) {
            vibrate(ctx, if (e == ChargeEvent.LOW_3 || e == ChargeEvent.OVERHEAT) 80 else 50)
            return true
        }
        playRawAsset(ctx, e.rawName, attrs(), e)
        return true
    }

    /** 试听：固定走媒体通道，保证静音模式下也能听见 */
    fun preview(ctx: Context, uri: Uri) {
        playUri(ctx, uri, mediaAttrs(), null)
    }

    private fun playRawAsset(ctx: Context, name: String, a: AudioAttributes, e: ChargeEvent?) {
        val resId = ctx.resources.getIdentifier(name, "raw", ctx.packageName)
        if (resId == 0) {
            playUri(ctx, defaultToneUri(), a, e)
            return
        }
        runCatching {
            ctx.resources.openRawResourceFd(resId).use { fd ->
                startPlayer(ctx, a, e) { mp ->
                    mp.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                }
            }
        }.onFailure {
            Log.w(TAG, "raw asset $name failed", it)
            playUri(ctx, defaultToneUri(), a, e)
        }
    }

    private fun playUri(ctx: Context, uri: Uri, a: AudioAttributes, e: ChargeEvent?) {
        runCatching {
            startPlayer(ctx, a, e) { mp -> mp.setDataSource(ctx, uri) }
        }.onFailure {
            Log.w(TAG, "MediaPlayer failed, fallback to Ringtone", it)
            runCatching {
                val tone: Ringtone = RingtoneManager.getRingtone(ctx, uri) ?: return
                // Ringtone 默认按自己的类型选流，这里强制用当前通道的属性
                runCatching { tone.audioAttributes = a }
                if (tone.isPlaying) tone.stop()
                tone.play()
            }
        }
    }

    private fun startPlayer(
        ctx: Context,
        a: AudioAttributes,
        e: ChargeEvent?,
        setSource: (MediaPlayer) -> Unit
    ) {
        stopPlayer()
        val mp = MediaPlayer()
        mp.setAudioAttributes(a)
        setSource(mp)
        val vol = volumeGain() * eventVolumeGain(e)
        mp.setVolume(vol, vol)
        mp.setOnCompletionListener { it.release() }
        mp.setOnErrorListener { p, what, extra ->
            Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
            p.release(); true
        }
        mp.prepare()
        val mb = boostMbFor(channel, e)
        applyBoost(mp, mb)
        mp.start()
        player = mp
        Log.i(TAG, "play usage=${a.usage} event=${e?.id ?: "preview"} dur=${mp.duration}ms " +
                "playing=${mp.isPlaying} volume=${"%.2f".format(vol)} boost=${mb}mB " +
                "(${"%.2f".format(mb / 100.0)}dB headset=$headsetConnected)")
    }

    private fun applyBoost(mp: MediaPlayer, mb: Int) {
        if (mb <= 0) return
        runCatching {
            val le = LoudnessEnhancer(mp.audioSessionId)
            le.setTargetGain(mb)
            le.enabled = true
            enhancer = le
        }.onFailure {
            Log.w(TAG, "LoudnessEnhancer 不可用，退回线性增益", it)
            runCatching { mp.setVolume(2f, 2f) }
        }
    }

    private fun stopPlayer() {
        try { enhancer?.enabled = false; enhancer?.release() } catch (_: Throwable) {}
        enhancer = null
        try { player?.release() } catch (_: Throwable) {}
        player = null
    }

    fun isSilent(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        if (am.ringerMode == AudioManager.RINGER_MODE_SILENT &&
            (channel == CHANNEL_NOTIFICATION || channel == CHANNEL_RING)) return true
        return am.getStreamVolume(streamForChannel()) <= 0
    }

    private fun vibrate(ctx: Context, ms: Long) {
        runCatching {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (v == null || !v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        }
    }

    /**
     * 监听「输出设备变化」。
     *
     * 用的是 AudioManager.registerAudioDeviceCallback，不需要任何权限，
     * 比 isWiredHeadsetOn() 可靠（那个在 Android 12 之后基本返回 false）。
     */
    fun ensureDeviceWatcher(ctx: Context) {
        if (deviceCallback != null) return
        val app = ctx.applicationContext
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                refreshHeadset(app)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                refreshHeadset(app)
            }
        }
        deviceCallback = cb
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        am.registerAudioDeviceCallback(cb, null)
        refreshHeadset(app)
        Log.i(TAG, "device watcher registered")
    }

    private fun refreshHeadset(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val outputs = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrDefault(emptyArray())
        // 用常量 + 两个高版本才有的类型（26 BLE_HEADSET / 27 BLE_SPEAKER），
        // 写成数字是为了不因为 minSdk 26 而被编译期挡掉
        val headsetTypes = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,        // 3
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,     // 4
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,        // 7
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,       // 8
            AudioDeviceInfo.TYPE_USB_HEADSET,          // 22
            AudioDeviceInfo.TYPE_HEARING_AID,          // 23
            26, 27                                      // BLE_HEADSET / BLE_SPEAKER
        )
        val now = outputs.any { it.type in headsetTypes }
        if (!headsetKnown || now != headsetConnected) {
            headsetKnown = true
            headsetConnected = now
            Log.i(TAG, "headset=" + now + " 输出设备=" + outputs.joinToString { it.type.toString() })
        }
    }

    /** 界面显示用 */
    fun isHeadsetConnected(): Boolean = headsetConnected

    /** 界面显示用：这个事件当前生效的放大倍数；没有放大的时候直接写「无增幅」 */
    fun boostText(e: ChargeEvent): String {
        val mb = boostMbFor(channel, e)
        if (headsetConnected) return "原声 20%（耳机）"
        if (mb <= 0) return "无增幅"
        val mul = Math.pow(10.0, mb / 2000.0)      // dB = mb/100；倍数 = 10^(dB/20)
        return String.format("放大 %.1fx", mul)
    }

    fun pickToneIntent(current: Uri?): android.content.Intent =
        android.content.Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
            putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                Settings.System.DEFAULT_NOTIFICATION_URI)
        }

    /**
     * 隐蔽模式。
     *
     * 打开之后：不跑前台服务（因此没有常驻通知、也没有后台卡片），
     * 只靠无障碍服务常驻。无障碍由系统绑定，不需要通知。
     */
    /** 通知小图标固定用闪电（其它三款已删除，见 2026-11-09 的决定） */
    fun notifIconRes(ctx: Context): Int {
        val id = ctx.resources.getIdentifier("ic_notif_bolt", "drawable", ctx.packageName)
        return if (id != 0) id else android.R.drawable.ic_lock_idle_charging
    }

    /** 通知标题 / 正文（用户可自定义） */
    fun notifTitle(ctx: Context): String =
        prefs(ctx).getString("notif_title", "充电提示音运行中") ?: "充电提示音运行中"

    fun notifText(ctx: Context): String =
        prefs(ctx).getString("notif_text", "插拔 / 充满 / 过热 / 低电量 都会提示")
            ?: "插拔 / 充满 / 过热 / 低电量 都会提示"

    fun setNotifContent(ctx: Context, title: String, text: String) {
        prefs(ctx).edit()
            .putString("notif_title", title)
            .putString("notif_text", text)
            .apply()
    }

    /** 单个事件的声音开关（默认全开） */
    fun eventEnabled(ctx: Context, e: ChargeEvent): Boolean =
        prefs(ctx).getBoolean("on_" + e.id, true)

    fun setEventEnabled(ctx: Context, e: ChargeEvent, on: Boolean) {
        prefs(ctx).edit().putBoolean("on_" + e.id, on).apply()
    }

    /** 总开关：关掉之后任何事件都不出声（无障碍/前台服务是否常驻另说） */
    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("enabled", on).apply()
    }

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("enabled", true)

    // ── 两个独立的隐藏选项 ────────────────────────────────
    //   hideRecents      离开界面时把任务从「最近任务」里移除
    //   hideNotification 不跑前台服务（因此没有常驻通知），改由无障碍常驻
    // 老版本只有一个 hidden 开关，这里做一次性迁移。
    private fun migrateHidden(ctx: Context) {
        val p = prefs(ctx)
        if (p.contains("hideRecents") || p.contains("hideNotification")) return
        val old = p.getBoolean("hidden", false)
        p.edit()
            .putBoolean("hideRecents", old)
            .putBoolean("hideNotification", old)
            .apply()
        Log.i(TAG, "migrate hidden=$old -> hideRecents/hideNotification")
    }

    fun setHideRecents(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("hideRecents", on).apply()
    }

    fun hideRecents(ctx: Context): Boolean {
        migrateHidden(ctx)
        return prefs(ctx).getBoolean("hideRecents", false)
    }

    fun setHideNotification(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("hideNotification", on).apply()
    }

    fun hideNotification(ctx: Context): Boolean {
        migrateHidden(ctx)
        return prefs(ctx).getBoolean("hideNotification", false)
    }

    /** 兼容旧调用：两个都开才算「全隐蔽」 */
    fun hidden(ctx: Context): Boolean = hideRecents(ctx) && hideNotification(ctx)

    fun setBootEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean("boot", on).apply()
    }

    fun bootEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("boot", false)

    fun release() {
        stopPlayer()
    }
}
