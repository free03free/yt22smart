package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.io.IOException;
import java.util.Map;

/** Downloads a remote playlist. Abstracted so tests and the Android layer can plug their own HTTP stack. */
public interface IptvFetcher {
    byte[] fetch(String url) throws IOException;

    /** Same with extra request headers (Stalker portals need Cookie / Authorization). */
    default byte[] fetch(String url, Map<String, String> headers) throws IOException {
        return fetch(url);
    }
}
