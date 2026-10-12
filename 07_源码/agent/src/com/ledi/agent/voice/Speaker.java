package com.ledi.agent.voice;

import com.ledi.agent.core.AgentService;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 远程语音：从网关下载 TTS 音频（/tts.mp3?text=）并用音乐流播放。
 */
public class Speaker {

    private static final String PREFS = "ledi_agent";
    private static final String KEY = "gateway";

    private final Context ctx;
    private volatile String gatewayBase = "";   // 例如 http://192.168.1.100:8080
    private volatile MediaPlayer player;

    public Speaker(Context ctx) {
        this.ctx = ctx;
        // 网关地址持久化：重启/开机后仍然生效（手机/PC 设置一次即可）
        try {
            gatewayBase = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, "");
        } catch (Exception ignored) {}
    }

    public void setGateway(String base) {
        gatewayBase = base == null ? "" : base.trim();
        try {
            // 用 commit() 而不是 apply()：这是「换电脑只改一个地址」唯一依赖的持久化设置，
            // apply() 是异步落盘，紧接着 force-stop（SIGKILL）会把还没刷盘的写入丢掉
            // —— 真机联调时踩过（设完地址、调试中反复重启，地址变回空）。
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, gatewayBase).commit();
        } catch (Exception ignored) {}
    }

    public String getGateway() { return gatewayBase; }

    /** text 走网关 TTS；url 直接指定音频地址。二选一。 */
    public String speak(final String text, final String url) {
        final String audio = url != null && !url.isEmpty() ? url
                : gatewayBase + "/tts.mp3?text=" + urlencode(text);
        if (!audio.startsWith("http")) return "no gateway configured";
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File f = new File(ctx.getCacheDir(), "tts_last.mp3");
                    HttpURLConnection c = (HttpURLConnection) new URL(audio).openConnection();
                    c.setConnectTimeout(4000);
                    c.setReadTimeout(15000);
                    if (c.getResponseCode() != 200) {
                        Log.e(AgentService.TAG, "tts http " + c.getResponseCode());
                        return;
                    }
                    InputStream in = c.getInputStream();
                    FileOutputStream fo = new FileOutputStream(f);
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                    fo.close(); in.close();
                    play(f);
                } catch (Exception e) {
                    Log.e(AgentService.TAG, "tts fail", e);
                }
            }
        }).start();
        return "speaking: " + text;
    }

    private void play(final File f) {
        try {
            stopPlayer();
            player = new MediaPlayer();
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            player.setDataSource(f.getAbsolutePath());
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override public void onCompletion(MediaPlayer mp) { stopPlayer(); }
            });
            player.prepare();
            player.start();
        } catch (Exception e) {
            Log.e(AgentService.TAG, "play fail", e);
        }
    }

    private void stopPlayer() {
        try { if (player != null) { player.release(); player = null; } } catch (Exception ignored) {}
    }

    static String urlencode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }
}
