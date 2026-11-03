package com.liskovsoft.smartyoutubetv2.common.iptv;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

/** Result of the last check of every channel (works / does not work), kept in a small file. */
public final class IptvHealth {
    public static final int UNKNOWN = 0;
    public static final int OK = 1;
    public static final int BAD = 2;

    /** An old answer is not trusted any more: servers come and go. */
    private static final long TTL_MS = 12L * 60 * 60 * 1000;
    private static final int MAX_ENTRIES = 60_000;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static IptvHealth sInstance;
    private final File mFile;
    private final Map<String, long[]> mMap = new HashMap<>(); // key -> {status, time}
    private boolean mLoaded;

    IptvHealth(File file) {
        mFile = file;
    }

    public static synchronized IptvHealth instance(Context context) {
        if (sInstance == null) {
            sInstance = new IptvHealth(new File(context.getApplicationContext().getFilesDir(), "iptv_health.txt"));
        }
        return sInstance;
    }

    public synchronized int status(String key) {
        load();
        long[] e = mMap.get(key);
        if (e == null || System.currentTimeMillis() - e[1] > TTL_MS) {
            return UNKNOWN;
        }
        return (int) e[0];
    }

    public synchronized void put(String key, boolean ok) {
        load();
        mMap.put(key, new long[]{ok ? OK : BAD, System.currentTimeMillis()});
    }

    public synchronized void clear() {
        mMap.clear();
        mLoaded = true;
        save();
    }

    public synchronized void save() {
        load();
        long now = System.currentTimeMillis();
        java.util.Iterator<Map.Entry<String, long[]>> it = mMap.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue()[1] > TTL_MS) {
                it.remove();
            }
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(mFile), UTF8)) {
            int n = 0;
            for (Map.Entry<String, long[]> e : mMap.entrySet()) {
                if (++n > MAX_ENTRIES) {
                    break;
                }
                w.write(e.getValue()[0] + "\t" + e.getValue()[1] + "\t" + e.getKey().replace('\t', ' ').replace('\n', ' ') + "\n");
            }
        } catch (IOException e) {
            // the dots are only a convenience: losing them is harmless
        }
    }

    private void load() {
        if (mLoaded) {
            return;
        }
        mLoaded = true;
        if (!mFile.exists()) {
            return;
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(mFile), UTF8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", 3);
                if (p.length == 3) {
                    try {
                        mMap.put(p[2], new long[]{Long.parseLong(p[0]), Long.parseLong(p[1])});
                    } catch (NumberFormatException ignored) {
                        // skip a damaged line
                    }
                }
            }
        } catch (IOException e) {
            // start empty
        }
    }
}
