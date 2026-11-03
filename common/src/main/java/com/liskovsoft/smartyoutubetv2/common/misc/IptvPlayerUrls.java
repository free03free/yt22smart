package com.liskovsoft.smartyoutubetv2.common.misc;

import java.util.Locale;

/** Tiny helper kept apart from the Activity so it can be unit tested on a plain JVM. */
final class IptvPlayerUrls {
    private IptvPlayerUrls() {
    }

    /** Best guess whether the url is an HLS playlist (the player tries the other type if it was wrong). */
    static boolean looksLikeHls(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase(Locale.ROOT);
        return pathOf(u).endsWith(".m3u8") || pathOf(u).endsWith(".m3u") || u.contains(".m3u8");
    }

    /** Best guess whether the url is an MPEG-DASH manifest (.mpd). */
    static boolean looksLikeDash(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase(Locale.ROOT);
        return pathOf(u).endsWith(".mpd") || u.contains(".mpd?") || u.contains(".mpd/");
    }

    private static String pathOf(String lowerUrl) {
        int query = lowerUrl.indexOf('?');
        return query == -1 ? lowerUrl : lowerUrl.substring(0, query);
    }
}
