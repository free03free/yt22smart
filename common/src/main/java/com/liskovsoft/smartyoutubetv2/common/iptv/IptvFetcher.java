package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.io.IOException;

/** Downloads a remote playlist. Abstracted so tests and the Android layer can plug their own HTTP stack. */
public interface IptvFetcher {
    byte[] fetch(String url) throws IOException;
}
