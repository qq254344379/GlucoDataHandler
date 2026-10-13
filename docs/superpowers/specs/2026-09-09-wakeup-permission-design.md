---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 5d6ae62faf84b6516c24476ce838bf71_d4b2f110ac4211f1af37525400826444
    ReservedCode1: UyUT3z9QkfsWGC/ejKRO3CKj+W/F7ynwcxE25nNnv1xujvpPq/G9OCCq3QTW4WO9/PeKLGvHmqqRTR6xQXNNEKMR8j7+odFs/XRgCq87s4nl2f2CtNsQRLwQpG7jH+WOzekz3fSPjvbvDs2H052DbSScwPS7/oXEbXpwLcHUKLxwiAIz76rxcfk4VvY=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 5d6ae62faf84b6516c24476ce838bf71_d4b2f110ac4211f1af37525400826444
    ReservedCode2: UyUT3z9QkfsWGC/ejKRO3CKj+W/F7ynwcxE25nNnv1xujvpPq/G9OCCq3QTW4WO9/PeKLGvHmqqRTR6xQXNNEKMR8j7+odFs/XRgCq87s4nl2f2CtNsQRLwQpG7jH+WOzekz3fSPjvbvDs2H052DbSScwPS7/oXEbXpwLcHUKLxwiAIz76rxcfk4VvY=
---

# GDH 周期唤醒权限自检与唤醒加固设计（2026-09-09）

背景：正式版 GDH 在 ColorOS 特殊权限中心不显示条目，用户无法手动授予精确闹钟/悬浮窗/电池优化权限，
导致「周期唤醒通知源 App」（NotificationSourceWatcher/StartupTrigger 自续链）无法实际生效。
用户选择方案 C：A（权限自检+引导）与 B（唤醒逻辑加固）一起做。

## A 部分：权限自检与集中引导
- A1 已由现有 MainActivity.requestExactAlarmPermission / 电池优化行 + SettingsFragmentBase.requestOverlayPermission 覆盖，本次补缺：
- A4 MainActivity.tableNotes 增加「悬浮窗未开启」提示行（ACTION_MANAGE_OVERLAY_PERMISSION），与精确闹钟/电池优化行并列。
- A3 SettingsFragmentBase 中 SHARED_PREF_SOURCE_NOTIFICATION_WAKEUP_ENABLED 由关变开时，自检三项特殊权限，
  缺失则以 AlertDialog 汇总提示，点击 OK 依序拉起第一个缺失权限的系统授权页（每轮触发只引导一项，逐次收敛）。

## B 部分：唤醒逻辑加固
- B1 StartupTrigger.ensureSourceWakeupCheck 调度间隔改为读 notification_reader_interval（coerceAtLeast 1），
  与 NotificationSourceWatcher 的超时判断同源，消除「间隔设置改了但检查频率不变」的不一致。
- B2/B3 NotificationSourceWatcher：
  - alive（lastSeen>0 且未超时）时重置该包失败计数。
  - 静默/从未见时按指数退避决定是否拉起：base=(interval+1)min，每次失败翻倍，上限 30min；
    上次尝试距今不足退避窗口则跳过并记日志。
  - 尝试时间与计数用 SharedPreferences 持久化（前缀键见 Constants），进程被杀不丢。
- B4 同 A4。

## 验证
- 本机 ./gradlew 编译 second/release 变体验证语法与资源。
- 手机装 Second 版：打开「周期唤醒源App」开关观察权限引导；授权后看 logcat GDH.NotificationSourceWatcher
  周期检查与退避日志。

---

# 实现与验证记录（2026-09-09，已完成）

## 状态
方案 C（A 权限自检引导 + B 唤醒加固）代码修改完成；`:mobile:compileSecondKotlin` 与 `:mobile:assembleSecond` 均通过。

## 改动文件清单（7 个源文件，均未 commit）
| 文件 | 改动 |
|---|---|
| common/.../Constants.kt | 新增退避持久化前缀键 SHARED_PREF_WAKEUP_LAST_ATTEMPT_PREFIX / SHARED_PREF_WAKEUP_ATTEMPT_COUNT_PREFIX |
| common/.../service/StartupTrigger.kt | ensureSourceWakeupCheck 调度间隔改读 SHARED_PREF_SOURCE_NOTIFICATION_READER_INTERVAl（coerceAtLeast 1），跟随设置 |
| common/.../service/NotificationSourceWatcher.kt | 指数退避（base=(interval+1)min，×2 上限 30min，指数上限 5）；alive 时清零失败计数；lastAttempt/attemptCount 持久化；日志：alive / skip wake-up / wake it up (attempt N) |
| mobile/.../MainActivity.kt | 主界面新增悬浮窗未开启提示行（ACTION_MANAGE_OVERLAY_PERMISSION），表 Notes 行区 |
| mobile/.../preferences/SettingsFragmentBase.kt | 开启唤醒开关时调用 checkWakeupPermissions()：自检精确闹钟/悬浮窗/电池优化，缺失弹窗列出并引导第一个缺失项（openMissingWakeupPermission） |
| common/res/values/strings.xml | 新增 overlay_permission_disabled、wakeup_permission_dialog_title/message、permission_name_exact_alarm/overlay/battery |
| common/res/values-zh/strings.xml | 同上简体中文翻译 |

## 产物
- APK：`mobile/build/outputs/apk/second/GlucoDataHandler_2.4.2_SECOND.apk`（SHA-256 见构建输出，10.3MB，Second 变体可安装）

## 手机验证步骤（后续检查用）
1. 安装 Second APK，打开设置 →「读取动态通知」→ 打开「周期唤醒源App」开关
2. 若弹出「开启源应用保活需要权限」，按引导逐项授予：精确闹钟 → 悬浮窗 → 忽略电池优化（每授予一项，关闭再打开开关引导下一项）
3. 验证唤醒链：logcat 过滤 `GDH.NotificationSourceWatcher`，可见周期检查日志；源 App 静默时会按退避节奏出现 `try to wake it up (attempt N)`；权限缺失时 `StartupTrigger` 打印 `No exact alarm permission`，设置页弹窗在 `GDH.SettingsFragmentBase` 标签下可见
4. 各动作时间戳供回溯：MainActivity 悬浮窗行 Log.w "Overlay permission is not granted"；checkWakeupPermissions Log.i "All wake-up permissions are granted" / Log.e 异常

## 补充：保活日志纳入应用内 DB 日志（2026-09-09）
目的：保活动作默认即可在 GDH 应用内「日志」界面查看/导出（DB 日志），不依赖 logcat 或开启「调试日志」。
现状：GDH Log 对象在 dbLoggingEnabled（日志时长>0）且 priority>=minLevel（默认 INFO）时写入 DB；DEBUG 级仅在开启调试日志后入 DB。
调整（已编译 :mobile:assembleSecond 通过）：
| 文件 | 原级别 | 现级别 | 日志内容 |
|---|---|---|---|
| NotificationSourceWatcher | D | I | "Wake-up disabled - stopping watchdog" |
| NotificationSourceWatcher | D | I | "Source app $pkg is alive (last seen Xs ago, timeout Ys)" |
| NotificationSourceWatcher | D | I | "Source app $pkg still silent - skip wake-up (retry in Zs)" |
| StartupTrigger | D | W | "No exact alarm permission for source wakeup check!" |
| SettingsFragmentBase | 无 | W | "Missing wake-up permissions: ... - showing guidance" |
未提升：StartupTrigger "Source wakeup check already scheduled"（每周期重复，保留 D 防噪音，logcat 调试可见）。
说明：wake-up attempt 原为 W、Wake-up intent sent 原为 I、onReceive 异常 E，本就入 DB，未改。
产物（同前）：`mobile/build/outputs/apk/second/GlucoDataHandler_2.4.2_SECOND.apk`
*（内容由AI生成，仅供参考）*

## 补充：周期唤醒链断链修复（2026-09-09 20:50）
现象（手机日志 GDH_PHONE_APP_20260909_204248.txt）：20:29:35 触发过一次 wake-up check 后，后续周期不再触发；源 App（com.sino.care）20:36 起掉线，无任何拉起动作。判定为"检查未运行"而非"拉起失败"。
根因：StartupTrigger.ensureSourceWakeupCheck() 的防重入守卫依赖内存字段 wakeupAlarmManager/wakeupPendingIntent；alarm（一次性 PendingIntent）到点触发后字段未清空，NotificationSourceWatcher.onReceive 末尾 finally 调 ensure 续排时命中 "already scheduled" 直接 return，下一轮 alarm 永远不会被设置 → 自续链在首轮后中断。
修复（2 处，已编译 Second+Release 通过）：
1. StartupTrigger 新增 wakeupCheckFired()：置空两个内存排程字段（不 cancel、不打日志）。
2. NotificationSourceWatcher.onReceive 的 finally 中先调 StartupTrigger.wakeupCheckFired() 再 ensureSourceWakeupCheck()，使续排真正生效。
验证方法：装新包后打开「周期唤醒源App」开关，日志应每 5 分钟（或所设读取间隔）出现一次 "Source wake-up check triggered"（I 级，入 DB），且不再只有单次。
产物（同前，正式签名可覆盖）：
- mobile/build/outputs/apk/release/GlucoDataHandler_2.4.2.apk（SHA-256 3b3321b70e2cfa1f16fc45e87acd200b11452c2e78a0085d4f7e8e07ece9886c）
- mobile/build/outputs/apk/second/GlucoDataHandler_2.4.2_SECOND.apk（SHA-256 597f4f07e6e74d8ef243751356e19783ccee9a751e0afc096943e0c3ba88d4c9）
*（内容由AI生成，仅供参考）*
