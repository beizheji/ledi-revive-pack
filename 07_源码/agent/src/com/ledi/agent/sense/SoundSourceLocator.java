package com.ledi.agent.sense;

import com.ledi.agent.core.J;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.util.Arrays;

/**
 * 双麦克风声源定位（DOA）。
 *
 * <p>背景与判据：本机 audio_policy.conf 的 primary 输入同时声明了
 * AUDIO_DEVICE_IN_BUILTIN_MIC 与 AUDIO_DEVICE_IN_BACK_MIC（说明壳子里确实有两个物理麦），
 * 但 ro.mtk_dual_mic_support=0（MTK 那套双麦软件处理是关的）。
 * 配置对不上，光看配置判不出"打开 STEREO 拿到的到底是两路真信号，还是同一路复制两份"。
 * 所以第一步必须是**探针**：把两路拉出来算互相关。
 *
 * <ul>
 *   <li>两路相关系数 ≈ 1.000 且最佳对齐就在 0 位移 → 复制出来的假立体声，没有方向信息，收工。</li>
 *   <li>两路相似但最佳对齐**不在 0 位移**（几到十几个采样的时延）、相关系数明显低于 1
 *       → 两个真实物理麦，这个时延差就是 DOA 的原料。</li>
 * </ul>
 *
 * <p>为什么不用 GCC-PHAT：本机麦间距小（厘米级），16kHz 下 1 采样 ≈ 21µs，
 * 10cm 间距的理论最大时延也只有 ±4.7 采样 —— 最佳对齐就落在 0 附近几个采样内。
 * 这么小的位移用互相关 + 抛物线插值足够，而且"相关系数"这个数直接可解释
 * （= 对齐后两路有多像），排障时比 PHAT 峰更像人话。省掉一整套 FFT 代码。
 *
 * <p>方向标定：麦克风阵列的**朝向**和**左右声道与左右侧的对应关系**都拿不到，
 * 所以绝不能假设 lag>0 就是右边。{@link #calibrate(String)} 让用户站在已知一侧录一次，
 * 把符号和满量程参考值一起存进 prefs。没标定时用保守默认值，只输出"偏A侧/偏B侧"。
 */
public class SoundSourceLocator {

    static final String PREFS = "ledi_agent";
    static final String KEY_SIGN = "doa_sign";        // +1 / -1
    static final String KEY_REF = "doa_ref";          // 满量程参考 |lag|（采样）
    static final String KEY_ON = "doa_on";
    static final String KEY_SRC = "doa_src";

    public static final int RATE = 16000;
    /** 每次估计用的窗长（采样）。1024 = 64ms，兼顾稳定与响应。 */
    static final int WIN = 1024;
    /** 互相关搜索范围（采样）。±MAX_LAG 要覆盖物理最大时延，留足余量。 */
    static final int MAX_LAG = 24;
    /** 能量门限：低于它的帧当静默，不参与方向估计（RMS，16bit 满量程 32767）。 */
    static final int RMS_GATE = 150;
    /** 平滑窗口：取最近几帧的中位数，抗单帧跳变。 */
    static final int SMOOTH = 5;

    // ==================== 探针（静态，不需要 Context） ====================

    /**
     * 一次性可行性探针：把几种 AudioSource 都试一遍，直接回答"这机器能不能做声源定位"。
     * 返回 JSON 数组，每个元素一行结论。会阻塞约 (源数量 × 秒) 长。
     */
    public static String probe(int millisPerSource) {
        if (millisPerSource <= 0) millisPerSource = 1200;
        StringBuilder sb = new StringBuilder();
        sb.append("{\"rate\":").append(RATE).append(",\"ms_per_source\":").append(millisPerSource)
                .append(",\"results\":[");
        int[] srcs = {
                MediaRecorder.AudioSource.DEFAULT,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        };
        String[] names = {"DEFAULT", "MIC", "CAMCORDER", "VOICE_RECOGNITION", "VOICE_COMMUNICATION"};
        boolean anyReal = false;
        for (int i = 0; i < srcs.length; i++) {
            if (i > 0) sb.append(',');
            boolean[] real = new boolean[1];
            sb.append(probeOne(srcs[i], names[i], millisPerSource, real));
            if (real[0]) anyReal = true;
        }
        sb.append("],\"any_real\":").append(anyReal).append('}');
        return sb.toString();
    }

    private static String probeOne(int source, String name, int ms, boolean[] realOut) {
        StringBuilder o = new StringBuilder();
        o.append("{\"source\":\"").append(name).append('"');
        AudioRecord rec = null;
        try {
            int minBuf = AudioRecord.getMinBufferSize(
                    RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            o.append(",\"min_buf\":").append(minBuf);
            if (minBuf <= 0) {
                return o.append(",\"ok\":false,\"why\":\"该通道组合不被支持\"}").toString();
            }
            int buf = Math.max(minBuf, RATE);   // 0.5s
            rec = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT, buf);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                release(rec);
                return o.append(",\"ok\":false,\"why\":\"初始化失败（可能麦克风被占用）\"}").toString();
            }
            int ch = rec.getChannelCount();
            o.append(",\"ch_count\":").append(ch);
            rec.startRecording();
            int want = RATE * Math.max(1, ch) * ms / 1000;
            short[] data = new short[want];
            int got = 0;
            long end = System.currentTimeMillis() + ms + 1500L;
            while (got < data.length && System.currentTimeMillis() < end) {
                int n = rec.read(data, got, data.length - got);
                if (n <= 0) break;
                got += n;
            }
            release(rec);
            if (got <= 0) return o.append(",\"ok\":false,\"why\":\"读不到数据\"}").toString();
            if (ch < 2) {
                long mx = 0; double s = 0; int c = 0;
                for (int i = 0; i < got; i++) { long v = Math.abs((long) data[i]); if (v > mx) mx = v; s += (double) data[i] * data[i]; c++; }
                o.append(",\"ok\":true,\"real\":false,\"mono\":true,\"max_abs\":").append(mx)
                        .append(",\"rms\":").append((long) Math.sqrt(c == 0 ? 0 : s / c))
                        .append(",\"verdict\":\"只给了一路（channelCount=1），没有方向信息\"}");
                return o.toString();
            }
            int n = got / 2;
            // 拆成两路
            short[] a = new short[n], b = new short[n];
            for (int i = 0; i < n; i++) { a[i] = data[i * 2]; b[i] = data[i * 2 + 1]; }
            long aMax = maxAbs(a), bMax = maxAbs(b);
            double aRms = rms(a), bRms = rms(b);
            // ★ 铁证：逐采样比一遍。声道"高度相关"可能是巧合（比如两路都只听得到
            //   同一个强声源），但"每一个采样都完全相同"只能是 HAL 把单路复制了两份。
            //   有了这个数，结论就不再依赖我的互相关代码写得对不对。
            int diffCount = 0, diffMax = 0;
            for (int i = 0; i < n; i++) {
                int d = Math.abs(a[i] - b[i]);
                if (d != 0) diffCount++;
                if (d > diffMax) diffMax = d;
            }
            o.append(",\"a_max\":").append(aMax).append(",\"b_max\":").append(bMax)
                    .append(",\"a_rms\":").append((long) aRms).append(",\"b_rms\":").append((long) bRms)
                    .append(",\"diff_count\":").append(diffCount).append(",\"diff_max\":").append(diffMax)
                    .append(",\"identical\":").append(diffCount == 0);
            if (aMax == 0 && bMax == 0) {
                return o.append(",\"ok\":true,\"real\":false,\"verdict\":\"两路全静音（被 AEC/占用掐死？）\"}").toString();
            }
            double[] est = estimate(a, b);
            double lag = est[0], rho = est[1], zero = est[2];
            boolean real = Math.abs(lag) >= 1.0 && rho > 0.05 && rho < 0.9995;
            boolean dup = diffCount == 0 || (zero > 0.9995 && Math.abs(lag) < 0.6);
            String verdict;
            if (diffCount == 0) {
                verdict = "假立体声（铁证）：" + n + " 个采样逐个比对，两路差异 0 个 —— "
                        + "HAL 把同一路复制成了两份，物理上不存在方向信息";
            } else if (dup) {
                verdict = "假立体声：两路几乎完全相同（相关系数 " + f4(zero) + "），无方向信息";
            } else if (real) {
                verdict = "★ 真双麦：最佳对齐 lag=" + f2(lag) + " 采样（"
                        + f0(lag / RATE * 1e6) + "µs），相关系数 " + f4(rho) + "，可做声源定位";
            } else {
                verdict = "存疑：最佳 lag=" + f2(lag) + "，相关系数 " + f4(rho)
                        + "，0 位移相关系数 " + f4(zero) + "（信号太弱或太嘈杂？对着麦克风说句话再试）";
            }
            realOut[0] = real;
            o.append(",\"ok\":true,\"real\":").append(real)
                    .append(",\"dup\":").append(dup)
                    .append(",\"lag\":").append(f2(lag))
                    .append(",\"rho\":").append(f4(rho))
                    .append(",\"rho0\":").append(f4(zero))
                    .append(",\"lag_us\":").append(f0(lag / RATE * 1e6))
                    .append(",\"verdict\":\"").append(J.esc(verdict)).append("\"}");
            return o.toString();
        } catch (Throwable t) {
            release(rec);
            return o.append(",\"ok\":false,\"why\":\"").append(J.esc(String.valueOf(t))).append("\"}").toString();
        }
    }

    // ==================== 实时定位 ====================

    private final Context ctx;
    private volatile AudioRecord rec;
    private volatile boolean running = false;
    private volatile boolean enabled = false;
    private volatile int source = MediaRecorder.AudioSource.MIC;
    private Thread thread;

    private volatile int chCount = 0;
    private volatile String lastError = "";
    private volatile long frames = 0;

    /** 最近若干帧的 lag，用于取中位数 */
    private final double[] hist = new double[SMOOTH];
    private int histN = 0, histI = 0;

    /** 说话中的方向（实时刷新） */
    private volatile double voiceLag = 0;
    private volatile double voiceRho = 0;
    private volatile long voiceTs = 0;
    /** 最后一段**有效语音**的方向 —— 静音后冻结，用来回答"刚才说话的人在哪" */
    private volatile double burstLag = 0;
    private volatile double burstRho = 0;
    private volatile long burstTs = 0;
    private volatile long burstFirstTs = 0;
    private volatile String burstSector = "";
    private volatile double burstRatio = 0;

    /** 标定 */
    private volatile double calSign = 1;     // +1: lag>0 = 右侧
    private volatile double calRef = 3.0;    // 满量程参考 |lag|
    /** 当前方位的来源：doa=双麦算出来的；touch=摸哪半边脸；""=还没有 */
    private volatile String dirFrom = "";

    public SoundSourceLocator(Context ctx) {
        this.ctx = ctx;
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        calSign = sp.getFloat(KEY_SIGN, 1f);
        calRef = sp.getFloat(KEY_REF, 3f);
        enabled = sp.getBoolean(KEY_ON, false);
        source = sp.getInt(KEY_SRC, MediaRecorder.AudioSource.MIC);
        if (calRef < 0.2) calRef = 3.0;
    }

    public boolean isEnabled() { return enabled; }
    public int source() { return source; }
    public String lastError() { return lastError; }
    public long frames() { return frames; }

    /** 当前方向：正前 / 左前 / 左侧 / 右前 / 右侧 / unknown */
    public String sector() { return burstSector; }

    /** 归一化方位，-1 = 最左，0 = 正前，+1 = 最右（依标定） */
    public double ratio() { return burstRatio; }

    /** 最近一次说话的方向（说话人方位） */
    public double lag() { return burstLag; }
    public double rho() { return burstRho; }
    public long ts() { return burstTs; }

    /** 距上次有人说话过去了多久（ms），没人说过返回 -1 */
    public long sinceVoiceMs() { return burstTs == 0 ? -1 : System.currentTimeMillis() - burstTs; }

    public void setEnabled(boolean on) {
        enabled = on;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply();
        if (on) start(); else stop();
    }

    public void setSource(int s) {
        source = s;
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SRC, s).apply();
        if (running) { stop(); start(); }
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        lastError = "";
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "doa");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
        release(rec);
        rec = null;
    }

    private void loop() {
        while (running) {
            if (!openMic()) {
                // 麦克风被唤醒引擎/ASR 占着时打不开很正常，退避重试，别刷屏
                try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
                continue;
            }
            if (chCount < 2) {
                lastError = "通道数 " + chCount + "（拿到的是单声道，无法定位）";
                release(rec); rec = null;
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                continue;
            }
            short[] buf = new short[WIN * chCount * 4];
            while (running && rec != null) {
                int n;
                try { n = rec.read(buf, 0, buf.length); }
                catch (Throwable t) { break; }
                if (n <= 0) break;
                int perCh = n / chCount;
                for (int off = 0; off + WIN <= perCh; off += WIN) {
                    feed(buf, off * chCount, perCh);
                }
            }
            release(rec); rec = null;
        }
    }

    private boolean openMic() {
        try {
            int minBuf = AudioRecord.getMinBufferSize(
                    RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) { lastError = "STEREO 不被支持"; return false; }
            AudioRecord r = new AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, RATE));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                release(r);
                lastError = "AudioRecord 初始化失败（多半被唤醒引擎占着）";
                return false;
            }
            chCount = r.getChannelCount();
            r.startRecording();
            rec = r;
            lastError = "";
            return true;
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            return false;
        }
    }

    /** 处理一窗数据并更新状态。 */
    private void feed(short[] raw, int base, int strideFrames) {
        short[] a = new short[WIN], b = new short[WIN];
        for (int i = 0; i < WIN; i++) {
            a[i] = raw[base + i * chCount];
            b[i] = raw[base + i * chCount + 1];
        }
        frames++;
        double e = rms(a);
        if (e < RMS_GATE) return;                       // 静默帧不参与
        double[] est = estimate(a, b);
        double lag = est[0], rho = est[1];
        if (rho < 0.05) return;                         // 两路不像，不给方向
        push(lag);
        double med = median();
        long now = System.currentTimeMillis();
        voiceLag = med; voiceRho = rho; voiceTs = now;
        // 语音突发：静音超过 700ms 再开口算新的一段
        if (burstTs == 0 || now - burstTs > 700) burstFirstTs = now;
        burstTs = now;
        burstLag = med;
        burstRho = rho;
        burstRatio = norm(med);
        burstSector = sectorOf(burstRatio);
        dirFrom = "doa";
    }

    private void push(double v) {
        hist[histI] = v;
        histI = (histI + 1) % SMOOTH;
        if (histN < SMOOTH) histN++;
    }

    private double median() {
        int n = histN;
        if (n == 0) return 0;
        double[] c = new double[n];
        System.arraycopy(hist, 0, c, 0, n);
        Arrays.sort(c);
        return n % 2 == 1 ? c[n / 2] : 0.5 * (c[n / 2 - 1] + c[n / 2]);
    }

    /** 把 lag 归一到 [-1,1]（-1 最左、+1 最右）。 */
    public double norm(double lag) {
        double r = lag * calSign / calRef;
        if (r > 1) r = 1;
        if (r < -1) r = -1;
        return r;
    }

    public static String sectorOf(double ratio) {
        double r = ratio;
        if (r >= 0.75) return "右侧";
        if (r >= 0.25) return "右前";
        if (r > -0.25) return "正前";
        if (r > -0.75) return "左前";
        return "左侧";
    }

    /**
     * 标定：用户站在 <b>已知一侧</b> 说话时调用，把当前的 lag 符号与幅度记下来。
     * side = "left" 表示"此刻人在机器人的左边"。
     */
    public synchronized String calibrate(String side) {
        if (burstTs == 0 || System.currentTimeMillis() - burstTs > 8000) {
            return "{\"ok\":false,\"why\":\"最近 8 秒内没听到有效语音，先说句话再标定\"}";
        }
        double lag = burstLag;
        if (Math.abs(lag) < 0.3) {
            return "{\"ok\":false,\"why\":\"当前 lag=" + f2(lag) + " 太小，人几乎在正前方，标不出左右。站到侧面再说一次\"}";
        }
        double sign = "left".equalsIgnoreCase(side) ? -1 : 1;
        // 期望：站在左边 → ratio 应为负 → lag*sign/ref < 0 → sign = -sign(lag)
        calSign = (lag > 0) ? -sign : sign;
        calRef = Math.abs(lag);
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(KEY_SIGN, (float) calSign)
                .putFloat(KEY_REF, (float) calRef)
                .apply();
        burstRatio = norm(lag);
        burstSector = sectorOf(burstRatio);
        return "{\"ok\":true,\"side\":\"" + J.esc(side) + "\",\"lag\":" + f2(lag)
                + ",\"cal_sign\":" + (int) calSign + ",\"cal_ref\":" + f2(calRef)
                + ",\"sector\":\"" + J.esc(burstSector) + "\"}";
    }

    /**
     * 兜底方位来源：<b>触摸</b>。摸左脸 = 人在左边，这是确定信息，不是估计。
     *
     * <p>为什么必须留这条：2026-09 真机实测（{@code POST /doa {"probe":true}}）发现，
     * 本机 audio_policy.conf 虽然同时声明了 BUILTIN_MIC 和 BACK_MIC（两个物理麦），
     * 但 HAL 打开 CHANNEL_IN_STEREO 时给出的两路**逐采样完全相同**（差异计数 0）
     * —— 是同一路复制两份，物理上不存在方向信息，DOA 这条路在本 ROM 上走不通。
     *
     * <p>所以"知道用户在哪"改用本来就拿得到的信号：他被摸的那半边脸 / 头顶。
     * 精度不如 DOA，但它永远可用、永不失效 —— 比一个装出来但永远返回 unknown
     * 的定位器诚实得多。</p>
     *
     * @param side lface（左侧）| rface（右侧）| head（正前）
     */
    public void noteTouch(String side) {
        double ratio;
        if ("lface".equals(side)) ratio = -1;
        else if ("rface".equals(side)) ratio = 1;
        else ratio = 0;
        burstLag = ratio * calRef;
        burstRho = 1.0;                        // 触摸是确定信息，置信度拉满
        burstRatio = ratio;
        burstSector = sectorOf(ratio);
        burstTs = System.currentTimeMillis();
        if (burstFirstTs == 0) burstFirstTs = burstTs;
        dirFrom = "touch";
    }

    /** 当前方位的来源：doa | touch | "" */
    public String dirFrom() { return dirFrom; }

    /** 在一次突发语音结束后重新判一次方位（打断/唤醒时用），返回方向名。 */
    public String freshSector(long maxAgeMs) {
        if (burstTs == 0 || System.currentTimeMillis() - burstTs > maxAgeMs) return "";
        return burstSector;
    }

    /** 把方向翻译成一个"看向用户"的动作名（不认识的返回空）。 */
    public String lookMotion() {
        String s = burstSector;
        if (s == null) return "";
        if ("左侧".equals(s) || "左前".equals(s)) return "stTurnLeft";
        if ("右侧".equals(s) || "右前".equals(s)) return "stTurnRight";
        return "";
    }

    public String stateJson() {
        long now = System.currentTimeMillis();
        return "{\"on\":" + enabled
                + ",\"running\":" + running
                + ",\"ch\":" + chCount
                + ",\"src\":" + source
                + ",\"frames\":" + frames
                + ",\"sector\":\"" + J.esc(burstSector) + "\""
                + ",\"from\":\"" + J.esc(dirFrom) + "\""
                + ",\"ratio\":" + f3(burstRatio)
                + ",\"lag\":" + f2(burstLag)
                + ",\"rho\":" + f4(burstRho)
                + ",\"voice_ms\":" + (burstTs == 0 ? -1 : now - burstTs)
                + ",\"cal_sign\":" + (int) calSign
                + ",\"cal_ref\":" + f2(calRef)
                + ",\"err\":\"" + J.esc(lastError) + "\"}";
    }

    // ==================== 数学 ====================

    /**
     * 归一化互相关 + 抛物线插值。返回 {lag, 峰值相关系数, 0位移相关系数}。
     * lag &gt; 0 表示 B 通道比 A 通道"晚"。
     */
    static double[] estimate(short[] a, short[] b) {
        int n = Math.min(a.length, b.length);
        double best = -2, zero = 0;
        int bestLag = 0;
        for (int lag = -MAX_LAG; lag <= MAX_LAG; lag++) {
            double c = corr(a, b, n, lag);
            if (lag == 0) zero = c;
            if (c > best) { best = c; bestLag = lag; }
        }
        // 抛物线插值，把整数 lag 细化到亚采样
        double y0 = corr(a, b, n, Math.max(-MAX_LAG, bestLag - 1));
        double y1 = best;
        double y2 = corr(a, b, n, Math.min(MAX_LAG, bestLag + 1));
        double den = y0 - 2 * y1 + y2;
        double frac = Math.abs(den) > 1e-12 ? 0.5 * (y0 - y2) / den : 0;
        if (frac > 1) frac = 1;
        if (frac < -1) frac = -1;
        return new double[]{ bestLag + frac, best, zero };
    }

    /** 零均值归一化互相关，只在重叠区上算。 */
    private static double corr(short[] a, short[] b, int n, int lag) {
        int lo = Math.max(0, lag), hi = Math.min(n, n + lag);
        int m = hi - lo;
        if (m < 64) return -2;
        double sa = 0, sb = 0;
        for (int i = lo; i < hi; i++) { sa += a[i]; sb += b[i - lag]; }
        double ma = sa / m, mb = sb / m;
        double num = 0, va = 0, vb = 0;
        for (int i = lo; i < hi; i++) {
            double x = a[i] - ma, y = b[i - lag] - mb;
            num += x * y; va += x * x; vb += y * y;
        }
        if (va <= 1e-9 || vb <= 1e-9) return -2;
        return num / Math.sqrt(va * vb);
    }

    private static long maxAbs(short[] d) {
        long mx = 0;
        for (short v : d) { long x = Math.abs((long) v); if (x > mx) mx = x; }
        return mx;
    }

    private static double rms(short[] d) {
        double s = 0;
        for (short v : d) s += (double) v * v;
        return d.length == 0 ? 0 : Math.sqrt(s / d.length);
    }

    private static void release(AudioRecord r) {
        if (r == null) return;
        try { if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) r.stop(); } catch (Throwable ignored) {}
        try { r.release(); } catch (Throwable ignored) {}
    }

    static String f2(double v) { return String.format(java.util.Locale.US, "%.2f", v); }
    static String f3(double v) { return String.format(java.util.Locale.US, "%.3f", v); }
    static String f4(double v) { return String.format(java.util.Locale.US, "%.4f", v); }
    static String f0(double v) { return String.format(java.util.Locale.US, "%.0f", v); }
}
