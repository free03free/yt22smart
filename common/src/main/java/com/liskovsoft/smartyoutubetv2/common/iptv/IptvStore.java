package com.liskovsoft.smartyoutubetv2.common.iptv;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

/** Keeps the imported library as one private M3U file inside the app storage. */
public final class IptvStore {
    private static final String FILE_NAME = "iptv_library.m3u";
    private static IptvStore sInstance;
    private final File mFile;
    private IptvPlaylist mCache;

    private IptvStore(Context context) {
        mFile = new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    public static synchronized IptvStore instance(Context context) {
        if (sInstance == null) {
            sInstance = new IptvStore(context);
        }
        return sInstance;
    }

    public synchronized IptvPlaylist load() {
        if (mCache != null) {
            return mCache;
        }
        IptvPlaylist playlist = new IptvPlaylist();
        if (mFile.exists()) {
            try {
                playlist.addAll(M3uParser.parse(TextDecoderUtil.decode(readAll(mFile))));
            } catch (IOException e) {
                // unreadable library: start empty
            }
        }
        mCache = playlist;
        return playlist;
    }

    /** @return number of channels that were really new */
    public synchronized int merge(IptvPlaylist incoming) throws IOException {
        IptvPlaylist current = load();
        int added = current.addAll(incoming.getChannels());
        if (added > 0) {
            save(current);
        }
        return added;
    }

    /** @return number of removed channels */
    public synchronized int removeChannel(String url) throws IOException {
        IptvPlaylist current = load();
        int removed = current.removeByUrl(url);
        if (removed > 0) {
            save(current);
        }
        return removed;
    }

    /** Removes several channels at once (one save, not one per channel). @return number of removed channels */
    public synchronized int removeChannels(java.util.Collection<String> urls) throws IOException {
        IptvPlaylist current = load();
        int removed = current.removeByUrls(urls);
        if (removed > 0) {
            save(current);
        }
        return removed;
    }

    /** Manual reordering: swaps two channels' positions (everything else keeps its place). @return true if both exist */
    public synchronized boolean swapChannels(String urlA, String urlB) throws IOException {
        IptvPlaylist current = load();
        boolean ok = current.swap(urlA, urlB);
        if (ok) {
            save(current);
        }
        return ok;
    }

    /** Group move (see IptvPlaylist.reorderKeys): one load, one save. */
    public synchronized boolean moveBlock(List<String> shown, java.util.Set<String> selected, int mode, String anchor) throws IOException {
        IptvPlaylist current = load();
        boolean ok = current.moveBlock(shown, selected, mode, anchor);
        if (ok) {
            save(current);
        }
        return ok;
    }

    /** Moves a channel before/after every other channel of the given (shown) list: one load, one save. */
    public synchronized boolean moveChannelToEdge(String url, List<String> shownUrls, boolean top) throws IOException {
        IptvPlaylist current = load();
        int idx = shownUrls.indexOf(url);
        if (idx < 0) {
            return false;
        }
        boolean ok = true;
        if (top) {
            for (int i = idx - 1; i >= 0; i--) { ok &= current.swap(url, shownUrls.get(i)); }
        } else {
            for (int i = idx + 1; i < shownUrls.size(); i++) { ok &= current.swap(url, shownUrls.get(i)); }
        }
        if (ok) {
            save(current);
        }
        return ok;
    }

    /** Applies a new order to the given channels only (see IptvPlaylist.slotReorder): one load, one save. */
    public synchronized boolean applyOrder(List<String> newOrder) throws IOException {
        IptvPlaylist current = load();
        boolean ok = current.applyOrder(newOrder);
        if (ok) {
            save(current);
        }
        return ok;
    }

    /** Renames one channel: one load, one save. */
    public synchronized boolean renameChannel(String url, String newName) throws IOException {
        IptvPlaylist current = load();
        boolean ok = current.rename(url, newName);
        if (ok) {
            save(current);
        }
        return ok;
    }

    /** @return number of removed channels */
    public synchronized int removeGroup(String group) throws IOException {
        IptvPlaylist current = load();
        int removed = current.removeGroup(group);
        if (removed > 0) {
            save(current);
        }
        return removed;
    }

    /**
     * Update of one list: channels that disappeared from it are removed, new ones are added.
     * Does nothing when the new list is empty (a failed download must not wipe the old one).
     * @return number of channels that were really new
     */
    public synchronized int replaceList(String list, IptvPlaylist incoming) throws IOException {
        if (incoming.isEmpty()) {
            return 0;
        }
        IptvPlaylist current = load();
        java.util.Set<String> fresh = new java.util.HashSet<>();
        for (IptvChannel c : incoming.getChannels()) {
            fresh.add(c.key());
        }
        String l = list == null ? "" : list;
        java.util.List<String> gone = new java.util.ArrayList<>();
        for (IptvChannel c : current.getChannels()) {
            if ((c.list == null ? "" : c.list).equals(l) && !fresh.contains(c.key())) {
                gone.add(c.key());
            }
        }
        for (String url : gone) {
            current.removeByUrl(url);
        }
        int added = current.addAll(incoming.getChannels());
        if (added > 0 || !gone.isEmpty()) {
            save(current);
        }
        return added;
    }

    /** Removes the channels / groups that contain one of the words. @return number of removed channels */
    public synchronized int removeBlacklisted(String words) throws IOException {
        IptvPlaylist current = load();
        IptvPlaylist keep = IptvSelection.withoutBlacklisted(current, words);
        int removed = current.size() - keep.size();
        if (removed > 0) {
            mCache = keep;
            save(keep);
        }
        return removed;
    }

    /** @return number of removed channels */
    public synchronized int removeGroupInList(String group, String list) throws IOException {
        IptvPlaylist current = load();
        int removed = current.removeGroupInList(group, list);
        if (removed > 0) {
            save(current);
        }
        return removed;
    }

    /** @return number of removed channels */
    public synchronized int removeList(String list) throws IOException {
        IptvPlaylist current = load();
        int removed = current.removeList(list);
        if (removed > 0) {
            save(current);
        }
        return removed;
    }

    public synchronized void clear() {
        mCache = new IptvPlaylist();
        //noinspection ResultOfMethodCallIgnored
        mFile.delete();
    }

    private void save(IptvPlaylist playlist) throws IOException {
        File tmp = new File(mFile.getParentFile(), FILE_NAME + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(M3uWriter.write(playlist).getBytes(Charset.forName("UTF-8")));
        }
        if (!tmp.renameTo(mFile)) {
            //noinspection ResultOfMethodCallIgnored
            mFile.delete();
            if (!tmp.renameTo(mFile)) {
                throw new IOException("Cannot save IPTV library");
            }
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
