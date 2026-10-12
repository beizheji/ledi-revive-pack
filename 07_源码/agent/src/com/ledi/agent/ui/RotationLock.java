package com.ledi.agent.ui;

import com.ledi.agent.core.BootLog;

import com.ledi.agent.core.AgentService;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

/**
 * 把机器人屏幕**锁死在横向**，不让它再跟着陀螺仪乱转。
 *
 * <h3>问题现场（2026-09 实测）</h3>
 * 症状：机器人屏幕上那张脸是躺着的（内容被转了 90°），而且点 Home 也回不到桌面。
 * 取证（{@code dumpsys window} / {@code dumpsys display}）：
 * <pre>
 *   mRotation=3                      ← 显示被转了 270°
 *   app 480 x 800, rotation 3        ← 应用视口成了竖向
 *   Built-in Screen, 800 x 480       ← 而面板原生是横向
 *   accelerometer_rotation = 1       ← 自动旋转开着
 *   user_rotation          = null
 * </pre>
 *
 * <h3>为什么"跟着陀螺仪转"在这台机器上必然是错的</h3>
 * 它是机器人，不是手机 —— 会被抱起来、倾斜、放倒。加速度计一抖，系统就把整个
 * 显示转 90°，于是机器人的脸躺下了；而"没有鼠标、触摸区很小"的机器上，
 * 用户根本没法把它转回来。另外原厂 {@code FaceActivity} 在清单里写死了
 * {@code android:screenOrientation="0"}（= SCREEN_ORIENTATION_LANDSCAPE），
 * 面板原生也是 800×480 —— 横向才是唯一正确的朝向。自动旋转对它没有任何价值，
 * 只有风险。
 *
 * <h3>做法</h3>
 * {@code accelerometer_rotation=0} + {@code user_rotation=0}（ROTATION_0 = 横向）。
 * 本 APK 是 {@code sharedUserId=android.uid.system} + platform 签名，直接写系统设置
 * 即可静默生效（清单里也申了 WRITE_SETTINGS）。
 *
 * <h3>为什么要 keep 而不只做一次</h3>
 * 和桌面（{@link HomeSetter}）一样，ROM 在开机阶段会自己再写一遍这些设置，
 * 把我们的值覆盖掉。所以前几分钟内被改回来就抢回来，之后不再管
 * —— 免得永久性地跟用户的意图打架。
 */
public final class RotationLock {

    static final String PREFS = "ledi_agent";
    static final String KEY_ON = "rot_lock_on";
    static final String KEY_VAL = "rot_lock_val";

    /** 期望的旋转：0=横向（面板原生 + 原厂 FaceActivity 声明），1/2/3 依次 90/180/270。 */
    public static final int DESIRED = 0;

    /** 前几分钟内每轮巡检都确认一次（覆盖 ROM 开机阶段的写入）。 */
    static final long ENFORCE_SECONDS = 300;

    private RotationLock() {}

    public static boolean enabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ON, true);
    }

    public static int value(Context ctx) {
        return prefs(ctx).getInt(KEY_VAL, DESIRED);
    }

    public static void set(Context ctx, Boolean on, Integer value) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (on != null) e.putBoolean(KEY_ON, on);
        if (value != null) e.putInt(KEY_VAL, ((value % 4) + 4) % 4);
        e.apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 立刻核对并纠正一次。返回是否做了修改。 */
    public static boolean apply(Context ctx) {
        if (!enabled(ctx)) return false;
        int want = value(ctx);
        boolean changed = false;
        try {
            ContentResolver cr = ctx.getContentResolver();
            int accel = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1);
            int user = Settings.System.getInt(cr, Settings.System.USER_ROTATION, -1);
            if (accel != 0) {
                Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0);
                BootLog.w("屏幕朝向: 关掉自动旋转（原 " + accel + "）");
                changed = true;
            }
            if (user != want) {
                Settings.System.putInt(cr, Settings.System.USER_ROTATION, want);
                BootLog.w("屏幕朝向: 锁定到 " + want + "（原 " + user + "）");
                changed = true;
            }
        } catch (Throwable t) {
            Log.w(AgentService.TAG, "锁屏朝向失败", t);
            BootLog.w("屏幕朝向: 写入失败 " + t);
        }
        return changed;
    }

    /** 巡检里调用：只在开机后前 {@link #ENFORCE_SECONDS} 秒内抢，之后不再插手。 */
    public static void keep(Context ctx, long upSeconds) {
        if (upSeconds > ENFORCE_SECONDS) return;
        if (!enabled(ctx)) return;
        apply(ctx);
    }

    /** 给 /rotation 和 /status 看的现状。 */
    public static String statusJson(Context ctx) {
        int accel = -1, user = -1, rot = -1;
        try {
            ContentResolver cr = ctx.getContentResolver();
            accel = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, -1);
            user = Settings.System.getInt(cr, Settings.System.USER_ROTATION, -1);
        } catch (Throwable ignored) {}
        try {
            android.view.WindowManager wm = (android.view.WindowManager)
                    ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) rot = wm.getDefaultDisplay().getRotation();
        } catch (Throwable ignored) {}
        boolean ok = enabled(ctx) ? (accel == 0 && user == value(ctx)) : true;
        return "{\"on\":" + enabled(ctx)
                + ",\"want\":" + value(ctx)
                + ",\"accel\":" + accel
                + ",\"user\":" + user
                + ",\"rotation\":" + rot
                + ",\"ok\":" + ok + "}";
    }
}
