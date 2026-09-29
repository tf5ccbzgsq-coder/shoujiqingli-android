package com.cleaner.app;

import android.content.Context;

/**
 * 调色板：Android 12+ 跟随壁纸动态取色（Material You），否则用固定的青绿配色。
 * 深色/浅色都由 Store.isDarkNow 决定。
 */
public class ThemeHelper {

    public static class Palette {
        public int bg, card, ink, muted, faint, accent, accentText, border, chip;
        /** 警示色：清理/删除相关（红） */
        public int danger;
    }

    public static Palette get(Context c) {
        boolean dark = Store.isDarkNow(c);
        if (UiCompat.isModern() && Store.isDynamicColor(c)) return dynamic(c, dark);
        return fixed(dark);
    }

    private static Palette fixed(boolean dark) {
        Palette p = new Palette();
        if (dark) {
            p.bg = 0xFF101413;
            p.card = 0xFF1A201E;
            p.ink = 0xFFF1F4F3;
            p.muted = 0xFF8B9793;
            p.faint = 0xFF55605C;
            p.accent = 0xFF3ED0A6;
            p.accentText = 0xFF06231C;
            p.border = 0xFF2A312F;
            p.chip = 0xFF232A28;
            p.danger = 0xFFFF6B6B;
        } else {
            p.bg = 0xFFF3F5F4;
            p.card = 0xFFFFFFFF;
            p.ink = 0xFF16201D;
            p.muted = 0xFF7C8A86;
            p.faint = 0xFFAFBAB7;
            p.accent = 0xFF12A37F;
            p.accentText = 0xFFFFFFFF;
            p.border = 0xFFE3E9E7;
            p.chip = 0xFFEFF4F2;
            p.danger = 0xFFE2483B;
        }
        return p;
    }

    // ===== Material You 动态取色（Android 12+）=====

    private static final int[] NEUTRAL = {
            android.R.color.system_neutral1_0, android.R.color.system_neutral1_10,
            android.R.color.system_neutral1_50, android.R.color.system_neutral1_100,
            android.R.color.system_neutral1_200, android.R.color.system_neutral1_300,
            android.R.color.system_neutral1_400, android.R.color.system_neutral1_500,
            android.R.color.system_neutral1_600, android.R.color.system_neutral1_700,
            android.R.color.system_neutral1_800, android.R.color.system_neutral1_900,
            android.R.color.system_neutral1_1000};

    private static final int[] ACCENT = {
            android.R.color.system_accent1_0, android.R.color.system_accent1_10,
            android.R.color.system_accent1_50, android.R.color.system_accent1_100,
            android.R.color.system_accent1_200, android.R.color.system_accent1_300,
            android.R.color.system_accent1_400, android.R.color.system_accent1_500,
            android.R.color.system_accent1_600, android.R.color.system_accent1_700,
            android.R.color.system_accent1_800, android.R.color.system_accent1_900,
            android.R.color.system_accent1_1000};

    /** 按目标亮度从系统色带里挑最接近的一级，避免不同 ROM 对色阶定义不一致 */
    private static int pick(Context c, int[] ids, double targetLum) {
        int best = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int id : ids) {
            int col;
            try {
                col = c.getColor(id);
            } catch (Throwable t) {
                continue;
            }
            double d = Math.abs(luminance(col) - targetLum);
            if (d < bestDiff) {
                bestDiff = d;
                best = col;
            }
        }
        return best;
    }

    private static double luminance(int color) {
        return androidx.core.graphics.ColorUtils.calculateLuminance(color);
    }

    private static Palette dynamic(Context c, boolean dark) {
        Palette p = new Palette();
        if (dark) {
            p.bg = pick(c, NEUTRAL, 0.012);
            p.card = pick(c, NEUTRAL, 0.048);
            p.chip = pick(c, NEUTRAL, 0.085);
            p.border = pick(c, NEUTRAL, 0.115);
            p.faint = pick(c, NEUTRAL, 0.27);
            p.muted = pick(c, NEUTRAL, 0.48);
            p.ink = pick(c, NEUTRAL, 0.90);
            p.accent = pick(c, ACCENT, 0.62);
            p.danger = 0xFFFF6B6B;
        } else {
            p.bg = pick(c, NEUTRAL, 0.855);
            p.card = pick(c, NEUTRAL, 0.985);
            p.chip = pick(c, NEUTRAL, 0.94);
            p.border = pick(c, NEUTRAL, 0.80);
            p.faint = pick(c, NEUTRAL, 0.55);
            p.muted = pick(c, NEUTRAL, 0.34);
            p.ink = pick(c, NEUTRAL, 0.05);
            p.accent = pick(c, ACCENT, 0.28);
            p.danger = 0xFFD93025;
        }
        p.accentText = luminance(p.accent) > 0.45 ? 0xFF1A1A1A : 0xFFFFFFFF;
        return p;
    }
}
