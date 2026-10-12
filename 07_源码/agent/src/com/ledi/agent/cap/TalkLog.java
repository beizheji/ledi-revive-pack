package com.ledi.agent.cap;

import com.ledi.agent.core.J;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * 机器人端<b>长期对话日志</b> + 给大模型用的<b>多轮记忆</b>（v93）。
 *
 * <h3>它解决两个问题</h3>
 * <ol>
 *   <li><b>大模型没有记忆</b>。此前 {@link AiCore#askDirect} 每次只发
 *       {@code system + 一条 user}，上一轮说了什么它完全不知道 —— 现场表现是
 *       "机器人每句都像第一次见我"。这里把每轮对话落盘，并在组请求时回填最近若干轮。</li>
 *   <li><b>两边各记一半，对不上账</b>。电脑端有 {@code dist/captures/dialog-YYYYMMDD.jsonl}
 *       （网关/控制台那一侧），机器人这边此前**什么都不留** —— 机器人走直连时
 *       （自己有 key 直接问大模型，完全不经过电脑）电脑端只有机器人主动推的那一份，
 *       而一旦没网/没连电脑就一寸证据都没有。这里在机器人本地也落一份同构的流水，
 *       并给每条一个 {@code tid}；{@link AiCore#pushDialog} 推给电脑端时带**同一个 tid**
 *       ⇒ 两份日志可以按 tid 一对一对照（见 {@code _dialoglog.py}）。</li>
 * </ol>
 *
 * <h3>存哪</h3>
 * <pre>
 *   /sdcard/ledi_talk.jsonl                        翻账用（adb 直接拉）
 *   /data/data/com.ledi.agent/ledi_talk.jsonl      应用私有，防 sdcard 被清
 * </pre>
 * 每行一条 JSON；超过 {@link #MAX_BYTES} 就把当前文件轮转成 {@code .1}（只留一代，
 * 别把存储写爆 —— 与 {@link BootLog} 同一个纪律）。
 *
 * <h3>三条纪律</h3>
 * <ul>
 *   <li>写盘在调用线程同步做，但**只 append 一行**，代价可忽略；任何异常一律吞掉，
 *       绝不能因为"记日志"把应答拖慢或把服务搞崩（与 {@code pushDialog} 同一条纪律）。</li>
 *   <li>内存环是<b>唯一</b>读路径（历史、{@code GET /talk}）—— 不反复读文件。</li>
 *   <li>进大模型历史的只有 {@code ask}（用户原话）与 {@code say}（去掉标签的播报文本）。
 *       {@code VOICE_HINT}、方位提示这类"给模型的旁白"**不进去** —— 否则它们会被当成
 *       用户说过的话，越滚越脏。</li>
 * </ul>
 */
public final class TalkLog {

    private static final String FILE_NAME = "ledi_talk.jsonl";
    public static final String PATH_SDCARD = "/sdcard/" + FILE_NAME;

    /** 单文件上限；超过就轮转成 .1。512KB ≈ 数千轮，够用。 */
    private static final long MAX_BYTES = 512 * 1024;
    /** 内存里保留的条数（历史与 /talk 都从这里取）。 */
    private static final int RING_MAX = 400;
    /** 回填给大模型的默认规模：最多 12 轮、约 3000 字。 */
    private static final int DEF_PAIRS = 12;
    private static final int DEF_CHARS = 3000;

    private static final Object LOCK = new Object();
    private static final ArrayDeque<Rec> RING = new ArrayDeque<Rec>();
    private static volatile String appPath;
    private static volatile long total;          // 本次进程生命周期内累计写入条数
    private static volatile long lastErr;

    private TalkLog() {}

    /** 一条对话记录。字段名即落盘的 JSON 键。 */
    public static final class Rec {
        public long t;          // epoch ms
        public String tid = ""; // 互查 id（机器人端与电脑端同一条）
        public String via = ""; // voice | ask | proactive | gateway …
        public String ask = ""; // 用户原话（**干净**的，无旁白）
        public String say = ""; // 实际播报的文本（已剥离 [表情:…] 标签）
        public String reply = ""; // 大模型原始回复（含标签）
        public String engine = ""; // direct | gateway
        public String dir = "";   // 说话人的方位（声源定位/触摸推断）
        public String note = "";  // 补充说明（如"没识别到内容"）
        public long ms;           // 本轮端到端耗时

        String toJson() {
            StringBuilder sb = new StringBuilder(256);
            sb.append("{\"t\":").append(t)
              .append(",\"tid\":\"").append(J.esc(tid)).append('"')
              .append(",\"via\":\"").append(J.esc(via)).append('"')
              .append(",\"ask\":\"").append(J.esc(ask)).append('"')
              .append(",\"say\":\"").append(J.esc(say)).append('"')
              .append(",\"reply\":\"").append(J.esc(reply)).append('"')
              .append(",\"engine\":\"").append(J.esc(engine)).append('"');
            if (dir != null && !dir.isEmpty())
                sb.append(",\"dir\":\"").append(J.esc(dir)).append('"');
            if (note != null && !note.isEmpty())
                sb.append(",\"note\":\"").append(J.esc(note)).append('"');
            if (ms > 0) sb.append(",\"ms\":").append(ms);
            return sb.append('}').toString();
        }

        static Rec fromJson(String line) {
            try {
                JSONObject o = new JSONObject(line);
                Rec r = new Rec();
                r.t = o.optLong("t", 0);
                r.tid = o.optString("tid", "");
                r.via = o.optString("via", "");
                r.ask = o.optString("ask", "");
                r.say = o.optString("say", "");
                r.reply = o.optString("reply", "");
                r.engine = o.optString("engine", "");
                r.dir = o.optString("dir", "");
                r.note = o.optString("note", "");
                r.ms = o.optLong("ms", 0);
                return r;
            } catch (Throwable t) {
                return null;      // 坏行跳过，别让一行脏数据毁掉整个历史
            }
        }
    }

    /** AgentService.onCreate 里调一次：登记私有目录 + 把已有历史读回内存。 */
    public static void init(Context ctx) {
        try {
            if (ctx != null) {
                appPath = ctx.getFilesDir().getAbsolutePath() + "/" + FILE_NAME;
            }
        } catch (Throwable ignored) { }
        reload();
    }

    /** 互查 id：8 位十六进制。够短便于人眼对照，重叠概率对这个规模无所谓。 */
    public static String newTid() {
        long v = System.currentTimeMillis();
        int r = (int) (Math.random() * 0xFFFF);
        return String.format("%08x", ((v & 0xFFFFFFFFFFL) ^ ((long) r << 16)) & 0xFFFFFFFFL);
    }

    /**
     * 记一条，返回本次的 tid（调用方拿它去推电脑端，两份日志就能对上）。
     *
     * @param ask   用户原话（干净文本，没有旁白）
     * @param say   实际播报文本（已剥标签）
     * @param reply 大模型原始回复（可含标签）；与 say 二选一即可，say 优先做历史
     */
    public static String add(String via, String ask, String say, String reply,
                             String engine, String dir, long ms, String note) {
        Rec r = new Rec();
        r.t = System.currentTimeMillis();
        r.tid = newTid();
        r.via = via == null ? "" : via;
        r.ask = trim(ask, 600);
        r.say = trim(say, 600);
        r.reply = trim(reply, 600);
        r.engine = engine == null ? "" : engine;
        r.dir = dir == null ? "" : dir;
        r.note = trim(note, 200);
        r.ms = ms;
        try {
            synchronized (LOCK) {
                RING.addLast(r);
                while (RING.size() > RING_MAX) RING.pollFirst();
                total++;
            }
            write(r.toJson());
        } catch (Throwable t) {
            lastErr = System.currentTimeMillis();
        }
        return r.tid;
    }

    private static String trim(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** 回填给大模型的多轮历史片段（前面带逗号，直接拼进 messages 数组）。 */
    public static String historyFragment() {
        return historyFragment(DEF_PAIRS, DEF_CHARS);
    }

    /**
     * 选取最近若干轮**供大模型回填**的历史。
     *
     * <p>★ 收成一处：{@link #historyFragment}（拼字符串片段，给单轮 ask 用）与
     * {@link #historyMessages}（对象列表，给多轮思考用）必须**取到同样的那些轮**，
     * 否则两条记忆路的长短/过滤会慢慢分叉（本项目老教训："同一条规则散在两处，
     * 迟早对不上"）。这里只负责"选哪些"，编码交给各自的输出方法。</p>
     */
    private static ArrayList<Rec> pick(int maxPairs, int maxChars) {
        ArrayList<Rec> picked = new ArrayList<Rec>();
        int chars = 0;
        synchronized (LOCK) {
            Iterator<Rec> it = RING.descendingIterator();     // 从最新往回取
            while (it.hasNext()) {
                Rec r = it.next();
                if (r.ask == null || r.ask.isEmpty()) continue;   // 主动说话等没有用户话的，不进历史
                int len = r.ask.length() + (r.say == null ? 0 : r.say.length());
                if (chars + len > maxChars && !picked.isEmpty()) break;
                picked.add(r);
                chars += len;
                if (picked.size() >= maxPairs) break;
            }
        }
        return picked;
    }

    public static String historyFragment(int maxPairs, int maxChars) {
        ArrayList<Rec> picked = pick(maxPairs, maxChars);
        StringBuilder sb = new StringBuilder(512);
        for (int i = picked.size() - 1; i >= 0; i--) {           // 再正过来，保持时间顺序
            Rec r = picked.get(i);
            sb.append(",{\"role\":\"user\",\"content\":\"").append(J.esc(r.ask)).append("\"}");
            String a = (r.say != null && !r.say.isEmpty()) ? r.say : r.reply;
            if (a != null && !a.isEmpty())
                sb.append(",{\"role\":\"assistant\",\"content\":\"").append(J.esc(a)).append("\"}");
        }
        return sb.toString();
    }

    /**
     * 同 {@link #historyFragment()} 的**消息对象列表**版本 —— 给多轮思考（{@link Thinking}）用：
     * 它的 messages 是一个 List，拼不了字符串片段。与上者共用 {@link #pick}，选取规则一致。
     */
    public static java.util.List<String> historyMessages() {
        return historyMessages(DEF_PAIRS, DEF_CHARS);
    }

    public static java.util.List<String> historyMessages(int maxPairs, int maxChars) {
        ArrayList<Rec> picked = pick(maxPairs, maxChars);
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (int i = picked.size() - 1; i >= 0; i--) {
            Rec r = picked.get(i);
            out.add("{\"role\":\"user\",\"content\":\"" + J.esc(r.ask) + "\"}");
            String a = (r.say != null && !r.say.isEmpty()) ? r.say : r.reply;
            if (a != null && !a.isEmpty())
                out.add("{\"role\":\"assistant\",\"content\":\"" + J.esc(a) + "\"}");
        }
        return out;
    }

    /** 当前内存里可用的历史轮数（给 /talk/status 显示"记忆有多长"）。 */
    public static int pairs() {
        int n = 0;
        synchronized (LOCK) {
            for (Rec r : RING) if (r.ask != null && !r.ask.isEmpty()) n++;
        }
        return n;
    }

    /** 最近 n 条（原样 JSON），给 {@code GET /talk}。 */
    public static String tailJson(int n) {
        int keep = Math.max(1, Math.min(RING_MAX, n));
        StringBuilder sb = new StringBuilder(1024);
        int i = 0, skip;
        ArrayList<Rec> snap;
        synchronized (LOCK) {
            snap = new ArrayList<Rec>(RING);
            skip = Math.max(0, snap.size() - keep);
        }
        sb.append("{\"count\":").append(total).append(",\"kept\":").append(snap.size())
          .append(",\"file\":\"").append(J.esc(path())).append("\",\"items\":[");
        for (Rec r : snap) {
            if (i++ < skip) continue;
            sb.append(r.toJson()).append(',');
        }
        if (sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        return sb.append("]}").toString();
    }

    public static String statusJson() {
        Rec last;
        int kept;
        synchronized (LOCK) {
            last = RING.peekLast();
            kept = RING.size();
        }
        return "{\"file\":\"" + J.esc(path()) + "\""
                + ",\"count\":" + total
                + ",\"kept\":" + kept
                + ",\"pairs\":" + pairs()
                + ",\"max_bytes\":" + MAX_BYTES
                + ",\"last\":\"" + J.esc(last == null ? "" : (last.via + " " + last.ask
                        + " → " + (last.say == null || last.say.isEmpty() ? last.reply : last.say))) + "\""
                + ",\"last_json\":" + (last == null ? "null" : last.toJson())
                + ",\"err\":" + (lastErr > 0)
                + "}";
    }

    /** 只清内存环，磁盘 jsonl 留给翻账（与电脑端 DialogLog.clear 同一语义）。 */
    public static String clear() {
        synchronized (LOCK) {
            RING.clear();
        }
        return "{\"ok\":true}";
    }

    /**
     * ★ v214：**真删** —— 内存环 + {@code total} + 两个落盘文件（含 {@code .1} 轮转件），
     * 并把"到底删干净没有"用**回读字节**证明出来。
     *
     * <p>为什么与 {@link #clear()} 分开：{@code clear()} 只清内存环、磁盘留着翻账
     * （那是"看账"的语义）；而用户要的"让机器人忘记记忆"是**真的抹掉**。两者混在一个
     * 方法里，就会出现"以为删了、其实还在"的假绿灯 —— 所以给"忘记"单开一条，且返回值里的
     * {@code ok} 由 {@code bytes_after} 算出来，不是写死的常量。</p>
     */
    public static String wipe() {
        long b1 = sizeOf(PATH_SDCARD);
        long b2 = appPath == null ? 0L : sizeOf(appPath);
        synchronized (LOCK) {
            RING.clear();
            total = 0;
        }
        boolean ok1 = deleteFile(PATH_SDCARD);
        boolean ok2 = appPath == null || deleteFile(appPath);
        long a1 = sizeOf(PATH_SDCARD);
        long a2 = appPath == null ? 0L : sizeOf(appPath);
        boolean ok = ok1 && ok2 && a1 == 0 && a2 == 0 && pairs() == 0;
        return "{\"ok\":" + ok
                + ",\"bytes_before\":" + (b1 + b2)
                + ",\"bytes_after\":" + (a1 + a2)
                + ",\"pairs\":" + pairs()
                + ",\"file\":\"" + J.esc(path()) + "\"}";
    }

    private static long sizeOf(String p) {
        try { File f = new File(p); return f.exists() ? f.length() : 0L; } catch (Throwable t) { return 0L; }
    }

    private static boolean deleteFile(String p) {
        try {
            File f = new File(p);
            boolean ok = !f.exists() || f.delete();
            File r = new File(p + ".1");     // 轮转件也是记忆的一部分
            if (r.exists()) r.delete();
            return ok;
        } catch (Throwable t) { return false; }
    }

    public static String path() {
        return appPath != null ? appPath : PATH_SDCARD;
    }

    // ------------------------------------------------------------ 磁盘

    private static void write(String line) {
        String s = line + "\n";
        append(PATH_SDCARD, s);
        if (appPath != null) append(appPath, s);
    }

    private static void append(String path, String s) {
        try {
            File f = new File(path);
            if (f.exists() && f.length() > MAX_BYTES) rotate(f);
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write(s.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
            // 权限/存储满就悄悄放弃 —— 日志绝不能成为服务崩溃的理由
        }
    }

    /** 轮转：当前 → {@code .1}（只留一代）。 */
    private static void rotate(File f) {
        try {
            File bak = new File(f.getAbsolutePath() + ".1");
            if (bak.exists()) {
                //noinspection ResultOfMethodCallIgnored
                bak.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            f.renameTo(bak);
        } catch (Throwable ignored) { }
    }

    /** 启动时把已有历史读回内存环（读不到就是空的，不报错）。 */
    private static void reload() {
        String[] cands = appPath == null
                ? new String[]{PATH_SDCARD}
                : new String[]{appPath, PATH_SDCARD};
        for (String p : cands) {
            try {
                File f = new File(p);
                if (!f.exists() || f.length() <= 0) continue;
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(new FileInputStream(f), "UTF-8"));
                ArrayDeque<Rec> tmp = new ArrayDeque<Rec>();
                String line;
                int n = 0;
                while ((line = br.readLine()) != null) {
                    if (line.length() < 8) continue;
                    Rec r = Rec.fromJson(line);
                    if (r == null) continue;
                    tmp.addLast(r);
                    while (tmp.size() > RING_MAX) tmp.pollFirst();
                    n++;
                }
                br.close();
                synchronized (LOCK) {
                    RING.clear();
                    RING.addAll(tmp);
                    total = n;
                }
                return;      // 第一个能读的就算数
            } catch (Throwable ignored) {
                // 换下一个候选
            }
        }
    }
}
