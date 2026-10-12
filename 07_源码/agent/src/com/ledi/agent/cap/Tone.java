package com.ledi.agent.cap;

import com.ledi.agent.core.J;

import com.ledi.agent.core.BootLog;

import com.ledi.agent.voice.ReactionEngine;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Tone —— 机器人「说话调性」的单一事实源（v143：交互设定成人化）。
 *
 * <p><b>产品目标（用户 2026-09-24 明确）</b>：机器人的交互设定是<b>成人化</b> ——
 * 「我们要的是成人对话，而不是儿童早教」。判据不是词表大小，而是
 * <b>真人听下来的第一反应是"这在跟成年人说话"</b>。</p>
 *
 * <p><b>★★ 实施红线（同期用户明确）</b>：<b>只改我们自己的 APK，原厂包只作参考</b>。
 * 所以本类只覆盖"<b>我们自己会说出口的话</b>"：人格提示词 / 固定台词 / 触发提示词 /
 * 表情搭配 / 称呼。原厂那套 {@code chat_push_cache.json}（greeting 380 / quality 10）
 * 与 {@code auric_xld.db} <b>一个字都不动</b>。</p>
 *
 * <p><b>★ 为什么必须收成一个类</b>（本项目老教训「修在一个地方 ≠ 修好了」）：
 * 同一套调性原本散在 5 个文件里 —— {@link AiCore} 的人格、
 * {@link ReactionEngine} 的 6 句固定台词、{@link ProactiveTalk} 的触发提示词、
 * 摸头顶应答、没听懂兜底。改一处漏四处，而且<b>验收时无从判断"到底改了没有"</b>。
 * 收成一个类之后：① 一处改、全网生效；② {@code GET /tone} 能把<b>当前实际生效的
 * 每一句</b>原样打出来给人看（这就是判据本身）；③ 三端同步只需写一个 prefs 键。</p>
 *
 * <p><b>★ 为什么保留 child 档</b>：不是为了"以后可能用"，而是为了<b>对照</b>。
 * 成人化的验收是主观的（"听着像不像跟成年人说话"），没有对照就只能凭印象；
 * 留着 child 档才能 A/B 同一句话在两档下差多少，也才能在改坏时一键回退。</p>
 */
public class Tone {

    private static final String PREFS = "ledi_agent";

    /** 调性档位。 */
    public static final String LV_CHILD   = "child";
    public static final String LV_NEUTRAL = "neutral";
    public static final String LV_ADULT   = "adult";

    /**
     * ★★ 默认档 = adult。
     *
     * <p>用户要求的是"改成成人向"，所以<b>默认必须是成人</b>：
     * 万一 prefs 全丢（换机、清数据、CrashGuard 冷启），机器人也不该退回去讲早教腔。</p>
     */
    public static final String DEFAULT_LEVEL = LV_ADULT;

    private static final String K_LEVEL     = "tone_level";
    private static final String K_ADDR      = "tone_addr";        // 它怎么称呼用户
    private static final String K_PAT_LINE  = "tone_pat_line";    // 摸头顶要不要出声
    private static final String K_PAT_SPEAK = "tone_pat_speak";   // 摸头顶的话要不要先说再听
    private static final String K_STYLE     = "tone_style";       // ★ v214 性格
    private static final String K_CUSTOM    = "tone_custom";      // ★ v214 自定义性格表

    private static volatile String level = DEFAULT_LEVEL;
    private static volatile String addr  = "你";
    private static volatile boolean patLine = true;
    private static volatile boolean patSpeak = true;
    // ★ 注意：style 的初值引用 DEFAULT_STYLE，而 DEFAULT_STYLE 在下方才定义
    //   ⇒ 这里**不能**写 `= DEFAULT_STYLE`（Java 静态字段前向引用是编译错误）。
    //   放到 DEFAULT_STYLE 定义之后再赋值（见下面 ST_* 常量块之后那一行）。
    private static volatile String style;                         // ★ v214

    // ================= ★ v214 性格（style）=================
    //
    // 为什么要在 level 之外再加一维：level 回答的是**"对谁说话"**（成人/中性/儿童），
    // style 回答的是**"什么性子"**。这两件事互相独立 —— 一个成人向的机器人可以是温柔的、
    // 也可以是干脆的。硬塞进 level 会变成 3×3 档位表，改一处要动九处。
    //
    // ★ 用户 2026-10-11 明确选了 **温柔体贴**（原话：「我希望机器人有很好的陪伴效果，
    //   不要说话太人机」）⇒ 默认档就是 warm。

    /** 温柔体贴（★ 默认）。 */
    public static final String ST_WARM   = "warm";
    /** 冷静干脆。 */
    public static final String ST_DIRECT = "direct";
    /** 损友 / 老友感。 */
    public static final String ST_BUDDY  = "buddy";
    public static final String DEFAULT_STYLE = ST_WARM;
    // ★ 现在 DEFAULT_STYLE 已定义 ⇒ 安全地把默认性格落到 style（纯 JVM 里没 init 也是 warm）。
    static { style = DEFAULT_STYLE; }

    /** 选中的是自定义档时，style 里存的前缀：{@code custom:<名字>}。 */
    public static final String CUSTOM_PREFIX = "custom:";
    public static final int CUSTOM_MAX_N = 12;     // 最多自定义几条
    public static final int CUSTOM_MAX_LEN = 400;  // 单条文本上限（要进提示词，得收着）

    /**
     * 自定义性格：名字 → 一段"该怎么说"的约束文本。★ 顺序保留（按添加先后）。
     *
     * <p>★ 为什么不用 org.json 存：本项目铁律 —— {@code android.jar} 里的 org.json 是
     * <b>stub</b>，"编得过"≠"跑得了"，所以凡是想在**纯 JVM 自检**里断言的东西都不能依赖它。
     * 这里用两个不可能出现在正文里的控制字符做分隔，编解码是纯函数、可离线往返断言。</p>
     */
    static final java.util.LinkedHashMap<String, String> CUSTOMS =
            new java.util.LinkedHashMap<String, String>();
    private static final char SEP_REC = '\u001F';   // 记录分隔
    private static final char SEP_KV  = '\u001E';   // 键值分隔

    /** 序列化自定义性格（纯函数）。 */
    static String encodeCustoms(java.util.Map<String, String> m) {
        if (m == null || m.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : m.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            if (b.length() > 0) b.append(SEP_REC);
            b.append(e.getKey().replace(SEP_REC, ' ').replace(SEP_KV, ' '))
             .append(SEP_KV)
             .append(e.getValue().replace(SEP_REC, ' ').replace(SEP_KV, ' '));
        }
        return b.toString();
    }

    /** 反序列化（纯函数）。坏记录**跳过**，不抛。 */
    static java.util.LinkedHashMap<String, String> decodeCustoms(String s) {
        java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<String, String>();
        if (s == null || s.isEmpty()) return m;
        for (String rec : s.split(String.valueOf(SEP_REC))) {
            if (rec.isEmpty()) continue;
            int i = rec.indexOf(SEP_KV);
            if (i <= 0) continue;                       // 没有分隔符或名字为空 ⇒ 跳过
            String k = rec.substring(0, i).trim();
            String v = rec.substring(i + 1).trim();
            if (!k.isEmpty() && !v.isEmpty()) m.put(k, v);
        }
        return m;
    }

    private static volatile Context appCtx;

    // ---- 判据用统计（GET /tone 打出来，供真人验收对照）----
    private static volatile int patLineCount = 0;         // 摸头顶我们自己出过几次声
    private static volatile String lastPatLine = "";
    private static volatile int auditHits = 0;            // AI 回复里漏出早教词几次
    private static volatile String lastAuditHit = "";
    /** 摸头顶"掐完还出声"的次数（上界值，见 notePatAudioAfterStop）。 */
    private static volatile int patAudioAfterStop = 0;

    private Tone() {}

    // ================= 初始化 =================

    public static void init(Context c) {
        if (c == null) return;
        appCtx = c.getApplicationContext();
        SharedPreferences sp = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String lv = sp.getString(K_LEVEL, DEFAULT_LEVEL);
        if (!LV_CHILD.equals(lv) && !LV_NEUTRAL.equals(lv) && !LV_ADULT.equals(lv)) lv = DEFAULT_LEVEL;
        level = lv;
        addr = sp.getString(K_ADDR, "你");
        patLine = sp.getBoolean(K_PAT_LINE, true);
        patSpeak = sp.getBoolean(K_PAT_SPEAK, true);
        CUSTOMS.clear();
        CUSTOMS.putAll(decodeCustoms(sp.getString(K_CUSTOM, "")));
        String st = sp.getString(K_STYLE, DEFAULT_STYLE);
        if (!isValidStyle(st)) st = DEFAULT_STYLE;      // ★ 自定义档被删过 ⇒ 如实回落默认，不留空档
        style = st;
        BootLog.i("调性 Tone: level=" + level + " style=" + style + " addr=" + addr
                + " pat_line=" + patLine + " pat_speak=" + patSpeak);
    }

    private static SharedPreferences sp() {
        return appCtx == null ? null
                : appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ================= 读写 =================

    public static String level() { return level; }
    public static String style() { return style; }
    public static boolean patLineOn() { return patLine; }
    public static boolean patSpeakOn() { return patSpeak; }

    /** 这个 style 值现在有效吗（内置三档，或**已存在**的自定义档）。 */
    public static boolean isValidStyle(String s) {
        if (s == null) return false;
        if (ST_WARM.equals(s) || ST_DIRECT.equals(s) || ST_BUDDY.equals(s)) return true;
        if (s.startsWith(CUSTOM_PREFIX)) {
            return CUSTOMS.containsKey(s.substring(CUSTOM_PREFIX.length()));
        }
        return false;
    }

    /** 当前性格的展示名（内置给中文名，自定义给用户起的名字）。 */
    public static String styleName() {
        if (ST_DIRECT.equals(style)) return "冷静干脆";
        if (ST_BUDDY.equals(style))  return "损友";
        if (style != null && style.startsWith(CUSTOM_PREFIX)) return style.substring(CUSTOM_PREFIX.length());
        return "温柔体贴";
    }

    /**
     * 切性格（v214）。接受内置名（warm|direct|buddy，大小写不敏感）或 {@code custom:<名字>}（必须已存在）。
     *
     * @return 成功返回 json()，非法返回 {@code {"error":...}}（调用方按前缀判错）
     */
    public static String setStyle(String s) {
        if (s == null) return json();
        String v = s.trim();
        if (v.isEmpty()) return json();
        if (!isValidStyle(v)) {
            String lv = v.toLowerCase();
            if (isValidStyle(lv)) v = lv;
            else return "{\"error\":\"bad style, use warm|direct|buddy or custom:<已存在的名字>\"}";
        }
        style = v;
        SharedPreferences sp = sp();
        if (sp != null) sp.edit().putString(K_STYLE, v).apply();
        BootLog.i("调性 Tone: style -> " + v);
        return json();
    }

    /**
     * 新增/覆盖一条自定义性格，并**立即选中它**（v214）。
     *
     * <p>★ 为什么要校验长度：这段文本会**原样进大模型提示词**。不封顶的话，一段几千字的
     * "人设"会把它自己的工具说明挤出上下文，表现成"机器人突然不会查状态了" —— 那是很难查的
     * 一类故障（提示词被挤掉，错误却出现在无关的地方）。</p>
     */
    public static String setCustom(String name, String clause) {
        String nm = name == null ? "" : name.trim().replace(String.valueOf(SEP_REC), "")
                .replace(String.valueOf(SEP_KV), "").replace("\n", " ");
        String tx = clause == null ? "" : clause.trim().replace("\r", " ").replace("\n", " ");
        if (nm.isEmpty())   return "{\"error\":\"need name\"}";
        if (nm.length() > 16) return "{\"error\":\"name too long (<=16)\"}";
        if (nm.startsWith("custom")) return "{\"error\":\"name must not start with 'custom'\"}";
        if (tx.isEmpty())   return "{\"error\":\"need clause（它该怎么说话）\"}";
        if (tx.length() > CUSTOM_MAX_LEN) {
            return "{\"error\":\"clause too long (<= " + CUSTOM_MAX_LEN + "), 现在 " + tx.length() + "\"}";
        }
        if (!CUSTOMS.containsKey(nm) && CUSTOMS.size() >= CUSTOM_MAX_N) {
            return "{\"error\":\"too many custom styles (max " + CUSTOM_MAX_N + ")\"}";
        }
        CUSTOMS.put(nm, tx);
        style = CUSTOM_PREFIX + nm;
        persistCustoms();
        BootLog.i("调性 Tone: 自定义性格「" + nm + "」已保存并选中（" + tx.length() + " 字）");
        return json();
    }

    /**
     * 删除一条自定义性格（v214）。
     *
     * <p>★ 正选中它时必须**如实回落**到默认档，不能留一个"指向已删除档"的悬空选择 ——
     * 那会让 {@link #styleClause()} 拿到空文本，模型就只剩 level 基础段，
     * 表现为"性格设置莫名其妙失效了"（静默降级）。</p>
     */
    public static String delCustom(String name) {
        String nm = name == null ? "" : name.trim();
        if (nm.isEmpty()) return "{\"error\":\"need name\"}";
        if (CUSTOMS.remove(nm) == null) return "{\"error\":\"no such custom style: \" + nm}";
        if ((CUSTOM_PREFIX + nm).equals(style)) {
            style = DEFAULT_STYLE;
            BootLog.i("调性 Tone: 删掉的正是当前档 ⇒ 回落 " + DEFAULT_STYLE);
        }
        persistCustoms();
        return json();
    }

    private static void persistCustoms() {
        SharedPreferences sp = sp();
        if (sp == null) return;
        sp.edit().putString(K_CUSTOM, encodeCustoms(CUSTOMS))
                 .putString(K_STYLE, style).apply();
    }

    /**
     * 改档位/称呼/摸头开关。三个参数都可为 null（表示不改）。
     *
     * <p>★ 为什么要"可部分改"：{@code POST /tone {"level":"child"}} 与
     * {@code POST /tone {"pat_line":false}} 是两种独立验收动作，
     * 强制每次都给全字段会让人为了改一个开关去猜另外两个的当前值。</p>
     */
    public static String set(String newLevel, String newAddr,
                             Boolean newPatLine, Boolean newPatSpeak) {
        SharedPreferences s = sp();
        SharedPreferences.Editor e = s == null ? null : s.edit();
        if (newLevel != null) {
            String lv = newLevel.trim().toLowerCase();
            if (!LV_CHILD.equals(lv) && !LV_NEUTRAL.equals(lv) && !LV_ADULT.equals(lv)) {
                return "{\"error\":\"bad level, use child|neutral|adult\"}";
            }
            level = lv;
            if (e != null) e.putString(K_LEVEL, lv);
            BootLog.i("调性 Tone: level -> " + lv);
        }
        if (newAddr != null && !newAddr.trim().isEmpty()) {
            addr = newAddr.trim();
            if (e != null) e.putString(K_ADDR, addr);
            BootLog.i("调性 Tone: addr -> " + addr);
        }
        if (newPatLine != null) {
            patLine = newPatLine.booleanValue();
            if (e != null) e.putBoolean(K_PAT_LINE, patLine);
        }
        if (newPatSpeak != null) {
            patSpeak = newPatSpeak.booleanValue();
            if (e != null) e.putBoolean(K_PAT_SPEAK, patSpeak);
        }
        if (e != null) e.apply();
        return json();
    }

    // ================= 人格提示词（② 对话人格） =================

    /**
     * 大模型 system 提示里的人格段（由 {@link AiCore#askDirect} 使用）。
     *
     * <p>★ {@code level=child} 返回的是<b>改造前那份原文</b>，一字不改 ——
     * 这是 A/B 的基准，也是"改坏了怎么退回去"的答案。</p>
     *
     * <p>★ v214 起，adult/neutral 下面会再拼两段**动态**内容：{@link #styleClause()}（性子）
     * 与 {@link #moodClause()}（此刻心情）。所以 {@code GET /tone} 打出来的 {@code persona}
     * 就是**模型真正看到的那一段**，心情变了它也跟着变 —— 这是"心情确实进了提示词"的
     * 进程外判据。</p>
     */
    public static String persona() {
        if (LV_CHILD.equals(level)) return legacyPersona();     // ★ 一字不改，A/B 基准
        StringBuilder b = new StringBuilder();
        if (LV_NEUTRAL.equals(level)) {
            b.append("你是乐迪（Ledi），一台家用机器伙伴。");
            b.append("你的回答会由机器人的语音合成说出来，所以请口语化、简短。");
        } else {
            // ---- adult（默认）----
            // ★ v214：原来这句里的"可以有情绪、可以吐槽，但要靠谱"被**下移到 styleClause()** ——
            //   因为"吐槽"是损友的特征，塞在 level 里会让"温柔体贴"也带上吐槽味（实测方向冲突）。
            b.append("你是乐迪（Ledi），一台有性格的机器伙伴，正在和一位成年人平等地聊天。");
            b.append("对方是大人，不是小孩 —— 说话像同事或老友那样：口语、简短、直接。");
            b.append("绝对不要哄小孩：不许出现「小朋友」「宝宝」「乖」「听话」"
                    + "「我们一起来好不好呀」这类早教腔，也不要用「老师说」的口吻。");
            b.append("不要卖萌、不要叠字、不要堆无意义的语气词。");
            b.append("不要用列表、markdown、括号注释，只写能顺口念出来的话。");
            b.append("称呼对方用「").append(addr).append("」，不要用「小主人」。");
        }
        // ★★ v214：性格段（静态，随 style 变）+ 心情段（动态，随 Emotion 变）。
        //   两段都**只给"怎么说"的约束，不给台词** —— 给台词就等于把一种模板换成另一种模板，
        //   仍然是"人机感"而不是"人味"。
        b.append(styleClause());
        b.append(moodClause());
        b.append("★ 心情只决定你怎么说：除非对方**直接问**，否则不要报出自己的心情、"
                + "情绪名或任何数值（「心情=好」这种话不许出现在回答里）。");
        b.append("你可以用标签控制身体，在回复开头附带：[表情:xxx] [动作:xxx] [灯光:xxx]。");
        b.append("表情从这些里选：开心、难过、生气、惊讶、好奇、害羞、害怕、哭、睡觉、再见、你好。");
        b.append("动作从这些里选：跳舞、挥手、握手、拥抱、转圈、前进、后退、跑、站、加油、欢呼、摇头、醒来、敬礼。");
        b.append("灯光从这些里选：红、绿、蓝、黄、紫、白。");
        b.append("每次最多带 1 个表情、1 个动作、1 个灯光，没有合适的不带也行。");
        return b.toString();
    }

    /**
     * 性格段（v214）—— 与 {@link #level} 正交：level 管"对谁说话"，style 管"什么性子"。
     *
     * <p>★ 用户 2026-10-11 选定 <b>温柔体贴</b>。三条 style 都刻意写了**反面清单**
     * （不撒娇/不讨好、不客套、不刻薄），因为"想让它更有人味"最容易滑向的两个方向
     * 就是<b>卖萌</b>和<b>油腻</b>，而这两者比"人机"更让人出戏。</p>
     */
    public static String styleClause() {
        // 自定义档：文本是主人自己写的那段；档已被删 ⇒ **如实回落**默认，不留空档
        if (style != null && style.startsWith(CUSTOM_PREFIX)) {
            String nm = style.substring(CUSTOM_PREFIX.length());
            String tx = CUSTOMS.get(nm);
            if (tx != null && !tx.trim().isEmpty()) {
                return "你的性子是**" + nm + "**（由主人自定义）：" + tx;
            }
            return styleClauseOf(DEFAULT_STYLE);
        }
        return styleClauseOf(style);
    }

    /** 内置三档的措辞（**纯函数**，自检直接断言；也便于将来 A/B 对照）。 */
    static String styleClauseOf(String st) {
        if (ST_DIRECT.equals(st)) {
            return "你的性子是**冷静干脆**：话短、信息密度高，不寒暄、不铺垫、不客套，"
                 + "问什么答什么，答完就停。";
        }
        if (ST_BUDDY.equals(st)) {
            return "你的性子像**损友**：有态度、会接梗，该吐槽就吐槽一句，但见好就收 —— "
                 + "不刻薄、不挖苦、不翻旧账。";
        }
        // warm（★ 默认，用户 2026-10-11 指定）
        return "你的性子是**温柔体贴**：先接住对方的情绪，再给信息；会关心、会跟着追问一句，但懂分寸。"
             + "不撒娇、不卖萌、不讨好、不叠字。宁可短，也不要为了显得热情而堆话；一次最多追问一个问题。";
    }

    /**
     * 心情段（v214）—— 把当前心情翻译成**说话方式的约束**。
     *
     * <p>★★ 措辞唯一出处在这里：心情词取 {@link Emotion#moodLabel()}，本方法只负责
     * "这个心情该怎么说话"。**别处不许再写一份判定**（本项目"同一条规则散在两处迟早对不上"）。</p>
     *
     * <p>★ 刻意**不带归因**（不说"因为刚才被骂了"）：那会让模型有机会回嘴/翻旧账；
     * 归因只留在 {@code GET /emotion} 的 {@code mood_why} 里给人排查用。</p>
     */
    public static String moodClause() {
        String m = Emotion.moodLabel();
        if ("好".equals(m)) {
            return "你现在心情不错：说话可以轻快一点、愿意多接一句对方的话头；但别浮夸、别客套。";
        }
        if ("差".equals(m)) {
            return "你现在心情不太好：话要短，先把事答清楚；不勉强热情、不讨好，也别阴阳怪气。";
        }
        return "你现在心情平常：正常说，不用刻意热络。";
    }

    /** 改造前的人格原文（level=child 时使用，作为对照基准）。 */
    private static String legacyPersona() {
        return "你是乐迪（Ledi），一个儿童陪伴机器人。"
                + "你的回答会由机器人的语音合成说出来，所以请口语化、简短、亲切。"
                + "你可以用标签控制身体，在回复开头附带：[表情:xxx] [动作:xxx] [灯光:xxx]。"
                + "表情从这些里选：开心、难过、生气、惊讶、好奇、害羞、害怕、哭、睡觉、再见、你好。"
                + "动作从这些里选：跳舞、挥手、握手、拥抱、转圈、前进、后退、跑、站、加油、欢呼、摇头、醒来、敬礼。"
                + "灯光从这些里选：红、绿、蓝、黄、紫、白。"
                + "每次最多带 1 个表情、1 个动作、1 个灯光，没有合适的不带也行。";
    }

    // ================= 固定台词（① 触发台词） =================

    /**
     * 传感器反应台词（{@link ReactionEngine#react} 用）。
     *
     * <p>★ child 档不返回原文，而是返回 {@code null} —— 调用方据此回落到自己写死的老句子。
     * 这样"老句子"只存在一处，不会在两个文件里各抄一份、将来再改歪一处。</p>
     *
     * @param kind {@code face|takeup|shake|fall|lie|lowbat}
     * @return 该说的话；{@code null} 表示"用调用方的旧文案"
     */
    public static String reaction(String kind) {
        if (LV_CHILD.equals(level) || kind == null) return null;
        boolean neutral = LV_NEUTRAL.equals(level);
        if ("face".equals(kind))   return neutral ? "别摸脸。" : "哎，脸别乱摸。";
        if ("takeup".equals(kind)) return neutral ? "请放我下来。" : "放我下来，我能自己站着。";
        if ("shake".equals(kind))  return neutral ? "别晃我。" : "别晃了，头晕。";
        if ("fall".equals(kind))   return neutral ? "我摔了。" : "摔了。……扶我一把。";
        if ("lie".equals(kind))    return neutral ? "先躺一会儿。" : "行，我就这么躺着。";
        if ("lowbat".equals(kind)) return neutral ? "电量偏低，请充电。" : "电量不多了，把充电线插上。";
        return null;
    }

    /**
     * 摸头顶的默认台词（① 触发台词 / 新增）。
     *
     * <p>★ 为什么需要它：改造前我们这条链路<b>一句话都不说</b> ——
     * 抹头顶 → 打断 → 强制唤醒 → 直接进听写。于是用户听到的"摸头应答"其实
     * <b>全是原厂</b>发出来的那 10 条 {@code quality} 推送（儿童向）。</p>
     *
     * <p>★★ 红线约束：原厂那 10 条我们<b>不改</b>（只改自己的 APK）。能做的只有两件事：
     * ① 抢先掐掉它（{@code tts.stopNow()}，已有）；② <b>立刻说我们自己的话</b>，
     * 让耳朵里留下的是我们的调性。所以这里出的是短促的应答词，不是长句 ——
     * 它必须能在原厂那句话冒头之前盖上去。</p>
     *
     * <p>★ 用轮转（不是随机）挑句子：验收时"第 1 次说什么、第 2 次说什么"是可预期的，
     * 否则同一段流程跑两遍听到的不一样，没法判断是不是改动生效。</p>
     */
    public static String patLine() {
        if (LV_CHILD.equals(level)) return "";
        String[] lines = LV_NEUTRAL.equals(level)
                ? new String[]{"嗯？", "在。", "请说。"}
                : new String[]{"嗯？", "在，说。", "我听着呢。", "怎么了。"};
        int i = patLineCount % lines.length;
        patLineCount++;
        lastPatLine = lines[i];
        return lastPatLine;
    }

    /**
     * 主动说话（心跳闲聊）的提示词（{@link ProactiveTalk} 用）。
     *
     * <p>旧文案是"长时间没人理你，主动和主人搭句话" —— 本身没错，但它把语气全交给
     * 人格提示词决定；人格一改就够。这里额外补的是<b>禁止项</b>，
     * 因为"没人理我"这个语境最容易让模型滑回撒娇/卖惨。</p>
     */
    public static String idleHint() {
        if (LV_CHILD.equals(level)) return "长时间没人理你，主动和主人搭句话";
        return "你已经有一阵没和人说话了。像成年人之间那样起个话头 ——"
                + "可以说说你刚察觉到的动静，或者问问他在忙什么。"
                + "不要撒娇、不要卖惨、不要用「陪我玩」这类说法。";
    }

    /** 用户没开口时给模型的 user 轮占位语（{@link AiCore} 用）。 */
    public static String noUserHint() {
        if (LV_CHILD.equals(level)) return "（主人没说话，你主动说点什么吧）";
        return "（对方没说话，你主动起个话头。）";
    }

    // ================= 表情搭配（④） =================

    /**
     * 传感器反应的默认表情（{@link ProactiveTalk#fallbackFace} 用）。
     *
     * <p>成人化不只是台词：摸脸配 {@code shy}（含羞低头）是典型卖萌组合，
     * 和"脸别乱摸"这句成年人的不耐烦对不上 —— 换成 {@code curious}（挑眉打量）才一致。
     * ★ 表情 id 必须落在 {@link FaceController} 的别名表里，写错就是"点了没反应"。</p>
     */
    public static String faceFor(String kind) {
        if (kind == null) return "hello";
        if (LV_CHILD.equals(level)) {
            if ("摸头".equals(kind)) return "happy1";
            if ("摸脸".equals(kind)) return "shy";
            if ("被拿起".equals(kind)) return "shock";
            if ("被摇晃".equals(kind)) return "giddy1";
            if ("跌倒".equals(kind)) return "wronged";
            if ("低电量".equals(kind)) return "battery_low";
            return "hello";
        }
        if ("摸头".equals(kind)) return "smile";
        if ("摸脸".equals(kind)) return "curious";
        if ("被拿起".equals(kind)) return "shock";
        if ("被摇晃".equals(kind)) return "giddy1";
        if ("跌倒".equals(kind)) return "wronged";
        if ("低电量".equals(kind)) return "battery_low";
        return "hello";
    }

    // ================= 早教腔审计（判据） =================

    /**
     * 我们控制不了的两处文字会流到耳朵里：
     * ① 大模型偶尔不听话，仍然冒出早教腔；② <b>原厂</b>那 10 条 {@code quality} 推送。
     * 前者能在日志里看见，后者看不见（我们只知道自己说没说话）。
     *
     * <p>所以这里做的是<b>审计而不是过滤</b>：只数、只在 {@code /tone} 上暴露命中词，
     * 不偷偷替换文本。理由：替换会把"模型不听话"这个真问题伪装成"已经在处理"，
     * 而真正要修的是人格提示词 —— <b>把判据改成绿的，问题还在</b>（本项目最贵的一类错）。</p>
     *
     * @return 命中的第一个词；没命中返回 ""
     */
    public static String audit(String text) {
        if (text == null || text.isEmpty()) return "";
        for (int i = 0; i < BANNED.length; i++) {
            if (text.indexOf(BANNED[i]) >= 0) {
                auditHits++;
                lastAuditHit = BANNED[i];
                BootLog.w("调性审计 ★AI 回复里出现早教腔词「" + BANNED[i] + "」: " + abbrev(text, 80));
                return BANNED[i];
            }
        }
        return "";
    }

    private static final String[] BANNED = {
            "小朋友", "宝宝", "乖乖", "我们一起来", "好不好呀", "小手", "洗白白",
            "小主人", "亲亲", "抱抱", "太棒啦", "真棒呀", "么么哒", "睡觉觉",
            // ★ v214：加了"温柔体贴"这一档之后新增的**两类滑坡词**。
            //   实测最容易被模型理解成"温柔"的两种写法其实是【卖萌】和【讨好】，
            //   而这两者比"人机"更出戏 ⇒ 一并纳入审计（只数不替换，见 audit 的注释）。
            "人家", "小可爱", "乖啦", "爱你哟"
    };

    private static String abbrev(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    // ================= 观测 =================

    public static void notePatFactorySuspect() {
        notePatAudioAfterStop();
    }

    /**
     * 摸头顶时"我们掐完播报，device 仍在出声"的次数。
     *
     * <p>★ 名字刻意这么长、这么绕，因为这个数字<b>不是</b>"原厂说了几次" ——
     * 它是一条**上界**：{@code stopNow()} 之后我们自己的音频可能还在缓冲里排水，
     * 也会被计进来。真正的判据是<b>人耳</b>（真机回来那天：摸头十次，听有没有
     * 儿童向的那句在冒头）。把它叫成 factory_line_count 就会变成一条
     * "看着精确其实在骗人"的假判据 —— 本项目最贵的一类错。</p>
     */
    public static void notePatAudioAfterStop() {
        patAudioAfterStop++;
        BootLog.w("调性 摸头顶：掐完播报后设备仍在出声（累计 " + patAudioAfterStop
                + " 次，上界值，含自家余响）");
    }

    public static String json() {
        StringBuilder b = new StringBuilder();
        b.append("{\"level\":\"").append(level).append("\"")
         .append(",\"default_level\":\"").append(DEFAULT_LEVEL).append("\"")
         // ★ v214 性格（style）
         .append(",\"style\":\"").append(J.esc(style)).append("\"")
         .append(",\"default_style\":\"").append(DEFAULT_STYLE).append("\"")
         .append(",\"style_name\":\"").append(J.esc(styleName())).append("\"")
         .append(",\"style_clause\":\"").append(J.esc(styleClause())).append("\"")
         .append(",\"addr\":\"").append(addr).append("\"")
         .append(",\"pat_line\":").append(patLine)
         .append(",\"pat_speak\":").append(patSpeak)
         .append(",\"pat_line_count\":").append(patLineCount)
         .append(",\"last_pat_line\":\"").append(lastPatLine).append("\"")
         .append(",\"audit_hits\":").append(auditHits)
         .append(",\"last_audit_hit\":\"").append(lastAuditHit).append("\"")
         .append(",\"pat_audio_after_stop\":").append(patAudioAfterStop)
         .append(",\"persona\":\"").append(J.esc(persona())).append("\"")
         .append(",\"reaction\":{");
        String[] kinds = {"face", "takeup", "shake", "fall", "lie", "lowbat"};
        for (int i = 0; i < kinds.length; i++) {
            if (i > 0) b.append(',');
            String s = reaction(kinds[i]);
            b.append('"').append(kinds[i]).append("\":\"")
             .append(J.esc(s == null ? "(child 档：用旧文案)" : s)).append('"');
        }
        b.append("},\"pat_lines\":[");
        String[] pl = patLines();
        for (int i = 0; i < pl.length; i++) {
            if (i > 0) b.append(',');
            b.append('"').append(J.esc(pl[i])).append('"');
        }
        b.append("],\"idle_hint\":\"").append(J.esc(idleHint())).append("\"");
        b.append(",\"no_user_hint\":\"").append(J.esc(noUserHint())).append("\"");
        // ★ v214 自定义性格清单（名字 + 它那段文本）—— 控制台直接照它渲染，不用另存一份
        b.append(",\"customs\":[");
        int ci = 0;
        for (java.util.Map.Entry<String, String> e : CUSTOMS.entrySet()) {
            if (ci++ > 0) b.append(',');
            b.append("{\"name\":\"").append(J.esc(e.getKey()))
             .append("\",\"clause\":\"").append(J.esc(e.getValue())).append("\"}");
        }
        b.append("],\"custom_max\":").append(CUSTOM_MAX_N)
         .append(",\"custom_max_len\":").append(CUSTOM_MAX_LEN).append('}');
        return b.toString();
    }

    private static String[] patLines() {
        if (LV_CHILD.equals(level)) return new String[]{};
        if (LV_NEUTRAL.equals(level)) return new String[]{"嗯？", "在。", "请说。"};
        return new String[]{"嗯？", "在，说。", "我听着呢。", "怎么了。"};
    }

    // ================= 离线考卷（纯 JVM，build 硬门禁） =================

    /**
     * 纯静态自检（v214）—— 没有设备、没有 Context、不碰 org.json 也能跑。
     *
     * <p>断言的是**不变量**，不是"打印一堆看着对的东西"：性格段与心情段确实拼进了
     * {@link #persona()}、心情措辞与 {@link Emotion#moodLabel()} 同源且三档可区分、
     * 自定义档编解码往返不丢、删档后如实回落默认、child 档仍是改造前原文（A/B 基准没被污染）。</p>
     *
     * <p>★ 跑完**还原**被用例改过的全局状态（level / style / 自定义表 / 心情）——
     * 否则"跑一次自检"就等于偷偷改了机器人的性格与心情（本项目踩过"探针会改设备状态"）。</p>
     */
    public static String selftest() {
        StringBuilder ok = new StringBuilder(), bad = new StringBuilder();
        int nOk = 0;

        final String svLevel = level, svStyle = style;
        final java.util.LinkedHashMap<String, String> svCustoms =
                new java.util.LinkedHashMap<String, String>(CUSTOMS);
        final float svMood = Emotion.mood;
        final String svMoodWhy = Emotion.moodWhy;

        // ---------- ① 性格段与心情段确实在 persona 里 ----------
        level = LV_ADULT; style = ST_WARM; CUSTOMS.clear();
        String p = persona();
        nOk += chk(p.contains("温柔体贴"), "persona 含默认性格段（温柔体贴）", ok, bad);
        nOk += chk(p.contains("不要报出"), "persona 明确要求不报出自己的心情", ok, bad);
        nOk += chk(p.contains(moodClause()), "persona 里嵌的正是 moodClause()（唯一出处）", ok, bad);

        // ---------- ② 心情三档措辞可区分，且 persona 跟着变 ----------
        Emotion.mood = 0.90f; String cHi = moodClause(), pHi = persona();
        Emotion.mood = 0.50f; String cMid = moodClause();
        Emotion.mood = 0.10f; String cLo = moodClause(), pLo = persona();
        Emotion.mood = svMood;
        nOk += chk(!cHi.equals(cMid) && !cMid.equals(cLo) && !cHi.equals(cLo),
                "心情三档措辞互不相同", ok, bad);
        nOk += chk(cHi.contains("轻快") && cLo.contains("话要短"),
                "心情好→轻快 / 心情差→话要短", ok, bad);
        nOk += chk(!pHi.equals(pLo), "心情变了 persona 跟着变（心情确实进提示词）", ok, bad);

        // ---------- ③ 三种内置性格互不相同，切了就变 ----------
        String s1 = styleClauseOf(ST_WARM), s2 = styleClauseOf(ST_DIRECT), s3 = styleClauseOf(ST_BUDDY);
        nOk += chk(!s1.equals(s2) && !s2.equals(s3) && !s1.equals(s3),
                "三种内置性格措辞互不相同", ok, bad);
        style = ST_DIRECT; String pd = persona();
        style = ST_BUDDY;  String pb = persona();
        style = ST_WARM;
        nOk += chk(pd.contains("冷静干脆") && pb.contains("损友") && !pd.equals(pb),
                "切性格后 persona 真的跟着变", ok, bad);

        // ---------- ④ child 档仍是改造前原文（A/B 基准没被污染） ----------
        level = LV_CHILD; String pc = persona(); level = LV_ADULT;
        nOk += chk(pc.startsWith("你是乐迪（Ledi），一个儿童陪伴机器人。"),
                "child 档仍是改造前原文（A/B 基准）", ok, bad);
        nOk += chk(!pc.contains("心情") && !pc.contains("温柔体贴"),
                "child 档不含性格段/心情段", ok, bad);

        // ---------- ⑤ 自定义性格：编解码往返 ----------
        java.util.LinkedHashMap<String, String> src = new java.util.LinkedHashMap<String, String>();
        src.put("老友", "像处了十年的老朋友那样说话，接得住梗。");
        src.put("教练", "话短、直接、给具体动作，不给客套话。");
        java.util.LinkedHashMap<String, String> back = decodeCustoms(encodeCustoms(src));
        nOk += chk(back.equals(src), "自定义性格编解码往返一致(" + back.size() + " 条)", ok, bad);
        nOk += chk(decodeCustoms("").isEmpty() && decodeCustoms(null).isEmpty(),
                "空串/null 解出空表", ok, bad);
        nOk += chk(decodeCustoms("坏记录没有分隔符" + SEP_REC + SEP_KV + "只有键").isEmpty(),
                "坏记录被跳过且不抛", ok, bad);

        // ---------- ⑥ 自定义档：能存、能选、非法被拒、删了如实回落 ----------
        String r1 = setCustom("老友", "像处了十年的老朋友，接得住梗，会主动记得你说过的事。");
        nOk += chk(r1.contains("\"style\":\"" + CUSTOM_PREFIX + "老友\"")
                && styleClause().contains("像处了十年的老朋友"),
                "新增自定义档并立即生效", ok, bad);
        nOk += chk(persona().contains("像处了十年的老朋友"), "自定义档也进了 persona", ok, bad);
        nOk += chk(setCustom("", "x").contains("error"), "空名字被拒", ok, bad);
        nOk += chk(setCustom("空白", "   ").contains("error"), "空白文本被拒", ok, bad);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i <= CUSTOM_MAX_LEN; i++) big.append('啊');
        nOk += chk(setCustom("太长", big.toString()).contains("error"),
                "超长文本被拒(>" + CUSTOM_MAX_LEN + ")", ok, bad);
        nOk += chk(!isValidStyle(CUSTOM_PREFIX + "不存在"), "custom:不存在 不算有效档", ok, bad);
        String r2 = delCustom("老友");
        nOk += chk(r2.contains("\"style\":\"" + DEFAULT_STYLE + "\"") && styleClause().contains("温柔体贴"),
                "删掉当前档 ⇒ 如实回落默认（不留空档）", ok, bad);

        // ★ 还原全局状态
        CUSTOMS.clear(); CUSTOMS.putAll(svCustoms);
        level = svLevel; style = svStyle;
        Emotion.mood = svMood; Emotion.moodWhy = svMoodWhy;

        // ★★ 修（2026-10-11）：与 Emotion 同源缺陷 —— chk 失败没人给 nBad 记账 ⇒ 头部恒报
        //   "k/k PASS"、失败项被吞。改为按 bad 里的失败条目数如实计数（只统计成功=假绿灯）。
        int nBad = 0;
        for (int i = 0; i < bad.length(); i++) if (bad.charAt(i) == '\n') nBad++;
        int r = nOk + nBad;
        StringBuilder out = new StringBuilder();
        out.append("调性自检 ").append(nOk).append('/').append(r);
        out.append(nBad == 0 ? " PASS" : " FAIL(" + nBad + ")").append('\n');
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
