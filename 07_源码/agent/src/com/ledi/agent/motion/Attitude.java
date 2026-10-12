package com.ledi.agent.motion;

import com.ledi.agent.ui.DebugPad;
import com.ledi.agent.core.J;
import com.ledi.agent.sense.SensorHub;

/**
 * 姿态数学：<b>加速度 → pitch/roll</b> 与 <b>机体 → 屏幕</b> 的旋转。
 *
 * <h3>为什么单独立一个类</h3>
 * 这两个公式原来散在两处：{@code SensorHub.onSensorChanged} 里算 pitch/roll，
 * {@code DebugPad.Gyro3DView.rot} 里另写一套旋转矩阵。两边各写一半的后果是
 * <b>它们对"哪个角绕哪根轴"的假设可以不一致，而且不会有任何报错</b> ——
 * 3D 视图照样能画、数字照样在跳，只是画出来的朝向跟真实的对不上。
 * 现在收成单一事实源，并且配一个**可以证伪的**自检（见 {@link #selfTest()}）。
 *
 * <h3>★★★ v153 修掉的真 bug：显示用的旋转轴选错了</h3>
 * 传感器的定义（本类 {@link #pitchOf}/{@link #rollOf}，与原厂 test 页一致）是：
 * <pre>
 *   pitch = atan2(-ay, √(ax²+az²))     ← 这是绕 <b>X</b> 轴的转角
 *   roll  = atan2( ax,      az )       ← 这是绕 <b>Y</b> 轴的转角
 * </pre>
 * 而旧代码的 3D 视图按 {@code R = Ry(yaw)·Rx(pitch)·Rz(roll)} 组合 ——
 * <b>把 roll 当成绕 Z 轴（偏航轴）转了</b>。后果（本机实测口径）：
 * <ul>
 *   <li>静置时机体轴 Z 正对镜头 ⇒ 蓝轴退化成<b>一个点</b>，立方体看着是张平面。</li>
 *   <li>机器人左右倾（roll）时，立方体在<b>屏幕平面内打转</b>，而不是侧倾。</li>
 *   <li>按 pitch/roll 反解出的"重力方向"投影不到屏幕竖直方向 ⇒ 地平线参考失效。</li>
 * </ul>
 *
 * <h3>正确组合怎么来的（可复核）</h3>
 * 设 {@code u = accel/|a|} 是"上"在机体系里的单位向量。把上面两条定义反解可得
 * <pre>
 *   u = (cosφ·sinψ, −sinφ, cosφ·cosψ)      φ=pitch, ψ=roll
 * </pre>
 * 于是 {@code Rc = Rx(−pitch)·Ry(−roll)} 恰好把 u 送回 {@code (0,0,1)}（即让"上"与机体 Z 重合）。
 * 屏幕坐标系是 {@code X 右 / Y 上 / Z 朝屏幕里}，而 Z 是"朝里"不是"朝上"，
 * 所以再补一个 {@code Rx(−90°)} 把"上"抬到屏幕竖直方向，最后按 yaw 绕屏幕竖直轴转：
 * <pre>
 *   M = Rz(yaw) · Rx(−90° − pitch) · Ry(−roll)
 * </pre>
 * <b>不变式</b>：对任意姿态，{@code M · u} 必须恒等于 {@code (0,1,0)}（重力永远朝屏幕下）。
 * 这正是 {@link #selfTest()} 断言的东西，也是旧代码通不过的那条。
 *
 * <p>⚠ 本机（MT6580 乐迪）<b>没有陀螺仪</b>，yaw 恒为 0；pitch/roll 的<b>正负号方向</b>
 * 只有把机器人真的前后 / 左右倾一次才能最终裁决，本机当前没有倾斜条件 —— 如实标注。
 */
public final class Attitude {

    /** 重力加速度标称值（m/s²）。用来判断加速度计读数是否可信。 */
    public static final float G = 9.81f;

    private Attitude() {}

    /** pitch（度）= 绕 X 轴的转角。单一事实源：SensorHub 也调这里。 */
    public static float pitchOf(float ax, float ay, float az) {
        return (float) Math.toDegrees(Math.atan2(-ay, Math.sqrt(ax * ax + az * az)));
    }

    /** roll（度）= 绕 Y 轴的转角。单一事实源：SensorHub 也调这里。 */
    public static float rollOf(float ax, float ay, float az) {
        return (float) Math.toDegrees(Math.atan2(ax, az));
    }

    /** 加速度模长 |a|。静止时无论什么朝向都应当 ≈ {@link #G}。 */
    public static float magOf(float ax, float ay, float az) {
        return (float) Math.sqrt(ax * ax + ay * ay + az * az);
    }

    /**
     * 读数是否可信：<b>|a| 与 g 的偏差</b>。
     *
     * <p>为什么用模长而不是分量：静止时 {@code |a| ≡ g} <b>与朝向无关</b>，
     * 所以"模长不对"只可能是偏置/标定问题，绝不是"机器人摆歪了"。
     * 这是全项目里唯一一条不用知道机器人朝向就能判加速度计好坏的判据。
     *
     * @return {@code true} = 该读数可以当姿态用
     */
    public static boolean magPlausible(float ax, float ay, float az) {
        float m = magOf(ax, ay, az);
        return m > G - MAG_TOL && m < G + MAG_TOL;
    }

    /** |a| 允许的偏差。正常姿态波动 &lt;0.1 g；留 0.8 g 只为不误报。 */
    public static final float MAG_TOL = 0.8f;

    /**
     * "水平"容差（度）：{@code |pitch|} 与 {@code |roll|} 都小于它，才算机器人是站着的。
     *
     * <p>取 25° 的依据是真机实测那五组姿态里最小的倾角是 {@code (13°, 33°)}
     * （{@code |a|=10.02}，此时 |a| 已经偏离站立的 9.79）⇒ 阈值必须小于 33
     * 才能在"小倾"时就把倾斜认出来；取 25 是留一点余量，机器人站在略斜的地板上
     * 本来就有 1~3° 的零偏。
     *
     * <p>★ 这个数是**判断**出来的，不是测出来的 —— 真机再攒到更多姿态数据后应该复核它。
     */
    public static final float LEVEL_DEG = 25f;

    /** 读数是否水平。见 {@link #LEVEL_DEG}。 */
    public static boolean isLevel(float pitch, float roll) {
        return Math.abs(pitch) < LEVEL_DEG && Math.abs(roll) < LEVEL_DEG;
    }

    /**
     * ★★★ v156：IMU 状态机的<b>纯函数</b>版本 —— 输入数值、输出状态串，不碰任何字段。
     *
     * <p>为什么要把这段逻辑从 {@code SensorHub} 里搬出来：它原来是读实例字段的，
     * 于是"侧躺时到底判成什么"只能靠真机摆姿势才知道，而**恰恰就是这里判断错过一次**——
     * v153/v154 只看 {@code |a|} 偏离就报"需标定"，真机把机器人放倒后 |a| 本来就偏
     * （侧躺 14.4 / 躺倒 18.4），于是面板一路弹"⚠需标定"，而标定要求"摆正放在水平面上"
     * ⇒ 照做就是把好数据校坏。搬到纯函数之后，{@link #selfTest()} 能把它变成断言。
     *
     * <h3>判据（任何一条不满足就绝不往下走）</h3>
     * <ol>
     *   <li>没有加速度计 ⇒ {@code unknown}。</li>
     *   <li>环形缓冲没收满 ⇒ {@code warming}（此时极差算不出来，任何结论都不成立）。</li>
     *   <li>{@code spread > 1.0} ⇒ {@code moving}：机器人在动，姿态判据一律失效。</li>
     *   <li><b>两条都成立</b>才算"站着"：{@code mcuStand}（原厂 MCU 的姿态广播说站立）
     *       <b>且</b> {@link #isLevel}（这颗芯片自己也说它是水平的）。
     *       任何一条不成立 ⇒ {@code tilted}，也就是"此刻的读数不可信，但这不是故障"。</li>
     *   <li>都成立时才看 {@code |a|}：≈ g ⇒ {@code ok}；偏离 ⇒ {@code off}（真该去标定）。</li>
     * </ol>
     *
     * <p>★ 为什么第 4 条非要用两个独立来源：MCU 广播在进程刚起来时<b>还没到</b>，
     * 字段停在默认值 {@code "stand"}（真机 {@code /sensors} 的 {@code posture.ago} 显示
     * {@code "-"}）—— 只信它就会把"躺着"误判成"站立+需标定"。
     * 而只信加速度计自己也不行：标定坏掉的芯片会一边 |a| 偏离、一边报出虚假的大角度，
     * 看角度就永远只会说 tilted，把真故障藏起来。两条互为对照，缺一条就有一个假绿灯。
     *
     * <p>⚠ 一个刻意的偏袒：{@code mcuStand} 在"还没收到过广播"时也是 {@code true}
     * （{@code postureRaw} 的默认值）。不能因为没消息就说"倾斜" —— 那会让面板挂着一个
     * 永久错误的标签（原厂只在姿态变化时发广播，站着不动就永远不会有新消息）。
     * 真正拦住误报的是 {@link #isLevel} 那一条，它不依赖任何外部消息。
     *
     * @param hasAccel 本机有没有加速度计
     * @param warming  采样是否还没攒够
     * @param spread   最近一段 |a| 的极差（判"在动"）
     * @param mcuStand 原厂 MCU 报告的姿态是不是"站立"
     */
    public static String imuState(boolean hasAccel, boolean warming, float spread,
                                  float pitch, float roll,
                                  float ax, float ay, float az, boolean mcuStand) {
        if (!hasAccel) return "unknown";
        if (warming) return "warming";
        if (spread > 1.0f) return "moving";
        if (!isLevel(pitch, roll) || !mcuStand) return "tilted";
        return magPlausible(ax, ay, az) ? "ok" : "off";
    }

    /**
     * 机体系 → 显示系。{@code M = Rz(yaw)·Rx(−90°−pitch)·Ry(−roll)}，见类注释。
     * @param v     机体系向量
     * @param pitch 度（已减零位）
     * @param roll  度（已减零位）
     * @param yaw   度（本机无陀螺仪时恒 0）
     */
    public static float[] rot(float[] v, float pitch, float roll, float yaw) {
        double rr = Math.toRadians(-roll);
        double pr = Math.toRadians(-90.0 - pitch);
        double yr = Math.toRadians(yaw);
        float x = v[0], y = v[1], z = v[2];

        // ① Ry(-roll)：roll 的定义轴是 Y
        float x1 = (float) (x * Math.cos(rr) + z * Math.sin(rr));
        float z1 = (float) (-x * Math.sin(rr) + z * Math.cos(rr));
        float y1 = y;

        // ② Rx(-90 - pitch)：把"上"抬到屏幕竖直方向，并带上俯仰
        float y2 = (float) (y1 * Math.cos(pr) - z1 * Math.sin(pr));
        float z2 = (float) (y1 * Math.sin(pr) + z1 * Math.cos(pr));
        float x2 = x1;

        // ③ Rz(yaw)：绕屏幕竖直轴
        float x3 = (float) (x2 * Math.cos(yr) - y2 * Math.sin(yr));
        float y3 = (float) (x2 * Math.sin(yr) + y2 * Math.cos(yr));

        return new float[] {x3, y3, z2};
    }

    // ==================== 自检 ====================

    /** 自检用：拿一个加速度向量过一遍状态机（pitch/roll 由向量现算，保证与真机同一条路）。 */
    private static String cls(float[] a, float spread, boolean mcuStand) {
        return imuState(true, false, spread,
                pitchOf(a[0], a[1], a[2]), rollOf(a[0], a[1], a[2]),
                a[0], a[1], a[2], mcuStand);
    }

    /** 正向模型：给定 pitch/roll 生成"应该读到"的加速度向量（用于造已知答案）。 */
    static float[] accelOf(float pitchDeg, float rollDeg) {
        double p = Math.toRadians(pitchDeg), r = Math.toRadians(rollDeg);
        return new float[] {
                (float) (G * Math.cos(p) * Math.sin(r)),
                (float) (-G * Math.sin(p)),
                (float) (G * Math.cos(p) * Math.cos(r)),
        };
    }

    /**
     * 自检：把姿态数学里**能静态判定对错**的部分摊开说清楚。
     *
     * <p>三项断言：
     * <ol>
     *   <li><b>不变式</b>：对 5×5 组姿态，{@code M · unit(accel)} 必须 ≈ {@code (0,1,0)}
     *       —— 即"重力永远指向屏幕下方"。旧代码这项最大误差约 1.0（完全不成立）。</li>
     *   <li><b>roll 必须绕 Y 轴起作用</b>：纯 roll=40° 时，机体 Z 轴在屏幕上的
     *       X 分量应 ≈ sin40° = 0.643。旧代码恒为 0（roll 被当成绕 Z 转而不可见）。</li>
     *   <li><b>pitch 必须绕 X 轴起作用</b>：纯 pitch=40° 时，机体 Z 轴的 X 分量应 ≈ 0，
     *       而 Y 分量应 ≈ cos40° = 0.766。</li>
     * </ol>
     * 这三项都不依赖真机、不依赖人手倾斜，是**可以现在就判死**的。
     */
    public static String selfTest() {
        double maxInv = 0;
        int n = 0;
        for (int p = -60; p <= 60; p += 30) {
            for (int r = -60; r <= 60; r += 30) {
                float[] a = accelOf(p, r);
                float mag = magOf(a[0], a[1], a[2]);
                float[] u = {a[0] / mag, a[1] / mag, a[2] / mag};
                float[] d = rot(u, pitchOf(a[0], a[1], a[2]), rollOf(a[0], a[1], a[2]), 0f);
                double e = Math.sqrt(Math.pow(d[0], 2) + Math.pow(d[1] - 1, 2) + Math.pow(d[2], 2));
                if (e > maxInv) maxInv = e;
                n++;
            }
        }
        float[] ar = accelOf(0, 40);
        float[] zr = rot(new float[] {0, 0, 1}, pitchOf(ar[0], ar[1], ar[2]),
                rollOf(ar[0], ar[1], ar[2]), 0f);
        float[] ap = accelOf(40, 0);
        float[] zp = rot(new float[] {0, 0, 1}, pitchOf(ap[0], ap[1], ap[2]),
                rollOf(ap[0], ap[1], ap[2]), 0f);

        boolean inv = maxInv < 1e-3;
        boolean rollOk = Math.abs(Math.abs(zr[0]) - 0.643f) < 0.02f;
        boolean pitchOk = Math.abs(zp[0]) < 0.02f && Math.abs(Math.abs(zp[1]) - 0.766f) < 0.02f;

        // ---- ④ IMU 状态机（v156）：把"什么时候才敢说该标定"也变成断言 ----
        //   ⚠ 这一组是**被真机打回来之后**补的。v153/v154 的判据只要 |a| 偏就报"需标定"，
        //   而真机把机器人放倒后 |a| 本来就偏 ⇒ 侧躺时会被误导去标定（把好数据校坏）。
        //   下面 c2/c3 两个用例就是那一刻的复现，**必须**是 tilted，绝不能是 off。
        float[] aStand = accelOf(0, 0);                    // 站立：|a| = 9.81
        float[] aLieD = accelOf(38, 60);                   // 侧躺的方向（实测 pitch/roll）
        float an = magOf(aLieD[0], aLieD[1], aLieD[2]);
        float[] aLie = {aLieD[0] / an * 14.38f,            // 侧躺**实测**的 |a| = 14.38
                        aLieD[1] / an * 14.38f,
                        aLieD[2] / an * 14.38f};
        float[] aBad = {-0.05f, -0.7f, 22.4f};             // 标定坏：|a|=22.4 却自称水平

        String c1 = cls(aStand, 0.05f, true);              // 站立静置 ⇒ ok
        String c2 = cls(aLie, 0.05f, false);               // 侧躺（MCU 也报了）⇒ tilted
        String c3 = cls(aLie, 0.05f, true);                // ★ 侧躺 + MCU 广播还没到 ⇒ 仍须 tilted
        String c4 = cls(aBad, 0.05f, true);                // 站立 + |a| 坏 ⇒ off（真该标定）
        String c5 = cls(aStand, 0.05f, false);             // 读数水平但 MCU 说不是站立 ⇒ tilted
        String c6 = cls(aStand, 2.00f, true);              // 在动 ⇒ moving
        boolean imuClassifyOk = "ok".equals(c1) && "tilted".equals(c2)
                && "tilted".equals(c3) && "off".equals(c4)
                && "tilted".equals(c5) && "moving".equals(c6);
        // 顺带证明 c4 走的是"|a| 坏"那条分支，而不是因为角度大被判成 tilted
        boolean badIsLevel = isLevel(pitchOf(aBad[0], aBad[1], aBad[2]),
                rollOf(aBad[0], aBad[1], aBad[2]));

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"cases\":").append(n);
        sb.append(",\"invariant_max_err\":").append(String.format("%.6f", maxInv));
        sb.append(",\"invariant_ok\":").append(inv);
        sb.append(",\"roll_axis_x_want\":0.643,\"roll_axis_x_got\":")
          .append(String.format("%.3f", zr[0])).append(",\"roll_axis_ok\":").append(rollOk);
        sb.append(",\"pitch_axis_x_got\":").append(String.format("%.3f", zp[0]))
          .append(",\"pitch_axis_y_got\":").append(String.format("%.3f", zp[1]))
          .append(",\"pitch_axis_ok\":").append(pitchOk);
        sb.append(",\"imu_classify\":{\"stand\":\"").append(J.esc(c1))
          .append("\",\"lie\":\"").append(J.esc(c2))
          .append("\",\"lie_stale_mcu\":\"").append(J.esc(c3))
          .append("\",\"bad_cali\":\"").append(J.esc(c4))
          .append("\",\"mcu_not_stand\":\"").append(J.esc(c5))
          .append("\",\"moving\":\"").append(J.esc(c6)).append("\"}");
        sb.append(",\"imu_classify_ok\":").append(imuClassifyOk);
        sb.append(",\"imu_level_deg\":").append(LEVEL_DEG);
        sb.append(",\"bad_cali_is_level\":");sb.append(badIsLevel);
        boolean all = inv && rollOk && pitchOk && imuClassifyOk;
        sb.append(",\"ok\":").append(all);
        sb.append(",\"formula\":\"Rz(yaw)*Rx(-90-pitch)*Ry(-roll)\"");
        sb.append(",\"note\":\"旧代码 Rz(roll) 把 roll 当偏航轴，invariant 与 roll_axis 两项必挂；"
                + "imu_classify 里 lie* 两项若变成 off，就是把侧躺误报成'该标定'\"");
        if (!all) sb.append(",\"fail\":\"有问题的那一项见上\"");
        return sb.append("}").toString();
    }
}
