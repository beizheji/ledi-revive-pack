# 系统

设备自身这一层：信息读取、网络与通道、屏保与深睡、进程托管与自恢复、屏幕投屏与远程操作、诊断自检。

## 1. 状态与信息

| 端点 | 说明 |
|---|---|
| `GET /health` | 探活 |
| `GET /status` | 综合状态：版本、运行时长、屏保、冻结、电量、引擎等 |
| `GET /sysinfo` | App 版本、运行时长、内存、存储 |
| `GET /state` | 一句话汇总：电量 / 屏保 / 深睡 / 音量 / 姿态 / 最近触摸 / 是否常听 |
| `GET /time` | 当前日期与时间 |
| `GET /battery` | 电量对象：格数、电压、温度、充电状态、数据来源、新鲜度 |
| `GET /logs?n=20` | 运行日志尾部 |

`GET /status` 里的 `battery_detail` 与 `GET /battery` 是同一个 `Battery.info(ctx)`，不各写一份解析。

`GET /status` 中与进程和自恢复相关的字段：

| 字段 | 含义 |
|---|---|
| `revive.pid` | 当前进程 PID |
| `revive.proc_starts` | 进程启动计数器 |
| `revive.counts.replaced` | 被替换（重装）次数 |
| `revive.proc_uptime_s` | 本进程已运行秒数 |
| `crashguard.*` | 主线程崩溃盾状态 |

判断「新装的包是否在跑」要看这几个计数器，不要看 PackageManager 里的 versionName —
后者只反映磁盘上的包。

## 2. 网络

| 端点 | 说明 |
|---|---|
| `GET /netinfo` | WiFi SSID、本机 IP、是否联网（只读） |
| `GET /btinfo` | 蓝牙开关、本机名、连接状态（只读） |
| `GET /adbinfo` | 5555 是否在监听、端口、可用的 scrcpy 命令（只读） |
| `GET /adbwifi` `POST /adbwifi {"on":true}` | 无线 ADB 开关（写 `service.adb.tcp.port=5555`） |
| `GET /bt` `POST /bt {"on":true}` | 蓝牙开关。关闭时记住这是用户关的，看护逻辑不再自动开回来 |
| `POST /pan` `GET /pan` | 蓝牙网络共享（无 WiFi 时的兜底链路，开启后机器人变为 `192.168.44.1`） |
| `POST /wifi` | 配网（红线，AI 不可调） |
| `POST /config` | 网关 / 网络配置 |

`wifi` / `bt` / `adbwifi` 的**修改**属于安全红线，只读查询走 `netinfo` / `btinfo` / `adbinfo`。

## 3. 屏保与深睡

| 端点 | 说明 |
|---|---|
| `GET /sleep` | 屏保状态 |
| `POST /sleep {"on":true,"bright":1.0}` | 进屏保：铺满图 + 闸住功能 |
| `POST /sleep {"on":false}` | 叫醒 |
| `POST /sleep {"img":"<base64>"}` | 设置屏保图 |
| `GET /deepsleep` | 深睡状态：`deep` / `sleeping` / `idle_stopped` / `freeze_mode` |
| `POST /deepsleep {"on":true}` | 只设置「进屏保时冻结谁」，不改变当前睡眠状态 |
| `POST /deepsleep {"deep":true}` | 立即进入深睡 |

深睡是长按头顶键进入的那一档：进屏保时冻结原厂进程。头顶键由原厂广播驱动，
冻结原厂后头顶键也叫不醒，因此 `/deepsleep` 必须在屏保白名单里，它是唯一的远程解药。

接管设备的顺序：第一条必须是 `POST /sleep {"on":false}`。屏保期间白名单外的端点一律返回
`{"error":"sleeping"}`，动作类端点全部不可用。

## 4. 进程与自恢复

| 端点 | 说明 |
|---|---|
| `GET /freezer` | 上一次冻结 / 解冻动了哪些进程、是否成功 |
| `POST /freeze {"main":true}` | 立刻冻结原厂进程（屏保会自动做，这个用于验收） |
| `POST /thaw` | 立刻解冻。冻结出问题时的远程解药 |
| `GET /revive` | 复活账本：本进程被谁拉起、停机多久、各通道救过几次、最近 6 次记录 |
| `POST /revive {"in_s":45}` | 排一个 45 秒后的精确唤醒闹钟（验收用） |
| `POST /crashprobe {"main":true}` | 向主线程投一个必抛异常的 Runnable，用于验收崩溃盾。立即回执，结果看 `GET /status` |
| `GET /shell` `POST /shell {"cmd":"..."}` | 远程 shell，以 uid=1000(system) 执行；`{"root":1}` 走 `su -c`（红线，AI 不可调） |
| `POST /kill` | 杀进程（红线） |

`/freezer` `POST /freeze` `POST /thaw` 必须在屏保期间可调用，否则冻结出错后只剩重启一条路。

## 5. 桌面

| 端点 | 说明 |
|---|---|
| `GET /home` | 默认桌面 / 目标桌面 / 已记录 / 候选数 / 是否收敛 |
| `POST /sethome` | 把当前桌面偏好写到目标 |

桌面锁定在原厂机器人 App（不是 agent），三级兜底见 `HomeSetter`。

## 6. 屏幕投屏与远程操作

投屏（三条「看」的端点在 `HttpServer` 里、路由之前接住，因为它们返回二进制字节流，
且需要在屏保期间可用）：

| 端点 | 说明 |
|---|---|
| `GET /mirror.h264` | 屏幕视频流（MJPEG / H.264），响应头 `X-Ledi-Width` / `X-Ledi-Height` 给出屏幕像素尺寸 |
| `GET /mirror.jpg` | 单张截图 |
| `GET /mirror/stat` | 投屏状态 |
| `POST /console/swipe` | 把网页上的点击 / 拖拽 / 按键 / 打字送回机器人屏幕 |
| `POST /console/tap` `/console/key` `/console/text` `/console/hold` | 同一组注入接口 |

坐标一律使用机器人屏幕像素（与 `X-Ledi-Width/Height` 同一坐标系），页面按显示比例换算，
浏览器窗口大小不影响落点。

## 7. 网页控制台

`GET /` 返回 `assets/console.html`，在路由分发之前接住，不受屏保闸影响。
网页里的按钮通过 `POST /console/agent` 万能转发（请求体 `{"path":"/motion","body":{...}}`）。

控制台自带的聚合端点：`GET /console/config`（动作 / 舞蹈 / 表情 / 预设姿态清单）、
`GET /console/state`（传感器）、`GET /console/sdk`（一次取回 caps + stat + events）、
`GET /console/appinfo`（版本信息）。

## 8. 诊断与自检

全部为纯静态或纯 JVM 自检，不吃 Context、不联网。

| 端点 | 自检内容 |
|---|---|
| `GET /abilitiesselftest` | 权限矩阵：红线拒绝、清单自洽、实现齐备 |
| `GET /capsselftest` | 能力索引 |
| `GET /emotionselftest` | 情绪系统 |
| `GET /ledselftest` | 灯语（8 种布尔组合穷举） |
| `GET /memoryselftest` | 长期记忆 |
| `GET /thinkselftest` | 多轮思考 |
| `GET /toneselftest` | 调性 |
| `GET /workflowselftest` | 工作流 |
| `GET /attitudetest` | 姿态数学：重力不变式、roll 绕 Y、pitch 绕 X |
| `GET /motordiag` | 读回链路：类在不在、收了几帧、静态字段兜底结果 |
| `GET /bootlog` | 启动溯源日志（本 ROM 屏蔽 App 的 logcat，排障只能看这个文件） |

触摸自测：`POST /touchtest` 让 App 自己发一次原厂触摸广播，走完整条「广播 → 接收 → 处理」链路。
直接用 `adb am broadcast` 在这台 ROM 上不会返回（它在等一个永远不会来的结果接收者），脚本会卡死。

## 9. 相关端点

| 端点 | 说明 |
|---|---|
| `GET /health` `GET /status` `GET /sysinfo` `GET /state` `GET /battery` `GET /time` `GET /logs` | 状态与信息 |
| `GET /netinfo` `GET /btinfo` `GET /adbinfo` | 只读网络信息 |
| `GET /adbwifi` `POST /adbwifi` `GET /bt` `POST /bt` `POST /pan` | 网络通道开关 |
| `GET /sleep` `POST /sleep` `GET /deepsleep` `POST /deepsleep` | 屏保与深睡 |
| `GET /freezer` `POST /freeze` `POST /thaw` `GET /revive` `POST /revive` `POST /crashprobe` | 进程与自恢复 |
| `GET /home` `POST /sethome` | 桌面 |
| `GET /mirror.h264` `GET /mirror.jpg` `GET /mirror/stat` `POST /console/swipe` | 投屏与注入 |
| `GET /` `GET /console/*` | 网页控制台 |
| 各类 `*selftest` | 自检 |
