# -*- coding: utf-8 -*-
"""组装「乐迪公开版刷机包」的 01_刷机包 / 02_素材 两个目录。

可重跑：每次跑都会重算并核对 md5，任何一项对不上就**红着退出**（不产出"看着成功"的包）。

输入全部走环境变量（本仓不带任何本机路径）：

  LEDI_SRC_BOOT     原 boot.img（v8）文件路径
  LEDI_SRC_SCATTER  MT6580_Android_scatter.txt 路径
  LEDI_SRC_ANIM     原版开机动画 bootanimation.zip 路径
  LEDI_SRC_COVER    屏保原图（1024x1024 jpg）路径
  LEDI_OEM_APK_DIR  （可缺省）改造工程目录，仅供注释追溯

输出：本仓根（由本脚本位置推导，无需配置）。

未设环境变量时脚本会**明确报缺哪一项**，不静默、不猜。
"""
import hashlib
import os
import shutil
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

OUT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_BOOT = os.environ.get("LEDI_SRC_BOOT") or ""
SRC_SCATTER = os.environ.get("LEDI_SRC_SCATTER") or ""
SRC_ANIM = os.environ.get("LEDI_SRC_ANIM") or ""
SRC_COVER = os.environ.get("LEDI_SRC_COVER") or ""

EXPECT = {}


def _expect_md5(p):
    """已随包交付的件，其 md5 固化在这里 —— 重跑时防止拿错源文件。"""
    return {
        "boot.img": "34c0a9a7665895ec45d20aa91f0e5f07",
        "bootanimation.zip": "4644859f911a6c351252a95f4a970103",
    }.get(os.path.basename(p) or "", None)


fails = []


def md5(p):
    h = hashlib.md5()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def check(tag, path, expect=None):
    if not os.path.exists(path):
        fails.append("%s 源文件不存在: %s" % (tag, path))
        print("  [FAIL] %s 源文件不存在 %s" % (tag, path))
        return None
    got = md5(path)
    sz = os.path.getsize(path)
    ok = (expect is None) or (got == expect)
    print("  [%s] %-12s %9d  %s" % ("OK  " if ok else "FAIL", tag, sz, got))
    if not ok:
        fails.append("%s md5 不符：期望 %s 实际 %s" % (tag, expect, got))
    return got


def copy(src, dst):
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copyfile(src, dst)
    a, b = md5(src), md5(dst)
    if a != b:
        fails.append("复制后 md5 不一致: %s" % dst)
        print("  [FAIL] 复制后 md5 不一致 -> %s" % dst)
    else:
        print("  [OK  ] -> %s  %d B  %s" % (os.path.relpath(dst, OUT), os.path.getsize(dst), b))
    return b


def main():
    print("== 1. 校验源文件 ==")
    missing = [n for n, v in (("LEDI_SRC_BOOT", SRC_BOOT), ("LEDI_SRC_SCATTER", SRC_SCATTER),
                              ("LEDI_SRC_ANIM", SRC_ANIM), ("LEDI_SRC_COVER", SRC_COVER)) if not v]
    if missing:
        print("  ★ 未设环境变量：%s" % ", ".join(missing))
        print("    本仓不带任何本机路径；源文件位置由环境变量给出（见本文件 docstring）。")
        return 1
    check("boot.img(v8)", SRC_BOOT, _expect_md5(SRC_BOOT))
    check("scatter", SRC_SCATTER)
    check("原版开机动画", SRC_ANIM, _expect_md5(SRC_ANIM))
    check("屏保原图", SRC_COVER)

    print("\n== 2. 落 01_刷机包 ==")
    copy(SRC_BOOT, os.path.join(OUT, "01_刷机包", "boot.img"))
    copy(SRC_SCATTER, os.path.join(OUT, "01_刷机包", "MT6580_Android_scatter.txt"))

    print("\n== 3. 落 02_素材 ==")
    copy(SRC_ANIM, os.path.join(OUT, "02_素材", "开机动画_bootanimation.zip"))
    copy(SRC_COVER, os.path.join(OUT, "02_素材", "屏保_原图1024x1024.jpg"))
    from PIL import Image
    im = Image.open(SRC_COVER).convert("RGB")
    print("  原图尺寸", im.size)
    # 机器人屏 800x480：按"填满"缩放后居中裁切
    tw, th = 800, 480
    s = max(tw / im.width, th / im.height)
    im2 = im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), Image.LANCZOS)
    left = (im2.width - tw) // 2
    top = (im2.height - th) // 2
    im2 = im2.crop((left, top, left + tw, top + th))
    out_jpg = os.path.join(OUT, "02_素材", "屏保_800x480.jpg")
    im2.save(out_jpg, "JPEG", quality=92, subsampling=0)
    print("  [OK  ] -> %s  %d B  %s" % (os.path.relpath(out_jpg, OUT),
                                       os.path.getsize(out_jpg), md5(out_jpg)))
    with Image.open(out_jpg) as chk:
        assert chk.size == (800, 480), chk.size
    print("  回读尺寸 OK 800x480")

    print("\n== 4. 结论 ==")
    if fails:
        print("  ★★ 有 %d 项失败，未通过：" % len(fails))
        for f in fails:
            print("     - " + f)
        return 1
    print("  全部通过（boot.img / scatter / 原版动画 / 屏保 均已按 md5 核对）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
