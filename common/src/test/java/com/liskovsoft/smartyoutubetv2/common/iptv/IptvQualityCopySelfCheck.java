package com.liskovsoft.smartyoutubetv2.common.iptv;

/** Plain JVM self check (no JUnit needed):  java IptvQualityCopySelfCheck */
public class IptvQualityCopySelfCheck {
    static int failed = 0;
    static int passed = 0;

    static void check(boolean ok, String what) {
        if (ok) passed++; else { failed++; System.out.println("FAIL: " + what); }
    }

    static IptvChannel ch(String name, String url, String list, int kind) {
        IptvChannel c = new IptvChannel(name, url);
        c.list = list;
        c.kind = kind;
        return c;
    }

    static IptvChannel ch(String name, String url, String list) {
        return ch(name, url, list, IptvChannel.KIND_LIVE);
    }

    public static void main(String[] args) {
        // ---- guessHeight
        check(IptvAlternatives.guessHeight("beIN Sports 1 FHD") == 1080, "FHD");
        check(IptvAlternatives.guessHeight("|AR| beIN Sports 1 HD") == 720, "HD");
        check(IptvAlternatives.guessHeight("beIN Sports 1 SD") == 480, "SD");
        check(IptvAlternatives.guessHeight("beIN Sports 1 Full HD") == 1080, "Full HD");
        check(IptvAlternatives.guessHeight("Movie 2160p") == 2160 && IptvAlternatives.guessHeight("Movie 4K") == 2160, "4K");
        check(IptvAlternatives.guessHeight("Movie 360p") == 360, "360p");
        check(IptvAlternatives.guessHeight("Movie 1080p") == 1080 && IptvAlternatives.guessHeight("Movie 720p") == 720, "p suffix");
        check(IptvAlternatives.guessHeight("beIN Sports 1") == 0, "no marker");
        check(IptvAlternatives.guessHeight("Cinema 360") == 0, "bare number is not a quality");
        check(IptvAlternatives.guessHeight("Shield TV") == 0 && IptvAlternatives.guessHeight("HDTV News") == 0, "hd inside a word");
        check(IptvAlternatives.guessHeight(null) == 0, "null");

        // ---- normalize keeps matching the same channel across qualities
        check(IptvAlternatives.normalize("beIN Sports 1 Full HD").equals(IptvAlternatives.normalize("[US] BEIN SPORTS 1 SD")), "normalize full hd / sd");
        check(IptvAlternatives.normalize("beIN Sports 1 360p").equals(IptvAlternatives.normalize("beIN Sports 1 FHD")), "normalize 360p");

        // ---- lowerQuality
        IptvPlaylist lib = new IptvPlaylist();
        IptvChannel fhd = ch("beIN Sports 1 FHD", "http://a/1", "A");
        IptvChannel hd = ch("beIN Sports 1 HD", "http://a/2", "A");
        IptvChannel sd = ch("beIN Sports 1 SD", "http://a/3", "A");
        IptvChannel sdOther = ch("beIN Sports 1 SD", "http://b/3", "B");
        IptvChannel p360 = ch("beIN Sports 1 360p", "http://a/4", "A");
        IptvChannel movieOther = ch("beIN Sports 1 SD", "http://a/5", "A", IptvChannel.KIND_MOVIE);
        lib.add(fhd); lib.add(hd); lib.add(sd); lib.add(sdOther); lib.add(movieOther);
        IptvAlternatives.QualityIndex idx = new IptvAlternatives.QualityIndex(lib);

        check(idx.lowerQuality(fhd, 720) == hd, "cap 720: FHD -> HD");
        check(idx.lowerQuality(fhd, 480) == sd, "cap 480: FHD -> SD of the same list");
        check(idx.lowerQuality(hd, 480) == sd, "cap 480: HD -> SD");
        check(idx.lowerQuality(fhd, 360) == sd, "nothing within 360: the lightest copy (SD)");
        check(idx.lowerQuality(sd, 480) == null, "already within the cap");
        check(idx.lowerQuality(sd, 720) == null, "SD stays SD");
        check(idx.lowerQuality(hd, 1080) == null, "HD within a 1080 cap");
        check(idx.lowerQuality(fhd, 0) == null, "no limit: nothing to do");
        check(idx.lowerQuality(ch("Other Channel HD", "http://a/9", "A"), 360) == null, "no copy of that channel");

        IptvChannel plain = ch("beIN Sports 1", "http://a/6", "A");
        check(idx.lowerQuality(plain, 480) == sd, "unknown quality channel -> SD within the cap");
        check(idx.lowerQuality(plain, 360) == null, "unknown quality, copy not within the cap: keep the channel");

        IptvChannel plainB = ch("beIN Sports 1", "http://c/6", "C");
        IptvChannel got = idx.lowerQuality(plainB, 480);
        check(got == sd || got == sdOther, "another list's copy is used when the list has none");

        IptvChannel hdB = ch("beIN Sports 1 HD", "http://b/2", "B");
        check(idx.lowerQuality(hdB, 480) == sdOther, "same list preferred (list B)");

        // an exact duplicate of the playing entry is never offered
        IptvPlaylist only = new IptvPlaylist();
        only.add(sd);
        check(new IptvAlternatives.QualityIndex(only).lowerQuality(ch("beIN Sports 1 FHD", sd.url, "A"), 360) == null, "same url is not a copy");

        // a 360p copy beats SD when it fits the cap
        lib.add(p360);
        idx = new IptvAlternatives.QualityIndex(lib);
        check(idx.lowerQuality(fhd, 360) == p360, "cap 360: 360p copy");
        check(idx.lowerQuality(fhd, 480) == sd, "cap 480: SD (480) is higher than 360p and within the cap");

        // movies and live channels are not mixed
        check(idx.lowerQuality(ch("beIN Sports 1 HD", "http://a/7", "A", IptvChannel.KIND_MOVIE), 480) == movieOther, "movies match movies only");

        System.out.println("passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
