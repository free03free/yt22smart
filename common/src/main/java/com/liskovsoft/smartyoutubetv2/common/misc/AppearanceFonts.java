package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;

import com.liskovsoft.smartyoutubetv2.common.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Font types and font weights for the appearance options.
 *
 * Font key (stored in prefs): "" = keep the original font, "sys:NAME" = Android system family,
 * "asset:FILE" = a .ttf/.otf from the app's assets/fonts folder, "file:FILE" = a font from the app's
 * external files folder (getExternalFilesDir("fonts")), which needs no storage permission.
 *
 * Weight: 0 = original, 100..900 = CSS-like weight, 1000 = "extra thick" (black + synthetic stroke).
 */
public final class AppearanceFonts {
    public static final int WEIGHT_ORIGINAL = 0;
    public static final int WEIGHT_BOLD = 700;
    public static final int WEIGHT_HEAVY = 1000;

    public static final int[] WEIGHT_VALUES = {0, 300, 400, 500, 600, 700, 800, 900, 1000};
    public static final int[] WEIGHT_NAMES = {R.string.ap_w_default, R.string.ap_w_light, R.string.ap_w_regular,
            R.string.ap_w_medium, R.string.ap_w_semibold, R.string.ap_w_bold, R.string.ap_w_extrabold,
            R.string.ap_w_black, R.string.ap_w_heavy};

    private static final String[] SYSTEM_KEYS = {"sans-serif", "sans-serif-condensed", "serif", "monospace",
            "serif-monospace", "casual", "cursive", "sans-serif-smallcaps"};
    private static final int[] SYSTEM_NAMES = {R.string.ap_f_sans, R.string.ap_f_condensed, R.string.ap_f_serif,
            R.string.ap_f_mono, R.string.ap_f_serif_mono, R.string.ap_f_casual, R.string.ap_f_cursive,
            R.string.ap_f_smallcaps};

    private static final Map<String, Typeface> sCache = new HashMap<>();

    private AppearanceFonts() {
    }

    /** Ready to show list of fonts: keys and their display names (system fonts first, then font files) */
    public static final class FontList {
        public final List<String> keys = new ArrayList<>();
        public final List<String> names = new ArrayList<>();
    }

    public static FontList list(Context context) {
        FontList result = new FontList();
        result.keys.add("");
        result.names.add(context.getString(R.string.ap_f_default));

        for (int i = 0; i < SYSTEM_KEYS.length; i++) {
            result.keys.add("sys:" + SYSTEM_KEYS[i]);
            result.names.add(context.getString(SYSTEM_NAMES[i]));
        }

        try {
            String[] assets = context.getAssets().list("fonts");
            if (assets != null) {
                Arrays.sort(assets);
                for (String name : assets) {
                    if (isFontFile(name)) {
                        result.keys.add("asset:" + name);
                        result.names.add(context.getString(R.string.ap_f_custom, stripExt(name)));
                    }
                }
            }
        } catch (Exception e) {
            // no assets/fonts folder
        }

        File dir = context.getExternalFilesDir("fonts");
        File[] files = dir != null ? dir.listFiles() : null;
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                if (file.isFile() && isFontFile(file.getName())) {
                    result.keys.add("file:" + file.getName());
                    result.names.add(context.getString(R.string.ap_f_custom, stripExt(file.getName())));
                }
            }
        }

        return result;
    }

    /**
     * @param original the typeface the view had before we touched it (may be null)
     * @param fontKey  see class comment ("" keeps the original family)
     * @param weight   see class comment (0 keeps the original weight)
     */
    public static synchronized Typeface resolve(Context context, Typeface original, String fontKey, int weight) {
        String key = fontKey + "|" + weight + "|" + (fontKey.isEmpty() ? System.identityHashCode(original) : 0);
        Typeface cached = sCache.get(key);
        if (cached != null) {
            return cached;
        }

        Typeface base = fontKey.isEmpty() ? (original != null ? original : Typeface.DEFAULT) : loadFamily(context, fontKey, original);
        Typeface result = weight == WEIGHT_ORIGINAL ? base : withWeight(base, fontKey, Math.min(weight, 900));

        sCache.put(key, result);
        return result;
    }

    private static Typeface withWeight(Typeface base, String fontKey, int weight) {
        if (Build.VERSION.SDK_INT >= 28) {
            return Typeface.create(base, weight, false);
        }

        // Older Android: only the named sans-serif variants have more than regular/bold
        boolean sans = fontKey.isEmpty() || fontKey.startsWith("sys:sans-serif");
        if (sans && !fontKey.contains("condensed") && !fontKey.contains("smallcaps")) {
            if (weight <= 300) {
                return Typeface.create("sans-serif-light", Typeface.NORMAL);
            } else if (weight >= 800) {
                return Typeface.create("sans-serif-black", Typeface.NORMAL);
            } else if (weight >= 500 && weight < 600) {
                return Typeface.create("sans-serif-medium", Typeface.NORMAL);
            }
        }

        return Typeface.create(base, weight >= 600 ? Typeface.BOLD : Typeface.NORMAL);
    }

    private static Typeface loadFamily(Context context, String fontKey, Typeface fallback) {
        try {
            if (fontKey.startsWith("sys:")) {
                return Typeface.create(fontKey.substring(4), Typeface.NORMAL);
            } else if (fontKey.startsWith("asset:")) {
                return Typeface.createFromAsset(context.getAssets(), "fonts/" + fontKey.substring(6));
            } else if (fontKey.startsWith("file:")) {
                File dir = context.getExternalFilesDir("fonts");
                if (dir != null) {
                    return Typeface.createFromFile(new File(dir, fontKey.substring(5)));
                }
            }
        } catch (Exception e) {
            // missing or broken font file: fall back to the original font
        }

        return fallback != null ? fallback : Typeface.DEFAULT;
    }

    private static boolean isFontFile(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".ttf") || n.endsWith(".otf");
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
