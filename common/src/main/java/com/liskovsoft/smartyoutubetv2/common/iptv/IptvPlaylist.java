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
