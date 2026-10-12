package com.ledi.agent.cap;

import com.ledi.agent.core.J;

import com.ledi.agent.sense.Battery;

import com.ledi.agent.voice.Av;

import com.ledi.agent.core.AgentService;

import com.ledi.agent.motion.MotionController;
import com.ledi.agent.sense.SensorHub;
import com.ledi.agent.ui.SleepMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 多轮思考引擎（v202）——「想 → 用工具查/做 → 再想 → 最终回答」的循环。
 *
 * <h3>为什么不能只改 {@link AiCore#ask}</h3>
 * {@code ask} 是**单轮**：一次请求、一次答复。遇到"我不知道现在几点/还剩多少电"这类
 * 只有本机才知道的事，模型只能瞎猜或说"我不知道"。多轮思考要做的是：允许模型在
 * 得到最终答案**之前**，分几步向本机要信息、或让机器人先做个动作。
 *
 * <h3>协议（刻意不用大模型的 function-calling）</h3>
 * 本工程不引 JSON 库、所有解析都是手写容错。function-calling 的 {@code tool_calls}
 * 是一层嵌套 JSON（数组里再套对象、还要处理流式增量），手写解析极易碎；而且不同厂商
 * （DeepSeek / 通义 / Kimi / Ollama）支持程度不一。所以这里用一层**纯文本协议**，
 * 完全落在我们自己的解析能力之内：
 * <pre>
 *   模型输出若干行  TOOL &lt;工具名&gt; &lt;参数&gt;      → 本机执行，把结果回给模型，进入下一轮
 *   或者一行        FINAL &lt;要说的话&gt;          → 结束，把后面的话当作最终回答
 * </pre>
 * 容错：大小写不敏感、认全角冒号、允许多行 TOOL、若模型压根没按协议走就把整段当答案、
 * 若用满轮数仍在要工具则如实标 {@code truncated=true}（不假装想完了）。
 *
 * <h3>可证伪</h3>
 * {@link #selftest()} 是**纯静态**的：不吃 Context、不联网、不碰 org.json，
 * 用脚本化的假模型 + 假工具箱把协议解析与循环跑一遍。本项目的规矩是"新引擎先配一张
 * 没硬件也能跑的考卷"——否则"编得过"会被当成"跑得了"。
 */
public final class Thinking {

    private Thinking() {}

    /** 默认思考轮数（一次问答最多几步）。实际使用 2~3 步就够；给足但不无限。 */
    public static final int DEFAULT_ROUNDS = 3;
    /**
     * workflow 模式下的轮数上限（v203）——比默认宽，因为这条路径允许：
     * ① {@code TOOL doc} 先检索能力索引（一次信息轮）；② 再 {@code TOOL workflow} 一次交出一串步骤；
     * ③ 最后 {@code FINAL} 汇报。3 步刚好卡在边上，给 4 步留一次机动。
     * ★ 仍受 {@link #MAX_ROUNDS} 硬顶，绝不无限。
     */
    public static final int WF_ROUNDS = 4;
    /** 硬上限：防止模型陷入"一直要工具"的死循环把 API 越花越多。 */
    public static final int MAX_ROUNDS = 6;

    /**
     * 一次模型调用（阻塞）。{@code messages} 是**已经编码好的 JSON 对象串**列表，
     * 由 {@link #msg} 生成 —— 这样引擎不认识网络，AiCore 也不认识消息形状。
     */
    public interface Transporter {
        String complete(List<String> messages) throws Exception;
    }

    /** 执行一个本机工具。name/args 已 trim；实现方自己保证安全（白名单）。 */
    public interface ToolBox {
        String call(String name, String args);
    }

    // ================================================================ 协议

    /** 把一条消息编码成 OpenAI 兼容的 JSON 对象串（不引第三方库）。 */
    public static String msg(String role, String content) {
        return "{\"role\":\"" + J.esc(role) + "\",\"content\":\""
                + J.esc(content == null ? "" : content) + "\"}";
    }

    /**
     * 追加给 system 的协议说明 + 工具清单。
     *
     * <p>★ v207：工具清单**从能力真源 {@link Abilities#SPECS} 现场生成**，不再手写抄一份。
     * 手写副本必然漂移（加了能力忘了改提示词 ⇒ 模型永远不知道有新工具，
     * 这正是"修在一个地方 ≠ 修好了"）。</p>
     */
    public static String protocolPrompt() {
        StringBuilder sb = new StringBuilder(1200);
        sb.append("【工具循环】你可以分多步完成一件事：先说想查一下，拿到结果再给答案。\n")
          .append("- 需要本机信息、或想让机器人先做个动作时，单独输出一行：TOOL 工具名 参数\n")
          .append("- 一次可以输出多行 TOOL，我会一次性执行完、把结果回给你。\n")
          .append("- 拿到结果后：还要别的就继续输出 TOOL；否则**必须**用 FINAL 开头给出最终回答。\n")
          .append("- 最终回答只有一条，形如 FINAL 要说给主人听的话（不要再带 TOOL）。\n")
          .append("可用能力（★ 与机器人本机能力真源同源，不会过期）：\n");
        for (Abilities.Spec s : Abilities.SPECS) {
            if (Abilities.FORBID.equals(s.kind)) continue;
            sb.append("  ").append(pad(s.usage, 30)).append(s.desc).append('\n');
        }
        sb.append("★ 安全红线（**只读不可改**，AI 不许改）：")
          .append(Abilities.brief(Abilities.forbiddenOps())).append('\n')
          .append("   wifi / adb / 蓝牙 只能**查询**：netinfo / adbinfo / btinfo；")
          .append("开关机、深睡、冻结、杀进程、shell 一律不许。\n")
          .append("★ 不确定动作名/表情名时，**先** doc 查，别猜；猜错就是「说了没反应」。\n")
          .append("★ 需要 2 个以上动作连做时用 workflow 一次交出去（比逐个 TOOL 省一轮往返）；")
          .append("一步失败会中止整串。\n")
          // ★★★ v209：反"编造"铁律 —— 2026-10-11 真机实测抓到的两个事故：
          //   ① 让它「把灯关掉」，它没调任何工具就回「关了。」（其实没关）；
          //   ② 问电量，它回「还是32%」，而实时是 97% —— 它把**历史对话里的旧值**当结论了。
          //   这两条都是"AI 完全接管"下最危险的失败形态：用户会照着假信息行动。
          //   所以把"先查再做结论"写成硬规矩，并明确"历史里的旧状态作废"。
          .append("★ 三条铁律（违反=事故）：\n")
          .append("  ① 凡涉及本机状态（电量/网络/温度/姿态/音量…）的提问，**先** TOOL 查、再回答；\n")
          .append("     不许用历史对话里的旧数字或印象作答 —— 旧值会过期（实测报过 32%，实际 97%）。\n")
          .append("  ② 凡要让机器人做动作/改设置，**先** 输出 TOOL 拿到结果，才能说「做了」；\n")
          .append("     没有 TOOL 结果就**不许**声称已经执行（「嘴上说做了、其实没调」是本项目最忌讳的错）。\n")
          .append("  ③ 工具行 `=>` 后面的才是事实。工具被拒或报错，就**如实**转述，严禁改口说成功。");
        return sb.toString();
    }

    /** 按显示宽度左对齐（中文算 2 列），只为让提示词里的清单对齐好读。 */
    private static String pad(String s, int w) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        int len = 0;
        for (int i = 0; i < sb.length(); i++) len += sb.charAt(i) > 127 ? 2 : 1;
        while (len < w) { sb.append(' '); len++; }
        return sb.append(' ').toString();
    }

    // ================================================================ 反"编造执行结果"（v209）

    /**
     * 目标是否**必须先调工具**（先查状态 / 先让机器人动手）才能作答。
     *
     * <p>★ 只有一个用途：当模型**一个工具都没调**就给了结论时，把它打回重来一轮
     * （见 {@link AiCore} 里的复核分支）。所以这里**假阳性只多花一轮 API**（不会答错），
     * 假阴性才是真问题 ⇒ 词表宁可宽一点。</p>
     *
     * <p>来历（2026-10-11 真机实测）：让它「把灯关掉」「把音量调到5」，模型没调任何工具
     * 就回「关了。」「5了。」——用户会照着**假信息**行动。提示词写了铁律不够（模型会漏），
     * 必须在**流程上**再挡一道。这是本项目"错误不许谎报成功"的同一条铁律在 AI 层的落地。</p>
     */
    private static final String[] NEED_TOOL_WORDS = {
        // 动作类（做/改）
        "关", "开", "亮", "暗", "调", "设", "改", "切", "换", "摆", "抬", "举", "挥", "点",
        "摇", "转", "走", "停", "跳", "唱", "念", "播", "放", "抱", "握手", "敬礼", "舞",
        "表情", "动作", "灯", "音量", "声音", "大点", "小点", "静音", "屏幕", "显示",
        "名字", "称呼", "帮我", "把",
        // 状态类（查）
        "多少", "几", "现在", "当前", "状态", "电量", "电池", "电压", "温度", "内存",
        "存储", "网络", "wifi", "ip", "蓝牙", "姿态", "触摸", "日志", "版本",
        "麦克风", "充电", "时间",
    };

    /** 只要"让机器人**动手/改设置**"的那部分词（= {@link #NEED_TOOL_WORDS} 的动作类）。 */
    private static final String[] ACTION_WORDS = {
        "关", "开", "亮", "暗", "调", "设", "改", "切", "换", "摆", "抬", "举", "挥", "点",
        "摇", "转", "走", "停", "跳", "唱", "念", "播", "放", "抱", "握手", "敬礼", "舞",
        "表情", "动作", "灯", "音量", "声音", "大点", "小点", "静音", "屏幕", "显示",
        "名字", "称呼",
    };

    /** 见 {@link #NEED_TOOL_WORDS}。 */
    public static boolean goalNeedsTool(String goal) {
        return containsAny(goal, NEED_TOOL_WORDS);
    }

    /**
     * 目标是"让机器人动手/改设置"吗（比 {@link #goalNeedsTool} 严）？
     *
     * <p>★ 为什么要单独分一类（2026-10-11 实测）：只说"涉及本机"不够 —— 让它「把灯关掉」时
     * 模型先调了一次 {@code led blue off}（**被拒**），紧接着用 {@code doc} 查了一下
     * （**成功**），就宣布"关掉了"。{@code doc} 成功 ≠ 灯关了。所以动作类目标必须要求
     * **写类能力**成功过，而不只是"有工具成功过"。</p>
     */
    /** 让"别关灯""不用开"这类**否定式**目标不被当成"要动手"（否则兜底会把它推去做动作）。 */
    private static final String[] NEGATION_WORDS = {
        "别", "不要", "不用", "不用了", "取消", "先别", "无需", "没必要",
    };

    public static boolean goalWantsAction(String goal) {
        if (containsAny(goal, NEGATION_WORDS)) return false;
        return containsAny(goal, ACTION_WORDS);
    }

    private static boolean containsAny(String goal, String[] words) {
        if (goal == null || goal.isEmpty()) return false;
        String g = goal.toLowerCase(Locale.ROOT);
        for (String w : words) if (g.contains(w.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /** 这一轮的 trace 里有没有**真正执行过**工具（不是"提到工具名"）。 */
    public static boolean traceUsedTool(List<String> trace) {
        if (trace == null) return false;
        for (String x : trace) if (x != null && x.contains(" TOOL ")) return true;
        return false;
    }

    /**
     * trace 里有没有**成功**的工具调用。
     *
     * <p>★ 判据取 trace 行里 {@code =>} 之后的那段（那才是**事实**），并用
     * {@link Abilities#isFailure} 判成败 —— 与 {@link Workflow} 的步骤成败**同一个判据**
     * （单一真源，不另写一份）。</p>
     *
     * @param kind {@code null}=任意能力；传 {@link Abilities#READ}/{@link Abilities#WRITE}
     *             /{@link Abilities#PLAN} 则只看该类能力成功过。
     */
    public static boolean traceToolSucceeded(List<String> trace, String kind) {
        if (trace == null) return false;
        for (String x : trace) {
            if (x == null) continue;
            int i = x.indexOf(" TOOL ");
            if (i < 0) continue;
            String rest = x.substring(i + 6);
            int a = rest.indexOf(" => ");
            if (a < 0) continue;
            String call = rest.substring(0, a).trim();
            String out = rest.substring(a + 4);
            if (Abilities.isFailure(out)) continue;    // 调了但被拒/报错 —— 不算成功
            if (kind == null) return true;
            String op = call.split("\\s+")[0];
            Abilities.Spec sp = Abilities.find(Abilities.canon(op));
            if (sp != null && kind.equals(sp.kind)) return true;
        }
        return false;
    }

    /**
     * 交给模型的「本机能力索引」旁白（v203）—— 让它知道可以自己查，而不是靠训练记忆猜。
     *
     * <p>为什么必须明说：模型对这台机器人的动作名一无所知（{@code MOTIONS}/{@code DANCE}
     * 里全是 {@code walk}/{@code stCheer}/{@code dance_L5M0R5} 这种标识），
     * 不告诉它"你可以查"，它就会编一个听起来合理但不存在的名字，
     * 结果 {@link MotionController#isValidMotion} 拒绝 → 表现为"嘴上说做了、实际没做"。</p>
     */
    public static String capabilityPrompt() {
        try {
            String st = CapIndex.statusJson();
            return "【本机能力】这台机器人有一份**随包内置**的能力索引（"
                    + "动作 " + MotionController.MOTIONS.length + " 个 + 舞曲 "
                    + MotionController.DANCE.length + " 支 + 表情与传感器若干）。\n"
                    + "需要知道「自己会不会做某事」、某个动作/表情叫什么名字时，"
                    + "**先**用 `TOOL doc <检索词>` 查（例如 `TOOL doc 握手`、`TOOL doc 跳舞`、`TOOL doc 电量`），"
                    + "拿到准确名字再用 TOOL 执行。\n"
                    + "★ 绝对不要凭印象编动作名或表情名 —— 编出来的名字本机不认，"
                    + "表现就是「嘴上说做了、机器人没动」。\n"
                    + "★ doc 的返回里会带一行「⇒ 要用就抄这一行」，**照抄**即可，"
                    + "不要再把中文自己翻译成动作名（实测会把「拥抱」直接当动作名发出去，被拒）。\n"
                    + "★ 索引摘要：" + st + "\n";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 从模型回复里抽出 FINAL 文本；没有则返回 null。 */
    public static String parseFinal(String reply) {
        if (reply == null) return null;
        String[] lines = reply.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].trim();
            if (!isMarker(s, "FINAL")) continue;
            String rest = stripMarker(s, "FINAL");
            StringBuilder sb = new StringBuilder(rest);
            for (int j = i + 1; j < lines.length; j++) sb.append('\n').append(lines[j]);
            return sb.toString().trim();
        }
        return null;
    }

    /** 从模型回复里抽出一行行工具调用（去掉 TOOL 前缀）。没有则返回空表。 */
    public static List<String> parseTools(String reply) {
        List<String> out = new ArrayList<String>();
        if (reply == null) return out;
        for (String raw : reply.split("\n")) {
            String s = raw.trim();
            if (!isMarker(s, "TOOL")) continue;
            String rest = stripMarker(s, "TOOL").trim();
            if (!rest.isEmpty()) out.add(rest);
        }
        return out;
    }

    /** 该行是不是形如 {@code MARKER} / {@code MARKER:} / {@code MARKER 参数} 的指令行。 */
    private static boolean isMarker(String s, String marker) {
        String u = s.toUpperCase(Locale.ROOT);
        if (!u.startsWith(marker)) return false;
        if (u.length() == marker.length()) return true;
        char c = u.charAt(marker.length());
        return c == ' ' || c == ':' || c == '\t' || c == '：';
    }

    /** 去掉指令前缀与紧随的分隔符，返回后半段。 */
    private static String stripMarker(String s, String marker) {
        String rest = s.substring(marker.length());
        // 去掉开头的空格 / 半角冒号 / 全角冒号
        int i = 0;
        while (i < rest.length()) {
            char c = rest.charAt(i);
            if (c == ' ' || c == '\t' || c == ':' || c == '：') i++;
            else break;
        }
        return rest.substring(i);
    }

    // ================================================================ 引擎

    /** 一轮思考的结果。 */
    public static final class Result {
        public String finalText = "";
        public int rounds = 0;
        /** 用满轮数仍未等到 FINAL（模型一直在要工具）—— 如实标出来，不假装想完了。 */
        public boolean truncated = false;
        public final List<String> trace = new ArrayList<String>();
        /**
         * ★ v210：最后一轮的**完整消息上下文**（含已执行的工具结果）。
         * 用途：用满轮数还没 FINAL 时，调用方可以**接着它**再要一句收尾 ——
         * 从零重来会把刚拿到的工具结果丢掉（模型就会又去调一遍工具，无限循环）。
         */
        public List<String> lastMsgs = null;
    }

    /**
     * 跑一次多轮思考。{@code system} 是系统提示词（人格 + 状态 + {@link #protocolPrompt()}）。
     *
     * <p>任何一环抛异常都不外抛（这是喂给"说话"用的，宁可这一轮降级也不能把调用方打死）：
     * 模型调用失败 → 记进 trace 并停；工具失败 → 把异常文本当结果继续喂回去。</p>
     */
    public static Result run(Transporter t, ToolBox tb, String system, String goal, int maxRounds) {
        return run(t, tb, system, null, goal, maxRounds);
    }

    /**
     * 带**跨轮历史**的版本：{@code historyMsgs} 是已编码好的消息对象（见
     * {@link TalkLog#historyMessages()}）—— 插在 system 之后、本次 goal 之前，
     * 让多轮思考也知道"刚才聊过什么"，而不是每句都从零开始。
     */
    public static Result run(Transporter t, ToolBox tb, String system, List<String> historyMsgs,
                             String goal, int maxRounds) {
        int rounds = maxRounds;
        if (rounds < 1) rounds = 1;
        if (rounds > MAX_ROUNDS) rounds = MAX_ROUNDS;

        Result r = new Result();
        if (t == null) { r.trace.add("没有传输器"); r.truncated = true; return r; }

        List<String> msgs = new ArrayList<String>();
        msgs.add(msg("system", system == null ? "" : system));
        if (historyMsgs != null) {
            for (String h : historyMsgs) if (h != null && !h.isEmpty()) msgs.add(h);
        }
        msgs.add(msg("user", goal == null ? "" : goal));

        for (int i = 1; i <= rounds; i++) {
            r.rounds = i;
            String reply;
            try {
                reply = t.complete(msgs);
            } catch (Throwable e) {
                r.trace.add("第" + i + "轮 调用失败: " + e.getClass().getSimpleName());
                break;
            }
            if (reply == null) reply = "";
            reply = reply.trim();

            String fin = parseFinal(reply);
            if (fin != null && !fin.isEmpty()) {
                r.finalText = fin;
                r.trace.add("第" + i + "轮 FINAL");
                return r;
            }

            List<String> tools = parseTools(reply);
            if (tools.isEmpty()) {
                // 模型没按协议走 —— 把整段当最终答案，别白跑剩下几轮
                if (!reply.isEmpty()) {
                    r.finalText = reply;
                    r.trace.add("第" + i + "轮 未用协议 → 整段当答案");
                    return r;
                }
                r.trace.add("第" + i + "轮 空回复，停");
                r.truncated = true;
                return r;
            }

            StringBuilder results = new StringBuilder();
            for (String call : tools) {
                int sp = call.indexOf(' ');
                String name = sp < 0 ? call : call.substring(0, sp);
                String args = sp < 0 ? "" : call.substring(sp + 1).trim();
                String out;
                try {
                    out = tb == null ? "(本机没有工具箱)" : tb.call(name, args);
                } catch (Throwable e) {
                    out = "工具执行异常：" + e;
                }
                if (out == null) out = "";
                r.trace.add("第" + i + "轮 TOOL " + call + " => " + abbrev(out, 140));
                results.append("- ").append(call).append(" => ").append(out).append('\n');
            }
            msgs.add(msg("assistant", reply));
            msgs.add(msg("user", "工具结果：\n" + results
                    + "需要更多信息就再输出 TOOL 行；否则输出 FINAL <最终回答>。"));
            // ★ v210：把"当前对话状态"留给调用方 —— 用满轮数没 FINAL 时（工具调对了、
            //   但没来得及说话），调用方可以**接着这份上下文**再要一句收尾，
            //   而不是从零重来（重来会丢掉刚拿到的工具结果）。
            r.lastMsgs = new ArrayList<String>(msgs);
        }
        r.truncated = r.finalText.isEmpty();
        return r;
    }

    private static String abbrev(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    // ================================================================ 设备侧工具箱

    /**
     * 把工具箱绑到本机能力。
     *
     * <p>★ v207：实现已**整体下沉**到 {@link Abilities}（能力真源）—— 这里只留一层薄壳。
     * 白名单、安全红线、参数校验、具体实现全在 {@code Abilities} 一处；
     * 这样"模型工具循环能调什么"与"workflow 能跑什么"必然一致，
     * 不会再出现"某个 op 在工具里能用、在脚本里被拒"这种分叉（本项目老毛病）。</p>
     */
    public static ToolBox deviceToolBox() {
        return new ToolBox() {
            @Override public String call(String name, String args) {
                return Abilities.execute(name, args);
            }
        };
    }

    /** 一句话状态（工具 state 与 system 里都用）。 */
    public static String stateBrief() {
        StringBuilder sb = new StringBuilder(160);
        try { sb.append("电量=").append(Battery.human(AgentService.I)); }
        catch (Throwable t) { sb.append("电量=?"); }
        try { sb.append("；屏保=").append(SleepMode.sleeping ? "是" : "否"); } catch (Throwable ignored) {}
        try { sb.append("；深睡=").append(AgentService.deepSleeping ? "是" : "否"); } catch (Throwable ignored) {}
        try { sb.append("；音量=").append(Av.get()).append('/').append(Av.max()); } catch (Throwable ignored) {}
        try {
            SensorHub s = AgentService.sensor;
            if (s != null) {
                sb.append("；姿态=").append(s.posture);
                sb.append("；最近触摸=").append(SensorHub.zh(s.lastTouch));
            }
        } catch (Throwable ignored) {}
        try { sb.append("；常听=").append(AgentService.continuousOn() ? "开" : "关"); } catch (Throwable ignored) {}
        // ★ v214 心情。**措辞唯一出处**仍是 Emotion.moodLabel()（说法的展开在 Tone.moodClause()）：
        //   这里只是让「你心情怎么样」这类提问**答得出真值**。两处取同一个函数 ⇒ 不会对不上。
        try { sb.append("；心情=").append(Emotion.moodLabel()); } catch (Throwable ignored) {}
        return sb.toString();
    }

    static String nowString() {
        try {
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss (EEEE)", Locale.CHINA);
            return f.format(new java.util.Date());
        } catch (Throwable t) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    static String joinList(List<String> xs, int cap) {
        if (xs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size() && i < cap; i++) {
            if (i > 0) sb.append(' ');
            sb.append(xs.get(i));
        }
        if (xs.size() > cap) sb.append(" …共").append(xs.size());
        return sb.toString();
    }

    static String joinArray(String[] xs, int cap) {
        if (xs == null) return "";
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String x : xs) {
            if (n >= cap) { sb.append(" …共").append(xs.length); break; }
            if (n > 0) sb.append(' ');
            sb.append(x);
            n++;
        }
        return sb.toString();
    }

    /** 动作全表 = {@code MOTIONS} + {@code DANCE}（两个独立数组，别只列一个）。 */
    static String[] allMotions() {
        String[] a = MotionController.MOTIONS;
        String[] b = MotionController.DANCE;
        String[] out = new String[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** {@code String[]} 直接 join（{@link #joinArray} 是封顶版，这个是全量版）。 */
    public static String joinArr(String[] xs) {
        if (xs == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String x : xs) { if (sb.length() > 0) sb.append('/'); sb.append(x); }
        return sb.toString();
    }

    /** 步骤序列的短摘要（回给模型时省 token，别把整份脚本回灌一遍）。 */
    static String stepsBrief(Workflow.Script sc) {
        StringBuilder sb = new StringBuilder();
        int cap = 8;
        for (int i = 0; i < sc.steps.size() && i < cap; i++) {
            if (i > 0) sb.append(" → ");
            sb.append(sc.steps.get(i).text());
        }
        if (sc.steps.size() > cap) sb.append(" …共").append(sc.steps.size()).append(" 步");
        return sb.toString();
    }

    // ================================================================ 自检

    /**
     * 纯静态自检：脚本化假模型 + 假工具箱，把协议解析与循环跑一遍。
     * 无 Context / 无网络 / 不碰 org.json ⇒ 纯 JVM 可跑。
     */
    public static String selftest() {
        StringBuilder log = new StringBuilder();
        int ok = 0, n = 0;

        // ---- 协议解析 ----
        n++; if (eq(parseTools("TOOL battery"), new String[]{"battery"})) ok++;
        else log.append("  ✗ parseTools 基本\n");
        n++; if (eq(parseTools("TOOL volume 5"), new String[]{"volume 5"})) ok++;
        else log.append("  ✗ parseTools 带参数\n");
        n++; if (eq(parseTools("TOOL：battery"), new String[]{"battery"})) ok++;
        else log.append("  ✗ parseTools 全角冒号\n");
        n++; if (eq(parseTools("tool battery"), new String[]{"battery"})) ok++;
        else log.append("  ✗ parseTools 小写\n");
        n++; if (parseTools("普通一句话").isEmpty()) ok++;
        else log.append("  ✗ parseTools 误命中普通文本\n");
        n++; if (eq(parseTools("TOOL battery\nTOOL time"), new String[]{"battery", "time"})) ok++;
        else log.append("  ✗ parseTools 多行\n");

        n++; if ("我还有80%".equals(parseFinal("FINAL 我还有80%"))) ok++;
        else log.append("  ✗ parseFinal 基本\n");
        n++; if ("第一行\n第二行".equals(parseFinal("FINAL 第一行\n第二行"))) ok++;
        else log.append("  ✗ parseFinal 多行\n");
        n++; if ("直接答".equals(parseFinal("FINAL：直接答"))) ok++;
        else log.append("  ✗ parseFinal 全角冒号\n");
        n++; if (parseFinal("TOOL battery") == null) ok++;
        else log.append("  ✗ parseFinal 无 FINAL 应为 null\n");
        n++; if (parseTools("FINALLY ok").isEmpty()) ok++;
        else log.append("  ✗ parseTools 不该匹配 FINALLY\n");

        // ---- msg 转义 ----
        String m = msg("user", "他说\"你好\"\n换行");
        n++; if (m.contains("\\\"") && m.contains("\\n")) ok++;
        else log.append("  ✗ msg 转义\n");

        // ---- 循环：TOOL 一轮 → FINAL ----
        {
            final int[] calls = new int[]{0};
            Transporter t = new Transporter() {
                @Override public String complete(List<String> msgs) {
                    calls[0]++;
                    return calls[0] == 1 ? "TOOL battery" : "FINAL 我还有80%电";
                }
            };
            final List<String> used = new ArrayList<String>();
            ToolBox tb = new ToolBox() {
                @Override public String call(String name, String args) { used.add(name); return "80"; }
            };
            Result r = run(t, tb, "sys", "我还有多少电", 3);
            n++; if ("我还有80%电".equals(r.finalText) && r.rounds == 2 && !r.truncated) ok++;
            else log.append("  ✗ 循环 TOOL→FINAL（got rounds=" + r.rounds + " fin=" + r.finalText + "）\n");
            n++; if (used.size() == 1 && "battery".equals(used.get(0))) ok++;
            else log.append("  ✗ 工具被调用次数/名字不对\n");
            n++; if (r.trace.size() == 2) ok++;
            else log.append("  ✗ trace 条数（" + r.trace.size() + "）\n");
        }

        // ---- 循环：模型未用协议 → 整段当答案 ----
        {
            Transporter t = new Transporter() {
                @Override public String complete(List<String> msgs) { return "今天天气不错"; }
            };
            Result r = run(t, null, "sys", "在吗", 3);
            n++; if ("今天天气不错".equals(r.finalText) && r.rounds == 1) ok++;
            else log.append("  ✗ 未用协议兜底\n");
        }

        // ---- 循环：一直要工具 → 用满轮数且标 truncated ----
        {
            final int[] calls = new int[]{0};
            Transporter t = new Transporter() {
                @Override public String complete(List<String> msgs) { calls[0]++; return "TOOL time"; }
            };
            ToolBox tb = new ToolBox() {
                @Override public String call(String name, String args) { return "12:00"; }
            };
            Result r = run(t, tb, "sys", "几点了", 4);
            n++; if (r.truncated && r.rounds == 4 && calls[0] == 4) ok++;
            else log.append("  ✗ 用满轮数/truncated（rounds=" + r.rounds + " calls=" + calls[0] + "）\n");
        }

        // ---- 轮数上限被钳到 MAX_ROUNDS ----
        {
            final int[] calls = new int[]{0};
            Transporter t = new Transporter() {
                @Override public String complete(List<String> msgs) { calls[0]++; return "TOOL time"; }
            };
            run(t, new ToolBox() {
                @Override public String call(String name, String args) { return "x"; }
            }, "sys", "g", 999);
            n++; if (calls[0] == MAX_ROUNDS) ok++;
            else log.append("  ✗ 轮数上限未被钳（" + calls[0] + "）\n");
        }

        // ---- 传输器中途抛异常 → 不外抛 ----
        {
            final int[] calls = new int[]{0};
            Transporter t = new Transporter() {
                @Override public String complete(List<String> msgs) throws Exception {
                    calls[0]++;
                    if (calls[0] == 1) return "TOOL battery";
                    throw new Exception("网络断了");
                }
            };
            Result r = run(t, new ToolBox() {
                @Override public String call(String name, String args) { return "50"; }
            }, "sys", "电量", 4);
            n++; if (r.finalText.isEmpty() && r.rounds == 2 && r.truncated) ok++;
            else log.append("  ✗ 传输器异常未如实收尾\n");
            n++; if (r.trace.size() >= 1 && r.trace.get(r.trace.size() - 1).contains("失败")) ok++;
            else log.append("  ✗ 传输器异常未记入 trace\n");
        }

        // ---- 工具抛异常 → 变结果继续，不中断 ----
        {
            final int[] calls = new int[]{0};
            Transporter t = new Transporter() {
                @Override public String complete(List<String> msgs) {
                    calls[0]++;
                    return calls[0] == 1 ? "TOOL battery" : "FINAL 读不到";
                }
            };
            ToolBox tb = new ToolBox() {
                @Override public String call(String name, String args) { throw new RuntimeException("炸了"); }
            };
            Result r = run(t, tb, "sys", "电量", 3);
            n++; if ("读不到".equals(r.finalText)) ok++;
            else log.append("  ✗ 工具异常后无法继续\n");
            n++; if (r.trace.get(0).contains("工具执行异常")) ok++;
            else log.append("  ✗ 工具异常未如实记录\n");
        }

        // ---- 第一轮就 FINAL ----
        {
            Transporter t = new Transporter() {
                @Override public String complete(List<String> msgs) { return "FINAL 我在呢"; }
            };
            Result r = run(t, null, "sys", "在吗", 3);
            n++; if ("我在呢".equals(r.finalText) && r.rounds == 1) ok++;
            else log.append("  ✗ 首轮 FINAL\n");
        }

        // ---- 空回复 ----
        {
            Transporter t = new Transporter() {
                @Override public String complete(List<String> msgs) { return ""; }
            };
            Result r = run(t, null, "sys", "x", 3);
            n++; if (r.finalText.isEmpty() && r.truncated && r.rounds == 1) ok++;
            else log.append("  ✗ 空回复处理\n");
        }

        // ---- v209：反"编造执行结果"的两个判据 ----
        {
            // goalNeedsTool：涉及本机（动作/状态）的目标必须为 true
            String[] need = {"把灯关掉", "帮我把音量调到5", "现在电量多少", "查一下网络",
                             "做个握手动作", "现在几点"};
            String[] notNeed = {"你好", "讲个笑话", "你是谁", ""};
            boolean bad = false;
            for (String g : need) if (!goalNeedsTool(g)) { bad = true; log.append("  ✗ 该判需要工具却没判：" + g + "\n"); }
            for (String g : notNeed) if (goalNeedsTool(g)) { bad = true; log.append("  ✗ 无关闲聊被误判需要工具：" + g + "\n"); }
            n++; if (!bad) ok++;

            // traceUsedTool / traceToolSucceeded：只有"第N轮 TOOL … => 结果"才算数，且要看结果成败
            java.util.List<String> t1 = new java.util.ArrayList<String>();
            t1.add("第1轮 未用协议 → 整段当答案");
            boolean noTool = !traceUsedTool(t1);
            java.util.List<String> t2 = new java.util.ArrayList<String>();
            t2.add("第1轮 TOOL led all off => sdk:m");
            boolean yesTool = traceUsedTool(t2);
            boolean noToolNull = !traceUsedTool(null);
            n++; if (noTool && yesTool && noToolNull) ok++;
            else log.append("  ✗ traceUsedTool 判错（noTool=" + noTool + " yesTool=" + yesTool
                    + " null=" + noToolNull + "）\n");
        }
        // ---- v210：动作类目标必须"写类能力成功过"，且**被拒的工具不算成功** ----
        {
            boolean bad = false;
            if (!goalWantsAction("把灯关掉") || !goalWantsAction("帮我把音量调到5")) { bad = true; log.append("  ✗ 动作类目标未识别\n"); }
            if (goalWantsAction("现在电量多少") || goalWantsAction("你好")) { bad = true; log.append("  ✗ 纯查询/闲聊被当成动作类\n"); }
            // ★ 否定式："别关灯"不该被推去做动作（否则兜底会把它推成"把灯开了"）
            if (goalWantsAction("别关灯") || goalWantsAction("不用调音量了")) { bad = true; log.append("  ✗ 否定式目标被当成动作类\n"); }
            n++; if (!bad) ok++;

            // (a) 只有失败的写调用 + 一次成功的查询调用 ⇒ 写类**没**成功过
            java.util.List<String> tr = new java.util.ArrayList<String>();
            tr.add("第1轮 TOOL led blue off => bad color: blue");
            tr.add("第2轮 TOOL doc 灯 => - led [led]：胸口/头顶 LED 灯（命中:灯）");
            boolean kindOk = traceToolSucceeded(tr, Abilities.WRITE);
            boolean anyOk = traceToolSucceeded(tr, null);
            n++; if (!kindOk && anyOk) ok++;
            else log.append("  ✗ \"调了但被拒→doc查成功\" 被误判成写类成功（write=" + kindOk
                    + " any=" + anyOk + "）\n");

            // (b) 写类真成功过
            java.util.List<String> tr2 = new java.util.ArrayList<String>();
            tr2.add("第1轮 TOOL led b off => sdk:f");
            n++; if (traceToolSucceeded(tr2, Abilities.WRITE)) ok++;
            else log.append("  ✗ 写类成功未被识别\n");

            // (c) 空/异常 trace 不算成功
            n++; if (!traceToolSucceeded(null, null) && !traceToolSucceeded(new java.util.ArrayList<String>(), Abilities.WRITE)) ok++;
            else log.append("  ✗ 空 trace 被当成成功\n");
        }

        StringBuilder out = new StringBuilder();
        out.append("多轮思考自检 ").append(ok).append('/').append(n).append(ok == n ? " PASS" : " FAIL");
        if (ok != n) out.append('(').append(n - ok).append(')');
        out.append('\n');
        if (log.length() > 0) out.append(log);
        out.append("协议工具由能力真源 Abilities 现场生成（read+write+plan = ")
           .append(Abilities.allowedOps().length).append(" 个）；安全红线 ")
           .append(Abilities.forbiddenOps().length).append(" 个只读不可改\n");
        return out.toString();
    }

    private static boolean eq(List<String> a, String[] b) {
        if (a.size() != b.length) return false;
        for (int i = 0; i < b.length; i++) if (!b[i].equals(a.get(i))) return false;
        return true;
    }
}
