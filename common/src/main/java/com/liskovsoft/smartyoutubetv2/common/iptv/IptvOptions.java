package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.ArrayList;
import java.util.List;

/**
 * Every tunable of the IPTV engine. For all numeric limits the value 0 means "unlimited" (the default).
 * Pure Java, no Android dependencies.
 */
public class IptvOptions {
    /** Maximum number of remote lists downloaded during one import. 0 = unlimited. */
    public int maxFetches = 0;
    /** How deep a list may point to another list (list -> zip -> txt -> m3u ...). 0 = unlimited. */
    public int maxLinkDepth = 0;
    /** Nesting of archives inside archives. 0 = unlimited. */
    public int maxArchiveDepth = 0;
    /** Files processed per import (all archives together). 0 = unlimited. */
    public int maxArchiveEntries = 0;
    /** Size of a single extracted file in bytes. 0 = unlimited. */
    public long maxEntryBytes = 0;
    /** Total extracted size in bytes. 0 = unlimited. */
    public long maxArchiveTotalBytes = 0;
    /** Size of one download in bytes. 0 = unlimited. */
    public long maxDownloadBytes = 0;

    /**
     * When a pasted text/list contains .m3u8 links, their content is inspected to tell channel lists from single streams.
     * 0 = always inspect, negative = never inspect, N = inspect only if the list has at most N entries (default 20, avoids
     * hundreds of requests for a normal channel list).
     */
    public int probeLimit = 20;
    public boolean followRemoteLinks = true;
    /** If the Xtream get.php url fails, retry with type=m3u / output=m3u8 variants. */
    public boolean xtreamFallbacks = true;

    public int connectTimeoutMs = 15_000;
    public int readTimeoutMs = 40_000;
    public String userAgent = HttpIptvFetcher.DEFAULT_USER_AGENT;

    /** Words (comma separated) that keep a channel / group out of the library. */
    public String blacklist = "";
    /** Stalker portals are tried several at a time with shorter timeouts (dead portals no longer block the others). */
    public int portalParallel = 4;
    public int portalConnectTimeoutMs = 6_000;
    /** Handshake / small requests: a silent portal fails after this long. */
    public int portalReadTimeoutMs = 12_000;
    /** The big channel list may take long on a slow portal. */
    public int portalBulkReadTimeoutMs = 60_000;
    /** MAC addresses tried at the same time on one portal (the first that returns channels wins). */
    public int portalParallelMacs = 3;
    /** Stalker portals: what to fetch. Movies and series are off by default (they can be very large). */
    public boolean stalkerLive = true;
    public boolean stalkerMovies = false;
    public boolean stalkerSeries = false;
    /** Every MAC that works is kept as its own list "host [MAC]". */
    public boolean stalkerPerMac = true;
    /** Largest number of movies / series per portal (and MAC). 0 = no limit. */
    public int stalkerMaxItems = 5000;

    /** Give up on one portal after this long. */
    public long portalDeadlineMs = 120_000;
    /** Things the user un-ticked before the download (see {@link IptvSourceItem#key}). null / empty = nothing is skipped. */
    public java.util.Set<String> skipKeys;
    /** Per portal content choice (key = {@link StalkerAccount#key()}), bits: 1 live, 2 movies, 4 series. A portal that is absent uses the general settings. */
    public final java.util.Map<String, Integer> portalTypes = new java.util.HashMap<>();

    /** Told about every portal when its download ends, so the app can remember which ones fail. */
    public interface PortalListener {
        void onPortal(String portalKey, boolean ok, String reason);
    }

    public PortalListener portalListener;

    /** Same options with other timeouts (used for the portal fetcher). */
    public IptvOptions withTimeouts(int connectMs, int readMs) {
        IptvOptions o = new IptvOptions();
        o.maxFetches = maxFetches;
        o.maxLinkDepth = maxLinkDepth;
        o.maxArchiveDepth = maxArchiveDepth;
        o.maxArchiveEntries = maxArchiveEntries;
        o.maxEntryBytes = maxEntryBytes;
        o.maxArchiveTotalBytes = maxArchiveTotalBytes;
        o.maxDownloadBytes = maxDownloadBytes;
        o.probeLimit = probeLimit;
        o.followRemoteLinks = followRemoteLinks;
        o.xtreamFallbacks = xtreamFallbacks;
        o.userAgent = userAgent;
        o.connectTimeoutMs = connectMs;
        o.readTimeoutMs = readMs;
        o.blacklist = blacklist;
        o.portalParallel = portalParallel;
        o.portalBulkReadTimeoutMs = portalBulkReadTimeoutMs;
        o.portalParallelMacs = portalParallelMacs;
        o.stalkerLive = stalkerLive;
        o.stalkerMovies = stalkerMovies;
        o.stalkerSeries = stalkerSeries;
        o.stalkerPerMac = stalkerPerMac;
        o.stalkerMaxItems = stalkerMaxItems;
        o.portalDeadlineMs = portalDeadlineMs;
        o.skipKeys = skipKeys;
        o.portalTypes.putAll(portalTypes);
        o.portalListener = portalListener;
        o.archivePasswords.addAll(archivePasswords);
        return o;
    }

    /** Tried in order on password protected 7z / rar archives. */
    public final List<String> archivePasswords = new ArrayList<>();

    public static boolean within(long limit, long value) {
        return limit <= 0 || value <= limit;
    }
}
