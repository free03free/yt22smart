package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.helpers.PermissionHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.IptvSection;
import com.liskovsoft.smartyoutubetv2.common.iptv.CommonsArchiveHandler;
import com.liskovsoft.smartyoutubetv2.common.iptv.HttpIptvFetcher;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvChannel;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvImportJob;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvOptions;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvPrefs;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvPlaylist;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvStore;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvUrls;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvUserData;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import arte.programar.materialfile.MaterialFilePicker;
import arte.programar.materialfile.ui.FilePickerActivity;
import arte.programar.materialfile.utils.FileUtils;

/**
 * Settings screen "IPTV": import channel lists from a link / pasted text / clipboard / local file
 * (m3u, m3u8, txt, pls, xspf, json, zip, gz, tar.gz, Xtream Codes accounts), browse them and open a channel
 * in the player app installed on the device.
 */
public class IptvSettingsPresenter extends BasePresenter<Void> {
    private static final int FILE_PICKER_REQUEST_CODE = 207;
    private static final int PAGE_SIZE = 150;
    @SuppressLint("StaticFieldLeak")
    private static IptvSettingsPresenter sInstance;
    private final IptvStore mStore;
    private final IptvPrefs mPrefs;

    private IptvSettingsPresenter(Context context) {
        super(context);
        mStore = IptvStore.instance(context);
        mPrefs = IptvPrefs.instance(context);
    }

    public static IptvSettingsPresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new IptvSettingsPresenter(context);
        }
        sInstance.setContext(context);
        return sInstance;
    }

    public static void unhold() {
        sInstance = null;
    }

    public void show() {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        IptvPlaylist library = mStore.load();

        dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_open_section), option -> {
            AppDialogPresenter.instance(getContext()).closeDialog();
            BrowsePresenter.instance(getContext()).selectSection(IptvSection.SECTION_ID);
        }));
        if (IptvPreviewPresenter.hasPending()) {
            dialog.appendSingleButton(UiOptionItem.from(
                    getContext().getString(R.string.iptv_preview_resume, IptvPreviewPresenter.pendingCount()), option -> {
                        AppDialogPresenter.instance(getContext()).closeDialog();
                        IptvPreviewPresenter.resume(getContext());
                    }));
        }
        dialog.appendRadioCategory(getContext().getString(R.string.iptv_player_mode), playerChoices());
        dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_import_link), getContext().getString(R.string.iptv_import_link_desc), option -> showLinkDialog()));
        dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_import_clipboard), option -> importFromClipboard()));
        dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_import_file), getContext().getString(R.string.iptv_import_file_desc), option -> startFilePicker()));
        dialog.appendSingleButton(UiOptionItem.from(
                getContext().getString(R.string.iptv_browse) + " (" + library.size() + ")", option -> showGroups()));
        dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_import_settings),
                getContext().getString(R.string.iptv_import_settings_desc), option -> showImportSettings()));
        if (!library.isEmpty()) {
            dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_clear), option ->
                    AppDialogUtil.showConfirmationDialog(getContext(), getContext().getString(R.string.iptv_clear), () -> {
                        mStore.clear();
                        IptvUserData.instance(getContext()).clearAll();
                        IptvSection.onLibraryChanged(getContext());
                        MessageHelpers.showMessage(getContext(), R.string.msg_done);
                        AppDialogPresenter.instance(getContext()).closeDialog();
                    })));
        }
        dialog.showDialog(getContext().getString(R.string.iptv_title), IptvSettingsPresenter::unhold);
    }

    private List<OptionItem> playerChoices() {
        int current = mPrefs.getPlayerMode();
        List<OptionItem> items = new ArrayList<>();
        items.add(UiOptionItem.from(getContext().getString(R.string.iptv_player_internal),
                option -> mPrefs.setPlayerMode(IptvPrefs.PLAYER_INTERNAL), current == IptvPrefs.PLAYER_INTERNAL));
        items.add(UiOptionItem.from(getContext().getString(R.string.iptv_player_external),
                option -> mPrefs.setPlayerMode(IptvPrefs.PLAYER_EXTERNAL), current == IptvPrefs.PLAYER_EXTERNAL));
        return items;
    }

    // ------------------------------------------------------------------ import

    private void showLinkDialog() {
        SimpleEditDialog.show(getContext(), getContext().getString(R.string.iptv_import_link),
                getContext().getString(R.string.iptv_link_hint), "", value -> {
                    startImport(value, null);
                    return true;
                });
    }

    private void importFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm == null ? null : cm.getPrimaryClip();
        CharSequence text = clip != null && clip.getItemCount() > 0 ? clip.getItemAt(0).coerceToText(getContext()) : null;
        if (text == null || text.toString().trim().isEmpty()) {
            MessageHelpers.showMessage(getContext(), R.string.iptv_clipboard_empty);
            return;
        }
        startImport(text.toString(), null);
    }

    private void startFilePicker() {
        AppDialogPresenter.instance(getContext()).closeDialog();
        if (!(getContext() instanceof MotherActivity)) {
            return;
        }
        MotherActivity activity = (MotherActivity) getContext();
        activity.addOnResult((requestCode, resultCode, data) -> {
            if (requestCode == FILE_PICKER_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
                String[] paths = data.getStringArrayExtra(FilePickerActivity.RESULT_FILE_PATHS);
                if (paths == null || paths.length == 0) {
                    String single = data.getStringExtra(FilePickerActivity.RESULT_FILE_PATH);
                    paths = single == null ? new String[0] : new String[]{single};
                }
                List<File> files = new ArrayList<>();
                for (String path : paths) {
                    files.add(new File(path));
                }
                startFiles(files);
            }
        });

        if (PermissionHelpers.hasStoragePermissions(activity)) {
            runFilePicker(activity);
        } else {
            activity.addOnPermissions((requestCode, permissions, grantResults) -> {
                if (requestCode == PermissionHelpers.REQUEST_EXTERNAL_STORAGE && grantResults.length >= 1
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    runFilePicker(activity);
                }
            });
            PermissionHelpers.verifyStoragePermissions(activity);
        }
    }

    private void runFilePicker(Activity activity) {
        File root = FileUtils.getFile(activity, null);
        if (root == null) {
            MessageHelpers.showMessage(getContext(), R.string.iptv_failed);
            return;
        }
        new MaterialFilePicker()
                .withActivity(activity)
                .withTitle(getContext().getString(R.string.iptv_import_file))
                .withRootPath(root.getAbsolutePath())
                .withMultiSelect(true) // tick several files / folders; the picker stays open until "Done"
                .start(FILE_PICKER_REQUEST_CODE);
    }

    private void startFiles(List<File> files) {
        startSources(IptvImportJob.expand(files));
    }

    /** Text / link / clipboard (text) or a single file. */
    private void startImport(String text, File file) {
        if (file != null) {
            startFiles(java.util.Collections.singletonList(file));
        } else {
            List<IptvImportJob.Source> sources = new ArrayList<>();
            sources.add(IptvImportJob.Source.ofText(getContext().getString(R.string.iptv_source_text), text));
            startSources(sources);
        }
    }

    /**
     * Runs the (network + disk heavy) import in the background: all sources at the same time (up to 3 workers),
     * the progress is shown in the IPTV sidebar section.
     */
    private void startSources(List<IptvImportJob.Source> sources) {
        IptvImportJob job = IptvImportJob.instance();

        if (job.isRunning()) {
            IptvImportJob.Snapshot snap = job.snapshot();
            MessageHelpers.showLongMessage(getContext(), getContext().getString(R.string.iptv_busy, snap.done, snap.total));
            return;
        }

        if (sources.isEmpty()) {
            MessageHelpers.showMessage(getContext(), R.string.iptv_nothing_found);
            return;
        }

        IptvSection.attachJob(getContext());

        boolean analyze = mPrefs.isPreviewEnabled();
        if (job.start(sources, mPrefs.load(), mStore, analyze)) {
            MessageHelpers.showLongMessage(getContext(), getContext().getString(
                    analyze ? R.string.iptv_analyze_started : R.string.iptv_import_started, sources.size()));
        }
    }

    // ------------------------------------------------------------------ import settings

    private static final int[] COUNT_CHOICES = {0, 10, 40, 100, 500, 2000};
    private static final int[] DEPTH_CHOICES = {0, 1, 2, 3, 5, 10};
    private static final long MB = 1024L * 1024L;
    private static final long[] SIZE_CHOICES_MB = {0, 16, 64, 256, 1024, 4096};
    private static final int[] TIMEOUT_CHOICES_SEC = {10, 15, 30, 60, 120, 300};

    private void showImportSettings() {
        AppDialogPresenter d = AppDialogPresenter.instance(getContext());
        IptvOptions o = mPrefs.load();

        d.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.iptv_opt_preview),
                getContext().getString(R.string.iptv_opt_preview_desc),
                option -> mPrefs.setPreviewEnabled(option.isSelected()), mPrefs.isPreviewEnabled()));

        // downloads / links
        d.appendRadioCategory(getContext().getString(R.string.iptv_opt_max_fetches), intChoices(COUNT_CHOICES, o.maxFetches, "maxFetches"));
        d.appendRadioCategory(getContext().getString(R.string.iptv_opt_link_depth), intChoices(DEPTH_CHOICES, o.maxLinkDepth, "maxLinkDepth"));
        d.appendRadioCategory(getContext().getString(R.string.iptv_opt_probe), probeChoices(o.probeLimit));
        d.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.iptv_opt_follow_links),
                option -> mPrefs.putBoolean("followRemoteLinks", option.isSelected()), o.followRemoteLinks));
        d.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.iptv_opt_xtream_fallback),
                option -> mPrefs.putBoolean("xtreamFallbacks", option.isSelected()), o.xtreamFallbacks));

        // archives
        d.appendRadioCategory(getContext().getString(R.string.iptv_opt_archive_depth), intChoices(DEPTH_CHOICES, o.maxArchiveDepth, "maxArchiveDepth"));
        d.appendRadioCategory(getContext().getString(R.string.iptv_opt_archive_entries), intChoices(COUNT_CHOICES, o.maxArchiveEntries, "maxArchiveEntries"));
        d.appendRadioCategory(getContext().getString(R.string.iptv_opt_entry_size), sizeChoices(o.maxEntryBytes, "maxEntryBytes"));
        d.appendRadioCategory(getContext().getString(R.string.iptv_opt_total_size), sizeChoices(o.maxArchiveTotalBytes, "maxArchiveTotalBytes"));
        d.appendRadioCategory(getContext().getString(R.string.iptv_opt_download_size), sizeChoices(o.maxDownloadBytes, "maxDownloadBytes"));
        d.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_opt_passwords),
                getContext().getString(R.string.iptv_opt_passwords_desc), option ->
                        SimpleEditDialog.show(getContext(), getContext().getString(R.string.iptv_opt_passwords),
                                getContext().getString(R.string.iptv_opt_passwords_desc), mPrefs.getPasswords(), value -> {
                                    mPrefs.setPasswords(value);
                                    return true;
                                })));

        // network
        d.appendRadioCategory(getContext().getString(R.string.iptv_opt_timeout), timeoutChoices(o.readTimeoutMs));
        d.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_opt_user_agent), option ->
                SimpleEditDialog.show(getContext(), getContext().getString(R.string.iptv_opt_user_agent),
                        HttpIptvFetcher.DEFAULT_USER_AGENT, mPrefs.getUserAgent(), value -> {
                            mPrefs.setUserAgent(value);
                            return true;
                        })));

        d.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_opt_reset), option -> {
            mPrefs.resetAll();
            MessageHelpers.showMessage(getContext(), R.string.msg_done);
            d.closeDialog();
        }));
        d.showDialog(getContext().getString(R.string.iptv_import_settings));
    }

    private List<OptionItem> intChoices(int[] values, int current, String key) {
        List<OptionItem> items = new ArrayList<>();
        for (int v : values) {
            items.add(UiOptionItem.from(v == 0 ? getContext().getString(R.string.iptv_unlimited) : String.valueOf(v),
                    option -> mPrefs.putInt(key, v), v == current));
        }
        return items;
    }

    private List<OptionItem> sizeChoices(long currentBytes, String key) {
        List<OptionItem> items = new ArrayList<>();
        for (long mb : SIZE_CHOICES_MB) {
            items.add(UiOptionItem.from(mb == 0 ? getContext().getString(R.string.iptv_unlimited) : mb + " MB",
                    option -> mPrefs.putLong(key, mb * MB), mb * MB == currentBytes));
        }
        return items;
    }

    private List<OptionItem> probeChoices(int current) {
        List<OptionItem> items = new ArrayList<>();
        int[][] choices = {{20, R.string.iptv_probe_auto}, {0, R.string.iptv_probe_always}, {-1, R.string.iptv_probe_never}};
        for (int[] c : choices) {
            items.add(UiOptionItem.from(getContext().getString(c[1]), option -> mPrefs.putInt("probeLimit", c[0]), c[0] == current));
        }
        return items;
    }

    private List<OptionItem> timeoutChoices(int currentMs) {
        List<OptionItem> items = new ArrayList<>();
        for (int sec : TIMEOUT_CHOICES_SEC) {
            items.add(UiOptionItem.from(sec + " s", option -> mPrefs.putInt("readTimeoutMs", sec * 1000), sec * 1000 == currentMs));
        }
        return items;
    }

    // ------------------------------------------------------------------ browse / play

    private void showGroups() {
        IptvPlaylist library = mStore.load();
        if (library.isEmpty()) {
            MessageHelpers.showMessage(getContext(), R.string.iptv_empty);
            return;
        }
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        List<OptionItem> options = new ArrayList<>();
        for (Map.Entry<String, List<IptvChannel>> e : library.groups().entrySet()) {
            final List<IptvChannel> channels = e.getValue();
            final String title = e.getKey().isEmpty() ? getContext().getString(R.string.iptv_ungrouped) : e.getKey();
            options.add(UiOptionItem.from(title + " (" + channels.size() + ")", option -> showChannels(title, channels, 0)));
        }
        dialog.appendStringsCategory(getContext().getString(R.string.iptv_browse), options);
        dialog.showDialog(getContext().getString(R.string.iptv_title));
    }

    private void showChannels(String groupTitle, List<IptvChannel> channels, int page) {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        List<OptionItem> options = new ArrayList<>();
        int from = page * PAGE_SIZE;
        int to = Math.min(channels.size(), from + PAGE_SIZE);
        for (int i = from; i < to; i++) {
            final IptvChannel c = channels.get(i);
            options.add(UiOptionItem.from(c.name == null || c.name.isEmpty() ? c.url : c.name, option -> play(c)));
        }
        if (to < channels.size()) {
            options.add(UiOptionItem.from(getContext().getString(R.string.iptv_next_page) + " (" + (page + 2) + ")",
                    option -> showChannels(groupTitle, channels, page + 1)));
        }
        dialog.appendStringsCategory(groupTitle, options);
        dialog.showDialog(groupTitle);
    }

    private void play(IptvChannel channel) {
        IptvSection.play(getContext(), channel); // shared with the sidebar section (also records "recent")
    }
}
