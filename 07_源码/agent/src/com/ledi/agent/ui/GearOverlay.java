package com.ledi.agent.ui;

import com.ledi.agent.core.AgentService;
import com.ledi.agent.core.BootLog;
import com.ledi.agent.sdk.FaceController;
import com.ledi.agent.net.HttpServer;
import com.ledi.agent.core.MainThread;
import com.ledi.agent.net.PcLink;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 机器人屏幕上的两个浮层。
 *
 * <h3>1) 左上角白色齿轮（<b>桌面入口</b>）</h3>
 * 视觉尺寸严格 20×20 像素（8 齿 + 中孔），默认贴在左上角 (8,8)，按住可拖、位置记忆。
 * <ul>
 *   <li><b>轻点 → {@link BrainActivity}</b>（我们的桌宠 / AI 主界面）——
 *       用户要求「齿轮就是桌面入口，点齿轮进入我们的桌宠软件」；</li>
 *   <li><b>长按 ≥900ms → {@link SettingsActivity}</b>：设置不另占屏幕位置，
 *       免得屏幕上多一个入口多挡一块脸。</li>
 * </ul>
 * 开机<b>强制点亮</b>（{@code AgentService} 写回 {@code gear_on=true}）：它是桌面唯一入口，
 * 被"记忆开关"永久关掉过一次，用户就没法从屏幕上自救了。
 * <p><b>这一层必须能接收触摸</b>，所以窗口 flag 的正确写法是
 * {@code FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL}：
 * <b>绝不能</b>加 {@code FLAG_NOT_TOUCHABLE}（加了齿轮就点不动、拖不动，等于废掉）；
 * 同时<b>必须</b>带 {@code FLAG_NOT_TOUCH_MODAL} —— 缺了它窗口是"模态"的，会吞掉整屏触摸，
 * 齿轮自己点不灵，还会连带吃掉原厂脸的摸脸/摸头触摸（v172 实测踩过，见 {@link #GEAR_TYPE} 旁的说明）。
 * 对照：{@link DisplayController} / {@link FaceController} 的全屏层是反例 —— 它们
 * 必须带 FLAG_NOT_TOUCHABLE，否则会吞掉整屏触摸（历史 bug）。
 *
 * <h3>2) 机器人 IP 文字 —— ★ 默认不显示</h3>
 * 位置：屏幕顶部往下 {@link #IP_Y} 像素、水平居中。
 * 内容：机器人自己的地址 {@code 192.168.x.x:8899}（电脑端/手机端照着填即可）。
 * <p>★ 用户要求（2026-09-22）：「屏幕上只能有小齿轮、log 入口和原生状态栏，
 * 其他都不要遮上去」⇒ 这块**默认关**（{@code gear_ip_on=false}），
 * 首次配电脑端时用 {@code POST /gear {"ip":true}} 临时打开。
 * <p>即使在打开状态下也有三道闸（见 {@link #ipLabel}）：
 * <ul>
 *   <li><b>必须真的联网</b>：以前这行字挂在齿轮下面，没联网也照显 {@code 0.0.0.0:8899} ——
 *       看着像可用地址，填进去必然连不上，是最容易误导人的一处；</li>
 *   <li><b>与电脑端连上后自动消失</b>，断开再自动冒回来（判定见 {@link PcLink}）——</li>
 *   <li>地址为 0.0.0.0 / 127.x / 169.254.x 时一律不显示。</li>
 * </ul>
 * <p>这一层是纯信息、不接收触摸，所以带 FLAG_NOT_TOUCHABLE：不挡背后界面的点击。
 */
public class GearOverlay {

    private static final String PREFS = "ledi_agent";
    /** 屏幕上那行 IP 提示的开关。★ 默认 **关**：用户要求屏幕上只留齿轮 + log 入口 + 原生状态栏。 */
    private static final String K_IP_ON = "gear_ip_on";

    /**
     * ★★★ v170（用户 2026-09-25「齿轮小标不见了」）：窗口类型从 {@code TYPE_PHONE} 改成
     * {@code TYPE_SYSTEM_ERROR}。
     *
     * <h4>为什么要改（真机取证，不是推测）</h4>
     * 用户报"齿轮不见了"。真机 {@code dumpsys window} 显示齿轮窗口**在**、位置尺寸都对
     * （{@code mAttrs=(8,8)(20x20)}、{@code mFrame=[8,8][28,28]}、{@code mHasSurface=true}、
     * {@code isReadyForDisplay()=true}、{@code mObscured=false}），但截图里左上角是空的。
     * <p>原因是**层级**：{@code TYPE_PHONE} 在本 ROM 上 layer 只有 <b>31000</b>，而
     * {@link LogOverlay} 的全屏容器窗口是**同一个 type**（{@code ty=2002}）却排在
     * {@code mAnimLayer=31005} ⇒ 同层里压在齿轮上面（{@code 1b115662}，{@code fillxfill}）。
     * 实测 {@code POST /gear {on:false}} 再 {@code {on:true}}（即重加一次、变成同层最后添加者）
     * 齿轮立刻可见 ⇒ 确诊为层级问题，不是绘制问题。
     *
     * <h4>为什么是 2010</h4>
     * {@code TYPE_SYSTEM_ERROR} layer = <b>221000</b>，比所有 {@code TYPE_PHONE}(31000) 高一个数量级，
     * 不会再被任何同层窗口压住。本 ROM 上这个层**既能显示也能收触摸**（{@link LogOverlay} 从
     * v152 起就在用，本轮又实测了一次：日志面板显示正常、面板上的控件可点）。注意
     * {@code TYPE_SYSTEM_OVERLAY} 虽然层更高(191000)却**收不到触摸**，对本齿轮是死路 —— 见
     * {@link LogOverlay} 里那条 v152 的实测记录。
     */
    public static final int GEAR_TYPE = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;
    private static final int SIZE = 20;   // 齿轮像素尺寸（用户规格：20*20）
    private static final int IP_Y = 80;   // IP 文字距屏幕上沿的像素数（用户规格：偏下 80 像素）

    /**
     * 原生状态栏条带高度 / 图标横向起点（实测自 {@code dumpsys window windows} 的
     * {@code StatusBar mAttrs=(0,0)(fillx38) ty=2000}；图标靠右排）。
     *
     * <p>齿轮默认在左上角 (8,8)，**碰不到右侧图标**（实测窗口矩形 {@code (8,8)(20x20)}）。
     * 但它是**可拖的**，而我们的窗口是 {@code ty=2002}／状态栏是 {@code ty=2000}，
     * 层级更高 —— 用户一旦把它拖到右上角，就会把时间/电量/蓝牙/WiFi 永久盖住，
     * 而且多半不知道是自己拖出来的。所以拖拽时把这块矩形列为禁放区。
     */
    private static final int STATUS_BAND_H = 38;
    private static final int STATUS_ICON_X = 560;

    /**
     * ★★★ v170：{@link LogOverlay} 那个"log 入口"在**左上角**占据的热区，齿轮必须避开。
     *
     * <p>实测（真机）：入口窗口是 {@code (0,-8)(26x26)}（10px 亮点 + 8px 透明热区，见
     * {@code LogOverlay.HANDLE_PAD/HANDLE_Y}），而齿轮在 {@code (8,8)(20x20)} ⇒ **两者重叠**。
     * 更糟的是两者现在**同层**（都是 {@code ty=2010}），入口在上面 ⇒ 点齿轮中心 (18,18)
     * 实际打开的是**日志面板**（用 {@code input tap 18 18} 复现，焦点没切到我们的界面）。
     * <p>所以这里把重叠的齿轮压到状态栏条带下面（{@code y=38}）：既避开入口热区，
     * 也不会挤在状态栏里。拖动时同样生效 —— 用户拖回左上角会被自动弹开，
     * 免得"看得见却点不到"。
     */
    private static final int LOG_HANDLE_W = 26;
    private static final int LOG_HANDLE_Y = -8;
    private static final int LOG_HANDLE_H = 26;

    private static volatile View gearView;
    private static volatile View ipView;
    private static volatile boolean shown;

    // ★★★ v173：齿轮"在不在"必须能**归因**。
    //   用户 2026-09-25 报「齿轮小标不见了」，而旧版 hide() 一行日志都不写
    //   ⇒ 事后只能靠猜（本轮真的猜了半天，最后只查出"消失了、没人知道是谁干的"）。
    //   还要记住一条**实测过的静默通道**：POST /gear {"ip":false} 会把齿轮一起
    //   永久关掉（旧版把"没给 on"当成 false），且屏幕上、日志里都毫无痕迹。
    //   所以这里记账：画了几次 / 摘了几次 / 最近一次是谁摘的（lastWhy）。
    static volatile int showN, hideN;
    static volatile String lastWhy = "";

    public static boolean isShown() { return shown; }

    /** 画/摘次数 + 最近一次摘的原因（供 {@code GET /health} 的 gear_hist 归因）。 */
    public static String whyJson() {
        return "{\"shows\":" + showN + ",\"hides\":" + hideN
                + ",\"why\":\"" + lastWhy.replace("\"", "'") + "\"}";
    }

    /** 屏幕上那行 IP 现在是不是正显示着（供 /health 与排障观察）。 */
    public static boolean isIpShown() { return ipView != null; }

    // ------------------------------------------------------------------ 齿轮

    /**
     * 画出左上角齿轮。
     * <p>可从任意线程调用（{@code POST /gear} 是从 HTTP 线程来的），
     * 非主线程会先投递到主线程 —— {@code WindowManager.addView} 必须在带 Looper 的线程做，
     * 否则在 HTTP 线程里直接 addView 只会抛 "Can't create handler inside thread..."，
     * 被 catch 吞掉后表现为"点了开关没反应"。
     */
    public static void show(final Context ctx) {
        if (!MainThread.isMain()) {
            MainThread.post(new Runnable() {
                @Override public void run() { show(ctx); }
            });
            return;
        }
        synchronized (GearOverlay.class) {
            if (shown && gearView != null) return;
            hideInternal(null);      // ★ v173：null = 自己重建，不算"被人摘掉"，不记账

            try {
                final WindowManager wm = (WindowManager)
                        ctx.getSystemService(Context.WINDOW_SERVICE);
                final SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

                final GearView gear = new GearView(ctx);

                final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        SIZE, SIZE,
                        GEAR_TYPE,                 // ★ v170：2010（221000 层）—— 见 GEAR_TYPE 的注释
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                // ★★★ v172：**必须**带 NOT_TOUCH_MODAL，否则这层是"模态"窗口 ——
                                //   它会吞掉整屏触摸：齿轮自己点不灵，连原厂脸的摸脸/摸头触摸
                                //   也一起被吃掉。实测对照：同层的 LogOverlay 入口带这个 flag
                                //   （fl=#1000328）所以可点；齿轮当时 fl=#1000208 缺 0x20 ⇒ 点不动。
                                //   ★ 这是本项目"可点浮层"的既有铁律（禁 NOT_TOUCHABLE ＋ 须
                                //   NOT_TOUCH_MODAL），原注释里漏了后半句。
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.LEFT;
                lp.x = sp.getInt("gear_x", 8);
                lp.y = sp.getInt("gear_y", 8);
                keepOffStatusBar(lp);   // 记忆位置也可能是旧版本拖到状态栏上的，开机先夹一次

                final long[] downAt = {0};
                final float[] downRaw = {0, 0};
                final int[] startPos = {0, 0};
                gear.setOnTouchListener(new View.OnTouchListener() {
                    @Override public boolean onTouch(View v, MotionEvent ev) {
                        switch (ev.getActionMasked()) {
                            case MotionEvent.ACTION_DOWN:
                                downAt[0] = SystemClock.uptimeMillis();
                                downRaw[0] = ev.getRawX();
                                downRaw[1] = ev.getRawY();
                                startPos[0] = lp.x;
                                startPos[1] = lp.y;
                                return true;
                            case MotionEvent.ACTION_MOVE:
                                lp.x = startPos[0] + (int) (ev.getRawX() - downRaw[0]);
                                lp.y = startPos[1] + (int) (ev.getRawY() - downRaw[1]);
                                keepOffStatusBar(lp);
                                try { wm.updateViewLayout(gear, lp); } catch (Exception ignored) {}
                                return true;
                            case MotionEvent.ACTION_UP:
                                float moved = Math.abs(ev.getRawX() - downRaw[0])
                                        + Math.abs(ev.getRawY() - downRaw[1]);
                                long held = SystemClock.uptimeMillis() - downAt[0];
                                if (moved < 12) {
                                    // 轻点 = 进**桌宠 / AI 主界面**（用户要求：齿轮就是桌面入口）；
                                    // 长按(≥900ms) = 进设置。设置不再另占屏幕位置 ——
                                    // 屏幕上多一个入口就多挡一块脸（见类注释的"只留这几样"）。
                                    if (held >= 900) openSettings(ctx); else openEntry(ctx);
                                } else {
                                    keepOffStatusBar(lp);
                                    sp.edit().putInt("gear_x", lp.x)
                                            .putInt("gear_y", lp.y).apply();
                                }
                                return true;
                        }
                        return false;
                    }
                });

                wm.addView(gear, lp);
                gearView = gear;
                shown = true;
                showN++;                                   // ★ v173 记账
                BootLog.i("齿轮: addView 成功 @(" + lp.x + "," + lp.y + ")"
                        + "（第 " + showN + " 次）");
            } catch (Exception e) {
                lastWhy = "addView 失败: " + e;             // ★ v173：这也是"看不见"的成因
                BootLog.w("齿轮: addView 失败 " + e);
                android.util.Log.e(AgentService.TAG, "gear show fail", e);
            }
        }
        refreshIp(ctx);            // 齿轮一出来就顺手把 IP 文字补齐
    }

    public static void hide(final Context ctx) { hide(ctx, "未标注"); }

    /**
     * ★★★ v173：带**原因**的隐藏。
     *
     * <p>为什么要有它：2026-09-25 用户报「齿轮小标不见了」，而旧版 {@code hide()}
     * 一行日志都不写 —— 事后只能猜是谁摘的。现在每次摘都记账（{@link #hideN} /
     * {@link #lastWhy}），由 {@code GET /health} 的 {@code gear_hist} 摊出来。
     * <b>本次调用点务必写成一句能读懂的话</b>（"onDestroy（服务被销毁）"、
     * "API POST /gear {\"on\":false}"），因为排障时它就是唯一的证据。</p>
     */
    public static void hide(final Context ctx, final String why) {
        if (!MainThread.isMain()) {
            MainThread.post(new Runnable() {
                @Override public void run() { hide(ctx, why); }
            });
            return;
        }
        synchronized (GearOverlay.class) {
            hideInternal(why);
        }
    }

    /** @param why null = show() 自己重建（不记账）；非 null = 真被摘掉，记账并写日志。 */
    private static void hideInternal(String why) {
        try {
            if (gearView != null) {
                WindowManager wm = (WindowManager) gearView.getContext()
                        .getSystemService(Context.WINDOW_SERVICE);
                wm.removeView(gearView);
            }
        } catch (Exception ignored) {}
        try {
            if (ipView != null) {
                WindowManager wm = (WindowManager) ipView.getContext()
                        .getSystemService(Context.WINDOW_SERVICE);
                wm.removeView(ipView);
            }
        } catch (Exception ignored) {}
        gearView = null;
        ipView = null;
        shown = false;
        if (why != null) {
            hideN++;
            lastWhy = why;
            BootLog.w("齿轮: 已隐藏（原因=" + why + "，第 " + hideN + " 次）");
        }
    }

    /**
     * 把齿轮夹出"原生状态栏图标区"：若它会落在 {@code y<38 且 x+SIZE≥560} 那块矩形里，
     * 就把它压到条带下面（{@code y=38}）。
     *
     * <p>为什么必须夹：齿轮的窗口层级(ty=2002)高于状态栏(ty=2000)，
     * 拖到右上角就是**永久盖住时间/电量/蓝牙/WiFi** —— 用户不会知道是自己拖出来的，
     * 只会觉得"状态栏坏了"。（左上角/顶部中间不在禁放区，默认位置不受影响。）
     */
    private static void keepOffStatusBar(WindowManager.LayoutParams lp) {
        if (lp.y < STATUS_BAND_H && lp.x + SIZE > STATUS_ICON_X) {
            lp.y = STATUS_BAND_H;
        }
        // ★★★ v170：再避让 log 入口的热区（见 LOG_HANDLE_*）。两者同层(ty=2010)且重叠时，
        //   入口在上面 ⇒ 点齿轮会打开日志面板。实测复现过，所以这里主动错开。
        boolean overlapX = lp.x < LOG_HANDLE_W;
        boolean overlapY = lp.y < LOG_HANDLE_Y + LOG_HANDLE_H && lp.y + SIZE > LOG_HANDLE_Y;
        if (overlapX && overlapY) {
            lp.y = STATUS_BAND_H;      // 落到状态栏条带下面
        }
    }

    /**
     * 轻点齿轮 = 打开**我们自己的桌宠 / AI 主界面**（{@link BrainActivity}）。
     *
     * <p>用户要求（2026-09-22）：「桌面上必须有齿轮，齿轮就是桌面入口，点齿轮进入我们的桌宠软件」。
     * 以前这里进的是 {@link SettingsActivity} —— 一个纯设置页，不是"软件入口"。</p>
     */
    private static void openEntry(Context ctx) {
        try {
            Intent i = new Intent(ctx, BrainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(i);
            BootLog.i("齿轮: 轻点 -> 打开桌宠(AI 主界面 BrainActivity)");
        } catch (Exception e) {
            BootLog.w("齿轮: 打开桌宠失败 " + e);
            android.util.Log.e(AgentService.TAG, "gear openEntry fail", e);
        }
    }

    private static void openSettings(Context ctx) {
        try {
            Intent i = new Intent(ctx, SettingsActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ctx.startActivity(i);
            BootLog.i("齿轮: 长按 -> 打开 SettingsActivity");
        } catch (Exception e) {
            BootLog.w("齿轮: 打开控制界面失败 " + e);
            android.util.Log.e(AgentService.TAG, "gear openSettings fail", e);
        }
    }

    // ------------------------------------------------------------------ IP 文字

    /**
     * 按"当前是否联网"刷新屏幕上的 IP 文字，可从任意线程调用
     * （{@code AgentService} 的后勤线程每 5 秒一轮）。
     * <p>没联网 → 摘掉整块；联网 → 补画或把文字更新成当前地址（DHCP 续租会换 IP）。
     */
    public static void refreshIp(final Context ctx) {
        if (!MainThread.isMain()) {
            MainThread.post(new Runnable() {
                @Override public void run() { refreshIp(ctx); }
            });
            return;
        }
        synchronized (GearOverlay.class) {
            if (!shown) return;
            // ★ 用户要求（2026-09-22）：「屏幕上只能有小齿轮、log 入口和原生状态栏，
            //   其他都不要遮上去」。这行 IP 是"其他"，所以**默认不显示**。
            //   它原本是给"第一次配电脑端"用的提示，需要时用 POST /gear {"ip":true} 临时开。
            if (!ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(K_IP_ON, false)) {
                if (ipView != null) {
                    try {
                        ((WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE))
                                .removeView(ipView);
                    } catch (Exception ignored) {}
                    ipView = null;
                }
                return;
            }
            String label = ipLabel(ctx);          // null = 没联网
            try {
                WindowManager wm = (WindowManager)
                        ctx.getSystemService(Context.WINDOW_SERVICE);
                if (label == null) {
                    if (ipView != null) {
                        wm.removeView(ipView);
                        ipView = null;
                        BootLog.i("齿轮: 未联网 -> IP 文字已隐藏");
                    }
                    return;
                }
                if (ipView == null) {
                    TextView tv = new TextView(ctx);
                    tv.setTextSize(11);
                    tv.setTextColor(Color.WHITE);
                    tv.setShadowLayer(4, 0, 0, Color.BLACK);
                    tv.setGravity(Gravity.CENTER);
                    // 半透明圆角底：机器人屏幕上是彩色表情/动画，纯文字容易糊在里面认不出
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(0x8C000000);
                    bg.setCornerRadius(8f);
                    tv.setBackground(bg);
                    tv.setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 8), dp(ctx, 2));

                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            GEAR_TYPE,             // ★ v170：与齿轮同层，免得 IP 文字也被压住
                            // 纯信息层：不抢焦点、也**不接收触摸**（否则会在屏幕中间
                            // 挡住背后界面的点击 —— 与 DisplayController 那条 bug 同源）
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                    lp.y = IP_Y;                  // 顶部往下 80 像素
                    tv.setText(label);
                    wm.addView(tv, lp);
                    ipView = tv;
                    BootLog.i("齿轮: IP 文字已上屏 y=" + IP_Y + " 居中 " + label);
                } else {
                    ((TextView) ipView).setText(label);
                }
            } catch (Exception e) {
                BootLog.w("齿轮: IP 文字刷新失败 " + e);
            }
        }
    }

    /**
     * 屏幕该显示的 IP 文字；<b>不该显示时返回 null</b>（调用方据此决定"整块不显示"）。
     * <p>三道闸：
     * <ol>
     *   <li>系统得有已连接的活动网络；</li>
     *   <li>得能从网卡上读到真实 IPv4 —— 0.0.0.0 / 127.x / 169.254.x（DHCP 没拿到地址时的
     *       自配地址）都算没联网。这些地址填进电脑端一定连不上，显示出来只会误导人；</li>
     *   <li>与电脑端<b>已连接</b>时不显示 —— 已经连上了就说明地址已经填好了，
     *       这行字没必要再占屏幕中间（断开会自动冒回来，判定见 {@link PcLink}）。</li>
     * </ol>
     */
    private static String ipLabel(Context ctx) {
        if (!online(ctx)) return null;
        if (PcLink.linked()) return null;
        return HttpServer.localIp() + ":" + AgentService.HTTP_PORT;
    }

    private static boolean online(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return false;
        } catch (Throwable ignored) {}
        String ip = HttpServer.localIp();
        if (ip == null || ip.isEmpty()) return false;
        return !(ip.startsWith("0.") || ip.startsWith("127.") || ip.startsWith("169.254."));
    }

    private static int dp(Context c, int v) {
        try {
            return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
        } catch (Throwable t) {
            return v;
        }
    }

    // ------------------------------------------------------------------ 齿轮绘制

    /**
     * 20×20px 白色齿轮绘制（软件层，中孔用 CLEAR 抠透）。
     *
     * <p>★★★ v174：白色本体下面多画一圈**暗色描边**（同一路径偏移 1px 画 8 次）。
     * 为什么必须加：齿轮是纯白 FILL、原本没有任何描边，而**背景不总是蓝的** ——
     * 用户那张屏保图（`SleepScreen`，层 31000，在齿轮**之下**）左上角正好是纯白，
     * 于是"白齿轮画在白底上"＝看不见。实测取样：屏保中齿轮窗口矩形
     * (8,38)-(28,58) 的 400 个像素**全是 (255,255,255)**，与四周无法区分。
     * 屏幕入口是用户唯一能点进我们界面的东西（本机没有别的入口），
     * 所以"在任何背景上都看得见"这条优先于"纯净"。</p>
     */
    static class GearView extends View {
        private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint clear = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** v174：描边用暗色（半透明黑，压在彩色脸上也不会显脏）。 */
        private final Paint dark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path gear = new Path();

        GearView(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
            white.setColor(Color.WHITE);
            white.setStyle(Paint.Style.FILL);
            dark.setColor(0x99000000);
            dark.setStyle(Paint.Style.FILL);
            clear.setColor(Color.TRANSPARENT);
            clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            float c0 = SIZE / 2f;
            // 齿圈（8 齿）：外圆 + 8 个齿块
            for (int i = 0; i < 8; i++) {
                saveTooth(c0, i * 45f, 5.2f, 10.0f, 2.6f);
            }
            gear.addCircle(c0, c0, 6.4f, Path.Direction.CW);
        }

        /** 加一个以 (c0,c0) 为中心、旋转 angle 的齿块矩形（半径 from..to，宽 w） */
        private void saveTooth(float c0, float angle, float from, float to, float w) {
            double rad = Math.toRadians(angle);
            float cx = (float) (c0 + Math.cos(rad) * (from + to) / 2);
            float cy = (float) (c0 + Math.sin(rad) * (from + to) / 2);
            Path tooth = new Path();
            tooth.addRect(cx - w / 2, cy - (to - from) / 2,
                    cx + w / 2, cy + (to - from) / 2, Path.Direction.CW);
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postRotate(angle, cx, cy);
            tooth.transform(m);
            gear.addPath(tooth);
        }

        @Override protected void onDraw(Canvas canvas) {
            // ★ v174：先铺一层暗色描边（同路径上下左右各偏 1px 画一圈）
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    canvas.save();
                    canvas.translate(dx, dy);
                    canvas.drawPath(gear, dark);
                    canvas.restore();
                }
            }
            canvas.drawPath(gear, white);
            canvas.drawCircle(SIZE / 2f, SIZE / 2f, 2.6f, clear);   // 中孔
        }
    }
}
