package com.cleaner.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 通用文件列表页。
 *
 * 两种数据来源：
 *  - media（音乐 / 视频 / 图片）：走 MediaLibrary —— 优先系统媒体库（content://），
 *    没有媒体权限时用「所有文件访问」扫文件，两条路都能读出来，不再依赖「先全盘扫描」
 *  - cat / src（按类型 / 按来源）：读上一次全盘扫描的结果
 *
 * 删除按钮固定在上方；点音频 / 视频 / 图片直接进内置播放器。
 */
public class FileListActivity extends AppCompatActivity {

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_MODE = "mode";      // media / cat / src
    public static final String EXTRA_INDEX = "index";

    private static final int REQ_PERM = 0x71;

    private ThemeHelper.Palette p;
    private LinearLayout root, listBox, delBtn;
    private TextView summary, selectAllBtn, statusTv;
    private ScrollView listScroll;

    private String mode = "cat";
    private int catIdx = 0;
    private String title = "文件";

    private List<ScanEngine.Item> items = new ArrayList<>();
    private List<MediaLibrary.MItem> media = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        p = ThemeHelper.get(this);
        Intent it = getIntent();
        mode = it == null || it.getStringExtra(EXTRA_MODE) == null ? "cat" : it.getStringExtra(EXTRA_MODE);
        catIdx = it == null ? 0 : it.getIntExtra(EXTRA_INDEX, 0);
        title = it == null ? "文件" : it.getStringExtra(EXTRA_TITLE);
        if (title == null || title.isEmpty()) title = "文件";

        buildUI();
        UiCompat.applyEdgeToEdge(this, root);
        UiCompat.applySystemBarIcons(this, Store.isDarkNow(this));

        if ("media".equals(mode)) {
            checkPermissionAndLoad();
        } else {
            List<ScanEngine.Item> list = loadItems();
            if (list == null) {
                Toast.makeText(this, "结果已失效，请重新扫描", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            items = list;
            rebuildList();
        }
    }

    // ===== 数据 =====
    private List<ScanEngine.Item> loadItems() {
        // 应用专清：清单由 AppCacheScanner 备好（走静态引用，不塞 Intent）
        if ("appjunk".equals(mode)) {
            List<ScanEngine.Item> pend = AppCacheScanner.pendingItems;
            return pend == null ? new ArrayList<ScanEngine.Item>() : pend;
        }
        ScanEngine.Result r = ScanEngine.last;
        if (r == null) return null;
        if ("src".equals(mode)) {
            if (catIdx < 0 || catIdx >= ScanEngine.SRC_NAMES.length) return null;
            return r.bySource(catIdx);
        }
        // 分类文件：大文件 / 安装包 / 冗余文件 / 空文件夹 / 新文件
        if ("big".equals(mode)) return r.big;
        if ("apk".equals(mode)) return r.apk;
        if ("junk".equals(mode)) return r.junk;
        if ("empty".equals(mode)) return r.emptyDirs;
        if ("recent".equals(mode)) return r.recent;
        if (catIdx < 0 || catIdx >= ScanEngine.CAT_NAMES.length) return null;
        return r.byCategory(catIdx);
    }

    private void checkPermissionAndLoad() {
        if (MediaLibrary.canRead(this, catIdx)) {
            loadMedia();
            return;
        }
        showPermissionCard();
    }

    private void loadMedia() {
        statusTv.setVisibility(View.VISIBLE);
        statusTv.setText("正在读取…");
        listScroll.setVisibility(View.GONE);
        MediaLibrary.load(this, catIdx, (listIn, source, err) -> {
            final List<MediaLibrary.MItem> list =
                    listIn == null ? new ArrayList<MediaLibrary.MItem>() : listIn;
            runOnUiThread(() -> {
                media = list;
                if (media.isEmpty()) {
                    statusTv.setVisibility(View.VISIBLE);
                    listScroll.setVisibility(View.GONE);
                    String reason = err != null ? err
                            : (MediaLibrary.canRead(this, catIdx) ? "没找到这类媒体文件" : "缺少读取权限");
                    statusTv.setText("读取不到内容：" + reason
                            + "\n\n可以点下面按钮授予权限后重试。");
                    listBox.removeAllViews();
                    delBtn.setVisibility(View.GONE);
                    addPermissionButtons();
                    summary.setText("0 个文件");
                    return;
                }
                statusTv.setVisibility(err == null ? View.GONE : View.VISIBLE);
                if (err != null) statusTv.setText(err + "（已改用文件扫描）");
                listScroll.setVisibility(View.VISIBLE);
                delBtn.setVisibility(View.VISIBLE);
                rebuildList();
            });
        });
    }

    private void showPermissionCard() {
        statusTv.setVisibility(View.VISIBLE);
        listScroll.setVisibility(View.GONE);
        delBtn.setVisibility(View.GONE);
        String what = catIdx == ScanEngine.CAT_IMAGE ? "照片" : (catIdx == ScanEngine.CAT_VIDEO ? "视频" : "音乐");
        statusTv.setText("读取" + what + "需要授权，二选一：\n"
                + "① 授予「照片和视频/音频」权限（推荐，直接读系统媒体库）\n"
                + "② 授予「所有文件访问」权限（顺带能扫全盘）");
        listBox.removeAllViews();
        summary.setText("");
        addPermissionButtons();
    }

    private void addPermissionButtons() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(14), 0, 0);

        TextView b1 = btn("授予媒体权限", p.accent, p.accentText);
        b1.setOnClickListener(v -> requestMediaPermission());
        box.addView(b1, new LinearLayout.LayoutParams(-1, -2));

        TextView b2 = btn("去开「所有文件访问」", p.chip, p.ink);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        b2.setOnClickListener(v -> openAllFilesSettings());
        box.addView(b2, lp);

        listBox.addView(box);
    }

    private void requestMediaPermission() {
        String[] perms = MediaLibrary.permissionsFor(catIdx);
        ActivityCompat.requestPermissions(this, perms, REQ_PERM);
    }

    private void openAppSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "打不开设置", Toast.LENGTH_SHORT).show();
        }
    }

    private void openAllFilesSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception e2) {
                Toast.makeText(this, "打不开设置", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERM) return;
        if (MediaLibrary.canRead(this, catIdx)) {
            checkPermissionAndLoad();
        } else {
            new AlertDialog.Builder(this)
                    .setTitle("还没拿到权限")
                    .setMessage("可以到「设置 → 应用 → 手机清理 → 权限」里手动允许读取照片/视频/音频；\n"
                            + "或者改用「所有文件访问」权限。")
                    .setPositiveButton("去设置", (d, w) -> openAppSettings())
                    .setNeutralButton("所有文件访问", (d, w) -> openAllFilesSettings())
                    .setNegativeButton("取消", null)
                    .show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置里授权后返回，自动重新读取
        if ("media".equals(mode) && media.isEmpty() && MediaLibrary.canRead(this, catIdx)) {
            checkPermissionAndLoad();
        }
    }

    // ===== 界面 =====
    private void buildUI() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.bg);
        setContentView(root);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(38), dp(8), dp(4));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(28);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setTextColor(p.ink);
        back.setPadding(dp(12), 0, dp(12), 0);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(p.ink);
        t.setGravity(Gravity.CENTER);
        top.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        selectAllBtn = new TextView(this);
        selectAllBtn.setText("全选");
        selectAllBtn.setTextSize(14);
        selectAllBtn.setTextColor(p.accent);
        selectAllBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        selectAllBtn.setOnClickListener(v -> toggleAll());
        top.addView(selectAllBtn);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // 顶部固定操作区：摘要 + 删除按钮
        LinearLayout actionBar = new LinearLayout(this);
        actionBar.setOrientation(LinearLayout.VERTICAL);
        actionBar.setPadding(dp(16), dp(2), dp(16), dp(8));
        summary = new TextView(this);
        summary.setTextSize(12);
        summary.setTextColor(p.muted);
        summary.setPadding(dp(4), 0, 0, dp(8));
        actionBar.addView(summary);
        delBtn = new LinearLayout(this);
        delBtn.setGravity(Gravity.CENTER);
        delBtn.setBackground(rounded(p.accent, dp(16)));
        delBtn.setPadding(0, dp(14), 0, dp(14));
        delBtn.setOnClickListener(v -> confirmDelete());
        TextView delText = new TextView(this);
        delText.setTextSize(15);
        delText.setTypeface(Typeface.DEFAULT_BOLD);
        delText.setTextColor(p.accentText);
        delText.setText("删除选中");
        delText.setId(android.R.id.text1);
        delBtn.addView(delText);
        actionBar.addView(delBtn, new LinearLayout.LayoutParams(-1, -2));
        root.addView(actionBar, new LinearLayout.LayoutParams(-1, -2));

        statusTv = new TextView(this);
        statusTv.setTextSize(12);
        statusTv.setTextColor(p.muted);
        statusTv.setPadding(dp(20), dp(6), dp(20), dp(6));
        statusTv.setVisibility(View.GONE);
        root.addView(statusTv, new LinearLayout.LayoutParams(-1, -2));

        listScroll = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(dp(18), 0, dp(18), dp(24));
        listScroll.addView(listBox);
        root.addView(listScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    private void toggleAll() {
        if ("media".equals(mode)) {
            boolean all = true;
            for (MediaLibrary.MItem i : media) if (!i.selected) all = false;
            for (MediaLibrary.MItem i : media) i.selected = !all;
            selectAllBtn.setText(!all ? "取消全选" : "全选");
        } else {
            boolean all = true;
            for (ScanEngine.Item i : items) if (!i.selected) all = false;
            for (ScanEngine.Item i : items) i.selected = !all;
            selectAllBtn.setText(!all ? "取消全选" : "全选");
        }
        rebuildList();
    }

    private void rebuildList() {
        listBox.removeAllViews();
        if ("media".equals(mode)) {
            buildMediaRows();
        } else {
            buildScanRows();
        }
        updateSummary();
    }

    private void buildScanRows() {
        if (items.isEmpty()) {
            statusTv.setVisibility(View.VISIBLE);
            statusTv.setText("这个分类下没有文件");
            return;
        }
        statusTv.setVisibility(View.GONE);
        for (final ScanEngine.Item it : items) {
            LinearLayout row = rowShell();
            CheckBox cb = check(it.selected);
            cb.setOnCheckedChangeListener((b, checked) -> {
                it.selected = checked;
                updateSummary();
            });
            row.addView(cb);
            row.addView(icon(glyph(it.cat)));
            LinearLayout col = col();
            col.addView(name(it.name()));
            col.addView(sub(FormatUtil.size(it.size) + " · " + FormatUtil.time(it.modified)
                    + " · " + ScanEngine.SRC_NAMES[it.src]
                    + " · " + FormatUtil.shortPath(it.parent(), 30)));
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            row.setOnClickListener(v -> openScanItem(it));
            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle(it.name())
                        .setItems(new String[]{"打开 / 播放", "删除这个文件"}, (d, w) -> {
                            if (w == 0) openScanItem(it);
                            else {
                                it.selected = true;
                                rebuildList();
                                confirmDelete();
                            }
                        })
                        .show();
                return true;
            });
            listBox.addView(row);
        }
    }

    private void buildMediaRows() {
        if (media.isEmpty()) {
            statusTv.setVisibility(View.VISIBLE);
            statusTv.setText("没有读到这类媒体文件");
            return;
        }
        statusTv.setVisibility(View.GONE);
        for (int i = 0; i < media.size(); i++) {
            final MediaLibrary.MItem it = media.get(i);
            final int pos = i;
            LinearLayout row = rowShell();
            CheckBox cb = check(it.selected);
            cb.setOnCheckedChangeListener((b, checked) -> {
                it.selected = checked;
                updateSummary();
            });
            row.addView(cb);
            row.addView(icon(it.cat == ScanEngine.CAT_IMAGE ? "🖼" : (it.cat == ScanEngine.CAT_VIDEO ? "🎬" : "♪")));
            LinearLayout col = col();
            col.addView(name(it.name));
            String line = FormatUtil.size(it.size);
            if (it.durationMs > 0) line = fmtDur(it.durationMs) + " · " + line;
            if (it.modified > 0) line += " · " + FormatUtil.time(it.modified);
            col.addView(sub(line));
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
            row.setOnClickListener(v -> playMedia(pos));
            listBox.addView(row);
        }
        if (media.size() >= 8000) {
            TextView more = new TextView(this);
            more.setText("（列表较长，只显示前 8000 项）");
            more.setTextSize(11);
            more.setTextColor(p.faint);
            more.setPadding(dp(4), dp(10), 0, 0);
            listBox.addView(more);
        }
    }

    private void playMedia(int pos) {
        List<PlayerActivity.PlayItem> q = new ArrayList<>();
        for (MediaLibrary.MItem m : media) q.add(m.toPlayItem());
        PlayerActivity.play(this, q, pos);
    }

    private void openScanItem(ScanEngine.Item it) {
        if (ScanEngine.isPlayableMedia(it) || it.cat == ScanEngine.CAT_IMAGE) {
            List<PlayerActivity.PlayItem> q = new ArrayList<>();
            int start = 0;
            for (ScanEngine.Item i : items) {
                if (i.cat == it.cat && !i.dir) {
                    if (i == it) start = q.size();
                    q.add(new PlayerActivity.PlayItem(null, i.path, i.name()));
                }
            }
            if (!q.isEmpty()) PlayerActivity.play(this, q, start);
            return;
        }
        try {
            File f = new File(it.path);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            String ext = MimeTypeMap.getFileExtensionFromUrl(it.path);
            String mime = ext == null ? "*/*"
                    : MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime == null ? "*/*" : mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "没有能打开这个文件的应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateSummary() {
        int n = 0;
        long size = 0;
        if ("media".equals(mode)) {
            for (MediaLibrary.MItem i : media) {
                if (i.selected) {
                    n++;
                    size += i.size;
                }
            }
            summary.setText("共 " + media.size() + " 个文件 · 勾选 " + n + " 个（" + FormatUtil.size(size) + "）");
        } else {
            for (ScanEngine.Item i : items) {
                if (i.selected) {
                    n++;
                    size += i.size;
                }
            }
            summary.setText("共 " + items.size() + " 个文件 · 勾选 " + n + " 个（" + FormatUtil.size(size) + "）");
        }
        TextView inner = delBtn.findViewById(android.R.id.text1);
        if (inner != null) {
            inner.setText(n == 0 ? "删除选中（未勾选）" : "删除选中的 " + n + " 个（" + FormatUtil.size(size) + "）");
        }
        delBtn.setAlpha(n == 0 ? 0.6f : 1f);
    }

    private void confirmDelete() {
        if ("media".equals(mode)) {
            final List<MediaLibrary.MItem> sel = new ArrayList<>();
            long size = 0;
            for (MediaLibrary.MItem i : media) {
                if (i.selected) {
                    sel.add(i);
                    size += i.size;
                }
            }
            if (sel.isEmpty()) {
                Toast.makeText(this, "先勾选要删除的文件", Toast.LENGTH_SHORT).show();
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("删除文件")
                    .setMessage("将删除 " + sel.size() + " 个文件，释放约 " + FormatUtil.size(size)
                            + "。\n删除后无法恢复，确认继续？")
                    .setPositiveButton("删除", (d, w) -> deleteMedia(sel))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        final List<ScanEngine.Item> sel = new ArrayList<>();
        long size = 0;
        for (ScanEngine.Item i : items) {
            if (i.selected) {
                sel.add(i);
                size += i.size;
            }
        }
        if (sel.isEmpty()) {
            Toast.makeText(this, "先勾选要删除的文件", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除文件")
                .setMessage("将删除 " + sel.size() + " 个文件，释放约 " + FormatUtil.size(size)
                        + "。\n删除后无法恢复，确认继续？")
                .setPositiveButton("删除", (d, w) -> MainActivity.cleanList(this, sel, () -> {
                    items.removeAll(sel);
                    rebuildList();
                }))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 媒体删除：有 content:// 就走系统删除（相册不会留坏条目），否则删文件 */
    private void deleteMedia(final List<MediaLibrary.MItem> sel) {
        new Thread(() -> {
            int ok = 0, fail = 0;
            long freed = 0;
            for (MediaLibrary.MItem m : sel) {
                boolean done = false;
                if (!m.uri.isEmpty()) {
                    try {
                        done = getContentResolver().delete(Uri.parse(m.uri), null, null) > 0;
                    } catch (Throwable ignored) { }
                }
                if (!done && !m.path.isEmpty()) {
                    try {
                        done = new File(m.path).delete();
                    } catch (Throwable ignored) { }
                }
                if (done) {
                    ok++;
                    freed += m.size;
                } else {
                    fail++;
                }
            }
            final int fok = ok, ffail = fail;
            final long ffreed = freed;
            runOnUiThread(() -> {
                Toast.makeText(this, "已删除 " + fok + " 个，释放 " + FormatUtil.size(ffreed)
                        + (ffail > 0 ? "（" + ffail + " 个失败）" : ""), Toast.LENGTH_LONG).show();
                media.removeAll(sel);
                rebuildList();
            });
        }, "media-delete").start();
    }

    // ===== 行元素 =====
    private LinearLayout rowShell() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(9), dp(4), dp(9));
        return row;
    }

    private CheckBox check(boolean selected) {
        CheckBox cb = new CheckBox(this);
        cb.setChecked(selected);
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(p.accent));
        return cb;
    }

    private TextView icon(String g) {
        TextView t = new TextView(this);
        t.setText(g);
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 0, dp(8), 0);
        return t;
    }

    private LinearLayout col() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private TextView name(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(p.ink);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        return t;
    }

    private TextView sub(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(10);
        t.setTextColor(p.faint);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        return t;
    }

    private TextView btn(String text, int bg, int fg) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(fg);
        t.setBackground(rounded(bg, dp(14)));
        t.setPadding(0, dp(13), 0, dp(13));
        return t;
    }

    private String glyph(int cat) {
        switch (cat) {
            case ScanEngine.CAT_IMAGE: return "🖼";
            case ScanEngine.CAT_VIDEO: return "🎬";
            case ScanEngine.CAT_AUDIO: return "♪";
            case ScanEngine.CAT_DOC: return "📄";
            case ScanEngine.CAT_APK: return "📦";
            case ScanEngine.CAT_ZIP: return "🗜";
            default: return "📁";
        }
    }

    private static String fmtDur(long ms) {
        long total = ms / 1000;
        long m = total / 60, s = total % 60;
        return m + ":" + String.format(Locale.ROOT, "%02d", s);
    }

    private android.graphics.drawable.GradientDrawable rounded(int color, int radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
