package com.dsha.pet;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DSH 端点发现。
 *
 * 老版本把 http://127.0.0.1:38859 写死在代码里，换设备（DSHA 选了别的端口）就永远连不上。
 * 这里按 手动 > 缓存 > 扫描 的顺序找，握手用 /dsh-pet-7340/state 的 JSON 业务字段判断，
 * 不靠端口号猜。
 *
 * 远程支持（本版新增）：手动端点可以填完整地址，如 https://dsh.20180515.xyz，
 * 带 scheme、https 走 TLS、认证交给 DshHttp（access-gate 密码登录 + Cookie）。
 * 远程端点连不上时不再往下扫端口——对别人的服务器扫 3 万个端口既慢又不礼貌。
 */
public final class DshEndpoint {
    /** dsh-pet 插件固定的路由前缀（见插件源码 ROUTE_PREFIX）。 */
    public static final String PATH = "/dsh-pet-7340";
    public static final int DEFAULT_PORT = 38859;
    /** 最近一次扫描中“端口开着但不是 DSH”的提示，供界面显示。 */
    public static volatile String lastHint = null;
    private static final Set<Integer> openPorts = java.util.Collections.synchronizedSet(new LinkedHashSet<Integer>());

    private static final String PREFS = "dsh_pet_endpoint";
    private static final String K_HOST = "ep_host";
    private static final String K_PORT = "ep_port";
    private static final String K_SCHEME = "ep_scheme";
    private static final String K_MANUAL = "ep_manual";

    public final String host;
    public final int port;
    public final String scheme; // http / https
    public final String source; // manual / cache / scan

    public DshEndpoint(String host, int port, String source) {
        this(host, port, "http", source);
    }

    public DshEndpoint(String host, int port, String scheme, String source) {
        this.host = host;
        this.port = port;
        this.scheme = scheme == null || scheme.isEmpty() ? "http" : scheme;
        this.source = source;
    }

    public boolean isHttps() {
        return "https".equals(scheme);
    }

    public String base() {
        String h = host != null && host.contains(":") && !host.startsWith("[")
                ? "[" + host + "]" : host;
        int defPort = isHttps() ? 443 : 80;
        String u = scheme + "://" + h;
        if (port > 0 && port != defPort) u += ":" + port;
        return u + PATH;
    }

    public String url(String suffix) {
        if (suffix == null || suffix.isEmpty()) return base();
        return base() + (suffix.startsWith("/") ? suffix : "/" + suffix);
    }

    @Override
    public String toString() {
        return scheme + "://" + host + ":" + port;
    }

    public interface Listener {
        void onProgress(String message);
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /** 读缓存端点（不验证连通）。没有则返回 null。 */
    public static DshEndpoint cached(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        String h = sp.getString(K_HOST, null);
        int p = sp.getInt(K_PORT, 0);
        if (h == null || p <= 0) return null;
        String sc = sp.getString(K_SCHEME, "http");
        return new DshEndpoint(h, p, sc, "cache");
    }

    /**
     * 阻塞解析端点：先手动端点，再上次缓存，最后扫常见动态端口段。
     * 必须放在后台线程调用。
     */
    public static DshEndpoint resolveBlocking(Context ctx, Listener l) {
        SharedPreferences sp = prefs(ctx);

        lastHint = null;
        openPorts.clear();
        DshHttp.lastAuthError = null;

        // 1) 手动指定：用户说了算，连不上也不自动覆盖
        Manual man = parseManual(sp.getString(K_MANUAL, null));
        if (man != null) {
            progress(l, "正在连接手动端点 " + man + "…");
            if (probe(man.host, man.port, man.scheme, 8000, 12000)) {
                DshEndpoint e = new DshEndpoint(man.host, man.port, man.scheme, "manual");
                save(sp, e);
                return e;
            }
            String authErr = DshHttp.lastAuthError;
            if (authErr != null) lastHint = authErr;
            if (!isLoopback(man.host)) {
                // 远程端点：别扫，直接如实报失败
                if (lastHint == null) lastHint = "连不上 " + man;
                progress(l, "手动端点连不上：" + lastHint);
                return null;
            }
            progress(l, "手动端点连不上，继续自动查找…");
        }

        // 2) 上次成功的端点（远程的同样不往下走扫描）
        DshEndpoint c = cached(ctx);
        if (c != null) {
            progress(l, "正在验证上次的端点 " + c + "…");
            if (probe(c.host, c.port, c.scheme, 4000, 6000)) return c;
            if (!isLoopback(c.host)) {
                if (lastHint == null) lastHint = "连不上 " + c;
                return null;
            }
        }

        // 3) 常见动态端口段（Linux 临时端口 32768-60999，覆盖大多数情况）
        List<Integer> fast = new ArrayList<>();
        Set<Integer> seed = new LinkedHashSet<>();
        if (c != null) seed.add(c.port);
        seed.add(DEFAULT_PORT);
        for (int p = 30000; p <= 61000; p++) seed.add(p);
        fast.addAll(seed);
        progress(l, "正在扫描本机端口（" + fast.size() + " 个）…");
        DshEndpoint hit = scan(fast, "127.0.0.1");
        if (hit == null) {
            progress(l, "IPv4 没找到，试试 IPv6 回环…");
            hit = scan(fast, "::1");
        }
        if (hit != null) {
            save(sp, hit);
            return hit;
        }

        // 4) 扫完仍没有：看看是不是 DSH 起着但插件没加载
        for (int p : new ArrayList<>(openPorts)) {
            if (looksLikeDsh("127.0.0.1", p) || looksLikeDsh("::1", p)) {
                lastHint = "DSH 在运行，但桌宠插件没加载（端口 " + p + "）";
                break;
            }
        }
        return null;
    }

    /** 最近一次扫描中 TCP 能连上的端口，用于界面诊断。 */
    public static String openPortsSummary() {
        if (openPorts.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (int p : new ArrayList<>(openPorts)) {
            if (n++ > 0) sb.append("、");
            sb.append(p);
            if (n >= 6) { sb.append("…"); break; }
        }
        return sb.toString();
    }

    /** 单点探测（http）：TCP 能连上且 /state 握手通过。 */
    public static boolean probe(String host, int port, int connectMs, int readMs) {
        return probe(host, port, "http", connectMs, readMs);
    }

    /** 单点探测：走 DshHttp（支持 https 与 access-gate 认证）。 */
    public static boolean probe(String host, int port, String scheme, int connectMs, int readMs) {
        try {
            DshEndpoint e = new DshEndpoint(host, port, scheme, "probe");
            DshHttp.Resp r = DshHttp.fetch("GET", e.url("/state"), null, connectMs, readMs);
            if (r == null) return false;
            if (r.status != 200) {
                if (r.status == 401) DshHttp.lastAuthError = "DSH 需要重新打开带 token 的地址（服务端重启过）";
                return false;
            }
            return looksLikeState(r.body);
        } catch (Throwable t) {
            if (DshHttp.lastAuthError == null && "https".equals(scheme)) {
                DshHttp.lastAuthError = "TLS 连接失败：" + t;
            }
            return false;
        }
    }

    /** 判断某个端点是否是 DSH（根路径 401 特征），用于给出更准确的提示。 */
    public static boolean looksLikeDsh(String host, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 500);
            s.setSoTimeout(900);
            OutputStream o = s.getOutputStream();
            o.write(("GET / HTTP/1.1\r\nHost: " + host + ":" + port
                    + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            o.flush();
            String raw = readAll(s);
            return raw.contains("dsh web authentication required");
        } catch (Throwable t) {
            return false;
        } finally {
            close(s);
        }
    }

    /** 手动端点：允许 host:port，也允许 https://域名 这种完整地址。 */
    public static void setManual(Context ctx, String raw) {
        SharedPreferences sp = prefs(ctx);
        if (raw == null || raw.trim().isEmpty()) {
            sp.edit().remove(K_MANUAL).apply();
        } else {
            sp.edit().putString(K_MANUAL, raw.trim()).apply();
        }
    }

    /** 兼容老调用：host 为空表示清除。 */
    public static void setManual(Context ctx, String host, int port) {
        if (host == null || host.trim().isEmpty() || port <= 0) {
            setManual(ctx, (String) null);
        } else {
            setManual(ctx, host.trim() + ":" + port);
        }
    }

    /** 原样返回手动端点字符串（可能是 URL 形式）。 */
    public static String getManual(Context ctx) {
        return prefs(ctx).getString(K_MANUAL, null);
    }

    public static void invalidate(Context ctx) {
        prefs(ctx).edit().remove(K_HOST).remove(K_PORT).remove(K_SCHEME).apply();
    }

    // ------------------------------------------------------------------
    // 手动端点解析
    // ------------------------------------------------------------------

    public static final class Manual {
        public final String host;
        public final int port;
        public final String scheme;

        Manual(String host, int port, String scheme) {
            this.host = host;
            this.port = port;
            this.scheme = scheme;
        }

        @Override
        public String toString() {
            int def = "https".equals(scheme) ? 443 : 80;
            return scheme + "://" + host + (port == def ? "" : ":" + port);
        }
    }

    /** 解析手动端点字符串。空 / 非法返回 null。 */
    public static Manual parseManual(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        String scheme = "http";
        String low = v.toLowerCase(Locale.US);
        if (low.startsWith("https://")) {
            scheme = "https";
            v = v.substring(8);
        } else if (low.startsWith("http://")) {
            scheme = "http";
            v = v.substring(7);
        }
        // 去掉路径部分，只留 authority
        int slash = v.indexOf('/');
        if (slash >= 0) v = v.substring(0, slash);
        if (v.isEmpty()) return null;

        String host;
        int port = "https".equals(scheme) ? 443 : 80;
        if (v.startsWith("[")) { // IPv6 字面量 [::1]:port
            int rb = v.indexOf(']');
            if (rb < 0) return null;
            host = v.substring(1, rb);
            String rest = v.substring(rb + 1);
            if (rest.startsWith(":")) {
                try {
                    port = Integer.parseInt(rest.substring(1).trim());
                } catch (Exception e) {
                    return null;
                }
            }
        } else {
            int colon = v.lastIndexOf(':');
            int otherColon = v.indexOf(':');
            if (colon > 0 && colon == otherColon) { // 只有一个冒号 → host:port
                host = v.substring(0, colon).trim();
                try {
                    port = Integer.parseInt(v.substring(colon + 1).trim());
                } catch (Exception e) {
                    return null;
                }
            } else {
                host = v.trim();
            }
        }
        if (host.isEmpty() || port <= 0 || port > 65535) return null;
        return new Manual(host, port, scheme);
    }

    // ------------------------------------------------------------------
    // 扫描
    // ------------------------------------------------------------------

    private static DshEndpoint scan(final List<Integer> ports, final String host) {
        final int threads = Math.min(128, Math.max(16, ports.size() / 64));
        final AtomicInteger idx = new AtomicInteger(0);
        final AtomicBoolean stop = new AtomicBoolean(false);
        final AtomicReference<DshEndpoint> hit = new AtomicReference<>();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!stop.get()) {
                        int i = idx.getAndIncrement();
                        if (i >= ports.size()) return;
                        int port = ports.get(i);
                        Socket s = new Socket();
                        try {
                            s.connect(new InetSocketAddress(host, port), 90);
                            openPorts.add(port);
                            if (handshake(s, host, port, 700)) {
                                if (hit.compareAndSet(null,
                                        new DshEndpoint(host, port, "scan"))) {
                                    stop.set(true);
                                }
                            }
                        } catch (Throwable ignored) {
                        } finally {
                            close(s);
                        }
                    }
                }
            }, "dsh-scan-" + i);
            t.setDaemon(true);
            t.start();
            workers.add(t);
        }
        for (Thread t : workers) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return hit.get();
    }

    // ------------------------------------------------------------------
    // HTTP 握手（本机扫描用裸 Socket；远程走 DshHttp）
    // ------------------------------------------------------------------

    private static boolean handshake(Socket s, String host, int port, int readMs) {
        try {
            s.setSoTimeout(readMs);
            OutputStream o = s.getOutputStream();
            String req = "GET " + PATH + "/state HTTP/1.1\r\n"
                    + "Host: " + host + ":" + port + "\r\n"
                    + "Connection: close\r\n"
                    + "Accept: application/json\r\n"
                    + "User-Agent: dsh-pet-android\r\n\r\n";
            o.write(req.getBytes("UTF-8"));
            o.flush();
            String raw = readAll(s);
            int split = raw.indexOf("\r\n\r\n");
            if (split < 0) return false;
            String head = raw.substring(0, split);
            if (!head.startsWith("HTTP/1.") || !head.contains(" 200")) return false;
            return looksLikeState(raw.substring(split + 4));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean looksLikeState(String body) {
        return body != null && (body.contains("\"sections\"")
                || body.contains("\"workStatus\"")
                || body.contains("\"balance\""));
    }

    private static String readAll(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[4096];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            total += n;
            if (total > (1 << 20)) break;
        }
        return bos.toString("UTF-8");
    }

    // ------------------------------------------------------------------

    private static boolean isLoopback(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase(Locale.US);
        return "127.0.0.1".equals(h) || "::1".equals(h) || "localhost".equals(h);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void save(SharedPreferences sp, DshEndpoint e) {
        sp.edit().putString(K_HOST, e.host).putInt(K_PORT, e.port)
                .putString(K_SCHEME, e.scheme).apply();
    }

    private static void progress(Listener l, String msg) {
        if (l != null) l.onProgress(msg);
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (Throwable ignored) {
        }
    }
}
