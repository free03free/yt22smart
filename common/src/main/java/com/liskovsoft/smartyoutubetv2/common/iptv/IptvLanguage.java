package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort language of a channel, used only to filter a list before importing it.
 * Order: the tvg-language attribute, then a country / language prefix of the group or the name
 * ("AR| beIN", "[US] CNN", "FR - TF1"). Returns {@link #UNKNOWN} when nothing matches.
 */
public final class IptvLanguage {
    public static final String UNKNOWN = "";

    private static final Pattern PREFIX = Pattern.compile("^\\s*[\\[(|]?\\s*([A-Za-z]{2,3})\\s*[\\])|:\\-\\u2022]");
    private static final Map<String, String> CODES = new HashMap<>();

    static {
        put("Arabic", "ar", "ara", "arabic", "sa", "ksa", "ae", "uae", "eg", "iq", "ma", "dz", "tn", "ly", "jo", "lb", "sy", "qa", "kw", "om", "bh", "ye", "sd", "ps");
        put("English", "en", "eng", "english", "us", "usa", "uk", "gb", "au", "ca", "ie", "nz");
        put("French", "fr", "fra", "fre", "french", "be", "ch");
        put("Spanish", "es", "spa", "spanish", "mx", "ar-es", "co", "cl", "pe", "ve");
        put("German", "de", "ger", "deu", "german", "at");
        put("Italian", "it", "ita", "italian");
        put("Portuguese", "pt", "por", "portuguese", "br");
        put("Turkish", "tr", "tur", "turkish");
        put("Russian", "ru", "rus", "russian");
        put("Persian", "ir", "fa", "fas", "per", "persian");
        put("Kurdish", "ku", "kur", "kurdish");
        put("Hindi", "hi", "hin", "hindi", "in");
        put("Urdu", "ur", "urd", "urdu", "pk");
        put("Dutch", "nl", "nld", "dut", "dutch");
        put("Polish", "pl", "pol", "polish");
        put("Greek", "gr", "el", "ell", "greek");
        put("Albanian", "al", "sq", "alb", "albanian");
        put("Romanian", "ro", "ron", "rum", "romanian");
    }

    private static void put(String language, String... codes) {
        for (String c : codes) {
            if (!CODES.containsKey(c)) {
                CODES.put(c, language);
            }
        }
    }

    private IptvLanguage() {
    }

    public static String of(IptvChannel c) {
        String fromAttr = fromWord(c.language);
        if (fromAttr != null) {
            return fromAttr;
        }
        String fromGroup = fromPrefix(c.group);
        if (fromGroup != null) {
            return fromGroup;
        }
        String fromName = fromPrefix(c.name);
        return fromName != null ? fromName : UNKNOWN;
    }

    private static String fromWord(String word) {
        if (word == null || word.trim().isEmpty()) {
            return null;
        }
        String w = word.trim().toLowerCase(Locale.ROOT);
        String direct = CODES.get(w);
        if (direct != null) {
            return direct;
        }
        // a free text value we do not know ("Kabyle"): keep it as its own language
        return Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    private static String fromPrefix(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = PREFIX.matcher(text);
        if (m.find()) {
            return CODES.get(m.group(1).toLowerCase(Locale.ROOT));
        }
        return null;
    }
}
