package com.demo.charge

import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 音频筛选界面。
 *
 * 规则（王桑 2026-11-08）：
 *   一、申请「管理全部文件」权限，让 App 自己扫出 7 秒以内的所有音频
 *   二、点一下  → 试听
 *   三、按住 2 秒 → 确定选择（返回给上一个界面）
 *
 * 扫描分两步：
 *   MediaStore 查一遍（有索引的快）
 *   再用 File 走一遍常见目录补漏（有些第三方下载的音频没进媒体库）
 */
class TonePickerActivity : Activity() {

    companion object {
        const val EXTRA_EVENT = "event_id"
        const val RESULT_URI = "picked_uri"

        /** 只要 7 秒以内的 */
        private const val MAX_MS = 7000L

        /** 按住多久算「确定选择」 */
        private const val HOLD_MS = 2000L
    }

    private var event: ChargeEvent = ChargeEvent.PLUG_IN
    private lateinit var list: ListView
    private lateinit var hintView: TextView
    private lateinit var adapter: AudioAdapter
    private val items = ArrayList<AudioItem>()

    /** 全部扫描结果（未过滤） */
    private val all = ArrayList<AudioItem>()

    /** 按当前过滤条件重算 items（搜索 + 极短音开关 一起生效） */
    private fun rebuildVisible() {
        items.clear()
        items.addAll(all.filter {
            !isCallRecording(it) &&
                    (showTiny || it.durationMs >= 200) &&
                    matches(it)
        })
        adapter.notifyDataSetChanged()
        updateHint()
    }

    /**
     * 通话录音一律不显示。
     *
     * 小米把通话录音放在 MIUI/sound_recorder/call_rec/ 下，
     * 这些文件名字是「号码_时间」，混在提示音列表里既没用也碍眼。
     * 只按路径判断，不动其他任何录音（app_rec / fm_rec 仍保留）。
     */
    private fun isCallRecording(item: AudioItem): Boolean {
        val p = item.path.ifBlank { item.file?.absolutePath.orEmpty() }
        val lower = p.lowercase()
        return lower.contains("/call_rec/") ||
                lower.contains("sound_recorder/call") ||
                lower.contains("/callrec/")
    }

    /**
     * 搜索匹配规则 —— 跟 MusicTage 的 DatabaseManagementActivity 完全一致：
     *
     *   songs.filter {
     *       it.title.contains(query, true) ||
     *       it.artist.contains(query, true) ||
     *       it.filePath.contains(query, true)
     *   }
     *
     * 也就是「忽略大小写的子串匹配」，标题 / 艺术家 / 文件路径 三处任意命中即可。
     * 这里没有艺术家字段，就用「标题」和「文件路径」两处，字段顺序保持一样。
     */
    private fun matches(item: AudioItem): Boolean {
        if (query.isBlank()) return true
        return item.title.contains(query, true) ||
                (item.file?.absolutePath ?: item.uri.toString()).contains(query, true)
    }

    private fun updateHint() {
        hintView.text = when {
            all.isEmpty() -> "没找到 7 秒以内的音频（已授权？点上面「申请全部文件权限」）"
            items.isEmpty() && query.isNotBlank() -> "没有匹配「" + query + "」的音频"
            items.isEmpty() -> "共 " + all.size + " 个，但都短于 200ms（点「显示极短音」）"
            query.isNotBlank() -> "匹配「" + query + "」：" + items.size + " 个"
            else -> "可用 " + items.size + " 个（共扫到 " + all.size + " 个 ≤7 秒的，" +
                    "已排除通话录音 " + all.count { isCallRecording(it) } + " 个）"
        }
    }
    private val holdTimer = android.os.Handler(android.os.Looper.getMainLooper())
    /** 默认藏掉 200 毫秒以下的系统按键音，不然一屏全是「点了没声音」的条目 */
    private var showTiny = false

    /** 搜索词（跟 MusicTage 的搜索逻辑一致，见 matches()） */
    private var query = ""

    private lateinit var searchBox: android.widget.EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        event = ChargeEvent.byId(intent.getStringExtra(EXTRA_EVENT))
        setContentView(buildUi())
        ensurePermissionThenScan()
    }

    override fun onResume() {
        super.onResume()
        if (!items.isEmpty() && isManager()) return
        if (isManager() && items.isEmpty()) rescan()
    }

    // ───────── 界面 ─────────

    private fun buildUi(): ViewGroup {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B1118"))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(10))
        }
        head.addView(TextView(this).apply {
            text = "选一个提示音"
            setTextColor(Color.WHITE)
            textSize = 20f
        })
        head.addView(TextView(this).apply {
            text = "只列出 7 秒以内的音频。点一下试听，按住 2 秒选定。"
            setTextColor(Color.parseColor("#8FA3B8"))
            textSize = 12f
            setPadding(0, dp(6), 0, 0)
        })
        hintView = TextView(this).apply {
            text = "正在准备…"
            setTextColor(Color.parseColor("#7CFFB2"))
            textSize = 12f
            setPadding(0, dp(10), 0, 0)
        }
        head.addView(hintView)

        searchBox = android.widget.EditText(this).apply {
            hint = "搜索：标题或路径（不分大小写）"
            setHintTextColor(Color.parseColor("#667788"))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    rebuildVisible()
                }
            })
        }
        head.addView(searchBox)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "重新扫描"
            setOnClickListener { rescan() }
        })
        row.addView(Button(this).apply {
            text = "申请全部文件权限"
            setOnClickListener { askAllFiles() }
        })
        row.addView(Button(this).apply {
            text = "显示极短音"
            setOnClickListener {
                showTiny = !showTiny
                rebuildVisible()
                val state = if (showTiny) "开" else "关"
                hintView.text = "显示极短音：" + state + "（当前 " + items.size + " 条）"
            }
        })
        head.addView(row)
        root.addView(head)

        list = ListView(this).apply {
            adapter = AudioAdapter().also { this@TonePickerActivity.adapter = it }
        }
        root.addView(list, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    // ───────── 权限 ─────────

    private fun isManager(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun ensurePermissionThenScan() {
        if (isManager()) {
            rescan()
            return
        }
        hintView.text = "需要「管理全部文件」权限才能扫到所有音频"
        askAllFiles()
    }

    private fun askAllFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }.onFailure {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:$packageName"))) }
            }
        }
    }

    // ───────── 扫描 ─────────

    private fun rescan() {
        hintView.text = "正在扫描…"
        items.clear()
        adapter.notifyDataSetChanged()

        Thread {
            val found = LinkedHashMap<String, AudioItem>()

            // 一、MediaStore（带时长，最准）
            runCatching {
                val cols = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATA
                )
                val sel = "${MediaStore.Audio.Media.DURATION} > 0 AND " +
                        "${MediaStore.Audio.Media.DURATION} <= $MAX_MS"
                contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, sel, null,
                    "${MediaStore.Audio.Media.DURATION} ASC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val title = c.getString(1) ?: "未命名"
                        val dur = c.getLong(2)
                        val size = c.getLong(3)
                        val path = runCatching { c.getString(4) }.getOrNull().orEmpty()
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        found[uri.toString()] = AudioItem(title, dur, size, uri, null, path)
                    }
                }
            }

            // 二、文件系统补漏（没进媒体库的）
            if (isManager()) {
                val roots = listOf(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_NOTIFICATIONS),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_ALARMS),
                    Environment.getExternalStorageDirectory()
                )
                val exts = setOf("mp3", "wav", "ogg", "m4a", "aac", "flac", "opus")
                val seen = HashSet<String>()
                for (root in roots) {
                    if (root == null || !root.exists()) continue
                    runCatching {
                        root.walkTopDown().maxDepth(6).forEach { f ->
                            if (f.isFile && f.length() in 1..(4 * 1024 * 1024)) {
                                val ext = f.extension.lowercase()
                                if (ext in exts && seen.add(f.absolutePath)) {
                                    val dur = probeDurationMs(f)
                                    if (dur in 1..MAX_MS) {
                                        val uri = Uri.fromFile(f)
                                        if (found[uri.toString()] == null) {
                                            found[uri.toString()] =
                                                AudioItem(f.nameWithoutExtension, dur, f.length(), uri, f,
                                                    f.absolutePath)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val result = found.values.sortedBy { it.durationMs }
            runOnUiThread {
                all.clear()
                all.addAll(result)
                rebuildVisible()
            }
        }.start()
    }

    /** 用 MediaMetadataRetriever 读时长 */
    private fun probeDurationMs(f: File): Long = runCatching {
        val mmr = android.media.MediaMetadataRetriever()
        mmr.setDataSource(f.absolutePath)
        val s = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
        mmr.release()
        s?.toLongOrNull() ?: 0L
    }.getOrDefault(0L)

    // ───────── 列表 ─────────

    private inner class AudioAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val item = items[position]
            val row = LinearLayout(this@TonePickerActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(12))
            }
            row.addView(TextView(this@TonePickerActivity).apply {
                text = item.title
                setTextColor(Color.WHITE)
                textSize = 15f
            })
            row.addView(TextView(this@TonePickerActivity).apply {
                text = "${item.durationMs} ms   ${formatSize(item.size)}"
                setTextColor(Color.parseColor("#8FA3B8"))
                textSize = 11f
                setPadding(0, dp(3), 0, 0)
            })
            // 点击试听 / 按住 2 秒选定
            //
            // 这里必须 return true（消费 DOWN），否则 ListView 会把这个手势
            // 当成「可能的滑动」拦下来，我们收不到 UP，点按和长按全部失效。
            //
            // 长按用 Handler 定时器在 2 秒整点触发（不用等松手），
            // 触发过就不再响应这次手势的 UP。
            row.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.setTag(R.id.tag_hold_token, Any())
                        v.animate().alpha(0.6f).setDuration(80).start()
                        val token = v.getTag(R.id.tag_hold_token)
                        holdTimer.postDelayed({
                            if (v.getTag(R.id.tag_hold_token) === token) {
                                v.setTag(R.id.tag_hold_token, null)
                                v.animate().alpha(1f).setDuration(80).start()
                                android.util.Log.i("ChargeSound", "pick by hold: ${item.title}")
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    pick(item)
                                }
                            }
                        }, HOLD_MS)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.animate().alpha(1f).setDuration(80).start()
                        holdTimer.removeCallbacksAndMessages(null)
                        val stillHolding = v.getTag(R.id.tag_hold_token) != null
                        v.setTag(R.id.tag_hold_token, null)
                        if (stillHolding) {
                            android.util.Log.i("ChargeSound", "preview by tap: ${item.title} ${item.uri}")
                            preview(item)
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        holdTimer.removeCallbacksAndMessages(null)
                        v.setTag(R.id.tag_hold_token, null)
                        v.animate().alpha(1f).setDuration(80).start()
                        true
                    }
                    else -> false
                }
            }
            // 右侧再给一个明确的「选定」按钮：
            // 长按 2 秒是主路径，但这个按钮更稳（模拟输入 / 无障碍场景）
            val wrap = LinearLayout(this@TonePickerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            wrap.addView(row, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            wrap.addView(Button(this@TonePickerActivity).apply {
                text = "选定"
                setOnClickListener {
                    android.util.Log.i("ChargeSound", "pick by button: " + item.title)
                    pick(item)
                }
            })
            return wrap
        }
    }

    private fun preview(item: AudioItem) {
        hintView.text = "试听：${item.title}"
        SoundHub.preview(this, item.uri)
    }

    private fun pick(item: AudioItem) {
        hintView.text = "已选定：${item.title}"
        SoundHub.setCustomUri(this, event, item.uri)
        setResult(RESULT_OK, Intent().putExtra(RESULT_URI, item.uri.toString()))
        Toast.makeText(this, "已选定：${item.title}", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun formatSize(b: Long): String = when {
        b >= 1024 * 1024 -> String.format("%.1f MB", b / 1024.0 / 1024)
        b >= 1024 -> String.format("%.0f KB", b / 1024.0)
        else -> "$b B"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}

data class AudioItem(
    val title: String,
    val durationMs: Long,
    val size: Long,
    val uri: Uri,
    val file: File?,
    /** 完整路径，用来做目录过滤（通话录音之类） */
    val path: String = ""
)
