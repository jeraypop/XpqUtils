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
- **系统级 Service 回调在 UiAutomation 模式下静默**（2026-10-14 核实）：系统只对**被 attach 的 Service**
  回调 `onConfigurationChanged` / `onLowMemory` / `onTrimMemory` / `onUnbind`；UiAutomation 模式下 App 进程内
  没有 attach 的实例（proxyService / bridgedHandler 都是手动 new，不在 ActivityThread 的 mServices 里），
  宿主 `AccessibilityServiceImp` 里的这些 override **不会被调用**，只有双活（`KEY_UIA_ENABLE_HOST_ACC=true`）
  时真实实例才收到 → **已实现（2026-10-14）**：库在 `autoBridgeAccessibilityEvent` 成功后把
  `uiAutomationComponentCallbacks`（**`ComponentCallbacks`**）注册到 Application（进程级），收到后转发给
  **桥接实例** `bridgedHandler`（宿主自己的类、已 `ensureAppContext`）；注销点 = `teardownUiAutomationBridge`
  （成对，漏注销会进程级泄漏 + 断开后仍回调）。双活去重：`SelectToSpeakServiceAbstract.instance != null`
  时不转发。转发的是**服务方法本身**（非自定义钩子）⇒ 宿主已有的 `override fun onConfigurationChanged`
  零改动即生效，两种模式共用一处。**只订阅 config，刻意不订阅 `onTrimMemory`**（要收须换回
  `ComponentCallbacks2`）：那个信号宿主从 `Application`/`Activity.onTrimMemory` 直接拿得到，库无消费方
  （`Application.dispatchTrimMemory` 按 `instanceof ComponentCallbacks2` 过滤 —— 类型上就断开了）。
  `SelectToSpeakServiceAbstract` 最终**零改动**。
- **重要事实（2026-10-14 源码核实）**：`Application` **本来就会**收到 `onConfigurationChanged` /
  `onTrimMemory`，两种通道都收得到 —— `ConfigurationController.handleConfigurationChangedInner:225` 用
  `collectComponentCallbacks(false)`、`ActivityThread.handleTrimMemory:7390` 用 `(true)`，两者都含
  **`mAllApplications` + `mServices`**（ActivityThread:6636-6656）。⚠ config 那条 `includeUiContexts=false`
  ⇒ **不含 Activity**（Activity 走 `performActivityConfigurationChanged`）。
  ⇒ 库这条链路的唯一能力差异是「把事件送到**宿主服务实例**、复活宿主已有的
  `AccessibilityServiceImp.onConfigurationChanged`（原本在纯 Shizuku 模式下是死代码且不报错）」，
  **不是**「宿主本来收不到」。另：`Application.onConfigurationChanged/onTrimMemory/onLowMemory` 都是
  `@CallSuper`（Application.java:277/282/287）且内部才遍历 `mComponentCallbacks`
  （ComponentCallbacksController:90/106）⇒ 宿主 override 了 Application 的同名方法却不调 super，
  别人注册的回调就收不到；库自己注册可绕开这层依赖。
  另：`Service.onConfigurationChanged` 是**空实现、不读 mBase**，手动 new 实例调 `super` 不会 NPE
  （`sources/android-36` 的 `android/app/Service.java:540` 原文）。

## 弹窗（XpqAcc 内的 AlertDialog）
- 一律传 `Theme_DeviceDefault_Light_Dialog_Alert` + `decorView.isForceDarkAllowed = false`（API 29+）。
- **按钮文字色必须显式设** `0xFF212121`（MIUI/EMUI 会改成浅色）；`getButton` 在 `show()` 前返回 null。
- `setView` 与 `setSingleChoiceItems` **可共存**（custom 面板在列表上方）；`setMessage` 与列表互斥。
- 「开启随选朗读」开关（Shizuku 模式下**始终显示**，取值 `KEY_SELECTTOSPEAK_ENABLE`；无障碍模式隐藏）：
  未传 `enableServiceString` 时可自由切换，true 等价于传 `SELECTTOSPEAK`；调用方传了 `enableServiceString`
  时本行转**只读**（勾选状态 = 传入的是否为 SELECTTOSPEAK，`isEnabled=false`，文字/颜色不变）。
  它开着时「同时开启无障碍服务」行隐藏，且其值**不写回 MMKV**（保留旧偏好），连接时被
  `enableServiceOnConnect` 非空守卫短路 —— 两者语义冲突（独占「只留随选朗读」 vs 增量「额外加宿主服务」），
  互斥是有意的。
- 无障碍模式「确定」按 `WRITE_SECURE_SETTINGS` 分流：有权限 → 后台 `enableAccessibilityService` +
  轮询等 `instance` 就绪（**超时也算失败**）；无权限/失败 → 跳系统设置引导。重活在后台线程，不阻塞主线程。
- `showEngineModeDialog` 三个回调的真实语义（与名字不完全一致，改前先看）：`onUIASuccess`/`onUIAFail`
  是**成功/失败**对称回调，**两种模式**都会触发（无障碍的代码开启成功/失败同样走）；`onConfirm(mode)`
  只在「无障碍模式（无条件，含失败）」与「Shizuku 模式**失败**」时触发，**Shizuku 成功时不触发**；
  `onCancel` 只认「取消」按钮（点外部/返回键关闭不触发）。失败通知统一走局部 `notifyFail` 收口
  （handleModeResult 两个分支 + failToast 分支各一次），新增失败路径时别漏 failToast 那条。

## 内存回调在 API 34+ 的现状（2026-10-14 源码核实）
- `onLowMemory()` 源码注释明写 **"Since API level 34 this is never called"** → 挂上去是死代码。
- `onTrimMemory`：7 档中 5 档已废弃（`RUNNING_*` 5/10/15、`MODERATE` 60、`COMPLETE` 80），
  **只剩 `UI_HIDDEN`(20) 与 `BACKGROUND`(40)**；且 `ActivityThread.handleTrimMemory` 对"前台型进程"
  （`mLastProcessState <= PROCESS_STATE_IMPORTANT_FOREGROUND`）的 `level >= 40` **直接 return 不派发**
  （`skipBgMemTrimOnFgApp`）→ 无障碍/保活进程实际只能等到 `UI_HIDDEN`。
- 陷阱：本项目可见面是悬浮窗而非 Activity → `UI_HIDDEN` 来得早（宿主 Activity 一退就来），
  不能按官方"释放 UI 对象"处理。

## 无障碍「已启用名单」的两种读法（2026-10-15 源码核实）
- **写入侧唯一实现 = `AccessibilityEnableHelper`**（2026-10-15 收敛）：`XpqAcc` 门面与
  `XPQAccUtils.enable/disableAccessibilityServices` 都只委托，库里不再有第二处直接写
  `ENABLED_ACCESSIBILITY_SERVICES` / `ACCESSIBILITY_ENABLED` 的代码。helper 的写路径是
  **「目标集合 + 名单只写一次」**（`enableAccessibilityServices(ctx, Collection, exclusive)` /
  `disableAccessibilityServices(ctx, Collection)`，单元素版只是 `listOf(x)` 重载）——
  批量时**不要**循环调用单元素版，每次 shell exec 都要 fork。
- **secure 名单** = 输入，可能含脏数据；解析唯一入口 `AccessibilityEnableHelper.parseEnabledServices`
  （冒号分隔 + trim + 丢空项）。**AccessibilityManager 列表** = 输出，是「名单 ∩ 已安装」的交集。
- 形态不同：名单存 `flattenToString()` **长式**，`AccessibilityServiceInfo.getId()` 是
  `flattenToShortString()` **短式** → 不能直接字符串相等。**已收口（2026-10-15）**：归一化唯一入口 =
  `AccessibilityEnableHelper.canonicalComponent()`（`ComponentName.unflattenFromString(..)?.flattenToString()
  ?: this`，解析失败原样保留），输出统一**长式**。**读**侧：`listEnabledAccessibilityServices`
  （= `XpqAcc.enabledAccessibilityServices`）与 `AccIsOpenUtil.getEnabledServiceIds` /
  `getEnabledServiceIdsFromSecure` 都 map 过它，可直接互比。**写**侧：`enable/disableAccessibilityService`
  入口就归一化入参，内部「已在名单 / 已移除 / 回读校验 / merge / 独占残留」全部走 `containsComponent` /
  `withoutComponent` 归一化比较 —— 否则会重复追加、关不掉、独占回读误报残留。
- `getEnabledAccessibilityServiceList` 服务端判定含**通配分支**
  `|| feedbackType == AccessibilityServiceInfo.FEEDBACK_ALL_MASK`（`0xFFFFFFFF`）→ 连 `feedbackType = 0`
  的服务也返回（该属性缺省值即 0）。⚠ 通配 **Android 14 才加**（13 及更早只有位与 → 静默漏）。
- 三道会静默清空 AM 列表的闸：① `suppressingAccessibilityServicesLocked()`（注册了 UiAutomation 且 connect
  flags **未含** `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`(0x1)）→ `Collections.emptyList()`；
  本库传了 0x1（`InvisibleAutomation` 的 `connect(int)` 分支），不受影响；② `getServiceLocked()` 为 null
  （`AccessibilityManager.java:878`）；③ proxy device（`mProxyManager.isProxyedDeviceId`）走另一条实现。
- 排查手法：AOSP **服务端**源码不在 SDK 里，用
  `raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/<branch>/services/accessibility/java/
  com/android/server/accessibility/*.java`（分支 `android-<ver>.0.0_r1`、`android14-release`、`main`）；
  逐版本比对可定位「某个行为是哪一代引入的」。

## Android 14+ `@Readable(maxTargetSdk)` 地雷（2026-10-12 实测）
- 本项目 targetSdk=37，凡 `maxTargetSdk` 有限的 Secure 键**一律必炸**（检查在客户端进程内做）。
  踩中 `ENABLED_INPUT_METHODS`(33) → 改用 `InputMethodManager.getEnabledInputMethodList()`。
- 同族受限：`DISABLED_SYSTEM_INPUT_METHODS`、`QS_TILES`(33)、`DATA_ROAMING`(32)。
- **不受限**：`DEFAULT_INPUT_METHOD`、`ACCESSIBILITY_ENABLED`、`ENABLED_ACCESSIBILITY_SERVICES`、
  `ENABLED_NOTIFICATION_LISTENERS`（读法不要顺手改）。
- 排查手法：`grep -B6 "public static final String <KEY> =" sources/android-36/android/provider/Settings.java`
  看注解（`sources/android-14` 那份缺注解，别用它判断）。

## `<queries>` 清单规则（2026-10-16 踩坑定案）
- **`<queries>` 内的 `<provider>` 按 authority 匹配**，`android:authorities` 是**必填**属性；
  只写 `android:name="rikka.shizuku.ShizukuProvider"` 会 AAPT 报
  `missing required attribute 'android:authorities'`，且**即便补上也毫无作用**（类名在这里不认）。
  跨包可见性用 `<package>`（已知包名）或 `<intent>`（按 action/category 广域）解决。
  本库**不需要**任何 Shizuku provider 可见性声明：客户端拿 binder 走**自己包**的 provider
  （`ShizukuProvider.requestBinderForNonProviderProcess` → `content://<本应用包名>.shizuku`），同包不过滤。
- **改清单后必须跑到清单/资源阶段验证**：`compileDebugKotlin` **完全不碰清单**，AAPT 错误要到
  `:xxx:processDebugManifest` / `:xxx:processDebugResources` / `assembleDebug` 才暴露。

## 定位「Shizuku 管理器所在应用」（2026-10-16 定案，零宿主配置）
- manager 清单（官方 App 与 `com.github.jeraypop.Shizuku:manager` 库同一份）里
  `moe.shizuku.manager.MainActivity` 是 `exported="true"` 但**没有任何 intent-filter**
  → 任何 `queryIntentActivities` 都扫不到它，`getLaunchIntentForPackage(宿主)` 拿到的也只是宿主启动页。
  跳转**只能**靠「已知类名 + 显式 `ComponentName`」。
- 但**包名可以零配置反查**：manager 恒声明**字面量 action**
  `rikka.shizuku.intent.action.REQUEST_BINDER`（`ShellRequestHandlerActivity` + `BinderRequestReceiver`，
  均 exported）→ 谁声明它谁就是管理器所在应用。库自己的 `<queries>` 里加这条 `<intent>`
  （+ `CATEGORY_DEFAULT`）即可看见它，宿主无需写代码/改清单。
- `IShizukuService`（aidl 13.1.5）**没有**任何返回管理器包名的方法 → 问不到 server，只能靠上面这条反查。
- `openShizuku` 顺序：本包 `Class.forName` 直开 → 探针反查包名 + 显式跳转 → 注册候选 + 官方包名 →
  兜底扫「带启动页的可见应用」逐个 `getActivityInfo` 探组件 → 退到候选应用启动页。
  `addStandaloneShizukuPackage` 仅在「fork 把探针 action 也改掉」时才有用。

## 点击扩展函数的既有陷阱（2026-10-16 读码确认，未改动代码）
- `AccessibilityService?.gestureClick(node)`（GestureExt.kt:17）= **HumanTouchEngine 拟人化点击**：
  `HumanTouchEngine.click()` 的返回值**被丢弃**、函数**恒 `return true`**（只在 receiver 为 null 时 false）。
  ⇒ `gestureClick(node) || node.click()` 里的 `node.click()` 是**不可达死代码**（service 已在入口判非空）；
  `clickByIdAndTextFilter` 的 `.takeIf { it }` 写法同理是摆设。
- ⇒ `clickMultipleByIdAndText` 的返回值 ≈「是否真的走到过一轮点击」：只要有任意一轮点到就恒 true；
  `var result` 每轮覆盖 = 只反映**最后一轮**（想表达「全部成功」得写 `result && ok`）。
- 形参 `gestureClick: Boolean` 与扩展函数**同名**（遮蔽）：调用处 `gestureClick(node)` 解析到函数、不是布尔 → 易误读。
- `rawList` **只在最开始取一次**：第一轮点击后界面若重排，后续节点已过期 → `copyNodeCompat` 失败被
  `continue` **静默跳过**。要连点多个元素时应**每轮重新取根再查**。
- 同族对照：`clickByIdAndTextFilter` 比较时两侧都做 `default(filter = true) + removeTrailingNumber()`
  （洗掉非中文/ASCII 字符与结尾序号），而 `clickMultipleByIdAndText` 是**原样精确**比较（空白 → ""）。

## 点击族新增函数：照抄同族结构（2026-10-16 用户要求）
- `ext/acc/AccessibilityServiceExt.kt` 加同族点击函数时，**先照抄已有那一个的骨架**：suspend 与否、
  参数形状（`startIndex` / `count` / `gestureClick`）、`copyNodeCompat → 点击 → recycleCompat`、
  每轮 `delay(500)`、返回**最后一轮**结果。
- ⚠ 反面教训：`clickMultipleByDesc` 第一版写成非 suspend 单点版（多出 `index` / `MatchMode` 参数），
  被否「风格要和 `clickMultipleByIdAndText` 保持一致」→ **不要自创「更好用」的参数形状**。
- 定位依据不同时只换数据源：desc 版 = `contentDescription.default() == desc`（与
  `it.text.default() == text` 对称），**不看 text**；无系统 byDesc API，配套 private
  `findNodesByDesc`（前序遍历全树收集，子树无命中回收 child）。

## 编辑工具纪律（补充 2026-10-16）
- 用 Edit 定位时，**`old_string` 不要跨越到下一个成员声明**（例如顺手带上下一个 `fun xxx(...) {` 当锚点）：
  若 `new_string` 忘了把那几行写回，就会**静默删掉**整个函数签名，函数体悬空在类里。
  定位优先用「目标块自身末尾几行」，或把锚点行**原样包含进 `new_string`**。
