package com.qinghe.nettest.floating;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.qinghe.nettest.R;
import com.qinghe.nettest.db.AppDatabase;
import com.qinghe.nettest.model.NetworkConfig;
import com.qinghe.nettest.vpn.QingheVpnService;
import java.util.ArrayList;
import java.util.List;

/**
 * Floating window service matching the APK's FWService architecture:
 * - Top bar with icon + start/pause + expand/collapse ImageViews
 * - Expanded area shows a ListView of config profiles
 * - Selected item highlighted with blue background
 * - Selection applies config immediately (real-time)
 */
public class FloatingWindowService extends Service {
    private static FloatingWindowService instance;
    private WindowManager windowManager;
    private LinearLayout floatingView;
    private LinearLayout expandView;
    private WindowManager.LayoutParams fwParams;

    private ImageView imgIcon;
    private ImageView imgStart;
    private ImageView imgExpand;

    private boolean isStarted = false;
    private boolean isExpanded = false;
    private List<NetworkConfig> configs = new ArrayList<>();
    private int selectedIndex = -1;
    private ConfigListAdapter adapter;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public FloatingWindowService() {
        instance = this;
    }

    public static FloatingWindowService getInstance() {
        return instance;
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

        fwParams = new WindowManager.LayoutParams();
        fwParams.type = layoutType;
        fwParams.format = PixelFormat.TRANSLUCENT;
        fwParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        fwParams.gravity = Gravity.TOP | Gravity.START;

        // Restore saved position
        SharedPreferences prefs = getSharedPreferences("FW_PRES", MODE_PRIVATE);
        fwParams.x = prefs.getInt("FW_X", 0);
        fwParams.y = prefs.getInt("FW_Y", 0);
        fwParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        fwParams.height = WindowManager.LayoutParams.WRAP_CONTENT;

        floatingView = (LinearLayout) LayoutInflater.from(this).inflate(R.layout.floating_window, null);
        windowManager.addView(floatingView, fwParams);

        initEvents();
    }

    private void initEvents() {
        imgIcon = floatingView.findViewById(R.id.img_icon);
        imgStart = floatingView.findViewById(R.id.img_start);
        imgExpand = floatingView.findViewById(R.id.img_expand);

        // Fix issue 3: Set correct initial state - VPN should be running when this service starts
        // (matches APK: LocalVpnService.IsActive = true at init)
        isStarted = QingheVpnService.isServiceRunning();
        imgStart.setImageResource(isStarted ? R.drawable.ic_pause : R.drawable.ic_play_arrow);

        // Start/Pause button
        imgStart.setOnClickListener(v -> {
            isStarted = !isStarted;
            imgStart.setImageResource(isStarted ? R.drawable.ic_pause : R.drawable.ic_play_arrow);

            if (isStarted) {
                // Start VPN
                Intent startIntent = new Intent(this, QingheVpnService.class);
                startIntent.setAction(QingheVpnService.ACTION_START);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(startIntent);
                } else {
                    startService(startIntent);
                }
                Toast.makeText(this, R.string.fw_config_active, Toast.LENGTH_SHORT).show();
            } else {
                // Stop VPN
                Intent stopIntent = new Intent(this, QingheVpnService.class);
                stopIntent.setAction(QingheVpnService.ACTION_STOP);
                startService(stopIntent);
                Toast.makeText(this, R.string.fw_config_inactive, Toast.LENGTH_SHORT).show();
            }
        });

        // Expand/Collapse button
        imgExpand.setOnClickListener(v -> {
            isExpanded = !isExpanded;
            imgExpand.setImageResource(isExpanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);

            LinearLayout layoutExpanded = floatingView.findViewById(R.id.layout_expanded);
            if (isExpanded) {
                layoutExpanded.setVisibility(View.VISIBLE);
                loadConfigs();
            } else {
                layoutExpanded.setVisibility(View.GONE);
            }
        });

        // Touch listener for dragging on the icon
        imgIcon.setOnTouchListener(new View.OnTouchListener() {
            private int posX, posY;
            private int touchX, touchY;
            private long lastClickTime = 0;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                long now = System.currentTimeMillis();
                if (now - lastClickTime > 500) {
                    touchX = (int) event.getRawX();
                    touchY = (int) event.getRawY();
                    posX = fwParams.x;
                    posY = fwParams.y;
                }
                lastClickTime = now;

                fwParams.x = posX + (int) event.getRawX() - touchX;
                fwParams.y = posY + (int) event.getRawY() - touchY;
                windowManager.updateViewLayout(floatingView, fwParams);
                saveFwPos(fwParams.x, fwParams.y);
                return false;
            }
        });
    }

    private void saveFwPos(int x, int y) {
        try {
            SharedPreferences.Editor editor = getSharedPreferences("FW_PRES", MODE_PRIVATE).edit();
            editor.putInt("FW_X", x);
            editor.putInt("FW_Y", y);
            editor.apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void loadConfigs() {
        new Thread(() -> {
            List<NetworkConfig> list = AppDatabase.getInstance(this).networkConfigDao().getAll();
            configs.clear();
            configs.addAll(list);

            // Determine which config is currently active
            int activeConfigId = getSharedPreferences("qinghe_prefs", MODE_PRIVATE)
                .getInt("active_config_id", -1);
            selectedIndex = -1;
            for (int i = 0; i < configs.size(); i++) {
                if (configs.get(i).getId() == activeConfigId) {
                    selectedIndex = i;
                    break;
                }
            }

            if (floatingView != null) {
                floatingView.post(this::buildConfigList);
            }
        }).start();
    }

    private void buildConfigList() {
        ListView listView = floatingView.findViewById(R.id.lv_configs);
        List<String> names = new ArrayList<>();
        for (NetworkConfig config : configs) {
            names.add(config.getName());
        }

        adapter = new ConfigListAdapter(names, selectedIndex);
        listView.setAdapter(adapter);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            selectedIndex = position;
            adapter.setSelectedPosition(position);

            // Apply config immediately
            if (position < configs.size()) {
                NetworkConfig config = configs.get(position);
                getSharedPreferences("qinghe_prefs", MODE_PRIVATE).edit()
                    .putInt("active_config_id", config.getId()).apply();
                if (QingheVpnService.isServiceRunning() && QingheVpnService.getInstance() != null) {
                    QingheVpnService.getInstance().updateConfig(config.getId());
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (floatingView != null) {
            windowManager.removeView(floatingView);
        }
        super.onDestroy();
    }

    /**
     * ListView adapter for config profiles.
     * Selected item gets blue background, others are transparent with selector.
     */
    private class ConfigListAdapter extends BaseAdapter {
        private List<String> data;
        private int selectedPosition = -1;

        ConfigListAdapter(List<String> data, int selectedPosition) {
            this.data = data;
            this.selectedPosition = selectedPosition;
        }

        void setSelectedPosition(int pos) {
            this.selectedPosition = pos;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return data != null ? data.size() : 0;
        }

        @Override
        public Object getItem(int position) {
            return data != null ? data.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView textView;
            if (convertView == null) {
                convertView = LayoutInflater.from(FloatingWindowService.this)
                    .inflate(R.layout.item_config_list, parent, false);
            }
            textView = convertView.findViewById(R.id.txt_config_item);
            textView.setText(data.get(position));

            if (position == selectedPosition) {
                textView.setTextColor(0xFF2196F3); // blue text
                convertView.setBackgroundResource(R.drawable.btn_square_blue);
            } else {
                textView.setTextColor(0xFFFFFFFF); // white text
                convertView.setBackgroundResource(R.drawable.fw_listview_color_selector);
            }
            return convertView;
        }
    }
}
