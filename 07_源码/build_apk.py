# -*- coding: utf-8 -*-
"""
通用 APK 构建脚本（无 Gradle）：
  aapt2 link(manifest-only, 无 res) -> javac -> d8 -> 组装 zip -> zipalign -> apksigner

用法:
  python build_apk.py --name LediAgent --src agent/src --manifest agent/AndroidManifest.xml \
      --out dist/LediAgent.apk [--merge-dex 原apk路径] [--sign platform|debug]

  --merge-dex: 把指定 APK 里的所有 classes*.dex 追加为本 APK 的 classes2+.dex
               （用于在 agent 内复用原包的 EngineService/串口代码，纯反射调用）
  --libs-from: 从指定 APK 复制 lib/<abi>/*.so 进本 APK（默认全部）
               ★ 必做：原厂 EngineService 在类加载时会 System.loadLibrary("serial_port")，
                 不把 libserial_port.so 打进本包，绑定引擎的瞬间就会
                 UnsatisfiedLinkError 崩进程（已实测，v1~v9 全部中招）。
  --lib:       只复制这些名字的 .so（可多次指定）；不指定则从 --libs-from 全量复制
  --res:       资源目录（默认没有：whole APK 是 manifest-only、连 res/ 和 R 类都没有）。
               给了就 aapt2 compile -> link，这样 manifest 才能写 android:icon="@mipmap/ic_launcher"。
               App 图标素材由 make_icon.py --apk-res 生成（三端共用的齿轮）。
               做了资源就必须断言它真的进了包 —— 否则会静默产出没有图标的 APK。
"""
import argparse, os, re, shutil, subprocess, sys, zipfile, tempfile

# ---------------------------------------------------------------------------
# 工具链定位（可移植版）
#   原版把三条路径写死在本机上，别人 clone 下来必然跑不通。
#   这里改成：**环境变量优先 → SDK 目录自动探测 → 常见默认位置兜底**，
#   一个都找不到就直接报错退出（而不是静默产出一个坏 APK）。
#   需要的环境变量（任选其一即可，能自动找到的可以不设）：
#     ANDROID_BUILD_TOOLS  build-tools 目录（须含 aapt2 / d8 / zipalign / apksigner）
#     ANDROID_SDK_ROOT     Android SDK 根（或用 ANDROID_HOME）
#     ANDROID_JAR          android.jar 全路径
#     JAVA_HOME            JDK 17+ 根目录（javac / d8 都要用）
# ---------------------------------------------------------------------------
def _sdk_root():
    return os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME') or ''

def _first_dir(*cands):
    for c in cands:
        if c and os.path.isdir(c):
            return os.path.abspath(c)
    return None

def _latest_build_tools():
    env = os.environ.get('ANDROID_BUILD_TOOLS')
    if env and os.path.isdir(env):
        return os.path.abspath(env)
    bt = os.path.join(_sdk_root(), 'build-tools')
    if os.path.isdir(bt):
        vers = sorted(d for d in os.listdir(bt) if os.path.isdir(os.path.join(bt, d)))
        if vers:
            return os.path.join(bt, vers[-1])
    return None

def _find_android_jar():
    env = os.environ.get('ANDROID_JAR')
    if env and os.path.isfile(env):
        return os.path.abspath(env)
    pf = os.path.join(_sdk_root(), 'platforms')
    if os.path.isdir(pf):
        for p in sorted((d for d in os.listdir(pf) if d.startswith('android-')), reverse=True):
            j = os.path.join(pf, p, 'android.jar')
            if os.path.isfile(j):
                return j
    return None

def _find_jdk():
    env = os.environ.get('JAVA_HOME')
    exe = 'javac.exe' if os.name == 'nt' else 'javac'
    if env and os.path.isfile(os.path.join(env, 'bin', exe)):
        return os.path.abspath(env)
    # 不写死具体版本：在常见安装父目录下逐个找 */bin/javac，取编号最大的
    import glob
    if os.name == 'nt':
        roots = [r'C:\Program Files', r'C:\Program Files (x86)',
                 r'C:\Program Files\Android\Android Studio']
    else:
        roots = ['/usr/lib/jvm', '/opt']
    best = None
    for r in roots:
        cands = (glob.glob(os.path.join(r, '*', 'bin', exe))
                 + glob.glob(os.path.join(r, 'jbr', 'bin', exe)))
        for j in cands:
            d = os.path.dirname(os.path.dirname(j))
            if best is None or d > best:
                best = d
    return best

BT  = _latest_build_tools()
AJ  = _find_android_jar()
JDK = _find_jdk()
# 签名密钥**不随本仓分发**：platform 私钥就是这台 ROM 的安装闸门本身（谁拿到谁能签平台应用），
# 所以仓库里没有 tools/keys/。密钥取 AOSP build/target/product/security 的 platform 测试密钥
# （口令 android），转成 PKCS#12 后放到任意目录，设 LEDI_SIGN_KEYS 指过去。
KEYS = os.environ.get('LEDI_SIGN_KEYS') or os.path.join(os.path.dirname(os.path.abspath(__file__)), 'tools', 'keys')

if not BT or not os.path.isdir(BT):
    raise SystemExit('找不到 Android build-tools。请安装 Android SDK 并设置 ANDROID_SDK_ROOT，'
                     '或设 ANDROID_BUILD_TOOLS 指向 build-tools 目录（需含 aapt2/d8/zipalign/apksigner）。')
if not AJ or not os.path.isfile(AJ):
    raise SystemExit('找不到 android.jar。请设 ANDROID_SDK_ROOT（其下需有 platforms/android-*/android.jar）'
                     '或直接设 ANDROID_JAR。')
if not JDK:
    raise SystemExit('找不到 JDK(17+)。请设置 JAVA_HOME 指向 JDK 根目录。')

def run(cmd, env=None):
    e = dict(os.environ)
    e['JAVA_HOME'] = JDK
    e['PATH'] = os.path.join(JDK, 'bin') + os.pathsep + e.get('PATH', '')
    if env: e.update(env)
    print('+', ' '.join(cmd))
    r = subprocess.run(cmd, env=e, capture_output=True, text=True, errors='replace')
    if r.returncode != 0:
        out = (r.stdout or '') + '\n' + (r.stderr or '')
        # 只显示 .java 相关的错误行（javac 中文输出可能是乱码，靠行号定位）
        for ln in out.splitlines():
            if '.java:' in ln or 'FAILED' in ln or ln.strip().startswith('符号') or 'error' in ln.lower():
                print(ln)
        raise SystemExit(f'FAILED: {cmd[0]}')
    if r.stdout.strip():
        print(r.stdout.strip()[-1500:])
    return r

def build(name, src, manifest, out, merge_dex=None, sign='platform',
          libs_from=None, libs=None, assets_dir=None, libs_dir=None, res_dir=None,
          version_code=None, version_name=None, target_sdk=25):
    work = tempfile.mkdtemp(prefix=f'build_{name}_')
    try:
        gen = os.path.join(work, 'gen'); classes = os.path.join(work, 'classes')
        os.makedirs(gen); os.makedirs(classes)

        # 0) 资源编译（可选）
        #    本工程默认是 aapt2 link "manifest-only"，APK 里没有 res/、也没有 R 类，
        #    好处是编译链极短；代价就是没法用 @mipmap/... 这类资源引用
        #    —— App 图标（android:icon）恰恰必须有资源。
        #    所以给了 --res 时先 aapt2 compile 成 flat zip，再喂给 link。
        #    注意顺序：必须先 compile，link 阶段才能解析 manifest 里的 @mipmap/ic_launcher。
        flat_res = None
        if res_dir and os.path.isdir(res_dir):
            flat_res = os.path.join(work, 'res.zip')
            run([os.path.join(BT, 'aapt2.exe'), 'compile',
                 '--dir', os.path.abspath(res_dir), '-o', flat_res])

        # 1) aapt2 link（无 --res 时仍是 manifest-only）
        base = os.path.join(work, 'base.apk')
        link_cmd = [os.path.join(BT, 'aapt2.exe'), 'link', '-o', base,
             '-I', AJ, '--manifest', os.path.abspath(manifest),
             '--min-sdk-version', '21',
             # ★ targetSdk 可覆盖：本仓库里 android.jar 是 36.1，但 **机器人包**
             #   一直是 25（老 ROM + 免走运行时权限那一套）。手机端遥控器要 30，
             #   所以这里不能再写死 —— 写死会让 manifest 里那句 targetSdkVersion
             #   变成一句空话（产物里根本不是那个数）。默认 25，与既有行为一致。
             '--target-sdk-version', str(target_sdk),
             '--auto-add-overlay']
        # ★★ 这里原来写死 '--version-code','1','--version-name','1.0' —— 纯误导：
        #   aapt2 这两个参数**只在 manifest 没写时才注入**，而我们的 manifest 一直
        #   写着 versionCode/versionName ⇒ 那两行**从来没生效过**。它制造过一起真事故：
        #   v142/v143/v144 三个产物的 versionCode **全是 142**，而三者**字节数又完全相同**
        #   ⇒ 「机器人上装的是哪一版」在产物层面彻底不可判（只剩 md5）。
        #   ⇒ 版本号**只有 manifest 一个事实源**；且由 _rebuild_agent.py 按 `vNNN` 参数
        #     自动改写（见那边的 _place_version），不许手改、更不许改这里。
        if version_code:
            link_cmd += ['--version-code', str(version_code)]
        if version_name:
            link_cmd += ['--version-name', version_name]
        # newcore：assets 目录（programmer.html 等）经 aapt2 打进 APK
        if assets_dir and os.path.isdir(assets_dir):
            link_cmd += ['-A', os.path.abspath(assets_dir)]
        if flat_res:
            link_cmd.append(flat_res)       # 编译产物走位置参数
        run(link_cmd)

        # 资源进没进包必须当场断言：这套无 Gradle 的流程里，"资源没编译进去"
        # 不会报错，只会安静地产出一个没有图标的 APK（装到机器上才发现是默认安卓小绿人）。
        if flat_res:
            got = [n for n in zipfile.ZipFile(base).namelist()
                   if re.match(r'res/mipmap[-/]', n)]
            if not got:
                raise SystemExit('--res 指定了资源目录，但 link 出的 base.apk 里没有 '
                                 'res/mipmap/* —— manifest 的 @mipmap/ic_launcher 会解析不到')
            print('base.apk 里的 mipmap: ' + ', '.join(sorted(got)))

        # 2) javac
        java_files = []
        for root, _, files in os.walk(src):
            for f in files:
                if f.endswith('.java'):
                    java_files.append(os.path.join(root, f))
        if not java_files:
            raise SystemExit('no java sources')
        run([os.path.join(JDK, 'bin', 'javac.exe'),
             '-source', '8', '-target', '8', '-encoding', 'UTF-8',
             '-bootclasspath', AJ, '-classpath', AJ,
             '-d', classes] + java_files)

        # 3) d8 -> classes.dex
        #    class 文件数量多时直接列表会超 Windows 命令行长度上限（曾导致 d8.bat FAILED），
        #    先打包成 classjar 再交给 d8，命令行恒定短。
        dexout = os.path.join(work, 'dex')
        os.makedirs(dexout)
        class_files = []
        for root, _, files in os.walk(classes):
            for f in files:
                if f.endswith('.class'):
                    class_files.append(os.path.join(root, f))
        classjar = os.path.join(work, 'classes.jar')
        with zipfile.ZipFile(classjar, 'w', zipfile.ZIP_DEFLATED) as z:
            for cf in class_files:
                z.write(cf, os.path.relpath(cf, classes).replace('\\', '/'))
        run([os.path.join(BT, 'd8.bat'), '--release', '--lib', AJ,
             '--min-api', '21', '--output', dexout, classjar])

        # 4) 组装 APK
        apk = os.path.join(work, 'unsigned.apk')
        shutil.copy(base, apk)
        with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
            z.write(os.path.join(dexout, 'classes.dex'), 'classes.dex')
            if merge_dex:
                zz = zipfile.ZipFile(merge_dex)
                i = 2
                for n in zz.namelist():
                    if n.endswith('.dex'):
                        z.writestr(f'classes{i}.dex', zz.read(n))
                        i += 1
                print(f'merged dexes from {merge_dex} (classes2..classes{i-1})')

            # 4b) 打入 native 库（必须是 ZIP_STORED：.so 不能压缩，
            #     安装时 PackageManager 直接 mmap/libpath 里读）
            if libs_from:
                zz = zipfile.ZipFile(libs_from)
                want = set(libs) if libs else None
                got = []
                for info in zz.infolist():
                    fn = info.filename
                    if not fn.startswith('lib/') or not fn.endswith('.so'):
                        continue
                    if want is not None and os.path.basename(fn) not in want:
                        continue
                    z.writestr(zipfile.ZipInfo(fn), zz.read(fn),
                               compress_type=zipfile.ZIP_STORED)
                    got.append(fn)
                if not got:
                    raise SystemExit(f'--libs-from 没匹配到任何 .so（want={sorted(want) if want else "ALL"}）')
                print(f'libs from {libs_from}: ' + ', '.join(got))

            # 4c) 从本地目录打入 native 库（ZIP_STORED）—— 用于打包灵云 TTS 等
            #     手动提取的 so（原厂 APK 里的 lib/armeabi/*.so 已复制到 agent/libs/armeabi/）
            if libs_dir:
                got_dir = []
                for root, _, files in os.walk(libs_dir):
                    for f in files:
                        if not f.endswith('.so'):
                            continue
                        full = os.path.join(root, f)
                        # 确定 abi 子目录（libs_dir 下的一层目录名，如 armeabi）
                        rel = os.path.relpath(full, libs_dir).replace('\\', '/')
                        arcname = 'lib/' + rel
                        with open(full, 'rb') as fh:
                            data = fh.read()
                        z.writestr(zipfile.ZipInfo(arcname), data,
                                   compress_type=zipfile.ZIP_STORED)
                        got_dir.append(arcname)
                if not got_dir:
                    raise SystemExit(f'--libs-dir 没匹配到任何 .so')
                print(f'libs from dir {libs_dir}: ' + ', '.join(got_dir))

        # 5) zipalign
        aligned = os.path.join(work, 'aligned.apk')
        run([os.path.join(BT, 'zipalign.exe'), '-p', '-f', '4', apk, aligned])

        # 6) 签名
        if sign == 'platform':
            ks, alias, pw = os.path.join(KEYS, 'platform.p12'), 'platform', 'android'
        else:
            ks, alias, pw = os.path.join(KEYS, 'debug.p12'), 'debug', 'android'
        if not os.path.isfile(ks):
            raise SystemExit(
                '找不到签名密钥: ' + ks + '\n'
                '  密钥不随本仓分发（platform 私钥 = 这台 ROM 的安装闸门，见 编译说明.md「关于签名密钥」）。\n'
                '  自备：取 AOSP build/target/product/security 的 platform 测试密钥（口令 android），\n'
                '  转 PKCS#12 后放入某目录，并设环境变量 LEDI_SIGN_KEYS 指向该目录。')
        os.makedirs(os.path.dirname(out), exist_ok=True)
        run([os.path.join(BT, 'apksigner.bat'), 'sign',
             '--ks', ks, '--ks-key-alias', alias, '--ks-pass', f'pass:{pw}',
             '--out', out, aligned])
        sz = os.path.getsize(out)
        print(f'\nOK -> {out}  ({sz/1048576:.1f} MB)')
    finally:
        shutil.rmtree(work, ignore_errors=True)

if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('--name', required=True)
    ap.add_argument('--src', required=True)
    ap.add_argument('--manifest', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--merge-dex', default=None)
    ap.add_argument('--libs-from', default=None)
    ap.add_argument('--lib', action='append', default=None)
    ap.add_argument('--assets', default=None, help='assets 目录（打进 APK 的 assets/）')
    ap.add_argument('--libs-dir', default=None, help='本地 so 目录（打进 APK 的 lib/<abi>/）')
    ap.add_argument('--res', dest='res_dir', default=None,
                    help='res 目录（aapt2 compile+link，用于 android:icon 等资源引用）')
    ap.add_argument('--target-sdk', dest='target_sdk', type=int, default=25,
                    help='aapt2 link 的 --target-sdk-version（默认 25 = 机器人包的既有行为）')
    ap.add_argument('--sign', default='platform', choices=['platform', 'debug'])
    ap.add_argument('--version-code', dest='version_code', type=int, default=None,
                    help='※ 一般别用：aapt2 仅在 manifest 未声明时注入，版本号真源是 manifest')
    ap.add_argument('--version-name', dest='version_name', default=None)
    a = ap.parse_args()
    build(a.name, a.src, a.manifest, a.out, a.merge_dex, a.sign,
          a.libs_from, a.lib, a.assets, a.libs_dir, a.res_dir,
          version_code=a.version_code, version_name=a.version_name,
          target_sdk=a.target_sdk)
