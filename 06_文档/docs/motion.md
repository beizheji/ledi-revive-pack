# 运动控制

机器人的运动（手臂、身体转向）由三关节舵机驱动。可通过 HTTP 走原厂通路控制，也可直接下发串口帧。

## 1. 通道

| 通道 | 端点 | 实际落到 |
|---|---|---|
| 动作名播放 | `POST /motion {"name":...}` | `MotionController`：查内置动作表，落到原厂动作文件 `/sdcard/xldBaseSDK/<名>` |
| 单帧角度直发 | `POST /motor {"j1","j2","j3","ms"}` | `EngineService.moveInterface(String, int)`（角度域） |
| 姿态文件 | `POST /pose` + `POST /poserun` | 往 `/sdcard/xldBaseSDK/<名>` 写明文动作文件，再按名播放 |
| 原厂引擎（序列 / 舞曲） | `POST /sdk/series` `dance` `bpm` `run` | `EngineService.baseAction` / `specialDance` / `radomDance` / `moveInterface`，见 `factory-sdk.md` |
| 停止 | `POST /stop`、`POST /motion {"name":"stop"}` | 原厂 `stopMove` |
| 释放力矩 | `POST /motorrelease` | `EngineService.realeaseControl_All()`（原厂拼写如此） |

`GET /motions` 列出可用动作名，`GET /motions_dance` 列出舞曲，`GET /poses` 列出已写姿态文件。

## 2. 动作名播放

```bash
curl -s -X POST -H 'Content-Type: application/json' -d '{"name":"hug"}'      $BASE/motion
curl -s -X POST -H 'Content-Type: application/json' -d '{"name":"dance_L2M3"}' $BASE/dance
```

- 动作名来自 `MotionController.MOTIONS`（普通动作）与 `MotionController.DANCE`（舞曲）两个独立数组，
  舞曲名不在普通动作表里。
- 动作名必须与表中的名字逐字一致，拼错返回错误而不是静默跳过。
- `GET /motionconfig` 可查 / 改启用状态（`POST /motionconfig`）。

## 3. 姿态文件

```bash
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"name":"ai_wave","spec":"start[500,-60,0,60],[500,60,0,-60]end"}' $BASE/pose
curl -s -X POST -H 'Content-Type: application/json' -d '{"name":"ai_wave"}' $BASE/poserun
curl -s -X POST -H 'Content-Type: application/json' -d '{"name":"ai_wave"}' $BASE/posedel
curl -s "$BASE/poseread?name=ai_wave"
```

`spec` 是原厂动作文件格式的正文，写入 `/sdcard/xldBaseSDK/<名>`（明文）。这是最安全的通路：
不碰协议、不拼帧，播放交给原厂引擎。

## 4. 单帧角度直发

```bash
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"j1":20,"j2":3,"j3":1,"ms":600}' $BASE/motor
```

返回示例：

```json
{"ok":1,"via":"moveInterface(text,int)","cmd":"start[600,20,3,1]end","clipped":false,"ms":2}
```

- `cmd` 是编码出来的动作文本；`clipped:true` 表示参数被限位钳位。
- `ms` 缺省取 `MotorPose.SLIDE_MS`（140）。
- 该端点不在屏保白名单里，屏保期间返回 `{"error":"sleeping"}`。

## 5. 串口帧格式

角度最终由 `EngineService.angleToAutoInstuction(int,int,int,int) → byte[12]` 编成帧，
经 `serialport.SerialPort` 写到 `/dev/ttyMT0`（115200）。

真帧样例：`FF FF F0 02 BA BA 02 02 00 02 BA D9`

| 字节 | 内容 |
|---|---|
| `[0]` `[1]` | `0xFF` `0xFF` 帧头 |
| `[2]` | `0xF0` 指令码 |
| `[3]` `[4]` | 时长（ms，大端 16 位） |
| `[5]` `[6]` | 关节1 目标位置（大端 16 位） |
| `[7]` `[8]` | 关节2 目标位置 |
| `[9]` `[10]` | 关节3 目标位置 |
| `[11]` | 校验：`~(byte)(Σ b[2..10])`，**有符号字节累加**后按位取反 |

校验必须按有符号 `byte` 累加（`aget-byte` 语义），按无符号累加会算出不同的校验字节。

帧头是 `FF FF F0`。工程里早期记录的 `AA 55 04 01…` 是误读。

## 6. 角度 → 目标位置（`angleToAutoInstuction` 还原）

原厂参数（`config.eng.EngDataPersist`）：

| 参数 | 值 | 含义 |
|---|---|---|
| `m()` | 220 | 关节1 行程（角度） |
| `n()` | 200 | 关节2 行程（角度） |
| `o()` | 220 | 关节3 行程（角度） |
| `p()`=`q()`=`r()` | 1024 | PWM / 编码器行程（10 位） |

step 1 — 角度归一化（三条分支不对称）：

```
关节1 / 关节3:  a > 90 ? (int)((a-90)*0.5)+90 : a
                然后 v = (v < 0) ? v : (int)(v * 0.8)      # 负值不乘 0.8
关节2:           v = (int)(a * 0.8)                         # 无条件
```

step 2 — 限位钳位：

| 关节 | 正值上限 | 负值下限 |
|---|---|---|
| 关节1 / 关节3 | 120 | -40 |
| 关节2 | 70 | -70 |

step 3 — 角度 → 位置增量（float32 精度）：

```
delta = (int)( (float)v / (float)行程 * (float)1024 )
```

step 4 — 叠加到当前位置：

```
v > 0 :  pos = 当前 + delta
v < 0 :  pos = 当前 - delta
```

当前位置取自 `EngDataPersist.d()/h()/l()`（`SharedPreferences "xldEngData"` 里的 2 字节大端值），
即上一条指令的落点。**位置是累积的**，同样的帧在不同起始姿态下落点不同。

关节物理含义（由原厂动作文件交叉验证）：

| 关节 | 部位 | 验证动作 |
|---|---|---|
| 关节1 | 左臂 | `liftLefthand = [1000,150,0,0]` |
| 关节2 | 身体转向 | `stTurnLeft = [500,0,70,0]` / `stTurnRight = [500,0,-70,0]` |
| 关节3 | 右臂 | `liftRighthand = [1000,0,0,150]` |

内置动作文件的取值范围：时长 0..13622 ms，关节1 -90..150，关节2 -100..100，关节3 -90..150，帧数 1..320。

动作文件文本格式：

```
start[500,-60,0,60],[500,60,0,-60]end
```

每帧 `[时长ms, 关节1, 关节2, 关节3]`，帧间用 `],[` 连接，整体包在 `start`…`end` 之间。

## 7. 位置回读与角度换算

```bash
curl -s $BASE/motorpos
```

```json
{"motor":{
  "raw":[373,429,374],
  "pct":[9,10,9],
  "ang":[1,3,1],
  "names":["电机1","电机2","电机3"],
  "src":"bcast/stop","ago_ms":3761,"frames":13,
  "zero_dropped":0,"arr_len":0,
  "last_cmd":"","last_cmd_via":"","last_cmd_ago_ms":-1,"last_cmd_ms":0,
  "engine":true}}
```

| 字段 | 含义 |
|---|---|
| `raw[3]` | 三个关节的原始位置，12 位编码器计数（0..4095） |
| `ang[3]` | 由 `raw` 按实测拟合换算出的角度（整数度）；该轴不可信时为 `null` |
| `pct[3]` | `raw` 占满量程的百分比（不是角度） |
| `src` | 最近一次更新的来源：`bcast`（广播中）/ `bcast/stop`（广播静止）/ `field`（反射静态数组兜底） |
| `frames` | 累计收到的广播帧数 |
| `last_cmd` | 最近一次下发的动作文本（下发值，不是读回值） |

数据来源有两条路，`MotorPose` 两条都走：

1. 广播：原厂 `EngineService$EngBroadCastReciver` 收 `com.auric.robot.xldbasesdk.ACTION.ENGSTATUS`，
   extra `ENGSTATUS` 是 hex 字符串。解码：去空格 → 转大写 → 每 2 字符 1 字节；
   取位 `m1=(b[3]&0x0F)<<8 | (b[4]&0xFF)`，m2 用 `b[7]/b[8]`，m3 用 `b[11]/b[12]`。
2. 反射静态数组 `eng1/2/3_LastPoss`（下标 0 为最新）。广播拿不到时兜底。

三路同时为 0 一律按「没有数据」处理（站立真值是 370/420/371，三路同 0 物理上不可能）。

换算（`MotorPose.angleOf`）：

```
angle = round( (raw - FIT_ZERO[axis]) / FIT_CPD[axis] )
FIT_ZERO = {370, 420, 371}
FIT_CPD  = {3.92, 3.38, 3.98}      # 计数 / 度
```

这三个零点与斜率是 v147 在这台机器上实测拟合的。实测点：j1 `0°→370` `30°→488` `45°→547`
`60°→607` `90°→723` `120°→783` `150°→844`；j2 `-50°→248` `0°→420` `50°→586`；
j3 `0°→371` `30°→490` `60°→608` `90°→729` `150°→845`。

换算的三条性质：

- j1 与 j3 高度对称（镜像安装、同减速比）。
- 0..90° 段线性（3.92~3.98 计数/度）；90° 以上压缩（j1 在 120°/150° 的等效斜率降到 3.44/3.16），
  线性式在高角度段会低估，误差可达十几度。
- 标定属于这台机器，拆装舵机后作废。

**用途**：面板显示与滑条定位。**不能作为控制回路**（`pos()` 只读回，用它闭环会震荡）。

v222 真机闭环实测（下发 20°）：

| 项 | 值 |
|---|---|
| 下发 | `POST /motor {"j1":20,"j2":3,"j3":1,"ms":600}` |
| `raw[0]` | 373 → 449 |
| 独立算出的期望 | `370 + 20 × 3.92 = 448.4` |
| 差 | 0.6 计数（约 0.2°） |
| `ang[0]` | 收敛到 20，且等于 `round((449-370)/3.92)` |

注：广播只在位置发生变化时上报。静止时 `frames` 不增长、`raw` 不变是正常状态。

## 8. 释放力矩

```bash
curl -s -X POST $BASE/motorrelease     # 立刻释放三个舵机力矩（可用手掰动）
curl -s $BASE/motorrelease             # 上一次释放的结果（含 1.5s 后补采的 after / delta_max）
```

原厂通路：`EngineService.realeaseControl_All()`，内部依次发 `0x1002` / `0x1001(obj=releaseIns)` / `0x1003`
三条消息，其中 `releaseIns = "FF FF FE FF FF FF FF FF FF 07"`。

释放后手臂会在无外力下随重力下垂，因此该端点不在屏保白名单里。

## 9. 相关端点

| 端点 | 说明 |
|---|---|
| `GET /motions` `GET /motions_all` `GET /motions_dance` | 动作名清单 |
| `POST /motion` `POST /dance` `POST /base` | 播放动作 / 舞曲 / 基础动作 |
| `POST /stop` | 停止当前动作 |
| `POST /pose` `POST /poserun` `POST /posedel` | 写 / 播 / 删姿态文件 |
| `GET /poses` `GET /poseread` | 列出 / 读取姿态文件 |
| `POST /motor` | 单帧角度直发 |
| `GET /motorpos` | 位置回读 + 换算角度 |
| `GET /motordiag` | 读回链路自检（类在不在 / 收了几帧 / 静态字段兜底结果） |
| `POST /motorrelease` `GET /motorrelease` | 释放舵机力矩 |
| `POST /gsensorcali` | 触发陀螺仪校准 |
| `POST /pan` `GET /pan` | 蓝牙网络共享开关（无 WiFi 时的兜底链路） |
