package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.misc.FontScaler.Category;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;

/**
 * Whole appearance (sizes, colors, bold, background, accent) as one text line.
 * Used for the saved slots and for export / import through the clipboard.
 * Format: AP1|global|sizes|colors|bold|background|accent|weights|fonts  (lists are comma separated;
 * the last two fields are optional so styles saved by older versions still load)
 */
public final class AppearanceStyle {
    private static final String HEADER = "AP1";

    private AppearanceStyle() {
    }

    public static String serialize(Context context) {
        MainUIData ui = MainUIData.instance(context);
        Category[] cats = Category.values();
        StringBuilder sizes = new StringBuilder();
        StringBuilder colors = new StringBuilder();
        StringBuilder bold = new StringBuilder();
        StringBuilder weights = new StringBuilder();
        StringBuilder fonts = new StringBuilder();

        for (int i = 0; i < cats.length; i++) {
            String sep = i == 0 ? "" : ",";
            sizes.append(sep).append(ui.getFontScale(cats[i]));
            colors.append(sep).append(AppearancePrefs.getTextColor(context, cats[i]));
            bold.append(sep).append(AppearancePrefs.isBold(context, cats[i]) ? 1 : 0);
            weights.append(sep).append(AppearancePrefs.getWeight(context, cats[i]));
            fonts.append(sep).append(android.net.Uri.encode(AppearancePrefs.getFont(context, cats[i])));
        }

        return HEADER + "|" + ui.getFontScale() + "|" + sizes + "|" + colors + "|" + bold + "|"
                + AppearancePrefs.getBackground(context) + "|" + AppearancePrefs.getAccent(context)
                + "|" + weights + "|" + fonts;
    }

    /** @return false if the text is not a valid style (nothing is changed in that case) */
    public static boolean apply(Context context, String text) {
        if (text == null) {
            return false;
        }

        try {
            String[] p = text.trim().split("\\|", -1);
            if (p.length < 7 || !HEADER.equals(p[0])) {
                return false;
            }

            Category[] cats = Category.values();
            String[] sizes = p[2].split(",", -1);
            String[] colors = p[3].split(",", -1);
            String[] bold = p[4].split(",", -1);

            // Parse everything first, then apply: a broken text must not leave a half applied style
            float global = Float.parseFloat(p[1]);
            float[] s = new float[cats.length];
            int[] c = new int[cats.length];
            boolean[] b = new boolean[cats.length];
            int[] w = new int[cats.length];
            String[] f = new String[cats.length];
            String[] weights = p.length > 7 ? p[7].split(",", -1) : new String[0];
            String[] fonts = p.length > 8 ? p[8].split(",", -1) : new String[0];
            for (int i = 0; i < cats.length; i++) {
                s[i] = i < sizes.length ? Float.parseFloat(sizes[i]) : 1.0f;
                c[i] = i < colors.length ? Integer.parseInt(colors[i].trim()) : 0;
                b[i] = i < bold.length && "1".equals(bold[i].trim());
                // Old styles have only the bold flag: it becomes weight 700
                w[i] = i < weights.length ? Integer.parseInt(weights[i].trim()) : (b[i] ? AppearanceFonts.WEIGHT_BOLD : 0);
                f[i] = i < fonts.length ? android.net.Uri.decode(fonts[i].trim()) : "";
            }
            int bg = Integer.parseInt(p[5].trim());
            int accent = Integer.parseInt(p[6].trim());

            MainUIData ui = MainUIData.instance(context);
            ui.setFontScale(global);
            for (int i = 0; i < cats.length; i++) {
                ui.setFontScale(cats[i], s[i]);
                AppearancePrefs.setTextColor(context, cats[i], c[i]);
                AppearancePrefs.setWeight(context, cats[i], w[i]);
                AppearancePrefs.setFont(context, cats[i], f[i]);
            }
            AppearancePrefs.setBackground(context, bg);
            AppearancePrefs.setAccent(context, accent);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // Saved slots

    public static final int SLOTS = 3;

    public static String getSlot(Context context, int slot) {
        return context.getApplicationContext().getSharedPreferences("appearance_slots", Context.MODE_PRIVATE).getString("slot" + slot, null);
    }

    public static void saveSlot(Context context, int slot) {
        context.getApplicationContext().getSharedPreferences("appearance_slots", Context.MODE_PRIVATE).edit()
                .putString("slot" + slot, serialize(context))
                .putLong("time" + slot, System.currentTimeMillis())
                .apply();
    }

    public static long getSlotTime(Context context, int slot) {
        return context.getApplicationContext().getSharedPreferences("appearance_slots", Context.MODE_PRIVATE).getLong("time" + slot, 0);
    }
}
