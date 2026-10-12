package com.ledi.agent.sense;

import com.ledi.agent.core.J;

import com.ledi.agent.cap.Emotion;

import com.ledi.agent.core.BootLog;

import com.ledi.agent.motion.Attitude;

import com.ledi.agent.core.MainThread;
import com.ledi.agent.motion.MotionController;
import com.ledi.agent.cap.ProactiveTalk;
import com.ledi.agent.voice.ReactionEngine;
import com.ledi.agent.ui.SleepMode;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 传感器中枢：把机器人的「顶部/脸部触摸、按键、陀螺仪姿态、电量、电机状态」
 * 全部收集起来，供控制界面显示、供 AI 作为上下文使用。
 *
 * 数据来源（逆向原厂 xldbasesdk + 主 App 的 SystemBroadcastReceiver 确认）：
 *   触摸  com.auric.intell.xld.rbt.face.head.down/up     顶部（头顶）触摸
 *         com.auric.intell.xld.rbt.face.l.down/up        左脸触摸
 *         com.auric.intell.xld.rbt.face.r.down/up        右脸触摸
 *   按键  com.auric.intell.xld.rbt.key.head.down/up      头顶按键
 *         com.auric.intell.xld.rbt.key.power.down/up     电源键
 *   姿态  com.auric.intell.xld.rbt.{liedown,overturn,trip,cewo,takeup,paxia,shake,stand,unknown}
 *   电量  com.auric.intell.xld.rbt.event.lowbattery + android.intent.action.BATTERY_CHANGED
 *   校准  android.intent.action.cz.gsensorcali.success/fail
 *         请求校准：发 android.intent.action.cszj.gsensorcali
 *   系统  com.auric.intell.xld.os.{interruput,poweroff,reboot,usbon,usboff}
 *
 * 另外直接读 Android 的陀螺仪/加速度计（若设备有该硬件），给出原始 x/y/z 与倾角，
 * 这样即使 MCU 只上报离散姿态事件，控制界面与 AI 也能看到连续数值。
 *
 * 电机状态：引擎独占串口，无法直接读舵机角度；这里报告
 *   引擎可用性 / 排他状态 / 最近下发的关节姿态 / 是否正在执行，作为「电机姿态」。
 */
public class SensorHub implements SensorEventListener {

    // ================= 广播 action（逆向自原厂） =================
    public static final String FACE_HEAD_DOWN = "com.auric.intell.xld.rbt.face.head.down";
    static final String FACE_HEAD_UP   = "com.auric.intell.xld.rbt.face.head.up";
    public static final String FACE_L_DOWN    = "com.auric.intell.xld.rbt.face.l.down";
    static final String FACE_L_UP      = "com.auric.intell.xld.rbt.face.l.up";
    public static final String FACE_R_DOWN    = "com.auric.intell.xld.rbt.face.r.down";
    static final String FACE_R_UP      = "com.auric.intell.xld.rbt.face.r.up";
    static final String KEY_HEAD_DOWN  = "com.auric.intell.xld.rbt.key.head.down";
    static final String KEY_HEAD_UP    = "com.auric.intell.xld.rbt.key.head.up";
    static final String KEY_POWER_DOWN = "com.auric.intell.xld.rbt.key.power.down";
    static final String KEY_POWER_UP   = "com.auric.intell.xld.rbt.key.power.up";
    public static final String LOW_BATTERY    = "com.auric.intell.xld.rbt.event.lowbattery";
    static final String CALI_OK        = "android.intent.action.cz.gsensorcali.success";
    static final String CALI_FAIL      = "android.intent.action.cz.gsensorcali.fail";
    static final String CALI_REQ       = "android.intent.action.cszj.gsensorcali";
    static final String GS_STATE_QUERY = "com.auric.intell.xld.os.get.gsensor.state";

    /** 陀螺仪姿态事件 → 中文 */
    private static final String[][] POSTURE = {
            {"com.auric.intell.xld.rbt.liedown",   "躺着"},
            {"com.auric.intell.xld.rbt.overturn",  "翻转/倒立"},
            {"com.auric.intell.xld.rbt.trip",      "绊倒/跌倒"},
            {"com.auric.intell.xld.rbt.cewo",      "侧倾"},
            {"com.auric.intell.xld.rbt.takeup",    "被拿起"},
            {"com.auric.intell.xld.rbt.paxia",     "趴下"},
            {"com.auric.intell.xld.rbt.shake",     "被摇晃"},
            {"com.auric.intell.xld.rbt.stand",     "站立"},
            {"com.auric.intell.xld.rbt.unknown",   "姿态未知"},
    };

    private final Context ctx;
    private final MotionController motion;

    // ---- 实时状态 ----
    public volatile String posture = "站立";
    public volatile String postureRaw = "stand";
    public volatile long postureTs = 0;

    public volatile String lastTouch = "";        // head|lface|rface
    public volatile String lastTouchAct = "";     // down|up
    public volatile long lastTouchTs = 0;
    public volatile int touchCount = 0;           // 累计触摸次数（down 计数）
    public volatile boolean touchingHead = false; // 头顶正被按住
    public volatile boolean touchingLF = false;
    public volatile boolean touchingRF = false;

    public volatile String lastButton = "";
    public volatile long lastButtonTs = 0;
    public volatile int buttonCount = 0;          // 任意按键累计次数

    // ---- 头顶**按键**（v116）----
    // ★ 为什么要计数：`lastButton` 存的是 action 字符串，**连按同一个键它不变**
    //   ⇒ 只看 `last` 的观测脚本会把"按了两次"看成"什么都没发生"（我第一版
    //   的 _keywatch.py 就犯了这个错）。要证明"按键确实送到了"，必须看**计数**。
    public volatile int headKeyCount = 0;         // 头顶键"按下"累计次数
    public volatile long lastHeadKeyMs = 0;
    public volatile boolean headKeyHeld = false;  // 头顶键此刻是否按住

    public volatile int battery = -1;
    public volatile boolean charging = false;
    public volatile boolean batteryLow = false;
    /**
     * ★ v202：最近一次电池读数更新的时刻（ms）。0 = 开机以来还没读到过。
     *
     * <p>为什么要它：电量是**广播驱动**的，只在 {@code ACTION_BATTERY_CHANGED} 到达时更新。
     * 光看 {@code battery=87} 分不清它是"刚刚读到"还是"两小时前的陈旧值"——
     * 这正是本项目最怕的那种"它不报错，它报一个数"。{@link Battery#info} 把它换算成
     * {@code age_ms} 摆出来，旧值一眼可见。</p>
     */
    public volatile long batteryTs = 0;

    public volatile String caliState = "";        // ok|fail|""
    public volatile long caliTs = 0;

    public volatile float gyroX, gyroY, gyroZ;
    public volatile float accX, accY, accZ;
    public volatile float pitch, roll;            // 倾角（度）
    public volatile boolean hasGyro = false, hasAccel = false;
    /**
     * ★★ v153/v154：加速度模长 |a|，以及由它推出的**IMU 可信度**。
     *
     * <h3>为什么要单独留这两个字段</h3>
     * 2026-09-24 真机踩到过：原厂 g-sensor 未校准时静置读到
     * {@code accel=[0.03, 1.64, 22.42]}、{@code |a|=22.48}（≈2.3 g），
     * 凭空造出一个 {@code pitch=-4.18°} 的假倾角，还把真倾斜压缩到约 60%。
     * 当时**没有任何地方体现这件事** —— 面板上就是三个看着挺像真的数字，
     * 典型的"它不报错，它报一个数"。
     *
     * <p>{@link Attitude#magPlausible} 是唯一一条不依赖机器人朝向的判据：
     * 静止时 {@code |a| ≡ g}。把它的结论挂到接口和面板上，这种坏法以后一眼可见。
     *
     * <h3>★★★ v154：这条判据一开始是**会误导人**的，倾斜测试当场把它暴露了</h3>
     * v153 初版只看 {@code |a|} 就直接下"需标定"的结论。真机倾斜实测（静止、抖动&lt;0.6%）：
     * <pre>
     *   站立  pitch   0°  roll   0°  |a| =  9.79   ← 对
     *   小倾  pitch  13°  roll  33°  |a| = 10.02
     *   侧倾  pitch  38°  roll  60°  |a| = 14.38   ← 1.47 g，物理上不可能
     *   躺倒  pitch  46°  roll   5°  |a| = 18.40   ← 1.88 g
     *   趴下  pitch  32°  roll 177°  |a| =  3.48   ← 0.35 g
     * </pre>
     * 也就是说<b>本机加速度计在大倾角下本身就严重偏离 g</b>（偏离量随倾角增大），
     * 这是硬件/驱动层面的既有事实，**跟"该不该重新标定"是两件事**。
     * 初版判据会在机器人侧躺时弹"⚠需标定"⇒ 用户真去标定 ⇒ 把好数据校坏
     * （标定必须把机器人摆正放在水平面上）。这正是本项目最怕的那种"判据自己错"。
     *
     * <p>现在的口径见 {@link #imuStateNow()}：**只有"站立 + 静置"时 |a| 偏离才算故障**，
     * 其余情况分别报 {@code tilted / moving / warming}，由面板与接口如实呈现。
     */
    public volatile float accMag = 0f;
    /** 最近一段时间 |a| 的极差（判断"是否静置"，见 {@link #imuStateNow()}） */
    public volatile float magSpread = 0f;
    /** ok | off | tilted | moving | warming | unknown（见 {@link #imuStateNow()}） */
    public volatile String imuState = "unknown";
    /** 最近 10 次 |a| 采样（约 0.7s @ SENSOR_DELAY_NORMAL） */
    private final float[] magRing = new float[10];
    private int magIdx = 0;
    // ★ "水平"容差与整条状态机都搬到了 Attitude（纯函数，可被 /attitudetest 断言），
    //   这里不再留第二份 —— 阈值散成两份就是下一个"修在一个地方 ≠ 修好了"。
    /** IMU 监听当前是否挂着（屏保期间会摘掉，见 setImuEnabled）。 */
    private volatile boolean imuOn = false;
    public volatile long imuTs = 0;

    // 最近事件环形缓冲（供控制台展示 / AI 上下文）
    private final Deque<String> events = new ArrayDeque<String>();

    private boolean started = false;
    private SensorManager sm;

    public SensorHub(Context ctx, MotionController motion) {
        this.ctx = ctx;
        this.motion = motion;
    }

    // ================= 启动 =================

    public void start() {
        if (started) return;
        started = true;
        IntentFilter f = new IntentFilter();
        for (String a : new String[]{
                FACE_HEAD_DOWN, FACE_HEAD_UP, FACE_L_DOWN, FACE_L_UP, FACE_R_DOWN, FACE_R_UP,
                KEY_HEAD_DOWN, KEY_HEAD_UP, KEY_POWER_DOWN, KEY_POWER_UP,
                LOW_BATTERY, CALI_OK, CALI_FAIL, CALI_REQ, GS_STATE_QUERY,
                Intent.ACTION_BATTERY_CHANGED,
                "com.auric.intell.xld.os.interruput", "com.auric.intell.xld.os.poweroff",
                "com.auric.intell.xld.os.reboot", "com.auric.intell.xld.os.usbon",
                "com.auric.intell.xld.os.usboff"}) {
            f.addAction(a);
        }
        for (String[] p : POSTURE) f.addAction(p[0]);
        // ★★ v133：广播**收到独立线程**，不再走主线程。
        //
        // 为什么非改不可（2026-09-23 实测）：短按/长按的判据是"DOWN 与 UP 两条广播的
        // **到达时刻**之差"。挂主线程时，主线程被 `MainThread.call(...)`（建引擎、开麦、
        // 碰 SDK）堵住几秒是常态 ⇒ DOWN 收到之后要隔好几秒才收到 UP ⇒ 一次 100ms 的
        // 物理短按被判成 ≥3000ms 的**长按** ⇒ 屏保被随机翻来翻去（用户看到的是"按键坏了"）。
        // 而 `input keyevent 3` 连发时实测过"一次读到 3 个 DOWN"的成批到达，就是这个机制。
        // log() 本身是 synchronized 的、各动作各自起线程，所以换线程是安全的。
        try {
            if (recvThread == null) {
                recvThread = new HandlerThread("ledi-key");
                recvThread.start();
            }
            Handler h = new Handler(recvThread.getLooper());
            keyHandler = h;                       // 长按定时器与广播投递同一个线程，避免跨线程错序
            ctx.registerReceiver(recv, f, null, h);
        } catch (Throwable t) {
            BootLog.w("sensor 广播注册失败（退回主线程）: " + t);
            try { ctx.registerReceiver(recv, f); } catch (Exception ignored) {}
        }

        // 直接读本机 IMU（有则用，无则只靠 MCU 事件）
        try {
            sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        } catch (Exception ignored) {}
        setImuEnabled(true);

        // 初始电量
        readBattery(null);
        log("sensor hub started (gyro=" + hasGyro + ", accel=" + hasAccel + ")");
    }

    public void stop() {
        if (!started) return;
        started = false;
        try { ctx.unregisterReceiver(recv); } catch (Exception ignored) {}
        try { if (sm != null) sm.unregisterListener(this); } catch (Exception ignored) {}
        try { if (recvThread != null) { recvThread.quitSafely(); recvThread = null; keyHandler = null; } } catch (Exception ignored) {}
    }

    public boolean isRunning() { return started; }

    // ================= 广播接收 =================

    private final BroadcastReceiver recv = new BroadcastReceiver() {
        public void onReceive(Context c, Intent it) {
            String a = it == null ? "" : it.getAction();
            if (a == null) return;
            long now = System.currentTimeMillis();
            lastRecvMs = now;
            lastRecvAction = a;
            if (FACE_HEAD_DOWN.equals(a) || FACE_HEAD_UP.equals(a)) {
                boolean down = a.endsWith("down");
                SleepMode.touch();      // v142：被摸 = 有人在用（闲置自动屏保的判据输入）
                lastTouch = "head"; lastTouchAct = down ? "down" : "up"; lastTouchTs = now;
                touchingHead = down;
                if (down) touchCount++;
                log("头顶" + (down ? "被摸" : "松开"));
                if (down) firePat();      // ★ 抹头顶 = 最高优先级打断 + 强制唤醒
            } else if (FACE_L_DOWN.equals(a) || FACE_L_UP.equals(a)) {
                boolean down = a.endsWith("down");
                SleepMode.touch();
                lastTouch = "lface"; lastTouchAct = down ? "down" : "up"; lastTouchTs = now;
                touchingLF = down;
                if (down) touchCount++;
                log("左脸" + (down ? "被摸" : "松开"));
            } else if (FACE_R_DOWN.equals(a) || FACE_R_UP.equals(a)) {
                boolean down = a.endsWith("down");
                SleepMode.touch();
                lastTouch = "rface"; lastTouchAct = down ? "down" : "up"; lastTouchTs = now;
                touchingRF = down;
                if (down) touchCount++;
                log("右脸" + (down ? "被摸" : "松开"));
            } else if (KEY_HEAD_DOWN.equals(a) || KEY_HEAD_UP.equals(a)) {
                // ★ 头顶**按键**（区别于 face.head 的"抹头顶"触摸）。
                //   原厂把它喂给场景状态机（RobotEvent.BTN_HEAD_DOWN/UP/CLICK）。
                // ★ v127：按下**不立刻动作**，只记时间戳并起一个长按定时器；动作在
                //   **抬手**时按按住时长分流（短按 = 打断/唤醒，长按 = 屏保）。
                //   为什么不按下就动作：v126 及以前真机实测"按下后 31ms 就 startActivity"，
                //   长按会先触发一次短按动作（唤醒 + 原厂脸调前台）再进屏保，
                //   用户会看到"先被唤醒一下、然后才铺图"；而且那样物理上**不存在**长按。
                boolean down = a.endsWith("down");
                lastButton = a; lastButtonTs = now;
                headKeyHeld = down;
                // ★ v133：把广播里的 extras 打出来。我们要找的是**事件本身的时刻**
                //   （原厂/框架有没有带时间戳）。有的话就不用"到达时刻之差"当按住时长了 ——
                //   那是被投递延迟污染的判据（见 start() 里那段注释）。
                if (it.getExtras() != null && !it.getExtras().isEmpty()) {
                    StringBuilder eb = new StringBuilder();
                    for (String k : it.getExtras().keySet()) {
                        eb.append(k).append('=').append(it.getExtras().get(k)).append(' ');
                    }
                    log("头顶按键广播 extras: " + eb);
                } else if (!extrasLogged) {
                    extrasLogged = true;
                    log("头顶按键广播**没有** extras（判据只能靠到达时刻差）");
                }
                if (down) {
                    headKeyCount++;
                    lastHeadKeyMs = now;
                    headKeyDownMs = now;
                    headKeyLongFired = false;
                    log("★头顶按键 按下（累计 " + headKeyCount + " 次）");
                    scheduleLongPress();
                } else {
                    long held = headKeyDownMs == 0 ? 0 : now - headKeyDownMs;
                    lastHeadKeyHeldMs = held;
                    cancelLongPress();
                    headKeyDownMs = 0;
                    // ★★★ v142：抬手时**按真实按住时长**分流，不再只看"定时器有没有先响"。
                    //
                    // 旧写法的判据是 `headKeyLongFired` —— 也就是"3 秒定时器必须准时跑到"。
                    // 但那条定时器跑在 ledi-key 这条 HandlerThread 上，只要它被任何一个
                    // 监听者（ReactionEngine / ProactiveTalk 会碰 SDK、TTS、网络）堵住，
                    // 定时器就迟到，于是：**物理按了 4 秒，却因为 up 先被处理而被算成短按**
                    // ⇒ 屏保进不去、反而把机器人唤醒 + 把主程序调前台。
                    // 用户反馈的"长按头顶键进屏保成功率有问题"正是这个形状。
                    // 按住的时长是**客观事实**，不该由定时器准不准来决定走哪个分支；
                    // 定时器从此只降级为"到点就先触发"的提前量，判据一律以 held 为准。
                    if (headKeyLongFired) {
                        log("头顶按键 抬起（按住 " + held + "ms）长按已处理，不再触发短按");
                    } else if (held >= HEADKEY_LONG_MS - 50) {
                        log("头顶按键 抬起（按住 " + held + "ms ≥ " + HEADKEY_LONG_MS
                                + "ms）→ 定时器未先触发，按**长按**补判");
                        headKeyLongFired = true;
                        fireHeadKeyLong(held);
                    } else {
                        log("头顶按键 抬起（按住 " + held + "ms < " + HEADKEY_LONG_MS
                                + "ms）→ 按短按处理");
                        fireHeadKeyShort(held);
                    }
                }
            } else if (a.startsWith("com.auric.intell.xld.rbt.key.")) {
                lastButton = a; lastButtonTs = now;
                buttonCount++;
                log("按键 " + a.substring(a.lastIndexOf('.') + 1));
            } else if (LOW_BATTERY.equals(a)) {
                batteryLow = true; log("低电量事件");
            } else if (CALI_OK.equals(a)) {
                caliState = "ok"; caliTs = now; log("陀螺仪校准成功");
            } else if (CALI_FAIL.equals(a)) {
                caliState = "fail"; caliTs = now; log("陀螺仪校准失败");
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(a)) {
                readBattery(it);
            } else if (GS_STATE_QUERY.equals(a)) {
                log("引擎上报陀螺仪状态查询");
            } else if (a.startsWith("com.auric.intell.xld.os.")) {
                log("系统事件 " + a.substring(a.lastIndexOf('.') + 1));
            } else {
                for (String[] p : POSTURE) {
                    if (p[0].equals(a)) {
                        posture = p[1];
                        postureRaw = p[0].substring(p[0].lastIndexOf('.') + 1);
                        postureTs = now;
                        log("姿态 " + posture);
                        break;
                    }
                }
            }
            fanout(a);
        }
    };

    private void readBattery(Intent it) {
        try {
            // ★ v202：任何一条读路走到这里都算"刚更新过" —— Battery.info 的 age_ms 据此算。
            //   放在最前：两条分支（广播 / 开机兜底）共用同一时刻，不各记一份。
            batteryTs = System.currentTimeMillis();
            if (it != null) {
                int lv = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int sc = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                int st = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                if (lv >= 0 && sc > 0) battery = lv * 100 / sc;
                charging = (st == BatteryManager.BATTERY_STATUS_CHARGING
                        || st == BatteryManager.BATTERY_STATUS_FULL);
                return;
            }
            BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            // 充电状态兜底
            Intent b2 = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b2 != null) {
                int st = b2.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = (st == BatteryManager.BATTERY_STATUS_CHARGING
                        || st == BatteryManager.BATTERY_STATUS_FULL);
            }
        } catch (Exception ignored) {}
    }

    /** 主动查询：发原厂的 gsensor 状态查询广播（引擎会回状态） */
    public void queryGsensor() {
        try { ctx.sendBroadcast(new Intent(GS_STATE_QUERY)); } catch (Exception ignored) {}
    }

    /** 陀螺仪校准：发原厂校准请求广播 */
    public void calibrate() {
        try { ctx.sendBroadcast(new Intent(CALI_REQ)); } catch (Exception ignored) {}
        log("已请求陀螺仪校准");
    }

    /**
     * 把 {@link #imuStateNow()} 的结论翻成一句**能照着做**的话。
     * <p>★ 这里最容易犯的错是"一律说需标定" —— 那会在机器人侧躺时把人骗去标定，
     * 而标定要求"摆正放在水平面上"。所以 tilted / moving 两种要明确说
     * <b>"这不是故障，别去标定"</b>。
     */
    public String imuHint() {
        String s = imuState;
        if ("ok".equals(s)) return "";
        if ("unknown".equals(s)) return "无加速度计";
        if ("warming".equals(s)) return "采样不足，稍候再看";
        if ("moving".equals(s)) return "机器人正在动（|a| 极差 " + round(magSpread)
                + "）—— 任何姿态判据此时都不成立，静止后重看";
        if ("tilted".equals(s)) return "当前不是站立姿态（或读数不水平），而**本机加速度计在大倾角下 "
                + "|a| 本就偏离 g**（侧躺实测 14.4 / 躺倒 18.4，站立才是 9.79）"
                + "⇒ 此刻的 pitch/roll 不可信；这不是故障，**不要**去标定";
        if ("off".equals(s)) return "站立静置时 |a| = " + round(accMag)
                + "（应 ≈9.81）⇒ 加速度计确实需要重新标定：POST /gsensorcali，"
                + "标定前请把机器人摆正放在水平平面上";
        return "";
    }

    public interface Listener { void onSensorEvent(String action); }

    // ★ 这里原来是"单个监听器 + setListener 覆盖"，而 ReactionEngine 与 ProactiveTalk
    //   各自都在构造时调了一次 setListener —— 后建的那个把先建的顶掉。
    //   实际顺序是 ReactionEngine(先) → ProactiveTalk(后)，于是 ReactionEngine
    //   永远收不到任何传感器事件：控制台上的「AI 接管 / 机器人自带反应」两个开关
    //   点了没反应，却看不出任何异常（典型的假绿灯）。改成扇出，两个都收得到。
    private final java.util.List<Listener> listeners =
            new java.util.concurrent.CopyOnWriteArrayList<Listener>();

    /** 注册一个传感器事件监听器（多个监听器会**都**收到，不互相覆盖）。 */
    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    /** 兼容旧调用：行为已从"替换"改为"追加"，避免再次顶掉别人。 */
    public void setListener(Listener l) { addListener(l); }

    /**
     * 把事件扇出给监听者 —— <b>异步，绝不占用广播线程</b>（v142 关键修复）。
     *
     * <h3>为什么必须异步（这是"长按头顶键成功率有问题"的头号嫌疑）</h3>
     * 本类的广播接收器（含头顶键 DOWN/UP 与<b>长按定时器</b>）跑在
     * {@code HandlerThread("ledi-key")} 上 —— 同一个 Looper。而监听者收到事件后做的事
     * 一点都不轻：{@code ReactionEngine.react()} 会调 {@code face.play()} /
     * {@code motion.motion()}（碰原厂 SDK）、{@code tts.speak()}（本地合成引擎）、
     * 甚至 {@code speaker.speak()} 走 HTTP 到电脑端（网络超时以秒计）。
     *
     * <p>一旦这一串被卡住 —（最恶劣的一种：屏保中进程被 CrashGuard 重启，
     * 内存里的 {@code sleepGate} 与 {@code SleepMode.sleeping} 双双归零，而原厂还冻着
     * ⇒ {@code face.play()} 直接在 binder 上等下去）— {@code ledi-key} 这条线程就<b>永久堵死</b>：
     * 后果不只是"这次反应没做"，而是
     * ① 之后的按键广播全部堆在 Looper 里收不到；
     * ② 长按定时器再也跑不起来，于是 {@code headKeyLongFired} 永远为 false，
     *    抬手时被算成 <b>短按</b> ⇒ 屏保进不去；表现就是"长按时灵时不灵、重启一下又好"。
     *
     * <p>所以这里把监听者放到独立线程：广播线程只负责记时间戳、起定时器，
     * 这些必须是**常量时间**的操作。队列满了就丢（传感器事件是"最新态"语义，
     * 丢旧的比堆到 OOM 好）。
     */
    private void fanout(final String action) {
        if (listeners.isEmpty()) return;
        try {
            if (!fanoutStarted) {
                synchronized (this) {
                    if (!fanoutStarted) {
                        fanoutExec = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                                new ArrayBlockingQueue<Runnable>(32),
                                new ThreadPoolExecutor.DiscardOldestPolicy());
                        fanoutStarted = true;
                    }
                }
            }
            try {
                fanoutExec.execute(new Runnable() {
                    @Override public void run() {
                        for (Listener l : listeners) {
                            try { l.onSensorEvent(action); }
                            catch (Throwable t) { log("监听器异常(" + action + "): " + t); }
                        }
                    }
                });
            } catch (Throwable full) {
                droppedEvents++;
            }
        } catch (Throwable t) {
            log("扇出失败(" + action + "): " + t);
        }
    }

    private volatile ThreadPoolExecutor fanoutExec;
    private volatile boolean fanoutStarted = false;
    /** 因为队列满而被丢掉的事件数（>0 说明监听者太慢，要查它）。 */
    public volatile int droppedEvents = 0;

    /** 广播线程健康（诊断用）：收到事件的时刻与"最近一次扇出用了多久"。 */
    public volatile long lastRecvMs = 0;
    public volatile String lastRecvAction = "";

    // ================= 抹头顶（v61） =================
    //
    // 为什么单独开一个钩子、而不是复用上面的 Listener：
    //   Listener 收到事件后由 ReactionEngine 按"AI 接管 / 原厂反应"两个开关决定要不要
    //   理你；而"抹头顶"是**最高优先级的打断**——不管开关怎么设、不管机器人正在跳舞
    //   还是在说话，都要立刻停下来并进入应答。所以它必须走在所有开关之前。
    //   （用户要求："抹头顶强制唤醒打断"）
    public interface PatListener { void onHeadPat(); }
    private volatile PatListener patListener;
    public void setPatListener(PatListener l) { this.patListener = l; }

    public volatile int patCount = 0;         // 抹头顶次数
    public volatile long lastPatMs = 0;
    /** 距上次抹头顶的毫秒数（没摸过返回 -1） */
    public long lastPatAgo() {
        return lastPatMs == 0 ? -1 : System.currentTimeMillis() - lastPatMs;
    }
    // 连拍去抖：同一次"抹"常常连着来几个 down，或者用户快速连抹。
    // 1.2 秒内只触发一次，否则会把应答链路打成连环触发。
    private static final long PAT_DEBOUNCE_MS = 1200;

    /**
     * 屏保闸（v142）。屏保期间"感应不该驱动任何动作"：
     * <ul>
     *   <li><b>不回调抹头顶</b>：{@link PatListener#onHeadPat} 那条链路会去调
     *       {@code motion.abort()} / {@code tts.stopNow()}，而 v140 起屏保会 SIGSTOP
     *       原厂进程 —— 冻着的时候调它的 SDK 是<b>在 binder 上永久等下去</b>
     *       （不是失败、是挂住），所以屏保里绝不能让感应事件走到 SDK。</li>
     *   <li><b>陀螺仪监听也停</b>（{@link #setImuEnabled}）：用户明确要求
     *       "屏保期间陀螺/感应全部停用"。屏幕都被盖住了，采样没有任何用途。</li>
     * </ul>
     * 放在 SensorHub 而不是各自调用点，是为了让"屏保中不响应感应"只有**一处**真话 ——
     * 本项目吃过太多次"修在一个地方 ≠ 修好了"。
     */
    private volatile boolean asleepGate;

    public synchronized void setAsleepGate(boolean on) {
        asleepGate = on;
        setImuEnabled(!on);
        log("屏保闸 " + (on ? "关（感应/陀螺全停）" : "开（感应恢复）"));
    }

    public boolean asleepGate() { return asleepGate; }

    private void firePat() {
        long now = System.currentTimeMillis();
        // ★ v142：屏保中只计数、不动作 —— 见 asleepGate 字段注释（冻住的原厂 SDL 调不得）。
        if (asleepGate || SleepMode.sleeping) {
            patCount++;
            lastPatMs = now;
            log("★抹头顶（屏保中，只计数不响应）第 " + patCount + " 次");
            return;
        }
        if (now - lastPatMs < PAT_DEBOUNCE_MS) return;
        patCount++;
        lastPatMs = now;
        // ★ v214：真被摸到了 ⇒ 给情绪引擎一个事件（心情/激活度轻轻抬一下）。
        //   放在去抖之后、真正"响应"的这一支：屏保中"只计数不响应"那一支不算接触。
        try { Emotion.onTouch(); } catch (Throwable ignored) {}
        log("★抹头顶 第 " + patCount + " 次 —— 强制唤醒并打断");
        final PatListener pl = patListener;
        if (pl == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try { pl.onHeadPat(); } catch (Throwable t) {
                    log("抹头顶处理异常: " + t);
                }
            }
        }, "ledi-headpat").start();
    }

    // ================= 头顶按键（v116 / v127 长短按） =================
    //
    // 与"抹头顶"分开的理由：抹头顶是**触摸**（face.head.down），原厂拿它做"强制唤醒打断"；
    // 头顶键是**按键**（key.head.down），原厂把它喂给场景状态机（RobotEvent.BTN_HEAD_CLICK）。
    //
    // ★ v127 分短按 / 长按，阈值 3000ms **与原厂 `robot.main.c.b()` 对齐**（REF-L）：
    //     短按（< 3000ms）→ onHeadKeyDown(heldMs)  = 原来的"打断 / 唤醒 / 开主程序"
    //     长按（≥ 3000ms）→ onHeadKeyLong(heldMs)   = 屏保（用户要求）
    //
    // ★ 长按判定靠**定时器自检**，不靠"后来收到 up 时算时长"：
    //   万一某次 `key.head.up` 丢了，纯靠 up 会让按键永久卡死（一直以为还按着）。
    //   定时器到点重新自检「仍按住 && 没被取消 && 起按时间戳没变 && 时长真的够了」，
    //   是幂等的：多跑几次也不会重复触发。
    public interface HeadKeyListener {
        /** 短按：抬手时回调（heldMs = 实际按住时长）。 */
        void onHeadKeyDown(long heldMs);
        /** 长按：按住到 {@link #HEADKEY_LONG_MS} 时回调一次；抬手不会再触发短按。 */
        void onHeadKeyLong(long heldMs);
    }
    private volatile HeadKeyListener headKeyListener;
    public void setHeadKeyListener(HeadKeyListener l) { this.headKeyListener = l; }

    /** 长按阈值：与原厂头顶键计时器 `c$3(3000, 500)` 对齐。 */
    public static final long HEADKEY_LONG_MS = 3000;
    /** 连按去抖：一次物理按动常带 down/up/down 抖动，快门内只认一次动作。 */
    private static final long HEADKEY_DEBOUNCE_MS = 800;

    public volatile int headKeyShortCount = 0;    // 判定为短按并已处理的次数
    public volatile int headKeyLongCount = 0;     // 判定为长按并已处理的次数
    public volatile long lastHeadKeyHeldMs = 0;   // 最近一次按住的时长
    public volatile String lastHeadKeyKind = "";  // short | long | ""

    /** 距上次按头顶键的毫秒数（没按过返回 -1） */
    public long lastHeadKeyAgo() {
        return lastHeadKeyMs == 0 ? -1 : System.currentTimeMillis() - lastHeadKeyMs;
    }

    // ---- 长按定时器 ----

    private volatile long headKeyDownMs = 0;
    private volatile boolean headKeyLongFired = false;
    private volatile long lastFireHeadKeyMs = 0;
    private Handler keyHandler;
    private Runnable pendingLong;
    /** v133：extras 只播报一次"没有"，避免刷屏。 */
    private volatile boolean extrasLogged = false;

    /** v133：广播接收与长按定时器共用的独立线程（不再挂主线程）。 */
    private HandlerThread recvThread;

    private Handler keyHandler() {
        if (keyHandler == null) keyHandler = new Handler(Looper.getMainLooper());
        return keyHandler;
    }

    private void scheduleLongPress() {
        cancelLongPress();
        final long t0 = headKeyDownMs;
        pendingLong = new Runnable() {
            @Override public void run() {
                long down = headKeyDownMs;
                if (!headKeyHeld || down != t0 || headKeyLongFired) return;
                long held = System.currentTimeMillis() - down;
                // Handler 可能被主线程忙等拖后，真要判长按就得时长真的够
                if (held < HEADKEY_LONG_MS - 50) return;
                headKeyLongFired = true;
                fireHeadKeyLong(held);
            }
        };
        try { keyHandler().postDelayed(pendingLong, HEADKEY_LONG_MS); }
        catch (Throwable t) { log("头顶按键 长按定时器起不来: " + t); }
    }

    private void cancelLongPress() {
        Runnable r = pendingLong;
        pendingLong = null;
        if (r != null && keyHandler != null) {
            try { keyHandler.removeCallbacks(r); } catch (Throwable ignored) {}
        }
    }

    private void fireHeadKeyShort(final long heldMs) {
        long now = System.currentTimeMillis();
        if (now - lastFireHeadKeyMs < HEADKEY_DEBOUNCE_MS) {
            log("头顶按键 短按去抖丢弃（距上次动作 " + (now - lastFireHeadKeyMs) + "ms）");
            return;
        }
        lastFireHeadKeyMs = now;
        headKeyShortCount++;
        lastHeadKeyKind = "short";
        final HeadKeyListener l = headKeyListener;
        if (l == null) { log("头顶按键：没有监听者（HeadKey 未接线）"); return; }
        new Thread(new Runnable() {
            @Override public void run() {
                try { l.onHeadKeyDown(heldMs); } catch (Throwable t) {
                    log("头顶按键短按处理异常: " + t);
                }
            }
        }, "ledi-headkey").start();
    }

    private void fireHeadKeyLong(final long heldMs) {
        long now = System.currentTimeMillis();
        if (now - lastFireHeadKeyMs < HEADKEY_DEBOUNCE_MS) {
            log("头顶按键 长按去抖丢弃（距上次动作 " + (now - lastFireHeadKeyMs) + "ms）");
            return;
        }
        lastFireHeadKeyMs = now;
        headKeyLongCount++;
        lastHeadKeyKind = "long";
        log("★★头顶按键 长按已判定（按住 " + heldMs + "ms ≥ " + HEADKEY_LONG_MS + "ms）");
        final HeadKeyListener l = headKeyListener;
        if (l == null) { log("头顶按键：没有监听者（HeadKey 未接线）"); return; }
        new Thread(new Runnable() {
            @Override public void run() {
                try { l.onHeadKeyLong(heldMs); } catch (Throwable t) {
                    log("头顶按键长按处理异常: " + t);
                }
            }
        }, "ledi-headkey-long").start();
    }

    // ================= IMU =================

    /**
     * 开关本机 IMU 采样（陀螺 + 加速度）。
     *
     * <p>屏保期间关掉（用户要求「屏保时陀螺停用」），退出屏保再打开。
     * 幂等：重复调用同一状态不会重复注册/注销。</p>
     */
    public synchronized void setImuEnabled(boolean on) {
        if (sm == null) return;
        try {
            if (!on) {
                if (!imuOn) return;
                sm.unregisterListener(this);
                imuOn = false;
                log("IMU 采样已停（屏保）");
                return;
            }
            if (imuOn) return;
            Sensor g = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            Sensor a = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (g != null) { sm.registerListener(this, g, SensorManager.SENSOR_DELAY_NORMAL); hasGyro = true; }
            if (a != null) { sm.registerListener(this, a, SensorManager.SENSOR_DELAY_NORMAL); hasAccel = true; }
            imuOn = true;
        } catch (Throwable t) {
            log("IMU 开关失败(" + on + "): " + t);
        }
    }

    public boolean imuOn() { return imuOn; }

    @Override public void onSensorChanged(SensorEvent e) {
        imuTs = System.currentTimeMillis();
        if (e.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            gyroX = e.values[0]; gyroY = e.values[1]; gyroZ = e.values[2];
        } else if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            accX = e.values[0]; accY = e.values[1]; accZ = e.values[2];
            // ★ v153：pitch/roll 的公式搬到 Attitude（单一事实源）——
            //   原来 DebugPad 的 3D 视图另写了一套旋转矩阵，两处对"哪个角绕哪根轴"
            //   的假设不一致，而且不会报错（详见 Attitude 类注释）。
            accMag = Attitude.magOf(accX, accY, accZ);
            magRing[magIdx] = accMag;
            magIdx = (magIdx + 1) % magRing.length;
            // ★ v156：pitch/roll 必须**排在 imuStateNow() 之前**。
            //   imuStateNow() 要用 pitch/roll 判断"机器人是不是躺着"（见那条 level 门控），
            //   而原来这两行在它后面 ⇒ 判据读的是**上一次采样**的角。
            //   在 200ms 采样下这个差通常看不出来，但它恰好出现在最需要准确的时刻：
            //   机器人刚躺下、角度正在快速变化的那两帧。这类"判据用了旧值"的错
            //   不会报错、也不会自己变好，只能靠顺序正确。
            pitch = Attitude.pitchOf(accX, accY, accZ);
            roll = Attitude.rollOf(accX, accY, accZ);
            imuState = imuStateNow();
        }
    }

    /**
     * ★★★ v154：IMU 读数到底能不能当姿态用 —— **分情况**判，不要只会说"需标定"。
     *
     * <p>为什么要分情况：倾斜实测（2026-09-24，静止）证明本机加速度计在**大倾角下
     * 本身就严重偏离 g**（侧躺 14.4 / 躺倒 18.4 / 趴下 3.5，而站立是 9.79）。
     * 这是硬件既有事实。若只看 |a| 就报"需标定"，用户会在机器人侧躺时去标定，
     * 而标定必须"摆正放在水平面上" —— 结果是**把好数据校坏**。
     *
     * <p>状态含义：
     * <ul>
     *   <li>{@code ok}      —— 静置 + MCU 说站立 + 加速度计自己说水平 + |a| ≈ g。读数可当姿态用。</li>
     *   <li>{@code off}     —— 同上，但 |a| 偏离 ⇒ 这才真的该重新标定。</li>
     *   <li>{@code tilted}  —— 姿态不是"站立" ⇒ 本机在这个角度 |a| 本来就会偏，
     *                          <b>此时 pitch/roll 也不可信</b>（这是已知限制，不是故障）。</li>
     *   <li>{@code moving}  —— 最近 0.7s 内 |a| 极差 &gt; 1.0 ⇒ 在动，任何判据都不成立。</li>
     *   <li>{@code warming} —— 采样还没攒够。</li>
     *   <li>{@code unknown} —— 没有加速度计。</li>
     * </ul>
     *
     * <p>★★ v156：{@code ok}/{@code off} 的门控从"一条"加到了"两条"
     * （{@code postureRaw == "stand"} <b>且</b> {@code |pitch|,|roll| < }{@link Attitude#LEVEL_DEG}）。
     * 起因是 v155 装机后立刻发现的：进程刚起来时还没收到原厂姿态广播，
     * {@code postureRaw} 停在默认值 {@code "stand"}（真机上 {@code /sensors} 的
     * {@code posture.ago} 显示 {@code "-"}）—— 这一刻若机器人正躺着，
     * "MCU 说站立"就是**假的**，单条门控会把倾斜误报成"该标定"。
     * 加第二条（让加速度计自己也表态"我是水平的"）之后，两条都成立才算故障：
     * MCU 的广播负责"机器人的姿态"，加速度计负责"这颗芯片此刻读得准不准"，互不替代。
     * <p>★ 反过来的误判也堵住了：标定坏掉的芯片会一边 |a| 偏离、一边报出虚假的大角度，
     * 于是第二条不成立 ⇒ 只会说 {@code tilted}。这是刻意的偏向 —— 宁可漏报一次"该标定"，
     * 也不要让人照着错判去标定（标定要求摆正水平，照做就是把好数据校坏）。
     */
    public String imuStateNow() {
        if (!hasAccel) return "unknown";
        int n = 0;
        float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
        for (int i = 0; i < magRing.length; i++) {
            float v = magRing[i];
            if (v <= 0f) continue;                  // 还没写过的槽位
            n++;
            if (v < mn) mn = v;
            if (v > mx) mx = v;
        }
        boolean warming = n < magRing.length;
        if (!warming) magSpread = mx - mn;
        // 判据本身在 Attitude.imuState（纯函数，/attitudetest 会把它的每一种输入结果钉住）。
        // 这里只负责把**本机此刻的字段**喂进去 —— 两侧各司其职。
        return Attitude.imuState(hasAccel, warming, magSpread, pitch, roll,
                accX, accY, accZ, "stand".equals(postureRaw));
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    // ================= 事件日志 =================

    private void log(String s) {
        synchronized (events) {
            events.addFirst((System.currentTimeMillis() / 1000) + "|" + s);
            while (events.size() > 30) events.removeLast();
        }
    }

    public void markExternal(String s) { log(s); }

    // ================= 输出 =================

    private static String ago(long ts) {
        if (ts <= 0) return "-";
        long s = (System.currentTimeMillis() - ts) / 1000;
        if (s < 60) return s + "秒前";
        if (s < 3600) return (s / 60) + "分钟前";
        return (s / 3600) + "小时前";
    }

    /** 完整状态 JSON（控制台 / API 用） */
    public String json() {
        try {
            JSONObject o = new JSONObject();
            JSONObject touch = new JSONObject();
            touch.put("last", lastTouch.isEmpty() ? JSONObject.NULL : lastTouch);
            touch.put("action", lastTouchAct.isEmpty() ? JSONObject.NULL : lastTouchAct);
            touch.put("ago", ago(lastTouchTs));
            touch.put("count", touchCount);
            touch.put("head_now", touchingHead);
            touch.put("lface_now", touchingLF);
            touch.put("rface_now", touchingRF);
            // 抹头顶（强制唤醒打断）的现场证据：摸过几次、多久之前摸的
            touch.put("pat_count", patCount);
            touch.put("pat_ago", lastPatAgo());
            // ★ v142 屏保闸 + 广播线程健康：排查"长按不灵"时先看这三项
            //   asleep_gate=true ⇒ 屏保中，感应事件本来就不该有动作（设计如此）；
            //   recv_ago 一直不涨 ⇒ 广播根本没到（原厂发不出来 / 被冻住）；
            //   dropped_events>0 ⇒ 监听者太慢把队列撑满（去看它在干什么）。
            touch.put("asleep_gate", asleepGate);
            touch.put("imu_on", imuOn);
            touch.put("recv_ago_ms", lastRecvMs == 0 ? -1
                    : System.currentTimeMillis() - lastRecvMs);
            touch.put("recv_last", lastRecvAction);
            touch.put("dropped_events", droppedEvents);
            o.put("touch", touch);

            JSONObject btn = new JSONObject();
            btn.put("last", lastButton.isEmpty() ? JSONObject.NULL : lastButton);
            btn.put("ago", ago(lastButtonTs));
            btn.put("count", buttonCount);
            // ★ 头顶键单独给一份**计数**：连按同一个键时 last 不变，只有计数会动，
            //   这是"按键真的送到我们 App 了"的唯一可判据。
            btn.put("head_key_count", headKeyCount);
            btn.put("head_key_ago", lastHeadKeyAgo());
            btn.put("head_key_held", headKeyHeld);
            // ★ v127 长短按要**分开计数**：只看一个总数无法区分"短按没生效"和
            //   "长按阈值判错了"，而这两件事的修法完全不同。
            btn.put("head_key_short", headKeyShortCount);
            btn.put("head_key_long", headKeyLongCount);
            btn.put("head_key_held_ms", lastHeadKeyHeldMs);
            btn.put("head_key_kind", lastHeadKeyKind.isEmpty() ? JSONObject.NULL : lastHeadKeyKind);
            btn.put("long_ms", HEADKEY_LONG_MS);
            o.put("button", btn);

            JSONObject pos = new JSONObject();
            pos.put("name", posture);
            pos.put("raw", postureRaw);
            pos.put("ago", ago(postureTs));
            // ★ v157：原厂 MCU **只在姿态变化时**发广播，进程重启后不会自发一条。
            //   于是刚起来时 posture 是"默认值站立"，而 ago 显示 "-" —— 光看 name 会以为
            //   这就是当前姿态，实际上这台机器可能正躺着（v156 那条误报的土壤）。
            //   heard=false 时表示"这条不是听来的，是默认值"，读的人才知道该怎么做。
            pos.put("heard", postureTs > 0);
            o.put("posture", pos);

            JSONObject bat = new JSONObject();
            bat.put("pct", battery);
            bat.put("charging", charging);
            bat.put("low", batteryLow);
            o.put("battery", bat);

            JSONObject imu = new JSONObject();
            imu.put("has_gyro", hasGyro);
            imu.put("has_accel", hasAccel);
            imu.put("gyro", new JSONArray().put(round(gyroX)).put(round(gyroY)).put(round(gyroZ)));
            imu.put("accel", new JSONArray().put(round(accX)).put(round(accY)).put(round(accZ)));
            imu.put("pitch", round(pitch));
            imu.put("roll", round(roll));
            // ★ v153/v154：|a| / imu_state / mag_spread。静止时 |a| 恒 ≈ 9.81（与朝向无关），
            //   偏离就是标定/偏置问题 —— 但**只有"站立"时这么判**，见 imuStateNow()。
            imu.put("mag", round(accMag));
            imu.put("mag_spread", round(magSpread));
            // 判据本身也要能被读到：否则"为什么说 tilted"只能翻源码猜。
            imu.put("level_deg", Attitude.LEVEL_DEG);
            imu.put("imu_state", imuState);
            imu.put("imu_hint", imuHint());
            imu.put("ago", ago(imuTs));
            o.put("imu", imu);

            JSONObject cali = new JSONObject();
            cali.put("state", caliState.isEmpty() ? JSONObject.NULL : caliState);
            cali.put("ago", ago(caliTs));
            o.put("cali", cali);

            JSONObject motor = new JSONObject();
            motor.put("engine", motion.hasEngine());
            motor.put("exclusive", motion.isExclusive());
            motor.put("last_action", motion.lastAction());
            motor.put("last_pose", motion.lastPose());
            motor.put("last_action_ago", ago(motion.lastActionTs()));
            o.put("motor", motor);

            JSONArray ev = new JSONArray();
            synchronized (events) { for (String s : events) ev.put(s); }
            o.put("events", ev);
            o.put("uptime_s", SystemClock.elapsedRealtime() / 1000);
            return o.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + J.esc(String.valueOf(e)) + "\"}";
        }
    }

    private static float round(float f) {
        return Math.round(f * 100f) / 100f;
    }

    /** 给 AI 看的一句话状态摘要（网关注入上下文用） */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("姿态=").append(posture);
        if (battery >= 0) sb.append("，电量=").append(battery).append('%').append(charging ? "(充电中)" : "");
        if (batteryLow) sb.append("，电量偏低");
        if (!lastTouch.isEmpty()) sb.append("，最近触摸=").append(zh(lastTouch)).append('(').append(ago(lastTouchTs)).append(')');
        if (touchingHead) sb.append("，头顶正被按住");
        if (hasAccel) sb.append(String.format("，倾斜 pitch=%.0f° roll=%.0f°", pitch, roll));
        String la = motion.lastAction();
        if (la != null && !la.isEmpty()) sb.append("，最近动作=").append(la).append('(').append(ago(motion.lastActionTs())).append(')');
        return sb.toString();
    }

    public static String zh(String zone) {
        if ("head".equals(zone)) return "头顶";
        if ("lface".equals(zone)) return "左脸";
        if ("rface".equals(zone)) return "右脸";
        return zone;
    }

    /** 供 /status 复用：蓝牙等静态信息 */
    static String btName() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            return a == null || a.getName() == null ? "" : a.getName();
        } catch (Exception e) {
            return "";
        }
    }
}
