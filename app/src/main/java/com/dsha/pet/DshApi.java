package com.dsha.pet;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * DSH Web GUI 的 /api RPC 客户端。
 *
 * 网页端和 App 端走的是同一套接口：`POST {origin}/api/{endpoint}`，报文
 * `{"type":"client-request","rpcId":uuid,"method":endpoint,"payload":{"args":{...}}}`，
 * 回到 `{"type":"server-response","rpcId":..,"result":{"ok":true,"value":..}}`。
 * 认证（gate 密码 cookie + DSH 签名 cookie）全在 {@link DshHttp} 里兜，这层只管业务。
 *
 * 为什么用 session/wait 而不是轮询 session/events：
 *   发完 prompt 后接管方是 Agent，`session/wait` 会阻塞到这一轮跑完直接把
 *   assistant 记录一起吐回来（实测 1.8 秒），比自己 sleep + 轮询省电也不抖。
 *
 * 命令约定：所有方法都在调用线程同步执行，界面层自己开线程。
 */
public final class DshApi {

    /** 一次 RPC 的返回。ok=false 时 msg 里是人话错误。 */
    public static final class Result {
        public final boolean ok;
        public final JSONObject value;
        public final String msg;

        Result(boolean ok, JSONObject value, String msg) {
            this.ok = ok;
            this.value = value;
            this.msg = msg;
        }

        public static Result fail(String msg) {
            return new Result(false, null, msg);
        }
    }

    private DshApi() {
    }

    // ------------------------------------------------------------------
    // 底层调用
    // ------------------------------------------------------------------

    /** 打一次 /api/{endpoint}。args 里必须恰好是描述符要求的那些键。 */
    public static Result call(String origin, String endpoint, JSONObject args,
                              int connectMs, int readMs) {
        try {
            JSONObject env = new JSONObject();
            env.put("type", "client-request");
            env.put("rpcId", UUID.randomUUID().toString());
            env.put("method", endpoint);
            JSONObject payload = new JSONObject();
            payload.put("args", args == null ? new JSONObject() : args);
            env.put("payload", payload);

            String url = origin + "/api/" + endpoint;
            DshHttp.Resp r = DshHttp.fetch("POST", url, env.toString(),
                    "application/json; charset=utf-8", connectMs, readMs);
            if (r == null) return Result.fail("没有响应");
            if (r.status == 401) {
                String hint = DshHttp.lastAuthError != null ? DshHttp.lastAuthError : "未登录";
                return Result.fail("DSH 未授权：" + hint);
            }
            if (r.status != 200) return Result.fail("HTTP " + r.status);

            JSONObject o = new JSONObject(r.body);
            JSONObject res = o.optJSONObject("result");
            if (res == null) return Result.fail("返回格式不对");
            if (!res.optBoolean("ok", false)) {
                JSONObject err = res.optJSONObject("error");
                String msg = err == null ? "未知错误" : err.optString("message", "未知错误");
                String code = err == null ? "" : err.optString("code", "");
                return Result.fail(code.isEmpty() ? msg : msg + "（" + code + "）");
            }
            JSONObject v = res.optJSONObject("value");
            return new Result(true, v == null ? new JSONObject() : v, null);
        } catch (org.json.JSONException e) {
            return Result.fail("解析失败：" + e.getMessage());
        } catch (Exception e) {
            return Result.fail("连不上：" + e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------
    // 会话列表
    // ------------------------------------------------------------------

    /** 一行会话摘要（只留界面要用的字段）。 */
    public static final class SessionRow {
        public String id;
        public String title;
        public String cwd;
        public String model;
        public boolean running;
        public boolean available;
        public long updatedAt;

        /** 侧边栏显示用：没标题就退回目录名。 */
        public String label() {
            if (title != null && !title.trim().isEmpty()) return title.trim();
            String dir = cwd == null ? "" : cwd;
            int i = dir.lastIndexOf('/');
            if (i >= 0 && i + 1 < dir.length()) return dir.substring(i + 1);
            return id == null ? "未命名" : id;
        }
    }

    /** 拉全部可见会话，按活跃度排好返回。 */
    public static Result list(String origin, List<SessionRow> out) {
        JSONObject args = new JSONObject();
        try {
            args.put("_request", new JSONObject());
        } catch (Exception ignored) {
        }
        Result r = call(origin, "session/list", args, 4000, 20000);
        if (!r.ok) return r;
        JSONArray items = r.value.optJSONArray("items");
        if (items == null) return Result.fail("列表为空");
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            if (it.optBoolean("blank", false)) continue;
            SessionRow row = new SessionRow();
            row.id = it.optString("sessionId", "");
            if (row.id.isEmpty()) continue;
            row.cwd = it.optString("cwd", "");
            row.running = it.optBoolean("running", false);
            row.available = it.optBoolean("agentAvailable", false);
            row.updatedAt = it.optLong("updatedAt", 0L);
            JSONObject pj = it.optJSONObject("projections");
            if (pj != null) {
                JSONObject vals = pj.optJSONObject("values");
                if (vals != null) {
                    row.title = vals.optString("title", null);
                    JSONObject ms = vals.optJSONObject("modelSelection");
                    if (ms != null) {
                        JSONObject last = ms.optJSONObject("lastUsed");
                        if (last != null) {
                            String p = last.optString("provider", "");
                            String m = last.optString("model", "");
                            if (!m.isEmpty()) row.model = p.isEmpty() ? m : p + "/" + m;
                        }
                    }
                }
            }
            out.add(row);
        }
        return new Result(true, r.value, null);
    }

    /**
     * 拿归档会话的 id 集合。
     *
     * 服务端有坑：`session/list` 不返回归档标记，归档状态只在 workspace 域里，
     * 而那个域唯一的读法是 `workspace/follow`——一个 stream Remote，必须开
     * /api/remote.mux 的 WebSocket 才能用，为了一个 id 列表不值得。
     *
     * 好在 `workspace/unarchiveSession` 对**不存在的 id 幂等成功**，返回体里
     * 还带着完整的 archivedSessionIds。所以拿一个假 id 去「取消归档」，实际
     * 什么都不会变，纯粹当只读探针用。
     *
     * @return 探针成功与否；失败时 out 保持原样（调用方按「没有归档信息」处理）
     */
    public static boolean archivedIds(String origin, java.util.Set<String> out) {
        JSONObject args = new JSONObject();
        try {
            JSONObject req = new JSONObject();
            // 这个 id 故意不存在：取消归档一个不存在的会话是空操作
            req.put("sessionId", "session-00000000-0000-0000-0000-000000000000");
            args.put("request", req);
        } catch (Exception ignored) {
        }
        Result r = call(origin, "workspace/unarchiveSession", args, 4000, 12000);
        if (!r.ok) return false;
        JSONArray arr = r.value.optJSONArray("archivedSessionIds");
        if (arr == null) return false;
        for (int i = 0; i < arr.length(); i++) {
            String id = arr.optString(i, "");
            if (!id.isEmpty()) out.add(id);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 消息内容块
    // ------------------------------------------------------------------

    /** 消息里的一个内容块：正文、图片、附件文件、present 出来的文件。 */
    public static final class Part {
        public static final int TEXT = 0;
        public static final int IMAGE = 1;
        public static final int FILE = 2;
        /** present 工具声明的磁盘文件（不是 attachment，走 /api/file 读）。 */
        public static final int PRESENTED = 3;

        public int kind;
        public String text;
        /** attachment 引用（image/file 两种块共用），读字节时整块回传。 */
        public JSONObject attachment;
        public String mediaType;
        public String name;
        public long bytes;
        public int width;
        public int height;
        /** PRESENTED 专用：绝对路径。 */
        public String path;
        public String description;
        /** 刚发出去、还没从服务端读回来的本地图片：直接拿本地 Uri 画，别等网络。 */
        public String localUri;
    }

    /** 一条要显示在聊天流里的东西。 */
    public static final class Msg {
        /** user / assistant。 */
        public String role;
        public String text;
        /** 图片、附件、present 文件按顺序排；纯文字消息这里只有 TEXT 块。 */
        public final List<Part> parts = new ArrayList<>();
        public long seq;
        public long time;
        public boolean error;

        /** 有没有除正文以外的东西要画。 */
        public boolean hasAttachments() {
            for (Part p : parts) {
                if (p.kind != Part.TEXT) return true;
            }
            return false;
        }
    }

    /** 一页历史的分页信息。 */
    public static final class Page {
        /** 还有更早的记录可以翻。 */
        public boolean hasMore;
        /** 这一页里最小的 seq（已经过滤掉不显示的记录），下一轮翻页用它当 beforeSeq。 */
        public long minSeq;
    }

    /**
     * 拉一页历史。
     *
     * @param beforeSeq 只取这个 seq 之前的内容（exclusive）；0 表示从最新开始
     */
    public static Result events(String origin, String sessionId, long beforeSeq,
                                int maxMessages, List<Msg> out, Page page) {
        JSONObject req = new JSONObject();
        JSONObject args = new JSONObject();
        try {
            JSONObject addr = new JSONObject();
            addr.put("kind", "session");
            addr.put("sessionId", sessionId);
            req.put("address", addr);
            req.put("maxMessages", maxMessages);
            if (beforeSeq > 0) req.put("beforeSeq", beforeSeq);
            args.put("request", req);
        } catch (Exception ignored) {
        }
        Result r = call(origin, "session/events", args, 4000, 30000);
        if (!r.ok) return r;
        JSONArray recs = r.value.optJSONArray("records");
        long minSeq = 0;
        if (recs != null) {
            for (int i = 0; i < recs.length(); i++) {
                JSONObject rec = recs.optJSONObject(i);
                if (rec == null) continue;
                JSONObject ev = rec.optJSONObject("event");
                // 翻页锚点要用**所有**记录的 seq（含被过滤掉的注入物），否则会重复拉
                if (ev != null) {
                    long sq = ev.optLong("seq", 0);
                    if (sq > 0 && (minSeq == 0 || sq < minSeq)) minSeq = sq;
                }
                Msg m = parseEvent(ev);
                if (m != null) out.add(m);
            }
        }
        if (page != null) {
            page.hasMore = r.value.optBoolean("hasMore", false);
            page.minSeq = minSeq;
        }
        return new Result(true, r.value, null);
    }

    /**
     * 把一个 SessionWireEvent 转成要显示的消息；不显示的返回 null。
     *
     * 过滤规则很重要：DSH 会把工作区指令、运行时上下文、技能目录、时间戳、
     * 插件注入等等都记成 user/message，全显示出来就是一屏机器话。只有
     * source.kind == "user" 的才是人打的字。
     *
     * 另外手机屏小，思考过程（reasoning 块）、工具调用、工具结果、step/job 事件
     * 一律不显示，只看最终结果。
     */
    private static Msg parseEvent(JSONObject ev) {
        if (ev == null) return null;
        String type = ev.optString("type", "");
        JSONObject data = ev.optJSONObject("data");
        long seq = ev.optLong("seq", 0);
        long time = ev.optLong("time", 0);

        if ("user/message".equals(type)) {
            if (data == null) return null;
            JSONObject src = data.optJSONObject("source");
            String kind = src == null ? "" : src.optString("kind", "");
            if (!"user".equals(kind)) return null;
            Msg m = new Msg();
            m.role = "user";
            m.seq = seq;
            m.time = time;
            collect(m, data.optJSONArray("content"));
            return m.parts.isEmpty() ? null : m;
        }

        if ("assistant/message".equals(type)) {
            JSONObject msg = data == null ? null : data.optJSONObject("message");
            if (msg == null) return null;
            Msg m = new Msg();
            m.role = "assistant";
            m.seq = seq;
            m.time = time;
            collect(m, msg.optJSONArray("content"));
            return m.parts.isEmpty() ? null : m;
        }

        // present 工具声明过的文件：单独一条，排在这一轮最后，可以点着下载
        if ("deliverables/presented".equals(type)) {
            if (data == null) return null;
            JSONArray files = data.optJSONArray("files");
            if (files == null || files.length() == 0) return null;
            Msg m = new Msg();
            m.role = "assistant";
            m.seq = seq;
            m.time = time;
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.optJSONObject(i);
                if (f == null) continue;
                String p = f.optString("path", "");
                if (p.isEmpty()) continue;
                Part part = new Part();
                part.kind = Part.PRESENTED;
                part.path = p;
                part.name = baseName(p);
                part.description = f.optString("description", "");
                m.parts.add(part);
            }
            return m.parts.isEmpty() ? null : m;
        }

        // tool/result、tool/call、step/*、job/* 全部不显示：手机屏装不下工具细节
        return null;
    }

    /** 把 content 块数组收进 Msg（正文拼起来，图片/附件留成 Part）。 */
    private static void collect(Msg m, JSONArray content) {
        if (content == null) return;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject b = content.optJSONObject(i);
            if (b == null) continue;
            String t = b.optString("type", "");
            if ("text".equals(t)) {
                String s = b.optString("text", "");
                if (s.isEmpty()) continue;
                Part p = new Part();
                p.kind = Part.TEXT;
                p.text = s;
                m.parts.add(p);
                if (text.length() > 0) text.append('\n');
                text.append(s);
            } else if ("image".equals(t) || "file".equals(t)) {
                JSONObject att = b.optJSONObject("attachment");
                if (att == null) continue;
                Part p = new Part();
                p.kind = "image".equals(t) ? Part.IMAGE : Part.FILE;
                p.attachment = att;
                p.name = att.optString("name", "");
                p.mediaType = att.optString("mediaType", "");
                p.bytes = att.optLong("bytes", 0L);
                p.width = att.optInt("width", 0);
                p.height = att.optInt("height", 0);
                m.parts.add(p);
            }
            // reasoning / tool-call 块直接丢：手机上不看思考过程和工具参数
        }
        m.text = text.length() == 0 ? null : text.toString();
    }

    static String baseName(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('/');
        return i >= 0 && i + 1 < path.length() ? path.substring(i + 1) : path;
    }

    // ------------------------------------------------------------------
    // 附件与文件
    // ------------------------------------------------------------------

    /** 读一条 attachment 的原始字节（图片预览、下载用户发来的附件都走这条）。 */
    public static byte[] attachmentBytes(String origin, String sessionId, JSONObject attachment) {
        if (attachment == null) return null;
        JSONObject req = new JSONObject();
        JSONObject args = new JSONObject();
        try {
            req.put("sessionId", sessionId);
            req.put("attachment", attachment);
            args.put("request", req);
        } catch (Exception ignored) {
        }
        Result r = call(origin, "session/attachment", args, 4000, 30000);
        if (!r.ok) return null;
        String data = r.value.optString("data", "");
        if (data.isEmpty()) return null;
        try {
            return Base64.decode(data, Base64.DEFAULT);
        } catch (Exception e) {
            return null;
        }
    }

    /** 读任意工作区文件的字节（present 出来的文件、回复里提到的路径）。 */
    public static byte[] fileBytes(String origin, String path) {
        if (path == null || path.isEmpty()) return null;
        try {
            String url = origin + "/api/file?path=" + DshHttp.enc(path);
            // 服务端第一次给 302 到带 v 的缓存地址，location 是相对路径，得自己接一次
            for (int hop = 0; hop < 3; hop++) {
                DshHttp.Resp r = DshHttp.fetchBytes("GET", url, null, null, 6000, 120000);
                if (r == null) return null;
                if (r.status == 200) return r.bytes;
                if (r.status < 300 || r.status >= 400) return null;
                String loc = r.header("location");
                if (loc == null || loc.isEmpty()) return null;
                url = resolveUrl(url, loc);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 拼相对 Location：支持 `?query`、`/abs`、`//host`、`http(s)://` 四种写法。 */
    private static String resolveUrl(String base, String loc) {
        if (loc.startsWith("http://") || loc.startsWith("https://")) return loc;
        String org = DshHttp.originOf(base);
        if (loc.startsWith("//")) return base.substring(0, base.indexOf("://") + 1) + loc;
        if (loc.startsWith("/")) return org + loc;
        int q = base.indexOf('?');
        String bare = q < 0 ? base : base.substring(0, q);
        if (loc.startsWith("?")) return bare + loc;
        int slash = bare.lastIndexOf('/');
        return (slash < 0 ? bare : bare.substring(0, slash + 1)) + loc;
    }

    /**
     * 上传一个本地文件/图片，拿回 receiptId（发消息时用）。
     *
     * mediaType 只对图片有意义（image/png|jpeg|webp|gif），其它一律传 null 当普通文件。
     */
    public static String upload(String origin, String sessionId, String name, String mediaType,
                                byte[] data, String[] err) {
        try {
            StringBuilder q = new StringBuilder();
            q.append(origin).append("/api/session/uploadFileBinary?sessionId=")
                    .append(DshHttp.enc(sessionId)).append("&name=").append(DshHttp.enc(name));
            if (mediaType != null && !mediaType.isEmpty()) {
                q.append("&mediaType=").append(DshHttp.enc(mediaType));
            }
            DshHttp.Resp r = DshHttp.fetchBytes("POST", q.toString(), data,
                    "application/octet-stream", 6000, 60000);
            if (r == null) {
                if (err != null && err.length > 0) err[0] = "上传没响应";
                return null;
            }
            if (r.status != 200) {
                if (err != null && err.length > 0) err[0] = "上传失败 HTTP " + r.status;
                return null;
            }
            JSONObject o = new JSONObject(r.body);
            if (!o.optBoolean("ok", false)) {
                JSONObject e = o.optJSONObject("error");
                if (err != null && err.length > 0) {
                    err[0] = e == null ? "上传被拒绝" : e.optString("message", "上传被拒绝");
                }
                return null;
            }
            JSONObject v = o.optJSONObject("value");
            String id = v == null ? "" : v.optString("receiptId", "");
            if (id.isEmpty()) {
                if (err != null && err.length > 0) err[0] = "没拿到 receiptId";
                return null;
            }
            return id;
        } catch (Exception e) {
            if (err != null && err.length > 0) err[0] = "上传出错：" + e.getClass().getSimpleName();
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 发消息
    // ------------------------------------------------------------------

    /** 读当前 cursor；发 prompt 前拿它当 afterSeq 的锚。 */
    public static Result state(String origin, String sessionId, long[] cursor, boolean[] running) {
        JSONObject req = new JSONObject();
        JSONObject args = new JSONObject();
        try {
            req.put("sessionId", sessionId);
            args.put("request", req);
        } catch (Exception ignored) {
        }
        Result r = call(origin, "session/state", args, 4000, 15000);
        if (!r.ok) return r;
        if (cursor != null && cursor.length > 0) cursor[0] = r.value.optLong("cursor", 0);
        if (running != null && running.length > 0) running[0] = r.value.optBoolean("running", false);
        return r;
    }

    /** 新建一个空会话。 */
    public static Result create(String origin, String cwd) {
        JSONObject req = new JSONObject();
        JSONObject args = new JSONObject();
        try {
            if (cwd != null && !cwd.isEmpty()) req.put("cwd", cwd);
            args.put("request", req);
        } catch (Exception ignored) {
        }
        return call(origin, "session/create", args, 4000, 20000);
    }

    /** 投一条纯文字用户消息（立即返回 accepted，不等生成）。 */
    public static Result prompt(String origin, String sessionId, String text) {
        return prompt(origin, sessionId, text, null);
    }

    /**
     * 投一条用户消息，可带若干已上传的附件（receiptId）。
     *
     * 服务端 content 只认三种块：text、image（内联 base64）、file（receiptId）。
     * 图片和普通文件都能用 file + receiptId，所以上传一次就够。
     */
    public static Result prompt(String origin, String sessionId, String text, List<String> receipts) {
        JSONObject req = new JSONObject();
        JSONObject args = new JSONObject();
        try {
            JSONArray content = new JSONArray();
            if (text != null && !text.trim().isEmpty()) {
                JSONObject part = new JSONObject();
                part.put("type", "text");
                part.put("text", text);
                content.put(part);
            }
            if (receipts != null) {
                for (String id : receipts) {
                    if (id == null || id.isEmpty()) continue;
                    JSONObject part = new JSONObject();
                    part.put("type", "file");
                    part.put("receiptId", id);
                    content.put(part);
                }
            }
            if (content.length() == 0) return Result.fail("没有可发的内容");
            req.put("sessionId", sessionId);
            req.put("requestId", UUID.randomUUID().toString());
            req.put("mode", "queue");
            req.put("content", content);
            req.put("clientTimeZone", java.util.TimeZone.getDefault().getID());
            args.put("request", req);
        } catch (Exception ignored) {
        }
        return call(origin, "session/prompt", args, 4000, 30000);
    }

    /**
     * 等这一轮跑完，顺带把新产生的 assistant 记录带回来。
     *
     * 这是取代「轮询 events」的关键：主机侧阻塞到空闲才回，手机端不用反复问。
     */
    public static Result waitIdle(String origin, String sessionId, long afterSeq,
                                  int timeoutMs, List<Msg> out) {
        JSONObject req = new JSONObject();
        JSONObject args = new JSONObject();
        try {
            req.put("sessionId", sessionId);
            req.put("timeoutMs", Math.max(1000, timeoutMs));
            if (afterSeq > 0) req.put("afterSeq", afterSeq);
            req.put("maxMessages", 20);
            args.put("request", req);
        } catch (Exception ignored) {
        }
        // 读超时给得比主机侧 timeoutMs 宽一点，让主机先超时
        Result r = call(origin, "session/wait", args, 5000, Math.max(1000, timeoutMs) + 15000);
        if (!r.ok) return r;
        JSONObject output = r.value.optJSONObject("output");
        if (output != null) {
            JSONArray recs = output.optJSONArray("records");
            if (recs != null) {
                // 注意：wait 的 output.records 是**倒序**（最新在前），跟 session/events 相反
                List<Msg> tmp = new ArrayList<>();
                for (int i = 0; i < recs.length(); i++) {
                    JSONObject rec = recs.optJSONObject(i);
                    if (rec == null) continue;
                    Msg m = parseEvent(rec.optJSONObject("event"));
                    if (m != null && "assistant".equals(m.role)) tmp.add(m);
                }
                java.util.Collections.reverse(tmp);
                out.addAll(tmp);
            }
        }
        return r;
    }

    /** 就绪态：能列会话就算连上了。 */
    public static boolean probe(String origin) {
        List<SessionRow> rows = new ArrayList<>();
        return list(origin, rows).ok;
    }

    // ------------------------------------------------------------------
    // 本地存档用的一次性转换（ChatStore 读写 JSON 用）
    // ------------------------------------------------------------------

    /** 一条消息 → 紧凑 JSON。附件块只留绘制和重新拉取需要的字段。 */
    public static JSONObject toJson(Msg m) {
        if (m == null) return null;
        JSONObject o = new JSONObject();
        try {
            if (m.role != null) o.put("role", m.role);
            if (m.text != null) o.put("text", m.text);
            if (m.seq > 0) o.put("seq", m.seq);
            if (m.time > 0) o.put("time", m.time);
            if (m.error) o.put("error", true);
            JSONArray ps = new JSONArray();
            for (Part p : m.parts) {
                JSONObject po = new JSONObject();
                po.put("kind", p.kind);
                if (p.text != null) po.put("text", p.text);
                if (p.attachment != null) po.put("attachment", p.attachment);
                if (p.mediaType != null) po.put("mediaType", p.mediaType);
                if (p.name != null) po.put("name", p.name);
                if (p.bytes > 0) po.put("bytes", p.bytes);
                if (p.width > 0) po.put("width", p.width);
                if (p.height > 0) po.put("height", p.height);
                if (p.path != null) po.put("path", p.path);
                if (p.description != null) po.put("description", p.description);
                // localUri 不存：那是本次运行的 content:// 授权，重启后已失效
                ps.put(po);
            }
            o.put("parts", ps);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** JSON → 一条消息；缺字段就当没有，坏一条跳过就好。 */
    public static Msg fromJson(JSONObject o) {
        if (o == null) return null;
        Msg m = new Msg();
        m.role = o.optString("role", "assistant");
        m.text = o.optString("text", null);
        m.seq = o.optLong("seq", 0);
        m.time = o.optLong("time", 0);
        m.error = o.optBoolean("error", false);
        JSONArray ps = o.optJSONArray("parts");
        if (ps != null) {
            for (int i = 0; i < ps.length(); i++) {
                JSONObject po = ps.optJSONObject(i);
                if (po == null) continue;
                Part p = new Part();
                p.kind = po.optInt("kind", Part.TEXT);
                p.text = po.optString("text", null);
                p.attachment = po.optJSONObject("attachment");
                p.mediaType = po.optString("mediaType", null);
                p.name = po.optString("name", null);
                p.bytes = po.optLong("bytes", 0L);
                p.width = po.optInt("width", 0);
                p.height = po.optInt("height", 0);
                p.path = po.optString("path", null);
                p.description = po.optString("description", null);
                m.parts.add(p);
            }
        }
        return m;
    }
}
