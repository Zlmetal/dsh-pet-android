package com.dsha.pet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * App 里的聊天：左侧会话抽屉 + 聊天流，跟网页用的是同一份会话。
 *
 * 数据来源是 DSH 的 /api（见 DshApi），不是 dsh-pet 插件那个 /chat——
 * 那个是插件自己的独立身份（sessionId 写死 "dsh-pet"），看不到网页里聊过的东西。
 *
 * 交互：
 *   左上角「≡」开合左侧会话抽屉（按工作区分组），「新」开新会话；
 *   点会话加载前后各一页历史；发消息后用 session/wait 阻塞到这一轮跑完。
 *   输入框左边的「＋」可以选图片或附件一起发；气泡里的图片直接显示，
 *   文件点一下才下载到本地（默认不占流量）。
 */
public class ChatActivity extends Activity {
    /** 打开过的会话记在本地，下次进来接着聊。 */
    private static final String PREFS = "dsh_pet_chat";
    private static final String K_LAST = "last_session";
    /** 一次拉多少条记录（体积不小，别贪）。 */
    private static final int PAGE_MESSAGES = 6;
    /** 提示行的 tag，真消息进来时要清掉。 */
    private static final String TAG_NOTICE = "notice";
    private static final int REQ_PICK_IMAGE = 11;
    private static final int REQ_PICK_FILE = 12;
    /** 单次上传上限，超过就不让发（手机流量和内存都受不了）。 */
    private static final long MAX_UPLOAD = 24L * 1024 * 1024;
    /** 气泡里图片缩略图的最大宽度。 */
    private static final int THUMB_DP = 200;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private View chatView;
    private FrameLayout.LayoutParams chatParams;
    private LinearLayout sidebar;
    private LinearLayout sessionList;
    private LinearLayout feed;
    private LinearLayout pendingBar;
    private ScrollView scroll;
    private EditText input;
    private TextView sendBtn;
    private TextView titleView;
    private TextView subtitleView;
    private Typeface cute;

    private final List<DshApi.SessionRow> rows = new ArrayList<>();
    /** 抽屉里收起的工作区分组（按 cwd 记）。 */
    private final Set<String> collapsedGroups = new LinkedHashSet<>();
    /** 待发送的附件（选好了还没发出去）。 */
    private final List<Pending> pending = new ArrayList<>();
    /** 图片字节缓存，翻页/重绘时不用重复拉。 */
    private final Map<String, Bitmap> imageCache = new LinkedHashMap<String, Bitmap>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > 24;
        }
    };

    private String currentId = null;
    private String currentCwd = null;
    private long oldestSeq = 0;
    private boolean hasMore = false;
    private boolean sending = false;
    private boolean loading = false;
    private boolean sidebarOpen = true;

    /** 一个待发送的本地文件。 */
    private static final class Pending {
        Uri uri;
        String name;
        String mime;
    }

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

    /**
     * 顶栏：开合抽屉、新会话都放左边（右手拿平板时左边够得着，也不挡标题），
     * 关闭留在最右。
     */
    private View buildBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(34), dp(12), dp(12));

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

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(6);
        texts.setLayoutParams(tlp);

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
        bar.addView(texts);

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
    // 会话列表（按工作区分组）
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
        // 先用 LinkedHashMap 保序分组：服务端已经按活跃度排好，这里不重排
        Map<String, List<DshApi.SessionRow>> groups = new LinkedHashMap<>();
        for (DshApi.SessionRow r : data) {
            String key = (r.cwd == null || r.cwd.isEmpty()) ? "" : r.cwd;
            List<DshApi.SessionRow> bucket = groups.get(key);
            if (bucket == null) {
                bucket = new ArrayList<>();
                groups.put(key, bucket);
            }
            bucket.add(r);
        }
        for (Map.Entry<String, List<DshApi.SessionRow>> e : groups.entrySet()) {
            sessionList.addView(groupHeader(e.getKey(), e.getValue().size()));
            if (collapsedGroups.contains(e.getKey())) continue;
            for (DshApi.SessionRow r : e.getValue()) {
                sessionList.addView(sessionItem(r));
            }
        }
    }

    /** 目录名：分组标题用最后一段，根目录就显示整条。 */
    private static String dirName(String path) {
        if (path == null || path.isEmpty()) return "未分组";
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        int i = p.lastIndexOf('/');
        if (i >= 0 && i + 1 < p.length()) return p.substring(i + 1);
        return p;
    }

    private View groupHeader(final String cwd, int count) {
        boolean folded = collapsedGroups.contains(cwd);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(10), dp(12), dp(8), dp(6));

        TextView caret = new TextView(this);
        caret.setText(folded ? "▸" : "▾");
        caret.setTextSize(11);
        caret.setTextColor(0xFF9A97B8);
        caret.setPadding(0, 0, dp(6), 0);
        head.addView(caret);

        TextView name = new TextView(this);
        name.setText(dirName(cwd));
        name.setTextSize(12);
        name.setTextColor(0xFF8A85A8);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.START);
        head.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView n = new TextView(this);
        n.setText(String.valueOf(count));
        n.setTextSize(10);
        n.setTextColor(0xFFB4B0CC);
        n.setPadding(dp(6), 0, 0, 0);
        head.addView(n);

        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!collapsedGroups.remove(cwd)) collapsedGroups.add(cwd);
                renderSessions(rows, null);
            }
        });
        return head;
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
        return getResources().getDisplayMetrics().widthPixels < dp(600);
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
            addNotice("还没有会话，点左上角「新」开一个。");
        }
    }

    private void openSession(DshApi.SessionRow row) {
        currentId = row.id;
        currentCwd = row.cwd;
        oldestSeq = 0;
        hasMore = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(K_LAST, row.id).apply();
        titleView.setText(row.label());
        subtitleView.setText(dirName(row.cwd));
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
                        currentId = sid;
                        currentCwd = useCwd;
                        oldestSeq = 0;
                        hasMore = false;
                        getSharedPreferences(PREFS, MODE_PRIVATE)
                                .edit().putString(K_LAST, sid).apply();
                        rows.add(0, row);
                        titleView.setText("新会话");
                        subtitleView.setText(dirName(useCwd));
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
        View v = msgView(m);
        if (v == null) return;
        removeNotices();
        feed.addView(v);
        scrollBottom();
    }

    /** 一条消息：纵向排开正文、图片、附件，各自对齐。 */
    private View msgView(final DshApi.Msg m) {
        if (m.parts.isEmpty()) return null;
        boolean mine = "user".equals(m.role);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(8);
        col.setLayoutParams(clp);

        // 正文先合并成一条气泡，图片和文件排在它下面
        StringBuilder body = new StringBuilder();
        for (DshApi.Part p : m.parts) {
            if (p.kind == DshApi.Part.TEXT) {
                String t = cleanText(p.text);
                if (t == null) continue;
                if (body.length() > 0) body.append('\n');
                body.append(t);
            }
        }
        if (body.length() > 0) {
            LinearLayout rowLine = new LinearLayout(this);
            rowLine.setOrientation(LinearLayout.HORIZONTAL);
            rowLine.setGravity(mine ? Gravity.END : Gravity.START);

            TextView tv = new TextView(this);
            tv.setText(body.toString());
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
            col.addView(rowLine);
        }

        for (DshApi.Part p : m.parts) {
            if (p.kind == DshApi.Part.IMAGE) {
                col.addView(imageBlock(p, mine));
            } else if (p.kind == DshApi.Part.FILE || p.kind == DshApi.Part.PRESENTED) {
                col.addView(fileChip(p, mine));
            }
        }
        return col;
    }

    /** 图片块：先占位再后台拉字节，拉到就贴上（同一张图有缓存）。 */
    private View imageBlock(final DshApi.Part p, boolean mine) {
        LinearLayout rowLine = new LinearLayout(this);
        rowLine.setOrientation(LinearLayout.HORIZONTAL);
        rowLine.setGravity(mine ? Gravity.END : Gravity.START);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(6);
        rowLine.setLayoutParams(rlp);

        final ImageView iv = new ImageView(this);
        int w = dp(THUMB_DP);
        int h = dp(THUMB_DP * 0.75f);
        if (p.width > 0 && p.height > 0) {
            h = (int) (w * (float) p.height / (float) p.width);
            int maxH = dp(THUMB_DP * 1.6f);
            if (h > maxH) {
                h = maxH;
                w = (int) (h * (float) p.width / (float) p.height);
            }
        }
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(12));
        bg.setColor(0xFFEFEBFA);
        iv.setBackground(bg);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setClipToOutline(true);
        iv.setLayoutParams(new LinearLayout.LayoutParams(w, h));

        final String key = p.attachment == null ? null
                : p.attachment.optString("attachmentId", null);
        final String local = p.localUri;
        Bitmap cached = key == null ? null : imageCache.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
        } else if (local != null && !local.isEmpty()) {
            // 自己刚发的图：还没从服务端读回来，直接读本地那份先画上
            final String cacheKey = local;
            final Bitmap hit = imageCache.get(cacheKey);
            if (hit != null) {
                iv.setImageBitmap(hit);
            } else {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final Bitmap bm = decodeThumb(readUri(Uri.parse(local)), dp(THUMB_DP * 2));
                        if (bm == null) return;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                imageCache.put(cacheKey, bm);
                                iv.setImageBitmap(bm);
                            }
                        });
                    }
                }).start();
            }
        } else if (key != null) {
            final String org = origin();
            final String sid = currentId;
            final DshApi.Part part = p;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final byte[] data = DshApi.attachmentBytes(org, sid, part.attachment);
                    if (data == null) return;
                    final Bitmap bm = decodeThumb(data, dp(THUMB_DP * 2));
                    if (bm == null) return;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            imageCache.put(key, bm);
                            iv.setImageBitmap(bm);
                        }
                    });
                }
            }).start();
        }
        rowLine.addView(iv);
        return rowLine;
    }

    /**
     * 附件行：一行小卡片，点一下才下载。
     * 默认不落地——手机流量和设备空间都是老周自己的。
     */
    private View fileChip(final DshApi.Part p, boolean mine) {
        LinearLayout rowLine = new LinearLayout(this);
        rowLine.setOrientation(LinearLayout.HORIZONTAL);
        rowLine.setGravity(mine ? Gravity.END : Gravity.START);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(6);
        rowLine.setLayoutParams(rlp);

        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(12), dp(9), dp(12), dp(9));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(mine ? 0x33FFFFFF : 0xFFFFFFFF);
        chip.setBackground(g);

        TextView icon = new TextView(this);
        icon.setText("📎");
        icon.setTextSize(14);
        icon.setPadding(0, 0, dp(8), 0);
        chip.addView(icon);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView nm = new TextView(this);
        nm.setText(p.name == null || p.name.isEmpty() ? "附件" : p.name);
        nm.setTextSize(13);
        nm.setTextColor(mine ? 0xFFFFFFFF : 0xFF3B3A5C);
        nm.setSingleLine(true);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        texts.addView(nm);

        TextView meta = new TextView(this);
        meta.setText(p.kind == DshApi.Part.PRESENTED ? "点一下下载"
                : ("点一下下载" + (p.bytes > 0 ? " · " + sizeText(p.bytes) : "")));
        meta.setTextSize(10);
        meta.setTextColor(mine ? 0xD9FFFFFF : 0xFF9A97B8);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(2);
        texts.addView(meta, mlp);
        chip.addView(texts);

        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                downloadPart(p);
            }
        });
        rowLine.addView(chip);
        return rowLine;
    }

    /** 点附件：把它拉到本地 Downloads 里，成功/失败都给一句人话。 */
    private void downloadPart(final DshApi.Part p) {
        final String org = origin();
        final String sid = currentId;
        if (sid == null) return;
        Toast.makeText(this, "开始下载 " + (p.name == null ? "" : p.name), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] data;
                if (p.kind == DshApi.Part.PRESENTED) {
                    data = DshApi.fileBytes(org, p.path);
                } else {
                    data = DshApi.attachmentBytes(org, sid, p.attachment);
                }
                final String name = (p.name == null || p.name.isEmpty())
                        ? ("dsh-" + System.currentTimeMillis()) : p.name;
                final String where = data == null ? null : saveToDownloads(name, p.mediaType, data);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (where == null) {
                            Toast.makeText(ChatActivity.this,
                                    "下载失败，检查一下 DSH 连接", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(ChatActivity.this,
                                    "已保存到 " + where, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 落盘。Android 10 起走 MediaStore 的 Downloads（不需要存储权限），
     * 更老的系统只能放应用自己的外部目录。
     *
     * @return 人能看懂的位置，失败返回 null
     */
    private String saveToDownloads(String name, String mime, byte[] data) {
        String type = (mime == null || mime.isEmpty()) ? mimeOf(name) : mime;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, type);
                Uri uri = getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) return null;
                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) return null;
                try {
                    os.write(data);
                    os.flush();
                } finally {
                    os.close();
                }
                return "下载/" + name;
            }
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) dir = getFilesDir();
            if (!dir.exists() && !dir.mkdirs()) return null;
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            try {
                fos.write(data);
                fos.flush();
            } finally {
                fos.close();
            }
            return f.getAbsolutePath();
        } catch (Exception e) {
            return null;
        }
    }

    private static String mimeOf(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".txt") || n.endsWith(".md")) return "text/plain";
        if (n.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private static String sizeText(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + "KB";
        return String.format(Locale.US, "%.1fMB", bytes / 1048576.0);
    }

    /** 按最大边缩一下再解码，别把原图整张塞进内存。 */
    private static Bitmap decodeThumb(byte[] data, int maxSide) {
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, probe);
            int big = Math.max(probe.outWidth, probe.outHeight);
            int sample = 1;
            while (big / sample > maxSide * 2) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap bm = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (bm != null && Math.max(bm.getWidth(), bm.getHeight()) > maxSide) {
                float scale = maxSide / (float) Math.max(bm.getWidth(), bm.getHeight());
                Bitmap small = Bitmap.createScaledBitmap(bm,
                        Math.max(1, (int) (bm.getWidth() * scale)),
                        Math.max(1, (int) (bm.getHeight() * scale)), true);
                if (small != bm) bm.recycle();
                return small;
            }
            return bm;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 输入与发送
    // ------------------------------------------------------------------

    private View buildInput() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFFFFFFF);
        wrap.setBackground(bg);
        wrap.setPadding(dp(10), dp(8), dp(10), dp(12));
        wrap.setElevation(dp(2));

        // 选好了还没发的附件：一排小卡片，点 × 撤掉
        pendingBar = new LinearLayout(this);
        pendingBar.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(pendingBar);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.BOTTOM);

        TextView plus = barBtn("＋", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickWhat();
            }
        });
        plus.setTextColor(0xFF7B6BB5);
        box.addView(plus);

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

        wrap.addView(box);
        return wrap;
    }

    /** 「＋」弹一个小选择：发图片还是发文件。 */
    private void pickWhat() {
        final String[] items = new String[]{"图片", "文件"};
        new AlertDialog.Builder(this)
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        openPicker(which == 0 ? "image/*" : "*/*",
                                which == 0 ? REQ_PICK_IMAGE : REQ_PICK_FILE);
                    }
                })
                .show();
    }

    private void openPicker(String mime, int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        if (req != REQ_PICK_IMAGE && req != REQ_PICK_FILE) return;
        Uri uri = data.getData();
        Pending p = new Pending();
        p.uri = uri;
        p.name = queryName(uri);
        p.mime = queryMime(uri, p.name);
        boolean img = p.mime != null && p.mime.startsWith("image/");
        if (p.name == null || p.name.isEmpty()) {
            p.name = img ? "image.png" : "file";
        }
        pending.add(p);
        renderPending();
        scrollBottom();
    }

    /** 优先问系统要 MIME，问不到按后缀猜。 */
    private String queryMime(Uri uri, String name) {
        try {
            String t = getContentResolver().getType(uri);
            if (t != null && !t.isEmpty()) return t;
        } catch (Exception ignored) {
        }
        return mimeOf(name);
    }

    /** 文件选择器给的 Uris 拿不到路径，只能问名字。 */
    private String queryName(Uri uri) {
        Cursor c = null;
        try {
            ContentResolver cr = getContentResolver();
            c = cr.query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        String last = uri.getLastPathSegment();
        return last == null ? null : last;
    }

    private void renderPending() {
        pendingBar.removeAllViews();
        if (pending.isEmpty()) return;
        for (int i = 0; i < pending.size(); i++) {
            final int idx = i;
            Pending p = pending.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10), dp(6), dp(10), dp(6));
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(12));
            g.setColor(0xFFF2EEFF);
            row.setBackground(g);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = dp(6);
            row.setLayoutParams(rlp);

            TextView nm = new TextView(this);
            nm.setText("📎 " + p.name);
            nm.setTextSize(12);
            nm.setTextColor(0xFF5A48A8);
            nm.setSingleLine(true);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.addView(nm, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView x = new TextView(this);
            x.setText("×");
            x.setTextSize(16);
            x.setTextColor(0xFF9A97B8);
            x.setPadding(dp(10), 0, dp(4), 0);
            x.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (idx >= 0 && idx < pending.size()) pending.remove(idx);
                    renderPending();
                }
            });
            row.addView(x);
            pendingBar.addView(row);
        }
    }

    private void send() {
        if (sending) return;
        if (currentId == null) {
            addNotice("先选一个会话（左上角「≡」），或者点「新」。");
            return;
        }
        final String text = input.getText().toString().trim();
        if (text.isEmpty() && pending.isEmpty()) return;
        input.setText("");

        // 本地先把这条消息画出来，附件的名字也一起显示
        DshApi.Msg mine = new DshApi.Msg();
        mine.role = "user";
        mine.text = text;
        if (!text.isEmpty()) {
            DshApi.Part tp = new DshApi.Part();
            tp.kind = DshApi.Part.TEXT;
            tp.text = text;
            mine.parts.add(tp);
        }
        final List<Pending> sendingFiles = new ArrayList<>(pending);
        for (Pending p : sendingFiles) {
            DshApi.Part part = new DshApi.Part();
            boolean img = p.mime != null && p.mime.startsWith("image/");
            part.kind = img ? DshApi.Part.IMAGE : DshApi.Part.FILE;
            part.name = p.name;
            part.attachment = null;
            // 自己发的图先把本地 Uri 挂上，气泡里立刻能看见，不用等服务器读回来
            if (img) part.localUri = p.uri.toString();
            mine.parts.add(part);
        }
        pending.clear();
        renderPending();
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
                String err = null;
                List<String> receipts = new ArrayList<>();
                for (Pending p : sendingFiles) {
                    byte[] data = readUri(p.uri);
                    if (data == null) {
                        err = "读不到 " + p.name;
                        break;
                    }
                    if (data.length > MAX_UPLOAD) {
                        err = p.name + " 太大（上限 24MB）";
                        break;
                    }
                    String mime = imageMimeOrNull(p.mime, p.name);
                    String[] upErr = new String[1];
                    String rid = DshApi.upload(org, sid, p.name, mime, data, upErr);
                    if (rid == null) {
                        err = upErr[0] == null ? "上传失败" : upErr[0];
                        break;
                    }
                    receipts.add(rid);
                }

                // 1) 发之前先读 cursor，等回复时用它当 afterSeq
                long[] cursor = new long[1];
                DshApi.state(org, sid, cursor, null);
                long after = cursor[0];

                final List<DshApi.Msg> got = new ArrayList<>();
                if (err == null) {
                    DshApi.Result p = DshApi.prompt(org, sid, text, receipts);
                    if (!p.ok) {
                        err = p.msg;
                    } else {
                        // 2) 阻塞等这一轮跑完（主机侧会话空闲才回）
                        DshApi.Result w = DshApi.waitIdle(org, sid, after, 180000, got);
                        if (!w.ok) err = w.msg;
                    }
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

    /** 读选中文件的字节。 */
    private byte[] readUri(Uri uri) {
        InputStream in = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total > MAX_UPLOAD) return null;
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        } finally {
            try {
                if (in != null) in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 服务端图片白名单只有 png/jpeg/webp/gif 四种，别的一律当普通文件传
     * （传错 mediaType 会被 400 挡回来）。
     */
    private static String imageMimeOrNull(String mime, String name) {
        String m = (mime == null || mime.isEmpty()) ? mimeOf(name) : mime;
        if ("image/png".equals(m) || "image/jpeg".equals(m)
                || "image/webp".equals(m) || "image/gif".equals(m)) {
            return m;
        }
        return null;
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
        tv.setTag(TAG_NOTICE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        feed.addView(tv, lp);
        scrollBottom();
    }

    /** 有真消息进来时把「这个会话还是空的」「正在拉取」这类提示清掉，别留着占位。 */
    private void removeNotices() {
        for (int i = feed.getChildCount() - 1; i >= 0; i--) {
            View v = feed.getChildAt(i);
            if (v != null && TAG_NOTICE.equals(String.valueOf(v.getTag()))) feed.removeViewAt(i);
        }
    }

    /**
     * 去掉 `[表情: xxx]` 这种只在支持表情包的前端才有意义的标记——
     * App 里没有表情包通道，原样留着就是一行机器话。
     */
    private static String cleanText(String s) {
        if (s == null) return null;
        if (s.indexOf("[表情") < 0) return s;
        String r = s.replaceAll("\\s*\\[表情[^\\]]*\\]", "").trim();
        // 整条就只有表情标记（本机没有表情包通道），返回 null 让调用方跳过这条
        return r.isEmpty() ? null : r;
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
