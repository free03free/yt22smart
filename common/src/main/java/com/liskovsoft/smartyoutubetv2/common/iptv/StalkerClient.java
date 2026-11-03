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
 * Minimal Stalker / Ministra (MAG "portal") client, live TV, movies and series (movies / series optional). Pure Java.
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

        /** What is being downloaded now ("movies: Action"); also the place where a cancel is noticed. */
        default void onStage(String text) {
        }
    }

    public static List<IptvChannel> fetchChannels(IptvFetcher http, StalkerAccount account, List<String> messages) throws IOException {
        return fetchChannels(http, account, messages, null);
    }

    public static List<IptvChannel> fetchChannels(IptvFetcher http, StalkerAccount account, List<String> messages, Step step) throws IOException {
        return fetchChannels(http, http, account, messages, step, 1, 0);
    }

    /**
     * @param quick fetcher with short timeouts for handshakes (a silent portal fails fast)
     * @param bulk fetcher with long timeouts for the big channel list
     * @param parallelMacs how many MACs are tried at the same time (the first one that returns channels wins)
     * @param deadlineMs give up on this portal after this long (0 = no deadline)
     */
    public static List<IptvChannel> fetchChannels(IptvFetcher quick, IptvFetcher bulk, StalkerAccount account, List<String> messages,
                                                  Step step, int parallelMacs, long deadlineMs) throws IOException {
        return fetchChannels(quick, bulk, account, messages, step, parallelMacs, deadlineMs, StalkerOptions.liveOnly());
    }

    public static List<IptvChannel> fetchChannels(IptvFetcher quick, IptvFetcher bulk, StalkerAccount account, List<String> messages,
                                                  Step step, int parallelMacs, long deadlineMs, StalkerOptions so) throws IOException {
        if (so.perMac) {
            return fetchEveryMac(quick, bulk, account, messages, step, parallelMacs, deadlineMs, so);
        }
        if (parallelMacs > 1 && account.macs.size() > 1) {
            return fetchParallel(quick, bulk, account, messages, step, parallelMacs, deadlineMs, so);
        }
        return fetchSequential(quick, bulk, account, messages, step, so);
    }

    private static List<IptvChannel> tryMac(IptvFetcher quick, IptvFetcher bulk, StalkerAccount account, String mac,
                                            StalkerOptions so, Step step) throws IOException {
        Session s = open(quick, account.portal, mac);
        StalkerAccount ordered = new StalkerAccount(account.portal);
        ordered.macs.add(mac); // the MAC that works goes first in every stored link
        if (!so.perMac) {
            for (String other : account.macs) {
                if (!other.equals(mac)) ordered.macs.add(other);
            }
        }
        // per MAC: the link holds only this MAC, so the channel always plays with the subscription it was listed under
        String list = so.perMac ? account.host() + " [" + mac + "]" : null;
        List<IptvChannel> channels = loadAll(bulk, s, ordered, so, step, list);
        if (channels.isEmpty()) {
            throw new IOException("no channels");
        }
        rememberMac(account.portal, mac);
        return channels;
    }

    private static List<IptvChannel> fetchParallel(final IptvFetcher quick, final IptvFetcher bulk, final StalkerAccount account,
                                                   final List<String> messages, final Step step, int parallel, long deadlineMs,
                                                   final StalkerOptions so) throws IOException {
        final java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger();
        final int total = account.macs.size();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(parallel, total));
        java.util.concurrent.ExecutorCompletionService<List<IptvChannel>> done = new java.util.concurrent.ExecutorCompletionService<>(pool);
        final java.util.Map<java.util.concurrent.Future<List<IptvChannel>>, String> macOf = new java.util.HashMap<>();
        long end = deadlineMs > 0 ? System.currentTimeMillis() + deadlineMs : Long.MAX_VALUE;
        try {
            for (final String mac : account.macs) {
                java.util.concurrent.Future<List<IptvChannel>> f = done.submit(() -> {
                    if (step != null) {
                        step.onMac(index.incrementAndGet(), total);
                    }
                    return tryMac(quick, bulk, account, mac, so, step);
                });
                macOf.put(f, mac);
            }
            IOException last = null;
            for (int i = 0; i < total; i++) {
                long wait = end == Long.MAX_VALUE ? Long.MAX_VALUE : end - System.currentTimeMillis();
                java.util.concurrent.Future<List<IptvChannel>> f;
                try {
                    f = wait == Long.MAX_VALUE ? done.take() : done.poll(Math.max(wait, 0), java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException();
                }
                if (f == null) {
                    throw new IOException("portal took too long");
                }
                try {
                    return f.get(); // the first MAC that returned channels wins
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof java.util.concurrent.CancellationException) {
                        throw (java.util.concurrent.CancellationException) cause;
                    }
                    last = cause instanceof IOException ? (IOException) cause : new IOException(String.valueOf(cause));
                    if (messages != null) {
                        synchronized (messages) {
                            messages.add("Portal " + account.host() + ": MAC " + macOf.get(f) + " failed: " + StalkerFailure.classify(last).label);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException();
                }
            }
            throw last != null ? last : new IOException("no MAC address");
        } finally {
            pool.shutdownNow(); // the other MACs are not needed any more
        }
    }

    /** Every MAC that works is fetched and kept as its own list; fails only when no MAC works. */
    private static List<IptvChannel> fetchEveryMac(final IptvFetcher quick, final IptvFetcher bulk, final StalkerAccount account,
                                                   final List<String> messages, final Step step, int parallel, long deadlineMs,
                                                   final StalkerOptions so) throws IOException {
        final java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger();
        final int total = account.macs.size();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, Math.min(parallel, total)));
        java.util.concurrent.ExecutorCompletionService<List<IptvChannel>> done = new java.util.concurrent.ExecutorCompletionService<>(pool);
        final java.util.Map<java.util.concurrent.Future<List<IptvChannel>>, String> macOf = new java.util.HashMap<>();
        long end = deadlineMs > 0 ? System.currentTimeMillis() + deadlineMs : Long.MAX_VALUE;
        List<IptvChannel> all = new ArrayList<>();
        IOException last = null;
        try {
            for (final String mac : account.macs) {
                java.util.concurrent.Future<List<IptvChannel>> f = done.submit(() -> {
                    if (step != null) {
                        step.onMac(index.incrementAndGet(), total);
                    }
                    return tryMac(quick, bulk, account, mac, so, step);
                });
                macOf.put(f, mac);
            }
            for (int i = 0; i < total; i++) {
                long wait = end == Long.MAX_VALUE ? Long.MAX_VALUE : end - System.currentTimeMillis();
                java.util.concurrent.Future<List<IptvChannel>> f;
                try {
                    f = wait == Long.MAX_VALUE ? done.take() : done.poll(Math.max(wait, 0), java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException();
                }
                if (f == null) {
                    last = new IOException("portal took too long");
                    break; // keep what the faster MACs already returned
                }
                try {
                    all.addAll(f.get());
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof java.util.concurrent.CancellationException) {
                        throw (java.util.concurrent.CancellationException) cause;
                    }
                    last = cause instanceof IOException ? (IOException) cause : new IOException(String.valueOf(cause));
                    if (messages != null) {
                        synchronized (messages) {
                            messages.add("Portal " + account.host() + ": MAC " + macOf.get(f) + " failed: " + StalkerFailure.classify(last).label);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException();
                }
            }
        } finally {
            pool.shutdownNow();
        }
        if (all.isEmpty()) {
            throw last != null ? last : new IOException("no MAC address");
        }
        return all;
    }

    private static List<IptvChannel> fetchSequential(IptvFetcher quick, IptvFetcher bulk, StalkerAccount account, List<String> messages,
                                                     Step step, StalkerOptions so) throws IOException {
        IOException last = null;
        int macIndex = 0;
        for (String mac : account.macs) {
            if (step != null) {
                step.onMac(++macIndex, account.macs.size());
            }
            try {
                return tryMac(quick, bulk, account, mac, so, step);
            } catch (IOException e) {
                last = e;
                if (messages != null) {
                    messages.add("Portal " + account.host() + ": MAC " + mac + " failed: " + StalkerFailure.classify(e).label);
                }
                if (isNetworkDown(e)) {
                    break; // the server itself is unreachable: other MACs will not help
                }
            }
        }
        throw last != null ? last : new IOException("no MAC address");
    }

    private static List<IptvChannel> loadAll(IptvFetcher http, Session s, StalkerAccount account, StalkerOptions so, Step step,
                                             String list) throws IOException {
        String prefix = list != null ? "" : account.host() + " | "; // with a list of its own the host is not repeated in the group
        List<IptvChannel> out = new ArrayList<>();
        if (so.fetchLive()) {
            if (step != null) {
                step.onStage("live " + account.host());
            }
            out.addAll(loadChannels(http, s, account, prefix, list));
        }
        if (so.movies) {
            try {
                out.addAll(loadVod(http, s, account, so, step, "vod", prefix, list));
            } catch (IOException e) {
                if (isNetworkDown(e) || out.isEmpty()) {
                    throw e;
                } // the portal may simply have no movies: keep the live channels
            }
        }
        if (so.series) {
            try {
                out.addAll(loadVod(http, s, account, so, step, "series", prefix, list));
            } catch (IOException e) {
                if (isNetworkDown(e) || out.isEmpty()) {
                    throw e;
                }
            }
        }
        return out;
    }

    /** Movies (type vod) or series (type series): category by category, one group per category name. */
    private static List<IptvChannel> loadVod(IptvFetcher http, Session s, StalkerAccount account, StalkerOptions so, Step step,
                                             String type, String prefix, String list) throws IOException {
        final boolean series = type.equals("series");
        final String label = series ? "Series" : "Movies";
        Map<String, String> categories = new LinkedHashMap<>();
        try {
            List<Object> cats = MiniJson.asList(call(http, s, "type=" + type + "&action=get_categories"));
            if (cats != null) {
                for (Object o : cats) {
                    Map<String, Object> m = MiniJson.asMap(o);
                    if (m == null) {
                        continue;
                    }
                    String id = MiniJson.str(m.get("id"));
                    String title = MiniJson.str(m.get("title"));
                    if (!id.isEmpty() && !id.equals("*") && !title.isEmpty()) {
                        categories.put(id, title);
                    }
                }
            }
        } catch (IOException e) {
            if (isNetworkDown(e)) {
                throw e;
            }
        }
        if (categories.isEmpty()) {
            categories.put("0", label); // no category list: one request series over everything
        }

        List<IptvChannel> out = new ArrayList<>();
        int items = 0;
        for (Map.Entry<String, String> cat : categories.entrySet()) {
            if (so.maxItems > 0 && items >= so.maxItems) {
                break;
            }
            String group = prefix + label + " | " + cat.getValue();
            long total = Long.MAX_VALUE;
            int seen = 0;
            for (int page = 1; page <= MAX_PAGES && seen < total; page++) {
                if (step != null) {
                    step.onStage(label.toLowerCase(Locale.ROOT) + ": " + cat.getValue() + " (" + items + ")");
                }
                Map<String, Object> js = MiniJson.asMap(call(http, s,
                        "type=" + type + "&action=get_ordered_list&category=" + enc(cat.getKey()) + "&genre=" + enc(cat.getKey())
                                + "&sortby=added&p=" + page));
                List<Object> data = js == null ? null : MiniJson.asList(js.get("data"));
                if (data == null || data.isEmpty()) {
                    break;
                }
                total = MiniJson.num(js.get("total_items"), Long.MAX_VALUE);
                seen += data.size();
                List<Map<String, Object>> pageItems = new ArrayList<>();
                for (Object o : data) {
                    Map<String, Object> m = MiniJson.asMap(o);
                    if (m != null && !MiniJson.str(m.get("name")).trim().isEmpty()) {
                        pageItems.add(m);
                    }
                }
                if (series) {
                    out.addAll(expandSeries(http, s, account, pageItems, cat.getKey(), group, list));
                } else {
                    for (Map<String, Object> m : pageItems) {
                        IptvChannel c = vodChannel(account, m, group, list, IptvChannel.KIND_MOVIE, 0, MiniJson.str(m.get("name")).trim(),
                                MiniJson.str(m.get("cmd")).trim());
                        if (c != null) {
                            out.add(c);
                        }
                    }
                }
                items += pageItems.size();
                if (so.maxItems > 0 && items >= so.maxItems) {
                    break;
                }
            }
        }
        return out;
    }

    private static IptvChannel vodChannel(StalkerAccount account, Map<String, Object> m, String group, String list, int kind,
                                          int episode, String name, String cmd) {
        if (cmd.isEmpty()) {
            return null;
        }
        // the marker tells playback to use type=vod (and which episode) when the link is created
        IptvChannel c = new IptvChannel(name, StalkerLink.build(account.portal, account.macs, "@vod:" + episode + "@" + cmd));
        c.group = group;
        c.kind = kind;
        if (list != null) {
            c.list = list;
        }
        String logo = MiniJson.str(m.get("screenshot_uri")).trim();
        if (logo.isEmpty()) {
            logo = MiniJson.str(m.get("logo")).trim();
        }
        if (logo.toLowerCase(Locale.ROOT).startsWith("http")) {
            c.logo = logo;
        }
        return c;
    }

    /** One request per series gives its seasons with their episode numbers; every episode becomes one entry. */
    private static List<IptvChannel> expandSeries(final IptvFetcher http, final Session s, final StalkerAccount account,
                                                  List<Map<String, Object>> seriesItems, final String category, final String group,
                                                  final String list) throws IOException {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, Math.min(6, seriesItems.size())));
        List<java.util.concurrent.Future<List<IptvChannel>>> futures = new ArrayList<>();
        try {
            for (final Map<String, Object> item : seriesItems) {
                futures.add(pool.submit(() -> episodesOf(http, s, account, item, category, group, list)));
            }
            List<IptvChannel> out = new ArrayList<>();
            for (java.util.concurrent.Future<List<IptvChannel>> f : futures) {
                try {
                    out.addAll(f.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.util.concurrent.CancellationException();
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof java.util.concurrent.CancellationException) {
                        throw (java.util.concurrent.CancellationException) e.getCause();
                    }
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<IptvChannel> episodesOf(IptvFetcher http, Session s, StalkerAccount account, Map<String, Object> item,
                                                String category, String group, String list) {
        String title = MiniJson.str(item.get("name")).trim();
        List<IptvChannel> out = new ArrayList<>();
        try {
            Map<String, Object> js = MiniJson.asMap(call(http, s, "type=series&action=get_ordered_list&movie_id=" + enc(MiniJson.str(item.get("id")))
                    + "&season_id=0&episode_id=0&category=" + enc(category) + "&p=1"));
            List<Object> seasons = js == null ? null : MiniJson.asList(js.get("data"));
            if (seasons != null) {
                for (Object so : seasons) {
                    Map<String, Object> season = MiniJson.asMap(so);
                    if (season == null) {
                        continue;
                    }
                    String cmd = MiniJson.str(season.get("cmd")).trim();
                    String seasonName = MiniJson.str(season.get("name")).trim();
                    List<Object> episodes = MiniJson.asList(season.get("series"));
                    if (cmd.isEmpty() || episodes == null) {
                        continue;
                    }
                    for (Object e : episodes) {
                        int n = (int) MiniJson.num(e, 0);
                        if (n <= 0) {
                            continue;
                        }
                        String name = title + (seasonName.isEmpty() ? "" : " - " + seasonName) + " E" + (n < 10 ? "0" : "") + n;
                        IptvChannel c = vodChannel(account, item, group, list, IptvChannel.KIND_SERIES, n, name, cmd);
                        if (c != null) {
                            out.add(c);
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // fall back to a single entry below
        }
        if (out.isEmpty()) { // the portal does not expose seasons: keep the series itself, it plays its first episode
            IptvChannel c = vodChannel(account, item, group, list, IptvChannel.KIND_SERIES, 0, title, MiniJson.str(item.get("cmd")).trim());
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    private static List<IptvChannel> loadChannels(IptvFetcher http, Session s, StalkerAccount account, String groupPrefix, String list) throws IOException {
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
            c.group = groupPrefix + (genre == null || genre.isEmpty() ? "TV" : genre);
            if (list != null) {
                c.list = list;
            }
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

    // ------------------------------------------------------------------ diagnosis

    /** Outcome of the three steps for one MAC: login, stream link, stream data. */
    public static final class Diag {
        public static final int LOGIN_FAILED = 0, LINK_FAILED = 1, STREAM_FAILED = 2, OK = 3;
        public final String mac;
        public final int stage;
        public final String detail;

        Diag(String mac, int stage, String detail) {
            this.mac = mac;
            this.stage = stage;
            this.detail = detail == null ? "" : detail;
        }
    }

    /**
     * Follows the real playback path for ONE channel: login, stream link, stream data. The MACs are tried in order and
     * the first one that plays wins; when none does, the result of the MAC that got furthest is returned. Sessions are
     * reused (one login per MAC), so it is cheap to call for every channel of a portal.
     */
    public static Diag diagnoseChannel(IptvFetcher http, String stalkerUrl) {
        StalkerLink link = StalkerLink.parse(stalkerUrl);
        if (link == null) {
            return new Diag("", Diag.LOGIN_FAILED, "Invalid portal channel");
        }
        List<String> order = new ArrayList<>(link.macs);
        String good = workingMac(link.portal);
        if (good != null && order.remove(good)) {
            order.add(0, good);
        }
        Diag best = null;
        for (String mac : order) {
            Diag d = diagnoseWith(http, link, mac);
            if (d.stage == Diag.OK) {
                rememberMac(link.portal, mac);
                return d;
            }
            if (best == null || d.stage > best.stage) {
                best = d;
            }
        }
        return best != null ? best : new Diag("", Diag.LOGIN_FAILED, "no MAC address");
    }

    private static Diag diagnoseWith(IptvFetcher http, StalkerLink link, String mac) {
        Session s;
        try {
            s = session(http, link.portal, mac, false, null);
        } catch (IOException e) {
            return new Diag(mac, Diag.LOGIN_FAILED, message(e));
        }
        Resolved r;
        try {
            try {
                r = createLink(http, s, link.cmd);
            } catch (IOException e) {
                if (isNetworkDown(e) || !looksLikeAuthFailure(e)) {
                    throw e;
                }
                r = createLink(http, session(http, link.portal, mac, false, s), link.cmd); // token expired: one new login
            }
        } catch (IOException e) {
            return new Diag(mac, isNetworkDown(e) ? Diag.LOGIN_FAILED : Diag.LINK_FAILED, message(e));
        }
        if (!r.url.toLowerCase(Locale.ROOT).startsWith("http")) {
            return new Diag(mac, Diag.OK, ""); // udp / rtmp: cannot be tested from here, the link at least exists
        }
        try {
            IptvChecker.Result probe = IptvChecker.probe(r.url, r.userAgent);
            return new Diag(mac, probe.ok ? Diag.OK : Diag.STREAM_FAILED, probe.detail);
        } catch (IOException e) {
            return new Diag(mac, Diag.STREAM_FAILED, message(e));
        }
    }

    /** What a portal says about itself: how many of its MACs log in and until when the account is valid. */
    public static final class PortalInfo {
        public int macsTotal;
        public int macsOk;
        /** end of the subscription as the portal reports it, "" when it does not say */
        public String expiry = "";
        /** why the login failed (set when no MAC got in) */
        public String error = "";
    }

    /** Logs in with each MAC (at most 10) and reads the account info; never throws. */
    public static PortalInfo portalInfo(IptvFetcher http, String portal, List<String> macs) {
        PortalInfo info = new PortalInfo();
        int n = 0;
        for (String mac : macs) {
            if (n++ >= 10) {
                break;
            }
            info.macsTotal++;
            Session s;
            try {
                s = session(http, portal, mac, false, null);
            } catch (IOException e) {
                if (info.error.isEmpty()) {
                    info.error = message(e);
                }
                if (isNetworkDown(e)) {
                    break; // the server does not answer at all: the other MACs will not either
                }
                continue;
            }
            info.macsOk++;
            if (info.expiry.isEmpty()) {
                try {
                    Map<String, Object> m = MiniJson.asMap(call(http, s, "type=account_info&action=get_main_info"));
                    if (m != null) {
                        String end = MiniJson.str(m.get("end_date"));
                        if (end.isEmpty() || end.startsWith("0000")) {
                            end = MiniJson.str(m.get("expire_billing_date"));
                        }
                        info.expiry = end.startsWith("0000") ? "" : end;
                    }
                } catch (IOException ignored) {
                    // not every portal answers this call
                }
            }
        }
        return info;
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
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
        Session s = session(http, link.portal, mac, fresh, null);
        try {
            return createLink(http, s, link.cmd);
        } catch (IOException e) {
            boolean oldSession = System.currentTimeMillis() - s.created > 5 * 60 * 1000L; // an old token may just have expired
            if (isNetworkDown(e) || !(looksLikeAuthFailure(e) || (oldSession && "portal gave no stream link".equals(e.getMessage())))) {
                throw e; // a dead channel / a portal answer: a new login would not help (and would kill the other tokens)
            }
            // The token is no longer valid. Portals keep ONE token per MAC, so another thread (channel check, zapping)
            // may already have logged in again: use its session first, log in again only if nobody did.
            Session now = session(http, link.portal, mac, false, s);
            return createLink(http, now, link.cmd);
        }
    }

    /** The portal refused our token (HTTP 401/403 or an answer that is not the portal api). */
    private static boolean looksLikeAuthFailure(IOException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        return m.startsWith("HTTP 401") || m.startsWith("HTTP 403") || m.equals("not a portal answer");
    }

    private static final Map<String, Object> LOCKS = new HashMap<>();

    private static synchronized Object lockFor(String key) {
        Object l = LOCKS.get(key);
        if (l == null) {
            l = new Object();
            LOCKS.put(key, l);
        }
        return l;
    }

    /**
     * The session of this portal + MAC. Only ONE login at a time per MAC: a portal gives a MAC a single token, so two
     * parallel handshakes (channel check, channel zapping, play) invalidate each other and every call then fails with 401.
     * @param stale a session that just failed: it is not returned again, a new login is made (unless someone already did)
     */
    private static Session session(IptvFetcher http, String portal, String mac, boolean fresh, Session stale) throws IOException {
        String key = portal + "|" + mac;
        synchronized (lockFor(key)) {
            Session c = cached(key);
            if (c != null && !fresh && c != stale) {
                return c;
            }
            return open(http, portal, mac);
        }
    }

    /** "@vod:<episode>@<cmd>": a movie / series episode (type=vod); no marker: a live channel (type=itv). */
    private static final Pattern VOD_MARK = Pattern.compile("^@vod:(\\d*)@");

    private static Resolved createLink(IptvFetcher http, Session s, String storedCmd) throws IOException {
        String type = "itv";
        String series = "";
        String cmd = storedCmd;
        Matcher mark = VOD_MARK.matcher(storedCmd);
        if (mark.find()) {
            type = "vod";
            series = mark.group(1).equals("0") ? "" : mark.group(1);
            cmd = storedCmd.substring(mark.end());
        }
        Object js = call(http, s, "type=" + type + "&action=create_link&cmd=" + enc(cmd)
                + "&series=" + series + "&forced_storage=undefined&disable_ad=0&download=0&force_ch_link_check=0");
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

    /** Login only (no channels, no stream): throws why this MAC cannot get in. Used by the quick portal check. */
    public static void handshakeOnly(IptvFetcher http, String portal, String mac) throws IOException {
        synchronized (lockFor(portal + "|" + mac)) {
            open(http, portal, mac);
        }
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
