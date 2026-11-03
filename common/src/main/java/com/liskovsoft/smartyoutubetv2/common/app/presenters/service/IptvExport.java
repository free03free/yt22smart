package com.liskovsoft.smartyoutubetv2.common.app.presenters.service;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.liskovsoft.smartyoutubetv2.common.iptv.IptvPlaylist;
import com.liskovsoft.smartyoutubetv2.common.iptv.M3uWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;

/** Saves channels as an M3U file in the Downloads folder (Download/IPTV). */
public final class IptvExport {
    private IptvExport() {
    }

    /** @return where the file was saved (for the message) */
    public static String save(Context context, String name, IptvPlaylist playlist) throws IOException {
        String file = safeName(name) + ".m3u";
        byte[] data = M3uWriter.write(playlist).getBytes(Charset.forName("UTF-8"));

        if (Build.VERSION.SDK_INT >= 29) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, file);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-mpegurl");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/IPTV");
                Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                        if (out != null) {
                            out.write(data);
                            return "Download/IPTV/" + file;
                        }
                    }
                }
            } catch (Exception e) {
                // fall through to the app folder
            }
        } else {
            try {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "IPTV");
                if (dir.isDirectory() || dir.mkdirs()) {
                    File f = new File(dir, file);
                    try (OutputStream out = new FileOutputStream(f)) {
                        out.write(data);
                    }
                    return f.getAbsolutePath();
                }
            } catch (Exception e) {
                // fall through to the app folder
            }
        }

        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        File f = new File(dir, file);
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
        return f.getAbsolutePath();
    }

    private static String safeName(String name) {
        String s = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "iptv" : (s.length() > 60 ? s.substring(0, 60) : s);
    }
}
