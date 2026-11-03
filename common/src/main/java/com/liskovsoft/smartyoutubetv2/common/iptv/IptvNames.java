package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.regex.Pattern;

/** Display helpers for channel and group names (idea from AJPanel: hide the "|AR|", "[US]", "FR -" prefixes). */
public final class IptvNames {
    private static final Pattern PREFIX = Pattern.compile(
            "^\\s*(?:\\|[^|]{1,12}\\|+|\\[[^\\]]{1,12}\\]|\\([^)]{1,12}\\)|[\\p{L}\\p{N}+]{1,6}\\s*[:|]|[\\p{L}\\p{N}+]{1,6}\\s+-\\s)\\s*");

    private IptvNames() {
    }

    /** Name without its prefix; the original name when nothing would be left. */
    public static String stripPrefix(String name) {
        if (name == null) {
            return null;
        }
        String s = name;
        for (int i = 0; i < 2; i++) {
            String next = PREFIX.matcher(s).replaceFirst("");
            if (next.equals(s) || next.trim().isEmpty()) {
                break;
            }
            s = next;
        }
        return s.trim();
    }

    /** Group title inside a list: "host | News" shows as "News" in the list "host". */
    public static String groupInList(String group, String list) {
        if (group == null || group.isEmpty() || list == null || list.isEmpty()) {
            return group;
        }
        String prefix = list + " | ";
        return group.startsWith(prefix) && group.length() > prefix.length() ? group.substring(prefix.length()) : group;
    }
}
