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
    private final Set<String> mDroppedLists = new HashSet<>();
    private final Map<String, String> mListStatus = new LinkedHashMap<>();
    private java.util.List<String> mBlacklist = new java.util.ArrayList<>();

    public IptvSelection(IptvPlaylist all) {
        mAll = all;
    }

    /** The playlist without the channels / groups that contain one of the words. */
    public static IptvPlaylist withoutBlacklisted(IptvPlaylist playlist, String words) {
        IptvSelection s = new IptvSelection(playlist);
        s.setBlacklist(words);
        return s.result();
    }

    public IptvPlaylist all() {
        return mAll;
    }

    /** Comma / line separated words; a channel whose name or group contains one of them is left out. */
    public void setBlacklist(String words) {
        mBlacklist = new java.util.ArrayList<>();
        if (words != null) {
            for (String w : words.split("[,\n;]")) {
                String t = w.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty()) {
                    mBlacklist.add(t);
                }
            }
        }
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

    public boolean isListOn(String list) {
        return !mDroppedLists.contains(list);
    }

    public void setList(String list, boolean on) {
        toggle(mDroppedLists, list, on);
    }

    /** List name -> channel count in order of appearance ("" = no list). */
    public Map<String, Integer> listCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Map.Entry<String, java.util.List<IptvChannel>> e : mAll.lists().entrySet()) {
            m.put(e.getKey(), e.getValue().size());
        }
        return m;
    }

    /** Result of the availability check shown next to the list ("" = not checked). */
    public String listStatus(String list) {
        String s = mListStatus.get(list);
        return s == null ? "" : s;
    }

    public void setListStatus(String list, String status) {
        mListStatus.put(list, status);
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
        if (mDroppedKinds.contains(c.kind) || mDroppedLists.contains(c.list == null ? "" : c.list)) {
            return false;
        }
        if (mDroppedLanguages.contains(IptvLanguage.of(c))) {
            return false;
        }
        if (mDroppedGroups.contains(c.group == null ? "" : c.group)) {
            return false;
        }
        for (String w : mBlacklist) {
            if (has(c.name, w) || has(c.group, w)) {
                return false;
            }
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
