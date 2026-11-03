package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Quick health check of Stalker portals: ONLY the handshake is made (no channel list, no stream), so a whole file of
 * portals is checked in seconds and the dead ones (domain gone, MAC expired / blocked ...) are told apart from the live ones.
 * Pure Java.
 */
public final class StalkerProbe {
    private static final int MAX_PARALLEL_MACS = 3;

    public static final class Result {
        public final String portal;
        public int macsTotal;
        public int macsOk;
        /** The reason of the failures when no MAC got in (the most common one otherwise: see {@link #macFailures}). */
        public StalkerFailure reason;
        /** MAC -> why it was refused. MACs that logged in are not listed. */
        public final Map<String, StalkerFailure> macFailures = new LinkedHashMap<>();

        Result(String portal) {
            this.portal = portal;
        }

        public boolean ok() {
            return macsOk > 0;
        }
    }

    public interface Progress {
        void onDone(int done, int total);
    }

    private StalkerProbe() {
    }

    /** @return portal key ({@link StalkerAccount#key()}) -> result, in the order of {@code accounts} */
    public static Map<String, Result> checkAll(final IptvFetcher http, List<StalkerAccount> accounts, int parallelPortals,
                                               final Progress progress) {
        final Map<String, Result> out = new LinkedHashMap<>();
        if (accounts.isEmpty()) {
            return out;
        }
        final AtomicInteger done = new AtomicInteger();
        final int total = accounts.size();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(parallelPortals <= 0 ? 1 : parallelPortals, total)));
        List<Future<Result>> futures = new ArrayList<>();
        try {
            for (final StalkerAccount a : accounts) {
                futures.add(pool.submit(() -> {
                    Result r = check(http, a);
                    if (progress != null) {
                        progress.onDone(done.incrementAndGet(), total);
                    }
                    return r;
                }));
            }
            for (int i = 0; i < total; i++) {
                StalkerAccount a = accounts.get(i);
                try {
                    out.put(a.key(), futures.get(i).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException e) {
                    Result r = new Result(a.portal);
                    r.macsTotal = a.macs.size();
                    r.reason = StalkerFailure.OTHER;
                    out.put(a.key(), r);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    /** One portal: the first MAC alone (if the server is down the rest are not even tried), then the others side by side. */
    public static Result check(final IptvFetcher http, final StalkerAccount account) {
        final Result r = new Result(account.portal);
        r.macsTotal = account.macs.size();
        if (account.macs.isEmpty()) {
            r.reason = StalkerFailure.MAC_REJECTED;
            return r;
        }
        StalkerFailure first = tryMac(http, account, account.macs.get(0));
        record(r, account.macs.get(0), first);
        if (first != null && first.isNetworkDown()) {
            for (int i = 1; i < account.macs.size(); i++) {
                record(r, account.macs.get(i), first);
            }
        } else if (account.macs.size() > 1) {
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(MAX_PARALLEL_MACS, account.macs.size() - 1));
            try {
                List<Future<StalkerFailure>> futures = new ArrayList<>();
                for (int i = 1; i < account.macs.size(); i++) {
                    final String mac = account.macs.get(i);
                    futures.add(pool.submit(() -> tryMac(http, account, mac)));
                }
                for (int i = 1; i < account.macs.size(); i++) {
                    StalkerFailure f;
                    try {
                        f = futures.get(i - 1).get();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (ExecutionException e) {
                        f = StalkerFailure.OTHER;
                    }
                    record(r, account.macs.get(i), f);
                }
            } finally {
                pool.shutdownNow();
            }
        }
        if (r.macsOk == 0) {
            r.reason = mostCommon(r);
        }
        return r;
    }

    /** @return null when the handshake worked, otherwise the reason */
    private static StalkerFailure tryMac(IptvFetcher http, StalkerAccount account, String mac) {
        try {
            StalkerClient.handshakeOnly(http, account.portal, mac);
            return null;
        } catch (IOException | RuntimeException e) {
            return StalkerFailure.classify(e);
        }
    }

    private static void record(Result r, String mac, StalkerFailure failure) {
        if (failure == null) {
            r.macsOk++;
        } else {
            r.macFailures.put(mac, failure);
        }
    }

    private static StalkerFailure mostCommon(Result r) {
        Map<StalkerFailure, Integer> count = new LinkedHashMap<>();
        for (StalkerFailure f : r.macFailures.values()) {
            Integer n = count.get(f);
            count.put(f, n == null ? 1 : n + 1);
        }
        StalkerFailure best = StalkerFailure.OTHER;
        int bestN = 0;
        for (Map.Entry<StalkerFailure, Integer> e : count.entrySet()) {
            if (e.getValue() > bestN) {
                best = e.getKey();
                bestN = e.getValue();
            }
        }
        return best;
    }
}
