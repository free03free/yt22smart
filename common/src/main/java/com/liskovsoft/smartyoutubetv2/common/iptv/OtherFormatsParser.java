package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PLS, XSPF and loosely structured JSON playlists. */
public final class OtherFormatsParser {
    private static final Pattern PLS_ENTRY = Pattern.compile("(?im)^\\s*(File|Title)(\\d+)\\s*=\\s*(.*?)\\s*$");
    private static final Pattern XSPF_TRACK = Pattern.compile("(?is)<track\\b.*?</track>");
    private static final Pattern XSPF_LOCATION = Pattern.compile("(?is)<location>\\s*(.*?)\\s*</location>");
    private static final Pattern XSPF_TITLE = Pattern.compile("(?is)<title>\\s*(.*?)\\s*</title>");
    private static final Pattern XSPF_IMAGE = Pattern.compile("(?is)<image>\\s*(.*?)\\s*</image>");
    private static final String NAME_KEYS = "name|title|channel|channel_name|tvg_name|tvg-name";
    private static final String URL_KEYS = "url|stream_url|stream|link|src|source|file|uri";
    private static final Pattern JSON_NAME_URL = Pattern.compile(
            "(?is)\"(?:" + NAME_KEYS + ")\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"[^{}]*?\"(?:" + URL_KEYS + ")\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern JSON_URL_NAME = Pattern.compile(
            "(?is)\"(?:" + URL_KEYS + ")\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"[^{}]*?\"(?:" + NAME_KEYS + ")\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private OtherFormatsParser() {
    }

    public static List<IptvChannel> parsePls(String text) {
        java.util.Map<String, String> files = new java.util.TreeMap<>();
        java.util.Map<String, String> titles = new java.util.HashMap<>();
        Matcher m = PLS_ENTRY.matcher(text);
        while (m.find()) {
            String idx = String.format("%08d", Integer.parseInt(m.group(2)));
            if (m.group(1).equalsIgnoreCase("File")) files.put(idx, m.group(3));
            else titles.put(idx, m.group(3));
        }
        List<IptvChannel> out = new ArrayList<>();
        for (java.util.Map.Entry<String, String> e : files.entrySet()) {
            if (!IptvUrls.isStreamUrl(e.getValue())) continue;
            IptvChannel c = new IptvChannel();
            c.url = e.getValue();
            IptvUrls.applyHeaderSuffix(c);
            String title = titles.get(e.getKey());
            c.name = title == null || title.isEmpty() ? IptvUrls.titleFromUrl(c.url) : title;
            IptvUrls.classify(c);
            out.add(c);
        }
        return out;
    }

    public static List<IptvChannel> parseXspf(String text) {
        List<IptvChannel> out = new ArrayList<>();
        Matcher t = XSPF_TRACK.matcher(text);
        while (t.find()) {
            String track = t.group();
            Matcher loc = XSPF_LOCATION.matcher(track);
            if (!loc.find()) continue;
            String url = unescapeXml(loc.group(1));
            if (!IptvUrls.isStreamUrl(url)) continue;
            IptvChannel c = new IptvChannel();
            c.url = url;
            Matcher title = XSPF_TITLE.matcher(track);
            c.name = title.find() ? unescapeXml(title.group(1)) : IptvUrls.titleFromUrl(url);
            Matcher img = XSPF_IMAGE.matcher(track);
            if (img.find()) c.logo = unescapeXml(img.group(1));
            IptvUrls.classify(c);
            out.add(c);
        }
        return out;
    }

    /** Pairs of name/url keys in any JSON shape; falls back to plain url extraction for everything else. */
    public static List<IptvChannel> parseJson(String text) {
        List<IptvChannel> out = new ArrayList<>();
        Matcher m = JSON_NAME_URL.matcher(text);
        while (m.find()) {
            addJson(out, m.group(1), m.group(2));
        }
        m = JSON_URL_NAME.matcher(text);
        while (m.find()) {
            addJson(out, m.group(2), m.group(1));
        }
        if (out.isEmpty()) {
            out.addAll(TextListParser.parse(text.replace("\\/", "/")));
        }
        return out;
    }

    private static void addJson(List<IptvChannel> out, String name, String url) {
        url = unescapeJson(url);
        if (!IptvUrls.isStreamUrl(url)) return;
        IptvChannel c = new IptvChannel(unescapeJson(name), url.trim());
        IptvUrls.applyHeaderSuffix(c);
        IptvUrls.classify(c);
        out.add(c);
    }

    private static String unescapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case '/': sb.append('/'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'n': sb.append(' '); break;
                    case 't': sb.append(' '); break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                                break;
                            } catch (NumberFormatException e) {
                                // fall through
                            }
                        }
                        sb.append('u');
                        break;
                    default: sb.append(n);
                }
            } else {
                sb.append(ch);
            }
        }
        return sb.toString().trim();
    }

    private static String unescapeXml(String s) {
        String r = s;
        if (r.startsWith("<![CDATA[") && r.endsWith("]]>")) {
            return r.substring(9, r.length() - 3).trim();
        }
        return r.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }
}
