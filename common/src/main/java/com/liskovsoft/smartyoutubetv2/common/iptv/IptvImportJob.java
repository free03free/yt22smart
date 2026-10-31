package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background import of one or many sources (files, folders, pasted text) at the same time.
 * <p>
 * Every source gets its own {@link IptvImporter}; the format is detected from the content, so a mixed
 * selection (m3u + txt + zip + rar + Xtream/Stalker text ...) is fine, and one bad file never stops the others.
 * The state is static so the sidebar section can show the progress while the user browses the app.
 */
public final class IptvImportJob {
    private static final int MAX_THREADS = 3; // memory: every worker holds a whole file in RAM
    private static final int MAX_MESSAGES = 40;

    /** Called (from worker threads, throttled) when the visible state changed. */
    public interface Listener {
        void onChanged(boolean finished);
    }

    /** One thing to import. */
    public static final class Source {
        public final String label;
        public final File file;
        public final String text;

        private Source(String label, File file, String text) {
            this.label = label;
            this.file = file;
            this.text = text;
        }

        public static Source ofFile(File file) {
            return new Source(file.getName(), file, null);
        }

        public static Source ofText(String label, String text) {
            return new Source(label, null, text);
        }
    }

    /** Immutable picture of the job for the UI. */
    public static final class Snapshot {
        public final boolean running;
        public final boolean hasResult;
        public final boolean cancelled;
        /** The job only collects channels (nothing is stored); the user chooses afterwards. */
        public final boolean analyze;
        public final int total;
        public final int done;
        public final int failed;
        public final int channels;   // collected so far (live)
        public final int added;      // really new channels stored so far
        public final long elapsedMs;
        public final String current; // names of what is being processed right now
        public final List<String> messages;

        Snapshot(boolean running, boolean hasResult, boolean cancelled, boolean analyze, int total, int done, int failed, int channels, int added,
                 long elapsedMs, String current, List<String> messages) {
            this.running = running;
            this.hasResult = hasResult;
            this.cancelled = cancelled;
            this.analyze = analyze;
            this.total = total;
            this.done = done;
            this.failed = failed;
            this.channels = channels;
            this.added = added;
            this.elapsedMs = elapsedMs;
            this.current = current;
            this.messages = messages;
        }

        public int percent() {
            return total <= 0 ? 0 : Math.min(100, done * 100 / total);
        }
    }

    private static final IptvImportJob INSTANCE = new IptvImportJob();

    public static IptvImportJob instance() {
        return INSTANCE;
    }

    private final Object mLock = new Object();
    private volatile boolean mRunning;
    private volatile boolean mHasResult;
    private volatile boolean mCancelled;
    private volatile boolean mAnalyze;
    private final Object mPendingLock = new Object();
    private IptvPlaylist mPending = new IptvPlaylist(); // analyze mode: everything found, waiting for the user's choice
    private volatile int mTotal;
    private final AtomicInteger mDone = new AtomicInteger();
    private final AtomicInteger mFailed = new AtomicInteger();
    private final AtomicInteger mAdded = new AtomicInteger();
    private final ConcurrentHashMap<String, Integer> mLiveChannels = new ConcurrentHashMap<>(); // per source, live count
    private final ConcurrentHashMap<String, String> mRunningNow = new ConcurrentHashMap<>();
    private final AtomicInteger mFinishedChannels = new AtomicInteger();
    private final List<String> mMessages = Collections.synchronizedList(new ArrayList<String>());
    private volatile long mStartMs;
    private volatile long mEndMs;
    private volatile long mLastNotifyMs;
    private volatile ExecutorService mPool;
    private volatile Listener mListener;

    private IptvImportJob() {
    }

    public boolean isRunning() {
        return mRunning;
    }

    public boolean hasResult() {
        return mHasResult;
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    public Snapshot snapshot() {
        int live = mFinishedChannels.get();
        for (Integer v : mLiveChannels.values()) {
            live += v;
        }

        StringBuilder current = new StringBuilder();
        for (String name : mRunningNow.values()) {
            if (current.length() > 0) {
                current.append(", ");
            }
            current.append(name);
        }

        long end = mRunning ? System.currentTimeMillis() : mEndMs;
        List<String> messages;
        synchronized (mMessages) {
            messages = new ArrayList<>(mMessages);
        }

        return new Snapshot(mRunning, mHasResult, mCancelled, mAnalyze, mTotal, mDone.get(), mFailed.get(), live, mAdded.get(),
                Math.max(0, end - mStartMs), current.toString(), messages);
    }

    /** Forget the finished result (the "done" card in the section). */
    public void dismissResult() {
        if (!mRunning) {
            mHasResult = false;
            mMessages.clear();
        }
    }

    public void cancel() {
        if (!mRunning) {
            return;
        }
        mCancelled = true;
        ExecutorService pool = mPool;
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    /**
     * Expands folders (recursively) into plain files and removes duplicates.
     * Hidden files are skipped; the format of the rest is decided later from the content.
     */
    public static List<Source> expand(List<File> inputs) {
        Set<String> seen = new LinkedHashSet<>();
        List<Source> out = new ArrayList<>();
        for (File f : inputs) {
            collect(f, seen, out, 0);
        }
        return out;
    }

    private static void collect(File f, Set<String> seen, List<Source> out, int depth) {
        if (f == null || depth > 12 || f.getName().startsWith(".")) {
            return;
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children == null) {
                return;
            }
            java.util.Arrays.sort(children);
            for (File c : children) {
                collect(c, seen, out, depth + 1);
            }
        } else if (f.isFile() && seen.add(f.getAbsolutePath())) {
            out.add(Source.ofFile(f));
        }
    }

    /**
     * Starts the job on background threads and returns at once.
     *
     * @return false when a job is already running or there is nothing to do
     */
    public boolean start(final List<Source> sources, final IptvOptions options, final IptvStore store) {
        return start(sources, options, store, false);
    }

    /**
     * @param analyze true: only collect the channels (nothing is stored); read them with {@link #takePending()}
     *                when the job has finished and let the user choose what to import
     */
    public boolean start(final List<Source> sources, final IptvOptions options, final IptvStore store, final boolean analyze) {
        if (sources == null || sources.isEmpty()) {
            return false;
        }
        synchronized (mLock) {
            if (mRunning) {
                return false;
            }
            mRunning = true;
        }

        mCancelled = false;
        mAnalyze = analyze;
        takePending();
        mHasResult = false;
        mTotal = sources.size();
        mDone.set(0);
        mFailed.set(0);
        mAdded.set(0);
        mFinishedChannels.set(0);
        mLiveChannels.clear();
        mRunningNow.clear();
        mMessages.clear();
        mStartMs = System.currentTimeMillis();
        mEndMs = mStartMs;
        mLastNotifyMs = 0;

        final int threads = Math.max(1, Math.min(MAX_THREADS, sources.size()));
        final ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "iptv-import");
            t.setDaemon(true);
            return t;
        });
        mPool = pool;

        Thread supervisor = new Thread(() -> {
            try {
                int index = 0;
                for (final Source source : sources) {
                    final String id = (index++) + ":" + source.label;
                    pool.execute(() -> runOne(id, source, options, store));
                }
                pool.shutdown();
                while (!pool.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    notifyChanged(false, false); // heartbeat: the elapsed time moves even when nothing else does
                }
            } catch (Throwable t) {
                addMessage("Import stopped: " + t);
            } finally {
                mEndMs = System.currentTimeMillis();
                if (mCancelled && mAnalyze) {
                    takePending(); // a worker may have added something after the cancel
                }
                mRunning = false;
                mHasResult = true;
                mRunningNow.clear();
                mPool = null;
                notifyChanged(true, true);
            }
        }, "iptv-import-supervisor");
        supervisor.setDaemon(true);
        supervisor.start();

        notifyChanged(false, true);
        return true;
    }

    private void runOne(String id, Source source, IptvOptions options, IptvStore store) {
        if (mCancelled) {
            return;
        }
        mRunningNow.put(id, source.label);
        notifyChanged(false, true);

        int found = 0;
        try {
            IptvImporter importer = new IptvImporter(new HttpIptvFetcher(options), options, new CommonsArchiveHandler());
            importer.setProgress((status, channels) -> {
                if (mCancelled) {
                    throw new CancellationException();
                }
                mLiveChannels.put(id, channels);
                if (status != null) {
                    mRunningNow.put(id, source.label + " " + status);
                }
                notifyChanged(false, false);
            });

            IptvImporter.Result result = source.file != null ? importer.importFile(source.file) : importer.importText(source.text);

            found = result.playlist.size();
            if (found > 0) {
                if (mAnalyze) {
                    synchronized (mPendingLock) {
                        mPending.addAll(result.playlist.getChannels());
                    }
                } else {
                    mAdded.addAndGet(store.merge(result.playlist));
                }
            } else {
                mFailed.incrementAndGet();
                addMessage(source.label + ": " + (result.messages.isEmpty() ? "no channels found" : result.messages.get(0)));
            }
            if (result.failures > 0 && found > 0) {
                addMessage(source.label + ": " + result.failures + " link(s) failed");
            }
            for (String m : result.messages) {
                if (found > 0 && m.startsWith("Failed")) { // keep the details only for the important notes
                    addMessage(source.label + ": " + m);
                    break;
                }
            }
        } catch (CancellationException e) {
            // cancelled by the user: nothing to report
        } catch (OutOfMemoryError e) {
            mFailed.incrementAndGet();
            addMessage(source.label + ": out of memory (file too large, lower the limits in import settings)");
        } catch (Throwable t) {
            mFailed.incrementAndGet();
            addMessage(source.label + ": " + t.getMessage());
        } finally {
            mLiveChannels.remove(id);
            mFinishedChannels.addAndGet(found);
            mRunningNow.remove(id);
            mDone.incrementAndGet();
            notifyChanged(false, true);
        }
    }

    /** Analyze mode: hands over everything that was found and forgets it here. */
    public IptvPlaylist takePending() {
        synchronized (mPendingLock) {
            IptvPlaylist found = mPending;
            mPending = new IptvPlaylist();
            return found;
        }
    }

    private void addMessage(String message) {
        if (mMessages.size() < MAX_MESSAGES) {
            mMessages.add(message);
        }
    }

    private void notifyChanged(boolean finished, boolean force) {
        Listener l = mListener;
        if (l == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - mLastNotifyMs < 1500) {
            return;
        }
        mLastNotifyMs = now;
        l.onChanged(finished);
    }
}
