package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.regex.Pattern;

/**
 * Cleans text that was copied or captured from a web page / PDF viewer before it is parsed:
 * - private-use glyphs (U+E000..U+F8FF): sites such as Scribd draw their text with a scrambled font, those lines
 *   cannot be read, so they are dropped (a url on such a line is kept);
 * - invisible direction marks;
 * - urls wrapped over two lines ("http://host/get.php?" + "username=..&password=..") are joined again.
 */
public final class IptvTextCleaner {
    private static final Pattern PARAM_START = Pattern.compile("^[A-Za-z0-9_.%-]+=\\S*");
    private static final Pattern LABEL_LINE = Pattern.compile(
            "(?i)^\\s*(?:server|host|url|portal|dns|link|panel|user(?:name)?|login|pass(?:word)?|pwd)\\s*[:=\\uff1a]");

    private IptvTextCleaner() {
    }

    public static String clean(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw == null ? "" : raw;
        }
        String[] lines = raw.split("\r\n|\r|\n");
        StringBuilder out = new StringBuilder(raw.length());
        String pending = null; // a line that may continue on the next one
        for (String line : lines) {
            String t = line;
            boolean scrambled = false;
            StringBuilder sb = new StringBuilder(t.length());
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (c >= '\uE000' && c <= '\uF8FF') {
                    scrambled = true;
                } else if (c == '\uFEFF' || (c >= '\u200B' && c <= '\u200F') || (c >= '\u202A' && c <= '\u202E') || (c >= '\u2066' && c <= '\u2069')) {
                    // invisible mark
                } else {
                    sb.append(c);
                }
            }
            t = sb.toString().trim();
            if (scrambled && !IptvUrls.STREAM_URL.matcher(t).find() && !LABEL_LINE.matcher(t).find()) {
                t = ""; // unreadable fragment of scrambled text
            }
            if (pending != null) {
                if (!t.isEmpty() && PARAM_START.matcher(t).matches()) {
                    pending = pending + t; // continuation of a wrapped url
                    if (!endsOpen(pending)) {
                        emit(out, pending);
                        pending = null;
                    }
                    continue;
                }
                emit(out, pending);
                pending = null;
            }
            if (t.isEmpty()) {
                continue;
            }
            if (endsOpen(t) && IptvUrls.STREAM_URL.matcher(t).find()) {
                pending = t;
            } else {
                emit(out, t);
            }
        }
        if (pending != null) {
            emit(out, pending);
        }
        return out.toString();
    }

    /** "...get.php?" or "...&type=" : the query is not finished. */
    private static boolean endsOpen(String s) {
        return s.endsWith("?") || s.endsWith("&") || s.endsWith("=");
    }

    private static void emit(StringBuilder out, String line) {
        out.append(line).append('\n');
    }
}
