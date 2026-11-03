package com.liskovsoft.smartyoutubetv2.common.iptv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;

/**
 * Remembers which Stalker portals / MACs failed (and why), so the next time the source picker marks them and leaves them
 * un-ticked instead of wasting minutes on the same dead portals. A failure is forgotten after a number of days (the portal may be back).
 */
public final class IptvPortalMemory {
    private static final String PREFS = "iptv_portal_memory";
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static IptvPortalMemory sInstance;
    private final SharedPreferences mPrefs;

    public static final class Entry {
        public final long time;
        public final StalkerFailure reason;

        Entry(long time, StalkerFailure reason) {
            this.time = time;
            this.reason = reason;
        }
    }

    private IptvPortalMemory(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized IptvPortalMemory instance(Context context) {
        if (sInstance == null) {
            sInstance = new IptvPortalMemory(context);
        }
        return sInstance;
    }

    public void recordPortal(String portalKey, boolean ok, String reasonName) {
        put("p:" + portalKey, ok, reasonName);
    }

    public void recordMac(String portalKey, String mac, boolean ok, String reasonName) {
        put("m:" + portalKey + "|" + mac, ok, reasonName);
    }

    private void put(String key, boolean ok, String reasonName) {
        mPrefs.edit().putString(key, ok ? "O|" + System.currentTimeMillis() : "F|" + System.currentTimeMillis() + "|" + reasonName).apply();
    }

    /** @return the remembered failure of this portal, null when it never failed, worked since, or the failure is older than {@code maxAgeDays} */
    public Entry failedPortal(String portalKey, int maxAgeDays) {
        return failure("p:" + portalKey, maxAgeDays);
    }

    public Entry failedMac(String portalKey, String mac, int maxAgeDays) {
        return failure("m:" + portalKey + "|" + mac, maxAgeDays);
    }

    private Entry failure(String key, int maxAgeDays) {
        String v = mPrefs.getString(key, null);
        if (v == null || !v.startsWith("F|")) {
            return null;
        }
        String[] parts = v.split("\\|", 3);
        long time;
        try {
            time = Long.parseLong(parts[1]);
        } catch (RuntimeException e) {
            return null;
        }
        if (maxAgeDays > 0 && System.currentTimeMillis() - time > maxAgeDays * DAY_MS) {
            return null;
        }
        return new Entry(time, StalkerFailure.of(parts.length > 2 ? parts[2] : ""));
    }

    /** How many portals are currently remembered as failed (any age). */
    public int failedCount() {
        int n = 0;
        for (Map.Entry<String, ?> e : mPrefs.getAll().entrySet()) {
            if (e.getKey().startsWith("p:") && String.valueOf(e.getValue()).startsWith("F|")) {
                n++;
            }
        }
        return n;
    }

    public void clear() {
        mPrefs.edit().clear().apply();
    }
}
