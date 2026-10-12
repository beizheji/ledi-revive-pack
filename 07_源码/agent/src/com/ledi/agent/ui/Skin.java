// ！！由 03_打包工具/_apply_skin.py 从 ui_kit/Skin.java 生成，请勿直接编辑本文件。
package com.ledi.agent.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 乐迪统一视觉层（手机 App / 机器人端共用同一份源码）。
 *
 * <h3>这套颜色是怎么来的</h3>
 * 不是拍脑袋调的，是从原厂手机客户端
 * {@code com.auric.intell.rbt.rbtclient/res} 的图标里**按真实像素统计高频色**得到的
 * （统计脚本见 {@code 03_打包工具/_ui_icons.py} 的 {@code palette()}）：
 * <pre>
 *   #385898 面板深蓝   占图标像素 72.4%   → PANEL
 *   #F8E808 乐迪黄     占 38.5%           → YELLOW（高亮/主按钮）
 *   #88C808 连接绿     占 33.6%           → GREEN（连接成功/在线）
 *   #D8E8F8 浅蓝白                        → WHITE_ISH（正文/分隔）
 * </pre>
 * 原厂面板 {@code panel_top.png} 实测主色是 {@code rgb(54,90,150)}，与上面的统计吻合，
 * 所以整套 UI 直接用这个蓝做底、乐迪黄做强调，视觉上就和机器人原厂界面是一家人。
 *
 * <h3>为什么要一个类</h3>
 * 本工程不用 Gradle、aapt2 是 manifest-only 链接（**没有 res/、没有 R 类**），
 * 所以「主题」只能靠代码。把颜色、圆角、按钮、图标加载集中在这里，
 * 两端（{@code com.ledi.controller} 与 {@code com.ledi.agent}）用**同一份源码**，
 * 风格就不会各走各的 —— 这正是需求「手机 app 与电脑 app 要和机器人风格相近」的落点。
 *
 * <h3>图标从哪来</h3>
 * {@code assets/icons/*.png} 是从原厂客户端 res 里原样提取的 19 个自有图
 * （乐迪形象 ledi.png、蓝色面板三件套、连接按钮三态、OK/灰按钮、左右卡片、
 * 返回/下拉、系统图标）。见 {@code _ui_icons.py}。
 *
 * <p><b>本文件是母本，不要直接改某一端的副本</b>：改 {@code 03_打包工具/ui_kit/Skin.java}
 * 然后跑 {@code python _apply_skin.py} 分发到两端。
 */
public final class Skin {

    // ---------------------------------------------------------------- 调色板

    /** 面板主蓝（原厂 panel_top.png 实测 rgb(54,90,150)，高频统计 #385898 同族）。 */
    public static final int PANEL = 0xFF365A96;
    /** 乐迪黄：主按钮、强调、标题高亮。 */
    public static final int YELLOW = 0xFFF8E808;
    /** 连接绿：在线/成功。 */
    public static final int GREEN = 0xFF88C808;
    /** 浅蓝白：正文与分隔线。 */
    public static final int WHITE_ISH = 0xFFD8E8F8;

    /** 页面底色（深空蓝）。 */
    public static final int BG = 0xFF16224A;
    /** 页面底色渐变另一端。 */
    public static final int BG2 = 0xFF1D2C5C;
    /** 卡片/面板填充。 */
    public static final int CARD = 0xFF24376B;
    /** 卡片次级填充（可点元素）。 */
    public static final int CARD2 = 0xFF2C4180;
    /** 描边。 */
    public static final int LINE = 0xFF3D569B;

    /** 正文字色。 */
    public static final int TX = 0xFFEEF3FF;
    /** 次要字色。 */
    public static final int DIM = 0xFFA8BDE8;
    /** 警示。 */
    public static final int WARN = 0xFFFFB454;
    /** 错误。 */
    public static final int ERR = 0xFFFF6B6B;
    /** 成功。 */
    public static final int OK = 0xFF8ECF2A;
    /** 黄底上的深字（保证对比度）。 */
    public static final int INK = 0xFF22315A;

    private Skin() {}

    // ---------------------------------------------------------------- 尺寸

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static float sp(Context c, float v) {
        return v;
    }

    // ---------------------------------------------------------------- 形状

    /**
     * 圆角矩形。
     *
     * @param fill     填充色
     * @param stroke   描边色（0 = 不画）
     * @param strokeDp 描边粗细（dp）
     * @param radiusDp 圆角（dp）
     */
    public static GradientDrawable rounded(Context c, int fill, int stroke,
                                           float strokeDp, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0 && strokeDp > 0) {
            g.setStroke(Math.max(1, dp(c, strokeDp)), stroke);
        }
        return g;
    }

    /** 页面底：深空蓝对角渐变（模拟原厂蓝色太空氛围）。 */
    public static Drawable windowBg(Context c) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{BG2, BG, 0xFF101A3C});
        return g;
    }

    /** 卡片底。 */
    public static Drawable card(Context c) { return rounded(c, CARD, LINE, 1, 12); }

    /** 可点卡片底（比卡片稍亮）。 */
    public static Drawable cardHi(Context c) { return rounded(c, CARD2, LINE, 1, 10); }

    /** 输入框底。 */
    public static Drawable inputBg(Context c) { return rounded(c, 0xFF1B2A55, LINE, 1, 8); }

    /** 徽标/小胶囊底。 */
    public static Drawable chip(Context c) { return rounded(c, CARD2, 0, 0, 999); }

    /** 1px 分隔线（颜色取自原厂 panel_mid 的白，压暗一点以免刺眼）。 */
    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(0x55D8E8F8);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.6f))));
        return v;
    }

    // ---------------------------------------------------------------- 按钮

    /**
     * 皮肤化按钮。
     *
     * @param primary true = 乐迪黄实心（主操作/确认），false = 面板蓝描边（次操作）
     */
    public static Button btn(Context c, String text, boolean primary,
                             View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(primary ? 15 : 14);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? INK : TX);
        b.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        if (primary) {
            b.setBackground(rounded(c, YELLOW, 0xFFB9AC06, 1, 10));
        } else {
            b.setBackground(rounded(c, CARD2, LINE, 1, 10));
        }
        if (l != null) b.setOnClickListener(l);
        return b;
    }

    /** 把一个已存在的按钮刷成皮肤（用于改造老代码，不改变原有 onClick）。 */
    public static Button skin(Context c, Button b, boolean primary) {
        if (b == null) return null;
        b.setAllCaps(false);
        b.setTextColor(primary ? INK : TX);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        b.setBackground(primary ? rounded(c, YELLOW, 0xFFB9AC06, 1, 10)
                                : rounded(c, CARD2, LINE, 1, 10));
        return b;
    }

    public static EditText input(Context c, EditText e) {
        if (e == null) return null;
        e.setTextColor(TX);
        e.setHintTextColor(0x88A8BDE8);
        e.setBackground(inputBg(c));
        e.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8));
        return e;
    }

    public static CheckBox check(Context c, CheckBox cb, boolean on) {
        if (cb == null) return null;
        cb.setTextColor(TX);
        cb.setButtonTintList(ColorStateList.valueOf(on ? YELLOW : LINE));
        return cb;
    }

    /** 文字：常规。 */
    public static TextView t(TextView v, int color, float size, boolean bold) {
        if (v == null) return null;
        v.setTextColor(color);
        v.setTextSize(size);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    /** 段落标题：左侧一条乐迪黄竖条 + 标题（原厂风格的分区标题）。 */
    public static LinearLayout sectionTitle(Context c, String title) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 12), 0, dp(c, 6));

        View bar = new View(c);
        bar.setBackground(rounded(c, YELLOW, 0, 0, 2));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                dp(c, 3), dp(c, 16));
        bp.rightMargin = dp(c, 8);
        row.addView(bar, bp);

        TextView tv = new TextView(c);
        tv.setText(title);
        tv.setTextColor(YELLOW);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(tv);
        return row;
    }

    /**
     * 顶部标题栏：原厂蓝色面板 {@code panel_top.png} 拉伸做底 + 乐迪形象 + 标题。
     *
     * <p>用 BitmapDrawable + {@code Gravity.FILL} 而不是固定尺寸，
     * 这样同一条标题栏在手机（360dp 宽）和机器人屏（480×272 横屏）上都不会错位。
     *
     * @param avatarSizeDp 头像边长；&lt;=0 表示不要头像
     * @param rightExtra   追加到标题行右侧的 View（可为 null）
     */
    public static LinearLayout titleBar(Context c, String title, String sub,
                                        int avatarSizeDp, View rightExtra) {
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 8));
        Drawable bg = panelTop(c);
        if (bg != null) head.setBackground(bg);
        else head.setBackground(rounded(c, PANEL, 0, 0, 10));

        if (avatarSizeDp > 0) {
            ImageView av = iconView(c, "ledi.png", avatarSizeDp);
            if (av != null) {
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                        dp(c, avatarSizeDp), dp(c, avatarSizeDp));
                ap.rightMargin = dp(c, 8);
                head.addView(av, ap);
            }
        }

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(c);
        t1.setText(title);
        t1.setTextColor(Color.WHITE);
        t1.setTextSize(17);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t1);
        if (sub != null && !sub.isEmpty()) {
            TextView t2 = new TextView(c);
            t2.setText(sub);
            t2.setTextColor(WHITE_ISH);
            t2.setTextSize(12);
            col.addView(t2);
        }
        head.addView(col, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (rightExtra != null) head.addView(rightExtra);
        return head;
    }

    // ---------------------------------------------------------------- 图标

    private static final Map<String, Bitmap> BM = new HashMap<String, Bitmap>();

    /** 读 {@code assets/icons/<name>} 的位图（带缓存；失败返回 null 而不是抛）。 */
    public static Bitmap icon(Context c, String name) {
        Bitmap hit = BM.get(name);
        if (hit != null) return hit;
        try {
            InputStream in = c.getAssets().open("icons/" + name);
            Bitmap b = BitmapFactory.decodeStream(in);
            in.close();
            if (b != null) BM.put(name, b);
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 图标做成 ImageView（等比缩放，不裁切）。 */
    public static ImageView iconView(Context c, String name, int sizeDp) {
        Bitmap b = icon(c, name);
        if (b == null) return null;
        ImageView iv = new ImageView(c);
        iv.setImageBitmap(b);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                dp(c, sizeDp), dp(c, sizeDp));
        iv.setLayoutParams(p);
        return iv;
    }

    /** 图标当"图片按钮"用：ImageButton 没皮肤，用 ImageView + 背景 + 点击。 */
    public static ImageView iconButton(Context c, String name, int sizeDp,
                                       boolean primary, View.OnClickListener l) {
        ImageView iv = iconView(c, name, sizeDp);
        if (iv == null) return null;
        int pad = dp(c, primary ? 2 : 4);
        iv.setPadding(pad, pad, pad, pad);
        iv.setBackground(primary ? rounded(c, YELLOW, 0xFFB9AC06, 1, 10)
                                 : rounded(c, CARD2, LINE, 1, 10));
        if (l != null) iv.setOnClickListener(l);
        return iv;
    }

    // ---------------------------------------------------------------- 原厂面板做底

    private static Drawable panelTopCache;

    /**
     * 原厂面板顶盖（480×133）做底图，拉伸铺满。
     * 主色 rgb(54,90,150)，正好是本套配色的 PANEL。
     */
    public static Drawable panelTop(Context c) {
        if (panelTopCache != null) return panelTopCache;
        Bitmap b = icon(c, "panel_top.png");
        if (b == null) return null;
        BitmapDrawable d = new BitmapDrawable(c.getResources(), b);
        d.setGravity(Gravity.FILL);
        panelTopCache = d;
        return d;
    }

    /** 原厂对话框底（480×272 全屏级背景），给弹窗式页面用。 */
    public static Drawable dialogBg(Context c) {
        Bitmap b = icon(c, "dialog_bg.png");
        if (b == null) return rounded(c, PANEL, 0, 0, 0);
        BitmapDrawable d = new BitmapDrawable(c.getResources(), b);
        d.setGravity(Gravity.FILL);
        return d;
    }
}
