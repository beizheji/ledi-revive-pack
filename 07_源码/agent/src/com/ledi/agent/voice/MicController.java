package com.ledi.agent.voice;

import com.ledi.agent.core.AgentService;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 机器人麦克风：MediaRecorder 录音，落 App 私有目录。
 * HTTP GET /micfile?name=xx.amr 取回音频（PC 图形化控制台里点播试听）。
 * 蓝牙通道不传音频文件（带宽小），只传指令。
 */
public class MicController {

    private final Context ctx;
    private MediaRecorder recorder;
    private volatile File current;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public MicController(Context ctx) { this.ctx = ctx; }

    public synchronized String start(int seconds) {
        try {
            if (recorder != null) stop();
            final File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return "no storage";
            String name = "mic_" + new SimpleDateFormat(
                    "MMdd_HHmmss", Locale.US).format(new Date()) + ".amr";
            File out = new File(dir, name);
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.setAudioSamplingRate(8000);
            recorder.setOutputFile(out.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            current = out;
            if (seconds > 0 && seconds <= 300) {
                handler.postDelayed(new Runnable() {
                    @Override public void run() { stop(); }
                }, seconds * 1000L);
            }
            Log.i(AgentService.TAG, "mic recording " + name + " (" + seconds + "s)");
            return "recording: " + name + " (" + seconds + "s max)";
        } catch (Exception e) {
            Log.e(AgentService.TAG, "mic start fail", e);
            release();
            return "mic error: " + e;
        }
    }

    public synchronized String stop() {
        try {
            if (recorder == null) return "not recording";
            recorder.stop();
            String name = current == null ? "" : current.getName();
            release();
            return "saved: " + name;
        } catch (Exception e) {
            release();
            return "stop error: " + e;
        }
    }

    private void release() {
        try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
        recorder = null;
    }

    public boolean isRecording() { return recorder != null; }

    /** 录音文件名列表（新→旧） */
    public List<String> list() {
        List<String> out = new ArrayList<String>();
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return out;
            File[] fs = dir.listFiles();
            if (fs == null) return out;
            Arrays.sort(fs, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return Long.valueOf(b.lastModified()).compareTo(a.lastModified());
                }
            });
            for (File f : fs) if (f.getName().endsWith(".amr")) out.add(f.getName());
        } catch (Exception ignored) {}
        return out;
    }

    public File file(String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")) return null;
        if (!name.endsWith(".amr")) return null;
        File f = new File(ctx.getExternalFilesDir(null), name);
        return f.exists() ? f : null;
    }
}
