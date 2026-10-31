package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** URL helpers shared by all parsers. Only well known streaming schemes are accepted (no file://, content://, javascript:). */
public final class IptvUrls {
    public static final String SCHEMES = "https?|rtmps?|rtmpe|rtmpt|rtsps?|udp|rtp|mmsh?t?|srt|rist|acestream|sop";
    public static final Pattern STREAM_URL = Pattern.compile(
            "(?i)\\b(?:" + SCHEMES + ")://[^\\s\"'<>\\u0000-\\u001f]+");
    private static final Pattern STARTS_WITH_URL = Pattern.compile("(?i)^(?:" + SCHEMES + ")://\\S+");
    private static final Pattern SPLIT_MULTI = Pattern.compile("#(?=(?i:" + SCHEMES + ")://)");

    private IptvUrls() {
    }

    public static boolean isStreamUrl(String s) {
        return s != null && STARTS_WITH_URL.matcher(s.trim()).find();
    }

    public static String stripTrailingJunk(String url) {
        int end = url.length();
        while (end > 0) {
            char c = url.charAt(end - 1);
            if (c == ',' || c == ';' || c == '\'' || c == '"' || c == ')' || c == ']' || c == '}' || c == '>' || c == '.') {
                end--;
            } else {
                break;
            }
        }
        return url.substring(0, end);
    }

    /** "http://a#http://b" -> ["http://a", "http://b"] (multi-source entries used by many txt lists). */
    public static String[] splitMulti(String token) {
        return SPLIT_MULTI.split(token);
    }

    public static String extension(String url) {
        if (url == null) {
            return "";
        }
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int h = path.indexOf('#');
        if (h >= 0) path = path.substring(0, h);
        int bar = path.indexOf('|');
        if (bar >= 0) path = path.substring(0, bar);
        int slash = path.lastIndexOf('/');
        String file = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = file.lastIndexOf('.');
        return dot >= 0 ? file.substring(dot + 1).toLowerCase() : "";
    }

    public static String hostOf(String url) {
        Matcher m = Pattern.compile("(?i)^[a-z][a-z0-9+.-]*://(?:[^/@]*@)?([^/:?#]+)").matcher(url == null ? "" : url);
        return m.find() ? m.group(1) : "";
    }

    /** Fallback channel title built from the url. */
    public static String titleFromUrl(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        int slash = path.lastIndexOf('/');
        String file = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = file.lastIndexOf('.');
        if (dot > 0) file = file.substring(0, dot);
        if (file.isEmpty() || file.endsWith(":") || file.equals("/")) {
            return hostOf(url);
        }
        return file;
    }

    /**
     * Kodi style header suffix: "http://host/stream.m3u8|User-Agent=Foo&Referer=http://x".
     * The suffix is removed from channel.url and moved into the channel fields.
     */
    public static void applyHeaderSuffix(IptvChannel channel) {
        if (channel.url == null) {
            return;
        }
        int bar = channel.url.indexOf('|');
        if (bar < 0) {
            return;
        }
        String suffix = channel.url.substring(bar + 1);
        if (!suffix.contains("=")) {
            return;
        }
        channel.url = channel.url.substring(0, bar).trim();
        for (String pair : suffix.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String k = pair.substring(0, eq).trim().toLowerCase();
            String v = pair.substring(eq + 1).trim();
            if (k.equals("user-agent")) {
                channel.userAgent = v;
            } else if (k.equals("referer") || k.equals("referrer")) {
                channel.referrer = v;
            }
        }
    }

    /** Sets {@link IptvChannel#kind} from Xtream style paths. */
    public static void classify(IptvChannel c) {
        String u = c.url == null ? "" : c.url.toLowerCase();
        if (u.contains("/movie/")) {
            c.kind = IptvChannel.KIND_MOVIE;
        } else if (u.contains("/series/")) {
            c.kind = IptvChannel.KIND_SERIES;
        }
    }
}
