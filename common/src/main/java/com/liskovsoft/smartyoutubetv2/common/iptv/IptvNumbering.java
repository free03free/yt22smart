package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Channel numbers like a receiver: a channel can have a fixed number chosen by the user that never changes when the
 * list is sorted or channels are deleted. The other channels take the lowest free numbers, in list order.
 * Pure Java (no Android), so it is covered by a JVM self check.
 */
public final class IptvNumbering {
    private IptvNumbering() {
    }

    /**
     * @param orderedUrls all channel keys of the library, in library order
     * @param fixed       url -> fixed number chosen by the user (entries for unknown urls are ignored)
     * @return url -> number, unique numbers, in the order of {@code orderedUrls}
     */
    public static Map<String, Integer> resolve(List<String> orderedUrls, Map<String, Integer> fixed) {
        Map<String, Integer> result = new LinkedHashMap<>();
        Set<Integer> used = new HashSet<>();
        if (fixed != null) {
            for (String url : orderedUrls) {
                Integer n = fixed.get(url);
                if (n != null && n >= 1 && used.add(n)) { // a duplicated number: the first channel keeps it
                    result.put(url, n);
                }
            }
        }
        int next = 1;
        for (String url : orderedUrls) {
            if (result.containsKey(url)) {
                continue;
            }
            while (used.contains(next)) {
                next++;
            }
            result.put(url, next);
            used.add(next);
        }
        // keep the iteration order equal to the library order
        Map<String, Integer> ordered = new LinkedHashMap<>();
        for (String url : orderedUrls) {
            ordered.put(url, result.get(url));
        }
        return ordered;
    }

    /** @return the url that has {@code number}, or null */
    public static String urlOf(Map<String, Integer> numbers, int number) {
        for (Map.Entry<String, Integer> e : numbers.entrySet()) {
            if (e.getValue() == number) {
                return e.getKey();
            }
        }
        return null;
    }
}
