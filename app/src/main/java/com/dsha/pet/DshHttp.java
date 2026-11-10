package com.dsha.pet;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import java.util.zip.GZIPInputStream;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * DSH 的统一 HTTP 出口。
 *
 * 原版只有裸 Socket + http://，连本机 127.0.0.1 够用，但连经过 dsh-access-gate
 * 反代的远程 DSH 就不行：没有 https、不带 Cookie、遇到 302 跳登录页直接判失败。
 *
 * 远程部署其实是<b>两层</b>认证：
 *   1) dsh-access-gate 的密码 cookie（dsh_gate_token）——拿不到就被 302 踢去登录页；
 *   2) DSH 自己的签名 cookie（dsh-auth-&lt;hash&gt;）——只管 /api，缺了就是纯文本 401。
 * 第 2 层的 cookie 只能从 `GET /?token=...` 的 303 响应里拿，而那个 token 只出现在
 * 密码登录成功的 Location 里。所以「补 dsh-auth」= 重新走一次密码登录抓 token 再交换，
 * 这一步藏在 ensureDshAuth() 里，调用方不用管。
 *
 * 这里把四件事收拢到一处：
 *   1. http / https 统一出口（TLS 用系统信任库，并做 HTTPS 主机名校验）；
 *   2. 一个多 cookie 的 jar，两层 cookie 都自动携带；
 *   3. 302 到登录页时用保存的密码自动重登再重试一次；
 *   4. /api 401 时自动补 dsh-auth cookie 再重试一次。
 */
public final class DshHttp {
    /** dsh-access-gate 的登录路径与 cookie 名（见 access-gate 的 lib/index.js）。 */
    public static final String LOGIN_PATH = "/-/auth/login";
    public static final String COOKIE_NAME = "dsh_gate_token";
    /** DSH 自己签发的会话 cookie 前缀（后面跟着 authority 的 hash）。 */
    public static final String DSH_COOKIE_PREFIX = "dsh-auth-";

    private static final String PREFS = "dsh_pet_auth";
    private static final String K_PASS = "gate_password";
    /** dsh-pet 的接口一律收 JSON。 */
    private static final String DEFAULT_CT = "application/json; charset=utf-8";
    private static final String FORM_CT = "application/x-www-form-urlencoded; charset=utf-8";

    /** 两层 cookie 都放这儿，请求时整串带上。 */
    private static final Map<String, String> jar = new LinkedHashMap<>();
    /** 密码登录时从 Location 里捞到的 launch token，用来换 dsh-auth cookie。 */
    private static volatile String launchToken = null;
    /** 最近一次认证失败的原因，供界面显示。 */
    public static volatile String lastAuthError = null;
    /** 上次登录失败的时间，避免每次轮询都去撞登录页。 */
    private static volatile long lastLoginFailAt = 0;
    /** 防止 ensureDshAuth 在极端情况下反复递归登录。 */
    private static final ThreadLocal<Boolean> authBusy = new ThreadLocal<>();

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
        synchronized (jar) {
            jar.clear();
        }
        launchToken = null;
        lastAuthError = null;
        lastLoginFailAt = 0;
    }

    /** 只为「已连上过 DSH」判断，两层都在才算有会话。 */
    public static boolean hasSession() {
        return hasGateCookie() && hasDshAuth();
    }

    private static boolean hasGateCookie() {
        synchronized (jar) {
            return jar.containsKey(COOKIE_NAME);
        }
    }

    /** 是否已经拿到 DSH 自己的签名 cookie（/api 要用）。 */
    public static boolean hasDshAuth() {
        synchronized (jar) {
            for (String k : jar.keySet()) {
                if (k.startsWith(DSH_COOKIE_PREFIX)) return true;
            }
            return false;
        }
    }

    /** 当前完整的 Cookie 请求头值；没 cookie 返回 null。 */
    public static String cookieHeader() {
        synchronized (jar) {
            if (jar.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : jar.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        }
    }

    /** 把 Set-Cookie 收进 jar（支持一次返回多条、支持 Max-Age=0 删除）。 */
    private static void absorbCookies(Resp r) {
        if (r == null) return;
        for (String sc : r.headers("set-cookie")) {
            int semi = sc.indexOf(';');
            String pair = semi < 0 ? sc : sc.substring(0, semi);
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String k = pair.substring(0, eq).trim();
            String v = pair.substring(eq + 1).trim();
            if (k.isEmpty()) continue;
            String low = sc.toLowerCase(Locale.US);
            boolean del = low.contains("max-age=0") || v.isEmpty();
            synchronized (jar) {
                if (del) {
                    jar.remove(k);
                } else {
                    jar.put(k, v);
                }
            }
        }
        // 从 302 的 Location 里捞 launch token：/?token=xxx
        String t = tokenOf(r.header("location"));
        if (t != null) launchToken = t;
    }

    private static String tokenOf(String loc) {
        if (loc == null || loc.isEmpty()) return null;
        int i = loc.indexOf("token=");
        if (i < 0) return null;
        int from = i + 6;
        int to = loc.indexOf('&', from);
        String v = to < 0 ? loc.substring(from) : loc.substring(from, to);
        return v.isEmpty() ? null : v;
    }

    // ------------------------------------------------------------------
    // 请求
    // ------------------------------------------------------------------

    public static final class Resp {
        public final int status;
        public final String head;
        public final String body;
        /** 原始字节。文本接口用 body，二进制接口（/api/file、上传）用这个。 */
        public final byte[] bytes;

        Resp(int status, String head, byte[] bytes) {
            this.status = status;
            this.head = head;
            this.bytes = bytes == null ? new byte[0] : bytes;
            this.body = new String(this.bytes, java.nio.charset.StandardCharsets.UTF_8);
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

        /** 取同名响应头的全部取值（Set-Cookie 会不止一条）。 */
        public List<String> headers(String name) {
            List<String> out = new ArrayList<>();
            if (head == null) return out;
            String want = name.toLowerCase(Locale.US) + ":";
            for (String line : head.split("\r\n")) {
                String l = line.toLowerCase(Locale.US);
                if (l.startsWith(want)) out.add(line.substring(want.length()).trim());
            }
            return out;
        }
    }

    /**
     * 发一次请求。两层认证都在这里兜：gate 302 会重登，/api 401 会补 dsh-auth。
     * 连不上 / 解析失败抛异常；其余情况返回带状态码的响应。
     */
    public static Resp fetch(String method, String url, String body,
                             int connectMs, int readMs) throws Exception {
        return fetch(method, url, body, DEFAULT_CT, connectMs, readMs);
    }

    public static Resp fetch(String method, String url, String body,
                             String contentType, int connectMs, int readMs) throws Exception {
        Resp r = onceRaw(method, url, body, contentType, connectMs, readMs);
        if (r == null) return null;

        // 第 1 层：被 access-gate 踢去登录页
        if (r.status >= 300 && r.status < 400) {
            String loc = r.header("location");
            if (loc != null && loc.contains(LOGIN_PATH)) {
                if (login(url, connectMs, readMs)) {
                    r = onceRaw(method, url, body, contentType, connectMs, readMs);
                }
            }
        }

        // 第 2 层：gate 过了但 DSH 不认（纯文本 401）
        if (r != null && r.status == 401) {
            if (ensureDshAuth(url, connectMs, readMs)) {
                r = onceRaw(method, url, body, contentType, connectMs, readMs);
            }
        }
        return r;
    }

    public static Resp fetch(String method, String url, String body) throws Exception {
        return fetch(method, url, body, DEFAULT_CT, 3000, 6000);
    }

    /**
     * 二进制入口：请求体是原始字节，响应的 body 也当字节看（下载文件、上传图片走这条）。
     * 两层认证的重试逻辑跟 {@link #fetch} 完全一致。
     */
    public static Resp fetchBytes(String method, String url, byte[] body, String contentType,
                                  int connectMs, int readMs) throws Exception {
        Resp r = onceRawBytes(method, url, body, contentType, connectMs, readMs);
        if (r == null) return null;
        if (r.status >= 300 && r.status < 400) {
            String loc = r.header("location");
            if (loc != null && loc.contains(LOGIN_PATH)) {
                if (login(url, connectMs, readMs)) {
                    r = onceRawBytes(method, url, body, contentType, connectMs, readMs);
                }
            }
        }
        if (r != null && r.status == 401) {
            if (ensureDshAuth(url, connectMs, readMs)) {
                r = onceRawBytes(method, url, body, contentType, connectMs, readMs);
            }
        }
        return r;
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

            Resp r = onceRaw("POST", url, body, FORM_CT, connectMs, readMs);
            if (r == null) {
                lastAuthError = "登录无响应";
                lastLoginFailAt = now;
                return false;
            }
            absorbCookies(r);
            if (hasGateCookie()) {
                lastAuthError = null;
                lastLoginFailAt = 0;
                return true;
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

    /**
     * 补齐 DSH 自己的签名 cookie。
     *
     * 顺序：确保 gate cookie → 拿到 launch token（没有就现登一次）→
     * `GET /?token=...` 触发 DSH 的 303 + Set-Cookie → 收进 jar。
     * token 只能从密码登录的 Location 里来，所以第二次进这里会重登一次。
     */
    public static boolean ensureDshAuth(String anyUrl, int connectMs, int readMs) {
        if (hasDshAuth()) return true;
        Boolean busy = authBusy.get();
        if (busy != null && busy) return false;
        authBusy.set(true);
        try {
            if (!hasGateCookie()) {
                if (!login(anyUrl, connectMs, readMs)) return false;
            }
            if (launchToken == null) {
                if (!login(anyUrl, connectMs, readMs)) return false;
            }
            if (launchToken == null) {
                lastAuthError = "拿不到 DSH 启动 token";
                return false;
            }
            URL u = new URL(anyUrl);
            String scheme = u.getProtocol() == null ? "http" : u.getProtocol().toLowerCase(Locale.US);
            int defaultPort = "https".equals(scheme) ? 443 : 80;
            int port = u.getPort() < 0 ? defaultPort : u.getPort();
            String url = scheme + "://" + hostHeader(u.getHost(), port, defaultPort)
                    + "/?token=" + enc(launchToken);
            Resp r = onceRaw("GET", url, null, DEFAULT_CT, connectMs, readMs);
            absorbCookies(r);
            if (hasDshAuth()) {
                lastAuthError = null;
                return true;
            }
            // token 过期了（服务端重启过）：重新登录换一个新 token 再来一次
            launchToken = null;
            if (login(anyUrl, connectMs, readMs) && launchToken != null) {
                Resp r2 = onceRaw("GET", scheme + "://" + hostHeader(u.getHost(), port, defaultPort)
                        + "/?token=" + enc(launchToken), null, DEFAULT_CT, connectMs, readMs);
                absorbCookies(r2);
                if (hasDshAuth()) {
                    lastAuthError = null;
                    return true;
                }
            }
            lastAuthError = "DSH 会话建立失败";
            return false;
        } catch (Throwable t) {
            lastAuthError = "建立 DSH 会话出错 " + t;
            return false;
        } finally {
            authBusy.set(false);
        }
    }

    // ------------------------------------------------------------------
    // 底层
    // ------------------------------------------------------------------

    /**
     * 与 once() 同构，但可以指定 Content-Type。cookie 由 jar 统一决定。
     */
    private static Resp onceRaw(String method, String url, String body, String contentType,
                                int connectMs, int readMs) throws Exception {
        return onceRawBytes(method, url,
                body == null ? null : body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                contentType, connectMs, readMs);
    }

    /**
     * 与 onceRaw 同构，但请求体是原始字节（发图片/附件要 stream 上去，不能当字符串处理）。
     */
    static Resp onceRawBytes(String method, String url, byte[] bb, String contentType,
                             int connectMs, int readMs) throws Exception {
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
            // 服务端支持 gzip：同样 300 条历史，压完 1MB vs 不压 4MB，翻页快得多
            req.append("Accept-Encoding: gzip\r\n");
            // 历史记录一页几百 KB，gzip 能压到四分之一；压不动的小响应服务端本来也就不压
            req.append("Accept-Encoding: gzip\r\n");
            req.append("User-Agent: dsh-pet-android\r\n");
            String ck = cookieHeader();
            if (ck != null) req.append("Cookie: ").append(ck).append("\r\n");
            if (bb != null) {
                req.append("Content-Type: ").append(contentType).append("\r\n");
                req.append("Content-Length: ").append(bb.length).append("\r\n");
            }
            req.append("\r\n");
            OutputStream os = s.getOutputStream();
            os.write(req.toString().getBytes("UTF-8"));
            if (bb != null) os.write(bb);
            os.flush();

            // 全程按字节走：chunk 的长度是**字节**数，先解成字符串再切会把中文算错
            byte[] raw = readAllBytes(s);
            int split = indexOfCrlf2(raw);
            if (split < 0) return null;
            String head = new String(raw, 0, split, "ISO-8859-1");
            byte[] payload = new byte[raw.length - split - 4];
            System.arraycopy(raw, split + 4, payload, 0, payload.length);
            int status = parseStatus(head);
            String lowHead = head.toLowerCase(Locale.US);
            if (lowHead.contains("transfer-encoding: chunked")) payload = dechunkBytes(payload);
            if (lowHead.contains("content-encoding: gzip")) payload = gunzip(payload);
            return new Resp(status, head, payload);
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 建连；https 用系统信任库并做主机名校验（裸 SSLSocket 默认不校验主机名）。
     *
     * 域名可能同时有 A 和 AAAA 记录，而不少网络里 IPv6 有记录但不通（运营商没给前缀、
     * 或者路由被挡）。new InetSocketAddress(host, port) 只会用解析出来的第一个地址，
     * 撞上不通的那个就白等一个 connect 超时。这里把全部地址拿出来，IPv4 排前面挨个试。
     */
    private static Socket open(String scheme, String host, int port, int connectMs) throws Exception {
        java.net.InetAddress[] addrs;
        try {
            addrs = java.net.InetAddress.getAllByName(host);
        } catch (Exception e) {
            addrs = new java.net.InetAddress[0];
        }
        if (addrs.length == 0) {
            throw new java.net.UnknownHostException("解析不到 " + host);
        }
        // IPv4 优先：v4 不通的概率远低于 v6
        java.util.List<java.net.InetAddress> order = new java.util.ArrayList<>();
        for (java.net.InetAddress a : addrs) {
            if (a instanceof java.net.Inet4Address) order.add(a);
        }
        for (java.net.InetAddress a : addrs) {
            if (!(a instanceof java.net.Inet4Address)) order.add(a);
        }
        Exception last = null;
        Socket raw = null;
        for (int i = 0; i < order.size(); i++) {
            java.net.InetAddress a = order.get(i);
            Socket s = new Socket();
            try {
                int ms = i == order.size() - 1 ? connectMs : Math.min(connectMs, 6000);
                s.connect(new InetSocketAddress(a, port), ms);
                raw = s;
                last = null;
                break;
            } catch (Exception e) {
                last = e;
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (raw == null) throw last != null ? last : new java.net.ConnectException("连不上 " + host);
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

    /**
     * 读到 EOF 为止。**必须返回原始字节**——chunk 分段长度是按字节算的，
     * 先转成 String 再按字符切会把每个中文算成 3 份，直接截断 JSON。
     * （这就是 1.0.0.4 里「Unterminated object at character 32768」的根因）
     */
    private static byte[] readAllBytes(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
            total += n;
            if (total > (4 << 20)) break;
        }
        return bos.toByteArray();
    }

    private static int indexOfCrlf2(byte[] b) {
        for (int i = 0; i + 3 < b.length; i++) {
            if (b[i] == '\r' && b[i + 1] == '\n' && b[i + 2] == '\r' && b[i + 3] == '\n') return i;
        }
        return -1;
    }

    /** 按字节解 chunked。长度后面可能跟 `;ext=...`，要按分号截掉。 */
    static byte[] dechunkBytes(byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        int i = 0;
        while (i < b.length) {
            int e = i;
            while (e < b.length && !(b[e] == '\r' && e + 1 < b.length && b[e + 1] == '\n')) e++;
            if (e >= b.length) break;
            String line = new String(b, i, e - i, java.nio.charset.StandardCharsets.ISO_8859_1).trim();
            int semi = line.indexOf(';');
            if (semi >= 0) line = line.substring(0, semi).trim();
            int size;
            try {
                size = Integer.parseInt(line, 16);
            } catch (Exception x) {
                break;
            }
            if (size == 0) break;
            int from = e + 2;
            int to = Math.min(b.length, from + size);
            if (to <= from) break;
            out.write(b, from, to - from);
            i = to + 2; // 跳过块尾的 CRLF
        }
        return out.toByteArray();
    }

    /**
     * 解 gzip。服务端对 /api/session/events 这种大响应会压缩，300 条历史能从 4MB 掉到 1MB。
     * 解不开就把原样还回去，让上层拿到乱码去报错，而不是这里静默丢数据。
     */
    static byte[] gunzip(byte[] b) {
        if (b == null || b.length < 2) return b;
        if ((b[0] & 0xFF) != 0x1F || (b[1] & 0xFF) != 0x8B) return b; // 不是 gzip 魔数
        java.io.ByteArrayInputStream bin = new java.io.ByteArrayInputStream(b);
        java.util.zip.GZIPInputStream in = null;
        try {
            in = new java.util.zip.GZIPInputStream(bin, 16384);
            ByteArrayOutputStream out = new ByteArrayOutputStream(b.length * 3);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            return b;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** URL 编码（/api/file 的 path、上传的文件名都要用它）。 */
    public static String enc(String v) {
        try {
            return java.net.URLEncoder.encode(v, "UTF-8");
        } catch (Exception e) {
            return v;
        }
    }

    /** 把一个 endpoint base（…/dsh-pet-7340）剥成 origin（scheme://host[:port]）。 */
    public static String originOf(String base) {
        if (base == null || base.isEmpty()) return "";
        try {
            URL u = new URL(base);
            String scheme = u.getProtocol() == null ? "http" : u.getProtocol().toLowerCase(Locale.US);
            int defaultPort = "https".equals(scheme) ? 443 : 80;
            int port = u.getPort() < 0 ? defaultPort : u.getPort();
            return scheme + "://" + hostHeader(u.getHost(), port, defaultPort);
        } catch (Exception e) {
            int i = base.indexOf("/", base.indexOf("://") + 3);
            return i < 0 ? base : base.substring(0, i);
        }
    }
}
