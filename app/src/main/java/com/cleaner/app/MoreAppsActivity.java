package com.cleaner.app;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 开发者的其他 App：列出蒲公英账号下的其它应用（图标 / 介绍 / 版本），
 * 点开看详情，点「下载」直接在 App 内下载安装。
 */
public class MoreAppsActivity extends AppCompatActivity {

    private ThemeHelper.Palette p;
    private LinearLayout root, listBox;
    private TextView status;

    private final List<PgyerStore.AppInfo> apps = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        p = ThemeHelper.get(this);
        buildUI();
        UiCompat.applyEdgeToEdge(this, root);
        UiCompat.applySystemBarIcons(this, Store.isDarkNow(this));
        load();
    }

    private void buildUI() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.bg);
        setContentView(root);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(40), dp(8), dp(4));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(28);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setTextColor(p.ink);
        back.setPadding(dp(12), 0, dp(12), 0);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView title = new TextView(this);
        title.setText("开发者的其他 App");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(p.ink);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextSize(12);
        status.setTextColor(p.muted);
        status.setPadding(dp(24), dp(6), dp(24), dp(6));
        status.setText("正在读取…");
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(dp(18), 0, dp(18), dp(30));
        sv.addView(listBox);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    // ===== 数据 =====
    private void load() {
        PgyerStore.loadApps(this, (list, err) -> runOnUiThread(() -> {
            apps.clear();
            if (list != null) apps.addAll(list);
            render(err);
        }));
    }

    private void render(String err) {
        listBox.removeAllViews();
        if (!apps.isEmpty()) {
            status.setText("共 " + apps.size() + " 个应用 · 点卡片看介绍，点下载可直接安装");
            for (PgyerStore.AppInfo a : apps) listBox.addView(appCard(a));
            return;
        }
        if (err != null) {
            status.setText("读取失败：" + err);
        } else {
            status.setText("暂时没有其他应用");
        }
        listBox.addView(fallbackCard());
    }

    /** 卡片：图标 + 名称/版本 + 一句介绍 + 下载按钮 */
    private View appCard(final PgyerStore.AppInfo a) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(rounded(p.card, dp(18)));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        card.setLayoutParams(lp);

        card.addView(iconView(a, 56), new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, dp(10), 0);
        TextView name = new TextView(this);
        name.setText(a.name == null || a.name.isEmpty() ? "未命名应用" : a.name);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(p.ink);
        col.addView(name);
        TextView meta = new TextView(this);
        String ver = a.version == null || a.version.isEmpty() ? "" : "v" + a.version + " · ";
        meta.setText(ver + (a.fileSize > 0 ? FormatSize.of(a.fileSize) : "")
                + (a.cate != null && !a.cate.isEmpty() ? " · " + a.cate : ""));
        meta.setTextSize(11);
        meta.setTextColor(p.muted);
        meta.setPadding(0, dp(2), 0, 0);
        col.addView(meta);
        TextView intro = new TextView(this);
        intro.setText(a.shortIntro(46));
        intro.setTextSize(11);
        intro.setTextColor(p.faint);
        intro.setMaxLines(2);
        intro.setPadding(0, dp(3), 0, 0);
        col.addView(intro);
        card.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView dl = new TextView(this);
        dl.setText("下载");
        dl.setTextSize(13);
        dl.setTypeface(Typeface.DEFAULT_BOLD);
        dl.setGravity(Gravity.CENTER);
        dl.setTextColor(p.accentText);
        dl.setBackground(rounded(p.accent, dp(14)));
        dl.setPadding(dp(16), dp(9), dp(16), dp(9));
        dl.setOnClickListener(v2 -> download(a));
        card.addView(dl);

        card.setOnClickListener(v -> showDetail(a));
        return card;
    }

    private View iconView(PgyerStore.AppInfo a, int sizeDp) {
        if (a.iconUrl != null && !a.iconUrl.isEmpty()) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackground(rounded(p.chip, dp(14)));
            iv.setClipToOutline(true);
            try {
                iv.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            } catch (Throwable ignored) { }
            IconLoader.load(this, a.iconUrl, iv);
            return iv;
        }
        // 没有图标就用首字占位
        TextView ph = new TextView(this);
        String n = a.name == null || a.name.isEmpty() ? "A" : a.name.substring(0, 1);
        ph.setText(n);
        ph.setTextSize(20);
        ph.setTypeface(Typeface.DEFAULT_BOLD);
        ph.setGravity(Gravity.CENTER);
        ph.setTextColor(p.accent);
        ph.setBackground(rounded(p.chip, dp(14)));
        return ph;
    }

    private View fallbackCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(p.card, dp(18)));
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(12);
        card.setLayoutParams(lp);

        TextView t1 = new TextView(this);
        t1.setText("暂时连不上应用列表");
        t1.setTextSize(14);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextColor(p.ink);
        card.addView(t1);
        TextView t2 = new TextView(this);
        t2.setText("可以直接用浏览器打开分发页查看：");
        t2.setTextSize(12);
        t2.setTextColor(p.muted);
        t2.setPadding(0, dp(6), 0, dp(6));
        card.addView(t2);

        TextView open = new TextView(this);
        open.setText("打开分发页");
        open.setTextSize(13);
        open.setGravity(Gravity.CENTER);
        open.setTextColor(p.accentText);
        open.setBackground(rounded(p.accent, dp(14)));
        open.setPadding(0, dp(11), 0, dp(11));
        open.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.pgyer.com/shoujiqingli-android"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                Toast.makeText(this, "打不开浏览器", Toast.LENGTH_SHORT).show();
            }
        });
        card.addView(open, new LinearLayout.LayoutParams(-1, -2));
        return card;
    }

    // ===== 详情 =====
    private void showDetail(final PgyerStore.AppInfo a) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(16), dp(20), dp(4));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(iconView(a, 64), new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(a.name);
        name.setTextSize(17);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(p.ink);
        col.addView(name);
        TextView meta = new TextView(this);
        meta.setText((a.version.isEmpty() ? "" : "版本 " + a.version)
                + (a.fileSize > 0 ? " · " + FormatSize.of(a.fileSize) : ""));
        meta.setTextSize(12);
        meta.setTextColor(p.muted);
        meta.setPadding(0, dp(3), 0, 0);
        col.addView(meta);
        head.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        box.addView(head);

        TextView body = new TextView(this);
        body.setText(a.intro == null || a.intro.isEmpty() ? "开发者出品的另一个应用。" : a.intro);
        body.setTextSize(13);
        body.setTextColor(p.ink);
        body.setLineSpacing(dp(3), 1.15f);
        body.setPadding(0, dp(14), 0, 0);
        ScrollView sv = new ScrollView(this);
        sv.addView(body);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
        box.addView(sv, slp);

        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("应用介绍")
                .setView(box)
                .setPositiveButton("下载安装", null)
                .setNegativeButton("关闭", null)
                .create();
        d.show();
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            d.dismiss();
            download(a);
        });
    }

    // ===== 下载安装 =====
    private void download(final PgyerStore.AppInfo a) {
        if (!PgyerStore.canInstall(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("需要安装权限")
                    .setMessage("请允许本应用安装未知来源的应用，才能直接装其它 App")
                    .setPositiveButton("去开启", (x, y) -> PgyerStore.requestInstallPermission(this))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("准备下载 " + (a.name == null ? "" : a.name));
        pd.setMessage("正在获取下载地址…");
        pd.setMax(100);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setCancelable(false);
        pd.show();

        PgyerStore.downloadUrl(a.appKey, (url, err) -> runOnUiThread(() -> {
            if (url == null || err != null) {
                pd.dismiss();
                toast("获取下载地址失败：" + (err == null ? "" : err));
                return;
            }
            pd.setMessage("正在下载…");
            PgyerStore.downloadApk(this, url, a.name, new PgyerStore.DlCb() {
                @Override
                public void onProgress(int percent) {
                    runOnUiThread(() -> pd.setProgress(percent));
                }

                @Override
                public void onDone(File apk) {
                    runOnUiThread(() -> {
                        pd.setProgress(100);
                        pd.dismiss();
                        PgyerStore.install(MoreAppsActivity.this, apk);
                    });
                }

                @Override
                public void onError(final String msg) {
                    runOnUiThread(() -> {
                        pd.dismiss();
                        new AlertDialog.Builder(MoreAppsActivity.this)
                                .setTitle("下载失败")
                                .setMessage(msg + "\n\n可以改用浏览器打开分发页手动下载。")
                                .setPositiveButton("重试", (x, y) -> download(a))
                                .setNeutralButton("浏览器打开", (x, y) -> openPage(a))
                                .setNegativeButton("取消", null)
                                .show();
                    });
                }
            });
        }));
    }

    private void openPage(PgyerStore.AppInfo a) {
        String url = a.pageUrl();
        if (url == null || url.isEmpty()) url = "https://www.pgyer.com";
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            toast("打不开浏览器");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ===== 小工具 =====
    private android.graphics.drawable.GradientDrawable rounded(int color, int radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    /** 文件大小格式化（本地小工具，避免依赖别处实现） */
    static final class FormatSize {
        static String of(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0);
            if (bytes < 1024L * 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0);
            return String.format(java.util.Locale.ROOT, "%.2f GB", bytes / 1073741824.0);
        }
    }
}
