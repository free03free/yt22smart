package com.liskovsoft.smartyoutubetv2.common.iptv;

import android.content.Context;
import android.content.SharedPreferences;

/** Persists {@link IptvOptions}. Everything defaults to "unlimited" (0). */
public final class IptvPrefs {
    private static final String PREFS = "iptv_prefs";
    public static final int PLAYER_INTERNAL = 0;
    public static final int PLAYER_EXTERNAL = 1;
    private static IptvPrefs sInstance;
    private final SharedPreferences mPrefs;

    private IptvPrefs(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized IptvPrefs instance(Context context) {
        if (sInstance == null) {
            sInstance = new IptvPrefs(context);
        }
        return sInstance;
    }

    public IptvOptions load() {
        IptvOptions o = new IptvOptions();
        o.maxFetches = mPrefs.getInt("maxFetches", 0);
        o.maxLinkDepth = mPrefs.getInt("maxLinkDepth", 0);
        o.maxArchiveDepth = mPrefs.getInt("maxArchiveDepth", 0);
        o.maxArchiveEntries = mPrefs.getInt("maxArchiveEntries", 0);
        o.maxEntryBytes = mPrefs.getLong("maxEntryBytes", 0);
        o.maxArchiveTotalBytes = mPrefs.getLong("maxArchiveTotalBytes", 0);
        o.maxDownloadBytes = mPrefs.getLong("maxDownloadBytes", 0);
        o.probeLimit = mPrefs.getInt("probeLimit", 20);
        o.followRemoteLinks = mPrefs.getBoolean("followRemoteLinks", true);
        o.xtreamFallbacks = mPrefs.getBoolean("xtreamFallbacks", true);
        o.connectTimeoutMs = mPrefs.getInt("connectTimeoutMs", 15_000);
        o.readTimeoutMs = mPrefs.getInt("readTimeoutMs", 40_000);
        String ua = mPrefs.getString("userAgent", null);
        if (ua != null && !ua.trim().isEmpty()) {
            o.userAgent = ua.trim();
        }
        for (String p : mPrefs.getString("passwords", "").split("\n")) {
            if (!p.isEmpty()) {
                o.archivePasswords.add(p);
            }
        }
        return o;
    }

    /** @return {@link #PLAYER_INTERNAL} (default) or {@link #PLAYER_EXTERNAL} */
    public int getPlayerMode() { return mPrefs.getInt("playerMode", PLAYER_INTERNAL); }
    public void setPlayerMode(int mode) { mPrefs.edit().putInt("playerMode", mode).apply(); }

    public void putInt(String key, int v) { mPrefs.edit().putInt(key, v).apply(); }
    public void putLong(String key, long v) { mPrefs.edit().putLong(key, v).apply(); }
    public void putBoolean(String key, boolean v) { mPrefs.edit().putBoolean(key, v).apply(); }

    public void setUserAgent(String ua) { mPrefs.edit().putString("userAgent", ua == null ? "" : ua.trim()).apply(); }
    public String getUserAgent() { return mPrefs.getString("userAgent", ""); }

    /** Passwords are separated by commas or new lines. */
    public void setPasswords(String text) {
        StringBuilder sb = new StringBuilder();
        for (String p : (text == null ? "" : text).split("[,\\n]")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                sb.append(t).append('\n');
            }
        }
        mPrefs.edit().putString("passwords", sb.toString()).apply();
    }

    public String getPasswords() {
        return mPrefs.getString("passwords", "").trim().replace('\n', ',');
    }

    public void resetAll() {
        mPrefs.edit().clear().apply();
    }
}
