package com.ledi.agent.net;

import com.ledi.agent.core.J;

import android.os.SystemClock;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 「机器人 ⇄ 电脑端」连通判定 —— 决定机器人屏幕上那行 IP 要不要显示。
 *
 * <h3>要解决的事</h3>
 * 机器人屏幕上会显示自己的地址 {@code 192.168.x.x:8899}，方便往电脑端 / 手机端里填。
 * 但电脑端一旦真的连上了，这行字就没人看了，还横在屏幕中间挡表情 ——
 * 所以要「连上就自动消失、断开再自己冒出来」。
 *
 * <h3>怎么算"与电脑端已连接"</h3>
 * 两个信号，任一成立即算连上：
 * <ol>
 *   <li>{@link #check} —— 主动对「齿轮设置里填的网关地址」做一次 <b>TCP 连接探测</b>。
 *       那个地址就是电脑端（乐迪控制器.exe）的地址，端口在监听就说明它活着、网也通。
 *       只 connect、不发任何 HTTP：不需要电脑端配合改造，也不会在它的访问日志里刷请求。</li>
 *   <li>{@link #markOk} —— {@link LoopbackRelay} 每次成功把主 App 的对话请求转发出去时打点。
 *       这是"真的通了一次"的硬证据，能让 IP 在用户第一次说话后<b>立刻</b>消失，
 *       不必等下一轮探测。</li>
 * </ol>
 * <p>判定带 {@link #OK_WINDOW_MS} 的宽限窗：连续两轮探测都失败才算断开，
 * 免得丢一个包就让屏幕上的 IP 忽隐忽现。
 *
 * <p>两个方向都覆盖了：探测 = 机器人→电脑端；{@code markOk} = 机器人→电脑端转发成功。
 * （电脑端主动来访问机器人那条路需要把 socket 对端地址透传进 CommandRouter，
 * 改动面大，这里不做 —— 上面两个信号已经够用。）
 */
public class PcLink {

    /** 最近一次成功之后多久内仍算"连着"：两轮探测周期，容忍偶发丢包 */
    private static final long OK_WINDOW_MS = 12000;
    /** 探测超时：局域网 1.5 秒足够；失败也别把后勤线程拖住 */
    private static final int PROBE_TIMEOUT_MS = 1500;
    /** 探测节流：最快 5 秒一次（与后勤轮询同频） */
    private static final long PROBE_MIN_GAP_MS = 5000;

    private static volatile long lastProbeAt = 0;
    private static volatile long lastOkAt = 0;
    private static volatile String lastTarget = "";
    private static volatile String lastError = "";
    private static volatile long probeCount = 0;
    private static volatile long okProbeCount = 0;

    /** 主 App 的对话请求真的转发到电脑端了 —— 由 {@link LoopbackRelay} 打点。 */
    public static void markOk(String target) {
        lastOkAt = SystemClock.uptimeMillis();
        if (target != null && !target.isEmpty()) lastTarget = target;
        lastError = "";
    }

    /** 当前是否算"与电脑端已连接"。零成本，UI 层可随便查。 */
    public static boolean linked() {
        long t = lastOkAt;
        return t > 0 && SystemClock.uptimeMillis() - t < OK_WINDOW_MS;
    }

    /**
     * 探测一次（内部节流，调用方可每轮后勤都调）。gatewayBase 允许为空串。
     *
     * @return 本次（或节流窗口内的上一次）判定结果
     */
    public static boolean check(String gatewayBase) {
        long now = SystemClock.uptimeMillis();
        if (now - lastProbeAt < PROBE_MIN_GAP_MS) return linked();
        lastProbeAt = now;

        String[] tgt = LoopbackRelay.parseTarget(gatewayBase);
        if (tgt == null) {
            // 没填电脑端地址 → 不可能连上；但仍保留 relay 打点带来的判定
            lastTarget = "";
            lastError = "未设置电脑端网关地址";
            return linked();
        }
        lastTarget = tgt[0] + ":" + tgt[1];
        probeCount++;
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(tgt[0], Integer.parseInt(tgt[1])),
                    PROBE_TIMEOUT_MS);
            okProbeCount++;
            markOk(lastTarget);
            return true;
        } catch (Exception e) {
            lastError = String.valueOf(e.getMessage());
            return linked();                 // 一次失败不清零，靠宽限窗兜
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    /** 供 /health 与日志观察（不含敏感信息）。 */
    public static String statusJson() {
        long t = lastOkAt;
        long ago = t == 0 ? -1 : (SystemClock.uptimeMillis() - t) / 1000;
        return "{\"linked\":" + linked()
                + ",\"target\":\"" + J.esc(lastTarget) + "\""
                + ",\"last_ok_ago\":" + ago
                + ",\"probes\":" + probeCount
                + ",\"probes_ok\":" + okProbeCount
                + ",\"error\":\"" + J.esc(lastError) + "\"}";
    }
}
