package com.qinghe.nettest.floating;

import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.AdapterView;
import com.qinghe.nettest.R;
import com.qinghe.nettest.db.AppDatabase;
import com.qinghe.nettest.model.NetworkConfig;
import com.qinghe.nettest.vpn.QingheVpnService;
import java.util.ArrayList;
import java.util.List;

public class FloatingWindowService extends Service {
    private WindowManager windowManager;
    private View floatingView;
    private boolean isExpanded = false;
    private List<NetworkConfig> configs = new ArrayList<>();
    private List<String> configNames = new ArrayList<>();

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
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
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

        Button btnToggle = floatingView.findViewById(R.id.btn_floating_toggle);
        Button btnExpand = floatingView.findViewById(R.id.btn_floating_expand);
        LinearLayout layoutExpanded = floatingView.findViewById(R.id.layout_expanded);
        Spinner spinnerConfig = floatingView.findViewById(R.id.spinner_config);

        // Toggle VPN service
        btnToggle.setOnClickListener(v -> {
            if (QingheVpnService.isServiceRunning()) {
                Intent stopIntent = new Intent(this, QingheVpnService.class);
                stopIntent.setAction(QingheVpnService.ACTION_STOP);
                startService(stopIntent);
                btnToggle.setText(R.string.btn_start);
            } else {
                Intent vpnIntent = VpnService.prepare(getApplicationContext());
                if (vpnIntent == null) {
                    Intent startIntent = new Intent(this, QingheVpnService.class);
                    startIntent.setAction(QingheVpnService.ACTION_START);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(startIntent);
                    } else {
                        startService(startIntent);
                    }
                    btnToggle.setText(R.string.btn_stop);
                }
            }
        });

        // Expand/collapse
        btnExpand.setOnClickListener(v -> {
            isExpanded = !isExpanded;
            layoutExpanded.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
            btnExpand.setText(isExpanded ? "▲" : "▼");
        });

        // Load configs for spinner
        loadConfigs(spinnerConfig);

        // Config selection listener
        spinnerConfig.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < configs.size() && QingheVpnService.isServiceRunning()) {
                    QingheVpnService.getInstance().updateConfig(configs.get(position).getId());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Touch listener for dragging
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        windowManager.updateViewLayout(floatingView, params);
                        return true;
                }
                return false;
            }
        });
    }

    private void loadConfigs(Spinner spinner) {
        new Thread(() -> {
            List<NetworkConfig> list = AppDatabase.getInstance(this).networkConfigDao().getAll();
            configs.clear();
            configs.addAll(list);
            configNames.clear();
            for (NetworkConfig c : configs) {
                configNames.add(c.getName());
            }
            if (floatingView != null) {
                floatingView.post(() -> {
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(
                        FloatingWindowService.this,
                        android.R.layout.simple_spinner_item,
                        configNames);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spinner.setAdapter(adapter);
                });
            }
        }).start();
    }

    @Override
    public void onDestroy() {
        if (floatingView != null) {
            windowManager.removeView(floatingView);
        }
        super.onDestroy();
    }
}
