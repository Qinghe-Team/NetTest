package com.qinghe.nettest.vpn;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import com.qinghe.nettest.MainActivity;
import com.qinghe.nettest.R;
import com.qinghe.nettest.db.AppDatabase;
import com.qinghe.nettest.model.NetworkConfig;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class QingheVpnService extends VpnService {
    private static final String CHANNEL_ID = "qinghe_vpn_channel";
    private static final int NOTIFICATION_ID = 1;
    public static final String ACTION_START = "com.qinghe.nettest.VPN_START";
    public static final String ACTION_STOP = "com.qinghe.nettest.VPN_STOP";
    public static final String EXTRA_CONFIG_ID = "config_id";

    private ParcelFileDescriptor vpnInterface;
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private Thread vpnThread;
    private volatile NetworkConfig currentConfig;
    private static QingheVpnService instance;

    public static QingheVpnService getInstance() {
        return instance;
    }

    public static boolean isServiceRunning() {
        return instance != null && instance.isRunning.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }

        int configId = -1;
        if (intent != null) {
            configId = intent.getIntExtra(EXTRA_CONFIG_ID, -1);
        }
        if (configId == -1) {
            SharedPreferences prefs = getSharedPreferences("qinghe_prefs", MODE_PRIVATE);
            configId = prefs.getInt("active_config_id", -1);
        }

        if (configId != -1) {
            final int finalConfigId = configId;
            new Thread(() -> {
                currentConfig = AppDatabase.getInstance(QingheVpnService.this).networkConfigDao().getById(finalConfigId);
                startVpn();
            }).start();
        } else {
            currentConfig = new NetworkConfig();
            startVpn();
        }

        return START_STICKY;
    }

    public void updateConfig(int configId) {
        new Thread(() -> {
            currentConfig = AppDatabase.getInstance(this).networkConfigDao().getById(configId);
            getSharedPreferences("qinghe_prefs", MODE_PRIVATE).edit()
                .putInt("active_config_id", configId).apply();
        }).start();
    }

    private void startVpn() {
        if (isRunning.get()) return;

        SharedPreferences prefs = getSharedPreferences("qinghe_prefs", MODE_PRIVATE);
        String selectedPackage = prefs.getString("selected_package", null);

        Builder builder = new Builder()
            .setSession("Qinghe")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8");

        if (selectedPackage != null) {
            try {
                builder.addAllowedApplication(selectedPackage);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        vpnInterface = builder.establish();
        if (vpnInterface == null) {
            stopSelf();
            return;
        }

        isRunning.set(true);
        startForeground(NOTIFICATION_ID, createNotification());

        vpnThread = new Thread(this::runVpnLoop);
        vpnThread.start();
    }

    private void runVpnLoop() {
        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
        ByteBuffer buffer = ByteBuffer.allocate(32767);
        Random random = new Random();
        long upContinuousLossCycleStartedAt = SystemClock.elapsedRealtime();
        int lastUpContinuousLossPassTime = 0;
        int lastUpContinuousLossDropTime = 0;

        while (isRunning.get()) {
            try {
                buffer.clear();
                int length = in.read(buffer.array());
                if (length <= 0) {
                    Thread.sleep(10);
                    continue;
                }

                if (currentConfig == null) {
                    out.write(buffer.array(), 0, length);
                    continue;
                }

                // Apply upstream effects
                boolean isUdp = length > 9 && (buffer.get(9) & 0xFF) == 17;
                boolean isTcp = length > 9 && (buffer.get(9) & 0xFF) == 6;
                int protocol = currentConfig.getProtocol();

                boolean shouldApply = (protocol == 3) ||
                    (protocol == 1 && isTcp) ||
                    (protocol == 2 && isUdp);

                if (shouldApply) {
                    int upContinuousLossPassTime = Math.max(0, currentConfig.getUpContinuousLossPassTime());
                    int upContinuousLossDropTime = Math.max(0, currentConfig.getUpContinuousLossDropTime());
                    if (upContinuousLossPassTime != lastUpContinuousLossPassTime
                        || upContinuousLossDropTime != lastUpContinuousLossDropTime) {
                        upContinuousLossCycleStartedAt = SystemClock.elapsedRealtime();
                        lastUpContinuousLossPassTime = upContinuousLossPassTime;
                        lastUpContinuousLossDropTime = upContinuousLossDropTime;
                    }

                    if (shouldDropForContinuousLoss(
                        upContinuousLossPassTime,
                        upContinuousLossDropTime,
                        upContinuousLossCycleStartedAt,
                        SystemClock.elapsedRealtime())) {
                        continue;
                    }

                    // Random packet loss
                    int upPacketLoss = clampPercentage(currentConfig.getUpPacketLoss());
                    if (upPacketLoss > 0) {
                        if (random.nextInt(100) < upPacketLoss) {
                            continue; // drop packet
                        }
                    }

                    // Delay simulation
                    int delay = currentConfig.getUpDelay();
                    if (currentConfig.getUpJitter() > 0) {
                        delay += random.nextInt(currentConfig.getUpJitter());
                    }
                    if (delay > 0) {
                        Thread.sleep(delay);
                    }

                    // Bandwidth limiting
                    long bandwidthDelay = calculateBandwidthDelayMillis(length, currentConfig.getUpBandwidth());
                    if (bandwidthDelay > 0) {
                        Thread.sleep(bandwidthDelay);
                    }
                }

                out.write(buffer.array(), 0, length);

            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                if (isRunning.get()) {
                    e.printStackTrace();
                }
                break;
            }
        }
    }

    private static int clampPercentage(int percentage) {
        return Math.max(0, Math.min(percentage, 100));
    }

    private static boolean shouldDropForContinuousLoss(
        int passTimeMs, int dropTimeMs, long cycleStartedAtMs, long nowMs) {
        if (dropTimeMs <= 0) {
            return false;
        }
        if (passTimeMs <= 0) {
            return true;
        }

        long cycleDurationMs = (long) passTimeMs + dropTimeMs;
        if (cycleDurationMs <= 0) {
            return false;
        }

        long elapsedMs = Math.max(0, nowMs - cycleStartedAtMs);
        long positionInCycle = elapsedMs % cycleDurationMs;
        return positionInCycle >= passTimeMs;
    }

    private static long calculateBandwidthDelayMillis(int packetLengthBytes, int bandwidthKbps) {
        if (packetLengthBytes <= 0 || bandwidthKbps <= 0) {
            return 0;
        }

        long bits = packetLengthBytes * 8L;
        return (bits + bandwidthKbps - 1L) / bandwidthKbps;
    }

    private void stopVpn() {
        isRunning.set(false);
        if (vpnThread != null) {
            vpnThread.interrupt();
            vpnThread = null;
        }
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            vpnInterface = null;
        }
        stopForeground(true);
    }

    @Override
    public void onDestroy() {
        stopVpn();
        instance = null;
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Qinghe VPN Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_content))
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pendingIntent)
            .build();
    }
}
