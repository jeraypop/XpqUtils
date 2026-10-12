# XpqUtils 项目长期记忆

## 加固（加壳）与 Shizuku 的兼容约束（2026-10-06 定案）
- **绝不能把 Shizuku UserService 放在关键路径上**。UserService 子进程由 Shizuku 服务端用
  `app_process` 拉起，它会**加载宿主 APK 的 dex 并创建宿主的 Application**。宿主加固/加壳后：
  ①壳 Application 在无 UI 的 app_process 环境初始化崩溃；②真实 dex 被加密 → ClassNotFoundException。
  两者都表现为 `bindUserService` 永远握不上手 → 超时 → `bind()` 返回 null。
- **加固免疫的替代（shell 通道）**：由 Shizuku 服务端以 shell 身份 fork 命令，不碰宿主 dex。
  实现顺序（`InvisibleAutomation.execViaNewProcess`）：
  ① **官方 AIDL 优先**：`IShizukuService.newProcess(String[],String[],String) → IRemoteProcess`
     （`moe.shizuku.server` 包，由 `dev.rikka.shizuku:aidl` 提供，api 的 pom 已 compile 传递，无需额外声明）。
     binder 来源：反射 `ShizukuProvider.getBinder()`（编译期 Unresolved，非 public）→ `IShizukuService.Stub.asInterface`；
     取不到时再反射 `Shizuku.requireService()`。
     **注意 `IRemoteProcess.getInputStream()/getErrorStream()` 返回 ParcelFileDescriptor**，
     要 `ParcelFileDescriptor.AutoCloseInputStream(pfd)` 包装后才能读。
  ② 反射 `Shizuku.newProcess` 兜底（它在新版 api 里是 **private static**，直接调用编译报
     `it is private in 'rikka.shizuku.Shizuku'`，须 `getDeclaredMethod + isAccessible`）。
- **`UiAutomation.executeShellCommand` 在本库走不通**：它需要真实的 UiAutomationConnection
  （AOSP 在 system_server 侧执行）。本库的 connection 是自研 `ProxyUiAutomationConnection`，
  只实现了 connect(=FIRST_CALL_TRANSACTION)/disconnect(+1) 两个事务，没有 executeShellCommand 分支。
- **UiAutomation 注册与 UserService 无关**：注册在 App 进程内完成
  （`ProxyUiAutomationConnection.handleConnect` → `ShizukuBinderWrapper(SystemServiceHelper.getSystemService("accessibility"))`
  → `registerUiTestAutomationService`），所以 UserService 失败**不应中断连接**。
- 结论：`AutomationUserService` 只是「可复用进程 + 自定 pressure 注入」的增强项；
  它不可用时统一降级——shell 走 `Shizuku.newProcess`，tap 反射注入回退 `input swipe`。
- **bind 必须异步，不能摆在连接关键路径上**（二次定案）：原先 `connect(timeoutMs=15_000)` 把
  timeoutMs 直接传给了 `AutomationShizuku.bind`，所以加固下"降级生效"的同时，**首次连接仍要白等满
  15s** 才触发降级。现改为 `InvisibleAutomation.bindUserServiceAsync()`：后台线程绑定，连接主流程
  不等；成功 → 填充 `mSvc`；失败 → 置 `userServiceUnavailable`（进程内记忆，后续连接直接跳过）。
  另外 `AutomationShizuku.isUserServiceBound()` 提供零阻塞的复用判定。注意 `timeoutMs` 现在只约束
  后台绑定，**不再等于连接耗时**。

## 双通道（无障碍 / UiAutomation）适配规则
- 只有「取根」是通道相关的：无障碍 `service.rootInActiveWindow`，UiAutomation 走
  `InvisibleAutomation.getRoot()`。统一用 `XpqAcc.rootInActiveWindow()`。
- 节点级 API（performAction / findNodesById / copyNodeCompat）天然跨通道，节点自带 connectionId。
- 手势：统一走 `XpqAcc.dispatchGesture` / `HumanTouchEngine`，由门面按当前通道分流。
- `AccessibilityService` 的扩展（如 findById）**只在无障碍模式有效**，UiAutomation 下无 receiver。
- **事件分发（dealEvent → asyncHandle_XXX）同样通道相关，但靠「桥接」而非系统绑定**（2026-10-09 定案）：
  - 无障碍：系统直接回调 `onServiceConnected` / `onAccessibilityEvent`，`instance` 由系统 attach 时赋值。
  - UiAutomation：`connectUiAutomation` 成功后**自动**调 `XpqAcc.autoBridgeAccessibilityEvent()`（XpqAcc.kt:227）
    → 反射宿主 manifest 中声明 `BIND_ACCESSIBILITY_SERVICE` 的子类并手动 new（找不到时用 fallback
    `{ SelectToSpeakService() }`）→ `bridgeAccessibilityEvent(handler)` →
    `setOnAccessibilityEventListener { executors5.execute { handler.onAccessibilityEvent(it) } }`
    → `dealEvent` → **`asyncHandle_WINDOW_STATE_CHANGED` 会执行**，前提是「连接成功 + 桥接成功 + 过滤通过」。
  - 手动 new 的实例**不会**被系统 attach，`onServiceConnected` 不回调 → `SelectToSpeakServiceAbstract.instance`
    在 UiAutomation 下为 null。一律用 `XpqAcc.currentService()`（该模式返回 `proxyService`），
    `XPQEventData.service` 已按此填。
  - 事件源 = UiAutomation 的 `OnAccessibilityEventListener`（回调线程 `InvisibleAutoThread`）；
    **只有一条 listener 槽且是覆盖式**，宿主自己再 `setOnAccessibilityEventListener` 会顶掉桥接。
  - 订阅范围：UiAutomation 注册用 `eventTypes = TYPES_ALL_MASK` + `packageNames = null`（全包全类型）。
    无障碍模式下 `onServiceConnected` 那份 serviceInfo（含 `packageNamesFilter`）被注释掉了
    （`//serviceInfo = info`），所以**两种模式下 packageNames 过滤实际都没生效**。
  - `dealEvent` 的 WINDOW_STATE_CHANGED 分支有两道自带过滤：`XpqAcc.rootInActiveWindow()` 非 null、
    且 `root.packageName == event.packageName`（不等直接 return）。排查「回调没进来」先看这两条。
- **`XpqAcc.currentService()` 的语义（2026-10-12 已改）**：**真实实例优先，与当前通道无关** ——
  `SelectToSpeakServiceAbstract.instance ?: proxyService.also { it.ensureAppContext() }`。
  即「无障碍 + UiAutomation 双活」时**永远返回真实无障碍服务**（原实现按 `driver.mode` 分流，
  UiAutomation 下给 `proxyService`）。**注意它只决定返回哪个实例、不改点击通道**：手势仍由 `driver`
  决定（门面路径走 UiAutomation）；但拿到真实实例后直接调
  `service.dispatchGesture / performGlobalAction / getRootInActiveWindow` 走的是**真实无障碍实现**
  （改动前打在 proxyService 上会失败）。→ 纪律：**取根与点击统一走 `XpqAcc.*` 门面**，
  `currentService()` 只用于需要真实 `AccessibilityService` 对象（context / serviceInfo / 事件分发）之处。
  附带好处：与 `accessibilityService`（liveData）口径一致了。
- （背景，2026-10-09 定案）UiAutomation 模式下**不存在**真实无障碍实例，故需要 `proxyService` 兜底：
  - 无障碍模式 → `SelectToSpeakServiceAbstract.instance`，**是**系统 attach 的真实实例（服务未开时为 null）。
  - UiAutomation 模式 → `proxyService = by lazy { ProxyAccessibilityService() }`，是**库内部 new 的空壳**：
    未被系统 attach、无 Context、`serviceInfo` 为 null；只有 `getRootInActiveWindow/getWindows/findFocus`
    被 override 并委托给 XpqAcc。`performGlobalAction` / `dispatchGesture` 是 AccessibilityService 的
    **final 方法无法 override**，必须走 `XpqAcc.performGlobalAction()` / `XpqAcc.dispatchGesture()`；
    `proxy.context` / `proxy.resources` 会 NPE，需要 Context 请用 `LibCtxProvider.appContext`。
    它的存在只为「类型兼容」，让宿主既有的 `accessibilityService?.xxx()` 零改动继续编译运行。
  - 于是 UiAutomation 模式下可能同时存在 **两个**服务对象：`proxyService`（能力代理，真实实例不存在时
    `currentService()` 返回它）与 `bridgedHandler`（手动 new 的宿主子类，只接事件）。排查时别混为一谈。
  - **根因（为什么「拿不到真实实例」不是库的缺陷）**：UiAutomation 注册后，被系统登记的服务对象
    `UiAutomationService` 活在 **system_server 进程**里；App 进程侧只有 framework 的 `UiAutomation`
    + binder 代理，`AccessibilityService` 这个类在 UiAutomation 模式下**从未被实例化**。
    而普通无障碍服务是系统 `bindService` 到 App 进程并 attach 的，实例天然在 App 进程 —— 两者注册路径不同。

## 双通道是「单槽互斥」，不是可并行的两条路（2026-10-12 厘清）
- `XpqAcc.driver` 是**单个** `@Volatile` 字段，`mode` / 所有门面透传
  （rootInActiveWindow、windows、findFocus、performGlobalAction、dispatchGesture、inputText*、
  getServiceInfo、setServiceInfo、disableSelf、setOnAccessibilityEventListener）**全部读这一个槽**
  → 任一瞬间只有一条通道在服务这些能力。**没有优先级机制，胜出者 = 最后一次 `use()` 的通道**
  （last-write-wins；同模式调用 early-return）。**注意 `currentService()` 已不读 driver（2026-10-12 起
  真实实例优先，见上）。**
- `selecttospeak.accessibilityService` 只是个 getter（`accessibilityServiceLiveData.value`），
  `use()` 会把它同步改成「真实实例」或 `proxyService` —— 宿主既有 `accessibilityService?.xxx()`
  也自动跟随单槽，不存在"两个都能用"。
- `use()` 是**双向互踢**：→ UIAUTOMATION 会对真实实例调 `disableSelf()`（同进程有效时）；
  → ACCESSIBILITY_SERVICE 会 `teardownUiAutomationBridge()` + `disconnect()`。
- **"两个都开着"唯一被支持的组合**：`connectUiAutomation(enableHostAccessibilityService = true)`
  （或 `applyEngineMode(UIAUTOMATION, enableHostAccessibilityService = true)`）= UiAutomation 独占门面
  + 宿主无障碍服务在系统侧开着。与 `disableHostAccessibilityService` 互斥，同时 true 时 disable 胜。
- **代价 = 事件双跑**：UiAutomation 下 `autoBridgeAccessibilityEvent` 是反射 `newInstance()` 出
  **第二个**宿主服务实例（不复用 `instance`）挂到 listener 上；真实服务若仍 enabled，系统也会把事件
  回给它 → 两条都跑 `onAccessibilityEvent → dealEvent → asyncHandle_XXX`。要长期允许这种组合，
  必须加去重，或桥接时优先复用 `instance`。
- 想做真优先级（无障碍优先 / UiAutomation 兜底或按能力择优）要改**门面透传层**，把 driver 换成
  `primary.takeIf { it.isConnected } ?: fallback` 之类的按需选路，而不是整体替换（**尚未实现**）。

## 弹窗外观（避免暗黑模式踩坑）
- `XpqAcc` 里的 AlertDialog 一律：Builder 传 `Theme_DeviceDefault_Light_Dialog_Alert` 锁浅色主题
  + `create()` 后 `decorView.isForceDarkAllowed = false`（API 29+）。
- **按钮文字色必须显式设**（`getButton(...).setTextColor(0xFF212121)`）：MIUI/EMUI 等 ROM 会把
  系统 alert 主题的按钮色改成浅色，浅底上看不见。`getButton` 在 `show()` 前返回 null——
  放 `setOnShowListener` 里或 `show()` 之后。

## Shizuku 回调时序与「是否加固」的判定边界（2026-10-06）
- **api 侧链路已用 javap 证实**（dev.rikka.shizuku:api:13.1.5）：`ShizukuServiceConnection extends
  IShizukuServiceConnection.Stub`，`connected(binder)` 里存 binder + `linkToDeath`；进程死亡 →
  `died()` → `MAIN_HANDLER.post` → 遍历 `connections` 调 **`ServiceConnection.onServiceDisconnected`**。
- **但 linkToDeath 只在「成功 connected 之后」才建立**。所以「UserService 进程在 attach 之前就崩」
  （加固宿主最典型：壳 Application 在 app_process 环境创建即崩）**不会产生 `died()`** → 客户端
  只能等 bind 超时。服务端 `UserServiceRecord` 有 **30s startup timeout**，超时才清理记录。
- 推论：`onServiceDisconnected → latch.countDown()`（已实现在 `AutomationShizuku.bind`，用
  `diedBeforeConnected` 标记 + 专门日志）只能加速「起来之后死掉」的场景（Shizuku 被杀、
  UserService 被 ROM 清理），**不能保证加固下秒退就快速返回**。好在它能帮我们区分
  「超时」与「秒退」两种失败，日志可作诊断。
- **回调向 `MAIN_HANDLER` 投递 = 主线程**：`bind()` 绝不能在主线程调用，否则 `latch.await`
  阻塞主线程 → 回调排不上队 → 必然超时。已在 KDoc 标注。
- **加固检测的三个层次**（结论，供决策）：
  ① 行为判定（UserService 能否绑定成功）——最贴近需求、无误判，代价是一次实测（已异步化）；
  ② APK 壳特征扫描（特征 so / assets / 壳类名）——命中率高但**漏检不可忽略**，特征表要长期维护，
     不要凭记忆写表；
  ③ 宿主自报真实 Application 类名，与 `applicationInfo.className` 比对——**对「替换 android:name」
     的壳 100% 命中**（360/乐固/梆梆等主流加固都会替换），但**不替换 Application、只在 dex/native
     层加密的加固会漏检**；且库拿不到宿主真实类名，需宿主一行声明。
  推荐：① 为主（已实现），③ 作为可选精准开关，② 只做日志提示、不用来分支。

## shell 批量操作必须合并成单条脚本（2026-10-06，2026-10-07 更正）
- **`exec()` 的两条路径在「每次调用」这个粒度上是一样的：都会 fork 一个 sh 进程。**
  UserService 路径内部是 `AutomationUserService.exec()` = `ProcessBuilder("sh","-c",command)`；
  newProcess 路径是 Shizuku 服务端 fork sh。**没有哪条路径能复用进程。**
  （⚠ 估算 shell 耗时前必须先确认这一点，否则会把「exec 调用次数」误当成「进程启动次数」。）
- **禁止**在循环里逐条 `exec` 去跑同一条命令（反例：`clearFocusedText` 在 API<30 时
  `repeat(120) { exec("input keyevent 67") }`）。但要注意合并的正确层级：
  - 合并成一条 sh 脚本（`... ; while [ ... ]; do input keyevent 67; done`）只省掉 119 次
    binder 往返 + 119 个 sh（toybox sh 约 3~8ms），**收益仅约 1 秒**——因为循环内每次
    `input keyevent 67` 仍是**独立的 ART 进程**（单次 50~150ms），这才是真正的耗时大头。
  - 真正的解法是用 `input` 命令自身的批量能力：`input keyevent 123 67 67 ... 67`
    （官方 usage：`keyevent [--longpress|--doubletap] <key code number or name> ...`，
    一次可传多个 keycode）→ 1 个进程、1 次 exec。**待真机验证连发是否丢键**
    （AOSP 用 `INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH` 注入，据此推断不丢，但未实测）。
- 同类风险点：`tapNew()` 每个 `input motionevent` 一条命令，一次点击 fork 3~5 次 —— 新增同类
  多步输入注入时应先问「能否用 `input` 自身的批量能力，而不是靠 shell 循环」。
- 判定用户「感知慢」的两个独立时钟：**功能时钟**（connect/onResult，几百 ms）与**日志时钟**
  （后台 bind 结论，默认 3s）。排障时要先问清是哪一个慢，别把日志静默当成功能阻塞。

## Android 14+ 读 Settings.Secure 的 @Readable(maxTargetSdk) 地雷（2026-10-12 实测确认）
- **机制**（本地 SDK `sources/android-36/android/provider/Settings.java` 原文）：
  每个 Secure 键的注解决定可读性——`@Readable` = 任何 app 可读；`@Readable(maxTargetSdk = X)` =
  **app 的 targetSdk 必须 ≤ X 才可读**。检查在**客户端进程内**做
  （`Settings.NameValueCache.getStringForUser`，用 `ActivityThread.currentApplication()` 取
  targetSdkVersion 比对 `mReadableFieldsWithMaxTargetSdk`），超标即抛
  `SecurityException: Settings key: <key> is only readable to apps with targetSdkVersion
  lower than or equal to: X`。
- **本项目 targetSdk = 37（= compile_sdk_version）**，所以凡是 `maxTargetSdk` 有限值的键，**一律必炸**。
- **踩中的键**：`Settings.Secure.ENABLED_INPUT_METHODS` → `@Readable(maxTargetSdk = TIRAMISU /*33*/)`。
  触发点：`KeepAliveInputMethod.ensureImeEnabledAndDefault()` 第 246 行 → 由 `AliveActivity` 的
  「输入法保活」按钮（AliveActivity.kt:422）调用，**调用处无 try/catch → 主线程直接崩**。
  官方替代：`InputMethodManager.getEnabledInputMethodList()` → 比对 `InputMethodInfo.id`。
- **同一族的其他受限键**（同一文件里读到会同样炸，暂未在项目中用到）：
  `DISABLED_SYSTEM_INPUT_METHODS`、`QS_TILES`（都是 maxTargetSdk=33）、`DATA_ROAMING`（32）。
- **确认【不】受限的键**（都只是裸 `@Readable`，可以放心读）：
  `DEFAULT_INPUT_METHOD`（所以 `KeepAliveInputMethod.isDefaultIme()` 第 293 行**安全**，
  不要顺手把它也改了）、`SELECTED_INPUT_METHOD_SUBTYPE`、`ACCESSIBILITY_ENABLED`、
  `ENABLED_ACCESSIBILITY_SERVICES`、`ENABLED_NOTIFICATION_LISTENERS`。
  也就是说本项目里读写无障碍名单/通知监听名单那条链路**不受此限制影响**。
- 排查手法（可复用）：本地已装 `sources/android-14/33/34/35/36`，直接
  `grep -B6 "public static final String <KEY> =" sources/android-36/android/provider/Settings.java`
  看注解，比查网页快且权威（注意 `sources/android-14` 那份 javadoc 较旧、**没有**这些注解，
  别用它判断）。
