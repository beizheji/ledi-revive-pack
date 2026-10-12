package com.ledi.agent.ui;

import com.ledi.agent.core.AgentService;
import com.ledi.agent.core.BootLog;
import com.ledi.agent.core.CrashGuard;
import com.ledi.agent.net.HttpServer;
import com.ledi.agent.core.J;
import com.ledi.agent.net.UdpBeacon;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 把机器人的桌面（HOME）<b>锁定在原厂机器人 App 上</b>——
 * 需求：「机器人桌面锁定为机器人的 app（不是 agent）」。
 *
 * <h3>为什么需要"设偏好"这一步</h3>
 * 这台机器人上，原厂 {@code com.auric.intell.rbt.xldrbtx} 的
 * {@code ui.FaceActivity} 和我们自己的 {@code BrainActivity} <b>都</b>声明了
 * {@code MAIN + HOME + DEFAULT}。两个 HOME 候选并存时，Android 不会"自动选一个"，
 * 而是弹<b>选择器</b>让用户点 —— 机器人没有鼠标、触摸区又小，等于开不了机。
 * 所以必须用 {@link PackageManager#addPreferredActivity} 把偏好写进系统，
 * 让 HOME 的解析结果唯一地指向原厂 FaceActivity。
 *
 * <h3>和上一版的方向正好相反</h3>
 * 早先的做法是"把偏好指向我们自己"（见 git 历史）。现在改成指向原厂 App：
 * 机器人开机应当就是它自己的那张脸，我们的 agent 退到后台做服务
 * （Agent、UdpBeacon、HttpServer 都是 Service，不依赖自己是不是桌面）。
 * 顺带一提，ROM 开机时自己也会写一次默认桌面、而且写的就是原厂 FaceActivity ——
 * 方向翻转之后，我们和 ROM 不再互相拉锯。
 *
 * <h3>防砖兜底</h3>
 * 目标不是写死的：如果原厂 App 没装、或者它压根没声明 HOME，就退回
 * "把桌面指向我们自己"，绝不让机器人出现"没有任何桌面"的黑屏。
 * {@link #targetHome} 就是这个决策点。
 *
 * <h3>为什么我们有资格做这件事</h3>
 * 本 APK 是 {@code sharedUserId="android.uid.system"} + platform 签名，
 * {@code addPreferredActivity / setComponentEnabledSetting} 这类系统级调用
 * 都能静默生效，不弹任何确认框。
 *
 * <h3>三级兜底</h3>
 * 不同 ROM 对 preferred-activity 的处理不完全一样，所以按顺序试：
 * <ol>
 *   <li>{@code addPreferredActivity} —— 标准做法，多数 ROM 到这步就成；</li>
 *   <li>{@code replacePreferredActivity} —— 已存在旧偏好时用它顶掉；</li>
 *   <li>禁用其它 HOME 入口 —— 把<b>非目标</b>的 HOME Activity 入口置为 disabled
 *       （不卸载、不动数据）。方向翻转之后，这一步要禁用的恰恰是<b>我们自己</b>的
 *       BrainActivity 入口；可通过 {@link #restore} 完整恢复。</li>
 * </ol>
 *
 * <p>每一步做完都用 {@link #currentHome} 复核，只有真的生效才往下走 ——
 * 不做"调用了就等于成功"的自欺欺人。
 */
public final class HomeSetter {

    public static final String OUR_PKG = "com.ledi.agent";
    /** 界面本体（AI 主界面）。★ v134 起它**不再**声明 HOME，所以不会被我们禁用。 */
    public static final String OUR_CLS = "com.ledi.agent.ui.BrainActivity";
    /**
     * 我们那条 HOME 入口的<b>别名</b>（清单里 {@code activity-alias targetActivity=BrainActivity}）。
     *
     * <p>★★ v134 的关键改动：唯一化"全机 HOME 候选"时要禁掉的是<b>它</b>，不是
     * {@link #OUR_CLS}。理由见 {@code AndroidManifest.xml} 里那段注释 ——
     * 禁整个 {@code BrainActivity} 会把"手动打开本界面"和
     * {@code POST /headkey {"main":"brain"}} 一起弄坏（而且命令行 {@code pm disable}
     * 还会把我们进程打死，实测死了 13 分钟没人救）。
     */
    public static final String OUR_HOME_CLS = "com.ledi.agent.BrainHome";

    /**
     * 要锁定的桌面：原厂机器人 App 的「脸」。
     *
     * <p>注意类是 {@code ui.FaceActivity} —— 它不是"设置页"也不是"主菜单"，
     * 就是机器人那张会动、会说话的脸；开机看到它就等于机器人"活着"。
     */
    public static final String FACTORY_PKG = "com.auric.intell.rbt.xldrbtx";
    public static final String FACTORY_CLS = "com.auric.intell.rbt.xldrbtx.ui.FaceActivity";

    private static final String PREFS = "ledi_agent";
    /** 被我们顶掉的原桌面 "pkg/cls"，用于物归原主 */
    private static final String K_PREV = "home_prev";
    /** 是否持续把桌面锁在目标上（默认 true；关掉后 housekeeping 不再抢） */
    private static final String K_LOCK = "home_lock";
    /** 兜底阶段禁用过的其它 HOME 组件（逗号分隔的 flattenToString） */
    private static final String K_DISABLED = "home_disabled";
    /** 最近一次成功设成桌面的时间戳 */
    private static final String K_SET_AT = "home_set_at";

    private HomeSetter() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ 查询

    /** HOME 查询用 intent：MAIN + HOME + DEFAULT。 */
    public static Intent homeIntent() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        i.addCategory(Intent.CATEGORY_DEFAULT);
        return i;
    }

    /** 原厂桌面组件。 */
    public static ComponentName factoryHome() {
        return new ComponentName(FACTORY_PKG, FACTORY_CLS);
    }

    private static boolean installed(Context ctx, String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 到底把桌面锁给谁 —— 唯一的决策点。
     *
     * <p>优先级：原厂机器人 App（且它确实声明了 HOME）＞ 我们自己。
     * 后者只是防砖兜底：宁可桌面是我们，也不能让机器人开出一块黑屏。
     */
    public static ComponentName targetHome(Context ctx) {
        ComponentName f = factoryHome();
        if (installed(ctx, FACTORY_PKG) && homeActivities(ctx).contains(f)) return f;
        // 防砖兜底：指向**别名**（可开关的那条 HOME 入口），不是界面本体 ——
        // 本体压根不声明 HOME，写它进去系统也解析不到。
        return new ComponentName(OUR_PKG, OUR_HOME_CLS);
    }

    /** 给状态输出用：目标桌面的 "pkg/cls"。 */
    public static String targetName(Context ctx) {
        ComponentName c = targetHome(ctx);
        return c.getPackageName() + "/" + c.getClassName();
    }

    /**
     * 系统当前认定的默认桌面，返回 {@code "pkg/cls"}。
     *
     * <p>没有任何偏好时系统会把内部的 ResolverActivity 顶上来，那说明"还没定"，
     * 这里返回空串而不是把 android 当成桌面。
     */
    public static String currentHome(Context ctx) {
        try {
            ResolveInfo ri = ctx.getPackageManager()
                    .resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null || ri.activityInfo == null) return "";
            String p = ri.activityInfo.packageName;
            if (p == null || "android".equals(p)) return "";
            return p + "/" + ri.activityInfo.name;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 当前默认桌面是不是我们自己（注意：这已经<b>不是</b>我们想要的状态了）。 */
    public static boolean isUs(Context ctx) {
        return currentHome(ctx).startsWith(OUR_PKG + "/");
    }

    /** 当前默认桌面是不是就是目标（原厂机器人 App）。 */
    public static boolean isTarget(Context ctx) {
        ComponentName t = targetHome(ctx);
        return currentHome(ctx).equals(t.getPackageName() + "/" + t.getClassName());
    }

    /**
     * 我们名下有没有被系统接收的"默认桌面 = 目标"偏好；<b>只作线索，不是权威判据</b>。
     *
     * <h3>★★ 2026-09-23 晚定案：上一版"它不可靠"的结论是我自己写错了（判据自己错更贵）</h3>
     * 上一版传的是 {@code OUR_PKG}，于是永远什么都查不到，我据此写下"它语义未知、生效了
     * 也可能是 false"——**错的不是它，是我传的参数**。真相是：
     * <pre>
     *   adb shell dumpsys package   →   Preferred Activities User 0:
     *                                     Non-Data Actions:
     *                                       android.intent.action.MAIN:
     *                                         cdcaead com.auric.intell.rbt.xldrbtx/.ui.FaceActivity
     *                                         mMatch=0x100000 mAlways=false
     *                                         ...
     * </pre>
     * {@link PackageManager#getPreferredActivities} 的第三个参数是按<b>偏好记录里那个组件所属的包</b>
     * 过滤的（AOSP {@code PreferredIntentResolver.isPackageForFilter} 就是比
     * {@code filter.mPref.mComponent.getPackageName()}），**不是"谁写的这条偏好"**。
     * 记录的组件是原厂 FaceActivity ⇒ 必须用<b>目标的包名</b>去查。
     *
     * <p>但即使查到了也<b>不能</b>当作"按 Home 一定落到它"：
     * 实测上述 3 条记录都在，按 Home 照样弹 {@code ResolverActivity}
     * （记录里 {@code mAlways=false}）。所以：
     * <ul>
     *   <li>权威判据仍是**按一次 Home 看 {@code mCurrentFocus}**（部署脚本第 4 步）；</li>
     *   <li>{@link #settled} 用它当"要不要再写一遍"的门槛之一；</li>
     *   <li>它<b>不可以</b>当 {@link #keepHome} 那种轮询的门槛 —— 万一查不到就会每 10 秒
     *       clear+add 一次，纯浪费（而且每次都要写 package-restrictions.xml）。</li>
     * </ul>
     */
    public static boolean recorded(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ComponentName t = targetHome(ctx);
            // ★ 目标包在前（真正会命中），OUR_PKG 留着兜"历史上我们把自己设成桌面"的情况
            String[] pkgs = new String[]{t.getPackageName(), OUR_PKG};
            for (String pkg : pkgs) {
                ArrayList<IntentFilter> filters = new ArrayList<IntentFilter>();
                ArrayList<ComponentName> comps = new ArrayList<ComponentName>();
                pm.getPreferredActivities(filters, comps, pkg);
                for (ComponentName c : comps) {
                    if (c != null && c.equals(t)) return true;
                }
            }
            return false;
        } catch (Throwable t) {
            Log.w(AgentService.TAG, "home recorded 查询失败: " + t);
            return false;
        }
    }

    /**
     * HOME 候选个数。{@code >1} 就意味着"解析有歧义"，也就是**可能弹选择器**。
     *
     * <p>为什么要把"个数"单列出来：这台 ROM 上偏好记录明明在、按 Home 还是弹选择器
     * （2026-09-23 实测），而候选个数是<b>结构性的、不依赖系统实现</b>的量 ——
     * 只有一个候选时，任何解析路径都只能选它。
     */
    public static int candidatesN(Context ctx) {
        return homeActivities(ctx).size();
    }

    /** 唯一候选、且就是目标 ⇒ 解析不可能有歧义（最强的"不会弹选择器"结构判据）。 */
    public static boolean unique(Context ctx) {
        List<ComponentName> all = homeActivities(ctx);
        return all.size() == 1 && all.get(0).equals(targetHome(ctx));
    }

    /**
     * "桌面已经落定"的合成判据 = {@link #unique}（结构上不可能有歧义）
     * <b>或</b>（{@link #isTarget} 且 {@link #recorded}）。
     *
     * <p>★ 为什么不能再单独用 {@link #isTarget}：没有偏好时 {@code resolveActivity} 也会
     * 挑一个候选 ⇒ 它乐观 ⇒ {@link #ensure} 直接回 {@code already} ⇒
     * <b>这个接口修不了自己该修的状态</b>（实测：/sethome 回 already，按 Home 却弹选择器）。
     */
    public static boolean settled(Context ctx) {
        return unique(ctx) || (isTarget(ctx) && recorded(ctx));
    }

    /** 全机所有声明了 HOME 的 Activity（含我们自己）。 */
    private static List<ComponentName> homeActivities(Context ctx) {
        List<ComponentName> out = new ArrayList<ComponentName>();
        try {
            List<ResolveInfo> ris = ctx.getPackageManager().queryIntentActivities(homeIntent(), 0);
            if (ris == null) return out;
            for (ResolveInfo ri : ris) {
                if (ri == null || ri.activityInfo == null) continue;
                ComponentName cn = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
                if (!out.contains(cn)) out.add(cn);
            }
        } catch (Throwable t) {
            Log.w(AgentService.TAG, "home activities query fail: " + t);
        }
        return out;
    }

    public static boolean lockOn(Context ctx) {
        return sp(ctx).getBoolean(K_LOCK, true);
    }

    public static void setLock(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean(K_LOCK, on).apply();
        BootLog.i("桌面: 持续锁定 = " + on);
    }

    // ------------------------------------------------------------------ 主流程

    /**
     * 确保桌面是目标（幂等；已经是了就立刻返回，不做无谓的系统调用）。
     *
     * @return 结果 JSON
     */
    // ------------------------------------------------------------------ 唯一候选

    /**
     * 我们那条 HOME 入口（<b>别名</b>）此刻是开是关，给人看的字符串。
     *
     * <p>★ 它只是<b>线索</b>：真正的结构判据是 {@link #unique}（候选个数），
     * 因为"候选里有没有它"才是系统解析时会看的东西。
     */
    public static String oursEntryState(Context ctx) {
        return stateOf(ctx, OUR_HOME_CLS);
    }

    /**
     * 界面本体（{@code BrainActivity}）此刻是开是关 —— v134 新增。
     *
     * <p>为什么要单列：v133 的做法是禁用它，代价是"手动打开本界面"和
     * {@code POST /headkey {"main":"brain"}} 一起失效，而这一点在当时的 {@code /home}
     * 里<b>看不出来</b>（只报了入口状态）。现在两个状态分开报，
     * "唯一候选"与"本体可用"就不再有隐含耦合。
     */
    public static String ourActivityState(Context ctx) {
        return stateOf(ctx, OUR_CLS);
    }

    private static String stateOf(Context ctx, String cls) {
        try {
            int s = ctx.getPackageManager()
                    .getComponentEnabledSetting(new ComponentName(OUR_PKG, cls));
            // DEFAULT(0) 表示"按清单来"，而清单里是 enabled ⇒ 实际生效 = 开
            return (s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || s == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) ? "disabled" : "enabled";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /**
     * v133 → v134 迁移：把被"<b>整组件禁用</b>"的 {@code BrainActivity} 开回来。
     *
     * <p>为什么必须有这一步：v133 禁用状态是写进 {@code package-restrictions.xml} 的，
     * <b>覆盖安装不会清掉</b>（这也正是"每次装完都要重做主屏"的原因之一）。
     * 不迁移的话，升到 v134 之后本体还是禁用的 —— 新代码里没有任何地方会去开它
     * （我们只动别名），于是这个坑会<b>一直留着</b>。
     *
     * @return 变了就返回一句描述，没变返回空串
     */
    public static String migrateOldDisable(Context ctx) {
        try {
            if (!"disabled".equals(stateOf(ctx, OUR_CLS))) return "";
            ComponentName act = new ComponentName(OUR_PKG, OUR_CLS);
            ctx.getPackageManager().setComponentEnabledSetting(act,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            BootLog.i("桌面: 迁移 —— BrainActivity 曾被整组件禁用（v133 的做法），已开回");
            return "brain_activity:disabled→enabled";
        } catch (Throwable t) {
            BootLog.w("桌面: 迁移 BrainActivity 状态失败: " + t);
            return "migrate-fail=" + t;
        }
    }

    /**
     * ★★ v133：把我们自己的 HOME 入口对齐到"是不是目标桌面"，**保证全机 HOME 候选唯一**。
     *
     * <h3>为什么这条要变成常规步骤（推翻 v132 的判断）</h3>
     * v132 把"禁用其它 HOME 入口"降级成要 {@code {"force":true}} 才做的最后手段，理由是
     * "实测减到 1 个候选后按 Home 依然 3/3 弹选择器 ⇒ 它不解决问题，却废掉防砖兜底"。
     * 那个判断建立在**两组无效测量**上：
     * <ol>
     *   <li>减候选是拿 {@code pm disable com.ledi.agent/.BrainActivity} 做的，
     *       而它会把我们进程**打死**（且 CrashGuard 在同进程里，救不了自己）⇒ 该相位的数据全废；</li>
     *   <li>判据只看 {@code dumpsys window windows} 的第一条 {@code mCurrentFocus}，
     *       而选择器 ANR 时那一行恰好是 {@code null} ⇒ "弹了"和"没弹"判反。</li>
     * </ol>
     * 而结构上的道理很简单：{@code resolveIntent} 在候选数为 1 时**直接返回它**，
     * 只有 &gt;1 才会造出 ResolverActivity。所以候选唯一 = 解析不可能有歧义。
     *
     * <h3>为什么不会把机器人变成砖</h3>
     * {@code wantEnabled = (target == 我们自己)}：只有"原厂桌面还在、我们把桌面锁给它"时才关掉
     * 自己那条；一旦原厂 App 没了、{@link #targetHome} 退回指向我们自己，这里就会把自己**开回来**。
     * 关/开都用 {@code DONT_KILL_APP}（不会杀进程），并记进 {@code K_DISABLED} 好让
     * {@link #restore} 一键物归原主。
     *
     * @return 变了就返回一句描述，没变返回空串
     */
    private static String alignOwnHomeEntry(Context ctx, ComponentName target) {
        try {
            // ★★ v134：动的是**别名**，不是 BrainActivity 本体。见 OUR_HOME_CLS 的注释 ——
            //   禁本体 = 连"手动打开本界面 / main=brain"一起废掉（v133 的代价）。
            ComponentName ours = new ComponentName(OUR_PKG, OUR_HOME_CLS);
            boolean wantEnabled = ours.equals(target);   // 只有"目标就是我们自己"时才保留候选
            String nowState = oursEntryState(ctx);
            String want = wantEnabled ? "enabled" : "disabled";
            if (want.equals(nowState)) return "";
            int st = wantEnabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                 : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            ctx.getPackageManager().setComponentEnabledSetting(ours, st, PackageManager.DONT_KILL_APP);
            // 记账（给 restore 用）
            String list = sp(ctx).getString(K_DISABLED, "");
            if (wantEnabled) {
                StringBuilder nb = new StringBuilder();
                for (String f : list.split(",")) {
                    if (!f.isEmpty() && !f.equals(ours.flattenToString()))
                        nb.append(f).append(',');
                }
                sp(ctx).edit().putString(K_DISABLED, nb.toString()).apply();
            } else if (!list.contains(ours.flattenToString())) {
                sp(ctx).edit().putString(K_DISABLED, list + ours.flattenToString() + ",").apply();
            }
            BootLog.i("桌面: 我们自己的 HOME 入口 " + nowState + " → " + want
                    + "（候选唯一化；target=" + target.flattenToShortString() + "）");
            return nowState + "→" + want;
        } catch (Throwable t) {
            BootLog.w("桌面: 对齐自己那条 HOME 入口失败: " + t);
            return "fail=" + t;
        }
    }

    public static String ensure(Context ctx) {
        return ensure(ctx, false);
    }

    /**
     * @param allowDisable 是否允许走 ④（禁用其它 HOME 入口）。默认 false —— 见 forceSet ④ 的注释：
     *                     实测它在这台 ROM 上不解决问题，却会废掉我们的防砖兜底入口。
     */
    public static String ensure(Context ctx, boolean allowDisable) {
        try {
            ComponentName target = targetHome(ctx);
            // ★★ v134 迁移：v133 禁用过 BrainActivity **整个组件**，而这个状态写在
            //   package-restrictions.xml 里、覆盖安装不清 ⇒ 不主动开回来就永远留着。
            //   （"本体可用"是 v134 明确的验收项：am start -n .../.BrainActivity 必须能开）
            String migrated = migrateOldDisable(ctx);
            // ★★ v133：再保证"全机 HOME 候选唯一"。必须在 settled() 之前 ——
            //   否则 settled() 为真就直接 `return already`，这一步永远走不到
            //   （v132 的 `{"force":true}` 就是这么变成死路径的，今天实测抓到）。
            String aligned = alignOwnHomeEntry(ctx, target);
            if (!aligned.isEmpty() || !migrated.isEmpty()) {
                return "{\"ok\":true,\"result\":\"aligned\",\"align\":\"" + J.esc(aligned)
                        + "\",\"migrated\":\"" + J.esc(migrated)
                        + "\",\"home\":\"" + J.esc(currentHome(ctx))
                        + "\",\"candidates_n\":" + candidatesN(ctx)
                        + ",\"our_activity\":\"" + ourActivityState(ctx)
                        + "\",\"note\":\"候选唯一化后解析不可能再有歧义\"}";
            }
            String tname = target.getPackageName() + "/" + target.getClassName();
            String cur = currentHome(ctx);
            // ★ v132 起判据换成 settled()（= 唯一候选 或 "resolve说是目标 且 偏好查得到"）。
            //   旧写法只判 `cur.equals(tname) && recorded`，而 recorded 当时传错包名恒 false，
            //   于是每次都会重写一遍（无害但白干）；换包名之后又变成"过于乐观"——
            //   实测出现过 resolve 说是目标、按 Home 却弹选择器 ⇒ 干脆用结构性判据。
            if (settled(ctx)) {
                sp(ctx).edit().putLong(K_SET_AT, System.currentTimeMillis()).apply();
                return "{\"ok\":true,\"result\":\"already\",\"home\":\"" + J.esc(cur) + "\"}";
            }
            BootLog.i("桌面: 未落定（candidates=" + candidatesN(ctx) + " recorded=" + recorded(ctx)
                    + " on_target=" + isTarget(ctx) + "）⇒ 重设一遍");
            // 先记下"当前是谁"，将来能退回（cur 就是目标时别记，那会让 restore 记成自己）
            if (cur.isEmpty()) {
                BootLog.w("桌面: 当前无默认桌面（系统还在弹选择器）-> 直接指定 " + tname);
            } else if (!cur.equals(tname) && sp(ctx).getString(K_PREV, "").isEmpty()) {
                sp(ctx).edit().putString(K_PREV, cur).apply();
                BootLog.i("桌面: 记录改前的桌面 = " + cur);
            }
            return forceSet(ctx, target, allowDisable);
        } catch (Throwable t) {
            BootLog.w("桌面: ensure 异常 " + t);
            return "{\"ok\":false,\"error\":\"" + J.esc(String.valueOf(t)) + "\"}";
        }
    }

    /** 无条件把偏好写到 {@code target} 上（三级兜底；④ 需 allowDisable）。 */
    private static String forceSet(Context ctx, ComponentName target, boolean allowDisable) {
        PackageManager pm = ctx.getPackageManager();
        String tname = target.getPackageName() + "/" + target.getClassName();
        List<ComponentName> all = homeActivities(ctx);
        if (!all.contains(target)) {
            // 目标没声明 HOME 就不该走到这里（targetHome 已经过滤过），防御一下
            BootLog.w("桌面: 目标 " + tname + " 不在 HOME 候选里，候选=" + all);
            return "{\"ok\":false,\"result\":\"target-not-home\",\"home\":\""
                    + J.esc(currentHome(ctx)) + "\"}";
        }
        ComponentName[] set = all.toArray(new ComponentName[all.size()]);

        IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_HOME);
        filter.addCategory(Intent.CATEGORY_DEFAULT);

        StringBuilder trace = new StringBuilder();
        BootLog.i("桌面: 开始指定 " + tname + "，HOME 候选=" + all.size() + " 个 " + all);

        // ① 清掉我们自己历史上写过的偏好：留着旧的 set 会让新的 set 被系统无视。
        //    偏好记录挂在"调用方"(也就是我们)名下，所以清的是 OUR_PKG。
        try {
            pm.clearPackagePreferredActivities(OUR_PKG);
            trace.append("clear;");
        } catch (Throwable t) {
            trace.append("clear=").append(t).append(';');
        }

        // ② 标准做法
        try {
            // match 只比较 MATCH_CATEGORY_MASK 那几位，HOME 无 data ⇒ MATCH_CATEGORY_EMPTY
            pm.addPreferredActivity(filter, IntentFilter.MATCH_CATEGORY_EMPTY, set, target);
            trace.append("add;");
        } catch (Throwable t) {
            trace.append("add=").append(t).append(';');
        }
        if (settled(ctx)) return ok(ctx, "addPreferredActivity", trace);

        // ③ 已有旧偏好时用 replace 顶掉
        //    注意：replacePreferredActivity 在 Android 8 之后就被从公开 SDK 摘掉了，
        //    编译期的 android.jar 里没有它（直接写会 "找不到符号"），但运行时的 5.1 上有，
        //    所以这里走反射 —— 拿不到就跳过，交给 ④ 兜底。
        try {
            Method m = PackageManager.class.getMethod("replacePreferredActivity",
                    IntentFilter.class, int.class, ComponentName[].class, ComponentName.class);
            m.invoke(pm, filter, Integer.valueOf(IntentFilter.MATCH_CATEGORY_EMPTY), set, target);
            trace.append("replace;");
        } catch (Throwable t) {
            trace.append("replace=").append(t).append(';');
        }
        if (settled(ctx)) return ok(ctx, "replacePreferredActivity", trace);

        // ④ 最后手段：把**非目标**的 HOME 入口置为 disabled
        //    （只关入口，不卸 App、不动数据）。方向翻转后，这里禁用的正是我们自己。
        //
        // ★★ v132 起**默认不做**这一手，要 `POST /sethome {"force":true}` 才做。
        //    理由是实测：把候选从 2 个减到 1 个（`pm disable com.ledi.agent/.BrainActivity`）
        //    之后按 Home 依然 3/3 弹 ResolverActivity ⇒ **它不解决问题**，
        //    却会顺手废掉我们的防砖兜底入口（BrainActivity 一禁用就整个起不来）。
        //    既然"减候选"不是答案（原因见 REF-P：ROM 缺 home 栈 + 残留 Resolver 任务
        //    被当成 home 任务），那就不该默默付出这个代价。
        if (!allowDisable) {
            BootLog.w("桌面: ①② 都没落定，且未授权禁用其它 HOME 入口（要 {\"force\":true}）"
                    + " home=" + currentHome(ctx) + " candidates=" + candidatesN(ctx));
            trace.append("skip-disable;");
            return "{\"ok\":false,\"result\":\"not-effective\",\"home\":\"" + J.esc(currentHome(ctx))
                    + "\",\"candidates_n\":" + candidatesN(ctx)
                    + ",\"recorded\":" + recorded(ctx)
                    + ",\"trace\":\"" + J.esc(trace.toString()) + "\"}";
        }
        disableOtherHomes(ctx, all, target, trace);
        if (settled(ctx)) return ok(ctx, "disableOthers", trace);

        BootLog.w("桌面: 三级兜底都没生效！home=" + currentHome(ctx) + " trace=" + trace);
        return "{\"ok\":false,\"result\":\"failed\",\"home\":\"" + J.esc(currentHome(ctx))
                + "\",\"candidates_n\":" + candidatesN(ctx)
                + ",\"trace\":\"" + J.esc(trace.toString()) + "\"}";
    }

    private static String ok(Context ctx, String how, StringBuilder trace) {
        String cur = currentHome(ctx);
        sp(ctx).edit().putLong(K_SET_AT, System.currentTimeMillis()).apply();
        BootLog.i("桌面: 设置成功（" + how + "）home=" + cur + " trace=" + trace);
        return "{\"ok\":true,\"result\":\"" + how + "\",\"home\":\"" + J.esc(cur)
                + "\",\"trace\":\"" + J.esc(trace.toString()) + "\"}";
    }

    private static void disableOtherHomes(Context ctx, List<ComponentName> all,
                                          ComponentName target, StringBuilder trace) {
        PackageManager pm = ctx.getPackageManager();
        StringBuilder done = new StringBuilder();
        for (ComponentName cn : all) {
            if (cn.equals(target)) continue;                 // 目标绝不动
            try {
                pm.setComponentEnabledSetting(cn,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
                done.append(cn.flattenToString()).append(',');
                trace.append("disable:").append(cn.flattenToString()).append(';');
                BootLog.w("桌面: 已禁用其它 HOME 入口 " + cn.flattenToString());
            } catch (Throwable t) {
                trace.append("disable-fail:").append(cn.flattenToString()).append("=")
                        .append(t).append(';');
                BootLog.w("桌面: 禁用失败 " + cn.flattenToString() + " : " + t);
            }
        }
        if (done.length() > 0) {
            // 累积保存（多次调用不会把早先禁用的弄丢）
            String old = sp(ctx).getString(K_DISABLED, "");
            sp(ctx).edit().putString(K_DISABLED, old + done.toString()).apply();
        }
    }

    // ------------------------------------------------------------------ 回退

    /**
     * 物归原主：恢复被我们禁用的 HOME 入口 + 清掉自己的偏好 + 把目标桌面设回去。
     *
     * <p>方向翻转之后，"原主"就是原厂机器人 App；{@link #targetHome} 会自动
     * 在原厂不可用时退回我们自己，所以这里不需要另一套分支。
     */
    public static String restore(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        StringBuilder trace = new StringBuilder();

        // ① 恢复被我们禁用的组件（含可能被禁掉的我们自己）
        String list = sp(ctx).getString(K_DISABLED, "");
        for (String f : list.split(",")) {
            if (f.isEmpty()) continue;
            ComponentName cn = ComponentName.unflattenFromString(f);
            if (cn == null) continue;
            try {
                pm.setComponentEnabledSetting(cn,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
                trace.append("enable:").append(f).append(';');
            } catch (Throwable t) {
                trace.append("enable-fail:").append(f).append('=').append(t).append(';');
            }
        }
        sp(ctx).edit().remove(K_DISABLED).apply();

        // ①b v134：本体（BrainActivity）**永远**要开回来 —— 它是我们的兜底界面，
        //     而且它不再声明 HOME，所以开它不会让候选变成 2 个（不会把选择器带回来）。
        try {
            pm.setComponentEnabledSetting(new ComponentName(OUR_PKG, OUR_CLS),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            trace.append("enable-brain;");
        } catch (Throwable t) {
            trace.append("enable-brain-fail=").append(t).append(';');
        }

        // ② 清掉我们写过的 HOME 偏好
        try {
            pm.clearPackagePreferredActivities(OUR_PKG);
            trace.append("cleared-ours;");
        } catch (Throwable t) {
            trace.append("clear-fail=").append(t).append(';');
        }

        // ③ 把目标桌面设回去
        ComponentName target = targetHome(ctx);
        String tname = target.getPackageName() + "/" + target.getClassName();
        List<ComponentName> all = homeActivities(ctx);
        if (!all.contains(target)) all.add(target);
        IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_HOME);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        try {
            pm.addPreferredActivity(filter, IntentFilter.MATCH_CATEGORY_EMPTY,
                    all.toArray(new ComponentName[all.size()]), target);
            trace.append("restored:").append(tname).append(';');
        } catch (Throwable t) {
            trace.append("restore-fail=").append(t).append(';');
        }
        setLock(ctx, true);   // 回退之后继续锁在目标上
        BootLog.i("桌面: 回退完成 home=" + currentHome(ctx) + " trace=" + trace);
        return "{\"ok\":true,\"result\":\"restored\",\"target\":\"" + J.esc(tname)
                + "\",\"home\":\"" + J.esc(currentHome(ctx)) + "\",\"trace\":\"" + J.esc(trace.toString()) + "\"}";
    }

    // ------------------------------------------------------------------ housekeeping 钩子

    /**
     * 由 {@link AgentService#startHousekeeping} 定期调用：桌面被改成别的东西时，
     * 重新掰回目标。
     *
     * <p>为什么要反复盯：这台 MTK 5.1 ROM 在开机阶段会自己写一次默认桌面。
     * 多数情况下它写的正是原厂 FaceActivity（也就是我们的目标），那这条就什么都不做；
     * 但如果 ROM 写成了别的（或用户手动换了桌面），这里负责掰回来。
     * 只在前几分钟做这件事，避免和 ROM 无限拉锯。
     *
     * @param uptimeSec 开机后经过的秒数
     * @return 是否真的做了动作
     */
    public static boolean keepHome(Context ctx, long uptimeSec) {
        try {
            if (!lockOn(ctx)) return false;
            if (uptimeSec > 300) return false;          // 只盯开机后 5 分钟
            // ★ 这里**不用** recorded()（见它的注释：它在这台机器上"生效了也可能是 false"）。
            //   拿它当轮询门槛 → 恒 false → 开机后 5 分钟内每 10 秒 clear+add 一次，
            //   纯浪费 + 反复写 package-restrictions.xml。
            //   装包之后那次"要重设主屏"由部署脚本第 4 步负责（那里的判据是
            //   按 Home 看 mCurrentFocus，权威）。
            if (isTarget(ctx)) return false;
            // 距上次尝试至少隔 10 秒，别在异常情况下刷爆日志
            long last = sp(ctx).getLong("home_try_at", 0);
            long now = System.currentTimeMillis();
            if (now - last < 10_000L) return false;
            sp(ctx).edit().putLong("home_try_at", now).apply();
            BootLog.w("桌面: 当前是 " + currentHome(ctx) + "（偏好记录 recorded="
                    + recorded(ctx) + "）-> 掰回 " + targetName(ctx) + " (t+" + uptimeSec + "s)");
            ensure(ctx);
            return true;
        } catch (Throwable t) {
            BootLog.w("桌面: keepHome 异常 " + t);
            return false;
        }
    }

    // ------------------------------------------------------------------ 状态

    public static String statusJson(Context ctx) {
        long at = sp(ctx).getLong(K_SET_AT, 0);
        return "{\"home\":\"" + J.esc(currentHome(ctx))
                + "\",\"ours\":" + isUs(ctx)
                + ",\"target\":\"" + J.esc(targetName(ctx))
                + "\",\"on_target\":" + isTarget(ctx)
                // ★ 必须和 on_target 一起给：on_target 是 resolveActivity 挑出来的（可能乐观），
                //   recorded 才是"系统真的记下了"。**两者不一致本身就是症状** ——
                //   实测就是 on_target=true 但按 Home 弹选择器（见 recorded 的注释）。
                + ",\"recorded\":" + recorded(ctx)
                // ★ 候选个数 / 是否唯一：结构性的量，不依赖 ROM 怎么实现偏好。
                //   candidates_n>1 ⇒ **可能**弹选择器；unique=true ⇒ 任何解析路径都只能选它。
                //   ⚠ 但实测 candidates_n=1 时按 Home 仍可能弹（ROM 缺 home 栈），
                //     所以这两个字段是"线索"，权威判据永远是"按一次 Home 看 mCurrentFocus"。
                + ",\"candidates_n\":" + candidatesN(ctx)
                + ",\"unique\":" + unique(ctx)
                + ",\"settled\":" + settled(ctx)
                // v133：我们那条 HOME 入口（v134 起 = **别名** BrainHome）此刻是开是关
                //       （关着 = 全机候选唯一 = 解析无歧义）
                + ",\"ours_entry\":\"" + oursEntryState(ctx) + "\""
                // ★ v134：入口(别名)与**界面本体**分开报。本体必须是 enabled ——
                //   否则 am start -n com.ledi.agent/.BrainActivity 会失败（v133 的坑）。
                + ",\"our_activity\":\"" + ourActivityState(ctx) + "\""
                + ",\"ours_entry_cls\":\"" + J.esc(OUR_HOME_CLS) + "\""
                + ",\"our_activity_cls\":\"" + J.esc(OUR_CLS) + "\""
                + ",\"lock\":" + lockOn(ctx)
                + ",\"prev\":\"" + J.esc(sp(ctx).getString(K_PREV, ""))
                + "\",\"disabled\":\"" + J.esc(sp(ctx).getString(K_DISABLED, ""))
                + "\",\"set_at\":" + at
                + ",\"candidates\":\"" + J.esc(homeActivities(ctx).toString()) + "\"}";
    }
}
