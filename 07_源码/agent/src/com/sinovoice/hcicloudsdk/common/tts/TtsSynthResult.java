package com.sinovoice.hcicloudsdk.common.tts;

/**
 * 灵云 TTS 合成结果。与官方结构一致：
 *   byte[] voiceData       —— PCM 音频数据（16k/16bit 单声道）
 *   String currentSynthText —— 当前合成的文本
 *   boolean hasMoreData    —— 是否还有后续分段（流式合成）
 *   ArrayList ttsSynthMark —— 合成标记（字/词时间戳）
 */
public class TtsSynthResult {
    private byte[] a;               // voiceData
    private String b;               // currentSynthText
    private boolean c;              // hasMoreData
    private java.util.ArrayList d;  // ttsSynthMark

    public TtsSynthResult() {
        this.c = false;
    }

    public byte[] getVoiceData() { return a; }
    public void setVoiceData(byte[] data) { this.a = data; }

    public String getCurrentSynthText() { return b; }
    public void setCurrentSynthText(String t) { this.b = t; }

    public boolean isHasMoreData() { return c; }
    public void setHasMoreData(boolean more) { this.c = more; }

    public java.util.ArrayList getTtsSynthMark() { return d; }
    public void setTtsSynthMark(java.util.ArrayList marks) { this.d = marks; }
}
