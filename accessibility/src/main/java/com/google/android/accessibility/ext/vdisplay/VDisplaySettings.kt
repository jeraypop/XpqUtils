package com.google.android.accessibility.ext.vdisplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.accessibility.ext.utils.ActivityUtils
import com.google.android.accessibility.ext.utils.safecheck.OverlayPermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 虚拟屏设置：总开关 + 锁屏时是否解锁主屏 + 是否显示预览。
 *
 * - 启用虚拟屏：任务是否在副屏执行；
 * - 解锁主屏：开启时锁屏任务先亮屏/解锁主屏（之后走剪贴板粘贴）；
 *   关闭时保持锁屏执行（锁屏下的文本输入走节点 ACTION_SET_TEXT）；
 * - 显示虚拟屏预览：任务运行时右上角是否显示副屏实时画面。
 */
object VDisplaySettings {
    private const val PREFS = "xpq_vdisplay"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_UNLOCK = "unlock"
    private const val KEY_PREVIEW = "preview"
    private const val KEY_DUAL = "dual"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun shouldUnlock(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_UNLOCK, true)

    fun isPreviewEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PREVIEW, true)

    /** 是否启动分身版应用；默认 false，即启动主应用版本。 */
    fun isDualEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DUAL, false)

    private fun enabledText(enabled: Boolean): String =
        if (enabled) "开启后任务在副屏默默执行，不影响操作设备"
        else "任务在主屏执行，在此期间操作设备会打断任务"

    private fun unlockText(unlocked: Boolean): String =
        if (unlocked) "需要解锁设备后才会执行任务，已开启"
        else "锁屏下就能执行任务，但被检测到几率大一点，推荐开启"

    private fun previewText(preview: Boolean): String =
        if (preview) "已显示预览窗：可拖动调整位置/大小，任务运行时显示副屏实时画面"
        else "任务默默地在虚拟屏执行，关闭不影响执行"

    private fun dualText(dual: Boolean): String =
        if (dual) "启动应用的分身版"
        else "启动应用的主版本"

    private fun subtitle(context: Context, text: String) = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(0xFF666666.toInt())
        setPadding(0, (2 * context.resources.displayMetrics.density).toInt(), 0, 0)
    }

    /** 一行设置：左侧「主标题 + 副标题」，右侧开关。 */
    private fun row(context: Context, title: String, subtitleView: TextView, switch: Switch): LinearLayout {
        val dp = context.resources.displayMetrics.density
        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(0xFF000000.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        left.addView(subtitleView)
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
            addView(left)
            addView(switch)
        }
    }

    private const val PREVIEW_VIDEO_URL =
        "https://gitlab.com/mytiper/send/-/raw/master/public/xuni.mp4"
    private const val VIDEO_CACHE_DIR = "xpq_preview_video"
    private const val VIDEO_CHECK_TIMEOUT_MS = 3_000
    private const val VIDEO_DOWNLOAD_TIMEOUT_MS = 15_000
    private const val VIDEO_TTL_MS = 24L * 60 * 60 * 1000

    @Volatile
    private var videoPreparing = false

    /**
     * 演示视频：首次下载到 `cacheDir`，之后每次点击只发一个 HEAD 校验（ETag / Last-Modified），
     * 没更新就直接播本地文件；断了网也能播缓存。
     */
    private fun playPreviewVideo(activity: Activity) {
        if (videoPreparing) return
        videoPreparing = true
        Toast.makeText(activity, "正在准备演示视频…", Toast.LENGTH_SHORT).show()
        CoroutineScope(Dispatchers.IO).launch {
            val file = runCatching { resolvePreviewVideo(activity.applicationContext) }.getOrNull()
            withContext(Dispatchers.Main) {
                videoPreparing = false
                if (file != null && file.length() > 0) {
                    runCatching {
                        ActivityUtils.showVideoDialog(activity, file.toURI().toString())
                    }.onFailure { Log.w("VDisplaySettings", "show video dialog failed", it) }
                } else {
                    Toast.makeText(activity, "演示视频加载失败，请检查网络", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 返回可直接播放的本地文件；网络不可用但有缓存时也返回缓存。 */
    private fun resolvePreviewVideo(context: Context): File? {
        val dir = File(context.cacheDir, VIDEO_CACHE_DIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        val key = md5(PREVIEW_VIDEO_URL)
        val file = File(dir, "$key.mp4")
        val meta = File(dir, "$key.meta")
        val cached = file.exists() && file.length() > 0
        val props = readMeta(meta)

        if (cached) {
            // false=没变化 / null=校验失败(多半断网) → 都直接播本地；true=有更新才重新下载
            when (checkRemoteVideo(meta, props)) {
                true -> Unit
                else -> return file
            }
        }

        val tmp = File(dir, "$key.tmp")
        val newProps = downloadVideo(tmp) ?: return if (cached) file else null
        if (!tmp.renameTo(file)) {
            tmp.delete()
            return if (cached) file else null
        }
        writeMeta(meta, newProps)
        return file
    }

    /**
     * HEAD 校验远端是否更新。
     * @return true=有更新 / false=未更新（含 304）/ null=校验失败（网络错误等）
     */
    private fun checkRemoteVideo(meta: File, props: Map<String, String>): Boolean? = runCatching {
        val conn = URL(PREVIEW_VIDEO_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"
        conn.connectTimeout = VIDEO_CHECK_TIMEOUT_MS
        conn.readTimeout = VIDEO_CHECK_TIMEOUT_MS
        props["etag"]?.takeIf { it.isNotEmpty() }?.let { conn.setRequestProperty("If-None-Match", it) }
        props["lastModified"]?.takeIf { it.isNotEmpty() }
            ?.let { conn.setRequestProperty("If-Modified-Since", it) }
        when (conn.responseCode) {
            HttpURLConnection.HTTP_NOT_MODIFIED -> return@runCatching false
            HttpURLConnection.HTTP_OK -> Unit
            else -> return@runCatching null
        }
        val etag = conn.getHeaderField("ETag")
        val lastModified = conn.getHeaderField("Last-Modified")
        val length = conn.getHeaderField("Content-Length")
        val cachedEtag = props["etag"].orEmpty()
        val cachedLastModified = props["lastModified"].orEmpty()
        val cachedLength = props["length"].orEmpty()
        val checkedAt = props["checkedAt"]?.toLongOrNull() ?: 0L
        val changed = when {
            !etag.isNullOrEmpty() && cachedEtag.isNotEmpty() -> etag != cachedEtag
            !lastModified.isNullOrEmpty() && cachedLastModified.isNotEmpty() ->
                lastModified != cachedLastModified ||
                    (!length.isNullOrEmpty() && cachedLength.isNotEmpty() && length != cachedLength)
            // 服务器没有可用校验头：按 TTL 兜底
            else -> System.currentTimeMillis() - checkedAt > VIDEO_TTL_MS
        }
        if (!changed) writeMeta(meta, props + ("checkedAt" to System.currentTimeMillis().toString()))
        changed
    }.getOrElse { null }

    /** 下载到临时文件；成功返回元信息（ETag/Last-Modified/长度/校验时间），失败返回 null。 */
    private fun downloadVideo(tmp: File): Map<String, String>? = runCatching {
        val conn = URL(PREVIEW_VIDEO_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = VIDEO_DOWNLOAD_TIMEOUT_MS
        conn.readTimeout = VIDEO_DOWNLOAD_TIMEOUT_MS
        if (conn.responseCode !in 200..299) {
            tmp.delete()
            return@runCatching null
        }
        conn.inputStream.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
        mapOf(
            "etag" to conn.getHeaderField("ETag").orEmpty(),
            "lastModified" to conn.getHeaderField("Last-Modified").orEmpty(),
            "length" to tmp.length().toString(),
            "checkedAt" to System.currentTimeMillis().toString(),
        )
    }.getOrElse {
        tmp.delete()
        null
    }

    private fun readMeta(meta: File): Map<String, String> = runCatching {
        if (!meta.exists()) emptyMap() else meta.readLines().mapNotNull { line ->
            val index = line.indexOf('=')
            if (index <= 0) null else line.substring(0, index) to line.substring(index + 1)
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun writeMeta(meta: File, props: Map<String, String>) = runCatching {
        meta.writeText(props.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * 显示预览窗；没有悬浮窗权限时先尝试用 shell(appops) 自动开启，
     * 仍然失败就提示用户去系统设置手动授权。必须在主线程调用。
     */
    private fun showPreview(context: Context) {
        if (XpqVDisplayPreview.show(context, null)) return
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val result = OverlayPermissionHelper.enableOverlayPermission(app)
            Log.i("VDisplaySettings", "overlay permission: ${result.message}")
            val shown = withContext(Dispatchers.Main) { XpqVDisplayPreview.show(context, null) }
            if (shown) return@launch
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    app,
                    "预览需要悬浮窗权限，请在系统设置里允许后重新打开本设置",
                    Toast.LENGTH_LONG,
                ).show()
                runCatching {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${app.packageName}"),
                    )
                    val activity = context as? Activity
                    if (activity != null) {
                        activity.startActivity(intent)
                    } else {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        app.startActivity(intent)
                    }
                }.onFailure { Log.w("VDisplaySettings", "open overlay settings failed", it) }
            }
        }
    }

    /**
     * @param showDualSwitch 是否显示第 4 个开关「启动分身版」。
     * 不显示时由宿主自己决定启动主应用还是分身版（如 autosend-AD 按双开坐标判断）。
     */
    @JvmOverloads
    fun showDialog(context: Context, showDualSwitch: Boolean = false) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dp = context.resources.displayMetrics.density
        val padding = (20 * dp).toInt()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        root.addView(TextView(context).apply {
            val tip = "需要 Android 11 及以上，且工作在Shizuku模式。" +
                "任务在虚拟屏(副屏：平常看不见的)运行，主屏(我们平常看到的就是主屏)可正常使用(比如刷视频，玩游戏等)。"
            val linkText = "点击查看效果"
            val spannable = SpannableString(tip + linkText)
            spannable.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val activity = context as? Activity ?: return
                    playPreviewVideo(activity)
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = 0xFF1976D2.toInt()
                    ds.isUnderlineText = true
                }
            }, tip.length, spannable.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = spannable
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = 0
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFF888888.toInt())
            setLineSpacing(0f, 1.2f)
            setPadding(0, 0, 0, (12 * dp).toInt())
        })

        val enabledSwitch = Switch(context).apply { isChecked = prefs.getBoolean(KEY_ENABLED, false) }
        val unlockSwitch = Switch(context).apply {
            isChecked = prefs.getBoolean(KEY_UNLOCK, true)
            isEnabled = enabledSwitch.isChecked
        }
        val previewSwitch = Switch(context).apply {
            isChecked = prefs.getBoolean(KEY_PREVIEW, true)
            isEnabled = enabledSwitch.isChecked
        }
        val unlockSubtitle = subtitle(context, unlockText(prefs.getBoolean(KEY_UNLOCK, true)))
        val enabledSubtitle = subtitle(context, enabledText(prefs.getBoolean(KEY_ENABLED, false)))
        val previewSubtitle = subtitle(context, previewText(prefs.getBoolean(KEY_PREVIEW, true)))

        // 第 4 个开关：由宿主决定是否需要（不需要时启动主应用还是分身版由宿主自己判断）
        val dualSwitch = if (showDualSwitch) Switch(context).apply {
            isChecked = prefs.getBoolean(KEY_DUAL, false)
            isEnabled = enabledSwitch.isChecked
        } else null
        val dualSubtitle = if (showDualSwitch) {
            subtitle(context, dualText(prefs.getBoolean(KEY_DUAL, false)))
        } else null

        // 切换即时写入，不依赖“确定”
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_ENABLED, checked).apply()
            unlockSwitch.isEnabled = checked
            previewSwitch.isEnabled = checked
            dualSwitch?.isEnabled = checked
            enabledSubtitle.text = enabledText(checked)
            // 关掉虚拟屏后保留预览窗没有意义
            if (!checked) XpqVDisplayPreview.hide()
        }
        unlockSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_UNLOCK, checked).apply()
            unlockSubtitle.text = unlockText(checked)
        }
        previewSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_PREVIEW, checked).apply()
            previewSubtitle.text = previewText(checked)
            // 打开预览开关就立刻把预览窗显示出来，方便调整大小/位置；关闭则收起
            if (checked) showPreview(context) else XpqVDisplayPreview.hide()
        }
        dualSwitch?.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_DUAL, checked).apply()
            dualSubtitle?.text = dualText(checked)
        }

        root.addView(row(context, "启用虚拟屏", enabledSubtitle, enabledSwitch))
        root.addView(row(context, "解锁设备", unlockSubtitle, unlockSwitch))
        root.addView(row(context, "显示虚拟屏预览", previewSubtitle, previewSwitch))
        if (dualSwitch != null && dualSubtitle != null) {
            root.addView(row(context, "启动分身版", dualSubtitle, dualSwitch))
        }

        // 预览已开启时，打开设置就先把预览窗摆出来，方便直接调整位置/大小
        if (enabledSwitch.isChecked && previewSwitch.isChecked) XpqVDisplayPreview.show(context, null)
        AlertDialog.Builder(context)
            .setTitle("虚拟(副)屏设置")
            .setView(root)
            .setPositiveButton("确定", null)
            .show()
    }
}
