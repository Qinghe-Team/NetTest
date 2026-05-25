package com.qinghe.nettest;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.qinghe.nettest.db.AppDatabase;
import com.qinghe.nettest.model.NetworkConfig;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_APP_SELECT = 1001;
    private TextView tvSelectedApp;
    private RecyclerView rvConfigs;
    private ConfigAdapter adapter;
    private List<NetworkConfig> configs = new ArrayList<>();
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("qinghe_prefs", MODE_PRIVATE);

        tvSelectedApp = findViewById(R.id.tv_selected_app);
        rvConfigs = findViewById(R.id.rv_configs);
        Button btnSelectApp = findViewById(R.id.btn_select_app);
        Button btnAddConfig = findViewById(R.id.btn_add_config);

        rvConfigs.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ConfigAdapter(configs, new ConfigAdapter.OnConfigActionListener() {
            @Override
            public void onEdit(NetworkConfig config) {
                Intent intent = new Intent(MainActivity.this, ConfigEditActivity.class);
                intent.putExtra("config_id", config.getId());
                startActivity(intent);
            }

            @Override
            public void onDelete(NetworkConfig config) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(R.string.config_delete_confirm)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        new Thread(() -> {
                            AppDatabase.getInstance(MainActivity.this).networkConfigDao().delete(config);
                            runOnUiThread(() -> loadConfigs());
                        }).start();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            }
        });
        rvConfigs.setAdapter(adapter);

        btnSelectApp.setOnClickListener(v -> {
            Intent intent = new Intent(this, AppSelectActivity.class);
            startActivityForResult(intent, REQUEST_APP_SELECT);
        });

        btnAddConfig.setOnClickListener(v -> {
            startActivity(new Intent(this, ConfigEditActivity.class));
        });

        updateSelectedApp();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadConfigs();
    }

    private void loadConfigs() {
        new Thread(() -> {
            List<NetworkConfig> list = AppDatabase.getInstance(this).networkConfigDao().getAll();
            runOnUiThread(() -> {
                configs.clear();
                configs.addAll(list);
                adapter.notifyDataSetChanged();
            });
        }).start();
    }

    private void updateSelectedApp() {
        String pkg = prefs.getString("selected_package", null);
        String name = prefs.getString("selected_app_name", null);
        if (pkg != null && name != null) {
            tvSelectedApp.setText(getString(R.string.selected_app, name));
        } else {
            tvSelectedApp.setText(R.string.no_app_selected);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_APP_SELECT && resultCode == RESULT_OK && data != null) {
            String pkg = data.getStringExtra("package_name");
            String name = data.getStringExtra("app_name");
            prefs.edit().putString("selected_package", pkg).putString("selected_app_name", name).apply();
            updateSelectedApp();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_about) {
            startActivity(new Intent(this, AboutActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
