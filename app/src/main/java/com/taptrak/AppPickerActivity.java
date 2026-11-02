package com.taptrak;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Searchable list of the apps installed on the phone. Icons load lazily, off the main thread. */
public class AppPickerActivity extends Activity {

    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_LABEL = "label";

    private static final class AppEntry {
        String label;
        String packageName;
        String searchKey;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Drawable> icons = new HashMap<>();
    private final Set<String> iconsLoading = new HashSet<>();
    private final List<AppEntry> allApps = new ArrayList<>();
    private final List<AppEntry> shownApps = new ArrayList<>();
    private AppAdapter adapter;
    private EditText search;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_picker);

        findViewById(R.id.pickerBack).setOnClickListener(v -> finish());
        search = findViewById(R.id.pickerSearch);
        ListView list = findViewById(R.id.pickerList);
        list.setEmptyView(findViewById(R.id.pickerEmpty));
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry app = shownApps.get(position);
            setResult(RESULT_OK, new Intent()
                    .putExtra(EXTRA_PACKAGE, app.packageName)
                    .putExtra(EXTRA_LABEL, app.label));
            finish();
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { applyFilter(); }
        });

        worker.execute(this::loadApps);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> seen = new HashSet<>();
        List<AppEntry> apps = new ArrayList<>();
        for (ResolveInfo info : pm.queryIntentActivities(launcher, 0)) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
            AppEntry app = new AppEntry();
            app.packageName = pkg;
            app.label = String.valueOf(info.loadLabel(pm));
            app.searchKey = (app.label + " " + pkg).toLowerCase(Locale.getDefault());
            apps.add(app);
        }
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        main.post(() -> {
            if (isDestroyed()) return;
            allApps.addAll(apps);
            findViewById(R.id.pickerProgress).setVisibility(View.GONE);
            applyFilter();
        });
    }

    private void applyFilter() {
        String query = search.getText().toString().trim().toLowerCase(Locale.getDefault());
        shownApps.clear();
        for (AppEntry app : allApps) {
            if (query.isEmpty() || app.searchKey.contains(query)) shownApps.add(app);
        }
        adapter.notifyDataSetChanged();
    }

    private void bindIcon(ImageView view, String pkg) {
        view.setTag(pkg);
        Drawable icon = icons.get(pkg);
        view.setImageDrawable(icon);
        if (icon != null || !iconsLoading.add(pkg)) return;
        worker.execute(() -> {
            Drawable loaded = null;
            try {
                loaded = getPackageManager().getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {}
            Drawable result = loaded;
            main.post(() -> {
                icons.put(pkg, result);
                iconsLoading.remove(pkg);
                adapter.notifyDataSetChanged();
            });
        });
    }

    private final class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return shownApps.size(); }
        @Override public Object getItem(int position) { return shownApps.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
            AppEntry app = shownApps.get(position);
            ((TextView) row.findViewById(R.id.appLabel)).setText(app.label);
            ((TextView) row.findViewById(R.id.appPackage)).setText(app.packageName);
            bindIcon(row.findViewById(R.id.appIcon), app.packageName);
            return row;
        }
    }
}
