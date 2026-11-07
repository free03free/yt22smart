package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.misc.FontScaler.Category;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;

/**
 * Live preview strip for the appearance screens.
 *
 * The strip is a child of the settings dialog's own window (not a separate Dialog), so it always sits on top of
 * the settings, can never stay on screen after the settings are closed, and is redrawn after every option change.
 */
public final class AppearancePreview {
    private static final String TAG = "appearance_preview_bar";
    private static final int MAX_ATTEMPTS = 20;
    private static final int MAX_SAMPLE_SP = 34;

    /** Elements shown when a group of elements is edited (screen space is limited) */
    private static final Category[] REPRESENTATIVE = {Category.CARD_TITLE, Category.ROW_HEADER, Category.BROWSE_TITLE,
            Category.DIALOG_TITLE, Category.CARD_INFO, Category.MENU_ITEM, Category.BUTTONS, Category.PLAYER_TIME};

    /** Base text size (sp) of every category, in the order of the Category enum */
    private static final int[] BASE_SP = {16, 13, 20, 16, 13, 20, 22, 14, 14, 12, 15, 16, 13};

    private AppearancePreview() {
    }

    /** Samples of the given elements, styled with the current size, weight, font and color options */
    public static void showElements(Activity activity, Category[] targets) {
        render(activity, targets, 0);
    }

    /** Sample of the background and highlight colors */
    public static void showColors(Activity activity) {
        render(activity, null, 0);
    }

    private static void render(Activity activity, Category[] targets, int attempt) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }

        // The dialog's fragment view may not be attached yet
        if (content.getChildCount() == 0) {
            if (attempt < MAX_ATTEMPTS) {
                content.postDelayed(() -> render(activity, targets, attempt + 1), 50);
            }
            return;
        }

        LinearLayout bar = content.findViewWithTag(TAG);
        if (bar == null) {
            bar = createBar(activity, content);
        }

        bar.removeAllViews();
        int bg = AppearancePrefs.getBackground(activity);
        ((GradientDrawable) bar.getBackground()).setColor(bg != 0 ? bg : 0xFF1E1E1E);

        addCaption(bar);

        if (targets == null) {
            addColorSamples(bar);
        } else {
            addElementSamples(bar, targets);
        }
    }

    private static LinearLayout createBar(Activity activity, ViewGroup content) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setTag(TAG);
        bar.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 12);
        bar.setPadding(pad, dp(activity, 8), pad, pad);
        bar.setElevation(dp(activity, 12));
        bar.setClickable(false);
        bar.setFocusable(false);

        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(activity, 12));
        shape.setStroke(dp(activity, 1), 0x66FFFFFF);
        bar.setBackground(shape);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int margin = dp(activity, 8);
        lp.setMargins(margin, margin, margin, margin);
        content.addView(bar, lp);

        // Keep the settings list clear of the strip, so no option is ever hidden behind it
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> reserveSpace(content, v));

        return bar;
    }

    private static void reserveSpace(ViewGroup content, View bar) {
        int height = bar.getHeight() + dp(content.getContext(), 16);
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child != bar && child.getPaddingBottom() != height) {
                child.setPadding(child.getPaddingLeft(), child.getPaddingTop(), child.getPaddingRight(), height);
            }
        }
    }

    private static void addCaption(LinearLayout bar) {
        TextView caption = new TextView(bar.getContext());
        caption.setText(R.string.ap_prev_caption);
        caption.setTextColor(0xFF9E9E9E);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        bar.addView(caption);
    }

    private static void addElementSamples(LinearLayout bar, Category[] targets) {
        Category[] shown = targets.length <= 2 ? targets : pickRepresentative(targets);

        LinearLayout row = null;
        for (int i = 0; i < shown.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(bar.getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                bar.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            row.addView(sample(bar, shown[i]), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    private static Category[] pickRepresentative(Category[] targets) {
        java.util.List<Category> result = new java.util.ArrayList<>();
        for (Category c : REPRESENTATIVE) {
            for (Category t : targets) {
                if (t == c) {
                    result.add(c);
                    break;
                }
            }
        }
        // Nothing representative in the group: show the first elements of the group
        if (result.isEmpty()) {
            for (int i = 0; i < Math.min(4, targets.length); i++) {
                result.add(targets[i]);
            }
        }
        return result.toArray(new Category[0]);
    }

    private static TextView sample(LinearLayout bar, Category category) {
        TextView tv = new TextView(bar.getContext());
        tv.setText(category.titleResId);
        tv.setTextColor(Color.WHITE);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(0, dp(bar.getContext(), 4), dp(bar.getContext(), 8), 0);

        float scale = MainUIData.instance(bar.getContext()).getFontScale(category);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.min(BASE_SP[category.ordinal()] * scale, MAX_SAMPLE_SP));

        // Same code path as the real screens: color, weight and font type
        FontScaler.applyColorAndStyle(tv, category);
        return tv;
    }

    private static void addColorSamples(LinearLayout bar) {
        TextView normal = new TextView(bar.getContext());
        normal.setText(R.string.ap_prev_normal);
        normal.setTextColor(Color.WHITE);
        normal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        bar.addView(normal);

        int accent = AppearancePrefs.getAccent(bar.getContext());
        TextView focused = new TextView(bar.getContext());
        focused.setText(R.string.ap_prev_focused);
        focused.setTextColor(accent != 0 ? accent : Color.WHITE);
        focused.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        focused.setPadding(0, dp(bar.getContext(), 4), 0, 0);
        bar.addView(focused);
    }

    private static int dp(android.content.Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }
}
