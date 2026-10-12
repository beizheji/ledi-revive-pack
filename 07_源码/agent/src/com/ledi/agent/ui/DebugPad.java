package com.ledi.agent.ui;

import com.ledi.agent.motion.Attitude;
import com.ledi.agent.motion.MotorPose;
import com.ledi.agent.sense.SensorHub;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * 调试控件组 —— <b>v148 起并入 {@link LogOverlay} 的面板，自己不再是浮层</b>。
 *
 * <h3>为什么改（v148）</h3>
 * v147 是两个独立浮层（log 在左下、本面板在右上），用户可以同时看见它们互相压着，
 * 且面板底部在 800×480 上会被截断。用户要求：<b>三块内容一起整合到 log 界面（小绿点）</b>。
 * 于是这里只剩「造控件 + 刷新」，窗口的挂载/位置/类型全部交给 {@link LogOverlay}。
 *
 * <h3>为什么又拆成两块（v151）</h3>
 * v148 的整合是**单列竖排**（日志在上、控件在下）—— 真机一量，窗口被钳在
 * {@code [0,0][520,480]}，内容比 480 高 ⇒ <b>底部三条滑条整块被裁掉</b>，
 * 用户报的"截断"根本没解决。用户随后给了排版图，是**两列**：
 * <pre>
 *   ┌───────────────────────┬──────────────┐
 *   │ 触摸（左脸/右脸/头顶） │ 电机滑条 x3  │
 *   │                       │ 动作控制和电机角度
 *   │                       │ 陀螺仪 3D    │
 *   │ LOG（吃掉剩余高度）    │              │
 *   └───────────────────────┴──────────────┘
 * </pre>
 * 两列各自独立排，整块高度由**较高那一列**决定，不再线性叠加 ⇒ 480 装得下。
 * 所以本类对外只剩两个"造块"入口：{@link #buildTouchBlock} 与 {@link #buildControlBlock}，
 * 谁摆哪儿由 {@link LogOverlay} 决定。
 *
 * <h3>v152 压缩（用户 09-24：「为 log 留出足够大的位置」）</h3>
 * 触摸块 {@code 80dp → 46dp}、滑条行定高 {@code 30dp}、姿态 3D {@code 96dp → 80dp}；
 * 面板里那条"功能开关"和右上那个"● 收起"都按用户要求删掉了。
 * ⚠ 日志在**左列**，所以只有压左列（触摸块）才真的给日志让出高度。
 *
 * <h3>三条铁律</h3>
 * <ul>
 *   <li><b>本类的任何方法都不许写日志</b>：{@link #update} 每 200ms 跑一次，写日志就是自己喂自己。</li>
 *   <li><b>不能用 {@code setMin}</b>：Android 5.1 的 SeekBar 没有这个方法，用 max=跨度、监听里偏回去。</li>
 *   <li><b>自动对齐只许碰"用户没碰过"的滑条</b>：见 {@link #syncBars(boolean)} ——
 *       否则读数会一路盖掉用户正在拖的值，手感像"滑条自己被抢走"。</li>
 * </ul>
 */
public final class DebugPad {

    /** 滑条下发节流：一次拖动会发几十次 onProgressChanged，不节流会把原厂动作队列冲垮。 */
    private static final long SEND_GAP_MS = 120;

    private static Context app;
    private static SensorHub sensor;
    private static MotorPose motor;

    private static LinearLayout rootCol;
    private static Gyro3DView gyroView;
    private static TextView tvPitchRoll, tvTouchCount;
    /** 「解除锁定」按钮（v153）。它的文字会被 update() 临时改成结果，3 秒后自己变回来。 */
    private static TextView tvRelease;
    private static final String RELEASE_LABEL = "解除锁定";
    private static volatile long releaseFlashUntil = 0L;
    private static volatile String releaseFlashText = "";
    private static final TextView[] tvMotorVal = new TextView[3];
    private static final SeekBar[] bars = new SeekBar[3];
    private static final View[] touchBox = new View[3];   // 0=左脸 1=右脸 2=头顶

    private static long lastSend = 0L;
    private static volatile float yaw = 0f;
    /** 用户正在拖哪条滑条（-1 = 没在拖）。拖动期间不让读数覆盖滑条位置。 */
    private static volatile int dragging = -1;

    // ---- 姿态零位（相对标定，见 zeroImu）----
    private static final String K_ZP = "imu_zero_pitch";
    private static final String K_ZR = "imu_zero_roll";
    private static float zeroPitch = 0f, zeroRoll = 0f;
    /**
     * 哪几条滑条**用户已经亲手碰过**。
     * <p>见 {@link #syncBars(boolean)}：没碰过的滑条会一直跟着读回值走（面板刚开、
     * 读数还没来的时候尤其重要）；碰过之后就交给用户，读数不再去盖他的输入。
     */
    private static final boolean[] touched = new boolean[3];

    private DebugPad() {}

    public static void bind(SensorHub s, MotorPose m) { sensor = s; motor = m; }

    /**
     * 造【左列】的"触摸"块（<b>必须主线程</b>调用）。
     * <p>用户 09-24 的要求：就是原来那三个方块（左脸/右脸/头顶），但**放大**。
     */
    public static View buildTouchBlock(Context ctx) {
        initCtx(ctx);
        for (int i = 0; i < 3; i++) touched[i] = false;
        return buildTouch();
    }

    /**
     * 造【右列】的控制块：小绿点 / 三条电机滑条 / 陀螺仪 3D（<b>必须主线程</b>调用）。
     * <p>面板每次展开都会重建（{@link LogOverlay#show()} 里调用），所以这里同时承担
     * 「把滑条摆到当前实际位置」的初始化 —— 见 {@link #syncBars(boolean)}。
     */
    public static View buildControlBlock(Context ctx) {
        initCtx(ctx);
        LinearLayout col = new LinearLayout(app);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(6), dp(4), dp(6), dp(4));
        // ★ v151：这里原来最上面还有一行「● 动作控制 / 收起」。用户 09-24 要求去掉 ——
        //   收起的入口只留**屏幕顶部那个常驻小绿点**（log 面板自带的那个），
        //   同一件事不做两个按钮，免得"点了哪个没同步"。
        col.addView(buildMotor());
        col.addView(buildGyro());
        rootCol = col;
        for (int i = 0; i < 3; i++) touched[i] = false;   // 新一次展开：重新自动对齐
        loadZero();
        syncBars(false);
        return col;
    }

    private static void loadZero() {
        try {
            zeroPitch = app.getSharedPreferences("ledi_agent", Context.MODE_PRIVATE)
                    .getFloat(K_ZP, 0f);
            zeroRoll = app.getSharedPreferences("ledi_agent", Context.MODE_PRIVATE)
                    .getFloat(K_ZR, 0f);
        } catch (Throwable ignored) {
        }
    }

    private static void initCtx(Context ctx) {
        app = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
    }

    /** 面板被拆掉时调，清掉对失效 View 的引用（否则 update 会往死人身上写）。 */
    public static void detach() {
        rootCol = null;
        gyroView = null;
        tvPitchRoll = null;
        tvTouchCount = null;
        tvRelease = null;
        for (int i = 0; i < 3; i++) {
            tvMotorVal[i] = null;
            bars[i] = null;
            touchBox[i] = null;
            touched[i] = false;
        }
        dragging = -1;
    }

    // ==================== 界面 ====================

    /**
     * 区块标题行（不可点，只做标题）。
     * <p>★ v152：原来这里还有个 {@code withCollapse=true} 分支（右端挂一个"收起"按钮）。
     * 用户 09-24 当场否掉：「上面的收起绿点去掉（log 面板自带绿点）」⇒ 删除，
     * 免得整块面板出现两个"收起"入口、点了哪个没同步。收起只走右上角那颗常驻小绿点。
     */
    private static View title(String s) {
        LinearLayout row = new LinearLayout(app);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView t = new TextView(app);
        t.setText(s);
        t.setTextColor(0xFF7FD4FF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        row.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /**
     * 标题行 + 右端一个**只它可点**的动作按钮。
     * <p>不靠空格把按钮"推"到右边（换字体就歪），也不让整行可点（会误触）。
     */
    private static View titleAction(String s, String action, View.OnClickListener l) {
        LinearLayout row = new LinearLayout(app);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView t = new TextView(app);
        t.setText(s);
        t.setTextColor(0xFF7FD4FF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        row.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView c = new TextView(app);
        c.setText(action);
        c.setTextColor(0xFF9BE7A0);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        c.setPadding(dp(12), dp(2), dp(4), dp(2));
        c.setOnClickListener(l);
        row.addView(c);
        return row;
    }

    /**
     * 区块 1：触摸反馈（左列上部）。
     *
     * <p>★ v151：三个方块**放大**（44dp → 80dp）—— 用户排版图里这块占左列上半，
     * 而且要能一眼看出"现在按的是哪一块"。
     *
     * <p>★★ v152：又**改矮**（80dp → 46dp）。用户 2026-09-24：「上面的这个触摸反馈
     * 可以改得矮一点……**为 log 留出足够大的位置**」。日志就在这个块的下面，
     * 所以它是唯一能给日志让出空间的杠杆（左列压 34dp ≈ 51px ≈ 3.6 行日志）。
     * 46dp × 约 150px 宽仍是很好按的指示块，字号 16 → 13 只降到块高还装得下。
     */
    private static View buildTouch() {
        LinearLayout col = new LinearLayout(app);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(title("触摸反馈（面部 / 头顶）"));

        LinearLayout boxes = new LinearLayout(app);
        boxes.setOrientation(LinearLayout.HORIZONTAL);
        String[] nm = {"左脸", "右脸", "头顶"};
        for (int i = 0; i < 3; i++) {
            TextView b = new TextView(app);
            b.setText(nm[i]);
            b.setGravity(Gravity.CENTER);
            b.setTextColor(0xFFBBBBBB);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            b.setBackgroundColor(0x33FFFFFF);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(TOUCH_H_DP), 1f);
            p.rightMargin = dp(5);
            boxes.addView(b, p);
            touchBox[i] = b;
        }
        col.addView(boxes);

        tvTouchCount = new TextView(app);
        tvTouchCount.setTextColor(0xFFE0E0E0);
        tvTouchCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvTouchCount.setText("—");
        col.addView(tvTouchCount);
        return col;
    }

    /** 触摸方块高度（dp）。v151 是 80，v152 压到 46 —— 见 {@link #buildTouch}。 */
    private static final int TOUCH_H_DP = 46;
    /** 一条电机滑条占的高度（dp）。v151 之前是 wrap_content（约 35dp），v152 定死 30dp 更紧凑。 */
    private static final int BAR_ROW_H_DP = 30;
    /** 姿态 3D 视图边长（dp）。v151 是 96，v152 压到 80。 */
    private static final int GYRO_DP = 80;

    /**
     * 区块 2：姿态 / 陀螺仪 3D。
     *
     * <p>★★★ v151：<b>本机没有陀螺仪</b> —— 真机取证：
     * <pre>
     *   adb shell dumpsys sensorservice
     *     Sensor List:
     *       ACCELEROMETER | MTK | type=1 | ...
     *     9-axis fusion disabled (0 clients), gyro-rate= 200.00Hz, q=&lt;0,0,0,0&gt;
     *     1 h/w sensors:
     * </pre>
     * 也就是**只有加速度计**，`GET /sensors` 也一直报 {@code has_gyro:false, gyro:[0,0,0]}。
     *
     * <p>后果必须说清楚，这是用户报"显示角度不跟着机器人走"的真因：
     * <ul>
     *   <li><b>pitch / roll 是活的</b> —— 它们由加速度计算（{@link SensorHub#onSensorChanged}），
     *       机器人前后倾 / 左右倾，这两个数会跟着变。</li>
     *   <li><b>yaw 拿不到</b> —— 它只能靠陀螺仪积分（或磁力计给绝对方位），
     *       两个硬件都没有 ⇒ 积分永远积在 0 上。v150 及以前这里写的是
     *       {@code yaw += gyroZ * dt}，等于**给一个不存在的传感器积分**，
     *       界面上却照样画着一个会转的立方体 —— 典型假绿灯。
     *       现在：没陀螺仪就不显示 yaw，<b>如实标"无陀螺仪"</b>。</li>
     *   <li>顺带修掉一个单位错：Android 陀螺仪单位是 <b>rad/s</b>，即使有硬件也不能直接
     *       当 deg/s 积分（会慢 57 倍）。</li>
     * </ul>
     *
     * <p>为了让"到底跟不跟手"一眼可验，标题右边给了个「归零」：把**当前**姿态记成零点
     * （落 prefs，重启还在）。这样静置时读数就是 0/0，人一动就看得见变化 ——
     * 否则本机静置本来就读 pitch≈-4°，谁也不知道那 4° 是标定偏移还是真在动。
     *
     * <h3>★★★ v153：3D 视图的旋转轴原来选错了（这才是"姿态检测还有问题"的真身）</h3>
     * 传感器的 pitch 是绕 <b>X</b> 轴、roll 是绕 <b>Y</b> 轴的转角，而 3D 视图原来按
     * {@code Ry(yaw)·Rx(pitch)·Rz(roll)} 组合 —— <b>把 roll 当成绕 Z（偏航）轴</b>。
     * 后果：静置时蓝轴正对镜头退化成点、立方体看着是张平面；左右倾时立方体在
     * <b>屏幕平面内打转</b>而不是侧倾。旋转矩阵已搬到 {@link Attitude}（单一事实源），
     * 并配了 {@code GET /attitudetest} 把它变成可证伪的三项断言。
     * <p>另外把读数行补上 {@code |a|}：静止时它恒 ≈ 9.81（与朝向无关），
     * 偏离就说明加速度计标定坏了 —— 这时候上面的 pitch/roll 全是假的。
     */
    private static View buildGyro() {
        LinearLayout col = new LinearLayout(app);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(titleAction("姿态 3D（加速度计）", "归零", new View.OnClickListener() {
            @Override public void onClick(View v) { zeroImu(); }
        }));
        gyroView = new Gyro3DView(app);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(GYRO_DP), dp(GYRO_DP));
        p.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(gyroView, p);
        tvPitchRoll = new TextView(app);
        tvPitchRoll.setTextColor(0xFFE0E0E0);
        tvPitchRoll.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvPitchRoll.setText("—");
        col.addView(tvPitchRoll);
        return col;
    }

    /** 把当前姿态记成零点（相对标定）。面板上的读数从此都是"相对零点"，静置即 0。 */
    private static void zeroImu() {
        SensorHub s = sensor;
        if (s == null || app == null) return;
        zeroPitch = s.pitch;
        zeroRoll = s.roll;
        try {
            app.getSharedPreferences("ledi_agent", Context.MODE_PRIVATE).edit()
                    .putFloat(K_ZP, zeroPitch).putFloat(K_ZR, zeroRoll).apply();
        } catch (Throwable ignored) {}
    }

    /**
     * 区块 3：电机位置 + 三条滑条（右列）。
     *
     * <p>★★ v152 压紧：用户要求「那个电机滑条也可以紧凑一点」。
     * <p>做法是给每一行**定死高度** {@link #BAR_ROW_H_DP}（30dp）+ 让 SeekBar 自己去撑满这一行。
     * 为什么不能只用 {@code WRAP_CONTENT}：Android 的 SeekBar 默认给拇指留了很大的上下
     * padding，实测一行约 35dp，三行就多花掉 15dp。定高之后滑条的**横向**拖动区域一点没变小，
     * 只是上下不再白占地方 —— 这在本机尤其重要：右列一共才 310px 宽、整块面板 438px 高。
     *
     * <p>⚠ 高度不能压到 24dp 以下：拇指本身约 20dp，再低会连拖动起手都摸不准。
     */
    private static View buildMotor() {
        LinearLayout col = new LinearLayout(app);
        col.setOrientation(LinearLayout.VERTICAL);
        // ★ v153：标题行右端挂「解除锁定」——**不新增一行高度**（面板高度是用户明确压过的）。
        //   调的是原厂公开方法 EngineService.realeaseControl_All()（释放全部舵机力矩），
        //   依据见 MotorPose.release()（含 sput 级别的字节证据）。点了之后文字临时变结果，
        //   3 秒后由 update() 变回 RELEASE_LABEL。
        LinearLayout head = new LinearLayout(app);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView ht = new TextView(app);
        ht.setText("动作控制和电机角度");
        ht.setTextColor(0xFF7FD4FF);
        ht.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        head.addView(ht, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tvRelease = new TextView(app);
        tvRelease.setText(RELEASE_LABEL);
        tvRelease.setTextColor(0xFFFF9A6B);
        tvRelease.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvRelease.setPadding(dp(10), dp(2), dp(4), dp(2));
        tvRelease.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                MotorPose m = motor;
                String r = m == null ? "{\"ok\":0}" : m.release();
                SleepMode.touch();
                releaseFlashText = r.contains("\"ok\":1") ? "已释放 ✓" : "失败";
                releaseFlashUntil = System.currentTimeMillis() + 3000L;
                if (tvRelease != null) tvRelease.setText(releaseFlashText);
            }
        });
        head.addView(tvRelease);
        col.addView(head);

        for (int i = 0; i < 3; i++) {
            LinearLayout line = new LinearLayout(app);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);

            TextView lb = new TextView(app);
            lb.setText(MotorPose.JOINT[i]);
            lb.setTextColor(0xFFBBBBBB);
            lb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            // ★ 右列只有 ~310px 宽，标签/读数都得收窄，否则滑条被挤到几乎拖不动。
            line.addView(lb, new LinearLayout.LayoutParams(dp(34),
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            final SeekBar sb = new SeekBar(app);
            final int idx = i;
            // Android 5.1 没有 setMin：用 max = 跨度，监听里再偏回 lo
            final int span = MotorPose.hi()[i] - MotorPose.lo()[i];
            final int lo = MotorPose.lo()[i];
            sb.setMax(span);
            sb.setProgress(span / 2);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    if (!fromUser) return;             // 见 syncBars：自动对齐不下发
                    touched[idx] = true;
                    onSlider(idx, p + lo);
                }
                @Override public void onStartTrackingTouch(SeekBar s) {
                    dragging = idx;
                    touched[idx] = true;               // 用户接管这条轴
                    SleepMode.touch();
                }
                @Override public void onStopTrackingTouch(SeekBar s) {
                    dragging = -1;
                    onSlider(idx, s.getProgress() + lo);
                }
            });
            // ★ v152：定高（原来 WRAP_CONTENT）。MATCH_PARENT 让滑条吃满这一行的 30dp，
            //   这样"行变矮"不会顺手把滑条的触摸区也削掉。
            line.addView(sb, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            bars[i] = sb;

            TextView v = new TextView(app);
            v.setTextColor(0xFFFFD479);
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            v.setGravity(Gravity.RIGHT);
            v.setText("-");
            line.addView(v, new LinearLayout.LayoutParams(dp(62),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            tvMotorVal[i] = v;

            col.addView(line, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(BAR_ROW_H_DP)));
        }
        return col;
    }

    /**
     * 按【读回值】把滑条摆到当前实际角度。
     *
     * <p>不这么做的话，滑条停在中点（j1 中点 = 30°），用户第一次碰它就会让电机从 0° 猛跳到 30°。
     *
     * <p>★ v149 加 {@code onlyUntouched}：
     * <ul>
     *   <li>{@code false}（面板刚建好时）：三条都摆。</li>
     *   <li>{@code true}（每次 tick）：只摆**用户还没碰过**的那几条。这是为了补一个真机场景 ——
     *       面板展开时原厂的第一帧广播可能还没到（{@code pos} 还是 -1），
     *       那一次对齐会整个跳过、滑条就僵在中点；等读数来了却再没人摆它。
     *       现在读数一到就会自动归位，而用户已经拖过的那条**永远不再被覆盖**。</li>
     * </ul>
     * <p>{@code setProgress} 会回调 {@code onProgressChanged(fromUser=false)}，而那里直接 return，
     * 所以对齐**不会下发**。
     */
    private static void syncBars(boolean onlyUntouched) {
        MotorPose m = motor;
        if (m == null) return;
        int[] raw = m.pos();
        int[] lo = MotorPose.lo(), hi = MotorPose.hi();
        for (int i = 0; i < 3; i++) {
            SeekBar b = bars[i];
            if (b == null) continue;
            if (onlyUntouched && touched[i]) continue;
            int a = MotorPose.angleOf(raw[i], i);
            if (a == Integer.MIN_VALUE) continue;      // 这条轴读数不可信 ⇒ 别乱摆
            if (a < lo[i]) a = lo[i];
            if (a > hi[i]) a = hi[i];
            int want = a - lo[i];
            if (b.getProgress() != want) b.setProgress(want);
        }
    }

    private static void onSlider(int idx, int angle) {
        long now = System.currentTimeMillis();
        if (now - lastSend < SEND_GAP_MS) return;
        lastSend = now;
        int[] cur = new int[3];
        for (int i = 0; i < 3; i++) {
            cur[i] = MotorPose.lo()[i] + (bars[i] == null ? 0 : bars[i].getProgress());
        }
        cur[idx] = angle;
        if (motor != null) motor.drive(cur[0], cur[1], cur[2], MotorPose.SLIDE_MS);
    }

    // ==================== 刷新（由 LogOverlay 的 tick 调） ====================

    /** @param dt 距上次刷新的秒数（只给"有陀螺仪"的机器积分 yaw 用） */
    public static void update(float dt) {
        SensorHub s = sensor;
        MotorPose m = motor;

        // ① 触摸：按住就亮
        if (s != null) {
            boolean[] on = {s.touchingLF, s.touchingRF, s.touchingHead};
            for (int i = 0; i < 3; i++) {
                View b = touchBox[i];
                if (b == null) continue;
                b.setBackgroundColor(on[i] ? 0xFF35C759 : 0x33FFFFFF);
                ((TextView) b).setTextColor(on[i] ? 0xFF000000 : 0xFFBBBBBB);
            }
            TextView tc = tvTouchCount;
            if (tc != null) {
                tc.setText("累计 " + s.touchCount + " 次 · 最近 "
                        + (s.lastTouch == null || s.lastTouch.isEmpty() ? "—" : s.lastTouch));
            }

            // ② 姿态。★ 本机没有陀螺仪（见 buildGyro 的取证），所以：
            //    · pitch/roll 用加速度计，减掉零位后显示 ⇒ 会跟着机器人倾斜动；
            //    · yaw 只在**真有陀螺仪**时才积分，且必须先 rad/s → deg/s；
            //      没有就如实写"无陀螺仪"，绝不画一个自己会转的假立方体。
            updatePose(s, dt);
        }

        // ③ 电机：右侧显示【读回】的真实位置（不是滑条值）；
        //    顺手把"用户还没碰过"的滑条对齐到真实角度 —— 面板刚开时读数可能还没来，
        //    那一次对齐会跳过，靠这里补上（详见 syncBars）。
        if (m != null) {
            syncBars(true);
            int[] p = m.pos();
            for (int i = 0; i < 3; i++) {
                TextView v = tvMotorVal[i];
                if (v == null) continue;
                if (p[i] < 0) {
                    v.setText("—");
                } else {
                    int a = MotorPose.angleOf(p[i], i);
                    v.setText(p[i] + (a == Integer.MIN_VALUE ? "" : " (" + a + "°)"));
                }
            }
        }

        // ④「解除锁定」按钮的文字回弹（点击后 3 秒变回标签）。
        //   按钮可能已被重建/拆掉，所以每次都从静态引用取一次，不缓存。
        if (tvRelease != null && releaseFlashUntil != 0L
                && System.currentTimeMillis() > releaseFlashUntil) {
            releaseFlashUntil = 0L;
            releaseFlashText = "";
            tvRelease.setText(RELEASE_LABEL);
        }

        // ⑤ 功能开关（v151 一度在这里加过一条开关条，用户 09-24 当场否掉：
        //    「功能面板去掉」）—— 已整条删除，不要再加回来。
    }

    /**
     * 姿态刷新（抽出来只为了把 {@link #update} 里的分支收干净）。
     */
    private static void updatePose(SensorHub s, float dt) {
        float p = s.pitch - zeroPitch;
        float r = s.roll - zeroRoll;
        float y = 0f;
        String yawTxt;
        if (s.hasGyro) {
            // ★ 单位：Android 陀螺仪是 rad/s，不转成度就会慢 57 倍（旧代码就是这个错）。
            yaw += (float) Math.toDegrees(s.gyroZ) * dt;
            if (yaw > 180f) yaw -= 360f;
            if (yaw < -180f) yaw += 360f;
            y = yaw;
            yawTxt = String.format("yaw %.0f°", y);
        } else {
            yawTxt = "yaw —（本机无陀螺仪）";
        }
        Gyro3DView gv = gyroView;
        if (gv != null) gv.setPose(p, r, y);
        TextView pr = tvPitchRoll;
        if (pr != null) {
            // ★ v153：把 |a| 一起显示出来。当时的推理是"静止时 |a| 恒 ≈ 9.81（与朝向无关），
            //   显示成别的数就是标定坏了"—— 前半句在**站立时**成立，后半句是错的（见下）。
            //   缘起是 2026-09-24 真机踩过：|a|=22.5 却凭空报出 pitch=-4.18°，而界面上
            //   看不出任何异常。把 |a| 摆出来这件事本身是对的，错的只是它的判定阈值。
            //
            // ★★ v154 修正：v153 的判据是「|a| 一旦不等于 ok 就报 ⚠需标定」，**这是错的** ——
            //   本机加速度计在**大倾角下 |a| 本就偏离 g**。用户 09-24 把机器人放倒后实测
            //   （全程静止、抖动 <0.6%）：
            //       站立 9.79 ｜ 小倾(13°,33°) 10.02 ｜ 侧倾(38°,60°) 14.38
            //       躺倒 18.40 ｜ 趴下(32°,177°) 3.48
            //   ⇒ 机器人一躺平，面板就闪"⚠需标定"；而标定要求"摆正放在水平平面上"，
            //   照着做等于**把好数据校坏**。现在按 {@link SensorHub#imuState} 分状态：
            //   只有 "站立 + 静置 + |a| 仍偏" 才敢说标定坏了。
            //   ★ 面板只给 2 个字的标记，完整长句走 GET /sensors 的 imu_hint（面板放不下）。
            String line1 = String.format("pitch %.0f°  roll %.0f°  |a| %.2f", p, r, s.accMag);
            String tag = imuTag(s.imuState);
            // 固定两行：右列只有 ~310px 宽，混在一行必然来回折行、面板高度跟着抖。
            if (tag.length() == 0) {
                pr.setText(line1 + "\n" + yawTxt);
            } else {
                // 用 span 上色（不改 TextView 整体色）：⚠标定=红，其余=暗黄，一眼分得清
                // "这是故障" 还是 "这只是位置不对"。
                SpannableString sp = new SpannableString(line1 + " " + tag + "\n" + yawTxt);
                int a0 = line1.length() + 1;
                sp.setSpan(new ForegroundColorSpan(imuTagColor(s.imuState)), a0,
                        a0 + tag.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                pr.setText(sp);
            }
        }
    }

    /**
     * 面板上给 {@link SensorHub#imuState} 的**极短**标记（最多 2 个字 + 1 个符号）。
     * <p>为什么这么短：右列只有 ~310px 宽，读数行必须**定长两行**，多一个字就会折行、
     * 面板高度跟着抖（v151 踩过）。完整解释走 {@code GET /sensors} 的 {@code imu_hint}。
     * <p>★ 语义分工是刻意的：{@code ⚠标定} 才是"去修"，其余都只是"此刻别信这个数"。
     */
    private static String imuTag(String st) {
        if (st == null) return "";
        if ("off".equals(st)) return "⚠标定";        // 站立+静置仍偏 ⇒ 真故障，该去标定
        if ("tilted".equals(st)) return "倾斜";      // ★ 不是故障：大倾角下 |a| 本就偏
        if ("moving".equals(st)) return "动中";
        if ("warming".equals(st)) return "预热";
        return "";
    }

    /** 标记颜色：只有"该去标定"给红，其余给暗黄 —— 别把"位置不对"画得和"故障"一样吓人。 */
    private static int imuTagColor(String st) {
        if ("off".equals(st)) return 0xFFFF5252;     // 红 = 要动手
        return 0xFFBDB76B;                           // 暗黄 = 只是此刻不可信
    }

    private static int dp(int v) {
        return (int) (v * app.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ==================== 陀螺仪 3D 视图 ====================

    /**
     * 软件 3D 线框立方体。
     * <p>为什么不用 GLSurfaceView：这台 ROM（Android 5.1 / MT6580 / 软件屏保零 wakelock）上，
     * 一个叠加层的 GL surface 一旦 context lost 就是黑屏，而且会额外占一个渲染线程 ——
     * 对一个"看一眼朝向"的调试件不值。Canvas 画 12 条线足够。
     * <p>另外画一组**不动**的参考十字（灰）：只有动的立方体时，人眼分不清是模型在转还是参考系在转。
     */
    static final class Gyro3DView extends View {

        private final Paint cube = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ref = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        private volatile float pitch, roll, yaw;

        private static final float[][] V = {
                {-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1},
                {-1, -1, 1}, {1, -1, 1}, {1, 1, 1}, {-1, 1, 1},
        };
        private static final int[][] E = {
                {0,1},{1,2},{2,3},{3,0}, {4,5},{5,6},{6,7},{7,4}, {0,4},{1,5},{2,6},{3,7},
        };

        Gyro3DView(Context c) {
            super(c);
            cube.setStyle(Paint.Style.STROKE);
            cube.setStrokeWidth(2.5f);
            cube.setColor(0xFF6FD3FF);
            axis.setStyle(Paint.Style.STROKE);
            axis.setStrokeWidth(3f);
            ref.setStyle(Paint.Style.STROKE);
            ref.setStrokeWidth(1.5f);
            ref.setColor(0x55FFFFFF);
            text.setTextSize(18f);
            text.setColor(0xFF9A9A9A);
        }

        void setPose(float p, float r, float y) {
            pitch = p; roll = r; yaw = y;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            if (w <= 4 || h <= 4) return;
            float cx = w / 2f, cy = h / 2f;
            float sc = Math.min(w, h) * 0.32f;

            // 参考系（不动）：地平线 + 十字。
            // ★ 旋转修好之后这条十字才有意义：现在"上"真的会被映射到屏幕竖直方向
            //   （见 Attitude 的不变式），所以十字就是重力线的参照。修之前它只是装饰。
            c.drawLine(cx - sc * 1.5f, cy, cx + sc * 1.5f, cy, ref);
            c.drawLine(cx, cy - sc * 1.5f, cx, cy + sc * 1.5f, ref);

            // ★ v153：旋转矩阵换成 Attitude.rot（单一事实源，单位=度）。
            //   旧实现在这里自己写了一套 R=Ry(yaw)·Rx(pitch)·Rz(roll)，把 roll 当成了
            //   绕 Z（偏航）轴 —— 静置时蓝轴正对镜头退化成点，左右倾时立方体在屏面内打转。
            float[] q = new float[8 * 3];
            for (int i = 0; i < 8; i++) {
                float[] p = Attitude.rot(V[i], pitch, roll, yaw);
                float z = 4.2f + p[2];                 // 相机在 z 负方向
                float f = sc * 1.6f / Math.max(1.2f, z);
                q[i * 3] = cx + p[0] * f;
                q[i * 3 + 1] = cy - p[1] * f;
                q[i * 3 + 2] = z;
            }
            path.reset();
            for (int[] e : E) {
                path.moveTo(q[e[0] * 3], q[e[0] * 3 + 1]);
                path.lineTo(q[e[1] * 3], q[e[1] * 3 + 1]);
            }
            c.drawPath(path, cube);

            // 机体系三轴（跟着转）：X 红 / Y 绿 / Z 蓝
            drawAxis(c, cx, cy, sc, 1, 0, 0, 0xFFFF5B5B);
            drawAxis(c, cx, cy, sc, 0, 1, 0, 0xFF5BE07A);
            drawAxis(c, cx, cy, sc, 0, 0, 1, 0xFF7FA8FF);

            // ★ v153：角标改成"可用性"而不是固定写 yaw —— 本机没有陀螺仪时，
            //   写个 "yaw" 会让人以为那个轴在动。
            c.drawText((sensor != null && sensor.hasGyro) ? "yaw" : "无陀螺仪", 6, h - 6, text);
        }

        private void drawAxis(Canvas c, float cx, float cy, float sc,
                              float x, float y, float z, int color) {
            float[] p = Attitude.rot(new float[] {x * 1.75f, y * 1.75f, z * 1.75f},
                    pitch, roll, yaw);
            float zz = 4.2f + p[2];
            float f = sc * 1.6f / Math.max(1.2f, zz);
            axis.setColor(color);
            c.drawLine(cx, cy, cx + p[0] * f, cy - p[1] * f, axis);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            return super.onTouchEvent(e);
        }
    }
}
