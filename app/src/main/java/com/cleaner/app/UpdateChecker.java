package com.cleaner.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.util.Base64;

import androidx.core.content.FileProvider;

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

/**
 * 版本检查 + App 内下载安装（Loadly.io 开放 API）。
 *
 * 接口：POST https://api.loadly.io/apiv2/app/check   (application/x-www-form-urlencoded)
 *   入参 _api_key / appKey / buildVersion / buildVersionNo / buildBuildVersion
 *   出参 buildHaveNewVersion、buildVersion、buildVersionNo、buildUpdateDescription、
 *        downloadURL（302 跳转到直链 APK）、needForceUpdate、buildFileSize、buildShortcutUrl
 *
 * 密钥不在源码里明文出现：构建时由 local.properties 注入，并以异或+Base64 存放在 BuildConfig，
 * 运行时解密（见 decode()）。未配置 key 时只提示"未配置"，不会误报有新版本。
 */
public final class UpdateChecker {

    private static final String ENDPOINT = "https://api.loadly.io/apiv2/app/check";
    private static final byte XOR_MASK = 0x5A;

    private UpdateChecker() { }

    /** 运行时解密 BuildConfig 里的密钥（构建期异或+Base64 混淆） */
    private static String decode(String enc) {
        if (enc == null || enc.isEmpty()) return "";
        try {
            byte[] b = Base64.decode(enc, Base64.DEFAULT);
            for (int i = 0; i < b.length; i++) b[i] = (byte) (b[i] ^ XOR_MASK);
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String apiKey() { return decode(BuildConfig.LOADLY_API_KEY_ENC); }
    private static String appKey() { return decode(BuildConfig.LOADLY_APP_KEY_ENC); }

    public static boolean configured() {
        return !apiKey().isEmpty() && !appKey().isEmpty();
    }

    public interface Callback {
        /**
         * @param hasNew 服务端判定有新版本
         * @param version 新版本号（如 1.0.1）
         * @param build   新版本编译号（versionCode）
         * @param note    更新说明
         * @param force   是否强制更新
         */
        void onResult(boolean hasNew, String version, long build, String note, boolean force);
        void onError(String msg);
    }

    public interface DownloadCallback {
        void onProgress(int percent);
        void onComplete(File apk);
        void onError(String msg);
    }

    private static volatile String latestDownloadUrl = null;
    private static volatile String latestShortcut = null;

    public static void check(final Context ctx, final Callback cb) {
        if (!configured()) {
            cb.onError("未配置更新服务");
            return;
        }
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                String body = "_api_key=" + URLEncoder.encode(apiKey(), "UTF-8")
                        + "&appKey=" + URLEncoder.encode(appKey(), "UTF-8")
                        + "&buildVersion=" + URLEncoder.encode(BuildConfig.VERSION_NAME, "UTF-8")
                        + "&buildVersionNo=" + BuildConfig.VERSION_CODE
                        + "&buildBuildVersion=" + BuildConfig.VERSION_CODE;
                conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setRequestProperty("User-Agent", "Cleaner/" + BuildConfig.VERSION_NAME);
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
                conn.disconnect();

                JSONObject resp = new JSONObject(sb.toString());
                if (resp.optInt("code") != 0) {
                    String m = resp.optString("message", "");
                    cb.onError(m.isEmpty() ? "检查失败" : m);
                    return;
                }
                JSONObject data = resp.optJSONObject("data");
                if (data == null) {
                    cb.onError("检查失败：返回数据异常");
                    return;
                }
                boolean hasNew = data.optBoolean("buildHaveNewVersion", false);
                boolean force = data.optBoolean("needForceUpdate", false);
                String version = data.optString("buildVersion", "");
                long build = data.optLong("buildVersionNo", 0);
                String note = data.optString("buildUpdateDescription", "");
                String url = data.optString("downloadURL", "");
                String shortcut = data.optString("buildShortcutUrl", "");
                latestDownloadUrl = url.isEmpty() ? null : url;
                // buildShortcutUrl 可能是完整地址也可能只是短码
                if (!shortcut.isEmpty()) {
                    latestShortcut = shortcut.startsWith("http") ? shortcut : "https://loadly.io/" + shortcut;
                }
                // 双保险：服务端已判断，这里再比一次 versionCode，避免误报
                if (hasNew && build > 0 && build <= BuildConfig.VERSION_CODE) hasNew = false;
                cb.onResult(hasNew, version, build, note, force);
            } catch (Exception e) {
                cb.onError("网络异常，请检查网络后重试");
            }
        }, "update-check").start();
    }

    public static void downloadApk(final Context ctx, final String version, final DownloadCallback cb) {
        final String url = latestDownloadUrl;
        if (url == null || url.isEmpty()) {
            cb.onError("未获取到下载链接");
            return;
        }
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(true);
                // 不加 UA 时 Cloudflare 可能拦截
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
                int rc = conn.getResponseCode();
                if (rc < 200 || rc >= 300) {
                    cb.onError("下载失败 " + rc);
                    return;
                }
                long total = conn.getContentLength();
                if (total <= 0) {
                    String len = conn.getHeaderField("Content-Length");
                    try { total = Long.parseLong(len); } catch (Exception ignored) { }
                }
                File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = ctx.getCacheDir();
                if (dir != null && !dir.exists()) dir.mkdirs();
                File out = new File(dir, "cleaner-" + version + ".apk");
                if (out.exists() && !out.delete()) {
                    out = new File(dir, "cleaner-" + version + "-" + System.currentTimeMillis() + ".apk");
                }
                try (InputStream in = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    int read;
                    long downloaded = 0;
                    int lastPct = -1;
                    while ((read = in.read(buf)) != -1) {
                        fos.write(buf, 0, read);
                        downloaded += read;
                        if (total > 0) {
                            int pct = (int) (downloaded * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                cb.onProgress(Math.min(pct, 99));
                            }
                        }
                    }
                }
                conn.disconnect();
                // 校验确实是 APK（PK 开头）+ 大小差不多，避免下到错误页
                long len = out.length();
                boolean valid = len > 500_000;
                if (valid) {
                    try (InputStream chk = new java.io.FileInputStream(out)) {
                        valid = chk.read() == 'P' && chk.read() == 'K';
                    } catch (Exception e) {
                        valid = false;
                    }
                }
                // 有 Content-Length 时再核对字节数（允许 1KB 误差）
                if (valid && total > 0 && Math.abs(len - total) > 1024) valid = false;
                if (!valid) {
                    out.delete();
                    cb.onError("下载文件不完整，请重试");
                    return;
                }
                cb.onProgress(100);
                cb.onComplete(out);
            } catch (Exception e) {
                cb.onError("下载失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage()));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }, "update-download").start();
    }

    /** 调起系统安装器（走 FileProvider，Android 7+ 需要） */
    public static void installApk(Context ctx, File apk) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ctx.startActivity(intent);
        } catch (Exception e) {
            openDownloadPage(ctx);
        }
    }

    public static boolean canInstall(Context ctx) {
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    /** 引导用户打开「安装未知应用」开关 */
    public static void requestInstallPermission(Context ctx) {
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + ctx.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Exception e) {
            openDownloadPage(ctx);
        }
    }

    /** 兜底：用浏览器打开分发页（App 内下载失败时用） */
    public static void openDownloadPage(Context ctx) {
        try {
            String url = latestShortcut;
            if (url == null || url.isEmpty()) {
                String s = BuildConfig.LOADLY_SHORTCUT;
                url = (s == null || s.isEmpty()) ? "https://loadly.io" : "https://loadly.io/" + s;
            }
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) { }
    }
}
