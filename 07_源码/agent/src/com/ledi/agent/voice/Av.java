package com.ledi.agent.voice;

import com.ledi.agent.core.AgentService;
import com.ledi.agent.sdk.FaceController;
import com.ledi.agent.core.J;
import com.ledi.agent.ui.SleepMode;
import com.ledi.agent.ui.SleepScreen;

import android.content.Context;
import android.media.AudioManager;

/**
 * 音量（{@code STREAM_MUSIC}）的统一出口。
 *
 * <h3>为什么要单独一个类，而不是各写各的</h3>
 * "音量"这条链在这台机器上已经有**四个**入口：语音本地直通（{@link IntentResolver}）、
 * 网页控制台、脚本 {@code POST /volume}、以及 AI 下发的 {@code [音量:]} 标签。
 * 各写一份 {@code setStreamVolume} 必然会分叉（有的夹上下限、有的不夹；有的回读、有的不回读），
 * 而这个项目的教训是"**同一条规则散在多处，迟早对不上**"（见 {@code SleepScreen.bright}
 * 与 {@code SleepMode.bright} 各存一份的那次）。
 * ⇒ 统一走这里，{@link FaceController#volume} 也改成委托过来。
 *
 * <h3>诚实口径</h3>
 * {@link #set} 设置之后**回读**并把 {@code ok} 算成 {@code 读回值 == 请求值} ——
 * 不认"调用没抛异常就算成功"。本机实测 {@code STREAM_MUSIC} 上限 15，
 * 且音量是**按设备**存的（{@code dumpsys audio} 里 {@code (default): 8, 2 (speaker): 10}），
 * 所以回读值与请求值不一致是有可能的，那种情况必须如实报出来。
 */
public final class Av {

    /** 语音"大声一点/小声一点"一步走多少（上限 15，2 是"听得出来但不过头"）。 */
    public static final int STEP = 2;

    private Av() {}

    private static AudioManager am() {
        try {
            Context c = AgentService.I;
            return c == null ? null : (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 当前音量，读不到返回 -1。 */
    public static int get() {
        AudioManager a = am();
        if (a == null) return -1;
        try {
            return a.getStreamVolume(AudioManager.STREAM_MUSIC);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 上限，读不到返回 -1。 */
    public static int max() {
        AudioManager a = am();
        if (a == null) return -1;
        try {
            return a.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static int clamp(int v) {
        int m = max();
        if (v < 0) v = 0;
        if (m > 0 && v > m) v = m;
        return v;
    }

    /** 设成绝对值；返回**回读后**的结果。 */
    public static String set(int v) {
        AudioManager a = am();
        if (a == null) return "{\"ok\":false,\"error\":\"拿不到 AudioManager\"}";
        int m;
        try {
            m = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"读不到音量上限: " + J.esc(String.valueOf(t)) + "\"}";
        }
        if (v < 0) v = 0;
        int want = v > m ? m : v;
        String err = "";
        try {
            a.setStreamVolume(AudioManager.STREAM_MUSIC, want, 0);
        } catch (Throwable t) {
            err = String.valueOf(t);
        }
        int back = get();
        boolean ok = (back == want) && err.isEmpty();
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"ok\":").append(ok)
          .append(",\"volume\":").append(back)
          .append(",\"requested\":").append(want)
          .append(",\"max\":").append(m);
        if (!err.isEmpty()) sb.append(",\"error\":\"").append(J.esc(err)).append('"');
        sb.append('}');
        return sb.toString();
    }

    /** 相对调整（语音走这条）：基于**当前读回值**加 delta。 */
    public static String adjust(int delta) {
        int cur = get();
        if (cur < 0) return "{\"ok\":false,\"error\":\"读不到当前音量\"}";
        return set(cur + delta);
    }

    public static String json() {
        int cur = get(), m = max();
        return "{\"ok\":" + (cur >= 0) + ",\"volume\":" + cur + ",\"max\":" + m
                + ",\"step\":" + STEP
                + ",\"stream\":\"STREAM_MUSIC\"}";
    }
}
