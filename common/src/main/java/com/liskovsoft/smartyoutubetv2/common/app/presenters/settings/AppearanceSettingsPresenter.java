package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.AppearanceFonts;
import com.liskovsoft.smartyoutubetv2.common.misc.AppearancePrefs;
import com.liskovsoft.smartyoutubetv2.common.misc.AppearancePreview;
import com.liskovsoft.smartyoutubetv2.common.misc.AppearanceStyle;
import com.liskovsoft.smartyoutubetv2.common.misc.FontScaler;
import com.liskovsoft.smartyoutubetv2.common.misc.FontScaler.Category;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;

import java.util.ArrayList;
import java.util.List;

/**
 * Advanced appearance. A short main screen opens sub screens: titles, a single element (size, weight, font type and
 * color in one place), all text, styles and sharing, and colors/background.
 */
public class AppearanceSettingsPresenter extends BasePresenter<Void> {
    private static final int[] COLOR_NAMES = {R.string.ap_color_default, R.string.ap_color_white, R.string.ap_color_grey, R.string.ap_color_yellow, R.string.ap_color_amber, R.string.ap_color_orange, R.string.ap_color_red, R.string.ap_color_pink, R.string.ap_color_purple, R.string.ap_color_blue, R.string.ap_color_cyan, R.string.ap_color_green, R.string.ap_color_black};
    private static final int[] COLOR_VALUES = {0, 0xFFFFFFFF, 0xFFE0E0E0, 0xFFFFEB3B, 0xFFFFC107, 0xFFFF9800, 0xFFFF5252, 0xFFFF80AB, 0xFFCE93D8, 0xFF64B5F6, 0xFF4DD0E1, 0xFF81C784, 0xFF000000};
    private static final int[] BG_NAMES = {R.string.ap_bg_default, R.string.ap_bg_black, R.string.ap_bg_dgrey, R.string.ap_bg_charcoal, R.string.ap_bg_navy, R.string.ap_bg_forest, R.string.ap_bg_plum, R.string.ap_bg_brown, R.string.ap_bg_wine};
    private static final int[] BG_VALUES = {0, 0xFF000000, 0xFF121212, 0xFF1E1E1E, 0xFF0D1B2A, 0xFF0F2417, 0xFF1A1025, 0xFF1B1410, 0xFF2A0D0D};
    private static final int[] PRESET_NAMES = {R.string.ap_preset_default, R.string.ap_preset_tv_large, R.string.ap_preset_compact, R.string.ap_preset_contrast, R.string.ap_preset_warm};
    private static final float[] FONT_STEPS = {0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.7f, 2.0f, 2.5f};

    /** Elements that are titles: used by the "Titles" screen */
    private static final Category[] TITLES = {Category.CARD_TITLE, Category.ROW_HEADER, Category.BROWSE_TITLE,
            Category.DIALOG_TITLE, Category.DETAILS, Category.MENU_ITEM, Category.SETTINGS_ITEM};

    private final MainUIData mMainUIData;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mRestartApp;
    private boolean mForward;
    private boolean mPreviewEnabled;
    private Category[] mPreviewTargets;

    private AppearanceSettingsPresenter(Context context) {
        super(context);
        mMainUIData = MainUIData.instance(context);
    }

    public static AppearanceSettingsPresenter instance(Context context) {
        return new AppearanceSettingsPresenter(context);
    }

    // ------------------------------------------------------------------ navigation

    /** Opens a deeper screen from a button: closes the current one without returning to its parent */
    private void go(Runnable screen) {
        mForward = true;
        mPreviewEnabled = false;
        AppDialogPresenter.instance(getContext()).closeDialog();
        mHandler.post(screen);
    }

    /** What happens when a screen is left: BACK returns to the parent screen, going deeper does not */
    private Runnable backTo(Runnable parent) {
        return () -> {
            if (mForward) {
                mForward = false;
                return;
            }
            mPreviewEnabled = false;
            if (mRestartApp) {
                mRestartApp = false;
                MessageHelpers.showLongMessage(getContext(), R.string.ap_note_restart);
            }
            if (parent != null) {
                mHandler.post(parent);
            }
        };
    }

    private void button(AppDialogPresenter d, int titleRes, int descRes, Runnable screen) {
        d.appendSingleButton(UiOptionItem.from(getContext().getString(titleRes), getContext().getString(descRes), optionItem -> go(screen)));
    }

    // ------------------------------------------------------------------ screens

    /** Main screen: short, only entry points */
    public void show() {
        AppDialogPresenter d = AppDialogPresenter.instance(getContext());

        button(d, R.string.ap_hub_titles, R.string.ap_hub_titles_d, this::showTitles);
        button(d, R.string.ap_hub_element, R.string.ap_hub_element_d, this::showElements);
        button(d, R.string.ap_hub_all, R.string.ap_hub_all_d, this::showAll);
        button(d, R.string.ap_hub_styles, R.string.ap_hub_styles_d, this::showStyles);
        button(d, R.string.ap_hub_colors, R.string.ap_hub_colors_d, this::showColors);
        appendReset(d);

        d.showDialog(getContext().getString(R.string.ap_dialog), backTo(null));
    }

    private void showTitles() {
        AppDialogPresenter d = AppDialogPresenter.instance(getContext());
        appendGroup(d, TITLES);
        previewFor(d, TITLES);
        d.showDialog(getContext().getString(R.string.ap_hub_titles), backTo(this::show));
    }

    private void showAll() {
        AppDialogPresenter d = AppDialogPresenter.instance(getContext());
        appendGlobalFontScale(d);
        appendGroup(d, Category.values());
        previewFor(d, Category.values());
        d.showDialog(getContext().getString(R.string.ap_hub_all), backTo(this::show));
    }

    private void showElements() {
        AppDialogPresenter d = AppDialogPresenter.instance(getContext());

        for (Category category : Category.values()) {
            d.appendSingleButton(UiOptionItem.from(getContext().getString(category.titleResId), summary(category),
                    optionItem -> go(() -> showElement(category))));
        }

        d.showDialog(getContext().getString(R.string.ap_elements_title), backTo(this::show));
    }

    private void showElement(Category category) {
        AppDialogPresenter d = AppDialogPresenter.instance(getContext());
        appendGroup(d, new Category[] {category});
        previewFor(d, new Category[] {category});
        d.showDialog(getContext().getString(category.titleResId), backTo(this::showElements));
    }

    private void showStyles() {
        AppDialogPresenter d = AppDialogPresenter.instance(getContext());
        appendPresets(d);
        appendSlots(d);
        appendTransfer(d);
        previewFor(d, Category.values());
        d.showDialog(getContext().getString(R.string.ap_hub_styles), backTo(this::show));
    }

    private void showColors() {
        AppDialogPresenter d = AppDialogPresenter.instance(getContext());
        appendBackground(d);
        appendAccent(d);
        previewFor(d, null);
        d.showDialog(getContext().getString(R.string.ap_hub_colors), backTo(this::show));
    }

    /** One line with the current settings of an element, e.g. "1.2x · Bold · Serif · Yellow" */
    private String summary(Category category) {
        Context ctx = getContext();
        List<String> parts = new ArrayList<>();

        parts.add(String.format("%sx", mMainUIData.getFontScale(category)));

        int weight = AppearancePrefs.getWeight(ctx, category);
        for (int i = 0; i < AppearanceFonts.WEIGHT_VALUES.length; i++) {
            if (weight != 0 && AppearanceFonts.WEIGHT_VALUES[i] == weight) {
                parts.add(ctx.getString(AppearanceFonts.WEIGHT_NAMES[i]));
            }
        }

        AppearanceFonts.FontList fonts = AppearanceFonts.list(ctx);
        int fontIndex = fonts.keys.indexOf(AppearancePrefs.getFont(ctx, category));
        if (fontIndex > 0) {
            parts.add(fonts.names.get(fontIndex));
        }

        int color = AppearancePrefs.getTextColor(ctx, category);
        for (int i = 1; i < COLOR_VALUES.length; i++) {
            if (COLOR_VALUES[i] == color) {
                parts.add(ctx.getString(COLOR_NAMES[i]));
            }
        }

        return TextUtils.join(" \u00B7 ", parts);
    }

    // ------------------------------------------------------------------ option groups

    /** Size, weight, font type and color for the given elements, all in one place */
    private void appendGroup(AppDialogPresenter d, Category[] targets) {
        appendSizeOptions(d, targets);
        appendWeightOptions(d, targets);
        appendFontOptions(d, targets);
        appendColorOptions(d, targets);
    }

    private void appendPresets(AppDialogPresenter settingsPresenter) {
        List<OptionItem> options = new ArrayList<>();

        for (int i = 0; i < PRESET_NAMES.length; i++) {
            final int preset = i;
            options.add(UiOptionItem.from(getContext().getString(PRESET_NAMES[i]),
                    optionItem -> {
                        applyPreset(preset);
                        updatePreview();
                        MessageHelpers.showMessage(getContext(), R.string.ap_preset_applied);
                    }, false));
        }

        settingsPresenter.appendRadioCategory(getContext().getString(R.string.ap_presets), options);
    }

    private void appendGlobalFontScale(AppDialogPresenter settingsPresenter) {
        List<OptionItem> options = new ArrayList<>();

        for (float scale : new float[] {0.7f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.7f, 2.0f}) {
            options.add(UiOptionItem.from(String.format("%sx", scale),
                    optionItem -> {
                        mMainUIData.setFontScale(scale);
                        mRestartApp = true;
                    },
                    Helpers.floatEquals(scale, mMainUIData.getFontScale())));
        }

        settingsPresenter.appendRadioCategory(getContext().getString(R.string.font_scale), options);
    }

    private void appendSizeOptions(AppDialogPresenter d, Category[] targets) {
        List<OptionItem> options = new ArrayList<>();

        for (float scale : FONT_STEPS) {
            boolean selected = true;
            for (Category target : targets) {
                selected &= Helpers.floatEquals(scale, mMainUIData.getFontScale(target));
            }
            options.add(UiOptionItem.from(String.format("%sx", scale),
                    optionItem -> {
                        for (Category target : targets) {
                            mMainUIData.setFontScale(target, scale);
                        }
                        updatePreview();
                    }, selected));
        }

        d.appendRadioCategory(getContext().getString(R.string.ap_k_size), options);
    }

    private void appendWeightOptions(AppDialogPresenter d, Category[] targets) {
        List<OptionItem> options = new ArrayList<>();

        for (int i = 0; i < AppearanceFonts.WEIGHT_VALUES.length; i++) {
            final int weight = AppearanceFonts.WEIGHT_VALUES[i];
            boolean selected = true;
            for (Category target : targets) {
                selected &= AppearancePrefs.getWeight(getContext(), target) == weight;
            }
            options.add(UiOptionItem.from(getContext().getString(AppearanceFonts.WEIGHT_NAMES[i]),
                    optionItem -> {
                        for (Category target : targets) {
                            AppearancePrefs.setWeight(getContext(), target, weight);
                        }
                        updatePreview();
                    }, selected));
        }

        d.appendRadioCategory(getContext().getString(R.string.ap_k_weight), options);
    }

    private void appendFontOptions(AppDialogPresenter d, Category[] targets) {
        AppearanceFonts.FontList fonts = AppearanceFonts.list(getContext());
        List<OptionItem> options = new ArrayList<>();

        for (int i = 0; i < fonts.keys.size(); i++) {
            final String key = fonts.keys.get(i);
            boolean selected = true;
            for (Category target : targets) {
                selected &= key.equals(AppearancePrefs.getFont(getContext(), target));
            }
            options.add(UiOptionItem.from(fonts.names.get(i),
                    optionItem -> {
                        for (Category target : targets) {
                            AppearancePrefs.setFont(getContext(), target, key);
                        }
                        updatePreview();
                    }, selected));
        }

        d.appendRadioCategory(getContext().getString(R.string.ap_k_font), options);
    }

    private void appendColorOptions(AppDialogPresenter d, Category[] targets) {
        List<OptionItem> options = new ArrayList<>();

        for (int i = 0; i < COLOR_VALUES.length; i++) {
            final int color = COLOR_VALUES[i];
            boolean selected = true;
            for (Category target : targets) {
                selected &= AppearancePrefs.getTextColor(getContext(), target) == color;
            }
            options.add(UiOptionItem.from(swatch(getContext().getString(COLOR_NAMES[i]), color),
                    optionItem -> {
                        for (Category target : targets) {
                            AppearancePrefs.setTextColor(getContext(), target, color);
                        }
                        updatePreview();
                    }, selected));
        }

        d.appendRadioCategory(getContext().getString(R.string.ap_k_color), options);
    }

    private void appendBackground(AppDialogPresenter settingsPresenter) {
        List<OptionItem> options = new ArrayList<>();

        for (int i = 0; i < BG_VALUES.length; i++) {
            final int color = BG_VALUES[i];
            options.add(UiOptionItem.from(swatch(getContext().getString(BG_NAMES[i]), color),
                    optionItem -> {
                        AppearancePrefs.setBackground(getContext(), color);
                        updatePreview();
                    },
                    AppearancePrefs.getBackground(getContext()) == color));
        }

        settingsPresenter.appendRadioCategory(getContext().getString(R.string.ap_background), options);
    }

    private void appendAccent(AppDialogPresenter settingsPresenter) {
        List<OptionItem> options = new ArrayList<>();

        for (int i = 0; i < COLOR_VALUES.length; i++) {
            final int color = COLOR_VALUES[i];
            options.add(UiOptionItem.from(swatch(getContext().getString(COLOR_NAMES[i]), color),
                    optionItem -> {
                        AppearancePrefs.setAccent(getContext(), color);
                        updatePreview();
                    },
                    AppearancePrefs.getAccent(getContext()) == color));
        }

        settingsPresenter.appendRadioCategory(getContext().getString(R.string.ap_accent), options);
    }

    /** A colored dot before the name, so the color can be recognized at a glance */
    private static CharSequence swatch(String name, int color) {
        if (color == 0) {
            return name;
        }

        SpannableString text = new SpannableString("\u25CF " + name);
        // Black would be invisible on dark dialogs: show it with a light outline-like grey dot
        int dot = (color & 0xFFFFFF) == 0 ? 0xFF616161 : color;
        text.setSpan(new ForegroundColorSpan(dot), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private void appendSlots(AppDialogPresenter settingsPresenter) {
        List<OptionItem> save = new ArrayList<>();
        List<OptionItem> load = new ArrayList<>();

        for (int i = 1; i <= AppearanceStyle.SLOTS; i++) {
            final int slot = i;
            String saved = AppearanceStyle.getSlot(getContext(), slot);
            String title = saved == null ?
                    getContext().getString(R.string.ap_slot_empty, slot) :
                    getContext().getString(R.string.ap_slot_filled, slot,
                            android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", AppearanceStyle.getSlotTime(getContext(), slot)));

            save.add(UiOptionItem.from(title, optionItem -> {
                AppearanceStyle.saveSlot(getContext(), slot);
                MessageHelpers.showMessage(getContext(), R.string.ap_slot_saved);
            }, false));

            if (saved != null) {
                load.add(UiOptionItem.from(title, optionItem -> {
                    if (AppearanceStyle.apply(getContext(), AppearanceStyle.getSlot(getContext(), slot))) {
                        updatePreview();
                        MessageHelpers.showMessage(getContext(), R.string.ap_slot_loaded);
                    }
                }, false));
            }
        }

        settingsPresenter.appendRadioCategory(getContext().getString(R.string.ap_save_slot), save);
        if (!load.isEmpty()) {
            settingsPresenter.appendRadioCategory(getContext().getString(R.string.ap_load_slot), load);
        }
    }

    private void appendTransfer(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.ap_export), optionItem -> {
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("style", AppearanceStyle.serialize(getContext())));
                MessageHelpers.showLongMessage(getContext(), R.string.ap_export_done);
            }
        }));

        settingsPresenter.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.ap_import), optionItem -> {
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = cm != null ? cm.getPrimaryClip() : null;
            CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(getContext()) : null;

            if (text != null && AppearanceStyle.apply(getContext(), text.toString())) {
                updatePreview();
                MessageHelpers.showMessage(getContext(), R.string.ap_import_done);
            } else {
                MessageHelpers.showMessage(getContext(), R.string.ap_import_bad);
            }
        }));
    }

    // ------------------------------------------------------------------ live preview

    /**
     * Shows the preview strip inside the settings window once it is created.
     * @param targets elements to show, or null to show the background and highlight colors
     */
    private void previewFor(AppDialogPresenter d, Category[] targets) {
        mPreviewTargets = targets;
        mPreviewEnabled = true;
        d.setOnStart(this::updatePreview);
    }

    /** Redraws the strip after an option was changed */
    private void updatePreview() {
        if (!mPreviewEnabled) {
            return;
        }

        Activity activity = AppDialogPresenter.instance(getContext()).getDialogActivity();
        if (mPreviewTargets == null) {
            AppearancePreview.showColors(activity);
        } else {
            AppearancePreview.showElements(activity, mPreviewTargets);
        }
    }

    private void appendReset(AppDialogPresenter settingsPresenter) {
        settingsPresenter.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.ap_reset), optionItem -> {
            resetAll();
            updatePreview();
            MessageHelpers.showMessage(getContext(), R.string.ap_reset_done);
        }));
    }

    private void resetAll() {
        AppearancePrefs.resetAll(getContext());
        for (Category category : Category.values()) {
            mMainUIData.setFontScale(category, 1.0f);
        }
    }

    private void setAllSizes(float scale) {
        for (Category category : Category.values()) {
            mMainUIData.setFontScale(category, scale);
        }
    }

    private void setAllColors(int color) {
        for (Category category : Category.values()) {
            AppearancePrefs.setTextColor(getContext(), category, color);
        }
    }

    private void applyPreset(int preset) {
        resetAll();

        switch (preset) {
            case 1: // large text
                setAllSizes(1.3f);
                break;
            case 2: // compact
                setAllSizes(0.85f);
                break;
            case 3: // high contrast
                setAllSizes(1.1f);
                setAllColors(0xFFFFFFFF);
                AppearancePrefs.setBackground(getContext(), 0xFF000000);
                for (Category category : new Category[] {Category.CARD_TITLE, Category.ROW_HEADER, Category.MENU_ITEM, Category.SETTINGS_ITEM}) {
                    AppearancePrefs.setWeight(getContext(), category, AppearanceFonts.WEIGHT_BOLD);
                }
                break;
            case 4: // warm night
                setAllColors(0xFFFFE0B2);
                AppearancePrefs.setBackground(getContext(), 0xFF1B1410);
                break;
            default: // 0: already reset
                break;
        }
    }
}
