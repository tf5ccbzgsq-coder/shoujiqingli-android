package com.cleaner.app;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.util.List;

/**
 * 清理执行器：删除选中的文件/空目录。
 * 媒体文件走 MediaStore 删除，避免相册留下坏引用；失败再退回 File.delete。
 */
public final class Cleaner {

    private Cleaner() { }

    public interface Progress {
        void onProgress(int done, int total, long freed, String current);
        void onDone(int deleted, int failed, long freed);
    }

    public static void clean(final Context ctx, final List<ScanEngine.Item> items, final Progress cb) {
        new Thread(() -> {
            int deleted = 0, failed = 0;
            long freed = 0;
            int total = items.size();
            for (int i = 0; i < total; i++) {
                ScanEngine.Item it = items.get(i);
                boolean ok = delete(ctx, it);
                if (ok) {
                    deleted++;
                    freed += it.size;
                } else {
                    failed++;
                }
                if (cb != null) cb.onProgress(i + 1, total, freed, it.path);
            }
            if (cb != null) cb.onDone(deleted, failed, freed);
        }, "cleaner-clean").start();
    }

    private static boolean delete(Context ctx, ScanEngine.Item it) {
        File f = new File(it.path);
        try {
            if (!f.exists()) return true;
            if (it.dir) {
                String[] kids = f.list();
                if (kids != null && kids.length > 0) {
                    // 只有明确标记为「缓存目录」的才允许连内容一起删（应用专清）
                    if (!it.recursive) return false;
                    deleteTree(f);
                    return !f.exists();
                }
                return f.delete();
            }
            // 媒体文件：交给 MediaStore 删，避免相册残留坏条目
            if (isMedia(it.path) && deleteViaMediaStore(ctx, f)) return true;
            boolean ok = f.delete();
            if (ok) notifyDeleted(ctx, f);
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 递归删除整个目录（只用于被标记为缓存的目录） */
    private static void deleteTree(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) {
            for (File f : kids) {
                if (f.isDirectory()) deleteTree(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    private static boolean isMedia(String path) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(path);
        if (ext == null) return false;
        ext = ext.toLowerCase(java.util.Locale.ROOT);
        return ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png") || ext.equals("gif")
                || ext.equals("webp") || ext.equals("bmp") || ext.equals("heic") || ext.equals("heif")
                || ext.equals("mp4") || ext.equals("mkv") || ext.equals("avi") || ext.equals("mov")
                || ext.equals("3gp") || ext.equals("webm") || ext.equals("mp3") || ext.equals("wav")
                || ext.equals("flac") || ext.equals("aac") || ext.equals("ogg") || ext.equals("m4a");
    }

    private static boolean deleteViaMediaStore(Context ctx, File f) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            Uri uri = MediaStore.Files.getContentUri("external");
            int n = cr.delete(uri, MediaStore.MediaColumns.DATA + "=?",
                    new String[]{f.getAbsolutePath()});
            return n > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 通知媒体库刷新（删除后清掉残留索引） */
    private static void notifyDeleted(Context ctx, File f) {
        try {
            android.media.MediaScannerConnection.scanFile(ctx,
                    new String[]{f.getAbsolutePath()}, null, null);
        } catch (Throwable ignored) { }
    }
}
