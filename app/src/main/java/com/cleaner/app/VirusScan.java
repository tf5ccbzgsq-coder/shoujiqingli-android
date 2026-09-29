package com.cleaner.app;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 病毒扫描：找出设备上所有「可执行 / 安装包」类文件，算 SHA-256，与本地病毒库比对。
 *
 * 只读不写；是否删除交给用户确认（复用 Cleaner）。
 * 需要「所有文件访问」权限才能遍历全盘，否则只给出提示。
 */
public final class VirusScan {

    /** 会拿来比对哈希的文件类型 */
    private static final Set<String> CAND_EXT = new HashSet<>(java.util.Arrays.asList(
            "apk", "apks", "xapk", "apkm", "aab", "exe", "dll", "msi", "scr", "bat", "cmd",
            "jar", "dex", "so", "bin", "elf", "deb", "rpm", "sh", "vbs", "ps1", "hta", "lnk"));

    private static final int MAX_CANDIDATES = 3000;

    public static class Hit {
        public final String path;
        public final long size;
        public final String sha256;
        public final VirusDb.Entry entry;
        public final ScanEngine.Item item;

        Hit(String path, long size, String sha256, VirusDb.Entry entry, ScanEngine.Item item) {
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
            this.entry = entry;
            this.item = item;
        }
    }

    public static class Out {
        public final List<Hit> hits = new ArrayList<>();
        public final List<ScanEngine.Item> clean = new ArrayList<>();
        public int scanned;
        public long bytes;
        public int candidates;
        public boolean needPermission;
        public boolean dbEmpty;
        public long costMs;
    }

    public interface Progress {
        void onProgress(int done, int total, String current, int hits);
        void onDone(Out out);
    }

    public static volatile boolean running;
    public static volatile Out last;

    public static void start(final Context ctx, final Progress cb) {
        if (running) return;
        running = true;
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            Out out = new Out();
            try {
                VirusDb.ensureLoaded(ctx);
                out.dbEmpty = !VirusDb.ready(ctx);

                boolean canAll = Environment.isExternalStorageManager();
                if (!canAll) {
                    out.needPermission = true;
                    if (cb != null) cb.onDone(out);
                    running = false;
                    return;
                }

                List<File> cands = new ArrayList<>();
                File root = Environment.getExternalStorageDirectory();
                if (root != null && root.exists()) collect(root, cands);
                out.candidates = cands.size();

                int total = cands.size();
                byte[] buf = new byte[128 * 1024];
                for (int i = 0; i < total; i++) {
                    if (Thread.currentThread().isInterrupted()) break;
                    File f = cands.get(i);
                    String sha = sha256(f, buf);
                    out.scanned++;
                    if (sha != null) {
                        out.bytes += f.length();
                        VirusDb.Entry e = VirusDb.lookup(ctx, sha);
                        ScanEngine.Item item = new ScanEngine.Item(f.getAbsolutePath(), f.length(),
                                f.lastModified(), false, ScanEngine.CAT_OTHER);
                        if (e != null) {
                            out.hits.add(new Hit(f.getAbsolutePath(), f.length(), sha, e, item));
                        } else {
                            out.clean.add(item);
                        }
                    }
                    if (cb != null) {
                        cb.onProgress(i + 1, total, f.getName(), out.hits.size());
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                out.costMs = System.currentTimeMillis() - t0;
                last = out;
                running = false;
                if (cb != null) cb.onDone(out);
            }
        }, "virus-scan").start();
    }

    /** 只统计候选文件（不读内容），用于预估 */
    public static int countCandidates() {
        if (!Environment.isExternalStorageManager()) return -1;
        List<File> cands = new ArrayList<>();
        File root = Environment.getExternalStorageDirectory();
        if (root != null && root.exists()) collect(root, cands);
        return cands.size();
    }

    private static void collect(File dir, List<File> out) {
        if (out.size() >= MAX_CANDIDATES) return;
        File[] kids;
        try {
            kids = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (kids == null) return;
        for (File f : kids) {
            if (out.size() >= MAX_CANDIDATES) return;
            if (f.isDirectory()) {
                String n = f.getName();
                if (n.equals("data") || n.equals("obb")) {
                    File p = f.getParentFile();
                    if (p != null && "Android".equals(p.getName())) continue;
                }
                collect(f, out);
            } else {
                String name = f.getName().toLowerCase(Locale.ROOT);
                int i = name.lastIndexOf('.');
                if (i < 0) continue;
                String ext = name.substring(i + 1);
                if (!CAND_EXT.contains(ext)) continue;
                if (f.length() <= 0) continue;
                out.add(f);
            }
        }
    }

    /** 流式计算 SHA-256 */
    public static String sha256(File f, byte[] buf) {
        InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(f);
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            byte[] out = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try {
                in.close();
            } catch (Throwable ignored) { }
        }
    }

    public static String sha256(File f) {
        return sha256(f, new byte[128 * 1024]);
    }

    /** 对任意输入流算哈希（用于「选文件查毒」） */
    public static String sha256(InputStream in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            byte[] out = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
