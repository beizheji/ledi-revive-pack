# 原厂 SDK 可调用面

机器人原厂 App（`xldrbt_v1.5.10`）里有一套 SDK。三份原厂 APK 的 DEX 字节码逐类解析得到可调用面，
并在真机上用 `GET /sdk/caps` 反射复核。

## 1. 三份包里的符号状态

照抄原厂 Java 文档里的类名会在运行期失败。三份包对同一套 SDK 的处理方式不同：

| APK | 角色 | SDK 符号状态 |
|---|---|---|
| `xldrbt_v1.5.10.apk` | 机器人主 App（实际合并的就是它） | SDK 被 R8 裁剪 + 混淆：门面 `XldBaseSDKInit → xldbasesdk.b`；`Function.SystemMethod → xldbasesdk.b.a` 且方法名压成单字母 `a…p`；动作回调 `xldbasesdk.a.a.a`；事件监听 `xldbasesdk.config.d` |
| `com.auric.intell.rbt.xldtvrbtx-1.apk` | tvbtx 版主 App | 全名符号基本齐全，用于反查「单字母方法原本叫什么」 |
| `com.auric.intell.rbt.rbtclient.apk` | 手机客户端 | 又一份 SDK 副本，混淆程度介于两者之间，用于交叉确认 `RobotEvent` 常量与广播字符串 |

混淆对照：

| 原厂全名（tvbtx） | v1.5.10 实际（R8） |
|---|---|
| `…xldbasesdk.XldBaseSDKInit` | `xldbasesdk.b` |
| `…xldbasesdk.Function.SystemMethod` | `xldbasesdk.b.a` |
| `…xldbasesdk.CallBack.eng.ActionCompleteCallBack` | `xldbasesdk.a.a.a`（唯一方法 `actionComplete()`） |
| `…xldbasesdk.config.RobotEventListener` | `xldbasesdk.config.d` |
| `service.engine.EngineService` | 同名（在 `AndroidManifest` 声明了 Service，R8 默认 keep） |
| `config.RobotEvent` | 同名（枚举常量名保留） |
| `config.eng.EngDataPersist` / `ActionSpeedSet` / `config.led.LedTypedef` / `serialport.SerialPort` | 同名（被当作类型引用，R8 一并保留） |
| `config.ConstantPath` / `config.MotionType` | 类消失（常量被内联，类体不保留） |

`SdkController` 对每个类 / 方法 / 字段准备「混淆名 + 全名」两份候选，运行时逐个
`Class.forName` / `getMethod` / `getDeclaredField`，命中即用。

## 2. 动作引擎 `EngineService`

未混淆，通过 `EngineBinder` 绑定后取得实例。15 个公开方法：

| 方法 | 用途 | 要点 |
|---|---|---|
| `baseAction(String[], ActionCompleteCallBack)` | 动作序列 | 逐个播放动作名数组。每个名字查 `/sdcard/xldBaseSDK/<名>`；带 `special_` 前缀时改走 `moveInterface("special_"+名)`；文件缺失只打日志并回调，不中断序列 |
| `baseAction(long, String[], ActionCompleteCallBack)` | 带间隔的序列 | 第一个参数是动作之间的等待毫秒数 |
| `specialDance(String, ActionCompleteCallBack)` | 指定舞蹈 | 名字为空 = 引擎自己随机 |
| `radomDance(int, ActionCompleteCallBack)` | 随机舞 | 那个 int 是节奏 bpm（写进 `songSpeed`），不是次数 |
| `moveInterface(String)` | 单动作（按名） | 查 `/sdcard/xldBaseSDK/<名>`，不存在再试 `dance_<名>` |
| `moveInterface(String, int)` | 单动作 + 速度 | 速度取 `ActionSpeedSet`（`SLOW`/`MEDIUM`/`FAST`） |
| `moveInterface(String, int, boolean)` | 单动作（内部用） | 第三个 boolean 是「是否舞蹈」 |
| `moveInterface(long, String)` | 单动作 + 延时 | 延后执行 |
| `stopMove(ActionCompleteCallBack, boolean)` | 停止 | 第二个参数传 `false` |
| `realeaseControl_All()` | 交出控制权 | 原厂拼写就是 `realease`。把串口控制权还给主 App |
| `setBatteryLow(boolean)` | 低电量标志 | 只置标志。`EngineService` 自己不用它做门禁，门禁在 SDK 门面上 |
| `setRobotEventListener(RobotEventListener)` | 事件监听注册 | `public static` |
| `angleToAutoInstuction(int,int,int,int) → byte[]` | 帧编码 | 把（时长, 关节1, 关节2, 关节3）编成 12 字节串口帧。包可见，需反射调用。见 `motion.md` |
| `onBind(Intent)` / `onCreate()` | Service 生命周期 | |

实例字段（`GET /sdk/stat` 读这些）：`isControled`、`engPause`、`batteryLow`、`songSpeed`、
`armWrestTime`、`releaseTime`、`left_ArmTurn`、`actionSpeed`、`actionSetFile`、`serialPort`、
`queeryIns`、`releaseIns`、`lastMoveInstrunction(byte[])`，以及常量 `DISCONTROL` / `RECONTROL` /
`STOP` / `FINISH` / `MESSAGE_ENGSPEED` / `WRITEINSTRUCTION` / `WRITELOCK` / `WRITEUNLOCK` /
`WREST_LEFT_ARM` / `WREST_RIGHT_ARM` / `ACTION_ENGSTATUS`。

## 3. 系统层 `Function.SystemMethod`

该类共 17 个 `public static void f(Context)`，方法体各自只有一行
`context.sendBroadcast(new Intent("…"))`。

调用它们等价于发一个广播，不依赖 SDK 初始化，也不依赖引擎。

| 混淆名 | 原厂全名（tvbtx） | 广播 action |
|---|---|---|
| `a` / `b` | `led_Red_On` / `led_Red_off` | `com.auric.intell.xld.os.led.r.on` / `.off` |
| `c` / `d` | `led_Green_On` / `led_Green_off` | `…led.g.on` / `.off` |
| `e` / `f` | `led_Blue_On` / `led_Blue_off` | `…led.b.on` / `.off` |
| `g` / `h` | `led_Yellow_On` / `led_Yellow_off` | `…led.y.on` / `.off` |
| `i` / `j` | `led_Pink_On` / `led_Pink_off` | `…led.p.on` / `.off` |
| `k` | `led_Red_BlinkFast_On` | `…led.r.blink.fast` |
| `l` | `led_Blue_BlinkSlow_On` | `…led.b.blink.slow` |
| `m` | `led_Close_All` | `…led.all.off` |
| `n` | `powerOff` | `com.auric.intell.xld.os.poweroff` |
| `o` / `p` | `adb_On` / `adb_Off` | `…os.usbon` / `…os.usboff` |
| `reboot` | `reboot` | `…os.reboot`（名字未被改） |

原厂 LED 只有上述 13 种组合有独立方法。其余组合（绿灯快闪、黄灯慢闪、全部点亮、全部快闪等）
在原厂 SDK 里不存在，只能用裸广播，action 按 `…led.<颜色>.<动作>` 拼接。

## 4. 事件

```java
// v1.5.10: com.auric.intell.rbt.xldbasesdk.config.d  ← RobotEventListener
public void onEvent(RobotEvent event)
public void onArmRoll(RobotEvent event, String arg)   // 手臂被扭转时另带一个参数字符串
```

把接口的 `Proxy` 注册给 `EngineService.setRobotEventListener(…)` 后开始收到事件。
`RobotEvent` 枚举共 44 个常量：

| 类别 | 常量 |
|---|---|
| 按键 (5) | `BTN_POWER_DOWN` `BTN_POWER_UP` `BTN_HEAD_DOWN` `BTN_HEAD_UP` `BTN_HEAD_CLICK` |
| 触摸 (10) | `TOUCH_HEAD_DOWN` `TOUCH_HEAD_UP` `TOUCH_HEAD_THREE_TIME` `TOUCH_LEFT_FACE_DOWN` `TOUCH_LEFT_FACE_UP` `TOUCH_RIGHT_FACE_DOWN` `TOUCH_RIGHT_FACE_UP` `TOUCH_LEFT_RIGHT_FACE_DOWN` `TOUCH_LEFT_RIGHT_FACE_UP` `TOUCH_TWOFACE_DOWN` `TOUCH_TWOFACE_UP` |
| 陀螺仪姿态 (9) | `FEEDBACK_BODY_STAND` `FEEDBACK_BODY_LIEDOWN` `FEEDBACK_BODY_CEWO` `FEEDBACK_BODY_PAXIA` `FEEDBACK_BODY_QITA` `FEEDBACK_BODY_SHAKE` `FEEDBACK_BODY_OVER_TURN` `FEEDBACK_BODY_TRIP` `FEEDBACK_BODY_TAKE_UP` |
| 关节 (6) | `FEEDBACK_LEFT_ARM_TURN` `FEEDBACK_RIGHT_ARM_TURN` `FEEDBACK_TWOHAND_TURN` `FEEDBACK_WAIST_TURN` `FEEDBACK_LEFT_ARM_WREST` `FEEDBACK_RIGHT_ARM_WREST` |
| 面部快触 (2) | `FEEDBACK_LEFT_FACE_FASTTOUCH` `FEEDBACK_RIGHT_FACE_FASTTOUCH` |
| 红外 (2) | `FEEDBACK_INFRARED_HAS_PERSON` `FEEDBACK_INFRARED_NO_PERSON` |
| 校准 (2) | `FEEDBACK_GENSENSOR_CZ_SUCESS`（原厂拼写） `FEEDBACK_GENSENSOR_CZ_FAIL` |
| 系统 (7) | `POWEROFF_EVENT` `REBOOT_EVENT` `LOWBATTERY_EVENT` `BATTARY_LEVELLOW`（原厂拼写） `DEEP_WAKEUP` `LIGHT_WAKEUP` `OPEN_AP` |

枚举自身有 `getId()` / `setId(int)` / `isSystemEvent()` 三个实例方法。

事件来自 `Receiver.SystemBroadcastReceiver`，其字符串常量表即「机器人实际在监听什么」：

| 广播 action | 映射到 |
|---|---|
| `com.auric.intell.xld.rbt.key.power.down` / `.up` | `BTN_POWER_DOWN` / `BTN_POWER_UP` |
| `com.auric.intell.xld.rbt.key.head.down` / `.up` | `BTN_HEAD_DOWN` / `BTN_HEAD_UP` |
| `com.auric.intell.xld.rbt.face.head.down` / `.up` | `TOUCH_HEAD_DOWN` / `TOUCH_HEAD_UP` |
| `com.auric.intell.xld.rbt.face.l.down` / `.l.up` | `TOUCH_LEFT_FACE_DOWN` / `TOUCH_LEFT_FACE_UP` |
| `com.auric.intell.xld.rbt.face.r.down` / `.r.up` | `TOUCH_RIGHT_FACE_DOWN` / `TOUCH_RIGHT_FACE_UP` |
| `com.auric.intell.xld.rbt.event.lowbattery` | `LOWBATTERY_EVENT` |
| `com.auric.intell.xld.rbt.unknown` | 姿态未知（`FEEDBACK_BODY_QITA`） |
| `android.intent.action.cz.Infrared` | `FEEDBACK_INFRARED_HAS_PERSON` / `NO_PERSON` |
| `android.intent.action.cz.gsensorcali.success` | `FEEDBACK_GENSENSOR_CZ_SUCESS` |
| `android.net.conn.CONNECTIVITY_CHANGE` | 网络变化（不是 `RobotEvent`） |

这套广播不绑定 `EngineService` 也能自己注册 `BroadcastReceiver` 接收。

## 5. 引擎状态字段（`GET /sdk/stat`）

| 逻辑名 | v1.5.10 字段 | 含义 |
|---|---|---|
| `engine_ready` | `EngineService.m`（static） | 引擎是否已初始化 |
| `dance` | `EngineService.i`（static） | 当前是否在跳舞 |
| `controlled` | `isControled` | 是否处于被接管状态 |
| `paused` | `engPause` | 引擎是否暂停 |
| `battery_low` | `batteryLow` | 低电量标志（门面拿它做门禁） |
| `song_speed` | `songSpeed` | 当前舞曲节奏 |
| `arm_wrest_ms` | `armWrestTime` | 手臂扭转判定时间窗 |
| `release_ms` | `releaseTime` | 释放保持时间 |
| `left_arm_turn` | `left_ArmTurn` | 左臂是否在扭转 |
| `right_arm_turn` | 该版无此字段 | 读取为 `null` |
| `two_arm_turn` | 该版无此字段 | 读取为 `null` |

三关节角度另有一条路：`service.engine.thread.b`（= `GetEngStatusThread`）的静态字段
`leftarm` / `rightarm` / `waist`。字段名在 v1.5.10 里是否保留需真机确认，判据是
`/sdk/stat` 里的 `arms` 是 `null` 还是三个数字。位置回读的主路见 `motion.md`。

## 6. 常量与工具类

| 类 | 内容 |
|---|---|
| `config.eng.EngDataPersist` | 三关节行程参数，存在 `SharedPreferences("xldEngData")`（值经异或加密后转 hex）。每关节四键 `EngN_miniPos / maxPos / midPos / zeroPos`，出厂默认 `mini=0x0000`、`max=0x03FF(1023)`、`mid=0x0200(512)`、关节1/3 `zero=0x0146(326)`、关节2 `zero=0x0200`。另有 `m()…r()` 六个取值方法做换算，写姿态时的限位校验用它。`<clinit>` 里有一串内置动作名（`angry` `beckon` `cheer` `comeon` `crosshand` `dizzy` `flymode` `handshake` `hi_1` `hi_2` `hug` `laystruggle` 等） |
| `config.eng.ActionSpeedSet` | 枚举 `SLOW / MEDIUM / FAST`，即 `moveInterface(name, speed)` 的 speed 取值域 |
| `config.led.LedTypedef` | 灯光类型枚举容器。实际发送仍落到广播 |
| `serialport.SerialPort` | 串口封装，native 库 `serial_port`，打开 `/dev/ttyMT0` 时 `chmod 777` |
| `Receiver.SystemBroadcastReceiver` | 事件广播接收器 |
| `Exception.eng.InstructionException` | 串口指令格式异常，帧长度不对时抛，可用于对照编码是否正确 |
| `…commonlib.utils.s.reboot(Context[, String])` | 另一个重启入口（三个重载，含无参） |
| `Net.MotionDataRefresh / MotionRepository / ApiService` | 原厂动作在线拉取链路。依赖的服务器已停服，不可用 |

## 7. 门面 `XldBaseSDKInit` 的门禁

门面包了初始化、权限申请、绑定 Activity 生命周期，同时是一道门禁：
`baseAction` / `specialDance` / `radomDance` 之前检查两个静态标志：

- `batteryLowLevel` — 低电量时静默跳过动作
- `move` — 未置位时静默跳过动作

跳过的表现是：只回调 `actionComplete`，不执行动作，日志里也没有痕迹。

因此 `SdkController` 的动作全部改走 `EngineService`，后者没有这两个门禁。

排查「回调成功但机器人不动」时，先用 `POST /sdk/flag {"battery_low":"true"}` 复现门面拒绝的现场，
再对比 `POST /sdk/series`（走 `EngineService`）是否照常执行。

## 8. HTTP 封装（`/sdk/*`，14 个端点）

| 端点 | 背后的原厂调用 |
|---|---|
| `GET /sdk/caps` | 运行时反射探测：命中哪套符号 / 15 个动作接口 / 13 个 LED 独立方法 / 44 个事件常量 / 常量类 |
| `GET /sdk/stat` | `EngineService` 的 11 个状态字段 + 三关节角度 |
| `GET /sdk/events` `POST /sdk/listen` | `setRobotEventListener` + 动态 `Proxy`，事件进设备端环形缓冲（上限 40 条） |
| `POST /sdk/series {"names":[…]}` | `baseAction(String[], cb)`；带 `delay` 时走 `baseAction(long,…)` |
| `POST /sdk/dance {"name":"dance_xx"}` | `specialDance(name, cb)`；name 为空 = 随机 |
| `POST /sdk/bpm {"bpm":120}` | `radomDance(bpm, cb)` |
| `POST /sdk/run {"name":"hug","speed":5}` | `moveInterface(name)` 或 `moveInterface(name, speed)` |
| `POST /sdk/stop` `POST /sdk/release` | `stopMove(cb,false)` / `realeaseControl_All()` |
| `POST /sdk/led {"color","action"}` | `SystemMethod` 静态方法，失败退回裸广播 |
| `POST /sdk/sys {"what"}` | `poweroff` / `reboot` / `adb_on` / `adb_off` |
| `POST /sdk/flag {"battery_low"}` | `setBatteryLow(boolean)` |
| `POST /sdk/forceready` | 强制标记引擎就绪 |
| `GET /console/sdk` | 一次取回 caps + stat + events |

## 9. 能调 / 不能调

可用：

- 动作：内置动作（普通 + 舞曲）、`ai_*` 自编姿态、动作序列、节奏控制、指定速度
- 灯光：5 色 × 4 动作共 20 种组合
- 系统：关机、重启、开 / 关 ADB、低电量标志
- 事件：44 种硬件事件回流
- 状态：11 个引擎标志 + 三关节角度
- 常量：关节行程与限位、动作速度枚举、串口直写通道

不可用：

| 项 | 原因 |
|---|---|
| 走 SDK 反射的高频位置回读 | 原厂没有对外的高频读接口，但它自己在广播位置（`ACTION.ENGSTATUS`），改用广播收，见 `motion.md` |
| 在线动作库 / OTA | 服务器已停服 |
| 被 R8 裁掉的常量类 | 类体消失，只能直接写字面量 |
| SDK 初始化 / 权限申请流程 | 门面需要 `BASEINIT` 与 Activity 生命周期配合，在外部 App 进程里跑不完整。做法是绕开初始化：动作走 `EngineService`，LED / 电源走广播 |

`poweroff` / `reboot` / `adb_off` 会直接影响设备可用性。`reboot` 有二次确认；
`adb_off` 关掉 ADB 后如果没有别的调试通道，只能靠设备本机设置重新打开。

## 10. 复现方法

1. 从 APK 解出 `classes*.dex`
2. 列出类清单（v1.5.10 里 `xldbasesdk` 相关类共 81 个）
3. 拉方法 / 字段签名（含混淆候选类的完整 `public` 方法表、字符串常量、静态字段）
4. 找方法体里的字符串 —— 带 `…` 后缀那一段就是该方法实际发的广播 / 读的文件路径 / 打的日志
5. 同名类在 tvbtx（全名）与 v1.5.10（混淆）里各看一遍，建立映射表

判定新接口能否用的三条经验：

- 方法体里只有一行 `sendBroadcast` ⇒ 可直接调，不用 init
- 碰到 `EngineService` ⇒ 一定可以通过 `EngineBinder` 拿到实例
- 出现 `batteryLow` / `move` 判断 ⇒ 是门面，需要绕开
