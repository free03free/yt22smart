package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Works out what to download again to update one list. Pure Java. */
public final class IptvRefresh {
    /** http://host:port/[live|movie|series/]user/pass/123[.ext] */
    static final Pattern XTREAM_STREAM = Pattern.compile("^(https?://[^/]+)/(?:live/|movie/|series/)?([^/]+)/([^/]+)/\\d+(?:\\.\\w+)?$");

    private IptvRefresh() {
    }

    /**
     * @param channels the channels of the list
     * @param savedLink the link the list was downloaded from (may be null)
     * @return text to import again, or null when the source is unknown
     */
    public static String sourceFor(List<IptvChannel> channels, String savedLink) {
        for (IptvChannel c : channels) {
            if (StalkerLink.isStalker(c.url)) {
                StalkerLink link = StalkerLink.parse(c.url);
                if (link != null) {
                    StringBuilder sb = new StringBuilder(link.portal);
                    for (String mac : link.macs) {
                        sb.append('\n').append(mac);
                    }
                    return sb.toString();
                }
            }
        }
        if (savedLink != null && !savedLink.trim().isEmpty()) {
            return savedLink.trim();
        }
        for (IptvChannel c : channels) {
            XtreamAccount a = xtreamOf(c.url);
            if (a != null) {
                return a.m3uUrl("m3u_plus", "ts");
            }
        }
        return null;
    }

    static XtreamAccount xtreamOf(String url) {
        if (url == null) {
            return null;
        }
        Matcher m = XTREAM_STREAM.matcher(url.trim());
        return m.matches() ? new XtreamAccount(m.group(1), m.group(2), m.group(3)) : null;
    }
}
