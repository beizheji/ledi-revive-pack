package com.ledi.agent.net;

import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 本机回环转发 —— 让主 App 的「云端对话端点」不再依赖编译期写死的网关 IP。
 *
 * 背景：xldrbt 主 App 里的对话地址（/projectapi/turingosapi、/projectapi/getuserid.do）
 * 是编译期常量，原厂没有留任何设置项，想改地址只能重打包。
 *
 * 做法：重打包时把它一次性地固定指向机器人本机（127.0.0.1:{@link #RELAY_PORT}）；
 * 本类在本机监听这个端口，把每个进来的连接<b>原样</b>转发到
 * 「齿轮设置」里填的网关地址（{@link GatewaySource}，即 POST /config 的 gateway 字段）。
 *
 * 于是换电脑 / 换网络只需要在机器人设置里改一下网关 IP，主 App 永远不用重打包。
 *
 * 为什么纯字节转发就够：对话内容是整体加密后的 HTTP over TCP，
 * 转发层不需要理解协议（也顺带天然兼容 POST / 分块传输 / keep-alive）。
 * 每连接重新读一次网关地址，所以改设置立即生效、无需重启 Agent。
 *
 * 本类不直接依赖 AgentService，网关地址通过 GatewaySource 注入 —— 便于脱机单测。
 */
public class LoopbackRelay {

    /** 必须与 apk_patcher 烘焙进主 App 的端口一致 */
    public static final int RELAY_PORT = 18080;

    private static final String TAG = "LediAgent";

    /** 网关地址来源（生产环境 = 机器人设置里填的值） */
    public interface GatewaySource { String getGateway(); }

    private final GatewaySource source;
    private final int port;
    private volatile boolean running = false;
    private ServerSocket server;
    private Thread acceptor;

    // 诊断计数（供 /health 查看）
    private volatile long okCount = 0;
    private volatile long failCount = 0;
    private volatile String lastError = "";
    private volatile String lastTarget = "";

    public LoopbackRelay(GatewaySource source) { this(source, RELAY_PORT); }

    /** 供单测指定端口 */
    public LoopbackRelay(GatewaySource source, int port) {
        this.source = source;
        this.port = port;
    }

    public boolean isRunning() { return running; }

    /** 实际监听端口（真机恒为 RELAY_PORT；单测里由构造参数决定） */
    public int port() { return port; }

    public void start() {
        if (running) return;
        running = true;
        acceptor = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "ledi-relay");
        acceptor.setDaemon(true);
        acceptor.start();
        Log.i(TAG, "loopback relay on 127.0.0.1:" + port + " -> 齿轮设置里填的网关地址");
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
        if (acceptor != null) acceptor.interrupt();
    }

    /** 一行诊断文本（塞进 /health，方便真机排障） */
    public String status() {
        String gw = source == null ? "" : source.getGateway();
        StringBuilder sb = new StringBuilder();
        sb.append("listen=127.0.0.1:").append(port)
          .append(" up=").append(running)
          .append(" gw=").append(gw.isEmpty() ? "(未设置)" : gw)
          .append(" last=").append(lastTarget.isEmpty() ? "-" : lastTarget)
          .append(" ok=").append(okCount)
          .append(" fail=").append(failCount);
        if (!lastError.isEmpty()) sb.append(" err=").append(lastError);
        return sb.toString();
    }

    private void acceptLoop() {
        while (running) {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(
                        InetAddress.getByName("127.0.0.1"), port), 32);
                while (running) {
                    final Socket client = server.accept();
                    Thread t = new Thread(new Runnable() {
                        @Override public void run() { handle(client); }
                    }, "ledi-relay-conn");
                    t.setDaemon(true);
                    t.start();
                }
            } catch (Exception e) {
                if (running) {
                    lastError = String.valueOf(e.getMessage());
                    Log.w(TAG, "relay accept: " + e);
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
                }
            } finally {
                try { if (server != null) server.close(); } catch (Exception ignored) {}
                server = null;
            }
        }
    }

    /** 把 http://host:port[/path] 或 host:port 解析成 {host, port}；解析不出返回 null */
    static String[] parseTarget(String gw) {
        if (gw == null) return null;
        String s = gw.trim();
        if (s.isEmpty()) return null;
        int p = s.indexOf("://");
        if (p >= 0) s = s.substring(p + 3);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        if (s.isEmpty()) return null;
        String host;
        int port = 80;
        int colon = s.lastIndexOf(':');
        if (colon > 0 && colon < s.length() - 1) {
            host = s.substring(0, colon);
            try { port = Integer.parseInt(s.substring(colon + 1)); }
            catch (Exception e) { return null; }
        } else {
            host = s;
        }
        if (host.isEmpty() || host.indexOf(':') >= 0 || host.indexOf(' ') >= 0
                || port <= 0 || port > 65535) return null;
        return new String[]{host, String.valueOf(port)};
    }

    /** 目标是否指向本机转发口（自环） */
    private boolean isSelfLoop(String host, String portStr) {
        return ("127.0.0.1".equals(host) || "localhost".equals(host))
                && String.valueOf(port).equals(portStr);
    }

    private void handle(Socket client) {
        Socket upstream = null;
        String gw = source == null ? "" : source.getGateway();
        String[] tgt = parseTarget(gw);
        if (tgt == null) {
            failCount++;
            lastError = "网关地址未设置";
            reply(client, "502 Bad Gateway",
                    "gateway not configured — 请在机器人齿轮设置里填电脑的网关地址");
            return;
        }
        if (isSelfLoop(tgt[0], tgt[1])) {
            failCount++;
            lastError = "网关地址指向本机转发口(自环)";
            reply(client, "502 Bad Gateway", "gateway points to the relay itself");
            return;
        }
        lastTarget = tgt[0] + ":" + tgt[1];
        try {
            upstream = new Socket();
            upstream.setTcpNoDelay(true);
            upstream.connect(new InetSocketAddress(tgt[0], Integer.parseInt(tgt[1])), 3000);
            client.setTcpNoDelay(true);
            okCount++;
            lastError = "";
            // 转发通了 = 电脑端确实在场：给 PcLink 打点，机器人屏幕上那行 IP 会立刻消失
            PcLink.markOk(lastTarget);
            final Socket up = upstream;
            Thread back = new Thread(new Runnable() {
                @Override public void run() {
                    pipe(up, client);
                    closeQuietly(up);
                    closeQuietly(client);
                }
            }, "ledi-relay-back");
            back.setDaemon(true);
            back.start();
            pipe(client, upstream);
            // 关键：必须等回程（网关 → 客户端）跑完才能收尾。
            // 请求方向半关（客户端 shutdownOutput）后立即关 socket 会把响应通道一起掐断，
            // 表现为「网关收到了请求、客户端却永远收不到回复」。
            try { back.join(180000); } catch (InterruptedException ignored) {}
            closeQuietly(upstream);
            closeQuietly(client);
        } catch (Exception e) {
            failCount++;
            lastError = String.valueOf(e.getMessage());
            reply(client, "502 Bad Gateway", "relay connect failed: " + lastTarget);
            closeQuietly(upstream);
            closeQuietly(client);
        }
    }

    private static void pipe(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                out.flush();
            }
            try { to.shutdownOutput(); } catch (Exception ignored) {}
        } catch (Exception ignored) {
            // 任一端断开即结束本次转发
        }
    }

    private static void reply(Socket s, String status, String msg) {
        try {
            byte[] body = (msg + "\n").getBytes("UTF-8");
            String head = "HTTP/1.1 " + status + "\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream o = s.getOutputStream();
            o.write(head.getBytes("UTF-8"));
            o.write(body);
            o.flush();
        } catch (Exception ignored) {}
        closeQuietly(s);
    }

    private static void closeQuietly(Socket s) {
        try { if (s != null) s.close(); } catch (Exception ignored) {}
    }
}
