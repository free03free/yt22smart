package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Stalker / Ministra ("portal") account: one portal url + one or more MAC addresses (the MAC is the login).
 * Unlike m3u lists a portal is not a file: channels are fetched with an API handshake and every stream link is
 * created on demand (see {@link StalkerClient}).
 *
 * Recognised text layout (any mix, also inside JSON / free text):
 * <pre>
 *   http://host/c/
 *   00:1A:79:AB:1B:80
 *   00:1A:79:AB:1B:6F
 * </pre>
 * Every http(s) url that is followed by at least one MAC address starts a portal; MACs belong to the last url before them.
 */
public final class StalkerAccount {
    private static final Pattern URL = Pattern.compile("(?i)\\bhttps?://[^\\s\"'<>\\u0000-\\u001f]+");
    private static final Pattern MAC = Pattern.compile("(?i)(?<![0-9a-f:])[0-9a-f]{2}(?::[0-9a-f]{2}){5}(?![0-9a-f:])");

    /** Portal url exactly as given, without query / fragment (e.g. http://host/c/). */
    public final String portal;
    public final List<String> macs = new ArrayList<>();

    public StalkerAccount(String portal) {
        this.portal = portal;
    }

    public String host() {
        return IptvUrls.hostOf(portal);
    }

    /** Normalised key used to merge the same portal listed twice. */
    String key() {
        String p = portal.toLowerCase(Locale.ROOT);
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    public static String normalizeMac(String mac) {
        return mac == null ? null : mac.trim().toUpperCase(Locale.ROOT);
    }

    /** @return the portals found in the text (portals without any MAC are not returned). */
    public static List<StalkerAccount> parseAll(String text) {
        List<StalkerAccount> result = new ArrayList<>();
        if (text == null || text.isEmpty() || !MAC.matcher(text).find()) {
            return result;
        }

        // Walk urls and MACs in text order; a MAC belongs to the closest url before it
        List<int[]> tokens = new ArrayList<>(); // {position, 0 = url / 1 = mac, group start, group end}
        Matcher u = URL.matcher(text);
        while (u.find()) {
            tokens.add(new int[] {u.start(), 0, u.start(), u.end()});
        }
        Matcher m = MAC.matcher(text);
        while (m.find()) {
            tokens.add(new int[] {m.start(), 1, m.start(), m.end()});
        }
        tokens.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));

        Map<String, StalkerAccount> byKey = new LinkedHashMap<>();
        StalkerAccount current = null;
        for (int[] t : tokens) {
            String s = text.substring(t[2], t[3]);
            if (t[1] == 0) {
                String portal = cleanPortal(s);
                StalkerAccount existing = portal == null ? null : byKey.get(new StalkerAccount(portal).key());
                current = existing != null ? existing : portal == null ? null : new StalkerAccount(portal);
                if (current != null && existing == null) {
                    byKey.put(current.key(), current);
                }
            } else if (current != null) {
                String mac = normalizeMac(s);
                if (!current.macs.contains(mac)) {
                    current.macs.add(mac);
                }
            }
        }
        for (StalkerAccount a : byKey.values()) {
            if (!a.macs.isEmpty()) {
                result.add(a);
            }
        }
        return result;
    }

    private static String cleanPortal(String url) {
        String u = IptvUrls.stripTrailingJunk(url);
        int cut = u.length();
        int q = u.indexOf('?');
        if (q >= 0) cut = Math.min(cut, q);
        int h = u.indexOf('#');
        if (h >= 0) cut = Math.min(cut, h);
        u = u.substring(0, cut);
        return IptvUrls.hostOf(u).isEmpty() ? null : u;
    }

    @Override
    public String toString() {
        return portal + " (" + macs.size() + " MAC)";
    }
}
