package com.cleaner.app;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

/**
 * 版本分流层：把「高版本专属」的 UI 能力集中在这里。
 * - Android 11（API 30）：扁平样式
 * - Android 12+（Material You）：圆角动效、弹性反馈、边到边
 * 注意：targetSdk 35 起 Android 15 会强制边到边，所以 insets 必须处理，否则内容被状态栏压住。
 */
public final class UiCompat {

    private UiCompat() { }

    /** 是否走「现代」分支：Android 12+ */
    public static boolean isModern() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    public static int cardRadiusDp() {
        return isModern() ? 22 : 16;
    }

    /** 让根视图铺满整屏，并用 insets 补回内边距；同时接管键盘 insets */
    public static void applyEdgeToEdge(final Activity a, final View root) {
        final Window w = a.getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.setDecorFitsSystemWindows(false);

        final int padLeft = root.getPaddingLeft();
        final int padTop = root.getPaddingTop();
        final int padRight = root.getPaddingRight();
        final int padBottom = root.getPaddingBottom();

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
            v.setPadding(padLeft, padTop + bars.top, padRight,
                    padBottom + Math.max(bars.bottom, ime.bottom));
            return insets;
        });
        root.requestApplyInsets();
    }

    /** 状态栏/导航栏图标反色：深色主题用浅色图标 */
    public static void applySystemBarIcons(Activity a, boolean darkTheme) {
        WindowInsetsController c = a.getWindow().getInsetsController();
        if (c == null) return;
        int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
        if (darkTheme) {
            c.setSystemBarsAppearance(0, mask);
        } else {
            c.setSystemBarsAppearance(mask, mask);
        }
    }

    /** 点击回弹（Android 12+） */
    public static void playBounce(final View v) {
        if (!isModern() || v == null) return;
        v.animate().cancel();
        v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).withEndAction(() ->
                v.animate().scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(new OvershootInterpolator(2.2f)).start()
        ).start();
    }

    /** 内容淡入 */
    public static void playFadeIn(final View v) {
        if (!isModern() || v == null) return;
        v.animate().cancel();
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(200)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    public static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
