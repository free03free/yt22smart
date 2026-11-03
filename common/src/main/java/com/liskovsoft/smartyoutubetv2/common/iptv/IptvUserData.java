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

    /** Un-favorites several channels at once. @return number actually removed */
    public synchronized int removeFavorites(java.util.Collection<String> urls) {
        return removeMany(KEY_FAVORITES, urls);
    }

    /** Manual reordering of favorites: swaps two entries (everything else keeps its place). @return true if both exist */
    public synchronized boolean swapFavorites(String urlA, String urlB) {
        return swap(KEY_FAVORITES, urlA, urlB);
    }

    public synchronized boolean moveFavoritesBlock(List<String> shown, java.util.Set<String> selected, int mode, String anchor) {
        List<String> order = IptvPlaylist.reorderKeys(read(KEY_FAVORITES), shown, selected, mode, anchor);
        if (order == null) {
            return false;
        }
        write(KEY_FAVORITES, order);
        return true;
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

    /** Removes several entries from "recently watched" at once. @return number actually removed */
    public synchronized int removeRecents(java.util.Collection<String> urls) {
        return removeMany(KEY_RECENT, urls);
    }

    public synchronized void clearAll() {
        mPrefs.edit().clear().apply();
    }

    // ------------------------------------------------------------ channel manager: lock / skip / pin

    private static final String KEY_LOCKED = "locked";
    private static final String KEY_SKIPPED = "skipped";
    private static final String KEY_PIN = "pin";

    /** Re-orders the favorites to match {@code newOrder} (others keep their slots). @return true if the order changed */
    public synchronized boolean reorderFavorites(List<String> newOrder) {
        List<String> all = read(KEY_FAVORITES);
        List<String> order = IptvPlaylist.slotReorder(all, newOrder);
        if (order.equals(all)) {
            return false;
        }
        write(KEY_FAVORITES, order);
        return true;
    }

    /** Adds / removes channels from the favorites in one go. */
    public synchronized void setFavorites(java.util.Collection<String> urls, boolean favorite) {
        setMany(KEY_FAVORITES, urls, favorite);
    }

    public synchronized boolean isLocked(String url) {
        return url != null && read(KEY_LOCKED).contains(url.trim());
    }

    public synchronized java.util.Set<String> locked() {
        return new java.util.HashSet<>(read(KEY_LOCKED));
    }

    public synchronized void setLocked(java.util.Collection<String> urls, boolean locked) {
        setMany(KEY_LOCKED, urls, locked);
    }

    public synchronized boolean isSkipped(String url) {
        return url != null && read(KEY_SKIPPED).contains(url.trim());
    }

    public synchronized java.util.Set<String> skipped() {
        return new java.util.HashSet<>(read(KEY_SKIPPED));
    }

    /** Skipped channels stay in the library but are passed over when zapping with Up / Down in the player. */
    public synchronized void setSkipped(java.util.Collection<String> urls, boolean skipped) {
        setMany(KEY_SKIPPED, urls, skipped);
    }

    /** @return the parental PIN, or an empty string when none was set yet */
    public synchronized String getPin() {
        return mPrefs.getString(KEY_PIN, "");
    }

    public synchronized void setPin(String pin) {
        mPrefs.edit().putString(KEY_PIN, pin == null ? "" : pin.trim()).apply();
    }

    // ------------------------------------------------------------------ bundles (extra favorite lists)

    private static final String KEY_BUNDLES = "bundles";       // lines: id <TAB> name, in the order shown
    private static final String KEY_BUNDLE_SEQ = "bundle_seq";
    private static final String KEY_NUMBERS = "numbers";       // lines: number <TAB> url

    /** An extra favorites list ("Favorites 2", "Kids" ...). The classic favorites list stays as it was. */
    public static final class Bundle {
        public final String id;
        public final String name;

        Bundle(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static String bundleKey(String id) {
        return "bundle_" + id;
    }

    private static String cleanName(String name) {
        return name == null ? "" : name.replace('\t', ' ').replace('\n', ' ').trim();
    }

    public synchronized List<Bundle> bundles() {
        List<Bundle> result = new ArrayList<>();
        for (String line : read(KEY_BUNDLES)) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                result.add(new Bundle(line.substring(0, tab), line.substring(tab + 1)));
            }
        }
        return result;
    }

    public synchronized String bundleName(String id) {
        for (Bundle b : bundles()) {
            if (b.id.equals(id)) {
                return b.name;
            }
        }
        return null;
    }

    private void writeBundles(List<Bundle> list) {
        List<String> lines = new ArrayList<>();
        for (Bundle b : list) {
            lines.add(b.id + "\t" + b.name);
        }
        write(KEY_BUNDLES, lines);
    }

    /** @return the id of the new bundle, or null when the name is blank */
    public synchronized String createBundle(String name) {
        String clean = cleanName(name);
        if (clean.isEmpty()) {
            return null;
        }
        int seq = mPrefs.getInt(KEY_BUNDLE_SEQ, 1) + 1; // "1" is the classic favorites list
        mPrefs.edit().putInt(KEY_BUNDLE_SEQ, seq).apply();
        List<Bundle> list = bundles();
        String id = String.valueOf(seq);
        list.add(new Bundle(id, clean));
        writeBundles(list);
        return id;
    }

    public synchronized boolean renameBundle(String id, String name) {
        String clean = cleanName(name);
        if (clean.isEmpty()) {
            return false;
        }
        List<Bundle> list = bundles();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.set(i, new Bundle(id, clean));
                found = true;
            }
        }
        if (found) {
            writeBundles(list);
        }
        return found;
    }

    public synchronized void deleteBundle(String id) {
        List<Bundle> list = bundles();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
            }
        }
        writeBundles(list);
        mPrefs.edit().remove(bundleKey(id)).apply();
    }

    /** Moves a bundle one place earlier (-1) or later (+1) among the tabs. @return true if it moved */
    public synchronized boolean moveBundle(String id, int delta) {
        List<Bundle> list = bundles();
        int from = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                from = i;
            }
        }
        int to = from + delta;
        if (from < 0 || to < 0 || to >= list.size()) {
            return false;
        }
        java.util.Collections.swap(list, from, to);
        writeBundles(list);
        return true;
    }

    public synchronized List<String> bundleItems(String id) {
        return read(bundleKey(id));
    }

    public synchronized boolean inBundle(String id, String url) {
        return url != null && read(bundleKey(id)).contains(url.trim());
    }

    public synchronized void addToBundle(String id, java.util.Collection<String> urls) {
        setMany(bundleKey(id), urls, true);
    }

    public synchronized int removeFromBundle(String id, java.util.Collection<String> urls) {
        return removeMany(bundleKey(id), urls);
    }

    /** Re-orders the bundle to match {@code newOrder} (others keep their slots). @return true if the order changed */
    public synchronized boolean reorderBundle(String id, List<String> newOrder) {
        List<String> all = read(bundleKey(id));
        List<String> order = IptvPlaylist.slotReorder(all, newOrder);
        if (order.equals(all)) {
            return false;
        }
        write(bundleKey(id), order);
        return true;
    }

    /** Removes the channels from every bundle (used when they are deleted from the library). */
    public synchronized void removeFromAllBundles(java.util.Collection<String> urls) {
        for (Bundle b : bundles()) {
            removeMany(bundleKey(b.id), urls);
        }
    }

    // ------------------------------------------------------------------ fixed channel numbers

    /** @return url -> fixed number chosen by the user */
    public synchronized java.util.Map<String, Integer> fixedNumbers() {
        java.util.Map<String, Integer> result = new java.util.HashMap<>();
        for (String line : read(KEY_NUMBERS)) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                try {
                    result.put(line.substring(tab + 1), Integer.parseInt(line.substring(0, tab)));
                } catch (NumberFormatException ignored) {
                    // damaged line: skipped
                }
            }
        }
        return result;
    }

    private void writeNumbers(java.util.Map<String, Integer> map) {
        List<String> lines = new ArrayList<>();
        for (java.util.Map.Entry<String, Integer> e : map.entrySet()) {
            lines.add(e.getValue() + "\t" + e.getKey());
        }
        write(KEY_NUMBERS, lines);
    }

    /**
     * Gives the channel a fixed number. If another channel had that fixed number it loses it
     * (it goes back to automatic numbering).
     * @return the url of the channel that lost the number, or null
     */
    public synchronized String setFixedNumber(String url, int number) {
        if (url == null || number < 1) {
            return null;
        }
        java.util.Map<String, Integer> map = fixedNumbers();
        String key = url.trim();
        String loser = null;
        for (java.util.Map.Entry<String, Integer> e : new ArrayList<>(map.entrySet())) {
            if (e.getValue() == number && !e.getKey().equals(key)) {
                loser = e.getKey();
                map.remove(loser);
            }
        }
        map.put(key, number);
        writeNumbers(map);
        return loser;
    }

    public synchronized void clearFixedNumber(java.util.Collection<String> urls) {
        java.util.Map<String, Integer> map = fixedNumbers();
        boolean changed = false;
        for (String url : urls) {
            if (url != null && map.remove(url.trim()) != null) {
                changed = true;
            }
        }
        if (changed) {
            writeNumbers(map);
        }
    }

    private void setMany(String key, java.util.Collection<String> urls, boolean on) {
        if (urls == null || urls.isEmpty()) {
            return;
        }
        List<String> list = read(key);
        boolean changed = false;
        for (String url : urls) {
            if (url == null) {
                continue;
            }
            String k = url.trim();
            if (on) {
                if (!list.contains(k)) {
                    list.add(k);
                    changed = true;
                }
            } else if (list.remove(k)) {
                changed = true;
            }
        }
        if (changed) {
            write(key, list);
        }
    }

    private int removeMany(String key, java.util.Collection<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return 0;
        }
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (String url : urls) {
            if (url != null) {
                keys.add(url.trim());
            }
        }
        List<String> list = read(key);
        int before = list.size();
        list.removeAll(keys);
        if (list.size() != before) {
            write(key, list);
        }
        return before - list.size();
    }

    private boolean swap(String key, String urlA, String urlB) {
        if (urlA == null || urlB == null) {
            return false;
        }
        List<String> list = read(key);
        int a = list.indexOf(urlA.trim());
        int b = list.indexOf(urlB.trim());
        if (a < 0 || b < 0) {
            return false;
        }
        java.util.Collections.swap(list, a, b);
        write(key, list);
        return true;
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
