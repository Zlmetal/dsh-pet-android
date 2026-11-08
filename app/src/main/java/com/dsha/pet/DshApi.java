package com.dsha.pet;

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

    // ------------------------------------------------------------------
    // 历史
    // ------------------------------------------------------------------

    /** 一条要显示在聊天流里的东西。 */
    public static final class Msg {
        /** user / assistant / tool。 */
        public String role;
        public String text;
        public long seq;
        public long time;
        public boolean error;
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
     */
    private static Msg parseEvent(JSONObject ev) {
        if (ev == null) return null;
        String type = ev.optString("type", "");
        JSONObject data = ev.optJSONObject("data");
        long seq = ev.optLong("seq", 0);
        long time = ev.optLong("time", 0);

        if ("user/message".equals(type)) {
            JSONObject src = data == null ? null : data.optJSONObject("source");
            String kind = src == null ? "" : src.optString("kind", "");
            if (!"user".equals(kind)) return null;
            String text = textOf(data.optJSONArray("content"));
            if (text == null || text.trim().isEmpty()) return null;
            Msg m = new Msg();
            m.role = "user";
            m.text = text.trim();
            m.seq = seq;
            m.time = time;
            return m;
        }

        if ("assistant/message".equals(type)) {
            JSONObject msg = data == null ? null : data.optJSONObject("message");
            if (msg == null) return null;
            // 只取正文块；reasoning 是思考过程，tool-call 由 tool/result 那边呈现
            String text = textOf(msg.optJSONArray("content"));
            if (text == null || text.trim().isEmpty()) return null;
            Msg m = new Msg();
            m.role = "assistant";
            m.text = text.trim();
            m.seq = seq;
            m.time = time;
            return m;
        }

        // tool/result 不显示：手机屏小，一条 send_meme 的候选清单就能糊满整屏
        // （十几行「[表情: …] 描述」），对读对话没有任何帮助。只保留人和鱼的正文。
        if ("tool/result".equals(type)) {
            return null;
        }
        return null;
    }

    /** 把 content 块数组拼成纯文本（只要 type=="text" 的块）。 */
    private static String textOf(JSONArray content) {
        if (content == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject b = content.optJSONObject(i);
            if (b == null) continue;
            if (!"text".equals(b.optString("type", ""))) continue;
            String t = b.optString("text", "");
            if (t.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t);
        }
        return sb.length() == 0 ? null : sb.toString();
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

    /** 投一条用户消息（立即返回 accepted，不等生成）。 */
    public static Result prompt(String origin, String sessionId, String text) {
        JSONObject req = new JSONObject();
        JSONObject args = new JSONObject();
        try {
            JSONArray content = new JSONArray();
            JSONObject part = new JSONObject();
            part.put("type", "text");
            part.put("text", text);
            content.put(part);
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
}
