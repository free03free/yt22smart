package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Xtream Codes account (server + username + password) and the well known endpoints of that API.
 * Recognised inputs:
 *  - http://host:port/get.php?username=U&amp;password=P&amp;type=m3u_plus&amp;output=ts
 *  - http://host:port/player_api.php?username=U&amp;password=P[&amp;action=...]  (also panel_api.php, xmltv.php, playlist.php)
 *  - http://host:port/live|movie|series|timeshift/U/P/ID.ext   and   http://host:port/U/P/ID.ts
 *  - "host U P", "host|U|P", "host,U,P", "host:port;U;P"
 *  - copy-pasted reseller messages:  "Server: ..  Username: ..  Password: .."
 */
public final class XtreamAccount {
    public final String server;   // scheme://host[:port], no trailing slash
    public final String username;
    public final String password;

    private static final Pattern QUERY_USER = Pattern.compile("(?i)[?&]username=([^&#\\s]+)");
    private static final Pattern QUERY_PASS = Pattern.compile("(?i)[?&]password=([^&#\\s]+)");
    private static final Pattern API_PATH = Pattern.compile(
            "(?i)^(https?://[^/?#\\s]+)(?:/[^?#\\s]*)?/(?:get|player_api|panel_api|xmltv|playlist|enigma2)\\.php\\?\\S*");
    private static final Pattern STREAM_PATH = Pattern.compile(
            "(?i)^(https?://[^/?#\\s]+)/(?:(?:live|movie|series|timeshift)/)?([^/?#\\s]+)/([^/?#\\s]+)/(?:\\d+)(?:\\.[a-z0-9]{2,4})?(?:[?#]\\S*)?$");
    private static final Pattern STREAM_PATH_PREFIXED = Pattern.compile(
            "(?i)^(https?://[^/?#\\s]+)/(?:live|movie|series|timeshift)/([^/?#\\s]+)/([^/?#\\s]+)/[^/?#\\s]+$");
    private static final Pattern TRIPLE = Pattern.compile(
            "^\\s*((?:https?://)?[^\\s,;|]+)\\s*[\\s,;|]\\s*([^\\s,;|]+)\\s*[\\s,;|]\\s*([^\\s,;|]+)\\s*$");
    private static final Pattern LABEL_SERVER = Pattern.compile(
            "(?im)^\\s*(?:server|host|url|portal|dns|link|panel|\\u0627\\u0644\\u0633\\u064a\\u0631\\u0641\\u0631|\\u0627\\u0644\\u0631\\u0627\\u0628\\u0637)\\s*(?:url)?\\s*[:=\\uff1a]\\s*(\\S+)");
    private static final Pattern LABEL_USER = Pattern.compile(
            "(?im)^\\s*(?:user(?:name)?|login|usr|\\u0627\\u0633\\u0645 \\u0627\\u0644\\u0645\\u0633\\u062a\\u062e\\u062f\\u0645|\\u0627\\u0644\\u064a\\u0648\\u0632\\u0631)\\s*[:=\\uff1a]\\s*(\\S+)");
    private static final Pattern LABEL_PASS = Pattern.compile(
            "(?im)^\\s*(?:pass(?:word)?|pwd|\\u0643\\u0644\\u0645\\u0629 \\u0627\\u0644\\u0645\\u0631\\u0648\\u0631|\\u0627\\u0644\\u0628\\u0627\\u0633\\u0648\\u0631\\u062f)\\s*[:=\\uff1a]\\s*(\\S+)");

    public XtreamAccount(String server, String username, String password) {
        this.server = stripSlash(server);
        this.username = username;
        this.password = password;
    }

    /** Tries every known shape; returns null when the text is not an Xtream account. */
    public static XtreamAccount parse(String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim();
        if (s.isEmpty()) {
            return null;
        }

        // 1) API url with username & password in the query
        Matcher api = API_PATH.matcher(s);
        if (api.find()) {
            Matcher u = QUERY_USER.matcher(s);
            Matcher p = QUERY_PASS.matcher(s);
            if (u.find() && p.find()) {
                return new XtreamAccount(serverBase(s, api.group(1)), decode(u.group(1)), decode(p.group(1)));
            }
        }

        // 2) stream url: /live/U/P/ID.ts
        Matcher pre = STREAM_PATH_PREFIXED.matcher(s);
        if (pre.matches()) {
            return new XtreamAccount(pre.group(1), decode(pre.group(2)), decode(pre.group(3)));
        }
        Matcher bare = STREAM_PATH.matcher(s);
        if (bare.matches() && !s.toLowerCase().contains(".m3u")) {
            return new XtreamAccount(bare.group(1), decode(bare.group(2)), decode(bare.group(3)));
        }

        // 3) labelled block copied from a reseller message
        Matcher ls = LABEL_SERVER.matcher(s);
        Matcher lu = LABEL_USER.matcher(s);
        Matcher lp = LABEL_PASS.matcher(s);
        if (ls.find() && lu.find() && lp.find()) {
            String server = ls.group(1);
            if (!server.toLowerCase().startsWith("http")) {
                server = "http://" + server;
            }
            return new XtreamAccount(origin(server), lu.group(1), lp.group(1));
        }

        // 4) "host user pass" in one line
        if (s.indexOf('\n') < 0) {
            Matcher t = TRIPLE.matcher(s);
            if (t.matches() && looksLikeHost(t.group(1)) && !IptvUrls.isStreamUrl(t.group(2))) {
                String server = t.group(1);
                if (!server.toLowerCase().startsWith("http")) {
                    server = "http://" + server;
                }
                return new XtreamAccount(origin(server), t.group(2), t.group(3));
            }
        }
        return null;
    }

    private static boolean looksLikeHost(String h) {
        String x = h.toLowerCase().replaceFirst("^https?://", "");
        return x.matches("[a-z0-9.-]+(:\\d{1,5})?(/.*)?") && (x.contains(".") || x.contains(":") || x.startsWith("localhost"));
    }

    /** Keeps a sub-path when the panel lives below the root (http://host/panel/get.php?...). */
    private static String serverBase(String full, String origin) {
        String rest = full.substring(origin.length());
        int q = rest.indexOf('?');
        String path = q >= 0 ? rest.substring(0, q) : rest;
        int slash = path.lastIndexOf('/');
        String dir = slash > 0 ? path.substring(0, slash) : "";
        return origin + dir;
    }

    private static String origin(String url) {
        Matcher m = Pattern.compile("(?i)^(https?://[^/?#\\s]+)").matcher(url);
        return m.find() ? m.group(1) : url;
    }

    private static String stripSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return s;
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    private String creds() {
        return "username=" + enc(username) + "&password=" + enc(password);
    }

    /** Full channel list with categories/logos. type: m3u_plus (default) or m3u. output: ts, m3u8 or null. */
    public String m3uUrl(String type, String output) {
        String url = server + "/get.php?" + creds() + "&type=" + type;
        if (output != null && !output.isEmpty()) {
            url += "&output=" + output;
        }
        return url;
    }

    public String m3uUrl() {
        return m3uUrl("m3u_plus", "ts");
    }

    public String apiUrl(String action) {
        return server + "/player_api.php?" + creds() + (action == null ? "" : "&action=" + action);
    }

    public String epgUrl() {
        return server + "/xmltv.php?" + creds();
    }

    public String liveUrl(String streamId, String ext) {
        return server + "/live/" + enc(username) + "/" + enc(password) + "/" + streamId + "." + (ext == null ? "ts" : ext);
    }

    public String movieUrl(String streamId, String ext) {
        return server + "/movie/" + enc(username) + "/" + enc(password) + "/" + streamId + "." + (ext == null ? "mp4" : ext);
    }

    public String seriesUrl(String episodeId, String ext) {
        return server + "/series/" + enc(username) + "/" + enc(password) + "/" + episodeId + "." + (ext == null ? "mp4" : ext);
    }

    @Override
    public String toString() {
        return server + " (" + username + ")";
    }
}
