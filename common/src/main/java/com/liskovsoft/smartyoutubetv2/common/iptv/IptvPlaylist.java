package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Ordered, de-duplicated collection of channels. */
public class IptvPlaylist {
    private final List<IptvChannel> mChannels = new ArrayList<>();
    private final Set<String> mKeys = new HashSet<>();

    /** @return true if the channel was added (not a duplicate / not empty / limit not reached) */
    public boolean add(IptvChannel channel) {
        if (channel == null || channel.url == null || channel.url.trim().isEmpty()) {
            return false;
        }
        if (!mKeys.add(channel.key())) {
            return false;
        }
        mChannels.add(channel);
        return true;
    }

    public int addAll(Iterable<IptvChannel> channels) {
        int added = 0;
        for (IptvChannel c : channels) {
            if (add(c)) {
                added++;
            }
        }
        return added;
    }

    public List<IptvChannel> getChannels() {
        return mChannels;
    }

    public int size() {
        return mChannels.size();
    }

    public boolean isEmpty() {
        return mChannels.isEmpty();
    }

    /** Group name -> channels, in order of first appearance. Ungrouped entries go to the empty-name key. */
    public Map<String, List<IptvChannel>> groups() {
        Map<String, List<IptvChannel>> result = new LinkedHashMap<>();
        for (IptvChannel c : mChannels) {
            String g = c.group == null ? "" : c.group;
            List<IptvChannel> list = result.get(g);
            if (list == null) {
                list = new ArrayList<>();
                result.put(g, list);
            }
            list.add(c);
        }
        return result;
    }

    /** @return number of removed channels (0 or 1) */
    public int removeByUrl(String url) {
        if (url == null) {
            return 0;
        }
        String key = url.trim();
        for (java.util.Iterator<IptvChannel> it = mChannels.iterator(); it.hasNext(); ) {
            if (it.next().key().equals(key)) {
                it.remove();
                mKeys.remove(key);
                return 1;
            }
        }
        return 0;
    }

    /** Removes several channels at once (one save instead of one per channel). @return number of removed channels */
    public int removeByUrls(java.util.Collection<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return 0;
        }
        Set<String> keys = new HashSet<>();
        for (String url : urls) {
            if (url != null) {
                keys.add(url.trim());
            }
        }
        int removed = 0;
        for (java.util.Iterator<IptvChannel> it = mChannels.iterator(); it.hasNext(); ) {
            IptvChannel c = it.next();
            if (keys.contains(c.key())) {
                it.remove();
                mKeys.remove(c.key());
                removed++;
            }
        }
        return removed;
    }

    /**
     * Swaps the positions of two channels (manual reordering: "move up" / "move down" in the UI swap a channel with its
     * neighbour in the currently shown list). Every other channel keeps its place.
     * @return true if both channels were found
     */
    public boolean swap(String urlA, String urlB) {
        int a = indexOf(urlA);
        int b = indexOf(urlB);
        if (a < 0 || b < 0) {
            return false;
        }
        java.util.Collections.swap(mChannels, a, b);
        return true;
    }

    public static final int MOVE_TOP = 0, MOVE_BOTTOM = 1, MOVE_BEFORE = 2;

    /**
     * Moves a whole group of items at once. {@code all} is the stored order, {@code shown} the order of the open view,
     * {@code selected} what to move (it keeps its order from the view). TOP / BOTTOM put the block before the first /
     * after the last other item of the view, BEFORE puts it right before {@code anchor}.
     * @return the new stored order, or null when nothing would change
     */
    public static List<String> reorderKeys(List<String> all, List<String> shown, java.util.Set<String> selected, int mode, String anchor) {
        List<String> moving = new ArrayList<>();
        for (String k : shown) {
            if (selected.contains(k) && !moving.contains(k)) {
                moving.add(k);
            }
        }
        if (moving.isEmpty()) {
            return null;
        }
        List<String> rest = new ArrayList<>(all);
        rest.removeAll(moving);
        List<String> others = new ArrayList<>(shown);
        others.removeAll(moving);
        int at;
        if (mode == MOVE_BEFORE) {
            at = anchor == null ? -1 : rest.indexOf(anchor);
        } else if (others.isEmpty()) {
            return null;
        } else if (mode == MOVE_TOP) {
            at = rest.indexOf(others.get(0));
        } else {
            at = rest.indexOf(others.get(others.size() - 1)) + 1;
        }
        if (at < 0) {
            return null;
        }
        rest.addAll(at, moving);
        return rest.equals(all) ? null : rest;
    }

    /** Group move on the stored channels, see {@link #reorderKeys}. @return true if the order changed */
    public boolean moveBlock(List<String> shown, java.util.Set<String> selected, int mode, String anchor) {
        List<String> all = new ArrayList<>();
        java.util.Map<String, IptvChannel> byKey = new java.util.HashMap<>();
        for (IptvChannel c : mChannels) {
            all.add(c.key());
            byKey.put(c.key(), c);
        }
        List<String> order = reorderKeys(all, shown, selected, mode, anchor);
        if (order == null) {
            return false;
        }
        List<IptvChannel> sorted = new ArrayList<>();
        for (String k : order) {
            IptvChannel c = byKey.get(k);
            if (c != null) {
                sorted.add(c);
            }
        }
        mChannels.clear();
        mChannels.addAll(sorted);
        return true;
    }

    /**
     * Puts the given keys in the given order while every other key keeps its place: the keys of {@code newOrder}
     * reuse, in order, the slots they already occupy in {@code all}. Used by the numbered channel manager
     * (sort, move to a number, live move) so that moving a channel inside a group never disturbs other groups.
     * @return the new full order (a new list); keys of newOrder that are not in {@code all} are ignored
     */
    public static List<String> slotReorder(List<String> all, List<String> newOrder) {
        java.util.Map<String, Integer> pos = new java.util.HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            pos.put(all.get(i), i);
        }
        List<String> wanted = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Integer> slots = new ArrayList<>();
        for (String k : newOrder) {
            if (k != null && pos.containsKey(k) && seen.add(k)) {
                wanted.add(k);
                slots.add(pos.get(k));
            }
        }
        java.util.Collections.sort(slots);
        List<String> result = new ArrayList<>(all);
        for (int i = 0; i < wanted.size(); i++) {
            result.set(slots.get(i), wanted.get(i));
        }
        return result;
    }

    /** Re-orders the stored channels, see {@link #slotReorder}. @return true if the order changed */
    public boolean applyOrder(List<String> newOrder) {
        List<String> all = new ArrayList<>();
        java.util.Map<String, IptvChannel> byKey = new java.util.HashMap<>();
        for (IptvChannel c : mChannels) {
            all.add(c.key());
            byKey.put(c.key(), c);
        }
        List<String> order = slotReorder(all, newOrder);
        if (order.equals(all)) {
            return false;
        }
        List<IptvChannel> sorted = new ArrayList<>(order.size());
        for (String k : order) {
            sorted.add(byKey.get(k));
        }
        mChannels.clear();
        mChannels.addAll(sorted);
        return true;
    }

    /** Changes the display name of one channel (the url, which is its identity, stays). @return true if found */
    public boolean rename(String url, String newName) {
        int i = indexOf(url);
        if (i < 0 || newName == null || newName.trim().isEmpty()) {
            return false;
        }
        mChannels.get(i).name = newName.trim();
        return true;
    }

    private int indexOf(String url) {
        String key = url == null ? null : url.trim();
        if (key == null) {
            return -1;
        }
        for (int i = 0; i < mChannels.size(); i++) {
            if (mChannels.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /** List name -> channels, in order of first appearance. Channels without a list go to the empty-name key. */
    public Map<String, List<IptvChannel>> lists() {
        Map<String, List<IptvChannel>> out = new java.util.LinkedHashMap<>();
        for (IptvChannel c : mChannels) {
            String l = c.list == null ? "" : c.list;
            List<IptvChannel> bucket = out.get(l);
            if (bucket == null) {
                bucket = new java.util.ArrayList<>();
                out.put(l, bucket);
            }
            bucket.add(c);
        }
        return out;
    }

    /** Removes every channel of the list ("" = channels without a list). @return number of removed channels */
    public int removeList(String list) {
        String l = list == null ? "" : list;
        int removed = 0;
        for (java.util.Iterator<IptvChannel> it = mChannels.iterator(); it.hasNext(); ) {
            IptvChannel c = it.next();
            if ((c.list == null ? "" : c.list).equals(l)) {
                it.remove();
                mKeys.remove(c.key());
                removed++;
            }
        }
        return removed;
    }

    /** Removes the channels of one group that belong to one list. @return number of removed channels */
    public int removeGroupInList(String group, String list) {
        String g = group == null ? "" : group;
        String l = list == null ? "" : list;
        int removed = 0;
        for (java.util.Iterator<IptvChannel> it = mChannels.iterator(); it.hasNext(); ) {
            IptvChannel c = it.next();
            if ((c.group == null ? "" : c.group).equals(g) && (c.list == null ? "" : c.list).equals(l)) {
                it.remove();
                mKeys.remove(c.key());
                removed++;
            }
        }
        return removed;
    }

    /** Removes every channel of the group ("" = ungrouped). @return number of removed channels */
    public int removeGroup(String group) {
        String g = group == null ? "" : group;
        int removed = 0;
        for (java.util.Iterator<IptvChannel> it = mChannels.iterator(); it.hasNext(); ) {
            IptvChannel c = it.next();
            if ((c.group == null ? "" : c.group).equals(g)) {
                it.remove();
                mKeys.remove(c.key());
                removed++;
            }
        }
        return removed;
    }
}
