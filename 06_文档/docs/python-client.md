# Python 客户端 `AgentLink`

参考实现在改造工程里（不在本仓库）。本文只记录它的调用面与口径，
照此可以自己实现一个客户端；下面示例里模块名用的是 `ledi_link`，你自己的叫什么都行。

## 1. 连接与通道

```python
from ledi_link import AgentLink

link = AgentLink("192.168.1.8:8899")
print(link.health())        # (True, {...})
print(link.link_status())   # 通道现状
```

| 方法 | 说明 |
|---|---|
| `health()` | 探活，返回 `(ok, dict)` |
| `link_status()` | 通道现状：`addr` / `enabled` / `via` / `bt_ready` / `bt_calls` / `bt_hits` / `last_bt_error` |
| `attach_bt(link)` | 挂上或更换蓝牙隧道；传 `None` 拆掉，退回纯 HTTP |
| `bt_ready()` | 是否有蓝牙兜底可用 |

行为约定：

- 机器人不在线时一切调用返回 `(False, 原因)`，不抛异常。
- 只有连接层失败（超时 / 被拒 / 不可达 / 没配地址）才回落到蓝牙。
  机器人回了 HTTP 4xx/5xx 不算连接失败，不换通道。
- 蓝牙回落走 SPP 隧道，路径、参数、返回与 HTTP 完全一致。

## 2. 原厂 SDK 直调

| 方法 | HTTP | 说明 |
|---|---|---|
| `sdk_caps()` | `GET /sdk/caps` | 原厂接口能力探测 |
| `sdk_stat()` | `GET /sdk/stat` | 引擎状态 |
| `sdk_events()` | `GET /sdk/events` | 事件流 |
| `sdk_series(names, delay=0)` | `POST /sdk/series` | 动作序列 |
| `sdk_dance(name="")` | `POST /sdk/dance` | 指定舞蹈，name 空 = 随机 |
| `sdk_bpm(bpm=60)` | `POST /sdk/bpm` | 随机舞，参数是节奏 bpm 不是次数 |
| `sdk_run(name, speed=0)` | `POST /sdk/run` | 按名播动作，可带速度 |
| `sdk_stop()` | `POST /sdk/stop` | 停止 |
| `sdk_release()` | `POST /sdk/release` | 交还控制权 |
| `sdk_led(color, action)` | `POST /sdk/led` | 灯光，拿不到原厂方法时自动退回广播 |
| `sdk_sys(what)` | `POST /sdk/sys` | `poweroff` / `reboot` / `adb_on` / `adb_off` |
| `sdk_battery_low(low)` | `POST /sdk/flag` | 置 / 清低电量标志 |

```python
link.sdk_series(["hug", "handshake"], delay=200)
link.sdk_bpm(120)
link.sdk_led("blue", "on")
```

## 3. 姿态

```python
name, err = link.define_pose("wave", "500,-60,0,60;500,60,0,-60", run=False)
# 成功时 name 形如 'ai_wave'
link.run("ai_wave")
link.remove("ai_wave")
```

| 方法 | HTTP | 说明 |
|---|---|---|
| `define_pose(name, spec, run=False)` | `POST /pose` | 写姿态文件，返回 `(动作名, 错误)` |
| `run(name)` | `POST /poserun` | 播放已定义姿态 |
| `remove(name)` | `POST /posedel` | 删除姿态 |

## 4. 姿态规格

`normalize_frames(spec)` 把任意写法解析成校验过的帧列表，每帧 4 个数
`[时长ms, 关节1, 关节2, 关节3]`，越界抛 `ValueError` 并把原因回给模型。

| 函数 | 作用 |
|---|---|
| `preset_menu(max_items=20)` | 列出内置动作菜单 |
| `normalize_frames(spec)` | 任意规格 → 校验过的帧列表 |
| `frames_to_text(frames)` | 帧列表 → 原厂动作文件正文 |
| `compile_pose(spec)` | 一步到位：任意规格 → 已校验的原厂文本 |
| `describe(spec)` | 人读摘要，如 `3帧/1500ms` |

## 5. 通用 HTTP 与流式

| 方法 | 说明 |
|---|---|
| `get(path, **q)` | 发 GET |
| `post(path, obj)` | 发 POST（JSON） |
| `get_cmd(path, params, timeout)` | 带超时的 GET |
| `stream_h264(...)` | 拉投屏流 |

## 6. 判定口径

| 方法 | 端点 | 用途 |
|---|---|---|
| `health()` | `GET /health` | 探活，只判连接层 |
| `link_status()` | 本地 | 最近一次走 http 还是 bt、兜底命中次数 |
| `sdk_caps()` | `GET /sdk/caps` | 先证明能力在，再调 |
| `sdk_stat()` | `GET /sdk/stat` | 读到 `null` 就如实显示，不编造数值 |

判成功只看 `ok` 与 body 顶层。本 ROM 的 HttpBridge 一律回 HTTP 200，不要拿状态码当判据。
