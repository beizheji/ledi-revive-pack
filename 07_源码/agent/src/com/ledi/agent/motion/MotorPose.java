package com.ledi.agent.motion;

import com.ledi.agent.core.J;

import com.ledi.agent.core.BootLog;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 电机（舵机）姿态：<b>读回</b> + <b>控制</b>。
 *
 * <h3>读回 —— 原厂本来就有，只是没人接</h3>
 * 逆向 {@code xldrbt_v1.5.10.apk} 的 {@code xldbasesdk}（dexdump 逐条可验证）：
 * <pre>
 *   service/engine/thread/a            = GetEngStatusThread（读串口 InputStream）
 *   EngineService$EngBroadCastReciver  = 收广播、把状态摊成三个数组
 *     static int[] eng1_LastPoss / eng2_LastPoss / eng3_LastPoss   ← 三个电机【位置】
 *     static int[] eng1_Speeds   / eng2_Speeds   / eng3_Speeds     ← 速度
 *     static int   engStatusDataLength / byte[][] lastEngStatusDatas
 *   onReceive 里：
 *     action == "com.auric.robot.xldbasesdk.ACTION.ENGSTATUS"
 *     s = intent.getStringExtra("ENGSTATUS")
 *     b = d/a.a(s)                     // hex -> byte[]（去空格、转大写、2 字符 1 字节）
 *     eng1 = (b[3] & 0x0F) << 8 | (b[4] & 0xFF)
 *     eng2 = (b[7] & 0x0F) << 8 | (b[8] & 0xFF)
 *     eng3 = (b[11]& 0x0F) << 8 | (b[12]& 0xFF)
 * </pre>
 * 也就是说：<b>原厂 SDK 自己就在广播电机位置</b>，我们只要注册一个接收器。
 * （另一条同样可行的路是反射读上面那三个静态数组 —— 本类两条都做了，
 *  广播优先、字段兜底，见 {@link #refresh()}。）
 *
 * <h3>控制 —— 用公开 API，不碰私有字段</h3>
 * {@code EngineService.moveInterface(String actionText, int speed)} 的第 1 个参数
 * <b>直接就是动作文本</b>（不是文件名），内部会
 * {@code substring(start..end) -> split("]") -> split(",") -> angleToAutoInstuction(...)
 * -> Message -> EngineService.handler.sendMessageAtTime}。
 * 1 参数版 {@code moveInterface(String)} 才是"按名字读 /sdcard/xldBaseSDK/ 下的文件"。
 * ⇒ 滑条直控走 2 参数版，<b>不用写文件、不用自己拼 12 字节串口帧</b>。
 *
 * <h3>★ 两套数值不是一回事（别记成假账）</h3>
 * <ul>
 *   <li><b>读回</b>：12 位原始编码器计数（0..4095），单位不是"度"。</li>
 *   <li><b>下发</b>：{@code start[ms,j1,j2,j3]end} 里的 j1/j2/j3 是原厂角度域，
 *       范围来自对 80 个内置动作的统计：j1 -90..150、j2 -100..100、j3 -90..150。</li>
 * </ul>
 * 本项目因此<b>不</b>把"读回值"直接当"下发角度"：滑条用角度域下发，位置显示用原始计数，
 * 两者都如实标注单位和来源。★ 换算关系**已按真机实测拟合**（见下方 FIT_ZERO / FIT_CPD，
 * v147 上机实测），但那只是"把滑条摆到当前角度 + 面板显示个大概角度"够用 ——
 * 90° 以上可达十几度误差，**不许当控制回路**。
 */
public final class MotorPose {

    /** 原厂状态广播（逆向自 EngineService$EngBroadCastReciver.onReceive） */
    public static final String ACTION_ENGSTATUS = "com.auric.robot.xldbasesdk.ACTION.ENGSTATUS";
    public static final String ACTION_ENGSTOP   = "com.auric.robot.xldbasesdk.ACTION.ENGSTOP";
    public static final String EXTRA_ENGSTATUS  = "ENGSTATUS";

    static final String T_ENG  = "com.auric.intell.rbt.xldbasesdk.service.engine.EngineService";
    static final String T_RECV = "com.auric.intell.rbt.xldbasesdk.service.engine.EngineService$EngBroadCastReciver";

    /** 三个电机：位置字段名 / 速度字段名。顺序 = 下发文本里的 j1,j2,j3。 */
    static final String[][] F = {
            {"eng1_LastPoss", "eng1_Speeds"},
            {"eng2_LastPoss", "eng2_Speeds"},
            {"eng3_LastPoss", "eng3_Speeds"},
    };

    /** 给人看的名字。★ 编号到部位的对应关系【未上机确认】，只作显示用，不当结论。 */
    public static final String[] JOINT = {"电机1", "电机2", "电机3"};

    /** 滑条每次下发的单帧时长（ms）。短一点才跟手；太短电机会"嗡嗡"抖。 */
    public static final int SLIDE_MS = 140;

    /** 下发角度安全范围（= PoseController 对 80 个内置动作的统计结果） */
    static final int[] LO = {-90, -100, -90};
    static final int[] HI = {150,  100, 150};

    // ==================== 读回计数 ↔ 下发角度 的实测拟合 ====================
    /**
     * ★★ <b>2026-09-24 首次上机实测拟合</b>（v147，本机）。
     *
     * <p>怎么测的：动作文件 {@code /sdcard/xldBaseSDK/stStand} = {@code start[500,0,0,0]end}
     * ⇒ 站立就是角度 (0,0,0)，此刻读回 {@code raw=[370,420,371]} ⇒ 拿到三个零位；
     * 再用 {@code POST /motor} 逐点推已知角度，读回计数：
     * <pre>
     *  j1:   0°→370   30°→488   45°→547   60°→607   90°→723  120°→783  150°→844
     *  j2: -50°→248    0°→420   50°→586
     *  j3:   0°→371   30°→490   60°→608   90°→729  150°→845
     * </pre>
     *
     * <p><b>三条必须知道的性质</b>：
     * <ol>
     *   <li>j1 / j3 **高度对称**（30° 时 +118 / +119，150° 时同为 +474）—— 同一套减速比、镜像安装。</li>
     *   <li><b>0..90° 段是线性的</b>（斜率稳定 3.92~3.98 计数/度）；
     *       <b>90° 以上开始压缩</b>（j1 在 120°/150° 的等效斜率掉到 3.44 / 3.16）⇒
     *       高角度段用线性式会**低估**，要准就得查表。</li>
     *   <li>零点与斜率都是**这台机器装出来的**，换机器 / 拆装过舵机就作废 —— 那时要重测。</li>
     * </ol>
     * <p>用途仅限"把滑条摆到当前角度"和"面板上显示一个大概的角度"。
     * <b>不许拿它当控制回路</b>（本换算的误差在 90° 以上可达十几度）。
     */
    static final int[] FIT_ZERO = {370, 420, 371};
    static final float[] FIT_CPD = {3.92f, 3.38f, 3.98f};

    /**
     * 读回计数 → 角度（约数）。越界太多就返回 {@link Integer#MIN_VALUE} 表示"不可信"，
     * 让调用方自己去处理（显示 "—"、或不摆滑条），**不要**硬给一个看着像真的数。
     */
    public static int angleOf(int raw, int axis) {
        if (raw < 0 || axis < 0 || axis > 2) return Integer.MIN_VALUE;
        float a = (raw - FIT_ZERO[axis]) / FIT_CPD[axis];
        if (a < LO[axis] - 40 || a > HI[axis] + 40) return Integer.MIN_VALUE;
        return Math.round(a);
    }

    /** 读回值的原始范围（12 位） */
    public static final int RAW_MAX = 4095;

    /** 下发角度域的可读副本（DebugPad 的滑条要用；LO/HI 本身是包内字段）。 */
    public static int[] lo() { return new int[] {LO[0], LO[1], LO[2]}; }
    public static int[] hi() { return new int[] {HI[0], HI[1], HI[2]}; }

    private final Context ctx;
    private volatile Object engine;         // EngineService 实例（AgentService 绑定后注入）
    private volatile PoseController pose;   // 回退通道用（写姿态文件 + 按名执行）
    private volatile Runnable onChange;     // 位置更新回调（DebugPad 刷新用）

    private final int[] pos = {-1, -1, -1}; // 最近一次读到的位置（原始计数）
    private volatile String src = "none";   // bcast | field | none
    private volatile long ts = 0L;
    private volatile int frames = 0;        // 累计收到的状态帧数
    /**
     * 累计丢掉多少次"全 0"读数（广播帧 + 反射兜底都算）。
     * <p>★ v149：这个计数存在的唯一理由是**让原本静默的事件可见** ——
     * 见 {@link #allZero}。没有它，"这次开机有没有读到过假 0"只能靠猜；
     * 有了它，冷启动后 GET /motorpos 一眼就能看出这道闸到底有没有真的拦到东西。
     */
    private volatile int zeroDropped = 0;
    private volatile int len = 0;           // engStatusDataLength（原厂状态数组长度）
    private volatile String err = "";
    private volatile boolean listening = false;
    /** 屏保中：不监听、不返回位置（见 {@link #setSleeping}）。 */
    private volatile boolean sleeping = false;
    private BroadcastReceiver recv;

    /** 最近一次下发的姿态（如实记录，不代表电机真的到了） */
    private volatile String lastCmd = "";
    private volatile long lastCmdTs = 0L;
    private volatile long lastCmdMs = 0L;
    private volatile String lastCmdVia = "";

    // ---- 「解除锁定」（释放舵机力矩）的现场记录，见 {@link #release()} ----
    private volatile String lastReleaseVia = "";
    private volatile String lastReleaseErr = "";
    private volatile long lastReleaseTs = 0L;
    private volatile int[] lastReleaseBefore = null;
    private volatile int[] lastReleaseAfter = null;

    public MotorPose(Context ctx) { this.ctx = ctx; }

    public void setEngine(Object e) { this.engine = e; }

    /** 回退通道：写姿态文件 + 按名执行。AgentService 在建好 router 之后注入。 */
    public void setPose(PoseController p) { this.pose = p; }

    public boolean hasEngine() { return engine != null; }

    public void setOnChange(Runnable r) { this.onChange = r; }

    // ==================== 熄屏（屏保）闸 ====================

    /**
     * ★ 用户要求（2026-09-24）：「<b>进入熄屏则停止返回 api</b>」。
     *
     * <p>做三件事，缺一不可：
     * <ol>
     *   <li><b>摘掉广播接收器</b> —— 原厂状态帧是几百毫秒一帧的常驻广播。屏保期间留着它，
     *       等于让一条没人看的读回链路持续唤醒 CPU（见 MEMORY 里
     *       「软件屏保省电只能靠少唤醒线程」那条）。</li>
     *   <li><b>置位 sleeping</b> —— {@link #positionJson()} 直接短路，不再吐任何位置数据。</li>
     *   <li><b>醒回来要重新注册 + 立刻 refresh()</b> —— 只置位不重注册，醒来后位置会永远
     *       停在屏保前的旧值；界面照样显示三个数字，看着"活着"其实是死的 ——
     *       这正是本项目最怕的假绿灯。</li>
     * </ol>
     *
     * <p>闸有<b>两层</b>：{@code CommandRouter} 的屏保总闸会让 {@code /motor*} 全部返回
     * {@code {"error":"sleeping"}}（第一层）；本方法是第二层，防止有人日后把
     * {@code /motorpos} 加进总闸白名单 —— "只读不吵"看着很无辜，但读本身就是唤醒。
     */
    public synchronized void setSleeping(boolean on) {
        if (on == sleeping) return;
        sleeping = on;
        if (on) {
            stop();
            BootLog.i("电机读回 屏保中 => 摘监听 + 停返回 API");
        } else {
            start();
            try { refresh(); } catch (Throwable ignored) {}
            BootLog.i("电机读回 恢复 => src=" + src
                    + " pos=" + pos[0] + "," + pos[1] + "," + pos[2]);
        }
    }

    public boolean isSleeping() { return sleeping; }

    // ==================== 读回 ====================

    public void start() {
        if (listening) return;
        recv = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent it) {
                try {
                    String a = it == null ? null : it.getAction();
                    if (ACTION_ENGSTATUS.equals(a)) {
                        frames++;
                        int[] v = parse(it.getStringExtra(EXTRA_ENGSTATUS));
                        if (v != null && allZero(v)) {
                            // 见 allZero 的注释：三路同时为 0 = 还没写到真值，不许当真。
                            zeroDropped++;
                            if (err.isEmpty()) err = "bcast: 收到全 0 帧（当没数据，未覆盖上次好值）";
                        } else if (v != null) {
                            pos[0] = v[0]; pos[1] = v[1]; pos[2] = v[2];
                            src = "bcast";
                            ts = System.currentTimeMillis();
                            fire();
                        } else if (err.isEmpty()) {
                            err = "bcast: 解析不出位置（extra 不是 hex？）";
                        }
                    } else if (ACTION_ENGSTOP.equals(a)) {
                        // 原厂停的时候会把数组整体左移、速度清零（位置保留）。
                        // 我们只更新时间戳，不当成"位置变了"。
                        ts = System.currentTimeMillis();
                        src = "bcast/stop";
                        fire();
                    }
                } catch (Throwable t) {
                    err = "recv: " + t;
                }
            }
        };
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(ACTION_ENGSTATUS);
            f.addAction(ACTION_ENGSTOP);
            ctx.registerReceiver(recv, f);
            listening = true;
            BootLog.i("MotorPose: 已监听 " + ACTION_ENGSTATUS);
        } catch (Throwable t) {
            err = "reg: " + t;
            BootLog.w("MotorPose 注册监听失败: " + t);
        }
    }

    public void stop() {
        if (!listening || recv == null) return;
        try { ctx.unregisterReceiver(recv); } catch (Throwable ignored) {}
        listening = false;
    }

    /**
     * ★★ v149：<b>三路同时为 0 一律当"没有数据"</b>，不许当真。
     *
     * <h3>为什么（真机抓到的假绿灯）</h3>
     * 反射原厂那两个静态数组时，如果原厂那侧<b>还没写过数组</b>（刚开机、EngineService 刚绑定），
     * 读到的就是全新的 {@code int[]} = {@code [0,0,0]}。老代码只看 {@code instanceof int[]}
     * 和 {@code length > 0} ⇒ {@code got==3} ⇒ 判定"读到了" ⇒ {@code src="field"}、
     * {@code pos=[0,0,0]}。于是：
     * <pre>
     *   /motorpos  →  raw:[0,0,0]
     *   面板显示    →  "0 (-94°)"   ← 看着像个真角度，其实是"没数据"
     * </pre>
     * 这正是本项目最忌讳的那种判据：<b>它不报错，它报一个数</b>。
     *
     * <p>物理上也站得住：这台机器站立时三路读回是 <b>370 / 420 / 371</b>
     * （见 {@link #FIT_ZERO}），三路同时为 0 等于三个舵机都被拧到机械极限 —— 不可能出现。
     * <p>★ 唯一需要回头的条件：真机将来**确实**观察到三路同时 0 的合法读数。届时改成
     * 按轴判（只丢全 0 的那一轴）或加时间窗，不要直接删掉这道闸。
     */
    static boolean allZero(int[] v) {
        return v != null && v[0] == 0 && v[1] == 0 && v[2] == 0;
    }

    /**
     * 兜底读法：直接反射原厂那两个静态数组。
     * <p>为什么还要它：广播是"原厂发、我们收"，一旦 EngineService 那侧还没起来
     * （或它把接收器摘了），广播就没有；而静态字段只要类被加载过就在。
     * ★ 取 index 0：onReceive 的 ENGSTOP 分支是 {@code arr[i] = arr[i-1]}（i 从后往前），
     * 即"整体后移"，说明<b>下标 0 = 最新</b>。
     */
    public String refresh() {
        Class<?> rc = null;
        try { rc = Class.forName(T_RECV); } catch (Throwable t) { return "no class"; }
        int[] got = new int[3];
        int n = 0;
        for (int i = 0; i < 3; i++) {
            Object v = readStatic(rc, F[i][0]);
            if (v instanceof int[] && ((int[]) v).length > 0) {
                got[i] = ((int[]) v)[0];
                n++;
            }
        }
        Object l = readStatic(rc, "engStatusDataLength");
        if (l instanceof Integer) len = (Integer) l;
        if (n == 3) {
            if (allZero(got)) {
                // ★ 见 allZero：数组在、但还没写进真值。**不设 src、不动 pos** ——
                //   宁可让界面显示 "—"，也不能显示一个 0。
                zeroDropped++;
                return "field 全 0（原厂还没写过，当没数据）";
            }
            pos[0] = got[0]; pos[1] = got[1]; pos[2] = got[2];
            src = "field";
            ts = System.currentTimeMillis();
            fire();
            return "field ok";
        }
        return "field 只读到 " + n + "/3";
    }

    private static Object readStatic(Class<?> c, String name) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(null);
            } catch (NoSuchFieldException e) {
                continue;
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /**
     * 状态串最少要有多少字节才够解析 —— 三个 12 位值分别落在 b[3]/b[4]、b[7]/b[8]、
     * b[11]/b[12]，所以至少 13 字节。
     * <p>★ v149：提成常量是因为 {@link #HEX_PROBE} 那个自检探针曾经比这道门禁还短
     * （22 字符 = 11 字节 &lt; 13）⇒ 自检恒为 false。写探针的地方直接引用这个名字，
     * 就不会再"探针自己没过自己的门"。
     */
    public static final int MIN_STATUS_BYTES = 13;

    /**
     * 解原厂的状态字符串。
     * <p>格式（逐条对照 {@code d/a.a(String)} 的字节码）：
     * {@code s.replaceAll(" ","").toUpperCase()} 后每 2 个字符 1 字节，
     * 高半字节在前，字符表 {@code "0123456789ABCDEF"}，非表内字符得 -1。
     * <p>三个 12 位值在字节流里的位置 = {@code (b[3]&0x0F)<<8|b[4]}、
     * {@code (b[7]&0x0F)<<8|b[8]}、{@code (b[11]&0x0F)<<8|b[12]}。
     */
    public static int[] parse(String s) {
        if (s == null) return null;
        String hex = s.replaceAll(" ", "").toUpperCase();
        if (hex.isEmpty()) return null;
        int n = hex.length() / 2;
        if (n < MIN_STATUS_BYTES) return null;   // 至少要够到 b[12]
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            int hi = "0123456789ABCDEF".indexOf(hex.charAt(i * 2));
            int lo = "0123456789ABCDEF".indexOf(hex.charAt(i * 2 + 1));
            if (hi < 0 || lo < 0) return null;   // 原厂这里会算出 -1 的脏值，我们直接拒收
            b[i] = (byte) ((hi << 4) | lo);
        }
        return new int[] {
                ((b[3] & 0x0F) << 8) | (b[4] & 0xFF),
                ((b[7] & 0x0F) << 8) | (b[8] & 0xFF),
                ((b[11] & 0x0F) << 8) | (b[12] & 0xFF),
        };
    }

    private void fire() {
        Runnable r = onChange;
        if (r == null) return;
        try { r.run(); } catch (Throwable ignored) {}
    }

    /** 当前三路位置（原始计数；-1 = 还没读到） */
    public int[] pos() { return new int[] {pos[0], pos[1], pos[2]}; }

    public long ts() { return ts; }
    public String src() { return src; }
    public int frames() { return frames; }
    public int rawLen() { return len; }

    // ==================== 控制 ====================

    /**
     * 让三个电机转到指定角度（原厂角度域）。
     * <p>参数会被夹到安全范围；任一路越界只夹不报错（滑条是连续的，报错反而没法用），
     * 但返回值里带 {@code clipped:true} 如实说明。
     *
     * @return JSON：成功 {@code {"ok":1,...}}；失败 {@code {"ok":0,"err":...}}
     */
    public String drive(int j1, int j2, int j3, int ms) {
        if (sleeping) {
            // 第二层闸：屏保中不下发（第一层是 CommandRouter 的屏保总闸）。
            return "{\"ok\":0,\"via\":\"none\",\"err\":\"sleeping\"}";
        }
        int[] v = {j1, j2, j3};
        boolean clipped = false;
        for (int i = 0; i < 3; i++) {
            if (v[i] < LO[i]) { v[i] = LO[i]; clipped = true; }
            if (v[i] > HI[i]) { v[i] = HI[i]; clipped = true; }
        }
        if (ms <= 0 || ms > 5000) ms = SLIDE_MS;

        String text = "start[" + ms + "," + v[0] + "," + v[1] + "," + v[2] + "]end";
        long t0 = System.currentTimeMillis();
        String via = "none";
        String err2 = "";

        if (engine != null) {
            // ① 首选：moveInterface(actionText, speed) —— 公开 API，内部自己拼帧并投递给 handler
            try {
                Method m = engine.getClass().getMethod("moveInterface", String.class, Integer.TYPE);
                m.invoke(engine, text, 100);
                via = "moveInterface(text,int)";
            } catch (NoSuchMethodException e) {
                err2 = "无 moveInterface(String,int)";
            } catch (Throwable t) {
                err2 = "moveInterface 抛: " + t.getClass().getSimpleName()
                        + (t.getCause() != null ? "/" + t.getCause().getClass().getSimpleName() : "");
            }
        } else {
            err2 = "engine==null（原厂引擎还没绑上）";
        }

        if ("none".equals(via)) {
            // ② 回退：写姿态文件 + 按名字执行（PoseController 这条链在本项目已跑通）
            PoseController pc = pose;
            if (pc == null) {
                err2 = err2 + " / 无回退通道（pose==null）";
            } else {
                try {
                    String r = pc.define("slide", text, true);
                    if (r != null && r.startsWith("OK")) via = "posefile";
                    else err2 = err2 + " / 回退失败: " + r;
                } catch (Throwable t) {
                    err2 = err2 + " / 回退异常: " + t;
                }
            }
        }

        lastCmd = text;
        lastCmdTs = System.currentTimeMillis();
        lastCmdMs = lastCmdTs - t0;
        lastCmdVia = via;

        StringBuilder sb = new StringBuilder("{\"ok\":");
        sb.append("none".equals(via) ? "0" : "1");
        sb.append(",\"via\":\"").append(J.esc(via)).append("\"");
        sb.append(",\"cmd\":\"").append(J.esc(text)).append("\"");
        sb.append(",\"clipped\":").append(clipped);
        sb.append(",\"ms\":").append(lastCmdMs);
        if (!err2.isEmpty()) sb.append(",\"detail\":\"").append(J.esc(err2)).append("\"");
        return sb.append("}").toString();
    }

    // ==================== 锁定 / 解除锁定（释放舵机力矩）====================

    /**
     * ★★★ v153：把三个舵机的**力矩释放掉**，让手臂能被手掰动。
     *
     * <h3>为什么是这个方法（原厂 dex 逐条可验证）</h3>
     * 原厂 {@code com.auric.intell.rbt.xldbasesdk.service.engine.EngineService} 有一个
     * <b>public</b> 方法（原厂拼错了字母，是 {@code realease} 不是 {@code release}）：
     * <pre>
     *   realeaseControl_All:()V          // v1510.dump.txt 里 method@60f1，insns size=57
     * </pre>
     * 它的实现（逐条指令，非推测）：
     * <pre>
     *   Message m1; m1.what = 4098(0x1002); handler.sendMessageDelayed(m1, 50);
     *   Message m2; m2.what = 4097(0x1001); m2.obj = c.a.f.a.a();   // ← 释放指令字节
     *                handler.sendMessageDelayed(m2, 50);
     *   Message m3; m3.what = 4099(0x1003); handler.sendMessageDelayed(m3, 50);
     * </pre>
     * 而 {@code c.a.f.<clinit>} 里（<b>看 sput 那一行，不是看字段声明顺序</b>）：
     * <pre>
     *   0x0007: sput-object f.a = b("FF FF FE FF FF FF FF FF FF 07")   ← releaseIns
     *   0x0010: sput-object f.b = b("FF FF FE FF FE FF FE FF FE 0A")   ← queeryIns
     * </pre>
     * 同一串帧在 TV 包里的名字叫 {@code IS_RELEASECONTRAL_ALL}
     * ⇒ {@code realeaseControl_All()} 就是"释放全部舵机力矩"，不是猜测。
     *
     * <h3>原厂自己什么时候用它</h3>
     * {@code EngineService$1.handleMessage} 在收到 {@code FEEDBACK_LEFT_ARM_WREST} /
     * {@code FEEDBACK_RIGHT_ARM_WREST}（掰手腕：人把手臂掰到某个位置后被判定"松手"）时，
     * 先 {@code access$902(true)}、再调 {@code realeaseControl_All()}、并打日志
     * {@code "relaseCONTROL_"}。也就是说这是原厂语义里的"松手"操作。
     *
     * <h3>⚠ 这一条还没验到的部分（如实说）</h3>
     * <ul>
     *   <li><b>力矩到底松没松，最终只有"用手掰一下"才能裁决。</b>本方法只能给出
     *       「调用没抛异常」+「释放前后读回差值」两个间接证据，不能替代手感。</li>
     *   <li>释放之后原厂引擎会不会在下一个动作里自动重新上力，<b>本机尚未验证</b>。
     *       {@code isLocked} 标志位会被本方法读出来，可以当观察点。</li>
     * </ul>
     */
    public String release() {
        if (sleeping) {
            // 与 /motor* 同一口径：屏保中不放行（第一层闸在 CommandRouter，这里是第二层）。
            return "{\"ok\":0,\"via\":\"none\",\"err\":\"sleeping\"}";
        }
        int[] before = pos();
        long t0 = System.currentTimeMillis();
        String via = "none";
        String err2 = "";

        if (engine == null) {
            err2 = "engine==null（原厂引擎还没绑上）";
        } else {
            try {
                // 原厂方法名就是这个拼写（realease），不要"顺手纠正"成 release。
                Method m = engine.getClass().getMethod("realeaseControl_All");
                m.invoke(engine);
                via = "realeaseControl_All()";
            } catch (NoSuchMethodException e) {
                err2 = "无 realeaseControl_All()（原厂包版本对不上？）";
            } catch (Throwable t) {
                err2 = "调用抛: " + t.getClass().getSimpleName()
                        + (t.getCause() != null ? "/" + t.getCause().getClass().getSimpleName() : "");
            }
        }

        lastReleaseVia = via;
        lastReleaseErr = err2;
        lastReleaseTs = t0;
        lastReleaseBefore = before;
        lastReleaseAfter = null;

        final boolean sent = !"none".equals(via);
        if (sent) {
            // 释放是异步的（原厂自己也是 sendMessageDelayed 三步）。
            // 起一条短命线程在 1.5s 后再读一次，把"位置有没有自己在动"记下来 ——
            // 手臂若有下垂，这一读会看到计数变化；没有变化**不能**证明力矩没松
            // （可能本来就停在重心位置），这一点写在返回值里，不许含糊。
            new Thread(new Runnable() {
                @Override public void run() {
                    try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                    try {
                        refresh();
                        lastReleaseAfter = pos();
                        fire();
                    } catch (Throwable ignored) {}
                }
            }, "ledi-motor-release").start();
        }

        StringBuilder sb = new StringBuilder("{\"ok\":");
        sb.append(sent ? "1" : "0");
        sb.append(",\"via\":\"").append(J.esc(via)).append("\"");
        sb.append(",\"before\":[").append(before[0]).append(",").append(before[1])
          .append(",").append(before[2]).append("]");
        sb.append(",\"flags\":").append(flagsJson());
        if (!err2.isEmpty()) sb.append(",\"detail\":\"").append(J.esc(err2)).append("\"");
        sb.append(",\"note\":\"力矩是否真的松了要用手掰一下才算验过；"
                + "1.5s 后 GET /motorrelease 看 after/delta（手臂下垂会看到计数变化，"
                + "没变化不等于没松）\"");
        return sb.append("}").toString();
    }

    /** 释放操作的结果（含 1.5s 后的补采）。 */
    public String releaseJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"via\":\"").append(J.esc(lastReleaseVia)).append("\"");
        sb.append(",\"ago_ms\":").append(lastReleaseTs == 0 ? -1
                : System.currentTimeMillis() - lastReleaseTs);
        sb.append(",\"before\":").append(arr(lastReleaseBefore));
        sb.append(",\"after\":").append(lastReleaseAfter == null ? "null" : arr(lastReleaseAfter));
        int[] b = lastReleaseBefore, a = lastReleaseAfter;
        if (b != null && a != null) {
            int d = Math.max(Math.abs(a[0] - b[0]),
                    Math.max(Math.abs(a[1] - b[1]), Math.abs(a[2] - b[2])));
            sb.append(",\"delta_max\":").append(d);
            sb.append(",\"moved\":").append(d > 3);
        } else {
            sb.append(",\"delta_max\":null,\"moved\":null");
        }
        sb.append(",\"flags\":").append(flagsJson());
        if (!lastReleaseErr.isEmpty())
            sb.append(",\"detail\":\"").append(J.esc(lastReleaseErr)).append("\"");
        return sb.append("}").toString();
    }

    private static String arr(int[] v) {
        if (v == null) return "null";
        return "[" + v[0] + "," + v[1] + "," + v[2] + "]";
    }

    /**
     * 读原厂引擎的锁定/受控标志 —— 用来观察"释放"有没有真的改变引擎状态。
     * <p>{@code isLocked} 是**实例**字段、{@code isControled} 是**静态**字段
     * （依据 {@code EngineService.<clinit>} 用 sput 写 isControled、而 {@code $1.handleMessage}
     *  用 iput 写 isLocked）。读不到就如实写 null，不要编一个 false。
     */
    public String flagsJson() {
        Object locked = null, controled = null, paused = null;
        try {
            if (engine != null) {
                locked = readField(engine, "isLocked");
                paused = readField(engine, "engPause");
            }
            Class<?> c = Class.forName(T_ENG);
            controled = readStatic(c, "isControled");
        } catch (Throwable ignored) {}
        return "{\"isLocked\":" + (locked == null ? "null" : locked.toString())
                + ",\"isControled\":" + (controled == null ? "null" : controled.toString())
                + ",\"engPause\":" + (paused == null ? "null" : paused.toString()) + "}";
    }

    /** 读实例字段（含父类）。读不到返回 null —— 调用方自己决定怎么表述。 */
    private static Object readField(Object target, String name) {
        for (Class<?> k = target.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                continue;
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    // ==================== 输出 ====================

    /** 只要位置（给 UI 高频轮询用，够小）。★ 屏保中直接闭嘴，不返回任何位置数据。 */
    public String positionJson() {
        if (sleeping) {
            return "{\"sleeping\":true,\"api\":\"paused\",\"src\":\"none\""
                    + ",\"hint\":\"屏保中不返回电机位置；先 POST /sleep {\\\"on\\\":false} 叫醒\"}";
        }
        StringBuilder sb = new StringBuilder("{\"raw\":[");
        sb.append(pos[0]).append(",").append(pos[1]).append(",").append(pos[2]).append("]");
        sb.append(",\"pct\":[");
        for (int i = 0; i < 3; i++) {
            if (i > 0) sb.append(",");
            sb.append(pos[i] < 0 ? -1 : Math.round(pos[i] * 100f / RAW_MAX));
        }
        // ★ v222 补：换算成角度一并给出。读回本身是 12 位编码器计数，"角度"是**按实测拟合
        //   换算**出来的（见 FIT_ZERO / FIT_CPD）。不可信的那一轴给 null —— 绝不给一个
        //   看着像真的假数（这正是本类 allZero 那条注释的同一口径）。
        sb.append("],\"ang\":[");
        for (int i = 0; i < 3; i++) {
            if (i > 0) sb.append(",");
            int a = pos[i] < 0 ? Integer.MIN_VALUE : angleOf(pos[i], i);
            if (a == Integer.MIN_VALUE) sb.append("null"); else sb.append(a);
        }
        sb.append("],\"names\":[");
        for (int i = 0; i < 3; i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(J.esc(JOINT[i])).append("\"");
        }
        sb.append("],\"src\":\"").append(J.esc(src)).append("\"");
        sb.append(",\"ago_ms\":").append(ts == 0 ? -1 : System.currentTimeMillis() - ts);
        sb.append(",\"frames\":").append(frames);
        sb.append(",\"zero_dropped\":").append(zeroDropped);
        sb.append(",\"arr_len\":").append(len);
        sb.append(",\"last_cmd\":\"").append(J.esc(lastCmd)).append("\"");
        sb.append(",\"last_cmd_via\":\"").append(J.esc(lastCmdVia)).append("\"");
        sb.append(",\"last_cmd_ago_ms\":")
          .append(lastCmdTs == 0 ? -1 : System.currentTimeMillis() - lastCmdTs);
        sb.append(",\"last_cmd_ms\":").append(lastCmdMs);
        sb.append(",\"engine\":").append(engine != null);
        if (!err.isEmpty()) sb.append(",\"err\":\"").append(J.esc(err)).append("\"");
        return sb.append("}").toString();
    }

    /**
     * 自检用的**已知答案**状态串：14 字节，故意把三个 12 位值放在 0x780 / 0x790 / 0xBAB。
     * <pre>
     *   b[3]=0x07 b[4]=0x80 → (0x07&lt;&lt;8)|0x80 = 1920
     *   b[7]=0x07 b[8]=0x90 → 1936
     *   b[11]=0x0B b[12]=0xAB → 2987
     * </pre>
     * ★★ 长度必须 <b>≥ 26 个字符（13 字节）</b> —— 见下面的血泪注释。
     */
    static final String HEX_PROBE = "010203078005060790090A0BABCD";
    static final int[]  HEX_PROBE_WANT = {1920, 1936, 2987};

    /**
     * 一次自检：把"读回这条路到底通不通"摊开说清楚，别让 UI 上的三条 -1 变成假绿灯。
     *
     * <h3>★★★ v149：这里原来有个**永久性假警报**，必须记一笔</h3>
     * 老代码是 {@code parse("FFFFF00078007800780078") != null} —— 那个串只有
     * <b>22 个字符 ⇒ 11 字节</b>，而 {@link #parse} 开头的门禁是 {@code if (n < 13) return null;}
     * （要够得着 {@code b[12]}）。于是这个"自检"**无论链路多好都恒返回 false**。
     * 更糟的是它长得像"解析器坏了"，而不是像"我的探针写短了" ——
     * 真机上一眼看过去只会去查解析器，白花半天。
     *
     * <p>所以现在两件事一起做：<b>① 探针加长到 14 字节</b>（跨过自己设的那道门禁）；
     * <b>② 不只报 true/false，把解出来的三个数一并报出来</b> ——
     * {@code true} 只说明"没返回 null"，看不出"解错了但没解崩"，那还是假绿灯。
     */
    public String diag() {
        StringBuilder sb = new StringBuilder("{");
        boolean cls = false;
        try { Class.forName(T_RECV); cls = true; } catch (Throwable ignored) {}
        sb.append("\"recv_class\":").append(cls);
        sb.append(",\"listening\":").append(listening);
        sb.append(",\"bcast_action\":\"").append(ACTION_ENGSTATUS).append("\"");
        int[] probe = parse(HEX_PROBE);
        boolean hexOk = probe != null
                && probe[0] == HEX_PROBE_WANT[0]
                && probe[1] == HEX_PROBE_WANT[1]
                && probe[2] == HEX_PROBE_WANT[2];
        sb.append(",\"hex_ok\":").append(hexOk);
        sb.append(",\"hex_got\":[");
        for (int i = 0; i < 3; i++) {
            if (i > 0) sb.append(",");
            sb.append(probe == null ? -1 : probe[i]);
        }
        sb.append("],\"hex_want\":[").append(HEX_PROBE_WANT[0]).append(",")
          .append(HEX_PROBE_WANT[1]).append(",").append(HEX_PROBE_WANT[2]).append("]");
        sb.append(",\"field_probe\":\"").append(J.esc(refresh())).append("\"");
        sb.append(",\"engine\":").append(engine != null);
        sb.append(",\"pos\":").append(positionJson());
        return sb.append("}").toString();
    }
}
