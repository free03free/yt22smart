package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.Arrays;
import java.util.List;

/** JVM self check of IptvPlaylist.swap / removeByUrls (used by the IPTV section's multi-select delete and move up/down). */
public class IptvPlaylistSelfCheck {
    static int passed, failed;
    static void check(boolean ok, String what) { if (ok) passed++; else { failed++; System.out.println("FAIL: " + what); } }

    public static void main(String[] a) {
        // ---- swap: only the two named channels change position, everyone else keeps their place
        IptvPlaylist p = new IptvPlaylist();
        for (int i = 1; i <= 5; i++) p.add(new IptvChannel("Ch" + i, "http://x/" + i));
        boolean ok = p.swap("http://x/2", "http://x/4");
        List<String> order = urlTail(p);
        check(ok, "swap reports success");
        check(order.equals(Arrays.asList("1", "4", "3", "2", "5")), "swap(2,4): only 2 and 4 trade places, got " + order);

        check(!p.swap("http://x/2", "http://nope"), "swap with a missing url fails cleanly");
        check(urlTail(p).equals(Arrays.asList("1", "4", "3", "2", "5")), "a failed swap does not touch the list");

        // ---- removeByUrls: several at once, one pass
        int removed = p.removeByUrls(Arrays.asList("http://x/1", "http://x/3", "http://nope"));
        check(removed == 2, "removeByUrls reports how many were actually removed (" + removed + ")");
        check(urlTail(p).equals(Arrays.asList("4", "2", "5")), "the right ones are gone, order of the rest kept: " + urlTail(p));
        check(p.size() == 3, "size matches after removeByUrls");

        // a channel removed by removeByUrls can be re-added (its key was really freed)
        check(p.add(new IptvChannel("again", "http://x/1")), "a removed url can be re-added");

        System.out.println("IptvPlaylistSelfCheck: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static List<String> urlTail(IptvPlaylist p) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (IptvChannel c : p.getChannels()) out.add(c.url.substring(c.url.lastIndexOf('/') + 1));
        return out;
    }
}
