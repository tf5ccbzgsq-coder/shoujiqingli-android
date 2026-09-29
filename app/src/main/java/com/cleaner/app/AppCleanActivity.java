package com.cleaner.app;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 应用专清：按应用看占用、清缓存。
 *
 * - 常用专清：微信 / QQ / 抖音… 装了的直接排在前面，也能自己「增加应用」
 * - 全部应用：按外部存储占用从大到小
 * - 点某个应用 → 列出它自己的缓存目录 / 临时文件（可勾选清理）
 */
public class AppCleanActivity extends AppCompatActivity {

    private ThemeHelper.Palette p;
    private LinearLayout root, content, quickBox, appBox, permBox;
    private TextView statusTv;

    private List<AppCacheScanner.AppEntry> apps = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        p = ThemeHelper.get(this);
        buildUI();
        UiCompat.applyEdgeToEdge(this, root);
        UiCompat.applySystemBarIcons(this, Store.isDarkNow(this));
        load();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermCard();
        if (apps.isEmpty() && !AppCacheScanner.running) load();
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
        t.setText("应用专清");
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(p.ink);
        top.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(4), dp(16), dp(28));
        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        statusTv = new TextView(this);
        statusTv.setTextSize(12);
        statusTv.setTextColor(p.muted);
        statusTv.setPadding(dp(4), dp(2), 0, dp(8));
        statusTv.setText("正在读取应用…");
        content.addView(statusTv);

        permBox = new LinearLayout(this);
        permBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(permBox);

        content.addView(sectionLabel("常用专清"));
        quickBox = new LinearLayout(this);
        quickBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(quickBox);

        content.addView(sectionLabel("全部应用（按占用排序）"));
        appBox = new LinearLayout(this);
        appBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(appBox);

        TextView warn = new TextView(this);
        warn.setText("只清理应用自己的缓存目录（cache / temp / log / 缩略图等），"
                + "不动聊天记录、照片、文档和用户文件。");
        warn.setTextSize(11);
        warn.setTextColor(p.faint);
        warn.setPadding(dp(4), dp(16), dp(4), 0);
        content.addView(warn);

        refreshPermCard();
    }

    private void refreshPermCard() {
        permBox.removeAllViews();
        if (Environment.isExternalStorageManager()) return;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(p.card, dp(16)));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        permBox.addView(card, lp);

        TextView h = new TextView(this);
        h.setText("需要「所有文件访问」权限");
        h.setTextSize(14);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setTextColor(p.ink);
        card.addView(h);

        TextView s = new TextView(this);
        s.setText("否则读不到 Android/data 里各应用的缓存（Android 11 起系统限制）。");
        s.setTextSize(12);
        s.setTextColor(p.muted);
        s.setPadding(0, dp(4), 0, dp(10));
        card.addView(s);

        TextView go = new TextView(this);
        go.setText("去开启权限");
        go.setTextSize(14);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setGravity(Gravity.CENTER);
        go.setTextColor(p.accentText);
        go.setBackground(rounded(p.accent, dp(14)));
        go.setPadding(0, dp(12), 0, dp(12));
        go.setOnClickListener(v -> openAllFilesSettings());
        card.addView(go, new LinearLayout.LayoutParams(-1, -2));
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

    // ===== 数据 =====
    private void load() {
        statusTv.setText("正在读取应用…");
        AppCacheScanner.scan(this, new AppCacheScanner.Callback() {
            @Override
            public void onProgress(final int done, final int total, final String cur) {
                runOnUiThread(() -> statusTv.setText("正在读取应用 " + done + "/" + total + " · " + cur));
            }

            @Override
            public void onDone(List<AppCacheScanner.AppEntry> list) {
                runOnUiThread(() -> {
                    apps = list == null ? new ArrayList<AppCacheScanner.AppEntry>() : list;
                    long totalSize = 0;
                    int withData = 0;
                    for (AppCacheScanner.AppEntry e : apps) {
                        if (e.totalSize > 0) withData++;
                        totalSize += e.totalSize;
                    }
                    statusTv.setText(apps.isEmpty()
                            ? "没有读到应用数据，先开启「所有文件访问」再试"
                            : "共 " + withData + " 个应用有外部数据 · 合计 " + FormatUtil.size(totalSize));
                    rebuild();
                });
            }
        });
    }

    private void rebuild() {
        buildQuick();
        buildAppList();
    }

    private void buildQuick() {
        quickBox.removeAllViews();
        Set<String> custom = Store.getCustomApps(this);
        List<AppCacheScanner.AppEntry> quick = new ArrayList<>();
        for (AppCacheScanner.AppEntry e : apps) {
            if (e.popular || custom.contains(e.pkg)) quick.add(e);
        }
        for (AppCacheScanner.AppEntry e : apps) {
            if (quick.size() >= 8) break;
            if (!quick.contains(e) && e.cacheSize > 0) quick.add(e);
        }
        if (quick.isEmpty()) {
            quickBox.addView(hint("还没有可专清的应用：开启「所有文件访问」后重新进来即可。"));
        } else {
            List<View> cards = new ArrayList<>();
            for (AppCacheScanner.AppEntry e : quick) cards.add(appCard(e));
            addGrid(quickBox, cards);
        }

        TextView add = new TextView(this);
        add.setText("＋ 增加应用");
        add.setTextSize(14);
        add.setGravity(Gravity.CENTER);
        add.setTextColor(p.accent);
        add.setBackground(rounded(p.chip, dp(16)));
        add.setPadding(0, dp(14), 0, dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        add.setOnClickListener(v -> {
            UiCompat.playBounce(add);
            pickAppToAdd();
        });
        quickBox.addView(add, lp);
    }

    private void buildAppList() {
        appBox.removeAllViews();
        if (apps.isEmpty()) {
            appBox.addView(hint("这里会出现手机上各应用的外部存储占用，点进去就能清它的缓存。"));
            return;
        }
        int n = 0;
        for (AppCacheScanner.AppEntry e : apps) {
            if (e.totalSize <= 0 && !e.popular) continue;
            if (n++ >= 200) break;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6), dp(10), dp(6), dp(10));
            row.setBackground(rounded(p.card, dp(14)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(8);
            row.setLayoutParams(lp);

            row.addView(appIcon(e));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView t1 = new TextView(this);
            t1.setText(e.label);
            t1.setTextSize(14);
            t1.setTypeface(Typeface.DEFAULT_BOLD);
            t1.setTextColor(p.ink);
            col.addView(t1);
            TextView t2 = new TextView(this);
            String line = "占用 " + FormatUtil.size(e.totalSize);
            if (e.cacheCount > 0) {
                line += " · 可清理 " + FormatUtil.size(e.cacheSize) + "（" + e.cacheCount + " 项）";
            } else {
                line += " · " + e.files + " 个文件";
            }
            t2.setText(line);
            t2.setTextSize(11);
            t2.setTextColor(e.cacheCount > 0 ? p.muted : p.faint);
            t2.setPadding(0, dp(3), 0, 0);
            col.addView(t2);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView arrow = new TextView(this);
            arrow.setText("›");
            arrow.setTextSize(18);
            arrow.setTextColor(p.faint);
            row.addView(arrow);

            final AppCacheScanner.AppEntry entry = e;
            row.setOnClickListener(v -> {
                UiCompat.playBounce(row);
                openApp(entry);
            });
            row.setOnLongClickListener(v -> {
                showAppInfo(entry);
                return true;
            });
            appBox.addView(row);
        }
    }

    private View appCard(final AppCacheScanner.AppEntry e) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(rounded(p.card, dp(16)));
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.addView(appIcon(e));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(e.label);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(p.ink);
        t.setMaxLines(1);
        col.addView(t);
        TextView s = new TextView(this);
        String sub = e.totalSize > 0 ? FormatUtil.size(e.totalSize) : "暂无数据";
        if (e.cacheCount > 0) sub += " · 可清 " + FormatUtil.size(e.cacheSize);
        s.setText(sub);
        s.setTextSize(11);
        s.setTextColor(p.muted);
        s.setPadding(0, dp(2), 0, 0);
        col.addView(s);
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        card.setOnClickListener(v -> {
            UiCompat.playBounce(card);
            openApp(e);
        });
        return card;
    }

    private View appIcon(AppCacheScanner.AppEntry e) {
        int size = dp(38);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.rightMargin = dp(10);
        if (e.icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(e.icon);
            iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            iv.setLayoutParams(lp);
            return iv;
        }
        TextView tv = new TextView(this);
        String name = e.label == null || e.label.isEmpty() ? e.pkg : e.label;
        tv.setText(name.substring(0, 1).toUpperCase(Locale.ROOT));
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(p.accentText);
        tv.setBackground(rounded(p.accent, dp(19)));
        tv.setLayoutParams(lp);
        return tv;
    }

    private void addGrid(LinearLayout parent, List<View> cards) {
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
            parent.addView(row);
        }
    }

    // ===== 进入某个应用 =====
    private void openApp(final AppCacheScanner.AppEntry e) {
        final ProgressDialog dlg = new ProgressDialog(this);
        dlg.setMessage("正在分析「" + e.label + "」的缓存…");
        dlg.setCancelable(false);
        dlg.show();
        new Thread(() -> {
            final List<ScanEngine.Item> items = AppCacheScanner.cacheItems(e.pkg);
            runOnUiThread(() -> {
                try {
                    dlg.dismiss();
                } catch (Throwable ignored) { }
                if (items.isEmpty()) {
                    new AlertDialog.Builder(this)
                            .setTitle(e.label)
                            .setMessage("没有找到可清理的缓存目录。\n\n"
                                    + "该应用可能把数据都存在内部目录（/data/data，任何清理工具都拿不到），"
                                    + "或者还没产生缓存。")
                            .setPositiveButton("知道了", null)
                            .show();
                    return;
                }
                AppCacheScanner.pendingItems = items;
                AppCacheScanner.pendingTitle = e.label + " 专清";
                AppCacheScanner.pendingPkg = e.pkg;
                Intent i = new Intent(this, FileListActivity.class);
                i.putExtra(FileListActivity.EXTRA_TITLE, e.label + " 专清");
                i.putExtra(FileListActivity.EXTRA_MODE, "appjunk");
                startActivity(i);
            });
        }, "app-clean-items").start();
    }

    private void showAppInfo(AppCacheScanner.AppEntry e) {
        StringBuilder sb = new StringBuilder();
        sb.append("包名：").append(e.pkg).append("\n");
        sb.append("占用：").append(FormatUtil.size(e.totalSize))
                .append("（").append(e.files).append(" 个文件）\n\n");
        List<java.io.File> dirs = AppCacheScanner.dirsOf(e.pkg);
        if (dirs.isEmpty()) {
            sb.append("（没有找到外部目录）");
        } else {
            for (java.io.File d : dirs) sb.append(d.getAbsolutePath()).append("\n");
        }
        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setMessage(sb.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 「增加应用」：从已安装列表里挑 */
    private void pickAppToAdd() {
        List<AppCacheScanner.AppEntry> all = AppCacheScanner.lastInstalled;
        if (all == null || all.isEmpty()) {
            Toast.makeText(this, "应用列表还没读出来，稍等一下再试", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<AppCacheScanner.AppEntry> list = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (AppCacheScanner.AppEntry e : all) {
            if (list.size() >= 200) break;
            list.add(e);
            names.add(e.label);
        }
        new AlertDialog.Builder(this)
                .setTitle("增加应用（选中后显示在常用专清）")
                .setItems(names.toArray(new String[0]), (d, w) -> {
                    AppCacheScanner.AppEntry e = list.get(w);
                    Set<String> custom = Store.getCustomApps(this);
                    custom.add(e.pkg);
                    Store.setCustomApps(this, custom);
                    Toast.makeText(this, "已添加：" + e.label, Toast.LENGTH_SHORT).show();
                    rebuild();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== 小工具 =====
    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(p.muted);
        t.setPadding(dp(4), dp(18), 0, 0);
        return t;
    }

    private TextView hint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(p.muted);
        t.setPadding(dp(4), dp(12), dp(4), 0);
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
}
