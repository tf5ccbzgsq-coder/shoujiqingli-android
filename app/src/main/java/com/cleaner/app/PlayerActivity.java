package com.cleaner.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 内置播放器：音乐 / 视频 / 图片，带上一首、下一首、进度拖动。
 *
 * 播放队列通过静态字段传递（同进程），避免文件太多时 Intent 传数组超限。
 * 媒体项优先用 content:// 地址（MediaStore），拿不到再退回文件路径 —— 两条路都兼容。
 */
public class PlayerActivity extends AppCompatActivity {

    /** 一个播放项：uri 与 path 至少有一个 */
    public static class PlayItem {
        public final String uri;
        public final String path;
        public final String name;

        public PlayItem(String uri, String path, String name) {
            this.uri = uri == null ? "" : uri;
            this.path = path == null ? "" : path;
            this.name = name == null ? "" : name;
        }

        public Uri resolve() {
            if (!uri.isEmpty()) return Uri.parse(uri);
            return Uri.fromFile(new File(path));
        }

        public int mode() {
            String s = (name.isEmpty() ? path : name).toLowerCase(Locale.ROOT);
            if (s.endsWith(".jpg") || s.endsWith(".jpeg") || s.endsWith(".png") || s.endsWith(".gif")
                    || s.endsWith(".webp") || s.endsWith(".bmp") || s.endsWith(".heic")
                    || s.endsWith(".heif") || s.endsWith(".dng") || s.endsWith(".raw")) return MODE_IMAGE;
            if (s.endsWith(".mp3") || s.endsWith(".wav") || s.endsWith(".flac") || s.endsWith(".aac")
                    || s.endsWith(".ogg") || s.endsWith(".m4a") || s.endsWith(".wma")
                    || s.endsWith(".opus") || s.endsWith(".amr") || s.endsWith(".ape")
                    || s.endsWith(".mid")) return MODE_AUDIO;
            return MODE_VIDEO;
        }

        public String display() {
            if (!name.isEmpty()) return name;
            if (!path.isEmpty()) {
                int i = path.lastIndexOf('/');
                return i < 0 ? path : path.substring(i + 1);
            }
            return "媒体文件";
        }
    }

    public static final int MODE_AUDIO = 0, MODE_VIDEO = 1, MODE_IMAGE = 2;
    private static final String EXTRA_INDEX = "index";

    private static List<PlayItem> queue = new ArrayList<>();

    /** 统一的启动入口 */
    public static void play(Context c, List<PlayItem> items, int index) {
        queue = items == null ? new ArrayList<>() : items;
        c.startActivity(new Intent(c, PlayerActivity.class)
                .addFlags(c instanceof android.app.Activity ? 0 : Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(EXTRA_INDEX, index));
    }

    private ThemeHelper.Palette p;
    private LinearLayout root;
    private FrameLayout stage;
    private VideoView video;
    private ImageView image;
    private TextView audioArt, playBtn, titleTv, counter, curTime, totalTime;
    private SeekBar seek;
    private MediaPlayer audio;

    private int index = 0;
    private int mode = MODE_AUDIO;
    private boolean prepared = false;
    private boolean dragging = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (mode == MODE_AUDIO && audio != null && prepared) {
                try {
                    int pos = audio.getCurrentPosition();
                    int dur = audio.getDuration();
                    if (dur > 0) {
                        seek.setMax(dur);
                        if (!dragging) seek.setProgress(pos);
                        curTime.setText(fmt(pos));
                        totalTime.setText(fmt(dur));
                    }
                } catch (Throwable ignored) { }
                ui.postDelayed(this, 500);
            }
        }
    };

    private final Runnable tickVideo = new Runnable() {
        @Override
        public void run() {
            if (mode == MODE_VIDEO && video != null && prepared) {
                try {
                    int pos = video.getCurrentPosition();
                    int dur = video.getDuration();
                    if (dur > 0) {
                        seek.setMax(dur);
                        if (!dragging) seek.setProgress(pos);
                        curTime.setText(fmt(pos));
                        totalTime.setText(fmt(dur));
                    }
                } catch (Throwable ignored) { }
                if (video.isPlaying()) ui.postDelayed(this, 500);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        p = ThemeHelper.get(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        index = getIntent() == null ? 0 : getIntent().getIntExtra(EXTRA_INDEX, 0);
        if (queue.isEmpty()) {
            Toast.makeText(this, "没有可播放的文件", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        if (index < 0 || index >= queue.size()) index = 0;

        buildUI();
        UiCompat.applyEdgeToEdge(this, root);
        UiCompat.applySystemBarIcons(this, true);
        load(index);
    }

    // ===== 界面 =====
    private void buildUI() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(38), dp(14), dp(6));
        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(28);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setTextColor(0xFFFFFFFF);
        back.setPadding(dp(12), 0, dp(12), 0);
        back.setOnClickListener(v -> finish());
        top.addView(back);
        titleTv = new TextView(this);
        titleTv.setTextSize(14);
        titleTv.setTextColor(0xFFFFFFFF);
        titleTv.setSingleLine(true);
        titleTv.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        top.addView(titleTv, new LinearLayout.LayoutParams(0, -2, 1f));
        counter = new TextView(this);
        counter.setTextSize(12);
        counter.setTextColor(0xFF9E9E9E);
        top.addView(counter);
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        stage = new FrameLayout(this);
        stage.setBackgroundColor(0xFF000000);
        video = new VideoView(this);
        video.setVisibility(View.GONE);
        stage.addView(video, new FrameLayout.LayoutParams(-1, -1));
        video.setOnPreparedListener(mp -> {
            prepared = true;
            mp.setLooping(false);
            video.start();
            updatePlayIcon(true);
            int dur = mp.getDuration();
            if (dur > 0) {
                seek.setMax(dur);
                totalTime.setText(fmt(dur));
            }
            ui.post(tickVideo);
        });
        video.setOnCompletionListener(mp -> next());
        video.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "这个视频播放器解不了（格式或编码不支持）", Toast.LENGTH_SHORT).show();
            return true;
        });

        image = new ImageView(this);
        image.setVisibility(View.GONE);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stage.addView(image, new FrameLayout.LayoutParams(-1, -1));
        image.setOnLongClickListener(v -> {
            next();
            return true;
        });

        audioArt = new TextView(this);
        audioArt.setVisibility(View.GONE);
        audioArt.setGravity(Gravity.CENTER);
        audioArt.setTextSize(64);
        audioArt.setTextColor(0xFF3DDC97);
        audioArt.setText("♪");
        stage.addView(audioArt, new FrameLayout.LayoutParams(-1, -1));
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));

        seek = new SeekBar(this);
        seek.setPadding(dp(16), dp(4), dp(16), dp(4));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) curTime.setText(fmt(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) { dragging = true; }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                dragging = false;
                if (mode == MODE_VIDEO && video != null) video.seekTo(sb.getProgress());
                else if (audio != null && prepared) {
                    try { audio.seekTo(sb.getProgress()); } catch (Throwable ignored) { }
                }
            }
        });
        root.addView(seek, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        times.setPadding(dp(20), 0, dp(20), dp(2));
        curTime = small("00:00");
        times.addView(curTime, new LinearLayout.LayoutParams(0, -2, 1f));
        totalTime = small("00:00");
        totalTime.setGravity(Gravity.END);
        times.addView(totalTime);
        root.addView(times, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setGravity(Gravity.CENTER);
        ctrl.setPadding(dp(16), dp(4), dp(16), dp(18));
        TextView prevBtn = ctrlBtn("⏮");
        prevBtn.setOnClickListener(v -> prev());
        ctrl.addView(prevBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        playBtn = ctrlBtn("▶");
        playBtn.setTextSize(30);
        playBtn.setOnClickListener(v -> togglePlay());
        ctrl.addView(playBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView nextBtn = ctrlBtn("⏭");
        nextBtn.setOnClickListener(v -> next());
        ctrl.addView(nextBtn, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(ctrl, new LinearLayout.LayoutParams(-1, -2));
    }

    private TextView small(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTextColor(0xFF9E9E9E);
        return t;
    }

    private TextView ctrlBtn(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(22);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(0xFFFFFFFF);
        t.setPadding(0, dp(10), 0, dp(10));
        return t;
    }

    // ===== 播放 =====
    private void load(int i) {
        index = i;
        stopAll();
        final PlayItem item = queue.get(index);
        mode = item.mode();
        titleTv.setText(item.display());
        counter.setText((index + 1) + "/" + queue.size());
        seek.setProgress(0);
        curTime.setText("00:00");
        totalTime.setText("00:00");
        seek.setEnabled(mode != MODE_IMAGE);

        video.setVisibility(mode == MODE_VIDEO ? View.VISIBLE : View.GONE);
        image.setVisibility(mode == MODE_IMAGE ? View.VISIBLE : View.GONE);
        audioArt.setVisibility(mode == MODE_AUDIO ? View.VISIBLE : View.GONE);

        if (mode == MODE_VIDEO) {
            try {
                video.setVideoURI(item.resolve());
                video.requestFocus();
            } catch (Throwable t) {
                Toast.makeText(this, "视频打开失败：" + shortMsg(t), Toast.LENGTH_SHORT).show();
            }
        } else if (mode == MODE_IMAGE) {
            showImage(item);
            updatePlayIcon(false);
        } else {
            try {
                audio = new MediaPlayer();
                audio.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
                audio.setDataSource(this, item.resolve());
                audio.setOnPreparedListener(mp -> {
                    prepared = true;
                    mp.start();
                    updatePlayIcon(true);
                    int dur = mp.getDuration();
                    if (dur > 0) {
                        seek.setMax(dur);
                        totalTime.setText(fmt(dur));
                    }
                    ui.post(tick);
                });
                audio.setOnCompletionListener(mp -> next());
                audio.setOnErrorListener((mp, what, extra) -> {
                    Toast.makeText(this, "这个音频解不了（格式或编码不支持）", Toast.LENGTH_SHORT).show();
                    return true;
                });
                audio.prepareAsync();
            } catch (Throwable t) {
                Toast.makeText(this, "音频打开失败：" + shortMsg(t), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showImage(PlayItem item) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            int needW = Math.max(1, dm.widthPixels);
            int needH = Math.max(1, dm.heightPixels);

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in1 = open(item);
            if (in1 == null) throw new IllegalStateException("无法读取");
            try {
                BitmapFactory.decodeStream(in1, null, bounds);
            } finally {
                close(in1);
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= needW && bounds.outHeight / (sample * 2) >= needH) {
                sample *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = Math.max(1, sample);
            InputStream in2 = open(item);
            if (in2 == null) throw new IllegalStateException("无法读取");
            Bitmap bm;
            try {
                bm = BitmapFactory.decodeStream(in2, null, o2);
            } finally {
                close(in2);
            }
            if (bm == null) throw new IllegalStateException("解码失败");
            image.setImageBitmap(bm);
        } catch (Throwable t) {
            Toast.makeText(this, "图片打开失败：" + shortMsg(t), Toast.LENGTH_SHORT).show();
        }
    }

    private InputStream open(PlayItem item) {
        try {
            if (!item.path.isEmpty()) {
                File f = new File(item.path);
                if (f.exists() && f.canRead()) return new java.io.FileInputStream(f);
            }
            if (!item.uri.isEmpty()) {
                return getContentResolver().openInputStream(Uri.parse(item.uri));
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static void close(InputStream in) {
        try {
            if (in != null) in.close();
        } catch (Throwable ignored) { }
    }

    private void togglePlay() {
        if (mode == MODE_VIDEO) {
            if (video.isPlaying()) {
                video.pause();
                updatePlayIcon(false);
            } else {
                video.start();
                updatePlayIcon(true);
                ui.post(tickVideo);
            }
        } else if (mode == MODE_AUDIO) {
            try {
                if (audio != null && prepared) {
                    if (audio.isPlaying()) {
                        audio.pause();
                        updatePlayIcon(false);
                    } else {
                        audio.start();
                        updatePlayIcon(true);
                        ui.post(tick);
                    }
                }
            } catch (Throwable ignored) { }
        }
    }

    private void updatePlayIcon(boolean playing) {
        if (playBtn != null) playBtn.setText(playing ? "⏸" : "▶");
    }

    private void prev() {
        if (queue.size() <= 1) {
            load(index);
            return;
        }
        load(index == 0 ? queue.size() - 1 : index - 1);
    }

    private void next() {
        if (queue.size() <= 1) {
            load(index);
            return;
        }
        load(index + 1 >= queue.size() ? 0 : index + 1);
    }

    private void stopAll() {
        prepared = false;
        ui.removeCallbacks(tick);
        ui.removeCallbacks(tickVideo);
        if (video != null) {
            try {
                video.stopPlayback();
            } catch (Throwable ignored) { }
        }
        if (audio != null) {
            try {
                audio.release();
            } catch (Throwable ignored) { }
            audio = null;
        }
        if (image != null) image.setImageDrawable(null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mode == MODE_VIDEO && video != null && video.isPlaying()) video.pause();
        else if (mode == MODE_AUDIO && audio != null && prepared) {
            try {
                if (audio.isPlaying()) audio.pause();
            } catch (Throwable ignored) { }
        }
        updatePlayIcon(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAll();
    }

    private static String fmt(int ms) {
        if (ms < 0) ms = 0;
        int total = ms / 1000;
        int m = total / 60, s = total % 60;
        if (m >= 60) {
            int h = m / 60;
            m = m % 60;
            return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.ROOT, "%02d:%02d", m, s);
    }

    private static String shortMsg(Throwable t) {
        String m = t.getMessage();
        if (m == null) m = t.getClass().getSimpleName();
        return m.length() > 70 ? m.substring(0, 70) : m;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
