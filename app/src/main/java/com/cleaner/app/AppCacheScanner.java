package com.cleaner.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 应用专清：按「应用」维度统计外部存储占用，并挑出该应用下可以安全清理的缓存项。
 *
 * 只碰这三类目录（都是应用自己的外部数据，不含用户的相册/文档/下载）：
 *   /sdcard/Android/data/&lt;包名&gt;
 *   /sdcard/Android/obb/&lt;包名&gt;
 *   /sdcard/Android/media/&lt;包名&gt;
 *
 * 可清理项只认「明确的缓存类目录/临时文件」（cache、code_cache、temp、log、thumbnails…），
 * 应用自己的 files/ 与用户数据一律不动 —— 宁可清少了，也不能清错。
 * 读 Android/data 需要「所有文件访问」权限（Android 11+）。
 */
public final class AppCacheScanner {

    private AppCacheScanner() { }

    /** 常用「专清」应用（包名, 显示名）——装了的才显示 */
    public static final String[][] POPULAR = {
            {"com.tencent.mm", "微信"},
            {"com.tencent.mobileqq", "QQ"},
            {"com.tencent.tim", "TIM"},
            {"com.ss.android.ugc.aweme", "抖音"},
            {"com.smile.gifmaker", "快手"},
            {"com.taobao.taobao", "淘宝"},
            {"com.eg.android.AlipayGphone", "支付宝"},
            {"com.xingin.xhs", "小红书"},
            {"tv.danmaku.bili", "哔哩哔哩"},
            {"com.sina.weibo", "微博"},
            {"com.tencent.mtt", "QQ浏览器"},
            {"com.tencent.qqlive", "腾讯视频"},
            {"com.youku.phone", "优酷"},
            {"com.jingdong.app.mall", "京东"},
            {"com.tencent.karaoke", "全民K歌"},
            {"com.netease.cloudmusic", "网易云音乐"},
    };

    /** 目录名命中这些 = 缓存，可整目录清理 */
    private static final Set<String> CACHE_DIR_NAMES = new HashSet<>(Arrays.asList(
            "cache", ".cache", "code_cache", "codecache", "temp", "tmp", ".tmp", "logs", "log",
            "thumbnails", ".thumbnails", "image_cache", "imagecache", "imgcache",
            "webviewcache", "webview_cache", "txcache", "discovertmp",
            "trash", ".trash", "recycle", ".recycle", "acache", ".acache"));

    /** 文件后缀命中这些 = 临时文件，可清理 */
    private static final Set<String> CACHE_FILE_EXT = new HashSet<>(Arrays.asList(
            "tmp", "temp", "log", "log1", "log2", "bak", "old", "part", "partial", "dmp", "trace",
            "chk", "thumb", "thumbnail", "crdownload"));

    private static final int MAX_DEPTH = 4;      // Android/data/<包>/x/y/z 之内找缓存
    private static final int MAX_ITEMS = 800;    // 单个应用最多列 800 项
    private static final int MAX_WALK_DEPTH = 10;

    public static class AppEntry {
        public final String pkg;
        public String label = "";
        public Drawable icon;
        public long totalSize;     // 该应用的外部存储占用
        public long cacheSize;     // 其中可清理的缓存
        public int cacheCount;
        public int files;
        public boolean popular;
        public boolean installed;

        AppEntry(String pkg) { this.pkg = pkg; }
    }

    public static volatile List<AppEntry> lastApps;       // 有外部数据的应用（按大小降序）
    public static volatile List<AppEntry> lastInstalled;  // 已安装应用（按名称，供「增加应用」）
    public static volatile boolean running;

    /** 最近一次「点进某个应用」得到的可清理清单：给 FileListActivity(mode=appjunk) 用 */
    public static volatile List<ScanEngine.Item> pendingItems;
    public static volatile String pendingTitle = "";
    public static volatile String pendingPkg = "";

    public interface Callback {
        void onProgress(int done, int total, String current);
        void onDone(List<AppEntry> apps);
    }

    // ===== 路径 =====
    private static File ext() {
        return Environment.getExternalStorageDirectory();
    }

    public static File dataDir(String pkg) { return new File(ext(), "Android/data/" + pkg); }
    public static File obbDir(String pkg) { return new File(ext(), "Android/obb/" + pkg); }
    public static File mediaDir(String pkg) { return new File(ext(), "Android/media/" + pkg); }

    public static List<File> dirsOf(String pkg) {
        List<File> out = new ArrayList<>();
        File d = dataDir(pkg);
        if (d.exists()) out.add(d);
        File o = obbDir(pkg);
        if (o.exists()) out.add(o);
        File m = mediaDir(pkg);
        if (m.exists()) out.add(m);
        return out;
    }

    public static boolean hasAnyDir(String pkg) {
        return !dirsOf(pkg).isEmpty();
    }

    // ===== 扫描 =====
    public static void scan(final Context ctx, final Callback cb) {
        if (running) return;
        running = true;
        new Thread(() -> {
            List<AppEntry> apps = scanNow(ctx, cb);
            lastApps = apps;
            lastInstalled = installedApps(ctx);
            running = false;
            if (cb != null) cb.onDone(apps);
        }, "app-clean-scan").start();
    }

    private static List<AppEntry> scanNow(Context ctx, Callback cb) {
        PackageManager pm = ctx.getPackageManager();
        List<String> pkgs = new ArrayList<>();

        File base = new File(ext(), "Android/data");
        File[] kids = base.listFiles();
        if (kids != null) {
            for (File k : kids) if (k.isDirectory()) pkgs.add(k.getName());
        }
        File obbBase = new File(ext(), "Android/obb");
        File[] okids = obbBase.listFiles();
        if (okids != null) {
            for (File k : okids) if (k.isDirectory() && !pkgs.contains(k.getName())) pkgs.add(k.getName());
        }
        // 常用应用即使暂时没有外部数据也列出来（有些刚装还没生成）
        for (String[] pop : POPULAR) {
            if (!pkgs.contains(pop[0]) && isInstalled(pm, pop[0])) pkgs.add(pop[0]);
        }

        List<AppEntry> out = new ArrayList<>();
        int total = pkgs.size();
        for (int i = 0; i < total; i++) {
            if (Thread.currentThread().isInterrupted()) break;
            String pkg = pkgs.get(i);
            if (cb != null) cb.onProgress(i + 1, total, pkg);
            AppEntry e = new AppEntry(pkg);
            e.popular = isPopular(pkg);
            fillAppInfo(pm, e);
            long[] acc = new long[]{0};
            for (File d : dirsOf(pkg)) countFiles(d, e, acc, 0);
            out.add(e);
        }
        out.sort((a, b) -> Long.compare(b.totalSize, a.totalSize));

        // 顺手算一下每个应用的「可清理」大小（只对占用前 40 名算，保证速度）
        for (int i = 0; i < out.size() && i < 40; i++) {
            AppEntry e = out.get(i);
            List<ScanEngine.Item> items = cacheItems(e.pkg);
            e.cacheCount = items.size();
            long s = 0;
            for (ScanEngine.Item it : items) s += it.size;
            e.cacheSize = s;
        }
        return out;
    }

    private static void countFiles(File dir, AppEntry e, long[] acc, int depth) {
        if (depth > MAX_WALK_DEPTH || acc[0] > 4_000_000) return;   // 单个应用最多数 400 万个文件
        File[] kids;
        try {
            kids = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (kids == null) return;
        for (File f : kids) {
            if (f.isDirectory()) {
                countFiles(f, e, acc, depth + 1);
            } else {
                e.files++;
                e.totalSize += f.length();
                acc[0]++;
            }
        }
    }

    /** 已安装且有桌面图标的应用（「增加应用」用） */
    public static List<AppEntry> installedApps(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<AppEntry> out = new ArrayList<>();
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                if (pm.getLaunchIntentForPackage(ai.packageName) == null) continue;
                AppEntry e = new AppEntry(ai.packageName);
                e.label = String.valueOf(ai.loadLabel(pm));
                e.installed = true;
                e.popular = isPopular(ai.packageName);
                out.add(e);
            }
        } catch (Throwable ignored) { }
        out.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    /** 某个应用的可清理项（缓存目录 + 临时文件），按大小降序 */
    public static List<ScanEngine.Item> cacheItems(String pkg) {
        List<ScanEngine.Item> out = new ArrayList<>();
        for (File root : dirsOf(pkg)) collectCache(root, out, 0);
        out.sort((a, b) -> Long.compare(b.size, a.size));
        return out;
    }

    private static void collectCache(File dir, List<ScanEngine.Item> out, int depth) {
        if (out.size() >= MAX_ITEMS || depth > MAX_DEPTH) return;
        File[] kids;
        try {
            kids = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (kids == null) return;
        for (File f : kids) {
            if (Thread.currentThread().isInterrupted()) return;
            String ln = f.getName().toLowerCase(Locale.ROOT);
            if (f.isDirectory()) {
                if (CACHE_DIR_NAMES.contains(ln)) {
                    ScanEngine.Item it = new ScanEngine.Item(f.getAbsolutePath(), dirSize(f, 0),
                            f.lastModified(), true, ScanEngine.CAT_OTHER);
                    it.recursive = true;    // 缓存目录允许整体删除
                    out.add(it);
                } else {
                    collectCache(f, out, depth + 1);
                }
            } else if (CACHE_FILE_EXT.contains(extOf(ln))) {
                out.add(new ScanEngine.Item(f.getAbsolutePath(), f.length(), f.lastModified(),
                        false, ScanEngine.CAT_OTHER));
            }
        }
    }

    private static long dirSize(File dir, int depth) {
        if (depth > MAX_WALK_DEPTH) return 0;
        File[] kids;
        try {
            kids = dir.listFiles();
        } catch (Throwable t) {
            return 0;
        }
        if (kids == null) return 0;
        long s = 0;
        for (File f : kids) {
            s += f.isDirectory() ? dirSize(f, depth + 1) : f.length();
        }
        return s;
    }

    private static String extOf(String lowerName) {
        int i = lowerName.lastIndexOf('.');
        if (i < 0 || i == lowerName.length() - 1) return "";
        return lowerName.substring(i + 1);
    }

    private static boolean isPopular(String pkg) {
        for (String[] p : POPULAR) if (p[0].equals(pkg)) return true;
        return false;
    }

    public static String popularName(String pkg) {
        for (String[] p : POPULAR) if (p[0].equals(pkg)) return p[1];
        return "";
    }

    private static boolean isInstalled(PackageManager pm, String pkg) {
        try {
            pm.getApplicationInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 名称 + 图标（拿不到就用包名） */
    private static void fillAppInfo(PackageManager pm, AppEntry e) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(e.pkg, 0);
            e.label = String.valueOf(ai.loadLabel(pm));
            e.icon = ai.loadIcon(pm);
        } catch (Throwable t) {
            e.label = e.pkg;
        }
        if (e.label.isEmpty()) e.label = e.pkg;
    }
}
