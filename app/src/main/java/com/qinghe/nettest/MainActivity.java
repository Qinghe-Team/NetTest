package com.qinghe.nettest;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.qinghe.nettest.db.AppDatabase;
import com.qinghe.nettest.floating.FloatingWindowService;
import com.qinghe.nettest.model.NetworkConfig;
import com.qinghe.nettest.vpn.QingheVpnService;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_APP_SELECT = 1001;
    private static final int REQUEST_VPN_PERMISSION = 1002;
    private static final int REQUEST_OVERLAY_PERMISSION = 1003;
    private TextView tvSelectedApp;
    private RecyclerView rvConfigs;
    private ConfigAdapter adapter;
    private List<NetworkConfig> configs = new ArrayList<>();
    private SharedPreferences prefs;
    private NetworkConfig selectedConfig;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("qinghe_prefs", MODE_PRIVATE);

        tvSelectedApp = findViewById(R.id.tv_selected_app);
        rvConfigs = findViewById(R.id.rv_configs);
        Button btnSelectApp = findViewById(R.id.btn_select_app);
        Button btnAddConfig = findViewById(R.id.btn_add_config);
        Button btnStart = findViewById(R.id.btn_start);

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
                            runOnUiThread(() -> {
                                selectedConfig = null;
                                loadConfigs();
                            });
                        }).start();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            }

            @Override
            public void onSelect(NetworkConfig config) {
                selectedConfig = config;
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

        btnStart.setOnClickListener(v -> {
            if (selectedConfig == null) {
                Toast.makeText(this, "请先选择一个配置项", Toast.LENGTH_SHORT).show();
                return;
            }
            startService();
        });

        updateSelectedApp();
    }

    private void startService() {
        // Check overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
            return;
        }

        // Check VPN permission
        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, REQUEST_VPN_PERMISSION);
            return;
        }

        // Save active config id
        prefs.edit()
            .putInt("active_config_id", selectedConfig.getId())
            .putBoolean("service_active", true)
            .apply();

        // Start VPN service
        Intent startVpn = new Intent(this, QingheVpnService.class);
        startVpn.setAction(QingheVpnService.ACTION_START);
        startVpn.putExtra(QingheVpnService.EXTRA_CONFIG_ID, selectedConfig.getId());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(startVpn);
        } else {
            startService(startVpn);
        }

        // Start floating window
        Intent floatingIntent = new Intent(this, FloatingWindowService.class);
        startService(floatingIntent);

        // Jump to target app
        String targetPackage = prefs.getString("selected_package", null);
        if (targetPackage != null) {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(targetPackage);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
            }
        }
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
        } else if (requestCode == REQUEST_VPN_PERMISSION && resultCode == RESULT_OK) {
            startService();
        } else if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                startService();
            } else {
                Toast.makeText(this, R.string.floating_window_permission, Toast.LENGTH_SHORT).show();
            }
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
