package com.cleaner.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 病毒特征库：本地保存的「恶意文件 SHA-256 → 家族/类型」索引。
 *
 * 数据来源（abuse.ch 的 MalwareBazaar，需免费 Auth-Key）：
 *   - get_recent   拉取最近公开的恶意样本哈希
 *   - get_taginfo&tag=apk  拉取标记为 Android 的样本哈希
 *   - get_info&hash=xxx    单文件精确查询（家族名 / 文件类型 / 首次发现）
 * 也支持「自定义清单源」：任意 URL 返回纯文本，每行 sha256[ ,家族名]。
 *
 * Auth-Key 免费注册：https://auth.abuse.ch/  （注册后在个人页复制 Auth-Key）
 */
public final class VirusDb {

    private static final String API = "https://mb-api.abuse.ch/api/v1/";
    private static final String DB_FILE = "virus_db.txt";
    private static final int MAX_ENTRIES = 200000;

    public static class Entry {
        public final String sha256;
        public final String family;
        public final String fileType;
        public final String firstSeen;
        public final String fileName;

        Entry(String sha256, String family, String fileType, String firstSeen, String fileName) {
            this.sha256 = sha256;
            this.family = family == null ? "" : family;
            this.fileType = fileType == null ? "" : fileType;
            this.firstSeen = firstSeen == null ? "" : firstSeen;
            this.fileName = fileName == null ? "" : fileName;
        }

        public String label() {
            if (!family.isEmpty()) return family;
            if (!fileType.isEmpty()) return fileType;
            return "恶意样本";
        }
    }

    public interface SyncCallback {
        void onStep(String msg);
        void onDone(int added, int total, String err);
    }

    private static Map<String, Entry> index;

    private VirusDb() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("cleaner", Context.MODE_PRIVATE);
    }

    /** 优先用界面上填的 key，其次用打包时注入的 key */
    public static String authKey(Context c) {
        String k = prefs(c).getString("mb_key", "");
        if (k == null || k.isEmpty()) k = BuildConfig.MB_AUTH_KEY;
        return k == null ? "" : k.trim();
    }

    public static void setAuthKey(Context c, String key) {
        prefs(c).edit().putString("mb_key", key == null ? "" : key.trim()).apply();
    }

    public static String feedUrl(Context c) {
        String u = prefs(c).getString("virus_feed", "");
        if (u == null || u.isEmpty()) u = BuildConfig.VIRUS_FEED_URL;
        return u == null ? "" : u.trim();
    }

    public static void setFeedUrl(Context c, String url) {
        prefs(c).edit().putString("virus_feed", url == null ? "" : url.trim()).apply();
    }

    public static int count(Context c) {
        ensureLoaded(c);
        return index.size();
    }

    public static boolean ready(Context c) {
        return count(c) > 0;
    }

    public static String lastSync(Context c) {
        long t = prefs(c).getLong("vdb_time", 0);
        if (t <= 0) return "从未更新";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date(t));
    }

    public static String source(Context c) {
        return prefs(c).getString("vdb_source", "");
    }

    /** 首页入口用的一句摘要 */
    public static String summary(Context c) {
        int n = count(c);
        if (n <= 0) return "病毒库未更新（需免费 Auth-Key 或自定义清单源）";
        String s = source(c);
        return "病毒库 " + n + " 条" + (s.isEmpty() ? "" : " · " + s) + " · " + lastSync(c);
    }

    // ===== 本地库读写 =====
    public static synchronized void ensureLoaded(Context c) {
        if (index != null) return;
        Map<String, Entry> map = new LinkedHashMap<>();
        try {
            File f = new File(c.getFilesDir(), DB_FILE);
            if (f.exists()) {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        String[] p = line.split("\\|", -1);
                        if (p.length < 1 || p[0].length() != 64) continue;
                        map.put(p[0].toLowerCase(Locale.ROOT), new Entry(
                                p[0].toLowerCase(Locale.ROOT),
                                p.length > 1 ? p[1] : "",
                                p.length > 2 ? p[2] : "",
                                p.length > 3 ? p[3] : "",
                                p.length > 4 ? p[4] : ""));
                    }
                }
            }
        } catch (Throwable ignored) { }
        index = map;
    }

    private static synchronized void save(Context c) {
        try {
            File f = new File(c.getFilesDir(), DB_FILE);
            try (OutputStream os = new FileOutputStream(f)) {
                StringBuilder sb = new StringBuilder();
                for (Entry e : index.values()) {
                    sb.append(e.sha256).append('|').append(e.family).append('|')
                            .append(e.fileType).append('|').append(e.firstSeen).append('|')
                            .append(e.fileName).append('\n');
                    if (sb.length() > 256 * 1024) {
                        os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                        sb.setLength(0);
                    }
                }
                if (sb.length() > 0) os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
            prefs(c).edit().putLong("vdb_time", System.currentTimeMillis()).apply();
        } catch (Throwable ignored) { }
    }

    public static synchronized void clear(Context c) {
        ensureLoaded(c);
        index.clear();
        try {
            new File(c.getFilesDir(), DB_FILE).delete();
        } catch (Throwable ignored) { }
        prefs(c).edit().putLong("vdb_time", 0).putString("vdb_source", "").apply();
    }

    /** 命中查询 */
    public static Entry lookup(Context c, String sha256) {
        if (sha256 == null) return null;
        ensureLoaded(c);
        return index.get(sha256.toLowerCase(Locale.ROOT));
    }

    /** 按文件名模糊搜索（本地库） */
    public static List<Entry> searchByFileName(Context c, String kw, int limit) {
        ensureLoaded(c);
        List<Entry> out = new ArrayList<>();
        String k = kw.toLowerCase(Locale.ROOT);
        for (Entry e : index.values()) {
            if (!e.fileName.isEmpty() && e.fileName.toLowerCase(Locale.ROOT).contains(k)) {
                out.add(e);
                if (out.size() >= limit) break;
            }
        }
        return out;
    }

    private static synchronized int merge(Context c, List<Entry> add) {
        ensureLoaded(c);
        int added = 0;
        for (Entry e : add) {
            if (index.size() >= MAX_ENTRIES) break;
            if (!index.containsKey(e.sha256)) added++;
            index.put(e.sha256, e);
        }
        save(c);
        return added;
    }

    private static void setSource(Context c, String src) {
        prefs(c).edit().putString("vdb_source", src).apply();
    }

    // ===== 同步：MalwareBazaar =====
    public static void sync(Context c, SyncCallback cb) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            String key = authKey(app);
            String feed = feedUrl(app);
            int totalAdded = 0;
            boolean anyOk = false;
            String err = null;

            if (!key.isEmpty()) {
                try {
                    if (cb != null) cb.onStep("正在同步 MalwareBazaar 最近样本…");
                    int a = fetchMb(app, key, "get_recent&selector=1000");
                    totalAdded += a;
                    anyOk = true;
                } catch (Throwable t) {
                    err = "MalwareBazaar 同步失败：" + shortMsg(t);
                }
                try {
                    if (cb != null) cb.onStep("正在同步 Android(APK) 恶意样本…");
                    int b = fetchMb(app, key, "get_taginfo&tag=apk&limit=1000");
                    totalAdded += b;
                    anyOk = true;
                } catch (Throwable t) {
                    if (err == null) err = "Android 样本同步失败：" + shortMsg(t);
                }
            }

            if (!feed.isEmpty()) {
                try {
                    if (cb != null) cb.onStep("正在同步自定义清单源…");
                    int n = fetchFeed(app, feed);
                    totalAdded += n;
                    anyOk = true;
                } catch (Throwable t) {
                    if (err == null) err = "自定义源同步失败：" + shortMsg(t);
                }
            }

            if (anyOk) setSource(app, key.isEmpty() ? "自定义清单源" : "MalwareBazaar(abuse.ch)");
            if (cb != null) cb.onDone(totalAdded, count(app), err);
        }, "virus-sync").start();
    }

    private static int fetchMb(Context c, String key, String query) throws Exception {
        String body = "query=" + query;
        String resp = post(API, body, key);
        JSONObject o = new JSONObject(resp);
        String status = o.optString("query_status", "");
        if (!"ok".equals(status)) {
            if ("unknown_auth_key".equals(status) || "unauthorized".equals(status)) {
                throw new IllegalStateException("Auth-Key 无效");
            }
            if ("no_results".equals(status) || "illegal_hash".equals(status)) return 0;
            throw new IllegalStateException(status.isEmpty() ? "返回异常" : status);
        }
        JSONArray arr = o.optJSONArray("data");
        if (arr == null) return 0;
        List<Entry> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject d = arr.optJSONObject(i);
            if (d == null) continue;
            String sha = d.optString("sha256_hash", "");
            if (sha.length() != 64) continue;
            list.add(new Entry(sha, d.optString("signature", ""), d.optString("file_type", ""),
                    d.optString("first_seen", ""), d.optString("file_name", "")));
        }
        return merge(c, list);
    }

    private static int fetchFeed(Context c, String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "Cleaner/" + BuildConfig.VERSION_NAME);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            List<Entry> list = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String sha = null, family = "", name = "";
                // 支持 "sha256", "sha256,family" 或 "sha256,family,filename"
                String[] p = line.split("[,\\t]");
                for (String s : p) {
                    String v = s.trim().replace("\"", "");
                    if (sha == null && v.length() == 64 && v.matches("(?i)[0-9a-f]{64}")) {
                        sha = v;
                    } else if (!v.isEmpty() && v.length() != 64 && family.isEmpty()) {
                        family = v;
                    } else if (!v.isEmpty() && v.length() != 64) {
                        name = v;
                    }
                }
                if (sha == null) continue;
                list.add(new Entry(sha, family, "", "", name));
            }
            r.close();
            return merge(c, list);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 单哈希在线精确查询（需要 Auth-Key），返回家族名等；失败抛异常 */
    public static Entry queryOnline(Context c, String sha256) throws Exception {
        String key = authKey(c);
        if (key.isEmpty()) throw new IllegalStateException("未配置 Auth-Key");
        String resp = post(API, "query=get_info&hash=" + URLEncoder.encode(sha256, "UTF-8"), key);
        JSONObject o = new JSONObject(resp);
        String status = o.optString("query_status", "");
        if ("hash_not_found".equals(status) || "no_results".equals(status)) return null;
        if ("unknown_auth_key".equals(status)) throw new IllegalStateException("Auth-Key 无效");
        JSONArray arr = o.optJSONArray("data");
        if (arr == null || arr.length() == 0) return null;
        JSONObject d = arr.getJSONObject(0);
        Entry e = new Entry(d.optString("sha256_hash", sha256), d.optString("signature", ""),
                d.optString("file_type", ""), d.optString("first_seen", ""), d.optString("file_name", ""));
        // 顺带存进本地库
        List<Entry> one = new ArrayList<>();
        one.add(e);
        merge(c, one);
        return e;
    }

    private static String post(String url, String body, String authKey) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("User-Agent", "Cleaner/" + BuildConfig.VERSION_NAME);
            if (authKey != null && !authKey.isEmpty()) conn.setRequestProperty("Auth-Key", authKey);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
            }
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String shortMsg(Throwable t) {
        String m = t.getMessage();
        if (m == null) m = t.getClass().getSimpleName();
        return m.length() > 60 ? m.substring(0, 60) : m;
    }

    /** 供在线查询跳转用 */
    public static Map<String, String> lookupPages(String sha256) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("VirusTotal", "https://www.virustotal.com/gui/file/" + sha256);
        m.put("MalwareBazaar", "https://bazaar.abuse.ch/sample/" + sha256 + "/");
        m.put("Koodous(安卓)", "https://koodous.com/apks/" + sha256);
        return m;
    }
}
