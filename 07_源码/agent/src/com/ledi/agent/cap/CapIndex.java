package com.ledi.agent.cap;

import com.ledi.agent.core.AgentService;
import com.ledi.agent.core.BootLog;
import com.ledi.agent.sdk.FaceController;
import com.ledi.agent.voice.IntentResolver;
import com.ledi.agent.core.J;
import com.ledi.agent.motion.MotionController;
import com.ledi.agent.sdk.SdkController;
import com.ledi.agent.sense.SensorHub;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 本机能力索引（v203）——「机器人自己找得到自己会什么」的那张表。
 *
 * <h3>为什么要有这一层</h3>
 * 用户在 2026-10-10 要的是：说话以后机器人<b>自己判断需要哪些数据和功能</b>，
 * 然后<b>在机器人里找对应的功能与状态</b>，再执行。要让模型"找得到"，前提是
 * 「机器人会什么」这件事**有一份可检索的清单**；否则模型只能凭训练记忆瞎猜动作名，
 * 猜出来的名字 {@link MotionController#isValidMotion} 一律不认，表现为"说了没反应"。
 *
 * <h3>★ 两条纪律（本项目反复踩过）</h3>
 *
 * <p><b>① 能力必须<b>自省</b>出来，不许硬编码。</b>
 * 动作名散落在 {@link MotionController#MOTIONS} 与 {@link MotionController#DANCE}
 * <b>两个独立数组</b>里（舞曲不在 MOTIONS 中），表情名在
 * {@link FaceController#listFaces()}，传感器字段在 {@link SensorHub}。
 * 任何"在索引里再抄一份"的做法都必然漂移（改了动作忘了改索引 ⇒ 索引说会、实际不会），
 * 这正是 {@link IntentResolver} 类注释里已经写过的教训。所以这里一律**现场读真源**。</p>
 *
 * <p><b>② 说明书文本打包进 APK assets。</b>
 * 用户明确要求「打包进机器人的本地部署」——不依赖 /sdcard 上的外部文档。
 * 于是把"这些功能是什么、怎么用、边界在哪"写在 {@code assets/ledi_caps.txt} 里，
 * 随包发布；改说明书只改 assets、不动代码。读不到也不报错，只是那段说明缺席
 * （索引仍能工作，因为能力清单是自省的）。</p>
 *
 * <h3>检索怎么做</h3>
 * 手写分词 + 打分，不引分词库：把查询按空白/标点切开，对每条能力条目算
 * 「关键词命中数 × 权重 + 名称精确命中加成」，返回前 k 条。
 * ★ 打分必须**可解释**：每条结果把它命中的关键词一起返回，否则"为什么排第一"
 * 无从核对（本项目铁律：判据要能对差）。
 */
public final class CapIndex {

    private static final String TAG = AgentService.TAG;

    /** 随包发布的说明书（改它不用改代码）。 */
    static final String CAPS_ASSET = "ledi_caps.txt";

    private CapIndex() {}

    // ================================================================ 能力条目

    /** 一条能力：名字 + 分类 + 关键词 + 说明（说明可能来自 assets）。 */
    public static final class Cap {
        public final String name;      // 稳定标识（动作名/表情名/工具名…）
        public final String kind;      // motion|dance|face|led|sensor|power|audio|system|doc
        public final String[] keys;    // 检索关键词（中文为主）
        public String desc = "";       // 人类可读说明（assets 里有就填，没有就留空）
        Cap(String name, String kind, String[] keys) {
            this.name = name; this.kind = kind; this.keys = keys;
        }
    }

    private static volatile List<Cap> CACHE;
    private static volatile long builtAt;
    private static volatile String docsRaw = "";
    private static volatile boolean docsLoaded;
    private static volatile String docsNote = "";

    /** 清缓存：动作表/表情表变了（理论上不会，但 reload 是廉价的保险）。 */
    static void invalidate() { CACHE = null; }

    // ================================================================ 构建

    /**
     * 现场构建能力清单。★ 每次调用都从真源读 —— 但结果会缓存，避免 /caps 被轮询时
     * 反复遍历 80 个动作名。{@link #invalidate()} 可强制重建。
     */
    static List<Cap> all() {
        List<Cap> c = CACHE;
        if (c != null) return c;
        synchronized (CapIndex.class) {
            if (CACHE != null) return CACHE;
            List<Cap> out = new ArrayList<Cap>();

            // —— 运动控制：普通动作（moveInterface 通道）
            for (String m : MotionController.MOTIONS) {
                out.add(new Cap(m, "motion", motionKeys(m)));
            }
            // —— 运动控制：舞曲（specialDance 通道；★ 与 MOTIONS 是**两个**数组）
            for (String d : MotionController.DANCE) {
                out.add(new Cap(d, "dance", new String[]{"舞", "跳舞", "舞曲", "dance", "跳舞动作"}));
            }

            // —— 表情：现场读 FaceController（本 ROM 的表情是资产文件名，会随版本变）
            try {
                List<String> fs = FaceController.listFaces();
                if (fs != null) for (String f : fs) {
                    out.add(new Cap(f, "face", new String[]{"表情", "脸", "face", f}));
                }
            } catch (Throwable t) {
                BootLog.w("能力索引 读表情表失败: " + t);
            }

            // —— 传感器 / 硬件（这几个是**固定**的物理能力，名字就是字段语义）
            out.add(new Cap("battery", "power", new String[]{"电量", "电池", "充电", "还剩多少电", "电压", "温度"}));
            out.add(new Cap("posture", "sensor", new String[]{"姿态", "站立", "躺下", "抱起", "倾斜", "姿势"}));
            out.add(new Cap("touch", "sensor", new String[]{"触摸", "摸头", "摸脸", "被摸", "头顶"}));
            out.add(new Cap("gyro", "sensor", new String[]{"陀螺仪", "角速度", "旋转", "倾角", "imu", "加速度"}));
            out.add(new Cap("headkey", "sensor", new String[]{"头顶键", "按键", "按钮", "长按", "短按"}));
            out.add(new Cap("volume", "audio", new String[]{"音量", "声音大小", "大声", "小声"}));
            out.add(new Cap("mic", "audio", new String[]{"麦克风", "麦", "录音", "听", "拾音"}));
            out.add(new Cap("tts", "audio", new String[]{"说话", "发声", "念", "播报", "语音合成"}));
            out.add(new Cap("led", "led", new String[]{"灯", "头顶灯", "灯光", "呼吸灯", "颜色"}));
            // ★ v206：灯语状态（听=蓝、思考=绿）也是"机器人会什么"的一部分 ——
            //   用户 2026-10-11 问"思考时亮灯"，说明这类观感状态是要被检索到的能力。
            out.add(new Cap("ledstate", "led", new String[]{"思考灯", "绿灯", "蓝灯", "灯语", "指示灯",
                    "状态灯", "在听", "思考中"}));
            out.add(new Cap("screen", "system", new String[]{"屏幕", "显示", "画面", "屏保", "息屏"}));
            out.add(new Cap("wifi", "system", new String[]{"wifi", "网络", "联网", "无线"}));
            out.add(new Cap("bt", "system", new String[]{"蓝牙", "配对", "蓝牙连接"}));
            out.add(new Cap("time", "system", new String[]{"时间", "几点", "日期", "今天"}));
            out.add(new Cap("state", "system", new String[]{"状态", "现在怎么样", "自检", "健康"}));

            // —— ★ v207：把**能力真源**里的查询/更改能力也纳入索引。
            //   用户问「你能查网速吗 / 能不能把灯关了」时，机器人得先"知道有这回事"。
            //   op 名单来自 {@link Abilities}（唯一真源），本类只负责给它配中文检索词 ——
            //   于是新增能力会自动出现在索引里，不会出现"加了 op 但检索不到"的漂移。
            for (Abilities.Spec sp : Abilities.SPECS) {
                if (Abilities.FORBID.equals(sp.kind)) continue;   // 红线不进"我会什么"的正向清单
                if (find(out, sp.name) != null) continue;          // 已有（动作/表情/电量…）不重复
                Cap cp = new Cap(sp.name, "ability", abilityKeys(sp.name));
                cp.desc = sp.desc;
                out.add(cp);
            }

            // —— 把 assets 说明书里的条目补进去（有则补 desc，没有就算了）
            Map<String, String> docs = docMap();
            if (!docs.isEmpty()) {
                for (Cap cp : out) {
                    String d = docs.get(cp.name);
                    if (d != null && !d.isEmpty()) cp.desc = d;
                }
                // assets 里写了但自省没覆盖到的条目（例如"场景/编排"这类软能力）也加进来，
                // 否则说明书里写了、索引里查不到 —— 那种"写了等于没写"最难查。
                for (Map.Entry<String, String> e : docs.entrySet()) {
                    if (find(out, e.getKey()) == null) {
                        Cap cp = new Cap(e.getKey(), "doc", new String[]{e.getKey()});
                        cp.desc = e.getValue();
                        out.add(cp);
                    }
                }
            }
            CACHE = out;
            builtAt = System.currentTimeMillis();
            BootLog.i("能力索引 已建立 " + out.size() + " 条（说明书 " + (docs.isEmpty() ? "缺席" : docs.size() + " 条") + "）");
            return out;
        }
    }

    private static Cap find(List<Cap> xs, String name) {
        for (Cap c : xs) if (c.name.equals(name)) return c;
        return null;
    }

    /**
     * 能力真源 op → 中文检索词（口语化）。
     *
     * <p>★ 这里是"翻译层"：把用户怎么说 映射到 本机怎么叫。op 名单本身来自
     * {@link Abilities}（唯一真源），所以即使某个 op 忘了配词，它仍然会出现在索引里
     * （靠 desc 兜底命中），不会漏 —— 只是排序靠后一点。</p>
     */
    private static String[] abilityKeys(String op) {
        if ("netinfo".equals(op))    return new String[]{"网络", "联网", "上网", "wifi", "信号", "IP", "网速", "连着吗"};
        if ("adbinfo".equals(op))    return new String[]{"adb", "无线调试", "调试端口", "5555", "投屏", "电脑连"};
        if ("btinfo".equals(op))     return new String[]{"蓝牙", "配对", "蓝牙连没连"};
        if ("screen".equals(op))     return new String[]{"屏幕", "屏保", "深睡", "息屏", "旋转", "正在显示"};
        if ("sensors".equals(op))    return new String[]{"传感器", "姿态", "触摸", "陀螺仪", "头顶键", "水平", "倾斜"};
        if ("sysinfo".equals(op))    return new String[]{"系统", "版本", "内存", "存储", "空间", "运行多久", "卡不卡"};
        if ("pose".equals(op))       return new String[]{"舵机", "电机", "手臂", "姿态", "位置"};
        if ("logs".equals(op))       return new String[]{"日志", "记录", "报错", "出了什么事"};
        if ("micinfo".equals(op))    return new String[]{"麦克风", "麦", "声源", "方位", "哪边说话"};
        if ("emotion".equals(op))    return new String[]{"情绪", "心情", "高兴", "生气", "难过", "开心"};
        if ("display".equals(op))    return new String[]{"屏幕显示", "写字", "显示文字", "打出来"};
        if ("displayoff".equals(op)) return new String[]{"清屏", "关掉屏幕文字", "别显示了"};
        if ("live".equals(op))       return new String[]{"活泼", "待机", "微动", "活泼一点", "安静点"};
        if ("idle".equals(op))       return new String[]{"待机动作", "多久动一次", "别乱动"};
        if ("listen".equals(op))     return new String[]{"常听", "一直听", "不用叫", "一直开着麦"};
        if ("think".equals(op))      return new String[]{"多轮思考", "想一下", "思考"};
        if ("name".equals(op))       return new String[]{"名字", "改名", "你叫什么", "给你取名"};
        if ("call".equals(op))       return new String[]{"称呼", "怎么叫我", "叫我"};
        if ("tone".equals(op))       return new String[]{"语气", "大人", "小孩", "声音档"};
        if ("rotation".equals(op))   return new String[]{"旋转", "横竖屏", "锁方向", "别转"};
        if ("wake".equals(op))       return new String[]{"唤醒", "主动说", "叫我一声"};
        return new String[]{"能力", "功能", op};
    }

    /** 给动作名配一组中文关键词 —— 让「握手」「抱一抱」这类说法也能命中英文动作名。 */
    private static String[] motionKeys(String m) {
        String s = m.toLowerCase(Locale.ROOT);
        if (s.contains("walk"))     return new String[]{"走", "前进", "走路", "行走", "walk"};
        if (s.contains("turnleft")) return new String[]{"左转", "向左", "转左"};
        if (s.contains("turnright"))return new String[]{"右转", "向右", "转右"};
        if (s.contains("run"))      return new String[]{"跑", "奔跑", "跑步", "run"};
        if (s.contains("hug"))      return new String[]{"抱", "拥抱", "抱一抱", "hug"};
        if (s.contains("handshake"))return new String[]{"握手", "握个手", "handshake"};
        if (s.contains("hi"))       return new String[]{"挥手", "打招呼", "举手", "hi"};
        if (s.contains("lifthand")) return new String[]{"举手", "抬手", "举起手"};
        if (s.contains("cheer"))    return new String[]{"欢呼", "加油", "庆祝", "高兴"};
        if (s.contains("comeon"))   return new String[]{"招手", "过来", "comeon"};
        if (s.contains("beckon"))   return new String[]{"招手", "召唤", "beckon"};
        if (s.contains("crosshand"))return new String[]{"抱臂", "交叉手", "叉腰"};
        if (s.contains("angry"))    return new String[]{"生气", "发怒", "angry"};
        if (s.contains("dizzy"))    return new String[]{"晕", "头晕", "转晕"};
        if (s.contains("wake"))     return new String[]{"醒来", "醒一醒", "wake"};
        if (s.contains("sledding")) return new String[]{"滑雪", "滑行", "sledding"};
        if (s.contains("roll"))     return new String[]{"翻滚", "侧滚", "打滚"};
        if (s.contains("struggle")) return new String[]{"挣扎", "摆手", "躺下挣扎"};
        if (s.contains("stand"))    return new String[]{"站", "站立", "站起来"};
        if (s.contains("reject"))   return new String[]{"拒绝", "不要", "摇头"};
        if (s.contains("traffic"))  return new String[]{"指挥交通", "交通"};
        if (s.contains("fly"))      return new String[]{"飞行", "飞", "变身"};
        if (s.contains("special_")) return new String[]{"特技", "表演", "special"};
        return new String[]{"动作", "motion", m};
    }

    // ================================================================ assets 说明书

    private static String readAsset(String nm) {
        Context ctx = AgentService.I;
        if (ctx == null) return "";
        InputStream in = null;
        try {
            in = ctx.getAssets().open(nm);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            docsNote = "读不到 " + nm + "（" + t.getClass().getSimpleName() + "）";
            return "";
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 说明书解析：每行 {@code 名字<TAB>说明}；{@code #} 开头是注释。
     *
     * <p>★ 用 TAB 而不是冒号：说明里必然出现中文冒号/英文冒号，用冒号切会让"名字"截断到
     * 句子中间（本项目已有过一次同类解析事故）。TAB 在说明里不会出现。</p>
     */
    private static Map<String, String> docMap() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        String raw = docs();
        if (raw.isEmpty()) return m;
        for (String line : raw.split("\r?\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int t = s.indexOf('\t');
            if (t <= 0) continue;
            String k = s.substring(0, t).trim();
            String v = s.substring(t + 1).trim();
            if (!k.isEmpty()) m.put(k, v);
        }
        return m;
    }

    private static String docs() {
        if (!docsLoaded) {
            synchronized (CapIndex.class) {
                if (!docsLoaded) {
                    docsRaw = readAsset(CAPS_ASSET);
                    docsLoaded = true;
                }
            }
        }
        return docsRaw;
    }

    // ================================================================ 检索

    /** 一条命中结果（可解释：把它命中的关键词也带上）。 */
    public static final class Hit {
        public final Cap cap;
        public final int score;
        public final String why;
        Hit(Cap c, int s, String w) { cap = c; score = s; why = w; }
    }

    /** 把查询切成词：空白 + 常见标点 + 全角标点都当分隔符。 */
    static List<String> tokens(String q) {
        List<String> out = new ArrayList<String>();
        if (q == null) return out;
        String n = IntentResolver.norm(q);     // 复用：去空白标点 + 小写（全角也覆盖）
        if (n.isEmpty()) return out;
        out.add(n);                            // 整串也算一个词（中文常说整句）
        // 再补 2~4 字滑窗，让"帮我看看还剩多少电"里的"电量"能被拆出来
        if (n.length() > 2) {
            for (int len = Math.min(4, n.length() - 1); len >= 2; len--) {
                for (int i = 0; i + len <= n.length(); i++) out.add(n.substring(i, i + len));
            }
        }
        return out;
    }

    /**
     * 检索能力/说明书。返回按分数降序的前 k 条（同分短名优先 —— 短名通常更泛用）。
     *
     * <p>打分规则（可解释）：
     * <pre>
     *   +8  关键词与查询整串相等
     *   +5  查询包含该关键词（关键词 ≥2 字）
     *   +4  关键词包含查询（查询 ≥2 字）
     *   +1  名字本身命中（在查询里出现）
     *   命中多个关键词则累加 —— 于是"抱一抱"会同时命中 hug 的「抱」与「拥抱」，排前。
     * </pre></p>
     */
    public static List<Hit> search(String query, int k) {
        ArrayList<Hit> hits = new ArrayList<Hit>();
        String normQ = IntentResolver.norm(query);
        if (normQ.isEmpty()) return hits;
        List<String> toks = tokens(query);
        for (Cap c : all()) {
            int score = 0;
            StringBuilder why = new StringBuilder();
            for (String key : c.keys) {
                String nk = IntentResolver.norm(key);
                if (nk.isEmpty()) continue;
                int add = 0;
                if (nk.equals(normQ)) add = 8;
                // ★ 关键词长度门槛：2 字以上用 contains；**单字关键词**（如"舞""灯""抱"）
                //   单独处理 —— 它们信息量低，只在"查询里就这一个动作词"时才给分，
                //   否则"他说他很无聊"会因为含"聊"而命中一堆东西。
                //   踩过的坑：「给我跳个舞」里的关键词是 `跳舞`（不连续），而 `舞` 是单字
                //   被整条跳过 ⇒ 检索返回空。所以单字必须能用，但要克制。
                else if (nk.length() >= 2 && normQ.contains(nk)) add = 5;
                else if (normQ.length() >= 2 && nk.contains(normQ)) add = 4;
                else if (nk.length() == 1 && normQ.length() <= 6 && normQ.indexOf(nk.charAt(0)) >= 0) {
                    add = 2;   // 单字：只给低分，且只在短查询里（长句里偶然含一个字不算意图）
                }
                if (add > 0) {
                    score += add;
                    if (why.length() > 0) why.append(',');
                    why.append(key);
                }
            }
            // 名字本身（英文动作名通常不会出现在中文查询里，但"dance"/"walk"这类会）
            String nn = IntentResolver.norm(c.name);
            if (!nn.isEmpty() && nn.length() >= 3 && normQ.contains(nn)) {
                score += 1;
                if (why.length() > 0) why.append(',');
                why.append("name:" + c.name);
            }
            // 说明文本命中（让"怎么充电"能检索到说明里写了充电的条目）
            // ★ 命中必须**记进 why**：设备侧自检就是这么抓出来的 —— 纯 JVM 里说明书读不到
            //   （AgentService.I==null），所以这条分支不执行、why 永远是满的；
            //   一上真机说明书加载了，就出现"有分数但 why 为空"的条目，
            //   而那正是本项目最忌讳的"分数说不清从哪来"。
            if (c.desc != null && !c.desc.isEmpty()) {
                String nd = IntentResolver.norm(c.desc);
                for (String t : toks) {
                    if (t.length() >= 2 && nd.contains(t)) {
                        score += 2;
                        if (why.length() > 0) why.append(',');
                        why.append("desc:" + t);
                        break;
                    }
                }
            }
            if (score > 0) hits.add(new Hit(c, score, why.toString()));
        }
        // 排序：分高优先，其次名字短优先（短名更可能是"主条目"），最后按名字定序（稳定）
        java.util.Collections.sort(hits, new java.util.Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) {
                if (a.score != b.score) return b.score - a.score;
                int la = a.cap.name.length(), lb = b.cap.name.length();
                if (la != lb) return la - lb;
                return a.cap.name.compareTo(b.cap.name);
            }
        });
        if (k > 0 && hits.size() > k) return new ArrayList<Hit>(hits.subList(0, k));
        return hits;
    }

    /**
     * 给模型看的一行式检索结果（省 token，且把"为什么命中"一起给，便于它判断相关性）。
     *
     * <p>★ 真机实测（2026-10-10）暴露过一个浪费：模型第一轮已经用 {@code TOOL doc 拥抱}
     * 查到了 {@code hug}，却**同一轮**又发了一条 {@code TOOL motion 拥抱}（中文名）→
     * 被本机拒成"未知动作"，白跑一轮。所以这里不但给名字，还**直接给出可抄的调用行**
     * （{@code motion hug} / {@code face happy1}），让模型照抄而不是自己翻译。</p>
     */
    public static String searchBrief(String query, int k) {
        List<Hit> hits = search(query, k);
        if (hits.isEmpty()) {
            return "（索引里没有与「" + (query == null ? "" : query) + "」直接相关的条目；"
                    + "说明你要的东西这台机器人可能不会做，请如实告诉主人，不要编造。）";
        }
        StringBuilder sb = new StringBuilder();
        for (Hit h : hits) {
            sb.append("- ").append(h.cap.name).append(" [").append(h.cap.kind).append(']');
            if (h.cap.desc != null && !h.cap.desc.isEmpty()) sb.append('：').append(h.cap.desc);
            if (h.why != null && !h.why.isEmpty()) sb.append("（命中:").append(h.why).append('）');
            // ★ 直接给"抄这一行"，别让模型自己把中文翻译成 op
            String call = callFor(h.cap);
            if (call != null) sb.append("  ⇒ 要用就抄这一行：").append(call);
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 一条能力"该怎么调用"的可抄行。
     *
     * <p>只对**名字容易翻译错或参数有约束**的给（动作 / 舞曲 / 表情的名字全是英文标识；
     * 灯的颜色只认单字母），其余（battery/state/time）没有歧义，给了反而啰嗦。</p>
     *
     * <p>★ 凡是给的示例，**必须真能跑** —— 见 {@link #selftest()} 里那条断言：
     * 示例里的组合要能在真值表里查到（这条断言就是为了 `led blue on` 那次事故加的）。</p>
     */
    static String callFor(Cap c) {
        if ("motion".equals(c.kind) || "dance".equals(c.kind)) return "TOOL motion " + c.name;
        if ("face".equals(c.kind)) return "TOOL face " + c.name;
        if ("led".equals(c.kind)) {
            // ★★★ v210：示例**从真值表 {@link SdkController#LED_MAP} 现取**，绝不手写。
            //   手写的那版是 `TOOL led blue on` —— **错的**（合法色只有 r/g/b/y/p/all），
            //   模型照抄必被拒（2026-10-11 真机实测：「把灯关掉」第一枪就打在这上面）。
            //   与"能力清单必须现读"同源：写死一份 = 迟早跟实现分叉。
            return "TOOL led " + SdkController.LED_MAP[0][0] + " " + SdkController.LED_MAP[0][1]
                    + "（颜色只认单个字母：红r/绿g/蓝b/黄y/粉p/全all；关灯用 " + closeAllTip() + "）";
        }
        if ("volume".equals(c.kind)) return "TOOL volume 8（0..15）";
        return null;
    }

    /** 「全关」那条的合法示例（也从真值表现取）。 */
    private static String closeAllTip() {
        for (String[] row : SdkController.LED_MAP) {
            if ("all".equals(row[0]) && "off".equals(row[1])) return "TOOL led all " + row[1];
        }
        return "TOOL led all off";
    }

    // ================================================================ 状态

    /** 动作 / 表情 / 说明书 的计数（给 /caps 与 /status 看）。 */
    public static String statusJson() {
        List<Cap> cs = all();
        int motion = 0, dance = 0, face = 0, doc = 0, ability = 0, other = 0;
        for (Cap c : cs) {
            if ("motion".equals(c.kind)) motion++;
            else if ("dance".equals(c.kind)) dance++;
            else if ("face".equals(c.kind)) face++;
            else if ("doc".equals(c.kind)) doc++;
            else if ("ability".equals(c.kind)) ability++;
            else other++;
        }
        return "{\"total\":" + cs.size()
                + ",\"motion\":" + motion
                + ",\"dance\":" + dance
                + ",\"face\":" + face
                + ",\"doc\":" + doc
                + ",\"ability\":" + ability
                + ",\"other\":" + other
                + ",\"asset\":\"" + J.esc(CAPS_ASSET) + "\""
                + ",\"docs_loaded\":" + !docsRaw.isEmpty()
                + ",\"docs_note\":\"" + J.esc(docsNote) + "\""
                + ",\"built_at\":" + builtAt + "}";
    }

    /** 全量清单（给 /caps?full=1，网页面板要看全）。 */
    public static String fullJson() {
        List<Cap> cs = all();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cs.size(); i++) {
            Cap c = cs.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"name\":\"").append(J.esc(c.name))
              .append("\",\"kind\":\"").append(c.kind)
              .append("\",\"desc\":\"").append(J.esc(c.desc == null ? "" : c.desc))
              .append("\",\"keys\":[");
            for (int j = 0; j < c.keys.length; j++) {
                if (j > 0) sb.append(',');
                sb.append('"').append(J.esc(c.keys[j])).append('"');
            }
            sb.append("]}");
        }
        return sb.append(']').toString();
    }

    // ================================================================ 自检

    /**
     * 纯静态自检：不读 assets（{@code AgentService.I == null} 时说明书自然缺席）、
     * 不联网、不碰 org.json —— 只验**检索打分**这套逻辑本身。
     *
     * <p>★ 为什么自检要能"没素材也跑"：能力清单是**自省**的，脱离设备时
     * {@link MotionController#MOTIONS} 仍在类里（静态数组，不依赖 Context），
     * 所以动作/表情那部分在纯 JVM 里也能建起来 —— 这张考卷因此是真有内容的。</p>
     */
    public static String selftest() {
        StringBuilder log = new StringBuilder();
        int ok = 0, n = 0;

        // 分词
        // ★ 断言要跟"人真的会怎么说"一致：「还剩多少电」里**没有**「量」字，
        //   所以正确期望是切不出「电量」；要验证"能切出电量"，得用真含「电量」的说法。
        n++; if (tokens("查询电量还有多少").contains("电量")) ok++;
        else log.append("  ✗ tokens 未切出「电量」\n");
        n++; if (!tokens("还剩多少电").contains("电量")) ok++;
        else log.append("  ✗ tokens 凭空造出了原文没有的「电量」（越界）\n");
        n++; if (tokens("").isEmpty()) ok++;
        else log.append("  ✗ tokens 空串应为空\n");

        // 检索：电量
        {
            List<Hit> h = search("还剩多少电", 3);
            n++; if (!h.isEmpty() && "battery".equals(h.get(0).cap.name)) ok++;
            else log.append("  ✗ 「还剩多少电」应首中 battery（got=" + names(h) + "）\n");
        }
        // 检索：跳舞（应命中 dance 类，且不是 motion）
        {
            List<Hit> h = search("给我跳个舞", 3);
            n++; if (!h.isEmpty() && "dance".equals(h.get(0).cap.kind)) ok++;
            else log.append("  ✗ 「给我跳个舞」应首中 dance 类（got=" + names(h) + "）\n");
        }
        // 检索：握手 → hug/handshake 之一，且 kind=motion
        {
            List<Hit> h = search("握个手", 3);
            boolean good = false;
            for (Hit x : h) if ("motion".equals(x.cap.kind)) good = true;
            n++; if (good) ok++;
            else log.append("  ✗ 「握个手」未命中任何 motion\n");
        }
        // 检索：无关键词 → 空（不许瞎给）
        {
            List<Hit> h = search("zzzqqq", 3);
            n++; if (h.isEmpty()) ok++;
            else log.append("  ✗ 无关键词查询应返回空（got=" + names(h) + "）\n");
        }
        // ★ 每个结果都必须带 why（可解释性）—— 否则"为什么排第一"无从核对
        {
            List<Hit> h = search("电量", 2);
            boolean allWhy = !h.isEmpty();
            for (Hit x : h) if (x.why == null || x.why.isEmpty()) allWhy = false;
            n++; if (allWhy) ok++;
            else log.append("  ✗ 命中结果缺 why（不可解释）\n");
        }
        // 分数单调：结果必须按分数降序
        {
            List<Hit> h = search("电量", 6);
            boolean sorted = true;
            for (int i = 1; i < h.size(); i++) if (h.get(i).score > h.get(i - 1).score) sorted = false;
            n++; if (sorted) ok++;
            else log.append("  ✗ 结果未按分数降序\n");
        }
        // 动作全表已在索引里（MOTIONS 与 DANCE 都要在）—— 防"只收了一个数组"
        {
            List<Cap> cs = all();
            boolean hasMotion = false, hasDance = false;
            for (Cap c : cs) {
                if ("motion".equals(c.kind) && "walk".equals(c.name)) hasMotion = true;
                if ("dance".equals(c.kind) && c.name.startsWith("dance_")) hasDance = true;
            }
            n++; if (hasMotion && hasDance) ok++;
            else log.append("  ✗ 索引缺动作（motion=" + hasMotion + " dance=" + hasDance + "）\n");
        }
        // 动作条数必须 >= MOTIONS.length + DANCE.length（不许丢条目）
        {
            int expect = MotionController.MOTIONS.length + MotionController.DANCE.length;
            int got = 0;
            for (Cap c : all()) if ("motion".equals(c.kind) || "dance".equals(c.kind)) got++;
            n++; if (got >= expect) ok++;
            else log.append("  ✗ 动作条目少了：期望≥" + expect + " 实得 " + got + "\n");
        }
        // 结果条数受 k 限制
        {
            List<Hit> h = search("动作", 2);
            n++; if (h.size() <= 2) ok++;
            else log.append("  ✗ k 未生效（got=" + h.size() + "）\n");
        }
        // ★★★ 说明书给的"可抄行"必须**真能跑**：示例里的 色+动作 要能在真值表里查到。
        //   来历：手写的 `TOOL led blue on` 不是合法组合（合法色只有 r/g/b/y/p/all），
        //   模型照抄被拒 ——「照着说明书做反而做错」是最毒的一种漂移。
        {
            String tip = null;
            for (Cap c : all()) if ("led".equals(c.kind)) { tip = callFor(c); break; }
            boolean good = false;
            if (tip != null) {
                String[] p = tip.replace('（', ' ').trim().split("\\s+");
                if (p.length >= 4 && "led".equals(p[1])) {
                    for (String[] row : SdkController.LED_MAP)
                        if (row[0].equals(p[2]) && row[1].equals(p[3])) { good = true; break; }
                }
            }
            n++; if (good) ok++;
            else log.append("  ✗ led 可抄行不是真值表里的合法组合：「" + tip + "」\n");
        }
        // 每条给出来的可抄行都必须有"op 名 + 至少一个参数"，不许是半截话
        {
            int bad = 0;
            for (Cap c : all()) {
                String tip = callFor(c);
                if (tip == null) continue;
                if (!tip.startsWith("TOOL ") || tip.trim().indexOf(' ') == tip.trim().lastIndexOf(' ')) bad++;
            }
            n++; if (bad == 0) ok++;
            else log.append("  ✗ 有 " + bad + " 条可抄行格式不完整\n");
        }

        StringBuilder out = new StringBuilder();
        out.append("能力索引自检 ").append(ok).append('/').append(n).append(ok == n ? " PASS" : " FAIL");
        if (ok != n) out.append('(').append(n - ok).append(')');
        out.append('\n');
        if (log.length() > 0) out.append(log);
        out.append("索引 ").append(all().size()).append(" 条（自省：MOTIONS+DANCE 动作表、FaceController 表情表；说明书 assets/")
           .append(CAPS_ASSET).append("）\n");
        return out.toString();
    }

    private static String names(List<Hit> h) {
        StringBuilder sb = new StringBuilder();
        for (Hit x : h) { if (sb.length() > 0) sb.append('/'); sb.append(x.cap.name).append('@').append(x.score); }
        return sb.length() == 0 ? "(空)" : sb.toString();
    }
}
