package com.ledi.agent.sdk;

import com.ledi.agent.core.J;

import com.ledi.agent.core.BootLog;

import com.ledi.agent.ui.SleepMode;

import android.app.ActivityManager;
import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;

/**
 * 屏保期间把"机器人的其他软件"冻起来。
 *
 * <h3>为什么要有它（用户 2026-09-24 明确要求）</h3>
 * 「屏保开启时机器人其他软件先都停止，减少设备性能开支」。
 *
 * <p>屏保 = 用户不在场。这时原厂那套（{@code com.auric.intell.rbt.xldrbtx} 及其
 * {@code :core} / {@code :cosine} 子进程）没有任何人需要它：桌面被我们的屏保窗口盖住、
 * 动作/表情/TTS 都已经在 {@link SleepMode} 里停过。但它仍然占着约 35 MB PSS，
 * 而且随时可能被自己的定时器唤醒。冻起来就是把这份"待机税"免掉。</p>
 *
 * <h3>★ 为什么用 SIGSTOP/SIGCONT，而不是 force-stop 或 pm disable</h3>
 * <ul>
 *   <li>{@code am force-stop}：真的把进程杀掉 ⇒ 退出屏保要重新冷启动原厂（慢、
 *       可能起不来）、而且它被标成 stopped 状态；</li>
 *   <li>{@code pm disable}：见 {@link HomeSetter} —— 会留下**持久**的禁用状态，
 *       而且实测并不杀进程（旧归因已证伪），**永不用它做实验**；</li>
 *   <li>{@code SIGSTOP}：进程还在、内存还在、**只是不跑**。{@code SIGCONT}
 *       立刻恢复，不需要任何重新初始化。真机实测：{@code S → T → S}，
 *       utime/stime 在冻结期间**完全不动**（698/471 三个采样点一模一样）。</li>
 * </ul>
 *
 * <h3>★★ 为什么命令要落成脚本文件（这一条最容易踩）</h3>
 * 本机的 {@code su} 是"**只接受单个可执行文件、零参数**"的语义：
 * <pre>
 *   su -c id                  → 通（uid=0(root)）
 *   su -c "kill -STOP 2938"   → su: exec failed for kill -STOP 2938
 *   su -c "for d in ..."      → su: exec failed for for d in ...
 * </pre>
 * 它拿 {@code -c} 后面那一整串当**一个路径**去 exec，不做 shell 分词。
 * 所以 {@code for}/{@code case}/{@code $()} 这些语法**只能写进脚本文件**，
 * 再用 {@code su -c <脚本路径>}（单可执行文件、零参数 ⇒ 正好走通的那条路）。
 *
 * <h3>★★ 动作与范围都由"文件名"决定，因为传不了参数</h3>
 * <pre>
 *   ledi_freeze.sh      → 冻结全部（主进程 + 子进程）
 *   ledi_freeze_sub.sh  → 只冻结带冒号的子进程
 *   ledi_thaw.sh        → 解冻全部
 * </pre>
 *
 * <h3>★★★ 三条安全约束</h3>
 * <ol>
 *   <li><b>冻之前先看前台是不是原厂</b>。实测：只要前台是我们的
 *       {@code BrainActivity}（部署脚本第 4 步把桌面绑到我们），冻原厂主进程 6 秒
 *       窗口焦点纹丝不动、无 ANR。但**如果哪天把桌面切回原厂**，它就是前台窗口，
 *       冻它必然 ANR ⇒ 那时只冻子进程（{@link #freezeAsync(Context, boolean)}）。</li>
 *   <li><b>进程一启动就无条件解冻一次</b>（{@link #thawAllAsync}）。上一次屏保
 *       如果在"冻完、还没解冻"时进程被杀，原厂会**一直冻着**，表现成"机器人半死"
 *       —— 界面不刷新、动作不响应，而且看不出原因。这条自愈是必须的。</li>
 *   <li><b>解冻必须同步且早于唤醒</b>。{@code SleepMode.exit()} 里先解冻、再
 *       {@code unsuspend()}：唤醒引擎起来就会去调原厂 SDK，那时候原厂还冻着的话
 *       binder 调用会**挂起**（不是失败，是永远等下去），比崩掉更难查。</li>
 * </ol>
 */
public class ProcessFreezer {

    /** 原厂 app 的包名 —— 冻结目标就是它和它的所有 {@code :xxx} 子进程。 */
    private static final String FACTORY_PKG = "com.auric.intell.rbt.xldrbtx";

    private static final String SH_FREEZE = "ledi_freeze.sh";
    private static final String SH_FREEZE_SUB = "ledi_freeze_sub.sh";
    private static final String SH_THAW = "ledi_thaw.sh";

    /**
     * 脚本正文 —— 只用 {@code cat}/{@code case}/{@code test}/{@code kill}/{@code echo}。
     *
     * <p>★ 本 ROM 缺 {@code tr/awk/sed/cut/basename/wc}（实测），所以一个都不能用。
     * 匹配用 {@code $d/comm}（**无 NUL**，而 {@code cmdline} 里有 NUL，在 shell 里
     * 拿来做 {@code case} 会静默不匹配）。comm 被截断到 15 字符，所以原厂三个进程
     * 分别是 {@code ell.rbt.xldrbtx} / {@code bt.xldrbtx:core} / {@code .xldrbtx:cosine}
     * —— 用 {@code *xldrbtx*} 一把全中，而我们自己（{@code com.ledi.agent}）不匹配。</p>
     */
    private static final String SCRIPT =
            "#!/system/bin/sh\n"
            + "# LediAgent 自动生成：屏保时冻结/解冻原厂进程。手改会被覆盖。\n"
            + "# ★ 本机 su 不支持传参，所以动作(A)和范围(SCOPE)都由**文件名**决定。\n"
            + "case \"$0\" in\n"
            + "  *thaw*) A=CONT ;;\n"
            + "  *)      A=STOP ;;\n"
            + "esac\n"
            + "case \"$0\" in\n"
            + "  *sub*)  SCOPE=1 ;;\n"
            + "  *)      SCOPE=0 ;;\n"
            + "esac\n"
            + "for d in /proc/[0-9]*; do\n"
            + "  c=$(cat $d/comm 2>/dev/null)\n"
            + "  case \"$c\" in\n"
            + "    *xldrbtx*)\n"
            + "      if [ $SCOPE = 1 ]; then\n"
            + "        case \"$c\" in *:*) ;; *) continue ;; esac\n"
            + "      fi\n"
            + "      p=${d#/proc/}\n"
            + "      kill -$A $p 2>/dev/null && echo \"$A $p $c\"\n"
            + "      ;;\n"
            + "  esac\n"
            + "done\n"
            + "echo FROZER_DONE\n";

    // ---------------- 状态（供 /status 观察，不参与判定） ----------------

    private static volatile boolean frozen = false;
    private static volatile String lastAct = "";
    private static volatile String lastOut = "";
    private static volatile long lastAt = 0;
    private static volatile int lastN = 0;
    private static volatile boolean mainSkipped = false;
    private static volatile String lastErr = "";
    /** 最近一次实际用的冻结范围（sub/all/off）—— 排查"按键为什么没了"时先看它。 */
    private static volatile String usedMode = "";

    private static final String PREFS = "ledi_agent";
    private static final String K_MODE = "freeze_mode";

    public static final String MODE_SUB = "sub";     // 只冻子进程（默认，保住按键通路）
    public static final String MODE_ALL = "all";     // 全冻（省电最多，但会掐断原厂的广播）
    public static final String MODE_OFF = "off";     // 不冻

    /**
     * 冻结范围（默认 {@link #MODE_SUB}）。
     *
     * <h3>★★★ 为什么默认不是 "all"（v142 改的默认值，理由要留着）</h3>
     * v140/v141 的默认是"连原厂主进程一起冻"，验收也过了（3/3 进程进 {@code T}）。
     * 但那次验收只证明了<b>机制</b>生效，没有证明<b>出口还在</b>：
     * 用户 2026-09-24 明确要求「屏保后只有长按头顶键才能恢复使用」，
     * 而头顶键的广播 {@code com.auric.intell.xld.rbt.key.head.*} 是**原厂发出来的** ——
     * 冻住了发广播的那个进程，就等于把唯一的物理出口从源头掐掉了。
     *
     * <p>⇒ 默认 {@code sub}：既省掉子进程（含 {@code :core} 这个 SDK 宿主）那份开销，
     * 又保住按键/感应广播通路。哪个更省只有真机对照能答，
     * 所以留了 {@code POST /sleep {"freeze":"all"}} 做实验开关 ——
     * 判据是：<b>冻成 all 之后，长按头顶键还能不能把屏保叫醒</b>。
     * 能，就改回 all；不能，就说明主进程必须留着。</p>
     */
    public static String mode(Context ctx) {
        try {
            String m = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(K_MODE, MODE_SUB);
            if (m == null) return MODE_SUB;
            m = m.trim().toLowerCase();
            if (MODE_ALL.equals(m) || MODE_OFF.equals(m) || MODE_SUB.equals(m)) return m;
        } catch (Throwable ignored) {}
        return MODE_SUB;
    }

    public static String setMode(Context ctx, String m) {
        if (m == null) return "mode=" + mode(ctx);
        String v = m.trim().toLowerCase();
        if (!MODE_ALL.equals(v) && !MODE_OFF.equals(v) && !MODE_SUB.equals(v)) {
            return "mode 只接受 sub|all|off（收到 " + m + "）";
        }
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(K_MODE, v).apply();
        } catch (Throwable ignored) {}
        BootLog.i("冻结范围改为 " + v);
        return "mode=" + v;
    }

    /** 按配置的范围冻结（屏保用这个）。 */
    public static void freezeAsync(final Context ctx, final String modeStr) {
        final Context app = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
        if (MODE_OFF.equals(modeStr)) {
            BootLog.i("冻结: 配置为 off，不冻原厂");
            return;
        }
        freezeAsync(ctx, MODE_ALL.equals(modeStr));
    }

    private ProcessFreezer() {}

    // ------------------------------------------------------------------ 对外

    /**
     * 冻结（异步，不阻塞调用方 —— 屏保进出都在主线程/HTTP 线程上，不能卡）。
     *
     * @param includeMain 是否连原厂主进程一起冻。前台是原厂时必须传 {@code false}。
     */
    public static void freezeAsync(final Context ctx, final boolean includeMain) {
        final Context app = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    // ★ 前台保护：原厂是前台窗口时绝不冻主进程（否则必然 ANR）
                    boolean fg = factoryForeground(app);
                    boolean withMain = includeMain && !fg;
                    mainSkipped = includeMain && fg;
                    if (mainSkipped) {
                        BootLog.w("冻结: 原厂当前是前台 → 只冻子进程（冻主进程会 ANR）");
                    }
                    usedMode = withMain ? MODE_ALL : MODE_SUB;
                    String out = exec(app, withMain ? SH_FREEZE : SH_FREEZE_SUB);
                    frozen = countOk(out) > 0;
                    record("freeze" + (withMain ? "-all" : "-sub"), out);
                    BootLog.i("冻结: " + summarize(out));
                } catch (Throwable t2) {
                    lastErr = String.valueOf(t2);
                    BootLog.w("冻结失败: " + t2);
                }
            }
        }, "ledi-freezer");
        t.setDaemon(true);
        t.start();
    }

    /** 冻结：按"能不能连主进程"自己判断（给 /freeze 用）。 */
    public static void freezeAsync(final Context ctx) {
        freezeAsync(ctx, true);
    }

    /**
     * 解冻（**同步**）。
     *
     * <p>刻意同步：{@code SleepMode.exit()} 后面紧接着就要 {@code unsuspend()} 唤醒引擎，
     * 而唤醒引擎一起来就会调原厂 SDK。解冻还没落地就唤醒 ⇒ binder 调用挂在冻结的
     * 进程上，永远等不到回复。脚本本身只跑几十毫秒，同步的代价远小于那个风险。</p>
     */
    public static boolean thawSync(Context ctx) {
        Context app = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
        try {
            String out = exec(app, SH_THAW);
            int n = countOk(out);
            frozen = false;
            mainSkipped = false;
            usedMode = "";
            record("thaw", out);
            if (n > 0) BootLog.i("解冻: " + summarize(out));
            return n > 0 || out.contains("FROZER_DONE");
        } catch (Throwable t) {
            lastErr = String.valueOf(t);
            BootLog.w("解冻失败: " + t);
            return false;
        }
    }

    /**
     * 无条件解冻一次（**进程启动时就调**）。
     *
     * <p>自愈用：上一次屏保若在"冻完还没解冻"时进程被杀，原厂会一直冻着。异步跑，
     * 因为它不该拖慢 {@code onCreate}。</p>
     */
    public static void thawAllAsync(final Context ctx) {
        final Context app = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String out = exec(app, SH_THAW);
                    int n = countOk(out);
                    frozen = false;
                    record("thaw-boot", out);
                    if (n > 0) {
                        BootLog.w("启动自愈: 发现 " + n + " 个原厂进程还冻着 → 已解冻");
                    }
                } catch (Throwable ignored) {}
            }
        }, "ledi-thaw-boot");
        t.setDaemon(true);
        t.start();
    }

    public static String statusJson() {
        long ago = lastAt == 0 ? -1 : (System.currentTimeMillis() - lastAt) / 1000;
        return "{\"frozen\":" + frozen
                + ",\"last\":\"" + J.esc(lastAct) + "\""
                + ",\"ago_s\":" + ago
                + ",\"n\":" + lastN
                + ",\"main_skipped\":" + mainSkipped
                + ",\"mode_used\":\"" + usedMode + "\""
                + ",\"out\":\"" + J.esc(lastOut) + "\""
                + ",\"err\":\"" + J.esc(lastErr) + "\"}";
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 原厂是不是当前前台进程 —— 是的话**非屏保期间**绝不能冻它
     * （前台窗口的进程被 STOP，一旦有输入要派发给它就会 ANR）。
     *
     * <h3>★★★ 但屏保期间返回 false —— 这是实测结论，不是推理</h3>
     * 「冻前台进程会 ANR」的前提是**真的有输入要派发给它**。屏保中我们的浮层全屏
     * 盖住一切 ⇒ 原厂窗口既不可见也不可聚焦 ⇒ 没有输入会落到它头上。
     *
     * <p>真机实测（2026-09-24，{@code _v140_freeze_main.py}）：前台确认是原厂
     * {@code xldrbtx/.ui.FaceActivity}，屏保中 {@code SIGSTOP} 主进程 10 秒 ——
     * <b>{@code dumpsys window} 里没有 Not Responding 窗口、{@code dumpsys activity}
     * 里没有 ANR 记录、{@code mCurrentFocus}/{@code mFocusedApp} 纹丝不动</b>，
     * {@code SIGCONT} 后回到 {@code S}。</p>
     *
     * <p>而屏保期间恰恰**最该**冻它：主进程 26 MB PSS，是三个原厂进程里最大的，
     * 只冻两个子进程等于把大头留在场上。</p>
     *
     * <p>★ 所以保护只对"非屏保时手动 {@code POST /freeze}（验收/调试用）"生效 ——
     * 那种场景下原厂正在前台接待用户，冻它就会 ANR。</p>
     */
    private static boolean factoryForeground(Context ctx) {
        if (SleepMode.sleeping) return false;      // ★ 屏保中：浮层盖住一切，冻它安全（实测）
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            List<ActivityManager.RunningAppProcessInfo> ps = am.getRunningAppProcesses();
            if (ps == null) return false;
            for (ActivityManager.RunningAppProcessInfo p : ps) {
                if (p == null || p.processName == null) continue;
                if (!p.processName.startsWith(FACTORY_PKG)) continue;
                if (p.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * ① 保证脚本在位（内容小、每次重写，免得升级后残留旧版本）② 用 su 跑它。
     *
     * <p>★ 方法名<b>不能叫 {@code run}</b>：下面几处调用都在匿名 {@code Runnable} 的
     * {@code run()} 方法体里，而**内部类的方法名会遮蔽外部类的同名静态方法** ——
     * 那样写出来的 {@code run(app, SH_THAW)} 会被解析成无参的 {@code Runnable.run()}
     * 并报"无法将方法 run 应用到给定类型"（编译期就炸，v140 第一次构建正是栽在这）。</p>
     */
    private static String exec(Context ctx, String name) {
        String path = ensureScript(ctx, name);
        if (path == null) return "ERR 脚本落盘失败";
        return suRun(path, 6000);
    }

    private static String ensureScript(Context ctx, String name) {
        try {
            File f = new File(ctx.getFilesDir(), name);
            FileOutputStream fo = new FileOutputStream(f);
            Writer w = new OutputStreamWriter(fo, "UTF-8");
            try {
                w.write(SCRIPT);
                w.flush();
            } finally {
                w.close();
            }
            f.setReadable(true, false);
            f.setExecutable(true, false);   // ★ 不设可执行 ⇒ su exec 会 EACCES
            return f.getAbsolutePath();
        } catch (Throwable t) {
            lastErr = "ensureScript " + t;
            return null;
        }
    }

    /**
     * 执行 {@code su -c <一个路径>}。
     *
     * <p>★ 只读一路流：用 {@code redirectErrorStream(true)} 把 stderr 并进 stdout。
     * 分成两路而只读一路，管道写满就会**死锁**（su 卡在写 stderr，我们卡在读 stdout）。</p>
     *
     * <p>★ 超时用 {@code reader.join(ms)} 而不是 {@code Process.waitFor(long,TimeUnit)}
     * —— 后者是 API 26 才有的，本机是 API 22。</p>
     */
    private static String suRun(String path, int timeoutMs) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/su", "-c", path);
            pb.redirectErrorStream(true);
            p = pb.start();
            final Process fp = p;
            final StringBuilder sb = new StringBuilder();
            Thread rd = new Thread(new Runnable() {
                @Override public void run() {
                    BufferedReader br = null;
                    try {
                        br = new BufferedReader(new InputStreamReader(fp.getInputStream(), "UTF-8"));
                        String l;
                        while ((l = br.readLine()) != null) {
                            if (sb.length() < 1500) sb.append(l).append(' ');
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        try { if (br != null) br.close(); } catch (Throwable ignored) {}
                    }
                }
            }, "ledi-freezer-rd");
            rd.setDaemon(true);
            rd.start();
            rd.join(timeoutMs);
            if (rd.isAlive()) {
                try { fp.destroy(); } catch (Throwable ignored) {}
                lastErr = "su 超时(" + timeoutMs + "ms)";
                return "TIMEOUT";
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            lastErr = String.valueOf(t);
            return "ERR " + t;
        } finally {
            if (p != null) { try { p.destroy(); } catch (Throwable ignored) {} }
        }
    }

    /** 脚本每动一个进程 echo 一行 "STOP <pid> <comm>" ⇒ 数行数就知道动了几个。 */
    private static int countOk(String out) {
        if (out == null || out.isEmpty()) return 0;
        int n = 0, i = 0;
        String[] marks = {"STOP ", "CONT "};
        while (i < out.length()) {
            int best = -1;
            for (String m : marks) {
                int k = out.indexOf(m, i);
                if (k >= 0 && (best < 0 || k < best)) best = k;
            }
            if (best < 0) break;
            n++;
            i = best + 5;
        }
        return n;
    }

    private static String summarize(String out) {
        int n = countOk(out);
        if (out == null) return "n=0";
        if (out.contains("ERR")) return "err=" + out;
        if (out.contains("TIMEOUT")) return "超时";
        return "动了 " + n + " 个进程";
    }

    private static void record(String act, String out) {
        lastAct = act;
        lastOut = out == null ? "" : (out.length() > 220 ? out.substring(0, 220) : out);
        lastN = countOk(out);
        lastAt = System.currentTimeMillis();
        if (out != null && (out.contains("ERR") || out.contains("TIMEOUT"))) lastErr = out;
        else lastErr = "";
    }
}
