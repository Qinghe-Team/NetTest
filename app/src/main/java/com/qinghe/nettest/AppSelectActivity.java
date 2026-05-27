package com.qinghe.nettest;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AppSelectActivity extends AppCompatActivity {
    private AppListAdapter adapter;
    private List<ApplicationInfo> allApps = new ArrayList<>();
    private List<ApplicationInfo> filteredApps = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_select);

        RecyclerView rvApps = findViewById(R.id.rv_apps);
        SearchView searchView = findViewById(R.id.search_view);

        rvApps.setLayoutManager(new LinearLayoutManager(this));
        adapter = new AppListAdapter(filteredApps, getPackageManager(), app -> {
            Intent result = new Intent();
            result.putExtra("package_name", app.packageName);
            result.putExtra("app_name", app.loadLabel(getPackageManager()).toString());
            setResult(RESULT_OK, result);
            finish();
        });
        rvApps.setAdapter(adapter);

        loadApps();

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) { return false; }

            @Override
            public boolean onQueryTextChange(String newText) {
                filterApps(newText);
                return true;
            }
        });
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        allApps.clear();
        for (ApplicationInfo app : apps) {
            if (pm.getLaunchIntentForPackage(app.packageName) != null) {
                allApps.add(app);
            }
        }
        Collections.sort(allApps, (a, b) -> 
            a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));
        filteredApps.clear();
        filteredApps.addAll(allApps);
        adapter.notifyDataSetChanged();
    }

    private void filterApps(String query) {
        filteredApps.clear();
        PackageManager pm = getPackageManager();
        for (ApplicationInfo app : allApps) {
            String label = app.loadLabel(pm).toString().toLowerCase();
            if (label.contains(query.toLowerCase()) || app.packageName.toLowerCase().contains(query.toLowerCase())) {
                filteredApps.add(app);
            }
        }
        adapter.notifyDataSetChanged();
    }
}
