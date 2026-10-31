package com.liskovsoft.smartyoutubetv2.common.iptv;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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

    /** @return number of removed channels */
    public synchronized int removeGroup(String group) throws IOException {
        IptvPlaylist current = load();
        int removed = current.removeGroup(group);
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
