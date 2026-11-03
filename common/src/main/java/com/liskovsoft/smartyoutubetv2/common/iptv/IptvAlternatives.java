package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** The same channel offered by other lists (portals / servers): a backup when one source stops working. */
public final class IptvAlternatives {
    private static final int MAX = 30;

    private IptvAlternatives() {
    }

    /** "|AR| beIN Sports 1 HD" and "[US] BEIN SPORTS 1 FHD" give the same key. */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String s = IptvNames.stripPrefix(name).toLowerCase(Locale.ROOT);
        s = s.replaceAll("\\b(?:full\\s*hd|fhd|uhd|hd|sd|lq|2k|4k|8k|hevc|h265|h264|1080p?|720p?|576p?|(?:2160|1440|480|360|240|144)p|backup|raw)\\b", " ");
        s = s.replaceAll("[^\\p{L}\\p{N}]+", "");
        return s;
    }

    /** Channels with the same normalized name and type in other lists, other lists first. */
    public static List<IptvChannel> find(IptvPlaylist library, IptvChannel channel) {
        String key = normalize(channel.name);
        List<IptvChannel> out = new ArrayList<>();
        if (key.length() < 3) {
            return out;
        }
        String myList = channel.list == null ? "" : channel.list;
        List<IptvChannel> sameList = new ArrayList<>();
        for (IptvChannel c : library.getChannels()) {
            if (c.kind != channel.kind || c.key().equals(channel.key()) || !normalize(c.name).equals(key)) {
                continue;
            }
            if ((c.list == null ? "" : c.list).equals(myList)) {
                sameList.add(c);
            } else {
                out.add(c);
            }
            if (out.size() >= MAX) {
                break;
            }
        }
        out.addAll(sameList);
        return out.size() > MAX ? out.subList(0, MAX) : out;
    }

    // ------------------------------------------------------------------ lower quality copy (data saver)

    private static final Pattern H_8K = Pattern.compile("\\b(?:8k|4320p?)\\b");
    private static final Pattern H_4K = Pattern.compile("\\b(?:4k|uhd|2160p?)\\b");
    private static final Pattern H_1440 = Pattern.compile("\\b(?:2k|1440p?)\\b");
    private static final Pattern H_1080 = Pattern.compile("\\b(?:fhd|full\\s*hd|1080[pi]?)\\b");
    private static final Pattern H_720 = Pattern.compile("\\b(?:hd|720[pi]?)\\b");
    private static final Pattern H_480 = Pattern.compile("\\b(?:sd|576[pi]?|480[pi]?)\\b");
    private static final Pattern H_360 = Pattern.compile("\\b(?:lq|360p)\\b");
    private static final Pattern H_240 = Pattern.compile("\\b240p\\b");
    private static final Pattern H_144 = Pattern.compile("\\b144p\\b");

    /**
     * Video height a channel name announces: "... FHD" 1080, "... HD" 720, "... SD" 480, "... 360p" 360 and so on.
     * @return 0 when the name says nothing about the quality
     */
    public static int guessHeight(String name) {
        if (name == null) {
            return 0;
        }
        String s = name.toLowerCase(Locale.ROOT);
        if (H_8K.matcher(s).find()) return 4320;
        if (H_4K.matcher(s).find()) return 2160;
        if (H_1440.matcher(s).find()) return 1440;
        if (H_1080.matcher(s).find()) return 1080;
        if (H_720.matcher(s).find()) return 720;
        if (H_480.matcher(s).find()) return 480;
        if (H_360.matcher(s).find()) return 360;
        if (H_240.matcher(s).find()) return 240;
        if (H_144.matcher(s).find()) return 144;
        return 0;
    }

    /**
     * Library index for "play the lower quality copy of this channel". A stream with one quality can't be made lighter by
     * the player, but many lists carry the same channel several times ("beIN 1 FHD", "beIN 1 HD", "beIN 1 SD").
     * Only entries whose name announces a quality are indexed. Build it off the UI thread: it scans the whole library.
     */
    public static final class QualityIndex {
        private final Map<String, List<IptvChannel>> mByName = new HashMap<>();

        public QualityIndex(IptvPlaylist library) {
            for (IptvChannel c : new ArrayList<>(library.getChannels())) {
                if (guessHeight(c.name) <= 0) {
                    continue;
                }
                String key = indexKey(c);
                if (key == null) {
                    continue;
                }
                List<IptvChannel> list = mByName.get(key);
                if (list == null) {
                    list = new ArrayList<>(2);
                    mByName.put(key, list);
                }
                list.add(c);
            }
        }

        private static String indexKey(IptvChannel c) {
            String name = normalize(c.name);
            return name.length() < 3 ? null : c.kind + "#" + name;
        }

        /**
         * @param cap highest height the viewer wants (> 0)
         * @return the best other copy of the channel that is lighter, or null when there is none. A copy within the cap wins
         *         (the highest one), otherwise the lowest copy that is at least lighter than the channel itself. A channel
         *         that is already within the cap, or an unknown copy that would not be proven lighter, never swaps.
         */
        public IptvChannel lowerQuality(IptvChannel channel, int cap) {
            if (channel == null || cap <= 0) {
                return null;
            }
            String key = indexKey(channel);
            List<IptvChannel> candidates = key == null ? null : mByName.get(key);
            if (candidates == null) {
                return null;
            }

            int current = guessHeight(channel.name);
            if (current > 0 && current <= cap) {
                return null;
            }

            String myList = channel.list == null ? "" : channel.list;
            IptvChannel best = null;
            int bestHeight = 0;
            boolean bestSameList = false;
            for (IptvChannel c : candidates) {
                if (c.key().equals(channel.key())) {
                    continue;
                }
                int h = guessHeight(c.name);
                if (current > 0 ? h >= current : h > cap) {
                    continue; // not lighter than what is playing (or, for an unknown channel, not within the cap)
                }
                boolean sameList = (c.list == null ? "" : c.list).equals(myList);
                if (best == null || isBetter(h, sameList, bestHeight, bestSameList, cap)) {
                    best = c;
                    bestHeight = h;
                    bestSameList = sameList;
                }
            }
            return best;
        }

        private static boolean isBetter(int h, boolean sameList, int bestH, boolean bestSameList, int cap) {
            boolean within = h <= cap;
            boolean bestWithin = bestH <= cap;
            if (within != bestWithin) {
                return within;
            }
            if (h != bestH) {
                return within ? h > bestH : h < bestH;
            }
            return sameList && !bestSameList; // the same server first: its account is known to work
        }
    }
}
