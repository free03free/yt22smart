package com.liskovsoft.smartyoutubetv2.common.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Phone-style keyboard (Arabic / English / symbols) drawn by the app itself, so the device keyboard is never used.
 * <p>
 * The view only produces keystrokes: the text lives in the {@link Host} (a search box on the screen, a dialog field...),
 * so the host can react to every letter (e.g. show YouTube search suggestions while typing).
 * Look: white keys on a light grey sheet, digit hints on the top row (long press types the digit), grey function keys,
 * wide space bar showing the language, blue action key. Works with touch, TV remote (focused key turns dark and zooms)
 * and a physical keyboard.
 */
public final class InAppKeyboardView extends LinearLayout {
    public interface Host {
        /** Current text (the keyboard always edits the end of it). */
        String getText();

        void setText(String text);

        /** Action key pressed. */
        void onSubmit();

        /** Back pressed while the keyboard has focus. */
        void onClose();
    }

    private static final String PREFS = "in_app_keyboard";
    private static final String PREF_LANG = "lang_ar";
    private static final String PREF_DRAFT = "draft:";

    private static final int LAYER_EN = 0;
    private static final int LAYER_AR = 1;
    private static final int LAYER_SYM = 2;

    // phone-keyboard palette
    static final int C_SHEET = 0xFFD3D6DB;
    static final int C_KEY = 0xFFFFFFFF;
    static final int C_KEY_SPECIAL = 0xFFADB2BA;
    static final int C_KEY_PRESSED = 0xFFE4E6EA;
    static final int C_SHADOW = 0xFF9EA3AB;
    static final int C_TEXT = 0xFF1F1F1F;
    static final int C_HINT = 0xFF6B7078;
    static final int C_FOCUS = 0xFF202124;
    static final int C_FOCUS_TEXT = 0xFFFFFFFF;
    static final int C_BLUE = 0xFF2F6FE0;
    static final int C_BLUE_FOCUS = 0xFF1B4BA8;
    private static final String TATWEEL = "\u0640";

    private static final String[] HINTS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};

    private static final String[][] EN = {
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
            {"z", "x", "c", "v", "b", "n", "m"}
    };

    private static final String[][] AR = {
            {"ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج"},
            {"ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط"},
            {"ذ", "د", "ز", "ر", "و", "ة", "ى", "ظ", "ء"}
    };

    /** Arabic "shift" layer: diacritics, hamza forms and extra letters. */
    private static final String[][] AR_SHIFT = {
            {"\u064E", "\u064B", "\u064F", "\u064C", "\u0650", "\u064D", "\u0651", "\u0652", TATWEEL, "\u0670", "\u0653"},
            {"أ", "إ", "آ", "ؤ", "ئ", "لا", "«", "»", ":", "؛", "!"},
            {"ڤ", "پ", "چ", "گ", "ژ", "ڨ", "؟", "٪", "٫"}
    };

    private static final String[][] SYM = {
            {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"},
            {"@", "#", "$", "%", "&", "*", "-", "+", "(", ")"},
            {"!", "\"", "'", ":", ";", "/", "?", "_"}
    };

    private final Host mHost;
    private final boolean mSearchStyle;
    private final SharedPreferences mPrefs;
    private final float mDensity;
    private final int mRowH;
    private float mCols = 10f;
    private int mLetters;   // LAYER_EN or LAYER_AR: the last letters layout used
    private int mLayer;     // what is shown now
    private boolean mShift;

    public InAppKeyboardView(Context context, Host host, boolean searchStyle, int rowHeightPx) {
        super(context);
        mHost = host;
        mSearchStyle = searchStyle;
        mRowH = rowHeightPx;
        mPrefs = prefs(context);
        mDensity = context.getResources().getDisplayMetrics().density;

        boolean arabicDefault = "ar".equals(Locale.getDefault().getLanguage());
        mLetters = mPrefs.getBoolean(PREF_LANG, arabicDefault) ? LAYER_AR : LAYER_EN;
        mLayer = mLetters;

        setOrientation(VERTICAL);
        setClipChildren(false);
        setClipToPadding(false);
        // keys keep their physical positions whatever the app language direction is
        if (Build.VERSION.SDK_INT >= 17) {
            setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        }
        buildKeys();
    }

    // ------------------------------------------------------------------ public helpers

    /** Light grey rounded sheet behind the keys (when the view is used on its own, not inside a dialog). */
    public void applySheetBackground(int bottomInsetPx) {
        GradientDrawable sheet = new GradientDrawable();
        sheet.setColor(C_SHEET);
        float r = dp(16);
        sheet.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        setBackground(sheet);
        setPadding(dp(8), dp(10), dp(8), dp(8) + bottomInsetPx);
    }

    /** Move D-pad focus to the space bar. */
    public void focusSpace() {
        View space = findViewWithTag("space");
        if (space != null) {
            space.requestFocus();
        }
    }

    public static String getDraft(Context context, String key) {
        return prefs(context).getString(PREF_DRAFT + key, "");
    }

    public static void saveDraft(Context context, String key, String text) {
        prefs(context).edit().putString(PREF_DRAFT + key, text == null ? "" : text).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Current display size - follows rotation (the app resources may still report the old orientation). */
    public static DisplayMetrics screenMetrics(Context context) {
        DisplayMetrics dm = new DisplayMetrics();
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            wm.getDefaultDisplay().getMetrics(dm);
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                return dm;
            }
        } catch (RuntimeException e) {
            // fall through
        }
        return context.getResources().getDisplayMetrics();
    }

    /** The app draws under the translucent system navigation bar (phones, portrait): keep the bottom row above it. */
    public static int navigationBarInset(Context context, DisplayMetrics dm) {
        try {
            if (isLeanback(context) || dm.heightPixels <= dm.widthPixels) {
                return 0;
            }
            int id = context.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            return id > 0 ? context.getResources().getDimensionPixelSize(id) : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public static boolean isLeanback(Context context) {
        try {
            return context.getPackageManager().hasSystemFeature("android.software.leanback");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Key height: as tall as the screen allows (720p TV / landscape phone is only ~360dp high) but never much taller
     * than wide. 4 rows must fit into {@code heightFraction} of the screen after {@code reservedDp}.
     */
    public static int rowHeightPx(Context context, DisplayMetrics dm, float reservedDp, float heightFraction) {
        float density = context.getResources().getDisplayMetrics().density;
        float heightDp = dm.heightPixels / density;
        float widthDp = Math.min(dm.widthPixels / density, 1100f);
        float rowDp = (heightDp * heightFraction - reservedDp) / 4f - 6f;
        float keyWidthDp = (widthDp - 16f) / 11f;
        rowDp = Math.max(30f, Math.min(Math.min(58f, keyWidthDp * 1.5f), rowDp));
        return (int) (rowDp * density + 0.5f);
    }

    /** Sheet width: full width on phones, keys kept close to square on wide screens. */
    public static int panelWidthPx(Context context, DisplayMetrics dm, int rowHeightPx) {
        float density = context.getResources().getDisplayMetrics().density;
        float rowDp = rowHeightPx / density;
        int wantedPx = (int) (Math.min(1100f, rowDp * 1.45f * 11f + 16f) * density + 0.5f);
        return Math.min(dm.widthPixels, wantedPx);
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
        removeAllViews();
        mCols = mLayer == LAYER_AR ? 11f : 10f;

        String[][] rows = letterRows();
        for (int r = 0; r < rows.length; r++) {
            boolean last = r == rows.length - 1;
            // digit hints sit on the first letter row (not in the symbols layer / Arabic shift layer)
            boolean hints = r == 0 && mLayer != LAYER_SYM && !(mLayer == LAYER_AR && mShift);
            if (!last) {
                addView(row(rows[r], 0, 0, false, hints));
                continue;
            }
            float free = mCols - rows[r].length;
            if (mLayer == LAYER_SYM) {
                addView(row(rows[r], 0, free, true, false));
            } else {
                addView(row(rows[r], free / 2f, free / 2f, true, false));
            }
        }

        addView(bottomRow());
    }

    /** One row of character keys; optional shift (left) / backspace (right) with the given weights. */
    private LinearLayout row(String[] chars, float shiftWeight, float backWeight, boolean withBackspace, boolean hints) {
        LinearLayout row = newRow();
        float used = 0;
        if (withBackspace && shiftWeight > 0) {
            KeyView shift = newKey("", KeyView.SHIFT, mShift ? C_KEY : C_KEY_SPECIAL, C_FOCUS, v -> {
                mShift = !mShift;
                rebuild("shift");
            });
            shift.setTag("shift");
            if (mShift) { // active: white key, filled arrow
                shift.setIcon(KeyView.SHIFT, C_TEXT, C_FOCUS_TEXT, true);
            }
            row.addView(shift, weighted(shiftWeight));
            used += shiftWeight;
        }
        for (int i = 0; i < chars.length; i++) {
            row.addView(charKey(chars[i], hints && i < HINTS.length ? HINTS[i] : null), weighted(1f));
            used += 1f;
        }
        if (withBackspace) {
            KeyView back = newKey("", KeyView.BACKSPACE, C_KEY_SPECIAL, C_FOCUS, v -> backspace());
            back.setOnLongClickListener(v -> {
                deleteWord();
                return true;
            });
            row.addView(back, weighted(backWeight));
            used += backWeight;
        } else if (used < mCols) { // centre short rows (QWERTY middle row)
            float gap = (mCols - used) / 2f;
            row.addView(spacer(), 0, weighted(gap));
            row.addView(spacer(), weighted(gap));
        }
        return row;
    }

    private LinearLayout bottomRow() {
        LinearLayout row = newRow();

        String lettersLabel = mLetters == LAYER_AR ? "\u0623\u0628\u062C" : "ABC";
        KeyView sym = newKey(mLayer == LAYER_SYM ? lettersLabel : "?123", KeyView.NONE, C_KEY_SPECIAL, C_FOCUS, v -> {
            mLayer = mLayer == LAYER_SYM ? mLetters : LAYER_SYM;
            mShift = false;
            rebuild("sym");
        });
        sym.setTag("sym");
        sym.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textDp() * 0.72f);
        row.addView(sym, weighted(1.5f));

        final String comma = mLetters == LAYER_AR && mLayer != LAYER_SYM ? "\u060C" : ",";
        row.addView(newKey(comma, KeyView.NONE, C_KEY_SPECIAL, C_FOCUS, v -> insert(comma)), weighted(1f));

        KeyView lang = newKey("", KeyView.GLOBE, C_KEY, C_FOCUS, v -> {
            mLetters = mLetters == LAYER_EN ? LAYER_AR : LAYER_EN;
            mLayer = mLetters;
            mShift = false;
            mPrefs.edit().putBoolean(PREF_LANG, mLetters == LAYER_AR).apply();
            rebuild("lang");
        });
        lang.setTag("lang");
        row.addView(lang, weighted(1f));

        // the space bar: wide, shows the current language
        String langName = mLayer == LAYER_SYM ? "" : (mLetters == LAYER_AR ? "\u0627\u0644\u0639\u0631\u0628\u064A\u0629" : "English");
        KeyView space = newKey(langName, KeyView.NONE, C_KEY, C_FOCUS, v -> insert(" "));
        space.setTag("space");
        space.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textDp() * 0.7f);
        space.setColors(C_HINT, C_FOCUS_TEXT);
        row.addView(space, weighted(mCols - 6.5f));

        row.addView(newKey(".", KeyView.NONE, C_KEY_SPECIAL, C_FOCUS, v -> insert(".")), weighted(1f));

        // action key (blue): magnifier for search, enter arrow otherwise
        int icon = mSearchStyle ? KeyView.SEARCH : KeyView.ENTER;
        KeyView ok = newKey("", icon, C_BLUE, C_BLUE_FOCUS, v -> mHost.onSubmit());
        ok.setTag("ok");
        ok.setIcon(icon, Color.WHITE, Color.WHITE, false);
        row.addView(ok, weighted(2f));
        return row;
    }

    private KeyView charKey(final String label, final String hint) {
        String shown = label;
        String value = label;
        if (mLayer == LAYER_EN && mShift) {
            shown = value = label.toUpperCase(Locale.ENGLISH);
        } else if (label.length() == 1 && Character.getType(label.charAt(0)) == Character.NON_SPACING_MARK) {
            shown = TATWEEL + label; // show a combining mark on a carrier so it is visible
        }
        final String insertValue = value;
        KeyView k = newKey(shown, KeyView.NONE, C_KEY, C_FOCUS, v -> {
            insert(insertValue);
            if (mShift) { // one-shot shift
                mShift = false;
                rebuild(null);
            }
        });
        if (hint != null) { // small digit on the key; long press types it
            k.setHintText(hint);
            k.setOnLongClickListener(v -> {
                insert(hint);
                return true;
            });
        }
        return k;
    }

    float textDp() {
        return mRowH / mDensity * 0.5f;
    }

    /** Creates a key styled like the rest of the keyboard (also used by the dialog for its own buttons). */
    KeyView newKey(String label, int icon, int face, int focusFace, View.OnClickListener click) {
        KeyView b = new KeyView(getContext());
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, textDp());
        b.setTypeface(medium());
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setIncludeFontPadding(false);
        b.setPadding(0, 0, 0, 0);
        b.setFocusable(true);
        b.setClickable(true);
        b.setColors(C_TEXT, C_FOCUS_TEXT);
        b.setIcon(icon, C_TEXT, C_FOCUS_TEXT, false);

        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, keyFace(focusFace));
        bg.addState(new int[]{android.R.attr.state_pressed}, keyFace(face == C_KEY ? C_KEY_PRESSED : focusFace));
        bg.addState(new int[]{}, keyFace(face));
        b.setBackground(bg);

        // TV focus: dark key, slight zoom, lifted above its neighbours
        b.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(90).start();
            if (Build.VERSION.SDK_INT >= 21) {
                v.setTranslationZ(focused ? dp(8) : 0);
            }
        });
        b.setOnClickListener(click);
        return b;
    }

    /** Rounded key face with a thin darker edge underneath, like a phone keyboard key. */
    private Drawable keyFace(int color) {
        GradientDrawable shadow = rounded(C_SHADOW, dp(7));
        GradientDrawable face = rounded(color, dp(7));
        LayerDrawable layers = new LayerDrawable(new Drawable[]{shadow, face});
        layers.setLayerInset(1, 0, 0, 0, dp(2));
        return layers;
    }

    private LinearLayout newRow() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setWeightSum(mCols);
        row.setClipChildren(false);
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, mRowH + dp(6)));
        return row;
    }

    private View spacer() {
        View v = new View(getContext());
        v.setFocusable(false);
        return v;
    }

    private LinearLayout.LayoutParams weighted(float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight);
        lp.setMargins(dp(2), dp(3), dp(2), dp(3));
        return lp;
    }

    /** Rebuild the keys (layer / shift changed) and keep D-pad focus where it was. */
    private void rebuild(String focusTag) {
        int fr = -1;
        int fi = -1;
        for (int r = 0; r < getChildCount() && fr < 0; r++) {
            List<View> f = focusables((ViewGroup) getChildAt(r));
            for (int i = 0; i < f.size(); i++) {
                if (f.get(i).hasFocus()) {
                    fr = r;
                    fi = i;
                    break;
                }
            }
        }

        buildKeys();

        View target = focusTag != null ? findViewWithTag(focusTag) : null;
        if (target == null && fr >= 0 && fr < getChildCount()) {
            List<View> f = focusables((ViewGroup) getChildAt(fr));
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

    // ------------------------------------------------------------------ text editing (always at the end of the host text)

    private String text() {
        String t = mHost.getText();
        return t == null ? "" : t;
    }

    void insert(String s) {
        mHost.setText(text() + s);
    }

    void backspace() {
        String t = text();
        if (t.isEmpty()) {
            return;
        }
        int n = Character.charCount(t.codePointBefore(t.length()));
        mHost.setText(t.substring(0, t.length() - n));
    }

    private void deleteWord() {
        String t = text();
        int start = t.length();
        while (start > 0 && Character.isWhitespace(t.charAt(start - 1))) {
            start--;
        }
        while (start > 0 && !Character.isWhitespace(t.charAt(start - 1))) {
            start--;
        }
        mHost.setText(t.substring(0, start));
    }

    // ------------------------------------------------------------------ hardware keys

    /** Physical keyboard / remote digits typed straight into the text. */
    public boolean handlePhysicalKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL:
                backspace();
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

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (hasFocus()) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    mHost.onClose();
                }
                return true; // Back closes the keyboard only, never the screen behind it
            }
            if (handlePhysicalKey(event)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
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

    // ------------------------------------------------------------------ key view with vector icons and digit hint

    /** A key that shows text and/or an icon drawn with Canvas (identical on every device, unlike font glyphs). */
    static final class KeyView extends TextView {
        static final int NONE = 0;
        static final int BACKSPACE = 1;
        static final int SHIFT = 2;
        static final int SEARCH = 3;
        static final int GLOBE = 4;
        static final int CLOSE = 7;
        static final int ENTER = 8;
        static final int CLEAR = 9;

        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mHintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final RectF mOval = new RectF();
        private int mIcon = NONE;
        private int mIconColor = Color.BLACK;
        private int mIconColorFocused = Color.WHITE;
        private boolean mFilled;
        private String mHintText;

        KeyView(Context context) {
            super(context);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            mPaint.setStrokeWidth(2f);
            mHintPaint.setTextAlign(Paint.Align.RIGHT);
        }

        void setIcon(int icon, int color, int focusedColor, boolean filled) {
            mIcon = icon;
            mIconColor = color;
            mIconColorFocused = focusedColor;
            mFilled = filled;
            invalidate();
        }

        void setHintText(String hint) {
            mHintText = hint;
            invalidate();
        }

        void setColors(int normal, int focused) {
            setTextColor(new ColorStateList(
                    new int[][]{{android.R.attr.state_focused}, {}},
                    new int[]{focused, normal}));
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (mIcon == NONE && mHintText == null) {
                return;
            }

            boolean hot = isFocused();
            canvas.save();
            canvas.translate(getScrollX(), getScrollY()); // TextView scrolls its own content; undo it

            if (mHintText != null) {
                mHintPaint.setTextSize(getHeight() * 0.25f);
                mHintPaint.setColor(hot ? 0xFFCCCCCC : C_HINT);
                canvas.drawText(mHintText, getWidth() - getWidth() * 0.1f, getHeight() * 0.3f, mHintPaint);
            }

            if (mIcon != NONE) {
                mPaint.setColor(hot ? mIconColorFocused : mIconColor);
                mPaint.setStyle(mFilled ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
                // icons are designed on a 24x24 grid
                float size = Math.min(getWidth(), getHeight()) * (mIcon == CLEAR ? 0.55f : 0.5f);
                float s = size / 24f;
                canvas.translate(getWidth() / 2f, getHeight() / 2f);
                canvas.scale(s, s);
                canvas.translate(-12f, -12f);
                mPaint.setStrokeWidth(2.1f);
                mPath.reset();
                drawIcon(canvas);
            }
            canvas.restore();
        }

        private void drawIcon(Canvas canvas) {
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
                case ENTER:
                    mPaint.setStyle(Paint.Style.STROKE);
                    mPaint.setStrokeWidth(2.4f);
                    mPath.moveTo(19f, 5f);
                    mPath.lineTo(19f, 13f);
                    mPath.lineTo(5.5f, 13f);
                    canvas.drawPath(mPath, mPaint);
                    canvas.drawLine(5.5f, 13f, 10.5f, 8f, mPaint);
                    canvas.drawLine(5.5f, 13f, 10.5f, 18f, mPaint);
                    break;
                case CLOSE:
                    mPaint.setStyle(Paint.Style.STROKE);
                    mPaint.setStrokeWidth(2.6f);
                    canvas.drawLine(6f, 6f, 18f, 18f, mPaint);
                    canvas.drawLine(18f, 6f, 6f, 18f, mPaint);
                    break;
                case CLEAR:
                    mPaint.setStyle(Paint.Style.FILL);
                    canvas.drawCircle(12f, 12f, 10f, mPaint);
                    mPaint.setColor(Color.WHITE);
                    mPaint.setStyle(Paint.Style.STROKE);
                    mPaint.setStrokeWidth(2.2f);
                    canvas.drawLine(8f, 8f, 16f, 16f, mPaint);
                    canvas.drawLine(16f, 8f, 8f, 16f, mPaint);
                    break;
                default:
                    break;
            }
        }
    }
}
