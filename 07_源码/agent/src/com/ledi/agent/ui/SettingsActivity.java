package com.ledi.agent.ui;

import com.ledi.agent.cap.AiCore;

import com.ledi.agent.core.AgentService;

import com.ledi.agent.net.LoopbackRelay;
import com.ledi.agent.motion.MotionController;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 机器人控制界面（机器人屏幕上**长按**左上角白色齿轮打开；轻点是进桌宠/AI 主界面）。
 * 可修改：
 *   - 机器人对应的局域网主机服务器 IP（网关地址，立即持久化）
 *     —— 既是 TTS/控制的地址，也是机器人主 App 对话请求的出口
 *     （主 App 端点被固定成 127.0.0.1:18080，由 LoopbackRelay 转发到这里）
 *   - 机器人动作（勾选启用/停用，手机与 PC 端按钮列表随之变化）
 * 另含快捷控制：动作测试 / 灯光 / 表情与屏幕文字 / 音量 / WiFi 配网 / 蓝牙信息 / 齿轮开关。
 */
public class SettingsActivity extends Activity {

    private TextView status;
    private EditText gwIn, ssidIn, passIn, dispIn, motionIn;
    // newcore 重构新增：AI 配置 + 主动说话
    private EditText aiBaseIn, aiKeyIn, aiModelIn;
    private final List<CheckBox> motionChecks = new ArrayList<CheckBox>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(buildUi());
        refreshStatus();
    }

    // ================= UI 工具 =================

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private Button btn(String text, View.OnClickListener l) {
        return Skin.btn(this, text, false, l);
    }

    private TextView tv(String text, float size, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        Skin.t(t, color, size, bold);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    /** 说明文字（原厂浅蓝白，压暗一档）。 */
    private TextView note(String text) { return tv(text, 12, false, Skin.DIM); }

    private EditText input(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(14);
        return Skin.input(this, e);
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private void addRow(LinearLayout parent, View... vs) {
        LinearLayout l = row();
        for (View v : vs) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            p.leftMargin = dp(2);
            p.rightMargin = dp(2);
            l.addView(v, p);
        }
        parent.addView(l);
    }

    /** 分区标题：乐迪黄竖条（与手机端、电脑端同一套视觉）。 */
    private LinearLayout section(String title) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.addView(Skin.sectionTitle(this, title));
        return l;
    }

    // ================= 主界面 =================

    private View buildUi() {
        ScrollView sc = new ScrollView(this);
        // 原厂配色：深空蓝底 + 乐迪黄强调（机器人端界面与手机端、电脑端一致）
        sc.setBackground(Skin.windowBg(this));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(10), 0, dp(10), dp(30));
        sc.addView(root);

        // 标题栏：原厂蓝色面板底图 + 乐迪形象 + 「完成」
        Button done = Skin.btn(this, "完成", true, new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        LinearLayout head = Skin.titleBar(this, "机器人设置", "LediAgent · 机器人端", 30, done);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.leftMargin = -dp(10);
        hp.rightMargin = -dp(10);
        hp.bottomMargin = dp(4);
        root.addView(head, hp);

        status = tv("初始化…", 13, false, Skin.WHITE_ISH);
        root.addView(status);

        // ---------- 服务器（网关） ----------
        LinearLayout s1 = section("AI 服务器（电脑，即网关）");
        s1.addView(note("填这台电脑的局域网地址，例如 http://192.168.1.100:8080"));
        s1.addView(tv("机器人的对话 / 动作 / 语音都经这个地址；改完立即生效",
                11, false, Skin.DIM));
        gwIn = input("http://192.168.1.100:8080");
        if (AgentService.speaker != null) gwIn.setText(AgentService.speaker.getGateway());
        s1.addView(gwIn);
        addRow(s1,
                btn("保存服务器 IP", new View.OnClickListener() {
                    public void onClick(View v) {
                        String g = gwIn.getText().toString().trim();
                        if (AgentService.speaker == null) return;
                        AgentService.speaker.setGateway(g);
                        toast("已保存：" + g);
                        refreshStatus();
                    }
                }),
                btn("测试（说你好）", new View.OnClickListener() {
                    public void onClick(View v) {
                        send("/say", "{\"text\":\"你好，我连上服务器啦\"}");
                    }
                }));
        root.addView(s1);

        // ---------- newcore：AI 大脑（机器人端直连大模型） ----------
        LinearLayout sAI = section("机器人端 AI（直连大模型，可不用电脑）");
        sAI.addView(note("填 OpenAI 兼容接口（DeepSeek/通义/Kimi/Ollama），机器人自己就能对话"));
        sAI.addView(tv("留空 base/key = 回落电脑网关；两者都配 = 优先直连、失败回落",
                11, false, Skin.DIM));
        aiBaseIn = input("https://api.deepseek.com/v1");
        aiKeyIn = input("sk-xxxx（API Key）");
        aiModelIn = input("deepseek-chat");
        if (AgentService.ai != null) {
            aiBaseIn.setText(AgentService.ai.base());
            aiKeyIn.setText(AgentService.ai.key());
            aiModelIn.setText(AgentService.ai.model());
        }
        sAI.addView(aiBaseIn);
        sAI.addView(aiKeyIn);
        sAI.addView(aiModelIn);
        addRow(sAI,
                btn("保存 AI 配置", new View.OnClickListener() {
                    public void onClick(View v) {
                        if (AgentService.ai == null) return;
                        AgentService.ai.setDirect(aiBaseIn.getText().toString(),
                                aiKeyIn.getText().toString(),
                                aiModelIn.getText().toString());
                        AgentService.ai.setEngine(AiCore.ENGINE_AUTO);
                        toast("已保存 AI 配置（auto 模式）");
                    }
                }),
                btn("只走电脑网关", new View.OnClickListener() {
                    public void onClick(View v) {
                        if (AgentService.ai == null) return;
                        AgentService.ai.setEngine(AiCore.ENGINE_GATEWAY);
                        toast("已切换为网关模式");
                    }
                }),
                btn("只走直连", new View.OnClickListener() {
                    public void onClick(View v) {
                        if (AgentService.ai == null) return;
                        AgentService.ai.setEngine(AiCore.ENGINE_DIRECT);
                        toast("已切换为直连模式");
                    }
                }));
        addRow(sAI,
                btn("主动说话：开", new View.OnClickListener() {
                    public void onClick(View v) {
                        if (AgentService.proactiveTalk == null) return;
                        AgentService.proactiveTalk.set(true, null, null);
                        toast("主动说话已开启");
                    }
                }),
                btn("主动说话：关", new View.OnClickListener() {
                    public void onClick(View v) {
                        if (AgentService.proactiveTalk == null) return;
                        AgentService.proactiveTalk.set(false, null, null);
                        toast("主动说话已关闭");
                    }
                }),
                btn("图形化动作编程", new View.OnClickListener() {
                    public void onClick(View v) { openProgrammer(); }
                }));
        root.addView(sAI);

        // ---------- 动作配置 ----------
        LinearLayout s2 = section("机器人动作");
        s2.addView(note("勾选 = 启用（手机/PC 遥控按钮列表随之变化）"));
        LinearLayout grid = null;
        int i = 0;
        List<String> enabled = AgentService.router == null
                ? new ArrayList<String>() : AgentService.router.enabledMotions();
        for (final String m : MotionController.MOTIONS) {
            if (i % 3 == 0) { grid = row(); s2.addView(grid); }
            CheckBox cb = new CheckBox(this);
            cb.setText(m);
            cb.setTextSize(12);
            Skin.check(this, cb, enabled.contains(m));
            cb.setChecked(enabled.contains(m));
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                    saveMotions();
                }
            });
            motionChecks.add(cb);
            grid.addView(cb, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            i++;
        }
        addRow(s2,
                btn("全选", new View.OnClickListener() {
                    public void onClick(View v) { setAllChecks(true); }
                }),
                btn("恢复默认", new View.OnClickListener() {
                    public void onClick(View v) { setAllChecks(false); }
                }),
                btn("保存动作", new View.OnClickListener() {
                    public void onClick(View v) { saveMotions(); toast("动作配置已保存"); }
                }));
        // 动作测试
        motionIn = input("输入动作名测试（如 hug）");
        s2.addView(motionIn);
        addRow(s2,
                btn("做这个动作", new View.OnClickListener() {
                    public void onClick(View v) {
                        send("/motion", "{\"name\":\"" + motionIn.getText().toString().trim() + "\"}");
                    }
                }),
                btn("随机跳舞", new View.OnClickListener() {
                    public void onClick(View v) { send("/dance", "{\"name\":\"\"}"); }
                }),
                btn("停止", new View.OnClickListener() {
                    public void onClick(View v) { send("/stop", "{}"); }
                }));
        root.addView(s2);

        // ---------- 灯光 ----------
        LinearLayout s3 = section("灯光（胸口 LED）");
        addRow(s3,
                btn("红灯开", mkLed("r", "on")),
                btn("红灯关", mkLed("r", "off")),
                btn("绿灯开", mkLed("g", "on")));
        addRow(s3,
                btn("绿灯关", mkLed("g", "off")),
                btn("蓝灯开", mkLed("b", "on")),
                btn("蓝灯关", mkLed("b", "off")));
        addRow(s3,
                btn("黄灯开", mkLed("y", "on")),
                btn("粉灯开", mkLed("p", "on")),
                btn("全部关", mkLed("all", "off")));
        root.addView(s3);

        // ---------- 表情 / 屏幕 ----------
        LinearLayout s4 = section("表情与屏幕显示");
        dispIn = input("要在机器人屏幕上显示的文字");
        s4.addView(dispIn);
        addRow(s4,
                btn("显示文字", new View.OnClickListener() {
                    public void onClick(View v) {
                        String t = dispIn.getText().toString().trim().replace("\"", "'");
                        if (!t.isEmpty()) send("/display", "{\"text\":\"" + t + "\"}");
                    }
                }),
                btn("清除文字", new View.OnClickListener() {
                    public void onClick(View v) { send("/displayoff", "{}"); }
                }),
                btn("关闭表情", new View.OnClickListener() {
                    public void onClick(View v) { send("/faceoff", "{}"); }
                }));
        root.addView(s4);

        // ---------- 音量 ----------
        LinearLayout s5 = section("音量");
        SeekBar vol = new SeekBar(this);
        vol.setMax(15);
        vol.setProgress(8);
        vol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                if (u) send("/volume", "{\"n\":" + p + "}");
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        s5.addView(vol);
        root.addView(s5);

        // ---------- 蓝牙 / WiFi ----------
        LinearLayout s6 = section("蓝牙直连与 WiFi");
        s6.addView(note("手机可与机器人蓝牙配对后直连遥控（无需 WiFi）"));
        ssidIn = input("WiFi 名称 (SSID)");
        passIn = input("WiFi 密码");
        s6.addView(ssidIn);
        s6.addView(passIn);
        s6.addView(btn("连接 WiFi", new View.OnClickListener() {
            public void onClick(View v) {
                final String ssid = ssidIn.getText().toString().trim();
                if (ssid.isEmpty() || AgentService.wifiProvisioner == null) return;
                new Thread(new Runnable() {
                    public void run() {
                        AgentService.wifiProvisioner.connect(ssid,
                                passIn.getText().toString(), null);
                    }
                }).start();
                toast("正在连接 " + ssid);
            }
        }));
        s6.addView(btn("临时隐藏屏幕齿轮（重启会回来）", new View.OnClickListener() {
            public void onClick(View v) { send("/gear", "{\"on\":false}"); }
        }));
        // ★ 齿轮是桌面唯一入口，开机一律强制点亮。所以这里只能"临时"隐藏：
        //   一旦允许永久关掉，用户就可能把屏幕上唯一的入口关没，只能从电脑发 HTTP 救回来。
        s6.addView(btn("显示屏幕上的机器人 IP（默认不显示）", new View.OnClickListener() {
            public void onClick(View v) { send("/gear", "{\"on\":true,\"ip\":true}"); }
        }));
        root.addView(s6);

        // ---- 屏幕日志浮层（调试）------------------------------------------
        // 说一句、看一眼：日志直接铺在屏幕**最上层**，站在机器人旁边调唤醒/识别时
        // 不用再去电脑上拉 adb / 开 HTTP。总开关 + 字号 + 行数都在这儿，不用连电脑。
        LinearLayout s7 = section("屏幕日志 + 调试面板（调试）");
        s7.addView(note("打开后实时日志浮在屏幕最下层偏左。调唤醒/语音时"
                + "站着就能看到「听见没有 / 认出没有 / 卡在哪」。"));
        // ★ v148：日志、触摸反馈、陀螺仪 3D、电机滑条**合并成同一块面板**，由顶部那个
        //   绿色小点统一开关（以前是两块独立浮层，互相压着、底部还会被屏幕截掉）。
        s7.addView(note("同一块面板里还有：触摸反馈、陀螺仪 3D、电机滑条"
                + "（拖滑条会真的驱动电机；右侧数字是原厂读回）。"
                + "点面板标题右边的「收起 ⌄」或顶部小绿点可收起来。"));
        s7.addView(note("等价于 POST /logwin {\"on\":true}，状态见 GET /logwin。"
                + "屏保期间会自动收起（开关保留），醒来自己回来；"
                + "屏保中的状态见 GET /sleep 的 panel_shown / panel_on。"));

        final CheckBox cbLog = new CheckBox(this);
        cbLog.setText("在屏幕最上层显示实时日志与调试面板");
        cbLog.setTextSize(13);
        Skin.check(this, cbLog, LogOverlay.isEnabled());
        cbLog.setChecked(LogOverlay.isEnabled());
        cbLog.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean c) {
                LogOverlay.set(c ? "true" : "false", null, null, null, null);
                toast(c ? "日志面板已开（退回桌面即可看到）" : "日志面板已关");
            }
        });
        s7.addView(cbLog);

        // ★ v149：原来最小只有「字号小 = 9」，用户想再小**在设备上根本点不到**。
        //   补到 6，并把默认那档（8）明确标出来。
        addRow(s7,
                btn("字号 6（最小）", new View.OnClickListener() {
                    public void onClick(View v) { LogOverlay.set(null, null, "6", null, null); }
                }),
                btn("字号 8（默认）", new View.OnClickListener() {
                    public void onClick(View v) { LogOverlay.set(null, null, "8", null, null); }
                }),
                btn("字号 12", new View.OnClickListener() {
                    public void onClick(View v) { LogOverlay.set(null, null, "12", null, null); }
                }),
                btn("字号 15", new View.OnClickListener() {
                    public void onClick(View v) { LogOverlay.set(null, null, "15", null, null); }
                }));
        addRow(s7,
                btn("10 行", new View.OnClickListener() {
                    public void onClick(View v) { LogOverlay.set(null, "10", null, null, null); }
                }),
                btn("18 行（默认）", new View.OnClickListener() {
                    public void onClick(View v) { LogOverlay.set(null, "18", null, null, null); }
                }),
                btn("28 行", new View.OnClickListener() {
                    public void onClick(View v) { LogOverlay.set(null, "28", null, null, null); }
                }));
        root.addView(s7);

        return sc;
    }

    private View.OnClickListener mkLed(final String color, final String action) {
        return new View.OnClickListener() {
            public void onClick(View v) {
                send("/light", "{\"color\":\"" + color + "\",\"action\":\"" + action + "\"}");
            }
        };
    }

    // ================= 逻辑 =================

    /** 直接调用本机指令路由（等价于 HTTP / 蓝牙发来的指令） */
    private String send(String path, String body) {
        if (AgentService.router == null) {
            toast("服务未就绪");
            return "{\"error\":\"not ready\"}";
        }
        String r = AgentService.router.handle("POST", path, "", body);
        refreshStatus();
        return r;
    }

    private void setAllChecks(boolean all) {
        // 「恢复默认」= 清空配置 = 全部动作可用
        for (CheckBox cb : motionChecks) cb.setChecked(all);
        if (!all && AgentService.router != null) {
            getSharedPreferences("ledi_agent", MODE_PRIVATE)
                    .edit().remove("motions_enabled").apply();
            toast("已恢复默认（全部动作可用）");
        }
    }

    private void saveMotions() {
        if (AgentService.router == null) return;
        StringBuilder body = new StringBuilder("{\"enabled\":[");
        boolean first = true;
        for (CheckBox cb : motionChecks) {
            if (cb.isChecked()) {
                if (!first) body.append(",");
                body.append("\"").append(cb.getText().toString()).append("\"");
                first = false;
            }
        }
        body.append("]}");
        AgentService.router.handle("POST", "/motionconfig", "", body.toString());
    }

    private void refreshStatus() {
        status.post(new Runnable() {
            public void run() {
                if (AgentService.router == null) {
                    status.setText("Agent 服务未就绪");
                    return;
                }
                String r = AgentService.router.handle("GET", "/status", "", "");
                status.setText(r);
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** newcore：打开图形化动作编程器（WebView 加载统一内核 programmer.html）。 */
    private void openProgrammer() {
        try {
            Intent i = new Intent(this, ProgrammerActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            toast("打开编程器失败：" + e);
        }
    }
}
