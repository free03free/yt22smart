package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Flattens archives (and archives nested inside archives) into the list of candidate playlist files.
 * ZIP, GZ and TAR are read natively; 7z, rar, bzip2, xz, zstd, cpio, ar, arj ... go through an
 * {@link ExternalArchiveHandler}. All limits come from {@link IptvOptions} (0 = unlimited).
 */
public final class IptvArchiveReader {
    private static final Set<String> SKIP_EXT = new HashSet<>(Arrays.asList(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "svg", "mp4", "mkv", "avi", "mov", "ts", "mp3", "aac", "flac",
            "apk", "exe", "dll", "so", "jar", "class", "pdf", "doc", "docx", "xls", "xlsx", "css", "js", "html", "htm",
            "ttf", "otf", "woff", "woff2", "db", "sqlite", "bin", "dat", "iso"));

    public static final class Entry {
        public final String name;
        public final byte[] data;

        public Entry(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    public static final class Result {
        public final List<Entry> entries;
        public final List<String> warnings;

        Result(List<Entry> entries, List<String> warnings) {
            this.entries = entries;
            this.warnings = warnings;
        }
    }

    private final IptvOptions mOptions;
    private final ExternalArchiveHandler mHandler;
    private final List<Entry> mEntries = new ArrayList<>();
    private final List<String> mWarnings = new ArrayList<>();
    private long mTotal;
    private int mSeen;
    private boolean mStopped;

    private IptvArchiveReader(IptvOptions options, ExternalArchiveHandler handler) {
        mOptions = options;
        mHandler = handler;
    }

    public static boolean isArchive(IptvFormat f) {
        switch (f) {
            case ZIP: case GZIP: case TAR: case RAR: case SEVENZ: case BZIP2: case XZ: case ZSTD:
            case UNIX_COMPRESS: case CPIO: case AR: case ARJ: case UNSUPPORTED_ARCHIVE:
                return true;
            default:
                return false;
        }
    }

    public static Result read(byte[] data, String name) throws IOException {
        return read(data, name, new IptvOptions(), null);
    }

    /** @param handler may be null: then only zip / gz / tar are readable */
    public static Result read(byte[] data, String name, IptvOptions options, ExternalArchiveHandler handler) throws IOException {
        IptvArchiveReader r = new IptvArchiveReader(options == null ? new IptvOptions() : options, handler);
        r.walk(data, name == null ? "" : name, 0);
        return new Result(r.mEntries, r.mWarnings);
    }

    private void walk(byte[] data, String name, int depth) throws IOException {
        if (mStopped) {
            return;
        }
        if (mOptions.maxArchiveDepth > 0 && depth > mOptions.maxArchiveDepth) {
            mWarnings.add("Nested archive too deep, skipped: " + name);
            return;
        }
        IptvFormat fmt = IptvFormat.detect(data, name);
        switch (fmt) {
            case ZIP:
                readZip(data, depth);
                break;
            case GZIP: {
                byte[] inner = readLimited(new GZIPInputStream(new ByteArrayInputStream(data)));
                walk(inner, stripExt(name, ".gz", ".tgz"), depth + 1);
                break;
            }
            case TAR:
                readTar(data, depth);
                break;
            case RAR: case SEVENZ: case BZIP2: case XZ: case ZSTD: case UNIX_COMPRESS: case CPIO: case AR: case ARJ:
                readExternal(data, name, fmt, depth);
                break;
            case UNSUPPORTED_ARCHIVE:
                mWarnings.add("Unsupported archive type (cab): " + name);
                break;
            default:
                offer(name, data);
        }
    }

    private void readExternal(byte[] data, String name, IptvFormat fmt, int depth) throws IOException {
        if (mHandler == null || !mHandler.supports(fmt)) {
            mWarnings.add(fmt + " archives are not available in this build: " + name);
            return;
        }
        List<Entry> entries;
        try {
            entries = mHandler.extract(data, name, fmt, mOptions);
        } catch (IOException e) {
            mWarnings.add("Cannot read " + fmt + " archive " + name + ": " + e.getMessage());
            return;
        } catch (RuntimeException | LinkageError e) { // e.g. library missing on this Android version
            mWarnings.add(fmt + " support failed on this device (" + e.getClass().getSimpleName() + "): " + name);
            return;
        }
        for (Entry e : entries) {
            if (!countEntry()) {
                return;
            }
            if (!shouldIgnore(e.name)) {
                walk(e.data, e.name, depth + 1);
            }
        }
    }

    private void readZip(byte[] data, int depth) throws IOException {
        int before = mEntries.size();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (!countEntry()) {
                    return;
                }
                if (e.isDirectory() || shouldIgnore(e.getName())) {
                    continue;
                }
                byte[] bytes;
                try {
                    bytes = readLimited(zis);
                } catch (IOException tooBig) {
                    mWarnings.add("Skipped " + e.getName() + ": " + tooBig.getMessage());
                    continue;
                }
                walk(bytes, e.getName(), depth + 1);
            }
        } catch (IOException e) {
            if (mEntries.size() == before && mSeen == 0) {
                throw e;
            }
            mWarnings.add("Archive is damaged or encrypted, partial result: " + e.getMessage());
        }
    }

    private void readTar(byte[] data, int depth) {
        int pos = 0;
        String longName = null;
        while (pos + 512 <= data.length) {
            boolean empty = true;
            for (int i = 0; i < 512; i++) {
                if (data[pos + i] != 0) {
                    empty = false;
                    break;
                }
            }
            if (empty) {
                break;
            }
            String name = cString(data, pos, 100);
            long size = parseOctal(data, pos + 124, 12);
            char type = (char) data[pos + 156];
            String prefix = cString(data, pos + 345, 155);
            if (!prefix.isEmpty() && new String(data, pos + 257, 5).equals("ustar")) {
                name = prefix + "/" + name;
            }
            pos += 512;
            if (size < 0 || pos + size > data.length || !IptvOptions.within(mOptions.maxEntryBytes, size)) {
                mWarnings.add("Tar entry skipped or invalid: " + name);
                if (size < 0 || pos + size > data.length) {
                    return;
                }
                pos += (int) ((size + 511) / 512) * 512;
                continue;
            }
            byte[] body = Arrays.copyOfRange(data, pos, pos + (int) size);
            pos += (int) ((size + 511) / 512) * 512;

            if (type == 'L') {
                longName = cString(body, 0, body.length);
                continue;
            }
            if (longName != null) {
                name = longName;
                longName = null;
            }
            if ((type == '0' || type == 0) && !name.endsWith("/") && !shouldIgnore(name)) {
                if (!countEntry()) {
                    return;
                }
                try {
                    walk(body, name, depth + 1);
                } catch (IOException e) {
                    mWarnings.add("Skipped " + name + ": " + e.getMessage());
                }
            }
        }
    }

    private boolean countEntry() {
        mSeen++;
        if (mOptions.maxArchiveEntries > 0 && mSeen > mOptions.maxArchiveEntries) {
            mWarnings.add("Entry limit (" + mOptions.maxArchiveEntries + ") reached, the rest was skipped");
            mStopped = true;
            return false;
        }
        return true;
    }

    private void offer(String name, byte[] data) {
        IptvFormat f = IptvFormat.detect(data, name);
        if (f == IptvFormat.BINARY || f == IptvFormat.XMLTV || f == IptvFormat.HLS_STREAM) {
            return;
        }
        mTotal += data.length;
        if (!IptvOptions.within(mOptions.maxArchiveTotalBytes, mTotal)) {
            mWarnings.add("Total size limit reached, the rest was skipped");
            mStopped = true;
            return;
        }
        mEntries.add(new Entry(name, data));
    }

    private static String stripExt(String name, String... exts) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String e : exts) {
            if (lower.endsWith(e)) {
                return name.substring(0, name.length() - e.length()) + (e.equals(".tgz") ? ".tar" : "");
            }
        }
        return name;
    }

    private static boolean shouldIgnore(String entryName) {
        String n = entryName.replace('\\', '/');
        String lower = n.toLowerCase(Locale.ROOT);
        if (lower.startsWith("__macosx/") || lower.contains("/__macosx/") || lower.contains("/.git/")) {
            return true;
        }
        String base = lower.substring(lower.lastIndexOf('/') + 1);
        if (base.startsWith("._") || base.equals(".ds_store") || base.equals("thumbs.db")) {
            return true;
        }
        if (base.startsWith("readme") || base.startsWith("license") || base.startsWith("changelog")) {
            return true;
        }
        return SKIP_EXT.contains(IptvUrls.extension(base));
    }

    private byte[] readLimited(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (!IptvOptions.within(mOptions.maxEntryBytes, total)) {
                    throw new IOException("file is larger than " + (mOptions.maxEntryBytes / 1024 / 1024) + " MB");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (OutOfMemoryError e) {
            throw new IOException("not enough memory for this file");
        }
    }

    private static String cString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && end < b.length && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, java.nio.charset.Charset.forName("UTF-8"));
    }

    private static long parseOctal(byte[] b, int off, int len) {
        long v = 0;
        boolean any = false;
        for (int i = off; i < off + len; i++) {
            int c = b[i];
            if (c >= '0' && c <= '7') {
                v = v * 8 + (c - '0');
                any = true;
            } else if (any) {
                break;
            }
        }
        return any ? v : 0;
    }
}
