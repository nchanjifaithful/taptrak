package com.taptrak;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Control screen: a setup checklist that ticks itself as each permission is
 * accepted, the target app, the screenshot limit, and the last run's result.
 */
public class MainActivity extends Activity implements Crawler.Listener {

    private static final int REQ_PROJECTION = 1001;
    private static final int REQ_PERMISSION = 1002;
    private static final int REQ_PICK_APP = 1003;

    private static final String PREFS = "taptrak";
    private static final String KEY_PACKAGE = "package";
    private static final String KEY_LABEL = "label";
    private static final String KEY_LIMIT = "shot_limit";
    private static final String KEY_ASKED_NOTIFICATIONS = "asked_notifications";

    private static final int LIMIT_DEFAULT = 1000;

    private SharedPreferences prefs;
    private View rowAccessibility, rowCapture, rowNotifications, rowStorage;
    private ImageView targetIcon;
    private TextView targetName, targetPackage, setupHint;
    private View resultCard;
    private TextView resultSummary, resultPath;
    private Button startButton;
    private EditText limitInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        rowAccessibility = findViewById(R.id.rowAccessibility);
        rowCapture = findViewById(R.id.rowCapture);
        rowNotifications = findViewById(R.id.rowNotifications);
        rowStorage = findViewById(R.id.rowStorage);
        targetIcon = findViewById(R.id.targetIcon);
        targetName = findViewById(R.id.targetName);
        targetPackage = findViewById(R.id.targetPackage);
        limitInput = findViewById(R.id.limitInput);
        setupHint = findViewById(R.id.setupHint);
        resultCard = findViewById(R.id.resultCard);
        resultSummary = findViewById(R.id.resultSummary);
        resultPath = findViewById(R.id.resultPath);
        startButton = findViewById(R.id.startButton);

        rowAccessibility.setOnClickListener(v -> {
            if (CrawlerAccessibilityService.instance == null) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        rowCapture.setOnClickListener(v -> {
            if (ScreenCaptureService.instance == null) requestProjection();
        });
        rowNotifications.setOnClickListener(v -> requestNotifications());
        rowStorage.setOnClickListener(v -> requestPermissions(
                new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERMISSION));
        findViewById(R.id.targetCard).setOnClickListener(v -> {
            if (!Crawler.isActive()) {
                startActivityForResult(new Intent(this, AppPickerActivity.class), REQ_PICK_APP);
            }
        });
        findViewById(R.id.resultOpen).setOnClickListener(v -> openLastShot());
        startButton.setOnClickListener(v -> onPrimaryAction());

        limitInput.setText(String.valueOf(prefs.getInt(KEY_LIMIT, LIMIT_DEFAULT)));

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) rowNotifications.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) rowStorage.setVisibility(View.GONE);
        bindRowText(rowAccessibility, R.string.setup_accessibility);
        bindRowText(rowCapture, R.string.setup_capture);
        bindRowText(rowNotifications, R.string.setup_notifications);
        bindRowText(rowStorage, R.string.setup_storage);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Crawler.addListener(this);
        onCrawlerChanged();
    }

    @Override
    protected void onStop() {
        Crawler.removeListener(this);
        super.onStop();
    }

    @Override
    public void onCrawlerChanged() {
        boolean accessibility = CrawlerAccessibilityService.instance != null;
        boolean capture = ScreenCaptureService.instance != null;
        boolean storage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                || hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE);

        bindRowState(rowAccessibility, accessibility, R.string.setup_accessibility_hint);
        bindRowState(rowCapture, capture, R.string.setup_capture_hint);
        bindRowState(rowStorage, storage, R.string.setup_storage_hint);
        bindRowState(rowNotifications,
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                        || hasPermission(Manifest.permission.POST_NOTIFICATIONS),
                R.string.setup_notifications_hint);

        String pkg = prefs.getString(KEY_PACKAGE, null);
        bindTarget(pkg);

        boolean active = Crawler.isActive();
        boolean ready = accessibility && capture && storage && pkg != null;
        startButton.setText(active ? R.string.action_stop : R.string.action_start);
        startButton.setBackgroundResource(active ? R.drawable.bg_button_danger : R.drawable.bg_button_primary);
        startButton.setEnabled(active || ready);
        limitInput.setEnabled(!active);

        if (active) {
            setupHint.setText(Crawler.statusText);
        } else if (!accessibility || !capture || !storage) {
            setupHint.setText(R.string.hint_finish_setup);
        } else if (pkg == null) {
            setupHint.setText(R.string.hint_choose_app);
        } else {
            setupHint.setText(getString(R.string.hint_saves_to,
                    ScreenshotStore.displayPath(prefs.getString(KEY_LABEL, pkg))));
        }

        String summary = Crawler.lastSummary;
        resultCard.setVisibility(summary != null && !active ? View.VISIBLE : View.GONE);
        if (summary != null) {
            resultSummary.setText(summary);
            resultPath.setText(ScreenshotStore.displayPath(Crawler.targetLabel));
            findViewById(R.id.resultOpen).setVisibility(Crawler.lastShotUri != null ? View.VISIBLE : View.GONE);
        }
    }

    private void onPrimaryAction() {
        CrawlerAccessibilityService service = CrawlerAccessibilityService.instance;
        if (service == null) return;
        if (Crawler.isActive()) {
            service.stopCrawl();
            return;
        }
        Crawler.targetPackage = prefs.getString(KEY_PACKAGE, "");
        Crawler.targetLabel = prefs.getString(KEY_LABEL, Crawler.targetPackage);
        Crawler.maxShots = readLimit();
        prefs.edit().putInt(KEY_LIMIT, Crawler.maxShots).apply();
        limitInput.setText(String.valueOf(Crawler.maxShots));
        service.startCrawl();
        moveTaskToBack(true);
    }

    private void bindTarget(String pkg) {
        if (pkg == null) {
            targetIcon.setImageResource(R.drawable.ic_launcher);
            targetName.setText(R.string.target_none);
            targetPackage.setText(R.string.target_none_hint);
            return;
        }
        targetName.setText(prefs.getString(KEY_LABEL, pkg));
        targetPackage.setText(pkg);
        try {
            Drawable icon = getPackageManager().getApplicationIcon(pkg);
            targetIcon.setImageDrawable(icon);
        } catch (PackageManager.NameNotFoundException e) {
            // The app was uninstalled since it was chosen.
            prefs.edit().remove(KEY_PACKAGE).remove(KEY_LABEL).apply();
            bindTarget(null);
        }
    }

    private void bindRowText(View row, int title) {
        ((TextView) row.findViewById(R.id.rowTitle)).setText(title);
    }

    private void bindRowState(View row, boolean done, int hint) {
        ((ImageView) row.findViewById(R.id.rowIcon)).setImageResource(
                done ? R.drawable.ic_check_circle : R.drawable.ic_circle_outline);
        ((TextView) row.findViewById(R.id.rowSubtitle)).setText(done ? R.string.setup_done : hint);
        row.findViewById(R.id.rowChevron).setVisibility(done ? View.GONE : View.VISIBLE);
    }

    private int readLimit() {
        try {
            return Math.max(1, Integer.parseInt(limitInput.getText().toString().trim()));
        } catch (NumberFormatException e) {
            return prefs.getInt(KEY_LIMIT, LIMIT_DEFAULT); // Empty or too long to be a real limit.
        }
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || hasPermission(Manifest.permission.POST_NOTIFICATIONS)) return;
        // Android stops showing the prompt after a refusal, so send later taps to Settings.
        if (prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        } else {
            prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMISSION);
        }
    }

    private void requestProjection() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        onCrawlerChanged();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                Intent svc = new Intent(this, ScreenCaptureService.class)
                        .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                        .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
            } else {
                Toast.makeText(this, R.string.capture_denied, Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_PICK_APP && resultCode == RESULT_OK && data != null) {
            prefs.edit()
                    .putString(KEY_PACKAGE, data.getStringExtra(AppPickerActivity.EXTRA_PACKAGE))
                    .putString(KEY_LABEL, data.getStringExtra(AppPickerActivity.EXTRA_LABEL))
                    .apply();
            onCrawlerChanged();
        }
    }

    private void openLastShot() {
        Uri uri = Crawler.lastShotUri;
        if (uri == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "image/png")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.no_viewer, Toast.LENGTH_LONG).show();
        }
    }
}
