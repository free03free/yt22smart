package com.liskovsoft.smartyoutubetv2.common.misc;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.iptv.IptvUserData;

/**
 * PIN dialogs for locked IPTV channels (channel manager + built-in player).
 * The PIN is stored with the other IPTV user data; an empty PIN means "not created yet".
 */
public final class IptvPin {
    private static final int MIN_LENGTH = 4;
    /** A locked channel the user has just unlocked in the manager: the player may start it once without asking again. */
    private static String sAllowedOnce;

    private IptvPin() {
    }

    public static synchronized void allowOnce(String url) {
        sAllowedOnce = url == null ? null : url.trim();
    }

    /** @return true (once) if the channel was unlocked right before the player was opened */
    public static synchronized boolean consumeAllowance(String url) {
        if (url != null && url.trim().equals(sAllowedOnce)) {
            sAllowedOnce = null;
            return true;
        }
        return false;
    }

    /** Runs {@code ok} when a PIN exists; otherwise asks the user to create one first. */
    public static void ensureSet(Activity activity, Runnable ok) {
        if (!IptvUserData.instance(activity).getPin().isEmpty()) {
            ok.run();
            return;
        }
        askNewPin(activity, ok);
    }

    /** Asks for the current PIN. {@code ok} runs when it is right; {@code cancel} (may be null) when it is wrong or dismissed. */
    public static void verify(final Activity activity, final Runnable ok, final Runnable cancel) {
        final String pin = IptvUserData.instance(activity).getPin();
        if (pin.isEmpty()) {
            ok.run();
            return;
        }
        final EditText field = field(activity);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.iptv_mgr_pin_enter)
                .setView(field)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (pin.equals(field.getText().toString().trim())) {
                        ok.run();
                    } else {
                        Toast.makeText(activity, R.string.iptv_mgr_pin_wrong, Toast.LENGTH_SHORT).show();
                        if (cancel != null) {
                            cancel.run();
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    if (cancel != null) {
                        cancel.run();
                    }
                })
                .setOnCancelListener(dialog -> {
                    if (cancel != null) {
                        cancel.run();
                    }
                })
                .show();
    }

    /** Verifies the current PIN (when there is one) and then asks for a new one. */
    public static void change(final Activity activity) {
        verify(activity, () -> askNewPin(activity, null), null);
    }

    private static void askNewPin(final Activity activity, final Runnable ok) {
        final EditText field = field(activity);
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.iptv_mgr_pin_create, MIN_LENGTH))
                .setView(field)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String value = field.getText().toString().trim();
                    if (value.length() < MIN_LENGTH) {
                        Toast.makeText(activity, activity.getString(R.string.iptv_mgr_pin_create, MIN_LENGTH), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    IptvUserData.instance(activity).setPin(value);
                    Toast.makeText(activity, R.string.iptv_mgr_pin_saved, Toast.LENGTH_SHORT).show();
                    if (ok != null) {
                        ok.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static EditText field(Activity activity) {
        EditText field = new EditText(activity);
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        field.setSingleLine(true);
        return field;
    }
}
