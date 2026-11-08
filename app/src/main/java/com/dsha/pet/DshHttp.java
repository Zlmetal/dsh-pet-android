package com.dsha.pet;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.Locale;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * DSH 的统一 HTTP 出口。
 *
 * 原版只有裸 Socket + http://，连本机 127.0.0.1 够用，但连经过 dsh-access-gate
 * 反代的远程 DSH 就不行：没有 https、不带 Cookie、遇到 302 跳登录页直接判失败。
 *
 * 这里把三件事收拢到一处：
 *   1. http / https 统一出口（TLS 用系统信任库，并做 HTTPS 主机名校验）；
 *   2. 自动携带 dsh_gate_token，302 到登录页时用保存的密码自动重登再重试一次；
 *   3. 返回带状态码的响应，让调用方自己决定怎么处理非 200。
 */
public final class DshHttp {
    /** dsh-access-gate 的登录路径与 cookie 名（见 access-gate 的 lib/index.js）。 */
    public static final String LOGIN_PATH = "/-/auth/login";
    public static final String COOKIE_NAME = "dsh_gate_token";

    private static final String PREFS = "dsh_pet_auth";
    private static final String K_PASS = "gate_password";
    /** dsh-pet 的接口一律收 JSON。 */
    private static final String DEFAULT_CT = "application/json; charset=utf-8";
    private static final String FORM_CT = "application/x-www-form-urlencoded; charset=utf-8";

    /** 当前 access-gate 会话 token。null = 还没登录过。 */
    private static volatile String cookie = null;
    /** 最近一次认证失败的原因，供界面显示。 */
    public static volatile String lastAuthError = null;
    /** 上次登录失败的时间，避免每次轮询都去撞登录页。 */
    private static volatile long lastLoginFailAt = 0;

    private static volatile Context app = null;

    private DshHttp() {
    }

    /** 全局初始化一次即可（PetService / MainActivity 的 onCreate 里调用）。 */
    public static void init(Context ctx) {
        if (ctx != null) app = ctx.getApplicationContext();
    }

    // ------------------------------------------------------------------
    // 密码与 cookie 状态
    // ------------------------------------------------------------------

    public static void setPassword(String pass) {
        Context c = app;
        if (c == null) return;
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String v = pass == null ? "" : pass.trim();
        if (v.isEmpty()) {
            sp.edit().remove(K_PASS).apply();
        } else {
            sp.edit().putString(K_PASS, v).apply();
        }
        clearAuth();
    }

    public static String password() {
        Context c = app;
        if (c == null) return null;
        String v = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_PASS, null);
        return v == null || v.isEmpty() ? null : v;
    }

    public static boolean hasPassword() {
        return password() != null;
    }

    /** 丢掉当前会话，下次请求会重新登录。 */
    public static void clearAuth() {
        cookie = null;
        lastAuthError = null;
        lastLoginFailAt = 0;
    }

    public static boolean hasSession() {
        return cookie != null;
    }

    // ------------------------------------------------------------------
    // 请求
    // ------------------------------------------------------------------

    public static final class Resp {
        public final int status;
        public final String head;
        public final String body;

        Resp(int status, String head, String body) {
            this.status = status;
            this.head = head;
            this.body = body;
        }

        /** 取响应头（小写匹配），没有返回 null。 */
        public String header(String name) {
            if (head == null) return null;
            String want = name.toLowerCase(Locale.US) + ":";
            for (String line : head.split("\r\n")) {
                String l = line.toLowerCase(Locale.US);
                if (l.startsWith(want)) return line.substring(want.length()).trim();
            }
            return null;
        }
    }

    /**
     * 发一次请求。遇到 access-gate 的 302 登录跳转会尝试自动登录并重试一次。
     * 连不上 / 解析失败抛异常；其余情况返回带状态码的响应。
     */
    public static Resp fetch(String method, String url, String body,
                             int connectMs, int readMs) throws Exception {
        return fetch(method, url, body, DEFAULT_CT, connectMs, readMs);
    }

    public static Resp fetch(String method, String url, String body,
                             String contentType, int connectMs, int readMs) throws Exception {
        Resp r = onceRaw(method, url, body, contentType, connectMs, readMs, true);
        if (r == null) return null;
        if (r.status >= 300 && r.status < 400) {
            String loc = r.header("location");
            if (loc != null && loc.startsWith(LOGIN_PATH)) {
                if (login(url, connectMs, readMs)) {
                    r = onceRaw(method, url, body, contentType, connectMs, readMs, true);
                }
            }
        }
        return r;
    }

    public static Resp fetch(String method, String url, String body) throws Exception {
        return fetch(method, url, body, DEFAULT_CT, 3000, 6000);
    }

    // ------------------------------------------------------------------
    // 登录
    // ------------------------------------------------------------------

    /** 用保存的密码换一个 dsh_gate_token，成功返回 true。 */
    public static boolean login(String anyUrl, int connectMs, int readMs) {
        String pass = password();
        if (pass == null) {
            lastAuthError = "需要 DSH 访问密码";
            return false;
        }
        // 5 分钟内刚失败过就别再撞，省得每轮轮询都卡在登录页
        long now = System.currentTimeMillis();
        if (now - lastLoginFailAt < 5 * 60 * 1000L && lastAuthError != null) return false;

        try {
            URL u = new URL(anyUrl);
            String scheme = u.getProtocol() == null ? "http" : u.getProtocol().toLowerCase(Locale.US);
            int defaultPort = "https".equals(scheme) ? 443 : 80;
            int port = u.getPort() < 0 ? defaultPort : u.getPort();
            String host = u.getHost();

            String body = "password=" + enc(pass) + "&next=" + enc("/");
            String url = scheme + "://" + hostHeader(host, port, defaultPort) + LOGIN_PATH;

            Resp r = oncePost(url, body, connectMs, readMs);
            if (r == null) {
                lastAuthError = "登录无响应";
                lastLoginFailAt = now;
                return false;
            }
            String setCookie = r.header("set-cookie");
            if (setCookie != null) {
                String token = extractToken(setCookie);
                if (token != null) {
                    cookie = token;
                    lastAuthError = null;
                    lastLoginFailAt = 0;
                    return true;
                }
            }
            if (r.status == 401) {
                lastAuthError = "密码不对";
            } else {
                lastAuthError = "登录失败 HTTP " + r.status;
            }
            lastLoginFailAt = now;
            return false;
        } catch (Throwable t) {
            lastAuthError = "登录出错 " + t;
            lastLoginFailAt = now;
            return false;
        }
    }

    private static Resp oncePost(String url, String body, int connectMs, int readMs) throws Exception {
        return onceRaw("POST", url, body, FORM_CT, connectMs, readMs, false);
    }

    /**
     * 与 once() 同构，但可以指定 Content-Type、并可选是否附带当前 cookie。
     * 登录请求本身不带 cookie（带了也没用，反而可能进"已登录直接跳转"分支）。
     */
    private static Resp onceRaw(String method, String url, String body, String contentType,
                                int connectMs, int readMs, boolean withCookie) throws Exception {
        URL u = new URL(url);
        String scheme = u.getProtocol() == null ? "http" : u.getProtocol().toLowerCase(Locale.US);
        int defaultPort = "https".equals(scheme) ? 443 : 80;
        int port = u.getPort() < 0 ? defaultPort : u.getPort();
        String host = u.getHost();
        String path = u.getFile();
        if (path == null || path.isEmpty()) path = "/";

        Socket s = open(scheme, host, port, connectMs);
        try {
            s.setSoTimeout(readMs);
            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(hostHeader(host, port, defaultPort)).append("\r\n");
            req.append("Connection: close\r\n");
            req.append("Accept: text/html,application/json,*/*\r\n");
            req.append("User-Agent: dsh-pet-android\r\n");
            if (withCookie && cookie != null) {
                req.append("Cookie: ").append(COOKIE_NAME).append('=').append(cookie).append("\r\n");
            }
            byte[] bb = body == null ? null : body.getBytes("UTF-8");
            if (bb != null) {
                req.append("Content-Type: ").append(contentType).append("\r\n");
                req.append("Content-Length: ").append(bb.length).append("\r\n");
            }
            req.append("\r\n");
            OutputStream os = s.getOutputStream();
            os.write(req.toString().getBytes("UTF-8"));
            if (bb != null) os.write(bb);
            os.flush();

            String raw = readAll(s);
            int split = raw.indexOf("\r\n\r\n");
            if (split < 0) return null;
            String head = raw.substring(0, split);
            String payload = raw.substring(split + 4);
            int status = parseStatus(head);
            String lowHead = head.toLowerCase(Locale.US);
            if (lowHead.contains("transfer-encoding: chunked")) payload = dechunk(payload);
            return new Resp(status, head, payload);
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String extractToken(String setCookie) {
        int i = setCookie.indexOf(COOKIE_NAME + "=");
        if (i < 0) return null;
        int from = i + COOKIE_NAME.length() + 1;
        int semi = setCookie.indexOf(';', from);
        String v = (semi < 0 ? setCookie.substring(from) : setCookie.substring(from, semi)).trim();
        return v.isEmpty() ? null : v;
    }

    // ------------------------------------------------------------------
    // 底层
    // ------------------------------------------------------------------

    /** 建连；https 用系统信任库并做主机名校验（裸 SSLSocket 默认不校验主机名）。 */
    private static Socket open(String scheme, String host, int port, int connectMs) throws Exception {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), connectMs);
        if (!"https".equals(scheme)) return raw;
        try {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) f.createSocket(raw, host, port, true);
            SSLParameters p = ssl.getSSLParameters();
            p.setEndpointIdentificationAlgorithm("HTTPS");
            ssl.setSSLParameters(p);
            ssl.startHandshake();
            return ssl;
        } catch (Exception e) {
            try {
                raw.close();
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    private static String hostHeader(String host, int port, int defaultPort) {
        String h = host != null && host.contains(":") && !host.startsWith("[")
                ? "[" + host + "]" : host;
        if (port <= 0 || port == defaultPort) return h;
        return h + ":" + port;
    }

    private static int parseStatus(String head) {
        try {
            int sp = head.indexOf(' ');
            if (sp < 0) return 0;
            int sp2 = head.indexOf(' ', sp + 1);
            String code = sp2 < 0 ? head.substring(sp + 1).trim()
                    : head.substring(sp + 1, sp2).trim();
            return Integer.parseInt(code);
        } catch (Exception e) {
            return 0;
        }
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

    static String dechunk(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int e = s.indexOf("\r\n", i);
            if (e < 0) break;
            int size;
            try {
                size = Integer.parseInt(s.substring(i, e).trim(), 16);
            } catch (Exception x) {
                break;
            }
            if (size == 0) break;
            int from = e + 2;
            int to = Math.min(s.length(), from + size);
            out.append(s, from, to);
            i = to + 2;
        }
        return out.toString();
    }

    private static String enc(String v) {
        try {
            return java.net.URLEncoder.encode(v, "UTF-8");
        } catch (Exception e) {
            return v;
        }
    }
}
