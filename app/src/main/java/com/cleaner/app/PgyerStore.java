package com.cleaner.app;

import android.content.Context;
import android.os.Environment;

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
import java.util.ArrayList;
import java.util.List;

/**
 * 开发者其他 App 的数据源：蒲公英开放 API。
 *
 * 用账号级 _api_key 调 app/listMy 就能列出本账号下全部 App（名称/版本/图标/介绍/下载直链全自动），
 * 以后新增 App 只要传到蒲公英就会自动出现在「更多 App」页，不用改代码、不用重新发版。
 *
 * 复用点：下载安装走 UpdateChecker 的那套（FileProvider + 安装未知应用引导）。
 */
public final class PgyerStore {

    private static final String BASE = "https://www.pgyer.com/apiv2/";

    public static class AppInfo {
        public String appKey = "";
        public String name = "";
        public String version = "";
        public String versionNo = "";
        public String iconUrl = "";
        public String intro = "";
        public String cate = "";
        public String shortcut = "";
        public long fileSize = 0;

        public String shortIntro(int max) {
            String s = intro == null ? "" : intro.replace("\n", " ").trim();
            return s.length() <= max ? s : s.substring(0, max) + "…";
        }

        public String pageUrl() {
            return shortcut == null || shortcut.isEmpty() ? "" : "https://www.pgyer.com/" + shortcut;
        }
    }

    public interface ListCb {
        void onDone(List<AppInfo> apps, String err);
    }

    public interface UrlCb {
        void onDone(String url, String err);
    }

    public interface DlCb {
        void onProgress(int percent);
        void onDone(File apk);
        void onError(String msg);
    }

    private PgyerStore() { }

    public static boolean configured() {
        return apiKey() != null && !apiKey().isEmpty();
    }

    private static String apiKey() {
        return BuildConfig.PGY_API_KEY;
    }

    /** 自己的 appKey（用于把自己从列表里排除） */
    private static String selfAppKey() {
        return BuildConfig.PGY_APP_KEY;
    }


    /** 蒲公英后台没填应用介绍时的兜底文案（按应用名匹配，新增应用在这里加一行即可） */
    private static String builtinIntro(String name) {
        if (name == null || name.isEmpty()) return "";
        if (name.contains("清理")) {
            return "存储分析 · 垃圾/大文件/重复文件清理 · 内置音乐视频播放器 · 病毒扫描，一个 App 管好手机空间。";
        }
        if (name.contains("月历") || name.contains("日历")) {
            return "点日期随手标记（emoji / 照片），生日、纪念日自动提醒，一键把整月日历导出成高清图。";
        }
        return "";
    }

    /** 蒲公英默认文案对用户没有信息量，命中就改用兜底 */
    private static boolean isDefaultDesc(String s) {
        return s == null || s.isEmpty() || s.startsWith("支持Android") || s.startsWith("支持 Android");
    }

    /** 拉取账号下的其他 App（含图标地址与介绍） */
    public static void loadApps(final Context ctx, final ListCb cb) {
        if (!configured()) {
            if (cb != null) cb.onDone(new ArrayList<>(), "未配置蒲公英 API Key");
            return;
        }
        new Thread(() -> {
            List<AppInfo> out = new ArrayList<>();
            String err = null;
            try {
                JSONObject r = post("app/listMy",
                        "_api_key=" + enc(apiKey()) + "&page=1");
                if (r.optInt("code") != 0) throw new IllegalStateException(r.optString("message", "接口返回异常"));
                JSONObject data = r.optJSONObject("data");
                JSONArray arr = data == null ? null : data.optJSONArray("list");
                if (arr == null) throw new IllegalStateException("返回数据异常");
                String self = selfAppKey();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject a = arr.optJSONObject(i);
                    if (a == null) continue;
                    AppInfo info = new AppInfo();
                    info.appKey = a.optString("appKey", "");
                    info.name = a.optString("buildName", "");
                    info.version = a.optString("buildVersion", "");
                    info.versionNo = a.optString("buildVersionNo", "");
                    info.shortcut = a.optString("buildShortcutUrl", "");
                    if (info.appKey.isEmpty()) continue;
                    if (!self.isEmpty() && self.equals(info.appKey)) continue;   // 不推荐自己
                    // 详情：拿图标直链 + 应用介绍
                    try {
                        JSONObject v = post("app/view",
                                "_api_key=" + enc(apiKey()) + "&appKey=" + enc(info.appKey));
                        JSONObject d = v.optJSONObject("data");
                        if (d != null) {
                            info.iconUrl = d.optString("iconUrl", "");
                            String desc = d.optString("buildDescription", "");
                            if (isDefaultDesc(desc)) desc = d.optString("appDownloadDescription", "");
                            if (isDefaultDesc(desc)) desc = builtinIntro(info.name);
                            info.intro = desc;
                            info.cate = d.optString("buildCates", "");
                            String sc = d.optString("buildShortcutUrl", "");
                            if (!sc.isEmpty()) info.shortcut = sc;
                            if (info.name.isEmpty()) info.name = d.optString("buildName", "");
                            if (info.version.isEmpty()) info.version = d.optString("buildVersion", "");
                            info.fileSize = d.optLong("buildFileSize", 0);
                        }
                    } catch (Throwable ignored) {
                        // 单个 App 详情失败不影响整体列表
                    }
                    out.add(info);
                }
            } catch (Throwable t) {
                err = shortMsg(t);
            }
            if (cb != null) cb.onDone(out, err);
        }, "pgyer-list").start();
    }

    /** 取某个 App 最新版的安装直链 */
    public static void downloadUrl(final String appKey, final UrlCb cb) {
        new Thread(() -> {
            try {
                JSONObject r = post("app/check", "_api_key=" + enc(apiKey())
                        + "&appKey=" + enc(appKey) + "&buildVersionNo=0");
                if (r.optInt("code") != 0) throw new IllegalStateException(r.optString("message", "接口返回异常"));
                JSONObject d = r.optJSONObject("data");
                String url = d == null ? "" : d.optString("downloadURL", "");
                if (url.isEmpty()) throw new IllegalStateException("没有拿到下载地址");
                if (cb != null) cb.onDone(url, null);
            } catch (Throwable t) {
                if (cb != null) cb.onDone(null, shortMsg(t));
            }
        }, "pgyer-url").start();
    }

    /** App 内下载 APK（带进度） */
    public static void downloadApk(final Context ctx, final String url, final String name, final DlCb cb) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
                int rc = conn.getResponseCode();
                if (rc < 200 || rc >= 300) {
                    cb.onError("下载失败 " + rc);
                    return;
                }
                long total = conn.getContentLength();
                File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = ctx.getCacheDir();
                if (dir != null && !dir.exists()) dir.mkdirs();
                String safe = (name == null || name.isEmpty() ? "app" : name.replaceAll("[\\\\/:*?\"<>|]", "_"));
                File out = new File(dir, safe + "-" + System.currentTimeMillis() + ".apk");
                try (InputStream in = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    int read;
                    long done = 0;
                    int last = -1;
                    while ((read = in.read(buf)) != -1) {
                        fos.write(buf, 0, read);
                        done += read;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct != last) {
                                last = pct;
                                cb.onProgress(Math.min(pct, 99));
                            }
                        }
                    }
                }
                conn.disconnect();
                long len = out.length();
                boolean valid = len > 500_000;
                if (valid) {
                    try (InputStream chk = new java.io.FileInputStream(out)) {
                        valid = chk.read() == 'P' && chk.read() == 'K';
                    } catch (Throwable t) {
                        valid = false;
                    }
                }
                if (!valid) {
                    out.delete();
                    cb.onError("下载文件不完整，请重试");
                    return;
                }
                cb.onProgress(100);
                cb.onDone(out);
            } catch (Throwable t) {
                cb.onError("下载失败：" + shortMsg(t));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "pgyer-download").start();
    }

    /** 调起系统安装器（复用已有的 FileProvider 配置） */
    public static void install(Context ctx, File apk) {
        UpdateChecker.installApk(ctx, apk);
    }

    public static boolean canInstall(Context ctx) {
        return UpdateChecker.canInstall(ctx);
    }

    public static void requestInstallPermission(Context ctx) {
        UpdateChecker.requestInstallPermission(ctx);
    }

    // ===== 网络 =====
    private static JSONObject post(String path, String body) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
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
            return new JSONObject(sb.toString());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String shortMsg(Throwable t) {
        String m = t.getMessage();
        if (m == null) m = t.getClass().getSimpleName();
        return m.length() > 60 ? m.substring(0, 60) : m;
    }
}
