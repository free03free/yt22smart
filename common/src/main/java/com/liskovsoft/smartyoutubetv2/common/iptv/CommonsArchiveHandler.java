package com.liskovsoft.smartyoutubetv2.common.iptv;

import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 7z, rar, bzip2, xz, compress(.Z), cpio, ar and arj support.
 * 7z/bzip2/xz/cpio/ar/arj/.Z: Apache commons-compress (+ org.tukaani:xz). rar: junrar, called through reflection so that a
 * missing/changed library can never break the build, only the rar import.
 * Notes: junrar reads RAR 2.x-4.x (RAR5 is reported as unsupported); 7z needs Android 7.0+ (SeekableByteChannel).
 */
public class CommonsArchiveHandler implements ExternalArchiveHandler {
    @Override
    public boolean supports(IptvFormat format) {
        switch (format) {
            case SEVENZ: case BZIP2: case XZ: case UNIX_COMPRESS: case CPIO: case AR: case ARJ:
                return true;
            case RAR:
                return classExists("com.github.junrar.Archive");
            default:
                return false; // zstd needs a native library, not bundled
        }
    }

    @Override
    public List<IptvArchiveReader.Entry> extract(byte[] data, String name, IptvFormat format, IptvOptions options) throws IOException {
        switch (format) {
            case SEVENZ:
                return extractSevenZ(data, options);
            case RAR:
                return extractRar(data, options);
            case BZIP2:
                return single(data, name, CompressorStreamFactory.BZIP2, options);
            case XZ:
                return single(data, name, CompressorStreamFactory.XZ, options);
            case UNIX_COMPRESS:
                return single(data, name, CompressorStreamFactory.Z, options);
            case CPIO:
                return streamArchive(data, ArchiveStreamFactory.CPIO, options);
            case AR:
                return streamArchive(data, ArchiveStreamFactory.AR, options);
            case ARJ:
                return streamArchive(data, ArchiveStreamFactory.ARJ, options);
            default:
                throw new IOException("Unsupported: " + format);
        }
    }

    // ---------------------------------------------------------------- 7z

    private static List<String> passwordCandidates(IptvOptions o) {
        List<String> list = new ArrayList<>();
        list.add(null); // first try without a password
        list.addAll(o.archivePasswords);
        return list;
    }

    private List<IptvArchiveReader.Entry> extractSevenZ(byte[] data, IptvOptions o) throws IOException {
        IOException last = null;
        for (String pw : passwordCandidates(o)) {
            List<IptvArchiveReader.Entry> out = new ArrayList<>();
            SevenZFile sz = null;
            try {
                SeekableInMemoryByteChannel ch = new SeekableInMemoryByteChannel(data);
                sz = pw == null ? new SevenZFile(ch) : new SevenZFile(ch, pw.toCharArray());
                SevenZArchiveEntry e;
                long total = 0;
                while ((e = sz.getNextEntry()) != null) {
                    if (e.isDirectory()) {
                        continue;
                    }
                    if (!IptvOptions.within(o.maxEntryBytes, e.getSize())) {
                        continue;
                    }
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = sz.read(buf)) != -1) {
                        bo.write(buf, 0, n);
                    }
                    total += bo.size();
                    if (!IptvOptions.within(o.maxArchiveTotalBytes, total)) {
                        break;
                    }
                    out.add(new IptvArchiveReader.Entry(e.getName(), bo.toByteArray()));
                }
                return out;
            } catch (IOException e) {
                last = e;
            } catch (OutOfMemoryError e) {
                throw new IOException("not enough memory");
            } finally {
                if (sz != null) {
                    try { sz.close(); } catch (IOException ignored) { }
                }
            }
        }
        throw last != null ? last : new IOException("empty 7z archive");
    }

    // ---------------------------------------------------------------- bzip2 / xz / Z (single stream)

    private List<IptvArchiveReader.Entry> single(byte[] data, String name, String compressor, IptvOptions o) throws IOException {
        try (InputStream in = new CompressorStreamFactory().createCompressorInputStream(compressor, new ByteArrayInputStream(data))) {
            byte[] bytes = readAll(in, o.maxEntryBytes);
            List<IptvArchiveReader.Entry> out = new ArrayList<>();
            out.add(new IptvArchiveReader.Entry(stripCompressionExt(name), bytes));
            return out;
        } catch (org.apache.commons.compress.compressors.CompressorException e) {
            throw new IOException(e.getMessage());
        }
    }

    private static String stripCompressionExt(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String e : new String[]{".tbz2", ".tbz", ".txz"}) {
            if (lower.endsWith(e)) return name.substring(0, name.length() - e.length()) + ".tar";
        }
        for (String e : new String[]{".bz2", ".xz", ".z"}) {
            if (lower.endsWith(e)) return name.substring(0, name.length() - e.length());
        }
        return name;
    }

    // ---------------------------------------------------------------- cpio / ar / arj

    private List<IptvArchiveReader.Entry> streamArchive(byte[] data, String type, IptvOptions o) throws IOException {
        List<IptvArchiveReader.Entry> out = new ArrayList<>();
        try (ArchiveInputStream ais = new ArchiveStreamFactory().createArchiveInputStream(type, new ByteArrayInputStream(data))) {
            ArchiveEntry e;
            long total = 0;
            while ((e = ais.getNextEntry()) != null) {
                if (e.isDirectory() || !ais.canReadEntryData(e) || !IptvOptions.within(o.maxEntryBytes, e.getSize())) {
                    continue;
                }
                byte[] bytes = readAll(ais, o.maxEntryBytes);
                total += bytes.length;
                if (!IptvOptions.within(o.maxArchiveTotalBytes, total)) {
                    break;
                }
                out.add(new IptvArchiveReader.Entry(e.getName(), bytes));
            }
        } catch (org.apache.commons.compress.archivers.ArchiveException e) {
            throw new IOException(e.getMessage());
        }
        return out;
    }

    // ---------------------------------------------------------------- rar (junrar through reflection)

    private List<IptvArchiveReader.Entry> extractRar(byte[] data, IptvOptions o) throws IOException {
        IOException last = null;
        for (String pw : passwordCandidates(o)) {
            try {
                return readRar(data, pw, o);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("empty rar archive");
    }

    private List<IptvArchiveReader.Entry> readRar(byte[] data, String password, IptvOptions o) throws IOException {
        Object archive = null;
        try {
            Class<?> archiveCls = Class.forName("com.github.junrar.Archive");
            Class<?> headerCls = Class.forName("com.github.junrar.rarfile.FileHeader");
            InputStream in = new ByteArrayInputStream(data);
            archive = password == null
                    ? archiveCls.getConstructor(InputStream.class).newInstance(in)
                    : archiveCls.getConstructor(InputStream.class, String.class).newInstance(in, password);

            Method next = archiveCls.getMethod("nextFileHeader");
            Method extract = archiveCls.getMethod("extractFile", headerCls, OutputStream.class);
            Method isDir = headerCls.getMethod("isDirectory");
            Method isEnc = headerCls.getMethod("isEncrypted");
            Method fileName;
            try {
                fileName = headerCls.getMethod("getFileName");
            } catch (NoSuchMethodException e) {
                fileName = headerCls.getMethod("getFileNameString");
            }

            List<IptvArchiveReader.Entry> out = new ArrayList<>();
            long total = 0;
            Object header;
            while ((header = next.invoke(archive)) != null) {
                if ((Boolean) isDir.invoke(header)) {
                    continue;
                }
                if ((Boolean) isEnc.invoke(header) && password == null) {
                    throw new IOException("password required");
                }
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                extract.invoke(archive, header, bo);
                if (!IptvOptions.within(o.maxEntryBytes, bo.size())) {
                    continue;
                }
                total += bo.size();
                if (!IptvOptions.within(o.maxArchiveTotalBytes, total)) {
                    break;
                }
                out.add(new IptvArchiveReader.Entry(String.valueOf(fileName.invoke(header)), bo.toByteArray()));
            }
            return out;
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            String msg = c.getClass().getSimpleName().contains("V5") ? "RAR5 is not supported (use RAR4, zip or 7z)" : String.valueOf(c.getMessage());
            throw new IOException(msg);
        } catch (ReflectiveOperationException e) {
            throw new IOException("rar library is not available: " + e.getMessage());
        } finally {
            if (archive != null) {
                try {
                    archive.getClass().getMethod("close").invoke(archive);
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }
    }

    // ---------------------------------------------------------------- utils

    private static byte[] readAll(InputStream in, long maxBytes) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (!IptvOptions.within(maxBytes, total)) {
                    throw new IOException("file is larger than " + (maxBytes / 1024 / 1024) + " MB");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (OutOfMemoryError e) {
            throw new IOException("not enough memory");
        }
    }

    private static boolean classExists(String cls) {
        try {
            Class.forName(cls);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
