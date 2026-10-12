package com.ledi.agent.net;

import com.ledi.agent.core.J;

import com.ledi.agent.core.BootLog;

import com.ledi.agent.core.AgentService;

import com.ledi.agent.ui.SleepMode;
import com.ledi.agent.voice.Speaker;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;

/**
 * UDP 存在广播 + 反向接收 —— 一条 socket 干两件事。
 *
 * <h3>发（老功能）</h3>
 * 每 2 秒往 {@code 255.255.255.255:8899} 喊一条自己的地址，
 * 手机 App / 电脑端听到就知道机器人在哪、端口是多少。
 * 电脑端 {@code robot_finder.py} 的 Sniffer 听的就是它 ——
 * 那套代码刻意不去定时扫网段，因为"机器人自己一直在喊"这件事是免费的。
 *
 * <h3>收（新功能）</h3>
 * 同一端口上等电脑端的反向广播 {@code {"ledi_pc":1,"ip":...,"port":8080}}，
 * 收到就把 {@link Speaker} 的网关地址改成对方。于是：
 * <ul>
 *   <li>「电脑端换了 IP」——机器人自己跟上，不用再 adb 进去改设置；</li>
 *   <li>「换了另一台电脑装电脑端」——同样自动跟上。</li>
 * </ul>
 *
 * <h3>为什么一条 socket</h3>
 * UDP 没有连接概念，bind 住 8899 之后既能 {@code sendto} 广播也能 {@code recvfrom}。
 * 拆成两个 socket 反而要抢同一个端口，还得处理 SO_REUSEADDR 的歧义。
 *
 * <h3>收包必须宽容</h3>
 * 同一局域网里可能有别人的 UDP 广播、别的程序发的东西。所以：解析失败的丢、
 * 没有 {@code ledi_pc} 标记的丢、自己发的包也丢（我们发的是 {@code ledi} 标记，
 * 天然不冲突）。一个坏包绝不能让这个线程退出 —— 退出了机器人就再也不广播，
 * 表现成「电脑端明明开着，机器人却老要手动填地址」这种很难查的问题。
 */
public class UdpBeacon {

    /** 收到电脑端广播后的回调。**跑在监听线程里**，实现方要保证轻量。 */
    public interface PcSeen {
        void onPc(String ip, int port);
    }

    /** 正常广播间隔：电脑端 / 手机端靠它发现机器人。 */
    private static final int SEND_GAP_MS = 2000;

    /**
     * ★ 屏保时的广播间隔：拉长到 15s。
     *
     * <p>屏保 = 用户不在场，这时"电脑端能多快发现我"不重要，但**广播不能停** ——
     * 停了则用户回到电脑前要先手动填 IP。15s 是折中：最坏多等 15 秒，
     * 换来的是这个线程的唤醒次数降到原来的 1/7.5。</p>
     */
    private static final int QUIET_GAP_MS = 15000;

    /** 收包等待的下限：防止"局域网里一直有噪音包"把循环变成忙转。 */
    private static final int RECV_MIN_MS = 200;

    /** 收包等待的上限：即使周期被拉长，也要偶尔回头看一眼状态有没有变。 */
    private static final int RECV_MAX_MS = 5000;

    /** 暂停期间的轮询周期：只留着收电脑端的反向广播，自己一个字不发。 */
    private static final int PAUSE_POLL_MS = 15000;

    /**
     * ★★ v142：暂停（屏保）。
     *
     * <p>用户 2026-09-24 要求「屏保期间心跳停用」。注意这和 v140 做的"降频"不是一回事：
     * 降频（2s → 15s）只是少发，仍然是"在往外发东西"；用户要的是<b>不发</b>。
     * 所以这里不是把周期拉长，而是整条<b>静默</b> —— 屏保期间一包不发。</p>
     *
     * <p>为什么还留着 socket 与线程：①收包是纯被动的，电脑端换 IP 时靠它把网关改过去，
     * 这是"屏保里唯一能自救的配置通路"；②这样恢复不需要重建 socket（重建要重绑端口，
     * 有绑失败的可能）。代价只是每 15s 醒一次看状态。</p>
     */
    private static volatile boolean paused;   // 静态：statusJson() 也是静态的，要能读到它

    public void setPaused(boolean p) {
        if (paused == p) return;                    // 幂等：屏保 enter/exit 都会被调用
        paused = p;
        BootLog.i("beacon: " + (p ? "★已暂停（屏保期间一包不发）" : "已恢复广播"));
    }

    public boolean paused() { return paused; }

    /**
     * ★★ 当前该用哪个周期 —— **唯一事实来源是 {@link SleepMode#sleeping} 本身**。
     *
     * <p>为什么不让 SleepMode 主动调一个 setQuiet()：那样"屏保"这件事就有了两个
     * 记录点（SleepMode 的状态 + 我们的标志位），任何一条异常路径（进程重启、
     * 屏保窗口被系统摘掉、异常退出）漏掉通知，降频就永远不生效，而且**看不出来**。
     * 直接读状态则不可能漏 —— 这也是"判据锁被测对象本身"的同一个道理。</p>
     */
    private static int gapMs() {
        return SleepMode.sleeping ? QUIET_GAP_MS : SEND_GAP_MS;
    }

    /** 供 /health：当前**实际**在用的广播周期（不报一个好看的值）。 */
    public static int periodMs() { return gapMs(); }

    /** 最近一次听到电脑端的证据（静态，供 /health 观察） */
    private static volatile long lastPcAt = 0;
    private static volatile String lastPcAddr = "";
    private static volatile int pcPackets = 0;

    private final Context ctx;
    private final PcSeen pc;
    private volatile boolean running;
    private DatagramSocket socket;

    public UdpBeacon(Context ctx) { this(ctx, null); }

    public UdpBeacon(Context ctx, PcSeen pc) {
        this.ctx = ctx;
        this.pc = pc;
    }

    public void start() {
        running = true;
        new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "ledi-udp-beacon").start();
    }

    private void loop() {
        long lastSend = 0;
        try {
            // bind 住 8899（而不是随便给个端口）—— 收电脑端广播必须有确定端口
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(AgentService.UDP_PORT));
        } catch (Exception e) {
            Log.e(AgentService.TAG, "beacon bind 失败", e);
            BootLog.w("beacon: bind " + AgentService.UDP_PORT + " 失败 " + e);
            running = false;
            return;
        }
        byte[] buf = new byte[2048];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (running) {
            long now = System.currentTimeMillis();
            // ★ 距离"下次该广播"还有多久 —— 拿它当收包等待时间。
            //   这样"发"和"收"共用一次唤醒：每 2s（屏保 15s）醒一回，
            //   而不是原来固定 500ms 醒一回、四次里三次纯属白醒。
            long due = lastSend + gapMs();
            int wait = paused ? PAUSE_POLL_MS
                    : (int) Math.max(RECV_MIN_MS, Math.min(RECV_MAX_MS, due - now));
            try {
                socket.setSoTimeout(wait);
                pkt.setLength(buf.length);          // receive 会改 length，必须每轮复位
                socket.receive(pkt);
                handle(pkt);
            } catch (SocketTimeoutException ignored) {
                // 正常：这段时间没有包 —— 正好说明该去广播了（或还没到点）
            } catch (Exception e) {
                if (!running) break;
                Log.w(AgentService.TAG, "beacon recv: " + e);
            }
            now = System.currentTimeMillis();
            // ★ 暂停时**只收不发**（见 setPaused 注释）。恢复后 lastSend 是旧值，
            //   一醒过来就会立刻补一包 —— 这正是要的：手机/电脑端能马上重新发现它。
            if (!paused && now - lastSend >= gapMs()) {
                lastSend = now;
                send();
            }
        }
        try { socket.close(); } catch (Exception ignored) { }
    }

    private void send() {
        try {
            byte[] msg = ("{\"ledi\":\"agent\",\"name\":\"LediAgent\",\"ip\":\""
                    + HttpServer.localIp() + "\",\"port\":"
                    + AgentService.HTTP_PORT + "}").getBytes("UTF-8");
            InetAddress bc = InetAddress.getByName("255.255.255.255");
            socket.send(new DatagramPacket(msg, msg.length, bc, AgentService.UDP_PORT));
        } catch (Exception e) {
            Log.w(AgentService.TAG, "beacon send: " + e);
        }
    }

    private void handle(DatagramPacket pkt) {
        String txt;
        try {
            txt = new String(pkt.getData(), pkt.getOffset(), pkt.getLength(),
                    "UTF-8").trim();
        } catch (Exception e) {
            return;
        }
        if (txt.length() < 2 || txt.charAt(0) != '{') return;
        String ip;
        int port;
        try {
            JSONObject o = new JSONObject(txt);
            if (o.optInt("ledi_pc", 0) != 1) return;      // 不是电脑端的广播
            ip = o.optString("ip", "");
            port = o.optInt("port", 0);
        } catch (Exception e) {
            return;                                        // 不是 JSON / 不是我们的东西
        }
        if (ip == null || ip.isEmpty()) {
            // 电脑端没报地址（多网卡时可能挑不出来）→ 用包来源地址兜底
            ip = pkt.getAddress() == null ? "" : pkt.getAddress().getHostAddress();
        }
        if (ip.isEmpty() || port <= 0 || port > 65535) return;
        if (ip.equals(HttpServer.localIp())) return;       // 自己发的

        lastPcAt = System.currentTimeMillis();
        lastPcAddr = ip + ":" + port;
        pcPackets++;
        if (pc != null) {
            try {
                pc.onPc(ip, port);
            } catch (Exception e) {
                Log.w(AgentService.TAG, "beacon onPc: " + e);
            }
        }
    }

    public void stop() {
        running = false;
        try { socket.close(); } catch (Exception ignored) { }
    }

    /** 供 /health：最近有没有听到电脑端、听到的是哪个地址。 */
    public static String statusJson() {
        long ago = lastPcAt == 0 ? -1 : (System.currentTimeMillis() - lastPcAt) / 1000;
        return "{\"heard\":" + (ago >= 0 && ago < 30)
                + ",\"pc\":\"" + J.esc(lastPcAddr) + "\""
                + ",\"last_ago\":" + ago
                + ",\"packets\":" + pcPackets
                + ",\"period_ms\":" + periodMs()          // ★ 当前真实周期（屏保会变大）
                + ",\"quiet\":" + SleepMode.sleeping
                + ",\"paused\":" + paused + "}";
    }
}
