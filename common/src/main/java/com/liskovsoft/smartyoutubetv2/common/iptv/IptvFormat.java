package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.Locale;

/** Content sniffing: the real bytes win over the file extension. */
public enum IptvFormat {
    ZIP, GZIP, TAR, RAR, SEVENZ, BZIP2, XZ, ZSTD, UNIX_COMPRESS, CPIO, AR, ARJ, UNSUPPORTED_ARCHIVE, M3U, HLS_STREAM, PLS, XSPF, XMLTV, JSON, TEXT, BINARY;

    public static IptvFormat detect(byte[] data, String nameHint) {
        if (data == null || data.length == 0) {
            return TEXT;
        }
        int n = data.length;
        if (n >= 4 && data[0] == 'P' && data[1] == 'K' && (data[2] == 3 || data[2] == 5) && (data[3] == 4 || data[3] == 6)) {
            return ZIP;
        }
        if (n >= 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
            return GZIP;
        }
        if (n >= 6 && (data[0] & 0xFF) == 0x37 && data[1] == 0x7A && (data[2] & 0xFF) == 0xBC && (data[3] & 0xFF) == 0xAF) {
            return SEVENZ;
        }
        if (n >= 6 && (data[0] & 0xFF) == 0xFD && data[1] == 0x37 && data[2] == 0x7A && data[3] == 0x58 && data[4] == 0x5A && data[5] == 0) {
            return XZ;
        }
        if (n >= 4 && data[0] == 'R' && data[1] == 'a' && data[2] == 'r' && data[3] == '!') {
            return RAR;
        }
        if (n >= 10 && data[0] == 'B' && data[1] == 'Z' && data[2] == 'h' && data[3] >= '1' && data[3] <= '9'
                && data[4] == 0x31 && data[5] == 0x41 && data[6] == 0x59 && data[7] == 0x26) {
            return BZIP2;
        }
        if (n >= 4 && (data[0] & 0xFF) == 0x28 && (data[1] & 0xFF) == 0xB5 && data[2] == 0x2F && (data[3] & 0xFF) == 0xFD) {
            return ZSTD;
        }
        if (n >= 3 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x9D) {
            return UNIX_COMPRESS;
        }
        if (n >= 8 && new String(data, 0, 8, java.nio.charset.Charset.forName("ISO-8859-1")).equals("!<arch>\n")) {
            return AR;
        }
        if (n >= 6 && data[0] == '0' && data[1] == '7' && data[2] == '0' && data[3] == '7' && data[4] == '0'
                && (data[5] == '1' || data[5] == '2' || data[5] == '7')) {
            return CPIO;
        }
        if (n >= 4 && (data[0] & 0xFF) == 0x60 && (data[1] & 0xFF) == 0xEA && (data[2] != 0 || data[3] != 0)) {
            return ARJ;
        }
        if (n >= 4 && data[0] == 'M' && data[1] == 'S' && data[2] == 'C' && data[3] == 'F') {
            return UNSUPPORTED_ARCHIVE; // cab
        }
        if (n >= 262 && data[257] == 'u' && data[258] == 's' && data[259] == 't' && data[260] == 'a' && data[261] == 'r') {
            return TAR;
        }

        int headLen = Math.min(n, 8192);
        int control = 0;
        for (int i = 0; i < headLen; i++) {
            int b = data[i] & 0xFF;
            if (b == 0 && !(n >= 2 && ((data[0] & 0xFF) == 0xFF || (data[0] & 0xFF) == 0xFE))) {
                control++;
            }
        }
        if (control > 0) {
            return BINARY;
        }

        String head = TextDecoderUtil.decode(java.util.Arrays.copyOf(data, headLen));
        String trimmed = head.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("#extm3u") || lower.contains("#extinf")) {
            return m3uOrHls(head);
        }
        if (lower.startsWith("[playlist]")) {
            return PLS;
        }
        if (lower.startsWith("<?xml") || lower.startsWith("<")) {
            if (lower.contains("<tv") && !lower.contains("<playlist")) {
                return XMLTV;
            }
            if (lower.contains("<playlist") || lower.contains("<tracklist")) {
                return XSPF;
            }
            return TEXT;
        }
        if (lower.startsWith("{") || lower.startsWith("[")) {
            return JSON;
        }
        String ext = nameHint == null ? "" : IptvUrls.extension(nameHint);
        if (ext.equals("pls")) return PLS;
        return TEXT;
    }

    private static IptvFormat m3uOrHls(String head) {
        return M3uParser.isHlsStream(head) ? HLS_STREAM : M3U;
    }
}
