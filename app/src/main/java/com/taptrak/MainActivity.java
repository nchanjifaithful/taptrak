package com.taptrak;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Control screen. Walks the user through the three grants the tool needs
 * (accessibility service, screen-capture consent, notifications) and starts
 * or stops a crawl of the named target package.
 */
public class MainActivity extends Activity {

    private static final int REQ_PROJECTION = 1001;
    private static final int REQ_NOTIFICATIONS = 1002;

    private EditText targetPackage;
    private EditText maxShots;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        targetPackage = findViewById(R.id.targetPackage);
        maxShots = findViewById(R.id.maxShots);
        status = findViewById(R.id.status);

        Button btnAccessibility = findViewById(R.id.btnAccessibility);
        Button btnProjection = findViewById(R.id.btnProjection);
        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop = findViewById(R.id.btnStop);

        btnAccessibility.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        btnProjection.setOnClickListener(v -> requestProjection());

        btnStart.setOnClickListener(v -> startCrawl());
        btnStop.setOnClickListener(v -> CrawlerAccessibilityService.stopCrawl());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Crawler.setListener(text -> runOnUiThread(() -> status.setText(text)));
    }

    @Override
    protected void onPause() {
        Crawler.setListener(null);
        super.onPause();
    }

    private void requestProjection() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Intent svc = new Intent(this, ScreenCaptureService.class);
                svc.putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode);
                svc.putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
            } else {
                toast("Screen capture permission denied.");
            }
        }
    }

    private void startCrawl() {
        String pkg = targetPackage.getText().toString().trim();
        if (TextUtils.isEmpty(pkg)) { toast("Enter the target package name."); return; }
        if (!isAccessibilityEnabled()) {
            toast("Enable the TapTrak accessibility service first (step 1).");
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (ScreenCaptureService.instance == null) {
            toast("Grant screen capture first (step 2).");
            return;
        }
        if (CrawlerAccessibilityService.instance == null) {
            toast("Accessibility service not connected yet — try again in a moment.");
            return;
        }

        int max = 200;
        try { max = Integer.parseInt(maxShots.getText().toString().trim()); } catch (Exception ignored) {}

        Crawler.targetPackage = pkg;
        Crawler.maxShots = Math.max(1, max);

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File base = getExternalFilesDir(null);
        Crawler.outputDir = new File(new File(base, "crawls"), pkg + "_" + stamp);
        Crawler.outputDir.mkdirs();

        toast("Starting — screenshots will save to:\n" + Crawler.outputDir.getAbsolutePath());
        // Bring the target app to the foreground; the engine drives from there.
        CrawlerAccessibilityService.startCrawl();
        moveTaskToBack(true);
    }

    private boolean isAccessibilityEnabled() {
        String flat = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (flat == null) return false;
        String me = getPackageName() + "/" + CrawlerAccessibilityService.class.getName();
        return flat.contains(me) || flat.contains(getPackageName() + "/.CrawlerAccessibilityService");
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
