package com.cleaner.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;

/** 轻量配置存储 + 存储空间查询 */
public final class Store {

    private Store() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("cleaner", Context.MODE_PRIVATE);
    }

    // ===== 深浅模式：auto / light / dark =====
    public static String getMode(Context c) { return prefs(c).getString("mode", "auto"); }
    public static void setMode(Context c, String v) { prefs(c).edit().putString("mode", v).apply(); }

    public static boolean isDarkNow(Context c) {
        String m = getMode(c);
        if ("dark".equals(m)) return true;
        if ("light".equals(m)) return false;
        int ui = c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return ui == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    // ===== 动态取色（Material You，Android 12+）=====
    public static boolean isDynamicColor(Context c) { return prefs(c).getBoolean("dyn_color", true); }
    public static void setDynamicColor(Context c, boolean v) {
        prefs(c).edit().putBoolean("dyn_color", v).apply();
    }

    // ===== 扫描范围：是否包含全盘（需要「所有文件访问」权限）=====
    public static boolean isScanAll(Context c) { return prefs(c).getBoolean("scan_all", true); }
    public static void setScanAll(Context c, boolean v) { prefs(c).edit().putBoolean("scan_all", v).apply(); }

    // ===== 大文件阈值（MB）=====
    public static int getBigFileMb(Context c) { return prefs(c).getInt("big_mb", 50); }
    public static void setBigFileMb(Context c, int v) { prefs(c).edit().putInt("big_mb", v).apply(); }

    // ===== 上次扫描时间 =====
    public static long getLastScan(Context c) { return prefs(c).getLong("last_scan", 0); }
    public static void setLastScan(Context c, long v) { prefs(c).edit().putLong("last_scan", v).apply(); }

    // ===== 是否已经提示过全盘权限 =====
    public static boolean isPermPrompted(Context c) { return prefs(c).getBoolean("perm_prompted", false); }
    public static void setPermPrompted(Context c, boolean v) {
        prefs(c).edit().putBoolean("perm_prompted", v).apply();
    }

    // ===== 「应用专清」里用户自己添加的应用包名 =====
    public static java.util.Set<String> getCustomApps(Context c) {
        java.util.Set<String> s = prefs(c).getStringSet("custom_apps", null);
        return s == null ? new java.util.HashSet<String>() : new java.util.HashSet<>(s);
    }

    public static void setCustomApps(Context c, java.util.Set<String> v) {
        prefs(c).edit().putStringSet("custom_apps", new java.util.HashSet<>(v)).apply();
    }

    // ===== 存储空间 =====
    public static class Space {
        public long total, free;
        public long used() { return Math.max(0, total - free); }
        public int usedPercent() {
            return total <= 0 ? 0 : (int) Math.round(used() * 100.0 / total);
        }
    }

    public static Space space() {
        Space s = new Space();
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            s.total = fs.getTotalBytes();
            s.free = fs.getAvailableBytes();
        } catch (Throwable ignored) { }
        return s;
    }

    /** 是否有「所有文件访问」权限（全盘扫描/清理用） */
    public static boolean hasAllFiles(Activity a) {
        if (Build.VERSION.SDK_INT < 30) return true;
        return Environment.isExternalStorageManager();
    }
}
