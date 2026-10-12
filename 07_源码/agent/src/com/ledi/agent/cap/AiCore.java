package com.ledi.agent.cap;

import com.ledi.agent.core.AgentService;
import com.ledi.agent.core.CommandRouter;
import com.ledi.agent.sdk.FaceController;
import com.ledi.agent.voice.IntentResolver;
import com.ledi.agent.core.J;
import com.ledi.agent.core.MainThread;
import com.ledi.agent.voice.Speaker;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * AiCore —— 机器人端 AI 大脑（newcore 重构核心）。
 *
 * 让机器人「直接连接 AI」，不再依赖电脑网关：
 *   直连（默认）：机器人自己调 OpenAI 兼容大模型接口（DeepSeek / 通义 / Kimi / Ollama…），
 *                Key 在齿轮设置里填，机器人关机重启都记住。
 *   回落网关： 直连失败（没配 Key / 无外网 / 报错）时，自动把对话交给电脑网关（18080→8080）。
 *
 * 支持「两种都支持，可切换」：齿轮设置里选 engine=direct | gateway | auto。
 *   auto = 先直连，失败回落到网关（默认，行为最稳）。
 *
 * <p>这是新系统与旧系统最大的区别：旧系统机器人必须靠电脑网关才会「说话」，
 * 现在机器人自己就能对话、能主动说话，电脑关机也能用。
 */
public class AiCore {

    private static final String TAG = AgentService.TAG;
    private static final String PREFS = "ledi_agent";

    // 直连大模型配置键
    static final String KEY_BASE = "ai_base";        // 如 https://api.deepseek.com/v1
    static final String KEY_KEY = "ai_key";          // 如 sk-xxxx
    static final String KEY_MODEL = "ai_model";      // 如 deepseek-chat
    static final String KEY_ENGINE = "ai_engine";    // direct | gateway | auto

    public static final String ENGINE_DIRECT = "direct";
    public static final String ENGINE_GATEWAY = "gateway";
    public static final String ENGINE_AUTO = "auto";

    private final Context ctx;
    private final Speaker speaker;   // 网关回落用（网关 TTS / 对话端点）

    // 最近一次对话结果（供诊断 / 主动说话参考）
    private volatile String lastReply = "";
    private volatile String lastError = "";

    /** 单线程池：所有 AI 网络请求都在这上面跑（见 {@link #ask} 的注释）。 */
    private static final java.util.concurrent.ExecutorService NET =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "ledi-ai-net");
                            t.setDaemon(true);
                            return t;
                        }
                    });

    /** 主线程等待上限。超过就放弃本次（宁可这一句不说，也不能让主线程卡成 ANR）。 */
    private static final long ASK_TIMEOUT_MS = 20000L;

    public AiCore(Context ctx, Speaker speaker) {
        this.ctx = ctx;
        this.speaker = speaker;
    }

    // ================= 配置 =================

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String engine() {
        String e = prefs().getString(KEY_ENGINE, ENGINE_AUTO);
        return (e == null || e.isEmpty()) ? ENGINE_AUTO : e;
    }

    public String base() { return prefs().getString(KEY_BASE, ""); }
    public String key() { return prefs().getString(KEY_KEY, ""); }
    public String model() { return prefs().getString(KEY_MODEL, "deepseek-chat"); }

    public void setEngine(String e) {
        prefs().edit().putString(KEY_ENGINE, e).commit();
    }

    public void setDirect(String base, String key, String model) {
        prefs().edit()
                .putString(KEY_BASE, base == null ? "" : base.trim())
                .putString(KEY_KEY, key == null ? "" : key.trim())
                .putString(KEY_MODEL, model == null || model.trim().isEmpty()
                        ? "deepseek-chat" : model.trim())
                .commit();
    }

    /** 直连是否已配置可用（base + key 都有） */
    public boolean directReady() {
        return !base().isEmpty() && !key().isEmpty();
    }

    // ================= 状态 =================

    public String lastReply() { return lastReply; }
    public String lastError() { return lastError; }

    /** 电脑端网关的基址（去掉尾斜杠）。没配返回空串。 */
    public String gatewayBase() {
        String gw = speaker == null ? null : speaker.getGateway();
        if (gw == null || gw.trim().isEmpty()) return "";
        gw = gw.trim();
        return gw.endsWith("/") ? gw.substring(0, gw.length() - 1) : gw;
    }

    public String statusJson() {
        return "{\"engine\":\"" + J.esc(engine()) + "\""
                + ",\"direct_ready\":" + directReady()
                + ",\"base\":\"" + J.esc(mask(base())) + "\""
                + ",\"model\":\"" + J.esc(model()) + "\""
                + ",\"last_reply\":\"" + J.esc(abbrev(lastReply, 60)) + "\""
                + ",\"last_error\":\"" + J.esc(abbrev(lastError, 120)) + "\""
                // ★ 记忆长度必须能一眼看到 —— 否则"到底记了几轮"只能靠猜
                + ",\"memory\":" + TalkLog.statusJson() + "}";
    }

    private static String mask(String s) {
        if (s == null || s.isEmpty()) return "";
        // 只露 scheme+host，隐藏 key 等敏感段
        if (s.contains("://")) {
            int i = s.indexOf("://");
            int j = s.indexOf('/', i + 3);
            return s.substring(0, j > 0 ? j : s.length());
        }
        return s;
    }

    private static String abbrev(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    // ================= 对话核心 =================

    /**
     * 问 AI 一句话，返回 AI 的文本回复。
     *
     * @param userText 用户说的话（可为空，用于主动说话时 AI 自己找话题）
     * @param context  给 AI 的上下文（传感器状态等，帮助它决定说什么/做什么）
     * @return AI 回复文本；失败返回 ""（调用方决定怎么兜底）
     */
    public String ask(final String userText, final String context) {
        return ask(userText, context, null);
    }

    /**
     * 语音那一句（v93）：把用户原话放进**真正的 user 轮**，识别器/方位说明走 preface。
     *
     * <p>为什么要拆开：此前语音通路的写法是 {@code ask("", VOICE_HINT + 用户原话)} ——
     * 用户说的话被塞进 {@code system} 的"当前状态"里，而 user 轮永远是那句
     * "（主人没说话，你主动说点什么吧）"。两个后果：① 大模型把用户的话当成"环境状态"
     * 而不是"主人对我说的话"，语气与职责都偏；② 多轮记忆根本无从建立
     * （历史里存不到"用户说了什么"）。现在原话归 user 轮，旁白归 preface。</p>
     *
     * @param hint 例如 {@code VOICE_HINT}（"这是语音识别结果，可能有同音错字…"）
     * @param dir  说话人的方位（"左"/"右"/"正前"或空）；非正前时会补一句方位提示
     * @param env  环境/传感器上下文（可为 null）
     */
    public String askVoice(String userText, String hint, String dir, String env) {
        // 没听清就别伪造一个 user 轮 —— 交回旧语义（由调用方给追问提示词）
        if (userText == null || userText.isEmpty()) return ask(userText, env);
        StringBuilder p = new StringBuilder();
        if (hint != null && !hint.isEmpty()) p.append(hint);
        if (dir != null && !dir.isEmpty() && !"正前".equals(dir)) {
            p.append("（主人现在在你的").append(dir).append("方向，答话时请留意这一点。）");
        }
        return ask(userText, env, p.toString());
    }

    public String askVoice(String userText, String hint, String dir) {
        return askVoice(userText, hint, dir, null);
    }

    public String ask(final String userText, final String context, final String preface) {
        // ★★ 网络绝不能在主线程上跑。
        // 之前这里直接在调用线程上开 HttpURLConnection，而主动说话（ProactiveTalk）
        // 是从主线程的定时器里调的 → 真机上必然抛
        //   NetworkOnMainThreadException，
        // 又因为配的是「强制 direct 不回退」，表现为「AI 永远是坏的」而 /ai 里
        // 只有一行 last_error 看不出所以然（v84 真机实测）。
        //
        // 修法：主线程调用时把整段（直连 or 网关）丢到后台线程，**同步等结果**，
        //      语义与原来完全一致 —— 所以下面 6 个调用点一行都不用改。
        if (MainThread.isMain()) {
            java.util.concurrent.FutureTask<String> task =
                    new java.util.concurrent.FutureTask<String>(
                            new java.util.concurrent.Callable<String>() {
                                @Override public String call() {
                                    return askInner(userText, context, preface);
                                }
                            });
            NET.execute(task);
            try {
                return task.get(ASK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                task.cancel(true);
                lastError = "AI 请求超时（" + (ASK_TIMEOUT_MS / 1000) + "s）";
                return "";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = "AI 请求被中断";
                return "";
            } catch (Throwable e) {
                lastError = "AI 请求异常: " + e.getClass().getSimpleName();
                return "";
            }
        }
        // 本来就在后台线程（HTTP 路由等）就直接跑，不用多绕一层
        return askInner(userText, context, preface);
    }

    /**
     * {@link #ask} 的真正的实现体（会阻塞）。
     *
     * <p>★ v143 起这一层只做一件事：把结果交给 {@link Tone#audit} 过一遍，
     * 然后原样返回。放在这里而不是各个分支里，是因为上面有<b>两条</b>出口
     * （直连成功 / 回落网关），而"早教腔审计"对两者都成立 ——
     * 这是本项目的老教训「修在一个地方 ≠ 修好了」：只在直连分支埋点，
     * 一旦 engine 切到 gateway 就会静默漏统计，验收时看着是"零命中"，实则根本没测。</p>
     */
    private String askInner(String userText, String context, String preface) {
        String r = askRaw(userText, context, preface);
        if (r != null && !r.isEmpty()) Tone.audit(r);
        return r;
    }

    /** 真正的请求体（不含审计）。外部一律走 {@link #ask}。 */
    private String askRaw(String userText, String context, String preface) {
        String eng = engine();
        if (ENGINE_GATEWAY.equals(eng)) {
            return askGateway(userText);
        }
        if (ENGINE_DIRECT.equals(eng) || directReady()) {
            String r = askDirect(userText, context, preface);
            if (!r.isEmpty()) return r;
            // 保留 askDirect 里记录的真实错误（如认证失败/网络异常），
            // 只在末尾追加"不回退"说明，别覆盖底层错误 → 便于诊断
            String cause = lastError;
            if (ENGINE_DIRECT.equals(eng)) {
                lastError = (cause == null || cause.isEmpty() ? "直连失败" : cause)
                        + "，且已强制 direct 模式，不回退";
                return "";
            }
            // auto 模式回落到网关
            lastError = (cause == null || cause.isEmpty() ? "直连失败" : cause) + "，回落网关";
        }
        return askGateway(userText);
    }

    // ---------------- 直连大模型 ----------------

    private String askDirect(String userText, String context, String preface) {
        try {
            String b = base();
            String k = key();
            String m = model();
            if (b.isEmpty() || k.isEmpty()) {
                lastError = "未配置直连大模型（base/key 为空）";
                return "";
            }
            // ★ v202：URL 拼接与 HTTP 收发已搬进 postChatCompletions（与多轮思考共用），此处不再需要。

            // 人格提示词：由 Tone 统一提供（v143 成人化）。
            //
            // ★ 为什么不再在这里写死：这段提示词决定了**机器人说的每一句话的语气**，
            //   是全项目最大的一处"调性来源"。它曾经写死成"一个儿童陪伴机器人"，
            //   于是无论主动说话、对话应答、还是摸头之后的那句回应，全是早教腔。
            //   收进 Tone 之后既能随档位切换，也能被 GET /tone 原样打出来做验收对照
            //   （见 Tone 的类注释："修在一个地方 ≠ 修好了"）。
            // ★★ 表情/动作/灯光三份词表仍是**原样**放在 Tone.persona() 里：
            //   它们映射到原厂引擎的 id，改字面就是"点了没反应"（FaceController 的别名表）。
            StringBuilder sys = new StringBuilder();
            sys.append(Tone.persona());
            if (context != null && !context.isEmpty()) {
                sys.append("当前状态：").append(context).append("。");
            }
            // ★ v214：长期记忆旁白（**唯一出处** Memory.brief()）。让"你记得我吗"答得出真值，
            //   也让每次对话都带着"我们认识多久、聊过几次、你提过什么"的语境 —— 这正是
            //   用户要的"别太人机"的根。措辞只在 Memory.brief() 写一份，不在这里另编。
            try { sys.append("你们的过往：").append(Memory.brief()); } catch (Throwable ignored) {}

            // ★★ 多轮记忆（v93）。此前这里只有 system + 一条 user，机器人"每句都像
            //    第一次见我"。现在把落盘的最近若干轮回填进来。
            //    纪律：进历史的只有 ask（用户原话）与 say（去标签的播报文本），
            //    VOICE_HINT / 方位提示这类"旁白"**不进** —— 见 TalkLog 的类注释。
            String history = TalkLog.historyFragment();

            StringBuilder body = new StringBuilder();
            body.append("{\"model\":\"").append(J.esc(m)).append("\"")
                .append(",\"messages\":[")
                .append("{\"role\":\"system\",\"content\":\"")
                    .append(J.esc(sys.toString())).append("\"}")
                .append(history)
                .append(",{\"role\":\"user\",\"content\":\"")
                    .append(preface == null || preface.isEmpty()
                            ? "" : J.esc(preface))
                    .append(J.esc(userText == null || userText.isEmpty()
                            ? Tone.noUserHint() : userText))
                    .append("\"}]")
                .append(",\"temperature\":0.7,\"max_tokens\":200}");

            String reply = postChatCompletions(body.toString(), k);
            if (reply.isEmpty()) return "";
            lastReply = reply;
            lastError = "";
            return reply;
        } catch (Exception e) {
            lastError = "直连异常: " + e.getClass().getSimpleName() + " " + abbrev(String.valueOf(e), 80);
            Log.e(TAG, "aiCore direct fail", e);
            return "";
        }
    }

    /**
     * 发一次 {@code chat/completions}，返回 {@code choices[0].message.content}；
     * 失败返回 {@code ""} 并把原因写进 {@link #lastError}。
     *
     * <p>★ 抽出来是给 {@link #think} 复用：单轮 {@code askDirect} 与多轮思考走**同一段**
     * HTTP，不再各写一份（本项目规矩：同一条语义散在两处，迟早分叉）。</p>
     */
    private String postChatCompletions(String body, String key) {
        HttpURLConnection c = null;
        try {
            String b = base();
            if (b.isEmpty() || key == null || key.isEmpty()) {
                lastError = "未配置直连大模型（base/key 为空）";
                return "";
            }
            String url = b.endsWith("/") ? b + "chat/completions" : b + "/chat/completions";
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setDoOutput(true);
            DataOutputStream os = new DataOutputStream(c.getOutputStream());
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            if (code >= 400) {
                lastError = "大模型 HTTP " + code + ": " + abbrev(sb.toString(), 120);
                return "";
            }
            String reply = extractContent(sb.toString());
            if (reply.isEmpty()) {
                lastError = "大模型返回空内容";
                return "";
            }
            lastError = "";
            return reply;
        } catch (Exception e) {
            lastError = "直连异常: " + e.getClass().getSimpleName() + " " + abbrev(String.valueOf(e), 80);
            Log.e(TAG, "aiCore chat fail", e);
            return "";
        } finally {
            try { if (c != null) c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    // ==================== 多轮思考（v202） ====================

    /** 多轮思考的整体超时：轮数 × 单次 ≈ 最大 15s，留足余量，但绝不挂死调用线程。 */
    private static final long THINK_TIMEOUT_MS = 55000L;

    /**
     * 多轮思考：允许模型分几步向本机要信息 / 让机器人先做动作，再给最终回答。
     *
     * <p>与 {@link #ask}（单轮）并列。传输用 {@link #postChatCompletions}（同一条 HTTP），
     * 工具循环用 {@link Thinking#run}，工具箱由调用方给（见 {@link Thinking#deviceToolBox()}）。</p>
     *
     * @param rounds   最多几步（内部会钳到 {@link Thinking#MAX_ROUNDS}）
     * @param tb       工具箱（本机白名单能力）
     * @param traceOut 传非 null 则把每一轮的动作写进去（验收/诊断）
     * @return 最终要说出口的文本；失败返回 ""
     */
    public String think(final String goal, final int rounds, final Thinking.ToolBox tb,
                        final java.util.List<String> traceOut) {
        // ★★★ v206：思考中亮绿灯（用户 2026-10-11）。挂在这里而不是调用点 ——
        //   think() 是**所有**多轮思考的唯一入口（语音链路 replyForAI、
        //   POST /think、workflow 编排都汇到它），一处生效即全覆盖；
        //   try/finally 保证超时/异常/中断路径也一定灭灯（否则绿灯会永久亮着）。
        AgentService.noteThinking(true);
        try {
            return think0(goal, rounds, tb, traceOut);
        } finally {
            AgentService.noteThinking(false);
        }
    }

    private String think0(final String goal, final int rounds, final Thinking.ToolBox tb,
                          final java.util.List<String> traceOut) {
        if (MainThread.isMain()) {
            java.util.concurrent.FutureTask<String> task =
                    new java.util.concurrent.FutureTask<String>(
                            new java.util.concurrent.Callable<String>() {
                                @Override public String call() {
                                    return thinkInner(goal, rounds, tb, traceOut);
                                }
                            });
            NET.execute(task);
            try {
                return task.get(THINK_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                task.cancel(true);
                lastError = "多轮思考超时（" + (THINK_TIMEOUT_MS / 1000) + "s）";
                return "";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = "多轮思考被中断";
                return "";
            } catch (Throwable e) {
                lastError = "多轮思考异常: " + e.getClass().getSimpleName();
                return "";
            }
        }
        return thinkInner(goal, rounds, tb, traceOut);
    }

    private String thinkInner(String goal, int rounds, final Thinking.ToolBox tb,
                              java.util.List<String> traceOut) {
        // ★ 跨轮记忆：与单轮 ask 用**同一份**历史（TalkLog.pick），所以多轮思考不会
        //   "每句都像第一次见主人"。历史是消息对象列表，直接插进 Thinking 的 messages。
        final java.util.List<String> history;
        try { history = TalkLog.historyMessages(); } catch (Throwable t) { return fallback(goal, t); }

        Thinking.Transporter t = new Thinking.Transporter() {
            @Override public String complete(java.util.List<String> msgs) {
                StringBuilder body = new StringBuilder();
                body.append("{\"model\":\"").append(J.esc(model())).append("\"")
                    .append(",\"messages\":[");
                for (int i = 0; i < msgs.size(); i++) {
                    if (i > 0) body.append(',');
                    body.append(msgs.get(i));
                }
                // 多轮思考的答复可以比单轮长一点：它要写"想→工具→再想"的中间过程
                body.append("],\"temperature\":0.7,\"max_tokens\":400}");
                return postChatCompletions(body.toString(), key());
            }
        };

        StringBuilder sys = new StringBuilder();
        sys.append(Tone.persona());
        // ★ v209：这份状态是**此刻**读的，必须压过历史对话里的旧值 ——
        //   实测模型曾拿历史里的旧电量作答（报 32%，实际 97%）。措辞写死"以此为准"。
        sys.append("★ 当前真实状态（实时读取，**以此为准**；历史对话里的任何旧状态一律作废）：")
           .append(Thinking.stateBrief()).append("。\n");
        // ★ v214：长期记忆旁白（**唯一出处** Memory.brief()）。与上面那行同源纪律。
        try { sys.append("你们的过往：").append(Memory.brief()).append("\n"); } catch (Throwable ignored) {}
        // ★ v203：本机能力索引旁白 —— 让模型"自己查"而不是凭记忆猜动作名/表情名。
        //   放在 protocolPrompt **之前**：先讲"你有哪些能力可查"，再讲"怎么调"。
        sys.append(Thinking.capabilityPrompt());
        sys.append(Thinking.protocolPrompt());

        Thinking.Result r = Thinking.run(t, tb, sys.toString(), history, goal, rounds);
        // ★★★ v209/v210：反"编造执行结果"的**引擎级**兜底（第一道闸；提示词铁律之外）。
        //   来历（2026-10-11 真机实测，两连击）：
        //     · 让它「把灯关掉」「把音量调到5」，模型**一个工具都没调**就回「关了。」「5了。」
        //       —— 用户会照着假信息行动，这是"AI 完全接管"下最危险的失败形态；
        //     · 改成"必须调工具"之后，它调了 {@code led blue off} 被拒，又调 {@code doc} 查成功，
        //       于是宣布"关掉了"——**调了 ≠ 成功**。所以判据必须是"**成功过**"，
        //       而且动作为主的目标还必须是**写类能力**成功过。
        //   复核轮同样没给出回答时，**保留原答案**（宁可答得差，也不能因为兜底把话吞了）。
        boolean needAny = Thinking.goalNeedsTool(goal);
        boolean needWrite = Thinking.goalWantsAction(goal);
        boolean anyOk = Thinking.traceToolSucceeded(r.trace, null);
        boolean writeOk = Thinking.traceToolSucceeded(r.trace, Abilities.WRITE);
        if ((needAny && !anyOk) || (needWrite && !writeOk)) {
            java.util.List<String> h2 = new java.util.ArrayList<String>();
            if (history != null) h2.addAll(history);
            h2.add(Thinking.msg("user", goal == null ? "" : goal));
            h2.add(Thinking.msg("assistant", r.finalText == null ? "" : r.finalText));
            String nudge = "★ 检查没过：这件事涉及本机状态或要让机器人动手，但你**没有任何一次成功的工具执行**"
                    + "（要么没调、要么调了被拒或报错）。规矩：\n"
                    + "  ① 先输出一行 `TOOL 工具名 参数`，并确认它**成功**（看 `=>` 后面的实际结果）；\n"
                    + "  ② 参数被拒就照提示改（例如 led 的颜色只认单个字母 r/g/b/y/p/all，不是 blue）；\n"
                    + "  ③ 真做不到就**如实**说「没做成」，绝对不许说「已经做了」。现在按协议重来。";
            if (traceOut != null) traceOut.add("— 复核①：没有成功的工具执行 → 打回重来一轮 —");
            Thinking.Result r2 = Thinking.run(t, tb, sys.toString(), h2, nudge, rounds);
            if (r2.finalText != null && !r2.finalText.isEmpty()) {
                r = r2;
            } else if (traceOut != null) {
                traceOut.add("复核①仍无回答，保留原答案");
            }
        }
        // ★★★ v210：第二道闸 —— 工具**调对了**但用满轮数没来得及说话（真机实测：
        //   「把关灯」时它第 1 轮色名写错、第 2 轮 doc 查、第 3 轮连发两条 led 才成功，
        //   结果灯真的灭了、却一句话没说）。这里**接着现有上下文**再要一句收尾，
        //   而不是从零重来（重来会丢掉刚拿到的工具结果，模型会再去调一遍、死循环）。
        if (r.truncated && Thinking.traceUsedTool(r.trace)) {
            java.util.List<String> h3 = r.lastMsgs != null
                    ? new java.util.ArrayList<String>(r.lastMsgs)
                    : new java.util.ArrayList<String>();
            if (h3.isEmpty()) {
                if (history != null) h3.addAll(history);
                h3.add(Thinking.msg("user", goal == null ? "" : goal));
            }
            if (traceOut != null) traceOut.add("— 复核②：工具已执行但没来得及说话 → 补一轮收尾 —");
            String settle = "工具已经执行完了，结果就在上面。现在**只**输出一行 "
                    + "`FINAL <要说给主人听的话>`，不要再调用任何工具。";
            Thinking.Result r3 = Thinking.run(t, tb, sys.toString(), h3, settle, 1);
            if (r3.finalText != null && !r3.finalText.isEmpty()) r = r3;
            else if (traceOut != null) traceOut.add("复核②仍无 FINAL");
        }
        if (traceOut != null) traceOut.addAll(r.trace);
        if (r.finalText != null && !r.finalText.isEmpty()) {
            lastReply = r.finalText;
            return r.finalText;
        }
        // 想完了却没有 FINAL（用满轮数 / 调用失败）—— 交给调用方回退单轮，别硬说半截话
        if (lastError == null || lastError.isEmpty()) {
            lastError = r.truncated ? "多轮思考用满轮数仍未给出最终回答" : "多轮思考没有产出";
        }
        return "";
    }

    /** 组历史失败时的兜底：直接回退成单轮问答（不让"取历史"这种小事把整轮打死）。 */
    private String fallback(String goal, Throwable t) {
        lastError = "读历史失败，回退单轮: " + t;
        return ask(goal, null);
    }

    /** 从 OpenAI 兼容返回里取 choices[0].message.content（不引第三方 JSON 库，手写容错解析） */
    static String extractContent(String json) {
        // 找 "content":"..." 最后一个（message.content 在返回末尾）
        String pat = "\"content\":\"";
        int i = json.lastIndexOf(pat);
        if (i < 0) return "";
        i += pat.length();
        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(i + 1);
                if (n == 'n') sb.append('\n');
                else if (n == 't') sb.append('\t');
                else if (n == 'r') sb.append('\r');
                else sb.append(n);
                i += 2;
                continue;
            }
            if (c == '"') break;
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    // ---------------- 回落网关 ----------------

    /**
     * 走电脑端网关对话（POST {@code /console/ask}）。
     *
     * <p>为什么不复用 {@code /console/emergency}：那个端点是"我把成品话交给你下发"，
     * <b>不经过大模型</b>。机器人要说话，需要的是"用户说了什么 → 大模型怎么答"，也就是
     * 电脑端控制台里选的那个大脑（API 或**网页版 DeepSeek**）。这一条才是把
     * 「网页端大脑」接到机器人身上的唯一通路。</p>
     *
     * <p>返回的 {@code reply} 里已经带了 {@code [表情:x] [动作:y]} 标签，直接丢给
     * {@code CommandRouter.applyAiTags()} 就能边说边动 —— 与直连模式完全同构。</p>
     */
    private String askGateway(String userText) {
        String base = gatewayBase();
        if (base.isEmpty()) {
            lastError = "无网关地址（齿轮设置里填电脑端地址）";
            return "";
        }
        String url = base + "/console/ask";
        try {
            String body = "{\"text\":\"" + J.esc(userText == null ? "" : userText)
                    + "\",\"uid\":\"robot\",\"note\":\"\"}";
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            // 走网页版大脑时要等浏览器那边出结果，给足时间（比直连慢是已知的）
            c.setReadTimeout(25000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            c.setDoOutput(true);
            DataOutputStream os = new DataOutputStream(c.getOutputStream());
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (in != null) {
                BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
            }
            if (code >= 400) {
                lastError = "网关返回 " + code + "：" + snippet(sb.toString(), 120);
                return "";
            }
            String reply = jsonStr(sb.toString(), "reply");
            if (reply == null || reply.trim().isEmpty()) {
                lastError = "网关没给回复：" + snippet(sb.toString(), 120);
                return "";
            }
            lastError = "";
            return reply.trim();
        } catch (Throwable t) {
            lastError = "网关对话失败：" + t;
            return "";
        }
    }

    /**
     * 把一条对话推给电脑端控制台的「对话日志」。
     *
     * <p>为什么必须由机器人主动推：控制台只能看见"经过自己"的对话 ——
     * 机器人走 <b>直连</b>（自带 key 直接问大模型）时电脑端是完全瞎的，
     * 用户看到的现象就是"机器人明明答了，控制台一个字都没有"。
     * 推一份上来，三条通路（直连 / 网关 / 原厂框架）才凑得齐。</p>
     *
     * <p>代价必须压到 0：开子线程、1.2s 连接超时、不重试、失败静默。
     * 这只是在记日志，绝不能因为它把应答拖慢或在控制台刷一堆错。</p>
     */
    public void pushDialog(final String via, final String ask, final String reply,
                           final String sector, final String face, final String motion) {
        pushDialog(via, ask, reply, reply, sector, face, motion, null, 0L);
    }

    /**
     * 同上，但补上"实际播报文本 / 备注 / 本轮耗时"。
     *
     * <p>★ v93 起这里同时是**机器人本地长期日志**的唯一落点（{@link TalkLog}）：
     * 先本地落一条并拿到 tid，再用**同一个 tid** 推给电脑端 ⇒ 两份日志可按 tid
     * 一对一对照（{@code _dialoglog.py}）。所以这个方法是「双端对照表」的地基，
     * 新增对话通路时别忘了走它。</p>
     *
     * @param spoken 去掉 [表情:…] 标签之后真正播出去的文本；为空则退回 reply
     * @param note   备注（如"没识别到内容（error=7）"）
     * @param ms     本轮端到端耗时（毫秒）
     */
    public void pushDialog(final String via, final String ask, final String reply,
                           final String spoken, final String sector, final String face,
                           final String motion, final String note, final long ms) {
        pushDialog(via, ask, reply, spoken, sector, face, motion, note, ms, null);
    }

    /**
     * 同上，且允许**改写 engine 字段**。
     *
     * <p>为什么需要：v94 起有些对话根本不经过大模型（{@code IntentResolver.local} 的本地直通）。
     * 那时 {@code engine()} 会返回 {@code direct}/{@code gateway} —— 于是双端对照表上会写着
     * "这轮是大模型答的"，而它其实一个字节都没发出去。对照表是用来**查账**的，
     * 记错一行就等于没记，所以 engine 必须由调用方按实际路线给。</p>
     *
     * @param engineOverride 非空则覆盖 engine 字段（本地直通传 {@code "local"}）
     */
    public void pushDialog(final String via, final String ask, final String reply,
                           final String spoken, final String sector, final String face,
                           final String motion, final String note, final long ms,
                           final String engineOverride) {
        // ★ 用户刚聊过 ⇒ 让"心跳闲聊"闭嘴一段时间（默认 5 分钟，见 ProactiveTalk.noteUserTalk）。
        //   埋在这里而不是各个调用点：所有对话通路（唤醒应答 / 常识别 / 电脑端提问 / 触摸反应）
        //   最终都走这个 pushDialog，"一处生效"才不会漏。
        //   只认 voice/ask —— via=proactive 是我们自己说的，不能自我续期。
        if ("voice".equals(via) || "ask".equals(via)) {
            ProactiveTalk p = AgentService.proactiveTalk;
            if (p != null) p.noteUserTalk();
            // ★ v214：跨轮"关系"计数（认识第几天 / 一起聊过几次）。埋在这个**唯一漏斗**里，
            //   所有对话通路（唤醒应答 / 常识别 / 电脑端提问）都必然走到，不会漏记。
            try { Memory.noteTalk(); } catch (Throwable ignored) {}
            // ★ v214：把用户这句话喂给情绪引擎判夸/骂（只判夸骂，不重复记"有人说话" ——
            //   唤醒那一步已经记过了）。「被夸心情变好、被骂变差」是陪伴感的主要来源。
            try { Emotion.notePraiseScold(ask); } catch (Throwable ignored) {}
        }
        final String base = gatewayBase();
        final String eng = (engineOverride != null && !engineOverride.isEmpty())
                ? engineOverride : engine();
        // ★ 本地先落，保证"没网 / 没连电脑"时机器人自己也有账；tid 由它产生
        final String tid = TalkLog.add(via, ask,
                (spoken == null || spoken.isEmpty()) ? reply : spoken,
                reply, eng, sector, ms, note);
        if (base.isEmpty()) return;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    StringBuilder b = new StringBuilder();
                    b.append("{\"via\":\"").append(J.esc(via)).append("\",")
                     .append("\"tid\":\"").append(J.esc(tid)).append("\",")
                     .append("\"ask\":\"").append(J.esc(ask)).append("\",")
                     .append("\"reply\":\"").append(J.esc(spoken == null || spoken.isEmpty()
                             ? reply : spoken)).append("\",")
                     .append("\"engine\":\"").append(J.esc(eng)).append("\"");
                    if (sector != null && !sector.isEmpty())
                        b.append(",\"sector\":\"").append(J.esc(sector)).append("\"");
                    if (face != null && !face.isEmpty())
                        b.append(",\"face\":\"").append(J.esc(face)).append("\"");
                    if (motion != null && !motion.isEmpty())
                        b.append(",\"motion\":\"").append(J.esc(motion)).append("\"");
                    if (note != null && !note.isEmpty())
                        b.append(",\"note\":\"").append(J.esc(note)).append("\"");
                    if (ms > 0) b.append(",\"ms\":").append(ms);
                    if (reply != null && !reply.isEmpty() && !reply.equals(spoken))
                        b.append(",\"raw\":\"").append(J.esc(abbrev(reply, 300))).append("\"");
                    b.append('}');
                    HttpURLConnection c = (HttpURLConnection)
                            new URL(base + "/console/dialog").openConnection();
                    c.setConnectTimeout(1200);
                    c.setReadTimeout(1500);
                    c.setRequestMethod("POST");
                    c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    c.setDoOutput(true);
                    DataOutputStream os = new DataOutputStream(c.getOutputStream());
                    os.write(b.toString().getBytes("UTF-8"));
                    os.flush();
                    os.close();
                    c.getResponseCode();
                    c.disconnect();
                } catch (Throwable ignored) {
                    // 记日志失败不影响任何事，静默
                }
            }
        }, "dlg-push").start();
    }

    /** 从一个 JSON 串里取字符串字段（不引第三方库，够用即可）。 */
    private static String jsonStr(String json, String key) {        if (json == null) return null;
        String pat = "\"" + key + "\"";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        i = json.indexOf(':', i + pat.length());
        if (i < 0) return null;
        i++;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return null;
        i++;
        StringBuilder sb = new StringBuilder();
        for (; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '\\') {
                i++;
                if (i >= json.length()) break;
                char e = json.charAt(i);
                if (e == 'n') sb.append('\n');
                else if (e == 't') sb.append('\t');
                else if (e == 'r') sb.append('\r');
                else if (e == 'u' && i + 4 < json.length()) {
                    try {
                        sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                        i += 4;
                    } catch (Throwable ignored) { }
                } else sb.append(e);
            } else if (ch == '"') {
                break;
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static String snippet(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    // ================= 主动说话辅助 =================

    /**
     * 让 AI 主动说一句话（无用户输入），返回 AI 找的话题。
     * 由 ProactiveTalk 定时/传感器触发时调用。
     */
    public String proactive(String context) {
        return ask("", context);
    }
}
