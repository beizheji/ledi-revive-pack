package com.ledi.agent.voice;

import com.ledi.agent.core.BootLog;
import com.ledi.agent.net.HttpServer;
import com.ledi.agent.core.J;
import com.ledi.agent.core.MainThread;
import com.ledi.agent.net.Proxies;

import android.content.Context;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

import org.json.JSONObject;

/**
 * 离线语音识别（ASR）探测器 —— 驱动百度 {@code EventManager("asr")}。
 *
 * <h3>为什么需要一个"探测器"而不是直接接对话</h3>
 * Agent 之前只有「唤醒 → 拿固定文案问 AI → 播报」，<b>完全没有把用户说的话变成文本</b>，
 * 所以「端到端语音识别与对话通路」是断的。离线 ASR 这条路能不能通，取决于几个
 * 目前无法离线确定的事实（native 认不认自造语法、授权是否同样被
 * {@code SetLicenseFlag(true)} 短路、VAD 端点是否正常收尾），
 * 所以先做一个只读、可反复调用的探测器，用最小代价在真机上拿到数据。
 *
 * <h3>依据（反汇编 com.baidu.speech.EventManagerAsr.handleAsrStart）</h3>
 * <pre>
 *   mUsingAsrStartParams = params;                 // 非空即 "asr busy!"
 *   if (params.optInt("vad.endpoint-timeout", -1) == 0) mLongSpeech = true;
 *   readyParamsForAsrStart(params);                // 内部走授权（SetLicenseFlag 可短路）
 *   mic.start                                       // ★ 引擎自己开麦，不用我们喂 PCM
 *   if (params.has("grammar")) {
 *       decoder = params.optInt("basic.decoder", params.optInt("decoder", 3));
 *       ... EventManagerDec(this.context, "dec") → dec.start
 *       JSONObject g = context.loadJsonFromUri(params.optString("grammar"), false, true);
 *       if (g == null) g = context.loadJsonFromUri(params.optString("grammar"), false, false);
 *       JSONObject slot = params.optJSONObject("slot-data") ...
 *       // 异步构建 NLU 特征
 *   }
 * </pre>
 * 关键结论：<b>{@code grammar} 是一个 URI，会被当 JSON 读</b>——这正好解释了为什么
 * {@code assets/baidu_speech_grammar.bsg} 是「URL 编码的 JSON」（%7B%0A…）。
 *
 * <p>事件名（从 DEX 常量池枚举）：{@code asr.begin / asr.ready / asr.enter /
 * asr.partial / asr.finish / asr.end / asr.exit / asr.volume / asr.status}；
 * 识别文本在 {@code asr.finish} 的 params 里，字段名 {@code results_recognition}
 * （见 {@code VoiceRecognitionService.parseFinishResult}）。</p>
 *
 * <p>本类只做「跑一轮、收事件、原样吐出来」，不做任何业务决策。</p>
 */
public final class AsrProbe {

    private static final String EMF_CLS = "com.baidu.speech.EventManagerFactory";
    private static final String EL_CLS  = "com.baidu.speech.EventListener";
    private static final String APP_ID  = "9528412";

    /** native 声学模型，与唤醒引擎共用同一个 s1 通用普通话音学模型。 */
    static final String RES_NAME = "libbd_easr_s1_merge_normal_20151216.dat.so";
    /** 离线命令词语法（URL 编码的 JSON），词库可直接改这个文件。 */
    static final String GRAMMAR_ASSET = "assets:///baidu_speech_grammar.bsg";

    /**
     * VAD 档位 —— 合法取值只有<b>八个</b>，其余一律当场抛异常。
     *
     * <p>依据（反汇编 {@code com.baidu.speech.EventManagerAsr} 里那张 vad switch，
     * 逐分支读完）：</p>
     * <pre>
     *   String v = params.optString("basic.vad", params.optString("vad", "model-vad"));
     *   if ("touch".equalsIgnoreCase(v))                              → EventManagerVadEmpty
     *   else if ("model_vad".equalsIgnoreCase(v)
     *         || "model-vad".equalsIgnoreCase(v))                     → EventManagerVadModule
     *   else if ("mfe".equalsIgnoreCase(v))                           → EventManagerVadMfe
     *   else if (isEmpty(v) || "null" || "search" || "input"
     *         || "model_vad" || "model-vad")                         → EventManagerVadModule
     *   else throw new AndroidRuntimeException("bad vad: " + v);      // ★ 只报这一个错
     * </pre>
     * 即合法集合 = { touch, model_vad, model-vad, mfe, "", "null", search, input }。
     *
     * <p><b>踩过（v78 真机）</b>：这里原本写死 {@code "dnn"}，依据是另一处
     * {@code paramBuilder} 里的 {@code optString("vad","dnn")}。结果引擎起来了、
     * 事件也来了，却在 {@code asr.finish} 里回一个
     * {@code {"error":7,"desc":"android.util.AndroidRuntimeException: bad vad: dnn"}}
     * —— 错误藏在识别结果里而不是调用栈里，光看 {@code /asrtest} 的顶层
     * {@code error} 字段（空）根本发现不了。</p>
     *
     * <p>现在取 SDK 自己的默认值 {@code model-vad}（等价于给空串，也是
     * {@code VoiceDialog} 一直在用、明确能跑通的档位）。</p>
     */
    static final String VAD_DEFAULT = "model-vad";

    /**
     * 免麦克风的确定性测试通道 —— 参数键是 <b>{@code infile}</b>（一个字符串路径），
     * 不是 {@code audio.file}；{@code audio.source} 是 <b>int</b>（默认 1），
     * 并且<b>只在麦克风分支里被用到</b>，给文件时传它没有任何作用。
     *
     * <p>依据（反汇编 {@code com.baidu.speech.EventManagerMic.createMicrophoneInputStream}，
     * 逐指令读完）：</p>
     * <pre>
     *   String infile = p.optString("infile");
     *   String m      = p.optString("audio.mills");
     *   if (!TextUtils.isEmpty(m)) mills = Long.valueOf(Long.parseLong(m));  // ★ 非数字直接 NFE
     *   readFile = false;
     *   int src = p.optInt("audio.source", 1);          // ★ int，只给麦克风用
     *   if (infile == null || "".equals(infile) || "null".equals(infile)) {
     *       new MicrophoneInputStream(src, sample) ...  // 麦克风分支
     *   }
     *   // 文件分支（按前缀分流）：
     *   if (infile.startsWith("res://"))     getClass().getResourceAsStream("/" + 去掉前缀)
     *   else if (startsWith("asset://"))     getClass().getResourceAsStream("/assets/" + 去掉前缀)
     *   else if (startsWith("#"))            反射 ^#(类)[#.](方法)\(\) → invoke(null) 取 InputStream
     *   else { new FileInputStream(infile); readFile = true; }   // ★ 裸路径 = 直接当文件读
     * </pre>
     *
     * <p><b>踩过</b>：曾以为键名是 {@code audio.source:"infile"} + {@code audio.file}，
     * 结果两个键都不对 → 既没进文件分支，还把异常包成
     * {@code InvocationTargetException} 丢掉了真因（见 {@link #chain} 的修复）。
     * 正确用法就一句：{@code {"infile":"/sdcard/xxx.wav"}}。</p>
     */
    static final String INFILE_KEY = "infile";

    /**
     * 文件模式下引擎期望的音频：<b>16k / 16bit / 单声道裸 PCM</b>。
     * 因为拿到的是 {@code FileInputStream}，没有任何容器解析环节。
     *
     * <p><b>但别把"带不带 WAV 头"当成诊断依据</b>（v79 实测，两边都能过）：
     * 一个 38~44 字节的 RIFF 头按 16k/16bit 算只有 <b>约 1 毫秒</b>，
     * 对解码器就是开头一丁点噪声，VAD 和声学模型都不受影响。
     * 之所以给出裸 PCM，是为了"少一个变量"，不是因为它能决定成败 ——
     * 真正决定成败的是收尾时序（见 {@code probe()} 里的说明）。</p>
     */
    static final int INFILE_RATE = 16000;
    static final int INFILE_BITS = 16;
    static final int INFILE_CH   = 1;

    private final Context ctx;
    private final Runnable licenseHook;   // 由 VoiceWakeController 注入：SetLicenseFlag(true)
    private final String licPath;
    private final String resFile;

    /**
     * 投递到主线程后等回执的超时。
     * asr.start 里含授权校验 + native 初始化 + 语法文件加载，比普通 View 操作重得多，
     * 所以给得比 {@link MainThread} 默认的 4s 宽。
     */
    private static final long MAIN_CALL_TIMEOUT_MS = 12000L;

    /**
     * 发完 {@code asr.stop} 后等 {@code asr.finish} 的上限。
     *
     * <p>比 {@code VoiceDialog.FINISH_GRACE_MS}（900ms）宽得多，因为那边是
     * "VAD 已经判定说完了、finish 马上就到"；这边是文件读完/时间到被我们主动停的，
     * 解码器要把整段话收尾，线上识别还可能走网络往返，给足余量才不会被误判成失败。</p>
     */
    private static final long FINISH_GRACE_MS = 6000L;

    private Object engine;
    /** 与 {@link #engine} 同一个对象，但由主线程侧赋值，供收尾调用使用。 */
    private volatile Object probeEngine;
    private Class<?> elCls;

    /** 事件流水的锁：onEvent 落在主线程，而 probe() 的汇总在工作线程，两边都会碰这几个容器。 */
    private final Object evLock = new Object();

    /** 本轮事件流水（有界，防刷爆）。 */
    private final ArrayDeque<String> events = new ArrayDeque<String>();
    private final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
    private volatile int eventTotal;
    private volatile String lastFinish = "";
    private volatile String lastPartial = "";
    private volatile String lastExit = "";
    /** 本场 {@code asr.volume} 的峰值（见 handleEvent 里的说明）。 */
    private volatile int volMax;

    AsrProbe(Context ctx, Runnable licenseHook, String licPath, String resFile) {
        this.ctx = ctx;
        this.licenseHook = licenseHook;
        this.licPath = licPath;
        this.resFile = resFile;
    }

    /** 当前是否还在收事件（给 status 用）。 */
    volatile boolean busy;

    /**
     * 跑一轮离线识别：起引擎 → 自己开麦听 {@code seconds} 秒 → 停 → 汇总事件。
     *
     * @param seconds  监听时长（秒），1..20
     * @param grammar  语法 URI；空则用 {@link #GRAMMAR_ASSET}
     * @param extra    额外参数 JSON（可覆盖 vad/decoder/nlu 等），可为空
     * @return 单行 JSON 诊断结果
     */
    synchronized String probe(int seconds, String grammar, String extra) {
        return probe(seconds, grammar, extra, null);
    }

    /**
     * 同上，但可以走<b>文件输入</b>做确定性测试（不开麦）。
     *
     * @param infile 音频文件裸路径（如 {@code /sdcard/x.pcm}）。
     *               非空时引擎从文件读 PCM 而不是开麦，于是「麦克风/环境噪声」这一整类
     *               变量被锁死，只剩「解码 + 语法 + 授权」三个变量。
     *               传 {@code null} / 空串 / {@code "null"} 都等于「不开文件，用麦克风」。
     */
    synchronized String probe(int seconds, String grammar, String extra, String infile) {
        if (seconds <= 0 || seconds > 20) seconds = 8;
        // ★ v215：显式传 "free"/"none"/"off" ⇒ 自由识别（grammar 给空串），
        //   与 VoiceDialog 的 grammarOverride="" 完全同义（那条路是明确跑通的）。
        //   历史坑：这里原本只要空串就强制换成 bsg，于是"离线自由识别"这一档
        //   从来测不到 —— 而 #7「No recognition result matched」本来只是
        //   "这句话不在 53 条命令词里"，却被误读成了"离线引擎哑"。
        String g0 = grammar == null ? "" : grammar.trim();
        boolean freeMode = "free".equalsIgnoreCase(g0) || "none".equalsIgnoreCase(g0)
                || "off".equalsIgnoreCase(g0);
        if (!freeMode && g0.isEmpty()) g0 = GRAMMAR_ASSET;
        String inf = infile == null ? "" : infile.trim();
        if ("null".equalsIgnoreCase(inf)) inf = "";

        String g = freeMode ? "" : g0;
        String x = extra == null ? "" : extra;
        synchronized (evLock) {
            events.clear();
            counts.clear();
            eventTotal = 0;
            lastFinish = lastPartial = lastExit = "";
            volMax = 0;
        }
        String err = "";

        try {
            busy = true;

            final JSONObject p = new JSONObject();
            p.put("appid", APP_ID);
            p.put("license-file-path", licPath);
            p.put("grammar", g);
            p.put("asr-base-file-path", resFile);
            p.put("vad", VAD_DEFAULT);       // ★ 必须用 SDK 认识的取值，见 VAD_DEFAULT 注释
            p.put("decoder", 3);
            p.put("nlu", "disable");
            p.put("sound_start", -1);
            // ★ 文件输入（见 INFILE_KEY 注释）：给了路径就整条链路不开麦。
            //   extra 在下面覆盖，所以调用方仍可用 extra 手动顶掉这个键。
            if (inf.length() > 0) p.put(INFILE_KEY, inf);
            if (x.length() > 0) {
                try {
                    JSONObject ov = new JSONObject(x);
                    java.util.Iterator<String> it = ov.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        p.put(k, ov.get(k));
                    }
                } catch (Throwable t) {
                    err = "extra 解析失败: " + chain(t);
                }
            }

            BootLog.i("ASR 探测 asr.start params=" + p);
            // ★★ 授权 + 建引擎 + asr.start **必须一起投到主线程**。
            //
            // 依据（反汇编 EventManagerAsrWrapper，逐指令读到）：
            //   两个方法（registerListener / send）开头都有一模一样的断言 ——
            //     if (Looper.getMainLooper() != Looper.myLooper())
            //         throw new AndroidRuntimeException("bad thread, need main thread, " + name + ", " + params);
            //   而 HttpServer 的请求跑在没有 Looper 的裸线程上，直接调必然抛。
            //   （这条断言在 EventManagerAsrWrapper 里，而 create() 返回的正是它。）
            //
            // 踩过：一开始只把 asr.start 投到主线程，结果还是同一个错 —— 因为
            //   ensureEngine() 里的 registerListener 先撞上了断言。断言是逐方法的，
            //   凡是碰 SDK 的调用都要过主线程，不能只挑看起来"像开麦"的那一个。
            MainThread.call(new java.util.concurrent.Callable<Void>() {
                @Override public Void call() throws Exception {
                    if (licenseHook != null) licenseHook.run();   // ★ 必须在 asr.start 之前
                    Object em = ensureEngine();                   // 内部 registerListener 同样断言主线程
                    probeEngine = em;
                    if (em == null) throw new Exception("EventManager(\"asr\") 创建失败");
                    send(em, "asr.start", p.toString());
                    return null;
                }
            }, MAIN_CALL_TIMEOUT_MS);

            long until = System.currentTimeMillis() + seconds * 1000L;
            while (System.currentTimeMillis() < until) {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }

            // ---- 收尾：必须"发 stop → 等 finish 真的到"，不能靠固定 sleep ----
            //
            // 踩过（v79 真机，症状极像"音频格式不对"）：这里原本是
            //   send(asr.stop) → sleep 1500 → send(asr.cancel)
            // 于是解码还没吐 asr.finish 就被 asr.cancel 掐掉了 ——
            // 表现是 events 里有 asr.begin/asr.end 却**永远没有 asr.finish**，
            // text 空、error 也是空，看着像"没听清"。更糟的是 cancel 之后引擎
            // 处于半死状态，下一轮 asr.start 只回一个 asr.exit 就结束了，
            // 于是同一份音频"第一次通过、后面几次随机失败"，很容易被误判成
            // "格式要求是 WAV 还是 PCM"这种根本不存在的差异。
            //
            // 正确契约照抄 VoiceDialog.stop()（那条路是明确跑通的）：
            //   asr.stop → 轮询等 finish（最多 FINISH_GRACE_MS），**不主动 cancel**。
            sendOnMain("asr.stop");
            boolean gotFinish = waitFinish(FINISH_GRACE_MS);
            if (!gotFinish) {
                // 只有真的什么都没等到才用 cancel 清场，否则会把正常解码掐死。
                BootLog.w("ASR 探测 " + FINISH_GRACE_MS + "ms 内没有 asr.finish，发 asr.cancel 清场");
                sendOnMain("asr.cancel");
            }
            // 让引擎把 exit 走完再返回，免得下一轮撞上没收干净的会话
            try { Thread.sleep(600); } catch (InterruptedException ignored) {}
        } catch (Throwable t) {
            // ★ 这里曾经写的是 String.valueOf(t)，于是 asr.start 的失败永远只显示
            //   "java.lang.reflect.InvocationTargetException" —— 真因（比如
            //   FileNotFoundException / 参数类型不对）全被反射包皮吃掉，
            //   在真机上就是一盏标准的假绿灯。必须展开因果链。
            err = chain(t);
            BootLog.w("ASR 探测失败: " + err);
        } finally {
            busy = false;
        }

        StringBuilder sb = new StringBuilder();
        synchronized (evLock) {                 // ★ 汇总时可能还有事件在主线程往里写
            sb.append("{\"sec\":").append(seconds);
            sb.append(",\"events\":").append(eventTotal);
            sb.append(",\"infile\":\"").append(J.esc(inf)).append('"');
            sb.append(",\"grammar\":\"").append(J.esc(g)).append('"');
            sb.append(",\"counts\":").append(countsJson());
            sb.append(",\"finish\":\"").append(J.esc(lastFinish)).append('"');
            // ★ 把 finish 里的错误码/真因提到顶层。引擎的失败信息藏在这里，
            //   不看这两个字段就会把"识别失败"误读成"识别成功但没听清"。
            sb.append(",\"finish_error\":").append(finishError());
            sb.append(",\"finish_desc\":\"").append(J.esc(finishDesc())).append('"');
            sb.append(",\"vol_max\":").append(volMax);
            sb.append(",\"text\":\"").append(J.esc(lastText())).append('"');
            sb.append(",\"partial\":\"").append(J.esc(lastPartial)).append('"');
            sb.append(",\"exit\":\"").append(J.esc(lastExit)).append('"');
            sb.append(",\"error\":\"").append(J.esc(err)).append('"');
            sb.append(",\"log\":").append(ringJson());
            sb.append('}');
        }
        return sb.toString();
    }

    /** 释放引擎（换词表/退出时用）。 */
    synchronized void release() {
        engine = null;
    }

    /**
     * 从最近一次 {@code asr.finish} 里取出第一句识别文本。
     *
     * <p>依据 {@code com.turing123.robotframe.internal.function.asr.RecogResult.parseJson}
     * 与 {@code com.baidu.speech.core.ASREngine} 的反汇编（铁证）：finish 的 params 是一个
     * JSON 对象，字段有
     * <pre>
     *   error                int    0 = 成功
     *   sub_error            int
     *   desc                 string
     *   result_type          string
     *   origin_result        string（内层 JSON 串）
     *   results_recognition  JSONArray&lt;String&gt;   ★ 识别候选，第 0 个是最优
     * </pre>
     * 取不到或 {@code error != 0} 一律返回空串，绝不编造。</p>
     */
    String lastText() {
        String p = lastFinish;
        if (p == null || p.length() == 0) return "";
        try {
            JSONObject o = new JSONObject(p);
            if (o.optInt("error", 0) != 0) return "";
            org.json.JSONArray a = o.optJSONArray("results_recognition");
            if (a != null && a.length() > 0) {
                String s = a.getString(0);
                return s == null ? "" : s.trim();
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 最近一次 asr.finish 的原始 params（诊断用，HTTP 直接吐）。 */
    String lastFinishParams() { return lastFinish; }

    /**
     * 最近一次 {@code asr.finish} 里的 {@code error} 码；没有 finish 返回 -1。
     *
     * <p>为什么要单独提出来：识别失败时引擎<b>不会</b>抛异常，而是把错因写成
     * {@code {"error":7,"desc":"…bad vad: dnn"}} 塞在 finish 事件里。
     * 只看顶层 {@code error} 字段（永远是空）就会以为一切正常 —— 这正是
     * v78 那次 "events 有了、text 是空的、却不报错" 的原因。</p>
     */
    int finishError() {
        try {
            if (lastFinish == null || lastFinish.length() == 0) return -1;
            return new JSONObject(lastFinish).optInt("error", 0);
        } catch (Throwable ignored) { return -1; }
    }

    /** 最近一次 asr.finish 里的 desc（真因常在这里）。 */
    String finishDesc() {
        try {
            if (lastFinish == null || lastFinish.length() == 0) return "";
            String d = new JSONObject(lastFinish).optString("desc", "");
            return d == null ? "" : d;
        } catch (Throwable ignored) { return ""; }
    }

    /**
     * 把异常展开成 {@code A: msg <= B: msg <= …} 的可读串。
     *
     * <p>反射调用（{@code Method.invoke}）抛出的永远是
     * {@code InvocationTargetException}，真因在 {@code getCause()} 上。
     * 只要有一层包装没拆，真机上看到的就是一句毫无信息量的类名。</p>
     */
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

    private Object ensureEngine() throws Exception {
        if (engine != null) return engine;
        Class<?> emf = Class.forName(EMF_CLS);
        Method create = emf.getMethod("create", Context.class, String.class);
        engine = create.invoke(null, ctx, "asr");
        Object listener = makeEventListener();
        Method reg = findMethod(engine, "registerListener");
        if (reg != null) reg.invoke(engine, listener);
        BootLog.i("ASR 探测 EventManager(\"asr\") 就绪");
        return engine;
    }

    private void send(Object em, String name, String params) throws Exception {
        Method m = findMethod(em, "send");
        if (m == null) throw new Exception("EventManager.send 不存在");
        m.invoke(em, name, params, null, 0, 0);
    }

    /**
     * 把一次 {@code send} 投到主线程执行 —— see {@link #probe} 里那段关于
     * {@code EventManagerAsrWrapper} 主线程断言的说明。收尾用的 stop/cancel 也一样。
     */
    private void sendOnMain(final String name) {
        final Object em = probeEngine != null ? probeEngine : engine;
        if (em == null) return;
        try {
            MainThread.call(new java.util.concurrent.Callable<Void>() {
                @Override public Void call() throws Exception {
                    send(em, name, "{}");
                    return null;
                }
            }, MAIN_CALL_TIMEOUT_MS);
        } catch (Throwable t) {
            // 收尾失败不改判定结论，但必须留痕（否则"最后一步没生效"又成了假绿灯）
            BootLog.w("ASR 探测 收尾 " + name + " 失败: " + chain(t));
        }
    }

    /** 轮询等 {@code asr.finish} 到达；等到返回 true，超时返回 false。 */
    private boolean waitFinish(long timeoutMs) {
        long until = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < until) {
            synchronized (evLock) {
                if (lastFinish != null && lastFinish.length() > 0) return true;
            }
            try { Thread.sleep(60); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    private static Method findMethod(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) return m;
            }
            for (Class<?> i : c.getInterfaces()) {
                for (Method m : i.getMethods()) {
                    if (m.getName().equals(name)) return m;
                }
            }
        }
        return null;
    }

    /**
     * 造一个 {@code com.baidu.speech.EventListener} 的动态代理。
     *
     * <p><b>v76 踩坑（代价：整个 {@code /asrtest} 从来没成功过一次）：</b>
     * {@code EventListener} 接口只有一个 {@code onEvent(String,String,[BII)V}，
     * 所以原先「不是 onEvent 就 return null」看着天经地义。但 SDK 内部把 listener
     * 放进了集合里，一调 {@code hashCode()} 就把 null 当返回值交出去，
     * 运行时要在调用点拆箱成 {@code int} →</p>
     * <pre>
     *   NullPointerException: Expected to unbox a 'int' primitive type but was returned null
     * </pre>
     * <p>再被 Proxy / Method.invoke 两层包装，日志里只剩
     * {@code InvocationTargetException} —— 接口一个字都没写错，功能却从没通过。</p>
     *
     * <p>现在统一走 {@link Proxies#guard} / {@link Proxies#nil}，两条铁律都收口在一处。</p>
     */
    private Object makeEventListener() throws Exception {
        elCls = Class.forName(EL_CLS);
        return Proxy.newProxyInstance(elCls.getClassLoader(), new Class<?>[]{elCls},
                new InvocationHandler() {
                    @Override public Object invoke(Object p, Method m, Object[] a) {
                        Object g = Proxies.guard(p, m, a);
                        if (g != Proxies.UNHANDLED) return g;
                        if ("onEvent".equals(m.getName())) {
                            int argc = a == null ? 0 : a.length;
                            String name = (argc > 0 && a[0] != null) ? String.valueOf(a[0]) : "";
                            String params = (argc > 1 && a[1] != null) ? String.valueOf(a[1]) : "";
                            handleEvent(name, params);
                        }
                        return Proxies.nil(m);
                    }
                });
    }

    private void handleEvent(String name, String params) {
        // ★ onEvent 现在由 SDK 在主线程回调，而 probe() 的汇总跑在 HTTP 工作线程上，
        //   ArrayDeque/LinkedHashMap 都不是线程安全的 —— 不锁就是偶发的
        //   ConcurrentModificationException / 计数对不上。
        synchronized (evLock) {
            eventTotal++;
            Integer c = counts.get(name);
            counts.put(name, c == null ? 1 : c + 1);

            if ("asr.finish".equals(name)) lastFinish = params;
            else if ("asr.partial".equals(name)) lastPartial = params;
            else if ("asr.exit".equals(name)) lastExit = params;
            else if ("asr.volume".equals(name)) {
                // ★ 峰值电平是"麦克风到底有没有收到声音"的唯一硬证据。
                //   量纲不是 0~100：安静约 1000 上下，正常说话上万（VoiceDialog 里实测过）。
                //   有它才能把两种"no match"分开：
                //     vol_max 只有几百  → 麦根本没收到，问题在音频链路
                //     vol_max 上到万级  → 收到了，问题在声学模型/语法匹配
                try {
                    int v = new JSONObject(params).optInt("volume", -1);
                    if (v > volMax) volMax = v;
                } catch (Throwable ignored) {}
            }

            // asr.volume / asr.audio 每次都刷，不进流水
            if (!"asr.volume".equals(name) && !"asr.audio".equals(name)) {
                if (events.size() >= 60) events.pollFirst();
                String brief = params == null ? "" : params;
                if (brief.length() > 260) brief = brief.substring(0, 260) + "…";
                events.addLast(name + " " + brief);
            }
        }
        // asr.exit 里带着真因（原厂封装会把它丢掉），必须落盘
        BootLog.i("ASR 事件 " + name + " " + params);
    }

    private String countsJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(J.esc(e.getKey())).append("\":").append(e.getValue());
            first = false;
        }
        return sb.append('}').toString();
    }

    private String ringJson() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String s : events) {
            if (!first) sb.append(',');
            sb.append('"').append(J.esc(s)).append('"');
            first = false;
        }
        return sb.append(']').toString();
    }
}
