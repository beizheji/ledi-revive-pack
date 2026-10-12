package com.ledi.agent.sense;

import com.ledi.agent.core.AgentService;
import com.ledi.agent.core.J;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

/**
 * 电量读取（v202）—— 「机器人知道自己还剩多少电」的唯一出口。
 *
 * <h3>为什么单独一个类</h3>
 * 此前电量散在两处、且各报一半：
 * <ul>
 *   <li>{@link SensorHub#battery}：靠 {@code ACTION_BATTERY_CHANGED} 广播累出来的
 *       {@code level/charging/batteryLow}（被动，只在广播到达时更新）；</li>
 *   <li>{@code GET /status.battery}：走 {@code BatteryManager.BATTERY_PROPERTY_CAPACITY}
 *       （只给一个整数）。</li>
 * </ul>
 * 两者都拿不到"温度/电压/健康/充电器类型/多久没更新"。而机器人要**主动**说
 * "我快没电了""我在充电"时，缺的正是这几项。所以统一收口到这里：
 * <b>一次读全，且是主动读</b>（sticky 广播是廉价且准确的，不必等下一次变化）。
 *
 * <h3>诚实口径（本项目规矩：不认"调用没抛异常就算成功"）</h3>
 * <ul>
 *   <li>每条读数都带 {@code source}：{@code sticky}（电池广播的粘性值）/ {@code manager}
 *       （BatteryManager 属性）/ {@code none}（两路都读不到）。</li>
 *   <li>每条都带 {@code age_ms}：距 {@link SensorHub} 最近一次电池广播多久。
 *       <b>它是个古老度</b>，不是"新鲜度"证据 —— 读数是粘性快照，可能已经放了一会儿。</li>
 *   <li>{@code low_event} 是**原厂**那条低电量广播（{@code ...rbt.event.lowbattery}）是否来过，
 *       与百分比是两套判据，各自报，不互相覆盖。</li>
 * </ul>
 *
 * <p>★ 另有一条硬事实要说清：本机「电量」读到的是**平板电脑**（也就是机器人的大脑）的电池。
 * 原厂把平板当主控，所以它 = 机器人电量；但如果将来接的是一块独立电池组，这个字段会需要重解释。</p>
 */
public final class Battery {

    private Battery() {}

    /** 状态码 → 人话（原厂/Android 同款常量）。 */
    public static String statusStr(int s) {
        switch (s) {
            case BatteryManager.BATTERY_STATUS_CHARGING:     return "charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING:  return "discharging";
            case BatteryManager.BATTERY_STATUS_FULL:         return "full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "not_charging";
            case BatteryManager.BATTERY_STATUS_UNKNOWN:      return "unknown";
            default:                                          return "?";
        }
    }

    /** 充电器类型码 → 人话。 */
    public static String plugStr(int p) {
        switch (p) {
            case BatteryManager.BATTERY_PLUGGED_AC:       return "ac";
            case BatteryManager.BATTERY_PLUGGED_USB:      return "usb";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: return "wireless";
            case 0:                                       return "none";
            default:                                      return "?";
        }
    }

    private static String healthStr(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD:               return "good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT:           return "overheat";
            case BatteryManager.BATTERY_HEALTH_DEAD:               return "dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:       return "over_voltage";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE:return "failure";
            case BatteryManager.BATTERY_HEALTH_COLD:               return "cold";
            case BatteryManager.BATTERY_HEALTH_UNKNOWN:            return "unknown";
            default:                                               return "?";
        }
    }

    /** 当前百分比（0..100），读不到返回 -1。 */
    public static int percent(Context ctx) {
        int[] out = new int[]{-1};
        read(ctx, out, null);
        return out[0];
    }

    /**
     * 完整电量信息（JSON）。所有字段都尽量给，缺的字段**如实标 -1 / "?"**，不猜。
     */
    public static String info(Context ctx) {
        int[] pct = new int[]{-1};
        StringBuilder extra = new StringBuilder();
        read(ctx, pct, extra);
        int s = AgentService.sensor == null ? -1 : AgentService.sensor.battery;
        long age = -1;
        boolean lowEvent = false;
        try {
            if (AgentService.sensor != null) {
                lowEvent = AgentService.sensor.batteryLow;
                long ts = AgentService.sensor.batteryTs;
                age = ts <= 0 ? -1 : (System.currentTimeMillis() - ts);
            }
        } catch (Throwable ignored) {}

        StringBuilder sb = new StringBuilder(280);
        sb.append("{\"ok\":").append(pct[0] >= 0)
          .append(",\"level\":").append(pct[0])
          // extra 里是 ",\"x\":y" 形式的片段，直接拼在中间
          .append(extra)
          .append(",\"sensor_level\":").append(s)
          .append(",\"low_event\":").append(lowEvent)
          .append(",\"age_ms\":").append(age)
          .append(",\"unit\":\"percent\"")
          .append(",\"note\":\"电量读的是本机（平板/机器人主控）电池；age_ms 是距最近一次电池广播的年龄\"")
          .append('}');
        return sb.toString();
    }

    /**
     * 读一次并填充：{@code pct[0]} = 百分比；{@code extra} 追加完整字段片段。
     *
     * <p>优先 sticky {@code ACTION_BATTERY_CHANGED}（一次拿全），失败再退
     * {@code BatteryManager}（只补一个百分比）。</p>
     */
    private static void read(Context ctx, int[] pct, StringBuilder extra) {
        String source = "none";
        int rawLevel = -1, scale = 100, status = -1, plugged = -1,
            health = -1, temp = -1, volt = -1, present = -1;
        String tech = "";
        if (ctx != null) {
            try {
                // registerReceiver(null, …) = 取该粘性广播的最近值，不注册常驻接收者。
                Intent it = ctx.registerReceiver(null,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (it != null) {
                    source = "sticky";
                    rawLevel = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    scale    = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    status   = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    plugged  = it.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                    health   = it.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
                    temp     = it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                    volt     = it.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
                    present  = it.getIntExtra(BatteryManager.EXTRA_PRESENT, -1);
                    String t = it.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
                    if (t != null) tech = t;
                }
            } catch (Throwable ignored) {}
        }
        if (rawLevel >= 0 && scale > 0) {
            pct[0] = rawLevel * 100 / scale;
        } else {
            // 退路：只拿百分比
            try {
                BatteryManager bm = (BatteryManager) ctx.getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) {
                    int c = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                    if (c >= 0) {
                        pct[0] = c;
                        if ("none".equals(source)) source = "manager";
                    }
                }
            } catch (Throwable ignored) {}
        }
        boolean charging = (status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL);
        if (extra != null) {
            extra.append(",\"raw_level\":").append(rawLevel)
                 .append(",\"scale\":").append(scale)
                 .append(",\"status\":").append(status)
                 .append(",\"status_str\":\"").append(statusStr(status)).append('"')
                 .append(",\"plugged\":").append(plugged)
                 .append(",\"plugged_str\":\"").append(plugStr(plugged)).append('"')
                 .append(",\"health\":").append(health)
                 .append(",\"health_str\":\"").append(healthStr(health)).append('"')
                 // temp 是 0.1℃ 为单位；读不到给 -1
                 .append(",\"temp_c\":").append(temp >= 0 ? (temp / 10.0) : -1)
                 // voltage 是 mV；同时给 V 方便读
                 .append(",\"voltage_mv\":").append(volt)
                 .append(",\"voltage_v\":").append(volt >= 0 ? (volt / 1000.0) : -1)
                 .append(",\"present\":").append(present)
                 .append(",\"technology\":\"").append(J.esc(tech)).append('"')
                 .append(",\"charging\":").append(charging)
                 .append(",\"source\":\"").append(source).append('"');
        }
    }

    /** 给 AI/语音用的一句话（不含 JSON 噪声）。 */
    public static String human(Context ctx) {
        int p = percent(ctx);
        if (p < 0) return "电量读不到";
        boolean charging = false;
        try {
            Intent it = ctx == null ? null : ctx.registerReceiver(null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (it != null) {
                int st = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = (st == BatteryManager.BATTERY_STATUS_CHARGING
                        || st == BatteryManager.BATTERY_STATUS_FULL);
            }
        } catch (Throwable ignored) {}
        return "电量 " + p + "%" + (charging ? "（充电中）" : "");
    }
}
