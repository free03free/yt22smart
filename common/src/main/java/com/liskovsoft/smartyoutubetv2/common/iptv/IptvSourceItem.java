package com.liskovsoft.smartyoutubetv2.common.iptv;

/**
 * One thing found inside the uploaded sources that the user may take or leave: a Stalker portal, an Xtream account,
 * a link to another list, or a local list / archive entry.
 */
public final class IptvSourceItem {
    public enum Kind { PORTAL, XTREAM, LINK, FILE }

    public final Kind kind;
    /** Stable id used by {@link IptvOptions#skipKeys}. */
    public final String key;
    /** Address / file name shown to the user. */
    public final String title;
    /** MAC addresses (portal) or channels (file); 0 = unknown. */
    public final int count;
    /** Account name (Xtream), host (link) or the archive it came from (file); may be empty. */
    public final String detail;

    /** The MAC addresses of a portal (empty for everything else). */
    public final java.util.List<String> macs;

    public IptvSourceItem(Kind kind, String key, String title, int count, String detail) {
        this(kind, key, title, count, detail, null);
    }

    public IptvSourceItem(Kind kind, String key, String title, int count, String detail, java.util.List<String> macs) {
        this.macs = macs == null ? java.util.Collections.<String>emptyList() : new java.util.ArrayList<>(macs);
        this.kind = kind;
        this.key = key;
        this.title = title == null ? "" : title;
        this.count = count;
        this.detail = detail == null ? "" : detail;
    }

    /** {@link StalkerAccount#key()} of a portal item. */
    public String portalKey() {
        return key.startsWith("portal:") ? key.substring("portal:".length()) : key;
    }

    /** Key of one MAC of a portal in {@link IptvOptions#skipKeys}. */
    public static String macKey(String portalKey, String mac) {
        return "mac:" + portalKey + "|" + mac;
    }
}
