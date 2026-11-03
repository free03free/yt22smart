package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** JVM self check of the Arabic ordering. */
public class ArabicSortSelfCheck {
    static int passed, failed;
    static void check(boolean ok, String what) { if (ok) passed++; else { failed++; System.out.println("FAIL: " + what); } }

    public static void main(String[] a) {
        List<String> names = new ArrayList<>(Arrays.asList(
                "Al Jazeera", "الجزيرة", "أبوظبي", "ابو ظبي", "MBC", "2M", "العربية", "بي بي سي", "سما دبي", "إم بي سي", "قناة الحدث", "٣ سبورت"));
        Collections.sort(names, ArabicSort.COMPARATOR);
        System.out.println(names);
        check(names.get(0).equals("2M") && names.get(1).equals("٣ سبورت"), "digits first: " + names);
        check(names.indexOf("أبوظبي") < names.indexOf("بي بي سي"), "alef before ba");
        check(names.indexOf("الجزيرة") < names.indexOf("سما دبي") && names.indexOf("الجزيرة") > names.indexOf("بي بي سي"), "article ignored: الجزيرة under ج");
        check(names.indexOf("العربية") > names.indexOf("سما دبي") && names.indexOf("العربية") < names.indexOf("قناة الحدث"), "العربية under ع");
        check(names.indexOf("إم بي سي") < names.indexOf("بي بي سي") && names.indexOf("إم بي سي") < names.indexOf("MBC"), "arabic before latin");
        check(names.indexOf("MBC") > names.indexOf("قناة الحدث"), "latin last");
        check(ArabicSort.key("الْجَزِيرَة").equals(ArabicSort.key("الجزيرة")), "tashkeel ignored");
        check(ArabicSort.key("ال").equals("ال"), "a bare article is kept");
        check(ArabicSort.key(null).isEmpty(), "null safe");
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
