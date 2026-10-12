package com.ledi.agent.net;

import com.ledi.agent.core.AgentService;
import com.ledi.agent.core.J;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 蓝牙网络共享（PAN-NAP）开关 —— 没有 WiFi 时，机器人当一台"蓝牙小网关"。
 *
 * <h3>为什么需要它</h3>
 * 电脑端有两条路能找到机器人：同一个 WiFi 上听广播（日常），或者扫网段（兜底）。
 * 这两条都要求"有 WiFi"。如果现场根本没有 WiFi（或者路由器把客户端隔离了），
 * 就只能走蓝牙：机器人打开「蓝牙网络共享」后自己会变成 192.168.44.1 这样一个小网关，
 * 电脑端加入它的个人区域网（PANU）之后，照样能连 {@code http://192.168.44.1:8899}。
 *
 * <h3>怎么开</h3>
 * 三层，从前到后：
 * <ol>
 *   <li><b>反射 {@code BluetoothPan.setBluetoothTethering}</b> —— 就是设置界面里
 *       「蓝牙网络共享」那个开关背后调的方法。这是个 @hide 的 public 方法，
 *       经 {@code BluetoothAdapter.getProfileProxy(PROFILE_PAN)} 拿到代理后反射调用。
 *       本包是 {@code android.uid.system} + platform 签名，权限直接授予、不弹框，
 *       所以这一层在真机上就是能直接成的。（Android 5.1 的 {@code BluetoothProfile.PAN}
 *       常量还没公开，用字面量 5。）</li>
 *   <li><b>root + settings</b>：直接写 {@code settings put global bluetooth_tethering_on 1}。
 *       本机有 Magisk 的 su，这条是"反射被 ROM 改坏"时的备胎。</li>
 *   <li><b>打开设置页</b>：最后退化成把「网络共享与便携热点」界面推到屏幕上，
 *       让人点一下。这不是失败 —— 是"我们尽力了，剩下的一步交给人"。</li>
 * </ol>
 *
 * <h3>注意</h3>
 * 一定要<b>机器人先开共享</b>、电脑再加入 PAN。反过来的话机器人没广播 SDP 记录，
 * 电脑那边会一直停在"正在连接"。电脑端 {@code robot_finder.bt_join_pan()} 就是
 * 按这个顺序做的。
 */
public class BtPan {

    /** BluetoothProfile.PAN —— API 22 里这个常量还是 @hide 的，只能写字面量 */
    private static final int PROFILE_PAN = 5;
    /** 拿 profile 代理的超时：正常几十毫秒，蓝牙栈没起来时才会卡到超时 */
    private static final long PROXY_TIMEOUT_MS = 6000;

    private static volatile long lastTryAt = 0;
    private static volatile String lastResult = "";
    private static volatile boolean lastWanted = false;
    /** 开关状态缓存 —— /health 会被电脑端反复轮询，绝不能每次都去问蓝牙栈
     *  （getProfileProxy 是异步的，最坏要等满超时）。 */
    private static volatile Boolean cachedOn = null;
    private static volatile long cachedAt = 0;
    private static final long CACHE_MS = 60000;

    /** 取 BluetoothPan 代理（拿不到返回 null）。异步回调，这里用 latch 等。 */
    private static BluetoothProfile proxy(Context ctx) {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) return null;
        final CountDownLatch latch = new CountDownLatch(1);
        final BluetoothProfile[] box = new BluetoothProfile[1];
        BluetoothProfile.ServiceListener lis = new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile p) {
                box[0] = p;
                latch.countDown();
            }
            @Override public void onServiceDisconnected(int profile) { }
        };
        try {
            if (!ad.getProfileProxy(ctx, lis, PROFILE_PAN)) return null;
            if (!latch.await(PROXY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return null;
            return box[0];
        } catch (Exception e) {
            Log.w(AgentService.TAG, "BtPan proxy 失败: " + e);
            return null;
        }
    }

    private static void release(BluetoothProfile p) {
        try {
            BluetoothAdapter.getDefaultAdapter().closeProfileProxy(PROFILE_PAN, p);
        } catch (Exception ignored) { }
    }

    /** 缓存里的开关状态（**纯读**，不发任何蓝牙调用）。/health 用这个。 */
    public static Boolean cached() { return cachedOn; }

    /** 强制真查一次并写进缓存（后勤线程低频调用，别在请求路径上叫它）。 */
    public static Boolean poll(Context ctx) {
        BluetoothProfile p = proxy(ctx);
        if (p == null) return cachedOn;              // 查不到就保留旧值，别把状态抹成未知
        try {
            Method m = p.getClass().getMethod("isTetheringOn");
            Object r = m.invoke(p);
            if (r instanceof Boolean) {
                cachedOn = (Boolean) r;
                cachedAt = SystemClock.elapsedRealtime();
            }
            return cachedOn;
        } catch (Exception e) {
            Log.w(AgentService.TAG, "BtPan isTetheringOn 失败: " + e);
            return cachedOn;
        } finally {
            release(p);
        }
    }

    /** 当前是否已经在共享中（带 60 秒缓存；拿不到返回 null = 不知道）。 */
    public static Boolean isTethering(Context ctx) {
        if (cachedOn != null && SystemClock.elapsedRealtime() - cachedAt < CACHE_MS)
            return cachedOn;
        return poll(ctx);
    }

    /**
     * 开/关蓝牙网络共享。
     *
     * @return 真正调成功返回 null（没错），失败返回原因字符串
     */
    public static String setTethering(Context ctx, boolean on) {
        lastWanted = on;
        lastTryAt = SystemClock.elapsedRealtime();

        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) {
            lastResult = "本机没有蓝牙适配器";
            return lastResult;
        }
        if (on && !ad.isEnabled()) {
            // 共享必须先有蓝牙。ROM 上蓝牙平时是开的，这里只是防御。
            lastResult = "蓝牙没开，先把蓝牙打开再试";
            return lastResult;
        }

        // ---- ① 反射 BluetoothPan（主路）----
        BluetoothProfile p = proxy(ctx);
        if (p != null) {
            try {
                Method m = p.getClass().getMethod("setBluetoothTethering",
                        boolean.class);
                Object r = m.invoke(p, on);
                boolean ok = !(r instanceof Boolean) || (Boolean) r;
                if (ok) {
                    lastResult = "通过系统蓝牙服务设置成功";
                    Log.i(AgentService.TAG, "BtPan setTethering(" + on + ") OK");
                    return null;
                }
                Log.w(AgentService.TAG, "BtPan setTethering(" + on + ") 返回 false");
            } catch (Exception e) {
                Log.w(AgentService.TAG, "BtPan 反射失败: " + e);
            } finally {
                release(p);
            }
        }

        // ---- ② root + settings（备胎）----
        if (runRoot("settings put global bluetooth_tethering_on " + (on ? "1" : "0"))) {
            lastResult = "已通过 root 写入设置（可能需要几秒生效）";
            return null;
        }

        // ---- ③ 打开设置页，交给用户点一下 ----
        openTetherSettings(ctx);
        lastResult = "打不开开关，已把「网络共享」设置页推到屏幕上，请手动开启蓝牙共享";
        return lastResult;
    }

    /** 把「网络共享与便携热点」界面推到屏幕上（最后一层兜底）。 */
    public static void openTetherSettings(Context ctx) {
        String[] acts = {"android.settings.TETHER_SETTINGS",
                         "android.settings.WIRELESS_SETTINGS"};
        for (String a : acts) {
            try {
                Intent i = new Intent(a);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                return;
            } catch (Exception ignored) { }
        }
    }

    /** root 执行一条命令（没有 su 就返回 false）。 */
    private static boolean runRoot(String cmd) {
        Process pr = null;
        try {
            pr = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            return pr.waitFor() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            try { if (pr != null) pr.destroy(); } catch (Exception ignored) { }
        }
    }

    /** 供 /health 与日志观察（纯读缓存，不做任何蓝牙调用）。 */
    public static String statusJson(Context ctx) {
        Boolean on = cached();
        long ago = lastTryAt == 0 ? -1
                : (SystemClock.elapsedRealtime() - lastTryAt) / 1000;
        return "{\"on\":" + (on == null ? "null" : on.toString())
                + ",\"wanted\":" + lastWanted
                + ",\"last_try_ago\":" + ago
                + ",\"last_result\":\"" + J.esc(lastResult) + "\"}";
    }
}
