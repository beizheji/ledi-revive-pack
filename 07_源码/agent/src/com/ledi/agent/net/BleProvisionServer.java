package com.ledi.agent.net;

import com.ledi.agent.core.AgentService;
import com.ledi.agent.core.BootLog;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import java.util.UUID;

/**
 * BLE 配网服务端（GATT Server）。
 * 协议（自定义 128bit UUID）：
 *   Service  7a0c0001-6b5d-4e8f-9b3a-5c1d2e3f4a5b
 *   Char 02  SSID（write）
 *   Char 03  PASSWORD（write）
 *   Char 04  RESULT（notify，返回 "OK ip=..." 或 "FAIL ..."）
 *   Char 05  PING（read，返回 "ledi-agent"）
 * 手机 App 连接后依次写 SSID/PASS，机器人联网后通过 RESULT 上报结果。
 */
public class BleProvisionServer {

    public static final UUID SERVICE   = UUID.fromString("7a0c0001-6b5d-4e8f-9b3a-5c1d2e3f4a5b");
    public static final UUID CHAR_SSID = UUID.fromString("7a0c0002-6b5d-4e8f-9b3a-5c1d2e3f4a5b");
    public static final UUID CHAR_PASS = UUID.fromString("7a0c0003-6b5d-4e8f-9b3a-5c1d2e3f4a5b");
    public static final UUID CHAR_RESULT = UUID.fromString("7a0c0004-6b5d-4e8f-9b3a-5c1d2e3f4a5b");
    public static final UUID CHAR_PING = UUID.fromString("7a0c0005-6b5d-4e8f-9b3a-5c1d2e3f4a5b");

    private final Context ctx;
    private final WifiProvisioner wifi;
    private volatile boolean running;
    private BluetoothGattServer gattServer;
    private BluetoothLeAdvertiser advertiser;
    private volatile BluetoothDevice client;
    private volatile BluetoothGattCharacteristic resultChar;
    private volatile String pendingSsid = "";

    private final BluetoothGattServerCallback callback = new BluetoothGattServerCallback() {
        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            Log.i(AgentService.TAG, "BLE " + device.getAddress() + " state=" + newState);
            if (newState == BluetoothGatt.STATE_CONNECTED) client = device;
            if (newState == BluetoothGatt.STATE_DISCONNECTED) client = null;
        }

        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                BluetoothGattCharacteristic ch, boolean prepared, boolean responseNeeded,
                int offset, byte[] value) {
            String s = value == null ? "" : new String(value).trim();
            UUID u = ch.getUuid();
            Log.i(AgentService.TAG, "BLE write " + u + " len=" + s.length());
            if (CHAR_SSID.equals(u)) {
                pendingSsid = s;
                if (responseNeeded) gattServer.sendResponse(device, requestId, 0, 0, value);
            } else if (CHAR_PASS.equals(u)) {
                if (responseNeeded) gattServer.sendResponse(device, requestId, 0, 0, value);
                final BluetoothDevice dev = device;
                wifi.connect(pendingSsid, s, new WifiProvisioner.Callback() {
                    @Override public void onResult(boolean ok, String message) {
                        notifyResult(dev, (ok ? "OK " : "FAIL ") + message);
                    }
                });
            } else {
                if (responseNeeded) gattServer.sendResponse(device, requestId, 0, 0, value);
            }
        }

        @Override public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                int offset, BluetoothGattCharacteristic ch) {
            byte[] v = CHAR_PING.equals(ch.getUuid()) ? "ledi-agent".getBytes() : new byte[0];
            gattServer.sendResponse(device, requestId, 0, 0, v);
        }

        @Override public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                android.bluetooth.BluetoothGattDescriptor desc, boolean prepared, boolean responseNeeded,
                int offset, byte[] value) {
            if (CHAR_RESULT.equals(desc.getCharacteristic().getUuid())) {
                resultChar = desc.getCharacteristic();
            }
            if (responseNeeded) gattServer.sendResponse(device, requestId, 0, 0, value);
        }
    };

    public BleProvisionServer(Context ctx, WifiProvisioner wifi) {
        this.ctx = ctx;
        this.wifi = wifi;
    }

    public void start() {
        if (running) return;
        try {
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm.getAdapter();
            try { adapter.setName("LediAgent"); } catch (Exception ignored) {}
            if (!adapter.isEnabled()) {
                // 蓝牙还没开：GATT server 现在开不起来（实测 openGattServer 直接返回 null）。
                // 交给 ensureStarted() 由后勤线程在蓝牙就绪后重试 —— 不能只试一次就放弃。
                BootLog.w("BLE: 蓝牙还没开，暂缓开 GATT（等蓝牙就绪后重试）");
                return;
            }
            gattServer = bm.openGattServer(ctx, callback);
            if (gattServer == null) {
                BootLog.w("BLE: openGattServer 返回 null");
                Log.e(AgentService.TAG, "openGattServer null");
                return;
            }
            BluetoothGattService svc = new BluetoothGattService(SERVICE,
                    BluetoothGattService.SERVICE_TYPE_PRIMARY);
            svc.addCharacteristic(newChar(CHAR_SSID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE));
            svc.addCharacteristic(newChar(CHAR_PASS,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE));
            svc.addCharacteristic(newChar(CHAR_RESULT,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ));
            svc.addCharacteristic(newChar(CHAR_PING,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ));
            gattServer.addService(svc);
            // 广播（若芯片不支持则手机按设备名扫描）
            if (adapter.isMultipleAdvertisementSupported() || true) {
                advertiser = adapter.getBluetoothLeAdvertiser();
                if (advertiser != null) {
                    AdvertiseSettings st = new AdvertiseSettings.Builder()
                            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                            .setConnectable(true).build();
                    AdvertiseData dt = new AdvertiseData.Builder()
                            .addServiceUuid(new ParcelUuid(SERVICE))
                            .setIncludeDeviceName(true).build();
                    advertiser.startAdvertising(st, dt, new AdvertiseCallback() {
                        @Override public void onStartFailure(int errorCode) {
                            Log.e(AgentService.TAG, "advertise fail " + errorCode);
                        }
                    });
                }
            }
            Log.i(AgentService.TAG, "BLE provision server started");
            running = true;
            BootLog.i("BLE: 配网 GATT 已就绪 name=LediAgent");
        } catch (Exception e) {
            Log.e(AgentService.TAG, "BLE start fail", e);
            BootLog.w("BLE: 启动失败 " + e);
        }
    }

    /**
     * 幂等补齐：还没起来就再试一次（由 AgentService 的后勤线程在蓝牙打开后调）。
     * 蓝牙刚开机可能还没就绪，只试一次就永远没有配网通道了。
     */
    public void ensureStarted() {
        if (running) return;
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null || !a.isEnabled()) return;
        start();
    }

    private BluetoothGattCharacteristic newChar(UUID u, int prop, int perm) {
        BluetoothGattCharacteristic c = new BluetoothGattCharacteristic(u, prop, perm);
        if (prop == BluetoothGattCharacteristic.PROPERTY_NOTIFY) {
            c.addDescriptor(new android.bluetooth.BluetoothGattDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                    android.bluetooth.BluetoothGattDescriptor.PERMISSION_WRITE));
        }
        return c;
    }

    private void notifyResult(BluetoothDevice device, String msg) {
        try {
            if (gattServer == null || device == null) return;
            BluetoothGattCharacteristic c = gattServer.getService(SERVICE) == null ? null
                    : gattServer.getService(SERVICE).getCharacteristic(CHAR_RESULT);
            if (c == null) return;
            c.setValue(msg);
            gattServer.notifyCharacteristicChanged(device, c, false);
        } catch (Exception e) {
            Log.e(AgentService.TAG, "notify fail", e);
        }
    }

    public void stop() {
        running = false;
        try { if (advertiser != null) advertiser.stopAdvertising(new AdvertiseCallback() {}); } catch (Exception ignored) {}
        try { if (gattServer != null) gattServer.close(); } catch (Exception ignored) {}
        gattServer = null;
        advertiser = null;
    }

    public boolean isRunning() { return running; }
}
