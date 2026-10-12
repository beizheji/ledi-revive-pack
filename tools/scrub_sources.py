# -*- coding: utf-8 -*-
"""刷机包源码/文档的「措辞收敛」：把指向仓库外工程过程的引用换成中性表述。

与 api文档仓 的 tools/sanitize.py 同一思路，但规则按本仓语境裁剪：
  - 本仓**公开源码**，`--merge-dex` / `libmsc.so` 是编译与组件清单的正当技术事实 ⇒ 不拦；
  - `appid` 的值按发布决策保留（原厂 dex 中本就存在，扣不干净），只收敛「怎么白用授权」的叙述；
  - 拦的是：本机盘符路径、工程内部专题文档/记忆/脚本引用。

每条规则 = (文件, 原文, 换成)。执行时逐条断言**恰好命中 1 次**：命中 0 说明产物已换过（幂等）或规则写偏；
命中 >1 说明原文不唯一——两种情况都停下来人工看，绝不静默多换。
--check 只报告不落盘。
"""
import os, sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

RULES = [
    # ---- LocalWake.java：工程记忆 / 内部脚本引用
    ("07_源码/agent/src/com/ledi/agent/voice/LocalWake.java",
     "`hits` 恒 0（记忆 §I3）；", "`hits` 恒 0；"),
    ("07_源码/agent/src/com/ledi/agent/voice/LocalWake.java",
     "（记忆 §I3：20~30cm 正常音量必削顶 32768，1m 外只剩 3255）",
     "（实测 20~30cm 正常音量必削顶 32768，1m 外只剩 3255）"),
    ("07_源码/agent/src/com/ledi/agent/voice/LocalWake.java",
     "同一套公式在 PC 上有一份 Python 版（{@code _kws/_kws_lib.py}），",
     "同一套公式在 PC 上有一份同公式的 Python 参考实现，"),
    # ---- VoiceWakeController.java
    ("07_源码/agent/src/com/ledi/agent/voice/VoiceWakeController.java",
     "（判决见 {@code 06_语音唤醒专题/离线ASR_任意语音可行性判决_v97.md}）。", ""),
    ("07_源码/agent/src/com/ledi/agent/voice/VoiceWakeController.java",
     "8 档增益回灌全 0 命中（记忆 §I3），", "8 档增益回灌全 0 命中，"),
    ("07_源码/agent/src/com/ledi/agent/voice/VoiceWakeController.java",
     "铁律「判据必须在进程之外」（记忆 §4）：", "铁律「判据必须在进程之外」："),
    # ---- CommandRouter.java
    ("07_源码/agent/src/com/ledi/agent/core/CommandRouter.java",
     "可用 {@code _route_inventory.py --diff} 机械对差。", "可用机械对差脚本复核。"),
    ("07_源码/agent/src/com/ledi/agent/core/CommandRouter.java",
     "由 _route_inventory.py 对差兜底。", "由对差脚本兜底。"),
    ("07_源码/agent/src/com/ledi/agent/core/CommandRouter.java",
     "（KWS 见记忆 §I3；离线 ASR 本轮实测 events=0）", "（KWS 与离线 ASR 均有失效实测）"),
    ("07_源码/agent/src/com/ledi/agent/core/CommandRouter.java",
     "（KWS 见记忆 §I3；离线 ASR", "（KWS 与离线 ASR"),
    ("07_源码/agent/src/com/ledi/agent/core/CommandRouter.java",
     "（见 §C7）", ""),
    ("07_源码/agent/src/com/ledi/agent/core/CommandRouter.java",
     "        // （判决见 06_语音唤醒专题/离线ASR_任意语音可行性判决_v97.md）。\n", ""),
    ("07_源码/agent/src/com/ledi/agent/core/CommandRouter.java",
     "（部署脚本第 4 步，判据说明见 _deploy_v122.py 文件头）", ""),
    # ---- cap / ui / core 杂项
    ("07_源码/agent/src/com/ledi/agent/cap/Abilities.java",
     "（本项目 §8 的经典坑）", ""),
    ("07_源码/agent/src/com/ledi/agent/cap/Memory.java",
     "（本项目铁律 §9：编得过 ≠ 跑得了）", "（编得过 ≠ 跑得了）"),
    ("07_源码/agent/src/com/ledi/agent/cap/Emotion.java",
     "（静态逆向所得，REF §K）", "（静态逆向所得）"),
    ("07_源码/agent/src/com/ledi/agent/ui/SleepGate.java",
     "（本项目 §8：只统计成功 = 假绿灯）", "（只统计成功 = 假绿灯）"),
    ("07_源码/agent/src/com/ledi/agent/core/BootReceiver.java",
     "（证据见项目根 {@code _boot_diag.txt}），而接收器", "而接收器"),
    ("07_源码/agent/src/com/ledi/agent/core/AgentService.java",
     "（2026-10-11，同一份真人录音 /sdcard/ledi_kws/human2.pcm）", "（2026-10-11，同一份真人录音 PCM）"),
    # ---- 工具与前端
    ("07_源码/agent/tools/_VocabDump.java",
     '"06_语音唤醒专题/词表/_java"', '"vocab_out"'),
    ("07_源码/agent/assets/programmer.html",
     "（与网关 pose_lib.py 同名，物理安全）", "（与电脑端参考实现同名，物理安全）"),
    # ---- 根部说明文档
    ("08_魔改版APK/说明.txt",
     "补丁来源脚本（改造工程）：make_offline.py / apk_patch_ui.py / apk_icon_jett.py 等。",
     "补丁由改造工程内的等长字节码补丁脚本完成（本仓只收补丁结果，见上面对差清单）。"),
]

# 发布闸：本仓文本里不允许再出现的模式（正则，人话说明）
# 注意：/sdcard/ledi_kws/ 是运行时路径，不在禁列；`_kws/` 用负向断言避开它。
BANNED = [
    (r"(?<![A-Za-z0-9_])[A-Za-z]:[\\/]", "本机盘符绝对路径"),
    (r"06_语音", "工程内部目录名"),
    (r"见记忆|记忆 §", "工程内部记忆引用"),
    (r"(?<!§\d)(?<![A-Za-z])§[A-IK-Z][A-Za-z0-9]*", "工程内部小节编号（§C7/§I3/§K 类；本文档内 §2.1 这类不算）"),
    (r"(?<![a-zA-Z])_kws/", "工程内部目录名"),
    (r"_kws_lib", "工程内部脚本名"),
    (r"pose_lib", "工程内部模块名"),
    (r"_route_inventory", "工程内部文件名"),
    (r"_deploy_v\d+", "工程内部脚本名"),
    (r"_boot_diag", "工程内部取证文件名"),
    (r"(?i)make_offline|apk_patch_ui|apk_icon_jett", "工程内部脚本名"),
]
SKIP_EXT_EXT = (".dex", ".so", ".img", ".bin", ".lic", ".bsg", ".png", ".jpg", ".zip",
                ".apk", ".jar", ".bat", ".ogg", ".mp3", ".wav", ".ttf")
TEXT_EXT = (".md", ".json", ".csv", ".py", ".txt", ".yml", ".yaml", ".html",
            ".java", ".xml", ".cfg", ".ini")


def _read(p):
    """utf-8 优先，GBK 兜底；返回 (文本, 编码)。"""
    b = open(p, "rb").read()
    for enc in ("utf-8", "gbk"):
        try:
            return b.decode(enc), enc
        except UnicodeDecodeError:
            continue
    raise UnicodeDecodeError("utf-8", b, 0, 1, "neither utf-8 nor gbk: %s" % p)


def apply(apply_changes=True):
    fails = []
    for rel, old, new in RULES:
        p = os.path.join(ROOT, rel.replace("/", os.sep))
        if not os.path.isfile(p):
            fails.append("缺文件: %s" % rel)
            continue
        t, enc = _read(p)
        n = t.count(old)
        if n == 0:
            print("  [0 命中] %-52s %r" % (rel, old[:52]))
            continue
        if n > 1:
            fails.append("命中 %d 次（应为 1）: %s :: %r" % (n, rel, old[:40]))
            continue
        if apply_changes:
            open(p, "w", encoding=enc, newline="").write(t.replace(old, new))
            print("  [OK] %-52s %r -> %r" % (rel, old[:44], (new[:26] or "(删)")))
        else:
            print("  [将改] %-52s %r" % (rel, old[:44]))
    return fails


def scan():
    import re
    # 白名单：`C:\Program Files\*` 是 Windows 标准系统目录，构建脚本用它探测工具链
    # 是通用可移植写法（不含任何个人路径）。除此之外的盘符路径一律不放行。
    ALLOW_PREFIX = ("C:\\Program Files", "C:/Program Files")
    bad = []
    for dp, dn, fn in os.walk(ROOT):
        dn[:] = [d for d in dn if d not in (".git", "__pycache__", "dist")]
        for f in sorted(fn):
            if f.endswith(SKIP_EXT_EXT) or not f.lower().endswith(TEXT_EXT):
                continue
            p = os.path.join(dp, f)
            if os.path.abspath(p) == os.path.abspath(__file__):
                continue
            try:
                t = open(p, encoding="utf-8", errors="replace").read()
            except OSError:
                continue
            for pat, why in BANNED:
                for m in re.finditer(pat, t):
                    frag = m.group(0)
                    # 盘符命中只有 2 字符，前缀豁免要看命中点往后的完整文本
                    ahead = t[m.start():m.start() + 20]
                    if why == "本机盘符绝对路径" and any(ahead.startswith(a) for a in ALLOW_PREFIX):
                        continue
                    bad.append((why, os.path.relpath(p, ROOT), frag,
                                t[max(0, m.start() - 45):m.start() + 45].replace("\n", " | ")))
    return bad


if __name__ == "__main__":
    mode_check = "--check" in sys.argv
    print("=== 1) 规则替换（%s）===" % ("只报告" if mode_check else "落盘"))
    fails = apply(apply_changes=not mode_check)
    print()
    print("=== 2) 发布闸残留扫描 ===")
    bad = scan()
    if not bad:
        print("  干净：0 处残留")
    for why, rel, frag, ctx in bad:
        print("  [%s] %s :: %r" % (why, rel, frag[:50]))
        print("      ...%s..." % ctx)
    print()
    print("规则异常 %d；残留 %d 处" % (len(fails), len(bad)))
    sys.exit(1 if (fails or bad) else 0)
