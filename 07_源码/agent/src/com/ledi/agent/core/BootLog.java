package com.ledi.agent.core;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Agent 启动溯源日志（写文件，不用 logcat）。
 *
 * <h3>为什么不用 Log.i</h3>
 * 这台机器人（MTK 5.1 / vinci_portal）的 logcat <b>不放行任何 App 的 Java 日志</b>：
 * 框架日志（ActivityManager 等）正常，我们自己的 {@code I/LediAgent} 与原厂
 * {@code TuringSDK} 一条都刷不出来（已确认 Log.i 在 dex 里 → 是 ROM 过滤）。
 * 所以「开机到底有没有把我拉起来、是哪个广播拉起来的」只能靠写文件。
 *
 * <h3>写在哪</h3>
 * 两处都写，任一失败不影响另一处：
 * <pre>
 *   /sdcard/ledi_agent.log            电脑侧 `adb shell cat /sdcard/ledi_agent.log` 直接看
 *   /data/data/com.ledi.agent/ledi_agent.log   （root adb 可读，做历史留档）
 * </pre>
 * 每行格式：{@code MM-dd HH:mm:ss.SSS 级别 内容}，超过 64 KB 自动清空重来（别把存储写爆）。
 */
public final class BootLog {

    public static final String PATH_SDCARD = "/sdcard/ledi_agent.log";
    private static final long MAX_BYTES = 64 * 1024;
    private static final Object LOCK = new Object();

    /** 内存环形缓冲：给屏幕浮层用。读它<b>不碰磁盘</b>，所以可以每几百毫秒刷一次。 */
    private static final int RING_MAX = 200;
    private static final java.util.ArrayDeque<String> RING =
            new java.util.ArrayDeque<String>();

    private static volatile String appPath;

    private BootLog() {}

    /** AgentService.onCreate 里调一次：把「应用私有目录」也纳入写入点。 */
    public static void init(String dataDir) {
        appPath = (dataDir == null || dataDir.isEmpty()) ? null : dataDir + "/ledi_agent.log";
    }

    public static void i(String msg) { line("I", msg); }
    public static void w(String msg) { line("W", msg); }

    private static void line(String level, String msg) {
        String s = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + " " + level + " " + msg + "\n";
        synchronized (LOCK) {
            RING.addLast(s.substring(0, s.length() - 1));   // 环里不带换行
            while (RING.size() > RING_MAX) RING.pollFirst();
            append(PATH_SDCARD, s);
            if (appPath != null) append(appPath, s);
        }
    }

    /**
     * 取最近 n 行（从内存环里取，<b>不读文件</b>）。
     * 给屏幕浮层高频调用；返回串末尾带换行。
     */
    public static String recent(int n) {
        synchronized (LOCK) {
            if (RING.isEmpty()) return "";
            int keep = Math.max(1, n);
            int skip = Math.max(0, RING.size() - keep);
            StringBuilder sb = new StringBuilder(keep * 64);
            int i = 0;
            for (String l : RING) {
                if (i++ < skip) continue;
                sb.append(l).append('\n');
            }
            return sb.toString();
        }
    }

    private static void append(String path, String s) {
        try {
            File f = new File(path);
            if (f.exists() && f.length() > MAX_BYTES) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            FileWriter fw = new FileWriter(f, true);
            try {
                fw.write(s);
            } finally {
                fw.close();
            }
        } catch (Throwable ignored) {
            // 权限/路径问题就悄悄放弃 —— 日志绝不能成为服务崩溃的理由
        }
    }

    /**
     * 取最后 n 行（给 {@code GET /bootlog} 用）。
     * 读不到就返回一段说明，绝不抛异常。
     */
    public static String tail(int n) {
        synchronized (LOCK) {
            String txt = readFirstAvailable();
            if (txt == null) return "(读不到日志文件)";
            String[] all = txt.split("\n");
            int from = Math.max(0, all.length - Math.max(1, n));
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < all.length; i++) {
                sb.append(all[i]).append('\n');
            }
            return sb.toString();
        }
    }

    private static String readFirstAvailable() {
        String[] cands = appPath == null
                ? new String[]{PATH_SDCARD}
                : new String[]{appPath, PATH_SDCARD};
        for (String p : cands) {
            try {
                File f = new File(p);
                if (!f.exists()) continue;
                byte[] buf = new byte[(int) Math.min(f.length(), MAX_BYTES)];
                java.io.FileInputStream in = new java.io.FileInputStream(f);
                try {
                    int off = 0;
                    while (off < buf.length) {
                        int r = in.read(buf, off, buf.length - off);
                        if (r <= 0) break;
                        off += r;
                    }
                } finally {
                    in.close();
                }
                return new String(buf, "UTF-8");
            } catch (Throwable ignored) {
                // 换下一个候选
            }
        }
        return null;
    }
}
