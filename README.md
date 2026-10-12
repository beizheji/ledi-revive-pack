# 乐迪智能机器人 · 复活开源包

让一台**已经报废的「乐迪智能机器人」**（MT6580 / `vinci_portal` / Android 5.1）重新活过来。

厂商大约在 2024 年前后停止运营，机器人原厂的 App 与云服务随之失效 —— 整机从此变成一块砖。
本仓把**复活它所需的全部东西**（可刷的 BOOT、可复刻的开机动画与壁纸、机器人 App 的**完整源码**、
接口文档、原厂安装包、以及一键落地脚本）全部开源出来，目标是**任何人拿到一台同型机器人都能自己把它救活**。

> 机器人被改造成**自带大脑 + 自带网页控制台**，不再依赖原厂服务器。
> **不填任何凭据**它就能动、能变表情、能用本地嗓音说话、能听懂词表内的语音指令；
> 想要"听懂任意话 + 自由对话"，再给它配一个**你自己的**大模型 API Key（可选，见复活指南第 5 步）。

---

## 五分钟看懂

| 你想做的事 | 去哪 |
|---|---|
| **先看一遍这个包长什么样** | 👉 [`预览.html`](预览.html)（两个真机 App 的说明与指纹） |
| **一步步把机器人救活** | 👉 [`复活指南.md`](复活指南.md) |
| 拿现成刷机包直接刷 | `01_刷机包/` —— **按 SoC 分栏**，本机型用 `MTK6580/`（`boot.img` + scatter + 刷入说明） |
| 装机器人 App（免编译） | 先到 [APK 存档仓的 Release](https://github.com/beizheji/ledi-apk-archive/releases/tag/apk-archive-2026-10-12) 下载，装法见 [`复活指南.md`](复活指南.md) 第 3 步 |
| **自己编译机器人 App** | `07_源码/` + [`07_源码/编译说明.md`](07_源码/编译说明.md) |
| 看机器人能干什么（接口总表） | `06_文档/` |
| 找原厂安装包 | `05_原厂APK/` |
| 看这包干不干净 | [`脱敏报告.md`](脱敏报告.md) |

---

## 目录结构

```
乐迪公开版刷机包/
├── 01_刷机包/                    ← 用 SP Flash Tool 刷进 BOOT 分区（复活的第一步）
│   ├── README.md                   分栏说明：刷机件按主控芯片分类
│   └── MTK6580/                    ← 本机型（vinci_portal / MT6580 / Android 5.1）
│       ├── boot.img                    6650138 B  md5 34c0a9a7…
│       ├── MT6580_Android_scatter.txt  10326 B    md5 c24bf96d…
│       └── 刷入说明.txt                 刷机步骤 + 逐条差异
├── 02_素材/                      ← 不刷机也能用，靠 adb 落地
│   ├── 开机动画_bootanimation.zip   5979550 B  原版（91 帧 800×480 红色乐迪飞机）
│   ├── 屏保_800x480.jpg             机器人屏直接用（按填满缩放后居中裁切）
│   └── 屏保_原图1024x1024.jpg       原始图
├── 03_App/
│   ├── LediAgent.public-v223.apk   ★ 本体不进 git —— 到 ledi-apk-archive 的 Release 下载（下完按文末校验值核对）
│   └── 脱敏说明.txt
├── 04_脚本/                      ← 一键把「素材 + App」推进机器人
│   ├── 一键落地.bat                 双击即用（默认演练，输入 Y 才真写）
│   └── _apply_public.py
├── 05_原厂APK/                   ← 原厂原样安装包（打补丁的干净基底 / 取证）
│   └── 溯源说明.txt                 ★ APK 本体在 ledi-apk-archive 的 Release；
│                                    编译 07_源码 需先下载 xldrbt_v1.5.10.apk（设 LEDI_OEM_APK 指向它）
├── 08_魔改版APK/                 ← ★ 机器人上真正在跑的「魔改过的原厂包」
│   └── 说明.txt                     改了什么 + 怎么装；APK 本体在 ledi-apk-archive 的 Release
├── 06_文档/                      ← 机器人接口总表与说明（并入 API 文档仓）
│   ├── api/endpoints.json            全部 HTTP 端点（机器可读）
│   ├── docs/                         分领域说明（motion / voice / system / display …）
│   └── README.md
├── 07_源码/                      ← 机器人 App 的完整源码，可自行编译
│   ├── agent/                        Java 源码 + assets + 厂商 .so
│   ├── ui_kit/appicon/               App 图标资源
│   ├── build_apk.py                  无 Gradle 的 APK 打包器（可移植版）
│   ├── _rebuild_agent.py             一条命令重打 APK
│   ├── tools/keys/                   （发布版为空 —— 签名密钥不随仓分发，见编译说明 §2.1）
│   └── 编译说明.md
├── tools/                        ← 本仓自身的构建/自检脚本
├── README.md
├── 预览.html                     ← 两个真机 App 的预览（自包含，浏览器直接看）
├── 复活指南.md
├── 脱敏报告.md
├── NOTICE.md                     第三方与遗弃软件声明
└── LICENSE                        MIT
```

---

## 校验值

| 文件 | 大小 | md5 |
|---|---|---|
| `01_刷机包/MTK6580/boot.img` | 6650138 | `34c0a9a7665895ec45d20aa91f0e5f07` |
| `01_刷机包/MTK6580/MT6580_Android_scatter.txt` | 10326 | `c24bf96d3a5b9c66170410f8c427b2b5` |
| `02_素材/开机动画_bootanimation.zip` | 5979550 | `4644859f911a6c351252a95f4a970103` |
| `02_素材/屏保_原图1024x1024.jpg` | 72889 | `9a70b7576fff284eba5cce6b03d357e4` |
| `03_App/LediAgent.public-v223.apk` | 17756662 | `a775f93ba4d5b99cf37721d91914699c` |
| `08_魔改版APK/xldrbt_v1.5.10-noguide.apk` | 95957826 | `3f83bc651f2e7ef255f25387961feb69` |

对不上就别刷 / 别装 —— 包被改过或没下全。

---

## 关于那颗 BOOT：root adb + FTP 是**故意留的**

刷完 `01_刷机包/MTK6580/boot.img`，机器人会得到：

- **免授权的 root adb**（USB 与 WiFi `5555` 双通道）—— 复活后连电脑、装 App、传文件全靠它；
- 开机自启的 **root 权限 FTP（`0.0.0.0:2121`）** —— 没有 adb 时的文件通道。

**这正是复活的入口，不是漏洞。** 原厂系统把这些门都焊死了，我们靠刷这颗 BOOT 才拿回控制权。
代价是它**只适合家庭内网**：FTP 是明文、adb 免授权，**绝不要把这台机器人暴露到公网**。

> 想收回这套能力：把原厂 `boot.img` 刷回去即可（本包没动 `bootloader` / `lk`）。

---

## 用到的外部工具（自己去 GitHub 拿最新版）

| 工具 | 用途 | 地址 |
|---|---|---|
| **scrcpy** | 电脑上投屏 + 键鼠遥控机器人（Android 5.0+ 均可，本项目 5.1 可用） | https://github.com/Genymobile/scrcpy |
| **SP Flash Tool** | 往 MTK 平台刷 `boot.img`（本机是 `.txt` scatter ⇒ 需 **v5** 系） | 见下方说明 |
| **platform-tools (adb)** | 连接与安装 APK | https://developer.android.com/studio/releases/platform-tools |

> SP Flash Tool 由联发科维护，**官方没有 GitHub 仓库**。可从社区镜像获取，例如
> `https://github.com/droidfirmwares/sptool/releases`（含 v5.2104-Win）或
> `https://github.com/SP-Flash-Tool-Portable/SP-Flash-Tool`。
> 只需它支持 `.txt` scatter 的 **v5 系**（v6 只吃 `.xml`），刷我们的 `boot.img` 足够。

---

## 许可

- 本仓**自研部分**（`07_源码/agent`、脚本、文档）以 **MIT** 发布，见 [`LICENSE`](LICENSE)。
- 仓内**第三方二进制**（厂商 `.so`、原厂 APK、原厂开机动画素材等）属原权利人，此处按
  「为互通与软件保全而保留」处理，详见 [`NOTICE.md`](NOTICE.md)。

## 这个包里没有什么

- 没有 API Key / token（全包逐字节扫过）；App 的大模型 Key 由使用者**运行时自己填**
- **没有签名私钥**（`07_源码/tools/keys/` 发布版为空——私钥是这台 ROM 的安装闸门，见 `NOTICE.md` §4）
- 没有原机主的录音、语音采样、TTS 缓存
- 没有原机主的局域网 IP（已从源码与 App 中清除）
- 没有 `system.img` / `userdata.img`（不碰数据与系统分区）
- 没有原厂内容过滤词表（被置空的补丁包里也只是空文件）

逐项证据见 [`脱敏报告.md`](脱敏报告.md)。
