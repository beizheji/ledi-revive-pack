# -*- coding: utf-8 -*-
"""
一条命令重打 LediAgent。

为什么要有这个：build_apk.py 的调用参数太长（merge-dex 原包 / libs-from / libs-dir /
assets / res / sign 一个都不能少），漏一个的后果是静默的 —— 少 --res 就没有图标，
少 --libs-from 就会在绑定原厂引擎的瞬间 UnsatisfiedLinkError 崩进程（v1~v9 全中招）。
把它固化在这里，重打永远不会漏。

so 的取法（v71 起）：原厂包的 lib/*.so **减去** agent/libs 里已有的，逐个 --lib 补搬。
  写死名单踩过两次，方向正好相反：
    · 一个都不限定 → 同名 .so 重复条目 → apksigner parseZipCentralDirectory 失败；
    · 只限定 libserial_port.so → 少搬 libBDVoiceRecognitionClient_MFE_V1_s2.so，
      ASR 解码器一被真正使用就 UnsatisfiedLinkError 崩进程（v69/v70）。
  减法两头都堵住了，原厂将来多带库也自动跟上。

用法:
  python _rebuild_agent.py v60          # -> dist/LediAgent.v60.apk
  python _rebuild_agent.py v60 --dry    # 只打印命令
"""
import io
import os
import re
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
# 原厂包：classes2+.dex 与部分 .so 都从它来。
#   默认取仓内 05_原厂APK/ 里的原厂安装包；也可用环境变量 LEDI_OEM_APK 指定别的。
HERE0 = os.path.dirname(os.path.abspath(__file__))
OEM = os.environ.get('LEDI_OEM_APK') or os.path.join(
    HERE0, '..', '05_原厂APK', 'xldrbt_v1.5.10.apk')
LOCAL_LIB_DIR = os.path.join(HERE, 'agent', 'libs', 'armeabi')      # 我们自己带进包里的那批 .so


def oem_libs_to_take():
    """
    算出该从原厂包搬哪些 .so：**原厂有的 减去 本地已有的**。

    为什么是"减法"而不是写死名字（这是 v71 的教训）：
      以前这里是 `--lib libserial_port.so` —— 只放行一个，理由是原厂 20 个 .so 全搬会
      和 agent/libs 的同名库撞成重复条目、apksigner 直接失败。
      但写死名单的问题在**运行期**才炸，而且报错离原因很远：
        `java.lang.UnsatisfiedLinkError: couldn't find
         "libBDVoiceRecognitionClient_MFE_V1_s2.so"`
        ← 由 com.baidu.speech.EventManagerDec.<clinit> 抛出，栈顶是
          EventManagerAsr.handleAsrStart —— 也就是说**唤醒引擎一切正常**，
          只有 ASR 解码器一被真正使用就找不到库、进程当场崩掉（ActivityManager 只留
          一句 "Skipping crash dialog ... background"，机器人自己的日志里什么都看不见）。
      改成减法之后：不会再漏（原厂有的我们都有），也不会重复（同名库本地优先），
      将来原厂升级多带一个 .so 也自动跟上。
    """
    if not os.path.exists(OEM):
        raise SystemExit('缺少原厂包: %s' % OEM)
    with zipfile.ZipFile(OEM) as z:
        oem = {n.split('/')[-1] for n in z.namelist()
               if n.startswith('lib/') and n.endswith('.so')}
    local = set()
    if os.path.isdir(LOCAL_LIB_DIR):
        local = {f for f in os.listdir(LOCAL_LIB_DIR) if f.endswith('.so')}
    take = sorted(oem - local)
    print('原厂 .so %d 个，本地自带 %d 个 → 补搬 %d 个：%s'
          % (len(oem), len(local), len(take), ', '.join(take) or '(无)'))
    if not take:
        raise SystemExit('一个都不用补搬，说明本地库已覆盖原厂 —— 请人工确认是否合理')
    return take


MANIFEST = os.path.join(HERE, 'agent', 'AndroidManifest.xml')
# build-tools 路径复用 build_apk.py 的自动探测结果（不再写死本机路径）
from build_apk import BT  # noqa: E402


def _assert_version_in_apk(apk, code, name):
    """
    打包后**回读产物**核版本号 —— 判据放在流程里，不靠人记得。
    ★ 为什么不能在源码里断言就完事：aapt2/apksigner 这一路都可能"字节数对得上、
       里面的东西却没进去"（本项目已实证两次）⇒ 判据必须在**产物内部**。
    """
    import re as _re
    r = subprocess.run([os.path.join(BT, 'aapt2.exe'), 'dump', 'badging', apk],
                       capture_output=True, text=True, errors='replace')
    m = _re.search(r"versionCode='(\d+)' versionName='([^']*)'", r.stdout or '')
    assert m, 'aapt2 dump badging 没读到版本号（%s）' % apk
    got_code, got_name = int(m.group(1)), m.group(2)
    assert (got_code, got_name) == (code, name), (
        '★★ 产物版本号与打包参数不一致：产物 %d/%s，期望 %d/%s '
        '（版本号没进包 —— 别只信字节数和文件名）' % (got_code, got_name, code, name))
    print('产物回读核验 OK: versionCode=%d versionName=%s' % (got_code, got_name))





def _place_version(ver):
    """
    把 manifest 的 versionCode/versionName 改成与 `vNNN` 参数一致，返回 (旧, 新)。

    ★ 为什么要有这个：产物名是 LediAgent.v144.apk，但 APK 里写着 versionCode=142 ——
      因为 bump manifest 一直是**手动步骤**，v143/v144 都忘了。而 v142/v143/v144 的
      **字节数完全相同**（17592485），于是「机器人上装的到底是哪一版」在产物层面
      只剩 md5 可判 —— 这正是本项目最贵的那类错（假绿灯）。
      ⇒ 让 **`vNNN` 成为唯一事实源**：打包前自动改写，忘了也不可能（见 build 后的回读断言）。
    """
    digits = ''.join(ch for ch in ver if ch.isdigit())
    if not digits:
        raise SystemExit('版本参数 %r 里没有数字，无法推 versionCode（例：v144）' % ver)
    code = int(digits)
    name = '1.0-' + ver
    p = os.path.join(HERE, MANIFEST)
    src = io.open(p, encoding='utf-8').read()
    old_code = re.search(r'android:versionCode="([^"]*)"', src)
    old_name = re.search(r'android:versionName="([^"]*)"', src)
    assert old_code and old_name, 'manifest 里找不到 versionCode/versionName'
    out = src
    for tag, pat, rep in (('code', r'android:versionCode="[^"]*"',
                           'android:versionCode="%d"' % code),
                          ('name', r'android:versionName="[^"]*"',
                           'android:versionName="%s"' % name)):
        n = len(re.findall(pat, out))
        assert n == 1, 'manifest version%s 命中 %d 次（应 1）' % (tag, n)
        out = re.sub(pat, rep, out, count=1)
    if out != src:
        io.open(p, 'w', encoding='utf-8', newline='').write(out)
    print('manifest 版本号: versionCode %s -> %d / versionName %s -> %s'
          % (old_code.group(1), code, old_name.group(1), name))
    return code, name


def main():
    ver = sys.argv[1] if len(sys.argv) > 1 else None
    if not ver:
        print(__doc__)
        return 1
    out = 'dist/LediAgent.%s.apk' % ver
    cmd = [
        sys.executable, 'build_apk.py',
        '--name', 'LediAgent',
        '--src', 'agent/src',
        '--manifest', 'agent/AndroidManifest.xml',
        '--out', out,
        '--merge-dex', OEM,
        '--libs-from', OEM,
        # ★ 只搬"原厂有、本地没有"的那些，逐个列名传给 --lib。
        #   不写名单 → 同名 .so 撞成重复条目，apksigner 以 parseZipCentralDirectory 失败；
        #   写死一个名字 → 缺 libBDVoiceRecognitionClient_MFE_V1_s2.so，
        #                   ASR 解码器一启动就 UnsatisfiedLinkError 崩进程（v69/v70 踩过）。
        '--libs-dir', 'agent/libs',
        '--assets', 'agent/assets',
        '--res', 'ui_kit/appicon',
        '--sign', 'platform',
    ]
    code, name = _place_version(ver)
    cmd += ['--version-code', str(code), '--version-name', name]
    for lib in oem_libs_to_take():
        cmd += ['--lib', lib]
    print('+', ' '.join(cmd))
    if '--dry' in sys.argv:
        return 0
    # 旧产物先挪走：PyInstaller/zip 覆盖正在被占用的文件会报 safe-delete 错，
    # 而且留着旧名字容易让人以为构建没生效（exe 那次就吃过这个亏）。
    if os.path.exists(out):
        roll = os.path.join('dist', '_rollback')
        os.makedirs(roll, exist_ok=True)
        os.replace(out, os.path.join(roll, os.path.basename(out)))
        print('旧产物已挪到', roll)
    r = subprocess.run(cmd, cwd=HERE)
    if r.returncode == 0:
        print('OK ->', out, '%.1f MB' % (os.path.getsize(os.path.join(HERE, out)) / 1048576.0))
        _assert_version_in_apk(os.path.join(HERE, out), code, name)
    return r.returncode


if __name__ == '__main__':
    sys.exit(main())
