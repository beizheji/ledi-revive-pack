package com.ledi.agent.net;

import com.ledi.agent.core.AgentService;
import com.ledi.agent.core.CommandRouter;
import com.ledi.agent.core.J;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.UUID;

/**
 * 蓝牙 SPP 直连控制服务（机器人作为服务端）。
 *
 * 手机 App 不连 WiFi、不经过电脑，直接蓝牙 RFCOMM 连上来发指令：
 *   UUID  : 00001101-0000-1000-8000-00805F9B34FB（标准 SPP）
 *   名称  : LediAgent
 *   协议  : 每行一条 UTF-8 指令，\n 结尾
 *           {"m":"POST","p":"/motion","b":"{\"name\":\"hug\"}"}
 *           或简写 "GET /health"
 *   响应  : 一行 JSON（与 HTTP 路由返回完全一致），\n 结尾
 *
 * 单客户端排队：同一时间只服务一条连接，断开后立刻回到 accept。
 * accept 循环异常退出时自动重启（间隔 3s），保证手机随时能连。
 */
public class BtCommandServer {

    public static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    public static final String SDP_NAME = "LediAgent";

    private final CommandRouter router;
    private volatile boolean running;
    private volatile boolean clientAlive;
    private volatile BluetoothServerSocket server;
    private volatile String clientAddr = "";
    private Thread acceptThread;
    // ---- v177：可判读的"真在监听"（见 listening() 的注释）----
    private volatile long listenSinceMs = 0;      // 套接字拿到手的时刻（elapsedRealtime）
    private volatile String lastErr = "";         // 最近一次 accept 循环里的异常
    private volatile int listenCount = 0;         // 一共 listen 成功几次

    public BtCommandServer(CommandRouter router) {
        this.router = router;
    }

    public boolean isRunning() { return running; }

    /**
     * <b>SPP 真的在监听吗</b> —— 与 {@link #isRunning()} 是两件事。
     *
     * <p>为什么不能拿 {@code running} 当"能连上"用（v176→v177 的教训，有实测数字）：
     * 蓝牙关掉再打开之后，适配器 state 在 +0.9s 就是 12（ON），而真能连上是 **+3.9s**
     * （`_v176_bt_ready.py` 实测）。这 3 秒里 {@code running} 一直是 true ——
     * 它只说明"线程还在转"，不说明"SDP 里那条记录已经注册好了"。
     * 客户端若据此认为链路就绪，就会在空窗里开扫：一轮 30 个通道 50 秒全不应答，
     * 而且扫完不会自动重试 ⇒ 用户的"先用蓝牙连上机器人"第一步会偶发失败。
     *
     * <p>真判据只能是**套接字拿到手没有**：{@code listenUsingRfcommWithServiceRecord}
     * 返回之后 SDP 记录才算注册。适配器一关、或 accept 抛异常，都立刻把它置回 null，
     * 绝不留着一个死套接字继续骗下一轮。
     */
    public boolean listening() { return running && server != null; }

    /** 最近一次 listen 成功距今多少毫秒（-1 = 这一轮还没成功过）。 */
    public long listenAgoMs() {
        return listenSinceMs == 0 ? -1 : (android.os.SystemClock.elapsedRealtime() - listenSinceMs);
    }

    public int listenCount() { return listenCount; }

    public String lastError() { return lastErr; }

    public boolean hasClient() { return clientAlive; }

    public String clientAddress() { return clientAddr; }

    public synchronized void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "LediBtAccept");
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
    }

    private void acceptLoop() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        while (running) {
            try {
                if (adapter == null || !adapter.isEnabled()) {
                    // ★ v177：适配器关了，旧套接字已经作废 —— 必须置 null。
                    //   否则 listening() 会继续报 true（死套接字），就是新的假绿灯。
                    server = null;
                    Thread.sleep(3000);
                    continue;
                }
                Log.i(AgentService.TAG, "BT SPP listening as " + adapter.getName());
                server = adapter.listenUsingRfcommWithServiceRecord(SDP_NAME, SPP_UUID);
                listenSinceMs = android.os.SystemClock.elapsedRealtime();
                listenCount++;
                lastErr = "";
                while (running) {
                    BluetoothSocket socket = server.accept();   // 阻塞
                    if (!running) { try { socket.close(); } catch (Exception ignored) {} break; }
                    serve(socket);
                }
            } catch (Exception e) {
                server = null;                 // ★ v177：作废的套接字不许留着当"在监听"
                lastErr = String.valueOf(e);
                if (running) Log.e(AgentService.TAG, "bt accept, retry in 3s", e);
                try { Thread.sleep(3000); } catch (Exception ignored) {}
            }
        }
        server = null;
    }

    /** 服务一条连接直到对端断开 */
    private void serve(final BluetoothSocket socket) {
        clientAlive = true;
        clientAddr = socket.getRemoteDevice() == null ? "" : socket.getRemoteDevice().getAddress();
        Log.i(AgentService.TAG, "BT client connected: " + clientAddr);
        try {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), "UTF-8"), 4096);
            OutputStream out = socket.getOutputStream();
            String line;
            while (running && (line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String resp = dispatch(line);
                out.write((resp + "\n").getBytes("UTF-8"));
                out.flush();
            }
        } catch (Exception e) {
            Log.i(AgentService.TAG, "BT client gone: " + e);
        } finally {
            clientAlive = false;
            clientAddr = "";
            try { socket.close(); } catch (Exception ignored) {}
            Log.i(AgentService.TAG, "BT client disconnected");
        }
    }

    /**
     * 解析一行指令并调用路由。
     * 支持：{"m":"POST","p":"/motion","q":"","b":"{...}"} 或 "GET /health"
     */
    String dispatch(String line) {
        String method = "GET", path = line, query = "", body = "";
        try {
            if (line.startsWith("{")) {
                String m = CommandRouter.field(line, "", "m");
                String p = CommandRouter.field(line, "", "p");
                String q = CommandRouter.field(line, "", "q");
                String b = CommandRouter.field(line, "", "b");
                if (!m.isEmpty() && !p.isEmpty()) {
                    method = m; path = p; query = q; body = b;
                }
            } else {
                String[] parts = line.split(" ");
                if (parts.length >= 2) { method = parts[0]; path = parts[1]; }
            }
            String route = path.split("\\?")[0];
            if (path.contains("?")) query = path.split("\\?", 2)[1];
            return router.handle(method, route, query, body);
        } catch (Exception e) {
            return "{\"error\":\"" + J.esc(String.valueOf(e)) + "\"}";
        }
    }
}
