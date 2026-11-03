package com.liskovsoft.smartyoutubetv2.common.iptv;

/** What to fetch from a Stalker portal. Pure Java. */
public final class StalkerOptions {
    public boolean live = true;
    public boolean movies;
    public boolean series;
    /** Every MAC that works gets its own list ("host [MAC]"); false: the first MAC that works is used for everything. */
    public boolean perMac = true;
    /** Largest number of movies, and of series, fetched from one portal / MAC. 0 = no limit. */
    public int maxItems = 5000;

    public static StalkerOptions liveOnly() {
        StalkerOptions o = new StalkerOptions();
        o.perMac = false;
        return o;
    }

    boolean fetchLive() {
        return live || (!movies && !series); // never fetch nothing
    }
}
