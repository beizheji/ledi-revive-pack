package com.ledi.agent.net;

import com.ledi.agent.core.AgentService;
import com.ledi.agent.core.CommandRouter;
import com.ledi.agent.sense.InputInject;
import com.ledi.agent.voice.MicController;
import com.ledi.agent.ui.ProgrammerActivity;
import com.ledi.agent.ui.ScreenCast;
import com.ledi.agent.core.Shell;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/**
 * 极简 HTTP 控制服务器（端口 8899）。
 * 路由全部在 CommandRouter（与蓝牙 SPP 直连共用）。
 * 本类只负责：HTTP 解析 / 响应头（含 CORS，允许 PC 图形化控制台跨域调用）/
 * 麦克风音频文件下载（二进制，不走共享路由）。
 *
 * 常用路由：
 *   GET  /health          GET /status      GET /motions   GET /motions_all
 *   GET  /faces           GET /poses       GET /miclist   GET /micfile?name=
 *   POST /motion /dance /base /stop /face /faceoff /say /config
 *   POST /volume /wifi /exclusive /display /displayoff /mic /gear
 *   POST /pose /poserun /posedel        POST /motionconfig {enabled:[..]|reset:true}
 */
public class HttpServer {

    private final Context ctx;
    private final CommandRouter router;
    private final MicController mic;
    private volatile boolean running;
    private ServerSocket server;

    public HttpServer(Context ctx, CommandRouter router, MicController mic) {
        this.ctx = ctx;
        this.router = router;
        this.mic = mic;
    }

    public void start() {
        running = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    server = new ServerSocket();
                    server.setReuseAddress(true);
                    server.bind(new InetSocketAddress(AgentService.HTTP_PORT));
                    while (running) {
                        final Socket s = server.accept();
                        new Thread(new Runnable() {
                            @Override public void run() { handle(s); }
                        }).start();
                    }
                } catch (Exception e) {
                    if (running) Log.e(AgentService.TAG, "http server die", e);
                }
            }
        }).start();
    }

    public void stop() {
        running = false;
        try { server.close(); } catch (Exception ignored) {}
    }

    private void handle(Socket s) {
        try {
            // 30s：/img 要收几百 KB 的 base64 图片，10s 在慢 WiFi 上会中途断
            s.setSoTimeout(30000);
            InputStream in = s.getInputStream();
            String line = readLine(in);
            if (line == null) { s.close(); return; }
            String[] parts = line.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";
            Map<String, String> headers = new HashMap<String, String>();
            String h;
            int contentLength = 0;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c > 0) {
                    headers.put(h.substring(0, c).trim().toLowerCase(),
                            h.substring(c + 1).trim());
                }
            }
            String cl = headers.get("content-length");
            if (cl != null) try { contentLength = Integer.parseInt(cl); } catch (Exception ignored) {}
            byte[] body = new byte[contentLength];
            int got = 0;
            while (got < contentLength) {
                int n = in.read(body, got, contentLength - got);
                if (n < 0) break;
                got += n;
            }
            String bodyStr = new String(body, "UTF-8");

            String route = path.split("\\?")[0];
            String query = path.contains("?") ? path.split("\\?", 2)[1] : "";

            // ★ v192：/console/micfile 是网页控制台里播放录音用的路径（那份页面的老习惯），
            //   与 /micfile 同义，直接并进来，省得页面改。
            if ("GET".equals(method)
                    && ("/micfile".equals(route) || "/console/micfile".equals(route))) {
                byte[] audio = micFile(query);
                if (audio == null) {
                    write(s, 404, "audio/amr", "{\"error\":\"no such recording\"}".getBytes("UTF-8"));
                } else {
                    write(s, 200, "audio/amr", audio);
                }
                return;
            }

            // ★★★ v175：图形化编程器网页（text/html 直发，不走 JSON 路由）。
            //   为什么要这条：**本机 ROM 里根本没有 WebView**（实测：60 个包无 webview 包、
            //   /system/app 无 WebViewGoogle、/system/framework 无 webviewchromium.jar、
            //   settings get global webview_provider = null）⇒ 机器人屏幕上的
            //   ProgrammerActivity 只能降级成"告诉你这个地址"的提示页，
            //   **真正能用的编程入口就是这条 HTTP**。
            //   注入一行 lediAutoHost：从浏览器打开时自动连**它自己所在的地址**
            //   （programmer.html 的 load 回调会读它、自动点"连接机器人"），用户不必手填 IP。
            if ("GET".equals(method) && "/programmer".equals(route)) {
                byte[] html = programmerPage();
                if (html == null) {
                    write(s, 500, "text/plain; charset=utf-8",
                            "programmer.html missing from assets".getBytes("UTF-8"));
                } else {
                    write(s, 200, "text/html; charset=utf-8", html);
                }
                return;
            }

            // ★★★ v192：机器人自带网页控制台 —— 与电脑端「乐迪控制器」**同一份页面**。
            //   为什么由机器人自己发：那份页面本来要"装 exe → 起 PC 网关 → 浏览器开 app 窗口"
            //   才能用；而它里面绝大多数按钮本来就是打机器人自己的 HTTP。把页面搬进 assets、
            //   再补一条 /console/* 兼容层（CommandRouter.consoleCompat），浏览器直接开
            //   http://<机器人IP>:8899/ 就是同一个控制台，**客户端零安装**。
            //   ★ 放在 router 之前 ⇒ 不受屏保总闸限制，永远打得开（屏保中打开它正是最常见的用法，
            //     页面上就有「叫醒」）。
            if ("GET".equals(method) && ("/".equals(route) || "/ui".equals(route)
                    || "/index.html".equals(route) || "/console".equals(route))) {
                byte[] html = consolePage();
                if (html == null) {
                    write(s, 500, "text/plain; charset=utf-8",
                            "console.html missing from assets".getBytes("UTF-8"));
                } else {
                    write(s, 200, "text/html; charset=utf-8", html);
                }
                return;
            }
            // 页面里引用的 /ui/icons/ledi.png、以及浏览器自己会来要的 /favicon.ico：
            // 都从 APK 的 assets/icons/ 里直接发，省得再挂一份图标。
            if ("GET".equals(method) && route.startsWith("/ui/")) {
                byte[] bin = assetBytes(route.substring(4));
                if (bin == null) {
                    write(s, 404, "text/plain; charset=utf-8", "no such asset".getBytes("UTF-8"));
                } else {
                    write(s, 200, mimeOf(route), bin);
                }
                return;
            }
            if ("GET".equals(method) && "/favicon.ico".equals(route)) {
                byte[] icon = assetBytes("/icons/ledi.png");
                if (icon == null) write(s, 204, "image/x-icon", new byte[0]);
                else write(s, 200, "image/png", icon);
                return;
            }

            // ★★★ v195：屏幕投屏 —— 机器人把自己的屏推成 H.264 给浏览器。
            //
            //   为什么不是"每帧一张 JPEG 轮询"：本机实测（Android 5.1/MTK/800x480）
            //   screencap 原始 RGBA ~230ms、screencap -p(PNG) ~640ms（PNG 压缩独吞 410ms），
            //   天花板就是 1.5fps；而 screenrecord 走的是系统硬件编码器，一次起进程就能
            //   连续吐 30fps 的**已编码**码流（~1.7Mbps）。浏览器端用 WebCodecs 直接解。
            //   也就是说：**瓶颈从来不是"抓屏"，是"每帧都要压一次图"**。
            //
            //   ★ 走这条路的三个前提都是实测确认过的，不是抄来的：
            //     ① 不需要 MediaProjection 授权弹窗（screenrecord 是系统命令，
            //        而本 App 是 android.uid.system / uid=1000，实测能直接录）；
            //     ② 不需要随包带任何 .so —— screenrecord 就在 /system/bin；
            //     ③ stdout 可用（--output-format=h264 -），且 Runtime.exec 是管道不是 pty，
            //        不会踩"老设备 exec-out 把字节搞坏"那个坑。
            //
            //   ★ 放在 router 之前 ⇒ 不受屏保总闸限制：人不在场时机器人多半正在屏保，
            //     而"看看屏保上是什么 / 点一下把它叫醒"恰恰是这个功能最常见的用法。
            //     这是**只读**的（吐像素），撕不掉屏保；注入那条路（/console/tap 等）
            //     照常走总闸，理由见 CommandRouter 里的白名单注释。
            if ("GET".equals(method) && ("/mirror.h264".equals(route)
                    || "/mirrorstream".equals(route))) {
                streamCast(s);
                return;
            }
            // 单张静态截图：视频那条路在个别浏览器（没有 WebCodecs）上走不通，
            // 这张图是**如实的降级**——能看，但只有一帧，不假装是投屏。
            if ("GET".equals(method) && ("/mirror.jpg".equals(route)
                    || "/mirror.png".equals(route))) {
                byte[] png = stillShot();
                if (png == null) {
                    write(s, 503, "application/json; charset=utf-8",
                            "{\"ok\":false,\"error\":\"截图失败：screencap 没产出文件\"}"
                                    .getBytes("UTF-8"));
                } else {
                    write(s, 200, "image/png", png);
                }
                return;
            }
            if ("GET".equals(method) && "/mirror/stat".equals(route)) {
                write(s, 200, "application/json; charset=utf-8",
                        ("{\"ok\":true,\"cast\":" + ScreenCast.get(ctx).json()
                                + ",\"inject\":" + InputInject.json() + "}").getBytes("UTF-8"));
                return;
            }

            String resp = router.handle(method, route, query, bodyStr);
            write(s, 200, "application/json; charset=utf-8", resp.getBytes("UTF-8"));
        } catch (Exception e) {
            Log.e(AgentService.TAG, "http handle", e);
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * ★★★ v175：编程器网页缓存（随包发布，运行期不会变，读一次就够）。
     * <p>读的是打进 APK 的 {@code assets/programmer.html}，与机器人本机 WebView
     * 加载的是**同一份**（三端同一套内核），所以电脑上看到的和机器人上本该看到的一致。
     */
    private byte[] progCache;

    /** ★★★ v175：把 {@code assets/programmer.html} 取出并注入自动连主机地址。 */
    private synchronized byte[] programmerPage() {
        if (progCache != null) return progCache;
        try {
            InputStream in = ctx.getAssets().open("programmer.html");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            String html = new String(bo.toByteArray(), "UTF-8");
            // 注入 autoHost：在 </head> 前插一行，早于 programmer.html 自己的 load 回调
            String inject = "<script>window.lediAutoHost=location.origin;</script>\n";
            int i = html.indexOf("</head>");
            if (i >= 0) html = html.substring(0, i) + inject + html.substring(i);
            else html = inject + html;
            progCache = html.getBytes("UTF-8");
            Log.i(AgentService.TAG, "programmer page ready " + progCache.length + " B");
            return progCache;
        } catch (Throwable t) {
            Log.e(AgentService.TAG, "programmer page", t);
            return null;
        }
    }

    /** ★★★ v192：网页控制台缓存（随包发布，运行期不变，读一次就够）。 */
    private byte[] uiCache;

    private synchronized byte[] consolePage() {
        if (uiCache != null) return uiCache;
        byte[] b = assetBytes("/console.html");
        if (b == null) return null;
        uiCache = b;
        Log.i(AgentService.TAG, "console page ready " + b.length + " B");
        return b;
    }

    /**
     * 从 APK 的 assets 里读一个文件（{@code name} 以 {@code /} 开头，如 {@code /icons/ledi.png}）。
     *
     * <p>读不到返回 {@code null} 而**不抛** —— 调用点要么回 404、要么回 500 并把
     * "缺了哪个文件"写进响应体，别让一次缺文件变成一条看不见的异常。</p>
     */
    private byte[] assetBytes(String name) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(name.startsWith("/") ? name.substring(1) : name);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
        }
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        return "application/octet-stream";
    }

    private byte[] micFile(String query) {
        String name = "";
        for (String kv : query.split("&")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2 && p[0].equals("name")) {
                try { name = URLDecoder.decode(p[1], "UTF-8"); } catch (Exception ignored) {}
            }
        }
        File f = mic.file(name);
        if (f == null) return null;
        try {
            FileInputStream fi = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fi.read(buf)) > 0) bo.write(buf, 0, n);
            fi.close();
            return bo.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * H.264 流式响应（v195）。
     *
     * <p>不设 {@code Content-Length} —— 编码器停之前长度根本不知道。改用
     * "无长度 + {@code Connection: close}"，也就是**读到连接关闭为止**；
     * 浏览器 {@code fetch()} 的 ReadableStream 正是这么读的，合法且广泛支持。
     * 一条连接占一个线程（accept 那里本来就是每连接一线程），所以慢连接不会
     * 挡住别人。</p>
     *
     * <p>观看者登记进 {@link ScreenCast} 之后就阻塞在这儿 —— 编码是**全 App 一份**，
     * 谁来看谁登记。反过来，如果没人看超过 8 秒，编码自己会停（省 CPU）。</p>
     */
    private void streamCast(Socket s) {
        ScreenCast cast = ScreenCast.get(ctx);
        if (!cast.start()) {
            try {
                write(s, 503, "application/json; charset=utf-8",
                        ("{\"ok\":false,\"error\":\"投屏编码器起不来\",\"cast\":"
                                + cast.json() + "}").getBytes("UTF-8"));
            } catch (Throwable ignored) {}
            return;
        }
        try {
            OutputStream os = s.getOutputStream();
            os.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: video/h264\r\n"
                    + "Cache-Control: no-store\r\n"
                    + "Pragma: no-cache\r\n"
                    + "X-Ledi-Width: " + cast.width() + "\r\n"
                    + "X-Ledi-Height: " + cast.height() + "\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Connection: close\r\n\r\n").getBytes("UTF-8"));
            os.flush();
            ScreenCast.Viewer v = cast.addViewer(os);
            if (v.closed) return;      // 连"开头 + 最近一个 IDR"都写不出去，直接滚
            v.await();                 // 被踢（写失败/停止）或超时才返回
        } catch (Throwable t) {
            // 浏览器关页面/切走 → 这里是**正常**路径，不记红
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 单张截图（PNG）（v195）。
     *
     * <p>落盘再回读，而不是让 screencap 往 stdout 吐 —— 老设备上"命令输出走管道"
     * 这条路出过字节被改写的事（本项目在 adb 侧踩过 CRLF 那个坑）。落盘再读是
     * 二进制安全的，而且这条路本机已实测：uid=1000 写自己的 cache 目录成功。</p>
     *
     * @return PNG 字节；失败返回 {@code null}（不返回一张假图）
     */
    private byte[] stillShot() {
        File f = new File(ctx.getCacheDir(), "_mirror_shot.png");
        Process p = null;
        try {
            if (f.exists()) f.delete();
            p = Runtime.getRuntime().exec(new String[]{
                    "/system/bin/screencap", "-p", f.getAbsolutePath()});
            // ★ 不能用 Process.waitFor(long, TimeUnit)：那是 API 26+，本机 API 22
            //   调了当场 NoSuchMethodError（Shell.java 里已踩过），所以照它那样
            //   把 waitFor() 丢进线程再 join 超时。
            final Process fp = p;
            Thread waiter = new Thread(new Runnable() {
                @Override public void run() {
                    try { fp.waitFor(); } catch (Throwable ignored) {}
                }
            }, "shot-wait");
            waiter.setDaemon(true);
            waiter.start();
            waiter.join(8000);
            if (waiter.isAlive()) {
                try { p.destroy(); } catch (Throwable ignored) {}
                Log.e(AgentService.TAG, "screencap 8s 没退出，已放弃");
                return null;
            }
            // 进程已退出，这两路现在都在 EOF，读干它们只为把错误留下来。
            String err = new String(drainAll(p.getErrorStream()), "UTF-8").trim();
            drainAll(p.getInputStream());
            if (!f.exists()) {
                Log.e(AgentService.TAG, "screencap 没产出文件: " + err);
                return null;
            }
            byte[] png = readFile(f);
            f.delete();
            // ★ 判据放在这里：**PNG 签名**。少了它，一个 0 字节或半个文件
            //   会被当成"截图成功"发给浏览器，然后页面上是一张空白图 —— 
            //   这正是本项目最贵的那类假绿灯。
            if (png == null || png.length < 8
                    || (png[0] & 0xff) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') {
                Log.e(AgentService.TAG, "screencap 产物不是 PNG: "
                        + (png == null ? "null" : png.length + "B") + " " + err);
                return null;
            }
            return png;
        } catch (Throwable t) {
            Log.e(AgentService.TAG, "stillShot", t);
            return null;
        }
    }

    private static byte[] drainAll(InputStream in) throws Exception {
        if (in == null) return new byte[0];
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private static byte[] readFile(File f) {
        FileInputStream fi = null;
        try {
            fi = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fi.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (fi != null) fi.close(); } catch (Exception ignored) {}
        }
    }

    private void write(Socket s, int code, String type, byte[] data) throws Exception {
        OutputStream os = s.getOutputStream();
        os.write(("HTTP/1.1 " + code + " OK\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\n"
                + "Connection: close\r\n\r\n").getBytes("UTF-8"));
        os.write(data);
        os.flush();
        s.close();
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int prev = -1, c;
        while ((c = in.read()) >= 0) {
            if (prev == '\r' && c == '\n') break;
            if (prev >= 0) b.write(prev);
            prev = c;
        }
        if (b.size() == 0 && prev < 0) return null;
        if (prev >= 0 && prev != '\r') b.write(prev);
        return new String(b.toByteArray(), "UTF-8");
    }

    public static String localIp() {
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a.getAddress().length == 4) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }
}
