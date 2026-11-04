package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A tiny web page served on the home network: open it in the phone's browser, paste links / text or pick files,
 * and they are handed to the importer. Protected by a PIN shown on the TV. Pure Java.
 */
public final class IptvWebReceiver {
    public static final class Upload {
        public final String name;
        public final byte[] data;

        Upload(String name, byte[] data) {
            this.name = name;
            this.data = data;
        }
    }

    public interface Listener {
        /** Called on a server thread. @return the message shown on the phone */
        String onReceive(String text, List<Upload> files);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int FIRST_PORT = 8085;
    private static final int PORTS = 10;
    /** A request is read into memory, so even "unlimited" stops below the largest array Java can allocate. */
    private static final long HARD_LIMIT = Integer.MAX_VALUE - 16L;
    private static final long IDLE_STOP_MS = 15L * 60 * 1000;

    private final Listener mListener;
    private final String mPin;
    private final int mFixedPort;
    private final long mMaxBody;
    private volatile ServerSocket mServer;
    private volatile long mLastActivity;
    private ExecutorService mPool;

    /** @param maxBytes largest accepted request in bytes; 0 or less = no limit of our own */
    public IptvWebReceiver(Listener listener, String pin, long maxBytes) {
        this(listener, pin, maxBytes, 0);
    }

    /** @param fixedPort the only port to use (1024-65535); 0 = the first free one from 8085 */
    public IptvWebReceiver(Listener listener, String pin, long maxBytes, int fixedPort) {
        mFixedPort = fixedPort;
        mListener = listener;
        mPin = pin;
        mMaxBody = maxBytes <= 0 ? HARD_LIMIT : Math.min(maxBytes, HARD_LIMIT);
    }

    public String pin() {
        return mPin;
    }

    public boolean isRunning() {
        ServerSocket s = mServer;
        return s != null && !s.isClosed();
    }

    public int port() {
        ServerSocket s = mServer;
        return s == null ? 0 : s.getLocalPort();
    }

    public synchronized void start() throws IOException {
        if (isRunning()) {
            return;
        }
        IOException last = null;
        int first = mFixedPort > 0 ? mFixedPort : FIRST_PORT;
        int count = mFixedPort > 0 ? 1 : PORTS;
        for (int p = first; p < first + count; p++) {
            try {
                mServer = new ServerSocket(p);
                break;
            } catch (IOException e) {
                last = e;
            }
        }
        if (mServer == null) {
            throw last != null ? last : new IOException("no free port");
        }
        mLastActivity = System.currentTimeMillis();
        mPool = Executors.newCachedThreadPool();
        final ServerSocket server = mServer;
        Thread t = new Thread(() -> acceptLoop(server), "iptv-web-receiver");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void stop() {
        ServerSocket s = mServer;
        mServer = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        if (mPool != null) {
            mPool.shutdownNow();
            mPool = null;
        }
    }

    private void acceptLoop(ServerSocket server) {
        try {
            server.setSoTimeout(60_000);
        } catch (IOException e) {
            return;
        }
        while (!server.isClosed()) {
            try {
                final Socket client = server.accept();
                mLastActivity = System.currentTimeMillis();
                ExecutorService pool = mPool;
                if (pool == null) {
                    client.close();
                    return;
                }
                pool.execute(() -> handle(client));
            } catch (SocketTimeoutException e) {
                if (System.currentTimeMillis() - mLastActivity > IDLE_STOP_MS) {
                    stop(); // nobody used it for a while: do not leave a server open on the home network
                    return;
                }
            } catch (IOException e) {
                return;
            } catch (RuntimeException e) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ http

    private void handle(Socket client) {
        try {
            client.setSoTimeout(30_000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String path = parts.length > 1 ? parts[1] : "/";
            long length = 0;
            String contentType = "";
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if (name.equals("content-length")) {
                    try {
                        length = Long.parseLong(value);
                    } catch (NumberFormatException e) {
                        length = 0;
                    }
                } else if (name.equals("content-type")) {
                    contentType = value;
                }
            }
            if (method.equals("GET")) {
                respond(out, 200, page(""));
            } else if (method.equals("POST")) {
                if (length > mMaxBody) {
                    respond(out, 413, page("\u0627\u0644\u062D\u062C\u0645 \u0643\u0628\u064A\u0631 \u062C\u062F\u0627\u064B (\u0627\u0644\u062D\u062F " + (mMaxBody / (1024 * 1024))
                            + "MB). \u063A\u064A\u0651\u0631\u0647 \u0645\u0646 \u0625\u0639\u062F\u0627\u062F\u0627\u062A \u0627\u0644\u0627\u0633\u062A\u064A\u0631\u0627\u062F"));
                    return;
                }
                byte[] body;
                try {
                    body = readBody(in, (int) length);
                } catch (OutOfMemoryError e) {
                    respond(out, 500, page("\u0627\u0644\u0630\u0627\u0643\u0631\u0629 \u0644\u0627 \u062A\u0643\u0641\u064A \u0644\u0647\u0630\u0627 \u0627\u0644\u062D\u062C\u0645\u060C \u0623\u0631\u0633\u0644 \u0645\u0644\u0641\u0627\u062A \u0623\u0635\u063A\u0631"));
                    return;
                }
                respond(out, 200, page(onPost(contentType, body)));
            } else {
                respond(out, 405, page(""));
            }
        } catch (Throwable t) {
            // a broken connection or a huge upload: nothing useful to tell
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
                // closed
            }
        }
    }

    private String onPost(String contentType, byte[] body) {
        String text = "";
        String pin = "";
        List<Upload> files = new ArrayList<>();
        try {
            if (contentType.toLowerCase(Locale.ROOT).startsWith("multipart/form-data")) {
                String boundary = boundaryOf(contentType);
                if (boundary == null) {
                    return "\u0637\u0644\u0628 \u063A\u064A\u0631 \u0635\u0627\u0644\u062D";
                }
                for (Part p : parseMultipart(body, boundary)) {
                    if (p.filename != null) {
                        if (p.data.length > 0) {
                            files.add(new Upload(p.filename, p.data));
                        }
                    } else if ("pin".equals(p.name)) {
                        pin = new String(p.data, UTF8).trim();
                    } else if ("text".equals(p.name)) {
                        text = new String(p.data, UTF8);
                    }
                }
            } else {
                for (String pair : new String(body, UTF8).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq < 0) {
                        continue;
                    }
                    String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                    String v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                    if (k.equals("pin")) {
                        pin = v.trim();
                    } else if (k.equals("text")) {
                        text = v;
                    }
                }
            }
        } catch (Exception e) {
            return "\u062A\u0639\u0630\u0651\u0631\u062A \u0642\u0631\u0627\u0621\u0629 \u0627\u0644\u0637\u0644\u0628";
        }
        if (!mPin.equals(pin)) {
            return "\u0631\u0645\u0632 PIN \u063A\u064A\u0631 \u0635\u062D\u064A\u062D (\u0627\u0646\u0638\u0631 \u0627\u0644\u062A\u0644\u0641\u0627\u0632)";
        }
        if (text.trim().isEmpty() && files.isEmpty()) {
            return "\u0644\u0645 \u062A\u064F\u0631\u0633\u0644 \u0634\u064A\u0626\u0627\u064B";
        }
        mLastActivity = System.currentTimeMillis();
        String message = mListener.onReceive(text, files);
        return message == null ? "" : message;
    }

    private static void respond(OutputStream out, int code, String html) throws IOException {
        byte[] data = html.getBytes(UTF8);
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "Error") + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(UTF8));
        out.write(data);
        out.flush();
    }

    private String page(String message) {
        String note = message.isEmpty() ? "" : "<p class=m>" + escape(message) + "</p>";
        return "<!doctype html><html lang=ar dir=rtl><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'><title>IPTV</title>"
                + "<style>body{font-family:sans-serif;margin:0;padding:16px;background:#111;color:#eee}"
                + "h2{margin-top:0}form{background:#1e1e1e;padding:14px;border-radius:12px;margin-bottom:16px}"
                + "textarea,input[type=text],input[type=password],input[type=tel]{width:100%;box-sizing:border-box;padding:10px;border-radius:8px;border:1px solid #444;background:#000;color:#fff;font-size:16px}"
                + "textarea{height:150px}button{margin-top:10px;width:100%;padding:12px;font-size:17px;border:0;border-radius:8px;background:#e53935;color:#fff}"
                + "label{display:block;margin:8px 0 4px}.m{background:#263238;padding:10px;border-radius:8px}</style></head><body>"
                + "<h2>\u0625\u0631\u0633\u0627\u0644 \u0625\u0644\u0649 \u0627\u0644\u062A\u0644\u0641\u0627\u0632</h2>" + note
                + "<form method=post action=/ enctype='application/x-www-form-urlencoded'>"
                + "<label>\u0631\u0645\u0632 PIN (\u064A\u0638\u0647\u0631 \u0639\u0644\u0649 \u0627\u0644\u062A\u0644\u0641\u0627\u0632)</label><input name=pin type=tel autocomplete=off>"
                + "<label>\u0631\u0648\u0627\u0628\u0637 \u0623\u0648 \u0646\u0635 \u0642\u0627\u0626\u0645\u0629</label><textarea name=text></textarea>"
                + "<button>\u0625\u0631\u0633\u0627\u0644 \u0627\u0644\u0646\u0635</button></form>"
                + "<form method=post action=/ enctype='multipart/form-data'>"
                + "<label>\u0631\u0645\u0632 PIN</label><input name=pin type=tel autocomplete=off>"
                + "<label>\u0645\u0644\u0641\u0627\u062A \u0645\u0646 \u0627\u0644\u0647\u0627\u062A\u0641 (m3u / txt / zip ...)</label><input type=file name=f multiple>"
                + "<button>\u0625\u0631\u0633\u0627\u0644 \u0627\u0644\u0645\u0644\u0641\u0627\u062A</button></form></body></html>";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------ parsing

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                b.write(c);
            }
            if (b.size() > 16 * 1024) {
                throw new IOException("header too long");
            }
        }
        if (c == -1 && b.size() == 0) {
            return null;
        }
        return new String(b.toByteArray(), UTF8);
    }

    private static byte[] readBody(InputStream in, int length) throws IOException {
        byte[] body = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(body, off, length - off);
            if (n < 0) {
                throw new IOException("connection closed");
            }
            off += n;
        }
        return body;
    }

    private static String boundaryOf(String contentType) {
        int i = contentType.toLowerCase(Locale.ROOT).indexOf("boundary=");
        if (i < 0) {
            return null;
        }
        String b = contentType.substring(i + 9).trim();
        int semi = b.indexOf(';');
        if (semi >= 0) {
            b = b.substring(0, semi);
        }
        if (b.length() >= 2 && b.startsWith("\"") && b.endsWith("\"")) {
            b = b.substring(1, b.length() - 1);
        }
        return b.isEmpty() ? null : b;
    }

    static final class Part {
        String name;
        String filename;
        byte[] data;
    }

    static List<Part> parseMultipart(byte[] body, String boundary) {
        List<Part> parts = new ArrayList<>();
        byte[] delimiter = ("--" + boundary).getBytes(UTF8);
        byte[] headerEnd = "\r\n\r\n".getBytes(UTF8);
        byte[] nextDelimiter = ("\r\n--" + boundary).getBytes(UTF8);
        int pos = indexOf(body, delimiter, 0);
        while (pos >= 0) {
            int start = pos + delimiter.length;
            if (start + 1 < body.length && body[start] == '-' && body[start + 1] == '-') {
                break; // closing delimiter
            }
            start += 2; // CRLF after the delimiter
            int headersEnd = indexOf(body, headerEnd, start);
            if (headersEnd < 0) {
                break;
            }
            String headers = new String(body, start, headersEnd - start, UTF8);
            int dataStart = headersEnd + headerEnd.length;
            int dataEnd = indexOf(body, nextDelimiter, dataStart);
            if (dataEnd < 0) {
                break;
            }
            Part p = new Part();
            p.data = java.util.Arrays.copyOfRange(body, dataStart, dataEnd);
            for (String h : headers.split("\r\n")) {
                if (h.toLowerCase(Locale.ROOT).startsWith("content-disposition")) {
                    p.name = param(h, "name");
                    p.filename = param(h, "filename");
                }
            }
            parts.add(p);
            pos = dataEnd + 2; // next delimiter starts after its CRLF
        }
        return parts;
    }

    private static String param(String header, String key) {
        String needle = ";" + " " + key + "=\"";
        int i = header.indexOf(needle);
        if (i < 0) {
            needle = ";" + key + "=\"";
            i = header.indexOf(needle);
        }
        if (i < 0) {
            return null;
        }
        int from = i + needle.length();
        int to = header.indexOf('"', from);
        return to < 0 ? null : header.substring(from, to);
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ addresses

    /** Local IPv4 addresses of this device (the TV), most likely the Wi-Fi / Ethernet one first. */
    public static List<String> localAddresses() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            if (nets == null) {
                return out;
            }
            for (NetworkInterface n : Collections.list(nets)) {
                if (!n.isUp() || n.isLoopback()) {
                    continue;
                }
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            out.add(0, ip);
                        } else {
                            out.add(ip);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // no network information
        }
        return out;
    }
}
