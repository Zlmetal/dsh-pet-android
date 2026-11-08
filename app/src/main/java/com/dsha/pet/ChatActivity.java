package com.dsha.pet;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 安卓端自带的对话界面：直接打 dsh-pet 的 /chat 接口，不依赖 DSHA 客户端。
 *
 * 契约（见 dsh-pet 的 handleChatRoutes）：
 *   GET  {base}/chat?pet=main&surface=any&rounds=N → {ok:true, messages:[{role,content,ts}], rounds}
 *   POST {base}/chat?pet=main&surface=any  body {"text":"..."} → {ok:true, text, image?}
 * 业务失败也是 HTTP 200，body 里 ok=false，所以必须看 ok 字段。
 * 生成是同步的（实测 2–3 秒，模型慢时更久），读超时给到 90 秒。
 */
public class ChatActivity extends Activity {
    /** 只跟主宠对话。 */
    private static final String PET = "main";
    /** 安卓端自报 surface=any，配置里 display 为 web/desktop/both 都能过。 */
    private static final String SURFACE = "any";
    private static final int HISTORY_ROUNDS = 20;
    private static final int READ_TIMEOUT_MS = 90000;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout feed;
    private ScrollView scroll;
    private EditText input;
    private TextView sendBtn;
    private Typeface cute;
    private boolean sending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DshHttp.init(this);
        try {
            cute = Typeface.create(
                    Typeface.createFromAsset(getAssets(), "font/cute.ttf"), Typeface.BOLD);
        } catch (Exception ignored) {
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFFFF2F7, 0xFFEDEFFF, 0xFFF7F4FF});
        root.setBackground(bg);
        root.setPadding(0, dp(34), 0, 0);
        setContentView(root);

        root.addView(buildBar());

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, slp);

        feed = new LinearLayout(this);
        feed.setOrientation(LinearLayout.VERTICAL);
        feed.setPadding(dp(16), dp(10), dp(16), dp(16));
        scroll.addView(feed, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(buildInput());

        loadHistory();
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    private View buildBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(18), dp(10), dp(12), dp(12));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("大肥鱼");
        title.setTextSize(19);
        title.setTextColor(0xFF3B3A5C);
        if (cute != null) title.setTypeface(cute);
        texts.addView(title);

        TextView sub = new TextView(this);
        sub.setText(PET + " · 直连 DSH 桌宠插件");
        sub.setTextSize(11);
        sub.setTextColor(0xFF9A97B8);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(3);
        texts.addView(sub, subLp);
        bar.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView close = new TextView(this);
        close.setText("关闭");
        close.setTextSize(13);
        close.setTextColor(0xFF7B6BB5);
        close.setPadding(dp(12), dp(8), dp(6), dp(8));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        bar.addView(close);
        return bar;
    }

    private View buildInput() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.BOTTOM);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        box.setBackground(bg);
        box.setPadding(dp(14), dp(10), dp(14), dp(14));
        box.setElevation(dp(2));

        input = new EditText(this);
        input.setHint("跟本鱼说点什么…");
        input.setHintTextColor(0xFFB4B0CC);
        input.setTextSize(15);
        input.setTextColor(0xFF3B3A5C);
        input.setBackground(null);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMaxLines(4);
        input.setPadding(0, dp(6), 0, dp(6));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        box.addView(input, ilp);

        sendBtn = new TextView(this);
        sendBtn.setText("发送");
        sendBtn.setTextSize(14);
        sendBtn.setTextColor(0xFFFFFFFF);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setPadding(dp(20), dp(9), dp(20), dp(9));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(20));
        pill.setColor(0xFFA98CFF);
        sendBtn.setBackground(pill);
        sendBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send();
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(10);
        box.addView(sendBtn, blp);
        return box;
    }

    /** 一行气泡；mine=true 右对齐（我说的话）。 */
    private void addBubble(String text, boolean mine) {
        LinearLayout rowLine = new LinearLayout(this);
        rowLine.setOrientation(LinearLayout.HORIZONTAL);
        rowLine.setGravity(mine ? Gravity.END : Gravity.START);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setTextColor(mine ? 0xFFFFFFFF : 0xFF3B3A5C);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(mine ? 0xFFA98CFF : 0xFFFFFFFF);
        tv.setBackground(g);
        tv.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.78f));
        if (!mine && cute != null) tv.setTypeface(cute);

        rowLine.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        feed.addView(rowLine, rlp);
        scrollBottom();
    }

    /** 系统提示行（灰字居中），不占气泡。 */
    private void addNotice(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(0xFF9A97B8);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        feed.addView(tv, lp);
        scrollBottom();
    }

    /** 「正在想…」占位，返回它自己的 View 以便回包后移除。 */
    private View addTyping() {
        LinearLayout rowLine = new LinearLayout(this);
        rowLine.setOrientation(LinearLayout.HORIZONTAL);
        rowLine.setGravity(Gravity.START);
        TextView tv = new TextView(this);
        tv.setText("正在想…");
        tv.setTextSize(14);
        tv.setTextColor(0xFF9A97B8);
        tv.setPadding(dp(14), dp(9), dp(14), dp(9));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(0xFFF0EDFB);
        tv.setBackground(g);
        rowLine.addView(tv);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);
        feed.addView(rowLine, rlp);
        scrollBottom();
        return rowLine;
    }

    private void scrollBottom() {
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void clearFeed() {
        feed.removeAllViews();
    }

    // ------------------------------------------------------------------
    // 网络
    // ------------------------------------------------------------------

    private String base() {
        return PetService.endpointBase(this);
    }

    private void loadHistory() {
        final String url = base() + "/chat?pet=" + PET + "&surface=" + SURFACE
                + "&rounds=" + HISTORY_ROUNDS;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String body = null;
                int status = 0;
                String err = null;
                try {
                    DshHttp.Resp r = DshHttp.fetch("GET", url, null, 4000, 12000);
                    if (r != null) {
                        status = r.status;
                        body = r.body;
                    } else {
                        err = "没有响应";
                    }
                } catch (Exception e) {
                    err = e.getClass().getSimpleName();
                }
                final String fBody = body;
                final int fStatus = status;
                final String fErr = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        onHistory(fBody, fStatus, fErr);
                    }
                });
            }
        }).start();
    }

    private void onHistory(String body, int status, String err) {
        if (body == null) {
            addNotice("连不上 DSH：" + (err == null ? "未知原因" : err)
                    + "\n先在设置里填好地址和访问密码，桌宠连上之后再回来。");
            return;
        }
        if (status != 200) {
            addNotice("读取历史失败（HTTP " + status + "）"
                    + (DshHttp.lastAuthError != null ? "：" + DshHttp.lastAuthError : ""));
            return;
        }
        try {
            JSONObject o = new JSONObject(body);
            if (!o.optBoolean("ok", false)) {
                addNotice("插件说：" + describeFail(o));
                return;
            }
            JSONArray msgs = o.optJSONArray("messages");
            clearFeed();
            if (msgs == null || msgs.length() == 0) {
                addBubble("来啦。想聊什么？", false);
                return;
            }
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null) continue;
                String role = m.optString("role", "");
                String content = m.optString("content", "");
                if (content.isEmpty()) continue;
                addBubble(content, "user".equals(role));
            }
        } catch (Exception e) {
            addNotice("历史解析失败：" + e.getClass().getSimpleName());
        }
    }

    private void send() {
        if (sending) return;
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        addBubble(text, true);
        final View typing = addTyping();
        sending = true;
        sendBtn.setAlpha(0.5f);

        final String url = base() + "/chat?pet=" + PET + "&surface=" + SURFACE;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String body = null;
                int status = 0;
                String err = null;
                try {
                    JSONObject req = new JSONObject();
                    req.put("text", text);
                    DshHttp.Resp r = DshHttp.fetch("POST", url, req.toString(),
                            4000, READ_TIMEOUT_MS);
                    if (r != null) {
                        status = r.status;
                        body = r.body;
                    } else {
                        err = "没有响应";
                    }
                } catch (Exception e) {
                    err = e.getClass().getSimpleName();
                }
                final String fBody = body;
                final int fStatus = status;
                final String fErr = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        feed.removeView(typing);
                        sending = false;
                        sendBtn.setAlpha(1f);
                        onReply(fBody, fStatus, fErr);
                    }
                });
            }
        }).start();
    }

    private void onReply(String body, int status, String err) {
        if (body == null) {
            addNotice("没发出去：" + (err == null ? "连不上 DSH" : err));
            return;
        }
        if (status != 200) {
            addNotice("发送失败（HTTP " + status + "）"
                    + (DshHttp.lastAuthError != null ? "：" + DshHttp.lastAuthError : ""));
            return;
        }
        try {
            JSONObject o = new JSONObject(body);
            if (!o.optBoolean("ok", false)) {
                addNotice("插件说：" + describeFail(o));
                return;
            }
            String text = o.optString("text", "");
            if (text.isEmpty()) text = "……";
            addBubble(text, false);
        } catch (Exception e) {
            addNotice("回复解析失败：" + e.getClass().getSimpleName());
        }
    }

    /** 业务失败的统一措辞：优先 message，其次 reason。 */
    private static String describeFail(JSONObject o) {
        String msg = o.optString("message", "");
        if (!msg.isEmpty()) return msg;
        String reason = o.optString("reason", "");
        return reason.isEmpty() ? "未知错误" : reason;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
