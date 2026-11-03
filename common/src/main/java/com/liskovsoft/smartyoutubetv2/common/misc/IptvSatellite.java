package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Build;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvChannel;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvPrefs;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Receiver / satellite" shortcut: opens the channels of the receiver itself (its tuner is not reachable from a normal
 * app, so the best a shortcut can do is to jump to whatever shows them). Several ways are offered, because which one
 * works depends on the receiver's firmware; the user tries them one by one from Settings > IPTV > Receiver / satellite.
 */
public final class IptvSatellite {
    public static final int MODE_OFF = 0;
    public static final int MODE_HOME = 1;
    public static final int MODE_LIVE_TV = 2;
    public static final int MODE_TV_INPUT = 3;
    public static final int MODE_APP = 4;
    public static final int MODE_PACKAGE = 5;
    public static final int MODE_STREAM = 6;

    /** Package names of the stock Android TV "Live TV" / "Live Channels" apps. */
    private static final String[] LIVE_TV_PACKAGES = {"com.google.android.tv", "com.android.tv"};
    /** Words in an app's name or package that make it a likely satellite / live TV app. */
    private static final String[] HINTS = {"sat", "dvb", "tuner", "live", "receiver", "icone", "wegoo", "iptv", "channel",
            "\u0642\u0646\u0648\u0627\u062A", "\u0633\u0627\u062A\u0644\u0627\u064A\u062A", "\u0631\u0633\u064A\u0641\u0631"};

    public static final class AppEntry {
        public String label;
        public String pkg;
        public boolean suggested;
    }

    public static final class InputEntry {
        public String id;
        public String label;
        public boolean passthrough;
    }

    private IptvSatellite() {
    }

    // ------------------------------------------------------------------ launching

    /** Opens whatever the saved mode says. Shows a message when it cannot. */
    public static void launch(Context context) {
        IptvPrefs prefs = IptvPrefs.instance(context);
        int mode = prefs.getSatMode();
        boolean ok;
        String what;
        switch (mode) {
            case MODE_HOME:
                ok = openHome(context);
                what = context.getString(R.string.iptv_sat_home);
                break;
            case MODE_LIVE_TV:
                ok = openLiveTv(context);
                what = context.getString(R.string.iptv_sat_livetv);
                break;
            case MODE_TV_INPUT:
                ok = openInput(context, prefs.getSatInput());
                what = context.getString(R.string.iptv_sat_input, valueOrNone(context, prefs.getSatInputLabel()));
                break;
            case MODE_APP:
                ok = openApp(context, prefs.getSatApp());
                what = context.getString(R.string.iptv_sat_app, valueOrNone(context, prefs.getSatAppLabel()));
                break;
            case MODE_PACKAGE:
                ok = openApp(context, prefs.getSatPackage());
                what = context.getString(R.string.iptv_sat_package, valueOrNone(context, prefs.getSatPackage()));
                break;
            case MODE_STREAM:
                ok = openStream(context, prefs.getSatStream());
                what = context.getString(R.string.iptv_sat_stream, valueOrNone(context, prefs.getSatStream()));
                break;
            default:
                return;
        }
        if (!ok) {
            MessageHelpers.showLongMessage(context, context.getString(R.string.iptv_sat_failed, what));
        }
    }

    private static String valueOrNone(Context context, String value) {
        return value == null || value.trim().isEmpty() ? context.getString(R.string.iptv_sat_none_chosen) : value;
    }

    /** The system home screen: on many receivers that screen is the list of satellite channels. */
    public static boolean openHome(Context context) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        return start(context, intent);
    }

    /** The stock Android TV "Live TV" app, or whatever handles the TV channels database. */
    public static boolean openLiveTv(Context context) {
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                if (start(context, new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI))) {
                    return true;
                }
            } catch (Throwable ignored) {
                // fall through to the known packages
            }
        }
        for (String pkg : LIVE_TV_PACKAGES) {
            if (openApp(context, pkg)) {
                return true;
            }
        }
        for (AppEntry app : installedApps(context)) {
            String l = app.label == null ? "" : app.label.toLowerCase(Locale.ROOT);
            if (l.equals("live tv") || l.equals("live channels") || l.equals("tv")) {
                if (openApp(context, app.pkg)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** One of the device's TV inputs (the tuner, HDMI ...). Inputs without their own screen open the Live TV app. */
    public static boolean openInput(Context context, String inputId) {
        if (inputId == null || inputId.isEmpty() || Build.VERSION.SDK_INT < 21) {
            return false;
        }
        try {
            InputEntry found = null;
            for (InputEntry e : tvInputs(context)) {
                if (inputId.equals(e.id)) {
                    found = e;
                }
            }
            if (found != null && found.passthrough) {
                Uri uri = TvContract.buildChannelUriForPassthroughInput(inputId);
                if (start(context, new Intent(Intent.ACTION_VIEW, uri))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // try the Live TV app below
        }
        return openLiveTv(context);
    }

    /** An installed app, by package name or by "package/activity". */
    public static boolean openApp(Context context, String target) {
        if (target == null || target.trim().isEmpty()) {
            return false;
        }
        target = target.trim();
        try {
            if (target.contains("/")) {
                ComponentName name = ComponentName.unflattenFromString(target);
                if (name == null) {
                    return false;
                }
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.setComponent(name);
                return start(context, intent);
            }
            PackageManager pm = context.getPackageManager();
            Intent intent = null;
            if (Build.VERSION.SDK_INT >= 21) {
                intent = pm.getLeanbackLaunchIntentForPackage(target);
            }
            if (intent == null) {
                intent = pm.getLaunchIntentForPackage(target);
            }
            return intent != null && start(context, intent);
        } catch (Throwable e) {
            return false;
        }
    }

    /** A network stream of the receiver (Sat>IP / udp / http ...) played by the built-in player. */
    public static boolean openStream(Context context, String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        try {
            List<IptvChannel> one = new ArrayList<>();
            one.add(new IptvChannel(context.getString(R.string.iptv_sat_card), url.trim()));
            IptvPlayerActivity.start(context, one, 0);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean start(Context context, Intent intent) {
        try {
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ what the device has

    /** Every app that has a launcher icon (TV or phone style), without this app; likely satellite apps first. */
    public static List<AppEntry> installedApps(Context context) {
        return installedApps(context, false);
    }

    /**
     * Same as above; with includeSystem also the system apps that have no launcher icon (shown by their first activity,
     * as "package/activity").
     */
    public static List<AppEntry> installedApps(Context context, boolean includeSystem) {
        PackageManager pm = context.getPackageManager();
        List<AppEntry> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(context.getPackageName());
        List<String> categories = new ArrayList<>();
        categories.add(Intent.CATEGORY_LAUNCHER);
        if (Build.VERSION.SDK_INT >= 21) {
            categories.add(Intent.CATEGORY_LEANBACK_LAUNCHER);
        }
        for (String category : categories) {
            try {
                Intent query = new Intent(Intent.ACTION_MAIN);
                query.addCategory(category);
                for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
                    if (info.activityInfo == null || !seen.add(info.activityInfo.packageName)) {
                        continue;
                    }
                    AppEntry app = new AppEntry();
                    app.pkg = info.activityInfo.packageName;
                    CharSequence label = info.loadLabel(pm);
                    app.label = label == null ? app.pkg : label.toString();
                    app.suggested = looksLikeSatellite(app.label, app.pkg);
                    result.add(app);
                }
            } catch (Throwable ignored) {
                // keep what was found
            }
        }
        if (includeSystem) {
            addSystemApps(pm, seen, result);
        }
        final Collator collator = Collator.getInstance();
        Collections.sort(result, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                if (a.suggested != b.suggested) {
                    return a.suggested ? -1 : 1;
                }
                return collator.compare(a.label, b.label);
            }
        });
        return result;
    }

    private static void addSystemApps(PackageManager pm, Set<String> seen, List<AppEntry> result) {
        try {
            for (android.content.pm.PackageInfo pi : pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)) {
                if (pi.applicationInfo == null || (pi.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
                        || pi.activities == null || !seen.add(pi.packageName)) {
                    continue;
                }
                android.content.pm.ActivityInfo first = null;
                for (android.content.pm.ActivityInfo a : pi.activities) {
                    if (a.exported && a.enabled) {
                        first = a;
                        break;
                    }
                }
                if (first == null) {
                    continue;
                }
                AppEntry app = new AppEntry();
                app.pkg = pi.packageName + "/" + first.name;
                CharSequence label = pi.applicationInfo.loadLabel(pm);
                app.label = (label == null ? pi.packageName : label.toString()) + " [" + pi.packageName + "]";
                app.suggested = looksLikeSatellite(app.label, pi.packageName);
                result.add(app);
            }
        } catch (Throwable ignored) {
            // keep what was found
        }
    }

    private static boolean looksLikeSatellite(String label, String pkg) {
        String text = ((label == null ? "" : label) + " " + (pkg == null ? "" : pkg)).toLowerCase(Locale.ROOT);
        for (String hint : HINTS) {
            if (text.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** The device's TV inputs (tuner, HDMI ...). Empty when the firmware has none. */
    public static List<InputEntry> tvInputs(Context context) {
        List<InputEntry> result = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 21) {
            return result;
        }
        try {
            TvInputManager manager = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
            if (manager == null) {
                return result;
            }
            for (TvInputInfo info : manager.getTvInputList()) {
                InputEntry e = new InputEntry();
                e.id = info.getId();
                CharSequence label = info.loadLabel(context);
                e.label = label == null ? e.id : label.toString();
                e.passthrough = info.isPassthroughInput();
                result.add(e);
            }
        } catch (Throwable ignored) {
            // no TV input framework on this device
        }
        return result;
    }

    /** A plain text summary of what this device offers, to help choosing one of the options. */
    public static String report(Context context) {
        StringBuilder sb = new StringBuilder();
        PackageManager pm = context.getPackageManager();

        String home = context.getString(R.string.iptv_sat_none_chosen);
        try {
            Intent homeIntent = new Intent(Intent.ACTION_MAIN);
            homeIntent.addCategory(Intent.CATEGORY_HOME);
            ResolveInfo info = pm.resolveActivity(homeIntent, 0);
            if (info != null && info.activityInfo != null) {
                home = info.loadLabel(pm) + " (" + info.activityInfo.packageName + ")";
            }
        } catch (Throwable ignored) {
            // keep the default text
        }
        sb.append(context.getString(R.string.iptv_sat_rep_home, home)).append("\n\n");

        StringBuilder live = new StringBuilder();
        for (String pkg : LIVE_TV_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                live.append(pkg).append("  ");
            } catch (Throwable ignored) {
                // not installed
            }
        }
        sb.append(context.getString(R.string.iptv_sat_rep_live,
                live.length() == 0 ? context.getString(R.string.iptv_sat_rep_none) : live.toString().trim())).append("\n\n");

        StringBuilder inputs = new StringBuilder();
        for (InputEntry e : tvInputs(context)) {
            inputs.append("\n  - ").append(e.label).append(e.passthrough ? "  [passthrough]" : "");
        }
        sb.append(context.getString(R.string.iptv_sat_rep_inputs,
                inputs.length() == 0 ? context.getString(R.string.iptv_sat_rep_none) : inputs.toString())).append("\n\n");

        StringBuilder apps = new StringBuilder();
        for (AppEntry app : installedApps(context)) {
            if (app.suggested) {
                apps.append("\n  - ").append(app.label).append("  (").append(app.pkg).append(")");
            }
        }
        sb.append(context.getString(R.string.iptv_sat_rep_apps,
                apps.length() == 0 ? context.getString(R.string.iptv_sat_rep_none) : apps.toString()));
        return sb.toString();
    }
}
