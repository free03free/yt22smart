package com.liskovsoft.smartyoutubetv2.common.iptv;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * JVM self check of the channel check / playback link on a portal that behaves like real ones: ONE valid token per MAC
 * (a new handshake kills the old token). Run:  java StalkerCheckSelfCheck
 */
public class StalkerCheckSelfCheck {
    static int failed, passed;
    static void check(boolean ok, String what) { if (ok) passed++; else { failed++; System.out.println("FAIL: " + what); } }

    static final Map<String, String> activeToken = new ConcurrentHashMap<>();
    static final AtomicInteger seq = new AtomicInteger(), handshakes = new AtomicInteger();
    static volatile int deadStreamFrom = Integer.MAX_VALUE; // channel ids from here on have a stream answering 403

    static String q(String query, String key) throws Exception {
        for (String p : query.split("&")) { int e = p.indexOf('='); if (e > 0 && p.substring(0, e).equals(key)) return URLDecoder.decode(p.substring(e + 1), "UTF-8"); }
        return "";
    }
    static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws Exception {
        byte[] b = body.getBytes("UTF-8"); ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    public static void main(String[] args) throws Exception {
        final HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.setExecutor(Executors.newFixedThreadPool(16));
        final int port = s.getAddress().getPort();
        s.createContext("/portal.php", ex -> { try {
            String query = ex.getRequestURI().getRawQuery(); String action = q(query, "action");
            String mac = URLDecoder.decode(ex.getRequestHeaders().getFirst("Cookie").replaceAll(".*mac=([^;]*).*", "$1"), "UTF-8");
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            Thread.sleep(40);
            if (action.equals("handshake")) {
                handshakes.incrementAndGet();
                String t = "tok" + seq.incrementAndGet(); activeToken.put(mac, t); // the old token dies
                reply(ex, 200, "{\"js\":{\"token\":\"" + t + "\",\"random\":\"x\"}}"); return;
            }
            if (auth == null || !auth.equals("Bearer " + activeToken.get(mac))) { reply(ex, 401, "Authorization failed"); return; }
            if (action.equals("create_link")) {
                String id = q(query, "cmd").replaceAll("\\D", "");
                // 0.0.0.0: the client refuses localhost / 127.x links on purpose
                reply(ex, 200, "{\"js\":{\"cmd\":\"ffmpeg http://0.0.0.0:" + port + "/stream/" + id + "?play_token=abc\"}}"); return;
            }
            reply(ex, 200, "{\"js\":{}}");
        } catch (Exception e) { e.printStackTrace(); } });
        s.createContext("/stream", ex -> { try {
            int id = Integer.parseInt(ex.getRequestURI().getPath().replaceAll("\\D", ""));
            if (id >= deadStreamFrom) { reply(ex, 403, "forbidden"); return; }
            reply(ex, 200, "TSDATA-TSDATA-TSDATA");
        } catch (Exception e) { e.printStackTrace(); } });
        s.start();

        String portal = "http://127.0.0.1:" + port + "/c/";
        String mac = "00:1A:79:AA:BB:CC";
        List<IptvChannel> chans = new ArrayList<>();
        for (int i = 1; i <= 60; i++) chans.add(new IptvChannel("Ch" + i, StalkerLink.build(portal, Arrays.asList(mac), "ffmpeg http://localhost/ch/" + i)));
        IptvFetcher http = new HttpIptvFetcher(new IptvOptions());

        // 1) a healthy portal: the real background checker (4 threads) must mark every channel as working
        File f = File.createTempFile("iptv_health", ".txt");
        IptvHealth health = new IptvHealth(f);
        final CountDownLatch done = new CountDownLatch(1);
        final int[] result = new int[2];
        IptvHealthChecker.instance().start(chans, http, null, health, 4, new IptvHealthChecker.Listener() {
            public void onProgress(int d, int t, int ok, int bad) { }
            public void onFinished(int ok, int bad, boolean cancelled) { result[0] = ok; result[1] = bad; done.countDown(); }
        });
        check(done.await(60, TimeUnit.SECONDS), "checker finished");
        check(result[0] == 60 && result[1] == 0, "healthy portal: all 60 channels OK (got ok=" + result[0] + " bad=" + result[1] + ")");
        check(handshakes.get() <= 3, "one login is shared by all checks (handshakes=" + handshakes.get() + ")");

        // 2) the token dies while the session is cached (someone else logged in): the link is still obtained
        activeToken.put(mac, "other-device-token");
        IptvChecker.Result r = IptvChecker.checkChannel(http, chans.get(0), null);
        check(r != null && r.ok, "dead token: a new login is made and the channel works (" + (r == null ? "null" : r.detail) + ")");

        // 3) the portal gives a link but the stream itself is refused: must NOT be reported as working, with the reason
        deadStreamFrom = 5;
        r = IptvChecker.checkChannel(http, chans.get(9), null);
        check(r != null && !r.ok && r.detail.contains("403"), "dead stream is reported with its reason (" + (r == null ? "null" : r.ok + " " + r.detail) + ")");
        r = IptvChecker.checkChannel(http, chans.get(0), null);
        check(r != null && r.ok, "a live stream is still OK");

        // 4) playing (resolve) while a check runs: parallel resolves never break each other
        deadStreamFrom = Integer.MAX_VALUE;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> fs = new ArrayList<>();
        for (int i = 0; i < 40; i++) { final IptvChannel c = chans.get(i); final boolean fresh = i % 7 == 0;
            fs.add(pool.submit(() -> { try { return StalkerClient.resolve(http, c.url, fresh).url != null; } catch (Exception e) { System.out.println("  resolve error: " + e); return false; } })); }
        int good = 0; for (Future<Boolean> x : fs) if (x.get()) good++;
        check(good == 40, "40 parallel resolves (some with fresh login) all succeed (" + good + "/40)");
        pool.shutdown();

        System.out.println("StalkerCheckSelfCheck: " + passed + " passed, " + failed + " failed");
        s.stop(0);
        System.exit(failed == 0 ? 0 : 1);
    }
}
