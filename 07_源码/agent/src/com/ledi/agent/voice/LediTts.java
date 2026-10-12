package com.ledi.agent.voice;

import com.ledi.agent.core.BootLog;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import com.sinovoice.hcicloudsdk.api.HciCloudSys;
import com.sinovoice.hcicloudsdk.api.tts.HciCloudTts;
import com.sinovoice.hcicloudsdk.common.Session;
import com.sinovoice.hcicloudsdk.common.tts.ITtsSynthCallback;
import com.sinovoice.hcicloudsdk.common.tts.TtsSynthResult;

/**
 * 灵云（Sinovoice）本地离线 TTS 封装。
 *
 * 让机器人用自带"嘻嘻"音色离线发声，不依赖电脑网关。
 * 调用链：init() -> speak(text) -> 内部 hciTtsSessionStart -> hciTtsSynth
 *        -> 回调拿到 PCM -> AudioTrack 播放 -> hciTtsSessionStop。
 *
 * 灵云 capKey=tts.local.synth（本地合成），音色 xixi（libxixi_CNPackage.dat.so）。
 */
public class LediTts {
    public static final String ENGINE_HCI = "hci";   // 灵云本地离线

    private static volatile LediTts instance;

    private final Context ctx;
    private volatile boolean inited = false;
    private volatile boolean busy = false;
    private volatile String lastError = "";

    private AudioTrack track;
    private volatile Session curSession;     // 当前合成会话（供打断时立即停）
    private volatile boolean abort;          // 打断标志：置位后 speak 尽快返回 false
    private final Object lock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());

    public static synchronized LediTts get(Context c) {
        if (instance == null) instance = new LediTts(c.getApplicationContext());
        return instance;
    }

    private LediTts(Context c) {
        this.ctx = c;
    }

    /** 是否可用（so 是否加载成功） */
    public boolean available() {
        try {
            HciCloudTts t = new HciCloudTts();  // 触发 <clinit> loadLibrary
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 初始化灵云 TTS。
     * @return 0 成功，否则错误码
     */
    public synchronized int init() {
        if (inited) return 0;
        ensureVoicePack();
        try {
            // ① 系统初始化（灵云基础库，先于 TTS 模块初始化）
            //    参数取自原厂 TTSEngine.c() 反编译（真值）：
            //      appKey        = 0d5d5430
            //      developerKey  = efc20b9baad1c6438bc541b1bda07265
            //      authPath      = Environment.getExternalStorageDirectory().getPath()
            //                      （= /sdcard，HCI_AUTH 授权文件在那里）
            //      autoCloudAuth = no        （跳过云端授权，用本地离线授权）
            //      cloudUrl      = test.api.hcicloud.com:8888（必填）
            String authPath = android.os.Environment.getExternalStorageDirectory().getPath();
            String sysParam = "appKey=0d5d5430,"
                    + "developerKey=efc20b9baad1c6438bc541b1bda07265,"
                    + "authPath=" + authPath + ","
                    + "autoCloudAuth=no,cloudUrl=test.api.hcicloud.com:8888";
            int sr = HciCloudSys.hciInitInternal(sysParam, ctx);
            String srInfo = "";
            try { srInfo = HciCloudSys.hciGetErrorInfo(sr); } catch (Throwable ignored) {}
            BootLog.i("灵云TTS: hciInit=" + sr + " info=" + srInfo + " authPath=" + authPath);

            // ② TTS 模块初始化
            String dataPath = dataLibDir();
            String param = "dataPath=" + dataPath
                    + ",initCapKeys=tts.local.synth,fileFlag=android_so";
            int r = HciCloudTts.hciTtsInit(param);
            String rInfo = "";
            try { rInfo = HciCloudSys.hciGetErrorInfo(r); } catch (Throwable ignored) {}
            if (r == 0 || r == 501) {
                inited = true;
                BootLog.i("灵云TTS: hciTtsInit 成功 dataPath=" + dataPath + " (sys=" + sr + ")");
                return 0;
            }
            lastError = "hciTtsInit=" + r + "[" + rInfo + "],hciInit=" + sr + "[" + srInfo + "]";
            BootLog.w("灵云TTS: hciTtsInit=" + r + " " + rInfo + " (hciInit=" + sr + " " + srInfo + ")");
            return r;
        } catch (Throwable t) {
            lastError = "init异常 " + t;
            BootLog.w("灵云TTS: init 异常 " + t);
            return -1;
        }
    }

    /** 数据目录：so 引擎库 + 音色包(xixi_CNPackage.dat 等)所在目录。
     *  与原厂一致：getFilesDir().replace("files","lib") = /data/data/<pkg>/lib。
     *  该目录需同时含引擎 so 和正确文件名的音色包(xixi_CNPackage.dat)。
     *  （音色包已在安装时/首次启动复制到该目录，见下方 ensureVoicePack） */
    private String dataLibDir() {
        try {
            String files = ctx.getFilesDir().getAbsolutePath();
            return files.replace("files", "lib");
        } catch (Throwable t) {
            try {
                return ctx.getApplicationInfo().nativeLibraryDir;
            } catch (Throwable t2) {
                return ctx.getFilesDir().getAbsolutePath();
            }
        }
    }

    /** 是否正在合成 */
    public boolean isBusy() { return busy; }

    /**
     * 确保音色包存在：灵云引擎用 resPrefix(xixi_) + CNPackage.dat 拼文件名，
     * 在 dataPath 目录下 dlopen 加载。若 dataPath 下缺 xixi_CNPackage.dat，
     * 从 /sdcard/ttsRes/（原厂音色包位置）复制过来。
     */
    private void ensureVoicePack() {
        try {
            String libDir = dataLibDir();
            java.io.File lib = new java.io.File(libDir);
            String[] packs = {"xixi_CNPackage.dat", "xixi_DMPackage.dat", "xixi_ENPackage.dat"};
            java.io.File srcDir = new java.io.File(
                    android.os.Environment.getExternalStorageDirectory(), "ttsRes");
            for (String p : packs) {
                java.io.File dst = new java.io.File(lib, p);
                if (dst.exists() && dst.length() > 0) continue;
                java.io.File src = new java.io.File(srcDir, p);
                if (src.exists() && src.length() > 0) {
                    copyFile(src, dst);
                    BootLog.i("灵云TTS: 音色包复制 " + p + " -> " + dst.getAbsolutePath());
                }
            }
        } catch (Throwable t) {
            BootLog.w("灵云TTS: ensureVoicePack 异常 " + t);
        }
    }

    private static void copyFile(java.io.File src, java.io.File dst) throws Exception {
        java.io.InputStream in = new java.io.FileInputStream(src);
        java.io.OutputStream out = new java.io.FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    /** 最近错误 */
    public String lastError() { return lastError; }

    /**
     * 立即打断当前播报（语音唤醒/新指令触发）。
     * abort 标志让 speak() 的等待循环尽快退出；AudioTrack 直接 pause 令声音立刻停；
     * 合成会话同步 stop。未在播报时调用是无害空操作。
     */
    public void stopNow() {
        abort = true;
        try {
            AudioTrack t = track;
            if (t != null) {
                try { t.pause(); } catch (Throwable ignored) {}
                try { t.flush(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
            Session s = curSession;
            if (s != null) HciCloudTts.hciTtsSessionStop(s);
        } catch (Throwable ignored) {}
        BootLog.i("灵云TTS: 打断播报");
    }

    /**
     * 合成并播放一段文本（同步阻塞直到播完，或失败返回）。
     * @param text 文本
     * @return 是否成功
     */
    public boolean speak(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        if (!inited && init() != 0) return false;

        abort = false;
        synchronized (lock) {
            busy = true;
        }
        try {
            final Session session = new Session();
            curSession = session;
            // 会话参数（图灵 TTSEngine 同款）：
            //   capKey=tts.local.synth   —— 本地合成能力
            //   resPrefix=xixi_          —— 音色资源前缀（拼 xixi_CNPackage.dat）
            //   soundEffect=base         —— 基础音效
            String sp = "capKey=tts.local.synth,resPrefix=xixi_,soundEffect=base,"
                    + "speed=5,volume=8,pitch=5,audioFormat=pcm16k16bit";
            int r = HciCloudTts.hciTtsSessionStart(sp, session);
            if (r != 0) {
                lastError = "sessionStart=" + r;
                BootLog.w("灵云TTS: sessionStart 失败 " + r);
                return false;
            }

            // 准备 AudioTrack（16k/16bit 单声道）
            final int SR = 16000;
            final int minBuf = AudioTrack.getMinBufferSize(SR,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(AudioManager.STREAM_MUSIC, SR,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf, 8192), AudioTrack.MODE_STREAM);
            track.play();

            final boolean[] ok = {false};
            final Object done = new Object();

            ITtsSynthCallback cb = new ITtsSynthCallback() {
                @Override
                public boolean onSynthFinish(int status, TtsSynthResult result) {
                    if (status == 0 && result != null) {
                        byte[] pcm = result.getVoiceData();
                        if (pcm != null && pcm.length > 0 && track != null) {
                            try { track.write(pcm, 0, pcm.length); } catch (Throwable ignored) {}
                            ok[0] = true;
                        }
                        return result.isHasMoreData();  // 有后续分段继续
                    }
                    // 出错
                    lastError = "synth status=" + status;
                    return false;
                }
            };

            r = HciCloudTts.hciTtsSynth(session, text, "audioFormat=pcm16k16bit", cb);
            if (r != 0) {
                lastError = "synth=" + r;
                BootLog.w("灵云TTS: synth 失败 " + r);
                HciCloudTts.hciTtsSessionStop(session);
                return false;
            }

            // 等待合成完成（回调在 native 线程；简单轮询等待 busy 释放）
            // 灵云是同步回调，hciTtsSynth 返回时通常已完成；但流式分段需要等待
            long deadline = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < deadline) {
                if (abort) break;               // 被打断（唤醒/新指令），立即收摊
                // 简单等待：hciTtsSynth 若同步则直接返回；异步则稍等
                Thread.sleep(10);
                if (ok[0]) {
                    // 已收到数据，再等一小段确保播完（按数据量估算）
                    break;
                }
            }

            // 停止会话
            HciCloudTts.hciTtsSessionStop(session);

            // 等待 AudioTrack 播完
            if (track != null) {
                Thread.sleep(100);
                try { track.pause(); } catch (Throwable ignored) {}
                try { track.stop(); } catch (Throwable ignored) {}
                try { track.release(); } catch (Throwable ignored) {}
                track = null;
            }

            return ok[0] && !abort;
        } catch (Throwable t) {
            lastError = "speak异常 " + t;
            BootLog.w("灵云TTS: speak 异常 " + t);
            return false;
        } finally {
            synchronized (lock) {
                busy = false;
            }
        }
    }

    /** 释放 */
    public synchronized void release() {
        try {
            if (track != null) { track.release(); track = null; }
            if (inited) { HciCloudTts.hciTtsRelease(); inited = false; }
        } catch (Throwable ignored) {}
    }
}
