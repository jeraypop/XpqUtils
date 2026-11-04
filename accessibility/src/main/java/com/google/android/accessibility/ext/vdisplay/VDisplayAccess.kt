package com.google.android.accessibility.ext.vdisplay

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.os.IInterface
import android.util.Log
import android.view.InputEvent
import android.view.KeyEvent
import com.google.android.accessibility.uiautomation.engine.InvisibleAutomation
import com.google.android.accessibility.uiautomation.util.HiddenApi
import rikka.shizuku.ShizukuBinderWrapper
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * 虚拟屏特权访问来源。
 *
 * - [ShellVDisplayAccess]：跑在 Shizuku UserService（shell/root 进程）里，直接持有系统服务原始 binder。
 * - [ShizukuVDisplayAccess]：跑在 App 进程里，把系统服务 binder 换成 [ShizukuBinderWrapper]，
 *   由 Shizuku 服务端以 shell 身份 transact。宿主加固导致 UserService 起不来时走这条。
 *
 * 两种实现只负责“拿特权”，建屏/启动/注入的业务逻辑统一在 [VDisplayService] 里。
 */
internal interface VDisplayAccess {
    val context: Context

    /** 当前所在用户：0=机主空间。 */
    val myUser: Int

    /** 建屏用的 DisplayManager；两种实现拿到的实例都必须以 shell 身份工作。 */
    val displayManager: DisplayManager

    /** IActivityTaskManager；拿不到返回 null。 */
    fun activityTaskManager(): Any?

    /** ILauncherApps（桌面点击图标背后的服务）；拿不到返回 null。 */
    fun launcherApps(): Any?

    /** IPowerManager + 按屏 userActivity 方法；拿不到返回 null。 */
    fun powerManager(): Pair<Any, Method>?

    /** IWindowManager；拿不到返回 null。 */
    fun windowManager(): Any?

    /** 把事件注入到 [displayId] 这块屏。 */
    fun inject(event: InputEvent, displayId: Int): Boolean

    /** 以 shell 身份执行命令，返回 (退出码, 合并输出)。 */
    fun exec(vararg cmd: String): Pair<Int, String>

    /** 以 shell 身份写剪贴板；不支持时返回 false（调用方改走节点输入）。 */
    fun setClipText(text: String): Boolean

    companion object {
        const val PER_USER_RANGE = 100_000
        const val INJECT_MODE_ASYNC = 0
        const val INJECT_MODE_WAIT_FOR_RESULT = 1
        const val CMD_TIMEOUT_S = 10L
        const val TAG = "XpqVDisplay"

        /** ServiceManager 是隐藏类，统一走反射。 */
        fun rawService(name: String): IBinder? = runCatching {
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, name) as? IBinder
        }.getOrNull()

        /** 把系统服务 binder 包成接口对象；[wrap] 为 true 时以 shell 身份调用。 */
        fun serviceInterface(name: String, stubClass: String, wrap: Boolean): Any? = runCatching {
            val raw = rawService(name) ?: return@runCatching null
            val binder = if (wrap) ShizukuBinderWrapper(raw) else raw
            Class.forName(stubClass)
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }.onFailure { Log.w(TAG, "service $name unavailable", it) }.getOrNull()

        /** 把事件绑定到指定显示屏（隐藏方法 setDisplayId）。 */
        fun attachDisplay(event: InputEvent, displayId: Int) {
            runCatching {
                event.javaClass.getMethod("setDisplayId", Int::class.java).invoke(event, displayId)
            }
        }
    }
}

/** shell 进程内的特权访问：与改造前的 VDisplayService 行为一致。 */
internal class ShellVDisplayAccess : VDisplayAccess {

    override val context: Context by lazy { ShellContext.create() }

    override val myUser: Int = android.os.Process.myUid() / VDisplayAccess.PER_USER_RANGE

    // 不能用 getSystemService：那样拿到的 DisplayManager 绑的是系统上下文，包名与 shell uid 对不上
    @get:SuppressLint("PrivateApi")
    override val displayManager: DisplayManager by lazy {
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
    }

    @SuppressLint("PrivateApi")
    override fun activityTaskManager(): Any? = runCatching {
        Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
    }.onFailure { Log.w(VDisplayAccess.TAG, "ActivityTaskManager unavailable", it) }.getOrNull()

    override fun launcherApps(): Any? =
        VDisplayAccess.serviceInterface("launcherapps", "android.content.pm.ILauncherApps\$Stub", wrap = false)

    override fun powerManager(): Pair<Any, Method>? = runCatching {
        val power = VDisplayAccess.serviceInterface("power", "android.os.IPowerManager\$Stub", wrap = false)
            ?: error("no power service")
        power to (DisplayAwake.userActivityMethod(power.javaClass) ?: error("no per-display userActivity"))
    }.onFailure { Log.w(VDisplayAccess.TAG, "display keep-awake unavailable", it) }.getOrNull()

    override fun windowManager(): Any? = runCatching {
        Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getWindowManagerService").invoke(null)
    }.onFailure { Log.w(VDisplayAccess.TAG, "WindowManager unavailable", it) }.getOrNull()

    override fun inject(event: InputEvent, displayId: Int): Boolean = try {
        val manager = runCatching {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
        }.getOrNull() ?: Class.forName("android.hardware.input.InputManager")
            .getMethod("getInstance").invoke(null)!!
        VDisplayAccess.attachDisplay(event, displayId)
        val mode = if (event is KeyEvent) VDisplayAccess.INJECT_MODE_WAIT_FOR_RESULT
        else VDisplayAccess.INJECT_MODE_ASYNC
        manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.java)
            .invoke(manager, event, mode) as Boolean
    } catch (t: Throwable) {
        Log.w(VDisplayAccess.TAG, "injectInputEvent failed", t)
        false
    }

    override fun exec(vararg cmd: String): Pair<Int, String> {
        val p = try {
            ProcessBuilder(*cmd).redirectErrorStream(true).start()
        } catch (t: Throwable) {
            Log.w(VDisplayAccess.TAG, "exec ${cmd.joinToString(" ")} failed", t)
            return -1 to ""
        }
        val out = StringBuilder()
        val reader = Thread {
            runCatching { out.append(p.inputStream.bufferedReader().readText()) }
        }.apply { isDaemon = true; start() }
        if (!p.waitFor(VDisplayAccess.CMD_TIMEOUT_S, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            Log.w(VDisplayAccess.TAG, "exec ${cmd.joinToString(" ")} timed out")
            return -1 to ""
        }
        reader.join(1_000)
        return p.exitValue() to out.toString()
    }

    override fun setClipText(text: String): Boolean = runCatching {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("", text))
        true
    }.getOrDefault(false)
}

/**
 * App 进程内的特权访问：系统服务 binder 经 [ShizukuBinderWrapper] 以 shell 身份调用。
 *
 * 加固导致 Shizuku UserService 无法加载宿主 dex 时，用这条路径建屏/启动/注入。
 */
internal class ShizukuVDisplayAccess(private val appContext: Context) : VDisplayAccess {

    init {
        // App 进程默认受隐藏 API 限制，先把豁免装上再反射系统服务
        HiddenApi.ensure()
    }

    override val context: Context get() = appContext

    override val myUser: Int = android.os.Process.myUid() / VDisplayAccess.PER_USER_RANGE

    /**
     * 建屏上下文伪装成 shell 包名：
     * binder 以 shell(uid 2000) 身份 transact，系统会核对「packageName 属于调用方 uid」，
     * 用 App 自己的包名会抛 `packageName must match the calling uid` 而建屏失败。
     */
    private val shellPackageContext: Context by lazy {
        object : ContextWrapper(appContext) {
            override fun getPackageName(): String = ShellContext.SHELL_PACKAGE
            override fun getOpPackageName(): String = ShellContext.SHELL_PACKAGE
            override fun getApplicationContext(): Context = this
        }
    }

    override val displayManager: DisplayManager by lazy {
        patchDisplayManagerGlobal()
        HiddenApi.ensure()
        // 不能用 appContext.getSystemService：那样拿到的 DisplayManager 绑的是 App 包名
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(shellPackageContext)
            ?: error("no DisplayManager")
    }

    override fun activityTaskManager(): Any? =
        VDisplayAccess.serviceInterface("activity", "android.app.IActivityTaskManager\$Stub", wrap = true)

    override fun launcherApps(): Any? =
        VDisplayAccess.serviceInterface("launcherapps", "android.content.pm.ILauncherApps\$Stub", wrap = true)

    override fun powerManager(): Pair<Any, Method>? = runCatching {
        val power = VDisplayAccess.serviceInterface("power", "android.os.IPowerManager\$Stub", wrap = true)
            ?: error("no power service")
        power to (DisplayAwake.userActivityMethod(power.javaClass) ?: error("no per-display userActivity"))
    }.onFailure { Log.w(VDisplayAccess.TAG, "display keep-awake unavailable", it) }.getOrNull()

    override fun windowManager(): Any? =
        VDisplayAccess.serviceInterface("window", "android.view.IWindowManager\$Stub", wrap = true)

    override fun inject(event: InputEvent, displayId: Int): Boolean = try {
        VDisplayAccess.attachDisplay(event, displayId)
        val manager = VDisplayAccess.serviceInterface(
            "input", "android.hardware.input.IInputManager\$Stub", wrap = true
        ) ?: return false
        val candidates = manager.javaClass.methods.filter {
            it.name == "injectInputEvent" && it.parameterTypes.isNotEmpty() &&
                it.parameterTypes[0] == InputEvent::class.java
        }
        val method = candidates.firstOrNull { it.parameterTypes.size == 2 }
            ?: candidates.firstOrNull { it.parameterTypes.size == 3 && it.parameterTypes[2].isPrimitive }
            ?: candidates.minByOrNull { it.parameterTypes.size }
            ?: return false
        val args = arrayOfNulls<Any>(method.parameterTypes.size)
        args[0] = event
        if (method.parameterTypes.size > 1 && method.parameterTypes[1].isPrimitive) {
            args[1] = if (event is KeyEvent) VDisplayAccess.INJECT_MODE_WAIT_FOR_RESULT
            else VDisplayAccess.INJECT_MODE_ASYNC
        }
        for (i in 2 until args.size) {
            args[i] = if (method.parameterTypes[i].isPrimitive) 0 else null
        }
        method.invoke(manager, *args) as? Boolean ?: false
    } catch (t: Throwable) {
        Log.w(VDisplayAccess.TAG, "shell injectInputEvent failed", t)
        false
    }

    override fun exec(vararg cmd: String): Pair<Int, String> {
        // newProcess 不加载宿主 APK 的 dex/Application，加固环境下仍然可用
        val command = cmd.joinToString(" ") { arg ->
            if (' ' in arg) "'${arg.replace("'", "'\\''")}'" else arg
        }
        val result = InvisibleAutomation.exec(command) ?: return -1 to ""
        return result.exitCode to (result.stdout + result.stderr)
    }

    override fun setClipText(text: String): Boolean = runCatching {
        // 加固/后台场景下 App 写剪贴板可能被系统拦；失败时调用方回退节点输入
        val cm = appContext.getSystemService(ClipboardManager::class.java) ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("", text))
        true
    }.getOrDefault(false)

    /** 把 DisplayManagerGlobal 单例里的 IDisplayManager 换成 shell 身份的包装 binder。 */
    @SuppressLint("PrivateApi")
    @Synchronized
    private fun patchDisplayManagerGlobal() {
        if (patched) return
        HiddenApi.ensure()
        val globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val global = globalClass.getMethod("getInstance").invoke(null)
            ?: error("no DisplayManagerGlobal")
        val field = globalClass.declaredFields.firstOrNull {
            it.type.name == "android.hardware.display.IDisplayManager"
        } ?: error("IDisplayManager field not found")
        field.isAccessible = true
        clearFinal(field)
        val current = field.get(global) as? IInterface ?: error("IDisplayManager is null")
        val wrapped = ShizukuBinderWrapper(current.asBinder())
        val iface = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, wrapped)
        try {
            field.set(global, iface)
        } catch (t: Throwable) {
            // 部分 ROM 的字段仍被当作 final：用 Unsafe 直接写
            val written = runCatching {
                val unsafeClass = Class.forName("sun.misc.Unsafe")
                val unsafe = unsafeClass.getDeclaredField("theUnsafe")
                    .apply { isAccessible = true }.get(null)
                val offset = unsafeClass.getMethod("objectFieldOffset", java.lang.reflect.Field::class.java)
                    .invoke(unsafe, field) as Long
                unsafeClass.getMethod(
                    "putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java
                ).invoke(unsafe, global, offset, iface)
                true
            }.getOrDefault(false)
            if (!written) throw t
        }
        patched = true
        Log.i(VDisplayAccess.TAG, "DisplayManagerGlobal patched to shell binder")
    }

    private companion object {
        @Volatile
        var patched = false

        /** 部分版本 mDm 是 final，反射赋值前先去掉 FINAL 标记。 */
        fun clearFinal(field: java.lang.reflect.Field) {
            runCatching {
                val flags = java.lang.reflect.Field::class.java.getDeclaredField("accessFlags")
                flags.isAccessible = true
                flags.setInt(field, field.modifiers and java.lang.reflect.Modifier.FINAL.inv())
            }.onFailure { Log.w(VDisplayAccess.TAG, "clear final failed", it) }
        }
    }
}
