package com.google.android.accessibility.ext.vdisplay

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import com.google.android.accessibility.uiautomation.util.HiddenApi
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 以 shell(uid 2000) / root 身份持有虚拟屏。
 *
 * 普通 App 建不了「可信虚拟屏」：带 TRUSTED / OWN_DISPLAY_GROUP / OWN_FOCUS 的屏需要
 * ADD_TRUSTED_DISPLAY，把第三方 App 启动到指定屏需要 INTERACT_ACROSS_USERS_FULL，
 * 输入注入需要 INJECT_EVENTS。Shizuku 的 UserService 以 shell/root 运行，这些权限都有。
 *
 * 隐藏接口一律反射调用，反射失败时退回到同等效果的 shell 命令。
 */
internal class VDisplayService {

    private val context: Context by lazy { ShellContext.create() }

    // 不能用 getSystemService：那样拿到的 DisplayManager 绑的是系统上下文，包名与 shell uid 对不上
    @get:SuppressLint("PrivateApi")
    private val displayManager: DisplayManager by lazy {
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
    }

    private val taskManager: Any by lazy {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)!!
    }

    private val displays = HashMap<Int, VirtualDisplay>()
    private val launchLock = Any()

    /** shell 所在用户（uid 2000 → 0）。虚拟屏和无障碍都在这个用户里，启动也固定到这一份。 */
    private val myUser = android.os.Process.myUid() / PER_USER_RANGE

    /** ILauncherApps（桌面点击图标背后的服务）；拿不到为 null。 */
    private val launcherApps: Any? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "launcherapps")
            Class.forName("android.content.pm.ILauncherApps\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        }.onFailure { Log.w(TAG, "launcherapps unavailable", it) }.getOrNull()
    }

    // ---------- 建屏 ----------

    fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int = try {
        // 宿主重启/重建屏时先清掉上一块：旧屏上的前台窗口（如分身选择器）不会跟着新任务残留
        releaseAll()
        createVirtualDisplay(surface, width, height, dpi, trusted = true)
    } catch (t: Throwable) {
        if (t !is SecurityException) {
            Log.e(TAG, "createDisplay failed", t)
            -1
        } else {
            // 没有 ADD_TRUSTED_DISPLAY 的系统：去掉只对可信屏可用的几项再建一次
            Log.w(TAG, "createDisplay without trusted flags, retrying", t)
            try {
                createVirtualDisplay(surface, width, height, dpi, trusted = false)
            } catch (t2: Throwable) {
                Log.e(TAG, "createDisplay failed", t2)
                -1
            }
        }
    }

    private fun createVirtualDisplay(surface: Surface, width: Int, height: Int, dpi: Int, trusted: Boolean): Int {
        HiddenApi.ensure()
        val flags = displayFlags(Build.VERSION.SDK_INT, trusted)
        val vd = displayManager.createVirtualDisplay(DISPLAY_NAME, width, height, dpi, surface, flags)
            ?: error("createVirtualDisplay returned null")
        val id = vd.display.displayId
        synchronized(displays) { displays[id] = vd }
        setImePolicy(id)
        if (DisplayAwake.needed(flags, FLAG_OWN_DISPLAY_GROUP, displayGroup(id))) awake.start(id)
        Log.i(TAG, "virtual display $id created ${width}x${height}@$dpi trusted=$trusted flags=$flags")
        return id
    }

    fun releaseDisplay(displayId: Int) {
        awake.stop(displayId)
        synchronized(displays) { displays.remove(displayId) }?.release()
        Log.i(TAG, "virtual display $displayId released")
    }

    fun releaseAll() {
        awake.stopAll()
        synchronized(displays) { displays.values.toList().also { displays.clear() } }.forEach { it.release() }
    }

    private val awake by lazy {
        val timer = Executors.newSingleThreadScheduledExecutor {
            Thread(it, "xpq-vdisplay-awake").apply { isDaemon = true }
        }
        DisplayAwake(::pokeDisplay) { r ->
            val f = timer.scheduleWithFixedDelay(r, 0, DisplayAwake.PERIOD_MS, TimeUnit.MILLISECONDS)
            return@DisplayAwake { f.cancel(false) }
        }
    }

    @get:SuppressLint("PrivateApi")
    private val userActivity: Pair<Any, Method>? by lazy {
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "power")
            val power = Class.forName("android.os.IPowerManager\$Stub")
                .getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)!!
            power to (DisplayAwake.userActivityMethod(power.javaClass) ?: error("no per-display userActivity"))
        }.onFailure { Log.w(TAG, "display keep-awake unavailable: ${it.message}") }.getOrNull()
    }

    private fun pokeDisplay(displayId: Int): Boolean {
        val (power, method) = userActivity ?: return false
        return try {
            method.invoke(power, displayId, SystemClock.uptimeMillis(), DisplayAwake.EVENT_TOUCH, 0)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "userActivity failed, display keep-awake off", t)
            false
        }
    }

    @SuppressLint("PrivateApi")
    private fun displayGroup(displayId: Int): Int? = runCatching {
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
            .getMethod("getInstance").invoke(null)!!
        val info = global.javaClass.getMethod("getDisplayInfo", Int::class.java).invoke(global, displayId)!!
        info.javaClass.getField("displayGroupId").getInt(info)
    }.getOrNull()

    fun ownDisplay(displayId: Int): Boolean = synchronized(displays) { displayId in displays }

    /**
     * 自动识别分身空间用户：shell 执行 `pm list users`，
     * 优先名字含「分身 / clone / dual」的非 0 用户，否则第一个非 0 用户；没有分身返回 0。
     */
    fun dualUserId(): Int {
        val (code, out) = sh("pm", "list", "users")
        if (code != 0) return myUser
        val users = Regex("UserInfo\\{([0-9]+):([^:}]+)").findAll(out)
            .map { it.groupValues[1].toInt() to it.groupValues[2] }
            .toList()
        val named = users.firstOrNull { (id, name) ->
            id != 0 && (name.contains("分身") || name.contains("clone", true) || name.contains("dual", true))
        }
        return (named ?: users.firstOrNull { it.first != 0 })?.first ?: myUser
    }

    // ---------- 启动 App ----------

    fun startApp(displayId: Int, packageName: String): Int = startApp(displayId, packageName, myUser)

    /**
     * 在 [userId] 这份用户里启动 [packageName] 到虚拟屏。
     * 主屏占用只看这一份用户；启动先走桌面路径（ILauncherApps，带 userId），再回退 ActivityTaskManager。
     */
    fun startApp(displayId: Int, packageName: String, userId: Int): Int = synchronized(launchLock) {
        if (!ownDisplay(displayId)) return START_ERROR
        try {
            if (inUseOnMainScreen(packageName, userId)) return START_IN_USE_ON_MAIN
        } catch (t: Throwable) {
            Log.e(TAG, "cannot inspect main-screen tasks", t)
            return START_ERROR
        }
        val intent = launchIntent(packageName, userId) ?: return START_NOT_FOUND
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        // 桌面路径：有的 ROM（如 ZUI 分身空间）在程序化启动多用户里都装的 App 时会弹
        // 「用哪一个打开」，桌面点击图标这条路不弹。先走它，失败再回退 ActivityManager。
        val component = intent.component
        if (component != null && startAsLauncher(component, userId, options)) return START_OK
        val ok = try {
            val start = taskManager.javaClass.methods.firstOrNull {
                it.name == "startActivityAsUser" && it.parameterTypes.size == 12
            } ?: error("no startActivityAsUser")
            val result = start.invoke(
                taskManager, null, ShellContext.SHELL_PACKAGE, null, intent, null, null, null,
                0, 0, null, options, userId,
            ) as Int
            if (result < 0) Log.w(TAG, "startActivityAsUser($packageName, user $userId) -> $result")
            result >= 0
        } catch (t: Throwable) {
            Log.w(TAG, "startActivityAsUser unavailable, falling back to am start", t)
            val component = intent.component?.flattenToShortString() ?: return START_NOT_FOUND
            exec("am", "start", "--display", "$displayId", "--user", "$userId", "-n", component) == 0
        }
        if (ok) START_OK else START_ERROR
    }

    /**
     * 像桌面那样打开 [component]：ILauncherApps.startActivityAsUser，带同样的 options（启动到虚拟屏）。
     * 系统那边按调用方查能不能碰这个用户：shell 有跨用户权限。接口不在或被拒返回 false。
     */
    private fun startAsLauncher(component: ComponentName, userId: Int, options: Bundle): Boolean = try {
        val la = launcherApps ?: throw IllegalStateException("no launcherapps service")
        val user = android.os.UserHandle.getUserHandleForUid(userId * PER_USER_RANGE)
        // 安卓 11 起：(caller, callingPackage, callingFeatureId, component, sourceBounds, opts, user)；更早没有 callingFeatureId
        val m = la.javaClass.methods.first {
            it.name == "startActivityAsUser" && it.parameterTypes.size in 6..7 &&
                ComponentName::class.java in it.parameterTypes
        }
        val args: Array<Any?> = if (m.parameterTypes.size == 7) {
            arrayOf(null, ShellContext.SHELL_PACKAGE, null, component, null, options, user)
        } else {
            arrayOf(null, ShellContext.SHELL_PACKAGE, component, null, options, user)
        }
        m.invoke(la, *args)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "launcher-style start of ${component.flattenToShortString()} failed", t)
        false
    }

    private fun launchIntent(packageName: String, userId: Int): Intent? {
        // 同一包在另一份用户里的启动组件通常相同；先用当前用户的入口，按 userId 启动
        if (userId != myUser) {
            context.packageManager.getLaunchIntentForPackage(packageName)?.let { return it }
        }
        return context.packageManager.getLaunchIntentForPackage(packageName)
            ?: run {
                val query = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(packageName)
                val found = runCatching {
                    context.packageManager.queryIntentActivities(Intent(query), 0)
                        .filterIsInstance<ResolveInfo>()
                        .map { it.activityInfo.packageName to it.activityInfo.name }
                }.getOrNull() ?: return null
                val (pkg, cls) = found.firstOrNull { it.first == packageName } ?: return null
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(ComponentName(pkg, cls))
            }
    }

    // ---------- 任务查询 ----------

    @SuppressLint("PrivateApi")
    private fun tasksOn(displayId: Int): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, displayId)
        } else {
            methods.firstOrNull { it.parameterTypes.size == 3 }?.invoke(taskManager, MAX_TASKS, false, false)
                ?: return emptyList()
        } as List<*>
        val displayField = TASK_INFO.getField("displayId")
        return tasks.filterNotNull().filter { displayField.getInt(it) == displayId }
    }

    @SuppressLint("PrivateApi")
    private fun allTasks(): List<Any> {
        val methods = taskManager.javaClass.methods.filter { it.name == "getTasks" }
        val withDisplay = methods.firstOrNull { it.parameterTypes.size == 4 }
        val tasks = if (withDisplay != null) {
            withDisplay.invoke(taskManager, MAX_TASKS, false, false, Display.INVALID_DISPLAY)
        } else {
            methods.firstOrNull { it.parameterTypes.size == 3 }?.invoke(taskManager, MAX_TASKS, false, false)
                ?: return emptyList()
        } as List<*>
        return tasks.filterNotNull()
    }

    private fun packagesOf(task: Any): List<String> = listOf("baseActivity", "topActivity").mapNotNull {
        (TASK_INFO.getField(it).get(task) as? ComponentName)?.packageName
    }

    @SuppressLint("PrivateApi")
    private fun inUseOnMainScreen(packageName: String, userId: Int): Boolean {
        val visibleField = runCatching { TASK_INFO.getField("isVisible") }.getOrNull()
        return tasksOn(Display.DEFAULT_DISPLAY).withIndex().any { (index, task) ->
            packageName in packagesOf(task) && taskUser(task) == userId &&
                (visibleField?.getBoolean(task) ?: (index == 0))
        }
    }

    private fun taskUser(task: Any): Int =
        runCatching { TASK_INFO.getField("userId").getInt(task) }.getOrDefault(myUser)

    fun topTaskId(displayId: Int): Int = try {
        tasksOn(displayId).firstOrNull()?.let { TASK_INFO.getField("taskId").getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "topTaskId failed", t)
        -1
    }

    fun displayOfTask(taskId: Int): Int = try {
        val idField = TASK_INFO.getField("taskId")
        val displayField = TASK_INFO.getField("displayId")
        allTasks().firstOrNull { idField.getInt(it) == taskId }?.let { displayField.getInt(it) } ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "displayOfTask failed", t)
        -1
    }

    // ---------- 输入注入 ----------

    fun injectTap(displayId: Int, x: Float, y: Float): Boolean {
        if (!ownDisplay(displayId)) return false
        HiddenApi.ensure()
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(now, now + TAP_MS, MotionEvent.ACTION_UP, x, y, 0)
        return try {
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            injectPair(down, up, displayId) {
                exec("input", "-d", "$displayId", "tap", "${x.toInt()}", "${y.toInt()}") == 0
            }
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    fun injectKey(displayId: Int, keyCode: Int): Boolean {
        if (!ownDisplay(displayId)) return false
        if (!allowedKey(keyCode)) {
            Log.w(TAG, "refused key $keyCode")
            return false
        }
        HiddenApi.ensure()
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
            VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP)
        return injectPair(down, up, displayId) { exec("input", "-d", "$displayId", "keyevent", "$keyCode") == 0 }
    }

    fun injectSwipe(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        if (!ownDisplay(displayId)) return false
        HiddenApi.ensure()
        val n = SWIPE_POINTS
        val total = durationMs.coerceIn(50L, 5_000L)
        val down = SystemClock.uptimeMillis()
        fun event(i: Int, action: Int): MotionEvent {
            val t = total * i / (n - 1)
            val x = x1 + (x2 - x1) * i / (n - 1)
            val y = y1 + (y2 - y1) * i / (n - 1)
            val props = MotionEvent.PointerProperties().apply {
                this.id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            val coords = MotionEvent.PointerCoords().apply {
                this.x = x; this.y = y; pressure = 1f; size = 1f
            }
            return MotionEvent.obtain(down, down + t, action, 1, arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        fun send(i: Int, action: Int): Boolean {
            val wait = down + total * i / (n - 1) - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            val e = event(i, action)
            return try { inject(e, displayId) } finally { e.recycle() }
        }
        if (!send(0, MotionEvent.ACTION_DOWN)) {
            return exec("input", "-d", "$displayId", "swipe",
                "${x1.toInt()}", "${y1.toInt()}", "${x2.toInt()}", "${y2.toInt()}", "$total") == 0
        }
        for (i in 1 until n - 1) send(i, MotionEvent.ACTION_MOVE)
        send(n - 1, MotionEvent.ACTION_UP)
        return true
    }

    /**
     * 按时间逐点注入一根手指的一笔（无障碍的 GestureDescription 转过来）：
     * 第一点按下、最后一点抬起，中间按 [times] 逐点移动；只发到虚拟屏。
     */
    fun injectGesture(displayId: Int, xs: IntArray, ys: IntArray, times: LongArray): Boolean {
        if (!ownDisplay(displayId)) return false
        val n = times.size
        if (n < 2 || xs.size != n || ys.size != n) return false
        if (times[0] != 0L || times.last() > GESTURE_MAX_MS) return false
        if ((1 until n).any { times[it] < times[it - 1] }) return false
        HiddenApi.ensure()
        val down = SystemClock.uptimeMillis()
        fun event(i: Int, action: Int): MotionEvent {
            val props = MotionEvent.PointerProperties().apply {
                this.id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            val coords = MotionEvent.PointerCoords().apply {
                this.x = xs[i].toFloat(); this.y = ys[i].toFloat(); pressure = 1f; size = 1f
            }
            return MotionEvent.obtain(
                down, down + times[i], action, 1, arrayOf(props), arrayOf(coords),
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
            )
        }
        fun send(i: Int, action: Int): Boolean {
            val wait = down + times[i] - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            val e = event(i, action)
            return try { inject(e, displayId) } finally { e.recycle() }
        }
        if (!send(0, MotionEvent.ACTION_DOWN)) {
            return exec("input", "-d", "$displayId", "swipe",
                "${xs[0]}", "${ys[0]}", "${xs[n - 1]}", "${ys[n - 1]}", "${times.last()}") == 0
        }
        for (i in 1 until n - 1) send(i, MotionEvent.ACTION_MOVE)
        send(n - 1, MotionEvent.ACTION_UP)
        return true
    }

    private inline fun injectPair(down: InputEvent, up: InputEvent, displayId: Int, fallback: () -> Boolean): Boolean {
        if (!inject(down, displayId)) return fallback()
        return inject(up, displayId) || inject(up, displayId)
    }

    private fun inject(event: InputEvent, displayId: Int): Boolean = try {
        val manager = runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
        }.getOrNull() ?: Class.forName("android.hardware.input.InputManager")
            .getMethod("getInstance").invoke(null)!!
        event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
        val mode = if (event is KeyEvent) INJECT_MODE_WAIT_FOR_RESULT else INJECT_MODE_ASYNC
        manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
            .invoke(manager, event, mode) as Boolean
    } catch (t: Throwable) {
        Log.w(TAG, "injectInputEvent failed", t)
        false
    }

    // ---------- 杂项 ----------

    @SuppressLint("PrivateApi")
    private fun setImePolicy(displayId: Int) {
        runCatching {
            val wm = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null)!!
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java)
                .invoke(wm, displayId, IME_POLICY_LOCAL)
        }.onFailure { Log.w(TAG, "setDisplayImePolicy failed", it) }
    }

    private fun exec(vararg cmd: String): Int = sh(*cmd).first

    /** 跑一条命令，返回 (退出码, 合并输出)；超时返回 -1。 */
    private fun sh(vararg cmd: String): Pair<Int, String> {
        val p = try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            Log.w(TAG, "exec ${cmd.joinToString(" ")} failed", t)
            return -1 to ""
        }
        val out = StringBuilder()
        val reader = Thread {
            runCatching { out.append(p.inputStream.bufferedReader().readText()) }
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(CMD_TIMEOUT_S, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            Log.w(TAG, "exec ${cmd.joinToString(" ")} timed out")
            return -1 to ""
        }
        reader.join(1_000)
        return p.exitValue() to out.toString()
    }

    companion object {
        private const val TAG = "XpqVDisplay"
        private const val DISPLAY_NAME = "xpq-agent"
        private const val MAX_TASKS = 100
        private const val TAP_MS = 50L
        private const val SWIPE_POINTS = 12
        private const val CMD_TIMEOUT_S = 10L
        private const val PER_USER_RANGE = 100_000
        private const val GESTURE_MAX_MS = 10_000L
        private const val VIRTUAL_KEYBOARD = -1
        private const val INJECT_MODE_ASYNC = 0
        private const val INJECT_MODE_WAIT_FOR_RESULT = 1
        private const val IME_POLICY_LOCAL = 0

        const val START_OK = 0
        const val START_IN_USE_ON_MAIN = 1
        const val START_NOT_FOUND = 2
        const val START_ERROR = 3

        private const val FLAG_PUBLIC = 1 shl 0
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val FLAG_TRUSTED = 1 shl 10
        private const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        private const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val FLAG_OWN_FOCUS = 1 shl 14
        private const val FLAG_STEAL_TOP_FOCUS_DISABLED = 1 shl 16

        private val TASK_INFO: Class<*> by lazy { Class.forName("android.app.TaskInfo") }

        internal fun displayFlags(sdk: Int, trusted: Boolean): Int {
            var flags = FLAG_PUBLIC or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
                FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
            if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                flags = flags or FLAG_TOUCH_FEEDBACK_DISABLED
                if (trusted) flags = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED
            }
            if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && trusted) {
                flags = flags or FLAG_OWN_FOCUS or FLAG_STEAL_TOP_FOCUS_DISABLED
            }
            return flags
        }

        private fun allowedKey(keyCode: Int): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_PASTE, KeyEvent.KEYCODE_COPY,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> true
            else -> keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ||
                keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z
        }
    }
}
