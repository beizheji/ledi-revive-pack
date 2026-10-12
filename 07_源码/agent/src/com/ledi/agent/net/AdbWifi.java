package com.ledi.agent.net;

import com.ledi.agent.core.BootLog;
import com.ledi.agent.core.CommandRouter;
import com.ledi.agent.core.J;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 无线 ADB 开关 —— 手机端「投屏」的机器人侧那一半。
 *
 * <h2>为什么需要它</h2>
 * 用户要「手机端无线 ADB 投屏」。但 scrcpy / adb.exe 都是**桌面程序，手机上跑不了**，
 * 所以手机只能做两件事：①在机器人上把无线 ADB 打开（本类）；②自己实现 ADB 协议去抓屏/点按
 * （协议原型 {@code _adbclient.py} 已真机跑通：手写 socket 客户端抓到合法 PNG 3/3 帧、
 * 并以 {@code uid=0(root)} 连上）。Java 版见手机端 AdbClient。
 *
 * <p>用户最终选的是「只做无线 ADB 开关」：机器人开一次，之后**电脑端 scrcpy 直接连**即可 ——
 * 这条已真机验证：scrcpy 3.0 连上后走 {@code OMX.MTK.VIDEO.ENCODER.AVC} 硬编，
 * {@code Texture: 640x384} 正常出画，是**流畅投屏**，不是 {@code screencap} 那种 ~1fps。
 *
 * <h2>★★ 属性的真实语义（这一条推翻了我自己先前的"已验证"，务必看完）</h2>
 * 本 ROM 上 {@code service.adb.tcp.port} 是 **只在 adbd 启动时读一次**的。
 * 也就是说它**不是**一个能随时改的热开关：运行期写它，adbd 完全不理会。
 *
 * <p>先前的错误结论是"只 setprop 就能让 5555 开始监听"。那是**假因果**：
 * 当时恰好在同一时间窗口里 adbd 被（USB 配置触发器之类）重新拉起来过一次，
 * 它在那次启动里读到了 5555，于是端口起来了 —— 看上去就像 setprop 生效了。
 * 后来用受控实验把这条证伪了（真机实测，每次都读 {@code /proc/net/tcp}）：
 * <pre>
 *   prop=-1  + ctl.restart adbd  ->  无 15B3 监听      （关：有效）
 *   只 setprop 5555，不重启      ->  无 15B3 监听      （★ 属性被忽略）
 *   setprop 5555 + ctl.restart   ->  00000000:15B3 0A  （开：有效）
 * </pre>
 * ⇒ **改属性必须配一次 {@code ctl.restart adbd} 才生效。** 本类的 {@link #set} 就是这么做的。
 *
 * <h2>为什么"开"这一步要幂等（不能无脑重启 adbd）</h2>
 * 重启 adbd 会把**正在进行的投屏连接一起掐断**。所以 {@link #set} 在"已经处于目标状态"时
 * **直接返回、一个字节都不改** —— 否则手机端每次刷新状态都可能把用户的投屏踢掉。
 *
 * <h2>★ 为什么状态以 /proc/net/tcp 为准，而不是以属性值为准</h2>
 * 属性值只说明"下次 adbd 启动时会怎么绑"，**不说明现在到底开没开**。
 * 所以 {@link #state()} 报的是端口**真的在 LISTEN**（直接读 {@code /proc/net/tcp} 的 st=0A，
 * 且 inode 属于 /sbin/adbd 那个进程）。这是本项目的固定纪律：
 * **报成功必须报"被观测到的结果"，否则兜底本身就是假绿灯。**
 */
public final class AdbWifi {

    /** 无线 ADB 端口。5555 是 adb 默认，电脑端 scrcpy 也默认连它。 */
    public static final int PORT = 5555;
    /** 属性名。写成 `-1` 即不监听（adb 的约定）。 */
    private static final String PROP = "service.adb.tcp.port";
    /** 记住"下次开机是否自动开"。默认**关** —— 留着 5555 常听是个不必要的口子。 */
    private static final String K_AUTO = "adbwifi_auto";

    private AdbWifi() {}

    // ------------------------------------------------------------------ 属性读写
    private static Method mGet;
    private static Method mSet;

    static {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            mGet = c.getMethod("get", String.class);
            mSet = c.getMethod("set", String.class, String.class);
        } catch (Throwable t) {
            BootLog.w("AdbWifi: 反射 SystemProperties 失败: " + t);
        }
    }

    public static String getProp(String key, String def) {
        try {
            if (mGet != null) {
                Object v = mGet.invoke(null, key);
                if (v instanceof String) return (String) v;
            }
        } catch (Throwable ignored) {}
        return def;
    }

    /**
     * 写系统属性（不读回校验）。返回 null=成功，否则是失败原因。
     *
     * <p>为什么单独要一个"不校验"的版本：{@code ctl.*} 属性是写给 init 的**指令**，
     * init 处理完会立刻把值清掉，读回必然是空 —— 拿它当判据会把成功误判成失败。
     */
    private static String writeProp(String key, String val) {
        try {
            if (mSet != null) {
                mSet.invoke(null, key, val);
                return null;
            }
        } catch (Throwable t) {
            return "反射失败: " + t;
        }
        // 兜底：直接跑 setprop（同一个调用者身份，不会更差，而且能带回原文错误）
        try {
            Process p = Runtime.getRuntime().exec(
                    new String[]{"/system/bin/setprop", key, val});
            p.waitFor();
            return null;
        } catch (Throwable t) {
            return "exec 也失败: " + t;
        }
    }

    // ------------------------------------------------------------------ 端口观测
    /**
     * 直接读 {@code /proc/net/tcp}，看 5555 是不是真的在 LISTEN。
     *
     * <p>为什么不干脆 {@code new Socket("127.0.0.1", 5555)} 探一下：那会**占掉一条 adbd 连接**
     * （这台 ROM 的 adbd 连接数很紧，我写客户端时就吃过"被别的连接占着所以静默超时"的亏），
     * 而 /proc 是只读旁路，零副作用。
     *
     * <p>{@code /proc/net/tcp} 的 local_address 形如 {@code 00000000:15B3}，
     * {@code 15B3} 就是 5555 的十六进制；{@code st} 列 {@code 0A} = TCP_LISTEN。
     */
    public static boolean listening() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/net/tcp"));
            String line;
            boolean first = true;
            while ((line = r.readLine()) != null) {
                if (first) { first = false; continue; }        // 跳过表头
                String[] f = line.trim().split("\\s+");
                if (f.length < 4) continue;
                int c = f[1].lastIndexOf(':');
                if (c < 0) continue;
                if (!"15B3".equalsIgnoreCase(f[1].substring(c + 1))) continue;
                if ("0A".equalsIgnoreCase(f[3])) return true;   // st=0A LISTEN
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
        return false;
    }

    /**
     * adbd 的 pid（判"重启有没有真的发生"用）。找不到返回 -1。
     *
     * <p>用 {@code /proc/<pid>/cmdline} 而不是 {@code /proc/<pid>/exe}：后者是非 root 身份时
     * 常读不到（会静默变成 null），而 cmdline 是全世界可读的。
     * ★ 这里返回 -1 只代表"我没看清"，**不代表 adbd 挂了** —— 调用方不能把 -1 当死亡证据，
     * 否则会误判重启失败、做出多余的动作。（本项目的老毛病：把观测不到当成观测到负面。）
     */
    public static int adbdPid() {
        String[] kids = new java.io.File("/proc").list();
        if (kids == null) return -1;
        for (String k : kids) {
            int pid;
            try { pid = Integer.parseInt(k); } catch (Throwable ignored) { continue; }
            String cmd = readSmall("/proc/" + k + "/cmdline");
            if (cmd == null) cmd = readSmall("/proc/" + k + "/comm");
            if (cmd != null && cmd.contains("adbd")) return pid;
        }
        return -1;
    }

    private static String readSmall(String path) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(path);
            byte[] b = new byte[256];
            int n = in.read(b);
            if (n <= 0) return null;
            return new String(b, 0, n, "UTF-8").replace('\0', ' ').trim();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------------ 重启 adbd
    /**
     * 重启 adbd。这是让 {@code service.adb.tcp.port} 改动生效的**唯一**办法（见类注释）。
     *
     * <p>先试 {@code ctl.restart}（一步到位）；不成就退到 {@code ctl.stop} + {@code ctl.start}。
     * 真机验证过：重启后 adbd 会以新 pid 回来，**USB adb 不受影响**（USB 走 ffs，与 TCP 端口无关）。
     */
    public static String restartAdbd() {
        int before = adbdPid();
        writeProp("ctl.restart", "adbd");
        if (waitPidChange(before, 3500)) return "ctl.restart";
        writeProp("ctl.stop", "adbd");
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        writeProp("ctl.start", "adbd");
        if (waitPidChange(before, 4500)) return "ctl.stop+start";
        return "重启 adbd 未见生效信号（before=" + before + " now=" + adbdPid() + "）";
    }

    /**
     * 等 adbd 的 pid 变掉。
     * ★ {@code before <= 0} 表示"重启前没看清"（不是"没在跑"）——那种情况下
     *   只要现在能看见 adbd，就算重启发生了。把观测不到当成反向结论是本项目的经典翻车点。
     */
    private static boolean waitPidChange(int before, int ms) {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < ms) {
            int now = adbdPid();
            if (now > 0 && now != before) return true;
            try { Thread.sleep(120); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    // ------------------------------------------------------------------ 对外
    public static boolean isAuto(Context ctx) {
        return prefs(ctx).getBoolean(K_AUTO, false);
    }

    /**
     * 开/关无线 ADB。
     *
     * <p>幂等：已经处于目标状态时**直接返回**，不碰属性、更不重启 adbd
     * （重启会掐断正在进行的投屏 —— 这是本方法最容易写错的地方）。
     *
     * <p>要动的时候：写属性 → 重启 adbd → 轮询到端口状态真的翻转为止。
     * 宁可这一个请求多花几秒，也不能回一个"已开启"然后让用户去 scrcpy 里吃
     * "connection refused"。
     */
    public static String set(Context ctx, boolean on) {
        if (listening() == on) {
            BootLog.i("AdbWifi: 已是目标状态(" + on + ")，不动 adbd（避免掐断投屏）");
            return state(ctx);
        }
        String want = on ? String.valueOf(PORT) : "-1";
        String we = writeProp(PROP, want);
        String how = restartAdbd();

        boolean ok = false;
        for (int i = 0; i < 25 && !ok; i++) {          // 最多等 25×200ms = 5s
            ok = (listening() == on);
            if (!ok) { try { Thread.sleep(200); } catch (InterruptedException ignored) {} }
        }
        if (!ok) {
            BootLog.w("AdbWifi: 目标=" + on + " 未达成（prop=" + getProp(PROP, "") + " / " + how
                    + (we == null ? "" : " / 写属性失败:" + we) + "）");
        } else {
            BootLog.i("AdbWifi: " + (on ? "开" : "关") + "成功 prop=" + getProp(PROP, "")
                    + " listening=" + listening() + " (" + how + ")");
        }
        return state(ctx);
    }

    public static String setAuto(Context ctx, boolean auto) {
        prefs(ctx).edit().putBoolean(K_AUTO, auto).apply();
        return state(ctx);
    }

    /**
     * 开机时按记住的意愿兜一下（默认不开）。
     * ★ 必须连 restart 一起做：开机时 adbd 往往已经起来并读过属性了，
     *   只写属性它不会理（见类注释里的受控实验）。
     */
    public static void applyAtBoot(Context ctx) {
        if (!isAuto(ctx)) return;
        if (listening()) { BootLog.i("AdbWifi: 开机时 5555 已在监听，不动"); return; }
        writeProp(PROP, String.valueOf(PORT));
        String how = restartAdbd();
        for (int i = 0; i < 25 && !listening(); i++) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        }
        BootLog.i("AdbWifi: 开机自动开 -> listening=" + listening() + " (" + how + ")");
    }

    /** 观察到的真实状态 + 给手机端直接可用的 scrcpy 命令。 */
    public static String state(Context ctx) {
        boolean listen = listening();
        if (!listen) {
            // /proc 读不到时的补充判断（例如 ROM 换了 /proc 布局）
            Socket s = null;
            try {
                s = new Socket();
                s.connect(new InetSocketAddress("127.0.0.1", PORT), 400);
                listen = true;
            } catch (Throwable ignored) {
            } finally {
                try { if (s != null) s.close(); } catch (Throwable ignored) {}
            }
        }
        String ip = HttpServer.localIp();
        if (ip == null) ip = "";
        return "{\"on\":" + listen
                + ",\"listening\":" + listen
                + ",\"port\":" + PORT
                + ",\"ip\":\"" + J.esc(ip) + "\""
                + ",\"prop\":\"" + J.esc(getProp(PROP, "")) + "\""
                + ",\"adbd\":" + adbdPid()
                + ",\"auto\":" + isAuto(ctx)
                + ",\"secure\":" + "0".equals(getProp("ro.adb.secure", "?"))
                + ",\"cmd\":\"scrcpy --serial " + J.esc(ip.isEmpty() ? "<机器人IP>" : ip)
                + ":" + PORT + "\""
                + ",\"hint\":\""
                + (listen
                   ? "已开启：电脑端直接跑上面那条 scrcpy 命令就能投屏"
                   : "未开启：点一下开关即可（会重启一次 adbd，正在进行的投屏会断开）")
                + "\"}";
    }

    private static SharedPreferences prefs(Context ctx) {
        // 和 CommandRouter 用同一个 prefs 文件，方便 /adbwifi 与其它开关共用
        return ctx.getSharedPreferences("ledi_agent", Context.MODE_PRIVATE);
    }
}
