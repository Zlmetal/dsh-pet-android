package com.dsha.pet;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话历史的本地存档。
 *
 * 打开会话先拿这里存的那份直接画出来，屏幕上立刻有东西；网络回来的再合并进去。
 * 不做增量同步——每轮把「服务端这一页 + 本地老消息」按 seq 去重合并，整份重写，
 * 文件最多留 {@link #MAX_KEEP} 条，再老的由用户下拉现拉。
 *
 * 存的是 JSON，不是二进制：耦合小、坏了单条跳过，不会整份读不出来。
 */
final class ChatStore {

    /** 每个会话最多留多少条，超了从头砍。 */
    private static final int MAX_KEEP = 400;

    private ChatStore() {
    }

    /** 会话号 → 文件名。会话号是 UUID 一类的安全字符，保险起见还是清洗一遍。 */
    private static File fileOf(Context c, String sid) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sid.length() && sb.length() < 80; i++) {
            char ch = sid.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '-' || ch == '_';
            sb.append(ok ? ch : '_');
        }
        File dir = new File(c.getFilesDir(), "chat");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, sb + ".json");
    }

    /** 读本地存档；没有或读坏了都返回空表，不抛。 */
    static List<DshApi.Msg> load(Context c, String sid) {
        List<DshApi.Msg> out = new ArrayList<>();
        if (sid == null || sid.isEmpty()) return out;
        File f = fileOf(c, sid);
        if (!f.exists()) return out;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(f), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                DshApi.Msg m = DshApi.fromJson(arr.optJSONObject(i));
                if (m != null && !m.parts.isEmpty()) out.add(m);
            }
        } catch (Exception ignored) {
        } finally {
            close(r);
        }
        return out;
    }

    /**
     * 把 server 这一页并进本地存档，返回合并后的完整列表（按 seq 升序）。
     *
     * 去重键优先用 seq；seq 为 0 的（本地刚发出的那条、present 之类）退回
     * 「角色 + 正文 + 附件名」当键，避免同一句话被存两遍。
     */
    static List<DshApi.Msg> merge(Context c, String sid, List<DshApi.Msg> server) {
        List<DshApi.Msg> old = load(c, sid);
        List<DshApi.Msg> all = new ArrayList<>(old);
        Set<String> seen = new HashSet<>();
        for (DshApi.Msg m : old) seen.add(keyOf(m));
        for (DshApi.Msg m : server) {
            if (seen.add(keyOf(m))) all.add(m);
        }
        // seq 稳定排序：没有 seq 的排在它原本的位置上（保持插入序）
        List<DshApi.Msg> sorted = new ArrayList<>(all);
        for (int i = 1; i < sorted.size(); i++) {
            DshApi.Msg cur = sorted.get(i);
            if (cur.seq <= 0) continue;
            int j = i - 1;
            while (j >= 0 && sorted.get(j).seq > cur.seq) {
                sorted.set(j + 1, sorted.get(j));
                j--;
            }
            sorted.set(j + 1, cur);
        }
        if (sorted.size() > MAX_KEEP) {
            sorted = new ArrayList<>(sorted.subList(sorted.size() - MAX_KEEP, sorted.size()));
        }
        save(c, sid, sorted);
        return sorted;
    }

    private static String keyOf(DshApi.Msg m) {
        if (m.seq > 0) return "s" + m.seq;
        StringBuilder sb = new StringBuilder("k");
        sb.append(m.role).append('|').append(m.text == null ? "" : m.text);
        for (DshApi.Part p : m.parts) {
            if (p.kind != DshApi.Part.TEXT) sb.append('|').append(p.name);
        }
        return sb.toString();
    }

    private static void save(Context c, String sid, List<DshApi.Msg> msgs) {
        if (sid == null || sid.isEmpty()) return;
        JSONArray arr = new JSONArray();
        for (DshApi.Msg m : msgs) arr.put(DshApi.toJson(m));
        FileOutputStream fos = null;
        try {
            // 先写临时文件再改名：写到一半被杀进程也不会把旧存档毁掉
            File f = fileOf(c, sid);
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            fos = new FileOutputStream(tmp);
            OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            w.write(arr.toString());
            w.flush();
            w.close();
            fos = null;
            if (!tmp.renameTo(f)) {
                tmp.delete();
            }
        } catch (Exception ignored) {
        } finally {
            close(fos);
        }
    }

    /** 只存这一条（发出去的消息先落盘，别等网络回来）。 */
    static void append(Context c, String sid, DshApi.Msg m) {
        if (sid == null || m == null) return;
        List<DshApi.Msg> one = new ArrayList<>();
        one.add(m);
        merge(c, sid, one);
    }

    /** 清掉某个会话的存档（新建会话不需要，切会话也用不上，留给以后）。 */
    static void clear(Context c, String sid) {
        if (sid == null || sid.isEmpty()) return;
        try {
            fileOf(c, sid).delete();
        } catch (Exception ignored) {
        }
    }

    private static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}
