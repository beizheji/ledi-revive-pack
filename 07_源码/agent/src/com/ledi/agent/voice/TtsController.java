package com.ledi.agent.voice;

import com.ledi.agent.core.J;

import com.ledi.agent.core.AgentService;

import com.ledi.agent.net.Proxies;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 机器人自带语音合成（本地 TTS）。
 *
 * 机器人出厂即带讯飞 MSC 语音组件（classes2.dex + libmsc.so，见 NOTICE.md），
 * 运行时按与 {@link CloudAsr} 同款的 SpeechUtility.createUtility 初始化串启动，
 * 随后用 com.iflytek.speech.SpeechSynthesizerAidl 合成（绑定系统里装的「讯飞语音+」服务）。
 * 本类按同一套路调用，且**全部走反射**，避免编译期依赖合并进来的原厂 dex。
 *
 * 引擎优先级（probe() 里逐个探测，谁可用用谁）：
 *   ① iflytek ：原厂同款，音色与机器人原机一致（离线/在线取决于语音+里的资源）
 *   ② system  ：Android 系统 TTS（ROM 自带引擎，中文可用才入选）
 *   ③ gateway ：电脑端 edge-tts 合成 mp3 再拉下来播（Speaker，最后的兜底）
 */
public class TtsController {

    private static final String TAG = AgentService.TAG;
    private static final String IFLYTEK_APPID = "562c8bae";
    private static final String IFLYTEK_PKG = "com.iflytek.vflynote";
    static final String ENGINE_IFLYTEK = "iflytek";
    static final String ENGINE_SYSTEM = "system";
    public static final String ENGINE_GATEWAY = "gateway";
    static final String ENGINE_HCI = "hci";          // 灵云本地离线（机器人自带"嘻嘻"音色）

    private volatile LediTts hciTts;                  // 灵云封装

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean probed = false;
    private volatile String engine = "";            // 实际可用的本地引擎
    private volatile String probeNote = "未探测";
    private volatile boolean iflytekInstalled = false;
    private volatile int iflytekInitCode = Integer.MIN_VALUE;
    private volatile String systemTtsLangs = "";
    private volatile boolean systemReady = false;

    private Object speechSynth;                     // SpeechSynthesizerAidl 实例
    private Object iflytekListener;                 // SynthesizerListener 动态代理
    private Class<?> iflytekListenerCls;
    private TextToSpeech sysTts;

    // 最近一次合成的结果，供 /ttsstatus 与异步诊断用
    private volatile String lastEngine = "";
    private volatile String lastText = "";
    private volatile String lastError = "";
    /** 正在播报（见 {@link #isSpeaking()}）。 */
    private volatile boolean speaking = false;

    public TtsController(Context ctx) { this.ctx = ctx; }

    // ======================= 探测 =======================

    /** 探测一次（结果缓存）。force=true 强制重新探测。 */
    public synchronized String probe(boolean force) {
        if (probed && !force) return engine;
        probed = true;
        engine = "";
        List<String> notes = new ArrayList<String>();

        // --- ⓪ 灵云本地离线 TTS（机器人自带"嘻嘻"音色，最高优先级） ---
        try {
            if (hciTts == null) hciTts = LediTts.get(ctx);
            if (hciTts.available()) {
                int r = hciTts.init();
                if (r == 0) {
                    engine = ENGINE_HCI;
                    notes.add("灵云:init成功(离线)");
                } else {
                    notes.add("灵云:init失败=" + r);
                }
            } else {
                notes.add("灵云:so未加载");
            }
        } catch (Throwable e) {
            notes.add("灵云异常:" + e.getClass().getSimpleName());
        }

        // --- ① 讯飞（原厂同款） ---
        iflytekInstalled = isPkgInstalled(IFLYTEK_PKG);
        if (iflytekInstalled) {
            try {
                Class<?> utilC = Class.forName("com.iflytek.cloud.SpeechUtility");
                utilC.getMethod("createUtility", Context.class, String.class)
                        .invoke(null, ctx, "appid=" + IFLYTEK_APPID + ",engine_mode=msc");
                Class<?> initL = Class.forName("com.iflytek.cloud.InitListener");
                Object initProxy = Proxy.newProxyInstance(initL.getClassLoader(),
                        new Class<?>[]{initL}, new InvocationHandler() {
                            @Override public Object invoke(Object p, Method m, Object[] a) {
                                Object g = Proxies.guard(p, m, a);   // ★ 见 Proxies
                                if (g != Proxies.UNHANDLED) return g;
                                if ("onInit".equals(m.getName()) && a != null && a.length > 0) {
                                    iflytekInitCode = ((Integer) a[0]).intValue();
                                    Log.i(TAG, "iflytek onInit code=" + iflytekInitCode);
                                }
                                return Proxies.nil(m);
                            }
                        });
                Constructor<?> ctor = Class.forName("com.iflytek.speech.SpeechSynthesizerAidl")
                        .getConstructor(Context.class, initL);
                speechSynth = ctor.newInstance(ctx, initProxy);
                iflytekListenerCls = Class.forName("com.iflytek.speech.SynthesizerListener");
                notes.add("讯飞:初始化" + iflytekInitCode);
                engine = ENGINE_IFLYTEK;
            } catch (Throwable e) {
                notes.add("讯飞异常:" + e.getClass().getSimpleName());
                lastError = String.valueOf(e);
            }
        } else {
            notes.add("讯飞:未装" + IFLYTEK_PKG);
        }

        // --- ② 系统 TTS ---
        try {
            if (sysTts == null) {
                sysTts = new TextToSpeech(ctx, new TextToSpeech.OnInitListener() {
                    @Override public void onInit(int status) {
                        if (status != TextToSpeech.SUCCESS) return;
                        try {
                            int r = sysTts.setLanguage(Locale.CHINA);
                            systemReady = r != TextToSpeech.LANG_MISSING_DATA
                                    && r != TextToSpeech.LANG_NOT_SUPPORTED;
                            systemTtsLangs = systemReady ? "zh-CN" : ("不可用(" + r + ")");
                        } catch (Throwable ignored) {}
                    }
                });
            }
            notes.add("系统TTS:初始化中");
            if (engine.isEmpty()) engine = ENGINE_SYSTEM;    // 暂定，实际以 isLocalReady 为准
        } catch (Throwable e) {
            notes.add("系统TTS异常:" + e.getClass().getSimpleName());
        }

        probeNote = android.text.TextUtils.join(" | ", notes);
        Log.i(TAG, "TTS probe -> " + engine + " (" + probeNote + ")");
        return engine;
    }

    private boolean isPkgInstalled(String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 本地引擎是否真的就绪（异步初始化需要等一会儿） */
    public boolean isLocalReady() {
        if (ENGINE_HCI.equals(engine)) return true;
        if (speechSynth != null) return true;
        return systemReady;
    }

    /**
     * 现在是不是有声音从喇叭里出来。
     *
     * <p>给「常识别」用：用户要求<b>机器人说话期间暂停识别</b>，所以需要有一个
     * 权威的"正在播报"信号。放在这里是因为 TtsController 是所有播报的**唯一出口**
     * —— 对话、主动说话、触摸反应、电脑端 {@code /say} 全都走它，挂一处就全覆盖。</p>
     *
     * <p>两个来源取或：① 灵云引擎自己的 {@code isBusy()}（它是同步播完才返回，
     * 标志最准）；② 本类的 {@code speaking} 兜住另外两个异步引擎
     * （讯飞 {@code waitBegin=false} 是"启动即返回"、系统 TTS 也是异步回调）。
     * 只看其中一个都会有漏。</p>
     */
    public boolean isSpeaking() {
        try {
            if (hciTts != null && hciTts.isBusy()) return true;
        } catch (Throwable ignored) {}
        return speaking;
    }

    // ======================= 合成 =======================

    /**
     * 用机器人自带引擎说话（异步等待 onSpeakBegin / 错误）。
     *
     * @return 实际使用的引擎（iflytek/system），失败返回 ""；调用方据此决定是否回退网关。
     */
    public String speak(String text, boolean waitBegin) {
        if (text == null || text.trim().isEmpty()) return "";
        probe(false);
        lastText = text;
        lastError = "";
        speaking = true;                       // 见 isSpeaking()：常识别靠它判断"该不该暂停听"
        // ★★★ v178：所有播报的唯一出口 ⇒ 在这里通知灯语"开始说话了"，
        //   蓝灯（听语灯）会在 AgentService.listenLed 顶部被挡掉并立刻灭。
        //   挂这一处就覆盖对话 / 主动说话 / 触摸反应 / 电脑端 /say 全部路径。
        //   本机实际用的灵云引擎 speak() 是**同步阻塞**的（见 LediTts.speak 注释），
        //   所以下面的 finally 一定在播完之后才执行，"说话中"这个窗口是准的。
        AgentService.noteTtsSpeaking(true);
        try {
            return speakInner(text, waitBegin);
        } finally {
            speaking = false;
            AgentService.noteTtsSpeaking(false);   // 说完 → 若麦闸还开着就把灯亮回来
        }
    }

    private String speakInner(String text, boolean waitBegin) {
        // ⓪ 灵云本地离线优先
        if (ENGINE_HCI.equals(engine) && hciTts != null) {
            if (hciTts.speak(text)) {
                lastEngine = ENGINE_HCI;
                return ENGINE_HCI;
            }
            lastError = "hci:" + hciTts.lastError();
        }

        if (speechSynth != null) {
            if (speakIflytek(text, waitBegin)) { lastEngine = ENGINE_IFLYTEK; return ENGINE_IFLYTEK; }
        }
        probeSystem(false);
        if (systemReady && speakSystem(text, waitBegin)) {
            lastEngine = ENGINE_SYSTEM;
            return ENGINE_SYSTEM;
        }
        return "";
    }

    private void probeSystem(boolean force) {
        try {
            if (sysTts == null) probe(true);
            // 初始化是异步的，给一点时间
            long t0 = System.currentTimeMillis();
            while (!systemReady && sysTts != null
                    && System.currentTimeMillis() - t0 < 900) {
                Thread.sleep(50);
            }
        } catch (Throwable ignored) {}
    }

    private boolean speakIflytek(final String text, boolean waitBegin) {
        try {
            final boolean[] began = {false};
            final boolean[] failed = {false};
            Object listener = newIflytekListener(began, failed);
            Method ss = speechSynth.getClass()
                    .getMethod("startSpeaking", String.class, iflytekListenerCls);
            ss.invoke(speechSynth, text, listener);
            if (!waitBegin) return true;
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 1500) {
                if (began[0]) return true;
                if (failed[0]) return false;
                Thread.sleep(50);
            }
            return began[0];
        } catch (Throwable e) {
            lastError = "iflytek:" + e;
            Log.e(TAG, "iflytek speak fail", e);
            return false;
        }
    }

    private Object newIflytekListener(final boolean[] began,
                                     final boolean[] failed) throws Exception {
        Class<?> lc = iflytekListenerCls;
        return Proxy.newProxyInstance(lc.getClassLoader(), new Class<?>[]{lc},
                new InvocationHandler() {
                    @Override public Object invoke(Object p, Method m, Object[] a) {
                        Object g = Proxies.guard(p, m, a);   // ★ 见 Proxies
                        if (g != Proxies.UNHANDLED) return g;
                        String n = m.getName();
                        if ("onSpeakBegin".equals(n)) {
                            began[0] = true;
                            Log.i(TAG, "iflytek speak begin");
                        } else if ("onCompleted".equals(n)) {
                            Log.i(TAG, "iflytek speak completed");
                        } else if ("onError".equals(n)) {
                            failed[0] = true;
                            Log.e(TAG, "iflytek speak error");
                        } else if ("onEvent".equals(n)) {
                            Log.i(TAG, "iflytek speak event");
                        }
                        return Proxies.nil(m);
                    }
                });
    }

    private boolean speakSystem(String text, boolean waitBegin) {
        try {
            final String uttId = "ledi_" + System.currentTimeMillis();
            int r = sysTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, uttId);
            if (waitBegin) Thread.sleep(120);   // 系统 TTS 无同步回调，短等即可
            return r == TextToSpeech.SUCCESS;
        } catch (Throwable e) {
            lastError = "system:" + e;
            return false;
        }
    }

    /** 立即打断当前播报（灵云/讯飞/系统三路都停）——语音唤醒打断、新指令覆盖时用 */
    public String stopNow() {
        try { if (hciTts != null) hciTts.stopNow(); } catch (Throwable ignored) {}
        return stop();
    }

    public String stop() {
        try { if (speechSynth != null) speechSynth.getClass()
                .getMethod("stopSpeaking", iflytekListenerCls)
                .invoke(speechSynth, iflytekListener); } catch (Throwable ignored) {}
        try { if (sysTts != null) sysTts.stop(); } catch (Throwable ignored) {}
        return "stopped";
    }

    // ======================= 状态 =======================

    /** 诊断信息（/ttsstatus 用） */
    public String status() {
        probe(false);
        List<String> engines = new ArrayList<String>();
        try {
            for (TextToSpeech.EngineInfo ei : sysTts == null
                    ? new ArrayList<TextToSpeech.EngineInfo>()
                    : sysTts.getEngines()) {
                engines.add(ei.name);
            }
        } catch (Throwable ignored) {}
        StringBuilder sb = new StringBuilder();
        sb.append("{\"engine\":\"").append(J.esc(engine)).append("\"")
          .append(",\"hci_ready\":").append(ENGINE_HCI.equals(engine))
          .append(",\"hci_err\":\"").append(J.esc(hciTts == null ? "" : hciTts.lastError())).append("\"")
          .append(",\"iflytek_installed\":").append(iflytekInstalled)
          .append(",\"iflytek_init_code\":").append(iflytekInitCode)
          .append(",\"iflytek_ready\":").append(speechSynth != null)
          .append(",\"system_ready\":").append(systemReady)
          .append(",\"system_lang\":\"").append(J.esc(systemTtsLangs)).append("\"")
          .append(",\"system_engines\":\"").append(J.esc(android.text.TextUtils.join(",", engines))).append("\"")
          .append(",\"last_engine\":\"").append(J.esc(lastEngine)).append("\"")
          .append(",\"last_text\":\"").append(J.esc(abbrev(lastText, 40))).append("\"")
          .append(",\"last_error\":\"").append(J.esc(abbrev(lastError, 120))).append("\"")
          .append(",\"note\":\"").append(J.esc(probeNote)).append("\"}");
        return sb.toString();
    }

    static String abbrev(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    public void shutdown() {
        try { if (hciTts != null) hciTts.release(); } catch (Throwable ignored) {}
        try { if (sysTts != null) sysTts.shutdown(); } catch (Throwable ignored) {}
    }
}
