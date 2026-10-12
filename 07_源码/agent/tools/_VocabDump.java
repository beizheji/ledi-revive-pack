// 词表编解码对拍台（离线，PC 上跑，不碰真机）
// 目的：证明 agent 里那份 Vocabulary.java 的 encode/decode 与 Python 复刻、
//       以及线上 assets/WakeUp.bin 三者一致 —— 只靠 Python 自己复刻自己不算证据。
//
// 编译运行：
//   javac -encoding UTF-8 -cp android.jar -d out Vocabulary.java _VocabDump.java
//   java  -cp out;android.jar com.ledi.agent._VocabDump <assets/WakeUp.bin> <输出目录>
//
// 它会：① 读入 assets/WakeUp.bin，用 Vocabulary.decode 解出明文；
//       ② 用 Vocabulary.encode 重新编码，和原文件逐字节比对；
//       ③ 把 base/safe/full 三档写成 bin 到输出目录，供 Python 侧再核。
//
// 注意：Vocabulary 是包级私有，所以本对拍类必须同包（此文件放在 agent/tools/，
//       不在 agent/src/ 下，构建脚本不会把它打进 APK）。
package com.ledi.agent;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

public class _VocabDump {

    public static void main(String[] args) throws Exception {
        String assetPath = args.length > 0 ? args[0] : "agent/assets/WakeUp.bin";
        String outDir = args.length > 1 ? args[1] : "vocab_out";

        byte[] raw = Files.readAllBytes(new File(assetPath).toPath());
        String plain = Vocabulary.decode(raw);
        System.out.println("== Java Vocabulary.decode(" + assetPath + ")");
        System.out.println("   字节数 = " + raw.length);
        System.out.println("   明文   = " + plain);

        byte[] mine = Vocabulary.encode(plain);
        System.out.println("== Java Vocabulary.encode(明文) 对拍原文件");
        System.out.println("   原 " + raw.length + " B / 复现 " + mine.length + " B");
        System.out.println("   逐字节一致 = " + (java.util.Arrays.equals(raw, mine)
                ? "是 ✓（Java 实现正确）" : "★否★"));

        // 三档词表
        String[][] presets = {
                {"base", Vocabulary.BASE},
                {"safe", Vocabulary.preset("safe")},
                {"full", Vocabulary.preset("full")},
        };
        new File(outDir).mkdirs();
        System.out.println("== 生成三档 bin（Java 产出）");
        for (String[] p : presets) {
            byte[] data = Vocabulary.encode(p[1]);
            File f = new File(outDir, "WakeUp." + p[0] + ".bin");
            FileOutputStream os = new FileOutputStream(f);
            os.write(data);
            os.close();
            System.out.println(String.format("   %-5s %4d B  %2d 词  回读=%s",
                    p[0], data.length, Vocabulary.split(p[1]).size(),
                    Vocabulary.decode(data).equals(p[1]) ? "一致✓" : "★不一致★"));
        }

        System.out.println("== encode(\"\") 应当抛异常（对齐 DEX 里 throw new Exception(\"bad input!\")）");
        try {
            Vocabulary.encode("");
            System.out.println("   ★没有抛异常，和 DEX 行为不一致★");
        } catch (Exception e) {
            System.out.println("   抛出了 " + e.getMessage() + " ✓");
        }
    }
}
