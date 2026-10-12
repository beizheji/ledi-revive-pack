package com.ledi.agent.cap;

import com.ledi.agent.core.BootLog;

import com.ledi.agent.sdk.FaceController;

import com.ledi.agent.motion.MotionController;
import com.ledi.agent.voice.ReactionEngine;
import com.ledi.agent.sense.SensorHub;
import com.ledi.agent.voice.TtsController;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * ProactiveTalk —— 机器人「主动说话」引擎（newcore 重构）。
 *
 * 需求：机器人必须主动说话。触发机制 = 传感器事件 + 定时，两者结合：
 *
 *   ① 传感器事件触发（即时）：被摸头 / 被拿起 / 被摇晃 / 被摸脸 / 低电量 …
 *     —— 由 SensorHub 上报事件，立刻让 AI 生成一句应景的话说出来。
 *
 *   ② 定时触发（空闲话题）：长时间没人理它，隔一段时间主动找话题说一句
 *     （配合表情/动作，避免「哑巴机器人」）。
 *
 * 说话走机器人自带语音（TtsController，讯飞优先）；话题由 AiCore 生成。
 * 同类触发 3 秒内只做一次，避免连续触摸抖成一片。
 *
 * 与旧 ReactionEngine 的区别：旧的是「写死的本地反应」；这个是「AI 生成的主动说话」，
 * 完全由 AI 接管，去除原厂/硬编码行为。
 */
public class ProactiveTalk {

    private static final String PREFS = "ledi_agent";

    /** prefs 里的开关版本号：v144 起主动推送默认关，老设备靠它做**一次性迁移**。 */
    private static final int PTALK_REV = 2;

    private final Context ctx;
    private final AiCore ai;
    private final TtsController tts;
    private final FaceController face;
    private final MotionController motion;
    private final SensorHub sensor;

    /** AI 标签剥离执行器：输入带 [表情]/[动作]/[灯光] 的原文，输出可播报的纯文本。 */
    public interface TagHandler { String apply(String raw); }
    private volatile TagHandler tagHandler;

    /**
     * "现在不许开口"的判据，由 {@code AgentService} 注入（实现 = 对话正占麦 / 正应答）。
     *
     * <p>★ 为什么必须有这道闸：机器人自己的喇叭走 <b>AEC</b>，**它一开口，自家麦克风里的
     * 近端人声会连着被压掉**。实测（v107 整链验收）：云端回 {@code 10118 您好像没有说话哦}，
     * 而设备侧同时是 {@code rms_max=3965 / saw_speech=true / write_fails=0} —— 看着"有声"、
     * 云端听着"不是人话"。根因就是 {@code via=proactive} 的机械空闲闲聊（摸脸/被拿起/定时）
     * 与用户说话撞在了同一时刻。</p>
     *
     * <p>★ 为什么"让路"要做在三个点上：只挡开头不够 —— 一次主动闲聊能持续好几秒，
     * 正好盖满整个听写窗口；而 AI 取词要 2～5s，期间用户完全可能刚被唤醒。</p>
     */
    public interface BusyGate { boolean busy(); }
    private volatile BusyGate busyGate;

    public void setBusyGate(BusyGate g) { busyGate = g; }

    /** 让路判据（异常一律当作"不忙"，宁可多说一句也不能把主动说话整个哑掉）。 */
    private boolean busyElsewhere() {
        BusyGate g = busyGate;
        try { return g != null && g.busy(); } catch (Throwable ignored) { return false; }
    }

    private volatile boolean enabled = true;        // 总开关
    private volatile boolean eventEnabled = true;   // 传感器触发
    private volatile boolean idleEnabled = true;    // 定时触发

    /**
     * 用户刚聊过之后，"心跳闲聊"闭嘴多久。
     *
     * <p>★ 为什么要有它（用户 2026-09-22 明确要求）：心跳闲聊是**无由头**开口，
     * 刚和用户聊完再自己搭话，体感就是"抢话/烦人"；而它一开口还会连带压制近端人声
     * （见 {@link BusyGate}）。所以对话结束后给它一段静默期。</p>
     */
    private static final long DEFAULT_USER_QUIET_MS = 5 * 60 * 1000L;   // 5 分钟

    /** 静默期时长，可调（验收时缩短，免得等 5 分钟）。 */
    private volatile long userQuietMs = DEFAULT_USER_QUIET_MS;

    /** 静默期截止时刻（{@code System.currentTimeMillis()}）。0 = 从未对话过。 */
    private volatile long userQuietUntil = 0;

    // 限流
    private volatile String lastKind = "";
    private volatile long lastTs = 0;

    // 定时
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable idleRunner = new Runnable() {
        @Override public void run() {
            if (!enabled || !idleEnabled) return;
            if (busyElsewhere()) { handler.postDelayed(this, 15000); return; }  // 对话正占麦，别抢喇叭
            if (userQuietLeftMs() > 0) {                                        // 刚聊过：心跳闲聊先歇着
                handler.postDelayed(this, 15000);
                return;
            }
            long idleMs = SystemClock.elapsedRealtime() - lastTalkElapsed;
            if (idleMs >= idleIntervalMs()) {
                // ★ v143：提示词来自 Tone（成人化）。旧文案是写死的
                //   "长时间没人理你，主动和主人搭句话" —— 在这个语境下最容易让模型
                //   滑回撒娇/卖惨，所以成人档额外带了禁止项（见 Tone.idleHint）。
                doProactive("idle", Tone.idleHint());
            }
            handler.postDelayed(this, 15000);   // 每 15s 检查一次
        }
    };

    private volatile long lastTalkElapsed = 0;

    public ProactiveTalk(Context ctx, AiCore ai, TtsController tts,
                         FaceController face, MotionController motion, SensorHub sensor) {
        this.ctx = ctx;
        this.ai = ai;
        this.tts = tts;
        this.face = face;
        this.motion = motion;
        this.sensor = sensor;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // ★★★ v144（用户 2026-09-24 明确）：「取消主动推送，仅用 LED 闪烁表示机器人在听」。
        //   ⇒ 三个开关默认值全改 false。★ 而且**必须做一次 prefs 迁移**：旧版本已经把
        //   true 写进了 prefs，光改默认值**毫无作用**（已存值优先于默认值）—— 那正是
        //   "改了等于没改"的假绿灯。用 ptalk_rev 打版本，升上来一次性关掉；
        //   用户之后手动 POST /ptalk {"on":true} 仍可开（开关保留，不删代码）。
        int rev = sp.getInt("ptalk_rev", 1);
        if (rev < PTALK_REV) {
            sp.edit().putBoolean("ptalk_on", false)
                    .putBoolean("ptalk_event", false)
                    .putBoolean("ptalk_idle", false)
                    .putInt("ptalk_rev", PTALK_REV).apply();
            BootLog.i("主动推送：按 v144 迁移为默认关闭（ptalk_rev " + rev + " -> "
                    + PTALK_REV + "）");
        }
        enabled = sp.getBoolean("ptalk_on", false);
        eventEnabled = sp.getBoolean("ptalk_event", false);
        idleEnabled = sp.getBoolean("ptalk_idle", false);
        // 注册传感器监听
        if (sensor != null) {
            // addListener（不是 setListener）：ReactionEngine 也注册了同一个事件源，
            // 之前用 setListener 会把它顶掉，见 SensorHub 里的注释。
            sensor.addListener(new SensorHub.Listener() {
                @Override public void onSensorEvent(String action) { onEvent(action); }
            });
        }
    }

    /**
     * 被更高优先级的事情插了队（例如抹头顶强制唤醒打断）。
     * 作用：把"空闲计时"清零，并短暂静默 —— 否则用户刚摸完头顶、机器人正回话，
     * 主动说话的空闲逻辑可能紧接着又让它开口，两句叠在一起。
     */
    public void noteInterrupt() {
        lastTalkElapsed = SystemClock.elapsedRealtime();
        lastTs = System.currentTimeMillis();
        quietUntil = lastTs + 12000;
        BootLog.i("主动说话：被抹头顶打断，静默 12 秒并重置空闲计时");
    }

    private volatile long quietUntil = 0;

    public void setTagHandler(TagHandler h) { this.tagHandler = h; }

    public void start() {
        lastTalkElapsed = SystemClock.elapsedRealtime();
        // ★ v144：总开关关着就**别挂心跳定时器** —— 用户要的是"不主动说话"，
        //   留一个每 15s 醒一次的轮询纯属白烧电（见 §十六 省电基线：屏保 0.03 核）。
        if (!enabled) {
            BootLog.i("主动推送：总开关关闭，心跳定时器不启动（要开走 POST /ptalk {\"on\":true}）");
            return;
        }
        handler.postDelayed(idleRunner, 10000);
    }

    public void stop() {
        handler.removeCallbacks(idleRunner);
    }

    // ================= 配置 =================

    private long idleIntervalMs() {
        // 默认 3 分钟；设置里可改（秒）
        int s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt("ptalk_idle_sec", 180);
        if (s < 30) s = 30;
        return s * 1000L;
    }

    // =================★ 对话后静默（用户要求：聊完 5 分钟内别再心跳闲聊） =================

    /**
     * 记一次"用户刚和我聊过"——随后 {@link #userQuietMs} 内**心跳闲聊闭嘴**。
     *
     * <p>由 {@code AiCore.pushDialog} 在 {@code via=voice|ask} 时调用，
     * 所以对话/常识别/电脑端提问三条路只要走了对话日志就都会生效，不必在各处分别埋点。</p>
     *
     * <p>注意：<b>不</b>掐传感器触发（摸脸/被拿起等）——那是用户主动碰它、属于直接互动；
     * 但所有主动说话仍受 {@link BusyGate} 让路约束。</p>
     */
    public void noteUserTalk() {
        userQuietUntil = System.currentTimeMillis() + userQuietMs;
    }

    /** 静默期还剩多少毫秒（0 = 不在静默期）。用于 /ptalk 观测与验收。 */
    public long userQuietLeftMs() {
        long left = userQuietUntil - System.currentTimeMillis();
        return left > 0 ? left : 0;
    }

    /**
     * 调静默期时长（秒）；{@code <=0} 表示不静默。
     *
     * <p>★ 必须同时"收短已经点着的那个窗"：只改 {@link #userQuietMs} 的话，
     * 先前 {@link #noteUserTalk()} 定下的**截止时刻还挂在未来**，于是
     * {@code quiet_sec=0} 之后 {@code userQuietLeftMs()} 照样 >0 ——
     * 验收时会看到"我已经把静默期关了，心跳却还是不出声"，很容易误判成代码没生效。</p>
     */
    public void setUserQuietSec(int sec) {
        userQuietMs = sec <= 0 ? 0 : sec * 1000L;
        if (userQuietUntil > 0) {
            long cap = System.currentTimeMillis() + userQuietMs;
            if (userQuietUntil > cap) userQuietUntil = cap;
        }
    }

    public boolean enabled() { return enabled; }
    public boolean eventEnabled() { return eventEnabled; }
    public boolean idleEnabled() { return idleEnabled; }

    public String set(Boolean on, Boolean ev, Boolean idle) {
        if (on != null) {
            boolean was = enabled;
            enabled = on;
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean("ptalk_on", on).apply();
            // 从关到开：心跳定时器已在 start() 里被判掉，这里补挂一次（幂等，重复 post 无害）
            if (on && !was) start();
        }
        if (ev != null) {
            eventEnabled = ev;
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean("ptalk_event", ev).apply();
        }
        if (idle != null) {
            idleEnabled = idle;
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean("ptalk_idle", idle).apply();
        }
        return json();
    }

    public String json() {
        return "{\"on\":" + enabled + ",\"event\":" + eventEnabled
                + ",\"idle\":" + idleEnabled
                + ",\"idle_sec\":" + (idleIntervalMs() / 1000)
                + ",\"user_quiet_s\":" + (userQuietLeftMs() / 1000)
                + ",\"user_quiet_set_s\":" + (userQuietMs / 1000) + "}";
    }

    // ================= 传感器事件触发 =================

    void onEvent(String action) {
        if (!enabled || !eventEnabled) return;
        if (action == null) return;
        // ★ 让路要放在 ai.ask 之前：否则白花一次模型调用，而且那 2～5s 里
        //   用户可能刚好被唤醒 —— 等拿到词再挡就已经晚了（真机上就是这么撞上的）。
        if (busyElsewhere()) {
            BootLog.i("主动说话：对话正占麦/正应答，跳过一次触发（" + action + "）");
            return;
        }
        String kind = classify(action);
        if (kind == null) return;

        long now = System.currentTimeMillis();
        if (kind.equals(lastKind) && now - lastTs < 3000) return;   // 同类 3 秒限流
        lastKind = kind;
        lastTs = now;

        String ctxHint = sensorContext();
        final String topic = stripTags(ai.ask("", ctxHint + "（触发：" + kind + "）"));
        if (topic == null || topic.trim().isEmpty()) return;
        speak(topic, kind);
    }

    private String classify(String action) {
        // ★ 头顶触摸（FACE_HEAD_DOWN）**故意不在这里处理**：它由 SensorHub 的
        //   PatListener → AgentService 的"抹头顶强制唤醒打断"接管（更高优先级，
        //   不受 enabled/eventEnabled 开关约束）。两边都处理的话，摸一下头会收到
        //   两句 AI 回复并互相打断。摸脸/被拿起/被摇晃/跌倒/低电量仍走这里。
        if (SensorHub.FACE_L_DOWN.equals(action) || SensorHub.FACE_R_DOWN.equals(action)) return "摸脸";
        if (action.endsWith(".takeup")) return "被拿起";
        if (action.endsWith(".shake")) return "被摇晃";
        if (action.endsWith(".trip") || action.endsWith(".overturn")) return "跌倒";
        if (SensorHub.LOW_BATTERY.equals(action)) return "低电量";
        return null;
    }

    // ================= 定时触发 =================

    private void doProactive(String kind, String hint) {
        if (!enabled) return;
        // 心跳闲聊：用户刚聊过就闭嘴（窗内连模型都不问，省一次调用）
        if ("idle".equals(kind) && userQuietLeftMs() > 0) {
            lastTalkElapsed = SystemClock.elapsedRealtime();
            return;
        }
        if (busyElsewhere()) {
            lastTalkElapsed = SystemClock.elapsedRealtime();
            return;
        }
        lastTalkElapsed = SystemClock.elapsedRealtime();
        String topic = stripTags(ai.ask("", sensorContext() + "（" + hint + "）"));
        if (topic == null || topic.trim().isEmpty()) return;
        speak(topic, kind);
    }

    /** 用注入的标签执行器剥离 [表情]/[动作]/[灯光]，拿到可直接播报的纯文本。 */
    private String stripTags(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        TagHandler h = tagHandler;
        return h == null ? raw : h.apply(raw);
    }

    // ================= 说话（机器人自带语音） =================

    private void speak(String text, String kind) {
        // ★ 最后一道闸（权威）：上面两处早退是为了省一次模型调用，
        //   但 ai.ask 要走 2～5s，这期间用户完全可能刚被唤醒 —— 所以开喇叭前必须再确认一次。
        if (busyElsewhere()) {
            BootLog.i("主动说话：对话正占麦/正应答，让路跳过（" + kind + "）");
            lastTalkElapsed = SystemClock.elapsedRealtime();
            return;
        }
        // 被抹头顶打断后的静默窗口内不开口（见 noteInterrupt）
        if (System.currentTimeMillis() < quietUntil) {
            BootLog.i("主动说话：静默期内跳过（" + kind + "）");
            lastTalkElapsed = SystemClock.elapsedRealtime();
            return;
        }
        // 说话的同时配一个表情，让机器人「活」起来
        try {
            String eng = tts.speak(text, false);
            if (eng == null || eng.isEmpty()) {
                // 本地 TTS 不可用，退而求其次只做表情动作（至少不哑）
                face.play(fallbackFace(kind));
            } else {
                face.play(fallbackFace(kind));
            }
            lastTalkElapsed = SystemClock.elapsedRealtime();
            // 对话日志：主动说话（事件触发/定时/空转）也要进电脑端的流水，
            // 否则控制台里会看到机器人自己开口却没有记录，排查时最容易被误导。
            if (ai != null) ai.pushDialog("proactive", kind, text, null, fallbackFace(kind), null);
        } catch (Exception ignored) {}
    }

    /**
     * 说话时顺带配的表情。
     *
     * <p>★ v143：改由 {@link Tone#faceFor} 提供 —— 成人化不只是换台词，
     * 表情搭配也得跟上：摸脸配 {@code shy}（含羞）是典型卖萌组合，
     * 和"脸别乱摸"这句成年人的不耐烦完全对不上。</p>
     */
    private String fallbackFace(String kind) {
        return Tone.faceFor(kind);
    }

    private String sensorContext() {
        return sensor == null ? "" : sensor.summary();
    }
}
