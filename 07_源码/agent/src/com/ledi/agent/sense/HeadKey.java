package com.ledi.agent.sense;

import com.ledi.agent.core.AgentService;
import com.ledi.agent.core.BootLog;
import com.ledi.agent.ui.HomeSetter;
import com.ledi.agent.core.J;
import com.ledi.agent.ui.SleepMode;
import com.ledi.agent.voice.VoiceDialog;
import com.ledi.agent.voice.VoiceWakeController;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * HeadKey —— 头顶**按键**（`com.auric.intell.xld.rbt.key.head.down`）的动作层。
 *
 * <h3>为什么需要它</h3>
 * 原厂把头顶键喂给了自己的场景状态机（{@code RobotEvent.BTN_HEAD_CLICK}，
 * 并在埋点里报成 {@code RBT_XLD_Push_Head_Button_Click}）。我们的 {@link SensorHub}
 * 早就注册了这个广播，但**只记录、不处理** —— 按键等于没接线。
 * 用户要求：「按机器人的头顶键打开语音和主程序，再按一下退出主程序」。
 *
 * <h3>行为（v127：分短按 / 长按）</h3>
 * 阈值 3000ms，与原厂头顶键计时器 {@code robot.main.c.b()} 的 {@code c$3(3000,500)} 对齐
 * （REF-L）。判定在 {@link SensorHub} 里做，这里只收结果：
 * <ul>
 *   <li><b>短按</b>（{@link #press}，抬手时触发）走一个翻转。
 * ★ 翻转的判据是**互动状态**（对话活跃 / 麦在对话手里 / 正在播报），
 * <b>不是</b>"主程序在不在前台"—— 本机原厂脸就是桌面，它永远在前台，
 * 拿它当判据会让每次按下都走同一个分支（我第一版就是这么错的，sim 一跑就暴露）：
 * <ul>
 *   <li><b>没在互动</b> → 打断一切（动作/播报/主动说话）→ 强制唤醒开始听 →
 *       把主程序调到前台。＝用户说的「打开语音和主程序」。</li>
 *   <li><b>正在互动</b> → 结束当前播报 → <b>回桌面</b>（HOME intent）。
 *       ＝用户说的「再按一下退出主程序」。</li>
 * </ul></li>
 *   <li><b>长按</b>（{@link #onHold}）→ 屏保开 / 关（用户要求「长按 = 屏保」）。</li>
 * </ul>
 * 屏保中短按被忽略（否则会 forceWake + 把脸盖到屏保图上）。
 *
 * <h3>一个必须说清楚的事实（否则会做出错误的产品判断）</h3>
 * ★ 本机**原厂那张脸（FaceActivity）本身就是系统桌面（HOME/LAUNCHER）**。
 * 实测：把它所在的 task 用 `am stack movetask` 移出 home 栈，屏幕上**仍然是一张脸**
 * （栈 1 里还躺着另一个 FaceActivity task），而且系统一定会给 home 栈补一个前台 —— 
 * 所以「让主程序退到后台、屏幕空出背景」**靠系统做不到**（这条是实测证伪的，
 * 不是推测）。用户对"退出后看见什么"的回答是「就是桌面」，因此这里退出 = 回桌面 + 收声。
 *
 * <h3>可配置</h3>
 * 主程序目标可换（{@code factory} 原厂脸 / {@code brain} 我们的 AI 主界面 / {@code off} 只唤醒语音），
 * 通过 {@code POST /headkey {"main":"brain"}} 换，**不用重装**。
 */
public final class HeadKey {

    private static final String PREFS = "ledi_agent";
    private static final String K_ON = "headkey_on";
    private static final String K_MAIN = "headkey_main";
    private static final String K_WAKE = "headkey_wake";

    public static final String MAIN_FACTORY = "factory";
    public static final String MAIN_BRAIN = "brain";
    public static final String MAIN_OFF = "off";

    private final Context ctx;

    // ---- 观测（都给 /headkey，让"没反应"能一眼看出卡在哪一步）----
    private volatile boolean mainShown = false;      // 我们自己记的开关态
    private volatile int presses = 0, opens = 0, exits = 0, errors = 0, skipped = 0;
    private volatile int longPresses = 0, sleeps = 0, wakes = 0;
    private volatile String lastAct = "";
    private volatile String lastNote = "";
    private volatile String lastBy = "";
    private volatile long lastMs = 0;
    private volatile long lastOpenMs = 0;            // 上次"打开"的时刻（判"自记状态过没过期"）
    // 决策留痕：按下那一刻判据的三个输入（排查"怎么又走了同一个分支"用）
    private volatile boolean dFlag = false, dDialog = false;
    private volatile long dAge = -1;

    public HeadKey(Context ctx) { this.ctx = ctx; }

    // ================= 配置 =================

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean enabled() { return prefs().getBoolean(K_ON, true); }

    public void setEnabled(boolean on) { prefs().edit().putBoolean(K_ON, on).apply(); }

    public String mainTarget() { return prefs().getString(K_MAIN, MAIN_FACTORY); }

    public void setMainTarget(String t) {
        String v = t == null ? MAIN_FACTORY : t.trim().toLowerCase();
        if (!MAIN_FACTORY.equals(v) && !MAIN_BRAIN.equals(v) && !MAIN_OFF.equals(v)) v = MAIN_FACTORY;
        prefs().edit().putString(K_MAIN, v).apply();
    }

    public boolean wakeVoice() { return prefs().getBoolean(K_WAKE, true); }

    public void setWakeVoice(boolean on) { prefs().edit().putBoolean(K_WAKE, on).apply(); }

    // ================= 前台判定 =================

    /**
     * 主程序此刻在前台吗？
     *
     * ★ 这里刻意做成"真查询 + 状态兜底"，并把**用的是哪个**报出去（{@code lastBy}）：
     *   Android 5.0 起，只有拿到 signature 级的 REAL_GET_TASKS 才能看到别人的 task，
     *   普通 GET_TASKS 只能看到自己 ⇒ 查询**可能看不到真相**。如果我们拿
     *   "查不到 = 不在前台" 当真，就会一直重复"打开"、永远进不了"退出"分支 ——
     *   典型的假信号。所以查不到就退回自记状态，并如实标注 by=state。
     */
    private boolean mainInFront() {
        String pkg = targetPackage();
        if (pkg == null) return mainShown;
        try {
            android.app.ActivityManager am = (android.app.ActivityManager)
                    ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) { lastBy = "state"; return mainShown; }
            java.util.List<android.app.ActivityManager.RunningTaskInfo> ts = am.getRunningTasks(1);
            if (ts == null || ts.isEmpty() || ts.get(0).topActivity == null) {
                lastBy = "state"; return mainShown;
            }
            String top = ts.get(0).topActivity.getPackageName();
            // 只信"看得到目标包"这一半：看得到目标 ⇒ 确实在前台；
            // 看到的是别的包 ⇒ 可能是被截断的假象，不敢据此下结论，退回状态。
            if (pkg.equals(top)) { lastBy = "query"; return true; }
            if (HomeSetter.OUR_PKG.equals(top) || "com.android.systemui".equals(top)) {
                lastBy = "query"; return false;
            }
            lastBy = "state";
            return mainShown;
        } catch (Throwable t) {
            lastBy = "state(异常:" + t.getClass().getSimpleName() + ")";
            return mainShown;
        }
    }

    /** 目标包名；{@code off} 时返回 null（没有界面目标）。 */
    private String targetPackage() {
        String m = mainTarget();
        if (MAIN_BRAIN.equals(m)) return HomeSetter.OUR_PKG;
        if (MAIN_FACTORY.equals(m)) return HomeSetter.FACTORY_PKG;
        return null;
    }

    private ComponentName targetComponent() {
        String m = mainTarget();
        if (MAIN_BRAIN.equals(m)) return new ComponentName(HomeSetter.OUR_PKG, HomeSetter.OUR_CLS);
        if (MAIN_FACTORY.equals(m)) return new ComponentName(HomeSetter.FACTORY_PKG, HomeSetter.FACTORY_CLS);
        return null;
    }

    // ================= 动作 =================

    /** 按键线程调用（SensorHub 已经丢到一个独立线程里了）。 */
    public void onPress() { press("头顶键"); }

    /**
     * 短按的同步实现，返回结果 JSON（给 {@code POST /headkey {"sim":"down"}} 复用同一份逻辑，
     * 这样"按键真到了"和"按键到了以后做对了"可以分别验证）。
     */
    public synchronized String press(String why) {
        presses++;
        SleepMode.touch();          // v142：按了键 = 有人在用（闲置自动屏保的判据输入）
        lastMs = System.currentTimeMillis();
        lastAct = "";
        lastNote = "";

        if (!enabled()) {
            skipped++;
            lastAct = "disabled";
            lastNote = "头顶键功能被关了（POST /headkey {\"on\":false}）";
            BootLog.i("头顶键：功能已关闭，忽略本次按下");
            return json();
        }

        // ★ 屏保中短按**必须忽略**：这条路径会 forceWake + startActivity(原厂脸)，
        //   而屏保正在挂起唤醒、把图铺在最上层 —— 一执行就会把屏保撕掉半张
        //   （唤醒引擎被拉起来、脸盖到图上面）。屏保的出口只有两个：轻点屏幕 / 长按。
        if (SleepMode.sleeping) {
            skipped++;
            lastAct = "ignored_sleeping";
            lastNote = "屏保中：短按不处理（长按 = 叫醒，或轻点屏幕）";
            BootLog.i("头顶键：屏保中，短按忽略");
            return json();
        }

        // ★ 判据是**互动状态**，不是"主程序在不在前台"。
        //   我第一版拿"主程序在不在前台"当判据，实测立刻暴露是错的：本机原厂脸
        //   就是桌面，它**永远**在前台 ⇒ 每次按下都走"退出"分支，第一次按下去
        //   根本不会唤醒语音。sim 一跑就看出来了（两次都是 act=exit）。
        //   用户的语义其实是"开互动 / 退互动"，界面只是顺带调前台。
        boolean on = interactionOn();
        // ★ 把"判据的三个输入"记下来。没有它，"两次都走同一分支"这种事
        //   只能靠猜（我上一轮就是这么浪费了一轮）；有了它，一眼看出是哪一个输入不对。
        dFlag = mainShown;
        dDialog = dialogActiveNow();
        dAge = lastOpenMs == 0 ? -1 : (System.currentTimeMillis() - lastOpenMs);
        if (on) exitMain(why);
        else openMain(why);
        return json();
    }

    /** 机器人此刻是否"正在互动"：对话活跃 / 麦已让给对话 / 正在播报。 */
    private boolean dialogActiveNow() {
        try {
            VoiceWakeController vw = AgentService.voiceWake;
            if (vw != null) {
                VoiceDialog d = vw.dialog();
                if (d != null && d.isActive()) return true;
                if (vw.isMicForDialog()) return true;
            }
        } catch (Throwable ignored) {}
        try {
            if (AgentService.router != null && AgentService.router.tts() != null
                    && AgentService.router.tts().isSpeaking()) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * 开关判据：以**自记状态**为准，用现实做**很慢的**单向纠正。
     *
     * <p>为什么不自记状态说了算就行：对话会因为静音超时自己结束，我们没有"立刻结束
     * 对话"的 API（{@link VoiceDialog} 只暴露 isActive，没有 stop）。如果纯看
     * dialog.isActive，刚"退出"完那几秒它还在听，再按一下会又"退出"一次 ——
     * 按三下才回到"打开"，用户会觉得按键坏了。
     *
     * <p>为什么纠正窗口不能短：我第一版取 3 秒，实测**按 1 之后停顿 4 秒再按 2**，
     * 对话已经因静音结束 ⇒ 判据翻回"没在互动" ⇒ 第 2 次按下又去"打开"，
     * 用户根本退不出去。对话在**等待用户说话的间隙**本来就是 inactive 的，
     * 拿"这一刻 inactive"当"互动结束了"是错的。
     *
     * <p>所以窗口取 30 秒（远大于一次正常互动的间隙）：
     * 快速连按严格交替（物理按键最重要的性质），
     * 只有明显离开很久了才自动归零，好让下一次按下是"打开"。
     */
    private boolean interactionOn() {
        if (!mainShown) return false;
        if (dialogActiveNow()) return true;
        if (System.currentTimeMillis() - lastOpenMs > 30000) return false;
        return true;
    }

    /** 打开：打断 → 唤醒语音 → 主程序到前台。 */
    private void openMain(String why) {
        StringBuilder note = new StringBuilder();
        long t0 = System.currentTimeMillis();

        // ① 打断一切（用户要求：和"抹头顶"一样是最高优先级）
        String ar = "?";
        try { ar = AgentService.motion == null ? "no motion" : AgentService.motion.abort(); }
        catch (Throwable t) { ar = "abort 异常"; errors++; }
        String tr = "?";
        try {
            tr = (AgentService.router == null || AgentService.router.tts() == null)
                    ? "no tts" : AgentService.router.tts().stopNow();
        } catch (Throwable t) { tr = "tts 异常"; errors++; }
        try {
            if (AgentService.proactiveTalk != null) AgentService.proactiveTalk.noteInterrupt();
        } catch (Throwable ignored) {}
        note.append("打断{动作=").append(ar).append(", 播报=").append(tr).append("}");

        // ② 语音：唤醒并开始听
        if (wakeVoice()) {
            try {
                if (AgentService.voiceWake != null) {
                    AgentService.voiceWake.forceWake(why == null ? "头顶键" : why);
                    note.append(" 语音=唤醒");
                } else note.append(" 语音=未就绪");
            } catch (Throwable t) {
                errors++;
                note.append(" 语音=失败(").append(t.getClass().getSimpleName()).append(")");
            }
        } else {
            note.append(" 语音=按配置不唤醒");
        }

        // ③ 界面到前台
        ComponentName cn = targetComponent();
        if (cn == null) {
            note.append(" 界面=按配置不打开");
        } else {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setComponent(cn);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                ctx.startActivity(i);
                mainShown = true;
                note.append(" 界面=").append(cn.getClassName());
            } catch (Throwable t) {
                errors++;
                note.append(" 界面=失败(").append(t.getClass().getSimpleName()).append(")");
                BootLog.w("头顶键 打开主程序失败: " + t);
            }
        }

        opens++;
        lastAct = "open";
        lastNote = note.toString();
        mainShown = true;
        lastOpenMs = System.currentTimeMillis();
        BootLog.i("★头顶键 打开语音和主程序: " + lastNote
                + " 耗时=" + (System.currentTimeMillis() - t0) + "ms by=" + lastBy);
    }

    /** 退出：收声 → 回桌面。 */
    private void exitMain(String why) {
        StringBuilder note = new StringBuilder();
        long t0 = System.currentTimeMillis();

        // ① 把正在说的话收掉（"退出"最直接的可感知效果）
        String tr = "?";
        try {
            tr = (AgentService.router == null || AgentService.router.tts() == null)
                    ? "no tts" : AgentService.router.tts().stopNow();
        } catch (Throwable t) { tr = "tts 异常"; errors++; }
        note.append("收声{播报=").append(tr).append("}");

        // ② 回桌面
        //    ★ 本机桌面 = 原厂脸，所以这一步在"主程序=原厂脸"时是**幂等**的。
        //      它真正解决的是"主程序被配成 brain / 或上面盖了别的东西"的情况。
        //
        //    ★★ v133：改成**显式组件**，不再发 `CATEGORY_HOME` 裸 intent。
        //       实测（2026-09-23）：本机头顶键就是 KEYCODE_HOME，且 HOME 有两个候选，
        //       发裸 HOME 会走系统解析 ⇒ **有一半概率弹"选择桌面"框**；那个选择器跑在
        //       系统进程里，在 load≈13 的负载下会 ANR，把输入派发堵死 ⇒ 后续按键成批
        //       迟到 ⇒ 我们的短按被判成长按、屏保乱翻。所以这条路径必须绕开解析。
        try {
            ComponentName hm = HomeSetter.targetHome(ctx);
            Intent home = new Intent(Intent.ACTION_MAIN);
            if (hm != null) home.setComponent(hm);
            else home.addCategory(Intent.CATEGORY_HOME);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(home);
            note.append(" 回桌面=").append(hm == null ? "已发 HOME(无目标桌面)" : hm.getClassName());
        } catch (Throwable t) {
            errors++;
            note.append(" 回桌面=失败(").append(t.getClass().getSimpleName()).append(")");
            BootLog.w("头顶键 回桌面失败: " + t);
        }

        exits++;
        lastAct = "exit";
        lastNote = note.toString();
        mainShown = false;
        BootLog.i("★头顶键 退出主程序（回桌面）: " + lastNote
                + " 耗时=" + (System.currentTimeMillis() - t0) + "ms by=" + lastBy);
    }

    // ================= 长按（v127）=================

    /**
     * 长按（≥ {@link SensorHub#HEADKEY_LONG_MS}）：<b>屏保开关</b>。
     *
     * <p>用户要求「长按 = 屏保」，所以这里做的是**翻转**而不是"只进不出"：
     * 醒着 → 进屏保；屏保中 → 叫醒。这样即使触摸层出问题（本机浮层触摸是踩过坑的），
     * 物理按键仍然是可靠的出口 —— 屏保最怕的就是"进去了出不来"。
     *
     * <p>失败了要能看见：{@link SleepMode#enter} 在**没有图片**时会拒绝进入
     * （进去就是全屏黑），这时报 {@code act=sleep_fail} 而不是假装成功。
     */
    public synchronized String onHold() {
        longPresses++;
        SleepMode.touch();          // v142：同上
        lastMs = System.currentTimeMillis();
        lastAct = "";
        lastNote = "";
        if (!enabled()) {
            skipped++;
            lastAct = "disabled";
            lastNote = "头顶键功能被关了（POST /headkey {\"on\":false}）";
            BootLog.i("头顶键：功能已关闭，忽略本次长按");
            return json();
        }
        long t0 = System.currentTimeMillis();
        if (!SleepMode.sleeping) {
            String r = SleepMode.enter(ctx, -1f);      // -1 = 沿用上次亮度
            boolean ok = SleepMode.sleeping;
            lastAct = ok ? "sleep_on" : "sleep_fail";
            lastNote = "长按 → 屏保: " + r;
            if (ok) sleeps++; else errors++;
            BootLog.i("★头顶键 长按 进入屏保(" + (ok ? "成功" : "失败") + "): " + r
                    + " 耗时=" + (System.currentTimeMillis() - t0) + "ms");
        } else {
            String r = SleepMode.exit(ctx);
            lastAct = "sleep_off";
            lastNote = "长按 → 叫醒: " + r;
            wakes++;
            BootLog.i("★头顶键 长按 退出屏保: " + r
                    + " 耗时=" + (System.currentTimeMillis() - t0) + "ms");
        }
        return json();
    }

    // ================= 输出 =================

    public String json() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("on", enabled());
            o.put("main", mainTarget());
            o.put("wake_voice", wakeVoice());
            o.put("main_shown", mainShown);
            o.put("interaction_on", interactionOn());
            o.put("dialog_active", dialogActiveNow());
            o.put("main_front", mainInFront());
            o.put("in_front_by", lastBy);
            o.put("presses", presses);
            o.put("long_presses", longPresses);
            o.put("sleeps", sleeps);
            o.put("wakes", wakes);
            o.put("sleeping", SleepMode.sleeping);
            o.put("long_ms", SensorHub.HEADKEY_LONG_MS);
            o.put("opens", opens);
            o.put("exits", exits);
            o.put("skipped", skipped);
            o.put("errors", errors);
            o.put("last_act", lastAct);
            o.put("last_note", lastNote);
            o.put("last_ago", lastMs == 0 ? -1 : (System.currentTimeMillis() - lastMs) / 1000);
            // 上次按下时的判据输入：flag=自记状态, dialog=对话活跃, age=距上次打开毫秒
            o.put("dec_flag", dFlag);
            o.put("dec_dialog", dDialog);
            o.put("dec_age_ms", dAge);
            o.put("target", targetComponent() == null ? "无" : targetComponent().flattenToShortString());
            o.put("target_pkg", targetPackage() == null ? "无" : targetPackage());
            return o.toString();
        } catch (Throwable t) {
            return "{\"error\":\"" + J.esc(String.valueOf(t)) + "\"}";
        }
    }
}
