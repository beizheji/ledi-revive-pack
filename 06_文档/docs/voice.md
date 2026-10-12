# 语音

语音链路分四段：唤醒 → 识别 → 处理 → 播报。识别与合成都走云端；唤醒与部分命令词走本地。

## 1. 唤醒

唤醒引擎是原厂百度离线唤醒词（`assets/WakeUp.bin` + `bds-easr-license.lic`）。
`GET /wakestatus` 查状态，`POST /wake` 主动发起一次对话。

| 端点 | 说明 |
|---|---|
| `GET /wakestatus` | 唤醒引擎状态 |
| `POST /wake` | 主动发起一次语音对话 |
| `GET /vocab` `POST /vocab` | 唤醒词表热替换。`{"preset":"base|safe|full"}` 三档；`{"words":"你好,乐迪,超级飞侠"}` 自定义；`{"reset":true}` 复位 |
| `POST /waketest {"sec":6}` | 唤醒体检：自己录 PCM 直接喂解码器，绕开喇叭与 AEC。可加 `pcm=` / `gain=` 回灌设备上的裸 PCM |
| `POST /forcewake {"why":"..."}` | 跳过唤醒词环节，直接进入「听一句话」。用于远程验收 |

`POST /forcewake` 跳过的是唤醒词，但「听一句话」本身仍是麦克风加识别，房间里没人说话时也拿不到文本。

自建唤醒内核（`LocalWake`：MFCC + VAD + DTW），用于原厂引擎不出命中时：

| 端点 | 说明 |
|---|---|
| `POST /kws/probe {"pcm":...}` | 与模板比对，返回距离分数 |
| `POST /kws/train {"pcm":...}` | 用一段 PCM 建模板 |
| `POST /kws/test` | 走一遍完整判定 |
| `POST /kws/clear` | 清空模板 |
| `GET /kws` | 内核状态 |

模板必须在设备上由真人录音产生，不使用预置模板。

## 2. 识别

| 端点 | 说明 |
|---|---|
| `POST /asrtest {"sec":8}` | 离线 ASR 端到端探测（阻塞 sec 秒，期间对机器人说话）。可加 `grammar=` 指定语法文件 |
| `GET /online` `POST /online {"on":true}` | 在线优先开关（默认开）。关掉后只用离线词表 |
| `POST /ilisten {"sec":6}` | 开麦听一轮（阻塞），返回识别结果与电平仪表 |
| `POST /ia` | 在线听写 |
| `GET /ia` | 在线听写状态 |
| `POST /iatest` | 文件输入测试（`infile=`，避免麦克风变量混入） |
| `GET /vocab` | 当前词表镜像 |

离线识别是语法受限解码器，词典固化在声学模型里，只能识别词表内的整句。
任意一句话的识别只能走云端。云端凭据沿用设备原有配置，本仓库不含任何凭据。

录音（用于自建唤醒的模板采集）：

| 端点 | 说明 |
|---|---|
| `GET /recadir` | 已录文件清单（名 + 字节数），用于核对传输完整性 |
| `POST /recaudio {"sec":6,"name":"pos1"}` | 录一段 16k / 单声道 / 16bit 原始 PCM 到 `/sdcard/ledi_kws/<name>.pcm` |

`POST /recaudio` 必须带 `src=`：

| `src` | 含义 |
|---|---|
| `1`（MIC，默认） | 经过 AEC + NS + AGC 的音频。自动增益会把电平压平，峰值恒定在 9 附近，听不出差异 |
| `1998`（UNPROCESSED） | 未经处理的信号。取真实信号、做自回环测试用这个 |

## 3. 播报

| 端点 | 说明 |
|---|---|
| `POST /say {"text":...}` | 用机器人自带嗓音念一句话 |
| `POST /tts {"text":...}` | 文字转语音 |
| `GET /ttsstatus` | 当前是否在播放合成语音 |
| `POST /ttsstop` | 停止播放 |

原厂 TTS 引擎，不经过大模型。

## 4. 对话

| 端点 | 说明 |
|---|---|
| `GET /dialog` `POST /dialog` | 对话通路。`{"on":true,"max_ms":10000,"silence_ms":1000,"turns":3}`；`{"continuous":true}` 为常识别模式；`{"listen":true}` 立即听一轮 |
| `GET /asrdialog` `POST /asrdialog` | 唤醒后是否「先听再说」。`{"on":true,"sec":5}` |
| `POST /dialogturn {"text":"...","why":"..."}` | 远程验收整轮应答（连「听」也跳过），直接喂文本走完整轮 |
| `GET /talk?n=50` `POST /talk {"action":"clear"}` | 本地对话流水（每条带 tid）；clear 只清内存环，磁盘 jsonl 保留 |
| `GET /talk/status` | 记忆长度、落盘位置、上一次记了什么 |

对话通路的行为：唤醒后听一整句，说完静音约 1 秒交给 AI，播报期间可被语音打断。

**常听**（`POST /listen {"on":true}`）是 `/dialog {"continuous":true}` 的可持久化版本。
常听的开关落在 prefs（`listen_auto` / `listen_grammar` / `think_on`），`onCreate` 末尾读取并自动开启。
深睡会停掉常听，退出深睡时需要重新打开。

常听与唤醒引擎共用同一路麦克风，开启常听等于停掉唤醒引擎。

## 5. 意图与多轮

| 端点 | 说明 |
|---|---|
| `GET /intent` | 词表镜像、开关、上一句走的路径 |
| `POST /intent {"test":"讲个故事"}` | 只解析，不执行。命中本地直通返回 `local_reply`，否则返回易混候选与给模型的提示 |
| `POST /intentrun {"text":"跳个舞"}` | 与 `/intent` 相反：真执行（与真人说这句话走同一条路） |

本地直通是词表式的：判定分支的先后顺序即语义，匹配用关键词，`norm()` 会把全角标点归一。

多轮思考（`Thinking`）：

| 端点 | 说明 |
|---|---|
| `GET /think?goal=...&rounds=3` | 跑一次多轮思考 |
| `POST /think {"goal":"...","rounds":3}` | 同上 |
| `POST /think {"think_on":true}` | 只切换语音回复是否走多轮思考 |

模型与引擎之间的协议是纯文本的 `TOOL` / `FINAL` 两行格式（不使用 function calling），上限 6 轮。
两道闸：一次都没成功执行则打回；已完成工具调用但没有 `FINAL` 则补收尾。

## 6. AI 大脑

| 端点 | 说明 |
|---|---|
| `GET /ai` `GET /ai/status` | 直连 / 回落状态，可切换 |
| `POST /ai/ask {"text":...}` | 直接问一句 |
| `POST /ai/config` | 配置 |
| `GET /ptalk` `POST /ptalk` | 主动说话（事件 + 定时） |
| `POST /media` `POST /media/stop` | 媒体播放（音频 / 视频 / 图片 / 文字） |

## 7. 语音状态指示

| 端点 | 说明 |
|---|---|
| `GET /listenled` `POST /listenled {"mode":"blue_on|blue_blink|all_off"}` | 听语灯，默认蓝灯常亮 |
| `GET /listenface` `POST /listenface {"name":"recording"|"off"}` | 听语表情，`GET /faces` 取可用表情名 |

灯语与思考灯共用同一盏灯，优先级为绿（思考）> 蓝（在听）> 灭，具体见 `display.md`。

## 8. 相关端点

| 端点 | 说明 |
|---|---|
| `GET /forcewake` `POST /wake` `GET /wakestatus` | 唤醒与唤醒状态 |
| `GET /kws` `POST /kws` `POST /kws/train` `POST /kws/probe` `POST /kws/clear` | 本地唤醒内核（词表与模板） |
| `GET /ia` `POST /ia` `POST /iatest` `POST /asrtest` | 在线听写与识别自测 |
| `GET /vocab` `POST /vocab` `GET /listen` `POST /listen` `POST /ilisten` | 词表与常听 |
| `POST /say` `POST /tts` `GET /ttsstatus` `POST /ttsstop` | 播报 |
| `GET /talk` `POST /talk` `GET /talk/status` `GET /dialog` `POST /dialog` `POST /dialogturn` `GET /asrdialog` `POST /asrdialog` `POST /intentrun` | 对话与意图 |
| `GET /ai` `POST /ai/ask` `POST /ai/config` `GET /ai/status` | AI 大脑 |
| `GET /ptalk` `POST /ptalk` `POST /media` `POST /media/stop` | 主动说话与媒体 |
| `GET /listenled` `POST /listenled` `GET /listenface` `POST /listenface` | 听语灯与听语表情 |

端点全表见 [`http-api.md`](http-api.md)，机器可读清单见 [`endpoints.json`](../api/endpoints.json)。
