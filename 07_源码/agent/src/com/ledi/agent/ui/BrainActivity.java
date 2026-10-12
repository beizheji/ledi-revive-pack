package com.ledi.agent.ui;

import com.ledi.agent.core.AgentService;
import com.ledi.agent.cap.AiCore;
import com.ledi.agent.cap.ProactiveTalk;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * BrainActivity —— 机器人 AI 主界面（newcore，launcher / HOME）。
 *
 * 需求⑨「彻底替换原厂 App、完全由 AI 接管」：本 Activity 注册为 HOME/DEFAULT launcher，
 * 装上后用 {@link HomeSetter} 把自己设成默认桌面，开机直接进这个 AI 界面，
 * 原厂 FaceActivity 不再作为 launcher 出现。
 *
 * 界面内容（全屏，按机器人屏 480×272 横屏排版）：
 *   - 顶部：原厂蓝色面板标题栏 + 乐迪形象（ledi.png）+ 状态条（AI 引擎 / 连接 / 主动说话）
 *   - 中间：左侧乐迪形象大图 + 右侧当前 AI 回复
 *   - 底部：三个入口（设置 / 动作编程 / 说话）
 *
 * <p>视觉与手机端、电脑端共用一套：配色从原厂客户端图标像素统计而来，
 * 见 {@link Skin}。乐迪形象用的是原厂客户端自带的 {@code ledi.png}。
 *
 * <p>注意：本 Activity 是「视觉上的 AI 界面」，真正的 AI 决策在 AgentService 的
 * AiCore + ProactiveTalk 里跑。这里只负责把状态展示出来，并提供手动入口。
 */
public class BrainActivity extends Activity {

    private TextView aiStatus, aiReply;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Skin.windowBg(this));
        root.setPadding(dp(10), 0, dp(10), dp(10));

        // ---------- 顶部标题栏（原厂蓝色面板底图 + 乐迪形象） ----------
        LinearLayout head = Skin.titleBar(this, "乐迪", "AI 大脑 · 机器人端", 30, null);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.leftMargin = -dp(10);
        hp.rightMargin = -dp(10);
        root.addView(head, hp);

        // 状态条：贴在标题栏下面的一条窄带
        aiStatus = new TextView(this);
        aiStatus.setText("状态加载中…");
        Skin.t(aiStatus, Skin.WHITE_ISH, 12, false);
        aiStatus.setGravity(Gravity.CENTER_VERTICAL);
        aiStatus.setPadding(dp(4), dp(4), dp(4), dp(4));
        root.addView(aiStatus);

        // ---------- 中间：左乐迪形象 + 右 AI 回复 ----------
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.HORIZONTAL);
        mid.setGravity(Gravity.CENTER_VERTICAL);
        mid.setBackground(Skin.card(this));
        mid.setPadding(dp(10), dp(8), dp(10), dp(8));

        ImageView face = Skin.iconView(this, "ledi.png", 84);
        if (face != null) {
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(dp(84), dp(84));
            fp.rightMargin = dp(10);
            mid.addView(face, fp);
        }

        aiReply = new TextView(this);
        aiReply.setText("（AI 尚未说话，摸摸我的头试试）");
        Skin.t(aiReply, Skin.TX, 16, false);
        aiReply.setLineSpacing(dp(3), 1.15f);
        mid.addView(aiReply, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        root.addView(mid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---------- 底部入口 ----------
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(6), 0, 0);

        row.addView(btn("设置", new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new android.content.Intent(BrainActivity.this, SettingsActivity.class));
            }
        }), weight());
        row.addView(btn("动作编程", new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new android.content.Intent(BrainActivity.this, ProgrammerActivity.class));
            }
        }), weight());
        row.addView(btn("说句话", new View.OnClickListener() {
            public void onClick(View v) { manualTalk(); }
        }), weight());
        root.addView(row);

        return root;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.leftMargin = dp(3);
        p.rightMargin = dp(3);
        return p;
    }

    private Button btn(String t, View.OnClickListener l) {
        return Skin.btn(this, t, false, l);
    }

    /** 手动触发一次 AI 主动说话 */
    private void manualTalk() {
        if (AgentService.ai == null) {
            aiReply.setText("AI 未就绪");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                String r = AgentService.ai.ask("", "主人按了「说句话」按钮");
                if (r != null && !r.isEmpty()) {
                    AgentService.router.handle("POST", "/say",
                            "", "{\"text\":\"" + r.replace("\"", "'") + "\"}");
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() { refresh(); }
                });
            }
        }).start();
    }

    private void refresh() {
        if (aiStatus == null) return;
        String eng = AgentService.ai == null ? "未就绪" : AgentService.ai.engine();
        String direct = AgentService.ai != null && AgentService.ai.directReady()
                ? "直连可用" : "直连未配";
        String p = AgentService.proactiveTalk != null && AgentService.proactiveTalk.enabled()
                ? "主动说话开" : "主动说话关";
        String home = HomeSetter.isTarget(this) ? "桌面=机器人App"
                : (HomeSetter.isUs(this) ? "桌面=本应用(兜底)" : "桌面未接管");
        aiStatus.setText("AI引擎=" + eng + " · " + direct + " · " + p + " · " + home);
        if (AgentService.ai != null && !AgentService.ai.lastReply().isEmpty()) {
            aiReply.setText(AgentService.ai.lastReply());
        }
    }
}
