package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.IptvSection;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvChannel;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvHealth;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvNames;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvNumbering;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvPlaylist;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvPrefs;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvStore;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvUserData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Numbered channel manager, modelled on the channel list of a satellite receiver:
 * a numbered list with favorite / lock / skip columns, groups switched with Left / Right,
 * and the four colour keys (Red = delete, Green = skip, Yellow = move, Blue = rename).
 *
 * Remote / keyboard:
 *   Up / Down          move the cursor (Page / CH +/- jump 10)
 *   Left / Right       previous / next group (All, Favorites, one tab per group)
 *   OK                 play the channel (while moving: drop it here)
 *   long OK / Menu     all actions (favorite, lock, mark, move to number, sort, search ...)
 *   0-9                type a channel number to jump to it (while moving: move the channel to that number)
 *   Red / D            delete          Green / S   skip when zapping
 *   Yellow / M         move            Blue / R    rename
 *   F favorite, L lock, Space mark, Q search
 *   Back               cancel move / clear search / clear marks / close
 *
 * Moving a channel changes the stored order of the library (or of the favorites in the Favorites tab), so the
 * order shown here is the order used everywhere else (cards, player zapping, export).
 */
public class IptvManagerActivity extends Activity {
    private static final String EXTRA_SCOPE = "iptv_mgr_scope";
    private static final String S_ALL = "all";
    private static final String S_FAV = "fav";
    private static final String S_GROUP = "g:";
    private static final String S_BUNDLE = "b:";

    private static final int ROW_DP = 46;
    private static final int PAGE_JUMP = 10;
    private static final long DIGIT_MS = 1200;

    private static final int C_BG = Color.parseColor("#0A1A33");
    private static final int C_TITLE = Color.parseColor("#5CE1E6");
    private static final int C_GOLD = Color.parseColor("#F5C542");
    private static final int C_CURSOR = Color.parseColor("#2F6FB0");
    private static final int C_CURSOR_EDGE = Color.parseColor("#C8D400");
    private static final int C_MOVING = Color.parseColor("#9A6B00");
    private static final int C_MARKED = Color.parseColor("#1D3F68");
    private static final int C_DIM = Color.parseColor("#7C8CA5");

    /** Opens the manager on the given scope: "all", "fav" or "g:" + group name. Null / unknown = all channels. */
    public static void start(Context context, String scope) {
        Intent intent = new Intent(context, IptvManagerActivity.class);
        intent.putExtra(EXTRA_SCOPE, scope);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private IptvStore mStore;
    private IptvUserData mUser;
    private IptvPrefs mPrefs;

    private final List<String> mScopes = new ArrayList<>();
    private int mScopeIdx;
    private String mFilter = "";
    private List<IptvChannel> mRows = new ArrayList<>();
    private final Set<String> mMarked = new LinkedHashSet<>();
    private Set<String> mFavSet = new HashSet<>();
    private Set<String> mLockedSet = new HashSet<>();
    private Set<String> mSkippedSet = new HashSet<>();
    /** url -> channel number shown in the first column (fixed numbers + automatic ones), library-wide. */
    private Map<String, Integer> mNumbers = new HashMap<>();
    private Set<String> mFixedSet = new HashSet<>();
    private int mCursor;
    private boolean mMoving;
    private List<IptvChannel> mMoveBackup;
    private int mMoveOrigin;
    private boolean mCenterLong;
    private boolean mDirty;
    private final StringBuilder mDigits = new StringBuilder();
    private final Runnable mCommitDigits = this::commitDigits;

    private TextView mScopeView;
    private TextView mInfoView;
    private ListView mList;
    /** Small window that keeps playing while the user browses; tap / OK on the playing channel = full screen. */
    private IptvMiniPlayer mMini;
    private boolean mResumeMini;
    /** The small window is the selected item (instead of the channel list); Back / arrows return to the channels of the group. */
    private boolean mMiniFocused;
    private RowsAdapter mAdapter;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        mStore = IptvStore.instance(this);
        mUser = IptvUserData.instance(this);
        mPrefs = IptvPrefs.instance(this);

        buildViews();
        rebuildScopes(getIntent().getStringExtra(EXTRA_SCOPE));
        reload();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (mResumeMini) { // back from full screen: the window carries on with the channel that was watched last
            mResumeMini = false;
            String key = IptvPlayerActivity.lastPlayedKey();
            if (key != null) {
                for (IptvChannel c : mStore.load().getChannels()) {
                    if (c.key().equals(key)) {
                        mMini.play(c);
                        break;
                    }
                }
            }
        }
        mMini.start();
        refresh();
    }

    @Override
    protected void onStop() {
        mMini.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mMini.close();
        mHandler.removeCallbacksAndMessages(null);
        if (mDirty) {
            // let the section cards pick up the new order / deletions
            IptvSection.onLibraryChanged(getApplicationContext());
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ views

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private TextView text(String value, int sp, int color, int gravity) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setGravity(gravity);
        view.setSingleLine(true);
        return view;
    }

    /** Aligns the text to the left edge of its (left-to-right) cell, whatever the language of the text is. */
    private static void alignToViewStart(TextView view) {
        if (android.os.Build.VERSION.SDK_INT >= 17) {
            view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        }
    }

    private void buildViews() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(C_BG);
        root.setPadding(dp(24), dp(10), dp(24), dp(8));
        if (android.os.Build.VERSION.SDK_INT >= 17) {
            root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); // number column on the left, like a receiver, also in Arabic
        }
        // The remote is handled by the activity itself (cursor, OK, long OK ...). If a button took the focus, OK would
        // "click" that button (it used to switch the tab) and Up / Down would move the focus instead of the cursor.
        root.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        root.setFocusable(false);

        TextView title = text(getString(R.string.iptv_mgr_title), 24, C_TITLE, Gravity.CENTER);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout scopeBar = new LinearLayout(this);
        scopeBar.setOrientation(LinearLayout.HORIZONTAL);
        scopeBar.setGravity(Gravity.CENTER);
        TextView prev = text("\u25C0", 22, C_GOLD, Gravity.CENTER);
        prev.setPadding(dp(18), dp(4), dp(18), dp(4));
        prev.setOnClickListener(v -> changeScope(-1));
        mScopeView = text("", 20, Color.WHITE, Gravity.CENTER);
        mScopeView.setTypeface(Typeface.DEFAULT_BOLD);
        mScopeView.setOnClickListener(v -> showScopePicker());
        TextView next = text("\u25B6", 22, C_GOLD, Gravity.CENTER);
        next.setPadding(dp(18), dp(4), dp(18), dp(4));
        next.setOnClickListener(v -> changeScope(1));
        scopeBar.addView(prev);
        scopeBar.addView(mScopeView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scopeBar.addView(next);
        root.addView(scopeBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(headerRow(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)));
        View line = new View(this);
        line.setBackgroundColor(C_GOLD);
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        mList = new ListView(this);
        mList.setDivider(null);
        mList.setFocusable(false);
        mList.setItemsCanFocus(false);
        mList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        mList.setVerticalScrollBarEnabled(true);
        mAdapter = new RowsAdapter();
        mList.setAdapter(mAdapter);
        mMini = new IptvMiniPlayer(this, this::expandMini);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int miniW = Math.max(dp(220), Math.min(dp(440), Math.round(screenW * 0.36f)));
        int miniH = Math.round(miniW * 9f / 16f) + dp(28); // picture 16:9 + the name bar
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.addView(mList, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams miniLp = new LinearLayout.LayoutParams(miniW, miniH);
        miniLp.gravity = Gravity.TOP;
        miniLp.setMargins(dp(10), dp(6), 0, 0);
        body.addView(mMini.view(), miniLp);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mInfoView = text("", 16, C_GOLD, Gravity.CENTER);
        root.addView(mInfoView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

        root.addView(buttonBar(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        mFrame = new FrameLayout(this);
        mFrame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(mFrame);
    }

    private View headerRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(getString(R.string.iptv_mgr_col_no), 18, C_GOLD, Gravity.CENTER), new LinearLayout.LayoutParams(dp(74), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView name = text(getString(R.string.iptv_mgr_col_name), 18, C_GOLD, Gravity.START | Gravity.CENTER_VERTICAL);
        alignToViewStart(name);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String[] marks = {"\u2605", "\uD83D\uDD12", "\u23ED", "\u2714"};
        for (String mark : marks) {
            row.addView(text(mark, 18, C_GOLD, Gravity.CENTER), new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return row;
    }

    private View buttonBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.addView(barButton(getString(R.string.iptv_mgr_btn_delete), Color.parseColor("#D32F2F"), this::deleteTargets));
        bar.addView(barButton(getString(R.string.iptv_mgr_btn_skip), Color.parseColor("#388E3C"), this::toggleSkip));
        bar.addView(barButton(getString(R.string.iptv_mgr_btn_move), Color.parseColor("#F9A825"), this::startMove));
        bar.addView(barButton(getString(R.string.iptv_mgr_btn_edit), Color.parseColor("#1976D2"), this::renameCurrent));
        bar.addView(barButton(getString(R.string.iptv_mgr_btn_menu), Color.parseColor("#546E7A"), this::showMenu));
        return bar;
    }

    private View barButton(String label, int color, final Runnable action) {
        TextView view = text(label, 16, Color.WHITE, Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(8));
        view.setBackground(bg);
        view.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(4), dp(2), dp(4), dp(2));
        view.setLayoutParams(lp);
        return view;
    }

    // ------------------------------------------------------------------ data

    private void rebuildScopes(String wanted) {
        String keep = wanted != null ? wanted : (mScopes.isEmpty() ? S_ALL : mScopes.get(mScopeIdx));
        mScopes.clear();
        mScopes.add(S_ALL);
        mScopes.add(S_FAV);
        for (IptvUserData.Bundle b : mUser.bundles()) {
            mScopes.add(S_BUNDLE + b.id);
        }
        for (String group : mStore.load().groups().keySet()) {
            mScopes.add(S_GROUP + group);
        }
        mScopeIdx = Math.max(0, mScopes.indexOf(keep));
    }

    private String scopeTitle(String scope) {
        if (scope.equals(S_ALL)) {
            return getString(R.string.iptv_mgr_all);
        }
        if (scope.equals(S_FAV)) {
            return getString(R.string.iptv_favorites);
        }
        if (scope.startsWith(S_BUNDLE)) {
            String name = mUser.bundleName(scope.substring(S_BUNDLE.length()));
            return "\u2605 " + (name == null ? "?" : name);
        }
        String group = scope.substring(S_GROUP.length());
        return group.isEmpty() ? getString(R.string.iptv_ungrouped) : group;
    }

    /** Re-reads the channels of the current scope (and the flags drawn in the columns). */
    private void reload() {
        IptvPlaylist library = mStore.load();
        String scope = mScopes.get(mScopeIdx);
        List<IptvChannel> base;
        if (scope.equals(S_ALL)) {
            base = new ArrayList<>(library.getChannels());
        } else if (scope.equals(S_FAV) || scope.startsWith(S_BUNDLE)) {
            Map<String, IptvChannel> byUrl = new HashMap<>();
            for (IptvChannel c : library.getChannels()) {
                byUrl.put(c.key(), c);
            }
            base = new ArrayList<>();
            List<String> members = scope.equals(S_FAV) ? mUser.favorites() : mUser.bundleItems(scope.substring(S_BUNDLE.length()));
            for (String url : members) {
                IptvChannel c = byUrl.get(url);
                if (c != null) {
                    base.add(c);
                }
            }
        } else {
            List<IptvChannel> inGroup = library.groups().get(scope.substring(S_GROUP.length()));
            base = inGroup == null ? new ArrayList<IptvChannel>() : new ArrayList<>(inGroup);
        }

        if (!mFilter.isEmpty()) {
            String q = mFilter.toLowerCase(Locale.ROOT);
            List<IptvChannel> found = new ArrayList<>();
            for (IptvChannel c : base) {
                if ((c.name != null && c.name.toLowerCase(Locale.ROOT).contains(q))
                        || (c.group != null && c.group.toLowerCase(Locale.ROOT).contains(q))) {
                    found.add(c);
                }
            }
            base = found;
        }

        mRows = base;
        mFavSet = new HashSet<>(mUser.favorites());
        mLockedSet = mUser.locked();
        mSkippedSet = mUser.skipped();
        List<String> allKeys = new ArrayList<>();
        for (IptvChannel c : library.getChannels()) {
            allKeys.add(c.key());
        }
        Map<String, Integer> fixed = mUser.fixedNumbers();
        mNumbers = IptvNumbering.resolve(allKeys, fixed);
        mFixedSet = new HashSet<>(fixed.keySet());

        // drop marks of channels that are no longer shown
        Set<String> shown = new HashSet<>();
        for (IptvChannel c : mRows) {
            shown.add(c.key());
        }
        mMarked.retainAll(shown);

        mCursor = mRows.isEmpty() ? 0 : Math.max(0, Math.min(mCursor, mRows.size() - 1));
        refresh();
    }

    private void setMiniFocus(boolean on) {
        on = on && mMini.hasChannel();
        if (mMiniFocused != on) {
            mMiniFocused = on;
            mMini.setSelected(on);
        }
    }

    private void refresh() {
        if (mMiniFocused && !mMini.hasChannel()) { // the window was closed: back to the normal state
            setMiniFocus(false);
        }
        String title = scopeTitle(mScopes.get(mScopeIdx)) + "  (" + mRows.size() + ")";
        if (!mFilter.isEmpty()) {
            title += "  \uD83D\uDD0D " + mFilter;
        }
        mScopeView.setText(title);
        if (mMoving) {
            mInfoView.setText(getString(R.string.iptv_mgr_moving_hint));
        } else if (mDigits.length() > 0) {
            mInfoView.setText("\u2192 " + mDigits);
        } else if (!mMarked.isEmpty()) {
            mInfoView.setText(getString(R.string.iptv_mgr_marked_count, mMarked.size()));
        } else {
            mInfoView.setText(mRows.isEmpty() ? getString(R.string.iptv_mgr_empty) : getString(R.string.iptv_mgr_help));
        }
        mAdapter.notifyDataSetChanged();
        scrollToCursor();
    }

    private void scrollToCursor() {
        if (mRows.isEmpty()) {
            return;
        }
        mList.post(() -> {
            int first = mList.getFirstVisiblePosition();
            int last = mList.getLastVisiblePosition();
            if (mCursor <= first || mCursor >= last) {
                mList.setSelectionFromTop(mCursor, Math.max(0, mList.getHeight() / 2 - dp(ROW_DP) / 2));
            }
        });
    }

    private IptvChannel current() {
        return mCursor >= 0 && mCursor < mRows.size() ? mRows.get(mCursor) : null;
    }

    /** What an action applies to: the marked channels, or the one under the cursor. */
    private List<String> targets() {
        List<String> urls = new ArrayList<>();
        if (!mMarked.isEmpty()) {
            for (IptvChannel c : mRows) {
                if (mMarked.contains(c.key())) {
                    urls.add(c.key());
                }
            }
        } else if (current() != null) {
            urls.add(current().key());
        }
        return urls;
    }

    private String displayName(IptvChannel c) {
        String name = c.name == null || c.name.trim().isEmpty() ? c.url : c.name;
        return mPrefs.isHidePrefix() ? IptvNames.stripPrefix(name) : name;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ cursor / scopes

    private void setCursor(int position) {
        if (mRows.isEmpty()) {
            return;
        }
        mCursor = Math.max(0, Math.min(position, mRows.size() - 1));
        refresh();
    }

    private void moveCursor(int delta) {
        if (mRows.isEmpty()) {
            return;
        }
        if (mMoving) {
            moveStep(delta);
            return;
        }
        int next = mCursor + delta;
        if (Math.abs(delta) == 1) { // wrap around with the arrows, clamp with page jumps
            next = (next + mRows.size()) % mRows.size();
        }
        setCursor(next);
    }

    private void changeScope(int delta) {
        if (mMoving) {
            return;
        }
        setMiniFocus(false);
        mScopeIdx = (mScopeIdx + delta + mScopes.size()) % mScopes.size();
        mCursor = 0;
        mMarked.clear();
        mFilter = "";
        reload();
    }

    private void showScopePicker() {
        if (mMoving) {
            return;
        }
        final String[] names = new String[mScopes.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = scopeTitle(mScopes.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.iptv_mgr_title)
                .setItems(names, (dialog, which) -> {
                    mScopeIdx = which;
                    mCursor = 0;
                    mMarked.clear();
                    mFilter = "";
                    reload();
                })
                .show();
    }

    // ------------------------------------------------------------------ number typing

    private void typeDigit(int digit) {
        if (mRows.isEmpty()) {
            return;
        }
        if (mDigits.length() >= 5) {
            mDigits.setLength(0);
        }
        mDigits.append(digit);
        mInfoView.setText("\u2192 " + mDigits);
        mHandler.removeCallbacks(mCommitDigits);
        mHandler.postDelayed(mCommitDigits, DIGIT_MS);
    }

    private void commitDigits() {
        mHandler.removeCallbacks(mCommitDigits);
        if (mDigits.length() == 0) {
            return;
        }
        int number = 0;
        try {
            number = Integer.parseInt(mDigits.toString());
        } catch (NumberFormatException ignored) {
            // too long: ignored
        }
        mDigits.setLength(0);
        if (mMoving) {
            if (number >= 1 && number <= mRows.size()) {
                moveTo(number - 1); // while moving the digits are a position in the list
            } else {
                toast(getString(R.string.iptv_mgr_no_such_number));
            }
        } else {
            int row = -1; // not moving: the digits are the channel number shown in the first column
            for (int i = 0; i < mRows.size(); i++) {
                Integer n = mNumbers.get(mRows.get(i).key());
                if (n != null && n == number) {
                    row = i;
                    break;
                }
            }
            if (row >= 0) {
                setCursor(row);
            } else {
                toast(getString(R.string.iptv_mgr_no_such_number));
            }
        }
        refresh();
    }

    // ------------------------------------------------------------------ move

    private void startMove() {
        if (mMoving || current() == null) {
            return;
        }
        mMoving = true;
        mMoveOrigin = mCursor;
        mMoveBackup = new ArrayList<>(mRows);
        mMarked.clear();
        refresh();
    }

    private void moveStep(int delta) {
        int to = mCursor + delta;
        if (to < 0 || to >= mRows.size()) {
            return;
        }
        moveTo(to);
    }

    private void moveTo(int position) {
        int to = Math.max(0, Math.min(position, mRows.size() - 1));
        IptvChannel moving = mRows.remove(mCursor);
        mRows.add(to, moving);
        mCursor = to;
        refresh();
    }

    private void finishMove(boolean commit) {
        if (!mMoving) {
            return;
        }
        mMoving = false;
        if (commit) {
            persistOrder(getString(R.string.iptv_mgr_moved));
        } else {
            mRows = new ArrayList<>(mMoveBackup);
            mCursor = mMoveOrigin;
        }
        mMoveBackup = null;
        refresh();
    }

    /** Saves the order of the rows on screen (library order, or the favorites' own order in the Favorites tab). */
    private void persistOrder(String doneMessage) {
        List<String> urls = new ArrayList<>();
        for (IptvChannel c : mRows) {
            urls.add(c.key());
        }
        try {
            String scope = mScopes.get(mScopeIdx);
            if (scope.equals(S_FAV)) {
                mUser.reorderFavorites(urls);
            } else if (scope.startsWith(S_BUNDLE)) {
                mUser.reorderBundle(scope.substring(S_BUNDLE.length()), urls);
            } else {
                mStore.applyOrder(urls);
            }
            mDirty = true;
            if (doneMessage != null) {
                toast(doneMessage);
            }
        } catch (IOException e) {
            toast(String.valueOf(e.getMessage()));
        }
    }

    /** Moves the marked channels as one block: to the top, the bottom, or right before the channel under the cursor. */
    private void moveMarked(int mode) {
        if (mMarked.isEmpty()) {
            return;
        }
        List<String> shown = new ArrayList<>();
        for (IptvChannel c : mRows) {
            shown.add(c.key());
        }
        String anchor = current() == null ? null : current().key();
        if (mode == IptvPlaylist.MOVE_BEFORE && (anchor == null || mMarked.contains(anchor))) {
            toast(getString(R.string.iptv_mgr_pick_unmarked));
            return;
        }
        List<String> order = IptvPlaylist.reorderKeys(shown, shown, mMarked, mode, anchor);
        if (order == null) {
            return;
        }
        Map<String, IptvChannel> byUrl = new HashMap<>();
        for (IptvChannel c : mRows) {
            byUrl.put(c.key(), c);
        }
        List<IptvChannel> sorted = new ArrayList<>();
        for (String k : order) {
            sorted.add(byUrl.get(k));
        }
        mRows = sorted;
        persistOrder(getString(R.string.iptv_mgr_moved));
        mMarked.clear();
        refresh();
    }

    private void askMoveToNumber() {
        final IptvChannel channel = current();
        if (channel == null) {
            return;
        }
        final EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.iptv_mgr_move_to_title, mRows.size()))
                .setView(field)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    int number;
                    try {
                        number = Integer.parseInt(field.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        return;
                    }
                    if (number < 1 || number > mRows.size()) {
                        toast(getString(R.string.iptv_mgr_no_such_number));
                        return;
                    }
                    mMarked.clear();
                    moveTo(number - 1);
                    persistOrder(getString(R.string.iptv_mgr_moved));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void sortAz() {
        if (mRows.size() < 2) {
            return;
        }
        confirm(getString(R.string.iptv_mgr_sort_confirm), () -> {
            List<IptvChannel> sorted = new ArrayList<>(mRows);
            Collections.sort(sorted, (a, b) -> String.valueOf(a.name).compareToIgnoreCase(String.valueOf(b.name)));
            mRows = sorted;
            persistOrder(getString(R.string.iptv_mgr_sorted));
            refresh();
        });
    }

    // ------------------------------------------------------------------ channel actions

    private void onOk() {
        if (mMoving) {
            finishMove(true);
            return;
        }
        playCurrent();
    }

    private void playCurrent() {
        playCurrent(false);
    }

    /** @param forceFull play full screen even when the small window is on */
    private void playCurrent(final boolean forceFull) {
        final IptvChannel channel = current();
        if (channel == null) {
            return;
        }
        if (mLockedSet.contains(channel.key())) {
            IptvPin.verify(this, () -> {
                if (forceFull || !miniEnabled()) {
                    IptvPin.allowOnce(channel.key());
                }
                openChannel(channel, forceFull);
            }, null);
            return;
        }
        openChannel(channel, forceFull);
    }

    private boolean miniEnabled() {
        return mPrefs.getPlayerMode() == IptvPrefs.PLAYER_INTERNAL && mPrefs.isMiniPlayer();
    }

    /** OK on a channel: plays it in the small window; OK on the channel that already plays there = full screen. */
    private void openChannel(IptvChannel channel, boolean forceFull) {
        if (forceFull || !miniEnabled()) {
            startPlayer(channel);
            return;
        }
        IptvChannel playing = mMini.channel();
        if (playing != null && playing.key().equals(channel.key())) {
            expandMini();
            return;
        }
        mMini.play(channel);
        refresh(); // the row of the playing channel gets its marker
    }

    /** The small window to full screen (the PIN was already entered when a locked channel started in the window). */
    private void expandMini() {
        IptvChannel channel = mMini.channel();
        if (channel == null || mMoving) {
            return;
        }
        if (mLockedSet.contains(channel.key())) {
            IptvPin.allowOnce(channel.key());
        }
        startPlayer(channel);
    }

    private void startPlayer(IptvChannel channel) {
        // the channels the player may zap through: everything shown except skipped and locked ones
        List<IptvChannel> queue = new ArrayList<>();
        for (IptvChannel c : mRows) {
            String key = c.key();
            if (c == channel || (!mSkippedSet.contains(key) && !mLockedSet.contains(key))) {
                queue.add(c);
            }
        }
        mResumeMini = mMini.hasChannel();
        mMini.stop(); // one stream at a time: the full screen player takes over
        IptvSection.play(this, channel, queue);
    }

    private void toggleFavorite() {
        List<String> urls = targets();
        if (urls.isEmpty()) {
            return;
        }
        boolean allFav = mFavSet.containsAll(urls);
        mUser.setFavorites(urls, !allFav);
        mDirty = true;
        mMarked.clear();
        // in the Favorites tab an un-favorited channel leaves the list
        reload();
    }

    private void toggleSkip() {
        List<String> urls = targets();
        if (urls.isEmpty() || mMoving) {
            return;
        }
        boolean allSkipped = mSkippedSet.containsAll(urls);
        mUser.setSkipped(urls, !allSkipped);
        mMarked.clear();
        reload();
    }

    private void toggleLock() {
        final List<String> urls = targets();
        if (urls.isEmpty() || mMoving) {
            return;
        }
        final boolean lock = !mLockedSet.containsAll(urls);
        if (lock) {
            IptvPin.ensureSet(this, () -> {
                mUser.setLocked(urls, true);
                mMarked.clear();
                reload();
            });
        } else {
            IptvPin.verify(this, () -> {
                mUser.setLocked(urls, false);
                mMarked.clear();
                reload();
            }, null);
        }
    }

    private void toggleMark() {
        IptvChannel channel = current();
        if (channel == null || mMoving) {
            return;
        }
        if (!mMarked.remove(channel.key())) {
            mMarked.add(channel.key());
        }
        refresh();
    }

    private void markAll(boolean on) {
        mMarked.clear();
        if (on) {
            for (IptvChannel c : mRows) {
                mMarked.add(c.key());
            }
        }
        refresh();
    }

    private void renameCurrent() {
        final IptvChannel channel = current();
        if (channel == null || mMoving) {
            return;
        }
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(channel.name == null ? "" : channel.name);
        field.setSelection(field.getText().length());
        new AlertDialog.Builder(this)
                .setTitle(R.string.iptv_mgr_rename_title)
                .setView(field)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String value = field.getText().toString().trim();
                    if (value.isEmpty()) {
                        return;
                    }
                    try {
                        if (mStore.renameChannel(channel.key(), value)) {
                            mDirty = true;
                            toast(getString(R.string.iptv_mgr_renamed));
                        }
                    } catch (IOException e) {
                        toast(String.valueOf(e.getMessage()));
                    }
                    reload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteTargets() {
        final List<String> urls = targets();
        if (urls.isEmpty() || mMoving) {
            return;
        }
        final String delScope = mScopes.get(mScopeIdx);
        final boolean bundleTab = delScope.startsWith(S_BUNDLE);
        final boolean favoritesTab = delScope.equals(S_FAV) || bundleTab;
        String question = getString(favoritesTab ? R.string.iptv_mgr_unfavorite_confirm : R.string.iptv_mgr_delete_confirm, urls.size());
        confirm(question, () -> {
            try {
                if (bundleTab) {
                    mUser.removeFromBundle(delScope.substring(S_BUNDLE.length()), urls); // only leaves this bundle
                } else if (favoritesTab) {
                    mUser.removeFavorites(urls); // only leaves the favorites, the channel stays in the library
                } else {
                    mStore.removeChannels(urls);
                    mUser.removeFavorites(urls);
                    mUser.removeFromAllBundles(urls);
                    mUser.clearFixedNumber(urls);
                    mUser.setLocked(urls, false);
                    mUser.setSkipped(urls, false);
                }
                mDirty = true;
                mMarked.clear();
                if (!favoritesTab) {
                    rebuildScopes(null); // a group may have become empty
                }
                reload();
            } catch (IOException e) {
                toast(String.valueOf(e.getMessage()));
            }
        });
    }

    private void askSearch() {
        if (mMoving) {
            return;
        }
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(mFilter);
        field.setSelection(field.getText().length());
        new AlertDialog.Builder(this)
                .setTitle(R.string.iptv_section_search)
                .setView(field)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    mFilter = field.getText().toString().trim();
                    mCursor = 0;
                    mMarked.clear();
                    reload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirm(String question, final Runnable action) {
        new AlertDialog.Builder(this)
                .setMessage(question)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> action.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ menu

    private void showMenu() {
        final IptvChannel channel = current();
        if (mMoving) {
            return;
        }
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        final boolean multi = !mMarked.isEmpty();

        if (channel != null) {
            List<String> urls = targets();
            boolean allFav = mFavSet.containsAll(urls);
            boolean allLocked = mLockedSet.containsAll(urls);
            boolean allSkipped = mSkippedSet.containsAll(urls);

            if (!multi) {
                add(labels, actions, getString(R.string.iptv_play), this::playCurrent);
                if (miniEnabled()) {
                    add(labels, actions, getString(R.string.iptv_mini_play_full), () -> playCurrent(true));
                }
            }
            add(labels, actions, getString(allFav ? R.string.iptv_remove_favorite : R.string.iptv_add_favorite), this::toggleFavorite);
            add(labels, actions, getString(R.string.iptv_mgr_to_bundle), () -> pickBundle(false));
            if (mScopes.get(mScopeIdx).equals(S_FAV) || currentBundleId() != null) {
                add(labels, actions, getString(R.string.iptv_mgr_move_to_bundle), () -> pickBundle(true));
            }
            if (!multi) {
                add(labels, actions, getString(R.string.iptv_mgr_fixed_number), this::askFixedNumber);
            }
            if (!Collections.disjoint(mFixedSet, urls)) {
                add(labels, actions, getString(R.string.iptv_mgr_fixed_clear), this::clearFixedNumbers);
            }
            add(labels, actions, getString(allLocked ? R.string.iptv_mgr_unlock : R.string.iptv_mgr_lock), this::toggleLock);
            add(labels, actions, getString(allSkipped ? R.string.iptv_mgr_unskip : R.string.iptv_mgr_skip), this::toggleSkip);
            add(labels, actions, getString(mMarked.contains(channel.key()) ? R.string.iptv_mgr_unmark : R.string.iptv_mgr_mark), this::toggleMark);
            if (multi) {
                add(labels, actions, getString(R.string.iptv_mgr_move_marked_top), () -> moveMarked(IptvPlaylist.MOVE_TOP));
                add(labels, actions, getString(R.string.iptv_mgr_move_marked_bottom), () -> moveMarked(IptvPlaylist.MOVE_BOTTOM));
                add(labels, actions, getString(R.string.iptv_mgr_move_marked_here), () -> moveMarked(IptvPlaylist.MOVE_BEFORE));
            } else {
                add(labels, actions, getString(R.string.iptv_mgr_move), this::startMove);
                add(labels, actions, getString(R.string.iptv_mgr_move_to), this::askMoveToNumber);
                add(labels, actions, getString(R.string.iptv_mgr_rename), this::renameCurrent);
            }
            add(labels, actions, getString(R.string.iptv_mgr_delete), this::deleteTargets);
        }
        if (!mRows.isEmpty()) {
            add(labels, actions, getString(mMarked.size() >= mRows.size() ? R.string.iptv_mgr_unmark_all : R.string.iptv_mgr_mark_all),
                    () -> markAll(mMarked.size() < mRows.size()));
            add(labels, actions, getString(R.string.iptv_mgr_sort), this::sortAz);
        }
        if (mMini.hasChannel()) {
            add(labels, actions, getString(R.string.iptv_mini_expand), this::expandMini);
            add(labels, actions, getString(R.string.iptv_mini_close), () -> {
                mMini.close();
                setMiniFocus(false);
                refresh();
            });
        }
        if (mPrefs.getPlayerMode() == IptvPrefs.PLAYER_INTERNAL) {
            add(labels, actions, getString(mPrefs.isMiniPlayer() ? R.string.iptv_mini_turn_off : R.string.iptv_mini_turn_on), () -> {
                boolean on = !mPrefs.isMiniPlayer();
                mPrefs.setMiniPlayer(on);
                if (!on) {
                    mMini.close();
                }
                refresh();
            });
        }
        if (miniEnabled()) {
            add(labels, actions, getString(mPrefs.isMiniSelect() ? R.string.iptv_mini_sel_turn_off : R.string.iptv_mini_sel_turn_on), () -> {
                mPrefs.setMiniSelect(!mPrefs.isMiniSelect());
                setMiniFocus(false);
                refresh();
            });
        }
        add(labels, actions, getString(R.string.iptv_mgr_bundles), this::showBundleManager);
        add(labels, actions, getString(R.string.iptv_section_search), this::askSearch);
        if (!mFilter.isEmpty()) {
            add(labels, actions, getString(R.string.iptv_mgr_clear_search), () -> {
                mFilter = "";
                reload();
            });
        }
        add(labels, actions, getString(R.string.iptv_mgr_pin_change), () -> IptvPin.change(this));

        openMenuOverlay(channel == null ? getString(R.string.iptv_mgr_title) : displayName(channel), labels, actions);
    }

    // The options menu is a layer inside this activity (not a dialog window): a dialog opened by a long press often
    // never got the input focus, so the remote did nothing until the app was left and entered again. Here every key
    // goes through the activity itself, so it always answers.
    private FrameLayout mFrame;
    private View mMenuOverlay;
    private ListView mMenuList;
    private BaseAdapter mMenuAdapter;
    private List<String> mMenuLabels;
    private List<Runnable> mMenuActions;
    private int mMenuSel;
    private boolean mMenuHeld;
    private boolean mMenuPressed;

    private void openMenuOverlay(String title, List<String> labels, List<Runnable> actions) {
        closeMenuOverlay();
        mMenuLabels = labels;
        mMenuActions = actions;
        mMenuSel = 0;
        mMenuHeld = true; // OK is still held when the menu comes from a long press: its repeats / key-up are ignored
        mMenuPressed = false;

        final int rowH = dp(52);
        final int rowGap = dp(3);
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;

        FrameLayout scrim = new FrameLayout(this);
        scrim.setBackgroundColor(0xB3000000);
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> closeMenuOverlay());

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF1C2A3A, 0xFF121B26});
        bg.setStroke(dp(1), 0x44FFFFFF);
        bg.setCornerRadius(dp(16));
        panel.setBackground(bg);
        panel.setClickable(true); // a tap inside the panel does not close it
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            panel.setElevation(dp(16));
        }

        // header: the channel name, a thin gold line and the position inside the menu
        TextView head = text(title, 19, Color.WHITE, Gravity.CENTER);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setSingleLine(true);
        head.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.setPadding(dp(18), dp(14), dp(18), dp(4));
        panel.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final TextView counter = text("", 13, 0xFF90A4AE, Gravity.CENTER);
        counter.setPadding(dp(18), 0, dp(18), dp(8));
        panel.addView(counter, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        View line = new View(this);
        line.setBackgroundColor(0x66F5C542);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lineLp.setMargins(dp(18), 0, dp(18), dp(6));
        panel.addView(line, lineLp);

        mMenuList = new ListView(this);
        mMenuList.setDivider(null);
        mMenuList.setFocusable(false);
        mMenuList.setVerticalScrollBarEnabled(true);
        mMenuList.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mMenuList.setSelector(new ColorDrawable(Color.TRANSPARENT));
        mMenuAdapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return mMenuLabels.size();
            }

            @Override
            public Object getItem(int position) {
                return mMenuLabels.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                FrameLayout cell;
                TextView row;
                if (convertView instanceof FrameLayout) {
                    cell = (FrameLayout) convertView;
                    row = (TextView) cell.getChildAt(0);
                } else {
                    cell = new FrameLayout(IptvManagerActivity.this);
                    cell.setLayoutParams(new android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH));
                    cell.setPadding(dp(10), rowGap, dp(10), rowGap);
                    row = new TextView(IptvManagerActivity.this);
                    row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17);
                    row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                    row.setSingleLine(true);
                    row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    row.setPadding(dp(16), 0, dp(16), 0);
                    cell.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                }
                boolean on = position == mMenuSel;
                row.setText(mMenuLabels.get(position));
                row.setTextColor(on ? Color.WHITE : 0xFFB0BEC5);
                row.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                GradientDrawable pill = new GradientDrawable();
                pill.setCornerRadius(dp(10));
                pill.setColor(on ? 0xFF1E88E5 : Color.TRANSPARENT);
                if (on) {
                    pill.setStroke(dp(2), 0xFFFFC107); // the selected option can not be missed
                }
                row.setBackground(pill);
                return cell;
            }
        };
        mMenuList.setAdapter(mMenuAdapter);
        mMenuList.setOnItemClickListener((parent, view, position, id) -> runMenuItem(position));
        mMenuListHeight = Math.min(labels.size() * rowH, Math.round(screenH * 0.58f));
        mMenuRowHeight = rowH;
        mMenuCounter = counter;
        panel.addView(mMenuList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, mMenuListHeight));

        TextView hint = text(getString(R.string.iptv_menu_hint), 12, 0xFF78909C, Gravity.CENTER);
        hint.setPadding(dp(14), dp(8), dp(14), dp(12));
        panel.addView(hint, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int width = Math.max(dp(280), Math.min(dp(440), Math.round(screenW * 0.58f)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        scrim.addView(panel, lp);
        mMenuOverlay = scrim;
        mFrame.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // soft appearance
        scrim.setAlpha(0f);
        panel.setScaleX(0.94f);
        panel.setScaleY(0.94f);
        scrim.animate().alpha(1f).setDuration(140).start();
        panel.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
        updateMenuCounter();
    }

    private int mMenuListHeight;
    private int mMenuRowHeight;
    private TextView mMenuCounter;

    private void updateMenuCounter() {
        if (mMenuCounter != null && mMenuLabels != null) {
            mMenuCounter.setText((mMenuSel + 1) + " / " + mMenuLabels.size());
        }
    }

    private void closeMenuOverlay() {
        if (mMenuOverlay != null) {
            mFrame.removeView(mMenuOverlay);
            mMenuOverlay = null;
            mMenuList = null;
            mMenuAdapter = null;
        }
    }

    private void runMenuItem(int position) {
        List<Runnable> actions = mMenuActions;
        closeMenuOverlay();
        if (actions != null && position >= 0 && position < actions.size()) {
            actions.get(position).run();
        }
    }

    private void moveMenuSelection(int step) {
        int count = mMenuLabels.size();
        if (count == 0 || mMenuList == null) {
            return;
        }
        mMenuSel = (mMenuSel + step + count) % count;
        mMenuAdapter.notifyDataSetChanged();
        updateMenuCounter();
        // the selected row stays in the middle of the list (at the ends the list simply stops): always in view
        final int pos = mMenuSel;
        final int offset = Math.max(0, (mMenuListHeight - mMenuRowHeight) / 2);
        mMenuList.setSelectionFromTop(pos, offset);
        mMenuList.post(() -> {
            if (mMenuList != null && pos == mMenuSel) {
                mMenuList.setSelectionFromTop(pos, offset);
            }
        });
    }

    private boolean handleMenuKey(KeyEvent ev) {
        int code = ev.getKeyCode();
        boolean down = ev.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_UP:
                if (down) {
                    moveMenuSelection(-1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                if (down) {
                    moveMenuSelection(1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down) {
                    if (ev.getRepeatCount() == 0) {
                        mMenuHeld = false; // a fresh press: the long press was released long ago
                        mMenuPressed = true;
                    }
                    return true;
                }
                if (mMenuHeld) { // key-up of the long press that opened the menu
                    mMenuHeld = false;
                    return true;
                }
                if (mMenuPressed) {
                    mMenuPressed = false;
                    mCenterLong = false;
                    runMenuItem(mMenuSel);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_MENU:
                if (!down) {
                    closeMenuOverlay();
                }
                return true;
            default:
                return true; // nothing moves behind the menu
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mMenuOverlay != null) {
            return handleMenuKey(event);
        }
        return super.dispatchKeyEvent(event);
    }

    private static void add(List<String> labels, List<Runnable> actions, String label, Runnable action) {
        labels.add(label);
        actions.add(action);
    }

    // ------------------------------------------------------------------ bundles / fixed numbers

    /** @return the id of the bundle shown in the current tab, or null when the tab is not a bundle */
    private String currentBundleId() {
        String scope = mScopes.get(mScopeIdx);
        return scope.startsWith(S_BUNDLE) ? scope.substring(S_BUNDLE.length()) : null;
    }

    private interface TextCallback {
        void onText(String text);
    }

    private void askText(String title, String initial, final TextCallback callback) {
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(initial);
        field.setSelection(field.getText().length());
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(field)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String value = field.getText().toString().trim();
                    if (!value.isEmpty()) {
                        callback.onText(value);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void askFixedNumber() {
        final IptvChannel channel = current();
        if (channel == null || mMoving) {
            return;
        }
        final EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setSingleLine(true);
        Integer now = mNumbers.get(channel.key());
        if (now != null) {
            field.setText(String.valueOf(now));
            field.setSelection(field.getText().length());
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.iptv_mgr_fixed_title)
                .setView(field)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    int number;
                    try {
                        number = Integer.parseInt(field.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        return;
                    }
                    if (number < 1 || number > 99999) {
                        toast(getString(R.string.iptv_mgr_no_such_number));
                        return;
                    }
                    String loser = mUser.setFixedNumber(channel.key(), number);
                    mDirty = true;
                    toast(loser != null ? getString(R.string.iptv_mgr_fixed_taken) : getString(R.string.iptv_mgr_fixed_set, number));
                    reload();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void clearFixedNumbers() {
        List<String> urls = targets();
        if (urls.isEmpty() || mMoving) {
            return;
        }
        mUser.clearFixedNumber(urls);
        mMarked.clear();
        mDirty = true;
        reload();
    }

    /** Copies (or moves) the marked channels / the channel under the cursor to the classic favorites or to a bundle. */
    private void pickBundle(final boolean move) {
        final List<String> urls = targets();
        if (urls.isEmpty() || mMoving) {
            return;
        }
        final String scope = mScopes.get(mScopeIdx);
        final List<String> ids = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        if (!scope.equals(S_FAV)) {
            ids.add("fav");
            labels.add("\u2605 " + getString(R.string.iptv_favorites));
        }
        for (IptvUserData.Bundle b : mUser.bundles()) {
            if (!scope.equals(S_BUNDLE + b.id)) {
                ids.add(b.id);
                labels.add("\u2605 " + b.name);
            }
        }
        ids.add("new");
        labels.add(getString(R.string.iptv_mgr_bundle_new));
        new AlertDialog.Builder(this)
                .setTitle(R.string.iptv_mgr_bundle_pick)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    final String target = ids.get(which);
                    if (target.equals("new")) {
                        askText(getString(R.string.iptv_mgr_bundle_name), "", name -> {
                            String id = mUser.createBundle(name);
                            if (id != null) {
                                applyToTarget(id, urls, move);
                            }
                        });
                    } else {
                        applyToTarget(target, urls, move);
                    }
                })
                .show();
    }

    private void applyToTarget(String target, List<String> urls, boolean move) {
        if (target.equals("fav")) {
            mUser.setFavorites(urls, true);
        } else {
            mUser.addToBundle(target, urls);
        }
        if (move) {
            String fromBundle = currentBundleId();
            if (fromBundle != null) {
                mUser.removeFromBundle(fromBundle, urls);
            } else if (mScopes.get(mScopeIdx).equals(S_FAV)) {
                mUser.removeFavorites(urls);
            }
        }
        mDirty = true;
        mMarked.clear();
        rebuildScopes(null);
        reload();
        toast(getString(R.string.iptv_mgr_bundle_done, urls.size()));
    }

    /** Create, rename, delete and re-order the bundles (the extra favorites lists shown as tabs). */
    private void showBundleManager() {
        if (mMoving) {
            return;
        }
        final List<IptvUserData.Bundle> list = mUser.bundles();
        final List<String> labels = new ArrayList<>();
        for (IptvUserData.Bundle b : list) {
            labels.add("\u2605 " + b.name + "  (" + mUser.bundleItems(b.id).size() + ")");
        }
        labels.add(getString(R.string.iptv_mgr_bundle_new));
        new AlertDialog.Builder(this)
                .setTitle(R.string.iptv_mgr_bundles)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    if (which == list.size()) {
                        askText(getString(R.string.iptv_mgr_bundle_name), "", name -> {
                            mUser.createBundle(name);
                            mDirty = true;
                            rebuildScopes(null);
                            reload();
                            showBundleManager();
                        });
                    } else {
                        showBundleActions(list.get(which));
                    }
                })
                .show();
    }

    private void showBundleActions(final IptvUserData.Bundle bundle) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        add(labels, actions, getString(R.string.iptv_mgr_bundle_open), () -> {
            rebuildScopes(S_BUNDLE + bundle.id);
            mCursor = 0;
            mMarked.clear();
            mFilter = "";
            reload();
        });
        add(labels, actions, getString(R.string.iptv_mgr_bundle_rename), () ->
                askText(getString(R.string.iptv_mgr_bundle_name), bundle.name, name -> {
                    mUser.renameBundle(bundle.id, name);
                    mDirty = true;
                    rebuildScopes(null);
                    reload();
                    showBundleManager();
                }));
        add(labels, actions, getString(R.string.iptv_mgr_bundle_up), () -> moveBundleTab(bundle.id, -1));
        add(labels, actions, getString(R.string.iptv_mgr_bundle_down), () -> moveBundleTab(bundle.id, 1));
        add(labels, actions, getString(R.string.iptv_mgr_bundle_delete), () ->
                confirm(getString(R.string.iptv_mgr_bundle_delete_confirm, bundle.name), () -> {
                    String keep = mScopes.get(mScopeIdx);
                    if (keep.equals(S_BUNDLE + bundle.id)) {
                        keep = S_FAV;
                    }
                    mUser.deleteBundle(bundle.id);
                    mDirty = true;
                    mMarked.clear();
                    rebuildScopes(keep);
                    reload();
                    showBundleManager();
                }));
        new AlertDialog.Builder(this)
                .setTitle("\u2605 " + bundle.name)
                .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run())
                .show();
    }

    private void moveBundleTab(String id, int delta) {
        mUser.moveBundle(id, delta);
        mDirty = true;
        rebuildScopes(null);
        reload();
        showBundleManager();
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean first = event.getRepeatCount() == 0;
        if (mMiniFocused && !mMini.hasChannel()) {
            setMiniFocus(false);
        }
        if (mMiniFocused) { // the small window is selected
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (first) {
                        mCenterLong = false;
                    }
                    event.startTracking(); // OK = full screen, long OK = close the window
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_CHANNEL_UP:
                case KeyEvent.KEYCODE_CHANNEL_DOWN:
                    if (first) {
                        setMiniFocus(false); // back to the channels of the selected group
                    }
                    return true;
                default:
                    break;
            }
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                moveCursor(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveCursor(1);
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                moveCursor(-PAGE_JUMP);
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                moveCursor(PAGE_JUMP);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (first) {
                    if (!mMoving && mMini.hasChannel() && miniEnabled() && mPrefs.isMiniSelect()) {
                        setMiniFocus(true); // select the small window instead of switching between groups
                    } else {
                        changeScope(keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    }
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (first) {
                    mCenterLong = false; // a new press (the menu may have swallowed the previous key-up)
                    if (mDigits.length() > 0) { // typed number + OK = go there now
                        commitDigits();
                        mCenterLong = true; // swallow the key-up
                        return true;
                    }
                } else if (event.getRepeatCount() >= 8) {
                    openMenuFromLongPress(); // some remotes never report a long press: held long enough = menu
                }
                event.startTracking();
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                if (first) {
                    showMenu();
                }
                return true;
            case KeyEvent.KEYCODE_PROG_RED:
            case KeyEvent.KEYCODE_D:
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL:
                if (first) {
                    deleteTargets();
                }
                return true;
            case KeyEvent.KEYCODE_PROG_GREEN:
            case KeyEvent.KEYCODE_S:
                if (first) {
                    toggleSkip();
                }
                return true;
            case KeyEvent.KEYCODE_PROG_YELLOW:
            case KeyEvent.KEYCODE_M:
                if (first) {
                    startMove();
                }
                return true;
            case KeyEvent.KEYCODE_PROG_BLUE:
            case KeyEvent.KEYCODE_R:
                if (first) {
                    renameCurrent();
                }
                return true;
            case KeyEvent.KEYCODE_F:
                if (first) {
                    toggleFavorite();
                }
                return true;
            case KeyEvent.KEYCODE_L:
                if (first) {
                    toggleLock();
                }
                return true;
            case KeyEvent.KEYCODE_SPACE:
                if (first) {
                    toggleMark();
                }
                return true;
            case KeyEvent.KEYCODE_Q:
            case KeyEvent.KEYCODE_SEARCH:
                if (first) {
                    askSearch();
                }
                return true;
            default:
                break;
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            typeDigit(keyCode - KeyEvent.KEYCODE_0);
            return true;
        }
        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            typeDigit(keyCode - KeyEvent.KEYCODE_NUMPAD_0);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void openMenuFromLongPress() {
        if (mCenterLong) {
            return; // already opened for this press
        }
        mCenterLong = true;
        showMenu();
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (mMiniFocused && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            mCenterLong = true; // swallow the key-up
            mMini.close();
            setMiniFocus(false); // closed: everything is as before
            refresh();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            openMenuFromLongPress();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (mCenterLong) {
                mCenterLong = false;
                return true;
            }
            if (event.isTracking() && !event.isCanceled()) {
                if (mMiniFocused) {
                    expandMini();
                } else {
                    onOk();
                }
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (mMiniFocused) {
            setMiniFocus(false);
        } else if (mMoving) {
            finishMove(false);
        } else if (!mFilter.isEmpty()) {
            mFilter = "";
            reload();
        } else if (!mMarked.isEmpty()) {
            mMarked.clear();
            refresh();
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------------ list

    private static final class Holder {
        TextView number;
        TextView name;
        TextView fav;
        TextView lock;
        TextView skip;
        TextView mark;
        int position;
    }

    private final class RowsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mRows.size();
        }

        @Override
        public Object getItem(int position) {
            return mRows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            LinearLayout row;
            if (convertView == null) {
                row = new LinearLayout(IptvManagerActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setLayoutParams(new ListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_DP)));
                final Holder holder = new Holder();
                holder.number = text("", 18, C_DIM, Gravity.CENTER);
                holder.name = text("", 20, Color.WHITE, Gravity.START | Gravity.CENTER_VERTICAL);
                holder.name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                holder.name.setPadding(dp(6), 0, dp(6), 0);
                alignToViewStart(holder.name);
                holder.fav = text("", 20, Color.parseColor("#FFD54F"), Gravity.CENTER);
                holder.lock = text("", 18, Color.WHITE, Gravity.CENTER);
                holder.skip = text("", 18, Color.parseColor("#81C784"), Gravity.CENTER);
                holder.mark = text("", 20, C_CURSOR_EDGE, Gravity.CENTER);
                row.addView(holder.number, new LinearLayout.LayoutParams(dp(74), ViewGroup.LayoutParams.MATCH_PARENT));
                row.addView(holder.name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                for (TextView col : new TextView[]{holder.fav, holder.lock, holder.skip, holder.mark}) {
                    row.addView(col, new LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.MATCH_PARENT));
                }
                // touch: tap selects, tap again plays, long tap = menu, the columns toggle directly
                row.setOnClickListener(v -> {
                    if (holder.position == mCursor) {
                        onOk();
                    } else if (!mMoving) {
                        setCursor(holder.position);
                    }
                });
                row.setOnLongClickListener(v -> {
                    if (!mMoving) {
                        mCursor = holder.position;
                        showMenu();
                    }
                    return true;
                });
                holder.fav.setOnClickListener(v -> onColumn(holder.position, 0));
                holder.lock.setOnClickListener(v -> onColumn(holder.position, 1));
                holder.skip.setOnClickListener(v -> onColumn(holder.position, 2));
                holder.mark.setOnClickListener(v -> onColumn(holder.position, 3));
                row.setTag(holder);
            } else {
                row = (LinearLayout) convertView;
            }
            h = (Holder) row.getTag();
            h.position = position;

            IptvChannel c = mRows.get(position);
            String key = c.key();
            Integer shownNumber = mNumbers.get(key);
            h.number.setText(String.format(Locale.US, "%04d", shownNumber == null ? position + 1 : shownNumber));
            h.number.setTextColor(mFixedSet.contains(key) ? C_GOLD : C_DIM); // gold = fixed number chosen by the user
            String label = displayName(c);
            int health = IptvHealth.instance(IptvManagerActivity.this).status(key);
            if (health == IptvHealth.BAD) {
                label = "\uD83D\uDD34 " + label;
            }
            if (mMini.hasChannel() && key.equals(mMini.channel().key())) {
                label = "\u25B6 " + label; // playing in the small window
            }
            h.name.setText(label);
            h.name.setTextColor(mSkippedSet.contains(key) ? C_DIM : Color.WHITE);
            h.fav.setText(mFavSet.contains(key) ? "\u2605" : "");
            h.lock.setText(mLockedSet.contains(key) ? "\uD83D\uDD12" : "");
            h.skip.setText(mSkippedSet.contains(key) ? "\u23ED" : "");
            h.mark.setText(mMarked.contains(key) ? "\u2714" : "");

            boolean isCursor = position == mCursor;
            if (isCursor || mMarked.contains(key)) {
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(6));
                bg.setColor(mMoving && isCursor ? C_MOVING : (isCursor ? C_CURSOR : C_MARKED));
                if (isCursor) {
                    bg.setStroke(dp(2), C_CURSOR_EDGE);
                }
                row.setBackground(bg);
            } else {
                row.setBackground(null);
            }
            return row;
        }
    }

    /** Touch on one of the icon columns of a row: 0 favorite, 1 lock, 2 skip, 3 mark. */
    private void onColumn(int position, int column) {
        if (mMoving || position < 0 || position >= mRows.size()) {
            return;
        }
        mCursor = position;
        switch (column) {
            case 0:
                toggleFavoriteOfRow();
                break;
            case 1:
                toggleLockOfRow();
                break;
            case 2:
                toggleSkipOfRow();
                break;
            default:
                toggleMark();
                break;
        }
    }

    // A tap on a column acts on that row only, even when other rows are marked.
    private void toggleFavoriteOfRow() {
        withOnlyCurrent(this::toggleFavorite);
    }

    private void toggleLockOfRow() {
        withOnlyCurrent(this::toggleLock);
    }

    private void toggleSkipOfRow() {
        withOnlyCurrent(this::toggleSkip);
    }

    private void withOnlyCurrent(Runnable action) {
        Set<String> saved = new LinkedHashSet<>(mMarked);
        mMarked.clear();
        action.run();
        // the actions clear / reload the marks themselves; restore the ones that are still shown
        mMarked.addAll(saved);
        Set<String> shown = new HashSet<>();
        for (IptvChannel c : mRows) {
            shown.add(c.key());
        }
        mMarked.retainAll(shown);
        refresh();
    }
}
