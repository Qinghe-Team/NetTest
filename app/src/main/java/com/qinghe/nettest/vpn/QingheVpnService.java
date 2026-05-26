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
import com.qinghe.nettest.MainActivity;
import com.qinghe.nettest.R;
import com.qinghe.nettest.db.AppDatabase;
import com.qinghe.nettest.model.NetworkConfig;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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
            .setSession("Qinghe弱网")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setMtu(1500);

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

        vpnThread = new Thread(this::runVpnLoop, "VPN-Loop");
        vpnThread.start();
    }

    /**
     * Main VPN loop: reads packets from tun device and applies weak-network effects.
     *
     * In Android's TUN VPN architecture:
     * - Reading from tun fd gets OUTGOING packets (app -> internet), source IP = 10.0.0.2
     * - Reading from tun fd also gets INCOMING packets (internet -> app), dest IP = 10.0.0.2
     *   after the kernel routes them back through the tun interface.
     *
     * We differentiate direction by checking the source/dest IP in the IP header,
     * then apply upstream or downstream effects accordingly.
     */
    private void runVpnLoop() {
        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
        byte[] packet = new byte[32767];
        Random random = new Random();

        while (isRunning.get()) {
            try {
                int length = in.read(packet);
                if (length <= 0) {
                    Thread.sleep(1);
                    continue;
                }

                NetworkConfig config = currentConfig;

                // If no config, pass through without any effect
                if (config == null) {
                    out.write(packet, 0, length);
                    continue;
                }

                // Parse IPv4 header to determine protocol and direction
                boolean isIpv4 = length >= 20 && ((packet[0] >> 4) & 0xF) == 4;
                if (!isIpv4) {
                    // Non-IPv4 packets pass through directly
                    out.write(packet, 0, length);
                    continue;
                }

                int ipProto = packet[9] & 0xFF;
                boolean isTcp = (ipProto == 6);
                boolean isUdp = (ipProto == 17);

                // Determine direction: outgoing has source 10.0.0.2
                boolean isOutgoing = (packet[12] == 10 && packet[13] == 0 &&
                                      packet[14] == 0 && packet[15] == 2);

                // Determine which protocol filter to apply
                int protocolFilter = config.getProtocol();
                boolean shouldApply = (protocolFilter == 3) ||
                    (protocolFilter == 1 && isTcp) ||
                    (protocolFilter == 2 && isUdp);

                if (shouldApply) {
                    if (isOutgoing) {
                        // Apply UPSTREAM effects
                        int upLoss = config.getUpPacketLoss();
                        if (upLoss > 0 && random.nextInt(100) < upLoss) {
                            continue; // drop packet
                        }

                        int delay = config.getUpDelay();
                        if (config.getUpJitter() > 0) {
                            delay += random.nextInt(config.getUpJitter());
                        }
                        if (delay > 0) {
                            Thread.sleep(delay);
                        }

                        int bw = config.getUpBandwidth();
                        if (bw > 0) {
                            int bytesPerSecond = bw * 1000 / 8;
                            if (bytesPerSecond > 0) {
                                int sleepMs = (length * 1000) / bytesPerSecond;
                                if (sleepMs > 0) Thread.sleep(sleepMs);
                            }
                        }
                    } else {
                        // Apply DOWNSTREAM effects
                        int downLoss = config.getDownPacketLoss();
                        if (downLoss > 0 && random.nextInt(100) < downLoss) {
                            continue; // drop packet
                        }

                        int delay = config.getDownDelay();
                        if (config.getDownJitter() > 0) {
                            delay += random.nextInt(config.getDownJitter());
                        }
                        if (delay > 0) {
                            Thread.sleep(delay);
                        }

                        int bw = config.getDownBandwidth();
                        if (bw > 0) {
                            int bytesPerSecond = bw * 1000 / 8;
                            if (bytesPerSecond > 0) {
                                int sleepMs = (length * 1000) / bytesPerSecond;
                                if (sleepMs > 0) Thread.sleep(sleepMs);
                            }
                        }
                    }
                }

                // Pass the packet through
                out.write(packet, 0, length);

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
