package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.List;

/** JVM self check: a page captured from a PDF viewer (scrambled glyph lines, wrapped urls, several accounts). */
public class IptvTextCleanSelfCheck {
    static int passed, failed;
    static void check(boolean ok, String what) { if (ok) passed++; else { failed++; System.out.println("FAIL: " + what); } }

    public static void main(String[] a) {
        String page = "Xtream Code Iptv | PDF\n"
                + "\u0627\uF8FE\n\u0646\uF8FE\n\uE000\n\uE001\n"
                + "https://www.example-site.com\nhttps://iptv.example-site.com\nhttps://t.me/somechannel\n"
                + "======\n\uE015\n"
                + "Host: http://srv-one.example:80\nUsername: demo.user\nPassword: pass111\nm3u8\n"
                + "http://srv-one.example:80/get.php?\nusername=demo.user&password=pass111&type=m3u&output=ts\n"
                + "\uE019\n"
                + "Host: http://srv-two.example:80\nPassword: pass222\n"
                + "http://srv-two.example:80/get.php?\nusername=demo.user&password=pass222&type=m3u&output=ts\n"
                + "Host: http://srv-three.example:8080\nPassword: pass333\n"
                + "TV VIP Fire\nShare this document\n";

        String cleaned = IptvTextCleaner.clean(page);
        check(!cleaned.contains("\uF8FE") && !cleaned.contains("\uE000"), "private use glyphs removed");
        check(cleaned.contains("get.php?username=demo.user&password=pass111&type=m3u&output=ts"), "wrapped url joined");
        check(cleaned.contains("srv-two.example:80/get.php?username=demo.user&password=pass222"), "second wrapped url joined");

        List<XtreamAccount> accounts = XtreamAccount.parseAll(page);
        check(accounts.size() == 3, "three accounts, got " + accounts.size());
        if (accounts.size() == 3) {
            check(accounts.get(0).server.equals("http://srv-one.example:80") && accounts.get(0).password.equals("pass111"), "account 1");
            check(accounts.get(1).server.equals("http://srv-two.example:80") && accounts.get(1).username.equals("demo.user"), "account 2");
            check(accounts.get(2).server.equals("http://srv-three.example:8080") && accounts.get(2).username.equals("demo.user")
                    && accounts.get(2).password.equals("pass333"), "account 3: user taken from the previous block");
        }

        // site links are not channels, the wrapped get.php urls are
        List<IptvChannel> parsed = TextListParser.parse(cleaned);
        for (IptvChannel c : parsed) {
            check(!IptvUrls.isSiteLink(c.url), "site link kept as a channel: " + c.url);
        }
        check(IptvUrls.isSiteLink("https://www.example-site.com") && IptvUrls.isSiteLink("http://t.me/x/1"), "site links detected");
        check(!IptvUrls.isSiteLink("http://192.168.1.5:8080") && !IptvUrls.isSiteLink("http://host.example:8080/live/1.ts")
                && !IptvUrls.isSiteLink("http://host.example/stream.m3u8"), "streams are not site links");

        // a chatty page: only the iptv parts become channels, the other text is ignored
        String noisy = "Welcome to my blog, here are the newest lists for this week!\nHost: http://x.example:80\nm3u8\n"
                + "http://a.example/live/1.m3u8\nBBC One\nhttp://b.example/bbc.m3u8\n"
                + "https://blog.example.com/2021/post.html\nhttps://www.scribd.com/document/1/x\nPassword: abc\n"
                + "Sport HD,http://c.example/c.ts\nhttp://d.example/pic.jpg\nJoin our telegram channel\nhttps://t.me/x\n";
        List<IptvChannel> ch = TextListParser.parse(IptvTextCleaner.clean(noisy));
        check(ch.size() == 3, "three channels expected, got " + ch.size());
        if (ch.size() == 3) {
            check(!ch.get(0).name.toLowerCase().contains("m3u8") && !ch.get(0).name.contains("Host"), "no keyword/label as a name: " + ch.get(0).name);
            check("BBC One".equals(ch.get(1).name), "title right before the url: " + ch.get(1).name);
            check("Sport HD".equals(ch.get(2).name), "Name,url: " + ch.get(2).name);
        }
        // a list of names and urls keeps working
        check(TextListParser.parse("Channel A\nhttp://x.example/a.m3u8\nChannel B\nhttp://x.example/b.m3u8\n").size() == 2, "title/url pairs");

        // plain text without anything special is untouched
        check(IptvTextCleaner.clean("News,http://a.example/live.m3u8\n").equals("News,http://a.example/live.m3u8\n"), "plain list unchanged");
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
