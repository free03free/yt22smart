package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the user chose to import out of an analyzed list. Pure Java.
 * Everything is selected at the start; unticking removes a language / type / group, the keyword narrows further.
 */
public final class IptvSelection {
    private final IptvPlaylist mAll;
    private final Set<String> mDroppedLanguages = new HashSet<>();
    private final Set<Integer> mDroppedKinds = new HashSet<>();
    private final Set<String> mDroppedGroups = new HashSet<>();
    private final Set<String> mDroppedChannels = new HashSet<>();
    private String mKeyword = "";

    public IptvSelection(IptvPlaylist all) {
        mAll = all;
    }

    public IptvPlaylist all() {
        return mAll;
    }

    public String keyword() {
        return mKeyword;
    }

    public void setKeyword(String keyword) {
        mKeyword = keyword == null ? "" : keyword.trim();
    }

    public boolean isLanguageOn(String language) {
        return !mDroppedLanguages.contains(language);
    }

    public void setLanguage(String language, boolean on) {
        toggle(mDroppedLanguages, language, on);
    }

    public boolean isKindOn(int kind) {
        return !mDroppedKinds.contains(kind);
    }

    public void setKind(int kind, boolean on) {
        toggle(mDroppedKinds, kind, on);
    }

    public boolean isGroupOn(String group) {
        return !mDroppedGroups.contains(group);
    }

    public void setGroup(String group, boolean on) {
        toggle(mDroppedGroups, group, on);
    }

    public void setAllGroups(boolean on) {
        mDroppedGroups.clear();
        if (!on) {
            mDroppedGroups.addAll(groupCounts().keySet());
        }
    }

    public void setAllLanguages(boolean on) {
        mDroppedLanguages.clear();
        if (!on) {
            mDroppedLanguages.addAll(languageCounts().keySet());
        }
    }

    private static <T> void toggle(Set<T> dropped, T value, boolean on) {
        if (on) {
            dropped.remove(value);
        } else {
            dropped.add(value);
        }
    }

    public boolean isChannelOn(IptvChannel c) {
        return !mDroppedChannels.contains(c.key());
    }

    public void setChannel(IptvChannel c, boolean on) {
        toggle(mDroppedChannels, c.key(), on);
    }

    /** Channels that pass the language / type / group / keyword filters (before single channels are unticked). */
    public java.util.List<IptvChannel> candidates() {
        java.util.List<IptvChannel> out = new java.util.ArrayList<>();
        for (IptvChannel c : mAll.getChannels()) {
            if (matchesFilters(c)) {
                out.add(c);
            }
        }
        return out;
    }

    public boolean accepts(IptvChannel c) {
        return matchesFilters(c) && !mDroppedChannels.contains(c.key());
    }

    private boolean matchesFilters(IptvChannel c) {
        if (mDroppedKinds.contains(c.kind)) {
            return false;
        }
        if (mDroppedLanguages.contains(IptvLanguage.of(c))) {
            return false;
        }
        if (mDroppedGroups.contains(c.group == null ? "" : c.group)) {
            return false;
        }
        if (!mKeyword.isEmpty()) {
            String k = mKeyword.toLowerCase(Locale.ROOT);
            return has(c.name, k) || has(c.group, k);
        }
        return true;
    }

    private static boolean has(String text, String lowerKeyword) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(lowerKeyword);
    }

    /** The channels that pass every filter, in the original order. */
    public IptvPlaylist result() {
        IptvPlaylist out = new IptvPlaylist();
        for (IptvChannel c : mAll.getChannels()) {
            if (accepts(c)) {
                out.add(c);
            }
        }
        return out;
    }

    public int selectedCount() {
        int n = 0;
        for (IptvChannel c : mAll.getChannels()) {
            if (accepts(c)) {
                n++;
            }
        }
        return n;
    }

    /** Language -> channel count, biggest first ({@link IptvLanguage#UNKNOWN} included). */
    public Map<String, Integer> languageCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (IptvChannel c : mAll.getChannels()) {
            String l = IptvLanguage.of(c);
            Integer n = m.get(l);
            m.put(l, n == null ? 1 : n + 1);
        }
        return sortedByCount(m);
    }

    /** Group -> channel count in order of appearance ("" = ungrouped). */
    public Map<String, Integer> groupCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Map.Entry<String, java.util.List<IptvChannel>> e : mAll.groups().entrySet()) {
            m.put(e.getKey(), e.getValue().size());
        }
        return m;
    }

    public int kindCount(int kind) {
        int n = 0;
        for (IptvChannel c : mAll.getChannels()) {
            if (c.kind == kind) {
                n++;
            }
        }
        return n;
    }

    private static Map<String, Integer> sortedByCount(Map<String, Integer> in) {
        java.util.List<Map.Entry<String, Integer>> list = new java.util.ArrayList<>(in.entrySet());
        java.util.Collections.sort(list, (a, b) -> b.getValue() - a.getValue());
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : list) {
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }
}
