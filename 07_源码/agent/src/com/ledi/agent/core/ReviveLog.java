package com.ledi.agent.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * 「我这条命是谁给的」—— 复活通道的<b>持久化账本</b>。
 *
 * <h3>为什么必须有它（没有它，修好了也不可证）</h3>
 * 2026-09-23 实测：{@code pm disable com.ledi.agent/.BrainActivity} 之后进程没了，
 * <b>整整 13 分钟没有任何东西把它拉回来</b>。事后我只能说"它没回来"，说不出
 * <b>是哪条兜底没生效</b> —— 因为进程一死，它内存里的计数器（{@code CrashGuard} 的
 * {@code armed / revive_beats}）就全没了，只剩"后来又活了"这一个事实。
 * <p>★ <b>归因更正（同日复现，别照抄旧笔记）</b>：我一度写"pm disable 把进程打死"，
 * <b>这条是错的</b> —— 复现 {@code pm disable com.ledi.agent/.BrainHome} 返回
 * {@code new state: disabled}，而 <b>pid 没变、8899 照样通</b>。那次停机的真因至今未定；
 * 有证据的只有"当时的兜底确实救不了它"。
 *
 * <p>所以要把判据放到<b>进程之外</b>：写 {@link SharedPreferences}
 * （存在 {@code /data/data/com.ledi.agent/shared_prefs/}，跟进程寿命无关）。
 * 每次进程启动、每条通道把服务拉起来，都往这里落一笔。
 *
 * <h3>口径（这几条是给"下次读的人"看的，别改语义）</h3>
 * <ul>
 *   <li>{@link #mark} 记的是<b>复活事件</b>：通道名 + 时刻；同时算出
 *       <b>silent_s = 距上一次心跳多久</b> —— 那就是这次停机时长的估计
 *       （精度 ± 心跳周期，见 {@code CrashGuard.BACKSTOP_MS}）。</li>
 *   <li>{@link #beat} 记的是<b>活着</b>：只更新心跳时刻。★ 区分这两件事很重要 ——
 *       把心跳也写进 {@code channel}，就会把"谁救了我"冲掉，正是本类要避免的假账。</li>
 *   <li>时刻用 {@link SystemClock#elapsedRealtime()}（设备单调时钟）算差值，
 *       不用墙钟 —— 本机墙钟曾被改过（时区修复），拿它算间隔会算出负数。</li>
 *   <li>{@code proc_uptime_s} = <b>本进程</b>活了多久（不是整机，见 REF-P 的
 *       {@code /status.uptime_s} 那个坑：那个是整机开机时长）。</li>
 * </ul>
 *
 * <p><b>线程</b>：全部方法可任意线程调用（{@code SharedPreferences} 自身线程安全，
 * 且这里不做任何需要 Looper 的事）。
 */
public final class ReviveLog {

    private static final String P = "ledi_revive";

    /** 最近一次复活事件的通道名，例如 {@code "alarm"} / {@code "key:head.down"}。 */
    private static final String K_CH = "last_channel";
    private static final String K_DETAIL = "last_detail";
    private static final String K_AT = "last_at";            // 墙钟（给人看）
    private static final String K_EL = "last_el";            // 单调时钟 ms（算差值）

    /** 活着的心跳时刻（单调），{@link #mark} 也顺带刷新它。 */
    private static final String K_BEAT_EL = "beat_el";
    private static final String K_BEATS = "beats";           // 心跳累计次数

    /**
     * 本次进程启动时测得的**停机时长**（秒；-1 = 无样本）。
     *
     * <p>★★ 为什么不能只看 {@code silent_s}（2026-09-23 深夜，又是"判据自己错"）：
     * {@code silent_s} 是 {@link #mark} 在<b>送达那一刻</b>算的 {@code now - beat_el}，
     * 而 {@code mark} 算完会把 {@code beat_el} 刷成"现在"。于是同一个新进程里
     * <b>第二次</b>被叫起来时，算出来的就必然是 0 —— 实测现场：
     * 冷杀 45s 后，{@code key} 广播与常驻闹钟在 2 秒内先后到达，账本里变成
     * {@code key|silent=44} 与 {@code alarm|silent=0}，看着像"alarm 那次没停机"。
     * 真正的停机时长只有一个可靠测量点：<b>进程刚起来、还没被任何 mark 动过 beat_el 的那一刻</b>
     * —— 就是 {@link #noteProcessStart}。所以在那里把它算出来、单独存一份。
     */
    private static final String K_DOWN_S = "down_s";

    private static final String K_STARTS = "proc_starts";    // 本进程被拉起过多少次
    private static final String K_PROC_START_EL = "proc_start_el";
    private static final String K_PID = "pid";

    /** 最近几次复活事件，新→旧，形如 {@code ch|silent_s|el_ms;ch|...}（最多 6 条）。 */
    private static final String K_HIST = "history";

    /** 由 {@link BootReceiver} 写、{@link AgentService} 消费的"这次的通道标签"。 */
    private static final String K_PEND = "pending";
    private static final String K_PEND_DETAIL = "pending_detail";

    /** 连续崩溃次数（跨进程持久）—— 用来给"崩溃风暴"限速，见 {@code CrashGuard}。 */
    private static final String K_STREAK = "crash_streak";

    /** 各通道累计计数前缀。 */
    private static final String CNT = "n_";

    private static final int HIST_MAX = 6;

    /**
     * "本进程还年轻"的界限（秒）。比它新的一次送达，才当成"很可能就是把我叫起来的那一下"，
     * 才会进 {@code history} 与通道计数 —— 见 {@link #mark}。
     */
    private static final int FRESH_S = 30;

    private ReviveLog() {}

    private static SharedPreferences sp(Context c) {
        Context a = c.getApplicationContext();
        return (a == null ? c : a).getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    private static long el() {
        long v = SystemClock.elapsedRealtime();
        return v > 0 ? v : -1L;
    }

    // ------------------------------------------------------------ 进程启动落账

    /**
     * 本进程起来了（{@code AgentService.onCreate} 最早处调用）。
     *
     * <p>只做两件事：累计"进程启动次数"、记下本进程的起点。
     * <b>不碰</b> {@code last_channel} —— 谁把我拉起来的，由
     * {@link AgentService#onStartCommand} 那边按 pending 标签落账（那样更准）。
     */
    public static void noteProcessStart(Context ctx) {
        try {
            SharedPreferences p = sp(ctx);
            long e = el();
            long beatEl = p.getLong(K_BEAT_EL, -1);
            // ★ 停机时长只能在这里测准（见 K_DOWN_S 的注释）：这一刻 beat_el 还是
            //   死前的值，之后任何一次 mark 都会把它刷掉。
            long downS = (beatEl > 0 && e > 0) ? Math.max(0, (e - beatEl) / 1000) : -1;
            SharedPreferences.Editor ed = p.edit()
                    .putInt(K_STARTS, p.getInt(K_STARTS, 0) + 1)
                    .putLong(K_PROC_START_EL, e)
                    .putLong(K_DOWN_S, downS)
                    .putInt(K_PID, android.os.Process.myPid());
            // ★★ 绝不覆盖已有的 K_BEAT_EL（2026-09-23 深夜踩的坑，代价是"判据自己错"）：
            //    mark() 算 silent_s 用的就是 K_BEAT_EL（= 死前最后一次心跳），
            //    如果进程一起来就把它刷成"现在"，那"停机了多久"永远算成 0~2 秒 ——
            //    **这条判据就废了**。我拿它验常驻心跳，连续两次把"42s 后被叫醒"读成
            //    silent_s=2，差点把成功判成失败。只在**从来没有过**心跳记录时才初始化。
            if (p.getLong(K_BEAT_EL, -1) <= 0) ed.putLong(K_BEAT_EL, e);
            ed.apply();
        } catch (Throwable ignored) {}
    }

    /** 活着 —— 只更新心跳时刻（见类注释：绝不能写进 channel）。 */
    public static void beat(Context ctx) {
        try {
            SharedPreferences p = sp(ctx);
            p.edit().putLong(K_BEAT_EL, el())
                    .putInt(K_BEATS, p.getInt(K_BEATS, 0) + 1)
                    .apply();
        } catch (Throwable ignored) {}
    }

    /**
     * 把"活着"的基准挪到此刻，但<b>不算</b>一次心跳送达。
     *
     * <p>给 {@code CrashGuard.setPeriod} 用。为什么要单独一个方法：
     * {@code silent_s} 是"复活时刻 − 上一次心跳时刻"，如果改完周期不动这个基准，
     * 它会把<b>改周期之前的那段</b>也算进来 —— 我设 40s、测出来 60s，
     * 看着像"常驻心跳没按我设的周期来"，其实是判据自己的口径错了。
     */
    public static void rebaseBeat(Context ctx) {
        try { sp(ctx).edit().putLong(K_BEAT_EL, el()).apply(); } catch (Throwable ignored) {}
    }

    /**
     * 记一次"谁在叫我"。
     *
     * <p>★★ 分两档，别混（混了账就废话化）：
     * <ul>
     *   <li><b>复活事件</b>（{@code proc_uptime &lt; }{@value #FRESH_S} s，说明这一嗓子
     *       很可能就是<b>把我这条命叫出来</b>的那一下）：进 {@code history} + 通道计数。</li>
     *   <li><b>活着时的例行送达</b>（常驻心跳每 {@code BACKSTOP_S} 秒就会来一次）：
     *       只更新 {@code channel}（"最近一次是谁"），<b>不进 history</b> ——
     *       否则 3 分钟一条，真正有价值的"按键救了我一次"会在 20 分钟内被挤出榜单。</li>
     * </ul>
     *
     * @param channel 通道名（短的、稳定的字面量，便于统计）：
     *                {@code alarm} / {@code key} / {@code touch} / {@code screen} /
     *                {@code boot} / {@code net} / {@code explicit}（am startservice 等）/
     *                {@code ams_sticky}（系统按 START_STICKY 重启，没带 intent）
     * @param detail  人看的补充，例如 {@code act=...}
     */
    public static void mark(Context ctx, String channel, String detail) {
        try {
            SharedPreferences p = sp(ctx);
            long now = System.currentTimeMillis();
            long e = el();
            long beatEl = p.getLong(K_BEAT_EL, -1);
            long procEl = p.getLong(K_PROC_START_EL, -1);
            long silentS = (beatEl > 0 && e > 0) ? Math.max(0, (e - beatEl) / 1000) : -1;
            boolean fresh = procEl > 0 && e > 0 && (e - procEl) < FRESH_S * 1000L;

            SharedPreferences.Editor ed = p.edit()
                    .putString(K_CH, channel)
                    .putString(K_DETAIL, detail == null ? "" : detail)
                    .putLong(K_AT, now)
                    .putLong(K_EL, e)
                    .putLong(K_BEAT_EL, e);         // 被叫起来/被送达 ⇒ 心跳从这里重新起算

            if (fresh) {
                String hist = p.getString(K_HIST, "");
                String next = channel + "|" + silentS + "|" + e + ";";
                int cnt = 0;
                for (String s : hist.split(";")) {
                    if (s.isEmpty()) continue;
                    if (cnt++ >= HIST_MAX - 1) break;
                    next += s + ";";
                }
                ed.putString(K_HIST, next)
                  .putInt(CNT + channel, p.getInt(CNT + channel, 0) + 1);
            }
            ed.apply();
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ pending 标签

    /**
     * {@link BootReceiver} 收到广播、准备 {@code startService} 之前先贴上标签。
     *
     * <p>为什么不能"让 AgentService 自己猜"：{@code BootReceiver} 调的是无 action 的
     * {@code startService}，服务侧只能看到 {@code intent != null && action == null}，
     * 分辨不出是按键、触摸还是开机广播。贴标签就能分辨，而且<b>不会</b>因为
     * create→onStartCommand 的先后顺序而丢。
     */
    public static void setPending(Context ctx, String channel, String detail) {
        try {
            sp(ctx).edit().putString(K_PEND, channel)
                    .putString(K_PEND_DETAIL, detail == null ? "" : detail).apply();
        } catch (Throwable ignored) {}
    }

    /** 读走并清空 pending 标签；没有则返回 {@code null}。 */
    public static String[] consumePending(Context ctx) {
        try {
            SharedPreferences p = sp(ctx);
            String ch = p.getString(K_PEND, "");
            if (ch == null || ch.isEmpty()) return null;
            String d = p.getString(K_PEND_DETAIL, "");
            p.edit().remove(K_PEND).remove(K_PEND_DETAIL).apply();
            return new String[]{ch, d == null ? "" : d};
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ 崩溃风暴限速

    public static int crashStreak(Context ctx) {
        try { return sp(ctx).getInt(K_STREAK, 0); } catch (Throwable t) { return 0; }
    }

    public static int bumpCrashStreak(Context ctx) {
        try {
            SharedPreferences p = sp(ctx);
            int n = p.getInt(K_STREAK, 0) + 1;
            p.edit().putInt(K_STREAK, n).apply();
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void clearCrashStreak(Context ctx) {
        try { sp(ctx).edit().remove(K_STREAK).apply(); } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------ 状态

    /**
     * 给 {@code GET /status.revive} / {@code GET /revive} 用的 JSON。
     *
     * <p>字段口径（脚本要按这个读）：
     * <ul>
     *   <li>{@code channel} —— <b>本进程</b>是被谁拉起来的</li>
     *   <li>{@code silent_s} —— 那一次距上一次心跳多久（≈ 停机时长；{@code -1} = 无样本）
     *       ⚠ 同一进程里若有多条通道先后到达，只有<b>第一条</b>的 {@code silent_s} 是真的，
     *       后面的会算成 0 ⇒ <b>要判"停机多久"请读 {@code down_s}</b></li>
     *   <li>{@code down_s} —— ★ <b>本次进程启动时测得的停机时长</b>，只有一个测量点、
     *       不会被后续 mark 冲掉；验"闹钟周期对不对"就用它</li>
     *   <li>{@code beat_ago_s} —— 现在距上一次心跳多久。★ <b>它是活着的硬判据</b>：
     *       一直小于心跳周期才说明心跳链在走</li>
     *   <li>{@code proc_uptime_s} —— 本进程活了多久（区别于整机 uptime）</li>
     *   <li>{@code history} —— 最近 6 次复活事件（新→旧），每条带当时的 silent_s</li>
     * </ul>
     */
    public static String statusJson(Context ctx) {
        try {
            SharedPreferences p = sp(ctx);
            long e = el();
            long lastEl = p.getLong(K_EL, -1);
            long beatEl = p.getLong(K_BEAT_EL, -1);
            long procEl = p.getLong(K_PROC_START_EL, -1);
            long lastAt = p.getLong(K_AT, 0);

            StringBuilder counts = new StringBuilder("{");
            boolean first = true;
            for (java.util.Map.Entry<String, ?> en : p.getAll().entrySet()) {
                String k = en.getKey();
                if (!k.startsWith(CNT)) continue;
                Object v = en.getValue();
                if (!(v instanceof Integer)) continue;
                if (!first) counts.append(',');
                first = false;
                counts.append('"').append(J.esc(k.substring(CNT.length())))
                        .append("\":").append(v);
            }
            counts.append('}');

            StringBuilder hist = new StringBuilder("[");
            String raw = p.getString(K_HIST, "");
            boolean f2 = true;
            for (String s : raw.split(";")) {
                if (s.isEmpty()) continue;
                int a = s.indexOf('|');
                int b = a < 0 ? -1 : s.indexOf('|', a + 1);
                if (a < 0 || b < 0) continue;
                String ch = s.substring(0, a);
                String sil = s.substring(a + 1, b);
                long evEl;
                try { evEl = Long.parseLong(s.substring(b + 1)); } catch (Exception ex) { evEl = -1; }
                if (!f2) hist.append(',');
                f2 = false;
                hist.append("{\"ch\":\"").append(J.esc(ch)).append("\",\"silent_s\":")
                        .append(sil).append(",\"ago_s\":")
                        .append(evEl > 0 && e > 0 ? (e - evEl) / 1000 : -1).append('}');
            }
            hist.append(']');

            return "{\"channel\":\"" + J.esc(p.getString(K_CH, ""))
                    + "\",\"detail\":\"" + J.esc(p.getString(K_DETAIL, ""))
                    + "\",\"ago_s\":" + (lastEl > 0 && e > 0 ? (e - lastEl) / 1000 : -1)
                    + ",\"silent_s\":" + silentOf(ctx)
                    + ",\"down_s\":" + p.getLong(K_DOWN_S, -1)
                    + ",\"beat_ago_s\":" + (beatEl > 0 && e > 0 ? (e - beatEl) / 1000 : -1)
                    + ",\"beats\":" + p.getInt(K_BEATS, 0)
                    + ",\"proc_uptime_s\":" + (procEl > 0 && e > 0 ? (e - procEl) / 1000 : -1)
                    + ",\"proc_starts\":" + p.getInt(K_STARTS, 0)
                    + ",\"pid\":" + p.getInt(K_PID, -1)
                    + ",\"crash_streak\":" + p.getInt(K_STREAK, 0)
                    + ",\"last_at\":" + lastAt
                    + ",\"counts\":" + counts
                    + ",\"history\":" + hist + "}";
        } catch (Throwable t) {
            return "{\"error\":\"" + J.esc(String.valueOf(t)) + "\"}";
        }
    }

    /** 最近一次复活时的 silent_s（从 history 第一条里读，保证与 history 自洽）。 */
    private static long silentOf(Context ctx) {
        try {
            String raw = sp(ctx).getString(K_HIST, "");
            for (String s : raw.split(";")) {
                if (s.isEmpty()) continue;
                int a = s.indexOf('|');
                int b = a < 0 ? -1 : s.indexOf('|', a + 1);
                if (a < 0 || b < 0) continue;
                return Long.parseLong(s.substring(a + 1, b));
            }
        } catch (Throwable ignored) {}
        return -1;
    }
}
