package com.ledi.agent.cap;

import com.ledi.agent.core.J;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * 机器人端<b>长期记忆</b>（v214）—— 它"记着的事"，以及**一条真能验证的"忘记"**。
 *
 * <h3>为什么需要它（用户的两次原话）</h3>
 * <blockquote>
 * 「是否可以为机器人添加情绪系统（我希望机器人有很好的陪伴效果，不要说话太人机）」<br>
 * 「可以添加一个让机器人忘记记忆的，机器人长期记忆存储在机器人本地」
 * </blockquote>
 * v189 的情绪系统解决了"脸会变"，v214 的 {@link Tone} 心情层解决了"说话有起伏"，但
 * 这两样都只在<b>当下</b>。真正让人机感消失、让"陪伴"成立的，是<b>关系会累积</b>：
 * 它记得你、记得你们认识多久、聊过几次、你提过什么。而"能忘"恰恰是这项能力的另一半 ——
 * 记忆若不给人一个清零的开关，用户不敢用。
 *
 * <h3>★ 存哪 / 存什么（全部在机器人本地，不上云）</h3>
 * <pre>
 *   /data/data/com.ledi.agent/ledi_memory.jsonl   显式事实（remember 记下的原话）
 *   /data/data/com.ledi.agent/ledi_memory.meta    关系计数（first_seen / last_seen / talks）
 * </pre>
 * 两份都在**应用私有目录**（不是 /sdcard）—— 记忆是最私人的一类数据，不放在随手能 adb pull
 * 的公共目录里；要看就 {@code GET /memory}（走 HTTP，与别的取证口同源）。
 *
 * <h3>★ 为什么不用 {@code org.json}</h3>
 * 本类要能在**纯 JVM**（没有设备）里跑 {@link #selftest()}，而 {@code android.jar} 里的
 * {@code org.json} 是 <b>stub</b>（编得过 ≠ 跑得了）。所以事实的落盘格式
 * 用**自制分隔符**（{@code \u001F}）而不是 JSON，编解码往返在 JVM 里就能断言。
 *
 * <h3>★★ "忘记"必须可以被验证（本类最重要的一条）</h3>
 * 只返回 {@code {"ok":true}} 的"忘记"是典型的<b>假绿灯</b>：删没删干净谁也不知道。
 * 所以 {@link #forget(String)} 一律<b>回读</b>：
 * <ol>
 *   <li>删内存环 + 删文件（含 TalkLog 的 {@code .1} 轮转件）；</li>
 *   <li>再把文件实际字节数量一遍，写进返回值的 {@code bytes_after}；</li>
 *   <li>{@code ok} 由 {@code bytes_after == 0} <b>算出来</b>，不是写死的常量。</li>
 * </ol>
 * 真机验收就照这条问：忘记之后再 {@code GET /memory}、再 {@code GET /talk}，两处都必须空。
 */
public final class Memory {

    // ------------------------------------------------------------ 落盘

    private static final String FACTS_FILE = "ledi_memory.jsonl";
    private static final String META_FILE  = "ledi_memory.meta";

    /** 事实行的字段分隔符。选控制字符是因为它不可能出现在用户说的话里 ⇒ 不用转义。 */
    private static final char SEP = '\u001F';

    /** 最多记这么多条事实（超了丢最旧的）。够用又不会把提示词撑爆。 */
    static final int MAX_FACTS = 200;
    /** 单条事实最长多少字。 */
    static final int MAX_FACT_LEN = 200;

    private static final Object LOCK = new Object();
    /** 内存里的事实环（读路径只走它，不反复读盘）。 */
    private static final ArrayDeque<Fact> FACTS = new ArrayDeque<Fact>();
    /** 落盘根目录（应用私有）。null = 未 init（纯 JVM 里由 selftest 临时指定）。 */
    private static volatile String dir;

    // 关系计数（落 meta 文件）
    private static volatile long firstSeen;   // 第一次打交道（epoch ms），0 = 还没有
    private static volatile long lastSeen;    // 最近一次
    private static volatile long talks;       // 累计聊过多少轮

    private Memory() {}

    /** 一条事实。字段名即 {@code GET /memory} 里的键。 */
    public static final class Fact {
        public long t;
        public String text = "";
        Fact() {}
        Fact(long t, String s) { this.t = t; this.text = s; }
    }

    /** AgentService.onCreate 里调一次：登记私有目录 + 把已有记忆读回内存。 */
    public static void init(Context ctx) {
        try {
            if (ctx != null) dir = ctx.getFilesDir().getAbsolutePath();
        } catch (Throwable ignored) { }
        load();
    }

    /** 纯 JVM 自检用：临时指定一个目录（跑完必须还原成 null）。 */
    static void setDirForTest(String d) { dir = d; }

    private static String factsPath() { return dir == null ? null : new File(dir, FACTS_FILE).getAbsolutePath(); }
    private static String metaPath()  { return dir == null ? null : new File(dir, META_FILE).getAbsolutePath(); }

    // ================================================================ 记

    /**
     * 记一条事实（用户明确要它记住的事）。
     *
     * @return JSON：{@code {"ok":true,"text":"…","count":N}}；参数非法或未就绪时 {@code ok:false}
     */
    public static String remember(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) return "{\"ok\":false,\"error\":\"empty\"}";
        if (s.length() > MAX_FACT_LEN) s = s.substring(0, MAX_FACT_LEN);
        if (dir == null) return "{\"ok\":false,\"error\":\"not ready\"}";
        Fact f = new Fact(System.currentTimeMillis(), s);
        int n;
        synchronized (LOCK) {
            FACTS.addLast(f);
            while (FACTS.size() > MAX_FACTS) FACTS.pollFirst();
            n = FACTS.size();
        }
        appendFact(f);
        // ★ 只记"打过一次交道"（首/末次时间），**不**动"聊过几次" —— 记事实 ≠ 聊天。
        //   为什么强调：AI 听到「记住我喜欢猫」会同时走对话通路（pushDialog → noteTalk）
        //   和 remember；若这里也 +1，「聊过几次」就会被灌水（一话说成两次）。
        noteSeen();
        return "{\"ok\":true,\"text\":\"" + J.esc(s) + "\",\"count\":" + n + "}";
    }

    /** 有人跟它聊过一轮：累加计数 + 更新首/末次时间。**对话通路每轮调一次**。 */
    public static void noteTalk() {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (firstSeen == 0L) firstSeen = now;
            lastSeen = now;
            talks++;
        }
        saveMeta();
    }

    /**
     * 只更新"首/末次打交道"的时间，**不动** {@code talks}（"聊过几次"特指对话轮数）。
     *
     * <p>★ 与 {@link #noteTalk()} 分开是因为它们是**两件事**：{@code remember} 是"存一条事实"，
     * 不是"聊了一轮"。若共用一个计数，AI 那句「记住我喜欢猫」会被记成两次（对话通路一次 +
     * remember 一次）⇒ 关系计数被灌水。分成两个方法，"聊过几次"才是个真数。</p>
     */
    public static void noteSeen() {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (firstSeen == 0L) firstSeen = now;
            lastSeen = now;
        }
        saveMeta();
    }

    // ================================================================ 读

    /** 当前记着多少条事实。 */
    public static int factCount() {
        synchronized (LOCK) { return FACTS.size(); }
    }

    /** 认识第几天（第一次打交道那天算第 1 天）；还没记过就是 0。 */
    public static long daysTogether() {
        long f = firstSeen;
        if (f <= 0) return 0;
        long d = (System.currentTimeMillis() - f) / 86400000L + 1L;
        return d < 1 ? 1 : d;
    }

    /**
     * ★★★ 注入提示词的**唯一出处**（措辞只在这里写一份）。
     *
     * <p>纪律：这里只给"事实"，不给"该有什么情绪"—— 情绪措辞归 {@link Tone#moodClause()}，
     * 情绪词归 {@link Emotion#moodLabel()}。三样各管一段，不越界，才不会互相打架。</p>
     */
    public static String brief() {
        long days = daysTogether();
        long tk;
        List<String> fs;
        synchronized (LOCK) {
            tk = talks;
            fs = new ArrayList<String>();
            Iterator<Fact> it = FACTS.descendingIterator();     // 最新的在前
            while (it.hasNext() && fs.size() < 8) fs.add(it.next().text);
        }
        StringBuilder b = new StringBuilder(200);
        if (days <= 0) {
            b.append("（你们刚认识，你还没有特别记下什么）");
            return b.toString();
        }
        b.append("你认识对方第 ").append(days).append(" 天");
        // ★ 只在真聊过时提"聊过几次"：0 次却说"聊过 0 次"读着别扭（记过事实但还没对话过）
        if (tk > 0) b.append("，到现在一共聊过 ").append(tk).append(" 次");
        if (!fs.isEmpty()) {
            b.append("。你记着对方说过的：");
            for (int i = 0; i < fs.size(); i++) {
                if (i > 0) b.append("；");
                b.append(fs.get(i));
            }
        }
        b.append("。");
        return b.toString();
    }

    /** {@code GET /memory} 的完整对象（进程外可读的判据）。 */
    public static String json() {
        long days = daysTogether();
        StringBuilder sb = new StringBuilder(512);
        long fb = fileSize(factsPath()), mb = fileSize(metaPath());
        long fs, ls, tk;
        synchronized (LOCK) { fs = firstSeen; ls = lastSeen; tk = talks; }
        sb.append("{\"ok\":true")
          .append(",\"dir\":\"").append(J.esc(dir == null ? "" : dir)).append('"')
          .append(",\"files\":{\"facts\":\"").append(J.esc(factsPath() == null ? "" : factsPath()))
          .append("\",\"meta\":\"").append(J.esc(metaPath() == null ? "" : metaPath()))
          .append("\",\"facts_bytes\":").append(fb)
          .append(",\"meta_bytes\":").append(mb).append('}')
          .append(",\"count\":").append(factCount())
          .append(",\"max\":").append(MAX_FACTS)
          .append(",\"first_seen\":").append(fs)
          .append(",\"last_seen\":").append(ls)
          .append(",\"talks\":").append(tk)
          .append(",\"days\":").append(days)
          .append(",\"brief\":\"").append(J.esc(brief())).append('"')
          .append(",\"facts\":[");
        List<Fact> snap;
        synchronized (LOCK) { snap = new ArrayList<Fact>(FACTS); }
        for (int i = 0; i < snap.size(); i++) {
            if (i > 0) sb.append(',');
            Fact f = snap.get(i);
            sb.append("{\"t\":").append(f.t).append(",\"text\":\"").append(J.esc(f.text)).append("\"}");
        }
        return sb.append("]}").toString();
    }

    // ================================================================ 忘（★ 必须可真验证）

    /**
     * 忘记。{@code scope} = {@code facts | talk | all}。
     *
     * <p>★★ 返回值里的 {@code ok} 是**算出来的**（删完回读文件字节必须为 0），不是写死的。
     * 这就是"忘记"的可验证证据 —— 真机验收照它问。</p>
     */
    public static String forget(String scope) {
        String s = scope == null ? "all" : scope.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) s = "all";
        if (!"facts".equals(s) && !"talk".equals(s) && !"all".equals(s))
            return "{\"ok\":false,\"error\":\"bad scope: " + J.esc(s) + "（可选 facts|talk|all）\"}";

        long metaBefore = fileSize(metaPath());
        long factsBefore = fileSize(factsPath());

        boolean factsDone = true, talkDone = true;
        String talkJson = null;
        if ("facts".equals(s) || "all".equals(s)) {
            synchronized (LOCK) { FACTS.clear(); }
            factsDone = delete(factsPath());
            if ("all".equals(s)) {
                synchronized (LOCK) { firstSeen = 0L; lastSeen = 0L; talks = 0L; }
                saveMeta();
            }
        }
        if ("talk".equals(s) || "all".equals(s)) {
            // 对话流水是另一半"记忆"——忘记就该把它一起清掉（含 .1 轮转件）。
            talkJson = TalkLog.wipe();
            talkDone = talkJson != null && talkJson.contains("\"ok\":true");
        }

        // ★ 回读验证（真删了没，量字节）
        long factsAfter = fileSize(factsPath());
        long metaAfter = fileSize(metaPath());

        // ★★ ok **按 scope 算出来**：
        //   忘记 facts 只看事实文件是否空；忘记 talk 只看 TalkLog 自己回读的结果；
        //   all 两者都要。绝不用写死的常量 —— 否则"没删干净也报成功"就是假绿灯。
        boolean wantFacts = "facts".equals(s) || "all".equals(s);
        boolean wantTalk  = "talk".equals(s) || "all".equals(s);
        boolean ok = (!wantFacts || (factsDone && factsAfter == 0))
                  && (!wantTalk  || talkDone);

        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"ok\":").append(ok)
          .append(",\"scope\":\"").append(s).append('"');
        if (talkJson != null) sb.append(",\"talk\":").append(talkJson);
        sb.append(",\"bytes_before\":{\"facts\":").append(factsBefore)
          .append(",\"meta\":").append(metaBefore).append('}')
          .append(",\"bytes_after\":{\"facts\":").append(factsAfter)
          .append(",\"meta\":").append(metaAfter).append('}')
          .append(",\"count\":").append(factCount())
          .append(",\"talks\":").append(talks)
          .append(",\"note\":\"ok 由回读字节算出：facts 删空且/或 TalkLog 自证清空\"}");
        return sb.toString();
    }

    // ================================================================ 磁盘

    private static void appendFact(Fact f) {
        String p = factsPath();
        if (p == null) return;
        String line = f.t + String.valueOf(SEP) + sanitize(f.text) + "\n";
        try {
            FileOutputStream out = new FileOutputStream(p, true);
            try { out.write(line.getBytes("UTF-8")); } finally { out.close(); }
        } catch (Throwable ignored) { }
    }

    /** 分隔符与换行不可能进文件（事实天然是单行）。 */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace(SEP, ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static void saveMeta() {
        String p = metaPath();
        if (p == null) return;
        long fs, ls, tk;
        synchronized (LOCK) { fs = firstSeen; ls = lastSeen; tk = talks; }
        String body = "first_seen=" + fs + "\nlast_seen=" + ls + "\ntalks=" + tk + "\n";
        try {
            FileOutputStream out = new FileOutputStream(p, false);
            try { out.write(body.getBytes("UTF-8")); } finally { out.close(); }
        } catch (Throwable ignored) { }
    }

    /** 启动时把事实与计数读回内存（读不到就是空的，不报错）。 */
    private static void load() {
        synchronized (LOCK) { FACTS.clear(); firstSeen = 0L; lastSeen = 0L; talks = 0L; }
        String fp = factsPath();
        if (fp != null) {
            File f = new File(fp);
            if (f.exists() && f.length() > 0) {
                BufferedReader br = null;
                try {
                    br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                    String line;
                    synchronized (LOCK) {
                        while ((line = br.readLine()) != null) {
                            Fact x = parseFact(line);
                            if (x == null) continue;
                            FACTS.addLast(x);
                            while (FACTS.size() > MAX_FACTS) FACTS.pollFirst();
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    try { if (br != null) br.close(); } catch (Throwable ignored) {}
                }
            }
        }
        String mp = metaPath();
        if (mp != null) {
            File f = new File(mp);
            if (f.exists() && f.length() > 0) {
                BufferedReader br = null;
                try {
                    br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                    String line;
                    synchronized (LOCK) {
                        while ((line = br.readLine()) != null) {
                            int i = line.indexOf('=');
                            if (i <= 0) continue;
                            String k = line.substring(0, i).trim();
                            long v = parseLong(line.substring(i + 1).trim());
                            if ("first_seen".equals(k)) firstSeen = v;
                            else if ("last_seen".equals(k)) lastSeen = v;
                            else if ("talks".equals(k)) talks = v;
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    try { if (br != null) br.close(); } catch (Throwable ignored) {}
                }
            }
        }
    }

    static Fact parseFact(String line) {
        if (line == null) return null;
        int i = line.indexOf(SEP);
        if (i <= 0) return null;
        try {
            long t = Long.parseLong(line.substring(0, i).trim());
            String s = line.substring(i + 1);
            return new Fact(t, s);
        } catch (Throwable t) {
            return null;    // 坏行跳过
        }
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Throwable t) { return 0L; }
    }

    private static long fileSize(String p) {
        if (p == null) return 0L;
        try { File f = new File(p); return f.exists() ? f.length() : 0L; } catch (Throwable t) { return 0L; }
    }

    private static boolean delete(String p) {
        if (p == null) return true;
        try {
            File f = new File(p);
            boolean ok = !f.exists() || f.delete();
            File r = new File(p + ".1");
            if (r.exists()) r.delete();
            return ok;
        } catch (Throwable t) { return false; }
    }

    // ================================================================ 自检（build 硬门禁）

    /**
     * 纯 JVM 自检 —— 不吃 Context、不联网、不碰 org.json。
     *
     * <p>★ 会在真机上被 {@code GET /memoryselftest} 调到 ⇒ 跑完**还原目录并清空全局状态**，
     * 不留副作用（"跑自检 = 偷偷改设备"是本项目踩过的坑）。</p>
     */
    public static String selftest() {
        StringBuilder log = new StringBuilder();
        int ok = 0, n = 0;
        final String svDir = dir;
        final long svT = talks, svF = firstSeen, svL = lastSeen;
        final List<Fact> svFacts;
        synchronized (LOCK) { svFacts = new ArrayList<Fact>(FACTS); }

        File tmp = null;
        try {
            tmp = File.createTempFile("ledi_memtest", "");
            tmp.delete();
            tmp.mkdirs();
            setDirForTest(tmp.getAbsolutePath());
            synchronized (LOCK) { FACTS.clear(); firstSeen = 0L; lastSeen = 0L; talks = 0L; }

            // ① 记住 → 事实进内存且落盘
            remember("喜欢猫");
            remember("住在杭州");
            n++; if (factCount() == 2 && fileSize(factsPath()) > 0) ok++;
            else log.append("  ✗ 记住后内存/落盘不对\n");

            // ② 落盘格式带自制分隔符、可往返（不依赖 org.json）
            String line = "123" + SEP + "测试";
            Fact back = parseFact(line);
            n++; if (back != null && back.t == 123 && "测试".equals(back.text)) ok++;
            else log.append("  ✗ 事实编解码往返失败\n");

            // ③ brief 里必须出现"认识…天"与事实原文（提示词唯一出处）。
            //    ★ 不断言"聊过"：没聊过时 brief 本来就不写它（只有 remember 不算聊天）。
            String br = brief();
            n++; if (br.contains("认识对方第") && br.contains("喜欢猫")) ok++;
            else log.append("  ✗ brief 没带上关系/事实：" + br + "\n");

            // ④ 换行/分隔符会被清洗（不许把文件格式搞坏）
            remember("带\n换行\u001F与分隔符");
            n++; if (factCount() == 3 && fileSize(factsPath()) > 0) ok++;
            else log.append("  ✗ 含控制字符的事实写入后计数不对\n");

            // ⑤ 计数：聊一轮 talks 涨、first_seen 落定
            long t0 = talks;
            noteTalk();
            n++; if (talks == t0 + 1 && firstSeen > 0 && lastSeen > 0) ok++;
            else log.append("  ✗ noteTalk 没更新计数\n");

            // ⑥ ★★ 忘记 facts：文件必须真被删空（回读字节 = 0），ok 由证据算出来
            String fr = forget("facts");
            n++; if (fr.contains("\"ok\":true") && factCount() == 0 && fileSize(factsPath()) == 0) ok++;
            else log.append("  ✗ 忘记 facts 未真清空：" + fr + "\n");

            // ⑦ 忘记 facts 后 brief 必须**不再带任何旧事实**（★ 关系计数可以留 —— 忘记"事"
            //   不等于抹掉"我们认识多久"，那是两码事，所以这里只断言事实没了，不要求"刚认识"）
            String b7 = brief();
            n++; if (!b7.contains("喜欢猫") && !b7.contains("杭州") && !b7.contains("分隔符")) ok++;
            else log.append("  ✗ 忘记后 brief 仍带旧事实：" + b7 + "\n");

            // ⑧ all：计数归零 + 事实文件为空
            String ar = forget("all");
            long fs, ls, tk;
            synchronized (LOCK) { fs = firstSeen; ls = lastSeen; tk = talks; }
            n++; if (ar.contains("\"ok\":true") && fs == 0 && ls == 0 && tk == 0 && factCount() == 0) ok++;
            else log.append("  ✗ 忘记 all 未把计数归零：" + ar + "\n");

            // ⑨ 非法 scope 如实拒绝
            n++; if (forget("nonsense").contains("\"ok\":false")) ok++;
            else log.append("  ✗ 非法 scope 未拒绝\n");

            // ⑩ 空文本不许被当成一条记忆
            n++; if (remember("   ").contains("\"ok\":false")) ok++;
            else log.append("  ✗ 空文本被记下\n");

            // ⑪ ★ remember 不许把"聊过几次"灌水（它只更新首/末次时间）——
            //    这条是 2026-10-11 真机验收抓出来的：remember 若也 +1，AI 那句"记住…"
            //    会被记成两次对话（对话通路一次 + remember 一次）。
            long tk1 = talks;
            remember("只记事实不算聊天");
            n++; if (talks == tk1 && firstSeen > 0) ok++;
            else log.append("  ✗ remember 把 talks 灌水了：talks " + tk1 + " -> " + talks + "\n");
        } catch (Throwable t) {
            log.append("  ✗ 自检异常：" + t + "\n");
            n++;
        } finally {
            // ★ 还原：目录 + 全局状态
            setDirForTest(svDir);
            if (tmp != null) {
                try {
                    File[] cs = tmp.listFiles();
                    if (cs != null) for (File c : cs) c.delete();
                    tmp.delete();
                } catch (Throwable ignored) {}
            }
            synchronized (LOCK) {
                FACTS.clear();
                FACTS.addAll(svFacts);
                talks = svT; firstSeen = svF; lastSeen = svL;
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("长期记忆自检 ").append(ok).append('/').append(n).append(ok == n ? " PASS" : " FAIL");
        if (ok != n) out.append('(').append(n - ok).append(')');
        out.append('\n');
        if (log.length() > 0) out.append(log);
        out.append("上限：").append(MAX_FACTS).append(" 条 / 单条 ").append(MAX_FACT_LEN).append(" 字\n");
        return out.toString();
    }

    /** 供别的类做"今天"的显示（纯 JVM 也能用）。 */
    static String dateOf(long ms) {
        try { return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(new Date(ms)); }
        catch (Throwable t) { return ""; }
    }
}
