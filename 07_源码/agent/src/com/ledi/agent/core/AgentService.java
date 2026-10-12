package com.ledi.agent.core;

import com.ledi.agent.net.AdbWifi;
import com.ledi.agent.cap.AiCore;
import com.ledi.agent.voice.AsrProbe;
import com.ledi.agent.net.BleProvisionServer;
import com.ledi.agent.net.BtCommandServer;
import com.ledi.agent.net.BtPan;
import com.ledi.agent.voice.CloudAsr;
import com.ledi.agent.ui.DebugPad;
import com.ledi.agent.ui.DisplayController;
import com.ledi.agent.cap.Emotion;
import com.ledi.agent.sdk.FaceController;
import com.ledi.agent.ui.GearOverlay;
import com.ledi.agent.sense.HeadKey;
import com.ledi.agent.ui.HomeSetter;
import com.ledi.agent.net.HttpServer;
import com.ledi.agent.voice.IntentResolver;
import com.ledi.agent.ui.LogOverlay;
import com.ledi.agent.net.LoopbackRelay;
import com.ledi.agent.voice.MediaPlayback;
import com.ledi.agent.cap.Memory;
import com.ledi.agent.voice.MicController;
import com.ledi.agent.motion.MotionController;
import com.ledi.agent.motion.MotorPose;
import com.ledi.agent.net.PcLink;
import com.ledi.agent.cap.Presence;
import com.ledi.agent.cap.ProactiveTalk;
import com.ledi.agent.sdk.ProcessFreezer;
import com.ledi.agent.voice.ReactionEngine;
import com.ledi.agent.ui.RotationLock;
import com.ledi.agent.sdk.SdkController;
import com.ledi.agent.sense.SensorHub;
import com.ledi.agent.ui.SettingsActivity;
import com.ledi.agent.ui.SleepMode;
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

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 遥控代理主服务（运行在机器人上）。
 * 职责：
 *  1. 绑定原厂 EngineService（动作引擎，串口 /dev/ttyMT0）
 *  2. 启动 HTTP 控制服务器（端口 8899）
 *  3. 启动蓝牙 SPP 直连控制服务（手机免 WiFi 直连遥控）
 *  4. 启动 BLE 配网 GATT 服务端
 *  5. 启动 UDP 存在广播（供手机 App 自动发现）
 *  6. 屏幕左上角白色齿轮设置入口（点开机器人控制界面）
 *  7. 本机回环转发（127.0.0.1:18080 → 设置里填的网关），让主 App 的对话端点不再写死 IP
 *  8. 蓝牙默认打开（机器人一开机就得能被手机连上，见 {@link #enableBluetooth}）
 *  9. 后勤线程：蓝牙看护（v176 起长期有效）+ 齿轮丢失自愈（见 {@link #startHousekeeping}）
 *
 * <p>诊断入口：本机 {@code /sdcard/ledi_agent.log} 记录「谁把我拉起来的 / 蓝牙什么时候开的 /
 * 齿轮什么时候补画的」—— 这台 ROM 屏蔽了 App 的 logcat，只能靠文件（见 {@link BootLog}）。
 */
public class AgentService extends Service {

    public static final String TAG = "LediAgent";
    public static final int HTTP_PORT = 8899;
    public static final int UDP_PORT = 8899;
    public static final String PREFS = "ledi_agent";   // 包内可见：IntentResolver 等要读写开关

    /** 服务是否已起来（供 BootReceiver 去重、供 housekeeping 判断）。 */
    private static volatile boolean running;

    /**
     * 当前活着的服务实例。
     *
     * <p>HTTP 路由（{@link CommandRouter}）是纯对象、拿不到 Service 实例，而
     * "开/关常识别"这种动作必须操作服务里的那个循环，所以留一个静态出口。
     * 只用它做<b>转发</b>，别把状态也放它身上。</p>
     */
    public static volatile AgentService I;

    // 机器人控制界面（SettingsActivity）与齿轮直接使用的组件引用
    public static volatile CommandRouter router;
    public static volatile MotionController motion;
    public static volatile FaceController face;
    public static volatile Speaker speaker;
    public static volatile WifiProvisioner wifiProvisioner;
    public static volatile MicController mic;
    public static volatile DisplayController display;
    public static volatile SensorHub sensor;
    public static volatile ReactionEngine reaction;
    public static volatile SdkController sdk;
    public static volatile LoopbackRelay relay;
    // newcore 重构新增
    public static volatile AiCore ai;
    public static volatile ProactiveTalk proactiveTalk;
    public static volatile MediaPlayback media;
    public static volatile SoundSourceLocator locator;
    public static volatile VoiceWakeController voiceWake;
    public static volatile HeadKey headKey;        // v116 头顶按键
    public static volatile Presence presence;      // v142 在不在线 / 名字 / 语音退避
    public static volatile MotorPose motorPose;    // v147 电机姿态读回（原厂状态广播）
    private HttpServer httpServer;
    private UdpBeacon beacon;
    private BleProvisionServer bleServer;
    private BtCommandServer btServer;

    /**
     * 心跳（{@link UdpBeacon}）的静态出口。
     * <p>它本质上是服务实例的字段，但 {@link SleepMode} 需要在屏保里把它整条停发
     * （用户要求"屏保期间停止心跳"），所以留一个只读转发 —— 状态还是只有服务里那一份。</p>
     */
    public static UdpBeacon beaconNow() {
        AgentService s = I;
        return s == null ? null : s.beacon;
    }

    private volatile boolean housekeeping;
    private Thread housekeepThread;

    /**
     * 应答互斥：一次只允许一条应答链路在跑。
     *
     * <p>没有它会发生什么：连抹两下头顶 / 喊一声又抹一下，会同时起两个应答线程 ——
     * 两边各自 {@code ai.ask} + {@code tts.speak} + {@code voiceWake.resume()}，
     * 播报互相盖掉、麦克风被反复抢，现场表现就是"结巴 + 听不见"。
     * 后到的那次只打断播报、不另起线程（用户的直觉也正是"打断它"，不是"再说一遍"）。</p>
     */
    private final java.util.concurrent.atomic.AtomicBoolean answering =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 方位感知的"合并序号"。
     *
     * <p>每收到一次摸脸事件就自增；700ms 后醒来的线程先看自己是不是仍是最新的那一号，
     * 不是就直接退出。这样"一串事件"只会留下**一次**转头，而不是每个事件各转一次
     * —— 后者会排在动作池（单线程）里一个个演完，现场就是用户反馈的"几个命令叠加、卡顿"。</p>
     */
    private final java.util.concurrent.atomic.AtomicLong dirSeq =
            new java.util.concurrent.atomic.AtomicLong();

    /** 上一次真正发出"转头看人"的时刻（毫秒），用于限流，防止连续两次转头。 */
    private volatile long lastLookMs = 0;

    /** 两次"转头看人"之间的最小间隔：小于它就直接放弃这一次（合并掉）。 */
    private static final long LOOK_MIN_GAP_MS = 1800;

    /**
     * 语音对话提示词：告诉大模型"这句话是离线语音识别结果"。
     *
     * <p>★ v94 改了措辞。原文写的是"可能有同音错字或漏字" —— 那句话对<b>语法受限</b>的
     * 离线解码器是<u>错的</u>：它的输出必然是词表里的某一句整句，**不可能出现错别字**，
     * 真正的错误形态是「整句选错」（见 {@link IntentResolver}）。提示词与真实错误形态
     * 对不上，模型就会去"猜错字"，方向从一开始就偏了。</p>
     *
     * <p>自由说话的语义容错仍然交给大模型，但依据由
     * {@link IntentResolver#correctionHint} 补上（词表边界 + 易混候选）。</p>
     */
    private static final String VOICE_HINT =
            "（下面这句是机器人的离线语音识别结果，它只会从一份固定词表里选出整句，"
            + "所以不会有错别字，但可能整句选错。请按最接近的意思理解后回答，"
            + "不要纠正我的错字，也不要复述我的话，不要提「识别」「词表」这类字眼。）\n";

    /** 机器人把话说完之后，还愿意等多久听主人接话（接上了就继续聊）。 */
    private static final long FOLLOWUP_WAIT_MS = 4000;

    /**
     * ★★★ v178：**播完自动续听**（用户 2026-10-06：「说完话后自动拉起对话」）。
     *
     * <p>开（默认）：一轮应答播报完、且没被打断，就自动开下一轮听 —— 不用再喊唤醒词，
     * 可连续聊到 {@link VoiceDialog#maxTurns} 轮；中间哪一轮没人说话就收工。</p>
     *
     * <p>关：回到 v177 的老行为（唤醒一次只聊一轮，除非你插嘴）。
     * 端点 {@code POST /dialog {"followup":false}} 可热切换 —— 观感类行为不写死，
     * 免得每换一次观感都得重打包装机。</p>
     */
    public static volatile boolean autoFollowup = true;

    /**
     * ★★★ v178：远程验收时注入的"主人第一句话"，设一次用一次（用完即清）。
     *
     * <p>配合 {@code POST /dialogturn {"text":"…"}}：它先设这里、再调
     * {@link VoiceWakeController#forceWake}，于是整轮应答从"听"这一步之后开始跑，
     * 唤醒与识别两个脆弱环节都被摘掉，剩下的链路（AI → 播报 → 播完自动续听）
     * 就能被远程一键验证。</p>
     */
    static volatile String injectFirstSay = null;

    /**
     * 主人刚才在哪一侧说话/摸它。取最近 8 秒内的一次方位事件 —— 判太严会取不到
     * （唤醒词刚喊完、用户一句话可能已经过去两三秒），判太松会拿到上一次的旧方位。
     *
     * <p>★ 这里**故意不看** {@code locator.isEnabled()}：双麦 DOA 在本 ROM 上已被实测
     * 否掉（HAL 把单路复制成两份），能用的方位来源是触摸。触摸是常开的，不该被
     * 那个开关连坐。</p>
     */
    private String locateSpeaker() {
        SoundSourceLocator l = locator;
        if (l == null) return "";
        String s = l.freshSector(8000);
        return s == null ? "" : s;
    }

    /**
     * 一轮唤醒的完整应答：听 → 给 AI → 播报（播报期间还能被语音打断）→ 听 → …
     *
     * <p>循环最多 {@link VoiceDialog#maxTurns} 轮，任一轮没听到人话就收工。
     * 打断时听到的那句话会直接当作下一轮的问题 —— 所以"打断"不是打断完重来，
     * 而是"你插嘴的内容立刻被回应"，这才是自然对话。</p>
     */
    /**
     * ★★★ v144：「机器人在听」的灯语 —— 麦克风打开期间 LED 闪，收工立即灭。
     *
     * <p>用户 2026-09-24 的明确口径：**取消主动推送，只用 LED 闪烁表示机器人在听**。
     * 所以这条灯语不是装饰，它是「轮到你说」的**唯一外部信号**；判据也随之变成
     * <b>「该听的时候灯在闪、不说话的时候灯是灭的」</b>，而不是「灯有没有亮过」。</p>
     *
     * <p>实现刻意走 {@link SdkController#led}：它优先反射原厂静态方法，失败退回
     * {@code com.auric.intell.xld.os.led.all.blink.fast} 裸广播 —— 灯效广播的接收方在
     * 系统/MCU 侧，**不依赖原厂 App 进程**，所以屏保冻结原厂时这条灯语照样能用。</p>
     *
     * <p>开关：prefs {@code led_listen}（默认 true）；关掉后 {@code ledListen} 直接空转。</p>
     */
    // ==================== ★★★ v167：听语灯 = 蓝灯 ====================
    //
    // 【用户口径（2026-09-25，高优先级）】「机器人正在收听的时候头顶亮蓝灯」。
    //
    // 【为什么把灯语从"全灯快闪"改成"蓝灯"，又为什么挂在麦克风闸上】
    //   v144 的写法是 all.blink.fast（全灯快闪），而且**只在 answerLoop 一处**点亮 ——
    //   也就是只有"抹头顶/唤醒词触发的整轮对话"那条路会亮。
    //   但"机器人在收听"的入口不止一个：`/ilisten`（诊断口直接开麦）、离线兜底链路
    //   （VoiceDialog.listenBlocking）、离线名字探测…它们**全都不亮灯**。
    //   本项目的教训白纸黑字写着「修在一个地方 ≠ 修好了」：同一规则散在多处，
    //   将来新加一个开麦点就又漏一个。所以这次不逐处补，而是**下沉到唯一的麦闸**——
    //   VoiceWakeController.beforeAsrMic()/afterAsrMic()，在线（cloudListen）与
    //   离线（VoiceDialog 经 MicGate）两条路都汇到它，一处生效即全覆盖。
    //
    // 【为什么做成可切换而不是写死】"蓝常亮"还是"蓝慢闪"是产品观感，真机上要人眼看；
    //   写死了每换一次观感就得重打一次包、重装一次机（本项目装包还要手工 am startservice）。
    //   所以模式进 prefs，`POST /listenled {"mode":...}` 一条命令换，且保留 v144 的
    //   all_blink 作为一键回退（万一蓝灯这条反射/广播在这台 ROM 上不生效）。
    //
    // 【判据】灯是硬件、我们读不回来，所以能自证的只有"我们到底下发了什么"：
    //   GET /listenled → {on, active, mode, led, last} —— active=现在是否该亮，
    //   last=最近一次下发的回执（sdk:xxx 表示反射到了原厂静态方法，否则是广播名）。
    //   真机"到底亮没亮、是不是蓝的"仍然只能人眼看，验收时就按这个去问。

    /** 蓝灯常亮（默认）：原厂 led_Blue_On。 */
    public static final String LED_MODE_BLUE_ON = "blue_on";
    /** 蓝灯慢闪：原厂 led_Blue_BlinkSlow_On。 */
    public static final String LED_MODE_BLUE_BLINK = "blue_blink";
    /** v144 旧行为（全灯快闪），留作一键回退。 */
    public static final String LED_MODE_ALL_BLINK = "all_blink";
    /** 完全不点灯（等同旧 led_listen=false）。 */
    public static final String LED_MODE_OFF = "off";

    /** ★ 默认蓝灯常亮 —— 用户原话是「亮蓝灯」，字面即常亮。 */
    public static final String LED_MODE_DEFAULT = LED_MODE_BLUE_ON;

    /**
     * 点/灭「机器人在听」的灯语。<b>幂等</b>：麦克风闸每收放一次都会调，重复调用不刷广播。
     *
     * <p>★ static 且用 {@link #I} 取 Context：{@link VoiceWakeController} 与
     * {@link CommandRouter} 都要在拿不到 AgentService 实例引用的地方调它。</p>
     */
    public static void listenLed(boolean on) { listenLed(on, false); }

    /**
     * @param force 无视幂等锁，强制下发一次。
     *
     *   <p>★★★ 为什么必须有它（v167 实测）：<b>原厂 App 有自己的周期灯效刷新</b> ——
     *   它在约 6~7 秒的周期上发 {@code com.auric.intell.xld.os.led.all.off}
     *   （对照证据：我们连点 10 次蓝灯，原厂只灭 3 次，两者不同步 ⇒ 那是它自发的周期，
     *   不是"看到我们亮灯就来灭"）。</p>
     *
     *   <p>后果很实在：一次收听超过 7 秒，灯就被原厂中途灭掉，用户看到的是"灯断了一下"。
     *   更麻烦的是<b>我们读不回灯</b> —— 逻辑锁以为亮着，硬件上其实已经被推翻了。</p>
     *
     *   <p>所以「开麦」「收麦」「采音心跳」这三种关键动作一律 force 重发，
     *   而不是相信那把锁。代价只是多几条广播（广播本身很轻，原厂自己也在发）。</p>
     */
    public static void listenLed(boolean on, boolean force) {
        // ★★★ v178：**说话期间不亮蓝灯**（用户 2026-10-06 明确要求）。
        //
        //   为什么判据必须落在**这个唯一出灯口**、而不是逐处去改调用点：
        //   要"亮灯"的路有四条 ——
        //     ① 麦闸 beforeAsrMic（开麦听）
        //     ② 防打断的 barge-in 开麦（AgentService.speakWithBargeIn）
        //     ③ 采音心跳补灯 CloudAsr.micTick（每几十毫秒一次，为对抗原厂周期灭灯）
        //     ④ 端点手动点灯（CommandRouter POST /listenled）
        //   其中 ③ 是硬伤：它每拍都发"亮"，只要还有一路在说话期间要求亮，
        //   灯会在几十毫秒内被重新点亮 —— 逐处打补丁必漏（本项目老毛病：
        //   「修在一个地方 ≠ 修好了」）。所以把判据下沉到这里，一处生效即全覆盖。
        //   ★★★ v206：这段判断已**收进 ledState()**（下面统一调用），不再在此手写 ——
        //     否则"思考中"会因为这里的提前 return 而被绕过（绿灯永远点不亮）。
        // ★★★ v206：**思考中优先**（用户 2026-10-11 口径）。
        //   状态优先级：思考中(绿灯常亮) > 在听(蓝灯) > 灭。
        //   ★ 为什么在"唯一出灯口"里再分一层，而不是在 think() 前后各点一次灯：
        //     ① 采音心跳（上面 ③）每几十毫秒发一次"亮蓝"，若不在出口拦，
        //        思考的绿灯会在几十毫秒内被蓝灯盖掉 —— 与 v178 说话灭蓝灯是同一个坑；
        //     ② 思考可能跨越"说完话 → 开麦续听"的边界，散点控制必然分叉。
         //   ★ 为什么绿灯是**常亮**不是闪：用户 2026-10-11 明确选「绿灯常亮」。
        if (thinkLedOn) {
            ledWrite(THINK_COLOR + "." + THINK_ACTION, true);
            return;
        }
        // ★★★ v206：灯的"该是什么颜色"由 ledState() 决定 —— 与自检**同一个函数**。
        //   不许在这里再手写一套判断（那样自检就变成"测一个不存在的实现"）。
        //   ledState 已把 v178 的"说话灭听灯"收进去，所以下面不再重复那段逻辑；
        //   want 只有 b.on / all.off 两种（thinkLedOn 为 false 时不会返回 g.on）。
        String want = ledState(false, on, ttsSpeakingNow, ledSpeakOff);
        if (!force && ledListenOn == "b.on".equals(want)) return;   // 幂等：非关键路径别刷广播
        AgentService svc = I;
        if (svc == null) return;
        String mode;
        try {
            mode = svc.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString("led_listen_mode", LED_MODE_DEFAULT);
        } catch (Throwable t) {
            mode = LED_MODE_DEFAULT;
        }
        if (mode == null || mode.isEmpty()) mode = LED_MODE_DEFAULT;

        // 关灯一律用 all.off（原厂 led_Close_All）：模式切换后不会留下上一盏没关掉
        //（比如刚才是 all.blink.fast，用 b.off 关不掉那圈闪灯）。
        // ★ 只有在"确实要亮"时才去取 led_listen_mode；要灭就是 all.off。
        String color, action;
        boolean lit = "b.on".equals(want);
        if (!lit) {
            color = "all"; action = "off";
        } else {
            String spec = ledSpec(mode);
            if ("off".equals(spec)) return;   // 不点灯：连状态都不翻，免得下一轮又"灭"一次
            int dot = spec.indexOf('.');
            if (dot <= 0) {
                color = "b"; action = "on";
            } else {
                color = spec.substring(0, dot);
                action = spec.substring(dot + 1);
            }
        }
        ledWrite(color + "." + action, lit);
    }

    /**
     * 唯一的下发动作（v206 从 {@code listenLed} 里抽出来，思考灯与听灯共用）。
     *
     * <p>★ 抽出来是为了**一个出口**：思考灯与听灯若各写一份"调 sdk + 记
     * {@code listenLedLast} + 打日志 + 失败回滚"，两处必然分叉（本项目老毛病）。
     * 抽出来后，{@code /listenled} 的回执与设备日志对思考灯同样有效。</p>
     *
     * @param spec 形如 {@code b.on} / {@code all.off} / {@code g.on}
     * @param on   写入 {@code ledListenOn} 的值（"逻辑上是否处在点亮态"）
     */
    private static void ledWrite(String spec, boolean on) {
        SdkController s = sdk;
        if (s == null) return;
        int dot = spec.indexOf('.');
        String color = dot > 0 ? spec.substring(0, dot) : "all";
        String action = dot > 0 ? spec.substring(dot + 1) : "off";
        boolean prev = ledListenOn;
        ledListenOn = on;
        try {
            String r = s.led(color, action);
            listenLedLast = color + "." + action + " -> " + r;
            BootLog.i("灯 " + (on ? "亮" : "灭") + "（" + color + "." + action + "）-> " + r);
        } catch (Throwable t) {
            ledListenOn = prev;             // 失败回滚状态，下一轮还会再试
            listenLedLast = color + "." + action + " -> 异常 " + t;
            BootLog.w("灯 失败: " + t);
        }
    }

    // ==================== ★★★ v206：思考中 = 绿灯常亮 ====================
    //
    // 【用户口径（2026-10-11）】「在机器人在思考的时候也亮起灯，绿灯常亮」。
    //
    // 【为什么必须挂在这里、且优先级最高】
    //   "思考"发生在**说完话之后、回答之前**这段窗口里，而这段时间麦闸往往还开着
    //   （防打断续听 / 常听模式）⇒ 听语灯正亮着蓝。若只是"另外点一盏绿灯"，
    //   两个状态会互相覆盖，看到的颜色取决于最后一次写入 ⇒ 不可预测。
    //   所以做成**状态优先级**：思考中(绿) > 在听(蓝) > 灭，判据只写在本函数一处。
    //
    // 【为什么用 force 常亮、不跟原厂周期灯效较劲】
    //   原厂 App 有自己的 ~6~7 秒周期灯效刷新（v167 实测，会发 all.off），
    //   所以思考窗口超过 7 秒时绿灯也可能被原厂灭掉 —— 与听语灯同一个问题。
    //   这里沿用同一套对策：由 callers 在思考期间保持"我该是绿的"，
    //   一旦有新的点灯请求经过本出口就重新写绿。

    /** 思考中：绿灯常亮（用户 2026-10-11 选定）。 */
    static final String THINK_COLOR = "g";
    static final String THINK_ACTION = "on";

    /** 是否正在思考（v206）。 */
    static volatile boolean thinkLedOn = false;

    /** 思考灯的观测计数（进了几次思考 / 实际下发过几次），用于进程外定性。 */
    static volatile int thinkLedEnters = 0;
    static volatile int thinkLedWrites = 0;

    /** 思考灯最近一次下发的回执（给 /listenled 看，与听灯同源字段）。 */
    static volatile String thinkLedLast = "";

    /**
     * 进入/退出「思考中」（绿灯常亮）。{@link AiCore#think} 的最外层调用。
     *
     * <p>★ 用 try/finally 的写法在调用点保证成对；本函数自身也做幂等，
     * 重复进/重复出不刷广播。</p>
     */
    public static void noteThinking(boolean thinkingNow) {
        if (thinkLedOn == thinkingNow) return;      // 幂等
        thinkLedOn = thinkingNow;
        if (thinkingNow) {
            thinkLedEnters++;
            ledWrite(THINK_COLOR + "." + THINK_ACTION, true);
            thinkLedWrites++;
            thinkLedLast = listenLedLast;
            BootLog.i("思考灯 亮（g.on）-> " + listenLedLast);
        } else {
            // ★ 收场口径（用户 2026-10-11 选「恢复成在听蓝灯」）：
            //   麦闸还开着 ⇒ 亮回蓝灯（"轮到你说"的信号不丢）；否则全灭。
            if (micGateOpen && !(ledSpeakOff && ttsSpeakingNow)) {
                listenLed(true, true);
            } else {
                ledWrite("all.off", false);
            }
            BootLog.i("思考灯 灭 -> 恢复（micGate=" + micGateOpen + "）: " + listenLedLast);
        }
    }

    /** GET /listenled 里思考灯那一段（自述 + 观测）。 */
    public static String thinkLedJson() {
        return "\"think_on\":" + thinkLedOn
                + ",\"think_led\":\"" + J.esc(THINK_COLOR + "." + THINK_ACTION) + "\""
                + ",\"think_enters\":" + thinkLedEnters
                + ",\"think_writes\":" + thinkLedWrites
                + ",\"think_last\":\"" + J.esc(thinkLedLast) + "\"";
    }

    /**
     * 灯的**状态决策**（v206 抽成纯函数，无副作用、不吃 Context）。
     *
     * <p>★ 为什么抽出来：真机上"灯到底该是什么颜色"由三个布尔共同决定
     * （思考中 / 在听 / 在说话），三者组合有 8 种情况，靠人眼在真机上穷举
     * 既慢又容易看漏（尤其"说话+思考同时成立"这种一闪而过的窗口）。
     * 抽成纯函数后，8 种组合全部可在**桌面 JVM**上断言 —— 这就是本项目
     * 「新引擎先配没硬件也能跑的考卷」那条规矩。</p>
     *
     * <p>优先级：<b>思考中(绿) &gt; 在听(蓝) &gt; 灭</b>；
     * 但"说话期间不亮听灯"（{@code ledSpeakOff}）仍然管着**听灯**那一支 ——
     * 它管不住思考灯，因为思考是"机器人自己忙"，跟"我说完了轮到你说"不是一回事。</p>
     *
     * @return {@code "g.on"} / {@code "b.on"} / {@code "all.off"}
     */
    static String ledState(boolean thinking, boolean listening, boolean speaking, boolean speakOff) {
        if (thinking) return THINK_COLOR + "." + THINK_ACTION;   // 绿：机器人自己在忙
        if (listening) {
            boolean suppressed = speakOff && speaking;            // v178：说话期间不亮听灯
            return suppressed ? "all.off" : "b.on";
        }
        return "all.off";
    }

    /**
     * v206 灯语自检（**纯静态**，不联网、不吃 Context、不碰 org.json）。
     *
     * <p>把 8 种布尔组合逐一断言，另加一条"用户口径"断言：思考结束且麦闸开着
     * ⇒ 回到蓝灯（不是全灭），这是用户 2026-10-11 明确选的收场方式。</p>
     */
    public static String ledSelftest() {
        StringBuilder log = new StringBuilder();
        int n = 0, ok = 0;
        // 组合穷举：没硬件也能跑
        String[][] cases = {
            // thinking, listening, speaking, speakOff, 期望
            {"false","false","false","true",  "all.off"},
            {"false","true", "false","true",  "b.on"},
            {"false","true", "true", "true",  "all.off"},   // v178：说话期间不亮听灯
            {"false","true", "true", "false", "b.on"},      // speakOff 关掉 ⇒ 照亮
            {"true", "false","false","true",  "g.on"},      // ★ v206 思考灯
            {"true", "true", "false","true",  "g.on"},      // ★ 思考优先于听
            {"true", "true", "true", "true",  "g.on"},      // ★ 说话也压不掉思考灯
            {"true", "false","false","false", "g.on"},
        };
        for (String[] c : cases) {
            String got = ledState(B(c[0]), B(c[1]), B(c[2]), B(c[3]));
            n++;
            if (got.equals(c[4])) ok++;
            else log.append("  ✗ 灯状态(think=").append(c[0]).append(",listen=").append(c[1])
                    .append(",speak=").append(c[2]).append(",off=").append(c[3])
                    .append(") 期望 ").append(c[4]).append(" 得到 ").append(got).append("\n");
        }
        // 收场口径：思考结束、麦闸还开着 ⇒ 必须回到蓝灯（不许停在全灭）
        {
            String after = ledState(false, true, false, true);
            n++; if ("b.on".equals(after)) ok++;
            else log.append("  ✗ 思考结束后未恢复「在听」蓝灯（得到 ").append(after).append("）\n");
        }
        // 收场口径：思考结束、麦闸关着 ⇒ 必须全灭（不许留下绿灯）
        {
            String after = ledState(false, false, false, true);
            n++; if ("all.off".equals(after)) ok++;
            else log.append("  ✗ 思考结束后麦闸关着却留着灯（得到 ").append(after).append("）\n");
        }
        // 灯语常量与白名单一致性：颜色/动作必须是 SdkController 认得的
        {
            n++; if ("g".equals(THINK_COLOR) && "on".equals(THINK_ACTION)) ok++;
            else log.append("  ✗ 思考灯常量不是 g.on\n");
        }
        return "思考灯自检 " + ok + "/" + n + (ok == n ? " PASS" : " FAIL(" + (n - ok) + ")") + "\n"
                + "优先级：思考中(g.on) > 在听(b.on) > 灭；说话灭听灯(v178)只作用于听灯那一支\n"
                + log;
    }

    private static boolean B(String s) { return "true".equals(s); }

    /** 听语灯当前是否该亮（观测用；{@link #listenLed} 的幂等判据）。
     *  ★ static：{@code GET /listenled} 要跨类读它，验收时这是唯一可观测的灯语状态。 */
    static volatile boolean ledListenOn = false;

    /** 最近一次灯语下发的回执（观测用，见 {@code GET /listenled} 的 last 字段）。 */
    static volatile String listenLedLast = "";

    /**
     * 采音期间**补灯**的实际下发次数（观测用）。
     *
     * <p>为什么单独立一个计数：补灯是"对付原厂周期灭灯"的补救措施，如果它因为
     * 心跳没挂上、或回调抛异常被吞掉而**从未执行**，外观上跟"原厂灭灯太勤"一模一样
     * —— 都是"灯中途黑了"。没有独立读数就只能靠猜。于是把它做成
     * {@code GET /listenled} 的 {@code keeper} 字段：长会话里它必须往上涨。</p>
     */
    public static volatile int ledKeeperN = 0;

    // ==================== ★★★ v178：说话期间不亮蓝灯 ====================
    //
    // 【用户口径（2026-10-06）】「机器人在说话的时候不亮蓝灯」。
    //
    // 【为什么这是一条"判据"而不是一次"打补丁"】
    //   蓝灯的语义是「机器人在听」（v167）。但"听"其实有两段：
    //     ① 等你说话（该亮）        ② 边播边听你的打断（barge-in，麦也开着 —— 不该亮）。
    //   原来两段共用同一个麦闸信号，于是 ② 也亮着，用户看到的就是
    //   "它一边说话一边亮蓝灯"。
    //
    // 【实现】亮灯 = 麦闸开着 **且** 不在说话（拦截写在 listenLed 顶部）。
    //   两个真值都收在这里，不散到各调用点：
    //     · micGateOpen     ← 麦闸 VoiceWakeController.beforeAsrMic/afterAsrMic
    //     · ttsSpeakingNow  ← TtsController.speak() 的起止（所有播报的唯一出口）
    //
    // 【开关】prefs led_speak_off（默认 true）。观感类参数不写死 —— 写死就得
    //   重打包装机（本机装包还得手工 am startservice），一条 POST 就能改。

    /** 说话期间禁止亮听语灯（默认开）。 */
    static volatile boolean ledSpeakOff = true;

    /** 麦闸是否真的开着（此刻有 ASR 在听）。 */
    static volatile boolean micGateOpen = false;

    /** 是否正在说话（TtsController.speak() 起止时置位）。 */
    static volatile boolean ttsSpeakingNow = false;

    /**
     * 麦闸开/关 —— {@link VoiceWakeController#beforeAsrMic()}/{@code afterAsrMic()} 的唯一入口。
     *
     * <p>★ 关麦一律 {@code force}（原厂可能已把它点亮，"确保灭"才是要的）；
     * 开麦不 force —— 说话期间 {@link #listenLed} 会把它挡掉，这正是想要的。</p>
     */
    public static void setMicGate(boolean open) {
        micGateOpen = open;
        listenLed(open, !open);
        listenFace(open, !open);      // ★ v182：表情与灯同一个信号源
    }

    /**
     * 说话状态变化 —— {@link TtsController#speak(String, boolean)} 起止各调一次。
     *
     * <p>开说 → 立刻灭（不管麦闸开没开，{@code /say}、触摸反应也都算"在说话"）；
     * 说完 → 若麦闸还开着（例如防打断后的续听窗口）就把灯亮回来，
     * 让「轮到你说」这个信号不丢。</p>
     */
    public static void noteTtsSpeaking(boolean speakingNow) {
        ttsSpeakingNow = speakingNow;
        if (!ledSpeakOff) return;
        if (speakingNow) {
            listenLed(false, true);
        } else if (micGateOpen) {
            listenLed(true, true);
        }
        // ★ v182：表情与灯走**同一个信号**（别另立一套判据，见 listenFace 的类注释）
        if (speakingNow) {
            listenFace(false, true);
        } else if (micGateOpen) {
            listenFace(true, true);
        }
    }

    // ==================== ★★★ v182：听语表情 =「两个麦克风」 ====================
    //
    // 【用户口径（2026-09-27）】「机器人有一个面部是两个麦克风，机器人在听说话的可以播放
    //   这个表情，听完后结束」。真机确认：那个表情就是 `res/raw/recording.mp4`
    //   （两个麦克风 + 声波，见 _faces/recording.png）。
    //
    // 【为什么与听语灯共用同一套闸门，而不是另写一份】
    //   要"播脸/亮灯"的路有四条：① 麦闸 beforeAsrMic ② barge-in 开麦
    //   ③ 采音心跳补灯 CloudAsr.micTick ④ 端点手动。灯那一侧已经付过学费 ——
    //   逐处打补丁必漏（本项目老毛病「修在一个地方 ≠ 修好了」）。
    //   所以判据只写在 listenFace 一处，并与 listenLed **并排挂在同一批调用点**上。
    //
    // 【与灯唯一的实质差别：幂等判据必须是"脸上现在是谁"】
    //   灯只有亮/灭，一个布尔就够。脸不行：播报时 `[表情:happy1]` 会**把我们的浮层换掉**。
    //   若还相信内部布尔，下一轮开麦就不会再播（以为"还亮着"）⇒ 听着的时候脸上挂着
    //   上一句的 happy1。所以脸的幂等一律比 `FaceController.getCurrent()`。
    //
    // 【开关】prefs `listen_face`（默认 recording；填 `off` = 不播脸）。
    //   与 led_listen_mode 同理：观感类参数不写死 —— 写死就得重打包装机。

    /** 听语表情默认 =「两个麦克风」那个视频。 */
    public static final String LISTEN_FACE_DEFAULT = "recording";

    /** 此刻脸上是不是"在听"的表情（观测用）。 */
    static volatile boolean faceListenOn = false;
    /** 最近一次播脸的的回执（观测用）。 */
    static volatile String listenFaceLast = "";
    /** 播脸的实际下发次数（观测：长会话里必须随开麦次数上涨，否则就是"挂错了地方"）。 */
    static volatile int faceListenN = 0;

    public static void listenFace(boolean on) { listenFace(on, false); }

    /**
     * 播/收「机器人在听」的表情 —— <b>唯一出口</b>，与 {@link #listenLed} 并排。
     *
     * @param force 无视幂等强制收一次（关键动作；见 {@link #listenLed(boolean, boolean)}）
     */
    public static void listenFace(boolean on, boolean force) {
        FaceController f = face;
        if (f == null) return;
        // ★ 同一条闸：说话期间不播"在听"的脸 —— 否则它会盖住 [表情:happy1] 那种答复表情
        if (on && ledSpeakOff && ttsSpeakingNow) {
            on = false;
            force = true;
        }
        String name = LISTEN_FACE_DEFAULT;
        try {
            name = getPrefs().getString("listen_face", LISTEN_FACE_DEFAULT);
        } catch (Throwable ignored) {}
        if (name == null || name.isEmpty()) name = LISTEN_FACE_DEFAULT;
        if ("off".equalsIgnoreCase(name)) { faceListenOn = false; return; }
        final String want = FaceController.normalize(name);
        if (want.isEmpty()) { faceListenOn = false; return; }
        String cur = f.getCurrent();                 // ★ 看真实状态，不看我们自己记的布尔
        if (!on) {
            faceListenOn = false;
            if (want.equals(cur)) {                  // 只在"脸上确实是它"时才收，别把答复表情抹掉
                try {
                    f.hide();
                    listenFaceLast = "faceoff -> " + cur;
                    BootLog.i("听语表情 收（原来在播 " + cur + "）");
                } catch (Throwable t) {
                    listenFaceLast = "faceoff -> 异常 " + t;
                    BootLog.w("听语表情 收失败: " + t);
                }
            }
            return;
        }
        if (want.equals(cur)) { faceListenOn = true; return; }   // 已经在播 ⇒ 什么都不做（幂等）
        faceListenOn = true;
        faceListenN++;
        try {
            String r = f.play(name);
            listenFaceLast = name + " -> " + r;
            BootLog.i("听语表情 播（" + name + "）-> " + r);
        } catch (Throwable t) {
            listenFaceLast = name + " -> 异常 " + t;
            BootLog.w("听语表情 失败: " + t);
        }
    }

    /** 取本服务的 prefs（listenLed/listenFace 共用同一个文件）。 */    private static android.content.SharedPreferences getPrefs() {
        AgentService svc = I;
        if (svc == null) throw new IllegalStateException("service null");
        return svc.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * ★★★ v178：载入两个观感开关（默认都开）。
     *
     * <p>{@code led_speak_off}：说话期间不亮蓝灯 —— 用户 2026-10-06 的口径。</p>
     * <p>{@code auto_followup}：播完自动续听 N 轮 —— 同一轮的要求。</p>
     *
     * <p>★ 与 {@code led_listen_mode} 一样放进 prefs：这类参数是**观感**，
     * 真机上要人眼看，写死就意味着每调一次都得重打包装机。</p>
     */
    static void loadV178Prefs(Context ctx) {
        try {
            android.content.SharedPreferences p =
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            ledSpeakOff = p.getBoolean("led_speak_off", true);
            autoFollowup = p.getBoolean("auto_followup", true);
            liveMode = p.getBoolean("live_mode", true);
            liveIdleEvery = p.getInt("live_idle_every", 3);
            liveIdleMotionEvery = p.getInt("live_idle_motion_every", 6);
            liveIdleDeg = p.getInt("live_idle_deg", 5);
        } catch (Throwable ignored) {}
        BootLog.i("v178 开关: 说话灭蓝灯=" + ledSpeakOff + " 播完自动续听=" + autoFollowup
                + " 活泼模式=" + liveMode + " 待机微动每 " + liveIdleEvery + " 拍"
                + " 待机动作每 " + liveIdleMotionEvery + " 拍/" + liveIdleDeg + "度");
    }

    // ==================== ★★★ v180：活泼化 ====================
    //
    // 【用户口径（2026-10-06）】「给机器人整活泼点，表情动作都用起来」。
    //
    // 【为什么拆成三个时刻，而不是"加一堆动画"】
    //   "活泼"不是一个动作，是三个可分别验收的时刻：
    //     ① 唤醒时  —— 有人叫它，要给一个**看得见的回应**（表情+动作+蓝灯）；
    //     ② 应答时  —— 说话的表情要跟着内容走（走既有的 [表情:] 标签通道，
    //                   AI 没给标签时补一个兜底，免得"说话时脸是死的"）；
    //     ③ 待机时  —— 没人理它也要"活着"（低频换表情），否则看着像关机了。
    //   三个时刻分头验收，任何一档坏了都能单独看出来 —— 混成"一个活泼功能"，
    //   坏了只知道"不活泼"，没法归因（本项目老毛病）。
    //
    // 【硬约束：不许新增线程/闹钟】
    //   用户 09-25 明令（MT6580 + 1GB 扛不住）。所以待机那一档**复用唤醒引擎
    //   本来就有的 8 秒自检心跳**（VoiceWakeController.startBeat），零边际成本。
    //
    // 【观感类参数一律走 prefs + 端点，不写死】
    //   写死就得重打包装机 —— 这条在本机已被反复验证过。

    /** 活泼总开关（prefs {@code live_mode}，默认开）。 */
    public static volatile boolean liveMode = true;

    /** 待机微动：每 N 次心跳动一下（默认 3 ⇒ 约 24 秒一次）。 */
    static volatile int liveIdleEvery = 3;

    private static volatile int liveIdleN = 0;
    private static volatile int liveIdleIdx = 0;

    /**
     * 心跳被调到的次数 / 真正播了表情的次数（观测用）。
     *
     * <p>★ 为什么要分两个数：这是 {@code ledKeeperN} 同一套教训 ——
     * "补灯"和"待机微动"都是**没有外部现象**的后台行为，如果它因为
     * 心跳没挂上 / 被哪道闸挡住而从没执行过，外观上跟"它就是不动"一模一样。
     * 两个数一起看就能定性：{@code beats 不涨} = 心跳没挂上；
     * {@code beats 涨、plays 不涨} = 被闸挡住了（正常，比如一直在说话）。</p>
     */
    public static volatile int liveBeats = 0;
    public static volatile int livePlays = 0;

    // ==================== ★★★ v186：待机动作 + 更丰富的待机表情 ====================
    //
    // 【用户口径（2026-10-06）】「机器人常开识别；没识别到就不对 api 发东西，识别到就发。
    //   待机表情多给一点，机器人添加待机动作。」「话筒表情就先暂时不要了。」
    //
    // 【为什么 v180 刻意不做待机动作，现在又做】
    //   v180 的注释写着"待机时的头部动作会和方位感知打架，两个来源同时下力矩有风险"。
    //   那条判断**没有被推翻**，只是用户明确要了 —— 所以做法不是"直接放开动作"，
    //   而是**把风险收窄**：只允许**手臂/招手类原地小幅动作**（白名单），
    //   绝不放进位移类（walk/run/turn*/sledding/roll*/flyMode），
    //   并加 30 秒去抖 —— 无人看管时机器人自己走起来是真的会摔的。
    //
    // 【频次与表情分开计数】
    //   表情可以勤（约 24s 一次），动作必须稀疏（默认约 48s 一次）。
    //   两个计数器分开，调表情不会顺带把动作也调勤（本项目「改一处带崩另一处」的老毛病）。

    /** 待机动作：每 N 次心跳做一个小动作（默认 6 ⇒ 约 48 秒一次）；0 = 关。 */
    public static volatile int liveIdleMotionEvery = 6;

    /**
     * 待机微动的**幅度**（度）—— 用户 2026-10-06：「待机动作幅度小一点，都只动 5 度左右」。
     *
     * <p>这是 j1/j2/j3 三个轴各自的**单次**增量；正负交替下发，所以长期不会漂。
     * 0 = 关掉待机微动（等价 {@code idle_motion_every=0}）。</p>
     */
    static volatile int liveIdleDeg = 5;
    /** 我们自己下发的"当前指令姿态"（角度域）。★ 不用读回当基准，理由见 idleMotion()。 */
    private static final int[] idlePose = {0, 0, 0};
    private static volatile boolean idlePoseInit = false;
    /** 正负交替的方向（每次取反）—— 没有定时器也能"围着一个位置轻轻摆"。 */
    private static volatile int liveIdleSign = -1;
    private static final Object IDLE_POSE_LOCK = new Object();

    /**
     * 最近一次「主人说话 / 被唤醒」的时间 —— <b>待机闸用它代替"麦克风开着"</b>（v186）。
     *
     * <p>★ 为什么必须换：v186 实测 —— 一开常识别（{@code /dialog {"continuous":true}}），
     * 待机表情/动作就**永久冻住**（100 秒里 {@code idle_plays} 恒 0）。真因是
     * {@link #micGateOpen} 在常识别下**几乎一直为真**（每轮 5 秒开麦听，间隙极短），
     * 而旧闸写的是"麦克风开着就不动"。那条语义在"听一次开一次"的旧模式下等价于
     * "对话中别动"，在常识别下却变成了"永远别动"。</p>
     *
     * <p>★ 换成"最近 20 秒没人说话才动"：对话真的在进行时（听到话 → 应答 → 播报）
     * {@code lastUserAt} 是新的 ⇒ 照样一动不动；纯粹在后台听的时候才活泼起来。</p>
     */
    static volatile long lastUserAt = 0L;
    /** 对话窗口：距上次"听到人说话"不足这么久，就认为对话在进行中，待机一动不动。 */
    static final long IDLE_QUIET_MS = 20000L;
    private static volatile int liveIdleMotionN = 0;
    private static volatile int liveIdleMotionIdx = 0;
    /** 待机动作**真正**下发了几次（观测用；与 livePlays 同理，用来区分"没挂上"和"被闸挡了"）。 */
    public static volatile int liveIdleMotionPlays = 0;
    public static volatile String liveIdleMotionLast = "";
    private static volatile long liveIdleMotionAt = 0L;

    /**
     * 待机动作白名单 —— <b>只放"站着做"的手臂/招手类动作</b>。
     *
     * <p>★ 这是一条安全约束，不是审美偏好：名单外的动作（尤其 `walk/run/turnLeft/turnRight/
     * sledding/leftRoll/rightRoll/flyMode/layStruggle`）会让机器人**真的位移或翻滚**，
     * 无人看管时可能摔下桌子。名字必须与 {@link MotionController#MOTIONS} 逐字一致。</p>
     *
     * <p>★ `hiRighthand` 放第一个是有依据的：它是 {@link #wakeFlourish} 已经在用的动作，
     * 每次唤醒都会播一次 —— 属于**本项目已验证过安全**的那一类。</p>
     */
    static final String[] IDLE_MOTIONS = {
            "hiRighthand", "hiLefthand", "beckon", "cheer", "comeon", "handshake",
            "liftHands", "stCheer", "stHiRighthand", "stHiLeftHand", "stLiftHands", "stStand"
    };

    /**
     * 待机表情池 —— 都是**轻、短、不吓人**的一张脸。
     *
     * <p>★ 刻意排除强情绪与"故障感"表情：`cry`/`rage1..3`/`angry1..3`/`terrified`/`scared`/
     * `shock`/`sad`/`worry`/`wronged`/`pity`/`regret`/`hate`（待机时突然哭一下比不动更糟），
     * `battery_low`/`net_off`/`off`/`lock`/`recording`（像报障）。
     * ★ 注：这里**不能**出现连写的星号斜杠，它会提前闭合注释块（v186 真踩过）。</p>
     */
    static final String[] IDLE_FACES = {
            "blink", "blink_around", "blink_nod", "look_around",
            "look_up", "look_up_left", "look_up_right", "look_down", "look_down_left",
            "smile", "happy1", "happy2", "happy3", "pleased", "like", "enjoy1", "enjoy2",
            "curious", "wink", "attention", "squint", "shy", "cool", "hello", "adore"
    };

    /**
     * 把两个池子导成 JSON 片段 —— <b>唯一真源</b>，供 {@code /live} 与 {@code /status} 共用。
     *
     * <p>★ 为什么不在这两处各写一份字面量：v180 的 {@code /live} 里就是硬编码了一份
     * 「idle_pool」，改了代码不改那串字面量，端点就会**对外撒谎**（报的还是旧池子）。
     * 这类"文档/接口与实现分叉"在本项目已犯过多次 ⇒ 一律从数组现算。</p>
     */
    static String idlePoolsJson() {
        StringBuilder b = new StringBuilder();
        b.append("\"idle_pool\":[");
        for (int i = 0; i < IDLE_FACES.length; i++) {
            if (i > 0) b.append(',');
            b.append('"').append(IDLE_FACES[i]).append('"');
        }
        b.append("],\"idle_motion_pool\":[");
        for (int i = 0; i < IDLE_MOTIONS.length; i++) {
            if (i > 0) b.append(',');
            b.append('"').append(IDLE_MOTIONS[i]).append('"');
        }
        b.append(']');
        return b.toString();
    }

    /**
     * 是否处在「对话进行中」的窗口里（最近 {@link #IDLE_QUIET_MS} 内听到过人说话）。
     *
     * <p>★ 这是 v186 待机闸的**唯一判据来源** —— 心跳、待机表情、待机动作三处都读它，
     * 避免"三处各写一份时间比较"那种必然分叉的写法（本项目老毛病）。</p>
     */
    private static boolean inConversation() {
        long t = lastUserAt;
        return t > 0 && (System.currentTimeMillis() - t) < IDLE_QUIET_MS;
    }

    /** 距上次"听到人说话"多少毫秒（-1 = 服务起来后还没听到过）。**观测用**。 */
    static long lastUserAgoMs() {
        long t = lastUserAt;
        return t <= 0 ? -1L : (System.currentTimeMillis() - t);
    }

    /** 此刻待机微动是否被"对话窗口"挡着（观测用；不用再去猜为什么不动）。 */
    static boolean idleBlockedByTalk() {
        return inConversation();
    }

    /**
     * 待机微动 —— 由 {@link VoiceWakeController} 的 **8 秒自检心跳**调用。
     *
     * <p>★ 五道闸，缺一条就会变成"半夜自己动起来吓人"：
     * 正在说话 / 正在应答 / 对话正占麦 / 屏保中 / 频率没到 —— 任一成立就一动不动。</p>
     *
     * <p>★ v186 起这里**也做动作**了（用户明确要"添加待机动作"）。风险收窄方式：
     * 只播 {@link #IDLE_MOTIONS} 白名单里的**原地手臂/招手**动作（无位移类），
     * 且与表情**分开计数**、再叠 30 秒去抖。v180 那条"待机动作会和方位感知打架"
     * 的判断没有被推翻 —— 所以动作这一档刻意做得比表情**稀疏得多**。</p>
     */
    public static void idleBeat() {
        liveBeats++;
        // ★ v189：情绪推进就挂在这条心跳上（**不新增线程**，与待机微动同源）。
        //   放在早期闸之前 —— 心绪随时间回落/发呆是"内在状态"，不该被"正在说话"冻住。
        try { Emotion.tick(); } catch (Throwable ignored) {}
        // ★★★ v190：休眠（屏保）期间**连发呆都不要**（用户原话：「包括发呆」）。
        if (deepSleeping) return;
        if (!liveMode) return;
        if (ttsSpeakingNow) return;                 // 说话中：表情要跟着话走，别插队
        if (inConversation()) return;               // ★ v186：对话进行中（听到人说话 20s 内）别动
        if (SleepMode.sleeping) return;             // 屏保/休眠：不打扰
        AgentService s = I;
        if (s == null || s.answering.get()) return; // 应答链路进行中，别抢

        int every = Math.max(1, liveIdleEvery);
        boolean faceDue = (++liveIdleN % every) == 0;
        int mEvery = liveIdleMotionEvery;
        boolean motionDue = mEvery > 0 && (++liveIdleMotionN % mEvery) == 0;
        if (!faceDue && !motionDue) return;

        if (faceDue) {
            FaceController f = face;
            if (f != null) {
                // ★ v189：脸不再从一个固定池子挑，而是**从"当前情绪"的脸里挑**
                //   （再与待机安全白名单求交 —— 情绪可以自由，但待机时只摆轻的，
                //    免得"心情不好"就自己哭起来）。
                String[] pool = Emotion.facePoolWithin(IDLE_FACES);
                String name = pool[Math.abs(liveIdleIdx++) % pool.length];
                try {
                    String r = f.play(name, false);
                    livePlays++;
                    BootLog.i("活泼 待机微动 face=" + name + "（情绪=" + Emotion.current()
                            + "）-> " + r);
                } catch (Throwable t) {
                    BootLog.w("活泼 待机微动失败: " + t);
                }
            }
        }
        if (motionDue) idleMotion();
    }

    /**
     * 待机动作 —— <b>只做 ±N 度的小幅关节微动</b>（用户 2026-10-06：「待机动作幅度小一点，
     * 都只动 5 度左右」）。
     *
     * <p>★ 为什么不用 {@link MotionController#playByName} 那套命名动作：
     * 那些是原厂编排好的**整套**动作（{@code hiRighthand} 之类），幅度不可调，
     * 待机时看着"太闹"。用户要的是"像在轻轻换口气"级别的小动，所以改成
     * <b>直接下发一帧姿态</b>：在"当前指令姿态"的某一个轴上 ±{@link #liveIdleDeg} 度。</p>
     *
     * <p>★ 为什么不用电机**读回**当基准（{@code MotorPose.pos()}）：
     * {@link MotorPose#angleOf} 的注释写得很清楚 ——「不许拿它当控制回路，90° 以上误差可到十几度」。
     * 拿它当基准，"5 度"可能真的转成 15 度。所以这里维护一份**我们自己下发的**姿态
     * （{@link #idlePose}），只在第一次用读回做个大概初始化。</p>
     *
     * <p>★ 为什么**正负交替**（{@link #idleSign}）：没有定时器（本机禁止新增常驻线程），
     * 没法"动出去再动回来"。正负交替天然把位置**锁在基准附近摆动**，
     * 不会一路累积跑偏 —— 单次幅度就是 5 度，长期也不会漂。</p>
     */
    private static void idleMotion() {
        if (ttsSpeakingNow || inConversation() || SleepMode.sleeping) return;
        AgentService s = I;
        if (s == null || s.answering.get()) return;
        long now = System.currentTimeMillis();
        if (now - liveIdleMotionAt < 30_000L) return;       // ② 去抖
        MotorPose mp = motorPose;
        if (mp == null) return;

        int deg = liveIdleDeg;
        synchronized (IDLE_POSE_LOCK) {
            if (!idlePoseInit) {
                // 第一次：用读回做个"大概"的起点（只能用一次，之后一律以我们自己下发的为准）
                int[] rb = mp.pos();
                int[] lo = MotorPose.lo(), hi = MotorPose.hi();
                for (int i = 0; i < 3; i++) {
                    int a = MotorPose.angleOf(rb[i], i);
                    idlePose[i] = (a == Integer.MIN_VALUE) ? 0 : Math.max(lo[i], Math.min(hi[i], a));
                }
                idlePoseInit = true;
            }
            int axis = Math.abs(liveIdleMotionIdx) % 3;
            int sign = (liveIdleSign = -liveIdleSign);
            int[] lo = MotorPose.lo(), hi = MotorPose.hi();
            int target = idlePose[axis] + sign * deg;
            if (target < lo[axis]) target = lo[axis];
            if (target > hi[axis]) target = hi[axis];
            idlePose[axis] = target;
            liveIdleMotionIdx++;
            int[] cmd = {idlePose[0], idlePose[1], idlePose[2]};
            liveIdleMotionAt = now;
            try {
                String r = mp.drive(cmd[0], cmd[1], cmd[2], 420);   // 420ms：慢一点，不"抽"
                liveIdleMotionPlays++;
                liveIdleMotionLast = "j" + (axis + 1) + (sign > 0 ? "+" : "-") + deg
                        + "° → " + cmd[0] + "," + cmd[1] + "," + cmd[2] + " | " + r;
                BootLog.i("活泼 待机微动(小于" + deg + "度) " + liveIdleMotionLast);
            } catch (Throwable t) {
                liveIdleMotionLast = "异常 " + t;
                BootLog.w("活泼 待机微动失败: " + t);
            }
        }
    }

    // ==================== ★★★ v190：休眠（深睡） ====================
    //
    // 【用户口径（2026-10-06）】「长按头顶键进入休眠模式后停止机器人主程序与 agent，包括发呆。」
    //
    // 【落地成什么】
    //   ① 停**我们自己的活动**：常识别、唤醒引擎、TTS、动作、表情、感应 —— 最后一项
    //      交给 {@link SleepMode#gateEverything}（屏保本来就在做）；常识别与"发呆"是本轮新增。
    //   ② 冻**原厂主程序**：{@link ProcessFreezer}（SIGSTOP，可逆，不是 kill）。
    //   ③ 发呆：{@link #deepSleeping} 一置位，{@link #idleBeat()} 立刻什么都不做。
    //
    // 【★ 必须知情的代价：头顶键会按不醒】
    //   这台机的头顶键**不是**我们读输入设备，而是原厂发广播
    //   （`com.auric.intell.xld.rbt.key.head.down/up`，见 SensorHub 类注释）。
    //   所以把原厂主进程 SIGSTOP 之后，**没有人再发那条广播** ⇒ 长按叫不醒。
    //   恢复只能靠：HTTP `POST /sleep {"on":false}`（我们的进程还活着）或 adb 重启。
    //   v142 的注释早就写下了这个风险，这里只是把它变成用户明确要的行为。
    //   ⇒ 所以留了 {@code sleep_deep} 开关（`POST /deepsleep {"on":false}` 可退回"只冻子进程"）。

    /** 是否处于"深睡"（屏保 + 深睡开关）。true 期间待机表情/动作/发呆一律不做。 */
    public static volatile boolean deepSleeping = false;

    /**
     * 进入/退出深睡。**幂等**。由 {@link SleepMode#enter}/{@link SleepMode#exit} 调用。
     *
     * <p>★ 只负责"停我们自己的活动"；冻原厂在 SleepMode 里（它才知道当前冻结档）。</p>
     */
    public static void onDeepSleep(boolean on) {
        deepSleeping = on;
        if (!on) {
            BootLog.i("深睡 ★退出：待机微动/发呆恢复");
            // ★ v202：退出深睡时把常听恢复回来。进深睡时它是被**显式**停的（见下），
            //   若不在这里补回来，会出现"睡一觉起来它就不常听了"这种静默退化。
            //   只认 listen_auto（用户没让常开就别擅自开）。
            try {
                AgentService s = I;
                if (s != null && listenAuto && !s.contRunning) {
                    BootLog.i("深睡 退出，恢复常听: " + s.startContinuous());
                }
            } catch (Throwable t) { BootLog.w("深睡 恢复常听失败 " + t); }
            return;
        }
        // ① 常识别必须显式停 —— 挂起唤醒引擎**不会**停它（它是另一个循环）
        try {
            AgentService s = I;
            if (s != null && (s.contRunning || (voiceWake != null && voiceWake.isContinuous()))) {
                BootLog.i("深睡 停常识别: " + s.stopContinuous());
            }
        } catch (Throwable t) { BootLog.w("深睡 停常识别失败 " + t); }
        BootLog.i("深睡 ★进入：常识别已停 / 待机微动与发呆已停 / 原厂主程序将被冻结"
                + "（★ 头顶键在冻结期间不会上报，叫醒请用 HTTP /sleep 或 adb）");
    }

    /**
     * 唤醒时的"回应动作" —— 有人叫它，必须**看得见地**应一声。
     *
     * <p>★ 为什么要单独做：唤醒（不管走唤醒词、抹头顶还是 HTTP）之后机器人只是
     * "开始听"，屏幕上没有任何变化 —— 主人无从判断"它到底醒了没有"，
     * 体感就是"喊了没反应"。给一个打招呼的表情 + 挥手动作 + 蓝灯，
     * 这三样一起出现，"我醒了"才是一个**可观测**的事实。</p>
     *
     * <p>★ 只对"唤醒词/HTTP 强唤醒"用；抹头顶有它自己的一套（ReactionEngine），
     * 两套叠在一起会互相打断（本项目 v107 现场：动作被对方 abort 掉）。</p>
     */
    public static void wakeFlourish(String info) {
        if (!liveMode) return;
        lastUserAt = System.currentTimeMillis();    // ★ v186：被唤醒也算"人在跟它打交道"
        try { Emotion.onUserSpoke(); } catch (Throwable ignored) {}   // ★ v189
        try {
            FaceController f = face;
            if (f != null) f.play("wake", false);
        } catch (Throwable ignored) {}
        try {
            MotionController m = motion;
            // ★ 只用小幅度、短时的招呼动作；不用 walk/run 这类会真的走起来的。
            if (m != null) m.playByName("hiRighthand");
        } catch (Throwable ignored) {}
        BootLog.i("活泼 唤醒呼应 info=" + info + "（表情 wake + 动作 hiRighthand）");
    }

    /**
     * 应答兜底表情 —— 回复里**没有** {@code [表情:…]} 标签时补一个，免得脸是死的。
     *
     * <p>★ 为什么必须有兜底：大模型**不保证**每次都吐标签（实测相当一部分回复是纯文本），
     * 而"说话时脸一动不动"正是用户说的"不活泼"。兜底只补表情、不补动作：
     * 动作会真的产生力矩，不能由"模型没给标签"这种随机事件触发。</p>
     */
    public static String livelyFallbackFace(String reply) {
        if (!liveMode || reply == null || reply.isEmpty()) return reply;
        if (reply.contains("[表情:") || reply.contains("[face:")) return reply;
        String[] pool = {"happy1", "smile", "curious", "enjoy1", "attention", "happy2"};
        String name = pool[Math.abs(liveIdleIdx++) % pool.length];
        return "[表情:" + name + "]" + reply;
    }

    /**
     * 模式 → 原厂灯效组合（{@code "color.action"}）。
     * <p>★ 这里刻意做成**唯一一份**模式语义：点灯（{@link #listenLed}）与端点自检
     * （{@code GET /listenled}）都读它。两处各写一份判断，就是"改了一处、另一处没改"
     * 的经典土壤。</p>
     */
    public static String ledSpec(String mode) {
        if (LED_MODE_BLUE_BLINK.equals(mode)) return "b.blink.slow";
        if (LED_MODE_ALL_BLINK.equals(mode))  return "all.blink.fast";
        if (LED_MODE_OFF.equals(mode))        return "off";
        return "b.on";                        // 默认：蓝灯常亮（含未知值兜底）
    }

    /** 模式串是否合法（端点入参校验用）。 */
    public static boolean isLedMode(String m) {
        return LED_MODE_BLUE_ON.equals(m) || LED_MODE_BLUE_BLINK.equals(m)
                || LED_MODE_ALL_BLINK.equals(m) || LED_MODE_OFF.equals(m);
    }

    // （原 v167 早期版本有过一个 resetLedLatch()：换档时先把幂等锁掰回 false 再重下发。
    //   listenLed(on, force) 出来后它就没用了 —— 已删。本项目吃过"死方法"的亏
    //   （v69 现场：releaseMicForDialog 写好了却从没被任何地方调用，于是两个引擎
    //   抢同一个麦、进程被杀且不留 tombstone）。留着没人调的方法 = 等着下次踩。）

    private void answerLoop(long t0, String info) {
        String pending = "";                       // 打断时听到的话：直接当下一轮的输入
        for (int turn = 1; turn <= VoiceDialog.maxTurns; turn++) {
            String userText;
            // 本轮是不是"注入"出来的（远程验收）：注入轮没有真人，静音不能算"听不懂"。
            boolean injected = false;
            // ★★★ v178：远程验收注入首句（{@code POST /dialogturn {"text":"…"}}）。
            //   为什么要有它：整条对话链路的验收一直卡在"必须先有人喊唤醒词"上 ——
            //   离线 KWS 对房间混响敏感（十喊不一定中一次），于是真正要测的那一环
            //   （听 → AI → 播报 → **播完自动续听**）反而测不到。这与 /forcewake
            //   跳过 KWS 是同一个思路：把"唤醒"这个变量从验收里摘出去。
            //   只作用于第 1 轮，且**设一次用一次**（用完即清，免得下一次唤醒被塞旧话）。
            //   复用 pending 这条已有通道，不另开分支。
            if (turn == 1 && injectFirstSay != null && !injectFirstSay.isEmpty()) {
                pending = injectFirstSay;
                injectFirstSay = null;
                injected = true;
                BootLog.i("语音对话 注入首句（远程验收，跳过听）[" + pending + "]");
            }
            if (!pending.isEmpty()) {
                userText = pending;
                pending = "";
                BootLog.i("语音对话 第 " + turn + " 轮：用打断时听到的话 [" + userText + "]");
            } else if (VoiceDialog.enabled) {
                // ★★★ v142：一轮里最多听 N 次（N = Presence.missLimit，默认 3，用户要求 3-5），
                //   全都没听到就收工。旧写法**只听一次**，没听到就直接去问大模型
                //   （下面 turn==1 分支的 ai.ask("", ...)），于是真机账上 112 轮 voice
                //   只有 21 轮真听到内容 —— 其余全是白花的 token。
                //   用户要的是两件事同时成立：**要重试**（不然一次没听清就废），
                //   但**要有上限**（不然一直听、一直送，就是他们抱怨的那件事）。
                // ★★★ v181：重试次数**不再等于**离线门槛。
                //   旧写法 maxTries = missLimit，加上"每失败一次 noteMiss 一次"，
                //   使一次唤醒内部就记满 missLimit 次（实测 2026-09-27：一次静音唤醒
                //   → miss 0→3 → 当场进离线）。两个概念共用一个数，是这一串怪现象的根。
                int maxTries = Presence.retryMax(this);
                String got = "";
                for (int tries = 1; tries <= maxTries; tries++) {
                    if (SleepMode.sleeping) {                 // 屏保插队 => 立刻收摊，不恋战
                        BootLog.i("语音对话 屏保先生效，停止本轮听");
                        break;
                    }
                    long ta = System.currentTimeMillis();
                    // ★ v99：走"在线优先、离线兜底"的听句子入口（VoiceWakeController.listenSentence）。
                    //   老写法直接调 dialog().listenBlocking() —— 那条路是百度语法解码器，
                    //   只听得懂 45 条命令词，用户说"我想吃苹果"会一个字都不出。
                    //   接口契约与 listenBlocking 完全一致（没听清返回 ""），所以这里只换名字。
                    // ★★★ v167：灯语**不在这里**了 —— 已下沉到麦克风闸
                    //   （VoiceWakeController.beforeAsrMic / afterAsrMic）。
                    //   v144 时它挂在这一处，于是**只有这条对话链路**会亮灯：
                    //   `/ilisten`、离线兜底（VoiceDialog.listenBlocking）、离线名字探测
                    //   全都不亮 —— 正是「修在一个地方 ≠ 修好了」。
                    //   现在挂在闸上，任何路径开麦都自动亮、收麦自动灭，这里只管听。
                    got = voiceWake == null ? ""
                            : voiceWake.listenSentence(VoiceDialog.maxListenMs + 1500L);
                    BootLog.i("语音对话 第 " + turn + " 轮 第 " + tries + "/" + maxTries
                            + " 次听：[" + got + "] 耗时="
                            + (System.currentTimeMillis() - ta) + "ms 路线="
                            + (voiceWake == null ? "?" : voiceWake.lastInputRouteNow()));
                    if (got != null && !got.trim().isEmpty()) break;
                    // ★ v181：**循环里不再记账**。旧写法每失败一次 noteMiss 一次，
                    //   于是"一轮"被记成"3 次" —— 记账移到循环之后，见下。
                    if (tries < maxTries) {
                        try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                    }
                }
                userText = got == null ? "" : got;
                // ★★★ v181：一轮听下来（含全部重试）只记**一次** miss。
                //
                //   判据语义：miss = 「**在等主人说话时**没听清」。
                //     ① turn>1 = 这一轮是**机器人自己**开的（v178 自动续听），主人没欠它一句；
                //     ② injected = 远程验收注入的轮次，现场**根本没人**。
                //   两种都不记（v180 已修）。第 1 轮真人唤醒才记 —— 那时主人在等它答，
                //   听不清确实值得降级省配额。
                //
                //   ★ 但"记"的**粒度**必须是一次**唤醒**，不是一次**重试**：
                //     同一瞬间连试 3 次，是同一个事件被采样了 3 次。
                //     旧写法把这一件事记成 3 件 ⇒ miss_limit=3 实际等于"一次唤醒"、
                //     机器人被屋里噪声误唤醒一次就进离线（2026-09-27 wakecost 实测：
                //     miss 0→3、offline=true、日志「第 1 轮重试耗尽…（离线模式=true）」）。
                //     拆开后门槛回到用户原话：「3-5 次（唤醒）都没听到，才进离线」。
                if (!userText.isEmpty()) {
                    Presence.noteHeard(this, userText);
                } else if (turn == 1 && !injected) {
                    String why = "第1轮 " + maxTries + " 次都没听到";
                    boolean wentOffline = Presence.noteMiss(this, why);
                    if (!wentOffline) {
                        BootLog.i("语音对话 第 1 轮 " + maxTries + " 次都没听到 → 只记 1 次"
                                + "「听不懂」（" + Presence.missStreakNow() + "/"
                                + Presence.missLimit(this) + "，未进离线）");
                    }
                } else {
                    BootLog.i("语音对话 第 " + turn + " 轮 " + maxTries + " 次都没听到 —— "
                            + "这是我自己开的续听轮，不计入「听不懂」判据"
                            + "（主人没接话 ≠ 听不懂）");
                }
            } else {
                userText = "";
            }

            // 声源定位：主人刚从哪个方位说话，就先把头转过去看他（程序化回应），
            // 同时把方位写进给 AI 的提示里，让它知道该朝谁答话。
            String dir = locateSpeaker();
            // ★ v93：用户原话走 askVoice 的 user 轮，旁白（VOICE_HINT / 方位）走 preface。
            //   旧写法把原话塞进 system 的"当前状态"里，大模型当成环境而不是"主人对我说的话"，
            //   而且多轮记忆里根本存不到用户说了什么（详见 AiCore.askVoice 的注释）。
            String reply;
            String heard = userText == null ? "" : userText;
            String note = null;
            IntentResolver.Act local = null;
            if (!heard.isEmpty()) {
                if (!dir.isEmpty() && !"正前".equals(dir)) {
                    String m = locator.lookMotion();
                    if (!m.isEmpty()) {
                        BootLog.i("声源定位 主人在" + dir + "，先转头看他：" + m);
                        motion.motion(m);
                    }
                }
                // ★ v94：先看本地直通表 —— 「停止」「电量」「声音小一点」这类意图唯一的命令
                //   不走大模型（离线也能用，且快）。不命中才带着"易混候选"去问 AI。
                local = IntentResolver.local(heard);
                if (local != null) {
                    IntentResolver.runAct(local);          // ← 判定与执行分开：这里才真的动
                    reply = local.reply;
                    note = local.note;
                    IntentResolver.remember(heard, "local", local.note, null);
                    BootLog.i("语音对话 本地直通 " + local.note + " → " + reply);
                } else {
                    List<String> near = IntentResolver.near(heard, 3);
                    IntentResolver.remember(heard, "ai", null, near);
                    // ★ v202：统一入口 —— thinkOn 时走多轮思考，否则单轮（见 replyForAI）
                    reply = replyForAI(heard, VOICE_HINT + IntentResolver.correctionHint(heard), dir);
                }
            } else if (turn == 1) {
                // ★★★ v142：**没听到就不问了**。
                //
                // 旧写法 `reply = ai.ask("", IntentResolver.missHint())`：喊了一声、
                // 一句话没说，机器人照样发一次大模型请求、再拿回复去播报。
                // 真机账（ledi_talk.jsonl，2026-09-22~24）：via=voice 112 轮里只有 21 轮
                // ask 非空；engine=cloud 的 45 轮**全部**是空转上云（换回一句 10118）。
                // 用户原话：「没有用户希望机器人在不使用的时候一直向上传递语音消息消耗 API token」。
                // 上面的循环已经在本机重试了 N 次，全失败 => 到此为止，一次请求都不发。
                IntentResolver.remember("", "none", "未识别到内容（已重试，未发请求）", null);
                BootLog.i("语音对话 第 1 轮重试耗尽，没听到任何话 -> 不发大模型请求，收工"
                        + "（离线模式=" + Presence.offline + "，详见 /presence）");
                break;
            } else {
                BootLog.i("语音对话 第 " + turn + " 轮没人说话，收工");
                break;
            }

            BootLog.i("语音对话 应答=[" + reply + "] 累计耗时="
                    + (System.currentTimeMillis() - t0) + "ms");
            if (reply == null || reply.trim().isEmpty()) {
                BootLog.w("语音对话 AI 无回复: " + ai.lastError());
                // 没答上也要留账：否则"机器人哑了一次"在双端日志里查无此事
                ai.pushDialog("voice", heard, "", "", dir, null, null,
                        "AI 无回复: " + ai.lastError(),
                        System.currentTimeMillis() - t0);
                break;
            }
            String spoken = router.applyAiTags(reply);   // 执行表情/动作/灯光并剥离标签
            BootLog.i("语音对话 播报文本=[" + spoken + "]");
            // 对话日志：本地落一份 + 推给电脑端控制台（同一个 tid，可对照）。
            // 不管这次是直连答的还是网关答的，电脑端都要能看见 —— 直连模式下控制台本来是完全瞎的。
            // ★ note 带"本地直通/易混候选"，engine 按实际路线给（本地直通根本没发给大模型，
            //   记成 direct 就是假账）—— 双端对照表是拿来查账的，不能有假行。
            // ★ v99：note 里再带上「谁听懂的」（cloud / cloud-empty / offline）——
            //   否则一串对话里分不清哪句是在线听懂、哪句其实是回落到离线那 45 条。
            String inRoute = voiceWake == null ? "" : voiceWake.lastInputRouteNow();
            String note2 = (note == null || note.isEmpty()) ? "" : note + " | ";
            if (!inRoute.isEmpty() && !"none".equals(inRoute)) note2 = note2 + "听=" + inRoute;
            ai.pushDialog("voice", heard, reply, spoken, dir, null, null,
                    note2.isEmpty() ? null : note2,
                    System.currentTimeMillis() - t0,
                    local != null ? "local" : null);
            if (spoken.isEmpty()) {
                BootLog.w("语音对话 播报文本为空（AI 只返回了标签/空白，没有说话内容）");
                break;
            }
            String barge = speakWithBargeIn(spoken);
            if (barge != null && !barge.isEmpty()) {
                pending = barge;
                continue;                            // 被打断 → 用打断时听到的话接着聊
            }
            // ★★★ v178：**播完自动续听**（用户 2026-10-06 要求）。
            //
            //   旧写法这里直接 break ⇒ 唤醒一次只聊一轮（除非你在它说话时插嘴）。
            //   用户要的是「说完话后自动拉起对话，可连续聊 N 轮」⇒ 播完没被打断，
            //   只要还没到 N 轮、也没进屏保，就 continue 开下一轮听。
            //
            //   为什么不会无限聊：下一轮若没人说话，turn>1 的分支会 break（收工），
            //   见下面 `else { 第 N 轮没人说话，收工 }`；N 由 VoiceDialog.maxTurns 封顶
            //   （默认 3，端点 `POST /dialog {"turns":N}` 可调，范围 1..8）。
            //   开关：prefs auto_followup（默认 true）。
            if (autoFollowup && turn < VoiceDialog.maxTurns && !SleepMode.sleeping) {
                BootLog.i("语音对话 播报结束且未被打断 → 自动续听第 " + (turn + 1)
                        + "/" + VoiceDialog.maxTurns + " 轮（蓝灯=该你说了；说「停」可退出）");
                continue;
            }
            break;
        }
        BootLog.i("语音对话 结束 info=" + info + " 总耗时=" + (System.currentTimeMillis() - t0) + "ms");
    }

    /**
     * 播报一句话，<b>同时听着</b>：有人开口就立刻掐掉播报，把听到的内容返回。
     *
     * <p>两道防自激的闸（详见 {@link VoiceDialog} 的类注释）：
     * ① 起播 700ms 内的活动一律忽略；② 识别出的片段若包含在正在播的句子里，
     * 判为"它听见了自己的喇叭"，不算打断。机器人本身有硬件 AEC，这两闸是兜底。</p>
     *
     * @return 打断时听到的文本；没人打断返回 null
     */
    private String speakWithBargeIn(final String spoken) {
        SleepMode.touch();                    // 播报 = 正在使用，重置闲置计时
        if (voiceWake == null || !VoiceDialog.enabled) {
            long t1 = System.currentTimeMillis();
            String used = router.tts().speak(spoken, false);
            BootLog.i("语音唤醒 ★播报结束 engine=" + used + " 耗时="
                    + (System.currentTimeMillis() - t1) + "ms");
            return null;
        }
        final VoiceDialog d = voiceWake.dialog();
        final Object lock = new Object();
        final String[] got = new String[]{null};         // null=没打断；""=听到但没解出；正文=打断内容
        final long t1 = System.currentTimeMillis();
        final boolean[] barged = new boolean[]{false};
        VoiceDialog.Listener l = new VoiceDialog.Listener() {
            @Override public void onSpeech(String partial) {
                if (System.currentTimeMillis() - t1 < 700) return;        // 闸①：起播保护
                if (partial == null || partial.length() < 2) return;
                if (spoken.contains(partial)) return;                     // 闸②：它听见了自己
                synchronized (lock) {
                    if (!barged[0]) {
                        barged[0] = true;
                        BootLog.i("语音对话 ★被语音打断（听到 [" + partial + "]），掐掉播报");
                        try { router.tts().stopNow(); } catch (Throwable ignored) {}
                    }
                }
            }
            @Override public void onFinal(String text) {
                synchronized (lock) { got[0] = text == null ? "" : text; lock.notifyAll(); }
            }
            @Override public void onError(String why) {
                synchronized (lock) { got[0] = ""; lock.notifyAll(); }
            }
        };
        boolean ok = d.start(l);
        if (!ok) {                                        // 听不了就别耽误说话
            String used = router.tts().speak(spoken, false);
            BootLog.i("语音唤醒 ★播报结束（无法边听）engine=" + used + " 耗时="
                    + (System.currentTimeMillis() - t1) + "ms");
            return null;
        }
        try {
            String used = router.tts().speak(spoken, false);   // 同步播完（或被上面的 stopNow 掐断）
            BootLog.i("语音唤醒 ★播报结束 engine=" + used + " 耗时="
                    + (System.currentTimeMillis() - t1) + "ms");
        } catch (Throwable t) {
            BootLog.w("语音对话 播报异常: " + t);
        }
        // 播完之后再等一小会儿：主人可能紧接着接话（这就是连续对话）。
        // 已经被打断的话，等它把这句听完（reaper 会按"静音 1 秒"收尾）。
        synchronized (lock) {
            long until = System.currentTimeMillis()
                    + (barged[0] ? VoiceDialog.maxListenMs : FOLLOWUP_WAIT_MS);
            while (got[0] == null && System.currentTimeMillis() < until) {
                try { lock.wait(Math.max(60, until - System.currentTimeMillis())); }
                catch (InterruptedException ignored) { break; }
            }
        }
        d.stop();
        String r = got[0];
        if (r == null) return null;
        return r;
    }

    // ============================================================ 常识别（v75）

    /**
     * 常识别：<b>不等唤醒词，一直听</b>；机器人说话期间暂停听。
     *
     * <p>用户的需求原话是「先尝试机器人的语音常识别启动，在机器人说话时暂停识别」。
     * 拆开是两个约束：</p>
     * <ul>
     *   <li><b>常听</b>：不再要求"先喊哈喽乐迪"。唤醒引擎（wp）和 ASR 共用这一个麦克风
     *       入口，所以开常识别 = <b>停 wp</b>（{@link VoiceWakeController#setContinuous}），
     *       两者是互斥的两种听法。</li>
     *   <li><b>说话时暂停</b>：播报期间不识别。判断信号取
     *       {@code TtsController.isSpeaking()}（所有播报的唯一出口），
     *       所以对话、主动说话、触摸反应、电脑端 {@code /say} 都会自动让路。</li>
     * </ul>
     *
     * <p>与"应答链路"（{@link #answerLoop}）的分工：那条是「唤醒一次聊几轮」，
     * 每轮都要重新听；这条是「一直听，听到就答」。两条共用 {@code answering}
     * 互斥量，任何时候只有一条在跑。</p>
     */
    private volatile boolean contRunning;
    private Thread contThread;
    private volatile int contHeard;
    private volatile int contEmpty;
    private volatile int contErr;
    /** 常识别每轮最多听多久。比应答链路短，收尾更勤、反应更快。 */
    private static final long CONT_LISTEN_MS = 5000;
    /**
     * ★ v217：常识别每轮**本地门控**的探测时长（零云开销，见 {@link CloudAsr#hasSpeech}）。
     * 判到持续人声才真正走云；没人声连会话都不建 —— 守住"不用的时候别一直往上送"。
     */
    private static final long CONT_GATE_MS = 2600;
    /**
     * ★★ v220：播报结束后"等房间安静"的**最长**等待（自适应判据见
     * {@link CloudAsr#waitQuiet}）。
     *
     * <p>来历：v218 试过固定 800ms 沉降，<b>挡不住</b> —— v219 真机日志显示喇叭余响在
     * {@code isSpeaking()} 转 false 之后还能持续约 2.6s，且到麦 {@code rms≈20560~20790}
     * 与真人语音（20800）同量级。两次实测都在"播报结束 + 2.64s"处抓到
     * {@code 门控命中 max≈20600}，随后云端 10118。固定值要么不够、要么白等，
     * 所以改成"连续采样到安静为止"：安静房间约 0.4s 就过，吵房间超时后照判。</p>
     */
    private static final long CONT_SETTLE_MS = 2500;
    /** 机器人在说话时的退避间隔。 */
    private static final long CONT_SPEAK_BACKOFF_MS = 120;

    private static void nap(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ==================== ★★★ v202：常听持久化 + 多轮思考开关 ====================
    //
    // 【用户口径（2026-10-10）】「让机器人长期主动听用户」，选的是「开机自动开 + 可关」。
    //
    // 【为什么必须落盘】常听（continuous）原本只是**进程内**状态：CrashGuard 把进程拉一次、
    //   或用户重启机器，它就没了 —— 用户看到的是"昨天还好用的常听，今天怎么不听了"，
    //   而且不报错、不提示。这里把它落盘，重启自动恢复。
    //     listen_auto    开机要不要自动开常听（默认 true）
    //     listen_grammar 识别用哪份语法（"free" 自由说 / "cmd" 命令词表；默认 free）
    //     think_on       语音链路是否走多轮思考（默认 false：开启=更强但更慢、更费 token）

    /** 开机是否自动开常听（prefs listen_auto）。 */
    static volatile boolean listenAuto = true;
    /** 语音链路是否走「多轮思考」（prefs think_on）。 */
    static volatile boolean thinkOn = false;
    /**
     * 语音链路是否走「workflow（先检索能力、再一步到位编排）」（prefs wf_on；v203）。
     *
     * <p>★ 默认 <b>true</b>：用户 2026-10-10 的口径是「**语音输入后机器人自己决定怎么执行**」
     * —— 也就是这条路径就该是默认行为，而不是要用户先去开一个开关。
     * 但它是<b>可关</b>的：关掉就退回 v202 的纯多轮思考/单轮。</p>
     *
     * <p>★ 与 {@link #thinkOn} 的关系：workflow 开启时**包含**多轮思考
     * （{@link #replyForAI} 里先试 think，think 里模型自己会 TOOL doc 检索）。
     * 两者不是"二选一"，而是"workflow 是 think 的超集"。</p>
     */
    static volatile boolean wfOn = true;

    /** 常听是否正开着（与 contRunning 同义，但可在静态上下文读 —— /status、工具 state 用）。 */
    public static boolean continuousOn() {
        AgentService s = I;
        return s != null && (s.contRunning || (voiceWake != null && voiceWake.isContinuous()));
    }

    /** v202 开关载入（只读 prefs + 应用语法，不碰引擎；必须在 voiceWake 建好之后调）。 */
    private void loadV202Prefs(Context ctx) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, MODE_PRIVATE);
            listenAuto = p.getBoolean("listen_auto", true);
            thinkOn = p.getBoolean("think_on", false);
            wfOn = p.getBoolean("wf_on", true);      // ★ v203 默认开
            // ★ v216：默认从 free 改成 cmd。
            //   实测（2026-10-11，同一份真人录音 PCM）：
            //     · free（不设语法）→ finish error #7 + err_no -3004 "app name unknwon"
            //       —— 离线**自由识别**需要在线授权，本机拿不到；这不是"引擎哑"。
            //     · 「唤醒词+命令词」语法 → 正确识别出 text="乐迪你好"（success）。
            //   所以常识别（走离线 VoiceDialog）必须用 cmd 档；历史遗留的 free 自动迁移。
            String g = p.getString("listen_grammar", "cmd");
            if ("free".equalsIgnoreCase(g) || "none".equalsIgnoreCase(g) || "off".equalsIgnoreCase(g)) {
                g = "cmd";
                try { p.edit().putString("listen_grammar", g).apply(); } catch (Throwable ignored) {}
                BootLog.w("v216 常听语法 free→cmd 迁移（free 在离线档必然失败，见 loadV202Prefs 注释）");
            }
            applyGrammar(g);
            BootLog.i("v202 开关: 开机自动常听=" + listenAuto + " 多轮思考=" + thinkOn
                    + " 常听语法=" + g + " | v203 workflow=" + wfOn);
        } catch (Throwable t) { BootLog.w("v202 开关载入失败 " + t); }
    }

    /**
     * 把 grammar 串应用到 {@link VoiceDialog} 并刷新意图层词表镜像。
     * <p>★ 换语法就是换"机器人能听懂什么"，{@link IntentResolver} 的词表镜像必须跟着重读，
     * 否则本地直通/易混候选还按旧词表判（静默错位，最难查）——同 {@code POST /dialog {"grammar"}}。</p>
     */
    static void applyGrammar(String g) {
        if (g == null) return;
        String s = g.trim().toLowerCase(java.util.Locale.ROOT);
        if ("cmd".equals(s)) VoiceDialog.grammarOverride = null;
        else if ("free".equals(s) || "none".equals(s) || "off".equals(s)) VoiceDialog.grammarOverride = "";
        else VoiceDialog.grammarOverride = g;
        try { IntentResolver.reload(); } catch (Throwable ignored) {}
    }

    /**
     * 语音链路取 AI 回复的**唯一入口**：按开关决定走 workflow / 多轮思考 / 单轮。
     *
     * <p>★ v203 起 workflow 是**超集**（用户口径：「语音输入后机器人自己决定怎么执行」）：
     * <pre>
     *   wfOn（默认开）    → 多轮思考 + 能力索引旁白（模型可 TOOL doc 自己查、TOOL workflow 一次编排）
     *   !wfOn && thinkOn  → v202 的纯多轮思考
     *   否则              → 单轮 askVoice（老行为）
     * </pre>
     * 任一层失败都**自动回退**下一层（宁可少想几步，也不能让机器人哑掉）。</p>
     *
     * <p>★ 这里刻意**不**另写一套"workflow 引擎"：能力检索与编排都发生在
     * {@link Thinking} 的同一套 TOOL 循环里（只是工具箱多了 doc/workflow 两个 op）。
     * 本项目的老毛病就是"同一个功能写两份实现、然后分叉"。</p>
     */
    static String replyForAI(String userText, String hint, String dir) {
        AiCore a = ai;
        if (a == null) return "";
        if (wfOn || thinkOn) {
            try {
                int rounds = wfOn ? Thinking.WF_ROUNDS : Thinking.DEFAULT_ROUNDS;
                String r = a.think(userText, rounds, Thinking.deviceToolBox(), null);
                if (r != null && !r.trim().isEmpty()) return r;
                BootLog.w((wfOn ? "workflow" : "多轮思考") + " 没产出，回退单轮: " + a.lastError());
            } catch (Throwable t) {
                BootLog.w((wfOn ? "workflow" : "多轮思考") + " 异常，回退单轮: " + t);
            }
        }
        return a.askVoice(userText, hint, dir);
    }

    /** GET /listen 的自述（常听 + 语法 + 多轮思考 + workflow，一眼看全）。 */
    public static String listenStatusJson() {
        AgentService s = I;
        return "{\"on\":" + continuousOn()
                + ",\"autostart\":" + listenAuto
                + ",\"think\":" + thinkOn
                + ",\"workflow\":" + wfOn
                + ",\"grammar\":\"" + J.esc(VoiceDialog.grammarOverride == null ? "cmd"
                        : (VoiceDialog.grammarOverride.isEmpty() ? "free"
                           : VoiceDialog.grammarOverride)) + "\""
                + ",\"listen_ms\":" + CONT_LISTEN_MS
                + ",\"heard\":" + (s == null ? 0 : s.contHeard)
                + ",\"empty\":" + (s == null ? 0 : s.contEmpty)
                + ",\"errors\":" + (s == null ? 0 : s.contErr)
                + ",\"speaking\":" + (voiceWake != null && voiceWake.isSpeaking())
                + ",\"note\":\"常听=不等唤醒词一直听；autostart=开机是否自动开；think=语音是否走多轮思考；"
                + "workflow=语音是否允许机器人自己检索能力并一次编排（默认开，是 think 的超集）\"}";
    }

    /** 落盘：开机是否自动常听。 */
    public static void persistListenAuto(boolean b) {
        listenAuto = b;
        try {
            Context c = I;
            if (c != null) c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean("listen_auto", b).apply();
        } catch (Throwable ignored) {}
    }

    /** 落盘：多轮思考开关。 */
    public static void persistThinkOn(boolean b) {
        thinkOn = b;
        try {
            Context c = I;
            if (c != null) c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean("think_on", b).apply();
        } catch (Throwable ignored) {}
    }

    /** 落盘：workflow 开关（v203）。 */
    public static void persistWfOn(boolean b) {
        wfOn = b;
        try {
            Context c = I;
            if (c != null) c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean("wf_on", b).apply();
        } catch (Throwable ignored) {}
    }

    /** 落盘 + 应用：常听语法（"free" / "cmd"）。 */
    public static void persistGrammar(String g) {
        String v = (g == null || g.trim().isEmpty()) ? "free" : g.trim();
        applyGrammar(v);
        try {
            Context c = I;
            if (c != null) c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString("listen_grammar", v).apply();
        } catch (Throwable ignored) {}
    }

    public synchronized String startContinuous() {
        if (contRunning) return "{\"continuous\":true,\"changed\":false}";
        if (voiceWake == null) return "{\"continuous\":false,\"error\":\"voice not ready\"}";
        // 先停 wp：麦克风只有一路，两个引擎同时抓会直接把进程搞死（v69 现场，无 tombstone）
        String wr = voiceWake.setContinuous(true);
        contRunning = true;
        contThread = new Thread(new Runnable() {
            @Override public void run() { continuousLoop(); }
        }, "ledi-continuous");
        contThread.setDaemon(true);
        contThread.start();
        BootLog.i("常识别 ★已启动 wake=" + wr);
        return "{\"continuous\":true,\"changed\":true,\"wake\":" + wr + "}";
    }

    public synchronized String stopContinuous() {
        boolean was = contRunning || (voiceWake != null && voiceWake.isContinuous());
        contRunning = false;
        contThread = null;                       // 线程自己会看到 contRunning=false 退出
        if (voiceWake != null) voiceWake.setContinuous(false);
        BootLog.i("常识别 ★已停止（听清 " + contHeard + " 次 / 空轮 " + contEmpty
                + " / 出错 " + contErr + "）");
        return "{\"continuous\":false,\"changed\":" + was + "}";
    }

    /** 常识别状态（给 /status 和电脑端控制台看）。 */
    public static String continuousStatus() {
        AgentService s = I;
        if (s == null) return "{\"on\":false}";
        return "{\"on\":" + s.contRunning + ",\"heard\":" + s.contHeard
                + ",\"empty\":" + s.contEmpty + ",\"errors\":" + s.contErr
                + ",\"listen_ms\":" + CONT_LISTEN_MS
                + ",\"speaking\":" + (voiceWake != null && voiceWake.isSpeaking()) + "}";
    }

    private void continuousLoop() {
        BootLog.i("常识别 循环开始（每轮上限 " + (CONT_LISTEN_MS / 1000)
                + "s；机器人说话期间暂停识别）");
        while (contRunning) {
            try {
                if (voiceWake == null || ai == null || router == null) { nap(500); continue; }
                // ① 唤醒/抹头顶正在应答 → 让给应答链路（两边同时听会互相掐麦）
                if (voiceWake.isHandling()) { nap(200); continue; }
                // ② ★ 机器人在说话 → 暂停识别（用户明确要求）
                if (voiceWake.isSpeaking()) { nap(CONT_SPEAK_BACKOFF_MS); continue; }
                // ②b ★★ v220：等房间**真的**安静下来再判门控（自适应，替代 v218 的固定延时）。
                //     固定延时挡不住：喇叭余响在 isSpeaking() 转 false 之后仍持续 ~2.6s、
                //     到麦 rms≈20600（与真人语音同量级）⇒ 门控把自家余声当人声、白建云会话
                //     再收 10118。waitQuiet 安静时约 0.4s 返回；一直吵就超时照判（不许卡死循环）。
                voiceWake.waitQuiet((int) CONT_SETTLE_MS);
                // ③a ★ v217：本地门控（零云开销）—— 没人说话就跳过本轮，连云端会话都不建。
                //     为什么常识别只能走云：离线 ASR 的**麦克风模式**在本机必被授权挡掉
                //     （实测 err_no -3004 "app name unknwon"；**同一份 cmd 语法**走 infile
                //     文件模式就正常出文本，见 AsrProbe 的 v215 注释）。
                //     "不用的时候别一直往上送"就靠这道闸守住（判据同 CloudAsr.hasSpeech）。
                if (!voiceWake.hasVoice((int) CONT_GATE_MS)) { contEmpty++; continue; }
                // ③b 有人说话 → 走云听一句
                String text = listenOne(CONT_LISTEN_MS);
                if (!contRunning) break;
                text = text == null ? "" : text.trim();
                if (text.isEmpty()) { contEmpty++; continue; }
                contHeard++;
                BootLog.i("常识别 听到=[" + text + "]（累计听清 " + contHeard + " 次）");
                answerOnce(text);
            } catch (Throwable t) {
                // ★ 整体兜住：这是常驻循环，任何一次意外都必须续跑。
                //   裸 while 里抛一次异常就会**静默打死**这个线程，外面只看得到
                //   "计数不再增长"这一个线索（见 silent-failure-forensics 技能）。
                contErr++;
                BootLog.w("常识别 循环出错（已续跑，第 " + contErr + " 次）: " + t);
                nap(300);
            }
        }
        BootLog.i("常识别 循环结束");
    }

    /**
     * 常识别听一轮。
     *
     * <p>★★ v217：改走<b>云端听写</b>（{@code VoiceWakeController.listenSentence}）。</p>
     *
     * <p>为什么不能再用离线（这里原来自己挂 {@link VoiceDialog.Listener} 边听边判，
     * 旧实现备份在 {@code _bak_v215/AgentService.java}）：实测（2026-10-11）离线 ASR 的
     * <b>麦克风模式</b>在本机必被授权挡掉 —— {@code /asrtest {"sec":5}}（麦克风）回
     * {@code err_no -3004 "app name unknwon"}，而<b>同一份 cmd 语法</b>走 {@code infile}
     * 文件模式就正常出 {@code text="乐迪你好"}。也就是说跟语法无关，是
     * <b>麦克风模式要过在线授权</b>而本机包名没登记。</p>
     *
     * <p>上游 {@link #continuousLoop} 已保证两件事，这里不重复：① 机器人说话期间
     * 不进来；② 开麦前先过<b>本地门控</b>（零云开销）。{@code listenSentence} 自身还有
     * "开麦前先等机器人闭嘴 ≤1.2s"的保险。</p>
     */
    private String listenOne(long maxMs) {
        // ★★ v217：改走云（在线优先、离线兜底）。
        //   离线那条路（原来这里自己挂 VoiceDialog.Listener 边听边判）整套作废：
        //   实测 离线 ASR 的**麦克风模式**在本机必被授权挡掉（err_no -3004
        //   "app name unknwon"），而**同一份 cmd 语法**走 infile 文件模式就正常出
        //   text="乐迪你好" ⇒ 与语法无关，是麦克风模式要过在线授权而本机没登记。
        //   "机器人在说话时作废本轮"的语义由上游 continuousLoop 的 isSpeaking 退避
        //   兜住（listenSentence 自己还有"开麦前先等它闭嘴 ≤1.2s"）。
        try {
            return voiceWake.listenSentence(maxMs);
        } catch (Throwable t) {
            BootLog.w("常识别 云听写失败: " + t);
            return "";
        }
    }

    /** 常识别听到一句之后：给 AI → 播报。播报期间循环那边会自动暂停识别。 */
    private void answerOnce(final String userText) {
        lastUserAt = System.currentTimeMillis();    // ★ v186：开一个对话窗口，待机闸据此让路
        try { Emotion.noteUtterance(userText); } catch (Throwable ignored) {}   // ★ v189
        if (!answering.compareAndSet(false, true)) {
            BootLog.i("常识别 已有链路在跑，本次丢弃");
            return;
        }
        long t0 = System.currentTimeMillis();
        try {
            String dir = locateSpeaker();
            if (!dir.isEmpty() && !"正前".equals(dir)) {
                String m = locator == null ? "" : locator.lookMotion();
                if (!m.isEmpty()) {
                    BootLog.i("声源定位 主人在" + dir + "，先转头看他：" + m);
                    motion.motion(m);
                }
            }
            // ★ v93：原话走 user 轮、旁白走 preface（同 answerLoop，见 AiCore.askVoice）
            // ★ v94：同样先过本地直通表，再决定要不要问 AI
            IntentResolver.Act local = IntentResolver.local(userText);
            String reply;
            String note;
            if (local != null) {
                IntentResolver.runAct(local);              // ← 真的执行副作用（停止/音量）
                reply = local.reply;
                note = local.note;
                IntentResolver.remember(userText, "local", local.note, null);
                BootLog.i("常识别 本地直通 " + local.note + " → " + reply);
            } else {
                List<String> near = IntentResolver.near(userText, 3);
                IntentResolver.remember(userText, "ai", null, near);
                // ★ v202：统一入口 —— thinkOn 时走多轮思考，否则单轮（见 replyForAI）
                reply = replyForAI(userText, VOICE_HINT + IntentResolver.correctionHint(userText), dir);
                note = near.isEmpty() ? null : ("易混候选 " + near.size() + " 条");
            }
            BootLog.i("常识别 应答=[" + reply + "] 耗时="
                    + (System.currentTimeMillis() - t0) + "ms");
            if (reply == null || reply.trim().isEmpty()) {
                BootLog.w("常识别 AI 无回复: " + ai.lastError());
                ai.pushDialog("voice", userText, "", "", dir, null, null,
                        "AI 无回复: " + ai.lastError(), System.currentTimeMillis() - t0);
                return;
            }
            String spoken = router.applyAiTags(reply);
            ai.pushDialog("voice", userText, reply, spoken, dir, null, null, note,
                    System.currentTimeMillis() - t0,
                    local != null ? "local" : null);
            if (spoken.isEmpty()) {
                BootLog.w("常识别 播报文本为空（AI 只返回了标签/空白）");
                return;
            }
            // 同步播完；isSpeaking() 在此期间为 true，循环自然暂停识别。
            // 这里刻意**不做**打断（barge-in）：用户要的是"说话时暂停识别"，
            // 两者是相反的策略 —— 打断要求边说边听，暂停要求说话期间闭嘴。
            String used = router.tts().speak(spoken, false);
            BootLog.i("常识别 播报结束 engine=" + used + " 总耗时="
                    + (System.currentTimeMillis() - t0) + "ms");
        } catch (Throwable t) {
            BootLog.w("常识别 应答失败: " + t);
        } finally {
            answering.set(false);
        }
    }

    /** 引擎绑定回调：拿到 EngineService 实例后注入 MotionController */
    private final ServiceConnection engineConn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            try {
                // EngineBinder.getService() -> EngineService 实例
                Method getService = binder.getClass().getMethod("getService");
                Object engine = getService.invoke(binder);
                Log.i(TAG, "EngineService bound: " + engine);
                BootLog.i("EngineService 绑定成功");
                if (motion != null) motion.setEngine(engine);
                if (motorPose != null) motorPose.setEngine(engine);   // v147 电机下发
                if (sdk != null) {
                    sdk.setEngine(engine);
                    // 顺带把原厂机器人事件流（按键/触摸/手臂/腰部/电量）接进来
                    Log.i(TAG, "sdk listen -> " + sdk.listen());
                }
                // 引擎就绪：原厂 isInited 靠「主控板串口上报→广播→置位」闭环，
                // Agent 单绑拿不到，这里延迟强制置位（等 onCreate 完成串口初始化）。
                final Object eng = engine;
                new Thread(new Runnable() {
                    @Override public void run() {
                        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                        if (sdk != null) {
                            String r = sdk.forceReady();
                            BootLog.i("引擎强制就绪: " + r);
                        }
                        if (motion != null && motion.hasEngine()) {
                            // 就绪后补一个「站立」基准姿态，确认电机链路可用
                            motion.motion("stStand");
                            BootLog.i("引擎就绪后补站立基准姿态");
                        }
                    }
                }, "ledi-force-ready").start();
            } catch (Exception e) {
                Log.e(TAG, "engine bind reflect fail", e);
                BootLog.w("EngineService 绑定失败: " + e);
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            BootLog.w("EngineService 断开");
            if (motion != null) motion.setEngine(null);
            if (motorPose != null) motorPose.setEngine(null);         // v147 断开就别再下发
        }
    };

    // ------------------------------------------------------------------ 对外状态

    public static boolean isRunning() { return running; }

    // ================================================================== 蓝牙看护（v176）

    /**
     * 蓝牙看护计数（**判据放这里，不要只靠日志**）。
     *
     * <p>为什么要有：2026-09-25 实测 —— 设备已经跑了一百多分钟，后勤心跳连续两轮写着
     * {@code 蓝牙=false}，而 {@code GET /status.bt_listen} 还是 {@code true}。
     * 前者说明"蓝牙真的关着"，后者说明"我们自己的标志位还立着"，两个读数互相矛盾，
     * 而**没有任何一个数字能回答"到底试过几次、成没成"**。这组数就是补这个洞的。
     *
     * <p>故意只放内存、不落盘：进程重启后归零，恰好就是"这一轮开始试"的起点；
     * 落盘反而会把上一次进程的账混进来（本项目已经吃过"账跟进程一起消失"和
     * "账跨进程串味"两种亏，见 ReviveLog 的类注释）。
     */
    public static volatile int btEnableCalls = 0;    // 真的调过 a.enable() 几次
    public static volatile int btEnableDenied = 0;   // a.enable() 返回 false 几次（系统直接不认）
    public static volatile int btEnableFails = 0;    // 喊过 enable 之后**仍是关着**几次
    public static volatile int btTries = 0;          // 当前连续失败轮数（成功即归零）
    public static volatile long btLastTryMs = 0;     // 最近一次尝试时刻（elapsedRealtime，0=没试过）
    public static volatile long btRecoveredMs = 0;   // 最近一次"从关到开"的时刻
    public static volatile long btOffSinceMs = 0;    // 本轮"持续关着"的起点（0=现在是开的）

    /**
     * 蓝牙是否由我们长期看住。
     *
     * <p>用户在遥控端按了"蓝牙：关"（{@code POST /bt {"on":false}}）就置 false ——
     * <b>用户的"关"必须被记住</b>，否则后勤 30 秒后又给它开回来，那个开关就是假的
     * （本项目最忌讳的一类开关：界面说关了、实际 30 秒后自己回来）。
     */
    public static boolean btKeepOn(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean("bt_keep_on", true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setBtKeepOn(Context ctx, boolean v) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean("bt_keep_on", v).apply();
        } catch (Throwable ignored) {}
    }

    /**
     * 适配器状态码：10=关 11=打开中 12=开 13=关闭中（-1=取不到）。
     *
     * <p>为什么用反射：{@code BluetoothAdapter.getState()} 在编译用的 android.jar 里
     * 不保证是公开 API（本项目就是拿 SDK 的 android.jar 编的），而 5.1 运行时确实有这个
     * 方法。取不到就如实返回 -1，绝不假装知道 —— 少了这个数，
     * "根本没在打开"（10）和"卡在打开中"（11）在日志里长得一模一样，又是一次假绿灯。
     */
    public static int btState() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) return -1;
            Object v = BluetoothAdapter.class.getMethod("getState").invoke(a);
            return v instanceof Integer ? ((Integer) v).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 蓝牙实况 JSON（{@code GET /bt} 与 {@code /status.bt} 用同一份，别再各写一遍）。
     *
     * <p>{@code accept} 是"我们的 SPP accept 线程在不在"，{@code on} 是"适配器真开没开"——
     * 两者**故意分开报**：这两个数不一致的时候，正是"假绿灯"被制造出来的时刻。
     */
    public static String btDiagJson(Context ctx) {
        long now = SystemClock.elapsedRealtime();
        boolean on = bluetoothOn();
        BtCommandServer s = (router == null) ? null : router.bt();
        return "{\"on\":" + on
                + ",\"state\":" + btState()
                + ",\"keep\":" + btKeepOn(ctx)
                // ★★ v177：accept 改成**真判据**（套接字拿到手才算），另留
                //   accept_thread = 原来的 running 标志位。两个数并排报是故意的：
                //   它们不一致的那几秒（实测 0.9s vs 3.9s）正是"以为连得上、其实连不上"
                //   的窗口 —— 上一版的 bt_listen 假绿灯就是从这种不一致里长出来的。
                + ",\"accept\":" + (s != null && s.listening())
                + ",\"accept_thread\":" + (s != null && s.isRunning())
                + ",\"listen_ago\":" + (s == null ? -1 : s.listenAgoMs())
                + ",\"listen_n\":" + (s == null ? 0 : s.listenCount())
                + ",\"listen_err\":\"" + J.esc(s == null ? "" : s.lastError()) + "\""
                + ",\"client\":" + (s != null && s.hasClient())
                + ",\"calls\":" + btEnableCalls
                + ",\"denied\":" + btEnableDenied
                + ",\"fails\":" + btEnableFails
                + ",\"tries\":" + btTries
                + ",\"off_s\":" + (on || btOffSinceMs == 0 ? 0 : (now - btOffSinceMs) / 1000)
                + ",\"last_try_ago\":" + (btLastTryMs == 0 ? -1 : (now - btLastTryMs))
                + ",\"recovered_ago\":" + (btRecoveredMs == 0 ? -1 : (now - btRecoveredMs))
                + "}";
    }

    /** 蓝牙当前是否真的开着（本机 /health 的 bt_on 字段就是这个）。 */
    public static boolean bluetoothOn() {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            return a != null && a.isEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 蓝牙

    /**
     * 打开蓝牙（幂等）。
     *
     * <p>机器人整套手机遥控都建立在蓝牙上：手机 App 的「蓝牙配网」写 WiFi、
     * 「蓝牙直连」直发指令、BLE 发现，全都要求适配器是开的。实测这台机器
     * 重启后 {@code settings get global bluetooth_on} = 0（默认是关的），
     * 原厂和改造前都没人管这件事 —— 所以由 Agent 负责把它开起来。
     *
     * <p>我们是 platform 签名 + {@code android.uid.system}，带 BLUETOOTH_ADMIN，
     * {@code enable()} 静默生效、不弹任何对话框。
     *
     * @param why 触发原因（写进日志，便于判断是谁开的）
     */
    public static void enableBluetooth(String why) {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null) {
                BootLog.w("蓝牙: 没有适配器（why=" + why + "）");
                return;
            }
            if (a.isEnabled()) return;
            btEnableCalls++;
            // ★ 返回码必须记：false = 系统当场不认（权限/静默策略），true = 已受理、
            //   但**受理 ≠ 开起来了**（异步）。这两个数分开才好定位（见 btEnableFails）。
            boolean ok = a.enable();
            if (!ok) btEnableDenied++;
            BootLog.i("蓝牙: enable() 已调用 why=" + why + " 返回=" + ok
                    + " state=" + btState()
                    + " uptime=" + (SystemClock.elapsedRealtime() / 1000) + "s");
        } catch (Throwable t) {
            BootLog.w("蓝牙: enable 失败 why=" + why + " : " + t);
        }
    }

    /** 关蓝牙（幂等）。只有用户在遥控端明确要求（POST /bt {"on":false}）才会走这里。 */
    public static void disableBluetooth(String why) {
        try {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a == null || !a.isEnabled()) return;
            boolean ok = a.disable();
            BootLog.w("蓝牙: disable() 已调用 why=" + why + " 返回=" + ok
                    + " state=" + btState());
        } catch (Throwable t) {
            BootLog.w("蓝牙: disable 失败 why=" + why + " : " + t);
        }
    }

    // ------------------------------------------------------------------ 齿轮

    private boolean gearWanted() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("gear_on", true);
    }

    /** 在主线程上画齿轮（WindowManager.addView 必须在有 Looper 的线程）。 */
    private void showGear() {
        try {
            MainThread.call(new Callable<Void>() {
                @Override public Void call() {
                    GearOverlay.show(AgentService.this);
                    return null;
                }
            }, 3000);
        } catch (Throwable t) {
            BootLog.w("齿轮: show 失败 " + t);
        }
    }

    /**
     * 后勤线程：看住蓝牙（★ v176 起**长期**，不再只是开机前 4 分钟 —— 见 {@link #btKeeper}）、
     * 看住齿轮（窗口被系统回收/我们自己重启过就补画一次）、看住屏幕上的 IP 文字
     * （联网状态与"电脑端连没连上"都会变）、以及抢桌面。
     * 开机前 5 分钟一轮 5 秒，之后清醒 15s / 屏保 30s，代价可忽略。
     */
    /**
     * 闲置 N 秒自动进屏保（v142，用户要求：<b>无交互待机 10 分钟自动进入屏保</b>）。
     *
     * <p>判据是 {@link SleepMode#idleAgoMs()} —— 全机**只有一处**记录"上次有人在用"
     * （{@code SleepMode.touch()}），所有交互入口都往它写：唤醒、对话、播报、
     * 头顶键、触摸/抹头顶、以及<b>任何 POST 命令</b>（远程下命令也算有人在用）。
     * 反过来刻意<b>不</b>把 {@code GET /status} 算作交互：电脑端控制台会定期轮询状态，
     * 若那个也算，"挂着控制台就永远不进屏保"，用户要的省电就落空了。</p>
     *
     * <p>为什么不用定时器而是借用后勤循环：后勤已经在跑（清醒 15s 一轮），
     * 再开一条定时器就是第二个"谁负责睡觉"的真话，迟早会不一致。10 分钟的判据
     * 容得下 15s 的粒度误差。</p>
     */
    private void checkAutoSleep(long up) {
        if (SleepMode.sleeping) return;
        int idleSec = SleepMode.idleSec(this);
        if (idleSec <= 0) return;                       // 0 = 关掉自动屏保
        long ago = SleepMode.idleAgoMs();
        if (ago < 0) { SleepMode.touch(); return; }      // 首次：以"现在"为基准
        if (ago < idleSec * 1000L) return;
        // 正在互动中不睡：应答线程在跑、或正在出声，都算还在用。
        // （这两条同时也是"兜底"：万一某个交互入口忘了 touch，这里还拦一道。）
        if (answering.get()) {
            SleepMode.touch();
            return;
        }
        try {
            if (router != null && router.tts() != null && router.tts().isSpeaking()) {
                SleepMode.touch();
                return;
            }
        } catch (Throwable ignored) {}
        BootLog.i("屏保 无交互 " + (ago / 1000) + "s >= " + idleSec + "s -> 自动进入屏保"
                + "（t+" + up + "s）");
        try {
            SleepMode.enter(this, -1f);
        } catch (Throwable t) {
            BootLog.w("屏保 自动进入失败 " + t);
        }
    }

    /** 上次离线名字探测的时刻（毫秒）。 */
    private volatile long lastNameProbeMs = 0;

    /**
     * 离线模式下按 {@code name_probe_sec} 周期做一次极短的"名字探测"（v142）。
     *
     * <p><b>默认关</b>（{@code probe_sec=0}）：它是离线模式下唯一还会花云配额的路径，
     * 而用户最在意的就是"不用的时候别一直往上送"。打开方式：
     * {@code POST /name {"probe_sec":45}}。</p>
     */
    private void maybeProbeName() {
        if (!Presence.offline) return;
        int sec = Presence.nameProbeSec(this);
        if (sec <= 0) return;
        long now = System.currentTimeMillis();
        if (lastNameProbeMs != 0 && now - lastNameProbeMs < sec * 1000L) return;
        lastNameProbeMs = now;
        try {
            String r = voiceWake == null ? "no voiceWake" : voiceWake.probeName();
            BootLog.i("离线 名字探测: " + r);
        } catch (Throwable t) {
            BootLog.w("离线 名字探测失败 " + t);
        }
    }

    /**
     * 蓝牙看护（v176）：适配器关着就把它开回来，并留下**可判读的账**。
     *
     * <h3>为什么从"只管开机前 4 分钟"改成长期</h3>
     * 旧条件是 {@code up <= 240 && !bluetoothOn()}。2026-09-25 实测把它证伪了：
     * 设备 uptime 104 分钟时适配器是关的（{@code settings get global bluetooth_on} = 0、
     * {@code dumpsys bluetooth_manager} 里 {@code state: 10}），后勤心跳连续两轮
     * {@code 蓝牙=false}；而用户指定的**遥控主路径第一步就是"先用蓝牙连上机器人"**，
     * 电脑端 bridge 扫 30 个 SPP 通道 51.6 秒零应答 —— 整条链路断在这里。
     * 开机窗口一过就没人管，这个洞迟早还会被踩到，所以纠偏窗口必须长期有效。
     *
     * <h3>为什么带节流与退避</h3>
     * {@code enable()} 是**异步**的（state 10→11→12 要几百毫秒到几秒）。不节流就会在
     * "正在打开中"的每一轮里再喊一次。更关键的是：万一这台 ROM 因为底层原因
     * **就是开不起来**，不退避就变成每 15 秒一条日志 —— 这台机器的 BootLog 只有 64KB
     * 且被麦克风日志刷得很凶（实测约 1.5 小时就轮转一圈），刷屏会把真正的证据挤掉，
     * 等于自己毁掉第一现场。
     *
     * <h3>遵守的红线</h3>
     * 用户否决了"机器人端再加任何自动启动/常驻线程/周期闹钟"。本方法**不新建任何线程**，
     * 只复用已经在跑的后勤循环；最坏情况（一直开不起来）也只是每 5 分钟一次
     * {@code enable()} + 一条日志。
     */
    private void btKeeper(long up) {
        if (!btKeepOn(this)) return;                  // 用户明确关了 -> 不擅自开回来
        long now = SystemClock.elapsedRealtime();
        if (bluetoothOn()) {
            if (btOffSinceMs != 0) {
                btRecoveredMs = now;
                BootLog.i("后勤: 蓝牙已恢复（关着 " + ((now - btOffSinceMs) / 1000)
                        + "s，期间 enable " + btEnableCalls + " 次 / 未生效 "
                        + btEnableFails + " 次）");
            }
            btOffSinceMs = 0;
            btTries = 0;
            btLastTryMs = 0;
            return;
        }
        if (btOffSinceMs == 0) {
            // 刚刚发现它是关的（正常态下这段永不进）—— 立刻试一次，别等节流
            btOffSinceMs = now;
            btTries = 0;
            btLastTryMs = 0;
            BootLog.w("后勤: 蓝牙是关的 -> 开始看护（t+" + up + "s state=" + btState() + "）");
        }
        long waitMs = btTries >= 10 ? 300000L : (btTries >= 5 ? 120000L : 30000L);
        if (btLastTryMs != 0 && now - btLastTryMs < waitMs) return;   // 还在等上一轮生效
        if (btLastTryMs != 0) {
            // 上一轮已经喊过 enable()、这一轮还是关的 ⇒ "开了没开起来"，记账
            btEnableFails++;
            btTries++;
            BootLog.w("后勤: 蓝牙上次 enable 之后仍是关的（第 " + btTries + " 次，state="
                    + btState() + "，已关 " + ((now - btOffSinceMs) / 1000)
                    + "s）—— " + (waitMs / 1000) + "s 后再试");
        }
        btLastTryMs = now;
        enableBluetooth("housekeeping t+" + up + "s state=" + btState());
    }

    private void startHousekeeping() {
        housekeeping = true;
        housekeepThread = new Thread(new Runnable() {
            @Override public void run() {
                final long t0 = SystemClock.elapsedRealtime();
                int n = 0;
                while (housekeeping) {
                    // ★ 轮询周期分三段（2026-09-24 优化：原来一律 5s，永久跑）：
                    //   · 开机前 5 分钟：5s —— ROM 会在开机阶段自己把蓝牙/桌面/方向写回去，
                    //     这一段就是靠密集纠偏赢下来的，不能松；
                    //   · 之后清醒：15s —— 电脑端连通性、齿轮补画、IP 提示都不需要 5s 那么灵敏；
                    //   · 之后屏保：30s —— 用户不在场，除了"电脑端掉线了没"没有别的要紧事。
                    //   连同 PcLink.check 一并降下来：它的节流是"最小间隔"，调用变稀
                    //   它自己就跟着变稀，不用单独改。
                    long up0 = (SystemClock.elapsedRealtime() - t0) / 1000;
                    long gap = up0 <= 300 ? 5000L
                            : (SleepMode.sleeping ? 30000L : 15000L);
                    try {
                        Thread.sleep(gap);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!housekeeping) return;
                    long up = (SystemClock.elapsedRealtime() - t0) / 1000;

                    // ⓪ 屏保：闲置超时自动进（v142，用户要求 10 分钟）
                    try { checkAutoSleep(up); } catch (Throwable t) {
                        BootLog.w("后勤 自动屏保检查异常: " + t);
                    }
                    // ⓪b 离线模式下的低频"名字探测"（默认关，见 Presence.nameProbeSec）
                    try { maybeProbeName(); } catch (Throwable t) {
                        BootLog.w("后勤 名字探测异常: " + t);
                    }

                    // ① 蓝牙：**长期**看住（v176）。旧条件是 up <= 240 —— 只覆盖开机前
                    //   4 分钟，实测设备跑到 104 分钟时蓝牙是关的、而 /status.bt_listen
                    //   还在报 true（假绿灯），用户指定的主路径「先蓝牙连上、再蓝牙
                    //   握手网络」在真机上根本进不去。判据与退避见 btKeeper。
                    //   ★ 不新增线程/定时器：就地复用本循环。
                    try { btKeeper(up); } catch (Throwable t) {
                        BootLog.w("后勤 蓝牙看护异常: " + t);
                    }

                    // ①b BLE 配网 GATT：蓝牙就绪后补齐（start 时蓝牙可能还没开）
                    if (bleServer != null && bluetoothOn() && !bleServer.isRunning()) {
                        BootLog.w("后勤: 蓝牙已开但 BLE 未起 -> 补齐 (t+" + up + "s)");
                        bleServer.ensureStarted();
                    }

                    // ② 齿轮：开关是开的、窗口却不在了 -> 补画
                    if (gearWanted() && !GearOverlay.isShown()) {
                        BootLog.w("后勤: 齿轮不在 -> 补画 (t+" + up + "s)");
                        showGear();
                    }

                    // ②b 桌面：开机阶段 ROM 会自己写一次默认桌面（常写回原厂 FaceActivity），
                    //     把我们的偏好覆盖掉 —— 前 5 分钟内被改了就抢回来
                    HomeSetter.keepHome(AgentService.this, up);

                    // ②b-2 屏幕朝向：和桌面同理 —— ROM 开机阶段会自己再写一次，
                    //       前 5 分钟内被改回来就抢回来，之后不再插手。
                    RotationLock.keep(AgentService.this, up);

                    // ②c 电脑端连通性 + 屏幕上那行 IP：
                    //     连上电脑端 -> IP 自动消失（地址已经填好了，不用再占屏幕中间）；
                    //     断开（连续两轮探测失败）-> 自动冒回来给用户照抄。
                    //     判定细节见 PcLink；显示逻辑见 GearOverlay.refreshIp。
                    PcLink.check(speaker == null ? "" : speaker.getGateway());
                    GearOverlay.refreshIp(AgentService.this);

                    // ②d 蓝牙网络共享状态：每 60 秒真查一次（BtPan 内部有限频）。
                    //     为什么放在这里而不是 /health 里：查一次要经 getProfileProxy
                    //     等异步回调，最坏卡满超时 —— 那是请求路径，不能挂这种东西。
                    BtPan.isTethering(AgentService.this);

                    // ③ 心跳：每 5 分钟留一条，便于判断服务是否长期存活
                    if (++n % 60 == 0) {
                        BootLog.i("后勤心跳 t+" + up + "s 蓝牙=" + bluetoothOn()
                                + " 齿轮=" + GearOverlay.isShown()
                                + " 电脑端=" + (PcLink.linked() ? "已连" : "未连"));
                    }
                }
            }
        }, "LediHousekeep");
        housekeepThread.setDaemon(true);
        housekeepThread.start();
    }

    // ------------------------------------------------------------------ 生命周期

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        // ★★ 复活账本：本进程被拉起过多少次（跨进程持久，见 ReviveLog 的类注释 ——
        //   2026-09-23 的死机复盘就是卡在"进程一死，内存里的计数器全没了"）。
        //   放在最前面：后面任何一行抛异常，这条账都已经落下。
        ReviveLog.noteProcessStart(this);
        try {
            // 能活过 60s 才算"这次启动是健康的" ⇒ 清掉连续崩溃计数（崩溃风暴限速用）
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override public void run() { ReviveLog.clearCrashStreak(AgentService.this); }
            }, 60_000L);
        } catch (Throwable ignored) {}
        I = this;                       // HTTP 路由要能转发"开关常识别"这类动作
        BootLog.init(getFilesDir() == null ? null : getFilesDir().getParent());
        // ★★ 启动自愈：屏保若在"已冻结原厂、还没解冻"时被杀（CrashGuard 复活 / LMK /
        //   用户重启），原厂会一直冻着 —— 表现成"机器人半死"：界面不刷新、动作不响应、
        //   而所有日志都正常。这里无条件解冻一次，把那条路堵死。
        //   异步：不在 onCreate 的关键路径上，也不阻塞任何后续初始化。
        // ★★★ v142：把"上次进程死的时候是不是在屏保里""连续听不懂几次了"捞回来。
        //   —— 见 SleepMode 的落盘注释与 Presence 的类注释：这两个状态如果只活在内存里，
        //   CrashGuard 把进程拉一次它们就归零，"3 次之后停"永远攒不满、
        //   屏保也会被无声地抹掉。顺序放在 thawAllAsync 之后：那一步是无条件解冻。
        try {
            Presence.load(this);
            BootLog.i("语音可用性 " + Presence.json(this));
        } catch (Throwable t) { BootLog.w("Presence 载入失败 " + t); }
        // ★★ v143：调性（成人化）。必须早于任何一个"会说出口"的地方 ——
        //   AiCore 的人格提示词、ProactiveTalk 的提示词、ReactionEngine 的台词都从 Tone 取。
        //   放在这里的效果是：**即使 prefs 全丢**（换机 / 清数据 / CrashGuard 冷启），
        //   机器人也回到成人档，而不是无声地退回早教腔（Tone.DEFAULT_LEVEL）。
        try {
            Tone.init(this);
        } catch (Throwable t) { BootLog.w("Tone 载入失败 " + t); }
        // ★★★ v178：两个观感开关（说话期间不亮蓝灯 / 播完自动续听）。
        //   与 Tone / Presence 同理：只活在内存里的话，CrashGuard 拉一次进程就归零，
        //   用户会看到"我改过的设置自己变回去了"。默认都开（= 用户本次要求的行为）。
        try {
            loadV178Prefs(this);
        } catch (Throwable t) { BootLog.w("v178 开关载入失败 " + t); }
        try { ProcessFreezer.thawAllAsync(this); } catch (Throwable t) {
            BootLog.w("启动自愈 解冻失败 " + t);
        }
        // ★★★ v189：情绪系统。词汇/分档从**打包进来的原厂两份 XML** 读（assets/emotions*.xml），
        //   不是硬编码 —— 原厂改表我们跟着变，而且"表没读到"会被 selfTest 与 /emotion 看见。
        try {
            BootLog.i("情绪 载入 " + Emotion.load(this)
                    + " 当前=" + Emotion.current() + " 脸=" + Emotion.facePool()[0]);
        } catch (Throwable t) {
            BootLog.w("情绪 载入失败 " + t);
        }
        // ★ v93：长期对话日志 / 多轮记忆。必须早于任何 ai.ask —— AI 组请求时要读历史，
        //   而 pushDialog 会往这里落账（含双端互查用的 tid）。
        TalkLog.init(this);
        // ★★★ v214：长期记忆（认识第几天 / 聊过几次 / 记着哪些事）。存在**应用私有目录**，
        //   与 TalkLog 一样"机器人本地、不上云"。必须早于任何 ai.ask —— 组提示词时要读它。
        try {
            Memory.init(this);
            BootLog.i("记忆 载入 认识第" + Memory.daysTogether() + "天 事实=" + Memory.factCount() + "条");
        } catch (Throwable t) { BootLog.w("记忆 载入失败 " + t); }
        // ★ v94：离线意图解析层。必须早于任何语音应答 —— 它要从设备上那份 .bsg 里
        //   读出词表（用于易混候选 + 本地直通），而读文件只有几十 KB、一次就够。
        IntentResolver.reload();
        BootLog.i("=== AgentService.onCreate pid=" + android.os.Process.myPid()
                + " uptime=" + (SystemClock.elapsedRealtime() / 1000) + "s 顶部灯号=启动 ===");
        Log.i(TAG, "AgentService starting");
        MainThread.init();          // 浮层类操作要在主线程做，先把调度器立起来
        // 崩溃兜底：Java 崩溃 → 3s 后自拉起；native 崩 / 被 LMK 杀 → 5min 心跳闹钟拉回。
        // 必须在最前面装 —— 下面任何一行抛异常都要能被它接住（见 CrashGuard 的取证）。
        CrashGuard.install(this);
        // 屏幕朝向：机器人会被抱起/倾斜，自动旋转只会把脸转躺下（实测 mRotation=3、
        // 应用视口变成 480x800 竖向，而面板原生 800x480、原厂 FaceActivity 也声明横向）。
        // 开机就锁死横向，见 RotationLock 类注释里的完整取证现场。
        RotationLock.apply(this);
        // 屏幕实时日志浮层：上次开着就自动铺上（站着调试时不用再去拉 adb / HTTP）。
        // 默认关，开关走 POST /logwin {"on":true}。必须在 MainThread.init() 之后调。
        LogOverlay.install(this);
        face = new FaceController(this);
        motion = new MotionController(this);
        wifiProvisioner = new WifiProvisioner(this);
        mic = new MicController(this);
        display = new DisplayController(this);
        speaker = new Speaker(this);
        sdk = new SdkController(this);

        // newcore 重构：AI 大脑（直连大模型 + 回落网关）
        ai = new AiCore(this, speaker);

        // 蓝牙：整套手机遥控的前提，开机就得是开的
        enableBluetooth("onCreate");

        // 绑定原厂动作引擎（同进程，classes2+.dex 来自原包）
        try {
            Intent ei = new Intent();
            ei.setComponent(new ComponentName(this,
                    "com.auric.intell.rbt.xldbasesdk.service.engine.EngineService"));
            bindService(ei, engineConn, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.e(TAG, "bind engine fail", e);
            BootLog.w("绑定引擎异常: " + e);
        }

        bleServer = new BleProvisionServer(this, wifiProvisioner);
        bleServer.start();

        // 共享指令路由：HTTP 与蓝牙 SPP 同一套指令
        router = new CommandRouter(this, motion, face, speaker,
                wifiProvisioner, bleServer, mic, display);
        router.setSdk(sdk);
        // 传感器中枢（触摸/按键/陀螺仪/电量）+ 自带反应引擎
        sensor = new SensorHub(this, motion);
        sensor.start();
        // ★★ v147：电机姿态【读回】。原厂 SDK 自己就在广播电机位置（见 MotorPose 类注释，
        //   逆向自 EngineService$EngBroadCastReciver），我们只是把它接出来。
        //   三条注入口缺一不可：
        //     setEngine —— 引擎绑上之后注入（见 engineConn），下发走 moveInterface(text,speed)
        //     setPose   —— 回退通道（引擎没绑上时写姿态文件 + 按名执行）
        //     start()   —— 注册 ENGTATUS 广播
        //   必须放在 router 建好之后：setPose 要 router.pose()。
        motorPose = new MotorPose(this);
        motorPose.setPose(router.pose());
        motorPose.start();
        // ★ v148：调试控件（触摸反馈 / 陀螺仪 3D / 三电机滑条）已**并入 LogOverlay 的面板**，
        //   不再是独立浮层 —— 点小绿点（或 POST /logwin {"on":true} / POST /pad {"on":true}）
        //   就一起展开。这里只负责把数据源接上；控件树的构建在
        //   LogOverlay.show() → DebugPad.buildTouchBlock()/buildControlBlock() 里。
        DebugPad.bind(sensor, motorPose);
        reaction = new ReactionEngine(this, face, motion, speaker, sensor);
        reaction.setTts(router.tts());          // 自带反应也用机器人自带语音合成
        router.setSensor(sensor);
        router.setReaction(reaction);

        // newcore 重构：媒体播放（依赖 router 的 TTS）+ AI + 主动说话
        media = new MediaPlayback(this, display, router.tts(), face);
        proactiveTalk = new ProactiveTalk(this, ai, router.tts(), face, motion, sensor);
        proactiveTalk.setTagHandler(new ProactiveTalk.TagHandler() {
            @Override public String apply(String raw) { return router.applyAiTags(raw); }
        });
        proactiveTalk.start();
        router.setAi(ai);
        router.setProactiveTalk(proactiveTalk);
        router.setMedia(media);

        // 语音唤醒（复用原厂图灵框架唤醒进程，AIDL 绑定 WakeUpService）：喊唤醒词 -> 打断当前播报 -> AI 应答 -> 播完恢复监听
        voiceWake = new VoiceWakeController(this);
        voiceWake.setWakeListener(new VoiceWakeController.WakeListener() {
            @Override public void onWakeup(String info) {
                // ★★ v142：屏保中绝不进入应答链路。
                //   VoiceWakeController 已经挂起了（dispatchWake 见 suspended 就返回），
                //   这里是第二道闸 —— "路由"和"应答"是两件事，本项目吃过
                //   "修在一个地方 != 修好了"的亏：任何绕过 suspended 的路径
                //   （强制唤醒 / 看门狗救回 / 离线模式切换）都不该把屏保撕掉。
                if (SleepMode.sleeping) {
                    BootLog.i("语音唤醒 屏保中，忽略应答 " + info);
                    return;
                }
                SleepMode.touch();
                BootLog.i("语音唤醒 命中! info=" + info);
                long t0 = System.currentTimeMillis();
                // 一次只跑一条应答链路：后到的唤醒/抹头顶只负责"打断"，不另起一条。
                if (!answering.compareAndSet(false, true)) {
                    BootLog.i("语音对话 已有应答在进行，" + info + " 只打断播报");
                    try { if (router != null && router.tts() != null) router.tts().stopNow(); }
                    catch (Throwable ignored) {}
                    return;
                }
                try {
                    if (ai == null || router == null || router.tts() == null) {
                        BootLog.w("语音唤醒 应答跳过: ai=" + ai + " router=" + router
                                + " tts=" + (router == null ? "null" : String.valueOf(router.tts())));
                        return;
                    }
                    // 打断式：立即掐掉当前播报（说话途中喊唤醒词也能打断），旧应答线程会自行收摊
                    router.tts().stopNow();
                    try { Thread.sleep(350); } catch (InterruptedException ignored) {}
                    // ★★★ v180：唤醒呼应（表情 wake + 挥手 + 蓝灯）。
                    //   放在 CAS **成功之后**、answerLoop **之前**：这样只有"真正拿到应答权"
                    //   的那一次唤醒才做呼应，被合并/只负责打断的那些（抹头顶连击、重复唤醒）
                    //   不会跟着挥手 —— 否则连着拍两下头顶会挥两次手，看着像抽搐。
                    //   放 answerLoop 之前而不是之后：先给观感，再开始"听你说话"。
                    wakeFlourish(info);
                    answerLoop(t0, info);
                } catch (Throwable t) {
                    BootLog.w("语音唤醒 应答失败: " + t);
                } finally {
                    answering.set(false);
                    listenLed(false);   // ★ 兜底：应答链路无论怎么结束，听语灯必须灭
                    listenFace(false);  // ★ v182：同一条兜底 —— 「听完后结束」也适用于表情
                    //   （v167 起灯语的主挂点是麦闸 beforeAsrMic/afterAsrMic，这里只是
                    //    安全网 —— 同一个幂等方法，重复调用不刷广播。留着是因为"灯不灭"
                    //    是那种不报错、只让用户困惑的故障，多一层几乎零成本的保证值得。）
                    if (voiceWake != null) voiceWake.resume();   // 恢复监听（幂等，重复 start 无害）
                }
            }
        });
        voiceWake.start();
        router.setVoiceWake(voiceWake);
        // 「机器人在说话吗」的信号源。常识别靠它实现"说话期间暂停识别"。
        // 放 TtsController 是因为它是**所有播报的唯一出口**（对话 / 主动说话 / 触摸反应 /
        // 电脑端 /say 全走它），挂一处即可全覆盖，不必在每条播报链路上各加一个标志。
        voiceWake.setSpeakingGate(new VoiceWakeController.SpeakingGate() {
            @Override public boolean speaking() {
                try {
                    return router != null && router.tts() != null && router.tts().isSpeaking();
                } catch (Throwable ignored) {
                    return false;
                }
            }
        });

        // ★ 「主动说话必须让路」闸：机器人自己的喇叭走 AEC，它一开口就把自家麦克风里的
        //   近端人声连着压掉。v107 整链验收就撞在这上面 —— 设备侧 rms_max=3965 / saw_speech=true /
        //   write_fails=0，云端却回 10118「您好像没有说话哦」，根因是 via=proactive 的闲聊
        //   与用户说话撞在同一时刻。判据用"对话正占麦" + "正应答"两个旗子。
        if (proactiveTalk != null) {
            proactiveTalk.setBusyGate(new ProactiveTalk.BusyGate() {
                @Override public boolean busy() {
                    try {
                        return voiceWake != null
                                && (voiceWake.isMicForDialog() || voiceWake.isHandling());
                    } catch (Throwable ignored) {
                        return false;
                    }
                }
            });
        }

        // 双麦声源定位（v60）：默认不开。它和唤醒引擎抢同一个麦克风输入，
        // 开了会不会把唤醒饿死，必须在本机实测（先跑 /doa {"probe":true} 看硬件给不给两路），
        // 所以这里只按用户上次的开关恢复，第一次装完是关的。
        locator = new SoundSourceLocator(this);
        router.setLocator(locator);
        if (locator.isEnabled()) {
            locator.start();
            BootLog.i("声源定位 已按上次设置启动");
        }
        // 方位感知（兜底，常开）：谁摸了它哪半边脸 = 那人就在哪一侧，
        // 然后**程序化地**把头转向那一侧看人（不经过 AI，确定性行为）。
        // ★ 为什么兜底要常开：v146 挖过原厂 TV 版后，旧判「双麦 DOA 已否掉」**作废** ——
        //   两路逐采样差异为 0 不是"没有第二只麦"，而是 HAL 的单麦路径把 MicSource1/2
        //   都映射到了 ADC1（ro.mtk_dual_mic_support=0）；硬件上 ADC1+ADC3 都在。
        //   ⇒ 但"硬件有第二只麦"≠"这条路现在能用"：走 HAL 拿不到真双路，要另开通道路
        //   （定位算法在 TV 版 libQvoice.so 里，见 双麦通道与定位算法_v146.html）。
        //   所以当下的方位信号仍然只有触摸，兜底照旧常开。
        //   摸头顶不在此列 —— 那是最高优先级的强制唤醒打断，不走这条。
        sensor.addListener(new SensorHub.Listener() {
            @Override public void onSensorEvent(String action) {
                if (locator == null || action == null || motion == null) return;
                final String side;
                if (action.endsWith(".face.l.down")) side = "lface";
                else if (action.endsWith(".face.r.down")) side = "rface";
                else return;
                locator.noteTouch(side);
                // ★ 方位与动作必须在**同一时刻**一起定下来再带走。
                //   踩过：原来只把动作名 m 带进线程，日志里读 locator.sector() 实时值，
                //   结果 700ms 后方位可能已被下一个事件改掉，日志就出现
                //   "主人在右侧…转头 stTurnLeft" 这种自相矛盾的记录（实测出现过）。
                final String sec = locator.sector();
                final String from = locator.dirFrom();
                final String m = locator.lookMotion();
                if (m.isEmpty()) return;
                // ★★ 合并取最新，而不是排队叠加（v73 用户反馈修）
                //
                // 现象：连摸几下，机器人就开始"卡" —— 几个动作一个一个慢慢演完。
                // 成因有两层，缺一不可：
                //   ① 原厂一次物理触摸常常连着推好几个 down，用户手快也会连按，所以事件是**一串**；
                //   ② 老写法每个事件都起一条线程、700ms 后各发一次动作，而
                //      MotionController 的动作池是 **newSingleThreadExecutor**（严格串行排队），
                //      于是这几条命令不会互相覆盖，只会乖乖排队演完 —— 那就是"卡顿"。
                // 修法：只认**最新**那一次。每次事件把序号顶掉，先前线程醒来发现自己已过期就退出，
                // 最多只留下一次转头，而且是相对"最后一次触摸"延迟 700ms，反应反而更快。
                final long my = dirSeq.incrementAndGet();
                // 等 700ms 再转：ReactionEngine 会先给表情/回应，同一瞬间抢动作会打架
                new Thread(new Runnable() {
                    @Override public void run() {
                        try { Thread.sleep(700); } catch (InterruptedException ignored) { return; }
                        if (my != dirSeq.get()) {
                            BootLog.i("方位感知 合并（期间又摸了一下，本次不转头）");
                            return;
                        }
                        long now = System.currentTimeMillis();
                        if (now - lastLookMs < LOOK_MIN_GAP_MS) {
                            BootLog.i("方位感知 限流（距上次转头 "
                                    + (now - lastLookMs) + "ms < " + LOOK_MIN_GAP_MS + "ms）");
                            return;
                        }
                        try {
                            if (motion.isExclusive()) return;   // 有别的东西在独占就先算了
                            lastLookMs = now;
                            BootLog.i("方位感知 主人在" + sec + "（来源 " + from
                                    + "），转头看他：" + m);
                            motion.motion(m);
                        } catch (Throwable t) {
                            BootLog.w("方位感知 转头失败: " + t);
                        }
                    }
                }, "dir-look").start();
            }
        });

        // 抹头顶（v61）：**最高优先级的打断**。
        //
        // 用户的要求是"抹头顶强制唤醒打断"——摸一下头顶，不管机器人此刻在跳舞、
        // 在播报、还是唤醒引擎根本没在听，都要立刻停下来并进入应答。
        // 所以这条链路刻意不走 ReactionEngine（那里要先过"AI 接管/原厂反应"两个开关），
        // 也不复用唤醒词命中（那要求用户先喊一句，功能就没意义了）：
        //   SensorHub 一收到头顶 down → 线程里执行 ① 打断动作 ② 打断播报
        //   ③ 让主动说话静默一会儿 ④ voiceWake.forceWake() 走完整应答链路。
        sensor.setPatListener(new SensorHub.PatListener() {
            @Override public void onHeadPat() {
                // ★ v142 第二道闸：SensorHub.firePat() 已经在屏保里直接返回了，
                //   这里再挡一次是因为本回调会去调 motion.abort()/tts.stopNow() ——
                //   而屏保期间原厂是 SIGSTOP 冻着的，碰它的 SDK 会在 binder 上永久等下去。
                if (SleepMode.sleeping) {
                    BootLog.i("抹头顶 屏保中，不打断、不唤醒");
                    return;
                }
                SleepMode.touch();
                long t0 = System.currentTimeMillis();
                String ar = "?";
                try { ar = motion == null ? "no motion" : motion.abort(); }
                catch (Throwable t) { ar = "abort 异常: " + t; }
                String tr = "?";
                try {
                    if (router != null && router.tts() != null) tr = router.tts().stopNow();
                    else tr = "no tts";
                } catch (Throwable t) { tr = "tts 异常: " + t; }
                // ★★ v143：掐完之后**设备还在出声**吗？
                //   这是一条"不自参考"的判据：我们自己讲的每一句都被 TtsController 记成
                //   speaking=false（stopNow 已经把它清了），所以"还在响"只能来自别的地方。
                //   这台机器上最可能的来源就是**原厂**那句 quality 推送 ——
                //   也就是用户听得见的"摸头默认语音"。
                //   ⚠ 但它只是**上界**：stopNow 之后我们自己的音频缓冲也可能还在排水。
                //   真判据是人耳（真机回来那天摸头十次听有没有儿童向那句冒头）。
                //   命名与注释都按"上界"来写，是因为把上界叫成"原厂说了几次"
                //   就会变成一条看着精确、其实在骗人的假判据。
                boolean stillPlaying = musicActive();
                if (stillPlaying) Tone.notePatAudioAfterStop();
                try { if (proactiveTalk != null) proactiveTalk.noteInterrupt(); }
                catch (Throwable ignored) {}
                BootLog.i("★抹头顶 打断: 动作=" + ar + " 播报=" + tr
                        + " 掐后仍出声=" + stillPlaying
                        + " 耗时=" + (System.currentTimeMillis() - t0) + "ms");
                // ★★★ v165「合并窗口」：这一下抹头顶是"新开一轮"还是"并进正在跑的那一轮"。
                //   判据与理由见 PAT_MERGE_MS 的长注释；一句话：用户要的是"摸一次头、
                //   开一轮、一直听"，不是"每摸一下就重开一轮 + 再叠一句 TTS 车轮话"。
                //   ★ 位置必须在打断**之后**：重复摸头仍然要能打断机器人自己的播报。
                long sinceLastPat = t0 - lastPatMs;
                boolean linkRunning = voiceWake != null
                        && (voiceWake.isMicForDialog() || voiceWake.isHandling());
                boolean mergePat = lastPatMs != 0L
                        && sinceLastPat < PAT_MERGE_MS
                        && (linkRunning || sinceLastPat < PAT_BURST_MS);
                lastPatMs = t0;
                if (mergePat) {
                    BootLog.i("★抹头顶 合并（距上次 " + sinceLastPat + "ms 链路在跑="
                            + linkRunning + "）→ 只打断，不重说台词 / 不重开麦");
                    return;
                }
                // ★★ v143 成人化：摸头顶**先出声再听**。
                //
                //   改造前这条链路一句话都不说（打断 → 强制唤醒 → 直接进听写），
                //   于是用户耳朵里留下的"摸头应答"全是**原厂**那 10 条儿童向推送。
                //   现在改成：掐掉原厂 → 说我们自己的一句（Tone.patLine，成人向、短促）
                //   → 等它播完 → 再唤醒进听写。
                //
                //   ★ 为什么必须"等它播完再唤醒"：机器人喇叭与麦克风在同一副身体上，
                //     它自己一开口，自家麦克风里的近端人声会被 AEC 连带压掉
                //     （见 ProactiveTalk.BusyGate 的注释与 REF-H1）。所以顺序不能反。
                //   ★ 为什么 speak(...,false) 之后就够了：该重载**同步播完才返回**
                //     （AgentService 播报路径依赖同一语义，见 speakWithBargeIn）。
                //   ★ 三道保险，缺一不可：
                //     ① 开关：tone_pat_line=false ⇒ 回到"不出声直接唤醒"的老行为；
                //     ② 时长兜底：万一 TTS 卡住，最多等 PAT_SPEAK_MAX_MS 就走，
                //        绝不能让"说一句话"把"唤醒"整个堵死（那正是 v142 长按被
                //        定时器堵住那类事故的形状）；
                //     ③ 屏保：上面已经 return 了（碰原厂 SDK 会永久挂 binder）。
                String patSay = "";
                if (Tone.patLineOn() && Tone.patSpeakOn()) {
                    try {
                        patSay = Tone.patLine();
                        if (patSay != null && !patSay.isEmpty() && router != null
                                && router.tts() != null) {
                            long ts = System.currentTimeMillis();
                            String eng = router.tts().speak(patSay, false);
                            long dt = System.currentTimeMillis() - ts;
                            // 喇叭余响：AEC 之外还有混响，立刻开麦会把自己刚说的听到
                            // （离线 KWS 抗混响差，见 REF-H2/H3）。与 releaseMicForDialog
                            // 的 450ms 同一量级，取 300ms 不必那么保守——因为紧接着
                            // dispatchWake→sendStop() 还会再放一次麦。
                            if (dt < PAT_SPEAK_MAX_MS) sleepQuiet(300);
                            BootLog.i("★抹头顶 出我们的声: [" + patSay + "] engine=" + eng
                                    + " 耗时=" + dt + "ms");
                        }
                    } catch (Throwable t) {
                        BootLog.w("抹头顶 台词失败（不影响唤醒）: " + t);
                    }
                }
                try { if (voiceWake != null) voiceWake.forceWake("抹头顶"); }
                catch (Throwable t) { BootLog.w("抹头顶 强制唤醒失败: " + t); }
                // 对话流水：让电脑端看得到"摸头这句是我们说的"。
                // ★ 只在真的出了声时记，否则每摸一次头都会多一条空气记录，
                //   验收时反而看不出"到底有没有出声"。
                // ★ engine 给 "local" 而不是让它默认成 direct/gateway：
                //   这一句**根本没发过任何请求**，盖 direct 就是把对照表做成假账
                //   （本项目"engine 必须按实际路线给"那条铁律）。
                if (patSay != null && !patSay.isEmpty()) {
                    try {
                        if (ai != null) {
                            ai.pushDialog("pat", "摸头顶", patSay, patSay, null, null, null,
                                    "调性台词·无模型调用", 0L, "local");
                        }
                    } catch (Throwable ignored) {}
                }
            }
        });

        // ================= v143：抹头顶"先出声再听"的两个小工具 =================

        // 头顶**按键**（v116）：用户要求「按头顶键打开语音和主程序，再按一下退出主程序」。
        // ★ 与上面的"抹头顶"是两回事：那条是**触摸**（face.head.down），原厂拿它做强制唤醒打断；
        //   这条是**按键**（key.head.down），原厂把它喂给自己的场景状态机（BTN_HEAD_CLICK）。
        // ★ v127 分短按 / 长按（阈值 3000ms，与原厂 `robot.main.c.b()` 对齐）：
        //   短按 = 原来那套打断/唤醒/退主程序；长按 = 屏保开/关（用户要求）。
        //   两个回调都在 SensorHub 的独立线程里跑，这里不必再开线程。
        headKey = new HeadKey(this);
        sensor.setHeadKeyListener(new SensorHub.HeadKeyListener() {
            @Override public void onHeadKeyDown(long heldMs) { headKey.onPress(); }
            @Override public void onHeadKeyLong(long heldMs) { headKey.onHold(); }
        });
        BootLog.i("头顶键: 已接线 on=" + headKey.enabled()
                + " main=" + headKey.mainTarget() + " 目标=" + headKey.json());

        btServer = new BtCommandServer(router);
        router.setBt(btServer);
        btServer.start();

        httpServer = new HttpServer(this, router, mic);
        httpServer.start();

        // UDP 广播：既是"我在这"（手机/电脑端据此发现机器人），
        // 也是"你在哪"的应答口 —— 电脑端会反向广播它自己的地址，我们收到就
        // 把网关设置改过去。于是「电脑端换 IP」或「换了一台电脑」，
        // 机器人自己就跟上了，不用再 adb 进去改设置、也不用人去屏幕上戳。
        beacon = new UdpBeacon(this, new UdpBeacon.PcSeen() {
            @Override public void onPc(String ip, int port) {
                if (speaker == null) return;
                String want = "http://" + ip + ":" + port;
                if (want.equals(speaker.getGateway())) return;   // 没变就别反复写盘
                BootLog.i("beacon: 收到电脑端广播 -> 网关改为 " + want
                        + "（原 " + speaker.getGateway() + "）");
                speaker.setGateway(want);
            }
        });
        beacon.start();

        // 本机回环转发：主 App 的对话端点被重打包固定成 127.0.0.1:18080，
        // 这里把它转发到「齿轮设置」里填的网关 —— 换电脑/换网络只改设置、不用重打包
        relay = new LoopbackRelay(new LoopbackRelay.GatewaySource() {
            @Override public String getGateway() {
                return speaker == null ? "" : speaker.getGateway();
            }
        });
        relay.start();

        // 屏幕左上角白色齿轮 = **桌面入口**（用户要求：桌面上必须有它，点它进桌宠软件）。
        // ★ 开机一律强制点亮："记忆开关"曾经把齿轮永久关掉（真机 gear_on=false），
        //   于是屏幕上再没有任何入口 —— 对着一台没有屏幕入口的机器人，用户是没法自救的，
        //   只能从电脑发 HTTP 把它打开。所以每次开机都写回 true；
        //   POST /gear {"on":false} 仍可临时隐藏，但下次开机照旧回来。
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("gear_on", true).apply();
        GearOverlay.show(this);
        BootLog.i("齿轮: 桌面入口已请求显示 结果=" + GearOverlay.isShown());

        // 无线 ADB（手机端「投屏」的机器人侧那一半）。
        // ★ 默认**不开**：留着 5555 常听是个没必要的口子（本 ROM adbd 无认证且是 root）。
        //   只有用户/手机端明确说过"下次也开"（K_AUTO）时，开机才兜这一下。
        //   放后台线程：setprop 在异常路径下会退化到 exec，不该卡服务启动。
        new Thread(new Runnable() {
            @Override public void run() {
                try { AdbWifi.applyAtBoot(AgentService.this); }
                catch (Throwable t) { BootLog.w("AdbWifi 开机兜底异常: " + t); }
            }
        }, "adbwifi-boot").start();

        // ★★★ v142：**放在这里**（而不是 onCreate 开头）恢复屏保 —— 因为
        //   SleepMode.enter() 会去闸 `AgentService.router/motion/face/reaction/
        //   proactiveTalk/voiceWake/sensor/beacon`，这些引用必须**已经建好**才能闸得住；
        //   放在开头会把一个"闸不住任何东西"的屏保铺上去，比不恢复更危险。
        //   判据取自落盘的 sleep_on（见 SleepMode 的落盘注释）：进程被 CrashGuard
        //   拉起来、或用户手动重启后，屏保不会无声消失、功能也不会偷偷全开。
        try {
            if (SleepMode.restoreIfWasSleeping(this)) {
                BootLog.w("屏保 已按落盘状态恢复（进程重启不影响屏保，原厂冻结已重做）");
            }
        } catch (Throwable t) {
            BootLog.w("屏保 恢复失败 " + t);
        }

        // ★ v201：这里原本是 `Bright.restore(this)` —— 语音/网页可调的「屏幕亮度」。
        //   该功能已按用户要求**整体移除**，原因：本机背光物理不可调，那套实现只能把
        //   **画面**叠一层半透明黑压暗（能变暗但**不省电**），收益有限，却容易把屏幕
        //   留在暗屏上（实测确实被留在了 level=10）。删除后开机不再贴任何压暗层，
        //   屏幕恒为正常亮度。原实现与改动前源码备份在 `_bak_v200_bright/`。

        // ★★★ v202：常听（长期主动听）的持久化 + 开机恢复。
        //   放在 onCreate **末尾**（voiceWake 已建好、屏保状态也恢复完）是有意的：
        //     ① VoiceDialog.create() 会先按"有没有外置词表"设一次 grammarOverride，
        //        用户选的语法必须在那之后应用，否则会被它改回去；
        //     ② 若开机即深睡（restoreIfWasSleeping 走 onDeepSleep(true)），此刻
        //        deepSleeping 已经是 true，不能把常听开起来（否则深睡当场被撕掉）。
        try {
            loadV202Prefs(this);
            if (deepSleeping) {
                BootLog.i("v202 开机即深睡 → 不自动开常听（醒来时会恢复）");
            } else if (listenAuto) {
                BootLog.i("v202 开机自动开常听: " + startContinuous());
            } else {
                BootLog.i("v202 开机不自动开常听（listen_auto=false）");
            }
        } catch (Throwable t) {
            BootLog.w("v202 常听开机恢复失败 " + t);
        }

        startHousekeeping();
        startHomeEnforcement();
        startHomeWatch();

        BootLog.i("=== AgentService ready (http=" + HTTP_PORT + ", bt=SPP) ===");
        Log.i(TAG, "AgentService ready (http=" + HTTP_PORT + ", bt=SPP)");
    }

    /**
     * 设备此刻是否还有音频在播（{@code STREAM_MUSIC}）。
     *
     * <p>用途只有一个：抹头顶时判断"我们掐完之后是不是还有别人在出声"（见那段的注释）。
     * ★ 它<b>不是</b>"原厂说了几次"的判据 —— 我们自己的音频缓冲也可能还在排水，
     * 所以它只作<b>上界</b>用。写成别的意思就会变成一条看着精确、其实在骗人的假判据。</p>
     */
    private boolean musicActive() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            return am != null && am.isMusicActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 睡一小会儿（可被中断）。
     *
     * <p>抹头顶说完那句之后用它给喇叭留一点余响时间，再开麦听 ——
     * 机器人喇叭与麦克风同体，离线 KWS 抗混响又差（REF-H2/H3），
     * 立刻开麦会把自己刚说的那句听进去。</p>
     */
    private static void sleepQuiet(long ms) {
        try { Thread.sleep(Math.max(0, ms)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * 抹头顶"说完我们那句"的等待上限（毫秒）。
     *
     * <p>★ 为什么必须有上限：{@code tts.speak(text,false)} 是同步播完才返回的。
     * 万一 TTS 引擎卡住（本 ROM 上不是没发生过），这一句就会把<b>唤醒</b>一起堵死 ——
     * 那正是 v142 "长按被定时器堵住"那类事故的形状：功能看着还在，实际已经废了。
     * 超过这个数就不再等余响，直接唤醒。判据是<b>超过与否</b>，不是"等了多久"。</p>
     */
    private static final long PAT_SPEAK_MAX_MS = 4000;

    /**
     * ★★★ v165「抹头顶合并窗口」（毫秒）—— 真机 2026-09-25 02:48 取证后加的。
     *
     * <h4>改之前是什么形状</h4>
     * 每收到一次抹头顶，{@code onHeadPat()} 都会完整跑一遍：
     * ① 打断动作 ② 打断播报 ③ 说一句成人向台词（Tone.patLine）④ {@code forceWake} 开一轮听写。
     * 转录流水 {@code ledi_talk.jsonl} 里能看到后果：02:48:33–02:50:52 这 2 分 19 秒，
     * 用户摸了约 60 次头，于是机器人**说了 60 句车轮话**（在，说。／我听着呢。／怎么了。／嗯？
     * 四句循环）并**开了 60 轮麦**，每轮 5.4 秒空转（{@code rms_max=280 < thr=500}，没人说话）。
     *
     * <h4>为什么这就是用户说的「采音实在不行」</h4>
     * 用户的原始需求是「摸头被动 + 录音时间拉长 + 用户不说话了再结束录音」——
     * 也就是<b>摸一次头、开一轮、一直听着</b>；而实际行为是<b>每摸一下就重开一轮</b>，
     * 不但把上一轮听到一半的话掐掉，还叠一层 TTS 车轮话。用户听到"没反应"就越摸越快，
     * 于是 60 次摸头 → 60 句废话 → 更觉得没反应，形成正反馈。
     *
     * <h4>改法</h4>
     * 上一轮<b>还没收尾</b>时（对话通路正独占麦克风 = {@code isMicForDialog()}，
     * 或被唤醒后正在应答 = {@code isHandling()}），窗口内的重复抹头顶只保留"打断"这一个动作，
     * <b>不再重说台词、也不再重开麦</b> —— 正在听的那一轮原样继续听着。
     *
     * <p>★ 判据为什么用 {@code isMicForDialog()} 而不是"距上次摸头多久就够了"：
     * 那个旗子由 {@code beforeAsrMic()/afterAsrMic()} 置位与清位，语义就是
     * <b>"此刻麦克风真的在听写"</b>，正是"该不该再开一轮"要问的那件事。</p>
     */
    private static final long PAT_MERGE_MS = 8000;

    /**
     * 极短连击窗口：链路状态还没来得及翻转时（{@code forceWake} 走的是异步线程，
     * {@code isHandling} 要晚几十毫秒才置位），这个时间内的重复抹头顶也一律合并。
     * 取 1500ms：人手连拍两下的间隔量级，慢速再摸（>1.5s）留给正常链路判断。
     */
    private static final long PAT_BURST_MS = 1500;

    /** 上一次抹头顶的时刻（0 = 开机以来还没摸过）。仅供 {@link #PAT_MERGE_MS} 的窗口判断用。 */
    private volatile long lastPatMs = 0L;

    /**
     * 装上就把自己设成机器人桌面（需求⑦）。
     *
     * <p>放在后台线程里、并且延迟 2 秒：{@code onCreate} 阶段系统可能还没把
     * 全部包信息扫完（覆盖安装后尤其明显），这时候查 HOME 候选会漏人，
     * 写进去的 preferred-activity 就不完整。延迟一下让 PackageManager 稳定下来。
     *
     * <p>这一次只是"先设一次"；开机阶段 ROM 还可能把它改写，所以真正的兜底
     * 在 {@link #startHousekeeping()} 里周期性调 {@link HomeSetter#keepHome}，
     * 加上 {@link #startHomeWatch()} 对"包变动"事件的即时反应。
     */
    private void startHomeEnforcement() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    String r = HomeSetter.ensure(AgentService.this);
                    BootLog.i("桌面: 首次设置 " + r);
                } catch (Throwable t2) {
                    BootLog.w("桌面: 首次设置失败 " + t2);
                }
            }
        }, "ledi-home");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 包变动后重设桌面。
     *
     * <p>为什么需要：覆盖安装任何一个包（尤其是原厂 App 或我们自己）时，系统会重算
     * preferred-activity，那条 HOME 偏好记录经常被清掉 —— 表现出来就是机器人卡在
     * 「选择要使用的应用」那个选择器上。机器人没有鼠标、触摸区又小，很可能就一直卡着。
     *
     * <p>只对"包变动"这一个事件反应，不做常驻轮询，所以**不会**和"用户手动换桌面"
     * 打架（那件事该由 Housekeeping 的开机 5 分钟窗口 + 用户自己作主）。
     */
    private void startHomeWatch() {
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_PACKAGE_REPLACED);
            f.addAction(Intent.ACTION_PACKAGE_ADDED);
            f.addAction(Intent.ACTION_PACKAGE_REMOVED);
            f.addDataScheme("package");
            registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent it) {
                    String pkg = (it == null || it.getData() == null) ? ""
                            : it.getData().getSchemeSpecificPart();
                    // 只关心会和 HOME 有关的这两个包，别为无关的安装去动系统设置
                    if (!HomeSetter.FACTORY_PKG.equals(pkg)
                            && !HomeSetter.OUR_PKG.equals(pkg)) return;
                    if (!HomeSetter.lockOn(AgentService.this)) return;
                    new Thread(new Runnable() {
                        @Override public void run() {
                            // 装完的瞬间 PackageManager 还在刷新，等它稳一稳再查候选
                            try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                            try {
                                if (HomeSetter.isTarget(AgentService.this)) return;
                                BootLog.w("桌面: 包变动(" + pkg + ")导致偏好丢失 -> 重设 "
                                        + HomeSetter.ensure(AgentService.this));
                            } catch (Throwable tt) {
                                BootLog.w("桌面: 包变动后重设失败 " + tt);
                            }
                        }
                    }, "ledi-home-fix").start();
                }
            }, f);
            BootLog.i("桌面: 已挂上包变动监听（重装后自动把桌面设回机器人 App）");
        } catch (Throwable t) {
            BootLog.w("桌面: 注册包变动监听失败 " + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // ★★ v134：先记下"这一次是谁把我叫起来的"。判据必须落在**进程之外**
        //    （见 ReviveLog）—— 13 分钟那次死机就是卡在"进程死了，内存计数器全没了"。
        //    顺手写进 BootLog：现场抓 logcat 时不用再去 /status 里翻。
        String ch = markReviveChannel(intent);
        BootLog.i("复活账本: channel=" + ch + " pid=" + android.os.Process.myPid());
        // START_STICKY：被系统杀掉后由 AMS 拉起；这里顺手把齿轮补回来
        if (gearWanted() && !GearOverlay.isShown()) {
            showGear();
        }
        // 心跳/复活闹钟送达 —— 记心跳并保证常驻闹钟还在（见 CrashGuard）。
        // 服务本来就活着时这次 startService 是空操作，所以这里只做记账。
        if (intent != null && CrashGuard.isReviveAction(intent.getAction())) {
            CrashGuard.noteReviveBeat(this);
            BootLog.i("收到复活/心跳闹钟，补排常驻心跳");
            CrashGuard.install(this);
        }
        return START_STICKY;
    }

    /**
     * 把"这一嗓子是谁喊的"落进 {@link ReviveLog}。
     *
     * <p>四种可能，必须分开（否则修完兜底也只有"它活了"这一个事实）：
     * <ol>
     *   <li>{@code pending} 标签 —— {@link BootReceiver} 收到广播后贴的，能精确到
     *       "按键 / 触摸 / 亮屏 / 开机 / 联网"；</li>
     *   <li>{@code alarm} —— 闹钟（{@code CrashGuard.ACTION_REVIVE}）；</li>
     *   <li>{@code ams_sticky} —— {@code intent == null}，只可能是系统按 START_STICKY
     *       重启服务（这条通道以前<b>没人验证过它到底会不会触发</b>，现在有数了）；</li>
     *   <li>{@code explicit} —— 其它显式 {@code am startservice} / 我们自己调。</li>
     * </ol>
     */
    private String markReviveChannel(Intent intent) {
        try {
            String act = intent == null ? null : intent.getAction();
            String[] pend = ReviveLog.consumePending(this);
            if (pend != null) {
                ReviveLog.mark(this, pend[0], pend[1]);
                return pend[0];
            }
            if (CrashGuard.isReviveAction(act)) {
                ReviveLog.mark(this, CrashGuard.reviveChannelOf(act), "action=" + act);
                return CrashGuard.reviveChannelOf(act);
            }
            if (intent == null) {
                ReviveLog.mark(this, "ams_sticky", "intent=null（系统按 START_STICKY 重启）");
                return "ams_sticky";
            }
            ReviveLog.mark(this, "explicit", "action=" + act);
            return "explicit";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    @Override
    public void onDestroy() {
        BootLog.w("=== AgentService.onDestroy（服务要没了）===");
        running = false;
        contRunning = false;                    // 常识别循环自行退出，别让它越过服务生命周期
        I = null;
        housekeeping = false;
        if (housekeepThread != null) housekeepThread.interrupt();
        if (sensor != null) sensor.stop();
        if (locator != null) locator.stop();   // 放掉麦克风，别让下一任进程打不开
        if (proactiveTalk != null) proactiveTalk.stop();
        if (media != null) media.stopAudio();
        if (voiceWake != null) voiceWake.shutdown();
        if (relay != null) relay.stop();
        if (httpServer != null) httpServer.stop();
        if (btServer != null) btServer.stop();
        if (beacon != null) beacon.stop();
        if (bleServer != null) bleServer.stop();
        GearOverlay.hide(this, "onDestroy（服务被销毁）");
        if (face != null) face.hide();
        if (display != null) display.hide();
        if (router != null && router.tts() != null) router.tts().shutdown();
        try { unbindService(engineConn); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
