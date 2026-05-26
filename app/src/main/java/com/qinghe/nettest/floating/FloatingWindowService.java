package com.qinghe.nettest.floating;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatButton;

import com.qinghe.nettest.R;
import com.qinghe.nettest.db.AppDatabase;
import com.qinghe.nettest.model.NetworkConfig;
import com.qinghe.nettest.vpn.QingheVpnService;

import java.util.ArrayList;
import java.util.List;

public class FloatingWindowService extends Service {
    private WindowManager windowManager;
    private View floatingView;
    private LinearLayout layoutExpanded;
    private GridLayout gridConfigs;
    private ImageButton btnToggle;
    private ImageButton btnExpand;
    private boolean isExpanded = true;
    private final List<NetworkConfig> configs = new ArrayList<>();
    private int selectedConfigId = -1;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createFloatingWindow();
    }

    private void createFloatingWindow() {
        int layoutType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 100;

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_window, null);
        windowManager.addView(floatingView, params);

        SharedPreferences prefs = getSharedPreferences("qinghe_prefs", MODE_PRIVATE);
        selectedConfigId = prefs.getInt("active_config_id", -1);

        btnToggle = floatingView.findViewById(R.id.btn_floating_toggle);
        ImageButton btnRefresh = floatingView.findViewById(R.id.btn_floating_refresh);
        btnExpand = floatingView.findViewById(R.id.btn_floating_expand);
        layoutExpanded = floatingView.findViewById(R.id.layout_expanded);
        gridConfigs = floatingView.findViewById(R.id.grid_configs);

        refreshToggleButton();
        updateExpandedState();

        btnToggle.setOnClickListener(v -> toggleVpn());
        btnRefresh.setOnClickListener(v -> loadConfigs());
        btnExpand.setOnClickListener(v -> {
            isExpanded = !isExpanded;
            updateExpandedState();
        });

        loadConfigs();
    }

    private void toggleVpn() {
        if (QingheVpnService.isServiceRunning()) {
            getSharedPreferences("qinghe_prefs", MODE_PRIVATE).edit()
                .putBoolean("service_active", false)
                .apply();
            Intent stopIntent = new Intent(this, QingheVpnService.class);
            stopIntent.setAction(QingheVpnService.ACTION_STOP);
            startService(stopIntent);
            refreshToggleButton();
            return;
        }

        Intent vpnIntent = VpnService.prepare(getApplicationContext());
        if (vpnIntent != null) {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
            }
            return;
        }

        Intent startIntent = new Intent(this, QingheVpnService.class);
        startIntent.setAction(QingheVpnService.ACTION_START);
        if (selectedConfigId != -1) {
            startIntent.putExtra(QingheVpnService.EXTRA_CONFIG_ID, selectedConfigId);
        }
        getSharedPreferences("qinghe_prefs", MODE_PRIVATE).edit()
            .putBoolean("service_active", true)
            .apply();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(startIntent);
        } else {
            startService(startIntent);
        }
        refreshToggleButton();
    }

    private void refreshToggleButton() {
        SharedPreferences prefs = getSharedPreferences("qinghe_prefs", MODE_PRIVATE);
        boolean running = prefs.getBoolean("service_active", false) || QingheVpnService.isServiceRunning();
        btnToggle.setBackgroundResource(running ? R.drawable.floating_tile_selected : R.drawable.floating_tile_normal);
        btnToggle.setSelected(running);
    }

    private void updateExpandedState() {
        layoutExpanded.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
        btnExpand.setImageResource(isExpanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
        btnExpand.setBackgroundResource(isExpanded ? R.drawable.floating_tile_selected : R.drawable.floating_tile_normal);
    }

    private void loadConfigs() {
        new Thread(() -> {
            List<NetworkConfig> list = AppDatabase.getInstance(this).networkConfigDao().getAll();
            configs.clear();
            configs.addAll(list);
            if (floatingView != null) {
                floatingView.post(this::renderConfigButtons);
            }
        }).start();
    }

    private void renderConfigButtons() {
        gridConfigs.removeAllViews();
        if (configs.isEmpty()) {
            TextView emptyView = new TextView(this);
            emptyView.setText(R.string.no_config);
            emptyView.setTextColor(getColorCompat(R.color.white));
            emptyView.setPadding(dp(16), dp(16), dp(16), dp(16));
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(0), GridLayout.spec(0, 3f));
            params.width = GridLayout.LayoutParams.MATCH_PARENT;
            emptyView.setLayoutParams(params);
            gridConfigs.addView(emptyView);
            return;
        }

        for (int i = 0; i < configs.size(); i++) {
            NetworkConfig config = configs.get(i);
            AppCompatButton button = new AppCompatButton(this);
            button.setText(config.getName());
            button.setAllCaps(false);
            button.setTextColor(getColorCompat(R.color.white));
            button.setPadding(dp(8), dp(8), dp(8), dp(8));
            button.setBackgroundResource(config.getId() == selectedConfigId
                ? R.drawable.floating_tile_selected
                : R.drawable.floating_tile_normal);

            GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(i / 3, 1f), GridLayout.spec(i % 3, 1f));
            params.width = 0;
            params.height = dp(64);
            params.setMargins(0, 0, 0, 0);
            button.setLayoutParams(params);

            button.setOnClickListener(v -> {
                selectedConfigId = config.getId();
                getSharedPreferences("qinghe_prefs", MODE_PRIVATE).edit()
                    .putInt("active_config_id", selectedConfigId)
                    .apply();
                if (QingheVpnService.isServiceRunning() && QingheVpnService.getInstance() != null) {
                    QingheVpnService.getInstance().updateConfig(selectedConfigId);
                }
                renderConfigButtons();
                refreshToggleButton();
            });
            gridConfigs.addView(button);
        }
    }

    private int getColorCompat(int colorRes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return getColor(colorRes);
        }
        return getResources().getColor(colorRes);
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    @Override
    public void onDestroy() {
        if (floatingView != null) {
            windowManager.removeView(floatingView);
        }
        super.onDestroy();
    }
}
