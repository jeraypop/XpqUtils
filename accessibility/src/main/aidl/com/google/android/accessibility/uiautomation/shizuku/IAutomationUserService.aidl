package com.google.android.accessibility.uiautomation.shizuku;

import com.google.android.accessibility.uiautomation.shizuku.ShellResult;
import android.os.IBinder;

/**
 * 运行在 shell(uid 2000) 下的自动化特权服务接口。
 *
 * 由 Shizuku 以 shell 身份启动，因此：
 *  - 可以调用隐藏 API AccessibilityManager.registerUiTestAutomationService
 *    （普通 App 调用会抛 SecurityException）；
 *  - 可以注入输入事件、执行 input 命令等。
 *
 * App 进程只持有 UiAutomation，并把“注册 / 反注册”等特权操作转发到这里执行。
 */
interface IAutomationUserService {
    ShellResult exec(String command) = 2;

    /** 以 shell 身份向系统注册一个 UiTestAutomationService。token 用本服务自身 binder。 */
    void registerUiAutomation(IBinder token, IBinder client, int flags) = 3;

    /** 反注册（对应 IUiAutomationConnection.disconnect / shutdown）。 */
    void unregisterUiAutomation(IBinder client) = 4;

    /**
     * 以 shell 身份反射 InputManager.injectInputEvent 注入一次带自定义 pressure/size 的点击。
     * 用于验证「压力波动 ≈ 0」反注入规则是否可被更高阶注入绕过（pressure 由注入方伪造）。
     */
    boolean injectTap(float x, float y, long durationMs, float pressureDown, float pressureUp, float sizeDown, float sizeUp) = 5;

    void destroy() = 16777114; // Shizuku server 定义的销毁方法
    void exit() = 1;           // 用户自定义的退出方法
}
