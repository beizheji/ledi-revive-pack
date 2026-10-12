package com.ledi.agent.core;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 远程执行一条 shell 命令（{@code POST /shell}）。
 *
 * <h2>为什么它同时是"功能"和"保险"</h2>
 * <ul>
 *   <li><b>功能</b>：手机端要做「自动化控制机器人」。绝大多数动作走我们自己的 HTTP API
 *       （动作/表情/灯光/AI/媒体…）比 shell 好得多，但总有一些系统级操作没有 API
 *       （改属性、重启 adbd、看进程、清缓存），这时它是不必重打 APK 的逃生舱。</li>
 *   <li><b>保险</b>：我在研究"无线 ADB 能不能关"时，需要重启 adbd 来做对照实验 ——
 *       而 adbd 一旦起不来，就会**同时丢掉 USB 和 WiFi 两条 adb 通道**。
 *       所以必须先把这条不依赖 adbd 的通道修好，否则那是一次"可能把自己锁在门外"的实验。
 *       本项目一贯纪律：**做危险实验之前，先铺好回来的路。**</li>
 * </ul>
 *
 * <h2>安全边界（说清楚，不含糊）</h2>
 * 这台机器人的 adbd 是 {@code ro.adb.secure=0} + {@code ro.secure=0}，
 * 即**同一局域网内任何人连 5555 就是 root shell**（见 {@link AdbWifi} 的类注释）。
 * 也就是说，"能远程执行命令"这件事在本机**本来就是既成事实**，本端点没有新增暴露面。
 *
 * <p>但它仍然**可以被关掉**：{@code POST /shell {"on":false}} 写 prefs {@code shell_on=false}，
 * 之后所有请求直接拒绝。谁都不该白白多开一个口子 —— 哪怕它和隔壁那个口子一样大。
 *
 * <p>★ 例外：{@link #RECOVERY_CMDS} 里的命令（只含启停 adbd 所需的最小集合）
 * 在 shell 关闭时**仍然放行**。否则一旦把 adbd 搞掉又关了 shell，就只能拆机了 ——
 * 这正是"保险"两个字的意义。
 */
public final class Shell {

    private static final String K_ON = "shell_on";
    /** 单次命令的输出上限，防止 `cat /dev/urandom` 把内存和 HTTP 连接一起打爆。 */
    private static final int MAX_OUT = 32 * 1024;
    /** 默认超时。够跑完大多数系统命令，又不至于让一次 HTTP 请求挂死。 */
    private static final int DEF_TIMEOUT_MS = 15000;

    /**
     * shell 被关掉时**依然放行**的最小命令集合（前缀匹配，已去空白）。
     * 目的只有一个：把 adbd 拉回来。别往里加别的东西。
     */
    private static final String[] RECOVERY_CMDS = {
            "setprop ctl.start adbd",
            "setprop ctl.restart adbd",
            "setprop ctl.stop adbd",
            "setprop service.adb.tcp.port",
            "getprop service.adb.tcp.port",
            "start adbd",
            "stop adbd",
    };

    private Shell() {}

    public static boolean enabled(android.content.Context ctx) {
        return ctx.getSharedPreferences("ledi_agent", android.content.Context.MODE_PRIVATE)
                .getBoolean(K_ON, true);
    }

    public static void setEnabled(android.content.Context ctx, boolean on) {
        ctx.getSharedPreferences("ledi_agent", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean(K_ON, on).apply();
    }

    /** 这条命令是不是"保命白名单"里的（据此决定 shell 关了要不要破例）。 */
    private static boolean isRecovery(String cmd) {
        String c = cmd == null ? "" : cmd.trim();
        for (String r : RECOVERY_CMDS) if (c.startsWith(r)) return true;
        return false;
    }

    public static String handle(android.content.Context ctx, String cmd, boolean root, int timeoutMs) {
        if (cmd == null || cmd.trim().isEmpty())
            return "{\"error\":\"缺少 cmd\"}";
        boolean allow = enabled(ctx) || isRecovery(cmd);
        if (!allow) {
            return "{\"error\":\"远程 shell 已关闭（POST /shell {\\\"on\\\":true} 可开）\","
                    + "\"enabled\":false}";
        }
        if (timeoutMs <= 0) timeoutMs = DEF_TIMEOUT_MS;
        if (timeoutMs > 60000) timeoutMs = 60000;

        long t0 = System.currentTimeMillis();
        Process p = null;
        Drain out = null, err = null;
        try {
            // uid=1000(system) 下大多数系统操作够用；需要 root 时显式走 su。
            // `su -c` 这条在本机是通的（BtPan 的网络共享就是这么做）。
            String[] argv = root
                    ? new String[]{"/system/bin/su", "-c", cmd}
                    : new String[]{"/system/bin/sh", "-c", cmd};
            p = Runtime.getRuntime().exec(argv);
            // ★ stdout/stderr 必须**并发**排空：只读一路的话，另一路管道写满后
            //   子进程会卡在 write 上永远不退出，表现为"命令超时"——而真正的原因是被我们憋死的。
            out = new Drain(p.getInputStream());
            err = new Drain(p.getErrorStream());
            out.start();
            err.start();
            // ★ 不能用 Process.waitFor(long, TimeUnit) —— 那是 **API 26+** 才有的方法，
            //   本机是 Android 5.1(API 22)，调用会当场 NoSuchMethodError
            //   （真机实测：{"error":"No virtual method waitFor(JLjava/util/concurrent/TimeUnit;)Z ..."}）。
            //   老平台的写法是把 waitFor() 丢进一个线程再 join 超时。
            final Process fp = p;
            Thread waiter = new Thread(new Runnable() {
                @Override public void run() {
                    try { fp.waitFor(); } catch (Throwable ignored) {}
                }
            }, "shell-wait");
            waiter.setDaemon(true);
            waiter.start();
            waiter.join(timeoutMs);
            if (waiter.isAlive()) {
                p.destroy();
                out.join(400);
                err.join(400);
                long ms = System.currentTimeMillis() - t0;
                return "{\"rc\":-1,\"timeout\":true,\"ms\":" + ms
                        + ",\"out\":\"" + J.esc(out.text()) + "\""
                        + ",\"err\":\"" + J.esc(err.text()) + "\"}";
            }
            out.join(1500);
            err.join(1500);
            long ms = System.currentTimeMillis() - t0;
            return "{\"rc\":" + p.exitValue()
                    + ",\"ms\":" + ms
                    + ",\"out\":\"" + J.esc(out.text()) + "\""
                    + ",\"err\":\"" + J.esc(err.text()) + "\""
                    + ",\"trunc\":" + (out.truncated || err.truncated) + "}";
        } catch (Throwable t) {
            return "{\"error\":\"" + J.esc(String.valueOf(t)) + "\"}";
        } finally {
            try { if (p != null) { p.getInputStream().close(); p.getErrorStream().close(); } }
            catch (Throwable ignored) {}
        }
    }

    /** 单路管道的排空线程（有上限，超了继续读但丢弃 —— 必须继续读，否则一样会憋死子进程）。 */
    private static final class Drain extends Thread {
        private final InputStream in;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        volatile boolean truncated;

        Drain(InputStream in) { this.in = in; setDaemon(true); }

        @Override public void run() {
            byte[] tmp = new byte[4096];
            try {
                int n;
                while ((n = in.read(tmp)) > 0) {
                    if (buf.size() < MAX_OUT) buf.write(tmp, 0, Math.min(n, MAX_OUT - buf.size()));
                    else truncated = true;
                }
            } catch (Throwable ignored) {}
        }

        String text() { return new String(buf.toByteArray(), java.nio.charset.Charset.forName("UTF-8")); }
    }
}
