package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.io.IOException;
import java.util.List;

/**
 * Plug-in for archive formats the pure Java core cannot read itself (7z, rar, bzip2, xz, zstd, cpio, ar, arj ...).
 * The Android build provides an implementation backed by commons-compress / junrar.
 */
public interface ExternalArchiveHandler {
    boolean supports(IptvFormat format);

    /**
     * @return extracted files. A single stream compressor (bzip2, xz ...) returns exactly one entry holding the
     * decompressed bytes (it may itself be a tar or a playlist, the core detects that).
     */
    List<IptvArchiveReader.Entry> extract(byte[] data, String name, IptvFormat format, IptvOptions options) throws IOException;
}
