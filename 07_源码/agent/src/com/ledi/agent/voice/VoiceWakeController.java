package com.ledi.agent.voice;

import com.ledi.agent.core.J;

import com.ledi.agent.core.BootLog;

import com.ledi.agent.core.AgentService;

import com.ledi.agent.cap.Presence;
import com.ledi.agent.cap.ProactiveTalk;
import com.ledi.agent.net.Proxies;
import com.ledi.agent.cap.TalkLog;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 语音唤醒 —— Agent 进程内自己开麦，直接驱动百度离线唤醒 EventManager("wp")。
 *
 * <h3>版本演进</h3>
 * <ul>
 *   <li>v40 AIDL 绑定原厂 WakeUpService：绑定成功但 onWakeUp 零命中。</li>
 *   <li>v41 反射 WakeUpEngine 封装：授权结果不可观测。</li>
 *   <li>v42 直接操作 EventManager + 自己实现 EventListener：<b>诊断盲区打开</b>，
 *       实测 {@code wp.exit desc=java.lang.Exception: WakeUpInitial=-1}。</li>
 *   <li>v43 加装授权透视（挂 java.util.logging Handler + 直接反射 auth()）：<b>拿到真因</b>——
 *       {@code GetLicense getLicenseRet=-101}、{@code VerifyLicense authRet=-2}、
 *       {@code AuthEasrHelper.auth 返回=9101}；同时证明：本包与原厂<b>签名完全一致</b>，
 *       license/libcuid.so 与原厂<b>字节完全相同</b>，设备<b>有网且能通百度</b>。</li>
 *   <li>v44（本版）锁定「包名」是唯一残余差异：用
 *       {@code ctx.createPackageContext(原厂包, 0)} 造原厂包的 Context 去 auth；
 *       若通过，就用该 Context 驱动整个唤醒引擎（引擎读原厂 assets/files，授权自然成立）。</li>
 *   <li>v46/v47 找到解药并落地：native 的 {@code SetLicenseFlag(true)} 能让
 *       {@code smartAuth} 直接短路，{@code WakeUpInitial} 返回 0 → 实测 {@code wp.enter}，
 *       引擎进入监听态（license 实为「绑包名 + 已过期」，换证服务端也已不可用）。</li>
 *   <li>v48（当前）针对「引擎在监听但喊了零命中」，一次测掉两种剩余可能：
 *       ① {@code decodeWakeUpString(WakeUp.bin)} 解出<b>词表</b>（确认该喊什么）；
 *       ② 自开 {@code AudioRecord} 采 1.5s 算 maxAbs/rms/静音占比（证伪"喂进引擎的是静音"）；
 *       ③ 噪音日志改「计数+抽样」，用行数增长证明麦克风数据确实在流动；
 *       ④ 8s 一次的监听自检心跳，把用户喊话时间点与引擎状态对齐；
 *       ⑤ 把 2×8s 的授权网络探测挪出主线程（原先在 onCreate 阻塞 17s，逼近服务 ANR 阈值）。</li>
 * </ul>
 *
 * <h3>v42 反汇编铁证</h3>
 * <pre>
 * EventManagerWp.send(name, params, data, off, len)
 *   ├─ name=="wp.start" && !running -> doWpStart(name, json)    // 同步，会抛异常
 *   └─ 整体 try/catch(Exception)；出错 publishEvent("wp.exit", {desc: 异常串})
 *
 * doWpStart：kws-file 检查 -> res-file 检查 -> smartAuth(context, json)
 *            -> WakeUpInitial(words, kwsFile, 3)!=0 -> throw "WakeUpInitial="+ret
 *            -> publishEvent("wp.enter")
 * 命中：name=="wp.data" -> new JSONObject(params).getString("word")
 *       name=="wp.exit" -> 原厂封装丢弃（本版落盘）
 *
 * AuthEasrHelper 授权码：0 通过；8000 未知；8001；8002 包名；8003 签名；
 *                       8004 CUID；8005 过期；8007；>=9000 服务端/试用(基址9000)
 * auth(Context,Integer,String) 是 PRIVATE STATIC（反射调用）
 * logger = Logger.getLogger("AuthEasrHelper")
 * </pre>
 */
public class VoiceWakeController {

    /** 唤醒命中回调（跑在百度引擎的回调线程，需快速返回，耗时操作应转线程） */
    public interface WakeListener { void onWakeup(String info); }

    /**
     * 唤醒后是否先「听用户说了什么」再应答（v53 新增，<b>默认关</b>）。
     *
     * <p>默认关的原因：离线 ASR 这条路（授权、自造语法、VAD 收尾）本轮尚未在真机验证过，
     * 直接开启会把现在能用的「喊了就应答」变成「喊了没反应」。等下一轮 {@code POST /asrtest}
     * 确认能拿到 {@code results_recognition} 之后，用 {@code POST /asrdialog {"on":true}} 打开。</p>
     */
    public static volatile boolean asrDialog = false;
    /** 对话模式下每次听多久（秒）。 */
    public static volatile int asrDialogSec = 5;

    private static final String EMF_CLS = "com.baidu.speech.EventManagerFactory";
    private static final String EM_CLS  = "com.baidu.speech.EventManager";
    private static final String EL_CLS  = "com.baidu.speech.EventListener";
    private static final String AUTH_CLS = "com.baidu.speech.AuthEasrHelper";
    private static final String UTIL_CLS = "com.baidu.speech.easr.Utility";

    private static final String LIC_NAME = "bds-easr-license.lic";
    private static final String ORIG_PKG = "com.auric.intell.rbt.xldrbtx";

    private static final String APP_ID   = "9528412";
    private static final String KWS_FILE = "assets:///WakeUp.bin";

    /**
     * 外部词表位置（v53）——有它就不必重装 APK 才能换唤醒词。
     *
     * <p>依据 {@code EventContext.loadBytesFromUri} 的反汇编（铁证）：
     * <pre>
     *   if (!uri.contains("://")) uri = "file://" + uri;              // 裸路径也接受
     *   Matcher m = Pattern.compile("(.*?)://(.*)").matcher(uri);
     *   if      (scheme.equalsIgnoreCase("file"))   in = new FileInputStream(rest);   // ★
     *   else if (scheme.equalsIgnoreCase("asset")||"assets")
     *                                               in = getClass().getResourceAsStream("/assets"+rest);
     *   else if (scheme.equalsIgnoreCase("res"))    in = getClass().getResourceAsStream(rest);
     *   ... 按 1024B 块读进 ByteArrayOutputStream ...
     * </pre>
     * {@code file} 分支就是<b>裸 FileInputStream</b>，跑在本进程 uid 下，所以
     * <b>我们自己的 files 目录天然可读</b>，不需要任何存储权限。</p>
     *
     * <p>两个投放点，优先级 /sdcard &gt; files &gt; assets：
     * <ul>
     *   <li>{@code /sdcard/ledi_wake_words.bin} —— 方便 {@code adb push} 手工替换、
     *       也可用 {@code adb shell cat} 直接看；</li>
     *   <li>{@code files/WakeUp.bin} —— HTTP 接口写入的正式副本，不依赖外部存储权限。</li>
     * </ul>
     * 两者都不存在时回落到 APK 内嵌的 {@code assets:///WakeUp.bin}，即原厂行为。</p>
     */
    private static final String VOCAB_SDCARD = "/sdcard/ledi_wake_words.bin";
    private static final String VOCAB_NAME   = "WakeUp.bin";

    private static final String[] BAIDU_LOGGERS = {
            "AuthEasrHelper", "EventManagerWp", "EventManagerFactory",
            "EventManagerMic", "WakeUpTools", "EventContext", "StatHelper"
    };

    private final Context ctx;
    private volatile boolean running;
    private volatile boolean inited;
    private volatile String lastEvent = "";
    private volatile String lastHit = "";
    private volatile String lastError = "";
    private volatile String lastExit = "";
    private volatile String lastAuth = "";      // 本包上下文 auth 返回码
    private volatile String lastAuthOrig = "";  // 原厂包上下文 auth 返回码
    private volatile String wakeWords = "";     // 从 WakeUp.bin 解出的词表（决定该喊什么）
    private volatile String rawWakeWords = "";  // 上面那份的原始串（用于喂 WakeUpInitial）
    private volatile String micInfo = "";       // 麦克风自检结果（证明不是静音）
    private volatile String vocabSource = "assets";  // 词表来源：assets / sdcard / files
    private volatile String activeKws = KWS_FILE;    // 本次 wp.start 实际用的 kws-file
    private volatile String vocabPreset = "asset";   // 词表档位：asset / full / safe / custom
    private final ArrayDeque<String> ring = new ArrayDeque<String>();
    private WakeListener listener;

    // ---- 看门狗状态（v61）----------------------------------------------------
    // 为什么必须有：wp.start 之后引擎会进 wp.enter 监听态，但**任何一次** wp.stop
    // （播报让麦、离线 ASR 探测、系统抢麦…）都会让它退到 wp.exit，而退出之后
    // 没有任何人在意它是否回来了。症状极具欺骗性：running/inited 都是 true、
    // 进程活着、其它功能全正常，界面显示"已广播/已运行"，只有 hits 永远是 0，
    // 表现为「语音无法唤醒机器人」。真机上踩到时的现场就是
    // last_event=wp.exit{"desc":"success, mic stop!"} 且 mic_ago 已经 60 多分钟。
    private volatile boolean listening;      // 是否处于 wp.enter 监听态
    private volatile long lastEventMs;       // 最后一次收到任意 wp.* 事件的时间
    private volatile int restarts;           // 看门狗救回来的次数
    private volatile long lastRestartMs;
    private volatile String lastRestartWhy = "";
    private Thread dog;
    private volatile boolean handling;       // 命中后正在应答（这段时间"不在听"是正常的）
    private volatile long handlingSince;

    private Context engineCtx;              // 实际驱动引擎用的 Context（优先原厂包上下文）
    private Object engine;                  // EventManager("wp") 实例
    private Class<?> elCls;
    private Object elProxy;

    private AsrProbe asrProbe;              // 离线 ASR 探测器（v53，懒建）

    public VoiceWakeController(Context ctx) {
        this.ctx = ctx;
        // ★★★ v167：给采音循环挂一个 3 秒心跳，用来给"正在收听"补灯。
        //
        //   【为什么要补灯】原厂 App 有自己的**周期灯效刷新** —— 实测它约每 6~7 秒
        //   发一次 `com.auric.intell.xld.os.led.all.off`（对照：我们连点 10 次蓝灯，
        //   原厂只灭 3 次，两者不同步 ⇒ 它自发周期，不是"看见我们亮灯就来灭"）。
        //   一次超过 7 秒的收听，灯就被它中途灭掉。而我们**读不回灯**，逻辑锁以为
        //   还亮着 —— 只能定期重发。补灯借的是**已经在跑**的采音循环（每 40ms 一拍），
        //   不新增线程/闹钟/巡检（用户 09-25 明令禁止；MT6580 扛不住）。
        //
        //   【为什么挂在构造函数】必须在**一定会执行**的地方挂一次。
        //   挂在 dialog() 的懒加载里是不行的：那条路只有离线链路才走，在线听写
        //   （cloudListen）根本不碰它 —— 挂在那边等于"只用在线时补灯永远不生效"，
        //   正是本项目"动作挂错地方 = 等于没挂"的老毛病。
        CloudAsr.micTick = new Runnable() {
            @Override public void run() {
                AgentService.listenLed(true, true);
                AgentService.ledKeeperN++;    // 观测：证明补灯真的在跑（见 GET /listenled 的 keeper）
                // ★ v182：听语表情也跟着补一次。它内部比的是 FaceController.getCurrent()，
                //   所以"已经在播"时只是一次字符串比较；只有在被答复表情顶掉后才会重播。
                AgentService.listenFace(true, true);
            }
        };
    }

    public void setWakeListener(WakeListener l) { listener = l; }

    // ---------------- 日志透视 ----------------

    private static volatile boolean logHooked;

    /** 噪音计数（按 logger 名）—— 用来证明「麦克风数据是否真的在流动」 */
    private static final java.util.HashMap<String, Integer> NOISE =
            new java.util.HashMap<String, Integer>();
    private static volatile int micLogLines;       // MicrophoneInputStream 落过的行数
    private static volatile long micLastLogMs;     // 最后一次采音日志的时间戳

    private static int bumpNoise(String lg) {
        synchronized (NOISE) {
            Integer v = NOISE.get(lg);
            int n = (v == null ? 1 : v.intValue() + 1);
            NOISE.put(lg, Integer.valueOf(n));
            return n;
        }
    }

    private static String trim(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() > 90 ? s.substring(0, 90) + "..." : s;
    }

    /**
     * 把百度 SDK 的 java.util.logging 输出接进 BootLog。
     * 只挂在 root logger 上（子 logger 默认 useParentHandlers=true 会向上冒泡），
     * 避免同时挂 root 与具体 logger 造成重复行。
     */
    private static void hookBaiduLogging() {
        if (logHooked) return;
        try {
            Handler h = new Handler() {
                @Override public void publish(LogRecord r) {
                    try {
                        String lg = r.getLoggerName();
                        if (lg == null) return;
                        String msg = r.getMessage();
                        Object[] ps = r.getParameters();
                        if (msg != null && ps != null && ps.length > 0 && msg.indexOf('%') >= 0) {
                            try { msg = String.format(msg, ps); } catch (Throwable ignore) {}
                        }
                        if (r.getThrown() != null) msg = msg + " [thrown=" + r.getThrown() + "]";

                        // 采音/消息池是「每帧一条」，直接落盘会瞬间刷爆（BootLog 64KB 轮转，
                        // 曾把授权诊断行整段冲掉）。这里改成「计数 + 抽样」：
                        // 既压住噪音，又能从计数的持续增长证明麦克风数据确实在往引擎里送。
                        if (lg.contains("MicrophoneInputStream")) {
                            micLogLines++;
                            micLastLogMs = System.currentTimeMillis();
                            int c = bumpNoise(lg);
                            if (c % 400 == 0) {
                                BootLog.i("baidu[" + lg + "] 采音持续中 累计=" + c + " 行 样例=" + trim(msg));
                            }
                            return;
                        }
                        if (lg.contains("EventManagerMessagePool")) {
                            int c = bumpNoise(lg);
                            if (c % 600 == 0) {
                                BootLog.i("baidu[" + lg + "] 消息池 累计=" + c + " 行 样例=" + trim(msg));
                            }
                            return;
                        }
                        BootLog.i("baidu[" + lg + "] " + msg);
                    } catch (Throwable ignore) {}
                }
                @Override public void flush() {}
                @Override public void close() {}
            };
            Logger root = Logger.getLogger("");
            root.setLevel(Level.ALL);
            root.addHandler(h);
            for (String n : BAIDU_LOGGERS) {
                Logger lg = Logger.getLogger(n);
                lg.setLevel(Level.ALL);
                lg.setUseParentHandlers(true);   // 确保冒泡到 root（不重复挂 handler）
            }
            logHooked = true;
            BootLog.i("语音唤醒 已挂百度日志 Handler（仅 root，避免重复）");
        } catch (Throwable t) {
            BootLog.w("语音唤醒 挂日志 Handler 失败: " + t);
        }
    }

    // ---------------- 授权透视 ----------------

    /** 反射调用 AuthEasrHelper.auth(c, appId, licensePath)，返回返回码（-999=异常） */
    private int probeAuth(Context c, String tag) {
        try {
            File lic = new File(c.getFilesDir(), LIC_NAME);
            Class<?> cls = Class.forName(AUTH_CLS);
            Method m = cls.getDeclaredMethod("auth", Context.class, Integer.class, String.class);
            m.setAccessible(true);
            Object ret = m.invoke(null, c, Integer.valueOf(Integer.parseInt(APP_ID)),
                    lic.getAbsolutePath());
            int code = (ret instanceof Integer) ? ((Integer) ret).intValue() : -999;
            String s = code + " (" + authName(code) + ")";
            BootLog.w("语音唤醒 auth[" + tag + "] 返回=" + s
                    + " pkg=" + c.getPackageName() + " path=" + lic.getAbsolutePath());
            return code;
        } catch (Throwable t) {
            BootLog.w("语音唤醒 auth[" + tag + "] 调用失败: " + chain(t));
            return -999;
        }
    }

    private static String authName(int code) {
        switch (code) {
            case 0:    return "AUTH_PASSED 授权通过";
            case 8000: return "AUTH_FAILED_UNKNOWN 未知失败";
            case 8001: return "AUTH_FAILED_1";
            case 8002: return "AUTH_FAILED_PACKAGE_NAME 包名不匹配";
            case 8003: return "AUTH_FAILED_SIGNATURE 签名不匹配";
            case 8004: return "AUTH_FAILED_CUID 设备CUID不匹配";
            case 8005: return "AUTH_FAILED_LICENSE_EXPIRED 授权已过期";
            case 8007: return "AUTH_FAILED_7";
            default:
                if (code >= 9000) return "服务端/试用错误(code=" + code + ")";
                return "未知 code=" + code;
        }
    }

    private void probeCuid() {
        try {
            Class<?> u = Class.forName(UTIL_CLS);
            Method m;
            try {
                m = u.getMethod("getCUID", Context.class);
            } catch (NoSuchMethodException e) {
                m = u.getDeclaredMethod("getCUID", Context.class);
                m.setAccessible(true);
            }
            BootLog.w("语音唤醒 本进程 cuid=" + m.invoke(null, ctx));
        } catch (Throwable t) {
            BootLog.w("语音唤醒 取 cuid 失败: " + chain(t));
        }
    }

    private void probeIdentity() {
        try {
            PackageManager pm = ctx.getPackageManager();
            BootLog.w("语音唤醒 本包=" + ctx.getPackageName()
                    + " 签名MD5=" + sigMd5(pm, ctx.getPackageName()));
            BootLog.w("语音唤醒 原厂包=" + ORIG_PKG + " 签名MD5=" + sigMd5(pm, ORIG_PKG));
        } catch (Throwable t) {
            BootLog.w("语音唤醒 取包信息失败: " + chain(t));
        }
    }

    private String sigMd5(PackageManager pm, String pkg) {
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            if (pi == null || pi.signatures == null || pi.signatures.length == 0) return "null";
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(pi.signatures[0].toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    /**
     * 选定驱动引擎用的 Context：
     *   先用本包上下文跑一次 auth（对照组），再尝试原厂包上下文；
     *   原厂包 auth 通过就用原厂包上下文（license 大概率绑定包名）。
     */
    private void chooseEngineContext() {
        int rcSelf = probeAuth(ctx, "本包");
        lastAuth = rcSelf + " (" + authName(rcSelf) + ")";
        engineCtx = ctx;

        if (rcSelf == 0) {
            BootLog.i("语音唤醒 本包上下文 auth 已通过，直接用本包上下文");
            return;
        }
        try {
            Context oc = ctx.createPackageContext(ORIG_PKG, 0);
            if (oc == null) {
                BootLog.w("语音唤醒 createPackageContext 返回 null");
                return;
            }
            int rcOrig = probeAuth(oc, "原厂包");
            lastAuthOrig = rcOrig + " (" + authName(rcOrig) + ")";
            if (rcOrig == 0) {
                engineCtx = oc;
                lastError = "";
                BootLog.i("语音唤醒 ★原厂包上下文 auth 通过 -> 用原厂包 Context 驱动引擎");
            } else {
                BootLog.w("语音唤醒 原厂包上下文 auth 也未通过（code=" + rcOrig + "），回退本包上下文");
            }
        } catch (Throwable t) {
            BootLog.w("语音唤醒 createPackageContext(" + ORIG_PKG + ") 失败: " + chain(t));
        }
    }

    // ---------------- 词表 / 麦克风体检（v48） ----------------

    /**
     * 用百度自己的 WakeUpTools.decodeWakeUpString 解出 WakeUp.bin 里的词表。
     *
     * 为什么必要：doWpStart 里 words 就是 `decodeWakeUpString(loadBytesFromUri(kws-file))`
     * 再按 `[\s,\n]` 切分后喂给 WakeUpInitial。也就是说<b>唤醒词由这个 bin 决定</b>——
     * 想知道该喊什么，只能解它。命不中时第一个要排除的就是「喊的词压根不在词表里」。
     */
    private String decodeWakeWords() {
        try {
            byte[] raw = readEffectiveVocab();
            if (raw == null || raw.length == 0) throw new Exception("词表字节为空");

            String s = null;
            try {
                Class<?> wt = Class.forName("com.baidu.speech.WakeUpTools");
                // 注意：decodeWakeUpString 是「包级私有」（DEX 里 doWpStart 靠同包直接 invoke-static），
                // getMethod 只能找 public 会抛 NoSuchMethodException —— 必须 getDeclaredMethod。
                Method m;
                try {
                    m = wt.getDeclaredMethod("decodeWakeUpString", byte[].class);
                } catch (NoSuchMethodException e) {
                    m = wt.getMethod("decodeWakeUpString", byte[].class);
                }
                m.setAccessible(true);
                s = (String) m.invoke(null, (Object) raw);
            } catch (Throwable t) {
                BootLog.w("语音唤醒 百度 decodeWakeUpString 不可用(" + chain(t) + ")，改用本机复刻解码");
            }
            if (s == null || s.length() == 0) s = Vocabulary.decode(raw);   // 复刻实现兜底
            rawWakeWords = s;

            StringBuilder sb = new StringBuilder();
            for (String t : s.split("[\\s,\n]")) {
                if (t.length() > 0) sb.append('[').append(t).append(']');
            }
            wakeWords = sb.toString();
            BootLog.w("语音唤醒 ★词表(" + vocabSource + ", " + raw.length + "B) 解出="
                    + wakeWords + " 原始串=" + trim(s));
            return wakeWords;
        } catch (Throwable t) {
            wakeWords = "ERR:" + chain(t);
            BootLog.w("语音唤醒 词表解码失败: " + wakeWords);
            return wakeWords;
        }
    }

    /** 读当前生效的词表字节（/sdcard &gt; files &gt; assets），并把来源记进 {@link #vocabSource}。 */
    private byte[] readEffectiveVocab() throws Exception {
        File s = new File(VOCAB_SDCARD);
        if (s.isFile() && s.length() > 0) {
            vocabSource = "sdcard";
            return readAll(new FileInputStream(s));
        }
        File f = new File(ctx.getFilesDir(), VOCAB_NAME);
        if (f.isFile() && f.length() > 0) {
            vocabSource = "files";
            return readAll(new FileInputStream(f));
        }
        vocabSource = "assets";
        return readAll(ctx.getAssets().open("WakeUp.bin"));
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    // ---------------- 词表热替换（v53） ----------------

    /** 解析本次 wp.start 该用的 kws-file，并顺带把来源刷新到 {@link #vocabSource}。 */
    private String resolveKwsFile() {
        File s = new File(VOCAB_SDCARD);
        if (s.isFile() && s.length() > 0) {
            vocabSource = "sdcard";
            return "file://" + s.getAbsolutePath();
        }
        File f = new File(ctx.getFilesDir(), VOCAB_NAME);
        if (f.isFile() && f.length() > 0) {
            vocabSource = "files";
            return "file://" + f.getAbsolutePath();
        }
        vocabSource = "assets";
        return KWS_FILE;
    }

    /**
     * 换唤醒词表 —— <b>不需要重装 APK</b>。
     *
     * <p>同时写到 files 与 /sdcard 两个位置（/sdcard 失败不算错，只是少个方便查看的副本），
     * 然后 stop + start 让 {@code doWpStart} 重新读词表；{@code mUsingWords} 与 native
     * 的 {@code WakeUpInitial} 用的都是这一份，所以立刻生效。</p>
     *
     * @param preset 档位名：{@code full} / {@code safe} / {@code base}；为空且 words 也为空 → 走 full
     * @param words  自定义词表串（逗号或空白分隔）；非空时优先于 preset
     * @return 结果 JSON
     */
    public synchronized String setVocabulary(String preset, String words) {
        String p = preset == null ? "" : preset.trim();
        String w = words == null ? "" : words.trim();
        String err = "";
        String source;
        try {
            if (w.length() > 0) {
                vocabPreset = "custom";
            } else {
                w = Vocabulary.preset(p);
                vocabPreset = (p.length() == 0) ? "full" : p.toLowerCase();
            }
            byte[] data = Vocabulary.encode(w);

            File f = new File(ctx.getFilesDir(), VOCAB_NAME);
            writeBytes(f, data);
            // /sdcard 副本：只为方便 adb 查看/替换，写不进去不影响功能
            try {
                writeBytes(new File(VOCAB_SDCARD), data);
            } catch (Throwable t) {
                BootLog.w("语音唤醒 词表写 /sdcard 失败（不影响功能）: " + chain(t));
            }

            if (running) { sendStop(); try { Thread.sleep(250); } catch (InterruptedException ignored) {} sendStart(); }
            decodeWakeWords();          // 立刻回读一次，把解出的词表暴露到 status
            source = vocabSource;
            BootLog.w("语音唤醒 ★词表已热替换 preset=" + vocabPreset + " 词数="
                    + Vocabulary.split(w).size() + " 来源=" + source + " 明文=" + trim(w));
        } catch (Throwable t) {
            err = chain(t);
            source = vocabSource;
            BootLog.w("语音唤醒 词表热替换失败: " + err);
        }
        return "{\"preset\":\"" + J.esc(vocabPreset) + "\",\"wrote\":\"" + J.esc(source)
                + "\",\"words\":\"" + J.esc(vocabWords()) + "\",\"count\":"
                + Vocabulary.split(rawWakeWords).size()
                + ",\"active_kws\":\"" + J.esc(activeKws)
                + "\",\"error\":\"" + J.esc(err) + "\"}";
    }

    /** 丢弃外部词表，回落到 APK 内嵌的 {@code assets/WakeUp.bin}。 */
    public synchronized String resetVocabulary() {
        StringBuilder removed = new StringBuilder();
        try {
            File f = new File(ctx.getFilesDir(), VOCAB_NAME);
            if (f.exists() && f.delete()) removed.append("files ");
            File s = new File(VOCAB_SDCARD);
            if (s.exists() && s.delete()) removed.append("sdcard ");
        } catch (Throwable t) {
            BootLog.w("语音唤醒 词表复位删除失败: " + chain(t));
        }
        vocabPreset = "asset";
        try {
            if (running) { sendStop(); Thread.sleep(250); sendStart(); }
            decodeWakeWords();
        } catch (Throwable t) {
            BootLog.w("语音唤醒 词表复位重启失败: " + chain(t));
        }
        return "{\"preset\":\"asset\",\"removed\":\"" + J.esc(removed.toString().trim())
                + "\",\"source\":\"" + J.esc(vocabSource)
                + "\",\"words\":\"" + J.esc(vocabWords()) + "\"}";
    }

    /** status 里展示用的词表（带方括号，与旧字段 wake_words 格式一致）。 */
    public String vocabWords() { return wakeWords; }

    /** 当前词表的明文串（诊断用）。 */
    public synchronized String vocabularyInfo() {
        String srcPath;
        if ("sdcard".equals(vocabSource)) srcPath = VOCAB_SDCARD;
        else if ("files".equals(vocabSource)) srcPath = new File(ctx.getFilesDir(), VOCAB_NAME).getAbsolutePath();
        else srcPath = "assets:///WakeUp.bin";
        return "{\"source\":\"" + J.esc(vocabSource) + "\",\"preset\":\"" + J.esc(vocabPreset)
                + "\",\"path\":\"" + J.esc(srcPath)
                + "\",\"raw\":\"" + J.esc(trim(rawWakeWords))
                + "\",\"words\":\"" + J.esc(wakeWords)
                + "\",\"count\":" + Vocabulary.split(rawWakeWords).size()
                + ",\"active_kws\":\"" + J.esc(activeKws)
                + "\",\"presets\":{\"base\":\"" + J.esc(Vocabulary.BASE)
                + "\",\"safe\":\"" + J.esc(Vocabulary.PRESET_SAFE)
                + "\",\"full\":\"" + J.esc(Vocabulary.PRESET_FULL) + "\"}}";
    }

    private static void writeBytes(File dst, byte[] data) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        FileOutputStream out = new FileOutputStream(dst);
        try {
            out.write(data);
            out.flush();
        } finally {
            out.close();
        }
    }

    // ---------------- 离线 ASR 探测（v53） ----------------

    /**
     * 跑一轮离线语音识别，把「用户说了什么」变成文本。
     *
     * <p>这是端到端通路里此前<b>完全缺失</b>的一环：Agent 过去只有「唤醒 → 拿固定文案问 AI →
     * 播报」，没有任何 ASR。这里先用最小代价把离线 ASR 跑通并暴露成 HTTP 接口，
     * 下一轮真机直接 {@code curl -XPOST .../asrtest} 就能验证。</p>
     *
     * @param seconds 监听秒数 1..20（默认 8）
     * @param grammar 语法 URI，空则用 assets 里的命令词表
     * @param extra   额外 asr.start 参数 JSON（可覆盖 vad/decoder/nlu/slot-data 等）
     */
    public synchronized String asrTest(int seconds, String grammar, String extra) {
        return asrTest(seconds, grammar, extra, null);
    }

    /**
     * 同上，多一个 {@code infile}：给一个设备上的<b>裸 PCM 文件路径</b>
     * （16k/16bit/单声道），引擎就读文件而不开麦 —— 免麦克风的确定性测试。
     *
     * <p>参数键与格式依据见 {@link AsrProbe#INFILE_KEY}（反汇编
     * {@code EventManagerMic.createMicrophoneInputStream} 逐指令得出）。
     * 有这条路，才能把「解码对不对」从「麦克风/噪声/授权」里单独摘出来验。</p>
     *
     * @param infile 设备上文件路径，如 {@code /sdcard/ledi_test.pcm}；空则走麦克风
     */
    public synchronized String asrTest(int seconds, String grammar, String extra, String infile) {
        boolean wasRunning = running;
        boolean useFile = infile != null && infile.trim().length() > 0;
        BootLog.w("语音唤醒 ★离线 ASR 探测开始 " + seconds + "s"
                + (useFile ? "（文件输入 " + infile + "，不开麦）" : "（麦克风输入）"));
        // ★ 只有走麦克风时才需要让麦。文件输入根本不碰麦，停唤醒引擎纯属白停。
        if (wasRunning && !useFile) { sendStop(); }
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        try {
            if (asrProbe == null) {
                String resFile = ctx.getApplicationInfo().nativeLibraryDir + "/" + AsrProbe.RES_NAME;
                String licPath = new File(ctx.getFilesDir(), LIC_NAME).getAbsolutePath();
                asrProbe = new AsrProbe(ctx, new Runnable() {
                    @Override public void run() { forceLicenseFlag(); }   // ★ 与唤醒同一枚解药
                }, licPath, resFile);
            }
            return asrProbe.probe(seconds, grammar, extra, infile);
        } catch (Throwable t) {
            BootLog.w("语音唤醒 离线 ASR 探测异常: " + chain(t));
            return "{\"error\":\"" + J.esc(chain(t)) + "\"}";
        } finally {
            if (wasRunning && !useFile && !asrNoResume) sendStart();   // 探测完把唤醒引擎还回去
        }
    }

    /** ASR 探测器是否还在收事件。 */
    public boolean isAsrBusy() { return asrProbe != null && asrProbe.busy; }

    // ---------------------------------------------------------------- 对话通路

    private VoiceDialog dialog;
    private final Object dialogLock = new Object();

    /**
     * 对话通路（v58）：唤醒之后"听一整句话"，说完静音就交给 AI。
     *
     * <p>和 {@link #listenOnce} 的区别是**收尾由语音活动决定**而不是由秒表决定：
     * 最长听 {@link VoiceDialog#maxListenMs}，但一旦确认人开过口，静音满
     * {@link VoiceDialog#silenceMs} 就立刻收尾。同一套会话还能在播报时继续开着
     * 当"语音打断"用。</p>
     *
     * <p>懒建，但<b>只建一次</b>——引擎和监听器都要复用，反复 new 会漏注册监听器。</p>
     */
    public VoiceDialog dialog() {
        synchronized (dialogLock) {
            if (dialog == null) {
                dialog = VoiceDialog.create(ctx, new Runnable() {
                    @Override public void run() { forceLicenseFlag(); }
                });
                // ★ v70：把"麦克风交接"接到对话通路上。
                //
                // 踩过（v69 现场）：`releaseMicForDialog()` 早就写好了，却**从来没有任何地方调用**
                // ——典型的死方法。于是从 HTTP 开一轮 ASR 时，唤醒引擎还在采音
                // （日志里 `baidu[MicrophoneInputStream] 采音持续中`），两个引擎同时抓同一个麦，
                // 进程当场被杀，而且**没留 tombstone**、logcat 又是空的，看不出死因。
                // 现在把它挂在会话的两端，任何调用方都不可能再漏掉。
                //
                // ★ v99：闸门本体提到 {@link #beforeAsrMic()} / {@link #afterAsrMic()} ——
                //   在线听写（CloudAsr）走的是另一条路，但**必须**走同一份交接实现。
                //   本项目吃过"修在一个地方 ≠ 修好了"：同一个坑在别的类里修过、这里再踩一遍。
                dialog.setMicGate(new VoiceDialog.MicGate() {
                    @Override public void beforeAsr() { beforeAsrMic(); }
                    @Override public void afterAsr() { afterAsrMic(); }
                });
            }
            return dialog;
        }
    }

    /**
     * 对话通路正在独占麦克风。
     *
     * <p>为什么要单独一个旗子而不是复用 {@link #handling}：{@code handling} 的意思是
     * "被唤醒后正在应答"，它由 {@code dispatchWake} 置位、由 {@code resume()} 清位；
     * 而 HTTP 直接开的 ASR 会话不属于任何一次应答，生命周期也完全不同
     * （开了就得等它自己收尾）。混用一个标志会让看门狗在会话中途把 wp 拉起来抢麦。</p>
     */
    private volatile boolean micForDialog;

    // ------------------------------------------------ 常识别（v75）

    /**
     * 常识别开着没有。
     *
     * <p>与唤醒词是<b>互斥</b>的两种听法：这台机器只有一个麦克风入口，wp 与 ASR
     * 同时抓麦在真机上会直接把进程搞死。所以开常识别 = 停 wp，关常识别 = 把 wp 还回去。</p>
     */
    private volatile boolean continuous;

    /** "机器人在说话吗"的判据来源，由 AgentService 注入（实现方是 {@code TtsController.isSpeaking()}）。 */
    public interface SpeakingGate { boolean speaking(); }

    private volatile SpeakingGate speakingGate;

    public void setSpeakingGate(SpeakingGate g) { speakingGate = g; }

    public boolean isSpeaking() {
        SpeakingGate g = speakingGate;
        try { return g != null && g.speaking(); } catch (Throwable ignored) { return false; }
    }

    public boolean isContinuous() { return continuous; }

    /**
     * 对话通路正独占麦克风（见 {@link #micForDialog}）。
     *
     * <p>★ 给 {@code ProactiveTalk} 用的让路闸：机器人自己的喇叭走 AEC，**它一开口，
     * 自家麦克风里的近端人声会被连着压掉**。所以"主动闲聊"绝不能在用户正被听写时开喇叭。
     * 实测（v107）：整链验收时云端回 {@code 10118 您好像没有说话哦}，而设备侧同时是
     * {@code rms_max=3965 / saw_speech=true / write_fails=0} —— 看着"有声"、云端听着"不是人话"，
     * 根因就是 {@code via=proactive} 的机械空闲闲聊与用户说话撞在了同一时刻。</p>
     */
    public boolean isMicForDialog() { return micForDialog; }

    /**
     * 开关常识别。
     *
     * <p>开：停掉唤醒引擎，把麦克风让给常驻的 ASR 循环
     * （循环本身在 {@code AgentService.continuousLoop()}，因为"听到话之后要干什么"
     * 只有那边知道：要问 AI、要播报、要推对话日志）。</p>
     *
     * <p>关：把唤醒引擎拉回来，回到"喊唤醒词才理你"的老行为。</p>
     */
    public synchronized String setContinuous(boolean on) {
        if (continuous == on) {
            return "{\"continuous\":" + on + ",\"changed\":false}";
        }
        continuous = on;
        VoiceDialog.continuous = on;                 // 让 /status 与 ASR 参数看到同一份真相
        if (on) {
            // 停 wp：接下来麦克风由常识别独占。这里必须**真的停掉**而不是只标记，
            // 否则 wp 会继续采音，两个引擎抢麦 → 进程被杀（且不留 tombstone）。
            sendStop();
            BootLog.i("常识别 ★开启：已停唤醒引擎，麦克风交给 ASR 循环");
        } else {
            BootLog.i("常识别 ★关闭：唤醒引擎还回去");
            if (running) resume();
        }
        return "{\"continuous\":" + on + ",\"changed\":true,\"running\":" + running
                + ",\"listening\":" + listening + "}";
    }

    /** 唤醒引擎和 ASR 引擎抢同一个麦克风：进对话前必须先让麦。 */
    public void releaseMicForDialog() {
        boolean was = running;
        if (was) sendStop();
        // ★ v103：280ms → 450ms。
        //   代价是每轮多等 170ms，换来的是"不会在唤醒引擎还没松手时就去开麦"。
        //   280ms 是当年为**离线** ASR 调的（那套的 mic 是 SDK 内部自己开的，
        //   对时序宽容）；在线这条是我们自己开 AudioRecord 直接读，
        //   打开成功但 read() 一直返回 <=0 就是这一族的症状。
        //   真正的保险在 CloudAsr.listenMic 里（读不到会自己重开一次麦，
        //   再不行就早退并明说"麦克风被占用"），这里只是把窗口先给足。
        try { Thread.sleep(450); } catch (InterruptedException ignored) {}
    }

    // ------------------------------------------------- 麦克风交接（唯一实现，v99）

    /**
     * 要麦：<b>两条听写路（离线 VoiceDialog / 在线 CloudAsr）共用这一份</b>。
     *
     * <p>顺序不能反 —— 先立 {@code micForDialog} 旗、再停 wp。旗子先立，看门狗这一拍
     * 看了就知道"此刻不在监听是故意的"，不会把 wp 拉回来抢麦。</p>
     */
    private void beforeAsrMic() {
        micForDialog = true;
        releaseMicForDialog();
        // ★★★ v167（用户 2026-09-25）：「机器人在收听的时候头顶亮蓝灯」。
        //   挂**这一处**而不是逐个调用点：本方法就是"机器人在收听"的唯一咽喉 ——
        //   在线走 cloudListen()、离线走 VoiceDialog 的 MicGate，两条路都汇到这里。
        //   将来任何新增的开麦点都自动被灯语覆盖（避免"修在一个地方 ≠ 修好了"）。
        // ★★★ v178：改走 setMicGate —— 它除了点灯还会记下"麦闸开着"这个事实，
        //   好让"说话期间不亮"（见 AgentService.listenLed 顶部）有据可依。
        //   说话期间开麦（防打断的 barge-in）会在这里被挡掉，正是要的效果。
        AgentService.setMicGate(true);
    }

    /**
     * 还麦。必须保证被调到：没还回去 = 唤醒引擎一直哑着。
     *
     * <p>应答期（抹头顶 / 唤醒词触发的整轮对话）的恢复由 {@code AgentService} 收尾时
     * 自己调 {@link #resume()}：那里要等 TTS 播完才敢重开麦，否则会被自己的喇叭唤醒。
     * ★ 常识别期间也<b>不能</b>恢复：那条路的规矩是"麦克风长期归 ASR"，
     * 每收尾一次就把 wp 拉起来一次，会造成两个引擎反复抢麦。</p>
     */
    private void afterAsrMic() {
        // ★★★ v167：收麦即灭灯，且**放在 resume() 之前**。
        //   灯只代表"麦克风此刻归 ASR、正在听你这句话"；下面 resume() 会把唤醒引擎拉回来
        //   接着采音，那是在听**唤醒词**（常态、一直在跑），不是"正在收听"，不该亮。
        //   force=true：中途可能已被原厂灭掉，这里要的是"确保灭"，不是"我觉得该灭"。
        // ★★★ v178：改走 setMicGate —— 顺带把"麦闸已关"记下来，
        //   这样说话结束的回调（noteTtsSpeaking(false)）不会把灯又亮起来。
        AgentService.setMicGate(false);
        micForDialog = false;
        if (!handling && !continuous) resume();
    }

    // ------------------------------------------------- 在线听写路由（v99）

    /**
     * 听一句话时优先走在线（讯飞 MSC，任意话），不通才回落离线（百度 45 条命令词）。
     *
     * <p>默认开。用户的原话是「我需要机器人完全听得懂我说的每一句话，而不是只会听那几句话」
     * —— 离线那条路是语法受限解码器 + 词表固化在声学模型里，结构上做不到任意话
     * 
     * 但<b>兜底必须留着</b>：断网时那 45 条命令词仍然能用，所以这里是"在线优先"
     * 而不是"只在线"。</p>
     */
    private volatile boolean onlineAsr = true;

    /**
     * 离线模式（v142，{@link Presence} 持有唯一真值，这里只是它的一个只读副本）。
     *
     * <p>与 {@link #onlineAsr} 的区别：{@code onlineAsr=false} 是"用户/设置里把在线听写关了"，
     * 那时还会**回落到本机离线 ASR**（那 45 条命令词）；而离线模式是"连续几次都没听懂，
     * 停止对语音的输入"—— 连离线 ASR 也不跑，全机只剩唤醒引擎在听（零成本），
     * 因为用户要的就是"别再一直听了"。见 {@link Presence} 的类注释。</p>
     */
    private volatile boolean offlineAsr;

    /** 由 {@link Presence.enterOffline}/{@code exitOffline} 调用（唯一调用点）。 */
    public void setOffline(boolean on) {
        offlineAsr = on;
        BootLog.i("在线听写 " + (on ? "★切到离线模式（停止对语音的输入，只留唤醒引擎）"
                : "回到在线（在线听写已恢复）"));
    }

    public boolean isOfflineAsr() { return offlineAsr; }

    public boolean isOnlineAsr() { return onlineAsr; }

    public String setOnlineAsr(boolean on) {
        onlineAsr = on;
        BootLog.i("在线听写 ★" + (on ? "开" : "关") + "（"
                + (on ? "任意话走云端，不通才回落离线" : "只用离线 45 条命令词") + "）");
        return "{\"online_asr\":" + on + "}";
    }

    /**
     * 上一轮"是谁听懂的"。
     *
     * <p>双端对照表要能看到这个 —— 否则一串对话里分不清哪句是在线答的、哪句是离线答的，
     * "记成 direct 就是假账"那类错误还会再犯（见 v93 的 engine 字段教训）。</p>
     */
    private volatile String lastInputRoute = "none";
    private volatile String lastInputNote = "";
    private volatile long lastInputMs;

    /**
     * 最近一轮听写的完整仪表。
     *
     * <p>★ 为什么必须把它挂到 {@code /status}：对话链路（唤醒 → 听 → AI → 播报）
     * 平时只留一句"听到=[...]"，出了问题根本分不清是"麦克风没开"、"读不到数据"、
     * "人没说话"还是"云端拒绝"。而设备日志（{@code /sdcard/ledi_agent.log}）
     * 超过 64KB 会被**整份删掉**，Baidu 的 VAD 日志几分钟就把它轮换掉 ——
     * <b>"日志里没有"不能当证据</b>（v103 现场就栽在这上面）。
     * 所以仪表必须走内存里的这条通道。</p>
     */
    private volatile CloudAsr.MicResult lastMicResult;

    public String listenStatusJson() {
        CloudAsr.MicResult m = lastMicResult;
        return "{\"online_on\":" + onlineAsr
                + ",\"last_route\":\"" + J.esc(lastInputRoute) + "\""
                + ",\"last_note\":\"" + J.esc(lastInputNote) + "\""
                + ",\"last_ms\":" + lastInputMs
                + ",\"last\":" + (m == null ? "null" : m.diagJson()) + "}";
    }

    /** 给对话日志用：这一句是谁听懂的（cloud / cloud-empty / offline / none）。 */
    public String lastInputRouteNow() { return lastInputRoute; }

    /** 给对话日志用：这条路有没有出过问题（如"在线不通 → 回落离线"）。 */
    public String lastInputNoteNow() { return lastInputNote; }

    /** 在线通路的唯一持有者（复用 CommandRouter 那一份，见 CloudAsr.get 的注释）。 */
    private CloudAsr cloudAsr;

    private CloudAsr cloud() {
        CloudAsr c = cloudAsr;
        if (c == null) {
            c = CloudAsr.get(ctx);
            cloudAsr = c;
        }
        return c;
    }

    /** 要麦 → 在线听一轮 → 还麦。麦克风交接与离线那条路<b>共用同一份实现</b>。 */
    /**
     * ★ v217：常识别走云之前的**本地门控**（零云开销）。
     *
     * <p>为什么要有它：离线 ASR 的**麦克风模式**在本机必被授权挡掉 —— 实测
     * {@code /asrtest {"sec":5}}（麦克风）回 {@code err_no -3004 app name unknwon}
     * 而同一份 cmd 语法走 {@code infile} 文件模式就正常出文本。所以常识别只能走云；
     * 而"不用的时候别一直往上送"必须靠这道**不建云会话**的本地闸守住。</p>
     *
     * @see CloudAsr#hasSpeech(int, int)
     */
    public boolean hasVoice(int maxMs) {
        try {
            CloudAsr c = cloud();
            return c != null && c.hasSpeech(maxMs, 0);
        } catch (Throwable t) {
            BootLog.w("本地门控 失败: " + chain(t));
            return false;
        }
    }

    /**
     * ★★ v220：等房间安静下来再开麦（自适应，替代固定延时）。
     *
     * <p>机器人喇叭余响在 {@code isSpeaking()} 转 false 后还会持续 ~2.6s（实测
     * {@code rms≈20600}，与真人语音同量级）⇒ 不等它就开麦，采到的全是它自己：
     * 常识别侧会白建云会话（10118），对话侧会出现
     * {@code 第 N 轮 第 1/3 次听：[] 路线=cloud-quiet}。见 {@link CloudAsr#waitQuiet}。</p>
     *
     * @return true = 已安静；false = 超时（调用方**照常往下走**，不许卡住整轮）
     */
    public boolean waitQuiet(int maxMs) {
        try {
            CloudAsr c = cloud();
            return c != null && c.waitQuiet(maxMs, 0, 0);
        } catch (Throwable t) {
            BootLog.w("等安静 失败: " + chain(t));
            return false;
        }
    }

    public CloudAsr.MicResult cloudListen(int maxMs, int silenceMs, int loudRms) {
        return cloudListen(maxMs, silenceMs, loudRms, null);
    }

    /** 同上，但可把喂进引擎的裸 PCM 落盘（诊断用，见 CloudAsr.listenMic 的说明）。 */
    public CloudAsr.MicResult cloudListen(int maxMs, int silenceMs, int loudRms, String dumpPath) {
        return cloudListen(maxMs, silenceMs, loudRms, dumpPath, 1);
    }

    /** 全参版：{@code audioSource} 见 CloudAsr.listenMic 的说明（1=MIC / 6=VOICE_RECOGNITION）。 */
    public CloudAsr.MicResult cloudListen(int maxMs, int silenceMs, int loudRms, String dumpPath,
                                          int audioSource) {
        beforeAsrMic();
        try {
            return cloud().listenMic(maxMs, silenceMs, loudRms, null, dumpPath, audioSource);
        } finally {
            afterAsrMic();
        }
    }

    /**
     * 离线模式下的「名字探测」（v142，<b>默认关</b>）。
     *
     * <p>为什么默认关：这是唯一一条"离线模式下还会花配额"的通路。用户最在意的就是
     * "不用的时候别一直往上送"。它只在用户显式打开
     * （{@code POST /name {"probe_sec":45}}）后才按周期跑一次短听写，
     * 用来听"是不是有人在叫它的名字"。</p>
     *
     * <p>★ 为什么不能只靠唤醒词：唤醒词是本机资源、零成本、永远可用，所以它是主通路
     * （见 {@code dispatchWake}）。但它是<b>出厂固化</b>的（证据：{@code WakeUp.bin}
     * 是 32 条相同记录，不是词表；离线声学词表同样固化在 {@code libbd_easr_*.so}）——
     * 自定义的名字只能靠这一条探测去近似。两者并用：默认只靠唤醒词，
     * 想要自定义名字就打开探测，代价是每 probe_sec 秒一次极短的在线听写。</p>
     *
     * @return 结果串（给端点/日志看）
     */
    public String probeName() {
        if (!Presence.offline) return "not offline";
        if (!onlineAsr) return "online asr off";
        CloudAsr.MicResult mr;
        try {
            mr = cloudListen(2600, 700, 0);
        } catch (Throwable t) {
            return "probe fail: " + chain(t);
        }
        lastMicResult = mr;
        String txt = mr.textTrim();
        if (!txt.isEmpty()) {
            BootLog.i("离线 名字探测听到=[" + txt + "] (route=" + mr.route + ")");
            if (Presence.calledBy(ctx, txt)) {
                Presence.exitOffline(ctx, "听到名字「" + Presence.name(ctx) + "」");
                return "name heard: " + txt;
            }
        }
        return "probe miss route=" + mr.route + " text=" + txt;
    }

    /**
     * 听一整句话：<b>在线优先，离线兜底</b>。
     *
     * <p>回落判据是"在线这条<b>通不了</b>"（初始化失败 / 麦克风打不开 / 服务端回错误码），
     * <b>不是</b>"没听到内容"。人没说话（{@code errCode==0} 而 text 为空，或
     * {@code 10118 您好像没有说话哦}）是<b>合法结果</b> —— 这时候再去跑一遍离线引擎
     * 不但白等十几秒，还会把"静音"当成"没听清"。</p>
     *
     * @return 识别文本；没听清返回 ""（与 {@code VoiceDialog.listenBlocking} 契约一致）
     */
    public String listenSentence(long timeoutMs) {
        // ★ v159：上界 12000 → 24000（配合 VoiceDialog.maxListenMs 10s→20s）。
        //   这里原本 clamp 到 12 秒，是"最长听 20 秒"生效不了的**第二道闸**：
        //   只改 VoiceDialog 那一处，在线听写照样在第 12 秒被掐掉。
        //   （本项目的教训："修在一个地方 ≠ 修好了"）
        int maxMs = (int) Math.max(2000, Math.min(24000, timeoutMs - 1200));
        if (onlineAsr && !offlineAsr) {
            // ★ 开麦之前先等"机器人自己闭嘴"（v106）。
            //   理由：机器人喇叭就在麦克风旁边，它还没说完我们就开麦，采到的是**它自己**，
            //   而且比用户响得多 —— 实测包络第一秒 14152（用户正常说话约 6000~20000，
            //   但同房间混着机器人残留播报时，起音会被它触发，用户话的头尾就被掩掉了）。
            //   `speakWithBargeIn` 那边本来就有这个判断，只是**对话的"听"这一侧漏了** ——
            //   "修在一个地方 ≠ 修好了"。这里最多等 1.2s，等不到也照听（宁可带上串扰，
            //   也不能因为判据卡住而整轮不听）。
            long waitUntil = System.currentTimeMillis() + 1200;
            while (isSpeaking() && System.currentTimeMillis() < waitUntil) {
                try { Thread.sleep(80); } catch (InterruptedException ignored) { break; }
            }
            // ★★ v220：光等 isSpeaking 不够 —— 它转 false 之后喇叭余响仍在（实测持续
            //   ~2.6s、rms≈20600，与真人语音同量级）⇒ 直接开麦只会采到它自己，
            //   真机表现为 `第 N 轮 第 1/3 次听：[] 路线=cloud-quiet`（前两次全废）。
            //   这里再等"房间真的安静"：安静时约 0.4s 返回，一直吵就超时照听（不许卡住整轮）。
            waitQuiet(2000);
            long t0 = System.currentTimeMillis();
            CloudAsr.MicResult mr = cloudListen(maxMs, CloudAsr.MIC_SILENCE_MS, 0);
            lastMicResult = mr;             // 仪表上挂 —— /status.listen.last 就是它
            lastInputMs = mr.ms;
            String t = mr.textTrim();
            // ★ 拿到文本就先认 —— 哪怕 errCode 非 0。
            //   麦克风模式下引擎判到句尾后收尾，常会再回一个"只有句号"的空终态，
            //   于是 errCode 可能不是 0，但 best_partial 里躺着逐字正确的结果（v100 现场）。
            //   有字还去回落离线，等于把已经听懂的东西扔掉。
            if (!t.isEmpty()) {
                lastInputRoute = "cloud";
                lastInputNote = mr.note;
                BootLog.i("在线听写 ★听懂=[" + t + "] route=" + lastInputRoute
                        + " mic=" + mr.micOpened + "(重试" + mr.micRetries + ")"
                        + " frames=" + mr.frames + "/chunks=" + mr.chunks
                        + " rms_max=" + mr.rmsMax + "(阈值" + mr.thr + ") eos=" + mr.sawEos
                        + " err=" + mr.errCode + " 开口@" + mr.loudAtMs + "ms 耗时="
                        + (System.currentTimeMillis() - t0) + "ms");
                return t;
            }
            // 一个词都没有。这里要分清两件完全不同的事：**人没说话** vs **这条通路坏了**。
            //   ★ 关键陷阱：两者的 err_code **都可能是 0** —— 麦克风压根没开起来时，
            //     服务端从来没回过话，err_code 就停在初值 0，而麦克风被占住（micStarved）
            //     也一路没有 onError。按 "err_code==0 或 10118 = 正常静音" 去分类，
            //     就会把**硬故障报成"用户没说话"**（v121 现场：机器人对着一屋子
            //     正在说话的人说「你怎么不说话呀」）。
            //   ⇒ v121 起"通路坏没坏"的判定**下沉到 CloudAsr**（唯一出口）：硬故障 /
            //     音频没送达一律把 route 改成 "cloud-fail" 并填 failKind。这里只认
            //     route + failKind，不再自己拿错误码猜。
            //   10118 = 您好像没有说话哦；10114 之类也常见于"没听到"。
            boolean quiet = mr.errCode == 0 || mr.errCode == 10118;
            boolean broken = !"cloud".equals(mr.route) || !quiet
                    || (mr.failKind != null && !mr.failKind.isEmpty());
            if (!broken) {
                // ★ v121：「本地判到人声、服务端说没说话」这一档**单独标出来**。
                //   它既不回落到离线（那会白等十几秒，而且我们并不能断定是故障），
                //   也不装作"用户没说话"（那样真相就被咽掉了）。
                if (mr.quietSuspect) {
                    lastInputRoute = "cloud-quiet";
                    lastInputNote = "服务端说没说话，但本地判到人声（rms_max=" + mr.rmsMax
                            + " 阈值=" + mr.thr + " chunks=" + mr.chunks
                            + " saw_bos=" + mr.sawBos + " vol_max=" + mr.volMax
                            + "）—— 待裁：可能真没说，也可能音频没送达";
                    BootLog.w("在线听写 " + lastInputNote);
                    // 落账：这一档最容易"查不出来"，必须能在双端日志里按时间戳对上。
                    try {
                        TalkLog.add("voice-in", "", "", "", "cloud", "",
                                mr.ms, "cloud待定 route=cloud-quiet err=" + mr.errCode
                                        + " " + mr.errDesc
                                        + " saw_speech=" + mr.sawSpeech + " saw_bos=" + mr.sawBos
                                        + " vol_max=" + mr.volMax + " chunks=" + mr.chunks
                                        + " rms_max=" + mr.rmsMax + " 包络=" + mr.rmsTrace);
                    } catch (Throwable ignored) {}
                    return "";
                }
                lastInputRoute = "cloud-empty";
                lastInputNote = mr.note;
                BootLog.i("在线听写 没听到内容（正常静音）rms_max=" + mr.rmsMax
                        + " 阈值=" + mr.thr + " saw_speech=" + mr.sawSpeech
                        + " saw_bos=" + mr.sawBos + " 包络=" + mr.rmsTrace
                        + " 耗时=" + (System.currentTimeMillis() - t0) + "ms");
                return "";
            }
            // 在线真的不通 ⇒ 回落离线（断网时那 45 条命令词还能用）
            lastInputRoute = "offline";
            lastInputNote = "在线不通(" + mr.route
                    + (mr.failKind == null || mr.failKind.isEmpty() ? "" : " fail=" + mr.failKind)
                    + " err=" + mr.errCode + " " + mr.errDesc + ") → 回落离线";
            BootLog.w("在线听写 " + lastInputNote);
            // ★ 错误码 + **故障种类**都要落进长期日志：原厂 appid 的配额是个未知数，
            //   而"配额耗尽""音频没送达"这类故障只在错误码/仪表里出现 ——
            //   不落账就等于没发生过，也就永远查不出"机器人为什么说你怎么不说话呀"。
            try {
                TalkLog.add("voice-in", "", "", "", "cloud", "",
                        mr.ms, "cloud失败 route=" + mr.route + " fail_kind=" + mr.failKind
                                + " err=" + mr.errCode
                                + " " + mr.errDesc + " mic=" + mr.micOpened
                                + " saw_speech=" + mr.sawSpeech + " saw_bos=" + mr.sawBos
                                + " vol_max=" + mr.volMax
                                + " rms_max=" + mr.rmsMax + " 包络=" + mr.rmsTrace
                                + " " + mr.note);
            } catch (Throwable ignored) {}
        } else {
            lastInputRoute = "offline";
            lastInputNote = offlineAsr
                    ? "离线模式（连续听不懂 → 已停止对语音的输入）"
                    : "在线已关";
            // ★★★ v142：离线模式下**连离线 ASR 都不跑**，直接返回没听到。
            //   用户原话：「3-5 次尝试接受语音后停止对语音的输入，机器人进入离线模式」。
            //   "停止对语音的输入"= 不再开麦。本机唯一还在听的是唤醒引擎（KWS，零成本），
            //   它就是"喊名字回来"的那条通路 —— 由 dispatchWake 负责把模式切回来。
            if (offlineAsr) return "";
        }
        return dialog().listenBlocking(timeoutMs);
    }

    /** 供 listenOnce 用：临时禁止 asrTest 在收尾时自动恢复唤醒引擎。 */
    private volatile boolean asrNoResume;

    /**
     * 只听一轮、把识别文本返回（给对话用），<b>不自动恢复唤醒监听</b>。
     *
     * <p>与 {@link #asrTest} 的区别就在「不恢复」：调用方（AgentService.onWakeup）接着要播报，
     * 若此时唤醒引擎已经重新开麦，它会听见自己的喇叭而自我唤醒/自激。
     * 所以这里刻意把恢复动作留给调用方在播完后自己调 {@link #resume()}。</p>
     *
     * @return 识别文本；没听清 / 失败一律返回空串
     */
    public synchronized String listenOnce(int seconds) {
        asrNoResume = true;
        try {
            asrTest(seconds, null, null);
            return asrProbe == null ? "" : asrProbe.lastText();
        } catch (Throwable t) {
            BootLog.w("语音唤醒 听一轮失败: " + chain(t));
            return "";
        } finally {
            asrNoResume = false;
        }
    }

    /**
     * 麦克风自检：本进程自己开一次 AudioRecord，采 1.5 秒算峰值/均方根。
     *
     * 为什么必要：引擎能进 wp.enter 说明词表和模型都加载成功了；那"喊了没反应"只剩
     * (a) 喂进去的是静音（麦克风被原厂主进程占住，或链路根本没出声）或
     * (b) 喊的词不在词表里 两种可能。这一段直接把 (a) 证死或证实。
     *
     * 顺序要求：必须在 wp.start <b>之前</b>跑完并 release，否则会和引擎自己的
     * EventManagerMic 抢麦（同 ROM 单麦仲裁下容易两败俱伤）。
     */
    private void probeMic() {
        android.media.AudioRecord rec = null;
        try {
            int rate = 16000;
            int min = android.media.AudioRecord.getMinBufferSize(rate,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(min, rate);   // 至少 0.5s
            rec = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC,
                    rate, android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (rec.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                micInfo = "AudioRecord 初始化失败 state=" + rec.getState() + " minBuf=" + min;
                BootLog.w("语音唤醒 麦克风自检: " + micInfo);
                return;
            }
            rec.startRecording();
            short[] chunk = new short[rate / 10];   // 100ms
            long t0 = System.currentTimeMillis();
            int maxAbs = 0, frames = 0, zeros = 0;
            long cnt = 0;
            double sumsq = 0;
            while (System.currentTimeMillis() - t0 < 1500) {
                int got = rec.read(chunk, 0, chunk.length);
                if (got <= 0) continue;
                frames++;
                for (int i = 0; i < got; i++) {
                    int v = chunk[i];
                    if (v == 0) zeros++;
                    int a = v < 0 ? -v : v;
                    if (a > maxAbs) maxAbs = a;
                    sumsq += (double) v * (double) v;
                    cnt++;
                }
            }
            double rms = cnt > 0 ? Math.sqrt(sumsq / cnt) : 0;
            int pctZero = cnt > 0 ? (int) (100L * zeros / cnt) : 100;
            micInfo = "frames=" + frames + " samples=" + cnt + " maxAbs=" + maxAbs
                    + " rms=" + (int) rms + " 静音占比=" + pctZero + "%";
            // maxAbs 长期为 0 / 静音占比接近 100% => 我们拿到的是静音（麦克风被占用）
            BootLog.w("语音唤醒 ★麦克风自检 1.5s: " + micInfo
                    + (maxAbs == 0 ? "  ⚠疑似静音/被占用" : "  有信号"));
        } catch (Throwable t) {
            micInfo = "异常: " + chain(t);
            BootLog.w("语音唤醒 麦克风自检失败: " + micInfo);
        } finally {
            try {
                if (rec != null) {
                    if (rec.getRecordingState() == android.media.AudioRecord.RECORDSTATE_RECORDING) rec.stop();
                    rec.release();
                }
            } catch (Throwable ignore) {}
        }
    }

    // ---------------- v183：自建唤醒的数据采集器 ----------------

    /** 采样落盘目录。正样本、负样本、回灌样本都放这里，PC 侧统一 adb pull。 */
    public static final String KWS_DIR = "/sdcard/ledi_kws";

    /**
     * 录一段原始麦克风 PCM 到 {@code /sdcard/ledi_kws/<name>.pcm}，并落同名 {@code .json} 统计。
     *
     * <h3>为什么必须有它：不然「自建唤醒」就是无米之炊</h3>
     * 原厂那套百度离线引擎（KWS + 离线 ASR）在本机**整族失效**：
     * KWS 4 次真人实测 + 8 档增益回灌全 0 命中，
     * v183 这轮又实测**离线 ASR 也 `events=0`**（连 `vol_max` 都是 0 ⇒ 引擎压根没吃进音频）。
     * 结论：唤醒只能自建 —— 自己开麦、自己算特征、自己比对。
     * 自建算法的第一块砖就是**拿到带标签的真实音频**，而这个 ROM 没有原厂录音 App 可用
     * （裸机），所以采集器必须由我们自己提供。
     *
     * <h3>为什么要顺手落 .json</h3>
     * 铁律「判据必须在进程之外」：光有 pcm 不够，还要有**当时的下发值**。
     * 峰值/均方根/削顶比例/静音比例/时长都落盘，PC 侧 `adb pull` 后与 PC 重新算出来的
     * 对差 —— 两边算不一致就是有一边错了，这比"我觉得采到了"强。
     *
     * <h3>让麦的时序</h3>
     * 与 {@link #offlineTest} 同构：先 {@code sendStop()} 把引擎的麦让出来，
     * 等 300ms 让它真的释放，采完再 {@code sendStart()} 还回去。
     * 这个 ROM 单麦仲裁下"不先让麦就开 AudioRecord"抓到过"打开成功但 read() 恒 &le;0"。
     *
     * @param seconds 录音秒数（钳到 1..25；HTTP 读超时 30s，留收尾余量）
     * @param name    文件名（只留 [A-Za-z0-9_-]；空则用时间戳）
     */
    public synchronized String recordRaw(int seconds, String name) {
        return recordRaw(seconds, name, -1);
    }

    /**
     * 全参版（v185 起加 <b>可选音频源</b>）。
     *
     * <p><b>为什么必须能选音频源</b>：本机 `MIC` 这条 android 音频源在这台 ROM 上
     * 带着一整套**语音处理**（AEC 回声消除 / NS 降噪 / AGC 自动增益）。2026-10-06 实测
     * 三条互相印证的证据：</p>
     * <ul>
     *   <li>自回环（机器人喇叭→自己的麦）**任何音量都是平的** —— 包括 vol=100 ——
     *       因为 AEC 的参考信号就是它自己的播报，被**完全抵消**；</li>
     *   <li>外灌增益曲线**非单调**：播放幅度 300→录到 27996、600→10226、4800→32768。
     *       线性声学链路不可能这样 ⇒ 是 AGC 在自适应；</li>
     *   <li>于是"用峰值当判据"全部失效（本项目已因此连判错两轮）。</li>
     * </ul>
     * <p>⇒ 想拿到**线性、可复现**的录音（也是自建唤醒做模板匹配所必需的），
     * 就得走不带语音处理的源：{@code UNPROCESSED}(1998) 或 {@code VOICE_RECOGNITION}(6)。</p>
     *
     * @param seconds     录音秒数（钳到 1..25；HTTP 读超时 30s，留收尾余量）
     * @param name        文件名（只留 [A-Za-z0-9_-]；空则用时间戳）
     * @param audioSource {@code MediaRecorder.AudioSource} 常量；**<=0 表示沿用 MIC(=1)**。
     *                    常用：1=MIC、6=VOICE_RECOGNITION、7=VOICE_COMMUNICATION、
     *                    5=CAMCORDER、1998=UNPROCESSED、0=DEFAULT。
     *                    （1998 = 0x7CE = AudioSource.UNPROCESSED，老 SDK 没有这个常量，
     *                     故这里直接用字面量。）
     */
    public synchronized String recordRaw(int seconds, String name, int audioSource) {
        if (seconds <= 0 || seconds > 25) seconds = 6;
        String safe = name == null ? "" : name.replaceAll("[^A-Za-z0-9_-]", "");
        if (safe.isEmpty()) {
            safe = "rec_" + new java.text.SimpleDateFormat("MMdd_HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
        }
        boolean wasRunning = running;
        android.media.AudioRecord rec = null;
        java.io.FileOutputStream out = null;
        int frames = 0, samples = 0, maxAbs = 0, clip = 0, zeros = 0;
        double sumsq = 0;
        String err = "";
        final int useSrc = audioSource > 0 ? audioSource : android.media.MediaRecorder.AudioSource.MIC;
        File dir = new File(KWS_DIR);
        if (!dir.exists()) dir.mkdirs();
        File dst = new File(dir, safe + ".pcm");
        BootLog.w("语音唤醒 ★采集开始 " + seconds + "s src=" + useSrc
                + " -> " + dst.getAbsolutePath());
        try {
            sendStop();
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}

            int rate = 16000;
            int min = android.media.AudioRecord.getMinBufferSize(rate,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(min, rate * 2);      // 至少 1s
            rec = new android.media.AudioRecord(useSrc,
                    rate, android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (rec.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                return "{\"ok\":false,\"error\":\"AudioRecord 初始化失败 state="
                        + rec.getState() + "\"}";
            }
            rec.startRecording();
            out = new java.io.FileOutputStream(dst);
            short[] chunk = new short[rate / 10];        // 100ms
            byte[] bytes = new byte[chunk.length * 2];
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < seconds * 1000L) {
                int got = rec.read(chunk, 0, chunk.length);
                if (got <= 0) continue;
                frames++;
                for (int i = 0; i < got; i++) {
                    int v = chunk[i];
                    if (v == 0) zeros++;
                    int a = v < 0 ? -v : v;
                    if (a > maxAbs) maxAbs = a;
                    if (a >= 32000) clip++;
                    sumsq += (double) v * (double) v;
                    bytes[i * 2] = (byte) (v & 0xff);
                    bytes[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
                }
                samples += got;
                out.write(bytes, 0, got * 2);
                out.flush();
            }
        } catch (Throwable t) {
            err = chain(t);
            BootLog.w("语音唤醒 采集异常: " + err);
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignore) {}
            try {
                if (rec != null) {
                    if (rec.getRecordingState() == android.media.AudioRecord.RECORDSTATE_RECORDING) rec.stop();
                    rec.release();
                }
            } catch (Throwable ignore) {}
            if (wasRunning) sendStart();
        }
        double rms = samples > 0 ? Math.sqrt(sumsq / samples) : 0;
        int clipPct = samples > 0 ? (int) (10000L * clip / samples) : 0;   // 万分比
        int zeroPct = samples > 0 ? (int) (100L * zeros / samples) : 100;
        long len = dst.exists() ? dst.length() : 0;
        BootLog.w("语音唤醒 ★采集结果 " + safe + " frames=" + frames + " samples=" + samples
                + " bytes=" + len + " maxAbs=" + maxAbs + " rms=" + (int) rms
                + " clip=" + clipPct + "/10000 静音=" + zeroPct + "%");
        String js = "{\"ok\":" + (err.isEmpty() ? "true" : "false")
                + ",\"name\":\"" + J.esc(safe) + "\""
                + ",\"file\":\"" + J.esc(dst.getAbsolutePath()) + "\""
                + ",\"sec\":" + seconds
                + ",\"src\":" + useSrc
                + ",\"bytes\":" + len
                + ",\"frames\":" + frames
                + ",\"samples\":" + samples
                + ",\"maxAbs\":" + maxAbs
                + ",\"rms\":" + (int) rms
                + ",\"clip_permille\":" + (clipPct / 10)
                + ",\"silence_pct\":" + zeroPct
                + ",\"at\":" + (System.currentTimeMillis() / 1000L)
                + ",\"error\":\"" + J.esc(err) + "\"}";
        try {
            java.io.FileOutputStream jo = new java.io.FileOutputStream(
                    new File(dir, safe + ".json"));
            jo.write(js.getBytes("UTF-8"));
            jo.close();
        } catch (Throwable ignore) {}
        return js;
    }

    /** 列出采集目录（文件名 + 字节大小），供 PC 侧核对传输完整性。 */
    public static String kwsDirList() {
        StringBuilder sb = new StringBuilder("[");
        try {
            File[] fs = new File(KWS_DIR).listFiles();
            if (fs != null) {
                java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
                    @Override public int compare(File a, File b) {
                        return Long.valueOf(a.lastModified()).compareTo(Long.valueOf(b.lastModified()));
                    }
                });
                boolean first = true;
                for (File f : fs) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"n\":\"").append(J.esc(f.getName()))
                            .append("\",\"b\":").append(f.length()).append('}');
                }
            }
        } catch (Throwable ignore) {}
        return sb.append(']').toString();
    }

    // ---------------- native 直控实验（v46） ----------------

    private static final String JNI_CLS = "com.baidu.speech.easr.EasrNativeJniInterface";

    /** 取 AuthEasrHelper 里的 easrNativeJni 实例（私有静态字段） */
    private Object nativeJni() {
        try {
            Class<?> c = Class.forName(AUTH_CLS);
            java.lang.reflect.Field f = c.getDeclaredField("easrNativeJni");
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            BootLog.w("语音唤醒 取 easrNativeJni 失败: " + chain(t));
            return null;
        }
    }

    /** 通过 EasrNativeJniInterface 调 native 方法 */
    private static Object callJni(Object jni, String name, Class<?>[] types, Object... args) {
        try {
            Class<?> iface = Class.forName(JNI_CLS);
            Method m = iface.getMethod(name, types);
            m.setAccessible(true);
            return m.invoke(jni, args);
        } catch (Throwable t) {
            return "ERR:" + chain(t);
        }
    }

    /** 打印 VERIFY_* 常量（把 VerifyLicense 的 -1/-2 映射成语义） */
    private void dumpVerifyConsts() {
        try {
            Class<?> iface = Class.forName(JNI_CLS);
            String[] names = {"VERIFY_OK", "VERIFY_LICENSE_EXPIRED", "VERIFY_LICENSE_FAIL_1",
                    "VERIFY_LICENSE_FAIL_7", "VERIFY_LICENSE_FAIL_CUID",
                    "VERIFY_LICENSE_FAIL_PACKAGE_NAME", "VERIFY_LICENSE_FAIL_SIGNATURE",
                    "VERIFY_LICENSE_WILL_EXPIRED", "VERIFY_TEST_LICENSE_EXPIRED",
                    "VERIFY_TEST_LICENSE_OK_PREFIX"};
            StringBuilder sb = new StringBuilder();
            for (String nm : names) {
                try {
                    java.lang.reflect.Field f = iface.getField(nm);
                    sb.append(nm).append('=').append(f.getInt(null)).append("  ");
                } catch (Throwable ignore) {}
            }
            BootLog.w("语音唤醒 VERIFY常量: " + sb);
        } catch (Throwable t) {
            BootLog.w("语音唤醒 dump VERIFY 常量失败: " + chain(t));
        }
    }

    /** 打印 native 侧授权/日志相关信息（只读，不改变状态） */
    private void probeNativeInfo() {
        Object jni = nativeJni();
        if (jni == null) return;
        dumpVerifyConsts();
        BootLog.w("语音唤醒 SetLogLevel(5)=" + callJni(jni, "SetLogLevel", new Class<?>[]{int.class}, 5));
        BootLog.w("语音唤醒 GetTestAuthorize=" + callJni(jni, "GetTestAuthorize", new Class<?>[]{}));
    }

    /**
     * ★解药★ 强制置位 native 的 license flag。
     *
     * 为什么有效（实测验证）：
     *   - license 绑定 packageName（VerifyLicense 返回 -2 = FAIL_PACKAGE_NAME）且已过期
     *     （GetTestAuthorize 返回 -5 = EXPIRED），换证的百度服务端 POST /auth.php 也已不可用
     *     （GetLicense 返回 -101/-1，阻塞 8 秒后失败）——正常授权路径无解。
     *   - 但 EngineRegistry 的 smartAuth 开头就是 `if (GetLicenseFlag()) return;`，
     *     而 WakeUpInitial 也只看这个 flag。置位后 smartAuth 直接短路（不再发起网络请求），
     *     WakeUpInitial 返回 0，引擎正常进入 wp.enter 监听态。
     *
     * 幂等；每次 start 前都调一次，防止 wp.stop 之后标志被清掉。
     */
    private void forceLicenseFlag() {
        Object jni = nativeJni();
        if (jni == null) return;
        try {
            Object before = callJni(jni, "GetLicenseFlag", new Class<?>[]{});
            if (Boolean.TRUE.equals(before)) return;   // 已置位，免打扰
            Object setRet = callJni(jni, "SetLicenseFlag", new Class<?>[]{boolean.class}, true);
            Object after = callJni(jni, "GetLicenseFlag", new Class<?>[]{});
            BootLog.w("语音唤醒 ★强制置 license flag: before=" + before
                    + " set=" + setRet + " after=" + after);
        } catch (Throwable t) {
            BootLog.w("语音唤醒 置 license flag 失败: " + chain(t));
        }
    }

    // ---------------- 离线唤醒体检（v50） ----------------

    /**
     * 把 PCM 直接喂给唤醒解码器，<b>完全绕开喇叭 / AEC / 实时链路</b>。
     *
     * <p>依据 {@code EventManagerWp.doMicData} 的反汇编（铁证）：
     * <pre>
     *   int ret = WakeUpDecodeByte(data, data.length, sentenceT, 1, false);
     *   mWakeUpDecodeByteCount += len;
     *   if (ret == 1) {                                   // ★ 返回 1 = 命中
     *       String s = new String(sentenceT[0]);          // 解出的文本
     *       for (String w : mUsingWords)                  // 词表
     *           if (s.contains(w)) { publishEvent("wp.data", {word:w,"time-offset":cnt/32}); break; }
     *   }
     * </pre>
     * {@code sentenceT = new byte[1][1000]}，是解码文本的输出缓冲；{@code cnt/32} 换算成毫秒
     * （3200B/帧 = 16k×16bit×100ms，3200/32=100）。
     *
     * <p>流程：停麦 → 自己 {@code WakeUpInitial} → 边录边按 3200B 帧喂解码器 →
     * 记录每帧返回码与解出文本 → 重开实时引擎。
     */
    public synchronized String offlineTest(int seconds) {
        return offlineTest(seconds, "", 1.0f);
    }

    /**
     * 离线唤醒体检（v181 起可选**回灌文件**）。
     *
     * <p>为什么加回灌：真人现场一轮要人守着 20 秒，而且"距离/音量"每次都在变
     * （2026-09-27 实测：20~30cm 必削顶 32768；1 米外只剩 3255 ≈ 手册里的
     * "静默"档 4642）。改成"录一段干净 PCM → 在设备上按不同增益反复喂**同一个**
     * 解码器"之后，声学变量被锁死，只剩「增益」一个自变量 ——
     * 这是能确定性回答"它到底还能不能命中"的做法。</p>
     *
     * @param pcmPath 设备上的裸 PCM（16k/16bit/mono）；空 / "null" = 照旧自己开麦
     * @param gain    回灌时的数字增益（1.0 = 原样；带 ±32767 削顶保护）
     */
    public synchronized String offlineTest(int seconds, String pcmPath, float gain) {
        final boolean fileMode = pcmPath != null && !pcmPath.trim().isEmpty()
                && !"null".equalsIgnoreCase(pcmPath.trim());
        if (seconds <= 0 || seconds > 20) seconds = 6;
        if (gain <= 0f || gain > 64f) gain = 1.0f;
        boolean wasRunning = running;
        int frames = 0, hits = 0, maxAbs = 0;
        StringBuilder texts = new StringBuilder();
        Object initRet = null;
        String err = "";
        android.media.AudioRecord rec = null;
        java.io.InputStream fin = null;
        Object jni = nativeJni();
        BootLog.w("语音唤醒 ★离线体检开始 " + (fileMode
                ? ("回灌 " + pcmPath.trim() + " gain=" + gain)
                : (seconds + "s（请在这段时间内喊「哈喽乐迪」）")));
        try {
            if (jni == null) return "{\"error\":\"native 接口不可用\"}";
            sendStop();                                  // 先把麦让出来
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}

            forceLicenseFlag();
            callJni(jni, "SetSampleRateMode", new Class<?>[]{int.class}, 3);
            callJni(jni, "WakeUpFree", new Class<?>[]{});
            String resFile = ctx.getApplicationInfo().nativeLibraryDir
                    + "/libbd_easr_s1_merge_normal_20151216.dat.so";
            String wordsTab = rawWakeWords.trim().replace(',', '\t');   // 与 doWpStart 同构：按 \t 喂
            initRet = callJni(jni, "WakeUpInitial",
                    new Class<?>[]{String.class, String.class, int.class}, wordsTab, resFile, 3);
            BootLog.w("语音唤醒 离线体检 WakeUpInitial=" + initRet + " res=" + resFile
                    + " words=" + wordsTab);

            if (fileMode) {
                try {
                    fin = new java.io.FileInputStream(pcmPath.trim());
                } catch (Throwable t) {
                    return "{\"error\":\"打不开 pcm " + J.esc(pcmPath.trim()) + ": "
                            + J.esc(chain(t)) + "\"}";
                }
            } else {
                rec = new android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, 16000,
                        android.media.AudioFormat.CHANNEL_IN_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT, 16000 * 2);
                if (rec.getState() != android.media.AudioRecord.STATE_INITIALIZED) {
                    err = "AudioRecord 初始化失败 state=" + rec.getState();
                    BootLog.w("语音唤醒 离线体检 " + err);
                    return "{\"error\":\"" + J.esc(err) + "\"}";
                }
                rec.startRecording();
            }

            Class<?> iface = Class.forName(JNI_CLS);
            Method decode = iface.getMethod("WakeUpDecodeByte",
                    byte[].class, int.class, byte[][].class, int.class, boolean.class);
            decode.setAccessible(true);

            final int WANT = 3200;                       // 100ms @16k/16bit，与引擎帧一致
            byte[] frame = new byte[WANT];
            byte[] fileBuf = new byte[WANT];
            int fileOff = 0;
            byte[][] sentenceT = new byte[1][1000];
            long endAt = System.currentTimeMillis() + (fileMode ? 120000L : seconds * 1000L);
            while (System.currentTimeMillis() < endAt) {
                int got;
                if (fileMode) {
                    while (fileOff < WANT) {
                        int r = fin.read(fileBuf, fileOff, WANT - fileOff);
                        if (r <= 0) break;
                        fileOff += r;
                    }
                    got = fileOff;
                    fileOff = 0;
                    if (got <= 0) break;                 // 文件读完
                    System.arraycopy(fileBuf, 0, frame, 0, got);
                } else {
                    got = readFully(rec, frame, WANT);
                    if (got <= 0) continue;
                }
                frames++;
                byte[] buf = (got == WANT) ? frame : java.util.Arrays.copyOf(frame, got);
                if (fileMode && gain != 1.0f) buf = applyGain(buf, gain);
                for (int i = 0; i + 1 < buf.length; i += 2) {
                    int v = (short) ((buf[i] & 0xff) | (buf[i + 1] << 8));
                    int a = v < 0 ? -v : v;
                    if (a > maxAbs) maxAbs = a;
                }
                int ret = ((Integer) decode.invoke(jni, buf, buf.length, sentenceT, 1, false)).intValue();
                if (ret == 1) {
                    hits++;
                    texts.append('[').append(cstr(sentenceT[0])).append(']');
                }
            }
            BootLog.w("语音唤醒 ★离线体检结果 frames=" + frames + " hits=" + hits
                    + " maxAbs=" + maxAbs + " 解出=" + texts);
        } catch (Throwable t) {
            err = chain(t);
            BootLog.w("语音唤醒 离线体检异常: " + err);
        } finally {
            try { if (fin != null) fin.close(); } catch (Throwable ignore) {}
            try {
                if (rec != null) {
                    if (rec.getRecordingState() == android.media.AudioRecord.RECORDSTATE_RECORDING) rec.stop();
                    rec.release();
                }
            } catch (Throwable ignore) {}
            if (jni != null) callJni(jni, "WakeUpFree", new Class<?>[]{});   // 让实时引擎重新干净初始化
            if (wasRunning) sendStart();
        }
        return "{\"sec\":" + seconds + ",\"frames\":" + frames + ",\"hits\":" + hits
                + ",\"maxAbs\":" + maxAbs
                + ",\"init\":" + (initRet == null ? "null" : initRet)
                + ",\"texts\":\"" + J.esc(texts.toString())
                + "\",\"words\":\"" + J.esc(wakeWords)
                + "\",\"pcm\":\"" + J.esc(fileMode ? pcmPath.trim() : "")
                + "\",\"gain\":" + gain
                + ",\"error\":\"" + J.esc(err) + "\"}";
    }

    /** 数字增益 + 削顶保护（v181 回灌用）。 */
    private static byte[] applyGain(byte[] in, float g) {
        byte[] out = new byte[in.length];
        for (int i = 0; i + 1 < in.length; i += 2) {
            int v = (short) ((in[i] & 0xff) | (in[i + 1] << 8));
            int a = Math.round(v * g);
            if (a > 32767) a = 32767;
            if (a < -32768) a = -32768;
            out[i] = (byte) (a & 0xff);
            out[i + 1] = (byte) ((a >> 8) & 0xff);
        }
        return out;
    }

    /** 读满 want 字节（AudioRecord.read 会短读）；连续失败多次则放弃 */
    private static int readFully(android.media.AudioRecord rec, byte[] buf, int want) {
        int off = 0, fails = 0;
        while (off < want) {
            int n = rec.read(buf, off, want - off);
            if (n <= 0) {
                if (++fails > 30) break;
                try { Thread.sleep(5); } catch (InterruptedException ignore) {}
                continue;
            }
            fails = 0;
            off += n;
        }
        return off;
    }

    /** native 写进 sentenceT[0] 的是 C 串（尾部补 0），此处截到第一个 0 并去空白 */
    private static String cstr(byte[] b) {
        if (b == null) return "";
        int n = 0;
        while (n < b.length && b[n] != 0) n++;
        try {
            return new String(b, 0, n, "UTF-8").trim();
        } catch (Throwable t) {
            return new String(b, 0, n).trim();
        }
    }

    // ---------------- license ----------------

    /** 确保目标 Context 的 files 目录里有 license（auth() 固定读 getFileStreamPath）。
     *
     *  v52：改为「先用 APK 内嵌副本」——交付包不再依赖原厂包私目录，换机器、
     *  原厂被清数据都能起来。内嵌那份就是原厂 license 的原件（512B，md5 debfd6c5…）。 */
    private boolean ensureLicense(Context c) {
        try {
            File dst = new File(c.getFilesDir(), LIC_NAME);
            if (dst.exists() && dst.length() > 0) {
                BootLog.i("语音唤醒 license 已在 " + c.getPackageName() + " 目录: "
                        + dst.getAbsolutePath() + " len=" + dst.length());
                return true;
            }
            // 1) 首选：APK 内嵌资源（自给自足，不依赖原厂包）
            try {
                InputStream in = ctx.getAssets().open(LIC_NAME);
                FileOutputStream out = new FileOutputStream(dst);
                byte[] b = new byte[4096];
                int m;
                while ((m = in.read(b)) > 0) out.write(b, 0, m);
                out.close();
                in.close();
                if (dst.length() > 0) {
                    BootLog.i("语音唤醒 license 已从内嵌资源释放: "
                            + dst.getAbsolutePath() + " len=" + dst.length());
                    return true;
                }
            } catch (Throwable t) {
                BootLog.w("语音唤醒 内嵌 license 释放失败: " + chain(t));
            }
            // 2) 兜底：原厂包私目录
            File src = new File("/data/data/" + ORIG_PKG + "/files/" + LIC_NAME);
            if (!src.exists() || src.length() == 0) {
                lastError = "原厂 license 不存在: " + src.getAbsolutePath();
                BootLog.w("语音唤醒 " + lastError);
                return false;
            }
            FileInputStream in = new FileInputStream(src);
            FileOutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            BootLog.i("语音唤醒 license 已复制: " + src.getAbsolutePath()
                    + " -> " + dst.getAbsolutePath() + " len=" + dst.length());
            return true;
        } catch (Throwable t) {
            lastError = "准备 license 失败: " + chain(t);
            BootLog.w("语音唤醒 " + lastError);
            return false;
        }
    }

    // ---------------- 事件环形缓冲 ----------------

    private void push(String s) {
        synchronized (ring) {
            ring.addLast(s);
            while (ring.size() > 10) ring.removeFirst();
        }
    }

    private String ringJson() {
        StringBuilder sb = new StringBuilder("[");
        synchronized (ring) {
            boolean first = true;
            for (String s : ring) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(J.esc(s)).append('"');
            }
        }
        return sb.append(']').toString();
    }

    // ---------------- EventListener 代理 ----------------

    private Object makeEventListener() {
        try {
            elCls = Class.forName(EL_CLS);
            return Proxy.newProxyInstance(elCls.getClassLoader(), new Class<?>[]{elCls},
                    new InvocationHandler() {
                        @Override public Object invoke(Object p, Method m, Object[] a) {
                            // ★ 统一走 Proxies：Object 的三个约定方法必须真实现，
                            //   末尾也不许裸 return null（primitive 返回类型会当场 NPE）。
                            //   见 Proxies 类注释 —— 这套规矩曾经散落在各处，
                            //   v76 的 AsrProbe 就是因为没抄到而整个 /asrtest 全废。
                            Object g = Proxies.guard(p, m, a);
                            if (g != Proxies.UNHANDLED) return g;
                            String n = m.getName();
                            if ("onEvent".equals(n)) {
                                String name = (a != null && a.length > 0 && a[0] != null)
                                        ? String.valueOf(a[0]) : "";
                                String params = (a != null && a.length > 1 && a[1] != null)
                                        ? String.valueOf(a[1]) : "";
                                byte[] data = (a != null && a.length > 2 && a[2] instanceof byte[])
                                        ? (byte[]) a[2] : null;
                                int off = (a != null && a.length > 3 && a[3] instanceof Integer)
                                        ? (Integer) a[3] : 0;
                                int len = (a != null && a.length > 4 && a[4] instanceof Integer)
                                        ? (Integer) a[4] : 0;
                                try {
                                    handleEvent(name, params, data, off, len);
                                } catch (Throwable t) {
                                    BootLog.w("语音唤醒 处理事件异常: " + t);
                                }
                            }
                            return Proxies.nil(m);
                        }
                    });
        } catch (Throwable t) {
            lastError = "构造 EventListener 失败: " + chain(t);
            BootLog.w("语音唤醒 " + lastError);
            return null;
        }
    }

    private void handleEvent(String name, String params, byte[] data, int offset, int length) {
        lastEvent = name + " " + params;
        lastEventMs = System.currentTimeMillis();
        push(name + " " + params);

        if ("wp.data".equals(name)) {
            String word = "";
            try {
                word = new JSONObject(params).optString("word", "");
            } catch (Throwable ignored) {}
            if (word.isEmpty()) word = params;
            BootLog.i("语音唤醒 ★命中 wp.data word=" + word + " raw=" + params);
            handleWakeup(word);
            return;
        }

        if ("wp.exit".equals(name)) {
            String desc = params;
            try {
                desc = new JSONObject(params).optString("desc", params);
            } catch (Throwable ignored) {}
            lastExit = desc;
            listening = false;          // ★ 退出监听态：看门狗据此判断要不要救回来
            BootLog.w("语音唤醒 wp.exit desc=" + desc);
            if (desc != null && (desc.contains("Exception") || desc.contains("bad ")
                    || desc.contains("WakeUpInitial") || desc.contains("license"))) {
                lastError = "wp.exit: " + desc;
            }
            return;
        }

        if ("wp.enter".equals(name)) {
            BootLog.i("语音唤醒 wp.enter（引擎已进入监听态，授权通过）");
            listening = true;           // ★ 真的在听了
            lastError = "";
            return;
        }

        if (name != null && name.startsWith("wp.")) {
            BootLog.i("语音唤醒 事件 " + name + " params=" + params);
        }
    }

    // ---------------- 初始化 ----------------

    private boolean ensureInit() {
        if (inited && engine != null) return true;
        try {
            hookBaiduLogging();
            probeIdentity();
            probeCuid();
            decodeWakeWords();      // 该喊什么，由 WakeUp.bin 说了算（干喊没用）
            probeMic();             // 先证明麦克风不是静音（必须在 wp.start 前跑完并放麦）
            engineCtx = ctx;

            // ⚠ 授权探测绝不能和引擎的授权路径并发：实测并发时它会把 license flag 冲掉，
            //   使 doWpStart 里的 smartAuth 又去发 8s 的换证请求（v48 踩到：wp.start 卡 8.3s）。
            //   因此默认不跑；需要留证据时放哨兵文件 /sdcard/ledi_wake_probe，此时串行跑在
            //   forceLicenseFlag 之前，跑完再置位。
            if (new File("/sdcard/ledi_wake_probe").exists()) {
                int rcSelf = probeAuth(ctx, "本包");
                lastAuth = rcSelf + " (" + authName(rcSelf) + ")";
            }

            probeNativeInfo();      // 只读诊断（VERIFY 常量 / GetTestAuthorize / 日志级别）
            forceLicenseFlag();     // ★解药：必须先于 wp.start，否则 auth 失败会卡住 WakeUpInitial
            if (!ensureLicense(engineCtx)) {
                // v52：不再阻断。license flag 已强制置位（smartAuth 首行即短路返回），
                // 缺文件时也值得让引擎继续试；失败只留痕，由 wp.exit 的 desc 兜底暴露。
                BootLog.w("语音唤醒 license 未就绪，继续尝试启动引擎（flag 已置位）");
            }

            Class<?> emf = Class.forName(EMF_CLS);
            Method create = emf.getMethod("create", Context.class, String.class);
            engine = create.invoke(null, engineCtx, "wp");
            if (engine == null) {
                lastError = "EventManagerFactory.create(ctx,\"wp\") 返回 null（isLegitimacy 未通过？）";
                BootLog.w("语音唤醒 " + lastError);
                return false;
            }

            elProxy = makeEventListener();
            if (elProxy == null) return false;

            Method reg = findMethod(engine, "registerListener", elCls);
            if (reg == null) {
                lastError = "找不到 registerListener(EventListener)";
                BootLog.w("语音唤醒 " + lastError);
                return false;
            }
            reg.invoke(engine, elProxy);

            inited = true;
            lastError = "";
            BootLog.i("语音唤醒 EventManager 初始化成功 engine="
                    + engine.getClass().getName() + " ctx.pkg=" + engineCtx.getPackageName());
            return true;
        } catch (Throwable t) {
            inited = false;
            lastError = "EventManager 初始化失败: " + chain(t);
            BootLog.w("语音唤醒 " + lastError);
            return false;
        }
    }

    // ---------------- start / stop ----------------

    private void sendStart() {
        if (engine == null || engineCtx == null) return;
        try {
            forceLicenseFlag();   // 每次 start 前确保 flag 在位（wp.stop 后可能被清）
            File lic = new File(engineCtx.getFilesDir(), LIC_NAME);
            String kws = resolveKwsFile();    // ★ 外部词表优先（换词免重装）
            activeKws = kws;
            JSONObject p = new JSONObject();
            p.put("kws-file", kws);
            p.put("appid", APP_ID);
            p.put("license-file-path", lic.getAbsolutePath());
            String js = p.toString();

            BootLog.i("语音唤醒 发 wp.start params=" + js);
            Method send = findMethod(engine, "send",
                    String.class, String.class, byte[].class, int.class, int.class);
            if (send == null) {
                lastError = "找不到 send(String,String,byte[],int,int)";
                BootLog.w("语音唤醒 " + lastError);
                return;
            }
            send.invoke(engine, "wp.start", js, null, 0, 0);
            BootLog.i("语音唤醒 wp.start 已发出（结果看 wp.enter / wp.exit 事件）");
        } catch (Throwable t) {
            lastError = "wp.start 失败: " + chain(t);
            BootLog.w("语音唤醒 " + lastError);
        }
    }

    private void sendStop() {
        if (engine == null) return;
        listening = false;                  // 不管发没发成功，都不该再认为它还在听
        try {
            Method send = findMethod(engine, "send",
                    String.class, String.class, byte[].class, int.class, int.class);
            if (send == null) return;
            send.invoke(engine, "wp.stop", "{}", null, 0, 0);
            BootLog.i("语音唤醒 已发 wp.stop");
        } catch (Throwable t) {
            BootLog.w("语音唤醒 wp.stop 失败: " + chain(t));
        }
    }

    private void handleWakeup(final String info) {
        dispatchWake(info, false);
    }

    /**
     * 强制唤醒：不等唤醒词命中，直接走一次完整的「被唤醒」链路。
     *
     * <p>给「抹头顶」用 —— 用户伸手摸头顶就是要机器人立刻理他，这时候如果还要求
     * 他先喊一句唤醒词，那这个功能就没有意义了。区别只在计数口径上：
     * 词命中记 {@code hits}，强制唤醒记 {@code forced}，两个数分开看，
     * 免得"从来没喊动过"被强制唤醒的计数掩盖（这正是上一版看不清问题的老毛病）。</p>
     */
    public void forceWake(String why) {
        dispatchWake(why == null ? "forceWake" : why, true);
    }

    private void dispatchWake(final String info, boolean forced) {
        // ★ 挂起（屏保）期间不接受任何唤醒 —— 词命中与"抹头顶强制唤醒"一视同仁。
        //   否则屏保时摸一下机器人就会弹对话、开始播报，"功能暂停"就是空话。
        if (suspended) {
            BootLog.w("语音唤醒 挂起中，忽略" + (forced ? "强制唤醒" : "词命中") + " " + info);
            return;
        }
        // ★★★ v142：这就是"用户喊机器人名称后才能重新启动"落地的地方。
        //   离线模式 ≠ 关掉机器人：它只是停止往外花钱（在线听写/大模型/主动说话），
        //   唤醒引擎一直在听（零成本、本机资源）。所以**任何一次唤醒命中**
        //   —— 唤醒词、或抹头顶强制唤醒 —— 都等于"用户在叫它" ⇒ 立刻回到在线。
        //   放在这里而不是各个调用点：本项目"修在一个地方 ≠ 修好了"的老教训。
        if (Presence.offline) {
            try { Presence.exitOffline(ctx, "唤醒命中(" + info + ")"); } catch (Throwable ignored) {}
        }
        if (forced) forcedWakes++;
        else hitCount++;
        lastHit = info == null ? "" : info;
        lastEvent = (forced ? "forceWake " : "onWakeUp ") + lastHit;
        lastEventMs = System.currentTimeMillis();
        if (forced) BootLog.i("语音唤醒 ★强制唤醒（" + lastHit + "），第 " + forcedWakes + " 次");
        // ★ 命中后到 resume() 之间是"应答期"：这段时间引擎**故意**不在监听态
        //   （要把麦克风让给 TTS，否则播报会被自己听见又触发一次）。看门狗必须
        //   知道这件事，否则它会在 AI 回复的十几秒里判定"引擎死了"、反复 start，
        //   把正在播报的 TTS 喂回麦克风 → 自激。用 handling 标记 + 超时兜底。
        handling = true;
        handlingSince = lastEventMs;
        // 命中后立刻放麦：一是把麦克风让给随后要说话的 TTS，二是避免 AI 播报被自己的
        // 麦克风听见又触发一次（自激）。AgentService 在应答播完后会调 resume() 重新 start()。
        sendStop();
        if (listener != null) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try { listener.onWakeup(lastHit); } catch (Throwable ignored) {}
                }
            }, "ledi-wakeup").start();
        }
    }

    // ---------------- 对外控制 ----------------

    private Thread beat;

    /**
     * 监听自检心跳：每 8 秒留一行「采音是否还在流 / 最近事件」。
     * 目的是把用户喊话的时间点与引擎内部状态对齐——否则拉日志时只能看到一个
     * 静止的 wp.enter，无法区分「引擎死了」还是「引擎活着但没听见」。
     */
    private void startBeat() {
        if (beat != null) return;
        beat = new Thread(new Runnable() {
            @Override public void run() {
                int last = -1;
                long t0 = System.currentTimeMillis();
                while (running) {
                    try { Thread.sleep(8000); } catch (InterruptedException e) { return; }
                    if (!running) return;
                    int cur = micLogLines;
                    int delta = (last < 0) ? cur : (cur - last);
                    last = cur;
                    long ago = micLastLogMs == 0 ? -1 : (System.currentTimeMillis() - micLastLogMs);
                    int up = (int) ((System.currentTimeMillis() - t0) / 1000);
                    // 常识别期间 wp 是**故意**停着的，这时候再打"命中数=0"的自检行会把人
                    // 带偏（看上去像"唤醒了但没人喊"）。改成直说是常识别在听。
                    //
                    // ★★★ v188：原来这里是个 `continue` —— 它顺手把下面的 idleBeat() 也跳过了。
                    //   后果是"一开常识别，待机表情/动作**永久冻住**"（实测 100 秒内
                    //   live_beats 恒 0、idle_plays 恒 0）。这是本项目最典型的**静默失败**：
                    //   `continue` 是为"少打一行误导日志"加的，却把副作用一起删掉了 ——
                    //   日志的措辞可以随模式变，**副作用不能随模式消失**。
                    //   所以现在只改打哪一行，不动后面那段。
                    if (continuous) {
                        BootLog.i("常识别 自检 t+" + up + "s 唤醒引擎=已停(常识别接管)"
                                + " 采音+=" + delta + " 行(累计" + cur + ")"
                                + " 最近采音=" + (ago < 0 ? "从未" : ago + "ms前")
                                + " 说话中=" + isSpeaking());
                    } else {
                        BootLog.i("语音唤醒 自检 t+" + up + "s 采音+=" + delta
                                + " 行(累计" + cur + ") 最近采音=" + (ago < 0 ? "从未" : ago + "ms前")
                                + " 命中数=" + hitCount + " 事件=" + lastEvent);
                    }
                    // ★★★ v180：待机微动就挂在这条**本来就在跑**的心跳上（每 8 秒一拍）。
                    //   为什么不另起线程/闹钟：这台机器（MT6580 + 1GB）扛不住，
                    //   用户 09-25 明令禁止新增常驻线程。复用已有心跳 = 零边际成本。
                    //   idleBeat 自己带闸（说话中/对话窗口/应答中/屏保/频率），
                    //   所以这里可以无脑调 —— 它不动就是不动。
                    try { AgentService.idleBeat(); } catch (Throwable ignored) {}
                    // ★ v181：同一条心跳上再挂一闸 —— 离线满 N 分钟自动回在线。
                    //   离线状态的唯一出口原本是"再听到名字"，而进离线的成因常常正是
                    //   "屋里没人跟它说话" ⇒ 自我锁死。这一条把死结解开（无新线程）。
                    try { Presence.autoRecoverTick(ctx); } catch (Throwable ignored) {}
                }
            }
        }, "ledi-wake-beat");
        beat.setDaemon(true);
        beat.start();
    }

    private volatile int hitCount;
    private volatile int forcedWakes;        // 强制唤醒次数（抹头顶等），与词命中分开计

    public int forcedWakes() { return forcedWakes; }
    public int hitCount() { return hitCount; }
    public boolean isListening() { return listening; }
    public boolean isHandling() { return handling; }
    public int restarts() { return restarts; }

    // ---------------- 看门狗 ----------------

    /**
     * 引擎自愈：每 {@code DOG_PERIOD_MS} 检查一次「唤醒引擎是否还在采音、还在监听态」，
     * 掉了就重新 {@code wp.start}。
     *
     * <p>判死的两个条件（任一满足即救）：</p>
     * <ol>
     *   <li><b>不在监听态</b>：最近事件是 wp.exit 且已经过去 {@code GRACE_MS}；
     *       wp.enter 之后正常情况可以一直待着，退出只有"有人 let go"一种解释。</li>
     *   <li><b>采音停滞</b>：明明在监听态，但麦克风日志 {@code DOG_MIC_STALL_MS}
     *       没有新行 —— 引擎看着在听，其实音频链路已经断了（原厂主进程抢麦、
     *       TTS 占麦后没还…）。这一条能抓住"界面显示正常、喊了就是没反应"。</li>
     * </ol>
     *
     * <p>不插手的情况：正在离线 ASR 探测 / 听一轮 / 唤醒应答期（这几段本来就
     * 故意不听），以及刚救过不到 {@code DOG_MIN_GAP_MS}（避免抖动式重启）。</p>
     */
    private static final long DOG_PERIOD_MS = 5000;

    /**
     * 挂起期间（屏保、常识别关掉）的检查周期。
     *
     * <p>★ 为什么要有这个第二条周期：{@code dogTick()} 第一件事就是
     * {@code if (suspended ...) return;} —— 也就是说挂起期间每 5 秒醒一次**纯属白醒**，
     * 什么也不做。而屏保可能一挂就是几小时。拉到 30s 把这段无效唤醒砍掉 5/6，
     * 又不会漏掉任何"该救"的情况（引擎本来就没在跑）。</p>
     *
     * <p>退出挂起不依赖这个周期：{@code unsuspend()} 自己会 {@code sendStart()}，
     * 看门狗只是兜底。</p>
     */
    private static final long DOG_IDLE_PERIOD_MS = 30000;
    private static final long DOG_GRACE_MS = 6000;
    private static final long DOG_MIC_STALL_MS = 25000;
    private static final long DOG_MIN_GAP_MS = 20000;
    private static final long DOG_HANDLING_MAX_MS = 90000;

    private void startDog() {
        if (dog != null && dog.isAlive()) return;
        dog = new Thread(new Runnable() {
            @Override public void run() {
                while (running) {
                    // ★ 挂起时改用长周期（见 DOG_IDLE_PERIOD_MS）：这时候 dogTick 只会
                    //   直接 return，每 5s 醒一次毫无意义。
                    long nap = suspended ? DOG_IDLE_PERIOD_MS : DOG_PERIOD_MS;
                    try { Thread.sleep(nap); } catch (InterruptedException e) { return; }
                    if (!running) return;
                    try { dogTick(); } catch (Throwable t) {
                        BootLog.w("语音唤醒 看门狗异常: " + chain(t));
                    }
                }
            }
        }, "ledi-wake-dog");
        dog.setDaemon(true);
        dog.start();
        BootLog.i("语音唤醒 看门狗已启动（不在监听态 / 采音停滞 都会自动救回来）");
    }

    private void dogTick() {
        // micForDialog：对话通路正独占麦克风，这时候"不在监听态"是**故意的**，
        // 去救就是去抢麦 —— 而两个引擎同时抓麦会把进程打死（v69 现场）。
        // ★ continuous 同理且更彻底：常识别期间 wp **本来就不该在跑**，
        //   看门狗每 5 秒把它拉起来一次，就是把"故意停掉"当成"死了"来救。
        // ★ suspended 必须在这里挡：stop() 只是 running=false + sendStop()，而本函数
        //   **从不检查 running** ⇒ 停掉之后看门狗照旧把 wp 拉起来，"停"形同虚设。
        if (suspended || isAsrBusy() || asrNoResume || micForDialog || continuous) return;
        long now = System.currentTimeMillis();
        if (handling && now - handlingSince < DOG_HANDLING_MAX_MS) return;
        if (lastRestartMs != 0 && now - lastRestartMs < DOG_MIN_GAP_MS) return;

        String why = "";
        if (!listening) {
            // lastEventMs==0 表示连一条事件都没收到过（wp.start 可能压根没发出去）
            long idle = lastEventMs == 0 ? Long.MAX_VALUE / 2 : now - lastEventMs;
            if (idle > DOG_GRACE_MS) {
                why = "不在监听态（" + (lastEventMs == 0 ? "从未收到事件" :
                        "最近事件 " + (idle / 1000) + "s 前：" + lastEvent) + "）";
            }
        } else if (micLastLogMs > 0 && now - micLastLogMs > DOG_MIC_STALL_MS) {
            why = "在监听态但采音停了 " + ((now - micLastLogMs) / 1000) + "s（麦克风链路断了）";
        }
        if (why.isEmpty()) return;

        restarts++;
        lastRestartMs = now;
        lastRestartWhy = why;
        BootLog.w("语音唤醒 ★看门狗救回：第 " + restarts + " 次，" + why);
        if (inited) sendStart();
        else { ensureInit(); if (inited) sendStart(); }
    }

    /**
     * 挂起标志（屏保用）。
     *
     * <p><b>为什么不直接复用 {@link #stop()} / {@code running}</b>：{@code running}
     * 在本类里只是"打算听"的意图位，<b>看门狗 {@link #dogTick()} 完全不看它</b> ——
     * {@code stop()} 之后 {@code listening} 变 false，看门狗随即判定"引擎死了"并
     * 自动 {@code sendStart()} 救回来。所以停唤醒必须有一个看门狗也认的旗子。
     */
    private volatile boolean suspended;

    /** 屏保/休息：停唤醒，且让看门狗与强制唤醒都不再拉它起来。 */
    public synchronized String suspend() {
        suspended = true;
        running = false;
        sendStop();
        BootLog.i("语音唤醒 已挂起（屏保）：看门狗与强制唤醒都停");
        return "wake suspended";
    }

    /** 解除挂起：把监听完整拉回来（幂等）。 */
    public synchronized String unsuspend() {
        suspended = false;
        return start();
    }

    public boolean isSuspended() { return suspended; }

    public synchronized String start() {
        running = true;
        if (!ensureInit()) return "wake start failed: " + lastError;
        sendStart();
        startBeat();
        startDog();
        return "wake start (inited=" + inited + ", restarts=" + restarts + ")";
    }

    public synchronized String stop() {
        running = false;
        sendStop();
        return "wake stopped";
    }

    public String resume() {
        handling = false;               // 应答期结束，看门狗重新上岗
        // ★ 常识别期间**不能**把 wp 拉起来：麦克风归 ASR 循环独占，wp 一起来就是抢麦，
        //   真机上会直接把进程搞死（而且不留 tombstone）。这里必须挡在 sendStart 之前。
        if (suspended) return "wake suppressed (suspended / 屏保中)";
        if (continuous) return "wake suppressed (continuous ASR owns the mic)";
        if (!running) return "wake not running";
        if (inited) {
            sendStart();
        } else {
            ensureInit();
            if (inited) sendStart();
        }
        startDog();
        return "wake resumed (inited=" + inited + ")";
    }

    public void shutdown() {
        running = false;
        sendStop();
        if (asrProbe != null) asrProbe.release();
        engine = null;
        elProxy = null;
        inited = false;
    }

    // ---------------- 工具 ----------------

    private static Method findMethod(Object obj, String name, Class<?>... types) {
        try {
            Method m = obj.getClass().getMethod(name, types);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ignored) {
        }
        try {
            Class<?> iface = Class.forName(EM_CLS);
            Method m = iface.getMethod(name, types);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        while (t != null && depth < 6) {
            if (depth > 0) sb.append(" <= ");
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
            t = t.getCause();
            depth++;
        }
        return sb.toString();
    }

    // ---------------- 状态 ----------------

    public boolean isRunning() { return running; }
    public boolean isBound() { return inited; }
    public String lastEvent() { return lastEvent; }
    public String lastHit() { return lastHit; }
    public String lastError() { return lastError; }

    public String status() {
        long ago = micLastLogMs == 0 ? -1 : (System.currentTimeMillis() - micLastLogMs);
        long restartAgo = lastRestartMs == 0 ? -1
                : (System.currentTimeMillis() - lastRestartMs);
        return "{\"running\":" + running
                + ",\"inited\":" + inited
                // ★ listening 才是"现在真的在听吗"：running/inited 都只是"打算听/初始化过"，
                //   引擎 wp.exit 之后这两个仍然是 true（这正是"喊不动"却看不到异常的根源）
                + ",\"listening\":" + listening
                + ",\"handling\":" + handling
                + ",\"restarts\":" + restarts
                + ",\"last_restart_ago\":" + restartAgo
                + ",\"last_restart_why\":\"" + J.esc(lastRestartWhy)
                + "\",\"event_ago_ms\":" + (lastEventMs == 0 ? -1
                        : System.currentTimeMillis() - lastEventMs)
                + ",\"engine_ctx\":\"" + J.esc(engineCtx == null ? "" : engineCtx.getPackageName())
                + "\",\"last_event\":\"" + J.esc(lastEvent)
                + "\",\"last_hit\":\"" + J.esc(lastHit)
                + "\",\"hits\":" + hitCount
                + ",\"forced\":" + forcedWakes
                + ",\"last_exit\":\"" + J.esc(lastExit)
                + "\",\"auth_self\":\"" + J.esc(lastAuth)
                + "\",\"auth_orig\":\"" + J.esc(lastAuthOrig)
                + "\",\"wake_words\":\"" + J.esc(wakeWords)
                + "\",\"vocab_source\":\"" + J.esc(vocabSource)
                + "\",\"vocab_preset\":\"" + J.esc(vocabPreset)
                + "\",\"active_kws\":\"" + J.esc(activeKws)
                + "\",\"vocab_count\":" + Vocabulary.split(rawWakeWords).size()
                + ",\"asr_busy\":" + isAsrBusy()
                + ",\"continuous\":" + continuous
                + ",\"suspended\":" + suspended
                + ",\"speaking\":" + isSpeaking()
                + ",\"mic_for_dialog\":" + micForDialog
                + ",\"offline\":" + Presence.offline
                + ",\"live\":" + AgentService.liveMode
                + ",\"live_beats\":" + AgentService.liveBeats
                + ",\"live_plays\":" + AgentService.livePlays
                + ",\"live_idle_motion_plays\":" + AgentService.liveIdleMotionPlays
                + ",\"live_idle_motion_last\":\"" + J.esc(AgentService.liveIdleMotionLast) + "\""
                + ",\"not_wake_why\":\"" + J.esc(notWakeWhy())
                + "\",\"mic\":\"" + J.esc(micInfo)
                + "\",\"mic_lines\":" + micLogLines
                + ",\"mic_ago_ms\":" + ago
                + ",\"last_error\":\"" + J.esc(lastError)
                + "\",\"events\":" + ringJson() + "}";
    }

    /**
     * 「现在为什么可能叫不醒」—— 空串表示一切正常、时刻在听。
     *
     * <p>★ 为什么要把它算出来：实机排障时发现，"喊不动"至少有六种完全不同的原因 ——
     * <ol>
     *   <li>常识别接管（wp 故意停着）</li>
     *   <li>屏保挂起</li>
     *   <li>正在应答（把麦让给喇叭）</li>
     *   <li>正开麦听一轮 / 离线 ASR 探测</li>
     *   <li>**不在监听态**（wp.exit 之后没人拉回来 —— 这才是真的坏了）</li>
     *   <li>在监听态但**采音停了**（麦被别人抢走）</li>
     * </ol>
     * 前四种是**正常**的（本来就是故意不听），后两种才是故障。
     * 可它们在用户眼里是**同一个现象**——"喊了没反应"。
     * 不把原因算出来，每次都得拉日志逐行猜；算出来就是一眼的事。
     * 这与 {@code GET /listenled} 里的 {@code why} 是同一个思路。</p>
     */
    private String notWakeWhy() {
        if (!running) return "唤醒引擎已停（POST /wake {\"on\":true} 打开）";
        if (suspended) return "屏保中，唤醒被挂起（POST /sleep {\"on\":false} 叫醒）";
        if (continuous) return "常识别接管中（wp 故意停着，直接说话即可）";
        if (micForDialog) return "对话正独占麦克风（正常，等它说完）";
        if (isAsrBusy()) return "正在听一轮 / 离线 ASR 探测（正常）";
        if (handling) return "正在应答（正常，播报期间让麦给喇叭）";
        if (!listening) return "★不在监听态（最近事件 " + lastEvent
                + "）—— 等看门狗救回，或 POST /wake on=false 再 on=true 手动重启";
        if (micLastLogMs > 0 && System.currentTimeMillis() - micLastLogMs > DOG_MIC_STALL_MS) {
            return "★在监听态但采音停了 " + ((System.currentTimeMillis() - micLastLogMs) / 1000)
                    + "s（麦克风链路被抢）";
        }
        return "";
    }
}
