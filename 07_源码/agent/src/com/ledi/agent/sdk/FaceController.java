package com.ledi.agent.sdk;

import com.ledi.agent.core.AgentService;
import com.ledi.agent.voice.Av;
import com.ledi.agent.core.BootLog;
import com.ledi.agent.ui.DisplayController;
import com.ledi.agent.core.MainThread;
import com.ledi.agent.voice.MediaPlayback;

import android.content.Context;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 表情控制器：从主 App APK（/data/app/ 下含 xldrbtx 的 base.apk）里提取 res/raw 的 mp4，
 * 用悬浮窗全屏循环播放。system UID 下悬浮窗无需用户授权。
 */
public class FaceController {

    private final Context ctx;
    private final WindowManager wm;
    private View overlay;
    private MediaPlayer player;
    private MediaPlayer soundPlayer;
    private SurfaceHolder currentHolder;
    private volatile String current;
    // 代际标记：每次 play 递增；异步回调（surfaceCreated/surfaceDestroyed/onPrepared）
    // 只在 generation 匹配时才操作 player，避免快速连续切换时旧回调误伤新 player。
    private volatile int generation = 0;

    public FaceController(Context ctx) {
        this.ctx = ctx;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
    }

    /** 主 App 安装包（表情视频/音效都在它的 res/raw 里） */
    private static ZipFile apk() throws Exception { return mainApk(); }

    // ================= 表情名归一化 =================
    // behaviors 那套名字（大模型/指令表用）与 res/raw 真实文件名差两处大小写风格，
    // 外加常见驼峰写法，统一收敛到真实视频名，避免「点了没反应」。
    private static final String[][] FACE_ALIAS = {
            {"blinknod", "blink_nod"}, {"eyessqueeze", "eyes_squeeze"},
            {"blinkaround", "blink_around"}, {"changeDizzy", "change_dizzy"},
            {"feelhot", "feel_hot"}, {"lookaround", "look_around"},
            {"lookdown", "look_down"}, {"lookdownleft", "look_down_left"},
            {"lookdownright", "look_down_right"}, {"lookup", "look_up"},
            {"upleft", "look_up_left"}, {"upright", "look_up_right"},
            {"batterylow", "battery_low"}, {"eyesclose", "eyes_close"},
            {"netoff", "net_off"}, {"goodnight", "goodnight"},
            // 近义/情绪名 → 真实视频名（大模型乱写时的兜底）
            {"happy", "happy1"}, {"joy", "enjoy1"}, {"laugh", "smile"},
            {"surprise", "shock"}, {"surprised", "shock"}, {"shocked", "shock"},
            {"confused", "curious"}, {"puzzle", "curious"}, {"sad", "wronged"},
            {"upset", "wronged"}, {"angry", "angry1"}, {"mad", "rage1"},
            {"love", "adore"}, {"sleepy", "drowsy"}, {"tired", "boring"},
            {"sleep", "eyes_close"}, {"hello", "hello"}, {"hi", "hello"},
            {"bye", "bye"}, {"cry", "cry"}, {"sport", "gym"},
            {"excite", "exciting"}, {"fear", "scared"}, {"afraid", "scared"},
    };

    /** 判断 res/raw 下有没有 <名>.mp4 */
    public static boolean hasVideo(String name) {
        if (name == null || name.isEmpty()) return false;
        try {
            ZipFile z = apk();
            if (z == null) return false;
            boolean ok = z.getEntry("res/raw/" + name + ".mp4") != null;
            z.close();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    /** 把任意写法收敛成 res/raw 里真实存在的视频名；认不出返回 ""。 */
    public static String normalize(String name) {
        if (name == null) return "";
        String n = name.trim();
        if (n.isEmpty()) return "";
        if (hasVideo(n)) return n;
        String low = n.toLowerCase();
        // 大小写不敏感
        List<String> all = listFaces();
        for (String v : all) if (v.toLowerCase().equals(low)) return v;
        // 驼峰 → 下划线（blinkNod → blink_nod）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.isUpperCase(c) && i > 0) sb.append('_');
            sb.append(Character.toLowerCase(c));
        }
        String snake = sb.toString();
        for (String v : all) if (v.equals(snake)) return v;
        // 别名表
        for (String[] kv : FACE_ALIAS) {
            if (kv[0].toLowerCase().equals(low)) {
                for (String v : all) if (v.equals(kv[1])) return v;
            }
        }
        return "";
    }

    /** 找主 App 安装包，列出全部表情视频名（不含扩展名） */
    public static List<String> listFaces() {
        List<String> out = new ArrayList<String>();
        try {
            ZipFile apk = mainApk();
            if (apk == null) return out;
            Enumeration<? extends ZipEntry> en = apk.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith("res/raw/") && n.endsWith(".mp4")) {
                    out.add(n.substring(8, n.length() - 4));
                }
            }
            apk.close();
        } catch (Exception e) {
            Log.e(AgentService.TAG, "listFaces fail", e);
        }
        return out;
    }

    private static ZipFile mainApk() throws Exception {
        File dir = new File("/data/app");
        File[] list = dir.listFiles();
        if (list != null) {
            for (File d : list) {
                File base = new File(d, "base.apk");
                if (d.getName().contains("xldrbtx") && base.exists()) {
                    return new ZipFile(base);
                }
            }
        }
        return null;
    }

    private File extractVideo(String name) {
        try {
            ZipFile apk = mainApk();
            if (apk == null) return null;
            ZipEntry e = apk.getEntry("res/raw/" + name + ".mp4");
            if (e == null) { apk.close(); return null; }
            File out = new File(ctx.getCacheDir(), name + ".mp4");
            InputStream in = apk.getInputStream(e);
            FileOutputStream fo = new FileOutputStream(out);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            fo.close(); in.close(); apk.close();
            return out;
        } catch (Exception e) {
            Log.e(AgentService.TAG, "extract fail " + name, e);
            return null;
        }
    }

    /** 播放表情视频（循环），名子先归一化 */
    public String play(String name) { return play(name, false); }

    /**
     * 播放任意视频文件（全屏循环）—— newcore 重构新增，供 MediaPlayback 播放
     * 电脑端上传的任意 mp4（不只是原厂表情）。
     */
    public String playFile(final File f) {
        if (f == null || !f.exists()) return "video file not found";
        current = f.getName();
        try {
            return playOnOverlay(f, f.getName());
        } catch (Exception e) {
            Log.e(AgentService.TAG, "video play fail", e);
            hide();
            return "video error: " + e;
        }
    }

    /**
     * 播放表情。
     *
     * @param withSound 是否同时播放原厂配套音效 res/raw/<名>_s.mp3
     */
    public String play(String rawName, boolean withSound) {
        final String name = normalize(rawName);
        BootLog.i("表情 执行开始 name=" + rawName + " -> " + name);
        if (name.isEmpty()) {
            BootLog.w("表情 未找到: " + rawName);
            return "face not found: " + rawName + "（可用: /faces 取清单）";
        }
        final File f = extractVideo(name);
        if (f == null) { BootLog.w("表情 视频文件缺失: " + name); return "face not found: " + name; }
        current = name;
        if (withSound) playSound(name);
        try {
            // 浮层 / SurfaceView / WindowManager.addView 必须在有 Looper 的线程上做，
            // 否则抛 Can't create handler inside thread that has not called Looper.prepare()
            // （HTTP 请求跑在裸线程上，没有 Looper）。见 MainThread 的类注释。
            String r = playOnOverlay(f, name);
            BootLog.i("表情 ok: " + name);
            return r;
        } catch (Exception e) {
            Log.e(AgentService.TAG, "face play fail", e);
            BootLog.w("表情 播放失败: " + name + " -> " + e);
            hide();                                  // 清掉可能残留的半成品浮层
            return "play error: " + e;
        }
    }

    /**
     * 在「常驻浮层」上启动一段循环视频（表情与任意视频共用）。
     *
     * 关键设计（修「连续切换瞬间退回桌面」）：
     * 全屏浮层窗口与 MediaPlayer 都是**常驻**的——只有第一次播放（或 faceoff 之后）才
     * 创建窗口，之后连续切换只做 reset() → 换数据源 → prepareAsync，**绝不 removeView/
     * addView 窗口**。旧实现每次切换都重建窗口，这台 MTK Android 5.1 的合成器在
     * 「同一帧销毁旧 surface + 创建新 surface」时会出竞态：新 surface 永远是空的，
     * 桌面壁纸透出来，肉眼看就是「切换瞬间退回桌面」。
     * 常驻窗口方案下，旧表情的最后一帧会一直保持在屏幕上，直到新表情首帧渲染完成，
     * 视觉上无缝衔接。
     * 必须在有 Looper 的主线程上调（经 MainThread.call）。
     */
    private String playOnOverlay(final File f, final String label) throws Exception {
        return MainThread.call(new Callable<String>() {
            @Override public String call() throws Exception {
                final int gen = ++generation;
                if (overlay == null || player == null) ensureOverlay();
                MediaPlayer mp = player;
                try { mp.reset(); } catch (Exception ignored) {}
                mp.setDataSource(ctx, Uri.fromFile(f));
                mp.setLooping(true);
                SurfaceHolder h = currentHolder;
                if (h != null && h.getSurface().isValid()) mp.setDisplay(h);
                mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    @Override public void onPrepared(MediaPlayer m) {
                        if (gen != generation) return;   // 已被更新的切换取代
                        try {
                            SurfaceHolder hh = currentHolder;
                            if (hh != null && hh.getSurface().isValid()) m.setDisplay(hh);
                            m.start();
                        } catch (Exception ignored) {}
                    }
                });
                mp.prepareAsync();
                return "playing: " + label;
            }
        }, 5000);
    }

    /**
     * 创建常驻全屏视频浮层 + MediaPlayer（只在主线程、overlay 为空时调）。
     * setZOrderOnTop(true)：surface 直接放在窗口之上，不依赖 SurfaceView 默认的
     * 「挖洞穿透」合成方式，在这台老 MTK 平台上显示最稳。
     *
     * <p>FLAG_NOT_TOUCHABLE 同样是必须的：表情层铺满整屏，若它接收触摸就会把点击
     * 全部吞掉，用户没法操作机器人（与 DisplayController 同一个坑）。
     */
    private void ensureOverlay() {
        SurfaceView sv = new SurfaceView(ctx);
        sv.setZOrderOnTop(true);
        int type = WindowManager.LayoutParams.TYPE_PHONE; // system UID 免悬浮窗授权
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT, type,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE   // 不抢焦点，切换时不挤掉前台界面
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,  // ★ 别删：表情层只"看得见"，触摸要透传
                PixelFormat.TRANSLUCENT);
        wm.addView(sv, lp);
        overlay = sv;
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder h) {
                currentHolder = h;                    // 常驻窗口的物理 surface 状态，实时跟随
                try { if (player != null) player.setDisplay(h); } catch (Exception ignored) {}
            }
            @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) {}
            @Override public void surfaceDestroyed(SurfaceHolder h) {
                currentHolder = null;
            }
        });
        player = new MediaPlayer();
    }

    public String hide() {
        current = null;
        try {
            return MainThread.call(new Callable<String>() {
                @Override public String call() throws Exception { return hideInner(); }
            }, 3000);
        } catch (Exception e) {
            return "hide error: " + e;
        }
    }

    /**
     * 真正收尾的动作，只在主线程里被调。
     * overlay 可能只是因为上次 addView 失败而「有引用但没挂上去」，
     * 这时 removeView 会抛 not attached to window manager —— 忽略它并把引用清干净。
     */
    private String hideInner() {
        generation++;   // 让所有在途的异步回调（surfaceCreated/onPrepared）立即失效
        try {
            if (soundPlayer != null) { soundPlayer.release(); soundPlayer = null; }
            if (player != null) { player.release(); player = null; }
            currentHolder = null;
            if (overlay != null) {
                try {
                    wm.removeView(overlay);
                } catch (Exception e) {
                    Log.w(AgentService.TAG, "face removeView skipped: " + e);
                }
                overlay = null;
            }
            return "hidden";
        } catch (Exception e) {
            overlay = null;
            return "hide error: " + e;
        }
    }

    // ---------------- 原厂表情音效（res/raw/<名>_s.mp3）----------------

    /** 播放表情配套音效；没有该文件就静默跳过。 */
    public String playSound(String name) {
        try {
            File f = extractRaw(name + "_s.mp3");
            if (f == null) return "no sound asset";
            if (soundPlayer != null) { soundPlayer.release(); soundPlayer = null; }
            soundPlayer = new MediaPlayer();
            soundPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            soundPlayer.setDataSource(f.getAbsolutePath());
            soundPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) {
                    try { mp.release(); } catch (Exception ignored) {}
                    if (soundPlayer == mp) soundPlayer = null;
                }
            });
            soundPlayer.prepare();
            soundPlayer.start();
            return "sound ok";
        } catch (Exception e) {
            Log.e(AgentService.TAG, "playSound fail " + name, e);
            return "sound error: " + e;
        }
    }

    /** 该表情是否带原厂音效 */
    public static boolean hasSound(String name) {
        try {
            ZipFile z = apk();
            if (z == null) return false;
            boolean ok = z.getEntry("res/raw/" + name + "_s.mp3") != null;
            z.close();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    /** 从主 APK 的 res/raw 里抽任意资源到缓存目录 */
    private File extractRaw(String leaf) {
        try {
            ZipFile z = apk();
            if (z == null) return null;
            ZipEntry e = z.getEntry("res/raw/" + leaf);
            if (e == null) { z.close(); return null; }
            File out = new File(ctx.getCacheDir(), leaf);
            InputStream in = z.getInputStream(e);
            FileOutputStream fo = new FileOutputStream(out);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            fo.close(); in.close(); z.close();
            return out;
        } catch (Exception e) {
            Log.e(AgentService.TAG, "extractRaw fail " + leaf, e);
            return null;
        }
    }

    /**
     * 设音量（绝对值）。
     * <p>★ 委托给 {@link Av} —— 音量在整机只有**一个**出口：
     * {@code STREAM_MUSIC}、上下限、回读校验都在那儿，语音/网页/脚本/AI 标签共用。
     * 这里保留一个同名方法只是为了不惊动既有调用点。</p>
     */
    public String volume(int n) {
        return Av.set(n);
    }

    public String getCurrent() { return current; }
}
