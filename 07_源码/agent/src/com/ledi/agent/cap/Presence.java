package com.ledi.agent.cap;

import com.ledi.agent.core.J;

import com.ledi.agent.core.BootLog;

import com.ledi.agent.core.AgentService;

import com.ledi.agent.voice.VoiceWakeController;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Presence —— 「机器人还在不在线」这一件事的唯一记录点（v142）。
 *
 * <h3>用户的原话与它对应的两个问题</h3>
 * <pre>
 *   「机器人没有接受语音输入时应该在 3-5 次尝试接受语音后停止对语音的输入，
 *     机器人进入离线模式，用户喊机器人名称后才能重新启动。」
 *   「没有用户希望机器人在不使用的时候一直向上传递语音消息消耗 API token。」
 * </pre>
 * 也就是说：**听不懂不是问题，"听不懂还一直问"才是问题。**
 *
 * <h3>离线模式到底是什么（只说这一处，别在别处再定义一遍）</h3>
 * 它<b>不是</b>"把机器人关掉"，而是把<b>会往外花钱/花配额的那条链路</b>关掉：
 * <ul>
 *   <li>不再开在线听写（讯飞 {@code writeAudio} 那条）—— 省 appid 配额；</li>
 *   <li>不再调大模型（{@link AiCore}）—— 省 API token；</li>
 *   <li>停主动说话（{@link ProactiveTalk}，它每次触发就是一次 LLM 调用）。</li>
 * </ul>
 * <b>保留</b>离线唤醒引擎（KWS）—— 它是本机资源、零成本，也是"喊名字回来"的唯一通路。
 * 所以离线模式下机器人**依然听得见自己的名字**，只是不再开口要钱。
 *
 * <h3>★★★ 一条必须说清的硬事实：自定义名字改不了唤醒词</h3>
 * 用户想「由手机端/电脑端说一个名字，三端同步」。能同步的部分我们做了
 * （{@link #name}/{@link #setName}，三端各有一个读写口），但要如实说明边界：
 * <ul>
 *   <li>声学唤醒词固化在原厂模型里。证据：{@code /data/data/com.ledi.agent/files/WakeUp.bin}
 *       是 <b>32 条完全相同的 40 字节记录</b>（实测 2026-09-24）—— 一个固定模板，
 *       里面既没有拼音也没有词条，<b>不是一个可以改定的词表</b>。
 *       离线 ASR 的声学词表同样固化在 {@code libbd_easr_*.dat.so}（REF-A/D2 已铁证）。</li>
 *   <li>⇒ "喊名字唤醒"的<strong>主通路</strong>只能是出厂唤醒词；自定义名字作用于
 *       ①三端显示与它自称的名字 ②在线/离线识别文本里"点名叫它"的匹配
 *       ③离线模式下按 {@code name_probe_sec} 打开的低频在线探测
 *       （默认 <b>0 = 关</b>，因为它要花配额 —— 这正是用户最在意的东西）。</li>
 * </ul>
 *
 * <h3>为什么计数要落盘</h3>
 * 连续失败次数如果只放内存，进程被 CrashGuard 拉一次就归零 ⇒ "3 次之后停"永远
 * 达不到（每次重启都从 0 开始）。这正是本项目"判据不能随被测对象一起死"的老坑。
 */
public final class Presence {

    private static final String PREFS = "ledi_agent";
    private static final String K_MISS = "voice_miss_streak";
    private static final String K_LIMIT = "voice_miss_limit";
    private static final String K_RETRY = "voice_retry_max";        // ★ v181：重试次数（≠ 离线门槛）
    private static final String K_AUTO_MIN = "voice_offline_auto_min"; // ★ v181：离线满几分钟自动回在线
    private static final String K_OFFLINE = "voice_offline";
    private static final String K_NAME = "robot_name";
    private static final String K_PROBE = "name_probe_sec";

    /** 默认名字。出厂唤醒词里的那个名字（"哈喽乐迪"）。 */
    public static final String DEFAULT_NAME = "乐迪";

    /** 连续失败几次就进离线模式。用户要求 3-5 次。★ v181 起语义 = **几次唤醒**（不是几次重试）。 */
    public static final int LIMIT_DEFAULT = 3;

    /** ★ v181：一次唤醒内部重试几次（与离线门槛无关，默认 3）。 */
    public static final int RETRY_DEFAULT = 3;

    /** ★ v181：离线满几分钟自动回在线（0 = 关，默认 5）。 */
    public static final int AUTO_MIN_DEFAULT = 5;

    /** 现在是不是离线模式（{@code CommandRouter}/{@code AiCore} 读它）。 */
    public static volatile boolean offline;

    private static volatile int missStreak;
    private static volatile int offlineEnters;
    private static volatile int offlineExits;
    private static volatile String offlineReason = "";
    private static volatile long offlineSince;
    private static volatile String lastMiss = "";
    private static volatile String lastHeard = "";

    /**
     * ★★★ v166：这次的 {@code offline=true} 是<b>从盘里恢复来的</b>，不是本进程进的。
     *
     * <p>为什么非要单列一个字段：{@link #offlineEnters} / {@link #offlineExits}
     * 是<b>进程内计数</b>，而 {@code voice_offline} 是<b>落盘</b>的 ——
     * 于是存在一个自相矛盾的读数：{@code peer.offline=true} 同时 {@code enters=0}。
     * 2026-09-25 现场就撞上了：我以为"这个进程从来没进过离线"，
     * 实际是**上一个进程进了离线、写盘、重启后又读回来了** ——
     * 正是本项目铁律「★★ 内存计数器随进程一起消失」和
     * 「★★ 默认值/迁移都 ≠ 关掉了」的同一族坑。
     *
     * <p>把这件事显式化之后，读 {@code GET /presence} 就能一眼分清
     * "它刚掉线" 和 "它一直是离线的（重启也没退）" —— 这两件事的处置完全不同。</p>
     */
    private static volatile boolean restoredFromDisk;

    private Presence() {}

    private static SharedPreferences p(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ 配置

    /** 连续失败几次进离线（1..5，默认 3）。 */
    public static int missLimit(Context ctx) {
        int v = LIMIT_DEFAULT;
        try { v = p(ctx).getInt(K_LIMIT, LIMIT_DEFAULT); } catch (Throwable ignored) {}
        if (v < 1) v = 1;
        if (v > 5) v = 5;
        return v;
    }

    public static String setMissLimit(Context ctx, int v) {
        if (v < 1) v = 1;
        if (v > 5) v = 5;
        try { p(ctx).edit().putInt(K_LIMIT, v).apply(); } catch (Throwable ignored) {}
        return "miss_limit=" + v;
    }

    /**
     * ★★★ v181：一次唤醒**内部**重试几次去听清（与"几次算离线"彻底解耦）。
     *
     * <p>为什么必须把这两个数拆开：v142 起 {@code answerLoop} 是
     * {@code maxTries = missLimit}，而且**每失败一次就 noteMiss 一次**。
     * 两个概念共用一个数，于是"重试 3 次"直接等于"记满 3 次"，
     * 门槛被偷偷缩小成 <b>一次唤醒</b>。2026-09-27 受控实测（wakecost 探针）：</p>
     * <pre>
     *   清场 miss_streak=0 offline=false
     *   一次 POST /forcewake + 全程不出声
     *   → miss_streak=3 offline=true
     *   → 日志「第 1 轮重试耗尽…（离线模式=true）」
     * </pre>
     * <p>后果：屋里 TV/噪声**误唤醒一次**，机器人就进离线模式（关掉在线听写/大模型/
     * 主动说话）当场变哑巴 —— 用户说的"不活泼/不理人"就是这个。拆开之后，
     * 重试仍然 3 次（听清的概率不变），但只记 1 次 miss。</p>
     */
    public static int retryMax(Context ctx) {
        int v = RETRY_DEFAULT;
        try { v = p(ctx).getInt(K_RETRY, RETRY_DEFAULT); } catch (Throwable ignored) {}
        if (v < 1) v = 1;
        if (v > 5) v = 5;
        return v;
    }

    public static String setRetryMax(Context ctx, int v) {
        if (v < 1) v = 1;
        if (v > 5) v = 5;
        try { p(ctx).edit().putInt(K_RETRY, v).apply(); } catch (Throwable ignored) {}
        return "retry_max=" + v;
    }

    /**
     * 离线满几分钟**自动**回在线（0 = 关；默认 {@link #AUTO_MIN_DEFAULT}）。
     *
     * <p>为什么要有这一条：离线状态的唯一出口原本是"再听到一句有效人话/喊名字"
     * （见 {@link #noteHeard}）—— 而进离线的成因往往正是"屋里没人跟它说话"，
     * 于是构成一个**自我锁死**：越哑越没人愿意跟它说话，越没人说话越哑。
     * 自动恢复把这个死结解开，代价只是"离线满 5 分钟后恢复在线"。
     * 用户 2026-09-27 明确选定这一档。</p>
     */
    public static int autoRecoverMin(Context ctx) {
        int v = AUTO_MIN_DEFAULT;
        try { v = p(ctx).getInt(K_AUTO_MIN, AUTO_MIN_DEFAULT); } catch (Throwable ignored) {}
        if (v < 0) v = 0;
        if (v > 120) v = 120;
        return v;
    }

    public static String setAutoRecoverMin(Context ctx, int min) {
        if (min < 0) min = 0;
        if (min > 120) min = 120;
        try { p(ctx).edit().putInt(K_AUTO_MIN, min).apply(); } catch (Throwable ignored) {}
        return "offline_auto_min=" + min;
    }

    /**
     * 心跳里调的一条闸：离线满 N 分钟就自动回在线。
     *
     * <p>★ 刻意**不新起线程/闹钟**：挂在 {@code VoiceWakeController.startBeat()}
     * 这条本来就在跑的 8 秒心跳上（与 v180 的 idleBeat 同一个做法）。</p>
     *
     * @return 真的触发了恢复时返回原因串，否则空串（便于调用方只在有事时落日志）
     */
    public static synchronized String autoRecoverTick(Context ctx) {
        if (!offline) return "";
        int min = autoRecoverMin(ctx);
        if (min <= 0) return "";
        long wasS = (System.currentTimeMillis() - offlineSince) / 1000L;
        if (wasS >= min * 60L) {
            return exitOffline(ctx, "离线满 " + min + " 分钟自动恢复（不必先喊名字）");
        }
        return "";
    }

    /** 给日志用：当前连续失败次数。 */
    public static int missStreakNow() {
        return missStreak;
    }

    /** 自定义名字（默认"乐迪"）。 */
    public static String name(Context ctx) {
        String n = null;
        try { n = p(ctx).getString(K_NAME, null); } catch (Throwable ignored) {}
        if (n == null || n.trim().isEmpty()) return DEFAULT_NAME;
        return n.trim();
    }

    /**
     * 改名。三端同步靠的就是这个：手机端、电脑端、对机器人说话都写这一个键。
     *
     * @return 结果串（给端点回显）
     */
    public static String setName(Context ctx, String v) {
        if (v == null) return "name=null";
        String n = v.trim();
        if (n.isEmpty()) return "name 不能为空";
        if (n.length() > 12) n = n.substring(0, 12);
        try { p(ctx).edit().putString(K_NAME, n).apply(); } catch (Throwable ignored) {}
        BootLog.i("名字改为 [" + n + "]（三端以机器人这一份为准）");
        return "name=" + n;
    }

    /** 名字匹配：识别文本里出现名字就算"在叫它"（去空白后 contains）。 */
    public static boolean calledBy(Context ctx, String text) {
        if (text == null) return false;
        String t = text.replace(" ", "").replace("，", "").replace("。", "").trim();
        if (t.isEmpty()) return false;
        String n = name(ctx);
        return !n.isEmpty() && t.contains(n);
    }

    /** 低频"名字探测"周期（秒）；0 = 关（默认）。见类注释里的成本说明。 */
    public static int nameProbeSec(Context ctx) {
        try { return p(ctx).getInt(K_PROBE, 0); } catch (Throwable t) { return 0; }
    }

    public static String setNameProbeSec(Context ctx, int sec) {
        if (sec < 0) sec = 0;
        if (sec > 0 && sec < 15) sec = 15;           // 太密就变成"一直上云"，与初衷相反
        try { p(ctx).edit().putInt(K_PROBE, sec).apply(); } catch (Throwable ignored) {}
        return "name_probe_sec=" + sec;
    }

    // ------------------------------------------------------------------ 计数

    /**
     * 记一次"喊醒了但没听见有效内容"。
     *
     * @return 达到上限并因此进入离线模式时返回 true
     */
    public static synchronized boolean noteMiss(Context ctx, String why) {
        missStreak++;
        lastMiss = why == null ? "" : why;
        persist(ctx);
        int lim = missLimit(ctx);
        BootLog.w("语音可用性 连续听不懂 " + missStreak + "/" + lim + "（" + lastMiss + "）");
        if (missStreak >= lim && !offline) {
            enterOffline(ctx, "连续 " + missStreak + " 次没听到有效语音");
            return true;
        }
        return false;
    }

    /** 听到了一句有效内容 —— 计数归零；若在离线模式里，顺便回来（说话即叫醒）。 */
    public static synchronized void noteHeard(Context ctx, String text) {
        lastHeard = text == null ? "" : text;
        if (missStreak != 0) {
            missStreak = 0;
            persist(ctx);
        }
        if (offline && calledBy(ctx, text)) {
            exitOffline(ctx, "听到名字「" + name(ctx) + "」");
        }
    }

    /**
     * 手动把"连续听不懂"计数拨到任意值（验收/排障用）。**不改变离线状态** ——
     * 想做"把计数推到上限、看它会不会自己进离线"的实验时，用它+一次失败即可。
     */
    public static synchronized String setMissStreak(Context ctx, int v) {
        if (v < 0) v = 0;
        missStreak = v;
        persist(ctx);
        BootLog.w("语音可用性 计数被手动改为 " + missStreak + "/" + missLimit(ctx));
        return "miss_streak=" + missStreak;
    }

    private static void persist(Context ctx) {
        try {
            p(ctx).edit().putInt(K_MISS, missStreak).putBoolean(K_OFFLINE, offline).apply();
        } catch (Throwable ignored) {}
    }

    /** 进程启动时把落盘的计数与模式读回来（否则"3 次"永远攒不满）。 */
    public static void load(Context ctx) {
        try {
            missStreak = p(ctx).getInt(K_MISS, 0);
            offline = p(ctx).getBoolean(K_OFFLINE, false);
        } catch (Throwable ignored) {}
        if (offline) {
            offlineSince = System.currentTimeMillis();
            // ★ v166：把"这次是恢复来的"记下来 —— 否则 enters=0 + offline=true
            //   这个自相矛盾的读数会把人骗进"它从没进过离线"的错误方向。
            restoredFromDisk = true;
            BootLog.w("语音可用性 上次是离线模式且次数=" + missStreak + " → 继续离线（"
                    + "喊「" + name(ctx) + "」或唤醒词可回来）");
        }
    }

    // ------------------------------------------------------------------ 模式切换

    /** 进入离线模式：关掉一切要花配额/钱的外发链路，保留唤醒引擎。 */
    public static synchronized String enterOffline(Context ctx, String why) {
        if (offline) return "already offline";
        offline = true;
        offlineSince = System.currentTimeMillis();
        offlineReason = why == null ? "" : why;
        offlineEnters++;
        restoredFromDisk = false;      // ★ v166：这一次是**本进程**进的，不是读盘来的
        persist(ctx);
        BootLog.w("★进入离线模式（第 " + offlineEnters + " 次）原因: " + offlineReason
                + " —— 停在线听写/大模型/主动说话，保留唤醒词");
        // ① 主动说话（每次触发都是一次 LLM 调用）—— 必须停
        try {
            if (AgentService.proactiveTalk != null) AgentService.proactiveTalk.stop();
        } catch (Throwable t) { BootLog.w("离线 停主动说话失败 " + t); }
        // ② 常识别（它一直在上云）—— 必须停
        try {
            if (AgentService.voiceWake != null) AgentService.voiceWake.setContinuous(false);
        } catch (Throwable t) { BootLog.w("离线 停常识别失败 " + t); }
        // ③ 在线听写总开关（离线模式下 listenSentence 会直接走离线引擎）
        try {
            if (AgentService.voiceWake != null) AgentService.voiceWake.setOffline(true);
        } catch (Throwable t) { BootLog.w("离线 切 ASR 失败 " + t); }
        return "offline on: " + offlineReason;
    }

    /** 退出离线模式（听到名字/唤醒词、或远程命令）。 */
    public static synchronized String exitOffline(Context ctx, String why) {
        if (!offline) return "not offline";
        long was = (System.currentTimeMillis() - offlineSince) / 1000L;
        offline = false;
        missStreak = 0;
        offlineReason = "";
        offlineExits++;
        persist(ctx);
        BootLog.i("★退出离线模式（第 " + offlineExits + " 次）原因: " + why + "，离线了 " + was + "s");
        try {
            if (AgentService.voiceWake != null) AgentService.voiceWake.setOffline(false);
        } catch (Throwable t) { BootLog.w("离线 恢复 ASR 失败 " + t); }
        try {
            if (AgentService.proactiveTalk != null) AgentService.proactiveTalk.start();
        } catch (Throwable t) { BootLog.w("离线 恢复主动说话失败 " + t); }
        return "offline off: " + why;
    }

    // ------------------------------------------------------------------ 输出

    public static String json(Context ctx) {
        return "{\"offline\":" + offline
                + ",\"reason\":\"" + J.esc(offlineReason) + "\""
                + ",\"offline_ago_s\":" + (offlineSince == 0 ? -1
                        : (System.currentTimeMillis() - offlineSince) / 1000)
                + ",\"restored_from_disk\":" + restoredFromDisk
                + ",\"enters\":" + offlineEnters
                + ",\"exits\":" + offlineExits
                + ",\"miss_streak\":" + missStreak
                + ",\"miss_limit\":" + missLimit(ctx)
                + ",\"retry_max\":" + retryMax(ctx)
                + ",\"offline_auto_min\":" + autoRecoverMin(ctx)
                + ",\"last_miss\":\"" + J.esc(lastMiss) + "\""
                + ",\"last_heard\":\"" + J.esc(lastHeard) + "\""
                + ",\"name\":\"" + J.esc(name(ctx)) + "\""
                + ",\"name_probe_sec\":" + nameProbeSec(ctx) + "}";
    }
}
