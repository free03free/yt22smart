package com.liskovsoft.smartyoutubetv2.common.iptv;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Plain JVM self check (no JUnit needed):  java IptvEngineSelfCheck */
public class IptvEngineSelfCheck {
    static int failed = 0;
    static int passed = 0;

    static void check(boolean ok, String what) {
        if (ok) passed++; else { failed++; System.out.println("FAIL: " + what); }
    }

    static byte[] u8(String s) { return s.getBytes(Charset.forName("UTF-8")); }

    static byte[] zip(Object... nameData) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bo);
        for (int i = 0; i < nameData.length; i += 2) {
            z.putNextEntry(new ZipEntry((String) nameData[i]));
            z.write((byte[]) nameData[i + 1]);
            z.closeEntry();
        }
        z.close();
        return bo.toByteArray();
    }

    static byte[] gz(byte[] d) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        GZIPOutputStream g = new GZIPOutputStream(bo);
        g.write(d);
        g.close();
        return bo.toByteArray();
    }

    static byte[] tar(String name, byte[] body) {
        byte[] h = new byte[512];
        byte[] n = u8(name);
        System.arraycopy(n, 0, h, 0, n.length);
        byte[] size = u8(String.format("%011o", body.length));
        System.arraycopy(size, 0, h, 124, size.length);
        h[156] = '0';
        byte[] ustar = u8("ustar");
        System.arraycopy(ustar, 0, h, 257, 5);
        int padded = ((body.length + 511) / 512) * 512;
        byte[] out = new byte[512 + padded + 1024];
        System.arraycopy(h, 0, out, 0, 512);
        System.arraycopy(body, 0, out, 512, body.length);
        return out;
    }

    static IptvChannel find(IptvPlaylist p, String name) {
        for (IptvChannel c : p.getChannels()) if (name.equals(c.name)) return c;
        return null;
    }

    public static void main(String[] args) throws Exception {
        // ---- M3U
        String m3u = "\uFEFF#EXTM3U\r\n"
                + "#EXTINF:-1 tvg-id=\"bbc.uk\" tvg-logo=\"http://l/x.png\" group-title=\"News, World\",BBC One, HD\r\n"
                + "#EXTVLCOPT:http-user-agent=MyUA\r\n"
                + "http://h/bbc.m3u8\r\n"
                + "#EXTINF:-1,قناة الجزيرة\n#EXTGRP:أخبار\nhttp://h/aj.ts|User-Agent=Foo&Referer=http://r\n"
                + "http://h/noinfo/stream.m3u8\n";
        List<IptvChannel> l = M3uParser.parse(m3u);
        check(l.size() == 3, "m3u count " + l.size());
        check("BBC One, HD".equals(l.get(0).name), "name keeps comma: " + l.get(0).name);
        check("News, World".equals(l.get(0).group), "quoted group with comma");
        check("MyUA".equals(l.get(0).userAgent), "vlcopt UA");
        check("أخبار".equals(l.get(1).group) && "Foo".equals(l.get(1).userAgent) && "http://r".equals(l.get(1).referrer)
                && "http://h/aj.ts".equals(l.get(1).url), "extgrp + pipe headers");
        check("stream".equals(l.get(2).name), "fallback name " + l.get(2).name);

        // ---- TXT
        String txt = "News,#genre#\nCNN,http://h/cnn.m3u8\nBBC,http://h/a.m3u8#http://h/b.m3u8\n"
                + "Sports,#genre#\nBein$http://h/bein.ts\nTitle Only\nhttp://h/titled.m3u8\n"
                + "see this: http://h/free.ts, and (rtmp://h/live/x) ok\n";
        l = TextListParser.parse(txt);
        check(l.size() == 7, "txt count " + l.size() + " " + l);
        check("News".equals(find(toPl(l), "CNN").group), "txt genre group");
        check("Sports".equals(find(toPl(l), "Bein").group), "dollar separator");
        check(find(toPl(l), "Title Only") != null, "title on previous line");

        // ---- PLS / XSPF / JSON
        check(OtherFormatsParser.parsePls("[playlist]\nFile1=http://h/1\nTitle1=One\nFile2=http://h/2\nNumberOfEntries=2").size() == 2, "pls");
        check(OtherFormatsParser.parseXspf("<playlist><trackList><track><location>http://h/x?a=1&amp;b=2</location><title>X</title></track></trackList></playlist>")
                .get(0).url.equals("http://h/x?a=1&b=2"), "xspf");
        List<IptvChannel> js = OtherFormatsParser.parseJson("[{\"name\":\"J1\",\"url\":\"http:\\/\\/h\\/j1.m3u8\"},{\"stream_url\":\"http://h/j2\",\"title\":\"J2\"}]");
        check(js.size() == 2 && "J1".equals(js.get(0).name) && "http://h/j1.m3u8".equals(js.get(0).url) && "J2".equals(js.get(1).name), "json " + js);

        // ---- Arabic charset
        byte[] cp1256 = "#EXTM3U\n#EXTINF:-1,قناة النيل\nhttp://h/nile.ts\n".getBytes(Charset.forName("windows-1256"));
        IptvImporter.Result r = new IptvImporter(null).importBytes(cp1256, "ar.m3u");
        check(r.playlist.size() == 1 && "قناة النيل".equals(r.playlist.getChannels().get(0).name), "windows-1256");

        // ---- archives
        byte[] inner = zip("sports.m3u", u8("#EXTM3U\n#EXTINF:-1,S1\nhttp://h/s1.ts\n"));
        byte[] pack = zip(
                "iptv/Arabic.m3u", u8("#EXTM3U\n#EXTINF:-1,A1\nhttp://h/a1.ts\n#EXTINF:-1 group-title=\"Own\",A2\nhttp://h/a2.ts\n"),
                "iptv/links.txt", u8("Kids,#genre#\nK1,http://h/k1.ts\n"),
                "iptv/logo.png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 0},
                "__MACOSX/iptv/._Arabic.m3u", new byte[]{0, 1, 2},
                "iptv/epg.xml", u8("<?xml version=\"1.0\"?><tv><channel id=\"a\"/></tv>"),
                "nested.zip", inner,
                "README.txt", u8("see http://example.com/readme"));
        r = new IptvImporter(null).importBytes(pack, "pack.zip");
        check(r.playlist.size() == 4, "zip count " + r.playlist.size() + " " + r.playlist.getChannels());
        check("Arabic".equals(find(r.playlist, "A1").group), "zip group hint from file name");
        check("Own".equals(find(r.playlist, "A2").group), "own group wins");
        check("Kids".equals(find(r.playlist, "K1").group), "txt group wins");
        check("sports".equals(find(r.playlist, "S1").group), "nested zip");

        r = new IptvImporter(null).importBytes(gz(u8("#EXTM3U\n#EXTINF:-1,G1\nhttp://h/g1.ts\n")), "list.m3u.gz");
        check(r.playlist.size() == 1, "gz");
        r = new IptvImporter(null).importBytes(gz(tar("a/list.m3u", u8("#EXTM3U\n#EXTINF:-1,T1\nhttp://h/t1.ts\n"))), "pack.tar.gz");
        check(r.playlist.size() == 1 && "T1".equals(r.playlist.getChannels().get(0).name), "tar.gz");
        r = new IptvImporter(null).importBytes(new byte[]{0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C, 0, 4}, "x.7z");
        check(r.playlist.isEmpty() && r.failures == 1, "7z reported");

        // ---- Xtream parsing
        XtreamAccount a = XtreamAccount.parse("http://srv.tv:8080/get.php?username=us&password=p%40ss&type=m3u_plus&output=ts");
        check(a != null && "http://srv.tv:8080".equals(a.server) && "us".equals(a.username) && "p@ss".equals(a.password), "xtream get.php " + a);
        a = XtreamAccount.parse("http://srv.tv/player_api.php?username=u&password=p&action=get_live_streams");
        check(a != null && "u".equals(a.username), "xtream api");
        a = XtreamAccount.parse("http://srv.tv:80/live/u1/p1/123.ts");
        check(a != null && "u1".equals(a.username) && "p1".equals(a.password), "xtream stream url");
        a = XtreamAccount.parse("Server: srv.tv:8080\nUsername: bob\nPassword: pw1\nExpires: soon");
        check(a != null && "http://srv.tv:8080".equals(a.server) && "bob".equals(a.username), "xtream labelled block");
        a = XtreamAccount.parse("السيرفر: http://srv.tv:8080\nاسم المستخدم: bob\nكلمة المرور: pw1");
        check(a != null && "pw1".equals(a.password), "xtream arabic labels");
        a = XtreamAccount.parse("srv.tv:8080|bob|pw1");
        check(a != null && "http://srv.tv:8080".equals(a.server), "xtream pipe triple");
        check(XtreamAccount.parse("http://h/list.m3u") == null && XtreamAccount.parse("hello world") == null, "no false xtream");
        check(a.liveUrl("5", "m3u8").equals("http://srv.tv:8080/live/bob/pw1/5.m3u8"), "live url builder");

        // ---- network: local server
        final Map<String, byte[]> files = new HashMap<>();
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final int port = srv.getAddress().getPort();
        final String base = "http://127.0.0.1:" + port;
        files.put("/list.m3u", u8("#EXTM3U\n#EXTINF:-1,N1\nhttp://h/n1.ts\n"));
        files.put("/pack.zip", pack);
        files.put("/hls.m3u8", u8("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts\n"));
        files.put("/chlist.m3u8", u8("#EXTM3U\n#EXTINF:-1,M8\nhttp://h/m8.ts\n"));
        files.put("/links.txt", u8("Mirror," + base + "/list.m3u\n" + base + "/pack.zip\n"));
        files.put("/get.php", u8("#EXTM3U\n#EXTINF:-1 group-title=\"X\",XT1\nhttp://127.0.0.1/live/u/p/1.ts\n"));
        srv.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            byte[] body = files.get(path);
            if (body == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            if (path.equals("/redir")) { }
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        srv.createContext("/redir", ex -> {
            ex.getResponseHeaders().add("Location", "/list.m3u");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        srv.start();
        try {
            IptvImporter imp = new IptvImporter(new HttpIptvFetcher());
            r = imp.importText(base + "/list.m3u");
            check(r.playlist.size() == 1 && r.listsDownloaded == 1, "remote m3u " + r.messages);
            check(java.util.Arrays.equals(new HttpIptvFetcher().fetch(base + "/redir"), files.get("/list.m3u")), "http redirect followed");
            r = imp.importText(base + "/pack.zip");
            check(r.playlist.size() == 4, "remote zip " + r.playlist.size() + r.messages);
            r = imp.importText(base + "/hls.m3u8");
            check(r.playlist.size() == 1 && r.playlist.getChannels().get(0).url.endsWith("/hls.m3u8"), "m3u8 stream kept as channel");
            r = imp.importText(base + "/chlist.m3u8");
            check(r.playlist.size() == 1 && "M8".equals(r.playlist.getChannels().get(0).name), "m3u8 channel list expanded");
            r = imp.importText(u8s(files.get("/links.txt")));
            check(r.playlist.size() == 5, "links txt -> list + zip: " + r.playlist.size() + r.messages);
            r = imp.importText("Server: " + base + "\nUsername: u\nPassword: p\n");
            check(r.playlist.size() == 1 && r.accounts.size() == 1, "xtream block auto fetch " + r.messages);
            r = imp.importText(base + "/get.php?username=u&password=p&type=m3u_plus&output=ts");
            check(r.playlist.size() == 1 && "X".equals(r.playlist.getChannels().get(0).group), "xtream get.php link");
            r = imp.importText(base + "/missing.m3u");
            check(r.playlist.isEmpty() && r.failures == 1, "404 reported, no crash");
            // loop safety: list that references itself
            files.put("/loop.txt", u8(base + "/loop.txt\nhttp://h/after.ts\n"));
            r = imp.importText(base + "/loop.txt");
            check(r.playlist.size() == 1, "self reference does not loop " + r.playlist.size());
        } finally {
            srv.stop(0);
        }

        // ---- no limits by default / limits are optional settings
        final Map<String, byte[]> big = new HashMap<>();
        HttpServer srv2 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String base2 = "http://127.0.0.1:" + srv2.getAddress().getPort();
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            big.put("/l" + i + ".m3u", u8("#EXTM3U\n#EXTINF:-1,C" + i + "\nhttp://h/c" + i + ".ts\n"));
            many.append(base2).append("/l").append(i).append(".m3u\n");
        }
        for (int i = 1; i <= 8; i++) {
            big.put("/a" + i + ".txt", i < 8 ? u8(base2 + "/a" + (i + 1) + ".txt\n") : u8("Deep,http://h/deep.ts\n"));
        }
        srv2.createContext("/", ex -> {
            byte[] body = big.get(ex.getRequestURI().getPath());
            if (body == null) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        srv2.start();
        try {
            r = new IptvImporter(new HttpIptvFetcher()).importText(many.toString());
            check(r.playlist.size() == 60, "60 downloads with default (unlimited) options: " + r.playlist.size());
            IptvOptions lim = new IptvOptions();
            lim.maxFetches = 10;
            r = new IptvImporter(new HttpIptvFetcher(lim), lim, null).importText(many.toString());
            check(r.playlist.size() == 10, "maxFetches=10 honoured: " + r.playlist.size());
            r = new IptvImporter(new HttpIptvFetcher()).importText(base2 + "/a1.txt");
            check(r.playlist.size() == 1 && "Deep".equals(r.playlist.getChannels().get(0).name), "8 levels deep with unlimited depth " + r.messages);
            IptvOptions d3 = new IptvOptions();
            d3.maxLinkDepth = 3;
            r = new IptvImporter(new HttpIptvFetcher(d3), d3, null).importText(base2 + "/a1.txt");
            check(r.playlist.isEmpty() || !"Deep".equals(r.playlist.getChannels().get(0).name), "maxLinkDepth=3 honoured");
            IptvOptions off = new IptvOptions();
            off.followRemoteLinks = false;
            r = new IptvImporter(new HttpIptvFetcher(off), off, null).importText(base2 + "/l1.m3u");
            check(r.listsDownloaded == 0, "followRemoteLinks=false");
        } finally {
            srv2.stop(0);
        }

        // ---- extra archive formats: sniffing + plug-in handler
        check(IptvFormat.detect(new byte[]{'R', 'a', 'r', '!', 0x1A, 7, 0, 0}, "a.rar") == IptvFormat.RAR, "sniff rar");
        check(IptvFormat.detect(new byte[]{0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C, 0, 4}, "a") == IptvFormat.SEVENZ, "sniff 7z");
        check(IptvFormat.detect(new byte[]{(byte) 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0, 0, 0}, "a") == IptvFormat.XZ, "sniff xz");
        check(IptvFormat.detect(new byte[]{'B', 'Z', 'h', '9', 0x31, 0x41, 0x59, 0x26, 0x53, 0x59}, "a") == IptvFormat.BZIP2, "sniff bzip2");
        check(IptvFormat.detect(new byte[]{0x28, (byte) 0xB5, 0x2F, (byte) 0xFD, 0, 0}, "a") == IptvFormat.ZSTD, "sniff zstd");
        check(IptvFormat.detect(u8("!<arch>\nfoo"), "a") == IptvFormat.AR, "sniff ar");

        byte[] fake7z = new byte[]{0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C, 0, 4};
        r = new IptvImporter(null).importBytes(fake7z, "x.7z");
        check(r.playlist.isEmpty() && r.failures == 1 && !r.messages.isEmpty(), "no handler -> clear message");
        final List<String> seenPasswords = new java.util.ArrayList<>();
        ExternalArchiveHandler fake = new ExternalArchiveHandler() {
            public boolean supports(IptvFormat f) { return f == IptvFormat.SEVENZ || f == IptvFormat.RAR || f == IptvFormat.BZIP2; }
            public List<IptvArchiveReader.Entry> extract(byte[] d, String n, IptvFormat f, IptvOptions o) throws java.io.IOException {
                seenPasswords.addAll(o.archivePasswords);
                List<IptvArchiveReader.Entry> l = new java.util.ArrayList<>();
                if (f == IptvFormat.BZIP2) {
                    l.add(new IptvArchiveReader.Entry("inner.m3u", u8("#EXTM3U\n#EXTINF:-1,BZ\nhttp://h/bz.ts\n")));
                } else {
                    l.add(new IptvArchiveReader.Entry("Pack/ar.m3u", u8("#EXTM3U\n#EXTINF:-1,FromPack\nhttp://h/p1.ts\n")));
                    l.add(new IptvArchiveReader.Entry("Pack/more.txt", u8("Two,http://h/p2.ts\n")));
                    l.add(new IptvArchiveReader.Entry("Pack/cover.jpg", new byte[]{1, 2, 3}));
                    try {
                        l.add(new IptvArchiveReader.Entry("Pack/inner.zip", zip("deep.m3u", u8("#EXTM3U\n#EXTINF:-1,InZip\nhttp://h/z.ts\n"))));
                    } catch (Exception e) { throw new java.io.IOException(e.getMessage()); }
                }
                return l;
            }
        };
        IptvOptions pw = new IptvOptions();
        pw.archivePasswords.add("secret");
        r = new IptvImporter(null, pw, fake).importBytes(fake7z, "x.7z");
        check(r.playlist.size() == 3 && find(r.playlist, "InZip") != null, "plug-in 7z (+ nested zip) " + r.playlist.getChannels());
        check(seenPasswords.contains("secret"), "passwords reach the handler");
        byte[] fakeRar = new byte[]{'R', 'a', 'r', '!', 0x1A, 7, 0, 0};
        r = new IptvImporter(null, pw, fake).importBytes(zip("deep/pack.rar", fakeRar), "outer.zip");
        check(r.playlist.size() == 3, "rar inside zip " + r.playlist.size());
        byte[] fakeBz = new byte[]{'B', 'Z', 'h', '9', 0x31, 0x41, 0x59, 0x26, 0x53, 0x59};
        r = new IptvImporter(null, pw, fake).importBytes(fakeBz, "list.m3u.bz2");
        check(r.playlist.size() == 1 && "BZ".equals(r.playlist.getChannels().get(0).name), "bzip2 single stream");
        IptvOptions capEntries = new IptvOptions();
        capEntries.maxArchiveEntries = 1;
        r = new IptvImporter(null, capEntries, null).importBytes(pack, "pack.zip");
        check(r.playlist.size() < 4, "maxArchiveEntries honoured");

        // ---- writer round trip
        IptvPlaylist p = new IptvPlaylist();
        p.addAll(M3uParser.parse(m3u));
        IptvPlaylist p2 = new IptvPlaylist();
        p2.addAll(M3uParser.parse(M3uWriter.write(p)));
        check(p2.size() == 3 && "News, World".equals(p2.getChannels().get(0).group) && "Foo".equals(p2.getChannels().get(1).userAgent), "writer round trip");

        System.out.println("passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    static String u8s(byte[] b) { return new String(b, Charset.forName("UTF-8")); }

    static IptvPlaylist toPl(List<IptvChannel> l) {
        IptvPlaylist p = new IptvPlaylist();
        p.addAll(l);
        return p;
    }
}
