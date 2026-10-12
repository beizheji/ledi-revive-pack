# 显示、情绪与灯光

屏幕上与灯光里的一切：表情、情绪、灯与灯语、屏幕文字与图片、屏幕朝向、音量。

## 1. 表情（屏幕上的脸）

| 端点 | 说明 |
|---|---|
| `GET /faces` | 可用表情名清单（`FaceController.listFaces()` 现读原厂资产） |
| `GET /faces_detail` | 表情详细信息 |
| `POST /face {"name":...}` | 切换表情 |
| `POST /faceoff` | 关掉表情显示 |
| `POST /facesound` | 表情音效 |

表情是原厂资产，名字随版本变化，不硬编码。名称必须先通过 `GET /faces` 取得再使用。

## 2. 情绪

| 端点 | 说明 |
|---|---|
| `GET /emotion` | 当前情绪 + 两个维度 + 它现在会让待机摆哪张脸 |
| `POST /emotion {"name":"happy"}` | 按情绪名切换，只认原厂词汇表内的名字 |
| `POST /emotion {"code":10300}` | 按原厂数字码切换 |
| `POST /emotion {"reset":true}` | 回到基线情绪 |

情绪词汇从原厂 `assets/emotions.xml` 现读，不硬编码。表情与情绪的绑定关系没有从原厂逆出来，
两者是独立设置。

## 3. 灯光

```bash
curl -s -X POST -H 'Content-Type: application/json' -d '{"color":"blue","action":"on"}' $BASE/led
curl -s -X POST -H 'Content-Type: application/json' -d '{"color":"all","action":"off"}' $BASE/led
```

| 参数 | 取值 |
|---|---|
| `color` | `r` `g` `b` `y` `p` `all` |
| `action` | `on` `off` `blinkfast` `blinkslow` |

颜色 × 动作共 20 种组合。原厂 SDK 只为其中 13 种提供了独立方法，因此实现分两条路：

- 12 条命中原厂方法（`Function.SystemMethod` 的单字母静态方法）。
- 8 条走裸广播兜底，广播 action 按 `com.auric.intell.xld.os.led.<颜色>.<动作>` 拼接。

`POST /light` 与 `POST /sdk/led` 是另两条灯光入口，行为一致。

## 4. 灯语

思考与在听共用同一盏灯，优先级：绿（思考）> 蓝（在听）> 灭。

| 状态 | 灯 |
|---|---|
| 机器人在思考 | 绿灯常亮 |
| 机器人在听 | 蓝灯常亮 |
| 都不在 | 灭 |

| 端点 | 说明 |
|---|---|
| `GET /listenled` | 听语灯状态 |
| `POST /listenled {"mode":"blue_on|blue_blink|all_off"}` | 设置听语灯模式 |
| `GET /ledselftest` | 灯语自检（8 种布尔组合穷举） |

灯态写入收敛到一个出口（`ledState()` + `ledWrite()`）。心跳与语音各写一份会出现「谁后写谁赢」，
导致绿灯被心跳覆盖而静默失效。

## 5. 屏幕

| 端点 | 说明 |
|---|---|
| `POST /display {"text":...}` | 在屏幕上显示一段文字 |
| `POST /displayoff` | 清屏 |
| `POST /img {"data":"base64(PNG/JPG)","max":800}` | 在屏幕上显示一张图片。图片由外部读文件并编码，设备只负责解码显示 |
| `GET /rotation` | 屏幕朝向锁状态 |
| `POST /rotation {"on":false}` | 放开自动旋转（默认锁横向） |
| `POST /rotation {"value":0}` | 0/1/2/3 = 0°/90°/180°/270° |
| `GET /screen` | 屏幕状态：屏保 / 深睡 / 旋转锁 / 当前显示内容 |

屏幕日志浮层（`LogOverlay`）：

| 端点 | 说明 |
|---|---|
| `POST /logwin {"on":true,"lines":12,"size":10,"x":0,"y":0}` | 把实时日志铺在屏幕左下角，字段均可省略 |
| `GET /logwin` | 浮层状态 |
| `GET /pad` `POST /pad` | 与 `/logwin` 同义，是「打开带控件的 log 面板」的入口 |

浮层面板含三块内容：左侧为触摸区（左脸 / 右脸 / 头顶）与日志尾部、状态头；
右侧为三条电机滑条与陀螺仪 3D。电机滑条显示 `原始计数 (换算角度)`，并会按读回值自动摆到当前位置
（用户正在拖动的那条不抢）。

**背光不可调**：屏幕亮度与背光的三种控制路径在真机上全部无效。v198 曾实现「叠黑压暗」，
v201 已整体移除。

## 6. 音量

| 端点 | 说明 |
|---|---|
| `GET /volume` | 读当前音量与上限 |
| `POST /volume {"v":8}` | 设绝对音量（0..15），也接受旧参数名 `{"n":8}` |
| `POST /volume {"delta":2}` | 相对调整，语音「声音大一点」走这条 |

音量写入收敛到单一出口，重复实现会出现一份夹上限、一份不夹的分叉。

播放语音期间不要用机器人自己的喇叭播放测试音频；把音量拉满播放会把设备弄重启。

## 7. 活泼化与待机

| 端点 | 说明 |
|---|---|
| `GET /live` | 活泼化状态 |
| `POST /live {"on":true,"idle_every":3,"idle_motion_every":6}` | `on` 为总开关（唤醒呼应 / 应答兜底表情 / 待机微动 / 待机动作） |
| `POST /idle` | 待机动作间隔（单位「拍」，1 拍约 8 秒，0 = 关掉待机动作） |

待机动作是各关节 ±5 度的微动（`MotorPose.drive`）。

## 8. 相关端点

| 端点 | 说明 |
|---|---|
| `GET /faces` `POST /face` `POST /faceoff` | 表情 |
| `GET /emotion` `POST /emotion` | 情绪 |
| `POST /led` `POST /light` `POST /sdk/led` | 灯光 |
| `GET /listenled` `POST /listenled` | 灯语 |
| `POST /display` `POST /displayoff` `POST /img` | 屏幕文字与图片 |
| `GET /rotation` `POST /rotation` | 屏幕朝向 |
| `GET /volume` `POST /volume` | 音量 |
| `GET /live` `POST /live` `POST /idle` | 活泼化与待机 |
| `POST /logwin` `GET /logwin` `/pad` | 屏幕日志浮层 |
