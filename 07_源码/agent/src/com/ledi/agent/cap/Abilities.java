package com.ledi.agent.cap;

import com.ledi.agent.net.AdbWifi;
import com.ledi.agent.core.AgentService;
import com.ledi.agent.voice.Av;
import com.ledi.agent.sense.Battery;
import com.ledi.agent.core.BootLog;
import com.ledi.agent.core.CommandRouter;
import com.ledi.agent.sdk.FaceController;
import com.ledi.agent.net.HttpServer;
import com.ledi.agent.core.J;
import com.ledi.agent.motion.MotionController;
import com.ledi.agent.ui.RotationLock;
import com.ledi.agent.sense.SensorHub;
import com.ledi.agent.ui.SleepMode;
import com.ledi.agent.sense.SoundSourceLocator;
import com.ledi.agent.voice.VoiceWakeController;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本机能力真源（v207）—— 「机器人会什么、哪些只能查、哪些不许改」的唯一一份规格表。
 *
 * <h3>为什么要有这一个类（而不是继续在四处各写一份清单）</h3>
 * v203 之前，同一件事的能力清单散在四个地方：{@link Thinking#deviceToolBox()} 里是
 * "有哪些工具"、{@link Workflow#ALLOWED} 里是"步骤能调哪些"、{@link Workflow#FORBIDDEN}
 * 里是"哪些不许调"、{@link CapIndex} 里是"机器人怎么向用户介绍自己"。
 * 四份清单**必然漂移**（本项目最贵的教训之一：修在一个地方 ≠ 修好了）。
 * 用户 2026-10-11 的要求把这件事推到了必须收敛的程度：
 * <blockquote>
 * 「AI 完全接管这台机器人；除不让改底层设置（wifi / adb / 开关机 可查不可改）外，
 *   其余信息都可以查询、或做出有限的更改。」
 * </blockquote>
 * 于是把三态权限（read / write / forbid）收敛成**一张表**，四处全部从它读。
 *
 * <h3>★ 三态语义（这就是"安全"的落点）</h3>
 * <ul>
 *   <li>{@link #READ}  只读查询 —— 零副作用，随便查（含 wifi / adb / 蓝牙 / 开关机**状态**）。</li>
 *   <li>{@link #WRITE} 有限更改 —— 可逆、不断连、不碰系统底层（音量/表情/动作/灯光/屏幕文字/
 *       情绪/待机活泼度/常听开关/改名/称呼/语气/旋转锁 …）。</li>
 *   <li>{@link #PLAN}  编排与间隔 —— 自身不改硬件（{@code workflow} / {@code wait}）。</li>
 *   <li>{@link #FORBID} 安全红线 —— 会断连（wifi/adb/蓝牙开关）、会失联（重启/关机/深睡/冻结/杀进程）、
 *       或万能到无法审计（shell）。**只读不可改**：想查就用上面的 {@code *_info}。</li>
 * </ul>
 *
 * <h3>★ 两条实现纪律</h3>
 * <p><b>① 边界下沉到执行层。</b>{@link #execute} 自己会拒绝 {@link #FORBID} 的 op ——
 * 不依赖调用方先查清单。这样"绕过清单直接调"也挡得住（本项目在 {@code POST /listen}
 * 上已经吃过"放行入口 = 开后门"的亏：安全判断必须下沉到真正执行的那一层）。</p>
 *
 * <p><b>② 纯 JVM 可跑。</b>自检不碰硬件、不联网、不读 assets —— 设备侧控制器为 null 时
 * 查询项**如实**回"未就绪"，绝不用默认值假装读到了。</p>
 */
public final class Abilities {

    private static final String TAG = AgentService.TAG;

    private Abilities() {}

    // ================================================================ 三态

    /** 只读查询：零副作用。 */
    public static final String READ   = "read";
    /** 有限更改：可逆、不断连、不碰系统底层。 */
    public static final String WRITE  = "write";
    /** 编排/间隔：自身不改硬件。 */
    public static final String PLAN   = "plan";
    /** 安全红线：只读不可改（查询走对应 *_info）。 */
    public static final String FORBID = "forbid";

    /** 一条能力的规格。 */
    public static final class Spec {
        public final String name;    // op 名（模型照抄这个）
        public final String kind;    // read | write | plan | forbid
        public final String desc;    // 一句话说明（人机共读）
        public final String usage;   // 用法串（含参数）
        Spec(String n, String k, String d, String u) { name = n; kind = k; desc = d; usage = u; }
    }

    /**
     * ★★★ 唯一真源。加/删能力只改这里，其余四处（Thinking / Workflow / CapIndex / 路由）自动跟随。
     *
     * <p>命名纪律：**查询用名词（{@code *_info}），修改用动词**。这样"改 wifi"（{@code wifi}）
     * 与"查 wifi"（{@code netinfo}）是**两个不同的名字**，既不会撞车，模型也不会把"查"误当"改"。</p>
     */
    static final Spec[] SPECS = {
        // ---------------- 只读查询 ----------------
        new Spec("state",   READ, "当前状态一句话（电量/屏保/深睡/音量/姿态/触摸/常听）", "state"),
        new Spec("battery", READ, "电量详情（百分比/充电/电压/温度/健康）", "battery"),
        new Spec("time",    READ, "当前日期与时间", "time"),
        new Spec("faces",   READ, "可切换的表情名清单", "faces"),
        new Spec("motions", READ, "可执行的动作名清单", "motions"),
        new Spec("netinfo", READ, "WiFi/网络：SSID、IP、是否联网（只读）", "netinfo"),
        new Spec("adbinfo", READ, "无线调试：5555 是否真在监听、端口（只读）", "adbinfo"),
        new Spec("btinfo",  READ, "蓝牙：开关、本机名、连接状态（只读）", "btinfo"),
        new Spec("screen",  READ, "屏幕：屏保/深睡/旋转锁/当前显示内容", "screen"),
        new Spec("sensors", READ, "传感器：姿态、最近触摸、陀螺仪、头顶键、被摸次数", "sensors"),
        new Spec("sysinfo", READ, "系统：版本、运行时长、内存、存储", "sysinfo"),
        new Spec("pose",    READ, "舵机最近下发值 + 姿态", "pose"),
        new Spec("logs",    READ, "最近的日志尾部（默认 20 行）", "logs [n]"),
        new Spec("micinfo", READ, "麦克风：录音状态、声源方位", "micinfo"),
        new Spec("doc",     READ, "在能力索引里检索（自己会不会做某事/名字叫什么）", "doc <检索词>"),
        new Spec("memory",  READ, "长期记忆：认识第几天、聊过几次、记着哪些事", "memory"),

        // ---------------- 有限更改 ----------------
        new Spec("volume",    WRITE, "音量 0..15", "volume <0-15>"),
        new Spec("face",      WRITE, "切表情", "face <表情名>"),
        new Spec("motion",    WRITE, "做动作（会真的产生力矩）", "motion <动作名>"),
        new Spec("led",       WRITE, "头顶灯", "led <色> <on|off|blink.fast|blink.slow>"),
        new Spec("say",       WRITE, "用机器人自带嗓音念一句话（不额外调模型）", "say <文本>"),
        new Spec("stop",      WRITE, "立即停止当前动作", "stop"),
        new Spec("emotion",   WRITE, "切情绪（原厂词表内的才认；reset=回基线）", "emotion <情绪名|reset>"),
        new Spec("display",   WRITE, "机器人屏幕上显示文字", "display <文本>"),
        new Spec("displayoff",WRITE, "清屏（去掉屏幕上的文字/图片）", "displayoff"),
        new Spec("live",      WRITE, "待机活泼化总开关（呼应/兜底表情/待机微动/待机动作）", "live on|off"),
        new Spec("idle",      WRITE, "待机动作间隔（拍；1 拍≈8s；0=关）", "idle <0-240>"),
        new Spec("listen",    WRITE, "常听（不等唤醒词一直听）开/关", "listen on|off"),
        new Spec("think",     WRITE, "语音回复是否走多轮思考", "think on|off"),
        new Spec("name",      WRITE, "给机器人自己改名", "name <名字>"),
        new Spec("call",      WRITE, "它怎么称呼用户", "call <称呼>"),
        new Spec("tone",      WRITE, "语气档：adult | neutral | child", "tone <档>"),
        new Spec("style",     WRITE, "性格：warm | direct | buddy | custom:<名>", "style <档|custom:名>"),
        new Spec("remember",  WRITE, "让它记住一件事（存机器人本地）", "remember <一句话>"),
        new Spec("forget",    WRITE, "忘记记忆（facts 只忘事 / talk 只忘对话 / all 全忘）", "forget <facts|talk|all>"),
        new Spec("rotation",  WRITE, "屏幕旋转锁：lock（锁横向）/ unlock（放开自动旋转）", "rotation lock|unlock"),
        new Spec("wake",      WRITE, "主动发起一次语音对话", "wake"),

        // ---------------- 编排 / 间隔 ----------------
        new Spec("workflow", PLAN, "一次交出一串步骤（用 ; 或换行分隔，每步 `op 参数`）", "workflow <步骤…>"),
        new Spec("wait",     PLAN, "等待若干毫秒（0..10000）", "wait <ms>"),

        // ---------------- 安全红线：只读不可改 ----------------
        new Spec("wifi",      FORBID, "改 WiFi（配网/开关网卡）—— 只能 netinfo 查，不许改", "—"),
        new Spec("bt",        FORBID, "改蓝牙开关/配对 —— 只能 btinfo 查，不许改", "—"),
        new Spec("adbwifi",   FORBID, "开关无线调试 —— 只能 adbinfo 查，不许改", "—"),
        new Spec("reboot",    FORBID, "重启系统", "—"),
        new Spec("shutdown",  FORBID, "关机", "—"),
        new Spec("poweroff",  FORBID, "关机", "—"),
        new Spec("closeadb",  FORBID, "关闭 adb 通道", "—"),
        new Spec("killsystem",FORBID, "杀掉系统进程", "—"),
        new Spec("uninstall", FORBID, "卸载应用", "—"),
        new Spec("deepsleep", FORBID, "深睡（冻原厂）—— 会失联，只许人工现场做", "—"),
        new Spec("freeze",    FORBID, "冻结进程 —— 会失联", "—"),
        new Spec("kill",      FORBID, "杀进程 —— 会失联", "—"),
        new Spec("shell",     FORBID, "任意 shell 命令 —— 万能到无法审计，不给 AI", "—"),
        new Spec("wipe",      FORBID, "清除数据/恢复出厂", "—"),
    };

    // ================================================================ 清单

    /** 按名字找规格；没有返回 null。 */
    public static Spec find(String name) {
        if (name == null) return null;
        for (Spec s : SPECS) if (s.name.equals(name)) return s;
        return null;
    }

    /**
     * 别名归一。★ 老调用方（v203 的 {@code doc}/{@code caps}、{@code workflow}/{@code run}）
     * 必须继续可用 —— 否则"换个名字就静默失效"正是本项目最忌讳的那类 bug。
     */
    static String canon(String name) {
        String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        if ("caps".equals(n)) return "doc";
        if ("run".equals(n))  return "workflow";
        return n;
    }

    /** 某一种态的全部 op 名（保持表内顺序，便于人读）。 */
    public static String[] namesOf(String kind) {
        List<String> out = new ArrayList<String>();
        for (Spec s : SPECS) if (s.kind.equals(kind)) out.add(s.name);
        return out.toArray(new String[0]);
    }

    /** 允许被 workflow 调用的 op = read + write + plan（即"除红线以外全部"）。 */
    public static String[] allowedOps() {
        List<String> out = new ArrayList<String>();
        for (Spec s : SPECS) if (!FORBID.equals(s.kind)) out.add(s.name);
        return out.toArray(new String[0]);
    }

    /** 安全红线 op。 */
    public static String[] forbiddenOps() { return namesOf(FORBID); }

    /** 该 op 是否属于安全红线。 */
    public static boolean isForbidden(String op) {
        Spec s = find(op);
        return s != null && FORBID.equals(s.kind);
    }

    // ================================================================ 执行（唯一出口）

    /**
     * 执行一个能力。**所有入口都走这里**（模型工具循环 / workflow 步骤 / 网页 / 脚本）。
     *
     * <p>★ 安全红线的拒绝就在这个函数里 —— 不靠调用方先查清单，因此"绕过清单"也挡得住。
     * 这与 {@code POST /listen} 的教训同源：入口放行了，安全判断就必须下沉到执行处。</p>
     */
    public static String execute(String name, String args) {
        String n = canon(name);
        String a = args == null ? "" : args.trim();
        if (n.isEmpty()) return "参数错误：缺少能力名";

        Spec s = find(n);
        // 未知（含拼错、含模型自己发明的名字）——如实拒绝，并给出可用清单
        if (s == null) return "未知能力：" + n + "（可用：" + brief(allowedOps()) + "）";
        // ★ 安全红线：先于任何实现分支拒绝
        if (FORBID.equals(s.kind)) {
            return "「" + n + "」是安全红线，**只读不可改**（" + s.desc + "）。"
                    + "要查询请用对应的只读能力：" + readHintFor(n);
        }
        try {
            if (s.kind.equals(READ))  return execRead(n, a);
            if (s.kind.equals(WRITE)) return execWrite(n, a);
            if (s.kind.equals(PLAN))  return execPlan(n, a);
        } catch (Throwable t) {
            return "工具执行异常：" + t;
        }
        return "未知能力：" + n;
    }

    /** 红线 op → 它对应的只读能力（让模型知道"想查该用哪个"）。 */
    static String readHintFor(String forbidden) {
        if ("wifi".equals(forbidden))    return "netinfo";
        if ("bt".equals(forbidden))      return "btinfo";
        if ("adbwifi".equals(forbidden)) return "adbinfo";
        if ("deepsleep".equals(forbidden) || "freeze".equals(forbidden) || "kill".equals(forbidden))
            return "screen（看屏保/深睡状态）";
        if ("reboot".equals(forbidden) || "shutdown".equals(forbidden) || "poweroff".equals(forbidden))
            return "sysinfo（看运行时长）/ battery";
        return "（没有对应查询项）";
    }

    // ---------------------------------------------------------------- read

    private static String execRead(String n, String a) {
        Context ctx = AgentService.I;
        if ("state".equals(n))   return Thinking.stateBrief();
        if ("battery".equals(n)) return ctx == null ? "battery 未就绪（服务未起）" : Battery.human(ctx);
        if ("time".equals(n))    return Thinking.nowString();
        if ("faces".equals(n))   return Thinking.joinList(FaceController.listFaces(), 60);
        if ("motions".equals(n)) return Thinking.joinArray(Thinking.allMotions(), 90);
        if ("doc".equals(n) || "caps".equals(n)) {
            if (a.isEmpty()) return "参数错误：doc <检索词>。例如 doc 握手 / doc 电量";
            return CapIndex.searchBrief(a, 6);
        }
        if ("netinfo".equals(n))  return netInfo(ctx);
        if ("adbinfo".equals(n))  return adbInfo(ctx);
        if ("btinfo".equals(n))   return btInfo(ctx);
        if ("screen".equals(n))   return screenInfo(ctx);
        if ("sensors".equals(n))  return sensorInfo();
        if ("sysinfo".equals(n))  return sysInfo(ctx);
        if ("pose".equals(n))     return poseInfo();
        if ("logs".equals(n))     return logsTail(a);
        if ("micinfo".equals(n))  return micInfo();
        if ("memory".equals(n))   return Memory.json();
        return "未知查询：" + n;
    }

    private static String netInfo(Context ctx) {
        if (ctx == null) return "netinfo 未就绪（服务未起）";
        String w = "?";
        try {
            CommandRouter r = AgentService.router;
            if (r != null && r.wifi() != null) w = r.wifi().status();
        } catch (Throwable t) { w = "读取失败：" + t; }
        String ip = "";
        try { ip = HttpServer.localIp(); } catch (Throwable ignored) {}
        boolean online = ip != null && !ip.isEmpty() && !"127.0.0.1".equals(ip);
        return "网络：" + w + "；本机 IP=" + (online ? ip : "（无）") + "；联网=" + (online ? "是" : "否");
    }

    private static String adbInfo(Context ctx) {
        if (ctx == null) return "adbinfo 未就绪（服务未起）";
        boolean listen;
        try { listen = AdbWifi.listening(); } catch (Throwable t) { return "adbinfo 读取失败：" + t; }
        String ip = "";
        try { ip = HttpServer.localIp(); } catch (Throwable ignored) {}
        return "无线调试（只读）：5555 " + (listen ? "正在监听" : "未监听")
                + "；端口=" + AdbWifi.PORT
                + (listen && ip != null && !ip.isEmpty() ? "；可用 scrcpy --serial " + ip + ":" + AdbWifi.PORT : "")
                + "。★ 开关它属于安全红线，AI 不改，只能查。";
    }

    private static String btInfo(Context ctx) {
        if (ctx == null) return "btinfo 未就绪（服务未起）";
        boolean on;
        try { on = AgentService.bluetoothOn(); } catch (Throwable t) { return "btinfo 读取失败：" + t; }
        String name = "", mac = "";
        try {
            android.bluetooth.BluetoothAdapter ba = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (ba != null) {
                name = ba.getName() == null ? "" : ba.getName();
                try { mac = ba.getAddress(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return "蓝牙（只读）：" + (on ? "已开" : "已关")
                + (name.isEmpty() ? "" : "；本机名=" + name)
                + (mac == null || mac.isEmpty() ? "" : "；MAC=" + mac)
                + "。★ 开关它属于安全红线，AI 不改。";
    }

    private static String screenInfo(Context ctx) {
        StringBuilder sb = new StringBuilder("屏幕：");
        try { sb.append("屏保=").append(SleepMode.sleeping ? "是" : "否"); } catch (Throwable ignored) {}
        try { sb.append("；深睡=").append(AgentService.deepSleeping ? "是" : "否"); } catch (Throwable ignored) {}
        try {
            if (ctx != null) {
                String rj = RotationLock.statusJson(ctx);
                sb.append("；旋转锁=").append(rj.contains("\"on\":true") ? "锁横向" : "放开");
            }
        } catch (Throwable ignored) {}
        try {
            CommandRouter r = AgentService.router;
            if (r != null && r.display() != null) {
                String cur = r.display().getCurrent();
                sb.append("；正在显示=").append(cur == null || cur.isEmpty() ? "（空）" : cur);
            }
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    private static String sensorInfo() {
        StringBuilder sb = new StringBuilder("传感器：");
        try {
            SensorHub s = AgentService.sensor;
            if (s == null) return "sensors 未就绪（服务未起）";
            sb.append("姿态=").append(s.posture);
            sb.append("；最近触摸=").append(SensorHub.zh(s.lastTouch));
            sb.append("；被摸次数=").append(s.patCount).append("（").append(s.lastPatAgo()).append("ms 前）");
            sb.append("；头顶键=").append(s.headKeyCount)
              .append("（短按").append(s.headKeyShortCount).append("/长按").append(s.headKeyLongCount).append("）");
        } catch (Throwable t) { sb.append("读取失败：").append(t); }
        return sb.toString();
    }

    private static String sysInfo(Context ctx) {
        StringBuilder sb = new StringBuilder("系统：");
        sb.append("版本=").append(versionName(ctx));
        try { sb.append("；运行=").append(sysUptimeSec()).append("s"); } catch (Throwable ignored) {}
        try { sb.append("；内存=").append(procMemHuman()); } catch (Throwable ignored) {}
        try { sb.append("；存储=").append(diskHuman()); } catch (Throwable ignored) {}
        try {
            MotionController mc = AgentService.motion;
            sb.append("；引擎=").append(mc != null && mc.hasEngine() ? "就绪" : "未就绪");
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    private static String poseInfo() {
        StringBuilder sb = new StringBuilder("姿态：");
        try {
            MotionController mc = AgentService.motion;
            sb.append("最近下发动作=").append(mc == null ? "?" : mc.lastAction());
        } catch (Throwable t) { sb.append("读取失败：").append(t); }
        try {
            SensorHub s = AgentService.sensor;
            if (s != null) sb.append("；姿态=").append(s.posture);
        } catch (Throwable ignored) {}
        sb.append("。★ 这里的「最近下发动作」只是**下发值**，不代表机械到位 —— 动作「同步播完」"
                + "也 ≠ 机械到位。★ 舵机角度另**可回读**：GET /motorpos（原厂 ENGSTATUS 广播 → "
                + "按实测拟合换算成角度）；但换算只在 0..90° 段线性、90° 以上会低估，"
                + "且标定随机器走，**不许当控制回路**。");
        return sb.toString();
    }

    private static String logsTail(String a) {
        int n = 20;
        if (a != null && !a.isEmpty()) {
            try { n = Integer.parseInt(a.trim()); } catch (Throwable ignored) {}
        }
        if (n < 1) n = 1;
        if (n > 200) n = 200;
        String body = readFileTail(BootLog.PATH_SDCARD, n);
        if (body == null || body.isEmpty()) return "logs 读不到日志（" + BootLog.PATH_SDCARD + "）";
        return "最近 " + n + " 行日志：\n" + body;
    }

    private static String micInfo() {
        StringBuilder sb = new StringBuilder("麦克风：");
        try {
            CommandRouter r = AgentService.router;
            SoundSourceLocator l = r == null ? null : r.locator();
            sb.append("声源定位=").append(l == null ? "未就绪" : (l.isEnabled() ? "开" : "关"));
            if (l != null && l.isEnabled()) sb.append("；方位=").append(l.sector());
        } catch (Throwable t) { sb.append("读取失败：").append(t); }
        try {
            sb.append("；常听=").append(AgentService.continuousOn() ? "开" : "关");
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    // ---------------------------------------------------------------- write

    private static String execWrite(String n, String a) {
        Context ctx = AgentService.I;
        if ("volume".equals(n)) {
            int v;
            try { v = Integer.parseInt(a); } catch (Throwable e) { return "参数错误：volume 需要 0..15"; }
            return Av.set(v);
        }
        if ("face".equals(n)) {
            if (AgentService.face == null) return "表情控制器不可用";
            if (a.isEmpty()) return "参数错误：face <表情名>";
            return AgentService.face.play(a, false);
        }
        if ("motion".equals(n)) {
            MotionController mc = AgentService.motion;
            if (mc == null) return "动作控制器不可用";
            if (a.isEmpty()) return "参数错误：motion <动作名>";
            if (!MotionController.isValidMotion(a) && !MotionController.isDance(a))
                return "未知动作：" + a + "（用 TOOL motions 看清单）";
            return MotionController.isDance(a) ? mc.dance(a) : mc.playByName(a);
        }
        if ("led".equals(n)) {
            if (AgentService.sdk == null) return "灯控不可用";
            int sp = a.indexOf(' ');
            String color = sp < 0 ? a : a.substring(0, sp).trim();
            String action = sp < 0 ? "" : a.substring(sp + 1).trim();
            if (color.isEmpty()) return "参数错误：led <颜色> <动作>（颜色 r/g/b/y/p/all）";
            return AgentService.sdk.led(color, action);
        }
        if ("say".equals(n)) {
            CommandRouter r = AgentService.router;
            if (r == null || r.tts() == null) return "TTS 不可用";
            if (a.isEmpty()) return "参数错误：say <文本>";
            return r.tts().speak(a, false);
        }
        if ("stop".equals(n)) {
            return AgentService.motion == null ? "动作控制器不可用" : AgentService.motion.stop();
        }
        if ("emotion".equals(n)) return setEmotion(a);
        if ("display".equals(n)) {
            CommandRouter r = AgentService.router;
            if (r == null || r.display() == null) return "屏幕控制器不可用";
            if (a.isEmpty()) return "参数错误：display <文本>";
            return r.display().show(a);
        }
        if ("displayoff".equals(n)) {
            CommandRouter r = AgentService.router;
            if (r == null || r.display() == null) return "屏幕控制器不可用";
            return r.display().hide();
        }
        if ("live".equals(n)) return setLive(ctx, a);
        if ("idle".equals(n)) return setIdle(ctx, a);
        if ("listen".equals(n)) return setListen(ctx, a);
        if ("think".equals(n)) return setThink(ctx, a);
        if ("name".equals(n)) {
            if (ctx == null) return "未就绪（服务未起）";
            if (a.isEmpty()) return "参数错误：name <名字>";
            return "名字已设为：" + Presence.setName(ctx, a);
        }
        if ("call".equals(n)) {
            if (a.isEmpty()) return "参数错误：call <称呼>";
            if (AgentService.I == null) return "未就绪（服务未起）";
            Tone.set(null, a, null, null);
            return "以后它这样称呼你：" + a;
        }
        if ("tone".equals(n)) {
            String v = a.toLowerCase(Locale.ROOT);
            if (!"adult".equals(v) && !"neutral".equals(v) && !"child".equals(v))
                return "参数错误：tone <adult|neutral|child>";
            if (AgentService.I == null) return "未就绪（服务未起）";
            Tone.set(v, null, null, null);
            return "语气档已设为：" + Tone.level();
        }
        if ("rotation".equals(n)) {
            if (ctx == null) return "未就绪（服务未起）";
            String v = a.toLowerCase(Locale.ROOT);
            if ("lock".equals(v)) { RotationLock.set(ctx, Boolean.TRUE, null); RotationLock.apply(ctx); return "已锁横向"; }
            if ("unlock".equals(v)) { RotationLock.set(ctx, Boolean.FALSE, null); RotationLock.apply(ctx); return "已放开自动旋转"; }
            return "参数错误：rotation <lock|unlock>";
        }
        if ("wake".equals(n)) {
            CommandRouter r = AgentService.router;
            VoiceWakeController vw = r == null ? null : r.voiceWake();
            if (vw == null) return "唤醒引擎未就绪";
            return vw.start();
        }
        // ★ v214 性格 style —— 与 POST /tone 的 style 字段走**同一个** Tone.setStyle()
        //   （界面 / HTTP / 模型工具三处同源，绝不各写一份）。
        if ("style".equals(n)) {
            if (a.isEmpty()) return "参数错误：style <warm|direct|buddy|custom:名>（用 GET /tone 看现有）";
            String r = Tone.setStyle(a);
            if (r != null && r.startsWith("{\"error")) return "未知性格：" + a + "（" + r + "）";
            return "性格已设为：" + Tone.styleName();
        }
        if ("remember".equals(n)) {
            String r = Memory.remember(a);
            if (r.contains("\"ok\":false")) return "没能记住：" + a + "（" + r + "）";
            return "记住了：" + a + "（现在共 " + Memory.factCount() + " 条）";
        }
        if ("forget".equals(n)) {
            String sc = a.isEmpty() ? "all" : a;
            String r = Memory.forget(sc);
            if (r.contains("\"ok\":false")) return "忘记失败：" + r;
            return "已忘记（" + sc + "）：" + r;
        }
        return "未知更改：" + n;
    }

    private static String setEmotion(String a) {
        if (a.isEmpty()) return "参数错误：emotion <情绪名|reset>";
        if ("reset".equalsIgnoreCase(a)) { Emotion.reset(); return "情绪已回基线：" + Emotion.current(); }
        if (!Emotion.setByName(a))
            return "情绪不在原厂词表里：" + a + "（用 doc 情绪 查可用名）";
        return "情绪已切到：" + Emotion.current();
    }

    private static String setLive(Context ctx, String a) {
        String v = a.toLowerCase(Locale.ROOT);
        if (!"on".equals(v) && !"off".equals(v)) return "参数错误：live <on|off>";
        boolean on = "on".equals(v);
        AgentService.liveMode = on;
        try {
            if (ctx != null) ctx.getSharedPreferences("ledi_agent", Context.MODE_PRIVATE)
                    .edit().putBoolean("live_mode", on).apply();
        } catch (Throwable ignored) {}
        return "待机活泼化：" + (on ? "已开" : "已关");
    }

    private static String setIdle(Context ctx, String a) {
        int n;
        try { n = Integer.parseInt(a); } catch (Throwable e) { return "参数错误：idle <0-240>（0=关）"; }
        if (n < 0) n = 0;
        if (n > 240) n = 240;
        AgentService.liveIdleMotionEvery = n;
        try {
            if (ctx != null) ctx.getSharedPreferences("ledi_agent", Context.MODE_PRIVATE)
                    .edit().putInt("live_idle_motion_every", n).apply();
        } catch (Throwable ignored) {}
        return "待机动作间隔：" + (n == 0 ? "已关" : n + " 拍（约 " + (n * 8) + "s 一次）");
    }

    private static String setListen(Context ctx, String a) {
        String v = a.toLowerCase(Locale.ROOT);
        if (!"on".equals(v) && !"off".equals(v)) return "参数错误：listen <on|off>";
        AgentService s = AgentService.I;
        if (s == null) return "未就绪（服务未起）";
        boolean on = "on".equals(v);
        if (on && SleepMode.sleeping)
            return "屏保中不能开麦（会撕开屏保）。先叫醒再开。";
        AgentService.persistListenAuto(on);
        if (on) s.startContinuous(); else s.stopContinuous();
        return "常听：" + (on ? "已开（会一直听）" : "已关");
    }

    private static String setThink(Context ctx, String a) {
        String v = a.toLowerCase(Locale.ROOT);
        if (!"on".equals(v) && !"off".equals(v)) return "参数错误：think <on|off>";
        AgentService.persistThinkOn("on".equals(v));
        return "多轮思考：" + ("on".equals(v) ? "已开" : "已关");
    }

    // ---------------------------------------------------------------- plan

    private static String execPlan(String n, String a) {
        if ("wait".equals(n)) {
            int ms = 0;
            if (a != null && !a.isEmpty()) { try { ms = Integer.parseInt(a.trim()); } catch (Throwable ignored) {} }
            if (ms < 0) ms = 0;
            if (ms > 10000) ms = 10000;
            try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "waited " + ms + "ms";
        }
        if ("workflow".equals(n) || "run".equals(n)) {
            if (a.isEmpty())
                return "参数错误：workflow 后跟步骤（每行一个 `op 参数`）。可用 op：" + brief(allowedOps());
            String body = Workflow.normalizeSteps(a);
            Workflow.Script sc = Workflow.parse(body, "ai-" + System.currentTimeMillis());
            if (!sc.ok()) return "预检未通过（**一步都没执行**）：" + sc.error;
            boolean started = Workflow.start(sc, Thinking.deviceToolBox());
            if (!started) return "已经有一条 workflow 在跑（同时只允许一条）—— 等它跑完再交新的";
            return "已接单并开始执行 " + sc.steps.size() + " 步：" + Thinking.stepsBrief(sc)
                    + "（结果用 GET /workflow 看流水）";
        }
        return "未知编排：" + n;
    }

    // ================================================================ 只读小工具

    private static String versionName(Context ctx) {
        try {
            if (ctx != null) {
                android.content.pm.PackageInfo pi = ctx.getPackageManager()
                        .getPackageInfo(ctx.getPackageName(), 0);
                return pi.versionName == null ? "?" : pi.versionName;
            }
        } catch (Throwable ignored) {}
        return "?";
    }

    private static long sysUptimeSec() {
        try { return android.os.SystemClock.elapsedRealtime() / 1000; } catch (Throwable t) { return 0; }
    }

    /**
     * 读 /proc/meminfo 里的 MemTotal / MemAvailable（读不到就如实空）。
     * ★ 不用 ActivityManager.getMemoryInfo 是因为那要给 Context；这里只想"纯读一行"。
     */
    private static String procMemHuman() {
        long total = 0, avail = 0;
        String txt = readAll("/proc/meminfo");
        if (txt == null) return "读不到";
        for (String line : txt.split("\r?\n")) {
            String s = line.trim();
            long kb = parseKb(s);
            if (kb <= 0) continue;
            if (s.startsWith("MemTotal:")) total = kb;
            else if (s.startsWith("MemAvailable:")) avail = kb;
        }
        if (total <= 0) return "读不到";
        if (avail <= 0) return (total / 1024) + "MB 总";
        return "可用 " + (avail / 1024) + "MB / 总 " + (total / 1024) + "MB";
    }

    private static long parseKb(String line) {
        int i = line.indexOf(':');
        if (i < 0) return -1;
        StringBuilder d = new StringBuilder();
        for (int k = i + 1; k < line.length(); k++) {
            char c = line.charAt(k);
            if (c >= '0' && c <= '9') d.append(c);
            else if (d.length() > 0) break;
        }
        if (d.length() == 0) return -1;
        try { return Long.parseLong(d.toString()); } catch (Throwable t) { return -1; }
    }

    private static String diskHuman() {
        try {
            android.os.StatFs d = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
            long free = (long) d.getAvailableBlocks() * (long) d.getBlockSize();
            long tot = (long) d.getBlockCount() * (long) d.getBlockSize();
            if (tot <= 0) return "读不到";
            return "可用 " + (free / 1048576L) + "MB / 总 " + (tot / 1048576L) + "MB";
        } catch (Throwable t) { return "读不到（" + t.getClass().getSimpleName() + "）"; }
    }

    /** 读文件尾 n 行（尾读：先整读再切，日志文件在本机不超过几百 KB）。 */
    static String readFileTail(String path, int n) {
        String all = readAll(path);
        if (all == null) return null;
        String[] lines = all.split("\r?\n");
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, lines.length - n);
        for (int i = from; i < lines.length; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    static String readAll(String path) {
        File f = new File(path);
        if (!f.exists() || !f.isFile()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] all = new byte[(int) Math.min(f.length(), 512 * 1024)];
            int off = 0, r;
            while (off < all.length && (r = in.read(all, off, all.length - off)) > 0) off += r;
            return new String(all, 0, off, "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    static String brief(String[] xs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.length; i++) { if (i > 0) sb.append('/'); sb.append(xs[i]); }
        return sb.toString();
    }

    // ================================================================ 成败判定（唯一出口）

    /**
     * ★★★ 一条能力输出到底是**成功**还是**失败** —— 判据也只有这一份。
     *
     * <h4>为什么它必须待在能力层</h4>
     * v203 起 {@link Workflow} 用一段**字符串启发式**（{@code looksOk}）决定"这一步算不算失败"
     * ——失败就中止整条编排。启发式漏一个词，后果就是**假绿灯**：v207 真机验收当场抓到，
     * {@code led blue on} 实际返回 {@code "bad color: blue"}（是失败），旧启发式却判成成功，
     * 编排会当作"这一步过了"继续往下跑。所以：
     * <ul>
     *   <li>失败词表**跟着能力层走** —— 谁产出错误文案，谁负责认得它（修在一个地方就真的修好了）；</li>
     *   <li>只看**第一行 / 前 80 字** —— {@code logs} 这种会把任意日志原文拼进输出的能力，
     *       正文里出现"未知""失败"是**内容**，不是这条能力失败。</li>
     * </ul>
     */
    private static final String[] FAIL_WORDS = {
        "参数错误", "不可用", "未就绪", "未知", "不在原厂词表",
        "bad color", "bad action", "读取失败", "读不到日志",
        "工具执行异常", "不能开麦", "预检未通过", "已经有一条",
        "只读不可改",
    };

    /** 见 {@link #FAIL_WORDS}。空 / 异常 / 已知失败文案 → true。 */
    public static boolean isFailure(String out) {
        if (out == null) return true;
        String t = out.trim();
        if (t.isEmpty()) return true;
        int nl = t.indexOf('\n');
        if (nl >= 0) t = t.substring(0, nl);            // ★ 只判第一行
        if (t.length() > 80) t = t.substring(0, 80);
        String o = t.toLowerCase(Locale.ROOT);
        if (o.startsWith("error")) return true;
        if (o.contains("\"ok\":false")) return true;
        if (o.contains("not ready")) return true;
        for (String b : FAIL_WORDS) if (o.contains(b.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    // ================================================================ 对外 JSON（GET /abilities）

    /**
     * 能力矩阵。给网页/脚本/模型看的"边界一览"——把安全边界**变成可查询的一等公民**，
     * 而不是只写在注释里。用户要"保证安全"，前提是他能一眼看到边界在哪。
     */
    public static String json() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ok\":true,\"counts\":{");
        sb.append("\"read\":").append(namesOf(READ).length)
          .append(",\"write\":").append(namesOf(WRITE).length)
          .append(",\"plan\":").append(namesOf(PLAN).length)
          .append(",\"forbid\":").append(namesOf(FORBID).length)
          .append(",\"total\":").append(SPECS.length).append('}');
        sb.append(",\"policy\":\"除安全红线外，AI 可查询或做出有限更改；"
                + "wifi/adb/开关机/深睡/冻结/杀进程/shell 属红线：只读不可改（查询走 netinfo/adbinfo/btinfo/screen）。\"");
        for (String kind : new String[]{READ, WRITE, PLAN, FORBID}) {
            sb.append(",\"").append(kind).append("\":[");
            boolean first = true;
            for (Spec s : SPECS) {
                if (!s.kind.equals(kind)) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":\"").append(J.esc(s.name)).append('"')
                  .append(",\"desc\":\"").append(J.esc(s.desc)).append('"')
                  .append(",\"usage\":\"").append(J.esc(s.usage)).append("\"}");
            }
            sb.append(']');
        }
        return sb.append('}').toString();
    }

    // ================================================================ 自检

    /**
     * 纯静态自检 —— 不碰硬件、不联网、不读 assets（{@code AgentService.I == null} 也能跑）。
     *
     * <p>验三件事：① 规格表自洽（无重名、无非法 kind、每条都有说明与用法）；
     * ② **安全红线在执行层真的被拒**（这是"安全"的硬证据，不是靠注释）；
     * ③ 允许的 op 都有实现分支（不在执行时掉进"未知"）。</p>
     */
    public static String selftest() {
        StringBuilder log = new StringBuilder();
        int ok = 0, n = 0;

        // ① 规格表自洽
        {
            java.util.Set<String> seen = new java.util.HashSet<String>();
            boolean dup = false, badKind = false, emptyField = false;
            for (Spec s : SPECS) {
                if (!seen.add(s.name)) dup = true;
                if (!READ.equals(s.kind) && !WRITE.equals(s.kind)
                        && !PLAN.equals(s.kind) && !FORBID.equals(s.kind)) badKind = true;
                if (s.desc == null || s.desc.isEmpty()) emptyField = true;
                if (s.usage == null || s.usage.isEmpty()) emptyField = true;
            }
            n++; if (!dup) ok++; else log.append("  ✗ 规格表有重名\n");
            n++; if (!badKind) ok++; else log.append("  ✗ 规格表有非法 kind\n");
            n++; if (!emptyField) ok++; else log.append("  ✗ 有条目缺 desc/usage\n");
        }
        // ② 安全红线必须在执行层被拒（用户要的"安全"，硬证据）
        {
            String[] red = {"wifi", "bt", "adbwifi", "reboot", "shutdown", "poweroff",
                    "deepsleep", "freeze", "kill", "shell", "uninstall", "wipe"};
            int rejected = 0;
            for (String r : red) {
                String out = execute(r, "");
                boolean isRej = isForbidden(r) && out != null && out.contains("安全红线");
                if (isRej) rejected++;
                else log.append("  ✗ 红线未被执行层拒绝：" + r + " -> " + out + "\n");
            }
            n++; if (rejected == red.length) ok++;
            else log.append("  ✗ 红线拒绝不全（" + rejected + "/" + red.length + "）\n");
        }
        // ③ 未知 op 必须如实拒绝（不许静默当成功）
        {
            String out = execute("flytospace", "1");
            n++; if (out != null && out.contains("未知能力")) ok++;
            else log.append("  ✗ 未知 op 未如实拒绝：" + out + "\n");
        }
        // ④ 每个 read/write/plan op 都必须有实现分支（不在执行时掉进"未知…"）
        {
            int missing = 0;
            for (Spec s : SPECS) {
                if (FORBID.equals(s.kind)) continue;
                String out = execute(s.name, s.name.equals("wait") ? "0" : "zzz_probe");
                // 允许"参数错误 / 未就绪 / 未起 / 不可用"（设备不在场），但**不许**是"未知能力/未知查询/未知更改/未知编排"
                if (out != null && (out.contains("未知能力") || out.contains("未知查询")
                        || out.contains("未知更改") || out.contains("未知编排"))) {
                    missing++;
                    log.append("  ✗ " + s.name + " 没有被实现（" + out + "）\n");
                }
            }
            n++; if (missing == 0) ok++;
        }
        // ⑤ 红线的"该用哪个查询"提示必须写对（别指向自己）
        {
            boolean bad = false;
            for (String r : forbiddenOps()) {
                String hint = readHintFor(r);
                if (hint == null || hint.isEmpty()) bad = true;
            }
            n++; if (!bad) ok++; else log.append("  ✗ 有条红线缺查询提示\n");
        }
        // ⑥ 允许清单与红线**不重叠**（同一 op 不能既可调又禁止）
        {
            java.util.Set<String> a = new java.util.HashSet<String>();
            for (String x : allowedOps()) a.add(x);
            boolean overlap = false;
            for (String x : forbiddenOps()) if (a.contains(x)) overlap = true;
            n++; if (!overlap) ok++; else log.append("  ✗ 允许与禁止清单重叠\n");
        }

        // ⑦ 成败判定：真实失败文案必须判失败、真实成功文案不许误判（防"假绿灯"）
        {
            String[] shouldFail = {
                "参数错误：volume 需要 0..15",
                "未知动作：zzz（用 TOOL motions 看清单）",
                "灯控不可用",
                "bad color: blue",
                "bad action: xxx",
                "「wifi」是安全红线，**只读不可改**（改 WiFi…）。要查询请用对应的只读能力：netinfo",
                "预检未通过（**一步都没执行**）：第 1 行：未知 op",
                "",
                "engine not ready",
                "adbinfo 读取失败：java.lang.SecurityException",
            };
            String[] shouldPass = {
                "queued: hiRighthand",
                "sdk:ledBlueOn",
                "broadcast:com.auric.intell.xld.os.led.all.off",
                // ★ logs 会把任意日志原文拼进输出：正文里的"未知/失败"是**内容**，不是这条能力失败
                "最近 5 行日志：\n10-11 12:00 I 未知动作：zzz（这是内容，不是失败）",
                // ★ sysinfo 允许局部"读不到"（内存项），第一行里含它有就不该整条判失败
                "系统：版本=1.0-v207；运行=797s；内存=读不到；存储=可用 2036MB / 总 2529MB；引擎=就绪",
                // ★ 只读查询项**成功**时也会提"安全红线"（那是说明，不是拒绝）
                "无线调试（只读）：5555 正在监听；★ 开关它属于安全红线，AI 不改，只能查。",
                "waited 350ms",
                "电量 99%（充电中）",
            };
            int bad2 = 0;
            for (String x : shouldFail) if (!isFailure(x)) { bad2++; log.append("  ✗ 失败文案未判失败：「" + x + "」\n"); }
            for (String x : shouldPass) if (isFailure(x)) { bad2++; log.append("  ✗ 成功文案被误判失败：「" + x + "」\n"); }
            n++; if (bad2 == 0) ok++;
        }

        StringBuilder out = new StringBuilder();
        out.append("能力真源自检 ").append(ok).append('/').append(n).append(ok == n ? " PASS" : " FAIL");
        if (ok != n) out.append('(').append(n - ok).append(')');
        out.append('\n');
        if (log.length() > 0) out.append(log);
        out.append("只读 ").append(namesOf(READ).length)
           .append(" / 可改 ").append(namesOf(WRITE).length)
           .append(" / 编排 ").append(namesOf(PLAN).length)
           .append(" / 红线 ").append(namesOf(FORBID).length)
           .append("（共 ").append(SPECS.length).append("）\n");
        out.append("红线：").append(brief(forbiddenOps())).append('\n');
        return out.toString();
    }
}
