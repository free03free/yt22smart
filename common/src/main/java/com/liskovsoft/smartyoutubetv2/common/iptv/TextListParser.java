package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses free form text lists: "Name,url", "Name$url", "Name url", "Name|url", one url per line,
 * "Group,#genre#" sections, [Group] sections, a title line followed by a url line, and urls buried in arbitrary text.
 */
public final class TextListParser {
    private static final Pattern GENRE = Pattern.compile("(?i)^(.*?)[,，;|\\s]*#genre#\\s*$");
    private static final Pattern BRACKET_GROUP = Pattern.compile("^\\[([^\\]]{1,80})\\]$");
    private static final Pattern TRAILING_SEP = Pattern.compile("[\\s,;|$=:>\\-\\u2013\\u2014]+$");

    private TextListParser() {
    }

    public static List<IptvChannel> parse(String text) {
        List<IptvChannel> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }

        String group = null;
        String pendingTitle = null;

        for (String raw : text.split("\r\n|\r|\n")) {
            String t = raw.trim();
            if (t.isEmpty()) {
                continue;
            }
            Matcher g = GENRE.matcher(t);
            if (g.matches()) {
                group = g.group(1).trim();
                pendingTitle = null;
                continue;
            }
            if (t.startsWith("//") || t.startsWith("#")) {
                continue;
            }
            Matcher bg = BRACKET_GROUP.matcher(t);
            if (bg.matches() && !t.contains("://")) {
                group = bg.group(1).trim();
                continue;
            }

            Matcher m = IptvUrls.STREAM_URL.matcher(t);
            List<String> urls = new ArrayList<>();
            int firstStart = -1;
            while (m.find()) {
                if (firstStart < 0) firstStart = m.start();
                for (String part : IptvUrls.splitMulti(m.group())) {
                    String u = IptvUrls.stripTrailingJunk(part.trim());
                    if (IptvUrls.isStreamUrl(u)) {
                        urls.add(u);
                    }
                }
            }
            if (urls.isEmpty()) {
                if (t.length() <= 200) {
                    pendingTitle = t;
                }
                continue;
            }

            String name = TRAILING_SEP.matcher(t.substring(0, firstStart)).replaceAll("").trim();
            name = stripQuotes(name);
            if (name.isEmpty() && pendingTitle != null) {
                name = pendingTitle;
            }
            pendingTitle = null;

            for (String u : urls) {
                IptvChannel c = new IptvChannel();
                c.url = u;
                IptvUrls.applyHeaderSuffix(c);
                c.group = group;
                c.name = !name.isEmpty() && urls.size() == 1 ? name : (!name.isEmpty() ? name : IptvUrls.titleFromUrl(c.url));
                IptvUrls.classify(c);
                result.add(c);
            }
        }
        return result;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char a = s.charAt(0);
            char b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                return s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }
}
