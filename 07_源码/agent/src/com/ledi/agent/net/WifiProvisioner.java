package com.ledi.agent.net;

import com.ledi.agent.core.AgentService;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.util.List;

/**
 * WiFi 配网执行器：BLE / HTTP 收到 SSID+密码后调用。
 * system UID（android.uid.system）+ platform 签名 → 旧版 WifiManager API 直接可用。
 */
public class WifiProvisioner {

    public interface Callback { void onResult(boolean ok, String message); }

    private final Context ctx;

    public WifiProvisioner(Context ctx) { this.ctx = ctx; }

    public String connect(final String ssid, final String pass, final Callback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                Result r = doConnect(ssid, pass);
                Log.i(AgentService.TAG, "wifi provision: " + r.message);
                if (cb != null) cb.onResult(r.ok, r.message);
            }
        }).start();
        return "provisioning started: " + ssid;
    }

    static class Result { boolean ok; String message; }

    private Result doConnect(String ssid, String pass) {
        Result r = new Result();
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (!wm.isWifiEnabled()) {
                wm.setWifiEnabled(true);
                Thread.sleep(1500);
            }
            // 已保存过则先复用
            int netId = -1;
            List<WifiConfiguration> saved = wm.getConfiguredNetworks();
            if (saved != null) {
                for (WifiConfiguration c : saved) {
                    if (c.SSID != null && c.SSID.equals(q(ssid))) { netId = c.networkId; break; }
                }
            }
            if (netId < 0) {
                WifiConfiguration wc = new WifiConfiguration();
                wc.SSID = q(ssid);
                if (pass == null || pass.isEmpty()) {
                    wc.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                } else {
                    wc.preSharedKey = q(pass);
                }
                netId = wm.addNetwork(wc);
            }
            if (netId < 0) {
                r.ok = false; r.message = "addNetwork failed";
                return r;
            }
            wm.disconnect();
            wm.enableNetwork(netId, true);
            wm.reconnect();
            // 最多等 20 秒拿 IP
            for (int i = 0; i < 20; i++) {
                Thread.sleep(1000);
                android.net.wifi.WifiInfo info = wm.getConnectionInfo();
                if (info != null && info.getIpAddress() != 0 && info.getNetworkId() == netId) {
                    String ip = ipToString(info.getIpAddress());
                    r.ok = true;
                    r.message = "OK ip=" + ip + " ssid=" + ssid;
                    return r;
                }
            }
            r.ok = false; r.message = "connect timeout (20s)";
        } catch (Exception e) {
            r.ok = false; r.message = "error: " + e;
        }
        return r;
    }

    public String status() {
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            android.net.wifi.WifiInfo info = wm.getConnectionInfo();
            if (info == null || info.getIpAddress() == 0) return "wifi: disconnected";
            return "wifi: " + info.getSSID() + " ip=" + ipToString(info.getIpAddress());
        } catch (Exception e) {
            return "wifi: error " + e;
        }
    }

    static String q(String s) { return "\"" + s.replace("\"", "\\\"") + "\""; }

    static String ipToString(int ip) {
        return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
    }
}
