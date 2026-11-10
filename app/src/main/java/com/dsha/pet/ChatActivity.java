package com.dsha.pet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
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
    /**
     * 一次向服务端要多少条「消息预算」。
     *
     * 注意这不是「能显示几条」——服务端按 user/assistant message 计数，
     * 而一页里绝大部分记录是 tool/call、step、job、reasoning，客户端全丢掉。
     * 实测（一个工具调用极多的会话）：400 条预算只能落 9 条能显示的，
     * 1000 条落 31 条、gzip 后 3.6MB / 0.53 秒；1500 条落 50 条 / 0.79 秒。
     * 预算给足了就一轮到位，省掉反复翻页——多翻一轮就是多传一份重复记录。
     */
    private static final int PAGE_MESSAGES = 1200;
    /** 一页至少要出这么多条能显示的，不够就继续往前翻（会话里工具调用多时会遇到）。 */
    private static final int MIN_VISIBLE = 30;
    /** 首次打开会话，至少先铺出这么多条能显示的。 */
    private static final int FIRST_VISIBLE = 30;
    /** 一次操作最多连翻几轮，防止极端会话把流量吃光。 */
    private static final int MAX_FETCH_ROUNDS = 2;
    /** 提示行的 tag，真消息进来时要清掉。 */
    private static final String TAG_NOTICE = "notice";
    /** 聊天流顶部那条「下拉加载更早」的 tag。 */
    private static final String TAG_OLDER = "older";
    /** 手指贴着顶部往下拉多少 dp 算一次翻页。 */
    private static final int PULL_TRIGGER_DP = 70;
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
    /** 正在翻更早的一页（防连拉，也防手指抖一下连发两次请求）。 */
    private boolean loadingOlder = false;
    private boolean sidebarOpen = true;

    /** 一个待发送的本地文件。 */
    private static final class Pending {
        Uri uri;
        String name;
        String mime;
    }

    /** 前台时置位，悬浮窗双击据此只提示、不再叠一层对话。 */
    @Override
    protected void onResume() {
        super.onResume();
        PetService.chatOpen = true;
    }

    @Override
    protected void onPause() {
        PetService.chatOpen = false;
        super.onPause();
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

        scroll = new PullScroll(this);
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
        // 窄屏上抽屉会盖住顶栏，所以把开合与新会话也放一份到这里
        head.addView(barBtn("≡", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSidebar();
            }
        }));
        head.addView(barBtn("新", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newSession();
            }
        }));
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

    /** 一次翻页的总结果：攒了几轮出来的消息 + 还翻不翻得动 + 下次的锚点。 */
    private static final class Fetch {
        final List<DshApi.Msg> msgs = new ArrayList<>();
        boolean ok = true;
        String err;
        boolean hasMore;
        /** 下一条要拉的最早 seq（翻页锚点）。 */
        long minSeq;
    }

    /**
     * 拉历史：一次不够就接着往前翻，直到攒够 want 条能显示的为止。
     *
     * 为什么不能只拉一次：服务端按 user/assistant message 计数，但一页里
     * 九成记录是 tool/call、step、job、reasoning，客户端全丢——预算 80 条
     * 实际只落十几条。所以这里按「能显示的条数」而不是「拉了几次」来收工。
     */
    private Fetch fetchHistory(String org, String sid, long beforeSeq, int want) {
        Fetch f = new Fetch();
        java.util.LinkedHashMap<Long, DshApi.Msg> got = new java.util.LinkedHashMap<>();
        long cursor = beforeSeq;
        for (int round = 0; round < MAX_FETCH_ROUNDS; round++) {
            List<DshApi.Msg> page = new ArrayList<>();
            DshApi.Page pg = new DshApi.Page();
            DshApi.Result r = DshApi.events(org, sid, cursor, PAGE_MESSAGES, page, pg);
            if (!r.ok) {
                // 第一轮就失败才算出错；后面几轮失败就用攒到的那些
                if (round == 0) {
                    f.ok = false;
                    f.err = r.msg;
                    return f;
                }
                break;
            }
            for (DshApi.Msg m : page) {
                if (m.seq > 0) got.put(m.seq, m);
            }
            f.hasMore = pg.hasMore;
            if (pg.minSeq > 0) {
                f.minSeq = pg.minSeq;
                cursor = pg.minSeq;
            } else {
                f.hasMore = false;
            }
            if (got.size() >= want || !pg.hasMore || cursor <= 0) break;
        }
        f.msgs.addAll(got.values());
        java.util.Collections.sort(f.msgs, new java.util.Comparator<DshApi.Msg>() {
            @Override
            public int compare(DshApi.Msg a, DshApi.Msg b) {
                return Long.compare(a.seq, b.seq);
            }
        });
        return f;
    }

    /**
     * 打开会话：先把本地存档画出来（秒开，不用等网络），再去拉服务端最新一页。
     * 服务端那页回来以后跟存档合并去重，重新画一次。
     */
    private void loadLatest() {
        if (currentId == null) return;
        final String org = origin();
        final String sid = currentId;
        clearFeed();

        // 1) 本地存档先上屏
        final List<DshApi.Msg> cached = ChatStore.load(this, sid);
        if (!cached.isEmpty()) {
            oldestSeq = cached.get(0).seq;
            hasMore = true;
            feed.addView(olderButton());
            for (DshApi.Msg m : cached) addMsgNoScroll(m);
            scrollBottom();
        } else {
            addNotice("拉取历史…");
        }

        // 2) 服务端最新一页
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Fetch f = fetchHistory(org, sid, 0, FIRST_VISIBLE);
                final boolean[] running = new boolean[1];
                if (f.ok) DshApi.state(org, sid, null, running);
                final List<DshApi.Msg> merged = f.ok ? ChatStore.merge(
                        ChatActivity.this, sid, f.msgs) : null;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!sid.equals(currentId)) return;
                        if (!f.ok) {
                            // 有存档就照常看着，别拿网络错误盖掉已经显示的内容
                            if (cached.isEmpty()) {
                                clearFeed();
                                addNotice("读取历史失败：" + f.err);
                            }
                            return;
                        }
                        clearFeed();
                        if (f.minSeq > 0) oldestSeq = f.minSeq;
                        else if (merged != null && !merged.isEmpty()) {
                            oldestSeq = merged.get(0).seq;
                        }
                        hasMore = f.hasMore;
                        if (merged == null || merged.isEmpty()) {
                            addNotice("这个会话还是空的，说点什么吧。");
                            return;
                        }
                        if (hasMore) feed.addView(olderButton());
                        for (DshApi.Msg m : merged) addMsgNoScroll(m);
                        scrollBottom();
                        if (running[0]) addTyping();
                    }
                });
            }
        }).start();
    }

    /**
     * 往上翻一页：只把这次拉回来的（严格更老的）几条插到顶部，
     * 已经显示的条目一个都不重画——重画会丢滚动位置、也会把「正在想…」顶掉。
     * 插完把 scrollY 补上新增高度，正在看的那句不会跳走。
     */
    private void loadOlder() {
        if (currentId == null || oldestSeq <= 0 || loadingOlder) return;
        loadingOlder = true;
        final String org = origin();
        final String sid = currentId;
        final long before = oldestSeq;
        setOlderHint("↑ 正在找更早的…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Fetch f = fetchHistory(org, sid, before, MIN_VISIBLE);
                final List<DshApi.Msg> msgs = f.msgs;
                if (f.ok) ChatStore.merge(ChatActivity.this, sid, msgs);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!sid.equals(currentId)) {
                            loadingOlder = false;
                            return;
                        }
                        loadingOlder = false;
                        if (!f.ok) {
                            setOlderHint("没翻动：" + f.err);
                            return;
                        }
                        hasMore = f.hasMore;
                        if (f.minSeq > 0) oldestSeq = f.minSeq;
                        if (msgs.isEmpty()) {
                            // 连着翻了几轮还是没捞到能显示的：别让提示一直转，说明白
                            setOlderHint(hasMore ? "这一段全是工具调用，再拉一次" : "没有更早的了");
                            return;
                        }

                        final int keepY = scroll.getScrollY();
                        final int oldH = feed.getHeight();
                        removeNotices();
                        View first = feed.getChildCount() > 0 ? feed.getChildAt(0) : null;
                        int at = 0;
                        if (first != null && TAG_OLDER.equals(first.getTag())) {
                            feed.removeViewAt(0);
                        }
                        if (hasMore) {
                            feed.addView(olderButton(), 0);
                            at = 1;
                        }
                        for (int i = 0; i < msgs.size(); i++) {
                            View v = msgView(msgs.get(i));
                            if (v != null) {
                                feed.addView(v, at + i);
                            }
                        }
                        // 还有更早就保持提示行原样，拉到头了才改成「没有更早」
                        if (!hasMore) setOlderHint("没有更早的了");
                        // 等这一帧量完高度再补滚动，否则量到的是旧高度
                        feed.post(new Runnable() {
                            @Override
                            public void run() {
                                int grew = feed.getHeight() - oldH;
                                if (grew > 0) scroll.scrollTo(0, keepY + grew);
                                else scroll.scrollTo(0, keepY);
                            }
                        });
                    }
                });
            }
        }).start();
    }

    private View olderButton() {
        TextView tv = new TextView(this);
        tv.setText("↑ 下拉或点这里加载更早");
        tv.setTextSize(12);
        tv.setTextColor(0xFF7B6BB5);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(14), dp(10), dp(14));
        tv.setTag(TAG_OLDER);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadOlder();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 改顶部那条的字；没有就补一条。 */
    private void setOlderHint(String text) {
        for (int i = 0; i < feed.getChildCount(); i++) {
            View v = feed.getChildAt(i);
            if (v instanceof TextView && TAG_OLDER.equals(v.getTag())) {
                ((TextView) v).setText(text);
                return;
            }
        }
        if (feed.getChildCount() > 0) {
            TextView tv = new TextView(this);
            tv.setText(text);
            tv.setTextSize(12);
            tv.setTextColor(0xFF7B6BB5);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(10), dp(14), dp(10), dp(14));
            tv.setTag(TAG_OLDER);
            feed.addView(tv, 0);
        }
    }

    /**
     * 滚到顶再往下拉一截就翻页（跟 QQ 一致）。
     *
     * 不能用 setOnTouchListener：气泡是可以长按选字的 TextView，会把 DOWN 吃掉，
     * ViewGroup 只有在没有子 view 接手时才会把事件交给自己的 OnTouchListener。
     * onInterceptTouchEvent 则是每次都能拿到，所以在这一层看手势。
     */
    private class PullScroll extends ScrollView {
        private float downY;
        private boolean armed;

        PullScroll(Context c) {
            super(c);
        }

        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downY = e.getY();
                    // 按下时就贴顶才备着，中途滚上去的不算
                    armed = hasMore && !loadingOlder && getScrollY() <= dp(4);
                    break;
                case android.view.MotionEvent.ACTION_MOVE:
                    if (armed && getScrollY() <= dp(4) && e.getY() - downY > dp(PULL_TRIGGER_DP)) {
                        armed = false;
                        loadOlder();
                    }
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    armed = false;
                    break;
            }
            return super.onInterceptTouchEvent(e);
        }
    }

    /** 追加一条但不滚到底——重画历史时用，否则每加一条跳一次。 */
    private void addMsgNoScroll(DshApi.Msg m) {
        View v = msgView(m);
        if (v != null) feed.addView(v);
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
            // 消息正文一律用系统字体：cute.ttf 当标题好看，成段文字看着费劲
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
        // 自己发的附件也在浅底上，用淡紫卡片；白字在浅底上看不见
        g.setColor(mine ? 0xFFEFEBFA : 0xFFFFFFFF);
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
        nm.setTextColor(0xFF3B3A5C);
        nm.setSingleLine(true);
        nm.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        texts.addView(nm);

        final String key = dlKey(p);
        final TextView meta = new TextView(this);
        meta.setTextSize(10);
        refreshMeta(meta, p, key);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(2);
        texts.addView(meta, mlp);
        chip.addView(texts);

        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 下过一次就存着：再点直接拿本地那份去开会，不再走网络
                Uri local = savedUri(key);
                if (local != null) {
                    openSaved(key, local, meta);
                    return;
                }
                downloadPart(p, key, meta);
            }
        });
        rowLine.addView(chip);
        return rowLine;
    }

    /** 附件卡片第二行的说明：没下过写大小，下过就写「已下载 · 点一下打开」。 */
    private void refreshMeta(TextView meta, DshApi.Part p, String key) {
        if (meta == null) return;
        boolean stale = p.kind == DshApi.Part.FILE && p.attachment != null
                && (p.localUri == null || p.localUri.isEmpty());
        if (savedUri(key) != null) {
            meta.setText("已下载 · 点一下打开");
            meta.setTextColor(0xFF7C6BD6);
        } else if (stale) {
            meta.setText("历史附件已不可取，重发一次才能下");
            meta.setTextColor(0xFF9A97B8);
        } else {
            meta.setText("点一下下载" + (p.bytes > 0 ? " · " + sizeText(p.bytes) : ""));
            meta.setTextColor(0xFF9A97B8);
        }
    }

    /**
     * 一份附件在本地落盘后的记号：同一个附件只下一次，再点就是打开。
     * 优先用 sha256 / attachmentId，present 文件用绝对路径，本地原件用 Uri。
     */
    private static String dlKey(DshApi.Part p) {
        if (p == null) return "";
        if (p.localUri != null && !p.localUri.isEmpty()) return "u:" + p.localUri;
        if (p.kind == DshApi.Part.PRESENTED) return "p:" + p.path;
        if (p.attachment != null) {
            String id = p.attachment.optString("attachmentId", "");
            if (id.isEmpty()) id = p.attachment.optString("sha256", "");
            if (!id.isEmpty()) return "a:" + id;
        }
        return "n:" + (p.name == null ? "" : p.name) + ":" + p.bytes;
    }

    private static String K_DL = "dl:";

    /** 已经下过就返回本地 Uri，没下过返回 null。 */
    private Uri savedUri(String key) {
        if (key == null || key.isEmpty()) return null;
        String v = getSharedPreferences(PREFS, MODE_PRIVATE).getString(K_DL + key, null);
        if (v == null) return null;
        String[] f = v.split("\n", 3);
        if (f.length < 1 || f[0].isEmpty()) return null;
        Uri u = Uri.parse(f[0]);
        // 文件被用户在系统里删了就当没下过，重新拉一次
        try {
            if ("file".equals(u.getScheme())) {
                if (f[0].startsWith("file://") && new File(u.getPath()).exists()) return u;
                if (!f[0].contains("://") && new File(f[0]).exists()) return Uri.fromFile(new File(f[0]));
                return null;
            }
            InputStream in = getContentResolver().openInputStream(u);
            if (in == null) return null;
            in.close();
            return u;
        } catch (Exception e) {
            return null;
        }
    }

    private void rememberSaved(String key, Uri uri, String name, String mime) {
        if (key == null || key.isEmpty() || uri == null) return;
        String v = uri.toString() + "\n" + (name == null ? "" : name) + "\n" + (mime == null ? "" : mime);
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(K_DL + key, v).apply();
    }

    private void forgetSaved(String key) {
        if (key == null || key.isEmpty()) return;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(K_DL + key).apply();
    }

    /** 已经下过：直接交给系统里能打开它的 App，不再重新下载。 */
    private void openSaved(String key, Uri uri, TextView meta) {
        String mime = null;
        String name = null;
        String v = getSharedPreferences(PREFS, MODE_PRIVATE).getString(K_DL + key, null);
        if (v != null) {
            String[] f = v.split("\n", 3);
            if (f.length > 2) mime = f[2];
            if (f.length > 1) name = f[1];
        }
        if (mime == null || mime.isEmpty()) mime = mimeOf(name);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            // 没有 App 认这个类型，就退回「用系统解析器选」
            try {
                startActivity(Intent.createChooser(
                        new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "*/*")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        "打开 " + (name == null ? "文件" : name)));
            } catch (Exception e2) {
                // 真的没有能开的，就说清楚并允许重下一次
                forgetSaved(key);
                Toast.makeText(this, "没找到能打开 "
                        + (name == null ? "它" : name) + " 的 App", Toast.LENGTH_LONG).show();
                if (meta != null) {
                    meta.setText("没 App 能打开 · 点一下重新下载");
                }
            }
        }
    }

    /** 点附件：把它拉到本地 Downloads 里，成功/失败都给一句人话。 */
    private void downloadPart(final DshApi.Part p, final String key, final TextView meta) {
        final String org = origin();
        final String sid = currentId;
        if (sid == null) return;
        Toast.makeText(this, "开始下载 " + (p.name == null ? "" : p.name), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] data = null;
                if (p.localUri != null && !p.localUri.isEmpty()) {
                    // 这一轮刚发出去的，本地原件还在，直接读
                    data = readUri(Uri.parse(p.localUri));
                }
                if (data == null && p.kind == DshApi.Part.PRESENTED) {
                    data = DshApi.fileBytes(org, p.path);
                }
                // session/attachment 只收图片，非图片传过去服务端会直接拒
                if (data == null && p.kind == DshApi.Part.IMAGE && p.attachment != null) {
                    data = DshApi.attachmentBytes(org, sid, p.attachment);
                }
                // 非图片附件服务端没留读取通道（要绝对路径，历史里只有 sha256），只能提示重发
                final boolean noSource = data == null && p.kind != DshApi.Part.PRESENTED
                        && p.kind != DshApi.Part.IMAGE;
                final String name = (p.name == null || p.name.isEmpty())
                        ? ("dsh-" + System.currentTimeMillis()) : p.name;
                final Saved saved = data == null ? null : saveToDownloads(name, p.mediaType, data);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (saved == null) {
                            Toast.makeText(ChatActivity.this,
                                    noSource ? "这份老附件服务端不给读，重新发一次就能下"
                                            : "下载失败，检查一下 DSH 连接",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            // 记下来，下次点同一份就不再走网络，直接交给系统打开
                            rememberSaved(key, saved.uri, name, p.mediaType);
                            Toast.makeText(ChatActivity.this,
                                    "已保存到 " + saved.where, Toast.LENGTH_LONG).show();
                            if (meta != null) {
                                meta.setText("已下载 · 点一下打开");
                                meta.setTextColor(0xFF7C6BD6);
                            }
                        }
                    }
                });
            }
        }).start();
    }

    /** 落盘结果：留给用户看的位置 + 下次直接打开用的 Uri。 */
    private static final class Saved {
        Uri uri;
        String where;
    }

    /**
     * 落盘。Android 10 起走 MediaStore 的 Downloads（不需要存储权限），
     * 更老的系统只能放应用自己的外部目录。
     *
     * @return 失败返回 null
     */
    private Saved saveToDownloads(String name, String mime, byte[] data) {
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
                Saved s = new Saved();
                s.uri = uri;
                s.where = "下载/" + name;
                return s;
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
            Saved s = new Saved();
            s.uri = Uri.fromFile(f);
            s.where = f.getAbsolutePath();
            return s;
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
            // 本地 Uri 先挂上：图能立刻画出来，附件点下载时也有源可读
            if (p.uri != null) part.localUri = p.uri.toString();
            mine.parts.add(part);
        }
        pending.clear();
        renderPending();
        addMsg(mine);
        // 发出去的那条立刻落盘：等回复的空档切走再回来也还在
        ChatStore.append(this, currentId, mine);

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
                        for (DshApi.Msg m : got) {
                            addMsg(m);
                            ChatStore.append(ChatActivity.this, sid, m);
                        }
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
