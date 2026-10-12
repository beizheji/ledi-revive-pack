package com.ledi.agent.voice;

import com.ledi.agent.core.J;

import com.ledi.agent.core.BootLog;

import com.ledi.agent.core.AgentService;

import com.ledi.agent.net.Proxies;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 对话通路：唤醒之后<b>听一整句话</b>，说完静音就交给 AI 应答。
 *
 * <h3>它和 {@link AsrProbe} / {@code listenOnce} 的区别</h3>
 * 老路子 {@code listenOnce(sec)} 是"开麦固定 N 秒，时间到就拿走结果"——
 * 不管人说完没有。人说到一半时间到了，或者说完还傻站 3 秒，两者都很难受。
 * 这里换成<b>由语音活动决定什么时候收尾</b>：
 *
 * <pre>
 *   唤醒 ──► 开麦（最长 10 秒）
 *              │
 *              ├─ 检测到人声 ──► 持续累积 partial
 *              │                     │
 *              │                     └─ 静音满 1 秒 ──► 立刻收尾，交给 AI
 *              │
 *              └─ 一直没人说话 ──► 10 秒到，判为"没听清"
 * </pre>
 *
 * <h3>两条独立的收尾判据，缺一不可</h3>
 * <ol>
 *   <li><b>SDK 自己的 VAD</b>：{@code asr.finish} 事件（params 里带
 *       {@code results_recognition}）。这是首选，因为它经过了完整的前端降噪。</li>
 *   <li><b>我们自己的静音计时</b>：{@link #reaper} 线程盯着"最后一次活动"。只要
 *       已经确认人开过口（{@code sawSpeech}），再静 {@code silenceMs} 毫秒就主动
 *       {@code asr.stop} 收尾。<b>必须有这一条</b>——离线命令词引擎在有些版本上
 *       只会推 {@code partial} 不发 {@code finish}，只等 VAD 就会一直等到 10 秒超时，
 *       "说完 1 秒内反应"这条需求就落空了。</li>
 * </ol>
 *
 * <h3>为什么能当"语音打断"用</h3>
 * 同一次会话既能在播报<b>前</b>听，也能在播报<b>中</b>听。播报时把会话开着，
 * 一旦重新检测到人声就 {@code tts.stopNow()}——这就是打断。
 * 为了不自我打断，有两道闸：
 * <ul>
 *   <li>开场 {@link #BARGE_GUARD_MS} 毫秒内的活动一律忽略（给 TTS 起播留时间）；</li>
 *   <li>识别出的文本若与正在播的句子高度重合，判为"机器人听见了自己"，不算打断。</li>
 * </ul>
 * 机器人这条链路本身有硬件 AEC（实测它自己放音时麦克风峰值反而更低），
 * 所以第二道闸是兜底，不是主力。
 *
 * <h3>★ 必须在主线程调 SDK（v66 修的致命伤）</h3>
 * 百度 EventManager 对 {@code send()} 有线程断言，不是主线程<b>直接抛</b>
 * {@code android.util.AndroidRuntimeException: bad thread, need main thread, asr.start}，
 * 会话根本开不起来。而唤醒回调是在引擎自己的线程上来的，{@link #answerLoop} 也就在
 * 那条线程上跑 —— 于是"喊完名字后开麦 10 秒听人说话"这条需求**从来没生效过**：
 * 每次唤醒都只是回了一句固定话术，用户说什么都听不见。
 *
 * <p>排查成本之所以高，是因为失败是<b>静默</b>的：唤醒引擎正常工作（{@code wp.enter}）、
 * 麦克风也在采音、TTS 也把话说了，只有对话通路那个异常被 {@code chain(t)} 收进字段里，
 * 没人去读就永远看不见。所以现在一律走 {@link #runOnMain}，并且把这条断言写在这里。</p>
 */
public final class VoiceDialog {

    // ---------------------------------------------------------------- 可调参数

    /**
     * 唤醒后最长听多久。
     *
     * <p>★ v159（用户 2026-09-24）：<b>10 秒 → 20 秒</b>。理由是真的用起来：
     * 一句话还没说完窗口就关，用户听到的就是"机器人抢话"。
     *
     * <p>★★ 但它<b>不是唯一一道闸</b> —— {@code VoiceWakeController.listenSentence}
     * 里还有一处 {@code Math.min(12000, …)} 把在线听写钳在 12 秒。
     * <b>只改这里 = 需求永远不生效</b>（v159 两处一起改，改一处≠改好）。
     */
    public static volatile int maxListenMs = 20000;
    /**
     * <b>本次会话</b>的"最长听多久"（由 {@code start(l, windowMs)} 设定，默认跟
     * {@link #maxListenMs} 一致）。收尾线程读这个而不是全局值 —— 见
     * {@code start(Listener, int)} 的说明。
     */
    private volatile int curWindowMs = maxListenMs;
    /**
     * 说完之后静音多久算"这句话说完了"。
     *
     * <p>★ v159（用户 2026-09-24）：<b>1 秒 → 2 秒</b>。人是会中途换气的；
     * 1 秒的停顿容忍经常在"半句"处收尾，机器答的就成了半句的答案。
     *
     * <p>★★ v165（用户 2026-09-25「用户不说话了再结束录音」）：<b>2 秒 → 3 秒</b>。
     * 起因是真机取证（{@code ledi_talk.jsonl} 02:48–02:50）：用户连摸 60 次头、
     * 每次都只换来一轮 5.4 秒的空听写。成年人在组织一句话时，2 秒的中途停顿很常见，
     * 而这里一旦收尾，用户后半句就被丢了、还得重新摸一次头。多等 1 秒换"少一次重来"。</p>
     *
     * <p>★ 注意<b>这道静音只作用于离线链路</b>（此处 {@code silenceMs} 同时喂
     * {@code reaper} 自建判据与 SDK 的 {@code vad.endpoint-timeout}）。
     * 用户日常走的是<b>在线听写</b>（讯飞 MSC），它有自己的静音参数，
     * 在 {@code CloudAsr} 里 —— 只改这里不等于"在线听写也变宽容了"。</p>
     */
    public static volatile int silenceMs = 3000;
    /** 一轮唤醒最多来回几次（防止和 AI 互相刷屏）。 */
    public static volatile int maxTurns = 3;
    /** 对话通路总开关。关掉 → 行为退回"喊了就回一句固定话"。 */
    public static volatile boolean enabled = true;
    /**
     * 常识别（用户新需求）：<b>不等唤醒词，一直听</b>。
     *
     * <p>开着的时候唤醒引擎（wp）会被停掉 —— 这台机器只有一个麦克风入口，
     * 两个引擎同时抓麦在真机上实测会直接把进程搞死（见 {@link #micHanded} 那段注释）。
     * 所以「常识别」和「唤醒词」是<b>互斥</b>的两种听法，不能同时开。</p>
     *
     * <p>配套要求：<b>机器人说话期间要暂停识别</b>。否则它自己播报的内容会被自己
     * 听见，轻则白白占着麦，重则自激。判断信号走
     * {@code TtsController.isSpeaking()}，由 {@code VoiceWakeController.speakingGate} 注入。</p>
     */
    static volatile boolean continuous = false;
    /**
     * 识别用哪份语法。{@code null} = 用 APK 里那份**命令词表**
     * （{@code assets/baidu_speech_grammar.bsg}，53 条固定说法）；{@code ""} = 不给语法，
     * 让解码器走自由识别。
     *
     * <p><b>为什么必须能切</b>：那份 bsg 是原厂的**命令词语法** —— 解出来是一串
     * {@code ^停止$} / {@code ^查询电量$} / {@code ^(左|右)转$} 这样的精确匹配规则。
     * 解码器只在<b>这些说法</b>里找答案，所以：
     * <pre>
     *   说「查询电量」→ 能识别
     *   说「今天天气怎么样」→ asr.finish error=7 No recognition result matched
     *                          （日志里 partial=0 帧，看着像"没人说话"，其实是语法里没有）
     * </pre>
     * 这与麦克风、授权、VAD 都无关 —— 真机上已经用电脑喇叭当声源验证过：
     * VAD 明确检出了 2.28 秒语音段（{@code asr.end begin=27200 end=63680}），
     * 音量峰值 17654（安静房间基准 1077），但一个候选都没匹配上。</p>
     *
     * <p>所以「常识别 + 交给 AI 聊天」要走得通，多数时候得把它切成自由识别。做成可配
     * 而不是直接改死，是因为命令词表在<b>离线、无网</b>时更准更省电，两种模式各有用途。</p>
     */
    public static volatile String grammarOverride = null;
    /**
     * {@code asr.start} 里 {@code vad} 的取值。
     *
     * <p><b>合法值只有这几个</b>（反汇编 {@code EventManagerAsr.handleAsrStart} 逐条比对得出）：
     * {@code model_vad} / {@code model-vad} / {@code mfe} / {@code search} / {@code input} /
     * {@code null} / 空串。给别的值它<b>不忽略</b>，而是当场把会话判失败：
     * {@code asr.finish error=7 desc=... bad vad: dnn}。</p>
     *
     * <p>踩过：老代码写的是 {@code "dnn"}（那是百度在线 SDK 的叫法，这台机器上的
     * 离线 EASR 不认）。因为失败表现成"会话刚开就结束"，很像是麦克风或授权问题，
     * 白白绕了一圈。离线引擎对应的名字是 {@code model_vad}。</p>
     */
    public static volatile String vadMode = "model_vad";
    /**
     * {@code decoder} 取值，SDK 里是 <b>int</b>（{@code optInt("decoder", …)}，
     * 随后又按 {@code basic.decoder} 走 packed-switch 选解码器）。原来的 3 恰好落在
     * 可用分支上，这里保留可配只是为了将来能换。
     */
    public static volatile int decoderMode = 3;
    /** 打断灵敏度：连续多少帧音量超阈值才算"人在说话"。 */
    private static final int LOUD_STREAK = 3;
    /**
     * 音量阈值。
     *
     * <p><b>量纲校准（v72 实测）</b>：这个 SDK 的 {@code asr.volume} <b>不是</b> 0~100，
     * 实测安静房间里就有 2210、2496 这种值（原注释里写"约 0~100"是错的，阈值 45
     * 于是形同虚设：环境噪声一直超线，{@link #lastActivityMs} 被无限刷新，
     * 结果"说完静音 1 秒就收尾"这条需求永远不会触发，每轮都硬等满 10 秒）。
     * 现在改成可配（{@code POST /dialog {"loud":N}}），并靠 {@link #volMax} 现场标定。
     *
     * <p><b>实测两档（v72）</b>：安静房间 vol_max=1077；机器人自己播报时 vol_max=9398。
     * 于是默认值取 2600 —— 安静时约 2.4 倍余量（不会被底噪顶穿），人声时远低于峰值
     * （该响的时候一定响）。标定方法：{@code POST /dialog {"listen":true}} 读 state.vol_max。</p>
     */
    public static volatile int loudLevel = 2600;
    /** 播报开始后这么久内的活动不算打断。 */
    private static final long BARGE_GUARD_MS = 700;
    /** 收尾时等 asr.finish 的额外时间。 */
    private static final long FINISH_GRACE_MS = 900;

    // ---------------------------------------------------------------- 对外接口

    /** 会话回调。三个方法都可能在 SDK 事件线程上被调，实现方自己保证轻量。 */
    public interface Listener {
        /** 检测到有人开口（携带当前 partial，可能为空串）。 */
        void onSpeech(String partial);
        /** 一句话结束。text 已过 {@link #correct} 纠错，可能是空串（没听清）。 */
        void onFinal(String text);
        /** 会话异常结束（引擎起不来 / asr.exit 报错）。 */
        void onError(String why);
    }

    /**
     * 麦克风交接：ASR 引擎和唤醒引擎抢的是**同一个麦克风**。
     *
     * <p>由 {@code VoiceWakeController} 注入（它才能让唤醒引擎让麦）。会话两端各叫一次：
     * {@link #beforeAsr} 在开麦前，{@link #afterAsr} 在收麦后（含起不来时的回滚）。</p>
     */
    interface MicGate {
        /** 开麦之前：把麦克风从唤醒引擎手里要过来。 */
        void beforeAsr();
        /** 收麦之后：把麦克风还回去。 */
        void afterAsr();
    }

    /** 注入麦克风交接器；不注入也能跑（只是可能和唤醒引擎抢麦）。 */
    void setMicGate(MicGate g) { micGate = g; }

    /** 麦克风当前是不是被本对象借走了（用于保证一定还）。 */
    private volatile boolean micHanded;

    private static final String EMF_CLS = "com.baidu.speech.EventManagerFactory";
    private static final String EL_CLS  = "com.baidu.speech.EventListener";
    private static final String APP_ID  = "9528412";

    private final Context ctx;
    private final Runnable licenseHook;     // SetLicenseFlag(true)，由 VoiceWakeController 注入
    private final String licPath;
    private final String resFile;
    private final String grammar;
    /** 主线程 Handler：所有 SDK 调用都要投到它上面去（见类注释的最后一段）。 */
    private final Handler main = new Handler(Looper.getMainLooper());
    /** 单次 SDK 调用在主线程上的等待上限（主线程被别的东西占住时别把对话拖死）。 */
    private static final long MAIN_CALL_TIMEOUT_MS = 6000;

    // ---------------------------------------------------------------- 运行状态

    private volatile boolean active;
    private volatile long startMs;
    private volatile long lastActivityMs;
    private volatile boolean sawSpeech;
    private volatile String lastPartial = "";
    private volatile String finishParams = "";
    private volatile String lastExit = "";
    private volatile String lastError = "";
    private volatile int volume = -1;
    private volatile int volMax;             // 本场音量峰值（标定 spoken 阈值用）
    private volatile String finishNote = ""; // 收尾说明（如"没识别到内容"，不算错误）
    private volatile int loudStreak;
    private volatile int partialCount;
    private volatile int turn;               // 第几轮（诊断用）

    private Object engine;
    private Class<?> elCls;
    private Object elProxy;
    private Listener listener;
    private Thread reaper;
    private volatile boolean finishing;
    private volatile MicGate micGate;

    VoiceDialog(Context ctx, Runnable licenseHook, String licPath, String resFile,
                String grammar) {
        this.ctx = ctx;
        this.licenseHook = licenseHook;
        this.licPath = licPath;
        this.resFile = resFile;
        this.grammar = grammar;
    }

    public boolean isActive() { return active; }
    int lastVolume() { return volume; }

    // ---------------------------------------------------------------- 会话

    /**
     * 开一次会话。<b>不阻塞</b>，结果通过 {@code l} 回调。
     *
     * @return 成功开始返回 true；false 时 {@code l.onError} 已经被调过
     */
    public synchronized boolean start(Listener l) {
        return start(l, maxListenMs);
    }

    /**
     * 同 {@link #start(Listener)}，但本次会话的"最长听多久"用 {@code windowMs}，
     * <b>不动全局配置</b>。
     *
     * <p>常识别需要它：那种模式每轮窗口比应答链路短（听到就立刻处理，收尾越勤反应越快），
     * 但又不能去改 {@code maxListenMs} —— 那是共享配置，改了会把同时存在的应答链路
     * 也一起改掉。所以窗口按会话传进来，存在 {@link #curWindowMs} 里给收尾线程读。</p>
     */
    synchronized boolean start(Listener l, int windowMs) {
        if (active) {
            lastError = "会话已在跑（上一次没收干净）";
            if (l != null) l.onError(lastError);
            return false;
        }
        curWindowMs = Math.max(1500, windowMs);
        listener = l;
        lastPartial = "";
        finishParams = "";
        lastExit = "";
        lastError = "";
        finishNote = "";
        volume = -1;
        volMax = 0;
        loudStreak = 0;
        partialCount = 0;
        sawSpeech = false;
        finishing = false;
        long now = System.currentTimeMillis();
        // ★ sawSpeech=false 时 lastActivityMs 也先置为开始时间：这样"从头到尾没人开口"
        //   会走满 maxListenMs 才收尾，而不是刚开麦就判成静音收尾。
        lastActivityMs = now;
        startMs = now;
        turn++;
        // ★ 先把麦克风要过来，再碰 SDK。顺序不能反：asr.start 一发出引擎就会去抢麦，
        //   这时候唤醒引擎还在采音，两个引擎同时开麦 → 进程被打死（v69 现场，无 tombstone）。
        takeMic();
        try {
            final JSONObject p = new JSONObject();
            p.put("appid", APP_ID);
            p.put("license-file-path", licPath);
            p.put("asr-base-file-path", resFile);
            p.put("grammar", grammarOverride == null ? grammar : grammarOverride);
            p.put("vad", vadMode == null ? "" : vadMode);
            p.put("decoder", decoderMode);
            p.put("nlu", "disable");
            p.put("sound_start", -1);
            // ★ 这两个是给 SDK 自己的 VAD 用的（反汇编确认都走 params.optString 读取，
            //   给错也不抛）。但真正保证"静音 1 秒收尾"的是下面那个 reaper 线程 ——
            //   不同 SDK 版本对 endpoint-timeout 的解释不一致，不能只靠它。
            p.put("vad.endpoint-timeout", silenceMs);
            p.put("vad.max-wait-duration", curWindowMs);
            // ★★ 授权开关 + 建引擎 + asr.start **三件事一起**投到主线程做。
            //    分开投递没用：每一步都可能撞上 SDK 的线程断言，而且建引擎本身
            //    也会去碰只允许主线程访问的东西。一次 hop 做完最稳。
            runOnMain(new Runnable() {
                @Override public void run() {
                    try {
                        if (licenseHook != null) licenseHook.run();   // 必须在 asr.start 之前
                        Object em = ensureEngine();
                        send(em, "asr.start", p.toString());
                    } catch (Throwable t) {
                        throw new RuntimeException(t);   // 带到调用方去（runOnMain 会还原）
                    }
                }
            }, MAIN_CALL_TIMEOUT_MS, "asr.start");
            active = true;
            BootLog.i("对话通路 ★开始听（上限 " + curWindowMs + "ms，静音 " + silenceMs
                    + "ms 收尾）第 " + turn + " 轮");
            startReaper();
            return true;
        } catch (Throwable t) {
            lastError = chain(t);
            active = false;
            giveMicBack();      // 起不来也要把麦还回去，否则唤醒引擎就一直哑着
            BootLog.w("对话通路 起不来: " + lastError + " | 根因栈: " + stack(t));
            if (l != null) l.onError(lastError);
            return false;
        }
    }

    /** 主动收尾（阻塞式，等 finish 落定）。幂等。 */
    public void stop() {
        // ★ 锁只用来"抢收尾权"，不跨主线程投递持有 —— 见 runOnMain 的说明：
        //   持锁等主线程时，若主线程正好也想进 stop()（SDK 事件回调可能落在主线程），
        //   就成了互等。active=false 这个翻转本身已经保证只有一个线程往下走。
        final Object em;
        synchronized (this) {
            if (!active) { giveMicBack(); return; }
            active = false;
            em = engine;
        }
        if (em == null) { giveMicBack(); return; }
        try {
            // asr.stop 同样要被 SDK 的主线程断言检查，走一样的路。
            runOnMain(new Runnable() {
                @Override public void run() {
                    try { send(em, "asr.stop", "{}"); }
                    catch (Throwable t) { throw new RuntimeException(t); }
                }
            }, MAIN_CALL_TIMEOUT_MS, "asr.stop");
            long until = System.currentTimeMillis() + FINISH_GRACE_MS;
            while (System.currentTimeMillis() < until) {
                if (finishParams != null && finishParams.length() > 0) break;
                try { Thread.sleep(60); } catch (InterruptedException ignored) {}
            }
        } catch (Throwable t) {
            BootLog.w("对话通路 收尾异常: " + chain(t));
        } finally {
            giveMicBack();
        }
    }

    /** 把麦克风要过来（幂等）。要不到也照样往下走 —— 让麦失败顶多是抢麦，不该直接放弃会话。 */
    private void takeMic() {
        if (micHanded) return;
        MicGate g = micGate;
        if (g == null) return;
        try {
            g.beforeAsr();
            micHanded = true;
        } catch (Throwable t) {
            BootLog.w("对话通路 让麦失败（照常继续）: " + t);
        }
    }

    /** 把麦克风还回去（幂等）。必须保证被调到：没还回去 = 唤醒引擎一直哑着。 */
    private void giveMicBack() {
        if (!micHanded) return;
        micHanded = false;
        MicGate g = micGate;
        if (g == null) return;
        try { g.afterAsr(); }
        catch (Throwable t) { BootLog.w("对话通路 还麦失败: " + t); }
    }

    /** 彻底放掉引擎（换词表 / 退出时用）。 */
    synchronized void release() {
        try { stop(); } catch (Throwable ignored) {}
        engine = null;
        elProxy = null;
    }

    /** 阻塞式听一轮：给懒得写回调的地方用。返回纠错后的文本，没听清返回 ""。 */
    public String listenBlocking(long timeoutMs) {
        final Object lock = new Object();
        final String[] out = new String[]{null};
        Listener l = new Listener() {
            @Override public void onSpeech(String partial) {}
            @Override public void onFinal(String text) {
                synchronized (lock) { out[0] = text; lock.notifyAll(); }
            }
            @Override public void onError(String why) {
                synchronized (lock) { out[0] = ""; lock.notifyAll(); }
            }
        };
        if (!start(l)) return "";
        synchronized (lock) {
            long until = System.currentTimeMillis() + timeoutMs;
            while (out[0] == null && System.currentTimeMillis() < until) {
                try { lock.wait(Math.max(50, until - System.currentTimeMillis())); }
                catch (InterruptedException ignored) { break; }
            }
        }
        stop();
        return out[0] == null ? "" : out[0];
    }

    // ---------------------------------------------------------------- 收尾线程

    /** 盯着"最后一次活动"，静音够了就收尾。 */
    private void startReaper() {
        reaper = new Thread(new Runnable() {
            @Override public void run() {
                while (active) {
                    try { Thread.sleep(120); } catch (InterruptedException ignored) { return; }
                    long now = System.currentTimeMillis();
                    boolean spoken = sawSpeech;
                    long quiet = now - lastActivityMs;
                    if (spoken && quiet >= silenceMs) {
                        finishTurn("静音 " + quiet + "ms（自建判据）");
                        return;
                    }
                    if (now - startMs >= curWindowMs) {
                        finishTurn(spoken ? ("听满 " + curWindowMs + "ms")
                                        : "全程没人说话");
                        return;
                    }
                }
            }
        }, "ledi-dialog");
        reaper.setDaemon(true);
        reaper.start();
    }

    /** 收尾一次：停 ASR → 取文本 → 回调 onFinal。用 finishing 保证只走一次。 */
    private void finishTurn(String why) {
        synchronized (this) {
            if (finishing) return;
            finishing = true;
        }
        String raw = "";
        try {
            stop();
            raw = bestText();
        } catch (Throwable t) {
            BootLog.w("对话通路 收尾取文本失败: " + chain(t));
        }
        String text = correct(raw);
        BootLog.i("对话通路 ★收尾（" + why + "）原文=[" + raw + "] 纠错后=[" + text
                + "] partial=" + partialCount + " 帧");
        Listener l = listener;
        listener = null;
        if (l != null) {
            try { l.onFinal(text); } catch (Throwable t) { BootLog.w("onFinal 异常: " + t); }
        }
    }

    /** finish 的 results_recognition[0] 优先，拿不到就用累计的 partial。 */
    private String bestText() {
        String f = finishParams;
        if (f != null && f.length() > 0) {
            try {
                JSONObject o = new JSONObject(f);
                int ec = o.optInt("error", 0);
                if (ec == 0) {
                    JSONArray a = o.optJSONArray("results_recognition");
                    if (a != null && a.length() > 0) {
                        String s = a.getString(0);
                        if (s != null && s.trim().length() > 0) return s.trim();
                    }
                } else if (ec == 7 || ec == 6) {
                    // ★ error=6/7 都是"这段音频里没有可识别的内容"，也就是**没人说话**，
                    //   不是故障。实测：安静房间跑满窗口后报
                    //     error=6 "java.lang.Exception: #6, No speech input."（全程没检测到人声）
                    //     error=7 "java.lang.Exception: #7, No recognition result matched."（有声音但没匹配上）
                    //   它们不该进 lastError —— 否则电脑端控制台会把每一次"用户没吭声"都
                    //   显示成红色错误，真正的故障反而淹在里面看不见。
                    finishNote = (ec == 6 ? "全程没人说话" : "没识别到内容") + "（error=" + ec + "）";
                } else {
                    lastError = "asr.finish error=" + ec
                            + " desc=" + o.optString("desc");
                }
            } catch (Throwable ignored) {}
        }
        // finish 没到（或 error≠0）时用 partial —— 至少把人说的一半救回来。
        // 取最长的那次：partial 是"越长越完整"的增量序列，中途可能被 VAD 重置成短的。
        return longestPartial();
    }

    private String longestPartial() {
        String best = "";
        for (String s : partials) if (s.length() > best.length()) best = s;
        return best;
    }

    private final List<String> partials = new ArrayList<String>();

    // ---------------------------------------------------------------- 事件

    private void handleEvent(String name, String params) {
        if (!active) return;
        long now = System.currentTimeMillis();
        if ("asr.partial".equals(name)) {
            String t = extractText(params);
            if (t.length() > 0) {
                lastPartial = t;
                partials.add(t);
                if (partials.size() > 40) partials.remove(0);
                partialCount++;
                sawSpeech = true;
                lastActivityMs = now;
                Listener l = listener;
                if (l != null) {
                    try { l.onSpeech(t); } catch (Throwable ignored) {}
                }
            }
        } else if ("asr.finish".equals(name)) {
            finishParams = params;
            lastActivityMs = now;
            // finish 也可能在 stop() 之前自己到（VAD 端点），那就立刻收尾，
            // 不必再等 reaper —— "说完 1 秒内反应"的 1 秒全花在等待上太亏。
            if (active && !finishing) finishTurn("SDK VAD 端点");
        } else if ("asr.exit".equals(name)) {
            // ★ 原厂封装在这里什么都不做，真因（如 WakeUpInitial=-1 那一类）
            //   就是这么被丢掉的。必须落盘。
            lastExit = params;
            BootLog.w("对话通路 asr.exit " + params);
        } else if ("asr.volume".equals(name)) {
            int v = extractVolume(params);
            if (v >= 0) {
                volume = v;
                if (v > volMax) volMax = v;          // 本场峰值，用来现场标定阈值量纲
                if (v >= loudLevel) {
                    if (++loudStreak >= LOUD_STREAK) {
                        // 音量只是"有人在出声"的弱证据：它分不清是人还是机器人自己的喇叭。
                        // 所以只用来**延长**已经确认过的那句话，绝不单独把 sawSpeech 置真
                        // ——否则 TTS 一响就会被自己打断（有 AEC 也别赌）。
                        lastActivityMs = now;
                    }
                } else {
                    loudStreak = 0;
                }
            }
        }
    }

    private static String extractText(String params) {
        if (params == null || params.length() == 0) return "";
        try {
            JSONObject o = new JSONObject(params);
            JSONArray a = o.optJSONArray("results_recognition");
            if (a != null && a.length() > 0) {
                String s = a.getString(0);
                return s == null ? "" : s.trim();
            }
            String s = o.optString("partial_result", "");
            return s == null ? "" : s.trim();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int extractVolume(String params) {
        if (params == null || params.length() == 0) return -1;
        try {
            return new JSONObject(params).optInt("volume", -1);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- 错字纠正

    /**
     * 语音识别的同音/近音错字纠正。
     *
     * <p>为什么在本地做而不是全丢给大模型：唤醒词、动作名、开关名这些是<b>要精确匹配</b>的
     * 命令词，错一个字就整条失效（"关灯"说成"观灯"、"乐迪"听成"勒迪"）。
     * 大模型能猜出语义，但机器人本地这些指令不经过大模型，猜不出来。
     * 所以先按"同音字归一到唯一正确写法"过一遍，剩下的语义容错再交给 AI
     * （见 {@code VOICE_HINT}，那条提示词会一起发给大模型）。</p>
     *
     * <p>只做替换，不做增删 —— 不编造用户没说过的话。</p>
     */
    public static String correct(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        // 1) 去掉 ASR 常带的分隔与标点（中文识别结果里常混空格和半角标点）
        s = s.replaceAll("[\\s，。、！？!?,.;:~…]+", "");
        // 2) 叠字去重（"我我我想"→"我想"）：ASR 在起音处很容易重复
        s = s.replaceAll("(.)\\1{2,}", "$1$1");   // 三连以上压成两连，避免误伤"哈哈哈"
        // 3) 同音/近音归一
        for (Map.Entry<String, String[]> e : HOMOPHONE.entrySet()) {
            for (String bad : e.getValue()) {
                if (s.contains(bad)) s = s.replace(bad, e.getKey());
            }
        }
        return s;
    }

    /**
     * 错字表：key = 正确写法，value = 常见错听写法（含近音，不只是严格同音）。
     *
     * <p>顺序无影响（每个 bad 都是独立子串替换），但条目里短的会被长的覆盖，
     * 所以每条内部把更长的写法排在前面更稳。</p>
     */
    private static final Map<String, String[]> HOMOPHONE = new LinkedHashMap<String, String[]>();

    static {
        HOMOPHONE.put("乐迪", new String[]{"勒迪", "乐帝", "勒帝", "乐蒂", "热迪", "乐的", "乐地",
                "乐乐迪", "洛迪", "罗迪"});
        HOMOPHONE.put("哈喽", new String[]{"哈罗", "哈啰", "哈洛", "哈楼", "哈咯"});
        HOMOPHONE.put("超级飞侠", new String[]{"超级飞狭", "超级飞霞", "超级飞下", "超极飞侠",
                "超级飞夏", "超级菲侠"});
        HOMOPHONE.put("你好", new String[]{"尼好", "泥好", "拟好", "你号", "你嚎", "妳好"});
        HOMOPHONE.put("跳舞", new String[]{"条舞", "跳五", "挑舞", "跳无"});
        HOMOPHONE.put("表情", new String[]{"表请", "表清", "彪情"});
        HOMOPHONE.put("关灯", new String[]{"观灯", "官灯", "关等"});
        HOMOPHONE.put("开灯", new String[]{"凯灯", "开等", "开瞪"});
        HOMOPHONE.put("电量", new String[]{"电亮", "店量", "电两"});
        HOMOPHONE.put("音量", new String[]{"阴量", "音亮", "因量"});
        HOMOPHONE.put("机器人", new String[]{"机械人", "机器任", "机气人"});
        HOMOPHONE.put("再见", new String[]{"在见", "在建", "在件"});
        HOMOPHONE.put("开心", new String[]{"开新", "开欣", "楷心"});
        HOMOPHONE.put("难受", new String[]{"男受", "南受", "难瘦"});
        HOMOPHONE.put("唱歌", new String[]{"昌歌", "唱哥", "长歌"});
        HOMOPHONE.put("故事", new String[]{"故是", "古事", "股市"});
        HOMOPHONE.put("睡觉", new String[]{"水觉", "碎觉", "税觉"});
        HOMOPHONE.put("等一下", new String[]{"等一夏", "等以下", "等一吓"});
        HOMOPHONE.put("什么", new String[]{"神么", "甚麽", "是么"});
        HOMOPHONE.put("为什么", new String[]{"为神么", "为甚么", "为是么"});
        HOMOPHONE.put("可以", new String[]{"可一", "柯以"});
        HOMOPHONE.put("谢谢", new String[]{"些些", "写写", "歇歇"});
        HOMOPHONE.put("拜拜", new String[]{"白白", "败败", "掰掰"});
    }

    // ---------------------------------------------------------------- 反射胶水

    /**
     * 把一段代码**放到主线程**上执行，并等它跑完。
     *
     * <p>为什么必须这样：百度 EventManager 的 {@code send()} 里有线程断言，
     * 非主线程直接抛 {@code AndroidRuntimeException: bad thread, need main thread}。
     * 而唤醒回调来自引擎自己的线程，应答链路就长在那条线程上，所以 SDK 调用一律
     * 要经过这里。</p>
     *
     * <p>为什么是"等它跑完"而不是"投递完就走"：{@code asr.start} 必须先真正生效，
     * 后面 {@link #startReaper} 开的静音计时才有意义；否则会出现"计时在跑、麦还没开"，
     * 收尾时必然拿不到文本。同理 {@code asr.stop} 不等它就取不到 finish 结果。</p>
     *
     * <p>超时不抛到调用栈外面 —— 主线程被 TTS / 动作占住时宁可这一轮判失败，
     * 也不能把 {@code answerLoop} 永久挂住。</p>
     *
     * @param what 出错信息里带上的动作名（asr.start / asr.stop），便于对日志
     */
    private void runOnMain(final Runnable r, long timeoutMs, final String what) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) { r.run(); return; }
        final CountDownLatch latch = new CountDownLatch(1);
        final Object[] err = new Object[1];
        boolean posted = main.post(new Runnable() {
            @Override public void run() {
                try { r.run(); }
                catch (Throwable t) { err[0] = t; }
                finally { latch.countDown(); }
            }
        });
        if (!posted) throw new Exception(what + " 投递主线程失败（Handler 已关闭）");
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new Exception(what + " 主线程执行超时 " + timeoutMs + "ms");
        }
        Object e = err[0];
        if (e == null) return;
        // 拆掉自己包的那层 RuntimeException，把 SDK 的原话原样抛出去，
        // 这样 chain(t) 出来的报错和日志里能直接看到真正的原因。
        if (e instanceof RuntimeException && ((RuntimeException) e).getCause() != null) {
            Throwable c = ((RuntimeException) e).getCause();
            if (c instanceof Exception) throw (Exception) c;
            throw new Exception(String.valueOf(c));
        }
        if (e instanceof Exception) throw (Exception) e;
        throw new Exception(String.valueOf(e));
    }

    private Object ensureEngine() throws Exception {
        if (engine != null) return engine;
        Class<?> emf = Class.forName(EMF_CLS);
        Method create = emf.getMethod("create", Context.class, String.class);
        engine = create.invoke(null, ctx, "asr");
        Object l = makeEventListener();
        // ★ 必须按**精确签名**取，不能用"第一个叫这名字的"（见 exactMethod 的说明）。
        Class<?> elc = Class.forName(EL_CLS);
        Method reg = exactMethod(engine, "registerListener", elc);
        if (reg == null) throw new Exception("EventManager.registerListener(" + EL_CLS + ") 不存在");
        reg.invoke(engine, l);
        BootLog.i("对话通路 EventManager(\"asr\") 就绪 impl=" + engine.getClass().getName());
        return engine;
    }

    private Object makeEventListener() throws Exception {
        if (elProxy != null) return elProxy;
        elCls = Class.forName(EL_CLS);
        elProxy = Proxy.newProxyInstance(elCls.getClassLoader(), new Class<?>[]{elCls},
                new InvocationHandler() {
                    @Override public Object invoke(Object p, Method m, Object[] a) {
                        // ★★ 代理必须自己实现 Object 的三个方法（Java 代理的经典陷阱）。
                        //    踩过的坑：EventManagerAsrWrapper.registerListener 会把监听器
                        //    放进 HashMap —— 而 HashMap.put 第一件事就是调 key.hashCode()。
                        //    对所有非 onEvent 的方法一律 return null，于是
                        //    "null 拆箱成 int" 直接抛
                        //    NullPointerException: Expected to unbox a 'int' primitive type
                        //    but was returned null，栈里只看到
                        //    $Proxy.hashCode <- Collections.secondaryHash <- HashMap.put，
                        //    完全看不出是"监听器注册"这一步，排查绕了一大圈。
                        //  ★ v76 起统一收口到 Proxies（这套规矩以前只在本类里手写，
                        //    AsrProbe 没抄到，于是它的 /asrtest 从来没成功过一次）。
                        Object g = Proxies.guard(p, m, a);
                        if (g != Proxies.UNHANDLED) return g;
                        String n = m.getName();
                        if ("onEvent".equals(n)) {
                            String name = (a != null && a.length > 0 && a[0] != null)
                                    ? String.valueOf(a[0]) : "";
                            String params = (a != null && a.length > 1 && a[1] != null)
                                    ? String.valueOf(a[1]) : "";
                            try { handleEvent(name, params); }
                            catch (Throwable t) { BootLog.w("对话通路 事件异常 " + t); }
                        }
                        return Proxies.nil(m);
                    }
                });
        return elProxy;
    }

    private void send(Object em, String name, String params) throws Exception {
        // ★ 精确签名：send 在 SDK 里有重载（第 2 个参数可能是 String，也可能是 JSONObject，
        //   还可能带 JSONArray 版本）。挑错重载时 SDK 报的是"参数类型不对"，
        //   听起来完全不像"方法选错了"，排查会绕大圈。直接点名叫这个签名，不留歧义。
        Method m = exactMethod(em, "send",
                String.class, String.class, byte[].class, int.class, int.class);
        if (m == null) {
            m = exactMethod(em, "send", String.class, org.json.JSONObject.class,
                    byte[].class, int.class, int.class);
            if (m == null) throw new Exception("EventManager.send 没有可用签名");
            m.invoke(em, name, params == null ? null : new JSONObject(params), null, 0, 0);
            return;
        }
        m.invoke(em, name, params, null, 0, 0);
    }

    /**
     * 按<b>精确参数类型</b>在目标对象（含父类、含接口）上找方法；找不到返回 null。
     *
     * <p>为什么不用 {@code getMethod(name, types)}：那个只找 <b>public</b> 方法，SDK 里有些
     * 实现类是包私有、方法也不一定 public，会直接抛 {@code NoSuchMethodException}。</p>
     *
     * <p>为什么不用"按名字撞第一个"（老写法）：{@code getDeclaredMethods()} 的顺序
     * <b>不保证</b>，{@code send} 又确实有多个重载。撞错的时候报出来的是类型/拆箱错误，
     * 而不是"找不到方法"，非常容易被误判成授权或线程问题——这台机器上已经因此
     * 多绕了一轮（{@code Expected to unbox a 'int' primitive type but was returned null}）。
     * 名字相同但签名不同的方法，本来就不是同一个东西，必须点名要哪一个。</p>
     */
    private static Method exactMethod(Object o, String name, Class<?>... types) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            Method m = pick(c.getDeclaredMethods(), name, types);
            if (m != null) { m.setAccessible(true); return m; }
            for (Class<?> i : c.getInterfaces()) {
                m = pick(i.getMethods(), name, types);
                if (m != null) { m.setAccessible(true); return m; }
            }
        }
        return null;
    }

    private static Method pick(Method[] all, String name, Class<?>[] types) {
        for (Method m : all) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != types.length) continue;
            boolean ok = true;
            for (int i = 0; i < ps.length; i++) {
                if (!ps[i].equals(types[i])) { ok = false; break; }
            }
            if (ok) return m;
        }
        return null;
    }

    private static String chain(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String s = c.toString();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /**
     * 根因的调用栈（拼成一行，给机器人自己的日志看）。
     *
     * <p>为什么需要它：{@link #chain} 只留最深那层的一句话，而这台机器上踩过的坑
     * 恰恰是"最深那句看着没头没脑"——比如
     * {@code NullPointerException: Expected to unbox a 'int' primitive type but was returned null}，
     * 光看这句根本分不清是 License 那次反射、建引擎还是 send 出的问题。
     * 有了前几帧，一眼就能落到具体那一行。<b>只保留 6 帧</b>，因为机器人日志是 80 行
     * 定长窗口，栈太长会把有用信息挤出去。</p>
     */
    static String stack(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        StringBuilder sb = new StringBuilder(root.getClass().getSimpleName());
        StackTraceElement[] st = root.getStackTrace();
        for (int i = 0; i < st.length && i < 6; i++) {
            StackTraceElement e = st[i];
            String cn = e.getClassName();
            int dot = cn.lastIndexOf('.');
            if (dot > 0) cn = cn.substring(dot + 1);
            sb.append(" <- ").append(cn).append('.').append(e.getMethodName())
              .append(':').append(e.getLineNumber());
        }
        return sb.toString();
    }

    /**
     * 反射实况：把「真正拿到的引擎对象是什么类、它身上叫这个名字的方法有哪些」原样吐出来。
     *
     * <p>为什么需要它：{@code EventManager} 是个接口，同一个名下有多个实现类，而且
     * {@code send} 有重载（{@code (String,String,byte[],int,int)} 与
     * {@code (String,JSONObject,byte[],int,int)}）。{@code Class.getDeclaredMethods()}
     * 的顺序<b>不保证</b>，谁先被"按名字撞"的老写法撞上就调用谁——一旦撞错重载，
     * 报出来的往往是"类型不对"，而不是"找不到方法"，很容易被误判成授权问题。
     * 这里把事实摆出来，不再靠猜。</p>
     */
    public String reflectDump() {
        // 还没建过引擎就先建一个（走主线程，和正常链路同一条路），否则这里什么都看不到。
        if (engine == null) {
            try {
                runOnMain(new Runnable() {
                    @Override public void run() {
                        try { ensureEngine(); }
                        catch (Throwable t) { throw new RuntimeException(t); }
                    }
                }, MAIN_CALL_TIMEOUT_MS, "refl-create");
            } catch (Throwable ignore) {}
        }
        StringBuilder sb = new StringBuilder("{\"cached\":\"");
        sb.append(engine == null ? "" : J.esc(engine.getClass().getName())).append('"');
        sb.append(",\"i18n\":\"");
        try {
            Class<?> iface = Class.forName(EMF_CLS);
            Method create = iface.getMethod("create", Context.class, String.class);
            sb.append(J.esc(create.getReturnType().getName()));
        } catch (Throwable t) {
            sb.append("ERR:").append(J.esc(String.valueOf(t)));
        }
        sb.append('"');
        // 接口自己声明了什么（这是"合约"，实现类必须对得上）
        sb.append(",\"iface_methods\":[");
        boolean f2 = true;
        try {
            for (Method m : Class.forName("com.baidu.speech.EventManager").getMethods()) {
                if (!f2) sb.append(',');
                f2 = false;
                sb.append("{\"name\":\"").append(m.getName()).append("\",\"params\":\"");
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(ps[i].getSimpleName());
                }
                sb.append("\",\"ret\":\"").append(m.getReturnType().getSimpleName()).append("\"}");
            }
        } catch (Throwable ignored) {}
        sb.append(']');
        sb.append(",\"methods\":[");
        boolean first = true;
        if (engine != null) {
            for (Class<?> c = engine.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!"send".equals(m.getName()) && !"registerListener".equals(m.getName())) continue;
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"in\":\"").append(J.esc(c.getSimpleName()))
                      .append("\",\"name\":\"").append(m.getName())
                      .append("\",\"params\":\"");
                    Class<?>[] ps = m.getParameterTypes();
                    for (int i = 0; i < ps.length; i++) {
                        if (i > 0) sb.append(',');
                        sb.append(ps[i].getSimpleName());
                    }
                    sb.append("\",\"ret\":\"").append(m.getReturnType().getSimpleName()).append("\"}");
                }
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    // ---------------------------------------------------------------- 诊断

    /** 单行 JSON，给 HTTP {@code /dialog} 看。 */
    public String stateJson() {
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"active\":").append(active);
        sb.append(",\"enabled\":").append(enabled);
        sb.append(",\"turn\":").append(turn);
        sb.append(",\"max_ms\":").append(maxListenMs);
        sb.append(",\"silence_ms\":").append(silenceMs);
        sb.append(",\"max_turns\":").append(maxTurns);
        // ★★★ v178：播完自动续听（用户 2026-10-06）。放在这里而不是各写一份：
        //   GET /dialog 是这条链路的自述端点，验收就照它读。
        sb.append(",\"followup\":").append(AgentService.autoFollowup);
        sb.append(",\"saw_speech\":").append(sawSpeech);
        sb.append(",\"partial\":\"").append(J.esc(lastPartial)).append('"');
        sb.append(",\"partials\":").append(partialCount);
        sb.append(",\"volume\":").append(volume);
        sb.append(",\"vol_max\":").append(volMax);
        sb.append(",\"loud\":").append(loudLevel);
        sb.append(",\"note\":\"").append(J.esc(finishNote)).append('"');
        sb.append(",\"since_activity_ms\":")
          .append(lastActivityMs == 0 ? -1 : (System.currentTimeMillis() - lastActivityMs));
        sb.append(",\"exit\":\"").append(J.esc(lastExit)).append('"');
        sb.append(",\"vad\":\"").append(J.esc(vadMode)).append('"');
        sb.append(",\"decoder\":").append(decoderMode);
        sb.append(",\"mic_handed\":").append(micHanded);
        sb.append(",\"continuous\":").append(continuous);
        sb.append(",\"grammar_used\":\"").append(J.esc(grammarOverride == null ? "cmd"
                : (grammarOverride.isEmpty() ? "free(不给语法)" : grammarOverride))).append('"');
        // ★ 区分「当前错误」和「历史错误」。
        //
        //   lastError 只在 start() 成功时才会被清空，所以一次失败会一直挂在那里：
        //   会话早就结束了（active=false、麦克风已交还、唤醒也恢复了），
        //   /dialog 里却仍然写着 "会话已在跑（上一次没收干净）"。
        //   下游拿着这个字段当"现在坏着"用，就必然误判 —— 实测
        //   _asr_e2e.py 的"会话前无残留 error"就是这么假失败了一整轮。
        //
        //   所以：error 只在**会话进行中**、或**刚发生不久**（ERROR_CURRENT_MS 内）
        //   才算"当前"；更老的一律降级成 error_last，并给出 error_ms 年龄。
        //   不删不改原文，只补事实，免得连"刚发生了什么"都看不见。
        sb.append(",\"error\":\"").append(J.esc(lastError)).append('"');
        sb.append(",\"error_fresh\":").append(errorIsCurrent());
        sb.append(",\"error_ms\":").append(lastError == null || lastError.isEmpty()
                ? 0 : errorAgeMs());
        sb.append('}');
        return sb.toString();
    }

    /** 一条错误超过这个年龄、且会话已不在跑，就只算历史，不算"当前错误"。 */
    private static final long ERROR_CURRENT_MS = 20000L;

    /** lastError 是否代表**当前**故障状态（见 stateJson 里的说明）。 */
    private boolean errorIsCurrent() {
        if (lastError == null || lastError.isEmpty()) return false;
        return active || errorAgeMs() < ERROR_CURRENT_MS;
    }

    /**
     * lastError 距今多久（ms）。
     *
     * <p>用「字符串变了就重新计时」来打时间戳，而不是去改十几处赋值点 ——
     * 赋值点里有两处在 chain(t) 之后、一处在锁里，逐个包一层反而更容易漏。
     * 这里只在被读取时比对，副作用局限在诊断字段上。</p>
     */
    private long errorAgeMs() {
        String e = lastError;
        long now = System.currentTimeMillis();
        if (!e.equals(errStamp)) { errStamp = e; errStampMs = now; }
        return now - errStampMs;
    }

    private String errStamp = "";
    private long errStampMs;

    /** 生成引擎需要的一堆路径（装配点统一放这里，免得调用方各自拼错）。 */
    static VoiceDialog create(Context ctx, Runnable licenseHook) {
        String res = ctx.getApplicationInfo().nativeLibraryDir + "/" + AsrProbe.RES_NAME;
        String lic = new File(ctx.getFilesDir(), "bds-easr-license.lic").getAbsolutePath();
        VoiceDialog d = new VoiceDialog(ctx, licenseHook, lic, res, AsrProbe.GRAMMAR_ASSET);
        // ★ v93：扩充词表要在**重启后仍然生效**。
        //
        // 原厂 assets 里那份词表只有 53 条固定命令（已用 decoder 0..6 全扫证实在词表外的
        // 句子一律 error=7），而 `POST /dialog {"grammar":…}` 设的 grammarOverride 只是
        // 个**进程内的静态字段** —— 服务一重启就悄悄退回 53 条，现场表现就是
        // "昨天还好用，今天它又听不懂了"，且没有任何报错。
        // 所以这里在装配时主动看一眼有没有外置词表，有就用它。
        // 词表由 `_grammar.py on` 生成并 push 到 /sdcard/ledi_grammar.bsg。
        try {
            File ext = new File(GRAMMAR_SDCARD);
            if (ext.exists() && ext.length() > 0) {
                grammarOverride = "file://" + GRAMMAR_SDCARD;
                BootLog.i("对话通路 外置词表生效: " + grammarOverride
                        + "（" + ext.length() + " 字节）");
            } else {
                BootLog.i("对话通路 用内置词表（未发现 " + GRAMMAR_SDCARD + "）");
            }
        } catch (Throwable t) {
            BootLog.w("对话通路 检查外置词表失败: " + t);
        }
        return d;
    }

    /** 外置扩充词表的固定位置（由 `_grammar.py on` 推送）。 */
    static final String GRAMMAR_SDCARD = "/sdcard/ledi_grammar.bsg";
}
