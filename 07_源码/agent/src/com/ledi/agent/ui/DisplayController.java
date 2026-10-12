package com.ledi.agent.ui;

import com.ledi.agent.core.AgentService;
import com.ledi.agent.sdk.FaceController;
import com.ledi.agent.core.MainThread;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.concurrent.Callable;

/**
 * 屏幕显示控制器：在机器人屏幕上全屏悬浮显示 文字 或 图片。
 * （表情视频走 FaceController 的悬浮窗，与这里互斥：显示内容前先关掉自己那一层）
 *
 * <p>浮层必须在有 Looper 的线程上建/拆，否则抛
 * {@code Can't create handler inside thread that has not called Looper.prepare()}
 * —— 所以真正的 addView/removeView 全部经 {@link MainThread} 搬到主线程。
 *
 * <p>图片由电脑端（网关）读文件、纠正方向并缩放后，以 base64 PNG 经 /img 送进来，
 * 这里只负责解码与显示，避免机器人侧做重活。
 */
public class DisplayController {

    private final Context ctx;
    private final WindowManager wm;
    private volatile View overlay;      // 文字用 TextView、图片用 ImageView，统一按 View 持有
    private volatile String current;

    public DisplayController(Context ctx) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    public String show(final String text) {
        if (text == null || text.isEmpty()) return "text required";
        current = text;
        try {
            return MainThread.call(new Callable<String>() {
                @Override public String call() throws Exception {
                    hideInner();
                    TextView tv = new TextView(ctx);
                    tv.setText(text);
                    tv.setTextColor(Color.WHITE);
                    tv.setBackgroundColor(Color.argb(220, 16, 18, 24));
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 48);
                    tv.setTypeface(Typeface.DEFAULT_BOLD);
                    tv.setGravity(Gravity.CENTER);
                    tv.setPadding(32, 32, 32, 32);
                    wm.addView(tv, fullScreenParams(PixelFormat.TRANSLUCENT));
                    overlay = tv;
                    return "displaying: " + text;
                }
            }, 5000);
        } catch (Exception e) {
            Log.e(AgentService.TAG, "display fail", e);
            return "display error: " + e;
        }
    }

    /**
     * 显示一张图片（PNG/JPG 原始字节）。
     *
     * @param maxSide 最长边上限，超过则等比缩小（&lt;=0 表示不缩）
     */
    public String showImage(final byte[] data, final int maxSide) {
        return showImage(data, maxSide, "fit");
    }

    /**
     * @param mode "fit" = 完整显示，可能留黑边（默认）；"crop" = 铺满屏幕，可能裁边
     */
    public String showImage(final byte[] data, final int maxSide, final String mode) {
        if (data == null || data.length == 0) return "image data required";
        try {
            return MainThread.call(new Callable<String>() {
                @Override public String call() throws Exception {
                    hideInner();
                    Bitmap src = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (src == null) return "decode failed (unsupported or corrupt image)";
                    Bitmap bmp = scaleTo(src, maxSide);
                    ImageView iv = new ImageView(ctx);
                    iv.setScaleType("crop".equalsIgnoreCase(mode)
                            ? ImageView.ScaleType.CENTER_CROP
                            : ImageView.ScaleType.FIT_CENTER);
                    iv.setBackgroundColor(Color.BLACK);      // 留白处黑底，屏幕上看不出边框
                    iv.setImageBitmap(bmp);
                    wm.addView(iv, fullScreenParams(PixelFormat.OPAQUE));
                    overlay = iv;
                    current = "img:" + bmp.getWidth() + "x" + bmp.getHeight() + ":" + mode;
                    Log.i(AgentService.TAG, "img shown: " + current);
                    return "image " + bmp.getWidth() + "x" + bmp.getHeight();
                }
            }, 15000);
        } catch (Exception e) {
            Log.e(AgentService.TAG, "image fail", e);
            return "image error: " + e;
        }
    }

    /** base64 图片显示（供 HTTP POST /img 使用）。 */
    public String showImage64(String b64, int maxSide) {
        return showImage64(b64, maxSide, "fit");
    }

    public String showImage64(String b64, int maxSide, String mode) {
        if (b64 == null || b64.isEmpty()) return "data required";
        try {
            byte[] bin = Base64.decode(b64, Base64.DEFAULT);
            return showImage(bin, maxSide, mode);
        } catch (Exception e) {
            Log.e(AgentService.TAG, "img base64 fail", e);
            return "base64 decode failed: " + e;
        }
    }

    /**
     * 全屏浮层的窗口参数。
     *
     * <p><b>关键：必须带 FLAG_NOT_TOUCHABLE。</b>这个浮层覆盖整块屏幕，如果它肯接收触摸，
     * 就会把下面所有 View 的点击事件全部吃掉 —— 表现就是"机器人一显示字符，屏幕就点不动了"。
     * 显示层只需要"看得见"，不需要"点得到"，所以触摸事件要原样透传给下层界面。
     * 同理补 FLAG_NOT_FOCUSABLE，避免它抢走输入焦点（返回键/物理键）。
     *
     * <p>反例：{@code GearOverlay}（齿轮）是<b>必须</b>接收点击与拖动的，所以那一层
     * 只能有 FLAG_NOT_FOCUSABLE，<b>绝不能</b>加 FLAG_NOT_TOUCHABLE。改这里不要顺手去动它。
     */
    private static WindowManager.LayoutParams fullScreenParams(int pixelFormat) {
        return new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_PHONE,   // system UID 免授权
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE  // ★ 别删：删了就点不动屏幕
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                pixelFormat);
    }

    private static Bitmap scaleTo(Bitmap src, int maxSide) {
        if (maxSide <= 0) return src;
        int w = src.getWidth(), h = src.getHeight();
        int m = Math.max(w, h);
        if (m <= maxSide) return src;
        float s = (float) maxSide / (float) m;
        int nw = Math.max(1, Math.round(w * s));
        int nh = Math.max(1, Math.round(h * s));
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    public String hide() {
        current = null;
        try {
            return MainThread.call(new Callable<String>() {
                @Override public String call() throws Exception { return hideInner(); }
            }, 3000);
        } catch (Exception e) {
            return "clear error: " + e;
        }
    }

    /** 只在主线程里被调；没挂上去过的引用直接丢掉，不报 not attached。 */
    private String hideInner() {
        try {
            if (overlay != null) {
                try {
                    wm.removeView(overlay);
                } catch (Exception e) {
                    Log.w(AgentService.TAG, "display removeView skipped: " + e);
                }
                overlay = null;
            }
            return "cleared";
        } catch (Exception e) {
            overlay = null;
            return "clear error: " + e;
        }
    }

    public String getCurrent() { return current; }
}
