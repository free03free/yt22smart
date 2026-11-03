package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;

/** Decodes playlist bytes: BOM aware, strict UTF-8 first, then Windows-1256 (Arabic) or ISO-8859-1. */
public final class TextDecoderUtil {
    private TextDecoderUtil() {
    }

    public static String decode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        if (data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return new String(data, 3, data.length - 3, Charset.forName("UTF-8"));
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return new String(data, 2, data.length - 2, Charset.forName("UTF-16LE"));
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return new String(data, 2, data.length - 2, Charset.forName("UTF-16BE"));
        }
        try {
            return Charset.forName("UTF-8").newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            // not UTF-8
        }
        try {
            String arabic = new String(data, Charset.forName("windows-1256"));
            int high = 0;
            int arabicLetters = 0;
            for (int i = 0; i < arabic.length(); i++) {
                char ch = arabic.charAt(i);
                if (ch > 127) {
                    high++;
                    if (ch >= 0x0600 && ch <= 0x06FF) {
                        arabicLetters++;
                    }
                }
            }
            if (high > 0 && arabicLetters * 10 >= high * 4) {
                return arabic;
            }
        } catch (RuntimeException e) {
            // charset unavailable
        }
        return new String(data, Charset.forName("ISO-8859-1"));
    }
}
