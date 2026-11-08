package com.dsha.pet;

import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.IBinder;
import android.os.Looper;
import android.os.Vibrator;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.WebSettings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.util.Locale;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PetService extends Service {
    public static volatile String lastPoll = "未运行";
    /** 端点状态，供主界面显示。 */
    public static volatile String discoveryStatus = "未检测";
    /** 由主界面触发重新发现 / 深度扫描。 */
    public static volatile boolean rediscoverRequested = false;
    public static volatile boolean deepScanRequested = false;
    /** 服务是否在运行，供主界面判断按钮行为。 */
    public static volatile boolean running = false;

    public static void requestRediscover() {
        rediscoverRequested = true;
    }

    public static void requestDeepScan() {
        deepScanRequested = true;
    }
    private static final String DSHA_PKG = "dsh.android.client";

    private WindowManager wm;
    private WebView web;
    private WindowManager.LayoutParams lp;
    private Handler ui;
    private ExecutorService net;
    private volatile boolean alive = true;
    /** 当前端点；null 表示还没找到。 */
    private volatile DshEndpoint ep;
    private int pollFails;
    /** 找不到 DSH 时的退避时间，避免反复全量扫描。 */
    private long discoverBackoff = 5000;

    private float downX, downY;
    private float petScale = 1f;
    private int curAnimIdx = 0;
    private float dragDist;
    /** lp 坐标与 getLocationOnScreen 之间的固定偏移（状态栏），仅用于诊断。 */
    private int locOffX = Integer.MIN_VALUE, locOffY = Integer.MIN_VALUE;
    private int startLpX, startLpY;
    private long downTime;
    private long lastTapUp = 0;
    private long lastTouch = 0;
    private float lastTapX, lastTapY;
    private boolean moved;
    private final int slop = 12;
    private Runnable longPressRunnable;
    private boolean menuShown;
    private String lastBalanceText = "";
    private int homeX, homeY;
    private float anchorX = Float.NaN, anchorY = Float.NaN;
    private org.json.JSONObject actionsCache;

    // ---- 物理 / 好感度 ----
    private boolean flying;
    private float flyVx, flyVy;
    private long lastFrameTime;
    private VelocityTracker vt;
    private SharedPreferences prefs;
    private boolean linkMode;
    private static volatile PetService inst;
    /** 联动暂停（仅停轮询，桌宠本体照常） */
    private volatile boolean pausedLink = false;
    /** 自适应轮询间隔：亮屏 2s，灭屏 10s */
    private volatile int pollInterval = 2000;
    private int baseInterval = 5000;
    private boolean screenOn = true;
    private int beatTick = 0;
    private static final String ACTION_PAUSE = "com.dsha.pet.PAUSE";
    private static final String ACTION_STOP = "com.dsha.pet.STOP";
    private volatile boolean pluginMissing;
    private int affection;
    private Vibrator vib;
    private final Runnable resumeTick = new Runnable() {
        @Override
        public void run() {
            if (!alive) return;
            long now = SystemClock.uptimeMillis();
            if (!flying && menuView == null && now - lastTouch > 2500) {
                js("window.petResume && petResume()");
            }
            ui.postDelayed(this, 700);
        }
    };

    private final Runnable flyTick = new Runnable() {
        @Override
        public void run() {
            if (!flying || !alive) return;
            long now = System.nanoTime();
            float dt = lastFrameTime == 0 ? 0.016f : (now - lastFrameTime) / 1e9f;
            lastFrameTime = now;
            if (dt > 0.04f) dt = 0.04f;
            if (dt < 0.004f) dt = 0.004f;
            int sw = getResources().getDisplayMetrics().widthPixels;
            int sh = getResources().getDisplayMetrics().heightPixels;
            int minX = 0, maxX = Math.max(0, sw - lp.width);
            int minY = 0, maxY = Math.max(0, sh - lp.height);
            float x = lp.x + flyVx * dt;
            float y = lp.y + flyVy * dt;
            boolean hit = false;
            String dir = null;
            // 反射而不是简单钳位：保留多余位移，撞墙后的轨迹连续，不会“顿一下”
            if (x < minX) {
                x = minX + (minX - x); flyVx = -flyVx * 0.95f; hit = true; dir = "x";
            } else if (x > maxX) {
                x = maxX - (x - maxX); flyVx = -flyVx * 0.94f; hit = true; dir = "x";
            }
            if (y < minY) {
                y = minY + (minY - y); flyVy = -flyVy * 0.95f; hit = true; dir = dir == null ? "y" : "xy";
            } else if (y > maxY) {
                y = maxY - (y - maxY); flyVy = -flyVy * 0.94f; hit = true; dir = dir == null ? "y" : "xy";
            }
            x = Math.max(minX, Math.min(maxX, x));
            y = Math.max(minY, Math.min(maxY, y));
            if (hit) {
                // 墙只做反射，不补充能量：撞完照样按线性减速走
                // 撞墙：Q 弹反馈
                js("window.petBounce && petBounce(" + JSONObject.quote(dir) + ")");
                buzz(8);
                buzz(8);
            }
            int nx = Math.round(x), ny = Math.round(y);
            if (nx != lp.x || ny != lp.y) moveWindow(nx, ny);
            float sp = (float) Math.hypot(flyVx, flyVy);
            if (sp > 0f) {
                float ns = Math.max(0f, sp - FLY_DECEL * dt);
                float k = ns / sp;
                flyVx *= k;
                flyVy *= k;
                sp = ns;
            }
            if (sp < FLY_STOP) {
                flying = false;
                flyVx = 0;
                flyVy = 0;
                // 停稳后才解冻并抽一个随机动作；飞行/撞墙期间保持静默静帧
                js("window.petSettleRandom && petSettleRandom()");
            } else {
                web.postOnAnimation(this);
            }
        }
    };
    private View menuView;
    private View bubbleView;
    private int bubbleW, bubbleH;
    private String lastState = "";
    private Typeface cute;
    private String lastShownBalance = "";
    private long lastSayCounter = -1;
    private boolean sayInit = false;
    private String lastWhisperText = null;
    private long lastWhisperAt = 0;
    private final Runnable hideBubbleR = new Runnable() {
        @Override
        public void run() {
            hideBubbleNow();
        }
    };
    private final Runnable autoClose = new Runnable() {
        @Override
        public void run() {
            hideMenu();
        }
    };

    private interface Pick {
        void on(int i);
    }

    private long lastNotifyCounter = -1;

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    /** 联动开关（true=连接 DSH；false=只看桌宠）。 */
    public static boolean linkModeOn(android.content.Context c) {
        return c.getSharedPreferences("pet", android.content.Context.MODE_PRIVATE)
                .getBoolean("linkMode", true);
    }

    public static void setLinkMode(android.content.Context c, boolean on) {
        c.getSharedPreferences("pet", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("linkMode", on).apply();
        PetService p = inst;
        if (p != null) {
            p.linkMode = on;
            p.pluginMissing = false;
        }
        if (!on) discoveryStatus = "只看桌宠模式（不连 DSH）";
        else requestRediscover();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 启动时把构建与渲染参数写进日志，便于确认装的是哪一版、缩放基准对不对
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            logPoll("BOOT ver=" + pi.versionName + "(" + pi.versionCode + ")"
                    + " d=" + getResources().getDisplayMetrics().density
                    + " petScale=" + petScale + " K=" + K);
        } catch (Exception ignored) {
        }
        if (intent != null) {
            String a = intent.getAction();
            if (ACTION_STOP.equals(a)) {
                prefs.edit().putBoolean("enabled", false).apply();
                stopSelf();
                return START_NOT_STICKY;
            }
            if (ACTION_PAUSE.equals(a)) {
                pausedLink = !pausedLink;
                prefs.edit().putBoolean("pausedLink", pausedLink).apply();
                updateNotification();
                return START_STICKY;
            }
        }
        prefs.edit().putBoolean("enabled", true).apply();
        return START_STICKY;
    }

    private final BroadcastReceiver screenRx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(a)) {
                screenOn = true;
                pollInterval = baseInterval;
                try { web.onResume(); web.resumeTimers(); } catch (Exception ignored) {}
                js("window.petSleep && window.petSleep(false)");
            } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                screenOn = false;
                pollInterval = 10000;
                // 灭屏：网页停 rAF + 停定时器 + 视频暂停解码，三重停机
                js("window.petSleep && window.petSleep(true)");
                try { web.onPause(); web.pauseTimers(); } catch (Exception ignored) {}
            }
        }
    };

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        try {
            Intent i = new Intent(this, PetService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        DshHttp.init(this);
        float d = getResources().getDisplayMetrics().density;
        ui = new Handler(Looper.getMainLooper());
        net = Executors.newCachedThreadPool();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            cute = Typeface.create(Typeface.createFromAsset(getAssets(), "font/cute.ttf"), Typeface.BOLD);
        } catch (Exception ignored) {
        }
        prefs = getSharedPreferences("pet", MODE_PRIVATE);
        linkMode = prefs.getBoolean("linkMode", true);
        pausedLink = prefs.getBoolean("pausedLink", false);
        prefs.edit().putLong("lastBeat", System.currentTimeMillis()).apply();
        inst = this;
        ep = DshEndpoint.cached(this);
        DshEndpoint c0 = ep;
        discoveryStatus = c0 == null ? "正在查找 DSH…" : "上次端点 " + c0.host + ":" + c0.port;
        affection = prefs.getInt("affection", 0);
        petScale = Math.max(0.5f, Math.min(2f, prefs.getFloat("petScale", 1f)));
        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        vt = VelocityTracker.obtain();

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        web.addJavascriptInterface(new PetHost(), "PetHost");
        web.loadUrl("file:///android_asset/pet.html");

        loadBboxes();
        int[] r0 = rectFor(0);
        curRect = r0.clone();
        int w = Math.max(1, Math.round(r0[2] * K * d)), h = Math.max(1, Math.round(r0[3] * K * d));
        lp = new WindowManager.LayoutParams(
                w, h,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (int) (16 * d);
        lp.y = (int) (160 * d);
        homeX = lp.x;
        homeY = lp.y;

        longPressRunnable = new Runnable() {
            @Override
            public void run() {
                if (moved || !alive) return;
                js("window.petSquash && petSquash()");
                buzz(28);
                menuShown = true;
                showMenu();
            }
        };

        web.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        // 飞行中按一下 = 接住
                        if (flying) {
                            flying = false;
                            ui.removeCallbacks(flyTick);
                            flyVx = 0;
                            flyVy = 0;
                            js("window.petResume && petResume()");
                            js("window.petSquash && petSquash()");
                            showBubble("接住啦～", 2200);
                            buzz(35);
                            addAffection(2, "♥ 接住 +2");
                            buzz(25);
                            return true;
                        }
                        hideMenu();
                        hideBubbleNow();
                        js("window.petFreeze && petFreeze()");
                        lastTouch = SystemClock.uptimeMillis();
                        downX = e.getRawX();
                        downY = e.getRawY();
                        dragDist = 0f;
                        downTime = e.getEventTime();
                        startLpX = lp.x;
                        startLpY = lp.y;
                        moved = false;
                        menuShown = false;
                        ui.removeCallbacks(longPressRunnable);
                        vt.clear();
                        vt.addMovement(e);
                        ui.postDelayed(longPressRunnable, 500);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        vt.addMovement(e);
                        float dx = e.getRawX() - downX;
                        float dy = e.getRawY() - downY;
                        dragDist = Math.max(dragDist, (float) Math.hypot(dx, dy));
                        if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                            moved = true;
                            ui.removeCallbacks(longPressRunnable);
                        }
                        if (moved && dragDist > 12 * d) {
                            lastTouch = SystemClock.uptimeMillis();
                            moveWindow(clampX(startLpX + (int) dx), clampY(startLpY + (int) dy));
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        ui.removeCallbacks(longPressRunnable);
                        lastTouch = SystemClock.uptimeMillis();
                        vt.addMovement(e);
                        vt.computeCurrentVelocity(1000, 5200f);
                        float vx = vt.getXVelocity();
                        float vy = vt.getYVelocity();
                        vt.clear();
                        if (moved) {
                            // 真甩出去要满足：拖动距离够长 + 松手速度够快；轻点/微动只当点击
                            if (dragDist > 24 * d && Math.hypot(vx, vy) > 1400) {
                                startFly(vx, vy);
                                return true;
                            }
                            // 普通拖动松手：回静默待机（不随机抽动作）
                            js("window.petSettle && petSettle()");
                        }
                        if (menuShown) {
                            js("window.petResume && petResume()");
                            return true;
                        }
                        long now = System.currentTimeMillis();
                        boolean dbl = now - lastTapUp < 400
                                && Math.abs(e.getRawX() - lastTapX) < 80 * d
                                && Math.abs(e.getRawY() - lastTapY) < 80 * d;
                        if (dbl) {
                            lastTapUp = 0;
                            openDsha();
                        } else {
                            lastTapUp = now;
                            lastTapX = e.getRawX();
                            lastTapY = e.getRawY();
                            if (web != null) {
                                web.evaluateJavascript(
                                        "window.petSquash && petSquash(); window.petTap && petTap()", null);
                            }
                            addAffection(1, null);
                            ui.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    js("window.petResume && petResume()");
                                }
                            }, 1200);
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });

        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }

        try {
            IntentFilter sf = new IntentFilter();
            sf.addAction(Intent.ACTION_SCREEN_ON);
            sf.addAction(Intent.ACTION_SCREEN_OFF);
            registerReceiver(screenRx, sf);
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            pollInterval = (pm != null && pm.isInteractive()) ? 2000 : 10000;
        } catch (Exception ignored) {
        }
        scheduleWatchdog();
        wm.addView(web, lp);
        ui.postDelayed(resumeTick, 800);
        final Thread poller = new Thread(new Runnable() {
            @Override
            public void run() {
                pollLoop();
            }
        }, "pet-poll");
        poller.setDaemon(true);
        poller.start();
    }

    private void logPoll(String text) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) return;
            File f = new File(dir, "poll.log");
            if (f.length() > 200000) f.delete();
            FileWriter w = new FileWriter(f, true);
            w.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
                    .format(new java.util.Date()) + " " + text + "\n");
            w.close();
        } catch (Exception ignored) {
        }
    }

    private static final float K = 150f / 640f;
    private static final float FLY_DECEL = 480f; // px/s²，线性减速
    private static final float FLY_STOP = 40f;
    private JSONArray bboxes;
    private JSONArray bodies;
    private int[] curRect = {0, 0, 640, 360};

    private void loadBboxes() {
        try {
            java.io.InputStream in = getAssets().open("bbox.json");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            bboxes = new JSONArray(new String(bo.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            bboxes = null;
        }
        try {
            java.io.InputStream in = getAssets().open("body.json");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            bodies = new JSONArray(new String(bo.toByteArray(), "UTF-8"));
        } catch (Exception e) {
            bodies = null;
        }
    }

    // 宠物本体（alpha 非透明）包围盒：每个动作的“模型”真实位置与大小
    private int[] bodyFor(int idx) {
        if (bodies != null && idx >= 0 && idx < bodies.length()) {
            try {
                JSONArray a = bodies.getJSONArray(idx);
                int w = a.getInt(2), h = a.getInt(3);
                if (w > 8 && h > 8) return new int[]{a.getInt(0), a.getInt(1), w, h};
            } catch (Exception ignored) {
            }
        }
        return rectFor(idx);
    }

    private int[] rectFor(int idx) {
        if (bboxes != null && idx >= 0 && idx < bboxes.length()) {
            try {
                JSONArray a = bboxes.getJSONArray(idx);
                return new int[]{a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3)};
            } catch (Exception ignored) {
            }
        }
        return new int[]{0, 0, 640, 360};
    }

    public class PetHost {
        @JavascriptInterface
        public void log(String m) {
            logPoll("JS " + m);
        }

        @JavascriptInterface
        public void setAnim(final int idx) {
            // 只记住目标动作，不动窗口：等新画面真的解码出帧后再整体切（见 applyBox）
            ui.post(new Runnable() {
                @Override
                public void run() {
                    curAnimIdx = idx;
                }
            });
        }

        // 网页那边已经拿到新帧，现在才把窗口几何切过去；同一次布局里网页会顺手换画面
        @JavascriptInterface
        public void applyBox(final int idx) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    curAnimIdx = idx;
                    applyAnimBox(idx);
                    // 网页不再依赖 resize 事件（绘制用等比包含，窗口慢一拍也只缩不放），
                    // 这里只做个回执，方便日志对时
                    js("window.petBoxReady && window.petBoxReady(" + idx + ")");
                }
            });
        }
    }

    private boolean applyAnimBox(int idx) {
        if (!alive || lp == null) return true;
        int[] r = rectFor(idx);
        float d = getResources().getDisplayMetrics().density;
        float PADW = 1.4f;
        // 宠物缩放：只影响模型窗口，气泡/菜单是独立窗口不受影响
        float sc = petScale;
        float maxW = getResources().getDisplayMetrics().widthPixels * 0.92f
                / Math.max(1f, r[2] * K * d * PADW);
        float maxH = getResources().getDisplayMetrics().heightPixels * 0.92f
                / Math.max(1f, r[3] * K * d * PADW);
        sc = Math.min(sc, Math.min(maxW, maxH));
        int nw = Math.max(1, Math.round(r[2] * K * d * PADW * sc));
        int nh = Math.max(1, Math.round(r[3] * K * d * PADW * sc));
        // 画布内缩比例：与 pet.html 里的 pad 保持一致
        float PAD = 1f / 7f;
        float cw = Math.max(1, curRect[2]);
        float ch = Math.max(1, curRect[3]);
        float oldRx = PAD + (320 - curRect[0]) * (1 - 2 * PAD) / cw;
        float oldRy = PAD + (200 - curRect[1]) * (1 - 2 * PAD) / ch;
        if (Float.isNaN(anchorX)) {
            anchorX = lp.x + oldRx * lp.width;
            anchorY = lp.y + oldRy * lp.height;
        }
        float newRx = PAD + (320 - r[0]) * (1 - 2 * PAD) / Math.max(1, r[2]);
        float newRy = PAD + (200 - r[1]) * (1 - 2 * PAD) / Math.max(1, r[3]);
        int nx = Math.round(anchorX - newRx * nw);
        int ny = Math.round(anchorY - newRy * nh);
        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;
        int px = Math.max(0, Math.min(sw - nw, nx));
        int py = Math.max(0, Math.min(sh - nh, ny));
        boolean same = (lp.width == nw && lp.height == nh && lp.x == px && lp.y == py);
        lp.width = nw;
        lp.height = nh;
        lp.x = px;
        lp.y = py;
        anchorX = px + newRx * nw;
        anchorY = py + newRy * nh;
        curRect = r;
        logPoll("BOX idx=" + idx + " r=" + r[0] + "," + r[1] + "," + r[2] + "," + r[3]
                + " win=" + nw + "x" + nh + " at " + px + "," + py);
        if (!same) wm.updateViewLayout(web, lp);
        js("window.petBox && window.petBox(" + r[0] + "," + r[1] + "," + r[2] + "," + r[3] + ")");
        return same;
    }

    private void moveWindow(int x, int y) {
        if (lp == null || web == null) return;
        if (!Float.isNaN(anchorX)) {
            anchorX += x - lp.x;
            anchorY += y - lp.y;
        }
        lp.x = x;
        lp.y = y;
        wm.updateViewLayout(web, lp);
    }

    private void getJson(final String url, final StrCb cb) {
        net.execute(new Runnable() {
            @Override
            public void run() {
                String out;
                try {
                    out = httpGet(url);
                } catch (Exception e) {
                    out = null;
                }
                final String res = out;
                logPoll("POST " + url + " -> " + (res == null ? "null" : res.substring(0, Math.min(70, res.length()))));
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) cb.on(res);
                    }
                });
            }
        });
    }

    private static String totalFromState(String body) {
        if (body == null) return null;
        try {
            JSONObject root = new JSONObject(body);
            JSONObject sections = root.optJSONObject("sections");
            if (sections == null) return null;
        JSONObject bal = sections.optJSONObject("balance");
            if (bal == null) return null;
            JSONObject data = bal.optJSONObject("data");
            if (data == null || !data.optBoolean("ok", false)) return null;
            JSONObject inner = data.optJSONObject("data");
            if (inner == null) return null;
            JSONArray wallets = inner.optJSONArray("wallets");
            if (wallets == null || wallets.length() == 0) return null;
            return wallets.getJSONObject(0).optString("total", null);
        } catch (Exception e) {
            return null;
        }
    }

    private void refreshBalance(final BalanceCb cb) {
        postJson(base() + "/balance", null, new StrCb() {
            @Override
            public void on(String b) {
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        getJson(base() + "/state", new StrCb() {
                            @Override
                            public void on(String body) {
                                if (body == null || body.trim().isEmpty()) {
                                    if (cb != null) cb.on(null, null, false);
                                    return;
                                }
                                String t = totalFromState(body);
                                String provider = providerFromState(body);
                                if (t != null) {
                                    lastBalanceText = t;
                                    lastShownBalance = t;
                                }
                                if (cb != null) cb.on(t, provider, true);
                            }
                        });
                    }
                }, 1000);
            }
        });
    }

    /** 从 /state 的 sections.balance.data.provider 取供应商标识。 */
    private String providerFromState(String body) {
        try {
            JSONObject root = new JSONObject(body);
            JSONObject sec = root.optJSONObject("sections");
            if (sec == null) return null;
            JSONObject bal = sec.optJSONObject("balance");
            if (bal == null) return null;
            JSONObject data = bal.optJSONObject("data");
            if (data == null) return null;
            String p = data.optString("provider", null);
            return (p == null || p.trim().isEmpty()) ? null : p;
        } catch (Exception e) {
            return null;
        }
    }

    /** 把插件暴露的 provider 标识翻成用户能看懂的名字（插件只给 provider，不给具体模型 id）。 */
    private String modelLabel(String provider) {
        if (provider == null) return "当前模型";
        String p = provider.toLowerCase(Locale.US);
        if (p.contains("deepseek-official")) return "DeepSeek 官方";
        if (p.contains("deepseek-account")) return "DeepSeek 账号";
        if (p.contains("anthropic") || p.contains("claude")) return "Claude";
        if (p.contains("openai") || p.contains("gpt")) return "OpenAI";
        if (p.contains("google") || p.contains("gemini")) return "Gemini";
        return provider;
    }

    private static final String[] SAD_EMOJI = {"🥰", "🥺", "😢", "💦", "🫠"};

    private String pickSadEmoji() {
        return SAD_EMOJI[(int) (Math.random() * SAD_EMOJI.length)];
    }

    private void js(String code) {
        try {
            web.evaluateJavascript(code, null);
        } catch (Exception ignored) {
        }
    }

    private org.json.JSONObject loadActions() {
        if (actionsCache != null) return actionsCache;
        try {
            java.io.InputStream in = getAssets().open("actions.json");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            actionsCache = new org.json.JSONObject(bo.toString("UTF-8"));
        } catch (Exception ignored) {
            actionsCache = new org.json.JSONObject();
        }
        return actionsCache;
    }

    private void hideMenu() {
        ui.removeCallbacks(autoClose);
        if (menuView != null) {
            try {
                wm.removeView(menuView);
            } catch (Exception ignored) {
            }
            menuView = null;
        }
    }

    private void showPage(String title, String[] labels, final Pick pick) {
        hideMenu();
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF02B2B30);
        bg.setCornerRadius(14 * d);
        bg.setStroke((int) (1 * d), 0x33FFFFFF);
        box.setBackground(bg);
        box.setPadding((int) (6 * d), (int) (6 * d), (int) (6 * d), (int) (6 * d));
        if (title != null) {
            TextView t = new TextView(this);
            t.setText(title);
            t.setTextColor(0xFFAAAAAA);
            t.setTextSize(10);
            if (cute != null) t.setTypeface(cute);
            t.setPadding((int) (10 * d), (int) (4 * d), (int) (10 * d), (int) (4 * d));
            box.addView(t);
        }
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView tv = new TextView(this);
            tv.setText(labels[i]);
            tv.setTextColor(0xFFFFFFFF);
            tv.setTextSize(12f);
            if (cute != null) tv.setTypeface(cute);
            tv.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
            tv.setClickable(true);
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pick.on(idx);
                }
            });
            box.addView(tv);
        }
        WindowManager.LayoutParams mlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        mlp.gravity = Gravity.TOP | Gravity.START;
        box.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int bw = box.getMeasuredWidth();
        int bh = box.getMeasuredHeight();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int mx = lp.x + lp.width + (int) (6 * d);
        if (mx + bw > screenW) mx = Math.max(0, lp.x - bw - (int) (6 * d));
        int my = Math.min(lp.y, Math.max(0, screenH - bh - (int) (60 * d)));
        mlp.x = mx;
        mlp.y = my;
        box.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    hideMenu();
                    return true;
                }
                return false;
            }
        });
        menuView = box;
        wm.addView(box, mlp);
        ui.postDelayed(autoClose, 12000);
    }

    /** DSHA（DeepSeek Harness Android）是否安装。三个联动功能依赖它。 */
    private boolean hasDsha() {
        try {
            getPackageManager().getPackageInfo("dsh.android.client", 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void showMenu() {
        final boolean dsha = hasDsha() && linkMode && !pluginMissing;
        java.util.ArrayList<String> ls = new java.util.ArrayList<>();
        final java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
        if (dsha) {
            ls.add("打开 DSH"); ids.add(0);
            ls.add("查看余额"); ids.add(1);
            ls.add("碎碎念"); ids.add(2);
            ls.add("对话"); ids.add(3);
        }
        ls.add("动作 ▸"); ids.add(4);
        ls.add("大小 ▸"); ids.add(5);
        ls.add("插件 ▸"); ids.add(6);
        ls.add("回到初始位置"); ids.add(7);
        ls.add("好感度"); ids.add(8);
        ls.add("退出桌宠"); ids.add(9);
        if (!dsha) {
            ls.add("获取 DSHA ↓"); ids.add(10);
        }
        final String[] labels = ls.toArray(new String[0]);
        showPage("大肥鱼 · 好感 Lv." + heartLevel(), labels,
                new Pick() {
                    @Override
                    public void on(int i) {
                        int act = (i >= 0 && i < ids.size()) ? ids.get(i) : -1;
                        switch (act) {
                            case 0:
                                hideMenu();
                                openDsha();
                                break;
                            case 1:
                                hideMenu();
                                showBubble("查一下余额…", 1600);
                                refreshBalance(new BalanceCb() {
                                    @Override
                                    public void on(String t, String provider, boolean reachable) {
                                        if (t != null) {
                                            showBubble("余额 ¥" + t, 6000);
                                        } else if (!reachable) {
                                            showBubble("DSH 没连上，先打开 DSHA 吧🥺", 4000);
                                        } else {
                                            showBubble("无法查询「" + modelLabel(provider) + "」的余额" + pickSadEmoji(), 5200);
                                        }
                                    }
                                });
                                break;
                            case 2:
                                hideMenu();
                                doWhisper(true);
                                break;
                            case 3:
                                hideMenu();
                                showBubble("去 DSH 里跟我说话吧～", 2200);
                                openDsha();
                                break;
                            case 4:
                                showActions();
                                break;
                            case 5:
                                showSize();
                                break;
                            case 6:
                                showPlugin();
                                break;
                            case 7:
                                hideMenu();
                                flying = false;
                                ui.removeCallbacks(flyTick);
                                flyVx = 0;
                                flyVy = 0;
                                anchorX = Float.NaN;
                                anchorY = Float.NaN;
                                moveWindow(homeX, homeY);
                                break;
                            case 8:
                                hideMenu();
                                showBubble("♥ 好感 Lv." + heartLevel() + "  " + affection + " 点", 3000);
                                break;
                            case 9:
                                hideMenu();
                                stopSelf();
                                break;
                            case 10:
                                hideMenu();
                                openDshaDownload();
                                break;
                            default:
                                break;
                        }
                    }
                });
    }

    /** 没装 DSHA 时，引导去 GitHub 下载。 */
    private void openDshaDownload() {
        try {
            Intent it = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/DSH-APP/DSHA"));
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
        } catch (Exception e) {
            showBubble("浏览器没打开，去 GitHub 搜 DSH-APP/DSHA", 3200);
        }
    }

    private void doWhisper(final boolean retry) {
        showBubble("让我想想…", 1800);
        postJson(base() + "/whisper?pet=main&surface=web", null, new StrCb() {
            @Override
            public void on(String b) {
                if (b == null) {
                    showBubble("碎碎念没发出去…DSH 没响应", 2600);
                    return;
                }
                String t = null;
                String why = null;
                try {
                    JSONObject o = new JSONObject(b);
                    t = o.optString("text", null);
                    if (!o.optBoolean("ok", false)) {
                        why = o.optString("message", o.optString("reason", null));
                    }
                } catch (Exception ignored) {
                }
                if (t != null && !t.isEmpty()) {
                    lastWhisperText = t;
                    lastWhisperAt = System.currentTimeMillis();
                    showBubble(t, 7000);
                    return;
                }
                // 显示模式不是 web/both 时服务端会拒绝，自动改成 both 再重试一次
                if (retry && b.contains("pet-not-displayed")) {
                    postJson(base() + "/pet-display", "{\"id\":\"main\",\"display\":\"both\"}",
                            new StrCb() {
                                @Override
                                public void on(String r) {
                                    doWhisper(false);
                                }
                            });
                    return;
                }
                if (why != null) {
                    showBubble("碎碎念失败：" + why, 3200);
                } else {
                    showBubble(FALLBACK_WHISPER[(int) (Math.random() * FALLBACK_WHISPER.length)], 6000);
                }
            }
        });
    }

    private void showSize() {
        hideMenu();
        final float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF02B2B30);
        bg.setCornerRadius(14 * d);
        bg.setStroke((int) (1 * d), 0x33FFFFFF);
        box.setBackground(bg);
        box.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));

        final TextView title = new TextView(this);
        title.setText("大小 · " + Math.round(petScale * 100) + "%");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(12f);
        if (cute != null) title.setTypeface(cute);
        box.addView(title);

        final SeekBar sb = new SeekBar(this);
        sb.setMax(150);
        sb.setProgress(Math.round((petScale - 0.5f) * 100));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                (int) (210 * d), LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = (int) (2 * d);
        sb.setLayoutParams(slp);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser) return;
                petScale = 0.5f + p / 100f;
                title.setText("大小 · " + Math.round(petScale * 100) + "%");
                applyAnimBox(curAnimIdx);
                ui.removeCallbacks(autoClose);
                ui.postDelayed(autoClose, 12000);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putFloat("petScale", petScale).apply();
            }
        });
        box.addView(sb);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView reset = new TextView(this);
        reset.setText("重置 100%");
        reset.setTextColor(0xFFBBBBBB);
        reset.setTextSize(11f);
        if (cute != null) reset.setTypeface(cute);
        reset.setPadding(0, (int) (6 * d), (int) (24 * d), (int) (4 * d));
        reset.setClickable(true);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                petScale = 1f;
                sb.setProgress(100);
                title.setText("大小 · 100%");
                applyAnimBox(curAnimIdx);
                prefs.edit().putFloat("petScale", petScale).apply();
            }
        });
        TextView done = new TextView(this);
        done.setText("完成");
        done.setTextColor(0xFFFFFFFF);
        done.setTextSize(11f);
        if (cute != null) done.setTypeface(cute);
        done.setPadding((int) (24 * d), (int) (6 * d), 0, (int) (4 * d));
        done.setClickable(true);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideMenu();
            }
        });
        row.addView(reset);
        row.addView(done);
        box.addView(row);

        WindowManager.LayoutParams mlp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        mlp.gravity = Gravity.TOP | Gravity.START;
        box.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int bw = box.getMeasuredWidth();
        int bh = box.getMeasuredHeight();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int mx = lp.x + lp.width + (int) (6 * d);
        if (mx + bw > screenW) mx = Math.max(0, lp.x - bw - (int) (6 * d));
        int my = Math.min(lp.y, Math.max(0, screenH - bh - (int) (60 * d)));
        mlp.x = mx;
        mlp.y = my;
        box.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    hideMenu();
                    return true;
                }
                return false;
            }
        });
        menuView = box;
        wm.addView(box, mlp);
        ui.postDelayed(autoClose, 12000);
    }

    /** 插件：看一眼数据源状态，或一键把 DSH 页面里的宠物藏掉（只当数据源）。 */
    private void showPlugin() {
        showPage("数据插件", new String[]{"‹ 返回", "检查插件状态", "设为都不显示"},
                new Pick() {
                    @Override
                    public void on(int i) {
                        if (i == 0) {
                            showMenu();
                            return;
                        }
                        hideMenu();
                        if (i == 1) {
                            showBubble("查一下插件…", 1500);
                            getJson(base() + "/config", new StrCb() {
                                @Override
                                public void on(String body) {
                                    showBubble(describeDisplay(body), 6500);
                                }
                            });
                        } else {
                            postJson(base() + "/pet-display", "{\"id\":\"main\",\"display\":\"none\"}",
                                    new StrCb() {
                                        @Override
                                        public void on(String b) {
                                            showBubble(b == null
                                                    ? "没改成：插件没响应（先确认 DSHA 在跑）"
                                                    : "已设为不显示，DSH 页面里不会再冒出宠物", 4800);
                                        }
                                    });
                        }
                    }
                });
    }

    /** 把 /config 里的 display 值翻成人话。 */
    private String describeDisplay(String body) {
        if (body == null) return "插件没响应，先确认 DSHA 和 dsh-pet 插件都在跑";
        try {
            JSONObject main = new JSONObject(body).optJSONObject("main");
            JSONArray pets = main == null ? null : main.optJSONArray("pets");
            String disp = "none";
            if (pets != null && pets.length() > 0) {
                JSONObject p0 = pets.optJSONObject(0);
                if (p0 != null) disp = p0.optString("display", "none");
            }
            String cn;
            if ("none".equals(disp)) cn = "都不显示（推荐，只当数据源）";
            else if ("web".equals(disp)) cn = "仅网页，DSH 页面里也会冒出宠物";
            else if ("desktop".equals(disp)) cn = "仅桌面，网页端拿不到碎碎念";
            else cn = "网页和桌面都显示";
            return "插件正常 · " + cn;
        } catch (Exception e) {
            return "插件路由在，但配置读不出来：" + e;
        }
    }

    /** 宠物旁边的独立小气泡（不压在宠物身上），自动避让屏幕边缘。 */
    private void showBubble(final String text, final int ms) {
        if (text == null || text.length() == 0 || !alive) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (!alive) return;
                try {
                    ui.removeCallbacks(hideBubbleR);
                    hideBubbleNow();
                    TextView tv = new TextView(PetService.this);
                    tv.setText(text);
                    tv.setTextColor(0xFF2B2B30);
                    tv.setTextSize(12f);
                    if (cute != null) tv.setTypeface(cute);
                    tv.setLineSpacing(0, 1.15f);
                    float d = getResources().getDisplayMetrics().density;
                    int padH = (int) (11 * d), padV = (int) (8 * d);
                    tv.setPadding(padH, padV, padH, padV);
                    tv.setMaxWidth((int) (230 * d));
                    GradientDrawable g = new GradientDrawable();
                    g.setColor(0xFFFFFDF6);
                    g.setCornerRadius(15 * d);
                    g.setStroke(Math.max(1, (int) d), 0x22000000);
                    tv.setBackground(g);
                    tv.measure(View.MeasureSpec.makeMeasureSpec((int) (230 * d), View.MeasureSpec.AT_MOST),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    bubbleW = tv.getMeasuredWidth();
                    bubbleH = tv.getMeasuredHeight();
                    WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.WRAP_CONTENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT);
                    p.gravity = Gravity.TOP | Gravity.START;
                    p.x = bubbleX();
                    p.y = bubbleY();
                    wm.addView(tv, p);
                    bubbleView = tv;
                    ui.postDelayed(hideBubbleR, ms <= 0 ? 3000 : ms);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private int bubbleX() {
        int sw = getResources().getDisplayMetrics().widthPixels;
        int x = lp.x + (lp.width - bubbleW) / 2;
        return Math.max(0, Math.min(Math.max(0, sw - bubbleW), x));
    }

    private int bubbleY() {
        int gap = (int) (5 * getResources().getDisplayMetrics().density);
        int y = lp.y - bubbleH - gap;
        if (y < 0) y = lp.y + lp.height + gap;
        int sh = getResources().getDisplayMetrics().heightPixels;
        return Math.max(0, Math.min(Math.max(0, sh - bubbleH), y));
    }

    private void placeBubble() {
        if (bubbleView == null) return;
        try {
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) bubbleView.getLayoutParams();
            p.x = bubbleX();
            p.y = bubbleY();
            wm.updateViewLayout(bubbleView, p);
        } catch (Exception ignored) {
        }
    }

    private void hideBubbleNow() {
        if (bubbleView != null) {
            try {
                wm.removeView(bubbleView);
            } catch (Exception ignored) {
            }
            bubbleView = null;
        }
    }

    private static final String[] FALLBACK_WHISPER = {
            "主人在忙什么呀，我就在旁边看着～",
            "有点困了…让我趴一会儿",
            "今天也要加油鸭！",
            "偷偷看你一眼，嘿嘿",
            "要不要摸摸我的头？",
            "碎碎念一句：记得喝水呀",
            "我在这儿呢，别把我忘了～",
            "桌面好安静，陪我玩会儿吧"
    };

    private static final String[][] WORK_TEXTS = {
            {"正在认真想下一步呢", "让我整理一下思路~", "正在梳理接下来要做什么"},
            {"正在处理任务呢", "这一步正在进行中哦", "正在继续干活呢，别打扰我~"},
            {"这一步处理好啦，接着看下一步", "搞定一步，继续看看哦~"},
            {"需要你确认一下呢", "这里要等你看一下哦", "轮到你来决定啦，等你~"},
            {"这一轮搞定啦，真棒！", "任务完成咯，可以摸鱼啦~"},
            {"这一步好像没跑通呢", "刚才的操作遇到点问题哦", "这里卡了一下，再等等你呢"}
    };

    private static String pickWork(int i) {
        if (i < 0 || i >= WORK_TEXTS.length) return null;
        String[] a = WORK_TEXTS[i];
        return a[(int) (Math.random() * a.length) % a.length];
    }

    private static final String[] WORK_KEYS = {"thinking", "working", "result", "waiting", "success", "error"};

    private void toastJs(String text) {
        showBubble(text, 2600);
    }

    private int clampX(int x) {
        int max = Math.max(0, getResources().getDisplayMetrics().widthPixels - lp.width);
        return Math.max(0, Math.min(max, x));
    }

    private int clampY(int y) {
        int max = Math.max(0, getResources().getDisplayMetrics().heightPixels - lp.height);
        return Math.max(0, Math.min(max, y));
    }

    /** 松手甩出去：带阻尼的惯性飞行，撞到屏幕边反弹并 Q 弹一下。 */
    private void startFly(float vx, float vy) {
        hideBubbleNow();
        js("window.petFreeze && petFreeze()");
        flying = true;
        flyVx = Math.max(-2200f, Math.min(2200f, vx));
        flyVy = Math.max(-2200f, Math.min(2200f, vy));
        lastFrameTime = System.nanoTime();
        web.postOnAnimation(flyTick);
    }

    private void buzz(int ms) {
        try {
            if (vib != null && vib.hasVibrator()) vib.vibrate(ms);
        } catch (Exception ignored) {
        }
    }

    private int heartLevel() {
        return 1 + affection / 20;
    }

    private void addAffection(int n, String text) {
        affection += n;
        if (affection < 0) affection = 0;
        try {
            prefs.edit().putInt("affection", affection).apply();
        } catch (Exception ignored) {
        }
        if (text != null) {
            showBubble(text + "  Lv." + heartLevel(), 2600);
        } else if (affection % 10 == 0) {
            showBubble("♥ 好感 Lv." + heartLevel() + "  " + affection, 2600);
        }
    }

    private interface StrCb {
        void on(String body);
    }

    /** 余额查询回调：total 为空时用 reachable 区分“没连上”和“连上了但查不到”。 */
    private interface BalanceCb {
        void on(String total, String provider, boolean reachable);
    }

    /** 后台 POST（body 可为 null），结果回主线程。 */
    private void postJson(final String url, final String body, final StrCb cb) {
        net.execute(new Runnable() {
            @Override
            public void run() {
                String out = null;
                try {
                    out = httpPost(url, body);
                } catch (Exception e) {
                    out = "ERR " + e.getClass().getSimpleName();
                }
                final String res = out;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) cb.on(res);
                    }
                });
            }
        });
    }


    private void showActions() {
        final org.json.JSONObject root = loadActions();
        final java.util.List<String> cats = new ArrayList<>();
        Iterator<String> it = root.keys();
        while (it.hasNext()) cats.add(it.next());
        if (cats.isEmpty()) return;
        String[] labels = new String[cats.size() + 1];
        labels[0] = "‹ 返回";
        for (int i = 0; i < cats.size(); i++) labels[i + 1] = cats.get(i);
        showPage("换个动作", labels, new Pick() {
            @Override
            public void on(int i) {
                if (i == 0) {
                    showMenu();
                    return;
                }
                final String cat = cats.get(i - 1);
                final JSONArray arr = root.optJSONArray(cat);
                if (arr == null || arr.length() == 0) return;
                final String[] names = new String[arr.length() + 1];
                names[0] = "‹ 返回";
                for (int k = 0; k < arr.length(); k++) names[k + 1] = arr.optString(k, "");
                showPage(cat, names, new Pick() {
                    @Override
                    public void on(int j) {
                        if (j == 0) {
                            showActions();
                            return;
                        }
                        hideMenu();
                        js("window.petPlay && petPlay(" + JSONObject.quote(names[j]) + ")");
                    }
                });
            }
        });
    }

    private void openDsha() {
        String err = null;
        try {
            Intent q = new Intent(Intent.ACTION_MAIN);
            q.addCategory(Intent.CATEGORY_LAUNCHER);
            q.setPackage(DSHA_PKG);
            List<ResolveInfo> list = getPackageManager().queryIntentActivities(q, 0);
            if (list != null && !list.isEmpty()) {
                ActivityInfo ai = list.get(0).activityInfo;
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                i.setComponent(new ComponentName(ai.packageName, ai.name));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            }
            err = "没有找到启动入口";
        } catch (Exception e) {
            err = e.getClass().getSimpleName();
        }
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(DSHA_PKG);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return;
            }
        } catch (Exception e) {
            err = e.getClass().getSimpleName();
        }
        if (web != null) web.evaluateJavascript("window.petTap && petTap()", null);
        final String msg = err == null ? "打不开 DSHA" : "打不开 DSHA：" + err;
        ui.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(PetService.this, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void pollLoop() {
        while (alive) {
            if ((++beatTick % 5) == 0) {
                prefs.edit().putLong("lastBeat", System.currentTimeMillis()).apply();
            }
            if (pausedLink) {
                discoveryStatus = "联动已暂停（点通知恢复）";
                lastPoll = "已暂停";
                sleepQuiet(2000);
                continue;
            }
            if (!linkMode) {
                discoveryStatus = "只看桌宠模式（不连 DSH）";
                lastPoll = "只看桌宠";
                sleepQuiet(2000);
                continue;
            }
            boolean deep = false;
            if (rediscoverRequested) {
                rediscoverRequested = false;
                ep = null;
                pollFails = 0;
                DshEndpoint.invalidate(this);
            }
            if (deepScanRequested) {
                deepScanRequested = false;
                deep = true;
                ep = null;
                pollFails = 0;
                DshEndpoint.invalidate(this);
            }

            if (ep == null) {
                discoveryStatus = "正在查找 DSH…";
                DshEndpoint found = DshEndpoint.resolveBlocking(this, deep, new DshEndpoint.Listener() {
                    @Override
                    public void onProgress(String message) {
                        discoveryStatus = message;
                    }
                });
                if (found == null) {
                    String authErr = DshHttp.lastAuthError;
                    if (DshEndpoint.lastHint != null) {
                        discoveryStatus = DshEndpoint.lastHint;
                    } else if (authErr != null) {
                        discoveryStatus = authErr;
                    } else if (DshEndpoint.openPortsSummary() != null) {
                        discoveryStatus = "未找到 DSH；本机开放端口：" + DshEndpoint.openPortsSummary();
                    } else {
                        discoveryStatus = "未找到 DSH，请先打开 DSHA（DSHA 首页显示实际端口）";
                    }
                    pluginMissing = discoveryStatus != null && discoveryStatus.contains("插件没加载");
                    lastPoll = "未连接";
                    sleepQuiet(discoverBackoff);
                    discoverBackoff = Math.min(discoverBackoff * 2, 60000);
                    continue;
                }
                discoverBackoff = 5000;
                ep = found;
                discoveryStatus = "已连接 " + found.host + ":" + found.port + "（" + found.source + "）";
                logPoll("endpoint -> " + found.host + ":" + found.port + " (" + found.source + ")");
                pollFails = 0;
                pluginMissing = false;
            }

            try {
                String body = httpGet(ep.url("/state"));
                if (body != null) {
                    lastPoll = "OK " + body.length() + "B";
                    logPoll(lastPoll);
                    pollFails = 0;
                    handleState(body);
                } else {
                    lastPoll = "HTTP 非 200";
                    logPoll(lastPoll);
                    if (++pollFails >= 3) {
                        ep = null;
                        pollFails = 0;
                        DshEndpoint.invalidate(this);
                    }
                }
            } catch (Exception e) {
                lastPoll = "ERR " + e;
                java.io.StringWriter sw = new java.io.StringWriter();
                e.printStackTrace(new java.io.PrintWriter(sw));
                logPoll(sw.toString());
                if (++pollFails >= 3) {
                    ep = null;
                    pollFails = 0;
                    DshEndpoint.invalidate(this);
                }
            }
            sleepQuiet(pollInterval);
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** 当前请求基址；没找到端点时退回缓存或默认端口。 */
    private String base() {
        DshEndpoint e = ep;
        if (e != null) return e.base();
        DshEndpoint c = DshEndpoint.cached(this);
        if (c != null) return c.base();
        return "http://127.0.0.1:" + DshEndpoint.DEFAULT_PORT + DshEndpoint.PATH;
    }

    private static String httpGet(String url) throws Exception {
        return rawRequest("GET", url, null);
    }

    private static String httpPost(String url, String body) throws Exception {
        return rawRequest("POST", url, body);
    }

    /** 统一出口：TLS / access-gate Cookie / 302 自动重登都交给 DshHttp。 */
    private static String rawRequest(String method, String url, String body) throws Exception {
        DshHttp.Resp r = DshHttp.fetch(method, url, body, 3000, 6000);
        if (r == null) return null;
        if (r.status != 200) {
            if (r.status == 401) {
                DshHttp.lastAuthError = "DSH 需要重新打开带 token 的地址（服务端重启过）";
            } else if (r.status == 302 || r.status == 303) {
                String loc = r.header("location");
                if (loc != null && loc.startsWith("/-/auth/login")) {
                    DshHttp.lastAuthError = DshHttp.lastAuthError != null
                            ? DshHttp.lastAuthError : "访问被拦，未登录";
                }
            }
            return null;
        }
        return r.body;
    }

    private void handleState(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONObject sections = root.optJSONObject("sections");
        if (sections == null) return;

        String state = null;
        String balance = null;
        String notify = null;

        JSONObject ws = sections.optJSONObject("workStatus");
        if (ws != null) {
            JSONObject data = ws.optJSONObject("data");
            if (data != null && !data.isNull("state")) state = data.optString("state", null);
        }

        if ("thinking".equals(state) || "working".equals(state)) baseInterval = 2000;
        else baseInterval = 5000;
        if (screenOn) pollInterval = baseInterval;

        JSONObject bal = sections.optJSONObject("balance");
        if (bal != null) {
            JSONObject data = bal.optJSONObject("data");
            if (data != null && data.optBoolean("ok", false)) {
                JSONObject inner = data.optJSONObject("data");
                if (inner != null) {
                    JSONArray wallets = inner.optJSONArray("wallets");
                    if (wallets != null && wallets.length() > 0) {
                        balance = wallets.getJSONObject(0).optString("total", null);
                            if (balance != null) lastBalanceText = balance;
                    }
                }
            }
        }

        JSONObject nf = sections.optJSONObject("notify");
        if (nf != null) {
            long counter = nf.optLong("counter", -1);
            if (counter != lastNotifyCounter) {
                lastNotifyCounter = counter;
                JSONObject data = nf.optJSONObject("data");
                if (data != null) {
                    JSONObject ev = data.optJSONObject("event");
                    if (ev != null) {
                        String t = ev.optString("type", "");
                        if ("turn/end".equals(t)) {
                            JSONObject ed = ev.optJSONObject("data");
                            String kind = null;
                            if (ed != null) {
                                JSONObject reason = ed.optJSONObject("reason");
                                if (reason != null) kind = reason.optString("kind", null);
                            }
                            if ("completed".equals(kind)) notify = "success";
                            else if (kind != null) notify = "error";
                        } else if ("approval/asked".equals(t)) {
                            notify = "waiting";
                        }
                    }
                }
            }
        }

        if (balance != null && !balance.equals(lastShownBalance)) {
            lastShownBalance = balance;
            showBubble("余额 ¥" + balance, 5000);
        }
        if (notify != null) {
            if ("success".equals(notify)) showBubble(pickWork(4), 4500);
            else if ("error".equals(notify)) showBubble(pickWork(5), 4500);
            else showBubble(pickWork(3), 4500);
        }
        if (state != null && !state.equals(lastState)) {
            lastState = state;
            int wi = 0;
            for (int k = 0; k < WORK_KEYS.length; k++) {
                if (WORK_KEYS[k].equals(state)) wi = k;
            }
            showBubble(pickWork(wi), 4500);
        }

        JSONObject pets = root.optJSONObject("pets");
        if (pets != null) {
            JSONObject mainPet = pets.optJSONObject("main");
            if (mainPet != null) {
                JSONObject say = mainPet.optJSONObject("say");
                if (say != null) {
                    long sc = say.optLong("counter", -1);
                    if (sc > 0) {
                        if (!sayInit) {
                            sayInit = true;
                            lastSayCounter = sc;
                        } else if (sc != lastSayCounter) {
                            lastSayCounter = sc;
                            JSONObject d = say.optJSONObject("data");
                            String txt = d == null ? null : d.optString("text", null);
                            boolean dup = txt != null && txt.equals(lastWhisperText)
                                    && System.currentTimeMillis() - lastWhisperAt < 9000;
                            if (txt != null && txt.length() > 0 && !dup) showBubble(txt, 7000);
                        }
                    }
                }
            }
        }

        JSONObject push = new JSONObject();
        // 关键：state 为 null 也要推（用 JSON null），否则网页那边的 curWork 永远清不掉，
        // 会一直以为"正在工作"，随机动作被永久闸住 —— 表现就是宠物卡在一个动作上不动。
        push.put("state", state == null ? JSONObject.NULL : state);
        if (balance != null) push.put("balance", balance);
        if (notify != null) push.put("notify", notify);

        final String js = "window.petUpdate && petUpdate(" + push.toString() + ")";
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (web != null) web.evaluateJavascript(js, null);
            }
        });
    }

    private void scheduleWatchdog() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            Intent i = new Intent(this, WatchdogReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(this, 9, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setInexactRepeating(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 15 * 60 * 1000L,
                    15 * 60 * 1000L, pi);
        } catch (Exception ignored) {
        }
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel("pet", "大肥鱼桌宠", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "pet")
                : new Notification.Builder(this);
        b.setContentTitle("大肥鱼桌宠运行中 · 好感 Lv." + heartLevel())
                .setContentText(pausedLink
                        ? "联动已暂停 · 桌宠仍在，点「继续联动」恢复"
                        : "单击Q弹 · 双击开DSH · 甩出去会飞 · 飞行中按一下接住")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentIntent(open)
                .setOngoing(true);
        Intent pause = new Intent(this, PetService.class).setAction(ACTION_PAUSE);
        b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_media_pause,
                pausedLink ? "继续联动" : "暂停联动",
                PendingIntent.getService(this, 1, pause,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        Intent stop = new Intent(this, PetService.class).setAction(ACTION_STOP);
        b.addAction(new Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel, "停止",
                PendingIntent.getService(this, 2, stop,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        return b.build();
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(1, buildNotification());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        inst = null;
        running = false;
        alive = false;
        flying = false;
        ui.removeCallbacks(flyTick);
        ui.removeCallbacks(longPressRunnable);
        hideMenu();
        if (vt != null) {
            try {
                vt.recycle();
            } catch (Exception ignored) {
            }
            vt = null;
        }
        try {
            unregisterReceiver(screenRx);
        } catch (Exception ignored) {
        }
        if (net != null) net.shutdownNow();
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
