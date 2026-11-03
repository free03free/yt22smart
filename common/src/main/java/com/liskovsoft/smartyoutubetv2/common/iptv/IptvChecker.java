package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Tests whether the server behind a channel still answers (and, for Xtream accounts, when it expires). Pure Java. */
public final class IptvChecker {
    public static final class Result {
        public final boolean ok;
        public final String detail;

        Result(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail == null ? "" : detail;
        }
    }

    private IptvChecker() {
    }

    public static Result check(IptvFetcher http, IptvChannel c, String userAgent) {
        try {
            if (StalkerLink.isStalker(c.url)) {
                return checkPortal(http, c.url);
            }
            XtreamAccount acc = IptvRefresh.xtreamOf(c.url);
            if (acc != null) {
                Result info = xtreamInfo(http, acc);
                if (info != null) {
                    return info;
                }
            }
            return probe(c.url, userAgent);
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            String m = e.getMessage();
            return new Result(false, m == null ? e.getClass().getSimpleName() : m);
        }
    }

    /**
     * Does this one channel play? Portal channels: the stream link can be created. Web streams: the server answers with data.
     * @return null for links that cannot be tested from here (rtmp, udp ...)
     */
    public static Result checkChannel(IptvFetcher http, IptvChannel c, String userAgent) {
        try {
            if (StalkerLink.isStalker(c.url)) {
                return checkPortal(http, c.url);
            }
            if (c.url == null || !c.url.toLowerCase(Locale.ROOT).startsWith("http")) {
                return null;
            }
            return probe(c.url, userAgent);
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            String m = e.getMessage();
            return new Result(false, m == null ? e.getClass().getSimpleName() : m);
        }
    }

    /**
     * A portal channel works only when the portal gives a link AND that link really delivers data. Asking for the link
     * alone says nothing: expired / blocked MACs and dead streams still get a link.
     */
    private static Result checkPortal(IptvFetcher http, String url) throws IOException {
        StalkerClient.Resolved r = StalkerClient.resolve(http, url, false);
        if (r == null || r.url == null || r.url.isEmpty()) {
            return new Result(false, "no link");
        }
        if (!r.url.toLowerCase(Locale.ROOT).startsWith("http")) {
            return new Result(true, ""); // udp / rtmp ...: cannot be tested from here, the link at least exists
        }
        return probe(r.url, r.userAgent);
    }

    /** Expiry date / status from player_api.php; null when the server does not answer in that format. */
    private static Result xtreamInfo(IptvFetcher http, XtreamAccount acc) {
        try {
            String url = acc.server + "/player_api.php?username=" + enc(acc.username) + "&password=" + enc(acc.password);
            Object json = MiniJson.parse(new String(http.fetch(url), "UTF-8"));
            Map<String, Object> root = MiniJson.asMap(json);
            Map<String, Object> user = root == null ? null : MiniJson.asMap(root.get("user_info"));
            if (user == null) {
                return null;
            }
            String status = MiniJson.str(user.get("status"));
            long exp = MiniJson.num(user.get("exp_date"), 0);
            boolean active = status.isEmpty() || status.equalsIgnoreCase("Active");
            boolean expired = exp > 0 && exp * 1000L < System.currentTimeMillis();
            String until = exp > 0 ? date(exp * 1000L) : "\u221E";
            if (!active || expired) {
                return new Result(false, expired ? "expired " + until : status);
            }
            return new Result(true, until);
        } catch (Exception e) {
            return null;
        }
    }

    static Result probe(String url, String userAgent) throws IOException {
        if (url == null || !url.toLowerCase(Locale.ROOT).startsWith("http")) {
            return new Result(false, "not a web link");
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(8_000);
            c.setReadTimeout(8_000);
            c.setRequestProperty("User-Agent", userAgent == null || userAgent.isEmpty() ? HttpIptvFetcher.DEFAULT_USER_AGENT : userAgent);
            c.setRequestProperty("Range", "bytes=0-1023");
            int code = c.getResponseCode();
            if (code == 416) {
                return new Result(true, ""); // the server answers, it just does not like the Range header
            }
            if (code >= 400) {
                return new Result(false, "HTTP " + code);
            }
            String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
            if (type.contains("text/html")) {
                return new Result(false, "page, not a stream");
            }
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[512];
                int n = in.read(buf);
                return n > 0 || code == 206 ? new Result(true, "") : new Result(false, "empty");
            }
        } finally {
            c.disconnect();
        }
    }

    private static String enc(String s) throws java.io.UnsupportedEncodingException {
        return java.net.URLEncoder.encode(s, "UTF-8");
    }

    private static String date(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(ms));
    }
}
