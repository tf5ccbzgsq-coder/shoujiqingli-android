package com.cleaner.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 病毒扫描 / 病毒库查询（数据源：abuse.ch MalwareBazaar，或自定义清单源）。
 *
 * - 更新病毒库：拉取恶意样本 SHA-256 到本地
 * - 开始扫描：把设备上的 APK / 可执行文件算哈希并与库比对，命中即可删除
 * - 搜索：支持 SHA-256、文件名、或直接选一个文件算哈希（本地 + 在线）
 */
public class VirusScanActivity extends AppCompatActivity {

    private static final int REQ_PICK_FILE = 0x51;

    private ThemeHelper.Palette p;
    private LinearLayout root, content, hitBox;
    private TextView dbInfo, scanStatus, searchResult;
    private ProgressBar bar;
    private EditText searchInput;
    private View hitHeader;

    private VirusScan.Out lastOut;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        p = ThemeHelper.get(this);
        buildUI();
        UiCompat.applyEdgeToEdge(this, root);
        UiCompat.applySystemBarIcons(this, Store.isDarkNow(this));
        refreshDbInfo();
        if (VirusScan.last != null) {
            lastOut = VirusScan.last;
            renderResults(lastOut);
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
        top.setPadding(dp(8), dp(38), dp(8), dp(6));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(28);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setTextColor(p.ink);
        back.setPadding(dp(12), 0, dp(12), 0);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView title = new TextView(this);
        title.setText("病毒扫描");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(p.ink);
        title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(4), dp(16), dp(28));
        sv.addView(content);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        // 1) 病毒库状态
        content.addView(section("病毒库"));
        LinearLayout dbCard = card();
        dbInfo = new TextView(this);
        dbInfo.setTextSize(12);
        dbInfo.setTextColor(p.muted);
        dbCard.addView(dbInfo);
        LinearLayout dbBtns = new LinearLayout(this);
        dbBtns.setOrientation(LinearLayout.HORIZONTAL);
        dbBtns.setPadding(0, dp(10), 0, 0);
        dbBtns.addView(smallBtn("更新病毒库", p.accent, p.accentText, v -> syncDb()), new LinearLayout.LayoutParams(0, -2, 1f));
        dbBtns.addView(spacer(8));
        dbBtns.addView(smallBtn("配置 Auth-Key", p.chip, p.ink, v -> askAuthKey()), new LinearLayout.LayoutParams(0, -2, 1f));
        dbCard.addView(dbBtns);
        TextView feedTip = new TextView(this);
        feedTip.setText("数据源 abuse.ch MalwareBazaar。Auth-Key 免费注册：auth.abuse.ch  ›  也支持自定义清单源（纯文本，每行 sha256[,家族名]）");
        feedTip.setTextSize(10);
        feedTip.setTextColor(p.faint);
        feedTip.setPadding(0, dp(8), 0, 0);
        feedTip.setOnClickListener(v -> askFeedUrl());
        dbCard.addView(feedTip);
        content.addView(dbCard);

        // 2) 扫描
        content.addView(section("全盘哈希扫描"));
        LinearLayout scanCard = card();
        TextView scanTip = new TextView(this);
        scanTip.setText("扫描手机上的 APK、exe、so 等可执行文件，逐个算 SHA-256 与病毒库比对。只读扫描，不会自己删东西。");
        scanTip.setTextSize(11);
        scanTip.setTextColor(p.muted);
        scanCard.addView(scanTip);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(12);
        scanCard.addView(bigBtn("开始扫描", p.accent, p.accentText, v -> startScan()), blp);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, dp(6));
        plp.topMargin = dp(12);
        scanCard.addView(bar, plp);
        scanStatus = new TextView(this);
        scanStatus.setTextSize(11);
        scanStatus.setTextColor(p.muted);
        scanStatus.setPadding(0, dp(8), 0, 0);
        scanCard.addView(scanStatus);
        content.addView(scanCard);

        // 3) 搜索
        content.addView(section("病毒查询 / 搜索"));
        LinearLayout searchCard = card();
        searchInput = new EditText(this);
        searchInput.setHint("粘贴 SHA-256，或输入文件名关键字");
        searchInput.setTextSize(13);
        searchInput.setHintTextColor(p.faint);
        searchInput.setTextColor(p.ink);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchCard.addView(searchInput, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout searchBtns = new LinearLayout(this);
        searchBtns.setOrientation(LinearLayout.HORIZONTAL);
        searchBtns.setPadding(0, dp(10), 0, 0);
        searchBtns.addView(smallBtn("搜索", p.accent, p.accentText, v -> doSearch()), new LinearLayout.LayoutParams(0, -2, 1f));
        searchBtns.addView(spacer(8));
        searchBtns.addView(smallBtn("选文件查毒", p.chip, p.ink, v -> pickFile()), new LinearLayout.LayoutParams(0, -2, 1f));
        searchCard.addView(searchBtns);
        searchResult = new TextView(this);
        searchResult.setTextSize(12);
        searchResult.setTextColor(p.ink);
        searchResult.setPadding(0, dp(10), 0, 0);
        searchResult.setTextIsSelectable(true);
        searchCard.addView(searchResult);
        content.addView(searchCard);

        // 4) 结果
        hitHeader = section("扫描结果");
        hitHeader.setVisibility(View.GONE);
        content.addView(hitHeader);
        hitBox = new LinearLayout(this);
        hitBox.setOrientation(LinearLayout.VERTICAL);
        content.addView(hitBox);
    }

    private void refreshDbInfo() {
        int n = VirusDb.count(this);
        dbInfo.setText(VirusDb.summary(this)
                + (n > 0 ? "" : "\n配置 Auth-Key 即可联网更新（免费，1 分钟注册）"));
        if (!VirusDb.authKey(this).isEmpty()) {
            dbInfo.append("\nAuth-Key 已配置");
        }
    }

    // ===== 病毒库 =====
    private void syncDb() {
        if (VirusDb.authKey(this).isEmpty() && VirusDb.feedUrl(this).isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("还没有配置数据源")
                    .setMessage("两种方式任选：\n\n"
                            + "① 用 abuse.ch 的免费 Auth-Key（推荐）\n"
                            + "   手机浏览器打开 auth.abuse.ch 注册 → 复制 Auth-Key → 点下面「配置 Auth-Key」粘贴进来\n\n"
                            + "② 自定义清单源\n"
                            + "   任何返回纯文本的网址，每行一个 SHA-256（可带逗号+家族名）")
                    .setPositiveButton("配置 Auth-Key", (d, w) -> askAuthKey())
                    .setNeutralButton("自定义源", (d, w) -> askFeedUrl())
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        scanStatus.setText("正在更新病毒库…");
        VirusDb.sync(this, new VirusDb.SyncCallback() {
            @Override
            public void onStep(String msg) {
                runOnUiThread(() -> scanStatus.setText(msg));
            }

            @Override
            public void onDone(int added, int total, String err) {
                runOnUiThread(() -> {
                    refreshDbInfo();
                    if (err != null) {
                        scanStatus.setText("更新未完成：" + err);
                    } else {
                        scanStatus.setText("病毒库已更新：新增 " + added + " 条，共 " + total + " 条");
                    }
                    Toast.makeText(VirusScanActivity.this,
                            err == null ? "病毒库已更新（共 " + total + " 条）" : err, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void askAuthKey() {
        final EditText et = new EditText(this);
        et.setTextSize(13);
        et.setHint("粘贴 abuse.ch Auth-Key");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        String cur = VirusDb.authKey(this);
        if (!cur.isEmpty()) et.setText(cur);
        new AlertDialog.Builder(this)
                .setTitle("abuse.ch Auth-Key")
                .setMessage("免费注册地址：https://auth.abuse.ch/\n注册后在个人页面复制 Auth-Key（形如 32 位十六进制串）")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    VirusDb.setAuthKey(this, et.getText().toString());
                    refreshDbInfo();
                    Toast.makeText(this, "已保存，可以点「更新病毒库」了", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("清除", (d, w) -> {
                    VirusDb.setAuthKey(this, "");
                    refreshDbInfo();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askFeedUrl() {
        final EditText et = new EditText(this);
        et.setTextSize(12);
        et.setHint("https://…/list.txt");
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        et.setText(VirusDb.feedUrl(this));
        new AlertDialog.Builder(this)
                .setTitle("自定义清单源")
                .setMessage("任意返回纯文本的网址，每行一条：\nsha256\nsha256,家族名\nsha256,家族名,文件名")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    VirusDb.setFeedUrl(this, et.getText().toString());
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("清除", (d, w) -> VirusDb.setFeedUrl(this, ""))
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== 扫描 =====
    private void startScan() {
        if (VirusScan.running) {
            Toast.makeText(this, "正在扫描…", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Environment.isExternalStorageManager()) {
            new AlertDialog.Builder(this)
                    .setTitle("需要「所有文件访问」权限")
                    .setMessage("要扫描手机里的文件，需要允许本应用访问全部文件。\n（系统设置 → 应用 → 手机清理 → 所有文件访问）")
                    .setPositiveButton("去开启", (d, w) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        if (!VirusDb.ready(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("病毒库还是空的")
                    .setMessage("没有可比对的样本哈希，扫描不出结果。\n先点「更新病毒库」（需要免费的 abuse.ch Auth-Key）。\n\n也可以继续扫描，只生成文件哈希清单。")
                    .setPositiveButton("继续扫描", (d, w) -> doScan())
                    .setNeutralButton("去更新", (d, w) -> syncDb())
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        doScan();
    }

    private void doScan() {
        bar.setVisibility(View.VISIBLE);
        bar.setProgress(0);
        scanStatus.setText("正在收集可执行文件…");
        hitBox.removeAllViews();
        hitHeader.setVisibility(View.GONE);
        VirusScan.start(this, new VirusScan.Progress() {
            @Override
            public void onProgress(int done, int total, String current, int hits) {
                runOnUiThread(() -> {
                    if (total > 0) bar.setProgress((int) (done * 100L / total));
                    scanStatus.setText("已检查 " + done + "/" + total + " 个文件 · 命中 " + hits
                            + "\n" + current);
                });
            }

            @Override
            public void onDone(VirusScan.Out out) {
                runOnUiThread(() -> {
                    bar.setProgress(100);
                    lastOut = out;
                    if (out.needPermission) {
                        scanStatus.setText("缺少「所有文件访问」权限，无法遍历手机文件");
                    } else {
                        scanStatus.setText("扫描完成：检查 " + out.scanned + " 个文件 · "
                                + FormatUtil.size(out.bytes) + " · 用时 " + (out.costMs / 1000) + " 秒"
                                + "\n命中 " + out.hits.size() + " 个可疑文件");
                    }
                    renderResults(out);
                });
            }
        });
    }

    private void renderResults(VirusScan.Out out) {
        hitBox.removeAllViews();
        hitHeader.setVisibility(View.VISIBLE);
        if (out == null) return;

        if (out.hits.isEmpty()) {
            LinearLayout c = card();
            TextView t = new TextView(this);
            t.setText(out.needPermission ? "没有执行扫描（缺权限）"
                    : "没有发现与病毒库匹配的文件 ✓");
            t.setTextSize(13);
            t.setTextColor(out.needPermission ? p.danger : p.accent);
            c.addView(t);
            TextView s = new TextView(this);
            s.setText("已检查 " + out.scanned + " 个可执行文件" + (out.dbEmpty ? "\n注意：病毒库为空，本次只生成了哈希，没有可比对的样本" : ""));
            s.setTextSize(11);
            s.setTextColor(p.muted);
            s.setPadding(0, dp(6), 0, 0);
            c.addView(s);
            hitBox.addView(c);
            return;
        }

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, dp(4), 0, dp(6));
        TextView n = new TextView(this);
        n.setText("命中 " + out.hits.size() + " 个可疑文件");
        n.setTextSize(14);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setTextColor(p.danger);
        bar.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView delAll = new TextView(this);
        delAll.setText("全部删除");
        delAll.setTextSize(13);
        delAll.setTextColor(p.accent);
        delAll.setPadding(dp(10), dp(6), dp(4), dp(6));
        delAll.setOnClickListener(v -> {
            List<ScanEngine.Item> all = new ArrayList<>();
            for (VirusScan.Hit h : out.hits) all.add(h.item);
            confirmDelete(all, () -> {
                out.hits.clear();
                renderResults(out);
            });
        });
        bar.addView(delAll);
        hitBox.addView(bar);

        for (final VirusScan.Hit h : out.hits) {
            LinearLayout c = card();
            c.setBackground(rounded(p.card, dp(16)));
            TextView t1 = new TextView(this);
            t1.setText("⚠ " + h.entry.label());
            t1.setTextSize(14);
            t1.setTypeface(Typeface.DEFAULT_BOLD);
            t1.setTextColor(p.danger);
            c.addView(t1);
            TextView t2 = new TextView(this);
            t2.setText(new File(h.path).getName() + " · " + FormatUtil.size(h.size));
            t2.setTextSize(12);
            t2.setTextColor(p.ink);
            t2.setPadding(0, dp(4), 0, 0);
            c.addView(t2);
            TextView t3 = new TextView(this);
            t3.setText(FormatUtil.shortPath(h.path, 52));
            t3.setTextSize(10);
            t3.setTextColor(p.faint);
            c.addView(t3);
            TextView t4 = new TextView(this);
            t4.setText("SHA-256 " + h.sha256.substring(0, 32) + "…"
                    + (h.entry.firstSeen.isEmpty() ? "" : " · 首次发现 " + h.entry.firstSeen));
            t4.setTextSize(10);
            t4.setTextColor(p.faint);
            t4.setPadding(0, dp(4), 0, 0);
            t4.setTextIsSelectable(true);
            c.addView(t4);
            LinearLayout btns = new LinearLayout(this);
            btns.setOrientation(LinearLayout.HORIZONTAL);
            btns.setPadding(0, dp(10), 0, 0);
            btns.addView(smallBtn("删除", p.danger, 0xFFFFFFFF, v -> {
                List<ScanEngine.Item> one = new ArrayList<>();
                one.add(h.item);
                confirmDelete(one, () -> {
                    out.hits.remove(h);
                    renderResults(out);
                });
            }), new LinearLayout.LayoutParams(0, -2, 1f));
            btns.addView(spacer(8));
            btns.addView(smallBtn("在线核对", p.chip, p.ink, v -> showLookupPages(h.sha256)),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            c.addView(btns);
            hitBox.addView(c);
        }
    }

    private void confirmDelete(final List<ScanEngine.Item> items, final Runnable onDone) {
        long size = 0;
        for (ScanEngine.Item i : items) size += i.size;
        File f0 = new File(items.get(0).path);
        new AlertDialog.Builder(this)
                .setTitle("删除可疑文件")
                .setMessage((items.size() == 1 ? f0.getName() : items.size() + " 个文件")
                        + "\n释放约 " + FormatUtil.size(size) + "。\n删除后无法恢复，确认？")
                .setPositiveButton("删除", (d, w) -> MainActivity.cleanList(this, items, onDone))
                .setNegativeButton("取消", null)
                .show();
    }

    // ===== 搜索 =====
    private void doSearch() {
        String q = searchInput.getText().toString().trim();
        if (q.isEmpty()) {
            Toast.makeText(this, "输入哈希或文件名", Toast.LENGTH_SHORT).show();
            return;
        }
        final String lower = q.toLowerCase(Locale.ROOT);
        if (lower.matches("[0-9a-f]{64}")) {
            VirusDb.Entry e = VirusDb.lookup(this, lower);
            if (e != null) {
                searchResult.setText("⚠ 本地病毒库命中：" + e.label()
                        + (e.firstSeen.isEmpty() ? "" : "\n首次发现 " + e.firstSeen)
                        + (e.fileType.isEmpty() ? "" : "\n类型 " + e.fileType)
                        + "\n" + lower);
                return;
            }
            searchResult.setText("本地病毒库没有这条记录，正在联网核对…");
            if (VirusDb.authKey(this).isEmpty()) {
                showLookupPages(lower);
                searchResult.setText("本地库没有这条记录；未配置 Auth-Key，无法在线查 MalwareBazaar。\n已在下面给出第三方查询入口。");
                return;
            }
            new Thread(() -> {
                String msg;
                try {
                    VirusDb.Entry online = VirusDb.queryOnline(VirusScanActivity.this, lower);
                    msg = online == null
                            ? "MalwareBazaar 没有这条样本记录（可能不是已知恶意文件）\n" + lower
                            : "⚠ MalwareBazaar 命中：" + online.label()
                            + (online.fileType.isEmpty() ? "" : "\n类型 " + online.fileType)
                            + (online.firstSeen.isEmpty() ? "" : "\n首次发现 " + online.firstSeen)
                            + (online.fileName.isEmpty() ? "" : "\n样本文件名 " + online.fileName)
                            + "\n" + lower;
                } catch (Throwable t) {
                    msg = "在线查询失败：" + (t.getMessage() == null ? "未知错误" : t.getMessage());
                }
                final String m = msg;
                runOnUiThread(() -> {
                    searchResult.setText(m);
                    refreshDbInfo();
                });
            }, "virus-query").start();
            return;
        }
        List<VirusDb.Entry> list = VirusDb.searchByFileName(this, q, 20);
        if (list.isEmpty()) {
            searchResult.setText("本地病毒库里没有文件名包含「" + q + "」的样本。\n（本地库只收哈希和样本名，不含文件内容）");
        } else {
            StringBuilder sb = new StringBuilder("本地病毒库匹配 " + list.size() + " 条：");
            for (VirusDb.Entry e : list) {
                sb.append("\n· ").append(e.label());
                if (!e.fileName.isEmpty()) sb.append(" — ").append(e.fileName);
                sb.append("\n  ").append(e.sha256, 0, 24).append("…");
            }
            searchResult.setText(sb.toString());
        }
    }

    private void showLookupPages(final String sha) {
        Map<String, String> pages = VirusDb.lookupPages(sha);
        final String[] names = pages.keySet().toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("在线核对这个哈希")
                .setItems(names, (d, w) -> {
                    String url = pages.get(names[w]);
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    } catch (Exception e) {
                        Toast.makeText(this, "打不开浏览器", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void pickFile() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_PICK_FILE);
        } catch (Exception e) {
            Toast.makeText(this, "打不开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FILE || resultCode != RESULT_OK || data == null) return;
        final Uri uri = data.getData();
        if (uri == null) return;
        searchResult.setText("正在计算文件哈希…");
        new Thread(() -> {
            String sha = null;
            try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in != null) sha = VirusScan.sha256(in);
            } catch (Throwable ignored) { }
            final String s = sha;
            runOnUiThread(() -> {
                if (s == null) {
                    searchResult.setText("读取失败，无法计算哈希");
                    return;
                }
                searchInput.setText(s);
                doSearch();
            });
        }, "virus-hash").start();
    }

    // ===== 小工具 =====
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(p.card, dp(16)));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView section(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(p.muted);
        t.setPadding(dp(4), dp(18), 0, dp(2));
        return t;
    }

    private TextView smallBtn(String text, int bgColor, int textColor, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(textColor);
        t.setBackground(rounded(bgColor, dp(12)));
        t.setPadding(dp(10), dp(11), dp(10), dp(11));
        t.setOnClickListener(v -> {
            UiCompat.playBounce(t);
            click.onClick(v);
        });
        return t;
    }

    private TextView bigBtn(String text, int bgColor, int textColor, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(textColor);
        t.setBackground(rounded(bgColor, dp(16)));
        t.setPadding(0, dp(14), 0, dp(14));
        t.setOnClickListener(v -> click.onClick(v));
        return t;
    }

    private View spacer(int dp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(dp), 1));
        return v;
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
