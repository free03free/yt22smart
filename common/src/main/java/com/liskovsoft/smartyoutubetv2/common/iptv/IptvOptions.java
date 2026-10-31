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

    /** Tried in order on password protected 7z / rar archives. */
    public final List<String> archivePasswords = new ArrayList<>();

    public static boolean within(long limit, long value) {
        return limit <= 0 || value <= limit;
    }
}
