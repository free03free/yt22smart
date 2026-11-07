package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.misc.FontScaler.Category;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;

import java.util.ArrayList;
import java.util.List;

/**
 * Live preview for the appearance screens: a strip and a full-screen sample.
 *
 * Both are children of the settings dialog's own window (not separate Dialogs), so they always sit on top of the
 * settings, can never stay on screen after the settings are closed, and are redrawn after every option change.
 *
 * The strip is configured in the "Preview window" screen: shown/hidden, position (bottom, top, auto), size
 * (compact, normal, large), text alignment, sample text (element name, sample sentence, custom text),
 * comparison with the original style and opacity.
 */
public final class AppearancePreview {
    private static final String TAG = "appearance_preview_bar";
    private static final String TAG_FULL = "appearance_preview_full";
    private static final int MAX_ATTEMPTS = 20;
    private static final int FULL_AUTO_CLOSE_MS = 20_000;

    /** Elements shown in the strip when a group of elements is edited (screen space is limited) */
    private static final Category[] REPRESENTATIVE = {Category.CARD_TITLE, Category.ROW_HEADER, Category.BROWSE_TITLE,
            Category.DIALOG_TITLE, Category.CARD_INFO, Category.MENU_ITEM, Category.BUTTONS, Category.PLAYER_TIME};

    /** Base text size (sp) of every category, in the order of the Category enum */
    private static final int[] BASE_SP = {16, 13, 20, 16, 13, 20, 22, 14, 14, 12, 15, 16, 13};

    // Strip size presets: compact, normal, large
    private static final int[] MAX_SAMPLES = {2, 6, 4};
    private static final int[] COLUMNS = {2, 2, 1};
    private static final int[] MAX_SAMPLE_SP = {24, 34, 48};

    private AppearancePreview() {
    }

    // ------------------------------------------------------------------ strip

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

        if (option(activity, AppearancePrefs.PREVIEW_ENABLED) == 0) {
            if (bar != null) {
                content.removeView(bar);
                reserveSpace(content, null);
            }
            return;
        }

        if (bar == null) {
            bar = createBar(activity, content);
        }

        bar.removeAllViews();

        // Position (bottom, top or auto) and opacity: the strip can be see-through, so the options behind stay readable
        applyPosition(activity, content, bar);
        int alpha = option(activity, AppearancePrefs.PREVIEW_OPACITY) * 255 / 100;
        int bg = AppearancePrefs.getBackground(activity);
        ((GradientDrawable) bar.getBackground()).setColor(((bg != 0 ? bg : 0xFF1E1E1E) & 0xFFFFFF) | (alpha << 24));

        int size = clampSize(option(activity, AppearancePrefs.PREVIEW_SIZE));
        boolean compare = option(activity, AppearancePrefs.PREVIEW_COMPARE) == 1;

        if (size != 0) {
            addCaption(bar, compare);
        }

        if (targets == null) {
            addColorSamples(bar);
        } else {
            addElementSamples(bar, targets, size, compare);
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
        bar.post(() -> reserveSpace(content, content.findViewWithTag(TAG)));

        // "Auto" position: move the strip away from the option the user is looking at
        content.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> {
            LinearLayout current = content.findViewWithTag(TAG);
            if (current != null && option(activity, AppearancePrefs.PREVIEW_POSITION) == 2) {
                applyPosition(activity, content, current);
            }
        });

        return bar;
    }

    private static void applyPosition(Activity activity, ViewGroup content, LinearLayout bar) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bar.getLayoutParams();
        boolean top = shouldBeOnTop(activity, content, lp);
        int gravity = (top ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;

        if (lp.gravity != gravity) {
            lp.gravity = gravity;
            bar.setLayoutParams(lp);
        }
    }

    private static boolean shouldBeOnTop(Activity activity, ViewGroup content, FrameLayout.LayoutParams lp) {
        int mode = option(activity, AppearancePrefs.PREVIEW_POSITION);
        if (mode != 2) {
            return mode == 1;
        }

        boolean onTop = (lp.gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP;
        View focus = activity.getCurrentFocus();
        Rect rect = new Rect();
        if (focus == null || !focus.getGlobalVisibleRect(rect) || content.getHeight() == 0) {
            return onTop;
        }

        int[] location = new int[2];
        content.getLocationOnScreen(location);
        float center = (rect.centerY() - location[1]) / (float) content.getHeight();

        // A dead zone in the middle keeps the strip from jumping back and forth
        if (center > 0.6f) {
            return true;
        } else if (center < 0.4f) {
            return false;
        }
        return onTop;
    }

    /** Leaves room for the strip (or gives the room back when bar is null) on the side where the strip sits */
    private static void reserveSpace(ViewGroup content, View bar) {
        Context context = content.getContext();
        boolean top = bar != null && (((FrameLayout.LayoutParams) bar.getLayoutParams()).gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.TOP;
        int height = bar != null ? bar.getHeight() + dp(context, 16) : 0;

        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            if (child == bar || TAG.equals(child.getTag()) || TAG_FULL.equals(child.getTag())) {
                continue;
            }

            // Remember the original paddings once
            int[] original = (int[]) child.getTag(R.id.appearance_preview_padding);
            if (original == null) {
                original = new int[] {child.getPaddingTop(), child.getPaddingBottom()};
                child.setTag(R.id.appearance_preview_padding, original);
            }

            int paddingTop = original[0] + (top ? height : 0);
            int paddingBottom = original[1] + (top ? 0 : height);
            if (child.getPaddingTop() != paddingTop || child.getPaddingBottom() != paddingBottom) {
                child.setPadding(child.getPaddingLeft(), paddingTop, child.getPaddingRight(), paddingBottom);
            }
        }
    }

    private static void addCaption(LinearLayout bar, boolean compare) {
        TextView caption = new TextView(bar.getContext());
        caption.setText(compare ? R.string.ap_prev_compare_caption : R.string.ap_prev_caption);
        caption.setTextColor(0xFF9E9E9E);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        caption.setGravity(horizontalGravity(bar.getContext()));
        bar.addView(caption, matchWrap());
    }

    private static void addElementSamples(LinearLayout bar, Category[] targets, int size, boolean compare) {
        int limit = compare ? Math.min(MAX_SAMPLES[size], 4) : MAX_SAMPLES[size];
        Category[] shown = pick(targets, limit);
        int columns = COLUMNS[size];

        LinearLayout row = null;
        for (int i = 0; i < shown.length; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(bar.getContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                bar.addView(row, matchWrap());
            }
            row.addView(cell(bar.getContext(), shown[i], size, compare), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
    }

    /** One element: its sample, and above it the same sample in the original style when comparing */
    private static View cell(Context context, Category category, int size, boolean compare) {
        LinearLayout cell = new LinearLayout(context);
        cell.setOrientation(LinearLayout.VERTICAL);

        CharSequence text = sampleText(context, category, false);
        float maxSp = MAX_SAMPLE_SP[size];

        if (compare) {
            TextView original = text(context, text, Math.min(BASE_SP[category.ordinal()], maxSp), 0xFF8A8A8A);
            cell.addView(original, matchWrap());
        }

        float scale = MainUIData.instance(context).getFontScale(category);
        TextView styled = text(context, text, Math.min(BASE_SP[category.ordinal()] * scale, maxSp), Color.WHITE);
        // Same code path as the real screens: color, weight and font type
        FontScaler.applyColorAndStyle(styled, category);
        cell.addView(styled, matchWrap());

        return cell;
    }

    private static TextView text(Context context, CharSequence text, float sp, int color) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setGravity(horizontalGravity(context));
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setPadding(0, dp(context, 4), dp(context, 8), 0);
        return tv;
    }

    private static Category[] pick(Category[] targets, int limit) {
        List<Category> result = new ArrayList<>();

        if (targets.length <= limit) {
            for (Category t : targets) {
                result.add(t);
            }
        } else {
            for (Category c : REPRESENTATIVE) {
                for (Category t : targets) {
                    if (t == c && result.size() < limit) {
                        result.add(c);
                        break;
                    }
                }
            }
            // Nothing representative in the group: show the first elements of the group
            for (int i = 0; result.isEmpty() && i < Math.min(limit, targets.length); i++) {
                result.add(targets[i]);
            }
        }

        return result.toArray(new Category[0]);
    }

    /** The text of a sample: element name, a sample sentence, or the text typed by the user */
    private static CharSequence sampleText(Context context, Category category, boolean fullScreen) {
        int mode = option(context, AppearancePrefs.PREVIEW_TEXT);
        String custom = AppearancePrefs.getPreviewCustomText(context);

        if (mode == 2 && !custom.isEmpty()) {
            return custom;
        }
        // The full-screen preview labels every sample with the element name, so it always shows real text
        if (mode == 1 || mode == 2 || fullScreen) {
            return context.getString(R.string.ap_prev_sample);
        }
        return context.getString(category.titleResId);
    }

    private static void addColorSamples(LinearLayout bar) {
        Context context = bar.getContext();

        TextView normal = text(context, context.getString(R.string.ap_prev_normal), 16, Color.WHITE);
        bar.addView(normal, matchWrap());

        int accent = AppearancePrefs.getAccent(context);
        TextView focused = text(context, context.getString(R.string.ap_prev_focused), 16, accent != 0 ? accent : Color.WHITE);
        bar.addView(focused, matchWrap());
    }

    // ------------------------------------------------------------------ full-screen preview

    /**
     * A full-screen sample with the real sizes, over the settings window. Tap, OK or Back closes it,
     * and it closes itself after a while, so it can never stay on screen.
     * @param targets elements to show, or null to show the background and highlight colors
     */
    public static void showFull(Activity activity, Category[] targets) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) {
            return;
        }

        closeFull(content);

        View previousFocus = activity.getCurrentFocus();
        int bg = AppearancePrefs.getBackground(activity);

        FrameLayout overlay = new FrameLayout(activity);
        overlay.setTag(TAG_FULL);
        overlay.setBackgroundColor((bg != 0 ? bg : 0xFF121212) | 0xFF000000);
        overlay.setElevation(dp(activity, 24));
        overlay.setFocusable(true);
        overlay.setFocusableInTouchMode(true);

        Runnable close = () -> {
            closeFull(content);
            if (previousFocus != null && previousFocus.isAttachedToWindow()) {
                previousFocus.requestFocus();
            }
        };
        overlay.setOnClickListener(v -> close.run());
        overlay.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    close.run();
                }
                return true; // also swallows ACTION_DOWN, so Back doesn't close the settings
            }
            return false;
        });

        ScrollView scroll = new ScrollView(activity);
        scroll.setFocusable(false);
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 20);
        column.setPadding(pad, pad, pad, pad);
        scroll.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = text(activity, activity.getString(R.string.ap_prev_full_hint), 12, 0xFF9E9E9E);
        hint.setSingleLine(false);
        column.addView(hint, matchWrap());

        if (targets == null) {
            addFullColors(column);
        } else {
            for (Category category : Category.values()) {
                if (contains(targets, category)) {
                    addFullSample(column, category);
                }
            }
        }

        overlay.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.requestFocus();
        overlay.postDelayed(close, FULL_AUTO_CLOSE_MS);
    }

    private static void closeFull(ViewGroup content) {
        View old = content.findViewWithTag(TAG_FULL);
        if (old != null) {
            content.removeView(old);
        }
    }

    private static void addFullSample(LinearLayout column, Category category) {
        Context context = column.getContext();

        TextView label = text(context, context.getString(category.titleResId), 12, 0xFF9E9E9E);
        label.setPadding(0, dp(context, 16), 0, 0);
        column.addView(label, matchWrap());

        // Real size: no clamping, long text wraps
        float scale = MainUIData.instance(context).getFontScale(category);
        TextView sample = text(context, sampleText(context, category, true), BASE_SP[category.ordinal()] * scale, Color.WHITE);
        sample.setSingleLine(false);
        sample.setEllipsize(null);
        FontScaler.applyColorAndStyle(sample, category);
        column.addView(sample, matchWrap());
    }

    private static void addFullColors(LinearLayout column) {
        Context context = column.getContext();

        TextView normal = text(context, context.getString(R.string.ap_prev_normal), 22, Color.WHITE);
        normal.setPadding(0, dp(context, 16), 0, 0);
        column.addView(normal, matchWrap());

        int accent = AppearancePrefs.getAccent(context);
        TextView focused = text(context, context.getString(R.string.ap_prev_focused), 22, accent != 0 ? accent : Color.WHITE);
        focused.setPadding(0, dp(context, 8), 0, 0);
        column.addView(focused, matchWrap());
    }

    // ------------------------------------------------------------------ helpers

    private static boolean contains(Category[] targets, Category category) {
        for (Category t : targets) {
            if (t == category) {
                return true;
            }
        }
        return false;
    }

    private static int option(Context context, String key) {
        return AppearancePrefs.getPreviewOption(context, key);
    }

    private static int clampSize(int size) {
        return Math.max(0, Math.min(2, size));
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Where the text sits inside the window: absolute left / center / right, as chosen in the preview settings */
    private static int horizontalGravity(Context context) {
        switch (option(context, AppearancePrefs.PREVIEW_ALIGN)) {
            case 0:
                return Gravity.LEFT;
            case 2:
                return Gravity.RIGHT;
            default:
                return Gravity.CENTER_HORIZONTAL;
        }
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }
}
