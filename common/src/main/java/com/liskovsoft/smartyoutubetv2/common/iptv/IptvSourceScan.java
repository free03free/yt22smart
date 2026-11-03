package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.ArrayList;
import java.util.List;

/**
 * "Look first": opens every source (text, file, archive, nested archives ...) WITHOUT any network access and lists
 * what it holds, so the user can pick what is really downloaded. Runs on the caller's thread (do not call on the UI thread).
 */
public final class IptvSourceScan {
    private IptvSourceScan() {
    }

    public static List<IptvSourceItem> scan(List<IptvImportJob.Source> sources, IptvOptions options) {
        List<IptvSourceItem> all = new ArrayList<>();
        for (IptvImportJob.Source s : sources) {
            List<IptvSourceItem> found;
            try {
                IptvImporter importer = new IptvImporter(null, options, new CommonsArchiveHandler());
                found = s.file != null ? importer.discoverFile(s.file) : importer.discoverText(s.label, s.text);
            } catch (Throwable t) { // unreadable here: the normal import reports the real problem
                continue;
            }
            for (IptvSourceItem i : found) {
                boolean dup = false;
                for (IptvSourceItem e : all) {
                    if (e.key.equals(i.key)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    all.add(i);
                }
            }
        }
        return all;
    }
}
