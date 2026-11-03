package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Checks many channels in the background (a few at a time, at most two per web server and one per Stalker portal) and records the answers. Pure Java. */
public final class IptvHealthChecker {
    public interface Listener {
        void onProgress(int done, int total, int ok, int bad);

        void onFinished(int ok, int bad, boolean cancelled);
    }

    private static final IptvHealthChecker INSTANCE = new IptvHealthChecker();
    private static final int PER_HOST = 2;
    /** A portal gives a MAC one session and often one stream: asking it for many links at once gets the MAC blocked. */
    private static final int PER_PORTAL = 1;

    private volatile boolean mRunning;
    private volatile boolean mCancelled;
    private final AtomicInteger mDone = new AtomicInteger();
    private final AtomicInteger mOk = new AtomicInteger();
    private final AtomicInteger mBad = new AtomicInteger();
    private volatile int mTotal;

    private IptvHealthChecker() {
    }

    public static IptvHealthChecker instance() {
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

    /** @return false when a check is already running */
    public synchronized boolean start(List<IptvChannel> channels, final IptvFetcher http, final String userAgent,
                                      final IptvHealth health, int threads, final Listener listener) {
        if (mRunning) {
            return false;
        }
        final List<IptvChannel> work = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (IptvChannel c : channels) {
            if (seen.add(c.key())) {
                work.add(c);
            }
        }
        mCancelled = false;
        mDone.set(0);
        mOk.set(0);
        mBad.set(0);
        mTotal = work.size();
        mRunning = true;
        final int pool = Math.max(1, threads);
        Thread t = new Thread(() -> run(work, http, userAgent, health, pool, listener), "iptv-health");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private void run(List<IptvChannel> work, final IptvFetcher http, final String userAgent, final IptvHealth health,
                     int threads, final Listener listener) {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        final Map<String, Semaphore> hosts = new HashMap<>();
        try {
            for (final IptvChannel c : work) {
                final Semaphore gate;
                synchronized (hosts) {
                    boolean portal = StalkerLink.isStalker(c.url);
                    String host = (portal ? "portal:" : "web:") + IptvUrls.hostOf(c.url);
                    Semaphore s = hosts.get(host);
                    if (s == null) {
                        s = new Semaphore(portal ? PER_PORTAL : PER_HOST);
                        hosts.put(host, s);
                    }
                    gate = s;
                }
                executor.execute(() -> {
                    if (mCancelled) {
                        return;
                    }
                    boolean acquired = false;
                    try {
                        gate.acquire();
                        acquired = true;
                        if (mCancelled) {
                            return;
                        }
                        IptvChecker.Result r = IptvChecker.checkChannel(http, c, userAgent);
                        if (r != null) { // null: a kind of link that cannot be tested from here
                            health.put(c.key(), r.ok);
                            (r.ok ? mOk : mBad).incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException e) {
                        // one broken channel must not stop the rest
                    } finally {
                        if (acquired) {
                            gate.release();
                        }
                    }
                    int d = mDone.incrementAndGet();
                    if (listener != null && (d % 10 == 0 || d == mTotal)) {
                        listener.onProgress(d, mTotal, mOk.get(), mBad.get());
                    }
                });
            }
            executor.shutdown();
            try {
                while (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    if (mCancelled) {
                        executor.shutdownNow();
                    }
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        } finally {
            health.save();
            mRunning = false;
            if (listener != null) {
                listener.onFinished(mOk.get(), mBad.get(), mCancelled);
            }
        }
    }
}
