package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Plain HttpURLConnection downloader: follows redirects (also http -> https), size capped, IPTV friendly user agent. */
public class HttpIptvFetcher implements IptvFetcher {
    public static final String DEFAULT_USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20";
    private static final int MAX_REDIRECTS = 6;

    private final IptvOptions mOptions;

    public HttpIptvFetcher() {
        this(new IptvOptions());
    }

    public HttpIptvFetcher(IptvOptions options) {
        mOptions = options;
    }

    @Override
    public byte[] fetch(String url) throws IOException {
        return fetch(url, null);
    }

    @Override
    public byte[] fetch(String url, Map<String, String> headers) throws IOException {
        String current = url;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            if (!current.toLowerCase().startsWith("http://") && !current.toLowerCase().startsWith("https://")) {
                throw new IOException("Only http/https lists can be downloaded");
            }
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(mOptions.connectTimeoutMs);
                c.setReadTimeout(mOptions.readTimeoutMs);
                c.setRequestProperty("User-Agent", mOptions.userAgent == null || mOptions.userAgent.isEmpty() ? DEFAULT_USER_AGENT : mOptions.userAgent);
                c.setRequestProperty("Accept", "*/*");
                if (headers != null) {
                    for (Map.Entry<String, String> h : headers.entrySet()) {
                        c.setRequestProperty(h.getKey(), h.getValue()); // overrides the defaults above
                    }
                }
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) {
                        throw new IOException("Redirect without Location");
                    }
                    current = new URL(new URL(current), loc).toString();
                    continue;
                }
                if (code < 200 || code >= 300) {
                    throw new IOException("HTTP " + code);
                }
                InputStream in = c.getInputStream();
                if ("gzip".equalsIgnoreCase(c.getContentEncoding())) {
                    in = new GZIPInputStream(in);
                }
                return readAll(in, mOptions.maxDownloadBytes);
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private static byte[] readAll(InputStream in, long maxBytes) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[32 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (!IptvOptions.within(maxBytes, total)) {
                    throw new IOException("Download is larger than " + (maxBytes / 1024 / 1024) + " MB");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (OutOfMemoryError e) {
            throw new IOException("Not enough memory for this download");
        } finally {
            in.close();
        }
    }
}
