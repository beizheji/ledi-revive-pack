package com.sinovoice.hcicloudsdk.common.tts;

/**
 * 灵云 TTS 合成回调接口。与官方一致。
 */
public interface ITtsSynthCallback {
    /**
     * 合成结果回调。
     * @param status 错误码，0 表示成功
     * @param result 合成结果（含 PCM 数据）
     * @return true 表示继续接收后续分段；false 表示终止
     */
    boolean onSynthFinish(int status, TtsSynthResult result);
}
