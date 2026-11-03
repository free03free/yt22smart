package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** JVM self check of the receiver-like channel numbering (fixed numbers + automatic numbers). */
public class IptvNumberingSelfCheck {
    static int passed, failed;
    static void check(boolean ok, String what) { if (ok) passed++; else { failed++; System.out.println("FAIL: " + what); } }

    public static void main(String[] a) {
        List<String> all = Arrays.asList("a", "b", "c", "d", "e");

        // no fixed numbers: 1..n in list order
        Map<String, Integer> plain = IptvNumbering.resolve(all, new HashMap<String, Integer>());
        check(plain.get("a") == 1 && plain.get("e") == 5, "plain numbering 1..5: " + plain);

        // a fixed number is kept, the others take the free numbers in order
        Map<String, Integer> fixed = new HashMap<>();
        fixed.put("c", 1);
        Map<String, Integer> r = IptvNumbering.resolve(all, fixed);
        check(r.get("c") == 1 && r.get("a") == 2 && r.get("b") == 3 && r.get("d") == 4 && r.get("e") == 5, "c fixed at 1: " + r);

        // a fixed number far away leaves a gap, and sorting the list does not move it
        fixed.clear();
        fixed.put("b", 100);
        r = IptvNumbering.resolve(all, fixed);
        check(r.get("b") == 100 && r.get("a") == 1 && r.get("c") == 2, "b fixed at 100: " + r);
        r = IptvNumbering.resolve(Arrays.asList("e", "d", "c", "b", "a"), fixed);
        check(r.get("b") == 100 && r.get("e") == 1 && r.get("a") == 4, "reversed list keeps b at 100: " + r);

        // deleting a channel: fixed numbers stay, automatic ones close the gap
        r = IptvNumbering.resolve(Arrays.asList("a", "c", "d", "e"), fixed);
        check(r.get("a") == 1 && r.get("c") == 2 && r.get("e") == 4 && !r.containsKey("b"), "after deleting b: " + r);

        // a fixed number of a channel that does not exist is ignored; a duplicated fixed number gives it to the first channel
        fixed.clear();
        fixed.put("zzz", 1);
        fixed.put("c", 2);
        fixed.put("d", 2);
        r = IptvNumbering.resolve(all, fixed);
        check(r.get("a") == 1 && r.get("c") == 2 && r.get("b") == 3 && r.get("d") == 4 && r.get("e") == 5, "unknown / duplicate fixed numbers: " + r);

        // all numbers are unique
        check(new java.util.HashSet<>(r.values()).size() == all.size(), "numbers are unique");

        check("c".equals(IptvNumbering.urlOf(r, 2)) && IptvNumbering.urlOf(r, 99) == null, "urlOf finds the channel of a number");

        System.out.println("IptvNumberingSelfCheck: " + passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }
}
