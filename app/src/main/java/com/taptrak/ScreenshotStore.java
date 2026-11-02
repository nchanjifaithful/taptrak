package com.taptrak;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes screenshots to {@code Pictures/TapTrak/<App>/<Feature>/}, where they
 * show up in the gallery and any file manager.
 */
final class ScreenshotStore {

    static final String ROOT = "TapTrak";
    private static final int MAX_NAME = 40;

    private final Context context;
    private final String appFolder;
    private final Map<String, Integer> perFeature = new HashMap<>();
    private final List<Uri> savedUris = new ArrayList<>();
    private final List<File> savedFiles = new ArrayList<>();

    ScreenshotStore(Context context, String appLabel) {
        this.context = context.getApplicationContext();
        this.appFolder = safeName(appLabel, "App");
    }

    static String displayPath(String appLabel) {
        return Environment.DIRECTORY_PICTURES + "/" + ROOT + "/" + safeName(appLabel, "App");
    }

    int featureCount() { return perFeature.size(); }

    int shotsIn(String feature) {
        Integer n = perFeature.get(safeName(feature, "General"));
        return n == null ? 0 : n;
    }

    boolean save(Bitmap bitmap, String feature, String screenTitle) {
        String folder = safeName(feature, "General");
        int index = shotsIn(feature) + 1;
        String name = String.format(Locale.US, "%02d_%s.png", index, safeName(screenTitle, "Screen"));
        boolean ok = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? saveToMediaStore(bitmap, folder, name)
                : saveToFile(bitmap, folder, name);
        if (ok) perFeature.put(folder, index);
        return ok;
    }

    /** Removes only what this run saved, so Restart starts clean without touching earlier runs. */
    void deleteRunShots() {
        ContentResolver resolver = context.getContentResolver();
        for (Uri uri : savedUris) {
            try { resolver.delete(uri, null, null); } catch (RuntimeException ignored) {}
        }
        for (File file : savedFiles) {
            if (file.delete()) {
                MediaScannerConnection.scanFile(context, new String[]{file.getPath()}, null, null);
            }
        }
        savedUris.clear();
        savedFiles.clear();
        perFeature.clear();
        Crawler.lastShotUri = null;
    }

    private boolean saveToMediaStore(Bitmap bitmap, String folder, String name) {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + ROOT + "/" + appFolder + "/" + folder);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return false;
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                resolver.delete(uri, null, null);
                return false;
            }
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            return false;
        }
        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        savedUris.add(uri);
        Crawler.lastShotUri = uri;
        return true;
    }

    private boolean saveToFile(Bitmap bitmap, String folder, String name) {
        File pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        File dir = new File(new File(new File(pictures, ROOT), appFolder), folder);
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        File file = new File(dir, name);
        try (FileOutputStream out = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return false;
        } catch (IOException e) {
            return false;
        }
        savedFiles.add(file);
        MediaScannerConnection.scanFile(context, new String[]{file.getPath()}, new String[]{"image/png"},
                (path, uri) -> { if (uri != null) Crawler.lastShotUri = uri; });
        return true;
    }

    private static String safeName(String raw, String fallback) {
        String s = raw == null ? "" : raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ").trim();
        while (s.startsWith(".")) s = s.substring(1).trim();
        if (s.length() > MAX_NAME) s = s.substring(0, MAX_NAME).trim();
        return s.isEmpty() ? fallback : s;
    }
}
