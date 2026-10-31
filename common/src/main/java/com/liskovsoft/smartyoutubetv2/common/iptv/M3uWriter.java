package com.liskovsoft.smartyoutubetv2.common.iptv;

/** Serialises a playlist back to M3U (used to persist the imported library). */
public final class M3uWriter {
    private M3uWriter() {
    }

    public static String write(IptvPlaylist playlist) {
        StringBuilder sb = new StringBuilder(playlist.size() * 120 + 16);
        sb.append("#EXTM3U\n");
        for (IptvChannel c : playlist.getChannels()) {
            sb.append("#EXTINF:-1");
            attr(sb, "tvg-id", c.tvgId);
            attr(sb, "tvg-name", c.tvgName);
            attr(sb, "tvg-logo", c.logo);
            attr(sb, "group-title", c.group);
            sb.append(',').append(clean(c.name)).append('\n');
            if (c.userAgent != null && !c.userAgent.isEmpty()) {
                sb.append("#EXTVLCOPT:http-user-agent=").append(clean(c.userAgent)).append('\n');
            }
            if (c.referrer != null && !c.referrer.isEmpty()) {
                sb.append("#EXTVLCOPT:http-referrer=").append(clean(c.referrer)).append('\n');
            }
            sb.append(clean(c.url)).append('\n');
        }
        return sb.toString();
    }

    private static void attr(StringBuilder sb, String key, String value) {
        if (value != null && !value.isEmpty()) {
            sb.append(' ').append(key).append("=\"").append(clean(value).replace('"', '\'')).append('"');
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
