package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Activity;
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
import android.widget.FrameLayout;
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
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.iptv.HttpIptvFetcher;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvChannel;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvPrefs;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvUserData;
import com.liskovsoft.smartyoutubetv2.common.iptv.StalkerClient;
import com.liskovsoft.smartyoutubetv2.common.iptv.StalkerLink;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Built-in full screen player for IPTV channels (ExoPlayer: HLS + DASH (.mpd) + MPEG-TS/progressive + udp,
 * plus Stalker / Ministra portal channels whose stream link is created on demand).
 *
 * Remote / keyboard:
 *   Up / Down (controller hidden) = previous / next channel of the opened list
 *   Channel +/-, Page up/down      = next / previous channel
 *   OK                             = show the playback controls
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
    private int mSourceAttempt;
    private SimpleExoPlayer mPlayer;
    private PlayerView mPlayerView;
    private TextView mTitleView;
    private TextView mStatusView;

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

        setContentView(root);
        hideSystemUi();
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
        mPlayer = ExoPlayerFactory.newSimpleInstance(this);
        mPlayer.addListener(new Player.EventListener() {
            @Override
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    mPortalRetried = false; // a later link expiry may be fixed with a new link again
                    mStatusView.setVisibility(View.GONE);
                }
            }

            @Override
            public void onPlayerError(ExoPlaybackException error) {
                handleError(error);
            }
        });
        mPlayerView.setPlayer(mPlayer);
        playChannel(mIndex);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mHandler.removeCallbacksAndMessages(null);
        if (mPlayer != null) {
            mPlayerView.setPlayer(null);
            mPlayer.release();
            mPlayer = null;
        }
    }

    // ------------------------------------------------------------------ playback

    private void playChannel(int index) {
        if (mQueue.isEmpty() || mPlayer == null) {
            return;
        }

        mIndex = (index % mQueue.size() + mQueue.size()) % mQueue.size(); // wrap around
        IptvChannel channel = mQueue.get(mIndex);
        IptvUserData.instance(this).addRecent(channel.url);

        showTitle(channel);
        showStatus(getString(R.string.iptv_player_loading));

        mPlayRequest++;
        mPortalRetried = false;
        if (StalkerLink.isStalker(channel.url)) {
            resolvePortalChannel(channel, false);
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

    private void handleError(ExoPlaybackException error) {
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

        showStatus(getString(R.string.iptv_player_error));
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
        if (event.getAction() == KeyEvent.ACTION_DOWN && mQueue.size() > 1) {
            int key = event.getKeyCode();
            boolean controls = mPlayerView.isControllerVisible();

            if (key == KeyEvent.KEYCODE_CHANNEL_UP || key == KeyEvent.KEYCODE_PAGE_DOWN || (!controls && key == KeyEvent.KEYCODE_DPAD_DOWN)) {
                playChannel(mIndex + 1);
                return true;
            }
            if (key == KeyEvent.KEYCODE_CHANNEL_DOWN || key == KeyEvent.KEYCODE_PAGE_UP || (!controls && key == KeyEvent.KEYCODE_DPAD_UP)) {
                playChannel(mIndex - 1);
                return true;
            }
        }

        return super.dispatchKeyEvent(event);
    }
}
