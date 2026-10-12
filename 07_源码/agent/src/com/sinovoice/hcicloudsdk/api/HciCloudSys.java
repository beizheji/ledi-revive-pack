package com.sinovoice.hcicloudsdk.api;

/**
 * 灵云（Sinovoice）系统级 API —— 最小静态 native 封装。
 * 与官方 com.sinovoice.hcicloudsdk.api.HciCloudSys 的 JNI 签名一致。
 * native 实现位于 libhci_sys_jni.so（依赖 libhci_sys.so / libhci_curl.so）。
 */
public class HciCloudSys {

    static {
        try {
            System.loadLibrary("hci_curl");
        } catch (Throwable t) {
            android.util.Log.w("HciCloudSys", "load hci_curl skip: " + t);
        }
        try {
            System.loadLibrary("hci_sys");
        } catch (Throwable t) {
            android.util.Log.w("HciCloudSys", "load hci_sys skip: " + t);
        }
        try {
            System.loadLibrary("hci_sys_jni");
        } catch (Throwable t) {
            android.util.Log.w("HciCloudSys", "load hci_sys_jni skip: " + t);
        }
    }

    public HciCloudSys() {
    }

    /**
     * 系统初始化（native）。
     * @param initParam 初始化参数，如 "appKey=xxx,developerKey=yyy"
     * @param context Android Context（可为 null）
     * @return 0 成功，非 0 错误码
     */
    public static native int hciInitInternal(String initParam, Object context);

    /**
     * 系统释放（native）。
     */
    public static native int hciReleaseInternal();

    /**
     * 获取错误信息（native）。
     */
    public static native String hciGetErrorInfo(int errCode);
}
