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
internal class VDisplayService(private val access: VDisplayAccess) {

    private val context: Context get() = access.context

    private val displayManager: DisplayManager get() = access.displayManager

    private val taskManager: Any by lazy {
        access.activityTaskManager() ?: error("ActivityTaskManager unavailable")
    }

    private val displays = HashMap<Int, VirtualDisplay>()
    private val launchLock = Any()

    /** 当前所在用户（shell 进程为 0）：虚拟屏和无障碍都在这个用户里，启动也固定到这一份。 */
    private val myUser: Int = access.myUser

    /** ILauncherApps（桌面点击图标背后的服务）；拿不到为 null。 */
    private val launcherApps: Any? by lazy { access.launcherApps() }

    // ---------- 建屏 ----------

    /** 最近一次建屏失败的「异常类型 + 原因」，供 binder 后端把真实原因带到任务日志里。 */
    @Volatile
    var lastFailure: String? = null
        private set

    fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int = try {
        // 宿主重启/重建屏时先清掉上一块：旧屏上的前台窗口（如分身选择器）不会跟着新任务残留
        releaseAll()
        createVirtualDisplay(surface, width, height, dpi, trusted = true)
    } catch (t: Throwable) {
        if (t !is SecurityException) {
            lastFailure = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "createDisplay failed", t)
            -1
        } else {
            // 没有 ADD_TRUSTED_DISPLAY 的系统：去掉只对可信屏可用的几项再建一次
            Log.w(TAG, "createDisplay without trusted flags, retrying", t)
            try {
                createVirtualDisplay(surface, width, height, dpi, trusted = false)
            } catch (t2: Throwable) {
                lastFailure = "${t2.javaClass.simpleName}: ${t2.message}"
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
        lastFailure = null
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
        access.powerManager().also { if (it == null) Log.w(TAG, "display keep-awake unavailable") }
    }

    @Volatile
    private var pokeFailureLogged = false

    @Volatile
    private var pokeSuccessLogged = false

    /**
     * 替虚拟屏报一次用户操作。新屏的电源组还没注册完时系统会抛 NPE，这类失败是瞬时的：
     * 继续按周期重试，只有整组接口都拿不到时才放弃（返回 false 会让 [DisplayAwake] 停止保活）。
     */
    private fun pokeDisplay(displayId: Int): Boolean {
        val (power, method) = userActivity ?: return false
        return try {
            method.invoke(power, displayId, SystemClock.uptimeMillis(), DisplayAwake.EVENT_TOUCH, 0)
            if (!pokeSuccessLogged) {
                pokeSuccessLogged = true
                Log.i(TAG, "display keep-awake active")
            }
            true
        } catch (t: Throwable) {
            if (!pokeFailureLogged) {
                pokeFailureLogged = true
                Log.w(TAG, "userActivity failed, will retry", t)
            }
            true
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

    /** 以 shell 身份写系统剪贴板：锁屏/App 后台时，App 自己写剪贴板可能被系统限制。 */
    fun setClipText(text: String): Boolean = access.setClipText(text)

    /**
     * 自动识别分身空间用户：shell 执行 `pm list users`，
     * 优先名字含「分身 / clone / dual」的非 0 用户，否则第一个非 0 用户；没有分身返回 0。
     */
    fun dualUserId(): Int {
        val users = nonCurrentUsers()
        val named = users.firstOrNull { (_, name) -> name.isDualName() }
        return (named ?: users.firstOrNull())?.first ?: myUser
    }

    /**
     * 自动识别「装了 [packageName] 的分身空间用户」：优先名字含「分身 / clone / dual」的用户，
     * 否则取第一个装了该包的非 0 用户；都没有返回 0（机主）。
     * 用于避免把工作资料/访客用户误当成分身空间。
     */
    fun dualUserIdForPackage(packageName: String): Int {
        val users = nonCurrentUsers()
        val named = users.filter { (_, name) -> name.isDualName() }
        val rest = users.filterNot { (_, name) -> name.isDualName() }
        return (named + rest).firstOrNull { (id, _) -> packageInstalled(packageName, id) }?.first
            ?: myUser
    }

    private fun nonCurrentUsers(): List<Pair<Int, String>> {
        val (code, out) = access.exec("pm", "list", "users")
        if (code != 0) return emptyList()
        return Regex("UserInfo\\{([0-9]+):([^:}]+)").findAll(out)
            .map { it.groupValues[1].toInt() to it.groupValues[2] }
            .filter { it.first != myUser }
            .toList()
    }

    private fun packageInstalled(packageName: String, userId: Int): Boolean {
        val (code, out) = access.exec("pm", "list", "packages", "--user", userId.toString())
        return code == 0 && out.lineSequence().any { it.trim().removePrefix("package:") == packageName }
    }

    private fun String.isDualName(): Boolean =
        contains("分身") || contains("clone", true) || contains("dual", true)

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
        // 同用户、同包里已有任务（主屏后台里的微信等）时，系统会把那个任务整块挪到副屏，
        // 于是保持原来的聊天页而不是首页。启动前先结束这些旧任务，保证从首页开始。
        val ended = endTasksOfPackage(packageName, userId, displayId)
        if (ended > 0) {
            Log.i(TAG, "closed $ended existing $packageName task(s) before launching on display $displayId")
        }
        // 桌面入口本身就是该包的 LauncherUI；CLEAR_TOP 清掉聊天页等上层 Activity，回到微信首页
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()
        val component = intent.component
        // 启动方法返回成功不代表真的开在目标用户里：有的 ROM 会忽略 user，实际拉起机主那份。
        // 每次启动后都核对这块屏上的任务确实是「这个包 + 这个用户」，不对就换下一种方式。
        fun viaLauncher(): Boolean {
            if (component == null || !startAsLauncher(component, userId, options)) return false
            // 查不到任务时(null)按启动成功处理，避免在拿不到任务列表的系统上误判失败
            return waitTaskOnDisplay(displayId, packageName, userId) ?: true
        }

        fun viaTaskManager(): Boolean {
            val accepted = try {
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
                Log.w(TAG, "startActivityAsUser unavailable", t)
                false
            }
            return accepted && (waitTaskOnDisplay(displayId, packageName, userId) ?: true)
        }

        fun viaShellCommand(): Boolean {
            val c = component?.flattenToShortString() ?: return false
            // --activity-clear-top：已有任务时清掉 LauncherUI 上面的聊天页，回到首页
            val code = exec("am", "start", "--display", "$displayId", "--user", "$userId",
                "--activity-clear-top", "-n", c)
            if (code != 0) return false
            return waitTaskOnDisplay(displayId, packageName, userId) ?: true
        }

        // 桌面路径：有的 ROM（如 ZUI 分身空间）在程序化启动多用户里都装的 App 时会弹
        // 「用哪一个打开」，桌面点击图标这条路不弹。先走它，再回退 ActivityManager / am start。
        if (viaLauncher() || viaTaskManager() || viaShellCommand()) return START_OK
        Log.w(TAG, "launch($packageName, user $userId) did not create that user's task on display $displayId")
        START_USER_MISMATCH
    }

    /**
     * 结束「[packageName] + [userId]」在其他显示上的任务（不含 [displayId] 这块副屏自己）。
     *
     * 系统按 taskAffinity 复用任务：主屏后台里已经开着的同包任务会被整块挪到副屏，
     * 页面状态（聊天页等）也跟着带过去，导致任务不在首页。启动前先清掉这些旧任务；
     * 副屏自己的任务不动，避免刚启动好又拆一次。
     */
    @SuppressLint("PrivateApi")
    private fun endTasksOfPackage(packageName: String, userId: Int, displayId: Int): Int = try {
        val idField = TASK_INFO.getField("taskId")
        val displayField = TASK_INFO.getField("displayId")
        val victims = allTasks()
            .filter { packageName in packagesOf(it) && taskUser(it) == userId }
            .filter { runCatching { displayField.getInt(it) != displayId }.getOrDefault(true) }
            .mapNotNull { runCatching { idField.getInt(it) }.getOrNull() }
        if (victims.isEmpty()) {
            0
        } else {
            victims.forEach(::removeTaskBinder)
            repeat(10) { attempt ->
                val remaining = allTasks().any { task ->
                    packageName in packagesOf(task) && taskUser(task) == userId &&
                        runCatching { displayField.getInt(task) != displayId }.getOrDefault(true)
                }
                if (!remaining) return victims.size
                if (attempt < 9) SystemClock.sleep(100)
            }
            victims.size
        }
    } catch (t: Throwable) {
        Log.w(TAG, "endTasksOfPackage($packageName, user $userId) failed", t)
        0
    }

    /** 结束这块屏上的所有任务（任意用户），等它们真的消失；用于释放旧屏前清场。 */
    fun endTasksOnDisplay(displayId: Int): Boolean {
        if (!ownDisplay(displayId)) return false
        val ids = taskIdsOnDisplay(displayId)
        if (ids.isEmpty()) return true
        ids.forEach(::removeTaskBinder)
        if (awaitNoTasks(displayId)) return true
        taskIdsOnDisplay(displayId).forEach {
            exec("am", "task", "remove", it.toString())
        }
        return awaitNoTasks(displayId)
    }

    @SuppressLint("PrivateApi")
    private fun taskIdsOnDisplay(displayId: Int): List<Int> = try {
        val idField = TASK_INFO.getField("taskId")
        tasksOn(displayId).mapNotNull { runCatching { idField.getInt(it) }.getOrNull() }
    } catch (t: Throwable) {
        Log.w(TAG, "taskIdsOnDisplay failed", t)
        emptyList()
    }

    private fun awaitNoTasks(displayId: Int): Boolean {
        repeat(10) { attempt ->
            if (taskIdsOnDisplay(displayId).isEmpty()) return true
            if (attempt < 9) SystemClock.sleep(100)
        }
        return false
    }

    @SuppressLint("PrivateApi")
    private fun removeTaskBinder(taskId: Int) {
        try {
            val m = taskManager.javaClass.methods.firstOrNull {
                it.name == "removeTask" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: return
            m.invoke(taskManager, taskId)
        } catch (t: Throwable) {
            Log.w(TAG, "removeTask($taskId) failed", t)
        }
    }

    /**
     * 启动后等这块屏上出现「[packageName] + [userId]」的任务；有的 ROM 建任务有延迟，最多等约 1.5 秒。
     * 任务列表根本查不到时返回 null（无法核对），调用方按启动成功处理。
     */
    @SuppressLint("PrivateApi")
    private fun waitTaskOnDisplay(displayId: Int, packageName: String, userId: Int): Boolean? = try {
        repeat(10) { attempt ->
            if (tasksOn(displayId).any { packageName in packagesOf(it) && taskUser(it) == userId }) return true
            if (attempt < 9) SystemClock.sleep(150)
        }
        false
    } catch (t: Throwable) {
        Log.w(TAG, "cannot verify task user on display $displayId", t)
        null
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

    fun packageOfTask(taskId: Int): String = try {
        val idField = TASK_INFO.getField("taskId")
        allTasks().firstOrNull { idField.getInt(it) == taskId }?.let { packagesOf(it).firstOrNull() } ?: ""
    } catch (t: Throwable) {
        Log.e(TAG, "packageOfTask failed", t)
        ""
    }

    fun userIdOfTask(taskId: Int): Int = try {
        val idField = TASK_INFO.getField("taskId")
        allTasks().firstOrNull { idField.getInt(it) == taskId }?.let(::taskUser) ?: -1
    } catch (t: Throwable) {
        Log.e(TAG, "userIdOfTask failed", t)
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
        /**
         * [extraDelay] 是整笔滑动相对原始时间轴的额外前置延迟。
         * 注意必须把它加在**等待目标**上，而不是发完 DOWN 后单独 sleep：
         * 否则后续各点的等待会变成负数，整笔滑动被瞬间灌进去，容易被当成点击。
         */
        fun send(i: Int, action: Int, extraDelay: Long = 0L): Boolean {
            val wait = down + extraDelay + times[i] - SystemClock.uptimeMillis()
            if (wait > 0) SystemClock.sleep(wait)
            val e = event(i, action)
            return try {
                // 单点注入偶发被系统丢弃（尤其异步模式）：失败立刻补发一次
                inject(e, displayId) || inject(e, displayId)
            } finally {
                e.recycle()
            }
        }
        if (!send(0, MotionEvent.ACTION_DOWN)) {
            return exec("input", "-d", "$displayId", "swipe",
                "${xs[0]}", "${ys[0]}", "${xs[n - 1]}", "${ys[n - 1]}", "${times.last()}") == 0
        }
        // DOWN 之后先停一拍再开始 MOVE：让窗口进入拖拽状态，同时保持各点之间原有的时间间隔
        var moved = true
        for (i in 1 until n - 1) {
            if (!send(i, MotionEvent.ACTION_MOVE, FIRST_MOVE_HOLD_MS)) moved = false
        }
        val upOk = send(n - 1, MotionEvent.ACTION_UP, FIRST_MOVE_HOLD_MS)
        // UP 没发出去时界面会停在「按下去」的状态，必须让调用方知道这次手势不算成功
        return upOk && moved
    }

    private inline fun injectPair(down: InputEvent, up: InputEvent, displayId: Int, fallback: () -> Boolean): Boolean {
        if (!inject(down, displayId)) return fallback()
        return inject(up, displayId) || inject(up, displayId)
    }

    private fun inject(event: InputEvent, displayId: Int): Boolean = access.inject(event, displayId)

    // ---------- 杂项 ----------

    @SuppressLint("PrivateApi")
    private fun setImePolicy(displayId: Int) {
        runCatching {
            val wm = access.windowManager() ?: error("no WindowManager")
            wm.javaClass.getMethod("setDisplayImePolicy", Int::class.java, Int::class.java)
                .invoke(wm, displayId, IME_POLICY_LOCAL)
        }.onFailure { Log.w(TAG, "setDisplayImePolicy failed", it) }
    }

    private fun exec(vararg cmd: String): Int = access.exec(*cmd).first

    companion object {
        private const val TAG = "XpqVDisplay"
        private const val DISPLAY_NAME = "xpq-agent"
        private const val MAX_TASKS = 100
        private const val TAP_MS = 50L
        private const val FIRST_MOVE_HOLD_MS = 40L
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
        /** 系统没在目标用户里建任务（实际可能拉起了另一个版本）。 */
        const val START_USER_MISMATCH = 4

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
