# 权限与边界

两处规格：

- 权限矩阵：`Abilities.SPECS`（三态：read / write / plan / forbid），`GET /abilities` 可查
- 屏保白名单：`SleepGate.ALLOW`（56 条 + `GET /console/` 前缀）

要改就改这两处，不要在别处另加判断。

## 1. 权限矩阵

| 态 | 语义 | 条数 |
|---|---|---|
| `read` | 只读查询，零副作用（含 wifi / adb / 蓝牙 / 开关机**状态**） | 16 |
| `write` | 有限更改：可逆、不断连、不碰系统底层 | 21 |
| `plan` | 编排 / 间隔 | 2 |
| `forbid` | 安全红线：只读不可改，AI 一律拒绝 | 14 |

### read（16）

`state` `battery` `time` `faces` `motions` `netinfo` `adbinfo` `btinfo` `screen`
`sensors` `sysinfo` `pose` `logs` `micinfo` `doc` `memory`

### write（21）

`volume` `face` `motion` `led` `say` `stop` `emotion` `display` `displayoff`
`live` `idle` `listen` `think` `name` `call` `tone` `style` `remember` `forget`
`rotation` `wake`

### plan（2）

`workflow` `wait`

### forbid（14）

| op | 说明 |
|---|---|
| `wifi` | 改 WiFi（配网 / 开关网卡）。只能 `netinfo` 查 |
| `bt` | 改蓝牙开关 / 配对。只能 `btinfo` 查 |
| `adbwifi` | 开关无线调试。只能 `adbinfo` 查 |
| `reboot` `shutdown` `poweroff` | 重启 / 关机 |
| `closeadb` | 关闭 adb 通道 |
| `killsystem` | 杀系统进程 |
| `uninstall` | 卸载应用 |
| `deepsleep` `freeze` `kill` | 会失联，只允许人工现场操作 |
| `shell` | 任意 shell 命令，无法审计 |
| `wipe` | 清除数据 / 恢复出厂 |

红线拒绝下沉到 `Abilities.execute()`：无论从 HTTP、网页按钮还是语音 / 脚本进来，都在同一个函数里被挡。
拒绝形如 `{"error":"forbidden ..."}`，并给出替代的只读能力提示（例如问 `wifi` 时提示用 `netinfo`）。

注意区分「端点存在」与「AI 可用」：`POST /wifi`、`POST /bt`、`POST /shell` 这些端点在服务里存在，
人工或脚本可以直接调；但在 AI 的权限体系里它们是红线，AI 调用会被 `Abilities` 拒绝。

## 2. 屏保白名单

进入屏保或深睡后，白名单外的端点统一返回：

```json
{"error":"sleeping"}
```

白名单共 56 条 + 前缀规则 `GET /console/`（`ui/SleepGate.java`）。加减放行项只改这一处。
两道回归闸门：`_sleepgate_check.py`（与冻结基准对差）+ `SleepGate.selftest()`（纯 JVM 行为自检）。

### 白名单全量

| 分组 | 端点 |
|---|---|
| 睡眠控制 | `GET /sleep` `POST /sleep` `GET /deepsleep` `POST /deepsleep` |
| 状态查询 | `GET /status` `GET /health` `GET /battery` `GET /caps` `GET /abilities` `GET /memory` `GET /workflow` `GET /presence` `GET /recadir` `GET /kws` `GET /listen` `GET /bt` `GET /tone` `GET /emotion` `GET /listenled` `GET /listenface` `GET /freezer` `GET /revive` |
| 自检 | `GET /thinkselftest` `GET /workflowselftest` `GET /capsselftest` `GET /ledselftest` `GET /abilitiesselftest` `GET /toneselftest` `GET /memoryselftest` |
| 进程（解药） | `POST /freeze` `POST /thaw` `POST /revive` `POST /kill` |
| 身份与称呼 | `POST /presence` `POST /name` `POST /tone` |
| 听语指示 | `POST /listenled` `POST /listenface` `POST /listen` |
| 记忆 | `POST /memory` |
| 采集与唤醒内核 | `POST /recaudio` `POST /kws` `POST /kws/clear` `POST /kws/train` `POST /kws/test` `POST /kws/probe` |
| 蓝牙 | `GET /bt` `POST /bt` |
| 投屏与注入 | `POST /console/tap` `POST /console/swipe` `POST /console/hold` `POST /console/key` `POST /console/text` `POST /console/mirror` |
| 其他 | `POST /headkey` `GET /programmer` |
| 前缀 | `GET /console/` 开头的全部 |

### 为什么这些必须在白名单里

1. 深睡会冻结原厂进程（含串口控制），冻结后头顶键也叫不醒，`POST /deepsleep` 是唯一的远程解药。
2. 冻结出错只会在屏保期间暴露，所以 `POST /thaw`、`POST /freeze` 必须在白名单里，
   否则只剩重启一条路。
3. 复活与崩溃盾相关的 `POST /revive`、`POST /kill` 同理。

### 控制台不受闸

`GET /` → `assets/console.html` 在总闸之前被接住，屏保期间也能打开控制页。
同网访问 `http://<IP>:8899/`。

## 3. 判定口径

本 ROM 的 HttpBridge 一律返回 HTTP 200，错误响应同样是 200，`{"error":...}` 与成功同码。

- 判成功 / 失败只看响应 body 顶层。
- 判失败认精确串 `{"error":...}` / `{"ok":false,...}`。
- 不要用「body 里包含 `error` 字样」作判据：很多正常响应本身带 `"error":""` 字段。
- 不要用「body 里包含某关键字」作判据：例如 `/logwin` 的日志内容里可能出现 `"layer":"error"`。

失败统计不能只统计成功：

- 只统计成功 = 假绿灯。必须同时报 `write_errors` 与 `dropped_frames`。
- 未经证实的能力写「未证实」，不写「伪造」或「不可能」。

## 4. 破坏性操作

| 操作 | 纪律 |
|---|---|
| `poweroff` / `reboot` / `adb_off` / `deepsleep` | 先说明再执行；控制台里 `reboot` 有二次确认 |
| 关闭 ADB | 关掉后若没有别的调试通道，只能靠设备本机设置重新打开 |
| 关闭 WiFi | `svc wifi disable`，关掉即断开 8899 |
| 冻结原厂 | 先备份；`POST /thaw` 是远程解药；`POST /deepsleep` 在屏保白名单里 |

## 5. 接管与回退

接管一台处于屏保状态的设备，第一条请求必须是：

```bash
curl -s -X POST -H 'Content-Type: application/json' -d '{"on":false}' $BASE/sleep
```

屏保期间动作类端点全部返回 `{"error":"sleeping"}`，不先叫醒什么都做不了。

回退：改造以独立包名 `com.ledi.agent` 安装，与设备上原厂 App 无关，`adb uninstall com.ledi.agent`
即可完全移除，不需要动 `/system`。

## 6. 相关端点

| 端点 | 说明 |
|---|---|
| `GET /abilities` | 权限矩阵现状（read / write / plan / forbid） |
| `GET /sleep` `POST /sleep` `GET /deepsleep` `POST /deepsleep` | 屏保与深睡；其间白名单外的端点统一被拦 |
| `POST /sdk/sys {"what":"poweroff\|reboot\|adb_on\|adb_off"}` | 原厂系统层：关机 / 重启 / ADB 开关 |
| `GET /freezer` `POST /freeze` `POST /thaw` `GET /revive` `POST /revive` | 进程冻结与复活 |
| `POST /crashprobe` | 崩溃盾验收（自检用） |

端点全表见 [`http-api.md`](http-api.md)。
