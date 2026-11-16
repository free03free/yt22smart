package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import android.net.Uri;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.cache.Cache;
import com.google.android.exoplayer2.upstream.cache.CacheKeyFactory;
import com.google.android.exoplayer2.upstream.cache.LeastRecentlyUsedCacheEvictor;
import com.google.android.exoplayer2.upstream.cache.SimpleCache;

import java.io.File;

/**
 * Disk cache for already loaded video data. Lets the player rewind/replay without downloading the video again.
 * Old data is evicted automatically (LRU) when the size limit is reached.
 */
public final class VideoDiskCache {
    private static final String CACHE_DIR = "video_disk_cache";
    private static SimpleCache sCache;
    private static long sMaxBytes;
    private static String sLastVideoId;

    private VideoDiskCache() {
    }

    /** Same media should hit the cache even if googlevideo url params (expire, signature, ip...) are different. */
    public static final CacheKeyFactory KEY_FACTORY = new CacheKeyFactory() {
        @Override
        public String buildCacheKey(DataSpec dataSpec) {
            if (dataSpec.key != null) {
                return dataSpec.key;
            }

            Uri uri = dataSpec.uri;
            String id = uri.getQueryParameter("id");
            String itag = uri.getQueryParameter("itag");

            if (id == null || itag == null) {
                return uri.toString();
            }

            StringBuilder key = new StringBuilder("yt_").append(id).append('_').append(itag);
            for (String param : new String[] {"lmt", "clen", "xtags", "range"}) {
                String value = uri.getQueryParameter(param);
                if (value != null) {
                    key.append('_').append(param).append('=').append(value);
                }
            }

            return key.toString();
        }

        @Override
        public int maxDownloadParallelSegments() {
            return 1;
        }
    };

    public static synchronized Cache get(Context context, int maxMb) {
        long maxBytes = maxMb * 1024L * 1024L;

        if (sCache == null || sMaxBytes != maxBytes) {
            release();
            sMaxBytes = maxBytes;
            // Each cache session has its own dir: the old data can be deleted in background without a race
            File root = getRootDir(context);
            File[] leftovers = root.listFiles(); // data of the previous sessions/app runs
            File dir = new File(root, "s" + System.currentTimeMillis());
            sCache = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(maxBytes));
            deleteInBackground(leftovers);
        }

        return sCache;
    }

    /**
     * Call when a regular (not live) video is about to be played.
     * If {@code deleteOnSwitch} is set the cache is deleted as soon as the user switches to another video.
     * The same video (e.g. quality change or replay) keeps its cache.
     */
    public static synchronized void onVideoOpened(Context context, String videoId, boolean deleteOnSwitch) {
        if (videoId == null) {
            return;
        }

        if (deleteOnSwitch && sLastVideoId != null && !sLastVideoId.equals(videoId)) {
            clear(context);
        }

        sLastVideoId = videoId;
    }

    /** Removes all cached video data */
    public static synchronized void clear(Context context) {
        release();
        sLastVideoId = null;
        deleteInBackground(getRootDir(context).listFiles()); // snapshot: dirs created later are not touched
    }

    public static synchronized void release() {
        if (sCache != null) {
            try {
                sCache.release();
            } catch (Exception e) {
                // NOP
            }
            sCache = null;
        }
    }

    private static File getRootDir(Context context) {
        File root = new File(context.getApplicationContext().getCacheDir(), CACHE_DIR);
        root.mkdirs();
        return root;
    }

    private static void deleteInBackground(File[] files) {
        if (files == null || files.length == 0) {
            return;
        }

        new Thread(() -> {
            for (File file : files) {
                deleteRecursive(file);
            }
        }, "VideoDiskCacheCleaner").start();
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }

        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }

        file.delete();
    }
}
