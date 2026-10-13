# XpqUtils 项目长期记忆

## 加固（加壳）与 Shizuku 兼容（2026-10-06 定案）
- UserService **不能放关键路径**：它用 app_process 拉起、会加载宿主 dex 并创建宿主 Application，
  加固后①壳 Application 无 UI 环境初始化崩 ②真实 dex 加密 → 绑定永远超时。
- 免疫方案 = shell 通道（不碰宿主 dex），`InvisibleAutomation.execViaNewProcess` 顺序：
  ① 官方 AIDL `IShizukuService.newProcess`（moe.shizuku.server 包，aidl 已传递依赖；binder 由反射
  `ShizukuProvider.getBinder()` 取）② 反射 `Shizuku.newProcess` 兜底（private static，需 getDeclaredMethod）。
  `IRemoteProcess` 的流是 **ParcelFileDescriptor**，须 `AutoCloseInputStream` 包装后才能读。
- `UiAutomation.executeShellCommand` 走不通：自研 `ProxyUiAutomationConnection` 只有 connect/disconnect 两个事务。
- UiAutomation 注册在 **App 进程内**完成（registerUiTestAutomationService），与 UserService 无关 → 它失败不中断连接。
- **bind 必须异步**（`bindUserServiceAsync()`）；加固下 `timeoutMs` 只约束后台绑定，**不等于连接耗时**。
- 回调经 MAIN_HANDLER 投递 = 主线程，`bind()` 禁止主线程调用（latch.await 会堵死回调）。
- linkToDeath 只在 connected 之后才建立 → 「起来前就崩」不触发 died()，只能等超时（服务端 30s 清理）。
- 加固检测：以**行为判定**（UserService 能否绑定）为主，无误判；壳特征扫描只做日志不用来分支；
  Application 类名比对仅命中「替换 android:name」的壳（需宿主一行声明）。
- shell 批量：**每次 exec 都 fork 一个 sh，没有进程复用**；真正耗时大头是 `input` 每次一个独立 ART 进程
  （50~150ms）。禁止循环逐条 exec，优先用 `input` 自身批量能力（`input keyevent k1 k2 …`，连发丢键待实测）。
  `tapNew()` 一次点击 fork 3~5 次同属此风险。

## 双通道（无障碍 / UiAutomation）
- 只有**取根**是通道相关的（无障碍 rootInActiveWindow / UiAutomation getRoot），统一走 `XpqAcc.rootInActiveWindow()`；
  节点级 API 天然跨通道；手势统一走 `XpqAcc.dispatchGesture`。
- 事件分发：无障碍靠系统回调；UiAutomation 靠**桥接** —— `autoBridgeAccessibilityEvent()` 反射宿主 manifest 中
  声明 BIND_ACCESSIBILITY_SERVICE 的子类并手动 new（fallback `SelectToSpeakService`）→
  `setOnAccessibilityEventListener` → `dealEvent` → `asyncHandle_XXX`。listener **单槽覆盖式**，
  宿主自己再 set 会顶掉桥接。两种模式下 packageNames 过滤实际都没生效（全包全类型）。
  `dealEvent` 的 WINDOW_STATE_CHANGED 有两道自带过滤：root 非 null + `root.packageName == event.packageName`。
- `currentService()`（2026-10-12 改）= `SelectToSpeakServiceAbstract.instance ?: proxyService`，**真实实例优先、
  与通道无关**。它只决定「返回哪个实例」，**不改点击通道**（手势仍由 driver 决定）。
  → 纪律：取根与点击一律走 `XpqAcc.*` 门面；`currentService()` 只用于需要真实 AccessibilityService 对象之处
  （context / serviceInfo / 事件分发）。
- UiAutomation 模式下**不存在**真实无障碍实例：被系统登记的 `UiAutomationService` 活在 **system_server 进程**。
  `proxyService` 是库内 new 的空壳（只 override getRootInActiveWindow/getWindows/findFocus；
  `performGlobalAction`/`dispatchGesture` 是 final 无法 override；`context`/`resources` 会 NPE → 用 LibCtxProvider.appContext）。
- **单槽互斥**：`driver` 是单个 `@Volatile` 字段，所有门面读它，**无优先级、last-write-wins**；`use()` 是双向互踢
  （→UIAUTOMATION 会 disableSelf()；→A11Y 会 teardownBridge + disconnect()）。
  唯一被支持的「双活」= Shizuku 模式 + 宿主无障碍在系统侧开着（现由 MMKV `KEY_UIA_ENABLE_HOST_ACC` 决定），
  代价 = **事件双跑**。想做真优先级须改门面透传层（`primary.takeIf{isConnected} ?: fallback`），**尚未实现**。

## 弹窗（XpqAcc 内的 AlertDialog）
- 一律传 `Theme_DeviceDefault_Light_Dialog_Alert` + `decorView.isForceDarkAllowed = false`（API 29+）。
- **按钮文字色必须显式设** `0xFF212121`（MIUI/EMUI 会改成浅色）；`getButton` 在 `show()` 前返回 null。
- `setView` 与 `setSingleChoiceItems` **可共存**（custom 面板在列表上方）；`setMessage` 与列表互斥。
- 「开启随选朗读」开关（仅 Shizuku 模式且**调用方未传 enableServiceString** 时显示，取值
  `KEY_SELECTTOSPEAK_ENABLE`）：true 等价于传 `SELECTTOSPEAK`。它开着时「同时开启无障碍服务」行隐藏，
  且其值**不写回 MMKV**（保留旧偏好），连接时被 `enableServiceOnConnect` 非空守卫短路 —— 两者语义冲突
  （独占「只留随选朗读」 vs 增量「额外加宿主服务」），互斥是有意的。
- 无障碍模式「确定」按 `WRITE_SECURE_SETTINGS` 分流：有权限 → 后台 `enableAccessibilityService` +
  轮询等 `instance` 就绪（**超时也算失败**）；无权限/失败 → 跳系统设置引导。重活在后台线程，不阻塞主线程。
- `showEngineModeDialog` 三个回调的真实语义（与名字不完全一致，改前先看）：`onUIASuccess`/`onUIAFail`
  是**成功/失败**对称回调，**两种模式**都会触发（无障碍的代码开启成功/失败同样走）；`onConfirm(mode)`
  只在「无障碍模式（无条件，含失败）」与「Shizuku 模式**失败**」时触发，**Shizuku 成功时不触发**；
  `onCancel` 只认「取消」按钮（点外部/返回键关闭不触发）。失败通知统一走局部 `notifyFail` 收口
  （handleModeResult 两个分支 + failToast 分支各一次），新增失败路径时别漏 failToast 那条。

## Android 14+ `@Readable(maxTargetSdk)` 地雷（2026-10-12 实测）
- 本项目 targetSdk=37，凡 `maxTargetSdk` 有限的 Secure 键**一律必炸**（检查在客户端进程内做）。
  踩中 `ENABLED_INPUT_METHODS`(33) → 改用 `InputMethodManager.getEnabledInputMethodList()`。
- 同族受限：`DISABLED_SYSTEM_INPUT_METHODS`、`QS_TILES`(33)、`DATA_ROAMING`(32)。
- **不受限**：`DEFAULT_INPUT_METHOD`、`ACCESSIBILITY_ENABLED`、`ENABLED_ACCESSIBILITY_SERVICES`、
  `ENABLED_NOTIFICATION_LISTENERS`（读法不要顺手改）。
- 排查手法：`grep -B6 "public static final String <KEY> =" sources/android-36/android/provider/Settings.java`
  看注解（`sources/android-14` 那份缺注解，别用它判断）。
