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
        /** List name -> the link it was downloaded from (lets "update this list" fetch it again). */
        public final java.util.Map<String, String> listSources = new java.util.LinkedHashMap<>();
    }

    /** Live progress hook. May throw a RuntimeException (e.g. CancellationException) to abort the import. */
    public interface Progress {
        /** @param status what is being done now (may be null) @param channels channels collected so far */
        void onStep(String status, int channels);
    }

    private Progress mProgress;
    private IptvFetcher mPortalFetcher;
    private IptvFetcher mPortalBulkFetcher;
    private String mDefaultList;
    private String mCurrentList;

    /** Fetcher with shorter timeouts used for Stalker portals (null = the normal one). */
    public void setPortalFetcher(IptvFetcher fetcher) {
        mPortalFetcher = fetcher;
    }

    /** Fetcher with long timeouts for the big portal channel list (null = same as the portal fetcher). */
    public void setPortalBulkFetcher(IptvFetcher fetcher) {
        mPortalBulkFetcher = fetcher;
    }

    /** Name given to channels that do not belong to a more specific list (portal / server). */
    public void setDefaultList(String name) {
        mDefaultList = name;
    }

    public void setProgress(Progress progress) {
        mProgress = progress;
    }

    private void report(String status) {
        if (mProgress != null) {
            mProgress.onStep(status, mResult == null ? 0 : mResult.playlist.size());
        }
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

    // ---------------------------------------------------------------- discovery ("look first, download later")

    private List<IptvSourceItem> mDiscover;

    /** What a file holds, found without any download: portals, accounts, links, archive entries / plain lists. */
    public synchronized List<IptvSourceItem> discoverFile(File file) throws IOException {
        return discover(readFile(file), file.getName(), null);
    }

    /** Same for pasted text; {@code label} is the name the import job gives the source. */
    public synchronized List<IptvSourceItem> discoverText(String label, String text) {
        return discover(null, label, text);
    }

    private List<IptvSourceItem> discover(byte[] data, String name, String text) {
        begin();
        mDiscover = new ArrayList<>();
        try {
            if (text != null) {
                processText(text, 0, null);
            } else {
                process(data, name, 0, null);
            }
            if (mDiscover.isEmpty() && mResult.playlist.size() > 0) {
                unit(IptvSourceItem.Kind.FILE, "src:" + name, name, mResult.playlist.size(), "");
            }
            return mDiscover;
        } finally {
            mDiscover = null;
        }
    }

    /**
     * One thing the user can tick / un-tick. Discovery: remembers it and returns false (nothing is downloaded).
     * Normal import: returns false when the user un-ticked it.
     */
    private boolean unit(IptvSourceItem.Kind kind, String key, String title, int count, String detail) {
        return unit(kind, key, title, count, detail, null);
    }

    private boolean unit(IptvSourceItem.Kind kind, String key, String title, int count, String detail, List<String> macs) {
        if (mDiscover != null) {
            for (IptvSourceItem i : mDiscover) {
                if (i.key.equals(key)) {
                    return false;
                }
            }
            mDiscover.add(new IptvSourceItem(kind, key, title, count, detail, macs));
            return false;
        }
        return mOptions.skipKeys == null || !mOptions.skipKeys.contains(key);
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
            case ZIP: {
                String office = IptvDocumentText.officeText(data); // docx / xlsx / pptx / odt ...: read it as a document
                if (office != null) {
                    mResult.filesParsed++;
                    processText(office, depth, groupHint);
                    return;
                }
                processArchive(data, name, depth);
                return;
            }
            case GZIP: case TAR: case RAR: case SEVENZ: case BZIP2: case XZ: case ZSTD:
            case UNIX_COMPRESS: case CPIO: case AR: case ARJ: case UNSUPPORTED_ARCHIVE:
                processArchive(data, name, depth);
                return;
            case PDF:
                mResult.filesParsed++;
                processText(IptvDocumentText.pdfText(data), depth, groupHint);
                return;
            case RTF:
                mResult.filesParsed++;
                processText(IptvDocumentText.rtfToText(TextDecoderUtil.decode(data)), depth, groupHint);
                return;
            case XMLTV:
                mResult.messages.add("EPG guide ignored: " + name);
                return;
            case BINARY: {
                // any other file: look through its readable strings, keep only what is IPTV, ignore the rest
                int before = mResult.playlist.size();
                String strings = IptvDocumentText.strings(data);
                if (IptvUrls.STREAM_URL.matcher(strings).find() || strings.contains("#EXTINF")) {
                    mResult.filesParsed++;
                    processText(strings, depth, groupHint);
                }
                if (mResult.playlist.size() == before) {
                    mResult.messages.add("No IPTV data found in: " + name);
                }
                return;
            }
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
            default: {
                String t = text.trim();
                if (t.startsWith("<")) {
                    text = IptvDocumentText.markupToText(text); // a web page / xml: its text and its links
                }
                processText(text, depth, groupHint);
            }
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
            String key = "file:" + name + "!" + e.name;
            if (mDiscover == null) {
                if (mOptions.skipKeys != null && mOptions.skipKeys.contains(key)) {
                    continue; // un-ticked by the user
                }
                process(e.data, e.name, depth, hint);
            } else {
                // discovery: an entry that holds portals / links / accounts is represented by those; a plain list by itself
                int items = mDiscover.size();
                int channels = mResult.playlist.size();
                process(e.data, e.name, depth, hint);
                if (mDiscover.size() == items && mResult.playlist.size() > channels) {
                    unit(IptvSourceItem.Kind.FILE, key, e.name, mResult.playlist.size() - channels, name);
                }
            }
        }
    }

    private void processText(String text, int depth, String groupHint) {
        String cleaned = IptvTextCleaner.clean(text); // unreadable web-page glyphs out, wrapped urls joined
        processText(TextListParser.parse(cleaned), cleaned, depth, groupHint);
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
            // the user may have un-ticked some portals before the download
            List<StalkerAccount> wanted = new ArrayList<>();
            for (StalkerAccount a : portals) {
                if (unit(IptvSourceItem.Kind.PORTAL, "portal:" + a.key(), a.portal, a.macs.size(), "", a.macs)) {
                    StalkerAccount use = withoutSkippedMacs(a);
                    if (use.macs.isEmpty()) {
                        mResult.messages.add("Portal " + a.host() + ": every MAC address was un-ticked");
                        continue;
                    }
                    wanted.add(use);
                }
            }
            if (!wanted.isEmpty()) {
                fetchStalkerAll(wanted, groupHint);
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
        // a long text (web page, document) may hold several accounts: api urls and Host / Username / Password blocks
        for (XtreamAccount a : XtreamAccount.parseAll(rawText)) {
            boolean known = false;
            for (XtreamAccount k : found) {
                if (k.server.equalsIgnoreCase(a.server) && k.username.equals(a.username) && k.password.equals(a.password)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                found.add(a);
            }
        }
        List<IptvChannel> channels = parsed;
        if (!found.isEmpty()) {
            channels = new ArrayList<>();
            for (IptvChannel c : parsed) {
                if (!pointsAtServerRoot(c.url, found) && !isAccountApiUrl(c.url, found)) {
                    channels.add(c);
                }
            }
            for (XtreamAccount a : found) {
                fetchXtream(a, null, depth);
            }
        }
        addWithLinks(channels, depth, groupHint, true);
    }

    /** The MAC addresses the user un-ticked in the picker are left out. */
    private StalkerAccount withoutSkippedMacs(StalkerAccount a) {
        if (mOptions.skipKeys == null || mOptions.skipKeys.isEmpty()) {
            return a;
        }
        StalkerAccount use = new StalkerAccount(a.portal);
        for (String mac : a.macs) {
            if (!mOptions.skipKeys.contains(IptvSourceItem.macKey(a.key(), mac))) {
                use.macs.add(mac);
            }
        }
        return use;
    }

    /** General Stalker settings, overridden by what the user chose for this one portal. */
    private StalkerOptions stalkerOptionsFor(StalkerAccount acc) {
        StalkerOptions so = new StalkerOptions();
        so.live = mOptions.stalkerLive;
        so.movies = mOptions.stalkerMovies;
        so.series = mOptions.stalkerSeries;
        so.perMac = mOptions.stalkerPerMac;
        so.maxItems = mOptions.stalkerMaxItems;
        Integer bits = mOptions.portalTypes.get(acc.key());
        if (bits != null && bits != 0) {
            so.live = (bits & 1) != 0;
            so.movies = (bits & 2) != 0;
            so.series = (bits & 4) != 0;
        }
        return so;
    }

    private static final class PortalOutcome {
        Throwable error;
        List<IptvChannel> channels;
        final List<String> notes = new ArrayList<>();
        boolean failed;
        java.util.concurrent.CancellationException cancelled;
    }

    /** Portals are tried several at a time (a dead portal no longer blocks the rest); results are added in file order. */
    private void fetchStalkerAll(List<StalkerAccount> portals, String groupHint) {
        if (mFetcher == null) {
            for (StalkerAccount a : portals) {
                mResult.messages.add("Stalker portal found but downloading is disabled: " + a.host());
            }
            return;
        }
        final IptvFetcher fetcher = mPortalFetcher != null ? mPortalFetcher : mFetcher;
        final IptvFetcher bulk = mPortalBulkFetcher != null ? mPortalBulkFetcher : fetcher;
        final int total = portals.size();
        final java.util.concurrent.atomic.AtomicInteger started = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger();
        int threads = Math.max(1, Math.min(mOptions.portalParallel <= 0 ? 1 : mOptions.portalParallel, total));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        List<java.util.concurrent.Future<PortalOutcome>> futures = new ArrayList<>();
        try {
            for (final StalkerAccount acc : portals) {
                futures.add(pool.submit(() -> {
                    PortalOutcome out = new PortalOutcome();
                    final int index = started.incrementAndGet();
                    final String host = acc.host();
                    final StalkerOptions so = stalkerOptionsFor(acc);
                    // movies / series are far bigger than the live list: they get a much longer time
                    final long deadline = mOptions.portalDeadlineMs <= 0 ? 0 : mOptions.portalDeadlineMs * ((so.movies || so.series) ? 6 : 1);
                    try {
                        StalkerClient.Step step = new StalkerClient.Step() {
                            @Override
                            public void onMac(int macIndex, int macs) {
                                report("portal " + index + "/" + total + " " + host + " (" + macIndex + "/" + macs + ")");
                            }

                            @Override
                            public void onStage(String text) {
                                report("portal " + index + "/" + total + " " + host + ": " + text);
                            }
                        };
                        out.channels = StalkerClient.fetchChannels(fetcher, bulk, acc, out.notes, step,
                                mOptions.portalParallelMacs, deadline, so);
                    } catch (java.util.concurrent.CancellationException e) {
                        out.cancelled = e;
                    } catch (IOException | RuntimeException e) {
                        out.failed = true;
                        out.error = e;
                    }
                    return out;
                }));
            }
            for (int i = 0; i < total; i++) {
                PortalOutcome out;
                try {
                    out = futures.get(i).get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException();
                } catch (java.util.concurrent.ExecutionException e) {
                    out = new PortalOutcome();
                    out.failed = true;
                }
                if (out.cancelled != null) {
                    throw out.cancelled;
                }
                StalkerAccount acc = portals.get(i);
                if (!out.failed && out.channels != null) {
                    mResult.stalkerPortals++;
                    mResult.listsDownloaded++;
                    for (IptvChannel c : out.channels) {
                        if (c.list == null || c.list.isEmpty()) {
                            c.list = acc.host();
                        }
                    }
                    addAll(out.channels, groupHint);
                    report("portal " + finished.incrementAndGet() + "/" + total + " \u2713");
                    if (mOptions.portalListener != null) {
                        mOptions.portalListener.onPortal(acc.key(), true, "");
                    }
                } else {
                    StalkerFailure why = StalkerFailure.classify(out.error);
                    mResult.failures++;
                    mResult.messages.add("Portal " + acc.host() + " did not return channels: " + why.label);
                    finished.incrementAndGet();
                    if (mOptions.portalListener != null) {
                        mOptions.portalListener.onPortal(acc.key(), false, why.name());
                    }
                }
                mResult.messages.addAll(out.notes);
            }
        } finally {
            pool.shutdownNow();
        }
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

    /** "http://host/get.php?username=U&password=P..." of an account that is fetched anyway (not a channel). */
    private static boolean isAccountApiUrl(String url, List<XtreamAccount> accounts) {
        if (url == null || !url.toLowerCase().contains(".php?")) {
            return false;
        }
        XtreamAccount a = XtreamAccount.parse(url);
        if (a == null) {
            return false;
        }
        for (XtreamAccount k : accounts) {
            if (k.server.equalsIgnoreCase(a.server) && k.username.equals(a.username)) {
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
        if ((mFetcher == null && mDiscover == null) || !mOptions.followRemoteLinks
                || (mOptions.maxLinkDepth > 0 && depth >= mOptions.maxLinkDepth)) {
            return false;
        }
        if (mDiscover != null && kind == Link.AMBIGUOUS) {
            return false; // cannot tell a stream from a list without downloading it
        }
        String url = normalizeSource(rawUrl);
        if (kind == Link.XTREAM_LIST) {
            XtreamAccount acc = XtreamAccount.parse(url);
            if (acc != null) {
                fetchXtream(acc, url, depth);
                return true;
            }
        }
        if (!unit(IptvSourceItem.Kind.LINK, "link:" + url, url, 0, IptvUrls.hostOf(url))) {
            return true; // listed (discovery) or un-ticked by the user
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
        String outerList = mCurrentList;
        if (outerList == null) {
            mCurrentList = IptvUrls.hostOf(url);
            mResult.listSources.put(mCurrentList, url);
        }
        try {
            process(data, url, depth + 1, groupHint);
        } finally {
            mCurrentList = outerList;
        }
        return true;
    }

    private void fetchXtream(XtreamAccount acc, String verbatimUrl, int depth) {
        if (!unit(IptvSourceItem.Kind.XTREAM, "xtream:" + acc.server.toLowerCase(Locale.ROOT) + "|" + acc.username,
                IptvUrls.hostOf(acc.server), 0, acc.username)) {
            return;
        }
        if (mFetcher == null) {
            mResult.messages.add("Xtream account found but downloading is disabled: " + acc);
            return;
        }
        addAccount(acc);
        String outerList = mCurrentList;
        if (outerList == null) {
            mCurrentList = IptvUrls.hostOf(acc.server);
        }
        try {
            fetchXtreamLists(acc, verbatimUrl, depth);
        } finally {
            mCurrentList = outerList;
        }
    }

    private void fetchXtreamLists(XtreamAccount acc, String verbatimUrl, int depth) {
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
        report("\u2193 " + IptvUrls.hostOf(url));
        try {
            return mFetcher.fetch(url);
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            mResult.failures++;
            mResult.messages.add("Failed: " + IptvUrls.hostOf(url) + " (" + e.getMessage() + ")");
            return null;
        }
    }

    private void addAll(List<IptvChannel> channels, String groupHint) {
        String list = mCurrentList != null ? mCurrentList : mDefaultList;
        for (IptvChannel c : channels) {
            if ((c.list == null || c.list.isEmpty()) && list != null) {
                c.list = list;
            }
            if ((c.group == null || c.group.isEmpty()) && groupHint != null) {
                c.group = groupHint;
            }
            mResult.playlist.add(c);
        }
        report(null);
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
