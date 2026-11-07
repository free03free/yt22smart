package com.liskovsoft.smartyoutubetv2.common.utils;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Keyboard that lives inside the app (Arabic / English / symbols) so the device keyboard is never opened.
 * <p>
 * Why: with the system keyboard, leaving it (Back / losing focus) closes the dialog and the typed text is lost.
 * Here the text is kept in the dialog itself and is also saved as a "draft" after every key, so it survives
 * closing the dialog with Back, Cancel, or even restarting the app. The next time a dialog with the same title is
 * opened the last text is restored (press "Clear" to empty it).
 * <p>
 * Look: a dark bottom sheet in the style of the YouTube TV keyboard - rounded dark keys, a white focus highlight
 * with a slight zoom, vector icons drawn in code (no font glyphs), accent colour taken from the app theme.
 * Works with touch, TV remote (D-pad) and a physical keyboard. Layout is built in code - no XML needed.
 */
public final class InAppKeyboardDialog {
    public interface OnSubmit {
        /** @return true to close the dialog, false to keep it open (e.g. invalid input) */
        boolean onSubmit(String text);
    }

    private static final String PREFS = "in_app_keyboard";
    private static final String PREF_LANG = "lang_ar";
    private static final String PREF_DRAFT = "draft:";

    private static final int LAYER_EN = 0;
    private static final int LAYER_AR = 1;
    private static final int LAYER_SYM = 2;


    // YouTube-TV like palette
    private static final int C_SHEET = 0xFF212121;
    private static final int C_FIELD = 0xFF121212;
    private static final int C_FIELD_STROKE = 0xFF3F3F3F;
    private static final int C_KEY = 0xFF303030;
    private static final int C_KEY_SPECIAL = 0xFF3D3D3D;
    private static final int C_TEXT = 0xFFF1F1F1;
    private static final int C_TEXT_DIM = 0xFFAAAAAA;
    private static final int C_FOCUS = 0xFFFFFFFF;
    private static final int C_FOCUS_TEXT = 0xFF0F0F0F;
    private static final int C_DEFAULT_ACCENT = 0xFFE52D27;
    private static final String TATWEEL = "\u0640";

    private static final String[] DIGITS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};

    private static final String[][] EN = {
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l", "'"},
            {"z", "x", "c", "v", "b", "n", "m"}
    };

    private static final String[][] AR = {
            {"ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج", "د"},
            {"ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط", "ذ"},
            {"ئ", "ء", "ؤ", "ر", "لا", "ى", "ة", "و", "ز", "ظ"}
    };

    /** Arabic "shift" layer: diacritics, hamza forms and extra letters. */
    private static final String[][] AR_SHIFT = {
            {"\u064E", "\u064B", "\u064F", "\u064C", "\u0650", "\u064D", "\u0651", "\u0652", TATWEEL, "\u0670", "\u0653", "\u0654"},
            {"أ", "إ", "آ", "ؤ", "ئ", "ء", "لا", "«", "»", ":", "!", "."},
            {"ڤ", "پ", "چ", "گ", "ژ", "ڨ", "،", "؛", "؟", "٪"}
    };

    private static final String[][] SYM = {
            {"@", "#", "$", "%", "&", "*", "-", "+", "(", ")"},
            {"!", "\"", "'", ":", ";", "/", "?", "_", "=", "."},
            {",", "~", "^", "[", "]", "<", ">", "\\"}
    };

    private final Context mContext;
    private final String mTitle;
    private final String mHint;
    private final OnSubmit mOnSubmit;
    private final boolean mSearchStyle;
    private final SharedPreferences mPrefs;
    private final StringBuilder mText = new StringBuilder();
    private final float mDensity;
    private final int mAccent;
    private int mRowH;
    private float mCols = 10f;
    private int mPanelWidth;
    private int mCaret;
    private int mLetters;   // LAYER_EN or LAYER_AR: the last letters layout used
    private int mLayer;     // what is shown now
    private boolean mShift;
    private Dialog mDialog;
    private TextView mDisplay;
    private LinearLayout mKeys;

    public static void show(Context context, String title, String hint, String initial, OnSubmit onSubmit) {
        show(context, title, hint, initial, onSubmit, null);
    }

    public static void show(Context context, String title, String hint, String initial, OnSubmit onSubmit, Runnable onDismiss) {
        open(context, title, hint, initial, onSubmit, onDismiss, false);
    }

    /** Same, but styled as a search box: magnifier in the field and on the action key. */
    public static void showSearch(Context context, String title, String hint, String initial, OnSubmit onSubmit) {
        showSearch(context, title, hint, initial, onSubmit, null);
    }

    public static void showSearch(Context context, String title, String hint, String initial, OnSubmit onSubmit, Runnable onDismiss) {
        open(context, title, hint, initial, onSubmit, onDismiss, true);
    }

    private static void open(Context context, String title, String hint, String initial, OnSubmit onSubmit, Runnable onDismiss, boolean search) {
        try {
            new InAppKeyboardDialog(context, title, hint, initial, onSubmit, search).open(onDismiss);
        } catch (RuntimeException e) {
            // BadTokenException etc. - fall back to the old dialog so the feature never gets lost
            e.printStackTrace();
            SimpleEditDialog.show(context, title, hint, initial, onSubmit::onSubmit, onDismiss);
        }
    }

    /** Last text typed in dialogs with this title (empty if none). */
    public static String getDraft(Context context, String title) {
        return prefs(context).getString(PREF_DRAFT + title, "");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private InAppKeyboardDialog(Context context, String title, String hint, String initial, OnSubmit onSubmit, boolean search) {
        mContext = context;
        mTitle = title == null ? "" : title;
        mHint = hint == null ? "" : hint;
        mOnSubmit = onSubmit;
        mSearchStyle = search;
        mPrefs = prefs(context);
        mDensity = context.getResources().getDisplayMetrics().density;
        mAccent = resolveAccent();

        String start = initial == null ? "" : initial;
        if (start.isEmpty()) {
            start = mPrefs.getString(PREF_DRAFT + mTitle, ""); // restore what was typed before
        }
        mText.append(start);
        mCaret = mText.length();

        boolean arabicDefault = "ar".equals(Locale.getDefault().getLanguage());
        mLetters = mPrefs.getBoolean(PREF_LANG, arabicDefault) ? LAYER_AR : LAYER_EN;
        mLayer = mLetters;
    }

    // ------------------------------------------------------------------ window

    private void open(Runnable onDismiss) {
        mDialog = new Dialog(mContext);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        mDialog.setContentView(buildRoot());
        mDialog.setCanceledOnTouchOutside(false); // a stray touch must not throw the text away
        mDialog.setOnKeyListener((d, keyCode, event) -> onDialogKey(keyCode, event));
        if (onDismiss != null) {
            mDialog.setOnDismissListener(d -> onDismiss.run());
        }

        Window window = mDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.65f);
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        }

        mDialog.show();

        if (window != null) {
            DisplayMetrics dm = screenMetrics();
            window.setLayout(mPanelWidth > 0 ? mPanelWidth : dm.widthPixels, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        View space = mKeys.findViewWithTag("space");
        if (space != null) {
            space.requestFocus();
        }
    }

    private View buildRoot() {
        DisplayMetrics dm = screenMetrics();
        float heightDp = dm.heightPixels / mDensity;
        float widthDp = Math.min(dm.widthPixels / mDensity, 1100f);
        boolean leanback = isLeanback();
        boolean titleRow = !mSearchStyle;
        int navPx = (!leanback && dm.heightPixels > dm.widthPixels) ? navigationBarHeight() : 0;

        // keys get as tall as the screen allows (720p TV / landscape phone is only ~360dp high) ...
        float fixed = 30 + 68 + (titleRow ? 34 : 0) + navPx / mDensity;
        float rowDp = (heightDp * 0.94f - fixed) / 5f - 6f;
        // ... but never taller than they are wide (12 columns in portrait are narrow)
        float keyWidthDp = (widthDp - 36f) / 12f;
        rowDp = Math.max(30f, Math.min(Math.min(56f, keyWidthDp * 1.45f), rowDp));
        mRowH = dp(Math.round(rowDp));
        // on wide screens keep the keys close to square instead of stretching the sheet edge to edge
        mPanelWidth = Math.min(dm.widthPixels, dp(Math.round(Math.min(1100f, rowDp * 1.6f * 12f + 36f))));

        LinearLayout root = new LinearLayout(mContext);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setPadding(dp(18), dp(14), dp(18), dp(16) + navPx);
        GradientDrawable sheet = new GradientDrawable();
        sheet.setColor(C_SHEET);
        float r = dp(22);
        sheet.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(sheet);
        // keys keep their physical positions whatever the app language direction is
        if (Build.VERSION.SDK_INT >= 17) {
            root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        }

        if (titleRow) {
            LinearLayout top = new LinearLayout(mContext);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(mContext);
            title.setText(mTitle);
            title.setTextColor(C_TEXT_DIM);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTypeface(medium());
            title.setSingleLine(true);
            top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (!leanback) { // touch devices have no Back key on screen
                KeyView close = key("", KeyView.CLOSE, C_KEY_SPECIAL, v -> mDialog.dismiss());
                top.addView(close, new LinearLayout.LayoutParams(dp(40), dp(30)));
            }
            LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
            root.addView(top, topLp);
        }

        // text field: pill with the magnifier on the left (search) like the YouTube search bar
        LinearLayout field = new LinearLayout(mContext);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable box = new GradientDrawable();
        box.setColor(C_FIELD);
        box.setCornerRadius(dp(30));
        box.setStroke(dp(1), C_FIELD_STROKE);
        field.setBackground(box);
        if (mSearchStyle) {
            KeyView icon = new KeyView(mContext);
            icon.setIcon(KeyView.SEARCH, C_TEXT_DIM, C_TEXT_DIM, false);
            icon.setFocusable(false);
            field.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        }
        mDisplay = new TextView(mContext);
        mDisplay.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        mDisplay.setTypeface(medium());
        mDisplay.setGravity(Gravity.CENTER_VERTICAL);
        mDisplay.setSingleLine(true);
        mDisplay.setEllipsize(android.text.TextUtils.TruncateAt.START);
        if (Build.VERSION.SDK_INT >= 17) {
            mDisplay.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            mDisplay.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START); // Arabic text hugs the right, English the left
        }
        LinearLayout.LayoutParams dispLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        dispLp.setMargins(mSearchStyle ? dp(12) : 0, 0, 0, 0);
        field.addView(mDisplay, dispLp);
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        fieldLp.setMargins(0, dp(2), 0, dp(10));
        root.addView(field, fieldLp);

        mKeys = new LinearLayout(mContext);
        mKeys.setOrientation(LinearLayout.VERTICAL);
        mKeys.setClipChildren(false);
        root.addView(mKeys, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildKeys();
        refresh(false);
        return root;
    }

    /** Current display size - follows rotation (the app resources may still report the old orientation). */
    private DisplayMetrics screenMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        try {
            WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
            wm.getDefaultDisplay().getMetrics(dm);
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                return dm;
            }
        } catch (RuntimeException e) {
            // fall through
        }
        return mContext.getResources().getDisplayMetrics();
    }

    /** The app draws under the translucent system navigation bar, so keep the bottom row above it. */
    private int navigationBarHeight() {
        try {
            int id = mContext.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            return id > 0 ? mContext.getResources().getDimensionPixelSize(id) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private boolean isLeanback() {
        try {
            return mContext.getPackageManager().hasSystemFeature("android.software.leanback");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Accent from the app theme (red / teal / blue colour schemes); falls back to YouTube red. */
    private int resolveAccent() {
        try {
            TypedValue tv = new TypedValue();
            if (mContext.getTheme().resolveAttribute(android.R.attr.colorAccent, tv, true)
                    && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data | 0xFF000000;
            }
        } catch (RuntimeException e) {
            // use default
        }
        return C_DEFAULT_ACCENT;
    }

    // ------------------------------------------------------------------ keys

    private String[][] letterRows() {
        if (mLayer == LAYER_SYM) {
            return SYM;
        }
        if (mLayer == LAYER_AR) {
            return mShift ? AR_SHIFT : AR;
        }
        return EN;
    }

    private void buildKeys() {
        mKeys.removeAllViews();
        mCols = mLayer == LAYER_AR ? 12f : 10f;

        // digits row (all layers)
        mKeys.addView(row(DIGITS, 0, 0, false));

        String[][] rows = letterRows();
        for (int r = 0; r < rows.length; r++) {
            boolean last = r == rows.length - 1;
            if (!last) {
                mKeys.addView(row(rows[r], 0, 0, false));
                continue;
            }
            float free = mCols - rows[r].length;
            if (mLayer == LAYER_SYM) {
                mKeys.addView(row(rows[r], 0, free, true));
            } else {
                mKeys.addView(row(rows[r], free / 2f, free / 2f, true));
            }
        }

        mKeys.addView(bottomRow());
    }

    /** One row of character keys; optional shift (left) / backspace (right) with the given weights. */
    private LinearLayout row(String[] chars, float shiftWeight, float backWeight, boolean withBackspace) {
        LinearLayout row = newRow();
        float used = 0;
        if (withBackspace && shiftWeight > 0) {
            KeyView shift = key("", KeyView.SHIFT, mShift ? C_FOCUS : C_KEY_SPECIAL, v -> {
                mShift = !mShift;
                rebuild("shift");
            });
            shift.setTag("shift");
            if (mShift) { // active: white key, dark icon
                shift.setIcon(KeyView.SHIFT, C_FOCUS_TEXT, C_FOCUS_TEXT, true);
            }
            row.addView(shift, weighted(shiftWeight));
            used += shiftWeight;
        }
        // rows without shift/backspace stretch to the full width (digits get wider keys on the 12-column layout)
        float keyWeight = withBackspace ? 1f : mCols / chars.length;
        for (final String c : chars) {
            row.addView(charKey(c), weighted(keyWeight));
            used += keyWeight;
        }
        if (withBackspace) {
            KeyView back = key("", KeyView.BACKSPACE, C_KEY_SPECIAL, v -> backspace());
            back.setOnLongClickListener(v -> {
                deleteWord();
                return true;
            });
            row.addView(back, weighted(backWeight));
            used += backWeight;
        }
        return row;
    }

    private LinearLayout bottomRow() {
        LinearLayout row = newRow();
        final float k = mCols / 12f;

        String lettersLabel = mLetters == LAYER_AR ? "\u0623\u0628\u062C" : "ABC";
        KeyView sym = key(mLayer == LAYER_SYM ? lettersLabel : "123", KeyView.NONE, C_KEY_SPECIAL, v -> {
            mLayer = mLayer == LAYER_SYM ? mLetters : LAYER_SYM;
            mShift = false;
            rebuild("sym");
        });
        sym.setTag("sym");
        sym.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textDp() * 0.8f);
        row.addView(sym, weighted(1.5f * k));

        KeyView lang = key("", KeyView.GLOBE, C_KEY_SPECIAL, v -> {
            mLetters = mLetters == LAYER_EN ? LAYER_AR : LAYER_EN;
            mLayer = mLetters;
            mShift = false;
            mPrefs.edit().putBoolean(PREF_LANG, mLetters == LAYER_AR).apply();
            rebuild("lang");
        });
        lang.setTag("lang");
        row.addView(lang, weighted(1.5f * k));

        row.addView(key("", KeyView.LEFT, C_KEY_SPECIAL, v -> moveCaret(-1)), weighted(1f * k));

        // space bar shows the current language, like the YouTube keyboard
        String langName = mLayer == LAYER_SYM ? "" : (mLetters == LAYER_AR ? "\u0627\u0644\u0639\u0631\u0628\u064A\u0629" : "English");
        KeyView space = key(langName, KeyView.NONE, C_KEY, v -> insert(" "));
        space.setTag("space");
        space.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textDp() * 0.7f);
        space.setNormalTextColor(C_TEXT_DIM);
        row.addView(space, weighted(3.5f * k));

        row.addView(key("", KeyView.RIGHT, C_KEY_SPECIAL, v -> moveCaret(1)), weighted(1f * k));

        KeyView clear = key(mContext.getString(R.string.inapp_kb_clear), KeyView.NONE, C_KEY_SPECIAL, v -> {
            mText.setLength(0);
            mCaret = 0;
            refresh(true);
        });
        clear.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textDp() * 0.7f);
        row.addView(clear, weighted(1.75f * k));

        // action key in the accent colour: magnifier for search, text otherwise
        KeyView ok = mSearchStyle
                ? key("", KeyView.SEARCH, mAccent, v -> submit())
                : key(mContext.getString(android.R.string.ok), KeyView.NONE, mAccent, v -> submit());
        ok.setTag("ok");
        ok.setNormalTextColor(Color.WHITE);
        if (mSearchStyle) {
            ok.setIcon(KeyView.SEARCH, Color.WHITE, mAccent, false);
        } else {
            ok.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textDp() * 0.75f);
        }
        row.addView(ok, weighted(1.75f * k));
        return row;
    }

    private KeyView charKey(final String label) {
        String shown = label;
        String value = label;
        if (mLayer == LAYER_EN && mShift) {
            shown = value = label.toUpperCase(Locale.ENGLISH);
        } else if (label.length() == 1 && Character.getType(label.charAt(0)) == Character.NON_SPACING_MARK) {
            shown = TATWEEL + label; // show a combining mark on a carrier so it is visible
        }
        final String insertValue = value;
        return key(shown, KeyView.NONE, C_KEY, v -> {
            insert(insertValue);
            if (mShift) { // one-shot shift
                mShift = false;
                rebuild(null);
            }
        });
    }

    private float textDp() {
        return mRowH / mDensity * 0.42f;
    }

    private KeyView key(String label, int icon, int color, View.OnClickListener click) {
        KeyView b = new KeyView(mContext);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textDp());
        b.setTypeface(medium());
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setIncludeFontPadding(false);
        b.setPadding(0, 0, 0, 0);
        b.setFocusable(true);
        b.setClickable(true);
        b.setNormalTextColor(C_TEXT);
        b.setIcon(icon, C_TEXT, C_FOCUS_TEXT, false);

        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, rounded(C_FOCUS, dp(8)));
        bg.addState(new int[]{android.R.attr.state_pressed}, rounded(C_FOCUS, dp(8)));
        bg.addState(new int[]{}, rounded(color, dp(8)));
        b.setBackground(bg);

        // YouTube-like focus: white key, slight zoom, lifted above its neighbours
        b.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(90).start();
            if (Build.VERSION.SDK_INT >= 21) {
                v.setTranslationZ(focused ? dp(8) : 0);
            }
        });
        b.setOnClickListener(click);
        return b;
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setWeightSum(mCols);
        row.setClipChildren(false);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, mRowH + dp(6)));
        return row;
    }

    private LinearLayout.LayoutParams weighted(float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        return lp;
    }

    /** Rebuild the keys (layer / shift changed) and keep D-pad focus where it was. */
    private void rebuild(String focusTag) {
        int fr = -1;
        int fi = -1;
        for (int r = 0; r < mKeys.getChildCount() && fr < 0; r++) {
            List<View> f = focusables((ViewGroup) mKeys.getChildAt(r));
            for (int i = 0; i < f.size(); i++) {
                if (f.get(i).hasFocus()) {
                    fr = r;
                    fi = i;
                    break;
                }
            }
        }

        buildKeys();

        View target = focusTag != null ? mKeys.findViewWithTag(focusTag) : null;
        if (target == null && fr >= 0 && fr < mKeys.getChildCount()) {
            List<View> f = focusables((ViewGroup) mKeys.getChildAt(fr));
            if (!f.isEmpty()) {
                target = f.get(Math.min(fi, f.size() - 1));
            }
        }
        if (target != null) {
            target.requestFocus();
        }
    }

    private static List<View> focusables(ViewGroup row) {
        List<View> out = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            if (v.isFocusable()) {
                out.add(v);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ text editing

    private void insert(String s) {
        mText.insert(mCaret, s);
        mCaret += s.length();
        refresh(true);
    }

    private void backspace() {
        if (mCaret <= 0) {
            return;
        }
        int n = Character.charCount(Character.codePointBefore(mText, mCaret));
        mText.delete(mCaret - n, mCaret);
        mCaret -= n;
        refresh(true);
    }

    private void deleteForward() {
        if (mCaret >= mText.length()) {
            return;
        }
        int n = Character.charCount(Character.codePointAt(mText, mCaret));
        mText.delete(mCaret, mCaret + n);
        refresh(true);
    }

    private void deleteWord() {
        int end = mCaret;
        while (mCaret > 0 && Character.isWhitespace(mText.charAt(mCaret - 1))) {
            mCaret--;
        }
        while (mCaret > 0 && !Character.isWhitespace(mText.charAt(mCaret - 1))) {
            mCaret--;
        }
        mText.delete(mCaret, end);
        refresh(true);
    }

    private void moveCaret(int delta) {
        mCaret = Math.max(0, Math.min(mText.length(), mCaret + delta));
        refresh(false);
    }

    private void refresh(boolean save) {
        if (mText.length() == 0) {
            mDisplay.setTextColor(0xFF777777);
            mDisplay.setText(mHint.isEmpty() ? "\u2502" : mHint);
        } else {
            mDisplay.setTextColor(C_TEXT);
            SpannableStringBuilder sb = new SpannableStringBuilder(mText);
            sb.insert(mCaret, "\u2502");
            sb.setSpan(new ForegroundColorSpan(mAccent), mCaret, mCaret + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            mDisplay.setText(sb);
        }
        if (save) {
            // draft: survives Back / Cancel / app restart
            mPrefs.edit().putString(PREF_DRAFT + mTitle, mText.toString()).apply();
        }
    }

    private void submit() {
        String text = mText.toString();
        mPrefs.edit().putString(PREF_DRAFT + mTitle, text).apply();
        if (mOnSubmit == null || mOnSubmit.onSubmit(text)) {
            mDialog.dismiss();
        }
    }

    /** Physical keyboard / remote digits typed straight into the box. */
    private boolean onDialogKey(int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
                backspace();
                return true;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                deleteForward();
                return true;
            case KeyEvent.KEYCODE_SPACE:
                insert(" ");
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_TAB:
            case KeyEvent.KEYCODE_MENU:
                return false; // normal navigation / click on the focused key
            default:
                break;
        }
        if (event.isPrintingKey()) {
            int u = event.getUnicodeChar();
            if (u >= 32 && (u & KeyCharacterMap.COMBINING_ACCENT) == 0) {
                insert(new String(Character.toChars(u)));
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    private static Typeface medium() {
        return Build.VERSION.SDK_INT >= 16 ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT_BOLD;
    }

    private GradientDrawable rounded(int color, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    private int dp(int v) {
        return (int) (v * mDensity + 0.5f);
    }

    // ------------------------------------------------------------------ key view with vector icons

    /** A key that shows either text or an icon drawn with Canvas (identical on every device, unlike font glyphs). */
    private static final class KeyView extends TextView {
        static final int NONE = 0;
        static final int BACKSPACE = 1;
        static final int SHIFT = 2;
        static final int SEARCH = 3;
        static final int GLOBE = 4;
        static final int LEFT = 5;
        static final int RIGHT = 6;
        static final int CLOSE = 7;

        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final RectF mOval = new RectF();
        private int mIcon = NONE;
        private int mIconColor = Color.WHITE;
        private int mIconColorFocused = Color.BLACK;
        private boolean mFilled;
        private int mTextColor = Color.WHITE;
        private final int mTextColorFocused = C_FOCUS_TEXT;

        KeyView(Context context) {
            super(context);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            mPaint.setStrokeWidth(2f);
        }

        void setIcon(int icon, int color, int focusedColor, boolean filled) {
            mIcon = icon;
            mIconColor = color;
            mIconColorFocused = focusedColor;
            mFilled = filled;
            invalidate();
        }

        void setNormalTextColor(int color) {
            mTextColor = color;
            setTextColor(new ColorStateList(
                    new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                    new int[]{mTextColorFocused, mTextColorFocused, color}));
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (mIcon == NONE) {
                return;
            }

            boolean hot = isFocused() || isPressed();
            mPaint.setColor(hot ? mIconColorFocused : mIconColor);
            mPaint.setStyle(mFilled ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);

            // icons are designed on a 24x24 grid
            float size = Math.min(getWidth(), getHeight()) * 0.5f;
            float s = size / 24f;
            canvas.save();
            canvas.translate(getScrollX(), getScrollY()); // TextView scrolls its own content; undo it
            canvas.translate(getWidth() / 2f, getHeight() / 2f);
            canvas.scale(s, s);
            canvas.translate(-12f, -12f);
            mPaint.setStrokeWidth(2.1f);
            mPath.reset();

            switch (mIcon) {
                case BACKSPACE:
                    mPaint.setStyle(Paint.Style.STROKE);
                    mPath.moveTo(9f, 5f);
                    mPath.lineTo(21f, 5f);
                    mPath.lineTo(21f, 19f);
                    mPath.lineTo(9f, 19f);
                    mPath.lineTo(2.5f, 12f);
                    mPath.close();
                    canvas.drawPath(mPath, mPaint);
                    canvas.drawLine(12.5f, 9.5f, 17.5f, 14.5f, mPaint);
                    canvas.drawLine(17.5f, 9.5f, 12.5f, 14.5f, mPaint);
                    break;
                case SHIFT:
                    mPath.moveTo(12f, 3.5f);
                    mPath.lineTo(3.5f, 13f);
                    mPath.lineTo(8.5f, 13f);
                    mPath.lineTo(8.5f, 20f);
                    mPath.lineTo(15.5f, 20f);
                    mPath.lineTo(15.5f, 13f);
                    mPath.lineTo(20.5f, 13f);
                    mPath.close();
                    canvas.drawPath(mPath, mPaint);
                    break;
                case SEARCH:
                    mPaint.setStyle(Paint.Style.STROKE);
                    mPaint.setStrokeWidth(2.4f);
                    canvas.drawCircle(10f, 10f, 6.2f, mPaint);
                    canvas.drawLine(14.6f, 14.6f, 20.5f, 20.5f, mPaint);
                    break;
                case GLOBE:
                    mPaint.setStyle(Paint.Style.STROKE);
                    canvas.drawCircle(12f, 12f, 8.5f, mPaint);
                    mOval.set(7.8f, 3.5f, 16.2f, 20.5f);
                    canvas.drawOval(mOval, mPaint);
                    canvas.drawLine(3.5f, 12f, 20.5f, 12f, mPaint);
                    break;
                case LEFT:
                    mPaint.setStyle(Paint.Style.STROKE);
                    mPaint.setStrokeWidth(2.6f);
                    mPath.moveTo(14.5f, 5.5f);
                    mPath.lineTo(8f, 12f);
                    mPath.lineTo(14.5f, 18.5f);
                    canvas.drawPath(mPath, mPaint);
                    break;
                case RIGHT:
                    mPaint.setStyle(Paint.Style.STROKE);
                    mPaint.setStrokeWidth(2.6f);
                    mPath.moveTo(9.5f, 5.5f);
                    mPath.lineTo(16f, 12f);
                    mPath.lineTo(9.5f, 18.5f);
                    canvas.drawPath(mPath, mPaint);
                    break;
                case CLOSE:
                    mPaint.setStyle(Paint.Style.STROKE);
                    mPaint.setStrokeWidth(2.6f);
                    canvas.drawLine(6f, 6f, 18f, 18f, mPaint);
                    canvas.drawLine(18f, 6f, 6f, 18f, mPaint);
                    break;
                default:
                    break;
            }
            canvas.restore();
        }
    }
}
