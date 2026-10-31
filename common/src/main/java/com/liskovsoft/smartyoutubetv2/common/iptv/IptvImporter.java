package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entry point of the IPTV engine.
 * Accepts bytes (file / zip / gz / tar), pasted text or links and produces one merged playlist.
 * Remote lists found inside text are downloaded and parsed automatically (Xtream, m3u, txt, zip ...).
 */
public final class IptvImporter {
    private static final Set<String> LIST_EXT = new HashSet<>(Arrays.asList(
            "m3u", "txt", "zip", "gz", "tgz", "tar", "iptv", "lst", "list", "pls", "xspf", "json", "csv"));
    private static final Set<String> LIST_HOSTS = new HashSet<>(Arrays.asList(
            "pastebin.com", "raw.githubusercontent.com", "gist.githubusercontent.com", "bit.ly", "tinyurl.com", "cutt.ly", "is.gd"));
    private static final Pattern GITHUB_BLOB = Pattern.compile("(?i)^https?://github\\.com/([^/]+)/([^/]+)/blob/(.+)$");
    private static final Pattern PASTEBIN = Pattern.compile("(?i)^https?://pastebin\\.com/(?!raw/)([A-Za-z0-9]+)$");

    private enum Link { XTREAM_LIST, LIST, AMBIGUOUS, EPG, STREAM }

    public static final class Result {
        public final IptvPlaylist playlist = new IptvPlaylist();
        public final List<String> messages = new ArrayList<>();
        public final List<XtreamAccount> accounts = new ArrayList<>();
        public int filesParsed;
        public int listsDownloaded;
        /** Stalker / Ministra portals whose channel list was downloaded. */
        public int stalkerPortals;
        public int failures;
    }

    private final IptvFetcher mFetcher;
    private final IptvOptions mOptions;
    private final ExternalArchiveHandler mArchiveHandler;
    private Result mResult;
    private Set<String> mVisited;
    private int mFetchCount;

    /** @param fetcher null disables downloading of remote lists */
    public IptvImporter(IptvFetcher fetcher) {
        this(fetcher, new IptvOptions(), null);
    }

    /**
     * @param fetcher        null disables downloading of remote lists
     * @param options        limits and behaviour (null = defaults: no limits)
     * @param archiveHandler optional plug-in for 7z / rar / bzip2 / xz ... (null = zip, gz, tar only)
     */
    public IptvImporter(IptvFetcher fetcher, IptvOptions options, ExternalArchiveHandler archiveHandler) {
        mFetcher = fetcher;
        mOptions = options == null ? new IptvOptions() : options;
        mArchiveHandler = archiveHandler;
    }

    public Result importFile(File file) throws IOException {
        byte[] data = readFile(file);
        return importBytes(data, file.getName());
    }

    public synchronized Result importBytes(byte[] data, String name) {
        begin();
        process(data, name, 0, null);
        return mResult;
    }

    /** Pasted text, a links file, a reseller message ... anything. */
    public synchronized Result importText(String text) {
        begin();
        processText(text, 0, null);
        return mResult;
    }

    private void begin() {
        mResult = new Result();
        mVisited = new HashSet<>();
        mFetchCount = 0;
    }

    // ---------------------------------------------------------------- core

    private void process(byte[] data, String name, int depth, String groupHint) {
        IptvFormat fmt = IptvFormat.detect(data, name);
        switch (fmt) {
            case ZIP: case GZIP: case TAR: case RAR: case SEVENZ: case BZIP2: case XZ: case ZSTD:
            case UNIX_COMPRESS: case CPIO: case AR: case ARJ: case UNSUPPORTED_ARCHIVE:
                processArchive(data, name, depth);
                return;
            case XMLTV:
                mResult.messages.add("EPG guide ignored: " + name);
                return;
            case BINARY:
                mResult.messages.add("Not a playlist: " + name);
                mResult.failures++;
                return;
            case HLS_STREAM:
                if (IptvUrls.isStreamUrl(name)) {
                    addAll(listOf(new IptvChannel(IptvUrls.titleFromUrl(name), name)), groupHint);
                }
                return;
            default:
                break;
        }

        String text = TextDecoderUtil.decode(data);
        mResult.filesParsed++;
        switch (fmt) {
            case M3U:
                addWithLinks(M3uParser.parse(text), depth, groupHint, false);
                break;
            case PLS:
                addWithLinks(OtherFormatsParser.parsePls(text), depth, groupHint, false);
                break;
            case XSPF:
                addWithLinks(OtherFormatsParser.parseXspf(text), depth, groupHint, false);
                break;
            case JSON:
                processText(OtherFormatsParser.parseJson(text), text, depth, groupHint);
                break;
            default:
                processText(text, depth, groupHint);
        }
    }

    private void processArchive(byte[] data, String name, int depth) {
        IptvArchiveReader.Result ar;
        try {
            ar = IptvArchiveReader.read(data, name, mOptions, mArchiveHandler);
        } catch (IOException e) {
            mResult.messages.add("Cannot read archive " + name + ": " + e.getMessage());
            mResult.failures++;
            return;
        }
        mResult.messages.addAll(ar.warnings);
        if (ar.entries.isEmpty() && !ar.warnings.isEmpty()) {
            mResult.failures++;
        }
        boolean multi = ar.entries.size() > 1;
        for (IptvArchiveReader.Entry e : ar.entries) {
            String hint = multi ? baseName(e.name) : null;
            process(e.data, e.name, depth, hint);
        }
    }

    private void processText(String text, int depth, String groupHint) {
        processText(TextListParser.parse(text), text, depth, groupHint);
    }

    private void processText(List<IptvChannel> parsed, String rawText, int depth, String groupHint) {
        // Stalker / Ministra portals: "http://host/c/" followed by MAC addresses (not a file, needs an API session)
        List<StalkerAccount> portals = StalkerAccount.parseAll(rawText);
        if (!portals.isEmpty()) {
            List<IptvChannel> rest = new ArrayList<>();
            for (IptvChannel c : parsed) {
                if (!isPortalUrl(c.url, portals)) {
                    rest.add(c);
                }
            }
            parsed = rest;
            for (StalkerAccount a : portals) {
                fetchStalker(a, groupHint);
            }
        }

        // Xtream accounts described by a labelled message ("Server/Username/Password") or "host user pass" lines
        List<XtreamAccount> found = new ArrayList<>();
        XtreamAccount whole = IptvUrls.isStreamUrl(rawText.trim()) ? null : XtreamAccount.parse(rawText);
        if (whole != null) {
            found.add(whole);
        } else {
            for (String line : rawText.split("\r\n|\r|\n")) {
                String t = line.trim();
                if (t.isEmpty()) {
                    continue;
                }
                boolean bareUrl = IptvUrls.isStreamUrl(t) && !(t.matches(".*[\\s|,;].*") && !t.contains("="));
                XtreamAccount a = bareUrl ? null : XtreamAccount.parse(t);
                if (a != null) {
                    found.add(a);
                }
            }
        }
        List<IptvChannel> channels = parsed;
        if (!found.isEmpty()) {
            channels = new ArrayList<>();
            for (IptvChannel c : parsed) {
                if (!pointsAtServerRoot(c.url, found)) {
                    channels.add(c);
                }
            }
            for (XtreamAccount a : found) {
                fetchXtream(a, null, depth);
            }
        }
        addWithLinks(channels, depth, groupHint, true);
    }

    private void fetchStalker(StalkerAccount acc, String groupHint) {
        if (mFetcher == null) {
            mResult.messages.add("Stalker portal found but downloading is disabled: " + acc.host());
            return;
        }
        List<String> notes = new ArrayList<>();
        try {
            List<IptvChannel> channels = StalkerClient.fetchChannels(mFetcher, acc, notes);
            mResult.stalkerPortals++;
            mResult.listsDownloaded++;
            addAll(channels, groupHint);
        } catch (IOException | RuntimeException e) {
            mResult.failures++;
            mResult.messages.add("Portal " + acc.host() + " did not return channels (expired MAC or blocked?)");
        }
        mResult.messages.addAll(notes);
    }

    private static boolean isPortalUrl(String url, List<StalkerAccount> portals) {
        if (url == null) {
            return false;
        }
        String u = url.trim();
        int cut = u.length();
        int q = u.indexOf('?');
        if (q >= 0) cut = Math.min(cut, q);
        int h = u.indexOf('#');
        if (h >= 0) cut = Math.min(cut, h);
        u = u.substring(0, cut).toLowerCase(Locale.ROOT);
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        for (StalkerAccount a : portals) {
            if (u.equals(a.key())) {
                return true;
            }
        }
        return false;
    }

    private static boolean pointsAtServerRoot(String url, List<XtreamAccount> accounts) {
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        for (XtreamAccount a : accounts) {
            if (u.equalsIgnoreCase(a.server)) {
                return true;
            }
        }
        return false;
    }

    /** Adds streams, downloads nested lists. */
    private void addWithLinks(List<IptvChannel> channels, int depth, String groupHint, boolean fromText) {
        boolean few = mOptions.probeLimit == 0 || (mOptions.probeLimit > 0 && channels.size() <= mOptions.probeLimit);
        boolean probe = fromText && few;
        List<IptvChannel> streams = new ArrayList<>();
        for (IptvChannel c : channels) {
            if (!fromText) {
                // a playlist entry that points at another playlist
                Link l = classify(c.url);
                if (l == Link.XTREAM_LIST || (l == Link.LIST && few)) {
                    followLink(c.url, depth, l, groupHint);
                    continue;
                }
                streams.add(c);
                continue;
            }
            Link l = classify(c.url);
            switch (l) {
                case XTREAM_LIST:
                case LIST:
                    followLink(c.url, depth, l, groupHint);
                    break;
                case AMBIGUOUS:
                    if (probe && followLink(c.url, depth, l, groupHint)) {
                        break;
                    }
                    streams.add(c);
                    break;
                case EPG:
                    break;
                default:
                    streams.add(c);
            }
        }
        addAll(streams, groupHint);
    }

    /** @return true when the url was a list and was consumed (false: treat as a plain stream) */
    private boolean followLink(String rawUrl, int depth, Link kind, String groupHint) {
        if (mFetcher == null || !mOptions.followRemoteLinks || (mOptions.maxLinkDepth > 0 && depth >= mOptions.maxLinkDepth)) {
            return false;
        }
        String url = normalizeSource(rawUrl);
        if (kind == Link.XTREAM_LIST) {
            XtreamAccount acc = XtreamAccount.parse(url);
            if (acc != null) {
                fetchXtream(acc, url, depth);
                return true;
            }
        }
        byte[] data = download(url);
        if (data == null) {
            return kind != Link.AMBIGUOUS;
        }
        IptvFormat f = IptvFormat.detect(data, url);
        if (f == IptvFormat.HLS_STREAM || (f == IptvFormat.TEXT && kind == Link.AMBIGUOUS)) {
            return false; // it is a stream, not a list
        }
        mResult.listsDownloaded++;
        process(data, url, depth + 1, groupHint);
        return true;
    }

    private void fetchXtream(XtreamAccount acc, String verbatimUrl, int depth) {
        if (mFetcher == null) {
            mResult.messages.add("Xtream account found but downloading is disabled: " + acc);
            return;
        }
        addAccount(acc);
        List<String> urls = new ArrayList<>();
        if (verbatimUrl != null && verbatimUrl.toLowerCase(Locale.ROOT).contains("get.php")) {
            urls.add(verbatimUrl);
        }
        urls.add(acc.m3uUrl("m3u_plus", "ts"));
        if (mOptions.xtreamFallbacks) {
            urls.add(acc.m3uUrl("m3u", "ts"));
            urls.add(acc.m3uUrl("m3u_plus", "m3u8"));
        }
        for (String u : urls) {
            if (mVisited.contains(u)) {
                continue;
            }
            byte[] data = download(u);
            if (data == null) {
                continue;
            }
            IptvFormat f = IptvFormat.detect(data, u);
            if (f == IptvFormat.M3U) {
                mResult.listsDownloaded++;
                process(data, u, depth + 1, null);
                return;
            }
        }
        mResult.messages.add("Xtream account did not return a channel list (expired or blocked?): " + acc.server);
    }

    private void addAccount(XtreamAccount acc) {
        for (XtreamAccount a : mResult.accounts) {
            if (a.server.equals(acc.server) && a.username.equals(acc.username)) {
                return;
            }
        }
        mResult.accounts.add(acc);
    }

    private byte[] download(String url) {
        if (!mVisited.add(url)) {
            return null;
        }
        if (mOptions.maxFetches > 0 && ++mFetchCount > mOptions.maxFetches) {
            mResult.messages.add("Download limit (" + mOptions.maxFetches + ") reached, remaining links were skipped");
            return null;
        }
        try {
            return mFetcher.fetch(url);
        } catch (IOException | RuntimeException e) {
            mResult.failures++;
            mResult.messages.add("Failed: " + IptvUrls.hostOf(url) + " (" + e.getMessage() + ")");
            return null;
        }
    }

    private void addAll(List<IptvChannel> channels, String groupHint) {
        for (IptvChannel c : channels) {
            if ((c.group == null || c.group.isEmpty()) && groupHint != null) {
                c.group = groupHint;
            }
            mResult.playlist.add(c);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Link classify(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains("xmltv.php")) {
            return Link.EPG;
        }
        if (lower.contains("username=") && lower.contains("password=")
                && (lower.contains("get.php") || lower.contains("player_api.php") || lower.contains("panel_api.php") || lower.contains("playlist.php"))) {
            return Link.XTREAM_LIST;
        }
        String ext = IptvUrls.extension(url);
        if (LIST_EXT.contains(ext)) {
            return Link.LIST;
        }
        if (ext.equals("m3u8")) {
            return Link.AMBIGUOUS;
        }
        if (lower.contains("type=m3u")) {
            return Link.LIST;
        }
        if (ext.isEmpty() && LIST_HOSTS.contains(IptvUrls.hostOf(url).toLowerCase(Locale.ROOT))) {
            return Link.LIST;
        }
        if (GITHUB_BLOB.matcher(url).matches() && LIST_EXT.contains(ext)) {
            return Link.LIST;
        }
        return Link.STREAM;
    }

    static String normalizeSource(String url) {
        Matcher gh = GITHUB_BLOB.matcher(url);
        if (gh.matches()) {
            return "https://raw.githubusercontent.com/" + gh.group(1) + "/" + gh.group(2) + "/" + gh.group(3);
        }
        Matcher pb = PASTEBIN.matcher(url);
        if (pb.matches()) {
            return "https://pastebin.com/raw/" + pb.group(1);
        }
        return url;
    }

    private static String baseName(String entryName) {
        String n = entryName.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static List<IptvChannel> listOf(IptvChannel c) {
        List<IptvChannel> l = new ArrayList<>();
        l.add(c);
        return l;
    }

    private byte[] readFile(File f) throws IOException {
        if (f.length() > Integer.MAX_VALUE - 16 || !IptvOptions.within(mOptions.maxDownloadBytes, f.length())) {
            throw new IOException("File is too large");
        }
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(f.length(), 32));
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
