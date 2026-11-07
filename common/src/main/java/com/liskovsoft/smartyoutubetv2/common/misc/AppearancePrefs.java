package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.content.SharedPreferences;

import com.liskovsoft.smartyoutubetv2.common.misc.FontScaler.Category;

/**
 * Advanced appearance options: text color, font weight and font type per element category, plus the app background color.
 * Stored in its own SharedPreferences file (independent from the other app data formats).
 * Values are cached in memory because they are read after every layout pass.
 */
public final class AppearancePrefs {
    private static final String PREFS = "appearance_prefs";
    private static final int N = Category.values().length;
    private static int[] sColors;
    private static int[] sWeight;
    private static String[] sFont;
    private static int sBackground;
    private static int sAccent;
    private static boolean sLoaded;

    private AppearancePrefs() {
    }

    private static synchronized void load(Context context) {
        if (sLoaded) {
            return;
        }

        SharedPreferences p = prefs(context);
        sColors = new int[N];
        sWeight = new int[N];
        sFont = new String[N];
        for (Category c : Category.values()) {
            sColors[c.ordinal()] = p.getInt("color_" + c.name(), 0);
            // Old versions stored only a bold flag: keep it working as weight 700
            int legacy = p.getBoolean("bold_" + c.name(), false) ? AppearanceFonts.WEIGHT_BOLD : 0;
            sWeight[c.ordinal()] = p.getInt("weight_" + c.name(), legacy);
            sFont[c.ordinal()] = p.getString("font_" + c.name(), "");
        }
        sBackground = p.getInt("background", 0);
        sAccent = p.getInt("accent", 0);
        sLoaded = true;
    }

    /** 0 means "keep the original color" */
    public static int getTextColor(Context context, Category category) {
        load(context);
        return sColors[category.ordinal()];
    }

    public static void setTextColor(Context context, Category category, int color) {
        load(context);
        sColors[category.ordinal()] = color;
        prefs(context).edit().putInt("color_" + category.name(), color).apply();
    }

    /** True if the weight is semi-bold or heavier */
    public static boolean isBold(Context context, Category category) {
        return getWeight(context, category) >= 600;
    }

    /** Kept for the old on/off bold option: on = weight 700, off = back to the original weight */
    public static void setBold(Context context, Category category, boolean bold) {
        if (bold) {
            if (getWeight(context, category) < 600) {
                setWeight(context, category, AppearanceFonts.WEIGHT_BOLD);
            }
        } else if (getWeight(context, category) >= 600) {
            setWeight(context, category, AppearanceFonts.WEIGHT_ORIGINAL);
        }
    }

    /** 0 = original weight, 100..900 = font weight, 1000 = extra thick */
    public static int getWeight(Context context, Category category) {
        load(context);
        return sWeight[category.ordinal()];
    }

    public static void setWeight(Context context, Category category, int weight) {
        load(context);
        sWeight[category.ordinal()] = weight;
        prefs(context).edit().putInt("weight_" + category.name(), weight).remove("bold_" + category.name()).apply();
    }

    /** "" = original font (see AppearanceFonts for the key format) */
    public static String getFont(Context context, Category category) {
        load(context);
        return sFont[category.ordinal()];
    }

    public static void setFont(Context context, Category category, String fontKey) {
        load(context);
        sFont[category.ordinal()] = fontKey == null ? "" : fontKey;
        prefs(context).edit().putString("font_" + category.name(), sFont[category.ordinal()]).apply();
    }

    /** 0 means "keep the theme background" */
    public static int getBackground(Context context) {
        load(context);
        return sBackground;
    }

    public static void setBackground(Context context, int color) {
        load(context);
        sBackground = color;
        prefs(context).edit().putInt("background", color).apply();
    }

    /** Color of focused/selected/pressed text. 0 = keep the original focus colors */
    public static int getAccent(Context context) {
        load(context);
        return sAccent;
    }

    public static void setAccent(Context context, int color) {
        load(context);
        sAccent = color;
        prefs(context).edit().putInt("accent", color).apply();
    }

    /** Resets colors, weight, font type and background. Font sizes are reset by the caller (they live in MainUIData). */
    public static void resetAll(Context context) {
        load(context);
        java.util.Arrays.fill(sColors, 0);
        java.util.Arrays.fill(sWeight, 0);
        java.util.Arrays.fill(sFont, "");
        sBackground = 0;
        sAccent = 0;
        prefs(context).edit().clear().apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
