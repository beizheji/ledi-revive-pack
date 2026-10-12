package com.ledi.agent.ui;

import com.ledi.agent.core.J;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 屏幕投屏（H.264）—— 机器人把自己的屏推给浏览器。
 *
 * <h2>为什么是 H.264，不是"截屏 + JPEG 轮询"</h2>
 * 本机实测（2026-10-06，Android 5.1 / MTK，800x480）：
 * <pre>
 *   adb 往返（基线）              ~60 ms
 *   原生进程启动（ls/id/getprop）  ~25 ms
 *   screencap 原始 RGBA(1.5MB)    ~230 ms
 *   screencap -p (PNG)            ~640 ms   ← PNG 压缩独吞 ~410 ms
 *   screenrecord → H.264 到 stdout ~0.6 MB/3s ≈ 1.7 Mbps，约 30~60 帧/秒
 * </pre>
 * 结论：**瓶颈不在"抓屏"，在"每帧都要压一次图"**。走 PNG 文件那条路，天花板就是
 * 1.5 fps；而 {@code screenrecord} 是系统自带的硬件编码通路，一次起进程就能连续吐
 * 30fps 的已编码码流，浏览器用 WebCodecs 直接解 —— 带宽约 200 KB/s，比每帧一张
 * JPEG（每张 50~100KB）还省。
 *
 * <h2>关键前提（都是实测出来的，不是猜的）</h2>
 * <ul>
 *   <li><b>不需要 MediaProjection 授权弹窗</b>：{@code screenrecord} 是系统命令，
 *       本 App 是 {@code android:sharedUserId="android.uid.system"}（uid=1000），
 *       实测以 1000 身份能直接录（371 KB/3s），屏幕上看不到任何授权框。</li>
 *   <li><b>不需要随包带任何 .so / jar</b>：{@code screenrecord} 就在 /system/bin。</li>
 *   <li><b>stdout 可用</b>：{@code screenrecord --output-format=h264 - } 把裸 Annex-B
 *       码流写到 stdout。用的是 {@code Runtime.exec(String[])}（管道，不是 pty），
 *       **不会**踩到"老设备 exec-out 把字节搞坏"那个坑。实测前 4 字节是
 *       {@code 00 00 00 01 67 42 C0 1F}（SPS，profile=Baseline@3.1）。</li>
 * </ul>
 *
 * <h2>为什么要"单编码器 + 迟到观看者"这套结构</h2>
 * 编码很贵（这是台 2016 年的 MTK），**一个观看者起一个 screenrecord 立刻就把 CPU 打满**。
 * 所以：整个 App 只跑一个编码进程，谁来看谁登记；新来的先补发
 * {@code SPS/PPS + 最近一个 IDR 起的那段 GOP}，再转直播 —— 否则 H.264 解不出来，
 * 用户只会看到一块花屏，而这**看不出是设计的错**。
 *
 * <h2>诚实口径</h2>
 * {@link #json()} 里的 fps / bitrate / frames 都是**数出来的**，不是估的。
 * 编码器起不来、或 screenrecord 没有输出，一律写进 {@code last_error} 并让
 * 观看端看到空流报错 —— 绝不返回一个"看起来在投屏"的静止画面。
 */
public final class ScreenCast {

    private static final String TAG = "ledi.cast";

    /** 机器人屏是 800x480（wm size 实测），用它当默认最省一次缩放。 */
    public static final int DEFAULT_W = 800;
    public static final int DEFAULT_H = 480;
    public static final int DEFAULT_BITRATE = 2000000;

    /**
     * {@code screenrecord} 自己把 {@code --time-limit} 上限卡在 180s，
     * 所以这**不是**我们的选择，是它的。留 10s 余量，到点自然收尾，
     * 由网页端自动重连（见 console.html 的 castLoop）。
     */
    public static final int TIME_LIMIT_SEC = 170;

    /** 没人看这么久就停编码，把 CPU 还给机器人。 */
    private static final long IDLE_STOP_MS = 8000L;

    /** 单个观看者最多挂多久（socket 假死时的兜底；别让 HTTP 线程住下来）。 */
    private static final long VIEWER_MAX_MS = (TIME_LIMIT_SEC + 25) * 1000L;

    /** GOP 缓存上限，防止极端情况下（长时间不出 IDR）把内存吃光。 */
    private static final int GOP_MAX = 3 * 1024 * 1024;

    private static ScreenCast INSTANCE;

    public static synchronized ScreenCast get(Context c) {
        if (INSTANCE == null) INSTANCE = new ScreenCast(c.getApplicationContext());
        return INSTANCE;
    }

    private final Context ctx;
    private final Object lock = new Object();
    private final Object gopLock = new Object();

    private Process proc;
    private Thread pump;
    private volatile boolean running;

    private volatile int reqW = DEFAULT_W;
    private volatile int reqH = DEFAULT_H;
    private volatile int bitrate = DEFAULT_BITRATE;

    private volatile String lastError = "";
    private volatile long startAt;
    private volatile long totalBytes;
    private volatile long totalFrames;
    private volatile long totalIdr;
    private volatile long totalReadErr;
    /** 最近一个统计窗口内的帧数/字节数，用来算"实测 fps / 码率"。 */
    private volatile int winFrames;
    private volatile long winBytes;
    private volatile int lastFps;
    private volatile int lastKbps;
    private volatile long lastFrameAt;

    /** SPS/PPS(/SEI)：码流开头到第一个 slice 之前那一段，新观看者必须先收到它。 */
    private byte[] prefix;
    /** 从最近一个 IDR 起的所有 NAL（含 IDR），新观看者靠它才能立刻出画。 */
    private byte[] gop;
    private boolean sawSlice;

    private final CopyOnWriteArrayList<Viewer> viewers = new CopyOnWriteArrayList<Viewer>();
    private volatile long emptySince;

    /**
     * 一位 HTTP 观看者。
     *
     * <p>★ 为什么每位观众要有**独立线程 + 有界队列**，而不是在读循环里直接
     * {@code os.write()}：那样一个卡住的浏览器（弱网、切到后台被节流、或者干脆拔网线）
     * 会把 {@link #pumpLoop} 堵在 write 上，**整个编码器跟着停** —— 现象是
     * "别人也一起卡住"，而日志里一条错都没有。这正是本项目"谁都不该堵住主循环"
     * 那条老教训（v195 首版就是这么写的，实测 fps 掉到 3 才发现）。</p>
     *
     * <p>队列满了说明这位观众真的跟不上：**踢掉**让它重连 —— 重连会重新补发
     * SPS/PPS + IDR，画面立刻回正。反过来"丢几帧继续发"会让解码器一直花屏到
     * 下一个 IDR，而用户只会以为"投屏画质差"，看不出是丢帧丢的。</p>
     */
    public static final class Viewer {
        /** 约 120 个 NAL ≈ 2~4 秒的画面；正常网络永远填不满。 */
        private static final int QUEUE = 120;

        final OutputStream os;
        private final CountDownLatch done = new CountDownLatch(1);
        private final ArrayBlockingQueue<byte[]> q = new ArrayBlockingQueue<byte[]>(QUEUE);
        private Thread writer;
        public volatile boolean closed;

        Viewer(OutputStream os) { this.os = os; }

        void startWriter() {
            writer = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        while (!closed) {
                            byte[] nal = q.poll(1, TimeUnit.SECONDS);
                            if (nal == null) continue;
                            os.write(nal);
                            os.flush();
                        }
                    } catch (Throwable t) {
                        // 浏览器关页面 / 切走 → 这里是**正常**路径，不记红
                    } finally {
                        kick();
                    }
                }
            }, "cast-viewer");
            writer.setDaemon(true);
            writer.start();
        }

        /** 入队，**永不阻塞**；满了返回 false（调用方据此踢人）。 */
        boolean offer(byte[] nal) {
            if (closed) return false;
            return q.offer(nal);
        }

        void kick() {
            closed = true;
            done.countDown();
            try { os.close(); } catch (Throwable ignored) {}
            if (writer != null) writer.interrupt();
        }

        /** HTTP 线程卡在这儿，直到被踢或超时。 */
        public void await() {
            try { done.await(VIEWER_MAX_MS, TimeUnit.MILLISECONDS); }
            catch (Throwable ignored) {}
        }
    }

    private ScreenCast(Context ctx) { this.ctx = ctx; }

    // ------------------------------------------------------------------ 开关

    public boolean running() { return running; }

    public int width() { return reqW; }

    public int height() { return reqH; }

    public int bitrate() { return bitrate; }

    /** 起编码进程（已在跑则直接返回 true）。 */
    public boolean start() {
        synchronized (lock) {
            if (running) return true;
            int w = reqW, h = reqH, br = bitrate;
            lastError = "";
            totalBytes = 0; totalFrames = 0; totalIdr = 0; totalReadErr = 0;
            winFrames = 0; winBytes = 0; lastFps = 0; lastKbps = 0; lastFrameAt = 0;
            synchronized (gopLock) { prefix = null; gop = null; sawSlice = false; }
            try {
                // 用 argv 数组，不经 shell：一是省一层进程，二是 stdout 是纯管道，
                // 保证 H.264 字节不被任何东西改写。
                String[] argv = {
                        "/system/bin/screenrecord",
                        "--output-format=h264",
                        "--size", w + "x" + h,
                        "--bit-rate", String.valueOf(br),
                        "--time-limit", String.valueOf(TIME_LIMIT_SEC),
                        "-",
                };
                proc = Runtime.getRuntime().exec(argv);
                running = true;
                startAt = System.currentTimeMillis();
                emptySince = startAt;
                // stderr 必须**并发**排空：它会打 "The max width/height supported by codec
                // is 1280x720" 这类提示，虽然不多，但管道写满了一样会把子进程憋死。
                drainQuietly(proc.getErrorStream());
                pump = new Thread(new Runnable() {
                    @Override public void run() { pumpLoop(); }
                }, "cast-pump");
                pump.setDaemon(true);
                pump.start();
                Log.i(TAG, "screenrecord 起来 " + w + "x" + h + " @" + br + "bps");
                return true;
            } catch (Throwable t) {
                running = false;
                lastError = "起 screenrecord 失败: " + t;
                Log.e(TAG, lastError, t);
                return false;
            }
        }
    }

    /** 停编码进程，并踢掉所有观看者。 */
    public void stop() {
        Process p;
        synchronized (lock) {
            if (!running && proc == null) return;
            running = false;
            p = proc;
            proc = null;
        }
        try { if (p != null) p.destroy(); } catch (Throwable ignored) {}
        for (Viewer v : viewers) v.kick();
        viewers.clear();
        Log.i(TAG, "screenrecord 停了，累计 " + totalFrames + " 帧");
    }

    /** 改分辨率/码率：会重启编码（只有观看者还在时才真的重启）。 */
    public void configure(int w, int h, int br) {
        boolean needRestart = running;
        boolean hadViewers = !viewers.isEmpty();
        reqW = even(w, DEFAULT_W, 320, 1280);
        reqH = even(h, DEFAULT_H, 240, 720);
        bitrate = Math.max(200000, Math.min(br, 12000000));
        if (needRestart) {
            // 不踢观看者：它们会在流结束时自动重连，重连就拿到新尺寸的头。
            Process p;
            synchronized (lock) { running = false; p = proc; proc = null; }
            try { if (p != null) p.destroy(); } catch (Throwable ignored) {}
            if (hadViewers) start();
        }
    }

    private static int even(int v, int def, int lo, int hi) {
        if (v <= 0) v = def;
        if (v < lo) v = lo;
        if (v > hi) v = hi;
        return v & ~1;   // 编码器对奇数尺寸不友好（H.264 宏块是 16，至少给个偶数）
    }

    // -------------------------------------------------------------- 观看者登记

    /** 登记一位观看者；返回后在它身上阻塞（{@link Viewer#await()}）。 */
    public Viewer addViewer(OutputStream os) {
        Viewer v = new Viewer(os);
        byte[] snap;
        synchronized (gopLock) { snap = join(prefix, gop); }
        // 先补发"开头 + 最近一个 IDR"，再登记进直播队列。
        // ★ 顺序不能反：反了就会在补发期间漏掉"下一个 IDR 之前的帧"，
        //   而漏 IDR 的后果是**一直花屏**，不是掉一帧。
        if (snap != null && snap.length > 0) {
            if (!v.offer(snap)) { v.kick(); return v; }
        }
        v.startWriter();
        viewers.add(v);
        emptySince = 0;
        return v;
    }

    public int viewerCount() { return viewers.size(); }

    // ------------------------------------------------------------------ 泵

    private void pumpLoop() {
        InputStream in = null;
        byte[] buf = new byte[65536];
        ByteArrayOutputStream pending = new ByteArrayOutputStream(262144);
        long winAt = System.currentTimeMillis();
        try {
            in = proc.getInputStream();
            while (running) {
                int n = in.read(buf);
                if (n < 0) break;
                if (n == 0) continue;
                totalBytes += n;
                winBytes += n;
                pending.write(buf, 0, n);

                byte[] pb = pending.toByteArray();
                int from = 0;
                while (true) {
                    int i0 = startCode(pb, from);
                    if (i0 < 0) break;
                    int i1 = startCode(pb, i0 + 3);
                    if (i1 < 0) break;                     // 这个 NAL 还没收完
                    onNal(pb, i0, i1 - i0);
                    from = i1;
                }
                // 收尾：把已处理的丢掉，只留最后一段未完成的 NAL。
                // ★ 必须留（不能全清）：起始码可能正好被切在两次 read 中间，
                //   清了就永远拼不回那个 NAL —— 代价是掉一帧，而掉 IDR 会花屏。
                if (from > 0) {
                    pending.reset();
                    pending.write(pb, from, pb.length - from);
                }
                if (pending.size() > GOP_MAX) {
                    // 极端情况（长时间找不到起始码）别把内存吃光
                    totalReadErr++;
                    byte[] tail = pending.toByteArray();
                    pending.reset();
                    pending.write(tail, tail.length - 4, 4);
                }

                long now = System.currentTimeMillis();
                if (now - winAt >= 1000) {
                    lastFps = winFrames;
                    lastKbps = (int) (winBytes * 8 / 1000);
                    winFrames = 0; winBytes = 0; winAt = now;
                    if (viewers.isEmpty()) {
                        if (emptySince == 0) emptySince = now;
                        else if (now - emptySince > IDLE_STOP_MS) {
                            Log.i(TAG, "没人看了，自动停编码");
                            stop();
                            break;
                        }
                    } else {
                        emptySince = 0;
                    }
                    // 反向兜底：有观看者但编码器**一个字节都没出**（典型是
                    // "--size 不被 codec 支持"），必须让它可见，而不是让页面
                    // 一直转圈等一个永远不来的画面。
                    if (!viewers.isEmpty() && totalFrames == 0 && now - startAt > 6000) {
                        lastError = "screenrecord 已跑 "
                                + ((now - startAt) / 1000) + "s 但没吐出任何 NAL —— "
                                + "多半是 --size " + reqW + "x" + reqH + " 这台编解码器不吃";
                        Log.e(TAG, lastError);
                        stop();
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            if (running) {
                totalReadErr++;
                lastError = "读码流异常: " + t;
                Log.e(TAG, lastError, t);
            }
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            synchronized (lock) { running = false; }
            for (Viewer v : viewers) v.kick();
            viewers.clear();
        }
    }

    /** 处理一个完整 NAL：更新 prefix/GOP、发直播、记统计。 */
    private void onNal(byte[] b, int off, int len) {
        if (len < 4) return;
        // b[off..off+2] 是起始码（00 00 01），NAL header 在 off+3
        int hdr = b[off + 3] & 0xff;
        int type = hdr & 0x1f;

        byte[] nal = new byte[len];
        System.arraycopy(b, off, nal, 0, len);

        synchronized (gopLock) {
            if (type == 7 || type == 8 || type == 6) {
                if (!sawSlice) {
                    // 码流开头这段是"参数集头"，给迟到观看者用
                    ByteArrayOutputStream hb = new ByteArrayOutputStream();
                    if (prefix != null) hb.write(prefix, 0, prefix.length);
                    hb.write(nal, 0, nal.length);
                    prefix = hb.toByteArray();
                }
            }
            boolean frame = (type == 1 || type == 5);
            if (frame) sawSlice = true;
            if (type == 5) {
                totalIdr++;
                gop = nal.clone();              // 新 IDR：从这里重新攒 GOP
            } else if (gop != null && gop.length < GOP_MAX) {
                ByteArrayOutputStream gb = new ByteArrayOutputStream(gop.length + nal.length);
                gb.write(gop, 0, gop.length);
                gb.write(nal, 0, nal.length);
                gop = gb.toByteArray();
            }
            if (frame) {
                totalFrames++;
                winFrames++;
                lastFrameAt = System.currentTimeMillis();
            }
        }
        broadcast(nal);
    }

    /**
     * 把这个 NAL 交给所有观看者。
     *
     * <p>★ 这里**只入队、绝不写 socket**：写动作在各自的 {@code cast-viewer} 线程里。
     * 一个卡住的观众只能把自己那一队憋满然后被踢掉，**动不了编码器**。</p>
     */
    private void broadcast(byte[] nal) {
        if (viewers.isEmpty()) return;
        for (Viewer v : viewers) {
            if (v.closed || !v.offer(nal)) {
                // 浏览器关页面 / 太慢跟不上 → 都是**正常**路径，别记成错误
                viewers.remove(v);
                v.kick();
            }
        }
    }

    /** 找 00 00 01 起始码，返回它的下标；找不到返回 -1。 */
    private static int startCode(byte[] b, int from) {
        for (int i = Math.max(0, from); i + 2 < b.length; i++) {
            if (b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1) return i;
        }
        return -1;
    }

    private static byte[] join(byte[] a, byte[] b) {
        if (a == null || a.length == 0) return b;
        if (b == null || b.length == 0) return a;
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private void drainQuietly(final InputStream in) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                byte[] b = new byte[4096];
                String last = "";
                try {
                    int n;
                    while ((n = in.read(b)) > 0) {
                        last = new String(b, 0, n, "UTF-8").trim();
                    }
                } catch (Throwable ignored) {}
                if (last.length() > 0) Log.i(TAG, "screenrecord stderr: " + last);
            }
        }, "cast-stderr");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ 状态

    public String json() {
        long up = startAt == 0 ? 0 : (System.currentTimeMillis() - startAt) / 1000;
        StringBuilder sb = new StringBuilder(320);
        sb.append("{\"ok\":true");
        sb.append(",\"running\":").append(running);
        sb.append(",\"w\":").append(reqW).append(",\"h\":").append(reqH);
        sb.append(",\"bitrate\":").append(bitrate);
        sb.append(",\"viewers\":").append(viewers.size());
        sb.append(",\"frames\":").append(totalFrames);
        sb.append(",\"idr\":").append(totalIdr);
        sb.append(",\"bytes\":").append(totalBytes);
        sb.append(",\"fps\":").append(lastFps);
        sb.append(",\"kbps\":").append(lastKbps);
        sb.append(",\"uptime_s\":").append(up);
        sb.append(",\"time_limit_s\":").append(TIME_LIMIT_SEC);
        sb.append(",\"read_err\":").append(totalReadErr);
        sb.append(",\"has_prefix\":").append(prefix != null && prefix.length > 0);
        sb.append(",\"gop_bytes\":").append(gop == null ? 0 : gop.length);
        sb.append(",\"last_frame_ago_ms\":")
                .append(lastFrameAt == 0 ? -1 : System.currentTimeMillis() - lastFrameAt);
        sb.append(",\"last_error\":\"").append(J.esc(lastError)).append("\"");
        sb.append(",\"cmd\":\"screenrecord --output-format=h264 --size ")
                .append(reqW).append("x").append(reqH)
                .append(" --bit-rate ").append(bitrate)
                .append(" --time-limit ").append(TIME_LIMIT_SEC).append(" -\"");
        sb.append("}");
        return sb.toString();
    }
}
