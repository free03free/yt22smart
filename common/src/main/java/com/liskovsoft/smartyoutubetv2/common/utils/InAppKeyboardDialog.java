package com.liskovsoft.smartyoutubetv2.common.utils;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.utils.InAppKeyboardView.KeyView;

/**
 * Dialog with its own text field above the in-app keyboard ({@link InAppKeyboardView}).
 * <p>
 * Why: with the system keyboard, leaving it (Back / losing focus) closes the dialog and the typed text is lost.
 * Here the text is saved as a "draft" after every key, so it survives closing the dialog with Back, or even
 * restarting the app. The next time a dialog with the same title is opened the last text is restored
 * (press the clear button to empty it).
 * <p>
 * For screens that have their own search box (YouTube search) the keyboard is embedded directly instead,
 * see {@link InAppKeyboardView}.
 */
public final class InAppKeyboardDialog {
    public interface OnSubmit {
        /** @return true to close the dialog, false to keep it open (e.g. invalid input) */
        boolean onSubmit(String text);
    }

    private static final int C_FIELD_HINT = 0xFF80858C;

    private final Context mContext;
    private final String mTitle;
    private final String mHint;
    private final OnSubmit mOnSubmit;
    private final boolean mSearchStyle;
    private final float mDensity;
    private final StringBuilder mText = new StringBuilder();
    private int mPanelWidth;
    private Dialog mDialog;
    private TextView mDisplay;
    private KeyView mClearButton;
    private InAppKeyboardView mKeyboard;

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
            SimpleEditDialog.showNative(context, title, hint, initial, onSubmit::onSubmit, onDismiss, false);
        }
    }

    /** Last text typed in dialogs with this title (empty if none). */
    public static String getDraft(Context context, String title) {
        return InAppKeyboardView.getDraft(context, title);
    }

    private InAppKeyboardDialog(Context context, String title, String hint, String initial, OnSubmit onSubmit, boolean search) {
        mContext = context;
        mTitle = title == null ? "" : title;
        mHint = hint == null ? "" : hint;
        mOnSubmit = onSubmit;
        mSearchStyle = search;
        mDensity = context.getResources().getDisplayMetrics().density;

        String start = initial == null ? "" : initial;
        if (start.isEmpty()) {
            start = InAppKeyboardView.getDraft(context, mTitle); // restore what was typed before
        }
        mText.append(start);
    }

    private void open(Runnable onDismiss) {
        mDialog = new Dialog(mContext);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        mDialog.setContentView(buildRoot());
        mDialog.setCanceledOnTouchOutside(false); // a stray touch must not throw the text away
        mDialog.setOnKeyListener((d, keyCode, event) -> mKeyboard.handlePhysicalKey(event));
        if (onDismiss != null) {
            mDialog.setOnDismissListener(d -> onDismiss.run());
        }

        Window window = mDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.55f);
            window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        }

        mDialog.show();

        if (window != null) {
            window.setLayout(mPanelWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        mKeyboard.focusSpace();

        // MOD: some devices place the window partly below the visible screen (bottom row cut off): measure and lift it
        if (window != null) {
            final View decor = window.getDecorView();
            decor.post(this::keepOnScreen);
            decor.addOnLayoutChangeListener((v, l, t, rr, b, ol, ot, orr, ob) -> {
                if (b - t != ob - ot) {
                    decor.post(this::keepOnScreen);
                }
            });
        }
    }

    private void keepOnScreen() {
        try {
            Window w = mDialog == null ? null : mDialog.getWindow();
            if (w == null) {
                return;
            }
            View decor = w.getDecorView();
            int[] loc = new int[2];
            decor.getLocationOnScreen(loc);
            int bottom = loc[1] + decor.getHeight();
            int limit = InAppKeyboardView.screenMetrics(mContext).heightPixels;
            Rect vis = new Rect();
            decor.getWindowVisibleDisplayFrame(vis);
            if (vis.bottom > 0) {
                limit = Math.min(limit, vis.bottom);
            }
            int overflow = bottom - limit;
            if (overflow > 0) {
                WindowManager.LayoutParams lp = w.getAttributes();
                lp.y += overflow; // gravity is BOTTOM: positive y moves the window up
                w.setAttributes(lp);
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
        }
    }

    private View buildRoot() {
        DisplayMetrics dm = InAppKeyboardView.screenMetrics(mContext);
        boolean titleRow = !mSearchStyle; // true = dialog (not search box): needs a close button
        int navPx = InAppKeyboardView.navigationBarInset(mContext, dm);
        float reserved = 20 + 58 + (titleRow ? 34 : 0) + navPx / mDensity;
        int rowH = InAppKeyboardView.rowHeightPx(mContext, dm, reserved, 0.88f);
        mPanelWidth = InAppKeyboardView.panelWidthPx(mContext, dm, rowH);

        mKeyboard = new InAppKeyboardView(mContext, new InAppKeyboardView.Host() {
            @Override
            public String getText() {
                return mText.toString();
            }

            @Override
            public void setText(String text) {
                mText.setLength(0);
                mText.append(text);
                refresh(true);
            }

            @Override
            public void onSubmit() {
                submit();
            }

            @Override
            public void onClose() {
                mDialog.dismiss();
            }
        }, mSearchStyle, rowH);

        LinearLayout root = new LinearLayout(mContext);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setClipChildren(false);
        root.setClipToPadding(false);
        root.setPadding(dp(8), dp(10), dp(8), dp(8) + navPx);
        GradientDrawable sheet = new GradientDrawable();
        sheet.setColor(InAppKeyboardView.C_SHEET);
        float r = dp(16);
        sheet.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(sheet);
        if (Build.VERSION.SDK_INT >= 17) {
            root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        }

        // text field: white pill, magnifier on the left (search), clear button on the right
        LinearLayout field = new LinearLayout(mContext);
        field.setOrientation(LinearLayout.HORIZONTAL);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setPadding(dp(14), 0, dp(8), 0);
        GradientDrawable box = new GradientDrawable();
        box.setColor(Color.WHITE);
        box.setCornerRadius(dp(28));
        field.setBackground(box);
        if (mSearchStyle) {
            KeyView icon = new KeyView(mContext);
            icon.setIcon(KeyView.SEARCH, InAppKeyboardView.C_HINT, InAppKeyboardView.C_HINT, false);
            icon.setFocusable(false);
            field.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        }
        mDisplay = new TextView(mContext);
        mDisplay.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        mDisplay.setTypeface(medium());
        mDisplay.setGravity(Gravity.CENTER_VERTICAL);
        mDisplay.setSingleLine(true);
        mDisplay.setEllipsize(TextUtils.TruncateAt.START);
        if (Build.VERSION.SDK_INT >= 17) {
            mDisplay.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            mDisplay.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START); // Arabic text hugs the right, English the left
        }
        LinearLayout.LayoutParams dispLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        dispLp.setMargins(dp(10), 0, dp(6), 0);
        field.addView(mDisplay, dispLp);
        mClearButton = new KeyView(mContext);
        mClearButton.setIcon(KeyView.CLEAR, 0xFF9AA0A6, 0xFF9AA0A6, false);
        mClearButton.setFocusable(true);
        mClearButton.setClickable(true);
        mClearButton.setOnClickListener(v -> {
            mText.setLength(0);
            refresh(true);
        });
        field.addView(mClearButton, new LinearLayout.LayoutParams(dp(40), dp(40)));
        // MOD: no separate title line (the field hint already shows it); the close button sits next to the field
        LinearLayout fieldRow = new LinearLayout(mContext);
        fieldRow.setOrientation(LinearLayout.HORIZONTAL);
        fieldRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        fieldLp.setMargins(dp(2), dp(2), dp(2), dp(8));
        fieldRow.addView(field, fieldLp);
        if (titleRow && !InAppKeyboardView.isLeanback(mContext)) { // touch devices have no Back key on screen
            KeyView close = mKeyboard.newKey("", KeyView.CLOSE, InAppKeyboardView.C_KEY_SPECIAL, InAppKeyboardView.C_FOCUS, v -> mDialog.dismiss());
            LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(44), dp(40));
            closeLp.setMargins(dp(4), dp(2), dp(2), dp(8));
            fieldRow.addView(close, closeLp);
        }
        root.addView(fieldRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(mKeyboard, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        refresh(false);
        return root;
    }

    private void refresh(boolean save) {
        if (mText.length() == 0) {
            mDisplay.setTextColor(C_FIELD_HINT);
            mDisplay.setText(mHint);
            mClearButton.setVisibility(View.INVISIBLE);
        } else {
            mDisplay.setTextColor(InAppKeyboardView.C_TEXT);
            SpannableStringBuilder sb = new SpannableStringBuilder(mText);
            int end = sb.length();
            sb.insert(end, "\u2502");
            sb.setSpan(new ForegroundColorSpan(InAppKeyboardView.C_BLUE), end, end + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            mDisplay.setText(sb);
            mClearButton.setVisibility(View.VISIBLE);
        }
        if (save) {
            // draft: survives Back / Cancel / app restart
            InAppKeyboardView.saveDraft(mContext, mTitle, mText.toString());
        }
    }

    private void submit() {
        String text = mText.toString();
        InAppKeyboardView.saveDraft(mContext, mTitle, text);
        if (mOnSubmit == null || mOnSubmit.onSubmit(text)) {
            mDialog.dismiss();
        }
    }

    private static Typeface medium() {
        return Build.VERSION.SDK_INT >= 16 ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT_BOLD;
    }

    private int dp(int v) {
        return (int) (v * mDensity + 0.5f);
    }
}
