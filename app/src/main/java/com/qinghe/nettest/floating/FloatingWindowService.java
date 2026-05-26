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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
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
    private int selectedConfigIndex = -1;

    @Override
    public IBinder onBind(Intent intent) { return null; }

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

        ImageButton btnToggle   = floatingView.findViewById(R.id.btn_floating_toggle);
        ImageButton btnProtocol = floatingView.findViewById(R.id.btn_floating_protocol);
        ImageButton btnClose    = floatingView.findViewById(R.id.btn_floating_close);
        ImageButton btnExpand   = floatingView.findViewById(R.id.btn_floating_expand);
        LinearLayout layoutExpanded = floatingView.findViewById(R.id.layout_expanded);

        // Fix 3: initialise toggle colour based on actual running state
        updateToggleButton(btnToggle);

        // Expand / collapse on tap of the collapsed handle
        btnExpand.setOnClickListener(v -> {
            isExpanded = !isExpanded;
            layoutExpanded.setVisibility(isExpanded ? View.VISIBLE : View.GONE);
            if (isExpanded) loadConfigs();
        });

        // Start / stop VPN
        btnToggle.setOnClickListener(v -> {
            if (QingheVpnService.isServiceRunning()) {
                Intent stop = new Intent(this, QingheVpnService.class);
                stop.setAction(QingheVpnService.ACTION_STOP);
                startService(stop);
            } else {
                Intent vpnIntent = VpnService.prepare(getApplicationContext());
                if (vpnIntent == null) {
                    Intent start = new Intent(this, QingheVpnService.class);
                    start.setAction(QingheVpnService.ACTION_START);
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(start);
                    } else {
                        startService(start);
                    }
                }
            }
            updateToggleButton(btnToggle);
        });

        // Protocol button shows active config's protocol
        btnProtocol.setOnClickListener(v -> applyProtocolToActiveConfig());

        // Close the floating window
        btnClose.setOnClickListener(v -> stopSelf());

        // Drag to move
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private int initX, initY;
            private float initTX, initTY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initX = params.x; initY = params.y;
                        initTX = e.getRawX(); initTY = e.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int)(e.getRawX() - initTX);
                        int dy = (int)(e.getRawY() - initTY);
                        if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                            params.x = initX + dx;
                            params.y = initY + dy;
                            windowManager.updateViewLayout(floatingView, params);
                            moved = true;
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        return moved; // consume only if actually dragged
                }
                return false;
            }
        });
    }

    // ─────────────────── Config tile list ────────────────────────────────────

    private void loadConfigs() {
        new Thread(() -> {
            List<NetworkConfig> list = AppDatabase.getInstance(this).networkConfigDao().getAll();
            if (floatingView == null) return;
            floatingView.post(() -> {
                configs.clear();
                configs.addAll(list);
                rebuildConfigTiles();
            });
        }).start();
    }

    private void rebuildConfigTiles() {
        LinearLayout container = floatingView.findViewById(R.id.layout_configs);
        container.removeAllViews();

        for (int i = 0; i < configs.size(); i++) {
            NetworkConfig cfg = configs.get(i);
            final int idx = i;

            TextView tile = new TextView(this);
            tile.setText(cfg.getName());
            tile.setTextColor(0xFFFFFFFF);
            tile.setTextSize(13);
            tile.setGravity(android.view.Gravity.CENTER);
            tile.setPadding(0, 18, 0, 18);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tile.setLayoutParams(lp);

            updateTileBackground(tile, idx == selectedConfigIndex);

            tile.setOnClickListener(v -> {
                selectedConfigIndex = idx;
                applySelectedConfig();
                refreshTileHighlights();
                updateProtocolLabel(cfg.getProtocol());
            });

            container.addView(tile);
        }
    }

    private void updateProtocolLabel(int protocol) {
        TextView tv = floatingView.findViewById(R.id.tv_protocol_label);
        if (tv == null) return;
        String label;
        switch (protocol) {
            case 1: label = "协议: TCP"; break;
            case 2: label = "协议: UDP"; break;
            default: label = "协议: 全部"; break;
        }
        tv.setText(label);
    }

    private void updateTileBackground(TextView tile, boolean selected) {
        tile.setBackgroundColor(selected ? 0xFF1976D2 : 0xFF444444);
    }

    private void refreshTileHighlights() {
        LinearLayout container = floatingView.findViewById(R.id.layout_configs);
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof TextView) {
                updateTileBackground((TextView) child, i == selectedConfigIndex);
            }
        }
    }

    private void applySelectedConfig() {
        if (selectedConfigIndex < 0 || selectedConfigIndex >= configs.size()) return;
        NetworkConfig cfg = configs.get(selectedConfigIndex);
        if (QingheVpnService.isServiceRunning() && QingheVpnService.getInstance() != null) {
            QingheVpnService.getInstance().updateConfig(cfg.getId());
        }
        getSharedPreferences("qinghe_prefs", MODE_PRIVATE).edit()
                .putInt("active_config_id", cfg.getId()).apply();
    }

    // Protocol button just shows current selected config's protocol (visual only)
    private void applyProtocolToActiveConfig() {
        if (selectedConfigIndex < 0 || selectedConfigIndex >= configs.size()) return;
        updateProtocolLabel(configs.get(selectedConfigIndex).getProtocol());
    }

    // ─────────────────── Toggle button state ────────────────────────────────

    private void updateToggleButton(ImageButton btn) {
        btn.setBackgroundColor(QingheVpnService.isServiceRunning() ? 0xFF1976D2 : 0xFF555555);
    }

    @Override
    public void onDestroy() {
        if (floatingView != null) windowManager.removeView(floatingView);
        super.onDestroy();
    }
}

