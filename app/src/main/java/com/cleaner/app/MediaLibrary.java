package com.cleaner.app;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 媒体库读取（音乐 / 视频 / 图片）。
 *
 * 两条通道，取到就用：
 *  ① MediaStore 查询（需要媒体权限），返回 content:// 地址，播放/解码最稳
 *  ② 文件系统遍历（需要「所有文件访问」），返回文件路径
 *
 * 这样无论用户给的是「照片和视频权限」还是「所有文件访问」，都能读到内容。
 */
public final class MediaLibrary {

    /** 一个可播放/可查看的媒体项 */
    public static class MItem {
        public final String uri;        // content://（可能为空）
        public final String path;       // 文件路径（可能为空）
        public final String name;
        public final long size;
        public final long modified;
        public final long durationMs;
        public final int cat;
        /** 列表里是否勾选（供文件列表页复用） */
        public boolean selected = false;

        public MItem(String uri, String path, String name, long size, long modified, long durationMs, int cat) {
            this.uri = uri;
            this.path = path;
            this.name = name;
            this.size = size;
            this.modified = modified;
            this.durationMs = durationMs;
            this.cat = cat;
        }

        public PlayerActivity.PlayItem toPlayItem() {
            return new PlayerActivity.PlayItem(uri, path, name);
        }
    }

    public interface Cb {
        void onDone(List<MItem> list, String source, String err);
    }

    private static final Set<String> IMG_EXT = new HashSet<>(java.util.Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "dng", "raw"));
    private static final Set<String> VID_EXT = new HashSet<>(java.util.Arrays.asList(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm", "ts", "m4v", "mpg", "mpeg", "rmvb"));
    private static final Set<String> AUD_EXT = new HashSet<>(java.util.Arrays.asList(
            "mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus", "amr", "ape", "dsf", "mid"));

    private static final int MAX_FS_ITEMS = 8000;

    private MediaLibrary() { }

    // ===== 权限 =====
    /** 该类别需要的运行时权限 */
    public static String[] permissionsFor(int cat) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (cat == ScanEngine.CAT_IMAGE) return new String[]{Manifest.permission.READ_MEDIA_IMAGES};
            if (cat == ScanEngine.CAT_VIDEO) return new String[]{Manifest.permission.READ_MEDIA_VIDEO};
            return new String[]{Manifest.permission.READ_MEDIA_AUDIO};
        }
        return new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    public static boolean hasMediaPermission(Context c, int cat) {
        for (String p : permissionsFor(cat)) {
            if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    public static boolean hasAllFiles(Context c) {
        return Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager();
    }

    /** 能不能读到这类媒体（任一条通道可用即可） */
    public static boolean canRead(Context c, int cat) {
        return hasMediaPermission(c, cat) || hasAllFiles(c);
    }

    // ===== 读取 =====
    public static void load(final Context ctx, final int cat, final Cb cb) {
        new Thread(() -> {
            List<MItem> list = null;
            String source = null;
            String err = null;
            if (hasMediaPermission(ctx, cat)) {
                try {
                    list = fromMediaStore(ctx, cat);
                    source = "系统媒体库";
                } catch (Throwable t) {
                    err = "读取系统媒体库失败：" + msg(t);
                }
            }
            if ((list == null || list.isEmpty()) && hasAllFiles(ctx)) {
                List<MItem> fs = fromFileSystem(ctx, cat);
                if (fs.isEmpty()) {
                    if (list == null) {
                        list = fs;
                        source = "文件扫描";
                    }
                } else {
                    list = fs;
                    source = "文件扫描";
                }
            }
            if (list == null) list = new ArrayList<>();
            if (list.size() > 1) sortByDate(list);
            if (cb != null) cb.onDone(list, source == null ? "" : source, err);
        }, "media-load").start();
    }

    private static void sortByDate(List<MItem> list) {
        Collections.sort(list, new Comparator<MItem>() {
            @Override
            public int compare(MItem a, MItem b) {
                return Long.compare(b.modified, a.modified);
            }
        });
    }

    private static List<MItem> fromMediaStore(Context c, int cat) {
        List<MItem> out = new ArrayList<>();
        ContentResolver cr = c.getContentResolver();
        Uri base;
        String[] cols;
        if (cat == ScanEngine.CAT_IMAGE) {
            base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            cols = new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.DATA};
        } else if (cat == ScanEngine.CAT_VIDEO) {
            base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            cols = new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.DATA, MediaStore.Video.Media.DURATION};
        } else {
            base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            cols = new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.DATA, MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.IS_MUSIC};
        }
        try (Cursor cur = cr.query(base, cols, null, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (cur == null) return out;
            int iId = cur.getColumnIndex(MediaStore.MediaColumns._ID);
            int iName = cur.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            int iSize = cur.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int iDate = cur.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED);
            int iData = cur.getColumnIndex(MediaStore.MediaColumns.DATA);
            int iDur = cat == ScanEngine.CAT_IMAGE ? -1 : cur.getColumnIndex("duration");
            int iMusic = cat == ScanEngine.CAT_AUDIO ? cur.getColumnIndex("is_music") : -1;
            while (cur.moveToNext()) {
                if (iMusic >= 0 && cur.getInt(iMusic) == 0) continue;   // 排除通话录音/提示音
                long id = cur.getLong(iId);
                if (id <= 0) continue;
                String name = iName >= 0 && !cur.isNull(iName) ? cur.getString(iName) : "";
                String path = iData >= 0 && !cur.isNull(iData) ? cur.getString(iData) : "";
                if (name.isEmpty() && !path.isEmpty()) {
                    int s = path.lastIndexOf('/');
                    name = s < 0 ? path : path.substring(s + 1);
                }
                long size = iSize >= 0 && !cur.isNull(iSize) ? cur.getLong(iSize) : 0;
                long date = iDate >= 0 && !cur.isNull(iDate) ? cur.getLong(iDate) * 1000L : 0;
                long dur = iDur >= 0 && !cur.isNull(iDur) ? cur.getLong(iDur) : 0;
                String uri = ContentUris.withAppendedId(base, id).toString();
                out.add(new MItem(uri, path, name, size, date, dur, cat));
            }
        }
        return out;
    }

    private static List<MItem> fromFileSystem(Context c, int cat) {
        List<MItem> out = new ArrayList<>();
        File root = Environment.getExternalStorageDirectory();
        if (root == null || !root.exists()) return out;
        walk(root, cat, out);
        return out;
    }

    private static void walk(File dir, int cat, List<MItem> out) {
        if (out.size() >= MAX_FS_ITEMS) return;
        File[] kids;
        try {
            kids = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (kids == null) return;
        for (File f : kids) {
            if (out.size() >= MAX_FS_ITEMS) return;
            if (f.isDirectory()) {
                String n = f.getName();
                if (n.equals("data") || n.equals("obb")) {
                    File p = f.getParentFile();
                    if (p != null && "Android".equals(p.getName())) continue;
                }
                if (n.startsWith(".")) continue;
                walk(f, cat, out);
            } else {
                String name = f.getName();
                int i = name.lastIndexOf('.');
                if (i < 0) continue;
                String ext = name.substring(i + 1).toLowerCase(Locale.ROOT);
                boolean hit = cat == ScanEngine.CAT_IMAGE ? IMG_EXT.contains(ext)
                        : cat == ScanEngine.CAT_VIDEO ? VID_EXT.contains(ext) : AUD_EXT.contains(ext);
                if (!hit || f.length() <= 0) continue;
                out.add(new MItem(null, f.getAbsolutePath(), name, f.length(), f.lastModified(), 0, cat));
            }
        }
    }

    /** 快速统计各类数量（不构建完整列表），用于媒体页显示 */
    public static int[] quickCount(Context c) {
        int[] n = new int[3];
        for (int k = 0; k < 3; k++) {
            int cat = k == 0 ? ScanEngine.CAT_AUDIO : (k == 1 ? ScanEngine.CAT_VIDEO : ScanEngine.CAT_IMAGE);
            try {
                if (hasMediaPermission(c, cat)) {
                    Uri base = cat == ScanEngine.CAT_IMAGE ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            : cat == ScanEngine.CAT_VIDEO ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                    try (Cursor cur = c.getContentResolver().query(base,
                            new String[]{MediaStore.MediaColumns._ID}, null, null, null)) {
                        n[k] = cur == null ? 0 : cur.getCount();
                    }
                } else if (hasAllFiles(c)) {
                    List<MItem> l = fromFileSystem(c, cat);
                    n[k] = l.size();
                }
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    private static String msg(Throwable t) {
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m;
    }
}
