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
import android.widget.Button;
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
    private List<Button> configButtons = new ArrayList<>();

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
        Button btnClose = floatingView.findViewById(R.id.btn_floating_close);
        LinearLayout layoutExpanded = floatingView.findViewById(R.id.layout_expanded);

        // Fix issue 3: Show correct toggle state when service starts
        // Since this service is started from MainActivity after VPN is already started,
        // the VPN should be running, so show "停止"
        if (QingheVpnService.isServiceRunning()) {
            btnToggle.setText(R.string.btn_stop);
        } else {
            btnToggle.setText(R.string.btn_start);
        }

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
            if (isExpanded) {
                loadConfigs();
            }
        });

        // Close floating window
        btnClose.setOnClickListener(v -> {
            stopSelf();
        });

        // Touch listener for dragging - only on the top bar
        LinearLayout topBar = (LinearLayout) floatingView.findViewById(R.id.btn_floating_toggle).getParent();
        topBar.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isDragging = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (Math.abs(dx) > 5 || Math.abs(dy) > 5) {
                            isDragging = true;
                        }
                        if (isDragging) {
                            params.x = initialX + (int) dx;
                            params.y = initialY + (int) dy;
                            windowManager.updateViewLayout(floatingView, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        return isDragging;
                }
                return false;
            }
        });
    }

    private void loadConfigs() {
        new Thread(() -> {
            List<NetworkConfig> list = AppDatabase.getInstance(this).networkConfigDao().getAll();
            configs.clear();
            configs.addAll(list);
            if (floatingView != null) {
                floatingView.post(() -> buildConfigGrid());
            }
        }).start();
    }

    private void buildConfigGrid() {
        LinearLayout configGrid = floatingView.findViewById(R.id.config_grid);
        configGrid.removeAllViews();
        configButtons.clear();

        if (configs.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.no_config);
            empty.setTextColor(0xFFFFFFFF);
            empty.setPadding(8, 8, 8, 8);
            empty.setTextSize(12);
            configGrid.addView(empty);
            return;
        }

        // Determine which config is currently active
        int activeConfigId = getSharedPreferences("qinghe_prefs", MODE_PRIVATE)
            .getInt("active_config_id", -1);
        selectedConfigIndex = -1;
        for (int i = 0; i < configs.size(); i++) {
            if (configs.get(i).getId() == activeConfigId) {
                selectedConfigIndex = i;
                break;
            }
        }

        // Build grid - flat tiled buttons, aligned with top bar
        LinearLayout currentRow = null;
        int buttonsInRow = 0;
        int columnsPerRow = 3;

        for (int i = 0; i < configs.size(); i++) {
            if (buttonsInRow % columnsPerRow == 0) {
                currentRow = new LinearLayout(this);
                currentRow.setOrientation(LinearLayout.HORIZONTAL);
                currentRow.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
                configGrid.addView(currentRow);
                buttonsInRow = 0;
            }

            Button btn = new Button(this);
            btn.setText(configs.get(i).getName());
            btn.setTextSize(10);
            btn.setTextColor(0xFFFFFFFF);
            btn.setPadding(4, 4, 4, 4);
            btn.setMinWidth(0);
            btn.setMinHeight(0);
            btn.setMinimumWidth(0);
            btn.setMinimumHeight(0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                btn.setStateListAnimator(null);
            }

            LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                0, 44);
            btnParams.weight = 1;
            btn.setLayoutParams(btnParams);

            // Set background based on selection
            if (i == selectedConfigIndex) {
                btn.setBackgroundResource(R.drawable.btn_square_blue);
            } else {
                btn.setBackgroundResource(R.drawable.btn_square_grey);
            }

            final int index = i;
            btn.setOnClickListener(v -> {
                selectConfig(index);
            });

            configButtons.add(btn);
            currentRow.addView(btn);
            buttonsInRow++;
        }

        // Fill remaining slots in last row with empty views for alignment
        if (buttonsInRow > 0 && buttonsInRow < columnsPerRow && currentRow != null) {
            for (int i = buttonsInRow; i < columnsPerRow; i++) {
                View spacer = new View(this);
                LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
                    0, 44);
                spacerParams.weight = 1;
                spacer.setLayoutParams(spacerParams);
                spacer.setBackgroundResource(R.drawable.btn_square_grey);
                spacer.setAlpha(0.3f);
                currentRow.addView(spacer);
            }
        }
    }

    private void selectConfig(int index) {
        // Update visual selection
        if (selectedConfigIndex >= 0 && selectedConfigIndex < configButtons.size()) {
            configButtons.get(selectedConfigIndex).setBackgroundResource(R.drawable.btn_square_grey);
        }
        selectedConfigIndex = index;
        if (selectedConfigIndex >= 0 && selectedConfigIndex < configButtons.size()) {
            configButtons.get(selectedConfigIndex).setBackgroundResource(R.drawable.btn_square_blue);
        }

        // Apply config immediately (real-time)
        if (index < configs.size()) {
            NetworkConfig config = configs.get(index);
            getSharedPreferences("qinghe_prefs", MODE_PRIVATE).edit()
                .putInt("active_config_id", config.getId()).apply();
            if (QingheVpnService.isServiceRunning() && QingheVpnService.getInstance() != null) {
                QingheVpnService.getInstance().updateConfig(config.getId());
            }
        }
    }

    @Override
    public void onDestroy() {
        if (floatingView != null) {
            windowManager.removeView(floatingView);
        }
        super.onDestroy();
    }
}
