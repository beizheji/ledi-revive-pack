# HTTP 控制接口参考

> 机器人内置 HTTP 服务（默认端口 8899）的全部 199 条端点。
> 本文件由 `tools/extract_api.py` + `tools/gen_docs.py` 从 `CommandRouter.java` 自动生成，不要手改。

## 通用约定

- **请求**：`GET /path?k=v` 或 `POST /path` + JSON body。两者字段等价，路由里统一用 `field(body, query, key)` 取值。
- **响应**：一律 `HTTP 200` + JSON。**成功与否只看 body 顶层**，不要信 HTTP 码 —— 本 ROM 的 HttpBridge 无论对错都回 200。失败形如 `{"error":"..."}`。
- **屏保闸**：进入屏保/深睡后，白名单外的端点统一被拦（返回 `{"error":"sleeping"}`）。白名单见 `SleepGate`（56 条 + `GET /console/` 前缀）。
- **权限**：写类/红线类能力受 `Abilities` 权限矩阵约束，拒绝形如 `{"error":"forbidden ..."}`。

## 端点分布

| 域 | 端点数 | 覆盖 |
|---|---|---|
| `sys` | 18 | 状态、网络、ADB、屏幕、重启、进程与冻结 |
| `motion` | 21 | 动作播放、舵机直控、姿态文件、姿态数学 |
| `voice` | 38 | 云 ASR、唤醒、对话、TTS、意图、AI 大脑 |
| `sense` | 22 | 电量、头顶键、触摸注入、传感器、声源定位 |
| `media` | 9 | 音量、灯光、屏幕图片与旋转 |
| `cap` | 43 | 能力索引、权限矩阵、情绪、记忆、性格、工作流、思考 |
| `sdk` | 14 | EngineService 动作 / SystemMethod 灯光电源 / 事件流 |
| `probe` | 29 | 各引擎纯 JVM 自检、崩溃盾验收、启动日志 |
| `ui` | 5 | 屏保与深睡控制、控制台页面 |

## 系统 / 设备（`sys`，18）

> 状态、网络、ADB、屏幕、重启、进程与冻结

| 方法 | 路径 | 参数 | 说明 |
|---|---|---|---|
| `GET` | `/adbwifi` | — | 无线 ADB（手机端投屏用） · GET /adbwifi -> 状态（on/listening/ip/port/scrcpy 命令） · POST /adbwifi {"on":true} -> 一键开（写 service.adb.tcp.port=5555） · POST /adbwifi {"on":false} -> 一键关（写 -1） · POS… |
| `POST` | `/adbwifi` | `auto` | `AdbWifi.setAuto()` |
| `GET` | `/bt` | — | 蓝牙开关（v176） · GET /bt -> 蓝牙实况（on/state/keep/accept + 看护计数器） · POST /bt {"on":true} -> 开（并把"由我们长期看住"置回 true） · POST /bt {"on":false} -> 关（并且**记住是用户关的**，后勤不再自动开回来） |
| `POST` | `/bt` | `on` | `AgentService.setBtKeepOn()` |
| `POST` | `/freeze` | `main` | `ProcessFreezer.freezeAsync()` |
| `GET` | `/freezer` | — | 屏保期冻原厂（v140） · GET /freezer → 上一次冻/解冻动了哪些进程、成功没 · POST /freeze {"main":true\|false} → 立刻冻结原厂（验收用；屏保会自动做） · POST /thaw → 立刻解冻（ 冻歪了时的远程解药） |
| `GET` | `/health` | — | 状态 |
| `POST` | `/kill` | — | — |
| `POST` | `/name` | `name` | `Presence.json()` |
| `GET` | `/revive` | — | 复活通道 / 死给谁看（v134） · GET /revive → 复活账本：本进程是被**谁**拉起来的、停机多久、 · 各通道各救过几次、最近 6 次记录 · POST /revive {"in_s":45} → 排一个 45s 后的**精确唤醒闹钟**（验收用） · POST /revive {"period_s":40} → 把常驻心跳周期临时改成… |
| `POST` | `/revive` | — | — |
| `GET` | `/shell` | — | 远程 shell（手机端自动化 + adb 逃生舱） · GET /shell -> 开关状态 · POST /shell {"cmd":"..."} -> 以 uid=1000(system) 跑一条命令 · POST /shell {"cmd":"...","root":1} -> 走 su -c 以 root 跑 · POST /shell {"on… |
| `POST` | `/shell` | `cmd`, `on` | `return "{\"enabled\":" + Shell.enabled(ctx) + "}"` |
| `GET` | `/status` | — | `BluetoothAdapter.getDefaultAdapter()` |
| `GET` | `/switch` | — | `return reaction == null ? "{\"error\":\"reaction not ready\"}" : reaction.json()` |
| `POST` | `/switch` | `ai`, `native` | `return "{\"error\":\"reaction not ready\"}"` |
| `POST` | `/thaw` | — | `return "{\"ok\":" + ok + ",\"freezer\":" + ProcessFreezer.statusJson() + "}"` |
| `POST` | `/wifi` | `pass`, `ssid` | `return "{\"error\":\"ssid required\"}"` |

## 运动 / 姿态（`motion`，21）

> 动作播放、舵机直控、姿态文件、姿态数学

| 方法 | 路径 | 参数 | 说明 |
|---|---|---|---|
| `POST` | `/base` | `name` | `return "{\"result\":\"" + J.esc(motion.baseAction(field(body, query, "name"))) + "\"}"` |
| `POST` | `/dance` | `name` | `return "{\"result\":\"" + J.esc(motion.dance(field(body, query, "name"))) + "\"}"` |
| `POST` | `/gsensorcali` | — | `return "{\"result\":\"calibrating\"}"` |
| `POST` | `/light` | `action`, `color` | 灯光（原厂系统广播） |
| `POST` | `/motion` | `name` | `MotionController.isValidMotion()` |
| `GET` | `/motionconfig` | — | `return "{\"enabled\":" + jsonList(enabledMotions()) + "}"` |
| `POST` | `/motionconfig` | `enabled`, `reset` | `return "{\"result\":\"reset all\"}"` |
| `GET` | `/motions` | — | 动作 |
| `GET` | `/motions_all` | — | — |
| `GET` | `/motions_dance` | — | — |
| `POST` | `/motor` | `j1`, `j2`, `j3`, `ms` | `return "{\"error\":\"motorpose not init\"}"` |
| `GET` | `/motorpos` | — | 电机姿态 + 调试面板（v147） · GET /motorpos → 三个电机的【读回】位置（原厂状态广播）+ **换算角度** + 最近一次下发 · raw[3]=12 位编码器计数；ang[3]=按实测拟合换算出的角度（不可信 ⇒ null） · POST /motor {"j1":..,"j2":..,"j3":..,"ms":..} → 直接下发… |
| `GET` | `/motorrelease` | — | 解除锁定 / 释放舵机力矩（v153） · GET /motorrelease → 上一次释放的结果（含 1.5s 后补采的 after / delta_max） · POST /motorrelease → 立刻释放三个舵机的力矩（手掰得动） · 原厂通路（不是猜的）：EngineService.realeaseControl_All()（原厂就拼成 r… |
| `POST` | `/motorrelease` | — | `return "{\"error\":\"motorpose not init\"}"` |
| `GET` | `/pan` | — | `return "{\"status\":" + BtPan.statusJson(ctx) + "}"` |
| `POST` | `/pan` | `on` | 蓝牙网络共享（没有 WiFi 时的兜底链路）：机器人开这个才变成 192.168.44.1， · 电脑端才加得进它的个人区域网。不带参数 = 开；{"on":false} = 关。 |
| `POST` | `/pose` | `name`, `run`, `spec` | `return "{\"result\":\"" + J.esc(r) + "\"}"` |
| `POST` | `/posedel` | `name` | `return "{\"result\":\"" + J.esc(pose.remove(field(body, query, "name"))) + "\"}"` |
| `POST` | `/poserun` | `name` | `return "{\"result\":\"" + J.esc(pose.run(field(body, query, "name"))) + "\"}"` |
| `GET` | `/poses` | — | 姿态 |
| `POST` | `/stop` | — | `return "{\"result\":\"" + J.esc(motion.stop()) + "\"}"` |

## 语音 / 对话（`voice`，38）

> 云 ASR、唤醒、对话、TTS、意图、AI 大脑

| 方法 | 路径 | 参数 | 说明 |
|---|---|---|---|
| `GET` | `/ai` | — | newcore：AI 大脑（直连/回落，可切换） |
| `POST` | `/ai/ask` | `context`, `text` | `return "{\"error\":\"ai not ready\"}"` |
| `POST` | `/ai/config` | `base`, `engine`, `key`, `model` | `return "{\"error\":\"ai not ready\"}"` |
| `GET` | `/ai/status` | — | newcore：AI 大脑（直连/回落，可切换） |
| `GET` | `/asrdialog` | `on`, `sec` | v53：唤醒后是否「先听再说」 · 开：POST /asrdialog {"on":true,"sec":5} 关：POST /asrdialog {"on":false} · 默认关。等 /asrtest 确认能拿到识别文本后再打开。 |
| `POST` | `/asrdialog` | `on`, `sec` | v53：唤醒后是否「先听再说」 · 开：POST /asrdialog {"on":true,"sec":5} 关：POST /asrdialog {"on":false} · 默认关。等 /asrtest 确认能拿到识别文本后再打开。 |
| `GET` | `/dialog` | `followup`, `max_ms`, `on`, `silence_ms`, `turns` | v58：对话通路（唤醒后听一整句，说完静音 1 秒交给 AI；播报期间可被语音打断） · 查：GET /dialog · 配：POST /dialog {"on":true,"max_ms":10000,"silence_ms":1000,"turns":3} · 试：POST /dialog {"listen":true} —— 阻塞听一轮（最多 max… |
| `POST` | `/dialog` | `followup`, `max_ms`, `on`, `silence_ms`, `turns` | v58：对话通路（唤醒后听一整句，说完静音 1 秒交给 AI；播报期间可被语音打断） · 查：GET /dialog · 配：POST /dialog {"on":true,"max_ms":10000,"silence_ms":1000,"turns":3} · 试：POST /dialog {"listen":true} —— 阻塞听一轮（最多 max… |
| `POST` | `/dialogturn` | — | v178：远程验收整轮应答（连"听"也跳过） · 用法：POST /dialogturn {"text":"你好呀","why":"http"} |
| `POST` | `/forcewake` | — | v102：远程强制唤醒（远程验收专用） · 用法：POST /forcewake {"why":"http"} |
| `GET` | `/ia` | — | 在线听写（v97，讯飞 MSC） · 离线那套是"语法受限解码器 + 词典固化在声学模型里"，只能听懂词表内的整句 · 「听懂任意一句话」只剩云端。云端凭据沿用设备原有配置，本仓库不含任何凭据。 · 用法： · GET /ia 只建初始化，验授权（不消耗识别额度） · POST /iatest {"infile":"/sdcard/x.wav"} · 可选… |
| `POST` | `/ia` | — | 在线听写（v97，讯飞 MSC） · 离线那套是"语法受限解码器 + 词典固化在声学模型里"，只能听懂词表内的整句 · 「听懂任意一句话」只剩云端。云端凭据沿用设备原有配置，本仓库不含任何凭据。 · 用法： · GET /ia 只建初始化，验授权（不消耗识别额度） · POST /iatest {"infile":"/sdcard/x.wav"} · 可选… |
| `POST` | `/ilisten` | `dump`, `loud`, `sec`, `silence`, `src` | `return "{\"error\":\"wake not ready\"}"` |
| `POST` | `/intentrun` | `text` | POST /intentrun {"text":"跳个舞"} · 与 /intent 的区别：/intent **只判定**，这个**真执行**（和真人说这句话时 · 走的是同一条路：本地直通 → applyAiTags 执行标签 → TTS 播报 → 落对话日志）。 |
| `GET` | `/listen` | `autostart`, `grammar`, `on`, `think`, `workflow` | 长期主动听（v202） · 这是 /dialog {"continuous":true} 的"可持久化"版本：v75 起常识别本来就有， · 但开关只活在内存里（contRunning 是进程内 static），**重启就丢**。 · 这里把"要不要开机自动常听"落进 prefs，onCreate 里读它决定要不要拉起。 · GET /listen → {… |
| `POST` | `/listen` | `autostart`, `grammar`, `on`, `think`, `workflow` | 长期主动听（v202） · 这是 /dialog {"continuous":true} 的"可持久化"版本：v75 起常识别本来就有， · 但开关只活在内存里（contRunning 是进程内 static），**重启就丢**。 · 这里把"要不要开机自动常听"落进 prefs，onCreate 里读它决定要不要拉起。 · GET /listen → {… |
| `GET` | `/listenface` | — | v182：听语表情（"两个麦克风"） · GET /listenface → {"on":true,"face":"recording","active":false,"shown_now":"","n":N,"last":"..."} · POST /listenface {"name":"recording"} → 换表情（"off" = 不播脸） ·… |
| `POST` | `/listenface` | `name` | `return "{\"error\":\"need name=表情名（off = 不播脸；GET /faces 取清单）\"}"` |
| `GET` | `/listenled` | — | 听语灯（v144 起； v167 改蓝灯） · GET /listenled → {"on":true,"active":false,"mode":"blue_on","led":"b.on","last":"..."} · POST /listenled {"mode":"blue_on\|blue_blink\|all_blink\|off"} · POST… |
| `POST` | `/listenled` | `mode`, `on`, `speak_off` | `AgentService.noteTtsSpeaking()` |
| `POST` | `/media` | `data`, `kind`, `name`, `path` | newcore：媒体播放（音频/视频/图片/文字） |
| `POST` | `/media/stop` | — | `return "{\"error\":\"media not ready\"}"` |
| `POST` | `/mic` | `on`, `seconds` | 麦克风 |
| `GET` | `/ptalk` | — | newcore：主动说话（事件+定时） |
| `POST` | `/ptalk` | `event`, `idle`, `on`, `quiet_sec` | `return "{\"error\":\"ptalk not ready\"}"` |
| `POST` | `/say` | `engine`, `text`, `url` | `return say(text, url, eng)` |
| `GET` | `/talk` | `n` | `return TalkLog.tailJson(n)` |
| `POST` | `/talk` | `action` | `return TalkLog.clear()` |
| `GET` | `/talk/status` | — | 长期对话日志 / 多轮记忆（v93） · GET /talk?n=50 机器人本地那份对话流水（每条带 tid，可与电脑端对照） · GET /talk/status 记忆有多长、落盘在哪、上一次记了什么 · POST /talk {"action":"clear"} 只清内存环，磁盘 jsonl 留着翻账 |
| `POST` | `/tts` | `engine`, `text`, `url` | `return say(text, url, eng)` |
| `GET` | `/ttsstatus` | — | 说话 / 机器人自带语音合成 |
| `POST` | `/ttsstop` | — | `return "{\"result\":\"stopped\"}"` |
| `GET` | `/vocab` | — | v53：唤醒词表热替换（换词不必重装 APK） · 查：GET /vocab · 换：POST /vocab {"preset":"full"} 三档：base / safe / full · POST /vocab {"words":"你好,乐迪,超级飞侠"} 自定义 · 复位：POST /vocab {"reset":true} 丢掉外部词表，回落到 … |
| `POST` | `/vocab` | `preset`, `reset`, `words` | `return "{\"error\":\"wake not ready\"}"` |
| `GET` | `/volume` | — | 音量（v198；屏幕亮度已于 v201 移除） · GET /volume 读当前音量与上限 · POST /volume {"v":8} 设绝对值（也认老名字 {"n":8}） · POST /volume {"delta":2} 相对调整（语音"声音大一点"就是这条） · 语音本地直通（IntentResolver）走的是**同一个** Av 出口， … |
| `POST` | `/volume` | `delta`, `n`, `v` | `return Av.adjust(intOr(d, 0))` |
| `POST` | `/wake` | `on` | `return "{\"error\":\"wake not ready\"}"` |
| `GET` | `/wakestatus` | — | 语音唤醒（百度离线唤醒词） |

## 传感 / 交互（`sense`，22）

> 电量、头顶键、触摸注入、传感器、声源定位

| 方法 | 路径 | 参数 | 说明 |
|---|---|---|---|
| `GET` | `/battery` | — | 电量（v202） · GET /battery → 完整电量对象（格数/电压/温度/充电状态/来源/新鲜度） · 与 GET /status 里的 battery_detail 是**同一个** Battery.info(ctx)， · 不另写一份解析 —— 这台机器上"两份读数各说各话"已经吃过亏。 · 本机只有"格数"这一路是**原厂在推**（ACTI… |
| `GET` | `/doa` | `ms`, `probe` | v60：双麦克风声源定位 · 查：GET /doa · 探：POST /doa {"probe":true,"ms":1200} —— 一次性可行性探针（约 6 秒）， · 直接回答"这机器打开 STEREO 拿到的是真双路还是复制两份" · 开：POST /doa {"on":true} —— 背一个常驻线程持续估计方位 · 标：POST /doa {"… |
| `POST` | `/doa` | `ms`, `probe` | v60：双麦克风声源定位 · 查：GET /doa · 探：POST /doa {"probe":true,"ms":1200} —— 一次性可行性探针（约 6 秒）， · 直接回答"这机器打开 STEREO 拿到的是真双路还是复制两份" · 开：POST /doa {"on":true} —— 背一个常驻线程持续估计方位 · 标：POST /doa {"… |
| `POST` | `/face` | `name`, `sound` | `FaceController.normalize()` |
| `POST` | `/faceoff` | — | `return "{\"result\":\"" + J.esc(face.hide()) + "\"}"` |
| `GET` | `/faces` | — | 表情 / 显示 |
| `GET` | `/faces_detail` | — | `FaceController.listFaces()` |
| `POST` | `/facesound` | `name` | `J.esc()` |
| `GET` | `/headkey` | — | 头顶按键（v116） · GET /headkey -> 状态（含按下次数，用来证明按键真的到了） · POST /headkey {"on":false} -> 关掉这个功能 · POST /headkey {"main":"brain"} -> 换"主程序"目标 factory\|brain\|off · POST /headkey {"wake":fal… |
| `POST` | `/headkey` | `main`, `on`, `sim`, `wake` | `return "{\"error\":\"headkey not ready\"}"` |
| `POST` | `/img` | `b64`, `data`, `max` | POST /img {data:"base64(PNG/JPG)", max:800} 在机器人屏幕上显示一张图片 · （图片由电脑端读文件、纠方向、缩放后编码送来；机器人只解码显示） |
| `GET` | `/live` | — | v180/v186：活泼化（表情 / 动作） · 查：GET /live · 配：POST /live {"on":true\|false,"idle_every":3,"idle_motion_every":6} · on —— 活泼总开关（唤醒呼应 / 应答兜底表情 / 待机微动 / 待机动作） · idle_every —— 待机换表情每 N 拍（一拍… |
| `POST` | `/live` | — | v180/v186：活泼化（表情 / 动作） · 查：GET /live · 配：POST /live {"on":true\|false,"idle_every":3,"idle_motion_every":6} · on —— 活泼总开关（唤醒呼应 / 应答兜底表情 / 待机微动 / 待机动作） · idle_every —— 待机换表情每 N 拍（一拍… |
| `GET` | `/online` | `on` | v99：麦克风实时听写（把"任意话"接到真麦克风上） · 用法： · GET /online 查"在线优先"开关 · POST /online {"on":true} 开/关（默认开；关掉就只用离线 45 条命令词） · POST /ilisten {"sec":6} 开麦听一轮（阻塞），返回完整仪表 + text · 可选 silence=900（开口后… |
| `POST` | `/online` | `on` | v99：麦克风实时听写（把"任意话"接到真麦克风上） · 用法： · GET /online 查"在线优先"开关 · POST /online {"on":true} 开/关（默认开；关掉就只用离线 45 条命令词） · POST /ilisten {"sec":6} 开麦听一轮（阻塞），返回完整仪表 + text · 可选 silence=900（开口后… |
| `GET` | `/pad` | — | v148：调试控件（触摸 / 陀螺仪 / 电机滑条）已并入 log 面板， · /pad 保留为"打开带控件的 log 面板"的入口，与 /logwin 同义 —— · 旧脚本还在用它，所以不能删；语义统一到 LogOverlay 这一个事实源。 |
| `POST` | `/pad` | `lines`, `on`, `size`, `x`, `y` | `return LogOverlay.statusJson()` |
| `GET` | `/presence` | — | 在线状态 / 名字（v142） · GET /presence → 在线状态 + 连续听不懂几次 + 名字 · POST /presence {"offline":true\|false} → 手动进/出离线模式（验收用） · POST /presence {"miss_streak":0} → 只把计数归零，不改离线状态 · POST /name {"na… |
| `POST` | `/presence` | `offline` | `Presence.json()` |
| `POST` | `/sensorquery` | — | `return "{\"result\":\"queried\"}"` |
| `GET` | `/sensors` | — | 传感器 / 开关（新增） |
| `GET` | `/sensorsummary` | — | `return "{\"summary\":\"" + J.esc(sensor == null ? "" : sensor.summary()) + "\"}"` |

## 媒体 / 显示（`media`，9）

> 音量、灯光、屏幕图片与旋转

| 方法 | 路径 | 参数 | 说明 |
|---|---|---|---|
| `POST` | `/config` | `gateway` | 网关 / 网络 |
| `POST` | `/display` | `text` | `return "{\"result\":\"" + J.esc(display.show(field(body, query, "text"))) + "\"}"` |
| `POST` | `/displayoff` | — | `return "{\"result\":\"" + J.esc(display.hide()) + "\"}"` |
| `POST` | `/exclusive` | `on` | v198：原来这里还有一个 `POST /volume`（只认 {"n":…}，解析失败就悄悄按 8 处理）。 · 已经**合并**到上面那处，走 Av 单一出口 —— · 留着两份必然分叉（一份夹上限、一份不夹；一份回读、一份不回读）。 · 参数名 n 仍然认，所以网页 ⑨ 的滑条不受影响。 |
| `POST` | `/gear` | `ip`, `on` | 设置齿轮 |
| `GET` | `/home` | — | 桌面（HOME） · 需求：把桌面**锁定在原厂机器人 App 上**（不是 agent）。三级兜底见 HomeSetter。 · GET /home -> 默认桌面 / 目标 / recorded / candidates_n / unique / settled · POST /sethome -> 把偏好写到目标桌面（幂等） · v133 起还会**… |
| `GET` | `/rotation` | `on`, `value` | v63：屏幕朝向锁 · 查：GET /rotation · 配：POST /rotation {"on":false} —— 放开自动旋转（默认锁横向） · POST /rotation {"value":0} —— 0/1/2/3 = 0°/90°/180°/270° · POST /rotation {"apply":true} —— 立刻纠一次 |
| `POST` | `/rotation` | `on`, `value` | v63：屏幕朝向锁 · 查：GET /rotation · 配：POST /rotation {"on":false} —— 放开自动旋转（默认锁横向） · POST /rotation {"value":0} —— 0/1/2/3 = 0°/90°/180°/270° · POST /rotation {"apply":true} —— 立刻纠一次 |
| `POST` | `/sethome` | `restore` | `return HomeSetter.restore(ctx)` |

## AI 能力层（`cap`，43）

> 能力索引、权限矩阵、情绪、记忆、性格、工作流、思考

| 方法 | 路径 | 参数 | 说明 |
|---|---|---|---|
| `GET` | `/abilities` | — | 能力真源 / 权限矩阵（v207） · GET /abilities → 三态矩阵：read（只读查询）/ write（有限更改）/ · plan（编排间隔）/ forbid（安全红线：只读不可改） · GET /abilitiesselftest → **纯静态**自检（红线拒绝、清单自洽、实现齐备） |
| `GET` | `/caps` | `full`, `k`, `q` | 本机能力索引（v203） · GET /caps → 索引摘要（各类计数 + 说明书是否加载 + 是否 busy） · GET /caps?full=1 → 全量清单（网页面板用） · GET /caps?q=握手 → 检索（可解释：每条带命中关键词） · 这是"机器人自己找得到自己会什么"的那张表 —— 能力自省出来（动作表/表情表）， · 说明书打包在… |
| `GET` | `/console/agent` | `b`, `body`, `path` | 万能转发：页面里绝大多数按钮都走这条 · 页面发 {path:"/motion", body:{...}}；老协议也可能发 "b":"{...}" 字符串形式。 |
| `POST` | `/console/agent` | `b`, `body`, `path` | 万能转发：页面里绝大多数按钮都走这条 · 页面发 {path:"/motion", body:{...}}；老协议也可能发 "b":"{...}" 字符串形式。 |
| `GET` | `/console/appinfo` | — | 版本 / 监听信息：页面顶栏那一行 |
| `GET` | `/console/config` | — | 配置：页面的启动依赖，缺了整页只显示一行错误 · 页面从 CFG 里取"动作/舞蹈/表情/预设姿态"的全部清单，所以这几个字段必须真。 |
| `POST` | `/console/config` | — | 配置：页面的启动依赖，缺了整页只显示一行错误 · 页面从 CFG 里取"动作/舞蹈/表情/预设姿态"的全部清单，所以这几个字段必须真。 |
| `GET` | `/console/dialog` | — | 对话日志：那是**网关**把三条通路汇总出来的，机器人端没有这份账 |
| `POST` | `/console/dialog` | — | `return "{\"ok\":false,\"error\":\"对话日志属于电脑端网关，机器人端没有可清的账\"}"` |
| `GET` | `/console/doa` | — | — |
| `POST` | `/console/doa` | — | — |
| `POST` | `/console/emergency` | `text` | 应急发送：把一段文字交给机器人"念出来 + 配一个表情" · 电脑端那份还会本地挑表情与动作；这里先只保证**说得出**，不伪造没做的事。 |
| `POST` | `/console/hold` | — | — |
| `POST` | `/console/key` | — | `InputInject.available()` |
| `GET` | `/console/lastuser` | — | 机器人端不认识的 input |
| `GET` | `/console/mirror` | `inject` | 屏幕投屏（v195）：控 + 配置 · 「看」那三条（GET /mirror.h264 流、/mirror.jpg 单张、/mirror/stat 状态） · 在 HttpServer 里、router 之前就接住了 —— 一是它们吐的是**二进制字节流**， · 不是 JSON，走不进这个字符串路由；二是它们要在屏保中也能用。 · 这里只补「控」（把网页… |
| `POST` | `/console/mirror` | `inject` | 屏幕投屏（v195）：控 + 配置 · 「看」那三条（GET /mirror.h264 流、/mirror.jpg 单张、/mirror/stat 状态） · 在 HttpServer 里、router 之前就接住了 —— 一是它们吐的是**二进制字节流**， · 不是 JSON，走不进这个字符串路由；二是它们要在屏保中也能用。 · 这里只补「控」（把网页… |
| `GET` | `/console/rotation` | — | — |
| `POST` | `/console/rotation` | — | — |
| `GET` | `/console/screenimg` | — | 屏幕显示图片：页面上填的是**电脑上的路径**，机器人读不到 · 明确回绝并给出去路，别让它看起来"点了没反应"。图片走 POST /img 的 base64。 |
| `POST` | `/console/screenimg` | — | 屏幕显示图片：页面上填的是**电脑上的路径**，机器人读不到 · 明确回绝并给出去路，别让它看起来"点了没反应"。图片走 POST /img 的 base64。 |
| `GET` | `/console/sdk` | — | 原厂 SDK：页面⑬区把三个接口一次要齐 |
| `POST` | `/console/sdk` | — | 原厂 SDK：页面⑬区把三个接口一次要齐 |
| `GET` | `/console/state` | — | 传感器 / 状态：页面⑩区 · sensors 直接内联 /sensors 的原样 JSON（它就是页面 fmtState 要的那个结构）， · summary 取 SensorHub 自己的那句总结。 |
| `POST` | `/console/state` | — | 传感器 / 状态：页面⑩区 · sensors 直接内联 /sensors 的原样 JSON（它就是页面 fmtState 要的那个结构）， · summary 取 SensorHub 自己的那句总结。 |
| `POST` | `/console/swipe` | — | 投屏的「控」：把网页上的一次点击 / 拖拽 / 按键 / 打字送回机器人屏幕 · 坐标一律用**机器人屏幕的像素**（和 /mirror.h264 的头 X-Ledi-Width/Height 同一域）； · 页面把显示尺寸按比例换算过来，所以浏览器窗口多大都不影响落点。 |
| `POST` | `/console/tap` | — | 投屏的「控」：把网页上的一次点击 / 拖拽 / 按键 / 打字送回机器人屏幕 · 坐标一律用**机器人屏幕的像素**（和 /mirror.h264 的头 X-Ledi-Width/Height 同一域）； · 页面把显示尺寸按比例换算过来，所以浏览器窗口多大都不影响落点。 |
| `POST` | `/console/text` | — | `InputInject.available()` |
| `POST` | `/console/tts` | — | `return handle("POST", "/tts", "", body)` |
| `GET` | `/console/ttsstatus` | — | `return "{\"ok\":true,\"robot\":" + handle("GET", "/ttsstatus", "", "") + "}"` |
| `POST` | `/console/ttsstatus` | — | `return "{\"ok\":true,\"robot\":" + handle("GET", "/ttsstatus", "", "") + "}"` |
| `GET` | `/emotion` | — | v189：情绪系统 · 查：GET /emotion → 当前情绪 + 两个维度 + 它现在会让待机摆哪张脸 · 设：POST /emotion {"name":"happy"} 按**情绪名**（原厂词汇表内的才认） · POST /emotion {"code":10300} 按**原厂数字码** · POST /emotion {"valence":… |
| `POST` | `/emotion` | — | v189：情绪系统 · 查：GET /emotion → 当前情绪 + 两个维度 + 它现在会让待机摆哪张脸 · 设：POST /emotion {"name":"happy"} 按**情绪名**（原厂词汇表内的才认） · POST /emotion {"code":10300} 按**原厂数字码** · POST /emotion {"valence":… |
| `GET` | `/intent` | — | 离线意图解析（v94） · GET /intent 词表镜像 / 开关 / 上一句走了哪条路 · POST /intent {"test":"讲个故事"} 只解析**不执行**：命中本地直通会回 local_reply， · 否则回 near（易混候选）与给模型的 hint。 · 这是远程验证纠错效果的主力 —— 不用在机器人旁边喊话 · 就能看到"这句话… |
| `POST` | `/intent` | `on` | `IntentResolver.setLocalEnabled()` |
| `GET` | `/memory` | — | v214：长期记忆 + 忘记 · 查：GET /memory → 认识第几天、聊过几次、记着哪些事（含 brief 原文，便于对差） · 记：POST /memory {"remember":"喜欢猫"} → 记一条事实（存机器人本地） · 忘：POST /memory {"forget":"all\|facts\|talk"} → 真删，返回里带回读字节证据… |
| `POST` | `/memory` | `action`, `forget`, `remember`, `scope` | `Memory.remember()` |
| `GET` | `/think` | `goal`, `rounds`, `think_on` | 多轮思考（v202） · GET /think?goal=...&rounds=3 跑一次多轮思考（模型可先要状态/让机器人做动作，再回答） · POST /think {"goal":"...","rounds":3} · POST /think {"think_on":true\|false} 只切"语音回复是否走多轮思考"（不跑） · 这是**独立于语… |
| `POST` | `/think` | `goal`, `rounds`, `think_on` | 多轮思考（v202） · GET /think?goal=...&rounds=3 跑一次多轮思考（模型可先要状态/让机器人做动作，再回答） · POST /think {"goal":"...","rounds":3} · POST /think {"think_on":true\|false} 只切"语音回复是否走多轮思考"（不跑） · 这是**独立于语… |
| `GET` | `/tone` | — | 调性（v143 成人化） · GET /tone → 当前档位 + **实际生效的每一句原文** · POST /tone {"level":"adult\|neutral\|child"} → 换档 · POST /tone {"addr":"你"} → 它怎么称呼用户 · POST /tone {"pat_line":true\|false} → 摸头顶要不… |
| `POST` | `/tone` | `addr`, `custom_clause`, `custom_name`, `delcustom`, `level`, `pat_line`, `pat_speak`, `style` | `return "{\"error\":\"自定义性格需要 custom_name 与 custom_clause 都给\"}"` |
| `GET` | `/workflow` | `async`, `del`, `list`, `read`, `rollback`, `save`, `text` | 简易 workflow（v203） · GET /workflow → 最近一次执行的流水 + 是否在跑 · GET /workflow?list=1 → 设备上已保存的流程列表 · GET /workflow?read=名字 → 读一份流程原文 · POST /workflow {"text":"…"} → 预检并**同步**执行（网页/脚本用；会等它跑… |
| `POST` | `/workflow` | `async`, `del`, `list`, `read`, `rollback`, `save`, `text` | 简易 workflow（v203） · GET /workflow → 最近一次执行的流水 + 是否在跑 · GET /workflow?list=1 → 设备上已保存的流程列表 · GET /workflow?read=名字 → 读一份流程原文 · POST /workflow {"text":"…"} → 预检并**同步**执行（网页/脚本用；会等它跑… |

## 原厂 SDK 直调（`sdk`，14）

> EngineService 动作 / SystemMethod 灯光电源 / 事件流

| 方法 | 路径 | 参数 | 说明 |
|---|---|---|---|
| `POST` | `/sdk/bpm` | `bpm` | `return "{\"error\":\"sdk not ready\"}"` |
| `GET` | `/sdk/caps` | — | 原厂 SDK 直调（xldbasesdk） · 与 /motion、/light 的区别：这里走原厂自己的 API（含序列、节奏、电源、ADB）， · 不是我们重写的通道。见 SdkController 的类注释。 |
| `POST` | `/sdk/dance` | `name` | `return "{\"error\":\"sdk not ready\"}"` |
| `GET` | `/sdk/events` | — | `J.esc()` |
| `POST` | `/sdk/flag` | `battery_low` | `return "{\"error\":\"sdk not ready\"}"` |
| `POST` | `/sdk/forceready` | — | `J.esc()` |
| `POST` | `/sdk/led` | `action`, `color` | `return "{\"error\":\"sdk not ready\"}"` |
| `POST` | `/sdk/listen` | — | `J.esc()` |
| `POST` | `/sdk/release` | — | `J.esc()` |
| `POST` | `/sdk/run` | `name`, `speed` | `return "{\"error\":\"sdk not ready\"}"` |
| `POST` | `/sdk/series` | `delay` | `return "{\"error\":\"sdk not ready\"}"` |
| `GET` | `/sdk/stat` | — | `return sdk == null ? "{\"error\":\"sdk not ready\"}" : sdk.stat()` |
| `POST` | `/sdk/stop` | — | `J.esc()` |
| `POST` | `/sdk/sys` | `what` | `return "{\"error\":\"sdk not ready\"}"` |

## 自检 / 探针（`probe`，29）

> 各引擎纯 JVM 自检、崩溃盾验收、启动日志

| 方法 | 路径 | 参数 | 说明 |
|---|---|---|---|
| `GET` | `/abilitiesselftest` | — | `return "{\"ok\":true,\"report\":\"" + J.esc(Abilities.selftest()) + "\"}"` |
| `POST` | `/asrtest` | `extra`, `grammar`, `infile`, `sec` | v53：离线 ASR 探测（把"用户说了什么"变成文本，端到端通路缺失的那一环） · 用法：POST /asrtest {"sec":8} —— 阻塞 sec 秒，期间对着机器人说话 · POST /asrtest {"sec":8,"grammar":"assets:///baidu_speech_grammar.bsg"} · POST /asrtes… |
| `GET` | `/attitudetest` | — | 姿态数学自检（v153） · GET /attitudetest → 把"能静态判死"的三项断言摊开：重力不变式、roll 绕 Y、 · pitch 绕 X。不依赖真机、不依赖人手倾斜。详见 Attitude.selfTest()。 |
| `GET` | `/bootlog` | — | 启动溯源日志（这台 ROM 屏蔽 App 的 logcat，排障只能看这个文件） |
| `GET` | `/capsselftest` | — | `return "{\"ok\":true,\"report\":\"" + J.esc(CapIndex.selftest()) + "\"}"` |
| `POST` | `/crashprobe` | `main` | v159：主线程崩溃盾的**验收探针**（只判定，不影响任何正常链路） · 开：POST /crashprobe {"main":true} 往主线程投一个**必抛**的 Runnable， · 立即回执（不等结果 —— 等的话请求会跟着主线程一起废掉）。 · 看：GET /status · · 盾生效 → crashguard.mt_shield +1、… |
| `GET` | `/emotionselftest` | — | `return "{\"ok\":true,\"report\":\"" + J.esc(Emotion.selftestOnDevice(ctx)) + "\"}"` |
| `POST` | `/iatest` | `infile` | `return "{\"ok\":false,\"error\":\"缺少 infile（本轮只支持文件输入，避免麦克风变量混进来）\"}"` |
| `GET` | `/kws` | — | v183：自建本地唤醒内核（MFCC + VAD + DTW） |
| `POST` | `/kws` | — | 仅在屏保白名单，无独立派发分支 |
| `POST` | `/kws/clear` | — | `return "{\"ok\":true,\"templates\":0}"` |
| `POST` | `/kws/probe` | `pcm` | `return "{\"ok\":false,\"error\":\"pcm= 必填\"}"` |
| `POST` | `/kws/test` | `active`, `band`, `delta`, `pcm`, `stride` | `return "{\"ok\":false,\"error\":\"pcm= 必填\"}"` |
| `POST` | `/kws/train` | `delta`, `files` | `Arrays.sort()` |
| `GET` | `/ledselftest` | — | v206：灯语自检（纯静态，8 种布尔组合穷举 + 收场口径）。 |
| `GET` | `/logwin` | — | — |
| `POST` | `/logwin` | `follow`, `layer`, `lines`, `on`, `panel_layer`, `size`, `x`, `y` | 屏幕日志浮层 · POST /logwin {"on":true,"lines":12,"size":10,"x":0,"y":0} · 把实时日志铺在机器人屏幕左下角，站着说话就能看见，不用再去拉 adb/HTTP。 · 字段都可省；on 只有传 "true"/"false" 才改开关。状态见 GET /logwin。 · layer / panel_l… |
| `GET` | `/memoryselftest` | — | `return "{\"ok\":true,\"report\":\"" + J.esc(Memory.selftest()) + "\"}"` |
| `GET` | `/miclist` | — | — |
| `GET` | `/motordiag` | — | `return "{\"error\":\"motorpose not init\"}"` |
| `GET` | `/poseread` | `name` | `return "{\"content\":\"" + J.esc(pose.read(field(body, query, "name"))) + "\"}"` |
| `GET` | `/programmer` | — | 仅在屏保白名单，无独立派发分支 |
| `GET` | `/recadir` | — | v183：自建唤醒的数据采集器 · 查：GET /recadir → 目录清单（名+字节），核对传输完整性 · 采：POST /recaudio {"sec":6,"name":"pos1"} → 录一段 16k/单声道/16bit 原始 PCM · 落 /sdcard/ledi_kws/<name>.pcm + 同名 .json（峰值/RMS/削顶/静音… |
| `POST` | `/recaudio` | `name`, `sec`, `src` | `return "{\"error\":\"wake not ready\"}"` |
| `GET` | `/thinkselftest` | — | 纯静态自检：不吃 Context、不联网、不碰 org.json —— 只算数字，随时可跑。 |
| `GET` | `/toneselftest` | — | `return "{\"ok\":true,\"report\":\"" + J.esc(Tone.selftest()) + "\"}"` |
| `POST` | `/touchtest` | `action`, `gap_ms`, `times` | v63：触摸自测（机器人给自己发原厂触摸广播） |
| `POST` | `/waketest` | `gain`, `pcm`, `sec` | 离线唤醒体检：自己录 PCM 直接喂唤醒解码器，绕开喇叭/AEC，用于定性"喊话到底能不能被识别" · 用法：POST /waketest {"sec":6} —— 请求会阻塞 sec 秒，期间对着机器人喊「哈喽乐迪」 · v181 起加 pcm= / gain= —— **回灌**设备上的裸 PCM（16k/16bit/mono）。 · 用途：把"距离 … |
| `GET` | `/workflowselftest` | — | 纯静态自检（不联网、不碰 org.json，只算数字）。 |

## 屏保 / 界面（`ui`，5）

> 屏保与深睡控制、控制台页面

| 方法 | 路径 | 参数 | 说明 |
|---|---|---|---|
| `GET` | `/console/` | — | 前缀路由：返回控制台页面 assets/console.html（不受屏保闸） |
| `GET` | `/deepsleep` | — | v190：深睡（长按头顶键进的那一档） · 查：GET /deepsleep → deep / sleeping / idle_stopped / freeze_mode · 设：POST /deepsleep {"on":true\|false} 只改"进屏保时冻谁"（不动睡眠状态） · POST /deepsleep {"now":true\|false}… |
| `POST` | `/deepsleep` | — | v190：深睡（长按头顶键进的那一档） · 查：GET /deepsleep → deep / sleeping / idle_stopped / freeze_mode · 设：POST /deepsleep {"on":true\|false} 只改"进屏保时冻谁"（不动睡眠状态） · POST /deepsleep {"now":true\|false}… |
| `GET` | `/sleep` | — | 屏保 / 休息 · GET /sleep → 状态 · POST /sleep {"on":true,"bright":1.0} → 铺满图 + 闸住功能（bright 缺省=1.0 不压暗） · POST /sleep {"on":false} → 叫醒 · POST /sleep {"img":"<base64>"} → 只上传/更换屏保图片 |
| `POST` | `/sleep` | `data`, `img` | 屏保 / 深睡控制（内联块，不经查表派发） |
