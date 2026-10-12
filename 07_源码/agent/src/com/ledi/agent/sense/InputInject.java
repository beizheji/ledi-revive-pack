package com.ledi.agent.sense;

import com.ledi.agent.core.J;
import com.ledi.agent.ui.SleepMode;
import com.ledi.agent.ui.SleepScreen;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * 往机器人自己的屏幕里注入触摸/按键（网页投屏的"控"那一半）。
 *
 * <h2>为什么不用 {@code /system/bin/input}</h2>
 * 本机实测（2026-10-06）：<b>一条 {@code input tap} 要 850~900 ms</b> ——
 * 它是 Java 程序，每次都要起一台 ART 虚拟机。做成控件的话，用户点一下要等近一秒
 * 才有反应，那就不叫"控制"了。这里改成**进程内反射调用**
 * {@code InputManager.injectInputEvent}，实测 1~5 ms。
 *
 * <h2>为什么我们有这个权限</h2>
 * {@code injectInputEvent} 要 {@code android.permission.INJECT_EVENTS}（签名级）。
 * 本 App 的 manifest 是 {@code android:sharedUserId="android.uid.system"}，
 * 进程 uid = 1000(system)（{@code ps} 实测），而
 * {@code ActivityManagerService.checkComponentPermission} 对
 * {@code uid == Process.SYSTEM_UID} 是**直接放行**。所以不需要额外申请。
 *
 * <p>反射而不是直接调：{@code InputManager.getInstance()} 与
 * {@code injectInputEvent()} 都是 @hide，直接用编译不过；反射还能在
 * 换 ROM 时优雅退化（{@link #available()} 返回 false，而不是崩进程）。</p>
 *
 * <h2>为什么屏保中也要能用</h2>
 * 本机屏保是**闲置 10 分钟自动进**的（{@code SleepMode.IDLE_DEFAULT_SEC=600}）。
 * 也就是说"人不在场"时机器人几乎总在屏保里 —— 若注入被屏保总闸挡掉，这个功能
 * 就等于不存在。放行的理由与 {@code /headkey} 完全同源：**它是人手点屏的远程替身**，
 * 而"轻点屏幕"本来就是屏保的既定出口之一，不新增任何暴露面。
 *
 * <h2>诚实口径</h2>
 * 每次注入都返回真实结果（{@code injectInputEvent} 的返回值），不去猜"应该成功了"。
 * 关掉它：{@code POST /console/mirror {"inject":false}}，与 {@code /shell} 的
 * {@code shell_on} 同一个纪律 —— 谁都不该白白多开一个口子。
 */
public final class InputInject {

    private static final String TAG = "ledi.inject";
    private static final String K_INJECT = "mirror_inject";

    /** {@code InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH}：等事件真的派发完。 */
    private static final int MODE_WAIT_FOR_FINISH = 2;

    /**
     * {@code InputManager.INJECT_INPUT_EVENT_MODE_ASYNC}：只要求"进队"，不等派发结果。
     *
     * <p>★ 为什么打字要用它：{@link #text} 一段文字会展开成**十几个到几十个**按键事件，
     * 每个都 WAIT_FOR_FINISH 的话，"等派发完成"要累加几十次 —— 实测正常情况下几十毫秒，
     * 但只要有一个事件落进**不消费输入**的窗口（被冻结/无焦点/正在 ANR），
     * {@code injectInputEvent} 会一直等到系统放弃，那种情况下一次打字能卡住分钟级。
     * （2026-10-06 验收里就抓到过 /console/text 连续两次 >20s 超时；同一台机器上
     *  随后手工重复 5 次都在 150ms 内 —— 说明是**偶发**，但最坏情况必须堵掉。）</p>
     *
     * <p>代价说清楚：ASYNC 下返回值只代表"**事件已交给系统**"，不代表已被应用消费。
     * 所以打了字要确认结果，看屏幕（投屏）而不是看这个返回值。</p>
     */
    private static final int MODE_ASYNC = 0;

    private static Method mGetInstance;
    private static Method mInject;
    private static Object mgr;
    private static boolean initDone;
    private static String initErr = "";

    private static String lastError = "";
    private static long okCount, failCount;
    private static long lastInjectAt;

    private InputInject() {}

    public static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean(K_INJECT, true);
    }

    public static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(K_INJECT, on).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("ledi_agent", Context.MODE_PRIVATE);
    }

    private static synchronized void init() {
        if (initDone) return;
        initDone = true;
        try {
            Class<?> im = Class.forName("android.hardware.input.InputManager");
            mGetInstance = im.getMethod("getInstance");
            mInject = im.getMethod("injectInputEvent", InputEvent.class, int.class);
            mgr = mGetInstance.invoke(null);
            if (mgr == null) initErr = "InputManager.getInstance() 返回 null";
        } catch (Throwable t) {
            initErr = "反射不到 InputManager: " + t;
            mInject = null;
            mgr = null;
            Log.e(TAG, initErr, t);
        }
    }

    public static synchronized boolean available() {
        init();
        return mgr != null && mInject != null;
    }

    private static synchronized boolean fire(InputEvent ev) {
        return fire(ev, MODE_WAIT_FOR_FINISH);
    }

    private static synchronized boolean fire(InputEvent ev, int mode) {
        init();
        if (mgr == null || mInject == null) {
            failCount++;
            lastError = initErr.length() > 0 ? initErr : "注入通道不可用";
            return false;
        }
        try {
            Object r = mInject.invoke(mgr, ev, Integer.valueOf(mode));
            boolean ok = (r instanceof Boolean) && ((Boolean) r).booleanValue();
            if (ok) {
                okCount++;
                lastInjectAt = System.currentTimeMillis();
            } else {
                failCount++;
                lastError = "injectInputEvent 返回 false（INJECT_EVENTS 被拒？）";
            }
            return ok;
        } catch (Throwable t) {
            failCount++;
            lastError = "注入异常: " + t;
            Log.e(TAG, lastError, t);
            return false;
        } finally {
            try { if (ev instanceof MotionEvent) ((MotionEvent) ev).recycle(); } catch (Throwable ignored) {}
        }
    }

    private static MotionEvent motion(int action, float x, float y, long down, long when) {
        MotionEvent ev = MotionEvent.obtain(down, when, action, x, y,
                1.0f, 1.0f, 0, 1.0f, 1.0f, 0, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return ev;
    }

    /** 点一下。 */
    public static boolean tap(float x, float y) {
        long down = SystemClock.uptimeMillis();
        boolean a = fire(motion(MotionEvent.ACTION_DOWN, x, y, down, down));
        boolean b = fire(motion(MotionEvent.ACTION_UP, x, y, down, down + 60));
        return a && b;
    }

    /**
     * 按住不放再抬手（长按手势）。
     *
     * <h3>为什么需要它（不是"顺手多做一个"）</h3>
     * 长按是 Android 上与"点一下"**并列**的一等手势（长按菜单、长按删除、拖动前的蓄力）。
     * 少了它，网页投屏就只能"点"，遇到只认长按的界面用户会以为"投屏点不动"。
     *
     * <p>它还有一个本机特有的用途：{@link SleepScreen} 的**兜底出口**就是"屏幕持续按压
     * 5 秒"（{@link SleepScreen#HOLD_EXIT_MS}；v142 起轻点故意不退出）。没有这个动作，
     * 远程就永远走不到那条分支上。</p>
     *
     * <h3>为什么中途要补 ACTION_MOVE</h3>
     * 只发 DOWN、等 5 秒、再发 UP 在多数 View 上没问题，但有些 View 会把手势"一直没有
     * MOVE"当成异常（或依赖 MOVE 驱动长按计时器）。补几个同坐标 MOVE：位移为 0 ⇒
     * 不会被判成拖拽（{@code moved < TAP_SLOP}），却能让长按计时器正常走完。</p>
     *
     * <p>注意：本调用会**阻塞 HTTP 线程 ms 毫秒**（这是它的语义，不是 bug）。所以上限
     * 夹在 10s，避免一个请求把线程占住太久。</p>
     */
    public static boolean hold(float x, float y, int ms) {
        if (ms < 100) ms = 100;
        if (ms > 10000) ms = 10000;
        long down = SystemClock.uptimeMillis();
        boolean a = fire(motion(MotionEvent.ACTION_DOWN, x, y, down, down));
        int steps = Math.max(1, ms / 250);
        boolean m = true;
        for (int i = 1; i <= steps; i++) {
            long t = down + (long) ((double) ms * i / (steps + 1));
            if (!fire(motion(MotionEvent.ACTION_MOVE, x, y, down, t))) m = false;
            try { Thread.sleep(Math.max(1, ms / (steps + 1))); } catch (InterruptedException ignored) {}
        }
        boolean b = fire(motion(MotionEvent.ACTION_UP, x, y, down, down + ms));
        return a && m && b;
    }

    /** 划一下（x1,y1 → x2,y2，全程 ms）。 */
    public static boolean swipe(float x1, float y1, float x2, float y2, int ms) {
        if (ms < 50) ms = 50;
        if (ms > 4000) ms = 4000;
        long down = SystemClock.uptimeMillis();
        boolean ok = fire(motion(MotionEvent.ACTION_DOWN, x1, y1, down, down));
        int steps = Math.max(2, ms / 16);
        for (int i = 1; i < steps; i++) {
            float f = (float) i / steps;
            float x = x1 + (x2 - x1) * f, y = y1 + (y2 - y1) * f;
            if (!fire(motion(MotionEvent.ACTION_MOVE, x, y, down, down + (long) (ms * f)))) ok = false;
        }
        boolean up = fire(motion(MotionEvent.ACTION_UP, x2, y2, down, down + ms));
        return ok && up;
    }

    /** 按一个键（KeyEvent 的 code，如 3=HOME 4=BACK 66=ENTER）。 */
    public static boolean key(int code) {
        long down = SystemClock.uptimeMillis();
        boolean a = fire(keyEvent(KeyEvent.ACTION_DOWN, code, down, down));
        boolean b = fire(keyEvent(KeyEvent.ACTION_UP, code, down, down + 40));
        return a && b;
    }

    private static KeyEvent keyEvent(int action, int code, long down, long when) {
        KeyEvent ev = new KeyEvent(down, when, action, code, 0);
        ev.setSource(InputDevice.SOURCE_KEYBOARD);
        return ev;
    }

    /**
     * 输入一段文字。
     *
     * <p>走 {@link KeyCharacterMap} —— 和 {@code input text} 同一条路。所以它只认
     * 虚拟键盘打得出来的字符：**中文不行**（会如实报错，不会假装发出去）。
     * 要在机器人屏幕上显示中文，用 ⑦ 屏幕显示 / {@code POST /display}。</p>
     */
    public static boolean text(String s) {
        if (s == null || s.length() == 0) return false;
        KeyEvent[] evs;
        try {
            KeyCharacterMap km = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
            evs = km.getEvents(s.toCharArray());
        } catch (Throwable t) {
            lastError = "这段文字虚拟键盘打不出来（中文请用 /display）：" + t;
            failCount++;
            return false;
        }
        if (evs == null || evs.length == 0) {
            lastError = "这段文字虚拟键盘打不出来（中文请用 /display）";
            failCount++;
            return false;
        }
        boolean ok = true;
        long down = SystemClock.uptimeMillis();
        for (KeyEvent e : evs) {
            // screenrecord 里原来的时间戳要重排一遍，否则会被当成"陈年事件"丢掉
            long when = down + (e.getEventTime() % 100000);
            KeyEvent one = new KeyEvent(down, when, e.getAction(), e.getKeyCode(),
                    e.getRepeatCount(), e.getMetaState());
            one.setSource(InputDevice.SOURCE_KEYBOARD);
            // ★ MODE_ASYNC（不是 WAIT_FOR_FINISH）：一段文字是几十个事件，
            //   逐个"等派发完成"会把最坏情况放大到分钟级（见 MODE_ASYNC 的注释）。
            if (!fire(one, MODE_ASYNC)) ok = false;
        }
        return ok;
    }

    public static synchronized String json() {
        return "{\"available\":" + available()
                + ",\"init_error\":\"" + J.esc(initErr) + "\""
                + ",\"ok_count\":" + okCount
                + ",\"fail_count\":" + failCount
                + ",\"last_error\":\"" + J.esc(lastError) + "\""
                + ",\"last_ago_ms\":" + (lastInjectAt == 0 ? -1
                        : System.currentTimeMillis() - lastInjectAt) + "}";
    }
}
