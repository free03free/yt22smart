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
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvAlternatives;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvHealth;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvHealthChecker;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvImportJob;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvOptions;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvRefresh;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvNames;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvPlaylist;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvPrefs;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvStore;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvUrls;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvUserData;
import com.liskovsoft.smartyoutubetv2.common.iptv.StalkerClient;
import com.liskovsoft.smartyoutubetv2.common.iptv.StalkerDiagnosis;
import com.liskovsoft.smartyoutubetv2.common.iptv.StalkerLink;
import com.liskovsoft.smartyoutubetv2.common.misc.IptvManagerActivity;
import com.liskovsoft.smartyoutubetv2.common.misc.IptvPlayerActivity;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "IPTV" sidebar section. Works like a small file manager made of cards:
 * <pre>
 *   root:   [search] [add channels] [favorites] [recent] [all channels] [group 1] [group 2] ...
 *   inside: [back] [channel] [channel] ... [next page]
 * </pre>
 * Click a channel to play it, long press it for the menu (play, move up/down, favorite, other sources, copy link, remove).
 * "Select channels" turns taps into check marks so several channels can be deleted together; "move up / move down" in
 * the channel menu reorders it against its neighbour in the view it was opened from (hidden while sorted A-Z, since
 * manual order exists but would not be visible there).
 * The state (opened folder / page / selection) lives in memory only; every card is a plain {@link Video}
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
    private static final String K_MANAGE = MARK + "manage";
    private static final String K_SAT = MARK + "sat";
    private static final String K_STATUS = MARK + "status";
    private static final String K_SORT = MARK + "sort";
    private static final String K_CHECK = MARK + "check";
    private static final String K_CHECKALL = MARK + "checkall";
    private static final String K_HIDEDEAD = MARK + "hidedead";
    private static final String K_SELECT = MARK + "select";
    private static final String K_DELSEL = MARK + "delsel";
    private static final String K_DIAG = MARK + "diag";
    private static final String K_DIAGALL = MARK + "diagall";
    private static final String K_DIAGSTART = MARK + "diagstart";
    private static final String K_DIAGINFO = MARK + "diaginfo";
    private static final String K_DIAGP = MARK + "diagp:";
    private static final String K_DIAGREPORT = MARK + "diagreport";
    private static final String K_SELALL = MARK + "selall";
    private static final String K_MOVESELTOP = MARK + "moveseltop";
    private static final String K_MOVESELBOTTOM = MARK + "moveselbottom";
    private static final String K_MOVEUP = MARK + "moveup:";
    private static final String K_MOVEDOWN = MARK + "movedown:";

    private static final String VIEW_ROOT = "";
    private static final String VIEW_FAVORITES = "fav";
    private static final String VIEW_RECENT = "recent";
    private static final String VIEW_ALL = "all";
    private static final String VIEW_DIAG = "diag";
    private static final String VIEW_GROUP = "g:";
    private static final String VIEW_LIST = "l:";     // the groups of one list (portal / server / file)
    private static final String VIEW_LISTALL = "la:";  // every channel of one list
    private static final String SEP = "\u0001";         // "g:" + list + SEP + group
    private static final String VIEW_QUERY = "q:";

    private static final int PAGE_SIZE = 120;

    private static String sView = VIEW_ROOT; // what the section shows now
    private static int sPage;
    private static final Set<String> sDiagSelected = new LinkedHashSet<>(); // portals ticked for the diagnosis
    private static final Map<String, StalkerClient.PortalInfo> sPortalInfo = Collections.synchronizedMap(new LinkedHashMap<String, StalkerClient.PortalInfo>());
    private static volatile boolean sInfoLoading;
    private static boolean sSelectMode;       // multi-select: tap a channel to tick it instead of playing it
    private static final Set<String> sSelected = new LinkedHashSet<>(); // selected channel urls, kept across a redraw

    private IptvSection() {
    }

    /** Called whenever the open view changes (a new folder, back, search\u2026): a selection made in one view makes\n     *  no sense in another. */
    private static void resetSelection() {
        sSelectMode = false;
        sSelected.clear();
    }

    // ------------------------------------------------------------------ cards

    /** Called by the browse screen every time the section has to be (re)drawn. */
    public static synchronized List<Video> buildItems(Context context) {
        IptvPlaylist library = IptvStore.instance(context).load();
        IptvUserData user = IptvUserData.instance(context);
        List<Video> items = new ArrayList<>();

        if (sView.isEmpty()) {
            buildRoot(context, library, user, items);
        } else if (sView.equals(VIEW_DIAG)) {
            buildDiagView(context, library, items);
        } else if (sView.startsWith(VIEW_LIST)) {
            buildListGroups(context, library, items);
        } else {
            buildChannelList(context, library, user, items);
        }

        return items;
    }

    private static void buildRoot(Context context, IptvPlaylist library, IptvUserData user, List<Video> items) {
        Map<String, IptvChannel> byUrl = index(library);

        // always the first card: the numbered channel manager (works, empty, even before the first import)
        items.add(card("\uD83D\uDCCB " + context.getString(R.string.iptv_open_manager),
                context.getString(R.string.iptv_open_manager_desc), K_MANAGE, null));

        // the receiver / satellite shortcut (only when switched on in Settings > IPTV > Receiver / satellite)
        if (IptvPrefs.instance(context).getSatMode() != 0) {
            items.add(card("\uD83D\uDCE1 " + context.getString(R.string.iptv_sat_card),
                    context.getString(R.string.iptv_sat_card_desc), K_SAT, null));
        }

        addImportStatusCard(context, items);

        if (!library.isEmpty()) {
            items.add(card("\uD83D\uDD0D " + context.getString(R.string.iptv_section_search), null, K_SEARCH, null));
        }

        items.add(card("\u2795 " + context.getString(R.string.iptv_section_import),
                context.getString(library.isEmpty() ? R.string.iptv_section_empty_desc : R.string.iptv_section_import_desc),
                K_IMPORT, null));

        if (hasPortalChannels(library)) {
            StalkerDiagnosis diag = StalkerDiagnosis.instance();
            if (diag.isRunning()) {
                items.add(card("\u23F9 " + context.getString(R.string.iptv_diag_stop, diag.done(), diag.total()), null, K_DIAG, null));
            } else {
                items.add(card("\uD83E\uDE7A " + context.getString(R.string.iptv_diag_card), context.getString(R.string.iptv_diag_desc), K_DIAG, null));
                if (diag.lastReport() != null) {
                    items.add(card("\uD83D\uDCCB " + context.getString(R.string.iptv_diag_last), null, K_DIAGREPORT, null));
                }
            }
        }

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

            Map<String, List<IptvChannel>> lists = library.lists();
            if (lists.size() > 1) {
                // every list (portal / server / file) keeps its own channels and groups
                for (Map.Entry<String, List<IptvChannel>> list : lists.entrySet()) {
                    String name = list.getKey();
                    String title = name.isEmpty() ? context.getString(R.string.iptv_other_list) : name;
                    items.add(folderCard(context, "\uD83D\uDCE1 " + title, list.getValue().size(), VIEW_LIST + name));
                }
                return;
            }
            for (Map.Entry<String, List<IptvChannel>> group : library.groups().entrySet()) {
                String name = group.getKey();
                String title = name.isEmpty() ? context.getString(R.string.iptv_ungrouped) : name;
                items.add(folderCard(context, "\uD83D\uDCC2 " + title, group.getValue().size(), VIEW_GROUP + name));
            }
        }
    }

    private static void buildListGroups(Context context, IptvPlaylist library, List<Video> items) {
        String list = sView.substring(VIEW_LIST.length());
        List<IptvChannel> inList = library.lists().get(list);
        if (inList == null) {
            inList = new ArrayList<>();
        }
        String title = list.isEmpty() ? context.getString(R.string.iptv_other_list) : list;
        items.add(card("\u2B05 " + context.getString(R.string.iptv_back) + " - " + title, String.valueOf(inList.size()), K_BACK, null));
        items.add(folderCard(context, "\uD83D\uDCFA " + context.getString(R.string.iptv_all_channels), inList.size(), VIEW_LISTALL + list));

        Map<String, List<IptvChannel>> groups = new LinkedHashMap<>();
        for (IptvChannel c : inList) {
            String g = c.group == null ? "" : c.group;
            List<IptvChannel> bucket = groups.get(g);
            if (bucket == null) {
                bucket = new ArrayList<>();
                groups.put(g, bucket);
            }
            bucket.add(c);
        }
        for (Map.Entry<String, List<IptvChannel>> g : groups.entrySet()) {
            String name = g.getKey();
            String shown = name.isEmpty() ? context.getString(R.string.iptv_ungrouped) : IptvNames.groupInList(name, list);
            items.add(folderCard(context, "\uD83D\uDCC2 " + shown, g.getValue().size(), VIEW_GROUP + list + SEP + name));
        }
    }

    private static String displayName(IptvChannel c, IptvPrefs prefs) {
        String name = c.name == null || c.name.trim().isEmpty() ? c.url : c.name;
        return prefs.isHidePrefix() ? IptvNames.stripPrefix(name) : name;
    }

    private static String dot(int status) {
        return status == IptvHealth.OK ? "\u25CF " : status == IptvHealth.BAD ? "\uD83D\uDD34 " : "\u26AA ";
    }

    /** The channels of the open view as shown: sorted, without the dead ones when they are hidden. */
    private static List<IptvChannel> viewChannels(Context context, IptvPlaylist library, IptvUserData user) {
        IptvPrefs prefs = IptvPrefs.instance(context);
        List<IptvChannel> channels = channelsOfCurrentView(library, user, prefs.isSortAz());
        if (prefs.isHideDead() && !channels.isEmpty()) {
            IptvHealth health = IptvHealth.instance(context);
            List<IptvChannel> alive = new ArrayList<>();
            for (IptvChannel c : channels) {
                if (health.status(c.key()) != IptvHealth.BAD) {
                    alive.add(c);
                }
            }
            return alive;
        }
        return channels;
    }

    /** Checks the channels in the background (or stops a running check). The dots appear when it finishes. */
    private static void runHealthCheck(Context context, List<IptvChannel> channels) {
        final IptvHealthChecker checker = IptvHealthChecker.instance();
        if (checker.isRunning()) {
            checker.cancel();
            MessageHelpers.showMessage(context, R.string.iptv_check_stopping);
            return;
        }
        if (channels.isEmpty()) {
            return;
        }
        final Context app = context.getApplicationContext();
        IptvOptions options = IptvPrefs.instance(context).load();
        boolean started = checker.start(new ArrayList<>(channels), new HttpIptvFetcher(options.withTimeouts(6_000, 12_000)),
                options.userAgent, IptvHealth.instance(context), 4, new IptvHealthChecker.Listener() {
                    private long mLastToast;

                    @Override
                    public void onProgress(int done, int total, int ok, int bad) {
                        long now = System.currentTimeMillis();
                        if (now - mLastToast >= 8000 && isAppInForeground(app)) {
                            mLastToast = now;
                            MAIN.post(() -> MessageHelpers.showMessage(app, app.getString(R.string.iptv_check_progress, done, total, ok, bad)));
                        }
                    }

                    @Override
                    public void onFinished(int ok, int bad, boolean cancelled) {
                        MAIN.post(() -> {
                            if (isAppInForeground(app)) {
                                MessageHelpers.showLongMessage(app, app.getString(R.string.iptv_check_done, ok, bad));
                            }
                            BrowseSection current = BrowsePresenter.instance(app).getCurrentSection();
                            if (current != null && current.getId() == SECTION_ID) {
                                redraw(app);
                            }
                        });
                    }
                });
        if (started) {
            MessageHelpers.showMessage(context, context.getString(R.string.iptv_check_started, channels.size()));
            redraw(context); // the card turns into "stop"
        }
    }

    private static void buildChannelList(Context context, IptvPlaylist library, IptvUserData user, List<Video> items) {
        List<IptvChannel> channels = viewChannels(context, library, user);
        IptvHealth health = IptvHealth.instance(context);

        int pages = Math.max(1, (channels.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        sPage = Math.max(0, Math.min(sPage, pages - 1));

        String second = channels.size() + (pages > 1 ? "  (" + (sPage + 1) + "/" + pages + ")" : "");
        items.add(card("\u2B05 " + context.getString(R.string.iptv_back) + " - " + viewTitle(context), second, K_BACK, null));

        if (channels.isEmpty() && sView.startsWith(VIEW_QUERY)) {
            items.add(card(context.getString(R.string.iptv_search_empty), sView.substring(VIEW_QUERY.length()), K_BACK, null));
            return;
        }

        IptvPrefs prefs = IptvPrefs.instance(context);
        if (!sView.equals(VIEW_FAVORITES) && !sView.equals(VIEW_RECENT) && channels.size() > 1) {
            items.add(card("\u21C5 " + context.getString(prefs.isSortAz() ? R.string.iptv_sort_az : R.string.iptv_sort_original),
                    context.getString(R.string.iptv_sort_toggle), K_SORT, null));
        }

        if (!channels.isEmpty()) {
            if (sSelectMode) {
                items.add(card("\u2716 " + context.getString(R.string.iptv_select_exit, sSelected.size()),
                        context.getString(R.string.iptv_select_hint), K_SELECT, null));
                boolean allTicked = sSelected.size() >= channels.size();
                items.add(card((allTicked ? "\u2610 " : "\u2611 ") + context.getString(allTicked ? R.string.iptv_select_none : R.string.iptv_select_all, channels.size()),
                        null, K_SELALL, null));
                if (!sSelected.isEmpty() && !sView.equals(VIEW_RECENT)) {
                    items.add(card("\u23EB " + context.getString(R.string.iptv_select_move_top, sSelected.size()),
                            context.getString(R.string.iptv_select_move_hint), K_MOVESELTOP, null));
                    items.add(card("\u23EC " + context.getString(R.string.iptv_select_move_bottom, sSelected.size()),
                            context.getString(R.string.iptv_select_move_hint), K_MOVESELBOTTOM, null));
                }
                if (!sSelected.isEmpty()) {
                    items.add(card("\uD83D\uDDD1 " + context.getString(R.string.iptv_select_delete, sSelected.size()),
                            null, K_DELSEL, null));
                }
            } else {
                items.add(card("\u2611 " + context.getString(R.string.iptv_select_enter),
                        context.getString(R.string.iptv_select_enter_desc), K_SELECT, null));
            }
        }

        // availability: a dot next to every channel instead of opening each one and waiting
        String legend = context.getString(R.string.iptv_check_legend);
        IptvHealthChecker checker = IptvHealthChecker.instance();
        if (checker.isRunning()) {
            items.add(card("\u23F9 " + context.getString(R.string.iptv_check_stop, checker.done(), checker.total()), legend, K_CHECK, null));
        } else if (!channels.isEmpty()) {
            items.add(card("\uD83D\uDD0E " + context.getString(R.string.iptv_check_page), legend, K_CHECK, null));
            items.add(card("\uD83D\uDD0E " + context.getString(R.string.iptv_check_all, channels.size()), legend, K_CHECKALL, null));
        }
        items.add(card("\uD83D\uDC41 " + context.getString(prefs.isHideDead() ? R.string.iptv_hide_dead_on : R.string.iptv_hide_dead_off),
                context.getString(R.string.iptv_hide_dead_toggle), K_HIDEDEAD, null));

        if (sPage > 0) {
            items.add(card("\u25C0 " + context.getString(R.string.iptv_prev_page), String.valueOf(sPage), K_PREV, null));
        }

        int from = sPage * PAGE_SIZE;
        int to = Math.min(channels.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) {
            IptvChannel c = channels.get(i);
            String name = displayName(c, prefs);
            String mark = sSelectMode ? (sSelected.contains(c.key()) ? "\u2611 " : "\u2610 ") : "";
            String prefix = mark + dot(health.status(c.key())) + (user.isFavorite(c.url) ? "\u2B50 " : "");
            String group = c.group == null || c.group.isEmpty() ? null : IptvNames.groupInList(c.group, c.list);
            Video ch = card(prefix + name, group, K_PLAY + c.key(), c.logo);
            ch.isIptvChecked = sSelectMode && sSelected.contains(c.key());
            if (ch.isIptvChecked) {
                ch.badge = context.getString(R.string.iptv_selected_badge);
            }
            items.add(ch);
        }

        if (to < channels.size()) {
            items.add(card(context.getString(R.string.iptv_next_page) + " \u25B6", String.valueOf(sPage + 2), K_NEXT, null));
        }
    }

    private static List<IptvChannel> channelsOfCurrentView(IptvPlaylist library, IptvUserData user) {
        return channelsOfCurrentView(library, user, false);
    }

    private static List<IptvChannel> channelsOfCurrentView(IptvPlaylist library, IptvUserData user, boolean sortAz) {
        List<IptvChannel> list = unsortedChannelsOfCurrentView(library, user);
        if (sortAz && !sView.equals(VIEW_FAVORITES) && !sView.equals(VIEW_RECENT)) {
            list = new ArrayList<>(list);
            Collections.sort(list, (a, b) -> com.liskovsoft.smartyoutubetv2.common.iptv.ArabicSort.COMPARATOR.compare(String.valueOf(a.name), String.valueOf(b.name)));
        }
        return list;
    }

    private static List<IptvChannel> unsortedChannelsOfCurrentView(IptvPlaylist library, IptvUserData user) {
        if (sView.equals(VIEW_ALL)) {
            return library.getChannels();
        }
        if (sView.equals(VIEW_FAVORITES)) {
            return resolve(user.favorites(), index(library));
        }
        if (sView.equals(VIEW_RECENT)) {
            return resolve(user.recents(), index(library));
        }
        if (sView.startsWith(VIEW_LISTALL)) {
            List<IptvChannel> inList = library.lists().get(sView.substring(VIEW_LISTALL.length()));
            return inList != null ? inList : new ArrayList<IptvChannel>();
        }
        if (sView.startsWith(VIEW_GROUP)) {
            String spec = sView.substring(VIEW_GROUP.length());
            int sep = spec.indexOf(SEP);
            if (sep >= 0) { // a group inside one list
                String list = spec.substring(0, sep);
                String group = spec.substring(sep + 1);
                List<IptvChannel> found = new ArrayList<>();
                for (IptvChannel c : library.getChannels()) {
                    if ((c.list == null ? "" : c.list).equals(list) && (c.group == null ? "" : c.group).equals(group)) {
                        found.add(c);
                    }
                }
                return found;
            }
            List<IptvChannel> inGroup = library.groups().get(spec);
            return inGroup != null ? inGroup : new ArrayList<IptvChannel>();
        }
        if (sView.startsWith(VIEW_QUERY)) {
            String query = sView.substring(VIEW_QUERY.length()).toLowerCase(Locale.ROOT);
            List<IptvChannel> found = new ArrayList<>();
            for (IptvChannel c : library.getChannels()) {
                if (contains(c.name, query) || contains(c.group, query) || contains(c.list, query)) {
                    found.add(c);
                }
            }
            return found;
        }
        return new ArrayList<>();
    }

    private static String viewTitle(Context context) {
        if (sView.equals(VIEW_DIAG)) {
            return context.getString(R.string.iptv_diag_card);
        }
        if (sView.equals(VIEW_ALL)) {
            return context.getString(R.string.iptv_all_channels);
        }
        if (sView.equals(VIEW_FAVORITES)) {
            return context.getString(R.string.iptv_favorites);
        }
        if (sView.equals(VIEW_RECENT)) {
            return context.getString(R.string.iptv_recent);
        }
        if (sView.startsWith(VIEW_LISTALL)) {
            String list = sView.substring(VIEW_LISTALL.length());
            return list.isEmpty() ? context.getString(R.string.iptv_other_list) : list;
        }
        if (sView.startsWith(VIEW_GROUP)) {
            String name = sView.substring(VIEW_GROUP.length());
            int sep = name.indexOf(SEP);
            if (sep >= 0) {
                String list = name.substring(0, sep);
                name = IptvNames.groupInList(name.substring(sep + 1), list);
            }
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
            String url = key.substring(K_PLAY.length());
            if (sSelectMode) {
                if (!sSelected.remove(url)) {
                    sSelected.add(url);
                }
                redraw(context);
                return true;
            }
            IptvChannel channel = findChannel(context, url);
            if (channel != null) {
                // the whole opened list (not only the visible page) becomes the zapping queue of the built-in player
                play(context, channel, channelsOfCurrentView(IptvStore.instance(context).load(), IptvUserData.instance(context),
                        IptvPrefs.instance(context).isSortAz()));
            } else {
                MessageHelpers.showMessage(context, R.string.iptv_failed);
            }
        } else if (key.equals(K_SELECT)) {
            sSelectMode = !sSelectMode;
            sSelected.clear();
            redraw(context);
        } else if (key.equals(K_DIAG)) {
            onDiagClicked(context);
        } else if (key.startsWith(K_DIAGP)) {
            String portal = key.substring(K_DIAGP.length());
            if (!sDiagSelected.remove(portal)) {
                sDiagSelected.add(portal);
            }
            redraw(context);
        } else if (key.equals(K_DIAGALL)) {
            Map<String, List<IptvChannel>> all = portalChannels(IptvStore.instance(context).load());
            if (sDiagSelected.containsAll(all.keySet())) {
                sDiagSelected.clear();
            } else {
                sDiagSelected.addAll(all.keySet());
            }
            redraw(context);
        } else if (key.equals(K_DIAGINFO)) {
            sPortalInfo.clear();
            loadPortalInfo(context, portalChannels(IptvStore.instance(context).load()));
            redraw(context);
        } else if (key.equals(K_DIAGSTART)) {
            onDiagStart(context);
        } else if (key.equals(K_DIAGREPORT)) {
            showDiagnosisReport(context, StalkerDiagnosis.instance().lastReport());
        } else if (key.equals(K_SELALL)) {
            List<IptvChannel> shown = viewChannels(context, IptvStore.instance(context).load(), IptvUserData.instance(context));
            if (sSelected.size() >= shown.size()) {
                sSelected.clear();
            } else {
                for (IptvChannel c : shown) {
                    sSelected.add(c.key());
                }
            }
            redraw(context);
        } else if (key.equals(K_MOVESELTOP)) {
            moveSelected(context, IptvPlaylist.MOVE_TOP, null);
            sPage = 0;
            redraw(context);
        } else if (key.equals(K_MOVESELBOTTOM)) {
            moveSelected(context, IptvPlaylist.MOVE_BOTTOM, null);
            sPage = Integer.MAX_VALUE; // clamped to the last page when drawn
            redraw(context);
        } else if (key.equals(K_DELSEL)) {
            if (!sSelected.isEmpty()) {
                final int count = sSelected.size();
                confirm(context, context.getString(R.string.iptv_select_delete_confirm, count), () -> {
                    removeSelectedChannels(context);
                    resetSelection();
                    redraw(context);
                });
            }
        } else if (key.equals(K_MANAGE)) {
            IptvManagerActivity.start(context, "all");
        } else if (key.equals(K_SAT)) {
            com.liskovsoft.smartyoutubetv2.common.misc.IptvSatellite.launch(context);
        } else if (key.startsWith(K_OPEN)) {
            String opened = key.substring(K_OPEN.length());
            String managerScope = IptvPrefs.instance(context).getUiMode() == IptvPrefs.UI_MANAGER ? managerScopeOf(opened) : null;
            if (managerScope != null) { // "numbered list" mode: channel lists open in the channel manager
                IptvManagerActivity.start(context, managerScope);
                return true;
            }
            sView = opened;
            sPage = 0;
            resetSelection();
            redraw(context);
        } else if (key.equals(K_BACK)) {
            sView = parentView();
            sPage = 0;
            resetSelection();
            redraw(context);
        } else if (key.equals(K_CHECK) || key.equals(K_CHECKALL)) {
            List<IptvChannel> shown = viewChannels(context, IptvStore.instance(context).load(), IptvUserData.instance(context));
            if (key.equals(K_CHECK) && !IptvHealthChecker.instance().isRunning()) {
                int from = Math.min(sPage * PAGE_SIZE, shown.size());
                shown = shown.subList(from, Math.min(shown.size(), from + PAGE_SIZE));
            }
            runHealthCheck(context, shown);
        } else if (key.equals(K_HIDEDEAD)) {
            IptvPrefs hidePrefs = IptvPrefs.instance(context);
            hidePrefs.setHideDead(!hidePrefs.isHideDead());
            sPage = 0;
            redraw(context);
        } else if (key.equals(K_SORT)) {
            IptvPrefs prefs = IptvPrefs.instance(context);
            prefs.setSortAz(!prefs.isSortAz());
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
                        resetSelection();
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
        } else if (key.startsWith(K_OPEN + VIEW_LIST)) {
            showListMenu(context, key.substring((K_OPEN + VIEW_LIST).length()));
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

    /** One step back: from a channel list of a list/group to that list's groups, otherwise to the root. */
    private static boolean isAppInForeground(Context context) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            List<android.app.ActivityManager.RunningAppProcessInfo> processes = am == null ? null : am.getRunningAppProcesses();
            if (processes != null) {
                int me = android.os.Process.myPid();
                for (android.app.ActivityManager.RunningAppProcessInfo p : processes) {
                    if (p.pid == me) {
                        return p.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                    }
                }
            }
        } catch (RuntimeException e) {
            // unknown: treat as background, the card in the section still shows the state
        }
        return false;
    }

    /** The channel manager scope that shows the same channels as a section view, or null when it has no equivalent. */
    private static String managerScopeOf(String view) {
        if (view.equals(VIEW_ALL)) {
            return "all";
        }
        if (view.equals(VIEW_FAVORITES)) {
            return "fav";
        }
        if (view.startsWith(VIEW_GROUP)) {
            String spec = view.substring(VIEW_GROUP.length());
            int sep = spec.indexOf(SEP);
            return "g:" + (sep >= 0 ? spec.substring(sep + 1) : spec);
        }
        return null; // recent (ordered by the last watch) and the groups of one list stay cards
    }

    private static String parentView() {
        if (sView.startsWith(VIEW_LISTALL)) {
            return VIEW_LIST + sView.substring(VIEW_LISTALL.length());
        }
        if (sView.startsWith(VIEW_GROUP)) {
            String spec = sView.substring(VIEW_GROUP.length());
            int sep = spec.indexOf(SEP);
            if (sep >= 0) {
                return VIEW_LIST + spec.substring(0, sep);
            }
        }
        return VIEW_ROOT;
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

    /**
     * "Move up" / "move down": swaps the channel with its neighbour in the view it was opened from. Available in
     * Favorites and the library views (All channels, a list, a group, search) when sorting is "original order" \u2014
     * while sorted A-Z the manual order exists but is not visible, so the buttons are hidden to avoid confusion.
     * Not available in Recent (it reorders itself by what was watched last).
     */
    private static void addMoveButtons(Context context, AppDialogPresenter dialog, IptvChannel channel) {
        if (sView.equals(VIEW_RECENT)) {
            return;
        }
        if (IptvPrefs.instance(context).isSortAz()) {
            // manual order is invisible while sorted A-Z: offer to switch instead of hiding the buttons silently
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_manual_order_enable), option -> {
                IptvPrefs.instance(context).setSortAz(false);
                dialog.closeDialog();
                redraw(context);
            }));
            return;
        }
        List<IptvChannel> bucket = unsortedChannelsOfCurrentView(IptvStore.instance(context).load(), IptvUserData.instance(context));
        int idx = -1;
        for (int i = 0; i < bucket.size(); i++) {
            if (bucket.get(i).key().equals(channel.key())) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return;
        }
        final List<String> bucketUrls = new ArrayList<>();
        for (IptvChannel b : bucket) {
            bucketUrls.add(b.url);
        }
        if (idx > 0) {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_move_top), option -> {
                dialog.closeDialog();
                moveChannelToEdge(context, channel.url, bucketUrls, true);
            }));
        }
        if (idx > 0) {
            final String aboveUrl = bucket.get(idx - 1).url;
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_move_up), option -> {
                dialog.closeDialog();
                moveChannel(context, channel.url, aboveUrl);
            }));
        }
        if (idx < bucket.size() - 1) {
            final String belowUrl = bucket.get(idx + 1).url;
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_move_down), option -> {
                dialog.closeDialog();
                moveChannel(context, channel.url, belowUrl);
            }));
        }
        if (idx < bucket.size() - 1) {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_move_bottom), option -> {
                dialog.closeDialog();
                moveChannelToEdge(context, channel.url, bucketUrls, false);
            }));
        }
    }

    /** Moves every ticked channel at once (keeping their order) to the top / bottom of the view, or before {@code anchor}. */
    private static void moveSelected(Context context, int mode, String anchor) {
        if (sSelected.isEmpty() || sView.equals(VIEW_RECENT)) {
            return;
        }
        IptvPrefs prefs = IptvPrefs.instance(context);
        if (prefs.isSortAz()) {
            prefs.setSortAz(false); // a manual order cannot be seen while sorted A-Z
        }
        IptvPlaylist library = IptvStore.instance(context).load();
        List<String> shown = new ArrayList<>();
        for (IptvChannel c : unsortedChannelsOfCurrentView(library, IptvUserData.instance(context))) {
            shown.add(c.key());
        }
        final int count = sSelected.size();
        boolean moved;
        try {
            moved = sView.equals(VIEW_FAVORITES)
                    ? IptvUserData.instance(context).moveFavoritesBlock(shown, sSelected, mode, anchor)
                    : IptvStore.instance(context).moveBlock(shown, sSelected, mode, anchor);
        } catch (IOException e) {
            moved = false;
        }
        MessageHelpers.showMessage(context, moved ? context.getString(R.string.iptv_select_moved, count) : context.getString(R.string.iptv_select_move_nothing));
    }

    /** Moves one channel to the very top / bottom of the shown list with a single save. */
    private static void moveChannelToEdge(Context context, String url, List<String> bucketUrls, boolean top) {
        boolean moved;
        try {
            if (sView.equals(VIEW_FAVORITES)) {
                moved = true;
                IptvUserData user = IptvUserData.instance(context);
                int idx = bucketUrls.indexOf(url);
                if (top) {
                    for (int i = idx - 1; i >= 0; i--) { moved &= user.swapFavorites(url, bucketUrls.get(i)); }
                } else {
                    for (int i = idx + 1; i < bucketUrls.size(); i++) { moved &= user.swapFavorites(url, bucketUrls.get(i)); }
                }
            } else {
                moved = IptvStore.instance(context).moveChannelToEdge(url, bucketUrls, top);
            }
        } catch (IOException e) {
            moved = false;
        }
        if (!moved) {
            MessageHelpers.showMessage(context, R.string.iptv_failed);
        }
        redraw(context);
    }

    /** Swaps two channels' order (Favorites: in the favorites list; elsewhere: in the library itself). */
    private static void moveChannel(Context context, String urlA, String urlB) {
        boolean moved;
        try {
            moved = sView.equals(VIEW_FAVORITES)
                    ? IptvUserData.instance(context).swapFavorites(urlA, urlB)
                    : IptvStore.instance(context).swapChannels(urlA, urlB);
        } catch (IOException e) {
            moved = false;
        }
        if (!moved) {
            MessageHelpers.showMessage(context, R.string.iptv_failed);
        }
        redraw(context);
    }

    private static void showChannelMenu(Context context, IptvChannel channel) {
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        IptvUserData user = IptvUserData.instance(context);
        boolean favorite = user.isFavorite(channel.url);

        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_play), option -> {
            dialog.closeDialog();
            play(context, channel);
        }));
        if (sSelectMode && !sSelected.isEmpty() && !sSelected.contains(channel.key()) && !sView.equals(VIEW_RECENT)) {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_select_move_here, sSelected.size()), option -> {
                dialog.closeDialog();
                moveSelected(context, IptvPlaylist.MOVE_BEFORE, channel.key());
                redraw(context);
            }));
        }
        addMoveButtons(context, dialog, channel);
        dialog.appendSingleButton(UiOptionItem.from(
                context.getString(favorite ? R.string.iptv_remove_favorite : R.string.iptv_add_favorite), option -> {
                    user.toggleFavorite(channel.url);
                    dialog.closeDialog();
                    redraw(context);
                }));
        final List<IptvChannel> alternatives = IptvAlternatives.find(IptvStore.instance(context).load(), channel);
        if (!alternatives.isEmpty()) {
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_alternatives, alternatives.size()), option -> {
                dialog.closeDialog();
                MAIN.post(() -> showAlternatives(context, channel, alternatives));
            }));
        }
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

    private static void showAlternatives(Context context, IptvChannel channel, List<IptvChannel> alternatives) {
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        for (IptvChannel alt : alternatives) {
            String where = alt.list == null || alt.list.isEmpty() ? context.getString(R.string.iptv_other_list) : alt.list;
            dialog.appendSingleButton(UiOptionItem.from(where + " \u2022 " + (alt.name == null ? alt.url : alt.name), option -> {
                dialog.closeDialog();
                play(context, alt);
            }));
        }
        dialog.showDialog(context.getString(R.string.iptv_alternatives_title) + ": "
                + (channel.name == null ? channel.url : IptvNames.stripPrefix(channel.name)));
    }

    /** Downloads the list again and replaces its channels (channels that disappeared are removed). */
    private static void updateList(Context context, String list) {
        IptvStore store = IptvStore.instance(context);
        List<IptvChannel> inList = store.load().lists().get(list);
        String text = inList == null ? null : IptvRefresh.sourceFor(inList, IptvPrefs.instance(context).getListSource(list));
        if (text == null) {
            MessageHelpers.showLongMessage(context, context.getString(R.string.iptv_update_unknown));
            return;
        }
        attachJob(context);
        if (IptvImportJob.instance().startRefresh(IptvImportJob.Source.ofText(list, text), IptvPrefs.instance(context).load(), store, list)) {
            MessageHelpers.showLongMessage(context, context.getString(R.string.iptv_update_started, list));
            redraw(context);
        } else {
            MessageHelpers.showLongMessage(context, context.getString(R.string.iptv_busy_short));
        }
    }

    private static void exportAsync(Context context, String name, IptvPlaylist playlist) {
        if (playlist.isEmpty()) {
            MessageHelpers.showMessage(context, R.string.iptv_failed);
            return;
        }
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            String where = null;
            try {
                where = IptvExport.save(app, name, playlist);
            } catch (Exception e) {
                // reported below
            }
            final String result = where;
            MAIN.post(() -> MessageHelpers.showLongMessage(context, result == null ? context.getString(R.string.iptv_failed)
                    : context.getString(R.string.iptv_exported, playlist.size(), result)));
        }, "iptv-export").start();
    }

    private static void showListMenu(Context context, String list) {
        String title = list.isEmpty() ? context.getString(R.string.iptv_other_list) : list;
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);

        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_update_list), option -> {
            dialog.closeDialog();
            updateList(context, list);
        }));
        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_check_list), option -> {
            dialog.closeDialog();
            List<IptvChannel> inList = IptvStore.instance(context).load().lists().get(list);
            if (inList != null) {
                runHealthCheck(context, inList);
            }
        }));
        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_export_m3u), option -> {
            dialog.closeDialog();
            IptvPlaylist export = new IptvPlaylist();
            List<IptvChannel> inList = IptvStore.instance(context).load().lists().get(list);
            if (inList != null) {
                export.addAll(inList);
            }
            exportAsync(context, title, export);
        }));
        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_remove_list), option ->
                confirm(context, context.getString(R.string.iptv_remove_list) + ": " + title, () -> {
                    try {
                        IptvStore.instance(context).removeList(list);
                    } catch (IOException e) {
                        MessageHelpers.showMessage(context, R.string.iptv_failed);
                    }
                    sView = VIEW_ROOT;
                    resetSelection();
                    redraw(context);
                })));
        dialog.showDialog(title);
    }

    private static void showGroupMenu(Context context, String spec) {
        final int sep = spec.indexOf(SEP);
        final String list = sep >= 0 ? spec.substring(0, sep) : null;
        final String group = sep >= 0 ? spec.substring(sep + 1) : spec;
        String title = group.isEmpty() ? context.getString(R.string.iptv_ungrouped) : IptvNames.groupInList(group, list);
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);

        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_check_list), option -> {
            dialog.closeDialog();
            List<IptvChannel> inGroup = new ArrayList<>();
            for (IptvChannel c : IptvStore.instance(context).load().getChannels()) {
                boolean sameGroup = (c.group == null ? "" : c.group).equals(group);
                boolean sameList = list == null || (c.list == null ? "" : c.list).equals(list);
                if (sameGroup && sameList) {
                    inGroup.add(c);
                }
            }
            runHealthCheck(context, inGroup);
        }));
        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_export_m3u), option -> {
            dialog.closeDialog();
            IptvPlaylist export = new IptvPlaylist();
            for (IptvChannel c : IptvStore.instance(context).load().getChannels()) {
                boolean sameGroup = (c.group == null ? "" : c.group).equals(group);
                boolean sameList = list == null || (c.list == null ? "" : c.list).equals(list);
                if (sameGroup && sameList) {
                    export.add(c);
                }
            }
            exportAsync(context, title, export);
        }));
        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_remove_group), option ->
                confirm(context, context.getString(R.string.iptv_remove_group) + ": " + title, () -> {
                    try {
                        if (list != null) {
                            IptvStore.instance(context).removeGroupInList(group, list);
                        } else {
                            IptvStore.instance(context).removeGroup(group);
                        }
                    } catch (IOException e) {
                        MessageHelpers.showMessage(context, R.string.iptv_failed);
                    }
                    if (sView.equals(VIEW_GROUP + spec)) {
                        sView = parentView();
                        resetSelection();
                    }
                    redraw(context);
                })));
        dialog.showDialog(title);
    }

    /**
     * Deletes every selected channel. In Favorites / Recent this only removes the pointer (the channel stays in the
     * library); everywhere else (All channels, a list, a group, search) it deletes the channel from the library itself,
     * same as "Remove channel from library" in the single-channel menu.
     */
    private static void removeSelectedChannels(Context context) {
        try {
            if (sView.equals(VIEW_FAVORITES)) {
                IptvUserData.instance(context).removeFavorites(sSelected);
            } else if (sView.equals(VIEW_RECENT)) {
                IptvUserData.instance(context).removeRecents(sSelected);
            } else {
                IptvStore.instance(context).removeChannels(sSelected);
            }
        } catch (IOException e) {
            MessageHelpers.showMessage(context, R.string.iptv_failed);
        }
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

    private static final long MIN_REDRAW_MS = 8000;
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

        if (finished) {
            IptvPrefs.instance(context).saveListSources(IptvImportJob.instance().takeListSources());
        }

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
        } else if (snap.running) {
            // Redrawing the section reloads the whole screen (visible flicker), so the card is not redrawn while the job
            // runs. Progress is shown by a short message every few seconds; tapping the card still cancels the job.
            long now = System.currentTimeMillis();
            // Toasts are system wide: never show the progress over another app after the user left
            if (now - sLastDrawMs >= MIN_REDRAW_MS && isAppInForeground(context)) {
                sLastDrawMs = now;
                String current = snap.current.length() > 60 ? snap.current.substring(0, 60) + "\u2026" : snap.current;
                MessageHelpers.showMessage(context, context.getString(snap.analyze ? R.string.iptv_analyze_title : R.string.iptv_progress_title,
                        snap.done, snap.total) + " \u2022 " + context.getString(R.string.iptv_progress_desc, snap.channels, formatTime(snap.elapsedMs))
                        + (current.isEmpty() ? "" : "\n" + current));
            }
        }
    }

    private static void addImportStatusCard(Context context, List<Video> items) {
        IptvImportJob.Snapshot snap = IptvImportJob.instance().snapshot();

        if (snap.running) {
            String title = "\u23F3 " + context.getString(snap.analyze ? R.string.iptv_analyzing_card : R.string.iptv_importing_card);
            String second = context.getString(R.string.iptv_running_card_desc);
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

    private static boolean hasPortalChannels(IptvPlaylist library) {
        for (IptvChannel c : library.getChannels()) {
            if (StalkerLink.isStalker(c.url)) {
                return true;
            }
        }
        return false;
    }

    /** Stalker channels of the library grouped by portal. */
    private static Map<String, List<IptvChannel>> portalChannels(IptvPlaylist library) {
        Map<String, List<IptvChannel>> byPortal = new LinkedHashMap<>();
        for (IptvChannel c : library.getChannels()) {
            StalkerLink link = StalkerLink.parse(c.url);
            if (link == null) {
                continue;
            }
            List<IptvChannel> l = byPortal.get(link.portal);
            if (l == null) {
                l = new ArrayList<>();
                byPortal.put(link.portal, l);
            }
            l.add(c);
        }
        return byPortal;
    }

    /** Opens the portal picker; while a diagnosis runs the same card stops it. */
    private static void onDiagClicked(Context context) {
        final StalkerDiagnosis diag = StalkerDiagnosis.instance();
        if (diag.isRunning()) {
            diag.cancel();
            MessageHelpers.showMessage(context, R.string.iptv_check_stopping);
            return;
        }
        sView = VIEW_DIAG;
        sPage = 0;
        resetSelection();
        redraw(context);
    }

    /** Tests every channel of the ticked portals (after a confirmation). */
    private static void onDiagStart(Context context) {
        if (StalkerDiagnosis.instance().isRunning()) {
            StalkerDiagnosis.instance().cancel();
            MessageHelpers.showMessage(context, R.string.iptv_check_stopping);
            return;
        }
        final Map<String, List<IptvChannel>> byPortal = new LinkedHashMap<>();
        int channels = 0;
        for (Map.Entry<String, List<IptvChannel>> e : portalChannels(IptvStore.instance(context).load()).entrySet()) {
            if (sDiagSelected.contains(e.getKey())) {
                byPortal.put(e.getKey(), e.getValue());
                channels += e.getValue().size();
            }
        }
        if (byPortal.isEmpty()) {
            return;
        }
        confirm(context, context.getString(R.string.iptv_diag_confirm, byPortal.size(), channels), () -> startDiagnosis(context, byPortal));
    }

    private static StalkerDiagnosis.PortalReport lastReportOf(String portal) {
        List<StalkerDiagnosis.PortalReport> last = StalkerDiagnosis.instance().lastReport();
        if (last != null) {
            for (StalkerDiagnosis.PortalReport r : last) {
                if (r.portal.equals(portal)) {
                    return r;
                }
            }
        }
        return null;
    }

    /** The picker: one card per portal with what is known about it; tap to tick, then start. */
    private static void buildDiagView(Context context, IptvPlaylist library, List<Video> items) {
        Map<String, List<IptvChannel>> portals = portalChannels(library);
        items.add(card("\u2B05 " + context.getString(R.string.iptv_back) + " - " + viewTitle(context), String.valueOf(portals.size()), K_BACK, null));
        if (portals.isEmpty()) {
            return;
        }
        sDiagSelected.retainAll(portals.keySet());
        if (sPortalInfo.isEmpty() && !sInfoLoading) {
            loadPortalInfo(context, portals);
        }

        StalkerDiagnosis diag = StalkerDiagnosis.instance();
        boolean all = sDiagSelected.containsAll(portals.keySet());
        items.add(card((all ? "\u2610 " : "\u2611 ") + context.getString(all ? R.string.iptv_select_none : R.string.iptv_diag_select_all, portals.size()), null, K_DIAGALL, null));

        if (diag.isRunning()) {
            items.add(card("\u23F9 " + context.getString(R.string.iptv_diag_stop, diag.done(), diag.total()), null, K_DIAGSTART, null));
        } else if (!sDiagSelected.isEmpty()) {
            int channels = 0;
            for (String p : sDiagSelected) {
                channels += portals.get(p).size();
            }
            items.add(card("\uD83E\uDE7A " + context.getString(R.string.iptv_diag_start, sDiagSelected.size(), channels),
                    context.getString(R.string.iptv_diag_start_desc), K_DIAGSTART, null));
        }
        items.add(card("\uD83D\uDD04 " + context.getString(sInfoLoading ? R.string.iptv_diag_info_loading : R.string.iptv_diag_refresh), null, K_DIAGINFO, null));
        if (!diag.isRunning() && diag.lastReport() != null) {
            items.add(card("\uD83D\uDCCB " + context.getString(R.string.iptv_diag_last), null, K_DIAGREPORT, null));
        }

        for (Map.Entry<String, List<IptvChannel>> e : portals.entrySet()) {
            String portal = e.getKey();
            boolean ticked = sDiagSelected.contains(portal);
            List<String> parts = new ArrayList<>();
            parts.add(context.getString(R.string.iptv_diag_p_channels, e.getValue().size()));
            StalkerClient.PortalInfo info = sPortalInfo.get(portal);
            if (info == null) {
                parts.add(context.getString(sInfoLoading ? R.string.iptv_diag_info_loading : R.string.iptv_diag_p_noinfo));
            } else if (info.macsOk == 0) {
                parts.add(context.getString(R.string.iptv_diag_p_error, info.error));
            } else {
                parts.add(context.getString(R.string.iptv_diag_p_mac, info.macsOk, info.macsTotal));
                if (!info.expiry.isEmpty()) {
                    parts.add(context.getString(R.string.iptv_diag_p_expiry, info.expiry));
                }
            }
            StalkerDiagnosis.PortalReport last = lastReportOf(portal);
            if (last != null && !last.loginDown) {
                parts.add(context.getString(R.string.iptv_diag_p_last, last.ok, last.failed()));
            }
            StringBuilder second = new StringBuilder();
            for (String part : parts) {
                if (second.length() > 0) second.append("  \u2022  ");
                second.append(part);
            }
            Video v = card((ticked ? "\u2611 " : "\u2610 ") + IptvUrls.hostOf(portal), second.toString(), K_DIAGP + portal, null);
            v.isIptvChecked = ticked;
            if (ticked) {
                v.badge = context.getString(R.string.iptv_selected_badge);
            }
            items.add(v);
        }
    }

    /** Logs in to every portal in the background (4 at a time) to learn which MACs work and until when. */
    private static void loadPortalInfo(Context context, final Map<String, List<IptvChannel>> portals) {
        if (sInfoLoading || portals.isEmpty()) {
            return;
        }
        sInfoLoading = true;
        final Context app = context.getApplicationContext();
        final IptvOptions options = IptvPrefs.instance(context).load();
        new Thread(() -> {
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(Math.min(4, portals.size()));
            try {
                HttpIptvFetcher http = new HttpIptvFetcher(options.withTimeouts(6_000, 12_000));
                for (final Map.Entry<String, List<IptvChannel>> e : portals.entrySet()) {
                    pool.execute(() -> {
                        Set<String> macs = new LinkedHashSet<>();
                        for (IptvChannel c : e.getValue()) {
                            StalkerLink link = StalkerLink.parse(c.url);
                            if (link != null) {
                                macs.addAll(link.macs);
                            }
                            if (macs.size() >= 10) {
                                break;
                            }
                        }
                        sPortalInfo.put(e.getKey(), StalkerClient.portalInfo(http, e.getKey(), new ArrayList<>(macs)));
                    });
                }
                pool.shutdown();
                try {
                    pool.awaitTermination(5, java.util.concurrent.TimeUnit.MINUTES);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                sInfoLoading = false;
            }
            MAIN.post(() -> {
                BrowseSection current = BrowsePresenter.instance(app).getCurrentSection();
                if (current != null && current.getId() == SECTION_ID && sView.equals(VIEW_DIAG)) {
                    redraw(app);
                }
            });
        }, "iptv-portal-info").start();
    }

    private static void startDiagnosis(Context context, Map<String, List<IptvChannel>> byPortal) {
        final Context app = context.getApplicationContext();
        IptvOptions options = IptvPrefs.instance(context).load();
        boolean started = StalkerDiagnosis.instance().start(byPortal, new HttpIptvFetcher(options.withTimeouts(6_000, 12_000)),
                IptvHealth.instance(context), new StalkerDiagnosis.Listener() {
                    private long mLastToast;

                    @Override
                    public void onProgress(int done, int total) {
                        long now = System.currentTimeMillis();
                        if (now - mLastToast >= 8000 && isAppInForeground(app)) {
                            mLastToast = now;
                            MAIN.post(() -> MessageHelpers.showMessage(app, app.getString(R.string.iptv_diag_progress, done, total)));
                        }
                    }

                    @Override
                    public void onFinished(List<StalkerDiagnosis.PortalReport> reports, boolean cancelled) {
                        MAIN.post(() -> {
                            BrowseSection current = BrowsePresenter.instance(app).getCurrentSection();
                            if (current != null && current.getId() == SECTION_ID) {
                                redraw(app);
                            }
                            if (isAppInForeground(app)) {
                                showDiagnosisReport(app, reports);
                            }
                        });
                    }
                });
        if (started) {
            MessageHelpers.showMessage(context, R.string.iptv_diag_started);
            redraw(context); // the card turns into "stop"
        }
    }

    /** One block per portal: verdict, counts per step and the most frequent failure reasons. */
    private static void showDiagnosisReport(Context context, List<StalkerDiagnosis.PortalReport> reports) {
        if (reports == null || reports.isEmpty()) {
            return;
        }
        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        for (StalkerDiagnosis.PortalReport r : reports) {
            List<com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem> options = new ArrayList<>();
            int verdict;
            if (r.loginDown) {
                verdict = R.string.iptv_diag_v_login;
            } else if (r.tested < r.total) {
                verdict = R.string.iptv_diag_v_partial_run;
            } else if (r.ok == 0) {
                verdict = R.string.iptv_diag_v_dead;
            } else if (r.failed() == 0) {
                verdict = R.string.iptv_diag_v_ok;
            } else {
                verdict = R.string.iptv_diag_v_some;
            }
            options.add(UiOptionItem.from(context.getString(verdict), option -> { }));
            options.add(UiOptionItem.from(context.getString(R.string.iptv_diag_counts, r.tested, r.total, r.ok, r.failed()), option -> { }));
            if (!r.loginDown) {
                if (r.linkFailed > 0) {
                    options.add(UiOptionItem.from(context.getString(R.string.iptv_diag_n_link, r.linkFailed), option -> { }));
                }
                if (r.streamFailed > 0) {
                    options.add(UiOptionItem.from(context.getString(R.string.iptv_diag_n_stream, r.streamFailed), option -> { }));
                }
                if (r.loginFailed > 0) {
                    options.add(UiOptionItem.from(context.getString(R.string.iptv_diag_n_login, r.loginFailed), option -> { }));
                }
            }
            List<Map.Entry<String, Integer>> top = new ArrayList<>(r.reasons.entrySet());
            Collections.sort(top, (a, b) -> b.getValue() - a.getValue());
            for (int i = 0; i < Math.min(4, top.size()); i++) {
                options.add(UiOptionItem.from(context.getString(R.string.iptv_diag_reason, top.get(i).getValue(), top.get(i).getKey()), option -> { }));
            }
            dialog.appendStringsCategory(IptvUrls.hostOf(r.portal), options);
        }
        dialog.showDialog(context.getString(R.string.iptv_diag_card));
    }

    private static void redraw(Context context) {
        BrowsePresenter.instance(context).refresh(false);
    }

    /** The library was changed from outside (import / clear): redraw only if the user is looking at this section. */
    public static synchronized void onLibraryChanged(Context context) {
        sView = VIEW_ROOT;
        sPage = 0;
        resetSelection();

        BrowseSection current = BrowsePresenter.instance(context).getCurrentSection();
        if (current != null && current.getId() == SECTION_ID) {
            redraw(context);
        }
    }
}
