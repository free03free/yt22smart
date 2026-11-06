package com.liskovsoft.smartyoutubetv2.common.utils;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
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

    private static final float COLS = 12f;
    private static final int C_BG = 0xFF1E1E1E;
    private static final int C_BOX = 0xFF101010;
    private static final int C_KEY = 0xFF3A3A3A;
    private static final int C_SPECIAL = 0xFF555555;
    private static final int C_OK = 0xFF2E7D32;
    private static final int C_ACCENT = 0xFFFFB300;
    private static final String TATWEEL = "\u0640";

    private static final String[] DIGITS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};

    private static final String[][] EN = {
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l", "'"},
            {"z", "x", "c", "v", "b", "n", "m", ",", "."}
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
    private final SharedPreferences mPrefs;
    private final StringBuilder mText = new StringBuilder();
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
        try {
            new InAppKeyboardDialog(context, title, hint, initial, onSubmit).open(onDismiss);
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

    private InAppKeyboardDialog(Context context, String title, String hint, String initial, OnSubmit onSubmit) {
        mContext = context;
        mTitle = title == null ? "" : title;
        mHint = hint == null ? "" : hint;
        mOnSubmit = onSubmit;
        mPrefs = prefs(context);

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
        }

        mDialog.show();

        if (window != null) {
            DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
            int width = (int) Math.min(dm.widthPixels * 0.96f, dp(1000));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        View space = mKeys.findViewWithTag("space");
        if (space != null) {
            space.requestFocus();
        }
    }

    private View buildRoot() {
        LinearLayout root = new LinearLayout(mContext);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(10), dp(14), dp(12));
        root.setBackground(rounded(C_BG, dp(16)));
        // keys keep their physical positions whatever the app language direction is
        if (Build.VERSION.SDK_INT >= 17) {
            root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        }

        // title + close
        LinearLayout top = new LinearLayout(mContext);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(mContext);
        title.setText(mTitle);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button close = key("\u2715", C_SPECIAL, v -> mDialog.dismiss());
        top.addView(close, new LinearLayout.LayoutParams(dp(52), dp(40)));
        root.addView(top);

        // text box
        mDisplay = new TextView(mContext);
        mDisplay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        mDisplay.setTextColor(Color.WHITE);
        mDisplay.setGravity(Gravity.CENTER_VERTICAL);
        mDisplay.setPadding(dp(14), dp(6), dp(14), dp(6));
        mDisplay.setMinHeight(dp(56));
        mDisplay.setSingleLine(false);
        mDisplay.setMaxLines(2);
        if (Build.VERSION.SDK_INT >= 17) {
            mDisplay.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            mDisplay.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START); // Arabic text hugs the right, English the left
        }
        GradientDrawable box = rounded(C_BOX, dp(10));
        box.setStroke(dp(2), C_KEY);
        mDisplay.setBackground(box);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.setMargins(0, dp(6), 0, dp(8));
        root.addView(mDisplay, boxLp);

        mKeys = new LinearLayout(mContext);
        mKeys.setOrientation(LinearLayout.VERTICAL);
        root.addView(mKeys, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildKeys();
        refresh(false);
        return root;
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

        // digits row (all layers)
        mKeys.addView(row(DIGITS, 0, 0, false));

        String[][] rows = letterRows();
        for (int r = 0; r < rows.length; r++) {
            boolean last = r == rows.length - 1;
            if (!last) {
                mKeys.addView(row(rows[r], 0, 0, false));
                continue;
            }
            float free = COLS - rows[r].length;
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
            Button shift = key("\u21E7", mShift ? C_ACCENT : C_SPECIAL, v -> {
                mShift = !mShift;
                rebuild("shift");
            });
            shift.setTag("shift");
            if (mShift) {
                shift.setTextColor(Color.BLACK);
            }
            row.addView(shift, weighted(shiftWeight));
            used += shiftWeight;
        }
        for (final String c : chars) {
            row.addView(charKey(c), weighted(1f));
            used += 1f;
        }
        if (withBackspace) {
            Button back = key("\u232B", C_SPECIAL, v -> backspace());
            back.setOnLongClickListener(v -> {
                deleteWord();
                return true;
            });
            row.addView(back, weighted(backWeight));
            used += backWeight;
        }
        if (!withBackspace && used < COLS) { // centre short rows
            float gap = (COLS - used) / 2f;
            row.addView(spacer(), 0, weighted(gap));
            row.addView(spacer(), weighted(gap));
        }
        return row;
    }

    private LinearLayout bottomRow() {
        LinearLayout row = newRow();

        Button sym = key(mLayer == LAYER_SYM ? "ABC" : "\u061F123", C_SPECIAL, v -> {
            mLayer = mLayer == LAYER_SYM ? mLetters : LAYER_SYM;
            mShift = false;
            rebuild("sym");
        });
        sym.setTag("sym");
        row.addView(sym, weighted(1.5f));

        // label = the language you will switch to
        Button lang = key(mLetters == LAYER_EN ? "\u0639" : "EN", C_SPECIAL, v -> {
            mLetters = mLetters == LAYER_EN ? LAYER_AR : LAYER_EN;
            mLayer = mLetters;
            mShift = false;
            mPrefs.edit().putBoolean(PREF_LANG, mLetters == LAYER_AR).apply();
            rebuild("lang");
        });
        lang.setTag("lang");
        row.addView(lang, weighted(1.5f));

        row.addView(key("\u25C0", C_SPECIAL, v -> moveCaret(-1)), weighted(1f));

        Button space = key(" ", C_KEY, v -> insert(" "));
        space.setTag("space");
        row.addView(space, weighted(3.5f));

        row.addView(key("\u25B6", C_SPECIAL, v -> moveCaret(1)), weighted(1f));

        row.addView(key(mContext.getString(R.string.inapp_kb_clear), C_SPECIAL, v -> {
            mText.setLength(0);
            mCaret = 0;
            refresh(true);
        }), weighted(1.75f));

        Button ok = key("\u2713 " + mContext.getString(android.R.string.ok), C_OK, v -> submit());
        ok.setTag("ok");
        row.addView(ok, weighted(1.75f));
        return row;
    }

    private Button charKey(final String label) {
        String shown = label;
        String value = label;
        if (mLayer == LAYER_EN && mShift) {
            shown = value = label.toUpperCase(Locale.ENGLISH);
        } else if (label.length() == 1 && Character.getType(label.charAt(0)) == Character.NON_SPACING_MARK) {
            shown = TATWEEL + label; // show a combining mark on a carrier so it is visible
        }
        final String insertValue = value;
        return key(shown, C_KEY, v -> {
            insert(insertValue);
            if (mShift) { // one-shot shift
                mShift = false;
                rebuild(null);
            }
        });
    }

    private Button key(String label, int color, View.OnClickListener click) {
        Button b = new Button(mContext);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setSingleLine(true);
        b.setFocusable(true);
        b.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}},
                new int[]{Color.BLACK, Color.BLACK, Color.WHITE}));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, rounded(C_ACCENT, dp(8)));
        bg.addState(new int[]{android.R.attr.state_pressed}, rounded(C_ACCENT, dp(8)));
        bg.addState(new int[]{}, rounded(color, dp(8)));
        b.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) {
            b.setStateListAnimator(null);
        }
        b.setOnClickListener(click);
        return b;
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setWeightSum(COLS);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return row;
    }

    private View spacer() {
        View v = new View(mContext);
        v.setFocusable(false);
        return v;
    }

    private LinearLayout.LayoutParams weighted(float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
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
            mDisplay.setTextColor(0xFF808080);
            mDisplay.setText(mHint.isEmpty() ? "\u2502" : mHint);
        } else {
            mDisplay.setTextColor(Color.WHITE);
            SpannableStringBuilder sb = new SpannableStringBuilder(mText);
            sb.insert(mCaret, "\u2502");
            sb.setSpan(new ForegroundColorSpan(C_ACCENT), mCaret, mCaret + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
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

    private GradientDrawable rounded(int color, int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, mContext.getResources().getDisplayMetrics());
    }
}
