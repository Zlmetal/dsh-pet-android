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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * App 里的聊天：左侧会话抽屉 + 聊天流，跟网页用的是同一份会话。
 *
 * 数据来源是 DSH 的 /api（见 DshApi），不是 dsh-pet 插件那个 /chat——
 * 那个是插件自己的独立身份（sessionId 写死 "dsh-pet"），看不到网页里聊过的东西。
 *
 * 交互：
 *   顶栏「≡」开合左侧会话抽屉；点会话加载前后各一页历史；发消息后
 *   用 session/wait 阻塞到这一轮跑完，回复直接拿到，不用轮询。
 */
public class ChatActivity extends Activity {
    /** 打开过的会话记在本地，下次进来接着聊。 */
    private static final String PREFS = "dsh_pet_chat";
    private static final String K_LAST = "last_session";
    /** 一次拉多少条 assistant 记录（体积不小，别贪）。 */
    private static final int PAGE_MESSAGES = 6;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private View chatView;
    private FrameLayout.LayoutParams chatParams;
    private LinearLayout sidebar;
    private LinearLayout sessionList;
    private LinearLayout feed;
    private ScrollView scroll;
    private EditText input;
    private TextView sendBtn;
    private TextView titleView;
    private TextView subtitleView;
    private Typeface cute;

    private final List<DshApi.SessionRow> rows = new ArrayList<>();
    private String currentId = null;
    private String currentCwd = null;
    private long oldestSeq = 0;
    private boolean hasMore = false;
    private boolean sending = false;
    private boolean loading = false;
    private boolean sidebarOpen = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DshHttp.init(this);
        try {
            cute = Typeface.create(
                    Typeface.createFromAsset(getAssets(), "font/cute.ttf"), Typeface.BOLD);
        } catch (Exception ignored) {
        }

        root = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFFFFF2F7, 0xFFEDEFFF, 0xFFF7F4FF});
        root.setBackground(bg);
        setContentView(root);

        chatView = buildChat();
        root.addView(chatView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        sidebar = buildSidebar();
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                dp(272), ViewGroup.LayoutParams.MATCH_PARENT);
        slp.gravity = Gravity.START;
        root.addView(sidebar, slp);

        // 抽屉盖上时给聊天列留出宽度；摊开时只留一条缝，别把内容全遮住
        chatParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        chatParams.leftMargin = 0;
        chatView.setLayoutParams(chatParams);

        // 手机上默认收起，平板（够宽）默认摊开并排显示
        sidebarOpen = !isNarrow();
        applySidebar();

        String last = getSharedPreferences(PREFS, MODE_PRIVATE).getString(K_LAST, null);
        if (last != null) currentId = last;

        refreshSessions();
    }

    /** 返回键：抽屉开着就先收抽屉，再按才退出。 */
    @Override
    public void onBackPressed() {
        if (sidebarOpen && isNarrow()) {
            toggleSidebar();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------
    // 骨架
    // ------------------------------------------------------------------

    private View buildChat() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        col.addView(buildBar());

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        col.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        feed = new LinearLayout(this);
        feed.setOrientation(LinearLayout.VERTICAL);
        feed.setPadding(dp(16), dp(10), dp(16), dp(16));
        scroll.addView(feed, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(buildInput());
        return col;
    }

    private View buildBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(34), dp(12), dp(12));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        titleView = new TextView(this);
        titleView.setText("大肥鱼");
        titleView.setTextSize(18);
        titleView.setTextColor(0xFF3B3A5C);
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (cute != null) titleView.setTypeface(cute);
        texts.addView(titleView);

        subtitleView = new TextView(this);
        subtitleView.setText("正在连 DSH…");
        subtitleView.setTextSize(11);
        subtitleView.setTextColor(0xFF9A97B8);
        subtitleView.setSingleLine(true);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(3);
        texts.addView(subtitleView, subLp);
        bar.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bar.addView(barBtn("≡", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSidebar();
            }
        }));
        bar.addView(barBtn("新", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newSession();
            }
        }));
        bar.addView(barBtn("关闭", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }));
        return bar;
    }

    private TextView barBtn(String text, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize("≡".equals(text) || "新".equals(text) ? 18 : 13);
        tv.setTextColor(0xFF7B6BB5);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(11), dp(7), dp(11), dp(7));
        tv.setOnClickListener(l);
        return tv;
    }

    private LinearLayout buildSidebar() {
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF7FFFFFF);
        side.setBackground(bg);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(16), dp(34), dp(12), dp(12));

        TextView t = new TextView(this);
        t.setText("会话");
        t.setTextSize(15);
        t.setTextColor(0xFF3B3A5C);
        if (cute != null) t.setTypeface(cute);
        head.addView(t, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(barBtn("刷新", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshSessions();
            }
        }));
        side.addView(head);

        sessionList = new LinearLayout(this);
        sessionList.setOrientation(LinearLayout.VERTICAL);
        sessionList.setPadding(dp(8), 0, dp(8), dp(16));
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(sessionList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        side.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return side;
    }

    private void toggleSidebar() {
        sidebarOpen = !sidebarOpen;
        applySidebar();
    }

    /**
     * 抽屉默认贴左边，摊开时聊天列整体右移（跟网页一样是并排，不是盖在上面）。
     * 窄屏上抽屉是抽屉（盖上来），宽屏上就是并排的两栏。
     */
    private void applySidebar() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sidebar.getLayoutParams();
        lp.width = sidebarOpen ? dp(272) : 0;
        sidebar.setLayoutParams(lp);
        sidebar.setVisibility(sidebarOpen ? View.VISIBLE : View.GONE);
        if (chatParams != null) {
            chatParams.leftMargin = (sidebarOpen && !isNarrow()) ? dp(272) : 0;
            chatView.setLayoutParams(chatParams);
        }
    }

    // ------------------------------------------------------------------
    // 会话列表
    // ------------------------------------------------------------------

    private String origin() {
        return DshHttp.originOf(PetService.endpointBase(this));
    }

    private void refreshSessions() {
        if (loading) return;
        loading = true;
        final String org = origin();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<DshApi.SessionRow> got = new ArrayList<>();
                final DshApi.Result r = DshApi.list(org, got);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        loading = false;
                        if (!r.ok) {
                            renderSessions(null, r.msg);
                            if (currentId == null) {
                                clearFeed();
                                addNotice("连不上 DSH：" + r.msg
                                        + "\n先在设置里确认地址和访问密码。");
                            }
                            return;
                        }
                        rows.clear();
                        rows.addAll(got);
                        renderSessions(rows, null);
                        pickInitial();
                    }
                });
            }
        }).start();
    }

    private void renderSessions(List<DshApi.SessionRow> data, String err) {
        sessionList.removeAllViews();
        if (err != null) {
            addSideNotice("读不到会话：" + err);
            return;
        }
        if (data == null || data.isEmpty()) {
            addSideNotice("还没有会话，点上面「新」开一个。");
            return;
        }
        for (int i = 0; i < data.size(); i++) {
            sessionList.addView(sessionItem(data.get(i)));
        }
    }

    private void addSideNotice(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(0xFF9A97B8);
        tv.setPadding(dp(10), dp(12), dp(10), dp(12));
        sessionList.addView(tv);
    }

    private View sessionItem(final DshApi.SessionRow row) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        boolean sel = row.id != null && row.id.equals(currentId);
        g.setColor(sel ? 0xFFEDE7FF : 0x00000000);
        card.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(4);
        card.setLayoutParams(lp);

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(row.label());
        name.setTextSize(13);
        name.setTextColor(sel ? 0xFF5A48A8 : 0xFF3B3A5C);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        line.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (row.running) {
            TextView dot = new TextView(this);
            dot.setText("●");
            dot.setTextSize(10);
            dot.setTextColor(0xFF35B37E);
            dot.setPadding(dp(6), 0, 0, 0);
            line.addView(dot);
        }
        card.addView(line);

        String sub = row.cwd == null ? "" : row.cwd;
        int i = sub.lastIndexOf('/');
        if (i >= 0 && i + 1 < sub.length()) sub = sub.substring(i + 1);
        TextView s = new TextView(this);
        s.setText(sub.isEmpty() ? "—" : sub);
        s.setTextSize(10);
        s.setTextColor(0xFF9A97B8);
        s.setSingleLine(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        card.addView(s, slp);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (row.id != null && row.id.equals(currentId) && feed.getChildCount() > 0) {
                    if (isNarrow()) toggleSidebar();
                    return;
                }
                openSession(row);
            }
        });
        return card;
    }

    private boolean isNarrow() {
        return getResources().getDisplayMetrics().widthPixels
                < dp(600);
    }

    /** 第一次进来挑一个：优先上次那个，否则第一个。 */
    private void pickInitial() {
        if (currentId != null) {
            for (DshApi.SessionRow r : rows) {
                if (currentId.equals(r.id)) {
                    openSession(r);
                    return;
                }
            }
            currentId = null;
        }
        if (!rows.isEmpty()) openSession(rows.get(0));
        else {
            clearFeed();
            addNotice("还没有会话，点「新」开一个，或者左上角「≡」换一个。");
        }
    }

    private void openSession(DshApi.SessionRow row) {
        currentId = row.id;
        currentCwd = row.cwd;
        oldestSeq = 0;
        hasMore = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(K_LAST, row.id).apply();
        titleView.setText(row.label());
        subtitleView.setText(row.model == null ? "DSH 会话" : row.model);
        renderSessions(rows, null);
        if (isNarrow()) toggleSidebar();
        loadLatest();
    }

    private void newSession() {
        final String org = origin();
        String cwd = currentCwd;
        if (cwd == null) {
            for (DshApi.SessionRow r : rows) {
                if (r.cwd != null && !r.cwd.isEmpty()) {
                    cwd = r.cwd;
                    break;
                }
            }
        }
        final String useCwd = cwd;
        clearFeed();
        addNotice("正在新建会话…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final DshApi.Result r = DshApi.create(org, useCwd);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!r.ok) {
                            clearFeed();
                            addNotice("新建失败：" + r.msg);
                            return;
                        }
                        String sid = r.value.optString("sessionId", "");
                        if (sid.isEmpty()) {
                            clearFeed();
                            addNotice("新建失败：没拿到会话号");
                            return;
                        }
                        DshApi.SessionRow row = new DshApi.SessionRow();
                        row.id = sid;
                        row.cwd = useCwd;
                        row.title = "新会话";
                        currentId = sid;
                        currentCwd = useCwd;
                        oldestSeq = 0;
                        hasMore = false;
                        getSharedPreferences(PREFS, MODE_PRIVATE)
                                .edit().putString(K_LAST, sid).apply();
                        rows.add(0, row);
                        titleView.setText("新会话");
                        subtitleView.setText("DSH 会话");
                        renderSessions(rows, null);
                        clearFeed();
                        addNotice("会话开好了，说吧。");
                        if (isNarrow()) toggleSidebar();
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------
    // 历史
    // ------------------------------------------------------------------

    private void loadLatest() {
        if (currentId == null) return;
        final String org = origin();
        final String sid = currentId;
        clearFeed();
        addNotice("拉取历史…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<DshApi.Msg> msgs = new ArrayList<>();
                final DshApi.Page pg = new DshApi.Page();
                final DshApi.Result r = DshApi.events(org, sid, 0, PAGE_MESSAGES, msgs, pg);
                // running 只是提示，读失败不影响历史显示
                final boolean[] running = new boolean[1];
                if (r.ok) DshApi.state(org, sid, null, running);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!sid.equals(currentId)) return;
                        clearFeed();
                        if (!r.ok) {
                            addNotice("读取历史失败：" + r.msg);
                            return;
                        }
                        if (msgs.isEmpty()) {
                            addNotice("这个会话还是空的，说点什么吧。");
                            return;
                        }
                        hasMore = pg.hasMore;
                        oldestSeq = pg.minSeq;
                        if (hasMore) feed.addView(olderButton());
                        for (DshApi.Msg m : msgs) addMsg(m);
                        if (running[0]) addTyping();
                    }
                });
            }
        }).start();
    }

    private void loadOlder() {
        if (currentId == null || oldestSeq <= 0) return;
        final String org = origin();
        final String sid = currentId;
        final long before = oldestSeq;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<DshApi.Msg> msgs = new ArrayList<>();
                final DshApi.Page pg = new DshApi.Page();
                final DshApi.Result r = DshApi.events(org, sid, before, PAGE_MESSAGES, msgs, pg);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!sid.equals(currentId)) return;
                        if (!r.ok) {
                            addNotice("翻页失败：" + r.msg);
                            return;
                        }
                        View first = feed.getChildAt(0);
                        if (first != null && "older".equals(String.valueOf(first.getTag()))) {
                            feed.removeViewAt(0);
                        }
                        hasMore = pg.hasMore;
                        if (pg.minSeq > 0) oldestSeq = pg.minSeq;
                        int at = 0;
                        if (hasMore) {
                            feed.addView(olderButton(), 0);
                            at = 1;
                        }
                        for (int i = 0; i < msgs.size(); i++) {
                            feed.addView(msgView(msgs.get(i)), at + i);
                        }
                    }
                });
            }
        }).start();
    }

    private View olderButton() {
        TextView tv = new TextView(this);
        tv.setText("↑ 更早的消息");
        tv.setTextSize(12);
        tv.setTextColor(0xFF7B6BB5);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(10), dp(10), dp(10));
        tv.setTag("older");
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadOlder();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void addMsg(DshApi.Msg m) {
        feed.addView(msgView(m));
        scrollBottom();
    }

    /** 一条消息的视图：用户右侧紫气泡，助手左侧白气泡，工具灰字小行。 */
    private View msgView(DshApi.Msg m) {
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(8);

        if ("tool".equals(m.role)) {
            TextView tv = new TextView(this);
            tv.setText(m.text);
            tv.setTextSize(11);
            tv.setTextColor(m.error ? 0xFFC9788A : 0xFF9A97B8);
            tv.setPadding(dp(10), dp(6), dp(10), dp(6));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(9));
            g.setColor(m.error ? 0x14C9788A : 0x0F000000);
            tv.setBackground(g);
            tv.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.86f));
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.START);
            line.addView(tv);
            line.setLayoutParams(rlp);
            return line;
        }

        boolean mine = "user".equals(m.role);
        LinearLayout rowLine = new LinearLayout(this);
        rowLine.setOrientation(LinearLayout.HORIZONTAL);
        rowLine.setGravity(mine ? Gravity.END : Gravity.START);

        TextView tv = new TextView(this);
        tv.setText(m.text);
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
        rowLine.addView(tv);
        rowLine.setLayoutParams(rlp);
        return rowLine;
    }

    // ------------------------------------------------------------------
    // 输入与发送
    // ------------------------------------------------------------------

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
        box.addView(input, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

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

    private void send() {
        if (sending) return;
        if (currentId == null) {
            addNotice("先选一个会话（左上角「≡」），或者点「新」。");
            return;
        }
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        DshApi.Msg mine = new DshApi.Msg();
        mine.role = "user";
        mine.text = text;
        addMsg(mine);
        final View typing = addTyping();
        sending = true;
        sendBtn.setAlpha(0.5f);
        sendBtn.setText("等");

        final String org = origin();
        final String sid = currentId;
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 1) 发之前先读 cursor，等回复时用它当 afterSeq
                long[] cursor = new long[1];
                DshApi.state(org, sid, cursor, null);
                long after = cursor[0];
                // 2) 投递
                DshApi.Result p = DshApi.prompt(org, sid, text);
                final List<DshApi.Msg> got = new ArrayList<>();
                String err = null;
                if (!p.ok) {
                    err = p.msg;
                } else {
                    // 3) 阻塞等这一轮跑完（主机侧会话空闲才回）
                    DshApi.Result w = DshApi.waitIdle(org, sid, after, 180000, got);
                    if (!w.ok) err = w.msg;
                }
                final String fErr = err;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!sid.equals(currentId)) return;
                        feed.removeView(typing);
                        sending = false;
                        sendBtn.setAlpha(1f);
                        sendBtn.setText("发送");
                        if (fErr != null) {
                            addNotice("没发出去：" + fErr);
                            return;
                        }
                        if (got.isEmpty()) {
                            addNotice("这一轮没产出文字回复（可能只调了工具）。");
                            return;
                        }
                        for (DshApi.Msg m : got) addMsg(m);
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

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
        rowLine.setLayoutParams(rlp);
        feed.addView(rowLine);
        scrollBottom();
        return rowLine;
    }

    private void addNotice(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(0xFF9A97B8);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(8), dp(10), dp(8), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        feed.addView(tv, lp);
        scrollBottom();
    }

    private void clearFeed() {
        feed.removeAllViews();
    }

    private void scrollBottom() {
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
