package com.cleaner.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 主页：存储概览 → 一键扫描 → 结果入口；五个 tab：首页 / 分析 / 重复 / 媒体 / 设置 */
public class MainActivity extends AppCompatActivity {

    private ThemeHelper.Palette p;
    private LinearLayout root, contentArea, navBar;
    private TextView[] navButtons;
    private String currentTab = "home";

    // 首页
    private View homeTab;
    private ProgressBar scanBar;
    private TextView scanStatus, scanBtn, spaceUsed, spaceSub;
    private View usedFillBar;
    private LinearLayout resultBox;

    // 分析 / 重复 / 媒体
    private View analyzeTab, dupTab, mediaTab;
    private LinearLayout analyzeBox, dupBox, mediaBox;
    private TextView dupSummary, dupCleanBtn;

    private String lastSig;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        p = ThemeHelper.get(this);
        buildRoot();
        buildHomeTab();
        buildNavBar();
        UiCompat.applyEdgeToEdge(this, root);
        UiCompat.applySystemBarIcons(this, Store.isDarkNow(this));
        switchTab(savedInstanceState != null && savedInstanceState.getString("tab") != null
                ? savedInstanceState.getString("tab") : "home");
        lastSig = themeSig();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("tab", currentTab);
    }

    private String themeSig() {
        return Store.getMode(this) + "|" + Store.isDynamicColor(this) + "|" + Store.isDarkNow(this)
                + "|" + Store.isScanAll(this) + "|" + Store.getBigFileMb(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String sig = themeSig();
        if (lastSig != null && !sig.equals(lastSig)) {
            lastSig = sig;
            recreate();
            return;
        }
        lastSig = sig;
        UiCompat.applySystemBarIcons(this, Store.isDarkNow(this));
        refreshHome();
    }

    // ===== 骨架 =====
    private void buildRoot() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.bg);
        setContentView(root, new FrameLayout.LayoutParams(-1, -1));

        contentArea = new LinearLayout(this);
        contentArea.setOrientation(LinearLayout.VERTICAL);
        root.addView(contentArea, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    private void buildNavBar() {
        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setBackgroundColor(p.card);
        navBar.setElevation(8);
        navBar.setPadding(0, dp(6), 0, dp(6));

        String[] labels = {"首页", "分析", "重复", "媒体", "我的"};
        String[] tabs = {"home", "analyze", "dup", "media", "settings"};
        String[] icons = {"⌂", "▤", "⧉", "♪", "☺"};
        navButtons = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final String tab = tabs[i];
            TextView btn = new TextView(this);
            btn.setText(icons[i] + "\n" + labels[i]);
            btn.setTextSize(11);
            btn.setLineSpacing(dp(2), 1f);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(0, dp(6), 0, dp(6));
            btn.setOnClickListener(v -> {
                UiCompat.playBounce(btn);
                switchTab(tab);
            });
            navBar.addView(btn, new LinearLayout.LayoutParams(0, -2, 1f));
            navButtons[i] = btn;
        }
        root.addView(navBar, new LinearLayout.LayoutParams(-1, -2));
    }

    private void switchTab(String tab) {
        currentTab = tab;
        String[] tabs = {"home", "analyze", "dup", "media", "settings"};
        for (int i = 0; i < navButtons.length; i++) {
            boolean active = tabs[i].equals(tab);
            navButtons[i].setTextColor(active ? p.accent : p.faint);
            navButtons[i].setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        }
        contentArea.removeAllViews();
        analyzeTab = null;
        dupTab = null;
        mediaTab = null;
        if ("home".equals(tab)) {
            contentArea.addView(homeTab, new LinearLayout.LayoutParams(-1, -1));
            refreshHome();
        } else if ("analyze".equals(tab)) {
            buildAnalyzeTab();
        } else if ("dup".equals(tab)) {
            buildDupTab();
        } else if ("media".equals(tab)) {
            buildMediaTab();
        } else {
            buildSettingsTab();
        }
        UiCompat.playFadeIn(contentArea);
    }

    // ===== 首页 =====
    private void buildHomeTab() {
        ScrollView sv = new ScrollView(this);
        homeTab = sv;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(24), dp(16), dp(20));
        sv.addView(box);

        TextView title = new TextView(this);
        title.setText("手机清理");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(p.ink);
        box.addView(title);

        // 存储概览卡
        LinearLayout spaceCard = new LinearLayout(this);
        spaceCard.setOrientation(LinearLayout.VERTICAL);
        spaceCard.setBackground(rounded(p.card, dp(UiCompat.cardRadiusDp())));
        spaceCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(14);
        box.addView(spaceCard, sp);

        spaceUsed = new TextView(this);
        spaceUsed.setTextSize(20);
        spaceUsed.setTypeface(Typeface.DEFAULT_BOLD);
        spaceUsed.setTextColor(p.ink);
        spaceCard.addView(spaceUsed);

        spaceSub = new TextView(this);
        spaceSub.setTextSize(12);
        spaceSub.setTextColor(p.muted);
        spaceSub.setPadding(0, dp(4), 0, dp(12));
        spaceCard.addView(spaceSub);

        FrameLayout barWrap = new FrameLayout(this);
        View bgBar = new View(this);
        bgBar.setBackground(rounded(p.chip, dp(5)));
        barWrap.addView(bgBar, new FrameLayout.LayoutParams(-1, dp(10)));
        usedFillBar = new View(this);
        usedFillBar.setBackground(rounded(p.accent, dp(5)));
        FrameLayout.LayoutParams fillLp = new FrameLayout.LayoutParams(dp(100), dp(10));
        barWrap.addView(usedFillBar, fillLp);
        spaceCard.addView(barWrap, new LinearLayout.LayoutParams(-1, dp(10)));

        // 一键扫描
        scanBtn = new TextView(this);
        scanBtn.setText("开始扫描");
        scanBtn.setTextSize(15);
        scanBtn.setTypeface(Typeface.DEFAULT_BOLD);
        scanBtn.setGravity(Gravity.CENTER);
        scanBtn.setTextColor(p.accentText);
        scanBtn.setPadding(0, dp(15), 0, dp(15));
        scanBtn.setBackground(rounded(p.accent, dp(16)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(16);
        scanBtn.setOnClickListener(v -> {
            UiCompat.playBounce(scanBtn);
            startScan();
        });
        box.addView(scanBtn, blp);

        scanBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        scanBar.setIndeterminate(true);
        scanBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams pbl = new LinearLayout.LayoutParams(-1, dp(4));
        pbl.topMargin = dp(10);
        box.addView(scanBar, pbl);

        scanStatus = new TextView(this);
        scanStatus.setTextSize(12);
        scanStatus.setTextColor(p.muted);
        scanStatus.setPadding(0, dp(8), 0, 0);
        box.addView(scanStatus);

        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rbl = new LinearLayout.LayoutParams(-1, -2);
        rbl.topMargin = dp(8);
        box.addView(resultBox, rbl);
    }

    /** 刷新首页数字与结果入口 */
    private void refreshHome() {
        if (spaceUsed == null) return;
        Store.Space s = Store.space();
        spaceUsed.setText(String.format(Locale.CHINA, "已用 %s / 共 %s", FormatUtil.size(s.used()), FormatUtil.size(s.total)));
        spaceSub.setText(String.format(Locale.CHINA, "可用 %s · 占用 %d%%", FormatUtil.size(s.free), s.usedPercent()));
        int pct = Math.max(2, s.usedPercent());
        spaceUsed.post(() -> {
            int w = ((View) usedFillBar.getParent()).getWidth();
            usedFillBar.setLayoutParams(new FrameLayout.LayoutParams(Math.max(dp(6), w * pct / 100), dp(10)));
        });

        resultBox.removeAllViews();
        // 病毒扫描入口：不依赖扫描结果，随时可用
        resultBox.addView(resultRow("病毒扫描", VirusDb.summary(this), "virus", null));
        // 应用专清入口：按应用清缓存（微信 / QQ / 抖音…）
        resultBox.addView(resultRow("应用专清",
                AppCacheScanner.lastApps == null
                        ? "按应用清理缓存：微信 / QQ / 抖音…"
                        : AppCacheScanner.lastApps.size() + " 个应用 · 点进去清缓存",
                "appclean", null));

        ScanEngine.Result r = ScanEngine.last;
        if (r == null) {
            TextView tip = new TextView(this);
            tip.setText(Store.getLastScan(this) > 0
                    ? "上次扫描：" + FormatUtil.time(Store.getLastScan(this)) + "（重开 App 后需重新扫描）"
                    : "点上方「开始扫描」找垃圾、大文件和重复文件");
            tip.setTextSize(12);
            tip.setTextColor(p.muted);
            tip.setPadding(0, dp(16), 0, 0);
            resultBox.addView(tip);
            addCategorySection(resultBox);
            return;
        }

        resultBox.addView(sectionLabel(r.fullScan ? "扫描结果（全盘）" : "扫描结果（仅本应用缓存）"));
        resultBox.addView(resultRow("垃圾文件", r.junk.size() + " 项 · " + FormatUtil.size(r.junkSize()),
                "junk", r.junk));
        resultBox.addView(resultRow("大文件（≥" + Store.getBigFileMb(this) + "MB）",
                r.big.size() + " 个 · " + FormatUtil.size(sum(r.big)), "big", r.big));
        resultBox.addView(resultRow("安装包（APK）",
                r.apk.size() + " 个 · " + FormatUtil.size(sum(r.apk)), "apk", r.apk));
        resultBox.addView(resultRow("重复文件",
                r.dups.size() + " 组 · 可清 " + FormatUtil.size(r.dupWasted()), "dup", null));

        TextView stat = new TextView(this);
        stat.setText("共扫描 " + r.scannedFiles + " 个文件 · " + FormatUtil.size(r.scannedBytes)
                + "\n" + FormatUtil.time(Store.getLastScan(this)));
        stat.setTextSize(11);
        stat.setTextColor(p.faint);
        stat.setPadding(dp(4), dp(10), 0, 0);
        resultBox.addView(stat);

        // 分类文件：按类型列全盘文件（空文件夹 / 大文件 / 冗余 / 新文件 / 安装包 / 图片 / 视频 / 音频 / 文档 / 压缩包）
        addCategorySection(resultBox);
    }

    private long sum(List<ScanEngine.Item> list) {
        long s = 0;
        for (ScanEngine.Item i : list) s += i.size;
        return s;
    }

    /** 结果入口行：点进结果页 */
    private View resultRow(String name, String sub, final String type, final List<ScanEngine.Item> items) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rounded(p.card, dp(14)));
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText(name);
        t1.setTextSize(15);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextColor(p.ink);
        col.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText(sub);
        t2.setTextSize(12);
        t2.setTextColor(p.muted);
        t2.setPadding(0, dp(3), 0, 0);
        col.addView(t2);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(p.faint);
        row.addView(arrow);

        row.setOnClickListener(v -> {
            if ("dup".equals(type)) {
                switchTab("dup");
                return;
            }
            if ("appclean".equals(type)) {
                startActivity(new Intent(this, AppCleanActivity.class));
                return;
            }
            if ("virus".equals(type)) {
                startActivity(new Intent(this, VirusScanActivity.class));
                return;
            }
            Intent i = new Intent(this, ResultsActivity.class);
            i.putExtra("type", type);
            startActivity(i);
        });
        row.setLayoutParams(lp);
        return row;
    }

    // ===== 扫描 =====
    private void startScan() {
        if (ScanEngine.running) {
            Toast.makeText(this, "正在扫描中…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Store.isScanAll(this) && !Environment.isExternalStorageManager()) {
            askAllFiles();
            return;
        }
        doScan();
    }

    private void doScan() {
        scanBar.setVisibility(View.VISIBLE);
        scanStatus.setText("准备扫描…");
        ScanEngine.scan(this, new ScanEngine.Progress() {
            @Override
            public void onProgress(final String path, final long files, final long bytes, int phase) {
                runOnUiThread(() -> scanStatus.setText(
                        (phase == 2 ? "查重中 · " : "扫描中 · ") + FormatUtil.shortPath(path, 40)
                                + "\n已扫 " + files + " 个文件 · " + FormatUtil.size(bytes)));
            }

            @Override
            public void onDone(final ScanEngine.Result r) {
                runOnUiThread(() -> {
                    Store.setLastScan(MainActivity.this, System.currentTimeMillis());
                    scanBar.setVisibility(View.GONE);
                    scanStatus.setText("扫描完成");
                    refreshHome();
                    Toast.makeText(MainActivity.this,
                            "扫描完成：垃圾 " + FormatUtil.size(r.junkSize())
                                    + " · 大文件 " + r.big.size() + " 个"
                                    + " · 重复 " + r.dups.size() + " 组",
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    /** 引导开启「所有文件访问」 */
    private void askAllFiles() {
        new AlertDialog.Builder(this)
                .setTitle("需要「所有文件访问」权限")
                .setMessage("全盘扫描/清理垃圾、大文件、重复文件需要这个权限。\n\n"
                        + "如果只想清理本应用自己的缓存，可以不授权。")
                .setPositiveButton("去授权", (d, w) -> {
                    try {
                        startActivity(new Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        } catch (Exception ignored) { }
                    }
                })
                .setNeutralButton("仅清本应用缓存", (d, w) -> {
                    Store.setScanAll(this, false);
                    lastSig = themeSig();
                    doScan();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 通用清理：给结果页和重复页共用 */
    static void cleanList(final AppCompatActivity a, final List<ScanEngine.Item> items,
                          final Runnable onDone) {
        if (items.isEmpty()) {
            Toast.makeText(a, "没有选中任何项目", Toast.LENGTH_SHORT).show();
            return;
        }
        long size = 0;
        for (ScanEngine.Item i : items) size += i.size;
        final android.app.ProgressDialog dlg = new android.app.ProgressDialog(a);
        dlg.setTitle("正在清理");
        dlg.setMax(items.size());
        dlg.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        dlg.setCancelable(false);
        dlg.show();
        Cleaner.clean(a, items, new Cleaner.Progress() {
            @Override
            public void onProgress(final int done, final int total, final long freed, String current) {
                a.runOnUiThread(() -> {
                    dlg.setProgress(done);
                    dlg.setMessage(FormatUtil.shortPath(current, 34) + "\n已释放 " + FormatUtil.size(freed));
                });
            }

            @Override
            public void onDone(final int deleted, final int failed, final long freed) {
                a.runOnUiThread(() -> {
                    dlg.dismiss();
                    Toast.makeText(a, "已清理 " + deleted + " 项，释放 " + FormatUtil.size(freed)
                            + (failed > 0 ? "（" + failed + " 项失败）" : ""), Toast.LENGTH_LONG).show();
                    if (onDone != null) onDone.run();
                });
            }
        });
    }

    // ===== 存储分析 =====
    private void buildAnalyzeTab() {
        ScrollView sv = new ScrollView(this);
        analyzeTab = sv;
        analyzeBox = new LinearLayout(this);
        analyzeBox.setOrientation(LinearLayout.VERTICAL);
        analyzeBox.setPadding(dp(16), dp(24), dp(16), dp(20));
        sv.addView(analyzeBox);

        TextView title = new TextView(this);
        title.setText("存储分析");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(p.ink);
        analyzeBox.addView(title);

        ScanEngine.Result r = ScanEngine.last;
        if (r == null) {
            analyzeBox.addView(hint("先回首页点「开始扫描」，这里会显示各类文件占用和目录排行"));
            contentArea.addView(sv, new LinearLayout.LayoutParams(-1, -1));
            return;
        }

        long total = 0;
        for (int i = 0; i < r.catSize.length; i++) total += r.catSize[i];

        analyzeBox.addView(sectionLabel("按类型占用（点一行看具体文件）"));
        LinearLayout catCard = new LinearLayout(this);
        catCard.setOrientation(LinearLayout.VERTICAL);
        catCard.setBackground(rounded(p.card, dp(16)));
        catCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        analyzeBox.addView(catCard, new LinearLayout.LayoutParams(-1, -2));
        for (int i = 0; i < r.catSize.length; i++) {
            if (r.catSize[i] <= 0) continue;
            final int ci = i;
            catCard.addView(catRow(ScanEngine.CAT_NAMES[i], r.catCount[i], r.catSize[i],
                    total <= 0 ? 0 : (int) (r.catSize[i] * 100 / total),
                    v -> openFileList(ScanEngine.CAT_NAMES[ci], "cat", ci)));
        }

        // 按来源（哪个 App / 场景产生的文件）
        boolean hasSrc = false;
        for (long s : r.srcSize) if (s > 0) hasSrc = true;
        if (hasSrc) {
            analyzeBox.addView(sectionLabel("按来源占用（点一行看具体文件）"));
            LinearLayout srcCard = new LinearLayout(this);
            srcCard.setOrientation(LinearLayout.VERTICAL);
            srcCard.setBackground(rounded(p.card, dp(16)));
            srcCard.setPadding(dp(16), dp(14), dp(16), dp(14));
            analyzeBox.addView(srcCard, new LinearLayout.LayoutParams(-1, -2));
            for (int i = 0; i < r.srcSize.length; i++) {
                if (r.srcSize[i] <= 0) continue;
                final int si = i;
                srcCard.addView(catRow(ScanEngine.SRC_NAMES[i], r.srcCount[i], r.srcSize[i],
                        total <= 0 ? 0 : (int) (r.srcSize[i] * 100 / total),
                        v -> openFileList(ScanEngine.SRC_NAMES[si] + " · 文件", "src", si)));
            }
        }

        analyzeBox.addView(sectionLabel("目录排行（占用最大的目录）"));
        LinearLayout dirCard = new LinearLayout(this);
        dirCard.setOrientation(LinearLayout.VERTICAL);
        dirCard.setBackground(rounded(p.card, dp(16)));
        dirCard.setPadding(dp(16), dp(10), dp(16), dp(10));
        analyzeBox.addView(dirCard, new LinearLayout.LayoutParams(-1, -2));
        if (r.topDirs.isEmpty()) {
            dirCard.addView(hint("暂无数据"));
        } else {
            int i = 0;
            for (Map.Entry<String, Long> e : r.topDirs.entrySet()) {
                if (i++ >= 15) break;
                dirCard.addView(kvRow(e.getKey(), FormatUtil.size(e.getValue())));
            }
        }
        contentArea.addView(sv, new LinearLayout.LayoutParams(-1, -1));
    }

    private View catRow(String name, int count, long size, int pct) {
        return catRow(name, count, size, pct, null);
    }

    private View catRow(String name, int count, long size, int pct, View.OnClickListener click) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, dp(8), 0, dp(8));
        if (click != null) col.setOnClickListener(click);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView n = new TextView(this);
        n.setText(name + " · " + count + " 个" + (click != null ? "  ›" : ""));
        n.setTextSize(13);
        n.setTextColor(p.ink);
        head.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = new TextView(this);
        v.setText(FormatUtil.size(size) + "  " + pct + "%");
        v.setTextSize(12);
        v.setTextColor(p.muted);
        head.addView(v);
        col.addView(head);
        FrameLayout bar = new FrameLayout(this);
        View bg = new View(this);
        bg.setBackground(rounded(p.chip, dp(4)));
        bar.addView(bg, new FrameLayout.LayoutParams(-1, dp(7)));
        View fill = new View(this);
        fill.setBackground(rounded(p.accent, dp(4)));
        bar.addView(fill, new FrameLayout.LayoutParams(dp(Math.max(4, pct * 3)), dp(7)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, dp(7));
        blp.topMargin = dp(6);
        col.addView(bar, blp);
        return col;
    }

    private View kvRow(String k, String v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(9), 0, dp(9));
        TextView kv = new TextView(this);
        kv.setText(k);
        kv.setTextSize(12);
        kv.setTextColor(p.ink);
        row.addView(kv, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView vv = new TextView(this);
        vv.setText(v);
        vv.setTextSize(12);
        vv.setTextColor(p.muted);
        row.addView(vv);
        return row;
    }

    // ===== 媒体（内置播放器） =====
    private void buildMediaTab() {
        ScrollView sv = new ScrollView(this);
        mediaTab = sv;
        mediaBox = new LinearLayout(this);
        mediaBox.setOrientation(LinearLayout.VERTICAL);
        mediaBox.setPadding(dp(16), dp(24), dp(16), dp(20));
        sv.addView(mediaBox);

        TextView title = new TextView(this);
        title.setText("媒体");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(p.ink);
        mediaBox.addView(title);

        ScanEngine.Result r = ScanEngine.last;
        boolean canRead = MediaLibrary.canRead(this, ScanEngine.CAT_AUDIO)
                || MediaLibrary.hasAllFiles(this);
        if (!canRead) {
            mediaBox.addView(hint("读取音乐/视频/照片需要授权：点下面任意一类进去，按提示授予「媒体权限」或「所有文件访问」即可。"));
        } else {
            mediaBox.addView(sectionLabel("点开列表，点条目即播放/预览"));
        }
        mediaBox.addView(mediaCard("音乐", ScanEngine.CAT_AUDIO, r));
        mediaBox.addView(mediaCard("视频", ScanEngine.CAT_VIDEO, r));
        mediaBox.addView(mediaCard("照片", ScanEngine.CAT_IMAGE, r));
        contentArea.addView(sv, new LinearLayout.LayoutParams(-1, -1));
    }

    private View mediaCard(String name, final int cat, ScanEngine.Result r) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(rounded(p.card, dp(16)));
        card.setPadding(dp(16), dp(18), dp(16), dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        card.setLayoutParams(lp);

        TextView icon = new TextView(this);
        icon.setText(cat == ScanEngine.CAT_AUDIO ? "♪" : (cat == ScanEngine.CAT_VIDEO ? "🎬" : "🖼"));
        icon.setTextSize(22);
        icon.setPadding(0, 0, dp(12), 0);
        card.addView(icon);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(p.ink);
        col.addView(t);
        TextView sub = new TextView(this);
        if (MediaLibrary.canRead(this, cat)) {
            sub.setText("点击打开列表播放");
        } else {
            sub.setText("需要授权后读取");
            sub.setTextColor(p.danger);
        }
        sub.setTextSize(12);
        if (MediaLibrary.canRead(this, cat)) sub.setTextColor(p.muted);
        col.addView(sub);
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(p.faint);
        card.addView(arrow);

        card.setOnClickListener(v -> {
            UiCompat.playBounce(card);
            openFileList(name, "media", cat);
        });
        return card;
    }

    private void openFileList(String title, String mode, int idx) {
        startActivity(new Intent(this, FileListActivity.class)
                .putExtra(FileListActivity.EXTRA_TITLE, title)
                .putExtra(FileListActivity.EXTRA_MODE, mode)
                .putExtra(FileListActivity.EXTRA_INDEX, idx));
    }

    // ===== 重复文件 =====
    private void buildDupTab() {
        // 固定头部：标题 + 摘要 + 清理按钮都放在上方，不用滚到底就能点
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(24), dp(16), dp(10));

        TextView title = new TextView(this);
        title.setText("重复文件");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(p.ink);
        header.addView(title);

        dupSummary = new TextView(this);
        dupSummary.setTextSize(12);
        dupSummary.setTextColor(p.muted);
        dupSummary.setPadding(0, dp(8), 0, dp(10));
        header.addView(dupSummary);

        dupCleanBtn = new TextView(this);
        dupCleanBtn.setTextSize(15);
        dupCleanBtn.setTypeface(Typeface.DEFAULT_BOLD);
        dupCleanBtn.setGravity(Gravity.CENTER);
        dupCleanBtn.setTextColor(p.accentText);
        dupCleanBtn.setPadding(0, dp(14), 0, dp(14));
        dupCleanBtn.setBackground(rounded(p.accent, dp(16)));
        header.addView(dupCleanBtn, new LinearLayout.LayoutParams(-1, -2));
        contentArea.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        dupTab = sv;
        dupBox = new LinearLayout(this);
        dupBox.setOrientation(LinearLayout.VERTICAL);
        dupBox.setPadding(dp(16), 0, dp(16), dp(40));
        sv.addView(dupBox);

        ScanEngine.Result r = ScanEngine.last;
        if (r == null || r.dups.isEmpty()) {
            dupBox.addView(hint("没有发现重复文件。先回首页扫描一次（重复文件需要全盘权限）"));
            dupCleanBtn.setVisibility(View.GONE);
            contentArea.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
            return;
        }
        dupCleanBtn.setVisibility(View.VISIBLE);
        dupCleanBtn.setOnClickListener(v -> {
            List<ScanEngine.Item> sel = new ArrayList<>();
            for (List<ScanEngine.Item> g : r.dups) {
                for (ScanEngine.Item i : g) if (i.selected) sel.add(i);
            }
            long size = 0;
            for (ScanEngine.Item i : sel) size += i.size;
            new AlertDialog.Builder(this)
                    .setTitle("清理重复文件")
                    .setMessage("将删除 " + sel.size() + " 个文件，释放约 " + FormatUtil.size(size)
                            + "。\n每组都会保留一份，确认继续？")
                    .setPositiveButton("清理", (d, w) -> cleanList(this, sel, () -> {
                        ScanEngine.last = null;
                        switchTab("home");
                    }))
                    .setNegativeButton("取消", null)
                    .show();
        });

        for (List<ScanEngine.Item> group : r.dups) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(rounded(p.card, dp(16)));
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(10);
            dupBox.addView(card, lp);

            TextView head = new TextView(this);
            head.setText("同一文件 " + group.size() + " 份 · 每份 " + FormatUtil.size(group.get(0).size));
            head.setTextSize(13);
            head.setTypeface(Typeface.DEFAULT_BOLD);
            head.setTextColor(p.ink);
            card.addView(head);

            for (ScanEngine.Item it : group) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, dp(6), 0, dp(6));
                android.widget.CheckBox cb = new android.widget.CheckBox(this);
                cb.setChecked(it.selected);
                cb.setButtonTintList(android.content.res.ColorStateList.valueOf(p.accent));
                cb.setOnCheckedChangeListener((b, checked) -> {
                    it.selected = checked;
                    updateDupSummary(r);
                });
                row.addView(cb);
                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                TextView path = new TextView(this);
                path.setText(new java.io.File(it.path).getName()
                        + (it.selected ? "" : "（保留）"));
                path.setTextSize(12);
                path.setTextColor(p.ink);
                col.addView(path);
                TextView sub = new TextView(this);
                sub.setText(FormatUtil.shortPath(new java.io.File(it.path).getParent(), 40));
                sub.setTextSize(10);
                sub.setTextColor(p.faint);
                col.addView(sub);
                row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
                card.addView(row);
            }
        }

        updateDupSummary(r);
        contentArea.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    private void updateDupSummary(ScanEngine.Result r) {
        int n = 0;
        long size = 0;
        for (List<ScanEngine.Item> g : r.dups) {
            for (ScanEngine.Item i : g) {
                if (i.selected) {
                    n++;
                    size += i.size;
                }
            }
        }
        if (dupSummary != null) {
            dupSummary.setText("共 " + r.dups.size() + " 组，勾选 " + n + " 个 · 可释放 " + FormatUtil.size(size));
        }
        if (dupCleanBtn != null) {
            dupCleanBtn.setText("清理选中的 " + n + " 个文件（" + FormatUtil.size(size) + "）");
        }
    }

    // ===== 设置 =====
    private void buildSettingsTab() {
        ScrollView sv = new ScrollView(this);
        settingsScroll = sv;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(24), dp(16), dp(20));
        sv.addView(box);

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(p.ink);
        box.addView(title);

        box.addView(sectionLabel("更多设置 · 外观"));
        LinearLayout look = new LinearLayout(this);
        look.setOrientation(LinearLayout.VERTICAL);
        look.setBackground(rounded(p.card, dp(16)));
        look.setPadding(dp(6), dp(6), dp(6), dp(6));
        box.addView(look, new LinearLayout.LayoutParams(-1, -2));

        String[] modeVals = {"auto", "light", "dark"};
        String[] modeNames = {"跟随系统", "浅色模式", "深色模式"};
        look.addView(pickRow("深浅模式", modeNames, modeVals, Store.getMode(this),
                v -> {
                    Store.setMode(this, v);
                    recreate();
                }));
        if (UiCompat.isModern()) {
            look.addView(pickRow("动态取色", new String[]{"开启", "关闭"},
                    new String[]{"1", "0"}, Store.isDynamicColor(this) ? "1" : "0",
                    v -> {
                        Store.setDynamicColor(this, "1".equals(v));
                        recreate();
                    }));
        }

        box.addView(sectionLabel("更多设置 · 扫描"));
        LinearLayout scanCard = new LinearLayout(this);
        scanCard.setOrientation(LinearLayout.VERTICAL);
        scanCard.setBackground(rounded(p.card, dp(16)));
        scanCard.setPadding(dp(6), dp(6), dp(6), dp(6));
        box.addView(scanCard, new LinearLayout.LayoutParams(-1, -2));
        scanCard.addView(pickRow("扫描范围", new String[]{"全盘", "仅本应用缓存"},
                new String[]{"1", "0"}, Store.isScanAll(this) ? "1" : "0",
                v -> {
                    Store.setScanAll(this, "1".equals(v));
                    if ("1".equals(v) && !Environment.isExternalStorageManager()) askAllFiles();
                    else Toast.makeText(this, "已保存，下次扫描生效", Toast.LENGTH_SHORT).show();
                }));
        scanCard.addView(pickRow("大文件阈值", new String[]{"50MB", "100MB", "200MB", "500MB"},
                new String[]{"50", "100", "200", "500"}, String.valueOf(Store.getBigFileMb(this)),
                v -> {
                    Store.setBigFileMb(this, Integer.parseInt(v));
                    Toast.makeText(this, "已设为 " + v + "MB", Toast.LENGTH_SHORT).show();
                }));
        scanCard.addView(actionRow("所有文件访问权限",
                Environment.isExternalStorageManager() ? "已授权" : "未授权（点此去开启）",
                v -> askAllFiles()));

        box.addView(sectionLabel("关于"));
        LinearLayout about = new LinearLayout(this);
        about.setOrientation(LinearLayout.VERTICAL);
        about.setBackground(rounded(p.card, dp(16)));
        about.setPadding(dp(6), dp(6), dp(6), dp(6));
        box.addView(about, new LinearLayout.LayoutParams(-1, -2));
        about.addView(actionRow("当前版本", "v" + BuildConfig.VERSION_NAME, v -> { }));
        about.addView(actionRow("检查更新",
                UpdateChecker.configured() ? "点此检查" : "未配置",
                v -> checkUpdate()));
        about.addView(actionRow("开发者的其他 App", "更多",
                v -> startActivity(new Intent(this, MoreAppsActivity.class))));

        contentArea.addView(sv, new LinearLayout.LayoutParams(-1, -1));
    }

    private ScrollView settingsScroll;

    // ===== 我的 =====
    private View userCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(rounded(p.card, dp(16)));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(14);
        card.setLayoutParams(lp);

        TextView av = new TextView(this);
        av.setText("清");
        av.setTextSize(20);
        av.setTypeface(Typeface.DEFAULT_BOLD);
        av.setGravity(Gravity.CENTER);
        av.setTextColor(p.accentText);
        av.setBackground(rounded(p.accent, dp(26)));
        LinearLayout.LayoutParams avlp = new LinearLayout.LayoutParams(dp(52), dp(52));
        avlp.rightMargin = dp(14);
        card.addView(av, avlp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(this);
        n.setText("普通用户");
        n.setTextSize(16);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setTextColor(p.ink);
        col.addView(n);
        TextView s = new TextView(this);
        s.setText("本机清理 · 不用登录 · 不联网也能用");
        s.setTextSize(11);
        s.setTextColor(p.muted);
        s.setPadding(0, dp(3), 0, 0);
        col.addView(s);
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView cnt = new TextView(this);
        ScanEngine.Result r = ScanEngine.last;
        cnt.setText(r == null ? "v" + BuildConfig.VERSION_NAME : "已扫 " + r.scannedFiles + " 个文件");
        cnt.setTextSize(12);
        cnt.setTextColor(p.muted);
        card.addView(cnt);
        return card;
    }

    /** 我的页快捷格：两行三列 */
    private View quickGrid() {
        String modeName = "auto".equals(Store.getMode(this)) ? "跟随系统"
                : ("dark".equals(Store.getMode(this)) ? "深色" : "浅色");
        View[][] tiles = new View[2][3];
        tiles[0][0] = mineTile("📦", "我的作品", "我们的其它应用",
                v -> startActivity(new Intent(this, MoreAppsActivity.class)));
        tiles[0][1] = mineTile("✉", "意见反馈", "去分发页留言", v -> feedbackDialog());
        tiles[0][2] = mineTile("👍", "好评支持", "觉得好用点个赞", v -> openUrl(DIST_URL));
        tiles[1][0] = mineTile("⤓", "检查更新", UpdateChecker.configured() ? "点此检查" : "未配置",
                v -> checkUpdate());
        tiles[1][1] = mineTile("🎨", "主题风格", modeName, v -> cycleTheme());
        tiles[1][2] = mineTile("🔑", "权限设置",
                Environment.isExternalStorageManager() ? "已授权" : "未授权", v -> askAllFiles());

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, -2);
        glp.topMargin = dp(10);
        grid.setLayoutParams(glp);
        for (View[] rowViews : tiles) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (View v : rowViews) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(v, lp);
            }
            grid.addView(row);
        }
        return grid;
    }

    private View mineTile(String glyph, String name, String sub, final View.OnClickListener click) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(rounded(p.card, dp(16)));
        card.setPadding(dp(6), dp(14), dp(6), dp(14));

        TextView g = new TextView(this);
        g.setText(glyph);
        g.setTextSize(20);
        g.setGravity(Gravity.CENTER);
        card.addView(g);

        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(12);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(p.ink);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(6), 0, 0);
        card.addView(t);

        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(10);
        s.setTextColor(p.muted);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, dp(2), 0, 0);
        card.addView(s);

        card.setOnClickListener(v -> {
            UiCompat.playBounce(card);
            click.onClick(v);
        });
        return card;
    }

    private void cycleTheme() {
        String m = Store.getMode(this);
        String next = "auto".equals(m) ? "light" : ("light".equals(m) ? "dark" : "auto");
        Store.setMode(this, next);
        Toast.makeText(this, "主题：" + ("auto".equals(next) ? "跟随系统"
                : ("dark".equals(next) ? "深色" : "浅色")), Toast.LENGTH_SHORT).show();
        recreate();
    }

    /** 分发页地址（蒲公英短链，公开信息） */
    private static final String DIST_URL = "https://www.pgyer.com/shoujiqingli-android";

    /** 反馈渠道：App 里不写任何私人账号，统一引导到分发页留言 */
    private void feedbackDialog() {
        new AlertDialog.Builder(this)
                .setTitle("意见反馈")
                .setMessage("在分发页的「评论 / 留言」里写就行，作者能看到：\n\n" + DIST_URL
                        + "\n\n反馈时带上手机型号、Android 版本和出问题的步骤，定位会快很多。")
                .setPositiveButton("打开分发页", (d, w) -> openUrl(DIST_URL))
                .setNegativeButton("复制链接", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", DIST_URL));
                        Toast.makeText(this, "链接已复制", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "打不开链接：" + url, Toast.LENGTH_SHORT).show();
        }
    }

    /** 一行里放多个可选项（单选） */
    private View pickRow(String name, final String[] names, final String[] vals, String cur,
                         final java.util.function.Consumer<String> onPick) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), dp(12), dp(12), dp(10));
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(13);
        t.setTextColor(p.muted);
        col.addView(t);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = dp(8);
        col.addView(row, rlp);
        for (int i = 0; i < names.length; i++) {
            final String v = vals[i];
            boolean sel = vals[i].equals(cur);
            TextView chip = new TextView(this);
            chip.setText(names[i]);
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(12), dp(9), dp(12), dp(9));
            chip.setTextColor(sel ? p.accentText : p.ink);
            chip.setBackground(rounded(sel ? p.accent : p.chip, dp(12)));
            chip.setOnClickListener(x -> {
                UiCompat.playBounce(chip);
                onPick.accept(v);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(dp(4), 0, dp(4), 0);
            row.addView(chip, lp);
        }
        return col;
    }

    private View actionRow(String name, String sub, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(14), dp(12), dp(14));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText(name);
        t1.setTextSize(14);
        t1.setTextColor(p.ink);
        col.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText(sub);
        t2.setTextSize(11);
        t2.setTextColor(p.muted);
        t2.setPadding(0, dp(3), 0, 0);
        col.addView(t2);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(18);
        arrow.setTextColor(p.faint);
        row.addView(arrow);
        row.setOnClickListener(click);
        return row;
    }

    // ===== 更新 =====
    private void checkUpdate() {
        if (!UpdateChecker.configured()) {
            new AlertDialog.Builder(this)
                    .setTitle("更新服务未配置")
                    .setMessage("App 内更新走 Loadly.io 分发，需要在构建用的 local.properties 里填：\n\n"
                            + "LOADLY_API_KEY=...\nLOADLY_APP_KEY=...\nLOADLY_SHORTCUT=...\n\n"
                            + "配好重新打包后，这里就能直接检查并 App 内下载安装。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        final android.app.ProgressDialog d =
                android.app.ProgressDialog.show(this, null, "正在检查更新…", true, false);
        UpdateChecker.check(this, new UpdateChecker.Callback() {
            @Override
            public void onResult(boolean hasNew, String version, long build, String note, boolean force) {
                runOnUiThread(() -> {
                    d.dismiss();
                    if (!hasNew) {
                        Toast.makeText(MainActivity.this, "已是最新版本 v" + BuildConfig.VERSION_NAME, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showUpdateDialog(version, note, force);
                });
            }

            @Override
            public void onError(String msg) {
                runOnUiThread(() -> {
                    d.dismiss();
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showUpdateDialog(final String version, String note, boolean force) {
        String msg = (note == null || note.isEmpty()) ? "有新版本可用" : note;
        if (force) msg = msg + "\n\n此版本为必须更新，请更新后再继续使用。";
        final android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
                .setTitle("发现新版本 v" + version)
                .setMessage(msg)
                .setPositiveButton("下载更新", null);
        if (!force) b.setNegativeButton("以后再说", null);
        final android.app.AlertDialog d = b.create();
        d.setCancelable(!force);
        d.setCanceledOnTouchOutside(!force);
        d.show();
        d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!UpdateChecker.canInstall(this)) {
                new AlertDialog.Builder(this)
                        .setTitle("需要安装权限")
                        .setMessage("请允许本应用安装未知应用，才能直接安装更新")
                        .setPositiveButton("去开启", (x, y) -> UpdateChecker.requestInstallPermission(this))
                        .setNegativeButton("取消", null)
                        .show();
                return;
            }
            d.dismiss();
            downloadAndInstall(version);
        });
    }

    private void downloadAndInstall(String version) {
        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setTitle("正在下载");
        pd.setMax(100);
        pd.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);
        pd.setCancelable(false);
        pd.show();
        UpdateChecker.downloadApk(this, version, new UpdateChecker.DownloadCallback() {
            @Override
            public void onProgress(final int percent) {
                runOnUiThread(() -> pd.setProgress(percent));
            }

            @Override
            public void onComplete(final java.io.File apk) {
                runOnUiThread(() -> {
                    pd.setProgress(100);
                    pd.dismiss();
                    UpdateChecker.installApk(MainActivity.this, apk);
                });
            }

            @Override
            public void onError(final String msg) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    new android.app.AlertDialog.Builder(MainActivity.this)
                            .setTitle("更新失败")
                            .setMessage(msg + "\n\n也可以改用浏览器打开分发页手动下载。")
                            .setPositiveButton("重试", (x, y) -> downloadAndInstall(version))
                            .setNeutralButton("浏览器打开", (x, y) -> UpdateChecker.openDownloadPage(MainActivity.this))
                            .setNegativeButton("取消", null)
                            .show();
                });
            }
        });
    }

    // ===== 小工具 =====
    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(p.muted);
        t.setPadding(dp(4), dp(20), dp(4), dp(8));
        return t;
    }

    private View hint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(p.muted);
        t.setPadding(dp(4), dp(24), dp(4), 0);
        return t;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private int dp(int v) {
        return UiCompat.dp(this, v);
    }

    /** 首页「分类文件」：两列卡片，各自的个数与占用 */
    private void addCategorySection(LinearLayout parent) {
        parent.addView(sectionLabel("分类文件"));
        ScanEngine.Result r = ScanEngine.last;
        if (r == null) {
            parent.addView(hint("先点上方「开始扫描」，这里就会按类型列出全盘文件"
                    + "（空文件夹 / 大文件 / 冗余文件 / 新文件 / 安装包 / 图片 / 视频 / 音频 / 文档 / 压缩包）。"));
            return;
        }
        List<View> cards = new ArrayList<>();
        cards.add(catCard("空文件(夹)", "\uD83D\uDDC2", r.emptyDirs.size(), sum(r.emptyDirs), "empty", 0));
        cards.add(catCard("大文件", "\uD83D\uDCE6", r.big.size(), sum(r.big), "big", 0));
        cards.add(catCard("冗余文件", "\uD83E\uDDF9", r.junk.size(), r.junkSize(), "junk", 0));
        cards.add(catCard("新文件", "\uD83C\uDD95", r.recent.size(), sum(r.recent), "recent", 0));
        cards.add(catCard("安装包", "\uD83E\uDD16", r.catCount[ScanEngine.CAT_APK],
                r.catSize[ScanEngine.CAT_APK], "apk", ScanEngine.CAT_APK));
        cards.add(catCard("图片", "\uD83D\uDDBC", r.catCount[ScanEngine.CAT_IMAGE],
                r.catSize[ScanEngine.CAT_IMAGE], "cat", ScanEngine.CAT_IMAGE));
        cards.add(catCard("视频", "\uD83C\uDFAC", r.catCount[ScanEngine.CAT_VIDEO],
                r.catSize[ScanEngine.CAT_VIDEO], "cat", ScanEngine.CAT_VIDEO));
        cards.add(catCard("音频", "\u266A", r.catCount[ScanEngine.CAT_AUDIO],
                r.catSize[ScanEngine.CAT_AUDIO], "cat", ScanEngine.CAT_AUDIO));
        cards.add(catCard("文档", "\uD83D\uDCC4", r.catCount[ScanEngine.CAT_DOC],
                r.catSize[ScanEngine.CAT_DOC], "cat", ScanEngine.CAT_DOC));
        cards.add(catCard("压缩包", "\uD83D\uDDDC", r.catCount[ScanEngine.CAT_ZIP],
                r.catSize[ScanEngine.CAT_ZIP], "cat", ScanEngine.CAT_ZIP));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        parent.addView(grid);
        for (int i = 0; i < cards.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, -2, 1f);
            lp1.setMargins(0, dp(10), dp(5), 0);
            row.addView(cards.get(i), lp1);
            if (i + 1 < cards.size()) {
                LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, -2, 1f);
                lp2.setMargins(dp(5), dp(10), 0, 0);
                row.addView(cards.get(i + 1), lp2);
            }
            grid.addView(row);
        }
        if (r.keepTruncated) {
            parent.addView(hint("文件太多，列表每类只保留前 6000 项（统计数字仍是完整的）。"));
        }
    }

    /** 分类文件卡片：图标 + 名称 + 个数/占用，点进去看文件列表 */
    private View catCard(String name, String glyph, int count, long size,
                         final String mode, final int idx) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(rounded(p.card, dp(16)));
        card.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView ic = new TextView(this);
        ic.setText(glyph);
        ic.setTextSize(19);
        ic.setPadding(0, 0, dp(10), 0);
        card.addView(ic);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(p.ink);
        col.addView(t);
        TextView s = new TextView(this);
        s.setText(count + " 个 \u00B7 " + FormatUtil.size(size));
        s.setTextSize(11);
        s.setTextColor(count > 0 ? p.muted : p.faint);
        s.setPadding(0, dp(2), 0, 0);
        col.addView(s);
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        card.setOnClickListener(v -> {
            UiCompat.playBounce(card);
            openFileList(name, mode, idx);
        });
        return card;
    }

}
