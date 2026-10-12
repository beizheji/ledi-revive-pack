package com.ledi.agent.cap;

import com.ledi.agent.core.AgentService;
import com.ledi.agent.sdk.FaceController;
import com.ledi.agent.core.J;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import android.content.Context;

/**
 * ★★★ v189：情绪系统 —— <b>词汇与分档全部来自原厂 `assets/emotions.xml` /
 * `assets/emotions_threshold.xml`</b>，不是我们自己编的。
 *
 * <h3>原厂那套长什么样（静态逆向所得）</h3>
 * <ul>
 *   <li>{@code emotions.xml}：{@code <item code="10300">happy</item>} —— 数字码 → 情绪名。
 *       码段有语义：{@code 1xxxx} 偏正面（happy/excite/love/expect…）、
 *       {@code 2xxxx} 偏负面（angry/cry/fear/unhappy…）、{@code 3xxxx} 注意/注视
 *       （think/stare/up/down/left/right…）、{@code 5xxxx} 卖萌（happys/naughtys/thank/praise）。</li>
 *   <li>{@code emotions_threshold.xml}：{@code lowest/middle/highest} 三个数组，
 *       各列一组"这个强度档下该出现哪些情绪"。原厂用云端打分
 *       （dex 里确有 {@code /nlpgetemotion/emotionscore.do}）决定强度；
 *       <b>我们是离线机器人，没有云，所以改用本地事件驱动</b>（见下）。</li>
 * </ul>
 *
 * <h3>我们怎么驱动它（离线）</h3>
 * 两个维度：<b>愉悦度 valence</b> 与 <b>激活度 arousal</b>，都在 0~1，向基线缓慢回落。
 * <ul>
 *   <li>有人跟它说话 → arousal 抬（→ expect/attention 一类"注意"），valence 微升；</li>
 *   <li>被夸（真好/厉害/棒…）→ valence 明显升（→ happys/praise/thank）；</li>
 *   <li>被骂（笨/讨厌/闭嘴…）→ valence 降（→ dissatisfied/unhappy）；</li>
 *   <li>长时间没人理 → arousal 持续降（→ doze/boring，即"发呆"）；</li>
 *   <li>被触摸/抹头顶 → arousal 抬（→ surprise/wink）。</li>
 * </ul>
 * 选情绪：先按 arousal 落到 `lowest|middle|highest` 档，再在该档里按 valence 的符号挑
 * （正面取正向那几个、负面取负向那几个）。<b>这样"哪张脸"始终由原厂的分档表决定。</b>
 *
 * <h3>★ 为什么这套东西要能在 PC 上跑（没有硬件也跑）</h3>
 * {@link #selftest(String, String)} 是**纯静态**的：只吃两段 XML 文本，不碰 Context、
 * 不碰 org.json（`android.jar` 里的 `org.json` 是 stub，编得过跑不了 —— 本项目已吃过这个亏）。
 * build 阶段当硬门禁跑，保证"情绪 → 脸"的映射不会静默腐烂。
 *
 * <h3>★ 归因口径</h3>
 * 面部动画与原厂情绪的**绑定关系**没有从静态逆向里恢复出来（原厂把 code 交给服务端播片）。
 * 所以"情绪名 → 我方是哪张脸"这张表是我们自己定的，并在 {@link #MAPPED} 里如实标注。
 * 不许把它说成"原厂的映射"。
 */
public final class Emotion {

    // ---------------------------------------------------------------- 词汇（来自原厂 XML）

    /** 数字码 → 情绪名（原厂 emotions.xml 的忠实副本，运行时从 assets 读）。 */
    static final Map<Integer, String> CODES = new LinkedHashMap<Integer, String>();
    /** 强度档 → 情绪名列表（原厂 emotions_threshold.xml 的忠实副本）。 */
    static final Map<String, List<String>> TIERS = new LinkedHashMap<String, List<String>>();
    static volatile boolean loaded = false;
    static volatile String loadNote = "未加载";

    // ---------------------------------------------------------------- 状态

    /** 当前情绪名（原厂词汇表里的一个）。 */
    static volatile String name = "calm";
    /** 愉悦度 0~1（0.5 = 中性基线）。 */
    public static volatile float valence = 0.5f;
    /** 激活度 0~1（0.35 = 静息基线；越低越"发呆"）。 */
    public static volatile float arousal = 0.35f;
    static volatile long lastAt = 0L;
    static volatile int changes = 0;
    static volatile String lastWhy = "";
    /** 最近一次"有人跟它打交道"的时间（与 AgentService.lastUserAt 同源语义，但独立记）。 */
    static volatile long lastUserAt = 0L;

    static final float BASE_V = 0.50f;
    static final float BASE_A = 0.35f;
    /** 多久没人理就进入"发呆"候选区。 */
    static final long DOZE_MS = 120_000L;

    // ================================================================ ★ v214 心情层（慢变量）
    //
    // 为什么要在 valence/arousal 之外**再加一层**（而不是把回落调慢）：
    //   valence/arousal 是**快变量** —— 8 秒心跳 ×6% 回落，被夸一次的余温约 2 分钟。
    //   它回答的是"此刻摆哪张脸"。而"陪伴感"要的是**能持续几十分钟的"今天心情"**：
    //   被夸一句之后，接下来一段聊天的语气都该轻快些；被凶一句之后该闷一阵。
    //   两者时间尺度差一个数量级。若硬用同一个变量：把回落调慢 ⇒ 脸会长时间卡在
    //   一个表情上（"一直傻笑"）；调快 ⇒ 心情留不住。⇒ 拆两层。
    //
    //   valence/arousal = 脸（快）        mood = 说话心情（慢）
    //
    // ★ 措辞唯一出处：提示词与状态简报里的"心情"**都**取 {@link #moodLabel()}，
    //   不许在别处再写一份判定（本项目"两条规则散在两处迟早对不上"）。
    /** 心情 0~1（0.5 中性）。★ 慢变量：只有被夸/被骂/被摸/有人说话才动。 */
    static volatile float mood = 0.5f;
    /** 最近一次"推动心情"的事件（**只进 /emotion，不进提示词** —— 免得模型拿"你刚骂我"去回嘴）。 */
    static volatile String moodWhy = "";
    /** 心情档位变过几次（好/一般/差之间的跳变，不是每次微调）。 */
    static volatile int moodChanges = 0;
    static final float MOOD_BASE = 0.50f;
    /** 每次心跳心情向基线回落的比例。★ 必须远小于 valence 的 0.06 —— 自检里有断言。 */
    static final float MOOD_RATE = 0.012f;
    /** 心情分档阈值：≥GOOD 好、≤BAD 差、中间一般。 */
    static final float MOOD_GOOD = 0.66f;
    static final float MOOD_BAD  = 0.36f;

    // ---------------------------------------------------------------- 解析（纯函数）

    /** 解析 {@code emotions.xml}：抽出 {@code code → 名字}。纯函数，可离线测。 */
    static Map<Integer, String> parseCodes(String xml) {
        Map<Integer, String> m = new LinkedHashMap<Integer, String>();
        if (xml == null) return m;
        java.util.regex.Matcher it = java.util.regex.Pattern
                .compile("<item\\s+code=\"(\\d+)\"\\s*>([^<]+)</item>").matcher(xml);
        while (it.find()) {
            try {
                m.put(Integer.parseInt(it.group(1)), it.group(2).trim());
            } catch (Throwable ignored) {}
        }
        return m;
    }

    /** 解析 {@code emotions_threshold.xml}：抽出三个档各自的情绪名列表。纯函数。 */
    static Map<String, List<String>> parseTiers(String xml) {
        Map<String, List<String>> m = new LinkedHashMap<String, List<String>>();
        if (xml == null) return m;
        java.util.regex.Matcher arr = java.util.regex.Pattern
                .compile("<array\\s+name=\"([^\"]+)\">(.*?)</array>",
                        java.util.regex.Pattern.DOTALL).matcher(xml);
        while (arr.find()) {
            List<String> l = new ArrayList<String>();
            java.util.regex.Matcher it = java.util.regex.Pattern
                    .compile("<item>([^<]+)</item>").matcher(arr.group(2));
            while (it.find()) l.add(it.group(1).trim());
            m.put(arr.group(1).trim(), l);
        }
        return m;
    }

    /** 从 assets 读两个表（只在真机上调用一次）。 */
    public static synchronized String load(Context ctx) {
        if (loaded) return loadNote;
        try {
            String a = readAsset(ctx, "emotions.xml");
            String b = readAsset(ctx, "emotions_threshold.xml");
            CODES.clear(); CODES.putAll(parseCodes(a));
            TIERS.clear(); TIERS.putAll(parseTiers(b));
            loaded = !CODES.isEmpty();
            loadNote = "码 " + CODES.size() + " 个 / 档 " + TIERS.size() + " 组";
        } catch (Throwable t) {
            loadNote = "加载失败: " + t;
        }
        return loadNote;
    }

    private static String readAsset(Context ctx, String nm) throws Exception {
        InputStream in = ctx.getAssets().open(nm);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    // ---------------------------------------------------------------- 情绪名 → 我方表情

    /**
     * 情绪名 → 可用表情（我方 {@code FaceController} 里的名字）。
     *
     * <p>★ 这张表**不是原厂的**（原厂把 code 交给服务端播片，静态逆向恢复不出绑定点），
     * 是我们按语义配的，所以单独标出来、并进 build 自检 —— 免得将来有人以为它是逆向结果。</p>
     */
    static String[] facesFor(String emo) {
        String e = emo == null ? "" : emo.trim().toLowerCase();
        if (e.equals("happy"))      return new String[]{"happy1", "happy2", "happy3", "pleased", "like"};
        if (e.equals("happys"))     return new String[]{"happy2", "happy3", "enjoy1", "like"};
        if (e.equals("excite"))     return new String[]{"exciting", "happy3", "happy2"};
        if (e.equals("excites"))    return new String[]{"exciting", "happy3", "enjoy2"};
        if (e.equals("smile"))      return new String[]{"smile", "pleased", "like"};
        if (e.equals("surprise"))   return new String[]{"surprise", "shock", "attention"};
        if (e.equals("wink"))       return new String[]{"wink", "cool"};
        if (e.equals("relax"))      return new String[]{"squint", "eyes_close", "smile"};
        if (e.equals("doze"))       return new String[]{"drowsy", "yawn", "eyes_close", "boring"};
        if (e.equals("boring"))     return new String[]{"boring", "drowsy", "look_around"};
        if (e.equals("confused"))   return new String[]{"confuse", "curious", "squint"};
        if (e.equals("love"))       return new String[]{"adore", "like", "shy"};
        if (e.equals("expect"))     return new String[]{"attention", "curious", "look_up"};
        if (e.equals("desire"))     return new String[]{"like", "adore", "attention"};
        if (e.equals("serious"))    return new String[]{"serious", "hard", "attention"};
        if (e.equals("unhappy"))    return new String[]{"sad", "wronged", "boring"};
        if (e.equals("dissatisfied")) return new String[]{"boring", "pity", "confuse"};
        if (e.equals("disgust"))    return new String[]{"hate", "angry1", "mustache"};
        if (e.equals("angry"))      return new String[]{"angry1", "angry2", "rage1"};
        if (e.equals("mad"))        return new String[]{"rage1", "rage2", "angry3"};
        if (e.equals("grimance"))   return new String[]{"eyes_squeeze", "hard", "squint"};
        if (e.equals("pity"))       return new String[]{"pity", "wronged", "sad"};
        if (e.equals("cry"))        return new String[]{"cry", "sad", "wronged"};
        if (e.equals("fear"))       return new String[]{"scared", "terrified", "shock"};
        if (e.equals("left_right")) return new String[]{"blink_around", "look_down_left", "look_down_right"};
        if (e.equals("up"))         return new String[]{"look_up", "look_up_left", "look_up_right"};
        if (e.equals("down"))       return new String[]{"look_down", "look_down_left", "look_down_right"};
        if (e.equals("left"))       return new String[]{"look_down_left", "look_up_left", "blink_around"};
        if (e.equals("right"))      return new String[]{"look_down_right", "look_up_right", "blink_around"};
        if (e.equals("think"))      return new String[]{"curious", "serious", "look_up_left"};
        if (e.equals("stare"))      return new String[]{"attention", "serious", "blink"};
        if (e.equals("kawaii"))     return new String[]{"adore", "shy", "smile"};
        if (e.equals("awkward"))    return new String[]{"shy", "confuse", "squint"};
        if (e.equals("naughtys"))   return new String[]{"wink", "mustache", "cool"};
        if (e.equals("thank"))      return new String[]{"pleased", "smile", "like"};
        if (e.equals("praise"))     return new String[]{"happy1", "adore", "smile"};
        if (e.equals("calm"))       return new String[]{"blink", "blink_around", "blink_nod",
                                                       "look_around", "look_up", "look_down"};
        return new String[]{"blink", "look_around", "smile"};      // 兜底：中性、短、不吓人
    }

    /** 该情绪是不是"负面"（2xxxx 段）—— 用来避免"心情好却摆臭脸"这类自相矛盾。 */
    static boolean isNegative(String emo) {
        String e = emo == null ? "" : emo.toLowerCase();
        return e.equals("unhappy") || e.equals("dissatisfied") || e.equals("disgust")
                || e.equals("angry") || e.equals("mad") || e.equals("cry")
                || e.equals("fear") || e.equals("pity") || e.equals("grimance");
    }

    // ---------------------------------------------------------------- 事件驱动

    private static void clamp() {
        if (valence < 0f) valence = 0f; if (valence > 1f) valence = 1f;
        if (arousal < 0f) arousal = 0f; if (arousal > 1f) arousal = 1f;
    }

    private static void clampMood() {
        if (mood < 0f) mood = 0f; if (mood > 1f) mood = 1f;
    }

    /**
     * 心情分档（**纯函数**，自检直接断言它）。
     *
     * <p>★ 只有"好/一般/差"三个字 —— 刻意不做更细的档。理由：档越细，措辞段就得分得越细，
     * 而"心情"这种东西本来就是模糊的；三个档足够驱动语气，且能被人一眼验收。</p>
     */
    static String moodLabelOf(float m) {
        if (m >= MOOD_GOOD) return "好";
        if (m <= MOOD_BAD)  return "差";
        return "一般";
    }

    /**
     * 一次心跳的心情演进（**纯函数**）。
     *
     * <p>自检断言"心情比情绪慢一个量级"就是拿它跟 {@link #MOOD_RATE} 比 —— 而不是靠读注释。</p>
     */
    static float moodStep(float m) {
        float r = m + (MOOD_BASE - m) * MOOD_RATE;
        return r < 0f ? 0f : (r > 1f ? 1f : r);
    }

    /** 推一把心情。{@code why} 只记在 /emotion 里（不进提示词）。 */
    private static void moodPush(float d, String why) {
        String before = moodLabelOf(mood);
        mood += d;
        clampMood();
        if (Math.abs(d) >= 0.02f) moodWhy = why;      // 极小的"有人说话"不覆盖归因
        if (!moodLabelOf(mood).equals(before)) moodChanges++;
    }

    /** 当前心情（"好/一般/差"）。★★ 提示词与状态简报**共用这一份**措辞。 */
    public static String moodLabel() { return moodLabelOf(mood); }

    /**
     * ★ v214：**直接给心情**（0..1）—— 与 {@code POST /emotion {"valence":…,"arousal":…}}
     * 的"直给维度"是同一性质的一个调试/验收入口。
     *
     * <p>为什么需要它：情绪有"被夸/被骂"这类**真实事件**驱动（走对话通路），但那条路要
     * 依赖大模型在不在线 —— 验收时若刚好没配 key，"心情会不会影响说话"这条判据就**取不到证据**。
     * 给一个确定性入口，才能把"心情 → 提示词措辞"这一环单独验干净（真机验收用）。</p>
     */
    public static void setMood(float m) {
        mood = m;
        clampMood();
        moodWhy = "端点直接给心情";
        pick("端点直接给心情", 0);
    }

    /** 有人跟它说话：激活度抬起来（"注意"档），愉悦度微升，心情极轻地往上挪。 */
    public static void onUserSpoke() {
        lastUserAt = System.currentTimeMillis();
        arousal = Math.min(1f, arousal + 0.25f);
        valence = Math.min(1f, valence + 0.05f);
        moodPush(0.010f, "有人说话");
        pick("有人说活", 0);
    }

    /** 被夸：明显转好（心情也记一笔 —— 这是"陪伴感"最主要的来源）。 */
    public static void onPraise() {
        lastUserAt = System.currentTimeMillis();
        valence = Math.min(1f, valence + 0.30f);
        arousal = Math.min(1f, arousal + 0.20f);
        moodPush(0.180f, "被夸");
        pick("被夸", 0);
    }

    /** 被骂：明显转差。★ mood 掉得比 valence 更深，但**收得慢** —— 才叫"心情"。 */
    public static void onScold() {
        lastUserAt = System.currentTimeMillis();
        valence = Math.max(0f, valence - 0.30f);
        arousal = Math.min(1f, arousal + 0.20f);
        moodPush(-0.220f, "被骂");
        pick("被骂", 0);
    }

    /** 被触摸/抹头顶：惊一下（被关注对心情是正面的，但很轻）。 */
    public static void onTouch() {
        lastUserAt = System.currentTimeMillis();
        arousal = Math.min(1f, arousal + 0.35f);
        moodPush(0.040f, "被摸");
        pick("被摸", 0);
    }

    /**
     * 心跳（挂在既有的 8 秒自检上，**不新增线程**）：向基线回落；长时间没人理就"发呆"。
     */
    public static void tick() {
        long now = System.currentTimeMillis();
        float dv = (BASE_V - valence) * 0.06f;
        float da = (BASE_A - arousal) * 0.10f;
        valence += dv; arousal += da;
        long quiet = lastUserAt == 0 ? Long.MAX_VALUE : (now - lastUserAt);
        if (quiet >= DOZE_MS) {
            valence += (0.45f - valence) * 0.10f;      // 发呆：往"平淡"走
            arousal += (0.10f - arousal) * 0.15f;      // 越待越低
        }
        clamp();
        // ★ v214 心情：只朝基线慢慢回落。**发呆不压心情** —— 没人理它是"没发生事"，
        //   不是"被冷落"，让 mood 因此变差会变成"越没人理越不高兴"（这台机器要的是陪伴，
        //   不是情绪绑架）。心情只由**确确实实发生过的**事件推动（被夸/被骂/被摸）。
        mood = moodStep(mood);
        pick(quiet >= DOZE_MS ? "无人搭理" : "自然回落", 0);
    }

    /**
     * 按当前 (arousal, valence) **从原厂分档表**里挑一个情绪。
     *
     * <p>这是整套系统的核心 8 行：档位由 arousal 决定（原厂 lowest/middle/highest），
     * 档内取正面还是负面由 valence 决定。<b>表里没有合适的就回落到 calm</b>，
     * 不去"发明"原厂词汇表以外的情绪名。</p>
     */
    static String decide(float v, float a) {
        String tier = a < 0.30f ? "lowest" : (a < 0.62f ? "middle" : "highest");
        List<String> pool = TIERS.get(tier);
        if (pool == null || pool.isEmpty()) return v >= 0.5f ? "calm" : "dissatisfied";
        List<String> pos = new ArrayList<String>();
        List<String> neg = new ArrayList<String>();
        for (String s : pool) {
            if (isNegative(s)) neg.add(s);
            // ★ "calm" 是**中性**，不算正面 —— 否则"心情很好"会被挑成一张平静脸
            //   （v189 自检里就抓到过：高愉悦 0.95 挑出 calm，断言虽然过但语义不对）。
            else if (!"calm".equals(s)) pos.add(s);
        }
        List<String> pick;
        if (v >= 0.58f && !pos.isEmpty()) {
            pick = pos;                                  // 明显高兴
        } else if (v <= 0.42f && !neg.isEmpty()) {
            pick = neg;                                  // 明显不高兴
        } else if (pool.contains("calm")) {
            // ★ 中间带（0.42~0.58）：**优先 calm**。
            //   v190 实测：没人搭理时 valence 会飘到 0.48，落进 lowest 档的负面分支 ⇒
            //   机器人自己摆出一张"不满意"的脸，看着很怪（"没人惹它它还不高兴"）。
            //   原厂 lowest 档里本来就有 calm，选它是**表内的**，不是我们编的。
            pick = new ArrayList<String>();
            pick.add("calm");
        } else {
            pick = pool;
        }
        if (pick.isEmpty()) pick = pool;
        // 同一档里轮换，避免"一直是同一张脸"
        int i = (int) (Math.abs(changes) % pick.size());
        return pick.get(i);
    }

    private static void pick(String why, int unused) {
        String nm = decide(valence, arousal);
        lastWhy = why;
        if (!nm.equals(name)) {
            name = nm;
            changes++;
            lastAt = System.currentTimeMillis();
        }
    }

    /** 手动设定（端点用）：按名字或码。返回 true 表示认。 */
    public static boolean setByName(String nm) {
        if (nm == null) return false;
        String want = nm.trim().toLowerCase();
        if (want.isEmpty()) return false;
        if (!CODES.containsValue(want)) return false;
        name = want;
        changes++;
        lastAt = System.currentTimeMillis();
        lastWhy = "手动设置";
        return true;
    }

    public static boolean setByCode(int code) {
        String nm = CODES.get(code);
        if (nm == null) return false;
        return setByName(nm);
    }

    public static String current() { return name; }

    /** 端点直接改了维度之后，重挑一次情绪（不改维度，只让 name 跟上）。 */
    public static void repick(String why) {
        clamp();
        pick(why == null ? "外部设置" : why, 0);
    }

    /** 回到基线（端点 {@code {"reset":true}}）。 */
    public static void reset() {
        valence = BASE_V;
        arousal = BASE_A;
        lastUserAt = 0L;
        mood = MOOD_BASE;          // ★ v214
        moodWhy = "";
        name = "calm";
        changes++;
        lastAt = System.currentTimeMillis();
        lastWhy = "重置到基线";
    }

    /** 在设备上跑自检：读 assets 里的两份表，再走纯逻辑断言。 */
    public static String selftestOnDevice(Context ctx) {
        try {
            String a = readAsset(ctx, "emotions.xml");
            String b = readAsset(ctx, "emotions_threshold.xml");
            return selftest(a, b);
        } catch (Throwable t) {
            return "情绪系统自检 0/1 FAIL(1)\n  ✗ 读不到 assets: " + t + "\n";
        }
    }

    /** 夸/骂的词表（**本地、离线**：原厂那套是云端打分，我们没有云）。 */
    static final String[] PRAISE = {"真好", "真棒", "好棒", "厉害", "聪明", "喜欢你",
                                    "乖", "谢谢你", "太棒", "真乖", "好可爱"};
    static final String[] SCOLD = {"笨", "讨厌", "闭嘴", "滚", "烦人", "傻瓜", "真差",
                                   "蠢", "别吵", "烦死"};

    /**
     * 分类一句听到的话：先记"有人说话"，再判夸/骂。
     *
     * <p>★ 为什么放在本地做：原厂的情绪分是**云端** {@code /nlpgetemotion/emotionscore.do}
     * 算的，这台机器离线 ⇒ 那条路根本不可用。这里退化成"关键词表 + 事件"，与本项目
     * 既有的词表式意图引擎是同一套思路（分支顺序即语义、匹配用关键词）。</p>
     */
    public static void noteUtterance(String text) {
        onUserSpoke();
        notePraiseScold(text);
    }

    /**
     * ★ v214：**只判夸/骂**，不重复记"有人说话"。
     *
     * <p>为什么单独留一个入口：唤醒那一刻 {@link AgentService} 已经记过 {@code onUserSpoke}，
     * 而这句话的原文要等到对话真的发生（{@link AiCore#pushDialog}）才拿得到。若那里再调
     * {@link #noteUtterance}，就会把"有人说话"记两次（激活度白抬两次）。夸/骂的判定逻辑
     * 只此一份（{@link #noteUtterance} 也走它），所以两条路不会分叉。</p>
     */
    public static void notePraiseScold(String text) {
        if (text == null || text.isEmpty()) return;
        for (String w : PRAISE) if (text.contains(w)) { onPraise(); return; }
        for (String w : SCOLD) if (text.contains(w)) { onScold(); return; }
    }

    /** 情绪的表情池 ∩ 允许集 —— 待机时用，做到"情绪自由，但只挑轻的"。 */
    public static String[] facePoolWithin(String[] allow) {
        if (allow == null || allow.length == 0) return facePool();
        String[] p = facePool();
        List<String> out = new ArrayList<String>();
        for (String a : p) {
            for (String b : allow) {
                if (a.equals(b)) { out.add(a); break; }
            }
        }
        return out.isEmpty() ? allow : out.toArray(new String[0]);
    }

    /** 当前情绪对应的表情池（待机微动从这里挑，于是"表情"自然跟着"情绪"走）。 */
    public static String[] facePool() { return facesFor(name); }

    public static String json() {
        StringBuilder b = new StringBuilder();
        b.append("{\"name\":\"").append(J.esc(name)).append('"')
         .append(",\"valence\":").append(round(valence))
         .append(",\"arousal\":").append(round(arousal))
         .append(",\"changes\":").append(changes)
         .append(",\"why\":\"").append(J.esc(lastWhy)).append('"')
         // ★ v214 心情层（慢变量）。mood 是数值、mood_label 是**提示词同款**的那个词，
         //   mood_why 只在这里露（不进提示词，免得模型拿"你刚骂我"回嘴）。
         .append(",\"mood\":").append(round(mood))
         .append(",\"mood_label\":\"").append(J.esc(moodLabel())).append('"')
         .append(",\"mood_why\":\"").append(J.esc(moodWhy)).append('"')
         .append(",\"mood_changes\":").append(moodChanges)
         .append(",\"last_ago_ms\":").append(lastAt == 0 ? -1 : (System.currentTimeMillis() - lastAt))
         .append(",\"user_ago_ms\":").append(lastUserAt == 0 ? -1 : (System.currentTimeMillis() - lastUserAt))
         .append(",\"loaded\":").append(loaded)
         .append(",\"load\":\"").append(J.esc(loadNote)).append('"')
         .append(",\"faces\":[");
        String[] f = facePool();
        for (int i = 0; i < f.length; i++) { if (i > 0) b.append(','); b.append('"').append(f[i]).append('"'); }
        b.append("],\"codes\":").append(CODES.size())
         .append(",\"tiers\":").append(TIERS.size());
        return b.append('}').toString();
    }

    private static String round(float f) {
        return String.format(java.util.Locale.US, "%.3f", f);
    }

    // ---------------------------------------------------------------- 离线考卷（build 硬门禁）

    /**
     * 纯静态自检 —— 没有设备、没有 Context、不碰 org.json 也能跑。
     * 断言的是**不变量**（不是"打印一堆看着对的东西"）：
     * 解析出的码数/档数、正面情绪不落到负面、负面不落到正面、每个原厂情绪都有表情可用。
     *
     * <p>★ v214 起还断言**心情层**：回落速率比情绪慢≥5 倍、分档无空档、被夸→好/被骂→差，
     * 以及那条立论性的"走 100 个心跳后情绪已回基线而心情仍留着"。</p>
     *
     * <p>★ 自检会在真机上被 {@code GET /emotionselftest} 调到，而用例会改全局状态
     * ⇒ <b>跑完统一还原</b>，不留副作用（"探针会改设备状态"是本项目踩过的坑）。</p>
     */
    public static String selftest(String emotionsXml, String thrXml) {
        StringBuilder ok = new StringBuilder(), bad = new StringBuilder();
        int nOk = 0;
        Map<Integer, String> codes = parseCodes(emotionsXml);
        Map<String, List<String>> tiers = parseTiers(thrXml);

        // ★ v214：自检会在真机上被 GET /emotionselftest 调到，而断言的用例（被夸/被骂/切换情绪名）
        //   会**改到全局状态** ⇒ 跑完必须还原，否则"跑一次自检"就会顺手改掉机器人的心情和表情
        //   （本项目老坑：探针自己会改设备状态）。这里统一存/还原。
        // ★★ 修（2026-10-11）：decide()/setByName() 读的是**静态表** CODES/TIERS，而纯 JVM 下
        //   这两张表没人 load（load(Context) 才填）⇒ 断言「中性+低激活→calm」「happy 被接受」
        //   在 JVM 里只走到"空表兜底分支"，测的根本不是真逻辑（真机 17 / JVM 15 的差异由此而来）。
        //   解法：把本次 parse 出来的表**临时装进静态字段**，跑完连同其它全局状态一起还原。
        final Map<Integer, String> svCodes = new LinkedHashMap<Integer, String>(CODES);
        final Map<String, List<String>> svTiers = new LinkedHashMap<String, List<String>>(TIERS);
        final boolean svLoaded = loaded;
        CODES.clear(); CODES.putAll(codes);
        TIERS.clear(); TIERS.putAll(tiers);
        loaded = true;
        final String  svName   = name;      final String  svWhy = lastWhy;
        final float   svV = valence, svA = arousal, svMood = mood;
        final String  svMoodWhy = moodWhy;
        final int     svChanges = changes, svMoodChanges = moodChanges;
        final long    svLastAt = lastAt, svLastUserAt = lastUserAt;

        nOk = chk(codes.size() >= 40, "解析出情绪码 " + codes.size() + " 个(≥40)", ok, bad);
        nOk += chk(tiers.size() == 3, "解析出强度档 " + tiers.size() + " 组(=3)", ok, bad);
        nOk += chk(codes.containsKey(10300) && "happy".equals(codes.get(10300)),
                "码 10300 → happy", ok, bad);
        nOk += chk(tiers.containsKey("highest") && tiers.get("highest").contains("excite"),
                "highest 档含 excite", ok, bad);

        // 每个原厂情绪都必须有表情可用（不许出现"情绪有了、脸不知道给哪张"）
        int missing = 0;
        for (String e : new java.util.LinkedHashSet<String>(codes.values())) {
            if (facesFor(e).length == 0) missing++;
        }
        nOk += chk(missing == 0, "原厂 " + codes.size() + " 个码的情绪都有表情可用(缺 " + missing + ")", ok, bad);

        // 极性：高愉悦必须落正面、低愉悦必须落负面
        String hi = decide(0.95f, 0.75f), lo = decide(0.02f, 0.75f);
        nOk += chk(!isNegative(hi), "高愉悦(0.95) 落正面: " + hi, ok, bad);
        nOk += chk(isNegative(lo), "低愉悦(0.02) 落负面: " + lo, ok, bad);
        // 发呆：极低激活 + 中性 → 必须是原厂 lowest 档里的词
        String doze = decide(0.45f, 0.02f);
        List<String> low = tiers.get("lowest");
        nOk += chk(low == null || low.contains(doze), "低激活(0.02) 落 lowest 档: " + doze, ok, bad);
        // ★ v191：中间带必须是 calm（不许"没人惹它它还不高兴"，v190 实测踩过）
        nOk += chk("calm".equals(doze), "中性+低激活 → calm（不是 dissatisfied）: " + doze, ok, bad);
        // 词表外的一律拒绝
        nOk += chk(!setByName("not_an_emotion"), "词表外的情绪名被拒", ok, bad);
        nOk += chk(setByName("happy"), "词表内的情绪名被接受(happy)", ok, bad);

        // ---------------- ★ v214 心情层 ----------------
        // ① 速率关系：心情必须比情绪慢**至少 5 倍**（这是"两层拆开"的立论）
        // ★★ 修（2026-10-11）：0.012f*5f 在 float 下 = 0.060000002，比 0.06f(=0.059999999)
        //   大一点点 ⇒ 原写法 "<= 0.06f" **恒假**（浮点陷阱，真机上也挂、只是被计数缺陷吞了）。
        nOk += chk(MOOD_RATE * 5f <= 0.06f + 1e-4f,
                "心情回落速率比情绪慢≥5倍(" + MOOD_RATE + " vs 0.06)", ok, bad);
        // ② 回落方向：从两端都朝基线走，且不越过基线
        nOk += chk(moodStep(1f) < 1f && moodStep(1f) > MOOD_BASE, "心情从高值落向基线", ok, bad);
        nOk += chk(moodStep(0f) > 0f && moodStep(0f) < MOOD_BASE, "心情从低值升向基线", ok, bad);
        // ③ 分档（含端点）
        nOk += chk("好".equals(moodLabelOf(0.80f)) && "一般".equals(moodLabelOf(0.50f))
                && "差".equals(moodLabelOf(0.20f)), "心情三档 好/一般/差", ok, bad);
        nOk += chk("好".equals(moodLabelOf(MOOD_GOOD)) && "一般".equals(moodLabelOf(MOOD_GOOD - 0.01f))
                && "差".equals(moodLabelOf(MOOD_BAD)) && "一般".equals(moodLabelOf(MOOD_BAD + 0.01f)),
                "心情分档在阈值处无空档/无重叠", ok, bad);
        // ④ 行为：真实事件函数 —— 被夸→心情好、被骂→心情差
        mood = MOOD_BASE; moodWhy = "";
        onPraise();
        boolean upOk = "好".equals(moodLabel());
        mood = MOOD_BASE; moodWhy = "";
        onScold();
        boolean downOk = "差".equals(moodLabel());
        nOk += chk(upOk && downOk, "被夸→心情好 / 被骂→心情差", ok, bad);
        // ⑤ ★ 这条是整套设计的判据：同样走 100 个心跳（≈13 分钟），
        //    情绪早已回基线，心情**还留着** —— 否则"心情"就只是换了个名字的快变量。
        float e100 = 1f, m100 = 1f;
        for (int i = 0; i < 100; i++) {
            e100 += (BASE_V - e100) * 0.06f;      // 情绪（valence 同款速率）
            m100 = moodStep(m100);                // 心情
        }
        nOk += chk(e100 < 0.51f && m100 > 0.60f,
                "100 步后情绪已回基线(" + round(e100) + ")、心情仍留着(" + round(m100) + ")", ok, bad);

        // ★ 还原被断言用例改过的全局状态
        name = svName; lastWhy = svWhy;
        valence = svV; arousal = svA;
        mood = svMood; moodWhy = svMoodWhy;
        changes = svChanges; moodChanges = svMoodChanges;
        lastAt = svLastAt; lastUserAt = svLastUserAt;
        CODES.clear(); CODES.putAll(svCodes);
        TIERS.clear(); TIERS.putAll(svTiers);
        loaded = svLoaded;

        // ★★ 修（2026-10-11）：原来 chk 失败只往 bad 里写一行、**没人给 nBad 记账**
        //   ⇒ 头部恒报 "k/k PASS"、失败项被 if(nBad>0) 挡住不打印 —— 本项目最忌的
        //   「只统计成功 = 假绿灯」。改为按 bad 里的失败条目数（逐行）如实计数。
        int nBad = 0;
        for (int i = 0; i < bad.length(); i++) if (bad.charAt(i) == '\n') nBad++;
        int r = nOk + nBad;
        StringBuilder out = new StringBuilder();
        out.append("情绪系统自检 ").append(nOk).append('/').append(r);
        if (nBad == 0) {
            out.append(" PASS");
        } else {
            out.append(" FAIL(").append(nBad).append(')');
        }
        out.append('\n');
        out.append(ok);
        if (nBad > 0) out.append("失败项:\n").append(bad);
        return out.toString();
    }

    private static int chk(boolean cond, String what, StringBuilder ok, StringBuilder bad) {
        if (cond) { ok.append("  ✓ ").append(what).append('\n'); return 1; }
        bad.append("  ✗ ").append(what).append('\n');
        return 0;
    }
}
