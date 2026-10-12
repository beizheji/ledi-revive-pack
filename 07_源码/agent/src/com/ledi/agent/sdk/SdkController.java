package com.ledi.agent.sdk;

import com.ledi.agent.core.J;

import com.ledi.agent.core.BootLog;

import com.ledi.agent.core.AgentService;

import com.ledi.agent.net.Proxies;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 原厂 SDK 控制器 —— 直接调用原厂 xldbasesdk 的公开接口。
 *
 * <h3>为什么不是简单照抄原厂方法名</h3>
 * LediAgent 通过 {@code build_apk.py --merge-dex} 合并了原厂
 * {@code xldrbt_v1.5.10.apk} 的 classes*.dex，但那份 SDK 被 R8 裁剪 + 混淆过，
 * 与 {@code com.auric.intell.rbt.xldtvrbtx-1.apk}（tvbtx）里的全名版并不一致：
 *
 * <pre>
 * 原厂全名（tvbtx）                              v1.5.10 实际（混淆后）
 * XldBaseSDKInit                                com.auric.intell.rbt.xldbasesdk.b
 * Function.SystemMethod                         com.auric.intell.rbt.xldbasesdk.b.a
 * CallBack.eng.ActionCompleteCallBack           com.auric.intell.rbt.xldbasesdk.a.a.a
 * config.RobotEventListener                     com.auric.intell.rbt.xldbasesdk.config.d
 * EngineService                                 （同名保留，方法名也保留）
 * </pre>
 *
 * 其中 {@code b.a}（=SystemMethod）的方法被改名为单字母，实测映射为：
 * <pre>
 * a=r.on  b=r.off  c=g.on  d=g.off  e=b.on  f=b.off  g=y.on  h=y.off
 * i=p.on  j=p.off  k=r.blink.fast  l=b.blink.slow  m=all.off
 * n=poweroff  o=usbon(=ADB开)  p=usboff(=ADB关)  reboot=reboot
 * </pre>
 * 这些方法内部只做 {@code sendBroadcast("com.auric.intell.xld.os.*")}，
 * 不依赖 SDK 初始化状态，因此可直接调用。
 *
 * <p>本类所有调用都走「候选名列表逐个尝试」，两套符号任一存在都能用；
 * 都拿不到时退回裸广播（与原厂广播协议一致）。
 *
 * <h3>为什么动作优先走 EngineService 而不是 SDK 门面</h3>
 * 门面（tvbtx 的 {@code XldBaseSDKInit}）在 baseAction/specialDance/radomDance
 * 前会检查静态标志 {@code batteryLowLevel} 与 {@code move}，任一不满足就静默跳过
 * （只回调 actionComplete 不动作）。EngineService 上没有这层门禁，可靠性更高。
 */
public class SdkController {

    // ==================== 原厂类名候选 ====================

    /** SDK 门面：XldBaseSDKInit */
    static final String T_FACADE_OBF  = "com.auric.intell.rbt.xldbasesdk.b";
    static final String T_FACADE_FULL = "com.auric.intell.rbt.xldbasesdk.XldBaseSDKInit";

    /** 系统实现：SystemMethod（LED / 电源 / ADB / 重启） */
    static final String T_SYS_OBF  = "com.auric.intell.rbt.xldbasesdk.b.a";
    static final String T_SYS_FULL = "com.auric.intell.rbt.xldbasesdk.Function.SystemMethod";

    /** 动作完成回调接口 */
    static final String T_CB_OBF  = "com.auric.intell.rbt.xldbasesdk.a.a.a";
    static final String T_CB_FULL = "com.auric.intell.rbt.xldbasesdk.CallBack.eng.ActionCompleteCallBack";

    /** 机器人事件监听接口（29 种事件：按键 / 触摸 / 手臂 / 腰部 / 电量 / 姿态反馈） */
    static final String T_EV_OBF  = "com.auric.intell.rbt.xldbasesdk.config.d";
    static final String T_EV_FULL = "com.auric.intell.rbt.xldbasesdk.config.RobotEventListener";

    /** 动作引擎服务（两版同名） */
    static final String T_ENG = "com.auric.intell.rbt.xldbasesdk.service.engine.EngineService";

    /** 引擎状态线程（左臂 / 右臂 / 腰部当前角度） */
    static final String T_ENGSTAT_OBF  = "com.auric.intell.rbt.xldbasesdk.service.engine.thread.b";
    static final String T_ENGSTAT_FULL = "com.auric.intell.rbt.xldbasesdk.service.engine.thread.GetEngStatusThread";

    /** 通用系统工具（含另一个 reboot 入口） */
    static final String T_COMMON_REBOOT = "com.auric.intell.commonlib.utils.s";

    /**
     * LED 表：{颜色, 动作, v1.5.10 混淆名, 原厂全名}
     * 未列出的组合（如 g.blink.fast）原厂本来就没有独立方法，走广播兜底。
     */
    public static final String[][] LED_MAP = {
            {"r", "on",         "a", "led_Red_On"},
            {"r", "off",        "b", "led_Red_off"},
            {"g", "on",         "c", "led_Green_On"},
            {"g", "off",        "d", "led_Green_off"},
            {"b", "on",         "e", "led_Blue_On"},
            {"b", "off",        "f", "led_Blue_off"},
            {"y", "on",         "g", "led_Yellow_On"},
            {"y", "off",        "h", "led_Yellow_off"},
            {"p", "on",         "i", "led_Pink_On"},
            {"p", "off",        "j", "led_Pink_off"},
            {"r", "blink.fast", "k", "led_Red_BlinkFast_On"},
            {"b", "blink.slow", "l", "led_Blue_BlinkSlow_On"},
            {"all", "off",      "m", "led_Close_All"},
    };

    /** 系统动作表：{逻辑名, v1.5.10 混淆名, 原厂全名} */
    static final String[][] SYS_MAP = {
            {"poweroff", "n", "powerOff"},
            {"adb_on",   "o", "adb_On"},
            {"adb_off",  "p", "adb_Off"},
            {"reboot",   "reboot", "reboot"},
    };

    /**
     * 引擎状态字段：{逻辑名, v1.5.10 名, tvbtx 名}
     * 前两个是静态标志，其余是实例字段。
     */
    static final String[][] STAT_FIELDS = {
            {"engine_ready",   "m",            "isInited"},
            {"dance",          "i",            "isDance"},
            {"controlled",     "isControled",  "isControled"},
            {"paused",         "engPause",     "engPause"},
            {"battery_low",    "batteryLow",   "batteryLow"},
            {"song_speed",     "songSpeed",    "songSpeed"},
            {"arm_wrest_ms",   "armWrestTime", "armWrestTime"},
            {"release_ms",     "releaseTime",  "releaseTime"},
            {"left_arm_turn",  "left_ArmTurn", "left_ArmTurn"},
            {"right_arm_turn", "right_ArmTurn", "right_ArmTurn"},
            {"two_arm_turn",   "two_ArmTurn",  "two_ArmTurn"},
    };

    private static final int EV_MAX = 40;
    private static final String TAG = AgentService.TAG;

    private final Context ctx;
    private volatile Object engine;          // EngineService 实例（AgentService 绑定后注入）
    private volatile Object engineBinder;    // EngineBinder（拿静态状态用）

    private final ArrayDeque<String> events = new ArrayDeque<String>();
    private volatile String lastEvent = "";
    private volatile long lastActionDone = 0L;
    private volatile String lastError = "";
    private volatile Object listenerProxy;
    /** 是否已经处理过原厂 actionCompleteCallBack 为空的情况（用来只报一次日志）。 */
    private volatile boolean cbPatched;

    public SdkController(Context ctx) { this.ctx = ctx; }

    public void setEngine(Object e) {
        this.engine = e;
        // 拿到实例的第一件事就是补原厂那个漏掉的不变式（见 ensureActionCallback）。
        // 放在这里而不是 forceReady()：forceReady 是 3 秒后才跑的定时任务，
        // 而崩溃要的是「任何一条动作完成消息到达时字段都非空」，越早补越安全。
        ensureActionCallback();
    }

    public boolean hasEngine() { return engine != null; }

    public String lastError() { return lastError; }

    public long lastActionDone() { return lastActionDone; }

    // ==================== 符号解析 ====================

    private static Class<?> pick(String... names) {
        for (String n : names) {
            try {
                return Class.forName(n);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 返回类描述名（用于 /sdk/caps 展示到底命中了哪套符号） */
    private static String pickName(String... names) {
        for (String n : names) {
            try {
                Class.forName(n);
                return n;
            } catch (Throwable ignored) {}
        }
        return "";
    }

    static Class<?> sysClass()  { return pick(T_SYS_OBF, T_SYS_FULL); }

    static Class<?> facadeClass() { return pick(T_FACADE_OBF, T_FACADE_FULL); }

    static Class<?> cbClass()   { return pick(T_CB_OBF, T_CB_FULL); }

    static Class<?> evClass()   { return pick(T_EV_OBF, T_EV_FULL); }

    private static boolean hasMethod(Class<?> c, String name, Class<?>... params) {
        if (c == null) return false;
        try {
            c.getMethod(name, params);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Method method(Class<?> c, String name, Class<?>... params) {
        if (c == null) return null;
        try {
            return c.getMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 回调 / 事件监听（动态代理） ====================

    /** 造一个原厂动作完成回调的空实现；拿不到接口就返回 null（原厂会做非空判断） */
    private Object newActionCallback() {
        Class<?> iface = cbClass();
        if (iface == null) return null;
        try {
            return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    new InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method m, Object[] a) {
                            Object g = Proxies.guard(proxy, m, a);   // ★ 见 Proxies：Object 约定方法必须真实现
                            if (g != Proxies.UNHANDLED) return g;
                            if (m.getName().startsWith("actionComplete")) {
                                lastActionDone = System.currentTimeMillis();
                            }
                            return Proxies.nil(m);                   // ★ 不许裸 return null
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "newActionCallback fail", t);
            return null;
        }
    }

    /**
     * 注册原厂机器人事件监听（按键 / 头部触摸 / 手臂扭转 / 腰部 / 电量 / 姿态反馈）。
     * 通用代理实现：不依赖具体方法名，任何签名都能接。
     */
    public synchronized String listen() {
        Class<?> iface = evClass();
        if (iface == null) return "no listener interface";
        Class<?> eng = pick(T_ENG);
        Method reg = method(eng, "setRobotEventListener", iface);
        if (reg == null) return "no setRobotEventListener";
        try {
            if (listenerProxy == null) {
                listenerProxy = Proxy.newProxyInstance(iface.getClassLoader(),
                        new Class<?>[]{iface}, new InvocationHandler() {
                            @Override public Object invoke(Object proxy, Method m, Object[] a) {
                                // ★ 这个代理是原厂机器人事件监听，SDK 会把它存进集合里，
                                //   所以 Object 约定方法一定会被调到（见 Proxies）。
                                Object g = Proxies.guard(proxy, m, a);
                                if (g != Proxies.UNHANDLED) return g;
                                try {
                                    String ev = (a != null && a.length > 0 && a[0] != null)
                                            ? String.valueOf(a[0]) : "";
                                    String extra = (a != null && a.length > 1 && a[1] != null)
                                            ? String.valueOf(a[1]) : "";
                                    pushEvent(m.getName(), ev, extra);
                                } catch (Throwable ignored) {}
                                return Proxies.nil(m);
                            }
                        });
            }
            reg.invoke(null, listenerProxy);
            return "listening";
        } catch (Throwable t) {
            return fail("listen", t);
        }
    }

    private void pushEvent(String method, String ev, String extra) {
        lastEvent = ev + (extra.isEmpty() ? "" : ("(" + extra + ")"));
        String line = "{\"t\":" + System.currentTimeMillis() + ",\"m\":\"" + J.esc(method)
                + "\",\"event\":\"" + J.esc(ev) + "\",\"extra\":\"" + J.esc(extra) + "\"}";
        synchronized (events) {
            events.addFirst(line);
            while (events.size() > EV_MAX) events.removeLast();
        }
    }

    public String eventsJson() {
        StringBuilder sb = new StringBuilder("[");
        synchronized (events) {
            boolean first = true;
            for (String e : events) {
                if (!first) sb.append(",");
                sb.append(e);
                first = false;
            }
        }
        return sb.append("]").toString();
    }

    public String lastEvent() { return lastEvent; }

    // ==================== 动作 ====================

    /**
     * 原厂动作序列：把动作名数组交给 EngineService.baseAction 逐个播放。
     * 引擎内部自己递进（没有间隔时靠前一个动作的完成回调推进）。
     *
     * @param names 动作名数组，与 {@code /motions_all} 同名（可混搭 dance_*）
     * @param delay 每个动作之间的等待毫秒数；0 = 用引擎默认递进
     */
    public String series(final String[] names, final long delay) {
        if (names == null || names.length == 0) return "empty list";
        if (engine == null) return "engine not ready";
        final Object eng = engine;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Object cb = newActionCallback();
                    Class<?> cc = cbClass();
                    Method m = (delay > 0)
                            ? method(eng.getClass(), "baseAction", Long.TYPE, String[].class, cc)
                            : method(eng.getClass(), "baseAction", String[].class, cc);
                    if (m == null) { lastError = "no baseAction"; return; }
                    if (delay > 0) m.invoke(eng, delay, names, cb);
                    else           m.invoke(eng, names, cb);
                    lastError = "";
                } catch (Throwable t) {
                    lastError = fail("series", t);
                    Log.e(TAG, "series fail", t);
                }
            }
        }, "ledi-sdk-series").start();
        return "queued " + names.length + " action(s)";
    }

    /** 指定舞蹈（dance_* 名）或随机舞蹈（name 为空） */
    public String dance(final String name) {
        if (engine == null) return "engine not ready";
        final Object eng = engine;
        final String nm = name == null ? "" : name.trim();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Object cb = newActionCallback();
                    Method m = method(eng.getClass(), "specialDance", String.class, cbClass());
                    if (m == null) { lastError = "no specialDance"; return; }
                    m.invoke(eng, nm, cb);
                    lastError = "";
                } catch (Throwable t) {
                    lastError = fail("dance", t);
                }
            }
        }, "ledi-sdk-dance").start();
        return "queued dance " + (nm.isEmpty() ? "(random)" : nm);
    }

    /**
     * 原厂随机舞蹈，参数是**节奏 bpm**（不是次数）。
     * EngineService.radomDance(int, cb) 内部会走 moveInterface(name, speed, true)。
     */
    public String bpm(final int beats) {
        if (engine == null) return "engine not ready";
        final Object eng = engine;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Object cb = newActionCallback();
                    Method m = method(eng.getClass(), "radomDance", Integer.TYPE, cbClass());
                    if (m == null) { lastError = "no radomDance"; return; }
                    m.invoke(eng, beats, cb);
                    lastError = "";
                } catch (Throwable t) {
                    lastError = fail("bpm", t);
                }
            }
        }, "ledi-sdk-bpm").start();
        return "queued random dance @" + beats + "bpm";
    }

    /** 按名执行单个动作，可指定速度（moveInterface(name, speed)） */
    public String run(final String name, final int speed) {
        if (engine == null) return "engine not ready";
        if (name == null || name.trim().isEmpty()) return "empty name";
        final Object eng = engine;
        final String nm = name.trim();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Method m = (speed > 0)
                            ? method(eng.getClass(), "moveInterface", String.class, Integer.TYPE)
                            : method(eng.getClass(), "moveInterface", String.class);
                    if (m == null) { lastError = "no moveInterface"; return; }
                    if (speed > 0) m.invoke(eng, nm, speed);
                    else           m.invoke(eng, nm);
                    lastError = "";
                } catch (Throwable t) {
                    lastError = fail("run", t);
                }
            }
        }, "ledi-sdk-run").start();
        return "queued " + nm + (speed > 0 ? (" @" + speed) : "");
    }

    /** 停止当前动作 */
    public String stop() {
        if (engine == null) return "engine not ready";
        try {
            Object cb = newActionCallback();
            Method m = method(engine.getClass(), "stopMove", cbClass(), Boolean.TYPE);
            if (m == null) return "no stopMove";
            m.invoke(engine, cb, Boolean.FALSE);
            return "stopped";
        } catch (Throwable t) {
            return fail("stop", t);
        }
    }

    /** 释放全部控制（原厂"松开接管"），用于把控制权交还主 App */
    public String release() {
        if (engine == null) return "engine not ready";
        try {
            Method m = method(engine.getClass(), "realeaseControl_All");
            if (m == null) return "no realeaseControl_All";
            m.invoke(engine);
            return "released";
        } catch (Throwable t) {
            return fail("release", t);
        }
    }

    /** 低电量标志：原厂据此拒绝动作 */
    public String setBatteryLow(boolean low) {
        if (engine == null) return "engine not ready";
        try {
            Method m = method(engine.getClass(), "setBatteryLow", Boolean.TYPE);
            if (m == null) return "no setBatteryLow";
            m.invoke(engine, low);
            return "batteryLow=" + low;
        } catch (Throwable t) {
            return fail("setBatteryLow", t);
        }
    }

    // ==================== LED / 电源 / ADB ====================

    /**
     * 灯光：优先走原厂静态方法（SystemMethod / SDK 门面），失败退回裸广播。
     * 广播协议与原厂完全一致：{@code com.auric.intell.xld.os.led.<color>.<action>}
     */
    public String led(String color, String action) {
        if (color == null || color.isEmpty()) color = "all";
        if (action == null || action.isEmpty()) action = "on";
        color = color.trim().toLowerCase();
        action = action.trim().toLowerCase();
        BootLog.i("灯光 执行开始 color=" + color + " action=" + action);
        if (!color.matches("(r|g|b|y|p|all)")) { BootLog.w("灯光 bad color: " + color); return "bad color: " + color; }
        if (!action.matches("(on|off|blink\\.fast|blink\\.slow)")) { BootLog.w("灯光 bad action: " + action); return "bad action: " + action; }

        String obf = null, full = null;
        for (String[] row : LED_MAP) {
            if (row[0].equals(color) && row[1].equals(action)) {
                obf = row[2];
                full = row[3];
                break;
            }
        }
        if (obf != null) {
            Class<?> sys = sysClass();
            String used = callStatic(sys, obf, "led");
            if (used.isEmpty()) used = callStatic(sys, full, "led");
            if (used.isEmpty()) used = callFacadeLed(obf, full);
            if (!used.isEmpty()) { BootLog.i("灯光 ok: " + used); return "sdk:" + used; }
        }
        String bcast = "com.auric.intell.xld.os.led." + color + "." + action;
        BootLog.i("灯光 走广播: " + bcast);
        return broadcast(bcast);
    }

    /** 门面（XldBaseSDKInit / 混淆版 b）上也有一套 LED 方法，作为第二顺位 */
    private String callFacadeLed(String obf, String full) {
        Class<?> f = facadeClass();
        if (f == null) return "";
        String r = callStatic(f, full, "led");
        if (r.isEmpty() && obf != null && !obf.equals(full)) r = callStatic(f, obf, "led");
        return r;
    }

    /**
     * 系统动作：poweroff / reboot / adb_on / adb_off
     * 走 SystemMethod（或混淆版 b.a），失败退回广播。
     */
    public String sys(String what) {
        if (what == null) what = "";
        what = what.trim().toLowerCase().replace("-", "_");
        String obf = null, full = null;
        for (String[] row : SYS_MAP) {
            if (row[0].equals(what)) { obf = row[1]; full = row[2]; break; }
        }
        if (obf == null) return "unknown sys action: " + what;

        Class<?> sys = sysClass();
        String used = callStatic(sys, full, "sys");
        if (used.isEmpty()) used = callStatic(sys, obf, "sys");
        if (used.isEmpty()) {
            Class<?> f = facadeClass();
            used = callStatic(f, full, "sys");
            if (used.isEmpty() && !full.equals(obf)) used = callStatic(f, obf, "sys");
        }
        if (!used.isEmpty()) return "sdk:" + used;

        String bcast = null;
        if ("poweroff".equals(what)) bcast = "com.auric.intell.xld.os.poweroff";
        else if ("adb_on".equals(what)) bcast = "com.auric.intell.xld.os.usbon";
        else if ("adb_off".equals(what)) bcast = "com.auric.intell.xld.os.usboff";
        else if ("reboot".equals(what)) bcast = "com.auric.intell.xld.os.reboot";
        return bcast == null ? "no route: " + what : broadcast(bcast);
    }

    private String callStatic(Class<?> c, String name, String kind) {
        Method m = method(c, name, Context.class);
        if (m == null) return "";
        try {
            m.invoke(null, ctx);
            return name;
        } catch (Throwable t) {
            lastError = fail(kind + "/" + name, t);
            return "";
        }
    }

    private String broadcast(String action) {
        try {
            ctx.sendBroadcast(new Intent(action));
            return "broadcast:" + action;
        } catch (Throwable t) {
            return fail("broadcast", t);
        }
    }

    // ==================== 状态 ====================

    /** 读引擎状态标志（两版字段名不同，逐个候选试） */
    public String stat() {
        StringBuilder sb = new StringBuilder("{");
        Class<?> eng = pick(T_ENG);
        Object inst = engine;
        boolean first = true;
        for (String[] row : STAT_FIELDS) {
            Object v = readField(eng, inst, row[1]);
            String via = row[1];
            if (v == null && !row[2].equals(row[1])) {
                v = readField(eng, inst, row[2]);
                via = row[2];
            }
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(J.esc(row[0])).append("\":");
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v.toString());
            } else {
                sb.append("\"").append(J.esc(String.valueOf(v))).append("\"");
            }
            if (!via.equals(row[0])) { /* 字段来源已在 caps 里说明，这里不重复 */ }
        }
        // 当前舵机/关节角度（原厂状态线程的静态字段）
        Class<?> st = pick(T_ENGSTAT_OBF, T_ENGSTAT_FULL);
        sb.append(",\"arms\":").append(jsonArms(st));
        sb.append(",\"engine\":" + hasEngine());
        sb.append(",\"last_event\":\"").append(J.esc(lastEvent)).append("\"");
        sb.append(",\"last_action_done\":").append(lastActionDone);
        sb.append(",\"last_error\":\"").append(J.esc(lastError)).append("\"");
        return sb.append("}").toString();
    }

    private String jsonArms(Class<?> st) {
        if (st == null) return "null";
        String[] cand = {"leftarm", "rightarm", "waist"};
        StringBuilder sb = new StringBuilder("{");
        boolean any = false, first = true;
        for (String f : cand) {
            Object v = readField(st, null, f);
            if (v == null) continue;
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(f).append("\":").append(v);
            any = true;
        }
        return any ? sb.append("}").toString() : "null";
    }

    private static Object readField(Class<?> c, Object inst, String name) {
        if (c == null) return null;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                if (Modifier.isStatic(f.getModifiers())) return f.get(null);
                if (inst != null) return f.get(inst);
                return null;
            } catch (NoSuchFieldException e) {
                continue;
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** 反射写 static 字段（返回是否成功）。 */
    private static boolean writeStatic(Class<?> c, String name, Object value) {
        if (c == null) return false;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                if (Modifier.isStatic(f.getModifiers())) {
                    f.set(null, value);
                    return true;
                }
                return false;
            } catch (NoSuchFieldException e) {
                continue;
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    /**
     * 补上原厂 SDK 漏判空的那处不变式：{@code EngineService.actionCompleteCallBack} 必须非空。
     *
     * <h3>为什么要这么修（真机崩溃栈还原）</h3>
     * 2026-09-21 真机 {@code dumpsys dropbox}：
     * <pre>
     * java.lang.NullPointerException: Attempt to invoke interface method
     *   'void com.auric.intell.rbt.xldbasesdk.a.a.a.actionComplete()' on a null object reference
     *     at ...service.engine.EngineService$1.handleMessage(Unknown Source)
     * </pre>
     * 反汇编 {@code EngineService$1.handleMessage} 看到，同一条 switch 里有**两处**调
     * {@code actionComplete()}：
     * <pre>
     *   一处： access$1100(this$0) -> a.a.a ;  invoke-interface actionComplete()   // 没有判空 ★
     *   另一处：access$1100(this$0) -> a.a.a ;  if-eqz v0, +… ; invoke-interface     // 判空了
     * </pre>
     * 而 {@code actionCompleteCallBack} 这个字段**全类只有一处赋值**，在
     * {@code EngineService.onBind()} 里塞一个 {@code EngineService$4} 默认实现。
     * 换句话说：**只在「服务被真正 bind 过」的实例上它才非空**。
     * 任何让「动作完成消息」落到一个没走过 onBind 的实例上的情况，都必然在这里 NPE，
     * 而它跑在主线程 Handler 上 —— 直接打死整个进程（进程一死，唤醒词就永久哑了）。
     *
     * <p>原厂自己的 App（{@code com.auric.intell.rbt.xldrbtx}）也栽在同一个地方，
     * 说明这是厂商的固有缺陷，不是我们的用法问题。
     *
     * <h3>修法</h3>
     * 不试图去猜它什么时候是空的 —— 直接把不变式补上：拿到实例后读一次该字段，
     * 为空就写入我们自己的空实现代理。字段非空后，两处调用点都不会再 NPE。
     * 非空时**什么都不做**，完全保留原厂行为（包括它 per-call 设进来的回调用法）。
     *
     * @return 供日志用的结果串（首次介入时会打出 {@code was null} 作为真机证据）
     */
    public synchronized String ensureActionCallback() {
        Object eng = engine;
        if (eng == null) return "no engine instance";
        try {
            Field f = null;
            for (Class<?> k = eng.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                try {
                    f = k.getDeclaredField("actionCompleteCallBack");
                    break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (f == null) return "no actionCompleteCallBack field";
            f.setAccessible(true);
            Object cur = f.get(eng);
            if (cur != null) {
                if (!cbPatched) {
                    cbPatched = true;
                    return "already set by onBind (" + cur.getClass().getName() + ")";
                }
                return "ok";
            }
            Object cb = newActionCallback();
            if (cb == null) return "no callback interface";
            f.set(eng, cb);
            cbPatched = true;
            // ★ 这条日志是崩溃根因的真机证据：出现它就说明原厂那处漏判空被我们补上了
            BootLog.w("★ 原厂 actionCompleteCallBack 为空，已补入空实现代理（防 actionComplete NPE 打死进程）");
            return "was null -> patched";
        } catch (Throwable t) {
            return fail("ensureActionCallback", t);
        }
    }

    /**
     * 强制引擎就绪：反射把 EngineService 的 isInited / isControled 静态标志置 true。
     *
     * 背景：原厂引擎的 isInited 靠「主控板串口上报 ENGSTATUS → 主 App 转广播 →
     * EngBroadCastReciver 置位」这条链才会变 true。Agent 单独 bind EngineService
     * 拿不到这条链，isInited 恒为 false，moveInterface 内部会静默跳过电机驱动。
     * 这里直接置位绕过，让动作指令能真正下发到串口。
     *
     * <p>顺带再补一次 {@link #ensureActionCallback()} —— 置位之后引擎就会真的
     * 派发动作完成消息，那正是原厂漏判空那处会被踩到的时刻，补一次最保险。
     */
    public String forceReady() {
        String cb = ensureActionCallback();
        Class<?> eng = pick(T_ENG);
        if (eng == null) return "engine class not found";
        boolean a = writeStatic(eng, "isInited", Boolean.TRUE);
        boolean b = writeStatic(eng, "isControled", Boolean.TRUE);
        // 尝试别名（不同 ROM 符号）
        if (!a) a = writeStatic(eng, "m", Boolean.TRUE);
        return "isInited=" + a + ", isControled=" + b + ", actionCallback=" + cb;
    }

    // ==================== 能力探测 ====================

    /**
     * 报告本机实际可用的原厂接口（供 PC 控制台显示"到底有哪些能用"）。
     * 全部走运行时反射判定，不依赖编译期符号。
     */
    public String caps() {
        StringBuilder sb = new StringBuilder("{");

        String facadeName = pickName(T_FACADE_OBF, T_FACADE_FULL);
        String sysName = pickName(T_SYS_OBF, T_SYS_FULL);
        String cbName = pickName(T_CB_OBF, T_CB_FULL);
        String evName = pickName(T_EV_OBF, T_EV_FULL);
        String stName = pickName(T_ENGSTAT_OBF, T_ENGSTAT_FULL);

        sb.append("\"build\":\"").append(facadeName.contains(T_FACADE_OBF) || sysName.contains(T_SYS_OBF)
                ? "v1.5.10(R8)" : "full-name").append("\"");
        sb.append(",\"classes\":{");
        sb.append("\"facade\":\"").append(J.esc(facadeName)).append("\"");
        sb.append(",\"system\":\"").append(J.esc(sysName)).append("\"");
        sb.append(",\"engine\":\"").append(pickName(T_ENG)).append("\"");
        sb.append(",\"callback\":\"").append(J.esc(cbName)).append("\"");
        sb.append(",\"listener\":\"").append(J.esc(evName)).append("\"");
        sb.append(",\"eng_status\":\"").append(J.esc(stName)).append("\"");
        sb.append("}");

        // EngineService 动作 API
        Class<?> eng = pick(T_ENG);
        Class<?> cc = cbClass();
        sb.append(",\"engine_api\":{");
        sb.append("\"baseAction\":").append(hasMethod(eng, "baseAction", String[].class, cc));
        sb.append(",\"baseAction_delay\":").append(hasMethod(eng, "baseAction", Long.TYPE, String[].class, cc));
        sb.append(",\"specialDance\":").append(hasMethod(eng, "specialDance", String.class, cc));
        sb.append(",\"radomDance_bpm\":").append(hasMethod(eng, "radomDance", Integer.TYPE, cc));
        sb.append(",\"stopMove\":").append(hasMethod(eng, "stopMove", cc, Boolean.TYPE));
        sb.append(",\"moveInterface\":").append(hasMethod(eng, "moveInterface", String.class));
        sb.append(",\"moveInterface_speed\":").append(hasMethod(eng, "moveInterface", String.class, Integer.TYPE));
        sb.append(",\"realeaseControl_All\":").append(hasMethod(eng, "realeaseControl_All"));
        sb.append(",\"setBatteryLow\":").append(hasMethod(eng, "setBatteryLow", Boolean.TYPE));
        sb.append(",\"setRobotEventListener\":").append(hasMethod(eng, "setRobotEventListener", evClass()));
        sb.append("}");

        // SystemMethod（LED / 电源 / ADB）
        Class<?> sys = sysClass();
        sb.append(",\"system_api\":{");
        sb.append("\"led\":").append(jsonLedCaps(sys));
        sb.append(",\"sys\":").append(jsonSysCaps(sys));
        sb.append("}");

        // 引擎状态字段可读性
        sb.append(",\"stat_fields\":[");
        boolean first = true;
        for (String[] row : STAT_FIELDS) {
            boolean ok = fieldExists(eng, row[1]) || fieldExists(eng, row[2]);
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"name\":\"").append(J.esc(row[0])).append("\",\"ok\":").append(ok).append("}");
        }
        sb.append("]");

        // 事件枚举
        Class<?> ev = pick("com.auric.intell.rbt.xldbasesdk.config.RobotEvent");
        sb.append(",\"events\":").append(jsonEnumConstants(ev));

        // 额外线索
        sb.append(",\"extras\":{");
        sb.append("\"commonlib_reboot\":").append(hasMethod(pick(T_COMMON_REBOOT), "reboot", Context.class));
        sb.append(",\"led_typedef\":").append(pick("com.auric.intell.rbt.xldbasesdk.config.config.led.LedTypedef") != null);
        sb.append(",\"action_speed_set\":").append(pick("com.auric.intell.rbt.xldbasesdk.config.eng.ActionSpeedSet") != null);
        sb.append(",\"eng_data_persist\":").append(pick("com.auric.intell.rbt.xldbasesdk.config.eng.EngDataPersist") != null);
        sb.append(",\"motion_online\":").append(pick("com.auric.intell.rbt.xldbasesdk.Net.MotionDataRefresh") != null);
        sb.append(",\"serialport\":").append(pick("com.auric.intell.rbt.xldbasesdk.serialport.SerialPort") != null);
        sb.append("}");

        sb.append(",\"engine\":" + hasEngine());
        sb.append(",\"listening\":").append(listenerProxy != null);
        return sb.append("}").toString();
    }

    private String jsonLedCaps(Class<?> sys) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String[] row : LED_MAP) {
            boolean ok = hasMethod(sys, row[2], Context.class) || hasMethod(sys, row[3], Context.class);
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(row[0]).append(".").append(row[1]).append("\":").append(ok);
        }
        // 原厂没有独立方法的组合，标广播兜底
        sb.append(",\"g.blink.fast\":\"broadcast\"");
        sb.append(",\"g.blink.slow\":\"broadcast\"");
        sb.append(",\"y.blink.fast\":\"broadcast\"");
        sb.append(",\"y.blink.slow\":\"broadcast\"");
        sb.append(",\"p.blink.fast\":\"broadcast\"");
        sb.append(",\"p.blink.slow\":\"broadcast\"");
        sb.append(",\"r.blink.slow\":\"broadcast\"");
        sb.append(",\"b.blink.fast\":\"broadcast\"");
        sb.append(",\"all.on\":\"broadcast\"");
        sb.append(",\"all.blink.fast\":\"broadcast\"");
        sb.append(",\"all.blink.slow\":\"broadcast\"");
        return sb.append("}").toString();
    }

    private String jsonSysCaps(Class<?> sys) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String[] row : SYS_MAP) {
            boolean ok = hasMethod(sys, row[1], Context.class) || hasMethod(sys, row[2], Context.class);
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(row[0]).append("\":").append(ok);
        }
        return sb.append("}").toString();
    }

    private static boolean fieldExists(Class<?> c, String name) {
        if (c == null) return false;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                k.getDeclaredField(name);
                return true;
            } catch (NoSuchFieldException e) {
                continue;
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    private static String jsonEnumConstants(Class<?> c) {
        if (c == null) return "[]";
        try {
            Object[] vals = c.getEnumConstants();
            if (vals == null) return "[]";
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < vals.length; i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(J.esc(String.valueOf(vals[i]))).append("\"");
            }
            return sb.append("]").toString();
        } catch (Throwable t) {
            return "[]";
        }
    }

    // ==================== 工具 ====================

    private static String fail(String what, Throwable t) {
        Throwable c = t.getCause() != null ? t.getCause() : t;
        String msg = c.getClass().getSimpleName()
                + (c.getMessage() == null ? "" : (": " + c.getMessage()));
        Log.e(TAG, "sdk " + what + " fail: " + msg);
        return what + " fail " + msg;
    }

    /** 供 /sdk/caps 之外的地方做"这个原厂类在不在"的判断 */
    public static boolean classPresent(String name) {
        return pick(name) != null;
    }

    /** 供控制台生成动作序列的候选名提示（不联网，纯本地清单） */
    public static List<String> knownSeries() {
        List<String> l = new ArrayList<String>();
        l.add("hug");
        l.add("cheer");
        l.add("comeon");
        l.add("dance_L0M0R0");
        return l;
    }
}
