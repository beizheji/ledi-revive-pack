# -*- coding: utf-8 -*-
"""整理交付包的文件编码/行尾，并做一次整体自检。

  .bat  -> 纯 ASCII + CRLF（cmd 唯一稳的形态；带中文会被 cmd 切碎）
  .txt  -> UTF-8 + LF（GitHub / 现代编辑器通用；旧版"GBK 最稳"是本机记事本视角，公开发布不适用）
  .py/.md -> UTF-8 + LF（原样，能跑能看）
自检：.bat 必须 0 个非 ASCII 字节；.txt 必须是合法 UTF-8；包结构齐全。
"""
import os
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
fails = []


def convert(path, enc, crlf):
    raw = open(path, "rb").read()
    try:
        txt = raw.decode("utf-8")
    except UnicodeDecodeError:
        txt = raw.decode("gbk", "replace")
        print("  (已是 GBK) %s" % os.path.relpath(path, ROOT))
    txt = txt.replace("\r\n", "\n").replace("\r", "\n")
    if crlf:
        txt = txt.replace("\n", "\r\n")
    try:
        data = txt.encode(enc)
    except UnicodeEncodeError as e:
        fails.append("%s 无法按 %s 编码：第 %d 字符 %r" % (path, enc, e.start, txt[e.start]))
        return
    if data == raw:
        print("  (无需改) %s" % os.path.relpath(path, ROOT))
        return
    with open(path, "wb") as f:
        f.write(data)
    print("  -> %-6s CRLF=%s  %s" % (enc, crlf, os.path.relpath(path, ROOT)))


def main():
    print("== 1. 整理编码/行尾 ==")
    # ★ SP Flash Tool 的刷机输入一个字节都不能动（scatter 被改过行尾会导致 md5 对不上，
    #   使用者按 README 校验值核对时直接 FAIL）。清单式排除，别靠扩展名猜。
    DO_NOT_TOUCH = {"01_刷机包/MT6580_Android_scatter.txt"}
    for base, _, files in os.walk(ROOT):
        for fn in sorted(files):
            p = os.path.join(base, fn)
            if os.path.relpath(p, ROOT).replace("\\", "/") in DO_NOT_TOUCH:
                print("  (跳过·刷机输入) %s" % os.path.relpath(p, ROOT))
                continue
            low = fn.lower()
            if low.endswith(".bat"):
                convert(p, "ascii", True)
            elif low.endswith(".txt"):
                convert(p, "utf-8", False)
            elif low.endswith((".md", ".py")):
                convert(p, "utf-8", False)

    print("\n== 2. 自检 ==")
    for base, _, files in os.walk(ROOT):
        for fn in sorted(files):
            p = os.path.join(base, fn)
            rel = os.path.relpath(p, ROOT)
            if fn.lower().endswith(".bat"):
                raw = open(p, "rb").read()
                bad = [b for b in raw if b > 127]
                if bad:
                    fails.append(".bat 里有 %d 个非 ASCII 字节：%s" % (len(bad), rel))
                    print("  [FAIL] %s 非 ASCII 字节 %d" % (rel, len(bad)))
                else:
                    print("  [OK  ] %-34s 纯 ASCII %d B" % (rel, len(raw)))
            if fn.lower().endswith(".txt"):
                raw = open(p, "rb").read()
                try:
                    raw.decode("utf-8")
                    crlf = raw.count(b"\r\n")
                    print("  [OK  ] %-34s UTF-8 可解 %d B  CRLF=%d" % (rel, len(raw), crlf))
                except UnicodeDecodeError:
                    fails.append(".txt 无法按 UTF-8 解码（多半是 GBK）： %s" % rel)
                    print("  [FAIL] %s UTF-8 解不开" % rel)

    need = ["README.md", "脱敏报告.md",
            "01_刷机包/boot.img", "01_刷机包/MT6580_Android_scatter.txt", "01_刷机包/刷入说明.txt",
            "02_素材/开机动画_bootanimation.zip", "02_素材/屏保_800x480.jpg", "02_素材/屏保_原图1024x1024.jpg",
            "03_App/LediAgent.public-v223.apk", "03_App/脱敏说明.txt",
            "04_脚本/一键落地.bat", "04_脚本/_apply_public.py", "tools/_build_public_pack.py"]
    print("\n  -- 结构 --")
    for n in need:
        p = os.path.join(ROOT, n.replace("/", os.sep))
        ok = os.path.exists(p) and os.path.getsize(p) > 0
        print("  [%s] %-38s %s" % ("OK  " if ok else "FAIL", n,
                                   ("%d B" % os.path.getsize(p)) if os.path.exists(p) else "缺失"))
        if not ok:
            fails.append("缺失或 0 字节：" + n)

    print("\n== 结论 ==")
    if fails:
        print("  ★★ %d 项未通过：" % len(fails))
        for f in fails:
            print("     - " + f)
        return 1
    print("  全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
