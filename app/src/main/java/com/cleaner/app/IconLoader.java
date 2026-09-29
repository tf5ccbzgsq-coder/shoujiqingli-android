package com.cleaner.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 极简图标加载器：内存缓存 + 磁盘缓存 + 采样解码。
 * 只为自己 App 内的推荐列表用（不引入第三方图片库，保持包体）。
 */
public final class IconLoader {

    private static final android.util.LruCache<String, Bitmap> MEM =
            new android.util.LruCache<>(32);
    private static final int TARGET_PX = 220;

    private IconLoader() { }

    public static void load(final Context ctx, final String url, final ImageView iv) {
        if (iv == null) return;
        iv.setTag(url == null ? "" : url);
        if (url == null || url.isEmpty()) {
            iv.setImageDrawable(null);
            return;
        }
        Bitmap cached = MEM.get(url);
        if (cached != null && !cached.isRecycled()) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageDrawable(null);
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            Bitmap bmp = fromDisk(app, url);
            if (bmp == null) {
                byte[] data = fetch(url);
                if (data != null) {
                    bmp = decode(data);
                    if (bmp != null) toDisk(app, url, data);
                }
            }
            if (bmp == null) return;
            final Bitmap fin = bmp;
            MEM.put(url, fin);
            iv.post(() -> {
                Object tag = iv.getTag();
                if (tag instanceof String && url.equals(tag)) iv.setImageBitmap(fin);
            });
        }, "icon-load").start();
    }

    private static byte[] fetch(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
            int rc = conn.getResponseCode();
            if (rc < 200 || rc >= 300) return null;
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                return bos.toByteArray();
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Bitmap decode(byte[] data) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= TARGET_PX && o.outHeight / (sample * 2) >= TARGET_PX) {
                sample *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Throwable t) {
            return null;
        }
    }

    private static File cacheFile(Context c, String url) {
        File dir = new File(c.getFilesDir(), "icon_cache");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, md5(url) + ".img");
    }

    private static Bitmap fromDisk(Context c, String url) {
        try {
            File f = cacheFile(c, url);
            if (!f.exists() || f.length() <= 0) return null;
            InputStream in = new java.io.FileInputStream(f);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                return decode(bos.toByteArray());
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static void toDisk(Context c, String url, byte[] data) {
        try (FileOutputStream fos = new FileOutputStream(cacheFile(c, url))) {
            fos.write(data);
        } catch (Throwable ignored) { }
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] out = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return String.valueOf(s.hashCode());
        }
    }
}
