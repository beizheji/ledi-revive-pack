package com.ledi.agent.voice;

import com.ledi.agent.core.AgentService;
import com.ledi.agent.core.BootLog;
import com.ledi.agent.core.CommandRouter;
import com.ledi.agent.core.J;
import com.ledi.agent.core.MainThread;
import com.ledi.agent.net.Proxies;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONObject;

/**
 * 在线（云端）语音听写通路 —— 驱动<b>包内现成</b>的讯飞 MSC SDK。
 *
 * <h3>为什么"任意语音"只能走这条路</h3>
 * 百度那套离线引擎是<b>语法受限解码器 + 词典固化在声学模型里</b>：
 * 它只可能吐出词表内的整句或什么都不吐，<b>离线听写任意话在这台机器上不存在</b>。
 * 本地跑神经声学模型也不可能（RAM 471MB / 空闲仅 ~31MB）。
 *
 * <h3>凭据从哪来</h3>
 * 设备出厂的语音组件（classes2.dex 里的 {@code com.iflytek.cloud.*} 与
 * {@code lib/armeabi/libmsc.so}）本就带有一组出厂授权标识；本类沿用这份
 * <b>设备已有</b>的配置，<b>不要求使用者另行申请或填写任何 key</b>。
 * 初始化串固定为 {@code appid=<出厂值>,engine_mode=msc}（{@code msc} = 在线，
 * 本地模式是 {@code local}）。授权能否通过由设备端 SDK 自行校验——
 * 本类只如实回吐错误码，不做任何绕过。
 *
 * <h3>授权校验</h3>
 * MSC 会在初始化/首帧时做授权校验（appid + 包名/签名）；本包包名与出厂 App 不同，
 * <b>校验是否放行在拿到真机前是未知数</b>。所以本类写成「只跑一轮、原样吐错误码」的探针：
 * 错误码本身就是判据（10111/10105 类 = 授权问题，文字结果 = 通路已通）。
 *
 * <h3>接口签名（反汇编 classes.dex 抄出，反射必须按精确签名匹配）</h3>
 * <pre>
 *   SpeechUtility.createUtility(Context, String)                 -> SpeechUtility   [static]
 *   SpeechRecognizer.createRecognizer(Context, InitListener)     -> SpeechRecognizer[static]
 *   SpeechRecognizer.setParameter(String, String)                -> boolean
 *   SpeechRecognizer.startListening(RecognizerListener)          -> int
 *   SpeechRecognizer.writeAudio(byte[], int, int)                -> int      ★ 三参数版
 *   SpeechRecognizer.stopListening()                             -> void
 *   SpeechRecognizer.isListening()                               -> boolean
 *   RecognizerResult.getResultString()                           -> String
 *   SpeechError.getErrorCode() / getPlainDescription(boolean)
 *   RecognizerListener: onBeginOfSpeech / onEndOfSpeech
 *                       onResult(RecognizerResult, boolean)
 *                       onError(SpeechError)
 *                       onVolumeChanged(int, byte[])
 *                       onEvent(int, int, int, Bundle)
 * </pre>
 * 参数键名同样抄自 {@code SpeechConstant}（129 个字段逐个核对过），
 * <b>不要凭记忆写</b>：{@code audio_source / vad_enable / sample_rate / engine_type /
 * domain / language / accent / result_type / timeout}。
 */
public final class CloudAsr {

    /** 出厂语音组件自带的授权标识（随包保存的原厂 dex 中原样存在，见 NOTICE.md）。 */
    static final String APPID = "562c8bae";

    /**
     * MSC 初始化串。{@code engine_mode=msc} = 在线。
     *
     * <p>注意 {@code createUtility} <b>一个进程只该调一次</b>；重复调 MSC 会打日志并
     * 返回已有实例，但为了可读性这里仍用 {@code utilTried} 挡住。</p>
     */
    static final String INIT_STRING = "appid=" + APPID + ",engine_mode=msc";

    private static final String SR_CLS = "com.iflytek.cloud.SpeechRecognizer";
    private static final String SU_CLS = "com.iflytek.cloud.SpeechUtility";
    private static final String IL_CLS = "com.iflytek.cloud.InitListener";
    private static final String RL_CLS = "com.iflytek.cloud.RecognizerListener";

    /** 16k / 16bit / 单声道裸 PCM 一帧的字节数：40ms 的音频。 */
    static final int CHUNK = 1280;
    /** 单次送入的上限（30 秒），防止误传大文件把内存和上传都撑爆。 */
    static final int MAX_BYTES = 16000 * 2 * 30;

    private final Context ctx;

    /** 最近一次运行的汇总（给 /ia 看当前状态用）。 */
    private volatile String lastSummary = "";
    private volatile boolean busy;
    /** 谁在用（诊断用）：文件路径或 "mic"。 */
    private volatile String busyWhat = "";

    /**
     * ★★★ v167：采音期间的「心跳」回调，每 {@link #MIC_TICK_FRAMES} 帧（3 秒）调一次。
     * 由 {@link VoiceWakeController} 注入。
     *
     * <p><b>为什么需要它</b>：原厂 App 有自己的<b>周期灯效刷新</b> —— 实测它在约 6~7 秒的
     * 周期上发 {@code com.auric.intell.xld.os.led.all.off}（对照：我们连点 10 次蓝灯，
     * 原厂只灭 3 次，两者不同步 ⇒ 是它自发的周期，不是"看见我们亮灯就来灭"）。
     * 于是"收听期间亮蓝灯"会被它中途灭掉，一次超过 7 秒的会话用户就看到灯断了。</p>
     *
     * <p>所以要在<b>已经开始在跑</b>的采音循环里顺手补一次 —— 不新增线程、不新增闹钟、
     * 不新增巡检（用户 2026-09-25 明令禁止；MT6580 扛不住）。这里只是把一个每 40ms
     * 就已经在转的循环"顺便"用起来，代价接近零。</p>
     *
     * <p>★ 为什么用回调而不是让 CloudAsr 直接调 AgentService：CloudAsr 只负责"听写"，
     * 不该知道"灯"这回事。给一个通用的"采音心跳"出口，谁需要谁来挂 —— 将来
     * 别的用途（比如采音期间的别的保活）也能复用，不必再改这个类。</p>
     *
     * <p>⚠ 只在**麦克风模式**下触发：文件模式（{@code /iatest}）走的是另一个循环，
     * 那不是在"收听"，不该亮灯。</p>
     */
    public static volatile Runnable micTick;

    /** 采音心跳间隔（帧）：40ms × 75 = 3 秒，比原厂 6~7 秒的灭灯周期密一倍。 */
    public static final int MIC_TICK_FRAMES = 75;

    CloudAsr(Context ctx) {
        this.ctx = ctx;
    }

    /**
     * 全局唯一实例。
     *
     * <p>为什么要唯一：{@code busy} 与"当前会话"是<b>实例状态</b>，而调用方有两处
     * （{@code CommandRouter} 的 HTTP 路由、{@code VoiceWakeController} 的对话链路）。
     * 各自 new 一个就会有<b>两份 busy、谁也挡不住谁</b>，两个线程同时在一个
     * {@code SpeechRecognizer} 上开会话 —— 这正是本项目反复吃到的那类
     * "两个引擎抢同一个资源、进程当场死且不留 tombstone"。</p>
     */
    private static volatile CloudAsr inst;

    public static CloudAsr get(Context ctx) {
        CloudAsr c = inst;
        if (c == null) {
            synchronized (CloudAsr.class) {
                c = inst;
                if (c == null) {
                    c = new CloudAsr(ctx.getApplicationContext());
                    inst = c;
                }
            }
        }
        return c;
    }

    /** 会话互斥：同一时刻只允许一轮（文件或麦克风）。 */
    private final AtomicBoolean inSession = new AtomicBoolean(false);

    /** 尝试占坑；占用中直接返回错误 JSON，由调用方原样回给用户。 */
    private String enter(String what) {
        if (!inSession.compareAndSet(false, true)) {
            return "{\"ok\":false,\"busy\":true,\"busy_what\":\"" + J.esc(busyWhat)
                    + "\",\"error\":\"上一轮还没收尾（" + J.esc(busyWhat)
                    + "），拒绝并发开会话\"}";
        }
        busy = true;
        busyWhat = what;
        return "";
    }

    private void exit() {
        busy = false;
        busyWhat = "";
        inSession.set(false);
    }

    // ------------------------------------------------------------------ 初始化

    private static volatile boolean utilReady;
    private static volatile String utilErr = "";
    private static volatile int initCode = -9999;      // createRecognizer 的 onInit 回执
    private static volatile Object recognizer;
    private static final Object INIT_LOCK = new Object();

    /**
     * ★★★ v166：识别器被<b>重建</b>了几次、最近一次因为什么。
     *
     * <p>为什么必须做成可读的计数、而不是只留一行日志：本项目的量具铁律——
     * {@code /sdcard/ledi_agent.log} 会被自家 SDK 刷屏行<b>整份挤掉</b>
     * （实测同一文件几秒内从 614 行掉到 13 行）⇒ <b>"日志里没有"不能当证据</b>。
     * 有了这两个数，验"坏引擎有没有被换掉"就变成一次 {@code GET /status}，
     * 不必去日志里翻（也翻不到）。</p>
     */
    private static volatile int resetN;
    private static volatile String resetLast = "";

    /**
     * ★★★ v218：本地门控（{@link #hasSpeech}）的可读计数 —— 探了几次 / 判到人声几次 /
     * 最近一次的实测数值。
     *
     * <p>为什么非要有这组数：v217 首轮静默实测只看到 {@code empty} 在涨，<b>判不出</b>
     * "门控到底拦掉了没有"（{@code empty} 把"门控拦掉"和"放行到云但云没听清"混成一体）。
     * 有了 {@code gate_n / gate_hit / gate_last}，一次 {@code GET /status} 就能算出
     * <b>误放行率 = (放行次数 - 云听清次数) / gate_n</b>，不必去翻会被自家 SDK 挤掉的日志。</p>
     */
    private static volatile int gateN;
    private static volatile int gateHit;
    private static volatile String gateLast = "";
    /** ★ v220：最近一次"等安静"（{@link #waitQuiet}）的实测值。 */
    private static volatile String quietLast = "";

    /**
     * ★ v218：门控判据的两条硬下限（详见 {@link #hasSpeech}）。
     *
     * <p>旧值 {@code thrOn} 钳在 {@code [500,1200]} 是首轮实测暴露的问题：真机环境底噪
     * 只有 417~497，而 500 的下限几乎贴着底噪，房间里的关门/掉落/电机瞬态轻松越线
     * ⇒ 静默期每 26s 误放行 ~2 次去建云会话（日志里 {@code 10118} + 反复"重建识别器"）。</p>
     */
    static final int GATE_THR_MIN = 1500;        // RMS 下限；真人近场语音 rms_max ≈ 4700
    static final int GATE_SUSTAIN_FRAMES = 6;    // 窗口内累计超阈帧数下限（6×40ms = 240ms）

    /**
     * 建 MSC 工具 + 识别器（都必须在主线程）。
     *
     * <p>和百度那套一样：从无 Looper 的 HTTP 线程直接碰 SDK 会踩
     * {@code Can't create handler inside thread that has not called Looper.prepare()}
     * 之类的坑，所以统一走 {@link MainThread#call}。</p>
     *
     * @return 出错时返回错误串，成功返回空串
     */
    private String ensureRecognizer() {
        synchronized (INIT_LOCK) {
            if (recognizer != null) return "";
            try {
                MainThread.call(new Callable<Void>() {
                    @Override public Void call() throws Exception {
                        if (!utilReady) {
                            try {
                                Class<?> su = Class.forName(SU_CLS);
                                Method create = su.getMethod("createUtility", Context.class, String.class);
                                Object u = create.invoke(null, ctx, INIT_STRING);
                                utilReady = true;
                                BootLog.i("在线听写 MSC createUtility -> " + (u != null)
                                        + " (" + INIT_STRING + ")");
                            } catch (Throwable t) {
                                utilErr = "createUtility: " + chain(t);
                                BootLog.w("在线听写 " + utilErr);
                                return null;
                            }
                        }
                        Class<?> il = Class.forName(IL_CLS);
                        Object initListener = Proxy.newProxyInstance(il.getClassLoader(),
                                new Class<?>[]{il}, new InvocationHandler() {
                                    @Override public Object invoke(Object p, Method m, Object[] a) {
                                        Object g = Proxies.guard(p, m, a);
                                        if (g != Proxies.UNHANDLED) return g;
                                        if ("onInit".equals(m.getName())) {
                                            int argc = a == null ? 0 : a.length;
                                            initCode = (argc > 0 && a[0] instanceof Integer)
                                                    ? ((Integer) a[0]).intValue() : -1;
                                            BootLog.i("在线听写 MSC onInit code=" + initCode);
                                        }
                                        return Proxies.nil(m);
                                    }
                                });
                        Class<?> sr = Class.forName(SR_CLS);
                        Method create = sr.getMethod("createRecognizer", Context.class, il);
                        Object r = create.invoke(null, ctx, initListener);
                        if (r == null) throw new Exception("createRecognizer 返回 null");
                        recognizer = r;
                        BootLog.i("在线听写 MSC 识别器就绪, onInit=" + initCode);
                        return null;
                    }
                }, 12000L);
            } catch (Throwable t) {
                return "ensureRecognizer: " + chain(t);
            }
            return "";
        }
    }

    /**
     * 把识别器<b>丢掉</b>，让下一轮 {@link #ensureRecognizer} 重新 {@code createRecognizer}。
     *
     * <p>★ 为什么必须有这条路：原实现里 {@code recognizer} 是<b>一次建好、终身复用</b>，
     * 一旦它进了坏状态，<b>后面每一轮都会跟着坏</b>，而唯一的出路是重启进程。</p>
     *
     * <p>本项目对"引擎半死"早有记录（离线那套也一样）：连跑几次之后连对照词都失败、
     * 只能重启进程、用例间隔还得 ≥6s。在线这条 v121 现场则是<b>第二种坏法</b>：
     * 会话不报错、{@code writeAudio} 每块都返 0，但引擎<b>一个音量事件都没有</b>
     * （{@code vol_max=0}、{@code onBeginOfSpeech} 一次不来）、最后回
     * {@code 10118 您好像没有说话哦} —— 即"数据收下了、服务端一个字没听见"。
     * 这种状态靠"下一轮再试一次"是<b>试不好</b>的，必须换一个干净的识别器。</p>
     *
     * @param reason 触发原因（只用于记日志）
     */
    void resetRecognizer(String reason) {
        Object r = recognizer;
        recognizer = null;                 // ★ 先摘掉引用：即使 destroy 失败，下一轮也会重建
        initCode = -9999;
        resetN++;
        resetLast = reason == null ? "" : reason;
        if (r == null) return;
        BootLog.w("在线听写 重建识别器（原因: " + reason + "）");
        try {
            MainThread.call(new Callable<Void>() {
                @Override public Void call() throws Exception {
                    Object o = r;
                    // stop → cancel → destroy，逐级尝试，哪个不存在就跳过。
                    for (String mn : new String[]{"stopListening", "cancel", "destroy"}) {
                        try { o.getClass().getMethod(mn).invoke(o); } catch (Throwable ignored) {}
                    }
                    try { o.getClass().getMethod("release").invoke(o); } catch (Throwable ignored) {}
                    return null;
                }
            }, 5000L, "asr.reset");
        } catch (Throwable t) {
            // ⚠ 失败也不回滚：留着旧引用比丢掉它更危险（下一轮会继续用坏引擎）。
            BootLog.w("在线听写 重建识别器时收尾失败（引用已摘除，不影响重建）: " + chain(t));
        }
    }

    // ------------------------------------------------------------------ 跑一轮

    /**
     * 用设备上的音频文件跑一轮在线听写（<b>不碰麦克风</b>）。
     *
     * <p>先走文件输入的原因和百度那套一样：把「麦克风/环境/时序」一整类变量锁死，
     * 只剩「网络 + 授权 + 服务端识别」三个，出了问题能一刀切开。</p>
     *
     * @param path   设备上的 wav 或裸 pcm（16k/16bit/单声道；wav 会自动剥头）
     * @param paceMs 每 1280 字节之间的间隔，40 ≈ 实时；0 = 全速灌
     * @param waitMs 收尾后等最终结果的上限
     * @param extra  额外参数 JSON（键名用讯飞 SpeechConstant 的字符串，如
     *               {@code {"asr_ptt":"0"}}），可为空
     */
    public String recognizeFile(String path, int paceMs, int waitMs, String extra) {
        long t0 = System.currentTimeMillis();
        String error = "";

        byte[] pcm = null;
        String warn = "";
        try {
            Object[] r = readPcm(path);
            pcm = (byte[]) r[0];
            warn = (String) r[1];
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + J.esc("读音频失败: " + chain(t))
                    + "\",\"file\":\"" + J.esc(path) + "\"}";
        }

        String er = ensureRecognizer();
        if (!er.isEmpty()) {
            return "{\"ok\":false,\"stage\":\"init\",\"error\":\"" + J.esc(er)
                    + "\",\"util_err\":\"" + J.esc(utilErr)
                    + "\",\"init_code\":" + initCode + ",\"file\":\"" + J.esc(path) + "\"}";
        }

        String busyErr = enter("file:" + path);
        if (!busyErr.isEmpty()) return busyErr;

        final Session s = new Session();
        try {
            // ---- 1) 参数 + 起会话（主线程）----
            er = startSession(s, extra);
            if (!er.isEmpty()) throw new Exception(er);

            // ---- 1.5) 回读参数：确认 setParameter 真的落进了库里 ----
            readbackParams(s);

            // ---- 2) 灌音频（工作线程；writeAudio 本来就是给喂流线程用的）----
            //
            // 契约（反汇编 thirdparty.p.a([BII)I）：**返回 0 = 成功**，非 0 是错误码：
            //   21004 = 还没有会话（startListening 的会话还没建好 → 就是在"写太早"）
            //   10106 = audio_source 不是 -1（会话不在音频流模式）
            //   10109 = buffer 参数不合法
            // 所以这里：0 才推进 offset；非 0 先当"等就绪"（有界），
            // 连续失败就早停 —— 绝不能让"计数器在涨"掩盖"一块都没进去"。
            Method wa = recognizer.getClass().getMethod("writeAudio",
                    byte[].class, int.class, int.class);
            int off = 0;
            while (off < pcm.length && s.writeFails < 30) {
                int n = Math.min(CHUNK, pcm.length - off);
                if (writeFrame(wa, s, pcm, off, n)) off += n;
                if (paceMs > 0) Thread.sleep(paceMs);
            }
            // 尾巴：让引擎把最后几帧发完
            Thread.sleep(300);

            // ---- 3) 收尾（主线程）----
            stopSession();
            s.done.await(waitMs <= 0 ? 15000 : waitMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            error = chain(t);
            BootLog.w("在线听写失败: " + error);
        } finally {
            exit();
        }

        long ms = System.currentTimeMillis() - t0;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":").append(s.text.length() > 0 && s.errCode == 0);
        sb.append(",\"src\":\"cloud/iflytek\"");
        sb.append(",\"appid\":\"").append(APPID).append('"');
        sb.append(",\"file\":\"").append(J.esc(path)).append('"');
        sb.append(",\"bytes\":").append(pcm == null ? 0 : pcm.length);
        sb.append(",\"chunks\":").append(s.chunks);
        sb.append(",\"write_fails\":").append(s.writeFails);
        sb.append(",\"write_rc\":").append(s.rcJson());
        sb.append(",\"ready_wait_ms\":").append(s.readyWaitMs);
        sb.append(",\"readback\":\"").append(J.esc(s.readback)).append('"');
        sb.append(",\"pace_ms\":").append(paceMs);
        sb.append(",\"init_code\":").append(initCode);
        sb.append(",\"start_ret\":").append(s.startRet);
        sb.append(",\"params\":\"").append(J.esc(s.params.toString().trim())).append('"');
        sb.append(",\"text\":\"").append(J.esc(s.text)).append('"');
        sb.append(",\"partials\":").append(s.partials.size());
        sb.append(",\"err_code\":").append(s.errCode);
        sb.append(",\"err_desc\":\"").append(J.esc(s.errDesc)).append('"');
        sb.append(",\"vol_max\":").append(s.volMax);
        sb.append(",\"ms\":").append(ms);
        sb.append(",\"warn\":\"").append(J.esc(warn)).append('"');
        sb.append(",\"error\":\"").append(J.esc(error)).append('"');
        sb.append(",\"events\":[");
        for (int i = 0; i < s.ev.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(J.esc(s.ev.get(i))).append('"');
        }
        sb.append("]}");
        lastSummary = sb.toString();
        BootLog.i("在线听写 结果 " + lastSummary);
        return lastSummary;
    }

    // ------------------------------------------------------------ 会话启停（两条路共用）

    /**
     * 设参数 + {@code startListening}，<b>打包成一次主线程跳转</b>。
     *
     * <p>抽出来是因为现在有两条路（文件 / 麦克风）要走同一套。本项目吃过一次
     * "修在一个地方 ≠ 修好了"（同一个坑在 VoiceDialog 修完、AsrProbe 又踩一遍），
     * 所以凡是两条路共用的契约都必须只有一份实现。</p>
     *
     * @return 出错返回错误串，成功返回空串
     */
    private String startSession(final Session s, final String extra) {
        try {
            MainThread.call(new Callable<Void>() {
                @Override public Void call() throws Exception {
                    Object r = recognizer;
                    if (r == null) throw new Exception("recognizer 为空");
                    setParams(r, s.params, extra);
                    Method m = r.getClass().getMethod("startListening",
                            Class.forName(RL_CLS));
                    Object ret = m.invoke(r, makeListener(s));
                    s.startRet = (ret instanceof Integer) ? ((Integer) ret).intValue() : -999;
                    return null;
                }
            }, 15000L, "asr.startListening");
        } catch (Throwable t) {
            String e = chain(t);
            // ★ v121：超时**不等于没开始**。`MainThread.call` 超时只是"调用方不等了"，
            //   那条 post 出去的 job 照样会被主线程执行 —— 也就是 `startListening`
            //   稍后真的会被调起来，留下一个**没人认领的活会话**占着麦克风，
            //   下一轮的 start/stop 还得排队排在它后面（这正是"上一轮超时、这一轮
            //   麦开了引擎却一个字没收到"的成因之一）。
            //   ⇒ 超时必须**立刻配套收尾**：让队列里的顺序变成 start → stop，
            //     而不是留一个会话在那里自生自灭。
            if (e.contains("main thread timeout")) {
                BootLog.w("在线听写 startSession 超时，立刻配对收尾 main=" + MainThread.json());
                stopSession();
            }
            return "startListening: " + e;
        }
        if (s.startRet != 0 && s.startRet != -999) {
            return "startListening 返回 " + s.startRet;
        }
        return "";
    }

    /** 回读参数（主线程）：确认 setParameter 真的落进了库里。 */
    private void readbackParams(final Session s) {
        try {
            MainThread.call(new Callable<Void>() {
                @Override public Void call() throws Exception {
                    Method gp = recognizer.getClass().getMethod("getParameter", String.class);
                    StringBuilder rb = new StringBuilder();
                    String[] keys = {"audio_source", "engine_type", "domain", "sample_rate",
                                     "vad_enable", "language", "accent", "result_type"};
                    for (String k : keys) {
                        Object v = null;
                        try { v = gp.invoke(recognizer, k); } catch (Throwable ignored) {}
                        if (rb.length() > 0) rb.append(' ');
                        rb.append(k).append('=').append(v == null ? "<null>" : String.valueOf(v));
                    }
                    s.readback = rb.toString();
                    return null;
                }
            }, 8000L);
        } catch (Throwable t) {
            s.readback = "回读失败: " + chain(t);
        }
        BootLog.i("在线听写 参数回读 " + s.readback);
    }

    /** {@code stopListening}（主线程）。 */
    private void stopSession() {
        try {
            MainThread.call(new Callable<Void>() {
                @Override public Void call() throws Exception {
                    recognizer.getClass().getMethod("stopListening").invoke(recognizer);
                    return null;
                }
            }, 12000L);
        } catch (Throwable t) {
            BootLog.w("在线听写 收尾失败: " + chain(t));
        }
    }

    /**
     * 写一帧，按 <b>0 = 成功</b> 的语义处理。
     *
     * @return true 表示这一帧被引擎收下了（调用方可推进 offset）
     */
    private boolean writeFrame(Method wa, Session s, byte[] buf, int off, int len) throws Exception {
        Object ret = wa.invoke(recognizer, buf, Integer.valueOf(off), Integer.valueOf(len));
        int rc = (ret instanceof Integer) ? ((Integer) ret).intValue() : -999;
        s.rc(rc);
        if (rc == 0) {
            s.chunks++;
            s.writeOk = s.chunks;
            return true;
        }
        // 一块都还没进去 ⇒ 会话大概还没建好（21004）。有界地等，别无限转。
        if (s.chunks == 0 && s.readyWaitMs < 6000) {
            s.readyWaitMs += 40;
            Thread.sleep(40);
            return false;
        }
        s.writeFails++;
        Thread.sleep(20);
        return false;
    }

    // ------------------------------------------------------------ 麦克风实时听写

    /** 一轮麦克风听写的结果：既给人看的 JSON，也给代码用的字段。 */
    public static final class MicResult {
        String text = "";
        int errCode;
        String errDesc = "";
        public String json = "{}";
        /** cloud / cloud-init-fail / mic-fail */
        String route = "cloud";
        /**
         * 采集/会话<b>硬故障</b>的种类（{@code ""} = 通路正常）。
         *
         * <p>★ 为什么必须单列：{@code errCode} 只在"服务端回过话"时才有意义。
         * 麦克风压根没开起来时它是 <b>0</b>，而调用方按
         * "err_code==0 或 10118 = 正常静音"去分类，就会把<b>一次硬故障报成
         * "用户没说话"</b>。v121 真机现场就是这么发生的：</p>
         * <pre>
         *   在线听写 麦克风失败: Exception: startListening:
         *        RuntimeException: main thread timeout after 15000ms
         *   ★麦克风结果 {"ok":false,"route":"cloud","err_code":0,"mic_opened":false,...}
         *   ⇒ 对话日志记成「听=cloud-empty」，机器人还对主人说"你怎么不说话呀"
         * </pre>
         *
         * <p>取值：{@code start-fail} / {@code mic-open-fail} / {@code mic-starved}
         * / {@code mic-ex}（循环里抛的其它异常）。</p>
         */
        String failKind = "";
        /** 一句话诊断（走哪条路、为什么没结果）。 */
        String note = "";
        int chunks, sentBytes, frames, micRetries, rmsMax, rmsFloor, loudAtMs, silenceHitMs, thr;
        /** 起音那一刻已经连续超阈多少帧（≥ {@link #ONSET_FRAMES} 才算开口）。 */
        int onsetFrames;
        /** 本次实际用的持续阈值（峰值/4 与起音阈值取大）—— 与 {@link #thr} 对照可看出触发是否正常。 */
        int thrSustain;
        /** 送进去的音频时长（ms）与实际循环耗时（ms）—— 节奏仪表。 */
        int audioMs, loopMs;
        /** 落盘的 raw PCM 路径（ground-truth 仪器，可为空）。 */
        String dumpPath = "";
        /** 实际用的 AudioRecord 音频源（1=MIC，6=VOICE_RECOGNITION）。 */
        int audioSource = 1;
        /** 引擎是否自己报过 onEndOfSpeech（它一报就说明云端已判到句尾）。 */
        boolean sawEos;
        /** 收尾那一刻"离最后一次有声"过了多久（ms）；-1 = 全程没声。 */
        int lastLoudAgoMs = -1;
        /** 每秒一个峰值的 RMS 包络，形如 {@code "12/6418/320/55/"}。 */
        String rmsTrace = "";
        /** {@code AudioRecord.read()} 原始返回码直方图 —— 用来证明"读不到"还是"没说话"。 */
        final Hist readRc = new Hist();
        /** read() 返回 &le;0 的次数。 */
        int readFails;
        /** 因为"一帧都读不到"而释放重开麦的次数。 */
        int micReopens;
        /** 是否判定为"麦克风被占用"（打开成功但读不到数据）。 */
        boolean micStarved;
        /** 起音前补灌的前导帧数（0 = 一起音就灌，说明是"一边说一边到"）。 */
        int preRollFrames;
        /** 是否"等不到人声"收工（正常结果，不是故障）。 */
        boolean onsetTimeout;
        /** 写帧失败次数（writeAudio 返回非 0 且上下文不是"等会话就绪"）。 */
        int writeFails;
        /** 引擎自己是否报过 onBeginOfSpeech（{@code false} + 本地 {@link #sawSpeech} = 音频没进到引擎）。 */
        boolean sawBos;
        /** 引擎报过的最大音量（服务端音量为 0 而本地 rms 很高 ⇒ 数据没送达）。 */
        int volMax;
        /**
         * 可疑但<b>不下判决</b>：本地判到人声、引擎却说"您好像没有说话哦"
         * （{@code saw_speech=true} + {@code saw_bos=false} + 已灌够帧）。
         *
         * <p>两种解释都成立且<b>目前无法区分</b>：①音频真的没送达引擎；
         * ②我们的 VAD 被房间底噪误触发、那确实不是人声。所以既不能报成"通路故障"
         * （会误判正常静音），也不能报成"正常静音"（会把真故障咽掉）——
         * 只标成 {@code cloud-quiet} 并把证据留下，等 dump 原始 PCM 来裁决。</p>
         */
        boolean quietSuspect;
        boolean sawSpeech, micOpened;
        long ms;
        /**
         * 本次听写的**预算**（ms）—— ★ v159 加的仪表。
         *
         * <p>为什么必须有它：{@link #ms} 是**实际耗时**，人一不说话几秒就收尾了，
         * 跟"最长能听多久"没关系。而 v159 把录音从 10s/1s 放到 20s/2s 时，
         * 中间隔了两道闸（VoiceDialog 的常量 + VoiceWakeController 的 clamp 上界），
         * 只改一处是**读不出差别的**。露出预算之后：10300=旧口径、20300=新口径。
         */
        int maxMs;

        String textTrim() { return text == null ? "" : text.trim(); }

        /**
         * 精简仪表（给 {@code /status.listen.last} 用）—— 只放"排查时必须看"的那几项，
         * 免得把 /status 撑爆。字段名与 /ilisten 保持一致，两边可以对读。
         */
        String diagJson() {
            return "{\"route\":\"" + route + "\""
                    + ",\"fail_kind\":\"" + J.esc(failKind) + "\""
                    + ",\"text\":\"" + J.esc(text) + "\""
                    + ",\"mic_opened\":" + micOpened
                    + ",\"mic_retries\":" + micRetries
                    + ",\"mic_reopens\":" + micReopens
                    + ",\"mic_starved\":" + micStarved
                    + ",\"read_fails\":" + readFails
                    + ",\"read_rc\":" + readRc.json()
                    + ",\"frames\":" + frames
                    + ",\"chunks\":" + chunks
                    + ",\"write_fails\":" + writeFails
                    + ",\"rms_max\":" + rmsMax
                    + ",\"rms_floor\":" + rmsFloor
                    + ",\"thr\":" + thr
                    + ",\"thr_sustain\":" + thrSustain
                    + ",\"rms_trace\":\"" + J.esc(rmsTrace) + "\""
                    + ",\"saw_speech\":" + sawSpeech
                    + ",\"onset_frames\":" + onsetFrames
                    + ",\"loud_at_ms\":" + loudAtMs
                    + ",\"last_loud_ago_ms\":" + lastLoudAgoMs
                    + ",\"saw_eos\":" + sawEos
                    + ",\"saw_bos\":" + sawBos
                    + ",\"vol_max\":" + volMax
                    + ",\"quiet_suspect\":" + quietSuspect
                    + ",\"onset_timeout\":" + onsetTimeout
                    + ",\"preroll_frames\":" + preRollFrames
                    + ",\"err_code\":" + errCode
                    + ",\"err_desc\":\"" + J.esc(errDesc) + "\""
                    + ",\"init_code\":" + initCode
                    + ",\"max_ms\":" + maxMs          // ★ v159：预算（验"录音放长了没"）
                    + ",\"silence_ms\":" + silenceHitMs // 静音收尾阈值
                    + ",\"ms\":" + ms
                    + ",\"note\":\"" + J.esc(note) + "\"}";
        }
    }

    /**
     * 写一帧，并（可选）把<b>同一份字节</b>落盘。
     *
     * <p>落盘必须和写入走同一条路：分成两处写迟早会漂移，那时候"dump 里明明有声音"
     * 就成了一个查不出所以然的假线索。</p>
     */
    private boolean sendAndDump(Method wa, Session s, byte[] b, int off, int len,
                                java.io.OutputStream dump) throws Exception {
        boolean sent = writeFrame(wa, s, b, off, len);
        if (sent && dump != null) {
            try { dump.write(b, off, len); } catch (Throwable ignored) {}
        }
        return sent;
    }

    /** 麦克风默认最长听多久（ms）—— 与服务端 timeout 保持余量。 */
    static final int MIC_MAX_MS = 8000;
    /**
     * 人开口之后，静音多久就收尾。
     *
     * <p>★★ v165（用户 2026-09-25「用户不说话了再结束录音」）：<b>900ms → 2500ms</b>。
     * 900ms 连"一句话中间换口气"都接不住 —— 现实表现就是用户说半句、机器人抢着答
     * 那半句的答案，或者把停顿当成"这段说完了"。成年人组织一句话时，中途停顿 2 秒
     * 很常见；这里多等 1.6 秒，换的是"少一次把话截断"。</p>
     *
     * <p>★ 它同时也是 {@code /status.listen.silence_ms} 的读数本身（全链只有这一处默认值，
     * 调用方 {@code VoiceWakeController.listenSentence} 传的就是它），
     * 所以改完可以<b>直接从设备读回来验证</b>，不必靠"听起来好像长了一点"。</p>
     */
    static final int MIC_SILENCE_MS = 2500;

    /**
     * 从开麦算起，<b>等"人开口"最多等多久</b>（ms）。
     *
     * <p>★ 这个窗口跟 {@code maxMs}（"开口之后还能说多久"）是两件事：
     * 它只在 {@code sawSpeech == false} 期间生效，一测到开口就作废。</p>
     *
     * <p>★★ v165：<b>5000 → 8000</b>。原来是硬编码在 {@code listenMic} 里的
     * {@code Math.min(maxMs, 5000)}。真机日志的形状是（{@code /status.listen.last}）：
     * 用户摸一下头 → 机器人回一句"在，说。" → 用户还在斟酌要说什么 →
     * 5 秒到 ⇒ {@code onset_timeout=true}、{@code ms=5446}，这一轮判"全程没听到有效人声"
     * 收场。用户感受到的就是"摸了头它根本没在听"，于是越摸越快。（09-25 02:48 那 2 分 19 秒
     * 摸了约 60 次头就是这么来的。）</p>
     *
     * <p>⚠ 单独拉长这一条会放大"误触也空转"的代价，所以必须<b>和 AgentService 的
     * 摸头合并窗口一起改</b>：合并之后一次误触只开一轮麦，而不是每摸一下开一轮。</p>
     */
    static final int ONSET_WAIT_MS = 8000;

    /**
     * 起音要连续超阈多少帧才算"有人开口"（v142）。
     * 3 帧 = 120ms：短于这个的是环境噪声尖峰（真机数据里 500~950 的一大批就是它），
     * 长于这个的才可能是语音的起始段。见 listenMic 里的判据注释。
     */
    static final int ONSET_FRAMES = 3;

    /**
     * <b>从真麦克风听一轮任意话</b>（v99 新增）。
     *
     * <p>和 {@link #recognizeFile} 的唯一区别在音频来源：这里自己开 {@code AudioRecord}
     * 采 16k/16bit/单声道，按 {@link #CHUNK}（40ms）逐帧喂 {@code writeAudio}，
     * 用<b>自己的 RMS 包络</b>判断"人开口了"和"说完了"，而不是依赖云端 VAD
     * （{@code vad_enable=false} 时引擎不会自己断句，端点完全由我们控制，确定性更好）。</p>
     *
     * <p><b>麦克风是独占资源</b>：调用方负有"先把唤醒引擎停掉"的义务
     * （见 {@code VoiceWakeController} 的 MicGate）。本方法只做一件事：
     * 如果 {@code AudioRecord} 开不起来，会有界重试 —— 因为刚让出麦克风的引擎
     * 还需要几十到几百毫秒才真正释放，这一段的失败是<b>竞态而不是故障</b>。</p>
     *
     * @param maxMs    最长听多久（0 = {@link #MIC_MAX_MS}）
     * @param silenceMs 开口后的静音收尾阈值（0 = {@link #MIC_SILENCE_MS}）
     * @param loudRms  人声判定的 RMS 阈值；&le;0 = 前 ~240ms 环境噪声自动定（floor×4，下限 400）
     * @param extra    额外讯飞参数（可为空）
     */
    /**
     * ★ v217：纯本地人声门控 —— 只开麦量几秒 RMS 包络，判"这轮有没有人在说话"。
     *
     * <p>为什么需要它：常识别改成<b>走云</b>之后，若每轮直连云端，没人说话时也会建一次
     * 会话（{@link #ONSET_WAIT_MS} 最长空等 8s）——用户最在意的"不用的时候别一直
     * 往上送"当场就破了。所以先用这条<b>零云开销</b>的路筛一遍：没人声直接跳过本轮，
     * 连会话都不建；判到人声才真正走 {@code cloudListen}。</p>
     *
     * <p>判据与 {@link #listenMic} 内<b>完全同一套</b>（滑动最小底噪 / 3 倍起音阈值 /
     * 连续 {@link #ONSET_FRAMES} 帧超阈），只是不建会话、不写音频 —— 共用同一套判据，
     * 免得出现"门控说有人声、识别说没人声"这种自相矛盾。</p>
     *
     * <p>真机实测（2026-10-11）：这一段<b>不碰百度离线 SDK</b>，因此不受
     * {@code err_no -3004 app name unknwon}（离线 ASR 的麦克风模式在线授权校验）影响。</p>
     *
     * @return true = 判到持续人声；false = 这轮没人说话（或拿不到麦）
     */
    boolean hasSpeech(int maxMs, int loudRms) {
        if (maxMs <= 0) maxMs = 3000;
        String busy = enter("mic");
        if (!busy.isEmpty()) return false;
        gateN++;
        android.media.AudioRecord rec = null;
        MicResult mr = new MicResult();
        try {
            rec = openMic(mr, 1);
            if (rec == null) { gateLast = "mic-open-fail"; return false; }
            byte[] buf = new byte[CHUNK];
            long endAt = System.currentTimeMillis() + maxMs;
            int floor = -1, run = 0, bestRun = 0, nAbove = 0, maxRms = 0, thr = GATE_THR_MIN;
            while (System.currentTimeMillis() < endAt) {
                int got = readFully(rec, buf, CHUNK, mr);
                if (got <= 0) continue;
                int rms = (int) rms16(buf, got);
                if (rms > maxRms) maxRms = rms;
                if (floor < 0) floor = rms;
                else if (rms < floor) floor = rms;
                else floor = floor + Math.max(1, (rms - floor) / 50);
                // ★★ v218：阈值 = max(下限 1500, 底噪×4)。**取消旧的上限 1200** ——
                //   那个上限让"越吵的房间越容易被误触发"，正好反了；底噪自适应上不封顶
                //   才能让稳态噪声（风扇/电视）把阈值一起抬上去。
                thr = loudRms > 0 ? Math.max(GATE_THR_MIN, loudRms)
                        : Math.max(GATE_THR_MIN, floor * 4);
                if (rms >= thr) {
                    nAbove++;
                    run++;
                    if (run > bestRun) bestRun = run;
                } else {
                    run = 0;
                }
            }
            // ★★ v218：两条**同时**满足才算"有人声"，缺一不可：
            //   ① 有一段连续起音（≥ ONSET_FRAMES 帧 = 120ms）—— 排除单帧脉冲；
            //   ② 窗口内累计超阈 ≥ GATE_SUSTAIN_FRAMES 帧（240ms）—— 排除短促瞬态。
            //   为什么必须两条：真人说 2.6s 窗口里天然攒够几百毫秒超阈；而房间里的
            //   关门/掉落/电机咔哒只是 1~5 帧（首轮实测 rms_max=3997 的那次就是这类，
            //   云端照样判定"你没说话"）。只靠单一阈值，两者在 rms_max 上区分不开。
            boolean hit = bestRun >= ONSET_FRAMES && nAbove >= GATE_SUSTAIN_FRAMES;
            gateLast = "floor=" + floor + " max=" + maxRms + " thr=" + thr
                    + " above=" + nAbove + " run=" + bestRun + " -> " + hit;
            if (hit) {
                gateHit++;
                BootLog.i("常识别 门控命中 " + gateLast);
            }
            return hit;
        } catch (Throwable t) {
            gateLast = "ex:" + t;
            return false;
        } finally {
            closeMic(rec);
            exit();
        }
    }

    /**
     * ★★ v220：<b>等房间真正安静下来</b>—— 自适应判据，替代"固定等 N 毫秒"。
     *
     * <p><b>为什么必须自适应（v219 真机日志实证）：</b>机器人喇叭的余响在
     * {@code isSpeaking()} 转 false <b>之后还会持续约 2.6s</b>，而且到麦时
     * {@code rms≈20560~20790}——与真人语音（实测 20800）<b>同量级</b>，靠幅度分不开。
     * 于是凡是在"刚说完话"的窗口里开麦，采到的都是它自己：
     * <ul>
     *   <li>常识别：门控命中（{@code max≈20600}）→ 建云会话 → 云回 10118 白烧一次配额；</li>
     *   <li>对话续听：{@code 第 2 轮 第 1/3 次听：[] 路线=cloud-quiet}——前 1~2 次全废，
     *       要靠第 3 次才听懂用户真正说的话。</li>
     * </ul>
     * 固定延时要么不够（800ms 挡不住 2.6s 的余响）、要么白等（安静房间也等满）。
     * 这里改成"连续采样，直到 RMS 低于阈值并保持一小段才放行"，安静时约 0.4s 就返回。</p>
     *
     * <p>⚠ 超时返回 false 时<b>调用方照常往下走</b>：房间本来就吵（电视/风扇常年在响）
     * 的时候，"安静"可能永远不来，但我们不能因此整轮不听 —— 宁可带上串扰。</p>
     *
     * @param maxMs    最长等多久
     * @param quietThr RMS 安静阈值（&le;0 = {@link #QUIET_THR}）
     * @param holdMs   要连续安静多久才算数（&le;0 = {@link #QUIET_HOLD_MS}）
     * @return true = 已安静；false = 等到超时（或拿不到麦）
     */
    boolean waitQuiet(int maxMs, int quietThr, int holdMs) {
        if (maxMs <= 0) maxMs = 2500;
        if (quietThr <= 0) quietThr = QUIET_THR;
        if (holdMs <= 0) holdMs = QUIET_HOLD_MS;
        String busy = enter("mic");
        if (!busy.isEmpty()) return false;   // 麦被别人占着 ⇒ 交给调用方按原逻辑走
        android.media.AudioRecord rec = null;
        MicResult mr = new MicResult();
        try {
            rec = openMic(mr, 1);
            if (rec == null) return false;
            byte[] buf = new byte[CHUNK];
            long endAt = System.currentTimeMillis() + maxMs;
            long quietSince = -1;
            int maxSeen = 0;
            while (System.currentTimeMillis() < endAt) {
                int got = readFully(rec, buf, CHUNK, mr);
                if (got <= 0) continue;
                int rms = (int) rms16(buf, got);
                if (rms > maxSeen) maxSeen = rms;
                if (rms < quietThr) {
                    if (quietSince < 0) quietSince = System.currentTimeMillis();
                    if (System.currentTimeMillis() - quietSince >= holdMs) {
                        quietLast = "quiet(true) max=" + maxSeen + " thr=" + quietThr;
                        return true;
                    }
                } else {
                    quietSince = -1;
                }
            }
            quietLast = "quiet(false/超时) max=" + maxSeen + " thr=" + quietThr;
            return false;
        } catch (Throwable t) {
            quietLast = "quiet(ex):" + t;
            return false;
        } finally {
            closeMic(rec);
            exit();
        }
    }

    /** ★ v220：等安静用的默认阈值与保持时长（与门控同一口径，便于对差）。 */
    static final int QUIET_THR = GATE_THR_MIN;
    static final int QUIET_HOLD_MS = 360;

    MicResult listenMic(int maxMs, int silenceMs, int loudRms, String extra) {
        return listenMic(maxMs, silenceMs, loudRms, extra, null);
    }

    /**
     * 同 {@link #listenMic(int,int,int,String)}，但把**送进引擎的同一份裸 PCM** 落盘。
     *
     * <p>这是本轮的 ground-truth 仪器（与百度那套的 {@code audio.outfile} 同一个思路：
     * {@code EventManagerMic.run()} 里 outfile 非空就把喂给解码器的原样字节写盘）。</p>
     *
     * <p><b>为什么必须有它：</b>麦克风这条路的失败可能是两类完全不同的东西 ——
     * ①采集本身有问题（HAL 给的不是我们以为的格式/内容）；②采集没问题，是<b>流式</b>
     * 的时序或服务端判定有问题。两者在 {@code text=} 空、{@code err_code=10118}
     * 上的表现<b>一模一样</b>。落盘之后就一刀切开：把 dump 当文件喂 {@code /iatest}，
     * 能出文本 ⇒ 采集是好的，问题在流式；还是空 ⇒ 采集就是坏的。</p>
     */
    MicResult listenMic(int maxMs, int silenceMs, int loudRms, String extra, String dumpPath) {
        return listenMic(maxMs, silenceMs, loudRms, extra, dumpPath,
                MediaRecorder.AudioSource.MIC);
    }

    /**
     * 完整签名。
     *
     * @param audioSource AudioRecord 的音频源。默认 {@code MIC}(1)；
     *                    {@code VOICE_RECOGNITION}(6) 是 ASR 的规范源，在这类 ROM 上
     *                    走的是 HAL 里<b>另一条</b>采集通路（AGC/NS 的开关都不一样），
     *                    所以当"采到的声音云端口径判成静音"时它是一个值得一试的变量。
     */
    MicResult listenMic(int maxMs, int silenceMs, int loudRms, String extra, String dumpPath,
                        int audioSource) {
        final MicResult mr = new MicResult();
        long t0 = System.currentTimeMillis();
        if (maxMs <= 0) maxMs = MIC_MAX_MS;
        if (silenceMs <= 0) silenceMs = MIC_SILENCE_MS;
        mr.maxMs = maxMs;          // ★ v159：把**钳位之后**的真实预算记下来

        String er = ensureRecognizer();
        if (!er.isEmpty()) {
            mr.route = "cloud-init-fail";
            mr.note = "MSC 初始化失败，走离线兜底";
            mr.errCode = -1;
            mr.errDesc = er;
            mr.json = "{\"ok\":false,\"stage\":\"init\",\"route\":\"" + mr.route
                    + "\",\"error\":\"" + J.esc(er) + "\",\"init_code\":" + initCode + "}";
            lastSummary = mr.json;
            return mr;
        }

        String busyErr = enter("mic");
        if (!busyErr.isEmpty()) {
            mr.route = "busy";
            mr.note = "上一轮没收尾";
            mr.json = busyErr;
            return mr;
        }

        final Session s = new Session();
        s.micMode = true;
        android.media.AudioRecord rec = null;
        String error = "";
        try {
            // ---- 1) 起会话 ----
            er = startSession(s, extra);
            if (!er.isEmpty()) throw new Exception(er);
            readbackParams(s);

            // ---- 2) 开麦（要不到就有界重试：多半是唤醒引擎还没松开） ----
            mr.audioSource = audioSource;
            rec = openMic(mr, audioSource);
            if (rec == null) {
                mr.route = "mic-fail";
                throw new Exception("麦克风打不开（重试 " + mr.micRetries + " 次）：" + mr.note);
            }
            mr.micOpened = true;

            // ---- 3) 边采边灌，用 RMS 包络判端点 ----
            Method wa = recognizer.getClass().getMethod("writeAudio",
                    byte[].class, int.class, int.class);
            byte[] buf = new byte[CHUNK];
            long endAt = t0 + maxMs + 800;                 // 起会话也算在预算里
            long tAudio = System.currentTimeMillis();
            long lastLoud = 0;
            // ★ v104：噪声底改成**滑动最小值**，不再用"开头 240ms 的平均值"。
            //   踩过（v103 现场）：标定那 240ms 正好撞上喇叭在响，底噪被算成 4445，
            //   于是起音阈值 = 4445×12 = **53340** —— 真人说话 rms_max 19974 也够不着，
            //   结果 saw_speech=false 而 last_route 还是 cloud，排查时完全看不出问题。
            //   最小值的好处：任何一次"安静"都会把它拉回真实底噪；回升很慢，
            //   所以人声间隙不会把阈值一路推高。
            int floor = -1;
            mr.thr = loudRms > 0 ? loudRms : 900;
            mr.silenceHitMs = silenceMs;
            // 施密特触发：起音用高阈值，起音之后允许掉到峰值的 1/4（有上限）——
            // 上限是为了不让房间底噪把 `lastLoud` 一直顶住（那样静音收尾永不触发，
            // 白灌几秒噪声，表现为"服务端说没说话/终态只剩一个句号"，v101 现场）。
            int thrSustain = mr.thr;
            int speechPeak = 0;
            int loudRun = 0;                         // 连续超阈帧数（v142 起音持续性判据）
            // 前导回滚 + 起音门（v105）：见循环里"起音之前只攒不发"的注释
            final int PRE = 10;                      // 10×40ms = 400ms 前导
            byte[][] pre = new byte[PRE][];
            int preHead = 0, preN = 0;
            boolean streaming = false;
            long onsetDeadline = tAudio + Math.min(maxMs, ONSET_WAIT_MS);

            // ground-truth 落盘：写的就是喂给引擎的同一份字节
            java.io.OutputStream dump = null;
            if (dumpPath != null && dumpPath.trim().length() > 0) {
                try {
                    dump = new java.io.BufferedOutputStream(
                            new java.io.FileOutputStream(dumpPath.trim()));
                    mr.dumpPath = dumpPath.trim();
                } catch (Throwable t) {
                    mr.note = "dump 打不开: " + chain(t);
                    BootLog.w("在线听写 " + mr.note);
                }
            }

            while (System.currentTimeMillis() < endAt) {
                int got = readFully(rec, buf, CHUNK, mr);
                if (got <= 0) {
                    mr.readFails++;
                    mr.readRc.rc(got);
                    // ★★ 开得起来 ≠ 读得到（v102 现场的血证）。
                    //   这条路上"AudioRecord 打开成功、startRecording 也成功，
                    //   但 read() 一直返回 <=0"是可能发生的 —— 麦克风还被唤醒引擎占着。
                    //   旧写法会因此白等满整个窗口，最后返回"没听到内容"，
                    //   而 listen.last_note 里写的是"正常静音"：**一个假判据**，
                    //   把"资源被占"说成了"没人说话"。
                    //   现在：先自己重开一次麦（多半是竞态），还不行就早退并明说。
                    long waited = System.currentTimeMillis() - tAudio;
                    if (mr.frames == 0 && waited > 1200 && mr.micReopens < 2) {
                        mr.micReopens++;
                        BootLog.w("在线听写 麦克风打开了却读不到数据（read=" + got
                                + "，已 " + waited + "ms）→ 释放重开第 " + mr.micReopens + " 次");
                        closeMic(rec);
                        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                        rec = openMic(mr, audioSource);
                        if (rec == null) { mr.micOpened = false; break; }
                        continue;
                    }
                    if (mr.frames == 0 && waited > 4000) {
                        mr.micStarved = true;
                        throw new Exception("麦克风被占用：AudioRecord 已打开，但 read() 连续 "
                                + mr.readFails + " 次返回 " + mr.readRc.first()
                                + "（累计 " + waited + "ms 读不到任何数据）"
                                + " —— 这不是'没人说话'，是采集通道拿不到麦");
                    }
                    continue;
                }
                mr.frames++;
                int rms = (int) rms16(buf, got);
                if (rms > mr.rmsMax) mr.rmsMax = rms;
                // 粗粒度包络：每秒一个峰值。用来一眼看出"声音是不是连续的" ——
                // 只看 rms_max 会被单个尖峰骗过去（"有声音"≠"有人在说话"）。
                if (rms > s.traceMax) s.traceMax = rms;
                if (mr.frames % 25 == 0) {
                    s.rmsTrace.append(s.traceMax).append('/');
                    s.traceSec++;
                    s.traceMax = 0;
                }
                // ★★★ v167：采音心跳 —— 每 3 秒给"正在收听"这段时间一个外部回调。
                //   目前唯一用途是**补灯**：原厂会周期性把灯灭掉（见 micTick 的说明）。
                //   放在这个已经在转的循环里，零新增线程/闹钟；回调自己吞掉异常，
                //   绝不能因为它把采音循环带崩（灯是观感，听写是主线）。
                if (mr.frames % MIC_TICK_FRAMES == 0) {
                    Runnable cb = micTick;
                    if (cb != null) {
                        try { cb.run(); } catch (Throwable ignored) {}
                    }
                }

                // 噪声底：滑动最小值。越静越快降；回升很慢（每帧只补 1/50），
                // 免得人声把"底噪"一路推高、阈值跟着水涨船高。
                if (floor < 0) floor = rms;
                else if (rms < floor) floor = rms;
                else floor = floor + Math.max(1, (rms - floor) / 50);
                mr.rmsFloor = floor;
                // 起音阈值：底噪的 3 倍，钳在 [500, 1200]。
                //
                // ★ 刻意"宁低勿高"（v106）：起音判晚了的代价是**吞掉句首** ——
                //   v105 现场 thr=2500 时，云端只认出"周杰伦的歌吧。"，"放一首"整个丢了
                //   （105 帧里只有 69 帧被送出去，正是开头那 1.4 秒）。
                //   而起音判早了的代价只是"多灌几百毫秒环境音"，引擎的 VAD 和
                //   持续阈值都能兜住。**两种错法的代价不对称，阈值就该往便宜那边偏。**
                //   下限 500：安静房间（底噪十几）也能起音；
                //   上限 1200：房间真有持续底噪时不至于一路误触发。
                int thrOn = loudRms > 0 ? Math.max(200, loudRms)
                        : Math.max(500, Math.min(floor * 3, 1200));
                mr.thr = thrOn;
                long now = System.currentTimeMillis();
                if (rms > speechPeak) speechPeak = rms;
                // 持续阈值：起音之后允许掉到本次人声峰值的 1/4，但不超过起音阈值的 3 倍。
                // 上限的作用是别让房间底噪把 `lastLoud` 一直顶住（那样静音收尾永不触发，
                // 白灌几秒噪声，表现为"服务端说没说话/终态只剩一个句号"，v101 现场）。
                thrSustain = mr.sawSpeech
                        ? Math.max(thrOn, Math.min(speechPeak / 4, thrOn * 3)) : thrOn;
                mr.thrSustain = thrSustain;
                // ★★★ v142：起音要**连续 ONSET_FRAMES 帧**超阈才算数。
                //
                // 为什么必须加这一条（真机数据，不是推理）：2026-09-22~24 的
                // ledi_talk.jsonl 里 112 次 voice 轮次只有 21 次真听到内容，
                // 而其中一大批的 rms_max 是 **501/511/519/526/532/573/593/597/619/
                // 651/697/707/769/864/883/951** —— 全是房间底噪的量级。
                // 起音阈值下限刻意压到 500（v106 的"宁低勿高"，为的是不吞句首），
                // 于是**单帧底噪尖峰就能触发一次上云** ⇒ 花掉一次 appid 配额、
                // 换回一句 10118「您好像没有说话哦」，这正是用户说的
                // "不使用的时候一直向上传递语音消息消耗 API token"。
                //
                // 判据为什么不改成"把阈值调高"：那会重蹈 v105 的覆辙（吞掉句首 1.4 秒）。
                // 两者的**形状不同**：底噪尖峰是单帧（< 120ms），人说话是持续段。
                // 所以用"持续性"去区分，而不是用"幅度" —— 阈值继续宁低勿高，
                // 由连续帧数来否决尖峰。前导缓冲（PRE=400ms）保证起音那几帧不会被丢。
                if (rms >= thrSustain) {
                    if (loudRun == 0 && mr.loudAtMs == 0) {
                        mr.loudAtMs = (int) (now - tAudio);   // 记的是**这一串的第一帧**
                    }
                    loudRun++;
                    s.sawLoud = true;
                    lastLoud = now;
                    if (!mr.sawSpeech && loudRun >= ONSET_FRAMES) {
                        mr.sawSpeech = true;
                        mr.onsetFrames = loudRun;
                    }
                } else {
                    loudRun = 0;
                }

                boolean sent;
                if (!streaming) {
                    // ---- 起音之前：只攒不发（v105）----
                    // 为什么不能在"还没人说话"的时候就开灌：云端自己也有 VAD，
                    // 一段长前导静音会让它先判"没人说话"然后收工 —— v104 现场
                    // 就是这样：只听了 2.7s（人还没开口）就回了没内容。
                    // 所以先攒一个环形前导缓冲，一旦判到起音，**按时间顺序**把它补上，
                    // 这样引擎是从"起音前 240ms"开始听的，既不缺头也不喂静音。
                    byte[] c = new byte[got];
                    System.arraycopy(buf, 0, c, 0, got);
                    pre[preHead] = c;
                    preHead = (preHead + 1) % PRE;
                    if (preN < PRE) preN++;
                    if (mr.sawSpeech) {
                        streaming = true;
                        mr.preRollFrames = preN;
                        for (int k = 0; k < preN; k++) {
                            byte[] f = pre[(preHead - preN + k + PRE * 2) % PRE];
                            sendAndDump(wa, s, f, 0, f.length, dump);
                        }
                    } else if (now > onsetDeadline) {
                        // 等不到人声就收工。把静音灌给云端既没意义又会被判"没说话"，
                        // 还会白占一次识别额度。这是**正常结果**，不是故障。
                        mr.onsetTimeout = true;
                        break;
                    }
                } else {
                    sent = sendAndDump(wa, s, buf, 0, got, dump);
                    if (!sent) {
                        // 会话都还没起来就连续写不进，别死等到 maxMs
                        if (s.chunks == 0 && s.writeFails > 60) break;
                    }
                }
                if (s.done.getCount() == 0) break;                       // 引擎自己给了终态
                // ★ 引擎已经判到句尾 ⇒ 后面都是静音，再灌只是白耗配额，
                //   而且多出来的尾音正是"终态只回一个句号"的来源（v100 现场）。
                if (s.sawEos) break;
                if (mr.sawSpeech && lastLoud > 0 && now - lastLoud >= silenceMs) break;
            }
            mr.chunks = s.chunks;
            mr.sentBytes = s.chunks * CHUNK;
            mr.sawEos = s.sawEos;
            mr.lastLoudAgoMs = lastLoud == 0 ? -1 : (int) (System.currentTimeMillis() - lastLoud);
            mr.rmsTrace = s.rmsTrace.toString();
            // ★ 节奏仪表：写进去的音频时长 vs 实际花了多久。
            //   readFully 被设备按实时节流，两者应当接近 1.0；
            //   明显 >1 说明我们在"追着喂"（buffer 堆积，丢帧或时序错位），
            //   明显 <1 说明喂得比实时快（服务端可能按时间戳判 VAD，会判成静音）。
            mr.audioMs = s.chunks * (CHUNK / 32);          // 1280B @16k/16bit = 40ms
            mr.loopMs = (int) (System.currentTimeMillis() - tAudio);
            if (dump != null) {
                try { dump.flush(); dump.close(); } catch (Throwable ignored) {}
            }
            // 尾巴：最后几帧落地（与文件模式同）
            Thread.sleep(250);

            // ---- 4) 收尾 ----
            stopSession();
            s.done.await(15000L, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            error = chain(t);
            // ★ v121：硬故障必须**自报家门**，不能只留一串 note 就完事。
            //   把 route 从 "cloud" 改成 "cloud-fail"，调用方才会走"通路故障"分支
            //   （回落离线 + 如实告诉主人），而不是把它当成"没人说话"。
            //   ⚠ 判据用 contains 不是 startsWith —— chain() 会在前面加 "Exception: "。
            mr.failKind = error.contains("startListening") ? "start-fail"
                    : (mr.micStarved ? "mic-starved"
                    : (!mr.micOpened && mr.frames == 0 ? "mic-open-fail" : "mic-ex"));
            if ("cloud".equals(mr.route)) mr.route = "cloud-fail";
            BootLog.w("在线听写 麦克风失败 fail_kind=" + mr.failKind + " " + error);
        } finally {
            closeMic(rec);
            exit();
        }

        mr.ms = System.currentTimeMillis() - t0;
        mr.text = s.text;
        mr.errCode = s.errCode;
        mr.errDesc = s.errDesc;
        mr.writeFails = s.writeFails;
        mr.sawBos = s.sawBos;
        mr.volMax = s.volMax;
        // 等超时/异常时 s.text 可能还没定稿，但中间结果已经拿到了 —— 用最长的兜住。
        if (mr.text.trim().isEmpty() && !s.bestText.trim().isEmpty()) mr.text = s.bestText;
        if (!error.isEmpty()) mr.note = error;
        else if (!mr.sawSpeech) {
            mr.note = "全程没听到有效人声（rms_max=" + mr.rmsMax + " 阈值=" + mr.thr + "）";
        }

        // ★★ v121 —— 「这条通路到底坏没坏」的**唯一**判定出口就在这里，调用方只认
        //    route / failKind，不再自己拿 errCode 猜。
        //    为什么必须在这里判：这三种故障的 errCode **全都是 0**（服务端压根没回过话），
        //    而调用方原来按 "err_code==0 || 10118 = 正常静音" 分类 ⇒ 把硬故障报成
        //    "用户没说话"，机器人于是对着一屋子正在说话的人说「你怎么不说话呀」。
        //    ⚠ 抛异常那一路已在 catch 里赋过值 —— 这里只补**没抛异常**的那几路。
        if (mr.failKind.isEmpty() && error.isEmpty()) {
            if (mr.micStarved) mr.failKind = "mic-starved";
            else if (!mr.micOpened) mr.failKind = "mic-open-fail";
            if (!mr.failKind.isEmpty()) {
                mr.route = "cloud-fail";
                mr.note = "通路故障 " + mr.failKind + "（本地 saw_speech=" + mr.sawSpeech
                        + " chunks=" + mr.chunks + " rms_max=" + mr.rmsMax
                        + " 引擎 saw_bos=" + mr.sawBos + " vol_max=" + mr.volMax + "）"
                        + (mr.note.isEmpty() ? "" : " 原note=" + mr.note);
                BootLog.w("在线听写 通路故障 fail_kind=" + mr.failKind + " route=" + mr.route
                        + " " + mr.note);
            }
        }
        // ★ 可疑、但**不下判决**：本地判到人声、引擎却回"您好像没有说话哦"。
        //   为什么不判成故障：`onBeginOfSpeech` 只在"引擎自己起了 VAD"时才来，而我们把
        //   `vad_enable=false`（手动收尾），所以**正常情况下它本来就可能一次不来** ——
        //   项目里翻遍历史日志也**没有一个成功样本**能证明"成功那次一定有 BOS"。
        //   在这种没有基线的信号上判"通路故障"，就是本项目的铁律「判据自己错更贵」。
        //   所以这里只做两件不越界的事：① 标成 cloud-quiet，使它**和真正的静音可区分**；
        //   ② 把证据原样留下，供下一次对照裁决（要裁决就 dump 原始 PCM 去听）。
        mr.quietSuspect = mr.failKind.isEmpty() && mr.textTrim().isEmpty()
                && mr.sawSpeech && !mr.sawBos && mr.chunks >= 8;
        if (mr.quietSuspect) {
            BootLog.w("在线听写 待定：服务端说没说话，但本地判到人声 rms_max=" + mr.rmsMax
                    + " 阈值=" + mr.thr + " chunks=" + mr.chunks
                    + " saw_bos=" + mr.sawBos + " vol_max=" + mr.volMax);
        }
        // ★ 硬故障之后**换一个干净的识别器**：否则"下一轮再试一次"试的还是同一个坏引擎，
        //   表现就是"一连几轮全都失败，重启进程才好"（本项目"引擎半死"一族的第二种坏法）。
        //   代价只是下一轮 createRecognizer 多花几百 ms。
        //
        // ★★★ v166：上面那段注释描述的坏法，**判据原本接不住它**。
        //   `resetRecognizer` 原来只在 `failKind != ""`（start-fail / mic-open-fail /
        //   mic-starved / mic-ex）时触发；而本方法上面 226-237 行亲口写的那种
        //   "第二种坏法"恰恰是：
        //       会话不报错、`writeAudio` **每块都返 0**（⇒ write_fails 一直是 0）、
        //       引擎一个音量事件都没有（`vol_max=0`、`onBeginOfSpeech` 一次不来）、
        //       最后回 `10118 您好像没有说话哦`
        //   —— 这种样本 `failKind` 是空串、`route` 是 "cloud"，被归进"正常静音"，
        //   于是**永远不会重建引擎**，正好落进注释自己警告的"一连几轮全都失败"。
        //
        //   真机证据（2026-09-25 02:48–02:50 的 ledi_talk.jsonl，v159 上跑出来的）：
        //   这一形状连续出现 **102 次**（`err=10118`，`rms_max` 2467/3926/6304/8535/18520，
        //   `chunks` 31~78 ⇒ **确实喂进去了**几百毫秒到 2.6 秒的响亮音频），
        //   中间只成功过 1 次（`ask="嗯。"`），随后连续 3 次 miss ⇒ 进离线模式
        //   ⇒ 用户看到的就是"机器人不理人"。
        //
        //   新触发条件（刻意保守，只认"我们确实喂了响亮的音频、引擎一个字没回"）：
        //     `chunks >= 8`（≥320ms 真的送进去了）× `sawSpeech`（本地判到人声）
        //     × `rmsMax >= 1500`（**响亮**；实测环境底噪只有 417~497，
        //        所以这条把"房间噪声误触发"挡在外面）× `text` 空 × `errCode != 0`。
        //   推不出的东西一律不推：判据里**不含** `saw_bos` / `vol_max` ——
        //   项目已确证**成功样本也是 `saw_bos=false` + `vol_max=0`**（因为
        //   `vad_enable=false`，引擎本来就不起 VAD），拿它们判故障就是"判据自己错"。
        //
        //   为什么这个改动不会伤到正常轮次：触发前提是**这一轮已经失败了**
        //   （`text` 空且 `errCode != 0`）。已经失败的一轮不会因为"下一轮换了个引擎"
        //   而变坏，最多让下一轮多花几百毫秒 createRecognizer。
        boolean fedLoudButNothing = mr.failKind.isEmpty()
                && mr.textTrim().isEmpty()
                && mr.errCode != 0
                && mr.sawSpeech
                && mr.chunks >= 8
                && mr.rmsMax >= 1500;
        if (!mr.failKind.isEmpty()) {
            resetRecognizer("fail_kind=" + mr.failKind);
        } else if (fedLoudButNothing) {
            // 记成与"硬故障"可区分的名字，免得事后把两类混成一笔账
            BootLog.w("在线听写 喂了响亮音频（rms_max=" + mr.rmsMax + " chunks=" + mr.chunks
                    + "）却拿到 err=" + mr.errCode + " 且无文本 ⇒ 按'引擎可能已退化'重建识别器");
            resetRecognizer("fed-loud-empty rms=" + mr.rmsMax + " chunks=" + mr.chunks
                    + " err=" + mr.errCode);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":").append(mr.textTrim().length() > 0 && mr.errCode == 0);
        sb.append(",\"src\":\"cloud/iflytek\"");
        sb.append(",\"route\":\"").append(mr.route).append('"');
        sb.append(",\"fail_kind\":\"").append(J.esc(mr.failKind)).append('"');
        sb.append(",\"appid\":\"").append(APPID).append('"');
        sb.append(",\"text\":\"").append(J.esc(mr.text)).append('"');
        sb.append(",\"err_code\":").append(mr.errCode);
        sb.append(",\"err_desc\":\"").append(J.esc(mr.errDesc)).append('"');
        sb.append(",\"mic_opened\":").append(mr.micOpened);
        sb.append(",\"mic_retries\":").append(mr.micRetries);
        sb.append(",\"mic_reopens\":").append(mr.micReopens);
        sb.append(",\"mic_starved\":").append(mr.micStarved);
        sb.append(",\"read_fails\":").append(mr.readFails);
        sb.append(",\"read_rc\":").append(mr.readRc.json());
        sb.append(",\"audio_source\":").append(mr.audioSource);
        sb.append(",\"frames\":").append(mr.frames);
        sb.append(",\"chunks\":").append(mr.chunks);
        sb.append(",\"sent_bytes\":").append(mr.sentBytes);
        sb.append(",\"write_fails\":").append(s.writeFails);
        sb.append(",\"write_rc\":").append(s.rcJson());
        sb.append(",\"ready_wait_ms\":").append(s.readyWaitMs);
        sb.append(",\"audio_ms\":").append(mr.audioMs);
        sb.append(",\"loop_ms\":").append(mr.loopMs);
        sb.append(",\"dump\":\"").append(J.esc(mr.dumpPath)).append('"');
        sb.append(",\"rms_max\":").append(mr.rmsMax);
        sb.append(",\"rms_floor\":").append(mr.rmsFloor);
        sb.append(",\"thr\":").append(mr.thr);
        sb.append(",\"thr_sustain\":").append(mr.thrSustain);
        sb.append(",\"saw_speech\":").append(mr.sawSpeech);
        sb.append(",\"loud_at_ms\":").append(mr.loudAtMs);
        sb.append(",\"last_loud_ago_ms\":").append(mr.lastLoudAgoMs);
        sb.append(",\"rms_trace\":\"").append(J.esc(mr.rmsTrace)).append('"');
        sb.append(",\"saw_eos\":").append(mr.sawEos);
        sb.append(",\"saw_bos\":").append(mr.sawBos);
        sb.append(",\"quiet_suspect\":").append(mr.quietSuspect);
        sb.append(",\"onset_timeout\":").append(mr.onsetTimeout);
        sb.append(",\"preroll_frames\":").append(mr.preRollFrames);
        sb.append(",\"best_partial\":\"").append(J.esc(s.bestText)).append('"');
        sb.append(",\"max_ms\":").append(maxMs);
        sb.append(",\"silence_ms\":").append(silenceMs);
        sb.append(",\"vol_max\":").append(s.volMax);
        sb.append(",\"init_code\":").append(initCode);
        sb.append(",\"start_ret\":").append(s.startRet);
        sb.append(",\"readback\":\"").append(J.esc(s.readback)).append('"');
        sb.append(",\"ms\":").append(mr.ms);
        sb.append(",\"note\":\"").append(J.esc(mr.note)).append('"');
        sb.append(",\"error\":\"").append(J.esc(error)).append('"');
        sb.append(",\"events\":[");
        for (int i = 0; i < s.ev.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(J.esc(s.ev.get(i))).append('"');
        }
        sb.append("]}");
        mr.json = sb.toString();
        lastSummary = mr.json;
        BootLog.i("在线听写 ★麦克风结果 " + mr.json);
        return mr;
    }

    /** 开麦：失败重试（刚让出麦克风的引擎需要时间真正释放，这一段失败是竞态不是故障）。 */
    private android.media.AudioRecord openMic(MicResult mr, int audioSource) {
        int minBuf = android.media.AudioRecord.getMinBufferSize(16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf < 16000) minBuf = 16000;      // 至少 0.5s 的缓冲，防 underrun
        for (int attempt = 1; attempt <= 10; attempt++) {
            android.media.AudioRecord r = null;
            try {
                r = new android.media.AudioRecord(audioSource, 16000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf);
                if (r.getState() == android.media.AudioRecord.STATE_INITIALIZED) {
                    r.startRecording();
                    mr.micRetries = attempt - 1;
                    return r;
                }
                mr.note = "state=" + r.getState();
            } catch (Throwable t) {
                mr.note = chain(t);
            }
            try { if (r != null) r.release(); } catch (Throwable ignored) {}
            mr.micRetries = attempt;
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
        }
        return null;
    }

    private static void closeMic(android.media.AudioRecord rec) {
        try {
            if (rec == null) return;
            if (rec.getRecordingState() == android.media.AudioRecord.RECORDSTATE_RECORDING) rec.stop();
            rec.release();
        } catch (Throwable ignored) {}
    }

    /** 读满 want 字节（AudioRecord.read 会短读）。与 VoiceWakeController 同构。
     *
     * <p>v103：把 {@code read()} 的**原始返回码**记进 {@code mr.readRc} ——
     * "读不到数据"和"没人说话"在结果上长得一样，原始返回码是唯一能把它们分开的东西。</p>
     */
    private static int readFully(android.media.AudioRecord rec, byte[] buf, int want,
                                 MicResult mr) {
        int off = 0, fails = 0;
        while (off < want) {
            int n = rec.read(buf, off, want - off);
            if (n <= 0) {
                if (mr != null) mr.readRc.rc(n);
                if (++fails > 30) break;
                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                continue;
            }
            fails = 0;
            off += n;
        }
        return off;
    }

    /** 极简返回码直方图（读数/写数各一份，出问题时不靠猜）。 */
    static final class Hist {
        private final java.util.Map<Integer, Integer> m = new java.util.LinkedHashMap<Integer, Integer>();
        synchronized void rc(int code) {
            Integer n = m.get(Integer.valueOf(code));
            m.put(Integer.valueOf(code), Integer.valueOf(n == null ? 1 : n.intValue() + 1));
        }
        synchronized Integer first() {
            for (Integer k : m.keySet()) return k;
            return Integer.valueOf(0);
        }
        synchronized String json() {
            StringBuilder sb = new StringBuilder("{");
            boolean f = true;
            for (java.util.Map.Entry<Integer, Integer> e : m.entrySet()) {
                if (!f) sb.append(',');
                sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
                f = false;
            }
            return sb.append('}').toString();
        }
    }

    /** 16bit 小端 PCM 的 RMS —— 判"有人说话"的<b>自持</b>判据，不依赖 SDK 回调。 */
    private static long rms16(byte[] b, int len) {
        long sum = 0;
        int n = 0;
        for (int i = 0; i + 1 < len; i += 2) {
            int v = (short) ((b[i] & 0xff) | (b[i + 1] << 8));
            sum += (long) v * v;
            n++;
        }
        if (n == 0) return 0;
        return (long) Math.sqrt((double) sum / n);
    }

    /** 只建不跑：验证授权/初始化是否放行（不消耗识别额度）。 */
    public String initOnly() {
        String er = ensureRecognizer();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":").append(er.isEmpty() && initCode == 0);
        sb.append(",\"appid\":\"").append(APPID).append('"');
        sb.append(",\"init_string\":\"").append(J.esc(INIT_STRING)).append('"');
        sb.append(",\"util_ready\":").append(utilReady);
        sb.append(",\"util_err\":\"").append(J.esc(utilErr)).append('"');
        sb.append(",\"init_code\":").append(initCode);
        sb.append(",\"error\":\"").append(J.esc(er)).append('"');
        sb.append('}');
        return sb.toString();
    }

    /**
     * 当前状态（/status 用）。
     *
     * @param onlineOn 对话链路当前是不是"在线优先"（由 VoiceWakeController 持有）
     */
    public String statusJson(boolean onlineOn) {
        return "{\"appid\":\"" + APPID + "\",\"util_ready\":" + utilReady
                + ",\"init_code\":" + initCode
                + ",\"recognizer\":" + (recognizer != null)
                + ",\"busy\":" + busy
                + ",\"busy_what\":\"" + J.esc(busyWhat) + "\""
                + ",\"online_on\":" + onlineOn
                + ",\"reset_n\":" + resetN
                + ",\"reset_last\":\"" + J.esc(resetLast) + "\""
                + ",\"gate_n\":" + gateN
                + ",\"gate_hit\":" + gateHit
                + ",\"gate_last\":\"" + J.esc(gateLast) + "\""
                + ",\"quiet_last\":\"" + J.esc(quietLast) + "\""
                + ",\"last_ok\":" + lastSummary.contains("\"ok\":true") + "}";
    }

    /** 兼容旧调用（只有 /status 用，保留一个无参重载免得别处编译不过）。 */
    String statusJson() { return statusJson(false); }

    String lastSummary() { return lastSummary; }

    // ------------------------------------------------------------------ 内部

    /** 一轮会话的共享状态；listener 回调在工作线程，主线程只发起。 */
    private static final class Session {
        final List<String> partials = new ArrayList<String>();
        /** 回调流水（有界）：出问题时不用翻设备日志就能看出卡在哪一步。 */
        final List<String> ev = new ArrayList<String>();
        final CountDownLatch done = new CountDownLatch(1);
        final StringBuilder params = new StringBuilder();
        volatile String text = "";
        volatile String errDesc = "";
        volatile int errCode;
        volatile int volMax;
        volatile int chunks;
        volatile int startRet = -999;
        /**
         * writeAudio 返回码直方图 —— <b>本轮最关键的仪表</b>。
         *
         * <p>依据（反汇编 {@code thirdparty.ao.a([BII)I} 与 {@code thirdparty.p.a([BII)I}）：</p>
         * <pre>
         *   if (this.d == null)          return 21004;   // 还没有会话
         *   if (buf null / 长度不足)      return 10109;
         *   if (p.a() != -1)             return 10106;   // ★ audio_source 不是 -1
         *   return p.a(buf,off,len);                     // 真写 → 0 表示成功
         * </pre>
         * 所以 <b>0 = 成功，非 0 = 错误码</b>。我第一版把它当成"0=忙、非0=成功"，
         * 结果 57 块全被 10106 吞掉、计数器却显示"已灌 57 块"，服务端只能回
         * 10118「您好像没有说话哦」—— 典型的"计数器在涨、东西没进去"。</p>
         */
        final java.util.Map<Integer, Integer> rcHist = new java.util.LinkedHashMap<Integer, Integer>();
        volatile int writeOk;
        volatile int writeFails;
        volatile int readyWaitMs;
        volatile int lastRc;
        /** 这一轮是不是麦克风模式（文件模式=false）。 */
        volatile boolean micMode;
        /** 我方 RMS 判据认为"听到人声了"。（与 SDK 的 onVolumeChanged 无关，是独立的一路。） */
        volatile boolean sawLoud;
        /**
         * ★★ 本轮识别到的<b>最长</b>文本 —— 终态结果只用来"定稿"，不许用来覆盖。
         *
         * <p>踩过（v100 现场，事件流水是铁证）：麦克风模式下引擎<b>自己会判句尾</b>，
         * 于是出现<b>两段</b>结果：</p>
         * <pre>
         *   onResult last=false [我想吃苹果]     ← 真正的识别在这里
         *   onEndOfSpeech
         *   onEvent a0=20001
         *   onResult last=true  [。]            ← 收尾时对"端点之后那段静音"的终态
         * </pre>
         * <p>我原来无条件把 {@code last=true} 那条写进 {@code s.text}，于是<b>每条都只剩一个句号</b>
         * （4 条用例 3 条如此），而事件的 {@code last=false} 那行明明逐字正确。
         * 这类"后到的那条把先到的有效结果盖掉"是本项目第 N 次遇到同一族问题
         * （参见 silent-failure-forensics 的"终态覆盖"一类）⇒ 判据改成<b>取最长</b>，
         * 并把这个中间结果原样留在 {@code best_partial} 里给双端日志查账。</p>
         */
        volatile String bestText = "";
        /** 引擎是否自己报过 onEndOfSpeech（有它就不必再灌静音）。 */
        volatile boolean sawEos;
        /**
         * ★ 引擎<b>自己</b>是否报过 onBeginOfSpeech —— "它到底有没有开始听"的唯一凭据。
         *
         * <p>为什么必须单列一个字段：{@code onBeginOfSpeech} 原来只打了一行日志、没存下来，
         * 于是"我们本地 VAD 明明判到人声、也把 164 块灌进去了，云端却回
         * <b>10118 您好像没有说话哦</b>"这种情形<b>无法与真正的静音区分</b>。</p>
         *
         * <p>v121 真机现场（`_iltest.py --text 今天天气怎么样`）：
         * <pre>
         *   麦克风 opened=True frames=188/100 chunks=164/100 sent=209920B/128000B
         *         write_rc={'0':164}          ← 每次 writeAudio 都返 0（＝成功）
         *   声学 rms_max=2589 底噪=880 起音阈值=1200 saw_speech=True
         *   云端 err=10118 desc=您好像没有说话哦   vol_max=0
         * </pre>
         * 即"每块都写成功了，服务端一个音都没听见" ⇒ 返 0 <b>不等于送达</b>。</p>
         */
        volatile boolean sawBos;
        /** 粗粒度 RMS 包络（每秒一个峰值）—— 用来一眼看出"声音是不是连续的"。 */
        final StringBuilder rmsTrace = new StringBuilder();
        int traceMax, traceSec;
        /** setParameter 之后用 getParameter 回读的结果 —— 确认参数真的落进库里了。 */
        volatile String readback = "";

        synchronized void rc(int code) {
            lastRc = code;
            Integer n = rcHist.get(Integer.valueOf(code));
            rcHist.put(Integer.valueOf(code), Integer.valueOf(n == null ? 1 : n.intValue() + 1));
        }

        String rcJson() {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            synchronized (this) {
                for (java.util.Map.Entry<Integer, Integer> e : rcHist.entrySet()) {
                    if (!first) sb.append(',');
                    sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
                    first = false;
                }
            }
            return sb.append('}').toString();
        }

        synchronized void add(String s) {
            if (ev.size() < 60) ev.add(s);
        }
    }

    private void setP(Object r, String k, String v) throws Exception {
        Method m = r.getClass().getMethod("setParameter", String.class, String.class);
        m.invoke(r, k, v);
    }

    /**
     * 一次性设置听写参数。
     *
     * <p>键名全部抄自 {@code SpeechConstant} 字段值（不是猜的）。</p>
     *
     * <p><b>为什么写成"两个都必须设"：</b>{@code audio_source} 决定会话跑麦克风还是音频流，
     * 而 {@code SpeechRecognizer.writeAudio} 的实现是
     * {@code if (p.a() != -1) return 10106;} —— 那个 {@code -1} 正是
     * {@code Integer.parseInt(map.get("audio_source"))}（反汇编 {@code thirdparty.ag.a}）。
     * 所以只要这个键没落进参数表，<b>每一帧都会被 10106 挡掉，且返回值容易被误读成"已写入"</b>。</p>
     */
    private void setParams(Object r, StringBuilder log, String extra) throws Exception {
        String[][] kv = new String[][]{
                {"engine_type", "cloud"},        // 在线（离线是 local）
                {"domain", "iat"},               // 语音听写
                {"language", "zh_cn"},
                {"accent", "mandarin"},
                {"sample_rate", "16000"},
                {"result_type", "plain"},        // 直接给纯文本，省得再解 JSON
                {"timeout", "12000"},            // 网络超时(ms)
                // ★ audio_source = -1：音频流模式（不自己开麦，由 writeAudio 灌）。
                //   writeAudio 的守卫就是拿它跟 -1 比，见上面注释。
                {"audio_source", "-1"},
                // ★ vad_enable=false：手动收尾，不让它靠 VAD 断句，
                //   否则"文件读完但 VAD 没判到端点"会变成随机失败。
                {"vad_enable", "false"},
        };
        for (String[] p : kv) {
            setP(r, p[0], p[1]);
            log.append(p[0]).append('=').append(p[1]).append(' ');
        }
        // 双保险：MSC 的会话对象读的是全局参数表（反汇编 p.c 里走的是 p.v() 拿到的
        // 那个共享 ag），所以在 SpeechUtility 上也设一遍，避免"识别器级设了、会话级没读到"。
        try {
            Class<?> su = Class.forName(SU_CLS);
            Object u = su.getMethod("getUtility").invoke(null);
            if (u != null) {
                Method sp = su.getMethod("setParameter", String.class, String.class);
                for (String[] p : kv) sp.invoke(u, p[0], p[1]);
                log.append("(已同步 SpeechUtility) ");
            }
        } catch (Throwable t) {
            log.append("util_set_err=").append(chain(t)).append(' ');
        }
        if (extra != null && extra.trim().length() > 0) {
            try {
                JSONObject o = new JSONObject(extra);
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    Object v = o.get(k);
                    setP(r, k, String.valueOf(v));
                    log.append(k).append('=').append(v).append("(extra) ");
                }
            } catch (Throwable t) {
                log.append("extra_error=").append(chain(t)).append(' ');
            }
        }
    }

    private Object makeListener(final Session s) throws Exception {
        Class<?> rl = Class.forName(RL_CLS);
        return Proxy.newProxyInstance(rl.getClassLoader(), new Class<?>[]{rl},
                new InvocationHandler() {
                    @Override public Object invoke(Object p, Method m, Object[] a) {
                        Object g = Proxies.guard(p, m, a);
                        if (g != Proxies.UNHANDLED) return g;
                        String n = m.getName();
                        int argc = a == null ? 0 : a.length;
                        try {
                            if ("onResult".equals(n)) {
                                Object res = argc > 0 ? a[0] : null;
                                boolean last = argc > 1 && Boolean.TRUE.equals(a[1]);
                                String t = "";
                                if (res != null) {
                                    Method gr = res.getClass().getMethod("getResultString");
                                    Object o = gr.invoke(res);
                                    t = o == null ? "" : String.valueOf(o);
                                }
                                s.add("onResult last=" + last + " [" + t + "]");
                                // ★ 一律参与"取最长"：终态那条常常只是收尾的句号，
                                //   绝不能让它盖掉前面逐字正确的中间结果（v100 现场）。
                                if (t != null && t.trim().length() > s.bestText.trim().length()) {
                                    s.bestText = t.trim();
                                }
                                if (!last) {
                                    if (t != null && t.length() > 0 && s.partials.size() < 40) {
                                        s.partials.add(t);
                                    }
                                } else {
                                    s.text = s.bestText;      // 定稿用最长的那条
                                    s.done.countDown();
                                }
                                BootLog.i("在线听写 onResult last=" + last + " [" + t + "]");
                            } else if ("onError".equals(n)) {
                                Object e = argc > 0 ? a[0] : null;
                                if (e != null) {
                                    try {
                                        Method gc = e.getClass().getMethod("getErrorCode");
                                        Object c = gc.invoke(e);
                                        s.errCode = (c instanceof Integer) ? ((Integer) c).intValue() : -1;
                                    } catch (Throwable ignored) {}
                                    try {
                                        Method gd = e.getClass().getMethod("getPlainDescription", boolean.class);
                                        Object d = gd.invoke(e, Boolean.TRUE);
                                        s.errDesc = d == null ? "" : String.valueOf(d);
                                    } catch (Throwable ignored) {}
                                }
                                s.add("onError " + s.errCode + " " + s.errDesc);
                                s.text = s.bestText;     // 出错了也不该丢掉已经拿到的识别
                                s.done.countDown();
                                BootLog.w("在线听写 onError " + s.errCode + " " + s.errDesc);
                            } else if ("onVolumeChanged".equals(n)) {
                                if (argc > 0 && a[0] instanceof Integer) {
                                    int v = ((Integer) a[0]).intValue();
                                    if (v > s.volMax) s.volMax = v;
                                }
                            } else if ("onBeginOfSpeech".equals(n)) {
                                s.add("onBeginOfSpeech");
                                BootLog.i("在线听写 onBeginOfSpeech");
                            } else if ("onEndOfSpeech".equals(n)) {
                                s.sawEos = true;
                                s.add("onEndOfSpeech");
                                BootLog.i("在线听写 onEndOfSpeech");
                            } else if ("onEvent".equals(n)) {
                                s.add("onEvent a0=" + (argc > 0 ? String.valueOf(a[0]) : "")
                                        + " a1=" + (argc > 1 ? String.valueOf(a[1]) : ""));
                                BootLog.i("在线听写 onEvent a0=" + (argc > 0 ? String.valueOf(a[0]) : "")
                                        + " a1=" + (argc > 1 ? String.valueOf(a[1]) : ""));
                            }
                        } catch (Throwable t) {
                            BootLog.w("在线听写 listener 异常: " + chain(t));
                        }
                        return Proxies.nil(m);
                    }
                });
    }

    /**
     * 读音频 → 裸 PCM。
     *
     * @return {byte[] pcm, String warn}
     */
    private static Object[] readPcm(String path) throws Exception {
        File f = new File(path);
        if (!f.exists()) throw new Exception("文件不存在: " + path);
        byte[] d = new byte[(int) Math.min(f.length(), MAX_BYTES + 64)];
        InputStream in = new FileInputStream(f);
        int read = 0;
        try {
            while (read < d.length) {
                int n = in.read(d, read, d.length - read);
                if (n <= 0) break;
                read += n;
            }
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
        byte[] all = new byte[read];
        System.arraycopy(d, 0, all, 0, read);

        String warn = "";
        if (all.length > 12 && all[0] == 'R' && all[1] == 'I' && all[2] == 'F' && all[3] == 'F') {
            int rate = 16000, ch = 1, bits = 16;
            int i = 12;
            byte[] data = null;
            while (i + 8 <= all.length) {
                String cid = new String(all, i, 4, "US-ASCII");
                int sz = (all[i + 4] & 0xff) | ((all[i + 5] & 0xff) << 8)
                        | ((all[i + 6] & 0xff) << 16) | ((all[i + 7] & 0xff) << 24);
                if (sz < 0 || i + 8 + sz > all.length) sz = all.length - i - 8;
                if ("fmt ".equals(cid)) {
                    ch = (all[i + 10] & 0xff) | ((all[i + 11] & 0xff) << 8);
                    rate = (all[i + 12] & 0xff) | ((all[i + 13] & 0xff) << 8)
                            | ((all[i + 14] & 0xff) << 16) | ((all[i + 15] & 0xff) << 24);
                    bits = (all[i + 22] & 0xff) | ((all[i + 23] & 0xff) << 8);
                } else if ("data".equals(cid)) {
                    data = new byte[sz];
                    System.arraycopy(all, i + 8, data, 0, sz);
                    break;
                }
                i += 8 + sz + (sz & 1);
            }
            if (data == null) throw new Exception("wav 里找不到 data 块");
            if (rate != 16000 || ch != 1 || bits != 16) {
                warn = "wav 规格 " + rate + "Hz/" + ch + "ch/" + bits + "bit，"
                        + "讯飞要求 16000/1/16（未做重采样，识别可能失败）";
            }
            all = data;
        }
        if (all.length > MAX_BYTES) {
            byte[] cut = new byte[MAX_BYTES];
            System.arraycopy(all, 0, cut, 0, MAX_BYTES);
            all = cut;
            warn = (warn.isEmpty() ? "" : warn + "; ") + "音频超 30s，已截断";
        }
        return new Object[]{all, warn};
    }

    /** 反射调用的异常必须展开 cause 链，否则只剩一个没有信息量的类名。 */
    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        while (t != null && depth < 6) {
            if (depth > 0) sb.append(" <= ");
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            t = t.getCause();
            depth++;
        }
        return sb.toString();
    }
}
