package com.liskovsoft.smartyoutubetv2.common.iptv;

/**
 * Direction helper for titles shown in the UI (pure Java, no Android).
 *
 * Why: a title such as "[HD] مسلسل الحفرة (الجزء 2)" or "beIN Sports | العربية" mixes scripts, digits and neutral
 * characters (brackets, bars, dashes). If the paragraph direction is guessed from the first character only, or
 * forced to LTR, brackets flip, the Arabic words come out in the wrong order and the text hugs the wrong edge.
 * Here the direction is decided by the dominant script of the letters, and stray direction marks that came
 * with the title are removed so they cannot fight the decision.
 */
public final class ArabicText {
    public static final char LRM = '\u200E';
    public static final char RLM = '\u200F';

    private ArabicText() {
    }

    /** True when the Arabic/Hebrew letters are at least as many as the Latin ones (a tie goes to RTL). */
    public static boolean isRtl(CharSequence text) {
        if (text == null) {
            return false;
        }
        int rtl = 0;
        int ltr = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = Character.codePointAt(text, i);
            i += Character.charCount(cp);
            byte d = Character.getDirectionality(cp);
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                rtl++;
            } else if (d == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
                ltr++;
            }
        }
        return rtl > 0 && rtl >= ltr;
    }

    /** Removes embedded/isolate/mark direction controls and tatweel-free stray joiners left by copied text. */
    public static String clean(CharSequence text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\uFEFF' || (c >= '\u200B' && c <= '\u200F') || (c >= '\u202A' && c <= '\u202E') || (c >= '\u2066' && c <= '\u2069')) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** "name   (3/20)": the counter always reads left-to-right and sits after the name in either direction. */
    public static String withCounter(String name, int index, int total) {
        return clean(name) + "   " + LRM + "(" + index + "/" + total + ")" + LRM;
    }
}
