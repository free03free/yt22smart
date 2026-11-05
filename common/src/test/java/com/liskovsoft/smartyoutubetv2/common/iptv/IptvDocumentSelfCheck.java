package com.liskovsoft.smartyoutubetv2.common.iptv;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** JVM self check: the IPTV text analysis reaches into office files, PDF, RTF, HTML and binary files. */
public class IptvDocumentSelfCheck {
    static int passed, failed;
    static void check(boolean ok, String what) { if (ok) passed++; else { failed++; System.out.println("FAIL: " + what); } }

    static byte[] zip(String[][] files) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            for (String[] f : files) {
                z.putNextEntry(new ZipEntry(f[0]));
                z.write(f[1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return b.toByteArray();
    }

    static byte[] deflate(byte[] in) {
        Deflater d = new Deflater();
        d.setInput(in);
        d.finish();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (!d.finished()) b.write(buf, 0, d.deflate(buf));
        return b.toByteArray();
    }

    public static void main(String[] a) throws Exception {
        // docx: text lines + an external hyperlink in the relationships file
        byte[] docx = zip(new String[][]{
                {"[Content_Types].xml", "<Types/>"},
                {"word/document.xml", "<w:document><w:body><w:p><w:r><w:t>Host: http://srv.example:80</w:t></w:r></w:p>"
                        + "<w:p><w:r><w:t>Username: u1</w:t></w:r></w:p><w:p><w:r><w:t>Password: p1</w:t></w:r></w:p></w:body></w:document>"},
                {"word/_rels/document.xml.rels", "<Relationships><Relationship Id=\"r1\" Type=\"hyperlink\" Target=\"http://lists.example/ara.m3u\" TargetMode=\"External\"/></Relationships>"}});
        String t = IptvDocumentText.officeText(docx);
        check(t != null && t.contains("Host: http://srv.example:80") && t.contains("Password: p1"), "docx text by lines");
        check(t != null && t.contains("http://lists.example/ara.m3u"), "docx hyperlink target");
        check(XtreamAccount.parseAll(t).size() == 1, "docx holds an account");
        check(IptvDocumentText.officeText(zip(new String[][]{{"a.txt", "x"}, {"b.m3u", "#EXTM3U"}})) == null, "a normal zip is not a document");

        // xlsx shared strings
        byte[] xlsx = zip(new String[][]{{"xl/sharedStrings.xml", "<sst><si><t>BBC One</t></si><si><t>http://x.example/bbc.m3u8</t></si></sst>"}});
        String x = IptvDocumentText.officeText(xlsx);
        check(x != null && x.contains("BBC One\n") && x.contains("http://x.example/bbc.m3u8"), "xlsx cells on separate lines");

        // pdf: a compressed content stream with text and a link annotation
        String content = "BT /F1 12 Tf (Host: http://pdf.example:8080) Tj T* [(User)-20(name: pu)] TJ ET";
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        pdf.write("%PDF-1.4\n1 0 obj\n<< /Subtype /Link /A << /URI (http://pdf-links.example/list.m3u) >> >>\nendobj\n2 0 obj\n<< /Filter /FlateDecode >>\nstream\n".getBytes(StandardCharsets.ISO_8859_1));
        pdf.write(deflate(content.getBytes(StandardCharsets.ISO_8859_1)));
        pdf.write("\nendstream\nendobj\n%%EOF".getBytes(StandardCharsets.ISO_8859_1));
        String p = IptvDocumentText.pdfText(pdf.toByteArray());
        check(p.contains("http://pdf-links.example/list.m3u"), "pdf link annotation");
        check(p.contains("Host: http://pdf.example:8080") && p.contains("Username: pu"), "pdf compressed text: " + p.replace("\n", "|"));
        check(IptvFormat.detect(pdf.toByteArray(), "x.bin") == IptvFormat.PDF, "pdf detected by content");

        // rtf, html
        String rtf = "{\\rtf1\\ansi{\\fonttbl\\f0 Arial;}\\f0\\fs24 Channel A\\par http://r.example/a.m3u8\\par}";
        String r = IptvDocumentText.rtfToText(rtf);
        check(r.contains("Channel A\n") && r.contains("http://r.example/a.m3u8"), "rtf: " + r.replace("\n", "|"));
        check(IptvFormat.detect(rtf.getBytes(StandardCharsets.ISO_8859_1), "x.rtf") == IptvFormat.RTF, "rtf detected");
        String h = IptvDocumentText.markupToText("<html><body><h1>News</h1><p>Sport HD</p><a href=\"http://h.example/s.m3u8\">watch</a>&amp; more</body></html>");
        check(h.contains("Sport HD") && h.contains("http://h.example/s.m3u8") && !h.contains("<"), "html text and href: " + h.replace("\n", "|"));

        // binary file: the strings
        byte[] bin = new byte[300];
        byte[] link = "http://bin.example/live/1.m3u8".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(link, 0, bin, 40, link.length);
        byte[] w = "http://wide.example/2.m3u8".getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(w, 0, bin, 120, w.length);
        String s = IptvDocumentText.strings(bin);
        check(s.contains("http://bin.example/live/1.m3u8") && s.contains("http://wide.example/2.m3u8"), "binary strings (ascii + utf-16): " + s.replace("\n", "|"));
        check(IptvDocumentText.strings(new byte[200]).isEmpty(), "zeros have no strings");

        // end to end: the same links reach the channel list through the importer
        List<IptvChannel> ch = TextListParser.parse(IptvTextCleaner.clean(x + "\n" + h + "\n" + r));
        check(ch.size() >= 3, "channels from document text: " + ch.size());
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
