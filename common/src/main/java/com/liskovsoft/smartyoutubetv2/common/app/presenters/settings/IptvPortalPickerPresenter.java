package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.iptv.HttpIptvFetcher;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvOptions;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvPortalMemory;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvPrefs;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvSourceItem;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvUrls;
import com.liskovsoft.smartyoutubetv2.common.iptv.StalkerAccount;
import com.liskovsoft.smartyoutubetv2.common.iptv.StalkerFailure;
import com.liskovsoft.smartyoutubetv2.common.iptv.StalkerProbe;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * "Look first, download later" for every kind of source: lists what the uploaded files hold (portals, Xtream accounts,
 * links, lists inside archives) and lets the user take all of it or only the ticked part. Nothing is downloaded before the confirmation.
 *
 * Extras: a quick handshake check of the portals (works / does not work + why), failures remembered from earlier runs
 * (shown and un-ticked), a search box, grouping by domain, pages for long lists, and per-portal MAC / content choices.
 */
public final class IptvPortalPickerPresenter {
    /** @param skipKeys the un-ticked items and MAC addresses (empty = take everything) */
    public interface Callback {
        void onChosen(Set<String> skipKeys);
    }

    private static final int PAGE_SIZE = 40;

    private static final class State {
        final Context context;
        final List<IptvSourceItem> items;
        final Callback callback;
        final IptvPrefs prefs;
        final IptvPortalMemory memory;
        final Set<String> selected = new LinkedHashSet<>();
        /** MAC addresses the user un-ticked (key = {@link IptvSourceItem#macKey}). */
        final Set<String> macOff = new LinkedHashSet<>();
        /** Result of the check made in this window, portal key -> result. */
        final Map<String, StalkerProbe.Result> checked = new HashMap<>();
        String filter = "";
        int page;
        boolean busy;
        /** The chooser was closed to open the next dialog (its finish callback must not reopen the picker). */
        boolean navigating;

        State(Context context, List<IptvSourceItem> items, Callback callback) {
            this.context = context;
            this.items = items;
            this.callback = callback;
            this.prefs = IptvPrefs.instance(context);
            this.memory = IptvPortalMemory.instance(context);
        }
    }

    private IptvPortalPickerPresenter() {
    }

    public static void show(Context context, List<IptvSourceItem> items, Callback callback) {
        State s = new State(context, items, callback);
        int leftOut = 0;
        for (IptvSourceItem i : items) {
            if (i.kind == IptvSourceItem.Kind.PORTAL && s.prefs.isRememberFailures() && s.prefs.isAutoUntick()
                    && s.memory.failedPortal(i.portalKey(), s.prefs.getMemoryDays()) != null) {
                leftOut++;
                continue; // failed last time: starts un-ticked
            }
            s.selected.add(i.key);
            if (i.kind == IptvSourceItem.Kind.PORTAL && s.prefs.isRememberFailures() && s.prefs.isAutoUntick()) {
                for (String mac : i.macs) {
                    if (s.memory.failedMac(i.portalKey(), mac, s.prefs.getMemoryDays()) != null) {
                        s.macOff.add(IptvSourceItem.macKey(i.portalKey(), mac));
                    }
                }
            }
        }
        if (leftOut > 0) {
            MessageHelpers.showMessage(context, context.getString(R.string.iptv_pick_skipped_saved, leftOut));
        }
        reopen(s);
    }

    /**
     * The items the user did not want, for the runs that do not show the picker (it still honours the remembered failures).
     * @return null when nothing is skipped
     */
    public static Set<String> rememberedSkips(Context context, List<IptvSourceItem> items) {
        IptvPrefs prefs = IptvPrefs.instance(context);
        if (!prefs.isRememberFailures() || !prefs.isAutoUntick()) {
            return null;
        }
        IptvPortalMemory memory = IptvPortalMemory.instance(context);
        Set<String> skip = new LinkedHashSet<>();
        for (IptvSourceItem i : items) {
            if (i.kind != IptvSourceItem.Kind.PORTAL) {
                continue;
            }
            if (memory.failedPortal(i.portalKey(), prefs.getMemoryDays()) != null) {
                skip.add(i.key);
                continue;
            }
            for (String mac : i.macs) {
                if (memory.failedMac(i.portalKey(), mac, prefs.getMemoryDays()) != null) {
                    skip.add(IptvSourceItem.macKey(i.portalKey(), mac));
                }
            }
        }
        for (IptvSourceItem i : items) {
            if (!skip.contains(i.key)) {
                return skip.isEmpty() ? null : skip; // something is left to download
            }
        }
        return null; // every portal failed before: try them anyway, the failures may be old
    }

    // ------------------------------------------------------------------ main window

    private static void reopen(State s) {
        final Context context = s.context;
        final AppDialogPresenter d = AppDialogPresenter.instance(context);
        final List<IptvSourceItem> visible = visible(s);
        final int pages = Math.max(1, (visible.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        s.page = Math.max(0, Math.min(s.page, pages - 1));
        final boolean hasPortals = hasPortals(s.items);

        d.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_pick_download, s.selected.size()),
                context.getString(R.string.iptv_pick_download_desc), option -> {
                    if (s.selected.isEmpty()) {
                        MessageHelpers.showMessage(context, R.string.iptv_pick_none_selected);
                        return;
                    }
                    Set<String> skip = new LinkedHashSet<>();
                    for (IptvSourceItem i : s.items) {
                        if (!s.selected.contains(i.key)) {
                            skip.add(i.key);
                        }
                    }
                    skip.addAll(s.macOff);
                    d.closeDialog();
                    s.callback.onChosen(skip);
                }));

        if (hasPortals) {
            d.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_pick_check),
                    context.getString(R.string.iptv_pick_check_desc), option -> runCheck(s)));
        }

        d.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_pick_search),
                s.filter.isEmpty() ? context.getString(R.string.iptv_pick_search_hint)
                        : context.getString(R.string.iptv_pick_filtered, visible.size(), s.items.size()) + "  [" + s.filter + "]",
                option -> {
                    d.closeDialog();
                    SimpleEditDialog.show(context, context.getString(R.string.iptv_pick_search),
                            context.getString(R.string.iptv_pick_search_hint), s.filter, value -> {
                                s.filter = value == null ? "" : value.trim();
                                s.page = 0;
                                return true;
                            }, () -> post(() -> reopen(s)));
                }));

        d.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_pick_all), option -> {
            for (IptvSourceItem i : visible(s)) {
                s.selected.add(i.key);
            }
            redraw(d, s);
        }));
        d.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_pick_none), option -> {
            for (IptvSourceItem i : visible(s)) {
                s.selected.remove(i.key);
            }
            redraw(d, s);
        }));

        final boolean byDomain = s.prefs.isGroupByDomain();
        d.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_pick_group,
                context.getString(byDomain ? R.string.iptv_pick_on : R.string.iptv_pick_off)), option -> {
            s.prefs.setGroupByDomain(!byDomain);
            s.page = 0;
            redraw(d, s);
        }));

        if (hasPortals) {
            d.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_pick_portal_opts),
                    context.getString(R.string.iptv_pick_portal_opts_desc), option -> {
                        d.closeDialog();
                        post(() -> showPortalChooser(s));
                    }));
        }
        d.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_pick_cancel), option -> d.closeDialog()));

        if (s.page > 0) {
            d.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_pick_prev), option -> {
                s.page--;
                redraw(d, s);
            }));
        }

        int from = s.page * PAGE_SIZE;
        List<IptvSourceItem> pageItems = visible.subList(Math.min(from, visible.size()), Math.min(visible.size(), from + PAGE_SIZE));
        if (byDomain) {
            byDomain(context, d, s, pageItems);
        } else {
            category(context, d, s, pageItems, IptvSourceItem.Kind.PORTAL, R.string.iptv_pick_portals);
            category(context, d, s, pageItems, IptvSourceItem.Kind.XTREAM, R.string.iptv_pick_xtream);
            category(context, d, s, pageItems, IptvSourceItem.Kind.LINK, R.string.iptv_pick_links);
            category(context, d, s, pageItems, IptvSourceItem.Kind.FILE, R.string.iptv_pick_files);
        }

        if (s.page + 1 < pages) {
            d.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_pick_next, s.page + 2, pages), option -> {
                s.page++;
                redraw(d, s);
            }));
        }

        String title = context.getString(R.string.iptv_pick_title, s.items.size());
        d.showDialog(s.filter.isEmpty() ? title : title + " · " + s.filter);
    }

    private static void redraw(AppDialogPresenter d, State s) {
        d.closeDialog();
        post(() -> reopen(s));
    }

    private static void post(Runnable r) {
        new Handler(Looper.getMainLooper()).post(r);
    }

    private static boolean hasPortals(List<IptvSourceItem> items) {
        for (IptvSourceItem i : items) {
            if (i.kind == IptvSourceItem.Kind.PORTAL) {
                return true;
            }
        }
        return false;
    }

    /** The items that match the search text (title, domain or account name). */
    private static List<IptvSourceItem> visible(State s) {
        if (s.filter.isEmpty()) {
            return s.items;
        }
        String f = s.filter.toLowerCase(Locale.ROOT);
        List<IptvSourceItem> out = new ArrayList<>();
        for (IptvSourceItem i : s.items) {
            if (i.title.toLowerCase(Locale.ROOT).contains(f) || i.detail.toLowerCase(Locale.ROOT).contains(f)) {
                out.add(i);
            }
        }
        return out;
    }

    private static void category(Context context, AppDialogPresenter d, State s, List<IptvSourceItem> source,
                                 IptvSourceItem.Kind kind, int titleRes) {
        List<IptvSourceItem> of = new ArrayList<>();
        for (IptvSourceItem i : source) {
            if (i.kind == kind) {
                of.add(i);
            }
        }
        if (!of.isEmpty()) {
            d.appendCheckedCategory(context.getString(titleRes) + " (" + of.size() + ")", checkItems(context, s, of));
        }
    }

    /** One group per domain: all the portals / accounts / links of the same server end up together. */
    private static void byDomain(Context context, AppDialogPresenter d, State s, List<IptvSourceItem> source) {
        Map<String, List<IptvSourceItem>> groups = new LinkedHashMap<>();
        for (IptvSourceItem i : source) {
            String host = domainOf(i);
            List<IptvSourceItem> list = groups.get(host);
            if (list == null) {
                list = new ArrayList<>();
                groups.put(host, list);
            }
            list.add(i);
        }
        for (Map.Entry<String, List<IptvSourceItem>> g : groups.entrySet()) {
            d.appendCheckedCategory(g.getKey() + " (" + g.getValue().size() + ")", checkItems(context, s, g.getValue()));
        }
    }

    private static String domainOf(IptvSourceItem i) {
        if (i.kind == IptvSourceItem.Kind.FILE) {
            return i.detail.isEmpty() ? i.title : i.detail; // the archive a list came from
        }
        String host = IptvUrls.hostOf(i.kind == IptvSourceItem.Kind.XTREAM ? firstWord(i.title) : i.title);
        return host.isEmpty() ? i.title : host;
    }

    private static String firstWord(String t) {
        int sp = t.indexOf(' ');
        return sp > 0 ? t.substring(0, sp) : t;
    }

    private static List<OptionItem> checkItems(Context context, State s, List<IptvSourceItem> of) {
        List<OptionItem> list = new ArrayList<>();
        for (IptvSourceItem i : of) {
            final String key = i.key;
            list.add(UiOptionItem.from(describe(context, s, i), option -> {
                if (option.isSelected()) {
                    s.selected.add(key);
                } else {
                    s.selected.remove(key);
                }
            }, s.selected.contains(key)));
        }
        return list;
    }

    private static String describe(Context context, State s, IptvSourceItem i) {
        String t = i.title.length() > 70 ? i.title.substring(0, 67) + "..." : i.title;
        switch (i.kind) {
            case PORTAL:
                int on = i.macs.size();
                for (String mac : i.macs) {
                    if (s.macOff.contains(IptvSourceItem.macKey(i.portalKey(), mac))) {
                        on--;
                    }
                }
                String macs = on == i.macs.size() ? context.getString(R.string.iptv_pick_macs, i.count)
                        : on + "/" + context.getString(R.string.iptv_pick_macs, i.count);
                return portalMark(context, s, i) + t + "  (" + macs + ")" + portalStatus(context, s, i);
            case XTREAM:
                return t + "  (" + i.detail + ")";
            case FILE:
                return t + "  (" + context.getString(R.string.iptv_channels_count, i.count)
                        + (i.detail.isEmpty() ? "" : " \u00B7 " + i.detail) + ")";
            default:
                return t;
        }
    }

    private static String portalMark(Context context, State s, IptvSourceItem i) {
        StalkerProbe.Result r = s.checked.get(i.portalKey());
        if (r != null) {
            return r.ok() ? "\u2705 " : "\u274C ";
        }
        return s.memory.failedPortal(i.portalKey(), s.prefs.getMemoryDays()) != null ? "\u26A0 " : "";
    }

    private static String portalStatus(Context context, State s, IptvSourceItem i) {
        StalkerProbe.Result r = s.checked.get(i.portalKey());
        if (r != null) {
            return "\n" + (r.ok() ? context.getString(R.string.iptv_pick_status_ok, r.macsOk, r.macsTotal)
                    : context.getString(R.string.iptv_pick_status_fail, reasonText(context, r.reason)));
        }
        IptvPortalMemory.Entry e = s.memory.failedPortal(i.portalKey(), s.prefs.getMemoryDays());
        return e == null ? "" : "\n" + context.getString(R.string.iptv_pick_status_saved, reasonText(context, e.reason));
    }

    /** User-facing (translated) reason of a failure. */
    static String reasonText(Context context, StalkerFailure f) {
        if (f == null) {
            return context.getString(R.string.iptv_why_other);
        }
        switch (f) {
            case TIMEOUT:
                return context.getString(R.string.iptv_why_timeout);
            case DNS:
                return context.getString(R.string.iptv_why_dns);
            case REFUSED:
                return context.getString(R.string.iptv_why_refused);
            case MAC_REJECTED:
                return context.getString(R.string.iptv_why_mac);
            case AUTH:
                return context.getString(R.string.iptv_why_auth);
            case HTTP:
                return context.getString(R.string.iptv_why_http);
            case NOT_PORTAL:
                return context.getString(R.string.iptv_why_notportal);
            case NO_CHANNELS:
                return context.getString(R.string.iptv_why_nochannels);
            default:
                return context.getString(R.string.iptv_why_other);
        }
    }

    // ------------------------------------------------------------------ quick check (handshake only)

    private static void runCheck(State s) {
        if (s.busy) {
            return;
        }
        final Context context = s.context;
        final List<StalkerAccount> accounts = new ArrayList<>();
        final Map<String, IptvSourceItem> byKey = new HashMap<>();
        for (IptvSourceItem i : s.items) {
            if (i.kind != IptvSourceItem.Kind.PORTAL) {
                continue;
            }
            StalkerAccount a = new StalkerAccount(i.title);
            a.macs.addAll(i.macs);
            accounts.add(a);
            byKey.put(a.key(), i);
        }
        if (accounts.isEmpty()) {
            return;
        }
        s.busy = true;
        AppDialogPresenter.instance(context).closeDialog();
        MessageHelpers.showMessage(context, context.getString(R.string.iptv_pick_checking, accounts.size()));
        final IptvOptions o = s.prefs.load();
        new Thread(() -> {
            Map<String, StalkerProbe.Result> results;
            try {
                results = StalkerProbe.checkAll(new HttpIptvFetcher(o.withTimeouts(o.portalConnectTimeoutMs, o.portalReadTimeoutMs)),
                        accounts, o.portalParallel, null);
            } catch (Throwable t) {
                results = new HashMap<>();
            }
            final Map<String, StalkerProbe.Result> done = results;
            post(() -> {
                s.busy = false;
                int good = 0;
                int bad = 0;
                for (Map.Entry<String, StalkerProbe.Result> e : done.entrySet()) {
                    IptvSourceItem item = byKey.get(e.getKey());
                    StalkerProbe.Result r = e.getValue();
                    if (item == null) {
                        continue;
                    }
                    s.checked.put(e.getKey(), r);
                    if (r.ok()) {
                        good++;
                        s.selected.add(item.key); // fresh proof beats an old failure
                    } else {
                        bad++;
                        s.selected.remove(item.key);
                    }
                    for (String mac : item.macs) {
                        String macKey = IptvSourceItem.macKey(e.getKey(), mac);
                        StalkerFailure f = r.macFailures.get(mac);
                        if (f != null && r.ok()) {
                            s.macOff.add(macKey);
                        } else {
                            s.macOff.remove(macKey);
                        }
                        if (s.prefs.isRememberFailures()) {
                            s.memory.recordMac(e.getKey(), mac, f == null, f == null ? "" : f.name());
                        }
                    }
                    if (s.prefs.isRememberFailures()) {
                        s.memory.recordPortal(e.getKey(), r.ok(), r.reason == null ? "" : r.reason.name());
                    }
                }
                MessageHelpers.showMessage(context, context.getString(R.string.iptv_pick_checked, good, bad));
                reopen(s);
            });
        }, "iptv-portal-check").start();
    }

    // ------------------------------------------------------------------ per portal: MAC addresses and content

    private static void showPortalChooser(State s) {
        final Context context = s.context;
        AppDialogPresenter d = AppDialogPresenter.instance(context);
        List<OptionItem> list = new ArrayList<>();
        for (IptvSourceItem i : visible(s)) {
            if (i.kind != IptvSourceItem.Kind.PORTAL) {
                continue;
            }
            final IptvSourceItem item = i;
            list.add(UiOptionItem.from(describe(context, s, i), option -> {
                s.navigating = true;
                d.closeDialog();
                post(() -> showPortalOptions(s, item));
            }));
        }
        d.appendStringsCategory(context.getString(R.string.iptv_pick_portal_choose), list);
        d.showDialog(context.getString(R.string.iptv_pick_portal_opts), () -> {
            if (s.navigating) {
                s.navigating = false;
            } else {
                post(() -> reopen(s));
            }
        });
    }

    private static void showPortalOptions(State s, IptvSourceItem item) {
        final Context context = s.context;
        final AppDialogPresenter d = AppDialogPresenter.instance(context);
        final String pk = item.portalKey();
        final StalkerProbe.Result checked = s.checked.get(pk);

        List<OptionItem> macs = new ArrayList<>();
        for (String mac : item.macs) {
            final String macKey = IptvSourceItem.macKey(pk, mac);
            StalkerFailure f = checked != null ? checked.macFailures.get(mac) : null;
            if (f == null && checked == null) {
                IptvPortalMemory.Entry e = s.memory.failedMac(pk, mac, s.prefs.getMemoryDays());
                f = e == null ? null : e.reason;
            }
            String label = (f == null ? (checked != null ? "\u2705 " : "") : "\u274C ") + mac + (f == null ? "" : "  (" + reasonText(context, f) + ")");
            macs.add(UiOptionItem.from(label, option -> {
                if (option.isSelected()) {
                    s.macOff.remove(macKey);
                } else {
                    s.macOff.add(macKey);
                }
            }, !s.macOff.contains(macKey)));
        }
        d.appendCheckedCategory(context.getString(R.string.iptv_pick_mac_title) + " (" + item.macs.size() + ")", macs);

        // what to fetch from this portal (stored; 0 = the general settings)
        final int[] bits = {s.prefs.getPortalTypes(pk)};
        if (bits[0] == 0) {
            bits[0] = (s.prefs.getBool("stalkerLive", true) ? 1 : 0) | (s.prefs.getBool("stalkerMovies", false) ? 2 : 0)
                    | (s.prefs.getBool("stalkerSeries", false) ? 4 : 0);
        }
        final int[] kinds = {1, 2, 4};
        final int[] labels = {R.string.iptv_stalker_live, R.string.iptv_stalker_movies, R.string.iptv_stalker_series};
        List<OptionItem> types = new ArrayList<>();
        for (int k = 0; k < kinds.length; k++) {
            final int bit = kinds[k];
            types.add(UiOptionItem.from(context.getString(labels[k]), option -> {
                bits[0] = option.isSelected() ? (bits[0] | bit) : (bits[0] & ~bit);
                if (bits[0] == 0) {
                    bits[0] = 1; // never fetch nothing: live is the fallback
                }
                s.prefs.setPortalTypes(pk, bits[0]);
            }, (bits[0] & bit) != 0));
        }
        d.appendCheckedCategory(context.getString(R.string.iptv_pick_types_title), types);
        d.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_pick_types_default), option -> {
            s.prefs.setPortalTypes(pk, 0);
            d.closeDialog();
        }));
        d.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_pick_done), option -> d.closeDialog()));

        String title = item.title.length() > 50 ? item.title.substring(0, 47) + "..." : item.title;
        d.showDialog(title, () -> post(() -> reopen(s)));
    }
}
