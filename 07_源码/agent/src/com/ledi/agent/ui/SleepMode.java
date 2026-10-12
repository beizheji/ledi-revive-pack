package com.ledi.agent.ui;

import com.ledi.agent.core.BootLog;

import com.ledi.agent.core.AgentService;

import com.ledi.agent.core.MainThread;
import com.ledi.agent.motion.MotorPose;
import com.ledi.agent.cap.ProactiveTalk;
import com.ledi.agent.sdk.ProcessFreezer;
import com.ledi.agent.voice.ReactionEngine;
import com.ledi.agent.sense.SensorHub;
import com.ledi.agent.net.UdpBeacon;
import com.ledi.agent.voice.VoiceDialog;
import com.ledi.agent.voice.VoiceWakeController;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 屏保 / 休息模式：屏幕<b>铺满一张图</b>，同时把机器人功能<b>闸住</b>，
 * 直到用户轻点屏幕、或从电脑端 {@code POST /sleep {"on":false}} 把它叫醒。
 *
 * <h3>为什么要"闸"而不是"藏"</h3>
 * 光把图铺上去是不够的：唤醒词、摸头顶、被拿起、到点闲聊都会立刻让机器人
 * 出声、做动作、弹表情 —— 屏保就成了一张随时被撕掉的纸。所以进入时按
 * 「先掐出声的、再掐会动的、最后放麦」的顺序停掉六样东西。
 *
 * <h3>两条不能省的纪律</h3>
 * <ol>
 *   <li><b>没有图就不许进。</b>进去等于全屏黑，用户会以为机器人坏了，
 *       而唯一出路只剩电脑端 —— 比"没进屏保"糟得多。</li>
 *   <li><b>闸不能用会写盘的开关。</b>{@code ReactionEngine.set()} 会把
 *       {@code ai_on}/{@code native_on} 写进 SharedPreferences —— 而屏保期间进程被
 *       CrashGuard 重启是这台机器的常态，那样用户的自带反应就被一次屏保<b>永久</b>关掉了。
 *       所以走内存临时闸 {@code setSleepGate()}：进程一没即自动失效，不留痕迹。</li>
 * </ol>
 *
 * <p>显示层见 {@link SleepScreen}（可触摸、真背光）；命令总闸见
 * {@code CommandRouter} 入口处的 {@code sleeping} 判断。
 */
public final class SleepMode {

    /** 现在是不是屏保中。{@code CommandRouter} 的总闸读它。 */
    public static volatile boolean sleeping;

    /**
     * ★★★ v142：屏保状态**必须落盘**。
     *
     * <h3>为什么（这一条解释了用户报的两个现象）</h3>
     * 在这之前 {@link #sleeping} 只是一个静态字段 —— 它<b>随进程一起消失</b>。
     * 而"屏保期间进程被 CrashGuard 拉起来"是这台机器的常态（正是 §十五 那套复活机制
     * 存在的理由）。一旦发生：
     * <pre>
     *   屏保浮层 → 随进程消失（窗口没了）⇒ 用户看到"屏保被强行关闭了"
     *   sleeping  → 归零 false          ⇒ 全部功能（唤醒/动作/主动说话/API）原地复活
     *   sleepGate → 归零 false（内存闸） ⇒ ReactionEngine 又敢碰 SDK 了
     *   但原厂还是 SIGSTOP 冻着的       ⇒ 碰 SDK 就是在 binder 上永久等下去
     * </pre>
     * 于是"躺下"这类会触发自带反应的动作，反而成了最容易把机器人从屏保里
     * 拽出来（并让按键线程堵死）的路径。
     *
     * <p>所以屏保状态写进 SharedPreferences，{@code AgentService.onCreate} 起来先读它，
     * 发现"上次是睡着的"就**原地把屏保重新铺上**。这是本项目
     * "判据不能随着被测对象一起死"在屏保上的落地。</p>
     */
    private static final String PREFS = "ledi_agent";
    private static final String K_SLEEP = "sleep_on";
    private static final String K_ENTER = "sleep_enter_ms";
    private static final String K_IDLE = "sleep_idle_sec";

    /** 无交互多久自动进屏保（秒）。用户要求 10 分钟。 */
    public static final int IDLE_DEFAULT_SEC = 600;

    /**
     * 屏保亮度系数（0..1，1 = 不压暗）。
     *
     * <p>★★ 默认 <b>1.0f</b>（用户 2026-09-24：「机器人的屏幕保护亮度改到 100 吧」）。
     * v121-v157 一直是 0.75f —— 那是叠一层 alpha=64 的黑。这个 ROM 关不掉背光
     * （{@code _bl_probe.txt}：进出屏保 {@code lcd-backlight} 都是 255），所谓
     * "屏保亮度"其实是**画面压暗**（见 {@link SleepScreen}）
     * ⇒ 1.0 = 屏保图按原亮度显示，不再压暗。
     *
     * <p>⚠ 本值**不落盘**（与 {@link #sleeping} 不同）：进程被 CrashGuard 拉起后
     * 回到这里的默认值。所以"改成 100"是靠**改默认值**落地的，不是改一次运行值 ——
     * 想改永久值就得改这个常量。临时覆盖仍可走
     * {@code POST /sleep {"on":true,"bright":0.8}}，但重启即回 1.0。
     */
    private static volatile float bright = 1.0f;
    private static volatile String imageInfo = "";       // 诊断：图片来自哪个文件、多大
    private static volatile long enterMs;
    /** 最近一次"用户交互"的时刻（唤醒/对话/按键/触摸/远程命令）—— 闲置计时用。 */
    private static volatile long lastActMs = 0;
    /** 进程启动时读到的"上次是睡着的"（供 /sleep 与日志解释）。 */
    private static volatile boolean restoredFromDisk = false;

    private SleepMode() {}

    public static float bright() { return bright; }

    private static android.content.SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 闲置超时（秒），0 = 关闭自动进屏保。 */
    public static int idleSec(Context ctx) {
        try { return prefs(ctx).getInt(K_IDLE, IDLE_DEFAULT_SEC); } catch (Throwable t) { return IDLE_DEFAULT_SEC; }
    }

    public static String setIdleSec(Context ctx, int sec) {
        if (sec < 0) sec = 0;
        if (sec > 0 && sec < 30) sec = 30;          // 太短会变成"刚醒就睡"
        try { prefs(ctx).edit().putInt(K_IDLE, sec).apply(); } catch (Throwable ignored) {}
        BootLog.i("屏保 闲置超时改为 " + sec + "s");
        return "idle_sec=" + sec;
    }

    /**
     * 把外部传进来的 bright 归一化到 (0,1]。
     *
     * <p>为什么需要：用户报需求时的口径是"改到 100"（百分数），而内部是 0..1 系数。
     * 旧代码拿到 100 会算出 {@code dim_alpha = (1-100)*255 = -25245} —— 负数虽然
     * 不会进 colorFilter（有 {@code dimA > 0} 挡着），但 {@code /sleep} 的 json 会
     * 报一个负数，看着像坏了。这里统一：{@code >1} 当百分数除 100，再夹到 [0.05,1]。
     *
     * <p>下界取 0.05 而不是 0：0 会让屏保**全黑**，用户会以为机器人坏了，
     * 而唯一出路只剩远程 —— 与本项目"绝不做一个进去了出不来的状态"一致。
     */
    public static float normBright(float b) {
        if (b > 1f) b = b / 100f;          // 按百分数给也能认（100 -> 1.0）
        if (b > 1f) b = 1f;
        if (b < 0.05f) b = 0.05f;
        return b;
    }

    /** 记一次"有人在用"（重置闲置计时）。所有交互入口都该调它。 */
    /**
     * 深睡开关（prefs {@code sleep_deep}）—— <b>默认 true</b>。
     *
     * <p>true = 进屏保时冻**原厂主进程**（用户 2026-10-06 要求"停止主程序与 agent"）；
     * false = 回到 v142 的保守档（只冻子进程，保住头顶键广播）。
     * 代价见 {@link #enter} 里那段注释 —— 开了它，头顶键就叫不醒。</p>
     */
    public static boolean deepOn(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean("sleep_deep", true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static String setDeep(Context ctx, boolean on) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean("sleep_deep", on).apply();
        } catch (Throwable ignored) {}
        BootLog.i("深睡开关 = " + on);
        return "deep=" + on;
    }

    public static void touch() {
        lastActMs = System.currentTimeMillis();
    }

    /** 距上次交互多少毫秒（从未交互返回 -1）。 */
    public static long idleAgoMs() {
        return lastActMs == 0 ? -1 : System.currentTimeMillis() - lastActMs;
    }

    private static void persist(Context ctx, boolean on) {
        try {
            prefs(ctx).edit().putBoolean(K_SLEEP, on)
                    .putLong(K_ENTER, on ? enterMs : 0L).apply();
        } catch (Throwable t) {
            BootLog.w("屏保 落盘失败 " + t);
        }
    }

    /**
     * 进程启动时调用：如果**上一次进程死的时候是屏保态**，把屏保原地恢复。
     *
     * @return 恢复了返回 true
     */
    public static synchronized boolean restoreIfWasSleeping(Context ctx) {
        boolean was;
        try { was = prefs(ctx).getBoolean(K_SLEEP, false); } catch (Throwable t) { return false; }
        if (!was) return false;
        restoredFromDisk = true;
        long em = 0;
        try { em = prefs(ctx).getLong(K_ENTER, 0L); } catch (Throwable ignored) {}
        BootLog.w("屏保 上次进程死时是屏保态（enter_ms=" + em + "），原地恢复");
        // 先把原厂解冻再重新闸 —— v141 的教训：冻着的时候我们死了，
        // 若直接冻结/闸功能，可能留下半冻状态；先无条件解冻一次是幂等且安全的。
        try { ProcessFreezer.thawAllAsync(ctx); } catch (Throwable ignored) {}
        String r = enter(ctx, -1f);
        BootLog.i("屏保 恢复结果 " + r + " sleeping=" + sleeping);
        return sleeping;
    }

    // ------------------------------------------------------------------ 图片

    private static File imgFile(Context ctx) {
        return new File(ctx.getFilesDir(), "sleep.jpg");
    }

    /** 保存屏保图片（base64）。存内部目录：不依赖外置存储权限，也不怕被清理。 */
    public static String saveImage(Context ctx, String b64) {
        if (b64 == null || b64.isEmpty()) return "base64 required";
        try {
            byte[] bin = Base64.decode(b64, Base64.DEFAULT);
            if (bin.length == 0) return "base64 decode -> 0 byte";
            File f = imgFile(ctx);
            FileOutputStream fo = new FileOutputStream(f);
            try {
                fo.write(bin);
                fo.flush();
            } finally {
                fo.close();
            }
            // ★ 立刻回读校验：这个项目踩过"大文件写入偶发不落盘"的坑，
            //   不校验就会出现"上传成功、屏保全黑"。
            byte[] back = readAll(f);
            boolean ok = back.length == bin.length;
            Bitmap b = BitmapFactory.decodeByteArray(bin, 0, bin.length);
            imageInfo = f.getName() + " " + bin.length + "B verify=" + ok
                    + (b == null ? "" : " " + b.getWidth() + "x" + b.getHeight());
            String r = "image saved: " + imageInfo;
            if (!ok) r += "（★回读字节数不符，落盘可能失败）";
            if (b == null) r += "（★解码失败：不是有效图片，屏保会被拒）";
            BootLog.i("屏保 " + r);
            return r;
        } catch (Exception e) {
            return "save fail: " + e;
        }
    }

    private static Bitmap loadImage(Context ctx) {
        try {
            File f = imgFile(ctx);
            if (!f.exists()) return null;
            byte[] d = readAll(f);
            if (d.length == 0) return null;
            return BitmapFactory.decodeByteArray(d, 0, d.length);
        } catch (Throwable t) {
            BootLog.w("屏保 读图失败 " + t);
            return null;
        }
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------------ 进入 / 退出

    /** 进入屏保（幂等：已在屏保中则只按新参数重铺一次）。 */
    public static synchronized String enter(final Context ctx, float b) {
        if (b > 0f) bright = normBright(b);
        Bitmap bmp = loadImage(ctx);
        if (bmp == null) {
            // ★ 没有图就不进 —— 见类注释第 1 条纪律
            return "no sleep image: 先用 POST /sleep {\"img\":\"<base64>\"} 上传一张";
        }
        if (!sleeping) {
            sleeping = true;
            enterMs = System.currentTimeMillis();
            touch();                              // 进屏保本身就是一次"状态变更"，重置闲置
            persist(ctx, true);
            gateEverything(ctx);
            // ★ 用户要求（2026-09-24）：「屏保开启时机器人其他软件先都停止」。
            //   放在 gateEverything 之后：先把我们自己的功能停干净（TTS/动作/表情/反应/
            //   主动说话/唤醒），再去冻原厂 —— 反过来的话，冻住期间原厂收不到我们的
            //   停止请求，动作可能停在半路。异步执行，不拖慢屏保显示。
            //
            //   ★★★ v142：冻结范围默认改成 **sub（只冻子进程）**。
            //   理由必须写清楚，因为它是"长按头顶键叫不醒"的头号嫌疑：
            //   头顶键的广播（com.auric.intell.xld.rbt.key.head.*）是**原厂那边发出来的**，
            //   而 v140/v141 把原厂主进程也 SIGSTOP 了 —— 冻住的进程发不出广播，
            //   于是屏保里唯一那个"人手按得动"的出口可能**从源头就断了**。
            //   v141 验收只证明了"3 个进程都进了 T"，没有验过"冻着的时候长按还能不能醒"
            //   （这两件事完全不同，前者是机制、后者是出口）。
            //   ⇒ 保守默认 sub：省电省掉子进程那部分，同时保住按键通路。
            //     真机对照实验 POST /sleep {"freeze":"all"} 之后确认长按仍能醒，再考虑 all。
            //
            // ★★★ v190：用户 2026-10-06 明确要求「长按头顶键进入休眠模式后**停止机器人主程序
            //   与 agent**，包括发呆」⇒ 默认改成冻 **all**（含原厂主进程）。
            //   ★ 代价必须说清楚：头顶键广播是原厂发的，冻住主进程后就没人发了 ⇒
            //     **长按叫不醒**，只能 HTTP `POST /sleep {"on":false}` 或 adb 恢复。
            //   开关留在 prefs `sleep_deep`（`POST /deepsleep {"on":false}` 退回 sub），
            //   所以不需要重打包就能退回去 —— 这条是踩过坑之后定的规矩。
            String fm = deepOn(ctx) ? ProcessFreezer.MODE_ALL : ProcessFreezer.mode(ctx);
            try { ProcessFreezer.freezeAsync(ctx, fm); } catch (Throwable t) {
                BootLog.w("屏保 冻结原厂失败 " + t);
            }
            // ★ v190：把"我们自己的活动"也停干净 —— 常识别（另一个循环，挂起唤醒引擎停不掉它）
            //   与"发呆"（待机微动/待机动作/情绪推进）。
            try { AgentService.onDeepSleep(true); } catch (Throwable t) {
                BootLog.w("屏保 深睡钩子失败 " + t);
            }
        }
        String r = SleepScreen.show(ctx, bmp, bright, exitRunnable(ctx));
        BootLog.i("屏保 进入 bright=" + bright + " → " + r);
        return "sleep on → " + r;
    }

    /** 叫醒。幂等：没睡着时只保证窗口不残留。 */
    public static synchronized String exit(Context ctx) {
        boolean was = sleeping;
        sleeping = false;
        persist(ctx, false);
        touch();
        SleepScreen.hide(ctx);
        if (!was) return "not sleeping (窗口已确保摘除)";
        // ★★ 第一件事必须是解冻原厂，且**同步等它落地**。
        //   顺序反了就是"唤醒引擎已经起来、原厂还冻着" ⇒ 唤醒引擎一调原厂 SDK 就
        //   挂在 binder 上（不是失败，是永远等下去），表现成"叫不醒但也没崩"。
        //   脚本只跑几十毫秒，同步的代价远小于这个风险。
        try { ProcessFreezer.thawSync(ctx); } catch (Throwable t) {
            BootLog.w("屏保 解冻原厂失败 " + t);
        }
        // ★ v190：解冻之后第一件事 —— 把"我们自己的活动"放回来（常识别由用户再开；发呆恢复）
        try { AgentService.onDeepSleep(false); } catch (Throwable t) {
            BootLog.w("屏保 退出深睡钩子失败 " + t);
        }
        // ★ v142：感应闸与心跳要在功能区之前放回来（见 gateEverything 的注释）
        try {
            SensorHub s = AgentService.sensor;
            if (s != null) s.setAsleepGate(false);
        } catch (Throwable t) { BootLog.w("屏保 恢复感应失败 " + t); }
        try {
            UdpBeacon b = AgentService.beaconNow();
            if (b != null) b.setPaused(false);
        } catch (Throwable t) { BootLog.w("屏保 恢复心跳失败 " + t); }
        // 恢复顺序与进入相反：先把"听"还回来，再放开行为
        try {
            VoiceWakeController v = AgentService.voiceWake;
            if (v != null) v.unsuspend();
        } catch (Throwable t) { BootLog.w("屏保 恢复唤醒失败 " + t); }
        try {
            ReactionEngine re = AgentService.reaction;
            if (re != null) re.setSleepGate(false);        // 只放临时闸，不动用户的 ai_on/native_on
        } catch (Throwable t) { BootLog.w("屏保 恢复反应引擎失败 " + t); }
        try {
            ProactiveTalk p = AgentService.proactiveTalk;
            if (p != null) p.start();
        } catch (Throwable t) { BootLog.w("屏保 恢复主动说话失败 " + t); }
        // ★ v147：把电机读回与调试面板还回来。两者互不依赖，但**必须在 thawSync 之后**
        //   （这里已经在后面了）—— 恢复读回要收原厂发的广播，原厂还冻着就一帧都收不到。
        try {
            MotorPose mp = AgentService.motorPose;
            if (mp != null) mp.setSleeping(false);
        } catch (Throwable t) { BootLog.w("屏保 恢复电机读回失败 " + t); }
        try {
            // ★ v148：log 面板（含调试控件）也一起还回来
            LogOverlay.onSleep(false);
        } catch (Throwable t) { BootLog.w("屏保 恢复 log 面板失败 " + t); }
        BootLog.i("屏保 退出（睡了 " + ((System.currentTimeMillis() - enterMs) / 1000) + "s）");
        return "sleep off（已恢复唤醒/反应/主动说话/心跳/感应）";
    }

    /**
     * 闸住六样：出声的 → 会动的 → 听声音的。
     * <p>顺序有讲究：先掐 TTS 再放麦，否则正在播的句子会在挂起唤醒之后还继续响。
     */
    private static void gateEverything(Context ctx) {
        // ---- ① 先掐"会往外发东西的"（v142 新增：心跳 / 对话 / 感应）----
        // 用户 2026-09-24 的原话：「屏保期间要停止连接 ai……没有用户希望机器人在
        // 不使用的时候一直向上传递语音消息消耗 API token」。所以顺序是先断外发通路，
        // 再停本地表现 —— 反过来的话，停本地那几百毫秒里仍可能有请求发出去。
        try {
            UdpBeacon b = AgentService.beaconNow();
            if (b != null) b.setPaused(true);              // 心跳整条停发（不是降频）
        } catch (Throwable t) { BootLog.w("屏保 停心跳失败 " + t); }
        try {
            VoiceWakeController v = AgentService.voiceWake;
            if (v != null) {
                VoiceDialog d = v.dialog();
                if (d != null && d.isActive()) d.stop();   // 正在听的这一轮立刻收掉
            }
        } catch (Throwable t) { BootLog.w("屏保 停对话失败 " + t); }
        try {
            if (AgentService.router != null && AgentService.router.tts() != null) {
                AgentService.router.tts().stopNow();
            }
        } catch (Throwable t) { BootLog.w("屏保 停播报失败 " + t); }
        try {
            if (AgentService.motion != null) AgentService.motion.stop();
        } catch (Throwable t) { BootLog.w("屏保 停动作失败 " + t); }
        try {
            if (AgentService.face != null) AgentService.face.hide();
        } catch (Throwable t) { BootLog.w("屏保 摘表情失败 " + t); }
        try {
            ReactionEngine re = AgentService.reaction;
            // ★ 用不写盘的临时闸（见 ReactionEngine.sleepGate）：屏保期间进程被 CrashGuard
            //   重启是常态，若走 set(false,false) 就会把用户的自带反应开关永久关掉。
            if (re != null) re.setSleepGate(true);
        } catch (Throwable t) { BootLog.w("屏保 停反应引擎失败 " + t); }
        try {
            ProactiveTalk p = AgentService.proactiveTalk;
            if (p != null) p.stop();
        } catch (Throwable t) { BootLog.w("屏保 停主动说话失败 " + t); }
        try {
            VoiceWakeController v = AgentService.voiceWake;
            if (v != null) v.suspend();
        } catch (Throwable t) { BootLog.w("屏保 挂起唤醒失败 " + t); }
        // ---- ② 最后才停感应与陀螺 ----
        // 放在最后、且与①分开：{@code setAsleepGate(true)} 会摘掉 IMU 监听并让
        // look-away/自带反应不再被触发。必须在 ① 之后，否则"躺下"这个动作可能在
        // 闸还没关上时触发一次自带反应 → 去碰（正被冻的）原厂 SDK。
        try {
            SensorHub s = AgentService.sensor;
            if (s != null) s.setAsleepGate(true);
        } catch (Throwable t) { BootLog.w("屏保 停感应失败 " + t); }
        // ---- ③ 电机读回 + 调试面板（v147）----
        // 用户要求：「**进入熄屏则停止返回 api**」。电机位置是原厂 ENGTATUS 广播驱动的
        // （几百毫秒一帧的常驻流），屏保期间留着它就是白白唤醒 CPU ⇒ 连接收器一起摘掉。
        // log 面板（含调试控件）也要摘：面板里的滑条能让用户**真去转电机**，
        // 而且我们这层(ty=2010)比屏保图更高，不收起来会直接盖在屏保上。
        // 放在最后：前面管的是"行为和发声"，这条只管我们自己的读回与浮层。
        try {
            MotorPose mp = AgentService.motorPose;
            if (mp != null) mp.setSleeping(true);
        } catch (Throwable t) { BootLog.w("屏保 停电机读回失败 " + t); }
        try {
            LogOverlay.onSleep(true);
        } catch (Throwable t) { BootLog.w("屏保 摘 log 面板失败 " + t); }
    }

    /**
     * 轻点屏幕后的收尾。
     * <p><b>不在触摸派发过程中拆自己的窗口</b>：{@code removeView} 一个正在处理
     * {@code ACTION_UP} 的 View，后面的事件派发会踩到已摘除的引用，
     * 所以推到消息队列下一轮再执行。
     */
    private static Runnable exitRunnable(final Context ctx) {
        return new Runnable() {
            @Override public void run() {
                MainThread.post(new Runnable() {
                    @Override public void run() { exit(ctx); }
                });
            }
        };
    }

    // ------------------------------------------------------------------ 状态

    public static String json(Context ctx) {
        // ★ 闲置字段必须在这儿（而不是只写在 prefs 里）：用户在电脑端问"10 分钟自动屏保
        //   到底生没生效"，唯一能自查的就是 /status 里这两个数 —— idle_sec 是配置值、
        //   idle_ago_s 是**当前已经闲置了多久**。少了后者，"为什么还不睡"就只能靠猜。
        int idle = idleSec(ctx);
        long ago = idleAgoMs();
        return "{\"sleeping\":" + sleeping
                + ",\"bright\":" + bright
                + ",\"enter_ago_ms\":" + (enterMs == 0 ? -1
                        : System.currentTimeMillis() - enterMs)
                + ",\"idle_sec\":" + idle
                + ",\"idle_ago_s\":" + (ago < 0 ? -1 : ago / 1000)
                + ",\"restored_from_disk\":" + restoredFromDisk
                // ★ v147：屏保是不是真的把电机读回停了 —— 用户要求的那个行为，
                //   必须有一个**当场可查**的判据，否则只能靠"感觉它没返回了"。
                + ",\"motor_paused\":" + (AgentService.motorPose == null
                        ? "null" : String.valueOf(AgentService.motorPose.isSleeping()))
                // ★ v149：log 面板（含调试控件）也同理。屏保期间 /logwin 被总闸挡着，
                //   不在 /sleep 里报这两项，"面板确实收起了、开关还留着"就没有任何端点可查。
                //   panel_shown = 窗口还在不在（期望 false）；panel_on = 用户的开关（期望不变）。
                //   ★ 两个都要报：只看 panel_shown 分不清"被屏保收起"和"用户自己关了"。
                + ",\"panel_shown\":" + LogOverlay.isShown()
                + ",\"panel_on\":" + LogOverlay.isEnabled()
                + ",\"image\":\"" + esc(imageInfo) + "\""
                + ",\"gate\":{\"wake_suspended\":"
                + (AgentService.voiceWake != null && AgentService.voiceWake.isSuspended())
                + ",\"reaction\":"
                + (AgentService.reaction == null ? "null" : AgentService.reaction.json())
                + ",\"proactive_enabled\":"
                + (AgentService.proactiveTalk == null ? "null"
                        : String.valueOf(AgentService.proactiveTalk.enabled()))
                + "},\"screen\":" + SleepScreen.json() + "}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
