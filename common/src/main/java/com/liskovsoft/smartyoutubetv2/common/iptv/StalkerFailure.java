package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** Why a Stalker portal / MAC could not be used, in a form the user can act on. Pure Java. */
public enum StalkerFailure {
    TIMEOUT("no answer (timed out)"),
    DNS("domain not found"),
    REFUSED("server refused the connection"),
    MAC_REJECTED("MAC rejected (expired or blocked)"),
    AUTH("login refused (HTTP 401/403)"),
    HTTP("server returned an error"),
    NOT_PORTAL("not a Stalker portal (wrong address?)"),
    NO_CHANNELS("no channels returned"),
    OTHER("connection failed");

    public final String label;

    StalkerFailure(String label) {
        this.label = label;
    }

    /** True when the server itself is unreachable (other MACs on the same portal will fail the same way). */
    public boolean isNetworkDown() {
        return this == TIMEOUT || this == DNS || this == REFUSED;
    }

    public static StalkerFailure classify(Throwable error) {
        Throwable t = error;
        for (int i = 0; t != null && i < 6; i++) {
            if (t instanceof SocketTimeoutException) {
                return TIMEOUT;
            }
            if (t instanceof UnknownHostException) {
                return DNS;
            }
            if (t instanceof ConnectException || t instanceof NoRouteToHostException) {
                return REFUSED;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        String m = error == null || error.getMessage() == null ? "" : error.getMessage();
        if (m.startsWith("MAC rejected")) {
            return MAC_REJECTED;
        }
        if (m.startsWith("HTTP 401") || m.startsWith("HTTP 403")) {
            return AUTH;
        }
        if (m.startsWith("HTTP ")) {
            return HTTP;
        }
        if (m.equals("not a portal answer") || m.equals("no portal endpoint")) {
            return NOT_PORTAL;
        }
        if (m.equals("no channels")) {
            return NO_CHANNELS;
        }
        if (m.contains("timed out")) {
            return TIMEOUT;
        }
        return OTHER;
    }

    /** Stored form (see IptvPortalMemory); unknown text maps to {@link #OTHER}. */
    public static StalkerFailure of(String name) {
        try {
            return valueOf(name);
        } catch (RuntimeException e) {
            return OTHER;
        }
    }
}
