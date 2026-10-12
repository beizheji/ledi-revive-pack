package com.ledi.agent.motion;

import com.ledi.agent.core.AgentService;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * 姿态控制器 —— 让 AI 生成的任意动作姿态真正被执行。
 *
 * <p>原理（逆向自 xldbasesdk 1.5.10 字节码，逐条可验证）：
 * <pre>
 *   config/a.&lt;clinit&gt;:   config/a.b = "/sdcard/xldBaseSDK/"
 *   SaveRawMotionThread.run: 开机把 assets/motions/* 解包复制到 config/a.b 目录
 *   EngineService.moveInterface(String name):
 *       File f = new File(config.a.b + name);
 *       if (!f.exists()) f = new File(config.a.b + "dance_" + name);
 *       if (!f.exists()) { actionComplete(); return; }
 *       BufferedReader 逐行读 → 取 startsWith("start") 的行
 *       → substring(indexOf("start")+5, indexOf("end")) → split("]")
 *       → replace(",[","") / replace("[","") / replace(" ","") → split(",")
 *       → (时长ms, 关节1, 关节2, 关节3) → angleToAutoInstuction(...) → 12 字节串口帧
 * </pre>
 *
 * <p>结论：动作是<b>明文文件驱动</b>且目录在<b>外部可写存储</b>上，
 * 因此只要往该目录写一个姿态文件，就能让机器人做出任意姿态 —— 不需要破解串口协议、
 * 也不需要修改主 App。
 *
 * <p>文件格式（与内置 80 个动作完全一致）：
 * <pre>
 *   start[500,-60,0,60],[500,60,0,-60]end
 *   即每帧 [时长ms, 关节1, 关节2, 关节3]，帧之间用 "],[" 连接
 * </pre>
 *
 * <p>取值范围（统计全部 80 个内置动作得出）：
 * <pre>
 *   时长 0..13622    关节1 -90..150    关节2 -100..100    关节3 -90..150    帧数 1..320
 * </pre>
 */
public class PoseController {

    /** 原厂动作目录，等于反汇编得到的 config/a.b */
    public static final String DIR = "/sdcard/xldBaseSDK/";
    /** AI 生成姿态统一前缀，避免覆盖内置动作（也避开 SaveRawMotionThread 的解包覆盖） */
    public static final String PREFIX = "ai_";
    public static final int MAX_FRAMES = 320;
    public static final int MAX_MS = 13622;

    private static final int[][] LIMIT = {{0, MAX_MS}, {-90, 150}, {-100, 100}, {-90, 150}};

    private final MotionController motion;

    public PoseController(MotionController motion) {
        this.motion = motion;
    }

    /**
     * 合法化姿态名：只保留字母数字下划线，并强制 ai_ 前缀。
     * 必须做这一步 —— 名字会直接参与 File 路径拼接，不校验会导致路径穿越。
     */
    public static String safeName(String raw) {
        if (raw == null) raw = "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < 24; i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            }
        }
        String n = sb.toString();
        if (n.isEmpty()) n = "pose" + (System.currentTimeMillis() % 100000);
        if (!n.startsWith(PREFIX)) n = PREFIX + n;
        return n;
    }

    /**
     * 把 AI 给的姿态规格规范化成原厂动作文本。
     * 支持三种输入：
     *   1) 完整原厂格式   start[500,-60,0,60],[500,60,0,-60]end
     *   2) 省略 start/end [500,-60,0,60],[500,60,0,-60]
     *   3) 单帧           [500,0,100,0]
     * 非法或越界会抛 IllegalArgumentException，由调用方回给 AI 让它自我修正。
     */
    public static String normalize(String spec) {
        if (spec == null) throw new IllegalArgumentException("pose spec is empty");
        String s = spec.trim().replace("\r", "").replace("\n", "").replace(" ", "");
        if (s.isEmpty()) throw new IllegalArgumentException("pose spec is empty");

        if (s.startsWith("start")) s = s.substring(5);
        if (s.endsWith("end")) s = s.substring(0, s.length() - 3);
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);
        s = s.replace("],[", ";").replace("], [", ";");
        if (s.indexOf(']') >= 0 || s.indexOf('[') >= 0) {
            throw new IllegalArgumentException("bad pose syntax: " + spec);
        }

        String[] frames = s.split(";");
        if (frames.length == 0) throw new IllegalArgumentException("no frames");
        if (frames.length > MAX_FRAMES) {
            throw new IllegalArgumentException("too many frames: " + frames.length + " > " + MAX_FRAMES);
        }

        StringBuilder out = new StringBuilder("start");
        for (int i = 0; i < frames.length; i++) {
            String[] p = frames[i].split(",");
            if (p.length != 4) {
                throw new IllegalArgumentException("frame " + i + " needs 4 values (ms,j1,j2,j3): " + frames[i]);
            }
            int[] v = new int[4];
            for (int k = 0; k < 4; k++) {
                try {
                    v[k] = Integer.parseInt(p[k].trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("frame " + i + " value " + k + " not a number: " + p[k]);
                }
                if (v[k] < LIMIT[k][0] || v[k] > LIMIT[k][1]) {
                    throw new IllegalArgumentException("frame " + i + " value " + k + "=" + v[k]
                            + " out of range [" + LIMIT[k][0] + "," + LIMIT[k][1] + "]");
                }
            }
            if (i > 0) out.append(',');
            out.append('[').append(v[0]).append(',').append(v[1])
               .append(',').append(v[2]).append(',').append(v[3]).append(']');
        }
        return out.append("end").toString();
    }

    /**
     * 写入一个 AI 姿态定义。
     *
     * @param rawName AI 给的名字（会被合法化并加 ai_ 前缀）
     * @param spec    姿态规格（三种写法都接受，见 normalize）
     * @param run     true = 写完立即执行
     * @return 结果描述（含最终动作名，调用方需要把这个名字回给对话链路）
     */
    public String define(String rawName, String spec, boolean run) {
        String name = safeName(rawName);
        String content;
        try {
            content = normalize(spec);
        } catch (IllegalArgumentException e) {
            return "ERROR " + e.getMessage();
        }

        File dir = new File(DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            return "ERROR cannot create " + DIR;
        }
        File f = new File(dir, name);
        try {
            FileOutputStream fos = new FileOutputStream(f);
            OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
            w.write(content);
            w.flush();
            w.close();
            fos.close();
        } catch (Exception e) {
            Log.e(AgentService.TAG, "pose write fail: " + name, e);
            return "ERROR write " + name + ": " + e;
        }

        Log.i(AgentService.TAG, "pose defined: " + name + " -> " + content);
        motion.rememberPose(content);      // 登记当前电机姿态（控制界面/AI 可见）
        if (!run) return "OK " + name;
        if (!motion.hasEngine()) return "OK " + name + " (saved, engine not ready)";
        motion.motion(name);
        return "OK " + name + " started";
    }

    /** 只执行已存在的姿态/动作 */
    public String run(String rawName) {
        String name = safeName(rawName);
        if (!new File(DIR, name).exists()) {
            if (!new File(DIR, rawName == null ? "" : rawName).exists()) {
                return "ERROR not found: " + name;
            }
            name = rawName;
        }
        return motion.motion(name);
    }

    public String remove(String rawName) {
        String name = safeName(rawName);
        File f = new File(DIR, name);
        boolean ok = f.exists() && f.delete();
        return ok ? "OK removed " + name : "ERROR not found: " + name;
    }

    /** 列出目录里 AI 生成过的姿态（含帧数） */
    public List<String> list() {
        List<String> out = new ArrayList<String>();
        File dir = new File(DIR);
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (f.isFile() && f.getName().startsWith(PREFIX)) {
                out.add(f.getName());
            }
        }
        return out;
    }

    /** 读回某个姿态文件内容（调试用） */
    public String read(String rawName) {
        String name = safeName(rawName);
        File f = new File(DIR, name);
        if (!f.exists()) return "";
        try {
            byte[] buf = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            int n = in.read(buf);
            in.close();
            return new String(buf, 0, Math.max(n, 0), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
