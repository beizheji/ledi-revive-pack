package com.ledi.agent.ui;

/**
 * 屏保总闸：屏保期间**只放行**下面 ALLOW 里的端点（外加一条 console GET 前缀）。
 *
 * <p>★ v221 结构重构：这份白名单原先是 {@link CommandRouter#handle} 里一条跨 130 行、
 * 内嵌几十条注释的巨型布尔式 —— 每加一个端点就得回去补一句、漏了不报错。
 * 现在收敛为<b>声明式表</b>：加减放行项只改 {@code ALLOW} 一处，
 * 并由 {@code _sleepgate_check.py} 与冻结基准 {@code _sleepgate_expected.json} 机械对差。</p>
 *
 * <p><b>判据（不许静默改行为）</b>：{@code blocked()} 的语义必须与原布尔式逐字等价 ——
 * 即「sleeping 且 act 不在 ALLOW 且 act 不以 GET /console/ 开头」才拒绝。</p>
 *
 * <p>放行理由（v128~v214 逐条，原样保留）：</p>
 * <pre>
 * 屏保期间除"叫醒 / 查状态"外一律不受理：否则任意一条 /face、/img、/talk、
 * /motion 都能把铺满的图顶掉、或让机器人出声，屏保就成了一张随时被撕掉的纸。
 * 放在所有路由之前，是为了**不会漏**——加新路由的人不必记得回来补一条判断。
 *
 * ★ /headkey 例外（v128）：它等价于"物理按一下头顶键"，是**本地按键的远程替身**。
 *   放它进来是因为屏保的出口本来就该有两条（轻点屏幕 / 长按头顶键），
 *   而"长按"只有人手按得动 ⇒ 不放进来，这条出口就**没法远程验收**。
 *   安全性由 HeadKey 自己保证：屏保中短按走 ignored_sleeping 直接返回，
 *   唯一能改变状态的是长按 = 叫醒（等于 /sleep off，不新增暴露面）。
 *   ★ v134 再加 /kill 与 /revive：要验的正是"**屏幕灭着、没人碰**"时
 *     常驻闹钟能不能把机器救活 —— 13 分钟那次死机就是这个场景，
 *     而屏保总闸会在最需要复现它的时候把实验挡在门外。
 *   ★ v140 再加 /freeze 与 /thaw：屏保本身就会冻原厂（用户 2026-09-24 要求），
 *     所以这两个必须放行 —— 尤其 /thaw，它是"冻错了/冻歪了"时唯一的远程解药，
 *     被总闸挡掉就等于把解药锁在保险箱里。
 *   ★ v142 再加 /presence 与 /name：它们**撕不掉屏保**。
 *     能撕掉屏保的只有三条路 —— 出声（/tts、/talk）、换脸（/face、/img）、
 *     动（/motion、/dance）。改名和查在线状态一条都不沾，只会写 prefs。
 *     而屏保期间恰恰最需要它们：用户人不在场，从电脑端看看"它还在不在"、
 *     给它换个名字 —— 正是这两个动作的典型使用场景。
 *   ★ v143 再加 /tone：它只读写调性档位（prefs），不发声、不换脸、不动，
 *     撕不掉屏保。放行的理由和 /presence·/name 一样 ——
 *     屏保期间调调性是**真实场景**（人在电脑边，先把调性建成人向，
 *     再让它按我们的说法醒过来），而不是"屏保应该被打破"。
 *   ★ v144 再加 /listenled：与 /tone 同类 —— 只读写一个 prefs 开关
 *     （必要时灭一次灯），不发声、不换脸、不动，撕不掉屏保。
 *   ★ v182 再加 /listenface：与 /listenled 完全同类 —— 只读写一个 prefs
 *     开关（真正播脸只发生在"开麦听"时，而屏保里开不了麦），
 *     本身不发声、不换脸、不动，撕不掉屏保。
 *   ★ v183 再加 /recaudio 与 /recadir：采集器只**收声**——不发声、不换脸、
 *     不动，一样撕不掉屏保。放行理由与 /listenled 同源。
 *     而且采集恰恰是"人在电脑边、设备正屏保着"时才做的事
 *     （屏保里开不了麦，录不到干净的底噪对比样本）。
 *   ★ v190 再加 /deepsleep 与 /emotion：
 *     · /deepsleep —— 它**必须**放行：深睡就是"冻原厂主进程"，
 *       而冻住之后头顶键不再上报。若这条也被总闸挡住，等于**没有任何
 *       远程出口**，只剩 adb 重启（真正把机器人锁死）。它是 v190 的解药。
 *     · /emotion —— 只读写情绪状态、不发声不换脸不动，撕不掉屏保。
 *   ★ v183 再加 /kws 全家：自建唤醒内核只**读文件、算数字**——
 *     不开麦、不发声、不换脸、不动，一样撕不掉屏保。
 *     而"人在电脑边、设备正屏保着"恰恰是最该能跑这些诊断的时候。
 *   ★ v202 再加 /battery 与 /thinkselftest：
 *     · GET /battery —— 只读电池（sticky 广播/BM），不发声不换脸不动，
 *       与 GET /status 完全同类。"人在电脑边、机器屏保着，先看还剩几格电"
 *       恰恰是最需要它的场景。
 *     · GET /thinkselftest —— **纯静态**自检（不吃 Context、不联网、不碰
 *       org.json），只是算数字，撕不掉屏保。放行理由与 /kws 那一族同源。
 *     ★ 只放 GET /listen 的"看"，不放 POST /listen —— 后者会**开麦**，
 *       开麦就能通过 TTS 撕屏保，不能放进来。
 *   ★ v203 再加 /caps /workflowselftest /workflow 的"看"：
 *     · GET /caps —— 只读能力索引（自省 + 随包说明书），不发声不换脸不动。
 *     · GET /workflowselftest —— **纯静态**自检，只算数字。
 *     · GET /workflow —— 看最近一次编排的流水与是否在跑。
 *       放行理由与 /thinkselftest 同源（v202 那一段）。
 *     ★ **不放 POST /workflow** —— 它会跑动作/说话，能撕屏保。
 *   ★ v206 再加 /ledselftest：与上面几个同类，纯静态只算数字。
 *   ★ v207 再加能力真源的"看"：/abilities（权限矩阵）+ /abilitiesselftest。
 *     两者都只读、不吃 Context、不发声不换脸不动 ⇒ 与 /caps 同类。
 *     ★ 只放 GET：能力执行永远走 TOOL/POST，不在屏保白名单里。
 *   ★ v214 再加长期记忆与两个自检：
 *     · GET /memory  —— 只读记忆（认识第几天/聊过几次/记着哪些事）。不发声、
 *       不换脸、不动 ⇒ 与 /caps 同类。"人在电脑边、机器屏保着，先看看它还记着啥"
 *       正是最需要它的场景。
 *     · POST /memory —— 只读写**记忆这份本地数据**（remember / forget），
 *       不发声、不换脸、不动，撕不掉屏保。放行理由与 /tone 完全同源：
 *       而"忘记记忆"恰恰是需要能在人不被打扰时远程做掉的事。
 *     · GET /toneselftest、GET /memoryselftest —— **纯静态**自检，只算数字。
 *     ★ 注意：`forget` 会真删文件，但它删的是**我们自己的数据**，不动系统、
 *       不碰原厂，与"开关机/深睡"这类会失联的红线是两码事。
 *   ★ v203：**POST /listen 必须放行**。
 *     v202 当时刻意只放 GET /listen，理由是"POST 会开麦 ⇒ 能撕屏保"。
 *     但 POST /listen 的 body 可以**只带 prefs 字段**（workflow/think/
 *     autostart/grammar），一个麦都不碰 —— 于是屏保期间想改这些开关
 *     明明是安全的，却被一刀切挡住了（本次验收实测：4 条判据因它 FAIL）。
 *     所以改成放行 POST，**把安全判断交给路由内部**：
 *     只有 {"on":...} 这一个字段真的会开麦 ⇒ 那一种情况在下面显式挡住。
 *   ★ v175 再加 GET /programmer：它只把一份**静态 HTML** 发出去 ——
 *     不发声、不换脸、不动，撕不掉屏保。放行理由与 /presence·/name·/tone 完全一致。
 *     而它恰恰是这台"无 WebView"设备的**唯一编程入口**，
 *     屏保中想从电脑打开编程器是很自然的事（人正坐在电脑前）。
 *   ★ v176 再加 GET/POST /bt：与 /thaw 同类 —— 它是**连通性解药**。
 *     蓝牙关着，用户手机/电脑唯一进得来的路就是 WiFi 上的 HTTP(:8899)；
 *     而"屏保中能不能把蓝牙开回来"恰恰是最需要它的场景（人不在场、
 *     屏保里蓝牙被关掉了，除了这条 HTTP 就没有第二条腿）。
 *     它只动适配器、不发声/不换脸/不动，撕不掉屏保。
 *   ★ v192 再加 **整族前缀** GET /console/*：网页控制台（由机器人自己
 *     吐出的那份，见 consoleCompat）要能在屏保中打开并且读得到状态/清单，
 *     否则用户人不在场时打开它只会看到一屏 "sleeping"。
 *     放行的理由与 /presence·/name·/tone 完全同源：这些 GET 全是**只读**
 *     —— 不发声、不换脸、不动，撕不掉屏保。
 *     ★ 只放 GET：POST /console/* 里面可能就是 /motion、/tts。
 *   ★ v195 再加 **投屏的"控"与"看"**：POST /console/tap|swipe|key|text
 *     以及 POST /console/mirror（换分辨率/开关投屏）。
 *
 *     为什么必须放行：本机屏保是**闲置 10 分钟自动进**的
 *     （SleepMode.IDLE_DEFAULT_SEC=600），也就是"人不在场"时机器人
 *     几乎总在屏保里。若把这几条挡在门外，用户打开投屏页只会看到
 *     一屏 "sleeping" —— **这个功能就等于不存在**。
 *
 *     放行的理由与 /headkey **完全同源**（见本函数开头那段）：
 *     它是"人手点屏 / 按键"的远程替身，而"轻点屏幕"本来就是屏保的
 *     既定出口之一，所以**不新增任何暴露面**。
 *     /console/mirror 同理：它只动"看不看屏"，不发声、不换脸、不动，
 *     用的还是 screenrecord 的**虚拟显示**，物理屏上的屏保撕不掉。
 *
 *     ★ 边界仍然清楚：只放行这**四条输入**+ 投屏自己的开关；
 *       /console/agent 那条万能转发依旧属于 POST /console/ 前缀，
 *       照常被闸住（它里面可能就是 /motion、/tts）。
 * </pre>
 */
public final class SleepGate {

    private SleepGate() {}

    /**
     * 屏保期间**放行**（不拒绝）的端点。**唯一真源** —— 加放行项只改这里。
     * 每条的放行理由见类注释（v128~v214）。
     */
    private static final java.util.Set<String> ALLOW =
            new java.util.HashSet<String>(java.util.Arrays.asList(
                    "GET /sleep",
                    "POST /sleep",
                    "POST /headkey",
                    "GET /status",
                    "GET /health",
                    "POST /kill",
                    "POST /revive",
                    "GET /revive",
                    "POST /freeze",
                    "POST /thaw",
                    "GET /freezer",
                    "GET /presence",
                    "POST /presence",
                    "POST /name",
                    "GET /tone",
                    "POST /tone",
                    "GET /listenled",
                    "POST /listenled",
                    "GET /listenface",
                    "POST /listenface",
                    "POST /recaudio",
                    "GET /recadir",
                    "GET /deepsleep",
                    "POST /deepsleep",
                    "GET /emotion",
                    "POST /emotion",
                    "GET /kws",
                    "POST /kws",
                    "POST /kws/clear",
                    "POST /kws/train",
                    "POST /kws/test",
                    "POST /kws/probe",
                    "GET /battery",
                    "GET /thinkselftest",
                    "GET /listen",
                    "GET /caps",
                    "GET /workflowselftest",
                    "GET /capsselftest",
                    "GET /ledselftest",
                    "GET /workflow",
                    "GET /abilities",
                    "GET /abilitiesselftest",
                    "GET /memory",
                    "POST /memory",
                    "GET /toneselftest",
                    "GET /memoryselftest",
                    "POST /listen",
                    "GET /bt",
                    "POST /bt",
                    "GET /programmer",
                    "POST /console/tap",
                    "POST /console/swipe",
                    "POST /console/hold",
                    "POST /console/key",
                    "POST /console/text",
                    "POST /console/mirror"
            ));

    /**
     * 控制台是**前缀**放行，不是逐个列举（原实现的 `!act.startsWith("GET /console/")`）。
     * ★ 这里刻意保留前缀语义：改成把 14 个 /console/* 列进 ALLOW 会**改变行为**
     *   （新增的 /console/xxx 会自动从「放行」变成「被闸」），属于静默变更，不做。
     */
    static final String CONSOLE_GET_PREFIX = "GET /console/";

    /** 屏保期间是否**拒绝**这条 act。与原内联布尔式逐字等价。 */
    public static boolean blocked(String act) {
        if (!SleepMode.sleeping) return false;
        if (act == null) return true;
        if (ALLOW.contains(act)) return false;
        if (act.startsWith(CONSOLE_GET_PREFIX)) return false;
        return true;
    }

    /** 屏保拒绝时的统一应答（措辞与原实现逐字一致）。 */
    public static String deny() {
        return "{\"error\":\"sleeping\",\"hint\":\"先 POST /sleep {\\\"on\\\":false} 叫醒\"}";
    }

    /** 供探针读：当前放行集合大小。 */
    public static int allowCount() { return ALLOW.size(); }

    /**
     * 纯 JVM 可跑的行为自检：把 sleeping 拨到 true，逐条验「该放的放、该拦的拦」，
     * 再拨回去。**不碰 Context、不联网** —— 所以能进 JVM 闸门。
     */
    public static String selftest() {
        StringBuilder sb = new StringBuilder();
        int n = 0, nOk = 0;
        boolean save = SleepMode.sleeping;
        try {
            SleepMode.sleeping = false;
            n++; if (!blocked("POST /tts")) nOk++; else sb.append("\n  x 不睡时不该拦 POST /tts");
            SleepMode.sleeping = true;
            String[] mustAllow = {"GET /sleep", "POST /sleep", "GET /status",
                    "POST /headkey", "GET /battery", "GET /memory", "POST /memory",
                    "GET /deepsleep", "POST /deepsleep", "GET /bt",
                    "GET /console/index", "POST /console/tap", "POST /console/mirror"};
            for (String a : mustAllow) {
                n++; if (!blocked(a)) nOk++; else sb.append("\n  x 该放行却被拦: " + a);
            }
            String[] mustDeny = {"POST /tts", "POST /talk", "POST /motion", "POST /dance",
                    "POST /face", "POST /img", "POST /console/agent", "POST /console/tts",
                    "GET /motions", "POST /display"};
            for (String a : mustDeny) {
                n++; if (blocked(a)) nOk++; else sb.append("\n  x 该拦却放行: " + a);
            }
            // 前缀语义必须**只**对 GET /console/ 生效
            n++; if (blocked("GET /consolex")) nOk++; else sb.append("\n  x 前缀判太宽：GET /consolex 被放行");
        } finally {
            SleepMode.sleeping = save;
        }
        // ★ 如实：没通过就不许说 PASS（只统计成功 = 假绿灯）
        String head = "屏保白名单自检 " + nOk + "/" + n + (nOk == n ? " PASS" : " FAIL");
        return head + "  (ALLOW=" + ALLOW.size() + " 条, 前缀=" + CONSOLE_GET_PREFIX + ")" + sb;
    }
}
