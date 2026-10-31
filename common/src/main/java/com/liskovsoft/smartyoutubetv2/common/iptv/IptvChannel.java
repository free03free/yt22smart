package com.liskovsoft.smartyoutubetv2.common.iptv;

/**
 * Single IPTV entry (live channel, movie or series episode).
 * Pure Java: no Android dependencies, so it can be unit tested on a plain JVM.
 */
public class IptvChannel {
    public static final int KIND_LIVE = 0;
    public static final int KIND_MOVIE = 1;
    public static final int KIND_SERIES = 2;

    public String name;
    public String url;
    public String group;
    public String logo;
    public String tvgId;
    public String tvgName;
    public String userAgent;
    public String referrer;
    public int kind = KIND_LIVE;

    public IptvChannel() {
    }

    public IptvChannel(String name, String url) {
        this.name = name;
        this.url = url;
    }

    /** Key used to drop duplicates when several lists are merged. */
    public String key() {
        return url == null ? "" : url.trim();
    }

    @Override
    public String toString() {
        return (group == null ? "" : "[" + group + "] ") + name + " -> " + url;
    }
}
