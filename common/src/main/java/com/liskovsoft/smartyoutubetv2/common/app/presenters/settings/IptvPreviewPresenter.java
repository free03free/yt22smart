package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.IptvSection;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvChannel;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvLanguage;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvPlaylist;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvSelection;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvStore;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "Analyze, then choose": after the lists were read (nothing stored yet) the user picks the languages, types,
 * groups and/or a keyword and only that part is added to the library.
 */
public final class IptvPreviewPresenter {
    private static final int PAGE = 80;
    private static IptvSelection sSelection; // kept so an accidental back does not lose a big download

    private IptvPreviewPresenter() {
    }

    public static boolean hasPending() {
        return sSelection != null && !sSelection.all().isEmpty();
    }

    public static int pendingCount() {
        return sSelection == null ? 0 : sSelection.all().size();
    }

    /** A new analysis finished. */
    public static void show(Context context, IptvPlaylist found) {
        sSelection = new IptvSelection(found);
        reopen(context);
    }

    /** Re-open the dialog for the analysis that is still waiting. */
    public static void resume(Context context) {
        if (hasPending()) {
            reopen(context);
        }
    }

    private static void reopen(Context context) {
        final IptvSelection sel = sSelection;
        if (sel == null) {
            return;
        }
        AppDialogPresenter d = AppDialogPresenter.instance(context);

        d.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_preview_import),
                context.getString(R.string.iptv_preview_import_desc), option -> importSelected(context)));
        d.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_preview_discard), option -> {
            sSelection = null;
            d.closeDialog();
        }));

        d.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_preview_channels),
                context.getString(R.string.iptv_preview_channels_desc), option -> {
                    d.closeDialog();
                    new Handler(Looper.getMainLooper()).post(() -> showChannels(context, 0));
                }));

        // keyword (name / group contains ...)
        String kw = sel.keyword();
        d.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_preview_keyword),
                kw.isEmpty() ? context.getString(R.string.iptv_preview_keyword_desc) : "\u201C" + kw + "\u201D", option -> {
                    d.closeDialog();
                    SimpleEditDialog.show(context, context.getString(R.string.iptv_preview_keyword),
                            context.getString(R.string.iptv_preview_keyword_desc), sel.keyword(), value -> {
                                sel.setKeyword(value);
                                return true;
                            }, () -> new Handler(Looper.getMainLooper()).post(() -> reopen(context)));
                }));

        // types
        List<OptionItem> kinds = new ArrayList<>();
        addKind(context, sel, kinds, IptvChannel.KIND_LIVE, R.string.iptv_type_live);
        addKind(context, sel, kinds, IptvChannel.KIND_MOVIE, R.string.iptv_type_movies);
        addKind(context, sel, kinds, IptvChannel.KIND_SERIES, R.string.iptv_type_series);
        if (kinds.size() > 1) {
            d.appendCheckedCategory(context.getString(R.string.iptv_preview_types), kinds);
        }

        // languages
        Map<String, Integer> languages = sel.languageCounts();
        if (languages.size() > 1) {
            List<OptionItem> items = new ArrayList<>();
            for (Map.Entry<String, Integer> e : languages.entrySet()) {
                final String lang = e.getKey();
                String title = (lang.equals(IptvLanguage.UNKNOWN) ? context.getString(R.string.iptv_preview_unknown_language) : lang)
                        + " (" + e.getValue() + ")";
                items.add(UiOptionItem.from(title, option -> sel.setLanguage(lang, option.isSelected()), sel.isLanguageOn(lang)));
            }
            d.appendCheckedCategory(context.getString(R.string.iptv_preview_languages), items);
        }

        // groups
        Map<String, Integer> groups = sel.groupCounts();
        if (groups.size() > 1) {
            d.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_preview_groups_all), option -> {
                sel.setAllGroups(true);
                d.closeDialog();
                new Handler(Looper.getMainLooper()).post(() -> reopen(context));
            }));
            d.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_preview_groups_none), option -> {
                sel.setAllGroups(false);
                d.closeDialog();
                new Handler(Looper.getMainLooper()).post(() -> reopen(context));
            }));
            List<OptionItem> items = new ArrayList<>();
            for (Map.Entry<String, Integer> e : groups.entrySet()) {
                final String group = e.getKey();
                String title = (group.isEmpty() ? context.getString(R.string.iptv_ungrouped) : group) + " (" + e.getValue() + ")";
                items.add(UiOptionItem.from(title, option -> sel.setGroup(group, option.isSelected()), sel.isGroupOn(group)));
            }
            d.appendCheckedCategory(context.getString(R.string.iptv_preview_groups), items);
        }

        d.showDialog(context.getString(R.string.iptv_preview_title, sel.all().size(), sel.selectedCount()));
    }

    /** Single channels that pass the other filters, one page at a time; untick the ones you do not want. */
    private static void showChannels(Context context, int page) {
        final IptvSelection sel = sSelection;
        if (sel == null) {
            return;
        }
        final List<IptvChannel> list = sel.candidates();
        final int pages = Math.max(1, (list.size() + PAGE - 1) / PAGE);
        final int p = Math.max(0, Math.min(page, pages - 1));
        AppDialogPresenter d = AppDialogPresenter.instance(context);

        d.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_preview_back), option -> {
            d.closeDialog();
            new Handler(Looper.getMainLooper()).post(() -> reopen(context));
        }));
        if (p > 0) {
            d.appendSingleButton(UiOptionItem.from("\u25C0 " + context.getString(R.string.iptv_prev_page), option -> {
                d.closeDialog();
                new Handler(Looper.getMainLooper()).post(() -> showChannels(context, p - 1));
            }));
        }
        if (p < pages - 1) {
            d.appendSingleButton(UiOptionItem.from(context.getString(R.string.iptv_next_page) + " \u25B6", option -> {
                d.closeDialog();
                new Handler(Looper.getMainLooper()).post(() -> showChannels(context, p + 1));
            }));
        }

        List<OptionItem> items = new ArrayList<>();
        int to = Math.min(list.size(), (p + 1) * PAGE);
        for (int i = p * PAGE; i < to; i++) {
            final IptvChannel c = list.get(i);
            String title = c.name == null || c.name.trim().isEmpty() ? c.url : c.name;
            items.add(UiOptionItem.from(title, option -> sel.setChannel(c, option.isSelected()), sel.isChannelOn(c)));
        }
        d.appendCheckedCategory(context.getString(R.string.iptv_preview_channels) + " " + (p + 1) + "/" + pages, items);
        d.showDialog(context.getString(R.string.iptv_preview_title, sel.all().size(), sel.selectedCount()));
    }

    private static void addKind(Context context, IptvSelection sel, List<OptionItem> out, int kind, int titleRes) {
        int n = sel.kindCount(kind);
        if (n > 0) {
            out.add(UiOptionItem.from(context.getString(titleRes) + " (" + n + ")",
                    option -> sel.setKind(kind, option.isSelected()), sel.isKindOn(kind)));
        }
    }

    private static void importSelected(Context context) {
        final IptvSelection sel = sSelection;
        if (sel == null) {
            return;
        }
        final IptvPlaylist chosen = sel.result();
        if (chosen.isEmpty()) {
            MessageHelpers.showMessage(context, R.string.iptv_preview_none_selected);
            return;
        }
        AppDialogPresenter.instance(context).closeDialog();
        sSelection = null;

        final Context app = context.getApplicationContext();
        MessageHelpers.showMessage(context, R.string.iptv_preview_saving);
        new Thread(() -> {
            int added = 0;
            boolean failed = false;
            try {
                added = IptvStore.instance(app).merge(chosen);
            } catch (Throwable t) {
                failed = true;
            }
            final int addedFinal = added;
            final boolean failedFinal = failed;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (failedFinal) {
                    MessageHelpers.showLongMessage(context, context.getString(R.string.iptv_failed));
                } else {
                    MessageHelpers.showLongMessage(context, context.getString(R.string.iptv_import_result, addedFinal,
                            IptvStore.instance(app).load().size()));
                }
                IptvSection.onLibraryChanged(context);
            });
        }, "iptv-save-selection").start();
    }
}
