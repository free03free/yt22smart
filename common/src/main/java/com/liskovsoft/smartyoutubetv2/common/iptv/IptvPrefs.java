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
    private final Context mContext;

    private IptvPrefs(Context context) {
        mContext = context.getApplicationContext();
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
        o.blacklist = mPrefs.getString("blacklistWords", "");
        o.portalParallel = mPrefs.getInt("portalParallel", 4);
        o.portalConnectTimeoutMs = mPrefs.getInt("portalConnectTimeoutMs", 6_000);
        o.portalReadTimeoutMs = mPrefs.getInt("portalReadTimeoutMs", 12_000);
        o.portalBulkReadTimeoutMs = mPrefs.getInt("portalBulkReadTimeoutMs", 60_000);
        o.portalParallelMacs = mPrefs.getInt("portalParallelMacs", 3);
        o.stalkerLive = mPrefs.getBoolean("stalkerLive", true);
        o.stalkerMovies = mPrefs.getBoolean("stalkerMovies", false);
        o.stalkerSeries = mPrefs.getBoolean("stalkerSeries", false);
        o.stalkerPerMac = mPrefs.getBoolean("stalkerPerMac", true);
        o.stalkerMaxItems = mPrefs.getInt("stalkerMaxItems", 5000);
        o.portalDeadlineMs = mPrefs.getLong("portalDeadlineMs", 120_000L);
        for (java.util.Map.Entry<String, ?> e : mPrefs.getAll().entrySet()) {
            if (e.getKey().startsWith("pt:") && e.getValue() instanceof Integer && (Integer) e.getValue() != 0) {
                o.portalTypes.put(e.getKey().substring(3), (Integer) e.getValue());
            }
        }
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
    public static final int UI_CARDS = 0;
    public static final int UI_MANAGER = 1;

    /** How channel lists open: as cards (classic) or in the numbered channel manager. */
    public int getUiMode() { return mPrefs.getInt("uiMode", UI_CARDS); }
    public void setUiMode(int mode) { mPrefs.edit().putInt("uiMode", mode).apply(); }

    /** Small window in the channel manager that keeps playing while the user browses (default on). */
    public boolean isMiniPlayer() { return mPrefs.getBoolean("miniPlayer", true); }
    /** With the small window playing: Left/Right select the window (true) instead of switching between groups (false). */
    public boolean isMiniSelect() { return mPrefs.getBoolean("miniSelect", true); }
    public void setMiniSelect(boolean on) { mPrefs.edit().putBoolean("miniSelect", on).apply(); }
    public void setMiniPlayer(boolean on) { mPrefs.edit().putBoolean("miniPlayer", on).apply(); }

    public int getPlayerMode() { return mPrefs.getInt("playerMode", PLAYER_INTERNAL); }
    public void setPlayerMode(int mode) { mPrefs.edit().putInt("playerMode", mode).apply(); }

    /** Selectable video height limits of the built-in player (0 = auto, no limit). */
    public static final int[] QUALITY_STEPS = {0, 1080, 720, 480, 360, 240, 144};

    /**
     * Highest video height (in pixels) the built-in player may use: 0 = auto (no limit).
     * Saves data on slow / metered connections. Applies to channels, movies and series alike.
     */
    // --- channel list shown over the video in the internal player ---
    public static final int PANEL_LEFT = 0, PANEL_RIGHT = 1;
    public int getPanelSide() { return mPrefs.getInt("panelSide", PANEL_LEFT); }
    public void setPanelSide(int side) { mPrefs.edit().putInt("panelSide", side == PANEL_RIGHT ? PANEL_RIGHT : PANEL_LEFT).apply(); }
    /** Background opacity of the panel, 20..100 percent. */
    public int getPanelOpacity() { return mPrefs.getInt("panelOpacity", 90); }
    public void setPanelOpacity(int pct) { mPrefs.edit().putInt("panelOpacity", Math.max(20, Math.min(100, pct))).apply(); }
    /** Panel width as a percent of the screen width, 20..60. */
    public int getPanelWidth() { return mPrefs.getInt("panelWidth", 34); }
    public void setPanelWidth(int pct) { mPrefs.edit().putInt("panelWidth", Math.max(20, Math.min(60, pct))).apply(); }
    /** Channel name size in sp, 12..30. */
    public int getPanelFont() { return mPrefs.getInt("panelFont", 17); }
    public void setPanelFont(int sp) { mPrefs.edit().putInt("panelFont", Math.max(12, Math.min(30, sp))).apply(); }

    public int getMaxQuality() { return mPrefs.getInt("maxQuality", 0); }
    public void setMaxQuality(int height) { mPrefs.edit().putInt("maxQuality", Math.max(0, height)).apply(); }

    /** With a quality limit: play a lighter copy (SD / 360p ...) of the same channel from the library when there is one. */
    public boolean isPreferLowerCopy() { return mPrefs.getBoolean("preferLowerCopy", true); }
    public void setPreferLowerCopy(boolean on) { mPrefs.edit().putBoolean("preferLowerCopy", on).apply(); }

    /** Analyze the lists first and let the user choose groups / languages / channels (default). */
    public boolean isPreviewEnabled() { return mPrefs.getBoolean("previewBeforeImport", true); }
    public void setPreviewEnabled(boolean enabled) { mPrefs.edit().putBoolean("previewBeforeImport", enabled).apply(); }

    /** Show channel names without "|AR|" / "[US]" style prefixes. */
    public boolean isHidePrefix() { return mPrefs.getBoolean("hideNamePrefix", false); }
    public void setHidePrefix(boolean on) { mPrefs.edit().putBoolean("hideNamePrefix", on).apply(); }

    /** Channel lists are shown sorted A-Z instead of the original order. */
    public boolean isSortAz() { return mPrefs.getBoolean("sortAz", false); }
    public void setSortAz(boolean on) { mPrefs.edit().putBoolean("sortAz", on).apply(); }

    /** Words (comma separated, any case) that hide a channel / group when it is imported. */
    public String getBlacklist() { return mPrefs.getString("blacklistWords", ""); }
    public void setBlacklist(String words) { mPrefs.edit().putString("blacklistWords", words == null ? "" : words.trim()).apply(); }

    /** Where a list came from (a link), so "update this list" can fetch it again. */
    public String getListSource(String list) { return mPrefs.getString("listSource:" + list, null); }
    public void saveListSources(java.util.Map<String, String> sources) {
        if (sources == null || sources.isEmpty()) {
            return;
        }
        android.content.SharedPreferences.Editor e = mPrefs.edit();
        for (java.util.Map.Entry<String, String> s : sources.entrySet()) {
            e.putString("listSource:" + s.getKey(), s.getValue());
        }
        e.apply();
    }

    /** Largest upload accepted from the phone, in MB. 0 = no limit. */
    public int getReceiveMaxMb() { return mPrefs.getInt("receiveMaxMb", 200); }
    public void setReceiveMaxMb(int mb) { mPrefs.edit().putInt("receiveMaxMb", Math.max(0, mb)).apply(); }

    public boolean getBool(String key, boolean def) { return mPrefs.getBoolean(key, def); }
    public void setBool(String key, boolean value) { mPrefs.edit().putBoolean(key, value).apply(); }
    public int getIntValue(String key, int def) { return mPrefs.getInt(key, def); }

    // ---- receiver / satellite shortcut (see misc/IptvSatellite)
    /** What the "satellite" shortcut opens: 0 = off (card hidden), 1 = home, 2 = live tv, 3 = tv input, 4 = app, 5 = package, 6 = stream. */
    public int getSatMode() { return mPrefs.getInt("satMode", 0); }
    public void setSatMode(int mode) { mPrefs.edit().putInt("satMode", mode).apply(); }
    public String getSatInput() { return mPrefs.getString("satInput", ""); }
    public String getSatInputLabel() { return mPrefs.getString("satInputLabel", ""); }
    public void setSatInput(String id, String label) {
        mPrefs.edit().putString("satInput", id == null ? "" : id).putString("satInputLabel", label == null ? "" : label).apply();
    }
    public String getSatApp() { return mPrefs.getString("satApp", ""); }
    public String getSatAppLabel() { return mPrefs.getString("satAppLabel", ""); }
    public void setSatApp(String pkg, String label) {
        mPrefs.edit().putString("satApp", pkg == null ? "" : pkg).putString("satAppLabel", label == null ? "" : label).apply();
    }
    public boolean isSatShowSystem() { return mPrefs.getBoolean("satShowSystem", false); }
    public void setSatShowSystem(boolean show) { mPrefs.edit().putBoolean("satShowSystem", show).apply(); }
    public String getSatPackage() { return mPrefs.getString("satPackage", ""); }
    public void setSatPackage(String pkg) { mPrefs.edit().putString("satPackage", pkg == null ? "" : pkg.trim()).apply(); }
    public String getSatStream() { return mPrefs.getString("satStream", ""); }
    public void setSatStream(String url) { mPrefs.edit().putString("satStream", url == null ? "" : url.trim()).apply(); }

    /** Hide the channels the last check found not working. */
    public boolean isHideDead() { return mPrefs.getBoolean("hideDead", false); }
    public void setHideDead(boolean on) { mPrefs.edit().putBoolean("hideDead", on).apply(); }

    // ---- source picker (the window that lists what the files hold before anything is downloaded)

    public static final int ASK_ALWAYS = 0, ASK_MANY = 1, ASK_NEVER = 2;

    /** When the picker opens: {@link #ASK_ALWAYS} (2 or more items), {@link #ASK_MANY} (more than the threshold), {@link #ASK_NEVER}. */
    public int getPickerMode() { return mPrefs.getInt("pickerMode", ASK_ALWAYS); }
    public void setPickerMode(int mode) { mPrefs.edit().putInt("pickerMode", mode).apply(); }
    public int getPickerThreshold() { return mPrefs.getInt("pickerThreshold", 5); }
    public void setPickerThreshold(int n) { mPrefs.edit().putInt("pickerThreshold", Math.max(1, n)).apply(); }
    /** The picker lists the items under their domain instead of under their type. */
    public boolean isGroupByDomain() { return mPrefs.getBoolean("pickerGroupDomain", false); }
    public void setGroupByDomain(boolean on) { mPrefs.edit().putBoolean("pickerGroupDomain", on).apply(); }
    /** Remember which portals failed (and show it the next time). */
    public boolean isRememberFailures() { return mPrefs.getBoolean("rememberFailures", true); }
    public void setRememberFailures(boolean on) { mPrefs.edit().putBoolean("rememberFailures", on).apply(); }
    /** Remembered failures start un-ticked. */
    public boolean isAutoUntick() { return mPrefs.getBoolean("autoUntickFailed", true); }
    public void setAutoUntick(boolean on) { mPrefs.edit().putBoolean("autoUntickFailed", on).apply(); }
    /** A remembered failure is forgotten after this many days (0 = never). */
    public int getMemoryDays() { return mPrefs.getInt("memoryDays", 3); }
    public void setMemoryDays(int days) { mPrefs.edit().putInt("memoryDays", Math.max(0, days)).apply(); }

    /** What to fetch from ONE portal: bits 1 live, 2 movies, 4 series; 0 = the general settings. */
    public int getPortalTypes(String portalKey) { return mPrefs.getInt("pt:" + portalKey, 0); }
    public void setPortalTypes(String portalKey, int bits) {
        if (bits == 0) {
            mPrefs.edit().remove("pt:" + portalKey).apply();
        } else {
            mPrefs.edit().putInt("pt:" + portalKey, bits).apply();
        }
    }

    public void clearPortalTypes() {
        SharedPreferences.Editor e = mPrefs.edit();
        for (String k : mPrefs.getAll().keySet()) {
            if (k.startsWith("pt:")) {
                e.remove(k);
            }
        }
        e.apply();
    }

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
