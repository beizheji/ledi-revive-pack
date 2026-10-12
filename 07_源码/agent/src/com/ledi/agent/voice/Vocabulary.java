package com.ledi.agent.voice;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 唤醒词表工具 —— 严格复刻百度 {@code com.baidu.speech.WakeUpTools} 的编解码。
 *
 * <h3>为什么能自己造词表</h3>
 * 反汇编 {@code EventManagerWp.doWpStart} 得到的链路是：
 * <pre>
 *   byte[] raw = context.loadBytesFromUri("assets:///WakeUp.bin");
 *   String words = WakeUpTools.decodeWakeUpString(raw);          // 只取第一行
 *   mUsingWords.clear();
 *   for (String w : words.split("[\\s,\n]")) {                   // 运行时切词
 *       if (w.length() > 0) { sb.append(w).append('\t'); mUsingWords.add(w); }
 *   }
 *   easrNativeJni.WakeUpInitial(sb.toString().trim(), resFile, 3);   // 同一份词表喂 native
 * </pre>
 * 也就是说 <b>词表是运行时从那个文件里读出来的</b>，Java 侧命中判定用
 * {@code mUsingWords}（{@code s.contains(w)}），native 侧初始化用的是同一个串。
 * 所以扩词 = 换掉 {@code assets/WakeUp.bin}，不需要改任何 SDK 逻辑。
 *
 * <h3>文件格式（反汇编 WakeUpTools 得到，已逐字节复现验证）</h3>
 * <pre>
 *   swap(b)   : out[i] = (byte)(b[i] ^ 0xFF)                      逐字节按位取反
 *   encode(s) : if (s == null || s.length() == 0) throw "bad input!"
 *               StringBuilder sb;
 *               while (sb.length() &lt; 512) { sb.append(s); sb.append("\r\n"); }
 *               return swap(sb.toString().getBytes("UTF-8"));
 *   decode(b) : swap 之后按 UTF-8 解，再取第一行
 * </pre>
 * 实测原始 {@code WakeUp.bin}（1280 字节）解开就是 {@code 哈喽乐迪,乐迪你好,哈喽热地}
 * 按 32 遍 CRLF 填到 512 字符 —— 本类 {@link #encode} 能对它逐字节复现。
 *
 * <h3>注意</h3>
 * 词表必须写在<b>一行</b>里（decode 只取第一行），词之间用逗号或空白分隔。
 * 词表长度不受 512 限制（那只是填充下限），但太长会让解码网络变大、变慢、误醒变多。
 */
public final class Vocabulary {

    /** 原厂/现网在用的基础词表（保留，确保「哈喽乐迪」这套现有行为不退化）。 */
    static final String BASE = "哈喽乐迪,乐迪你好,哈喽热地";

    /**
     * 用户要的「你好 / 乐迪 / 超级飞侠」+ 同音近音变体。
     *
     * <p>KWS 是按发音建模的，同一个音可以有多种写法，所以「同音字近音字」的做法
     * 就是把这些写法<b>都列进词表</b>——原厂把「哈喽乐迪」写成「哈喽热地」就是这个道理
     * （le→re 的近音变体）。</p>
     */
    static final String USER_WORDS =
            "超级飞侠,超级飞狭,超级飞霞,超级飞下,超极飞侠,超级飞夏,"
          + "你好,尼好,泥好,拟好,你号,你嚎,"
          + "乐迪,勒迪,乐帝,勒帝,乐蒂,热迪";

    /** 宽松档 = 基础 + 用户要的全量（含短词，误醒概率高，但你说要）。 */
    static final String PRESET_FULL = BASE + "," + USER_WORDS;

    /**
     * 保守档 = 基础 + 只保留 4 字词。
     * 短词（你好 / 乐迪）在连续监听下极易被日常对话蹭醒，实测前建议先跑这档。
     */
    static final String PRESET_SAFE =
            BASE + ",超级飞侠,超级飞狭,超级飞霞,超级飞下,超极飞侠,超级飞夏";

    /** 词表挑档：full（默认，你要的全量）/ safe（只 4 字词）。 */
    static String preset(String name) {
        if ("safe".equalsIgnoreCase(name)) return PRESET_SAFE;
        if ("base".equalsIgnoreCase(name)) return BASE;
        return PRESET_FULL;
    }

    private Vocabulary() {}

    /** 逐字节按位取反 —— WakeUpTools.swap。 */
    static byte[] swap(byte[] b) {
        byte[] o = new byte[b.length];
        for (int i = 0; i < b.length; i++) o[i] = (byte) (b[i] ^ 0xFF);
        return o;
    }

    /** WakeUpTools.encodeWakeUpString(String)[B —— 生成可直接当 kws-file 用的字节。 */
    static byte[] encode(String words) throws Exception {
        if (words == null || words.length() == 0) throw new Exception("bad input!");
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 512) {
            sb.append(words);
            sb.append("\r\n");
        }
        return swap(sb.toString().getBytes("UTF-8"));
    }

    /** WakeUpTools.decodeWakeUpString([B)String —— 只取第一行。 */
    static String decode(byte[] raw) {
        String s = new String(swap(raw), java.nio.charset.Charset.forName("UTF-8"));
        int i = s.indexOf('\n');
        if (i >= 0) s = s.substring(0, i);
        return s.replace("\r", "").trim();
    }

    /** 把词表串切成一个个词（与 doWpStart 的 split("[\\s,\n]") 同规则）。 */
    static List<String> split(String words) {
        List<String> out = new ArrayList<String>();
        if (words == null) return out;
        for (String t : words.split("[\\s,\n]")) {
            if (t.length() > 0) out.add(t);
        }
        return out;
    }

    /** 生成词表文件并返回文件对象；供「外部词表覆盖」用。 */
    static File writeTo(File dst, String words) throws Exception {
        byte[] data = encode(words);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            out.write(data);
        } finally {
            out.close();
        }
        return dst;
    }

    /** 读 assets 里的词表（不解码时用，主要用于自检）。 */
    static String readAssetWords(android.content.Context ctx, String assetName) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(assetName);
            byte[] buf = new byte[8192];
            int n, total = 0;
            byte[] all = new byte[8192];
            while ((n = in.read(buf)) > 0) {
                if (total + n > all.length) {
                    byte[] bigger = new byte[(total + n) * 2];
                    System.arraycopy(all, 0, bigger, 0, total);
                    all = bigger;
                }
                System.arraycopy(buf, 0, all, total, n);
                total += n;
            }
            byte[] raw = new byte[total];
            System.arraycopy(all, 0, raw, 0, total);
            return decode(raw);
        } catch (Throwable t) {
            return "";
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }
}
