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
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvPortalMemory;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvSourceItem;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvSourceScan;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvPrefs;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvPlaylist;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvStore;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvUrls;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvUserData;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvWebReceiver;
import com.liskovsoft.smartyoutubetv2.common.misc.MotherActivity;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

import java.io.File;
import java.io.IOException;
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
        dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_open_manager),
                getContext().getString(R.string.iptv_open_manager_desc), option -> {
                    AppDialogPresenter.instance(getContext()).closeDialog();
                    com.liskovsoft.smartyoutubetv2.common.misc.IptvManagerActivity.start(getContext(), "all");
                }));
        dialog.appendRadioCategory(getContext().getString(R.string.iptv_ui_mode), uiModeChoices());
        dialog.appendRadioCategory(getContext().getString(R.string.iptv_player_mode), playerChoices());
        dialog.appendRadioCategory(getContext().getString(R.string.iptv_quality_limit), qualityChoices());
        dialog.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.iptv_quality_swap),
                getContext().getString(R.string.iptv_quality_swap_desc),
                option -> mPrefs.setPreferLowerCopy(option.isSelected()), mPrefs.isPreferLowerCopy()));
        dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_import_link), getContext().getString(R.string.iptv_import_link_desc), option -> showLinkDialog()));
        dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_import_clipboard), option -> importFromClipboard()));
        dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_receive),
                getContext().getString(R.string.iptv_receive_desc), option -> {
                    AppDialogPresenter.instance(getContext()).closeDialog();
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(this::showReceiver);
                }));
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

    private List<OptionItem> uiModeChoices() {
        int current = mPrefs.getUiMode();
        List<OptionItem> items = new ArrayList<>();
        items.add(UiOptionItem.from(getContext().getString(R.string.iptv_ui_mode_cards),
                option -> mPrefs.setUiMode(IptvPrefs.UI_CARDS), current == IptvPrefs.UI_CARDS));
        items.add(UiOptionItem.from(getContext().getString(R.string.iptv_ui_mode_manager),
                option -> mPrefs.setUiMode(IptvPrefs.UI_MANAGER), current == IptvPrefs.UI_MANAGER));
        return items;
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

    private List<OptionItem> qualityChoices() {
        int current = mPrefs.getMaxQuality();
        List<OptionItem> items = new ArrayList<>();
        for (int height : IptvPrefs.QUALITY_STEPS) {
            String label = height == 0 ? getContext().getString(R.string.iptv_quality_auto) : height + "p";
            items.add(UiOptionItem.from(label, option -> mPrefs.setMaxQuality(height), current == height));
        }
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
    // ------------------------------------------------------------------ receive from the phone (Wi-Fi)

    private static IptvWebReceiver sReceiver;

    private void showReceiver() {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        if (sReceiver == null || !sReceiver.isRunning()) {
            String pin = String.format(java.util.Locale.US, "%04d", new java.security.SecureRandom().nextInt(10000));
            final Context app = getContext().getApplicationContext();
            sReceiver = new IptvWebReceiver((text, files) -> onPhoneData(app, text, files), pin,
                    mPrefs.getReceiveMaxMb() * 1024L * 1024L);
            try {
                sReceiver.start();
            } catch (java.io.IOException e) {
                sReceiver = null;
                MessageHelpers.showLongMessage(getContext(), getContext().getString(R.string.iptv_receive_failed));
                return;
            }
        }
        List<String> addresses = IptvWebReceiver.localAddresses();
        if (addresses.isEmpty()) {
            sReceiver.stop();
            MessageHelpers.showLongMessage(getContext(), getContext().getString(R.string.iptv_receive_no_network));
            return;
        }
        List<OptionItem> lines = new ArrayList<>();
        for (String ip : addresses) {
            lines.add(UiOptionItem.from("http://" + ip + ":" + sReceiver.port(), option -> { }));
        }
        lines.add(UiOptionItem.from("PIN: " + sReceiver.pin(), option -> { }));
        dialog.appendStringsCategory(getContext().getString(R.string.iptv_receive_steps), lines);
        dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_receive_stop), option -> {
            if (sReceiver != null) {
                sReceiver.stop();
            }
            AppDialogPresenter.instance(getContext()).closeDialog();
        }));
        dialog.showDialog(getContext().getString(R.string.iptv_receive_title));
    }

    /** Runs on the web server thread: saves what the phone sent and starts the import on the main thread. */
    private static String onPhoneData(Context app, String text, List<IptvWebReceiver.Upload> files) {
        if (IptvImportJob.instance().isRunning()) {
            return app.getString(R.string.iptv_receive_busy);
        }
        final List<IptvImportJob.Source> sources = new ArrayList<>();
        if (text != null && !text.trim().isEmpty()) {
            sources.add(IptvImportJob.Source.ofText("phone", text));
        }
        File inbox = new File(app.getCacheDir(), "iptv_inbox");
        long now = System.currentTimeMillis();
        for (IptvWebReceiver.Upload u : files) {
            try {
                String safe = u.name.replaceAll("[\\\\/:*?\"<>|]+", "_");
                File dir = new File(inbox, String.valueOf(now)); // one folder per upload: the file keeps its own name
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    continue;
                }
                File f = new File(dir, safe.isEmpty() ? "list.txt" : safe);
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                    out.write(u.data);
                }
                sources.add(IptvImportJob.Source.ofFile(f));
                now++;
            } catch (java.io.IOException e) {
                // skip this file
            }
        }
        if (sources.isEmpty()) {
            return app.getString(R.string.iptv_receive_nothing);
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> IptvSettingsPresenter.instance(app).importSources(sources));
        return app.getString(R.string.iptv_receive_ok, sources.size());
    }

    public void importSources(List<IptvImportJob.Source> sources) {
        startSources(sources);
    }

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

        // Look first: open the sources without any download and let the user pick what is really fetched
        final Context app = getContext();
        MessageHelpers.showMessage(app, R.string.iptv_pick_scanning);
        final IptvOptions scanOptions = mPrefs.load();
        new Thread(() -> {
            List<IptvSourceItem> items;
            try {
                items = IptvSourceScan.scan(sources, scanOptions);
            } catch (Throwable t) {
                items = new ArrayList<>();
            }
            final List<IptvSourceItem> found = items;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                int mode = mPrefs.getPickerMode();
                boolean ask = found.size() > 1 && (mode == IptvPrefs.ASK_ALWAYS
                        || (mode == IptvPrefs.ASK_MANY && found.size() > mPrefs.getPickerThreshold()));
                if (ask) {
                    IptvPortalPickerPresenter.show(app, found, skip -> launch(sources, skip));
                } else {
                    // no window: everything is taken, except the portals that failed before (when that is switched on)
                    launch(sources, found.size() > 1 ? IptvPortalPickerPresenter.rememberedSkips(app, found) : null);
                }
            });
        }, "iptv-scan").start();
    }

    /** @param skipKeys what the user un-ticked, null = take everything */
    private void launch(List<IptvImportJob.Source> sources, java.util.Set<String> skipKeys) {
        IptvImportJob job = IptvImportJob.instance();
        if (job.isRunning()) {
            IptvImportJob.Snapshot snap = job.snapshot();
            MessageHelpers.showLongMessage(getContext(), getContext().getString(R.string.iptv_busy, snap.done, snap.total));
            return;
        }

        IptvSection.attachJob(getContext());

        boolean analyze = mPrefs.isPreviewEnabled();
        IptvOptions options = mPrefs.load();
        options.skipKeys = skipKeys;
        if (mPrefs.isRememberFailures()) {
            final IptvPortalMemory memory = IptvPortalMemory.instance(getContext());
            options.portalListener = memory::recordPortal; // the next picker marks the portals that failed now
        }
        if (job.start(sources, options, mStore, analyze)) {
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

    private static final int[] THRESHOLD_CHOICES = {3, 5, 10, 20, 50};
    private static final int[] DAYS_CHOICES = {1, 3, 7, 30, 0};
    private static final int[] PARALLEL_CHOICES = {1, 2, 4, 8};
    private static final int[] PARALLEL_MAC_CHOICES = {1, 2, 3, 5};
    private static final int[] CONNECT_CHOICES_SEC = {3, 6, 10, 15};
    private static final int[] HANDSHAKE_CHOICES_SEC = {6, 12, 20, 30};
    private static final int[] DEADLINE_CHOICES_SEC = {60, 120, 300, 600};

    /** The import settings are split into sections; this is the list of them. */
    private void showImportSettings() {
        AppDialogPresenter d = AppDialogPresenter.instance(getContext());
        section(d, R.string.iptv_set_picker, R.string.iptv_set_picker_desc, this::showPickerSettings);
        section(d, R.string.iptv_set_stalker, R.string.iptv_set_stalker_desc, this::showStalkerSettings);
        section(d, R.string.iptv_set_downloads, R.string.iptv_set_downloads_desc, this::showDownloadSettings);
        section(d, R.string.iptv_set_archives, R.string.iptv_set_archives_desc, this::showArchiveSettings);
        section(d, R.string.iptv_set_network, R.string.iptv_set_network_desc, this::showNetworkSettings);
        section(d, R.string.iptv_set_library, R.string.iptv_set_library_desc, this::showLibrarySettings);
        d.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_opt_reset), option -> {
            mPrefs.resetAll();
            MessageHelpers.showMessage(getContext(), R.string.msg_done);
            d.closeDialog();
        }));
        d.showDialog(getContext().getString(R.string.iptv_import_settings));
    }

    private void section(AppDialogPresenter hub, int titleRes, int descRes, Runnable open) {
        hub.appendSingleButton(UiOptionItem.from(getContext().getString(titleRes), getContext().getString(descRes), option -> {
            hub.closeDialog();
            new android.os.Handler(android.os.Looper.getMainLooper()).post(open);
        }));
    }

    /** Shows one section; the Back key returns to the list of sections. */
    private void showSection(AppDialogPresenter d, int titleRes) {
        d.showDialog(getContext().getString(titleRes), () ->
                new android.os.Handler(android.os.Looper.getMainLooper()).post(this::showImportSettings));
    }

    // ---- 1. source picker

    private void showPickerSettings() {
        AppDialogPresenter d = AppDialogPresenter.instance(getContext());
        int mode = mPrefs.getPickerMode();
        List<OptionItem> when = new ArrayList<>();
        int[] modes = {IptvPrefs.ASK_ALWAYS, IptvPrefs.ASK_MANY, IptvPrefs.ASK_NEVER};
        int[] names = {R.string.iptv_ps_when_always, R.string.iptv_ps_when_many, R.string.iptv_ps_when_never};
        for (int i = 0; i < modes.length; i++) {
            final int m = modes[i];
            when.add(UiOptionItem.from(getContext().getString(names[i]), option -> mPrefs.setPickerMode(m), m == mode));
        }
        d.appendRadioCategory(getContext().getString(R.string.iptv_ps_when), when);

        int threshold = mPrefs.getPickerThreshold();
        List<OptionItem> many = new ArrayList<>();
        for (int n : THRESHOLD_CHOICES) {
            many.add(UiOptionItem.from(String.valueOf(n), option -> mPrefs.setPickerThreshold(n), n == threshold));
        }
        d.appendRadioCategory(getContext().getString(R.string.iptv_ps_threshold), many);

        d.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.iptv_ps_group),
                getContext().getString(R.string.iptv_ps_group_desc),
                option -> mPrefs.setGroupByDomain(option.isSelected()), mPrefs.isGroupByDomain()));
        d.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.iptv_ps_remember),
                getContext().getString(R.string.iptv_ps_remember_desc),
                option -> mPrefs.setRememberFailures(option.isSelected()), mPrefs.isRememberFailures()));
        d.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.iptv_ps_untick),
                getContext().getString(R.string.iptv_ps_untick_desc),
                option -> mPrefs.setAutoUntick(option.isSelected()), mPrefs.isAutoUntick()));

        int days = mPrefs.getMemoryDays();
        List<OptionItem> forget = new ArrayList<>();
        for (int n : DAYS_CHOICES) {
            forget.add(UiOptionItem.from(n == 0 ? getContext().getString(R.string.iptv_ps_forever)
                    : getContext().getString(R.string.iptv_ps_days_value, n), option -> mPrefs.setMemoryDays(n), n == days));
        }
        d.appendRadioCategory(getContext().getString(R.string.iptv_ps_days), forget);

        final IptvPortalMemory memory = IptvPortalMemory.instance(getContext());
        d.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_ps_clear, memory.failedCount()), option -> {
            memory.clear();
            MessageHelpers.showMessage(getContext(), R.string.msg_done);
        }));
        showSection(d, R.string.iptv_set_picker);
    }

    // ---- 2. Stalker portals

    private void showStalkerSettings() {
        AppDialogPresenter d = AppDialogPresenter.instance(getContext());
        IptvOptions o = mPrefs.load();

        d.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.iptv_stalker_live),
                option -> mPrefs.setBool("stalkerLive", option.isSelected()), mPrefs.getBool("stalkerLive", true)));
        d.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.iptv_stalker_movies),
                getContext().getString(R.string.iptv_stalker_big_desc),
                option -> mPrefs.setBool("stalkerMovies", option.isSelected()), mPrefs.getBool("stalkerMovies", false)));
        d.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.iptv_stalker_series),
                getContext().getString(R.string.iptv_stalker_big_desc),
                option -> mPrefs.setBool("stalkerSeries", option.isSelected()), mPrefs.getBool("stalkerSeries", false)));
        d.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.iptv_stalker_per_mac),
                getContext().getString(R.string.iptv_stalker_per_mac_desc),
                option -> mPrefs.setBool("stalkerPerMac", option.isSelected()), mPrefs.getBool("stalkerPerMac", true)));
        int maxItems = mPrefs.getIntValue("stalkerMaxItems", 5000);
        d.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_stalker_max_items),
                maxItems <= 0 ? getContext().getString(R.string.iptv_receive_limit_unlimited) : String.valueOf(maxItems), option ->
                        SimpleEditDialog.show(getContext(),
                                getContext().getString(R.string.iptv_stalker_max_items), getContext().getString(R.string.iptv_stalker_max_items_hint),
                                String.valueOf(mPrefs.getIntValue("stalkerMaxItems", 5000)), value -> {
                                    try {
                                        mPrefs.putInt("stalkerMaxItems", Math.max(0, Integer.parseInt(value.trim())));
                                    } catch (NumberFormatException e) {
                                        return false;
                                    }
                                    return true;
                                })));
        d.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_ps_clear_types), option -> {
            mPrefs.clearPortalTypes();
            MessageHelpers.showMessage(getContext(), R.string.msg_done);
        }));

        // speed and time limits
        d.appendRadioCategory(getContext().getString(R.string.iptv_st_parallel), numChoices(PARALLEL_CHOICES, o.portalParallel, "portalParallel"));
        d.appendRadioCategory(getContext().getString(R.string.iptv_st_parallel_macs), numChoices(PARALLEL_MAC_CHOICES, o.portalParallelMacs, "portalParallelMacs"));
        d.appendRadioCategory(getContext().getString(R.string.iptv_st_connect), secChoices(CONNECT_CHOICES_SEC, o.portalConnectTimeoutMs, "portalConnectTimeoutMs"));
        d.appendRadioCategory(getContext().getString(R.string.iptv_st_read), secChoices(HANDSHAKE_CHOICES_SEC, o.portalReadTimeoutMs, "portalReadTimeoutMs"));
        d.appendRadioCategory(getContext().getString(R.string.iptv_st_deadline), secChoices(DEADLINE_CHOICES_SEC, (int) o.portalDeadlineMs, "portalDeadlineMs"));
        showSection(d, R.string.iptv_set_stalker);
    }

    // ---- 3. downloads and links

    private void showDownloadSettings() {
        AppDialogPresenter d = AppDialogPresenter.instance(getContext());
        IptvOptions o = mPrefs.load();
        d.appendRadioCategory(getContext().getString(R.string.iptv_opt_max_fetches), intChoices(COUNT_CHOICES, o.maxFetches, "maxFetches"));
        d.appendRadioCategory(getContext().getString(R.string.iptv_opt_link_depth), intChoices(DEPTH_CHOICES, o.maxLinkDepth, "maxLinkDepth"));
        d.appendRadioCategory(getContext().getString(R.string.iptv_opt_probe), probeChoices(o.probeLimit));
        d.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.iptv_opt_follow_links),
                option -> mPrefs.putBoolean("followRemoteLinks", option.isSelected()), o.followRemoteLinks));
        d.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.iptv_opt_xtream_fallback),
                option -> mPrefs.putBoolean("xtreamFallbacks", option.isSelected()), o.xtreamFallbacks));
        d.appendRadioCategory(getContext().getString(R.string.iptv_opt_download_size), sizeChoices(o.maxDownloadBytes, "maxDownloadBytes"));
        showSection(d, R.string.iptv_set_downloads);
    }

    // ---- 4. archives

    private void showArchiveSettings() {
        AppDialogPresenter d = AppDialogPresenter.instance(getContext());
        IptvOptions o = mPrefs.load();
        d.appendRadioCategory(getContext().getString(R.string.iptv_opt_archive_depth), intChoices(DEPTH_CHOICES, o.maxArchiveDepth, "maxArchiveDepth"));
        d.appendRadioCategory(getContext().getString(R.string.iptv_opt_archive_entries), intChoices(COUNT_CHOICES, o.maxArchiveEntries, "maxArchiveEntries"));
        d.appendRadioCategory(getContext().getString(R.string.iptv_opt_entry_size), sizeChoices(o.maxEntryBytes, "maxEntryBytes"));
        d.appendRadioCategory(getContext().getString(R.string.iptv_opt_total_size), sizeChoices(o.maxArchiveTotalBytes, "maxArchiveTotalBytes"));
        d.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_opt_passwords),
                getContext().getString(R.string.iptv_opt_passwords_desc), option ->
                        SimpleEditDialog.show(getContext(), getContext().getString(R.string.iptv_opt_passwords),
                                getContext().getString(R.string.iptv_opt_passwords_desc), mPrefs.getPasswords(), value -> {
                                    mPrefs.setPasswords(value);
                                    return true;
                                })));
        showSection(d, R.string.iptv_set_archives);
    }

    // ---- 5. network

    private void showNetworkSettings() {
        AppDialogPresenter d = AppDialogPresenter.instance(getContext());
        IptvOptions o = mPrefs.load();
        d.appendRadioCategory(getContext().getString(R.string.iptv_opt_timeout), timeoutChoices(o.readTimeoutMs));
        d.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_opt_user_agent), option ->
                SimpleEditDialog.show(getContext(), getContext().getString(R.string.iptv_opt_user_agent),
                        HttpIptvFetcher.DEFAULT_USER_AGENT, mPrefs.getUserAgent(), value -> {
                            mPrefs.setUserAgent(value);
                            return true;
                        })));
        showSection(d, R.string.iptv_set_network);
    }

    // ---- 6. library and display

    private void showLibrarySettings() {
        AppDialogPresenter d = AppDialogPresenter.instance(getContext());
        d.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.iptv_opt_preview),
                getContext().getString(R.string.iptv_opt_preview_desc),
                option -> mPrefs.setPreviewEnabled(option.isSelected()), mPrefs.isPreviewEnabled()));
        d.appendSingleSwitch(UiOptionItem.from(getContext().getString(R.string.iptv_opt_hide_prefix),
                getContext().getString(R.string.iptv_opt_hide_prefix_desc),
                option -> mPrefs.setHidePrefix(option.isSelected()), mPrefs.isHidePrefix()));
        d.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_opt_blacklist),
                mPrefs.getBlacklist().isEmpty() ? getContext().getString(R.string.iptv_opt_blacklist_desc) : mPrefs.getBlacklist(), option ->
                        SimpleEditDialog.show(getContext(), getContext().getString(R.string.iptv_opt_blacklist),
                                getContext().getString(R.string.iptv_opt_blacklist_desc), mPrefs.getBlacklist(), value -> {
                                    mPrefs.setBlacklist(value);
                                    return true;
                                })));
        d.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_blacklist_apply),
                getContext().getString(R.string.iptv_blacklist_apply_desc), option -> {
                    String words = mPrefs.getBlacklist();
                    if (words.trim().isEmpty()) {
                        MessageHelpers.showMessage(getContext(), R.string.iptv_blacklist_empty);
                        return;
                    }
                    int removed;
                    try {
                        removed = mStore.removeBlacklisted(words);
                    } catch (IOException e) {
                        MessageHelpers.showMessage(getContext(), R.string.iptv_failed);
                        return;
                    }
                    MessageHelpers.showLongMessage(getContext(), getContext().getString(R.string.iptv_blacklist_removed, removed));
                    IptvSection.onLibraryChanged(getContext());
                }));
        int limitMb = mPrefs.getReceiveMaxMb();
        d.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.iptv_receive_limit),
                limitMb <= 0 ? getContext().getString(R.string.iptv_receive_limit_unlimited)
                        : getContext().getString(R.string.iptv_receive_limit_value, limitMb), option ->
                        SimpleEditDialog.show(getContext(),
                                getContext().getString(R.string.iptv_receive_limit), getContext().getString(R.string.iptv_receive_limit_hint),
                                String.valueOf(mPrefs.getReceiveMaxMb()), value -> {
                                    try {
                                        mPrefs.setReceiveMaxMb(Integer.parseInt(value.trim()));
                                    } catch (NumberFormatException e) {
                                        return false;
                                    }
                                    if (sReceiver != null) {
                                        sReceiver.stop(); // the new limit applies the next time it is started
                                    }
                                    return true;
                                })));
        showSection(d, R.string.iptv_set_library);
    }

    private List<OptionItem> numChoices(int[] values, int current, String key) {
        List<OptionItem> items = new ArrayList<>();
        for (int v : values) {
            items.add(UiOptionItem.from(String.valueOf(v), option -> mPrefs.putInt(key, v), v == current));
        }
        return items;
    }

    /** Seconds on screen, milliseconds in the settings (int or long keys: {@code portalDeadlineMs} is a long). */
    private List<OptionItem> secChoices(int[] seconds, int currentMs, String key) {
        List<OptionItem> items = new ArrayList<>();
        for (int sec : seconds) {
            items.add(UiOptionItem.from(sec + " s", option -> {
                if (key.equals("portalDeadlineMs")) {
                    mPrefs.putLong(key, sec * 1000L);
                } else {
                    mPrefs.putInt(key, sec * 1000);
                }
            }, sec * 1000 == currentMs));
        }
        return items;
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
