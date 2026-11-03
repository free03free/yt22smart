package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.extractor.ts.DefaultTsPayloadReaderFactory;
import com.google.android.exoplayer2.source.BehindLiveWindowException;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.iptv.HttpIptvFetcher;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvChannel;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvPrefs;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvUserData;
import com.liskovsoft.smartyoutubetv2.common.iptv.StalkerClient;
import com.liskovsoft.smartyoutubetv2.common.iptv.StalkerLink;

import java.util.HashMap;
import java.util.Map;

/**
 * Small picture-in-the-list player used by the channel manager: a channel or a movie keeps playing in a small window
 * while the user browses and picks other channels. A tap on the window (or OK on the playing channel) opens it full
 * screen (the manager does that, this class only reports the tap).
 *
 * It follows the lifecycle of the hosting activity: {@link #start()} in onStart, {@link #stop()} in onStop.
 * Movies and episodes remember where they were when the user switches to another entry (kept for the app session).
 */
final class IptvMiniPlayer {
    interface Listener {
        void onTap();
    }

    private static final int SRC_HLS = 0;
    private static final int SRC_PROGRESSIVE = 1;
    private static final int SRC_DASH = 2;
    private static final long MIN_RESUME_MS = 5_000;
    private static final long END_MARGIN_MS = 10_000;

    /** Where non-live entries were left (url -> position). Process wide, so it survives re-creating the manager. */
    private static final Map<String, Long> sPositions = new HashMap<>();

    private final Activity mActivity;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final LinearLayout mPanel;
    private final PlayerView mPlayerView;
    private final TextView mStatus;
    private final TextView mTitle;

    private SimpleExoPlayer mPlayer;
    private boolean mStarted;
    private IptvChannel mChannel;
    private String mPlayUrl;
    private String mPlayUa;
    private int[] mSources = {SRC_PROGRESSIVE, SRC_HLS};
    private int mSourceAttempt;
    /** Incremented for every channel change: a late portal answer for an old channel is dropped. */
    private int mRequest;
    private boolean mPortalRetried;
    private String mErrorReason = "";

    IptvMiniPlayer(Activity activity, final Listener listener) {
        mActivity = activity;

        mPanel = new LinearLayout(activity);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setBackgroundColor(Color.BLACK);
        mPanel.setVisibility(View.GONE);

        FrameLayout video = new FrameLayout(activity);
        mPlayerView = new PlayerView(activity);
        mPlayerView.setUseController(false);
        mPlayerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        mPlayerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        mPlayerView.setKeepContentOnPlayerReset(true);
        video.addView(mPlayerView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mStatus = new TextView(activity);
        mStatus.setTextColor(Color.WHITE);
        mStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mStatus.setGravity(Gravity.CENTER);
        mStatus.setBackgroundColor(0x99000000);
        mStatus.setPadding(dp(6), dp(3), dp(6), dp(3));
        mStatus.setVisibility(View.GONE);
        video.addView(mStatus, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // transparent layer on top: a tap anywhere on the picture asks for full screen
        View tapLayer = new View(activity);
        tapLayer.setClickable(true);
        tapLayer.setOnClickListener(v -> {
            if (listener != null) {
                listener.onTap();
            }
        });
        video.addView(tapLayer, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mPanel.addView(video, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mTitle = new TextView(activity);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        mTitle.setSingleLine(true);
        mTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mTitle.setPadding(dp(6), dp(4), dp(6), dp(4));
        mTitle.setBackgroundColor(0xFF1D3F68);
        mPanel.addView(mTitle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, mActivity.getResources().getDisplayMetrics()));
    }

    /** The view to put in the layout (hidden until a channel is played). */
    View view() {
        return mPanel;
    }

    boolean hasChannel() {
        return mChannel != null;
    }

    IptvChannel channel() {
        return mChannel;
    }

    // ------------------------------------------------------------------ lifecycle

    void start() {
        mStarted = true;
        if (mChannel != null) {
            ensurePlayer();
            begin(mChannel);
        }
    }

    void stop() {
        mStarted = false;
        mHandler.removeCallbacksAndMessages(null);
        mRequest++;
        releasePlayer();
    }

    /** Stops the playback and hides the window. */
    void close() {
        mRequest++;
        mHandler.removeCallbacksAndMessages(null);
        releasePlayer();
        mChannel = null;
        mPanel.setVisibility(View.GONE);
    }

    private void ensurePlayer() {
        if (mPlayer != null) {
            return;
        }
        DefaultTrackSelector selector = new DefaultTrackSelector();
        // a small window does not need more than 720p: saves data and decoder work
        int cap = IptvPrefs.instance(mActivity).getMaxQuality();
        int height = cap > 0 ? Math.min(cap, 720) : 720;
        selector.setParameters(selector.buildUponParameters()
                .setMaxVideoSize(Integer.MAX_VALUE, height)
                .setExceedVideoConstraintsIfNecessary(true));
        mPlayer = ExoPlayerFactory.newSimpleInstance(mActivity, selector);
        mPlayer.addListener(new Player.EventListener() {
            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    mPortalRetried = false;
                    mStatus.setVisibility(View.GONE);
                }
            }

            @Override
            public void onPlayerError(ExoPlaybackException error) {
                handleError(error);
            }
        });
        mPlayerView.setPlayer(mPlayer);
    }

    private void releasePlayer() {
        if (mPlayer != null) {
            savePosition();
            mPlayerView.setPlayer(null);
            mPlayer.release();
            mPlayer = null;
        }
    }

    // ------------------------------------------------------------------ playing

    /** Plays the channel in the window (shows the window). Safe to call before the activity started. */
    void play(IptvChannel channel) {
        if (channel == null) {
            return;
        }
        savePosition(); // the entry that was playing until now
        mChannel = channel;
        mPanel.setVisibility(View.VISIBLE);
        mTitle.setText(name(channel));
        IptvUserData.instance(mActivity).addRecent(channel.url);
        if (mStarted) {
            ensurePlayer();
            begin(channel);
        }
    }

    private static String name(IptvChannel c) {
        return c.name == null || c.name.trim().isEmpty() ? c.url : c.name;
    }

    private void begin(IptvChannel channel) {
        mRequest++;
        mPortalRetried = false;
        mErrorReason = "";
        mPlayUa = null;
        showStatus(mActivity.getString(R.string.iptv_player_loading));
        if (StalkerLink.isStalker(channel.url)) {
            resolvePortal(channel, false);
            return;
        }
        startPlayback(channel, channel.url, null);
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
        prepare(channel, true);
    }

    private void prepare(IptvChannel channel, boolean resume) {
        if (mPlayer == null || mPlayUrl == null) {
            return;
        }
        Long position = resume && channel.kind != IptvChannel.KIND_LIVE ? sPositions.get(channel.key()) : null;
        if (position != null) {
            mPlayer.seekTo(position);
            mPlayer.prepare(createSource(channel, mSources[mSourceAttempt]), false, true);
        } else {
            mPlayer.prepare(createSource(channel, mSources[mSourceAttempt]), true, true);
        }
        mPlayer.setPlayWhenReady(true);
    }

    /** Stalker portal channels have no fixed url: ask the portal for a stream link (network: off the UI thread). */
    private void resolvePortal(final IptvChannel channel, final boolean fresh) {
        final int request = mRequest;
        final android.content.Context context = mActivity.getApplicationContext();
        if (mPlayer != null) {
            mPlayer.stop();
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
                if (request != mRequest || mPlayer == null || mActivity.isFinishing()) {
                    return; // another channel was chosen meanwhile
                }
                if (result != null) {
                    startPlayback(channel, result.url, result.userAgent);
                } else {
                    showStatus(mActivity.getString(R.string.iptv_player_error) + (failure == null || failure.isEmpty() ? "" : "\n" + failure));
                }
            });
        }, "iptv-mini-portal-link").start();
    }

    private MediaSource createSource(IptvChannel channel, int type) {
        IptvPrefs prefs = IptvPrefs.instance(mActivity);
        String ua = channel.userAgent != null && !channel.userAgent.trim().isEmpty() ? channel.userAgent.trim()
                : prefs.getUserAgent().isEmpty() ? HttpIptvFetcher.DEFAULT_USER_AGENT : prefs.getUserAgent();
        if (mPlayUa != null) {
            ua = mPlayUa; // portal streams expect the set-top box user agent
        }
        DefaultHttpDataSourceFactory http = new DefaultHttpDataSourceFactory(ua, 15_000, 30_000, true);
        if (channel.referrer != null && !channel.referrer.trim().isEmpty()) {
            http.setDefaultRequestProperty("Referer", channel.referrer.trim());
        }
        DefaultDataSourceFactory dataSource = new DefaultDataSourceFactory(mActivity, http); // also handles udp://
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

    private void handleError(ExoPlaybackException error) {
        IptvChannel channel = mChannel;
        if (channel == null) {
            return;
        }
        String why = describeError(error);
        if (!why.isEmpty() && (mErrorReason.isEmpty() || (why.startsWith("HTTP ") && !mErrorReason.startsWith("HTTP ")))) {
            mErrorReason = why;
        }
        if (error.type == ExoPlaybackException.TYPE_SOURCE && error.getSourceException() instanceof BehindLiveWindowException) {
            prepare(channel, false); // live window passed: just reload
            return;
        }
        if (mSourceAttempt + 1 < mSources.length) { // wrong guess of the stream type: try the other one
            mSourceAttempt++;
            prepare(channel, true);
            return;
        }
        if (StalkerLink.isStalker(channel.url) && !mPortalRetried) { // portal link expired: ask for a new one once
            mPortalRetried = true;
            mRequest++;
            resolvePortal(channel, true);
            return;
        }
        showStatus(mActivity.getString(R.string.iptv_player_error) + (mErrorReason.isEmpty() ? "" : "\n" + mErrorReason));
    }

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

    private void showStatus(String text) {
        mStatus.setText(text);
        mStatus.setVisibility(View.VISIBLE);
    }

    /** Remembers where a movie / episode was left (live channels have nothing to resume). */
    private void savePosition() {
        if (mPlayer == null || mChannel == null || mChannel.kind == IptvChannel.KIND_LIVE) {
            return;
        }
        long position = mPlayer.getCurrentPosition();
        long duration = mPlayer.getDuration();
        boolean nearEnd = duration > 0 && position > duration - END_MARGIN_MS;
        if (position >= MIN_RESUME_MS && !nearEnd) {
            sPositions.put(mChannel.key(), position);
        } else {
            sPositions.remove(mChannel.key());
        }
    }
}
