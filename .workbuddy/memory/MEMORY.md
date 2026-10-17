# XpqUtils 项目长期记忆

## 加固（加壳）与 Shizuku 兼容
- UserService **不能放关键路径**：app_process 拉起会加载宿主 dex 并创建宿主 Application，
  加固后 ①壳 Application 崩 ②真实 dex 加密 → 绑定永远超时。
- 免疫方案 = shell 通道（不碰宿主 dex）。`InvisibleAutomation.execViaNewProcess` 顺序：
  ① 官方 AIDL `IShizukuService.newProcess`（binder 由反射 `ShizukuProvider.getBinder()` 取）
  ② 反射 `Shizuku.newProcess` 兜底（private static，需 getDeclaredMethod）。
  `IRemoteProcess` 的流是 **ParcelFileDescriptor**，须 `AutoCloseInputStream` 包装后才能读。
- `UiAutomation.executeShellCommand` 走不通（自研 connection 只有 connect/disconnect 两个事务）。
  UiAutomation 注册在 **App 进程内**（registerUiTestAutomationService），与 UserService 无关。
- **bind 必须异步**；加固下 `timeoutMs` 只约束后台绑定，不等于连接耗时。回调经 MAIN_HANDLER = 主线程
  ⇒ `bind()` 禁止主线程调用。linkToDeath 只在 connected 后建立 ⇒ 「起来前就崩」不触发 died()，只能等超时。
- 加固检测以**行为判定**（UserService 能否绑定）为主，壳特征扫描只做日志不用于分支。
- shell 批量：**每次 exec 都 fork 一个 sh，无进程复用**；大头是 `input` 每次一个独立 ART 进程（50~150ms）。
  禁止循环逐条 exec，优先 `input` 自带批量能力。`tapNew()` 一次点击 fork 3~5 次同属此风险。

## 双通道（无障碍 / UiAutomation）
- 只有**取根**与**手势**通道相关，统一走 `XpqAcc.rootInActiveWindow()` / `dispatchGesture`；节点级 API 天然跨通道。
- UiAutomation 的事件靠**桥接**：反射宿主 manifest 中声明 BIND_ACCESSIBILITY_SERVICE 的子类手动 new →
  `setOnAccessibilityEventListener` → `dealEvent`。listener **单槽覆盖式**（宿主再 set 会顶掉桥接）。
  两模式下 packageNames 过滤实际都没生效。
- `currentService()` = `instance ?: proxyService`（**真实实例优先、与通道无关**），只决定返回哪个实例、
  **不改点击通道**。纪律：取根/点击一律走 `XpqAcc.*` 门面。
- UiAutomation 模式下**没有**真实无障碍实例（被登记的 `UiAutomationService` 活在 system_server 进程）；
  `proxyService` 是库内空壳，`context`/`resources` 会 NPE → 用 `LibCtxProvider.appContext`。
- **单槽互斥**：`driver` 是单个 `@Volatile` 字段，last-write-wins；`use()` 双向互踢。唯一支持的「双活」=
  Shizuku + 宿主无障碍在系统侧开着（MMKV `KEY_UIA_ENABLE_HOST_ACC`），代价 = 事件双跑。
  真优先级（`primary.takeIf{isConnected} ?: fallback`）**尚未实现**。
- **Service 生命周期回调在 UiAutomation 模式下静默**（系统只回调被 attach 的 Service）。补偿已实现：
  桥接成功后把 `uiAutomationComponentCallbacks`（**ComponentCallbacks**）注册到 Application（进程级）→
  转发给桥接实例；注销点 = `teardownUiAutomationBridge`（必须成对）。**只订阅 config，刻意不订阅 onTrimMemory**。
  转发的是服务方法本身 ⇒ 宿主已有 `onConfigurationChanged` override 零改动生效。
- `Application` **本来就会**收到 config / trim 事件（`ConfigurationController:225`、`ActivityThread:7390` 的
  `collectComponentCallbacks` 都含 `mAllApplications + mServices`；⚠ config 那条 `includeUiContexts=false`
  ⇒ **不含 Activity**）。所以库这条链路的唯一价值是「把事件送到**宿主服务实例**」。
  `Application` 三个回调都是 `@CallSuper`，宿主 override 却不调 super → 别人注册的回调收不到；
  `Service.onConfigurationChanged` 是空实现不读 mBase，手动 new 调 super 不会 NPE。

## 弹窗（XpqAcc 内的 AlertDialog）
- 一律 `Theme_DeviceDefault_Light_Dialog_Alert` + `decorView.isForceDarkAllowed = false`（API 29+）。
- **按钮文字色必须显式设** `0xFF212121`（MIUI/EMUI 会改浅色）；`getButton` 在 `show()` 前返回 null。
- `setView` 与 `setSingleChoiceItems` 可共存；`setMessage` 与列表互斥。
- 「开启随选朗读」开关（Shizuku 模式始终显示，取值 `KEY_SELECTTOSPEAK_ENABLE`）：未传 `enableServiceString`
  时可自由切换，传了则本行转**只读**（`isEnabled=false`，文字/颜色不变）。它开着时「同时开启无障碍服务」行隐藏、
  且其值**不写回 MMKV**，连接时被 `enableServiceOnConnect` 非空守卫短路（独占 vs 增量，互斥是有意的）。
- 无障碍模式「确定」按 `WRITE_SECURE_SETTINGS` 分流：有权限 → 后台开启 + 轮询等 `instance` 就绪
  （**超时也算失败**）；无权限/失败 → 跳系统设置引导。
- `showEngineModeDialog` 回调真实语义：`onUIASuccess`/`onUIAFail` 是**成功/失败**对称回调、**两种模式**都会触发；
  `onConfirm(mode)` 只在「无障碍模式（无条件，含失败）」与「Shizuku 模式**失败**」时触发（成功不触发）；
  `onCancel` 只认「取消」按钮。失败统一走局部 `notifyFail` 收口（别漏 failToast 那条）。
- **异步回调里弹窗必须先查 Activity 存活**（2026-10-17 定案）：`showUiAutomationFailDialog` 是在连接尝试**结束后**
  才被调用的（中间隔着 permission 弹窗 / UIA 连接 / 无障碍名单写入），此时宿主传进来的 Activity 可能已 `finish`
  或重建，`dialog.show()` 会抛 `WindowManager.BadTokenException: token ... is not valid; is your activity running?`，
  且抛在主线程回调里、外面无 try/catch ⇒ **直接崩应用**（趣安卓 8.0.47 实测崩溃栈就在这）。
  修法：方法开头 `if (activity.isFinishing || activity.isDestroyed) { AliveUtils.toast(...); return }`，
  并把 `dialog.show()` 包进 `runCatching`（检查与 show 之间仍有同帧竞态）→ 失败降级 toast。
  **凡是"捕获 Activity 后延迟使用"的地方都要照此办理**；`LibCtxProvider` 里注册的 `ActivityLifecycleCallbacks`
  目前是**空实现**（没有全局 current-activity 可回退），`RuntimeTracker` 只统计前后台时长、也不存实例。

## 内存回调在 API 34+
- `onLowMemory()` 源码注释明写 **"Since API level 34 this is never called"** → 死代码。
- `onTrimMemory` **只剩 `UI_HIDDEN`(20) 与 `BACKGROUND`(40)** 未废弃；且 `ActivityThread.handleTrimMemory`
  对前台型进程的 `level >= 40` **直接 return**。本项目可见面是悬浮窗而非 Activity → `UI_HIDDEN` 来得早。

## 无障碍「已启用名单」读写（已收口）
- **写入侧唯一实现 = `AccessibilityEnableHelper`**：`XpqAcc` 门面与 `XPQAccUtils.enable/disableAccessibilityServices`
  都只委托。写路径 = **「目标集合 + 名单只写一次」**（`enableAccessibilityServices(ctx, Collection, exclusive)` /
  `disableAccessibilityServices(ctx, Collection)`）——批量**不要**循环调单元素版，每次 shell exec 都 fork。
- **secure 名单** = 输入，可能含脏数据；解析唯一入口 `parseEnabledServices`（冒号分隔 + trim + 丢空项）。
  **AMS 列表** = 输出 = 「名单 ∩ 已安装」。
- 名单存 `flattenToString()` **长式**，`AccessibilityServiceInfo.getId()` 是 `flattenToShortString()` **短式** →
  不能直接字符串相等。归一化唯一入口 `canonicalComponent()`（解析失败原样保留），输出统一**长式**。
  读侧（`listEnabledAccessibilityServices` / `getEnabledServiceIds` / `getEnabledServiceIdsFromSecure`）都 map 过它，
  可直接互比；写侧入口就归一化，内部比较全走 `containsComponent` / `withoutComponent` —— 否则会重复追加、
  关不掉、独占回读误报残留。
- `getEnabledAccessibilityServiceList` 含通配分支 `|| feedbackType == FEEDBACK_ALL_MASK`(0xFFFFFFFF) →
  连 `feedbackType = 0` 也返回。⚠ **Android 14 才加**（13 及更早只有位与 → 静默漏）。
- 三道静默清空 AM 列表的闸：① `suppressingAccessibilityServicesLocked()`（注册 UiAutomation 且 connect flags
  未含 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`(0x1)；本库传了 0x1，不受影响）；
  ② `getServiceLocked()` 为 null（`AccessibilityManager.java:878`）；③ proxy device 走另一条实现。
- 严格版 `onlyWhiteAccessibilityIsEnabled` 用 **AMS 列表**判「size == 1」，不用 secure 名单（名单含卸载残留会失真）。
- 排查手法：AOSP **服务端**源码不在 SDK 里，用
  `raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/<branch>/services/accessibility/java/com/android/server/accessibility/*.java`
  （分支 `android-<ver>.0.0_r1`、`android14-release`、`main`）；逐版本比对可定位行为是哪一代引入的。

## Android 14+ `@Readable(maxTargetSdk)` 地雷
- targetSdk=37 下凡 `maxTargetSdk` 有限的 Secure 键**一律必炸**（检查在客户端进程内做）。
  踩中 `ENABLED_INPUT_METHODS`(33) → 改用 `InputMethodManager.getEnabledInputMethodList()`。
  同族受限：`DISABLED_SYSTEM_INPUT_METHODS`、`QS_TILES`(33)、`DATA_ROAMING`(32)。
  **不受限**：`DEFAULT_INPUT_METHOD`、`ACCESSIBILITY_ENABLED`、`ENABLED_ACCESSIBILITY_SERVICES`、
  `ENABLED_NOTIFICATION_LISTENERS`（读法不要顺手改）。
- 排查：`grep -B6 "public static final String <KEY> =" sources/android-36/.../Settings.java` 看注解
  （`sources/android-14` 那份缺注解，别用它判断）。

## `<queries>` 清单规则
- `<queries>` 内的 `<provider>` **按 authority 匹配**，`android:authorities` **必填**；写类名会 AAPT 报
  `missing required attribute 'android:authorities'`，即便补上也毫无作用。跨包可见性用 `<package>` 或 `<intent>`。
  本库**不需要** Shizuku provider 可见性声明（客户端走自己包的 provider，同包不过滤）。
- **改清单后必须跑到清单/资源阶段**：`compileDebugKotlin` 完全不碰清单，AAPT 错误要到
  `processDebugManifest` / `assembleDebug` 才暴露。

## 定位「Shizuku 管理器所在应用」（零宿主配置）
- manager 的 `moe.shizuku.manager.MainActivity` `exported=true` 但**无 intent-filter** → 任何
  `queryIntentActivities` 扫不到，`getLaunchIntentForPackage(宿主)` 只给宿主启动页。跳转**只能**靠显式 `ComponentName`。
- 但**包名可零配置反查**：manager 恒声明**字面量 action** `rikka.shizuku.intent.action.REQUEST_BINDER`
  （`ShellRequestHandlerActivity` + `BinderRequestReceiver`，均 exported）→ 谁声明谁就是管理器宿主。
  库自己的 `<queries>` 加这条 `<intent>`（+ CATEGORY_DEFAULT）即可看见，宿主无需改清单/写代码。
- `IShizukuService`（aidl 13.1.5）**没有**返回管理器包名的方法 → 问不到 server。
- `openShizuku` 顺序：本包 `Class.forName` 直开 → 探针反查包名 + 显式跳转 → 注册候选 + 官方包名 →
  兜底扫「带启动页的可见应用」逐个探组件 → 退到候选应用启动页。`addStandaloneShizukuPackage` 仅在
  「fork 把探针 action 也改掉」时才有用。

## 点击扩展函数的既有陷阱
- `gestureClick(node)`（GestureExt.kt:17）= HumanTouchEngine 拟人点击，**恒 `return true`**
  ⇒ `gestureClick(node) || node.click()` 里的 `node.click()` 是**不可达死代码**；`.takeIf { it }` 同理是摆设。
- ⇒ `clickMultipleByIdAndText` 返回值 ≈「是否走到过一轮点击」；`var result` 每轮覆盖 = 只反映**最后一轮**
  （想表达「全部成功」得写 `result && ok`）。形参 `gestureClick: Boolean` 与扩展函数**同名遮蔽**。
- `rawList` 只在最开始取一次：点击后界面重排 → 后续节点过期 → `copyNodeCompat` 失败被 `continue` **静默跳过**。
  连点多个元素应**每轮重新取根再查**。
- `clickByIdAndTextFilter` 两侧都 `default(filter = true) + removeTrailingNumber()`；
  `clickMultipleByIdAndText` 是**原样精确**比较（空白 → ""）。

## 新增同族函数的纪律
- 加同族点击函数时**先照抄已有那一个的骨架**：suspend 与否、参数形状（`startIndex`/`count`/`gestureClick`）、
  `copyNodeCompat → 点击 → recycleCompat`、每轮 `delay(500)`、返回最后一轮结果。
  ⚠ 反面教训：`clickMultipleByDesc` 第一版自创非 suspend 单点 + `index`/`MatchMode`，被否。
- 定位依据不同时只换数据源：desc 版 = `contentDescription.default() == desc`，**不看 text**；无系统 byDesc API，
  配套 private `findNodesByDesc`（前序遍历全树收集，未命中子树回收 child）。

## 悬浮窗权限（SYSTEM_ALERT_WINDOW，appops）
- `Settings.canDrawOverlays()` = 查 `OP_SYSTEM_ALERT_WINDOW` 的 appops 模式 ⇒ `pm grant` 无效（非 dangerous）、
  Secure 表无此键、无障碍/UiAutomation 无 `MANAGE_APP_OPS_MODES`。**唯一自动通道 = shell**：
  `appops set <pkg> SYSTEM_ALERT_WINDOW allow`（shell 持有 MANAGE_APP_OPS_MODES，`setMode` 对 S.A.W 无特殊拦截）。
- 实现 `OverlayPermissionHelper`（`ext/utils/safecheck/`）：已授权短路不 fork；回读用 `Settings.canDrawOverlays`
  而非再 fork `appops get`。⚠ `OP_SYSTEM_ALERT_WINDOW`(int) 是 `@hide`、`checkOpNoThrow(String,…)` 是 `@FlaggedApi`，都别用。
- `connectUiAutomation.finishConnect` 中**必须排在 `DynamicIslandFloatWindow.autoInit()` 之前**（autoInit 是
  `handler.post` 异步）；失败只记日志、不改连接结果。国产 ROM 可能另有开关 ⇒ `canDrawOverlays` 只是必要条件。

## 库内阻塞型能力的对外形态
- 凡内部含 shell / `SystemClock.sleep` / binder 连接的**阻塞**能力，库一律**自己切后台线程**
  （先例 = `XpqAcc.connectUiAutomation`）。做法：**保留同步版**（KDoc 标注阻塞）+ 新增
  `xxxAsync(time, onResult)`（`@JvmStatic @JvmOverloads`，`onResult` 经 `Handler(mainLooper).post` 回主线程）。
- 线程池**不借 `AppExecutors`**（共享**单线程**池，长 sleep 会堵住同池任务）：用独立
  `Executors.newSingleThreadExecutor` + daemon，隔离且天然串行。
- `AliveUtils.toast` 内部 `ContextCompat.getMainExecutor(context).execute{}` **自带切主线程** ⇒ 子线程随便调。
- `enableAccessibilityServiceOnly(..).success == false`（无 shell 也无 `WRITE_SECURE_SETTINGS`）时**立即返回**，
  不再白等 `time` 毫秒。

## 编辑工具纪律
- Edit 的 `old_string` **不要跨越到下一个成员声明**（带上下一个 `fun xxx(..) {` 当锚点却忘了写回 →
  会**静默删掉整个函数签名**）；定位优先用「目标块自身末尾几行」。同一文件多处修改**串行**、不同文件可并行。
