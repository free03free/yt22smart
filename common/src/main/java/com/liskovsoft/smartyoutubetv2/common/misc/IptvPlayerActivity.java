package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.ts.DefaultTsPayloadReaderFactory;
import com.google.android.exoplayer2.source.BehindLiveWindowException;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.Util;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.iptv.HttpIptvFetcher;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvAlternatives;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvChannel;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvPrefs;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvStore;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvUserData;
import com.liskovsoft.smartyoutubetv2.common.iptv.StalkerClient;
import com.liskovsoft.smartyoutubetv2.common.iptv.StalkerLink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Built-in full screen player for IPTV channels (ExoPlayer: HLS + DASH (.mpd) + MPEG-TS/progressive + udp,
 * plus Stalker / Ministra portal channels whose stream link is created on demand).
 *
 * Remote / keyboard:
 *   Up / Down (controller hidden) = previous / next channel of the opened list
 *   Channel +/-, Page up/down      = next / previous channel
 *   OK                             = show the playback controls
 *   Menu / Settings                = choose the maximum video quality (data saver)
 *   Back                           = close
 */
public class IptvPlayerActivity extends Activity {
    private static final String EXTRA_NAME = "iptv_name";
    private static final String EXTRA_URL = "iptv_url";
    private static final String EXTRA_UA = "iptv_ua";
    private static final String EXTRA_REF = "iptv_ref";
    private static final long OVERLAY_MS = 4000;

    private static final int SRC_HLS = 0;
    private static final int SRC_PROGRESSIVE = 1;
    private static final int SRC_DASH = 2;

    /** The list the user was browsing (kept in memory; an Intent can't carry thousands of channels). */
    private static List<IptvChannel> sQueue = Collections.emptyList();
    private static int sIndex;
    /** Key of the channel that played last, so the manager's small window can carry on with it after full screen. */
    private static String sLastKey;

    static String lastPlayedKey() {
        return sLastKey;
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mHideTitle = this::hideTitle;
    private List<IptvChannel> mQueue = new ArrayList<>();
    private int mIndex;
    private int[] mSources = {SRC_PROGRESSIVE, SRC_HLS};
    /** Real stream url of the current channel (differs from channel.url for portal channels). */
    private String mPlayUrl;
    private String mPlayUa;
    /** Incremented on every channel change: late answers of the portal for an old channel are dropped. */
    private int mPlayRequest;
    private boolean mPortalRetried;
    /** Why the channel does not play ("HTTP 403", ...): shown instead of a bare "error". */
    private String mErrorReason = "";
    private int mSourceAttempt;
    private SimpleExoPlayer mPlayer;
    private PlayerView mPlayerView;
    private TextView mTitleView;
    private TextView mStatusView;
    private TextView mQualityView;
    /** Side list of the channels, opened with OK over the picture. */
    private ListView mChannelList;
    private boolean mPanelRtl;
    private int mNumberWidth;
    private BaseAdapter mChannelAdapter;
    private boolean mSwallowCenterUp;
    /** The current OK press already became a long press (controls shown), so its release must not open the list. */
    private boolean mCenterLongHandled;
    private TextView mMuteView;
    private LinearLayout mTopBar;
    private boolean mMuted;
    private DefaultTrackSelector mTrackSelector;
    private Dialog mQualityDialog;
    /** Variants of the current video (1 = the stream offers a single quality) and the height of the selected one. */
    private int mVideoVariants;
    private int mVideoHeight = Format.NO_VALUE;
    /** True when some variants of the stream do not tell their resolution: then the limit falls back to a bitrate cap. */
    private boolean mVideoUnlabeled;
    private boolean mSingleNoteShown;
    /** Channels (by queue index) replaced by a lighter copy for this playback; the originals are put back when zapping on. */
    private final Map<Integer, IptvChannel> mOriginals = new HashMap<>();
    private boolean mSwapNoteShown;
    /** A locked channel the PIN was already entered for (in this player session). */
    private String mUnlockedKey;
    private IptvAlternatives.QualityIndex mQualityIndex;

    /** @param queue channels to zap through (may contain only the one to play) */
    public static void start(Context context, List<IptvChannel> queue, int index) {
        if (queue == null || index < 0 || index >= queue.size()) {
            return;
        }

        IptvChannel channel = queue.get(index);
        sQueue = new ArrayList<>(queue);
        sIndex = index;

        Intent intent = new Intent(context, IptvPlayerActivity.class);
        intent.putExtra(EXTRA_NAME, channel.name);
        intent.putExtra(EXTRA_URL, channel.url);
        intent.putExtra(EXTRA_UA, channel.userAgent);
        intent.putExtra(EXTRA_REF, channel.referrer);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        restoreQueue();
        buildViews();
    }

    private void restoreQueue() {
        if (!sQueue.isEmpty() && sIndex >= 0 && sIndex < sQueue.size()) {
            mQueue = new ArrayList<>(sQueue);
            mIndex = sIndex;
            return;
        }

        // Process was recreated: only the channel from the Intent is known
        Intent intent = getIntent();
        IptvChannel channel = new IptvChannel(intent.getStringExtra(EXTRA_NAME), intent.getStringExtra(EXTRA_URL));
        channel.userAgent = intent.getStringExtra(EXTRA_UA);
        channel.referrer = intent.getStringExtra(EXTRA_REF);
        mQueue = new ArrayList<>();
        mQueue.add(channel);
        mIndex = 0;
    }

    private void buildViews() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        mPlayerView = new PlayerView(this);
        mPlayerView.setUseController(true);
        mPlayerView.setControllerShowTimeoutMs(4000);
        mPlayerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        mPlayerView.setKeepContentOnPlayerReset(true);
        root.addView(mPlayerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mTitleView = overlayText(Gravity.TOP | Gravity.START, 18);
        mTitleView.setBackgroundColor(0x99000000);
        mTitleView.setVisibility(View.GONE);
        root.addView(mTitleView);

        mStatusView = overlayText(Gravity.CENTER, 20);
        mStatusView.setBackgroundColor(0x99000000);
        mStatusView.setVisibility(View.GONE);
        root.addView(mStatusView);

        mQualityView = overlayText(Gravity.TOP | Gravity.END, 16);
        mQualityView.setBackgroundColor(0x99000000);
        mQualityView.setFocusable(true);
        mQualityView.setClickable(true);
        mQualityView.setOnClickListener(v -> showQualityDialog());
        mQualityView.setOnFocusChangeListener((v, focused) -> v.setBackgroundColor(focused ? 0xCC1E88E5 : 0x99000000));

        mMuteView = overlayText(Gravity.TOP | Gravity.END, 16);
        mMuteView.setBackgroundColor(0x99000000);
        mMuteView.setFocusable(true);
        mMuteView.setClickable(true);
        mMuteView.setOnClickListener(v -> toggleMute());
        mMuteView.setOnFocusChangeListener((v, focused) -> v.setBackgroundColor(focused ? 0xCC1E88E5 : 0x99000000));

        // the quality and mute buttons are shown together with the playback controls
        mTopBar = new LinearLayout(this);
        mTopBar.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonLp.setMargins(dp(6), 0, dp(6), 0);
        mTopBar.addView(mMuteView, buttonLp);
        mTopBar.addView(mQualityView, buttonLp);
        mTopBar.setVisibility(View.GONE);
        root.addView(mTopBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END));
        updateQualityLabel();
        updateMuteLabel();
        mPlayerView.setControllerVisibilityListener(visibility -> mTopBar.setVisibility(visibility == View.VISIBLE ? View.VISIBLE : View.GONE));

        buildChannelPanel(root);

        setContentView(root);
        hideSystemUi();
    }

    private void buildChannelPanel(FrameLayout root) {
        mChannelList = new ListView(this);
        mChannelList.setBackgroundColor(panelBackground());
        mChannelList.setDivider(null);
        mChannelList.setVerticalScrollBarEnabled(true);
        mChannelList.setItemsCanFocus(false);
        mChannelList.setSelector(new android.graphics.drawable.ColorDrawable(0xCC1E88E5));
        mChannelList.setVisibility(View.GONE);
        mChannelAdapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return mQueue.size();
            }

            @Override
            public Object getItem(int position) {
                return mQueue.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                // Row = [number][name]. In an RTL list the number sits on the right edge and the name follows it,
                // whichever side of the screen the panel is on.
                LinearLayout row;
                TextView number;
                TextView nameView;
                if (convertView instanceof LinearLayout) {
                    row = (LinearLayout) convertView;
                    number = (TextView) row.getChildAt(0);
                    nameView = (TextView) row.getChildAt(1);
                } else {
                    row = new LinearLayout(IptvPlayerActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    number = new TextView(IptvPlayerActivity.this);
                    number.setSingleLine(true);
                    number.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                    nameView = new TextView(IptvPlayerActivity.this);
                    nameView.setSingleLine(true);
                    nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    nameView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                    // Arabic name -> RTL paragraph; Latin name -> LTR paragraph (keeps "(552p)" and "+1" in place)
                    nameView.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
                    LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    nameLp.setMarginStart(dp(6));
                    row.addView(number, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    row.addView(nameView, nameLp);
                    row.setPadding(dp(12), dp(6), dp(12), dp(6));
                }
                row.setLayoutDirection(mPanelRtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
                number.setTextDirection(mPanelRtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
                number.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
                nameView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);

                IptvChannel c = mQueue.get(position);
                String name = c.name == null || c.name.trim().isEmpty() ? c.url : c.name;
                float sp = IptvPrefs.instance(IptvPlayerActivity.this).getPanelFont();
                int color = position == mIndex ? 0xFFFFC107 : Color.WHITE;
                number.setText((position + 1) + ".");
                number.setMinWidth(mNumberWidth);
                number.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
                number.setTextColor(color);
                nameView.setText(name);
                nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
                nameView.setTextColor(color);
                return row;
            }
        };
        mChannelList.setAdapter(mChannelAdapter);
        mChannelList.setOnItemClickListener((parent, view, position, id) -> {
            hideChannelPanel();
            if (position != mIndex) {
                playChannel(position);
            }
        });
        root.addView(mChannelList, new FrameLayout.LayoutParams(panelWidth(), ViewGroup.LayoutParams.MATCH_PARENT, panelGravity()));
    }

    private int panelBackground() {
        int alpha = Math.round(IptvPrefs.instance(this).getPanelOpacity() * 255f / 100f);
        return (alpha << 24) | 0x101820;
    }

    private int panelWidth() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        return Math.round(screenW * IptvPrefs.instance(this).getPanelWidth() / 100f);
    }

    /** Physical left / right of the screen, whatever the layout direction of the language is. */
    private int panelGravity() {
        return IptvPrefs.instance(this).getPanelSide() == IptvPrefs.PANEL_RIGHT ? (Gravity.RIGHT | Gravity.FILL_VERTICAL) : (Gravity.LEFT | Gravity.FILL_VERTICAL);
    }

    /**
     * The list reads right-to-left (number on the right edge, names aligned right) when the app language is RTL or most
     * channel names are Arabic. This is independent of the side the panel is on.
     */
    private boolean isPanelRtl() {
        if (android.text.TextUtils.getLayoutDirectionFromLocale(java.util.Locale.getDefault()) == View.LAYOUT_DIRECTION_RTL
                || getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            return true;
        }
        int arabic = 0;
        for (IptvChannel c : mQueue) {
            String n = c.name == null ? "" : c.name;
            for (int i = 0; i < n.length(); i++) {
                char ch = n.charAt(i);
                if (ch >= '\u0600' && ch <= '\u06FF') {
                    arabic++;
                    break;
                }
            }
        }
        return arabic * 2 > mQueue.size();
    }

    /** The settings can change in the manager, so the panel re-reads them each time it opens. */
    private void applyPanelSettings() {
        mChannelList.setBackgroundColor(panelBackground());
        // Panel on the right: RTL rows (number on the right edge, names aligned right).
        // Panel on the left: mirrored rows (number on the left edge, names aligned left), so the text always hugs the screen edge.
        mPanelRtl = IptvPrefs.instance(this).getPanelSide() == IptvPrefs.PANEL_RIGHT && isPanelRtl();
        mChannelList.setLayoutDirection(mPanelRtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        mChannelList.setVerticalScrollbarPosition(mPanelRtl ? View.SCROLLBAR_POSITION_LEFT : View.SCROLLBAR_POSITION_RIGHT);
        android.graphics.Paint paint = new android.graphics.Paint();
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, IptvPrefs.instance(this).getPanelFont(), getResources().getDisplayMetrics()));
        StringBuilder widest = new StringBuilder();
        for (int i = String.valueOf(mQueue.size()).length(); i > 0; i--) {
            widest.append('8');
        }
        mNumberWidth = Math.round(paint.measureText(widest + "."));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(panelWidth(), ViewGroup.LayoutParams.MATCH_PARENT, panelGravity());
        mChannelList.setLayoutParams(lp);
    }

    private boolean isChannelPanelShown() {
        return mChannelList != null && mChannelList.getVisibility() == View.VISIBLE;
    }

    private void showChannelPanel() {
        mPlayerView.hideController();
        applyPanelSettings();
        mChannelAdapter.notifyDataSetChanged();
        mChannelList.setVisibility(View.VISIBLE);
        mChannelList.requestFocus();
        mChannelList.post(() -> mChannelList.setSelectionFromTop(mIndex, dp(80)));
    }

    /** Long OK: the normal controls (play / pause, seek) plus the mute and quality buttons. */
    private void showControls() {
        hideChannelPanel();
        mPlayerView.showController();
    }

    private void hideChannelPanel() {
        mChannelList.setVisibility(View.GONE);
    }

    @Override
    public void onBackPressed() {
        if (isChannelPanelShown()) {
            hideChannelPanel();
            return;
        }
        super.onBackPressed();
    }

    private TextView overlayText(int gravity, int sp) {
        TextView view = new TextView(this);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        int pad = dp(12);
        view.setPadding(pad, pad / 2, pad, pad / 2);
        view.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity));
        return view;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onStart() {
        super.onStart();
        mTrackSelector = new DefaultTrackSelector();
        mVideoVariants = 0;
        mVideoHeight = Format.NO_VALUE;
        mVideoUnlabeled = false;
        applyQualityLimit();
        mPlayer = ExoPlayerFactory.newSimpleInstance(this, mTrackSelector);
        mPlayer.addListener(new Player.EventListener() {
            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    mPortalRetried = false; // a later link expiry may be fixed with a new link again
                    mStatusView.setVisibility(View.GONE);
                }
            }

            @Override
            public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
                handleTracks(trackSelections);
            }

            @Override
            public void onPlayerError(ExoPlaybackException error) {
                handleError(error);
            }
        });
        mPlayer.setVolume(mMuted ? 0f : 1f);
        mPlayerView.setPlayer(mPlayer);
        playChannel(mIndex);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mHandler.removeCallbacksAndMessages(null);
        if (mQualityDialog != null) {
            mQualityDialog.dismiss();
            mQualityDialog = null;
        }
        if (mPlayer != null) {
            mPlayerView.setPlayer(null);
            mPlayer.release();
            mPlayer = null;
        }
        mTrackSelector = null;
    }

    // ------------------------------------------------------------------ quality limit (data saver)

    /**
     * Caps the video height the player may choose. It really lowers the traffic only for streams that offer several
     * qualities (HLS / DASH variants). A stream with a single quality is sent by the server as is: it still plays,
     * at its own quality, because the player cannot ask the server for less data.
     */
    private void applyQualityLimit() {
        if (mTrackSelector == null) {
            return;
        }

        int cap = IptvPrefs.instance(this).getMaxQuality();
        DefaultTrackSelector.ParametersBuilder builder = mTrackSelector.buildUponParameters();
        if (cap > 0) {
            builder.setMaxVideoSize(Integer.MAX_VALUE, cap);
            // variants that do not tell their resolution can only be limited by bitrate
            builder.setMaxVideoBitrate(mVideoUnlabeled ? bitrateFor(cap) : Integer.MAX_VALUE);
        } else {
            builder.setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE);
            builder.setMaxVideoBitrate(Integer.MAX_VALUE);
        }
        builder.setExceedVideoConstraintsIfNecessary(true); // nothing fits the limit: play the lowest one anyway
        mTrackSelector.setParameters(builder);
    }

    /** Generous bitrate (bits/s) that a stream of the given height normally stays under. */
    private static int bitrateFor(int height) {
        if (height <= 144) return 300_000;
        if (height <= 240) return 500_000;
        if (height <= 360) return 900_000;
        if (height <= 480) return 1_500_000;
        if (height <= 720) return 3_000_000;
        return 6_000_000;
    }

    private void handleTracks(TrackSelectionArray selections) {
        int variants = 0;
        int height = Format.NO_VALUE;
        boolean unlabeled = false;

        if (mPlayer != null) {
            for (int i = 0; i < selections.length; i++) {
                TrackSelection selection = selections.get(i);
                if (selection == null || mPlayer.getRendererType(i) != C.TRACK_TYPE_VIDEO) {
                    continue;
                }
                TrackGroup group = selection.getTrackGroup();
                variants = group.length;
                height = selection.getSelectedFormat().height;
                for (int j = 0; j < group.length; j++) {
                    if (group.getFormat(j).height == Format.NO_VALUE) {
                        unlabeled = true;
                    }
                }
            }
        }

        mVideoVariants = variants;
        mVideoHeight = height;

        if (unlabeled != mVideoUnlabeled) {
            mVideoUnlabeled = unlabeled;
            applyQualityLimit(); // switch between the resolution limit and the bitrate fallback
        }

        // Tell once (per player session) that the limit can't help this stream
        if (!mSingleNoteShown && singleQualityAboveLimit()) {
            mSingleNoteShown = true;
            announceQuality();
        }
    }

    private boolean singleQualityAboveLimit() {
        int cap = IptvPrefs.instance(this).getMaxQuality();
        return cap > 0 && mVideoVariants == 1 && (mVideoHeight == Format.NO_VALUE || mVideoHeight > cap);
    }

    private void announceQuality() {
        int cap = IptvPrefs.instance(this).getMaxQuality();
        String text;
        if (singleQualityAboveLimit()) {
            text = getString(R.string.iptv_quality_single, mVideoHeight == Format.NO_VALUE ? "" : " (" + mVideoHeight + "p)");
        } else {
            text = getString(R.string.iptv_quality_set, qualityLabel(cap));
        }
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private String qualityLabel(int height) {
        return height <= 0 ? getString(R.string.iptv_quality_auto) : height + "p";
    }

    private void updateQualityLabel() {
        mQualityView.setText(getString(R.string.iptv_quality_button, qualityLabel(IptvPrefs.instance(this).getMaxQuality())));
    }

    private void toggleMute() {
        mMuted = !mMuted;
        if (mPlayer != null) {
            mPlayer.setVolume(mMuted ? 0f : 1f);
        }
        updateMuteLabel();
    }

    private void updateMuteLabel() {
        mMuteView.setText(getString(mMuted ? R.string.iptv_player_muted : R.string.iptv_player_unmuted));
    }

    private void showQualityDialog() {
        final IptvPrefs prefs = IptvPrefs.instance(this);
        final int[] steps = IptvPrefs.QUALITY_STEPS;
        String[] labels = new String[steps.length];
        int checked = 0;
        for (int i = 0; i < steps.length; i++) {
            labels[i] = qualityLabel(steps[i]);
            if (steps[i] == prefs.getMaxQuality()) {
                checked = i;
            }
        }

        if (mQualityDialog != null) {
            mQualityDialog.dismiss();
        }
        mQualityDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.iptv_quality_limit)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    prefs.setMaxQuality(steps[which]);
                    applyQualityLimit();
                    updateQualityLabel();
                    // A single-quality stream (or one already swapped for a lighter copy) can only change by choosing a copy again
                    boolean replay = mVideoVariants <= 1 || mOriginals.containsKey(mIndex);
                    if (replay) {
                        mSingleNoteShown = false; // the new playback tells whether the limit can help it
                        Toast.makeText(this, getString(R.string.iptv_quality_set, qualityLabel(steps[which])), Toast.LENGTH_LONG).show();
                        playChannel(mIndex);
                    } else {
                        mSingleNoteShown = true; // the answer is announced right now
                        announceQuality();
                    }
                    dialog.dismiss();
                })
                .create();
        mQualityDialog.show();
    }

    // ------------------------------------------------------------------ playback

    private void playChannel(int index) {
        playChannel(index, true);
    }

    /** @param allowCopy false: play exactly this entry (no lighter copy), e.g. after the copy failed */
    private void playChannel(int index, boolean allowCopy) {
        if (mQueue.isEmpty() || mPlayer == null) {
            return;
        }

        mIndex = (index % mQueue.size() + mQueue.size()) % mQueue.size(); // wrap around
        IptvChannel original = mOriginals.remove(mIndex);
        if (original != null) {
            mQueue.set(mIndex, original); // a lighter copy was used last time: decide again with the current limit
        }
        IptvChannel channel = mQueue.get(mIndex);
        if (needsPin(channel)) {
            // locked channel: nothing plays until the right PIN is entered
            mPlayRequest++;
            mPlayer.stop();
            showTitle(channel);
            showStatus(getString(R.string.iptv_mgr_pin_enter));
            final int pinIndex = mIndex;
            final String pinKey = channel.key();
            IptvPin.verify(this, () -> {
                mUnlockedKey = pinKey;
                playChannel(pinIndex, allowCopy);
            }, this::finish);
            return;
        }
        IptvUserData.instance(this).addRecent(channel.url);
        sLastKey = channel.key();

        showTitle(channel);
        showStatus(getString(R.string.iptv_player_loading));

        mPlayRequest++;
        mPortalRetried = false;
        mErrorReason = "";

        int cap = IptvPrefs.instance(this).getMaxQuality();
        if (allowCopy && cap > 0 && IptvPrefs.instance(this).isPreferLowerCopy()) {
            lookupLowerCopy(channel, cap);
            return;
        }
        beginPlayback(channel);
    }

    private void beginPlayback(IptvChannel channel) {
        if (StalkerLink.isStalker(channel.url)) {
            resolvePortalChannel(channel, false);
            return;
        }
        startPlayback(channel, channel.url, null);
    }

    /**
     * A stream with a single quality can't be made lighter by the player, but the library often holds the same channel
     * as SD / 360p too. The search scans the whole library, so it runs off the UI thread.
     */
    private void lookupLowerCopy(final IptvChannel channel, final int cap) {
        final int request = mPlayRequest;
        final Context context = getApplicationContext();
        new Thread(() -> {
            IptvChannel copy = null;
            try {
                copy = qualityIndex(context).lowerQuality(channel, cap);
            } catch (Throwable t) {
                // no index (library changing / out of memory): just play the channel itself
            }
            final IptvChannel found = copy;
            mHandler.post(() -> {
                if (request != mPlayRequest || mPlayer == null || isFinishing()) {
                    return; // user already zapped to another channel / left the player
                }
                if (found == null) {
                    beginPlayback(channel);
                    return;
                }
                mOriginals.put(mIndex, channel);
                mQueue.set(mIndex, found);
                showTitle(found);
                if (!mSwapNoteShown) {
                    mSwapNoteShown = true;
                    Toast.makeText(this, getString(R.string.iptv_quality_swapped, found.name), Toast.LENGTH_LONG).show();
                }
                beginPlayback(found);
            });
        }, "iptv-quality-copy").start();
    }

    private synchronized IptvAlternatives.QualityIndex qualityIndex(Context context) {
        if (mQualityIndex == null) {
            mQualityIndex = new IptvAlternatives.QualityIndex(IptvStore.instance(context).load());
        }
        return mQualityIndex;
    }

    private void startPlayback(IptvChannel channel, String url, String userAgent) {
        mPlayUrl = url;
        mPlayUa = userAgent;
        if (IptvPlayerUrls.looksLikeDash(url)) {
            mSources = new int[] {SRC_DASH, SRC_HLS, SRC_PROGRESSIVE};
        } else if (IptvPlayerUrls.looksLikeHls(url)) {
            mSources = new int[] {SRC_HLS, SRC_PROGRESSIVE};
        } else {
            mSources = new int[] {SRC_PROGRESSIVE, SRC_HLS};
        }
        mSourceAttempt = 0;
        prepare(channel);
    }

    /** Stalker portal channels have no fixed url: ask the portal for a fresh stream link (network, so off the UI thread). */
    private void resolvePortalChannel(IptvChannel channel, boolean fresh) {
        final int request = mPlayRequest;
        final Context context = getApplicationContext();
        showStatus(getString(R.string.iptv_player_loading));
        if (mPlayer != null) {
            mPlayer.stop(); // do not keep playing the previous channel while the portal answers
        }
        new Thread(() -> {
            StalkerClient.Resolved resolved = null;
            String error = null;
            try {
                resolved = StalkerClient.resolve(new HttpIptvFetcher(IptvPrefs.instance(context).load()), channel.url, fresh);
            } catch (Throwable t) {
                error = t.getMessage();
            }
            final StalkerClient.Resolved result = resolved;
            final String failure = error;
            mHandler.post(() -> {
                if (request != mPlayRequest || mPlayer == null || isFinishing()) {
                    return; // user already zapped to another channel / left the player
                }
                if (result != null) {
                    startPlayback(channel, result.url, result.userAgent);
                } else {
                    showStatus(getString(R.string.iptv_player_error) + (failure == null || failure.isEmpty() ? "" : "\n" + failure));
                }
            });
        }, "iptv-portal-link").start();
    }

    private void prepare(IptvChannel channel) {
        if (mPlayer == null) {
            return;
        }
        mPlayer.prepare(createSource(channel, mSources[mSourceAttempt]), true, true);
        mPlayer.setPlayWhenReady(true);
    }

    private MediaSource createSource(IptvChannel channel, int type) {
        IptvPrefs prefs = IptvPrefs.instance(this);
        String ua = channel.userAgent != null && !channel.userAgent.trim().isEmpty() ? channel.userAgent.trim()
                : prefs.getUserAgent().isEmpty() ? HttpIptvFetcher.DEFAULT_USER_AGENT : prefs.getUserAgent();

        if (mPlayUa != null) {
            ua = mPlayUa; // portal streams expect the set-top box user agent
        }

        DefaultHttpDataSourceFactory http = new DefaultHttpDataSourceFactory(ua, 15_000, 30_000, true);
        if (channel.referrer != null && !channel.referrer.trim().isEmpty()) {
            http.setDefaultRequestProperty("Referer", channel.referrer.trim());
        }
        DefaultDataSourceFactory dataSource = new DefaultDataSourceFactory(this, http); // also handles udp://
        Uri uri = Uri.parse(mPlayUrl.trim());

        if (type == SRC_DASH) {
            return new DashMediaSource.Factory(dataSource).createMediaSource(uri);
        }

        if (type == SRC_HLS) {
            return new HlsMediaSource.Factory(dataSource).setAllowChunklessPreparation(true).createMediaSource(uri);
        }

        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES | DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS);
        return new ProgressiveMediaSource.Factory(dataSource, extractors).createMediaSource(uri);
    }

    /** Short, human readable reason of a player error. A server answer (HTTP code) is the most useful thing to know. */
    private static String describeError(ExoPlaybackException error) {
        Throwable root = error.type == ExoPlaybackException.TYPE_SOURCE ? error.getSourceException() : error.getCause();
        Throwable t = root;
        for (int depth = 0; t != null && depth < 6; depth++, t = t.getCause()) {
            if (t instanceof HttpDataSource.InvalidResponseCodeException) {
                return "HTTP " + ((HttpDataSource.InvalidResponseCodeException) t).responseCode;
            }
        }
        if (root == null) {
            return "";
        }
        String m = root.getMessage();
        return root.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
    }

    private void handleError(ExoPlaybackException error) {
        // The guesses below (progressive / HLS / DASH) each fail in their own way; the first server answer (HTTP code)
        // is the real reason, the later "not a playlist" parse errors only hide it.
        String why = describeError(error);
        if (!why.isEmpty() && (mErrorReason.isEmpty() || (why.startsWith("HTTP ") && !mErrorReason.startsWith("HTTP ")))) {
            mErrorReason = why;
        }
        if (error.type == ExoPlaybackException.TYPE_SOURCE && error.getSourceException() instanceof BehindLiveWindowException) {
            prepare(mQueue.get(mIndex)); // live window passed: just reload
            return;
        }

        if (mSourceAttempt + 1 < mSources.length) { // wrong guess of the stream type: try the other one
            mSourceAttempt++;
            prepare(mQueue.get(mIndex));
            return;
        }

        IptvChannel current = mQueue.get(mIndex);
        if (StalkerLink.isStalker(current.url) && !mPortalRetried) { // portal link expired: ask for a new one once
            mPortalRetried = true;
            mPlayRequest++;
            resolvePortalChannel(current, true);
            return;
        }

        if (mOriginals.containsKey(mIndex)) { // the lighter copy is dead: go back to the channel that was chosen
            Toast.makeText(this, R.string.iptv_quality_swap_failed, Toast.LENGTH_LONG).show();
            playChannel(mIndex, false);
            return;
        }

        showStatus(getString(R.string.iptv_player_error) + (mErrorReason.isEmpty() ? "" : "\n" + mErrorReason));
    }

    /** True when the channel is locked and the PIN was not entered yet (the manager passes it on once after unlocking). */
    private boolean needsPin(IptvChannel channel) {
        String key = channel.key();
        if (!IptvUserData.instance(this).isLocked(key) || key.equals(mUnlockedKey)) {
            return false;
        }
        if (IptvPin.consumeAllowance(key)) {
            mUnlockedKey = key;
            return false;
        }
        return true;
    }

    /** Zaps to the next / previous channel, passing over the ones marked "skip" and the locked ones. */
    private void zap(int direction) {
        int size = mQueue.size();
        IptvUserData user = IptvUserData.instance(this);
        Set<String> skipped = user.skipped();
        Set<String> locked = user.locked();
        for (int step = 1; step <= size; step++) {
            int index = ((mIndex + direction * step) % size + size) % size;
            IptvChannel original = mOriginals.get(index); // a lighter copy has another url: judge the chosen channel
            String key = (original != null ? original : mQueue.get(index)).key();
            if (!skipped.contains(key) && !locked.contains(key)) {
                playChannel(index);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ ui

    private void showTitle(IptvChannel channel) {
        String name = channel.name == null || channel.name.trim().isEmpty() ? channel.url : channel.name;
        mTitleView.setText(name + "   (" + (mIndex + 1) + "/" + mQueue.size() + ")");
        mTitleView.setVisibility(View.VISIBLE);
        mHandler.removeCallbacks(mHideTitle);
        mHandler.postDelayed(mHideTitle, OVERLAY_MS);
    }

    private void hideTitle() {
        mTitleView.setVisibility(View.GONE);
    }

    private void showStatus(String text) {
        mStatusView.setText(text);
        mStatusView.setVisibility(View.VISIBLE);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && (event.getKeyCode() == KeyEvent.KEYCODE_MENU || event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS)) {
            showQualityDialog();
            return true;
        }

        int code = event.getKeyCode();
        boolean center = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        // OK over the picture (controls hidden, focus not on a control):
        //   short press = the list of channels slides in on the side
        //   long press  = the playback controls (play / pause, quality, mute ...)
        if (center && !isChannelPanelShown() && !mPlayerView.isControllerVisible() && getCurrentFocus() != mQualityView && getCurrentFocus() != mMuteView) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    mSwallowCenterUp = true;
                    mCenterLongHandled = false;
                } else if (!mCenterLongHandled && event.isLongPress()) {
                    mCenterLongHandled = true;
                    showControls();
                }
                return true;
            }
            if (mSwallowCenterUp) {
                mSwallowCenterUp = false;
                if (!mCenterLongHandled) {
                    showChannelPanel();
                }
                mCenterLongHandled = false;
                return true;
            }
        }
        if (isChannelPanelShown()) {
            return super.dispatchKeyEvent(event); // the list handles the arrows and OK itself
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN && mQueue.size() > 1) {
            int key = event.getKeyCode();
            boolean controls = mPlayerView.isControllerVisible();

            if (key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_PAGE_DOWN || (!controls && key == KeyEvent.KEYCODE_DPAD_DOWN)) {
                zap(1);
                return true;
            }
            if (key == KeyEvent.KEYCODE_CHANNEL_DOWN || key == KeyEvent.KEYCODE_PAGE_UP || (!controls && key == KeyEvent.KEYCODE_DPAD_UP)) {
                zap(-1);
                return true;
            }
        }

        return super.dispatchKeyEvent(event);
    }
}
