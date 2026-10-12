package com.ledi.agent.motion;

import com.ledi.agent.core.BootLog;

import com.ledi.agent.core.AgentService;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 动作控制器：反射调用原厂 EngineService。
 * 可用方法（逆向自 xldbasesdk 1.5.10）：
 *   moveInterface(String)                 按动作名执行（assets/motions 里的 80 个名字）
 *   moveInterface(String, int)
 *   radomDance(int, Object callback)      随机舞蹈
 *   specialDance(String, Object callback) 指定舞蹈（dance_* 名）
 *   baseAction(String, Object callback)
 *   stopMove(Object callback, boolean)
 */
public class MotionController {

    public static final String[] MOTIONS = {
            "walk", "stWalk", "turnLeft", "turnRight", "stTurnLeft", "stTurnRight",
            "hug", "handshake", "hiLefthand", "hiRighthand", "liftHands",
            "liftLefthand", "liftRighthand", "cheer", "comeon", "beckon",
            "crosshand", "angry", "dizzy", "wake", "run", "sledding",
            "trafficCommand", "flyMode", "leftRoll", "rightRoll", "leftRightRoll",
            "layStruggle", "stCheer", "stHiLeftHand", "stHiRighthand",
            "stLiftHands", "stStand", "reject",
            "special_honorOfKings", "special_jettGym", "special_rabbitDance",
            "special_superWings"
    };

    /**
     * 42 个舞曲（assets/motions 下 dance_* 条目）。
     * 大小写有意义（L/M/R 大写=大幅度，小写=小幅度），必须原样下发。
     */
    public static final String[] DANCE = {
            "dance_L0M0R0", "dance_L0M0RA", "dance_L0M0r5", "dance_L0M2R8", "dance_L0M3R0",
            "dance_L0M3r5", "dance_L0M4R0", "dance_L0m3R2", "dance_L0m5R0", "dance_L2M0R2",
            "dance_L3M0r5", "dance_L4M0r3", "dance_L5M0R5", "dance_L5M0R8", "dance_L5M2R1",
            "dance_L5M2r3", "dance_L5M3R0", "dance_L5M4r3", "dance_L5m3R2", "dance_L5m4r2",
            "dance_L6M0R6", "dance_L6M3R0", "dance_L7M0R7", "dance_L7M4R0", "dance_L8M0R6",
            "dance_L8M4r5", "dance_L9M0R9", "dance_L9M4r5", "dance_LAM0R0", "dance_LAM0RA",
            "dance_l2M0r3", "dance_l3M0R4", "dance_l3M0r3", "dance_l3M2R4", "dance_l3M2R5",
            "dance_l5M0R0", "dance_l5M0R3", "dance_l5M0r2", "dance_l5M3R1", "dance_l5M4R7",
            "dance_l5M4R8", "dance_l5m3r5",
    };

    private final Context ctx;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private volatile Object engine;   // EngineService 实例
    private volatile boolean exclusive = false;

    public MotionController(Context ctx) { this.ctx = ctx; }

    // ================= 电机姿态/动作回传 =================

    private volatile String lastAction = "";
    private volatile String lastPose = "";
    private volatile long lastActionTs = 0;

    private void remember(String action) {
        lastAction = action == null ? "" : action;
        lastActionTs = System.currentTimeMillis();
    }

    public String lastAction() { return lastAction; }
    public long lastActionTs() { return lastActionTs; }

    /** 由 PoseController 在写姿态文件时登记，代表当前「电机姿态」 */
    public void rememberPose(String spec) {
        lastPose = spec == null ? "" : spec;
        lastActionTs = System.currentTimeMillis();
    }

    public String lastPose() { return lastPose; }

    public void setEngine(Object engine) { this.engine = engine; }

    public boolean hasEngine() { return engine != null; }

    public void setExclusive(boolean on) {
        if (on == exclusive) return;
        exclusive = on;
        if (on) {
            // 停掉主 App（独占串口与屏幕），动作/表情由 agent 全权接管
            forceStop("com.auric.intell.rbt.xldrbtx");
            forceStop("com.auric.intell.rbt.rbtclient");
        } else {
            restartMainApp();
        }
    }

    public boolean isExclusive() { return exclusive; }

    private void forceStop(String pkg) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(Context.ACTIVITY_SERVICE);
            Method m = android.app.ActivityManager.class.getMethod("forceStopPackage", String.class);
            m.invoke(am, pkg);
            Log.i(AgentService.TAG, "forceStop " + pkg);
        } catch (Exception e) {
            Log.e(AgentService.TAG, "forceStop fail " + pkg, e);
        }
    }

    private void restartMainApp() {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.setPackage("com.auric.intell.rbt.xldrbtx");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.e(AgentService.TAG, "restartMainApp fail", e);
        }
    }

    /** 按动作名执行动作。异步执行，结果写日志。 */
    public String motion(final String name) {
        if (engine == null) { BootLog.w("motion 拒绝: engine==null name=" + name); return "engine not ready"; }
        final String nm = name;
        final int my = gen;
        pool.execute(new Runnable() {
            @Override public void run() {
                if (my != gen) { BootLog.w("motion 放弃（已被打断）: " + nm); return; }
                try {
                    Class<?> c = engine.getClass();
                    BootLog.i("motion 执行开始 name=" + nm + " engineClass=" + c.getName());
                    Method m;
                    try {
                        m = c.getMethod("moveInterface", String.class);
                        m.invoke(engine, nm);
                        BootLog.i("motion 调用 moveInterface(String) 完成 name=" + nm);
                    } catch (NoSuchMethodException e) {
                        m = c.getMethod("moveInterface", String.class, Integer.TYPE);
                        m.invoke(engine, nm, 100);
                        BootLog.i("motion 调用 moveInterface(String,int) 完成 name=" + nm);
                    }
                    remember(nm);
                    BootLog.i("motion ok: " + nm);
                } catch (Exception e) {
                    BootLog.w("motion fail: " + nm + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        });
        return "queued: " + name;
    }

    public String dance(final String name) {
        if (engine == null) { BootLog.w("dance 拒绝: engine==null"); return "engine not ready"; }
        final int my = gen;
        pool.execute(new Runnable() {
            @Override public void run() {
                if (my != gen) { BootLog.w("dance 放弃（已被打断）: " + name); return; }
                try {
                    Class<?> c = engine.getClass();
                    // 原厂回调接口 com.auric.intell.rbt.xldbasesdk.a.a.a（ActionCompleteCallBack）
                    Class<?> cb = null;
                    try { cb = Class.forName("com.auric.intell.rbt.xldbasesdk.a.a.a"); }
                    catch (Throwable t) {
                        try { cb = Class.forName("com.auric.intell.rbt.xldbasesdk.CallBack.eng.ActionCompleteCallBack"); }
                        catch (Throwable t2) { cb = Object.class; }
                    }
                    if (name == null || name.isEmpty()) {
                        c.getMethod("radomDance", Integer.TYPE, cb)
                                .invoke(engine, 60, null);
                        BootLog.i("dance ok: random");
                    } else {
                        c.getMethod("specialDance", String.class, cb)
                                .invoke(engine, name, null);
                        BootLog.i("dance ok: " + name);
                    }
                    remember(name == null || name.isEmpty() ? "dance(random)" : name);
                } catch (Exception e) {
                    BootLog.w("dance fail: " + name + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        });
        return "queued: " + (name == null ? "random dance" : name);
    }

    public String stop() {
        if (engine == null) return "engine not ready";
        final int my = gen;
        pool.execute(new Runnable() {
            @Override public void run() {
                if (my != gen) { BootLog.w("stop 放弃：已被更新的打断取代"); return; }
                try {
                    engine.getClass()
                            .getMethod("stopMove", Object.class, Boolean.TYPE)
                            .invoke(engine, null, false);
                    remember("stop");
                } catch (Exception e) {
                    Log.e(AgentService.TAG, "stopMove fail", e);
                }
            }
        });
        return "queued: stop";
    }

    // ================= 打断（抹头顶用） =================
    //
    // 和 stop() 的关键区别：**不走队列**。
    // 本类所有动作都跑在同一个单线程 pool 上，stop() 自己也是 pool 里的一个任务 ——
    // 于是"停下来"这件事会排在"正在跑的那个 8 秒舞蹈"后面，用户抹了头顶还要等它
    // 跳完才停，完全不像打断。
    // abort() 做两件事：
    //   ① gen++ —— 已排队还没开跑的任务在入口处看到自己代次过期，直接放弃；
    //   ② 在当前线程直接调 stopMove —— 不等队列，立刻停正在执行的那一个。
    private volatile int gen = 0;
    private volatile int aborts = 0;
    private volatile long lastAbortMs = 0;

    public int aborts() { return aborts; }
    public long lastAbortMs() { return lastAbortMs; }

    public String abort() {
        gen++;
        if (engine == null) return "engine not ready";
        java.lang.reflect.Method m = stopMoveMethod(engine.getClass());
        if (m == null) return "stopMove 不存在（引擎变了？）";
        try {
            // 第 1 个参数是厂商内部类型（xldbasesdk.a.a.a），我们手上没有它的实例，
            // 传 null 即可 —— 引擎只判断"要不要清队列"（第 2 个 boolean）。
            m.invoke(engine, null, Boolean.FALSE);
            remember("abort");
            aborts++;
            lastAbortMs = System.currentTimeMillis();
            BootLog.i("动作已被打断（abort #" + aborts + "，stopMove=" + sig(m) + "）");
        } catch (Exception e) {
            BootLog.w("abort stopMove 调用失败: " + e);
            return "stopMove 调用失败: " + e.getClass().getSimpleName();
        }
        return "aborted";
    }

    /**
     * 按**名字 + 参数个数**找 {@code stopMove}，不再写死参数类型。
     *
     * <p>★ 这里原来写的是 {@code getMethod("stopMove", Object.class, Boolean.TYPE)}，
     * 而引擎上的真实签名是
     * {@code EngineService.stopMove(Lcom/auric/intell/rbt/xldbasesdk/a/a/a; Z)V}
     * —— 第 1 个参数是厂商内部类型、**不是 Object**。{@code getMethod} 要求参数类型
     * 精确匹配，于是每次都在抛 NoSuchMethodException，被 catch 成一句
     * {@code "stopMove fail"} 记进日志里 —— 表面上"打断走了"，实际**动作从来没停过**
     * （只有播报被停了）。这是典型的"报错被当成正常返回"，所以现在按签名找。
     */
    private static java.lang.reflect.Method stopMoveMethod(Class<?> cls) {
        if (cachedStopMove != null && cachedStopMoveFor == cls) return cachedStopMove;
        java.lang.reflect.Method found = null;
        try {
            for (java.lang.reflect.Method mm : cls.getMethods()) {
                if (!"stopMove".equals(mm.getName())) continue;
                Class<?>[] pt = mm.getParameterTypes();
                if (pt.length == 2 && (pt[1] == Boolean.TYPE || pt[1] == Boolean.class)) {
                    found = mm; break;
                }
            }
        } catch (Throwable ignored) {}
        cachedStopMove = found;
        cachedStopMoveFor = cls;
        if (found != null) BootLog.i("动作打断: 找到 " + sig(found));
        else BootLog.w("动作打断: 引擎上没有 stopMove(?, boolean)");
        return found;
    }

    private static volatile java.lang.reflect.Method cachedStopMove;
    private static volatile Class<?> cachedStopMoveFor;

    private static String sig(java.lang.reflect.Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        Class<?>[] pt = m.getParameterTypes();
        for (int i = 0; i < pt.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(pt[i].getSimpleName());
        }
        return sb.append(')').toString();
    }

    public String baseAction(final String name) {
        if (engine == null) return "engine not ready";
        final int my = gen;
        pool.execute(new Runnable() {
            @Override public void run() {
                if (my != gen) { BootLog.w("baseAction 放弃（已被打断）: " + name); return; }
                try {
                    engine.getClass()
                            .getMethod("baseAction", String.class, Object.class)
                            .invoke(engine, name, null);
                    remember("base:" + name);
                } catch (Exception e) {
                    Log.e(AgentService.TAG, "baseAction fail", e);
                }
            }
        });
        return "queued: base " + name;
    }

    public static boolean isValidMotion(String name) {
        if (name == null) return false;
        for (String s : MOTIONS) if (s.equals(name)) return true;
        for (String s : DANCE) if (s.equals(name)) return true;
        return false;
    }

    /** 舞曲名（dance_*）走 specialDance，普通动作走 moveInterface。 */
    public static boolean isDance(String name) {
        return name != null && name.startsWith("dance_");
    }

    /** 按名字自动选正确通道执行（dance_* → specialDance，其余 → moveInterface）。 */
    public String playByName(final String name) {
        if (name == null || name.trim().isEmpty()) return "empty name";
        if (engine == null) return "engine not ready";
        return isDance(name) ? dance(name) : motion(name);
    }
}
