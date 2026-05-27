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
import android.util.Log;
import com.qinghe.nettest.MainActivity;
import com.qinghe.nettest.R;
import com.qinghe.nettest.db.AppDatabase;
import com.qinghe.nettest.model.NetworkConfig;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VPN Service that implements weak-network simulation using a proxy-based architecture.
 *
 * Architecture (replicated from APK analysis):
 * 1. TUN device captures all traffic from the target app
 * 2. Outgoing TCP packets are redirected to a local TCP proxy server
 * 3. Outgoing UDP packets are redirected to a local UDP proxy server
 * 4. The proxy servers create real (protected) connections to destinations
 * 5. Responses come back through the proxies and are written to TUN
 * 6. Weak-network effects (delay, drop, bandwidth) are applied per-direction
 *    via a DelayQueue before packets are written to TUN
 */
public class QingheVpnService extends VpnService {
    private static final String TAG = "QingheVPN";
    private static final String CHANNEL_ID = "qinghe_vpn_channel";
    private static final int NOTIFICATION_ID = 1;
    private static final int MAX_PACKET_SIZE = 20000;
    public static final String ACTION_START = "com.qinghe.nettest.VPN_START";
    public static final String ACTION_STOP = "com.qinghe.nettest.VPN_STOP";
    public static final String EXTRA_CONFIG_ID = "config_id";

    private ParcelFileDescriptor vpnInterface;
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private Thread vpnThread;
    private Thread upDelayThread;
    private Thread downDelayThread;
    private volatile NetworkConfig currentConfig;
    private static QingheVpnService instance;

    // Local IP assigned to TUN interface
    private static final int LOCAL_IP = ipToInt(10, 0, 0, 2);

    // TCP proxy
    private ServerSocketChannel tcpProxyChannel;
    private short tcpProxyPort;
    private Thread tcpProxyThread;

    // UDP proxy
    private DatagramChannel udpProxyChannel;
    private int udpProxyPort;
    private Thread udpProxyThread;

    // NAT session tables
    private final ConcurrentHashMap<Short, TcpNatSession> tcpSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, UdpNatSession> udpSessions = new ConcurrentHashMap<>();

    // Delay queues for applying effects
    private final PriorityBlockingQueue<DelayPacket> upQueue = new PriorityBlockingQueue<>();
    private final PriorityBlockingQueue<DelayPacket> downQueue = new PriorityBlockingQueue<>();

    // Random number generators for packet loss
    private final Random upRandom = new Random();
    private final Random downRandom = new Random();

    // Output stream to write to TUN
    private volatile FileOutputStream vpnOutputStream;

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

        try {
            // Start local TCP proxy server
            tcpProxyChannel = ServerSocketChannel.open();
            tcpProxyChannel.configureBlocking(false);
            tcpProxyChannel.socket().bind(new InetSocketAddress(0));
            tcpProxyPort = (short) tcpProxyChannel.socket().getLocalPort();
            Log.d(TAG, "TCP proxy listening on port " + (tcpProxyPort & 0xFFFF));

            // Start local UDP proxy server
            udpProxyChannel = DatagramChannel.open();
            udpProxyChannel.configureBlocking(false);
            udpProxyChannel.socket().bind(new InetSocketAddress(0));
            udpProxyPort = udpProxyChannel.socket().getLocalPort();
            Log.d(TAG, "UDP proxy listening on port " + udpProxyPort);
        } catch (IOException e) {
            Log.e(TAG, "Failed to start proxy servers", e);
            stopSelf();
            return;
        }

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

        vpnOutputStream = new FileOutputStream(vpnInterface.getFileDescriptor());
        isRunning.set(true);
        startForeground(NOTIFICATION_ID, createNotification());

        // Start TCP proxy thread
        tcpProxyThread = new Thread(this::runTcpProxy, "TCP-Proxy");
        tcpProxyThread.start();

        // Start UDP proxy thread
        udpProxyThread = new Thread(this::runUdpProxy, "UDP-Proxy");
        udpProxyThread.start();

        // Start delay threads (upstream and downstream)
        upDelayThread = new Thread(() -> runDelayLoop(true), "UpDelay");
        upDelayThread.start();
        downDelayThread = new Thread(() -> runDelayLoop(false), "DownDelay");
        downDelayThread.start();

        // Start main VPN loop
        vpnThread = new Thread(this::runVpnLoop, "VPN-Loop");
        vpnThread.start();
    }

    /**
     * Main VPN loop: reads outgoing packets from TUN device, applies upstream effects,
     * then redirects TCP/UDP to local proxies by rewriting IP headers.
     *
     * Incoming packets (from proxy servers back through TUN) have their headers
     * rewritten to original destinations and downstream effects applied.
     */
    private void runVpnLoop() {
        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
        byte[] packet = new byte[MAX_PACKET_SIZE];

        while (isRunning.get()) {
            try {
                int length = in.read(packet);
                if (length <= 0) {
                    Thread.sleep(1);
                    continue;
                }

                // Only handle IPv4
                if (length < 20 || ((packet[0] >> 4) & 0xF) != 4) {
                    writeToTun(packet, 0, length);
                    continue;
                }

                int protocol = packet[9] & 0xFF;
                int srcIP = getInt(packet, 12);
                int dstIP = getInt(packet, 16);
                int ipHeaderLen = (packet[0] & 0x0F) * 4;

                if (protocol == 6 && length >= ipHeaderLen + 20) {
                    // TCP
                    int srcPort = getShort(packet, ipHeaderLen) & 0xFFFF;
                    int dstPort = getShort(packet, ipHeaderLen + 2) & 0xFFFF;

                    if (srcIP == LOCAL_IP && srcPort == (tcpProxyPort & 0xFFFF)) {
                        // Response from TCP proxy -> rewrite to look like from original destination
                        TcpNatSession session = tcpSessions.get((short) dstPort);
                        if (session != null) {
                            putInt(packet, 12, session.remoteIP);
                            putShort(packet, ipHeaderLen, (short) session.remotePort);
                            putInt(packet, 16, LOCAL_IP);
                            recomputeTcpChecksum(packet, ipHeaderLen, length);
                            // Apply downstream effects
                            sendWithEffects(packet, 0, length, false, session.remoteIP);
                        }
                    } else if (srcIP == LOCAL_IP) {
                        // Outgoing TCP -> redirect to local TCP proxy
                        short localPort = (short) srcPort;
                        TcpNatSession session = tcpSessions.get(localPort);
                        if (session == null || session.remoteIP != dstIP || session.remotePort != dstPort) {
                            session = new TcpNatSession(localPort, dstIP, (short) dstPort);
                            tcpSessions.put(localPort, session);
                        }
                        // Rewrite destination to local proxy.
                        // Set src IP to original dest IP so the kernel routes response back correctly.
                        putInt(packet, 12, dstIP);
                        putInt(packet, 16, LOCAL_IP);
                        putShort(packet, ipHeaderLen + 2, tcpProxyPort);
                        recomputeTcpChecksum(packet, ipHeaderLen, length);
                        // Apply upstream effects
                        sendWithEffects(packet, 0, length, true, dstIP);
                    }
                } else if (protocol == 17 && length >= ipHeaderLen + 8) {
                    // UDP
                    int srcPort = getShort(packet, ipHeaderLen) & 0xFFFF;
                    int dstPort = getShort(packet, ipHeaderLen + 2) & 0xFFFF;

                    if (srcIP == LOCAL_IP && srcPort == udpProxyPort) {
                        // Response from UDP proxy -> rewrite headers
                        // The dstPort tells us which local port originated the request
                        int dstPortVal = getShort(packet, ipHeaderLen + 2) & 0xFFFF;
                        UdpNatSession session = findUdpSessionByLocalPort(dstPortVal);
                        if (session != null) {
                            putInt(packet, 12, session.remoteIP);
                            putShort(packet, ipHeaderLen, (short) session.remotePort);
                            putInt(packet, 16, LOCAL_IP);
                            putShort(packet, ipHeaderLen + 2, (short) session.localPort);
                            recomputeUdpChecksum(packet, ipHeaderLen, length);
                            // Apply downstream effects
                            sendWithEffects(packet, 0, length, false, session.remoteIP);
                        }
                    } else if (srcIP == LOCAL_IP && dstPort == 53) {
                        // DNS query - forward directly via UDP proxy without effects
                        int key = udpSessionKey(srcPort, dstIP, dstPort);
                        UdpNatSession session = udpSessions.get(key);
                        if (session == null) {
                            session = new UdpNatSession(srcIP, srcPort, dstIP, dstPort);
                            udpSessions.put(key, session);
                        }
                        // Redirect to local UDP proxy, keep srcPort for session tracking
                        putInt(packet, 16, LOCAL_IP);
                        putShort(packet, ipHeaderLen + 2, (short) udpProxyPort);
                        recomputeUdpChecksum(packet, ipHeaderLen, length);
                        writeToTun(packet, 0, length);
                    } else if (srcIP == LOCAL_IP) {
                        // Outgoing UDP -> redirect to UDP proxy
                        int key = udpSessionKey(srcPort, dstIP, dstPort);
                        UdpNatSession session = udpSessions.get(key);
                        if (session == null) {
                            session = new UdpNatSession(srcIP, srcPort, dstIP, dstPort);
                            udpSessions.put(key, session);
                        }
                        // Redirect to local UDP proxy, keep srcPort for session tracking
                        putInt(packet, 16, LOCAL_IP);
                        putShort(packet, ipHeaderLen + 2, (short) udpProxyPort);
                        recomputeUdpChecksum(packet, ipHeaderLen, length);
                        // Apply upstream effects
                        sendWithEffects(packet, 0, length, true, dstIP);
                    }
                } else {
                    // Other protocols (ICMP etc) - pass through
                    writeToTun(packet, 0, length);
                }

            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                if (isRunning.get()) {
                    Log.e(TAG, "VPN loop error", e);
                }
                break;
            } catch (Exception e) {
                Log.e(TAG, "VPN loop exception", e);
            }
        }
    }

    /**
     * Apply weak-network effects (drop, delay) then queue the packet for sending.
     * Replicates the APK's DelayThread.Send() logic.
     */
    private void sendWithEffects(byte[] data, int offset, int length, boolean isUpstream, int remoteIP) {
        NetworkConfig config = currentConfig;
        if (config == null) {
            // No config - pass through immediately
            writeToTun(data, offset, length);
            return;
        }

        int protocol = data[9] & 0xFF;
        boolean isTcp = (protocol == 6);
        boolean isUdp = (protocol == 17);

        // Check protocol filter
        int protocolFilter = config.getProtocol();
        boolean shouldApply = (protocolFilter == 3) ||
            (protocolFilter == 1 && isTcp) ||
            (protocolFilter == 2 && isUdp);

        if (!shouldApply) {
            writeToTun(data, offset, length);
            return;
        }

        // Check packet drop (random loss + continuous loss)
        if (needDrop(config, isUpstream)) {
            return; // Drop packet
        }

        // Calculate delay
        int delay = getDelay(config, isUpstream);

        // Copy packet data and queue it
        byte[] packetCopy = new byte[length];
        System.arraycopy(data, offset, packetCopy, 0, length);

        long sendTime = System.currentTimeMillis() + delay;
        DelayPacket delayPacket = new DelayPacket(packetCopy, sendTime);

        if (isUpstream) {
            upQueue.add(delayPacket);
        } else {
            downQueue.add(delayPacket);
        }
    }

    /**
     * Determine if a packet should be dropped.
     * Replicates APK's NetworkConfig.needDrop() logic:
     * - Continuous loss: alternates between pass and drop periods
     * - Random loss rate: percentage-based random drop
     */
    private boolean needDrop(NetworkConfig config, boolean isUpstream) {
        if (isUpstream) {
            int lossTime = config.getUpContinuousLossDropTime();
            int passTime = config.getUpContinuousLossPassTime();
            int lossRate = config.getUpPacketLoss();

            // Continuous loss pattern: creates a periodic drop/pass cycle based on absolute time.
            // Intentionally uses modulo of currentTimeMillis for a repeating pattern (matches APK).
            int totalTime = lossTime + passTime;
            if (totalTime > 0 && passTime > 0 && System.currentTimeMillis() % totalTime > passTime) {
                return true;
            }

            // Random loss rate
            if (lossRate > 0) {
                return upRandom.nextInt(100) < lossRate;
            }
        } else {
            int lossTime = config.getDownContinuousLossDropTime();
            int passTime = config.getDownContinuousLossPassTime();
            int lossRate = config.getDownPacketLoss();

            // Continuous loss pattern: creates a periodic drop/pass cycle based on absolute time.
            int totalTime = lossTime + passTime;
            if (totalTime > 0 && passTime > 0 && System.currentTimeMillis() % totalTime > passTime) {
                return true;
            }

            // Random loss rate
            if (lossRate > 0) {
                return downRandom.nextInt(100) < lossRate;
            }
        }
        return false;
    }

    /**
     * Calculate delay for a packet.
     * Replicates APK's NetworkConfig.getDelay() logic with jitter.
     */
    private int getDelay(NetworkConfig config, boolean isUpstream) {
        if (isUpstream) {
            int delay = config.getUpDelay();
            int jitter = config.getUpJitter();
            if (jitter > 0) {
                delay += upRandom.nextInt(jitter);
            }
            return delay;
        } else {
            int delay = config.getDownDelay();
            int jitter = config.getDownJitter();
            if (jitter > 0) {
                delay += downRandom.nextInt(jitter);
            }
            return delay;
        }
    }

    /**
     * Delay loop: dequeues packets and writes them to TUN at the scheduled time.
     * Also implements bandwidth limiting.
     * Replicates APK's DelayThread.run() logic.
     */
    private void runDelayLoop(boolean isUpstream) {
        PriorityBlockingQueue<DelayPacket> queue = isUpstream ? upQueue : downQueue;
        long lastTime = System.currentTimeMillis();
        int bytesBudget = 0;

        while (isRunning.get()) {
            try {
                NetworkConfig config = currentConfig;
                int bandwidthKbps = 0;
                if (config != null) {
                    bandwidthKbps = isUpstream ? config.getUpBandwidth() : config.getDownBandwidth();
                }

                // Bandwidth budget calculation (bytes per 100ms interval)
                long bytesPerIntervalLong = (bandwidthKbps > 0) ? ((long) bandwidthKbps * 128L * 100L) / 1000L : 0L;
                int bytesPerInterval = (int) Math.min(bytesPerIntervalLong, Integer.MAX_VALUE);

                long now = System.currentTimeMillis();
                while (now - lastTime > 100) {
                    lastTime += 100;
                    if (bytesBudget > 0 || bytesPerInterval <= 0) {
                        bytesBudget = bytesPerInterval;
                    } else {
                        bytesBudget += bytesPerInterval;
                    }
                }

                // Check if we can send
                if (bytesPerInterval > 0 && bytesBudget <= 0) {
                    Thread.sleep(1);
                    continue;
                }

                DelayPacket pkt = queue.peek();
                if (pkt != null && now >= pkt.sendTime) {
                    pkt = queue.poll();
                    if (pkt != null) {
                        writeToTun(pkt.data, 0, pkt.data.length);
                        if (bytesPerInterval > 0) {
                            bytesBudget -= pkt.data.length;
                        }
                    }
                } else {
                    Thread.sleep(1);
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * TCP proxy server: accepts connections redirected from TUN,
     * creates protected real connections to destinations, and forwards data.
     */
    private void runTcpProxy() {
        try {
            Selector selector = Selector.open();
            tcpProxyChannel.register(selector, SelectionKey.OP_ACCEPT);

            while (isRunning.get()) {
                selector.select(100);
                Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();
                    if (!key.isValid()) continue;

                    if (key.isAcceptable()) {
                        SocketChannel client = tcpProxyChannel.accept();
                        if (client != null) {
                            handleTcpConnection(client);
                        }
                    }
                }
            }
            selector.close();
        } catch (Exception e) {
            if (isRunning.get()) {
                Log.e(TAG, "TCP proxy error", e);
            }
        }
    }

    private void handleTcpConnection(final SocketChannel client) {
        new Thread(() -> {
            SocketChannel remote = null;
            try {
                client.configureBlocking(true);
                short clientPort = (short) client.socket().getPort();
                TcpNatSession session = tcpSessions.get(clientPort);
                if (session == null) {
                    client.close();
                    return;
                }

                // Create protected connection to real destination
                remote = SocketChannel.open();
                remote.configureBlocking(true);
                protect(remote.socket());

                String destIP = intToIp(session.remoteIP);
                int destPort = session.remotePort & 0xFFFF;
                remote.connect(new InetSocketAddress(destIP, destPort));

                // Bidirectional forwarding
                final SocketChannel finalRemote = remote;
                final SocketChannel finalClient = client;

                Thread clientToRemote = new Thread(() -> {
                    try {
                        ByteBuffer buf = ByteBuffer.allocate(4096);
                        while (isRunning.get() && finalClient.isOpen() && finalRemote.isOpen()) {
                            buf.clear();
                            int n = finalClient.read(buf);
                            if (n <= 0) break;
                            buf.flip();
                            while (buf.hasRemaining()) {
                                finalRemote.write(buf);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                    try { finalClient.close(); } catch (Exception ignored) {}
                    try { finalRemote.close(); } catch (Exception ignored) {}
                }, "TCP-C2R");
                clientToRemote.start();

                ByteBuffer buf = ByteBuffer.allocate(4096);
                while (isRunning.get() && client.isOpen() && remote.isOpen()) {
                    buf.clear();
                    int n = remote.read(buf);
                    if (n <= 0) break;
                    buf.flip();
                    while (buf.hasRemaining()) {
                        client.write(buf);
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "TCP connection error: " + e.getMessage());
            } finally {
                try { client.close(); } catch (Exception ignored) {}
                if (remote != null) try { remote.close(); } catch (Exception ignored) {}
            }
        }, "TCP-Handler").start();
    }

    /**
     * UDP proxy server: receives UDP packets redirected from TUN,
     * creates protected real sockets to forward to actual destinations.
     */
    private void runUdpProxy() {
        try {
            Selector selector = Selector.open();
            udpProxyChannel.register(selector, SelectionKey.OP_READ);

            while (isRunning.get()) {
                selector.select(100);
                Iterator<SelectionKey> iter = selector.selectedKeys().iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();
                    if (!key.isValid()) continue;

                    if (key.isReadable()) {
                        DatagramChannel ch = (DatagramChannel) key.channel();
                        if (ch == udpProxyChannel) {
                            // Packet from TUN redirected to our proxy
                            ByteBuffer buf = ByteBuffer.allocate(MAX_PACKET_SIZE);
                            InetSocketAddress sender = (InetSocketAddress) ch.receive(buf);
                            if (sender != null) {
                                buf.flip();
                                // Look up session by sender port (the local port that sent the packet)
                                int senderPort = sender.getPort();
                                UdpNatSession session = findUdpSessionByLocalPort(senderPort);
                                if (session != null) {
                                    forwardUdpPacket(buf, session);
                                }
                            }
                        }
                    }
                }
            }
            selector.close();
        } catch (Exception e) {
            if (isRunning.get()) {
                Log.e(TAG, "UDP proxy error", e);
            }
        }
    }

    private void forwardUdpPacket(ByteBuffer data, UdpNatSession session) {
        new Thread(() -> {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                protect(socket);

                byte[] payload = new byte[data.remaining()];
                data.get(payload);

                String destIP = intToIp(session.remoteIP);
                int destPort = session.remotePort;
                InetAddress destAddr = InetAddress.getByName(destIP);

                DatagramPacket sendPkt = new DatagramPacket(payload, payload.length, destAddr, destPort);
                socket.send(sendPkt);

                // Wait for response
                socket.setSoTimeout(5000);
                byte[] respBuf = new byte[MAX_PACKET_SIZE];
                DatagramPacket recvPkt = new DatagramPacket(respBuf, respBuf.length);
                try {
                    socket.receive(recvPkt);

                    // Send response back to the original local port through the UDP proxy channel
                    ByteBuffer resp = ByteBuffer.wrap(respBuf, 0, recvPkt.getLength());
                    InetSocketAddress localAddr = new InetSocketAddress(
                        InetAddress.getByName("10.0.0.2"), session.localPort);
                    udpProxyChannel.send(resp, localAddr);
                } catch (Exception ignored) {
                    // Timeout or error receiving response
                }
            } catch (Exception e) {
                Log.d(TAG, "UDP forward error: " + e.getMessage());
            } finally {
                if (socket != null) socket.close();
            }
        }, "UDP-Fwd").start();
    }

    /**
     * Generate a stable key for UDP session lookup using XOR-based hash to avoid overflow.
     */
    private static int udpSessionKey(int localPort, int remoteIP, int remotePort) {
        int hash = 17;
        hash = hash * 31 + localPort;
        hash = hash * 31 + remoteIP;
        hash = hash * 31 + remotePort;
        return hash;
    }

    /**
     * Find a UDP session by the local port that originated the request.
     */
    private UdpNatSession findUdpSessionByLocalPort(int localPort) {
        for (UdpNatSession session : udpSessions.values()) {
            if (session.localPort == localPort) {
                return session;
            }
        }
        return null;
    }

    private synchronized void writeToTun(byte[] data, int offset, int length) {
        try {
            if (vpnOutputStream != null) {
                vpnOutputStream.write(data, offset, length);
            }
        } catch (IOException e) {
            Log.e(TAG, "Write to TUN failed", e);
        }
    }

    private void stopVpn() {
        isRunning.set(false);
        if (vpnThread != null) {
            vpnThread.interrupt();
            vpnThread = null;
        }
        if (upDelayThread != null) {
            upDelayThread.interrupt();
            upDelayThread = null;
        }
        if (downDelayThread != null) {
            downDelayThread.interrupt();
            downDelayThread = null;
        }
        if (tcpProxyThread != null) {
            tcpProxyThread.interrupt();
            tcpProxyThread = null;
        }
        if (udpProxyThread != null) {
            udpProxyThread.interrupt();
            udpProxyThread = null;
        }
        if (tcpProxyChannel != null) {
            try { tcpProxyChannel.close(); } catch (IOException ignored) {}
            tcpProxyChannel = null;
        }
        if (udpProxyChannel != null) {
            try { udpProxyChannel.close(); } catch (IOException ignored) {}
            udpProxyChannel = null;
        }
        if (vpnInterface != null) {
            try { vpnInterface.close(); } catch (IOException ignored) {}
            vpnInterface = null;
        }
        vpnOutputStream = null;
        tcpSessions.clear();
        udpSessions.clear();
        upQueue.clear();
        downQueue.clear();
        stopForeground(true);
    }

    @Override
    public void onDestroy() {
        stopVpn();
        instance = null;
        super.onDestroy();
    }

    // --- Helper classes ---

    private static class TcpNatSession {
        final short localPort;
        final int remoteIP;
        final short remotePort;

        TcpNatSession(short localPort, int remoteIP, short remotePort) {
            this.localPort = localPort;
            this.remoteIP = remoteIP;
            this.remotePort = remotePort;
        }
    }

    private static class UdpNatSession {
        final int localIP;
        final int localPort;
        final int remoteIP;
        final int remotePort;

        UdpNatSession(int localIP, int localPort, int remoteIP, int remotePort) {
            this.localIP = localIP;
            this.localPort = localPort;
            this.remoteIP = remoteIP;
            this.remotePort = remotePort;
        }
    }

    private static class DelayPacket implements Comparable<DelayPacket> {
        final byte[] data;
        final long sendTime;

        DelayPacket(byte[] data, long sendTime) {
            this.data = data;
            this.sendTime = sendTime;
        }

        @Override
        public int compareTo(DelayPacket other) {
            return Long.compare(this.sendTime, other.sendTime);
        }
    }

    // --- IP utility methods ---

    private static int ipToInt(int a, int b, int c, int d) {
        return ((a & 0xFF) << 24) | ((b & 0xFF) << 16) | ((c & 0xFF) << 8) | (d & 0xFF);
    }

    private static String intToIp(int ip) {
        return ((ip >> 24) & 0xFF) + "." + ((ip >> 16) & 0xFF) + "." +
               ((ip >> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    private static int getInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16) |
               ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static void putInt(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 24) & 0xFF);
        data[offset + 1] = (byte) ((value >> 16) & 0xFF);
        data[offset + 2] = (byte) ((value >> 8) & 0xFF);
        data[offset + 3] = (byte) (value & 0xFF);
    }

    private static short getShort(byte[] data, int offset) {
        return (short) (((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF));
    }

    private static void putShort(byte[] data, int offset, short value) {
        data[offset] = (byte) ((value >> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

    /**
     * Recompute IP header checksum and TCP checksum.
     */
    private static void recomputeTcpChecksum(byte[] packet, int ipHeaderLen, int totalLen) {
        // Zero out IP checksum and recompute
        packet[10] = 0;
        packet[11] = 0;
        int ipChecksum = computeChecksum(packet, 0, ipHeaderLen);
        putShort(packet, 10, (short) ipChecksum);

        // Zero out TCP checksum and recompute with pseudo-header
        int tcpLen = totalLen - ipHeaderLen;
        packet[ipHeaderLen + 16] = 0;
        packet[ipHeaderLen + 17] = 0;

        // Pseudo-header: src IP (4) + dst IP (4) + zero (1) + protocol (1) + TCP length (2)
        long sum = 0;
        // Source IP
        sum += (packet[12] & 0xFF) << 8 | (packet[13] & 0xFF);
        sum += (packet[14] & 0xFF) << 8 | (packet[15] & 0xFF);
        // Dest IP
        sum += (packet[16] & 0xFF) << 8 | (packet[17] & 0xFF);
        sum += (packet[18] & 0xFF) << 8 | (packet[19] & 0xFF);
        // Protocol (6 for TCP)
        sum += 6;
        // TCP length
        sum += tcpLen;

        // TCP data
        for (int i = ipHeaderLen; i < totalLen - 1; i += 2) {
            sum += (packet[i] & 0xFF) << 8 | (packet[i + 1] & 0xFF);
        }
        if (tcpLen % 2 != 0) {
            sum += (packet[totalLen - 1] & 0xFF) << 8;
        }

        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        int tcpChecksum = (int) (~sum & 0xFFFF);
        putShort(packet, ipHeaderLen + 16, (short) tcpChecksum);
    }

    /**
     * Recompute IP header checksum and UDP checksum.
     */
    private static void recomputeUdpChecksum(byte[] packet, int ipHeaderLen, int totalLen) {
        // Zero out IP checksum and recompute
        packet[10] = 0;
        packet[11] = 0;
        int ipChecksum = computeChecksum(packet, 0, ipHeaderLen);
        putShort(packet, 10, (short) ipChecksum);

        // Zero out UDP checksum (optional for IPv4)
        packet[ipHeaderLen + 6] = 0;
        packet[ipHeaderLen + 7] = 0;

        // Compute UDP checksum with pseudo-header
        int udpLen = totalLen - ipHeaderLen;
        long sum = 0;
        // Source IP
        sum += (packet[12] & 0xFF) << 8 | (packet[13] & 0xFF);
        sum += (packet[14] & 0xFF) << 8 | (packet[15] & 0xFF);
        // Dest IP
        sum += (packet[16] & 0xFF) << 8 | (packet[17] & 0xFF);
        sum += (packet[18] & 0xFF) << 8 | (packet[19] & 0xFF);
        // Protocol (17 for UDP)
        sum += 17;
        // UDP length
        sum += udpLen;

        // UDP data
        for (int i = ipHeaderLen; i < totalLen - 1; i += 2) {
            sum += (packet[i] & 0xFF) << 8 | (packet[i + 1] & 0xFF);
        }
        if (udpLen % 2 != 0) {
            sum += (packet[totalLen - 1] & 0xFF) << 8;
        }

        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        int udpChecksum = (int) (~sum & 0xFFFF);
        if (udpChecksum == 0) udpChecksum = 0xFFFF;
        putShort(packet, ipHeaderLen + 6, (short) udpChecksum);
    }

    private static int computeChecksum(byte[] data, int offset, int length) {
        long sum = 0;
        for (int i = offset; i < offset + length - 1; i += 2) {
            sum += (data[i] & 0xFF) << 8 | (data[i + 1] & 0xFF);
        }
        if (length % 2 != 0) {
            sum += (data[offset + length - 1] & 0xFF) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int) (~sum & 0xFFFF);
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
