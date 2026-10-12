# 06 · 机器人接口文档（随包副本）

机器人刷好、装上 App 之后**能干什么、怎么指挥它**，全部在这里。随刷机包一起分发，方便离线查阅。

设备是一台 MTK 平台的定制 Android 机器人；改造 App `com.ledi.agent` 常驻设备，
对外提供一个 HTTP 服务（默认端口 8899），把三套控制面统一成一套接口。

- **真源（以此为准）**：[`ledi-smart-companion-robot-basic-features`](https://github.com/beizheji/ledi-smart-companion-robot-basic-features)
  —— 文档由 App 源码注释经生成链自动产出、带脱敏闸与 7 项自检；本目录与其同步于 **2026-10-12**，
  内容若与本仓有出入，以真源仓为准。
- `api/endpoints.json` — 全部 **199 个 HTTP 端点**（机器可读）
- `api/endpoints.csv` — 同一份清单的表格形态
- `docs/http-api.md` — 接口总表（生成物，**别手改**）
- `docs/*.md` — 分领域说明：`motion` 动作 / `voice` 语音 / `system` 系统 / `display` 显示·情绪·灯光
  / `security` 安全边界（屏保闸、权限红线）/ `factory-sdk` 原厂广播接口 / `python-client` Python 客户端

## 通用约定（读端点前先看这三条）

1. **HTTP 状态码不可用作判据**：本 ROM 的 HttpBridge 一律返回 200，判成功 / 失败只看响应 body 顶层
   （失败形如 `{"error":"..."}`）。
2. **屏保闸**：进入屏保或深睡后，白名单外的端点统一返回 `{"error":"sleeping"}`；先 `POST /sleep {"on":false}`。
3. **参数来源等价**：`GET /x?a=1` 与 `POST /x` + `{"a":1}` 等价。

## 复活流程里什么时候用哪份

| 阶段 | 用哪份 |
|---|---|
| 先不填任何凭据玩起来（动作 / 表情 / 本地嗓音 / 离线词表） | `docs/motion.md`、`docs/voice.md`、`docs/display.md` |
| 远程验收一条指令是否真执行 | `docs/voice.md` 的 `/intentrun`、`POST /dialogturn` |
| 能力边界与安全红线（哪些会被屏保闸挡住） | `docs/security.md` |
| 想做自己的控制端 / 脚本 | `docs/python-client.md` + `api/endpoints.json` |
