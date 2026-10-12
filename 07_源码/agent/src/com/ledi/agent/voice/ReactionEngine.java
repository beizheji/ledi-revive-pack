package com.ledi.agent.voice;

import com.ledi.agent.sdk.FaceController;

import com.ledi.agent.motion.MotionController;
import com.ledi.agent.cap.ProactiveTalk;
import com.ledi.agent.sense.SensorHub;
import com.ledi.agent.cap.Tone;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 机器人「自带反应」引擎。
 *
 * 一键开关（/switch）：
 *   ai      —— 是否让电脑端 AI 接管对话与主动反应（关掉 = 网关退回原厂透传）
 *   native  —— 是否启用机器人自带反应（本引擎，被摸头/被拿起/摇晃/低电量即时回反应）
 *
 * 自带反应不依赖网络：只用表情 + 动作（引擎本地资源），有网关时才补一句语音。
 * 同类反应 3 秒内只做一次，避免连续触摸时动作抖成一片。
 */
public class ReactionEngine {

    static final String PREFS = "ledi_agent";

    private final Context ctx;
    private final FaceController face;
    private final MotionController motion;
    private final Speaker speaker;
    private final SensorHub sensor;
    private volatile TtsController tts;      // 机器人自带语音合成（可为空）

    /** 由 AgentService 注入本地 TTS（自带反应的出声也走机器人自己的嗓子） */
    public void setTts(TtsController t) { this.tts = t; }

    private volatile boolean aiOn = true;
    private volatile boolean nativeOn = true;

    /**
     * 屏保临时闸。
     *
     * <p><b>刻意不走 {@link #set}、也不写 SharedPreferences</b>：{@code set()} 会持久化
     * {@code ai_on}/{@code native_on}，而屏保期间进程被 CrashGuard 重启是这台机器的常态 ——
     * 那样用户的自带反应开关就被一次屏保<b>永久</b>关掉了，而且没人知道是被谁改的。
     * 临时闸只活在内存里，进程一没就自动失效（默认放行），不会留下痕迹。
     */
    private volatile boolean sleepGate;

    public void setSleepGate(boolean on) {
        sleepGate = on;
        sensor.markExternal("屏保闸 " + (on ? "关（暂停自带反应）" : "开（恢复）"));
    }

    public boolean sleepGate() { return sleepGate; }

    private volatile String lastKind = "";
    private volatile long lastTs = 0;

    public ReactionEngine(Context ctx, FaceController face, MotionController motion,
                          Speaker speaker, SensorHub sensor) {
        this.ctx = ctx;
        this.face = face;
        this.motion = motion;
        this.speaker = speaker;
        this.sensor = sensor;
        SharedPreferences sp = prefs();
        aiOn = sp.getBoolean("ai_on", true);
        nativeOn = sp.getBoolean("native_on", true);
        // addListener（不是 setListener）：ProactiveTalk 也注册了同一个事件源，
        // 之前用 setListener 会被后建的 ProactiveTalk 顶掉，本引擎永远收不到事件
        // —— 控制台那两个开关就成了摆设。见 SensorHub 里的注释。
        sensor.addListener(new SensorHub.Listener() {
            @Override public void onSensorEvent(String action) { react(action); }
        });
    }

    private SharedPreferences prefs() { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public boolean aiOn() { return aiOn; }
    public boolean nativeOn() { return nativeOn; }

    /** 一键开关：ai / native；任一为空表示不改 */
    public String set(Boolean ai, Boolean native_) {
        if (ai != null) {
            aiOn = ai;
            prefs().edit().putBoolean("ai_on", ai).apply();
        }
        if (native_ != null) {
            nativeOn = native_;
            prefs().edit().putBoolean("native_on", native_).apply();
        }
        sensor.markExternal("开关变更 ai=" + aiOn + " native=" + nativeOn);
        return json();
    }

    public String json() {
        return "{\"ai\":" + aiOn + ",\"native\":" + nativeOn
                + ",\"sleep_gate\":" + sleepGate + "}";
    }

    /** 触摸/姿态事件 → 自带反应 */
    void react(String action) {
        if (sleepGate) return;      // 屏保：被拿起/摇晃/摸脸通通不反应（不写盘，见字段注释）
        if (!nativeOn) return;
        String kind = null;
        // ★ 头顶触摸不在这里：它由「抹头顶强制唤醒打断」接管（SensorHub.PatListener
        //   → AgentService），语义是"无论开关如何、无论正在干什么，立刻停下来理我"。
        //   这里再抢一份会变成"摸一下头收到两句回复 + 一个动作"。
        if (SensorHub.FACE_L_DOWN.equals(action) || SensorHub.FACE_R_DOWN.equals(action)) { kind = "face"; }
        else if (action.endsWith(".takeup")) { kind = "takeup"; }
        else if (action.endsWith(".shake")) { kind = "shake"; }
        else if (action.endsWith(".trip") || action.endsWith(".overturn")) { kind = "fall"; }
        else if (action.endsWith(".liedown")) { kind = "lie"; }
        else if (SensorHub.LOW_BATTERY.equals(action)) { kind = "lowbat"; }
        if (kind == null) return;

        long now = System.currentTimeMillis();
        if (kind.equals(lastKind) && now - lastTs < 3000) return;   // 同类反应限流
        lastKind = kind;
        lastTs = now;

        // ★ v143 成人化：台词与表情都取自 Tone（默认 adult 档），
        //   下面的字符串是 **child 档的原文**，也就是改造前的样子。
        //   Tone 在 child 档下 reaction() 返回 null ⇒ 这里回落成原文 ⇒
        //   一键就能 A/B 对照"同一句在成人档与儿童档下差多少"，也就能回退。
        switch (kind) {
            case "face":                     // 摸脸
                face.play(Tone.faceFor("摸脸"));
                say(line(kind, "哎呀，别挠我脸"));
                break;
            case "takeup":                   // 被拿起
                face.play(Tone.faceFor("被拿起"));
                motion.motion("dizzy");
                say(line(kind, "哇，起飞咯"));
                break;
            case "shake":                    // 被摇晃
                face.play(Tone.faceFor("被摇晃"));
                motion.motion("dizzy");
                say(line(kind, "哎哟别晃了，我头晕"));
                break;
            case "fall":                     // 跌倒/翻转
                motion.stop();
                face.play(Tone.faceFor("跌倒"));
                say(line(kind, "摔到我啦，扶我一下嘛"));
                break;
            case "lie":                      // 躺下
                face.play("drowsy");
                say(line(kind, "躺着真舒服"));
                break;
            case "lowbat":                   // 低电量
                face.play(Tone.faceFor("低电量"));
                say(line(kind, "电量不太够了，记得给我充电"));
                break;
            default:
                break;
        }
    }

    /**
     * 取该事件在当前调性档下要说的话。
     *
     * <p>{@link Tone#reaction} 在 child 档返回 {@code null}（表示"用调用方给的旧文案"）；
     * 其余档返回成人化的句子。这样"旧文案"在本项目里只存在一处，
     * 不会在 Tone 与 ReactionEngine 里各留一份、将来改歪其中一份。</p>
     */
    private String line(String kind, String legacy) {
        String t = Tone.reaction(kind);
        return t == null ? legacy : t;
    }

    /**
     * 优先机器人自带语音合成（讯飞/系统 TTS，无需电脑）；失败退回网关 TTS；
     * 都没有就只做表情动作。自带反应要即时出声，所以不等 onSpeakBegin。
     */
    private void say(String text) {
        try {
            if (tts != null) {
                String eng = tts.speak(text, false);
                if (eng != null && !eng.isEmpty()) return;
            }
            if (speaker.getGateway() == null || speaker.getGateway().isEmpty()) return;
            speaker.speak(text, null);
        } catch (Exception ignored) {}
    }
}
