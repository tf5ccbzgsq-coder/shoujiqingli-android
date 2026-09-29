package com.cleaner.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 大小 / 时间格式化 */
public final class FormatUtil {

    private FormatUtil() { }

    public static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.CHINA, "%.0f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.CHINA, "%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format(Locale.CHINA, "%.2f GB", gb);
    }

    public static String time(long ts) {
        if (ts <= 0) return "—";
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(ts));
    }

    /** 长路径中间省略，保留头尾，便于识别 */
    public static String shortPath(String path, int max) {
        if (path == null) return "";
        if (path.length() <= max) return path;
        int head = max * 2 / 3;
        int tail = max - head - 1;
        return path.substring(0, head) + "…" + path.substring(path.length() - tail);
    }
}
