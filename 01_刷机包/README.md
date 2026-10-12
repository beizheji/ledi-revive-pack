# 01_刷机包 · 按 SoC 分栏

刷机件（`boot.img` / scatter / 刷入说明）**按机器人的主控芯片分目录**。
不同 SoC 的 BOOT 与 scatter **绝对不能混用** —— 刷错分区表轻则不开机，重则变砖。

## 现有

| 目录 | 机型 | 说明 |
|---|---|---|
| [`MTK6580/`](MTK6580/) | 乐迪智能机器人（`vinci_portal`，Android 5.1） | 本仓当前唯一的刷机件 |

`MTK6580/` 内容：

| 文件 | 干什么 |
|---|---|
| `boot.img` | 用 **SP Flash Tool v5** 刷进 **BOOT 分区**（复活的第一步；自带 root adb + FTP） |
| `MT6580_Android_scatter.txt` | SP Flash Tool 的分区表（`.txt` ⇒ 只能用 **v5 系**，v6 只吃 `.xml`） |
| `刷入说明.txt` | 逐步刷机步骤 + 这颗 BOOT 相对原厂的逐条差异 |

刷机步骤与安全边界见仓根 [`复活指南.md`](../复活指南.md) 第 1 节。

## 以后要加别的芯片（例如 MTK6572）

1. 新建 `01_刷机包/MTK6572/`，放进该机的 `boot.img` + `*_Android_scatter.txt` + 自己的刷入说明；
2. 在**上面的表格**里加一行；
3. 同步仓根 `README.md` 的目录树与「校验值」表（把新芯片的 md5 一起登记）；
4. 若该芯片要复刻本仓这套改造，另开一栏（本目录只放刷机件，不放 App / 素材）。

> ⚠️ 每颗芯片的 BOOT **只对同芯片、同机型的机器有效**。刷前先确认主控型号
> （`adb shell getprop ro.board.platform`）。
