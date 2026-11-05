package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns "other formats" into plain text so that the one IPTV text analysis can look for portals, accounts, links and
 * lists in them: Office / OpenDocument files, PDF, RTF, HTML / XML pages and any binary file (printable strings).
 * Pure Java, no libraries; nothing here decides what is IPTV, the text analysis does.
 */
public final class IptvDocumentText {
    private static final int MAX_TEXT = 16 * 1024 * 1024;
    private static final Pattern TAG_BREAK = Pattern.compile(
            "(?i)</(?:w:p|text:p|text:h|a:p|p|div|li|tr|row|si|h[1-6]|pre|table)>|<br\\s*/?>|<w:br\\s*/?>|<text:line-break\\s*/?>|<w:tab\\s*/?>");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern REL_TARGET = Pattern.compile("(?i)Target=\"([^\"]+)\"[^>]*TargetMode=\"External\"|TargetMode=\"External\"[^>]*Target=\"([^\"]+)\"");

    private IptvDocumentText() {
    }

    /** HTML / XML to lines of text; entities are decoded. */
    public static String markupToText(String markup) {
        String s = TAG_BREAK.matcher(markup).replaceAll("\n");
        // links hide in attributes: keep href / src / url values of the tags that are going to be removed
        StringBuilder links = new StringBuilder();
        Matcher a = Pattern.compile("(?i)\\b(?:href|src|url|data-url|data-src)\\s*=\\s*[\"']([^\"']+)[\"']").matcher(s);
        while (a.find()) {
            String u = a.group(1);
            if (u.toLowerCase().startsWith("http") || u.toLowerCase().startsWith("rtmp") || u.toLowerCase().startsWith("udp")) {
                links.append('\n').append(unescape(u));
            }
        }
        s = TAG.matcher(s).replaceAll("");
        return unescape(s) + links;
    }

    private static String unescape(String s) {
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ");
        Matcher m = Pattern.compile("&#(x?)([0-9a-fA-F]{1,6});").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String repl = " ";
            try {
                int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                repl = new String(Character.toChars(cp));
            } catch (RuntimeException ignored) {
                // keep a space
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** docx / xlsx / pptx / odt / ods / odp: the text and the hyperlinks; null when this zip is not an office document. */
    public static String officeText(byte[] zip) {
        StringBuilder out = new StringBuilder();
        boolean office = false;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                String n = e.getName().toLowerCase();
                boolean text = n.equals("word/document.xml") || n.startsWith("word/footnotes") || n.startsWith("word/header") || n.startsWith("word/footer")
                        || n.equals("xl/sharedstrings.xml") || n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml")
                        || n.startsWith("ppt/slides/slide") && n.endsWith(".xml") || n.equals("content.xml");
                boolean rels = n.endsWith(".rels");
                if (!text && !rels) {
                    continue;
                }
                if (n.equals("word/document.xml") || n.equals("xl/sharedstrings.xml") || n.equals("content.xml") || n.startsWith("ppt/slides/slide")) {
                    office = true;
                }
                String xml = new String(readLimited(in), Charset.forName("UTF-8"));
                if (rels) {
                    Matcher m = REL_TARGET.matcher(xml);
                    while (m.find()) {
                        out.append(unescape(m.group(1) != null ? m.group(1) : m.group(2))).append('\n');
                    }
                } else {
                    out.append(markupToText(xml)).append('\n');
                }
                if (out.length() > MAX_TEXT) {
                    break;
                }
            }
        } catch (IOException | RuntimeException ex) {
            return office ? out.toString() : null;
        }
        return office ? out.toString() : null;
    }

    /** RTF without its control words. */
    public static String rtfToText(String rtf) {
        String s = rtf.replaceAll("\\\\'([0-9a-fA-F]{2})", " ");
        s = s.replaceAll("\\\\(?:par|line|tab)\\b ?", "\n");
        s = s.replaceAll("\\{\\\\\\*?\\\\fldinst\\s*\\{?", "\n"); // keep hyperlink field instructions: HYPERLINK "url"
        s = s.replaceAll("\\\\[a-zA-Z]+-?\\d* ?", "");
        return s.replaceAll("[{}]", "");
    }

    /**
     * PDF: the text of the (compressed) content streams and the link annotations. Best effort without a PDF library:
     * fonts with custom encodings come out scrambled, links and plain text usually come out fine.
     */
    public static String pdfText(byte[] pdf) {
        StringBuilder out = new StringBuilder();
        String raw = new String(pdf, Charset.forName("ISO-8859-1"));
        appendPdfPart(out, raw);
        int pos = 0;
        while (out.length() < MAX_TEXT) {
            int s = raw.indexOf("stream", pos);
            if (s < 0) {
                break;
            }
            int start = s + 6;
            if (start < raw.length() && raw.charAt(start) == '\r') start++;
            if (start < raw.length() && raw.charAt(start) == '\n') start++;
            int end = raw.indexOf("endstream", start);
            if (end < 0) {
                break;
            }
            byte[] inflated = inflate(pdf, start, end - start);
            if (inflated != null) {
                appendPdfPart(out, new String(inflated, Charset.forName("ISO-8859-1")));
            }
            pos = end + 9;
        }
        return out.toString();
    }

    private static void appendPdfPart(StringBuilder out, String part) {
        Matcher uri = Pattern.compile("/URI\\s*\\(([^)]{4,2000})\\)").matcher(part);
        while (uri.find()) {
            out.append(uri.group(1)).append('\n');
        }
        // text operators: (text) Tj / [(te)-20(xt)] TJ, a new line at every T* / Td / ET
        Matcher bt = Pattern.compile("BT(.*?)ET", Pattern.DOTALL).matcher(part);
        while (bt.find()) {
            Matcher op = Pattern.compile("\\[(.*?)\\]\\s*TJ|\\(((?:\\\\.|[^\\\\)])*)\\)\\s*(?:Tj|'|\")|(T\\*|Td|TD)").matcher(bt.group(1));
            while (op.find()) {
                if (op.group(3) != null) {
                    out.append('\n');
                } else if (op.group(1) != null) {
                    Matcher str = Pattern.compile("\\(((?:\\\\.|[^\\\\)])*)\\)").matcher(op.group(1));
                    while (str.find()) {
                        out.append(pdfString(str.group(1)));
                    }
                } else {
                    out.append(pdfString(op.group(2)));
                }
            }
            out.append('\n');
        }
    }

    private static String pdfString(String s) {
        return s.replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "");
    }

    private static byte[] inflate(byte[] data, int off, int len) {
        if (len <= 2) {
            return null;
        }
        Inflater inf = new Inflater();
        try {
            inf.setInput(data, off, len);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            while (!inf.finished() && bos.size() < MAX_TEXT) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) {
                    break;
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null; // not a flate stream (image, font...)
        } finally {
            inf.end();
        }
    }

    /** Any other file: its readable strings (ASCII / UTF-8 and UTF-16), one per line, like the unix "strings" tool. */
    public static String strings(byte[] data) {
        StringBuilder out = new StringBuilder();
        int n = Math.min(data.length, MAX_TEXT);
        StringBuilder run = new StringBuilder();
        for (int i = 0; i < n; i++) { // single byte runs
            int b = data[i] & 0xFF;
            if (b >= 32 && b < 127 || b == '\t') {
                run.append((char) b);
            } else {
                flush(out, run);
            }
        }
        flush(out, run);
        for (int pass = 0; pass < 2; pass++) { // UTF-16 little / big endian runs
            for (int i = 0; i + 1 < n; i += 1) {
                int lo = data[pass == 0 ? i : i + 1] & 0xFF;
                int hi = data[pass == 0 ? i + 1 : i] & 0xFF;
                if (hi == 0 && (lo >= 32 && lo < 127)) {
                    run.append((char) lo);
                    i++;
                } else {
                    flush(out, run);
                }
            }
            flush(out, run);
        }
        return out.toString();
    }

    private static void flush(StringBuilder out, StringBuilder run) {
        if (run.length() >= 8) {
            out.append(run).append('\n');
        }
        run.setLength(0);
    }

    private static byte[] readLimited(java.io.InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0 && bos.size() < MAX_TEXT) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }
}
