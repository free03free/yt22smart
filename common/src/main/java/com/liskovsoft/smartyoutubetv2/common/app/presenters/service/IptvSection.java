package com.liskovsoft.smartyoutubetv2.common.app.presenters.service;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.IptvPreviewPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.IptvSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.iptv.HttpIptvFetcher;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvChannel;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvImportJob;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvPlaylist;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvPrefs;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvStore;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvUrls;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvUserData;
import com.liskovsoft.smartyoutubetv2.common.iptv.StalkerClient;
import com.liskovsoft.smartyoutubetv2.common.iptv.StalkerLink;
import com.liskovsoft.smartyoutubetv2.common.misc.IptvPlayerActivity;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "IPTV" sidebar section. Works like a small file manager made of cards:
 * <pre>
 *   root:   [search] [add channels] [favorites] [recent] [all channels] [group 1] [group 2] ...
 *   inside: [back] [channel] [channel] ... [next page]
 * </pre>
 * Click a channel to play it, long press it for the menu (favorite, copy link, remove).
 * The state (opened folder / page) lives in memory only; every card is a plain {@link Video}
 * whose reloadPageKey carries the action (same trick the playlist library uses).
 */
public final class IptvSection {
    /** Must be less than SidebarService.RESERVED_ID (100) and not clash with MediaGroup.TYPE_* and PlaylistLibraryService (50). */
    public static final int SECTION_ID = 51;

    private static final String MARK = "iptv:";
    private static final String K_PLAY = MARK + "play:";
    private static final String K_OPEN = MARK + "open:";
    private static final String K_BACK = MARK + "back";
    private static final String K_NEXT = MARK + "next";
    private static final String K_PREV = MARK + "prev";
    private static final String K_SEARCH = MARK + "search";
    private static final String K_IMPORT = MARK + "import";
    private static final String K_STATUS = MARK + "status";

    private static final String VIEW_ROOT = "";
    private static final String VIEW_FAVORITES = "fav";
    private static final String VIEW_RECENT = "recent";
    private static final String VIEW_ALL = "all";
    private static final String VIEW_GROUP = "g:";
    private static final String VIEW_QUERY = "q:";

    private static final int PAGE_SIZE = 120;

    private static String sView = VIEW_ROOT; // what the section shows now
    private static int sPage;

    private IptvSection() {
    }

    // ------------------------------------------------------------------ cards

    /** Called by the browse screen every time the section has to be (re)drawn. */
    public static synchronized List<Video> buildItems(Context context) {
        IptvPlaylist library = IptvStore.instance(context).load();
        IptvUserData user = IptvUserData.instance(context);
        List<Video> items = new ArrayList<>();

        if (sView.isEmpty()) {
            buildRoot(context, library, user, items);
        } else {
            buildChannelList(context, library, user, items);
        }

        return items;
    }

    private static void buildRoot(Context context, IptvPlaylist library, IptvUserData user, List<Video> items) {
        Map<String, IptvChannel> byUrl = index(library);

        addImportStatusCard(context, items);

        if (!library.isEmpty()) {
            items.add(card("\uD83D\uDD0D " + context.getString(R.string.iptv_section_search), null, K_SEARCH, null));
        }

        items.add(card("\u2795 " + context.getString(R.string.iptv_section_import),
                context.getString(library.isEmpty() ? R.string.iptv_section_empty_desc : R.string.iptv_section_import_desc),
                K_IMPORT, null));

        int favorites = resolve(user.favorites(), byUrl).size();
        if (favorites > 0) {
            items.add(folderCard(context, "\u2B50 " + context.getString(R.string.iptv_favorites), favorites, VIEW_FAVORITES));
        }

        int recent = resolve(user.recents(), byUrl).size();
        if (recent > 0) {
            items.add(folderCard(context, "\uD83D\uDD52 " + context.getString(R.string.iptv_recent), recent, VIEW_RECENT));
        }

        if (!library.isEmpty()) {
            items.add(folderCard(context, "\uD83D\uDCFA " + context.getString(R.string.iptv_all_channels), library.size(), VIEW_ALL));

            for (Map.Entry<String, List<IptvChannel>> group : library.groups().entrySet()) {
                String name = group.getKey();
                String title = name.isEmpty() ? context.getString(R.string.iptv_ungrouped) : name;
                items.add(folderCard(context, "\uD83D\uDCC2 " + title, group.getValue().size(), VIEW_GROUP + name));
            }
        }
    }

    private static void buildChannelList(Context context, IptvPlaylist library, IptvUserData user, List<Video> items) {
        List<IptvChannel> channels = channelsOfCurrentView(library, user);

        int pages = Math.max(1, (channels.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        sPage = Math.max(0, Math.min(sPage, pages - 1));

        String second = channels.size() + (pages > 1 ? "  (" + (sPage + 1) + "/" + pages + ")" : "");
        items.add(card("\u2B05 " + context.getString(R.string.iptv_back) + " - " + viewTitle(context), second, K_BACK, null));

        if (channels.isEmpty() && sView.startsWith(VIEW_QUERY)) {
            items.add(card(context.getString(R.string.iptv_search_empty), sView.substring(VIEW_QUERY.length()), K_BACK, null));
            return;
        }

        if (sPage > 0) {
            items.add(card("\u25C0 " + context.getString(R.string.iptv_prev_page), String.valueOf(sPage), K_PREV, null));
        }

        int from = sPage * PAGE_SIZE;
        int to = Math.min(channels.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) {
            IptvChannel c = channels.get(i);
            String name = c.name == null || c.name.trim().isEmpty() ? c.url : c.name;
            String prefix = user.isFavorite(c.url) ? "\u2B50 " : "";
            String group = c.group == null || c.group.isEmpty() ? null : c.group;
            items.add(card(prefix + name, group, K_PLAY + c.key(), c.logo));
        }

        if (to < channels.size()) {
            items.add(card(context.getString(R.string.iptv_next_page) + " \u25B6", String.valueOf(sPage + 2), K_NEXT, null));
        }
    }

    private static List<IptvChannel> channelsOfCurrentView(IptvPlaylist library, IptvUserData user) {
        if (sView.equals(VIEW_ALL)) {
            return library.getChannels();
        }
        if (sView.equals(VIEW_FAVORITES)) {
            return resolve(user.favorites(), index(library));
        }
        if (sView.equals(VIEW_RECENT)) {
            return resolve(user.recents(), index(library));
        }
        if (sView.startsWith(VIEW_GROUP)) {
            List<IptvChannel> list = library.groups().get(sView.substring(VIEW_GROUP.length()));
            return list != null ? list : new ArrayList<IptvChannel>();
        }
        if (sView.startsWith(VIEW_QUERY)) {
            String query = sView.substring(VIEW_QUERY.length()).toLowerCase(Locale.ROOT);
            List<IptvChannel> found = new ArrayList<>();
            for (IptvChannel c : library.getChannels()) {
                if (contains(c.name, query) || contains(c.group, query)) {
                    found.add(c);
                }
            }
            return found;
        }
        return new ArrayList<>();
    }

    private static String viewTitle(Context context) {
        if (sView.equals(VIEW_ALL)) {
            return context.getString(R.string.iptv_all_channels);
        }
        if (sView.equals(VIEW_FAVORITES)) {
            return context.getString(R.string.iptv_favorites);
        }
        if (sView.equals(VIEW_RECENT)) {
            return context.getString(R.string.iptv_recent);
        }
        if (sView.startsWith(VIEW_GROUP)) {
            String name = sView.substring(VIEW_GROUP.length());
            return name.isEmpty() ? context.getString(R.string.iptv_ungrouped) : name;
        }
        if (sView.startsWith(VIEW_QUERY)) {
            return "\uD83D\uDD0D " + sView.substring(VIEW_QUERY.length());
        }
        return "";
    }

    private static boolean contains(String text, String lowerQuery) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(lowerQuery);
    }

    private static Map<String, IptvChannel> index(IptvPlaylist library) {
        Map<String, IptvChannel> map = new LinkedHashMap<>();
        for (IptvChannel c : library.getChannels()) {
            map.put(c.key(), c);
        }
        return map;
    }

    /** Urls (in the given order) -> channels that still exist in the library. */
    private static List<IptvChannel> resolve(List<String> urls, Map<String, IptvChannel> byUrl) {
        List<IptvChannel> result = new ArrayList<>();
        for (String url : urls) {
            IptvChannel c = byUrl.get(url);
            if (c != null) {
                result.add(c);
            }
        }
        return result;
    }

    private static Video folderCard(Context context, String title, int count, String view) {
        return card(title, context.getString(R.string.iptv_channels_count, count), K_OPEN + view, null);
    }

    private static Video card(String title, CharSequence secondTitle, String key, String imageUrl) {
        Video video = new Video();
        video.title = title;
        video.secondTitle = secondTitle;
        video.reloadPageKey = key;
        video.cardImageUrl = imageUrl;
        return video;
    }

    // ------------------------------------------------------------------ clicks

    /** @return true if the card belongs to this section and was handled */
    public static boolean handleClick(Context context, Video item) {
        String key = keyOf(item);
        if (key == null) {
            return false;
        }

        if (key.startsWith(K_PLAY)) {
            IptvChannel channel = findChannel(context, key.substring(K_PLAY.length()));
            if (channel != null) {
                // the whole opened list (not only the visible page) becomes the zapping queue of the built-in player
                play(context, channel, channelsOfCurrentView(IptvStore.instance(context).load(), IptvUserData.instance(context)));
            } else {
                MessageHelpers.showMessage(context, R.string.iptv_failed);
            }
        } else if (key.startsWith(K_OPEN)) {
            sView = key.substring(K_OPEN.length());
            sPage = 0;
            redraw(context);
        } else if (key.equals(K_BACK)) {
            sView = VIEW_ROOT;
            sPage = 0;
            redraw(context);
        } else if (key.equals(K_NEXT)) {
            sPage++;
            redraw(context);
        } else if (key.equals(K_PREV)) {
            sPage = Math.max(0, sPage - 1);
            redraw(context);
        } else if (key.equals(K_SEARCH)) {
            SimpleEditDialog.show(context, context.getString(R.string.iptv_section_search),
                    context.getString(R.string.iptv_section_search_hint), "", value -> {
                        if (value == null || value.trim().isEmpty()) {
                            return false;
                        }
                        sView = VIEW_QUERY + value.trim();
                        sPage = 0;
                        redraw(context);
                        return true;
                    });
        } else if (key.equals(K_IMPORT)) {
            IptvSettingsPresenter.instance(context).show();
        } else if (key.equals(K_STATUS)) {
            onStatusClicked(context);
        }

        return true; // any "iptv:" card is ours, even an unknown one
    }

    /** @return true if the card belongs to this section and was handled */
    public static boolean handleLongClick(Context context, Video item) {
        String key = keyOf(item);
        if (key == null) {
            return false;
        }

        if (key.startsWith(K_PLAY)) {
            IptvChannel channel = findChannel(context, key.substring(K_PLAY.length()));
            if (channel != null) {
                showChannelMenu(context, channel);
            }
        } else if (key.startsWith(K_OPEN + VIEW_GROUP)) {
            showGroupMenu(context, key.substring((K_OPEN + VIEW_GROUP).length()));
        } else if (key.equals(K_OPEN + VIEW_FAVORITES)) {
            confirm(context, context.getString(R.string.iptv_clear_favorites), () -> {
                IptvUserData.instance(context).clearFavorites();
                redraw(context);
            });
        } else if (key.equals(K_OPEN + VIEW_RECENT)) {
            confirm(context, context.getString(R.string.iptv_clear_recent), () -> {
                IptvUserData.instance(context).clearRecents();
                redraw(context);
            });
        }

        return true;
    }

    private static String keyOf(Video item) {
        return item != null && item.reloadPageKey != null && item.reloadPageKey.startsWith(MARK) ? item.reloadPageKey : null;
    }

    private static IptvChannel findChannel(Context context, String url) {
        for (IptvChannel c : IptvStore.instance(context).load().getChannels()) {
            if (c.key().equals(url)) {
                return c;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ menus

    private static void showChannelMenu(Context context, IptvChannel channel) {
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        IptvUserData user = IptvUserData.instance(context);
        boolean favorite = user.isFavorite(channel.url);

        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_play), option -> {
            dialog.closeDialog();
            play(context, channel);
        }));
        dialog.appendSingleButton(UiOptionItem.from(
                context.getString(favorite ? R.string.iptv_remove_favorite : R.string.iptv_add_favorite), option -> {
                    user.toggleFavorite(channel.url);
                    dialog.closeDialog();
                    redraw(context);
                }));
        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_copy_link), option -> {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("iptv", channel.url));
                MessageHelpers.showMessage(context, R.string.msg_done);
            }
            dialog.closeDialog();
        }));
        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_remove_channel), option ->
                confirm(context, context.getString(R.string.iptv_remove_channel), () -> {
                    try {
                        IptvStore.instance(context).removeChannel(channel.url);
                    } catch (IOException e) {
                        MessageHelpers.showMessage(context, R.string.iptv_failed);
                    }
                    redraw(context);
                })));
        dialog.showDialog(channel.name == null || channel.name.isEmpty() ? channel.url : channel.name);
    }

    private static void showGroupMenu(Context context, String group) {
        String title = group.isEmpty() ? context.getString(R.string.iptv_ungrouped) : group;
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);

        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_remove_group), option ->
                confirm(context, context.getString(R.string.iptv_remove_group) + ": " + title, () -> {
                    try {
                        IptvStore.instance(context).removeGroup(group);
                    } catch (IOException e) {
                        MessageHelpers.showMessage(context, R.string.iptv_failed);
                    }
                    if (sView.equals(VIEW_GROUP + group)) {
                        sView = VIEW_ROOT;
                    }
                    redraw(context);
                })));
        dialog.showDialog(title);
    }

    private static void confirm(Context context, String title, Runnable action) {
        AppDialogUtil.showConfirmationDialog(context, title, () -> {
            action.run();
            AppDialogPresenter.instance(context).closeDialog();
        });
    }

    // ------------------------------------------------------------------ playback

    /** Plays a single channel with the player chosen in Settings > IPTV. */
    public static void play(Context context, IptvChannel channel) {
        play(context, channel, null);
    }

    /**
     * Plays the channel with the player chosen in Settings > IPTV (built-in by default).
     * @param queue channels the built-in player can zap through; may be null
     */
    public static void play(Context context, IptvChannel channel, List<IptvChannel> queue) {
        if (IptvPrefs.instance(context).getPlayerMode() == IptvPrefs.PLAYER_EXTERNAL) {
            if (StalkerLink.isStalker(channel.url)) {
                playExternalPortal(context, channel);
            } else {
                playExternal(context, channel, channel.url);
            }
            return;
        }

        List<IptvChannel> list = queue;
        int index = -1;
        if (list != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).key().equals(channel.key())) {
                    index = i;
                    break;
                }
            }
        }
        if (index == -1) {
            list = Collections.singletonList(channel);
            index = 0;
        }
        IptvPlayerActivity.start(context, list, index);
    }

    /** Portal channel + external player: the real stream link must be requested from the portal first. */
    private static void playExternalPortal(Context context, IptvChannel channel) {
        MessageHelpers.showMessage(context, R.string.iptv_player_loading);
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            Handler main = new Handler(Looper.getMainLooper());
            try {
                StalkerClient.Resolved r = StalkerClient.resolve(new HttpIptvFetcher(IptvPrefs.instance(app).load()), channel.url, false);
                IptvChannel real = new IptvChannel(channel.name, r.url);
                real.userAgent = r.userAgent;
                main.post(() -> playExternal(context, real, channel.url));
            } catch (Throwable t) {
                String detail = t.getMessage() == null || t.getMessage().isEmpty() ? "" : "\n" + t.getMessage();
                main.post(() -> MessageHelpers.showLongMessage(context, context.getString(R.string.iptv_player_error) + detail));
            }
        }, "iptv-portal-link").start();
    }

    /**
     * Opens the stream in a player app installed on the device (VLC, MX Player ...).
     * @param recentKey what is stored in "recently watched" (the library url, not a temporary portal link)
     */
    private static void playExternal(Context context, IptvChannel channel, String recentKey) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            String ext = IptvUrls.extension(channel.url);
            intent.setDataAndType(Uri.parse(channel.url), ext.equals("m3u8") ? "application/x-mpegURL" : "video/*");
            if (channel.userAgent != null || channel.referrer != null) {
                List<String> headers = new ArrayList<>(); // MX Player style extra, ignored by other players
                if (channel.userAgent != null) { headers.add("User-Agent"); headers.add(channel.userAgent); }
                if (channel.referrer != null) { headers.add("Referer"); headers.add(channel.referrer); }
                intent.putExtra("headers", headers.toArray(new String[0]));
            }
            intent.putExtra("title", channel.name);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(Intent.createChooser(intent, channel.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            IptvUserData.instance(context).addRecent(recentKey);
        } catch (ActivityNotFoundException e) {
            MessageHelpers.showMessage(context, R.string.iptv_no_player);
        }
    }

    // ------------------------------------------------------------------ refresh

    // ------------------------------------------------------------------ import progress

    private static final long MIN_REDRAW_MS = 4000;
    private static String sLastDrawnState = "";
    private static long sLastDrawMs;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Context> sContextRef = new WeakReference<>(null);

    /** Called when an import starts: remembers where to show the progress / the final message. */
    public static void attachJob(Context context) {
        sContextRef = new WeakReference<>(context);
        IptvImportJob.instance().setListener(finished -> MAIN.post(() -> onJobChanged(finished)));
    }

    private static void onJobChanged(boolean finished) {
        Context context = sContextRef.get();
        if (context == null) {
            return;
        }

        IptvImportJob.Snapshot snap = IptvImportJob.instance().snapshot();

        if (finished && snap.analyze) {
            IptvImportJob job = IptvImportJob.instance();
            if (snap.cancelled) {
                MessageHelpers.showLongMessage(context, context.getString(R.string.iptv_import_cancelled));
                job.takePending();
                onLibraryChanged(context);
                return;
            }
            IptvPlaylist found = job.takePending();
            if (found.isEmpty()) {
                MessageHelpers.showLongMessage(context, context.getString(R.string.iptv_nothing_found)
                        + (snap.failed > 0 ? "\n" + context.getString(R.string.iptv_import_failed_count, snap.failed) : ""));
                onLibraryChanged(context);
                return;
            }
            job.dismissResult();
            onLibraryChanged(context);
            IptvPreviewPresenter.show(context, found);
        } else if (finished) {
            String message = snap.cancelled
                    ? context.getString(R.string.iptv_import_cancelled)
                    : context.getString(R.string.iptv_import_result, snap.added, IptvStore.instance(context).load().size());
            if (snap.failed > 0) {
                message += "\n" + context.getString(R.string.iptv_import_failed_count, snap.failed);
            }
            MessageHelpers.showLongMessage(context, message);
            onLibraryChanged(context); // back to the root + redraw (the "done" card is there)
        } else if (sView.isEmpty()) {
            // every redraw reloads the whole screen (visible flicker): redraw only when what the card shows changed,
            // and not more often than every few seconds
            String state = snap.done + "/" + snap.total + ":" + snap.channels + ":" + snap.current;
            long now = System.currentTimeMillis();
            if (state.equals(sLastDrawnState) || now - sLastDrawMs < MIN_REDRAW_MS) {
                return;
            }
            BrowseSection current = BrowsePresenter.instance(context).getCurrentSection();
            if (current != null && current.getId() == SECTION_ID) {
                sLastDrawnState = state;
                sLastDrawMs = now;
                redraw(context); // refresh the progress card
            }
        }
    }

    private static void addImportStatusCard(Context context, List<Video> items) {
        IptvImportJob.Snapshot snap = IptvImportJob.instance().snapshot();

        if (snap.running) {
            String title = "\u23F3 " + context.getString(snap.analyze ? R.string.iptv_analyze_title : R.string.iptv_progress_title, snap.done, snap.total) + "  " + bar(snap.percent());
            String current = snap.current.length() > 70 ? snap.current.substring(0, 70) + "\u2026" : snap.current;
            String second = context.getString(R.string.iptv_progress_desc, snap.channels, formatTime(snap.elapsedMs))
                    + (current.isEmpty() ? "" : "\n" + current);
            items.add(card(title, second, K_STATUS, null));
        } else if (snap.hasResult) {
            String title = (snap.cancelled ? "\u26D4 " + context.getString(R.string.iptv_import_cancelled)
                    : "\u2705 " + context.getString(R.string.iptv_done_title, snap.added));
            String second = context.getString(R.string.iptv_done_desc, snap.done, snap.failed, formatTime(snap.elapsedMs));
            items.add(card(title, second, K_STATUS, null));
        }
    }

    private static String bar(int percent) {
        int filled = Math.max(0, Math.min(10, percent / 10));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append(i < filled ? '\u2588' : '\u2591');
        }
        return sb.append(' ').append(percent).append('%').toString();
    }

    private static String formatTime(long ms) {
        long sec = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
    }

    private static void onStatusClicked(Context context) {
        final IptvImportJob job = IptvImportJob.instance();

        if (job.isRunning()) {
            confirm(context, context.getString(R.string.iptv_cancel_import), () -> {
                job.cancel();
                redraw(context);
            });
            return;
        }

        IptvImportJob.Snapshot snap = job.snapshot();
        job.dismissResult();
        redraw(context);

        if (!snap.messages.isEmpty()) { // details of the files that failed
            AppDialogPresenter dialog = AppDialogPresenter.instance(context);
            List<com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem> options = new ArrayList<>();
            for (String m : snap.messages) {
                options.add(UiOptionItem.from(m, option -> { }));
            }
            dialog.appendStringsCategory(context.getString(R.string.iptv_details), options);
            dialog.showDialog(context.getString(R.string.iptv_title));
        }
    }

    private static void redraw(Context context) {
        BrowsePresenter.instance(context).refresh(false);
    }

    /** The library was changed from outside (import / clear): redraw only if the user is looking at this section. */
    public static synchronized void onLibraryChanged(Context context) {
        sView = VIEW_ROOT;
        sPage = 0;

        BrowseSection current = BrowsePresenter.instance(context).getCurrentSection();
        if (current != null && current.getId() == SECTION_ID) {
            redraw(context);
        }
    }
}
