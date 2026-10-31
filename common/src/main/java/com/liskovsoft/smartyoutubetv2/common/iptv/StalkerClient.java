package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Stalker / Ministra (MAG "portal") client, live TV only. Pure Java.
 *
 * Protocol: the MAC address is the login.
 *   handshake -> token,  get_profile,  get_genres + get_all_channels (channel list),
 *   create_link(cmd) -> the real, short lived stream url (done every time a channel is played).
 *
 * The API endpoint is not part of the portal url ("http://host/c/"), so the usual locations are probed
 * (/portal.php, /server/load.php, /stalker_portal/server/load.php ...) and the working one is remembered.
 */
public final class StalkerClient {
    public static final String USER_AGENT =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3";
    private static final String X_USER_AGENT = "Model: MAG250; Link: WiFi";
    private static final long SESSION_TTL_MS = 15 * 60 * 1000L;
    private static final int MAX_PAGES = 400;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Pattern STREAM = Pattern.compile("(?i)\\b(?:https?|rtmps?|rtsp|udp|rtp|mms|mmsh)://\\S+");

    private static final Map<String, String> API_CACHE = new HashMap<>();     // portal key -> api url
    private static final Map<String, Session> SESSIONS = new HashMap<>();     // portal key|mac -> session
    private static final Map<String, String> WORKING_MAC = new HashMap<>();   // portal -> last MAC that worked

    /** The endpoint is a real portal but it refused this MAC (so probing other endpoints is pointless). */
    private static final class MacRejected extends IOException {
        MacRejected(String message) {
            super(message);
        }
    }

    private StalkerClient() {
    }

    /** What the player needs to open the channel. */
    public static final class Resolved {
        public final String url;
        public final String userAgent;

        Resolved(String url, String userAgent) {
            this.url = url;
            this.userAgent = userAgent;
        }
    }

    private static final class Session {
        final String portal;
        final String api;
        final String mac;
        final String token;
        final long created = System.currentTimeMillis();

        Session(String portal, String api, String mac, String token) {
            this.portal = portal;
            this.api = api;
            this.mac = mac;
            this.token = token;
        }
    }

    // ------------------------------------------------------------------ channel list

    /**
     * Downloads the live channel list using the first MAC that the portal accepts.
     * @param messages receives one line per MAC that failed
     * @return channels (never empty), url = {@link StalkerLink}; throws when no MAC works
     */
    /** Progress hook: called before every MAC attempt. May throw to abort (cancel). */
    public interface Step {
        void onMac(int index, int total);
    }

    public static List<IptvChannel> fetchChannels(IptvFetcher http, StalkerAccount account, List<String> messages) throws IOException {
        return fetchChannels(http, account, messages, null);
    }

    public static List<IptvChannel> fetchChannels(IptvFetcher http, StalkerAccount account, List<String> messages, Step step) throws IOException {
        IOException last = null;
        int macIndex = 0;
        for (String mac : account.macs) {
            if (step != null) {
                step.onMac(++macIndex, account.macs.size());
            }
            try {
                Session s = open(http, account.portal, mac);
                StalkerAccount ordered = new StalkerAccount(account.portal);
                ordered.macs.add(mac); // the MAC that works goes first in every stored link
                for (String other : account.macs) {
                    if (!other.equals(mac)) ordered.macs.add(other);
                }
                List<IptvChannel> channels = loadChannels(http, s, ordered);
                if (!channels.isEmpty()) {
                    rememberMac(account.portal, mac);
                    return channels;
                }
                last = new IOException("no channels");
                if (messages != null) {
                    messages.add("Portal " + account.host() + ": MAC " + mac + " returned no channels");
                }
            } catch (IOException e) {
                last = e;
                if (messages != null) {
                    messages.add("Portal " + account.host() + ": MAC " + mac + " failed (" + e.getMessage() + ")");
                }
                if (isNetworkDown(e)) {
                    break; // the server itself is unreachable: other MACs will not help
                }
            }
        }
        throw last != null ? last : new IOException("no MAC address");
    }

    private static List<IptvChannel> loadChannels(IptvFetcher http, Session s, StalkerAccount account) throws IOException {
        Map<String, String> genres = new HashMap<>();
        try {
            List<Object> g = MiniJson.asList(call(http, s, "type=itv&action=get_genres"));
            if (g != null) {
                for (Object o : g) {
                    Map<String, Object> m = MiniJson.asMap(o);
                    if (m != null) {
                        genres.put(MiniJson.str(m.get("id")), MiniJson.str(m.get("title")));
                    }
                }
            }
        } catch (IOException e) {
            if (isNetworkDown(e)) {
                throw e;
            } // genres are optional: channels just end up in one group
        }

        List<Object> raw = null;
        try {
            Map<String, Object> all = MiniJson.asMap(call(http, s, "type=itv&action=get_all_channels"));
            if (all != null) {
                raw = MiniJson.asList(all.get("data"));
            }
        } catch (IOException e) {
            if (isNetworkDown(e)) {
                throw e;
            } // not supported by this portal: use the paged list below
        }
        if (raw == null || raw.isEmpty()) {
            raw = pagedChannels(http, s);
        }

        String base = apiBase(s.portal);
        String logoBase = base.endsWith("/stalker_portal") ? base + "/misc/logos/320/" : base + "/stalker_portal/misc/logos/320/";
        List<IptvChannel> out = new ArrayList<>();
        for (Object o : raw) {
            Map<String, Object> m = MiniJson.asMap(o);
            if (m == null) {
                continue;
            }
            String cmd = MiniJson.str(m.get("cmd")).trim();
            String name = MiniJson.str(m.get("name")).trim();
            if (cmd.isEmpty() || name.isEmpty()) {
                continue;
            }
            IptvChannel c = new IptvChannel(name, StalkerLink.build(account.portal, account.macs, cmd));
            String genre = genres.get(MiniJson.str(m.get("tv_genre_id")));
            c.group = account.host() + " | " + (genre == null || genre.isEmpty() ? "TV" : genre);
            String logo = MiniJson.str(m.get("logo")).trim();
            if (!logo.isEmpty()) {
                c.logo = logo.toLowerCase(Locale.ROOT).startsWith("http") ? logo : logoBase + logo;
            }
            c.kind = IptvChannel.KIND_LIVE;
            out.add(c);
        }
        return out;
    }

    /** Some portals refuse get_all_channels and only serve the paged list. */
    private static List<Object> pagedChannels(IptvFetcher http, Session s) throws IOException {
        List<Object> out = new ArrayList<>();
        long total = Long.MAX_VALUE;
        for (int page = 1; page <= MAX_PAGES && out.size() < total; page++) {
            Map<String, Object> js = MiniJson.asMap(call(http, s,
                    "type=itv&action=get_ordered_list&genre=0&force_ch_link_check=&fav=0&sortby=number&hd=0&p=" + page));
            List<Object> data = js == null ? null : MiniJson.asList(js.get("data"));
            if (data == null || data.isEmpty()) {
                break;
            }
            out.addAll(data);
            total = MiniJson.num(js.get("total_items"), Long.MAX_VALUE);
        }
        return out;
    }

    // ------------------------------------------------------------------ playback

    /**
     * Turns a stored {@link StalkerLink} url into a playable stream url. Tries the MACs in order, re-doing the
     * handshake once when a cached token has expired.
     * @param fresh ignore cached sessions (use after a playback error)
     */
    public static Resolved resolve(IptvFetcher http, String stalkerUrl, boolean fresh) throws IOException {
        StalkerLink link = StalkerLink.parse(stalkerUrl);
        if (link == null) {
            throw new IOException("Invalid portal channel");
        }
        List<String> order = new ArrayList<>(link.macs);
        String good = workingMac(link.portal);
        if (good != null && order.remove(good)) {
            order.add(0, good);
        }
        IOException last = null;
        for (String mac : order) {
            try {
                Resolved r = resolveWith(http, link, mac, fresh);
                rememberMac(link.portal, mac);
                return r;
            } catch (IOException e) {
                last = e;
                if (isNetworkDown(e)) {
                    break;
                }
            }
        }
        throw last != null ? last : new IOException("no MAC address");
    }

    private static Resolved resolveWith(IptvFetcher http, StalkerLink link, String mac, boolean fresh) throws IOException {
        String key = link.portal + "|" + mac;
        Session s = fresh ? null : cached(key);
        boolean wasCached = s != null;
        if (s == null) {
            s = open(http, link.portal, mac);
        }
        try {
            return createLink(http, s, link.cmd);
        } catch (IOException e) {
            if (!wasCached || isNetworkDown(e)) {
                throw e;
            }
            // cached token probably expired: new handshake, one more try
            return createLink(http, open(http, link.portal, mac), link.cmd);
        }
    }

    private static Resolved createLink(IptvFetcher http, Session s, String cmd) throws IOException {
        Object js = call(http, s, "type=itv&action=create_link&cmd=" + enc(cmd)
                + "&series=&forced_storage=undefined&disable_ad=0&download=0&force_ch_link_check=0");
        Map<String, Object> m = MiniJson.asMap(js);
        String error = m == null ? "" : MiniJson.str(m.get("error"));
        String link = m == null ? "" : MiniJson.str(m.get("cmd"));
        Matcher u = STREAM.matcher(link);
        String url = u.find() ? u.group() : null;
        if (url == null || hostIsLocal(url)) {
            // some portals already keep a ready url in the channel cmd
            Matcher d = STREAM.matcher(cmd);
            String direct = d.find() ? d.group() : null;
            if (url == null && direct != null && !hostIsLocal(direct)) {
                return new Resolved(direct, USER_AGENT);
            }
            throw new IOException(error.isEmpty() ? "portal gave no stream link" : "portal: " + error);
        }
        return new Resolved(url, USER_AGENT);
    }

    private static boolean hostIsLocal(String url) {
        String h = IptvUrls.hostOf(url).toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.startsWith("127.") || h.isEmpty();
    }

    // ------------------------------------------------------------------ session / handshake

    private static synchronized String workingMac(String portal) {
        return WORKING_MAC.get(portal);
    }

    private static synchronized void rememberMac(String portal, String mac) {
        WORKING_MAC.put(portal, mac);
    }

    private static synchronized Session cached(String key) {
        Session s = SESSIONS.get(key);
        if (s != null && System.currentTimeMillis() - s.created > SESSION_TTL_MS) {
            SESSIONS.remove(key);
            return null;
        }
        return s;
    }

    private static Session open(IptvFetcher http, String portal, String mac) throws IOException {
        String key = portal + "|" + mac;
        List<String> candidates = new ArrayList<>();
        String known;
        synchronized (StalkerClient.class) {
            known = API_CACHE.get(portal);
        }
        if (known != null) {
            candidates.add(known);
        }
        for (String c : apiCandidates(portal)) {
            if (!candidates.contains(c)) {
                candidates.add(c);
            }
        }

        IOException last = null;
        for (String api : candidates) {
            try {
                Session s = handshake(http, portal, api, mac);
                synchronized (StalkerClient.class) {
                    API_CACHE.put(portal, api);
                    SESSIONS.put(key, s);
                }
                return s;
            } catch (IOException e) {
                if (isNetworkDown(e) || e instanceof MacRejected) {
                    throw e; // dead server / right endpoint but wrong MAC: other endpoints will not help
                }
                if (last == null) {
                    last = e;
                }
            }
        }
        throw last != null ? last : new IOException("no portal endpoint");
    }

    private static Session handshake(IptvFetcher http, String portal, String api, String mac) throws IOException {
        Session probe = new Session(portal, api, mac, null);
        Map<String, Object> js = MiniJson.asMap(call(http, probe, "type=stb&action=handshake&token=&prehash=0"));
        String token = js == null ? "" : MiniJson.str(js.get("token"));
        if (token.isEmpty()) {
            throw new MacRejected("MAC rejected by the portal");
        }
        Session s = new Session(portal, api, mac, token);

        try { // not every portal needs it, a failure here is not fatal
            String sn = hex(digest("MD5", mac), 13);
            String id = hex(digest("SHA-256", mac), 64);
            String random = js.get("random") == null ? "" : MiniJson.str(js.get("random"));
            String metrics = "{\"mac\":\"" + mac + "\",\"sn\":\"" + sn + "\",\"type\":\"STB\",\"model\":\"MAG250\",\"uid\":\"\",\"random\":\"" + random + "\"}";
            call(http, s, "type=stb&action=get_profile&hd=1"
                    + "&ver=" + enc("ImageDescription: 0.2.18-r14-pub-250; ImageDate: Fri Jan 15 15:20:44 EET 2016; PORTAL version: 5.6.1; API Version: JS API version: 328; STB API version: 134; Player Engine version: 0x566")
                    + "&num_banks=2&sn=" + sn + "&stb_type=MAG250&client_type=STB&image_version=218&video_out=hdmi"
                    + "&device_id=" + id + "&device_id2=" + id + "&signature=&auth_second_step=1&hw_version=1.7-BD-00"
                    + "&not_valid_token=0&metrics=" + enc(metrics) + "&hw_version_2=" + hex(digest("SHA-1", mac), 40)
                    + "&timestamp=" + (System.currentTimeMillis() / 1000) + "&api_signature=262&prehash=0");
        } catch (IOException e) {
            if (isNetworkDown(e)) {
                throw e;
            }
        }
        return s;
    }

    /** One API call; returns the "js" member of the answer. */
    private static Object call(IptvFetcher http, Session s, String query) throws IOException {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", USER_AGENT);
        h.put("X-User-Agent", X_USER_AGENT);
        h.put("Accept", "*/*");
        h.put("Referer", s.portal);
        h.put("Cookie", "mac=" + enc(s.mac) + "; stb_lang=en; timezone=GMT");
        if (s.token != null) {
            h.put("Authorization", "Bearer " + s.token);
        }
        byte[] data = http.fetch(s.api + "?" + query + "&JsHttpRequest=1-xml", h);
        Map<String, Object> root = MiniJson.asMap(MiniJson.parse(new String(data, UTF8)));
        if (root == null || !root.containsKey("js")) {
            throw new IOException("not a portal answer");
        }
        return root.get("js");
    }

    // ------------------------------------------------------------------ helpers

    /** "http://host/c/" -> "http://host", "http://host/stalker_portal/c/" -> "http://host/stalker_portal". */
    static String apiBase(String portal) {
        String p = portal;
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.toLowerCase(Locale.ROOT).endsWith("/c")) {
            p = p.substring(0, p.length() - 2);
        }
        return p;
    }

    static List<String> apiCandidates(String portal) {
        List<String> out = new ArrayList<>();
        String lower = portal.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".php")) {
            out.add(portal); // the api url itself was given
            return out;
        }
        String base = apiBase(portal);
        out.add(base + "/portal.php");
        out.add(base + "/server/load.php");
        if (!base.toLowerCase(Locale.ROOT).endsWith("/stalker_portal")) {
            out.add(base + "/stalker_portal/server/load.php");
        }
        out.add(base + "/load.php");
        return out;
    }

    static boolean isNetworkDown(IOException e) {
        return e instanceof ConnectException || e instanceof SocketTimeoutException || e instanceof UnknownHostException;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] digest(String algorithm, String text) {
        try {
            return MessageDigest.getInstance(algorithm).digest(text.getBytes(UTF8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes, int length) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.length() > length ? sb.substring(0, length) : sb.toString();
    }
}
