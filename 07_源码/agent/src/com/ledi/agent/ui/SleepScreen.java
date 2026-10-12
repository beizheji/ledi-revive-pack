package com.ledi.agent.ui;

import com.ledi.agent.core.BootLog;

import com.ledi.agent.core.MainThread;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import java.util.concurrent.Callable;

/**
 * 屏保层：全屏<b>铺满</b>一张图，并<b>接管整屏触摸</b>（轻点 = 退出）。
 *
 * <h3>为什么单独一个类，不塞进 DisplayController</h3>
 * 这两个全屏层的触摸语义是<b>相反</b>的，混在一起早晚会被"顺手统一"掉：
 * <ul>
 *   <li>{@link DisplayController} / {@code FaceController}：纯显示 ⇒ 必须
 *       {@code FLAG_NOT_TOUCHABLE}，把触摸<b>透传</b>给下层（历史上漏了这个 flag
 *       就出现"机器人一显示字符，整块屏幕就点不动"）。</li>
 *   <li>本类：屏保 ⇒ 必须<b>没有</b> {@code FLAG_NOT_TOUCHABLE}，把触摸<b>吃掉</b>
 *       ——"机器人功能暂时停止"期间本来也不该再点到原厂脸。
 *       窗口 flag 只能有 {@code FLAG_NOT_FOCUSABLE}。
 *       （代价：铺满 ⇒ 原生状态栏被本层盖住，实测记录见 {@code params()}。）</li>
 * </ul>
 * 参照物是 {@link GearOverlay} 的齿轮（可拖可点的那层）。
 *
 * <h3>亮度：为什么是"压暗画面"而不是"调背光"</h3>
 * 本来打算用 {@code LayoutParams.screenBrightness}（免权限、退出即自动恢复），
 * 但本机实测<b>它不生效</b>：属性确实进了窗口（{@code dumpsys window} 里
 * {@code sbrt=0.75}），然而 {@code dumpsys power} 的
 * {@code mScreenBrightnessOverrideFromWindowManager} 始终 {@code -1}、
 * {@code /sys/class/leds/lcd-backlight/brightness} 进出屏保都是 {@code 255}
 * ⇒ 这个 ROM 的 WMS 不把窗口亮度转给 PowerManager，那条路是死的。
 * 于是改用<b>实测线性有效</b>的画面压暗（半透明黑叠加）：屏幕灰度 160 → 120 @0.75。
 * 代价说清楚：它压暗的是<b>画面</b>，背光仍全开、暗部会偏灰；换来的是"确实看得见的变暗"。
 *
 * <p>★ v158：默认系数改成 <b>1.0</b>（用户 2026-09-24 要求"亮度改到 100"）⇒ 默认
 * <b>不叠</b>这层黑，屏保图按原亮度显示。压暗机制本身保留：
 * {@code POST /sleep {"on":true,"bright":0.75}} 仍可临时压暗，重启回 1.0。
 */
public final class SleepScreen {

    /** 手指移动超过这个像素数就算拖拽/手滑，不当成"轻点"。 */
    private static final int TAP_SLOP = 30;

    /**
     * 屏幕<b>按住</b>多久才算"刻意想醒"（毫秒）。
     *
     * <h3>为什么必须留这条兜底出口（用户要求"只有长按头顶键才能恢复"）</h3>
     * 用户的诉求是"屏保期间别被碰一下就醒"，这条已经用"轻点无效"满足了。
     * 但把这个出口<b>只</b>留成头顶键是有真实风险的：头顶键广播是<b>原厂那边发出来的</b>
     * （{@code com.auric.intell.xld.rbt.key.head.*}），而 v140 起屏保会 SIGSTOP 原厂进程 ——
     * 万一广播发送方被冻住、或广播链路本身出问题，用户就会面对"屏幕上铺着图、
     * 按头顶键没反应、只剩电脑端能救"的局面。本项目对这类"进去了出不来"的代价
     * 一向是宁可留一条安全绳。
     *
     * <p>取 5 秒而不是 3 秒：躺下时脸压到桌面、手扶着机器人，都只会产生"点"或短促按压，
     * 产生不了 5 秒的<b>持续</b>按压。5 秒是"人明确想让它醒"的量级。
     * 想彻底关掉这条兜底：{@code POST /sleep {"hold_exit":false}}。</p>
     */
    public static final long HOLD_EXIT_MS = 5000;

    /** 屏幕长按兜底是否可用（用户可在 /sleep 里关掉，关掉后只剩头顶键与远程）。 */
    private static volatile boolean holdExit = true;

    public static void setHoldExit(boolean on) { holdExit = on; }

    public static boolean holdExit() { return holdExit; }

    /** 屏幕被按住超过阈值而退出屏保的次数（诊断：区分"轻点误触"与"刻意长按"）。 */
    private static volatile int holds;

    private static volatile View view;
    private static volatile boolean shown;
    private static volatile int taps;
    private static volatile long lastTouchMs;
    /**
     * ⚠ <b>这不是真源</b>：真源是 {@link SleepMode} 的那个 bright（默认 1.0f），
     * 本字段只是"上次 {@link #show} 用的值"的镜像 —— 因为 SleepMode 每次都会显式
     * 把它传进来。留一个同值的默认，是为了防止有人直接调
     * {@code show(..., b<=0, ...)} 时拿到一个跟别处不一致的数。
     * （v158 之前这里是 0.75f，与 SleepMode 各存一份 —— 典型的"同一规则散在多处"。）
     */
    private static volatile float bright = 1.0f;

    private SleepScreen() {}

    public static boolean isShown() { return shown; }
    public static int taps() { return taps; }
    public static float bright() { return bright; }

    /**
     * 铺上屏保。
     *
     * @param b      屏幕亮度系数（0..1）；{@code <0} 表示不改
     * @param onExit 用户轻点屏幕时回调（在<b>主线程</b>执行）
     */
    public static String show(final Context ctx, final Bitmap bmp,
                             final float b, final Runnable onExit) {
        if (bmp == null) return "bitmap required";
        if (b > 0f) bright = b;
        final float use = bright;
        try {
            return MainThread.call(new Callable<String>() {
                @Override public String call() throws Exception {
                    hideInner(ctx);
                    WindowManager wm = (WindowManager)
                            ctx.getSystemService(Context.WINDOW_SERVICE);
                    ImageView iv = new ImageView(ctx);
                    // ★ CENTER_CROP = 按比例放大到铺满 + 居中裁切 —— 与系统多任务背景
                    //   同一效果（已用滑窗逐行比对确认：两者显示的都是原图 y∈[160,640]）。
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    iv.setBackgroundColor(Color.BLACK);
                    iv.setImageBitmap(bmp);
                    // ★ 亮度 = **画面压暗**（半透明黑叠加），不是窗口 screenBrightness。
                    //   本机实测（v122，证据 _bl_probe.txt）：窗口属性 sbrt=0.75 确实设上了，
                    //   但 dumpsys power 的 mScreenBrightnessOverrideFromWindowManager 始终 -1、
                    //   /sys/class/leds/lcd-backlight/brightness 进出屏保都是 255
                    //   ⇒ 这个 ROM 的 WMS 不把窗口亮度转给 PowerManager，那条路是**死的**，
                    //     写进去只会让人以为"亮度已经能调了"。
                    //   画面压暗实测线性有效：屏幕灰度 160 → 120（@0.75）。
                    int dimA = Math.round((1f - use) * 255f);
                    if (dimA > 0) {
                        iv.setColorFilter(new PorterDuffColorFilter(
                                Color.argb(Math.min(255, dimA), 0, 0, 0),
                                PorterDuff.Mode.SRC_OVER));
                    }

                    final float[] down = {0f, 0f};
                    final long[] downMs = {0L};
                    final boolean[] longFired = {false};
                    iv.setOnTouchListener(new View.OnTouchListener() {
                        @Override public boolean onTouch(View v, MotionEvent ev) {
                            switch (ev.getActionMasked()) {
                                case MotionEvent.ACTION_DOWN:
                                    down[0] = ev.getRawX();
                                    down[1] = ev.getRawY();
                                    downMs[0] = System.currentTimeMillis();
                                    longFired[0] = false;
                                    return true;
                                case MotionEvent.ACTION_UP:
                                    float moved = Math.abs(ev.getRawX() - down[0])
                                            + Math.abs(ev.getRawY() - down[1]);
                                    long held = System.currentTimeMillis() - downMs[0];
                                    lastTouchMs = System.currentTimeMillis();
                                    // ★★★ v142：**轻点不再退出屏保**。
                                    //
                                    // 这是用户 2026-09-24 报的"躺下后 / 触摸头部时屏保被强行关闭"
                                    // 的直接成因，而且一直是设计缺陷而不是故障：
                                    //   头顶触摸区（face.head.*）就在**同一块触摸屏的顶部**，
                                    //   而本层是全屏的、又刻意没有 FLAG_NOT_TOUCHABLE（要吃掉触摸
                                    //   不让它透到下面那张原厂脸）⇒ 用户摸一下头顶，
                                    //   事件先落到我们这层，变成一次 <30px 的轻点 ⇒ 直接 exit()。
                                    //   也就是说"抹头顶"和"点一下屏幕"在触摸层长得一模一样，
                                    //   光靠坐标无解 ⇒ 干脆把"轻点"这个动作从屏保里删掉。
                                    // 现在退出只有三条路：头顶键长按（主）/ 远程 POST /sleep off /
                                    // 屏幕持续按压 HOLD_EXIT_MS（兜底，见字段注释）。
                                    if (longFired[0]) return true;      // 已按长按处理过
                                    if (moved >= TAP_SLOP) {
                                        taps++;
                                        BootLog.i("屏保 轻点/拖拽 " + (int) moved + "px "
                                                + held + "ms —— 已忽略（屏保只认长按）");
                                        return true;
                                    }
                                    if (holdExit && held >= HOLD_EXIT_MS) {
                                        holds++;
                                        longFired[0] = true;
                                        BootLog.i("屏保 ★屏幕按住 " + held + "ms ≥ "
                                                + HOLD_EXIT_MS + "ms → 退出（兜底出口，第 "
                                                + holds + " 次）");
                                        if (onExit != null) onExit.run();
                                    } else {
                                        taps++;
                                        BootLog.i("屏保 轻点 " + held + "ms（< " + HOLD_EXIT_MS
                                                + "ms）忽略 —— 站立/躺下/摸头顶都长这样，不退出");
                                    }
                                    return true;
                            }
                            return false;
                        }
                    });

                    wm.addView(iv, params());
                    view = iv;
                    shown = true;
                    BootLog.i("屏保 已上屏 bright=" + use + "（画面压暗 alpha=" + dimA
                            + "）尺寸=" + bmp.getWidth() + "x" + bmp.getHeight());
                    return "sleep screen on (bright=" + use + ", dim_alpha=" + dimA + ")";
                }
            }, 8000);
        } catch (Exception e) {
            BootLog.w("屏保 上屏失败 " + e);
            return "sleep screen fail: " + e;
        }
    }

    public static String hide(final Context ctx) {
        try {
            return MainThread.call(new Callable<String>() {
                @Override public String call() throws Exception {
                    // ★ 先取原值再 hideInner()：hideInner 会把 shown 置 false，
                    //   写在它后面就成了"永远走同一分支"的恒定判据（本项目踩过这个坑）。
                    boolean was = shown;
                    hideInner(ctx);
                    return was ? "sleep screen off" : "sleep screen not shown";
                }
            }, 5000);
        } catch (Exception e) {
            return "sleep hide fail: " + e;
        }
    }

    /** 只在主线程调；引用可能根本没挂上去过，直接丢。 */
    private static void hideInner(Context ctx) {
        try {
            if (view != null) {
                WindowManager wm = (WindowManager)
                        ctx.getSystemService(Context.WINDOW_SERVICE);
                wm.removeView(view);
            }
        } catch (Exception e) {
            BootLog.w("屏保 removeView 跳过: " + e);
        }
        view = null;
        shown = false;
    }

    /**
     * 窗口参数。
     * <p><b>绝不要加 {@code FLAG_NOT_TOUCHABLE}</b>：加了这层就收不到触摸，
     * "轻点退出" 直接失效 —— 而屏保是全屏的，那之后就只能在电脑端点 {@code POST /sleep}
     * 才能把机器人叫醒。
     */
    private static WindowManager.LayoutParams params() {
        // 刻意**不设** screenBrightness：本机实测它不生效（证据见 show() 里的注释），
        // 写进去只会被后来的人误读成"背光已经能调"。
        //
        // ★ 取舍说明（实验记录见 _sysbar_cmp.txt）：
        //   1) 不需要 FLAG_FULLSCREEN：FLAG_LAYOUT_IN_SCREEN 已经让窗口铺满整屏
        //      （含状态栏那 38px 区域），多一个全屏请求只会让行为更难推理。
        //   2) PixelFormat 用 TRANSLUCENT，与 /img 通道保持一致。
        // ⚠ **已实测并接受的代价：全屏铺满 ⇒ 状态栏被盖住。**
        //   受控实验三档对读：原厂脸本身**有**状态栏（顶带最暗值 0、均值 6.9）；
        //   一旦叠上我方 /img 或屏保，顶带就变成壁纸色（均值 197.7、最暗 17）。
        //   而 {FLAG_FULLSCREEN 有/无} × {OPAQUE/TRANSLUCENT} 的结果**逐字节相同**
        //   ⇒ 与这两个 flag 无关，纯粹是图层高低：本层 ty=2002 > 状态栏 ty=2000，
        //     图又铺满整屏，状态栏自然被压在下面。
        //   想留住状态栏就只能让窗口不覆盖顶部，那与用户要的"按原来铺满"直接冲突 ——
        //   所以选择铺满，屏保期间不显示状态栏。
        return new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_PHONE,      // system UID 免授权
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
    }

    public static String json() {
        long ago = lastTouchMs == 0 ? -1 : (System.currentTimeMillis() - lastTouchMs);
        return "{\"shown\":" + shown
                + ",\"bright\":" + bright
                // 实际叠加的半透明黑 alpha（0=不压暗）——用于核对"参数有没有真的落到画面上"
                + ",\"dim_alpha\":" + Math.round((1f - bright) * 255f)
                + ",\"taps\":" + taps
                + ",\"holds\":" + holds
                + ",\"hold_exit\":" + holdExit
                + ",\"hold_exit_ms\":" + HOLD_EXIT_MS
                + ",\"touch_ago_ms\":" + ago + "}";
    }
}
