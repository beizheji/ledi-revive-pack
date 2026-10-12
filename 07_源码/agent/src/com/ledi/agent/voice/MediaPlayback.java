package com.ledi.agent.voice;

import com.ledi.agent.core.AgentService;

import com.ledi.agent.sdk.FaceController;

import com.ledi.agent.ui.DisplayController;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.Callable;

/**
 * MediaPlayback —— 机器人「播放媒体」统一入口（newcore 重构）。
 *
 * 需求：直接让机器人播放音频 / 视频 / 图片 / 指定文字。
 *   - 音频 mp3/wav  → MediaPlayer 播放（音乐流）
 *   - 视频 mp4      → 全屏悬浮窗播放（表情视频同款，但可放任意视频）
 *   - 图片 png/jpg  → 屏幕显示（复用 DisplayController）
 *   - 指定文字      → 机器人自带语音合成（复用 TtsController）
 *
 * 数据来源两种：
 *   ① 电脑端上传（base64 经 HTTP /media 送来，落盘后播放）
 *   ② 机器人本机已有文件（/sdcard 或缓存目录，直接按路径播放）
 */
public class MediaPlayback {

    private static final String TAG = AgentService.TAG;

    private final Context ctx;
    private final DisplayController display;
    private final TtsController tts;
    private final FaceController face;

    private volatile MediaPlayer audioPlayer;
    private volatile MediaPlayer videoPlayer;

    public MediaPlayback(Context ctx, DisplayController display,
                         TtsController tts, FaceController face) {
        this.ctx = ctx;
        this.display = display;
        this.tts = tts;
        this.face = face;
    }

    // ================= 音频 =================

    /** 播放音频文件（本地路径）。 */
    public String playAudio(final String path) {
        if (path == null || path.isEmpty()) return "path required";
        final File f = new File(path);
        if (!f.exists()) return "file not found: " + path;
        try {
            stopAudio();
            audioPlayer = new MediaPlayer();
            audioPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
            audioPlayer.setDataSource(f.getAbsolutePath());
            audioPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) { stopAudio(); }
            });
            audioPlayer.prepare();
            audioPlayer.start();
            return "playing audio: " + f.getName();
        } catch (Exception e) {
            Log.e(TAG, "audio play fail", e);
            return "audio error: " + e;
        }
    }

    /** 播放音频（base64 字节，落盘到缓存后播放）。 */
    public String playAudioBytes(String b64, String name) {
        try {
            byte[] bin = Base64.decode(b64, Base64.DEFAULT);
            File f = writeCache(name, bin);
            return playAudio(f.getAbsolutePath());
        } catch (Exception e) {
            return "audio decode fail: " + e;
        }
    }

    public void stopAudio() {
        try { if (audioPlayer != null) { audioPlayer.release(); audioPlayer = null; } }
        catch (Exception ignored) {}
    }

    // ================= 视频 =================

    /** 播放视频文件（全屏悬浮窗，复用 FaceController 的浮层播放机制）。 */
    public String playVideo(final String path) {
        if (path == null || path.isEmpty()) return "path required";
        final File f = new File(path);
        if (!f.exists()) return "file not found: " + path;
        // 复用 FaceController 的通用视频播放（它支持任意 mp4，不只是表情）
        return face.playFile(f);
    }

    /** 播放视频（base64 字节）。 */
    public String playVideoBytes(String b64, String name) {
        try {
            byte[] bin = Base64.decode(b64, Base64.DEFAULT);
            File f = writeCache(name, bin);
            return playVideo(f.getAbsolutePath());
        } catch (Exception e) {
            return "video decode fail: " + e;
        }
    }

    // ================= 图片 / 文字 =================

    public String showImage(String b64, int maxSide, String mode) {
        return display.showImage64(b64, maxSide, mode);
    }

    public String showText(String text) {
        return display.show(text);
    }

    public String say(String text) {
        String eng = tts.speak(text, true);
        if (eng == null || eng.isEmpty()) return "本地语音合成不可用";
        return "said via " + eng + ": " + text;
    }

    // ================= 工具 =================

    private File writeCache(String name, byte[] data) throws Exception {
        if (name == null || name.isEmpty()) name = "media_" + System.currentTimeMillis();
        // 防路径穿越
        String safe = name.replaceAll("[^a-zA-Z0-9._-]", "_");
        File f = new File(ctx.getCacheDir(), safe);
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(data);
        fo.flush();
        fo.close();
        return f;
    }
}
