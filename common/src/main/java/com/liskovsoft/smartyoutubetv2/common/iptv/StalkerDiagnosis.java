package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Full diagnosis of Stalker portals: EVERY channel of every portal is followed through login -> stream link -> stream data
 * and the failures are counted by the step and the reason they stopped at. Portals run side by side, but each portal is
 * asked for one channel at a time (a portal gives a MAC one session; many parallel links get the MAC blocked).
 * The verdict of every channel is also written to {@link IptvHealth}, so the green / red dots are filled in as well. Pure Java.
 */
public final class StalkerDiagnosis {
    public static final class PortalReport {
        public final String portal;
        public int total;
        public int tested;
        public int ok;
        public int loginFailed;
        public int linkFailed;
        public int streamFailed;
        /** true when login failed on the first channel: the rest could not even be tried */
        public boolean loginDown;
        public String loginDetail = "";
        /** failure reason -> how many channels, in order of first appearance */
        public final Map<String, Integer> reasons = new LinkedHashMap<>();

        PortalReport(String portal, int total) {
            this.portal = portal;
            this.total = total;
        }

        public int failed() {
            return loginFailed + linkFailed + streamFailed;
        }
    }

    public interface Listener {
        void onProgress(int done, int total);

        void onFinished(List<PortalReport> reports, boolean cancelled);
    }

    private static final StalkerDiagnosis INSTANCE = new StalkerDiagnosis();
    private static final int PARALLEL_PORTALS = 4;

    private volatile boolean mRunning;
    private volatile boolean mCancelled;
    private final AtomicInteger mDone = new AtomicInteger();
    private volatile int mTotal;
    private volatile List<PortalReport> mLast;

    private StalkerDiagnosis() {
    }

    public static StalkerDiagnosis instance() {
        return INSTANCE;
    }

    public boolean isRunning() {
        return mRunning;
    }

    public int done() {
        return mDone.get();
    }

    public int total() {
        return mTotal;
    }

    public void cancel() {
        mCancelled = true;
    }

    /** The report of the last finished (or stopped) run, null if none. */
    public List<PortalReport> lastReport() {
        return mLast;
    }

    /** @param byPortal portal url -> all its channels. @return false when a run is already going */
    public synchronized boolean start(final Map<String, List<IptvChannel>> byPortal, final IptvFetcher http,
                                      final IptvHealth health, final Listener listener) {
        if (mRunning || byPortal.isEmpty()) {
            return false;
        }
        int total = 0;
        for (List<IptvChannel> l : byPortal.values()) {
            total += l.size();
        }
        mCancelled = false;
        mDone.set(0);
        mTotal = total;
        mRunning = true;
        Thread t = new Thread(() -> run(byPortal, http, health, listener), "iptv-diagnosis");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private void run(Map<String, List<IptvChannel>> byPortal, final IptvFetcher http, final IptvHealth health, final Listener listener) {
        final List<PortalReport> reports = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(PARALLEL_PORTALS, byPortal.size()));
        try {
            for (Map.Entry<String, List<IptvChannel>> e : byPortal.entrySet()) {
                final PortalReport report = new PortalReport(e.getKey(), e.getValue().size());
                final List<IptvChannel> channels = e.getValue();
                synchronized (reports) {
                    reports.add(report);
                }
                pool.execute(() -> runPortal(report, channels, http, health, listener));
            }
            pool.shutdown();
            try {
                while (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
                    if (mCancelled) {
                        pool.shutdownNow();
                    }
                }
            } catch (InterruptedException ex) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        } finally {
            health.save();
            mLast = reports;
            mRunning = false;
            if (listener != null) {
                listener.onFinished(reports, mCancelled);
            }
        }
    }

    private void runPortal(PortalReport r, List<IptvChannel> channels, IptvFetcher http, IptvHealth health, Listener listener) {
        boolean first = true;
        for (IptvChannel c : channels) {
            if (mCancelled || Thread.currentThread().isInterrupted()) {
                return;
            }
            StalkerClient.Diag d;
            try {
                d = StalkerClient.diagnoseChannel(http, c.url);
            } catch (RuntimeException ex) {
                d = new StalkerClient.Diag("", StalkerClient.Diag.STREAM_FAILED, String.valueOf(ex.getMessage()));
            }
            if (first && d.stage == StalkerClient.Diag.LOGIN_FAILED) {
                // no MAC can log in: every other channel would fail at the very same step, so say so once
                r.loginDown = true;
                r.loginDetail = d.detail;
                r.loginFailed = r.total;
                r.tested = 1;
                r.reasons.put(d.detail, r.total);
                for (IptvChannel all : channels) {
                    health.put(all.key(), false);
                }
                mDone.addAndGet(r.total);
                report(listener);
                return;
            }
            first = false;
            record(r, d);
            health.put(c.key(), d.stage == StalkerClient.Diag.OK);
            mDone.incrementAndGet();
            report(listener);
        }
    }

    private void record(PortalReport r, StalkerClient.Diag d) {
        r.tested++;
        switch (d.stage) {
            case StalkerClient.Diag.OK:
                r.ok++;
                return;
            case StalkerClient.Diag.LINK_FAILED:
                r.linkFailed++;
                break;
            case StalkerClient.Diag.STREAM_FAILED:
                r.streamFailed++;
                break;
            default:
                r.loginFailed++;
                break;
        }
        String key = d.detail.isEmpty() ? "-" : d.detail;
        Integer n = r.reasons.get(key);
        r.reasons.put(key, n == null ? 1 : n + 1);
    }

    private void report(Listener listener) {
        int d = mDone.get();
        if (listener != null && (d % 10 == 0 || d == mTotal)) {
            listener.onProgress(d, mTotal);
        }
    }
}
