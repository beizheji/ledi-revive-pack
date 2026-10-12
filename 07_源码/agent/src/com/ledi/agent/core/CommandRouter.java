package com.ledi.agent.core;

import com.ledi.agent.cap.Abilities;
import com.ledi.agent.net.AdbWifi;
import com.ledi.agent.cap.AiCore;
import com.ledi.agent.voice.AsrProbe;
import com.ledi.agent.motion.Attitude;
import com.ledi.agent.voice.Av;
import com.ledi.agent.sense.Battery;
import com.ledi.agent.net.BleProvisionServer;
import com.ledi.agent.net.BtCommandServer;
import com.ledi.agent.net.BtPan;
import com.ledi.agent.cap.CapIndex;
import com.ledi.agent.voice.CloudAsr;
import com.ledi.agent.ui.DisplayController;
import com.ledi.agent.cap.Emotion;
import com.ledi.agent.sdk.FaceController;
import com.ledi.agent.ui.GearOverlay;
import com.ledi.agent.sense.HeadKey;
import com.ledi.agent.ui.HomeSetter;
import com.ledi.agent.net.HttpServer;
import com.ledi.agent.sense.InputInject;
import com.ledi.agent.voice.IntentResolver;
import com.ledi.agent.voice.LocalWake;
import com.ledi.agent.ui.LogOverlay;
import com.ledi.agent.voice.MediaPlayback;
import com.ledi.agent.cap.Memory;
import com.ledi.agent.voice.MicController;
import com.ledi.agent.motion.MotionController;
import com.ledi.agent.motion.MotorPose;
import com.ledi.agent.net.PcLink;
import com.ledi.agent.motion.PoseController;
import com.ledi.agent.cap.Presence;
import com.ledi.agent.cap.ProactiveTalk;
import com.ledi.agent.sdk.ProcessFreezer;
import com.ledi.agent.ui.ProgrammerActivity;
import com.ledi.agent.voice.ReactionEngine;
import com.ledi.agent.ui.RotationLock;
import com.ledi.agent.ui.ScreenCast;
import com.ledi.agent.sdk.SdkController;
import com.ledi.agent.sense.SensorHub;
import com.ledi.agent.ui.SleepGate;
import com.ledi.agent.ui.SleepMode;
import com.ledi.agent.ui.SleepScreen;
import com.ledi.agent.sense.SoundSourceLocator;
import com.ledi.agent.voice.Speaker;
import com.ledi.agent.cap.TalkLog;
import com.ledi.agent.cap.Thinking;
import com.ledi.agent.cap.Tone;
import com.ledi.agent.voice.TtsController;
import com.ledi.agent.net.UdpBeacon;
import com.ledi.agent.voice.VoiceDialog;
import com.ledi.agent.voice.VoiceWakeController;
import com.ledi.agent.net.WifiProvisioner;
import com.ledi.agent.cap.Workflow;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;

/**
 * 共享指令路由：HTTP(8899) 与 蓝牙 SPP 直连 共用同一套指令。
 *
 * 蓝牙协议（每行一条，UTF-8，\n 结尾）：
 *   请求：{"m":"POST","p":"/motion","b":"{\"name\":\"hug\"}"}
 *         或简写 "GET /health"（无 body 的调试形式）
 *   响应：一行 JSON，与 HTTP 返回体完全一致。
 *
 * 新增路由（相对 v1.0）：
 *   GET  /status                        详细状态（电量/音量/蓝牙/运行时长）
 *   POST /light  {color,action}         灯光：color=r|g|b|y|p|all, action=on|off|blink.fast|blink.slow
 *   POST /display {text}                机器人屏幕显示文字
 *   POST /img {data,max}                机器人屏幕显示图片（base64，电脑端读文件后送来）
 *   POST /displayoff                    清除屏幕文字 / 图片
 *   POST /mic    {on,seconds}           机器人麦克风录音（HTTP /micfile 取音频）
 *   GET  /miclist                       录音文件列表
 *   POST /gear   {on}                   显示/隐藏屏幕左上角白色齿轮设置入口
 *   GET  /motions_all                   全部动作（不受启用配置影响）
 *   GET  /motionconfig                  动作启用配置
 *   POST /motionconfig {enabled:[..]}   设置启用动作（空=全部）
 */
public class CommandRouter {

    private static final String PREFS = "ledi_agent";

    private final Context ctx;
    private final MotionController motion;
    private final FaceController face;
    private final Speaker speaker;
    private final WifiProvisioner wifi;
    private final BleProvisionServer ble;
    private final PoseController pose;
    private final MicController mic;
    private final DisplayController display;
    private volatile SensorHub sensor;
    private volatile ReactionEngine reaction;
    private volatile BtCommandServer bt;
    private volatile TtsController tts;
    private volatile SdkController sdk;
    // newcore 重构新增：AI 大脑 / 主动说话 / 媒体播放
    private volatile AiCore ai;
    private volatile ProactiveTalk ptalk;
    private volatile MediaPlayback media;
    private volatile VoiceWakeController voiceWake;
    private volatile SoundSourceLocator locator;
    /** 在线听写（讯飞 MSC）。懒建，因为它要 Context 且初始化必须走主线程。 */
    private volatile CloudAsr cloudAsr;

    /**
     * 在线听写。
     *
     * <p>★ 必须走 {@link CloudAsr#get} 拿<b>全局唯一实例</b>：对话链路
     * （{@code VoiceWakeController}）也要用它，各自 new 一个就会有<b>两份忙闲状态</b>，
     * 谁也挡不住谁 —— 两个线程同时在一个 {@code SpeechRecognizer} 上开会话。</p>
     */
    private CloudAsr cloud() {
        CloudAsr c = cloudAsr;
        if (c == null) {
            c = CloudAsr.get(ctx);
            cloudAsr = c;
        }
        return c;
    }

    public CommandRouter(Context ctx, MotionController motion, FaceController face,
                         Speaker speaker, WifiProvisioner wifi, BleProvisionServer ble,
                         MicController mic, DisplayController display) {
        this.ctx = ctx;
        this.motion = motion;
        this.face = face;
        this.speaker = speaker;
        this.wifi = wifi;
        this.ble = ble;
        this.mic = mic;
        this.display = display;
        this.pose = new PoseController(motion);
        this.tts = new TtsController(ctx);
    }

    public TtsController tts() { return tts; }

    /** PoseController 出口：{@link MotorPose} 的回退通道要用（写姿态文件 + 按名执行）。 */
    public PoseController pose() { return pose; }

    public void setSdk(SdkController s) { this.sdk = s; }

    public SdkController sdk() { return sdk; }

    public void setSensor(SensorHub s) { this.sensor = s; }

    public void setReaction(ReactionEngine r) { this.reaction = r; }

    public SensorHub sensor() { return sensor; }
    public ReactionEngine reaction() { return reaction; }

    public void setBt(BtCommandServer bt) { this.bt = bt; }

    public BtCommandServer bt() { return bt; }
    public Speaker speaker() { return speaker; }
    public WifiProvisioner wifi() { return wifi; }
    public MicController mic() { return mic; }
    /** v207：能力真源要读"屏幕正在显示什么"，加这条 getter（display 原本只有私有字段）。 */
    public DisplayController display() { return display; }

    // newcore 重构：注入 AI 大脑 / 主动说话 / 媒体播放
    public void setAi(AiCore a) { this.ai = a; }
    public void setProactiveTalk(ProactiveTalk p) { this.ptalk = p; }
    public void setMedia(MediaPlayback m) { this.media = m; }
    public void setVoiceWake(VoiceWakeController v) { this.voiceWake = v; }
    public void setLocator(SoundSourceLocator l) { this.locator = l; }
    public SoundSourceLocator locator() { return locator; }
    public AiCore ai() { return ai; }
    public ProactiveTalk proactiveTalk() { return ptalk; }
    public MediaPlayback media() { return media; }
    public VoiceWakeController voiceWake() { return voiceWake; }

    /**
     * 从 body 取 "key":"value" 或 query 里 key=value。
     *
     * <h3>为什么不能只按分隔符裸切（v10 的血泪教训）</h3>
     * 早先的实现是 {@code body.split("[{},]")} 然后找 {@code key:value} —— 那个字符类是
     * <b>「[」「{」「}」「,」四个字符</b>，于是任何<b>值里含逗号或方括号</b>的参数都会被腰斩：
     * <pre>
     *   POST /pose {"name":"x","spec":"start[400,-60,0,60],[400,60,0,-60]end"}
     *   → split 后 "spec" 只拿到 "start"        → "ERROR bad pose syntax: start"
     *   POST /display {"text":"你好,乐迪"}
     *   → text 只拿到 "你好"                    → 屏幕只显示半句
     *   POST /motionconfig {"enabled":["hug","cheer"]}
     *   → enabled 只拿到一个开头片段            → 白名单只存进 1 个动作
     * </pre>
     * 姿态是这个项目最核心的 AI 能力，网关的 {@code AgentLink.define_pose} 又正是
     * 用 POST body 下发 ── 等于「AI 生成的姿态永远写不进机器人」。
     *
     * <p>现在改成：<b>先按标准 JSON 取引号内的字符串</b>（正确处理 {@code \" \\ \n} 转义），
     * 取到就返回；取值是数字/布尔/数组或 body 不是 JSON 时，才回落到原来的宽松解析。
     * query 那一路保持原样（按 {@code &} 切，逗号/方括号本来就是安全的）。
     */
    /** 取一个整数参数（缺失 / 非法就返回 default）。用于 /motor 这类数值入参。 */
    static int intOr(String s, int dflt) {
        if (s == null || s.isEmpty()) return dflt;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return dflt; }
    }

    public static String field(String body, String query, String key) {
        // ---------- 1) 规范 JSON 字符串取值 ----------
        if (body != null && body.length() > 0) {
            String pat = "\"" + key + "\"";
            int i = body.indexOf(pat);
            while (i >= 0) {
                int c = i + pat.length();
                while (c < body.length() && isWs(body.charAt(c))) c++;
                if (c < body.length() && body.charAt(c) == ':') {
                    c++;
                    while (c < body.length() && isWs(body.charAt(c))) c++;
                    if (c < body.length() && body.charAt(c) == '"') {
                        // 先把字面量整段取出来，再统一走 J.unescape。
                        // 原来是在这里逐字符手写一份转义处理，和 jsonStr() 的那份各写各的，
                        // 两份都不认 \\uXXXX —— 见 J.unescape 的注释（中文全变乱码那个坑）。
                        int start = c + 1;
                        int k = start;
                        while (k < body.length()) {
                            char ch = body.charAt(k);
                            if (ch == '\\') { k += 2; continue; }
                            if (ch == '"') break;
                            k++;
                        }
                        return J.unescape(body.substring(start, Math.min(k, body.length())));
                    }
                    break;   // 值不是字符串（数字/布尔/数组）→ 交给下面的宽松解析
                }
                i = body.indexOf(pat, i + 1);
            }
        }
        // ---------- 2) 宽松解析（无引号的裸 KV / query）----------
        try {
            for (String kv : body.split("[{},]")) {
                kv = kv.trim().replace("\"", "");
                int c = kv.indexOf(':');
                if (c > 0 && kv.substring(0, c).trim().equals(key)) {
                    return kv.substring(c + 1).trim();
                }
            }
            for (String kv : query.split("&")) {
                String[] p = kv.split("=", 2);
                if (p.length == 2 && p[0].equals(key))
                    return java.net.URLDecoder.decode(p[1], "UTF-8");
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    /** 取字符串数组并合成逗号分隔串（供 /motionconfig 用；见 field 里那段说明）。 */
    static String listCsv(String body, String query, String key) {
        List<String> l = listField(body, query, key);
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (sb.length() > 0) sb.append(",");
            sb.append(s);
        }
        return sb.toString();
    }

    /**
     * 取字符串数组：body 里 {@code "names":["hug","cheer"]}，或 query 里 {@code names=hug,cheer}。
     * 原有的 {@link #field} 会按 {@code [{},]} 切分，取不了数组，所以单开一个。
     */
    static List<String> listField(String body, String query, String key) {
        List<String> out = new ArrayList<String>();
        if (body != null && !body.isEmpty()) {
            int i = body.indexOf("\"" + key + "\"");
            if (i >= 0) {
                int lb = body.indexOf('[', i);
                int rb = lb < 0 ? -1 : body.indexOf(']', lb + 1);
                if (lb >= 0 && rb > lb) {
                    for (String t : body.substring(lb + 1, rb).split(",")) {
                        t = t.trim().replace("\"", "");
                        if (!t.isEmpty()) out.add(t);
                    }
                    if (!out.isEmpty()) return out;
                }
            }
        }
        for (String t : field("", query, key).split(",")) {
            t = t.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // ================= 屏幕面板上的功能开关 =================
    // ★ v151 一度在这里加过一套"开关中枢"（SW_NAME/switchStates/switchToggle），
    //   给屏幕面板底部的开关条用。用户 2026-09-24 当场否掉：「**功能面板去掉**」——
    //   于是开关条、以及这套只有它一个调用方的方法一起删除，不留死代码。
    //   （记住那条设计本身是对的：面板要改开关就该走 {@link #handle} 的同一条 act，
    //     不要自己另写一套 prefs/SDK 调用 —— 要加回来时照这个原则来。）

    /**
     * 共享指令路由（HTTP 8899 / 蓝牙 SPP / 网页控制台共用）。
     *
     * <p>★ v221 结构重构：本方法原先是一个 <b>2,512 行、内含 199 个端点</b>的 if 链。
     * 现在收敛为五段 —— 屏保总闸 → {@code /console/} 前缀 → 屏保/深睡 →
     * 有人在用计时 → <b>查表派发</b>。各域处理体<b>逐字未改</b>地搬进
     * {@code xxxRoutes()}；端点 → 域的归属只在静态表 {@link #TABLE} 里写一次，
     * 可用机械对差脚本复核。</p>
     */
    public String handle(String method, String route, String query, String body) {
        String act = method + " " + route;
        try {
            // ================= 屏保总闸（必须最早）=================
            // ★ v221：这份白名单（含 v128~v214 每一条的放行理由）已抽成**声明式表**，
            //   见 {@link SleepGate} —— 加减放行项只改那一处（ALLOW），并由
            //   _sleepgate_check.py 对差 + SleepGate.selftest() 行为自检兜底。
            if (SleepGate.blocked(act)) return SleepGate.deny();
            // ================= 网页控制台兼容层（v192）=================
            // 必须**在**屏保总闸之后、其它路由之前：GET 那一族已经在闸门里放行了，
            // 而 POST 的（/console/tts 等）要照常被闸住。
            if (route.startsWith("/console/")) {
                return consoleCompat(method, route, query, body);
            }
            // ================= 屏保 / 休息 =================
            // GET  /sleep                            → 状态
            // POST /sleep {"on":true,"bright":1.0}   → 铺满图 + 闸住功能（bright 缺省=1.0 不压暗）
            // POST /sleep {"on":false}               → 叫醒
            // POST /sleep {"img":"<base64>"}         → 只上传/更换屏保图片
            if ("GET /sleep".equals(act)) {
                return "{\"sleep\":" + SleepMode.json(ctx) + "}";
            }
            if ("POST /sleep".equals(act)) {
                String img = field(body, query, "img");
                if (img.isEmpty()) img = field(body, query, "data");
                StringBuilder sb = new StringBuilder("{\"before\":");
                sb.append(SleepMode.json(ctx));
                if (!img.isEmpty()) {
                    sb.append(",\"image\":\"").append(J.esc(SleepMode.saveImage(ctx, img)))
                            .append("\"");
                }
                String on = field(body, query, "on");
                float br = -1f;
                String bs = field(body, query, "bright");
                if (!bs.isEmpty()) {
                    try { br = Float.parseFloat(bs.trim()); } catch (Exception ignored) {}
                }
                // ★ v142 三个"改配置"的可选旋钮。它们与 on=true/false **互不冲突**：
                //   只给旋钮不写 on，就是"只改配置、不动屏保状态"（下面那段 if/else 里
                //   最后那个 else 分支会如实回一句"需要 on 或 img"，这是刻意的 ——
                //   不写 on 就不该误触发进入/退出）。
                //   · idle_sec  → 无交互多少秒自动进屏保（0=关；默认 600=10 分钟，用户要求）
                //   · hold_exit → 屏幕"长按 5s"这条安全绳开不开（默认开，见 SleepScreen）
                //   · freeze    → 冻结原厂的范围 sub|all|off（默认 sub，见 ProcessFreezer）
                String idleS = field(body, query, "idle_sec");
                if (!idleS.isEmpty()) {
                    try {
                        sb.append(",\"idle\":\"")
                                .append(J.esc(SleepMode.setIdleSec(ctx, Integer.parseInt(idleS.trim()))))
                                .append("\"");
                    } catch (Exception ignored) {}
                }
                String he = field(body, query, "hold_exit");
                if (!he.isEmpty()) {
                    boolean on2 = !("false".equalsIgnoreCase(he) || "0".equals(he)
                            || "off".equalsIgnoreCase(he));
                    SleepScreen.setHoldExit(on2);
                    sb.append(",\"hold_exit\":").append(on2);
                }
                String fz = field(body, query, "freeze");
                if (!fz.isEmpty()) {
                    sb.append(",\"freeze\":\"").append(J.esc(ProcessFreezer.setMode(ctx, fz)))
                            .append("\"");
                }
                if ("false".equalsIgnoreCase(on) || "0".equals(on) || "off".equalsIgnoreCase(on)) {
                    sb.append(",\"result\":\"").append(J.esc(SleepMode.exit(ctx))).append("\"");
                } else if ("true".equalsIgnoreCase(on) || "1".equals(on)
                        || "on".equalsIgnoreCase(on)) {
                    sb.append(",\"result\":\"").append(J.esc(SleepMode.enter(ctx, br)))
                            .append("\"");
                } else if (!img.isEmpty()) {
                    sb.append(",\"result\":\"only image saved (屏保状态未变)\"");
                } else {
                    sb.append(",\"result\":\"需要 on=true|false 或 img\"");
                }
                sb.append(",\"sleep\":").append(SleepMode.json(ctx)).append("}");
                return sb.toString();
            }
            // ================= ★★★ v190：深睡（长按头顶键进的那一档） =================
            // 查：GET  /deepsleep   → deep / sleeping / idle_stopped / freeze_mode
            // 设：POST /deepsleep {"on":true|false}   只改"进屏保时冻谁"（不动睡眠状态）
            //     POST /deepsleep {"now":true|false}  立刻进/出屏保（等价 /sleep on|off）
            //
            // ★ 为什么单独给一条路由、而不是塞进 /sleep 的参数里：
            //   "进屏保时冻谁"（配置）与"现在睡不睡"（状态）是**两件事**。混在一个参数里，
            //   就会出现"想改配置却顺手把机器人叫醒/弄睡"的事故 —— 这类"一个入口干两件事"
            //   在本项目已经反复出问题（见 /listenled 为什么 mode 与 on 分开）。
            //
            // ★ 这条路由是 v190 的**唯一远程解药**：deep 模式下原厂主进程被 SIGSTOP，
            //   头顶键广播就没人发了（SensorHub 类注释：按键是原厂发广播给我们的）。
            //   所以它被显式放进屏保白名单，见上面总闸那段。
            if ("GET /deepsleep".equals(act) || "POST /deepsleep".equals(act)) {
                if ("POST /deepsleep".equals(act)) {
                    String on = field(body, query, "on");
                    if (on.length() > 0) {
                        boolean v = !("false".equalsIgnoreCase(on) || "0".equals(on)
                                || "off".equalsIgnoreCase(on));
                        SleepMode.setDeep(ctx, v);
                    }
                    String now = field(body, query, "now");
                    if (now.length() > 0) {
                        boolean v = ("true".equalsIgnoreCase(now) || "1".equals(now)
                                || "on".equalsIgnoreCase(now));
                        if (v) SleepMode.enter(ctx, -1f); else SleepMode.exit(ctx);
                    }
                }
                return "{\"ok\":true,\"deep\":" + SleepMode.deepOn(ctx)
                        + ",\"sleeping\":" + SleepMode.sleeping
                        + ",\"idle_stopped\":" + AgentService.deepSleeping
                        + ",\"freeze_mode\":\"" + ProcessFreezer.mode(ctx) + "\""
                        + ",\"note\":\"deep=冻原厂主进程；冻住后头顶键不再上报，"
                        + "叫醒请 POST /sleep {\\\"on\\\":false} 或 POST /deepsleep {\\\"now\\\":false}\"}";
            }
            // ★★★ v142：**任何一条成功的 POST 都算"有人在用"**（闲置计时归零）。
            //
            // 为什么放在总闸之后、所有路由之前：闲置判据只能有**一处真话**
            // （{@code SleepMode.touch()}），逐个路由去补迟早会漏掉一个 ——
            // 那一个漏掉的端点就是"机器人永远不睡"的成因。放在这里，加新路由的人
            // 不需要记得回来补。
            //
            // ★ 只认 POST、**不认 GET**：电脑端控制台会定期 GET /status 轮询，
            //   若连那个也算交互，"挂着控制台就永远不进屏保"，用户要的省电直接落空。
            //   这正是本类里唯一需要区分 GET/POST 的地方。
            //
            // ★ 例外只有两个，且都是**实验室端点**（我方脚本会用，不代表用户在場）：
            //   /kill（自杀验收）、/revive（排闹钟）。不排除的话，跑一轮长时间的
            //   验收脚本会把闲置计时一直按着，"10 分钟自动进屏保"就永远测不出来。
            if (method != null && method.startsWith("POST")
                    && !"POST /kill".equals(act) && !"POST /revive".equals(act)) {
                SleepMode.touch();
            }
            // ================= 查表派发（v221）=================
            // ★ 端点 → 域的归属**只此一处**（下面的静态表）。查不到就走 noRoute，
            //   不会静默落到别的域 —— 由对差脚本兜底。
            String dom = TABLE.get(act);
            if (dom == null) return noRoute(act);
            String out = dispatch(dom, act, query, body);
            return out != null ? out : noRoute(act);
        } catch (Exception e) {
            return "{\"error\":\"" + J.esc(String.valueOf(e)) + "\"}";
        }
    }

    // ==================================================================
    // 端点处理体：按域拆分（v221）。
    // ★ 下面每个方法的体都逐字来自原 handle() 的 if 链（只去了 4 格缩进），
    //   if 条件原样保留 —— 行为按构造不变，diff 只需比对「谁的体搬去了哪」。
    // ==================================================================

    /** 自检 / 测试 / 采集 / KWS / 诊断。 */
    private String probeRoutes(String act, String query, String body) throws Exception {
        if ("GET /emotionselftest".equals(act)) {
            return "{\"ok\":true,\"report\":\"" + J.esc(Emotion.selftestOnDevice(ctx)) + "\"}";
        }
        if ("GET /toneselftest".equals(act)) {
            return "{\"ok\":true,\"report\":\"" + J.esc(Tone.selftest()) + "\"}";
        }
        if ("GET /memoryselftest".equals(act)) {
            return "{\"ok\":true,\"report\":\"" + J.esc(Memory.selftest()) + "\"}";
        }
        if ("GET /motordiag".equals(act)) {
            MotorPose mp = AgentService.motorPose;
            if (mp == null) return "{\"error\":\"motorpose not init\"}";
            return mp.diag();
        }
        // ================= 姿态数学自检（v153） =================
        // GET /attitudetest → 把"能静态判死"的三项断言摊开：重力不变式、roll 绕 Y、
        //   pitch 绕 X。不依赖真机、不依赖人手倾斜。详见 Attitude.selfTest()。
        if ("GET /attitudetest".equals(act)) {
            return Attitude.selfTest();
        }
        // ---- ★ v159：主线程崩溃盾的**验收探针**（只判定，不影响任何正常链路） ----
        // 开：POST /crashprobe {"main":true}   往主线程投一个**必抛**的 Runnable，
        //     立即回执（不等结果 —— 等的话请求会跟着主线程一起废掉）。
        // 看：GET /status
        //     · 盾生效  → crashguard.mt_shield +1、**pid 不变**、revive.proc_starts 不变
        //     · 盾没生效→ pid 变、3s 后 CrashGuard 复活（proc_starts +1、crashes 归零）
        // ★ 为什么必须造得出这一次崩溃：如果只能"等机器自己崩"，那这个加固就永远只能
        //   写"未验" —— 而"未验的加固"和"没有加固"在判断上是同一件事。
        // ★ 风险边界：无任何自动调用点；不传 {"main":true} 时只回提示（同 POST /kill）。
        if ("POST /crashprobe".equals(act)) {
            if (!"true".equals(field(body, query, "main"))) {
                return "{\"ok\":false,\"error\":\"need {\\\"main\\\":true}\""
                        + ",\"hint\":\"往主线程投一个必抛 Runnable，会**真的崩**（自检崩溃盾用）\"}";
            }
            final String why = field(body, query, "why");
            MainThread.post(new Runnable() {
                @Override public void run() {
                    throw new RuntimeException("ledi v159 crash-shield probe"
                            + (why.isEmpty() ? "" : (" why=" + why)));
                }
            });
            return "{\"ok\":true,\"posted\":true"
                    + ",\"note\":\"已投递必抛 Runnable 到主线程；立刻 GET /status 看 "
                    + "crashguard.mt_shield 与 crashguard.pid\"}";
        }
        // 启动溯源日志（这台 ROM 屏蔽 App 的 logcat，排障只能看这个文件）
        if ("GET /bootlog".equals(act))
            return "{\"log\":\"" + J.esc(BootLog.tail(80)) + "\"}";
        if ("GET /abilitiesselftest".equals(act))
            return "{\"ok\":true,\"report\":\"" + J.esc(Abilities.selftest()) + "\"}";
        // 纯静态自检（不联网、不碰 org.json，只算数字）。
        if ("GET /workflowselftest".equals(act))
            return "{\"ok\":true,\"report\":\"" + J.esc(Workflow.selftest()) + "\"}";
        // ★ v206：灯语自检（纯静态，8 种布尔组合穷举 + 收场口径）。
        if ("GET /ledselftest".equals(act))
            return "{\"ok\":true,\"report\":\"" + J.esc(AgentService.ledSelftest()) + "\"}";
        if ("GET /capsselftest".equals(act))
            return "{\"ok\":true,\"report\":\"" + J.esc(CapIndex.selftest()) + "\"}";
        // 纯静态自检：不吃 Context、不联网、不碰 org.json —— 只算数字，随时可跑。
        if ("GET /thinkselftest".equals(act))
            return "{\"ok\":true,\"report\":\"" + J.esc(Thinking.selftest()) + "\"}";
        if ("GET /miclist".equals(act)) return jsonList(mic.list());
        // ================= 屏幕日志浮层 =================
        // POST /logwin {"on":true,"lines":12,"size":10,"x":0,"y":0}
        //   把实时日志铺在机器人屏幕左下角，站着说话就能看见，不用再去拉 adb/HTTP。
        //   字段都可省；on 只有传 "true"/"false" 才改开关。状态见 GET /logwin。
        //   layer / panel_layer 改窗口类型（phone|alert|overlay|priority|dialog|error|keyguard），
        //   用于真机上挑「既在原厂脸之上、又能点」的那一个 —— 类型是窗口属性，改了会重挂。
        if ("POST /logwin".equals(act)) {
            String on = field(body, query, "on");
            String r = LogOverlay.set(on,
                    field(body, query, "lines"),
                    field(body, query, "size"),
                    field(body, query, "x"),
                    field(body, query, "y"),
                    field(body, query, "layer"),
                    field(body, query, "panel_layer"));
            // ★ v148：原「开日志顺带开调试面板」的联动**已删除** ——
            //   触摸反馈 / 陀螺仪 3D / 电机滑条现在就在这一块面板里
            //   （见 LogOverlay.makePanel），「开日志」本身就等于「开面板」，
            //   再维护两套开关同步只会制造不一致。
            if ("true".equals(on)) {
                String fw = field(body, query, "follow");
                if (!"false".equalsIgnoreCase(fw) && !"0".equals(fw)) {
                    // 无操作：保留分支只为让老脚本传的 follow 参数仍被接受
                }
            }
            return r;
        }
        if ("GET /logwin".equals(act)) return LogOverlay.statusJson();
        if ("GET /poseread".equals(act))
            return "{\"content\":\"" + J.esc(pose.read(field(body, query, "name"))) + "\"}";
        // 离线唤醒体检：自己录 PCM 直接喂唤醒解码器，绕开喇叭/AEC，用于定性"喊话到底能不能被识别"
        // 用法：POST /waketest {"sec":6}  —— 请求会阻塞 sec 秒，期间对着机器人喊「哈喽乐迪」
        //
        //   ★ v181 起加 pcm= / gain= —— **回灌**设备上的裸 PCM（16k/16bit/mono）。
        //     用途：把"距离 / 音量 / 房间混响"三个变量一次锁死，只扫数字增益，
        //     从而确定性回答"这个解码器在真人语音上到底还能不能命中"。
        //     例：POST /waketest {"pcm":"/sdcard/hl_human.pcm","gain":4}
        if ("POST /waketest".equals(act)) {
            if (voiceWake == null) return "{\"error\":\"wake not ready\"}";
            int sec = 6;
            try { sec = Integer.parseInt(field(body, query, "sec")); } catch (Throwable ignore) {}
            String pcm = field(body, query, "pcm");
            float gain = 1.0f;
            try { gain = Float.parseFloat(field(body, query, "gain")); } catch (Throwable ignore) {}
            return voiceWake.offlineTest(sec, pcm, gain);
        }
        // ---- v183：自建唤醒的数据采集器 ----
        //
        // 查：GET  /recadir                             → 目录清单（名+字节），核对传输完整性
        // 采：POST /recaudio {"sec":6,"name":"pos1"}    → 录一段 16k/单声道/16bit 原始 PCM
        //        落 /sdcard/ledi_kws/<name>.pcm + 同名 .json（峰值/RMS/削顶/静音/时长）
        //        ★ v185 加 src= —— 选 android 音频源（1=MIC / 6=VOICE_RECOGNITION /
        //          7=VOICE_COMMUNICATION / 5=CAMCORDER / 1998=UNPROCESSED / 0=DEFAULT）。
        //          为什么必须能选：本机 MIC 源带 AEC+NS+AGC（证据见 recordRaw 的注释），
        //          AEC 会把"机器人自己喇叭的声音"完全抵消 ⇒ 自回环永远录不到东西；
        //          AGC 会让电平与源电平脱钩 ⇒ 峰值判据失效。要拿线性可复现的录音，
        //          得走不带语音处理的源。不传该字段 = 沿用 MIC（行为与旧版一致）。
        //
        // ★ 为什么要一个专门的采集端点：原厂那套百度离线引擎（KWS + 离线 ASR）
        //   在本机整族失效（KWS 与离线 ASR 均有失效实测），
        //   唤醒只能自建 —— 而自建算法的第一块砖就是**带标签的真实音频**。
        //   这个 ROM 是裸机、没有原厂录音 App，采集器必须自己提供。
        // ★ 采集是"收声"不是"发声"：不撕屏保，所以放行（见上面的白名单）。
        if ("GET /recadir".equals(act)) {
            return "{\"dir\":\"" + VoiceWakeController.KWS_DIR + "\",\"files\":"
                    + VoiceWakeController.kwsDirList() + "}";
        }
        if ("POST /recaudio".equals(act)) {
            if (voiceWake == null) return "{\"error\":\"wake not ready\"}";
            int sec = 6;
            try { sec = Integer.parseInt(field(body, query, "sec")); } catch (Throwable ignore) {}
            int asrc = -1;
            try { asrc = Integer.parseInt(field(body, query, "src")); } catch (Throwable ignore) {}
            return voiceWake.recordRaw(sec, field(body, query, "name"), asrc);
        }
        // ---- v183：自建本地唤醒内核（MFCC + VAD + DTW） ----
        //
        // 为什么要这几个端点：原厂百度引擎整族失效（KWS 与离线 ASR
        // 本轮实测 events=0），唤醒只能自己算。内核在 LocalWake 里，
        // 这里只做「喂文件、回数字」——**不在设备上写死模板**，
        // 因为模板必须来自真人，而真人得等用户在场地才能录。
        //
        // GET  /kws                 → 内核状态（模板数/模板帧长/参数）
        // POST /kws/clear           → 清空模板
        // POST /kws/train {"files":"/sdcard/a.pcm,/sdcard/b.pcm","delta":true}
        // POST /kws/test  {"pcm":"/sdcard/x.pcm","stride":5,"delta":true,"band":0.25}
        // POST /kws/probe {"pcm":"/sdcard/x.pcm"}
        //      ★ probe 是**交叉对差**用的：它把帧数 / 能量指纹 / MFCC 指纹 /
        //        VAD 段都吐出来，PC 侧用 Python 参考实现算同样的东西逐层比。
        //        只比最后那个 DTW 距离，可能两层错误互相抵消；分层比才能定位。
        if ("GET /kws".equals(act)) {
            int lo = Integer.MAX_VALUE, hi = 0;
            for (float[][] t : LocalWake.TPL) {
                lo = Math.min(lo, t.length);
                hi = Math.max(hi, t.length);
            }
            return "{\"templates\":" + LocalWake.TPL.size()
                    + ",\"tpl_frames_min\":" + (LocalWake.TPL.isEmpty() ? -1 : lo)
                    + ",\"tpl_frames_max\":" + hi
                    + ",\"frame\":" + LocalWake.FRAME + ",\"hop\":" + LocalWake.HOP
                    + ",\"nfft\":" + LocalWake.NFFT + ",\"nmel\":" + LocalWake.NMEL
                    + ",\"nceps\":" + LocalWake.NCEPS + ",\"sr\":" + LocalWake.SR + "}";
        }
        if ("POST /kws/clear".equals(act)) {
            LocalWake.clearTemplates();
            return "{\"ok\":true,\"templates\":0}";
        }
        if ("POST /kws/train".equals(act)) {
            String files = field(body, query, "files");
            boolean delta = !"false".equalsIgnoreCase(field(body, query, "delta"));
            if (files.isEmpty()) {
                // 不给就按约定取 KWS_DIR 里的 tpl_*.pcm（采集器/人工放的模板）
                StringBuilder sb = new StringBuilder();
                java.io.File d = new java.io.File(VoiceWakeController.KWS_DIR);
                java.io.File[] fs = d.listFiles();
                if (fs != null) {
                    java.util.Arrays.sort(fs);
                    for (java.io.File f : fs) {
                        if (f.getName().startsWith("tpl_") && f.getName().endsWith(".pcm")) {
                            if (sb.length() > 0) sb.append(',');
                            sb.append(f.getAbsolutePath());
                        }
                    }
                }
                files = sb.toString();
            }
            if (files.isEmpty()) return "{\"ok\":false,\"error\":\"没有可训练的模板文件"
                    + "（给 files= 或把 tpl_*.pcm 放进 " + VoiceWakeController.KWS_DIR + "）\"}";
            java.util.List<String> paths = new java.util.ArrayList<String>();
            for (String s : files.split(",")) if (!s.trim().isEmpty()) paths.add(s.trim());
            // ★ 默认**先清空再训练**。踩过：模板是累加的，连调两次 /kws/train
            //   会把同一批模板装两遍（状态里显示 count=4 而实际只有 2 个不同模板）。
            //   后果不是报错，而是 DTW 取 min 的那份"多出来"的模板掩盖了真实情况 ——
            //   评出来的分数看着更低、更像"识别成功"。要追加得显式给 append=true。
            if (!"true".equalsIgnoreCase(field(body, query, "append"))) {
                LocalWake.clearTemplates();
            }
            return LocalWake.trainFrom(paths, delta);
        }
        if ("POST /kws/test".equals(act)) {
            String pcm = field(body, query, "pcm");
            if (pcm.isEmpty()) return "{\"ok\":false,\"error\":\"pcm= 必填\"}";
            int stride = 5;
            try { stride = Integer.parseInt(field(body, query, "stride")); } catch (Throwable ignore) {}
            if (stride < 1) stride = 1;
            boolean delta = !"false".equalsIgnoreCase(field(body, query, "delta"));
            double band = 0.25;
            try { band = Double.parseDouble(field(body, query, "band")); } catch (Throwable ignore) {}
            boolean activeOnly = !"false".equalsIgnoreCase(field(body, query, "active"));
            return LocalWake.scoreFile(pcm, stride, delta, band, activeOnly);
        }
        if ("POST /kws/probe".equals(act)) {
            String pcm = field(body, query, "pcm");
            if (pcm.isEmpty()) return "{\"ok\":false,\"error\":\"pcm= 必填\"}";
            return LocalWake.probe(pcm);
        }
        // ---- v53：离线 ASR 探测（把"用户说了什么"变成文本，端到端通路缺失的那一环） ----
        // 用法：POST /asrtest {"sec":8}                     —— 阻塞 sec 秒，期间对着机器人说话
        //      POST /asrtest {"sec":8,"grammar":"assets:///baidu_speech_grammar.bsg"}
        //      POST /asrtest {"sec":8,"extra":"{\"vad\":\"disable\"}"}   覆盖任意 asr.start 参数
        // 免麦确定性测试（v76）：
        //      POST /asrtest {"sec":6,"infile":"/sdcard/ledi_test.pcm"}
        //  ★ infile 是设备上的**裸 PCM**（16k/16bit/单声道），不是 WAV。
        //    WAV 的 44 字节 RIFF 头会被当成第一个采样，且读不出尾部。
        //    键名/格式依据见 AsrProbe.INFILE_KEY（反汇编 EventManagerMic）。
        //    走文件输入时不会停唤醒引擎（根本不碰麦）。
        if ("POST /asrtest".equals(act)) {
            if (voiceWake == null) return "{\"error\":\"wake not ready\"}";
            int sec = 8;
            try { sec = Integer.parseInt(field(body, query, "sec")); } catch (Throwable ignore) {}
            if (sec > 20) sec = 20;     // HTTP 读超时 30s，留出收尾时间
            return voiceWake.asrTest(sec, field(body, query, "grammar"),
                    field(body, query, "extra"), field(body, query, "infile"));
        }
        if ("POST /iatest".equals(act)) {
            String infile = field(body, query, "infile");
            if (infile.isEmpty()) {
                return "{\"ok\":false,\"error\":\"缺少 infile（本轮只支持文件输入，避免麦克风变量混进来）\"}";
            }
            int pace = 40, wait = 15000;
            try { pace = Integer.parseInt(field(body, query, "pace")); } catch (Throwable ignore) {}
            try { wait = Integer.parseInt(field(body, query, "wait")); } catch (Throwable ignore) {}
            if (pace < 0) pace = 0;
            if (wait < 1000) wait = 1000;
            if (wait > 25000) wait = 25000;   // HTTP 读超时 30s，留出收尾
            return cloud().recognizeFile(infile, pace, wait, field(body, query, "extra"));
        }
        // ---- v63：触摸自测（机器人给自己发原厂触摸广播）----
        // 为什么要这个：真机上没法"点一下左脸"来验证链路，而 adb 的 am broadcast
        // 在这台 ROM 上**不会返回**（它在等永远不会来的结果接收者），脚本直接卡死。
        // 让 App 自己 sendBroadcast 一发，就能走完整条
        // 「广播 → SensorHub → 方位感知 → 转头」而完全不依赖 adb。
        //   {"action":"l"} / {"action":"r"} / {"action":"head"}  —— 简写
        //   或直接给完整 action 名
        //   {"action":"l","times":5,"gap_ms":120}                  —— **连发**模式
        //     为什么要连发：从电脑侧循环调用这个接口，"连击"根本连不起来 ——
        //     每次 HTTP 往返就要几百毫秒到 1 秒，5 次会摊开成 4 秒多，那已经不算
        //     "一次连摸"了，测不出命令叠加。连发让机器人在**本机**把广播快速打出去，
        //     才是真实的"一次触摸被原厂推成一串 down"的现场。
        if ("POST /touchtest".equals(act)) {
            String a = field(body, query, "action");
            if (a.length() == 0) return "{\"error\":\"action required: l|r|head|完整action\"}";
            String full;
            if ("l".equalsIgnoreCase(a)) full = SensorHub.FACE_L_DOWN;
            else if ("r".equalsIgnoreCase(a)) full = SensorHub.FACE_R_DOWN;
            else if ("head".equalsIgnoreCase(a)) full = SensorHub.FACE_HEAD_DOWN;
            else full = a;
            int times = 1;
            try { times = Integer.parseInt(field(body, query, "times")); } catch (Throwable ignore) {}
            if (times < 1) times = 1;
            if (times > 50) times = 50;
            long gap = 120;
            try { gap = Long.parseLong(field(body, query, "gap_ms")); } catch (Throwable ignore) {}
            if (gap < 0) gap = 0;
            if (gap > 2000) gap = 2000;
            // ★ 不能"睡固定时间再读"：广播是异步的，睡 1.2s 读到的是**上一次**
            //   事件的旧方位 —— 那就是一条假绿灯（接口看着有值，其实没反映刚发的那下）。
            //   改成盯着 burstTs 变（最多 3 秒），变了才读。
            long before = locator == null ? 0 : locator.ts();
            long fireT0 = System.currentTimeMillis();
            for (int i = 0; i < times; i++) {
                ctx.sendBroadcast(new android.content.Intent(full));
                if (gap > 0 && i < times - 1) {
                    try { Thread.sleep(gap); } catch (InterruptedException ignored) { break; }
                }
            }
            long t0 = System.currentTimeMillis();
            while (locator != null && locator.ts() == before
                    && System.currentTimeMillis() - t0 < 3000) {
                try { Thread.sleep(80); } catch (InterruptedException ignored) { break; }
            }
            boolean applied = locator == null || locator.ts() != before;
            return "{\"ok\":" + applied + ",\"sent\":\"" + J.esc(full) + "\""
                    + ",\"times\":" + times + ",\"gap_ms\":" + gap
                    + ",\"burst_ms\":" + (t0 - fireT0)
                    + ",\"waited_ms\":" + (System.currentTimeMillis() - t0)
                    + ",\"sensors\":" + (sensor == null ? "{}" : sensor.json())
                    + ",\"doa\":" + (locator == null ? "{}" : locator.stateJson()) + "}";
        }
        return null;   // TABLE 已保证本域不认这个 act（正常不会发生）
    }

    /** 传感器 / 触摸 / 声源 / 在线 / 存在。 */
    private String senseRoutes(String act, String query, String body) throws Exception {
        // ================= 在线状态 / 名字（v142） =================
        // GET  /presence                          → 在线状态 + 连续听不懂几次 + 名字
        // POST /presence {"offline":true|false}   → 手动进/出离线模式（验收用）
        // POST /presence {"miss_streak":0}        → 只把计数归零，不改离线状态
        // POST /name {"name":"小乐"}              → 改名（三端同写这一个键）
        // POST /name {"probe_sec":45}             → 离线时的名字探测周期（0=关，默认关）
        // POST /name {"miss_limit":4}             → 连错几次算离线（1..5，默认 3）
        //
        // ★ 三端同步是怎么成立的：手机端、电脑端、以及"对机器人说话"三条路，
        //   最终都写同一个 prefs 键（Presence.setName → "robot_name"）。
        //   所以不存在"三份名字要对表"这种事 —— **只有一份**，读的也是同一份。
        //   ⚠ 但必须如实告诉用户边界：**自定义名字改不了声学唤醒词**。
        //   证据：/data/data/com.ledi.agent/files/WakeUp.bin 是 32 条完全相同的
        //   40 字节记录（一个固定模板，不是可改的词表），离线 ASR 词表同理固化在
        //   libbd_easr_*.dat.so。名字真正生效的三处是：①显示与自称 ②识别文本里
        //   "点名叫它"的匹配 ③离线模式下按 probe_sec 打开的低频在线探测（默认关，
        //   因为它要花配额 —— 正是用户最在意的那个东西）。见 Presence 类注释的详述。
        if ("GET /presence".equals(act)) {
            return Presence.json(ctx);
        }
        if ("POST /presence".equals(act)) {
            StringBuilder sb = new StringBuilder("{\"before\":").append(Presence.json(ctx));
            String off = field(body, query, "offline");
            if ("true".equalsIgnoreCase(off) || "1".equals(off)) {
                sb.append(",\"result\":\"").append(J.esc(Presence.enterOffline(ctx, "远程命令")))
                        .append("\"");
            } else if ("false".equalsIgnoreCase(off) || "0".equals(off)) {
                sb.append(",\"result\":\"").append(J.esc(Presence.exitOffline(ctx, "远程命令")))
                        .append("\"");
            }
            String ms = field(body, query, "miss_streak");
            if (!ms.isEmpty()) {
                try {
                    sb.append(",\"miss\":\"")
                            .append(J.esc(Presence.setMissStreak(ctx, Integer.parseInt(ms.trim()))))
                            .append("\"");
                } catch (Throwable ignored) {}
            }
            // ★ v181：重试次数与离线门槛已解耦，两个旋钮各自可调
            String rm = field(body, query, "retry_max");
            if (!rm.isEmpty()) {
                try {
                    sb.append(",\"retry\":\"")
                            .append(J.esc(Presence.setRetryMax(ctx, Integer.parseInt(rm.trim()))))
                            .append("\"");
                } catch (Throwable ignored) {}
            }
            String am = field(body, query, "auto_min");
            if (!am.isEmpty()) {
                try {
                    sb.append(",\"auto\":\"")
                            .append(J.esc(Presence.setAutoRecoverMin(ctx, Integer.parseInt(am.trim()))))
                            .append("\"");
                } catch (Throwable ignored) {}
            }
            sb.append(",\"presence\":").append(Presence.json(ctx)).append("}");
            return sb.toString();
        }
        // ================= ★★★ v180/v186：活泼化（表情 / 动作） =================
        // 查：GET  /live
        // 配：POST /live {"on":true|false,"idle_every":3,"idle_motion_every":6}
        //   on                —— 活泼总开关（唤醒呼应 / 应答兜底表情 / 待机微动 / 待机动作）
        //   idle_every        —— 待机换表情每 N 拍（一拍 = 唤醒引擎的 8 秒自检心跳），1 = 最勤
        //   idle_motion_every —— 待机动作每 N 拍，**0 = 关**；默认 6（约 48s 一次）
        //
        // ★ 为什么把"活泼"做成可调而不是写死：它是**观感**类参数，真机上要人眼看。
        //   写死就意味着每调一次都得重打包装机 + 手工 am startservice ——
        //   这条在本机已经反复吃过亏（见 /listenled 的 mode 为什么也是可调的）。
        //
        // ★ v186：两个池子不再在这里写死字面量，改从 AgentService 的数组现算
        //   （唯一真源；否则改代码不改字符串 ⇒ 端点对外撒谎）。
        if ("GET /live".equals(act) || "POST /live".equals(act)) {
            if ("POST /live".equals(act)) {
                final String on = field(body, query, "on").trim();
                final String every = field(body, query, "idle_every").trim();
                final String mEvery = field(body, query, "idle_motion_every").trim();
                final String mDeg = field(body, query, "idle_deg").trim();
                android.content.SharedPreferences.Editor ed =
                        ctx.getSharedPreferences("ledi_agent", Context.MODE_PRIVATE).edit();
                if (on.length() > 0) {
                    AgentService.liveMode = "true".equalsIgnoreCase(on) || "1".equals(on);
                    ed.putBoolean("live_mode", AgentService.liveMode);
                }
                if (every.length() > 0) {
                    try {
                        int n = Integer.parseInt(every);
                        if (n < 1) n = 1;
                        if (n > 60) n = 60;
                        AgentService.liveIdleEvery = n;
                        ed.putInt("live_idle_every", n);
                    } catch (Throwable ignore) {}
                }
                if (mEvery.length() > 0) {
                    try {
                        int n = Integer.parseInt(mEvery);
                        if (n < 0) n = 0;              // 0 = 关掉待机动作
                        if (n > 240) n = 240;
                        AgentService.liveIdleMotionEvery = n;
                        ed.putInt("live_idle_motion_every", n);
                    } catch (Throwable ignore) {}
                }
                if (mDeg.length() > 0) {
                    try {
                        int n = Integer.parseInt(mDeg);
                        if (n < 0) n = 0;
                        if (n > 30) n = 30;            // 30° 以上就不叫"待机微动"了
                        AgentService.liveIdleDeg = n;
                        ed.putInt("live_idle_deg", n);
                    } catch (Throwable ignore) {}
                }
                ed.apply();
            }
            return "{\"ok\":true,\"on\":" + AgentService.liveMode
                    + ",\"idle_every\":" + AgentService.liveIdleEvery
                    + ",\"idle_motion_every\":" + AgentService.liveIdleMotionEvery
                    + ",\"idle_deg\":" + AgentService.liveIdleDeg
                    + ",\"idle_plays\":" + AgentService.livePlays
                    + ",\"idle_motion_plays\":" + AgentService.liveIdleMotionPlays
                    + ",\"idle_motion_last\":\"" + J.esc(AgentService.liveIdleMotionLast)
                    + "\"," + AgentService.idlePoolsJson()
                    + ",\"beats\":" + AgentService.liveBeats
                    + ",\"last_user_ago_ms\":" + AgentService.lastUserAgoMs()
                    + ",\"quiet_ms\":" + AgentService.IDLE_QUIET_MS
                    + ",\"blocked_by_talk\":" + AgentService.idleBlockedByTalk()
                    + ",\"wake_face\":\"wake\",\"wake_motion\":\"hiRighthand\""
                    + ",\"note\":\"待机微动挂唤醒引擎 8s 心跳，不新增线程\"}";
        }
        // ★ v148：调试控件（触摸 / 陀螺仪 / 电机滑条）已并入 log 面板，
        //   /pad 保留为"打开带控件的 log 面板"的入口，与 /logwin 同义 ——
        //   旧脚本还在用它，所以不能删；语义统一到 LogOverlay 这一个事实源。
        if ("GET /pad".equals(act)) return LogOverlay.statusJson();
        if ("POST /pad".equals(act)) {
            String on = field(body, query, "on");
            String x = field(body, query, "x");
            String y = field(body, query, "y");
            String lines = field(body, query, "lines");
            String size = field(body, query, "size");
            if (on.isEmpty() && x.isEmpty() && y.isEmpty()
                    && lines.isEmpty() && size.isEmpty()) {
                return LogOverlay.statusJson();          // 参数为空的兜底：回状态
            }
            return LogOverlay.set(on.isEmpty() ? null : on,
                    lines.isEmpty() ? null : lines,
                    size.isEmpty() ? null : size,
                    x.isEmpty() ? null : x,
                    y.isEmpty() ? null : y);
        }
        // ================= 头顶按键（v116）=================
        //   GET  /headkey                        -> 状态（含按下次数，用来证明按键真的到了）
        //   POST /headkey {"on":false}           -> 关掉这个功能
        //   POST /headkey {"main":"brain"}       -> 换"主程序"目标 factory|brain|off
        //   POST /headkey {"wake":false}         -> 按键时不唤醒语音
        //   POST /headkey {"sim":"down"}         -> 软件模拟一次按下（走**同一条**逻辑）
        //   POST /headkey {"sim":"long"}         -> 软件模拟一次长按（= 屏保开/关）
        //
        //   ★ 为什么要 sim：物理按键只有用户按得动。sim 验证的是"到了之后做对没有"，
        //     真按验证的是"广播到底送没送到"—— 两件事必须分开证，不能拿一个当另一个。
        //   ★ v127 起长短按是两个不同动作，sim 也要分别给：短按只走 press()，
        //     长按只走 onHold()，这样"长按阈值判错"和"长按动作写错"能分开定位。
        if ("GET /headkey".equals(act))
            return AgentService.headKey == null
                    ? "{\"error\":\"headkey not ready\"}" : AgentService.headKey.json();
        if ("POST /headkey".equals(act)) {
            HeadKey hk = AgentService.headKey;
            if (hk == null) return "{\"error\":\"headkey not ready\"}";
            String on = field(body, query, "on");
            if ("true".equals(on) || "false".equals(on)) hk.setEnabled("true".equals(on));
            String wk = field(body, query, "wake");
            if ("true".equals(wk) || "false".equals(wk)) hk.setWakeVoice("true".equals(wk));
            String mn = field(body, query, "main");
            if (mn != null && !mn.isEmpty()) hk.setMainTarget(mn);
            String sim = field(body, query, "sim");
            if ("down".equals(sim)) return hk.press("模拟头顶键");
            if ("long".equals(sim)) return hk.onHold();
            return hk.json();
        }
        // ================= 传感器 / 开关（新增） =================
        if ("GET /sensors".equals(act))
            return sensor == null ? "{\"error\":\"sensor not ready\"}" : sensor.json();
        if ("GET /sensorsummary".equals(act))
            return "{\"summary\":\"" + J.esc(sensor == null ? "" : sensor.summary()) + "\"}";
        if ("POST /sensorquery".equals(act)) {
            if (sensor != null) sensor.queryGsensor();
            return "{\"result\":\"queried\"}";
        }
        // ================= 表情 / 显示 =================
        if ("GET /faces".equals(act)) return jsonList(FaceController.listFaces());
        if ("GET /faces_detail".equals(act)) {
            // 每个表情是否带原厂音效，供控制台标注
            List<String> fs = FaceController.listFaces();
            StringBuilder sb = new StringBuilder("[");
            for (String f : fs) {
                sb.append("{\"name\":\"").append(J.esc(f)).append("\",\"sound\":")
                        .append(FaceController.hasSound(f)).append("},");
            }
            if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
            return sb.append("]").toString();
        }
        if ("POST /face".equals(act)) {
            String name = field(body, query, "name");
            boolean sound = "true".equals(field(body, query, "sound"));
            String r = face.play(name, sound);
            String norm = FaceController.normalize(name);
            return "{\"result\":\"" + J.esc(r) + "\",\"face\":\"" + J.esc(norm)
                    + "\",\"requested\":\"" + J.esc(name) + "\"}";
        }
        if ("POST /facesound".equals(act))
            return "{\"result\":\"" + J.esc(face.playSound(
                    FaceController.normalize(field(body, query, "name")))) + "\"}";
        if ("POST /faceoff".equals(act))
            return "{\"result\":\"" + J.esc(face.hide()) + "\"}";
        // POST /img {data:"base64(PNG/JPG)", max:800}  在机器人屏幕上显示一张图片
        // （图片由电脑端读文件、纠方向、缩放后编码送来；机器人只解码显示）
        if ("POST /img".equals(act)) {
            String b64 = field(body, query, "data");
            if (b64.isEmpty()) b64 = field(body, query, "b64");
            if (b64.isEmpty()) return "{\"error\":\"data(base64) required\"}";
            int mx = 800;
            String mss = field(body, query, "max");
            if (!mss.isEmpty()) {
                try { mx = Integer.parseInt(mss.trim()); } catch (Exception ignored) {}
            }
            String md = field(body, query, "mode");      // fit（完整）/ crop（铺满）
            if (!"crop".equalsIgnoreCase(md)) md = "fit";
            return "{\"result\":\"" + J.esc(display.showImage64(b64, mx, md)) + "\"}";
        }
        // ================= 电量（v202）=================
        // GET /battery → 完整电量对象（格数/电压/温度/充电状态/来源/新鲜度）
        //   ★ 与 GET /status 里的 battery_detail 是**同一个** Battery.info(ctx)，
        //     不另写一份解析 —— 这台机器上"两份读数各说各话"已经吃过亏。
        //   ★ 本机只有"格数"这一路是**原厂在推**（ACTION_BATTERY_CHANGED，SensorHub 已收），
        //     电压/温度/健康是 Android 标准字段、本 ROM 喂没喂要**当场看**才有结论；
        //     Battery.info 会照实报 present/age_ms，没喂到就是空，不假装。
        if ("GET /battery".equals(act)) return Battery.info(ctx);
        // ---- v99：麦克风实时听写（把"任意话"接到真麦克风上） ----
        //
        // 用法：
        //   GET  /online                 查"在线优先"开关
        //   POST /online {"on":true}     开/关（默认开；关掉就只用离线 45 条命令词）
        //   POST /ilisten {"sec":6}      开麦听一轮（阻塞），返回完整仪表 + text
        //        可选 silence=900（开口后静音多少 ms 收尾）
        //             loud=1200（人声 RMS 阈值；不传=前 240ms 环境噪声自动定）
        //
        // ★ 为什么要有 /ilisten：对话链路（唤醒 → 听 → AI → 播报）是**一条长链**，
        //   直接在那里试错，"哪一段坏的"永远说不清。这个端点把链路砍到只剩
        //   「让麦 → 开麦 → 灌帧 → 收敛」——配合写回码直方图/rms/开口时刻，
        //   现场一次性就能定位。与 /iatest（文件输入）配合可一刀切开
        //   "麦克风坏" 与 "云端拒绝/网络不通"。
        if ("GET /online".equals(act) || "POST /online".equals(act)) {
            if (voiceWake == null) return "{\"error\":\"wake not ready\"}";
            String on = field(body, query, "on");
            if (on.length() > 0) {
                boolean b = "true".equalsIgnoreCase(on) || "1".equals(on);
                String r = voiceWake.setOnlineAsr(b);
                BootLog.w("在线听写 开关被远程改成 " + b);
                // 开关本身也要能一眼看到现状（否则"改了没生效"只能靠猜）
                return "{\"ok\":true,\"set\":" + b + ",\"listen\":"
                        + voiceWake.listenStatusJson() + ",\"raw\":" + r + "}";
            }
            return "{\"ok\":true,\"listen\":" + voiceWake.listenStatusJson()
                    + ",\"cloud\":" + cloud().statusJson(voiceWake.isOnlineAsr()) + "}";
        }
        // ---- v60：双麦克风声源定位 ----
        // 查：GET  /doa
        // 探：POST /doa {"probe":true,"ms":1200}  —— 一次性可行性探针（约 6 秒），
        //     直接回答"这机器打开 STEREO 拿到的是真双路还是复制两份"
        // 开：POST /doa {"on":true}               —— 背一个常驻线程持续估计方位
        // 标：POST /doa {"calib":"left"}          —— 人站在【机器人的左边】说话时调，
        //     把 lag 的符号和满量程参考值记下来（不标定只能输出"偏A侧/偏B侧"）
        // 试：POST /doa {"test":"左"}             —— 只翻译方位名，不碰硬件
        if ("GET /doa".equals(act) || "POST /doa".equals(act)) {
            String probe = field(body, query, "probe");
            if ("true".equalsIgnoreCase(probe) || "1".equals(probe)) {
                String msS = field(body, query, "ms");
                int ms = 1200;
                try { if (msS.length() > 0) ms = Math.max(400, Math.min(4000, Integer.parseInt(msS))); }
                catch (Throwable ignore) {}
                // 探针自己开麦，先让常驻定位线程让出麦克风
                if (locator != null) locator.stop();
                String json = SoundSourceLocator.probe(ms);
                if (locator != null && locator.isEnabled()) locator.start();
                return json;
            }
            String rt = field(body, query, "ratio");
            if (rt.length() > 0) {
                // 只验分段阈值，不碰硬件：{"ratio":0.8} -> 右侧
                try {
                    double r = Double.parseDouble(rt);
                    return "{\"ratio\":" + r + ",\"sector\":\""
                            + J.esc(SoundSourceLocator.sectorOf(r)) + "\"}";
                } catch (Throwable e) {
                    return "{\"error\":\"ratio 要是数字\"}";
                }
            }
            if (locator == null) return "{\"error\":\"locator not ready\"}";
            // 兜底方位来源：模拟一次触摸（不用真的去摸机器人的脸就能验）
            //   {"touch":"lface"} / {"touch":"rface"} / {"touch":"head"}
            String touch = field(body, query, "touch");
            if (touch.length() > 0) {
                locator.noteTouch(touch);
                return locator.stateJson();
            }
            String calib = field(body, query, "calib");
            if (calib.length() > 0) return locator.calibrate(calib);
            String on = field(body, query, "on");
            if (on.length() > 0) {
                locator.setEnabled("true".equalsIgnoreCase(on) || "1".equals(on));
                return locator.stateJson();
            }
            String src = field(body, query, "src");
            if (src.length() > 0) {
                try { locator.setSource(Integer.parseInt(src)); } catch (Throwable ignore) {}
                return locator.stateJson();
            }
            String look = field(body, query, "look");
            if ("true".equalsIgnoreCase(look) || "1".equals(look)) {
                String m = locator.lookMotion();
                String applied = m.isEmpty() ? "（正前，不用转）" : motion.motion(m);
                return "{\"sector\":\"" + J.esc(locator.sector()) + "\",\"motion\":\""
                        + J.esc(m) + "\",\"applied\":\"" + J.esc(applied) + "\"}";
            }
            return locator.stateJson();
        }
        return null;   // TABLE 已保证本域不认这个 act（正常不会发生）
    }

    /** 语音输入输出 / 唤醒 / 对话 / 云。 */
    private String voiceRoutes(String act, String query, String body) throws Exception {
        // ================= 听语灯（v144 起；★ v167 改蓝灯） =================
        // GET  /listenled  → {"on":true,"active":false,"mode":"blue_on","led":"b.on","last":"..."}
        // POST /listenled {"mode":"blue_on|blue_blink|all_blink|off"}
        // POST /listenled {"on":true|false}   ← v144 旧写法，等价 mode=默认 / off
        //
        // ★ 为什么必须有这个端点：用户 09-24 的口径是「**取消主动推送，只靠 LED
        //   表示机器人在听**」—— 那"灯到底亮没亮、现在还亮不亮"就成了**唯一的
        //   行为判据**。判据不可观测，验收就只能靠人盯着机器人看、复现不了
        //   （假绿灯的典型土壤）。所以这里把四件事一起摊出来：
        //   应该亮吗(active) / 按哪个模式(mode) / 下发成什么(led) / 最近回执(last)。
        // ★ 灯本身是硬件、我们读不回来 ⇒ 这是软件侧能做到的极限：
        //   last 里的 `sdk:xxx` 表示反射到了原厂静态方法，否则说明走了裸广播。
        //   "到底亮没亮、是不是蓝的"仍只能人眼看，问的时候要照这个问。
        // ★ 用户 09-25（高优先级）「机器人正在收听的时候头顶亮蓝灯」⇒ 默认 blue_on。
        //   保留 all_blink 是**回退路**：万一蓝灯在这台 ROM 上反射/广播都没反应，
        //   一条 POST 就能切回 v144 的行为，不必重新打包重装。
        if ("GET /listenled".equals(act)) {
            final String md = ctx.getSharedPreferences("ledi_agent", Context.MODE_PRIVATE)
                    .getString("led_listen_mode", AgentService.LED_MODE_DEFAULT);
            return "{\"on\":" + !AgentService.LED_MODE_OFF.equals(md)
                    + ",\"active\":" + AgentService.ledListenOn
                    + ",\"mode\":\"" + J.esc(md) + "\""
                    + ",\"led\":\"" + J.esc(AgentService.ledSpec(md)) + "\""
                    + ",\"keeper\":" + AgentService.ledKeeperN
                    // ★★★ v178：把"说话期间不亮灯"的两个输入也自述出来，验收才有的看：
                    //   speak_off=该功能开没开；speaking=此刻在不在说话；mic_gate=麦闸开着没。
                    //   ★ 灯是硬件、读不回来 ⇒ 这三项是**软件侧能做到的极限**；
                    //     "真机上到底亮没亮"仍要人眼看，验收时照这个问。
                    + ",\"speak_off\":" + AgentService.ledSpeakOff
                    + ",\"speaking\":" + AgentService.ttsSpeakingNow
                    + ",\"mic_gate\":" + AgentService.micGateOpen
                    // ★★★ v206：思考灯的观测（与听语灯同源字段）。思维状态是
                    //   "说完话之后、回答之前"这段窗口，肉眼只看到灯变了颜色 ⇒
                    //   必须给它可读的计数，否则"到底进没进思考"无从对差。
                    + "," + AgentService.thinkLedJson()
                    + ",\"last\":\"" + J.esc(AgentService.listenLedLast) + "\"}";
        }
        if ("POST /listenled".equals(act)) {
            final String md = field(body, query, "mode").trim();
            final String on = field(body, query, "on").trim();
            final String spk = field(body, query, "speak_off").trim();
            // ★★★ v178：只改"说话灭蓝灯"这一项时，**不要顺手把灯效模式也改回默认**
            //  （旧写法在 mode/on 都缺省时会写 LED_MODE_DEFAULT —— 那对旧调用方
            //   是"空 body 视作开"的兼容行为，但会误伤只想调 speak_off 的新调用方）。
            if (spk.length() > 0 && md.isEmpty() && on.isEmpty()) {
                boolean b = "true".equalsIgnoreCase(spk) || "1".equals(spk);
                AgentService.ledSpeakOff = b;
                ctx.getSharedPreferences("ledi_agent", Context.MODE_PRIVATE)
                        .edit().putBoolean("led_speak_off", b).apply();
                // 改成立即生效：正说着话就立刻灭；说完/不开该功能时按麦闸恢复。
                AgentService.noteTtsSpeaking(AgentService.ttsSpeakingNow);
                final String cur = ctx.getSharedPreferences("ledi_agent", Context.MODE_PRIVATE)
                        .getString("led_listen_mode", AgentService.LED_MODE_DEFAULT);
                return "{\"ok\":true,\"speak_off\":" + AgentService.ledSpeakOff
                        + ",\"mode\":\"" + J.esc(cur) + "\""
                        + ",\"active\":" + AgentService.ledListenOn
                        + ",\"speaking\":" + AgentService.ttsSpeakingNow + "}";
            }
            String target;
            if (!md.isEmpty()) {
                if (!AgentService.isLedMode(md)) {
                    return "{\"error\":\"bad mode: " + J.esc(md)
                            + "（可选 blue_on|blue_blink|all_blink|off）\"}";
                }
                target = md;
            } else if (!on.isEmpty()) {
                boolean b = "true".equalsIgnoreCase(on) || "1".equals(on);
                target = b ? AgentService.LED_MODE_DEFAULT : AgentService.LED_MODE_OFF;
            } else {
                target = AgentService.LED_MODE_DEFAULT;   // 兼容 v144：空 body 视作"开"
            }
            ctx.getSharedPreferences("ledi_agent", Context.MODE_PRIVATE)
                    .edit().putString("led_listen_mode", target).apply();
            // ★ 换档要**立即**生效：灯正亮着就按新模式强制重发。
            //   不 force 的话，用户会以为"改了没反应"，其实是他得等下一轮开麦
            //   （可能是几小时后）才看到 —— 这正是"判据只慢一轮"的坑。
            if (AgentService.ledListenOn) {
                AgentService.listenLed(true, true);
            } else if (AgentService.LED_MODE_OFF.equals(target) && sdk != null) {
                try { sdk.led("all", "off"); } catch (Throwable ignored) {}
            }
            return "{\"ok\":true,\"on\":" + !AgentService.LED_MODE_OFF.equals(target)
                    + ",\"mode\":\"" + J.esc(target) + "\""
                    + ",\"led\":\"" + J.esc(AgentService.ledSpec(target)) + "\""
                    + ",\"active\":" + AgentService.ledListenOn + "}";
        }
        // ================= ★★★ v182：听语表情（"两个麦克风"） =================
        // GET  /listenface → {"on":true,"face":"recording","active":false,"shown_now":"","n":N,"last":"..."}
        // POST /listenface {"name":"recording"}   → 换表情（"off" = 不播脸）
        //
        // ★ 与 /listenled 完全同构，理由也一样：表情也是**硬件侧**的东西（全屏视频浮层），
        //   我们读不回"用户到底看见了什么"。所以把能自证的都摊出来：
        //   该播什么(face) / 此刻该不该播(active) / **现在脸上是谁**(shown_now) /
        //   下发了多少次(n) / 最近回执(last)。"真机上到底出没出那两个麦克风"仍要人眼看。
        // ★ 为什么 n 必须单列：万一挂点漏了（例如只挂麦闸、漏了 barge-in 那条路），
        //   外观上跟"这功能没做出来"一模一样。长会话里 n 必须随开麦次数往上涨。
        if ("GET /listenface".equals(act)) {
            final String nm = ctx.getSharedPreferences("ledi_agent", Context.MODE_PRIVATE)
                    .getString("listen_face", AgentService.LISTEN_FACE_DEFAULT);
            return "{\"on\":" + !"off".equalsIgnoreCase(nm)
                    + ",\"face\":\"" + J.esc(nm) + "\""
                    + ",\"norm\":\"" + J.esc(FaceController.normalize(nm)) + "\""
                    + ",\"active\":" + AgentService.faceListenOn
                    + ",\"shown_now\":\"" + J.esc(AgentService.face == null ? ""
                            : String.valueOf(AgentService.face.getCurrent())) + "\""
                    + ",\"n\":" + AgentService.faceListenN
                    + ",\"speaking\":" + AgentService.ttsSpeakingNow
                    + ",\"mic_gate\":" + AgentService.micGateOpen
                    + ",\"last\":\"" + J.esc(AgentService.listenFaceLast) + "\"}";
        }
        if ("POST /listenface".equals(act)) {
            final String nm = field(body, query, "name").trim();
            if (nm.length() == 0) {
                return "{\"error\":\"need name=表情名（off = 不播脸；GET /faces 取清单）\"}";
            }
            if (!"off".equalsIgnoreCase(nm) && FaceController.normalize(nm).isEmpty()) {
                return "{\"error\":\"face not found: " + J.esc(nm) + "（GET /faces 取清单）\"}";
            }
            ctx.getSharedPreferences("ledi_agent", Context.MODE_PRIVATE)
                    .edit().putString("listen_face", nm).apply();
            // ★ 换档要**立即**生效（与 /listenled 同理）：此刻正在听就按新表情重播一次。
            //   不立刻生效的话用户会以为"改了没反应"，其实得等下一轮开麦（可能几小时后）。
            if (AgentService.micGateOpen && !AgentService.ttsSpeakingNow
                    && !"off".equalsIgnoreCase(nm)) {
                AgentService.listenFace(true, true);
            }
            return "{\"ok\":true,\"face\":\"" + J.esc(nm) + "\""
                    + ",\"norm\":\"" + J.esc(FaceController.normalize(nm)) + "\""
                    + ",\"active\":" + AgentService.faceListenOn + "}";
        }

        // ================= 音量（v198；屏幕亮度已于 v201 移除）=================
        // GET  /volume                读当前音量与上限
        // POST /volume {"v":8}        设绝对值（也认老名字 {"n":8}）
        // POST /volume {"delta":2}    相对调整（语音"声音大一点"就是这条）
        //
        // ★ 语音本地直通（IntentResolver）走的是**同一个** Av 出口，
        //   所以"网页调的和语音调的"必然一致，不存在两套实现分叉。
        // ★★ v201：原来这里还有 `GET/POST /bright`，已按用户要求**整体移除** ——
        //   本机背光物理不可调，那套实现只是把**画面**叠一层黑压暗（能变暗但**不省电**），
        //   而且很容易把屏幕留在暗屏上（实测就被留在了 level=10）。
        //   随之，语法里那 6 句"亮一点/暗一点…"不再本地直通（回落到 AI，与 v198 之前一致）。
        if ("GET /volume".equals(act)) return Av.json();
        if ("POST /volume".equals(act)) {
            String d = field(body, query, "delta");
            if (!d.isEmpty()) return Av.adjust(intOr(d, 0));
            // ★ 参数名同时认 v（新）与 n（网页 ⑨ 那条滑条最早用的就是 n）。
            //   老名字必须留着：删了它页面上的滑条会**静默**变成空操作
            //   （原来这里 catch 掉解析失败、悄悄按 8 处理，也是同一个坑的另一种形态）。
            String v = field(body, query, "v");
            if (v.isEmpty()) v = field(body, query, "n");
            if (!v.isEmpty()) {
                int n = intOr(v, -1);
                if (n < 0) return "{\"ok\":false,\"error\":\"v/n 需要 0..max\"}";
                return Av.set(n);
            }
            return Av.json();
        }
        // ================= 长期主动听（v202）=================
        // ★ 这是 /dialog {"continuous":true} 的"可持久化"版本：v75 起常识别本来就有，
        //   但开关只活在内存里（contRunning 是进程内 static），**重启就丢**。
        //   这里把"要不要开机自动常听"落进 prefs，onCreate 里读它决定要不要拉起。
        //
        // GET  /listen → {"on":进程内是否在听,"autostart":开机是否自动开,"think":是否多轮思考,
        //                 "grammar":"cmd|free|...","engine":...}
        // POST /listen {"on":true|false}        现在开/关（等价 POST /dialog {"continuous":...}）
        // POST /listen {"autostart":true|false} 开机是否自动常听（写 prefs）
        // POST /listen {"grammar":"cmd|free"}   常听时用哪份语法（写 prefs）
        // POST /listen {"think":true|false}     语音回复是否走多轮思考（写 prefs）
        //   ★ 四个字段可**任意组合**一次下发；都不给就只回读（等价 GET）。
        if ("GET /listen".equals(act) || "POST /listen".equals(act)) {
            String on = field(body, query, "on");
            String auto = field(body, query, "autostart");
            String gr = field(body, query, "grammar");
            String th = field(body, query, "think");
            String wf = field(body, query, "workflow");
            if (auto.length() > 0)
                AgentService.persistListenAuto("true".equalsIgnoreCase(auto) || "1".equals(auto));
            if (gr.length() > 0) AgentService.persistGrammar(gr.trim());
            if (th.length() > 0)
                AgentService.persistThinkOn("true".equalsIgnoreCase(th) || "1".equals(th));
            if (wf.length() > 0)
                AgentService.persistWfOn("true".equalsIgnoreCase(wf) || "1".equals(wf));
            if (on.length() > 0) {
                boolean contOn = "true".equalsIgnoreCase(on) || "1".equals(on);
                // ★★ v203：屏保总闸对 POST /listen 开了口子（见 handle 开头的注释）——
                //    因为只改 prefs 的那几个字段是安全的。但 **{"on":true} 会真的开麦**，
                //    开麦就能被说话声/播报撕屏保 ⇒ 这一种情况必须在这里显式挡住，
                //    否则"放行 POST /listen"就成了一个悄悄撕屏保的后门。
                if (contOn && SleepMode.sleeping) {
                    return "{\"ok\":false,\"error\":\"sleeping\","
                            + "\"hint\":\"屏保中不能开麦（会撕开屏保）。先 POST /sleep {\\\"on\\\":false} 叫醒；"
                            + "改 workflow/think/autostart/grammar 这几个开关不受影响。\"}";
                }
                AgentService s = AgentService.I;
                if (s == null) return "{\"ok\":false,\"error\":\"service not ready\"}";
                // ★ 复用 /dialog 的那对入口（startContinuous/stopContinuous），
                //   不另写一套 —— 常听只有一路麦，两套实现必然互相抢。
                if (contOn) s.startContinuous(); else s.stopContinuous();
            }
            return "{\"ok\":true,\"status\":" + AgentService.listenStatusJson() + "}";
        }
        // ================= 说话 / 机器人自带语音合成 =================
        if ("GET /ttsstatus".equals(act)) return tts.status();
        if ("POST /tts".equals(act) || "POST /say".equals(act)) {
            String text = field(body, query, "text");
            String url = field(body, query, "url");
            String eng = field(body, query, "engine");
            if (eng == null || eng.isEmpty()) eng = "auto";
            return say(text, url, eng);
        }
        // ================= 麦克风 =================
        if ("POST /mic".equals(act)) {
            String on = field(body, query, "on");
            int seconds = 10;
            try { seconds = Integer.parseInt(field(body, query, "seconds")); }
            catch (Exception ignored) {}
            return "{\"result\":\"" + J.esc(
                    "true".equals(on) ? mic.start(seconds) : mic.stop()) + "\"}";
        }
        // ================= 长期对话日志 / 多轮记忆（v93） =================
        // GET  /talk?n=50       机器人本地那份对话流水（每条带 tid，可与电脑端对照）
        // GET  /talk/status     记忆有多长、落盘在哪、上一次记了什么
        // POST /talk {"action":"clear"}   只清内存环，磁盘 jsonl 留着翻账
        // 为什么要在机器人本地也留一份：走直连时电脑端只有机器人"主动推"的那一份，
        // 没连电脑/没网时就一寸证据都没有；而且大模型的"记忆"就是从这里回填的。
        if ("GET /talk/status".equals(act)) return TalkLog.statusJson();
        if ("GET /talk".equals(act)) {
            int n = 50;
            try { n = Integer.parseInt(field(body, query, "n")); } catch (Exception ignored) {}
            return TalkLog.tailJson(n);
        }
        if ("POST /talk".equals(act)) {
            if ("clear".equals(field(body, query, "action"))) return TalkLog.clear();
            return TalkLog.statusJson();
        }
        // POST /intentrun {"text":"跳个舞"}
        //   与 /intent 的区别：/intent **只判定**，这个**真执行**（和真人说这句话时
        //   走的是同一条路：本地直通 → applyAiTags 执行标签 → TTS 播报 → 落对话日志）。
        //   为什么需要它：本地直通的价值恰恰在"动作有没有真的发生"，只回一个
        //   local_reply 是假绿灯。有了它，整条链路可以坐在电脑前验收，不必守在机器人旁边喊。
        //   ★ 会真的让机器人动起来 / 出声 / 改音量，调试用，别放进任何自动流程。
        if ("POST /intentrun".equals(act)) {
            String text = field(body, query, "text");
            if (text.isEmpty()) return "{\"error\":\"text required\"}";
            IntentResolver.Act a = IntentResolver.local(text);
            if (a == null)
                return "{\"route\":\"ai\",\"note\":\"本地直通未命中；真人说这句时会带易混候选去问 AI\","
                        + "\"near\":" + jsonList(IntentResolver.near(text, 5)) + "}";
            IntentResolver.runAct(a);                   // ← 真的执行（/intent 的 probe 不会走到这）
            String spoken = applyAiTags(a.reply);       // 真的执行 [表情:]/[动作:] 标签
            String used = tts == null ? "" : tts.speak(spoken, false);
            IntentResolver.remember(text, "local", a.note, null);
            if (ai != null) ai.pushDialog("voice", text, a.reply, spoken, null, null, null,
                    a.note, 0, "local");
            return "{\"route\":\"local\",\"note\":\"" + J.esc(a.note)
                    + "\",\"reply\":\"" + J.esc(a.reply)
                    + "\",\"spoken\":\"" + J.esc(spoken)
                    + "\",\"tts\":\"" + J.esc(used) + "\"}";
        }
        // ================= newcore：AI 大脑（直连/回落，可切换） =================
        if ("GET /ai".equals(act) || "GET /ai/status".equals(act)) {
            return ai == null ? "{\"error\":\"ai not ready\"}" : ai.statusJson();
        }
        if ("POST /ai/config".equals(act)) {
            if (ai == null) return "{\"error\":\"ai not ready\"}";
            String eng = field(body, query, "engine");
            String base = field(body, query, "base");
            String key = field(body, query, "key");
            String model = field(body, query, "model");
            if (!base.isEmpty() || !key.isEmpty() || !model.isEmpty()) {
                ai.setDirect(base, key, model);
            }
            if (!eng.isEmpty()) ai.setEngine(eng);
            return ai.statusJson();
        }
        if ("POST /ai/ask".equals(act)) {
            if (ai == null) return "{\"error\":\"ai not ready\"}";
            String text = field(body, query, "text");
            String ctxStr = field(body, query, "context");
            String reply = ai.ask(text, ctxStr);
            if (reply.isEmpty()) {
                return "{\"ok\":false,\"error\":\"" + J.esc(ai.lastError()) + "\"}";
            }
            // 解析 AI 回复里的 [表情:x] [动作:x] [灯光:x] 标签并执行，
            // 剥离标签后的纯文本交给 TTS 播报（别把"[表情:开心]"念出来）
            String spoken = applyAiTags(reply);
            // 主动说话闭环：让机器人真正「张嘴」播报（默认开启，speak=false 可关）
            String sayStat = "";
            boolean doSpeak = !"false".equalsIgnoreCase(field(body, query, "speak"));
            if (doSpeak && spoken != null && !spoken.trim().isEmpty()) {
                sayStat = tts.speak(spoken, true);
            }
            // 对话日志：这条是"外部（控制台/蓝牙/手机）点名让机器人答一句"，
            // 同样要进流水，否则电脑端只知道自己发了请求、不知道它答了什么
            ai.pushDialog("ask", text, spoken, null, null, null);
            return "{\"ok\":true,\"reply\":\"" + J.esc(reply)
                    + "\",\"spoken\":\"" + J.esc(spoken)
                    + "\",\"say\":\"" + J.esc(sayStat) + "\"}";
        }
        // ================= newcore：主动说话（事件+定时） =================
        if ("GET /ptalk".equals(act)) {
            return ptalk == null ? "{\"error\":\"ptalk not ready\"}" : ptalk.json();
        }
        // ================= 语音唤醒（百度离线唤醒词） =================
        if ("GET /wakestatus".equals(act)) {
            return voiceWake == null ? "{\"error\":\"wake not ready\"}" : voiceWake.status();
        }
        if ("POST /wake".equals(act)) {
            if (voiceWake == null) return "{\"error\":\"wake not ready\"}";
            String on = field(body, query, "on");
            if ("true".equalsIgnoreCase(on)) return "{\"result\":\"" + J.esc(voiceWake.start()) + "\"}";
            if ("false".equalsIgnoreCase(on)) return "{\"result\":\"" + J.esc(voiceWake.stop()) + "\"}";
            return voiceWake.status();      // 无参=查状态
        }
        // ---- v102：远程强制唤醒（远程验收专用） ----
        //
        // 用法：POST /forcewake {"why":"http"}
        //
        // ★ 为什么需要它：完整对话链路（唤醒 → 听 → AI → 播报）的验收卡在
        //   "必须有人先喊一句唤醒词"上 —— 而唤醒词是**离线 KWS**，对房间混响敏感
        //   （见 §二 的 KWS 脆弱度画像），喊十次不一定中一次。这样每次验收都变成
        //   "先跟唤醒较劲"，真正要测的那一环反而测不到。
        //   强制唤醒走的是**同一条** `dispatchWake` 链路（与"摸头顶"完全一致），
        //   只是跳过了 KWS 那一步，把"唤醒"这个变量从验收里摘出去。
        //   与 /intentrun 同类：都是"真执行"的远程验收入口，不是只判定。
        if ("POST /forcewake".equals(act)) {
            if (voiceWake == null) return "{\"error\":\"wake not ready\"}";
            if (!VoiceDialog.enabled) {
                return "{\"ok\":false,\"error\":\"对话通路是关的（/dialog {\\\"on\\\":true}），"
                        + "强制唤醒也听不了\"}";
            }
            final String why = field(body, query, "why");
            voiceWake.forceWake(why.isEmpty() ? "http" : why);
            // 唤醒是异步的（dispatchWake 会另起线程跑应答链路），所以这里只报"已触发"，
            // 结果要去 /status 的 listen/wake 与设备日志里看 —— 不在这里假装同步。
            return "{\"ok\":true,\"triggered\":true,\"listen\":"
                    + voiceWake.listenStatusJson() + "}";
        }
        // ---- v178：远程验收整轮应答（连"听"也跳过） ----
        //
        // 用法：POST /dialogturn {"text":"你好呀","why":"http"}
        //
        // ★ 为什么还要在 /forcewake 之外再加一个：/forcewake 跳过的是**唤醒词**，
        //   但"听一句话"本身仍是麦克风 + 在线/离线识别，房间里没人说话照样测不到
        //   后面那一环。本轮（v178）要验的是「**播完自动续听**」，它在播报之后 ——
        //   注入首句就能让链路一路跑到那里，且不需要真人在场。
        //   与 /intentrun、/forcewake 同类：真执行的远程验收入口，不是只判定。
        if ("POST /dialogturn".equals(act)) {
            if (voiceWake == null) return "{\"error\":\"wake not ready\"}";
            if (!VoiceDialog.enabled) {
                return "{\"ok\":false,\"error\":\"对话通路是关的（POST /dialog {\\\"on\\\":true}）\"}";
            }
            final String txt = field(body, query, "text");
            if (txt.isEmpty()) return "{\"ok\":false,\"error\":\"need text=注入的话\"}";
            AgentService.injectFirstSay = txt;
            final String why = field(body, query, "why");
            voiceWake.forceWake(why.isEmpty() ? "dialogturn" : why);
            return "{\"ok\":true,\"triggered\":true,\"injected\":\"" + J.esc(txt)
                    + "\",\"followup\":" + AgentService.autoFollowup
                    + ",\"turns\":" + VoiceDialog.maxTurns
                    + ",\"note\":\"异步：结果去 GET /talk 与设备日志里看，别在这里等\"}";
        }
        // ---- v53：唤醒词表热替换（换词不必重装 APK） ----
        // 查：GET  /vocab
        // 换：POST /vocab {"preset":"full"}        三档：base / safe / full
        //     POST /vocab {"words":"你好,乐迪,超级飞侠"}   自定义
        // 复位：POST /vocab {"reset":true}          丢掉外部词表，回落到 APK 内嵌那份
        if ("GET /vocab".equals(act)) {
            return voiceWake == null ? "{\"error\":\"wake not ready\"}" : voiceWake.vocabularyInfo();
        }
        if ("POST /vocab".equals(act)) {
            if (voiceWake == null) return "{\"error\":\"wake not ready\"}";
            String reset = field(body, query, "reset");
            if ("true".equalsIgnoreCase(reset) || "1".equals(reset)) return voiceWake.resetVocabulary();
            return voiceWake.setVocabulary(field(body, query, "preset"), field(body, query, "words"));
        }
        // ================= 在线听写（v97，讯飞 MSC）=================
        //
        // 离线那套是"语法受限解码器 + 词典固化在声学模型里"，只能听懂词表内的整句
        // 「听懂任意一句话」只剩云端。本机不需要用户掏 key：原厂 App 的讯飞 appid
        // 和 libmsc.so 已经因为 --merge-dex 全在我们的包里了，见 CloudAsr 类注释。
        //
        // 用法：
        //   GET  /ia                      只建初始化，验授权（不消耗识别额度）
        //   POST /iatest {"infile":"/sdcard/x.wav"}
        //        可选 pace=40（每 1280 字节间隔 ms，40≈实时）、wait=15000（等结果上限）
        //        extra="{\"asr_ptt\":\"0\"}"（任意讯飞 SpeechConstant 键覆盖）
        //   ★ 不传 infile 时**不会**自动开麦 —— 先只做文件输入，把"麦克风/环境"
        //     这一整类变量锁死；通路确认后再接麦克风。
        if ("GET /ia".equals(act) || "POST /ia".equals(act)) return cloud().initOnly();
        if ("POST /ilisten".equals(act)) {
            if (voiceWake == null) return "{\"error\":\"wake not ready\"}";
            int sec = 6;
            try { sec = Integer.parseInt(field(body, query, "sec")); } catch (Throwable ignore) {}
            if (sec < 2) sec = 2;
            if (sec > 12) sec = 12;                 // HTTP 读超时 30s，留出收尾与网络
            int silence = 0, loud = 0;
            try { silence = Integer.parseInt(field(body, query, "silence")); } catch (Throwable ignore) {}
            try { loud = Integer.parseInt(field(body, query, "loud")); } catch (Throwable ignore) {}
            // dump="/sdcard/x.pcm" → 把喂进引擎的同一份裸 PCM 落盘（ground-truth 仪器）。
            // 拿这个文件去 POST /iatest {"infile":"/sdcard/x.pcm"} 就能一刀切开
            // 「采集坏」与「流式/时序坏」——两者在 text 空 + err=10118 上长得一模一样。
            String dump = field(body, query, "dump");
            String src = field(body, query, "src");
            int audioSource = 1;                 // MIC
            if (!src.isEmpty()) {
                try { audioSource = Integer.parseInt(src); } catch (Throwable ignore) {}
            }
            BootLog.w("在线听写 ★/ilisten 开麦 " + sec + "s（请开始说话） src=" + audioSource
                    + (dump.isEmpty() ? "" : " dump=" + dump));
            CloudAsr.MicResult mr = voiceWake.cloudListen(sec * 1000, silence, loud,
                    dump, audioSource);
            return mr.json;
        }
        // ---- v53：唤醒后是否「先听再说」 ----
        // 开：POST /asrdialog {"on":true,"sec":5}   关：POST /asrdialog {"on":false}
        // 默认关。等 /asrtest 确认能拿到识别文本后再打开。
        if ("POST /asrdialog".equals(act) || "GET /asrdialog".equals(act)) {
            String on = field(body, query, "on");
            String sec = field(body, query, "sec");
            if (sec.length() > 0) {
                try { VoiceWakeController.asrDialogSec = Math.max(1, Math.min(20, Integer.parseInt(sec))); }
                catch (Throwable ignore) {}
            }
            if (on.length() > 0) VoiceWakeController.asrDialog = "true".equalsIgnoreCase(on) || "1".equals(on);
            return "{\"asr_dialog\":" + VoiceWakeController.asrDialog
                    + ",\"sec\":" + VoiceWakeController.asrDialogSec + "}";
        }
        // ---- v58：对话通路（唤醒后听一整句，说完静音 1 秒交给 AI；播报期间可被语音打断） ----
        // 查：GET  /dialog
        // 配：POST /dialog {"on":true,"max_ms":10000,"silence_ms":1000,"turns":3}
        // 试：POST /dialog {"listen":true}   —— 阻塞听一轮（最多 max_ms），返回原文与纠错后的文本
        //     POST /dialog {"fix":"观灯"}    —— 单独验证错字纠正表
        if ("GET /dialog".equals(act) || "POST /dialog".equals(act)) {
            if (voiceWake == null) return "{\"error\":\"wake not ready\"}";
            String on = field(body, query, "on");
            String mx = field(body, query, "max_ms");
            String si = field(body, query, "silence_ms");
            String tn = field(body, query, "turns");
            // ★★★ v178：播完自动续听（用户 2026-10-06「说完话后自动拉起对话」）。
            //   POST /dialog {"followup":true|false} —— 热切换，不必重打包装机。
            String fol = field(body, query, "followup");
            if (fol.length() > 0) {
                boolean b = "true".equalsIgnoreCase(fol) || "1".equals(fol);
                AgentService.autoFollowup = b;
                ctx.getSharedPreferences("ledi_agent", Context.MODE_PRIVATE)
                        .edit().putBoolean("auto_followup", b).apply();
                return "{\"ok\":true,\"followup\":" + AgentService.autoFollowup
                        + ",\"turns\":" + VoiceDialog.maxTurns
                        + ",\"max_ms\":" + VoiceDialog.maxListenMs
                        + ",\"silence_ms\":" + VoiceDialog.silenceMs
                        + ",\"enabled\":" + VoiceDialog.enabled + "}";
            }
            if (on.length() > 0) VoiceDialog.enabled = "true".equalsIgnoreCase(on) || "1".equals(on);
            // vad / decoder：SDK 对 vad 只认 model_vad|model-vad|mfe|search|input|null|空，
            // 给错会以 error=7 "bad vad: x" 直接判失败。做成可配是为了避免"每试一个
            // 候选值就重打一次 APK"（这台机器上就是这么绕过来的）。
            String vd = field(body, query, "vad");
            if (vd.length() > 0) VoiceDialog.vadMode = vd;
            String dc = field(body, query, "decoder");
            if (dc.length() > 0) {
                try { VoiceDialog.decoderMode = Integer.parseInt(dc); } catch (Throwable ignore) {}
            }
            // grammar：识别用哪份语法。默认是 APK 里的**命令词表**（53 条精确说法），
            // 所以"随便说一句话"本来就解不出文本 —— 真机上已证实：VAD 检出了 2.28 秒
            // 语音段、音量峰值 17654（静音基准 1077），但 error=7 一个候选都没匹配上。
            //   {"grammar":"cmd"}   命令词表（默认，离线更准）
            //   {"grammar":"free"}  不给语法，走自由识别（要跟 AI 聊天就得用这个）
            String gr = field(body, query, "grammar");
            if (gr.length() > 0) {
                String g = gr.trim();
                if ("cmd".equalsIgnoreCase(g)) VoiceDialog.grammarOverride = null;
                else if ("free".equalsIgnoreCase(g) || "none".equalsIgnoreCase(g)
                        || "off".equalsIgnoreCase(g)) VoiceDialog.grammarOverride = "";
                else VoiceDialog.grammarOverride = g;      // 其它按 URI 原样用
                // ★ 换了语法就换了"机器人能听懂什么"，意图解析层的词表镜像必须跟着重读，
                //   否则易混候选/本地直通还按旧词表判（静默错位，最难查）。
                IntentResolver.reload();
                return "{\"grammar_used\":\"" + J.esc(VoiceDialog.grammarOverride == null ? "cmd"
                        : (VoiceDialog.grammarOverride.isEmpty() ? "free(不给语法)"
                           : VoiceDialog.grammarOverride))
                        + "\",\"intent_vocab\":" + IntentResolver.vocab().length + "}";
            }
            // loud：spoken 音量阈值。实测这台机器的 asr.volume 量纲不是 0~100
            //（安静房间就有两千多），所以必须能现场改，靠 state.vol_max 标定。
            String ld = field(body, query, "loud");
            if (ld.length() > 0) {
                try { VoiceDialog.loudLevel = Integer.parseInt(ld); } catch (Throwable ignore) {}
            }
            if (mx.length() > 0) {
                try { VoiceDialog.maxListenMs = Math.max(2000, Math.min(30000, Integer.parseInt(mx))); }
                catch (Throwable ignore) {}
            }
            if (si.length() > 0) {
                try { VoiceDialog.silenceMs = Math.max(300, Math.min(5000, Integer.parseInt(si))); }
                catch (Throwable ignore) {}
            }
            if (tn.length() > 0) {
                try { VoiceDialog.maxTurns = Math.max(1, Math.min(8, Integer.parseInt(tn))); }
                catch (Throwable ignore) {}
            }
            String fix = field(body, query, "fix");
            if (fix.length() > 0) {
                return "{\"raw\":\"" + J.esc(fix) + "\",\"corrected\":\""
                        + J.esc(VoiceDialog.correct(fix)) + "\"}";
            }
            // 探：POST /dialog {"refl":true}  —— 把"真正拿到的引擎对象 + 它身上
            //     send/registerListener 的全部重载"打印出来。getDeclaredMethods()
            //     顺序不保证，挑错重载时 SDK 报的是类型错、不像"方法没了"，很容易
            //     被误当成授权/线程问题，所以宁可把事实摆出来。
            String refl = field(body, query, "refl");
            if ("true".equalsIgnoreCase(refl) || "1".equals(refl)) {
                return voiceWake.dialog().reflectDump();
            }
            String listen = field(body, query, "listen");
            if ("true".equalsIgnoreCase(listen) || "1".equals(listen)) {
                long t0 = System.currentTimeMillis();
                String raw = voiceWake.dialog().listenBlocking(VoiceDialog.maxListenMs + 1500L);
                return "{\"ok\":true,\"text\":\"" + J.esc(raw) + "\",\"ms\":"
                        + (System.currentTimeMillis() - t0) + ",\"state\":"
                        + voiceWake.dialog().stateJson() + "}";
            }
            // ---- v75：常识别（不等唤醒词，一直听；机器人说话期间暂停识别） ----
            // 开：POST /dialog {"continuous":true}    关：POST /dialog {"continuous":false}
            // 说清三件事，免得下次又当成 bug 排查：
            //   ① 常识别与唤醒词**互斥**：只有一路麦克风，开它就会停 wp
            //      （看门狗同时闭嘴，否则它会把"故意停掉"当成"死了"去救，反过来抢麦）；
            //   ② 说话期间不识别是**设计**，不是丢帧（信号源 TtsController.isSpeaking()）；
            //   ③ 识别用的还是 assets 里的**命令词语法**（53 条固定说法），
            //      所以自由说话目前大概率解不出文本 —— 那是语法问题，不是麦克风/链路问题。
            String cont = field(body, query, "continuous");
            if (cont.length() > 0) {
                boolean contOn = "true".equalsIgnoreCase(cont) || "1".equals(cont);
                AgentService s = AgentService.I;
                if (s == null) return "{\"error\":\"service not ready\"}";
                return contOn ? s.startContinuous() : s.stopContinuous();
            }
            return voiceWake.dialog().stateJson();
        }
        if ("POST /ptalk".equals(act)) {
            if (ptalk == null) return "{\"error\":\"ptalk not ready\"}";
            String on = field(body, query, "on");
            String ev = field(body, query, "event");
            String idle = field(body, query, "idle");
            // 验收用：quiet_sec = 对话后心跳静默期时长（默认 300s）；idle_sec = 心跳间隔（默认 180s）。
            // 不给就保持原值 —— 免得每次调开关都把静默期重置回默认。
            String qsec = field(body, query, "quiet_sec");
            if (!qsec.isEmpty()) {
                try { ptalk.setUserQuietSec(Integer.parseInt(qsec.trim())); }
                catch (NumberFormatException ignored) { }
            }
            String isec = field(body, query, "idle_sec");
            if (!isec.isEmpty()) {
                try {
                    int v = Math.max(30, Integer.parseInt(isec.trim()));
                    ctx.getSharedPreferences("ledi_agent", Context.MODE_PRIVATE)
                            .edit().putInt("ptalk_idle_sec", v).apply();
                } catch (NumberFormatException ignored) { }
            }
            return ptalk.set(
                    on.isEmpty() ? null : Boolean.valueOf("true".equals(on)),
                    ev.isEmpty() ? null : Boolean.valueOf("true".equals(ev)),
                    idle.isEmpty() ? null : Boolean.valueOf("true".equals(idle)));
        }
        // ================= newcore：媒体播放（音频/视频/图片/文字） =================
        if ("POST /media".equals(act)) {
            if (media == null) return "{\"error\":\"media not ready\"}";
            String kind = field(body, query, "kind");
            String data = field(body, query, "data");
            String name = field(body, query, "name");
            String path = field(body, query, "path");
            if ("audio".equals(kind)) {
                if (!path.isEmpty()) return "{\"result\":\"" + J.esc(media.playAudio(path)) + "\"}";
                if (data.isEmpty()) return "{\"error\":\"audio data or path required\"}";
                return "{\"result\":\"" + J.esc(media.playAudioBytes(data, name)) + "\"}";
            }
            if ("video".equals(kind)) {
                if (!path.isEmpty()) return "{\"result\":\"" + J.esc(media.playVideo(path)) + "\"}";
                if (data.isEmpty()) return "{\"error\":\"video data or path required\"}";
                return "{\"result\":\"" + J.esc(media.playVideoBytes(data, name)) + "\"}";
            }
            if ("image".equals(kind)) {
                int mx = 800;
                try { mx = Integer.parseInt(field(body, query, "max")); } catch (Exception ignored) {}
                String mode = field(body, query, "mode");
                if (!"crop".equalsIgnoreCase(mode)) mode = "fit";
                return "{\"result\":\"" + J.esc(media.showImage(data, mx, mode)) + "\"}";
            }
            if ("text".equals(kind)) {
                // 电脑端「播放文字」：上屏显示 + 机器人自带语音播报
                if (data.isEmpty()) return "{\"error\":\"text data required\"}";
                String shown = media.showText(data);
                String said = media.say(data);
                return "{\"result\":\"" + J.esc(shown) + "\",\"say\":\"" + J.esc(said) + "\"}";
            }
            return "{\"error\":\"bad kind: " + J.esc(kind) + "\",\"hint\":\"audio|video|image|text\"}";
        }
        if ("POST /media/stop".equals(act)) {
            if (media == null) return "{\"error\":\"media not ready\"}";
            media.stopAudio();
            face.hide();
            return "{\"result\":\"stopped\"}";
        }
        if ("POST /ttsstop".equals(act)) {
            tts.stop();
            return "{\"result\":\"stopped\"}";
        }
        return null;   // TABLE 已保证本域不认这个 act（正常不会发生）
    }

    /** 动作 / 电机 / 摇臂 / 灯 / 姿态。 */
    private String motionRoutes(String act, String query, String body) throws Exception {
        // ================= 电机姿态 + 调试面板（v147） =================
        // GET  /motorpos       → 三个电机的【读回】位置（原厂状态广播）+ **换算角度** + 最近一次下发
        //                        raw[3]=12 位编码器计数；ang[3]=按实测拟合换算出的角度（不可信 ⇒ null）
        // POST /motor {"j1":..,"j2":..,"j3":..,"ms":..} → 直接下发一帧姿态（角度域）
        // GET  /motordiag      → 读回链路自检（类在不在 / 收了几帧 / 静态字段兜底结果）
        // GET  /pad            → 调试面板状态
        // POST /pad {"on":true|false,"x":..,"y":..}   → 铺 / 摘面板、挪位置
        //
        // ★★ 这四个**都不在屏保总闸的白名单里**，是刻意的：用户 2026-09-24 的原话是
        //    「**进入熄屏则停止返回 api**」。屏保期间它们一律返回 {"error":"sleeping"}。
        //    /motordiag 也一起挡 —— 它虽然只读，但"只在清醒时可观测"才是一致的口径，
        //    否则会出现"屏保里能查到位置、可 API 说没有"这种自相矛盾。
        //    （第二层闸在 MotorPose.setSleeping：屏保期间连广播接收器都摘掉。）
        if ("GET /motorpos".equals(act)) {
            MotorPose mp = AgentService.motorPose;
            if (mp == null) return "{\"error\":\"motorpose not init\"}";
            return "{\"motor\":" + mp.positionJson() + "}";
        }
        if ("POST /motor".equals(act)) {
            MotorPose mp = AgentService.motorPose;
            if (mp == null) return "{\"error\":\"motorpose not init\"}";
            int j1 = intOr(field(body, query, "j1"), 0);
            int j2 = intOr(field(body, query, "j2"), 0);
            int j3 = intOr(field(body, query, "j3"), 0);
            int ms = intOr(field(body, query, "ms"), MotorPose.SLIDE_MS);
            SleepMode.touch();            // 远程下发即"有人在用"
            return mp.drive(j1, j2, j3, ms);
        }
        // ================= 解除锁定 / 释放舵机力矩（v153） =================
        // GET  /motorrelease → 上一次释放的结果（含 1.5s 后补采的 after / delta_max）
        // POST /motorrelease → 立刻释放三个舵机的力矩（手掰得动）
        //
        // 原厂通路（不是猜的）：EngineService.realeaseControl_All()（原厂就拼成 realease），
        // 内部发 0x1002 / 0x1001(obj=releaseIns) / 0x1003 三条消息，
        // releaseIns = "FF FF FE FF FF FF FF FF FF 07"。详见 MotorPose.release()。
        //
        // ★ 与 /motor* 同一口径：**不在屏保白名单里** ⇒ 屏保期间一律 {"error":"sleeping"}。
        //   理由：释放力矩会让手臂位姿在无人看管时下垂，而屏保正是"没人看"的状态。
        if ("GET /motorrelease".equals(act)) {
            MotorPose mp = AgentService.motorPose;
            if (mp == null) return "{\"error\":\"motorpose not init\"}";
            return "{\"release\":" + mp.releaseJson() + "}";
        }
        if ("POST /motorrelease".equals(act)) {
            MotorPose mp = AgentService.motorPose;
            if (mp == null) return "{\"error\":\"motorpose not init\"}";
            SleepMode.touch();            // 远程操作即"有人在用"
            return mp.release();
        }
        // 蓝牙网络共享（没有 WiFi 时的兜底链路）：机器人开这个才变成 192.168.44.1，
        // 电脑端才加得进它的个人区域网。不带参数 = 开；{"on":false} = 关。
        if ("POST /pan".equals(act)) {
            String on = field(body, query, "on");
            boolean enable = !("false".equals(on) || "0".equals(on));
            String err = BtPan.setTethering(ctx, enable);
            return "{\"result\":\"" + (err == null ? "OK" : J.esc(err))
                    + "\",\"on\":" + BtPan.poll(ctx)
                    + ",\"status\":" + BtPan.statusJson(ctx) + "}";
        }
        if ("GET /pan".equals(act))
            return "{\"status\":" + BtPan.statusJson(ctx) + "}";
        if ("POST /gsensorcali".equals(act)) {
            if (sensor != null) sensor.calibrate();
            return "{\"result\":\"calibrating\"}";
        }
        // ================= 动作 =================
        if ("GET /motions".equals(act)) return jsonList(MotionController.MOTIONS.length > 0
                ? toList(MotionController.MOTIONS) : enabledMotions());
        if ("GET /motions_all".equals(act)) return jsonList(allMotions());
        if ("GET /motions_dance".equals(act)) return jsonList(toList(MotionController.DANCE));
        if ("GET /motionconfig".equals(act))
            return "{\"enabled\":" + jsonList(enabledMotions()) + "}";
        if ("POST /motionconfig".equals(act)) {
            // {"enabled":["hug",...]} 或 {"reset":true}
            // 用 listCsv 而不是 field：值形如 ["hug","cheer"]，field 只能取到开头片段，
            // 会把「只启用 2 个动作」静默变成「只启用 1 个」。
            String list = listCsv(body, query, "enabled");
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if ("true".equals(field(body, query, "reset")) || list.isEmpty()) {
                sp.edit().remove("motions_enabled").apply();
                return "{\"result\":\"reset all\"}";
            }
            // 校验：只保留合法动作名（普通动作 + 舞曲）
            // 切分符必须含逗号：listCsv 交上来的是 "hug,cheer"，
            // 只按方括号/引号/空格切的话整串会被当成一个名字 → 一个都存不进去。
            StringBuilder sb = new StringBuilder();
            for (String m : list.split("[\\[\\]\" ,]+")) {
                if (m.isEmpty()) continue;
                if (MotionController.isValidMotion(m)) {
                    if (sb.length() > 0) sb.append(",");
                    sb.append(m);
                }
            }
            sp.edit().putString("motions_enabled", sb.toString()).apply();
            return "{\"result\":\"saved\",\"count\":"
                    + sb.toString().split(",").length + "}";
        }
        if ("POST /motion".equals(act)) {
            String name = field(body, query, "name");
            if (name == null) name = "";
            if (!MotionController.isValidMotion(name))
                return "{\"error\":\"unknown motion: " + J.esc(name)
                        + "\",\"hint\":\"GET /motions_all 取清单\"}";
            // dance_* 必须走 specialDance，走 moveInterface 会找不到动作集文件
            String r = MotionController.isDance(name)
                    ? motion.dance(name) : motion.motion(name);
            return "{\"result\":\"" + J.esc(r) + "\",\"kind\":\""
                    + (MotionController.isDance(name) ? "dance" : "motion")
                    + "\",\"name\":\"" + J.esc(name) + "\"}";
        }
        if ("POST /dance".equals(act))
            return "{\"result\":\"" + J.esc(motion.dance(field(body, query, "name"))) + "\"}";
        if ("POST /base".equals(act))
            return "{\"result\":\"" + J.esc(motion.baseAction(field(body, query, "name"))) + "\"}";
        if ("POST /stop".equals(act))
            return "{\"result\":\"" + J.esc(motion.stop()) + "\"}";
        // ================= 灯光（原厂系统广播） =================
        if ("POST /light".equals(act)) {
            String color = field(body, query, "color").trim().toLowerCase();
            String action = field(body, query, "action").trim().toLowerCase();
            return "{\"result\":\"" + J.esc(light(color, action)) + "\"}";
        }
        // ================= 姿态 =================
        if ("GET /poses".equals(act)) return jsonList(pose.list());
        if ("POST /pose".equals(act)) {
            String name = field(body, query, "name");
            String spec = field(body, query, "spec");
            boolean run = "true".equals(field(body, query, "run"));
            String r = pose.define(name, spec, run);
            return "{\"result\":\"" + J.esc(r) + "\"}";
        }
        if ("POST /poserun".equals(act))
            return "{\"result\":\"" + J.esc(pose.run(field(body, query, "name"))) + "\"}";
        if ("POST /posedel".equals(act))
            return "{\"result\":\"" + J.esc(pose.remove(field(body, query, "name"))) + "\"}";
        return null;   // TABLE 已保证本域不认这个 act（正常不会发生）
    }

    /** 屏幕 / 显示 / 投屏 / 旋转 / 悬浮窗 / 齿轮。 */
    private String mediaRoutes(String act, String query, String body) throws Exception {
        if ("POST /display".equals(act))
            return "{\"result\":\"" + J.esc(display.show(field(body, query, "text"))) + "\"}";
        if ("POST /displayoff".equals(act))
            return "{\"result\":\"" + J.esc(display.hide()) + "\"}";
        // ================= 网关 / 网络 =================
        if ("POST /config".equals(act)) {
            speaker.setGateway(field(body, query, "gateway"));
            return "{\"gateway\":\"" + J.esc(speaker.getGateway()) + "\"}";
        }
        // ★ v198：原来这里还有一个 `POST /volume`（只认 {"n":…}，解析失败就悄悄按 8 处理）。
        //   已经**合并**到上面那处，走 Av 单一出口 ——
        //   留着两份必然分叉（一份夹上限、一份不夹；一份回读、一份不回读）。
        //   参数名 n 仍然认，所以网页 ⑨ 的滑条不受影响。
        if ("POST /exclusive".equals(act)) {
            motion.setExclusive(field(body, query, "on").equals("true"));
            return "{\"exclusive\":" + motion.isExclusive() + "}";
        }
        // ================= 设置齿轮 =================
        if ("POST /gear".equals(act)) {
            // ★★★ v173：`on` **必须显式给**才动齿轮。
            //
            //   旧写法是 `boolean on = "true".equals(field(body, query, "on"));` ——
            //   对"没给这个参数"和"明确说要关"一视同仁，于是：
            //       POST /gear {"ip":false}      ← 只是想关掉那行 IP 提示
            //         ⇒ on=false ⇒ 齿轮被摘 + gear_on=false 落盘
            //         ⇒ 后勤的补画器条件是 `gearWanted() && !isShown()`，
            //            而 gearWanted() 读的正是 gear_on ⇒ **从此永不补画**
            //         ⇒ 本机屏幕上再没有任何入口（齿轮是唯一的屏幕入口）
            //         ⇒ 而且 hide() 一行日志都不写，事后完全无法归因
            //   本补丁把语义收窄：没给 on ⇒ 只当"改 IP"，齿轮一动不动；
            //   两个都没给 ⇒ 明确报错，绝不静默关掉屏幕入口。
            final SharedPreferences spGear =
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            final String onS = field(body, query, "on").trim();
            final String ipS = field(body, query, "ip").trim();
            if (onS.isEmpty() && ipS.isEmpty()) {
                return "{\"error\":\"need on or ip（例：{\\\"on\\\":true} / "
                        + "{\\\"on\\\":false} / {\\\"ip\\\":true}）\"}";
            }
            if (!onS.isEmpty()) {
                boolean on = "true".equalsIgnoreCase(onS) || "1".equals(onS);
                spGear.edit().putBoolean("gear_on", on).apply();
                if (on) GearOverlay.show(ctx);
                else    GearOverlay.hide(ctx, "API POST /gear {\"on\":false}");
            }
            // 只改屏幕上那行 IP 提示（默认关，见 GearOverlay.K_IP_ON）——**不碰齿轮**
            if ("true".equals(ipS) || "false".equals(ipS)) {
                spGear.edit().putBoolean("gear_ip_on", "true".equals(ipS)).apply();
                GearOverlay.refreshIp(ctx);
            }
            // 回执一律报**实际值**，不报"你以为你设的那个"
            return "{\"gear\":" + GearOverlay.isShown()
                    + ",\"gear_on\":" + spGear.getBoolean("gear_on", true)
                    + ",\"ip\":" + spGear.getBoolean("gear_ip_on", false) + "}";
        }
        // ================= 桌面（HOME） =================
        // 需求：把桌面**锁定在原厂机器人 App 上**（不是 agent）。三级兜底见 HomeSetter。
        //   GET  /home                  -> 默认桌面 / 目标 / recorded / candidates_n / unique / settled
        //   POST /sethome               -> 把偏好写到目标桌面（幂等）
        //                                  ★ v133 起还会**对齐我们自己那条 HOME 入口**
        //                                  （target≠我们 ⇒ 关掉自己那条 ⇒ 全机候选唯一），
        //                                  返回 result=aligned 表示刚做了这一步。
        //   POST /sethome {"force":true} -> 允许走 ④（禁用其它 HOME 入口的兜底路径）
        //   POST /sethome?restore=true  -> 物归原主（恢复被禁用的入口 + 把目标桌面设回去）
        //   POST /sethome?lock=false    -> 只解除"持续锁定"，当前偏好保留
        //   ★ 权威判据不在本接口：按一次 Home 看 `dumpsys window` 的 mCurrentFocus
        //     。
        if ("GET /home".equals(act))
            return HomeSetter.statusJson(ctx);
        if ("POST /sethome".equals(act)) {
            if ("true".equals(field(body, query, "restore"))) {
                return HomeSetter.restore(ctx);
            }
            String lock = field(body, query, "lock");
            if (!lock.isEmpty()) HomeSetter.setLock(ctx, "true".equals(lock));
            String r = HomeSetter.ensure(ctx, "true".equals(field(body, query, "force")));
            // ensure 没成功时补上状态，便于一次请求就看清现场
            return r;
        }
        // ---- v63：屏幕朝向锁 ----
        // 查：GET /rotation
        // 配：POST /rotation {"on":false}          —— 放开自动旋转（默认锁横向）
        //     POST /rotation {"value":0}          —— 0/1/2/3 = 0°/90°/180°/270°
        //     POST /rotation {"apply":true}       —— 立刻纠一次
        if ("GET /rotation".equals(act) || "POST /rotation".equals(act)) {
            String on = field(body, query, "on");
            String val = field(body, query, "value");
            Boolean bOn = null;
            Integer iVal = null;
            if (on.length() > 0) bOn = Boolean.valueOf("true".equalsIgnoreCase(on) || "1".equals(on));
            if (val.length() > 0) {
                try { iVal = Integer.valueOf(Integer.parseInt(val)); } catch (Throwable ignore) {}
            }
            if (bOn != null || iVal != null) {
                RotationLock.set(ctx, bOn, iVal);
                RotationLock.apply(ctx);
            }
            String ap = field(body, query, "apply");
            if ("true".equalsIgnoreCase(ap) || "1".equals(ap)) RotationLock.apply(ctx);
            return RotationLock.statusJson(ctx);
        }
        return null;   // TABLE 已保证本域不认这个 act（正常不会发生）
    }

    /** 系统 / 电源 / 进程 / 接入 / 身份。 */
    private String sysRoutes(String act, String query, String body) throws Exception {
        if ("POST /name".equals(act)) {
            StringBuilder sb = new StringBuilder("{\"before\":").append(Presence.json(ctx));
            String nm = field(body, query, "name");
            if (!nm.isEmpty()) {
                sb.append(",\"name\":\"").append(J.esc(Presence.setName(ctx, nm))).append("\"");
            }
            String ps = field(body, query, "probe_sec");
            if (!ps.isEmpty()) {
                try { Presence.setNameProbeSec(ctx, Integer.parseInt(ps.trim())); } catch (Throwable ignored) {}
            }
            String ml = field(body, query, "miss_limit");
            if (!ml.isEmpty()) {
                try { Presence.setMissLimit(ctx, Integer.parseInt(ml.trim())); } catch (Throwable ignored) {}
            }
            sb.append(",\"presence\":").append(Presence.json(ctx)).append("}");
            return sb.toString();
        }
        // ================= 复活通道 / 死给谁看（v134） =================
        // GET  /revive              → 复活账本：本进程是被**谁**拉起来的、停机多久、
        //                             各通道各救过几次、最近 6 次记录
        // POST /revive {"in_s":45}  → 排一个 45s 后的**精确唤醒闹钟**（验收用）
        // POST /revive {"period_s":40} → 把常驻心跳周期临时改成 40s（验收用；
        //                              ★ 验完记得 {"period_s":180} 改回来）
        // POST /kill {"yes":true}   → ★ 立刻 SIGKILL 自己（验收用，见下）
        //
        // ★ 为什么必须有 /kill：兜底机制的唯一权威判据是"**真死了之后能不能回来**"。
        //   能模拟"真死"的只有它 ——
        //     · `am force-stop` 会把包标成 stopped：按键广播（FLAG_EXCLUDE_STOPPED_PACKAGES）
        //       收不到、闹钟被清 ⇒ 测出来的是"系统禁止我活"，不是我们的兜底；
        //     · `pm disable` 会被系统当"用户明确禁用"，不是干净的死法。
        //   SIGKILL 自己 = 最接近 native 崩溃 / LMK 被杀的那种死法，不留任何
        //   "禁止重启"的标记。先回包、400ms 后再死，否则调用方只会看到连接被重置。
        //   ★ 2026-09-23 实测更正：早先我把那 13 分钟的停机归因于"pm disable 把进程打死"，
        //     **这条是错的** —— 本次复现 {pm disable com.ledi.agent/.BrainHome} 返回
        //     `new state: disabled` 而 pid **没变**、8899 照样通。那次真因至今未定。
        if ("GET /revive".equals(act)) {
            return ReviveLog.statusJson(ctx);
        }
        if ("POST /revive".equals(act)) {
            String inS = field(body, query, "in_s");
            String perS = field(body, query, "period_s");
            // ★ period_s：把**常驻心跳**的周期临时改小（验收用）。没有它，"常驻心跳
            //   真能把死进程拉回来"就只能靠撞运气 —— 之前那次"等了 2 秒就活"正是
            //   恰好撞上 180s 闹钟的到期时刻，不是证据（见 CrashGuard.setPeriod 注释）。
            if (!perS.isEmpty()) {
                long ps;
                try { ps = Long.parseLong(perS.trim()); } catch (Exception e) { ps = 0; }
                if (ps < 10 || ps > 21600) {
                    return "{\"ok\":false,\"error\":\"period_s 取值 10..21600 秒\"}";
                }
                CrashGuard.setPeriod(ctx, ps * 1000L);
                if (inS.isEmpty()) {
                    return "{\"ok\":true,\"period_s\":" + ps
                            + ",\"guard\":" + CrashGuard.statusJson() + "}";
                }
            }
            if (!inS.isEmpty()) {
                long sec;
                try { sec = Long.parseLong(inS.trim()); } catch (Exception e) { sec = 0; }
                if (sec <= 0 || sec > 3600) {
                    return "{\"ok\":false,\"error\":\"in_s 取值 1..3600 秒\"}";
                }
                CrashGuard.armForTest(ctx, sec * 1000L);
                return "{\"ok\":true,\"armed_for_s\":" + sec + ",\"period_s\":" + perS
                        + ",\"revive\":" + ReviveLog.statusJson(ctx) + "}";
            }
            return "{\"ok\":true,\"revive\":" + ReviveLog.statusJson(ctx) + "}";
        }
        // ================= 屏保期冻原厂（v140） =================
        // GET  /freezer                        → 上一次冻/解冻动了哪些进程、成功没
        // POST /freeze {"main":true|false}     → 立刻冻结原厂（验收用；屏保会自动做）
        // POST /thaw                           → 立刻解冻（★ 冻歪了时的远程解药）
        //
        // ★ 为什么必须能在屏保期间调到它们：屏保本身就会冻原厂，所以"冻错了"这件事
        //   只会在屏保期间发生；这时若被屏保总闸挡住，就只剩重启一条路。
        if ("GET /freezer".equals(act)) {
            return ProcessFreezer.statusJson();
        }
        if ("POST /freeze".equals(act)) {
            String m = field(body, query, "main");
            boolean withMain = m.isEmpty() || "true".equals(m) || "1".equals(m);
            ProcessFreezer.freezeAsync(ctx, withMain);
            return "{\"ok\":true,\"include_main\":" + withMain
                    + ",\"hint\":\"异步，200ms 后 GET /freezer 看结果\"}";
        }
        if ("POST /thaw".equals(act)) {
            boolean ok = ProcessFreezer.thawSync(ctx);
            return "{\"ok\":" + ok + ",\"freezer\":" + ProcessFreezer.statusJson() + "}";
        }
        if ("POST /kill".equals(act)) {
            if (!"true".equals(field(body, query, "yes"))) {
                return "{\"ok\":false,\"error\":\"need {\\\"yes\\\":true}\""
                        + ",\"hint\":\"这会立刻把自己 SIGKILL 掉（自检兜底用）\"}";
            }
            final boolean useExit = "EXIT".equalsIgnoreCase(field(body, query, "sig"));
            // ★ mode=cold：先 stopService 再死。
            //   目的是**故意把系统自己的自愈拿掉**，好单独验我们自己的通道：
            //   实测裸 SIGKILL 之后 AMS 会按 START_STICKY 在 ~2s 内把它拉回来
            //   （通道 ams_sticky），那样就永远测不出"闹钟/按键通道到底行不行"。
            //   stopService 会把 ServiceRecord 的 startRequested 清掉 ⇒ AMS 不再自愈，
            //   这时能把它救回来的只剩我们自己的闹钟与静态广播 —— 正是要证的东西。
            final boolean cold = "cold".equalsIgnoreCase(field(body, query, "mode"));
            final int pid = android.os.Process.myPid();
            BootLog.w("★ POST /kill 被调用：400ms 后 "
                    + (useExit ? "System.exit(10)" : "SIGKILL") + " 自己 pid=" + pid
                    + (cold ? "（cold：先 stopService 拿掉 AMS 的自愈）" : ""));
            final android.content.Context app = ctx.getApplicationContext() == null
                    ? ctx : ctx.getApplicationContext();
            Thread suicide = new Thread(new Runnable() {
                @Override public void run() {
                    try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                    if (cold) {
                        try {
                            app.stopService(new Intent(app, AgentService.class));
                        } catch (Throwable t) {
                            BootLog.w("cold: stopService 失败 " + t);
                        }
                        try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                    }
                    if (useExit) {
                        System.exit(10);
                    } else {
                        android.os.Process.killProcess(android.os.Process.myPid());
                    }
                }
            }, "ledi-suicide");
            suicide.setDaemon(true);
            suicide.start();
            return "{\"ok\":true,\"killing\":true,\"mode\":\""
                    + (cold ? "cold" : "normal") + "\",\"sig\":\""
                    + J.esc(useExit ? "EXIT" : "KILL") + "\",\"pid\":" + pid + "}";
        }
        // ================= 状态 =================
        if ("GET /health".equals(act)) {
            return "{\"name\":\"LediAgent\",\"ip\":\"" + J.esc(HttpServer.localIp())
                    + "\",\"engine\":" + motion.hasEngine()
                    + ",\"ble\":" + ble.isRunning()
                    // ★ v176：bt 也改成真判据（旧写法同样只报标志位，见 /status 那段注释）。
                    //   bt_on 一直是适配器的真值，保留；再加 bt_state 便于分清
                    //   "根本没在开(10)" 和 "卡在打开中(11)"。
                    + ",\"bt\":" + (bt != null && bt.listening()
                            && AgentService.bluetoothOn())
                    + ",\"bt_accept\":" + (bt != null && bt.listening())
                    + ",\"bt_on\":" + AgentService.bluetoothOn()
                    + ",\"bt_state\":" + AgentService.btState()
                    + ",\"gear\":" + GearOverlay.isShown()
                    // ★ v170：齿轮窗口的 type。**这是"新代码上机"的判据** ——
                    //   `/status.version` 读的是 APK manifest，装完包立刻就是新号，
                    //   证明不了进程里跑的是新代码（v168 栽过）。2010 才说明这段真在跑。
                    + ",\"gear_ty\":" + GearOverlay.GEAR_TYPE
                    // ★★★ v173：齿轮可归因两件套。
                    //   gear_on  = **开关的落盘实际值**（判据要读 prefs 实际值，
                    //              不能信界面/内存里那个开关 —— 本项目吃过多次亏）
                    //   gear_hist= 画了几次/摘了几次/最近一次是谁摘的（见 GearOverlay.whyJson）
                    // 这两项是为"齿轮小标不见了"这类**事后无法归因**的故障加的：
                    // 下次它再消失，先看 gear_hist.why，不用再猜。
                    + ",\"gear_on\":" + ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .getBoolean("gear_on", true)
                    + ",\"gear_hist\":" + GearOverlay.whyJson()
                    + ",\"exclusive\":" + motion.isExclusive()
                    + ",\"gateway\":\"" + J.esc(speaker.getGateway()) + "\""
                    + ",\"relay\":\"" + J.esc(AgentService.relay == null ? "" : AgentService.relay.status()) + "\""
                    + ",\"home\":\"" + J.esc(HomeSetter.currentHome(ctx)) + "\""
                    // home_ours = 桌面是不是我们（现在**不该**是了，见 HomeSetter 注释）；
                    // home_on_target = 桌面是不是已经锁在原厂机器人 App 上 —— 这才是期望值
                    + ",\"home_ours\":" + HomeSetter.isUs(ctx)
                    + ",\"home_target\":\"" + J.esc(HomeSetter.targetName(ctx)) + "\""
                    + ",\"home_on_target\":" + HomeSetter.isTarget(ctx)
                    // 与电脑端连通 + 屏幕上那行 IP 当前该不该显示（0=在显示）
                    + ",\"pc_link\":" + PcLink.statusJson()
                    + ",\"ip_shown\":" + GearOverlay.isIpShown()
                    // 电脑端反向广播听到了没（heard=true 说明本机的网关设置
                    // 会被它自动纠正 —— 用于排「机器人还在往旧电脑发请求」这类问题）
                    + ",\"pc_beacon\":" + UdpBeacon.statusJson()
                    + ",\"pan\":" + BtPan.statusJson(ctx)
                    + ",\"adbwifi\":" + AdbWifi.listening()
                    // ★★★ v175：本机编程器降级原因（空 = WebView 可用，或还没点开过）。
                    //   **这是"新代码上机"的判据**：/status.version 读的是 APK manifest，
                    //   装完包立刻就是新号，证明不了进程里跑的是新代码（v168 栽过）——
                    //   要看这个新字段在不在。本机实测会是非空（ROM 无 WebView）。
                    + ",\"prog_err\":\"" + J.esc(ProgrammerActivity.lastError) + "\""
                    + ",\"net\":\"" + J.esc(wifi.status()) + "\"}";
        }
        // ================= 蓝牙开关（v176）=================
        //   GET  /bt               -> 蓝牙实况（on/state/keep/accept + 看护计数器）
        //   POST /bt {"on":true}   -> 开（并把"由我们长期看住"置回 true）
        //   POST /bt {"on":false}  -> 关（并且**记住是用户关的**，后勤不再自动开回来）
        //
        // ★ 为什么必须有这条：用户的遥控主路径是「先用蓝牙与机器人连接，再用蓝牙
        //   握手网络」，而 2026-09-25 实测设备跑到 104 分钟时蓝牙是关的 ——
        //   只要 WiFi 这条腿还在，就必须有一条**从 HTTP 去开蓝牙**的路，
        //   否则手机/电脑永远迈不进第一环（BLE 配网、SPP 直连都要求适配器是开的）。
        // ★ 语义按用户的理解来：这个开关**就是蓝牙开关本身**，不是"看护策略开关"。
        //   所以 on=false 必须同时把 keep 置 false —— 否则 30 秒后自己开回来，
        //   界面上那个"已关闭"就是假的（本项目最忌讳这类开关）。
        // ★ enable() 是异步的（10→11→12 要几百毫秒到几秒），本端点**不阻塞等**
        //   （请求路径上不能挂异步等待）：立刻回执，要确认就轮询 GET /bt 的 on。
        if ("GET /bt".equals(act)) {
            return "{\"bt\":" + AgentService.btDiagJson(ctx) + "}";
        }
        if ("POST /bt".equals(act)) {
            String on = field(body, query, "on");
            boolean want = !("false".equals(on) || "0".equals(on));
            AgentService.setBtKeepOn(ctx, want);
            if (want) AgentService.enableBluetooth("POST /bt");
            else AgentService.disableBluetooth("POST /bt");
            return "{\"want\":" + want + ",\"bt\":" + AgentService.btDiagJson(ctx)
                    + ",\"note\":\"enable/disable 是异步的，2~4s 后看 GET /bt 的 on\"}";
        }
        // ================= 无线 ADB（手机端投屏用）=================
        //   GET  /adbwifi                       -> 状态（on/listening/ip/port/scrcpy 命令）
        //   POST /adbwifi {"on":true}           -> 一键开（写 service.adb.tcp.port=5555）
        //   POST /adbwifi {"on":false}          -> 一键关（写 -1）
        //   POST /adbwifi {"auto":true}         -> 记住"下次开机也开"（默认不记）
        //
        // ★ 为什么在这里能做：本 App 是 android.uid.system(uid=1000) + SELinux Permissive，
        //   实测可以直接 setprop。
        // ★ 但"只写属性就能开"是**错的**（我一度把它当已验证）：本 ROM 的 adbd
        //   **只在启动时读一次** `service.adb.tcp.port`。开/关都必须配一次
        //   `setprop ctl.restart adbd`，且状态一律以 /proc/net/tcp 为准。
        //   详见 AdbWifi 的类注释。
        if ("GET /adbwifi".equals(act))
            return AdbWifi.state(ctx);
        if ("POST /adbwifi".equals(act)) {
            String auto = field(body, query, "auto");
            if ("true".equals(auto) || "false".equals(auto)) {
                AdbWifi.setAuto(ctx, "true".equals(auto));
            }
            String on = field(body, query, "on");
            if ("true".equals(on) || "false".equals(on)) {
                return AdbWifi.set(ctx, "true".equals(on));
            }
            return AdbWifi.state(ctx);      // 无参 = 只查/只设 auto
        }
        // ================= 远程 shell（手机端自动化 + adb 逃生舱）=================
        //   GET  /shell                        -> 开关状态
        //   POST /shell {"cmd":"..."}          -> 以 uid=1000(system) 跑一条命令
        //   POST /shell {"cmd":"...","root":1} -> 走 su -c 以 root 跑
        //   POST /shell {"on":true|false}      -> 开/关这个端点
        // 安全说明见 Shell 类注释：本机 5555 本来就对同网段开放 root shell，
        // 所以这不新增暴露面；但仍然可以关掉。
        if ("GET /shell".equals(act))
            return "{\"enabled\":" + Shell.enabled(ctx) + "}";
        if ("POST /shell".equals(act)) {
            String on = field(body, query, "on");
            if ("true".equals(on) || "false".equals(on)) {
                Shell.setEnabled(ctx, "true".equals(on));
                String cmd0 = field(body, query, "cmd");
                if (cmd0.isEmpty()) return "{\"enabled\":" + Shell.enabled(ctx) + "}";
            }
            String cmd = field(body, query, "cmd");
            String root = field(body, query, "root");
            int to = 0;
            try { to = Integer.parseInt(field(body, query, "timeout")); }
            catch (Throwable ignored) {}
            return Shell.handle(ctx, cmd, "true".equals(root) || "1".equals(root), to);
        }
        if ("GET /switch".equals(act))
            return reaction == null ? "{\"error\":\"reaction not ready\"}" : reaction.json();
        if ("POST /switch".equals(act)) {
            if (reaction == null) return "{\"error\":\"reaction not ready\"}";
            String ai = field(body, query, "ai");
            String nv = field(body, query, "native");
            return reaction.set(ai.isEmpty() ? null : Boolean.valueOf("true".equals(ai)),
                                nv.isEmpty() ? null : Boolean.valueOf("true".equals(nv)));
        }
        if ("GET /status".equals(act)) {
            int battery = 0;
            try {
                BatteryManager bm = (BatteryManager)
                        ctx.getSystemService(Context.BATTERY_SERVICE);
                battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            } catch (Exception ignored) {}
            BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
            String btn = "", btm = "";
            if (ba != null) {
                btn = ba.getName() == null ? "" : ba.getName();
                try { btm = ba.getAddress(); } catch (Exception ignored) {}
            }
            return "{\"ip\":\"" + J.esc(HttpServer.localIp())
                    // ★ v154：把"设备上跑的是哪个版本"摆到第一格。之前没有这个字段，
                    //   验版本只能再去敲 adb；而"我到底装上了没有"恰恰是每次装机后第一个问题。
                    + "\",\"version\":\"" + J.esc(appVersion(ctx)) + "\""
                    + ",\"battery\":" + battery
                    // ★ v202：battery 只是"一格数字"（v154 起就有）。这里再并一份**完整**
                    //   电量对象（电压/温度/充电状态/来源/新鲜度）—— 与 GET /battery 同源
                    //   （都是 Battery.info），不另写一份解析。见 Battery.java。
                    + ",\"battery_detail\":" + Battery.info(ctx)
                    + ",\"engine\":" + motion.hasEngine()
                    // ★ v176：bt_listen 改成**真判据**。此前它只报 BtCommandServer
                    //   自己的标志位 running（start() 里立刻置 true），适配器关着
                    //   照样是 true —— 2026-09-25 就是被这个读数骗了一轮，多绕了
                    //   一大圈才确认"蓝牙根本没开"。现在要求 accept 线程在听
                    //   **且** 适配器真的开着；拆开的原始读数见 bt_accept 与 bt.on。
                    + ",\"bt_listen\":" + (bt != null && bt.listening()
                            && AgentService.bluetoothOn())
                    + ",\"bt_accept\":" + (bt != null && bt.listening())
                    + ",\"bt_accept_thread\":" + (bt != null && bt.isRunning())
                    + ",\"bt\":" + AgentService.btDiagJson(ctx)
                    + ",\"bt_name\":\"" + J.esc(btn) + "\""
                    + ",\"bt_mac\":\"" + J.esc(btm) + "\""
                    + ",\"gateway\":\"" + J.esc(speaker.getGateway()) + "\""
                    + ",\"net\":\"" + J.esc(wifi.status()) + "\""
                    // 无线 ADB 是不是真的在听（手机端"投屏"那一半的前提）。
                    // ★ 报的是 /proc/net/tcp 里 5555 的 LISTEN 实况，不是属性值 ——
                    //   属性写进去到端口起来是异步的，拿属性当成功就是假绿灯。
                    + ",\"adbwifi\":" + AdbWifi.listening()
                    + ",\"adbwifi_port\":" + AdbWifi.PORT
                    // 头顶键：head_key = 累计按下次数（只加不减）—— 用来证明
                    // "按键广播确实送到了我们 App"。看 last 是看不出来的（同键连按不变）。
                    + ",\"head_key\":" + (sensor == null ? 0 : sensor.headKeyCount)
                    + ",\"head_key_ago\":" + (sensor == null ? -1 : sensor.lastHeadKeyAgo())
                    // ★ v127 起长短按是两个动作，计数必须分开 —— 「判定层」（SensorHub
                    //   按按住时长分流）与「动作层」（HeadKey 做了什么）也分开，
                    //   这样"长按阈值判错"与"长按动作写错"能一眼区分。
                    //   判定层看这两个数，动作层看下面 headkey.long_presses/sleeps。
                    + ",\"head_key_short\":" + (sensor == null ? 0 : sensor.headKeyShortCount)
                    + ",\"head_key_long\":" + (sensor == null ? 0 : sensor.headKeyLongCount)
                    + ",\"head_key_held_ms\":" + (sensor == null ? 0 : sensor.lastHeadKeyHeldMs)
                    + ",\"headkey\":" + (AgentService.headKey == null
                            ? "{}" : AgentService.headKey.json())
                    + ",\"posture\":\"" + J.esc(sensor == null ? "" : sensor.posture) + "\""
                    + ",\"touch\":\"" + J.esc(sensor == null ? "" : SensorHub.zh(sensor.lastTouch)) + "\""
                    + ",\"motor\":\"" + J.esc(motion.lastAction()) + "\""
                    + ",\"motions\":" + allMotions().size()
                    + ",\"faces\":" + FaceController.listFaces().size()
                    + ",\"tts\":\"" + J.esc(ttsEngine()) + "\""
                    + ",\"sdk\":" + (sdk != null && sdk.hasEngine())
                    + ",\"sdk_event\":\"" + J.esc(sdk == null ? "" : sdk.lastEvent()) + "\""
                    + ",\"last_light\":\"" + J.esc(lastLight) + "\""
                    // 抹头顶（强制唤醒打断）与唤醒引擎的实况 —— 这两块是
                    // "语音喊不动 / 摸头没反应"这类问题的第一现场证据
                    + ",\"pat\":" + (sensor == null ? "{}"
                            : ("{\"count\":" + sensor.patCount
                               + ",\"ago\":" + sensor.lastPatAgo() + "}"))
                    + ",\"wake\":" + (voiceWake == null ? "{}"
                            : ("{\"listening\":" + voiceWake.isListening()
                               + ",\"restarts\":" + voiceWake.restarts()
                               + ",\"hits\":" + voiceWake.hitCount()
                               + ",\"forced\":" + voiceWake.forcedWakes()
                               + ",\"continuous\":" + voiceWake.isContinuous()
                               + ",\"speaking\":" + voiceWake.isSpeaking()
                               + ",\"words\":\"" + J.esc(voiceWake.vocabWords()) + "\"}"
                               + ",\"dialog\":" + (voiceWake == null ? "{}" : voiceWake.dialog().stateJson())))
                    + ",\"continuous\":" + AgentService.continuousStatus()
                    + ",\"switches\":" + (reaction == null ? "{}" : reaction.json())
                    // 双麦声源定位：用户现在在哪一侧（说话人方位）
                    + ",\"doa\":" + (locator == null ? "{}" : locator.stateJson())
                    // 屏幕朝向：机器人会被抱起/倾斜，自动旋转会把脸转躺下
                    + ",\"rotation\":" + RotationLock.statusJson(ctx)
                    // 崩溃兜底：进程被杀之后能不能自己回来（真机验证过的机制才敢说"调好了"）
                    + ",\"crashguard\":" + CrashGuard.statusJson()
                    // ★ v134 复活账本（落在 prefs 里，进程死了也不丢）：
                    //   channel = 本进程是被谁拉起来的（alarm/key/touch/boot/explicit/ams_sticky）
                    //   silent_s = 那一次停机多久（±心跳周期）
                    //   beat_ago_s = 现在距上次心跳多久 —— **活着的硬判据**，不能超 backstop_s
                    + ",\"revive\":" + ReviveLog.statusJson(ctx)
                    // ★ v140 屏保期冻结原厂（用户 2026-09-24 要求「其他软件都停止」）：
                    //   frozen = 现在冻着没；n = 上一次动了几个进程；
                    //   main_skipped = 因为原厂是前台而**故意跳过主进程**（防 ANR）
                    + ",\"freezer\":" + ProcessFreezer.statusJson()
                    // 长期对话日志 / 多轮记忆（v93）：pairs = 大模型现在能记住几轮。
                    // ★ 必须能在 /status 一眼看到 —— 否则"记住没有"只能靠猜。
                    + ",\"talk\":" + TalkLog.statusJson()
                    // 离线意图解析（v94）：词表镜像条数 + 上一句走了本地直通还是 AI。
                    // ★ 必须在这里 —— 否则"这句话到底是谁答的"在双端日志里看不出来。
                    + ",\"intent\":" + IntentResolver.statusJson()
                    // 在线听写（v97）：讯飞 MSC 的授权/初始化状态。
                    // ★ 授权是"在线能不能用"的唯一前提，必须一眼可见。
                    // ★ v99：`online_on` = 对话链路是不是"在线优先"，busy_what = 谁在占用会话。
                    + ",\"cloud\":" + cloud().statusJson(
                            voiceWake != null && voiceWake.isOnlineAsr())
                    // 听句子走哪条路（v99）：上一句是在线听懂还是回落离线。
                    // ★ 没有这个字段，"这句话到底是谁听懂的"在双端日志里查不出来。
                    + ",\"listen\":" + (voiceWake == null ? "{}" : voiceWake.listenStatusJson())
                    // 主线程队列仪表（v121）：pending>0 = 主线程真的忙不过来（而不是我们的
                    // job 自己卡住）。★ 必须能读出来 —— 否则"startListening 超时"只能靠猜。
                    + ",\"main_thread\":" + MainThread.json()
                    // 屏保（v122）：铺满一张图 + 闸住全部功能。见 SleepMode/SleepScreen。
                    + ",\"sleep\":" + SleepMode.json(ctx)
                    // 在线状态（v142）：离线模式是"省 token"的执行机构 ——
                    // offline=true 时不会再有在线听写/大模型/主动说话的外发。
                    // ★ 必须摆在 /status 第一屏能看到的位置，否则"它到底还在不在往外花钱"
                    //   只能靠抓包，用户根本没法自查。
                    + ",\"presence\":" + Presence.json(ctx)
                    + ",\"uptime_s\":" + SystemClock.elapsedRealtime() / 1000
                    + "}";
        }
        if ("POST /wifi".equals(act)) {
            final String ssid = field(body, query, "ssid");
            final String pass = field(body, query, "pass");
            if (ssid.isEmpty()) return "{\"error\":\"ssid required\"}";
            wifi.connect(ssid, pass, null);
            return "{\"result\":\"provisioning: " + J.esc(ssid) + "\"}";
        }
        return null;   // TABLE 已保证本域不认这个 act（正常不会发生）
    }

    /** 原厂 SDK 直控面（/sdk/*）。 */
    private String sdkRoutes(String act, String query, String body) throws Exception {
        // ================= 原厂 SDK 直调（xldbasesdk） =================
        // 与 /motion、/light 的区别：这里走原厂自己的 API（含序列、节奏、电源、ADB），
        // 不是我们重写的通道。见 SdkController 的类注释。
        if ("GET /sdk/caps".equals(act))
            return sdk == null ? "{\"error\":\"sdk not ready\"}" : sdk.caps();
        if ("GET /sdk/stat".equals(act))
            return sdk == null ? "{\"error\":\"sdk not ready\"}" : sdk.stat();
        if ("POST /sdk/forceready".equals(act))
            return sdk == null ? "{\"error\":\"sdk not ready\"}"
                    : "{\"result\":\"" + J.esc(sdk.forceReady()) + "\"}";
        if ("GET /sdk/events".equals(act))
            return sdk == null ? "{\"error\":\"sdk not ready\"}"
                    : "{\"last\":\"" + J.esc(sdk.lastEvent()) + "\",\"events\":"
                      + sdk.eventsJson() + "}";
        if ("POST /sdk/listen".equals(act))
            return sdk == null ? "{\"error\":\"sdk not ready\"}"
                    : "{\"result\":\"" + J.esc(sdk.listen()) + "\"}";
        if ("POST /sdk/series".equals(act)) {
            if (sdk == null) return "{\"error\":\"sdk not ready\"}";
            List<String> names = listField(body, query, "names");
            if (names.isEmpty()) return "{\"error\":\"names required\",\"hint\":\"\\\"names\\\":[\\\"hug\\\",\\\"cheer\\\"] 或 names=hug,cheer\"}";
            long delay = 0;
            try { delay = Long.parseLong(field(body, query, "delay")); } catch (Exception ignored) {}
            String r = sdk.series(names.toArray(new String[names.size()]), delay);
            return "{\"result\":\"" + J.esc(r) + "\",\"count\":" + names.size()
                    + ",\"delay\":" + delay + ",\"names\":" + jsonList(names) + "}";
        }
        if ("POST /sdk/dance".equals(act)) {
            if (sdk == null) return "{\"error\":\"sdk not ready\"}";
            String name = field(body, query, "name");
            return "{\"result\":\"" + J.esc(sdk.dance(name)) + "\",\"name\":\"" + J.esc(name) + "\"}";
        }
        if ("POST /sdk/bpm".equals(act)) {
            if (sdk == null) return "{\"error\":\"sdk not ready\"}";
            int bpm = 60;
            try { bpm = Integer.parseInt(field(body, query, "bpm")); } catch (Exception ignored) {}
            return "{\"result\":\"" + J.esc(sdk.bpm(bpm)) + "\",\"bpm\":" + bpm + "}";
        }
        if ("POST /sdk/run".equals(act)) {
            if (sdk == null) return "{\"error\":\"sdk not ready\"}";
            String name = field(body, query, "name");
            if (!MotionController.isValidMotion(name))
                return "{\"error\":\"unknown motion: " + J.esc(name) + "\"}";
            int speed = 0;
            try { speed = Integer.parseInt(field(body, query, "speed")); } catch (Exception ignored) {}
            return "{\"result\":\"" + J.esc(sdk.run(name, speed)) + "\",\"speed\":" + speed + "}";
        }
        if ("POST /sdk/stop".equals(act))
            return sdk == null ? "{\"error\":\"sdk not ready\"}"
                    : "{\"result\":\"" + J.esc(sdk.stop()) + "\"}";
        if ("POST /sdk/release".equals(act))
            return sdk == null ? "{\"error\":\"sdk not ready\"}"
                    : "{\"result\":\"" + J.esc(sdk.release()) + "\"}";
        if ("POST /sdk/led".equals(act)) {
            if (sdk == null) return "{\"error\":\"sdk not ready\"}";
            String color = field(body, query, "color");
            String action = field(body, query, "action");
            return "{\"result\":\"" + J.esc(sdk.led(color, action)) + "\",\"via\":\"sdk\"}";
        }
        if ("POST /sdk/sys".equals(act)) {
            if (sdk == null) return "{\"error\":\"sdk not ready\"}";
            String what = field(body, query, "what");
            return "{\"result\":\"" + J.esc(sdk.sys(what)) + "\",\"what\":\"" + J.esc(what) + "\"}";
        }
        if ("POST /sdk/flag".equals(act)) {
            if (sdk == null) return "{\"error\":\"sdk not ready\"}";
            String low = field(body, query, "battery_low");
            if (low.isEmpty()) return "{\"error\":\"battery_low required\"}";
            return "{\"result\":\"" + J.esc(sdk.setBatteryLow("true".equals(low))) + "\"}";
        }
        return null;   // TABLE 已保证本域不认这个 act（正常不会发生）
    }

    /** 能力真源 / 文档 / 记忆 / 情绪 / 调性 / 思考 / 编排。 */
    private String capRoutes(String act, String query, String body) throws Exception {
        // ================= 调性（v143 成人化） =================
        // GET  /tone                                    → 当前档位 + **实际生效的每一句原文**
        // POST /tone {"level":"adult|neutral|child"}    → 换档
        // POST /tone {"addr":"你"}                      → 它怎么称呼用户
        // POST /tone {"pat_line":true|false}            → 摸头顶要不要出声
        // POST /tone {"pat_speak":true|false}           → 出声之后要不要等它播完再听
        //
        // ★ 为什么 GET 要把人格提示词与每一句台词**整段打出来**：
        //   成人化的验收是主观的（"听着像不像在跟成年人说话"），没有对照就只能凭印象。
        //   把"当前实际生效的原文"直接摊在眼前，才能一眼看出改了没有、改到哪一档；
        //   否则验完只剩一句"感觉好像好点了"，等于没验。
        // ★ 为什么允许 level=child：那是**改造前的原文**，是 A/B 的基准，
        //   也是改坏时一键回退的路（见 Tone.persona 的注释）。
        if ("GET /tone".equals(act)) {
            return Tone.json();
        }
        if ("POST /tone".equals(act)) {
            final String lv = field(body, query, "level");
            final String ad = field(body, query, "addr");
            final String pl = field(body, query, "pat_line");
            final String psp = field(body, query, "pat_speak");
            // ★ v214：性格 / 自定义性格（这几个字段与 POST /tone 的其余字段**并存**，
            //   一次请求里可以只给其中一个 —— 与 level/addr 的"可部分改"同一纪律）。
            final String st  = field(body, query, "style");
            final String cn  = field(body, query, "custom_name");
            final String cc  = field(body, query, "custom_clause");
            final String dc  = field(body, query, "delcustom");
            Boolean plb = pl.isEmpty() ? null : Boolean.valueOf(
                    "true".equalsIgnoreCase(pl) || "1".equals(pl));
            Boolean psb = psp.isEmpty() ? null : Boolean.valueOf(
                    "true".equalsIgnoreCase(psp) || "1".equals(psp));
            // ★ 顺序很关键：先增/删自定义档（它们会**顺带选中或回落**），再处理显式 style。
            if (!cn.isEmpty() || !cc.isEmpty()) {
                if (cn.isEmpty() || cc.isEmpty())
                    return "{\"error\":\"自定义性格需要 custom_name 与 custom_clause 都给\"}";
                String rc = Tone.setCustom(cn, cc);
                if (rc.startsWith("{\"error")) return rc;
            }
            if (!dc.isEmpty()) {
                String rd = Tone.delCustom(dc);
                if (rd.startsWith("{\"error")) return rd;
            }
            if (!st.isEmpty()) {
                String rs = Tone.setStyle(st);
                if (rs.startsWith("{\"error")) return rs;
            }
            String r = Tone.set(lv.isEmpty() ? null : lv, ad.isEmpty() ? null : ad, plb, psb);
            if (r != null && r.startsWith("{\"error")) return r;
            return "{\"tone\":" + Tone.json() + "}";
        }
        // ================= ★★★ v189：情绪系统 =================
        // 查：GET  /emotion  → 当前情绪 + 两个维度 + 它现在会让待机摆哪张脸
        // 设：POST /emotion {"name":"happy"}          按**情绪名**（原厂词汇表内的才认）
        //     POST /emotion {"code":10300}            按**原厂数字码**
        //     POST /emotion {"valence":0.9,"arousal":0.8}  直接给两个维度
        //     POST /emotion {"reset":true}            回到基线
        // 自检：GET /emotionselftest  → 用打包进来的**原厂两份 XML** 跑不变量断言
        //
        // ★ 为什么情绪名是从 assets 的 emotions.xml 现读、而不是写死一张表：
        //   写死就等于"我们编了一套情绪"，与原厂那套会悄悄分叉；现读则原厂改表我们跟。
        //   词典外的一律拒绝（setByName 会返回 false），不许"发明"情绪。
        if ("GET /emotion".equals(act) || "POST /emotion".equals(act)) {
            if ("POST /emotion".equals(act)) {
                final String nm = field(body, query, "name").trim();
                final String code = field(body, query, "code").trim();
                final String vv = field(body, query, "valence").trim();
                final String aa = field(body, query, "arousal").trim();
                final String mm = field(body, query, "mood").trim();   // ★ v214 直接给心情
                final String rst = field(body, query, "reset").trim();
                boolean any = false;
                if (nm.length() > 0) {
                    if (!Emotion.setByName(nm)) return "{\"ok\":false,\"error\":\"not in lexicon: " + J.esc(nm) + "\"}";
                    any = true;
                }
                if (code.length() > 0) {
                    try {
                        if (!Emotion.setByCode(Integer.parseInt(code))) {
                            return "{\"ok\":false,\"error\":\"unknown code: " + J.esc(code) + "\"}";
                        }
                        any = true;
                    } catch (Throwable t) {
                        return "{\"ok\":false,\"error\":\"bad code\"}";
                    }
                }
                if ("true".equalsIgnoreCase(rst) || "1".equals(rst)) {
                    Emotion.reset(); any = true;
                }
                if (vv.length() > 0 || aa.length() > 0) {
                    try {
                        if (vv.length() > 0) Emotion.valence = Float.parseFloat(vv);
                        if (aa.length() > 0) Emotion.arousal = Float.parseFloat(aa);
                        Emotion.repick("端点直接给维度");
                        any = true;
                    } catch (Throwable ignore) {}
                }
                if (mm.length() > 0) {
                    try { Emotion.setMood(Float.parseFloat(mm)); any = true; } catch (Throwable ignore) {}
                }
                if (!any) return "{\"ok\":false,\"error\":\"nothing to set\"}";
            }
            return "{\"ok\":true,\"emotion\":" + Emotion.json() + "}";
        }
        // ================= ★★★ v214：长期记忆 + 忘记 =================
        // 查：GET  /memory  → 认识第几天、聊过几次、记着哪些事（含 brief 原文，便于对差）
        // 记：POST /memory {"remember":"喜欢猫"}          → 记一条事实（存机器人本地）
        // 忘：POST /memory {"forget":"all|facts|talk"}    → ★ 真删，返回里带回读字节证据
        //     POST /memory {"action":"forget","scope":"facts"}  ← 等价别名（给网页用）
        //
        // ★ 为什么"忘记"要单开一个入口、且必须回读：只返回 {"ok":true} 的"忘记"是典型
        //   假绿灯 —— 删没删干净谁也不知道。Memory.forget 会把删完之后的**实际字节数**
        //   写进返回值，ok 由它算出来。真机验收就照这条问：忘完再 GET /memory、GET /talk，
        //   两处都必须空。
        if ("GET /memory".equals(act)) {
            return "{\"ok\":true,\"memory\":" + Memory.json() + "}";
        }
        if ("POST /memory".equals(act)) {
            final String rem  = field(body, query, "remember");
            final String fgt  = field(body, query, "forget");
            final String act2 = field(body, query, "action");
            final String scp  = field(body, query, "scope");
            if (!rem.isEmpty()) {
                String r = Memory.remember(rem);
                return "{\"ok\":" + r.contains("\"ok\":true") + ",\"result\":" + r
                        + ",\"memory\":" + Memory.json() + "}";
            }
            String fsc = "";
            if (!fgt.isEmpty()) fsc = fgt;
            else if ("forget".equalsIgnoreCase(act2) || "clear".equalsIgnoreCase(act2))
                fsc = scp.isEmpty() ? "all" : scp;
            if (!fsc.isEmpty()) {
                String r = Memory.forget(fsc);
                return "{\"ok\":" + r.contains("\"ok\":true") + ",\"result\":" + r
                        + ",\"memory\":" + Memory.json() + "}";
            }
            // 什么都没给：当"看"处理，别静默当成功
            return "{\"ok\":true,\"memory\":" + Memory.json()
                    + ",\"hint\":\"POST {\\\"remember\\\":\\\"…\\\"} 或 {\\\"forget\\\":\\\"all|facts|talk\\\"}\"}";
        }
        // ================= 本机能力索引（v203）=================
        // GET /caps            → 索引摘要（各类计数 + 说明书是否加载 + 是否 busy）
        // GET /caps?full=1     → 全量清单（网页面板用）
        // GET /caps?q=握手     → 检索（可解释：每条带命中关键词）
        //   ★ 这是"机器人自己找得到自己会什么"的那张表 —— 能力自省出来（动作表/表情表），
        //     说明书打包在 assets/ledi_caps.txt 随包发布（用户要求"打包进本地部署"）。
        if ("GET /caps".equals(act)) {
            String q = field(body, query, "q");
            String full = field(body, query, "full");
            if (q.length() > 0) {
                java.util.List<CapIndex.Hit> hs = CapIndex.search(q, intOr(field(body, query, "k"), 6));
                StringBuilder sb = new StringBuilder("{\"ok\":true,\"q\":\"").append(J.esc(q))
                        .append("\",\"hits\":[");
                for (int i = 0; i < hs.size(); i++) {
                    CapIndex.Hit h = hs.get(i);
                    if (i > 0) sb.append(',');
                    sb.append("{\"name\":\"").append(J.esc(h.cap.name))
                      .append("\",\"kind\":\"").append(h.cap.kind)
                      .append("\",\"score\":").append(h.score)
                      .append(",\"why\":\"").append(J.esc(h.why))
                      .append("\",\"desc\":\"").append(J.esc(h.cap.desc == null ? "" : h.cap.desc))
                      .append("\"}");
                }
                return sb.append("],\"index\":").append(CapIndex.statusJson()).append('}').toString();
            }
            if (full.length() > 0) {
                return "{\"ok\":true,\"index\":" + CapIndex.statusJson()
                        + ",\"items\":" + CapIndex.fullJson() + "}";
            }
            return "{\"ok\":true,\"index\":" + CapIndex.statusJson()
                    + ",\"brief\":\"" + J.esc(CapIndex.searchBrief(field(body, query, "brief"), 0).trim()) + "\"}";
        }
        // ================= 能力真源 / 权限矩阵（v207）=================
        // GET /abilities        → 三态矩阵：read（只读查询）/ write（有限更改）/
        //                          plan（编排间隔）/ forbid（安全红线：只读不可改）
        // GET /abilitiesselftest → **纯静态**自检（红线拒绝、清单自洽、实现齐备）
        //   ★ 用户 2026-10-11 要"AI 完全接管 + 保证安全"，前提是**边界可见**：
        //     把权限矩阵做成一个可查询的只读端点，而不是只写在注释里。
        //   ★ 只读、不吃 Context ⇒ 屏保期也可查（"它现在能干什么"不用先叫醒它）。
        if ("GET /abilities".equals(act)) return Abilities.json();
        // ================= 简易 workflow（v203）=================
        // GET  /workflow                     → 最近一次执行的流水 + 是否在跑
        // GET  /workflow?list=1              → 设备上已保存的流程列表
        // GET  /workflow?read=名字           → 读一份流程原文
        // POST /workflow {"text":"…"}        → 预检并**同步**执行（网页/脚本用；会等它跑完）
        // POST /workflow {"text":"…","async":true} → 预检后**后台**跑（不占 HTTP 线程）
        // POST /workflow {"save":"名字","text":"…"} → 保存（写入前必预检，不合法整份拒绝）
        // POST /workflow {"del":"名字"}      → 删除
        // POST /workflow {"rollback":true,"volume":5,"face":happy1} → 回滚（如实报告能回滚什么）
        //   ★ 步骤白名单见 Workflow.ALLOWED；破坏性 op 被**明确拒绝**（见 Workflow.FORBIDDEN）。
        //   ★ 一步失败即**中止整条**（不硬着头皮往下跑），执行流水单独落盘到
        //     /sdcard/ledi_workflow_run.log。
        if ("GET /workflow".equals(act) || "POST /workflow".equals(act)) {
            String list = field(body, query, "list");
            String read = field(body, query, "read");
            String del = field(body, query, "del");
            String save = field(body, query, "save");
            String text = field(body, query, "text");
            String rb = field(body, query, "rollback");
            String async = field(body, query, "async");
            if (list.length() > 0) {
                return "{\"ok\":true,\"dir\":\"" + J.esc(Workflow.DIR)
                        + "\",\"items\":" + Workflow.listJson()
                        + ",\"busy\":" + Workflow.busy() + "}";
            }
            if (read.length() > 0) {
                String body2 = Workflow.read(read);
                if (body2 == null) return "{\"ok\":false,\"error\":\"没有这份流程：" + J.esc(read) + "\"}";
                return "{\"ok\":true,\"name\":\"" + J.esc(read) + "\",\"text\":\"" + J.esc(body2) + "\"}";
            }
            if (del.length() > 0) return Workflow.delete(del);
            if (rb.length() > 0) {
                int pv = intOr(field(body, query, "volume"), -1);
                return Workflow.rollback(pv, field(body, query, "face"));
            }
            if (save.length() > 0) {
                if (text.isEmpty()) return "{\"ok\":false,\"error\":\"save 需要 text\"}";
                return Workflow.save(save, text);
            }
            if (!text.isEmpty()) {
                // ★ 必须先 normalizeSteps 再 parse：网页/PC 端也喜欢把多步写成一行
                // （`battery; motion hug; say 你好`）。漏了这一步 ⇒ parse 只认换行 ⇒
                // **静默只跑第 1 步**（真机实测 2026-10-10）。入口处统一还原，别让两条路分叉。
                String body3 = Workflow.normalizeSteps(text);
                Workflow.Script sc = Workflow.parse(body3, "web-" + System.currentTimeMillis());
                if (!sc.ok()) {
                    // ★ 预检不过 ⇒ **一步都没执行**。如实说清楚，不许含糊。
                    return "{\"ok\":false,\"stage\":\"precheck\",\"error\":\"" + J.esc(sc.error) + "\"}";
                }
                if (async.length() > 0 && ("true".equalsIgnoreCase(async) || "1".equals(async))) {
                    boolean started = Workflow.start(sc, Thinking.deviceToolBox());
                    if (!started) return "{\"ok\":false,\"error\":\"已经有一条 workflow 在跑（同时只允许一条）\"}";
                    return "{\"ok\":true,\"async\":true,\"steps\":" + sc.steps.size()
                            + ",\"hint\":\"GET /workflow 看流水\"}";
                }
                Workflow.RunResult r = Workflow.exec(sc, Thinking.deviceToolBox(), null);
                return "{\"ok\":" + r.ok + ",\"aborted\":" + r.aborted
                        + ",\"failed_at\":" + r.failedAt + ",\"ms\":" + r.ms
                        + ",\"error\":\"" + J.esc(r.error) + "\""
                        + ",\"last\":" + Workflow.lastJson() + "}";
            }
            return "{\"ok\":true,\"last\":" + Workflow.lastJson()
                    + ",\"busy\":" + Workflow.busy()
                    + ",\"dir\":\"" + J.esc(Workflow.DIR) + "\""
                    + ",\"allowed\":\"" + J.esc(Thinking.joinArr(Workflow.ALLOWED)) + "\""
                    + ",\"forbidden\":\"" + J.esc(Thinking.joinArr(Workflow.FORBIDDEN)) + "\""
                    + ",\"hint\":\"POST /workflow {\\\"text\\\":\\\"battery\\\\nmotion hug\\\\nsay 你好\\\"}\"}";
        }
        // ================= 多轮思考（v202）=================
        // GET  /think?goal=...&rounds=3     跑一次多轮思考（模型可先要状态/让机器人做动作，再回答）
        // POST /think {"goal":"...","rounds":3}
        // POST /think {"think_on":true|false}   只切"语音回复是否走多轮思考"（不跑）
        //   ★ 这是**独立于语音链路**的入口：语音那条在 AgentService.replyForAI 里
        //     按 thinkOn 分流，这里让"网页/脚本"能直接驱动同一台引擎（同一套 Thinking.run）。
        //   ★ 模型协议：它输出的每一行要么是 `TOOL 名 参数`（执行后结果喂回去继续想），
        //     要么是 `FINAL 最终话`（就结束）。白名单工具见 Thinking.deviceToolBox()
        //     —— **不含任何破坏性动作**（关机/重启/关ADB/关网关都不在里面）。
        //   ★ 失败一律**降级不抛**：返回 ok:false + trace，调用方自己决定怎么办。
        if ("GET /think".equals(act) || "POST /think".equals(act)) {
            String goal = field(body, query, "goal");
            String thOn = field(body, query, "think_on");
            if (thOn.length() > 0)
                AgentService.persistThinkOn("true".equalsIgnoreCase(thOn) || "1".equals(thOn));
            if (goal.length() > 0) {
                if (ai == null) return "{\"ok\":false,\"error\":\"ai not ready\"}";
                int rounds = Thinking.DEFAULT_ROUNDS;
                String rs = field(body, query, "rounds");
                if (rs.length() > 0) rounds = intOr(rs, Thinking.DEFAULT_ROUNDS);
                java.util.List<String> trace = new java.util.ArrayList<String>();
                long t0 = System.currentTimeMillis();
                String finalText = ai.think(goal, rounds, Thinking.deviceToolBox(), trace);
                boolean ok = finalText != null && !finalText.isEmpty();
                return "{\"ok\":" + ok
                        + ",\"goal\":\"" + J.esc(goal) + "\""
                        + ",\"final\":\"" + J.esc(ok ? finalText : "") + "\""
                        + ",\"rounds\":" + rounds
                        + ",\"trace\":" + jsonList(trace)
                        + ",\"ms\":" + (System.currentTimeMillis() - t0)
                        + ",\"error\":\"" + J.esc(ok ? "" : ai.lastError()) + "\""
                        + ",\"think_on\":" + AgentService.thinkOn + "}";
            }
            return "{\"ok\":true,\"think_on\":" + AgentService.thinkOn
                    + ",\"hint\":\"POST /think {\\\"goal\\\":\\\"...\\\",\\\"rounds\\\":3}\"}";
        }
        // ================= 离线意图解析（v94） =================
        // GET  /intent                     词表镜像 / 开关 / 上一句走了哪条路
        // POST /intent {"test":"讲个故事"}   只解析**不执行**：命中本地直通会回 local_reply，
        //                                   否则回 near（易混候选）与给模型的 hint。
        //                                   ★ 这是远程验证纠错效果的主力 —— 不用在机器人旁边喊话
        //                                     就能看到"这句话会被怎么处理"。
        // POST /intent {"on":false}        关掉本地直通（全部交 AI）
        // POST /intent {"reload":true}     重读词表（换过 /sdcard/ledi_grammar.bsg 之后）
        if ("GET /intent".equals(act)) return IntentResolver.statusJson();
        if ("POST /intent".equals(act)) {
            String on = field(body, query, "on");
            if ("true".equals(on) || "false".equals(on)) {
                IntentResolver.setLocalEnabled("true".equals(on));
            }
            if ("true".equals(field(body, query, "reload"))) IntentResolver.reload();
            String test = field(body, query, "test");
            if (!test.isEmpty()) return IntentResolver.probe(test);
            return IntentResolver.statusJson();
        }
        return null;   // TABLE 已保证本域不认这个 act（正常不会发生）
    }

    /** 按域派发。返回 null = 该域不认这个 act。 */
    private String dispatch(String dom, String act, String query, String body)
            throws Exception {
        if ("probe".equals(dom)) return probeRoutes(act, query, body);
        if ("sense".equals(dom)) return senseRoutes(act, query, body);
        if ("voice".equals(dom)) return voiceRoutes(act, query, body);
        if ("motion".equals(dom)) return motionRoutes(act, query, body);
        if ("media".equals(dom)) return mediaRoutes(act, query, body);
        if ("sys".equals(dom)) return sysRoutes(act, query, body);
        if ("sdk".equals(dom)) return sdkRoutes(act, query, body);
        if ("cap".equals(dom)) return capRoutes(act, query, body);
        return null;
    }

    /** 无此端点。措辞与原 handle 兜底逐字一致。 */
    private static String noRoute(String act) {
        return "{\"error\":\"no route " + J.esc(act) + "\"}";
    }

    /**
     * 端点 → 域 的静态表（v221）。**唯一真源**：加端点必须同时在这里登记。
     * 完整性判据：键集必须 == 源码里出现的（非 /console/）端点集。
     */
    private static final java.util.Map<String, String> TABLE =
            new java.util.HashMap<String, String>();
    static {
        // ---- probe（27 条）----
        TABLE.put("GET /abilitiesselftest", "probe");
        TABLE.put("GET /attitudetest", "probe");
        TABLE.put("GET /bootlog", "probe");
        TABLE.put("GET /capsselftest", "probe");
        TABLE.put("GET /emotionselftest", "probe");
        TABLE.put("GET /kws", "probe");
        TABLE.put("GET /ledselftest", "probe");
        TABLE.put("GET /logwin", "probe");
        TABLE.put("GET /memoryselftest", "probe");
        TABLE.put("GET /miclist", "probe");
        TABLE.put("GET /motordiag", "probe");
        TABLE.put("GET /poseread", "probe");
        TABLE.put("GET /recadir", "probe");
        TABLE.put("GET /thinkselftest", "probe");
        TABLE.put("GET /toneselftest", "probe");
        TABLE.put("GET /workflowselftest", "probe");
        TABLE.put("POST /asrtest", "probe");
        TABLE.put("POST /crashprobe", "probe");
        TABLE.put("POST /iatest", "probe");
        TABLE.put("POST /kws/clear", "probe");
        TABLE.put("POST /kws/probe", "probe");
        TABLE.put("POST /kws/test", "probe");
        TABLE.put("POST /kws/train", "probe");
        TABLE.put("POST /logwin", "probe");
        TABLE.put("POST /recaudio", "probe");
        TABLE.put("POST /touchtest", "probe");
        TABLE.put("POST /waketest", "probe");
        // ---- sense（22 条）----
        TABLE.put("GET /battery", "sense");
        TABLE.put("GET /doa", "sense");
        TABLE.put("GET /faces", "sense");
        TABLE.put("GET /faces_detail", "sense");
        TABLE.put("GET /headkey", "sense");
        TABLE.put("GET /live", "sense");
        TABLE.put("GET /online", "sense");
        TABLE.put("GET /pad", "sense");
        TABLE.put("GET /presence", "sense");
        TABLE.put("GET /sensors", "sense");
        TABLE.put("GET /sensorsummary", "sense");
        TABLE.put("POST /doa", "sense");
        TABLE.put("POST /face", "sense");
        TABLE.put("POST /faceoff", "sense");
        TABLE.put("POST /facesound", "sense");
        TABLE.put("POST /headkey", "sense");
        TABLE.put("POST /img", "sense");
        TABLE.put("POST /live", "sense");
        TABLE.put("POST /online", "sense");
        TABLE.put("POST /pad", "sense");
        TABLE.put("POST /presence", "sense");
        TABLE.put("POST /sensorquery", "sense");
        // ---- voice（38 条）----
        TABLE.put("GET /ai", "voice");
        TABLE.put("GET /ai/status", "voice");
        TABLE.put("GET /asrdialog", "voice");
        TABLE.put("GET /dialog", "voice");
        TABLE.put("GET /ia", "voice");
        TABLE.put("GET /listen", "voice");
        TABLE.put("GET /listenface", "voice");
        TABLE.put("GET /listenled", "voice");
        TABLE.put("GET /ptalk", "voice");
        TABLE.put("GET /talk", "voice");
        TABLE.put("GET /talk/status", "voice");
        TABLE.put("GET /ttsstatus", "voice");
        TABLE.put("GET /vocab", "voice");
        TABLE.put("GET /volume", "voice");
        TABLE.put("GET /wakestatus", "voice");
        TABLE.put("POST /ai/ask", "voice");
        TABLE.put("POST /ai/config", "voice");
        TABLE.put("POST /asrdialog", "voice");
        TABLE.put("POST /dialog", "voice");
        TABLE.put("POST /dialogturn", "voice");
        TABLE.put("POST /forcewake", "voice");
        TABLE.put("POST /ia", "voice");
        TABLE.put("POST /ilisten", "voice");
        TABLE.put("POST /intentrun", "voice");
        TABLE.put("POST /listen", "voice");
        TABLE.put("POST /listenface", "voice");
        TABLE.put("POST /listenled", "voice");
        TABLE.put("POST /media", "voice");
        TABLE.put("POST /media/stop", "voice");
        TABLE.put("POST /mic", "voice");
        TABLE.put("POST /ptalk", "voice");
        TABLE.put("POST /say", "voice");
        TABLE.put("POST /talk", "voice");
        TABLE.put("POST /tts", "voice");
        TABLE.put("POST /ttsstop", "voice");
        TABLE.put("POST /vocab", "voice");
        TABLE.put("POST /volume", "voice");
        TABLE.put("POST /wake", "voice");
        // ---- motion（21 条）----
        TABLE.put("GET /motionconfig", "motion");
        TABLE.put("GET /motions", "motion");
        TABLE.put("GET /motions_all", "motion");
        TABLE.put("GET /motions_dance", "motion");
        TABLE.put("GET /motorpos", "motion");
        TABLE.put("GET /motorrelease", "motion");
        TABLE.put("GET /pan", "motion");
        TABLE.put("GET /poses", "motion");
        TABLE.put("POST /base", "motion");
        TABLE.put("POST /dance", "motion");
        TABLE.put("POST /gsensorcali", "motion");
        TABLE.put("POST /light", "motion");
        TABLE.put("POST /motion", "motion");
        TABLE.put("POST /motionconfig", "motion");
        TABLE.put("POST /motor", "motion");
        TABLE.put("POST /motorrelease", "motion");
        TABLE.put("POST /pan", "motion");
        TABLE.put("POST /pose", "motion");
        TABLE.put("POST /posedel", "motion");
        TABLE.put("POST /poserun", "motion");
        TABLE.put("POST /stop", "motion");
        // ---- media（9 条）----
        TABLE.put("GET /home", "media");
        TABLE.put("GET /rotation", "media");
        TABLE.put("POST /config", "media");
        TABLE.put("POST /display", "media");
        TABLE.put("POST /displayoff", "media");
        TABLE.put("POST /exclusive", "media");
        TABLE.put("POST /gear", "media");
        TABLE.put("POST /rotation", "media");
        TABLE.put("POST /sethome", "media");
        // ---- sys（18 条）----
        TABLE.put("GET /adbwifi", "sys");
        TABLE.put("GET /bt", "sys");
        TABLE.put("GET /freezer", "sys");
        TABLE.put("GET /health", "sys");
        TABLE.put("GET /revive", "sys");
        TABLE.put("GET /shell", "sys");
        TABLE.put("GET /status", "sys");
        TABLE.put("GET /switch", "sys");
        TABLE.put("POST /adbwifi", "sys");
        TABLE.put("POST /bt", "sys");
        TABLE.put("POST /freeze", "sys");
        TABLE.put("POST /kill", "sys");
        TABLE.put("POST /name", "sys");
        TABLE.put("POST /revive", "sys");
        TABLE.put("POST /shell", "sys");
        TABLE.put("POST /switch", "sys");
        TABLE.put("POST /thaw", "sys");
        TABLE.put("POST /wifi", "sys");
        // ---- sdk（14 条）----
        TABLE.put("GET /sdk/caps", "sdk");
        TABLE.put("GET /sdk/events", "sdk");
        TABLE.put("GET /sdk/stat", "sdk");
        TABLE.put("POST /sdk/bpm", "sdk");
        TABLE.put("POST /sdk/dance", "sdk");
        TABLE.put("POST /sdk/flag", "sdk");
        TABLE.put("POST /sdk/forceready", "sdk");
        TABLE.put("POST /sdk/led", "sdk");
        TABLE.put("POST /sdk/listen", "sdk");
        TABLE.put("POST /sdk/release", "sdk");
        TABLE.put("POST /sdk/run", "sdk");
        TABLE.put("POST /sdk/series", "sdk");
        TABLE.put("POST /sdk/stop", "sdk");
        TABLE.put("POST /sdk/sys", "sdk");
        // ---- cap（14 条）----
        TABLE.put("GET /abilities", "cap");
        TABLE.put("GET /caps", "cap");
        TABLE.put("GET /emotion", "cap");
        TABLE.put("GET /intent", "cap");
        TABLE.put("GET /memory", "cap");
        TABLE.put("GET /think", "cap");
        TABLE.put("GET /tone", "cap");
        TABLE.put("GET /workflow", "cap");
        TABLE.put("POST /emotion", "cap");
        TABLE.put("POST /intent", "cap");
        TABLE.put("POST /memory", "cap");
        TABLE.put("POST /think", "cap");
        TABLE.put("POST /tone", "cap");
        TABLE.put("POST /workflow", "cap");
    }

    /**
     * 灯光控制：发原厂系统广播。
     * 动作串来自原包逆向（xldbasesdk/b/a.a..m）：
     *   com.auric.intell.xld.os.led.{r|g|b|y|p}.{on|off}
     *   com.auric.intell.xld.os.led.r.blink.fast
     *   com.auric.intell.xld.os.led.b.blink.slow
     *   com.auric.intell.xld.os.led.all.off
     * 颜色 5 种 + all（全部）；动作 on/off/blink.fast/blink.slow 对任一颜色都合法，
     * all 同样支持 on（全亮）。引擎端收不到就说明该 ROM 的 LED 域没监听这条广播。
     */
    String light(String color, String action) {
        if (color.isEmpty()) color = "all";
        if (action.isEmpty()) action = "on";
        if (!color.matches("(r|g|b|y|p|all)"))
            return "bad color: " + color;
        if (!action.matches("(on|off|blink\\.fast|blink\\.slow)"))
            return "bad action: " + action;
        String act = "com.auric.intell.xld.os.led." + color + "." + action;
        ctx.sendBroadcast(new Intent(act));
        lastLight = act;
        return "broadcast: " + act;
    }

    private volatile String lastLight = "";

    public String lastLight() { return lastLight; }

    /**
     * 说话：engine=auto|local|gateway
     *   local   —— 机器人自带语音合成（讯飞/系统 TTS）【newcore 默认，不再用外部语音】
     *   gateway —— 电脑端合成 mp3 再由机器人播放（保留但不再作为默认兜底）
     *   auto    —— 同 local（newcore 起语音统一改回机器人自带，见需求③）
     */
    String say(String text, String url, String engine) {
        if (text == null) text = "";
        if (text.trim().isEmpty() && (url == null || url.isEmpty()))
            return "{\"error\":\"text required\"}";
        String eng = engine == null ? "auto" : engine.trim().toLowerCase();
        String used = "";

        // newcore：语音统一走机器人自带语音合成，不再默认回退网关 edge-tts
        if ("gateway".equals(eng)) {
            if (url != null && !url.isEmpty()) {
                speaker.speak(text, url);
            } else {
                speaker.speak(text, null);
            }
            used = TtsController.ENGINE_GATEWAY;
        } else {
            used = tts.speak(text, true);
            if (used.isEmpty()) {
                return "{\"engine\":\"\",\"ok\":false,\"result\":\"本地语音合成不可用\""
                        + ",\"status\":" + tts.status() + "}";
            }
        }
        return "{\"engine\":\"" + J.esc(used) + "\",\"ok\":true,\"text\":\""
                + J.esc(abbrev(text, 60)) + "\"}";
    }

    /**
     * 解析 AI 回复里的 [表情:x] [动作:x] [灯光:x] 标签并执行，返回剥离标签后的纯文本。
     *
     * 人格提示词里让 AI 用这些标签附带指令（见 AiCore.askDirect），这里把它们
     * 「翻译」成真实的机器人动作/表情/灯光，让 AI 不只是说话，还能动起来。
     * 标签不区分中英文键（表情/动作/灯光 或 face/action/light 都认），值允许空白。
     */
    String applyAiTags(String reply) {
        if (reply == null) return "";
        // ★★★ v180：活泼兜底 —— 回复里没有 [表情:] 时补一个，免得"说话时脸是死的"。
        //   刻意放在**这里**而不是各调用点：本方法就是"回复 → 标签执行"的**唯一咽喉**
        //   （对话 / 常识别 / 电脑端点名 / 主动说话四条路都汇到这），
        //   挂一处即全覆盖。散到调用点去改，就是"改了三处漏了第四处"的老剧本
        //   —— 本项目在"修在一个地方 ≠ 修好了"上吃过太多次。
        reply = AgentService.livelyFallbackFace(reply);
        StringBuilder out = new StringBuilder();
        StringBuilder tag = null;         // 正在累积的标签名（在 [ 和 : 之间）
        StringBuilder val = null;         // 正在累积的标签值（在 : 和 ] 之间）
        for (int i = 0; i < reply.length(); i++) {
            char c = reply.charAt(i);
            if (tag != null) {
                if (c == ':') { val = new StringBuilder(); continue; }
                if (c == ']') {
                    doTag(tag.toString(), val == null ? "" : val.toString());
                    tag = null; val = null;
                    continue;
                }
                if (val != null) val.append(c); else tag.append(c);
            } else if (c == '[') {
                tag = new StringBuilder();
            } else {
                out.append(c);
            }
        }
        // 没闭合的 [ 残段：按普通文本吐回，别丢字
        if (tag != null) {
            out.append('[').append(tag);
            if (val != null) out.append(':').append(val);
        }
        return out.toString().trim();
    }

    /** 执行单个 AI 指令标签（表情/动作/灯光）。 */
    private void doTag(String rawKey, String rawVal) {
        String k = rawKey == null ? "" : rawKey.trim();
        String v = rawVal == null ? "" : rawVal.trim();
        if (v.isEmpty()) return;
        try {
            if ("表情".equals(k) || "face".equalsIgnoreCase(k)) {
                face.play(normFace(v), false);
            } else if ("动作".equals(k) || "action".equalsIgnoreCase(k)
                    || "motion".equalsIgnoreCase(k)) {
                motion.playByName(normMotion(v));
            } else if ("灯光".equals(k) || "light".equalsIgnoreCase(k)) {
                if (sdk != null) sdk.led(normColor(v), null);
            }
        } catch (Throwable ignored) {
            // 单个标签失败不影响整体
        }
    }

    /**
     * AI 中文语义 → 原厂表情名。AI 自由发挥（开心/难过/生气…）也能对上 res/raw 真实视频。
     */
    private String normFace(String v) {
        String s = v == null ? "" : v.trim();
        String[] zh = {"开心", "高兴", "笑", "哈哈", "难过", "伤心", "委屈", "生气", "愤怒",
                "惊讶", "吃惊", "好奇", "疑惑", "害羞", "可爱", "爱", "喜欢", "睡觉", "困",
                "累", "害怕", "恐惧", "哭", "再见", "拜拜", "你好", "嗨"};
        String[] en = {"happy", "enjoy", "smile", "laugh", "sad", "wronged", "wronged",
                "angry", "rage", "shock", "shock", "curious", "curious", "shy", "cute",
                "adore", "adore", "eyes_close", "drowsy", "boring", "scared", "scared",
                "cry", "bye", "bye", "hello", "hello"};
        for (int i = 0; i < zh.length; i++) {
            if (s.contains(zh[i])) return en[i];
        }
        return s; // 英文/原厂名原样走 normalize
    }

    /**
     * AI 中文语义 → 原厂动作名。映射不到时返回原名（走 playByName 的 dance/motion 分流）。
     */
    private String normMotion(String v) {
        String s = v == null ? "" : v.trim();
        String[][] map = {
                {"跳舞", "dance_L5M0R5"}, {"舞蹈", "dance_L5M0R5"}, {"转圈", "turnRight"},
                {"旋转", "turnRight"}, {"挥手", "hiRighthand"}, {"招手", "hiRighthand"},
                {"你好", "hiRighthand"}, {"打招呼", "hiRighthand"}, {"握手", "handshake"},
                {"拥抱", "hug"}, {"抱抱", "hug"}, {"生气", "angry"}, {"愤怒", "angry"},
                {"开心", "cheer"}, {"欢呼", "cheer"}, {"加油", "comeon"}, {"前进", "walk"},
                {"走", "walk"}, {"后退", "stWalk"}, {"跑", "run"}, {"站", "stStand"},
                {"立正", "stStand"}, {"睡觉", "stStand"}, {"醒来", "wake"}, {"起床", "wake"},
                {"摇头", "reject"}, {"拒绝", "reject"}, {"晕", "dizzy"}, {"头晕", "dizzy"},
                {"左转", "turnLeft"}, {"右转", "turnRight"}, {"敬礼", "trafficCommand"},
        };
        for (String[] kv : map) {
            if (s.contains(kv[0])) return kv[1];
        }
        return s;
    }

    /**
     * AI 中文/英文颜色 → 原厂灯光码（r/g/b/y/p/all）。认不出返回 ""（led 会默认 all）。
     */
    private String normColor(String v) {
        String s = v == null ? "" : v.trim();
        if (s.isEmpty()) return "";
        String[][] map = {
                {"红", "r"}, {"red", "r"}, {"绿", "g"}, {"green", "g"},
                {"蓝", "b"}, {"blue", "b"}, {"黄", "y"}, {"yellow", "y"},
                {"紫", "p"}, {"purple", "p"}, {"粉", "p"}, {"pink", "p"},
                {"白", "all"}, {"white", "all"}, {"彩", "all"}, {"rainbow", "all"},
        };
        for (String[] kv : map) {
            if (s.contains(kv[0])) return kv[1];
        }
        // 已是单字符码（r/g/b/y/p）直接用
        if (s.matches("[rgbyp]")) return s;
        return "";
    }

    /**
     * 从一段 JSON 文本里取某个字符串键的值（不带引号）。
     * 注意：不能用 detail.indexOf('"', i + 15) 那种写法 —— 空值 "key":"" 时会把
     * 下一个字段的开头（",）当成值取出来，于是 fallback_reason 变成 "\"," 这种鬼东西。
     * 这里改成：值起点 = i + key.length() + 4（"key":"），并跳过 \" \\ 转义。
     */
    static String jsonStr(String s, String key) {
        if (s == null) return "";
        String pat = "\"" + key + "\":\"";
        int i = s.indexOf(pat);
        if (i < 0) return "";
        int j = i + pat.length();
        int k = j;
        while (k < s.length()) {
            char c = s.charAt(k);
            if (c == '\\') { k += 2; continue; }
            if (c == '"') break;
            k++;
        }
        return j >= s.length() ? "" : J.unescape(s.substring(j, Math.min(k, s.length())));
    }

    /** 当前可用的本地 TTS 引擎名（status 里拿，供 /status 汇总） */
    String ttsEngine() {
        String s = tts.status();
        if (s.contains("\"engine\":\"iflytek\"")) return "iflytek";
        if (s.contains("\"engine\":\"system\"")) return "system";
        return "";
    }

    static String abbrev(String s, int n) {
        if (s == null) return "";
        if (n <= 0) return s;
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    // ================= 动作清单 =================

    /** 全部动作：38 普通 + 42 舞曲（与 APK assets/motions 一一对应） */
    static List<String> allMotions() {
        List<String> l = toList(MotionController.MOTIONS);
        l.addAll(toList(MotionController.DANCE));
        return l;
    }

    // ================= 动作启用配置 =================

    static List<String> toList(String[] arr) {
        List<String> l = new ArrayList<String>();
        for (String s : arr) l.add(s);
        return l;
    }

    /** 启用的动作（未配置 = 全部） */
    public List<String> enabledMotions() {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String csv = sp.getString("motions_enabled", "");
        List<String> out = new ArrayList<String>();
        if (csv.isEmpty()) return allMotions();
        for (String m : csv.split(",")) {
            if (MotionController.isValidMotion(m.trim())) out.add(m.trim());
        }
        return out.isEmpty() ? allMotions() : out;
    }

    static String jsonList(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (String s : list) sb.append("\"").append(J.esc(s)).append("\",");
        if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        return sb.append("]").toString();
    }

    // ==================================================================
    // 网页控制台兼容层（v192）
    // ==================================================================

    /**
     * 就地把「电脑端乐迪控制器」那份网页控制台要用的 {@code /console/*} 接口实现一遍，
     * 使**同一份页面**可以直接由机器人自己吐出来：浏览器打开
     * {@code http://<机器人IP>:8899/} 就是同一个控制台，不必再跑 exe、不必再起 PC 网关。
     *
     * <p>为什么不重写一份页面：那会变成两份必须同步维护的 UI（本项目铁律：各写一份必分叉）。
     * 这里只补**页面启动与调用必需的那几个接口**，其余动作全部原样落回机器人自己的路由
     * （见下面 {@code agent} 分支里的 {@link #handle} 递归），页面里的按钮一个都不用改。</p>
     *
     * <p>★ 电脑端专属的那几项（大模型 Key / 网页版 DeepSeek / 随机心跳 / 局域网自动发现 /
     * 蓝牙隧道 / 退出应用 / 重启关机）**在机器人上没有对应物**，一律返回
     * {@code ok:false} 并写明原因。不假装成功 —— 假装成功就是又造一条假绿灯，
     * 而这里恰恰最容易被"返个空对象"糊过去。</p>
     */
    private String consoleCompat(String method, String route, String query, String body) {
        String act = method + " " + route;

        // ---- 版本 / 监听信息：页面顶栏那一行 ----
        if ("GET /console/appinfo".equals(act)) {
            return "{\"app\":false,\"on_robot\":true,\"can_quit\":false"
                    + ",\"lan_ip\":\"" + J.esc(HttpServer.localIp()) + "\""
                    + ",\"port\":" + AgentService.HTTP_PORT
                    + ",\"version\":\"" + J.esc(appVersion(ctx)) + "\"}";
        }

        // ---- 配置：页面的启动依赖，缺了整页只显示一行错误 ----
        // 页面从 CFG 里取"动作/舞蹈/表情/预设姿态"的全部清单，所以这几个字段必须真。
        if ("GET /console/config".equals(act) || "POST /console/config".equals(act)) {
            List<String> normal = new ArrayList<String>();
            List<String> dance = new ArrayList<String>();
            // ★ 必须遍历全部 80 个（38 普通 + 42 舞曲）。只遍历 MotionController.MOTIONS
            // 会把 42 个 dance_* 全漏掉（MOTIONS 里没有舞曲，舞曲在 DANCE 数组里），
            // 页面就会显示「舞蹈(0)」—— 实测 v192 首版即此坑。
            List<String> all = allMotions();
            for (String m : all) {
                if (MotionController.isDance(m)) dance.add(m); else normal.add(m);
            }
            List<String> faces = FaceController.listFaces();
            return "{\"ok\":true,\"on_robot\":true,\"mode\":\"robot\""
                    + ",\"version\":\"" + J.esc(appVersion(ctx)) + "\""
                    + ",\"agent\":{\"addr\":\"" + J.esc(HttpServer.localIp()) + ":"
                    + AgentService.HTTP_PORT + "\",\"enabled\":true}"
                    + ",\"llm\":{\"base\":\"\",\"model\":\"\"}"
                    + ",\"brain\":{\"mode\":\"robot\"}"
                    + ",\"discover\":{\"enabled\":false}"
                    + ",\"heartbeat\":{\"on\":false}"
                    + ",\"tts\":{\"robot_engine\":\"auto\"}"
                    + ",\"faces\":" + jsonList(faces) + ",\"faces_source\":\"robot\""
                    + ",\"motions\":" + jsonList(all)
                    + ",\"motions_normal\":" + jsonList(normal)
                    + ",\"motions_dance\":" + jsonList(dance)
                    + ",\"presets\":" + handle("GET", "/poses", "", "")
                    + ",\"counts\":{\"motions\":" + all.size()
                    + ",\"normal\":" + normal.size() + ",\"dance\":" + dance.size()
                    + ",\"faces\":" + faces.size() + "}}";
        }

        // ---- 万能转发：页面里绝大多数按钮都走这条 ----
        // 页面发 {path:"/motion", body:{...}}；老协议也可能发 "b":"{...}" 字符串形式。
        if ("GET /console/agent".equals(act) || "POST /console/agent".equals(act)) {
            String path = field(body, query, "path");
            if (path.isEmpty()) return "{\"error\":\"path required\"}";
            if ("GET".equals(method)) return handle("GET", path, "", "");
            String inner = rawObject(body, "body");
            if (inner.isEmpty()) inner = field(body, query, "body");
            if (inner.isEmpty()) inner = field(body, query, "b");
            return handle("POST", path, "", inner);
        }

        // ---- 传感器 / 状态：页面⑩区 ----
        // sensors 直接内联 /sensors 的原样 JSON（它就是页面 fmtState 要的那个结构），
        // summary 取 SensorHub 自己的那句总结。
        if ("GET /console/state".equals(act) || "POST /console/state".equals(act)) {
            String sensors = handle("GET", "/sensors", "", "");
            String sum = "";
            SensorHub sh = sensor;
            if (sh != null) {
                try { sum = sh.summary(); } catch (Throwable ignored) {}
            }
            return "{\"sensors\":" + sensors
                    + ",\"summary\":\"" + J.esc(sum == null ? "" : sum) + "\""
                    + ",\"events\":[]"
                    + ",\"last_user_text\":\"\""
                    // 这三个开关是**网关侧**的概念：机器人上 AI 一直在，没有"接管/不接管"。
                    // ai=true 表示"AI 在工作"，native=null 表示"这项在本机不适用"（页面会显示"开"）。
                    + ",\"switches\":{\"ai\":true,\"native\":null}"
                    + ",\"auto_react\":{\"on\":false}"
                    + ",\"heartbeat\":null}";
        }

        // ---- 原厂 SDK：页面⑬区把三个接口一次要齐 ----
        if ("GET /console/sdk".equals(act) || "POST /console/sdk".equals(act)) {
            return "{\"ok\":true"
                    + ",\"stat\":" + handle("GET", "/sdk/stat", "", "")
                    + ",\"events\":" + handle("GET", "/sdk/events", "", "")
                    + ",\"caps\":" + handle("GET", "/sdk/caps", "", "") + "}";
        }

        if ("GET /console/ttsstatus".equals(act) || "POST /console/ttsstatus".equals(act)) {
            return "{\"ok\":true,\"robot\":" + handle("GET", "/ttsstatus", "", "") + "}";
        }
        if ("POST /console/tts".equals(act)) {
            return handle("POST", "/tts", "", body);
        }
        if ("GET /console/rotation".equals(act) || "POST /console/rotation".equals(act)) {
            return "POST".equals(method) ? handle("POST", "/rotation", "", body)
                    : handle("GET", "/rotation", "", "");
        }
        if ("GET /console/doa".equals(act) || "POST /console/doa".equals(act)) {
            return "POST".equals(method) ? handle("POST", "/doa", "", body)
                    : handle("GET", "/doa", "", "");
        }

        // ---- 应急发送：把一段文字交给机器人"念出来 + 配一个表情"----
        // 电脑端那份还会本地挑表情与动作；这里先只保证**说得出**，不伪造没做的事。
        if ("POST /console/emergency".equals(act)) {
            String text = field(body, query, "text");
            if (text.isEmpty()) return "{\"ok\":false,\"error\":\"text required\"}";
            String r = handle("POST", "/tts", "", "{\"text\":\"" + J.esc(text) + "\"}");
            return "{\"ok\":true,\"sent\":{\"text\":\"" + J.esc(text) + "\"},\"result\":" + r + "}";
        }

        // ---- 屏幕显示图片：页面上填的是**电脑上的路径**，机器人读不到 ----
        // 明确回绝并给出去路，别让它看起来"点了没反应"。图片走 POST /img 的 base64。
        if ("POST /console/screenimg".equals(act) || "GET /console/screenimg".equals(act)) {
            return "{\"ok\":false,\"error\":\"机器人读不到电脑上的文件路径\""
                    + ",\"hint\":\"用这一区的「选图片上传」按钮（走 POST /img 的 base64）\"}";
        }

        // ---- 对话日志：那是**网关**把三条通路汇总出来的，机器人端没有这份账 ----
        if ("GET /console/dialog".equals(act)) {
            return "{\"items\":[],\"state\":{\"count\":0,\"kept\":0"
                    + ",\"log\":\"（对话日志是电脑端网关记的；机器人端不保留这份汇总）\"}}";
        }
        if ("POST /console/dialog".equals(act)) {
            return "{\"ok\":false,\"error\":\"对话日志属于电脑端网关，机器人端没有可清的账\"}";
        }

        // ---- 机器人端不认识的 input ----
        if ("GET /console/lastuser".equals(act)) {
            return "{\"ok\":false,\"text\":\"\",\"error\":\"机器人端没有单独缓存'最近听到的话'；"
                    + "看 GET /live 的 last_user_ago_ms 判断它最近有没有听到人说话\"}";
        }

        // ---- 屏幕投屏（v195）：控 + 配置 ----
        //
        // 「看」那三条（GET /mirror.h264 流、/mirror.jpg 单张、/mirror/stat 状态）
        // 在 HttpServer 里、router 之前就接住了 —— 一是它们吐的是**二进制字节流**，
        // 不是 JSON，走不进这个字符串路由；二是它们要在屏保中也能用。
        // 这里只补「控」（把网页上的一次点击送回屏幕）和「配置」（尺寸/码率/注入开关）。
        if ("GET /console/mirror".equals(act) || "POST /console/mirror".equals(act)) {
            ScreenCast cast = ScreenCast.get(ctx);
            if ("POST".equals(method)) {
                String inj = field(body, query, "inject");
                if (inj.length() > 0) {
                    InputInject.setEnabled(ctx, "true".equalsIgnoreCase(inj) || "1".equals(inj));
                }
                String on = field(body, query, "on");
                if (on.length() > 0) {
                    if ("true".equalsIgnoreCase(on) || "1".equals(on)) cast.start();
                    else cast.stop();
                }
                String w = field(body, query, "w"), h = field(body, query, "h");
                String br = field(body, query, "bitrate");
                if (w.length() > 0 || h.length() > 0 || br.length() > 0) {
                    cast.configure(intOr(w, cast.width()), intOr(h, cast.height()),
                            intOr(br, cast.bitrate()));
                }
            }
            return "{\"ok\":true,\"cast\":" + cast.json()
                    + ",\"inject\":" + InputInject.json()
                    + ",\"inject_enabled\":" + InputInject.enabled(ctx) + "}";
        }

        // ---- 投屏的「控」：把网页上的一次点击 / 拖拽 / 按键 / 打字送回机器人屏幕 ----
        // 坐标一律用**机器人屏幕的像素**（和 /mirror.h264 的头 X-Ledi-Width/Height 同一域）；
        // 页面把显示尺寸按比例换算过来，所以浏览器窗口多大都不影响落点。
        if ("POST /console/tap".equals(act) || "POST /console/swipe".equals(act)
                || "POST /console/hold".equals(act)
                || "POST /console/key".equals(act) || "POST /console/text".equals(act)) {
            if (!InputInject.available()) {
                return "{\"ok\":false,\"error\":\"注入通道不可用（反射不到 InputManager）\""
                        + ",\"inject\":" + InputInject.json() + "}";
            }
            if (!InputInject.enabled(ctx)) {
                return "{\"ok\":false,\"error\":\"远程注入已被关闭"
                        + "（POST /console/mirror {\\\"inject\\\":true} 可开）\"}";
            }
            boolean ok;
            String modeNote = "";
            if ("POST /console/tap".equals(act)) {
                ok = InputInject.tap(intOr(field(body, query, "x"), -1),
                        intOr(field(body, query, "y"), -1));
            } else if ("POST /console/swipe".equals(act)) {
                ok = InputInject.swipe(intOr(field(body, query, "x1"), -1),
                        intOr(field(body, query, "y1"), -1),
                        intOr(field(body, query, "x2"), -1),
                        intOr(field(body, query, "y2"), -1),
                        intOr(field(body, query, "ms"), 200));
            } else if ("POST /console/hold".equals(act)) {
                // 长按：x/y 缺省用屏心（和 tap/swipe 的域一致 —— 800x480 的像素）
                ok = InputInject.hold(intOr(field(body, query, "x"), 400),
                        intOr(field(body, query, "y"), 240),
                        intOr(field(body, query, "ms"), 800));
            } else if ("POST /console/key".equals(act)) {
                ok = InputInject.key(keyCode(field(body, query, "code"),
                        field(body, query, "name")));
            } else {
                ok = InputInject.text(field(body, query, "text"));
                // ★ 口径必须写进响应：打字走的是**异步**提交（几十个事件逐个等派发
                //  会把最坏情况放大到分钟级），所以 ok 只代表"事件已交给系统"，
                //   不代表已被应用消费 —— 要看真打上没有，请看投屏画面。
                modeNote = ",\"mode\":\"async\",\"mode_note\":\"异步提交；ok 只表示事件已交给系统，"
                        + "是否真打上请看屏幕（/mirror）\"";
            }
            // 一律回真实结果：injectInputEvent 说 false 就是 false。
            // 这里最容易被"发出去就算成功"糊过去，而那样用户会以为是自己点歪了。
            return "{\"ok\":" + ok + ",\"route\":\"" + J.esc(act) + "\""
                    + modeNote
                    + ",\"inject\":" + InputInject.json() + "}";
        }

        // ---- 电脑端专属：明确回绝，并说明在机器人上该怎么办 ----
        if (route.equals("/console/quit") || route.equals("/console/discover")
                || route.equals("/console/heartbeat") || route.equals("/console/react")
                || route.equals("/console/switch") || route.equals("/console/llmtest")
                || route.equals("/console/webbrain")) {
            return "{\"ok\":false,\"on_robot\":true,\"error\":\""
                    + "这是电脑端「乐迪控制器」的功能（大模型/网页版 DeepSeek/随机心跳/自动发现/"
                    + "蓝牙隧道/退出应用），机器人上没有对应物\",\"route\":\"" + J.esc(route) + "\"}";
        }

        return "{\"ok\":false,\"error\":\"unknown console route\",\"route\":\"" + J.esc(route) + "\"}";
    }

    /**
     * 把网页发来的按键统一成一个 KeyEvent code（v195，投屏的"控"用）。
     *
     * <p>{@code code} 优先（Android 的 KeyEvent 常量值）；给不出数字就用 {@code name} 查表。
     * 表里只放投屏控制真用得上的那几个 —— **每多一个键就是多一个说不清用途的口子**，
     * 比如把 HOME/BACK 放进来是因为"投屏时点不动了，得有条路退出去"，
     * 而 POWER 是因为机器人屏幕本身没有熄屏键。</p>
     */
    private static int keyCode(String code, String name) {
        int c = intOr(code, -1);
        if (c > 0) return c;
        String n = name == null ? "" : name.trim().toLowerCase();
        if (n.equals("home")) return 3;                    // KEYCODE_HOME
        if (n.equals("back")) return 4;                    // KEYCODE_BACK
        if (n.equals("enter") || n.equals("ok")) return 66;
        if (n.equals("menu")) return 82;
        if (n.equals("power")) return 26;                  // KEYCODE_POWER（开关屏）
        if (n.equals("volume_up")) return 24;
        if (n.equals("volume_down")) return 25;
        if (n.equals("up")) return 19;
        if (n.equals("down")) return 20;
        if (n.equals("left")) return 21;
        if (n.equals("right")) return 22;
        if (n.equals("del") || n.equals("backspace")) return 67;
        return -1;
    }

    /**
     * 从 {@code {"a":1,"body":{"x":1}}} 里把 {@code "body"} 的那个**对象原文**抠出来。
     *
     * <p>为什么不用 {@link #field}：它按"字符串值"实现的，遇到对象值只能退化成宽松解析，
     * 拿到的可能是半截。而这里必须**逐字节原样**透传 —— 下游 {@code /motion} 等
     * 要的就是那份 JSON 本身。</p>
     */
    private static String rawObject(String body, String key) {
        if (body == null) return "";
        int i = body.indexOf("\"" + key + "\"");
        while (i >= 0) {
            int c = body.indexOf(':', i + key.length() + 2);
            if (c < 0) return "";
            int s = c + 1;
            while (s < body.length() && Character.isWhitespace(body.charAt(s))) s++;
            if (s < body.length() && body.charAt(s) == '{') {
                int depth = 0;
                for (int k = s; k < body.length(); k++) {
                    char ch = body.charAt(k);
                    if (ch == '{') depth++;
                    else if (ch == '}') {
                        depth--;
                        if (depth == 0) return body.substring(s, k + 1);
                    }
                }
                return "";
            }
            i = body.indexOf("\"" + key + "\"", i + key.length() + 2);
        }
        return "";
    }

    /** 版本号缓存。读不到时**不缓存** —— 别把一次失败钉成永久答案。 */
    private static volatile String verCache = "";

    /**
     * 设备上**真正在跑**的版本号 —— 从 PackageManager 读，不是源码里的常量。
     *
     * <p>★ 为什么非要从 PM 读：本项目栽过"源码改了忘了重打包"的坑，而**光看 HTTP
     * 看不出来**。2026-09-24 装完 v154 想用 {@code /status} 确认版本，才发现当时
     * 压根没这个字段，只能回去敲 {@code adb shell dumpsys package}。现在它是唯一的
     * 版本出口（面板标题也走这里），源码常量与产物不会再有两个口径。
     *
     * <p>读的是 {@code versionName}（本项目的打包流程会把它写成 {@code 1.0-vNNN}），
     * 所以这一格同时就是"重打包到底生效没有"的判据。
     */
    public static String appVersion(Context c) {
        String v = verCache;
        if (!v.isEmpty()) return v;
        try {
            String s = c.getPackageManager()
                    .getPackageInfo(c.getPackageName(), 0).versionName;
            if (s == null || s.isEmpty()) return "?";
            verCache = s;
            return s;
        } catch (Exception e) {
            return "?";
        }
    }
}

