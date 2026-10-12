package com.ledi.agent.voice;

import com.ledi.agent.core.J;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * LocalWake —— **自建本地唤醒**的特征与匹配内核（MFCC + 能量 VAD + DTW）。
 *
 * <h3>为什么必须自建</h3>
 * 原厂那套百度离线引擎在本机**整族失效**，而且各有独立证据：
 * <ul>
 *   <li><b>KWS</b>：4 次真人实测 + 同一样本 8 档增益回灌，`hits` 恒 0；</li>
 *   <li><b>离线 ASR</b>：v183 本轮实测 `/asrtest` 回灌真人 PCM 得 {@code events=0}、
 *       {@code vol_max=0} —— 连"引擎吃进音频"都没发生；</li>
 *   <li>根因在**模型 / 授权层**（换证服务端已死、`VerifyLicense=FAIL_PACKAGE_NAME`），
 *       软件侧的增益 / 距离 / 词表三条路都堵死了。</li>
 * </ul>
 * 所以唤醒只能自己算：自己开麦、自己提特征、自己比对。本类是那个内核。
 *
 * <h3>三个刻意的取舍</h3>
 * <ol>
 *   <li><b>丢掉 c0</b>：c0 就是帧能量，而这台机器的麦克风**动态范围极窄**
 *       （实测 20~30cm 正常音量必削顶 32768，1m 外只剩 3255）。
 *       留着 c0，距离度量就被"说话远近"主导 —— 喊近一点就崩。能量改由 VAD 单独用。</li>
 *   <li><b>CMN（倒谱均值归一）</b>：按整段减均值，抹掉通道/麦克风频响。
 *       这是"一个模板跨距离可用"的前提，也是模板法唯一便宜的鲁棒手段。</li>
 *   <li><b>VAD 先切段、再算距离</b>：不是逐帧滑窗硬算。逐帧滑窗在 MT6580 上是
 *       每秒上百次 DTW，扛不住；而且真实系统本来就是"听到一段语音才去认"。</li>
 * </ol>
 *
 * <h3>与 PC 参考实现的关系（★ 反自参考）</h3>
 * 同一套公式在 PC 上有一份同公式的 Python 参考实现，
 * 用来在**没有硬件**的情况下快速迭代参数。两份实现互相独立，
 * 靠 {@code /kws/probe} 与 {@code /kws/test} 回灌**同一段 PCM** 对差：
 * 帧数、能量指纹、MFCC 指纹、DTW 距离四层逐级比。
 * 只对最后一个距离就是把两层错误抵消掉的可能也一起验了；分层比能定位到出错那一层。
 */
public final class LocalWake {

    public static final int SR = 16000;
    public static final int FRAME = 400;          // 25ms
    public static final int HOP = 160;            // 10ms
    public static final int NFFT = 512;
    public static final int NMEL = 26;
    public static final int NCEPS = 13;           // 保留 c1..c13（c0 丢弃）
    static final double PREEMPH = 0.97;
    static final double FMIN = 80.0;
    static final double FMAX = 7600.0;

    private static final double[][] MEL = new double[NMEL][NFFT / 2 + 1];
    private static final double[][] DCT = new double[NCEPS][NMEL];
    private static final double[] HAMM = new double[FRAME];

    static {
        // ---- mel 滤波器组：与 Python 版逐式一致（整数 bin + 三角） ----
        double ml = hz2mel(FMIN), mh = hz2mel(FMAX);
        double[] pts = new double[NMEL + 2];
        for (int i = 0; i < pts.length; i++) {
            pts[i] = mel2hz(ml + (mh - ml) * i / (NMEL + 1));
        }
        int[] bins = new int[NMEL + 2];
        for (int i = 0; i < bins.length; i++) {
            int b = (int) Math.floor((NFFT + 1) * pts[i] / SR);
            if (b < 0) b = 0;
            if (b > NFFT / 2) b = NFFT / 2;
            bins[i] = b;
        }
        for (int m = 1; m <= NMEL; m++) {
            int l = bins[m - 1], c = bins[m], r = bins[m + 1];
            if (c == l) c = l + 1;
            if (r <= c) r = c + 1;
            if (r > NFFT / 2) r = NFFT / 2;
            for (int k = l; k < c && k <= NFFT / 2; k++) {
                if (c > l && k >= 0) MEL[m - 1][k] = (k - l) / (double) (c - l);
            }
            for (int k = c; k <= r && k <= NFFT / 2; k++) {
                if (r > c && k >= 0) MEL[m - 1][k] = (r - k) / (double) (r - c);
            }
        }
        // ---- DCT-II（正交归一化，同 Python） ----
        double s = Math.sqrt(2.0 / NMEL);
        for (int i = 0; i < NCEPS; i++) {
            for (int j = 0; j < NMEL; j++) {
                DCT[i][j] = Math.cos(Math.PI / NMEL * (i + 1) * (j + 0.5)) * s;
            }
        }
        for (int n = 0; n < FRAME; n++) {
            HAMM[n] = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * n / (FRAME - 1));
        }
    }

    private static double hz2mel(double f) { return 2595.0 * Math.log10(1.0 + f / 700.0); }

    private static double mel2hz(double m) { return 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0); }

    private LocalWake() {}

    // ================================================================ 读音频

    /** 读 16k/单声道/16bit 小端裸 PCM 或带头的 WAV，返回 [-1,1] 的 float 采样。 */
    static float[] readPcm(String path) throws Exception {
        byte[] raw = readAll(new File(path));
        if (raw.length >= 12 && raw[0] == 'R' && raw[1] == 'I' && raw[2] == 'F' && raw[3] == 'F') {
            int off = 12;
            while (off + 8 <= raw.length) {
                String id = new String(raw, off, 4, "US-ASCII");
                int sz = le32(raw, off + 4);
                if ("data".equals(id)) {
                    byte[] d = new byte[Math.max(0, Math.min(sz, raw.length - off - 8))];
                    System.arraycopy(raw, off + 8, d, 0, d.length);
                    raw = d;
                    break;
                }
                off += 8 + sz + (sz & 1);
            }
        }
        int n = raw.length / 2;
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            int lo = raw[i * 2] & 0xff, hi = raw[i * 2 + 1];
            x[i] = (short) (lo | (hi << 8)) / 32768.0f;
        }
        return x;
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | ((b[o + 1] & 0xff) << 8)
                | ((b[o + 2] & 0xff) << 16) | ((b[o + 3] & 0xff) << 24);
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) Math.min(Integer.MAX_VALUE, f.length())];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            if (off == buf.length) return buf;
            byte[] cut = new byte[off];
            System.arraycopy(buf, 0, cut, 0, off);
            return cut;
        } finally {
            try { in.close(); } catch (Throwable ignore) {}
        }
    }

    // ================================================================ 分帧

    static int frameCount(int len) {
        if (len < FRAME) return 0;
        return 1 + (len - FRAME) / HOP;
    }

    /** 第 i 帧的原始采样（不足则零填充，与 Python 的 pad 行为一致）。 */
    private static double[] frame(float[] x, int i) {
        double[] f = new double[FRAME];
        int base = i * HOP;
        for (int k = 0; k < FRAME; k++) {
            int p = base + k;
            f[k] = p < x.length ? x[p] : 0.0;
        }
        return f;
    }

    /** 每帧 dB 能量（0dB = 满量程），与 Python frame_energy_db 同式。 */
    static float[] frameEnergyDb(float[] x) {
        int nf = frameCount(x.length);
        float[] e = new float[nf];
        for (int i = 0; i < nf; i++) {
            double[] f = frame(x, i);
            double s = 0;
            for (int k = 0; k < FRAME; k++) s += f[k] * f[k];
            s /= FRAME;
            e[i] = (float) (10.0 * Math.log10(s + 1e-12) + 90.0);
        }
        return e;
    }

    // ================================================================ VAD

    /**
     * 能量 VAD。返回 {@code int[][]}，每个元素是 {起帧, 止帧(半开)}。
     *
     * <p>与 Python 版同式：噪声底取 **20 分位**（不是"前 N 帧"——这台机器一开机就在响，
     * 灯效/风机都在，前 N 帧不一定干净），阈值 = max(底+margin, abs_db)，
     * 间隔 &le; hang 的语音块并起来，短于 min_frames 的块丢掉。</p>
     */
    static int[][] vad(float[] x, double marginDb, int minFrames, int hangFrames, double absDb) {
        float[] e = frameEnergyDb(x);
        int n = e.length;
        if (n == 0) return new int[0][];
        double floor = percentile(e, 20.0);
        double thr = Math.max(floor + marginDb, absDb);
        List<int[]> on = new ArrayList<int[]>();
        for (int i = 0; i < n; i++) if (e[i] > thr) on.add(new int[]{i});
        if (on.isEmpty()) return new int[0][];
        List<int[]> blocks = new ArrayList<int[]>();
        int start = -1, prev = -1;
        for (int i = 0; i < n; i++) {
            if (e[i] <= thr) continue;
            if (start < 0) { start = i; prev = i; }
            else if (i - prev <= hangFrames) { prev = i; }
            else { blocks.add(new int[]{start, prev + 1}); start = i; prev = i; }
        }
        if (start >= 0) blocks.add(new int[]{start, prev + 1});
        List<int[]> segs = new ArrayList<int[]>();
        for (int[] b : blocks) if (b[1] - b[0] >= minFrames) segs.add(b);
        return segs.toArray(new int[0][]);
    }

    /** 线性插值分位数，与 numpy.percentile 默认方法一致。 */
    private static double percentile(float[] a, double p) {
        float[] c = a.clone();
        Arrays.sort(c);
        if (c.length == 1) return c[0];
        double pos = p / 100.0 * (c.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return c[lo];
        double f = pos - lo;
        return c[lo] * (1 - f) + c[hi] * f;
    }

    // ================================================================ MFCC

    /** 迭代 radix-2 FFT（原地）。NFFT=512 固定。 */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j &= ~bit;
            j |= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1.0, ci = 0.0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr;        im[a] += xi;
                    double nr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = nr;
                }
            }
        }
    }

    /** MFCC（丢 c0），cmn=true 时按整段减均值。 */
    static float[][] mfcc(float[] x, boolean cmn) {
        int nf = frameCount(x.length);
        float[][] c = new float[nf][NCEPS];
        if (nf == 0) return c;
        // pre-emphasis（y[0]=x[0]）
        double[] y = new double[x.length];
        if (x.length > 0) y[0] = x[0];
        for (int i = 1; i < x.length; i++) y[i] = x[i] - PREEMPH * x[i - 1];
        double[] re = new double[NFFT], im = new double[NFFT];
        double[] melE = new double[NMEL];
        for (int i = 0; i < nf; i++) {
            int base = i * HOP;
            Arrays.fill(re, 0.0);
            Arrays.fill(im, 0.0);
            for (int k = 0; k < FRAME; k++) {
                int p = base + k;
                double v = (p < y.length ? y[p] : 0.0) * HAMM[k];
                re[k] = v;
            }
            fft(re, im);
            for (int m = 0; m < NMEL; m++) {
                double s = 0;
                for (int k = 0; k <= NFFT / 2; k++) {
                    double w = MEL[m][k];
                    if (w != 0.0) s += w * (re[k] * re[k] + im[k] * im[k]);
                }
                melE[m] = Math.log(s + 1e-10);
            }
            for (int d = 0; d < NCEPS; d++) {
                double s = 0;
                for (int m = 0; m < NMEL; m++) s += DCT[d][m] * melE[m];
                c[i][d] = (float) s;
            }
        }
        if (cmn) {
            for (int d = 0; d < NCEPS; d++) {
                double m = 0;
                for (int i = 0; i < nf; i++) m += c[i][d];
                m /= nf;
                for (int i = 0; i < nf; i++) c[i][d] -= m;
            }
        }
        return c;
    }

    /** 一阶差分（窗口 ±2 帧，与 Python d_mfcc 同式，edge pad=edge）。 */
    static float[][] delta(float[][] c) {
        int n = c.length;
        float[][] d = new float[n][NCEPS];
        for (int t = 0; t < n; t++) {
            for (int k = 0; k < NCEPS; k++) {
                double p4 = c[Math.min(n - 1, t + 2)][k];
                double p0 = c[Math.max(0, t - 2)][k];
                d[t][k] = (float) ((p4 - p0) / 4.0);
            }
        }
        return d;
    }

    /** 整段特征：[MFCC(CMN) | ΔMFCC]。 */
    static float[][] features(float[] x, boolean useDelta) {
        float[][] c = mfcc(x, true);
        if (!useDelta) return c;
        float[][] d = delta(c);
        float[][] out = new float[c.length][NCEPS * 2];
        for (int i = 0; i < c.length; i++) {
            System.arraycopy(c[i], 0, out[i], 0, NCEPS);
            System.arraycopy(d[i], 0, out[i], NCEPS, NCEPS);
        }
        return out;
    }

    /** 取某个 VAD 段（帧下标）的特征，前后各留 pad 帧余量。 */
    static float[][] segmentFeatures(float[] x, int i0, int i1, int pad, boolean useDelta) {
        int a = Math.max(0, (i0 - pad) * HOP);
        int b = Math.min(x.length, (i1 + pad) * HOP + FRAME);
        if (b <= a) return new float[0][];
        float[] sub = new float[b - a];
        System.arraycopy(x, a, sub, 0, sub.length);
        return features(sub, useDelta);
    }

    // ================================================================ DTW

    /**
     * 归一化 DTW 距离（越小越像）。
     *
     * <p>★ 这里刻意用**朴素实现**，不做整行向量化。PC 侧的 Python 版曾把整行用 numpy
     * 一次算完，结果把 DTW 的 {@code left}（同行的前一列）依赖算丢了，
     * 所有距离静默退化成 1e18 附近（约 5.9e15）——不报错，只"看起来很大"。
     * 在设备上数据量小（只在 VAD 段上算），朴素实现足够快，也不必再冒那个险。</p>
     */
    static double dtw(float[][] a, float[][] b, double band) {
        int n = a.length, m = b.length;
        if (n == 0 || m == 0) return Double.POSITIVE_INFINITY;
        int w = Math.max((int) (band * Math.max(n, m)), Math.abs(n - m) + 1);
        double INF = 1e18;
        double[][] D = new double[n + 1][m + 1];
        for (double[] r : D) Arrays.fill(r, INF);
        D[0][0] = 0.0;
        for (int i = 1; i <= n; i++) {
            int lo = Math.max(1, i - w), hi = Math.min(m, i + w);
            for (int j = lo; j <= hi; j++) {
                double d = 0;
                for (int k = 0; k < a[i - 1].length && k < b[j - 1].length; k++) {
                    double t = a[i - 1][k] - b[j - 1][k];
                    d += t * t;
                }
                d = Math.sqrt(d);
                double p = D[i - 1][j];
                if (D[i][j - 1] < p) p = D[i][j - 1];
                if (D[i - 1][j - 1] < p) p = D[i - 1][j - 1];
                D[i][j] = d + p;
            }
        }
        return D[n][m] / (double) (n + m);
    }

    // ================================================================ 指纹（交叉对差用）

    private static double sumAbs(float[][] m) {
        double s = 0;
        for (float[] r : m) for (float v : r) s += Math.abs(v);
        return s;
    }

    private static double sum(float[] v) {
        double s = 0;
        for (float x : v) s += x;
        return s;
    }

    /**
     * 一段音频的**分层指纹** —— 供 PC 与设备对差。
     *
     * <p>为什么要分层：只比最后的 DTW 距离，可能两层错误互相抵消（比如特征差一点、
     * DTW 也差一点，凑巧结果相近）。分层比能定位到"到底哪一层实现不一致"。</p>
     */
    public static String probe(String path) {
        try {
            float[] x = readPcm(path);
            float[] e = frameEnergyDb(x);
            float[][] c = mfcc(x, true);
            float[][] cf = mfcc(x, false);
            float[][] f = features(x, true);
            int[][] segs = vad(x, 8.0, 25, 18, 38.0);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"ok\":true,\"file\":\"").append(J.esc(path)).append('"');
            sb.append(",\"samples\":").append(x.length);
            sb.append(",\"frames\":").append(e.length);
            sb.append(",\"energy_sum\":").append(fmt(sum(e)));
            sb.append(",\"energy_first5\":").append(arr(e, 5));
            sb.append(",\"mfcc_n\":").append(c.length).append(",\"mfcc_d\":").append(NCEPS);
            sb.append(",\"mfcc_sumabs\":").append(fmt(sumAbs(c)));
            sb.append(",\"mfcc_frame0\":").append(arr(c.length > 0 ? c[0] : new float[0], 13));
            sb.append(",\"mfcc_raw_c0_sumabs\":").append(fmt(sumAbs(cf)));
            sb.append(",\"feat_sumabs\":").append(fmt(sumAbs(f)));
            sb.append(",\"vad\":[");
            for (int i = 0; i < segs.length; i++) {
                if (i > 0) sb.append(',');
                sb.append('[').append(segs[i][0]).append(',').append(segs[i][1]).append(']');
            }
            sb.append("]}");
            return sb.toString();
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + J.esc(String.valueOf(t)) + "\"}";
        }
    }

    private static String arr(float[] v, int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(n, v.length); i++) {
            if (i > 0) sb.append(',');
            sb.append(fmt(v[i]));
        }
        return sb.append(']').toString();
    }

    /** 固定 6 位小数，避免 JSON 里出现 float 的 1.0000001 噪声。 */
    static String fmt(double d) {
        if (Double.isInfinite(d)) return "1e30";
        return String.format(java.util.Locale.US, "%.6f", d);
    }

    /** 收集一个目录下（或其列表）的 pcm 当模板。 */
    public static String trainFrom(List<String> paths, boolean useDelta) {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"tpl\":[");
        int n = 0;
        for (String p : paths) {
            try {
                float[] x = readPcm(p);
                int[][] segs = vad(x, 8.0, 25, 18, 38.0);
                for (int[] s : segs) {
                    float[][] f = segmentFeatures(x, s[0], s[1], 2, useDelta);
                    if (f.length < 10) continue;
                    TPL.add(f);
                    if (n > 0) sb.append(',');
                    sb.append("{\"src\":\"").append(J.esc(new File(p).getName()))
                            .append("\",\"frames\":").append(f.length)
                            .append(",\"seg\":[").append(s[0]).append(',').append(s[1]).append("]}");
                    n++;
                }
            } catch (Throwable t) {
                if (n > 0) sb.append(',');
                sb.append("{\"error\":\"").append(J.esc(p + ": " + t)).append("\"}");
            }
        }
        sb.append("],\"count\":").append(TPL.size()).append('}');
        return sb.toString();
    }

    /** 已装载的模板（进程内）。 */
    public static final List<float[][]> TPL = new ArrayList<float[][]>();

    public static void clearTemplates() { TPL.clear(); }

    /**
     * 在整段音频上滑窗，返回最小距离（含位置与窗口数）。
     *
     * <p>与 PC 版 `score_file` 同式：窗口长度随模板长度，步长 stride，
     * {@code activeOnly=true} 时只在 VAD 活跃区附近评（真实系统就是这样，
     * 否则每秒上百次 DTW 在 MT6580 上扛不住）。</p>
     */
    public static String scoreFile(String path, int stride, boolean useDelta, double band,
                            boolean activeOnly) {
        try {
            float[] x = readPcm(path);
            int nf = frameCount(x.length);
            if (TPL.isEmpty()) return "{\"ok\":false,\"error\":\"没有模板\"}";
            if (nf == 0) return "{\"ok\":false,\"error\":\"音频太短\"}";
            int lmin = Integer.MAX_VALUE, lmax = 0;
            for (float[][] t : TPL) { lmin = Math.min(lmin, t.length); lmax = Math.max(lmax, t.length); }
            boolean[] active = null;
            if (activeOnly) {
                // ★ 只放**原始 VAD 掩码**，不要再膨胀 8 帧：窗口侧已经按 [i-8, i+Lmax+8)
                //   查过一次了。两边各膨胀一次 = 相对 PC 多膨胀 8 帧 ⇒ 判据分叉，
                //   而且分叉方向是"设备比 PC 多算一些窗口"，DTW 取 min 后设备更容易偏小。
                active = new boolean[nf];
                int[][] segs = vad(x, 8.0, 25, 18, 38.0);
                for (int[] s : segs) {
                    for (int i = Math.max(0, s[0]); i < Math.min(nf, s[1]); i++) active[i] = true;
                }
            }
            double best = Double.POSITIVE_INFINITY;
            double bestAt = -1;
            int windows = 0;
            for (int i = 0; i + lmax <= nf; i += stride) {
                if (activeOnly) {
                    boolean any = false;
                    for (int k = Math.max(0, i - 8); k < Math.min(nf, i + lmax + 8); k++) {
                        if (active[k]) { any = true; break; }
                    }
                    if (!any) continue;
                }
                windows++;
                for (float[][] t : TPL) {
                    int j = Math.min(i + t.length, nf);
                    if (j - i < Math.max(6, t.length / 2)) continue;
                    int a = i * HOP, b = Math.min(x.length, j * HOP + FRAME);
                    float[] sub = new float[b - a];
                    System.arraycopy(x, a, sub, 0, sub.length);
                    float[][] f = features(sub, useDelta);
                    double d = dtw(t, f, band);
                    if (d < best) { best = d; bestAt = a / (double) SR; }
                }
            }
            return "{\"ok\":true,\"file\":\"" + J.esc(path) + "\""
                    + ",\"tpl\":" + TPL.size()
                    + ",\"tpl_frames\":[" + lmin + "," + lmax + "]"
                    + ",\"windows\":" + windows
                    + ",\"frames\":" + nf
                    + ",\"stride\":" + stride
                    + ",\"best\":" + fmt(best)
                    + ",\"best_at_s\":" + fmt(bestAt) + "}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + J.esc(String.valueOf(t)) + "\"}";
        }
    }
}
