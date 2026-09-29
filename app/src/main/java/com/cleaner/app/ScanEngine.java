package com.cleaner.app;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 扫描引擎：遍历存储、归类、挑出垃圾/大文件/安装包/重复文件。
 *
 * 设计原则（安全第一）：
 * - 只在扫描根目录内操作；不碰 Android/data、Android/obb（系统也不允许）
 * - 「垃圾」只认明确的临时目录名（cache/temp/log/thumbnails/…）与临时扩展名
 * - 重复文件只保留每组第一个，其余才算可清理
 * - 不扫描也不删除任何非媒体用户文件，除安装包（可清理项，默认不勾选）
 */
public final class ScanEngine {

    public static final int CAT_IMAGE = 0, CAT_VIDEO = 1, CAT_AUDIO = 2, CAT_DOC = 3,
            CAT_APK = 4, CAT_ZIP = 5, CAT_OTHER = 6;
    public static final String[] CAT_NAMES = {"图片", "视频", "音频", "文档", "安装包", "压缩包", "其他"};

    /** 文件来源：按存放路径判断是哪个 App / 场景产生的 */
    public static final int SRC_WECHAT = 0, SRC_QQ = 1, SRC_DOWNLOAD = 2, SRC_CAMERA = 3,
            SRC_SCREENSHOT = 4, SRC_BLUETOOTH = 5, SRC_RECORD = 6, SRC_OTHER = 7;
    public static final String[] SRC_NAMES = {"微信", "QQ", "下载", "相机", "截图", "蓝牙", "录音", "其他"};

    private static final Set<String> JUNK_DIRS = new HashSet<>(Arrays.asList(
            "cache", ".cache", "temp", "tmp", ".tmp", "logs", "log", "crash", "crashlog",
            "thumbnails", ".thumbnails", ".trash", "trash", "recycle", ".recycle",
            "lost.dir", ".nomedia.d", "acache", ".acache", "webviewcache", "webview_cache"));

    private static final Set<String> JUNK_EXT = new HashSet<>(Arrays.asList(
            "tmp", "temp", "log", "log1", "log2", "bak", "old", "crdownload", "part", "partial",
            "download", "dmp", "trace", "chk", "thumb", "thumbnail"));

    private static final Set<String> MEDIA_EXT = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "dng", "raw",
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm", "ts", "m4v",
            "mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus", "amr"));

    private static final Set<String> DOC_EXT = new HashSet<>(Arrays.asList(
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "pdf", "txt", "md", "csv", "rtf"));

    private static final Set<String> ZIP_EXT = new HashSet<>(Arrays.asList(
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso"));

    private static final long MIN_DUP_SIZE = 64 * 1024;      // 小于 64KB 不参与查重
    private static final int MAX_JUNK_ITEMS = 3000;
    private static final int MAX_DUP_GROUPS = 200;
    private static final long RECENT_WINDOW = 7L * 24 * 3600 * 1000;   // 「新文件」= 最近 7 天
    private static final int MAX_RECENT = 5000;
    private static final int MAX_KEEP_ITEMS = 40000;         // 保留清单总上限，防止内存爆
    private static final int MAX_KEEP_PER_CAT = 6000;        // 单类上限
    private static final int HASH_BYTES = 16 * 1024;

    public static class Item {
        public final String path;
        public final long size;
        public final long modified;
        public final boolean dir;
        public final int cat;
        public final int src;
        public boolean selected = true;
        /** 目录是否允许整体删除（应用专清的缓存目录用；普通空目录不需要） */
        public boolean recursive = false;

        Item(String path, long size, long modified, boolean dir, int cat) {
            this.path = path;
            this.size = size;
            this.modified = modified;
            this.dir = dir;
            this.cat = cat;
            this.src = sourceOf(path);
        }

        public String name() {
            int i = path.lastIndexOf('/');
            return i < 0 ? path : path.substring(i + 1);
        }

        public String parent() {
            int i = path.lastIndexOf('/');
            return i <= 0 ? "" : path.substring(0, i);
        }
    }

    public static class Result {
        public final List<Item> junk = new ArrayList<>();
        public final List<Item> big = new ArrayList<>();
        public final List<Item> apk = new ArrayList<>();
        public final List<List<Item>> dups = new ArrayList<>();
        /** 空文件夹（首页「分类文件」单独一栏） */
        public final List<Item> emptyDirs = new ArrayList<>();
        /** 最近 7 天的新文件（首页「分类文件」单独一栏） */
        public final List<Item> recent = new ArrayList<>();
        public final long[] catSize = new long[CAT_NAMES.length];
        public final int[] catCount = new int[CAT_NAMES.length];
        public final long[] srcSize = new long[SRC_NAMES.length];
        public final int[] srcCount = new int[SRC_NAMES.length];
        /** 扫描时保留的文件清单（有上限），供分类/来源列表与播放器使用 */
        public final List<Item> kept = new ArrayList<>();
        /** 每类各自的名额，避免相册把音乐/视频挤掉 */
        public final int[] catKept = new int[CAT_NAMES.length];
        public boolean keepTruncated;
        public final LinkedHashMap<String, Long> topDirs = new LinkedHashMap<>();
        public long scannedFiles, scannedBytes;
        public boolean fullScan;

        /** 某类型（或来源）下的文件，按大小从大到小 */
        public List<Item> byCategory(int cat) {
            List<Item> out = new ArrayList<>();
            for (Item i : kept) if (!i.dir && i.cat == cat) out.add(i);
            out.sort(Comparator.comparingLong((Item i) -> i.size).reversed());
            return out;
        }

        public List<Item> bySource(int src) {
            List<Item> out = new ArrayList<>();
            for (Item i : kept) if (!i.dir && i.src == src) out.add(i);
            out.sort(Comparator.comparingLong((Item i) -> i.size).reversed());
            return out;
        }

        /** 媒体文件（音频/视频/图片），按修改时间从新到旧 —— 播放器列表用 */
        public List<Item> mediaByDate(int cat) {
            List<Item> out = new ArrayList<>();
            for (Item i : kept) if (!i.dir && i.cat == cat) out.add(i);
            out.sort(Comparator.comparingLong((Item i) -> i.modified).reversed());
            return out;
        }

        public long junkSize() {
            long s = 0;
            for (Item i : junk) s += i.size;
            return s;
        }

        public long dupWasted() {
            long s = 0;
            for (List<Item> g : dups) {
                for (Item i : g) s += i.size;
            }
            return s;
        }

        public int dupCount() {
            int n = 0;
            for (List<Item> g : dups) n += g.size();
            return n;
        }
    }

    public interface Progress {
        void onProgress(String currentPath, long files, long bytes, int phase);
        void onDone(Result r);
    }

    /** 最近一次扫描结果：界面重建后仍可用 */
    public static volatile Result last;
    public static volatile boolean running;

    public static void scan(final Context ctx, final Progress cb) {
        if (running) return;
        running = true;
        new Thread(() -> {
            Result r = scanNow(ctx, cb);
            last = r;
            running = false;
            if (cb != null) cb.onDone(r);
        }, "cleaner-scan").start();
    }

    private static Result scanNow(Context ctx, Progress cb) {
        Result r = new Result();
        final long threshold = Store.getBigFileMb(ctx) * 1024L * 1024L;
        r.fullScan = Store.isScanAll(ctx) && Environment.isExternalStorageManager();

        List<File> roots = new ArrayList<>();
        if (r.fullScan) {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null && ext.exists()) roots.add(ext);
        }
        // 自己 App 的缓存无论如何都能清
        addIfExists(roots, ctx.getCacheDir());
        addIfExists(roots, ctx.getExternalCacheDir());
        addIfExists(roots, ctx.getCodeCacheDir());

        Map<Long, List<Item>> bySize = new HashMap<>();
        List<File> emptyCandidates = new ArrayList<>();
        Set<String> seenRoots = new HashSet<>();

        for (File root : roots) {
            String rootPath = root.getAbsolutePath();
            if (!seenRoots.add(rootPath)) continue;
            walk(root, rootPath, r, bySize, emptyCandidates, threshold, cb);
        }

        // 空文件夹（只收真·空目录）
        for (File d : emptyCandidates) {
            if (r.junk.size() >= MAX_JUNK_ITEMS) break;
            String[] kids = d.list();
            if (kids != null && kids.length == 0) {
                Item empty = new Item(d.getAbsolutePath(), 0, d.lastModified(), true, CAT_OTHER);
                r.junk.add(empty);
                r.emptyDirs.add(empty);
            }
        }

        // 重复文件：先按大小分组，再对同大小文件算「头尾+长度」摘要
        if (cb != null) cb.onProgress("正在查重…", r.scannedFiles, r.scannedBytes, 2);
        for (Map.Entry<Long, List<Item>> e : bySize.entrySet()) {
            if (r.dups.size() >= MAX_DUP_GROUPS) break;
            List<Item> group = e.getValue();
            if (group.size() < 2) continue;
            Map<String, List<Item>> byHash = new HashMap<>();
            for (Item it : group) {
                String h = quickHash(it.path, it.size);
                if (h == null) continue;
                byHash.computeIfAbsent(h, k -> new ArrayList<>()).add(it);
            }
            for (List<Item> same : byHash.values()) {
                if (same.size() < 2) continue;
                // 每组保留第一个（最新修改的），其余标记为可清理
                same.sort(Comparator.comparingLong((Item i) -> i.modified).reversed());
                same.get(0).selected = false;
                r.dups.add(same);
            }
        }

        // 大文件 / 垃圾按大小排序，方便先看大头
        r.junk.sort(Comparator.comparingLong((Item i) -> i.size).reversed());
        r.big.sort(Comparator.comparingLong((Item i) -> i.size).reversed());
        sortTopDirs(r);
        return r;
    }

    private static void sortTopDirs(Result r) {
        List<Map.Entry<String, Long>> list = new ArrayList<>(r.topDirs.entrySet());
        list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        r.topDirs.clear();
        for (int i = 0; i < list.size() && i < 30; i++) {
            r.topDirs.put(list.get(i).getKey(), list.get(i).getValue());
        }
    }

    private static void addIfExists(List<File> roots, File f) {
        if (f != null && f.exists()) roots.add(f);
    }

    private static void walk(File dir, String rootPath, Result r, Map<Long, List<Item>> bySize,
                             List<File> emptyDirs, long threshold, Progress cb) {
        File[] kids;
        try {
            kids = dir.listFiles();
        } catch (Throwable t) {
            return;
        }
        if (kids == null) return;
        if (kids.length == 0) {
            // 根目录本身不算空目录
            if (!dir.getAbsolutePath().equals(rootPath)) emptyDirs.add(dir);
            return;
        }
        for (File f : kids) {
            if (Thread.currentThread().isInterrupted()) return;
            String name = f.getName();
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (f.isDirectory()) {
                // 系统限制目录，跳过
                if (lower.equals("data") || lower.equals("obb")) {
                    File parent = f.getParentFile();
                    if (parent != null && "Android".equals(parent.getName())) continue;
                }
                walk(f, rootPath, r, bySize, emptyDirs, threshold, cb);
            } else {
                long size = f.length();
                r.scannedFiles++;
                r.scannedBytes += size;
                if (cb != null && (r.scannedFiles & 0x3F) == 0) {
                    cb.onProgress(f.getAbsolutePath(), r.scannedFiles, r.scannedBytes, 1);
                }

                String ext = extOf(lower);
                int cat = classify(ext);
                r.catSize[cat] += size;
                r.catCount[cat]++;
                // 「新文件」（最近 7 天）单独收一栏
                if (System.currentTimeMillis() - f.lastModified() < RECENT_WINDOW
                        && r.recent.size() < MAX_RECENT) {
                    r.recent.add(new Item(f.getAbsolutePath(), size, f.lastModified(), false, cat));
                }
                int srcIdx = sourceOf(f.getAbsolutePath());
                r.srcSize[srcIdx] += size;
                r.srcCount[srcIdx]++;
                if (r.catKept[cat] < MAX_KEEP_PER_CAT && r.kept.size() < MAX_KEEP_ITEMS) {
                    r.kept.add(new Item(f.getAbsolutePath(), size, f.lastModified(), false, cat));
                    r.catKept[cat]++;
                } else {
                    r.keepTruncated = true;
                }

                File parentFile = f.getParentFile();
                String parent = parentFile == null ? "" : parentFile.getName().toLowerCase(java.util.Locale.ROOT);
                String parentPath = parentFile == null ? "" : parentFile.getAbsolutePath();

                // 目录排行（按二级目录归纳，避免过碎）
                String key = topKey(parentPath, rootPath);
                if (key != null) {
                    Long old = r.topDirs.get(key);
                    r.topDirs.put(key, (old == null ? 0 : old) + size);
                }

                boolean junk = JUNK_EXT.contains(ext) || JUNK_DIRS.contains(parent)
                        || parentPath.contains("/cache") || parentPath.contains("/.cache");
                if (junk) {
                    if (r.junk.size() < MAX_JUNK_ITEMS) {
                        r.junk.add(new Item(f.getAbsolutePath(), size, f.lastModified(), false, cat));
                    }
                } else if (size >= MIN_DUP_SIZE) {
                    bySize.computeIfAbsent(size, k -> new ArrayList<>()).add(
                            new Item(f.getAbsolutePath(), size, f.lastModified(), false, cat));
                }

                // 大文件与安装包属于用户数据，默认不勾选，必须用户自己确认
                if (size >= threshold) {
                    Item big = new Item(f.getAbsolutePath(), size, f.lastModified(), false, cat);
                    big.selected = false;
                    r.big.add(big);
                }
                if (CAT_APK == cat) {
                    Item apk = new Item(f.getAbsolutePath(), size, f.lastModified(), false, cat);
                    apk.selected = false;
                    r.apk.add(apk);
                }
            }
        }
    }

    /** 归纳到「根目录下的第一/第二级」，让排行有意义 */
    private static String topKey(String parentPath, String rootPath) {
        if (parentPath == null || parentPath.isEmpty()) return null;
        String rel = parentPath.startsWith(rootPath) ? parentPath.substring(rootPath.length()) : parentPath;
        String[] parts = rel.split("/");
        List<String> keep = new ArrayList<>();
        for (String p : parts) {
            if (p == null || p.isEmpty()) continue;
            keep.add(p);
            if (keep.size() >= 2) break;
        }
        if (keep.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String p : keep) sb.append("/").append(p);
        return sb.toString();
    }

    private static String extOf(String lowerName) {
        int i = lowerName.lastIndexOf('.');
        if (i < 0 || i == lowerName.length() - 1) return "";
        return lowerName.substring(i + 1);
    }

    private static int classify(String ext) {
        if (MEDIA_EXT.contains(ext)) {
            if (ext.startsWith("jpg") || ext.startsWith("jp") || ext.equals("png") || ext.equals("gif")
                    || ext.equals("webp") || ext.equals("bmp") || ext.equals("heic")
                    || ext.equals("heif") || ext.equals("dng") || ext.equals("raw")) return CAT_IMAGE;
            if (ext.equals("mp3") || ext.equals("wav") || ext.equals("flac") || ext.equals("aac")
                    || ext.equals("ogg") || ext.equals("m4a") || ext.equals("wma")
                    || ext.equals("opus") || ext.equals("amr")) return CAT_AUDIO;
            return CAT_VIDEO;
        }
        if (DOC_EXT.contains(ext)) return CAT_DOC;
        if ("apk".equals(ext) || "apks".equals(ext) || "xapk".equals(ext)) return CAT_APK;
        if (ZIP_EXT.contains(ext)) return CAT_ZIP;
        return CAT_OTHER;
    }

    /**
     * 按路径判断文件来源（微信/QQ/下载/相机/截图/蓝牙/录音/其他）。
     * 大小写不敏感，命中优先级：具体 App 目录 > 通用目录。
     */
    public static int sourceOf(String path) {
        if (path == null) return SRC_OTHER;
        String p = path.toLowerCase(java.util.Locale.ROOT).replace('\\', '/');
        if (p.contains("/micromsg/") || p.contains("/wechat/") || p.contains("/weixin/")
                || p.contains("/tencent/mm/") || p.contains("wechatimage") || p.contains("wxwork")) {
            return SRC_WECHAT;
        }
        if (p.contains("/mobileqq/") || p.contains("/qqfile_recv/") || p.contains("/qq_images/")
                || p.contains("/tencent/qq") || p.contains("/qqmusic/") || p.contains("/qzone/")) {
            return SRC_QQ;
        }
        if (p.contains("/download/") || p.contains("/downloads/") || p.contains("/browser/")
                || p.contains("/baidunetdisk/") || p.contains("/ucdownloads/")) {
            return SRC_DOWNLOAD;
        }
        if (p.contains("/bluetooth/")) return SRC_BLUETOOTH;
        if (p.contains("screenshot") || p.contains("/screenshots/") || p.contains("/截屏")) {
            return SRC_SCREENSHOT;
        }
        if (p.contains("/dcim/camera/") || p.contains("/dcim/100andro") || p.contains("/camera/")
                || p.contains("/movies/")) {
            return SRC_CAMERA;
        }
        if (p.contains("/recordings/") || p.contains("/recorder/") || p.contains("/voice/")
                || p.contains("/callrecord") || p.contains("/miui/sound_recorder")) {
            return SRC_RECORD;
        }
        return SRC_OTHER;
    }

    /** 音频 / 视频 / 图片（播放器与浏览器可用的类型） */
    public static boolean isPlayableMedia(Item it) {
        return !it.dir && (it.cat == CAT_AUDIO || it.cat == CAT_VIDEO);
    }

    /** 头 16KB + 尾 16KB + 长度 的摘要：够快，误判率极低 */
    private static String quickHash(String path, long size) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(path, "r")) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[HASH_BYTES];
            int n = raf.read(buf);
            if (n > 0) md.update(buf, 0, n);
            if (size > HASH_BYTES * 2L) {
                raf.seek(size - HASH_BYTES);
                n = raf.read(buf);
                if (n > 0) md.update(buf, 0, n);
            }
            md.update(Long.toString(size).getBytes());
            byte[] out = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
