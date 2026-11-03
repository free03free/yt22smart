package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.Locale;

/**
 * Name ordering that follows the Arabic dictionary rules (pure Java, covered by ArabicSortSelfCheck):
 * - diacritics (tashkeel), tatweel and the RTL marks are ignored;
 * - alef forms (أ إ آ ٱ) count as ا, ة as ه, ى as ي, ؤ as و, ئ as ي;
 * - the leading definite article "ال" is ignored (الجزيرة is filed under ج);
 * - digits first (Arabic-Indic digits count as 0-9), then Arabic names, then the other scripts (Latin ...).
 */
public final class ArabicSort {
    private ArabicSort() {
    }

    public static final Comparator<String> COMPARATOR = new Comparator<String>() {
        @Override
        public int compare(String a, String b) {
            String ka = key(a), kb = key(b);
            int ra = rank(ka), rb = rank(kb);
            if (ra != rb) {
                return ra - rb;
            }
            int c = ka.compareTo(kb);
            return c != 0 ? c : String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
        }
    };

    /** Sort key: normalized, lower case, without the leading article. */
    public static String key(String name) {
        if (name == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch >= '\u064B' && ch <= '\u065F' || ch == '\u0670' || ch == '\u0640' || ch == '\u200E' || ch == '\u200F'
                    || ch == '\u202A' || ch == '\u202B' || ch == '\u202C' || ch == '\u2066' || ch == '\u2067' || ch == '\u2069') {
                continue; // tashkeel, tatweel, direction marks
            }
            switch (ch) {
                case '\u0623': case '\u0625': case '\u0622': case '\u0671': ch = '\u0627'; break; // أ إ آ ٱ -> ا
                case '\u0629': ch = '\u0647'; break; // ة -> ه
                case '\u0649': case '\u0626': ch = '\u064A'; break; // ى ئ -> ي
                case '\u0624': ch = '\u0648'; break; // ؤ -> و
                default:
                    if (ch >= '\u0660' && ch <= '\u0669') ch = (char) ('0' + (ch - '\u0660')); // Arabic-Indic digits
                    else if (ch >= '\u06F0' && ch <= '\u06F9') ch = (char) ('0' + (ch - '\u06F0')); // Persian digits
            }
            sb.append(ch);
        }
        String s = Normalizer.normalize(sb.toString(), Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT);
        // drop leading symbols / spaces so that "| ... " or "★" do not decide the place
        int start = 0;
        while (start < s.length() && !Character.isLetterOrDigit(s.charAt(start))) {
            start++;
        }
        s = s.substring(start);
        // "ال" + at least two letters: the article is not part of the sort key
        if (s.length() > 3 && s.startsWith("\u0627\u0644") && isArabicLetter(s.charAt(2))) {
            s = s.substring(2).trim();
        }
        return s;
    }

    private static int rank(String key) {
        if (key.isEmpty()) {
            return 3;
        }
        char c = key.charAt(0);
        if (c >= '0' && c <= '9') {
            return 0;
        }
        return isArabicLetter(c) ? 1 : 2;
    }

    private static boolean isArabicLetter(char c) {
        return c >= '\u0621' && c <= '\u064A' || c >= '\u066E' && c <= '\u06D3';
    }
}
