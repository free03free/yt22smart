package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Internal channel url of a Stalker portal channel:
 * <pre>stalker://host/?c=&lt;cmd&gt;&amp;p=&lt;portal url&gt;&amp;m=&lt;MAC1,MAC2,...&gt;</pre>
 * It is stored in the library like any other url and turned into a real stream url only when the channel is played
 * (portal stream links are short lived, so they can not be saved).
 */
public final class StalkerLink {
    public static final String SCHEME = "stalker://";

    public final String portal;
    public final List<String> macs;
    public final String cmd;

    private StalkerLink(String portal, List<String> macs, String cmd) {
        this.portal = portal;
        this.macs = macs;
        this.cmd = cmd;
    }

    public static boolean isStalker(String url) {
        return url != null && url.trim().regionMatches(true, 0, SCHEME, 0, SCHEME.length());
    }

    public static String build(String portal, List<String> macs, String cmd) {
        StringBuilder m = new StringBuilder();
        for (String mac : macs) {
            if (m.length() > 0) m.append(',');
            m.append(mac);
        }
        return SCHEME + IptvUrls.hostOf(portal) + "/?c=" + enc(cmd) + "&p=" + enc(portal) + "&m=" + enc(m.toString());
    }

    /** @return null when the url is not a valid stalker link */
    public static StalkerLink parse(String url) {
        if (!isStalker(url)) {
            return null;
        }
        String u = url.trim();
        int q = u.indexOf('?');
        if (q < 0) {
            return null;
        }
        String portal = null;
        String cmd = null;
        List<String> macs = new ArrayList<>();
        for (String pair : u.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String k = pair.substring(0, eq);
            String v = dec(pair.substring(eq + 1));
            if (k.equals("p")) {
                portal = v;
            } else if (k.equals("c")) {
                cmd = v;
            } else if (k.equals("m")) {
                for (String mac : v.split(",")) {
                    if (!mac.trim().isEmpty()) macs.add(mac.trim());
                }
            }
        }
        if (portal == null || portal.isEmpty() || cmd == null || cmd.isEmpty() || macs.isEmpty()) {
            return null;
        }
        return new StalkerLink(portal, macs, cmd);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String dec(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }
}
