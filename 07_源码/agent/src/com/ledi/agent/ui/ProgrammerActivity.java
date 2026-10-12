package com.ledi.agent.ui;

import com.ledi.agent.net.HttpServer;

import com.ledi.agent.core.BootLog;

import com.ledi.agent.core.AgentService;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ProgrammerActivity —— 图形化动作编程器（newcore）。
 *
 * 机器人端由齿轮 → 设置界面 →「图形化动作编程」进入。
 * 用 WebView 加载统一内核 programmer.html（三端同一套：电脑浏览器 / 机器人 WebView / 手机）。
 *
 * <p><b>★★★ v175（2026-09-25）根因与修法 —— 这台设备上本机界面必然是空的：</b>
 * <ol>
 *   <li>真机实证：<b>这台 ROM 把 WebView 整个删掉了</b> ——
 *       60 个包里没有任何 webview/chrome 包；{@code /system/app} 里没有 WebViewGoogle；
 *       {@code /system/framework} 里没有 webviewchromium.jar；
 *       {@code settings get global webview_provider} = null。</li>
 *   <li>而旧代码 <b>第一行就是 {@code new WebView(this)}</b> ⇒ 构造即抛异常
 *       ⇒ 后面的 {@code setContentView(...)} <b>永不执行</b>。</li>
 *   <li>现象（像素硬证）：窗口只剩主题底色 {@code Theme.Material} = #303030
 *       —— 截图 800×480 全屏 <b>100% 都是 (48,48,48)</b>，
 *       我们的深蓝渐变 {@code 0xFF101A3C} 一个像素都没有。
 *       另一个独立量具同向：{@code uiautomator dump} 报 {@code null root node}
 *       （窗口里确实没有视图）。用户看到的就是"点进去一片黑"。</li>
 *   <li>历史同源：2026-09-24 的 dropbox 里那条
 *       {@code Unable to start activity ComponentInfo{...ProgrammerActivity}}
 *       就是这条异常 —— 那时还没有主线程崩溃盾，所以它直接把进程打死了。</li>
 * </ol>
 *
 * <p>修法分两层，缺一不可：
 * <ul>
 *   <li><b>本机</b>：把 {@code setContentView} 这一步挪进 try 保护里，
 *       WebView 只是"能用就用"的一层；不可用就画一张<b>纯原生提示页</b>，
 *       把真正能用的入口（{@code http://<机器人IP>:8899/programmer}）写在屏幕上。
 *       再也不会出现"黑屏且什么信息都没有"。</li>
 *   <li><b>真正可用</b>：编程器网页本身是好的（它调用的 11 个端点服务端全都有），
 *       所以服务端新增 {@code GET /programmer} 直接把这份 HTML 发给浏览器
 *       （见 {@link HttpServer#programmerPage()}）—— 用电脑/手机浏览器打开即可编程。</li>
 * </ul>
 */
public class ProgrammerActivity extends Activity {

    /**
     * ★★★ v175：本机 WebView 不可用的原因（空串 = 可用）。
     * <p>这是「新代码上机」的判据：{@code /status.version} 读的是 APK manifest，
     * 装完包立刻就是新号，证明不了进程里跑的是新代码 ⇒ 要看 {@code /health.prog}
     * 这个字段在不在、值对不对。见本项目既往铁律。
     */
    public static volatile String lastError = "";

    private WebView web;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // ★★★ v175：**先保证界面一定画出来**，再把 WebView 塞进去。
        //   旧写法把 setContentView 放在最后，一旦 new WebView 抛异常，
        //   就连"告诉用户发生了什么"的机会都没有了（屏幕全黑、日志也只有一条没人看的栈）。
        try {
            web = new WebView(this);
            WebSettings ws = web.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setAllowFileAccess(true);
            ws.setAllowContentAccess(true);

            // 注入本机 host，让编程器自动连接机器人自己
            web.addJavascriptInterface(new Object() {
                @android.webkit.JavascriptInterface
                public String getRobotHost() {
                    return "http://127.0.0.1:" + AgentService.HTTP_PORT;
                }
            }, "LediBridge");

            // 让编程器自动连接机器人自己（等页面加载完注入 host）
            web.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String url) {
                    try {
                        view.loadUrl("javascript:(function(){"
                                + "var h='http://127.0.0.1:" + AgentService.HTTP_PORT + "';"
                                + "window.lediAutoHost=h;"
                                + "var e=document.getElementById('host');if(e){e.value=h;}"
                                + "var c=document.getElementById('connectBtn');if(c){c.click();}"
                                + "})();");
                    } catch (Throwable ignored) {}
                }
            });

            web.loadUrl("file:///android_asset/programmer.html");
            setContentView(buildShell(web));
            lastError = "";
            BootLog.i("编程器: WebView 正常，已加载 assets/programmer.html");
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : (": " + t.getMessage()));
            BootLog.w("编程器: 本机 WebView 不可用（" + lastError + "）⇒ 降级为提示页");
            try {
                setContentView(buildFallback());
            } catch (Throwable t2) {
                // 连降级页都画不出来（理论上不会），至少保证日志有账
                BootLog.w("编程器: 降级页也失败 " + t2);
            }
        }
    }

    /** 外壳：乐迪配色标题栏 + 下面的 WebView。 */
    private View buildShell(WebView content) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Skin.windowBg(this));

        View back = Skin.iconButton(this, "icon_back.png", 26, false,
                new View.OnClickListener() {
                    public void onClick(View v) { finish(); }
                });
        LinearLayout head = Skin.titleBar(this, "图形化动作编程", "拖积木拼动作 · 直接下发机器人",
                28, back);
        root.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    /**
     * ★★★ v175：本机 WebView 不可用时的<b>纯原生</b>提示页（不依赖 WebView，一定能画出来）。
     * <p>关键不是"报个错"，而是<b>把出路写在屏幕上</b>：这台设备能编程的唯一入口
     * 是 {@code http://<机器人IP>:8899/programmer}，用户在电脑/手机浏览器打开就能用，
     * 而屏幕上原来只有一个黑框，谁也猜不到这一步。
     */
    private View buildFallback() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Skin.windowBg(this));

        View back = Skin.iconButton(this, "icon_back.png", 26, false,
                new View.OnClickListener() {
                    public void onClick(View v) { finish(); }
                });
        root.addView(Skin.titleBar(this, "图形化动作编程", "拖积木拼动作 · 直接下发机器人",
                        28, back),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), dp(10), dp(14), dp(10));

        TextView t1 = new TextView(this);
        t1.setText("本机没有浏览器组件，这个界面显示不出来");
        Skin.t(t1, Skin.WARN, 17, true);
        col.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText("这台机器人的系统精简掉了 WebView（连系统浏览器都没有），"
                + "所以本机画不出编程界面。\n"
                + "编程器本身是好的 —— 换个地方打开就能用：");
        Skin.t(t2, Skin.DIM, 12, false);
        t2.setPadding(0, dp(6), 0, dp(8));
        col.addView(t2);

        final String url = "http://" + HttpServer.localIp() + ":"
                + AgentService.HTTP_PORT + "/programmer";
        TextView u = new TextView(this);
        u.setText(url);
        Skin.t(u, Skin.YELLOW, 15, true);
        u.setPadding(dp(12), dp(10), dp(12), dp(10));
        u.setBackground(Skin.card(this));
        u.setTextIsSelectable(true);      // 长按可选中，方便抄写
        col.addView(u);

        TextView t3 = new TextView(this);
        t3.setText("用电脑或手机的浏览器打开上面这行（要和机器人连同一个 WiFi）。\n"
                + "电脑上打开会更顺手：屏幕大、可以用鼠标拖积木。");
        Skin.t(t3, Skin.DIM, 12, false);
        t3.setPadding(0, dp(8), 0, 0);
        col.addView(t3);

        if (lastError != null && !lastError.isEmpty()) {
            TextView t4 = new TextView(this);
            t4.setText("（技术细节：" + lastError + "）");
            Skin.t(t4, Skin.DIM, 10, false);
            t4.setPadding(0, dp(6), 0, 0);
            col.addView(t4);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(10), 0, 0);
        Button retry = Skin.btn(this, "重试", false, new View.OnClickListener() {
            public void onClick(View v) { recreate(); }
        });
        Button home = Skin.btn(this, "回主界面", true, new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = dp(8);
        row.addView(retry, lp1);
        row.addView(home, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(row);

        root.addView(col, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    @Override
    protected void onDestroy() {
        try { if (web != null) web.destroy(); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
