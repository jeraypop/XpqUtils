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

    // ---- 虚拟屏：以 shell 身份建可信副屏、把 App 启动到副屏、按屏注入 ----
    // 用宿主的 Surface 建一块虚拟屏；失败返回 -1
    int createDisplay(in android.view.Surface surface, int width, int height, int dpi) = 6;
    void releaseDisplay(int displayId) = 7;
    // 0=已启动 1=用户正在主屏使用该 App(不启动) 2=没有启动入口 3=出错
    int startAppOnDisplay(int displayId, String packageName) = 8;
    boolean injectTapOnDisplay(int displayId, float x, float y) = 9;
    boolean injectKeyOnDisplay(int displayId, int keyCode) = 10;
    boolean injectSwipeOnDisplay(int displayId, float x1, float y1, float x2, float y2, long durationMs) = 11;
    int topTaskIdOnDisplay(int displayId) = 12;
    int displayOfTask(int taskId) = 13;
    // 按时间逐点注入一根手指的一笔（点击/滑动/曲线）：第一点按下、最后一点抬起；times[i] 相对按下毫秒
    boolean injectGesture(int displayId, in int[] xs, in int[] ys, in long[] times) = 14;
    // 在指定用户（分身空间）里启动该包到虚拟屏；返回值同 startAppOnDisplay
    int startAppInUser(int displayId, String packageName, int userId) = 15;

    void destroy() = 16777114; // Shizuku server 定义的销毁方法
    void exit() = 1;           // 用户自定义的退出方法
}
