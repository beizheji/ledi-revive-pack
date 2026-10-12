package com.ledi.agent.core;

import com.ledi.agent.voice.AsrProbe;
import com.ledi.agent.ui.BrainActivity;
import com.ledi.agent.net.Proxies;
import com.ledi.agent.sdk.SdkController;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;

/**
 * 进程死了要能自己爬起来 —— 「静默死亡」的兜底。
 *
 * <h3>为什么必须有这个</h3>
 * 2026-09-21 真机取证：{@code dumpsys dropbox} 里 {@code com.ledi.agent} 的崩溃
 * 横跨 v56 / v69 / v70 / v82 反复出现，其中 v82 那条是原厂 SDK 的漏判空
 * （{@code EngineService$1.handleMessage} 调 {@code actionComplete} 前不判空，
 * 见 {@link SdkController#ensureActionCallback}）。那处已经在源头补掉了，但：
 * <ul>
 *   <li>原厂 SDK 里同类漏判空不止一处，我们没法逐条审完；</li>
 *   <li>这台机器上还有 native 崩溃（dropbox 里多条 {@code SYSTEM_TOMBSTONE}）、
 *       lowmem 杀进程（{@code system_server_lowmem}），这些我们更控制不了。</li>
 * </ul>
 * 而本项目的可用性底座是**极其脆弱**的：AgentService 一死，
 * 唤醒词 / HTTP 8899 / 蓝牙遥控 / 齿轮入口**全部同时消失**，且没有任何东西会把它拉回来
 * —— 之前只能等用户手动重启机器人。也就是说一次偶发崩溃 = 机器人永久变砖（直到有人发现）。
 *
 * <h3>★★ 2026-09-23 晚：一次 13 分钟的"没人救"推翻了两条旧假设</h3>
 * 实验里 {@code pm disable com.ledi.agent/.BrainActivity} 之后，进程<b>13 分钟没有任何东西
 * 把它拉回来</b>（直到我手动 {@code am startservice}）。
 * ★ <b>归因更正（同日复现）</b>：当时我写的是"pm disable 把进程打死"——
 * <b>这条是错的</b>。实测 {@code pm disable com.ledi.agent/.BrainHome} 返回
 * {@code new state: disabled}，而 <b>pid 没变、8899 照样通</b> ⇒ 它<b>不杀进程</b>。
 * 那次真因至今未定；能确定的是：当时的兜底<b>确实救不了</b>它。
 * 事后复盘，旧实现有两个真问题：
 * <ol>
 *   <li><b>心跳用的是非唤醒闹钟</b>（{@code am.set(RTC, ...)}）：当时屏幕是灭的
 *       （实验里刚做过屏保），非唤醒闹钟不会把 CPU 叫醒 ⇒ 它<b>到点了也不送</b>。
 *       而"要救的正是没人碰、屏幕灭着的机器"，这恰好是最需要唤醒的场景 ——
 *       为了省电把兜底调成非唤醒，就是把兜底交给运气。现改为 <b>RTC_WAKEUP</b>。</li>
 *   <li><b>心跳是"一次性 + 自己重排"的链子</b>：进程一死就没人排下一次了，
 *       而系统里那条已经排上的闹钟又是非唤醒的 ⇒ 整条链断在这里。
 *       现改为再加一条 {@code setRepeating} 的 <b>常驻</b>闹钟
 *       （{@link #BACKSTOP_MS}，只排一次，永不依赖我们进程再排）。</li>
 * </ol>
 * 另外补了两个**独立**通道（都不依赖 AlarmManager）：见 {@link BootReceiver}
 * 里的按键/触摸/亮屏广播 —— 用户一碰机器人，进程就会被系统拉起来。
 *
 * <h3>现在有几条通道（互相独立，谁活着谁上）</h3>
 * <table>
 *   <tr><th>通道</th><th>触发</th><th>最坏延迟</th></tr>
 *   <tr><td>崩溃自拉起</td><td>Java 未捕获异常</td><td>{@value #REVIVE_MS} ms</td></tr>
 *   <tr><td>常驻闹钟</td><td>系统闹钟（RTC_WAKEUP，重复）</td><td>{@value #BACKSTOP_S} s</td></tr>
 *   <tr><td>按键/触摸/亮屏广播</td><td>静置接收器（{@code AndroidManifest}）</td><td>用户碰它就立刻</td></tr>
 *   <tr><td>开机/联网/换包广播</td><td>同上</td><td>事件驱动</td></tr>
 * </table>
 * ★ <b>哪次是谁救的，看 {@code GET /status.revive.channel}</b>
 * （{@link ReviveLog} 把账存在 prefs 里，进程死了也不丢）——
 * 没有这条，修完了也只有"它活了"这一个事实，说不出是哪条通道生效。
 *
 * <p>闹钟用显式 {@code Intent}（{@code PendingIntent.getService} 到 AgentService），
 * 从 PendingIntent 发起属于「应用自己发起」，在这台 Android 5.1 上不受后台启动限制。
 */
public final class CrashGuard {

    /** 崩溃后多久尝试复活（留 2s 让系统把 crash dialog / 记录写完）。 */
    static final long REVIVE_MS = 3000L;

    /**
     * 崩溃风暴时的复活延迟。
     *
     * <p>为什么需要：本类会无限重试，而"启动就崩"（例如原厂引擎此时不可用）会变成
     * 3 秒一次的无限重启风暴 —— 那比死着更糟（烧 CPU、刷日志、可能拖垮系统）。
     * 连续崩溃 ≥ {@value #STREAK_N} 次后把延迟放宽到 {@value #REVIVE_SLOW_S} s。
     * ★ 只是<b>变慢</b>，不会停：宁可慢，不可砖。
     */
    static final long REVIVE_SLOW_MS = 30_000L;
    static final int STREAK_N = 3;

    /**
     * 主线程崩溃盾：{@value #SHIELD_WINDOW_MS} ms 窗口内最多吞 {@value #SHIELD_MAX} 次。
     *
     * <p>为什么要有上界而不是无脑吞：万一发出去的版本带着一个"必崩路径"，
     * 无脑吞就变成主线程在崩溃里空转 —— 比死还糟（烧 CPU、UI 永远画不出来）。
     * 超限即放行，交给已经很成熟的复活通道（{@value #REVIVE_MS} ms /
     * {@value #REVIVE_SLOW_MS} ms 两级）。
     */
    static final int SHIELD_MAX = 5;
    static final long SHIELD_WINDOW_MS = 60_000L;

    /**
     * 常驻心跳闹钟周期 = 死亡检测的最坏延迟。
     *
     * <p>3 分钟是权衡：这台机器人常带电、屏幕常亮，一次 {@code startService} 送达
     * 只是几十微秒的活；而"宕机 3 分钟"是能接受的可用性下限（原来是 5 分钟且非唤醒）。
     */
    static final long BACKSTOP_MS = 3 * 60 * 1000L;
    static final long BACKSTOP_S = BACKSTOP_MS / 1000;

    /** 验收时能把周期临时改小的下限/上限（见 {@link #setPeriod}）。 */
    static final long PERIOD_MIN_MS = 10_000L;
    static final long PERIOD_MAX_MS = 6 * 60 * 60 * 1000L;

    /** 三个**互不相同**的 requestCode。★ 共用会让三条闹钟互相顶掉（见 arm 的注释）。 */
    private static final int REQ_REVIVE = 1001;    // 崩溃后 3s（一次性，精确）
    private static final int REQ_BACKSTOP = 1002;  // 常驻心跳（重复，唤醒）
    private static final int REQ_TEST = 1003;      // 验收用（一次性，精确）

    static final String ACTION_REVIVE = "com.ledi.agent.REVIVE";

    /**
     * 验收闹钟专用的 action，只为让账本能把"我排的那一条"和"常驻心跳"分开。
     *
     * <p>为什么要分：两者都走 {@code PendingIntent.getService}，如果共用一个 action，
     * 测下来通道名都是 {@code alarm} —— 而这两件事的结论完全不同：
     * 前者证明"闹钟送达链路在进程已死时是通的"，后者证明"常驻兜底真的会自己回来"。
     * 判据自己分不清，就等于没测（这是本项目反复踩的那类坑）。
     */
    static final String ACTION_REVIVE_TEST = "com.ledi.agent.REVIVE_TEST";

    /** 这个 action 是不是"叫服务起来"的（两条都是）。 */
    static boolean isReviveAction(String act) {
        return ACTION_REVIVE.equals(act) || ACTION_REVIVE_TEST.equals(act);
    }

    /** 通道名：验收闹钟单独记，别混进常驻心跳的账里。 */
    static String reviveChannelOf(String act) {
        return ACTION_REVIVE_TEST.equals(act) ? "alarm_test" : "alarm";
    }

    private static volatile boolean installed = false;

    /** 观测用状态：兜底机制必须**可验证**，否则它本身就是一个假绿灯。 */
    private static volatile long mountedAt = 0L;      // 挂载时刻
    private static volatile int armedCount = 0;       // 成功排闹钟次数
    private static volatile int armFail = 0;          // 排闹钟失败次数（拿不到 AlarmManager 等）
    private static volatile int crashes = 0;          // 捕获到的未捕获异常次数
    private static volatile String lastCrash = "";    // 最近一次崩溃摘要（进程若还活着才读得到）
    // ★ v159 主线程崩溃盾的账（**只在本进程内存里** —— 进程真死就没了，
    //   所以它只回答"当前这个进程吞过几次"；跨进程的账仍然看 ReviveLog）。
    private static volatile int shieldSwallowed = 0;   // 累计吞掉（=进程没死成）的次数
    private static volatile int shieldStreak = 0;      // 当前窗口内吞了几次
    private static volatile long shieldWindowAt = 0L;  // 当前窗口起点
    private static volatile String lastShield = "";    // 最近一次被吞掉的签名
    private static volatile int reviveBeats = 0;      // 收到复活/心跳 Intent 的次数
    private static volatile long lastBeatAt = 0L;     // 最近一次心跳时刻（单调时钟）
    private static volatile long backstopArmedAt = 0L;// 常驻闹钟排上的时刻
    private static volatile boolean backstopOn = false;// 常驻闹钟是否已在系统里
    private static volatile long periodMs = BACKSTOP_MS; // 当前周期（验收可临时改小）
    private static volatile long lastArmedAt = 0L;    // 最近一次成功排闹钟的时刻
    private static volatile long lastArmedFor = 0L;   // 那次的延迟（兼容旧字段 armed_ms/armed_for_s）

    /** 周期存在 prefs 里：进程重启后 /status 仍能报出**真实的**周期（别报一个好看的值）。 */
    private static final String P = "ledi_revive";
    private static final String K_PERIOD_S = "backstop_period_s";

    /**
     * 主线程崩溃盾的闸门：这一次吞不吞？
     *
     * <p>返回 true = 已记账、可以吞（调用方负责重新 {@code Looper.loop()}）；
     * false = 超限，请让进程死，交给复活通道。
     */
    private static synchronized boolean shieldOk(Throwable e) {
        long now = SystemClock.elapsedRealtime();
        if (now - shieldWindowAt > SHIELD_WINDOW_MS) shieldStreak = 0;
        shieldWindowAt = now;
        shieldStreak++;
        try {
            lastShield = "thread=main " + chain(e);
        } catch (Throwable ignored) {}
        if (shieldStreak > SHIELD_MAX) {
            BootLog.w("★ 主线程崩溃盾已用满（" + (SHIELD_WINDOW_MS / 1000) + "s 内 "
                    + shieldStreak + " 次）⇒ 放行，交给复活通道");
            return false;
        }
        shieldSwallowed++;
        BootLog.w("★ 主线程未捕获异常已吞掉（本窗口第 " + shieldStreak + "/" + SHIELD_MAX
                + " 次），进程不死：" + lastShield);
        return true;
    }

    private CrashGuard() {}

    static String statusJson() {
        long now = SystemClock.elapsedRealtime();
        long base = lastBeatAt > 0 ? lastBeatAt : backstopArmedAt;
        return "{\"installed\":" + installed
                + ",\"pid\":" + android.os.Process.myPid()
                + ",\"mounted_ms\":" + (mountedAt == 0 ? -1 : now - mountedAt)
                + ",\"armed\":" + armedCount
                + ",\"arm_fail\":" + armFail
                + ",\"crashes\":" + crashes
                // ★ v159 主线程崩溃盾的仪表：crashes 是"看见几次未捕获异常"，
                //   mt_shield 是"其中几次被吞掉因而进程没死"。两者不等 = 有崩溃真的放行了。
                + ",\"mt_shield\":" + shieldSwallowed
                + ",\"mt_shield_streak\":" + shieldStreak
                + ",\"mt_shield_last\":\"" + J.esc(lastShield) + "\""
                + ",\"revive_beats\":" + reviveBeats
                + ",\"last_crash\":\"" + J.esc(lastCrash) + "\""
                // ★ 常驻心跳：beat_ago_s 必须一直 < backstop_s + 余量，否则就是"链子断了"
                + ",\"backstop_s\":" + (periodMs / 1000)
                + ",\"backstop_on\":" + backstopOn
                + ",\"beat_ago_s\":" + (lastBeatAt == 0 ? -1 : (now - lastBeatAt) / 1000)
                + ",\"next_in_s\":" + (base == 0 ? -1
                        : Math.max(0, (base + periodMs - now) / 1000))
                // ★ heartbeat_s = 编译进去的默认周期（正常应等于 backstop_s）；
                //   两者不等 = 有人用 POST /revive {"period_s":N} 临时改过 —— 别让它被当成"就是 180s"。
                + ",\"heartbeat_s\":" + BACKSTOP_S
                + ",\"period_default_s\":" + BACKSTOP_S
                + ",\"armed_ms\":" + (lastArmedAt == 0 ? -1 : now - lastArmedAt)
                + ",\"armed_for_s\":" + (lastArmedFor / 1000)
                + ",\"revive_delay_s\":" + (REVIVE_MS / 1000) + "}";
    }

    /**
     * onStartCommand 收到 REVIVE 时调用：计数 + 记心跳 + 重排。
     *
     * <p>★ 心跳要同时写进 {@link ReviveLog}：那个是**跨进程**的账，
     * 本类这几个 volatile 字段随进程一起消失（13 分钟那次就是死无对证）。
     */
    static void noteReviveBeat(Context ctx) {
        reviveBeats++;
        lastBeatAt = SystemClock.elapsedRealtime();
        if (ctx != null) ReviveLog.beat(ctx);
    }

    /**
     * 挂载兜底机制。可重复调用（{@link AgentService#onCreate} 与收到心跳时都会调）。
     *
     * <p>崩溃处理器只装一次；闹钟每次调用都重排/补排。**常驻心跳**只排一次
     * （用 {@code FLAG_NO_CREATE} 探测是否已在系统里）—— 不能每次重排，
     * 否则一个频繁重启的进程会把"下一次触发"永远往后推，等于把兜底推没了。
     */
    public static void install(Context ctx) {
        final Context app = ctx.getApplicationContext() == null
                ? ctx : ctx.getApplicationContext();

        readPeriod(app);   // ★ 先把"真实的周期"读回来（进程重启后 /status 才报得对）

        if (installed) {
            try {
                armBackstop(app);
            } catch (Throwable t) {
                armFail++;
            }
            return;
        }
        installed = true;
        mountedAt = SystemClock.elapsedRealtime();

        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                crashes++;
                try {
                    lastCrash = "thread=" + t.getName() + " " + chain(e);
                } catch (Throwable ignored) {}

                // ★★★ v159 主线程崩溃盾
                //   事实（2026-09-24 真机 /data/system/dropbox 13 个 dump）：除一条 native
                //   SIGABRT 外，4 类签名**全在主线程**，且都是原厂代码在我们进程里抛的
                //   未捕获 NPE：
                //     xldrbtx.robot.main.c$3.a / xldrbtx.ui.FaceActivity.onBackPressed /
                //     xldbasesdk.service.engine.EngineService$1.handleMessage -> Looper.loop
                //   栈底都是 ActivityThread.main ⇒ 主线程一死 = 整个进程死 = 唤醒词哑掉。
                //   源头我们改不了（原厂代码，见 SdkController#ensureActionCallback 那处
                //   已经补过一次、仍然不够 —— 同类漏判空不止一处），那就退到**出口**拦：
                //   记完账重新进入主线程消息循环，进程继续活着，唤醒词/8899/蓝牙一条都不掉。
                if (t == Looper.getMainLooper().getThread() && shieldOk(e)) {
                    while (true) {
                        try {
                            Looper.loop();          // ★ 重新进入主线程消息循环
                            break;                  // 正常返回 = 循环被 quit，不再拦
                        } catch (Throwable again) {
                            if (!shieldOk(again)) break;   // 超限 ⇒ 放行，走下面的复活通道
                        }
                    }
                } else {
                    // 非主线程（或有盾但放行）：保留原行为
                    try {
                        BootLog.w("★ 未捕获异常，进程即将退出  " + lastCrash);
                    } catch (Throwable ignored) {}
                }
                try {
                    int streak = ReviveLog.bumpCrashStreak(app);
                    long delay = streak >= STREAK_N ? REVIVE_SLOW_MS : REVIVE_MS;
                    if (streak >= STREAK_N) {
                        BootLog.w("★ 连续崩溃 " + streak + " 次 ⇒ 复活延迟放宽到 "
                                + (delay / 1000) + "s（只变慢，不会停）");
                    }
                    armExact(app, REQ_REVIVE, delay);   // 立刻预约复活（要唤醒）
                } catch (Throwable ignored) {
                    armFail++;
                }
                // 交回给默认处理器：保留系统原行为（写 dropbox、结束进程）
                if (prev != null) prev.uncaughtException(t, e);
            }
        });

        try {
            armBackstop(app);
        } catch (Throwable t) {
            armFail++;
        }
        BootLog.i("CrashGuard 已挂载（崩溃后 " + (REVIVE_MS / 1000) + "s 自拉起；"
                + "常驻心跳每 " + BACKSTOP_S + "s，RTC_WAKEUP）");
    }

    /**
     * 排/补排常驻心跳闹钟（幂等）。
     *
     * <p>为什么用 {@code getService(..., FLAG_NO_CREATE)} 探测而不是靠标志位：
     * 标志位在进程死掉后归零，而<b>闹钟本身还在系统里</b>；用 {@code FLAG_NO_CREATE}
     * 问的就是系统（"这个 PendingIntent 存在吗"），跟我们的内存状态无关。
     */
    private static void armBackstop(Context app) {
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            armFail++;
            return;
        }
        PendingIntent pi = getPi(app, REQ_BACKSTOP, false);
        if (pi != null) {                        // 系统里已经有了 ⇒ 不动它（别把触发时间往后推）
            backstopOn = true;
            if (backstopArmedAt == 0) backstopArmedAt = SystemClock.elapsedRealtime();
            return;
        }
        pi = getPi(app, REQ_BACKSTOP, true);
        if (pi == null) {
            armFail++;
            return;
        }
        long at = System.currentTimeMillis() + periodMs;
        am.setRepeating(AlarmManager.RTC_WAKEUP, at, periodMs, pi);
        backstopArmedAt = SystemClock.elapsedRealtime();
        lastArmedAt = backstopArmedAt;
        lastArmedFor = periodMs;
        lastBeatAt = backstopArmedAt;
        backstopOn = true;
        armedCount++;
        BootLog.i("CrashGuard: 常驻心跳闹钟已排（每 " + (periodMs / 1000) + "s，RTC_WAKEUP）");
    }

    /**
     * 读回真实周期（只读一次；进程重启后重读，所以 /status 不会报一个漂亮但假的 180）。
     */
    private static boolean periodLoaded = false;
    private static void readPeriod(Context app) {
        if (periodLoaded) return;
        periodLoaded = true;
        try {
            long sec = app.getSharedPreferences(P, 0).getLong(K_PERIOD_S, BACKSTOP_S);
            if (sec > 0) periodMs = clampPeriod(sec * 1000L);
        } catch (Throwable ignored) {}
    }

    private static long clampPeriod(long ms) {
        return Math.min(PERIOD_MAX_MS, Math.max(PERIOD_MIN_MS, ms));
    }

    /**
     * 改常驻心跳周期 —— <b>只为验收</b>（{@code POST /revive {"period_s":N}}）。
     *
     * <h3>为什么非要有这个口子</h3>
     * 「常驻心跳能把死进程拉回来」这件事，之前的测法是"冷杀 + 等" —— 结果等了 2 秒就回来了，
     * 看着像通过，实则<b>恰好撞上那条 180s 闹钟的到期时刻</b>（真机日志：
     * {@code onDestroy 23:41:22} → {@code onCreate pid=4354 23:41:26} → 同一 pid 在
     * {@code 23:44:34} 又记了一次 {@code channel=alarm}，间隔 186s）。
     * 也就是说那 2 秒是**运气**，不是证据。
     * 要把它变成证据，就必须能<b>控制到期时刻</b>：把周期改成 40s，冷杀，然后等满。
     *
     * <p>★ 改完必须<c>重新排</c>（先 {@code cancel} 再 {@code setRepeating}）：
     * 常驻闹钟是 {@code setRepeating} 排进系统的，光改我们内存里的数不会影响它。
     * ★ 周期写进 prefs：进程死后重启，报出来的仍是**真实的**那个值。
     */
    public static void setPeriod(Context ctx, long ms) {
        Context app = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
        long p = clampPeriod(ms);
        try {
            app.getSharedPreferences(P, 0).edit().putLong(K_PERIOD_S, p / 1000).commit();
        } catch (Throwable ignored) {}
        periodMs = p;
        periodLoaded = true;
        // ★ 顺手把"活着"的基准挪到此刻：否则换周期后算出来的 silent_s 会把换之前的
        //   那一段也算进去（我设 40s 却测出 60s），变成判据自己错。
        ReviveLog.rebaseBeat(app);
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) { armFail++; return; }
        try {
            PendingIntent old = getPi(app, REQ_BACKSTOP, false);
            if (old != null) am.cancel(old);      // 周期变了，旧的必须撤掉，否则两条并存
            PendingIntent pi = getPi(app, REQ_BACKSTOP, true);
            long at = System.currentTimeMillis() + p;
            am.setRepeating(AlarmManager.RTC_WAKEUP, at, p, pi);
            backstopArmedAt = SystemClock.elapsedRealtime();
            lastArmedAt = backstopArmedAt;
            lastArmedFor = p;
            lastBeatAt = backstopArmedAt;
            backstopOn = true;
            armedCount++;
            BootLog.w("CrashGuard: 常驻心跳周期改为 " + (p / 1000) + "s（验收用，记得改回 "
                    + BACKSTOP_S + "）");
        } catch (Throwable t) {
            armFail++;
        }
    }

    /**
     * 排一个**精确**的一次性唤醒闹钟（崩溃复活 / 验收用）。
     *
     * <p>用 {@code setExact} 而不是 {@code set}：API 19 起 {@code set} 是不精确的
     * （系统会把一堆闹钟攒到一起再发），"3 秒后自拉起"会被拖到几十秒甚至更久。
     * 验收脚本要的是可预期的延迟，所以这里必须精确。
     */
    static void armExact(Context app, int req, long delayMs) {
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            armFail++;
            return;
        }
        PendingIntent pi = getPi(app, req, true);
        if (pi == null) {
            armFail++;
            return;
        }
        am.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pi);
        lastArmedAt = SystemClock.elapsedRealtime();
        lastArmedFor = delayMs;
        armedCount++;
    }

    /**
     * 验收用：排一个 {@code delayMs} 后送达的唤醒闹钟。
     *
     * <p>存在的理由：要证明"闹钟通道在**进程已死**时真的能把服务拉起来"，
     * 就必须先排一个已知时刻的闹钟、再把自己弄死 —— 否则只能等常驻心跳那 3 分钟。
     * 走 {@code POST /revive {"in_s":N}}。
     */
    public static void armForTest(Context ctx, long delayMs) {
        Context app = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            armFail++;
            return;
        }
        PendingIntent pi = getPi(app, REQ_TEST, true, ACTION_REVIVE_TEST);
        if (pi == null) {
            armFail++;
            return;
        }
        am.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pi);
        lastArmedAt = SystemClock.elapsedRealtime();
        lastArmedFor = delayMs;
        armedCount++;
        BootLog.i("CrashGuard: 已排验收闹钟 " + (delayMs / 1000) + "s 后（POST /revive）");
    }

    private static PendingIntent getPi(Context app, int req, boolean create) {
        return getPi(app, req, create, ACTION_REVIVE);
    }

    private static PendingIntent getPi(Context app, int req, boolean create, String action) {
        Intent i = new Intent(app, AgentService.class);
        i.setAction(action);
        return PendingIntent.getService(app, req, i,
                create ? PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_NO_CREATE);
    }

    /** 把 cause 链拼成一行（只打 getMessage 会把真因吃掉，见 Proxies/AsrProbe 的教训）。 */
    static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && sb.length() < 600; c = c.getCause()) {
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(c.getClass().getName());
            String m = c.getMessage();
            if (m != null) sb.append(": ").append(m);
            if (c.getCause() == c) break;
        }
        return sb.toString();
    }
}
