package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny lenient JSON reader (objects -> Map, arrays -> List, numbers -> Long/Double, strings, Boolean, null).
 * Pure Java so the IPTV engine stays testable on a plain JVM (org.json only exists on Android).
 * Skips any junk (BOM, HTML, warnings) before the first '{' or '['.
 */
final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s, int start) {
        this.s = s;
        this.i = start;
    }

    /** @return the parsed value or null when the text holds no JSON object / array */
    static Object parse(String text) {
        if (text == null) {
            return null;
        }
        int start = -1;
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            if (c == '{' || c == '[') {
                start = k;
                break;
            }
        }
        if (start < 0) {
            return null;
        }
        try {
            return new MiniJson(text, start).value(0);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    /** Scalar -> String ("12" for 12 and 12.0, "" for null / containers). */
    static String str(Object o) {
        if (o == null || o instanceof Map || o instanceof List) {
            return "";
        }
        if (o instanceof Double) {
            double d = (Double) o;
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
        }
        return String.valueOf(o);
    }

    static long num(Object o, long def) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return Long.parseLong(str(o).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private Object value(int depth) {
        if (depth > 64) {
            throw new IllegalStateException("too deep");
        }
        ws();
        char c = s.charAt(i);
        switch (c) {
            case '{': return object(depth);
            case '[': return array(depth);
            case '"': return string();
            case 't': i += 4; return Boolean.TRUE;
            case 'f': i += 5; return Boolean.FALSE;
            case 'n': i += 4; return null;
            default: return number();
        }
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        ws();
        if (s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            String key = string();
            ws();
            expect(':');
            m.put(key, value(depth + 1));
            ws();
            char c = s.charAt(i++);
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw new IllegalStateException("object");
            }
        }
    }

    private List<Object> array(int depth) {
        List<Object> l = new ArrayList<>();
        i++; // [
        ws();
        if (s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            l.add(value(depth + 1));
            ws();
            char c = s.charAt(i++);
            if (c == ']') {
                return l;
            }
            if (c != ',') {
                throw new IllegalStateException("array");
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u':
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: sb.append(e); // \" \\ \/
            }
        }
    }

    private Object number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (st == i) {
            throw new IllegalStateException("value");
        }
        String t = s.substring(st, i);
        if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException e) {
                // fall through to double
            }
        }
        return Double.parseDouble(t);
    }

    private void expect(char c) {
        if (s.charAt(i++) != c) {
            throw new IllegalStateException("expected " + c);
        }
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }
}
