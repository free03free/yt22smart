package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.Arrays;
import java.util.List;

/** JVM self check of the order helpers used by the numbered channel manager (move, sort, rename inside a group). */
public class IptvManagerOrderSelfCheck {
    static int passed, failed;
    static void check(boolean ok, String what) { if (ok) passed++; else { failed++; System.out.println("FAIL: " + what); } }

    public static void main(String[] a) {
        List<String> all = Arrays.asList("a", "b", "c", "d", "e", "f");

        // channels of one group (b, d, f) re-ordered: only their own slots change, a / c / e stay where they are
        check(IptvPlaylist.slotReorder(all, Arrays.asList("f", "b", "d")).equals(Arrays.asList("a", "b", "c", "d", "e", "f")) == false,
                "group re-order changes something");
        check(IptvPlaylist.slotReorder(all, Arrays.asList("f", "b", "d")).equals(Arrays.asList("a", "f", "c", "b", "e", "d")),
                "b,d,f -> f,b,d keeps a,c,e in place: " + IptvPlaylist.slotReorder(all, Arrays.asList("f", "b", "d")));

        // unknown keys and duplicates are ignored, nothing is lost or duplicated
        List<String> r = IptvPlaylist.slotReorder(all, Arrays.asList("d", "zzz", "b", "b"));
        check(r.equals(Arrays.asList("a", "d", "c", "b", "e", "f")), "unknown / duplicate keys ignored: " + r);

        // the same order gives the same list (nothing to save)
        check(IptvPlaylist.slotReorder(all, all).equals(all), "identity order is a no-op");

        // applyOrder on a playlist
        IptvPlaylist p = new IptvPlaylist();
        for (String k : all) p.add(new IptvChannel(k.toUpperCase(), k));
        check(p.applyOrder(Arrays.asList("f", "a")), "applyOrder reports a change");
        check(names(p).equals("F B C D E A"), "applyOrder swaps the two slots only: " + names(p));
        check(!p.applyOrder(Arrays.asList("f", "a")), "applying the same order again changes nothing");
        check(p.size() == 6 && p.add(new IptvChannel("again", "zz")) && p.size() == 7, "playlist stays consistent after applyOrder");

        // rename keeps the url (identity) and the position
        check(p.rename("c", "  Nice name "), "rename finds the channel");
        check(names(p).equals("F B Nice name D E A zz".replace("zz", "again")), "renamed in place: " + names(p));
        check(!p.rename("nope", "x") && !p.rename("c", "   "), "rename of a missing url / blank name is refused");

        // a sorted group order (what the "sort A-Z" action sends)
        IptvPlaylist q = new IptvPlaylist();
        for (String k : new String[]{"x3", "x1", "x2"}) q.add(new IptvChannel("ch" + k, k));
        q.applyOrder(Arrays.asList("x1", "x2", "x3"));
        check(names(q).equals("chx1 chx2 chx3"), "sorted order applied: " + names(q));

        System.out.println("IptvManagerOrderSelfCheck: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static String names(IptvPlaylist p) {
        StringBuilder sb = new StringBuilder();
        for (IptvChannel c : p.getChannels()) { if (sb.length() > 0) sb.append(' '); sb.append(c.name); }
        return sb.toString();
    }
}
