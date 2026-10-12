# -*- coding: utf-8 -*-
"""把「公开版素材」落到机器人上（开机动画 / 屏保 / App）。

★ 这是给使用者跑的脚本，不是自动执行的。默认**只打印、不动手**；
   加 --yes 才真的写。

用法:
    python _apply_public.py --ip 192.168.1.100            # 演练：只打印命令
    python _apply_public.py --ip 192.168.1.100 --yes      # 真的写
    可选:
      --serial <adb序列号>   多设备时指定（如 0123456789ABCDEF）
      --skip-apk / --skip-anim / --skip-wallpaper
      --reboot               全部完成后重启（壁纸要重启才生效）

前提：电脑上有 adb；机器人的 adbd 是 root（刷本包的 boot.img 后即是）。
"""
import argparse
import os
import subprocess
import sys
import time

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
APK = os.path.join(ROOT, "03_App", "LediAgent.public-v223.apk")
ANIM = os.path.join(ROOT, "02_素材", "开机动画_bootanimation.zip")
WALL = os.path.join(ROOT, "02_素材", "屏保_800x480.jpg")

ADB = os.environ.get("ADB", "adb")
TMP = "/data/local/tmp"


class Abort(Exception):
    pass


def run(args, timeout=60, quiet=False):
    if not quiet:
        print("  $ " + " ".join(args))
    r = subprocess.run(args, capture_output=True, text=True, errors="replace",
                       timeout=timeout)
    out = (r.stdout or "") + (r.stderr or "")
    if out.strip() and not quiet:
        for ln in out.strip().splitlines():
            print("      " + ln)
    if r.returncode != 0:
        raise Abort("命令失败(%d): %s\n%s" % (r.returncode, " ".join(args), out.strip()))
    return out


def sh(serial, cmd, **kw):
    return run(["-s", serial, "shell", cmd], **kw) if serial else run(["shell", cmd], **kw)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ip", default=None, help="机器人 IP（会自动 adb connect IP:5555）")
    ap.add_argument("--serial", default=None)
    ap.add_argument("--yes", action="store_true", help="真的写；不加只打印")
    ap.add_argument("--skip-apk", action="store_true")
    ap.add_argument("--skip-anim", action="store_true")
    ap.add_argument("--skip-wallpaper", action="store_true")
    ap.add_argument("--reboot", action="store_true")
    a = ap.parse_args()

    dry = not a.yes
    if dry:
        print("== 演练模式（只打印，不修改）。要真写请加 --yes ==\n")

    for tag, p in (("APK", APK), ("开机动画", ANIM), ("屏保", WALL)):
        if not os.path.exists(p):
            if tag == "APK":
                raise SystemExit(
                    "[中止] 包内缺少 APK: %s\n"
                    "  APK 本体不随本仓 git 分发。请先到\n"
                    "    https://github.com/beizheji/ledi-apk-archive/releases/tag/apk-archive-2026-10-12\n"
                    "  下载 LediAgent.public-v223.apk 放入 03_App/（md5 见 README 校验值表），\n"
                    "  或本次跳过安装：--skip-apk" % p)
            raise SystemExit("[中止] 包内缺少 %s: %s" % (tag, p))
        print("  [包内] %-8s %9d  %s" % (tag, os.path.getsize(p), os.path.basename(p)))

    if a.ip:
        print("\n== 0. 连接 %s:5555 ==" % a.ip)
        if dry:
            print("  $ adb connect %s:5555" % a.ip)
        else:
            run([ADB, "connect", "%s:5555" % a.ip])

    serial = a.serial
    if not dry and not serial:
        out = run([ADB, "devices"])
        devs = [l.split()[0] for l in out.splitlines()[1:] if l.strip().endswith("device")]
        if not devs:
            raise SystemExit("[中止] 没有可用设备（先 adb connect 或用 --ip）")
        serial = devs[0]
        print("  使用设备: " + serial)

    ts = time.strftime("%Y%m%d_%H%M%S")

    # ---- 1. 开机动画 ----
    if not a.skip_anim:
        print("\n== 1. 开机动画 -> /system/media/bootanimation.zip ==")
        dst = "/system/media/bootanimation.zip"
        bak = "/system/media/bootanimation.zip.bak-" + ts
        steps = [
            ("备份现有动画", "cp -f %s %s" % (dst, bak)),
            ("挂载 /system 可写", "mount -o rw,remount /system"),
            ("推送新动画", None),
            ("回读大小核对", "ls -l %s" % dst),
        ]
        if dry:
            for n, c in steps:
                print("  $ adb shell %s" % c if c else
                      "  $ adb push %s %s" % (ANIM, dst))
        else:
            sh(serial, steps[0][1], quiet=True)
            sh(serial, steps[1][1], quiet=True)
            run([ADB] + (["-s", serial] if serial else []) + ["push", ANIM, dst])
            print("      " + sh(serial, steps[3][1]).strip())

    # ---- 2. 屏保 ----
    if not a.skip_wallpaper:
        print("\n== 2. 屏保 -> /data/system/users/0/wallpaper ==")
        wdst = "/data/system/users/0/wallpaper"
        bak = wdst + ".bak-" + ts
        tmpf = TMP + "/_ledi_wp.jpg"
        if dry:
            print("  $ adb push %s %s" % (WALL, tmpf))
            print("  $ adb shell cp -f %s %s     # 先备份原图到 %s" % (wdst, bak, bak))
            print("  $ adb shell cp -f %s %s" % (tmpf, wdst))
            print("  $ adb shell chown system:system %s" % wdst)
            print("  $ adb shell chmod 600 %s" % wdst)
            print("  注：壁纸要重启一次才生效（--reboot）")
        else:
            run([ADB] + (["-s", serial] if serial else []) + ["push", WALL, tmpf])
            sh(serial, "cp -f %s %s" % (wdst, bak), quiet=True)
            sh(serial, "cp -f %s %s" % (tmpf, wdst))
            sh(serial, "chown system:system %s" % wdst, quiet=True)
            sh(serial, "chmod 600 %s" % wdst, quiet=True)
            print("      " + sh(serial, "ls -l %s" % wdst).strip())

    # ---- 3. App ----
    if not a.skip_apk:
        print("\n== 3. 安装 App（覆盖安装，不清数据）==")
        cmd = [ADB] + (["-s", serial] if serial else []) + ["install", "-r", APK]
        if dry:
            print("  $ " + " ".join(cmd))
        else:
            print(run(cmd, timeout=180))

    # ---- 4. 重启 ----
    if a.reboot:
        print("\n== 4. 重启 ==")
        if dry:
            print("  $ adb reboot")
        else:
            run([ADB] + (["-s", serial] if serial else []) + ["reboot"], timeout=30)
    elif not dry:
        print("\n提示：屏保（壁纸）要重启一次才生效 —— 需要的话重跑并加 --reboot。")

    print("\n完成。" + ("（演练模式，什么都没改）" if dry else ""))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Abort as e:
        print("\n[中止] " + str(e))
        sys.exit(1)
