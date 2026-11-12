package com.google.android.accessibility.ext.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.google.android.accessibility.ext.utils.AliveUtils

/**
 * 移植自 Shizuku 的配对方式：**通知栏里输入配对码**。
 *
 * 之前用 App 内的悬浮窗输入，用户要在系统配对弹窗和 App 之间切换；
 * 这台 ROM 上一旦离开设置，配对服务就结束了，所以失败率高。
 *
 * 现在改为：
 *  1. 前台服务在后台 mDNS 发现配对服务（系统配对弹窗打开后才会出现）；
 *  2. 发现后发一条带输入框的通知（RemoteInput）；
 *  3. 用户下拉通知栏直接输入 6 位码 → 设置页始终在前台，配对服务不会中断；
 *  4. 配对成功自动连接。
 *
 * 通知里的 host/port 是发现那一刻捕获的，避免二次发现拿到旧端口。
 */
class AdbPairingService : Service() {

    companion object {
        private const val TAG = "XpqAdbShell"
        private const val CHANNEL = "xpq_adb_pairing"
        private const val NOTIFICATION_ID = 0x5851
        private const val KEY_CODE = "pairing_code"
        private const val EXTRA_HOST = "pairing_host"
        private const val EXTRA_PORT = "pairing_port"
        private const val ACTION_START = "com.google.android.accessibility.ext.shell.PAIR_START"
        private const val ACTION_INPUT = "com.google.android.accessibility.ext.shell.PAIR_INPUT"
        private const val SEARCH_TIMEOUT_MS = 120_000L

        /** 启动配对流程（发现 + 等用户在通知栏输入配对码）。 */
        @JvmStatic
        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, AdbPairingService::class.java).setAction(ACTION_START)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "pairing service start failed", it) }
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var searching = false

    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "ADB 配对", NotificationManager.IMPORTANCE_HIGH).apply {
                        setSound(null, null)
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    /** minSdk 24：API 26 以下没有 channel 构造器。 */
    private fun newBuilder(): Notification.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundSafe(searchingNotification())
                beginSearch()
            }
            ACTION_INPUT -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(KEY_CODE)?.toString()?.trim().orEmpty()
                val host = intent.getStringExtra(EXTRA_HOST) ?: "127.0.0.1"
                val port = intent.getIntExtra(EXTRA_PORT, -1)
                if (port > 0 && code.isNotEmpty()) {
                    doPair(host, port, code)
                } else {
                    startForegroundSafe(searchingNotification())
                    beginSearch()
                }
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startForegroundSafe(notification: Notification) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w(TAG, "startForeground failed", it) }
    }

    /** 后台轮询发现配对服务；配对服务只在系统配对弹窗打开时存在。 */
    private fun beginSearch() {
        if (searching) return
        searching = true
        Log.i(TAG, "pairing discovery started")
        val deadline = System.currentTimeMillis() + SEARCH_TIMEOUT_MS
        Thread {
            while (searching && System.currentTimeMillis() < deadline) {
                val found = runCatching {
                    AdbMdns.discover(applicationContext, AdbMdns.PAIRING, 3_000L)
                }.getOrNull()
                if (found != null && found.second > 0) {
                    Log.i(TAG, "pairing service found ${found.first}:${found.second}")
                    handler.post { notifyInput(found.first, found.second) }
                    return@Thread
                }
            }
            if (searching) {
                handler.post {
                    nm.notify(
                        NOTIFICATION_ID,
                        resultNotification("配对超时", "请重新打开无线调试的「使用配对码配对设备」")
                    )
                    stopSelf()
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun notifyInput(host: String, port: Int) {
        searching = false
        val reply = Intent(this, AdbPairingService::class.java).setAction(ACTION_INPUT)
            .putExtra(EXTRA_HOST, host)
            .putExtra(EXTRA_PORT, port)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getService(this, 1, reply, flags)
        val input = RemoteInput.Builder(KEY_CODE)
            .setLabel("6 位配对码")
            .build()
        val action = Notification.Action.Builder(null, "输入配对码", pi)
            .addRemoteInput(input)
            .build()
        val n = newBuilder()
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("找到配对服务，请输入配对码")
            .setContentText("端口 ${port}（在通知栏输入，别离开设置页）")
            .setOngoing(true)
            .addAction(action)
            .build()
        nm.notify(NOTIFICATION_ID, n)
    }

    private fun doPair(host: String, port: Int, code: String) {
        startForegroundSafe(
            newBuilder()
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("正在配对…")
                .build()
        )
        Thread {
            val bridge = KadbShellBridge(applicationContext, host, port)
            val paired = bridge.pair(code)
            var connected = false
            if (paired) {
                connected = runCatching {
                    XpqAdbShell.connect(applicationContext, host, port = 0)
                }.getOrDefault(false)
            }
            handler.post {
                val title = when {
                    !paired -> "配对失败"
                    connected -> "配对并连接成功"
                    else -> "配对成功，连接失败"
                }
                val text = when {
                    !paired -> XpqAdbShell.lastError ?: bridge.lastPairError ?: "端口或配对码不对"
                    connected -> "之后启动/重启会自动重连"
                    else -> XpqAdbShell.lastError ?: "请稍后重试"
                }
                nm.notify(NOTIFICATION_ID, resultNotification(title, text))
                AliveUtils.toast(msg = title)
                stopSelf()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun searchingNotification(): Notification =
        newBuilder()
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("正在查找配对服务…")
            .setContentText("请打开 开发者选项 → 无线调试 → 使用配对码配对设备")
            .setOngoing(true)
            .build()

    private fun resultNotification(title: String, text: String): Notification =
        newBuilder()
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()

    override fun onDestroy() {
        searching = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
