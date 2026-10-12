package com.ledi.agent.core;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 主线程调度器 —— 把「必须在有 Looper 的线程上做」的事搬到主线程，并把结果同步带回来。
 *
 * <h3>为什么需要它</h3>
 * Agent 的 HTTP 请求跑在 {@code HttpServer} 自己起的裸线程上，那些线程<b>没有 Looper</b>。
 * 而下面这些动作一旦在无 Looper 线程上执行，就会抛
 * {@code RuntimeException: Can't create handler inside thread that has not called Looper.prepare()}：
 * <pre>
 *   WindowManager.addView()      → 内部 new ViewRootImpl() → new ViewRootHandler()
 *   WindowManager.removeView()   → 同上
 *   浮层 View 的构造/绘制链       → 依赖 ViewRootImpl
 * </pre>
 * 实测症状（v10 真机）：
 * <pre>
 *   POST /display {"text":"CMD TEST 2026"}
 *     → {"result":"display error: java.lang.RuntimeException: Can't create handler inside
 *        thread that has not called Looper.prepare()"}
 *   POST /face {"name":"smile"}
 *     → {"result":"play error: java.lang.RuntimeException: 同上"}
 *   POST /faceoff
 *     → {"result":"hide error: java.lang.IllegalArgumentException:
 *        View=android.widget.FrameLayout{...} not attached to window manager"}
 *        （上一次 play 的 addView 失败但 overlay 字段已被赋值，留下脏引用）
 * </pre>
 *
 * <h3>用法</h3>
 * <pre>
 *   String r = MainThread.call(new Callable&lt;String&gt;() {
 *       &#64;Override public String call() { ... 在主线程里执行 ... }
 *   }, 4000);
 * </pre>
 * 已经在主线程里调用时直接执行，不会死锁。超时/异常统一包成 RuntimeException 抛出，
 * 由各控制器 catch 后照常回 "xxx error: ..." 给调用方，行为与之前一致。
 */
public final class MainThread {

    private static volatile Handler handler;

    // ---------------------------------------------------------------- 队列仪表
    //
    // ★ 为什么必须记账：`call()` 超时时那个 job **已经 post 出去了，照样会跑完**。
    //   于是出现一种极难查的假象 —— 调用方收到 "main thread timeout" 就当这次失败
    //   走人了，可主线程稍后还是把 `startListening` 执行了：一个**没人认领的活会话**
    //   挂在那里占着麦克风，下一轮的 start/stop 还得排在它后面。
    //   v121 真机现场：`/ilisten` 报 `main thread timeout after 15000ms`，紧接着
    //   再来一轮就变成"麦开了、块也灌了、引擎却一个字没收到"（engine-deaf）。
    //   所以要能把「投了几条、跑完几条、卡了几条」直接读出来 ——
    //   判据必须锁"队列本身"，不能靠猜主线程忙不忙。
    private static final java.util.concurrent.atomic.AtomicInteger posted =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger ran =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger timeouts =
            new java.util.concurrent.atomic.AtomicInteger();
    private static volatile int lastHopMs;
    private static volatile int maxHopMs;
    private static volatile String lastTimeoutTag = "";

    private MainThread() {}

    /**
     * 队列仪表（挂到 {@code /status} 上）。
     * <p>{@code pending} = 投出去还没跑完的条数。**正常应当是 0**；
     * 超时那一刻它 &gt;0 就说明"主线程真的忙不过来"，=0 则说明主线程空闲、
     * 是我们的 job 自己卡住了 —— 两者查的方向完全不同。</p>
     */
    public static String json() {
        int p = Math.max(0, posted.get() - ran.get());
        return "{\"posted\":" + posted.get() + ",\"ran\":" + ran.get()
                + ",\"pending\":" + p + ",\"timeouts\":" + timeouts.get()
                + ",\"last_hop_ms\":" + lastHopMs + ",\"max_hop_ms\":" + maxHopMs
                + ",\"last_timeout\":\"" + lastTimeoutTag + "\"}";
    }

    public static int pending() {
        return Math.max(0, posted.get() - ran.get());
    }

    /** 在主线程（Service.onCreate / onStartCommand）里调一次即可；重复调无害。 */
    public static void init() {
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
        }
    }

    public static boolean isMain() {
        return Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper();
    }

    /**
     * 在主线程序列上执行 job 并等它做完（默认 4s 超时）。
     * job 里抛的异常会被原样包进 RuntimeException 抛给调用者。
     */
    public static <T> T call(final Callable<T> job, long timeoutMs) {
        return call(job, timeoutMs, "");
    }

    /**
     * 带标签版：超时时会把标签记进仪表（{@code /status.main_thread.last_timeout}），
     * 这样"是哪一步卡了主线程"就不用再靠翻 logcat 猜。
     */
    public static <T> T call(final Callable<T> job, long timeoutMs, final String tag) {
        if (job == null) return null;
        if (isMain()) {                       // 已经在主线程：直接跑，别 post 否则死锁
            try {
                return job.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        Handler h = handler;
        if (h == null) {                      // 兜底：没 init 过也照样能用
            h = new Handler(Looper.getMainLooper());
            handler = h;
        }
        final long tPost = System.currentTimeMillis();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<T> out = new AtomicReference<T>();
        final AtomicReference<Throwable> err = new AtomicReference<Throwable>();
        final AtomicReference<Integer> hopMs = new AtomicReference<Integer>(Integer.valueOf(-1));
        posted.incrementAndGet();
        h.post(new Runnable() {
            @Override public void run() {
                try {
                    hopMs.set(Integer.valueOf((int) (System.currentTimeMillis() - tPost)));
                    lastHopMs = hopMs.get().intValue();
                    if (lastHopMs > maxHopMs) maxHopMs = lastHopMs;
                    out.set(job.call());
                } catch (Throwable t) {
                    err.set(t);
                } finally {
                    ran.incrementAndGet();
                    done.countDown();
                }
            }
        });
        long budget = timeoutMs <= 0 ? 4000 : timeoutMs;
        try {
            if (!done.await(budget, TimeUnit.MILLISECONDS)) {
                // ★ 注意：这里只是"不等了"，**job 仍会被主线程执行**。
                timeouts.incrementAndGet();
                lastTimeoutTag = (tag == null || tag.isEmpty() ? "?" : tag)
                        + "@" + budget + "ms";
                int p = pending();
                BootLog.w("主线程超时 tag=" + lastTimeoutTag + " pending=" + p
                        + "（job 仍会被执行 —— 调用方当它失败了，实际它会跑完）");
                throw new RuntimeException("main thread timeout after " + budget
                        + "ms（pending=" + p + (tag == null || tag.isEmpty() ? "" : " tag=" + tag) + "）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        Throwable t = err.get();
        if (t != null) throw new RuntimeException(t);
        return out.get();
    }

    public static <T> T call(Callable<T> job) {
        return call(job, 4000);
    }

    /**
     * 只投递、不等结果（例如「关掉浮层」这种不需要回执的收尾动作）。
     * 有 Looper 就走主线程 post，没有也 post 到主线程。
     */
    public static void post(Runnable job) {
        if (job == null) return;
        Handler h = handler;
        if (h == null) {
            h = new Handler(Looper.getMainLooper());
            handler = h;
        }
        h.post(job);
    }
}
