package com.ledi.agent.core;

/** JSON 转义小工具（避免引入外部库） */
public final class J {
    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * 反向：把 JSON 字符串字面量里的转义序列还原成真字符。
     *
     * <p>为什么必须有一个公共实现：这里以前在 {@code field()} 和 {@code jsonStr()}
     * 里各写了一份，而且两份都不处理 {@code \\uXXXX}（只是把反斜杠丢掉，留下
     * "u6d4b" 这样的裸十六进制）。后果是：**合法的** JSON 转义会被弄坏 ——
     * Python 的 {@code json.dumps} 默认 {@code ensure_ascii=True}，中文一律发成
     * {@code \\uXXXX}，于是用它写的任何客户端发中文过来，机器人收到的都是乱码
     * （实测：发"请记住这三个字：红色的苹果"→ 机器人侧拿到 "u8bf7u8bb0..."）。
     * 收方解不了自己协议里的合法转义，属于协议实现错误，不是调用方的问题。</p>
     */
    static String unescape(String raw) {
        if (raw == null || raw.indexOf('\\') < 0) return raw == null ? "" : raw;
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) { sb.append(c); continue; }
            char e = raw.charAt(++i);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u':
                    if (i + 4 < raw.length()) {
                        try {
                            sb.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException ignore) {
                            sb.append('u');
                        }
                    } else {
                        sb.append('u');
                    }
                    break;
                default: sb.append(e);     // \" \\ \/ 都是"反斜杠去掉、字符留下"
            }
        }
        return sb.toString();
    }
}
