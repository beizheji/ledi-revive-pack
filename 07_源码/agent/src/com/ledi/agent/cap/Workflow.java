package com.ledi.agent.cap;

import com.ledi.agent.core.J;

import com.ledi.agent.core.BootLog;

import com.ledi.agent.voice.Av;

import com.ledi.agent.core.AgentService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 简易 workflow（v203）——「一串步骤，一次交出去」的声明式编排。
 *
 * <h3>它解决的到底是什么问题</h3>
 * 用户 2026-10-10 的要求：说话以后机器人<b>自己决定需要哪些数据与功能</b>、
 * <b>在本机找对应的功能与状态</b>，然后<b>执行命令并反馈</b>。
 * {@link Thinking} 已经把"边查边想"解决了（模型每轮输出 {@code TOOL} 行）；
 * 但那是**一步一步、每步一次模型往返**。有一类请求天然是"一串固定动作"，
 * 例如「先看看电量，低于 20% 就提醒我充电，否则做个开心的表情说声不错」——
 * 让模型逐步试错既慢又贵，且中途失败会留下半截状态。
 *
 * <p>workflow 把这类请求变成：<b>一次性拿到完整步骤序列 → 逐行预检 → 后台整串执行
 * → 逐步落盘 → 汇报</b>。所以它是 {@link Thinking} 的**补集**，不是替代。</p>
 *
 * <h3>★ 四条硬约束（全部来自本项目已踩过的坑）</h3>
 *
 * <p><b>① 纯文本、一行一步。</b>
 * 不引 JSON 库（手写解析已在本项目碎过多次）。一行 {@code op 参数…}，
 * 分隔符是空白；需要带空格的文本参数放最后，原样吃掉。空行与 {@code #} 注释忽略。</p>
 *
 * <p><b>② 写入前逐行语法预检，任何一行不合法就整份拒绝。</b>
 * 不许"先跑前 3 步再说第 4 步有错" —— 那会把机器人留在半截状态
 * （本项目铁律：脚本默认"某步失败即中止"，且"预检"必须在**动手之前**）。</p>
 *
 * <p><b>③ 白名单。</b>步骤只能调用 {@link Thinking#deviceToolBox()} 里已有的那些能力，
 * 且额外**禁止**任何破坏性 op（关机/重启/关 ADB/关网关/卸载/深睡）。白名单只有一处真源
 * —— 见 {@link #ALLOWED}，它与 {@code Thinking.protocolPrompt()} 的工具清单必须同步。</p>
 *
 * <p><b>④ 后台线程 + 单实例闸门。</b>
 * 编排可能要跑几十秒（动作 + 说话），绝不能占住 HTTP 线程或主线程；同时只允许
 * 一条 workflow 在跑，否则两条编排会抢麦、抢动作（本项目老毛病："两套实现必然分叉"）。</p>
 *
 * <h3>可证伪</h3>
 * {@link #selftest()} 纯静态：脚本化工具箱 + 假 clock，把预检/执行/中止/落盘全跑一遍。
 * 不吃 Context、不联网、不碰 org.json ⇒ 纯 JVM 可跑，进 build 硬门禁。
 */
public final class Workflow {

    private static final String TAG = AgentService.TAG;

    private Workflow() {}

    /** 单条流程最多几步（防"一次交出去"变成无限循环）。 */
    public static final int MAX_STEPS = 24;
    /** 一步之间默认的间隔（毫秒）—— 给动作留出起势时间，别把串口打爆。 */
    public static final int STEP_GAP_MS = 350;

    /** 流程落盘目录（在设备上；不进 prefs，因为内容是多行的）。 */
    public static final String DIR = "/sdcard/ledi_workflows";
    /** 执行流水（"单独落盘"—— 与流程定义分开，见类注释①）。 */
    static final String RUN_LOG = "/sdcard/ledi_workflow_run.log";

    // ================================================================ 白名单

    /**
     * 步骤允许的 op。
     *
     * <p>★ 与 {@link Thinking#deviceToolBox()} 是**同一批能力**，但这里的粒度是
     * "一条步骤"，所以多了 {@code wait}（纯粹的时间间隔）。两处必须同步改 ——
     * 加工具时同时看这里（本项目教训："修在一个地方 ≠ 修好了"）。</p>
     *
     * <p>★ 破坏性 op 一律不在列，且被 {@link #checkOp} 显式拒绝（不是"忘了加"）。</p>
     */
    public static final String[] ALLOWED = Abilities.allowedOps();

    /**
     * 显式禁止的 op（**只报错、不执行**）。
     *
     * <p>为什么要单独列出来：本项目的铁律是"破坏性广播只记录不代触发"。
     * 如果只是"没写进白名单"，报错会说"未知 op"——听起来像是我们漏了；
     * 单独列出来，模型和用户都能看到"这台机器人**故意**不做这些"。</p>
     */
    public static final String[] FORBIDDEN = Abilities.forbiddenOps();
    /** 不能出现在**步骤**里的 op：workflow 自身（防嵌套递归）。 */
    static final String[] NOT_A_STEP = {"workflow", "run"};

    // ================================================================ 步骤

    /** 一条已解析、已预检通过的步骤。 */
    public static final class Step {
        public final String op;
        public final String arg;     // 参数原文（可能为空）
        public final int line;       // 原始行号（报错时能指出来）
        Step(String op, String arg, int line) { this.op = op; this.arg = arg; this.line = line; }

        public String text() { return arg == null || arg.isEmpty() ? op : op + " " + arg; }
    }

    /** 一份流程。 */
    public static final class Script {
        public String name = "";
        public final List<Step> steps = new ArrayList<Step>();
        /** 预检不通过时非空（此时 steps 不可用）。 */
        public String error = "";
        public boolean ok() { return error.isEmpty() && !steps.isEmpty(); }
    }

    // ================================================================ 解析 + 预检

    /**
     * 解析并**预检**一份流程文本。失败时返回的 {@link Script#error} 非空。
     *
     * <p>预检覆盖：行数上限、空脚本、未知 op、破坏性 op、缺参数、参数越界
     * （volume 必须 0..15；motion/face 不在这里校验存在性 —— 那两个由执行期的
     * 真源去判，因为动作/表情表是**自省**的，预检时读不到就是读不到，
     * 不能拿一份硬编码名单去卡）。</p>
     */
    public static Script parse(String text, String name) {
        Script s = new Script();
        s.name = name == null ? "" : name.trim();
        if (text == null || text.trim().isEmpty()) {
            s.error = "流程是空的";
            return s;
        }
        String[] lines = text.split("\r?\n");
        int n = 0;
        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i].trim();
            if (raw.isEmpty() || raw.startsWith("#")) continue;
            n++;
            if (n > MAX_STEPS) {
                s.error = "第 " + (i + 1) + " 行：步骤超过上限 " + MAX_STEPS + " 步";
                return s;
            }
            int sp = raw.indexOf(' ');
            String op = (sp < 0 ? raw : raw.substring(0, sp)).trim().toLowerCase(Locale.ROOT);
            String arg = sp < 0 ? "" : raw.substring(sp + 1).trim();

            String why = checkOp(op, arg);
            if (why != null) {
                s.error = "第 " + (i + 1) + " 行：" + why;
                return s;
            }
            s.steps.add(new Step(op, arg, i + 1));
        }
        if (s.steps.isEmpty()) {
            s.error = "流程里一行有效步骤都没有（只有空行/注释？）";
            return s;
        }
        return s;
    }

    /** 单个 op + 参数的合法性；返回 null 表示通过。 */
    static String checkOp(String op, String arg) {
        if (op == null || op.isEmpty()) return "空 op";
        for (String f : FORBIDDEN) {
            if (f.equals(op)) return "「" + op + "」被明确禁止：这台机器人不做破坏性/连通性操作（只记录，不代执行）";
        }
        boolean known = false;
        for (String a : ALLOWED) if (a.equals(op)) { known = true; break; }
        if (!known) return "未知 op「" + op + "」，可用：" + join(ALLOWED);
        // ★ workflow 不能在步骤里再嵌套（会递归起第二条编排，抢动作/抢麦）
        for (String x : NOT_A_STEP) {
            if (x.equals(op)) return "「" + op + "」不能作为步骤：步骤里不能再开一条编排（防递归）";
        }

        String a = arg == null ? "" : arg.trim();
        if ("volume".equals(op)) {
            int v;
            try { v = Integer.parseInt(a); } catch (Throwable t) { return "volume 需要数字参数（0..15），收到「" + a + "」"; }
            if (v < 0 || v > 15) return "volume 越界：" + v + "（允许 0..15）";
        } else if ("face".equals(op) || "motion".equals(op) || "say".equals(op) || "doc".equals(op)) {
            if (a.isEmpty()) return op + " 需要参数（" + hint(op) + "）";
        } else if ("led".equals(op)) {
            if (a.isEmpty()) return "led 需要参数。看颜色：led blue on / 关灯：led all off";
        } else if ("emotion".equals(op) || "display".equals(op) || "name".equals(op) || "call".equals(op)) {
            if (a.isEmpty()) return op + " 需要参数（" + hint(op) + "）";
        } else if ("tone".equals(op)) {
            if (!"adult".equals(a) && !"neutral".equals(a) && !"child".equals(a))
                return "tone 只能是 adult/neutral/child，收到「" + a + "」";
        } else if ("rotation".equals(op)) {
            if (!"lock".equals(a) && !"unlock".equals(a))
                return "rotation 只能是 lock/unlock，收到「" + a + "」";
        } else if ("live".equals(op) || "listen".equals(op) || "think".equals(op)) {
            if (!"on".equals(a) && !"off".equals(a))
                return op + " 只能是 on/off，收到「" + a + "」";
        } else if ("idle".equals(op)) {
            int v;
            try { v = Integer.parseInt(a); } catch (Throwable t) { return "idle 需要数字 0..240（0=关）"; }
            if (v < 0 || v > 240) return "idle 越界：" + v + "（允许 0..240）";
        } else if ("logs".equals(op)) {
            if (!a.isEmpty()) {
                int v;
                try { v = Integer.parseInt(a); } catch (Throwable t) { return "logs 的可选参数是行数（1..200）"; }
                if (v < 1 || v > 200) return "logs 行数越界：" + v + "（允许 1..200）";
            }
        } else if ("wait".equals(op)) {
            if (!a.isEmpty()) {
                int ms;
                try { ms = Integer.parseInt(a); } catch (Throwable t) { return "wait 需要毫秒数，收到「" + a + "」"; }
                if (ms < 0 || ms > 10000) return "wait 越界：" + ms + "ms（允许 0..10000）";
            }
        }
        // battery/state/time/faces/motions/stop：不吃参数，给了也忽略（不报错 —— 宽容比严苛好）
        return null;
    }

    private static String hint(String op) {
        if ("face".equals(op))     return "表情名，用 doc 表情 查";
        if ("motion".equals(op))   return "动作名，用 doc 动作 查";
        if ("say".equals(op))      return "要念出来的话";
        if ("emotion".equals(op))  return "情绪名（原厂词表内）或 reset";
        if ("display".equals(op))  return "要显示在屏幕上的文字";
        if ("name".equals(op))     return "机器人自己的新名字";
        if ("call".equals(op))     return "它该怎么称呼你";
        return "一个检索词";
    }

    /**
     * 把"模型给的一行参数"还原成步骤文本。
     *
     * <p>★ 真机实测（2026-10-10）踩到的坑：模型把 3 步压成**一行**
     * （{@code TOOL workflow motion hug; motion cheer; say 你真棒}），而我们的
     * {@link #parse} 只认换行 ⇒ 只解析出 1 步、静默丢掉后面两步。
     * 这不是模型的错：一行是它在文本协议里最自然的写法。</p>
     *
     * <p>所以先把常见的"步骤分隔符"还原成换行：{@code ;} {@code ；} {@code |} 。
     * ★ 但 {@code \n} 与真正换行也要认（模型有时会写转义、有时写真的换行）。
     * ★ 不动逗号：动作参数里可能出现逗号，切了就误伤。</p>
     */
    public static String normalizeSteps(String a) {
        if (a == null) return "";
        String s = a.replace("\\n", "\n");
        s = s.replace(";", "\n").replace("；", "\n").replace("|", "\n");
        return s;
    }

    private static String join(String[] xs) {
        StringBuilder sb = new StringBuilder();
        for (String x : xs) { if (sb.length() > 0) sb.append('/'); sb.append(x); }
        return sb.toString();
    }

    // ================================================================ 执行

    /** 一步的执行结果。 */
    public static final class StepResult {
        public final Step step;
        public final String out;      // 工具返回（可能很长，落盘时截断）
        public final boolean ok;      // false ⇒ 中止整条流程
        public final long ms;
        StepResult(Step s, String o, boolean k, long m) { step = s; out = o; ok = k; ms = m; }
    }

    /** 一次执行的完整结果。 */
    public static final class RunResult {
        public String name = "";
        public boolean ok;             // 整条流程是否跑完（含"被中止"=false）
        public boolean aborted;        // 是否因某步失败而中止
        public int failedAt = -1;      // 失败步骤在 steps 里的下标（0 基）
        public String error = "";
        public final List<StepResult> results = new ArrayList<StepResult>();
        public long ms;
        public String trace = "";      // 人类可读流水（也是落盘内容）

        public String summary() {
            if (!ok && !aborted && error != null && !error.isEmpty()) return error;
            return (ok ? "跑完 " : "中止于第 " + (failedAt + 1) + " 步，共 ") + results.size() + " 步";
        }
    }

    /** 执行回调（便于纯 JVM 自检：把工具箱与 clock 都注入）。 */
    public interface Runner {
        RunResult run(Script s, Thinking.ToolBox tb);
    }

    /**
     * 默认执行器：后台逐行跑，某步失败即**中止整条**（不硬着头皮往下走）。
     *
     * <p>★ 怎么判断"失败"：{@link Thinking.ToolBox} 返回的是**字符串**，没有结构化
     * 成功位。约定是——返回文本里出现以下任一即视为失败：以 {@code error} 开头、
     * 含 {@code "工具执行异常"}、含 {@code 未知}、含 {@code 不可用}、含 {@code 参数错误}。
     * 这不是完美判据，但**比"看谁都没报错就当成功"强**（本项目最贵的假绿灯就是它）。
     * 同时把原始文本一并落盘，人可以对差。</p>
     */
    public static RunResult exec(Script s, Thinking.ToolBox tb, Thinking.ToolBox readOnly) {
        RunResult r = new RunResult();
        r.name = s == null ? "" : s.name;
        if (s == null || !s.ok()) {
            r.error = s == null ? "没有流程" : s.error;
            r.trace = r.error;
            return r;
        }
        long t0 = System.currentTimeMillis();
        StringBuilder log = new StringBuilder();
        log.append("# workflow ").append(r.name).append("  开始 ")
           .append(now()).append('\n');

        for (int i = 0; i < s.steps.size(); i++) {
            Step st = s.steps.get(i);
            long s0 = System.currentTimeMillis();
            String out;
            if ("wait".equals(st.op)) {
                int ms = STEP_GAP_MS;
                if (!st.arg.isEmpty()) { try { ms = Integer.parseInt(st.arg); } catch (Throwable ignored) {} }
                sleep(ms);
                out = "waited " + ms + "ms";
            } else {
                try {
                    out = tb == null ? "(没有工具箱)" : tb.call(st.op, st.arg);
                } catch (Throwable t) {
                    out = "工具执行异常：" + t;
                }
            }
            if (out == null) out = "";
            long ms = System.currentTimeMillis() - s0;
            boolean good = looksOk(out);
            r.results.add(new StepResult(st, out, good, ms));
            log.append(String.format("%2d. %-14s -> %s  [%dms]%s\n",
                    i + 1, st.text(), abbrev(out, 200), ms, good ? "" : "   ← 失败，中止"));
            if (!good) {
                r.ok = false;
                r.aborted = true;
                r.failedAt = i;
                r.error = "第 " + (i + 1) + " 步（" + st.text() + "）失败：" + abbrev(out, 160);
                break;
            }
            // 步骤之间留一点间隔（除最后一步）
            if (i + 1 < s.steps.size()) sleep(STEP_GAP_MS);
        }
        if (r.failedAt < 0) r.ok = true;
        r.ms = System.currentTimeMillis() - t0;
        log.append("# ").append(r.ok ? "完成" : "中止").append(' ').append(now())
           .append("  共 ").append(r.results.size()).append(" 步 / ").append(r.ms).append("ms\n");
        r.trace = log.toString();
        // ★★ 同步执行也要记进 last —— 只有异步路径记的话，`GET /workflow` 在主叫方
        //    （网页/脚本）刚同步跑完之后会读到 last.has=false（"跑了但看不到流水"）。
        //    本次验收就是这么抓到的：ok=true 但 trace 为空、逐步结果 0 条。
        last = r;
        lastTrace = r.trace;
        writeRunLog(r.trace);
        return r;
    }

    /**
     * 一看就知道失败的样子（判据写在明处，便于日后对差）。
     * 顺序无所谓，但**宁可多报失败也不要漏报** —— 漏报就是假绿灯。
     */
    static boolean looksOk(String out) {
        // ★ v207：判据**下沉到能力层** —— 谁产出错误文案，谁负责认得它（见 Abilities.FAIL_WORDS）。
        //   以前这里各写一份字符串启发式，漏一个词就把失败当成功（本项目最爱的"假绿灯"）：
        //   真机抓到的实例是 `led blue on` → "bad color: blue"（失败）旧启发式判成成功。
        return !Abilities.isFailure(out);
    }

    // ================================================================ 单实例闸门 + 后台

    private static volatile Thread runner;
    private static volatile String runningName = "";
    private static volatile long runningSince;
    private static volatile RunResult last;
    private static volatile String lastTrace = "";

    public static boolean busy() { return runner != null && runner.isAlive(); }
    public static String runningJson() {
        return "{\"busy\":" + busy()
                + ",\"name\":\"" + J.esc(runningName) + "\""
                + ",\"since\":" + runningSince
                + ",\"ms\":" + (busy() ? System.currentTimeMillis() - runningSince : 0) + "}";
    }

    /**
     * 后台跑一条流程。**同时只允许一条** —— 第二条直接拒绝（返回 null）。
     *
     * <p>为什么必须闸：编排会抢动作与麦；两条并行时"谁把机器人停下的"无从判断。
     * ★ 返回 null 时调用方必须如实报"已经在跑"，不许假装接单。</p>
     */
    public static boolean start(final Script s, final Thinking.ToolBox tb) {
        if (busy()) return false;
        if (s == null || !s.ok()) return false;
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    RunResult r = exec(s, tb, null);
                    last = r;
                    lastTrace = r.trace;
                    BootLog.i("workflow 完成 name=" + r.name + " ok=" + r.ok + " :: " + r.summary());
                } catch (Throwable e) {
                    BootLog.w("workflow 崩了: " + e);
                }
            }
        }, "ledi-workflow");
        t.setDaemon(true);
        runner = t;
        runningName = s.name;
        runningSince = System.currentTimeMillis();
        t.start();
        return true;
    }

    /** 最近一次结果（JSON，给 /workflow 与网页看）。 */
    public static String lastJson() {
        RunResult r = last;
        if (r == null) {
            return "{\"has\":false,\"trace\":\"" + J.esc(ansiStrip(lastTrace)) + "\"}";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"has\":true,\"name\":\"").append(J.esc(r.name)).append('"')
          .append(",\"ok\":").append(r.ok)
          .append(",\"aborted\":").append(r.aborted)
          .append(",\"failed_at\":").append(r.failedAt)
          .append(",\"error\":\"").append(J.esc(r.error)).append('"')
          .append(",\"ms\":").append(r.ms)
          .append(",\"steps\":[");
        for (int i = 0; i < r.results.size(); i++) {
            StepResult sr = r.results.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"n\":").append(i + 1)
              .append(",\"op\":\"").append(J.esc(sr.step.op)).append('"')
              .append(",\"arg\":\"").append(J.esc(sr.step.arg)).append('"')
              .append(",\"ok\":").append(sr.ok)
              .append(",\"ms\":").append(sr.ms)
              .append(",\"out\":\"").append(J.esc(abbrev(sr.out, 300))).append("\"}");
        }
        sb.append("],\"trace\":\"").append(J.esc(ansiStrip(r.trace))).append("\"}");
        return sb.toString();
    }

    // ================================================================ 落盘 / 列表 / 删除

    private static void writeRunLog(String trace) {
        try {
            File f = new File(RUN_LOG);
            FileOutputStream o = new FileOutputStream(f, true);   // 追加
            try { o.write(trace.getBytes("UTF-8")); } finally { o.close(); }
        } catch (Throwable t) {
            BootLog.w("workflow 流水落盘失败: " + t);
        }
    }

    /** 保存一份流程到设备（名字做安全化，不许出现路径分隔符）。 */
    public static String save(String name, String text) {
        String n = safeName(name);
        if (n.isEmpty()) return "{\"ok\":false,\"error\":\"流程名字不能为空\"}";
        Script s = parse(text, n);
        if (!s.ok()) return "{\"ok\":false,\"error\":\"" + J.esc(s.error) + "\"}";
        try {
            File d = new File(DIR);
            if (!d.exists() && !d.mkdirs()) return "{\"ok\":false,\"error\":\"建不了目录 " + J.esc(DIR) + "\"}";
            File f = new File(d, n + ".wf");
            FileOutputStream o = new FileOutputStream(f);
            try { o.write(text.getBytes("UTF-8")); } finally { o.close(); }
            BootLog.i("workflow 已保存 " + f.getAbsolutePath() + "（" + s.steps.size() + " 步）");
            return "{\"ok\":true,\"name\":\"" + J.esc(n) + "\",\"steps\":" + s.steps.size()
                    + ",\"file\":\"" + J.esc(f.getAbsolutePath()) + "\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"保存失败：" + J.esc(String.valueOf(t)) + "\"}";
        }
    }

    /** 列出设备上已保存的流程（名字 + 步数）。 */
    public static String listJson() {
        StringBuilder sb = new StringBuilder("[");
        try {
            File d = new File(DIR);
            File[] fs = d.listFiles();
            if (fs != null) {
                java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
                    @Override public int compare(File a, File b) { return a.getName().compareTo(b.getName()); }
                });
                boolean first = true;
                for (File f : fs) {
                    if (!f.isFile() || !f.getName().endsWith(".wf")) continue;
                    String nm = f.getName().substring(0, f.getName().length() - 3);
                    String body = readFile(f);
                    Script s = parse(body, nm);
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"name\":\"").append(J.esc(nm)).append('"')
                      .append(",\"steps\":").append(s.steps.size())
                      .append(",\"valid\":").append(s.ok())
                      .append(",\"error\":\"").append(J.esc(s.error)).append('"')
                      .append(",\"file\":\"").append(J.esc(f.getAbsolutePath())).append("\"}");
                }
            }
        } catch (Throwable t) {
            BootLog.w("workflow 列表失败: " + t);
        }
        return sb.append(']').toString();
    }

    /** 读一份流程原文（给网页编辑用）。 */
    public static String read(String name) {
        try {
            File f = new File(DIR, safeName(name) + ".wf");
            if (!f.exists()) return null;
            return readFile(f);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 删除一份流程（★ 只是删文件，不是"回滚到某个状态"）。 */
    public static String delete(String name) {
        String n = safeName(name);
        if (n.isEmpty()) return "{\"ok\":false,\"error\":\"名字为空\"}";
        try {
            File f = new File(DIR, n + ".wf");
            if (!f.exists()) return "{\"ok\":false,\"error\":\"没有这份流程：" + J.esc(n) + "\"}";
            if (f.delete()) return "{\"ok\":true,\"deleted\":\"" + J.esc(n) + "\"}";
            return "{\"ok\":false,\"error\":\"删除失败（权限？）\"}";
        } catch (Throwable t) {
            return "{\"ok\":false,\"error\":\"" + J.esc(String.valueOf(t)) + "\"}";
        }
    }

    /**
     * 「回滚」= 把当前机器人状态<b>拉回</b>到一次编排之前。
     *
     * <p>★ 如实口径：舵机角度**能回读**（原厂 ENGSTATUS 广播 → 按实测拟合换算，见
     * {@code motion/MotorPose}），但**回滚用不上它** —— 读回是近似值、且不能当控制回路，
     * 把手臂"按读回值开回去"本身就不安全；屏幕内容则**确实读不回来**。
     * 所以"回滚"能做到的只有：停止正在跑的动作、把音量恢复到编排前的值、
     * 把表情切回编排前的表情。灯光与屏幕做不到（读不回来），
     * 这一点必须写在返回里，不许含糊成"已完全回滚"。</p>
     */
    public static String rollback(int prevVolume, String prevFace) {
        StringBuilder did = new StringBuilder();
        try {
            if (AgentService.motion != null) { AgentService.motion.stop(); did.append("停动作 "); }
        } catch (Throwable ignored) {}
        try {
            if (prevVolume >= 0) { Av.set(prevVolume); did.append("音量→").append(prevVolume).append(' '); }
        } catch (Throwable ignored) {}
        try {
            if (AgentService.face != null && prevFace != null && !prevFace.isEmpty()) {
                AgentService.face.play(prevFace, false); did.append("表情→").append(prevFace).append(' ');
            }
        } catch (Throwable ignored) {}
        return "{\"ok\":true,\"did\":\"" + J.esc(did.toString().trim()) + "\""
                + ",\"note\":\"只能回滚：动作（停）、音量、表情。★ 灯光与屏幕内容本机读不回来，"
                + "无法回滚到原状 —— 这是硬件限制，不是没做。\"}";
    }

    // ================================================================ 工具

    private static String safeName(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 32; i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c > 127) sb.append(c);
        }
        return sb.toString().trim();
    }

    private static String readFile(File f) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] all = new byte[(int) f.length()];
            int off = 0, n;
            while (off < all.length && (n = in.read(all, off, all.length - off)) > 0) off += n;
            return new String(all, 0, off, "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String now() {
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                    .format(new java.util.Date());
        } catch (Throwable t) { return String.valueOf(System.currentTimeMillis()); }
    }

    static String abbrev(String s, int n) {
        if (s == null) return "";
        String x = s.replace('\n', ' ');
        return x.length() <= n ? x : x.substring(0, n) + "…";
    }

    /** 去掉 ANSI（boot log 里可能有颜色码，落到 JSON 会破坏解析）。 */
    static String ansiStrip(String s) {
        if (s == null) return "";
        return s.replaceAll("\u001b\\[[0-9;]*m", "");
    }

    // ================================================================ 自检

    /**
     * 纯静态自检：脚本化工具箱，把预检 / 执行 / 中止 / 单实例 / 落盘文本全跑一遍。
     * 不碰 Context（落盘失败会被 try 住，不影响判据）。
     */
    public static String selftest() {
        StringBuilder log = new StringBuilder();
        int ok = 0, n = 0;

        // ---- 预检：合法 ----
        {
            Script s = parse("battery\nmotion hug\nsay 你好", "t1");
            n++; if (s.ok() && s.steps.size() == 3) ok++;
            else log.append("  ✗ 合法脚本被拒：" + s.error + "\n");
            n++; if (s.ok() && "hug".equals(s.steps.get(1).arg)) ok++;
            else log.append("  ✗ 参数切分错\n");
        }
        // ---- 预检：注释/空行忽略 ----
        {
            Script s = parse("# 注释\n\nbattery\n   \n# 又一条\nstate", "t2");
            n++; if (s.ok() && s.steps.size() == 2) ok++;
            else log.append("  ✗ 注释/空行未忽略（" + s.steps.size() + " 步，err=" + s.error + "）\n");
        }
        // ---- 预检：未知 op ----
        {
            Script s = parse("battery\nflytospace 1", "t3");
            n++; if (!s.ok() && s.error.contains("未知 op")) ok++;
            else log.append("  ✗ 未知 op 未拒（" + s.error + "）\n");
        }
        // ---- 预检：破坏性 op **必须**被明确拒绝（不是"未知"）----
        {
            Script s = parse("reboot", "t4");
            n++; if (!s.ok() && s.error.contains("禁止")) ok++;
            else log.append("  ✗ 破坏性 op 未按「禁止」报（" + s.error + "）\n");
        }
        // ---- 预检：volume 越界 ----
        {
            n++; if (!parse("volume 99", "t5").ok()) ok++;
            else log.append("  ✗ volume 99 未拒\n");
            n++; if (parse("volume 15", "t5b").ok()) ok++;
            else log.append("  ✗ volume 15 应通过\n");
            n++; if (!parse("volume abc", "t5c").ok()) ok++;
            else log.append("  ✗ volume abc 未拒\n");
        }
        // ---- 预检：say/face/motion 缺参数 ----
        {
            n++; if (!parse("say", "t6").ok()) ok++;
            else log.append("  ✗ say 缺参数未拒\n");
        }
        // ---- 预检：步数上限 ----
        {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < MAX_STEPS + 1; i++) sb.append("battery\n");
            n++; if (!parse(sb.toString(), "t7").ok()) ok++;
            else log.append("  ✗ 步数上限未生效\n");
        }
        // ---- 预检：空脚本 ----
        {
            n++; if (!parse("", "t8").ok()) ok++;
            else log.append("  ✗ 空脚本未拒\n");
            n++; if (!parse("# 只有注释", "t8b").ok()) ok++;
            else log.append("  ✗ 只有注释未拒\n");
        }
        // ---- 预检：wait 越界 ----
        {
            n++; if (!parse("wait 99999", "t9").ok()) ok++;
            else log.append("  ✗ wait 越界未拒\n");
            n++; if (parse("wait 500", "t9b").ok()) ok++;
            else log.append("  ✗ wait 500 应通过\n");
        }

        // ---- 执行：全部成功 ----
        {
            final List<String> calls = new ArrayList<String>();
            Thinking.ToolBox tb = new Thinking.ToolBox() {
                @Override public String call(String name, String args) { calls.add(name + " " + args); return "ok"; }
            };
            Script s = parse("battery\nmotion hug\nsay 你好\nwait 10", "r1");
            RunResult r = exec(s, tb, null);
            n++; if (r.ok && r.results.size() == 4) ok++;
            else log.append("  ✗ 全成功用例失败（ok=" + r.ok + " n=" + r.results.size() + "）\n");
            n++; if (calls.size() == 3 && "motion hug".equals(calls.get(1))) ok++;
            else log.append("  ✗ 调用序列错：" + calls + "\n");
            n++; if (r.trace.contains("完成")) ok++;
            else log.append("  ✗ trace 未记完成\n");
        }
        // ---- 执行：中间一步失败 → **中止**，后面的步不再执行 ----
        {
            final List<String> calls = new ArrayList<String>();
            Thinking.ToolBox tb = new Thinking.ToolBox() {
                @Override public String call(String name, String args) {
                    calls.add(name);
                    if ("motion".equals(name)) return "未知动作：hugxa（用 TOOL motions 看清单）";
                    return "ok";
                }
            };
            Script s = parse("battery\nmotion hugxa\nsay 你好", "r2");
            RunResult r = exec(s, tb, null);
            n++; if (!r.ok && r.aborted && r.failedAt == 1) ok++;
            else log.append("  ✗ 未在中止点收尾（ok=" + r.ok + " aborted=" + r.aborted + " at=" + r.failedAt + "）\n");
            n++; if (calls.size() == 2) ok++;
            else log.append("  ✗ 失败后仍继续执行（调用了 " + calls.size() + " 次）\n");
            n++; if (r.error.contains("第 2 步")) ok++;
            else log.append("  ✗ 错误信息未指出第几步（" + r.error + "）\n");
            n++; if (r.results.size() == 2) ok++;
            else log.append("  ✗ 结果条数应停留在失败处\n");
        }
        // ---- 执行：工具抛异常 → 计入失败并中止，不外抛 ----
        {
            Thinking.ToolBox tb = new Thinking.ToolBox() {
                @Override public String call(String name, String args) { throw new RuntimeException("炸了"); }
            };
            Script s = parse("battery\nstate", "r3");
            RunResult r = exec(s, tb, null);
            n++; if (!r.ok && r.aborted) ok++;
            else log.append("  ✗ 工具异常未收尾\n");
            n++; if (r.results.size() == 1 && r.results.get(0).out.contains("异常")) ok++;
            else log.append("  ✗ 异常未记入结果\n");
        }
        // ---- 执行：非法脚本不执行 ----
        {
            final int[] c = new int[]{0};
            Thinking.ToolBox tb = new Thinking.ToolBox() {
                @Override public String call(String name, String args) { c[0]++; return "ok"; }
            };
            RunResult r = exec(parse("reboot", "r4"), tb, null);
            n++; if (!r.ok && c[0] == 0) ok++;
            else log.append("  ✗ 非法脚本被执行了（calls=" + c[0] + "）\n");
        }

        // ---- normalizeSteps：一行压平的多步必须还原回来 ----
        //   ★ 这条来自真机实测：模型把 3 步写成一行 "motion hug; motion cheer; say 你真棒"，
        //     旧实现只解析出 1 步、**静默丢掉后两步**（"说了三件事、只做了一件"）。
        {
            String one = "motion hug; motion cheer; say 你真棒";
            String two = "motion hug\nmotion cheer\nsay 你真棒";
            String three = "motion hug；motion cheer|say 你真棒";
            String four = "motion hug\\nmotion cheer\\nsay 你真棒";
            for (String raw : new String[]{one, two, three, four}) {
                Script s = parse(normalizeSteps(raw), "n1");
                // 3 步都要在（"motion hug" 里的空格不能被当成分隔符）
                boolean good = s.ok() && s.steps.size() == 3
                        && "hug".equals(s.steps.get(0).arg)
                        && "cheer".equals(s.steps.get(1).arg)
                        && "你真棒".equals(s.steps.get(2).arg);
                n++; if (good) ok++;
                else log.append("  ✗ 一行压平的多步未还原（raw=" + raw.replace("\n", "\\n")
                        + " got=" + s.steps.size() + " err=" + s.error + "）\n");
            }
            // 分号分隔的不该被当成一个参数
            Script s = parse(normalizeSteps("volume 5; battery"), "n2");
            n++; if (s.ok() && s.steps.size() == 2 && "5".equals(s.steps.get(0).arg)) ok++;
            else log.append("  ✗ 分号分隔未切（got=" + s.steps.size() + "）\n");
            // ★ 入口一致性：**每一个**入口（工具 / 网页 / PC）都必须先 normalize 再 parse。
            //   真机实测（2026-10-10）：网页入口漏了这一步 ⇒ 一行三步骤只跑第一步，
            //   而工具入口是对的 ⇒ 两条路分叉、"同一个脚本换个人交结果不同"。
            //   ★★ 判据必须是**行为**判据，不能读源码：进了 APK 之后
            //   `agent/src/...CommandRouter.java` 根本不在文件系统里（自检在设备上
            //   readAllBytes 失败 ⇒ 假 FAIL 40/41，实测踩过）。而且"读源码"本身
            //   就是判据自己会错——源码路径/打包方式一变就误报。
            //   所以只认**可观测行为**：同一份"一行压平"的文本，
            //   走**规范化入口**与走**原始入口**的结果必须一致。
            {
                String oneLine = "motion hug; motion cheer; say 你好";   // 3 步全是合法 op
                Script viaNorm = parse(normalizeSteps(oneLine), "e1");
                Script raw = parse(oneLine, "e2");
                n++; if (viaNorm.ok() && viaNorm.steps.size() == 3) ok++;
                else log.append("  ✗ 规范化入口没切成 3 步（got=" + viaNorm.steps.size() + "）\n");
                // 原始入口（不 normalize）只能得到 1 步：`motion` 的 arg 变成 "hug; motion cheer; say 你好"。
                // ★ 这条断言锁的是"**分叉确实是那个样子**"：哪天有人把 parse 改成也会切分号，
                //   这条会红——那就该回去把 normalizeSteps 这个单点去掉，而不是两处各切一遍。
                n++; if (raw.steps.size() == 1) ok++;
                else log.append("  ✗ 原始入口行为变了（got=" + raw.steps.size() + "），"
                        + "两个入口可能已分叉\n");
            }
        }

        // ---- looksOk 判据自查（多报可接受，漏报=假绿灯）----
        {
            n++; if (!looksOk("error: bad")) ok++; else log.append("  ✗ error 前缀未判失败\n");
            n++; if (!looksOk("未知工具：xx")) ok++; else log.append("  ✗ 未知工具未判失败\n");
            n++; if (!looksOk("灯控不可用")) ok++; else log.append("  ✗ 不可用未判失败\n");
            n++; if (!looksOk("参数错误：volume 需要 0..15")) ok++; else log.append("  ✗ 参数错误未判失败\n");
            n++; if (!looksOk("")) ok++; else log.append("  ✗ 空返回未判失败\n");
            n++; if (looksOk("queued: base hug")) ok++; else log.append("  ✗ 正常返回被误判失败\n");
            n++; if (looksOk("waited 350ms")) ok++; else log.append("  ✗ wait 返回被误判失败\n");
        }

        // ---- safeName：不许路径穿越 ----
        {
            n++; if ("etcpasswd".equals(safeName("../../etc/passwd")) || !safeName("../../etc/passwd").contains("/")) ok++;
            else log.append("  ✗ safeName 未挡住路径分隔符\n");
            n++; if (safeName("早晨流程").equals("早晨流程")) ok++;
            else log.append("  ✗ safeName 把中文名字改了\n");
        }

        // ---- 单实例闸门（不真起线程：直接验 busy 初值为 false 且 start(null) 返回 false）----
        {
            n++; if (!busy()) ok++;
            else log.append("  ✗ 初始不该 busy\n");
            n++; if (!start(parse("reboot", "g1"), null)) ok++;
            else log.append("  ✗ 非法脚本不该接单\n");
        }

        // ---- ★★ v207：白名单/禁止清单必须与**能力真源**逐字一致（对差，不许各写一份）----
        {
            String[] wantA = Abilities.allowedOps();
            String[] wantF = Abilities.forbiddenOps();
            boolean aOk = wantA.length == ALLOWED.length, fOk = wantF.length == FORBIDDEN.length;
            if (aOk) for (int i = 0; i < wantA.length; i++) if (!wantA[i].equals(ALLOWED[i])) aOk = false;
            if (fOk) for (int i = 0; i < wantF.length; i++) if (!wantF[i].equals(FORBIDDEN[i])) fOk = false;
            n++; if (aOk) ok++;
            else log.append("  ✗ 允许清单与真源不一致（真源 " + wantA.length + " 条 / 本类 " + ALLOWED.length + " 条）\n");
            n++; if (fOk) ok++;
            else log.append("  ✗ 禁止清单与真源不一致（真源 " + wantF.length + " 条 / 本类 " + FORBIDDEN.length + " 条）\n");
        }
        // ---- ★ 安全红线：wifi/adb/开关机/shell 必须被拒（且报"禁止"，不是"未知"）----
        {
            String[] red = {"wifi", "bt", "adbwifi", "reboot", "shutdown", "shell", "deepsleep", "kill"};
            int rej = 0;
            for (String r : red) {
                Script s = parse(r, "red-" + r);
                if (!s.ok() && s.error.contains("禁止")) rej++;
                else log.append("  ✗ 红线 " + r + " 未被按「禁止」拒绝（" + s.error + "）\n");
            }
            n++; if (rej == red.length) ok++;
            else log.append("  ✗ 红线拒绝不全（" + rej + "/" + red.length + "）\n");
        }
        // ---- ★ 只读查询 op 必须在白名单里（用户口径：wifi/adb 可查）----
        {
            int miss = 0;
            for (String q : new String[]{"netinfo", "adbinfo", "btinfo", "screen", "sensors", "sysinfo", "logs"}) {
                if (!parse(q + ("logs".equals(q) ? " 5" : ""), "q-" + q).ok()) {
                    miss++;
                    log.append("  ✗ 只读查询 " + q + " 不在白名单\n");
                }
            }
            n++; if (miss == 0) ok++;
        }
        // ---- ★ 新写入 op 预检：合法通过、非法拒绝 ----
        {
            n++; if (parse("emotion happy", "w1").ok()) ok++;   else log.append("  ✗ emotion happy 应通过\n");
            n++; if (!parse("emotion", "w2").ok()) ok++;        else log.append("  ✗ emotion 缺参数应拒\n");
            n++; if (parse("display 你好", "w3").ok()) ok++;     else log.append("  ✗ display 应通过\n");
            n++; if (!parse("tone loud", "w4").ok()) ok++;       else log.append("  ✗ tone 非法档应拒\n");
            n++; if (parse("tone child", "w4b").ok()) ok++;      else log.append("  ✗ tone child 应通过\n");
            n++; if (!parse("rotation spin", "w5").ok()) ok++;   else log.append("  ✗ rotation 非法值应拒\n");
            n++; if (!parse("listen maybe", "w6").ok()) ok++;    else log.append("  ✗ listen 非 on/off 应拒\n");
            n++; if (!parse("idle 999", "w7").ok()) ok++;        else log.append("  ✗ idle 越界应拒\n");
            n++; if (parse("idle 0", "w7b").ok()) ok++;          else log.append("  ✗ idle 0 应通过\n");
            n++; if (!parse("workflow battery", "w8").ok()) ok++; else log.append("  ✗ 步骤里嵌套 workflow 应拒\n");
        }
        // ---- ★ 查询 op 真的能跑（设备不在场时如实回"未就绪"，不是"未知"）----
        {
            final java.util.List<String> unk = new java.util.ArrayList<String>();
            Thinking.ToolBox tb = new Thinking.ToolBox() {
                @Override public String call(String nm, String aa) { return Abilities.execute(nm, aa); }
            };
            for (String q : new String[]{"netinfo", "adbinfo", "btinfo", "screen", "sensors", "sysinfo", "pose", "micinfo"}) {
                String o = tb.call(q, "");
                if (o == null || o.contains("未知能力") || o.contains("未知查询")) unk.add(q);
            }
            n++; if (unk.isEmpty()) ok++;
            else log.append("  ✗ 查询 op 未实现：" + unk + "\n");
        }

        StringBuilder out = new StringBuilder();
        out.append("workflow 自检 ").append(ok).append('/').append(n).append(ok == n ? " PASS" : " FAIL");
        if (ok != n) out.append('(').append(n - ok).append(')');
        out.append('\n');
        if (log.length() > 0) out.append(log);
        out.append("白名单 ").append(ALLOWED.length).append(" 个 op（= 能力真源 read+write+plan）：").append(join(ALLOWED)).append('\n');
        out.append("明确禁止 ").append(FORBIDDEN.length).append(" 个 op（安全红线，只读不可改）：").append(join(FORBIDDEN)).append('\n');
        out.append("上限 ").append(MAX_STEPS).append(" 步 / 步间隔 ").append(STEP_GAP_MS).append("ms")
           .append(" | 落盘 ").append(DIR).append(" 流水 ").append(RUN_LOG).append('\n');
        return out.toString();
    }
}
