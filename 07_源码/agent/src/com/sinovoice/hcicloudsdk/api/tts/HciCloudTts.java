package com.sinovoice.hcicloudsdk.api.tts;

import com.sinovoice.hcicloudsdk.common.Session;
import com.sinovoice.hcicloudsdk.common.tts.ITtsSynthCallback;

/**
 * 灵云（Sinovoice）TTS 本地离线合成引擎 —— 静态 native 封装。
 *
 * 与官方 com.sinovoice.hcicloudsdk.api.tts.HciCloudTts 的 JNI 签名完全一致，
 * native 实现位于 libhci_tts_jni.so（依赖 libhci_tts.so / libhci_sys.so /
 * libhci_tts_local_synth.so + libxixi_*Package.dat.so 音色数据）。
 *
 * 调用链：
 *   hciTtsInit(capKey) -> hciTtsSessionStart(param, session)
 *   -> hciTtsSynth(session, text, param, callback) -> onSynthFinish 回调拿 PCM
 *   -> hciTtsSessionStop(session) -> hciTtsRelease()
 */
public class HciCloudTts {

    static {
        // 完整复刻官方 HciCloudTts.<clinit> 的 so 加载序列：
        //   hci_curl / hci_sys（基础库，System.loadLibrary 硬依赖）
        //   然后 jtopus / jtspeex / hci_tts_cloud_synth / hci_tts_local_synth /
        //   hci_tts_local-v5_synth 等合成引擎（loadLibraryIfExist 语义：缺了就跳过）。
        loadIfExist("hci_curl");
        loadIfExist("hci_sys");
        loadIfExist("hci_sys_jni");
        loadIfExist("hci_tts");
        loadIfExist("hci_tts_jni");
        // 本地合成引擎（核心）+ 编解码
        loadIfExist("jtopus");
        loadIfExist("jtspeex");
        loadIfExist("hci_tts_cloud_synth");
        loadIfExist("hci_tts_local_synth");        // 本地合成（N4/基础）
        loadIfExist("hci_tts_local-v5_synth");     // 本地合成 v5（N6/V6）
        // 音色数据包（libxixi_*Package.dat.so）由引擎按 dataPath dlopen，
        // 不需要在此显式 loadLibrary，但文件名保留在 lib/armeabi 即可。
    }

    private static void loadIfExist(String name) {
        try {
            System.loadLibrary(name);
        } catch (Throwable t) {
            android.util.Log.w("HciCloudTts", "loadLibrary " + name + " skip: " + t);
        }
    }

    public HciCloudTts() {
    }

    /**
     * TTS 模块初始化。
     * @param initParam 初始化参数，格式 "capKey=tts.local.synth,dataPath=..."
     * @return 0 成功，非 0 为错误码
     */
    public static native int hciTtsInit(String initParam);

    /**
     * 释放 TTS 模块。
     */
    public static native int hciTtsRelease();

    /**
     * 开启一次合成会话。
     * @param sessionParam 会话参数，格式 "voiceName=xixi,lang=zh_CN,speed=5,volume=8,pitch=5"
     * @param session 会话句柄（native 层回填 sessionId）
     * @return 0 成功
     */
    public static native int hciTtsSessionStart(String sessionParam, Session session);

    /**
     * 结束一次合成会话。
     */
    public static native int hciTtsSessionStop(Session session);

    /**
     * 合成文本。
     * @param session 会话句柄
     * @param text 要合成的文本
     * @param synthParam 合成参数，如 "audioFormat=pcm16k16bit"
     * @param callback 结果回调
     * @return 0 成功
     */
    public static native int hciTtsSynth(Session session, String text, String synthParam,
                                         ITtsSynthCallback callback);
}
