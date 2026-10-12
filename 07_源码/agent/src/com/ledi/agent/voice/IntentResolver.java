package com.ledi.agent.voice;

import com.ledi.agent.core.AgentService;
import com.ledi.agent.core.BootLog;
import com.ledi.agent.core.J;
import com.ledi.agent.sdk.SdkController;

import android.content.Context;
import android.media.AudioManager;

import java.io.File;
import java.io.FileInputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 离线意图解析（v94）—— 夹在「离线词表识别」与「大模型」之间的一层。
 *
 * <h3>为什么需要这一层</h3>
 * 这台机器人的离线识别是<b>语法受限解码器</b>：它的输出**只可能是词表里的某一句整句**，
 * 或者什么都没有。这条事实决定了两种失败，而它们需要的对策完全不同：
 *
 * <ol>
 *   <li><b>词表里没有这句</b> → 一个字都出不来（{@code asr.finish error=7}）。
 *       这不是"听不清"，是"不在识别范围里"。</li>
 *   <li><b>词表里有两句发音很像</b> → 解码器<b>必然二选一</b>，可能选错。
 *       例如「讲个笑话」被听成「讲个故事」、「亮一点」被听成「暗一点」。
 *       输出是**合法但错误**的整句 —— 这一点最坑，因为下游看不出任何异常。</li>
 * </ol>
 *
 * <p>对这两类，大模型恰好都能帮上忙，但前提是**告诉它发生了什么**。
 * 此前送给模型的只有一句"可能有同音错字或漏字" —— 对语法解码器来说这句提示是错的
 * （它根本不会吐错字），模型于是无从判断"该不该怀疑识别结果"。这里把词表边界
 * 和**易混候选**一起交代清楚，模型的纠错才有依据。</p>
 *
 * <h3>三条能力</h3>
 * <pre>
 *   vocab()           词表镜像（直接读设备上那份 .bsg，**不另存一份**，永远同步）
 *   correctionHint()  给模型的旁白：识别范围 + 易混候选（AI 纠错的依据）
 *   local()           本地直通：少数命令不经过大模型就能准确执行（离线也能用）
 * </pre>
 *
 * <h3>词表为什么从 .bsg 里读、而不是在 Java 里再抄一份</h3>
 * 抄一份就一定会漂移（改了词表忘了改 Java）。设备上那份 {@code ledi_grammar.bsg}
 * 是**语音引擎真正加载的文件**，也就是"机器人实际能听懂什么"的唯一真源；
 * 从它里面把 {@code origin} 字段扫出来即可。URL 解码 + 扫字符串，不需要 JSON 库。
 */
public final class IntentResolver {

    private static final String TAG = AgentService.TAG;

    /** 设备上那份词表（与语音引擎加载的是同一份）。 */
    static final String GRAMMAR_PATH = VoiceDialog.GRAMMAR_SDCARD;   // /sdcard/ledi_grammar.bsg

    /** 开关：本地直通是否生效。默认开（离线也要能用命令）。 */
    static final String KEY_LOCAL = "intent_local";

    private static volatile String[] VOC = new String[0];
    private static volatile long loadedAt;
    private static volatile boolean loadFailed;

    // 诊断
    private static volatile String lastPhrase = "";
    private static volatile String lastRoute = "";    // local:xxx | ai | none
    private static volatile String lastNote = "";
    private static volatile String lastNear = "";

    private IntentResolver() {}

    // ================================================================ 词表

    /** 重新从设备上的 .bsg 里读词表。改了词表 / 换了语法后要调一次。 */
    public static void reload() {
        try {
            File f = new File(GRAMMAR_PATH);
            if (!f.exists() || f.length() <= 0) {
                VOC = new String[0];
                loadFailed = true;
                BootLog.w("意图解析 读不到词表 " + GRAMMAR_PATH + "（本地直通与易混候选将不可用）");
                return;
            }
            byte[] raw = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            try {
                int off = 0, n;
                while (off < raw.length && (n = in.read(raw, off, raw.length - off)) > 0) off += n;
            } finally {
                in.close();
            }
            String enc = new String(raw, "UTF-8");
            String txt;
            try {
                txt = URLDecoder.decode(enc, "UTF-8");
            } catch (Throwable t) {
                txt = enc;      // 有裸 % 之类的非标准编码时退化为原文，扫不到就当空
            }
            LinkedHashSet<String> set = new LinkedHashSet<String>();
            String key = "\"origin\"";
            int i = 0;
            while (true) {
                int a = txt.indexOf(key, i);
                if (a < 0) break;
                i = a + key.length();
                int c = txt.indexOf(':', i);
                if (c < 0 || c - i > 12) continue;      // 不是 "origin": 这种键值形式，跳过
                int q = c + 1;
                while (q < txt.length() && Character.isWhitespace(txt.charAt(q))) q++;
                if (q >= txt.length() || txt.charAt(q) != '"') continue;
                q++;
                StringBuilder sb = new StringBuilder();
                while (q < txt.length()) {
                    char ch = txt.charAt(q);
                    if (ch == '\\' && q + 1 < txt.length()) {
                        sb.append(txt.charAt(q + 1));
                        q += 2;
                        continue;
                    }
                    if (ch == '"') break;
                    sb.append(ch);
                    q++;
                }
                i = q + 1;
                String p = sb.toString().trim();
                if (p.isEmpty()) continue;
                // 词表里带节点的条目（如「伸出<bodyname>」）解码器是会展开的，它们同样是
                // **机器人真能听懂的整句**，只是 JSON 里记的是模板。展开后一起进镜像 ——
                // 否则「伸出右手」这类在会上被判成"不在词表里"，易混候选就少了一整块。
                for (String e : expand(p)) set.add(e);
            }
            VOC = set.toArray(new String[set.size()]);
            loadedAt = System.currentTimeMillis();
            loadFailed = false;
            BootLog.i("意图解析 词表已加载 " + VOC.length + " 条（来自 " + GRAMMAR_PATH + "）");
        } catch (Throwable t) {
            loadFailed = true;
            BootLog.w("意图解析 读词表失败: " + t);
        }
    }

    public static String[] vocab() {
        if (VOC.length == 0 && !loadFailed) reload();
        return VOC;
    }

    /**
     * 语法槽取值 —— 直接抄自设备上那份 .bsg 的 {@code slots} 段（原厂资产，不会变）：
     * <pre>
     *   bodyname  = 手 左手 右手 双手
     *   direction = 左 右
     *   musicname = 兔子舞 超级飞侠 飞侠体操第一套 第一套飞侠体操
     * </pre>
     * 这些是厂方固定的枚举，不是我们的配置，所以在这里写死是安全的；
     * 真正的词表仍然只从 .bsg 里读（见 {@link #reload}）。
     */
    private static final String[][] SLOTS = {
            {"<bodyname>", "手", "左手", "右手", "双手"},
            {"<direction>", "左", "右"},
            {"<musicname>", "兔子舞", "超级飞侠", "飞侠体操第一套", "第一套飞侠体操"},
    };

    /** 把一条带节点的词条展开成所有可说整句；没有节点就原样返回一条。 */
    private static List<String> expand(String p) {
        ArrayList<String> cur = new ArrayList<String>();
        cur.add(p);
        for (String[] slot : SLOTS) {
            String tok = slot[0];
            ArrayList<String> next = new ArrayList<String>();
            for (String s : cur) {
                if (s.indexOf(tok) < 0) { next.add(s); continue; }
                for (int i = 1; i < slot.length; i++) next.add(s.replace(tok, slot[i]));
            }
            cur = next;
        }
        // 展开后若还残留别的 <…>（未知节点），就整条丢掉，别放进"能听懂"的镜像里
        ArrayList<String> out = new ArrayList<String>();
        for (String s : cur) if (s.indexOf('<') < 0 && s.indexOf('>') < 0) out.add(s);
        return out;
    }

    static boolean localEnabled() {
        try {
            return AgentService.I == null || AgentService.I
                    .getSharedPreferences(AgentService.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_LOCAL, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setLocalEnabled(boolean on) {
        try {
            if (AgentService.I != null) {
                AgentService.I.getSharedPreferences(AgentService.PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_LOCAL, on).commit();
            }
        } catch (Throwable ignored) { }
    }

    // ================================================================ 文本工具

    /**
     * 归一化：去空白与标点、统一小写。
     *
     * <p>词表里的句子本身是干净的；这一步主要防 ASR 偶尔带出的标点，
     * 以及让"同一个意思不同写法"能对上。</p>
     */
    public static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= ' ') continue;
            if ("，。、！？；：,.!?;:~…\"'“”‘’()（）【】[]{}<>-_/\\|".indexOf(c) >= 0) continue;
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** 有界编辑距离（超过 cap 直接返回 cap+1，不做无谓计算）。 */
    static int dist(String a, String b, int cap) {
        int n = a.length(), m = b.length();
        if (Math.abs(n - m) > cap) return cap + 1;
        int[] prev = new int[m + 1], cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            int best = cur[0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= m; j++) {
                int cost = ca == b.charAt(j - 1) ? 0 : 1;
                int v = prev[j - 1] + cost;
                int d = prev[j] + 1;
                if (d < v) v = d;
                d = cur[j - 1] + 1;
                if (d < v) v = d;
                cur[j] = v;
                if (v < best) best = v;
            }
            if (best > cap) return cap + 1;      // 整行都超了，后面只会更大
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[m];
    }

    /** 句尾语气助词：差别只在这些字上的两条，算同一个说法的变体，不算"易混"。 */
    private static final String TAIL = "吧呀啊呢嘛哦啦咯喽哈嘛的了哇哎诶";

    private static boolean variantOf(String a, String b) {
        String s = a.length() <= b.length() ? a : b;
        String l = a.length() <= b.length() ? b : a;
        if (s.isEmpty() || !l.startsWith(s)) return false;
        String rest = l.substring(s.length());
        for (int i = 0; i < rest.length(); i++) {
            if (TAIL.indexOf(rest.charAt(i)) < 0) return false;
        }
        return true;
    }

    /**
     * 与 {@code asr} 发音/写法最接近的<b>其他</b>词表句子（按需取前 k 条）。
     *
     * <p>注意这是<b>汉字级</b>编辑距离，不是声学距离。它对本用途够用是因为
     * 词表句子都很短、且易混对几乎总是"只差关键那一个字"：
     * 笑话/故事、大一点/小一点、亮一点/暗一点、停止/暂停。</p>
     */
    public static List<String> near(String asr, int k) {
        ArrayList<String> out = new ArrayList<String>();
        String a = norm(asr);
        if (a.isEmpty()) return out;
        String[] v = vocab();
        int cap = a.length() <= 6 ? 2 : 3;
        int[] ds = new int[v.length];
        for (int i = 0; i < v.length; i++) {
            String b = norm(v[i]);
            if (b.isEmpty() || b.equals(a)) { ds[i] = 99; continue; }
            if (variantOf(a, b)) { ds[i] = 99; continue; }        // 「讲个故事」/「讲个故事吧」
            int d = dist(a, b, cap);
            ds[i] = d > cap ? 99 : d;
        }
        // 选前 k：距离小优先，同距离短句优先
        for (int pick = 0; pick < k; pick++) {
            int best = -1;
            for (int i = 0; i < v.length; i++) {
                if (ds[i] >= 99) continue;
                if (best < 0) { best = i; continue; }
                if (ds[i] < ds[best]) best = i;
                else if (ds[i] == ds[best] && v[i].length() < v[best].length()) best = i;
            }
            if (best < 0) break;
            out.add(v[best]);
            ds[best] = 99;
        }
        return out;
    }

    // ================================================================ 给模型的旁白

    /**
     * AI 纠错的依据：告诉模型"这是从一份固定词表里选出来的整句"，并列出易混候选。
     *
     * <p>拼在 {@code VOICE_HINT} 后面一起当 preface 发出去（旁白不进多轮历史）。</p>
     */
    public static String correctionHint(String asr) {
        StringBuilder sb = new StringBuilder();
        sb.append("（补充说明：本轮结果来自机器人的**离线词表识别**，它只会输出词表里的整句，"
                + "所以不存在错别字；典型错误是「听成了词表里另一句发音相近的话」。");
        List<String> ns = near(asr, 3);
        if (!ns.isEmpty()) {
            sb.append("与「").append(asr).append("」最可能被听混的是：");
            for (int i = 0; i < ns.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append(ns.get(i));
            }
            sb.append("。请结合上文判断我更可能想说哪一句，按最可能的意思回答。");
        } else {
            sb.append("请结合上文判断我真正想说的是什么。");
        }
        sb.append("）\n");
        return sb.toString();
    }

    /**
     * 一个字都没识别出来时的旁白：不是"没听见"，而是"这句话不在词表里"。
     *
     * <p>这是最需要 AI 推测意图的场景 —— 什么都没识别到，靠的只能是上文。</p>
     */
    static String missHint() {
        return "（主人刚才确实说话了，但**一个词都没识别出来** —— 说明那句话不在机器人的离线词表里。"
                + "请顺着上面的对话猜一下主人最可能想说什么：像是接着上一个话题，就顺着往下说；"
                + "完全猜不到，就用一句话礼貌地请他换个说法。绝对不要编造主人说过的话。）\n";
    }

    // ================================================================ 本地直通

    /**
     * 本地直通的结果。
     *
     * <p>★ <b>它是"判定"不是"执行"</b>：{@link #local} 只做判定、**没有任何副作用**，
     * 真正的动作在 {@link #runAct} 里做。为什么要拆开：验证端点 {@code POST /intent {"test":…}}
     * 号称"只判定不执行"，如果 {@code local()} 自己就把 {@code motion.stop()} 调了，
     * 那句承诺就是假的 —— 拿「停止」去探测会把机器人真的停下（而且看不出来）。
     * 判定/执行混在一起，等于给验证工具埋了一颗地雷。</p>
     */
    public static final class Act {
        public final String reply;   // 带标签的回复，走既有 applyAiTags 通道
        public final String note;
        /**
         * 需要执行的**副作用**类别：
         * {@code tag}（无需额外动作，标签自己会执行）/ stop / volume / led。
         * <p>★ {@code volume} 的出口是 {@link Av} ——
         * 语音、网页、脚本共用同一个出口，别在这里另开一条。
         * （原来的 {@code bright} 已于 v201 随「屏幕亮度」功能一并移除。）</p>
         */
        final String kind;
        /** 副作用的参数（音量绝对值；其余 kind 不用）。 */
        final int arg;
        /**
         * {@code kind=="led"} 时的「颜色.动作」，如 {@code b.on} / {@code all.off}。
         *
         * <p>★ 为什么不复用 {@code arg}（int）：灯光要**两个**维度（颜色 + 开/关），
         * 而颜色是枚举（r/g/b/y/p/all）、动作也是（on/off）。硬编码成 int 只会让
         * 读的人去数位。这与 {@code reply} 里那套 {@code [灯光:蓝]} 标签并存 ——
         * 标签通道适合 AI 自由发挥，这里适合本地直通要的**精确**下发。</p>
         */
        final String extra;

        Act(String reply, String note) { this(reply, note, "tag", 0, null); }

        Act(String reply, String note, String kind, int arg) { this(reply, note, kind, arg, null); }

        Act(String reply, String note, String kind, String extra) {
            this(reply, note, kind, 0, extra);
        }

        Act(String reply, String note, String kind, int arg, String extra) {
            this.reply = reply;
            this.note = note;
            this.kind = kind;
            this.arg = arg;
            this.extra = extra;
        }
    }

    /**
     * 执行 {@link #local} 判定的副作用。**只有真人说话与 {@code POST /intentrun} 该调它**，
     * {@code POST /intent {"test":…}} 绝不能调（否则"只判定"就是谎话）。
     */
    public static void runAct(Act a) {
        if (a == null) return;
        try {
            if ("stop".equals(a.kind)) {
                if (AgentService.motion != null) AgentService.motion.stop();
            } else if ("volume".equals(a.kind)) {
                if (AgentService.face != null) AgentService.face.volume(a.arg);
            } else if ("led".equals(a.kind)) {
                // ★ v180：头顶灯。走的是与 /sdk/led、[灯光:] 标签**同一个**出口
                //   （SdkController.led），所以"本地直通点亮"和"AI 标签点亮"
                //   下发的是同一条原厂指令，不存在两套实现分叉。
                //   ★ 如实口径：这里只能证明**指令发出去了**；灯是硬件、读不回来，
                //     到底亮没亮仍要看现场（见项目铁律「发出≠送达」）。
                if (AgentService.sdk != null && a.extra != null && !a.extra.isEmpty()) {
                    int dot = a.extra.indexOf('.');
                    String c = dot > 0 ? a.extra.substring(0, dot) : a.extra;
                    String act = dot > 0 ? a.extra.substring(dot + 1) : "on";
                    String r = AgentService.sdk.led(c, act);
                    BootLog.i("意图解析 本地直通 灯 " + c + "." + act + " -> " + r);
                } else {
                    BootLog.w("意图解析 本地直通 灯：没有可用出口（sdk=" + AgentService.sdk + "）");
                }
            }
            // kind == "tag"：不需要额外动作 —— reply 里的 [表情:]/[动作:] 由 applyAiTags 执行
        } catch (Throwable t) {
            BootLog.w("意图解析 本地直通执行失败（" + a.kind + "）: " + t);
        }
    }

    private static boolean hit(String t, String... keys) {
        for (String k : keys) if (t.equals(norm(k))) return true;
        return false;
    }

    /**
     * **包含式**命中 —— 只要句子里出现任一关键词即算。
     *
     * <p>★ 为什么灯光这条非用它不可：{@link #hit} 是**逐字相等**，而真机上主人说的是
     * 「你头上的蓝色灯亮一下」这种带前缀的整句，逐字比必然落空。
     * 2026-10-06 实测就是这么漏的：这句落到大模型手里，模型回了句
     * 「亮了。还有别的吗？」—— <b>嘴上说亮了，一个灯光指令都没发出去</b>。
     * 「说做了但没做」比"没听懂"更糟（用户会以为灯坏了），所以这条必须本地接住。</p>
     *
     * <p>★ 代价与边界：包含式天生比相等式"宽"。所以调用方只把它用在
     * <b>意图唯一、说错也没代价</b>的短句上，并且**先判否定再判肯定**
     * （「关灯」优先于「开灯」），避免"关灯"被读成"开灯"。</p>
     */
    private static boolean has(String t, String... keys) {
        for (String k : keys) {
            String n = norm(k);
            if (!n.isEmpty() && t.contains(n)) return true;
        }
        return false;
    }

    /**
     * 本地直通：这一句不经过大模型就能<b>准确</b>执行，返回 null 表示"不归我管，走 AI"。
     *
     * <h3>为什么值得做</h3>
     * <ul>
     *   <li><b>离线可用</b>：没网 / 没配 key / 电脑关机时，「停止」「电量」「声音小一点」
     *       这类命令现在会整条丢给大模型 → 失败 → 机器人装死。本地直通让它们永远有效。</li>
     *   <li><b>快</b>：省掉一次往返（实测几百毫秒到数秒），短命令的体感差别很大。</li>
     *   <li><b>不污染记忆</b>：「声音大一点」这种不构成"对话内容"，不该占多轮记忆的位置。</li>
     * </ul>
     *
     * <h3>刻意的边界</h3>
     * 只收<b>意图唯一、本地就能做对</b>的命令。但凡有一点"要理解语义"的成分
     * （「你会跳舞吗」「讲个笑话」）就一律交给 AI —— 抢答比不答更糟。
     * 想要关闭这条通道：{@code POST /intent {"on":false}}。
     */
    public static Act local(String asr) {
        String t = norm(asr);
        if (t.isEmpty() || !localEnabled()) return null;

        // —— 停止 / 暂停：最常被漏掉的短命令，必须本地可靠
        // ★ 这里只**判定**，不调 motion.stop()（副作用在 runAct 里）—— 见 Act 的注释
        if (hit(t, "停止", "暂停", "停一下")) {
            return new Act("[表情:开心]好，我停下来啦。", "本地直通 停止", "stop", 0);
        }

        // —— 电量：本地就能读到准确数字，模型只能瞎猜
        if (hit(t, "查询电量", "还有多少电", "电量还有多少")) {
            int b = AgentService.sensor == null ? -1 : AgentService.sensor.battery;
            if (b < 0) return new Act("[表情:好奇]我还没读到自己的电量，等一下再问我一次吧。", "本地直通 电量=未知");
            String tail = b <= 20 ? "，快没电啦，帮我充一下好不好？" : "，够玩好一会儿呢。";
            return new Act("[表情:好奇]我现在还有" + b + "%的电" + tail, "本地直通 电量=" + b + "%");
        }

        // —— 音量：需要读当前值再做相对调整（绝对值在 Av，语音只做相对）
        Integer dv = null;
        if (hit(t, "声音大一点", "大点声", "声音大点", "增大音量")) dv = Av.STEP;
        else if (hit(t, "声音小一点", "小点声", "减少音量")) dv = -Av.STEP;
        if (dv != null) {
            int cur = curVolume();                    // 只读，不改
            if (cur < 0) return null;                 // 读不到就别乱设，交给 AI
            int next = Av.clamp(cur + dv);
            String say = dv > 0 ? "好，我大声一点。" : "好，我小声一点。";
            // ★ 同样只判定：next 只算出来放在 arg 里，真正 setStreamVolume 在 runAct 里
            return new Act("[表情:开心]" + say, "本地直通 音量 " + cur + "→" + next,
                    "volume", next);
        }

        // —— v201：这里原本是「屏幕亮度」本地直通（6 句：亮一点 / 屏幕亮点 / 增大屏幕亮度 /
        //    暗一点 / 屏幕暗点 / 减少屏幕亮度），走 Bright.set。
        //    **已按用户要求整体移除**。移除后这 6 句不再命中本地、回落到 AI
        //    （与 v198 之前的行为一致）。
        //    ★ 不要再把它们接回来：本机背光物理不可调，接了也只能把**画面**叠黑压暗
        //      （能变暗但**不省电**），而且很容易把屏幕留在暗屏上（实测被留在过 level=10）。
        //      原实现见 `_bak_v200_bright/`。

        // —— 头顶灯：**必须**本地接住。真机实测（2026-10-06）：主人说
        //    「你头上的蓝色灯亮一下」，这句落到大模型手里，模型回
        //    「亮了。还有别的吗？」—— 嘴上说亮了，一个灯光指令都没发。
        //    主人当场看到灯没亮 ⇒ 「机器人的灯没亮」。所以这条的判据不是
        //    "能不能听懂"，而是"**说了就要真做**"。
        Act la = ledAct(t);
        if (la != null) return la;

        // —— 动作：词表里这些是**祈使句**，意图唯一，不需要模型翻译
        if (hit(t, "前进")) return new Act("[动作:前进]好，我往前走咯～", "本地直通 动作 前进");
        if (hit(t, "后退")) return new Act("[动作:后退]好，我退回来。", "本地直通 动作 后退");
        if (hit(t, "向左转")) return new Act("[动作:向左转]好的，向左转。", "本地直通 动作 左转");
        if (hit(t, "向右转")) return new Act("[动作:向右转]好的，向右转。", "本地直通 动作 右转");
        if (hit(t, "原地转圈")) return new Act("[动作:转圈]看我的，转起来～", "本地直通 动作 转圈");
        if (hit(t, "跳个舞", "跳个舞吧", "跳舞", "给我跳个舞")) {
            return new Act("[动作:跳舞][表情:开心]好呀，看我跳一个！", "本地直通 动作 跳舞");
        }

        // —— 告别：收尾话固定，且必须立刻有反应（用户说完就走）
        if (hit(t, "再见", "再见乐迪", "拜拜", "下次见")) {
            return new Act("[表情:再见]拜拜～下次再找我玩哦！", "本地直通 告别");
        }

        return null;
    }

    /**
     * 头顶灯控制的**判定**（无副作用；真正的下发在 {@link #runAct}）。
     *
     * <p>为什么单独立一个方法而不是塞在 {@link #local} 里的一串 {@code if}：
     * 灯光这条的判据是"提到灯 + 带控制动词"，比别的命令多两个条件，
     * 混在大流程里下次改动很容易被删掉其中一个（本项目老毛病）。</p>
     *
     * <p>★ 为什么用 {@link #has}（包含式）而不是 {@link #hit}（逐字相等）：
     * 主人说的是「你头上的蓝色灯亮一下」这种**带前缀的整句**，
     * 逐字比一定落空 —— 这条命令就是这么漏到大模型那里去的。</p>
     */
    private static Act ledAct(String t) {
        // ① 必须提到"灯"；② 必须带一个控制动词；③ 别抢长句（长句多半是在"描述"
        //    而不是"下命令"，交给 AI 更合适）。
        if (!has(t, "灯")) return null;
        if (!has(t, "亮", "开", "关", "灭", "熄", "点", "换", "变")) return null;
        if (t.length() > 24) return null;

        String color = "b";
        if (has(t, "红")) color = "r";
        else if (has(t, "绿")) color = "g";
        else if (has(t, "黄")) color = "y";
        else if (has(t, "粉")) color = "p";
        // ★ 否定优先：「关灯」「灯灭」「别亮」必须先判 —— 否则"关灯"里的"灯"会被
        //   当成"把灯点亮"。同一个句子里同时出现"开"和"关"时也以"关"为准。
        if (has(t, "关", "灭", "熄", "别亮", "不要亮", "不用亮")) {
            return new Act("[表情:开心]好，灯我关了。", "本地直通 灯 all.off", "led", "all.off");
        }
        return new Act("[表情:开心]好，灯给你亮上。", "本地直通 灯 " + color + ".on",
                "led", color + ".on");
    }

    private static int curVolume() {
        // 委托给 Av —— 音量只有一个出口，读和写都在那儿（别再各写一份 AudioManager 调用）
        return Av.get();
    }

    // ================================================================ 诊断

    /** 走一次解析但**不执行**，用于远程验证（POST /intent {"test":"…"}）。 */
    public static String probe(String asr) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"phrase\":\"").append(J.esc(asr)).append('"');
        sb.append(",\"norm\":\"").append(J.esc(norm(asr))).append('"');
        sb.append(",\"in_vocab\":").append(inVocab(asr));
        sb.append(",\"local_enabled\":").append(localEnabled());
        Act a = local(asr);
        if (a == null) {
            sb.append(",\"route\":\"ai\",\"local\":null");
        } else {
            sb.append(",\"route\":\"local\",\"local_reply\":\"").append(J.esc(a.reply))
              .append("\",\"local_note\":\"").append(J.esc(a.note)).append('"')
              // ★ 只"报告会做什么"，**不做** —— probe 必须无副作用（见 Act 的注释）
              .append(",\"would_do\":\"").append(J.esc(a.kind)).append('"');
            if ("volume".equals(a.kind)) sb.append(",\"would_set_volume\":").append(a.arg);
            if ("led".equals(a.kind)) sb.append(",\"would_led\":\"").append(J.esc(a.extra)).append('"');
        }
        sb.append(",\"near\":[");
        List<String> ns = near(asr, 5);
        for (int i = 0; i < ns.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(J.esc(ns.get(i))).append('"');
        }
        sb.append("],\"hint\":\"").append(J.esc(correctionHint(asr))).append("\"}");
        return sb.toString();
    }

    static boolean inVocab(String asr) {
        String t = norm(asr);
        for (String v : vocab()) if (norm(v).equals(t)) return true;
        return false;
    }

    /** 记一笔本轮的路由结果（给 /status 看）。 */
    public static void remember(String phrase, String route, String note, List<String> near) {
        lastPhrase = phrase == null ? "" : phrase;
        lastRoute = route == null ? "" : route;
        lastNote = note == null ? "" : note;
        StringBuilder sb = new StringBuilder();
        if (near != null) {
            for (int i = 0; i < near.size(); i++) {
                if (i > 0) sb.append('/');
                sb.append(near.get(i));
            }
        }
        lastNear = sb.toString();
    }

    public static String statusJson() {
        String src;
        try {
            File f = new File(GRAMMAR_PATH);
            src = f.exists() ? GRAMMAR_PATH + " (" + f.length() + "B)" : "缺失";
        } catch (Throwable t) {
            src = "?";
        }
        return "{\"local_on\":" + localEnabled()
                + ",\"vocab\":" + vocab().length
                + ",\"source\":\"" + J.esc(src) + "\""
                + ",\"failed\":" + loadFailed
                + ",\"loaded_at\":" + loadedAt
                + ",\"last_phrase\":\"" + J.esc(lastPhrase) + "\""
                + ",\"last_route\":\"" + J.esc(lastRoute) + "\""
                + ",\"last_note\":\"" + J.esc(lastNote) + "\""
                + ",\"last_near\":\"" + J.esc(lastNear) + "\"}";
    }
}
