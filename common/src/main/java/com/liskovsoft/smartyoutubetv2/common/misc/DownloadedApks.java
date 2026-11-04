package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Finds the APK files (updates, other apps, copies) the app has downloaded, so they can be installed, deleted or cleaned. */
public final class DownloadedApks {
    public static final class Item {
        public final File file;
        public final long size;
        public final long modified;
        /** "package version" read from the file itself, or null when it cannot be read */
        public final String info;
        public final String packageName;

        Item(File file, String info, String packageName) {
            this.file = file;
            this.size = file.length();
            this.modified = file.lastModified();
            this.info = info;
            this.packageName = packageName;
        }
    }

    private static final int MAX_DEPTH = 4;

    private DownloadedApks() {
    }

    /** The folders the app can write to; the public Download folder is not readable on new Android versions. */
    private static List<File> roots(Context context) {
        Set<File> roots = new LinkedHashSet<>();
        roots.add(context.getCacheDir());
        roots.add(context.getFilesDir());
        roots.add(context.getExternalCacheDir());
        roots.add(context.getExternalFilesDir(null));
        List<File> result = new ArrayList<>();
        for (File f : roots) {
            if (f != null && f.isDirectory()) {
                result.add(f);
            }
        }
        return result;
    }

    public static List<Item> list(Context context) {
        PackageManager pm = context.getPackageManager();
        List<Item> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (File root : roots(context)) {
            collect(root, 0, pm, items, seen);
        }
        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                return Long.compare(b.modified, a.modified); // newest first
            }
        });
        return items;
    }

    private static void collect(File dir, int depth, PackageManager pm, List<Item> out, Set<String> seen) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                if (depth < MAX_DEPTH) {
                    collect(f, depth + 1, pm, out, seen);
                }
            } else if (f.getName().toLowerCase().endsWith(".apk") && seen.add(f.getAbsolutePath())) {
                String info = null;
                String pkg = null;
                try {
                    PackageInfo p = pm.getPackageArchiveInfo(f.getPath(), 0);
                    if (p != null) {
                        pkg = p.packageName;
                        info = p.packageName + "  " + p.versionName;
                    }
                } catch (RuntimeException ignored) {
                    // a broken / partial file: listed without details
                }
                out.add(new Item(f, info, pkg));
            }
        }
    }

    public static long totalSize(List<Item> items) {
        long sum = 0;
        for (Item i : items) {
            sum += i.size;
        }
        return sum;
    }

    /** Deletes the files (and the partial downloads of the updates folder). @return how many files were deleted */
    public static int delete(Context context, List<Item> items) {
        int n = 0;
        for (Item i : items) {
            if (i.file.delete()) {
                n++;
            }
        }
        return n;
    }

    /** Everything that is left of downloaded updates: the listed APKs and the unfinished files of the updates folder. */
    public static int cleanAll(Context context) {
        int n = delete(context, list(context));
        File[] left = new File(context.getCacheDir(), "updates").listFiles();
        if (left != null) {
            for (File f : left) {
                if (f.isFile() && f.delete()) {
                    n++;
                }
            }
        }
        return n;
    }
}
