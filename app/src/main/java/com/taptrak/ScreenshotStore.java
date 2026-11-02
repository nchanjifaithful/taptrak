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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Writes screenshots to {@code Pictures/TapTrak/<App>/<Folder>/<Subfolder>/}, where
 * they show up in the gallery and any file manager. Encoding happens on its own
 * thread so the crawl doesn't wait for it.
 */
final class ScreenshotStore {

    static final String ROOT = "TapTrak";
    /** Separates folder levels in the paths the engine passes in. */
    static final String SEPARATOR = "/";
    private static final int MAX_NAME = 40;
    private static final int JPEG_QUALITY = 95;
    /** Full-size frames waiting to be written, before the crawl has to wait its turn. */
    private static final int MAX_QUEUED = 4;

    private final Context context;
    private final String appFolder;
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final Semaphore queueSlots = new Semaphore(MAX_QUEUED);
    private final Map<String, Integer> perFolder = new HashMap<>();
    private final List<Uri> savedUris = new ArrayList<>();
    private final List<File> savedFiles = new ArrayList<>();

    ScreenshotStore(Context context, String appLabel) {
        this.context = context.getApplicationContext();
        this.appFolder = safeName(appLabel, "App");
    }

    static String displayPath(String appLabel) {
        return Environment.DIRECTORY_PICTURES + "/" + ROOT + "/" + safeName(appLabel, "App");
    }

    int folderCount() { return perFolder.size(); }

    /** Queues the bitmap for writing, and recycles it afterwards. */
    void save(Bitmap bitmap, String folderPath, String screenTitle) {
        save(bitmap, folderPath, screenTitle, null);
    }

    /**
     * Queues the screenshot for writing and, when {@code textContent} is given, writes a
     * matching {@code .txt} of everything on the screen next to it. The bitmap is recycled after.
     */
    void save(Bitmap bitmap, String folderPath, String screenTitle, String textContent) {
        String folder = safePath(folderPath);
        Integer last = perFolder.get(folder);
        int index = last == null ? 1 : last + 1;
        perFolder.put(folder, index);
        String base = String.format(Locale.US, "%03d_%s", index, safeName(screenTitle, "Screen"));
        String name = base + ".jpg";

        queueSlots.acquireUninterruptibly();
        writer.execute(() -> {
            try {
                boolean ok = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? saveToMediaStore(bitmap, folder, name)
                        : saveToFile(bitmap, folder, name);
                if (!ok) Crawler.status(context.getString(R.string.status_save_failed));
                if (textContent != null && !textContent.isEmpty()) saveText(folder, base + ".txt", textContent);
            } finally {
                bitmap.recycle();
                queueSlots.release();
            }
        });
    }

    /**
     * Writes the screen's text to the app's own folder
     * ({@code Android/data/.../files/TapTrak/<App>/<Folder>/}), which needs no permission on
     * any Android version. Named to match its screenshot.
     */
    private void saveText(String folder, String textName, String text) {
        try {
            File base = context.getExternalFilesDir(ROOT);
            if (base == null) return;
            File dir = new File(new File(base, appFolder), folder);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            try (FileOutputStream out = new FileOutputStream(new File(dir, textName))) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    /** Removes only what this run saved, so Restart starts clean without touching earlier runs. */
    void deleteRunShots() {
        perFolder.clear();
        Crawler.lastShotUri = null;
        writer.execute(() -> {
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
            Crawler.lastShotUri = null;
        });
    }

    /** Finishes what's queued, then releases the thread. */
    void close() { writer.shutdown(); }

    private boolean saveToMediaStore(Bitmap bitmap, String folder, String name) {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + ROOT + "/" + appFolder + "/" + folder);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return false;
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
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
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return false;
        } catch (IOException e) {
            return false;
        }
        savedFiles.add(file);
        MediaScannerConnection.scanFile(context, new String[]{file.getPath()}, new String[]{"image/jpeg"},
                (path, uri) -> { if (uri != null) Crawler.lastShotUri = uri; });
        return true;
    }

    private static String safePath(String path) {
        StringBuilder out = new StringBuilder();
        for (String part : path.split(SEPARATOR)) {
            if (out.length() > 0) out.append(SEPARATOR);
            out.append(safeName(part, "Screen"));
        }
        return out.toString();
    }

    private static String safeName(String raw, String fallback) {
        String s = raw == null ? "" : raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ").trim();
        while (s.startsWith(".")) s = s.substring(1).trim();
        if (s.length() > MAX_NAME) s = s.substring(0, MAX_NAME).trim();
        return s.isEmpty() ? fallback : s;
    }
}
