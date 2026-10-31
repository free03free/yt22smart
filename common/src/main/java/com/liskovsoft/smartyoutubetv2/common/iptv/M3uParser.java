package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses M3U / M3U8 / M3U_PLUS channel lists (#EXTINF attributes, #EXTGRP, #EXTVLCOPT, Kodi header suffix). */
public final class M3uParser {
    private static final Pattern ATTR = Pattern.compile(
            "([A-Za-z0-9_\\-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\",]+))");

    private M3uParser() {
    }

    /** True for HLS media/master playlists (a single stream), false for channel lists. */
    public static boolean isHlsStream(String text) {
        return text.contains("#EXT-X-TARGETDURATION") || text.contains("#EXT-X-STREAM-INF")
                || text.contains("#EXT-X-MEDIA-SEQUENCE") || text.contains("#EXT-X-VERSION");
    }

    public static List<IptvChannel> parse(String text) {
        List<IptvChannel> result = new ArrayList<>();
        if (text == null) {
            return result;
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }

        IptvChannel pending = null;
        String extGroup = null;
        String vlcUa = null;
        String vlcRef = null;

        for (String raw : text.split("\r\n|\r|\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                String upper = line.toUpperCase();
                if (upper.startsWith("#EXTINF")) {
                    pending = parseExtInf(line);
                } else if (upper.startsWith("#EXTGRP:")) {
                    extGroup = line.substring(8).trim();
                } else if (upper.startsWith("#EXTVLCOPT:")) {
                    String opt = line.substring(11).trim();
                    int eq = opt.indexOf('=');
                    if (eq > 0) {
                        String k = opt.substring(0, eq).trim().toLowerCase();
                        String v = opt.substring(eq + 1).trim();
                        if (k.equals("http-user-agent")) vlcUa = v;
                        else if (k.equals("http-referrer") || k.equals("http-referer")) vlcRef = v;
                    }
                }
                continue;
            }
            if (!IptvUrls.isStreamUrl(line)) {
                continue;
            }

            IptvChannel c = pending != null ? pending : new IptvChannel();
            c.url = line;
            IptvUrls.applyHeaderSuffix(c);
            if (c.userAgent == null) c.userAgent = vlcUa;
            if (c.referrer == null) c.referrer = vlcRef;
            if (c.group == null || c.group.isEmpty()) c.group = extGroup;
            if (c.name == null || c.name.isEmpty()) {
                c.name = c.tvgName != null && !c.tvgName.isEmpty() ? c.tvgName : IptvUrls.titleFromUrl(c.url);
            }
            IptvUrls.classify(c);
            result.add(c);

            pending = null;
            extGroup = null;
            vlcUa = null;
            vlcRef = null;
        }
        return result;
    }

    private static IptvChannel parseExtInf(String line) {
        IptvChannel c = new IptvChannel();
        int colon = line.indexOf(':');
        String body = colon >= 0 ? line.substring(colon + 1) : "";

        // display name = text after the first comma that is outside of quotes
        int comma = -1;
        char quote = 0;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (quote != 0) {
                if (ch == quote) quote = 0;
            } else if (ch == '"' || ch == '\'') {
                // a quote only opens a value right after '='
                if (i > 0 && body.charAt(i - 1) == '=') quote = ch;
            } else if (ch == ',') {
                comma = i;
                break;
            }
        }
        String attrs = comma >= 0 ? body.substring(0, comma) : body;
        c.name = comma >= 0 ? body.substring(comma + 1).trim() : "";

        Matcher m = ATTR.matcher(attrs);
        while (m.find()) {
            String key = m.group(1).toLowerCase();
            String val = m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4);
            val = val == null ? null : val.trim();
            switch (key) {
                case "tvg-id": c.tvgId = val; break;
                case "tvg-name": c.tvgName = val; break;
                case "tvg-logo": case "tvg-logo-small": case "logo": if (c.logo == null) c.logo = val; break;
                case "group-title": case "group": c.group = val; break;
                case "user-agent": case "http-user-agent": c.userAgent = val; break;
                case "referrer": case "http-referrer": c.referrer = val; break;
                case "tvg-language": case "language": c.language = val; break;
                default: break;
            }
        }
        return c;
    }
}
