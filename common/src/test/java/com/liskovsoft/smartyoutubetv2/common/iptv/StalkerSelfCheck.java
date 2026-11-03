package com.liskovsoft.smartyoutubetv2.common.iptv;

import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plain JVM self check of the Stalker / Ministra portal support:  java StalkerSelfCheck [path/to/e-portals.txt] */
public class StalkerSelfCheck {
    static int failed = 0;
    static int passed = 0;

    static void check(boolean ok, String what) {
        if (ok) passed++; else { failed++; System.out.println("FAIL: " + what); }
    }

    // ---- mock portal -------------------------------------------------------------------------------------------
    static final String GOOD = "00:1A:79:00:00:01";
    static final String NO_CHANNELS = "00:1A:79:00:00:02";   // valid handshake, empty list (expired subscription)
    static final String BAD = "00:1A:79:00:00:03";           // handshake rejected

    static class Portal {
        boolean pagedOnly;                 // get_all_channels unsupported
        final Set<String> validTokens = new HashSet<>();
        int tokenSeq;
        int handshakes;
        int createLinks;
        final List<String> log = new ArrayList<>();

        void expireTokens() { validTokens.clear(); }
    }

    static String q(String query, String key) throws Exception {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
        }
        return "";
    }

    static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws Exception {
        byte[] b = body.getBytes(Charset.forName("UTF-8"));
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    static String channelJson(int i) {
        return "{\"id\":\"" + i + "\",\"name\":\"Channel " + i + "\",\"number\":" + i + ",\"tv_genre_id\":\"" + (i % 2 == 0 ? 10 : 20)
                + "\",\"logo\":\"" + i + ".png\",\"cmd\":\"ffmpeg http://localhost/ch/" + i + "\"}";
    }

    static HttpServer startPortal(String apiPath, Portal portal) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(apiPath, ex -> {
            try {
                String query = ex.getRequestURI().getRawQuery();
                String type = q(query, "type"), action = q(query, "action");
                String cookie = ex.getRequestHeaders().getFirst("Cookie");
                String auth = ex.getRequestHeaders().getFirst("Authorization");
                String mac = cookie == null ? "" : URLDecoder.decode(cookie.replaceAll(".*mac=([^;]*).*", "$1"), "UTF-8");
                portal.log.add(type + "/" + action + " mac=" + mac);

                if (action.equals("handshake")) {
                    portal.handshakes++;
                    if (mac.equals(BAD)) { reply(ex, 200, "{\"js\":[]}"); return; }
                    String t = "tok" + (++portal.tokenSeq);
                    portal.validTokens.add(t);
                    reply(ex, 200, "{\"js\":{\"token\":\"" + t + "\",\"random\":\"abc\"}}");
                    return;
                }
                if (auth == null || !auth.startsWith("Bearer ") || !portal.validTokens.contains(auth.substring(7))) {
                    reply(ex, 401, "Authorization failed");
                    return;
                }
                if (action.equals("get_profile")) { reply(ex, 200, "{\"js\":{\"id\":\"1\"}}"); return; }
                if (action.equals("get_genres")) {
                    reply(ex, 200, "{\"js\":[{\"id\":\"*\",\"title\":\"All\"},{\"id\":\"10\",\"title\":\"Sports\"},{\"id\":\"20\",\"title\":\"\\u0623\\u062e\\u0628\\u0627\\u0631\"}]}");
                    return;
                }
                if (action.equals("get_all_channels")) {
                    if (portal.pagedOnly) { reply(ex, 200, "{\"js\":[]}"); return; }
                    if (mac.equals(NO_CHANNELS)) { reply(ex, 200, "{\"js\":{\"data\":[]}}"); return; }
                    StringBuilder sb = new StringBuilder("{\"js\":{\"data\":[");
                    for (int i = 1; i <= 5; i++) sb.append(i > 1 ? "," : "").append(channelJson(i));
                    reply(ex, 200, sb.append("]}}").toString());
                    return;
                }
                if (action.equals("get_ordered_list")) {
                    if (mac.equals(NO_CHANNELS)) { reply(ex, 200, "{\"js\":{\"total_items\":0,\"data\":[]}}"); return; }
                    int page = Integer.parseInt(q(query, "p"));
                    // 5 channels, 2 per page
                    StringBuilder sb = new StringBuilder("{\"js\":{\"total_items\":5,\"max_page_items\":2,\"data\":[");
                    boolean first = true;
                    for (int i = (page - 1) * 2 + 1; i <= Math.min(5, page * 2); i++) {
                        sb.append(first ? "" : ",").append(channelJson(i));
                        first = false;
                    }
                    reply(ex, 200, sb.append("]}}").toString());
                    return;
                }
                if (action.equals("create_link")) {
                    portal.createLinks++;
                    String cmd = q(query, "cmd");
                    String id = cmd.substring(cmd.lastIndexOf('/') + 1);
                    if (id.equals("4")) { reply(ex, 200, "{\"js\":{\"cmd\":\"\",\"error\":\"limit\"}}"); return; }
                    reply(ex, 200, "{\"js\":{\"cmd\":\"ffmpeg http://stream.example:8080/live/" + id + ".ts?play_token=" + auth.substring(7) + "\",\"error\":\"\"}}");
                    return;
                }
                reply(ex, 404, "?");
            } catch (Exception e) {
                e.printStackTrace();
                try { reply(ex, 500, "err"); } catch (Exception ignored) { }
            }
        });
        server.start();
        return server;
    }

    public static void main(String[] args) throws Exception {
        // ---- 1. parser on the real file layout ----
        String sample = args.length > 0 ? new String(Files.readAllBytes(Paths.get(args[0])), "UTF-8") : null;
        if (sample != null) {
            List<StalkerAccount> all = StalkerAccount.parseAll(sample);
            int macs = 0;
            for (StalkerAccount a : all) macs += a.macs.size();
            check(all.size() == 8, "8 portals in e-portals.txt, got " + all.size());
            check(macs == 22, "22 MACs in e-portals.txt, got " + macs);
            check(all.get(0).portal.equals("http://net.youclik.net/c/") && all.get(0).macs.size() == 3, "first portal");
            check(all.get(7).portal.equals("http://zenlink.link/c") && all.get(7).macs.get(0).equals("00:1A:79:33:90:46"), "last portal (no trailing slash)");
        }
        List<StalkerAccount> one = StalkerAccount.parseAll("Portal: http://h.tv/c/ mac: 00:1a:79:aa:bb:cc, 00:1A:79:AA:BB:CC\nhttp://h.tv/c/\n00:1A:79:AA:BB:DD");
        check(one.size() == 1 && one.get(0).macs.size() == 2, "same portal merged, MACs normalised and de-duplicated: " + one);
        check(StalkerAccount.parseAll("http://a/b.ts\nhttp://c/d.m3u8").isEmpty(), "plain streams are not portals");
        check(StalkerAccount.parseAll("00:1A:79:AA:BB:CC").isEmpty(), "MAC without portal ignored");
        check(StalkerAccount.parseAll("{\"portal\":\"http://x.tv/c/\",\"macs\":[\"00:1A:79:AA:BB:01\",\"00:1A:79:AA:BB:02\"]}").get(0).macs.size() == 2, "json layout");

        // ---- 2. link round trip ----
        List<String> macs = new ArrayList<>();
        macs.add("00:1A:79:AA:BB:01");
        macs.add("00:1A:79:AA:BB:02");
        String cmd = "ffrt http://localhost/ch/12 ?a=b&c=d é.";
        String link = StalkerLink.build("http://h.tv/stalker_portal/c/", macs, cmd);
        StalkerLink back = StalkerLink.parse(link);
        check(back != null && back.cmd.equals(cmd) && back.portal.equals("http://h.tv/stalker_portal/c/") && back.macs.equals(macs), "stalker link round trip");
        check(IptvUrls.isStreamUrl(link) && IptvUrls.hostOf(link).equals("h.tv"), "stalker link accepted by url helpers");
        check(StalkerLink.parse("stalker://x/?c=1") == null && StalkerLink.parse("http://a/b") == null, "invalid links rejected");

        // ---- 3. import from a /c/ portal (api at /portal.php) ----
        Portal p1 = new Portal();
        HttpServer s1 = startPortal("/portal.php", p1);
        String base1 = "http://127.0.0.1:" + s1.getAddress().getPort();
        String text = base1 + "/c/\n" + BAD + "\n" + NO_CHANNELS + "\n" + GOOD + "\n\n";
        IptvImporter.Result r = new IptvImporter(new HttpIptvFetcher()).importText(text);
        check(r.playlist.size() == 5, "5 portal channels imported, got " + r.playlist.size() + " " + r.messages);
        check(r.stalkerPortals == 1, "stalkerPortals counted");
        boolean portalUrlLeaked = false;
        for (IptvChannel c : r.playlist.getChannels()) if (c.url.startsWith("http")) portalUrlLeaked = true;
        check(!portalUrlLeaked, "portal url itself is not added as a channel");
        IptvChannel c1 = r.playlist.getChannels().get(0);
        check(c1.name.equals("Channel 1") && StalkerLink.isStalker(c1.url), "channel name + stalker url");
        check(c1.group.equals("127.0.0.1 | \u0623\u062e\u0628\u0627\u0631"), "group = host | genre (arabic title decoded): " + c1.group);
        check(r.playlist.getChannels().get(1).group.endsWith("| Sports"), "second genre");
        check(c1.logo != null && c1.logo.endsWith("/stalker_portal/misc/logos/320/1.png"), "logo url built: " + c1.logo);
        StalkerLink l1 = StalkerLink.parse(c1.url);
        check(l1 != null && l1.macs.size() == 3 && l1.macs.get(0).equals(GOOD), "working MAC first, all MACs kept for fallback: " + (l1 == null ? null : l1.macs));
        boolean sawBad = false, sawEmpty = false;
        for (String m : r.messages) { if (m.contains(BAD)) sawBad = true; if (m.contains(NO_CHANNELS)) sawEmpty = true; }
        check(sawBad && sawEmpty, "failed MACs reported: " + r.messages);
        check(r.messages.stream().noneMatch(m -> m.contains("404")), "no misleading HTTP 404 note: " + r.messages);
        check(p1.log.stream().anyMatch(s -> s.startsWith("stb/get_profile")), "get_profile is called");

        // ---- 4. persistence through the m3u library ----
        IptvPlaylist reloaded = new IptvPlaylist();
        reloaded.addAll(M3uParser.parse(M3uWriter.write(r.playlist)));
        check(reloaded.size() == 5 && reloaded.getChannels().get(2).url.equals(r.playlist.getChannels().get(2).url), "library save/load keeps stalker urls");

        // ---- 5. playback resolve ----
        StalkerClient.Resolved res = StalkerClient.resolve(new HttpIptvFetcher(), c1.url, false);
        check(res.url.startsWith("http://stream.example:8080/live/1.ts?play_token=tok") && !res.url.contains("ffmpeg"), "create_link url extracted: " + res.url);
        check(res.userAgent.contains("MAG200"), "MAG user agent for the stream");
        int hs = p1.handshakes;
        StalkerClient.resolve(new HttpIptvFetcher(), r.playlist.getChannels().get(1).url, false);
        check(p1.handshakes == hs, "token cached between channels (no new handshake)");
        p1.expireTokens();
        res = StalkerClient.resolve(new HttpIptvFetcher(), r.playlist.getChannels().get(2).url, false);
        check(res.url.contains("/live/3.ts") && p1.handshakes == hs + 1, "expired token -> automatic re-handshake");
        try {
            StalkerClient.resolve(new HttpIptvFetcher(), r.playlist.getChannels().get(3).url, false);
            check(false, "portal error must throw");
        } catch (java.io.IOException e) {
            check(e.getMessage().contains("limit"), "portal error message surfaced: " + e.getMessage());
        }
        hs = p1.handshakes;
        StalkerClient.resolve(new HttpIptvFetcher(), c1.url, true);
        check(p1.handshakes == hs + 1, "fresh=true forces a new handshake");
        s1.stop(0);

        // ---- 6. paged list + /stalker_portal/server/load.php endpoint ----
        Portal p2 = new Portal();
        p2.pagedOnly = true;
        HttpServer s2 = startPortal("/stalker_portal/server/load.php", p2);
        String portal2 = "http://127.0.0.1:" + s2.getAddress().getPort() + "/stalker_portal/c/";
        r = new IptvImporter(new HttpIptvFetcher()).importText(portal2 + "\n" + GOOD);
        check(r.playlist.size() == 5, "paged channel list collected (3 pages): " + r.playlist.size() + " " + r.messages);
        s2.stop(0);

        // ---- 7. dead host aborts after the first MAC ----
        int dead;
        try (ServerSocket ss = new ServerSocket(0)) { dead = ss.getLocalPort(); }
        long t0 = System.currentTimeMillis();
        r = new IptvImporter(new HttpIptvFetcher()).importText("http://127.0.0.1:" + dead + "/c/\n" + GOOD + "\n" + BAD + "\n" + NO_CHANNELS);
        check(r.playlist.isEmpty() && r.failures == 1, "dead portal -> failure counted");
        int macNotes = 0;
        for (String m : r.messages) if (m.contains("00:1A:79:00")) macNotes++;
        check(macNotes == 1, "dead portal: other MACs not tried (" + macNotes + " MAC notes)");
        check(System.currentTimeMillis() - t0 < 5000, "dead portal fails fast");

        // ---- 8. nothing else changes ----
        r = new IptvImporter(null).importText("News,http://a.tv/1.ts\nhttp://b.tv/2.m3u8");
        check(r.playlist.size() == 2 && r.stalkerPortals == 0, "plain text list unaffected");
        r = new IptvImporter(null).importText("http://h.tv/c/\n" + GOOD);
        check(r.playlist.isEmpty() && r.messages.get(0).contains("disabled"), "no fetcher -> clear message");

        System.out.println("passed " + passed + ", failed " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
