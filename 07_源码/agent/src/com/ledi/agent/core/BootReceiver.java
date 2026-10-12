package com.ledi.agent.core;

import com.ledi.agent.sense.SensorHub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * 「把 AgentService 叫起来」的<b>静态注册</b>接收器 —— 进程死了也要能被它救活。
 *
 * <h3>为什么要挂这么多个 action</h3>
 * 真机（MTK 5.1 ROM）实测：<b>只挂 {@code BOOT_COMPLETED} 不保证能起来</b>。
 * 2026-09-18 晚一次重启后，{@code logcat -b events} 里整条开机过程**没有任何**
 * {@code am_proc_start ... com.ledi.agent}，8899 / 18080 / 齿轮全都没有
 * 而接收器在真机上确实是注册着的
 * （{@code dumpsys package} 的 Receiver Resolver Table 里有 BOOT_COMPLETED）。
 * 与其去猜 ROM 为什么吞掉那一条广播，不如把「开机/系统就绪后必然会来」的广播
 * 全部挂上，谁能来就靠谁把服务拉起来。
 *
 * <h3>★★ 2026-09-23 晚：新增「用户碰机器人」这一类高频通道（重要的那一类）</h3>
 * 上面那些系统广播全是<b>低频且不可控</b>的（重启、联网）。而 13 分钟那次"没人救"
 * 暴露的问题是：机器宕着的时候，用户**唯一会做的事就是去按它**。于是把原厂的
 * 按键/触摸广播也静态挂上 —— 它们的发送者是 <b>{@code system_server} 自己</b>
 * （实测 {@code Broadcast: Intent { act=com.auric.intell.xld.rbt.key.head.down flg=0x10 }
 * ordered=false callerApp=ProcessRecord{... 681:system/1000}}，<b>没有</b> {@code cmp}/{@code pkg}），
 * 是**隐式广播** ⇒ 进程死了也会被系统拉起来送达。
 *
 * <p>{@code flg=0x10} = {@code FLAG_EXCLUDE_STOPPED_PACKAGES}：排除"被 force-stop 过的包"。
 * 含义很明确 —— <b>本机制防不住 {@code am force-stop}</b>（那是系统设计的"别再来"），
 * 但能防住 native 崩溃 / LMK 杀进程 / 被别的东西 kill 掉这些"意外死亡"，
 * 而这正是我们要防的。
 *
 * <p>★ <b>热路径必须零开销</b>：这一组 action 里有一大半是"用户每碰一下就来一次"，
 * 所以第一件事就是 {@link AgentService#isRunning()} 判活，活着直接 return，
 * 连 {@code startService} 都不发。真正的按键处理仍在 {@link SensorHub} 的
 * 动态接收器里做 —— 本接收器<b>只负责"把进程叫起来"</b>，绝不重复处理按键，
 * 否则一次按键会被当成两次（长按/短按分流的计数就全乱了）。
 *
 * <p>代价（要知道）：进程死着的时候用户按一下键，这一次按键事件本身<b>不</b>会被处理
 * （动态接收器还没注册上），用户看到的是"机器人活过来了，但这一下没反应"。
 * 这比"死着不动"好得多，也是能用最少的活动部件换来的最好结果。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final String act = intent == null ? "(null)" : String.valueOf(intent.getAction());

        // ★★ 活着 ⇒ 立刻返回。绝不在这里做任何"处理按键"的事（见类注释）。
        if (AgentService.isRunning()) return;

        // 把"这次是谁把我叫起来的"贴成标签，交给 AgentService.onStartCommand 落账。
        // 不贴的话服务侧只能看到 intent.action==null，分辨不出按键/触摸/开机。
        ReviveLog.setPending(context, channelOf(act), "act=" + act);
        BootLog.i("BootReceiver.onReceive action=" + act
                + " uptime=" + (SystemClock.elapsedRealtime() / 1000) + "s -> startService");
        try {
            context.startService(new Intent(context, AgentService.class));
            BootLog.i("BootReceiver startService 已提交 (action=" + act + ")");
        } catch (Throwable t) {
            BootLog.w("BootReceiver startService 失败 action=" + act + " : " + t);
        }
    }

    /**
     * 广播 → 稳定短通道名（给 {@link ReviveLog} 统计用）。
     *
     * <p>粒度取"哪一类"而不是"哪一个"：验收要看的是"是**按键**救的命，还是**闹钟**救的命"，
     * 精确的 action 名放进 {@code detail} 里，不占统计口径。
     */
    private static String channelOf(String act) {
        if (act == null) return "bcast";
        if (act.startsWith("com.auric.intell.xld.rbt.key.")) return "key";
        if (act.startsWith("com.auric.intell.xld.rbt.face.")) return "touch";
        if (act.startsWith("com.auric.intell.xld.rbt.")) return "rbt";
        if (act.startsWith("android.intent.action.SCREEN")) return "screen";
        if (act.contains("CONNECTIVITY") || act.startsWith("android.net.wifi")) return "net";
        if (act.contains("QUICKBOOT") || act.contains("BOOT_COMPLETED")) return "boot";
        if (act.contains("MY_PACKAGE_REPLACED")) return "replaced";
        if (act.contains("USER_PRESENT")) return "user";
        return "bcast";
    }
}
