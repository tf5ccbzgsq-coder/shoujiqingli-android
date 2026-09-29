package com.cleaner.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 结果页：垃圾 / 大文件 / 安装包 三种列表，带勾选与清理 */
public class ResultsActivity extends AppCompatActivity {

    private ThemeHelper.Palette p;
    private LinearLayout root, listBox;
    private TextView cleanBtn, summary;
    private List<ScanEngine.Item> items;
    private String type;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        p = ThemeHelper.get(this);
        type = getIntent() != null ? getIntent().getStringExtra("type") : "junk";
        if (type == null) type = "junk";
        items = pickItems(type);
        if (items == null) {
            Toast.makeText(this, "结果已失效，请重新扫描", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        buildUI();
        UiCompat.applyEdgeToEdge(this, root);
        UiCompat.applySystemBarIcons(this, Store.isDarkNow(this));
    }

    private List<ScanEngine.Item> pickItems(String type) {
        ScanEngine.Result r = ScanEngine.last;
        if (r == null) return null;
        if ("big".equals(type)) return r.big;
        if ("apk".equals(type)) return r.apk;
        return r.junk;
    }

    private String titleOf(String type) {
        if ("big".equals(type)) return "大文件";
        if ("apk".equals(type)) return "安装包";
        return "垃圾文件";
    }

    private void buildUI() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.bg);
        setContentView(root);

        // 顶部栏
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(40), dp(8), dp(8));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(28);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setTextColor(p.ink);
        back.setPadding(dp(12), 0, dp(12), 0);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView title = new TextView(this);
        title.setText(titleOf(type));
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(p.ink);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView selectAll = new TextView(this);
        selectAll.setText("全选");
        selectAll.setTextSize(14);
        selectAll.setTextColor(p.accent);
        selectAll.setPadding(dp(12), dp(6), dp(12), dp(6));
        selectAll.setOnClickListener(v -> {
            boolean allSelected = true;
            for (ScanEngine.Item i : items) if (!i.selected) allSelected = false;
            for (ScanEngine.Item i : items) i.selected = !allSelected;
            rebuildList();
        });
        top.addView(selectAll);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        summary = new TextView(this);
        summary.setTextSize(12);
        summary.setTextColor(p.muted);
        summary.setPadding(dp(20), 0, dp(20), dp(8));
        root.addView(summary);

        if ("big".equals(type) || "apk".equals(type)) {
            TextView warn = new TextView(this);
            warn.setText("注意：这些属于你自己的文件，默认没有勾选，请确认后再清理");
            warn.setTextSize(11);
            warn.setTextColor(p.danger);
            warn.setPadding(dp(20), 0, dp(20), dp(10));
            root.addView(warn);
        }

        // 清理按钮放上方（固定，不用滚到底）
        LinearLayout actionBar = new LinearLayout(this);
        actionBar.setOrientation(LinearLayout.VERTICAL);
        actionBar.setPadding(dp(16), dp(2), dp(16), dp(8));
        cleanBtn = new TextView(this);
        cleanBtn.setTextSize(15);
        cleanBtn.setTypeface(Typeface.DEFAULT_BOLD);
        cleanBtn.setGravity(Gravity.CENTER);
        cleanBtn.setTextColor(p.accentText);
        cleanBtn.setPadding(0, dp(14), 0, dp(14));
        cleanBtn.setBackground(rounded(p.accent, dp(16)));
        cleanBtn.setOnClickListener(v -> confirmClean());
        actionBar.addView(cleanBtn, new LinearLayout.LayoutParams(-1, -2));
        root.addView(actionBar, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(dp(14), 0, dp(14), dp(16));
        sv.addView(listBox);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        rebuildList();
    }

    private void rebuildList() {
        listBox.removeAllViews();
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("没有需要处理的项目");
            empty.setTextSize(14);
            empty.setTextColor(p.muted);
            empty.setPadding(dp(6), dp(30), 0, 0);
            listBox.addView(empty);
        }
        int shown = 0;
        for (final ScanEngine.Item it : items) {
            if (shown >= 400) {
                TextView more = new TextView(this);
                more.setText("（仅显示前 400 项）");
                more.setTextSize(11);
                more.setTextColor(p.faint);
                more.setPadding(dp(6), dp(12), 0, 0);
                listBox.addView(more);
                break;
            }
            shown++;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(rounded(p.card, dp(14)));
            row.setPadding(dp(6), dp(10), dp(14), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(8);
            listBox.addView(row, lp);

            CheckBox cb = new CheckBox(this);
            cb.setChecked(it.selected);
            cb.setButtonTintList(android.content.res.ColorStateList.valueOf(p.accent));
            cb.setOnCheckedChangeListener((b, checked) -> {
                it.selected = checked;
                updateSummary();
            });
            row.addView(cb);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(this);
            name.setText(new File(it.path).getName());
            name.setTextSize(13);
            name.setTextColor(p.ink);
            col.addView(name);
            TextView sub = new TextView(this);
            sub.setText((it.size > 0 ? FormatUtil.size(it.size) + " · " : "空文件夹 · ")
                    + FormatUtil.shortPath(new File(it.path).getParent(), 44));
            sub.setTextSize(10);
            sub.setTextColor(p.faint);
            sub.setPadding(0, dp(2), 0, 0);
            col.addView(sub);
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

            // 音频/视频/图片：点一行直接进内置播放器
            if (ScanEngine.isPlayableMedia(it) || it.cat == ScanEngine.CAT_IMAGE) {
                TextView play = new TextView(this);
                play.setText("▶");
                play.setTextSize(14);
                play.setTextColor(p.accent);
                play.setPadding(dp(6), 0, dp(4), 0);
                row.addView(play);
                row.setOnClickListener(v -> openPlayer(it));
            }
        }
        updateSummary();
    }

    private void updateSummary() {
        int n = 0;
        long size = 0;
        for (ScanEngine.Item i : items) {
            if (i.selected) {
                n++;
                size += i.size;
            }
        }
        summary.setText("共 " + items.size() + " 项 · 勾选 " + n + " 项 · 可释放 " + FormatUtil.size(size));
        cleanBtn.setText(n == 0 ? "清理选中" : "清理选中的 " + n + " 项（" + FormatUtil.size(size) + "）");
    }

    private void confirmClean() {
        final List<ScanEngine.Item> sel = new ArrayList<>();
        for (ScanEngine.Item i : items) if (i.selected) sel.add(i);
        if (sel.isEmpty()) {
            Toast.makeText(this, "没有选中任何项目", Toast.LENGTH_SHORT).show();
            return;
        }
        long size = 0;
        for (ScanEngine.Item i : sel) size += i.size;
        new AlertDialog.Builder(this)
                .setTitle("确认清理")
                .setMessage("将删除 " + sel.size() + " 项，释放约 " + FormatUtil.size(size)
                        + "。\n删除后无法恢复，确认继续？")
                .setPositiveButton("清理", (d, w) -> MainActivity.cleanList(this, sel, () -> {
                    // 清完后把已删项移出列表
                    items.removeAll(sel);
                    rebuildList();
                }))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 打开内置播放器：带上同一类型的媒体，可上下切换 */
    private void openPlayer(ScanEngine.Item target) {
        List<PlayerActivity.PlayItem> q = new ArrayList<>();
        int start = 0;
        for (ScanEngine.Item i : items) {
            if (!i.dir && i.cat == target.cat) {
                if (i == target) start = q.size();
                q.add(new PlayerActivity.PlayItem(null, i.path, i.name()));
            }
        }
        if (!q.isEmpty()) PlayerActivity.play(this, q, start);
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
