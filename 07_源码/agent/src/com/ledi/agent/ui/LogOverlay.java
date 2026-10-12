package com.ledi.agent.ui;

import com.ledi.agent.core.J;

import com.ledi.agent.core.CommandRouter;

import com.ledi.agent.core.BootLog;

import com.ledi.agent.core.AgentService;

import com.ledi.agent.core.MainThread;
import com.ledi.agent.voice.VoiceWakeController;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * LogOverlay —— 机器人屏幕上的**实时运行日志浮层**。
 *
 * <h3>为什么要它</h3>
 * 真机调试时，人站在机器人旁边，但日志在电脑上（`adb shell cat /sdcard/ledi_agent.log`），
 * 或者要开 HTTP 去拉 —— 边说话边看日志根本来不及。这个浮层把日志直接铺在机器人脸上，
 * 说一句、看一眼，当场就知道"听见没有 / 认出没有 / 卡在哪"。
 *
 * <h3>显示什么</h3>
 * <pre>
 *   v148 起：一块面板 = 【左列】触摸块 + 日志（默认 8 行）
 *                     【右列】小绿点/收起 + 三条电机滑条 + 陀螺仪 3D
 *   日志第 1 行：状态头（版本 / 听 / 忙 / 说 / 常识别 / 唤醒次数 / 唤醒词命中 / 抹头顶强制）
 *   之后       ：最近 N 行日志（内存环，默认 8 行）
 * </pre>
 *
 * <h3>三条实现铁律（都踩过）</h3>
 * <ol>
 *   <li><b>必须主线程 addView</b>：HTTP 线程里直接 addView 会抛
 *       "Can't create handler inside thread that has not called Looper.prepare()"，
 *       所以统一走 {@link MainThread#post}。</li>
 *   <li><b>flag 组合要按"能不能点"来选，别抄错</b>：
 *       v148 之前日志面板是<b>纯信息层</b>，必须带 {@code FLAG_NOT_TOUCHABLE}
 *       （否则吞掉整屏触摸）。<b>v148 起面板里有滑条了，必须能收触摸</b>
 *       ⇒ <b>去掉</b> {@code FLAG_NOT_TOUCHABLE}，但<b>绝不能</b>少了
 *       {@code FLAG_NOT_TOUCH_MODAL}（少了它，面板之外的触摸也会被整块吃掉）。
 *       折叠小标同理只能是「NOT_FOCUSABLE + NOT_TOUCH_MODAL」。</li>
 *   <li><b>绝不写日志</b>：tick 里一旦调 {@code BootLog.x}，就会自己喂自己，
 *       轻则日志被自己的行刷满，重则死循环。所以浮层<b>只读不写</b>日志。</li>
 *   <li><b>挂载要幂等</b>：换窗口类型时真实出现过"新窗口挂上、旧的没拆"，屏幕上两个
 *       26×26 小标叠着 —— 于是点击落到哪个说不清。所以成功挂过的都记账，每次挂载后
 *       清掉不在册的（见 {@link #handleMade} / {@link #dropStaleHandles}）。</li>
 * </ol>
 *
 * <p>开关与外观走 {@code POST /logwin}（{@code /pad} 是它的别名），状态走 {@code GET /logwin}。
 */
public final class LogOverlay {

    private static final String PREF = "ledi_overlay";
    private static final String K_ON = "logwin_on";
    private static final String K_LINES = "logwin_lines";
    private static final String K_SIZE = "logwin_size";
    private static final String K_X = "logwin_x";
    private static final String K_Y = "logwin_y";
    /** 小标 / 面板的窗口类型（名字）。见 {@link #typeOf} 的注释：这是个真机上"看层级"的旋钮。 */
    private static final String K_LAYER = "logwin_layer";
    private static final String K_PLAYER = "logwin_panel_layer";
    /** 外观默认值的"版本水印"，见 {@link #migrateUiOnce}。 */
    private static final String K_UI_REV = "logwin_ui_rev";
    private static final String DEF_LAYER = "error";
    /**
     * 面板窗口类型默认值。
     *
     * <p>★★★ <b>v152 从 {@code "overlay"} 改成 {@code "error"} —— 这是"滑条拖不动"的真因。</b>
     *
     * <p>{@code "overlay"} = {@code TYPE_SYSTEM_OVERLAY}(2006)，本 ROM 上它是
     * <b>纯显示层，压根不接收触摸</b>（这一点在 {@link #typeOf} 的注释里早就写着，
     * 但面板的默认值一直没跟着改）。而 v148 起面板里装了**真能驱动电机的滑条** ——
     * 于是面板画面正常、每个控件都画出来了，<b>却一个都点不动</b>。
     *
     * <p>真机取证（2026-09-24，同一块面板、只改这一项）：
     * <pre>
     *   panel_ty=2006  input tap 120,420（底部开关条）  → /online 纹丝不动
     *   panel_ty=2010  input tap 120,420（同一坐标）    → /online  true → false   立刻翻转
     *   panel_ty=2010  input swipe 540,132 → 700,132    → last_cmd='start[140,150,-6,1]end'
     *                                                     raw[0] 416 → 842，frames 1798 → 1825
     * </pre>
     *
     * <p>⚠ 排查时先走的是"是不是滑条被压扁了/被别的窗口盖住了"这条路，全是错的：
     * 面板窗口参数是 {@code (0,0)(780x438)}，滑条真实存在、位置也对，
     * <b>只是这个窗口类型天生收不到输入事件</b>。⇒ 形状对 ≠ 能交互，
     * 判据必须是"点下去有没有反应"，不是"看不看得见"。
     */
    private static final String DEF_PLAYER = "error";

    /**
     * 本版外观默认值的版本号。
     * <p>★ v148 把三个默认值一起改了（字号 10→8、行数 12→8、tick 700→200）。
     * <b>但改默认值对老装机是无效的</b>：{@link #K_SIZE}/{@link #K_LINES}/{@link #K_PLAYER}
     * 只要被手动设过一次就永久留在 prefs 里，{@code getInt(K, 8)} 这个"默认值"再也读不到 ——
     * 真机上就成了"我明明改了默认值，屏幕上一个字都没变小"。
     * 所以升一次版本、把这些键**清掉一次**，让新默认真正落到老装机上。
     *
     * <p>★ v152：{@code UI_REV} 148 → 152，并把 {@link #K_PLAYER} 一起清掉 ——
     * 否则老装机里存着 {@code "overlay"}，面板照样收不到触摸，
     * 只改代码里的默认值等于没改（这正是 v151 之前的现场）。
     */
    private static final int UI_REV = 152;

    /**
     * 窗口类型名 → 类型值。
     *
     * <p>为什么要有这个旋钮：真机实测（`dumpsys window` 的 `mBaseLayer`）——
     * <pre>
     *   原厂 FaceActivity 的 SurfaceView  ty=1000  layer=31000
     *   我们的 TYPE_PHONE 窗口            ty=2002  layer=31000   ← 同一个层！被脸压住，看不见
     *   我们的日志面板 TYPE_SYSTEM_OVERLAY ty=2006  layer=191000  ← 浮在最上，能显示
     * </pre>
     * 而 {@code TYPE_SYSTEM_OVERLAY} 又是**纯显示层、不接收触摸**。
     * 所以「既要在脸上面、又要能点」的类型只能实测确定，不能照 AOSP 文档推 ——
     * MTK 这份 ROM 的层表跟 AOSP 不一样。做成运行时参数就能逐个试，不用反复重编译。
     */
    private static int typeOf(String name) {
        if (name == null) return -1;
        if ("phone".equals(name)) return WindowManager.LayoutParams.TYPE_PHONE;
        if ("alert".equals(name)) return WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        if ("overlay".equals(name)) return WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
        if ("priority".equals(name)) return WindowManager.LayoutParams.TYPE_PRIORITY_PHONE;
        if ("dialog".equals(name)) return WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG;
        if ("error".equals(name)) return WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
        if ("keyguard".equals(name)) return WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
        return -1;
    }

    /** 主选 + 兜底，去重后依次尝试（前一个 addView 抛异常就换下一个）。 */
    private static int[] typeChain(String prefKey, String dflt) {
        // ★★ v152：兜底链里的 TYPE_PHONE 与 TYPE_SYSTEM_OVERLAY **两个都是死路**，已剔除。
        //   · TYPE_SYSTEM_OVERLAY(2006)：纯显示层，不接收触摸（本文件 typeOf 的注释里写着）
        //     ⇒ 拿它当兜底，等于"面板画出来了但点不动"，是最坏的那种假绿灯。
        //   · TYPE_PHONE(2002)：能点，但和原厂脸同层（都是 31000）⇒ 被脸压住看不见。
        //   换成三个真心能用（既在脸上面、又能收触摸）的：error / alert / priority / dialog。
        int[] cand = {
                typeOf(prefs().getString(prefKey, dflt)),
                WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,      // 2010：本机实测可点
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,      // 2003
                WindowManager.LayoutParams.TYPE_PRIORITY_PHONE,    // 2007
                WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG,     // 2008
        };
        int n = 0;
        int[] out = new int[cand.length];
        for (int c : cand) {
            if (c <= 0) continue;
            if (c == WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY) continue;   // 见上：死路
            boolean dup = false;
            for (int i = 0; i < n; i++) if (out[i] == c) dup = true;
            if (!dup) out[n++] = c;
        }
        int[] r = new int[n];
        System.arraycopy(out, 0, r, 0, n);
        return r;
    }

    /** v148：面板里多了滑条与陀螺仪 3D，700ms 太慢（拖滑条看着像卡死）⇒ 提到 200ms。 */
    private static final long TICK_MS = 200;
    /**
     * 面板【左列】宽度（px）—— 日志与触摸块。
     * <p>定宽是必须的：日志一行很长，用 WRAP_CONTENT 会一路撑到屏幕边，
     * 而且中文一个字符占用两倍宽，长度按"字符数"截断根本挡不住折行 ——
     * 结果每行都被折成两行，面板高度翻倍、把脸挡掉大半（v86 真机实测）。
     */
    private static final int LEFT_W_PX = 470;
    /** 面板【右列】宽度（px）—— 小绿点 / 三条滑条 / 陀螺仪 3D。 */
    private static final int RIGHT_W_PX = 310;

    /** 折叠小标的**可视**尺寸（用户指定 10×10 像素）。 */
    private static final int HANDLE_PX = 10;
    /**
     * 小标四周外扩的**透明热区**（视觉仍是 10×10）。
     * 纯 10×10 在 800×480 的屏上几乎点不中，所以热区外扩到 10+2*8=26px。
     * 因为小标窗口带 {@code FLAG_NOT_TOUCH_MODAL}，这块热区只吃它自己范围内的触摸，
     * 不会影响屏幕其他地方。
     */
    private static final int HANDLE_PAD = 8;
    /**
     * 小标窗口相对屏幕顶的 y 偏移 = <b>负的热区宽度</b>。
     *
     * <p>光把窗口挂到 y=0 还不够：窗口里那个 <b>可见</b> 的 10×10 亮点居中，上下各留
     * {@link #HANDLE_PAD} 的透明热区，所以亮点的上边缘还在屏幕下方 9px（真机量出来的包围盒
     * `y=9..16`）。用户要的是「**紧贴屏幕最上方**」，指的是看得见的那个点 ⇒
     * 把整个窗口往上提 8px，亮点的上边缘就落在 y=0（窗口最上面 8px 落到屏幕外，无所谓）。
     */
    private static final int HANDLE_Y = -HANDLE_PAD;

    /**
     * 原生状态栏占用的顶部条带高度（px）——实测自 {@code dumpsys window windows}：
     * {@code Window{… StatusBar} mAttrs=WM.LayoutParams{(0,0)(fillx38) gr=#30 ty=2000}}，
     * 即**整条 800×38**，时间/电量/蓝牙/WiFi 图标靠右排。
     *
     * <p>★ 用户要求（2026-09-22）：「确保不要挡住原生的时间和电量还有蓝牙状态、wifi 状态」。
     * 我们的浮层是 {@code ty=2010}（{@code TYPE_SYSTEM_ERROR}），**层级比状态栏(ty=2000)更高**，
     * 压上去就真的把原生状态盖掉 —— 不是"看得见看不见"的问题，是**一定会盖**。
     */
    private static final int STATUS_BAND_H = 38;

    /** 状态栏图标实际占用的横向起点（图标靠右排，左侧是空的，可以放东西）。 */
    private static final int STATUS_ICON_X = 560;

    private static Context appCtx;
    private static WindowManager wm;
    /** 日志文本（面板上半部分）。v148 起面板下半部分还有调试控件。 */
    private static TextView tv;
    /** 面板容器（日志 + 触摸/陀螺仪/电机控件）。addView / removeView 一律用这个。 */
    private static View panel;
    /** 上次 tick 时间戳 —— 给姿态 3D 积分用（本机无陀螺仪，见 DebugPad）。 */
    private static long lastTickAt = 0L;
    private static View handle;                 // 折叠小标（常显，是这块浮层的唯一入口）
    /**
     * 曾经成功挂上过的所有小标窗口。
     *
     * <p>为什么要记账：**换类型时会出现"新窗口挂上了、旧窗口没被拆掉"**，屏幕上就同时有
     * 两个 26×26 的小标（v91 真机实测：`ty=2010` 在 y=0、残留的 `ty=2002` 在 y=38）。
     * 两个窗口叠着，`input tap` 打到哪个不确定，点击排查就会得出自相矛盾的结论。
     * 所以只要成功 addView 过就记下来，每次挂载后把不在册的都清掉 —— 幂等，不依赖
     * "上一次一定 removeView 成功"。
     */
    private static final java.util.ArrayList<View> handleMade = new java.util.ArrayList<View>();
    /** 同上，日志面板（换类型时同样出现过"新窗口挂上、旧的没拆"，两个面板叠着）。 */
    private static final java.util.ArrayList<View> panelMade = new java.util.ArrayList<View>();
    private static Handler handler;
    private static Runnable ticker;
    private static String cachedVer = "";

    /** 实际挂上去用的窗口类型值（-1 = 没挂）。用来对 `dumpsys window` 里的 `ty=` 做对照。 */
    private static int usedPanelType = -1;
    private static int usedHandleType = -1;
    /** 最近一次挂载失败的异常（给小标和面板各留一条，便于真机判断"到底是没挂上还是挂了看不见"）。 */
    private static String lastPanelErr = "";
    private static String lastHandleErr = "";

    private static volatile boolean shown = false;

    private LogOverlay() {}

    // ------------------------------------------------------------------
    // 对外：安装 / 开关 / 外观
    // ------------------------------------------------------------------

    /**
     * AgentService.onCreate 里调一次。
     * <p>小标（折叠入口）**永远挂上**；日志面板**开机一律收起**。
     *
     * <p>★ 用户要求（2026-09-22）：「开机默认不显示这个 log 栏，必须用户手动点击 log 入口
     * 才能显示出来。」所以这里<b>不再恢复上次的展开状态</b>（{@link #K_ON}）——
     * 以前是 {@code if (prefs().getBoolean(K_ON,false)) show()}，一旦上次调试时开着，
     * 重启后它又自己铺出来挡住脸，用户根本不知道为什么。{@code K_ON} 仍然表示
     * "本次运行期的当前状态"，只是每次开机从"关"起步。</p>
     */
    public static void install(Context ctx) {
        appCtx = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
        migrateUiOnce();                                 // ★ v148：让新的外观默认值真正生效
        prefs().edit().putBoolean(K_ON, false).apply();   // 开机从"收起"起步
        hide();                                          // 先无条件拆干净，再按规则决定挂什么
        dropStaleHandles(null);
        handle = null;
        usedHandleType = -1;
        syncVisibility();
    }

    /**
     * ★★ 面板可见性的**唯一**判据（v152 收紧）。
     *
     * <p>就一个式子：{@code 开关开着 && 不在屏保里}。
     *
     * <h3>为什么必须是"小标留、面板收"（用户 2026-09-24 定的）</h3>
     * v151 一度把小标也一起摘了，用户当场纠回来：<b>「那个小的别删」/「log 自带的留着，
     * 那个就是我示意图上画的」</b> —— 那颗常驻小绿点是他设计里的"返回入口"，不能消失。
     *
     * <p>所以真正要拦的不是小标，而是<b>面板</b>：屏保是一张"不许被撕的纸"，
     * 而面板/小标都是 {@code ty=2010}（{@code mBaseLayer=221000}），
     * 比屏保图 {@code ty=2014 / 161000} <b>更高</b>。屏保期间点一下小绿点，
     * 面板就铺到屏保脸上 —— 而屏保总闸只拦 HTTP（{@code CommandRouter.handle} 入口那条），
     * <b>拦不住本地这个点击</b>。真机取证：{@code sleeping:true} 同时 {@code panel_shown:true}。
     *
     * <p>现在两道闸<sup>（1 是入口、2 是出口，各司其职，不是重复）</sup>：
     * <ol>
     *   <li>小标的点击回调开头判 {@code SleepMode.sleeping} ⇒ 屏保中点击**直接忽略**，
     *       连 prefs 都不动（否则醒来会莫名其妙自己铺出来）。</li>
     *   <li>{@link #show()} 的所有调用方都改成先问 {@link #panelWant()}。</li>
     * </ol>
     */
    private static boolean panelWant() {
        return isEnabled() && !SleepMode.sleeping;
    }

    private static void syncVisibility() {
        if (appCtx == null) return;
        if (!MainThread.isMain()) {
            MainThread.post(new Runnable() {
                @Override public void run() { syncVisibility(); }
            });
            return;
        }
        final boolean want = panelWant();
        // ★ 小标**无条件保住**：它是用户要的"返回小绿点"，屏保期间也留在屏幕上。
        ensureHandle();
        if (want) {
            show();
        } else {
            hide();
        }
        placeHandle(want);
    }

    /**
     * 一次性外观迁移：把 {@link #UI_REV} 之前手动设过的字号/行数**清掉一次**，
     * 使本版的新默认值生效。幂等（靠水印判断），用户之后自己再改的值不会再被冲掉。
     *
     * <p>为什么不做成"每次都强制写默认值"：那就等于用户改什么都留不住；
     * 为什么不做成"什么都不动"：那就是 v147 那个 bug —— 改了默认值，老装机纹丝不动。
     */
    private static void migrateUiOnce() {
        try {
            SharedPreferences p = prefs();
            int rev = p.getInt(K_UI_REV, 0);
            if (rev >= UI_REV) return;
            // ★ v152：K_PLAYER 也要清 —— 老装机里存着 "overlay"（收不到触摸），
            //   不清掉的话，代码里默认值改成 "error" 也读不到，面板照样点不动。
            p.edit().remove(K_SIZE).remove(K_LINES).remove(K_PLAYER)
                    .putInt(K_UI_REV, UI_REV).commit();
            resetMeasurePaint();
            BootLog.i("屏幕日志: 外观迁移 ui_rev " + rev + " -> " + UI_REV
                    + "（字号/行数回到新默认 " + textSize() + "/" + lineCount()
                    + "，面板图层 → " + prefs().getString(K_PLAYER, DEF_PLAYER) + "）");
        } catch (Throwable t) {
            BootLog.w("屏幕日志: 外观迁移失败 " + t);
        }
    }

    /**
     * {@code POST /logwin} 的落点。
     * 所有字段都可缺省（缺省=不改）。on 传 "true"/"false" 才改开关。
     */
    public static String set(String on, String lines, String size, String x, String y) {
        return set(on, lines, size, x, y, null, null);
    }

    /**
     * 完整版：除开关/外观外还能改**窗口类型**（{@code layer} = 小标，{@code panel_layer} = 面板）。
     *
     * <p>改类型必须**拆掉重挂**：窗口类型在 {@code addView} 时定死，{@code updateViewLayout} 改不动。
     */
    public static String set(String on, String lines, String size, String x, String y,
                             String layer, String panelLayer) {
        if (appCtx == null) return "{\"error\":\"overlay not installed\"}";
        try {
            // ★★ v152 硬拦：面板 **不许** 用 overlay。
            //   面板里有真能驱动电机的滑条，"图层设错"的后果不是难看，而是
            //   **所有控件都点不动**，而且屏幕上完全看不出来（画面照样正常）。
            //   这种"配置一改就静默失效"的口子必须堵掉，而不是靠注释提醒。
            if ("overlay".equals(panelLayer)) {
                return "{\"error\":\"panel 图层不能用 overlay：TYPE_SYSTEM_OVERLAY 是纯显示层，"
                        + "收不到触摸（面板里的滑条/按钮会全部点不动）。用 error 或 alert。\"}";
            }
            SharedPreferences.Editor e = prefs().edit();
            if ("true".equals(on) || "false".equals(on)) {
                e.putBoolean(K_ON, "true".equals(on));
            }
            int n = parseInt(lines, -1);
            if (n > 0) e.putInt(K_LINES, Math.max(2, Math.min(40, n)));
            int s = parseInt(size, -1);
            if (s > 0) {
                e.putInt(K_SIZE, Math.max(5, Math.min(24, s)));   // v148：下限 7 → 5
                resetMeasurePaint();          // 字号变了，测量画笔要重建
            }
            int px = parseInt(x, -1);
            if (px >= 0) e.putInt(K_X, px);
            int py = parseInt(y, -1);
            if (py >= 0) e.putInt(K_Y, py);
            boolean layerChanged = false;
            if (typeOf(layer) > 0 && !layer.equals(prefs().getString(K_LAYER, DEF_LAYER))) {
                e.putString(K_LAYER, layer);
                layerChanged = true;
            }
            if (typeOf(panelLayer) > 0 && !panelLayer.equals(prefs().getString(K_PLAYER, DEF_PLAYER))) {
                e.putString(K_PLAYER, panelLayer);
                layerChanged = true;
            }
            e.commit();

            // 已经在屏上：位置/字号/图层改动靠重新挂载生效（布局参数不会自动重算）
            // ★ v152：这里也必须走 panelWant() —— 屏保期间通过 HTTP /logwin 设
            //   {on:true} 同样不许把面板铺到屏保脸上（总闸白名单以后万一放行也不会漏）。
            if (panelWant()) {
                hide();
                show();
            } else {
                hide();
            }
            if (layerChanged) remountHandle(); else ensureHandle();
            placeHandle(panelWant());
            return statusJson();
        } catch (Throwable t) {
            return "{\"error\":\"" + J.esc(String.valueOf(t)) + "\"}";
        }
    }

    public static boolean isEnabled() {
        return appCtx != null && prefs().getBoolean(K_ON, false);
    }

    /**
     * 面板此刻是不是真的挂在窗口上（只管"看得见的那部分"，不改任何状态）。
     *
     * <p>★ v149 加它，是为了让 {@link SleepMode#json} 能报出
     * 「屏保期间面板已收起、但开关还在」—— 否则屏保期间 {@code /logwin} 被总闸挡掉，
     * 这件事**没有任何端点查得到**，只能靠"感觉它没了"，正是本项目最忌讳的那种判据。
     * 也不做成"顺带 show/hide"的副作用方法：只读，才能在任意时刻随便调。
     */
    public static boolean isShown() {
        return shown;
    }

    public static String statusJson() {
        if (appCtx == null) {
            return "{\"on\":false,\"shown\":false,\"handle\":false,\"error\":\"not installed\"}";
        }
        return "{\"on\":" + isEnabled()
                + ",\"shown\":" + shown
                + ",\"handle\":" + (handle != null)
                + ",\"handle_side\":\"" + (isEnabled() ? "right" : "center") + "\""
                + ",\"lines\":" + lineCount()
                + ",\"size\":" + textSize()
                + ",\"x\":" + prefs().getInt(K_X, 0)
                + ",\"y\":" + prefs().getInt(K_Y, 0)
                + ",\"layer\":\"" + prefs().getString(K_LAYER, DEF_LAYER) + "\""
                + ",\"panel_layer\":\"" + prefs().getString(K_PLAYER, DEF_PLAYER) + "\""
                + ",\"handle_ty\":" + usedHandleType
                + ",\"panel_ty\":" + usedPanelType
                // ★ v152：面板"到底点不点得动"要能一眼查出来。
                //   TYPE_SYSTEM_OVERLAY 是纯显示层 —— 画得出来但收不到触摸，
                //   这种失败在屏幕上和正常一模一样，只能靠这个字段报。
                + ",\"panel_touchable\":" + (usedPanelType > 0
                        && usedPanelType != WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY)
                + ",\"panel_want\":" + panelWant()
                + ",\"handle_err\":\"" + J.esc(lastHandleErr) + "\""
                + ",\"panel_err\":\"" + J.esc(lastPanelErr) + "\""
                + "}";
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static SharedPreferences prefs() {
        return appCtx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static int lineCount() {
        // ★ v152（用户 2026-09-24）：「触摸反馈改矮、滑条/其他功能区紧凑，**为 log 留出足够大的位置**」。
        //   左列把触摸块从 80dp 压到 46dp 后，日志区实际可用高度约 300px，
        //   字号 8 时一行约 14px ⇒ 物理上装得下约 21 行（含状态头那一行）。
        //   取 18 留一点余量：万一某台机器字体度量偏大，也不会把顶部那行状态头挤掉
        //   （TextView 是 BOTTOM 对齐的，装不下时**从上面开始裁**）。
        return prefs().getInt(K_LINES, 18);
    }

    private static int textSize() {
        // v148（用户要求「log 字符可以再小点」）：默认 10 → 8。
        return prefs().getInt(K_SIZE, 8);
    }

    private static int parseInt(String s, int dflt) {
        if (s == null || s.isEmpty()) return dflt;
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
            return dflt;
        }
    }

    private static void show() {
        if (!MainThread.isMain()) {
            MainThread.post(new Runnable() {
                @Override public void run() { show(); }
            });
            return;
        }
        try {
            if (shown) { tick(); return; }
            if (wm == null) {
                wm = (WindowManager) appCtx.getSystemService(Context.WINDOW_SERVICE);
            }

            // 图层：见 typeOf() 的注释。TYPE_SYSTEM_OVERLAY 在本 ROM 上是 191000，
            // 比原厂脸的 31000 高得多，所以默认用它；addView 失败再依次退。
            int[] types = typeChain(K_PLAYER, DEF_PLAYER);
            Throwable last = null;
            StringBuilder tried = new StringBuilder();
            for (int t : types) {
                View v = makePanel();
                if (tv != null) tv.setText(compose());
                try {
                    wm.addView(v, buildLp(v, t));
                    panel = v;
                    shown = true;
                    usedPanelType = t;
                    lastPanelErr = "";
                    last = null;
                    panelMade.add(v);
                    dropStalePanels(v);           // 幂等：清掉没拆干净的旧面板
                    break;
                } catch (Throwable e) {
                    last = e;
                    tried.append(t).append(' ');
                    try { wm.removeView(v); } catch (Throwable ignored) {}
                }
            }
            if (!shown) {
                lastPanelErr = "ty[" + tried.toString().trim() + "] " + last;
                throw (last == null ? new RuntimeException("addView 失败") : last);
            }

            if (handler == null) handler = new Handler(Looper.getMainLooper());
            if (ticker == null) {
                ticker = new Runnable() {
                    @Override public void run() {
                        if (!shown) return;
                        try { tick(); } catch (Throwable ignored) {}
                        Handler h = handler;
                        if (h != null) h.postDelayed(this, TICK_MS);
                    }
                };
            }
            handler.removeCallbacks(ticker);
            handler.postDelayed(ticker, TICK_MS);
        } catch (Throwable t) {
            shown = false;
            // 这里可以用 BootLog —— 它不会调回 apply()
            BootLog.w("屏幕日志: addView 失败 " + t);
        }
    }

    /**
     * 造一个全新的面板（每次 addView 都用新的，避免失败后半复用半挂状态）。
     *
     * <p>★ v148：面板 = 日志 + 调试控件（触摸 / 姿态 3D / 电机滑条），由小绿点统一开关。
     *
     * <p>★★ v151 改**两列**（按用户 2026-09-24 给的排版图）：
     * <pre>
     *   ┌──────────────────────┬──────────────┐
     *   │ 触摸（左脸/右脸/头顶）│ 电机滑条 x3  │
     *   │                      │ 动作控制和电机角度
     *   │                      │ 陀螺仪 3D    │
     *   │ LOG（吃掉剩余高度）   │              │
     *   └──────────────────────┴──────────────┘
     * </pre>
     *
     * <p><b>为什么必须改（真机量出来的）</b>：v148 的单列竖排，窗口参数是
     * {@code (0,0)(520xwrap)}，而 {@code dumpsys window} 实测
     * {@code mFrame=[0,0][520,480]} —— 窗口被钳在 480 高，内容比它高 ⇒
     * <b>底部三条滑条整块被裁掉</b>（截图确认"电机位置"标题贴在屏幕最下沿）。
     * 两列各自独立排，整块高度由**较高那一列**决定，不再线性叠加 ⇒ 480 装得下。
     *
     * <p>★★ v152 压缩：用户要求把上面那些控件都压矮，把高度让给日志。
     * 靠三个杠杆（都在 {@link DebugPad} 里）：触摸块 80dp→46dp、滑条行定高 30dp、
     * 姿态 3D 96dp→80dp；再把 {@link #lineCount()} 默认 8→18。
     *
     * <p>⚠ 要说清楚一件事：**日志在左列**，所以只有"压左列"才真的把空间让给了日志；
     * 压右列（滑条/姿态）只是让整块面板更紧凑，不会让日志变大。
     *
     * <p>代价要写明：面板从"纯信息层"变成了<b>可点层</b> —— 它盖住的那块屏幕点不到别的东西。
     * 所以随时点右上角那颗常驻小绿点收起来。
     */
    private static View makePanel() {
        LinearLayout panelCol = new LinearLayout(appCtx);
        panelCol.setOrientation(LinearLayout.VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xC4000000);                          // 半透明黑底
        bg.setCornerRadius(6f);
        panelCol.setBackground(bg);

        LinearLayout row = new LinearLayout(appCtx);
        row.setOrientation(LinearLayout.HORIZONTAL);

        // ---------- 左列：触摸块在上、LOG 在下（日志贴底，与排版图一致） ----------
        LinearLayout left = new LinearLayout(appCtx);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(4), dp(3), dp(2), dp(3));

        // 触摸块造不出来不能把日志一起带走 —— 单独 try。
        try {
            View touch = DebugPad.buildTouchBlock(appCtx);
            if (touch != null) left.addView(touch);
        } catch (Throwable ignored) {
            // 触摸块挂了就只剩日志
        }

        TextView lt = new TextView(appCtx);
        lt.setText("LOG");
        lt.setTextColor(0xFF7FD4FF);
        lt.setTextSize(12f);
        left.addView(lt);

        TextView v = new TextView(appCtx);
        v.setTextSize(textSize());
        v.setTextColor(Color.rgb(0xC8, 0xFF, 0xC8));      // 淡绿，在彩色表情上仍看得清
        v.setTypeface(Typeface.MONOSPACE);
        v.setShadowLayer(3f, 0f, 0f, Color.BLACK);
        // ★ 贴底显示：排版图里日志就是落在左下角的。给 weight=1 吃满剩余高度，
        //   再把文字自己沉到底部 —— 否则行数少的时候中间会空一大块，看着像没数据。
        v.setGravity(Gravity.BOTTOM | Gravity.START);
        v.setIncludeFontPadding(false);
        v.setPadding(dp(4), dp(3), dp(4), dp(3));
        left.addView(v, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        tv = v;

        row.addView(left, new LinearLayout.LayoutParams(
                LEFT_W_PX, LinearLayout.LayoutParams.MATCH_PARENT));

        // ---------- 右列：小绿点 + 滑条 + 姿态 3D（DebugPad 自己组装） ----------
        try {
            View ctrl = DebugPad.buildControlBlock(appCtx);
            if (ctrl != null) {
                row.addView(ctrl, new LinearLayout.LayoutParams(
                        RIGHT_W_PX, LinearLayout.LayoutParams.MATCH_PARENT));
            }
        } catch (Throwable ignored) {
            // 控件区挂了就只剩日志列
        }

        panelCol.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ★ v151：这里一度挂过一条"功能开关条"（用户当时要求"对每一功能的开关"），
        //   同一天当场被否掉 ——「**功能面板去掉**」。已整条删除，不要再加回来。
        return panelCol;
    }

    /**
     * 面板能用的高度（px）：屏高 − 顶部状态栏条带 − 一点余量。
     *
     * <p>★ 为什么不能让它 {@code WRAP_CONTENT}：真机上窗口就是被钳在
     * {@code [0,0][520,480]}，内容再高也只会被裁掉（v148 的滑条就是这么没的）。
     * 给死高度，再把两列设成 {@code MATCH_PARENT}，才谈得上"整块塞得进屏幕"。
     */
    private static int panelHeightPx() {
        int h = appCtx.getResources().getDisplayMetrics().heightPixels;
        if (h <= 0) h = 480;
        int avail = h - STATUS_BAND_H - 4;
        return avail > 120 ? avail : h;                   // 屏高异常小时别算出负数
    }

    private static WindowManager.LayoutParams buildLp(View v, int type) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LEFT_W_PX + RIGHT_W_PX,
                panelHeightPx(),
                type,
                // ★ v148：面板里有了滑条，必须能收触摸 ⇒ 去掉 FLAG_NOT_TOUCHABLE。
                //   但绝不能少了 FLAG_NOT_TOUCH_MODAL —— 否则连面板**之外**的触摸也会
                //   被整块吃掉（历史上一个 10×10 的小点就干过这事）。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.LEFT;        // 左下角，不挡脸
        lp.x = prefs().getInt(K_X, 0);
        lp.y = prefs().getInt(K_Y, 0);
        return lp;
    }

    /** 同 {@link #dropStaleHandles}，针对日志面板。 */
    private static void dropStalePanels(View keep) {
        if (wm == null) return;
        for (int i = panelMade.size() - 1; i >= 0; i--) {
            View v = panelMade.get(i);
            if (v == keep) continue;
            try {
                wm.removeView(v);
            } catch (Throwable ignored) {
                // 见 dropStaleHandles
            }
            panelMade.remove(i);
        }
    }

    private static void hide() {
        if (!MainThread.isMain()) {
            MainThread.post(new Runnable() {
                @Override public void run() { hide(); }
            });
            return;
        }
        try {
            if (handler != null && ticker != null) handler.removeCallbacks(ticker);
            if (panel != null && wm != null) wm.removeView(panel);
        } catch (Throwable ignored) {
            // 已经不在窗口上就算了
        }
        panel = null;
        tv = null;
        try { DebugPad.detach(); } catch (Throwable ignored) {}
        lastTickAt = 0L;
        shown = false;
        dropStalePanels(null);              // 兜底：把没拆掉的旧面板一并清掉
    }

    /**
     * 屏保进 / 出（由 {@link SleepMode} 调）。
     *
     * <p>★ 只动"看得见的那部分"，**不写 prefs** —— 用户开着面板去睡，醒来它该还在。
     * 所以这里不需要另存一个"屏保期间临时收起"的标志位：期望状态本来就完全由
     * {@link #panelWant()} 决定（开关 × 屏保），进出屏保只是让它重算一次。
     *
     * <p>调用时序已核对（{@link SleepMode}）：进屏保是 {@code sleeping = true} 之后
     * 才调 {@code onSleep(true)}；叫醒是先 {@code sleeping = false} 再 {@code onSleep(false)}。
     * ⇒ 这里读 {@code SleepMode.sleeping} 一定是新值。
     */
    public static void onSleep(final boolean asleep) {
        MainThread.post(new Runnable() {
            @Override public void run() {
                // 入参只用来记日志（状态真源是 SleepMode.sleeping，不再自己存一份）
                if (asleep != SleepMode.sleeping) {
                    BootLog.w("屏幕日志: onSleep(" + asleep + ") 与 SleepMode.sleeping("
                            + SleepMode.sleeping + ") 不一致，按后者执行");
                }
                syncVisibility();
            }
        });
    }

    // ------------------------------------------------------------------
    // 折叠小标
    // ------------------------------------------------------------------

    /**
     * 挂上折叠小标（10×10 可视 + 外扩热区）。可重复调用，已挂就只归位。
     *
     * <p><b>窗口类型不能图省事用 {@code TYPE_SYSTEM_OVERLAY}</b>：它在 Android 里是
     * **纯显示层、根本不接收触摸**（设计如此）。v89 真机实测：小标画得出来、
     * `adb shell input tap` 点上去毫无反应。
     *
     * <p>但换成 {@code TYPE_PHONE} 也不够 —— 它在本 ROM 上与**原厂脸同层**（都是 31000），
     * 于是小标被那张脸压住、屏幕上看不见（只有离开 FaceActivity 才看得见）。
     * 而且 {@code TYPE_SYSTEM_OVERLAY} 是 191000（最上、可见），两者各有缺失。
     * ⇒ 类型取 {@link #K_LAYER} 配置，默认 {@code error}，供真机实测挑「又在上层又能点」的那个。
     *
     * <p><b>flag 组合也只有一种是对的</b>：小标要能点，就<b>不能</b>带
     * {@code FLAG_NOT_TOUCHABLE}；但<b>不带</b> {@code FLAG_NOT_TOUCH_MODAL} 的话，
     * 落在窗口之外的触摸也会被它整块吃掉 —— 一个 10×10 的小点足以让整屏摸不动。
     * 所以只能是「NOT_FOCUSABLE + NOT_TOUCH_MODAL」。
     */
    private static void ensureHandle() {
        if (appCtx == null) return;
        if (!MainThread.isMain()) {
            MainThread.post(new Runnable() {
                @Override public void run() { ensureHandle(); }
            });
            return;
        }
        try {
            if (wm == null) {
                wm = (WindowManager) appCtx.getSystemService(Context.WINDOW_SERVICE);
            }
            if (handle != null) {
                if (handle.getParent() != null) return;   // 已经挂在窗口上，什么都不用做
                handle = null;                            // 掉了（被系统/force-stop 清掉）→ 重挂
            }

            int size = HANDLE_PX + HANDLE_PAD * 2;
            // 小标的类型是**运行时可调**的（K_LAYER），因为真机上「既在脸上面又能点」的类型
            // 只能实测：TYPE_PHONE 能点但和原厂脸同层(31000)被压住；TYPE_SYSTEM_OVERLAY 在最上层
            // (191000) 却收不到触摸。默认 error(=TYPE_SYSTEM_ERROR，层高于 19 且可交互)，
            // 失败则依次退 phone / overlay —— 至少保证看得见。
            int[] types = typeChain(K_LAYER, DEF_LAYER);
            Throwable last = null;
            StringBuilder tried = new StringBuilder();
            for (int t : types) {
                View box = makeHandle();
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        size, size, t,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                // ★ 让 y=0 就是**物理屏幕最顶**。没有这两个 flag 时，
                                //   有些类型（TYPE_PHONE）会被状态栏顶下去 38px —— 实测
                                //   ty=2002 的 frame 是 [774,38]，而 ty=2010 是 [774,0]。
                                //   用户要求"紧贴屏幕最上方"，加 flag 后不论哪种类型都贴顶。
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.y = HANDLE_Y;                   // 见 HANDLE_Y：让**亮点**贴顶，而不是窗口贴顶
                box.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        // ★ v152 第一道闸（入口）：屏保期间**直接忽略这次点击**。
                        //   不写 prefs、不动窗口 —— 否则醒来会莫名其妙自己铺出面板来。
                        //   屏保期间点不动是**设计如此**（出口本来就只剩长按头顶键 /
                        //   /sleep off / 按住 5s，见 SleepMode），这里不能开一个后门。
                        if (SleepMode.sleeping) {
                            BootLog.i("屏幕日志: 屏保中点小绿点 —— 按设计忽略");
                            return;
                        }
                        // 点一下 = 展开/折叠（与设置页开关、/logwin 共用同一个状态）
                        set(isEnabled() ? "false" : "true", null, null, null, null);
                    }
                });
                try {
                    wm.addView(box, lp);
                    handle = box;
                    usedHandleType = t;
                    lastHandleErr = "";
                    last = null;
                    handleMade.add(box);
                    dropStaleHandles(box);        // 幂等：把之前没拆干净的清掉
                    break;
                } catch (Throwable e) {
                    last = e;
                    tried.append(t).append(' ');
                    // addView 可能"抛了但窗口已注册"，那它就漏在册外了 —— 主动删一次
                    try { wm.removeView(box); } catch (Throwable ignored) {}
                }
            }
            if (handle == null) {
                lastHandleErr = "ty[" + tried.toString().trim() + "] " + last;
                BootLog.w("屏幕日志: 小标挂载失败 " + lastHandleErr);
            }
        } catch (Throwable t) {
            handle = null;
            BootLog.w("屏幕日志: 小标挂载失败 " + t);
        }
    }

    /**
     * 把不在册的小标窗口全拆掉（{@code keep} 传 null 就是全拆）。
     *
     * <p>幂等兜底：不依赖"上一次 removeView 一定成功"。见 {@link #handleMade} 的注释。
     */
    private static void dropStaleHandles(View keep) {
        if (wm == null) return;
        for (int i = handleMade.size() - 1; i >= 0; i--) {
            View v = handleMade.get(i);
            if (v == keep) continue;
            try {
                wm.removeView(v);
            } catch (Throwable ignored) {
                // 本来就不在窗口上（"有引用但没 attach" 会抛 not attached to window manager）
            }
            handleMade.remove(i);
        }
    }

    /**
     * 拆掉小标再重挂（为了换窗口类型）。
     *
     * <p>窗口类型只在 {@code addView} 时生效，{@code updateViewLayout} 改不了 —— 所以换类型
     * 必须整个 remove + add。
     */
    private static void remountHandle() {
        if (!MainThread.isMain()) {
            MainThread.post(new Runnable() {
                @Override public void run() { remountHandle(); }
            });
            return;
        }
        try {
            dropStaleHandles(null);
        } catch (Throwable ignored) {
            // 拆不干净也不要紧：下一次 ensureHandle 会再清一遍
        }
        handle = null;
        usedHandleType = -1;
        ensureHandle();
    }

    /** 10×10 的可见小方块，装在一个带透明 padding 的容器里（见 HANDLE_PAD）。 */
    private static View makeHandle() {
        FrameLayout box = new FrameLayout(appCtx);
        View dot = new View(appCtx);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF200E676);                       // 亮绿，蓝背景/白脸上都显眼
        bg.setCornerRadius(2.5f);
        bg.setStroke(1, 0xFF00381A);
        dot.setBackground(bg);

        FrameLayout.LayoutParams p =
                new FrameLayout.LayoutParams(HANDLE_PX, HANDLE_PX);
        p.gravity = Gravity.CENTER;
        box.addView(dot, p);
        return box;
    }

    /**
     * 小标归位：<b>展开时靠右</b>（把左边让给日志窗口），<b>折叠时回到顶部正中</b>。
     */
    private static void placeHandle(final boolean expanded) {
        if (appCtx == null) return;
        if (!MainThread.isMain()) {
            MainThread.post(new Runnable() {
                @Override public void run() { placeHandle(expanded); }
            });
            return;
        }
        try {
            View h = handle;
            if (h == null || wm == null) return;
            WindowManager.LayoutParams lp =
                    (WindowManager.LayoutParams) h.getLayoutParams();
            if (lp == null) return;
            lp.gravity = expanded
                    ? (Gravity.TOP | Gravity.RIGHT)
                    : (Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            lp.x = 0;
            // ★ 展开态原本是 y=HANDLE_Y(-8)，可见点正好贴在**右上角** —— 那正是原生状态栏
            //   图标（时间/电量/蓝牙/WiFi）所在的位置，而我们的层级比状态栏高，于是把它们盖掉。
            //   所以展开态把它压到状态栏条带下面：可见点从 y=40 起（HANDLE_PAD 是透明热区）。
            //   收起态仍在顶部居中（那里是空的，状态图标只在右侧）。
            lp.y = expanded ? (STATUS_BAND_H + 2 - HANDLE_PAD) : HANDLE_Y;
            wm.updateViewLayout(h, lp);
        } catch (Throwable t) {
            BootLog.w("屏幕日志: 小标归位失败 " + t);
        }
    }

    private static void tick() {
        TextView v = tv;
        if (!shown || v == null) return;
        v.setText(compose());
        // ★ v148：控件区跟着一起刷（触摸高亮 / 陀螺仪 3D / 电机读回数字）。
        //   算 dt 是为了陀螺仪 yaw 积分 —— 丢帧时不能按固定步长积，否则角度会跑偏。
        long now = System.currentTimeMillis();
        float dt = lastTickAt == 0L ? (TICK_MS / 1000f)
                : Math.min(0.6f, (now - lastTickAt) / 1000f);
        lastTickAt = now;
        try { DebugPad.update(dt); } catch (Throwable ignored) {}
    }

    /** 组装整块文字：状态头 + 日志尾。 */
    private static String compose() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append(header()).append('\n');

        int n = lineCount();
        String tail = BootLog.recent(n);
        if (tail.isEmpty()) {
            sb.append("(暂无日志)");
            return sb.toString();
        }
        String[] all = tail.split("\n");
        int from = Math.max(0, all.length - n);
        int wrote = 0;
        // ★ v151：日志只在**左列**里排，所以按左列宽度算截断预算 ——
        //   用整块面板宽度算，每行都会超宽折行，面板再被撑爆一次。
        float budget = LEFT_W_PX - dp(16);
        for (int i = from; i < all.length; i++) {
            String l = stripDate(all[i]);
            if (l.isEmpty()) continue;
            if (wrote++ > 0) sb.append('\n');
            sb.append(clip(l, budget));
        }
        return sb.toString();
    }

    /** 去掉行首的 "MM-dd " 日期前缀 —— 屏幕就这么点宽，日期几乎没用，宽度留给正文。 */
    private static String stripDate(String l) {
        if (l == null) return "";
        if (l.length() > 6 && l.charAt(2) == '-' && l.charAt(5) == ' ') {
            return l.substring(6);
        }
        return l;
    }

    private static Paint measurePaint;

    /**
     * 按<b>真实像素宽度</b>截断。
     *
     * <p>为什么不用"字符数"或"半/全角加权"估算：中文字符在 monospace 下的实际宽度
     * 并不是 ASCII 的两倍（实测约 1.1~1.3 倍），估算必然偏乐观 → 行被折成两行 →
     * 面板高度翻倍、把脸挡掉大半（v86/v87 真机都踩了）。这里直接调
     * {@link Paint#measureText} 量，量出来多少就是多少。
     */
    private static String clip(String s, float limitPx) {
        if (measurePaint == null) {
            measurePaint = new Paint();
            measurePaint.setTypeface(Typeface.MONOSPACE);
            measurePaint.setTextSize(textSize()
                    * appCtx.getResources().getDisplayMetrics().scaledDensity);
        }
        if (measurePaint.measureText(s) <= limitPx) return s;
        float ell = measurePaint.measureText("…");
        int lo = 0, hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (measurePaint.measureText(s, 0, mid) + ell <= limitPx) lo = mid;
            else hi = mid - 1;
        }
        return s.substring(0, Math.max(0, lo)) + "…";
    }

    /** 字号改了要重建测量画笔，否则还是按老字号量。 */
    private static void resetMeasurePaint() {
        measurePaint = null;
    }

    /**
     * 状态头。只取 {@link VoiceWakeController} 上的现成访问器 —— 不引新的耦合点，
     * 也就能保证它任何时候都读得出来（读不出来就退化成只显示版本）。
     */
    private static String header() {
        StringBuilder h = new StringBuilder(128);
        h.append("v").append(version());
        try {
            VoiceWakeController w = AgentService.voiceWake;
            if (w != null) {
                h.append(" 听=").append(w.isListening() ? 1 : 0)
                 .append(" 忙=").append(w.isHandling() ? 1 : 0)
                 .append(" 说=").append(w.isSpeaking() ? 1 : 0)
                 .append(" 常=").append(w.isContinuous() ? 1 : 0)
                 .append(" 重起=").append(w.restarts())
                 .append(" │ hit=").append(w.hitCount())
                 .append(" 强=").append(w.forcedWakes());
            }
        } catch (Throwable ignored) {
            // 服务还没起来就只显示版本号
        }
        return h.toString();
    }

    /**
     * 面板标题上的版本号。
     * <p>★ v154：口径收到 {@link CommandRouter#appVersion(Context)} —— 这里原来自己读一遍
     * PackageManager 并缓存在 {@code cachedVer}，于是"面板显示的版本"与"HTTP 报的版本"
     * 是两份独立实现；只要有一处忘了改，就会出现"两边都自称是最新"的假绿灯。
     */
    private static String version() {
        String v = CommandRouter.appVersion(appCtx);
        if (!v.equals(cachedVer)) cachedVer = v;    // 面板那行只在标题上，留着做变化检测
        return v;
    }

    private static int dp(int v) {
        float d = appCtx.getResources().getDisplayMetrics().density;
        return (int) (v * d + 0.5f);
    }
}
