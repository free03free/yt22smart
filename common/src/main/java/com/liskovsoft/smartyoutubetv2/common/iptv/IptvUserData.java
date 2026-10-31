package com.liskovsoft.smartyoutubetv2.common.iptv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** Favorites and recently watched channels (stored as stream urls, newest first for "recent"). */
public final class IptvUserData {
    private static final String PREFS = "iptv_user";
    private static final String KEY_FAVORITES = "favorites";
    private static final String KEY_RECENT = "recent";
    private static final int MAX_RECENT = 40;
    private static IptvUserData sInstance;
    private final SharedPreferences mPrefs;

    private IptvUserData(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized IptvUserData instance(Context context) {
        if (sInstance == null) {
            sInstance = new IptvUserData(context);
        }
        return sInstance;
    }

    public synchronized List<String> favorites() {
        return read(KEY_FAVORITES);
    }

    public synchronized boolean isFavorite(String url) {
        return url != null && read(KEY_FAVORITES).contains(url.trim());
    }

    /** @return true if the channel is a favorite after the call */
    public synchronized boolean toggleFavorite(String url) {
        if (url == null) {
            return false;
        }
        String key = url.trim();
        List<String> list = read(KEY_FAVORITES);
        boolean nowFavorite = !list.remove(key);
        if (nowFavorite) {
            list.add(key);
        }
        write(KEY_FAVORITES, list);
        return nowFavorite;
    }

    public synchronized void clearFavorites() {
        mPrefs.edit().remove(KEY_FAVORITES).apply();
    }

    public synchronized List<String> recents() {
        return read(KEY_RECENT);
    }

    public synchronized void addRecent(String url) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        String key = url.trim();
        List<String> list = read(KEY_RECENT);
        list.remove(key);
        list.add(0, key);
        while (list.size() > MAX_RECENT) {
            list.remove(list.size() - 1);
        }
        write(KEY_RECENT, list);
    }

    public synchronized void clearRecents() {
        mPrefs.edit().remove(KEY_RECENT).apply();
    }

    public synchronized void clearAll() {
        mPrefs.edit().clear().apply();
    }

    private List<String> read(String key) {
        List<String> result = new ArrayList<>();
        for (String line : mPrefs.getString(key, "").split("\n")) {
            if (!line.isEmpty()) {
                result.add(line);
            }
        }
        return result;
    }

    private void write(String key, List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            sb.append(s).append('\n');
        }
        mPrefs.edit().putString(key, sb.toString()).apply();
    }
}
